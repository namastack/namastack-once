plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-once-jdbc"

dependencies {
    api(project(":namastack-once-core"))
    api(platform(libs.spring.boot.bom))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.jdbc)
    compileOnly(libs.slf4j.api)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
