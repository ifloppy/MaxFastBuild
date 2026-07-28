plugins { java }

dependencies {
    implementation(project(":maxfastbuild-core"))
    implementation(project(":maxfastbuild-storage"))
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("net.coreprotect:coreprotect:24.0")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":maxfastbuild-api").sourceSets.main.get().output)
    from(project(":maxfastbuild-core").sourceSets.main.get().output)
    from(project(":maxfastbuild-storage").sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().filter { it.name.contains("sqlite") }.map { zipTree(it) })
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
