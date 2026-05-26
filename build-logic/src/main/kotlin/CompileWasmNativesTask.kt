import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task to compile C/C++ native source files into WebAssembly (Wasm) and JavaScript loader
 * using Emscripten (emcc) and CMake.
 *
 * This task supports a hybrid environment:
 * 1. If [emsdkDir] is provided, it automatically loads the Emscripten environment variables
 *    (and discovers MSVC's vcvarsall.bat on Windows) at runtime, allowing zero-config execution from IDEs.
 * 2. If [emsdkDir] is not provided, it falls back to the system environment variables.
 */
abstract class CompileWasmNativesTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    /**
     * The input directory containing the C/C++ source code and CMakeLists.txt.
     */
    @get:InputDirectory
    abstract val cppSourceDir: DirectoryProperty

    /**
     * The output directory where the built Wasm and JS loader files will be placed.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * The optional directory path to the Emscripten SDK installation.
     * If specified, it will be used to initialize the compiler environment at runtime.
     */
    @get:Input
    @get:Optional
    abstract val emsdkDir: Property<String>

    @TaskAction
    fun compile() {
        val sourceDir = cppSourceDir.get().asFile
        val buildDir = sourceDir.resolve("build_wasm")
        buildDir.mkdirs()

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val emsdkPath = emsdkDir.orNull

        configureCMake(sourceDir, isWindows, emsdkPath)
        buildCMake(sourceDir, isWindows, emsdkPath)
        copyArtifacts(buildDir)
    }

    private fun configureCMake(sourceDir: File, isWindows: Boolean, emsdkPath: String?) {
        val configureArgs = if (isWindows) {
            val vcvarsall = findVcvarsallBat()
            val vcPrefix = if (vcvarsall != null) {
                "call \"${vcvarsall.absolutePath}\" amd64 && "
            } else {
                ""
            }
            val envPrefix = if (emsdkPath != null) {
                "call \"$emsdkPath\\emsdk_env.bat\" && "
            } else {
                ""
            }
            val cmd = "${vcPrefix}$envPrefix" +
                "emcmake cmake -S . -B build_wasm -G \"NMake Makefiles\""
            listOf("cmd", "/c", cmd)
        } else {
            if (emsdkPath != null) {
                val shCmd = ". \"$emsdkPath/emsdk_env.sh\" && " +
                    "emcmake cmake -S . -B build_wasm"
                listOf("sh", "-c", shCmd)
            } else {
                listOf("emcmake", "cmake", "-S", ".", "-B", "build_wasm")
            }
        }

        val process = ProcessBuilder(configureArgs)
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Wasm Configure Output:\n$output")
            throw GradleException(
                "CMake Wasm configure failed with exit code $exitCode. Output:\n$output"
            )
        }
    }

    private fun buildCMake(sourceDir: File, isWindows: Boolean, emsdkPath: String?) {
        val buildArgs = if (isWindows) {
            val vcvarsall = findVcvarsallBat()
            val vcPrefix = if (vcvarsall != null) {
                "call \"${vcvarsall.absolutePath}\" amd64 && "
            } else {
                ""
            }
            val envPrefix = if (emsdkPath != null) {
                "call \"$emsdkPath\\emsdk_env.bat\" && "
            } else {
                ""
            }
            val cmd = "${vcPrefix}$envPrefix" +
                "cmake --build build_wasm --config Release"
            listOf("cmd", "/c", cmd)
        } else {
            if (emsdkPath != null) {
                val shCmd = ". \"$emsdkPath/emsdk_env.sh\" && " +
                    "cmake --build build_wasm --config Release"
                listOf("sh", "-c", shCmd)
            } else {
                listOf("cmake", "--build", "build_wasm", "--config", "Release")
            }
        }
        val process = ProcessBuilder(buildArgs)
            .directory(sourceDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("CMake Wasm Build Output:\n$output")
            throw GradleException(
                "CMake Wasm build failed with exit code $exitCode. Output:\n$output"
            )
        }
    }

    private fun copyArtifacts(buildDir: File) {
        val jsFile = buildDir.resolve("kioarch.js")
        val wasmFile = buildDir.resolve("kioarch.wasm")

        fileSystemOperations.copy {
            from(jsFile)
            from(wasmFile)
            into(outputDir.get())
        }
    }

    /**
     * Discovers Visual Studio's vcvarsall.bat location using Windows vswhere.exe tool.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun findVcvarsallBat(): File? = try {
        val process = ProcessBuilder(
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe",
            "-latest",
            "-property",
            "installationPath"
        )
            .redirectErrorStream(true)
            .start()
        val path = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && path.isNotEmpty()) {
            val file = File(path).resolve("VC\\Auxiliary\\Build\\vcvarsall.bat")
            if (file.exists()) file else null
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
