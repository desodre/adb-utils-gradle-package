plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
}

group = "org.desodre"
version = "0.2.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
