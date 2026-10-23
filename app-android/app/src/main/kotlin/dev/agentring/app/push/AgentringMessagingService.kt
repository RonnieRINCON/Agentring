package dev.agentring.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.agentring.app.Graph

class AgentringMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PushHandler.handle(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        Graph.push.onNewToken(token)
    }
}
