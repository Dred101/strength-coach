plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "app.repcoach"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.anthropic:anthropic-java:2.73.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Optional: point at a real Hevy export to run the import check against it.
    System.getenv("HEVY_CSV")?.let { environment("HEVY_CSV", it) }
}
