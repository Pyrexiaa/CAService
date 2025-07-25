plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.serviceapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.serviceapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.ui.geometry.android)
    implementation(libs.litert)
    implementation(libs.androidx.room.external.antlr)
    implementation(libs.ads.mobile.sdk)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.material.v1120)
    implementation(libs.androidx.core.i18n)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation (libs.androidx.gridlayout)
    // CameraX - custom control over camera
    implementation (libs.androidx.camera.core)
    implementation (libs.androidx.camera.camera2.v130)
    implementation (libs.androidx.camera.lifecycle.v130)
    implementation (libs.androidx.camera.view.v130)
    implementation (libs.androidx.camera.extensions)
    //chart
    implementation(libs.mpandroidchart)
    // stable biometric version
    implementation(libs.androidx.biometric)
    // customized face recognition
    implementation(libs.face.detection)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // audio detection
    implementation(libs.webrtc)
    // audio processing
    implementation (libs.core)
    implementation (libs.jvm)
    implementation(libs.tensorflow.lite.select.tf.ops.v2120)
}