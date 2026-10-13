plugins {
    id("com.android.application")
}

android {
    namespace = "com.supremeaiventures.marketscout"
    compileSdk = 36
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)

    defaultConfig {
        applicationId = "com.supremeaiventures.marketscout"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    val signingValues = listOf("MARKET_SCOUT_KEYSTORE", "MARKET_SCOUT_STORE_PASSWORD", "MARKET_SCOUT_KEY_ALIAS", "MARKET_SCOUT_KEY_PASSWORD")
        .map { System.getenv(it).orEmpty() }
    require(signingValues.all { it.isEmpty() } || signingValues.none { it.isEmpty() }) {
        "Provide all four MARKET_SCOUT signing variables, or leave all unset for an unsigned local build."
    }
    if (signingValues.none { it.isEmpty() }) {
        signingConfigs.create("release") {
            storeFile = file(signingValues[0])
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }

    buildTypes {
        release {
            if (signingValues.none { it.isEmpty() }) signingConfig = signingConfigs.getByName("release")
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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}
