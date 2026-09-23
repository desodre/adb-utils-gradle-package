plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.desodre.adbutils.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.desodre.adbutils.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("io.github.desodre:adb-utils:${providers.gradleProperty("adbUtilsVersion").getOrElse("0.2.0")}")
}
