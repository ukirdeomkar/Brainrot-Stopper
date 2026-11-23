# Rules & schedule editor (M3)

The first user-facing control surface. It reuses the M2 Room repositories, so every change is
stored on the device and survives a process restart. UI is plain Android views, matching the
rest of the app (no Compose dependency yet).

## Screens

| Screen | Activity | Purpose |
|---|---|---|
| Apps and schedules | `RulesActivity` | Lists guarded apps and schedules. Inline switches enable/disable a rule or schedule. Buttons open the picker and the two editors. |
| Add apps | `AppPickerActivity` | Lists installed apps that have a launcher entry and are not already guarded. Multi-select adds `AppRule` rows. |
| Edit app rule | `RuleEditorActivity` | Enabled state, whole-app blocking flag, the optional per-app daily limit, and the schedules assigned to the app. Remove deletes the rule but keeps its schedules. |
| Edit schedule | `ScheduleEditorActivity` | Name, day toggles, and any number of start/end windows using the system time picker. Save is blocked until the draft is valid. |

An app with no schedule and no daily limit is stored but does not block; assign at least one
schedule in the app editor. The demo schedule seeded by `MainActivity` is only scaffolding for
the shell status text.

## Data flow

```
Activity --suspend calls--> RuleRepository (:core:data) --> Room --> brainrot.db
   |                                                               ^
   +-- RulesStore -> one process-wide RoomRuleRepository ----------+
```

`RulesStore` is the only place that opens the database for the rules screens, so a save in an
editor is visible to the list when the user returns. `RulesStore.setRepositoryForTests` is an
instrumentation-only seam.

## Validation

`ScheduleValidator` lives in `:core:model` (pure Kotlin, no Android types):

- at least one day;
- at least one window;
- no zero-length window (same start and end);
- no two overlapping windows.

Overlap detection expands each window on every selected day into an absolute minute interval
and also compares the neighbouring weeks. That matters for overnight windows: a Monday
22:00-02:00 window overlaps a Tuesday 01:00-02:00 window **when Tuesday is selected**, and does
not when only Monday is selected. The editor shows the offending window numbers and writes
nothing until the problems are gone.

The activity layer still checks the schedule name, because `BlockSchedule` requires a
non-blank label.

## Daily limit

`RuleEditorModel` parses the "Daily limit" field and builds the `AppRule`:

- an empty or blank field clears the limit (`dailyLimitMinutes = null`);
- otherwise the value must be a whole number from `1` to `1440` minutes (24 hours);
- non-numeric, zero/negative, and out-of-range input shows an error and writes nothing.

`daily_limit_minutes` already exists in the M2 `app_rule` table, so no schema change is
needed. The rule list shows `Daily limit: N min` or `No daily limit` under each app.

A null limit keeps daily timers off for that app: `DecisionEngine` only evaluates the timer
branch when `dailyLimitMinutes != null`. A set limit is enforced by M6 tracking at
`usedMinutes >= limitMinutes`.

The "Always block this app (whole app)" switch writes `blockAll`. An enabled rule with
`blockAll = true` is blocked at all times by `DecisionEngine`; schedules and the daily limit
are not required. Apps added from the picker start with `blockAll = true`, and the rules list
labels each rule "Always blocked", "Blocked during schedules", "Daily limit only", or flags
"Not blocking" so a selection can never be silently inert. See
[blocking-engine.md](blocking-engine.md).

## Password gate

Every mutating action (save, delete, inline toggle, add apps, new schedule) calls
`RuleChangeGate`, which delegates to `PasswordGate`. When an unblock password is configured,
the user must enter it before the write runs; a wrong password, a cancelled prompt, or an
unreadable credential store aborts the change and shows an error. Inline switches revert
visually while the prompt is open so the list never shows an unconfirmed state. Before a
credential exists, changes are allowed so first-run setup works. See
[emergency-unlock.md](emergency-unlock.md).

## Picker visibility

The module manifest declares only the narrow launcher-intent `<queries>` entry:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

This is not `QUERY_ALL_PACKAGES`. Apps without a launcher entry (service-only packages) are
not listed, and the app's own package is excluded.

## Tests

JVM (`./gradlew testDebugUnitTest`):

- `ScheduleValidationTest` — empty days/windows, zero-length, same-day overlap, overnight
  boundaries, week wrap.
- `ScheduleEditorModelTest` — load/edit/add/remove, validation before build.
- `RuleEditorModelTest` — daily-limit load/set/clear, number and bounds validation, build.
- `DailyLimitEditorToBlockingTest` — an editor-saved limit blocks at the exact M6 boundary.
- `ScheduleFormatTest`, `InstalledAppsTest`, `RuleChangeGateTest`.

Emulator (`RulesEditorFlowTest`, API 31 + API 36 in CI, API 31 on the VPS):

1. create a schedule through the UI, then reopen the list and see it;
2. save is refused with no windows;
3. disable an app rule from the list;
4. assign a schedule in the app editor;
5. set a daily limit, see it after reopening and in the list, then clear it;
6. an invalid daily limit is refused without writing;
7. the app picker lists installed launcher apps.

`RoomRuleRepositoryTest` in `:core:data` proves the disk round-trip, including schedule links.
