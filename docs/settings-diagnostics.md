# Settings & diagnostics (M9)

M9 adds the read-only screen that answers "is the guard working right now?". It is opened from
**Settings & diagnostics** on the home screen and lives in `:feature:settings`. Design
rationale is in [design §1.1 and §5](design.md); this page is what QA verifies.

The screen has four sections and no write path:

1. **Blocking engine** — live self-check: engine running, background service running, last state
   refresh, Usage Access, timers available.
2. **Permissions** — every permission the app asks for, with its current grant state. A revoked
   permission stays in the list (red, "not granted") and is repeated in the attention summary;
   it is never hidden.
3. **Today: per-app usage** — the authoritative `usage_bucket` rows for the current local day,
   highest usage first, with the configured daily limit when the app has a rule.
4. **Recent blocks (local only)** — the newest `block_event` rows (at most 20) with reason,
   start time, and release time or "still blocking".

## Components

| Component | File | Responsibility |
|---|---|---|
| `SettingsActivity` | `feature/settings/.../SettingsActivity.kt` | Plain `Activity`: loads on every resume, renders the four sections, has no export/upload control |
| `DiagnosticsStore` | `feature/settings/.../DiagnosticsStore.kt` | Shared Room instance, live `BlockingRuntime.status()`, permission checker; instrumentation test seam |
| `DiagnosticsAssembler` | `feature/settings/.../DiagnosticsReport.kt` | Pure assembly: rule display names + limits, usage ordering/filtering, duration formatting |
| `PermissionSnapshot` | `feature/onboarding/.../PermissionSnapshot.kt` | Reused degraded-mode rules for the attention summary |

`SettingsActivity` reads only rule names, usage totals, and block events. It never opens the
credential store, so the password hash and mode cannot reach this screen.

## Data and privacy

- Usage and block events are read from the local Room database. Nothing is uploaded or exported,
  and the screen has no share/export button.
- Old usage buckets are already retained for a rolling 30 days for diagnostics only (M6); M9
  surfaces only today's rows.
- No diagnostics value is logged. The new code has no `android.util.Log` calls and does not touch
  `PasswordManager` or `EncryptedSharedPreferences`.
- The block-event row cap is 20 on screen; the repository already caps the table at
  `BlockEventRepository.DEFAULT_LIMIT` (500).

## Block-event capture

The events are written by the blocking engine, not by this screen. When the AccessibilityService
attaches the overlay it calls `BlockingEngine.recordBlockStarted(package, reason)`; when the
overlay goes away (unlock, schedule end, package change, service stop) it calls
`recordBlockReleased(package)`. `BlockEventRecorder` serialises both calls on a background
consumer, so a release queued immediately after a record still closes the same row, and the
table is trimmed to its cap on every insert.

At process start the engine closes any row that a previous process left open, because no
overlay can outlive the process that owned it. That keeps "still blocking" truthful after a
force-stop or an OEM kill. All of this is local diagnostics only.

## Failure behaviour

- A permission read exception is shown as **not granted** rather than removing the row, so a
  broken check fails closed.
- A usage or block-event read failure shows an inline "could not be read right now" message for
  that section; the other sections still render.
- A failure to assemble the report shows a single error line and never crashes the screen.

## Verification

JVM unit tests cover naming, ordering, limits, attention summary, and duration formatting:
`feature/settings/src/test/.../DiagnosticsAssemblerTest.kt`.

Emulator test (API 31 + API 36) injects a known engine snapshot, permission map, usage row, and
block rows through `DiagnosticsStore` and asserts:

- `Running: on` / `Background service: on` match the injected engine snapshot, and the off case
  reports `Running: off` (values come from live state, not hardcoded);
- a denied Usage Access row says **not granted** and the attention summary names Daily timers;
- today's usage shows the rule name and "42m of 30m" for a 30-minute limit;
- released and open block events render their reason and release state;
- the privacy line states nothing is exported or uploaded.

Test: `app/src/androidTest/.../SettingsDiagnosticsTest.kt`.

QA must also confirm on the emulator that a password set before opening the screen produces no
password, hash, or salt in `logcat` while the screen loads and refreshes, and that revoking
Usage Access in Android Settings and returning to the screen changes the row to **not granted**
without restarting the app.
