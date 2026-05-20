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
            resources.srcDir(layout.buildDirectory.dir("generated/natives"))
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

val compileJvmNatives by tasks.registering {
    group = "build"
    description = "Compiles native libraries for JVM"
    
    inputs.dir("src/cpp")
    
    val osName = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac")
    val isLinux = osName.contains("nix") || osName.contains("nux")
    
    val (platformDir, libName) = when {
        isWindows -> "windows/amd64" to "sevenzip.dll"
        isMac -> "macos/universal" to "libsevenzip.dylib"
        else -> "linux/amd64" to "libsevenzip.so"
    }
    
    val outputDir = layout.buildDirectory.dir("generated/natives/natives/$platformDir")
    outputs.dir(outputDir)
    
    doLast {
        val buildDir = file("src/cpp/build")
        buildDir.mkdirs()
        
        // 1. CMake Configure
        val configureArgs = mutableListOf("cmake", "-S", ".", "-B", "build")
        val javaHome = System.getenv("JAVA_HOME")?.replace('\\', '/')
        if (!javaHome.isNullOrEmpty()) {
            configureArgs.add("-DJAVA_HOME=$javaHome")
        }
        
        val process1 = ProcessBuilder(configureArgs)
            .directory(file("src/cpp"))
            .redirectErrorStream(true)
            .start()
        val output1 = process1.inputStream.bufferedReader().readText()
        val exitCode1 = process1.waitFor()
        if (exitCode1 != 0) {
            println("CMake Configure Output:\n$output1")
            throw GradleException("CMake configure failed with exit code $exitCode1. Output:\n$output1")
        }
        
        // 2. CMake Build
        val process2 = ProcessBuilder("cmake", "--build", "build", "--config", "Release")
            .directory(file("src/cpp"))
            .redirectErrorStream(true)
            .start()
        val output2 = process2.inputStream.bufferedReader().readText()
        val exitCode2 = process2.waitFor()
        if (exitCode2 != 0) {
            println("CMake Build Output:\n$output2")
            throw GradleException("CMake build failed with exit code $exitCode2. Output:\n$output2")
        }
        
        // 3. Copy built library to output resources directory
        val builtLib = when {
            isWindows -> file("src/cpp/build/Release/sevenzip.dll")
            isMac -> file("src/cpp/build/libsevenzip.dylib")
            else -> file("src/cpp/build/libsevenzip.so")
        }
        
        project.copy {
            from(builtLib)
            into(outputDir)
            rename { libName }
        }
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(compileJvmNatives)
}
