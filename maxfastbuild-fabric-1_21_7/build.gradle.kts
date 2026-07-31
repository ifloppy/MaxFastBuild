plugins { id("net.fabricmc.fabric-loom-remap") }

loom {
    splitEnvironmentSourceSets()
    mods {
        create("maxfastbuild") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

// 1.21.7 (obfuscated era): loom-remap + official Mojang mappings, so the shared
// mojmap-written sources compile unchanged. RemapJar maps everything to intermediary.
// Shared sources (also compiled by maxfastbuild-fabric for 26.2) live in the
// maxfastbuild-fabric tree; only version-specific implementations live here.
val sharedAssets by tasks.registering(Copy::class) {
    from("../maxfastbuild-fabric/src/main/resources") {
        exclude("fabric.mod.json", "maxfastbuild.mixins.json")
    }
    into(layout.buildDirectory.dir("generated/resources/main"))
}

sourceSets {
    main {
        java.srcDir("../maxfastbuild-fabric/src/main/java")
        resources.srcDir(layout.buildDirectory.dir("generated/resources/main"))
    }
    named("client") {
        java.srcDir("../maxfastbuild-fabric/src/client/java")
        java.srcDir("src/client/1.21.7/java")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version_1217")}")
    mappings(loom.officialMojangMappings())
    implementation("net.fabricmc:fabric-loader:${property("loader_version_1217")}")
    // loom-remap does not auto-add the mixin library (loom does for 26.1+); required to compile the shared mixins.
    implementation("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    // modImplementation: remaps fabric-api to mojmap for the dev env and strips the meta jar's
    // nested jars (they are not remapped by loom-remap); module jars publish separately and remap fine.
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version_1217")}")
    implementation(project(":maxfastbuild-core"))
    implementation(project(":maxfastbuild-storage"))
    include(project(":maxfastbuild-api"))
    include(project(":maxfastbuild-core"))
    include(project(":maxfastbuild-storage"))
    compileOnly("net.luckperms:api:5.5")
}

tasks.processResources {
    dependsOn(sharedAssets)
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.sourcesJar {
    dependsOn(sharedAssets)
}
