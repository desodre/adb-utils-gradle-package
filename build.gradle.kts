plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
}

group = "io.github.desodre"
version = "0.2.0"

repositories { mavenCentral() }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val adbTest = sourceSets.create("adbTest")
configurations[adbTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[adbTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies.add(adbTest.implementationConfigurationName, sourceSets.main.get().output)

tasks.register<Test>("adbTest") {
    description = "Runs opt-in tests against a local ADB Server and physical device"
    group = "verification"
    testClassesDirs = adbTest.output.classesDirs
    classpath = adbTest.runtimeClasspath
    useJUnitPlatform()
    onlyIf { providers.gradleProperty("adbTest").orNull == "true" }
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }
tasks.test { useJUnitPlatform() }

val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    dependsOn("dokkaGeneratePublicationJavadoc")
    from(layout.buildDirectory.dir("dokka/javadoc"))
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "adb-utils"
            artifact(dokkaJavadocJar)
            pom {
                name.set("adb-utils")
                description.set("Kotlin/JVM SDK for direct communication with the Android Debug Bridge server")
                url.set("https://github.com/desodre/adb-utils")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("desodre")
                        name.set("desodre")
                        url.set("https://github.com/desodre")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/desodre/adb-utils.git")
                    developerConnection.set("scm:git:ssh://git@github.com/desodre/adb-utils.git")
                    url.set("https://github.com/desodre/adb-utils")
                }
            }
        }
    }
    repositories {
        maven {
            name = "releaseBundle"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
}

signing {
    val key = providers.gradleProperty("signingKey").orNull
    val password = providers.gradleProperty("signingPassword").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications["maven"])
    }
}
