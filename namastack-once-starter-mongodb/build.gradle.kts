plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-once-starter-mongodb"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":namastack-once-mongodb"))
    api(libs.spring.boot.starter.data.mongodb)
}
