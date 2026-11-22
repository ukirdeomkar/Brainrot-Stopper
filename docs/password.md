# Password / unblock credential (M7)

The app has one local unblock credential. It is a commitment device: it adds friction to
impulse bypass, and in friend mode it makes a second person the holder. It is **not** a jail.
See [design §4](design.md#4-password--accountability) for the product reasoning.

This document covers what M7 shipped. M8 wires the credential into the block overlay and the
settings gate; see [emergency-unlock.md](emergency-unlock.md).

## What M7 ships

- **KDF:** PBKDF2-HMAC-SHA256 with a 128-bit (16 byte) random salt from `SecureRandom`,
  **600,000 iterations**, and a 256-bit derived key. These are the OWASP 2023 floor values
  recorded in [design §4.1](design.md#41-credential-storage).
- **Constant-time compare:** verification uses `MessageDigest.isEqual` on the derived bytes,
  never `String.equals`.
- **Storage:** `EncryptedSharedPreferences` (`brainrot_credentials.xml`) under an Android
  Keystore master key. The stored record keeps `version`, `mode`, `algorithm`, `iterations`,
  `salt`, `hash`, and `created_at` so a future work-factor change is a migration.
- **Self-set flow:** the owner enters the password twice on `PasswordSetupActivity`.
- **Friend-set flow:** the screen tells the owner to hand the phone over; the friend enters the
  password twice. Every field uses Android's password transformation, so the owner cannot read
  the value off the screen, and the app only records `PasswordMode.FRIEND_SET`.
- **No recovery:** there is no reset API or UI. The documented escape hatch is clearing app
  data, which also erases rules ([design §4.3](design.md#43-what-the-password-actually-buys-honest-limits)).
- **Change requires the old password:** `PasswordManager.changePassword` refuses unless the old
  password verifies. A failed change leaves the stored credential untouched. The recorded
  `PasswordMode` is preserved across a change.

## Backup exclusion

The credential must not be restorable from a backup or device transfer, because an old backup
could roll the password back. The app sets:

- `android:allowBackup="false"` in `app/src/main/AndroidManifest.xml`;
- `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+);
- `android:fullBackupContent="@xml/backup_rules"` (legacy path).

Both rule files explicitly exclude `domain="sharedpref" path="brainrot_credentials.xml"`. The
`BackupRulesTest` JVM test fails if any of those three guards is removed or if the credential
file is renamed without updating the rules.

## Code map

| File | Responsibility |
|---|---|
| `core/security/.../PasswordKdf.kt` | PBKDF2 derive/hash/verify, parameter constants |
| `core/security/.../PasswordCredential.kt` | Stored record shape and version |
| `core/security/.../CredentialStore.kt` | Storage seam (production + test double) |
| `core/security/.../EncryptedCredentialStore.kt` | `EncryptedSharedPreferences` implementation |
| `core/security/.../PasswordPolicy.kt` | Length validation |
| `core/security/.../PasswordManager.kt` | Configure, verify, change (old password required) |
| `core/security/.../PasswordSetupModel.kt` | Screen descriptor and form outcomes |
| `feature/settings/.../PasswordStore.kt` | Process-wide manager + test seam |
| `feature/settings/.../PasswordSetupActivity.kt` | Self-set, friend-set, and change screens |

## Tests

JVM (`./gradlew testDebugUnitTest`):

- `PasswordKdfTest` — PBKDF2 reference vector, salt/iteration/key sizes, correct and wrong
  password, wrong salt, unknown algorithm, and the shipped 600k default.
- `PasswordManagerTest` — setup once only, wrong-password rejection, friend mode, change needs
  the old password, failed change leaves the credential intact, too-short rejection.
- `PasswordSetupModelTest` — self/friend screen flags, confirmation mismatch, and both flows.
- `BackupRulesTest` — manifest and backup rules exclusion guardrail.

Emulator (`:app:connectedDebugAndroidTest`, CI API 31 + API 36):

- `PasswordSetupTest.friendModeMasksInputAndSavesFriendMode` — friend copy, masked fields,
  stored `FRIEND_SET`.
- `PasswordSetupTest.encryptedCredentialStoreRoundTripsOnThisDevice` — the real
  `EncryptedSharedPreferences` store round-trips and rejects a wrong password on the image.

## Deliberate limits

- A password field's `EditText` still holds the characters while the screen is open; Android
  offers no wipeable text buffer. The fields disable autofill and saved instance state
  (`importantForAutofill="no"`, `saveEnabled="false"`).
- A rooted or unlocked device can read process memory. PBKDF2 raises the cost of offline
  guessing; it does not defend a live device.
- Clearing app data or uninstalling removes the credential. This is the honest limit of a
  normal Android app and is repeated in the setup warning.
