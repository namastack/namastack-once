plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

description = "namastack-once-observability"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.micrometer.observation)
    api(project(":namastack-once-observability-api"))
    implementation(project(":namastack-once-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation("org.springframework.boot:spring-boot-micrometer-observation")
    testImplementation(project(":namastack-once-starter-jdbc"))
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.h2)
    testImplementation(libs.mockk)
    testImplementation(libs.micrometer.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
