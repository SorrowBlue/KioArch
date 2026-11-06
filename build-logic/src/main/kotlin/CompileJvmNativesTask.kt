import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CompileJvmNativesTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppSourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val externalSourceDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val targetOs: Property<TargetOs>

    @get:Input
    abstract val enableAsan: Property<Boolean>

    @TaskAction
    fun compile() {
        val sourceDir = cppSourceDir.get().asFile
        val buildDir = temporaryDir.resolve("build")
        buildDir.mkdirs()

        // 1. Pre-flight check (Check if required tools are available)
        checkToolsAvailability()

        // 2. Run CMake Configure & Build
        configureCMake(sourceDir, buildDir)
        buildCMake(sourceDir, buildDir)
        copyBuiltLibrary(buildDir)
    }

    private fun checkToolsAvailability() {
        if (!isCommandAvailable("cmake")) {
            throw GradleException("CMake is not installed or not available in your PATH.").apply {
                addResolution(
                    "Install CMake and ensure it is registered in your system PATH."
                )
            }
        }

        if (targetOs.get() == TargetOs.Windows) {
            val hasCompiler = isCommandAvailable("cl") ||
                isCommandAvailable("gcc") ||
                isCommandAvailable("clang") ||
                hasVisualStudioInstalled()
            if (!hasCompiler) {
                throw GradleException("C/C++ compiler could not be found.").apply {
                    addResolution(
                        "For Windows, ensure Visual Studio is installed with " +
                            "the 'Desktop development with C++' workload, or install a " +
                            "compatible compiler like MinGW or Clang and add it to your PATH."
                    )
                }
            }
        }
    }

    private fun isCommandAvailable(command: String): Boolean = runCatching {
        val checkCmd = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("where", command)
        } else {
            listOf("which", command)
        }
        val process = ProcessBuilder(checkCmd).start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun hasVisualStudioInstalled(): Boolean = findVcvarsallBat() != null

    private fun findVcvarsallBat(): File? = runCatching {
        val vswhere = File(
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe"
        )
        if (vswhere.exists()) {
            val process = ProcessBuilder(
                vswhere.absolutePath,
                "-latest",
                "-products",
                "*",
                "-requires",
                "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property",
                "installationPath"
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
    }.getOrNull()

    private fun configureCMake(sourceDir: File, buildDir: File) {
        val buildPath = buildDir.absolutePath
        val isAsan = enableAsan.getOrElse(false)
        val asanFlag = if (isAsan) "-DKIOARCH_ENABLE_ASAN=ON" else "-DKIOARCH_ENABLE_ASAN=OFF"
        val configureArgs = if (targetOs.get() == TargetOs.Windows) {
            val vcvarsall = findVcvarsallBat()
            if (vcvarsall != null) {
                listOf(
                    "cmd",
                    "/c",
                    "call \"${vcvarsall.absolutePath}\" amd64 && " +
                        "cmake -S . -B \"$buildPath\" -DCMAKE_BUILD_TYPE=Release $asanFlag"
                )
            } else {
                listOf("cmake", "-S", ".", "-B", buildPath, "-DCMAKE_BUILD_TYPE=Release", asanFlag)
            }
        } else {
            listOf("cmake", "-S", ".", "-B", buildPath, "-DCMAKE_BUILD_TYPE=Release", asanFlag)
        }

        val process = try {
            ProcessBuilder(configureArgs)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException(
                "Failed to run 'cmake'. Error details: ${e.message}",
                e
            ).apply {
                addResolution("Make sure CMake is installed and available in your PATH.")
            }
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Configure Output:\n$output")
            throw GradleException(
                "CMake configure failed with exit code $exitCode.\n\nCMake Output:\n$output"
            ).apply {
                getCompilerMissingResolution(output)?.let { addResolution(it) }
            }
        }
    }

    private fun buildCMake(sourceDir: File, buildDir: File) {
        val buildPath = buildDir.absolutePath
        val buildArgs = if (targetOs.get() == TargetOs.Windows) {
            val vcvarsall = findVcvarsallBat()
            if (vcvarsall != null) {
                val path = vcvarsall.absolutePath
                listOf(
                    "cmd",
                    "/c",
                    "call \"$path\" amd64 && cmake --build \"$buildPath\" --config Release"
                )
            } else {
                listOf("cmake", "--build", buildPath, "--config", "Release")
            }
        } else {
            listOf("cmake", "--build", buildPath, "--config", "Release")
        }

        val process = try {
            ProcessBuilder(buildArgs)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException(
                "Failed to run 'cmake --build'. Error details: ${e.message}",
                e
            ).apply {
                addResolution("Make sure CMake is installed and available in your PATH.")
            }
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Build Output:\n$output")
            throw GradleException("CMake build failed with exit code $exitCode. Output:\n$output")
        }
    }

    private fun copyBuiltLibrary(buildDir: File) {
        val builtLib = when (targetOs.get()) {
            TargetOs.Windows -> {
                val releaseLib = buildDir.resolve("Release/kioarch.dll")
                if (releaseLib.exists()) releaseLib else buildDir.resolve("kioarch.dll")
            }

            TargetOs.MacOS -> buildDir.resolve("libkioarch.dylib")

            TargetOs.Linux -> buildDir.resolve("libkioarch.so")
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

    private fun getCompilerMissingResolution(output: String): String? {
        val hasCompilerError = output.contains("CMAKE_C_COMPILER not set") ||
            output.contains("no such file or directory") ||
            output.contains("No CMAKE_C_COMPILER could be found")
        if (!hasCompilerError) return null

        return when (targetOs.get()) {
            TargetOs.Windows -> {
                "C/C++ compiler (MSVC) could not be found. For Windows, ensure Visual Studio is " +
                    "installed with the 'Desktop development with C++' workload, or make sure a " +
                    "compatible compiler (like MinGW or Clang) is installed and registered in your PATH."
            }

            TargetOs.MacOS -> {
                "C/C++ compiler (Clang) could not be found. For macOS, install Xcode Command " +
                    "Line Tools using 'xcode-select --install'."
            }

            else -> {
                "C/C++ compiler could not be found. For Linux, install gcc/g++ or clang using " +
                    "your package manager (e.g., 'sudo apt install build-essential')."
            }
        }
    }
}
