package dev.agentring.app.data

import android.content.Context
import androidx.core.content.edit

/** User-tweakable behaviour. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Register calls with Android Telecom (headset buttons, watches, cars, system audio routing).
     * Can be turned off if a device's Telecom misbehaves; calls then use our own routing.
     */
    var useTelecom: Boolean
        get() = prefs.getBoolean(KEY_USE_TELECOM, true)
        set(v) = prefs.edit { putBoolean(KEY_USE_TELECOM, v) }

    private companion object {
        const val KEY_USE_TELECOM = "use_telecom"
    }
}
