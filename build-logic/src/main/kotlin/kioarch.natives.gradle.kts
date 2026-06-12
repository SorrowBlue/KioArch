import TargetOs.Companion.currentOs
import java.util.Properties

val config = extensions.create("compileNative", CompileNativeExtension::class.java)

val compileJvmNatives = tasks.register("compileJvmNatives", CompileJvmNativesTask::class.java) {
    group = "build"
    description = "Compiles native libraries for JVM"
    cppSourceDir.set(config.jvm.cppSourceDir.orElse(config.cppSourceDir))
    outputDir.set(config.jvm.outputDir)
    targetOs.set(providers.currentOs())
}

val compileWasmNatives = tasks.register("compileWasmNatives", CompileWasmNativesTask::class.java) {
    group = "build"
    description = "Compiles native libraries for JS/Wasm using Emscripten"
    cppSourceDir.set(config.wasm.cppSourceDir.orElse(config.cppSourceDir))
    outputDir.set(config.wasm.outputDir)

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

val compileIosNatives = tasks.register("compileIosNatives", CompileIosNativesTask::class.java) {
    group = "build"
    description = "Compiles native libraries for iOS (macOS host only)"
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac")
    }
    cppSourceDir.set(config.ios.cppSourceDir.orElse(config.cppSourceDir))
}

// Set up task dependencies
tasks.configureEach {
    when (name) {
        "jvmProcessResources" -> dependsOn(compileJvmNatives)
        "jsProcessResources", "wasmJsProcessResources" -> dependsOn(compileWasmNatives)
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.CInteropProcess::class.java).configureEach {
    dependsOn(compileIosNatives)
}
