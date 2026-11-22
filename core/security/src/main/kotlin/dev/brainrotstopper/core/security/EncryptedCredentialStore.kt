package dev.brainrotstopper.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.brainrotstopper.core.model.PasswordMode
import java.util.Base64

/**
 * Credential storage in [EncryptedSharedPreferences].
 *
 * The values (salt, hash, parameters) are encrypted with an Android Keystore master key
 * (AES-256-GCM values, AES-256-SIV keys). The file name is listed as a backup exclusion in
 * `app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`, and the app sets
 * `android:allowBackup="false"`, so a backup or device transfer cannot restore an old
 * credential over a newer one.
 *
 * Reads fail closed: an unknown newer version throws instead of looking "not configured",
 * because treating a parse failure as "no password" would be a bypass.
 */
class EncryptedCredentialStore(context: Context) : CredentialStore {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences by lazy { open() }

    override fun read(): PasswordCredential? {
        val values = prefs
        val salt = values.getString(KEY_SALT, null) ?: return null
        val hash = values.getString(KEY_HASH, null) ?: return null
        val modeName = values.getString(KEY_MODE, null) ?: return null
        val mode = runCatching { PasswordMode.valueOf(modeName) }.getOrNull() ?: return null

        val version = values.getInt(KEY_VERSION, PasswordCredential.CURRENT_VERSION)
        check(version <= PasswordCredential.CURRENT_VERSION) {
            "credential version $version is newer than this build supports"
        }

        return PasswordCredential(
            version = version,
            mode = mode,
            hash = PasswordHash(
                algorithm = values.getString(KEY_ALGORITHM, null) ?: PasswordKdf.ALGORITHM,
                iterations = values.getInt(KEY_ITERATIONS, PasswordKdf.DEFAULT_ITERATIONS),
                salt = Base64.getDecoder().decode(salt),
                hash = Base64.getDecoder().decode(hash),
            ),
            createdAtEpochMillis = values.getLong(KEY_CREATED_AT, 0L),
        )
    }

    override fun write(credential: PasswordCredential) {
        prefs.edit()
            .putInt(KEY_VERSION, credential.version)
            .putString(KEY_MODE, credential.mode.name)
            .putString(KEY_ALGORITHM, credential.hash.algorithm)
            .putInt(KEY_ITERATIONS, credential.hash.iterations)
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(credential.hash.salt))
            .putString(KEY_HASH, Base64.getEncoder().encodeToString(credential.hash.hash))
            .putLong(KEY_CREATED_AT, credential.createdAtEpochMillis)
            // Commit, not apply: the caller may report "saved" right after this returns, and a
            // crash before the async write would lose a credential the user believes is set.
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        /**
         * The prefs file is `shared_prefs/brainrot_credentials.xml`. The backup rules use the
         * `.xml` suffix; keep the two in sync.
         */
        const val FILE_NAME: String = "brainrot_credentials"

        private const val KEY_VERSION = "version"
        private const val KEY_MODE = "mode"
        private const val KEY_ALGORITHM = "algorithm"
        private const val KEY_ITERATIONS = "iterations"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_CREATED_AT = "created_at"
    }
}
