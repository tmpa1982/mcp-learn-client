val mcpVersion = "0.7.2"
val anthropicVersion = "2.9.0"

plugins {
    kotlin("jvm") version "2.2.20"
}

group = "net.tmpa.mcp.learn"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:${mcpVersion}")
    implementation("com.azure:azure-ai-openai:1.0.0-beta.16")
    implementation("com.azure:azure-identity:1.18.1")
    implementation("com.anthropic:anthropic-java:${anthropicVersion}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

kotlin {
    jvmToolchain(24)
}