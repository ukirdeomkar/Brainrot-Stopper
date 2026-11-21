package dev.brainrotstopper.feature.rules

/**
 * Authorization seam for edits that change rules or schedules.
 *
 * M3 ships [AllowAll] because no password exists yet. M7 replaces the authorizer with a
 * password-backed implementation; the activities already refuse the write when it returns
 * false, so no UI code has to change then.
 */
fun interface RuleChangeAuthorizer {
    fun isAuthorized(): Boolean
}

object RuleChangeGate {

    @Volatile
    var authorizer: RuleChangeAuthorizer = AllowAll

    fun isAuthorized(): Boolean = authorizer.isAuthorized()

    object AllowAll : RuleChangeAuthorizer {
        override fun isAuthorized(): Boolean = true
    }
}
