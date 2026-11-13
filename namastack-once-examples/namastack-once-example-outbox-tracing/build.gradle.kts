plugins {
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

group = "io.namastack"
version = "0.0.1-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
kotlin { jvmToolchain(17) }
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.namastack.once.bom))
    implementation("io.namastack:namastack-once-starter-jdbc")
    implementation("io.namastack:namastack-once-observability")
    implementation(platform("io.namastack:namastack-outbox-bom:1.11.1-SNAPSHOT"))
    implementation("io.namastack:namastack-outbox-starter-jdbc")
    implementation("io.namastack:namastack-outbox-observability")
    implementation("io.namastack:namastack-outbox-kafka")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.h2)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.withType<Test> { useJUnitPlatform() }
