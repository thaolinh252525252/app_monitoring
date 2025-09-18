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
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" -> useVersion("8.1.0")
                "org.jetbrains.kotlin.android" -> useVersion("1.9.0")
                "org.jetbrains.kotlin.plugin.compose" -> useVersion("1.9.0")
                "com.google.gms.google-services" -> useVersion("4.4.0")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // maven { url = uri("https://jitpack.io") } // Giữ nếu cần
        // Comment hoặc xóa: maven { url = uri("https://maven.ffmpegkit.org") }
    }
}

rootProject.name = "ChildMonitoringApp"
include(":app")