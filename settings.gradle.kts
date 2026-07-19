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
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroup("com.arthenica")
            }
        }
    }
}

rootProject.name = "SubtitleEditforAndroid"
include(":app")
