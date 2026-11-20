package dev.brainrotstopper.feature.onboarding

/**
 * Matches the flattened service ids reported by `AccessibilityManager` against our own
 * accessibility service. Kept pure so the matching rules are unit-tested without a device.
 */
object AccessibilityMatching {

    fun isGranted(
        enabledServiceIds: List<String>,
        packageName: String,
        className: String,
    ): Boolean = enabledServiceIds.any { id -> matches(id, packageName, className) }

    private fun matches(id: String, packageName: String, className: String): Boolean {
        val idPackage = id.substringBefore('/', missingDelimiterValue = "")
        val idClass = id.substringAfter('/', missingDelimiterValue = "")
        if (idPackage != packageName || idClass.isEmpty()) return false
        val shortClass = "." + className.removePrefix("$packageName.")
        return idClass == className || idClass == shortClass
    }
}
