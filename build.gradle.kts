@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.jreleaser)
    `maven-publish`
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

group = "io.modelcontextprotocol"
version = "0.5.0-maestro1"

val mainSourcesJar = tasks.register<Jar>("mainSourcesJar") {
    archiveClassifier.set("sources")
    from(kotlin.sourceSets.getByName("commonMain").kotlin)
}

publishing {
    val javadocJar = configureEmptyJavadocArtifact()

    publications.withType(MavenPublication::class).all {
        if (name.contains("jvm", ignoreCase = true)) {
            artifact(javadocJar)
        }
        pom.configureMavenCentralMetadata()
        signPublicationIfKeyPresent()
    }

    repositories {
        maven(url = layout.buildDirectory.dir("staging-deploy"))
    }
}

jreleaser {
    gitRootSearch.set(true)
    strict.set(true)

    signing {
        active.set(Active.ALWAYS)
        armored.set(true)
        artifacts.set(true)
    }

    deploy {
        active.set(Active.ALWAYS)
        maven {
            active.set(Active.ALWAYS)
            mavenCentral {
                val ossrh by creating {
                    active.set(Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    applyMavenCentralRules.set(false)
                    maxRetries.set(240)
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.path)
                    // workaround: https://github.com/jreleaser/jreleaser/issues/1784
                    kotlin.targets.forEach { target ->
                        if (target !is KotlinJvmTarget && target !is KotlinAndroidTarget && target !is KotlinMetadataTarget) {
                            val klibArtifactId = "${name}-${target.name.toLowerCase()}"
                            artifactOverride {
                                artifactId.set(klibArtifactId)
                                jar.set(false)
                                verifyPom.set(false)
                                sourceJar.set(false)
                                javadocJar.set(false)
                            }
                        }
                    }
                }
            }
        }
    }

    release {
        github {
            skipRelease.set(true)
            skipTag.set(true)
            overwrite.set(false)
            token.set("none")
        }
    }
}

fun MavenPom.configureMavenCentralMetadata() {
    name by project.name
    description by "Kotlin implementation of the Model Context Protocol (MCP)"
    url by "https://github.com/modelcontextprotocol/kotlin-sdk"

    licenses {
        license {
            name by "MIT License"
            url by "https://github.com/modelcontextprotocol/kotlin-sdk/blob/main/LICENSE"
            distribution by "repo"
        }
    }

    developers {
        developer {
            id by "Anthropic"
            name by "Anthropic Team"
            organization by "Anthropic"
            organizationUrl by "https://www.anthropic.com"
        }
    }

    scm {
        url by "https://github.com/modelcontextprotocol/kotlin-sdk"
        connection by "scm:git:git://github.com/modelcontextprotocol/kotlin-sdk.git"
        developerConnection by "scm:git:git@github.com:modelcontextprotocol/kotlin-sdk.git"
    }
}

fun configureEmptyJavadocArtifact(): org.gradle.jvm.tasks.Jar {
    val javadocJar by project.tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        // contents are deliberately left empty
        // https://central.sonatype.org/publish/requirements/#supply-javadoc-and-sources
    }
    return javadocJar.get()
}

fun MavenPublication.signPublicationIfKeyPresent() {
    val keyId = project.getSensitiveProperty("SIGNING_KEY_ID")
    val signingKey = project.getSensitiveProperty("SIGNING_KEY_PRIVATE")
    val signingKeyPassphrase = project.getSensitiveProperty("SIGNING_PASSPHRASE")

    if (!signingKey.isNullOrBlank()) {
        the<SigningExtension>().apply {
            useInMemoryPgpKeys(keyId, signingKey, signingKeyPassphrase)

            sign(this@signPublicationIfKeyPresent)
        }
    }
}

fun Project.getSensitiveProperty(name: String?): String? {
    if (name == null) {
        error("Expected not null property '$name' for publication repository config")
    }

    return project.findProperty(name) as? String
        ?: System.getenv(name)
        ?: System.getProperty(name)
}

infix fun <T> Property<T>.by(value: T) {
    set(value)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

abstract class GenerateLibVersionTask @Inject constructor(
    @get:Input val libVersion: String,
    @get:OutputDirectory val sourcesDir: File,
) : DefaultTask() {
    @TaskAction
    fun generate() {
        val sourceFile = File(sourcesDir.resolve("io/modelcontextprotocol/kotlin/sdk"), "LibVersion.kt")

        if (!sourceFile.exists()) {
            sourceFile.parentFile.mkdirs()
            sourceFile.createNewFile()
        }

        sourceFile.writeText(
            """
            package io.modelcontextprotocol.kotlin.sdk

            public const val LIB_VERSION: String = "$libVersion"

            """.trimIndent()
        )
    }
}

dokka {
    moduleName.set("MCP Kotlin SDK")

    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/modelcontextprotocol/kotlin-sdk")
            remoteLineSuffix.set("#L")
            documentedVisibilities(VisibilityModifier.Public)
        }
    }
    dokkaPublications.html {
        outputDirectory.set(project.layout.projectDirectory.dir("docs"))
    }
}

val sourcesDir = File(project.layout.buildDirectory.asFile.get(), "generated-sources/libVersion")

val generateLibVersionTask =
    tasks.register<GenerateLibVersionTask>("generateLibVersion", version.toString(), sourcesDir)

kotlin {
    jvm {
        jvmToolchain(11)
        withJava()
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    explicitApi = ExplicitApiMode.Strict

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateLibVersionTask.map { it.sourcesDir })
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.cio)
                api(libs.ktor.server.cio)
                implementation(libs.kotlin.logging)
                implementation(libs.kotlinx.io)
                implementation(libs.kotlinx.atomicfu)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
                implementation(libs.mockk)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }
    }
}
