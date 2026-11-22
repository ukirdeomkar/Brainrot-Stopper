# Foreground service and resilience (M5)

> Operating contract for the `specialUse` foreground service, the boot/update receivers, and
> the "blocking engine running" self-check. Design rationale lives in
> [design.md](design.md) §1.2 and §2.3; this page is what QA verifies.

## Why a foreground service

The blocking engine must not depend on an Activity being alive. M4 put the engine inside the
`AccessibilityService`, which survives Activity death but still lets Android reclaim the
process under memory pressure. M5 adds `BlockingForegroundService`:

- it owns the process lifetime of the single `BlockingEngine` (one `BlockingStateCache` plus
  the `UnlockFlow`) and keeps the snapshot warm;
- it shows the ongoing notification Android requires for a foreground service;
- it restarts after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`;
- it publishes `BlockingRuntime.status()` for the shell/settings UI.

The engine is process-scoped, not service-instance-scoped. `BlockingEngineProvider` creates it
once. The foreground service and the AccessibilityService share the same cache, so an unlock
grant written by one is immediately visible to the other. If the system denies a foreground
start, the AccessibilityService still creates the engine on its own: blocking degrades, it
does not disappear.

## Components

| Component | File | Responsibility |
|---|---|---|
| `BlockingForegroundService` | `feature/blocking/.../BlockingForegroundService.kt` | Foreground lifecycle, notification, 60 s state refresh, screen on/off refresh |
| `BlockingBootReceiver` | `feature/blocking/.../BlockingBootReceiver.kt` | Restart after boot/update; rebind the notification listener |
| `BlockingEngineProvider` | `feature/blocking/.../BlockingEngine.kt` | Creates the one process-wide engine |
| `BlockingRuntime` | `feature/blocking/.../BlockingRuntime.kt` | Self-check status, cached verdict, unlock hook |
| `BlockingEngineStatus` | `feature/blocking/.../BlockingEngineStatus.kt` | Pure status model shown by the UI |

## Start paths

The service starts from every context that can legitimately start it:

1. **Visible Activity** — `MainActivity.onCreate` calls
   `BlockingForegroundService.start(this, ACTIVITY)`. This is the user-initiated path that
   API 31+ background-start restrictions always allow.
2. **AccessibilityService** — `BrainrotAccessibilityService.onCreate` asks for the service
   with reason `ACCESSIBILITY`. If Android denies it, the exception is caught and a
   high-priority "Blocking is not running" notification opens the app instead.
3. **Boot / package replaced** — `BlockingBootReceiver` handles `BOOT_COMPLETED`,
   `MY_PACKAGE_REPLACED`, and the OEM `QUICKBOOT_POWERON` alias.

`START_STICKY` and `android:stopWithTask="false"` keep the service alive when the user swipes
the task away. Only an explicit `stopService` (M8 gates that behind the password) or process
death ends it.

## Permissions and manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".BlockingForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:stopWithTask="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="attention_guard_blocking_engine" />
</service>
```

`specialUse` is the correct type: the service exists to enforce an on-device attention policy,
not to download, play media, or track location. The `<property>` subtype is what Play would
ask to justify; sideload/F-Droid does not require it, but recording it keeps the manifest
honest and self-describing.

## API 31–36 behaviour

| API | Change | What this app does |
|---|---|---|
| 31 (12) | Background foreground-service starts are restricted. `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are explicit exemptions. `ForegroundServiceStartNotAllowedException` exists. | Starts from the visible Activity, the AccessibilityService, and the boot/update receiver. Catches a denied start and posts a reopen notification. |
| 32 (12L) | No new foreground-service rule that affects this app. | Same as API 31. |
| 33 (13) | `POST_NOTIFICATIONS` becomes a runtime permission. A denied permission hides the foreground notification but does not stop the service. | Onboarding requests the permission; the service runs either way and the self-check still reports state. |
| 34 (14) | `foregroundServiceType` is mandatory; `specialUse` plus `FOREGROUND_SERVICE_SPECIAL_USE` is the type for policy/attention services. | Declares `specialUse`, calls the three-argument `startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. |
| 35 (15) | Tighter background starts and time limits for `dataSync`; `specialUse` is not one of the boot-denied types. | Keeps `specialUse`; never uses `dataSync`; boot/update start remains allowed. |
| 36 (16) | Continued foreground/boot restrictions and stronger user controls. | Same strategy; the self-check exposes "blocking engine not running" after a denied start or a kill. |

On API 31–33 the framework does not know the `specialUse` type. The manifest attribute is
ignored there and the service uses the two-argument `startForeground`, which is valid. API 34+
takes the typed path. CI runs the same code on API 31 and API 36, so both branches are
exercised.

## Self-check

`BlockingRuntime.status()` returns `BlockingEngineStatus`:

- `isEngineRunning` — the engine exists **and** has a loaded snapshot. This is the
  "blocking engine running" check.
- `foregroundServiceRunning` — the foreground service reported start and has not been
  destroyed.
- `lastRefreshAtEpochMillis` — when the cached rules/grants/usage were last loaded.
- `lastStartReason` / `lastStartAtEpochMillis` — why and when the service last started
  (`BOOT_COMPLETED`, `PACKAGE_REPLACED`, `ACTIVITY`, `ACCESSIBILITY`, `MANUAL`).

`MainActivity` renders this on the shell entry point. After a reboot the line reads
`Last start: after reboot`; after a package update it reads `after app update`. That is the
QA-visible proof that the restart path fired, not just that the process happened to be alive.

The reason is recorded once per service instance. Opening the app after a boot restart starts
the service again with reason `ACTIVITY`, but the self-check keeps `BOOT_COMPLETED`; the same
applies to a package update. Only a new service instance (process death, boot, or update)
records a new reason.

## Verification (server-side only)

```bash
# Service is running and is foreground
adb shell dumpsys activity services dev.brainrotstopper | grep -A4 BlockingForegroundService
adb shell dumpsys activity processes | grep -i foreground

# Notification is present (grant POST_NOTIFICATIONS first on API 33+)
adb shell pm grant dev.brainrotstopper android.permission.POST_NOTIFICATIONS
adb shell dumpsys notification --noredact | grep -i "blocking engine"

# Boot restart: reboot, wait for boot_completed, then check the service and notification
adb reboot
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb shell dumpsys activity services dev.brainrotstopper | grep BlockingForegroundService

# Activity death: start the app, leave it, then confirm the service and engine still run
adb shell am start -W -n dev.brainrotstopper/dev.brainrotstopper.app.MainActivity
adb shell input keyevent KEYCODE_HOME
adb shell am force-stop dev.brainrotstopper   # stops the process; the FGS restarts only on boot/update

# Update path
adb shell am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -p dev.brainrotstopper
adb shell dumpsys activity services dev.brainrotstopper | grep BlockingForegroundService
```

Instrumentation coverage in `ForegroundServiceTest` starts the service through the shell
(the same path the system uses), asserts the notification and the self-check, closes an
Activity and re-checks, and exercises the boot/update receiver. A real reboot is QA's device
path because instrumentation cannot reboot the emulator under test.

## Known limits

- **Force-stop is not recoverable.** `adb shell am force-stop`, Settings → Force stop, and some
  OEM "deep clean" actions put the app in a stopped state; Android will not deliver
  `BOOT_COMPLETED` or any broadcast until the user opens the app again. The app cannot bypass
  this without Device Owner.
- **User-disabled AccessibilityService.** The foreground service can be running while
  Accessibility is off. The self-check reports both lines, so the UI can say blocking is not
  effective. Android gives no API to re-enable the service programmatically.
- **`POST_NOTIFICATIONS` denied.** The service still runs (Android 13+), but the mandatory
  notification is hidden. Onboarding asks for the permission; the self-check does not depend
  on it.
- **OEM task killers.** Xiaomi/Oppo/Vivo/Samsung can still kill the process; onboarding asks
  for a battery-optimization exemption, and the boot receiver is the recovery path.
