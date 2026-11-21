package dev.brainrotstopper.core.model

/**
 * A short-lived permission to bypass blocking.
 *
 * A null [packageName] grants a global bypass; otherwise it applies only to that package.
 * Grants are time-boxed so an emergency unlock re-arms automatically.
 */
data class UnlockGrant(
    val id: String,
    val packageName: String?,
    val expiresAtEpochMillis: Long,
    val reason: String,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(expiresAtEpochMillis > 0) {
            "expiresAtEpochMillis must be positive, was $expiresAtEpochMillis"
        }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }

    fun isActiveAt(epochMillis: Long): Boolean = expiresAtEpochMillis > epochMillis

    fun covers(packageName: String): Boolean = this.packageName == null || this.packageName == packageName
}
