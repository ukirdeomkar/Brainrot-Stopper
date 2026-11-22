# Brainrot-Stopper — Technical Design (feasibility spike)

> Status: spike / architecture decision record. Parent: **OMK-531**.
> Target: Android 12+ (`minSdk 31`), compile/target API 37.
> Scope: validate the technical approach and lay a compiling foundation. No feature implementation in this issue.

## 0. Success condition for this spike

A reviewable architecture that answers, for every MVP feature in the parent `plan`, **how it is built, what Android APIs it uses, what it cannot do, and what the risk is** — plus a public, MIT-licensed Kotlin repo whose skeleton builds a debug APK in CI with unit tests green. Everything below is written to be converted into child issues (section 9).

Toolchain chosen for the scaffold (pinned, verified to exist):

| Component | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.5.1 | latest 9.x; supports compileSdk 37 |
| Gradle | 9.6.0 | AGP 9.5 minimum |
| JDK | 17 | AGP 9.5 minimum/default |
| Kotlin | built-in to AGP 9 (`kotlin-android` plugin not applied) | one less version to keep in sync |
| compileSdk / targetSdk | 37 | current latest platform |
| minSdk | 31 | Android 12 requirement |
| Build Tools | 36.0.0 | AGP 9.5 default |

If AGP 9's built-in Kotlin causes friction in a follow-up, the documented opt-out is `android.builtInKotlin=false` plus `org.jetbrains.kotlin.android`; that is a one-line change, not an architecture change.

---

## 1. Architecture

### 1.1 Module map

Single Gradle build, package base `dev.brainrotstopper`, layered so that the blocking engine is testable without Android.

| Module | Type | Responsibility | In scaffold now |
|---|---|---|---|
| `:app` | application | Activity shell, DI wiring, navigation host, foreground-service lifecycle | yes |
| `:core:model` | android library | Pure domain types + rules: `AppRule`, `BlockSchedule`, `TimeWindow`, `DailyTimer`, `PasswordMode`, `BlockReason` | yes |
| `:core:data` | android library | Repository interfaces + persistence (Room later); in-memory impl now | yes |
| `:core:common` | android library | clock abstraction, result types, dispatchers, logging facade | not yet |
| `:feature:onboarding` | android library | permission walkthrough (Accessibility, Usage Access, overlay, battery, optional VPN) | not yet |
| `:feature:rules` | android library | blocklist + schedule editor UI | not yet |
| `:feature:blocking` | android library | `BlockingController`, AccessibilityService, overlay, decision engine | interface stub now |
| `:feature:timers` | android library | `TimerController`, usage polling, per-app counters | interface stub now |
| `:feature:settings` | android library | password/friend setup, emergency unlock, diagnostics | M7 password, M8 unlock gate |

Why multi-module now: the domain rules (schedules, timers) are pure Kotlin and get fast JVM unit tests; the Android-heavy pieces (AccessibilityService, VpnService) stay isolated so they can be exercised by instrumentation/emulator tests later. `:core:model` has no Android dependency even though it lives in an Android library module — that keeps the option to move it to a JVM module later without rewrites.

### 1.2 Runtime components

```
                     ┌──────────────────────── Android system ────────────────────────┐
                     │ AccessibilityManager        UsageStatsManager       AlarmManager│
                     └───────┬─────────────────────────────┬──────────────────────────┘
                             │ events                      │ aggregates
┌───────────────┐   ┌────────▼─────────┐          ┌─────────▼──────────┐
│ BootReceiver  │──▶│ BlockingService  │          │   UsagePoller      │
│ (BOOT/UPDATE) │   │ (foreground,     │◀─────────│ (inside service,   │
└───────────────┘   │  specialUse)     │  state   │  60 s tick)        │
                    └───┬─────────┬────┘          └─────────┬──────────┘
                        │         │                         │
             ┌──────────▼──┐   ┌──▼───────────┐   ┌─────────▼──────────┐
             │ Decision    │   │ Overlay      │   │ TimerEngine        │
             │ Engine      │──▶│ Controller   │   │ (budgets, day keys)│
            └──────┬──────┘   │ (accessibility│  └─────────┬──────────┘
                    │          │  overlay)    │            │
             ┌──────▼──────┐   └──────────────┘            │
             │ Rules/State │─────────────────────────────────┘
             │ repositories│  (Room + DataStore + credential store)
             └─────────────┘
```

- **BlockingService** — a foreground service owns the live state, survives Activity death, re-registers listeners, and is the single writer to repositories. The AccessibilityService never writes to disk directly; it asks `BlockingService`/`DecisionEngine` for a verdict.
- **DecisionEngine** — pure function: `(packageName, instant, rules, schedules, timerState, unlockGrant) -> Decision(block, reason, releaseAt)`. Unit-testable; no Android types.
- **OverlayController** — shows/hides a full-screen accessibility overlay window.
- **UsagePoller** — reads `UsageStatsManager` on a 60 s tick plus screen-on/off broadcasts and reconciles the authoritative daily total.
- **PasswordManager** — KDF verify/derive, separate from UI.

### 1.3 Data model

Domain types (in `:core:model`, already scaffolded):

```kotlin
data class TimeWindow(startMinuteOfDay: Int, endMinuteOfDay: Int) // 0..1439, overnight if start > end
data class BlockSchedule(id, label, daysOfWeek: Set<DayOfWeek>, windows: List<TimeWindow>)
data class AppRule(packageName, displayName, enabled, dailyLimitMinutes: Int?, scheduleIds: Set<String>)
data class DailyTimer(packageName, limitMinutes, usedMinutes)
enum class PasswordMode { SELF_SET, FRIEND_SET }
```

Persistence (Room, added in M2):

| Table | Columns | Notes |
|---|---|---|
| `app_rule` | `package_name` PK, `display_name`, `enabled`, `block_all`, `daily_limit_minutes` | nullable limit = no timer |
| `schedule` | `id` PK, `label`, `enabled`, `days_mask` | bitmask Mon..Sun, avoids a join for the hot path |
| `schedule_window` | `schedule_id` FK, `start_min`, `end_min`, `ordinal` | overnight supported |
| `rule_schedule` | `package_name`, `schedule_id` | many-to-many |
| `usage_bucket` | `package_name`, `epoch_day`, `used_millis` | key = (pkg, day); rewritten from UsageStats, never blindly incremented |
| `block_event` | `id`, `package_name`, `started_at`, `reason`, `released_at` | local diagnostics only, capped/rotated |
| `unlock_grant` | `id`, `package_name` (null = global), `expires_at`, `reason` | short-lived bypass |
| `credential` | `mode`, `salt`, `iterations`, `hash`, `created_at` | stored in EncryptedSharedPreferences, not Room |

Day boundaries use `LocalDate` in the device timezone; every `epoch_day` is recomputed from the current zone, and a `ACTION_TIMEZONE_CHANGED` receiver invalidates today's key. This makes the daily reset correct across DST and travel.

---

## 2. Blocking mechanism (AccessibilityService + overlay)

### 2.1 Trigger path

1. Service declared with `android.permission.BIND_ACCESSIBILITY_SERVICE`, `accessibilityEventTypes` including `typeWindowStateChanged|typeWindowsChanged`, `canRetrieveWindowContent=true`, `notificationTimeout` small (e.g. 50 ms).
2. On `TYPE_WINDOW_STATE_CHANGED`, read `event.packageName`. Ignore our own package and system UI.
3. Ask `DecisionEngine` (state cached in process memory; refreshed on screen-on and rule changes). Verdict is O(active rules), no disk I/O on the hot path.
4. If `block`, call `OverlayController.show(reason, releaseAt)`.
5. If `unblock` and an overlay is showing, hide it.

Overlay is a `WindowManager` view added by the **AccessibilityService** with `TYPE_ACCESSIBILITY_OVERLAY`. This is a deliberate choice: it needs **no** `SYSTEM_ALERT_WINDOW` permission and is the sanctioned way for an accessibility service to draw over other apps. It also means the overlay dies with the service, which is acceptable because the service is the thing keeping us alive.

### 2.2 Making the overlay hard to dismiss

- Full-screen (`MATCH_PARENT`), `FLAG_LAYOUT_IN_SCREEN`, opaque background, no `FLAG_NOT_FOCUSABLE` so it consumes input.
- `AccessibilityService.onKeyEvent` returns `true` for `KEYCODE_BACK`, `KEYCODE_HOME` is *not* interceptable in a normal overlay — the user can still go home. That is fine: the blocked app stays in the background, and re-entry triggers a new event and a fresh overlay. We do not pretend to trap the user.
- Re-assert on every window change: if the overlay was removed by an OEM or by the user navigating away, the next block-worthy event re-shows it.
- Only the password path removes the overlay and writes a session-scoped `unlock_grant`. A time-boxed grant auto-expires.
- The overlay content itself is a plain view (title, reason, countdown, "unlock" button) — no WebView, no dynamic code.

### 2.3 Edge cases across Android 12–16 (API 31–36+)

| Version | Relevant change | Handling |
|---|---|---|
| 12 (31) | foreground-service start restrictions from background | start FGS from `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` and from the visible Activity; if start is denied, post a high-priority notification to reopen |
| 13 (33) | `POST_NOTIFICATIONS` runtime permission; FGS notification can be hidden by user | request permission in onboarding; FGS still runs without it, but the user loses the "why is this running" affordance |
| 14 (34) | mandatory `foregroundServiceType`; new `specialUse` type + `FOREGROUND_SERVICE_SPECIAL_USE` permission | declare `specialUse` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing "attention/blocking engine"; Play would require a justification, sideload/F-Droid does not |
| 15 (35) | tighter background/FGS starts, `dataSync` time limits | never use `dataSync`; `specialUse` + user-initiated start path |
| 16 (36) | continued FGS/boot restrictions, stronger user-visible controls | same strategy; add a startup self-check that surfaces "blocking engine not running" in the UI |
| All | Doze, OEM task killers (Xiaomi/Oppo/Vivo/Samsung) | onboarding asks for battery-optimization exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; document OEM-specific settings; service re-registers on `ACTION_MY_PACKAGE_REPLACED` and on accessibility re-enable |

Known, accepted limitations (documented, not bugs):

- A user can turn the AccessibilityService off in Settings. We can detect it (`AccessibilityManager` enabled-services check) and nag, but cannot prevent it without Device Owner.
- The overlay appears after the target window is already foreground, so a 100–300 ms glimpse is possible. We do not claim frame-perfect blocking.
- Picture-in-Picture and the recents thumbnail can show a frame of blocked content; Android gives no hook to blank those.
- Split-screen: we block when the target app is *a* foreground window; the other pane keeps working, which is the intended behaviour.

---

## 3. Time tracking (UsageStatsManager)

### 3.1 Permission flow

- Manifest: `android.permission.PACKAGE_USAGE_STATS` with `tools:ignore="ProtectedPermissions"` (it is a signature/privileged permission, granted only by the user in Settings).
- Onboarding: `startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))`, then poll whether access was granted. `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, packageName) == MODE_ALLOWED` is the check; also attempt a tiny `queryUsageStats` as a fallback on OEMs where AppOps reports oddly.
- If denied, the app runs in "blocking only, no timers" degraded mode and says so in the UI. Blocking does not need Usage Access.

### 3.2 Collection

- `UsageStatsManager.queryAndAggregateUsageStats(begin, end)` for the authoritative per-package foreground total for today.
- Poll every 60 s from `BlockingService`, and immediately on `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`, and app foreground changes we already observe via accessibility.
- Store the authoritative total, not an increment: `usage_bucket.used_millis = total`. Recomputing is idempotent, so a missed tick, a duplicate tick, or a service restart cannot inflate usage.
- `queryEvents` can refine session boundaries later, but the aggregate is enough for a daily budget and is cheaper.

### 3.3 Accuracy limits (honest)

- UsageStats is derived from activity-resume events, not screen pixels: it can over-count when an app holds the foreground while the screen is off (rare) and under-count momentarily (up to the poll interval).
- Some OEMs batch or delay writes; a just-opened app may show 0 for a few seconds.
- Work-profile apps and some system apps are not visible.
- The daily total is only as good as Usage Access; if the user revokes it, timers stop and the UI must reflect that.
- We do not claim exact per-second accounting; the budget is a behavioural guardrail, not billing.

### 3.4 Daily reset semantics

- Boundary = local midnight in the device timezone.
- Key = `LocalDate.toEpochDay()` stored on every bucket.
- On date change, timezone change, or boot, today's bucket is created / recomputed. Old buckets are retained for a rolling 30 days for local diagnostics only (no upload).
- Timer state is `usedMillis vs limitMillis`; the engine blocks at `>= limit`, not `> limit`, to avoid a 1-tick overshoot.

---

## 4. Password / accountability

### 4.1 Credential storage

- KDF: **PBKDF2-HMAC-SHA256** using `javax.crypto.SecretKeyFactory` from the standard library — no new dependency, FIPS-ish, well understood. Parameters: 128-bit random salt (`SecureRandom`), 600,000 iterations (OWASP 2023 floor for PBKDF2-SHA256), 256-bit derived key, stored as `iterations`, `salt`, `hash`, `version`.
  - Argon2id is stronger against GPU attacks, but needs a native library; deferred until there is a concrete threat model that justifies a dependency. The stored format includes a `version`/algorithm field so a migration is possible.
- Verification uses `MessageDigest.isEqual` (constant-time) on the derived bytes.
- Store salt/params/hash in `EncryptedSharedPreferences` (AndroidX Security) or, if that dependency is undesirable, in app-private `DataStore` protected by the OS app sandbox + full-disk encryption. The scaffold documents the choice but does not add the dependency yet.
- `android:allowBackup="false"` and `android:dataExtractionRules` exclude the credential file, so a backup/restore cannot roll the password back.

### 4.2 Self-set vs friend-set

- **Self-set**: onboarding asks the owner for a PIN/passphrase twice, shows a strength hint, and stores the hash. There is a "change password" flow behind the current password.
- **Friend/accountability**: onboarding shows "Hand the phone to your friend" then a PIN entry screen with no echo and no confirmation shown to the owner; the friend enters it twice (the confirmation screen can visibly say "re-enter the same PIN"). The owner is never shown or told the value. Recovery is intentionally impossible without clearing app data (see 4.3).
- The mode is recorded so the UI can say "A friend holds this password" — that framing is part of the product.

### 4.3 What the password actually buys (honest limits)

- It stops **impulse** bypass: changing rules, cancelling a block, or ending a timer needs the password. That is the main value.
- It does **not** stop a determined user: Android settings can clear app data or uninstall the app, which wipes the credential. We mitigate only with friction (warn on setup, `allowBackup=false`, optional notification) and by being explicit in the README.
- Therefore v1 is a **commitment device plus accountability**, not a jail. The parent plan already states this; the app UI must repeat it at setup.

### 4.4 What Device Owner would add, and what it costs

Device Owner (provisioned via ADB `dpm set-device-owner` or QR on a factory-fresh device) would let us:

- `setUninstallBlocked(packageName, true)` — the app cannot be uninstalled.
- `setPackagesSuspended(...)` / `setApplicationHidden(...)` — hard-disable the target apps rather than draw an overlay.
- `addUserRestriction(DISALLOW_SAFE_BOOT / DISALLOW_FACTORY_RESET)` — close the safe-mode/factory-reset escape hatches (subject to OEM support).
- `setLockTaskPackages` — kiosk-style focus mode.
- Prevent clear-data for the managed app in many configurations.

Costs that make it Phase 2, not v1:

- Requires a factory reset or ADB provisioning; the device becomes a "managed device" with a device-admin posture.
- Distribution changes: a normal APK install cannot become Device Owner without the ADB/QR step; F-Droid/sideload UX suffers.
- It is one-way for practical purposes: removing Device Owner usually needs a factory reset or a deprovision flow.
- More compliance/security surface for an open-source tool; more ways to brick a daily driver.

Recommendation: ship v1 without it, document it clearly, and offer it as an explicit opt-in "strict mode" in Phase 2 for users who want it.

---

## 5. Browser URL blocking via VpnService + DNS

**Can it work?** Yes, partially: a local `VpnService` can become the system DNS resolver (or transparently intercept UDP/53) and refuse A/AAAA answers for blocked domains. That blocks *browser* navigations that resolve through the system resolver.

**Why it is not worth shipping in v1:**

- **DoH/DoT bypass.** Chrome and Firefox use secure DNS by default in many regions; a system VPN DNS filter is simply not consulted. Blocking that needs per-browser configuration or TLS interception, which is out of scope and privacy-hostile.
- **Does not touch in-app traffic.** Instagram/YouTube/TikTok app APIs do not go through the browser resolver path we can see, so the primary brainrot surface is unaffected. Whole-app blocking (section 2) is the real control.
- **VPN conflicts.** Android allows one active VPN; enabling ours disables the user's real VPN, and vice versa. That is a bad trade for a secondary feature.
- **Fragility.** SNI/IP-based filtering breaks on QUIC, ECH, CDNs, and IP-literal navigation; maintaining a hosts list is endless.
- **Cost.** A correct userspace DNS proxy is a meaningful chunk of work (packet parsing, UDP relay, lifecycle, battery), plus a scary permission dialog.

**Recommendation:** v1 = no VPN. Spend the effort on reliable app blocking. Phase 2 may add an **optional** "browser domain blocker" toggle with: a maintained hosts blocklist, A/AAAA filtering only, a clear "does not work with secure DNS" warning, an allowlist, no query logging, and `VpnService.prepare()` in onboarding. If it ships, it is an add-on, never the core enforcement.

Scope if/when built: `VpnService` with `addAddress`/`addDnsServer` (or a UDP relay), minimal DNS question parser, blocklist matcher, foreground notification, `onRevoke` handling. Not TLS MITM, not SNI filtering, not ad-blocking.

---

## 6. Short-form detection (Shorts / Reels)

**What is actually achievable:** there is no public API that says "the user is now in the Shorts/Reels feed". Android exposes the foreground *activity/window* and, via accessibility, the view tree. Short-form surfaces are tabs inside the same activity as the normal feed (YouTube Shorts, Instagram Reels, TikTok's main feed is itself short-form), and their view IDs/labels are internal, localized, and change with app updates.

Possible approaches and why each is weak:

| Approach | Reality |
|---|---|
| Accessibility `viewIdResourceName` matching | Obfuscated and version-specific; a YouTube update breaks it silently. |
| Text/`contentDescription` heuristics ("Shorts", "Reels") | Locale-dependent, A/B-tested labels, false positives on normal pages. |
| Activity/component name matching | Often the same component hosts both feed types (e.g. a single `MainActivity`). |
| Intent/deep-link interception | We cannot intercept arbitrary app launches; also spoofable. |
| Screen-content ML | Out of scope, privacy-hostile, battery, and still unreliable. |

**Recommendation for v1:** block the **whole app** during windows and/or when its timer is exhausted. That is reliable and honest. Optionally, offer a softer "gate on entry" (a 5–10 s pause/nudge before letting the app open) as a friction feature that does not depend on detecting the feed.

**Phase 2 experiment (opt-in, behind a flag):** best-effort detection limited to a small, versioned rules file per app (package + view-id signatures), with on-device diagnostics, a kill switch, and **no promise of reliability**. Never make acceptance depend on it; never claim surgical per-tab blocking in the README or store listing.

---

## 7. Build & test

### 7.1 CI design (GitHub Actions, `ubuntu-latest`)

Workflow `.github/workflows/android.yml`, triggered on push/PR to `main` and `workflow_dispatch`:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` — Temurin **17** (AGP 9.5 minimum).
3. Use the runner's preinstalled Android SDK (`$ANDROID_HOME`) directly; skip `android-actions/setup-android@v3` because its `sdkmanager tools` bootstrap fails now that the legacy `tools` package is gone.
4. Resolve `sdkmanager` from `PATH` or `$ANDROID_HOME/cmdline-tools/*/bin`, run `--licenses`, then install `platforms;android-37.0` (the package id is `android-37.0`) and `build-tools;36.0.0`.
5. `gradle/actions/setup-gradle@v4` — Gradle caching.
6. `./gradlew testDebugUnitTest` — JVM unit tests for domain rules.
7. `./gradlew assembleDebug` — proves the APK builds.
8. `actions/upload-artifact@v4` — uploads `app-debug.apk` (fails if missing).

Repository is public, so Actions minutes are free. Gradle and the wrapper are pinned (wrapper checksum committed) so a CI run is reproducible.

**Emulator smoke (optional, follow-up):** `reactivecircus/android-emulator-runner` on the Linux runner (KVM available) with an API 31 and an API 36 image, running one instrumentation test that launches `MainActivity` and asserts the scaffold view. This is deliberately *not* in the first workflow: emulator boots are the flakiest part of Android CI and the spike does not need them. Add it as child issue M10 once the skeleton is stable.

### 7.2 Why not build on the VPS

The VPS has **no JDK and no Android SDK**; a working toolchain is roughly 5–7 GB (JDK + cmdline-tools + platform + build-tools + Gradle caches + emulator images) against ~12 GB free. With **2 vCPU / 3.7 GB RAM**, a Gradle build competes with the agent runtime, and an emulator is memory-infeasible. GitHub-hosted `ubuntu-latest` already has the SDK and JDK, is free for public repos, and keeps heavy builds off the box that runs the company. Local verification on the VPS is therefore limited to static review; the CI run is the build evidence.

### 7.3 Test strategy

- **JVM unit tests** (`:core:model`, `:core:data`, decision engine): schedules, overnight windows, day masks, timer exhaustion, idempotent usage reconciliation, password KDF round-trip + wrong-password rejection. Fast, no device.
- **Instrumentation / emulator** (M10): service starts, overlay shows, permission screens render.
- **Device acceptance**: the owner sideloads the APK on an Android 12+ phone — only a real device proves OEM behaviour. This cannot be replaced by CI; Totto reviews the CI evidence and Mana/Omkar run the device pass.

### 7.4 Dependency policy

Standard library and platform APIs first. Current scaffold has **zero third-party runtime dependencies** (Kotlin stdlib comes from AGP's built-in Kotlin; tests use JUnit 4). Each future dependency needs a one-line justification in its issue (e.g. Room for persistence, AndroidX Security for the credential store).

---

## 8. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | AccessibilityService disabled or killed by the user/OEM | High | High | foreground `specialUse` service, boot receiver, re-enable detection + nag, battery-optimization exemption in onboarding |
| 2 | OEM task killers (Xiaomi/Oppo/Vivo/Samsung) silence the service | High | High | onboarding guidance per OEM, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, startup self-check banner |
| 3 | Surgical Shorts/Reels detection impossible/unreliable | High | Medium | whole-app blocking in v1; opt-in experimental detection in Phase 2 (section 6) |
| 4 | Clear-data / uninstall bypasses password | Certain | Medium | honest UX + README, `allowBackup=false`, Device Owner strict mode Phase 2 (section 4.4) |
| 5 | FGS type/boot-start rules change on new Android | Medium | High | target-based `specialUse` declaration, test API 31/34/36, notification fallback, monitor release notes |
| 6 | Overlay dismissed by OEM or race on fast navigation | Medium | Low | re-assert on every window event, back-key interception, no dependency on overlay persistence |
| 7 | UsageStats inaccurate/delayed | Medium | Low | authoritative recompute (idempotent), poll + screen events, document limits (section 3.3) |
| 8 | VPN conflicts and DoH bypass make URL blocking a bad trade | High | Medium | do not ship VPN in v1 (section 5) |
| 9 | Credential extraction on rooted/backed-up device | Low | Medium | PBKDF2 600k, EncryptedSharedPreferences/private storage, `allowBackup=false` |
| 10 | Play Store rejects Accessibility/VPN use | High | Medium | distribution = open source / F-Droid / sideload for v1; no Play dependency |
| 11 | Toolchain drift breaks CI | Medium | Medium | pinned AGP/Gradle/wrapper checksum, Dependabot PRs reviewed, one module of blast radius |
| 12 | Privacy perception ("an app that watches me") | Medium | Medium | open source, no analytics, no cloud, README privacy statement, minimal permissions |

---

## 9. MVP task breakdown (ready to become child issues)

Effort is engineering days, rough. Dependencies noted. Each becomes one child issue under OMK-531 unless stated.

| ID | Task | Depends on | Effort | Notes / acceptance |
|---|---|---|---|---|
| M1 | Onboarding + permission flow (Accessibility, Usage Access, notifications, battery exemption) | — | 1.0d | each step detects grant state and deep-links to the right Settings screen |
| M2 | Persistence layer (Room entities from §1.3 + repositories + migrations v1) | — | 1.5d | in-memory repo replaced; unit tests on DAO with Robolectric or instrumented |
| M3 | Blocklist + schedule editor UI (select apps, add windows, day toggles) | M2 | 2.0d | create/edit/disable rules; validation for empty/overlapping windows |
| M4 | Blocking engine + AccessibilityService + non-dismissible overlay | M2 | 2.5d | decision engine unit-tested; overlay re-asserts; back key consumed; unlock hides |
| M5 | Foreground `specialUse` service, boot/update receivers, resilience self-check | M4 | 1.5d | survives reboot where allowed; banner when service not running |
| M6 | Usage tracking + daily timers | M1, M2 | 2.0d | authoritative recompute, local-midnight reset, TZ change handled, blocks at limit |
| M7 | Password KDF + self-set and friend-set onboarding | M2 | 1.5d | PBKDF2 600k, constant-time compare, friend mode hides input, no recovery |
| M8 | Emergency unblock + password gate on settings changes | M7 | 1.0d | time-boxed grant, auto re-arm, settings require password |
| M9 | Settings + diagnostics screen (permission status, today's usage, engine status) | M1, M6 | 1.0d | no secrets logged; export-free |
| M10 | Instrumentation/emulator smoke in CI (API 31 + API 36) | M4 | 1.0d | one launch test per image; flake budget documented |
| M11 | Device acceptance pass on owner's Android 12+ phone | M1–M9 | 0.5d | owner runs the 2-week success condition from the parent plan |
| M12 | Finalize README privacy/permission docs and F-Droid metadata prep | — | 0.5d | maps exactly to manifest permissions; no analytics claim verified |

Total ≈ 17 engineering days, parallelizable after M2/M4. Recommended sequencing: M1 → M2 → M4 → M7 → M6 → M8 → M3 → M5 → M9 → M10 → M11, with M12 alongside.

### Suggested child issue titles

1. `[M1] Onboarding + permission flow`
2. `[M2] Room persistence + repositories`
3. `[M3] Blocklist & schedule editor UI`
4. `[M4] Blocking engine, AccessibilityService, overlay`
5. `[M5] Foreground service + boot/resilience`
6. `[M6] Usage tracking + daily timers`
7. `[M7] Password KDF + self/friend setup`
8. `[M8] Emergency unblock + settings password gate`
9. `[M9] Settings & diagnostics`
10. `[M10] Emulator smoke test in CI`
11. `[M11] Owner device acceptance pass`
12. `[M12] README/privacy/F-Droid prep`

---

## Appendix A — Permission rationale (mirrors README)

| Permission | Why | When requested |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` (service-level) | detect foreground app and draw the blocking overlay | user enables the service in Accessibility settings |
| `PACKAGE_USAGE_STATS` | read per-app foreground time for daily timers | user grants Usage Access in Settings |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | keep the blocking engine alive | declared; no runtime dialog |
| `POST_NOTIFICATIONS` (33+) | show the required foreground-service notification and status | runtime prompt in onboarding |
| `RECEIVE_BOOT_COMPLETED` | restart blocking after reboot | declared |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ask the user to exempt the app so OEMs do not kill it | onboarding |
| `SYSTEM_ALERT_WINDOW` | **not used** — overlay goes through the AccessibilityService | — |
| `BIND_VPN_SERVICE` | deferred to Phase 2, not in v1 | — |

## Appendix B — Privacy statement (mirrors README)

No analytics, no telemetry, no cloud, no account. All rules, usage totals, and the password hash stay on the device. Block/usage events are local diagnostics only, capped and never uploaded. The app makes no network requests in v1. Open source under MIT so the claims are auditable.
