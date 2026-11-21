package dev.brainrotstopper.feature.blocking

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.brainrotstopper.core.model.BlockReason
import kotlin.math.ceil

/**
 * Full-screen block screen drawn by the AccessibilityService with
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * No `SYSTEM_ALERT_WINDOW` permission is needed, and nothing can dismiss the window without
 * going through our unlock path or removing the accessibility service itself. The window is
 * focusable (no `FLAG_NOT_FOCUSABLE`) and opaque, so touch and key input are consumed.
 */
class BlockOverlayController(
    private val service: AccessibilityService,
) {

    private val windowManager: WindowManager =
        service.getSystemService(WindowManager::class.java)

    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var titleView: TextView? = null
    private var reasonView: TextView? = null
    private var countdownView: TextView? = null
    private var releaseAtEpochMillis: Long? = null

    val isShowing: Boolean
        get() = root != null

    /**
     * Shows the overlay, or updates the text when it is already attached. This makes
     * re-assertion on every window change idempotent.
     */
    fun show(
        reason: BlockReason,
        releaseAtEpochMillis: Long?,
        onUnlock: () -> Unit,
    ) {
        this.releaseAtEpochMillis = releaseAtEpochMillis
        val existing = root
        if (existing != null) {
            titleView?.text = service.getString(R.string.block_overlay_title)
            reasonView?.text = reasonText(reason)
            refreshCountdown()
            return
        }

        val view = buildView(onUnlock)
        titleView = view.findViewWithTag<TextView>(TAG_TITLE)
        reasonView = view.findViewWithTag<TextView>(TAG_REASON)
        countdownView = view.findViewWithTag<TextView>(TAG_COUNTDOWN)

        titleView?.text = service.getString(R.string.block_overlay_title)
        reasonView?.text = reasonText(reason)
        refreshCountdown()

        try {
            windowManager.addView(view, layoutParams())
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach the block overlay", t)
            root = null
            return
        }
        root = view
    }

    fun hide() {
        val view = root ?: return
        root = null
        titleView = null
        reasonView = null
        countdownView = null
        releaseAtEpochMillis = null
        handler.removeCallbacksAndMessages(null)
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "Block overlay was already detached", t)
        }
    }

    private fun buildView(onUnlock: () -> Unit): View {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BLOCK_BACKGROUND)
            setPadding(dp(32), dp(48), dp(32), dp(48))
            isClickable = true
            isFocusable = true
            contentDescription = service.getString(R.string.block_overlay_content_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val title = TextView(service).apply {
            tag = TAG_TITLE
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val reason = TextView(service).apply {
            tag = TAG_REASON
            textSize = 18f
            setTextColor(REASON_COLOR)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        val countdown = TextView(service).apply {
            tag = TAG_COUNTDOWN
            textSize = 16f
            setTextColor(COUNTDOWN_COLOR)
            gravity = Gravity.CENTER
        }
        val unlock = Button(service).apply {
            text = service.getString(R.string.block_overlay_unlock, UNLOCK_MINUTES)
            setOnClickListener { onUnlock() }
        }
        val backNotice = TextView(service).apply {
            text = service.getString(R.string.block_overlay_back_disabled)
            textSize = 14f
            setTextColor(REASON_COLOR)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }

        container.addView(title, matchWidth())
        container.addView(reason, matchWidth())
        container.addView(countdown, matchWidth())
        container.addView(unlock, wrapContent())
        container.addView(backNotice, matchWidth())
        return container
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            title = WINDOW_TITLE
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }

    private fun refreshCountdown() {
        val releaseAt = releaseAtEpochMillis
        if (releaseAt == null) {
            countdownView?.visibility = View.GONE
            handler.removeCallbacksAndMessages(null)
            return
        }
        countdownView?.visibility = View.VISIBLE
        updateCountdown(releaseAt)
        scheduleNextTick(releaseAt)
    }

    private fun scheduleNextTick(releaseAt: Long) {
        handler.removeCallbacksAndMessages(null)
        if (releaseAt <= System.currentTimeMillis()) return
        handler.postDelayed({ updateCountdown(releaseAt) }, COUNTDOWN_INTERVAL_MILLIS)
    }

    private fun updateCountdown(releaseAt: Long) {
        val remainingMillis = releaseAt - System.currentTimeMillis()
        val text = if (remainingMillis <= 0) {
            service.getString(R.string.block_overlay_release_now)
        } else {
            val minutes = ceil(remainingMillis / 60_000.0).toInt()
            service.getString(R.string.block_overlay_release_in, minutes)
        }
        countdownView?.text = text
        if (remainingMillis > 0 && root != null) scheduleNextTick(releaseAt)
    }

    private fun reasonText(reason: BlockReason): String = when (reason) {
        BlockReason.SCHEDULE -> service.getString(R.string.block_overlay_reason_schedule)
        BlockReason.DAILY_LIMIT -> service.getString(R.string.block_overlay_reason_daily_limit)
        BlockReason.MANUAL -> service.getString(R.string.block_overlay_reason_manual)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun wrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = (24 * service.resources.displayMetrics.density).toInt()
        }

    companion object {
        /** Visible in `dumpsys window windows`; QA asserts the overlay by this title. */
        const val WINDOW_TITLE = "BrainrotBlockerOverlay"
        const val UNLOCK_MINUTES = 5

        private const val TAG = "BrainrotBlocking"
        private const val TAG_TITLE = "block_overlay_title"
        private const val TAG_REASON = "block_overlay_reason"
        private const val TAG_COUNTDOWN = "block_overlay_countdown"
        private const val COUNTDOWN_INTERVAL_MILLIS = 1_000L
        private val BLOCK_BACKGROUND = Color.parseColor("#FF101014")
        private val REASON_COLOR = Color.parseColor("#FFBFC4CC")
        private val COUNTDOWN_COLOR = Color.parseColor("#FF8AB4F8")
    }
}
