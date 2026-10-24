package dev.agentring.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayException(val status: Int, val code: String, message: String) : IOException(message) {
    /** Credentials are no longer valid (device revoked or relay reset): the app must re-pair. */
    val isUnauthorized: Boolean get() = status == 401
}

/** Blocking client for the relay REST API, as a device. Call from a background thread. */
class RelayApi(
    val baseUrl: String,
    private val deviceJwt: String?,
    private val http: OkHttpClient = defaultClient,
    private val userAgent: String = "agentring-android",
) {
    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()
        private val EMPTY = "".toRequestBody(null)
    }

    @Serializable
    private data class FcmBody(@SerialName("fcm_token") val fcmToken: String)

    @Serializable
    private data class PostMessageBody(
        val text: String,
        @SerialName("reply_to") val replyTo: String? = null,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
    )

    fun pair(req: PairRequest): PairResponse =
        call("POST", "/v1/pair", body(PairRequest.serializer(), req), PairResponse.serializer(), auth = false)

    fun updateFcmToken(token: String) {
        call<Unit>("POST", "/v1/device/fcm", body(FcmBody.serializer(), FcmBody(token)), null)
    }

    fun status(): Status = call("GET", "/v1/status", null, Status.serializer())

    fun listMessages(after: String? = null, limit: Int = 200): MessagePage {
        val q = buildString {
            append("/v1/messages?limit=").append(limit)
            if (after != null) append("&after=").append(after)
        }
        return call("GET", q, null, MessagePage.serializer())
    }

    fun getMessage(msgId: String): Message = call("GET", "/v1/messages/$msgId", null, Message.serializer())

    fun postMessage(text: String, replyTo: String? = null, clientMsgId: String? = null): Message =
        call(
            "POST", "/v1/messages",
            body(PostMessageBody.serializer(), PostMessageBody(text, replyTo, clientMsgId)),
            Message.serializer(),
        )

    fun markRead(msgId: String) {
        call<Unit>("POST", "/v1/messages/$msgId/read", "".toRequestBody(null), null)
    }

    fun getCall(callId: String): Call = call("GET", "/v1/calls/$callId", null, Call.serializer())

    /** Tells the relay this device is showing the incoming call (measures ring latency). */
    fun reportRinging(callId: String) {
        call<Unit>("POST", "/v1/calls/$callId/ringing", EMPTY, null)
    }

    /** Answers a ringing call; after the ring window it fails with 409 `invalid_state` (use [joinCall]). */
    fun acceptCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/accept", EMPTY, CallWithJoin.serializer())

    /**
     * Joins an agent call that is ringing or active, also after this phone's ring
     * ended, it declined or it hung up. 409 `call_ended` once the call is over.
     */
    fun joinCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/join", EMPTY, CallWithJoin.serializer())

    fun declineCall(callId: String): Call = call("POST", "/v1/calls/$callId/decline", EMPTY, Call.serializer())

    fun endCall(callId: String): Call = call("POST", "/v1/calls/$callId/end", EMPTY, Call.serializer())

    fun webSocketRequest(afterSeq: Long): Request {
        val url = "${baseUrl.trimEnd('/')}/v1/ws".toHttpUrl().newBuilder()
            .addQueryParameter("after_seq", afterSeq.toString())
            .build()
        return Request.Builder().url(url)
            .header("Authorization", "Bearer ${requireNotNull(deviceJwt)}")
            .header("User-Agent", userAgent)
            .build()
    }

    private fun <T> body(serializer: KSerializer<T>, value: T): RequestBody =
        ProtocolJson.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> call(method: String, path: String, body: RequestBody?, out: KSerializer<T>?, auth: Boolean = true): T {
        val rb = Request.Builder().url(baseUrl.trimEnd('/') + path).method(method, body).header("User-Agent", userAgent)
        if (auth) rb.header("Authorization", "Bearer ${requireNotNull(deviceJwt) { "not paired" }}")
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val err = runCatching { ProtocolJson.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
                throw RelayException(res.code, err?.code ?: "http_${res.code}", err?.message ?: "HTTP ${res.code}")
            }
            @Suppress("UNCHECKED_CAST")
            return if (out == null) Unit as T else ProtocolJson.decodeFromString(out, text)
        }
    }
}
