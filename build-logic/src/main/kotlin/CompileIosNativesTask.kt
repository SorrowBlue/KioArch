import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

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
