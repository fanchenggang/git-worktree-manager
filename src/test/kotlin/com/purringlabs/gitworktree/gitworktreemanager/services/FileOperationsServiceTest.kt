package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileOperationsServiceTest : BasePlatformTestCase() {

    fun `test copies selected file into destination`() = runBlocking {
        val source = Files.createTempDirectory("gwt-src-")
        val dest = Files.createTempDirectory("gwt-dst-")
        try {
            Files.writeString(source.resolve("cache.txt"), "hello")
            val service = FileOperationsService(project)

            val result = service.copyItems(
                sourceRoot = source,
                destRoot = dest,
                items = listOf(
                    IgnoredFileInfo(
                        relativePath = "cache.txt",
                        type = IgnoredFileInfo.FileType.FILE,
                        sizeBytes = 5,
                        selected = true
                    )
                )
            )

            assertEquals(listOf("cache.txt"), result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertEquals("hello", Files.readString(dest.resolve("cache.txt")))
        } finally {
            source.toFile().deleteRecursively()
            dest.toFile().deleteRecursively()
        }
    }

    fun `test skips unselected items`() = runBlocking {
        val source = Files.createTempDirectory("gwt-src-")
        val dest = Files.createTempDirectory("gwt-dst-")
        try {
            Files.writeString(source.resolve("a.txt"), "a")
            val service = FileOperationsService(project)

            val result = service.copyItems(
                sourceRoot = source,
                destRoot = dest,
                items = listOf(
                    IgnoredFileInfo(
                        relativePath = "a.txt",
                        type = IgnoredFileInfo.FileType.FILE,
                        sizeBytes = 1,
                        selected = false
                    )
                )
            )

            assertTrue(result.succeeded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(Files.notExists(dest.resolve("a.txt")))
        } finally {
            source.toFile().deleteRecursively()
            dest.toFile().deleteRecursively()
        }
    }

    fun `test rejects path traversal outside roots`() = runBlocking {
        val source = Files.createTempDirectory("gwt-src-")
        val dest = Files.createTempDirectory("gwt-dst-")
        try {
            val service = FileOperationsService(project)

            val result = service.copyItems(
                sourceRoot = source,
                destRoot = dest,
                items = listOf(
                    IgnoredFileInfo(
                        relativePath = "../escape.txt",
                        type = IgnoredFileInfo.FileType.FILE,
                        sizeBytes = null,
                        selected = true
                    )
                )
            )

            assertTrue(result.succeeded.isEmpty())
            assertEquals(1, result.failed.size)
            assertTrue(result.failed.first().second.contains("Invalid path"))
        } finally {
            source.toFile().deleteRecursively()
            dest.toFile().deleteRecursively()
        }
    }

    fun `test copies directory recursively without following symlinks`() = runBlocking {
        val source = Files.createTempDirectory("gwt-src-")
        val dest = Files.createTempDirectory("gwt-dst-")
        try {
            val dir = source.resolve("node_modules")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("pkg.json"), "{}")
            val service = FileOperationsService(project)

            val result = service.copyItems(
                sourceRoot = source,
                destRoot = dest,
                items = listOf(
                    IgnoredFileInfo(
                        relativePath = "node_modules",
                        type = IgnoredFileInfo.FileType.DIRECTORY,
                        sizeBytes = null,
                        selected = true
                    )
                )
            )

            assertEquals(listOf("node_modules"), result.succeeded)
            assertTrue(Files.exists(dest.resolve("node_modules/pkg.json")))
        } finally {
            source.toFile().deleteRecursively()
            dest.toFile().deleteRecursively()
        }
    }

    fun `test records failure when source file is missing`() = runBlocking {
        val source = Files.createTempDirectory("gwt-src-")
        val dest = Files.createTempDirectory("gwt-dst-")
        try {
            val service = FileOperationsService(project)

            val result = service.copyItems(
                sourceRoot = source,
                destRoot = dest,
                items = listOf(
                    IgnoredFileInfo(
                        relativePath = "missing.log",
                        type = IgnoredFileInfo.FileType.FILE,
                        sizeBytes = null,
                        selected = true
                    )
                )
            )

            assertTrue(result.succeeded.isEmpty())
            assertEquals(1, result.failed.size)
            assertEquals("missing.log", result.failed.first().first)
        } finally {
            source.toFile().deleteRecursively()
            dest.toFile().deleteRecursively()
        }
    }
}
