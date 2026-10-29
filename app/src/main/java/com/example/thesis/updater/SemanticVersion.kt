package com.example.thesis.updater

/**
 * Pure, immutable semantic version descriptor adhering to SemVer 2.0.0 specifications.
 * Supports standard release triplets (Major.Minor.Patch) and optional pre-release qualifiers.
 *
 * Examples:
 * - `v1.0.4` -> `SemanticVersion(1, 0, 4, null)`
 * - `1.2.0-beta.1` -> `SemanticVersion(1, 2, 0, "beta.1")`
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemanticVersion> {

    companion object {
        private val VERSION_REGEX = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?"""
        )

        /**
         * Safely parses a version string into a [SemanticVersion].
         * Returns `null` if the string cannot be parsed into numeric major/minor components.
         */
        fun parse(versionString: String?): SemanticVersion? {
            if (versionString.isNullOrBlank()) return null
            val trimmed = versionString.trim()
            val match = VERSION_REGEX.find(trimmed) ?: return null

            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val preRelease = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }

            return SemanticVersion(
                major = major,
                minor = minor,
                patch = patch,
                preRelease = preRelease
            )
        }

        /**
         * Evaluates whether [remoteVersion] is strictly newer than [currentVersion].
         *
         * @return `true` if remote is newer; `false` otherwise or if either cannot be parsed.
         */
        fun isUpdateAvailable(remoteVersion: String?, currentVersion: String?): Boolean {
            val remote = parse(remoteVersion) ?: return false
            val current = parse(currentVersion) ?: return false
            return remote > current
        }
    }

    override fun compareTo(other: SemanticVersion): Int {
        if (this.major != other.major) return this.major.compareTo(other.major)
        if (this.minor != other.minor) return this.minor.compareTo(other.minor)
        if (this.patch != other.patch) return this.patch.compareTo(other.patch)

        // A version without pre-release has higher precedence than one with a pre-release
        if (this.preRelease == null && other.preRelease != null) return 1
        if (this.preRelease != null && other.preRelease == null) return -1
        if (this.preRelease != null && other.preRelease != null) {
            return this.preRelease.compareTo(other.preRelease)
        }
        return 0
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch"
        return if (preRelease != null) "$base-$preRelease" else base
    }
}
