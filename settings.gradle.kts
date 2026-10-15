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

rootProject.name = "Aistee"
include(":app", ":shared", ":benchmark")

// CodeQL autobuild traces kotlinc/javac; compile outputs restored from its cached ~/.gradle leave nothing to extract.
buildCache { local { isEnabled = System.getenv("CODEQL_ACTION_VERSION") == null } }
