plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("kioarch.detekt")
}

kotlin {
    explicitApi()

    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    open = true
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.kioarch)
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
            }
        }
    }
}

val copyWasmNativesToResources by tasks.registering(Copy::class) {
    dependsOn(":kioarch:compileWasmNatives")
    from(project(":kioarch").layout.buildDirectory.dir("generated/wasm/natives"))
    into(layout.projectDirectory.dir("src/wasmJsMain/resources"))
}

tasks.named("wasmJsProcessResources") {
    dependsOn(copyWasmNativesToResources)
}

