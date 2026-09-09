package com.example.myempty.githubk.ui

/**
 * StudioTheme：Android Studio（IntelliJ Darcula）配色 token，专用于 [EditorPage]。
 *
 * 这套颜色只影响 IDE / 代码编辑器界面，App 其余页面（首页/设置/AI）继续使用
 * [ThemeManager] 的 Material 主题，互不影响。数值对齐 IntelliJ 官方 Darcula
 * 主题的常见色值，让移动端的代码编辑体验在观感上尽量贴近桌面版 Android Studio。
 */
object StudioTheme {
    // 编辑器主体
    val EDITOR_BG = 0xFF2B2B2B.toInt()
    val GUTTER_BG = 0xFF313335.toInt()
    val GUTTER_TEXT = 0xFF606366.toInt()
    val SELECTION_BG = 0xFF214283.toInt()
    val CARET_LINE_BG = 0xFF323232.toInt()

    // 工具栏 / 侧边栏 / 状态栏（IntelliJ 统一使用的中性灰）
    val TOOLBAR_BG = 0xFF3C3F41.toInt()
    val TOOLBAR_BORDER = 0xFF232323.toInt()
    val SIDEBAR_BG = 0xFF3C3F41.toInt()
    val SIDEBAR_HEADER_BG = 0xFF3C3F41.toInt()
    val STATUSBAR_BG = 0xFF3C3F41.toInt()

    // 编辑器标签页
    val TAB_BG = 0xFF3A3D3F.toInt()
    val TAB_ACTIVE_BG = 0xFF2B2B2B.toInt()
    val TAB_ACTIVE_INDICATOR = 0xFF4A88C7.toInt()

    // 文本
    val TEXT_PRIMARY = 0xFFA9B7C6.toInt()
    val TEXT_BRIGHT = 0xFFDFE1E5.toInt()
    val TEXT_MUTED = 0xFF808080.toInt()
    val DIVIDER = 0xFF232323.toInt()

    // 强调色 / 状态色（对齐 Android Studio 工具栏图标语义）
    val ACCENT_BLUE = 0xFF3592C4.toInt()
    val RUN_GREEN = 0xFF59A869.toInt()
    val STOP_RED = 0xFFC75450.toInt()
    val WARNING_YELLOW = 0xFFBBB529.toInt()
    val ERROR_RED = 0xFFBC3F3C.toInt()
    val MODIFIED_DOT = 0xFFE8BF6A.toInt()

    // 项目树文件类型图标配色
    val FOLDER_ICON = 0xFFDCB67A.toInt()
    val KOTLIN_ICON = 0xFFA97BFF.toInt()
    val JAVA_ICON = 0xFFEA9A4C.toInt()
    val XML_ICON = 0xFF59A869.toInt()
    val GRADLE_ICON = 0xFF8DA6D6.toInt()
    val DART_ICON = 0xFF54C5F8.toInt()
    val JSON_ICON = 0xFFCC7832.toInt()
    val DEFAULT_ICON = 0xFF808080.toInt()

    fun fileIconColor(name: String): Int = when {
        name.endsWith(".kt") || name.endsWith(".kts") -> KOTLIN_ICON
        name.endsWith(".java") -> JAVA_ICON
        name.endsWith(".xml") -> XML_ICON
        name.endsWith(".gradle") -> GRADLE_ICON
        name.endsWith(".dart") -> DART_ICON
        name.endsWith(".json") -> JSON_ICON
        else -> DEFAULT_ICON
    }

    fun fileIconGlyph(name: String): String = when {
        name.endsWith(".kt") || name.endsWith(".java") -> "◎"
        name.endsWith(".xml") -> "◇"
        name.endsWith(".gradle") || name.endsWith(".kts") -> "▣"
        name.endsWith(".json") -> "{}"
        name.endsWith(".dart") -> "◆"
        else -> "▪"
    }
}
