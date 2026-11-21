#!/usr/bin/env bash
#
# Launches the built debug APK on a CI emulator and captures QA evidence.
#
# Called from .github/workflows/android.yml as a single command because the
# android-emulator-runner executes each script line in its own shell.
#
# The workflow assembles app-debug.apk before this runs. On branches without
# androidTest sources the connected instrumentation run is an accepted no-op;
# the device gate is install + cold launch + force-stop + relaunch with no
# FATAL EXCEPTION in logcat.
#
# Usage: ./scripts/ci-emulator-evidence.sh <api-level>

set -uo pipefail

API_LEVEL="${1:?usage: ci-emulator-evidence.sh <api-level>}"
EVIDENCE="qa-evidence/ci-api-${API_LEVEL}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="dev.brainrotstopper"
ACTIVITY="${PKG}/dev.brainrotstopper.app.MainActivity"

mkdir -p "$EVIDENCE"

FAILED=0
fail() {
  echo "FAIL: $*" >&2
  FAILED=1
}

# --- Device identity -------------------------------------------------------
{
  echo "ro.build.version.sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "ro.build.version.release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "ro.product.cpu.abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "ro.product.model=$(adb shell getprop ro.product.model | tr -d '\r')"
} > "$EVIDENCE/device-sdk.txt" 2>&1 || fail "could not read device properties"

# --- Optional instrumentation tests ---------------------------------------
# No androidTest sources exist on every branch; treat that as a no-op instead
# of asking Gradle to run an unconfigured test task.
if [ -d app/src/androidTest ] && [ -n "$(find app/src/androidTest -type f -print -quit)" ]; then
  ./gradlew :app:connectedDebugAndroidTest --stacktrace \
    > "$EVIDENCE/connected-android-test.txt" 2>&1
  TEST_STATUS=$?
  echo "connectedDebugAndroidTest exited ${TEST_STATUS}" >> "$EVIDENCE/connected-android-test.txt"
  if [ "$TEST_STATUS" -ne 0 ]; then
    fail "connectedDebugAndroidTest failed (see connected-android-test.txt)"
  fi
else
  echo "No androidTest sources in app/src/androidTest; skipping connectedDebugAndroidTest (accepted no-op)." \
    | tee "$EVIDENCE/connected-android-test.txt"
fi

# --- Install ---------------------------------------------------------------
if [ ! -f "$APK" ]; then
  fail "APK missing at $APK"
else
  adb install -r "$APK" > "$EVIDENCE/install.txt" 2>&1
  INSTALL_STATUS=$?
  if [ "$INSTALL_STATUS" -ne 0 ] || ! grep -qi 'Success' "$EVIDENCE/install.txt"; then
    fail "apk install failed (exit ${INSTALL_STATUS}, see install.txt)"
  fi
fi

# --- Cold launch, force-stop, relaunch -------------------------------------
adb logcat -c > "$EVIDENCE/logcat-clear.txt" 2>&1 || true

adb shell am force-stop "$PKG" > "$EVIDENCE/force-stop-first.txt" 2>&1 || true
adb shell am start -W -n "$ACTIVITY" > "$EVIDENCE/first-launch.txt" 2>&1
if ! grep -q 'Status: ok' "$EVIDENCE/first-launch.txt"; then
  fail "first launch did not report Status: ok (see first-launch.txt)"
fi

adb shell am force-stop "$PKG" > "$EVIDENCE/force-stop.txt" 2>&1
sleep 2

adb shell am start -W -n "$ACTIVITY" > "$EVIDENCE/launch.txt" 2>&1
if ! grep -q 'Status: ok' "$EVIDENCE/launch.txt"; then
  fail "relaunch did not report Status: ok (see launch.txt)"
fi
sleep 3

adb exec-out screencap -p > "$EVIDENCE/launch.png" 2>/dev/null || true
if [ ! -s "$EVIDENCE/launch.png" ]; then
  fail "screenshot launch.png was not captured"
fi

adb shell pidof "$PKG" > "$EVIDENCE/pid.txt" 2>&1
if [ ! -s "$EVIDENCE/pid.txt" ] || ! tr -d '\r' < "$EVIDENCE/pid.txt" | grep -q '[0-9]'; then
  fail "app process is not running after relaunch (see pid.txt)"
fi

adb shell dumpsys activity activities > "$EVIDENCE/activities.txt" 2>&1 || true

# --- Logs ------------------------------------------------------------------
adb logcat -d -v threadtime > "$EVIDENCE/logcat.txt" 2>&1 || true
grep -n -E 'FATAL EXCEPTION' "$EVIDENCE/logcat.txt" > "$EVIDENCE/fatal-exceptions.txt" 2>&1 || true
if [ -s "$EVIDENCE/fatal-exceptions.txt" ]; then
  fail "FATAL EXCEPTION found in logcat (see fatal-exceptions.txt)"
else
  echo "No FATAL EXCEPTION in logcat." > "$EVIDENCE/fatal-exceptions.txt"
fi

echo "evidence_dir=$EVIDENCE"
echo "api_level=${API_LEVEL}"
echo "result=$([ "$FAILED" -eq 0 ] && echo PASS || echo FAIL)"
exit "$FAILED"
