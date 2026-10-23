package dev.agentring.core

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Resumable event stream: reconnects with exponential backoff and resumes
 * from the last `seq` the caller has processed.
 */
class RelaySocket(
    private val api: RelayApi,
    private val lastSeq: () -> Long,
    private val listener: Listener,
    private val http: OkHttpClient = RelayApi.defaultClient,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    interface Listener {
        fun onEvent(event: Event)
        fun onState(state: State)
        /** Credentials rejected (revoked device). The socket stops reconnecting. */
        fun onUnauthorized()
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-socket").apply { isDaemon = true } }
    private var ws: WebSocket? = null
    private var retry: ScheduledFuture<*>? = null
    private var attempts = 0
    @Volatile private var running = false

    fun start() = scheduler.execute {
        if (running) return@execute
        running = true
        connect()
    }

    fun stop() = scheduler.execute {
        running = false
        retry?.cancel(false)
        ws?.close(1000, "bye")
        ws = null
        listener.onState(State.DISCONNECTED)
    }

    private fun connect() {
        if (!running) return
        listener.onState(State.CONNECTING)
        val socket = http.newWebSocket(api.webSocketRequest(lastSeq()), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = scheduler.execute {
                if (ws !== webSocket) return@execute
                attempts = 0
                listener.onState(State.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try {
                    Event.parse(text)
                } catch (e: Exception) {
                    return
                }
                listener.onEvent(event)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                scheduler.execute { onDown(webSocket, unauthorized = code == 4401) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                scheduler.execute { onDown(webSocket, unauthorized = response?.code == 401) }
        })
        ws = socket
    }

    private fun onDown(socket: WebSocket, unauthorized: Boolean) {
        if (ws !== socket) return
        ws = null
        listener.onState(State.DISCONNECTED)
        if (unauthorized) {
            running = false
            listener.onUnauthorized()
            return
        }
        if (!running) return
        val base = min(30_000L, 1_000L shl min(attempts, 5))
        attempts++
        val delay = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        retry = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }
}
