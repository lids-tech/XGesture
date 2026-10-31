package com.slideindex.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentUriNormalizerTest {
    private val kugouShortcut =
        "kugousc://search#Intent;action=kugou.intent.action.SHORTCUT;" +
            "launchFlags=0x10008000;" +
            "component=com.kugou.android/.app.splash.shortcut.ShortcutEntryActivity;end"

    @Test
    fun normalize_prependsMissingIntentScheme() {
        assertEquals("intent:$kugouShortcut", IntentUriNormalizer.normalize(kugouShortcut))
    }

    @Test
    fun normalize_keepsAlipayShortcutShape() {
        // 支付宝那条预设也是同样的形态：原样解析不到接收方时会走到这里
        val alipayShortcut = "alipays://platformapi/startapp?appId=20001003&keyword=x" +
            "#Intent;component=com.eg.android.AlipayGphone/com.alipay.mobile.quinox.LauncherActivity.alias;" +
            "B.directly=true;B.fromDesktop=true;end"
        assertEquals("intent:$alipayShortcut", IntentUriNormalizer.normalize(alipayShortcut))
    }

    @Test
    fun normalize_keepsExistingIntentScheme() {
        val uri = "intent:$kugouShortcut"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_keepsAndroidAppScheme() {
        val uri = "android-app://com.example#Intent;action=android.intent.action.VIEW;end"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_keepsHttpLinks() {
        val uri = "https://example.com/path#Intent;action=android.intent.action.VIEW;end"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_keepsIncompleteFragment() {
        // 没有 ;end 收尾：多半是用户只粘了一半，补前缀只会拼出坏串
        val uri = "kugousc://search#Intent;action=kugou.intent.action.SHORTCUT;"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_keepsSentenceEndingWithShortcut() {
        // 句子后面直接跟着一条串：data 部分不是合法 URI，补前缀只会拼出坏串
        val uri = "看看这个 $kugouShortcut"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_keepsUppercaseIntentScheme() {
        val uri = "Intent:$kugouShortcut"
        assertEquals(uri, IntentUriNormalizer.normalize(uri))
    }

    @Test
    fun normalize_trimsSurroundingWhitespace() {
        assertEquals("intent:$kugouShortcut", IntentUriNormalizer.normalize("  $kugouShortcut \n"))
    }

    @Test
    fun normalize_handlesFragmentWithoutData() {
        assertEquals(
            "intent:#Intent;action=android.intent.action.VIEW;end",
            IntentUriNormalizer.normalize("#Intent;action=android.intent.action.VIEW;end"),
        )
    }

    @Test
    fun normalize_keepsPlainUrls() {
        assertEquals("https://example.com", IntentUriNormalizer.normalize("https://example.com"))
        assertEquals("zhihu://search?q=x", IntentUriNormalizer.normalize("zhihu://search?q=x"))
        assertEquals("", IntentUriNormalizer.normalize("   "))
    }

    @Test
    fun isBareIntentFragment_detectsShortcutShape() {
        assertTrue(IntentUriNormalizer.isBareIntentFragment(kugouShortcut))
        assertTrue(IntentUriNormalizer.isBareIntentFragment("#Intent;end"))
        assertFalse(IntentUriNormalizer.isBareIntentFragment("kugousc://search"))
        assertFalse(IntentUriNormalizer.isBareIntentFragment("kugousc://search#Intent;action=x"))
    }
}
