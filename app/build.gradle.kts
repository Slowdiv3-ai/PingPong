import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    // REMOVED: Chaquopy plugin (no Python/yt-dlp needed for offline player)
}

val appVersionName = (project.findProperty("versionOverride") as String?) ?: "1.0"
val appVersionCode =
    appVersionName
        .substringBefore("-")
        .split(".")
        .mapNotNull { it.toIntOrNull() }
        .let {
            (it.getOrElse(0) { 0 } * 10_000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 }
        }.coerceAtLeast(1)

val keystoreProps: Properties? =
    rootProject
        .file("keystore.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProps?.getProperty(propertyKey) ?: System.getenv(envKey)

base {
    archivesName.set("PingPong-$appVersionName")
}

android {
    namespace = "dev.abhi.zmt"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.abhi.zmt"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        
        // Removed NDK abiFilters since we no longer compile C++/Python code
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "SIGNING_KEYSTORE_PATH")
            if (storePath != null && rootProject.file(storePath).exists()) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS") ?: "pingpong"
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/proguard/coroutines.pro",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions",
                "META-INF/LSRC-INF",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    
    // Local modules
    implementation(project(":metadata"))
    implementation(project(":lyrics"))
    
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    
    // Media3 (ExoPlayer) - The heart of PingPong
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(files("libs/media3-decoder-ffmpeg-1.10.1.aar"))
    
    // Coroutines & DI
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // Networking (Kept for LRCLIB lyrics fetching, which is safe and lightweight)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.nop)
    
    // REMOVED: tdlib (Telegram), Chaquopy, and Python-specific dependencies
    
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// REMOVED: All Chaquopy/QuickJS afterEvaluate blocks and Python proguard tasks
