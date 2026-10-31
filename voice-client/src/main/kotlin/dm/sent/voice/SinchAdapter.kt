package dm.sent.voice

import android.content.Context
import android.util.Base64
import com.sinch.android.rtc.ClientRegistration
import com.sinch.android.rtc.SinchClient
import com.sinch.android.rtc.SinchClientListener
import com.sinch.android.rtc.SinchError
import com.sinch.android.rtc.calling.Call
import com.sinch.android.rtc.calling.CallDetails
import com.sinch.android.rtc.calling.CallEndCause
import com.sinch.android.rtc.calling.CallListener
import com.sinch.android.rtc.calling.MediaConstraints
import dm.sent.voice.core.AdapterCallEvent
import dm.sent.voice.core.CallState
import dm.sent.voice.core.CallStats
import dm.sent.voice.core.CallTarget
import dm.sent.voice.core.ErrorCode
import dm.sent.voice.core.ProviderAdapter
import dm.sent.voice.core.SentVoiceException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The provider's Android SDK behind the client: one provider client per registration, one listener per call.
 * The provider calls back on the thread its client was built on, the main thread here; events are forwarded
 * as they come.
 */
internal class SinchAdapter(private val context: Context) : ProviderAdapter {
    private companion object {
        const val ENVIRONMENT_HOST = "ocra-euc1.api.sinch.com"
        const val APPLICATION_PREFIX = "//rtc.sinch.com/applications/"
    }

    private val lock = Any()
    private var client: SinchClient? = null
    private var credential = ""
    private var callerId: String? = null
    private val calls = HashMap<String, Call>()
    private var handler: ((String, AdapterCallEvent) -> Unit)? = null

    override suspend fun register(credential: String, number: String?) {
        val (applicationKey, userId) = subjectOf(credential)
        val existing = synchronized(lock) {
            this.credential = credential
            this.callerId = number
            client
        }
        if (existing != null) return

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val built = SinchClient.builder()
                    .context(context)
                    .applicationKey(applicationKey)
                    .environmentHost(ENVIRONMENT_HOST)
                    .userId(userId)
                    .build()
                // The listener also carries the push token callbacks; they never fire here, this client registers no push configuration.
                built.addSinchClientListener(object : SinchClientListener {
                    override fun onCredentialsRequired(clientRegistration: ClientRegistration) {
                        clientRegistration.register(synchronized(lock) { this@SinchAdapter.credential })
                    }

                    override fun onUserRegistered() {}

                    override fun onUserRegistrationFailed(error: SinchError) = refused("The provider refused the credential.", error)

                    override fun onClientStarted(client: SinchClient) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onClientFailed(client: SinchClient, error: SinchError) = refused("The provider refused the registration.", error)

                    override fun onPushTokenRegistered() {}

                    override fun onPushTokenRegistrationFailed(error: SinchError) {}

                    override fun onPushTokenUnregistered() {}

                    override fun onPushTokenUnregistrationFailed(error: SinchError) {}

                    private fun refused(message: String, error: SinchError) {
                        synchronized(lock) { this@SinchAdapter.client = null }
                        if (continuation.isActive) {
                            continuation.resumeWithException(SentVoiceException(ErrorCode.TOKEN_INVALID, message, providerDetail = detail(error)))
                        }
                    }
                })
                synchronized(lock) { client = built }
                built.start()
            }
        }
    }

    override suspend fun unregister() {
        val (current, open) = synchronized(lock) {
            val pair = client to calls.values.toList()
            client = null
            calls.clear()
            pair
        }
        withContext(Dispatchers.Main) {
            open.forEach { it.hangup() }
            current?.terminateGracefully()
        }
    }

    override suspend fun call(target: CallTarget): String {
        val (current, cli) = synchronized(lock) { client to callerId }
        if (current == null) throw SentVoiceException(ErrorCode.NOT_REGISTERED)
        return withContext(Dispatchers.Main) {
            val call = try {
                when (target) {
                    // The provider needs a caller id on every call to a phone number; the token names it when the account has one.
                    is CallTarget.Number -> current.callController.callPhoneNumber(
                        target.number,
                        cli ?: throw SentVoiceException(ErrorCode.CALL_FAILED, "Calls to phone numbers need a number on the token."),
                    )
                    is CallTarget.User -> current.callController.callUser(target.id, MediaConstraints(false))
                    is CallTarget.Conference -> current.callController.callConference(target.room)
                }
            } catch (error: Exception) {
                throw SentVoiceException(ErrorCode.CALL_FAILED, "The call could not be placed.", providerDetail = error.toString(), cause = error)
            }
            call.addCallListener(listener)
            synchronized(lock) { calls[call.callId] = call }
            call.callId
        }
    }

    override suspend fun hangup(callId: String) {
        val call = synchronized(lock) { calls[callId] } ?: return
        withContext(Dispatchers.Main) { call.hangup() }
    }

    override fun mute(callId: String, muted: Boolean) {
        val current = synchronized(lock) { client } ?: throw SentVoiceException(ErrorCode.NOT_REGISTERED)
        if (muted) current.audioController.mute() else current.audioController.unmute()
        emit(callId, AdapterCallEvent.MuteChanged(muted))
    }

    override fun sendDigits(callId: String, digits: String) {
        val call = synchronized(lock) { calls[callId] } ?: throw SentVoiceException(ErrorCode.CALL_FAILED, "The call is over.")
        call.sendDTMF(digits)
    }

    override suspend fun stats(callId: String): CallStats = CallStats()

    override fun setCallEventHandler(handler: (callId: String, event: AdapterCallEvent) -> Unit) {
        synchronized(lock) { this.handler = handler }
    }

    private fun emit(callId: String, event: AdapterCallEvent) {
        synchronized(lock) { handler }?.invoke(callId, event)
    }

    private val listener = object : CallListener {
        override fun onCallProgressing(call: Call) = emit(call.callId, AdapterCallEvent.Ringing)
        override fun onCallRinging(call: Call) = emit(call.callId, AdapterCallEvent.Ringing)
        override fun onCallAnswered(call: Call) = emit(call.callId, AdapterCallEvent.Answered)
        override fun onCallEstablished(call: Call) = emit(call.callId, AdapterCallEvent.Connected)
        override fun onCallEnded(call: Call) {
            synchronized(lock) { calls.remove(call.callId) }
            emit(call.callId, ended(call.details))
        }
    }

    /** The application key and user id a credential names, read from its claims without verifying it. */
    internal fun subjectOf(credential: String): Pair<String, String> {
        val parts = credential.split('.')
        if (parts.size < 2) throw SentVoiceException(ErrorCode.TOKEN_INVALID)
        val claims = try {
            JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (error: Exception) {
            throw SentVoiceException(ErrorCode.TOKEN_INVALID, "The credential names no application and user.", cause = error)
        }
        val iss = claims.optString("iss")
        val sub = claims.optString("sub")
        val usersAt = sub.indexOf("/users/")
        if (!iss.startsWith(APPLICATION_PREFIX) || usersAt < 0) {
            throw SentVoiceException(ErrorCode.TOKEN_INVALID, "The credential names no application and user.")
        }
        return iss.removePrefix(APPLICATION_PREFIX) to sub.substring(usersAt + "/users/".length)
    }

    /** What the provider's end cause means in the contract's words. */
    internal fun ended(details: CallDetails): AdapterCallEvent = when (details.endCause) {
        CallEndCause.DENIED -> AdapterCallEvent.Disconnected(CallState.BUSY)
        CallEndCause.NO_ANSWER, CallEndCause.TIMEOUT -> AdapterCallEvent.Disconnected(CallState.NO_ANSWER)
        CallEndCause.INACTIVE -> AdapterCallEvent.Disconnected(
            CallState.FAILED,
            SentVoiceException(ErrorCode.CALL_FAILED, "The call was ended after no media flowed.", providerDetail = "inactive"),
        )
        CallEndCause.FAILURE -> AdapterCallEvent.Disconnected(
            CallState.FAILED,
            SentVoiceException(ErrorCode.CALL_FAILED, providerDetail = details.error?.let(::detail)),
        )
        else -> AdapterCallEvent.Disconnected(CallState.COMPLETED)
    }

    /** The provider's error as one line for `providerDetail`. */
    private fun detail(error: SinchError): String = "${error.errorType} ${error.code}: ${error.message}"
}
