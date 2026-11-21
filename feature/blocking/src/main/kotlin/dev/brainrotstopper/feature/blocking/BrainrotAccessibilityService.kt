package dev.brainrotstopper.feature.blocking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.RoomUnlockGrantRepository
import dev.brainrotstopper.core.data.RoomUsageRepository
import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.UnlockGrant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app blocking engine.
 *
 * On every window change it reads the cached [Decision] for the foreground package (no disk
 * I/O on the hot path), shows the full-screen [BlockOverlayController] when blocked, hides it
 * when allowed, and asks [BlockingStateCache] to refresh in the background so a rule change
 * is picked up without restarting the service.
 *
 * Key design points from `docs/design.md` §2:
 * - the overlay is `TYPE_ACCESSIBILITY_OVERLAY`, so it needs no `SYSTEM_ALERT_WINDOW`;
 * - the back key is consumed while the overlay is showing;
 * - the overlay is re-asserted on every window change (an OEM or a navigation away cannot
 *   silently remove it permanently);
 * - only the unlock path removes it, by writing a time-boxed [UnlockGrant] and refreshing.
 */
class BrainrotAccessibilityService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private lateinit var cache: BlockingStateCache
    private lateinit var unlockGrants: UnlockGrantRepository
    private var overlay: BlockOverlayController? = null

    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var blockedPackage: String? = null

    private var lastRefreshAtMillis = 0L

    /**
     * Password gate for the unlock button. M4 uses the placeholder; M7 replaces this with
     * the real password check without changing the surrounding grant/dismiss flow.
     */
    internal var unlockGate: UnlockGate = PlaceholderUnlockGate()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val database = BrainrotDatabase.create(applicationContext)
        unlockGrants = RoomUnlockGrantRepository(database)
        cache = BlockingStateCache(
            ruleRepository = RoomRuleRepository(database),
            unlockGrantRepository = unlockGrants,
            usageRepository = RoomUsageRepository(database),
        )
        overlay = BlockOverlayController(this)

        BlockingRuntime.attach(cache)
        BlockingRuntime.setUnlockHandler(::requestUnlock)
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
        val foregroundPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        // Our own overlay and the block screen's own windows must never trigger a decision.
        if (foregroundPackage == packageName) return

        if (foregroundPackage in IGNORED_PACKAGES) {
            dismissOverlay()
            return
        }

        lastForegroundPackage = foregroundPackage
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
        scope.cancel()
        dismissOverlay()
        overlay = null
        BlockingRuntime.detach()
        super.onDestroy()
    }

    /**
     * Runs the unlock flow for [packageName] through [unlockGate]. Returns true when the
     * request was accepted (the grant write and dismissal happen on the main scope).
     */
    internal fun requestUnlock(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val request = UnlockRequest(
            packageName = packageName,
            reason = cache.decisionFor(packageName).reason,
            nowEpochMillis = now,
        )
        if (!unlockGate.authorize(request)) return false

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    unlockGrants.save(
                        UnlockGrant(
                            id = "session-${UUID.randomUUID()}",
                            packageName = packageName,
                            expiresAtEpochMillis = now + SESSION_UNLOCK_MILLIS,
                            reason = "Session unlock from block screen",
                        ),
                    )
                    cache.refresh()
                }
                val controller = overlay
                if (controller != null && controller.isShowing && blockedPackage == packageName) {
                    controller.hide()
                    blockedPackage = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not apply unlock grant for $packageName", t)
            }
        }
        return true
    }

    private fun applyDecision(packageName: String, receivedAtMillis: Long) {
        val decision = cache.decisionFor(packageName)
        val controller = overlay ?: return

        if (decision.block) {
            if (blockedPackage == packageName && controller.isShowing) return
            blockedPackage = packageName
            controller.show(
                reason = decision.reason ?: BlockReason.MANUAL,
                releaseAtEpochMillis = decision.releaseAtEpochMillis,
                onUnlock = { requestUnlock(packageName) },
            )
            Log.d(
                TAG,
                "Block overlay shown for $packageName in " +
                    "${SystemClock.elapsedRealtime() - receivedAtMillis} ms",
            )
        } else if (controller.isShowing) {
            controller.hide()
            blockedPackage = null
        }
    }

    private fun dismissOverlay() {
        overlay?.hide()
        blockedPackage = null
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
                withContext(Dispatchers.IO) { cache.refresh() }
            } catch (t: Throwable) {
                Log.w(TAG, "Blocking state refresh failed", t)
            } finally {
                refreshInFlight.set(false)
            }
            lastForegroundPackage?.let { applyDecision(it, SystemClock.elapsedRealtime()) }
        }
    }

    companion object {
        /** Session bypass length written by the M4 placeholder unlock. M7 keeps the same shape. */
        const val SESSION_UNLOCK_MILLIS: Long = BlockOverlayController.UNLOCK_MINUTES * 60_000L

        private const val TAG = "BrainrotBlocking"
        private const val REFRESH_MIN_INTERVAL_MILLIS = 500L
        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotAccessibilityService::class.java)
    }
}
