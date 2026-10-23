package dev.agentring.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CoreTest {
    private val protocolDir = File(System.getProperty("agentring.protocolDir") ?: "../../protocol")

    @Test
    fun `every event fixture parses to a typed event`() {
        val files = File(protocolDir, "examples/events").listFiles()!!.filter { it.name.endsWith(".json") }
        assertTrue(files.size >= 10)
        for (f in files) {
            val json = f.readText()
            val ev = Event.parse(json)
            assertFalse("${f.name} parsed as Unknown", ev is Event.Unknown)
            val obj = ProtocolJson.parseToJsonElement(json).jsonObject
            assertEquals(obj["type"]!!.jsonPrimitive.content, ev.type)
            assertEquals(obj["id"]!!.jsonPrimitive.content, ev.id)
        }
        val msg = Event.parse(File(protocolDir, "examples/events/msg.new.json").readText()) as Event.MsgNew
        assertEquals("Build finished ✅", msg.text)
        assertEquals("agent", msg.from)
    }

    @Test
    fun `unknown event types are tolerated`() {
        val ev = Event.parse("""{"type":"future.thing","id":"evt_1","ts":5,"seq":9,"x":1}""")
        assertTrue(ev is Event.Unknown)
        assertEquals(9L, ev.seq)
    }

    @Test
    fun `push signature matches the shared test vector`() {
        val v = ProtocolJson.parseToJsonElement(File(protocolDir, "examples/push/signature-vector.json").readText()).jsonObject
        val secret = v["push_secret"]!!.jsonPrimitive.content
        val data = (v["data"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
        assertEquals(data["sig"], PushVerifier.sign(secret, data["event"]!!))

        val ok = PushVerifier.verify(data, data["relay_id"]!!, secret)
        assertTrue(ok is PushVerifier.Result.Ok)
        assertTrue((ok as PushVerifier.Result.Ok).event is Event.MsgNew)

        assertTrue(PushVerifier.verify(data, "rly_other", secret) is PushVerifier.Result.Rejected)
        val tampered = data + ("event" to data["event"]!!.replace("Build", "Evil"))
        assertEquals(PushVerifier.Result.Rejected("bad signature"), PushVerifier.verify(tampered, data["relay_id"]!!, secret))
        assertTrue(PushVerifier.verify(data - "sig", data["relay_id"]!!, secret) is PushVerifier.Result.Rejected)
    }

    @Test
    fun `qr payload parsing and url policy`() {
        val p = QrPayload.parse("""  {"v":1,"relay_url":"https://relay.example.com","pairing_token":"pt_abc"} """)
        assertEquals("https://relay.example.com", p.relayUrl)
        QrPayload.parse("""{"v":1,"relay_url":"http://192.168.1.20:8080","pairing_token":"pt_abc"}""")
        QrPayload.parse("""{"v":1,"relay_url":"http://10.0.2.2:8080","pairing_token":"pt_abc"}""")
        for (bad in listOf(
            "hello",
            """{"v":2,"relay_url":"https://x","pairing_token":"pt_abc"}""",
            """{"v":1,"relay_url":"http://relay.example.com","pairing_token":"pt_abc"}""",
            """{"v":1,"relay_url":"ftp://x","pairing_token":"pt_abc"}""",
            """{"v":1,"relay_url":"https://x","pairing_token":"nope"}""",
        )) {
            try {
                QrPayload.parse(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertFalse(RelayUrlPolicy.isPrivateHost("8.8.8.8"))
        assertFalse(RelayUrlPolicy.isPrivateHost("172.32.0.1"))
        assertTrue(RelayUrlPolicy.isPrivateHost("172.20.0.1"))
    }

    @Test
    fun `livekit url must be wss outside a private network`() {
        RelayUrlPolicy.checkLiveKit("wss://relay.example.com")
        RelayUrlPolicy.checkLiveKit("ws://localhost:7880")
        RelayUrlPolicy.checkLiveKit("ws://192.168.1.10:7880")
        for (bad in listOf("ws://relay.example.com", "http://8.8.8.8", "ftp://x", "not a url")) {
            try {
                RelayUrlPolicy.checkLiveKit(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `call join info decodes`() {
        val j = ProtocolJson.decodeFromString(
            CallWithJoin.serializer(),
            """{"call":{"call_id":"call_1","direction":"agent_to_user","state":"active","priority":"urgent","agent_name":"Claude",
               "created_at":1,"answered_at":2,"device_id":"dev_1","extra":true},
               "join":{"livekit_url":"wss://r","livekit_token":"t","room":"call_1","identity":"device_dev_1","expires_at":3}}""",
        )
        assertEquals("active", j.call.state)
        assertFalse(j.call.isRinging)
        assertEquals("wss://r", j.join.livekitUrl)
    }

    @Test
    fun `relay api speaks the protocol`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"device_id":"dev_1","device_jwt":"jwt","push_secret":"c2VjcmV0","relay_id":"rly_1","agent_name":"Claude",
                       "push_config":{"provider":"fcm","fcm":{"project_id":"p","application_id":"1:2:android:3","api_key":"k","sender_id":"2"}}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"msg_id":"msg_1","from":"user","text":"hi","created_at":5}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"Device revoked"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))

            val base = server.url("/").toString().trimEnd('/')
            val pair = RelayApi(base, null).pair(PairRequest("pt_x", "Pixel", appVersion = "0.1.0"))
            assertEquals("p", pair.pushConfig!!.fcm.projectId)
            val r1 = server.takeRequest()
            assertEquals("/v1/pair", r1.path)
            assertNull(r1.getHeader("Authorization"))
            val body = ProtocolJson.parseToJsonElement(r1.body.readUtf8()).jsonObject
            assertEquals("pt_x", body["pairing_token"]!!.jsonPrimitive.content)
            assertEquals("android", body["platform"]!!.jsonPrimitive.content)
            assertFalse("null fcm_token must be omitted", body.containsKey("fcm_token"))

            val api = RelayApi(base, "jwt")
            val m = api.postMessage("hi", clientMsgId = "c1")
            assertEquals("msg_1", m.msgId)
            val r2 = server.takeRequest()
            assertEquals("Bearer jwt", r2.getHeader("Authorization"))
            assertEquals("""{"text":"hi","client_msg_id":"c1"}""", r2.body.readUtf8())

            try {
                api.status()
                fail("expected 401")
            } catch (e: RelayException) {
                assertTrue(e.isUnauthorized)
                assertEquals("unauthorized", e.code)
            }
            server.takeRequest()

            api.markRead("msg_9")
            assertEquals("/v1/messages/msg_9/read", server.takeRequest().path)

            val ws = api.webSocketRequest(42)
            assertEquals("42", ws.url.queryParameter("after_seq"))
            assertEquals("Bearer jwt", ws.header("Authorization"))
        }
    }
}
