import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val skgramVersion: String by rootProject.extra

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/** Native folder inside the TDLib jar for the machine building the installer (installers are per-OS anyway). */
val tdlibNativeDir: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
    when {
        os.contains("win") -> "windows/$arch"
        os.contains("mac") -> "macos/$arch"
        else -> "linux/$arch"
    }
}

// The TDLib jar carries native libraries for every OS (~300 MB); repackage it with only this platform's.
val tdlibFull by configurations.creating { isTransitive = false }
val tdlibStripped by tasks.registering(Jar::class) {
    archiveFileName.set("tdl-coroutines-jvm-stripped.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tdlib"))
    from(tdlibFull.elements.map { jars -> jars.map { zipTree(it) } }) {
        exclude { e -> e.path.startsWith("linux/") || e.path.startsWith("macos/") || e.path.startsWith("windows/") }
    }
    from(tdlibFull.elements.map { jars -> jars.map { zipTree(it) } }) {
        include("$tdlibNativeDir/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

configurations.matching { it.name == "runtimeClasspath" || it.name == "compileClasspath" }.configureEach {
    exclude(group = "dev.g000sha256", module = "tdl-coroutines-jvm")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(files(tdlibStripped))
    // What the excluded TDLib artifact would have brought in.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
    tdlibFull("dev.g000sha256:tdl-coroutines-jvm:15.0.0")
}

compose.desktop {
    application {
        mainClass = "com.skgram.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dskgram.version=$skgramVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.AppImage, TargetFormat.Deb)
            packageName = "SKGram"
            packageVersion = skgramVersion
            description = "SKGram — a fast Telegram client"
            vendor = "SKGram"
            copyright = "SKGram contributors"
            modules("java.prefs", "java.desktop", "jdk.unsupported", "java.naming")

            windows {
                iconFile.set(project.file("icons/skgram.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                menuGroup = "SKGram"
                upgradeUuid = "8b7c1a54-2f0e-4c8e-9a52-5d3f1c6b2e71"
            }
            macOS {
                iconFile.set(project.file("icons/skgram.icns"))
                bundleID = "com.skgram.desktop"
                appCategory = "public.app-category.social-networking"
            }
            linux {
                iconFile.set(project.file("icons/skgram.png"))
                packageName = "skgram"
                appCategory = "Network"
                menuGroup = "Network;InstantMessaging"
            }
        }
    }
}
