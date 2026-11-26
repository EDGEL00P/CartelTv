plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.carteltv.tv.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    // The app's release type is signed outside Gradle, so the generated benchmark types need an installable key.
    buildTypes {
        create("benchmarkRelease") { signingConfig = signingConfigs.getByName("debug") }
        create("nonMinifiedRelease") { signingConfig = signingConfigs.getByName("debug") }
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
}
