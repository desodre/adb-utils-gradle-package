pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("adbUtilsRepository").orNull?.let { localRepository ->
            maven { url = uri(localRepository) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "adb-utils-android-sample"
include(":app")
