plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.hawkiee.firestickhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hawkiee.firestickhome"
        minSdk = 25
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // A keystore kept in the repo so every build can update the last one in place.
    // It only signs this hobby app; it protects nothing else.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "firestickhome"
            keyAlias = "firestickhome"
            keyPassword = "firestickhome"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // QR codes for Settings › Back up & restore.
    implementation("com.google.zxing:core:3.5.3")
}
