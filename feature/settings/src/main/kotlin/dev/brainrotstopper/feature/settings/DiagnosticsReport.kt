package dev.brainrotstopper.feature.settings

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.UsageBucket
import dev.brainrotstopper.feature.blocking.BlockingEngineStatus
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionSnapshot
import dev.brainrotstopper.feature.onboarding.PermissionStatus

/** One permission and its live grant state, in the order the settings screen shows it. */
data class PermissionStatusRow(
    val kind: PermissionKind,
    val status: PermissionStatus,
)

/** Today's authoritative usage for one app, with the configured limit when it has one. */
data class UsageRow(
    val packageName: String,
    val displayName: String,
    val usedMillis: Long,
    val limitMinutes: Int?,
)

/** One local block event, shown as diagnostics only and never uploaded. */
data class BlockEventRow(
    val id: Long,
    val packageName: String,
    val displayName: String,
    val startedAtEpochMillis: Long,
    val reason: BlockReason,
    val releasedAtEpochMillis: Long?,
)

/**
 * Everything the settings & diagnostics screen renders.
 *
 * [engine] comes straight from [dev.brainrotstopper.feature.blocking.BlockingRuntime.status] so
 * the screen cannot drift from the live engine. Permission rows are always the full
 * [PermissionKind] set, so a revoked permission is visible instead of being silently absent.
 */
data class DiagnosticsReport(
    val permissions: List<PermissionStatusRow>,
    val engine: BlockingEngineStatus,
    val usageToday: List<UsageRow>,
    val recentBlocks: List<BlockEventRow>,
) {

    /** Permission rows whose denial disables a feature. Optional grants are not included. */
    val attentionNeeded: List<PermissionKind>
        get() = permissions
            .filter { it.status == PermissionStatus.DENIED && isFeaturePermission(it.kind) }
            .map { it.kind }

    /** Snapshot form used to reuse the onboarding degraded-mode summary. */
    val permissionSnapshot: PermissionSnapshot
        get() = PermissionSnapshot(permissions.associate { it.kind to it.status })

    private fun isFeaturePermission(kind: PermissionKind): Boolean =
        kind != PermissionKind.BATTERY_OPTIMIZATION
}

/**
 * Assembles a [DiagnosticsReport] from repository rows. Pure: the Android screen only supplies
 * the data, which keeps naming, ordering, and duration formatting unit-testable.
 */
object DiagnosticsAssembler {

    fun assemble(
        permissions: List<PermissionStatusRow>,
        engine: BlockingEngineStatus,
        usageToday: List<UsageBucket>,
        recentBlocks: List<BlockEvent>,
        rules: List<AppRule>,
    ): DiagnosticsReport {
        val names = rules.associate { it.packageName to it.displayName }
        val limits = rules.mapNotNull { rule ->
            rule.dailyLimitMinutes?.let { rule.packageName to it }
        }.toMap()

        val usageRows = usageToday
            .filter { it.usedMillis > 0L }
            .sortedWith(compareByDescending<UsageBucket> { it.usedMillis }.thenBy { it.packageName })
            .map { bucket ->
                UsageRow(
                    packageName = bucket.packageName,
                    displayName = label(names[bucket.packageName], bucket.packageName),
                    usedMillis = bucket.usedMillis,
                    limitMinutes = limits[bucket.packageName],
                )
            }

        val blockRows = recentBlocks.map { event ->
            BlockEventRow(
                id = event.id,
                packageName = event.packageName,
                displayName = label(names[event.packageName], event.packageName),
                startedAtEpochMillis = event.startedAtEpochMillis,
                reason = event.reason,
                releasedAtEpochMillis = event.releasedAtEpochMillis,
            )
        }

        return DiagnosticsReport(
            permissions = permissions.sortedBy { it.kind.ordinal },
            engine = engine,
            usageToday = usageRows,
            recentBlocks = blockRows,
        )
    }

    /**
     * Compact usage duration. Whole minutes only, matching the granularity timers enforce:
     * 90_000 -> "1m", 3_900_000 -> "1h 5m".
     */
    fun formatDuration(millis: Long): String {
        val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** App name for display, falling back to the package name when no rule names it. */
    fun label(displayName: String?, packageName: String): String =
        displayName?.takeIf { it.isNotBlank() } ?: packageName
}
