plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    id("kioarch.detekt")
}

kotlin {
    explicitApi()

    jvmToolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }

    js {
        nodejs()
        browser()
        binaries.executable()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
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
                implementation(projects.sample)
                implementation(libs.compose.ui)
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

