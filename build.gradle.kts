import codegen.GenerateClasspathManifest
import codegen.GenerateConfigurationExtensions
import codegen.GenerateKotlinDependencyExtensions

import groovy.lang.GroovyObject

import org.gradle.api.file.FileCopyDetails
import org.gradle.api.internal.HasConvention
import org.gradle.api.publish.*
import org.gradle.api.publish.maven.*
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.GradleBuild
import org.gradle.jvm.tasks.Jar

import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig
import org.jfrog.gradle.plugin.artifactory.dsl.ResolverConfig

import java.io.File

buildscript {
    var kotlinProperties = java.util.Properties().apply {
        file("kotlin.properties").inputStream().use { load(it) }
    }
    var kotlinVersion: String by extra
    kotlinVersion = kotlinProperties.getProperty("version")
    var kotlinRepo: String by extra
    kotlinRepo = kotlinProperties.getProperty("repository")

    repositories {
        maven { setUrl(kotlinRepo) }
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

plugins {
    `maven-publish`
    id("com.jfrog.artifactory") version "4.1.1"
}

apply {
    plugin("kotlin")
}

group = "org.gradle"

version = "0.7.0-SNAPSHOT"

dependencies {
    compileOnly(gradleApi())

    compile("org.codehaus.groovy:groovy-all:2.4.7")
    compile("org.slf4j:slf4j-api:1.7.10")
    compile("javax.inject:javax.inject:1")
    compile("org.ow2.asm:asm-all:5.1")

    compile(kotlin("stdlib"))
    compile(kotlin("reflect"))
    compile(kotlin("compiler-embeddable"))

    testCompile(gradleTestKit())
    testCompile("junit:junit:4.12")
    testCompile("com.nhaarman:mockito-kotlin:0.6.0")
    testCompile("com.fasterxml.jackson.module:jackson-module-kotlin:2.7.5")
}

val sourceSets = the<JavaPluginConvention>().sourceSets
val mainSourceSet = sourceSets.getByName("main")!!

val jar = tasks.getByName("jar") as Jar
jar.apply {
    from(mainSourceSet.allSource)
    manifest.attributes.apply {
        put("Implementation-Title", "Gradle Script Kotlin")
        put("Implementation-Version", version)
    }
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components.getByName("java"))
        }
    }
}

// --- Enable automatic generation of API extensions -------------------
val apiExtensionsOutputDir = file("src/generated/kotlin")

val generateConfigurationExtensions = task<GenerateConfigurationExtensions>("generateConfigurationExtensions") {
    outputFile = File(apiExtensionsOutputDir, "org/gradle/script/lang/kotlin/ConfigurationsExtensions.kt")
}

val kotlinVersion: String by extra
val kotlinRepo: String by extra

val generateKotlinDependencyExtensions = task<GenerateKotlinDependencyExtensions>("generateKotlinDependencyExtensions") {
    outputFile = File(apiExtensionsOutputDir, "org/gradle/script/lang/kotlin/KotlinDependencyExtensions.kt")
    embeddedKotlinVersion = kotlinVersion
    gradleScriptKotlinRepository = kotlinRepo
}

val generateExtensions = task("generateExtensions") {
    dependsOn(generateConfigurationExtensions)
    dependsOn(generateKotlinDependencyExtensions)
}

(mainSourceSet as HasConvention).convention.getPlugin<KotlinSourceSet>().apply {
    kotlin.srcDir(apiExtensionsOutputDir)
}
val compileKotlin = tasks.getByName("compileKotlin")
compileKotlin.dependsOn(generateExtensions)


// -- Performance testing ----------------------------------------------
//
// 1. Creates a custom Gradle installation with latest gradle-script-kotlin jar
//
// 2. Benchmarks latest installation against configured wrapper
//
val customInstallationDir = file("$buildDir/custom/gradle-${gradle.gradleVersion}")

val copyCurrentDistro = task<Copy>("copyCurrentDistro") {
    description = "Copies the current Gradle distro into '$customInstallationDir'."

    from(gradle.gradleHomeDir)
    into(customInstallationDir)
    exclude("**/*kotlin*")

    // preserve last modified date on each file to make it easier
    // to check which files were patched by next step
    val copyDetails = mutableListOf<FileCopyDetails>()
    eachFile { copyDetails.add(this) }
    doLast {
        copyDetails.forEach { details ->
            val target = File(customInstallationDir, details.path)
            target.setLastModified(details.lastModified)
        }
    }

    // don't bother recreating it
    onlyIf { !customInstallationDir.exists() }
}


val customInstallation = task<Copy>("customInstallation") {
    description = "Copies latest gradle-script-kotlin snapshot over the custom installation."
    dependsOn(copyCurrentDistro)
    from(configurations.compile)
    from(jar)
    into("$customInstallationDir/lib")
}
val test = tasks.getByName("test")
test.dependsOn(customInstallation)

task<integration.Benchmark>("benchmark") {
    dependsOn(customInstallation)
    latestInstallation = customInstallationDir
}


// -- Integration testing ---------------------------------------------
//
// Checks a single sample, for instance:
//
//     check-hello-kotlin
//
tasks.addRule("Pattern: check-<SAMPLE>") { taskName ->
    if (taskName.startsWith("check-")) {
        val checkSample = task<integration.CheckSample>("$taskName-task") {
            dependsOn(customInstallation)
            installation = customInstallationDir
            sampleDir = file("samples/${taskName.removePrefix("check-")}")
        }
        task(taskName).dependsOn(checkSample)
    }
}

val checkSamples = task("checkSamples") {
    description = "Checks all samples"
    file("samples").listFiles().forEach {
        if (it.isDirectory && !it.name.contains("android")) {
            dependsOn("check-${it.name}")
        }
    }
}
val check = tasks.getByName("check")!!
check.dependsOn(checkSamples)

val prepareIntegrationTestFixtures = task<GradleBuild>("prepareIntegrationTestFixtures") {
    setDir(file("fixtures"))
}
// See #189
//test.dependsOn(prepareIntegrationTestFixtures)


// --- classpath.properties --------------------------------------------
val generatedResourcesDir = file("$buildDir/generate-resources/main")
task<GenerateClasspathManifest>("generateClasspathManifest") {
    outputDirectory = generatedResourcesDir
}
mainSourceSet.output.dir(mapOf("builtBy" to "generateClasspathManifest"), generatedResourcesDir)


// --- Configure publications ------------------------------------------
fun buildTagFor(version: String): String =
    when (version.substringAfterLast('-')) {
        "SNAPSHOT" -> "snapshot"
        in Regex("""M\d+[a-z]*$""") -> "milestone"
        else -> "release"
    }

configure<ArtifactoryPluginConvention> {
    setContextUrl("https://repo.gradle.org/gradle")
    publish(delegateClosureOf<PublisherConfig> {
        repository(delegateClosureOf<GroovyObject> {
            val targetRepoKey = "libs-${buildTagFor(project.version as String)}s-local"
            setProperty("repoKey", targetRepoKey)
            setProperty("username", project.findProperty("artifactory_user") ?: "nouser")
            setProperty("password", project.findProperty("artifactory_password") ?: "nopass")
            setProperty("maven", true)
        })
        defaults(delegateClosureOf<GroovyObject> {
            invokeMethod("publications", "mavenJava")
        })
    })
}

repositories {
    maven { setUrl("https://repo.gradle.org/gradle/libs-snapshots") }
    maven { setUrl(kotlinRepo) }
}


// --- Utility functions -----------------------------------------------
fun kotlin(module: String) = "org.jetbrains.kotlin:kotlin-$module:${extra["kotlinVersion"]}"

operator fun Regex.contains(s: String) = matches(s)
