package dev.agentring.app.call

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One LiveKit room for one call (a Room is never reused: reusing one routes
 * audio to the earpiece on later calls). Created while the phone rings so the
 * expensive WebRTC setup is done before the user answers.
 */
class VoiceSession(
    context: Context,
    private val scope: CoroutineScope,
    /** With Telecom, the system owns audio routing; otherwise LiveKit's AudioSwitch does. */
    private val telecomRoutesAudio: Boolean,
) {
    sealed interface Signal {
        data object Reconnecting : Signal
        data object Reconnected : Signal
        /** The agent left or the connection is gone for good. */
        data class Gone(val reason: String) : Signal
    }

    private val audioSwitch: AudioSwitchHandler? = if (telecomRoutesAudio) null else AudioSwitchHandler(context.applicationContext).apply {
        // LiveKit's default prefers the speaker; a phone call starts on the earpiece.
        preferredDeviceList = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
    }

    val room: Room = LiveKit.create(
        appContext = context.applicationContext,
        options = RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false,
            ),
            // Voice, not music: 32 kbps Opus with DTX, plus redundancy (RED) against packet loss.
            audioTrackPublishDefaults = AudioTrackPublishDefaults(audioBitrate = 32_000, dtx = true, red = true),
        ),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioHandler = audioSwitch ?: NoAudioHandler()),
        ),
    )

    private val _signals = MutableStateFlow<Signal?>(null)
    val signals: StateFlow<Signal?> = _signals
    private var eventsJob: Job? = null
    @Volatile private var closing = false

    /** Warms up DNS/TLS to the LiveKit server while the phone rings. */
    suspend fun prepare(url: String) {
        runCatching { room.prepareConnection(url) }
    }

    /** Joins with the microphone published as part of the connection. */
    suspend fun connect(url: String, token: String, withMic: Boolean) {
        eventsJob = scope.launch {
            room.events.collect { e ->
                when (e) {
                    is RoomEvent.Reconnecting -> _signals.value = Signal.Reconnecting
                    is RoomEvent.Reconnected -> _signals.value = Signal.Reconnected
                    is RoomEvent.ParticipantDisconnected -> {
                        Log.i(TAG, "${e.participant.identity} left")
                        if (e.participant.identity?.value == "agent") _signals.value = Signal.Gone("agent_left")
                    }
                    is RoomEvent.Disconnected -> if (!closing) _signals.value = Signal.Gone("disconnected: ${e.reason}")
                    else -> Unit
                }
            }
        }
        room.connect(url, token, ConnectOptions(audio = withMic))
    }

    fun setMuted(muted: Boolean) {
        scope.launch { room.localParticipant.setMicrophoneEnabled(!muted) }
    }

    /** Fallback routing (no Telecom): toggles the speakerphone. */
    fun setSpeaker(on: Boolean) {
        val sw = audioSwitch ?: return
        val target = if (on) {
            sw.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
        } else {
            sw.availableAudioDevices.firstOrNull { it !is AudioDevice.Speakerphone }
        }
        sw.selectDevice(target)
    }

    val speakerOn: Boolean get() = audioSwitch?.selectedAudioDevice is AudioDevice.Speakerphone

    fun close() {
        closing = true
        eventsJob?.cancel()
        room.disconnect()
        room.release()
    }

    private companion object {
        const val TAG = "VoiceSession"
    }
}
