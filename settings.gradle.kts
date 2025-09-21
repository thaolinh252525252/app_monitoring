pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application"      -> useVersion("8.4.2")
                "org.jetbrains.kotlin.android" -> useVersion("1.9.24")
                "com.google.gms.google-services" -> useVersion("4.4.1")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ChildMonitoringApp"
include(":app")
