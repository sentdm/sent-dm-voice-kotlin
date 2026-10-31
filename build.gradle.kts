plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

allprojects {
    group = "dm.sent"
    // gradle.properties keeps release-please's marker on the version line; a properties value has no comment syntax.
    version = (property("version") as String).substringBefore('#').trim()
}

// Publishing to Maven Central, the way sent-dm-java does it: the Central Portal, every publication signed
// with the key the workflow imports from the repository's secrets.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<PublishingExtension> {
            repositories {
                if (project.hasProperty("publishLocal")) {
                    maven {
                        name = "LocalFileSystem"
                        url = uri("${rootProject.layout.buildDirectory.get()}/local-maven-repo")
                    }
                }
            }
        }
        extra["signingInMemoryKey"] = System.getenv("GPG_SIGNING_KEY")
        extra["signingInMemoryKeyId"] = System.getenv("GPG_SIGNING_KEY_ID")
        extra["signingInMemoryKeyPassword"] = System.getenv("GPG_SIGNING_PASSWORD")
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            if (!project.hasProperty("publishLocal")) {
                signAllPublications()
                publishToMavenCentral()
            }
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set("Sent Voice Client for Android: ${project.name}")
                description.set("Calls for the people in your app: place phone and in-app calls from an Android app through Sent.")
                url.set("https://docs.sent.dm")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        name.set("Sent")
                        email.set("support@sent.dm")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/sentdm/sent-dm-voice-kotlin.git")
                    developerConnection.set("scm:git:git://github.com/sentdm/sent-dm-voice-kotlin.git")
                    url.set("https://github.com/sentdm/sent-dm-voice-kotlin")
                }
            }
        }
    }
}
