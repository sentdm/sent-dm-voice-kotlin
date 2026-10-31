package dm.sent.voice.core

/** Where a call goes. */
public sealed class CallTarget {
    /** A phone number in E.164 format. */
    public data class Number(val number: String) : CallTarget()
    /** A user of your app, as the provider knows them (the identity, qualified by the token's prefix). */
    public data class User(val id: String) : CallTarget()
    /** A conference room, as the provider knows it. */
    public data class Conference(val room: String) : CallTarget()
}

/** Who is on the other end of a call, as the app sees it. */
public sealed class Address {
    public data class Number(val number: String) : Address()
    public data class User(val identity: String) : Address()
    public data class Conference(val name: String) : Address()
}

/** A snapshot of a call's media quality. */
public data class CallStats(val rttMs: Double? = null, val jitterMs: Double? = null, val packetLoss: Double? = null)

/** A warning the provider raised about a call's media, or cleared. */
public data class QualityWarning(val metric: String, val cleared: Boolean)

/** What the provider reports about a call, in the contract's words. */
public sealed class AdapterCallEvent {
    public object Ringing : AdapterCallEvent()
    public object Answered : AdapterCallEvent()
    public object Connected : AdapterCallEvent()
    public object Reconnecting : AdapterCallEvent()
    public object Reconnected : AdapterCallEvent()
    /** The call ended in [state] (completed, failed, busy or noAnswer), with the error that ended it when there is one. */
    public data class Disconnected(val state: CallState, val error: SentVoiceException? = null) : AdapterCallEvent()
    public data class MuteChanged(val muted: Boolean) : AdapterCallEvent()
    public data class Quality(val warning: QualityWarning) : AdapterCallEvent()
    public data class Error(val error: SentVoiceException) : AdapterCallEvent()
}

/** The provider's SDK behind the client. The core drives it and never sees the provider's types. */
public interface ProviderAdapter {
    /**
     * Registers with the credential Sent issued, or re-registers with a fresh one. [number] is the Sent number
     * the user is bound to, which the provider shows as the caller id on calls to phones.
     */
    public suspend fun register(credential: String, number: String?)
    public suspend fun unregister()
    /** Places a call and returns the provider's id for it. */
    public suspend fun call(target: CallTarget): String
    public suspend fun hangup(callId: String)
    public fun mute(callId: String, muted: Boolean)
    public fun sendDigits(callId: String, digits: String)
    public suspend fun stats(callId: String): CallStats
    /** The core sets this once; the adapter calls it for every event of every call, on any thread. */
    public fun setCallEventHandler(handler: (callId: String, event: AdapterCallEvent) -> Unit)
}
