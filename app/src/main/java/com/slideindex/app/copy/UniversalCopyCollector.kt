package com.slideindex.app.copy

/**
 * Portions derived from EdgeGesture (https://github.com/evilgodxu/EdgeGesture)
 * Licensed under GPL-3.0. Modified for com.slideindex.app.
 */

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import com.slideindex.app.service.AccessibilityTextExtractor
import com.slideindex.app.util.SelfAppForeground

data class UniversalCopyBlock(
    val text: String,
    val bounds: Rect
)

object UniversalCopyCollector {
    /**
     * 本应用页面在前台时，本包 Activity 的文本也是要采集的内容，不能按包名整包排除；
     * 只有本应用浮层盖在别的应用上时，才排除本包、改采下层宿主文本。
     */
    fun collectAllFromService(service: AccessibilityService): List<UniversalCopyBlock> {
        val selfPackage = service.packageName
        val activeRoot = service.rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString()
        val blocks = when {
            // 本应用主进程在前台（设置页、编辑页等）：连同本包窗口一起采。
            SelfAppForeground.isForeground(service) -> {
                AccessibilityTextExtractor.collectAllScreenTextBlocks(service)
            }
            // 本应用浮层盖在第三方应用上：activeRoot 指向本包，采底层宿主。
            !activePackage.isNullOrBlank() && activePackage != selfPackage -> {
                AccessibilityTextExtractor.collectScreenTextBlocksFromRoot(activeRoot, service)
            }
            else -> {
                val exclude = if (selfPackage.isNullOrBlank()) emptySet() else setOf(selfPackage)
                AccessibilityTextExtractor.collectAllScreenTextBlocks(service, exclude)
            }
        }
        if (blocks.isNotEmpty()) {
            return blocks.map { UniversalCopyBlock(it.text, Rect(it.bounds)) }
        }
        return AccessibilityTextExtractor.collectScreenTextBlocksFromRoot(activeRoot, service)
            .map { UniversalCopyBlock(it.text, Rect(it.bounds)) }
    }
}
