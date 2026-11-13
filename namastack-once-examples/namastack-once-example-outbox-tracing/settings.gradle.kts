pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "namastack-once-example-outbox-tracing"

providers.gradleProperty("onceSource").orNull?.let { sourcePath ->
    includeBuild(sourcePath)
}

providers.gradleProperty("outboxSource").orNull?.let { includeBuild(it) }
