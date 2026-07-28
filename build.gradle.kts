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

// Collect distributable Paper + Fabric jars into ./release after they are built.
val releaseDir = layout.projectDirectory.dir("release")
val releaseVersion = version.toString()
// Dev Leaf server sits beside this git repo: ../test-server-leaf
val leafPluginsDir = layout.projectDirectory.dir("../test-server-leaf/plugins").asFile

fun pickDistributableJar(projectPath: String): File {
    val libs = project(projectPath).layout.buildDirectory.dir("libs").get().asFile
    return libs.listFiles()
        ?.filter { it.isFile && it.extension == "jar" }
        ?.filterNot { it.name.contains("-sources") || it.name.contains("-javadoc") || it.name.contains("-dev") }
        ?.maxByOrNull { it.lastModified() }
        ?: error("No distributable jar in $libs — build $projectPath first")
}

val copyReleaseJars by tasks.registering {
    group = "distribution"
    description = "Copy Paper plugin and Fabric mod jars into release/"
    dependsOn(":maxfastbuild-paper:jar", ":maxfastbuild-fabric:jar")

    inputs.files(
        project(":maxfastbuild-paper").tasks.named("jar").map { it.outputs.files },
        project(":maxfastbuild-fabric").tasks.named("jar").map { it.outputs.files },
    )
    outputs.dir(releaseDir)

    doLast {
        val out = releaseDir.asFile
        out.mkdirs()
        val paper = pickDistributableJar(":maxfastbuild-paper")
        val fabric = pickDistributableJar(":maxfastbuild-fabric")
        paper.copyTo(out.resolve("MaxFastBuild-Paper-$releaseVersion.jar"), overwrite = true)
        fabric.copyTo(out.resolve("MaxFastBuild-Fabric-$releaseVersion.jar"), overwrite = true)
        logger.lifecycle("Release jars:")
        logger.lifecycle("  ${out.resolve("MaxFastBuild-Paper-$releaseVersion.jar")}")
        logger.lifecycle("  ${out.resolve("MaxFastBuild-Fabric-$releaseVersion.jar")}")
    }
}

val deployPaperToLeaf by tasks.registering {
    group = "distribution"
    description = "Overwrite ../test-server-leaf/plugins/MaxFastBuild.jar with the Paper plugin jar (dev test env)"
    dependsOn(":maxfastbuild-paper:jar")

    // Always re-copy when jar task ran; dest mtime alone is unreliable across machines.
    outputs.upToDateWhen { false }

    onlyIf {
        val serverRoot = leafPluginsDir.parentFile
        serverRoot != null && serverRoot.isDirectory
    }

    doLast {
        val plugins = leafPluginsDir
        if (!plugins.isDirectory) {
            plugins.mkdirs()
        }
        val paper = pickDistributableJar(":maxfastbuild-paper")
        val dest = plugins.resolve("MaxFastBuild.jar")
        // Remove versioned leftovers so Leaf only loads one MaxFastBuild jar.
        plugins.listFiles()
            ?.filter { it.isFile && it.name.startsWith("MaxFastBuild") && it.name.endsWith(".jar") && it.name != "MaxFastBuild.jar" }
            ?.forEach { it.delete() }
        // Server may lock MaxFastBuild.jar while running — try overwrite, else write .new sidecar.
        try {
            paper.copyTo(dest, overwrite = true)
            logger.lifecycle("Deployed Paper plugin -> ${dest.absolutePath} (from ${paper.name}, ${paper.length()} bytes)")
        } catch (ex: Exception) {
            val sidecar = plugins.resolve("MaxFastBuild.jar.new")
            paper.copyTo(sidecar, overwrite = true)
            logger.warn(
                "Could not overwrite locked ${dest.name} (${ex.message}). " +
                    "Wrote ${sidecar.absolutePath} — PlugMan unload/load or stop server, then replace MaxFastBuild.jar",
            )
        }
    }
}

tasks.named("build") {
    dependsOn(copyReleaseJars)
    finalizedBy(deployPaperToLeaf)
}

tasks.named("assemble") {
    finalizedBy(copyReleaseJars)
}

// Any Paper jar build also refreshes the Leaf test plugin when the server tree exists.
gradle.projectsEvaluated {
    project(":maxfastbuild-paper").tasks.named("jar").configure {
        finalizedBy(deployPaperToLeaf)
    }
}
