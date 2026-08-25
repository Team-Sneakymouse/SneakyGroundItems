plugins {
    kotlin("jvm") version "2.4.10"
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
    file("libs/CoreProtect-23.3.jar"),
    file("C:\\Users\\DaniDipp\\Downloads\\1.21.4\\CoreProtect-23.3.jar"),
).firstOrNull { it.exists() }
    ?: error("CoreProtect jar not found. Put CoreProtect-23.3.jar in libs/ or update build.gradle.kts.")

val magicSpellsJar = sequenceOf(
    file("libs/MagicSpells.jar"),
    file("D:\\Projects\\MagicSpells\\core\\build\\libs\\MagicSpells.jar"),
).firstOrNull { it.exists() }
    ?: error("MagicSpells jar not found. Put MagicSpells.jar in libs/ or update build.gradle.kts.")

val sneakyBagOfHoldingJar = sequenceOf(
    file("libs/SneakyBagOfHolding.jar"),
    file("D:\\Projects\\SneakyBagOfHolding\\build\\libs\\SneakyBagOfHolding.jar"),
).firstOrNull { it.exists() }
    ?: error("SneakyBagOfHolding jar not found. Put SneakyBagOfHolding.jar in libs/ or update build.gradle.kts.")

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
    compileOnly(files(magicSpellsJar))
    compileOnly(files(sneakyBagOfHoldingJar))

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:26.2.build.117-stable")
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
    jvmToolchain(25)
}
