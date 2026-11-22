package dev.brainrotstopper.feature.settings

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.UsageBucket
import dev.brainrotstopper.feature.blocking.BlockingEngineStatus
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAssemblerTest {

    @Test
    fun usageRowsUseRuleNamesAndLimitsAndDropZeroUsage() {
        val report = DiagnosticsAssembler.assemble(
            permissions = permissions(),
            engine = engine(),
            usageToday = listOf(
                UsageBucket("com.instagram.android", EPOCH_DAY, 5 * 60_000L),
                UsageBucket("com.zhiliaoapp.musically", EPOCH_DAY, 42 * 60_000L),
                UsageBucket("com.example.idle", EPOCH_DAY, 0L),
            ),
            recentBlocks = emptyList(),
            rules = listOf(
                AppRule("com.instagram.android", "Instagram", dailyLimitMinutes = 30),
                AppRule("com.zhiliaoapp.musically", "TikTok", dailyLimitMinutes = 60),
            ),
        )

        assertEquals(listOf("com.zhiliaoapp.musically", "com.instagram.android"), report.usageToday.map { it.packageName })
        assertEquals("TikTok", report.usageToday[0].displayName)
        assertEquals(60, report.usageToday[0].limitMinutes)
        assertEquals("Instagram", report.usageToday[1].displayName)
        assertEquals(30, report.usageToday[1].limitMinutes)
    }

    @Test
    fun usageWithoutARuleFallsBackToPackageNameAndNoLimit() {
        val report = DiagnosticsAssembler.assemble(
            permissions = permissions(),
            engine = engine(),
            usageToday = listOf(UsageBucket("com.example.unknown", EPOCH_DAY, 60_000L)),
            recentBlocks = emptyList(),
            rules = emptyList(),
        )

        assertEquals("com.example.unknown", report.usageToday.single().displayName)
        assertNull(report.usageToday.single().limitMinutes)
        assertEquals(
            "com.example.unknown",
            DiagnosticsAssembler.label(report.usageToday.single().displayName, "com.example.unknown"),
        )
    }

    @Test
    fun attentionNeededListsOnlyDeniedFeaturePermissions() {
        val report = DiagnosticsAssembler.assemble(
            permissions = listOf(
                PermissionStatusRow(PermissionKind.ACCESSIBILITY, PermissionStatus.GRANTED),
                PermissionStatusRow(PermissionKind.USAGE_ACCESS, PermissionStatus.DENIED),
                PermissionStatusRow(PermissionKind.NOTIFICATION_LISTENER, PermissionStatus.NOT_APPLICABLE),
                PermissionStatusRow(PermissionKind.POST_NOTIFICATIONS, PermissionStatus.GRANTED),
                PermissionStatusRow(PermissionKind.BATTERY_OPTIMIZATION, PermissionStatus.DENIED),
            ),
            engine = engine(),
            usageToday = emptyList(),
            recentBlocks = emptyList(),
            rules = emptyList(),
        )

        assertEquals(listOf(PermissionKind.USAGE_ACCESS), report.attentionNeeded)
        assertTrue(report.permissionSnapshot.isDegraded)
    }

    @Test
    fun permissionsAreOrderedByKindEvenWhenInputIsNot() {
        val report = DiagnosticsAssembler.assemble(
            permissions = listOf(
                PermissionStatusRow(PermissionKind.POST_NOTIFICATIONS, PermissionStatus.GRANTED),
                PermissionStatusRow(PermissionKind.ACCESSIBILITY, PermissionStatus.GRANTED),
                PermissionStatusRow(PermissionKind.USAGE_ACCESS, PermissionStatus.GRANTED),
            ),
            engine = engine(),
            usageToday = emptyList(),
            recentBlocks = emptyList(),
            rules = emptyList(),
        )

        assertEquals(
            listOf(
                PermissionKind.ACCESSIBILITY,
                PermissionKind.USAGE_ACCESS,
                PermissionKind.POST_NOTIFICATIONS,
            ),
            report.permissions.map { it.kind },
        )
    }

    @Test
    fun blockEventsKeepReasonAndReleaseState() {
        val report = DiagnosticsAssembler.assemble(
            permissions = permissions(),
            engine = engine(),
            usageToday = emptyList(),
            recentBlocks = listOf(
                BlockEvent(
                    id = 7,
                    packageName = "com.instagram.android",
                    startedAtEpochMillis = 1_000L,
                    reason = BlockReason.DAILY_LIMIT,
                    releasedAtEpochMillis = 2_000L,
                ),
                BlockEvent(
                    id = 8,
                    packageName = "com.example.other",
                    startedAtEpochMillis = 3_000L,
                    reason = BlockReason.SCHEDULE,
                ),
            ),
            rules = listOf(AppRule("com.instagram.android", "Instagram")),
        )

        assertEquals(listOf(7L, 8L), report.recentBlocks.map { it.id })
        assertEquals("Instagram", report.recentBlocks[0].displayName)
        assertEquals(BlockReason.DAILY_LIMIT, report.recentBlocks[0].reason)
        assertEquals(2_000L, report.recentBlocks[0].releasedAtEpochMillis)
        assertEquals("com.example.other", report.recentBlocks[1].displayName)
        assertNull(report.recentBlocks[1].releasedAtEpochMillis)
    }

    @Test
    fun formatDurationUsesWholeMinutes() {
        assertEquals("0m", DiagnosticsAssembler.formatDuration(0L))
        assertEquals("0m", DiagnosticsAssembler.formatDuration(59_999L))
        assertEquals("1m", DiagnosticsAssembler.formatDuration(60_000L))
        assertEquals("1h 0m", DiagnosticsAssembler.formatDuration(3_600_000L))
        assertEquals("1h 5m", DiagnosticsAssembler.formatDuration(3_900_000L))
        assertEquals("0m", DiagnosticsAssembler.formatDuration(-1L))
    }

    private fun permissions(): List<PermissionStatusRow> = PermissionKind.entries.map { kind ->
        PermissionStatusRow(kind, PermissionStatus.GRANTED)
    }

    private fun engine(): BlockingEngineStatus = BlockingEngineStatus(
        engineAttached = true,
        stateLoaded = true,
        foregroundServiceRunning = true,
    )

    private companion object {
        const val EPOCH_DAY = 20_000L
    }
}
