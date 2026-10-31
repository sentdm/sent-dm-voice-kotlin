# Sent Voice Client for Android

Calls for the people in your app: place phone and in-app calls from an Android app, under the same contract
as the [web client](https://github.com/sentdm/sent-dm-voice-typescript).

Status: in development, not yet published. The first release places outbound calls (to a phone number, a
user of your app, or a conference). Receiving calls on a phone needs push credentials registered with Sent,
which is platform work scheduled separately; it arrives as a minor version with no change to this SDK's shape.

Read the [Voice docs](https://docs.sent.dm) to get started.

## Layout

| Path                 | What it is                                                                                           |
| -------------------- | ---------------------------------------------------------------------------------------------------- |
| `voice-client-core/` | The provider-free core: the contract's vocabulary, errors, the call state machine, the token lifecycle and telemetry. Plain Kotlin on the JVM, tests anywhere |
| `contract/`          | The pinned Voice Client contract and how to take a new version                                        |
| `scripts/`           | `generate-contract.py` writes `Contract.kt` from the contract; `check-contract` fails CI on a diff    |

The Android client over the provider's SDK is the next module to land, as `voice-client`
(`dm.sent:voice-client` on Maven Central).

## Develop

```sh
python3 scripts/generate-contract.py   # after a contract change
./gradlew check                        # build and test; needs a JDK 17 or newer
```

Releases are cut by release-please from `main` and published to Maven Central from GitHub Actions.
