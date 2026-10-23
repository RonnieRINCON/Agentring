package dev.agentring.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the ringtone and vibrates for an incoming call. A self-managed call gets
 * no system ringer, so the app does it, honouring the ringer mode. Urgent calls
 * ring on the alarm stream, which also sounds in silent mode.
 */
class Ringer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun start(urgent: Boolean) {
        stop()
        val am = context.getSystemService(AudioManager::class.java)
        val mode = am.ringerMode
        val sound = urgent || mode == AudioManager.RINGER_MODE_NORMAL
        val vibrate = urgent || mode != AudioManager.RINGER_MODE_SILENT
        if (sound) player = startSound(urgent)
        if (vibrate) startVibration(urgent)
        Log.i(TAG, "ringing (urgent=$urgent, ringerMode=$mode, sound=$sound, vibrate=$vibrate)")
    }

    private fun startSound(urgent: Boolean): MediaPlayer? {
        val type = if (urgent) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_RINGTONE
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return null
        val attrs = AudioAttributes.Builder()
            .setUsage(if (urgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot play ringtone $uri", e)
            null
        }
    }

    private fun startVibration(urgent: Boolean) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 600)
        val attrs = AudioAttributes.Builder()
            .setUsage(if (urgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
        vibrator = v
    }

    @Synchronized
    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private companion object {
        const val TAG = "Ringer"
    }
}
