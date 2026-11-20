pluginManagement {
    repositories {
        google()
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

rootProject.name = "ThreadLog"

include(":core:model")
include(":core:parser")
include(":core:media")
include(":core:data")
include(":feature:autotag")
include(":feature:chat")
include(":feature:views")
include(":feature:search")
include(":feature:backup")
include(":app")
