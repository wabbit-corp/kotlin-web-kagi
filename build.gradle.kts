import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

group   = "one.wabbit"
version = "1.1.0"

plugins {
    kotlin("jvm") version "2.0.20"

    kotlin("plugin.serialization") version "2.0.20"

    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "one.wabbit"
            artifactId = "kotlin-web-kagi"
            version = "1.1.0"
            from(components["java"])
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")

    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-client-serialization:2.3.13")
    implementation("io.ktor:ktor-client-auth:2.3.13")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

tasks {
    withType<Test> {
        jvmArgs("-ea")

    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    jar {
        setProperty("zip64", true)

    }
}