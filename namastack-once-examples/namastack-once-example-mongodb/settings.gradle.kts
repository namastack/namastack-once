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

rootProject.name = "namastack-once-example-mongodb"

providers.gradleProperty("onceSource").orNull?.let { sourcePath ->
    includeBuild(sourcePath)
}
