fun getVersionFromGit(): String {
    val defaultVersion = "0.1.0-SNAPSHOT"
    return try {
        val gitDescribe = providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.get()

        if (gitDescribe.isEmpty()) {
            defaultVersion
        } else {
            val tagRegex = """^v\.?([0-9]+\.[0-9]+\.[0-9]+)(.*)$""".toRegex()
            val matchResult = tagRegex.matchEntire(gitDescribe)
            if (matchResult != null) {
                val baseVersion = matchResult.groupValues[1]
                val suffix = matchResult.groupValues[2]
                
                if (suffix.isEmpty()) {
                    baseVersion
                } else {
                    "$baseVersion-SNAPSHOT"
                }
            } else {
                defaultVersion
            }
        }
    } catch (e: Exception) {
        defaultVersion
    }
}

val gitVersion = getVersionFromGit()
version = gitVersion
logger.lifecycle("Set version for ${project.name}: $gitVersion")

