import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api("dev.g000sha256:tdl-coroutines:15.0.0")
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.material3)
            api("org.jetbrains.compose.material:material-icons-core:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.12.3")
            implementation("androidx.core:core-ktx:1.18.0")
            implementation("androidx.media3:media3-exoplayer:1.11.1")
            implementation("androidx.media3:media3-ui:1.11.1")
            implementation("io.github.pytgcalls:ntgcalls:3.0.5")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
                implementation("net.java.dev.jna:jna:5.17.0")
            }
        }
    }
}

android {
    namespace = "com.skgram.shared"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
