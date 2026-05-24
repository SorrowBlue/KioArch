import TargetOs.Companion.currentOs

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
        val androidHostTest by getting {
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
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
            "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64",
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

        // 1. CMake Configure
        val configureArgs = mutableListOf("cmake", "-S", ".", "-B", "build")

        val process1 = ProcessBuilder(configureArgs)
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output1 = process1.inputStream.bufferedReader().readText()
        val exitCode1 = process1.waitFor()
        if (exitCode1 != 0) {
            logger.error("CMake Configure Output:\n$output1")
            throw GradleException("CMake configure failed with exit code $exitCode1. Output:\n$output1")
        }

        // 2. CMake Build
        val process2 = ProcessBuilder("cmake", "--build", "build", "--config", "Release")
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output2 = process2.inputStream.bufferedReader().readText()
        val exitCode2 = process2.waitFor()
        if (exitCode2 != 0) {
            logger.error("CMake Build Output:\n$output2")
            throw GradleException("CMake build failed with exit code $exitCode2. Output:\n$output2")
        }

        // 3. Copy built library to output resources directory
        val builtLib = when (targetOs.get()) {
            TargetOs.Windows -> sourceDir.resolve("build/Release/kioarch.dll")
            TargetOs.MacOS -> sourceDir.resolve("build/libkioarch.dylib")
            TargetOs.Linux -> sourceDir.resolve("build/libkioarch.so")
        }
        fileSystemOperations.copy {
            from(builtLib)
            into(outputDir.get().dir(targetOs.get().output))
            rename { targetOs.get().libName }
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

val generateLargeTestFiles by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates large 7z and tar.gz files for iOS testing"
    dependsOn("jvmTestClasses")
    classpath = kotlin.targets.getByName("jvm").compilations.getByName("test").let {
        files(it.output.allOutputs, it.runtimeDependencyFiles)
    }
    mainClass.set("com.sorrowblue.kioarch.LargeTestFileGenerator")
    args(layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(generateLargeTestFiles)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    environment("LARGE_7Z_PATH", "$testDir/large.7z")
    environment("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
}
