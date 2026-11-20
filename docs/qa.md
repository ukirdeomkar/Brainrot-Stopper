# Server-side QA

The owner does not test on a phone. Every verification runs on a server-side Android
emulator. This page is the operating guide for the harness introduced in OMK-536 and the
template every later QA pass reuses (see [qa-template.md](qa-template.md)).

## Environments

| Environment | Role | Emulator images | Evidence |
|---|---|---|---|
| GitHub Actions (`.github/workflows/android.yml`) | **Authoritative** | API 31 and API 36, `google_apis`, `x86_64` | `qa-evidence-api-31` / `qa-evidence-api-36` artifacts + `connected` test reports |
| VPS KVM (`scripts/qa-vps-emulator.sh`) | Supplementary / interactive | API 31 (`google_apis`, `x86_64`) | `qa-evidence/vps-api31-<UTC timestamp>/` |

A green CI run on both API levels is the gate. The VPS run is proof of the same flows on a
second, independent emulator and is the place to attach screenshots to a QA issue. Do not ask
the owner to install or run anything.

## CI instrumentation job

On every push and pull request to `main`, and on manual `workflow_dispatch`:

1. `build` builds the debug APK, runs JVM unit tests, and uploads the APK.
2. `instrumentation` runs a matrix (`api: [31, 36]`) on `reactivecircus/android-emulator-runner`:
   - installs `platforms;android-37.0` and `build-tools;36.0.0`;
   - enables KVM on the runner;
   - runs `scripts/ci-emulator-evidence.sh <level>`, which runs
     `./gradlew :app:connectedDebugAndroidTest`, launches
     `dev.brainrotstopper/.app.MainActivity`, and captures a screenshot, logcat, and an
     activity dump into `qa-evidence/ci-api-<level>/`;
   - uploads that folder (and the connected test report) as `qa-evidence-api-<level>`,
     even when the test fails.

   The runner executes each `script` line in its own shell, so the evidence step is one
   script file rather than inline multi-line shell. Keep it that way.

The smoke test is
`app/src/androidTest/kotlin/dev/brainrotstopper/app/MainActivitySmokeTest.kt`. It launches the
real `MainActivity` and asserts the wired domain modules render the expected status text. Keep
it fast and deterministic; feature issues add their own instrumentation tests next to it.

Check a run:

```bash
# Last run for the branch/PR (requires GitHub auth)
gh run list --workflow android.yml --limit 5
# Download the evidence from a run
gh run download <run-id> -n qa-evidence-api-31
```

## VPS KVM harness

The VPS has 2 vCPU, 3.7 GB RAM and 6 GB swap, and exposes `/dev/kvm`. It has no JDK or Android
SDK by default.

### One-time setup

Requirements: JDK 17, `curl`, `unzip`, about 6 GB free disk, and KVM access.

```bash
# Put the SDK on real disk. Never install it under /tmp: /tmp is a 1.9 GB tmpfs here.
export ANDROID_SDK_ROOT="$HOME/android-sdk"
./scripts/qa-vps-setup.sh

# One-time KVM access for the service user, then a fresh login (or use `sg kvm` below).
sudo usermod -aG kvm "$USER"
```

`qa-vps-setup.sh` installs `platform-tools`, `emulator`, `platforms;android-37.0`,
`build-tools;36.0.0`, and the API 31 Google APIs x86_64 system image, then creates the
`qa_api_31` AVD. CI installs API 36; the VPS does not, to keep the disk budget.

### Run a QA pass

Build or download the debug APK first (CI publishes `brainrot-stopper-debug-apk`), then:

```bash
APK=/path/to/app-debug.apk sg kvm -c './scripts/qa-vps-emulator.sh'
```

If the instrumentation test APK is present at
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, the harness installs it and
runs `MainActivitySmokeTest` too. Otherwise it runs the adb smoke flows only.

Configuration is environment-driven:

| Variable | Default | Meaning |
|---|---|---|
| `APK` | `app/build/outputs/apk/debug/app-debug.apk` | APK to install |
| `TEST_APK` | auto-detected androidTest APK | Instrumentation APK; skipped if absent |
| `API_LEVEL` | `31` | System image / AVD API level |
| `SYSTEM_IMAGE` | `system-images;android-<API>;google_apis;x86_64` | Image used if the AVD is created |
| `AVD_NAME` | `qa_api_<API>` | AVD name |
| `EVIDENCE_ROOT` | `qa-evidence` | Evidence parent folder |
| `BOOT_TIMEOUT` | `420` | Seconds to wait for device + boot |
| `USERDATA_MB` | `2048` | Sparse userdata image size |
| `WIPE_DATA` | `1` | Recreate the userdata image for a clean run |
| `KEEP_EMULATOR` | `0` | `1` leaves the emulator running |
| `START_EMULATOR` | `1` | `0` attaches to an already running emulator |

### What the harness does

1. Checks `/dev/kvm`, adb, the emulator, and the AVD.
2. Creates a sparse 2 GB ext4 userdata image and boots a headless emulator
   (`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`).
3. Waits for `sys.boot_completed`, disables animations.
4. Installs the APK, launches `dev.brainrotstopper/.app.MainActivity`.
5. Drives basic flows with adb: launch, HOME, relaunch, recents, final launch.
6. Runs the instrumentation smoke test when its APK is available.
7. Captures evidence, then stops the emulator (`adb emu kill`).

### Evidence layout

```
qa-evidence/vps-api31-<UTC timestamp>/
├── emulator.log          # emulator boot log
├── host-before.txt       # CPU, RAM, KVM, APK
├── host-runtime.txt      # RAM/swap and qemu RSS during the run
├── install.txt
├── launch.txt            # am start -W result
├── package-path.txt
├── 01-launch.png
├── 02-home.png
├── 03-relaunch.png
├── 04-recents.png
├── 05-final.png
├── 99-exit.png           # last frame, including on failure
├── instrumentation.txt   # when the test APK is present
├── logcat.txt
├── activities.txt
├── windows.txt
├── meminfo.txt
├── getprop.txt
└── window-dump.xml
```

`qa-evidence/` is git-ignored. Attach the screenshots and `instrumentation.txt` to the QA
issue; never commit large binary evidence to the repo.

### Measured limitations on this VPS (2026-11-20)

These are measurements from the first run, not guesses:

- **Boot time:** about 3 minutes (175 s) from emulator start to `sys.boot_completed=1`.
  The instrumentation test then passed (`OK (1 test)`, 14.33 s).
- **Memory:** the host had ~0.5–1.2 GB free with the emulator running at 2 GB guest RAM,
  so the guest swapped heavily. A transient **"System UI isn't responding"** dialog appeared
  on one screenshot; `input tap` on **Wait** dismissed it and the app was fine. Treat this as
  a harness-host limitation, not an app failure.
- **Disk:** the API 31 x86_64 image asks for a 6 GB userdata partition (7.4 GB free needed by
  the emulator's preallocation check). The VPS had ~3.9 GB free, so the harness passes
  `-data` with a pre-created **sparse 2 GB ext4** image. Do not remove that workaround.
- **SDK location:** `/tmp` is a 1.9 GB tmpfs; installing the SDK there fails with
  `Disk quota exceeded`. Keep the SDK on `/` (e.g. `$HOME/android-sdk`).
- **API 36 is CI-only.** Both API levels are covered by CI; the VPS covers API 31. Running
  two system images and two boots on this box is not reliable within the RAM budget.

Because of the memory pressure, **CI is the authoritative environment**. The VPS harness is
still useful for screenshots and quick adb checks, but a VPS-only pass is not a release gate.

## Reusable QA comment / checklist

Copy the template from [qa-template.md](qa-template.md) into the QA issue. Fill every field;
"should work" is not evidence. A QA pass is complete only when the command output and
evidence links are recorded.

## Residual risks (state them in every QA comment)

- Emulators cover AOSP/Google APIs behaviour, not OEM skins (Xiaomi/Oppo/Vivo/Samsung) and not
  real-device battery/Doze behaviour.
- The current instrumentation test covers app launch and scaffold wiring only. Feature issues
  (blocking, timers, password) must add their own tests and adb flows.
- Accessibility, Usage Access, notification-listener, and overlay behaviour need the permissions
  to be granted through Settings; a future harness step should script `settings put secure`
  only where Android allows it, otherwise the pass must use `adb shell uiautomator`/manual
  emulator interaction.
- CI emulator boots are the flakiest part of Android CI. If a run fails only in the emulator
  job, re-run that job and record both the failure and the successful re-run.
