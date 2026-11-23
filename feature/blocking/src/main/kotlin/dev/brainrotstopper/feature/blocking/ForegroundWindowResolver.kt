package dev.brainrotstopper.feature.blocking

/**
 * A minimal, Android-free view of one interactive window.
 *
 * [type] uses the same values as `AccessibilityWindowInfo`: an application window is
 * [TYPE_APPLICATION], the soft keyboard is [TYPE_INPUT_METHOD], and so on.
 */
data class ForegroundWindowSnapshot(
    val type: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val packageName: String?,
)

/**
 * Picks the package of the real foreground application from the accessibility window list.
 *
 * The service must not treat a soft keyboard (or any non-application window) as "the user left
 * the app": doing so hides a block overlay the moment the password field gains focus. Only
 * [TYPE_APPLICATION] windows are considered, and an active one beats a merely focused one.
 *
 * Returns null when no application window is known, so callers can fall back to the event's
 * package instead of guessing.
 */
object ForegroundWindowResolver {

    /** `AccessibilityWindowInfo.TYPE_APPLICATION`; kept literal so this stays JVM-testable. */
    const val TYPE_APPLICATION: Int = 1

    fun resolve(windows: List<ForegroundWindowSnapshot>): String? {
        val applications = windows.filter { it.type == TYPE_APPLICATION && !it.packageName.isNullOrBlank() }
        if (applications.isEmpty()) return null

        val candidate = applications.firstOrNull { it.isActive }
            ?: applications.firstOrNull { it.isFocused }
            ?: applications.singleOrNull()
            ?: applications.first()
        return candidate.packageName
    }
}
