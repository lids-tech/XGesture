package com.slideindex.app.overlay.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickMenuHomeModuleTest {
    @Test
    fun sanitize_defaultsToRecentsThenFilesThenToolGroups() {
        assertEquals(
            QuickMenuHomeModule.defaultOrder,
            QuickMenuHomeModule.sanitize(emptyList()),
        )
    }

    @Test
    fun sanitize_preservesKnownOrderAndAppendsMissing() {
        assertEquals(
            listOf(
                QuickMenuHomeModule.Files,
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CONVERT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CODE),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TEXT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.SECURITY),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TIME),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TOOL),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.ACTION),
                QuickMenuHomeModule.Recents,
            ),
            QuickMenuHomeModule.sanitize(listOf("FILES", "TOOLS")),
        )
    }

    @Test
    fun sanitize_dropsUnknownAndDuplicates() {
        assertEquals(
            listOf(
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CONVERT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CODE),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TEXT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.SECURITY),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TIME),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TOOL),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.ACTION),
                QuickMenuHomeModule.Recents,
                QuickMenuHomeModule.Files,
            ),
            QuickMenuHomeModule.sanitize(listOf("TOOLS", "NOPE", "TOOLS", "RECENTS")),
        )
    }

    @Test
    fun sanitize_acceptsNewGroupIds() {
        assertEquals(
            listOf(
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TIME),
                QuickMenuHomeModule.Recents,
                QuickMenuHomeModule.Files,
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CONVERT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.CODE),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TEXT),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.SECURITY),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.TOOL),
                QuickMenuHomeModule.ToolGroup(QuickMenuGroup.ACTION),
            ),
            QuickMenuHomeModule.sanitize(listOf("GROUP_TIME")),
        )
    }

    @Test
    fun prefsCodec_keepsSanitizedModuleOrder() {
        val sanitized = QuickMenuPrefsCodec.sanitize(
            QuickMenuPrefs(homeModuleOrder = listOf("FILES")),
        )
        assertEquals(
            listOf(
                "FILES",
                "RECENTS",
                "GROUP_CONVERT",
                "GROUP_CODE",
                "GROUP_TEXT",
                "GROUP_SECURITY",
                "GROUP_TIME",
                "GROUP_TOOL",
                "GROUP_ACTION",
            ),
            sanitized.homeModuleOrder,
        )
    }
}
