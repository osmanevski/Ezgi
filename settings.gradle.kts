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
        // NewPipeExtractor ve nanojson yalnız JitPack'te; depo yalnız TeamNewPipe grubuna açık.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.(?i)teamnewpipe") } }
    }
}
rootProject.name = "Ezgi"
include(":app")
