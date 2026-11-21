#!/usr/bin/env bash
#
# One-time VPS setup for the server-side QA harness: Android command-line tools,
# platform-tools, emulator, and the API 31 x86_64 Google APIs system image.
#
# Requirements: JDK 17+ on PATH (or JAVA_HOME), curl, unzip, ~6 GB free disk,
# and KVM access for the user that runs the harness.
#
# Disk note: the previous scaffold spike installed the SDK under /tmp, which is a
# 1.9 GB tmpfs on this VPS. Put the SDK on the real disk instead; the defaults
# below do that. If /tmp is tmpfs, the setup will refuse to install there unless
# QA_ALLOW_TMPFS=1 is set.

set -euo pipefail

log() { printf '[qa-setup] %s\n' "$*"; }
die() { printf '[qa-setup] ERROR: %s\n' "$*" >&2; exit 1; }

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
CMDLINE_TOOLS_ZIP_URL="${CMDLINE_TOOLS_ZIP_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
IMAGES="${QA_IMAGES:-system-images;android-31;google_apis;x86_64}"
BOOT_API="${API_LEVEL:-31}"

if command -v java >/dev/null 2>&1; then
  JAVA_MAJOR="$(java -version 2>&1 | awk -F '"' '/version/ {split($2,a,"."); print a[1]}')"
  [ "${JAVA_MAJOR:-0}" -ge 17 ] || die "JDK 17+ required (found ${JAVA_MAJOR:-unknown}). Set JAVA_HOME to a JDK 17 install."
else
  die "java not found. Install a JDK 17 (e.g. Temurin) and put it on PATH."
fi

if [ -n "${QA_ALLOW_TMPFS:-}" ] || [ "${QA_ALLOW_TMPFS:-0}" = "1" ]; then
  :
elif df -T "$(dirname "$SDK_ROOT")" 2>/dev/null | awk 'NR==2 {print $2}' | grep -qx tmpfs; then
  die "$(dirname "$SDK_ROOT") is tmpfs (RAM-backed). Install the SDK on real disk, e.g. ANDROID_SDK_ROOT=\$HOME/android-sdk."
fi

mkdir -p "$SDK_ROOT/cmdline-tools"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  log "Downloading Android command-line tools"
  tmp_zip="$(mktemp -d)/cmdline-tools.zip"
  curl -fsSL "$CMDLINE_TOOLS_ZIP_URL" -o "$tmp_zip"
  unzip -q "$tmp_zip" -d "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$(dirname "$tmp_zip")"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

log "Accepting SDK licenses"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# shellcheck disable=SC2086
log "Installing: platform-tools emulator platforms;android-37.0 build-tools;36.0.0 $IMAGES"
"$SDKMANAGER" --install "platform-tools" "emulator" \
  "platforms;android-37.0" "build-tools;36.0.0" $IMAGES

if ! getent group kvm >/dev/null 2>&1; then
  log "Warning: no kvm group. Ensure /dev/kvm is usable by the QA user."
elif ! getent group kvm | grep -qw "$(id -un)"; then
  log "Add the QA user to kvm: sudo usermod -aG kvm $(id -un)  (then log in again, or use sg kvm)"
fi
[ -r /dev/kvm ] && log "KVM available" || log "Warning: /dev/kvm is not readable in this session"

log "Creating API $BOOT_API AVD if missing"
"$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: qa_api_$BOOT_API" || {
  echo no | "$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --force \
    -n "qa_api_$BOOT_API" -k "system-images;android-$BOOT_API;google_apis;x86_64" -d pixel_2
}

log "Done. SDK: $SDK_ROOT"
log "Next: APK=app/build/outputs/apk/debug/app-debug.apk sg kvm -c './scripts/qa-vps-emulator.sh'"
