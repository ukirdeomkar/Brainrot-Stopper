package dev.brainrotstopper.feature.blocking

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps the blocking engine alive outside any Activity.
 *
 * Responsibilities:
 * - own the process lifetime of the [BlockingEngine] and keep its snapshot warm;
 * - show the ongoing notification Android requires for a foreground service;
 * - restart itself on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` through
 *   [BlockingBootReceiver], and re-subscribe to screen state changes;
 * - publish `BlockingRuntime.status()` so the UI can show "blocking engine running".
 *
 * The service uses `specialUse` on API 34+; API 31–33 use the two-argument
 * `startForeground` because the type constant does not exist there. The manifest declares the
 * type for every level, which older platforms ignore. See `docs/foreground-service.md`.
 */
class BlockingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: BlockingEngine? = null

    private var refreshJob: Job? = null
    private var stateReceiverRegistered = false
    private var startReasonRecorded = false
    private var foregroundStarted = false

    /**
     * Screen and clock changes are the moments the authoritative usage total moves or the
     * local day key changes, so each one forces an immediate usage poll + state refresh.
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF -> syncNow("screen ${action.substringAfterLast('.')}")
                Intent.ACTION_TIMEZONE_CHANGED -> syncNow("timezone changed")
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_DATE_CHANGED -> syncNow("clock/date changed")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Android gives a service started with startForegroundService only a few seconds to
        // call startForeground(). Enter the foreground before touching Room, coroutines, or
        // any other cold-start verification work; those run on a background dispatcher below.
        ensureNotificationChannel()
        foregroundStarted = startInForeground()
        if (!foregroundStarted) {
            stopSelf()
            return
        }
        registerStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = BlockingServiceStartReason.fromExtra(
            intent?.getStringExtra(EXTRA_START_REASON),
        )
        if (!foregroundStarted) {
            foregroundStarted = startInForeground()
        }
        if (!foregroundStarted) {
            BlockingRuntime.onForegroundServiceStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        // Record the reason once per service instance. A later start from the visible
        // Activity must not overwrite "after reboot" / "after app update": the first start
        // after process start is the one the self-check is meant to report.
        if (!startReasonRecorded) {
            BlockingRuntime.onForegroundServiceStarted(reason)
            startReasonRecorded = true
        }
        startPeriodicRefresh()
        Log.i(TAG, "Blocking foreground service started reason=$reason")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiping the task away must not stop blocking. START_STICKY restarts us.
        Log.i(TAG, "Task removed; keeping the blocking engine alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        engine = null
        unregisterStateReceiver()
        BlockingRuntime.onForegroundServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Blocking foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // A missing special-use permission or an OEM policy denial must not crash the app.
            Log.e(TAG, "Could not enter the foreground; blocking will not be kept alive", t)
            false
        }
    }

    private fun startPeriodicRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val loaded = try {
                // Building Room + the engine is the expensive part of a cold boot. It must
                // happen after startForeground() and off the main thread.
                withContext(Dispatchers.IO) { BlockingEngineProvider.get(applicationContext) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Could not initialize the blocking engine", t)
                return@launch
            }
            engine = loaded
            while (isActive) {
                sync(loaded)
                delay(REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private fun syncNow(reason: String) {
        val active = engine ?: return
        scope.launch {
            sync(active)
            Log.d(TAG, "Blocking state synced on $reason")
        }
    }

    /**
     * One authoritative sync: load rules, rewrite today's usage from UsageStatsManager, then
     * load the rewritten buckets into the decision snapshot. The two refreshes are cheap and
     * make the timer path see the new totals before the next window event.
     */
    private suspend fun sync(engine: BlockingEngine) {
        refreshQuietly(engine, "load rules before usage poll")
        try {
            engine.pollUsage()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Usage poll failed", t)
        }
        refreshQuietly(engine, "load rewritten usage")
    }

    private suspend fun refreshQuietly(engine: BlockingEngine, stage: String) {
        try {
            engine.refresh()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Blocking state refresh failed ($stage)", t)
        }
    }

    private fun registerStateReceiver() {
        if (stateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        stateReceiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!stateReceiverRegistered) return
        runCatching { unregisterReceiver(stateReceiver) }
            .onFailure { Log.w(TAG, "Could not unregister the state receiver", it) }
        stateReceiverRegistered = false
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.blocking_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.blocking_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blocking_notification)
            .setContentTitle(getString(R.string.blocking_notification_title))
            .setContentText(getString(R.string.blocking_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        openAppIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Id of the ongoing foreground-service notification. */
        const val NOTIFICATION_ID: Int = 0x0B10C

        const val CHANNEL_ID = "blocking_engine"
        const val EXTRA_START_REASON = "dev.brainrotstopper.extra.START_REASON"

        private const val TAG = "BrainrotBlocking"
        private const val FALLBACK_CHANNEL_ID = "blocking_start_denied"
        private const val FALLBACK_NOTIFICATION_ID = 0x0B10D
        private const val REQUEST_OPEN_APP = 50
        private const val REFRESH_INTERVAL_MILLIS = 60_000L

        /**
         * Starts the foreground service from a visible Activity, an accessibility-service
         * callback, or a boot/update receiver.
         *
         * A denied background start (API 31+) is caught and replaced with a high-priority
         * notification that reopens the app, so the user learns blocking is not running
         * instead of the process dying silently.
         */
        @JvmStatic
        fun start(
            context: Context,
            reason: BlockingServiceStartReason = BlockingServiceStartReason.MANUAL,
        ) {
            val intent = Intent(context, BlockingForegroundService::class.java)
                .putExtra(EXTRA_START_REASON, reason.name)
            try {
                context.startForegroundService(intent)
            } catch (denied: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Android denied the foreground-service start ($reason)", denied)
                postStartDeniedNotification(context)
            } catch (t: RuntimeException) {
                Log.w(TAG, "Foreground-service start failed ($reason)", t)
                postStartDeniedNotification(context)
            }
        }

        /** Stops the service. M8 gates this behind the password; tests use it directly. */
        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, BlockingForegroundService::class.java))
        }

        private fun postStartDeniedNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    FALLBACK_CHANNEL_ID,
                    context.getString(R.string.blocking_start_denied_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val builder = Notification.Builder(context, FALLBACK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_blocking_notification)
                .setContentTitle(context.getString(R.string.blocking_start_denied_title))
                .setContentText(context.getString(R.string.blocking_start_denied_text))
                .setAutoCancel(true)
            if (launch != null) {
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        REQUEST_OPEN_APP,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            runCatching { manager.notify(FALLBACK_NOTIFICATION_ID, builder.build()) }
                .onFailure { Log.w(TAG, "Could not post the start-denied notification", it) }
        }
    }
}
