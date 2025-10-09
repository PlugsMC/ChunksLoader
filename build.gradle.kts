import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.plugins.JavaPlugin

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "bout2p1_ograines"

val pluginVersion = findProperty("pluginVersion")?.toString() ?: "1.0.0"
val releaseTag = findProperty("releaseTag")?.toString() ?: "v$pluginVersion"
val mcVersion = findProperty("mcVersion")?.toString() ?: "1.21.9"
val spigotApiVersion = findProperty("spigotApiVersion")?.toString() ?: "1.21.1-R0.1-SNAPSHOT"

version = pluginVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigotmc"
    }
    maven("https://repo.bluecolored.de/releases") {
        name = "bluecolored"
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("com.flowpowered:flow-math:1.0.3")
    compileOnly("de.bluecolored:bluemap-api:2.7.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME) {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(mapOf("release" to mapOf("tag" to releaseTag)))
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("ChunksLoader-$mcVersion-$releaseTag")
    archiveVersion.set("")
}

val shadowJarTask = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(shadowJarTask)
}

val assetsDir = layout.projectDirectory.dir("assets")

val copyShadowJarToAssets by tasks.registering(Copy::class) {
    dependsOn(shadowJarTask)
    dependsOn(tasks.named("jar"))
    from(shadowJarTask.flatMap { it.archiveFile })
    into(assetsDir)
}

tasks.build {
    dependsOn(copyShadowJarToAssets)
}

tasks.register("printVersion") {
    doLast { println(version) }
}

tasks.register("printReleaseTag") {
    doLast { println(releaseTag) }
}
