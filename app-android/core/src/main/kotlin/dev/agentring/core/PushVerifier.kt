package dev.agentring.core

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies push payloads: only events signed by the paired relay are accepted.
 * sig = base64url(HMAC-SHA256(base64url_decode(push_secret), utf8(event))).
 */
object PushVerifier {
    sealed interface Result {
        data class Ok(val event: Event) : Result
        data class Rejected(val reason: String) : Result
    }

    fun sign(pushSecret: String, eventJson: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(pushSecret), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(eventJson.toByteArray(Charsets.UTF_8)))
    }

    fun verify(data: Map<String, String>, expectedRelayId: String, pushSecret: String): Result {
        if (data["v"] != "1") return Result.Rejected("unsupported payload version ${data["v"]}")
        if (data["relay_id"] != expectedRelayId) return Result.Rejected("payload from a different relay")
        val eventJson = data["event"] ?: return Result.Rejected("missing event")
        val sig = data["sig"] ?: return Result.Rejected("missing signature")
        val expected = sign(pushSecret, eventJson)
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) {
            return Result.Rejected("bad signature")
        }
        return try {
            Result.Ok(Event.parse(eventJson))
        } catch (e: Exception) {
            Result.Rejected("malformed event: ${e.message}")
        }
    }
}
