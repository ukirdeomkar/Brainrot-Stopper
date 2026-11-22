package dev.brainrotstopper.app

import android.app.AlertDialog
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.PasswordMode
import dev.brainrotstopper.core.security.CredentialStore
import dev.brainrotstopper.core.security.PasswordCredential
import dev.brainrotstopper.core.security.PasswordKdf
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.feature.rules.RulesActivity
import dev.brainrotstopper.feature.rules.RulesStore
import dev.brainrotstopper.feature.settings.PasswordGate
import dev.brainrotstopper.feature.settings.PasswordStore
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the M8 settings password gate on API 31 and API 36.
 *
 * Disabling a guarded app weakens blocking, so it must require the unblock password. The test
 * drives the real [RulesActivity] toggle and the real [PasswordGate] dialog: a wrong password
 * must leave the rule enabled, and the correct password must let the change through.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPasswordGateTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var fakeStore: GateFakeCredentialStore
    private lateinit var fakeManager: PasswordManager
    private lateinit var repository: GateFakeRuleRepository

    @Before
    fun installFakes() {
        fakeStore = GateFakeCredentialStore()
        fakeManager = PasswordManager(fakeStore, PasswordKdf(iterations = 1_000))
        fakeManager.configure(PASSWORD.toCharArray(), PasswordMode.SELF_SET)
        PasswordStore.setManagerForTests(fakeManager)

        repository = GateFakeRuleRepository()
        repository.putRule(AppRule(packageName = PACKAGE, displayName = "Instagram", enabled = true))
        RulesStore.setRepositoryForTests(repository)
    }

    @After
    fun clearFakes() {
        PasswordStore.setManagerForTests(null)
        RulesStore.setRepositoryForTests(null)
    }

    @Test
    fun disablingARuleRequiresThePasswordAndAWrongOneChangesNothing() {
        ActivityScenario.launch<RulesActivity>(RulesActivity::class.java).use { scenario ->
            await("rule row renders") { tagExists(scenario, "rule_enabled_$PACKAGE") }

            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<Switch>("rule_enabled_$PACKAGE")
                    .performClick()
            }
            await("the password gate opens") { PasswordGate.lastDialogForTests() != null }

            // Bypass attempt: the wrong password must not disable the rule.
            scenario.onActivity { PasswordGate.submitForTests("0000") }
            await("the wrong password finishes verifying") {
                PasswordGate.lastDialogForTests()
                    ?.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.isEnabled == true
            }
            assertTrue(
                "a wrong password must leave the rule enabled",
                repository.snapshotRules().single().enabled,
            )
            assertTrue(
                "the gate stays open so the user can retry",
                PasswordGate.lastDialogForTests() != null,
            )

            // The correct password lets the weakening change through.
            scenario.onActivity { PasswordGate.submitForTests(PASSWORD) }
            await("the rule is disabled") {
                repository.snapshotRules().single().enabled.not()
            }
            await("the gate closes after a correct password") {
                PasswordGate.lastDialogForTests() == null
            }
        }

        assertFalse(repository.snapshotRules().single().enabled)
    }

    @Test
    fun anUnconfiguredPasswordDoesNotBlockFirstRunSetup() {
        PasswordStore.setManagerForTests(PasswordManager(GateFakeCredentialStore(), PasswordKdf(iterations = 1_000)))

        ActivityScenario.launch<RulesActivity>(RulesActivity::class.java).use { scenario ->
            await("rule row renders") { tagExists(scenario, "rule_enabled_$PACKAGE") }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<Switch>("rule_enabled_$PACKAGE")
                    .performClick()
            }
            await("the rule is disabled without a prompt") {
                repository.snapshotRules().single().enabled.not()
            }
        }

        assertTrue("no dialog should open before a credential exists", PasswordGate.lastDialogForTests() == null)
    }

    private fun tagExists(scenario: ActivityScenario<*>, tag: String): Boolean {
        var exists = false
        scenario.onActivity { activity ->
            exists = activity.window.decorView.findViewWithTag<android.view.View>(tag) != null
        }
        return exists
    }

    private fun await(what: String, timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private companion object {
        const val PACKAGE = "com.instagram.android"
        const val PASSWORD = "2468"
    }
}

private class GateFakeCredentialStore : CredentialStore {
    private var credential: PasswordCredential? = null

    override fun read(): PasswordCredential? = credential

    override fun write(credential: PasswordCredential) {
        this.credential = credential
    }

    override fun clear() {
        credential = null
    }
}

private class GateFakeRuleRepository : RuleRepository {
    private val rules = ConcurrentHashMap<String, AppRule>()
    private val schedules = ConcurrentHashMap<String, BlockSchedule>()

    override suspend fun rules(): List<AppRule> = snapshotRules()

    override suspend fun upsertRule(rule: AppRule) {
        rules[rule.packageName] = rule
    }

    override suspend fun removeRule(packageName: String) {
        rules.remove(packageName)
    }

    override suspend fun schedules(): List<BlockSchedule> = snapshotSchedules()

    override suspend fun upsertSchedule(schedule: BlockSchedule) {
        schedules[schedule.id] = schedule
    }

    override suspend fun removeSchedule(id: String) {
        schedules.remove(id)
    }

    fun snapshotRules(): List<AppRule> = rules.values.toList()

    fun snapshotSchedules(): List<BlockSchedule> = schedules.values.toList()

    fun putRule(rule: AppRule) {
        rules[rule.packageName] = rule
    }
}
