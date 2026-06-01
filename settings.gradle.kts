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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FFmpegKit was archived by its author in 2025 and the binaries were
        // pulled from Maven Central. The Aliyun and HuaweiCloud public mirrors
        // still serve the cached 6.0.LTS AAR with matching SHA1
        // (4b3fc143f29a61044bb87b9c8dd80982d7b1c35b). Restrict each mirror to
        // the com.arthenica group so they're never consulted for anything
        // else; mavenCentral() above still serves smart-exception-java.
        maven("https://maven.aliyun.com/repository/public") {
            content { includeGroup("com.arthenica") }
        }
        maven("https://repo.huaweicloud.com/repository/maven") {
            content { includeGroup("com.arthenica") }
        }
    }
}

rootProject.name = "VideoSubtitle"
include(":app")
 