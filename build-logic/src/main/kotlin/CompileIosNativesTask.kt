import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CompileIosNativesTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val simulatorOnly: Property<Boolean>

    @TaskAction
    fun compile() {
        val sourceDir = cppSourceDir.get().asFile
        val buildDir = temporaryDir.resolve("build_ios")
        buildDir.mkdirs()

        configureIosCMake(sourceDir, buildDir)
        if (!simulatorOnly.get()) {
            buildIosSdk(sourceDir, buildDir, "iphoneos", "Release-iphoneos")
        }
        buildIosSdk(sourceDir, buildDir, "iphonesimulator", "Release-iphonesimulator")
    }

    private fun configureIosCMake(sourceDir: java.io.File, buildDir: java.io.File) {
        val process = ProcessBuilder(
            "cmake", "-S", ".", "-B", buildDir.absolutePath,
            "-G", "Xcode",
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO",
            "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="
        )
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("CMake configure for iOS failed. Output:\n$output")
        }
    }

    private fun buildIosSdk(
        sourceDir: java.io.File,
        buildDir: java.io.File,
        sdk: String,
        releaseDirName: String
    ) {
        val process = ProcessBuilder(
            "cmake",
            "--build",
            buildDir.absolutePath,
            "--config",
            "Release",
            "--",
            "-sdk",
            sdk
        )
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("CMake build for iOS ($sdk) failed. Output:\n$output")
        }
        fileSystemOperations.copy {
            from(buildDir.resolve("$releaseDirName/libkioarch.a"))
            into(outputDir.get().dir(releaseDirName))
        }
    }
}
