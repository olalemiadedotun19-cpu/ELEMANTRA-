plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase is switched on only when app/google-services.json is present, so the build
// still succeeds before that file has been added.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

// Codemagic exposes the signing keystore through these variables when the workflow
// declares `android_signing`. Without them the release build is signed with the
// debug key, so it still installs for testing (but cannot be uploaded to Google Play).
val hasReleaseKeystore = !System.getenv("CM_KEYSTORE_PATH").isNullOrBlank()

android {
    namespace = "com.dotcon.elemantra"
    compileSdk = 35

    defaultConfig {
        // Must match the package name registered in Firebase (google-services.json).
        applicationId = "com.elemantra.game"
        minSdk = 24
        targetSdk = 35
        // Codemagic increments BUILD_NUMBER on every build, which Google Play needs.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(System.getenv("CM_KEYSTORE_PATH"))
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Thin WebView shell: there is no meaningful code to shrink, and the game
            // itself is the HTML asset, so shrinking is switched off on purpose.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The game page is a ~31 MB HTML file. Storing it uncompressed lets Android
    // stream it straight from the APK without inflating it on every launch.
    androidResources {
        noCompress += listOf("html")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    if (hasFirebaseConfig) {
        // The BoM keeps all Firebase libraries on compatible versions (no versions needed below).
        implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
        implementation("com.google.firebase:firebase-analytics")
    }
}
