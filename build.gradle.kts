import dev.detekt.gradle.report.ReportMergeTask

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.multiplatformLibrary) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.dokka)
    alias(libs.plugins.detekt) apply false
}

dependencies {
    dokka(projects.kioarch)
}

dokka {
    moduleName.set("KioArch")
}

val reportMerge = tasks.register("reportMerge", ReportMergeTask::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    output.set(rootProject.layout.buildDirectory.file("reports/detekt/merge.sarif"))
}

tasks.register("detektAll") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Run all detekt analyses across the project and build-logic, and merge SARIF reports."
    dependsOn(gradle.includedBuild("build-logic").task(":detektMain"))
    dependsOn(reportMerge)
}
