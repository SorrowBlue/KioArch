plugins {
    kotlin("multiplatform") version "2.3.21"
    id("com.android.library") version "9.1.1"
    id("com.android.application") version "9.1.1" apply false
    id("com.android.built-in-kotlin") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                // JVM-specific dependencies if any
            }
        }
        val jvmTest by getting
        val androidMain by getting {
            dependencies {
                // Android-specific dependencies if any
            }
        }
        val androidUnitTest by getting
    }
}

android {
    namespace = "com.antigravity.sevenzip"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        
        ndk {
            // Compile for all major Android architectures
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/cpp/CMakeLists.txt")
            version = "3.10.2"
        }
    }
}
