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
    file("libs/CoreProtect-23.3.jar"),
    file("C:\\Users\\DaniDipp\\Downloads\\1.21.4\\CoreProtect-23.3.jar"),
).firstOrNull { it.exists() }
    ?: error("CoreProtect jar not found. Put CoreProtect-23.3.jar in libs/ or update build.gradle.kts.")

val magicSpellsJar = sequenceOf(
    file("libs/MagicSpells-4.0-Beta-13.jar"),
    file("C:\\Users\\DaniDipp\\Downloads\\1.21.4\\MagicSpells-4.0-Beta-13.jar"),
).firstOrNull { it.exists() }
    ?: error("MagicSpells jar not found. Put MagicSpells-4.0-Beta-13.jar in libs/ or update build.gradle.kts.")

val sneakyBagOfHoldingJar = sequenceOf(
    file("libs/SneakyBagOfHolding-1.0.0.jar"),
    file("C:\\Users\\DaniDipp\\Downloads\\1.21.4\\SneakyBagOfHolding-1.0.0.jar"),
).firstOrNull { it.exists() }
    ?: error("SneakyBagOfHolding jar not found. Put SneakyBagOfHolding-1.0.0.jar in libs/ or update build.gradle.kts.")

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.13")
    compileOnly(files(coreProtectJar))
    compileOnly(files(magicSpellsJar))
    compileOnly(files(sneakyBagOfHoldingJar))

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
