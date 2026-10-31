package com.slideindex.app.iconpack

/**
 * 一个图标包的 `appfilter.xml` 解析结果。
 *
 * - [componentMap]：`包名/类名` → drawable 名（精确匹配）
 * - [packageMap]：包名 → drawable 名（component 未命中时的回退）
 * - [allDrawables]：appfilter 里引用到的全部 drawable 名，用于「包内手动挑」
 * - [scaleFactor] / [iconBacks] / [iconMask] / [iconUpon]：未收录应用的合成参数，
 *   第一版只解析、不合成，留给后续「自动生成同风格图标」使用
 */
data class IconPackFilter(
    val packageName: String,
    val componentMap: Map<String, String>,
    val packageMap: Map<String, String>,
    val allDrawables: List<String>,
    val scaleFactor: Float = 1f,
    val iconBacks: List<String> = emptyList(),
    val iconMask: String? = null,
    val iconUpon: String? = null,
) {

    /** 该包是否收录了 [packageName]，用于跳①列表的状态标记。 */
    fun covers(packageName: String, activityName: String? = null): Boolean =
        resolve(packageName, activityName) != null

    /**
     * 找出这个应用在包里的图标名。
     *
     * 顺序与 Launcher3 / 一墨一致：先按 `ComponentInfo` 精确匹配，
     * 再按包名回退（很多图标包只写包名）。
     */
    fun resolve(packageName: String, activityName: String? = null): String? {
        val cls = activityName?.takeIf { it.isNotBlank() }
        if (cls != null) {
            val key = when {
                // appfilter 里偶尔写成 ".ui.LauncherUI" 这种相对类名
                cls.startsWith(".") -> "$packageName/$packageName$cls"
                // 已经是 "pkg/cls" 的完整形式
                cls.contains('/') -> cls
                else -> "$packageName/$cls"
            }
            componentMap[key]?.let { return it }
        }
        return packageMap[packageName]
    }

    val isEmpty: Boolean get() = allDrawables.isEmpty()
}
