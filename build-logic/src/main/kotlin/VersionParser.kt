object VersionParser {
    const val DEFAULT_VERSION = "0.0.0-SNAPSHOT"

    /**
     * Parses the given [gitDescribe] tag output into a valid semantic version string.
     *
     * @param gitDescribe The string output from the `git describe` command.
     * @param defaultVersion The version to fall back to in case parsing is unsuccessful.
     * @return The parsed semantic version or the fallback version.
     */
    fun parse(gitDescribe: String, defaultVersion: String = DEFAULT_VERSION): String {
        val trimmed = gitDescribe.trim()
        val tagRegex = (
            """^v([0-9]+)\.([0-9]+)\.([0-9]+)""" +
                """(-(?:alpha|beta|rc)\.[0-9]+)?(?:-([0-9]+)-g([0-9a-fA-F]+))?(-dirty)?$"""
            ).toRegex()
        val matchResult = if (trimmed.isNotEmpty()) tagRegex.matchEntire(trimmed) else null

        return if (matchResult != null) {
            val major = matchResult.groupValues[1]
            val minor = matchResult.groupValues[2]
            val patch = matchResult.groupValues[3].toInt()
            val prerelease = matchResult.groupValues[4]
            val commitCount = matchResult.groupValues[5]
            val dirty = matchResult.groupValues[7]

            if (commitCount.isNotEmpty()) {
                "$major.$minor.${patch + 1}-SNAPSHOT"
            } else {
                "$major.$minor.$patch$prerelease$dirty"
            }
        } else {
            defaultVersion
        }
    }
}
