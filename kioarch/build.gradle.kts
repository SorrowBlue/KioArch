import TargetOs.Companion.currentOs
import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.dokka)

    id("kioarch.versioning")
    id("kioarch.detekt")
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

val compileJvmNatives by tasks.registering(CompileJvmNativesTask::class) {
    group = "build"
    description = "Compiles native libraries for JVM"
    cppSourceDir = layout.projectDirectory.dir("src/cpp")
    outputDir = layout.buildDirectory.dir("generated/natives/natives/")
    targetOs = providers.currentOs()
}

tasks.named("jvmProcessResources") {
    dependsOn(compileJvmNatives)
}

val compileWasmNatives by tasks.registering(CompileWasmNativesTask::class) {
    group = "build"
    description = "Compiles native libraries for JS/Wasm using Emscripten"
    cppSourceDir = layout.projectDirectory.dir("src/cpp")
    outputDir = layout.buildDirectory.dir("generated/wasm/natives/")

    val localProperties = Properties().apply {
        val file = project.rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
    val emsdkPath = localProperties.getProperty("emsdk.dir")
        ?: project.findProperty("emsdk.dir") as? String
    if (emsdkPath != null) {
        emsdkDir.set(emsdkPath)
    }
}

tasks.named("jsProcessResources") {
    dependsOn(compileWasmNatives)
}

tasks.named("wasmJsProcessResources") {
    dependsOn(compileWasmNatives)
}

tasks.named("jsTestProcessResources") {
    dependsOn(generateTestFiles)
}

tasks.named("wasmJsTestProcessResources") {
    dependsOn(generateTestFiles)
}

val compileIosNatives by tasks.registering(CompileIosNativesTask::class) {
    group = "build"
    description = "Compiles native libraries for iOS (macOS host only)"
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac")
    }
    cppSourceDir = layout.projectDirectory.dir("src/cpp")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    dependsOn(compileIosNatives)
}

abstract class CompileIosNativesTask : DefaultTask() {

    @get:InputDirectory
    abstract val cppSourceDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val sourceDir = cppSourceDir.get().asFile
        val buildDir = sourceDir.resolve("build_ios")
        buildDir.mkdirs()

        // 1. CMake Configure (iOS)
        val process1 = ProcessBuilder(
            "cmake", "-S", ".", "-B", "build_ios",
            "-G", "Xcode",
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="
        )
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output1 = process1.inputStream.bufferedReader().readText()
        val exitCode1 = process1.waitFor()
        if (exitCode1 != 0) {
            throw GradleException("CMake configure for iOS failed. Output:\n$output1")
        }

        // 2a. CMake Build (iOS Device)
        val process2a = ProcessBuilder(
            "cmake", "--build", "build_ios",
            "--config", "Release",
            "--", "-sdk", "iphoneos"
        )
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output2a = process2a.inputStream.bufferedReader().readText()
        val exitCode2a = process2a.waitFor()
        if (exitCode2a != 0) {
            throw GradleException("CMake build for iOS Device failed. Output:\n$output2a")
        }

        // 2b. CMake Build (iOS Simulator)
        val process2b = ProcessBuilder(
            "cmake", "--build", "build_ios",
            "--config", "Release",
            "--", "-sdk", "iphonesimulator"
        )
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output2b = process2b.inputStream.bufferedReader().readText()
        val exitCode2b = process2b.waitFor()
        if (exitCode2b != 0) {
            throw GradleException("CMake build for iOS Simulator failed. Output:\n$output2b")
        }
    }
}

abstract class CompileJvmNativesTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    @get:InputDirectory
    abstract val cppSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val targetOs: Property<TargetOs>

    @TaskAction
    fun compile() {
        val sourceDir = cppSourceDir.get().asFile
        val buildDir = sourceDir.resolve("build")
        buildDir.mkdirs()

        // 1. Pre-flight check (Check if required tools are available)
        checkToolsAvailability()

        // 2. Run CMake Configure & Build
        configureCMake(sourceDir)
        buildCMake(sourceDir)
        copyBuiltLibrary(sourceDir)
    }

    private fun checkToolsAvailability() {
        if (!isCommandAvailable("cmake")) {
            throw GradleException(
                "CMake is not installed or not available in your PATH.\n" +
                "Please install CMake and ensure it is registered in your system PATH."
            )
        }

        if (targetOs.get() == TargetOs.Windows) {
            val hasCompiler = isCommandAvailable("cl") || 
                              isCommandAvailable("gcc") || 
                              isCommandAvailable("clang") || 
                              hasVisualStudioInstalled()
            if (!hasCompiler) {
                throw GradleException(
                    "C/C++ compiler could not be found.\n" +
                    "For Windows, please ensure Visual Studio is installed with the 'Desktop development with C++' workload.\n" +
                    "Alternatively, install a compatible compiler like MinGW or Clang and add it to your PATH."
                )
            }
        }
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val checkCmd = if (System.getProperty("os.name").lowercase().contains("windows")) {
                listOf("where", command)
            } else {
                listOf("which", command)
            }
            val process = ProcessBuilder(checkCmd).start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun hasVisualStudioInstalled(): Boolean {
        return findVcvarsallBat() != null
    }

    private fun findVcvarsallBat(): File? = try {
        val vswhere = File("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe")
        if (vswhere.exists()) {
            val process = ProcessBuilder(
                vswhere.absolutePath,
                "-latest",
                "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property", "installationPath"
            ).start()
            val path = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && path.isNotEmpty()) {
                val file = File(path).resolve("VC\\Auxiliary\\Build\\vcvarsall.bat")
                if (file.exists()) file else null
            } else {
                null
            }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun configureCMake(sourceDir: File) {
        val configureArgs = if (targetOs.get() == TargetOs.Windows) {
            val vcvarsall = findVcvarsallBat()
            if (vcvarsall != null) {
                listOf("cmd", "/c", "call \"${vcvarsall.absolutePath}\" amd64 && cmake -S . -B build")
            } else {
                listOf("cmake", "-S", ".", "-B", "build")
            }
        } else {
            listOf("cmake", "-S", ".", "-B", "build")
        }

        val process = try {
            ProcessBuilder(configureArgs)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException(
                "Failed to run 'cmake'. Please make sure CMake is installed and available in your PATH.\n" +
                "Error details: ${e.message}",
                e
            )
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Configure Output:\n$output")
            val additionalMsg = getCompilerMissingMessage(output)
            throw GradleException("CMake configure failed with exit code $exitCode.$additionalMsg\n\nCMake Output:\n$output")
        }
    }

    private fun buildCMake(sourceDir: File) {
        val buildArgs = if (targetOs.get() == TargetOs.Windows) {
            val vcvarsall = findVcvarsallBat()
            if (vcvarsall != null) {
                listOf("cmd", "/c", "call \"${vcvarsall.absolutePath}\" amd64 && cmake --build build --config Release")
            } else {
                listOf("cmake", "--build", "build", "--config", "Release")
            }
        } else {
            listOf("cmake", "--build", "build", "--config", "Release")
        }

        val process = try {
            ProcessBuilder(buildArgs)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException(
                "Failed to run 'cmake --build'. Please make sure CMake is installed and available in your PATH.\n" +
                "Error details: ${e.message}",
                e
            )
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Build Output:\n$output")
            throw GradleException("CMake build failed with exit code $exitCode. Output:\n$output")
        }
    }

    private fun copyBuiltLibrary(sourceDir: File) {
        val builtLib = when (targetOs.get()) {
            TargetOs.Windows -> {
                val releaseLib = sourceDir.resolve("build/Release/kioarch.dll")
                if (releaseLib.exists()) releaseLib else sourceDir.resolve("build/kioarch.dll")
            }
            TargetOs.MacOS -> sourceDir.resolve("build/libkioarch.dylib")
            TargetOs.Linux -> sourceDir.resolve("build/libkioarch.so")
        }
        if (!builtLib.exists()) {
            throw GradleException("Built library not found at: ${builtLib.absolutePath}")
        }
        fileSystemOperations.copy {
            from(builtLib)
            into(outputDir.get().dir(targetOs.get().output))
            rename { targetOs.get().libName }
        }
    }

    private fun getCompilerMissingMessage(output: String): String {
        val hasCompilerError = output.contains("CMAKE_C_COMPILER not set") ||
                output.contains("no such file or directory") ||
                output.contains("No CMAKE_C_COMPILER could be found")
        if (!hasCompilerError) return ""

        return when (targetOs.get()) {
            TargetOs.Windows -> {
                "\n\n[Error Reason] C/C++ compiler (MSVC) could not be found.\n" +
                "For Windows, please ensure Visual Studio is installed with the 'Desktop development with C++' workload.\n" +
                "Alternatively, make sure a compatible compiler (like MinGW or Clang) is installed and registered in your PATH."
            }
            TargetOs.MacOS -> {
                "\n\n[Error Reason] C/C++ compiler (Clang) could not be found.\n" +
                "For macOS, please install Xcode Command Line Tools using 'xcode-select --install'."
            }
            else -> {
                "\n\n[Error Reason] C/C++ compiler could not be found.\n" +
                "For Linux, please install gcc/g++ or clang using your package manager (e.g., 'sudo apt install build-essential')."
            }
        }
    }
}

enum class TargetOs(val osName: String, val libName: String, val output: String) {
    Windows("win", "kioarch.dll", "windows/amd64"),
    MacOS("mac", "libkioarch.dylib", "macos/universal"),
    Linux("linux", "libkioarch.so", "linux/amd64");

    companion object {
        fun ProviderFactory.currentOs(): TargetOs? {
            val osName = systemProperty("os.name").map { it.lowercase() }.get()
            return TargetOs.entries.firstOrNull { osName.contains(it.osName) }
        }
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

