plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.erfanesfahanian.key2fixer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.erfanesfahanian.key2fixer"
        // Android 10+. The Reels/Shorts paging feature needs Android 14 (API 34) at runtime,
        // which is checked in code; everything else works from Android 10.
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        // A fixed, committed debug key: every CI build has the same signature, so a newer APK
        // installs over an older one without uninstalling (which would lose your settings).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Set KEY2FIXER_KEYSTORE* env vars to sign a release build with your own key instead.
        create("release") {
            val ks = System.getenv("KEY2FIXER_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEY2FIXER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY2FIXER_KEY_ALIAS")
                keyPassword = System.getenv("KEY2FIXER_KEY_PASSWORD")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
