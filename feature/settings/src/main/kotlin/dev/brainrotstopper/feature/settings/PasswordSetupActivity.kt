package dev.brainrotstopper.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.core.model.PasswordMode
import dev.brainrotstopper.core.security.ChangeOutcome
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.core.security.PasswordPolicy
import dev.brainrotstopper.core.security.PasswordSetupModel
import dev.brainrotstopper.core.security.PasswordValidationProblem
import dev.brainrotstopper.core.security.SetupOutcome

/**
 * Self-set / friend-set password setup and the change-password form.
 *
 * Both flows mask every input, but friend mode is a distinct screen: it tells the owner to
 * hand the phone over and never shows the value back, so the owner cannot learn the password
 * by watching their own screen. The change flow requires the current password before a new
 * hash is written; see [PasswordManager.changePassword].
 */
class PasswordSetupActivity : Activity() {

    private lateinit var manager: PasswordManager
    private lateinit var model: PasswordSetupModel

    private lateinit var titleView: TextView
    private lateinit var instructionsView: TextView
    private lateinit var oldField: EditText
    private lateinit var newField: EditText
    private lateinit var confirmField: EditText
    private lateinit var errorView: TextView
    private lateinit var saveButton: Button

    private var isChange: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_setup)

        isChange = intent.getBooleanExtra(EXTRA_CHANGE, false)
        val requestedMode = parseMode(intent.getStringExtra(EXTRA_MODE))
        manager = PasswordStore.manager(this)
        val mode = if (isChange) {
            runCatching { manager.mode() }.getOrNull() ?: requestedMode
        } else {
            requestedMode
        }
        model = PasswordSetupModel(mode)

        titleView = findViewById(R.id.password_title)
        instructionsView = findViewById(R.id.password_instructions)
        oldField = findViewById(R.id.password_old)
        newField = findViewById(R.id.password_new)
        confirmField = findViewById(R.id.password_confirm)
        errorView = findViewById(R.id.password_error)
        saveButton = findViewById(R.id.password_save)

        // Set the transformation explicitly. The XML does it too, but this is the property the
        // friend-mode instrumentation test asserts, so keep it visible in code.
        listOf(oldField, newField, confirmField).forEach {
            it.transformationMethod = PasswordTransformationMethod.getInstance()
        }

        oldField.visibility = if (isChange) View.VISIBLE else View.GONE
        saveButton.setOnClickListener { save() }
        render()
    }

    private fun render() {
        val screen = model.screen()
        when {
            isChange -> {
                titleView.text = getString(R.string.password_change_title)
                instructionsView.text = getString(R.string.password_change_instructions)
            }
            screen.ownerMustNotSeePassword -> {
                titleView.text = getString(R.string.password_friend_title)
                instructionsView.text = getString(R.string.password_friend_instructions)
            }
            else -> {
                titleView.text = getString(R.string.password_self_title)
                instructionsView.text = getString(R.string.password_self_instructions)
            }
        }
        errorView.visibility = View.GONE
    }

    private fun save() {
        errorView.visibility = View.GONE

        val oldPassword = if (isChange) oldField.text.toString().toCharArray() else CharArray(0)
        val newPassword = newField.text.toString().toCharArray()
        val confirmation = confirmField.text.toString().toCharArray()

        try {
            if (isChange) {
                handleChange(model.submitChange(oldPassword, newPassword, confirmation, manager))
            } else {
                handleSetup(model.submit(newPassword, confirmation, manager))
            }
        } catch (t: Throwable) {
            // A broken keystore must fail closed with a visible message, not a crash and not a
            // silent "saved".
            showError(getString(R.string.password_error_store))
        } finally {
            oldPassword.fill('\u0000')
            newPassword.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    private fun handleSetup(outcome: SetupOutcome) {
        when (outcome) {
            SetupOutcome.Saved -> {
                val message = if (model.screen().ownerMustNotSeePassword) {
                    R.string.password_saved_friend
                } else {
                    R.string.password_saved_self
                }
                finishSaved(message)
            }
            SetupOutcome.AlreadyConfigured ->
                showError(getString(R.string.password_error_already_configured))
            SetupOutcome.Mismatch -> showError(getString(R.string.password_error_mismatch))
            is SetupOutcome.Invalid -> showError(validationMessage(outcome.problem))
        }
    }

    private fun handleChange(outcome: ChangeOutcome) {
        when (outcome) {
            ChangeOutcome.Changed -> finishSaved(R.string.password_saved_changed)
            ChangeOutcome.WrongOldPassword ->
                showError(getString(R.string.password_error_wrong_old))
            ChangeOutcome.NotConfigured ->
                showError(getString(R.string.password_error_not_configured))
            ChangeOutcome.Mismatch -> showError(getString(R.string.password_error_mismatch))
            is ChangeOutcome.Invalid -> showError(validationMessage(outcome.problem))
        }
    }

    private fun validationMessage(problem: PasswordValidationProblem): String = when (problem) {
        PasswordValidationProblem.TooShort ->
            getString(R.string.password_error_too_short, PasswordPolicy.MIN_LENGTH)
        PasswordValidationProblem.TooLong ->
            getString(R.string.password_error_too_long, PasswordPolicy.MAX_LENGTH)
    }

    private fun finishSaved(messageRes: Int) {
        clearFields()
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        setResult(RESULT_OK, Intent())
        finish()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun clearFields() {
        oldField.setText("")
        newField.setText("")
        confirmField.setText("")
    }

    private fun parseMode(name: String?): PasswordMode = when (name) {
        PasswordMode.FRIEND_SET.name -> PasswordMode.FRIEND_SET
        else -> PasswordMode.SELF_SET
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_CHANGE = "extra_change"

        fun setupIntent(context: Context, mode: PasswordMode): Intent =
            Intent(context, PasswordSetupActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)

        fun changeIntent(context: Context): Intent =
            Intent(context, PasswordSetupActivity::class.java)
                .putExtra(EXTRA_CHANGE, true)
    }
}
