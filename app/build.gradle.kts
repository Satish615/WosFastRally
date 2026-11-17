plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStore = System.getenv("WOS_KEYSTORE_PATH")
val signingAlias = System.getenv("WOS_KEY_ALIAS")
val signingStorePassword = System.getenv("WOS_KEYSTORE_PASSWORD")
val signingKeyPassword = System.getenv("WOS_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStore, signingAlias, signingStorePassword, signingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.example.wosfastrally"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wosfastrally"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("WOS_VERSION_CODE")?.toIntOrNull() ?: 4
        versionName = System.getenv("WOS_VERSION_NAME") ?: "0.4"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("persistentRelease") {
                storeFile = file(signingStore!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias!!
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
