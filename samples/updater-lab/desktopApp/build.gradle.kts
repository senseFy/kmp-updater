import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "saien.updater.lab.desktop.MainKt"

        nativeDistributions {
            modules(
                "java.management",
                "java.naming",
                "jdk.crypto.ec",
                "java.xml",
                "java.desktop",
                "jdk.unsupported",
            )
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Updater Lab"
            packageVersion = "1.0.0"
            vendor = "saien.updater"

            macOS {
                bundleID = "saien.updater.lab"
                minimumSystemVersion = "13.0"
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
            }
            windows {
                upgradeUuid = "1AC266C7-266A-3B11-AA11-26A6C7EBF8B5"
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
            }
        }
    }
}
