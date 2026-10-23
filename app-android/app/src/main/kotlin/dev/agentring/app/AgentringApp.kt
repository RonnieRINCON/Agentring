package dev.agentring.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.agentring.app.notify.Notifications

class AgentringApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        // Firebase must be ready before FirebaseMessagingService delivers a push.
        Graph.pairing.current?.let { Graph.push.initFirebase(it.pushConfig) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(Graph.connection)
    }
}
