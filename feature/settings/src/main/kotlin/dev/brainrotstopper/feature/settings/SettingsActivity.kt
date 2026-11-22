package dev.brainrotstopper.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.feature.blocking.BlockingEngineStatus
import dev.brainrotstopper.feature.onboarding.OnboardingActivity
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionStatus
import dev.brainrotstopper.feature.timers.UsageAccessStatus
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Settings & diagnostics: the single surface that answers "is this app working right now?".
 *
 * It reports the live blocking engine state from `BlockingRuntime`, every permission with its
 * grant state (a revoked permission stays visible instead of disappearing), today's per-app
 * usage from Room, and the newest local block events. Everything is read-only and local: the
 * screen never touches the password hash, never logs a value, and has no export or upload path.
 *
 * Like the other screens in this app it is a plain `android.app.Activity`; reads run on a
 * background thread and re-render on the main thread, and every resume re-reads live state so a
 * change made in Android Settings is reflected when the user returns.
 */
class SettingsActivity : Activity() {

    private val loadToken = AtomicInteger(0)

    private lateinit var engineView: TextView
    private lateinit var attentionView: TextView
    private lateinit var permissionList: LinearLayout
    private lateinit var usageEmptyView: TextView
    private lateinit var usageList: LinearLayout
    private lateinit var blocksEmptyView: TextView
    private lateinit var blockList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        engineView = findViewById(R.id.settings_engine_status)
        attentionView = findViewById(R.id.settings_attention)
        permissionList = findViewById(R.id.settings_permission_list)
        usageEmptyView = findViewById(R.id.settings_usage_empty)
        usageList = findViewById(R.id.settings_usage_list)
        blocksEmptyView = findViewById(R.id.settings_blocks_empty)
        blockList = findViewById(R.id.settings_block_list)
        attentionView.tag = ATTENTION_TAG

        findViewById<Button>(R.id.settings_refresh).setOnClickListener { load() }
        findViewById<Button>(R.id.settings_review_permissions).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val generation = loadToken.incrementAndGet()
        val context: Context = applicationContext
        Thread({
            val loaded = runCatching { runBlocking { readDiagnostics(context) } }
            runOnUiThread {
                if (generation != loadToken.get() || isFinishing) return@runOnUiThread
                loaded.fold(::render, ::renderLoadFailure)
            }
        }, "settings-diagnostics-load").apply { isDaemon = true }.start()
    }

    private suspend fun readDiagnostics(context: Context): LoadedDiagnostics {
        val checker = DiagnosticsStore.permissionChecker(context)
        val permissions = PermissionKind.entries.map { kind ->
            val status = runCatching { checker.status(kind) }
                .getOrDefault(PermissionStatus.DENIED)
            PermissionStatusRow(kind, status)
        }
        val engine = DiagnosticsStore.engineStatus()
        val epochDay = LocalDate.now().toEpochDay()
        val usage = runCatching { DiagnosticsStore.usageRepository(context).usageForDay(epochDay) }
        val blocks = runCatching {
            DiagnosticsStore.blockEventRepository(context).recent(RECENT_BLOCK_LIMIT)
        }
        val rules = runCatching { DiagnosticsStore.ruleRepository(context).rules() }
            .getOrDefault(emptyList())

        return LoadedDiagnostics(
            report = DiagnosticsAssembler.assemble(
                permissions = permissions,
                engine = engine,
                usageToday = usage.getOrDefault(emptyList()),
                recentBlocks = blocks.getOrDefault(emptyList()),
                rules = rules,
            ),
            usageFailed = usage.isFailure,
            blocksFailed = blocks.isFailure,
        )
    }

    private fun render(loaded: LoadedDiagnostics) {
        renderEngine(loaded.report.engine)
        renderPermissions(loaded.report)
        renderUsage(loaded.report.usageToday, loaded.usageFailed)
        renderBlocks(loaded.report.recentBlocks, loaded.blocksFailed)
    }

    private fun renderEngine(engine: BlockingEngineStatus) {
        engineView.text = getString(
            R.string.settings_engine_status,
            onOff(engine.isEngineRunning),
            onOff(engine.foregroundServiceRunning),
            formatClock(engine.lastRefreshAtEpochMillis),
            usageAccessText(engine.usageAccessStatus, engine.usageLastPollFailed),
            onOff(engine.timersAvailable),
        )
    }

    private fun renderPermissions(report: DiagnosticsReport) {
        permissionList.removeAllViews()
        report.permissions.forEach { row ->
            val view = TextView(this).apply {
                text = getString(
                    R.string.settings_permission_row,
                    getString(featureLabel(row.kind)),
                    statusLabel(row.status),
                )
                textSize = 14f
                setPadding(0, dp(4), 0, dp(4))
                tag = permissionRowTag(row.kind)
                if (row.status == PermissionStatus.DENIED) {
                    setTextColor(COLOR_ATTENTION)
                }
            }
            permissionList.addView(view)
        }

        val attention = report.attentionNeeded
        attentionView.visibility = View.VISIBLE
        attentionView.text = if (attention.isEmpty()) {
            getString(R.string.settings_attention_none)
        } else {
            getString(
                R.string.settings_attention_some,
                attention.joinToString(", ") { getString(featureLabel(it)) },
            )
        }
        attentionView.setTextColor(
            if (attention.isEmpty()) COLOR_OK else COLOR_ATTENTION,
        )
    }

    private fun renderUsage(rows: List<UsageRow>, failed: Boolean) {
        usageList.removeAllViews()
        when {
            failed -> showEmpty(usageEmptyView, getString(R.string.settings_usage_unavailable))
            rows.isEmpty() -> showEmpty(usageEmptyView, getString(R.string.settings_usage_empty))
            else -> {
                usageEmptyView.visibility = View.GONE
                rows.forEach { row ->
                    val label = row.displayName
                    val used = DiagnosticsAssembler.formatDuration(row.usedMillis)
                    val text = row.limitMinutes?.let { limit ->
                        getString(
                            R.string.settings_usage_row_limited,
                            label,
                            used,
                            DiagnosticsAssembler.formatDuration(limit * 60_000L),
                        )
                    } ?: getString(R.string.settings_usage_row, label, used)
                    usageList.addView(
                        diagnosticRow(text).apply { tag = usageRowTag(row.packageName) },
                    )
                }
            }
        }
    }

    private fun renderBlocks(rows: List<BlockEventRow>, failed: Boolean) {
        blockList.removeAllViews()
        when {
            failed -> showEmpty(blocksEmptyView, getString(R.string.settings_blocks_unavailable))
            rows.isEmpty() -> showEmpty(blocksEmptyView, getString(R.string.settings_blocks_empty))
            else -> {
                blocksEmptyView.visibility = View.GONE
                rows.forEach { row ->
                    val label = row.displayName
                    val base = getString(
                        R.string.settings_block_row,
                        label,
                        reasonLabel(row.reason),
                        formatBlockTime(row.startedAtEpochMillis),
                    )
                    val release = row.releasedAtEpochMillis?.let { released ->
                        getString(R.string.settings_block_released, formatBlockTime(released))
                    } ?: getString(R.string.settings_block_active)
                    blockList.addView(
                        diagnosticRow("$base — $release").apply { tag = blockRowTag(row.id) },
                    )
                }
            }
        }
    }

    private fun renderLoadFailure(cause: Throwable) {
        engineView.text = getString(
            R.string.settings_load_failed,
            cause.message ?: cause.javaClass.simpleName,
        )
        permissionList.removeAllViews()
        usageList.removeAllViews()
        blockList.removeAllViews()
        attentionView.visibility = View.GONE
        showEmpty(usageEmptyView, getString(R.string.settings_usage_unavailable))
        showEmpty(blocksEmptyView, getString(R.string.settings_blocks_unavailable))
    }

    private fun showEmpty(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
    }

    private fun diagnosticRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun statusLabel(status: PermissionStatus): String = when (status) {
        PermissionStatus.GRANTED -> getString(R.string.settings_status_granted)
        PermissionStatus.DENIED -> getString(R.string.settings_status_denied)
        PermissionStatus.NOT_APPLICABLE -> getString(R.string.settings_status_not_applicable)
    }

    private fun featureLabel(kind: PermissionKind): Int = when (kind) {
        PermissionKind.ACCESSIBILITY -> R.string.settings_feature_blocking
        PermissionKind.USAGE_ACCESS -> R.string.settings_feature_usage_access
        PermissionKind.NOTIFICATION_LISTENER -> R.string.settings_feature_notification_listener
        PermissionKind.POST_NOTIFICATIONS -> R.string.settings_feature_post_notifications
        PermissionKind.BATTERY_OPTIMIZATION -> R.string.settings_feature_battery
    }

    private fun reasonLabel(reason: BlockReason): String = when (reason) {
        BlockReason.SCHEDULE -> getString(R.string.settings_reason_schedule)
        BlockReason.DAILY_LIMIT -> getString(R.string.settings_reason_daily_limit)
        BlockReason.MANUAL -> getString(R.string.settings_reason_manual)
    }

    private fun usageAccessText(status: UsageAccessStatus, pollFailed: Boolean): String = when (status) {
        UsageAccessStatus.GRANTED -> getString(
            if (pollFailed) {
                R.string.settings_usage_access_granted_poll_failed
            } else {
                R.string.settings_usage_access_granted
            },
        )
        UsageAccessStatus.REVOKED -> getString(R.string.settings_usage_access_revoked)
        UsageAccessStatus.UNDETERMINED -> getString(R.string.settings_usage_access_undetermined)
    }

    private fun formatClock(epochMillis: Long): String =
        if (epochMillis <= 0L) {
            getString(R.string.settings_never)
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
        }

    private fun formatBlockTime(epochMillis: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private fun onOff(value: Boolean): String =
        getString(if (value) R.string.settings_state_on else R.string.settings_state_off)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LoadedDiagnostics(
        val report: DiagnosticsReport,
        val usageFailed: Boolean,
        val blocksFailed: Boolean,
    )

    private companion object {
        const val RECENT_BLOCK_LIMIT = 20
        const val ATTENTION_TAG = "settings_attention"
        val COLOR_ATTENTION: Int = Color.rgb(180, 0, 0)
        val COLOR_OK: Int = Color.rgb(0, 110, 0)

        fun permissionRowTag(kind: PermissionKind): String = "permission_row_${kind.name}"
        fun usageRowTag(packageName: String): String = "usage_row_$packageName"
        fun blockRowTag(id: Long): String = "block_row_$id"
    }
}
