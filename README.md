# Sent Voice Client for Android

Calls for the people in your app: place phone and in-app calls from an Android app, under the same contract
as the [web client](https://github.com/sentdm/sent-dm-voice-typescript).

This SDK places outbound calls: to a phone number, to a user of your app, or into a conference. Receiving
calls on a phone is not part of it; the web client receives calls.

Read the [Voice docs](https://docs.sent.dm/sdks/voice/android) to get started.

## Install

From Maven Central, for Android 8.0 (API 26) or later:

```kotlin
dependencies {
    implementation("dm.sent:voice-client:0.1.0")
}
```

`dm.sent:voice-client-core` comes with it. Calls use the microphone: the library declares `RECORD_AUDIO`,
and your app asks the user for it at runtime before the first call.

## Quickstart

### 1. Mint voice tokens on your backend

Mint tokens on your server with your Sent API key, never in the app, for the user who is signed in:
`POST https://api.sent.dm/v3/voice/tokens` with `{ "identity": "<user>" }` answers with `data.token`.
Return it as text from an endpoint of your own, such as `/api/voice-token`. The
[web client](https://github.com/sentdm/sent-dm-voice-typescript/tree/main/packages/voice-client#1-mint-voice-tokens-on-your-backend)
shows the request and its options; they are the same for every platform.

### 2. Register and call

```kotlin
import dm.sent.voice.SentVoice
import dm.sent.voice.core.CallEvent
import dm.sent.voice.core.SentVoiceOptions

val client = SentVoice.client(applicationContext, SentVoiceOptions(tokenProvider = { myBackend.voiceToken() }))

// From a coroutine: register and connect suspend
client.register()

val call = client.connect("+14155551234")
val listener = call.on { event ->
    when (event) {
        is CallEvent.Connected -> Log.i("voice", "Connected")
        is CallEvent.Disconnected -> Log.i("voice", "The call ended as ${event.state}")
        else -> Unit
    }
}
```

`connect(to)` takes a phone number in E.164 format or the identity of another user of your app;
`joinConference(name)` joins a conference. `hangup()`, `mute(muted)` and `sendDigits(digits)` on the call
do what they say. Events arrive on the thread that reported them, so hop to the main thread before
touching views. Keep one client for the life of the app: it refreshes the token before it expires, and
`destroy()` ends every call and releases it.

## Layout

| Path                 | What it is                                                                                           |
| -------------------- | ---------------------------------------------------------------------------------------------------- |
| `voice-client/`      | The Android client: `SentVoice.client(context, options)` and the adapter over the calling provider's SDK. `dm.sent:voice-client` on Maven Central |
| `voice-client-core/` | The provider-free core: the contract's vocabulary, errors, the call state machine, the token lifecycle and telemetry. Plain Kotlin on the JVM, tests anywhere. `dm.sent:voice-client-core` |
| `contract/`          | The pinned Voice Client contract and how to take a new version                                        |
| `scripts/`           | `generate-contract.py` writes `Contract.kt` from the contract; `check-contract` fails CI on a diff    |

## Develop

```sh
python3 scripts/generate-contract.py        # after a contract change
./gradlew :voice-client-core:check          # build and test the core; needs a JDK 17 or newer
./gradlew :voice-client:assembleRelease     # the Android client; needs the Android SDK
```

Releases are cut by release-please from `main` and published to Maven Central from GitHub Actions.
