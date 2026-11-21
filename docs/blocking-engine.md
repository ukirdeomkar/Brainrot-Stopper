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

## Unlock split (M4 vs M7)

The surrounding flow is production-shaped in M4:

1. the overlay's unlock button calls `BrainrotAccessibilityService.requestUnlock`;
2. that calls the injected `UnlockGate`;
3. on approval it writes a 5-minute package-scoped `UnlockGrant` through the M2
   `UnlockGrantRepository`, refreshes the cache, and hides the overlay.

M4 ships `PlaceholderUnlockGate`, which always approves, so QA can verify dismissal before
the credential work exists. **M7 replaces the gate with the real PBKDF2 password check and
must not ship v1 with the placeholder.** The grant, refresh, and dismissal code does not
change.

`BlockingRuntime` exposes the verification hooks used by tests and later UI:

- `isAttached()` — a service owns the engine;
- `decisionFor(package)` — the current cached verdict;
- `isUnlocked(package, now)` — a cached grant covers the package;
- `requestUnlock(package)` — run the unlock flow (still goes through the gate).

## Verification (server-side only)

| Check | Command / flow |
|---|---|
| Engine unit tests | `./gradlew :feature:blocking:testDebugUnitTest` |
| App builds | `./gradlew assembleDebug` |
| Overlay appears | Instrumentation `BlockingOverlayTest` on CI API 31 + API 36; `dumpsys window windows` contains `BrainrotBlockerOverlay` |
| Back does not dismiss | `input keyevent KEYCODE_BACK`, then `dumpsys window windows` still contains the overlay |
| Unlock hides it | `BlockingRuntime.requestUnlock(package)` -> overlay gone and `isUnlocked(package)` true |
| Latency | `adb logcat -s BrainrotBlocking` shows `Block overlay shown ... in N ms` |

`BlockingOverlayTest` enables the real service through secure settings (the same grant the
user gives in Settings), blocks `com.android.settings`, and cleans up the rule, schedule, and
grant afterwards.

## Known limits (unchanged from design)

- OEM task killers and permission revocation can stop the service; M5 owns the foreground
  service, boot receiver, and self-check banner.
- Usage Access is not required for schedule blocking. Timers stay at zero usage until M6
  populates `usage_bucket`, so they do not block yet.
- Short-form-only detection is not in v1; blocking is whole-app.
