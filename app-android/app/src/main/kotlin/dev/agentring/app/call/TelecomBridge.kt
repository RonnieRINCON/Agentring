package dev.agentring.app.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers the call with Android Telecom through Jetpack Core-Telecom, so the
 * system knows we're in a call (Bluetooth headset buttons, watches, cars,
 * audio routing, other calls). Optional: if the platform refuses, the call
 * still works with our own notification and audio routing.
 */
class TelecomBridge(private val context: Context, private val scope: CoroutineScope) {
    interface Listener {
        /** The user answered from outside the app (headset, watch, car). */
        fun onSystemAnswer()

        /** Telecom ended the call (e.g. the user hung up from a headset, or another call took over). */
        fun onSystemDisconnect(cause: DisconnectCause)
    }

    private sealed interface Command {
        data object Answer : Command
        data class Disconnect(val cause: Int) : Command
        data class Route(val endpoint: CallEndpointCompat) : Command
    }

    private val manager by lazy { CallsManager(context) }
    private var commands = Channel<Command>(Channel.UNLIMITED)
    private var job: Job? = null

    private val _endpoint = MutableStateFlow<CallEndpointCompat?>(null)
    val endpoint: StateFlow<CallEndpointCompat?> = _endpoint
    private val _endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    val endpoints: StateFlow<List<CallEndpointCompat>> = _endpoints
    private val _muted = MutableStateFlow(false)
    val systemMuted: StateFlow<Boolean> = _muted

    /** Adds an incoming call. Returns false (quickly) if Telecom can't take it. */
    suspend fun addIncoming(displayName: String, callId: String, listener: Listener): Boolean {
        val ready = CompletableDeferred<Boolean>()
        commands = Channel(Channel.UNLIMITED)
        val cmds = commands
        job = scope.launch {
            try {
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                val attrs = CallAttributesCompat(
                    displayName = displayName,
                    address = Uri.fromParts("agentring", callId, null),
                    direction = CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = 0,
                )
                manager.addCall(
                    attrs,
                    onAnswer = { listener.onSystemAnswer() },
                    onDisconnect = { cause ->
                        Log.i(TAG, "telecom disconnect: $cause")
                        listener.onSystemDisconnect(cause)
                    },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    ready.complete(true)
                    launch { currentCallEndpoint.collect { _endpoint.value = it } }
                    launch { availableEndpoints.collect { _endpoints.value = it } }
                    launch { isMuted.collect { _muted.value = it } }
                    launch {
                        for (c in cmds) {
                            // A failed command must never kill this loop: the call would then
                            // linger in Telecom and block every later call (CallException 5).
                            val r = runCatching {
                                when (c) {
                                    Command.Answer -> answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                                    is Command.Disconnect -> disconnect(DisconnectCause(platformCause(c.cause)))
                                    is Command.Route -> requestEndpointChange(c.endpoint)
                                }
                            }
                            if (r.getOrNull() !is CallControlResult.Success) Log.w(TAG, "$c failed: ${r.exceptionOrNull() ?: r.getOrNull()}")
                            if (c is Command.Disconnect) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom unavailable, continuing without it", e)
            } finally {
                ready.complete(false)
                _endpoint.value = null
                _endpoints.value = emptyList()
            }
        }
        // addCall must not hold up the ringing; give it a moment, then go on without it.
        val ok = withTimeoutOrNull(3_000) { ready.await() }
        if (ok == null) {
            Log.w(TAG, "Telecom addCall timed out")
            job?.cancel()
        }
        return ok == true
    }

    fun answer() {
        commands.trySend(Command.Answer)
    }

    fun disconnect(cause: Int) {
        commands.trySend(Command.Disconnect(cause))
    }

    fun route(endpoint: CallEndpointCompat) {
        commands.trySend(Command.Route(endpoint))
    }

    private companion object {
        const val TAG = "TelecomBridge"

        /** CallControl.disconnect only accepts LOCAL, REMOTE, MISSED and REJECTED. */
        fun platformCause(cause: Int): Int = when (cause) {
            DisconnectCause.LOCAL, DisconnectCause.REMOTE, DisconnectCause.MISSED, DisconnectCause.REJECTED -> cause
            DisconnectCause.CANCELED -> DisconnectCause.MISSED
            DisconnectCause.ANSWERED_ELSEWHERE -> DisconnectCause.REMOTE
            else -> DisconnectCause.LOCAL
        }
    }
}
