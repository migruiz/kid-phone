plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "ovh.tenjo.kidphone.parent"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "ovh.tenjo.kidphone.parent"
        minSdk = 29
        targetSdk = 36
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        buildConfigField("String", "HUB_URL", "\"https://kidphone.tenjotech.com\"")
        manifestPlaceholders["cleartext"] = "false"
    }

    buildTypes {
        debug {
            // For testing at home before the internet address works:
            //   gradlew :parent:assembleDebug -PparentHubUrl=http://192.168.0.11:8775
            providers.gradleProperty("parentHubUrl").orNull?.let {
                buildConfigField("String", "HUB_URL", "\"$it\"")
                manifestPlaceholders["cleartext"] = "true"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sideloaded from GitHub releases. Same key as Kid Phone (this PC's debug key, also a
            // GitHub secret), so updates install over the previous version.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.fragment.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
