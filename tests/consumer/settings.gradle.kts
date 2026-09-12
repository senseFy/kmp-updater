pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "PublishedUpdater"
                    url = uri(providers.gradleProperty("releaseRepository").get())
                }
            }
            filter { includeGroup("io.github.sensefy") }
        }
        mavenCentral()
    }
}

rootProject.name = "published-updater-consumer"
