package dev.brainrotstopper.feature.blocking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.security.PasswordManagerProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app blocking engine driver.
 *
 * On every window change it reads the cached [Decision] for the foreground package (no disk
 * I/O on the hot path), shows the full-screen [BlockOverlayController] when blocked, hides it
 * when allowed, and asks the shared [BlockingEngine] to refresh in the background so a rule
 * change is picked up without restarting the service.
 *
 * The cache is owned by [BlockingEngineProvider], not by this class: the foreground service
 * installs the same engine so blocking, the self-check, and the unlock grant survive Activity
 * death and are shared with the overlay path. See `docs/foreground-service.md`.
 *
 * Key design points from `docs/design.md` §2:
 * - the overlay is `TYPE_ACCESSIBILITY_OVERLAY`, so it needs no `SYSTEM_ALERT_WINDOW`;
 * - the back key is consumed while the overlay is showing;
 * - the overlay is re-asserted on every window change (an OEM or a navigation away cannot
 *   silently remove it permanently);
 * - only the unlock path removes it, by writing a time-boxed [dev.brainrotstopper.core.model.UnlockGrant]
 *   and refreshing.
 */
class BrainrotAccessibilityService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private lateinit var engine: BlockingEngine
    private var overlay: BlockOverlayController? = null
    private var removeUnlockListener: (() -> Unit)? = null
    private var removeShortFormHandler: (() -> Unit)? = null

    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var blockedPackage: String? = null

    /**
     * True while the current overlay came from a short-form confirmation rather than a rule
     * decision. Such a block has no schedule/daily-limit release time, so it must not be
     * dismissed by the next "allowed" verdict for the same app; only a password unlock or
     * leaving the app ends it.
     */
    @Volatile
    private var blockedByShortForm: Boolean = false

    private var lastRefreshAtMillis = 0L

    /** Scheduled refresh at the current grant expiry so blocking re-arms without a window event. */
    private var rearmJob: Job? = null
    private var rearmExpiry: Long? = null

    /**
     * Password gate for the unlock button. Installed into [BlockingRuntime] so the shared
     * engine's unlock flow uses the same credential the settings screens verify.
     */
    private val unlockGate: UnlockGate = PasswordUnlockGate {
        PasswordManagerProvider.manager(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        engine = BlockingEngineProvider.get(applicationContext)
        BlockingRuntime.onAccessibilityServiceStarted()
        overlay = BlockOverlayController(this)
        BlockingRuntime.installUnlockGate(unlockGate)
        removeUnlockListener = BlockingRuntime.addUnlockListener { packageName ->
            scope.launch { dismissAfterUnlock(packageName) }
        }
        removeShortFormHandler = BlockingRuntime.addShortFormBlockHandler { packageName ->
            scope.launch { showShortFormBlock(packageName) }
        }

        // Keep the blocking engine alive independently of this service when Android allows it.
        BlockingForegroundService.start(
            applicationContext,
            BlockingServiceStartReason.ACCESSIBILITY,
        )

        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { engine.refresh() }
                    .onFailure { Log.w(TAG, "Initial blocking refresh failed", it) }
            }
        }
        Log.i(TAG, "Blocking engine attached to the accessibility service")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The XML config already asks for this; re-assert it so a stale serviceInfo cannot
        // silently drop key filtering on a re-enable.
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        requestRefresh(force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val receivedAtMillis = SystemClock.elapsedRealtime()
        val eventPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        // Our own overlay and the block screen's own windows must never trigger a decision.
        if (eventPackage == packageName) return

        // A soft keyboard (or another non-application window) can take focus while the user is
        // still in the blocked app. Resolve the real foreground application so typing a password
        // does not look like leaving the app and tear the overlay down.
        val foregroundPackage = foregroundApplicationPackage(eventPackage)
        if (foregroundPackage == packageName) return

        if (foregroundPackage in IGNORED_PACKAGES) {
            BlockingRuntime.onForegroundPackageChanged(null)
            dismissOverlay()
            return
        }

        lastForegroundPackage = foregroundPackage
        BlockingRuntime.onForegroundPackageChanged(foregroundPackage)
        applyDecision(foregroundPackage, receivedAtMillis)
        // A window state change is the moment a rule change must take effect; other window
        // events are throttled.
        requestRefresh(
            force = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Consume both the down and up events so the blocked app never sees the back press.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && overlay?.isShowing == true) {
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        dismissOverlay()
    }

    override fun onDestroy() {
        removeUnlockListener?.invoke()
        removeUnlockListener = null
        removeShortFormHandler?.invoke()
        removeShortFormHandler = null
        rearmJob?.cancel()
        rearmJob = null
        scope.cancel()
        dismissOverlay()
        overlay = null
        BlockingRuntime.onAccessibilityServiceStopped()
        super.onDestroy()
    }

    /**
     * Runs the unlock flow for [packageName] through the password gate and suspends until it
     * completes. Success is reported only after the grant is persisted and visible in the
     * shared engine; the overlay is dismissed through the runtime unlock listener.
     */
    internal suspend fun requestUnlock(packageName: String, password: CharArray?): UnlockResult =
        engine.unlock(packageName, password)

    private suspend fun dismissAfterUnlock(packageName: String) {
        withContext(Dispatchers.Main.immediate) {
            val controller = overlay
            if (controller != null && controller.isShowing && blockedPackage == packageName) {
                engine.recordBlockReleased(packageName)
                controller.hide()
                blockedPackage = null
                blockedByShortForm = false
            }
            // Any completed unlock, whichever caller started it, schedules the re-arm check.
            scheduleRearmFor(packageName)
        }
    }

    private fun applyDecision(packageName: String, receivedAtMillis: Long) {
        val decision = engine.decisionFor(packageName)
        val controller = overlay ?: return

        // A confirmed short-form block has no rule decision behind it, so an "allowed" verdict
        // for the same app must not tear it down. Leaving the app still dismisses it below.
        if (blockedByShortForm && blockedPackage == packageName) return

        if (decision.block) {
            if (blockedPackage == packageName && controller.isShowing) return
            // A different package replaced the overlay: close the previous local event first.
            blockedPackage?.takeIf { it != packageName }?.let { previous ->
                engine.recordBlockReleased(previous)
            }
            blockedPackage = packageName
            blockedByShortForm = false
            val reason = decision.reason ?: BlockReason.MANUAL
            controller.show(
                reason = reason,
                releaseAtEpochMillis = decision.releaseAtEpochMillis,
                onSubmitPassword = { password ->
                    scope.launch {
                        when (val result = requestUnlock(packageName, password)) {
                            UnlockResult.Success -> Unit
                            UnlockResult.Rejected -> {
                                Log.d(TAG, "Unlock rejected by the password gate for $packageName")
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_wrong),
                                )
                            }
                            UnlockResult.NotAttached -> {
                                Log.w(TAG, "Unlock requested but no engine is attached")
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_unavailable),
                                )
                            }
                            is UnlockResult.Failed -> {
                                Log.e(TAG, "Unlock failed for $packageName", result.cause)
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_unavailable),
                                )
                            }
                        }
                    }
                },
            )
            // Only a block that actually attached is a real block; a failed addView is not.
            if (controller.isShowing) {
                engine.recordBlockStarted(packageName, reason)
            }
            Log.d(
                TAG,
                "Block overlay shown for $packageName in " +
                    "${SystemClock.elapsedRealtime() - receivedAtMillis} ms",
            )
        } else if (controller.isShowing) {
            blockedPackage?.let { previous -> engine.recordBlockReleased(previous) }
            controller.hide()
            blockedPackage = null
            blockedByShortForm = false
            rearmJob?.cancel()
            rearmJob = null
            rearmExpiry = null
        }
    }

    /**
     * The real foreground application package for a window event.
     *
     * [eventPackage] is used directly when it owns an application window, so a fast app switch is
     * not delayed by a stale window list. Otherwise (for example the soft keyboard taking focus)
     * the active application window is resolved instead of treating the keyboard as the app.
     */
    private fun foregroundApplicationPackage(eventPackage: String): String {
        val current = runCatching { windows }.getOrNull() ?: return eventPackage
        val snapshots = current.map { window ->
            ForegroundWindowSnapshot(
                type = window.type,
                isActive = window.isActive,
                isFocused = window.isFocused,
                packageName = runCatching { window.root?.packageName?.toString() }.getOrNull(),
            )
        }
        if (snapshots.any {
                it.type == ForegroundWindowResolver.TYPE_APPLICATION && it.packageName == eventPackage
            }
        ) {
            return eventPackage
        }
        return ForegroundWindowResolver.resolve(snapshots) ?: eventPackage
    }

    private fun dismissOverlay() {
        blockedPackage?.let { previous -> engine.recordBlockReleased(previous) }
        overlay?.hide()
        blockedPackage = null
        blockedByShortForm = false
    }

    /**
     * Draws the short-form overlay for a confirmation that arrived from the media listener.
     *
     * Re-checks the foreground gate and the unlock grant here, off the listener's thread, so a
     * stale callback or an active grant cannot produce a block. The block is recorded like any
     * other so diagnostics show a `SHORT_FORM` row.
     */
    private suspend fun showShortFormBlock(packageName: String) {
        withContext(Dispatchers.Main.immediate) {
            val controller = overlay ?: return@withContext
            if (!BlockingRuntime.isForeground(packageName)) return@withContext
            if (BlockingRuntime.isUnlocked(packageName)) return@withContext
            if (blockedPackage == packageName && controller.isShowing) return@withContext

            blockedPackage?.takeIf { it != packageName }?.let { previous ->
                engine.recordBlockReleased(previous)
            }
            blockedPackage = packageName
            blockedByShortForm = true
            controller.show(
                reason = BlockReason.SHORT_FORM,
                releaseAtEpochMillis = null,
                onSubmitPassword = { password ->
                    scope.launch {
                        when (val result = requestUnlock(packageName, password)) {
                            UnlockResult.Success -> Unit
                            UnlockResult.Rejected -> {
                                Log.d(TAG, "Unlock rejected by the password gate for $packageName")
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_wrong),
                                )
                            }
                            UnlockResult.NotAttached -> {
                                Log.w(TAG, "Unlock requested but no engine is attached")
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_unavailable),
                                )
                            }
                            is UnlockResult.Failed -> {
                                Log.e(TAG, "Unlock failed for $packageName", result.cause)
                                controller.showPasswordError(
                                    getString(R.string.block_overlay_password_unavailable),
                                )
                            }
                        }
                    }
                },
            )
            if (controller.isShowing) {
                engine.recordBlockStarted(packageName, BlockReason.SHORT_FORM)
            }
        }
    }

    /**
     * Schedules one refresh at the active grant's expiry. When it fires the snapshot no longer
     * contains the expired grant, so [applyDecision] re-shows the overlay even if the user never
     * left the app. This is the "auto re-arm" half of the time-boxed emergency unlock.
     */
    private fun scheduleRearmFor(packageName: String) {
        val expiry = BlockingRuntime.activeGrantExpiry(packageName)
        if (expiry == null) {
            rearmJob?.cancel()
            rearmJob = null
            rearmExpiry = null
            return
        }
        if (expiry == rearmExpiry && rearmJob?.isActive == true) return
        rearmJob?.cancel()
        rearmExpiry = expiry
        rearmJob = scope.launch {
            val remaining = expiry - System.currentTimeMillis()
            delay(remaining.coerceAtLeast(0L) + REARM_GRACE_MILLIS)
            // A periodic refresh may already be in flight. Let it finish, then refresh again
            // so the re-arm decision is made from a snapshot loaded after the expiry.
            var waited = 0L
            while (refreshInFlight.get() && waited < REARM_MAX_WAIT_MILLIS) {
                delay(100L)
                waited += 100L
            }
            rearmExpiry = null
            requestRefresh(force = true)
        }
    }

    /**
     * Reloads rules/schedules/grants off the main thread, then re-evaluates the last
     * foreground package so a rule change takes effect without another window event.
     * Throttled because `TYPE_WINDOWS_CHANGED` can be chatty.
     */
    private fun requestRefresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAtMillis < REFRESH_MIN_INTERVAL_MILLIS) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        lastRefreshAtMillis = now

        scope.launch {
            try {
                withContext(Dispatchers.IO) { engine.refresh() }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Blocking state refresh failed", t)
            } finally {
                refreshInFlight.set(false)
            }
            lastForegroundPackage?.let { packageName ->
                scheduleRearmFor(packageName)
                applyDecision(packageName, SystemClock.elapsedRealtime())
            }
        }
    }

    companion object {
        private const val TAG = "BrainrotBlocking"
        private const val REFRESH_MIN_INTERVAL_MILLIS = 500L

        /** Small cushion so the re-arm refresh observes an already-expired grant. */
        private const val REARM_GRACE_MILLIS = 250L
        private const val REARM_MAX_WAIT_MILLIS = 5_000L
        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotAccessibilityService::class.java)
    }
}
