pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "challenge-server"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core")
include(":batch")
include(":domain:model")
include(":domain:repository")
include(":controller")
include(":service")
include(":infra:entity")
include(":infra:jpa")
include(":infra:repositoryimpl")
include(":infra:external")
