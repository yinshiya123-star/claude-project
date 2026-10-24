plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}

/** Single source of truth for the app version (Android, Windows, macOS, Linux). */
extra["skgramVersion"] = "1.2.0"
extra["skgramVersionCode"] = 3

tasks.register("printVersion") {
    val v = project.extra["skgramVersion"]
    doLast { println(v) }
}
