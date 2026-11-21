import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// One key signs every build, so the in-app update can replace the installed app. It comes from
// keystore.properties on the developer's computer, or from GitHub secrets in the cloud build; never from git.
val keyProps = Properties()
rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keyProps.load(it) }
val storePath: String? = System.getenv("ZOL_KEYSTORE") ?: keyProps.getProperty("storeFile")

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
        versionCode = 9
        versionName = "1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (storePath != null) create("zol") {
            storeFile = rootProject.file(storePath!!)
            storePassword = System.getenv("ZOL_KEYSTORE_PASSWORD") ?: keyProps.getProperty("storePassword")
            keyAlias = System.getenv("ZOL_KEY_ALIAS") ?: keyProps.getProperty("keyAlias")
            keyPassword = System.getenv("ZOL_KEY_PASSWORD") ?: keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (storePath != null) signingConfig = signingConfigs.getByName("zol")
        }
        debug {
            if (storePath != null) signingConfig = signingConfigs.getByName("zol")
        }
    }
    // the release build skips the extra lint pass: it downloads more tools and the tests already guard the code
    lint {
        checkReleaseBuilds = false
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