plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-once-integration-tests"

dependencies {
    testImplementation(project(":namastack-once-starter-jdbc"))
    testImplementation(project(":namastack-once-starter-mongodb"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.mssqlserver)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mssql.jdbc)
    testRuntimeOnly(libs.oracle.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    if (!providers.gradleProperty("databaseTests").map(String::toBoolean).getOrElse(false)) {
        useJUnitPlatform { excludeTags("database") }
    }
}
