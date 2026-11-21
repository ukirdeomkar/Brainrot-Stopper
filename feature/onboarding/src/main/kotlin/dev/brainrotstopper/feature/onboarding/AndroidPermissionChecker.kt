package dev.brainrotstopper.feature.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService
import dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService

/**
 * Production [PermissionChecker]. Every check is a read-only query; no permission is requested
 * here. The UI opens the matching Settings screen and the check runs again on return.
 */
class AndroidPermissionChecker(context: Context) : PermissionChecker {

    private val appContext: Context = context.applicationContext

    override fun status(kind: PermissionKind): PermissionStatus = when (kind) {
        PermissionKind.ACCESSIBILITY -> accessibilityStatus()
        PermissionKind.USAGE_ACCESS -> usageAccessStatus()
        PermissionKind.NOTIFICATION_LISTENER -> notificationListenerStatus()
        PermissionKind.POST_NOTIFICATIONS -> postNotificationsStatus()
        PermissionKind.BATTERY_OPTIMIZATION -> batteryOptimizationStatus()
    }

    private fun accessibilityStatus(): PermissionStatus {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledIds = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { info -> info.id }
        val component = BrainrotAccessibilityService.component(appContext)
        if (AccessibilityMatching.isGranted(enabledIds, component.packageName, component.className)) {
            return PermissionStatus.GRANTED
        }
        // The manager can lag the Settings write; the secure setting is the source of truth.
        val stored = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return if (ComponentSettingMatching.containsComponent(
                stored,
                component.packageName,
                component.className,
            )
        ) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private fun usageAccessStatus(): PermissionStatus {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return PermissionStatus.GRANTED
        // Some OEMs report MODE_DEFAULT after the user grants access. A non-empty query proves it.
        if (hasAnyUsageStats()) return PermissionStatus.GRANTED
        return PermissionStatus.DENIED
    }

    private fun hasAnyUsageStats(): Boolean {
        return try {
            val usageStatsManager =
                appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - LOOKBACK_MILLIS,
                now,
            )
            stats.isNotEmpty()
        } catch (denied: SecurityException) {
            // Android 31/36 throw instead of returning an empty list when access is missing.
            false
        }
    }

    private fun notificationListenerStatus(): PermissionStatus {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val component = BrainrotNotificationListenerService.component(appContext)
        if (manager.isNotificationListenerAccessGranted(component)) {
            return PermissionStatus.GRANTED
        }
        // The manager can lag the Settings write; the secure setting is the source of truth.
        val stored = Settings.Secure.getString(
            appContext.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS,
        )
        return if (ComponentSettingMatching.containsComponent(
                stored,
                component.packageName,
                component.className,
            )
        ) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private fun postNotificationsStatus(): PermissionStatus {
        val granted = Build.VERSION.SDK_INT < PostNotificationRules.FIRST_RUNTIME_SDK ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return PostNotificationRules.statusFor(Build.VERSION.SDK_INT, granted)
    }

    private fun batteryOptimizationStatus(): PermissionStatus {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private companion object {
        /** Six hours is enough to see any foreground app on a device in use. */
        const val LOOKBACK_MILLIS: Long = 6L * 60L * 60L * 1000L

        /** Same key the framework uses; kept as a literal to avoid an API-level guard. */
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    }
}
