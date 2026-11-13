plugins {
    `java-platform`
}

description = "Namastack Once BOM"

dependencies {
    constraints {
        api(project(":namastack-once-observability-api"))
        api(project(":namastack-once-observability"))
        api(project(":namastack-once-api"))
        api(project(":namastack-once-core"))
        api(project(":namastack-once-jdbc"))
        api(project(":namastack-once-mongodb"))
        api(project(":namastack-once-starter-jdbc"))
        api(project(":namastack-once-starter-mongodb"))
    }
}
