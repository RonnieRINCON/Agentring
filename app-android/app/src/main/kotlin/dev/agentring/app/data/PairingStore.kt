package dev.agentring.app.data

import android.content.Context
import dev.agentring.core.ProtocolJson
import dev.agentring.core.PushConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Everything the phone needs to talk to its relay. Stored encrypted. */
@Serializable
data class Pairing(
    val relayUrl: String,
    val relayId: String,
    val deviceId: String,
    val deviceJwt: String,
    val pushSecret: String,
    val agentName: String,
    val pushConfig: PushConfig? = null,
)

class PairingStore(context: Context) {
    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences("agentring_state", Context.MODE_PRIVATE)

    private val _pairing = MutableStateFlow(load())
    val pairing: StateFlow<Pairing?> = _pairing
    val current: Pairing? get() = _pairing.value

    private val _setupComplete = MutableStateFlow(prefs.getBoolean(KEY_SETUP, false))
    /** Onboarding finished (the user saw the "paired" screen). */
    val setupComplete: StateFlow<Boolean> = _setupComplete

    private val _revoked = MutableStateFlow(false)
    /** The relay rejected our credentials; the user must pair again. */
    val revoked: StateFlow<Boolean> = _revoked

    private fun load(): Pairing? = secrets.get(KEY_PAIRING)?.let {
        runCatching { ProtocolJson.decodeFromString(Pairing.serializer(), it) }.getOrNull()
    }

    fun save(p: Pairing) {
        secrets.put(KEY_PAIRING, ProtocolJson.encodeToString(Pairing.serializer(), p))
        setSetupComplete(false)
        _revoked.value = false
        _pairing.value = p
    }

    fun clear() {
        secrets.put(KEY_PAIRING, null)
        setSetupComplete(false)
        _revoked.value = false
        _pairing.value = null
    }

    fun setSetupComplete(done: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP, done).apply()
        _setupComplete.value = done
    }

    fun markRevoked() {
        _revoked.value = true
    }

    private companion object {
        const val KEY_PAIRING = "pairing"
        const val KEY_SETUP = "setup_complete"
    }
}
