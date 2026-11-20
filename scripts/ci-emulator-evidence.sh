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
mkdir -p "$EVIDENCE"

set +e
./gradlew :app:connectedDebugAndroidTest --stacktrace
TEST_STATUS=$?

# Capture always, not only on failure: a passing run still needs launch evidence.
adb shell am start -W -n dev.brainrotstopper/dev.brainrotstopper.app.MainActivity || true
sleep 3
adb exec-out screencap -p > "$EVIDENCE/launch.png" || true
adb logcat -d -v threadtime > "$EVIDENCE/logcat.txt" || true
adb shell dumpsys activity activities > "$EVIDENCE/activities.txt" || true

echo "evidence_dir=$EVIDENCE"
echo "instrumentation_status=$TEST_STATUS"
exit "$TEST_STATUS"
