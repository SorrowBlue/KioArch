import org.gradle.api.provider.ProviderFactory

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
