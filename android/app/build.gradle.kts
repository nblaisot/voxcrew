plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries.plugin.android)
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.nblaisot.voxcrew"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nblaisot.voxcrew"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "2.0.8"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties["storeFile"] as? String
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("config")
        filterVariants.addAll("debug", "release")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.telecom)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.concentus)
    // Silero VAD (ONNX Runtime Mobile) — neural voice-activity detection for VOX, chosen
    // over an RMS/energy gate or WebRTC's GMM VAD for robustness to outdoor noise (wind,
    // traffic). See docs/android-audio.md.
    implementation(libs.android.vad.silero)
    // OSS license attribution screen (About → licenses). Scans release/debug runtime classpath at build time.
    implementation(libs.aboutlibraries.compose.m3)
    // Self-hosted TLS WebSocket relay (optional Cloud path). See relay/README.md.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.concentus)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
