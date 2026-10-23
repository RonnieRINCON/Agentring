package dev.agentring.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.agentring.app.Graph

/**
 * Foreground service that keeps the process alive for the call and carries the
 * CallStyle notification (Android 14+ requires CallStyle to belong to a
 * foreground service or have a full-screen intent; we do both).
 *
 * Type `phoneCall` while ringing (allowed from an FCM high-priority message);
 * `phoneCall|microphone` once answered, which needs the app in the foreground
 * (the user just tapped Answer).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = Graph.calls.state.value
        if (call == null || call is CallUi.Ended) {
            // Started for a call that is already over: satisfy startForeground's contract, then leave.
            goForeground(CallNotifications.placeholder(this), micToo = false)
            stopNow()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ACTIVE -> goForeground(CallNotifications.ongoing(this, call), micToo = true)
            else -> goForeground(CallNotifications.incoming(this, call.info), micToo = false)
        }
        return START_NOT_STICKY
    }

    private fun goForeground(notification: android.app.Notification, micToo: Boolean) {
        val phone = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
        val mic = if (micToo && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID_CALL, notification, phone or mic)
        } catch (e: Exception) {
            // Microphone type refused (app not in the foreground, e.g. answered from a headset):
            // the active Telecom call still lets us record, so carry on as a phone call.
            Log.w(TAG, "startForeground(phone|mic) failed, retrying as phoneCall only", e)
            ServiceCompat.startForeground(this, CallNotifications.ID_CALL, notification, phone)
        }
    }

    /** Re-posts the notification (e.g. ringing → ongoing) without changing the service type. */
    fun refresh() {
        val call = Graph.calls.state.value ?: return
        val n = if (call is CallUi.Incoming) CallNotifications.incoming(this, call.info) else CallNotifications.ongoing(this, call)
        CallNotifications.post(this, n)
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "CallService"
        private const val ACTION_RING = "dev.agentring.app.call.RING"
        private const val ACTION_ACTIVE = "dev.agentring.app.call.ACTIVE"

        @Volatile private var instance: CallService? = null

        fun startRinging(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(ACTION_RING))
        }

        fun promoteToActive(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(ACTION_ACTIVE))
            } catch (e: Exception) {
                Log.w(TAG, "cannot promote the call service", e)
                instance?.refresh()
            }
        }

        fun refresh() {
            instance?.refresh()
        }

        fun stop() {
            instance?.stopNow()
        }
    }
}
