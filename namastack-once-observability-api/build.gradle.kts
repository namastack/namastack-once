plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

description = "namastack-once-observability-api"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.micrometer.observation)
}
