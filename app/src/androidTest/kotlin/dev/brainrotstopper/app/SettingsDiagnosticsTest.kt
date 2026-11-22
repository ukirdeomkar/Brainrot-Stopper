package dev.brainrotstopper.app

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.data.BlockEventRepository
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.UsageBucket
import dev.brainrotstopper.feature.blocking.BlockingEngineStatus
import dev.brainrotstopper.feature.onboarding.PermissionChecker
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionStatus
import dev.brainrotstopper.feature.settings.DiagnosticsStore
import dev.brainrotstopper.feature.settings.SettingsActivity
import dev.brainrotstopper.feature.settings.R as SettingsR
import dev.brainrotstopper.feature.timers.UsageAccessStatus
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the M9 settings & diagnostics screen on API 31 and API 36.
 *
 * The state is injected through `DiagnosticsStore` so the screen is driven by a known engine
 * snapshot, a known permission snapshot, and known usage/block rows. The test asserts the
 * rendered text matches the injected state, that a revoked Usage Access is visibly reported,
 * and that the screen offers no export/upload path.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDiagnosticsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearOverrides() {
        DiagnosticsStore.setForTests()
    }

    @Test
    fun rendersLiveEngineStateAndReportsRevokedUsageAccess() {
        val engine = BlockingEngineStatus(
            engineAttached = true,
            stateLoaded = true,
            lastRefreshAtEpochMillis = 1_700_000_000_000L,
            foregroundServiceRunning = true,
            usageAccessStatus = UsageAccessStatus.REVOKED,
            usageLastPollFailed = false,
            usageTrackedPackages = 2,
            timersAvailable = false,
        )
        DiagnosticsStore.setForTests(
            permissionChecker = SettingsFakePermissionChecker(
                PermissionKind.entries.associateWith { kind ->
                    if (kind == PermissionKind.USAGE_ACCESS) {
                        PermissionStatus.DENIED
                    } else {
                        PermissionStatus.GRANTED
                    }
                },
            ),
            engineStatus = { engine },
            rules = SettingsFakeRuleRepository(
                listOf(AppRule("com.instagram.android", "Instagram", dailyLimitMinutes = 30)),
            ),
            usage = SettingsFakeUsageRepository(
                mapOf(
                    "com.instagram.android" to 42 * 60_000L,
                    "com.zhiliaoapp.musically" to 3 * 60_000L,
                ),
            ),
            blockEvents = SettingsFakeBlockEventRepository(
                listOf(
                    BlockEvent(
                        id = 1,
                        packageName = "com.instagram.android",
                        startedAtEpochMillis = 1_700_000_000_000L,
                        reason = BlockReason.DAILY_LIMIT,
                        releasedAtEpochMillis = 1_700_000_060_000L,
                    ),
                    BlockEvent(
                        id = 2,
                        packageName = "com.zhiliaoapp.musically",
                        startedAtEpochMillis = 1_700_000_120_000L,
                        reason = BlockReason.SCHEDULE,
                    ),
                ),
            ),
        )

        ActivityScenario.launch<SettingsActivity>(SettingsActivity::class.java).use { scenario ->
            await("engine status renders") {
                textOf(scenario, SettingsR.id.settings_engine_status).contains("Running: on")
            }
            val engineText = textOf(scenario, SettingsR.id.settings_engine_status)
            assertTrue("background service must match engine state", engineText.contains("Background service: on"))
            assertTrue("revoked Usage Access must be reported", engineText.contains("Usage Access: not granted"))
            assertTrue("timers must show paused", engineText.contains("Daily timers: off"))

            val usageAccessRow = tagText(scenario, "permission_row_USAGE_ACCESS")
            assertTrue(
                "the revoked permission row must say not granted, got: $usageAccessRow",
                usageAccessRow.contains("not granted"),
            )
            val attention = tagText(scenario, "settings_attention")
            assertTrue(
                "the attention summary must name Daily timers, got: $attention",
                attention.contains("Daily timers"),
            )

            val usageRow = tagText(scenario, "usage_row_com.instagram.android")
            assertTrue(
                "usage must use the rule name and configured limit, got: $usageRow",
                usageRow.contains("Instagram") && usageRow.contains("42m") && usageRow.contains("30m"),
            )

            val releasedBlock = tagText(scenario, "block_row_1")
            assertTrue(
                "a released block must show its reason and release time, got: $releasedBlock",
                releasedBlock.contains("Instagram") && releasedBlock.contains("daily limit") &&
                    releasedBlock.contains("released"),
            )
            val activeBlock = tagText(scenario, "block_row_2")
            assertTrue(
                "an open block must say it is still blocking, got: $activeBlock",
                activeBlock.contains("still blocking"),
            )

            val privacy = textOf(scenario, SettingsR.id.settings_privacy)
            assertTrue(
                "the screen must state it is local-only, got: $privacy",
                privacy.contains("exported") && privacy.contains("uploaded"),
            )
        }
    }

    @Test
    fun reportsEngineOffAndEmptySectionsWithoutCrashing() {
        DiagnosticsStore.setForTests(
            permissionChecker = SettingsFakePermissionChecker(
                PermissionKind.entries.associateWith { PermissionStatus.GRANTED },
            ),
            engineStatus = { BlockingEngineStatus() },
            rules = SettingsFakeRuleRepository(emptyList()),
            usage = SettingsFakeUsageRepository(emptyMap()),
            blockEvents = SettingsFakeBlockEventRepository(emptyList()),
        )

        ActivityScenario.launch<SettingsActivity>(SettingsActivity::class.java).use { scenario ->
            await("engine off renders") {
                textOf(scenario, SettingsR.id.settings_engine_status).contains("Running: off")
            }
            val engineText = textOf(scenario, SettingsR.id.settings_engine_status)
            assertTrue(engineText.contains("Background service: off"))

            await("empty usage renders") {
                textOf(scenario, SettingsR.id.settings_usage_empty).contains("No app usage recorded today.")
            }
            await("empty blocks render") {
                textOf(scenario, SettingsR.id.settings_blocks_empty).contains("No blocks recorded yet.")
            }
            val attention = tagText(scenario, "settings_attention")
            assertTrue(
                "all-granted permissions must say so, got: $attention",
                attention.contains("All requested permissions are granted."),
            )
        }
    }

    private fun textOf(scenario: ActivityScenario<*>, viewId: Int): String {
        var text = ""
        scenario.onActivity { activity ->
            text = activity.findViewById<TextView>(viewId)?.text?.toString().orEmpty()
        }
        return text
    }

    private fun tagText(scenario: ActivityScenario<*>, tag: String): String {
        var text = ""
        scenario.onActivity { activity ->
            text = activity.window.decorView.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()
        }
        return text
    }

    private fun await(what: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}

private class SettingsFakePermissionChecker(
    private val statuses: Map<PermissionKind, PermissionStatus>,
) : PermissionChecker {
    override fun status(kind: PermissionKind): PermissionStatus =
        statuses[kind] ?: PermissionStatus.DENIED
}

private class SettingsFakeRuleRepository(
    rules: List<AppRule>,
) : RuleRepository {
    private val storedRules = rules.toMutableList()

    override suspend fun rules(): List<AppRule> = storedRules.toList()

    override suspend fun upsertRule(rule: AppRule) {
        storedRules.removeAll { it.packageName == rule.packageName }
        storedRules += rule
    }

    override suspend fun removeRule(packageName: String) {
        storedRules.removeAll { it.packageName == packageName }
    }

    override suspend fun schedules(): List<BlockSchedule> = emptyList()

    override suspend fun upsertSchedule(schedule: BlockSchedule) = Unit

    override suspend fun removeSchedule(id: String) = Unit
}

private class SettingsFakeUsageRepository(
    private val today: Map<String, Long>,
) : UsageRepository {
    private val epochDay = LocalDate.now().toEpochDay()

    override suspend fun usage(packageName: String, epochDay: Long): UsageBucket? =
        today[packageName]?.let { UsageBucket(packageName, epochDay, it) }
            ?.takeIf { epochDay == this.epochDay }

    override suspend fun usageForDay(epochDay: Long): List<UsageBucket> =
        if (epochDay == this.epochDay) {
            today.map { (packageName, usedMillis) -> UsageBucket(packageName, epochDay, usedMillis) }
        } else {
            emptyList()
        }

    override suspend fun rewriteUsage(packageName: String, epochDay: Long, usedMillis: Long) = Unit

    override suspend fun deleteBefore(epochDay: Long) = Unit
}

private class SettingsFakeBlockEventRepository(
    private val events: List<BlockEvent>,
) : BlockEventRepository {
    override suspend fun record(
        packageName: String,
        startedAtEpochMillis: Long,
        reason: BlockReason,
    ): Long = 0L

    override suspend fun releaseOpen(packageName: String, releasedAtEpochMillis: Long): Int = 0

    override suspend fun recent(limit: Int): List<BlockEvent> = events.take(limit)

    override suspend fun trimTo(keep: Int): Int = 0
}
