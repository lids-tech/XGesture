package com.slideindex.app.iconpack

import android.graphics.drawable.Drawable

/**
 * 一个已安装的第三方图标包（ADW / Nova 系）。
 *
 * @param packageName 图标包自身的包名
 * @param label 图标包名称（应用名）
 * @param icon 图标包的应用图标，用于列表展示
 */
data class IconPackInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * 「从图标包选择」的目标应用。
 *
 * @param packageName 目标应用包名
 * @param activityName 启动 Activity 的类名；null 时只按包名回退匹配
 * @param label 目标应用名称（用于页面标题）
 */
data class IconPackTarget(
    val packageName: String,
    val activityName: String?,
    val label: String,
)

/**
 * 图标包约定的 intent 标识。
 *
 * Android 从来没有官方的图标包 API —— Nova / ADW 时代定的这套约定是事实标准，
 * AOSP Launcher3 的 `IconPackHelper`、各家 launcher 都按这几条查。
 */
object IconPackIntents {

    /** 图标包通常在 manifest 里声明这些 action 的 intent-filter。 */
    val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
    )

    /** 少数图标包把主题声明成 launcher 的 category，而不是 action。 */
    val CATEGORIES = listOf(
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    )
}
