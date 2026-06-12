import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.dokka)

    id("kioarch.versioning")
    id("kioarch.detekt")
    id("kioarch.natives")
}

kotlin {
    explicitApi()

    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    android {
        namespace = "com.sorrowblue.kioarch"

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
        target.compilations.getByName("main") {
            val kioarch by cinterops.creating {
                defFile = project.file("src/nativeInterop/cinterop/kioarch.def")
                includeDirs(
                    project.file("src/cpp")
                )
            }
        }
        target.binaries.framework {
            baseName = "KioArch"
            xcf.add(this)
        }
        val architecture = when (target.name) {
            "iosX64" -> "Release-iphonesimulator"
            "iosSimulatorArm64" -> "Release-iphonesimulator"
            else -> "Release-iphoneos"
        }
        target.binaries.all {
            linkerOpts("-L${project.file("src/cpp/build_ios/$architecture").absolutePath}", "-lkioarch")
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
        val androidJvmMain by creating {
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
        val androidHostTest by getting {
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
        val jsTest by getting {
            resources.srcDir(layout.buildDirectory.dir("tmp/large_tests"))
        }
        val wasmJsTest by getting {
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

val generateTestFiles by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates test archives for testing"
    dependsOn("jvmTestClasses")
    classpath = kotlin.targets.getByName("jvm").compilations.getByName("test").let {
        files(it.output.allOutputs, it.runtimeDependencyFiles)
    }
    mainClass.set("com.sorrowblue.kioarch.TestFileGenerator")
    args(layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(generateTestFiles)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    environment("LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("LARGE_7Z_PATH", "$testDir/large.7z")
    environment("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    environment("LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    environment("LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    environment("TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")

    // Pass environment variables to the iOS Simulator test runner
    environment("SIMCTL_CHILD_LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("SIMCTL_CHILD_LARGE_7Z_PATH", "$testDir/large.7z")
    environment("SIMCTL_CHILD_LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("SIMCTL_CHILD_LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    environment("SIMCTL_CHILD_LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    environment("SIMCTL_CHILD_LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    environment("SIMCTL_CHILD_TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("SIMCTL_CHILD_TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    dependsOn(generateTestFiles)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    environment("LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("LARGE_7Z_PATH", "$testDir/large.7z")
    environment("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("TEST_7Z_PATH", "$testDir/test.7z")
    environment("TEST_ZIP_PATH", "$testDir/test.zip")
    environment("TEST_SJIS_ZIP_PATH", "$testDir/test_sjis.zip")
    environment("TEST_PATH_NORMAL_ZIP_PATH", "$testDir/test_path_normal.zip")
    environment("TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
}

tasks.withType<Test>().configureEach {
    dependsOn(generateTestFiles)
    failOnNoDiscoveredTests.set(false)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    systemProperty("TEST_ZIP_PATH", "$testDir/test.zip")
    systemProperty("TEST_7Z_PATH", "$testDir/test.7z")
    systemProperty("TEST_TARGZ_PATH", "$testDir/test.tar.gz")
    systemProperty("TEST_BZ2_PATH", "$testDir/test.bz2")
    systemProperty("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
    systemProperty("TEST_SJIS_ZIP_PATH", "$testDir/test_sjis.zip")
    systemProperty("TEST_PATH_NORMAL_ZIP_PATH", "$testDir/test_path_normal.zip")
    systemProperty("TEST_BULK_ZIP_PATH", "$testDir/test_bulk.zip")
    systemProperty("TEST_EXT_ZIP_PATH", "$testDir/test_ext.zip")
    systemProperty("LARGE_ZIP_PATH", "$testDir/large.zip")
    systemProperty("LARGE_7Z_PATH", "$testDir/large.7z")
    systemProperty("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    systemProperty("LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    systemProperty("LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    systemProperty("LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    systemProperty("LARGE_TARBZ2_PATH", "$testDir/large.tar.bz2")
    systemProperty("LARGE_100M_TARBZ2_PATH", "$testDir/large_100m.tar.bz2")
}

val copyJsTestAssets by tasks.registering(Copy::class) {
    dependsOn(generateTestFiles)
    from(layout.buildDirectory.dir("tmp/large_tests"))
    into(project.rootProject.layout.buildDirectory.dir("js/packages/KioArch-root-kioarch-test/kotlin"))
}

val copyWasmTestAssets by tasks.registering(Copy::class) {
    dependsOn(generateTestFiles)
    from(layout.buildDirectory.dir("tmp/large_tests"))
    into(project.rootProject.layout.buildDirectory.dir("wasm/packages/KioArch-root-kioarch-test/kotlin"))
}

tasks.configureEach {
    if (name == "jsBrowserTest" || name == "jsNodeTest") {
        dependsOn(copyJsTestAssets)
    }
    if (name == "wasmJsBrowserTest" || name == "wasmJsNodeTest") {
        dependsOn(copyWasmTestAssets)
    }
}

