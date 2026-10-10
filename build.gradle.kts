import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.4.10"
    `maven-publish`
}

group = "io.github.team-sneakymouse"

version =
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
    }.standardOutput.asText.map { commit ->
        val (timestamp, hash) = commit.trim().split(":", limit = 2)
        val date =
            DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(timestamp.toLong()))
        "$date-$hash"
    }.get()

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://maven.enginehub.org/repo/")
    }
    mavenCentral()
    maven("https://maven.sneakyrp.com/releases")
}

val coreProtectJar =
    file("libs/CoreProtect-23.3.jar").takeIf { it.exists() }
        ?: error("CoreProtect jar not found. Put CoreProtect-23.3.jar in libs/.")

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.117-stable")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.18") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.18") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.0") {
        isTransitive = false
    }
    compileOnly(files(coreProtectJar))
    compileOnly("io.github.team-sneakymouse:magicspells-core:2026.10.09-17f579714ffe") {
        isTransitive = false
    }
    compileOnly("io.github.team-sneakymouse:sneakybagofholding:2026.10.09-a79a55f6d44b") {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:26.2.build.117-stable")
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks {
    processResources {
        inputs.property("version", project.version.toString())
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version.toString())
        }
    }

    jar {
        archiveBaseName.set(rootProject.name)
        archiveFileName.set("${rootProject.name}-${project.version}.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        })
    }

    test {
        useJUnitPlatform()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "sneakygrounditems"
            from(components["java"])
            pom {
                name.set("SneakyGroundItems")
                description.set("Dedicated Paper plugin for custom placed ground items.")
                url.set("https://github.com/Team-Sneakymouse/SneakyGroundItems")
                scm {
                    url.set("https://github.com/Team-Sneakymouse/SneakyGroundItems")
                    connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyGroundItems.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sneakyrp"
            url = uri("https://maven.sneakyrp.com/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<org.gradle.authentication.http.BasicAuthentication>("basic")
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.check)
}
