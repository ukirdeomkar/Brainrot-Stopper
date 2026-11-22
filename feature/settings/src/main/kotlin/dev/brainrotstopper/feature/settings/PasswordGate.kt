package dev.brainrotstopper.feature.settings

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.core.security.PasswordManagerProvider

/**
 * Password challenge shown before a change that weakens blocking (rules, schedules, timers,
 * or the credential itself).
 *
 * Behaviour:
 * - When no credential is configured yet the change is allowed: there is no secret to verify
 *   and the app must stay configurable for first-run setup.
 * - When a credential is configured the caller's change runs only after [PasswordManager.verify]
 *   succeeds. A wrong password leaves the dialog open with an inline error, and the caller's
 *   side effect must not run; verification happens on a worker thread because PBKDF2 is
 *   deliberately expensive.
 * - A broken credential store fails closed: the change is refused instead of silently allowed.
 *
 * The dialog is intentionally plain `android.app` UI so it works from the plain
 * `android.app.Activity` screens in `:feature:rules` without adding an AndroidX UI dependency.
 */
object PasswordGate {

    @Volatile
    private var dialogForTests: AlertDialog? = null

    fun request(activity: Activity, onResult: (Boolean) -> Unit) {
        val manager = runCatching { PasswordManagerProvider.manager(activity) }.getOrNull()
        if (manager == null) {
            onResult(false)
            return
        }
        val configured = runCatching { manager.isConfigured() }.getOrElse {
            // A credential store that cannot be read must not become a bypass.
            onResult(false)
            return
        }
        if (!configured) {
            onResult(true)
            return
        }
        show(activity, manager, onResult)
    }

    private fun show(
        activity: Activity,
        manager: PasswordManager,
        onResult: (Boolean) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val input = EditText(activity).apply {
            id = R.id.password_gate_input
            hint = activity.getString(R.string.password_gate_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            imeOptions = EditorInfo.IME_ACTION_DONE
            tag = "password_gate_input"
        }
        val error = TextView(activity).apply {
            id = R.id.password_gate_error
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                error,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        var resultDelivered = false
        var closed = false
        fun deliver(granted: Boolean) {
            if (resultDelivered) return
            resultDelivered = true
            onResult(granted)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.password_gate_title)
            .setMessage(R.string.password_gate_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ -> deliver(false) }
            .setPositiveButton(R.string.password_gate_confirm, null)
            .setOnCancelListener { deliver(false) }
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val entered = input.text.toString().toCharArray()
                input.setText("")
                positive.isEnabled = false
                error.visibility = View.GONE

                Thread({
                    val granted = try {
                        manager.verify(entered)
                    } catch (t: Throwable) {
                        false
                    } finally {
                        entered.fill('\u0000')
                    }
                    activity.runOnUiThread {
                        if (closed) return@runOnUiThread
                        if (granted) {
                            // Mark the terminal outcome before dismissing so the dismiss
                            // listener cannot turn a success into a refusal.
                            resultDelivered = true
                            dialogForTests = null
                            dialog.dismiss()
                            onResult(true)
                        } else {
                            positive.isEnabled = true
                            error.text = activity.getString(R.string.password_gate_wrong)
                            error.visibility = View.VISIBLE
                            input.requestFocus()
                        }
                    }
                }, "password-gate-verify").start()
            }
        }
        dialog.setOnDismissListener {
            closed = true
            dialogForTests = null
            deliver(false)
        }

        dialogForTests = dialog
        dialog.show()
    }

    /** Test seam: the dialog most recently opened by [request], or null. */
    fun lastDialogForTests(): AlertDialog? = dialogForTests

    /**
     * Test seam: type [password] into the open dialog and press Confirm. Returns false when no
     * dialog is open. The value still goes through the real [PasswordManager] verification.
     */
    fun submitForTests(password: String): Boolean {
        val dialog = dialogForTests ?: return false
        val input = dialog.findViewById<EditText>(R.id.password_gate_input) ?: return false
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return false
        input.setText(password)
        positive.performClick()
        return true
    }
}
