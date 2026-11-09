import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("io.izzel.taboolib") version "2.0.38"
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
}

taboolib {
    env {
        // 安装模块
        install(Basic, Bukkit, BukkitUI, BukkitNMSUtil, CommandHelper, I18n, PtcObject, "database-postgresql")
    }
    version {
        taboolib = "6.3.0-afd75a7"
        skipPlatformFile = true
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly(kotlin("stdlib"))
    compileOnly(fileTree("libs"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
