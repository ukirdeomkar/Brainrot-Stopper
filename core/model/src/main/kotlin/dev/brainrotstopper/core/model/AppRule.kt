package dev.brainrotstopper.core.model

/** An app the user wants to guard, optionally with a daily budget. */
data class AppRule(
    val packageName: String,
    val displayName: String,
    val enabled: Boolean = true,
    val dailyLimitMinutes: Int? = null,
    val scheduleIds: Set<String> = emptySet(),
    /** Block the whole app at all times; no schedule or daily limit is required. */
    val blockAll: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(dailyLimitMinutes == null || dailyLimitMinutes > 0) {
            "dailyLimitMinutes must be positive when set, was $dailyLimitMinutes"
        }
    }
}
