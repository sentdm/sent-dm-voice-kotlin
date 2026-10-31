package dm.sent.voice

import android.content.Context
import dm.sent.voice.core.SentVoiceClient
import dm.sent.voice.core.SentVoiceOptions

/**
 * The Sent Voice Client for Android: a [SentVoiceClient] over the provider's SDK.
 *
 * ```kotlin
 * val client = SentVoice.client(applicationContext, SentVoiceOptions(tokenProvider = { myBackend.voiceToken() }))
 * client.register()
 * val call = client.connect("+14155551234")
 * ```
 */
public object SentVoice {
    /** A client that registers and places calls on this device. Keep one for the life of the app. */
    public fun client(context: Context, options: SentVoiceOptions): SentVoiceClient =
        SentVoiceClient(options, SinchAdapter(context.applicationContext))
}
