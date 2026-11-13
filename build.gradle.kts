import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish) apply false
    id("jacoco-report-aggregation")
    jacoco
}

dependencies {
    jacocoAggregation(project(":namastack-once-api"))
    jacocoAggregation(project(":namastack-once-core"))
    jacocoAggregation(project(":namastack-once-observability"))
    jacocoAggregation(project(":namastack-once-jdbc"))
    jacocoAggregation(project(":namastack-once-mongodb"))
    jacocoAggregation(project(":namastack-once-integration-tests"))
}

val javaVersion = 17
val jvmTargetVersion = JvmTarget.fromTarget(javaVersion.toString())
val isRelease = project.hasProperty("release") && project.property("release") == "true"
val isCi = providers.environmentVariable("CI").getOrElse("false").toBoolean()

allprojects {
    group = "io.namastack"
    version = "0.1.0" + if (!isRelease) "-SNAPSHOT" else ""

    repositories {
        mavenLocal()
        mavenCentral()

        maven {
            url = uri("https://repo.spring.io/snapshot")
            mavenContent {
                snapshotsOnly()
            }
        }
    }

    apply { plugin("org.jlleitschuh.gradle.ktlint") }

    configurations.matching { it.name == "dokkaJavadocGeneratorRuntime" }.configureEach {
        // Korte brings legacy stdlib artifacts into Dokka's JVM runtime, breaking Gradle 9.9
        // dependency graph serialization. Their APIs are already part of kotlin-stdlib.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

kotlin {
    jvmToolchain(javaVersion)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("testCodeCoverageReport"))
}

subprojects {
    if (name != "namastack-once-integration-tests") {
        apply(plugin = "org.jetbrains.dokka-javadoc")
        apply(plugin = "com.vanniktech.maven.publish")
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        if (isCi) {
            outputs.doNotCacheIf("Tests must execute in CI") { true }
            outputs.upToDateWhen { false }
        }

        testLogging {
            exceptionFormat = FULL
            showStandardStreams = false
            events(SKIPPED, FAILED)
        }
    }

    tasks.withType<JavaCompile> {
        options.release.set(javaVersion)
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(jvmTargetVersion)
        }
    }

    afterEvaluate {
        plugins.withId("java") {
            java {
                withSourcesJar()
            }
        }

        if (name == "namastack-once-integration-tests") return@afterEvaluate

        dokka {
            moduleName = project.name
        }

        if (!isCi) {
            tasks.build {
                finalizedBy(tasks.named("publishToMavenLocal"))
            }
        }

        configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            coordinates(project.group.toString(), project.name, project.version.toString())

            pom {
                name.set(project.name)
                description.set(project.description ?: "Namastack Once Module")
                url.set("https://github.com/namastack/namastack-once")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("rolandbeisel")
                        name.set("Roland Beisel")
                        email.set("info@rolandbeisel.de")
                        url.set("https://rolandbeisel.de")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/namastack/namastack-once.git")
                    developerConnection.set("scm:git:ssh://github.com/namastack/namastack-once.git")
                    url.set("https://github.com/namastack/namastack-once")
                }
            }
        }
    }
}
