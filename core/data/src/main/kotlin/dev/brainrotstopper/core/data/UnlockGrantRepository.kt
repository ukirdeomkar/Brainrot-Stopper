package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.UnlockGrant

/** Time-boxed bypass grants. Expired grants are never returned. */
interface UnlockGrantRepository {
    suspend fun save(grant: UnlockGrant)
    suspend fun revoke(id: String)

    /** All grants active at [nowEpochMillis], soonest expiry first. */
    suspend fun active(nowEpochMillis: Long): List<UnlockGrant>

    /** The earliest-expiring grant covering [packageName] (a global grant matches any app). */
    suspend fun activeFor(packageName: String, nowEpochMillis: Long): UnlockGrant?

    /** Deletes expired grants, returning how many rows were removed. */
    suspend fun purgeExpired(nowEpochMillis: Long): Int
}
