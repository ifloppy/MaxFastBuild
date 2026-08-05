plugins { java }

dependencies {
    implementation(project(":maxfastbuild-core"))
    implementation(project(":maxfastbuild-storage"))
    // SQLite is provided by the server libraries or a separate JDBC plugin — do not shade into the jar.
    compileOnly("org.xerial:sqlite-jdbc:3.50.3.0")
    // Compile against 1.21.11 so the plugin loads on Leaf/Paper 1.21.11 and remains usable on newer (e.g. 26.2).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("net.coreprotect:coreprotect:24.0")
    // Prism logging API (see https://docs.prism-mc.org/api/); softdepend in plugin.yml, no-op at runtime without it.
    compileOnly("org.prism_mc.prism:prism-paper-api:4.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":maxfastbuild-api").sourceSets.main.get().output)
    from(project(":maxfastbuild-core").sourceSets.main.get().output)
    from(project(":maxfastbuild-storage").sourceSets.main.get().output)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
