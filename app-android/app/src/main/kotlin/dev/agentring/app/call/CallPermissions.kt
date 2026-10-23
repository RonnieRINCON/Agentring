package dev.agentring.app.call

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.agentring.app.notify.Notifications

/**
 * Everything that has to be in place for the phone to ring reliably when locked,
 * with a way to fix each item. Shown in onboarding and in Settings.
 */
object CallPermissions {
    enum class Item { NOTIFICATIONS, MICROPHONE, FULL_SCREEN, BATTERY, BACKGROUND, DND }

    fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** "Restricted" battery usage blocks the call service from starting in the background. */
    fun backgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun hasDndAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun isOk(context: Context, item: Item): Boolean = when (item) {
        Item.NOTIFICATIONS -> Notifications.canNotify(context)
        Item.MICROPHONE -> hasMic(context)
        Item.FULL_SCREEN -> canFullScreen(context)
        Item.BATTERY -> ignoresBatteryOptimizations(context)
        Item.BACKGROUND -> !backgroundRestricted(context)
        Item.DND -> hasDndAccess(context)
    }

    /** Items that stop calls from working, as opposed to nice-to-haves. */
    val required = listOf(Item.NOTIFICATIONS, Item.MICROPHONE, Item.FULL_SCREEN, Item.BACKGROUND)

    /** Settings screen that fixes [item] (runtime permissions are requested by the UI instead). */
    fun settingsIntent(context: Context, item: Item): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (item) {
            Item.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            // Sideloaded app: the direct exemption dialog is allowed (Play restricts it to some app types).
            Item.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Item.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Item.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
