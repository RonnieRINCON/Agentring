package dev.agentring.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.agentring.app.Graph

/** Decline / hang up from the call notification (no UI needed for either). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> Graph.calls.decline()
            ACTION_HANG_UP -> Graph.calls.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "dev.agentring.app.call.DECLINE"
        const val ACTION_HANG_UP = "dev.agentring.app.call.HANG_UP"
    }
}
