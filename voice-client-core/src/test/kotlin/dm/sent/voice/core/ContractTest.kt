package dm.sent.voice.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContractTest {
    @Test
    fun `every state has a transition list`() {
        for (state in CallState.entries) assertNotNull(Contract.callTransitions[state], "$state is missing from the transitions")
    }

    @Test
    fun `terminal states stay put`() {
        for (state in listOf(CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER)) {
            assertTrue(state.isTerminal)
            for (next in CallState.entries) assertFalse(state.canTransitionTo(next))
        }
    }

    @Test
    fun `outbound starts initiated and inbound ringing`() {
        assertEquals(CallState.INITIATED, CallState.initial(CallDirection.OUTBOUND))
        assertEquals(CallState.RINGING, CallState.initial(CallDirection.INBOUND))
    }

    @Test
    fun `the state machine refuses what the contract does not allow`() {
        val machine = CallStateMachine(CallDirection.OUTBOUND)
        assertTrue(machine.transitionTo(CallState.RINGING))
        assertFalse(machine.transitionTo(CallState.INITIATED), "a call never goes back to initiated")
        assertTrue(machine.transitionTo(CallState.CONNECTED))
        assertTrue(machine.transitionTo(CallState.RECONNECTING))
        assertTrue(machine.transitionTo(CallState.CONNECTED))
        assertTrue(machine.transitionTo(CallState.COMPLETED))
        assertFalse(machine.transitionTo(CallState.CONNECTED))
        assertEquals(CallState.COMPLETED, machine.state)
    }

    @Test
    fun `every error class matches its code`() {
        assertEquals(8, Contract.errorClasses.size)
        for (spec in Contract.errorClasses) {
            val error = SentVoiceException(spec.code)
            assertEquals(spec.category, error.category, spec.name)
            assertEquals(spec.retriable, error.retriable, spec.name)
            assertFalse(error.message.isNullOrEmpty(), spec.name)
        }
    }

    @Test
    fun `codes without a class still have a category and message`() {
        val classCodes = Contract.errorClasses.map { it.code }.toSet()
        for (code in ErrorCode.entries.filter { it !in classCodes }) {
            val error = SentVoiceException(code)
            assertFalse(error.retriable, code.wire)
            assertFalse(error.message!!.startsWith("Voice error"), "${code.wire} has no written message")
        }
        assertEquals(ErrorCategory.VALIDATION, SentVoiceException(ErrorCode.INVALID_ADDRESS).category)
        assertEquals(ErrorCategory.VALIDATION, SentVoiceException(ErrorCode.CALL_IN_PROGRESS).category)
    }

    @Test
    fun `wire names round-trip`() {
        assertEquals(TelemetryEventName.REGISTER_COMPLETED, TelemetryEventName.fromWire("register.completed"))
        assertEquals("noAnswer", CallState.NO_ANSWER.wire)
        assertEquals(ErrorCode.TOKEN_EXPIRED, ErrorCode.fromWire("TOKEN_EXPIRED"))
    }

    @Test
    fun `token timing matches the contract file`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "contract/voice-client.json") }.first { it.exists() }
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val token = json["token"]!!.jsonObject
        assertEquals(token["refreshShare"]!!.jsonPrimitive.double, TokenTiming.REFRESH_SHARE)
        assertEquals(token["refreshMarginMs"]!!.jsonPrimitive.double.toLong(), TokenTiming.REFRESH_MARGIN_MS)
        assertEquals(token["maxRetryDelayMs"]!!.jsonPrimitive.double.toLong(), TokenTiming.MAX_RETRY_DELAY_MS)
        assertEquals(json["version"]!!.jsonPrimitive.content, Contract.VERSION)
    }
}
