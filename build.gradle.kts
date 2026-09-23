import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.GradleBuild

plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
}

group = "io.github.desodre"
version = file("VERSION").readText().trim()

val artifactName = "adb-utils"
val projectUrl = "https://github.com/desodre/adb-utils-gradle-package"
val publicationCheckRepository = layout.buildDirectory.dir("publication-check-repository")
val releaseRepository = layout.buildDirectory.dir("release-repository")
val signingKey = providers.gradleProperty("signingKey")
val signingPassword = providers.gradleProperty("signingPassword")

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

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    jvmToolchain(17)
    explicitApi()
    abiValidation()
}
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
            artifactId = artifactName
            artifact(dokkaJavadocJar)
            pom {
                name.set("adb-utils")
                description.set("Kotlin/JVM SDK for direct communication with the Android Debug Bridge server")
                url.set(projectUrl)
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
                    connection.set("scm:git:${projectUrl}.git")
                    developerConnection.set("scm:git:ssh://git@github.com/desodre/adb-utils-gradle-package.git")
                    url.set(projectUrl)
                }
            }
        }
    }
    repositories {
        maven {
            name = "publicationCheck"
            url = uri(publicationCheckRepository)
        }
        maven {
            name = "releaseBundle"
            url = uri(releaseRepository)
        }
    }
}

signing {
    isRequired = false
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
    sign(publishing.publications["maven"])
}

val cleanPublicationCheckRepository = tasks.register<Delete>("cleanPublicationCheckRepository") {
    delete(publicationCheckRepository)
}

val cleanReleaseRepository = tasks.register<Delete>("cleanReleaseRepository") {
    delete(releaseRepository)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    when (repository.name) {
        "publicationCheck" -> dependsOn(cleanPublicationCheckRepository)
        "releaseBundle" -> {
            dependsOn(cleanReleaseRepository)
            doFirst {
                check(!version.toString().endsWith("-SNAPSHOT")) {
                    "Release bundles require a non-SNAPSHOT version"
                }
                check(signingKey.orNull?.isNotBlank() == true) {
                    "Release bundles require the signingKey Gradle property"
                }
                check(signingPassword.isPresent) {
                    "Release bundles require the signingPassword Gradle property"
                }
            }
        }
    }
}

fun validateMavenRepository(repositoryRoot: File, requireSignatures: Boolean) {
    val versionDirectory = repositoryRoot.resolve(
        "${project.group.toString().replace('.', '/')}/$artifactName/${project.version}",
    )
    check(versionDirectory.isDirectory) { "Missing Maven version directory: $versionDirectory" }

    val baseName = "$artifactName-${project.version}"
    val requiredArtifacts = listOf(
        "$baseName.jar",
        "$baseName-sources.jar",
        "$baseName-javadoc.jar",
        "$baseName.pom",
        "$baseName.module",
    )
    requiredArtifacts.forEach { name ->
        val artifact = versionDirectory.resolve(name)
        check(artifact.isFile && artifact.length() > 0) { "Missing or empty publication artifact: $artifact" }
        listOf("md5", "sha1").forEach { algorithm ->
            check(versionDirectory.resolve("$name.$algorithm").isFile) {
                "Missing $algorithm checksum for $name"
            }
        }
        if (requireSignatures) {
            val signature = versionDirectory.resolve("$name.asc")
            check(signature.isFile && signature.length() > 0) { "Missing or empty PGP signature for $name" }
        }
    }

    val pom = versionDirectory.resolve("$baseName.pom").readText()
    listOf(
        "<groupId>${project.group}</groupId>",
        "<artifactId>$artifactName</artifactId>",
        "<version>${project.version}</version>",
        "<name>adb-utils</name>",
        "<description>",
        "<url>$projectUrl</url>",
        "<licenses>",
        "<developers>",
        "<scm>",
    ).forEach { requiredMetadata ->
        check(requiredMetadata in pom) { "Generated POM is missing $requiredMetadata" }
    }
}

val validatePublication = tasks.register("validatePublication") {
    group = "verification"
    description = "Builds and validates an unsigned Maven repository for CI and local checks"
    dependsOn("publishMavenPublicationToPublicationCheckRepository")
    doLast { validateMavenRepository(publicationCheckRepository.get().asFile, requireSignatures = false) }
}

val validateReleaseMetadata = tasks.register("validateReleaseMetadata") {
    group = "verification"
    description = "Checks VERSION and CHANGELOG metadata used by tagged releases"
    inputs.file(layout.projectDirectory.file("VERSION"))
    inputs.file(layout.projectDirectory.file("CHANGELOG.md"))
    doLast {
        val releaseVersion = project.version.toString()
        check(releaseVersion.matches(Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"))) {
            "VERSION must contain a stable Semantic Versioning value, received: $releaseVersion"
        }
        val changelogHeading = Regex("(?m)^## ${Regex.escape(releaseVersion)}(?:\\s|$)")
        check(changelogHeading.containsMatchIn(layout.projectDirectory.file("CHANGELOG.md").asFile.readText())) {
            "CHANGELOG.md is missing a section for $releaseVersion"
        }
    }
}

val validateReleaseBundle = tasks.register("validateReleaseBundle") {
    group = "publishing"
    description = "Builds and validates a signed Maven Central release repository"
    dependsOn("publishMavenPublicationToReleaseBundleRepository")
    doLast { validateMavenRepository(releaseRepository.get().asFile, requireSignatures = true) }
}

tasks.register<Zip>("releaseBundle") {
    group = "publishing"
    description = "Creates the signed archive that can be uploaded to Maven Central"
    dependsOn(validateReleaseBundle, validateReleaseMetadata)
    archiveFileName.set("$artifactName-${project.version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(releaseRepository) {
        exclude("**/maven-metadata.xml*")
        exclude("**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    }
}

tasks.register<GradleBuild>("consumerTest") {
    group = "verification"
    description = "Builds the standalone Kotlin/JVM sample against the generated Maven repository"
    dependsOn(validatePublication)
    dir = file("samples/kotlin-jvm")
    tasks = listOf("clean", "test")
    startParameter.projectProperties = mapOf(
        "adbUtilsRepository" to publicationCheckRepository.get().asFile.absolutePath,
        "adbUtilsVersion" to project.version.toString(),
    )
}
