plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
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

mavenPublishing {
    coordinates(
        groupId = "com.sorrowblue.kioarch",
        artifactId = "kioarch-android-native",
        version = "0.1.0-SNAPSHOT"
    )

    pom {
        name.set("KioArch Android")
        description.set("Android library component for KioArch C++ native code")
        inceptionYear.set("2026")
        url.set("https://github.com/sorrowblue/KioArch")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("sorrowblue")
                name.set("Sorrow Blue")
                email.set("sorrowblue@example.com")
            }
        }
        scm {
            url.set("https://github.com/sorrowblue/KioArch")
            connection.set("scm:git:git://github.com/sorrowblue/KioArch.git")
            developerConnection.set("scm:git:ssh://github.com/sorrowblue/KioArch.git")
        }
    }
}
