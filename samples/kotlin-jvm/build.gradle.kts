plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    providers.gradleProperty("adbUtilsRepository").orNull?.let { localRepository ->
        maven { url = uri(localRepository) }
    }
    mavenCentral()
}

dependencies {
    implementation("io.github.desodre:adb-utils:${providers.gradleProperty("adbUtilsVersion").getOrElse("0.2.0")}")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("example.MainKt") }
tasks.test { useJUnitPlatform() }
