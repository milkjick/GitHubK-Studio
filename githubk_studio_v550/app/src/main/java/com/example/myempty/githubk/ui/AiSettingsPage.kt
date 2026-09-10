package com.example.myempty.githubk.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import com.example.myempty.githubk.R

/**
 * AiSettingsPage：AI 配置中心（全屏精美版）。
 *
 * 参照设计稿：
 *  - 服务商名称 / API Key / API Base Url / API 路径
 *  - 是否启用、Response API、回传历史思考过程（开关）
 *  - 获取账户余额 / 配置模型 / 保存
 *  - 内置连接测试，避免“配了却不知道能不能用”。
 */
class AiSettingsPage(private val host: PageHost) : RefreshablePage {

    private val ctx get() = host.context
    private val sec get() = host.state.secure
    private val ai get() = host.state.ai
    private val tc get() = ThemeManager.colors

    /** provider -> (label, base(不含 path), path, 默认模型, 可用模型列表) */
    private data class Preset(val label: String, val base: String, val path: String, val model: String, val models: List<String>)

    private val presets = linkedMapOf(
        "openai" to Preset("OpenAI", "https://api.openai.com/v1", "/chat/completions", "gpt-4o-mini",
            listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")),
        "compatible" to Preset("兼容模式", "https://api.openai.com/v1", "/chat/completions", "gpt-4o-mini",
            listOf("gpt-4o-mini", "gpt-4o", "deepseek-chat", "qwen-plus", "glm-4-flash")),
        "deepseek" to Preset("DeepSeek", "https://api.deepseek.com", "/chat/completions", "deepseek-chat",
            listOf("deepseek-chat", "deepseek-reasoner")),
        "qwen" to Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions", "qwen-plus",
            listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen3-max")),
        "glm" to Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "/chat/completions", "glm-4-flash",
            listOf("glm-4-flash", "glm-4-air", "glm-4-plus", "glm-4.5")),
        "openrouter" to Preset("OpenRouter", "https://openrouter.ai/api/v1", "/chat/completions", "openai/gpt-4o-mini",
            listOf("openai/gpt-4o-mini", "openai/gpt-4o", "anthropic/claude-3.5-sonnet", "google/gemini-2.0-flash")),
        "anthropic" to Preset("Claude", "https://api.anthropic.com", "/v1/messages", "claude-3-5-haiku-latest",
            listOf("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-7-sonnet-latest")),
        "gemini" to Preset("Gemini", "https://generativelanguage.googleapis.com/v1beta/models/{model}", ":generateContent", "gemini-2.0-flash",
            listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-pro", "gemini-1.5-flash")),
        "local" to Preset("本地 AI (Ollama/LM Studio)", "http://127.0.0.1:11434/v1", "/chat/completions", "llama3.1",
            listOf("llama3.1", "llama3.2", "qwen2.5-coder", "deepseek-coder-v2", "codellama"))
    )

    private val providers = presets.keys.toList()

    private var provider = ""
    private lateinit var providerLabel: TextView
    private lateinit var keyInput: EditText
    private lateinit var baseInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var swEnabled: Switch
    private lateinit var swResponse: Switch
    private lateinit var swReasoning: Switch
    private lateinit var summaryLabel: TextView
    private var keyVisible = false
    private var rootRef: LinearLayout? = null

    fun buildView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.surface)
        }

        rootRef = root
        root.tag = this
        buildInto(root)
        return root
    }

    override fun refresh() { rootRef?.let { buildInto(it) } }

    private fun buildInto(root: LinearLayout) {
        root.removeAllViews()
        val cfg = ai.config()
        provider = cfg.provider.ifBlank { "compatible" }
        if (!presets.containsKey(provider)) provider = "compatible"

        // ============ 顶栏 ============
        val bar = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(tc.surfaceElevated)
            orientation = LinearLayout.HORIZONTAL
        }
        bar.addView(UiKit.button(ctx, "‹") { host.popPage() })
        bar.addView(TextView(ctx).apply {
            text = "AI 配置中心"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tc.onSurface)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(bar)

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ============ 头部 ============
        body.addView(TextView(ctx).apply {
            text = "AI 服务配置"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tc.onSurface)
        })
        body.addView(TextView(ctx).apply {
            text = "支持 OpenAI 兼容 / DeepSeek / 通义千问 / 智谱 GLM / Gemini / Claude / OpenRouter"
            textSize = 11.5f
            setTextColor(tc.muted)
            setPadding(0, dp(2), 0, 0)
        })
        body.addView(spacer(10))

        // ============ 0. 已保存配置（多配置并存） ============
        body.addView(card {
            addView(cardTitle("已保存配置"))
            addView(TextView(ctx).apply {
                text = "可同时保存多套服务商配置，点击切换启用；＋新增一套。"
                textSize = 11f
                setTextColor(tc.muted)
                setPadding(0, dp(2), 0, dp(8))
            })
            val h = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ai.profiles().forEach { p ->
                row.addView(UiKit.chip(ctx, p.name, p.id == ai.activeId()) {
                    if (p.id != ai.activeId()) { ai.setActive(p.id); host.rebuildCurrentPage() }
                })
            }
            row.addView(UiKit.chip(ctx, "＋ 新增", false) {
                ai.newBlank("配置 ${ai.profiles().size + 1}")
                host.rebuildCurrentPage()
            })
            h.addView(row)
            addView(h)
            addView(UiKit.ghostButton(ctx, "删除当前配置：${ai.activeProfile().name}") {
                if (ai.profiles().size <= 1) { host.toast("至少保留一套配置"); return@ghostButton }
                val name = ai.activeProfile().name
                ai.deleteProfile(ai.activeId())
                host.toast("已删除配置：$name")
                host.rebuildCurrentPage()
            }.apply { setTextColor(tc.error) })
        })
        body.addView(spacer(10))

        // ============ 1. 服务商 ============
        body.addView(card {
            addView(cardTitle("服务商"))
            addView(TextView(ctx).apply {
                text = "选择服务商会自动填入对应的 Base URL、API 路径与默认模型，可继续手动修改。"
                textSize = 11f
                setTextColor(tc.muted)
                setPadding(0, dp(2), 0, dp(8))
            })
            // chips 横向滚动
            val h = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
            }
            providers.forEach { p ->
                row.addView(UiKit.chip(ctx, presets[p]!!.label, p == provider) {
                    provider = p
                    applyPreset(p)
                    renderChips(row)
                })
                row.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
            }
            h.addView(row)
            addView(h)
            addView(spacer(6))
            providerLabel = TextView(ctx).apply {
                textSize = 12f
                setTextColor(tc.accent)
                setPadding(0, dp(2), 0, 0)
            }
            addView(providerLabel)
        })
        body.addView(spacer(10))

        // ============ 2. 连接信息 ============
        body.addView(card {
            addView(cardTitle("连接信息"))
            addView(fieldLabel("API Key"))
            keyInput = EditText(ctx).apply {
                hint = "sk-...（本地 AI 可留空）"
                setText(cfg.key)
                textSize = 13.5f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setPadding(dp(4), 0, dp(4), 0)
            }
            addView(keyInput)
            addView(spacer(4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(UiKit.ghostButton(ctx, if (keyVisible) "隐藏 Key" else "显示 Key") {
                    keyVisible = !keyVisible
                    keyInput.inputType = if (keyVisible)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    keyInput.setSelection(keyInput.text?.length ?: 0)
                })
                addView(spacer(6))
                addView(UiKit.ghostButton(ctx, "清除 Key") { keyInput.setText("") })
            })
            addView(spacer(6))

            addView(fieldLabel("API Base URL"))
            baseInput = EditText(ctx).apply {
                textSize = 13.5f
                setSingleLine(true)
                setPadding(dp(4), 0, dp(4), 0)
            }
            addView(baseInput)
            addView(spacer(6))

            addView(fieldLabel("API 路径"))
            pathInput = EditText(ctx).apply {
                textSize = 13.5f
                setSingleLine(true)
                hint = "/chat/completions"
                setPadding(dp(4), 0, dp(4), 0)
            }
            addView(pathInput)
            addView(TextView(ctx).apply {
                text = "两者拼接后即为请求 Endpoint；保留 /chat/completions 等默认值即可。"
                textSize = 10.5f
                setTextColor(tc.muted)
                setPadding(0, dp(2), 0, 0)
            })
            addView(spacer(6))

            addView(fieldLabel("模型"))
            modelInput = EditText(ctx).apply {
                textSize = 13.5f
                setSingleLine(true)
                setPadding(dp(4), 0, dp(4), 0)
            }
            addView(modelInput)
            addView(spacer(6))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(UiKit.button(ctx, "配置模型") { showModelPicker() })
                addView(spacer(6))
                addView(UiKit.ghostButton(ctx, "恢复默认") { applyPreset(provider, resetAll = true) })
            })
        })
        body.addView(spacer(10))

        // ============ 3. 功能开关 ============
        body.addView(card {
            addView(cardTitle("功能选项"))
            swEnabled = Switch(ctx).apply {
                isChecked = cfg.enabled
                text = "启用 AI"
                textSize = 14f
                setTextColor(tc.onSurface)
            }
            addView(swEnabled)
            addView(descText("关闭后所有对话 / 任务执行会提示“AI 已停用”。"))
            addView(divider())
            swResponse = Switch(ctx).apply {
                text = "Response API"
                textSize = 14f
                setTextColor(tc.onSurface)
            }
            addView(swResponse)
            addView(descText("仅 OpenAI 兼容端点使用 /v1/responses 协议时生效；切换开关会自动调整 API 路径。"))
            addView(divider())
            swReasoning = Switch(ctx).apply {
                text = "回传历史思考过程"
                textSize = 14f
                setTextColor(tc.onSurface)
            }
            addView(swReasoning)
            addView(descText("模型返回思维链时，在对话气泡中额外展示思考过程（不影响工具调用）。"))
        })
        body.addView(spacer(10))

        // ============ 4. 操作按钮 ============
        body.addView(card {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(UiKit.button(ctx, "测试连接") { testConnection() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(spacer(6))
                addView(UiKit.ghostButton(ctx, "获取账户余额") { fetchBalance() })
            })
            addView(spacer(6))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(UiKit.tonalButton(ctx, "保存配置") { save() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
            addView(spacer(6))
            summaryLabel = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(tc.muted)
                setPadding(0, dp(2), 0, 0)
            }
            addView(summaryLabel)
        })

        // 载入当前配置
        loadFrom(cfg)
    }

    // ================= UI 辅助 =================

    private fun loadFrom(cfg: com.example.myempty.githubk.ai.AiConfig) {
        provider = cfg.provider.ifBlank { "compatible" }
        if (!presets.containsKey(provider)) provider = "compatible"
        // endpoint 反解 base/path
        var base = ""
        var path = ""
        var endpoint = cfg.endpoint.ifBlank { "" }
        val suffixes = listOf("/chat/completions", "/v1/chat/completions", "/v1/messages", "/v1/responses", ":generateContent")
        for (s in suffixes) {
            if (endpoint.endsWith(s)) {
                base = endpoint.removeSuffix(s)
                path = s
                break
            }
        }
        if (base.isBlank()) { base = endpoint; path = "" }
        // 有 provider 预设且 endpoint 空白时直接填预设
        if (endpoint.isBlank()) {
            val pre = presets[provider]!!
            base = pre.base
            path = if (provider == "gemini") pre.path else pre.path
        }
        baseInput.setText(base)
        pathInput.setText(path)
        modelInput.setText(cfg.model.ifBlank { presets[provider]?.model ?: "" })
        swEnabled.isChecked = cfg.enabled
        swResponse.isChecked = cfg.responseApi
        swReasoning.isChecked = cfg.reasoning
        if (cfg.key.isBlank()) keyInput.setText("") else keyInput.setText(cfg.key)
        swResponse.isEnabled = provider == "openai" || provider == "compatible"
        swResponse.setOnCheckedChangeListener { _, checked ->
            val curPath = pathInput.text.toString().trim()
            if (checked) {
                swResponse.isEnabled = true
                if (curPath.isEmpty() || curPath.endsWith("/chat/completions")) pathInput.setText("/v1/responses")
            } else {
                if (curPath.endsWith("/v1/responses")) pathInput.setText("/chat/completions")
            }
        }
        renderSummary()
        providerLabel.text = "当前：${presets[provider]?.label ?: provider}"
    }

    private fun applyPreset(p: String, resetAll: Boolean = false) {
        val pre = presets[p] ?: return
        baseInput.setText(pre.base)
        if (p == "gemini") pathInput.setText(pre.path) else pathInput.setText(pre.path)
        if (resetAll || modelInput.text.isNullOrBlank()) modelInput.setText(pre.model)
        swResponse.isEnabled = p == "openai" || p == "compatible"
        if (!swResponse.isEnabled) swResponse.isChecked = false
        providerLabel.text = "当前：${pre.label}"
    }

    private fun renderChips(row: LinearLayout) {
        row.removeAllViews()
        providers.forEach { p ->
            row.addView(UiKit.chip(ctx, presets[p]!!.label, p == provider) {
                provider = p
                applyPreset(p)
                renderChips(row)
            })
            row.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        }
    }

    private fun currentEndpoint(): String {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val path = pathInput.text.toString().trim()
        val ep = when {
            path.isBlank() -> base
            path.startsWith("/") || path.startsWith(":") -> base + path
            else -> base + "/" + path
        }
        return ep
    }

    private fun renderSummary() {
        val lines = mutableListOf<String>()
        lines.add("服务商 ${ai.providerLabel(provider)} · ${if (swEnabled.isChecked) "已启用" else "已停用"}")
        lines.add("模型 ${modelInput.text.toString().trim().ifBlank { "未设置" }}")
        val keyTxt = keyInput.text.toString().trim()
        val keyOptional = provider == "local"
        lines.add(when {
            keyTxt.isNotBlank() -> "API Key 已配置"
            keyOptional -> "本地 AI：无需 API Key"
            else -> "⚠ 未设置 API Key"
        })
        if (currentEndpoint().isNotBlank()) lines.add("Endpoint ${currentEndpoint()}")
        summaryLabel.text = lines.joinToString("\n")
        summaryLabel.setTextColor(if (keyTxt.isBlank() && !keyOptional) tc.warning else tc.muted)
    }

    private fun save() {
        val base = baseInput.text.toString().trim()
        if (base.isBlank()) { host.toast("请填写 API Base URL"); return }
        val model = modelInput.text.toString().trim()
        if (model.isBlank()) { host.toast("请填写模型名称"); return }
        val oldKey = ai.activeProfile().key
        val newKey = keyInput.text.toString().trim()
        ai.saveConfig(
            provider = provider,
            endpoint = currentEndpoint(),
            model = model,
            key = if (newKey != oldKey) newKey else null,
            enabled = swEnabled.isChecked,
            responseApi = swResponse.isChecked && (provider == "openai" || provider == "compatible"),
            reasoning = swReasoning.isChecked
        )
        renderSummary()
        host.toast("AI 配置已保存")
        host.rebuildCurrentPage()
    }

    private fun testConnection() {
        host.toast("正在测试连接…")
        summaryLabel.text = "正在测试连接…"
        Thread {
            val r = ai.test()
            host.runUi {
                summaryLabel.text = r
                summaryLabel.setTextColor(if (r.startsWith("连接成功")) tc.success else tc.error)
            }
        }.start()
    }

    private fun fetchBalance() {
        host.toast("正在查询余额…")
        summaryLabel.text = "正在查询余额…"
        Thread {
            val r = ai.fetchBalanceText()
            host.runUi {
                summaryLabel.text = r
                summaryLabel.setTextColor(if (r.contains("失败") || r.startsWith("未")) tc.warning else tc.success)
            }
        }.start()
    }

    private fun showModelPicker() {
        val list = presets[provider]?.models ?: return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        box.addView(TextView(ctx).apply {
            text = "选择 ${presets[provider]?.label} 模型，或直接在上方输入自定义模型名："
            textSize = 12f
            setTextColor(tc.muted)
            setPadding(0, 0, 0, dp(6))
        })
        var dlg: android.app.Dialog? = null
        list.forEach { m ->
            box.addView(UiKit.ghostButton(ctx, m) {
                modelInput.setText(m)
                dlg?.dismiss()
            })
            box.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(4)) })
        }
        dlg = UiKit.dialog(ctx, "配置模型", box, onCancel = {})
    }

    // ---- 组件工厂 ----

    private fun card(content: LinearLayout.() -> Unit): LinearLayout {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(ctx, tc.surfaceElevated, 16, tc.divider, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            content()
        }
        return v
    }

    private fun cardTitle(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(tc.onSurface)
        setPadding(0, 0, 0, dp(4))
    }

    private fun fieldLabel(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(tc.muted)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun descText(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(tc.muted)
        setPadding(dp(4), 0, dp(4), dp(6))
    }

    private fun divider(): View = View(ctx).apply {
        background = UiKit.rounded(ctx, tc.divider, 1, null, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4); bottomMargin = dp(8)
        }
    }

    private fun spacer(h: Int): Space = Space(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(v: Int): Int = UiKit.dp(ctx, v)
}
