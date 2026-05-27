plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.dokka)

    id("kioarch.versioning")
    id("kioarch.detekt")
}

kotlin {
    explicitApi()
    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

android {
    namespace = "com.sorrowblue.kioarch.android"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Compile for all major Android architectures
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("../src/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    coordinates(
        groupId = "com.sorrowblue.kioarch",
        artifactId = "kioarch-android-native",
        version = project.version.toString()
    )

    pom {
        name.set("KioArch Android")
        description.set("Android library component for KioArch C++ native code")
        inceptionYear.set("2026")
        url.set("https://github.com/SorrowBlue/KioArch")
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
                url.set("https://github.com/SorrowBlue")
                email.set("sorrowblue.dev@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/SorrowBlue/KioArch")
            connection.set("scm:git:git://github.com/SorrowBlue/KioArch.git")
            developerConnection.set("scm:git:ssh://github.com/SorrowBlue/KioArch.git")
        }
    }
}

// Resolve Gradle 9.x implicit dependency issue for Kover
tasks.matching { it.name == "koverGenerateArtifact" }.configureEach {
    dependsOn(tasks.matching { it.name == "compileReleaseKotlin" })
    dependsOn(tasks.matching { it.name == "compileReleaseJavaWithJavac" })
    dependsOn(tasks.matching { it.name == "compileDebugKotlin" })
    dependsOn(tasks.matching { it.name == "compileDebugJavaWithJavac" })
}

