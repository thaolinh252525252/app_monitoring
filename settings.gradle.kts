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
//        maven { url = uri("https://jitpack.io") }  // Thay bằng JitPack
        // Comment hoặc xóa: maven { url = uri("https://maven.ffmpegkit.org") }
    }
}

rootProject.name = "ChildMonitoringApp"
include(":app")