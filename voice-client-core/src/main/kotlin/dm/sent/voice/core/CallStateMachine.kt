package dm.sent.voice.core

/** Tracks a call's state and refuses the moves the contract does not allow. */
public class CallStateMachine(direction: CallDirection) {
    public var state: CallState = CallState.initial(direction)
        private set

    /** Moves to [next] when the contract allows it. Returns whether it did. */
    public fun transitionTo(next: CallState): Boolean {
        if (!state.canTransitionTo(next)) return false
        state = next
        return true
    }
}
