package dev.agentring.app.push

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.agentring.app.Graph
import dev.agentring.core.PushConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

sealed interface PushState {
    data object NotConfigured : PushState
    data object Registering : PushState
    data object Registered : PushState
    data class Unavailable(val reason: String) : PushState
    data class Error(val message: String) : PushState
}

/**
 * Registers for FCM using the Firebase project the relay announced at pairing
 * (no google-services.json compiled into the app), and keeps the relay's copy
 * of our FCM token current.
 */
class PushRegistrar(private val context: Context) {
    private val prefs = context.getSharedPreferences("agentring_state", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PushState>(PushState.NotConfigured)
    val state: StateFlow<PushState> = _state

    /** Initialises the default FirebaseApp. Safe to call repeatedly. Returns false if push can't be used. */
    fun initFirebase(config: PushConfig?): Boolean {
        if (config == null || config.provider != "fcm") {
            _state.value = PushState.NotConfigured
            return false
        }
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (gms != ConnectionResult.SUCCESS) {
            _state.value = PushState.Unavailable("Google Play services ($gms)")
            return false
        }
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            if (existing.options.applicationId != config.fcm.applicationId) {
                _state.value = PushState.Error("restart the app to switch Firebase project")
                return false
            }
            return true
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(config.fcm.projectId)
            .setApplicationId(config.fcm.applicationId)
            .setApiKey(config.fcm.apiKey)
            .setGcmSenderId(config.fcm.senderId)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }

    /** Makes sure the relay has our current FCM token. */
    suspend fun ensureRegistered() = mutex.withLock {
        val p = Graph.pairing.current ?: return@withLock
        if (!initFirebase(p.pushConfig)) return@withLock
        _state.value = PushState.Registering
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            sendIfChanged(p.deviceId, token)
            _state.value = PushState.Registered
        } catch (e: Exception) {
            Log.w(TAG, "FCM registration failed", e)
            _state.value = PushState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun onNewToken(token: String) {
        val p = Graph.pairing.current ?: return
        Graph.appScope.launch {
            mutex.withLock {
                try {
                    sendIfChanged(p.deviceId, token)
                    _state.value = PushState.Registered
                } catch (e: Exception) {
                    _state.value = PushState.Error(e.message ?: "token upload failed")
                }
            }
        }
    }

    private fun sendIfChanged(deviceId: String, token: String) {
        val key = "fcm_sent_$deviceId"
        if (prefs.getString(key, null) == token) return
        val api = Graph.api() ?: return
        api.updateFcmToken(token)
        prefs.edit().putString(key, token).apply()
    }

    fun reset() {
        _state.value = PushState.NotConfigured
    }

    private companion object {
        const val TAG = "PushRegistrar"
    }
}
