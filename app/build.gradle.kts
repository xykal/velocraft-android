plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.velocraft.android"
    compileSdk = 34
    ndkVersion = project.findProperty("velocraft.ndkVersion")?.toString() ?: "27.0.12077973"

    defaultConfig {
        applicationId = "id.xyverse.minecraft"
        minSdk = 24 // Android 7.0+ (Supports 32-bit armeabi-v7a & 64-bit arm64-v8a)
        targetSdk = 34
        versionCode = 9
        versionName = "0.7.1-release"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-ffast-math", "-Wall", "-fno-exceptions")
                // Use c++_static so native libraries have ZERO external STL .so dependency issues
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = file("keystore/xyverse-official-release.p12")
            if (ksFile.exists()) {
                storeFile = ksFile
                storeType = "PKCS12"
                storePassword = System.getenv("XYVERSE_KEYSTORE_PASS") ?: "XyverseGlobal2026"
                keyAlias = System.getenv("XYVERSE_KEY_ALIAS") ?: "xyverse_official"
                keyPassword = System.getenv("XYVERSE_KEYSTORE_PASS") ?: "XyverseGlobal2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val ksFile = file("keystore/xyverse-official-release.p12")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val ksFile = file("keystore/xyverse-official-release.p12")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.tukaani:xz:1.9")
    implementation("org.apache.commons:commons-compress:1.26.1")
}
