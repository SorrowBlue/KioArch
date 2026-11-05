import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import kotlinx.benchmark.gradle.NativeBenchmarkExec
import kotlinx.benchmark.gradle.NativeBenchmarkTarget
import kotlinx.benchmark.gradle.createNativeBenchmarkExecTask
import kotlinx.benchmark.gradle.internal.KotlinxBenchmarkPluginInternalApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

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

afterEvaluate {
    val simTargetName = when (HostManager.host) {
        KonanTarget.MACOS_ARM64 -> "iosSimulatorArm64"
        KonanTarget.MACOS_X64 -> "iosX64"
        else -> null
    }
    if (simTargetName != null) {
        val target = benchmark.targets.findByName(simTargetName) as? NativeBenchmarkTarget
        if (target != null) {
            val helperClass = target.javaClass.classLoader
                .loadClass("kotlinx.benchmark.gradle.NativeMultiplatformTasksKt")
            val genMethod = helperClass.getDeclaredMethod(
                "createNativeBenchmarkGenerateSourceTask",
                Project::class.java,
                NativeBenchmarkTarget::class.java,
            ).apply { isAccessible = true }
            val compileMethod = helperClass.getDeclaredMethod(
                "createNativeBenchmarkCompileTask",
                Project::class.java,
                NativeBenchmarkTarget::class.java,
            ).apply { isAccessible = true }

            genMethod.invoke(null, project, target)
            val benchmarkCompilation =
                compileMethod.invoke(null, project, target) as KotlinNativeCompilation
            @OptIn(KotlinxBenchmarkPluginInternalApi::class)
            benchmark.configurations.forEach { config ->
                project.createNativeBenchmarkExecTask(
                    config,
                    target,
                    benchmarkCompilation,
                )
            }
            tasks.withType<NativeBenchmarkExec>().matching {
                it.name.startsWith(simTargetName)
            }.configureEach {
                doFirst {
                    val kexeFile = executable
                    if (kexeFile.extension == "kexe") {
                        val wrapper = File(kexeFile.parentFile, "${kexeFile.nameWithoutExtension}-simctl.sh")
                        wrapper.writeText(
                            """
                            #!/bin/sh
                            set -e
                            UDID=${'$'}(xcrun simctl list devices available | grep -E "iPhone.*\(([0-9A-F-]{36})\)" | tail -n 1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
                            exec xcrun simctl spawn --standalone "${'$'}UDID" "${kexeFile.absolutePath}" "${'$'}@"
                            """.trimIndent() + "\n",
                        )
                        wrapper.setExecutable(true)
                        executable = wrapper
                    }
                }
            }
        }
    }
}
