package dm.sent.voice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ClientTest {
    @Test
    fun `register exchanges the token and registers the credential`() = runTest {
        val adapter = FakeAdapter()
        val http = FakeHttp(listOf(FakeHttp.ok(EXCHANGE_BODY)))
        val client = testClient(adapter, http, scope = backgroundScope)
        val events = mutableListOf<ClientEvent>()
        client.on { events.add(it) }
        assertEquals(ClientState.UNREGISTERED, client.state)

        client.register()

        assertEquals(ClientState.REGISTERED, client.state)
        assertEquals("alice", client.identity)
        assertEquals("+15555550100", client.number)
        assertEquals(listOf("eyJ.credential"), adapter.registered)
        assertEquals(1, http.requests.size)
        assertEquals(listOf<ClientEvent>(ClientEvent.Registering, ClientEvent.Registered), events)

        client.register()
        assertEquals(1, http.requests.size, "already registered: no second exchange")
        client.destroy()
    }

    @Test
    fun `register fails with the token error and stays unregistered`() = runTest {
        val client = testClient(http = FakeHttp(listOf(FakeHttp.ok("{}", 401))), scope = backgroundScope)
        val events = mutableListOf<ClientEvent>()
        client.on { events.add(it) }
        expectCode(ErrorCode.TOKEN_INVALID) { client.register() }
        assertEquals(ClientState.UNREGISTERED, client.state)
        assertEquals(2, events.size)
        val error = assertIs<ClientEvent.Error>(events[1])
        assertEquals(ErrorCode.TOKEN_INVALID, error.error.code)
        client.destroy()
    }

    @Test
    fun `register retries a retriable failure`() = runTest {
        val http = FakeHttp(listOf(FakeHttp.unreachable(), FakeHttp.ok(EXCHANGE_BODY)))
        val client = testClient(http = http, registerRetries = 1, scope = backgroundScope)
        client.register()
        assertEquals(ClientState.REGISTERED, client.state)
        assertEquals(2, http.requests.size)
        client.destroy()
    }

    @Test
    fun `connect needs a registered client`() = runTest {
        val client = testClient(scope = backgroundScope)
        expectCode(ErrorCode.NOT_REGISTERED) { client.connect("+14155551234") }
    }

    @Test
    fun `connect validates the address`() = runTest {
        val adapter = FakeAdapter()
        val client = testClient(adapter, scope = backgroundScope)
        client.register()
        expectCode(ErrorCode.INVALID_ADDRESS) { client.connect("415 555 1234") }
        expectCode(ErrorCode.INVALID_ADDRESS) { client.connect("bob with spaces") }
        expectCode(ErrorCode.INVALID_ADDRESS) { client.joinConference("no/slashes") }
        assertTrue(adapter.calls.isEmpty())
        client.destroy()
    }

    @Test
    fun `connect places a number call and follows the provider`() = runTest {
        val adapter = FakeAdapter()
        val client = testClient(adapter, scope = backgroundScope)
        client.register()
        val clientEvents = mutableListOf<ClientEvent>()
        client.on { clientEvents.add(it) }

        val call = client.connect("+14155551234")
        assertEquals(listOf<CallTarget>(CallTarget.Number("+14155551234")), adapter.calls)
        assertEquals("call_1", call.id)
        assertEquals(CallState.INITIATED, call.state)
        assertEquals(CallDirection.OUTBOUND, call.direction)
        assertEquals(Address.Number("+14155551234"), call.to)
        assertEquals(Address.User("alice"), call.from)
        assertEquals("call_1", client.activeCall?.id)
        assertEquals(listOf<ClientEvent>(ClientEvent.ActiveCallChanged(call)), clientEvents)

        val callEvents = mutableListOf<CallEvent>()
        call.on { callEvents.add(it) }
        adapter.report("call_1", AdapterCallEvent.Ringing)
        adapter.report("call_1", AdapterCallEvent.Answered)
        adapter.report("call_1", AdapterCallEvent.Connected)
        assertEquals(CallState.CONNECTED, call.state)
        assertNotNull(call.connectedTimeMs)

        call.mute(true)
        assertTrue(call.isMuted)
        call.sendDigits("1#")
        assertEquals(listOf("1#"), adapter.digits.map { it.second })
        expectCode(ErrorCode.INVALID_ADDRESS) { call.sendDigits("hello") }

        call.hangup()
        assertEquals(listOf("call_1"), adapter.hangups)
        assertEquals(CallState.COMPLETED, call.state)
        assertNull(client.activeCall)
        assertEquals(
            listOf<CallEvent>(CallEvent.Ringing, CallEvent.Answered, CallEvent.Connected, CallEvent.MuteChanged(true), CallEvent.Disconnected(CallState.COMPLETED)),
            callEvents,
        )
        assertEquals(ClientEvent.ActiveCallChanged(null), clientEvents.last())
        client.destroy()
    }

    @Test
    fun `user and conference targets carry the token prefix`() = runTest {
        val adapter = FakeAdapter()
        val client = testClient(adapter, scope = backgroundScope)
        client.register()
        val first = client.connect("bob-1")
        assertEquals(CallTarget.User("acme_bob-1"), adapter.calls.last())
        assertEquals(Address.User("bob-1"), first.to)
        adapter.report(first.id, AdapterCallEvent.Disconnected(CallState.FAILED, SentVoiceException(ErrorCode.CALL_FAILED)))
        assertEquals(CallState.FAILED, first.state)
        assertEquals(ErrorCode.CALL_FAILED, first.error?.code)

        val room = client.joinConference("standup")
        assertEquals(CallTarget.Conference("acme_standup"), adapter.calls.last())
        assertEquals(Address.Conference("standup"), room.to)
        client.destroy()
    }

    @Test
    fun `one call at a time`() = runTest {
        val client = testClient(scope = backgroundScope)
        client.register()
        client.connect("+14155551234")
        expectCode(ErrorCode.CALL_IN_PROGRESS) { client.connect("+14155550000") }
        client.destroy()
    }

    @Test
    fun `an event the contract refuses is ignored`() = runTest {
        val adapter = FakeAdapter()
        val client = testClient(adapter, scope = backgroundScope)
        client.register()
        val call = client.connect("+14155551234")
        val events = mutableListOf<CallEvent>()
        call.on { events.add(it) }
        adapter.report("call_1", AdapterCallEvent.Connected)
        adapter.report("call_1", AdapterCallEvent.Ringing)
        adapter.report("somebody-else", AdapterCallEvent.Disconnected(CallState.COMPLETED))
        assertEquals(CallState.CONNECTED, call.state)
        assertEquals(listOf<CallEvent>(CallEvent.Connected), events)
        assertEquals("call_1", client.activeCall?.id)
        client.destroy()
    }

    @Test
    fun `unregister hangs up and tells the provider`() = runTest {
        val adapter = FakeAdapter()
        val client = testClient(adapter, scope = backgroundScope)
        val events = mutableListOf<ClientEvent>()
        client.on { events.add(it) }
        client.register()
        client.connect("+14155551234")
        client.unregister()
        assertEquals(listOf("call_1"), adapter.hangups)
        assertEquals(1, adapter.unregisterCalls)
        assertEquals(ClientState.UNREGISTERED, client.state)
        assertNull(client.identity)
        assertEquals(ClientEvent.Unregistered, events.last())
        client.destroy()
        assertEquals(ClientState.DESTROYED, client.state)
        expectCode(ErrorCode.NOT_REGISTERED) { client.register() }
    }

    @Test
    fun `quality averages`() {
        val payload = SentVoiceClient.average(listOf(CallStats(rttMs = 10.0, packetLoss = 0.2), CallStats(rttMs = 30.0, jitterMs = 4.0)))
        assertEquals(TelemetryValue.Count(2), payload["samples"])
        assertEquals(TelemetryValue.Decimal(20.0), payload["rtt_ms"])
        assertEquals(TelemetryValue.Decimal(4.0), payload["jitter_ms"])
        assertEquals(TelemetryValue.Decimal(0.2), payload["packet_loss"])
    }

    @Test
    fun validation() {
        assertTrue(Validation.isE164("+14155551234"))
        assertFalse(Validation.isE164("+0123"))
        assertFalse(Validation.isE164("14155551234"))
        assertTrue(Validation.isName("alice_01-x"))
        assertFalse(Validation.isName(""))
        assertFalse(Validation.isName("a".repeat(201)))
    }
}
