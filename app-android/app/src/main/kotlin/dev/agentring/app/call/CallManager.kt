package dev.agentring.app.call

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.edit
import androidx.core.telecom.CallEndpointCompat
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.ui.CallActivity
import dev.agentring.core.Event
import dev.agentring.core.RelayException
import dev.agentring.core.RelayUrlPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the call screen and the notification show. */
data class CallInfo(
    val callId: String,
    val agentName: String,
    val reason: String,
    val urgent: Boolean,
    val expiresAt: Long,
)

sealed interface CallUi {
    val info: CallInfo

    data class Incoming(override val info: CallInfo) : CallUi
    data class Connecting(override val info: CallInfo) : CallUi
    data class Active(
        override val info: CallInfo,
        /** [SystemClock.elapsedRealtime] when audio started, for the timer. */
        val since: Long,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val reconnecting: Boolean = false,
    ) : CallUi
    data class Ended(override val info: CallInfo, val messageRes: Int) : CallUi
}

/**
 * The one incoming call: ringing, answering, the voice session and hanging up.
 * Invites arrive by FCM (app in background) or WebSocket (app open); both are
 * de-duplicated by call id. All state changes happen on the main thread.
 */
class CallManager(private val context: Context) : TelecomBridge.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ringer = Ringer(context)
    private val telecom = TelecomBridge(context, scope)
    private val prefs = context.getSharedPreferences("calls", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state

    /** Calls we've already handled, so a second copy of an invite (FCM + WS) doesn't ring again. */
    private val seen = LinkedHashSet<String>()
    private var session: VoiceSession? = null
    private var telecomActive = false
    private var timeoutJob: Job? = null
    /** Collectors that live as long as the current call. */
    private val callJobs = mutableListOf<Job>()

    private val myDeviceId: String? get() = Graph.pairing.current?.deviceId

    // ---------------------------------------------------------------- incoming

    /**
     * Rings for an invite. Safe to call from any thread; starts the foreground
     * service synchronously so it happens inside FCM's high-priority window.
     */
    fun onInvite(invite: Event.CallInvite, verifyFirst: Boolean = false) {
        if (invite.direction != "agent_to_user") return
        val now = System.currentTimeMillis()
        if (now > invite.expiresAt + CLOCK_SKEW_MS) {
            Log.i(TAG, "ignoring expired invite ${invite.callId}")
            return
        }
        val info = CallInfo(invite.callId, invite.agentName, invite.reason, invite.priority == "urgent", invite.expiresAt)
        synchronized(seen) {
            if (!seen.add(invite.callId)) return
            while (seen.size > 50) seen.remove(seen.first())
            val current = _state.value
            if (current != null && current !is CallUi.Ended) {
                Log.w(TAG, "already in a call; ignoring ${invite.callId}")
                return
            }
            if (!verifyFirst) {
                _state.value = CallUi.Incoming(info)
                CallService.startRinging(context)
            }
        }
        scope.launch {
            if (verifyFirst) {
                // A WebSocket replay may carry an invite that was answered or canceled since.
                val call = withContext(Dispatchers.IO) { runCatching { Graph.api()?.getCall(info.callId) }.getOrNull() }
                if (call?.isRinging != true || _state.value.let { it != null && it !is CallUi.Ended }) return@launch
                _state.value = CallUi.Incoming(info)
                CallService.startRinging(context)
            }
            startRinging(info)
        }
    }

    private suspend fun startRinging(info: CallInfo) {
        ringer.start(info.urgent)
        Log.i(TAG, "ringing ${info.callId} (${System.currentTimeMillis() - (info.expiresAt - RING_WINDOW_MS)} ms after invite)")
        scope.launch(Dispatchers.IO) {
            repeat(2) {
                try {
                    Graph.api()?.reportRinging(info.callId)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "report ringing failed", e)
                }
            }
        }
        // Don't ring past the relay's deadline even if the "missed" push never arrives.
        timeoutJob = scope.launch {
            delay((info.expiresAt - System.currentTimeMillis() + 2_000).coerceAtLeast(0))
            if (isCurrent(info.callId) && _state.value is CallUi.Incoming) finish(info, R.string.call_missed, DisconnectCause.MISSED, notifyMissed = true)
        }
        telecomActive = Graph.settings.useTelecom && telecom.addIncoming(info.agentName, info.callId, this)
        Log.i(TAG, "telecom: $telecomActive")
        if (!isCurrent(info.callId)) return
        // Build the WebRTC stack and warm the connection while the user decides.
        val s = VoiceSession(context, scope, telecomRoutesAudio = telecomActive)
        session = s
        prefs.getString(KEY_LAST_LIVEKIT_URL, null)?.let { s.prepare(it) }
    }

    fun answer() {
        val cur = _state.value as? CallUi.Incoming ?: return
        val info = cur.info
        ringer.stop()
        timeoutJob?.cancel()
        _state.value = CallUi.Connecting(info)
        if (telecomActive) telecom.answer()
        CallService.promoteToActive(context)
        scope.launch {
            val join = try {
                withContext(Dispatchers.IO) { Graph.api()!!.acceptCall(info.callId).join }
            } catch (e: RelayException) {
                Log.w(TAG, "accept failed: ${e.code}")
                finish(info, if (e.code == "invalid_state") R.string.call_unavailable else R.string.call_failed, DisconnectCause.ERROR)
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "accept failed", e)
                finish(info, R.string.call_failed, DisconnectCause.ERROR)
                return@launch
            }
            try {
                RelayUrlPolicy.checkLiveKit(join.livekitUrl)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "refusing LiveKit URL from relay: ${e.message}")
                withContext(Dispatchers.IO) { runCatching { Graph.api()?.endCall(info.callId) } }
                finish(info, R.string.call_failed, DisconnectCause.ERROR)
                return@launch
            }
            prefs.edit { putString(KEY_LAST_LIVEKIT_URL, join.livekitUrl) }
            val s = session ?: VoiceSession(context, scope, telecomActive).also { session = it }
            watch(info, s)
            val t0 = SystemClock.elapsedRealtime()
            try {
                s.connect(join.livekitUrl, join.livekitToken, withMic = CallPermissions.hasMic(context))
            } catch (e: Exception) {
                Log.w(TAG, "LiveKit connect failed", e)
                withContext(Dispatchers.IO) { runCatching { Graph.api()?.endCall(info.callId) } }
                finish(info, R.string.call_failed, DisconnectCause.ERROR)
                return@launch
            }
            Log.i(TAG, "in the room after ${SystemClock.elapsedRealtime() - t0} ms")
            if (isCurrent(info.callId) && _state.value is CallUi.Connecting) {
                _state.value = CallUi.Active(info, since = SystemClock.elapsedRealtime(), speaker = speakerOn())
                CallService.refresh()
            }
        }
    }

    fun decline() {
        val cur = _state.value as? CallUi.Incoming ?: return
        scope.launch(Dispatchers.IO) { runCatching { Graph.api()?.declineCall(cur.info.callId) } }
        finish(cur.info, R.string.call_declined, DisconnectCause.REJECTED)
    }

    fun hangUp() {
        val cur = _state.value ?: return
        when (cur) {
            is CallUi.Incoming -> decline()
            is CallUi.Connecting, is CallUi.Active -> {
                scope.launch(Dispatchers.IO) { runCatching { Graph.api()?.endCall(cur.info.callId) } }
                finish(cur.info, R.string.call_ended, DisconnectCause.LOCAL)
            }
            is CallUi.Ended -> Unit
        }
    }

    fun toggleMute() {
        val cur = _state.value as? CallUi.Active ?: return
        session?.setMuted(!cur.muted)
        _state.value = cur.copy(muted = !cur.muted)
    }

    fun toggleSpeaker() {
        val cur = _state.value as? CallUi.Active ?: return
        val on = !cur.speaker
        if (telecomActive) {
            val eps = telecom.endpoints.value
            val target = if (on) {
                eps.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
            } else {
                eps.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                    ?: eps.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                    ?: eps.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
            }
            target?.let { telecom.route(it) }
        } else {
            session?.setSpeaker(on)
        }
        _state.value = cur.copy(speaker = on)
    }

    private fun speakerOn(): Boolean =
        if (telecomActive) telecom.endpoint.value?.type == CallEndpointCompat.TYPE_SPEAKER else session?.speakerOn == true

    /** Relay events (push or WebSocket) about a call we may be showing. */
    fun onCallEvent(event: Event) {
        scope.launch {
            val cur = _state.value ?: return@launch
            val info = cur.info
            when (event) {
                is Event.CallAnswer -> if (event.callId == info.callId && event.deviceId != myDeviceId && cur is CallUi.Incoming) {
                    // Answered or declined on another device.
                    finish(info, R.string.call_answered_elsewhere, DisconnectCause.ANSWERED_ELSEWHERE)
                }
                is Event.CallEnd -> if (event.callId == info.callId && cur !is CallUi.Ended) {
                    val missed = cur is CallUi.Incoming
                    finish(info, if (missed) R.string.call_missed else R.string.call_ended_by_agent,
                        if (missed) DisconnectCause.CANCELED else DisconnectCause.REMOTE, notifyMissed = missed)
                }
                is Event.CallMissed -> if (event.callId == info.callId && cur is CallUi.Incoming) {
                    finish(info, R.string.call_missed, DisconnectCause.MISSED, notifyMissed = true)
                }
                else -> Unit
            }
        }
    }

    private fun watch(info: CallInfo, s: VoiceSession) {
        callJobs += scope.launch {
            s.signals.collect { sig ->
                val cur = _state.value as? CallUi.Active
                when (sig) {
                    VoiceSession.Signal.Reconnecting -> if (cur != null) _state.value = cur.copy(reconnecting = true)
                    VoiceSession.Signal.Reconnected -> if (cur != null) _state.value = cur.copy(reconnecting = false)
                    is VoiceSession.Signal.Gone -> if (isCurrent(info.callId) && _state.value !is CallUi.Ended) {
                        Log.i(TAG, "voice session gone: ${sig.reason}")
                        scope.launch(Dispatchers.IO) { runCatching { Graph.api()?.endCall(info.callId) } }
                        finish(info, R.string.call_ended_by_agent, DisconnectCause.REMOTE)
                    }
                    null -> Unit
                }
            }
        }
        callJobs += scope.launch {
            telecom.endpoint.collect { ep ->
                val cur = _state.value as? CallUi.Active ?: return@collect
                if (ep != null) _state.value = cur.copy(speaker = ep.type == CallEndpointCompat.TYPE_SPEAKER)
            }
        }
    }

    // ---------------------------------------------------------------- Telecom callbacks

    override fun onSystemAnswer() {
        scope.launch {
            answer()
            // Answered from a headset/watch: bring up the call screen if Android lets us.
            runCatching {
                context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    override fun onSystemDisconnect(cause: DisconnectCause) {
        scope.launch {
            telecomActive = false
            when (_state.value) {
                is CallUi.Incoming -> decline()
                is CallUi.Connecting, is CallUi.Active -> hangUp()
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun isCurrent(callId: String) = _state.value?.info?.callId == callId

    private fun finish(info: CallInfo, messageRes: Int, cause: Int, notifyMissed: Boolean = false) {
        if (!isCurrent(info.callId) || _state.value is CallUi.Ended) return
        Log.i(TAG, "call ${info.callId} finished: ${context.getString(messageRes)}")
        ringer.stop()
        timeoutJob?.cancel()
        callJobs.forEach { it.cancel() }
        callJobs.clear()
        session?.close()
        session = null
        if (telecomActive) telecom.disconnect(cause)
        telecomActive = false
        CallService.stop()
        if (notifyMissed) CallNotifications.showMissed(context, info)
        _state.value = CallUi.Ended(info, messageRes)
        scope.launch {
            delay(ENDED_LINGER_MS)
            if (_state.value.let { it is CallUi.Ended && it.info.callId == info.callId }) _state.value = null
        }
    }

    companion object {
        private const val TAG = "CallManager"
        private const val KEY_LAST_LIVEKIT_URL = "last_livekit_url"
        private const val CLOCK_SKEW_MS = 5_000L
        private const val RING_WINDOW_MS = 30_000L
        const val ENDED_LINGER_MS = 2_000L
    }
}
