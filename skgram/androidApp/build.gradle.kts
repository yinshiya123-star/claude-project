import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val skgramVersion: String by rootProject.extra
val skgramVersionCode: Int by rootProject.extra

// Telegram API credentials (https://my.telegram.org). Optional at build time:
// if left empty, the app asks for them on first launch.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')) ?: "").trim()

android {
    namespace = "com.skgram.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skgram.app"
        minSdk = 26
        targetSdk = 36
        versionCode = skgramVersionCode
        versionName = skgramVersion
        buildConfigField("int", "TG_API_ID", secret("tg.api.id").ifEmpty { "0" })
        buildConfigField("String", "TG_API_HASH", "\"${secret("tg.api.hash")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = secret("skgram.keystore")
            if (ks.isNotEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = secret("skgram.keystore.password")
                keyAlias = secret("skgram.key.alias")
                keyPassword = secret("skgram.key.password")
            } else {
                // No release key configured: fall back to the debug key so the APK still installs.
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.core:core-ktx:1.18.0")
}
