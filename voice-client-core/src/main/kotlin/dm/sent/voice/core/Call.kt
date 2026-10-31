package dm.sent.voice.core

/** What a call reports as it goes. */
public sealed class CallEvent {
    public object Ringing : CallEvent()
    public object Answered : CallEvent()
    public object Connected : CallEvent()
    public object Reconnecting : CallEvent()
    public object Reconnected : CallEvent()
    /** The call ended in [state], with the error that ended it when there is one. */
    public data class Disconnected(val state: CallState, val error: SentVoiceException? = null) : CallEvent()
    public data class MuteChanged(val muted: Boolean) : CallEvent()
    public data class Quality(val warning: QualityWarning) : CallEvent()
    public data class Error(val error: SentVoiceException) : CallEvent()
}

/** Cancels a listener. */
public class ListenerToken internal constructor(private var onCancel: (() -> Unit)?) {
    public fun cancel() {
        val action = synchronized(this) { onCancel.also { onCancel = null } }
        action?.invoke()
    }
}

/** A call the client placed. Events arrive on the listeners in the order the provider reported them. */
public class Call internal constructor(
    /** The provider's id for the call, which Sent's call records carry too. */
    public val id: String,
    public val direction: CallDirection,
    public val from: Address,
    public val to: Address,
    public val startedAtMs: Long,
    private val actions: Actions,
) {
    /** What the client lets a call do. */
    internal class Actions(
        val hangup: suspend () -> Unit,
        val mute: (Boolean) -> Unit,
        val sendDigits: (String) -> Unit,
        val stats: suspend () -> CallStats,
    )

    private val lock = Any()
    private val machine = CallStateMachine(direction)
    private val listeners = LinkedHashMap<Int, (CallEvent) -> Unit>()
    private var nextListener = 0
    private var muted = false
    private var connectedAtMs: Long? = null
    private var endedAtMs: Long? = null
    private var endedWith: SentVoiceException? = null

    public val state: CallState get() = synchronized(lock) { machine.state }
    public val isMuted: Boolean get() = synchronized(lock) { muted }
    /** Set once the call reached `connected`. */
    public val connectedTimeMs: Long? get() = synchronized(lock) { connectedAtMs }
    /** Set once the call ended. */
    public val endedTimeMs: Long? get() = synchronized(lock) { endedAtMs }
    /** The error that ended the call, when there is one. */
    public val error: SentVoiceException? get() = synchronized(lock) { endedWith }

    /** Listens to the call's events until the token is cancelled or the call ends. */
    public fun on(listener: (CallEvent) -> Unit): ListenerToken {
        val id = synchronized(lock) { nextListener++.also { listeners[it] = listener } }
        return ListenerToken { synchronized(lock) { listeners.remove(id) } }
    }

    public suspend fun hangup() {
        if (state.isTerminal) return
        actions.hangup()
    }

    public fun mute(muted: Boolean) {
        actions.mute(muted)
    }

    /** Sends DTMF digits: `0`-`9`, `*`, `#`, `A`-`D`, and `,` for a pause. */
    public fun sendDigits(digits: String) {
        val allowed = "0123456789*#ABCDabcd,".toSet()
        if (digits.isEmpty() || !digits.all { it in allowed }) {
            throw SentVoiceException(ErrorCode.INVALID_ADDRESS, "Digits may be 0-9, *, #, A-D and , for a pause.")
        }
        actions.sendDigits(digits)
    }

    public suspend fun stats(): CallStats = actions.stats()

    /** Applies what the provider reported. Returns the event to emit, or null when the contract refuses the move. */
    internal fun apply(event: AdapterCallEvent, nowMs: Long): CallEvent? {
        val out: CallEvent? = synchronized(lock) {
            when (event) {
                AdapterCallEvent.Ringing -> if (machine.transitionTo(CallState.RINGING)) CallEvent.Ringing else null
                AdapterCallEvent.Answered -> if (machine.transitionTo(CallState.ANSWERED)) CallEvent.Answered else null
                AdapterCallEvent.Connected -> if (machine.transitionTo(CallState.CONNECTED)) {
                    if (connectedAtMs == null) connectedAtMs = nowMs
                    CallEvent.Connected
                } else null
                AdapterCallEvent.Reconnecting -> if (machine.transitionTo(CallState.RECONNECTING)) CallEvent.Reconnecting else null
                AdapterCallEvent.Reconnected -> if (machine.transitionTo(CallState.CONNECTED)) CallEvent.Reconnected else null
                is AdapterCallEvent.Disconnected -> if (event.state.isTerminal && machine.transitionTo(event.state)) {
                    endedAtMs = nowMs
                    endedWith = event.error
                    CallEvent.Disconnected(event.state, event.error)
                } else null
                is AdapterCallEvent.MuteChanged -> {
                    muted = event.muted
                    CallEvent.MuteChanged(event.muted)
                }
                is AdapterCallEvent.Quality -> CallEvent.Quality(event.warning)
                is AdapterCallEvent.Error -> CallEvent.Error(event.error)
            }
        }
        if (out != null) emit(out)
        return out
    }

    private fun emit(event: CallEvent) {
        val current = synchronized(lock) { listeners.values.toList() }
        current.forEach { it(event) }
        if (event is CallEvent.Disconnected) synchronized(lock) { listeners.clear() }
    }
}
