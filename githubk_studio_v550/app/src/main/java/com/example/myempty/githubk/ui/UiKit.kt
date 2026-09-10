package com.example.myempty.githubk.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.myempty.githubk.R

/**
 * UiKit v5：GitHub 官方客户端风格设计系统（AppCompat + 原生 View）。
 * - Material 3 风格 Surface + 1dp Outline + 12/16dp 圆角
 * - Filled / Tonal / Outlined 按钮与状态色 token
 * - 分区标题小号加粗，页面头简洁白底（无渐变）
 * - 颜色统一从 ThemeManager.colors 读取
 */
object UiKit {

    @Suppress("DEPRECATION")
    private fun resourceColor(c: Context, res: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= 23) c.resources.getColor(res, c.theme) else c.resources.getColor(res)

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** 主题色解析：R.color 映射为主题方案色，未映射的返回资源原色。 */
    fun themeColor(c: Context, res: Int): Int {
        val t = ThemeManager.colors
        return when (res) {
            R.color.surface -> t.surface
            R.color.surface_alt -> t.surfaceAlt
            R.color.surface_elevated -> t.surfaceElevated
            R.color.divider -> t.divider
            R.color.primary -> t.primary
            R.color.primary_dark -> t.primaryDark
            R.color.gradient_start -> t.gradientStart
            R.color.gradient_end -> t.gradientEnd
            R.color.accent -> t.accent
            R.color.on_surface -> t.onSurface
            R.color.muted -> t.muted
            R.color.success -> t.success
            R.color.error -> t.error
            R.color.warning -> t.warning
            else -> resourceColor(c, res)
        }
    }

    fun rounded(c: Context, fill: Int, radius: Int, strokeColor: Int? = null, strokeW: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(c, radius).toFloat()
        g.setColor(fill)
        if (strokeColor != null) g.setStroke(dp(c, strokeW), strokeColor)
        return g
    }

    private fun gradient(c: Context, start: Int, end: Int, radius: Int, angle: Int = 0): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
        g.cornerRadius = dp(c, radius).toFloat()
        return g
    }

    /** 带水波纹的背景包装（Android 5.0+）。 */
    private fun ripple(c: Context, base: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        return try {
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((ThemeManager.colors.primary and 0x00FFFFFF) or 0x22000000),
                base,
                null
            )
        } catch (e: Throwable) {
            base
        }
    }

    /** 通用渐变背景（角度 0=LEFT_RIGHT）。 */
    fun gradientBg(c: Context, start: Int, end: Int, radius: Int, angle: Int = 0): GradientDrawable =
        gradient(c, start, end, radius, angle)

    /** 胶囊形选中背景（底部导航）。 */
    fun pillBg(c: Context, fill: Int, strokeColor: Int): GradientDrawable =
        rounded(c, fill, 20, strokeColor, 1)

    /** 简洁页面头：大标题 + 灰色副标题（GitHub 风格，无渐变）。 */
    fun pageHeader(c: Context, title: String, subtitle: String, icon: String? = null): LinearLayout {
        val h = LinearLayout(c)
        h.orientation = LinearLayout.VERTICAL
        h.setPadding(0, dp(c, 6), 0, dp(c, 12))
        val t = TextView(c).apply {
            text = title
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        }
        h.addView(t)
        if (subtitle.isNotBlank()) {
            h.addView(TextView(c).apply {
                text = subtitle
                textSize = 13f
                setTextColor(ThemeManager.colors.muted)
                setPadding(0, dp(c, 3), 0, 0)
            })
        }
        return h
    }

    /** 搜索栏：输入框 + 搜索按钮，返回输入框引用。 */
    fun searchBar(c: Context, hint: String, onSearch: (String) -> Unit): EditText {
        val box = hstack(c)
        val input = EditText(c).apply {
            this.hint = hint
            setHintTextColor(themeColor(c, R.color.muted))
            setTextColor(themeColor(c, R.color.on_surface))
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9))
            isSingleLine = true
            minHeight = dp(c, 48)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(input)
        box.addView(spacer(c, 8))
        box.addView(button(c, "搜索") { onSearch(input.text.toString().trim()) })
        input.tag = box
        return input
    }

    // ---------- 布局 ----------

    fun vstack(c: Context): LinearLayout {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.VERTICAL
        return v
    }

    fun hstack(c: Context): LinearLayout {
        val h = LinearLayout(c)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        return h
    }

    fun spacer(c: Context, h: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
        return v
    }

    fun divider(c: Context): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1))
        v.setBackgroundColor(ThemeManager.colors.divider)
        return v
    }

    // ---------- 文本 ----------

    fun title(c: Context, text: String, size: Float = 22f): TextView =
        TextView(c).apply {
            this.text = text
            this.textSize = size
            setTextColor(themeColor(c, R.color.on_surface))
            typeface = Typeface.DEFAULT_BOLD
        }

    fun section(c: Context, text: String): TextView =
        TextView(c).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColor(c, R.color.muted))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(c, 2), dp(c, 14), dp(c, 2), dp(c, 6))
        }

    fun label(c: Context, text: String, color: Int = R.color.muted, size: Float = 13f): TextView =
        TextView(c).apply {
            this.text = text
            this.textSize = size
            setTextColor(themeColor(c, color))
        }

    fun mono(c: Context, text: String, size: Float = 13f): TextView =
        TextView(c).apply {
            this.text = text
            textSize = size
            typeface = Typeface.MONOSPACE
            setTextColor(themeColor(c, R.color.on_surface))
        }

    // ---------- 卡片 ----------

    fun card(c: Context, content: View): LinearLayout {
        val card = LinearLayout(c)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(c, ThemeManager.colors.surfaceElevated, 16, ThemeManager.colors.divider, 1)
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        card.addView(content)
        return card
    }

    /** 带左侧强调条的卡片（用于列表项）。 */
    fun cardAccent(c: Context, content: View, accentRes: Int = R.color.primary): LinearLayout {
        val wrap = LinearLayout(c)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.background = rounded(c, ThemeManager.colors.surfaceElevated, 16, ThemeManager.colors.divider, 1)
        val bar = View(c)
        bar.layoutParams = LinearLayout.LayoutParams(dp(c, 3), LinearLayout.LayoutParams.MATCH_PARENT)
        bar.setBackgroundColor(themeColor(c, accentRes))
        wrap.addView(bar)
        val body = LinearLayout(c)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        body.addView(content)
        wrap.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return wrap
    }

    // ---------- 按钮 ----------

    fun button(c: Context, text: String, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = ripple(c, rounded(c, ThemeManager.colors.primary, 20))
            minHeight = dp(c, 48)
            setPadding(dp(c, 16), 0, dp(c, 16), 0)
            setOnClickListener { onClick() }
        }

    fun ghostButton(c: Context, text: String, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            setTextColor(themeColor(c, R.color.primary))
            textSize = 13f
            isAllCaps = false
            background = ripple(c, rounded(c, ThemeManager.colors.surfaceAlt, 12, ThemeManager.colors.divider, 1))
            minHeight = dp(c, 44)
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            setOnClickListener { onClick() }
        }

    /** 危险按钮（删除等）。 */
    fun dangerButton(c: Context, text: String, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            setTextColor(themeColor(c, R.color.error))
            textSize = 13f
            isAllCaps = false
            background = rounded(c, ThemeManager.colors.surface, 20, ThemeManager.colors.error, 1)
            minHeight = dp(c, 44)
            setOnClickListener { onClick() }
        }

    /** 胶囊 Chip（可选中态）。 */
    fun chip(c: Context, text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(if (selected) android.graphics.Color.WHITE else themeColor(c, R.color.muted))
            background = if (selected) {
                rounded(c, ThemeManager.colors.primary, 20)
            } else {
                rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            }
            minHeight = dp(c, 40)
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            setOnClickListener { onClick() }
        }

    /** Material 3 风格 Filled Tonal Button。 */
    fun tonalButton(c: Context, text: String, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.primary)
            background = ripple(c, rounded(c, ThemeManager.colors.surfaceAlt, 20, ThemeManager.colors.divider, 1))
            minHeight = dp(c, 44)
            setPadding(dp(c, 16), 0, dp(c, 16), 0)
            setOnClickListener { onClick() }
        }

    /** Material 3 风格 Icon/Outlined action。 */
    fun outlinedButton(c: Context, text: String, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(ThemeManager.colors.onSurface)
            background = ripple(c, rounded(c, ThemeManager.colors.surface, 20, ThemeManager.colors.divider, 1))
            minHeight = dp(c, 44)
            setPadding(dp(c, 14), 0, dp(c, 14), 0)
            setOnClickListener { onClick() }
        }

    // ---------- 输入 ----------

    fun input(c: Context, hint: String, singleLine: Boolean = true): EditText =
        EditText(c).apply {
            this.hint = hint
            setHintTextColor(themeColor(c, R.color.muted))
            setTextColor(themeColor(c, R.color.on_surface))
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            isSingleLine = singleLine
            minHeight = dp(c, 50)
            textSize = 14f
        }

    fun textArea(c: Context, hint: String): EditText =
        EditText(c).apply {
            this.hint = hint
            setHintTextColor(themeColor(c, R.color.muted))
            setTextColor(themeColor(c, R.color.on_surface))
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }

    // ---------- 现代组件 ----------

    /** 大标题 Hero（欢迎页/首页品牌区，白底简洁）。 */
    fun hero(c: Context, title: String, subtitle: String): LinearLayout {
        val h = LinearLayout(c)
        h.orientation = LinearLayout.VERTICAL
        h.setPadding(dp(c, 2), dp(c, 16), dp(c, 2), dp(c, 16))
        val t = TextView(c).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        }
        val s = TextView(c).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, dp(c, 6), 0, 0)
        }
        h.addView(t)
        h.addView(s)
        return h
    }

    /** 圆形首字母头像。 */
    fun avatar(c: Context, text: String, colorRes: Int = R.color.primary): TextView {
        val size = dp(c, 38)
        val tv = TextView(c)
        tv.layoutParams = LinearLayout.LayoutParams(size, size)
        tv.text = text.ifBlank { "?" }.take(1).uppercase()
        tv.gravity = Gravity.CENTER
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 15f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.background = rounded(c, themeColor(c, colorRes), 6)
        return tv
    }

    /** 主题图标：矢量 drawable + 主题色着色（跟随浅色/深色主题）。 */
    fun icon(c: Context, drawableRes: Int, sizeDp: Int = 20, color: Int = ThemeManager.colors.onSurface): ImageView {
        val iv = ImageView(c)
        iv.layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        iv.setImageResource(drawableRes)
        iv.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        return iv
    }

    /** 图标 + 文字的按钮行（现代 GitHub 风格）。 */
    fun iconButton(c: Context, iconRes: Int, text: String, onClick: () -> Unit): LinearLayout {
        val btn = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
            setOnClickListener { onClick() }
        }
        btn.addView(icon(c, iconRes, 18, ThemeManager.colors.primary))
        btn.addView(TextView(c).apply {
            this.text = " $text"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        return btn
    }

    /** 徽章标签（语言/类型等）。 */
    fun badge(c: Context, text: String, colorRes: Int = R.color.primary): TextView =
        TextView(c).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(c, colorRes))
            background = rounded(c, ThemeManager.colors.surfaceAlt, 20)
            setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
        }

    /** 统计项（star / fork 等）。 */
    fun stat(c: Context, icon: String, text: String, colorRes: Int = R.color.on_surface): LinearLayout {
        val h = hstack(c)
        val ic = TextView(c).apply { this.text = icon; textSize = 13f; setTextColor(themeColor(c, R.color.muted)) }
        val t = TextView(c).apply {
            this.text = " $text"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(c, colorRes))
        }
        h.addView(ic)
        h.addView(t)
        return h
    }

    /** 带文本图标的标签（GitHub 风格，无 emoji）。 */
    fun iconLabel(c: Context, icon: String, text: String, colorRes: Int = R.color.on_surface, size: Float = 14f): TextView =
        TextView(c).apply {
            this.text = "$icon  $text"
            textSize = size
            setTextColor(themeColor(c, colorRes))
        }

    /** 页面容器：带安全边距的 vstack。 */
    fun page(c: Context): LinearLayout =
        vstack(c).apply {
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 10))
        }

    // ---------- 工具 ----------

    // ---------- 底部动作弹窗（Material 3 bottom sheet 风格） ----------
    data class SheetItem(
        val icon: String,
        val title: String,
        val subtitle: String? = null,
        val colorRes: Int = R.color.on_surface,
        val onClick: () -> Unit
    )

    private fun sheetItemRow(c: Context, icon: String, title: String, subtitle: String?, colorRes: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 10), dp(c, 8), dp(c, 8), dp(c, 8))
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(c, 3); bottomMargin = dp(c, 3) }
            setOnClickListener { onClick() }
            val iconChar = if (icon.isBlank()) "\u2022" else if (icon.codePointCount(0, icon.length) == 1) icon else icon.take(1)
            addView(TextView(c).apply {
                text = iconChar
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(c, themeColor(c, colorRes), 8)
            }, LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)))
            addView(spacer(c, 11))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(c).apply {
                    text = title
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.onSurface)
                })
                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(c).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(ThemeManager.colors.muted)
                        setPadding(0, dp(c, 2), 0, 0)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            })
            addView(TextView(c).apply { text = "\u203a"; textSize = 20f; setTextColor(ThemeManager.colors.muted) })
        }

    /** 单组底部动作弹窗。 */
    fun sheet(c: Context, title: String, subtitle: String? = null, items: List<SheetItem>): android.app.Dialog =
        groupedSheet(c, title, subtitle, listOf("" to items))

    /** 分组底部动作弹窗（groups 为 (组名, 项列表)，组名为空则不分隔）。 */
    fun groupedSheet(c: Context, title: String, subtitle: String? = null, groups: List<Pair<String, List<SheetItem>>>): android.app.Dialog {
        val dlg = android.app.Dialog(c)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c, ThemeManager.colors.surfaceElevated, 28)
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 12))
        }
        root.addView(View(c).apply { background = rounded(c, ThemeManager.colors.divider, 3) },
            LinearLayout.LayoutParams(dp(c, 44), dp(c, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(c, 2) })
        root.addView(TextView(c).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(0, dp(c, 12), 0, 0)
        })
        if (!subtitle.isNullOrBlank()) {
            root.addView(TextView(c).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(ThemeManager.colors.muted)
                setPadding(0, dp(c, 4), 0, 0)
            })
        }
        root.addView(View(c).apply { setBackgroundColor(ThemeManager.colors.divider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 12); bottomMargin = dp(c, 4) })
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(c).apply { isFillViewport = false }
        scroll.addView(body)
        val maxH = (c.resources.displayMetrics.heightPixels * 0.62).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH))

        groups.forEachIndexed { gi, (gLabel, list) ->
            if (gLabel.isNotBlank()) {
                body.addView(TextView(c).apply {
                    text = gLabel
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(dp(c, 8), if (gi == 0) dp(c, 4) else dp(c, 12), dp(c, 8), dp(c, 2))
                })
                body.addView(View(c).apply { setBackgroundColor(ThemeManager.colors.divider) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 2); bottomMargin = dp(c, 2) })
            }
            list.forEach { it ->
                body.addView(sheetItemRow(c, it.icon, it.title, it.subtitle, it.colorRes) { dlg.dismiss(); it.onClick() })
            }
        }
        body.addView(View(c).apply { setBackgroundColor(ThemeManager.colors.divider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 6) })
        body.addView(ghostButton(c, "\u53d6消") { dlg.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 8) }
        })

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.window?.setLayout((c.resources.displayMetrics.widthPixels * 0.94).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dlg.window?.setGravity(android.view.Gravity.BOTTOM)
        dlg.window?.setDimAmount(0.4f)
        dlg.show()
        return dlg
    }

    fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

    /**
     * v5：GitHub 风格弹窗（白底圆角面板 + 滚动内容 + 底部操作区）。
     */
    fun dialog(c: Context, title: String, view: View, onOk: (() -> Unit)? = null, onCancel: (() -> Unit)? = null): android.app.Dialog {
        val dlg = android.app.Dialog(c)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 12).toFloat()
                setColor(ThemeManager.colors.surfaceElevated)
                setStroke(dp(c, 1), ThemeManager.colors.divider)
            }
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        }
        // 标题
        root.addView(TextView(c).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        // 分隔线
        root.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(c, 10)
                bottomMargin = dp(c, 10)
            }
            setBackgroundColor(ThemeManager.colors.divider)
        })
        // 内容（限制高度，内部滚动）
        val maxH = (c.resources.displayMetrics.heightPixels * 0.6).toInt()
        val scroll = ScrollView(c).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH)
        }
        scroll.addView(view)
        root.addView(scroll)
        // 底部操作
        if (onOk != null || onCancel != null) {
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(c, 12), 0, 0)
            }
            if (onCancel != null) {
                row.addView(ghostButton(c, "取消") { dlg.dismiss(); onCancel() })
                row.addView(spacer(c, 6))
            }
            row.addView(button(c, "确定") { dlg.dismiss(); onOk?.invoke() })
            root.addView(row)
        }
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.window?.setLayout((c.resources.displayMetrics.widthPixels * 0.94).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dlg.show()
        return dlg
    }

    fun confirm(c: Context, title: String, msg: String, onYes: () -> Unit) {
        val box = vstack(c).apply {
            addView(label(c, msg, color = R.color.on_surface))
        }
        dialog(c, title, box, onOk = onYes, onCancel = {})
    }

    fun hideKeyboard(c: Context, v: View) {
        val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** 终端风格输出容器（等宽 + 深色背景）。 */
    fun terminalBox(c: Context, content: View): LinearLayout {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(c, 0xFF0D1117.toInt(), 6)
        box.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        box.addView(content)
        return box
    }

    /**
     * GitHub 风格仓库列表卡片：名称（蓝色）/ owner / 描述 / 底部统计行。
     * 简洁、无花哨装饰，点击打开仓库详情。
     */
    fun repoCard(
        c: Context,
        name: String,
        owner: String,
        desc: String,
        language: String,
        stars: Int,
        forks: Int,
        updatedAt: String,
        avatarColorRes: Int = R.color.primary,
        langColorRes: Int = R.color.accent,
        onClick: (() -> Unit)? = null,
        actions: ((LinearLayout) -> Unit)? = null
    ): LinearLayout {
        val content = vstack(c)

        content.addView(hstack(c).apply {
            addView(vstack(c).apply {
                addView(TextView(c).apply {
                    text = name
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColor(c, R.color.primary))
                })
                addView(TextView(c).apply {
                    text = "@$owner"
                    textSize = 12f
                    setTextColor(themeColor(c, R.color.muted))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        if (desc.isNotBlank()) {
            content.addView(TextView(c).apply {
                text = desc
                textSize = 13f
                setTextColor(themeColor(c, R.color.on_surface))
                setPadding(0, dp(c, 6), 0, 0)
                maxLines = 2
            })
        }
        // 底部统计行
        content.addView(hstack(c).apply {
            setPadding(0, dp(c, 8), 0, 0)
            addView(stat(c, "★", "$stars"))
            addView(spacer(c, 10))
            addView(stat(c, "⑂", "$forks"))
            if (language.isNotBlank()) {
                addView(spacer(c, 10))
                addView(TextView(c).apply {
                    text = "● $language"
                    textSize = 12.5f
                    setTextColor(themeColor(c, langColorRes))
                })
            }
            addView(spacer(c, 8))
            addView(TextView(c).apply {
                text = updatedAt
                textSize = 12f
                setTextColor(themeColor(c, R.color.muted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.END
                }
            })
        })
        if (actions != null) {
            val row = hstack(c).apply {
                setPadding(0, dp(c, 8), 0, 0)
            }
            actions(row)
            content.addView(row)
        }
        val card = LinearLayout(c)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(c, ThemeManager.colors.surfaceElevated, 16, ThemeManager.colors.divider, 1)
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        card.addView(content)
        if (onClick != null) card.setOnClickListener { onClick() }
        return card
    }

    // ---------- v5.2：自定义确认弹窗 / 开关行 ----------

    /**
     * 自定义按钮文案的确认弹窗（Material 圆角面板 + 滚动内容）。
     * okText 为确认按钮文案，danger=true 使用红色主按钮。
     */
    fun confirmEx(
        c: Context,
        title: String,
        content: View,
        okText: String,
        danger: Boolean = false,
        onOk: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): android.app.Dialog {
        val dlg = android.app.Dialog(c)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 12).toFloat()
                setColor(ThemeManager.colors.surfaceElevated)
                setStroke(dp(c, 1), ThemeManager.colors.divider)
            }
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        }
        root.addView(TextView(c).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        root.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(c, 10)
                bottomMargin = dp(c, 10)
            }
            setBackgroundColor(ThemeManager.colors.divider)
        })
        val maxH = (c.resources.displayMetrics.heightPixels * 0.6).toInt()
        val scroll = ScrollView(c).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH)
        }
        scroll.addView(content)
        root.addView(scroll)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(c, 12), 0, 0)
        }
        if (onCancel != null) {
            row.addView(ghostButton(c, "取消") { dlg.dismiss(); onCancel() })
            row.addView(spacer(c, 6))
        }
        val okBtn = if (danger) dangerButton(c, okText) { dlg.dismiss(); onOk() }
                    else button(c, okText) { dlg.dismiss(); onOk() }
        row.addView(okBtn)
        root.addView(row)
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.window?.setLayout((c.resources.displayMetrics.widthPixels * 0.94).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dlg.show()
        return dlg
    }

    /** Material 风格开关行：标题 + 可选副标题 + Switch。 */
    fun switchRow(
        c: Context,
        title: String,
        subtitle: String? = null,
        initial: Boolean = false,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(c, ThemeManager.colors.surface, 12, ThemeManager.colors.divider, 1)
            setPadding(dp(c, 12), dp(c, 6), dp(c, 8), dp(c, 6))
        }
        val col = vstack(c).apply {
            addView(TextView(c).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.colors.onSurface)
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(c).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(0, dp(c, 1), 0, 0)
                })
            }
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = android.widget.Switch(c).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(sw)
        return row
    }
}
