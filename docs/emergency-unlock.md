# Emergency unblock + settings password gate (M8)

M8 turns the M7 credential into enforcement. It adds two password paths:

1. **Emergency unblock** — the block overlay asks for the unblock password, writes a
   time-boxed `UnlockGrant`, and re-arms blocking automatically when the grant expires.
2. **Settings gate** — every rule, schedule, timer, or password change asks for the unblock
   password first. A wrong password or a cancelled prompt changes nothing.

The product reasoning is in [design §4](design.md#4-password--accountability): this is a
commitment device and an accountability tool, not a jail. Clearing app data still resets the
app, as documented.

## Emergency unblock

### Flow

1. The user taps **Unlock for 5 minutes** on the block overlay.
2. The overlay swaps to a masked password field with Cancel / Unlock. The field disables
   autofill and saved-instance-state, uses `PasswordTransformationMethod`, and is cleared after
   every attempt.
3. `UnlockFlow` runs `PasswordUnlockGate.authorize` on `Dispatchers.IO` (PBKDF2 is deliberately
   expensive), passing the entered `CharArray`.
4. On success it writes a package-scoped `UnlockGrant` expiring at `now + 5 minutes`,
   refreshes the cache, verifies the grant is visible, then dismisses the overlay.
5. On a wrong password the grant is not written; the overlay shows “Wrong password. This app
   stays blocked.” and keeps the user in the block screen. A broken credential store fails
   closed with the same outcome.
6. The characters are zeroed after the attempt.

The process default gate is `DenyAllUnlockGate`, so an unlock attempt before the
AccessibilityService installs the real gate cannot succeed. The service installs
`PasswordUnlockGate`, which resolves the process-wide `PasswordManager` lazily so a password
change is picked up without a restart.

### Auto re-arm

A grant is only an exemption, never a permanent switch:

- `DecisionEngine` allows a package while an active grant covers it and blocks it again as soon
  as `expiresAtEpochMillis <= now` (half-open, so the exact expiry instant is blocked).
- After every completed unlock the service reads the earliest active grant expiry and schedules
  one refresh at `expiry + 250 ms`. That refresh loads the repository (which excludes expired
  grants) and re-applies the decision, so the overlay comes back even if the user never left
  the app.
- A process restart during a grant is covered too: each service refresh reschedules the re-arm
  check from the currently cached grants.

`UnlockFlow.SESSION_UNLOCK_MILLIS` is the 5-minute default. `BlockingRuntime` exposes a
test-only override so the emulator test can observe expiry without waiting five minutes.

## Settings password gate

`RulesActivity`, `RuleEditorActivity`, `ScheduleEditorActivity`, and `AppPickerActivity` route
every mutating action through `RuleChangeGate`, which delegates to `PasswordGate`:

- **No credential configured:** the change is allowed, so first-run setup works.
- **Credential configured:** `PasswordGate` shows a masked password dialog and verifies on a
  worker thread. Only a correct password runs the caller’s write.
- **Wrong / cancelled / unreadable store:** the write is refused. Both inline switches revert
  visually while the prompt is open, so the list never shows a state the repository did not
  accept.

The password change screen already requires the old password (`PasswordSetupActivity`), so it
is gated by the same rule. There is no remove-password path.

## Code map

| File | Responsibility |
|---|---|
| `feature/blocking/.../UnlockGate.kt` | `UnlockRequest` (+ password), `PasswordUnlockGate`, fail-closed default |
| `feature/blocking/.../UnlockFlow.kt` | Gate on IO, grant write, refresh, dismiss, zeroing |
| `feature/blocking/.../BlockOverlayController.kt` | Two-stage overlay (unlock button → masked password field) |
| `feature/blocking/.../BrainrotAccessibilityService.kt` | Installs the gate, shows errors, schedules the re-arm refresh |
| `feature/blocking/.../BlockingStateCache.kt` | `nextGrantExpiry` for the re-arm check |
| `core/security/.../PasswordManagerProvider.kt` | One process-wide `PasswordManager` for overlay + settings |
| `feature/settings/.../PasswordGate.kt` | Masked challenge dialog + verification on a worker thread |
| `feature/rules/.../RuleChangeGate.kt` | The settings seam that delegates to `PasswordGate` |

## Tests

JVM (`./gradlew testDebugUnitTest`):

- `UnlockFlowTest` — a wrong password and a missing password are rejected with no grant;
  a grant is active just before expiry and blocking re-arms at the exact expiry instant; the
  expired row is purged.
- `PasswordManagerTest` / `PasswordKdfTest` — the M7 credential rules still hold.

Emulator (`:app:connectedDebugAndroidTest`, CI API 31 + API 36):

- `BlockingOverlayTest` — overlay appears, back does not dismiss it, a wrong password is
  rejected and keeps the block up, the correct password unlocks, and a short grant re-arms the
  overlay with no window change.
- `SettingsPasswordGateTest` — disabling a guarded app opens the gate; a wrong password leaves
  the rule enabled; the correct password applies the change; an unconfigured credential allows
  first-run setup.

The CI emulator matrix is the release gate; the VPS KVM harness is supplementary evidence.
