import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins { kotlin("multiplatform") version "2.4.0" }

kotlin {
    jvmToolchain(21)
    jvm()
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()
    sourceSets {
        val updaterVersion = providers.gradleProperty("updaterVersion").get()
        commonMain.dependencies { implementation("io.github.sensefy:updater-core:$updaterVersion") }
        jvmMain.dependencies { implementation("io.github.sensefy:updater-jvm:$updaterVersion") }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.register("verifyPublishedDependencies") {
    dependsOn(
        "jvmTest",
        "compileKotlinMacosArm64",
        "compileKotlinMacosX64",
        "compileKotlinLinuxX64",
        "compileKotlinMingwX64",
    )
    doLast {
        val components =
            configurations["jvmTestRuntimeClasspath"].incoming.resolutionResult.allComponents
        val modules = components.mapNotNull { it.id as? ModuleComponentIdentifier }
        check(modules.any { it.group == "io.github.sensefy" && it.module == "updater-jvm" })
        check(modules.any { it.group == "io.github.sensefy" && it.module == "updater-core-jvm" })
        check(components.none { it.id.displayName.contains("project :updater-") })
    }
}
