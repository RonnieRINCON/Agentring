package dev.agentring.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.agentring.app.Graph

class AgentringMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // A high-priority message that arrives deprioritized can't start the call service.
        if (message.priority != message.originalPriority) {
            Log.w("AgentringFCM", "push deprioritized: original=${message.originalPriority} delivered=${message.priority}")
        }
        PushHandler.handle(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        Graph.push.onNewToken(token)
    }
}
