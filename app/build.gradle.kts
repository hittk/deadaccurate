plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.deadaccurate.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.deadaccurate.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 17
        versionName = "0.4.5"
    }

    // One committed keystore signs every build type in every environment
    // (local, CI, cloud), so any newer APK installs over any older one —
    // no uninstalls between updates. The key is deliberately in-repo with a
    // known password: it exists for update continuity on test devices, not
    // secrecy. Generate a fresh private key before any store distribution.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/deadaccurate.keystore")
            storePassword = "deadaccurate"
            keyAlias = "deadaccurate"
            keyPassword = "deadaccurate"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric-based Compose UI tests need resources.
            isIncludeAndroidResources = true
            all {
                // Robolectric + Compose + the DSP fixture generators share
                // one test JVM; the default heap OOMs.
                it.maxHeapSize = "2g"
                // Lets captureToImage() work under Robolectric so the UI
                // preview renders (UiPreviewCapture) can produce PNGs.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
