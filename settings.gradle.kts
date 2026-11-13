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
}

rootProject.name = "Proxima"

// One module per lane. See docs/TEAM_ROLES.md for ownership.
include(":app")    // UI lane
include(":core")   // shared contract — change only via `interface-change` issue
include(":mesh")   // MESH lane
include(":ai")     // AI lane
include(":locate") // LOCATE lane
include(":trust")  // TRUST lane
