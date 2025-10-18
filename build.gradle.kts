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
    implementation("com.anthropic:anthropic-java:${anthropicVersion}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}