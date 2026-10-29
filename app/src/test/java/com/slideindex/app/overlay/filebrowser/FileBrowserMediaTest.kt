package com.slideindex.app.overlay.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserMediaTest {
    @Test
    fun mediaPathsAreStableVirtualLocations() {
        assertEquals(FileBrowserMediaCategory.PICTURES, FileBrowserMediaCategory.forPath("media://pictures"))
        assertEquals(FileBrowserMediaCategory.VIDEOS, FileBrowserMediaCategory.forPath("media://videos"))
        assertEquals(FileBrowserMediaCategory.AUDIO, FileBrowserMediaCategory.forPath("media://audio"))
        assertEquals(FileBrowserMediaCategory.DOCUMENTS, FileBrowserMediaCategory.forPath("media://documents"))
        assertNull(FileBrowserMediaCategory.forPath("/storage/emulated/0/Pictures"))
        assertTrue(FileBrowserMediaCategory.isMediaPath("media://pictures"))
        assertFalse(FileBrowserMediaCategory.isMediaPath("/storage/emulated/0"))
    }

    @Test
    fun normalizeLocation_preservesMediaPathsAndNormalizesFilePaths() {
        assertEquals(
            "media://pictures",
            FileBrowserMedia.normalizeLocation("media://pictures", "/storage/emulated/0"),
        )
        assertEquals(
            "/storage/emulated/0/Download",
            FileBrowserMedia.normalizeLocation("/sdcard/Download", "/storage/emulated/0"),
        )
    }
}
