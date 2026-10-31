package dm.sent.voice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TelemetryTest {
    private val date = 1_793_397_600_000L // 2026-10-30T22:00:00Z

    @Test
    fun `flush posts a batch with the voice token and the SDK version`() = runTest {
        val http = FakeHttp(listOf(FakeHttp.ok("{}", 202)))
        val telemetry = Telemetry("https://api.example.test", "1.2.3", http, this)
        telemetry.useToken("voice.token")
        telemetry.record(TelemetryEventName.REGISTER_COMPLETED, payload = mapOf("attempt" to TelemetryValue.Count(1)), atMs = date)
        telemetry.record(TelemetryEventName.CALL_STARTED, callId = "call_1", payload = mapOf("direction" to TelemetryValue.Text("outbound")), atMs = date)
        telemetry.flush()

        val request = http.requests.single()
        assertEquals("https://api.example.test/v3/voice/telemetry", request.url)
        assertEquals("Bearer voice.token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        val body = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        assertEquals("1.2.3", body["sdk_version"]!!.jsonPrimitive.content)
        val events = body["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("register.completed", "call.started"), events.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("call_1", events[1]["call_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-30T22:00:00Z", events[0]["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("outbound", events[1]["payload"]!!.jsonObject["direction"]!!.jsonPrimitive.content)
        assertTrue(telemetry.pending.isEmpty())
        telemetry.destroy()
    }

    @Test
    fun `events before the first token wait for it`() = runTest {
        val http = FakeHttp(emptyList())
        val telemetry = Telemetry(http = http, scope = this)
        telemetry.record(TelemetryEventName.CLIENT_INFO, atMs = date)
        telemetry.flush()
        assertTrue(http.requests.isEmpty())
        assertEquals(1, telemetry.pending.size, "kept until a token arrives")
        telemetry.useToken("t")
        telemetry.flush()
        assertTrue(telemetry.pending.isEmpty())
        assertEquals(1, http.requests.size)
        telemetry.destroy()
    }

    @Test
    fun `a failed batch is retried once then dropped`() = runTest {
        val http = FakeHttp(listOf(FakeHttp.ok("", 500), FakeHttp.unreachable(), FakeHttp.ok("", 500)))
        val telemetry = Telemetry(http = http, scope = this)
        telemetry.useToken("t")
        telemetry.record(TelemetryEventName.CLIENT_ERROR, atMs = date)
        telemetry.flush()
        assertEquals(1, telemetry.pending.size, "kept for one retry")
        telemetry.flush()
        assertTrue(telemetry.pending.isEmpty(), "dropped after the retry failed")
        assertEquals(2, http.requests.size)
        telemetry.destroy()
    }

    @Test
    fun `long text is clipped and the queue is bounded`() = runTest {
        val telemetry = Telemetry(http = FakeHttp(emptyList()), scope = this)
        telemetry.record(TelemetryEventName.CLIENT_ERROR, payload = mapOf("message" to TelemetryValue.Text("x".repeat(900))), atMs = date)
        val payload = telemetry.pending[0].json()["payload"]!!.jsonObject
        assertEquals(Telemetry.MAX_TEXT_LENGTH, payload["message"]!!.jsonPrimitive.content.length)
        repeat(150) { telemetry.record(TelemetryEventName.CLIENT_INFO, atMs = date) }
        assertEquals(Telemetry.MAX_EVENTS, telemetry.pending.size)
        telemetry.destroy()
    }

    @Test
    fun `big backlogs split into batches under the body limit`() {
        val big = TelemetryEvent(TelemetryEventName.CLIENT_ERROR, occurredAtMs = date, payload = mapOf("provider_error" to TelemetryValue.Text("e".repeat(500))))
        val entries = List(200) { Telemetry.Entry(big, retried = false) }
        val batches = Telemetry.split(entries)
        assertTrue(batches.size > 1)
        for (batch in batches) assertTrue(Telemetry.body(batch, "0").toByteArray().size <= Telemetry.MAX_BODY_BYTES + 200)
        assertEquals(200, batches.flatten().size)
    }

    @Test
    fun `disabled telemetry records nothing`() = runTest {
        val http = FakeHttp(emptyList())
        val telemetry = Telemetry(http = http, scope = this, disabled = true)
        telemetry.useToken("t")
        telemetry.record(TelemetryEventName.CLIENT_INFO, atMs = date)
        telemetry.flush()
        assertTrue(http.requests.isEmpty())
    }
}
