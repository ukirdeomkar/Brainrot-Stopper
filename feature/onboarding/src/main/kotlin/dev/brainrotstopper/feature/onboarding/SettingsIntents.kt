package dev.brainrotstopper.feature.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Turns a [DeepLink] into the real Settings intent, with fallbacks for OEMs that lack one. */
object SettingsIntents {

    fun toIntent(context: Context, link: DeepLink): Intent = when (link.type) {
        DeepLinkType.ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        DeepLinkType.USAGE_ACCESS_SETTINGS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        DeepLinkType.NOTIFICATION_LISTENER_SETTINGS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        DeepLinkType.NOTIFICATION_LISTENER_DETAIL -> Intent(
            Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
        ).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            requireNotNull(link.listenerComponent),
        )
        DeepLinkType.BATTERY_REQUEST_EXEMPTION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        ).setData(Uri.parse("package:${requireNotNull(link.packageName)}"))
        DeepLinkType.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * A broader Settings screen to try when the precise intent is not handled by the device.
     * Returns null when there is nothing better to open.
     */
    fun fallbackIntent(link: DeepLink): Intent? = when (link.type) {
        DeepLinkType.NOTIFICATION_LISTENER_DETAIL -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        DeepLinkType.BATTERY_REQUEST_EXEMPTION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> null
    }
}
