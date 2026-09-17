plugins {
    id("com.android.application")
}

android {
    namespace = "com.supremeaiventures.marketscout"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.supremeaiventures.marketscout"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
