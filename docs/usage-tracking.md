# Usage tracking and daily timers (M6)

> Operating contract for `UsageTracker`, the authoritative per-app daily total, the local-day
> reset, and the revoked-Usage-Access degradation. Design rationale lives in
> [design.md](design.md) §1.2 and §3; this page is what QA verifies.

## Components

| Component | File | Responsibility |
|---|---|---|
| `UsageTracker` | `feature/timers/.../UsageTracker.kt` | Polls, reconciles, and rewrites today's buckets; publishes tracking state |
| `UsageAccessChecker` / `AppOpsUsageAccessChecker` | `feature/timers/.../UsageAccessChecker.kt`, `AppOpsUsageAccessChecker.kt` | Reads the `PACKAGE_USAGE_STATS` app-op |
| `UsageStatsSource` / `AndroidUsageStatsSource` | `feature/timers/.../UsageStatsSource.kt`, `AndroidUsageStatsSource.kt` | Wraps `UsageStatsManager.queryAndAggregateUsageStats` |
| `UsageTrackingSnapshot` | `feature/timers/.../UsageTrackingSnapshot.kt` | Access status, last poll, day key, tracked count, failure flag |
| `BlockingForegroundService` | `feature/blocking/.../BlockingForegroundService.kt` | 60 s sync loop plus screen/clock/timezone triggers |
| `BlockingStateCache` | `feature/blocking/.../BlockingStateCache.kt` | Rebuilds timer inputs for the current local day; pauses them when access is revoked |
| `DecisionEngine` | `feature/blocking/.../DecisionEngine.kt` | Blocks at `usedMinutes >= limitMinutes` |

## Collection and reconciliation

The foreground service runs one sync immediately after the engine loads, then every 60 s:

1. `engine.refresh()` loads rules, schedules, grants, and stored usage.
2. `engine.pollUsage()` asks the tracker to reconcile every enabled rule that has a daily
   limit.
3. `engine.refresh()` loads the rewritten buckets into the decision snapshot.

The tracker calls `UsageStatsManager.queryAndAggregateUsageStats(dayStart, now)` for the
current local day and stores the reported foreground total directly:

```text
usage_bucket(package_name, epoch_day) := total_time_in_foreground_millis
```

It never adds to the previous value. A duplicate tick, a missed tick, a service restart, or a
retry therefore cannot inflate usage. Repeating the poll with the same platform total rewrites
the same row with the same value.

`BlockingStateCache` converts millis to whole minutes with integer division and hands the
result to `DecisionEngine`. A 30-minute limit therefore blocks once `usedMillis` reaches
`1_800_000`, and the last partial minute cannot be spent past the limit.

## Local day, midnight, and timezone changes

- The day key is `LocalDate.ofInstant(now, zone).toEpochDay()` in the device timezone.
- The query window is `startOfDay(now, zone) .. now` in the same zone.
- Both use the same `instantSource`/`zoneSource` pair, so a clock or timezone change cannot
  combine one day's key with another day's window.
- The service also synchronises immediately on `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`,
  `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, and `ACTION_DATE_CHANGED`, so a rollover
  does not wait for the next 60 s tick.
- `TimerState` carries the `epochDay` it was measured on, and `DecisionEngine` ignores a
  snapshot for a different day. The next poll writes a new bucket under the new key, which is
  what makes the daily timer reset at local midnight.

Old buckets are retained for 30 local days for local diagnostics only; nothing is uploaded.

## Blocking and degradation

- A timer blocks at `used >= limit`, never `> limit`; `releaseAt` is the next local midnight
  in the supplied timezone.
- `AppOpsUsageAccessChecker` reads `OPSTR_GET_USAGE_STATS`. An empty `UsageStatsManager`
  result is ambiguous, so the tracker always checks the app-op first and never treats an
  empty map as "usage access missing" on its own.
- **Access revoked:** the tracker writes nothing, publishes `UsageAccessStatus.REVOKED`, and
  `BlockingStateCache` stops loading timer inputs. Schedules still block; daily timers pause.
  The cached buckets are kept so a re-grant resumes from authoritative data. No crash, no
  silent enforcement of stale usage.
- **Before the first check:** the snapshot is `UNDETERMINED` and timers remain available, so a
  process restart cannot open a short window where an exhausted budget stops blocking.
- **Transient read failure while access is granted:** the poll is marked failed and the last
  authoritative total stays in force. The next tick retries.
- The shell self-check shows `Usage Access: granted / not granted / not checked yet` and
  `Daily timers: on / off`; a granted access whose last poll failed reads
  `granted (last poll failed)`.

## Verification (server-side only)

Fast JVM checks (run in CI on both API job variants through `testDebugUnitTest`):

```bash
./gradlew :feature:timers:testDebugUnitTest
./gradlew :feature:blocking:testDebugUnitTest
```

`UsageTrackerTest` covers authoritative rewrite versus increment, idempotent repeats, the
midnight rollover, a timezone-driven day/window change, revoked access and re-grant, source
failure, an empty tracked list, and the 30-day retention window.
`BlockingStateCacheTest` covers blocking at the exact limit, rollover isolation, and pausing
timers while Usage Access is revoked.

Emulator checks used by QA (debug build, so `run-as` works):

```bash
# Grant or revoke Usage Access exactly as the Settings screen does
adb shell appops set dev.brainrotstopper GET_USAGE_STATS allow
adb shell appops get dev.brainrotstopper GET_USAGE_STATS
adb shell appops set dev.brainrotstopper GET_USAGE_STATS deny

# Read the self-check lines
adb shell am start -W -n dev.brainrotstopper/dev.brainrotstopper.app.MainActivity

# Inspect the authoritative buckets
adb shell run-as dev.brainrotstopper sqlite3 databases/brainrot.db \
  "select package_name, epoch_day, used_millis from usage_bucket order by epoch_day desc limit 10;"

# Timezone rollover (google_apis emulator images allow adb root)
adb root
adb shell setprop persist.sys.timezone Asia/Tokyo
```

Expected: with access granted, a tracked app's bucket grows only to the platform total;
denying access shows `Usage Access: not granted` and `Daily timers: off` with no crash while
schedule blocking still works; a timezone change moves the day key and starts a fresh bucket
without rewriting yesterday's row.

## Known limits (unchanged from design §3.3)

- UsageStats is derived from activity-resume events, not screen pixels. It can over-count an
  app that holds the foreground while the screen is off and under-count by up to one poll
  interval.
- Some OEMs batch or delay UsageStats writes, so a just-opened app can read as zero briefly.
- Work-profile apps and some system apps are not visible.
- Polling stops when neither the foreground service nor the accessibility service keeps the
  engine alive. In normal use the foreground service owns it.
- Usage Access is not required for schedule blocking; only daily timers degrade without it.
