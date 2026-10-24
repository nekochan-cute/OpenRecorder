pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.testing.*"); includeGroupByRegex("com\\.google\\.ai\\.edge.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.testing.*"); includeGroupByRegex("com\\.google\\.ai\\.edge.*") } }
        mavenCentral()
    }
}
rootProject.name = "OpenRecorder"
include(":app")
