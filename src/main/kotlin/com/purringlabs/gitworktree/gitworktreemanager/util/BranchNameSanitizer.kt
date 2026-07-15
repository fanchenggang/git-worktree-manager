package com.purringlabs.gitworktree.gitworktreemanager.util

/**
 * Shared branch-name sanitization used by create-worktree UI.
 */
object BranchNameSanitizer {
    fun sanitize(input: String): String {
        return input
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9._/-]"), "-")
            .replace(Regex("/+"), "/")
            .replace(Regex("-+"), "-")
            .replace(Regex("(^[-./]+|[-./]+$)"), "")
    }
}
