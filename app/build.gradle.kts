plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.hoonex.esp32car"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hoonex.esp32car"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "3.1.0"

        // Current app target devices are modern Galaxy/Android phones and tablets.
        // OpenCV ships very large native libraries for several CPU ABIs; keeping
        // only arm64-v8a cuts the downloadable APK size dramatically without
        // removing AI tracking/OpenCV functionality on these devices.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
