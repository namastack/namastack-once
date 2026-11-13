rootProject.name = "namastack-once"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        maven {
            url = uri("https://repo.spring.io/snapshot")
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}

include("namastack-once-api")
include("namastack-once-core")
include("namastack-once-jdbc")
include("namastack-once-mongodb")
include("namastack-once-starter-jdbc")
include("namastack-once-starter-mongodb")
include("namastack-once-bom")
include("namastack-once-integration-tests")

include("namastack-once-observability-api")
include("namastack-once-observability")
