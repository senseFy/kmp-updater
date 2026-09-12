pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the JDK 21 toolchain automatically when the JDK running Gradle differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "updater-lab"

include(":shared")

include(":desktopApp")

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.sensefy:updater-core")).using(project(":updater-core"))
        substitute(module("io.github.sensefy:updater-jvm")).using(project(":updater-jvm"))
    }
}
