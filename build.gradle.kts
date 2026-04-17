plugins {
    kotlin("jvm") version "2.2.21"
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://maven.enginehub.org/repo/")
    }
    mavenCentral()
}

val coreProtectJar = sequenceOf(
    file("libs/CoreProtect-22.1.jar"),
    file("C:\\Users\\DaniDipp\\Downloads\\1.20\\CoreProtect-22.1.jar"),
).firstOrNull { it.exists() }
    ?: error("CoreProtect jar not found. Put CoreProtect-22.1.jar in libs/ or update build.gradle.kts.")

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.13")
    compileOnly(files(coreProtectJar))

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
