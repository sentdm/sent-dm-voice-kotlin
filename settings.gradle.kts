plugins {
    // Downloads the JDK the toolchain asks for when the machine has none.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sent-voice-kotlin"

include("voice-client-core")
