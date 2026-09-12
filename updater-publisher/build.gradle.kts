plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass = "saien.updater.publisher.MainKt" }

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from("THIRD_PARTY_NOTICES.md")
        }
    }
}

dependencies {
    implementation(project(":updater-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}
