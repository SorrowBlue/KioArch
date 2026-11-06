import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.maven.publish)

    id("kioarch.versioning")
    id("kioarch.detekt")
    id("kioarch.dokka")
    id("kioarch.natives")
    id("kioarch.testing")
}

dokka {
    moduleName.set("KioArch")
    dokkaPublications.html {
        includes.from("MODULE.md")
    }
    dokkaSourceSets.configureEach {
        includes.from("MODULE.md")
    }
}

kotlin {
    explicitApi()

    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    android {
        namespace = "com.sorrowblue.kioarch"

        @Suppress("UnstableApiUsage")
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            animationsDisabled = true
            managedDevices {
                localDevices {
                    create("pixel7ProApi35") {
                        device = "Pixel 7 Pro"
                        sdkVersion = 35
                        systemImageSource = "aosp"
                        testedAbi = "x86_64"
                    }
                }
            }
        }
    }

    jvm()

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
        browser {}
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        browser {}
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    val xcf = XCFramework("KioArch")
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { target ->
        val architecture = when (target.name) {
            "iosX64" -> "Release-iphonesimulator"
            "iosSimulatorArm64" -> "Release-iphonesimulator"
            else -> "Release-iphoneos"
        }
        val libDir = layout.buildDirectory.dir("generated/ios/natives/$architecture")
        target.compilations.named("main") {
            cinterops.register("kioarch") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/kioarch.def"))
                includeDirs(
                    project.file("src/cpp")
                )
                extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
            }
        }
        target.binaries.framework {
            baseName = "KioArch"
            xcf.add(this)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidJvmMain = register("androidJvmMain") {
            dependsOn(commonMain.get())
        }
        val androidJvmTest = register("androidJvmTest") {
            dependsOn(commonTest.get())
        }
        jvmMain {
            dependsOn(androidJvmMain.get())
            resources.srcDir(layout.buildDirectory.dir("generated/natives"))
        }
        jvmTest {
            dependsOn(androidJvmTest.get())
        }
        androidMain {
            dependsOn(androidJvmMain.get())
            dependencies {
                implementation(projects.kioarch.android)
                implementation(libs.androidx.startup)
            }
        }
        named("androidHostTest") {
            dependsOn(androidJvmTest.get())
        }
        jsMain {
            resources.srcDir(layout.buildDirectory.dir("generated/wasm"))
        }
        wasmJsMain {
            resources.srcDir(layout.buildDirectory.dir("generated/wasm"))
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
        named("jsTest") {
            resources.srcDir(tasks.named("generateTestFiles"))
        }
        named("wasmJsTest") {
            resources.srcDir(tasks.named("generateTestFiles"))
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

compileNative {
    cppSourceDir.set(layout.projectDirectory.dir("src/cpp"))
    jvm {
        outputDir.set(layout.buildDirectory.dir("generated/natives/natives/"))
    }
    wasm {
        outputDir.set(layout.buildDirectory.dir("generated/wasm/natives/"))
    }
    ios {
        outputDir.set(layout.buildDirectory.dir("generated/ios/natives/"))
    }
}
publishing {
    repositories {
        mavenLocal()
    }
}

mavenPublishing {
    publishToMavenCentral()

    coordinates(
        groupId = "com.sorrowblue.kioarch",
        artifactId = "kioarch",
        version = project.version.toString()
    )

    pom {
        name.set("KioArch")
        description.set("Kotlin Multiplatform Library for Archive Files")
        inceptionYear.set("2026")
        url.set("https://github.com/SorrowBlue/KioArch")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("sorrowblue")
                name.set("Sorrow Blue")
                url.set("https://github.com/SorrowBlue")
                email.set("sorrowblue.dev@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/SorrowBlue/KioArch")
            connection.set("scm:git:git://github.com/SorrowBlue/KioArch.git")
            developerConnection.set("scm:git:ssh://github.com/SorrowBlue/KioArch.git")
        }
    }
}



