pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Prefer auto-discovery; comment this out if gradle/libs.versions.toml exists.
    // versionCatalogs {
    //     create("libs") {
    //         from(files("gradle/libs.versions.toml")) // Only one 'from' per catalog
    //     }
    // }
}
rootProject.name = "alarm"
include(":app")
