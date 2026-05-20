plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

android {
    namespace = "com.sorrowblue.kioarch.android"
    defaultConfig {
        ndk {
            // Compile for all major Android architectures
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }
    logger.lifecycle("ndkVersion: $ndkVersion")

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            logger.lifecycle("version: $version")
            path = file("../src/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}
