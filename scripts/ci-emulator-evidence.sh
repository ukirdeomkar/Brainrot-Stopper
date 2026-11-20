#!/usr/bin/env bash
#
# Runs the instrumentation smoke test on the CI emulator and captures evidence.
# Called from .github/workflows/android.yml as a single command because the
# android-emulator-runner executes each script line in its own shell.
#
# Usage: ./scripts/ci-emulator-evidence.sh <api-level>

set -uo pipefail

API_LEVEL="${1:?usage: ci-emulator-evidence.sh <api-level>}"
EVIDENCE="qa-evidence/ci-api-${API_LEVEL}"
APK="app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$EVIDENCE"

set +e
./gradlew :app:connectedDebugAndroidTest --stacktrace
TEST_STATUS=$?

# connectedAndroidTest can uninstall the APK when it finishes, so reinstall the
# already-built debug APK before capturing the launch screenshot.
adb install -r "$APK" > "$EVIDENCE/install.txt" 2>&1
adb shell am force-stop dev.brainrotstopper >/dev/null 2>&1
adb shell am start -W -n dev.brainrotstopper/dev.brainrotstopper.app.MainActivity \
  > "$EVIDENCE/launch.txt" 2>&1
sleep 3
adb exec-out screencap -p > "$EVIDENCE/launch.png" || true
adb logcat -d -v threadtime > "$EVIDENCE/logcat.txt" || true
adb shell dumpsys activity activities > "$EVIDENCE/activities.txt" || true

echo "evidence_dir=$EVIDENCE"
echo "instrumentation_status=$TEST_STATUS"
exit "$TEST_STATUS"
