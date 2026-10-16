pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://api.modrinth.com/maven")
    }
}

rootProject.name = "VeloCraft-Android"
include(":app")
include(":fabric-bridge-mod")
