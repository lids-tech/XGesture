package com.slideindex.app.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `appfilter.xml` 解析器的行为回归。
 *
 * 这些用例覆盖的都是真机上见过的不规范写法：
 * 相对类名、只写包名、drawable 名带 .png、iconback 多属性、scale 越界。
 *
 * 用 Robolectric 是因为 `XmlPullParserFactory` 在裸 JVM 上是 android.jar 的 stub，
 * 直接调会抛 `RuntimeException: Stub!`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class IconPackFilterParserTest {

    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="wechat" />
            <item component="com.taobao.taobao/.MainActivity" drawable="taobao" />
            <item component="com.foo.bar/com.foo.bar.MainActivity" drawable="foo.png" />
            <iconback img="back1" img2="back2" />
            <iconmask img="mask" />
            <iconupon img="upon" />
            <scale factor="0.85" />
        </resources>
    """.trimIndent()

    @Test
    fun `component map hits exact activity`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertEquals(
            "wechat",
            filter.resolve("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
        )
    }

    @Test
    fun `relative class name is completed with package`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertEquals("taobao", filter.resolve("com.taobao.taobao", ".MainActivity"))
    }

    @Test
    fun `package map is used when activity is unknown`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertEquals("foo.png", filter.resolve("com.foo.bar", null))
        assertEquals("foo.png", filter.resolve("com.foo.bar", "com.foo.bar.Other"))
    }

    @Test
    fun `unknown package resolves to null`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertNull(filter.resolve("com.unknown.app", null))
    }

    @Test
    fun `all drawables keep file order and drop duplicates`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertEquals(listOf("wechat", "taobao", "foo.png"), filter.allDrawables)
    }

    @Test
    fun `iconback iconmask iconupon and scale are parsed`() {
        val filter = IconPackFilterParser.parse("com.pack", sample)
        assertEquals(listOf("back1", "back2"), filter.iconBacks)
        assertEquals("mask", filter.iconMask)
        assertEquals("upon", filter.iconUpon)
        assertEquals(0.85f, filter.scaleFactor, 0.0001f)
    }

    @Test
    fun `scale outside range falls back to one`() {
        val tooBig = IconPackFilterParser.parse(
            "com.pack",
            """<resources><scale factor="5" /></resources>""",
        )
        assertEquals(1f, tooBig.scaleFactor, 0.0001f)

        val tooSmall = IconPackFilterParser.parse(
            "com.pack",
            """<resources><scale factor="0.01" /></resources>""",
        )
        assertEquals(1f, tooSmall.scaleFactor, 0.0001f)
    }

    @Test
    fun `malformed component is skipped without dropping the drawable`() {
        val filter = IconPackFilterParser.parse(
            "com.pack",
            """<resources><item component="no-slash" drawable="broken" /></resources>""",
        )
        assertTrue(filter.componentMap.isEmpty())
        assertTrue(filter.packageMap.isEmpty())
        assertEquals(listOf("broken"), filter.allDrawables)
    }

    @Test
    fun `empty resources yields empty filter`() {
        val filter = IconPackFilterParser.parse("com.pack", "<resources />")
        assertTrue(filter.isEmpty)
        assertTrue(filter.componentMap.isEmpty())
        assertTrue(filter.packageMap.isEmpty())
    }

    @Test
    fun `component info wrapper is optional`() {
        val filter = IconPackFilterParser.parse(
            "com.pack",
            """<resources><item component="com.plain.app/com.plain.app.Main" drawable="plain" /></resources>""",
        )
        assertEquals("plain", filter.resolve("com.plain.app", "com.plain.app.Main"))
    }
}
