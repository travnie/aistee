plugins {
    id("com.android.application")
}

android {
    namespace = "ais.tee"
    compileSdk = 36

    defaultConfig {
        applicationId = "ais.tee.experimental"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-experimental"
    }

    buildTypes {
        getByName("debug") {
            // Use Android's default debug keystore. The archive ships no signing key.
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity:1.10.1")
}
