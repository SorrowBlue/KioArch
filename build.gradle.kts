import dev.detekt.gradle.report.ReportMergeTask

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.multiplatformLibrary) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.dokka) apply false
    alias(libs.plugins.detekt) apply false

}

val reportMerge = tasks.register("reportMerge", ReportMergeTask::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    output.set(rootProject.layout.buildDirectory.file("reports/detekt/merge.sarif"))
}
