package dev.brainrotstopper.app

import android.content.Context
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.model.PasswordMode
import dev.brainrotstopper.core.security.ConfigureResult
import dev.brainrotstopper.core.security.CredentialStore
import dev.brainrotstopper.core.security.EncryptedCredentialStore
import dev.brainrotstopper.core.security.PasswordCredential
import dev.brainrotstopper.core.security.PasswordKdf
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.feature.settings.PasswordSetupActivity
import dev.brainrotstopper.feature.settings.PasswordStore
import dev.brainrotstopper.feature.settings.R as SettingsR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests for M7. They run on the CI API 31 + API 36 matrix and the VPS KVM harness.
 *
 * - friend mode masks every input and shows the hand-over copy, and saving records FRIEND_SET;
 * - the production [EncryptedCredentialStore] round-trips a credential through the Android
 *   Keystore on a real device image.
 */
@RunWith(AndroidJUnit4::class)
class PasswordSetupTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var fakeStore: FakeCredentialStore
    private lateinit var fakeManager: PasswordManager

    @Before
    fun installFakeManager() {
        fakeStore = FakeCredentialStore()
        fakeManager = PasswordManager(fakeStore, PasswordKdf(iterations = 1_000))
        PasswordStore.setManagerForTests(fakeManager)
    }

    @After
    fun clearFakeManager() {
        PasswordStore.setManagerForTests(null)
    }

    @Test
    fun friendModeMasksInputAndSavesFriendMode() {
        val intent = PasswordSetupActivity.setupIntent(context, PasswordMode.FRIEND_SET)
        ActivityScenario.launch<PasswordSetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<TextView>(SettingsR.id.password_title)
                val instructions = activity.findViewById<TextView>(SettingsR.id.password_instructions)
                val oldField = activity.findViewById<EditText>(SettingsR.id.password_old)
                val newField = activity.findViewById<EditText>(SettingsR.id.password_new)
                val confirmField = activity.findViewById<EditText>(SettingsR.id.password_confirm)

                assertEquals(activity.getString(SettingsR.string.password_friend_title), title.text.toString())
                assertEquals(
                    activity.getString(SettingsR.string.password_friend_instructions),
                    instructions.text.toString(),
                )
                assertEquals(View.GONE, oldField.visibility)

                // The owner must not see the friend's entry: both fields use the password
                // transformation, so the value is rendered as dots even as it is typed.
                assertTrue(newField.transformationMethod is PasswordTransformationMethod)
                assertTrue(confirmField.transformationMethod is PasswordTransformationMethod)

                newField.setText("2468")
                confirmField.setText("2468")
                activity.findViewById<Button>(SettingsR.id.password_save).performClick()
            }
        }

        val credential = fakeStore.credential
        assertNotNull("friend setup must store a credential", credential)
        assertEquals(PasswordMode.FRIEND_SET, credential!!.mode)
        assertEquals(PasswordMode.FRIEND_SET, fakeManager.mode())
        assertTrue(fakeManager.verify("2468".toCharArray()))
    }

    @Test
    fun encryptedCredentialStoreRoundTripsOnThisDevice() {
        val store = EncryptedCredentialStore(context)
        store.clear()
        try {
            val manager = PasswordManager(store, PasswordKdf(iterations = 1_000))
            assertEquals(
                ConfigureResult.Saved,
                manager.configure("1357".toCharArray(), PasswordMode.SELF_SET),
            )

            // A second store instance proves the value survives process/structure boundaries,
            // not just one in-memory object.
            val reloaded = PasswordManager(
                EncryptedCredentialStore(context),
                PasswordKdf(iterations = 1_000),
            )
            assertTrue(reloaded.isConfigured())
            assertEquals(PasswordMode.SELF_SET, reloaded.mode())
            assertTrue(reloaded.verify("1357".toCharArray()))
            assertFalse(reloaded.verify("0000".toCharArray()))
        } finally {
            store.clear()
        }
    }

    private class FakeCredentialStore : CredentialStore {
        var credential: PasswordCredential? = null
            private set

        override fun read(): PasswordCredential? = credential

        override fun write(credential: PasswordCredential) {
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }
    }
}
