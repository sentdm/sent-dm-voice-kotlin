// Generated from contract/voice-client.json 1.0.0 by scripts/generate-contract.py. Do not edit.

package dm.sent.voice.core

/** The Voice Client contract this SDK implements: the vocabulary every Sent Voice Client SDK shares. */
public object Contract {
    public const val VERSION: String = "1.0.0"

    /** Which call states a call may move to from each state. */
    public val callTransitions: Map<CallState, List<CallState>> = mapOf(
        CallState.INITIATED to listOf(CallState.RINGING, CallState.ANSWERED, CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER),
        CallState.RINGING to listOf(CallState.ANSWERED, CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER),
        CallState.ANSWERED to listOf(CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER),
        CallState.CONNECTED to listOf(CallState.RECONNECTING, CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER),
        CallState.RECONNECTING to listOf(CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.BUSY, CallState.NO_ANSWER),
        CallState.COMPLETED to listOf(),
        CallState.FAILED to listOf(),
        CallState.BUSY to listOf(),
        CallState.NO_ANSWER to listOf(),
    )

    /** The error classes the contract names, with the category and retriability of each. */
    public val errorClasses: List<ErrorClass> = listOf(
        ErrorClass("TokenExpiredError", ErrorCode.TOKEN_EXPIRED, ErrorCategory.AUTH, retriable = true),
        ErrorClass("TokenInvalidError", ErrorCode.TOKEN_INVALID, ErrorCategory.AUTH, retriable = false),
        ErrorClass("NotRegisteredError", ErrorCode.NOT_REGISTERED, ErrorCategory.VALIDATION, retriable = false),
        ErrorClass("MediaPermissionError", ErrorCode.MEDIA_PERMISSION_DENIED, ErrorCategory.MEDIA, retriable = false),
        ErrorClass("CallRejectedError", ErrorCode.CALL_REJECTED, ErrorCategory.SIGNALING, retriable = false),
        ErrorClass("CallFailedError", ErrorCode.CALL_FAILED, ErrorCategory.SIGNALING, retriable = false),
        ErrorClass("NetworkError", ErrorCode.NETWORK, ErrorCategory.NETWORK, retriable = true),
        ErrorClass("CapabilityUnsupportedError", ErrorCode.CAPABILITY_UNSUPPORTED, ErrorCategory.CAPABILITY, retriable = false),
    )

    /** The fields a telemetry payload may carry. */
    public val telemetryPayloadFields: List<String> = listOf("browser", "browser_version", "os", "os_version", "device_type", "duration_ms", "attempt", "direction", "outcome", "rtt_ms", "jitter_ms", "packet_loss", "samples", "code", "category", "message", "retriable", "provider_error")
}

/** The states a client moves through. */
public enum class ClientState(public val wire: String) {
    UNREGISTERED("unregistered"),
    REGISTERING("registering"),
    REGISTERED("registered"),
    OFFLINE("offline"),
    DESTROYED("destroyed"),
;

    public companion object {
        public fun fromWire(wire: String): ClientState? = entries.firstOrNull { it.wire == wire }
    }
}

/** The events a client emits. */
public enum class ClientEventName(public val wire: String) {
    REGISTERING("registering"),
    REGISTERED("registered"),
    UNREGISTERED("unregistered"),
    INCOMING_CALL("incomingCall"),
    ACTIVE_CALL_CHANGED("activeCallChanged"),
    OFFLINE("offline"),
    TOKEN_WILL_EXPIRE("tokenWillExpire"),
    ERROR("error"),
;

    public companion object {
        public fun fromWire(wire: String): ClientEventName? = entries.firstOrNull { it.wire == wire }
    }
}

/** The states a call moves through. */
public enum class CallState(public val wire: String) {
    INITIATED("initiated"),
    RINGING("ringing"),
    ANSWERED("answered"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    COMPLETED("completed"),
    FAILED("failed"),
    BUSY("busy"),
    NO_ANSWER("noAnswer"),
;

    public companion object {
        public fun fromWire(wire: String): CallState? = entries.firstOrNull { it.wire == wire }
    }
}

/** The state a call starts in, by direction. */
public fun CallState.Companion.initial(direction: CallDirection): CallState = when (direction) {
    CallDirection.INBOUND -> CallState.RINGING
    CallDirection.OUTBOUND -> CallState.INITIATED
}

/** Whether the contract allows a call in this state to move to [next]. */
public fun CallState.canTransitionTo(next: CallState): Boolean = Contract.callTransitions[this]?.contains(next) ?: false

/** A state no call leaves. */
public val CallState.isTerminal: Boolean get() = Contract.callTransitions[this]?.isEmpty() ?: true

/** Who placed the call. */
public enum class CallDirection(public val wire: String) { INBOUND("inbound"), OUTBOUND("outbound") }

/** The events a call emits. */
public enum class CallEventName(public val wire: String) {
    RINGING("ringing"),
    ANSWERED("answered"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    RECONNECTED("reconnected"),
    DISCONNECTED("disconnected"),
    MUTE_CHANGED("muteChanged"),
    QUALITY_WARNING("qualityWarning"),
    ERROR("error"),
;

    public companion object {
        public fun fromWire(wire: String): CallEventName? = entries.firstOrNull { it.wire == wire }
    }
}

/** The events an incoming-call invite emits. */
public enum class InviteEventName(public val wire: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
;

    public companion object {
        public fun fromWire(wire: String): InviteEventName? = entries.firstOrNull { it.wire == wire }
    }
}

/** The events the audio layer emits. */
public enum class AudioEventName(public val wire: String) {
    DEVICE_CHANGED("deviceChanged"),
;

    public companion object {
        public fun fromWire(wire: String): AudioEventName? = entries.firstOrNull { it.wire == wire }
    }
}

/** The error codes an SDK error carries. */
public enum class ErrorCode(public val wire: String) {
    TOKEN_EXPIRED("TOKEN_EXPIRED"),
    TOKEN_INVALID("TOKEN_INVALID"),
    NOT_REGISTERED("NOT_REGISTERED"),
    MEDIA_PERMISSION_DENIED("MEDIA_PERMISSION_DENIED"),
    CALL_REJECTED("CALL_REJECTED"),
    CALL_FAILED("CALL_FAILED"),
    NETWORK("NETWORK"),
    CAPABILITY_UNSUPPORTED("CAPABILITY_UNSUPPORTED"),
    INVALID_ADDRESS("INVALID_ADDRESS"),
    CALL_IN_PROGRESS("CALL_IN_PROGRESS"),
    UNKNOWN("UNKNOWN"),
;

    public companion object {
        public fun fromWire(wire: String): ErrorCode? = entries.firstOrNull { it.wire == wire }
    }
}

/** The categories an SDK error belongs to. */
public enum class ErrorCategory(public val wire: String) {
    AUTH("auth"),
    MEDIA("media"),
    SIGNALING("signaling"),
    NETWORK("network"),
    VALIDATION("validation"),
    CAPABILITY("capability"),
;

    public companion object {
        public fun fromWire(wire: String): ErrorCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** An error class the contract names. */
public data class ErrorClass(val name: String, val code: ErrorCode, val category: ErrorCategory, val retriable: Boolean)

/** When a voice token is refreshed and how failed fetches are retried, in milliseconds. */
public object TokenTiming {
    public const val REFRESH_SHARE: Double = 0.8
    public const val REFRESH_MARGIN_MS: Long = 30000L
    public const val INITIAL_RETRY_DELAY_MS: Long = 500L
    public const val MAX_RETRY_DELAY_MS: Long = 8000L
    public const val OFFLINE_RETRY_INTERVAL_MS: Long = 30000L
}

/** The events the SDK reports to Sent. */
public enum class TelemetryEventName(public val wire: String) {
    REGISTER_COMPLETED("register.completed"),
    REGISTER_FAILED("register.failed"),
    UNREGISTER_COMPLETED("unregister.completed"),
    CLIENT_INFO("client.info"),
    CLIENT_ERROR("client.error"),
    CALL_STARTED("call.started"),
    CALL_CONNECTED("call.connected"),
    CALL_QUALITY("call.quality"),
    CALL_ENDED("call.ended"),
;

    public companion object {
        public fun fromWire(wire: String): TelemetryEventName? = entries.firstOrNull { it.wire == wire }
    }
}
