plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.jarvis.assistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.jarvis.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment (GitHub secrets), never from the repository.
    val releaseKeystore = System.getenv("ZOL_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("ZOL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ZOL_KEY_ALIAS")
            keyPassword = System.getenv("ZOL_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // head pointer: front camera + on-device face detection (no internet)
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("com.google.mlkit:face-detection:16.1.7")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}