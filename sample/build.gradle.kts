plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatformLibrary)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    id("kioarch.detekt")
}

kotlin {
    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    android {
        namespace = "com.sorrowblue.kioarch.sample"
    }
    jvm()

    js {
        nodejs()
        browser {}
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }
    wasmJs {
        nodejs()
        browser {}
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.kioarch)

                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.androidx.lifecycle.viewmodelCompose)

                implementation(libs.compose.componentsResources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.uiToolingPreview)

                implementation(libs.filekit.dialogsCompose)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.androidx.documentfile)
            }
        }
    }
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
