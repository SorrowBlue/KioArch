import dev.detekt.gradle.Detekt
import org.gradle.kotlin.dsl.withType

plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

dependencies {
    compileOnly(libs.bundles.gradlePlugin)
    compileOnly(files(currentLibs.javaClass.superclass.protectionDomain.codeSource.location))
    testImplementation(kotlin("test"))
    detektPlugins(libs.bundles.detekt)
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    toolVersion = "2.0.0-alpha.3"
    // build-logicの親ディレクトリ（メインプロジェクトルート）にある config/detekt/detekt.yml を参照
    config.setFrom(files(rootDir.parentFile.resolve("config/detekt/detekt.yml")))
    basePath.set(rootProject.layout.projectDirectory)
    buildUponDefaultConfig = true
    autoCorrect = true
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(true)
        checkstyle.required.set(false)
    }

    exclude {
        it.file.path.run { contains("generated-sources") }
    }
}

// Temporarily set to PushMode only
private val currentLibs get() = libs
