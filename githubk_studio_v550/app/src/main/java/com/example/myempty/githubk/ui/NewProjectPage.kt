package com.example.myempty.githubk.ui

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.core.ProjectTemplate
import java.io.File

/**
 * NewProjectPage：新建项目（Android / Compose / Flutter / Web / React Native / 空项目）。
 * GitHub 官方风格：简洁表单 + 类型选择。
 */
class NewProjectPage(private val host: PageHost) {

    private lateinit var nameInput: EditText
    private var type = "Android"

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 10), UiKit.dp(host.context, 14), UiKit.dp(host.context, 8))
        }
        val scroll = ScrollView(host.context).apply {
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 返回
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.ghostButton(host.context, "← 返回") { host.popPage() })
        })
        root.addView(UiKit.pageHeader(host.context, "新建项目", "在本地工作区创建新项目"))

        nameInput = UiKit.input(host.context, "项目名称，如 MyApp")
        root.addView(UiKit.label(host.context, "项目名称", color = com.example.myempty.githubk.R.color.on_surface))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(nameInput)
        root.addView(UiKit.spacer(host.context, 12))

        root.addView(UiKit.label(host.context, "项目类型", color = com.example.myempty.githubk.R.color.on_surface))
        root.addView(UiKit.spacer(host.context, 4))
        val hScroll = HorizontalScrollView(host.context).apply { isHorizontalScrollBarEnabled = false }
        val typeRow = UiKit.hstack(host.context)
        listOf("Android", "Compose", "Flutter", "Web", "React Native", "Xposed", "嵌入式 C", "Linux 工具", "空项目").forEach { t ->
            val chipBtn = UiKit.chip(host.context, t, type == t) {
                type = t
                refreshTypeRow(typeRow)
            }
            chipBtn.tag = t
            typeRow.addView(chipBtn)
            typeRow.addView(UiKit.spacer(host.context, 6))
        }
        hScroll.addView(typeRow)
        root.addView(hScroll)
        root.addView(UiKit.spacer(host.context, 16))

        root.addView(UiKit.button(host.context, "创建项目") { doCreate() })
        root.addView(UiKit.spacer(host.context, 8))
        root.addView(UiKit.label(host.context, "创建后可在「工作区」打开编辑，并通过构建中心编译 APK。",
            color = com.example.myempty.githubk.R.color.muted, size = 12f))

        return scroll
    }

    private fun refreshTypeRow(row: LinearLayout) {
        // 原地刷新 chip 选中态，不重建页面（避免丢失输入）
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val chipBtn = (v as? android.widget.Button) ?: continue
            val label = chipBtn.tag as? String ?: continue
            val selected = label == type
            chipBtn.setTextColor(if (selected) android.graphics.Color.WHITE else ThemeManager.colors.muted)
            chipBtn.background = if (selected) {
                UiKit.rounded(host.context, ThemeManager.colors.primary, 6)
            } else {
                UiKit.rounded(host.context, ThemeManager.colors.surface, 6, ThemeManager.colors.divider, 1)
            }
        }
    }

    private fun doCreate() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { host.toast("请输入项目名称"); return }
        val dir = File(host.state.workspace.rootDir(), name)
        if (dir.exists()) { host.toast("项目已存在"); return }
        try {
            ProjectTemplate.create(dir, name, type)
            host.state.workspace.scan()
            host.toast("项目 $name 创建成功")
            host.state.workspace.project(name)?.let { host.openEditor(it) }
        } catch (e: Throwable) {
            host.toast("创建失败：${e.message}")
        }
    }
}
