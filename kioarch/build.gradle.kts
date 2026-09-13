import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.dokka)

    id("kioarch.versioning")
    id("kioarch.detekt")
    id("kioarch.natives")
    id("kioarch.testing")
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
        }
    }

    jvm()

    js {
        nodejs()
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
        val libDir = project.file("src/cpp/build_ios/$architecture")
        target.compilations.getByName("main") {
            cinterops.create("kioarch") {
                definitionFile = project.file("src/nativeInterop/cinterop/kioarch.def")
                includeDirs(
                    project.file("src/cpp")
                )
                extraOpts("-libraryPath", libDir.absolutePath)
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
            }
        }
        val androidJvmMain = create("androidJvmMain") {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(androidJvmMain)
            resources.srcDir(layout.buildDirectory.dir("generated/natives"))
        }
        jvmTest {
            dependencies {
                implementation(libs.commons.compress)
                implementation(libs.xz)
            }
        }
        androidMain {
            dependsOn(androidJvmMain)
            dependencies {
                implementation(projects.kioarch.android)
                implementation(libs.androidx.startup)
            }
        }
        jsMain {
            resources.srcDir(layout.buildDirectory.dir("generated/wasm"))
        }
        wasmJsMain {
            resources.srcDir(layout.buildDirectory.dir("generated/wasm"))
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
        getByName("jsTest") {
            resources.srcDir(layout.buildDirectory.dir("tmp/large_tests"))
        }
        getByName("wasmJsTest") {
            resources.srcDir(layout.buildDirectory.dir("tmp/large_tests"))
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



