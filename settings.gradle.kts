pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "MaxFastBuild"
include("maxfastbuild-api", "maxfastbuild-core", "maxfastbuild-storage", "maxfastbuild-fabric", "maxfastbuild-paper")
