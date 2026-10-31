package dm.sent.voice.core

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A voice token the app's backend minted, exchanged with Sent for the credential the client registers with. */
public data class VoiceToken(
    /** The voice token as the token provider returned it. */
    val token: String,
    /** The credential the provider adapter registers with. Never shown to the app. */
    val credential: String,
    /** The user the token names. */
    val identity: String,
    /** The Sent number the user is bound to, when the token carries one. */
    val number: String?,
    /** The prefix that qualifies user identities and conference names for the provider. */
    val prefix: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
) {
    val lifetimeMs: Long get() = expiresAtMs - issuedAtMs
}

/** Fetches a voice token from the app and exchanges it with Sent, with the retry timing the contract fixes. */
public object TokenExchange {
    public const val EXCHANGE_PATH: String = "/v3/voice/tokens/exchange"

    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun fetch(
        tokenProvider: suspend () -> String,
        baseUrl: String = SentApi.DEFAULT_BASE_URL,
        http: HttpClient,
    ): VoiceToken {
        val token = try {
            tokenProvider()
        } catch (e: Exception) {
            throw SentVoiceException(ErrorCode.NETWORK, "The token provider failed to fetch a voice token.", cause = e)
        }
        if (token.isEmpty()) throw SentVoiceException(ErrorCode.TOKEN_INVALID)

        // No body, so no Content-Type: the API refuses one on a request without a body.
        val request = HttpRequest("POST", SentApi.url(baseUrl, EXCHANGE_PATH), mapOf("Authorization" to "Bearer $token"))
        val response = try {
            http.send(request)
        } catch (e: Exception) {
            throw SentVoiceException(ErrorCode.NETWORK, "Sent could not be reached to exchange the voice token.", cause = e)
        }
        if (response.status == 401 || response.status == 403) throw SentVoiceException(ErrorCode.TOKEN_INVALID)
        if (!response.isSuccess) {
            throw SentVoiceException(ErrorCode.NETWORK, "Sent could not exchange the voice token (HTTP ${response.status}).")
        }
        return read(token, response.body.decodeToString())
    }

    /** Reads the exchange response: `{ "data": { credential, identity, number?, prefix, issued_at, expires_at } }`. */
    public fun read(token: String, body: String): VoiceToken {
        val data = try {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
        } catch (e: Exception) {
            null
        }
        fun text(name: String): String? = data?.get(name)?.let { element ->
            try { element.jsonPrimitive.content.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
        }
        val credential = text("credential")
        val identity = text("identity")
        val prefix = text("prefix")
        val issuedAt = text("issued_at")?.let(::parseInstant)
        val expiresAt = text("expires_at")?.let(::parseInstant)
        if (credential == null || identity == null || prefix == null || issuedAt == null || expiresAt == null || expiresAt <= issuedAt) {
            throw SentVoiceException(ErrorCode.TOKEN_INVALID, "Sent answered the token exchange with an unexpected body.")
        }
        return VoiceToken(token, credential, identity, text("number"), prefix, issuedAt, expiresAt)
    }

    /**
     * How long after the token was issued it is refreshed: the contract's share of its lifetime, at least the
     * margin before it expires, and never before half its lifetime.
     */
    public fun refreshDelayMs(token: VoiceToken): Long {
        val lifetime = token.lifetimeMs
        return max(min((lifetime * TokenTiming.REFRESH_SHARE).toLong(), lifetime - TokenTiming.REFRESH_MARGIN_MS), lifetime / 2)
    }

    /** The wait before retry number [retry] (0-based) of a failed fetch, doubling up to the maximum, with jitter. */
    public fun retryDelayMs(retry: Int, random: () -> Double = { Random.nextDouble() }): Long =
        (min(TokenTiming.INITIAL_RETRY_DELAY_MS * 2.0.pow(retry), TokenTiming.MAX_RETRY_DELAY_MS.toDouble()) * jitter(random)).toLong()

    /** The wait between fetch attempts while the client is offline, with jitter. */
    public fun offlineRetryDelayMs(random: () -> Double = { Random.nextDouble() }): Long =
        (TokenTiming.OFFLINE_RETRY_INTERVAL_MS * jitter(random)).toLong()

    internal fun jitter(random: () -> Double): Double = 1 - random() * 0.25

    internal fun parseInstant(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}
