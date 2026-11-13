plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-once-mongodb"

dependencies {
    api(project(":namastack-once-core"))
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.data.mongodb)
    implementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.starter.data.mongodb.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
