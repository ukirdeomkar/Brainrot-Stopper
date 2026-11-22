# Brainrot Stopper

**Open-source attention guard for Android 12+.** Block short-form and social apps during chosen hours, enforce daily per-app budgets, and require a password — yours, or one a friend holds for you — to unblock.

[![Android CI](https://github.com/ukirdeomkar/Brainrot-Stopper/actions/workflows/android.yml/badge.svg)](https://github.com/ukirdeomkar/Brainrot-Stopper/actions/workflows/android.yml)

> **Status: onboarding + persistence + rules editor + blocking engine + foreground resilience + usage timers + password setup + password-gated unblocks and settings + diagnostics.** The repository builds a debug APK, runs unit tests, and ships the M1 onboarding/permission flow (Accessibility, Usage Access, notification access, notifications, battery), the M2 Room persistence layer for rules, schedules, usage, block events, and unlock grants, the M3 app/schedule editor: an installed-app picker, create/edit/disable app rules and schedules, day toggles, a per-app daily-limit editor, and empty/overlapping-window validation (see [docs/rules-editor.md](docs/rules-editor.md)), and the M4 blocking engine: a pure, unit-tested `DecisionEngine`, an `AccessibilityService` that draws a full-screen non-dismissible overlay, back-key consumption, and unlock-grant dismissal (see [docs/blocking-engine.md](docs/blocking-engine.md)), plus the M5 foreground `specialUse` service with boot/update restart and a "blocking engine running" self-check (see [docs/foreground-service.md](docs/foreground-service.md)), and the M6 usage tracking + daily timers: a 60 s authoritative per-app usage reconciliation from `UsageStatsManager`, local-midnight and timezone rollover handling, blocking at the exact limit, and clean degradation when Usage Access is revoked (see [docs/usage-tracking.md](docs/usage-tracking.md)), M7 password setup: PBKDF2-HMAC-SHA256 (600k iterations, 128-bit salt) in `EncryptedSharedPreferences`, self-set and friend-set flows, and a change flow that requires the old password (see [docs/password.md](docs/password.md)), M8 enforcement: password-gated emergency unblocks that expire and re-arm automatically, plus a password gate on every rule, schedule, timer, and password change (see [docs/emergency-unlock.md](docs/emergency-unlock.md)), and M9 settings & diagnostics: a local-only status screen for the live engine state, every permission grant state (revoked ones stay visible), today's per-app usage against its limit, and the newest block events, with no export or upload path (see [docs/settings-diagnostics.md](docs/settings-diagnostics.md)). Remaining work is tracked as MVP issues from the [technical design](docs/design.md).

## Why

Short-form feeds are the biggest attention leak on a phone, and willpower loses to a bottomless feed. Two things help more than willpower: **friction** (the app is not there when you reach for it) and **accountability** (someone else holds the password). This project is a small, auditable, offline tool for both. No account, no cloud, no analytics.

## Scope

Phase 1 (MVP):

- Block selected apps during scheduled windows (whole-app blocking).
- Daily per-app time budgets read from Android's UsageStats.
- A full-screen block overlay that only the password removes.
- Password modes: self-set PIN, or friend-set (accountability partner) PIN.
- Short, explicit emergency unlocks.

Explicit non-goals for v1: iOS, cloud accounts/sync, parental multi-user, Device Owner jail mode, ad-blocking, and Play Store distribution. Surgical "block only Shorts/Reels" is a Phase 2 experiment — see [design §6](docs/design.md#6-short-form-detection-shorts--reels) for why it cannot be a v1 promise.

## Requirements

- Android 12 (API 31) or newer.
- To build: JDK 17, Android SDK with platform **android-37** and build-tools **36.0.0**.
- Toolchain: AGP 9.5.1, Gradle 9.6.0 (wrapper checked in), Kotlin built into AGP 9.

## Build and test

```bash
# unit tests (JVM)
./gradlew testDebugUnitTest

# debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
```

CI (`.github/workflows/android.yml`) does the same on every push and pull request to `main`, installs the SDK packages, uploads the debug APK, and runs an instrumentation smoke test on API 31 and API 36 emulators (screenshots and logcat are uploaded as `qa-evidence-api-*` artifacts). The VPS that hosts this project has no JDK or Android SDK by default and only 2 vCPU / 3.7 GB RAM, so CI is the build and authoritative test path (design §7.2). Server-side QA, the VPS KVM harness, and the reusable QA checklist are documented in [docs/qa.md](docs/qa.md).

### Runtime dependencies

The app ships only what it uses. Each addition is justified in the issue that added it (design §7.4):

| Dependency | Why |
|---|---|
| `androidx.room` (runtime, ktx, compiler) | Structured, migration-managed storage for rules, schedules, usage buckets, block events, and unlock grants (M2). |
| `kotlinx-coroutines-core` | Already a transitive dependency of Room; declared explicitly because `:core:data` exposes `suspend` APIs. Adds no artifact. |
| `androidx.security:security-crypto` | `EncryptedSharedPreferences` for the local unblock credential, which the M7 acceptance criteria require to be encrypted at rest (M7). |

Credentials do **not** use Room. The password hash lives in `EncryptedSharedPreferences`
(`brainrot_credentials.xml`) with a backup exclusion; see [docs/password.md](docs/password.md).
Room DAO tests run on the JVM with Robolectric, so no emulator is required for
`./gradlew testDebugUnitTest`.

## Module layout

```
:app                 Activity shell and wiring (scaffold UI)
:core:model          Pure domain types: rules, schedules, timers, password mode (unit-tested)
:core:data           Repository contracts + Room persistence (rules, schedules, usage,
                     block events, unlock grants)
:core:security       PBKDF2 KDF, credential record, EncryptedSharedPreferences store, and
                     the setup/change rules (unit-tested)
:feature:blocking    Pure DecisionEngine, cached state, AccessibilityService, and the
                     TYPE_ACCESSIBILITY_OVERLAY block screen (M4); notification-listener
                     shell for M10
:feature:onboarding  First-run permission walkthrough, grant detection, and deep links (M1)
:feature:rules       Installed-app picker and the blocklist/schedule editor, including
                     empty/overlapping-window validation (M3)
:feature:settings    Self-set, friend-set, change-password, and password-gate screens (M7/M8)
:feature:timers      UsageStats polling, authoritative daily reconciliation, and
                     local-day/timer state (M6)
```

Planned modules (`:core:common`) are described in the [design](docs/design.md) and arrive with
their issues.

## Permissions and why each exists

This app asks for sensitive Android permissions. Every one is listed here, and the app has no hidden permissions.

| Permission | Why it is needed | When it is requested |
|---|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Detect which app is in the foreground and draw the blocking overlay. This is the only supported way to do per-app blocking without root. | The user enables the service in Android Settings → Accessibility. |
| Usage access (`PACKAGE_USAGE_STATS`) | Read per-app foreground time so daily budgets can be enforced. | The user grants Usage Access in Android Settings. |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Best-effort short-form detection: read the playback duration from an app's media session. No message content is read. | The user enables Notification access in Android Settings. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the blocking engine alive while the phone is in use. | Declared in the manifest; no runtime dialog. |
| `POST_NOTIFICATIONS` (Android 13+) | Show the mandatory foreground-service notification and status. | Runtime prompt during onboarding. |
| `RECEIVE_BOOT_COMPLETED` | Restart blocking after a reboot. | Declared in the manifest. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask the user to exempt the app so OEM battery managers do not kill the blocking engine. | Onboarding. |

Not used in v1: `SYSTEM_ALERT_WINDOW` (the overlay goes through the accessibility service) and `BIND_VPN_SERVICE` (browser URL blocking is deferred; see [design §5](docs/design.md#5-browser-url-blocking-via-vpnservice--dns)). No internet permission is requested: v1 makes no network calls.

## Privacy

- **No analytics, no telemetry, no cloud, no account.**
- Rules, schedules, usage totals, and the password hash stay on the device.
- Block and usage events are local diagnostics only, capped, and never uploaded.
- The app is MIT-licensed and auditable; the privacy claim is verifiable in the source.

## Honest limits

- A normal Android app **cannot prevent** the user from clearing its data or uninstalling it. Clearing data removes the password and all rules. The password is a **commitment device and an accountability tool, not a jail**. True enforcement needs Device Owner provisioning, which is a Phase 2 opt-in (design §4.4).
- Blocking is not frame-perfect: the overlay appears shortly after the target app reaches the foreground, and Android may show a recents thumbnail.
- OEMs (Xiaomi, Oppo, Vivo, Samsung, …) sometimes kill background services aggressively. Onboarding will guide battery exemptions, but OEM behaviour varies.
- Daily usage figures come from `UsageStatsManager` and are approximate (design §3.3).

## Roadmap

The MVP breakdown (M1–M12, roughly 17 engineering days) is in [design §9](docs/design.md#9-mvp-task-breakdown-ready-to-become-child-issues) and is being converted into tracked issues. Sequencing: permission onboarding → persistence → blocking engine → password → timers → settings → CI emulator smoke → owner device acceptance.

## Contributing

Issues and pull requests are welcome. Please keep the privacy contract intact: no analytics, no network calls without a documented issue, and no new runtime dependency without a one-line justification. Run `./gradlew testDebugUnitTest assembleDebug` before opening a PR; CI must be green.

## License

[MIT](LICENSE) © 2026 Omkar Ukirde.
