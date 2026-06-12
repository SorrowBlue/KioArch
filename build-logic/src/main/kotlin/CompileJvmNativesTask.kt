import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
