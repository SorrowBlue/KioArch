import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.kioarch)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs)
                implementation(libs.filekit.dialogs.compose)
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.sorrowblue.kioarch.sample.jvm.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.sorrowblue.kioarch.sample.jvm"
            packageVersion = "1.0.0"
        }
        jvmArgs("-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
    }
}
