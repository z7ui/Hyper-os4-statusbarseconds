plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.statusbarseconds"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.statusbarseconds"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
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
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}