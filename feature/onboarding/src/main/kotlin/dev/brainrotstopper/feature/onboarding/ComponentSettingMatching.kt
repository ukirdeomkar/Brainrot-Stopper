package dev.brainrotstopper.feature.onboarding

/**
 * Matches a colon-separated secure-setting value (notification listeners, accessibility
 * services) against our component. This is a fallback for when the framework manager has not
 * refreshed its cache yet; the setting itself is what the Settings UI writes.
 */
object ComponentSettingMatching {

    fun containsComponent(
        rawSetting: String?,
        packageName: String,
        className: String,
    ): Boolean {
        if (rawSetting.isNullOrBlank()) return false
        return rawSetting.split(':').any { entry -> matches(entry.trim(), packageName, className) }
    }

    private fun matches(entry: String, packageName: String, className: String): Boolean {
        val entryPackage = entry.substringBefore('/', missingDelimiterValue = "")
        val entryClass = entry.substringAfter('/', missingDelimiterValue = "")
        if (entryPackage != packageName || entryClass.isEmpty()) return false
        val shortClass = "." + className.removePrefix("$packageName.")
        return entryClass == className || entryClass == shortClass
    }
}
