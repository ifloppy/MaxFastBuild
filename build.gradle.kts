plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT" apply false
}

allprojects {
    group = "dev.maxfastbuild"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://maven.playpro.com")
    }
}

subprojects {
    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
