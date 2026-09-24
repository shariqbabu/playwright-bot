plugins {
    kotlin("jvm") version "2.0.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.automation"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Playwright
    implementation("com.microsoft.playwright:playwright:1.49.0")

    // Lightweight JSON serialization (Minimal RAM)
    implementation("com.google.code.gson:gson:2.11.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
}
