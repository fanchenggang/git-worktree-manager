package com.purringlabs.gitworktree.gitworktreemanager.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IgnoredFilesServiceParseTest {

    @Test
    fun `parseIgnoredFiles extracts paths from porcelain v2 ignored lines`() {
        val output = listOf(
            "1 .M N... 100644 100644 100644 abcd abcd README.md",
            "! build/",
            "! node_modules/pkg.json",
            "!  spaced.log",
            "?",
            "! "
        )

        val paths = IgnoredFilesService.parseIgnoredFiles(output)

        assertEquals(
            listOf("build/", "node_modules/pkg.json", "spaced.log"),
            paths
        )
    }

    @Test
    fun `parseIgnoredFiles ignores non-ignored porcelain lines`() {
        val output = listOf(
            "1 A. N... 100644 100644 100644 abcd abcd src/Main.kt",
            "2 R. N... 100644 100644 100644 abcd efgh R100 old.txt new.txt",
            "? untracked.txt"
        )

        assertTrue(IgnoredFilesService.parseIgnoredFiles(output).isEmpty())
    }

    @Test
    fun `parseIgnoredFiles returns empty for blank input`() {
        assertTrue(IgnoredFilesService.parseIgnoredFiles(emptyList()).isEmpty())
    }
}
