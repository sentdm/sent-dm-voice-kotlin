plugins {
    kotlin("jvm") version "1.9.20" apply false
    kotlin("android") version "1.9.20" apply false
    kotlin("plugin.serialization") version "1.9.20" apply false
    id("com.android.library") version "8.7.3" apply false
}

allprojects {
    group = "dm.sent"
    version = property("version") as String
}
