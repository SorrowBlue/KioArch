enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.kotlinx.kover.aggregation") version "0.9.8"
    id("com.android.settings") version "9.3.0"
}

android {
    compileSdk = 37
    minSdk = 30
}

kover {
    enableCoverage()
    reports {
        excludedClasses.add("org.test.MyClass*")
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
//    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "KioArch-root"
include(":kioarch")
include(":kioarch:android")
include(":sample")
include(":sample:android")
include(":sample:web")
include(":sample:jvm")
