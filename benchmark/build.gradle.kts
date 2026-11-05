import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    id("kioarch.detekt")
    id("kioarch.benchmark")
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    android {
        namespace = "com.sorrowblue.kioarch.benchmark"
        withDeviceTest {
            instrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
            instrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
                "DEBUGGABLE,NOT-AOT-COMPILED,LOW-BATTERY,UNLOCKED,EMULATOR,ENG-BUILD,ACTIVITY-MISSING"
            instrumentationRunnerArguments["androidx.benchmark.profiling.mode"] = "None"
            animationsDisabled = true
        }
    }

    jvm()

    js {
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.kioarch)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
        val androidJvmMain = register("androidJvmMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.commons.compress)
                implementation(libs.xz)
                compileOnly(libs.sevenzipjbinding.jvm)
            }
        }
        jvmMain {
            dependsOn(androidJvmMain.get())
            dependencies {
                implementation(libs.sevenzipjbinding.jvm)
                implementation(libs.sevenzipjbinding.all.platforms)
            }
        }
        androidMain {
            dependsOn(androidJvmMain.get())
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime.jvm)
                compileOnly(libs.jmh.core)
                implementation(libs.sevenzipjbinding.android)
            }
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.benchmark.junit4)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

benchmark {
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        register("smoke") {
            warmups = 1
            iterations = 2
            iterationTime = 500
            iterationTimeUnit = "ms"
        }
    }
    targets {
        register("jvm") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
        register("js")
        register("wasmJs")
        register("iosX64")
        register("iosArm64")
        register("iosSimulatorArm64")
    }
}
