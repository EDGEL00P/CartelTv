plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// Reproducible builds: every project resolves exactly the versions in its
// gradle.lockfile (regenerate with `./gradlew dependencies --write-locks`).
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
