# Blocking engine (M4)

> Implementation notes for the decision engine, the `AccessibilityService`, and the block
> overlay. Design rationale lives in [design.md](design.md) §2; this page is the operating
> contract QA verifies.

## Decision precedence

`DecisionEngine` is a pure function with no Android types and no I/O:

```text
(packageName, instant, zone, rules, schedules, timers, grants) -> Decision(block, reason, releaseAt)
```

1. **Active unlock grant** covering the package -> allow. Grants are time-boxed and either
   package-scoped or global (`packageName == null`).
2. **No enabled rule** -> allow. A disabled rule always allows.
3. **Active schedule window** -> block with `SCHEDULE`. `releaseAt` is the end of the earliest
   active window. Windows are half-open, so a window ending exactly now is not active.
4. **Exhausted daily timer** -> block with `DAILY_LIMIT`. A timer blocks at `used >= limit`,
   never `> limit`, so there is no one-tick overshoot. `releaseAt` is the next local midnight.

A schedule wins over a timer when both apply, because the schedule is the stronger intent.

### Time and rollover

- `TimerState` carries the `epochDay` it was measured on. The engine ignores a snapshot whose
  day does not match the evaluated instant, so stale usage cannot leak across midnight or a
  timezone change.
- `releaseAt` is computed in the supplied `ZoneId`, using `LocalDate`/`ZonedDateTime`
  arithmetic. A DST jump therefore produces the correct wall-clock end, not a fixed 24-hour
  offset.
- `BlockingStateCache.refresh()` rebuilds the timer snapshot for the *current* device date and
  timezone on every refresh. The service refreshes on every `TYPE_WINDOW_STATE_CHANGED`
  (forced) and throttles other window events to 500 ms.
- M6 fills those timer inputs through `UsageTracker` (see [usage-tracking.md](usage-tracking.md)).
  The foreground service reconciles authoritative usage on a 60 s tick and on screen, clock,
  date, and timezone changes. When Usage Access is revoked, the cache stops loading timer
  inputs, so daily timers pause while schedule blocking continues.

## State and latency

- The service never reads disk on the decision path. A refresh builds an immutable snapshot
  and swaps it atomically; `decisionFor(package)` is O(rules + schedules) in memory.
- If the snapshot is not loaded yet, the engine fails open and the service re-evaluates as
  soon as the first refresh lands.
- The overlay is attached synchronously in the same window-change callback, after an
  in-memory decision. The documented latency budget has two tiers:
  - **steady state <= 500 ms** from the window-change event to the overlay attach. Measured on
    CI: **227 ms** and **325 ms** on API 36, **367 ms** on API 31.
  - **first block while the state refresh is in flight <= 1500 ms**, because that event may
    wait for the initial Room read. Measured on CI: **914 ms** on API 31 and **1104 ms** on
    API 36. This is the cold-state case, not the steady state; the service is normally warm
    before a user opens a blocked app, and M3 will invalidate the cache on rule edits.
  The service logs the measured value:

  ```text
  adb logcat -s BrainrotBlocking
  D/BrainrotBlocking: Block overlay shown for com.example.app in 42 ms
  ```

  The design's "100-300 ms glimpse" caveat still applies: blocking is not frame-perfect.

- Blocking is not frame-perfect and Android may show a recents thumbnail. Re-assertion on
  every window change handles an overlay removed by an OEM or by navigation.

## Overlay

- `TYPE_ACCESSIBILITY_OVERLAY`, full screen, opaque, focusable, and `FLAG_KEEP_SCREEN_ON`.
  No `SYSTEM_ALERT_WINDOW` permission is needed.
- The window title is `BrainrotBlockerOverlay`, so `adb shell dumpsys window windows` is the
  deterministic QA check for "is the overlay attached".
- `onKeyEvent` consumes both directions of `KEYCODE_BACK` while the overlay is showing.
  `KEYCODE_HOME` is not interceptable and is intentionally not faked; going home lets the
  overlay be removed, and re-entering the blocked app shows it again.
- The service ignores its own package and the system UI, so the overlay cannot block itself.

## Unlock flow (M8)

The overlay's unlock button opens a masked password field. Submitting it:

1. calls `BrainrotAccessibilityService.requestUnlock(package, password)`;
2. runs `PasswordUnlockGate` on the IO dispatcher (PBKDF2 against the M7 credential);
3. on approval writes a 5-minute package-scoped `UnlockGrant` through the M2
   `UnlockGrantRepository`, refreshes the cache, verifies the grant is visible, and only
   then hides the overlay;
4. on a wrong password shows an inline refusal and leaves the overlay up.

The flow is `suspend` end to end (`UnlockFlow.unlock`). It returns `UnlockResult.Success`
only after `BlockingStateCache.isUnlocked` is true; a failed grant write or refresh returns
`UnlockResult.Failed` instead of looking like an unlock, and the overlay stays up.

The process default gate is `DenyAllUnlockGate`; the AccessibilityService installs
`PasswordUnlockGate`. After an unlock the service schedules a refresh at the grant expiry, so
blocking re-arms even if the user stays in the app. See
[emergency-unlock.md](emergency-unlock.md).

`BlockingRuntime` exposes the verification hooks used by tests and later UI:

- `isAttached()` / `isAccessibilityServiceAttached()` — a service owns the engine/overlay;
- `decisionFor(package)` — the current cached verdict;
- `isUnlocked(package, now)` — a cached grant covers the package;
- `activeGrantExpiry(package)` — when the current exemption ends;
- `suspend requestUnlock(package, password)` — runs the unlock flow and returns an
  `UnlockResult`; `Success` means the grant is already durable and cached.

## Local block diagnostics (M9)

Each attached overlay writes one `block_event` row (`BlockingEngine.recordBlockStarted`), and
the row is closed when the overlay is hidden (`recordBlockReleased`). Writes are queued to
`BlockEventRecorder` off the hot path and processed in order, so a release cannot race ahead
of its record. Rows are trimmed to `BlockEventRepository.DEFAULT_LIMIT`; any row left open by
a previous process is closed at engine construction. The settings & diagnostics screen reads
only this local table; nothing is uploaded (see [settings-diagnostics.md](settings-diagnostics.md)).

## Verification (server-side only)

| Check | Command / flow |
|---|---|
| Engine unit tests | `./gradlew :feature:blocking:testDebugUnitTest` |
| App builds | `./gradlew assembleDebug` |
| Overlay appears | Instrumentation `BlockingOverlayTest` on CI API 31 + API 36; `dumpsys window windows` contains `BrainrotBlockerOverlay` |
| Back does not dismiss | `input keyevent KEYCODE_BACK`, then `dumpsys window windows` still contains the overlay |
| Wrong password rejected | `BlockingRuntime.requestUnlock(package, "0000")` returns `Rejected`; overlay stays and no grant exists |
| Unlock hides it | `BlockingRuntime.requestUnlock(package, password)` returns `Success` -> overlay gone and `isUnlocked(package)` true |
| Block events | `BlockingOverlayTest` asserts a real overlay opens a `block_event` row and unlock closes it; `BlockEventRecorderTest` covers record-then-release ordering and the cap |
| Auto re-arm | `BlockingOverlayTest.shortGrantExpiresAndTheOverlayReArms` on API 31 + API 36 |
| Latency | `adb logcat -s BrainrotBlocking` shows `Block overlay shown ... in N ms` |

`BlockingOverlayTest` enables the real service through secure settings (the same grant the
user gives in Settings), blocks `com.android.settings`, and cleans up the rule, schedule, and
grant afterwards.

## Known limits (unchanged from design)

- OEM task killers and permission revocation can stop the service. M5 adds the foreground
  `specialUse` service, boot/update receivers, and the self-check; see
  [foreground-service.md](foreground-service.md).
- Usage Access is not required for schedule blocking. Daily timers use it and pause (with
  the state surfaced in the self-check) when it is missing or revoked.
- Short-form-only detection is not in v1; blocking is whole-app.
