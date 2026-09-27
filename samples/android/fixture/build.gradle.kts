plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.desodre.adbutils.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.desodre.adbutils.fixture"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}
