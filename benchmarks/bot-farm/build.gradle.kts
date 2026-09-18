plugins {
    java
    application
}

group = "io.agcmc.agc.bench"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // Match the AGC server toolchain (Java 25) — same JDK is already provisioned locally.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // MCProtocolLib snapshots + transitive deps (cloudburstmc math, nukkitx fastutil).
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.nukkitx.com/maven-releases/")
    maven("https://repo.nukkitx.com/maven-snapshots/")
    maven("https://jitpack.io")
}

// Pin to an MC-26.x-protocol-compatible release when running live fire.
// Override with: ./gradlew -PmcprotocolVersion=<tag>
val mcprotocolVersion: String = providers.gradleProperty("mcprotocolVersion").getOrElse("26.2-SNAPSHOT")

dependencies {
    implementation("org.geysermc.mcprotocollib:protocol:$mcprotocolVersion")
}

application {
    mainClass.set("agc.bench.HarnessMain")
}

// Console-command CLI for inspecting a server that is already under load:
//   gradlew -p benchmarks/bot-farm rcon --args="127.0.0.1 25575 bench \"agc status\""
tasks.register<JavaExec>("rcon") {
    group = "application"
    description = "Runs one or more console commands over RCON against a running server."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("agc.bench.RconCommandCli")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
