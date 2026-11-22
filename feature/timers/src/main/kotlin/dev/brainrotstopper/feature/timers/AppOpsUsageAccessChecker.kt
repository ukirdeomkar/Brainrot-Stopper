package dev.brainrotstopper.feature.timers

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Reads the protected `PACKAGE_USAGE_STATS` app-op, which is what Android toggles when the
 * user grants Usage Access in Settings.
 *
 * The query itself returns an empty map when access is missing, so the app must not treat an
 * empty result as "no usage". [UsageTracker] relies on this checker to distinguish the two.
 */
class AppOpsUsageAccessChecker(context: Context) : UsageAccessChecker {

    private val appContext = context.applicationContext

    override fun status(): UsageAccessStatus {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
            ?: return UsageAccessStatus.REVOKED
        val mode = try {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        } catch (t: Throwable) {
            return UsageAccessStatus.REVOKED
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> UsageAccessStatus.GRANTED
            else -> UsageAccessStatus.REVOKED
        }
    }
}
