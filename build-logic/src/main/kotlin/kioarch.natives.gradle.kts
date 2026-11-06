import TargetOs.Companion.currentOs
import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

val config = extensions.create<CompileNativeExtension>("compileNative")

val externalNativeSources = listOf("7zip/C", "miniz", "bzip2").map { dir ->
    rootProject.fileTree(dir) {
        include("*.c", "*.h")
    }
}

val compileJvmNatives = tasks.register<CompileJvmNativesTask>("compileJvmNatives") {
    group = "build"
    description = "Compiles native libraries for JVM"
    cppSourceDir.set(config.jvm.cppSourceDir.orElse(config.cppSourceDir))
    externalSourceDirs.from(externalNativeSources)
    outputDir.set(config.jvm.outputDir)
    targetOs.set(providers.currentOs())
    enableAsan.set(
        providers.gradleProperty("kioarch.asan").map { it.toBoolean() }.orElse(false)
    )
}

val compileWasmNatives = tasks.register<CompileWasmNativesTask>("compileWasmNatives") {
    group = "build"
    description = "Compiles native libraries for JS/Wasm using Emscripten"
    cppSourceDir.set(config.wasm.cppSourceDir.orElse(config.cppSourceDir))
    externalSourceDirs.from(externalNativeSources)
    outputDir.set(config.wasm.outputDir)

    val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties")
    val localEmsdkProvider = providers.fileContents(localPropertiesFile).asBytes.map { bytes ->
        Properties().apply { load(bytes.inputStream()) }.getProperty("emsdk.dir")
    }
    emsdkDir.set(localEmsdkProvider.orElse(providers.gradleProperty("emsdk.dir")))
}

val compileIosNatives = tasks.register<CompileIosNativesTask>("compileIosNatives") {
    group = "build"
    description = "Compiles native libraries for iOS (macOS host only)"
    val osName = providers.systemProperty("os.name")
    onlyIf {
        osName.get().lowercase().contains("mac")
    }
    cppSourceDir.set(config.ios.cppSourceDir.orElse(config.cppSourceDir))
    externalSourceDirs.from(externalNativeSources)
    outputDir.set(config.ios.outputDir)
    simulatorOnly.set(
        providers.gradleProperty("ios.simulatorOnly").map { it.toBoolean() }.orElse(false)
    )
}

// Set up task dependencies
tasks.configureEach {
    when (name) {
        "jvmProcessResources", "testAndroidHostTest" -> dependsOn(compileJvmNatives)
        "jsProcessResources", "wasmJsProcessResources" -> dependsOn(compileWasmNatives)
    }
}

tasks.withType<CInteropProcess>().configureEach {
    dependsOn(compileIosNatives)
}
