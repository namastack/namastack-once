plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-once-core"

dependencies {
    api(project(":namastack-once-api"))
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.spring.aop)
    implementation(libs.spring.expression)
    api(libs.spring.tx)
    implementation(libs.kotlin.reflect)
    compileOnly(libs.slf4j.api)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
