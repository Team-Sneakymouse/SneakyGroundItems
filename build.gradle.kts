import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.4.10"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
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
    maven {
        name = "playpro-repo"
        url = uri("https://maven.playpro.com")
    }
    mavenCentral()
    maven("https://maven.sneakyrp.com/releases")
}

dependencies {
    implementation(kotlin("stdlib"))
    paperweight.paperDevBundle("26.2.build.117-stable")
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
    // 23.3 was vendored; PlayPro Maven publishes through 23.2 / 24.x (listeners used by audit logger present).
    compileOnly("net.coreprotect:coreprotect:23.2") {
        isTransitive = false
    }
    compileOnly("io.github.team-sneakymouse:magicspells:2026.10.15-f0a44ae377a3") {
        isTransitive = false
    }
    compileOnly("io.github.team-sneakymouse:sneakybagofholding:2026.10.09-a79a55f6d44b") {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.117-stable")
}

configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
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
        workingDir = layout.buildDirectory.dir("test-runtime").get().asFile
        doFirst { workingDir.mkdirs() }
        // Packet adapter tests exercise the actual pinned Paper classes without bundling them.
        classpath += sourceSets.main.get().compileClasspath
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
                properties.put("sneakyrp.deployment.type", "paper-plugin")
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
