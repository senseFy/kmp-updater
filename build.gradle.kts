import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("com.diffplug.spotless") version "8.1.0"
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktfmt("0.64").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktfmt("0.64").kotlinlangStyle()
    }
}

tasks.register("verify") {
    dependsOn(
        "spotlessCheck",
        ":updater-core:jvmTest",
        ":updater-jvm:test",
        ":updater-publisher:test",
    )
}

allprojects {
    group = "io.github.sensefy"
    version = rootProject.file("VERSION").readText().trim()
}

subprojects {
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "Release"
                    url =
                        rootProject.layout.buildDirectory
                            .dir("release-repository")
                            .get()
                            .asFile
                            .toURI()
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("KMP Updater — ${project.name}")
                    description.set(
                        "Signed update feeds and platform adapters for Kotlin Multiplatform applications."
                    )
                    url.set("https://github.com/senseFy/kmp-updater")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("senseFy")
                            name.set("senseFy")
                            url.set("https://github.com/senseFy")
                        }
                    }
                    scm {
                        url.set("https://github.com/senseFy/kmp-updater")
                        connection.set("scm:git:https://github.com/senseFy/kmp-updater.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/senseFy/kmp-updater.git"
                        )
                    }
                }
            }
        }
    }
}

tasks.register<Zip>("mavenRepositoryZip") {
    dependsOn(
        ":updater-core:publishAllPublicationsToReleaseRepository",
        ":updater-jvm:publishAllPublicationsToReleaseRepository",
    )
    from(layout.buildDirectory.dir("release-repository")) { into("repository") }
    from("LICENSE", "README.md")
    from("docs") { into("docs") }
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    archiveFileName.set("kmp-updater-${project.version}-maven.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
