plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        val updaterVersion = rootProject.file("../../VERSION").readText().trim()
        commonMain.dependencies {
            implementation("io.github.sensefy:updater-core:$updaterVersion")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation("io.github.sensefy:updater-jvm:$updaterVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
        }

        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
        }
    }
}
