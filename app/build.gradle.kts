plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ovh.tenjo.kidphone"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "ovh.tenjo.kidphone"
        minSdk = 29
        targetSdk = 36
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    signingConfigs {
        // The key Kid Phone was first installed with: this PC's Android debug key. Android only
        // accepts updates signed with the same key. KIDPHONE_KEYSTORE can point at a copy elsewhere.
        create("kidphone") {
            storeFile = file(
                System.getenv("KIDPHONE_KEYSTORE") ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            )
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // A test-only device owner can be removed from a computer ("adb shell dpm
            // remove-active-admin"), which is handy while developing.
            manifestPlaceholders["testOnly"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Releases update themselves from GitHub, and Android refuses to install a test-only
            // app that way, so release builds are normal apps.
            manifestPlaceholders["testOnly"] = "false"
            // Sideloaded, never published. Same key as the debug builds, so either installs over the other.
            signingConfig = signingConfigs.getByName("kidphone")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
