import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    dokkaPublications.html {
        suppressInheritedMembers.set(false)
        suppressObviousFunctions.set(true)
    }
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        jdkVersion.set(libs.versions.java.get().toInt())

        val srcDir = projectDir.resolve("src")
        if (srcDir.exists()) {
            sourceLink {
                localDirectory.set(srcDir)
                val relPath = rootProject.projectDir.toPath()
                    .relativize(srcDir.toPath())
                    .toString()
                    .replace('\\', '/')
                remoteUrl("https://github.com/SorrowBlue/KioArch/tree/main/$relPath")
                remoteLineSuffix.set("#L")
            }
        }

        externalDocumentationLinks.register("kotlinx-io") {
            url("https://kotlinlang.org/api/kotlinx-io/")
            packageListUrl("https://kotlinlang.org/api/kotlinx-io/package-list")
        }
    }
}
