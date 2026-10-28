plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor build GitHub Actions dipakai sebagai versionCode, jadi APK baru bisa dipasang menimpa yang lama.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.yd1bux.gk2a"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yd1bux.gk2a"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Keystore rilis diambil dari GitHub Secret (lihat README). Tanpa itu, APK ditandatangani
    // dengan debug keystore runner, yang berbeda tiap build.
    val keystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "yd1bux"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
