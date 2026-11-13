plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "io.namastack"
version = "0.0.1-SNAPSHOT"
description = "namastack-once-example-postgresql"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.namastack.once.bom))
    implementation(libs.namastack.once.starter.jdbc)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> { useJUnitPlatform() }
