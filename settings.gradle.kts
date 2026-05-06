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
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("android/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "openclaw-android"

// Incluimos los módulos especificando su ruta real
include(":app")
project(":app").projectDir = file("android/app")

include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("android/terminal-emulator")

include(":terminal-view")
project(":terminal-view").projectDir = file("android/terminal-view")
