pluginManagement {
    repositories {
        // 腾讯云镜像 - Gradle 插件
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            content {
                excludeGroupByRegex("com\\.github.*")
            }
        }
        google()
    }
}

rootProject.name = "StarGallery"
include(":app")
