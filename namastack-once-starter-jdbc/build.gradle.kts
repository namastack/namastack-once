plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-once-starter-jdbc"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":namastack-once-jdbc"))
    api(libs.spring.boot.starter.jdbc)
}
