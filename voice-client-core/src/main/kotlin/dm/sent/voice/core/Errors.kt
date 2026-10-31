package dm.sent.voice.core

/**
 * An error the Voice Client reports: a contract code, its category, whether retrying can help, a message
 * written for the app's logs, and the provider's own detail when there is one.
 */
public class SentVoiceException(
    public val code: ErrorCode,
    message: String? = null,
    public val providerDetail: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: DEFAULT_MESSAGES[code] ?: "Voice error ${code.wire}.", cause) {
    private val spec: ErrorClass? = Contract.errorClasses.firstOrNull { it.code == code }

    public val category: ErrorCategory = spec?.category ?: FALLBACK_CATEGORIES[code] ?: ErrorCategory.SIGNALING
    public val retriable: Boolean = spec?.retriable ?: false

    override fun toString(): String = "${code.wire}: $message"

    private companion object {
        /** Codes the contract names without an error class. */
        val FALLBACK_CATEGORIES: Map<ErrorCode, ErrorCategory> = mapOf(
            ErrorCode.INVALID_ADDRESS to ErrorCategory.VALIDATION,
            ErrorCode.CALL_IN_PROGRESS to ErrorCategory.VALIDATION,
            ErrorCode.UNKNOWN to ErrorCategory.SIGNALING,
        )

        val DEFAULT_MESSAGES: Map<ErrorCode, String> = mapOf(
            ErrorCode.TOKEN_EXPIRED to "The voice token expired and could not be refreshed.",
            ErrorCode.TOKEN_INVALID to "The token provider returned an invalid voice token.",
            ErrorCode.NOT_REGISTERED to "The client is not registered.",
            ErrorCode.MEDIA_PERMISSION_DENIED to "Microphone permission was denied.",
            ErrorCode.CALL_REJECTED to "The call was rejected.",
            ErrorCode.CALL_FAILED to "The call failed.",
            ErrorCode.NETWORK to "The network connection failed.",
            ErrorCode.CAPABILITY_UNSUPPORTED to "This capability is not supported.",
            ErrorCode.INVALID_ADDRESS to "The address is invalid.",
            ErrorCode.CALL_IN_PROGRESS to "A call is in progress or an incoming call is waiting; end or decline it first.",
            ErrorCode.UNKNOWN to "An unexpected error occurred.",
        )
    }
}
