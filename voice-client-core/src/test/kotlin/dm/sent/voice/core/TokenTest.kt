package dm.sent.voice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TokenTest {
    @Test
    fun `the exchange posts the voice token as bearer with no body`() = runTest {
        val http = FakeHttp(listOf(FakeHttp.ok(EXCHANGE_BODY)))
        val token = TokenExchange.fetch({ "voice.token" }, "https://api.example.test/", http)
        val request = http.requests.single()
        assertEquals("https://api.example.test/v3/voice/tokens/exchange", request.url)
        assertEquals("POST", request.method)
        assertEquals("Bearer voice.token", request.headers["Authorization"])
        assertNull(request.headers["Content-Type"])
        assertNull(request.body)
        assertEquals("eyJ.credential", token.credential)
        assertEquals("alice", token.identity)
        assertEquals("+15555550100", token.number)
        assertEquals("acme_", token.prefix)
        assertEquals(3_600_000L, token.lifetimeMs)
    }

    @Test
    fun `a failing provider is a network error`() = runTest {
        val http = FakeHttp(emptyList())
        expectCode(ErrorCode.NETWORK) { TokenExchange.fetch({ throw Unreachable() }, http = http) }
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `an empty token is invalid`() = runTest {
        expectCode(ErrorCode.TOKEN_INVALID) { TokenExchange.fetch({ "" }, http = FakeHttp(emptyList())) }
    }

    @Test
    fun `unauthorized is invalid and other failures are network`() = runTest {
        expectCode(ErrorCode.TOKEN_INVALID) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.ok("{}", 401)))) }
        expectCode(ErrorCode.NETWORK) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.ok("{}", 503)))) }
        expectCode(ErrorCode.NETWORK) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.unreachable()))) }
    }

    @Test
    fun `an unexpected body is invalid`() = runTest {
        expectCode(ErrorCode.TOKEN_INVALID) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.ok("""{"data":{"credential":"c"}}""")))) }
        val swapped = EXCHANGE_BODY.replace("11:00:00", "09:00:00")
        expectCode(ErrorCode.TOKEN_INVALID) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.ok(swapped)))) }
        expectCode(ErrorCode.TOKEN_INVALID) { TokenExchange.fetch({ "t" }, http = FakeHttp(listOf(FakeHttp.ok("not json")))) }
    }

    @Test
    fun `the refresh delay follows the contract`() {
        fun token(lifetimeMs: Long) = VoiceToken("t", "c", "i", null, "p", issuedAtMs = 0, expiresAtMs = lifetimeMs)
        // An hour: 80% of it, well clear of the 30 s margin.
        assertEquals(2_880_000L, TokenExchange.refreshDelayMs(token(3_600_000)))
        // Two minutes: the margin wins, at least half the lifetime.
        assertEquals(90_000L, TokenExchange.refreshDelayMs(token(120_000)))
        // Forty seconds: never before half its lifetime.
        assertEquals(20_000L, TokenExchange.refreshDelayMs(token(40_000)))
    }

    @Test
    fun `the retry delay doubles to the maximum with jitter`() {
        assertEquals(500L, TokenExchange.retryDelayMs(0) { 0.0 })
        assertEquals(4_000L, TokenExchange.retryDelayMs(3) { 0.0 })
        assertEquals(8_000L, TokenExchange.retryDelayMs(10) { 0.0 })
        assertEquals(6_000L, TokenExchange.retryDelayMs(10) { 1.0 })
        assertEquals(30_000L, TokenExchange.offlineRetryDelayMs { 0.0 })
    }

    @Test
    fun `dates with and without fractions parse`() {
        assertNotNull(TokenExchange.parseInstant("2026-10-31T10:00:00Z"))
        assertNotNull(TokenExchange.parseInstant("2026-10-31T10:00:00.123Z"))
        assertNull(TokenExchange.parseInstant("yesterday"))
    }
}
