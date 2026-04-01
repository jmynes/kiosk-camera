plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kioskcamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kioskcamera"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // For dev: uses ADB reverse port forward (adb reverse tcp:8443 tcp:8443)
        // For prod: replace with actual server IP/hostname
        buildConfigField("String", "UPLOAD_URL", "\"https://127.0.0.1:8443/upload\"")
        buildConfigField("String", "CERT_PIN", "\"sha256/+tUcWyunJuT8RAAS/lyZPUPthJZz/wIwDDeqPzMjCZU=\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.camera:camera-extensions:1.3.1")
    implementation("androidx.camera:camera-video:1.3.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
