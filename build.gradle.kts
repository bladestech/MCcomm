plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.mccomm"
// CI passes -PreleaseVersion=<tag without the leading v> when building a release.
version = (findProperty("releaseVersion") as String?) ?: "1.1.0-alpha"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.248-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveBaseName.set("MCcomm")
        from("LICENSE") // ship the licence inside the jar
    }
    runServer {
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }
}
