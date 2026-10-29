package com.slideindex.app.settings

/**
 * 取词面板图片区底栏：**工具图标组**在左或右；分享引擎主芯片在另一侧。
 *
 * 与设置项标题「图片区工具行位置」的读法一致：`LEFT` = 图标组在左、分享芯片在右。
 */
enum class PickResultImageToolbarPosition(val storageKey: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromStorageKey(key: String?): PickResultImageToolbarPosition =
            when (key) {
                RIGHT.storageKey -> RIGHT
                else -> LEFT
            }
    }
}
