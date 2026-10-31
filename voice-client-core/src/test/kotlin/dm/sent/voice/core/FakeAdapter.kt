package dm.sent.voice.core

/** A provider adapter that records what the client asked and lets a test play the provider's part. */
internal class FakeAdapter : ProviderAdapter {
    val registered = mutableListOf<String>()
    var unregisterCalls = 0
    val calls = mutableListOf<CallTarget>()
    val hangups = mutableListOf<String>()
    val mutes = mutableListOf<Pair<String, Boolean>>()
    val digits = mutableListOf<Pair<String, String>>()
    var statsToReturn = CallStats(rttMs = 40.0, jitterMs = 5.0, packetLoss = 0.1)
    var registerError: Exception? = null
    var callError: Exception? = null
    private var handler: ((String, AdapterCallEvent) -> Unit)? = null
    private var nextCallNumber = 0

    override suspend fun register(credential: String) {
        registerError?.let { throw it }
        registered.add(credential)
    }

    override suspend fun unregister() {
        unregisterCalls += 1
    }

    override suspend fun call(target: CallTarget): String {
        callError?.let { throw it }
        calls.add(target)
        nextCallNumber += 1
        return "call_$nextCallNumber"
    }

    override suspend fun hangup(callId: String) {
        hangups.add(callId)
        report(callId, AdapterCallEvent.Disconnected(CallState.COMPLETED))
    }

    override fun mute(callId: String, muted: Boolean) {
        mutes.add(callId to muted)
        report(callId, AdapterCallEvent.MuteChanged(muted))
    }

    override fun sendDigits(callId: String, digits: String) {
        this.digits.add(callId to digits)
    }

    override suspend fun stats(callId: String): CallStats = statsToReturn

    override fun setCallEventHandler(handler: (callId: String, event: AdapterCallEvent) -> Unit) {
        this.handler = handler
    }

    /** Plays the provider: reports an event for a call. */
    fun report(callId: String, event: AdapterCallEvent) {
        handler?.invoke(callId, event)
    }
}

internal fun testClient(
    adapter: FakeAdapter = FakeAdapter(),
    http: FakeHttp = FakeHttp(listOf(FakeHttp.ok(EXCHANGE_BODY))),
    tokenProvider: suspend () -> String = { "voice.token" },
    registerRetries: Int = 0,
    scope: kotlinx.coroutines.CoroutineScope,
): SentVoiceClient {
    val options = SentVoiceOptions(tokenProvider, baseUrl = "https://api.example.test", registerRetries = registerRetries, telemetryDisabled = true)
    return SentVoiceClient(options, adapter, http, scope)
}
