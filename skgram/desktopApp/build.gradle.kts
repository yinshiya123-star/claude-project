import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val skgramVersion: String by rootProject.extra

// Telegram API credentials baked into the app (from local.properties or the TG_API_ID / TG_API_HASH environment
// variables). If absent, users enter their own on first launch.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')) ?: "").trim()

val buildInfoDir = layout.buildDirectory.dir("generated/skgram-build-info")
val writeBuildInfo by tasks.registering {
    val id = secret("tg.api.id")
    val hash = secret("tg.api.hash")
    inputs.property("credentials", "$id:$hash")
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("skgram-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("tg.api.id=$id\ntg.api.hash=$hash\n")
    }
}
sourceSets["main"].resources.srcDir(writeBuildInfo)

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

// NTgCalls (voice calls): prebuilt C library from its GitHub releases, bundled as an app resource for this OS.
// Not published for Intel Macs, where calls are then unavailable.
val ntgcallsVersion = "3.0.5"
val ntgcallsAsset: String? = when (tdlibNativeDir) {
    "linux/x64" -> "linux-x86_64"
    "linux/arm64" -> "linux-arm64"
    "windows/x64" -> "windows-x86_64"
    "macos/arm64" -> "macos-arm64"
    else -> null
}
// Compose copies <resources dir>/<os>-<arch>/ into the app and exposes it as compose.application.resources.dir.
val composeResDir = tdlibNativeDir.replace('/', '-')
val ntgcallsResources = layout.buildDirectory.dir("ntgcalls-resources")
val fetchNtgcalls by tasks.registering {
    val out = ntgcallsResources.map { it.dir(composeResDir) }
    outputs.dir(ntgcallsResources)
    onlyIf { ntgcallsAsset != null }
    doLast {
        val zip = layout.buildDirectory.file("ntgcalls/$ntgcallsAsset-$ntgcallsVersion.zip").get().asFile
        if (!zip.exists()) {
            zip.parentFile.mkdirs()
            uri("https://github.com/pytgcalls/ntgcalls/releases/download/v$ntgcallsVersion/ntgcalls.$ntgcallsAsset-shared_libs.zip")
                .toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }
        val dest = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        copy {
            from(zipTree(zip)) { include("lib/**/*.so", "lib/**/*.dll", "lib/**/*.dylib") }
            eachFile { path = name }
            includeEmptyDirs = false
            into(dest)
        }
        // The Linux build ships with debug info (~125 MB); stripping it leaves ~40 MB.
        dest.listFiles { f -> f.name.endsWith(".so") }?.forEach { so ->
            runCatching { ProcessBuilder("strip", "--strip-unneeded", so.absolutePath).inheritIO().start().waitFor() }
        }
    }
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
        nativeDistributions.appResourcesRootDir.set(ntgcallsResources)

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
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>SKGram uses the microphone for voice calls and voice messages.</string>
                    """.trimIndent()
                }
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

// Make sure the call library is in place before running or packaging.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchNtgcalls) }
