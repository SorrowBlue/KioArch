val defaultVersion = VersionParser.DEFAULT_VERSION

/**
 * Resolves the project version from Git tag information.
 * Falls back to the default fallback version if Git is not available.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun getVersionFromGit(): String = try {
    val gitDescribe = providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.get()

    VersionParser.parse(gitDescribe, defaultVersion)
} catch (e: Exception) {
    defaultVersion
}

val gitVersion = getVersionFromGit()
version = gitVersion
logger.lifecycle("Set version for ${project.name}: $gitVersion")

gradle.taskGraph.whenReady {
    val hasPublishTask = allTasks.any { it.name.contains("publish", ignoreCase = true) }
    if (hasPublishTask && gitVersion == defaultVersion) {
        throw GradleException(
            "Publishing is prohibited with the fallback version '$defaultVersion'. " +
                "Please ensure you have a valid Git tag checked out and that the repository history is fully fetched (e.g., fetch-depth: 0 in CI)."
        )
    }
}
