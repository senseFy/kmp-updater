pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "kmp-updater"

include(":updater-core", ":updater-jvm", ":updater-publisher")
