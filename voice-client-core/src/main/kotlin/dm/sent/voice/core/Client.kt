package dm.sent.voice.core

import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the client is set up. */
public class SentVoiceOptions(
    /** Returns a voice token from your backend, which mints it with `POST /v3/voice/tokens`. */
    public val tokenProvider: suspend () -> String,
    /** The Sent API origin the token is exchanged with and telemetry goes to. */
    public val baseUrl: String = SentApi.DEFAULT_BASE_URL,
    /** How many times registering or refreshing the voice token is retried before the client gives up. */
    public val registerRetries: Int = 3,
    public val telemetryDisabled: Boolean = false,
    /** Where the client's own log lines go, if anywhere. */
    public val log: ((LogLevel, String) -> Unit)? = null,
)

public enum class LogLevel { ERROR, WARN, INFO, DEBUG }

/** What the client reports as it goes. */
public sealed class ClientEvent {
    public object Registering : ClientEvent()
    public object Registered : ClientEvent()
    public object Unregistered : ClientEvent()
    /** The call in progress changed: a placed call, or null once it ended. */
    public data class ActiveCallChanged(val call: Call?) : ClientEvent()
    /** The client lost its registration and could not get it back; register again when the cause is fixed. */
    public data class Offline(val error: SentVoiceException) : ClientEvent()
    /** The voice token is about to expire and the next refresh is due. */
    public data class TokenWillExpire(val expiresAtMs: Long) : ClientEvent()
    public data class Error(val error: SentVoiceException) : ClientEvent()
}

/**
 * The Sent Voice Client: registers the user with a voice token and places calls.
 *
 * One call at a time. Events reach listeners in order, on whatever thread reported them; hop to the main
 * thread before touching UI.
 */
public class SentVoiceClient(
    private val options: SentVoiceOptions,
    private val adapter: ProviderAdapter,
    private val http: HttpClient = UrlConnectionHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val telemetry = Telemetry(options.baseUrl, http = http, scope = scope, disabled = options.telemetryDisabled)
    private val lock = Any()
    private var clientState = ClientState.UNREGISTERED
    private var token: VoiceToken? = null
    private var active: Call? = null
    private val listeners = LinkedHashMap<Int, (ClientEvent) -> Unit>()
    private var nextListener = 0
    private var refreshJob: Job? = null
    private var qualityJob: Job? = null
    private var registering: CompletableDeferred<Unit>? = null

    init {
        adapter.setCallEventHandler { callId, event -> handle(callId, event) }
    }

    public val state: ClientState get() = synchronized(lock) { clientState }
    /** The user the voice token names, once registered. */
    public val identity: String? get() = synchronized(lock) { token?.identity }
    /** The Sent number the user is bound to, once registered and when the token carries one. */
    public val number: String? get() = synchronized(lock) { token?.number }
    /** The call in progress, if any. */
    public val activeCall: Call? get() = synchronized(lock) { active }

    /** Listens to the client's events until the token is cancelled. */
    public fun on(listener: (ClientEvent) -> Unit): ListenerToken {
        val id = synchronized(lock) { nextListener++.also { listeners[it] = listener } }
        return ListenerToken { synchronized(lock) { listeners.remove(id) } }
    }

    /**
     * Fetches a voice token, registers with it and keeps it refreshed. Safe to call again: a second call
     * while registering waits for the first; once registered it returns at once.
     */
    public suspend fun register() {
        val (state, running) = synchronized(lock) { clientState to registering }
        if (state == ClientState.DESTROYED) throw SentVoiceException(ErrorCode.NOT_REGISTERED, "The client was destroyed.")
        if (state == ClientState.REGISTERED) return
        if (running != null) {
            running.await()
            return
        }
        // Registration runs in the caller's coroutine; later callers wait on this and get its outcome.
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { registering = done }
        try {
            doRegister()
            done.complete(Unit)
        } catch (error: Throwable) {
            done.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) { registering = null }
        }
    }

    private suspend fun doRegister() {
        setState(ClientState.REGISTERING, ClientEvent.Registering)
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                val fresh = TokenExchange.fetch(options.tokenProvider, options.baseUrl, http)
                adapter.register(fresh.credential, fresh.number)
                synchronized(lock) { token = fresh }
                telemetry.useToken(fresh.token)
                telemetry.record(TelemetryEventName.REGISTER_COMPLETED, payload = mapOf("attempt" to TelemetryValue.Count(attempt.toLong())), atMs = now())
                telemetry.record(TelemetryEventName.CLIENT_INFO, payload = Platform.info(), atMs = now())
                setState(ClientState.REGISTERED, ClientEvent.Registered)
                scheduleRefresh(fresh)
                return
            } catch (error: SentVoiceException) {
                telemetry.record(TelemetryEventName.REGISTER_FAILED, payload = error.telemetryPayload() + ("attempt" to TelemetryValue.Count(attempt.toLong())), atMs = now())
                if (!error.retriable || attempt > options.registerRetries) {
                    setState(ClientState.UNREGISTERED, ClientEvent.Error(error))
                    throw error
                }
                log(LogLevel.WARN, "Registering failed (attempt $attempt): ${error.message}")
                delay(TokenExchange.retryDelayMs(attempt - 1))
            } catch (error: Exception) {
                val wrapped = SentVoiceException(ErrorCode.UNKNOWN, "Registering failed: $error", providerDetail = error.toString(), cause = error)
                setState(ClientState.UNREGISTERED, ClientEvent.Error(wrapped))
                throw wrapped
            }
        }
    }

    /** Ends the active call, unregisters from the provider and stops refreshing the token. */
    public suspend fun unregister() {
        val (state, call) = synchronized(lock) { clientState to active }
        if (state != ClientState.REGISTERED && state != ClientState.OFFLINE) return
        if (call != null) runCatching { call.hangup() }
        synchronized(lock) { refreshJob?.cancel(); refreshJob = null }
        adapter.unregister()
        synchronized(lock) { token = null }
        telemetry.record(TelemetryEventName.UNREGISTER_COMPLETED, atMs = now())
        setState(ClientState.UNREGISTERED, ClientEvent.Unregistered)
    }

    /** Calls a phone number in E.164 format or another user of your app, by identity. */
    public suspend fun connect(to: String): Call {
        if (Validation.isE164(to)) return place(Address.Number(to)) { CallTarget.Number(to) }
        if (!Validation.isName(to)) {
            throw SentVoiceException(ErrorCode.INVALID_ADDRESS, "'to' must be a phone number in E.164 format (for example +14155551234) or a user identity.")
        }
        return place(Address.User(to)) { prefix -> CallTarget.User(prefix + to) }
    }

    /** Joins a conference by name; everyone who joins the same name is in the same room. */
    public suspend fun joinConference(name: String): Call {
        if (!Validation.isName(name)) {
            throw SentVoiceException(ErrorCode.INVALID_ADDRESS, "A conference name is letters, digits, - and _, up to 200 characters.")
        }
        return place(Address.Conference(name)) { prefix -> CallTarget.Conference(prefix + name) }
    }

    /** Unregisters and releases the client; it cannot be used afterwards. */
    public suspend fun destroy() {
        runCatching { unregister() }
        synchronized(lock) {
            refreshJob?.cancel(); refreshJob = null
            qualityJob?.cancel(); qualityJob = null
        }
        telemetry.destroy()
        setState(ClientState.DESTROYED, null)
        synchronized(lock) { listeners.clear() }
    }

    private suspend fun place(address: Address, target: (String) -> CallTarget): Call {
        val (state, current, inProgress) = synchronized(lock) { Triple(clientState, token, active) }
        if (state != ClientState.REGISTERED || current == null) throw SentVoiceException(ErrorCode.NOT_REGISTERED)
        if (inProgress != null) throw SentVoiceException(ErrorCode.CALL_IN_PROGRESS)
        val callId = adapter.call(target(current.prefix))
        val call = Call(
            id = callId,
            direction = CallDirection.OUTBOUND,
            from = Address.User(current.identity),
            to = address,
            startedAtMs = now(),
            actions = Call.Actions(
                hangup = { adapter.hangup(callId) },
                mute = { muted -> adapter.mute(callId, muted) },
                sendDigits = { digits -> adapter.sendDigits(callId, digits) },
                stats = { adapter.stats(callId) },
            ),
        )
        synchronized(lock) { active = call }
        telemetry.record(TelemetryEventName.CALL_STARTED, callId = callId, payload = mapOf("direction" to TelemetryValue.Text("outbound")), atMs = now())
        emit(ClientEvent.ActiveCallChanged(call))
        return call
    }

    private fun handle(callId: String, event: AdapterCallEvent) {
        val call = synchronized(lock) { active } ?: return
        if (call.id != callId) return
        when (val emitted = call.apply(event, now()) ?: return) {
            CallEvent.Connected -> {
                telemetry.record(TelemetryEventName.CALL_CONNECTED, callId = callId, payload = mapOf("duration_ms" to TelemetryValue.Count(now() - call.startedAtMs)), atMs = now())
                startQualitySampling(call)
            }
            is CallEvent.Disconnected -> {
                synchronized(lock) {
                    if (active?.id == callId) active = null
                    qualityJob?.cancel(); qualityJob = null
                }
                val payload = mutableMapOf<String, TelemetryValue>(
                    "outcome" to TelemetryValue.Text(emitted.state.wire),
                    "duration_ms" to TelemetryValue.Count(now() - call.startedAtMs),
                )
                emitted.error?.let { payload.putAll(it.telemetryPayload()) }
                telemetry.record(TelemetryEventName.CALL_ENDED, callId = callId, payload = payload, atMs = now())
                emit(ClientEvent.ActiveCallChanged(null))
            }
            is CallEvent.Error -> telemetry.record(TelemetryEventName.CLIENT_ERROR, callId = callId, payload = emitted.error.telemetryPayload(), atMs = now())
            else -> Unit
        }
    }

    private fun startQualitySampling(call: Call) {
        synchronized(lock) {
            qualityJob?.cancel()
            qualityJob = scope.launch {
                val samples = mutableListOf<CallStats>()
                while (isActive) {
                    delay(10_000)
                    if (call.state.isTerminal) return@launch
                    runCatching { call.stats() }.getOrNull()?.let { samples.add(it) }
                    if (samples.size >= 6) {
                        telemetry.record(TelemetryEventName.CALL_QUALITY, callId = call.id, payload = average(samples), atMs = now())
                        samples.clear()
                    }
                }
            }
        }
    }

    private fun scheduleRefresh(current: VoiceToken) {
        synchronized(lock) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(max(0L, TokenExchange.refreshDelayMs(current)))
                emit(ClientEvent.TokenWillExpire(current.expiresAtMs))
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        var attempt = 0
        while (scope.isActive) {
            attempt += 1
            try {
                val fresh = TokenExchange.fetch(options.tokenProvider, options.baseUrl, http)
                adapter.register(fresh.credential, fresh.number)
                synchronized(lock) { token = fresh }
                telemetry.useToken(fresh.token)
                if (synchronized(lock) { clientState } == ClientState.OFFLINE) setState(ClientState.REGISTERED, ClientEvent.Registered)
                scheduleRefresh(fresh)
                return
            } catch (error: SentVoiceException) {
                if (error.retriable && attempt <= options.registerRetries) {
                    log(LogLevel.WARN, "Refreshing the voice token failed (attempt $attempt): ${error.message}")
                    delay(TokenExchange.retryDelayMs(attempt - 1))
                } else {
                    setState(ClientState.OFFLINE, ClientEvent.Offline(SentVoiceException(ErrorCode.TOKEN_EXPIRED, error.message, error.providerDetail, error)))
                    return
                }
            } catch (error: Exception) {
                setState(ClientState.OFFLINE, ClientEvent.Offline(SentVoiceException(ErrorCode.TOKEN_EXPIRED, cause = error)))
                return
            }
        }
    }

    private fun setState(state: ClientState, event: ClientEvent?) {
        synchronized(lock) { clientState = state }
        if (event != null) emit(event)
    }

    private fun emit(event: ClientEvent) {
        val current = synchronized(lock) { listeners.values.toList() }
        current.forEach { it(event) }
    }

    private fun log(level: LogLevel, message: String) {
        options.log?.invoke(level, message)
    }

    internal companion object {
        internal fun average(samples: List<CallStats>): Map<String, TelemetryValue> {
            fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size
            val payload = mutableMapOf<String, TelemetryValue>("samples" to TelemetryValue.Count(samples.size.toLong()))
            mean(samples.mapNotNull { it.rttMs })?.let { payload["rtt_ms"] = TelemetryValue.Decimal(it) }
            mean(samples.mapNotNull { it.jitterMs })?.let { payload["jitter_ms"] = TelemetryValue.Decimal(it) }
            mean(samples.mapNotNull { it.packetLoss })?.let { payload["packet_loss"] = TelemetryValue.Decimal(it) }
            return payload
        }
    }
}

/** What the client checks before it calls the provider. */
public object Validation {
    private val e164 = Regex("^\\+[1-9][0-9]{1,14}$")
    private val name = Regex("^[A-Za-z0-9_-]{1,200}$")

    public fun isE164(value: String): Boolean = e164.matches(value)
    public fun isName(value: String): Boolean = name.matches(value)
}

/** What the client says about the platform it runs on. */
internal object Platform {
    fun info(): Map<String, TelemetryValue> {
        val android = runCatching { Class.forName("android.os.Build\$VERSION") }.isSuccess
        return if (android) {
            val release = runCatching { Class.forName("android.os.Build\$VERSION").getField("RELEASE").get(null)?.toString() }.getOrNull()
            mapOf(
                "os" to TelemetryValue.Text("Android"),
                "os_version" to TelemetryValue.Text(release ?: "unknown"),
                "device_type" to TelemetryValue.Text("mobile"),
            )
        } else {
            mapOf(
                "os" to TelemetryValue.Text(System.getProperty("os.name") ?: "unknown"),
                "os_version" to TelemetryValue.Text(System.getProperty("os.version") ?: "unknown"),
                "device_type" to TelemetryValue.Text("desktop"),
            )
        }
    }
}

/** The error as a telemetry payload. */
internal fun SentVoiceException.telemetryPayload(): Map<String, TelemetryValue> {
    val payload = mutableMapOf<String, TelemetryValue>(
        "code" to TelemetryValue.Text(code.wire),
        "category" to TelemetryValue.Text(category.wire),
        "message" to TelemetryValue.Text(message ?: ""),
        "retriable" to TelemetryValue.Flag(retriable),
    )
    providerDetail?.let { payload["provider_error"] = TelemetryValue.Text(it) }
    return payload
}
