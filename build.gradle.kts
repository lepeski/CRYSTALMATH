import org.gradle.api.file.DuplicatesStrategy

plugins {
    `java`
}

group = "dev.crystalmath"
version = "1.0.0"

description = "Combined crystalmath plugin"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

val paperApiVersion = (project.findProperty("paperApiVersion") as String?)
    ?: "1.21.8-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

configurations {
    runtimeClasspath {
        exclude(group = "org.slf4j")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
