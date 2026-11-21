plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenrec.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenrec.app"
        minSdk = 29
        targetSdk = 29
        versionCode = 4
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // FileProvider untuk berbagi dan mengedit hasil rekaman
    implementation("androidx.core:core:1.12.0")
}
