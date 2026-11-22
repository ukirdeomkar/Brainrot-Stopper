package dev.brainrotstopper.app

import dev.brainrotstopper.core.security.EncryptedCredentialStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static guardrail for the M7 acceptance criterion "credential is excluded from backup".
 *
 * The credential lives in `shared_prefs/brainrot_credentials.xml`. This test fails if a later
 * change turns backup on, drops the exclusion, or renames the credential file without updating
 * the rules. It reads the source files, so it is fast and has no emulator dependency.
 */
class BackupRulesTest {

    @Test
    fun `app disables backup so no app data is backed up`() {
        val manifest = read("app/src/main/AndroidManifest.xml")

        assertTrue(
            "allowBackup must stay false",
            Regex("""android:allowBackup\s*=\s*"false"""").containsMatchIn(manifest),
        )
        assertTrue(
            "manifest must point at the data extraction rules",
            manifest.contains("android:dataExtractionRules="),
        )
        assertTrue(
            "manifest must point at the legacy backup rules",
            manifest.contains("android:fullBackupContent="),
        )
    }

    @Test
    fun `credential file is excluded from cloud backup and device transfer`() {
        val credentialFileName = EncryptedCredentialStore.FILE_NAME + ".xml"

        val cloud = read("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue(
            "cloud backup must exclude $credentialFileName",
            exclusionMatches(cloud, credentialFileName),
        )
        assertTrue("device transfer must be declared", cloud.contains("<device-transfer>"))
        assertTrue(
            "device transfer must exclude $credentialFileName",
            cloud.substringAfter("<device-transfer>").contains("$credentialFileName"),
        )

        val legacy = read("app/src/main/res/xml/backup_rules.xml")
        assertTrue(
            "legacy backup rules must exclude $credentialFileName",
            exclusionMatches(legacy, credentialFileName),
        )
    }

    @Test
    fun `credential file name matches the backup rules`() {
        assertFalse(
            "a rename must be reflected in the rules",
            EncryptedCredentialStore.FILE_NAME.contains(".xml"),
        )
        assertTrue(EncryptedCredentialStore.FILE_NAME.isNotBlank())
    }

    private fun exclusionMatches(xml: String, fileName: String): Boolean {
        val pattern = "<exclude[^>]*domain=\"sharedpref\"[^>]*path=\"" +
            Regex.escape(fileName) + "\""
        return Regex(pattern).containsMatchIn(xml)
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("expected file at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return File(".").absoluteFile
        }
    }
}
