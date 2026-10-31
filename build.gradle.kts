plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("com.android.library") version "8.7.3" apply false
}

allprojects {
    group = "dm.sent"
    version = property("version") as String
}
