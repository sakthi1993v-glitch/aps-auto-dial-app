plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apsconnect.autodial"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.apsconnect.autodial"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
    }

    // 2026-08-07 -- THE UPDATE-LOOP FIX. Read this before touching it.
    //
    // Munnadi inga signingConfig-ē illa. `gradle assembleDebug` GitHub Actions-la odumbodhu,
    // andha fresh machine-la debug.keystore illa -> Gradle OVVORU BUILD-KUM pudhu random key
    // uruvaakki sign pannuchu. v1.4 / v1.5 / v1.6 moonum VERA VERA certificate:
    //   v1.4 a687b5e3…   v1.5 31198bab…   v1.6 9fa9af26…
    // Android update panna pazhaya key-um pudhu key-um ondraa irukkanum. Vera key-na
    // "App not installed" nu maruththudum -> app pazhaya version-lye irukkum -> updater
    // "pudhu version irukku" nu MARUPADI kekkum. Adhu dhaan andha mudivillaadha update loop.
    //
    // Ippo ORE fixed key: ANDROID_KEYSTORE_B64 (GitHub secret, base64 PKCS12).
    // Local backup + recovery note: C:\Users\sakth\aps-app-signing\
    // Indha key-oda cert SHA256 a90e23a4… -- ella future APK-um idhē cert-a kaattanum.
    // Secret set aagalna (fork / local build) signing config-ai muzhusa vittudurom, illaati
    // build fail aagum -- appo Gradle default debug key-a use pannum, pazhaya nadatai.
    signingConfigs {
        create("shared") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        // debug-um release-um ORE key-la sign aaganum. Staff-ku pōradhu debug APK
        // (assembleDebug), adhanaala debug-ku idha pōdaama indha fix vēlaiyē seiyaadhu.
        debug {
            signingConfig = signingConfigs.getByName(
                if (System.getenv("ANDROID_KEYSTORE_PATH") != null) "shared" else "debug"
            )
        }
        release {
            isMinifyEnabled = false
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
