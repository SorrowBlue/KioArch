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

val typeResolvedSourceSetTasks = setOf(
    "detektMainSourceSet",
    "detektTestSourceSet",
    "detektDebugSourceSet",
    "detektReleaseSourceSet",
    "detektDebugUnitTestSourceSet",
    "detektDebugAndroidTestSourceSet",
    "detektCommonMainSourceSet",
    "detektCommonTestSourceSet",
    "detektAndroidJvmMainSourceSet",
    "detektAndroidJvmTestSourceSet",
    "detektJvmMainSourceSet",
    "detektJvmTestSourceSet",
    "detektJvmDevSourceSet",
    "detektAndroidMainSourceSet",
    "detektAndroidHostTestSourceSet",
    "detektAndroidDeviceTestSourceSet"
)

val detektTasks = tasks.withType<Detekt>().named {
    it != "detekt" && it != "detektDevJvm" && it !in typeResolvedSourceSetTasks
}

rootProject.tasks.named("detektAll").configure {
    dependsOn(detektTasks)
}

rootProject.tasks.withType<ReportMergeTask>().configureEach {
    mustRunAfter(detektTasks)
    input.from(
        detektTasks.map { detektTask ->
            detektTask.reports.sarif.outputLocation.get().asFile
        }
    )
}
