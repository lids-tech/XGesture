package com.slideindex.app.overlay.filebrowser

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserOperationsTest {
    @Test
    fun isInside_detectsSelfAndDescendant() {
        assertTrue(FileBrowserOperations.isInside("/a/b", "/a/b"))
        assertTrue(FileBrowserOperations.isInside("/a/b/c", "/a/b"))
        assertFalse(FileBrowserOperations.isInside("/a/bc", "/a/b"))
        assertFalse(FileBrowserOperations.isInside("/a", "/a/b"))
    }

    @Test
    fun uniqueDestination_appendsIndexBeforeExtension() {
        val dir = Files.createTempDirectory("filebrowser-ops").toFile()
        try {
            assertEquals("photo.jpg", FileBrowserOperations.uniqueDestination(dir, "photo.jpg").name)
            dir.resolve("photo.jpg").writeText("x")
            assertEquals("photo (1).jpg", FileBrowserOperations.uniqueDestination(dir, "photo.jpg").name)
            dir.resolve("photo (1).jpg").writeText("x")
            assertEquals("photo (2).jpg", FileBrowserOperations.uniqueDestination(dir, "photo.jpg").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun conflicts_countsExistingNames() {
        val dir = Files.createTempDirectory("filebrowser-ops").toFile()
        try {
            dir.resolve("a.txt").writeText("x")
            dir.resolve("b.txt").writeText("x")
            assertEquals(
                1,
                FileBrowserOperations.conflicts(
                    listOf("/source/a.txt", "/source/c.txt"),
                    dir.absolutePath,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
