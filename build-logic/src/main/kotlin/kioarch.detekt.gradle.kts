import dev.detekt.gradle.Detekt
import dev.detekt.gradle.report.ReportMergeTask
import org.gradle.kotlin.dsl.withType

plugins {
    dev.detekt
}

dependencies {
    detektPlugins(libs.bundles.detekt)
}

detekt {
    config.setFrom(files(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")))
    basePath.set(rootProject.layout.projectDirectory)
    buildUponDefaultConfig = true
    autoCorrect = true
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        sarif.required.set(true)
        html.required.set(false)
        markdown.required.set(false)
        checkstyle.required.set(false)
    }
    exclude {
        it.file.path.run { contains("generated") || contains("buildkonfig") }
    }
}

rootProject.tasks.withType<ReportMergeTask> {
    input.from(
        tasks.withType<Detekt>()
            .map { it.reports.sarif.outputLocation }
    )
}
