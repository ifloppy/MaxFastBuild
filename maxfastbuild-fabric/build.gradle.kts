plugins { id("net.fabricmc.fabric-loom") }

loom {
    splitEnvironmentSourceSets()
    mods {
        create("maxfastbuild") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

// 26.2-specific implementations of the ClientPlatform SPI.
// Shared sources (also compiled by maxfastbuild-fabric-1_21_7) stay in src/main|client/java.
sourceSets {
    named("client") {
        java.srcDir("src/client/26.2/java")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":maxfastbuild-core"))
    implementation(project(":maxfastbuild-storage"))
    include(project(":maxfastbuild-api"))
    include(project(":maxfastbuild-core"))
    include(project(":maxfastbuild-storage"))
    compileOnly("net.luckperms:api:5.5")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
