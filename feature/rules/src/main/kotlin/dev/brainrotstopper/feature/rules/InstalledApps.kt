package dev.brainrotstopper.feature.rules

import android.content.Context
import android.content.Intent

/** A launchable app the user can add to the guard list. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/** Pure selection rules for the app picker, kept separate from [android.content.pm.PackageManager]. */
object AppCatalogPolicy {

    fun select(
        entries: List<InstalledApp>,
        excludedPackages: Set<String>,
    ): List<InstalledApp> =
        entries
            .asSequence()
            .filter { it.packageName !in excludedPackages }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<InstalledApp> { it.label.lowercase() }
                    .thenBy { it.packageName },
            )
            .toList()
}

/** Reads installed apps. The narrow launcher query is declared in this module's manifest. */
fun interface AppCatalog {
    fun installedApps(): List<InstalledApp>
}

class PackageManagerAppCatalog(context: Context) : AppCatalog {

    private val packageManager = context.packageManager
    private val ownPackage = context.packageName

    override fun installedApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager.queryIntentActivities(intent, 0).mapNotNull { resolved ->
            val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
            val label = packageManager.getApplicationLabel(info)
                .toString()
                .ifBlank { info.packageName }
            InstalledApp(packageName = info.packageName, label = label)
        }
        return AppCatalogPolicy.select(entries, excludedPackages = setOf(ownPackage))
    }
}
