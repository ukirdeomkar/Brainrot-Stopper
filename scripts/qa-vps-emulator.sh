#!/usr/bin/env bash
#
# VPS QA harness: boot a headless Android emulator on KVM, install a debug APK,
# drive basic adb flows, run the instrumentation smoke test when its APK exists,
# and write screenshots + logcat + device dumps under qa-evidence/.
#
# CI is the authoritative environment (API 31 + API 36). This harness is the
# supplementary server-side proof on the 2 vCPU / 3.7 GB VPS. See docs/qa.md.
#
# Usage:
#   APK=app/build/outputs/apk/debug/app-debug.apk \
#     sg kvm -c './scripts/qa-vps-emulator.sh'
#
# All configuration is environment-driven; see the defaults below.

set -euo pipefail

log() { printf '[qa-vps] %s\n' "$*"; }
die() { printf '[qa-vps] ERROR: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$SDK_ROOT/avd}"

ADB="${ADB:-$SDK_ROOT/platform-tools/adb}"
EMULATOR="${EMULATOR:-$SDK_ROOT/emulator/emulator}"
AVDMANAGER="${AVDMANAGER:-$SDK_ROOT/cmdline-tools/latest/bin/avdmanager}"
MKFS_EXT4="${MKFS_EXT4:-$SDK_ROOT/emulator/bin64/mkfs.ext4}"

API_LEVEL="${API_LEVEL:-31}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-${API_LEVEL};google_apis;x86_64}"
AVD_NAME="${AVD_NAME:-qa_api_${API_LEVEL}}"
DEVICE_PROFILE="${DEVICE_PROFILE:-pixel_2}"

APP_PACKAGE="${APP_PACKAGE:-dev.brainrotstopper}"
APP_ACTIVITY="${APP_ACTIVITY:-dev.brainrotstopper.app.MainActivity}"
TEST_PACKAGE="${TEST_PACKAGE:-${APP_PACKAGE}.test}"
TEST_RUNNER="${TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
TEST_CLASS="${TEST_CLASS:-dev.brainrotstopper.app.MainActivitySmokeTest}"

APK="${APK:-}"
TEST_APK="${TEST_APK:-}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$REPO_ROOT/qa-evidence}"

BOOT_TIMEOUT="${BOOT_TIMEOUT:-420}"
START_EMULATOR="${START_EMULATOR:-1}"
KEEP_EMULATOR="${KEEP_EMULATOR:-0}"
WIPE_DATA="${WIPE_DATA:-1}"
EMULATOR_CORES="${EMULATOR_CORES:-2}"
USERDATA_MB="${USERDATA_MB:-2048}"

if [ -z "$APK" ]; then
  for candidate in \
    "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" \
    "$REPO_ROOT/app-debug.apk"
  do
    if [ -f "$candidate" ]; then APK="$candidate"; break; fi
  done
fi
[ -n "$APK" ] || die "No APK found. Build one (./gradlew assembleDebug) or set APK=/path/to/app-debug.apk."
[ -f "$APK" ] || die "APK does not exist: $APK"

if [ -z "$TEST_APK" ]; then
  candidate="$REPO_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [ -f "$candidate" ] && TEST_APK="$candidate"
fi

[ -x "$ADB" ] || die "adb not found at $ADB (run scripts/qa-vps-setup.sh)."
[ -x "$EMULATOR" ] || die "emulator not found at $EMULATOR (run scripts/qa-vps-setup.sh)."
[ -x "$AVDMANAGER" ] || die "avdmanager not found at $AVDMANAGER (run scripts/qa-vps-setup.sh)."

# /dev/kvm is root:kvm 0660 on the VPS. If the service user is in the kvm group
# but this login session predates that change, re-exec once through sg.
if [ ! -r /dev/kvm ]; then
  if getent group kvm | grep -qw "$(id -un)"; then
    log "KVM not readable in this session; re-executing via sg kvm"
    exec sg kvm -c "$(printf '%q ' "$0" "$@")"
  fi
  die "/dev/kvm is not readable. Set up access once: sudo usermod -aG kvm \$USER, then log in again (or run via: sg kvm -c './scripts/qa-vps-emulator.sh')."
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="$EVIDENCE_ROOT/vps-api${API_LEVEL}-${STAMP}"
mkdir -p "$EVIDENCE_DIR"

SERIAL=""
EMU_PID=""
STARTED_EMULATOR=0

on_exit() {
  local status=$?
  set +e
  if [ -n "$SERIAL" ]; then
    "$ADB" -s "$SERIAL" logcat -d -v threadtime > "$EVIDENCE_DIR/logcat.txt" 2>&1 || true
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/99-exit.png" 2>&1 || true
  fi
  {
    echo "exit_status=$status"
    echo "serial=$SERIAL"
    echo "evidence=$EVIDENCE_DIR"
    uname -a
    nproc
    free -m
    swapon --show 2>/dev/null || true
  } > "$EVIDENCE_DIR/exit.txt" 2>&1
  if [ "$STARTED_EMULATOR" = "1" ] && [ "$KEEP_EMULATOR" != "1" ] && [ -n "$SERIAL" ]; then
    log "Stopping emulator $SERIAL"
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  exit $status
}
trap on_exit EXIT

log "SDK: $SDK_ROOT"
log "APK: $APK"
log "Evidence: $EVIDENCE_DIR"

{
  echo "== host =="
  uname -a
  echo "cpus=$(nproc)"
  free -h
  echo "== kvm =="
  ls -l /dev/kvm
  echo "== apk =="
  ls -l "$APK"
  [ -n "$TEST_APK" ] && ls -l "$TEST_APK"
} > "$EVIDENCE_DIR/host-before.txt" 2>&1

if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
  log "Creating AVD $AVD_NAME from $SYSTEM_IMAGE"
  echo no | "$AVDMANAGER" create avd --force -n "$AVD_NAME" \
    -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE" >> "$EVIDENCE_DIR/avd-create.log" 2>&1
fi
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
DATA_IMG="$AVD_DIR/userdata-qa.img"

if [ "$START_EMULATOR" = "1" ]; then
  # API 31 x86_64 asks for a 6 GB userdata image (7.4 GB free is unavailable on
  # this VPS). A pre-created sparse ext4 image passed with -data avoids that
  # preallocation check and keeps actual disk use small.
  if [ "$WIPE_DATA" = "1" ] || [ ! -f "$DATA_IMG" ]; then
    log "Creating ${USERDATA_MB}M sparse userdata image"
    rm -f "$DATA_IMG"
    truncate -s "${USERDATA_MB}M" "$DATA_IMG"
    if [ -x "$MKFS_EXT4" ]; then
      "$MKFS_EXT4" -q -F -L userdata "$DATA_IMG" >> "$EVIDENCE_DIR/avd-create.log" 2>&1
    else
      mkfs.ext4 -q -F -L userdata "$DATA_IMG" >> "$EVIDENCE_DIR/avd-create.log" 2>&1
    fi
  fi

  log "Starting headless emulator (API $API_LEVEL)"
  nohup "$EMULATOR" -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim -no-snapshot -no-snapshot-save \
    -gpu swiftshader_indirect -cores "$EMULATOR_CORES" \
    -data "$DATA_IMG" \
    > "$EVIDENCE_DIR/emulator.log" 2>&1 &
  EMU_PID=$!
  STARTED_EMULATOR=1
  log "Emulator pid $EMU_PID"
fi

"$ADB" start-server >/dev/null 2>&1 || true

deadline=$((SECONDS + BOOT_TIMEOUT))
log "Waiting for device (timeout ${BOOT_TIMEOUT}s)"
while [ "$SECONDS" -lt "$deadline" ]; do
  SERIAL="$("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device/{print $1; exit}')"
  if [ -n "$SERIAL" ]; then break; fi
  if [ "$STARTED_EMULATOR" = "1" ] && ! kill -0 "$EMU_PID" 2>/dev/null; then
    tail -40 "$EVIDENCE_DIR/emulator.log" >&2 || true
    die "Emulator exited before adb connected. See $EVIDENCE_DIR/emulator.log"
  fi
  sleep 3
done
[ -n "$SERIAL" ] || die "No emulator device appeared within ${BOOT_TIMEOUT}s."
log "Device: $SERIAL"

log "Waiting for sys.boot_completed"
while [ "$SECONDS" -lt "$deadline" ]; do
  booted="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$booted" = "1" ]; then break; fi
  sleep 3
done
[ "${booted:-}" = "1" ] || die "Emulator did not finish booting within ${BOOT_TIMEOUT}s."
log "Boot complete"

"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 || true
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 || true
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 || true

log "Installing APK"
"$ADB" -s "$SERIAL" install -r "$APK" > "$EVIDENCE_DIR/install.txt" 2>&1
"$ADB" -s "$SERIAL" shell pm path "$APP_PACKAGE" > "$EVIDENCE_DIR/package-path.txt" 2>&1 || true

log "Launching $APP_PACKAGE/$APP_ACTIVITY"
"$ADB" -s "$SERIAL" shell am force-stop "$APP_PACKAGE" || true
"$ADB" -s "$SERIAL" shell am start -W -n "$APP_PACKAGE/$APP_ACTIVITY" \
  > "$EVIDENCE_DIR/launch.txt" 2>&1
sleep 4
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/01-launch.png"

log "Driving basic flows: home, relaunch, recents"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME || true
sleep 2
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/02-home.png"
"$ADB" -s "$SERIAL" shell am start -W -n "$APP_PACKAGE/$APP_ACTIVITY" >/dev/null 2>&1 || true
sleep 2
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/03-relaunch.png"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_APP_SWITCH || true
sleep 1
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/04-recents.png"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_BACK || true
"$ADB" -s "$SERIAL" shell am start -W -n "$APP_PACKAGE/$APP_ACTIVITY" >/dev/null 2>&1 || true
sleep 2
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/05-final.png"

"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/qa-window.xml >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" pull /sdcard/qa-window.xml "$EVIDENCE_DIR/window-dump.xml" >/dev/null 2>&1 || true

TEST_STATUS=0
if [ -n "$TEST_APK" ] && [ -f "$TEST_APK" ]; then
  log "Running instrumentation $TEST_PACKAGE/$TEST_RUNNER ($TEST_CLASS)"
  "$ADB" -s "$SERIAL" install -r "$TEST_APK" > "$EVIDENCE_DIR/test-install.txt" 2>&1
  set +e
  "$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$TEST_CLASS" \
    "$TEST_PACKAGE/$TEST_RUNNER" 2>&1 | tee "$EVIDENCE_DIR/instrumentation.txt"
  TEST_STATUS=${PIPESTATUS[0]}
  set -e
else
  log "No instrumentation APK found; skipped instrumentation test (smoke flows only)"
fi

"$ADB" -s "$SERIAL" shell dumpsys activity activities > "$EVIDENCE_DIR/activities.txt" 2>&1 || true
"$ADB" -s "$SERIAL" shell dumpsys window windows > "$EVIDENCE_DIR/windows.txt" 2>&1 || true
"$ADB" -s "$SERIAL" shell dumpsys meminfo "$APP_PACKAGE" > "$EVIDENCE_DIR/meminfo.txt" 2>&1 || true
"$ADB" -s "$SERIAL" shell getprop > "$EVIDENCE_DIR/getprop.txt" 2>&1 || true
{
  echo "== free -m =="
  free -m
  echo "== swap =="
  swapon --show 2>/dev/null || true
  echo "== qemu =="
  ps -o pid,rss,vsz,etime,pcpu,cmd -C qemu-system-x86_64 2>/dev/null | head || true
} > "$EVIDENCE_DIR/host-runtime.txt" 2>&1

log "Evidence written to $EVIDENCE_DIR"
ls -la "$EVIDENCE_DIR"

if [ "$TEST_STATUS" -ne 0 ]; then
  die "Instrumentation test failed (exit $TEST_STATUS). See $EVIDENCE_DIR/instrumentation.txt"
fi
log "PASS: launch + basic flows + instrumentation smoke"
