plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.overmind.obdgateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.overmind.obdgateway"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // phone = passerelle OBD seule (appli téléphone) ; headunit = passerelle + copilote vocal « Kitt »
    // pour autoradio Android (même code OBD/MQTT partagé dans src/main).
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
        }
        create("headunit") {
            dimension = "device"
            applicationId = "com.overmind.copilot"
            // Autoradios et téléphones : ARM uniquement (allège l'APK des bibliothèques Vosk x86).
            // `-PallAbis` : garde x86 pour l'émulateur (vérification de l'interface).
            if (!project.hasProperty("allAbis")) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signature : configurer un keystore personnel (voir README). Debug par défaut pour le sideload.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Copilote (autoradio uniquement) : mot de réveil hors ligne + WebSocket vers Overmind
    "headunitImplementation"("com.alphacephei:vosk-android:0.3.47")
    "headunitImplementation"("net.java.dev.jna:jna:5.13.0@aar")
    "headunitImplementation"("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
