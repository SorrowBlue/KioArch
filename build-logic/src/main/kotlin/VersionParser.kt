object VersionParser {
    const val DEFAULT_VERSION = "0.0.0-SNAPSHOT"

    fun parse(gitDescribe: String, defaultVersion: String = DEFAULT_VERSION): String {
        val trimmed = gitDescribe.trim()
        if (trimmed.isEmpty()) return defaultVersion
        
        val tagRegex = """^v([0-9]+)\.([0-9]+)\.([0-9]+)(-(?:alpha|beta|rc)\.[0-9]+)?(?:-([0-9]+)-g([0-9a-fA-F]+))?(-dirty)?$""".toRegex()
        val matchResult = tagRegex.matchEntire(trimmed) ?: return defaultVersion

        val major = matchResult.groupValues[1]
        val minor = matchResult.groupValues[2]
        val patch = matchResult.groupValues[3].toInt()
        val prerelease = matchResult.groupValues[4]
        val commitCount = matchResult.groupValues[5]
        val dirty = matchResult.groupValues[7]

        return if (commitCount.isNotEmpty()) {
            "$major.$minor.${patch + 1}-SNAPSHOT"
        } else {
            "$major.$minor.$patch$prerelease$dirty"
        }
    }
}
