import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory

open class CompileNativeExtension @Inject constructor(objects: ObjectFactory) {
    val cppSourceDir: DirectoryProperty = objects.directoryProperty()

    val jvm = objects.newInstance(JvmNativesConfig::class.java)
    val wasm = objects.newInstance(WasmNativesConfig::class.java)
    val ios = objects.newInstance(IosNativesConfig::class.java)

    fun jvm(action: Action<JvmNativesConfig>) {
        action.execute(jvm)
    }

    fun wasm(action: Action<WasmNativesConfig>) {
        action.execute(wasm)
    }

    fun ios(action: Action<IosNativesConfig>) {
        action.execute(ios)
    }
}

open class JvmNativesConfig @Inject constructor(objects: ObjectFactory) {
    val cppSourceDir: DirectoryProperty = objects.directoryProperty()
    val outputDir: DirectoryProperty = objects.directoryProperty()
}

open class WasmNativesConfig @Inject constructor(objects: ObjectFactory) {
    val cppSourceDir: DirectoryProperty = objects.directoryProperty()
    val outputDir: DirectoryProperty = objects.directoryProperty()
}

open class IosNativesConfig @Inject constructor(objects: ObjectFactory) {
    val cppSourceDir: DirectoryProperty = objects.directoryProperty()
}
