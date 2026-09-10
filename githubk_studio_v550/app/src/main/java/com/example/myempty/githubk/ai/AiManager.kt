package com.example.myempty.githubk.ai

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import com.example.myempty.githubk.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiConfig(
    val provider: String,
    val endpoint: String,
    val model: String,
    val key: String,
    val enabled: Boolean = true,
    val responseApi: Boolean = false,
    val reasoning: Boolean = false
)

data class ChatMessage(val role: String, val content: String)

/** 模型返回内容 + 思维链。reasoning 仅在配置开启时填充，用于 UI 展示。 */
data class AiProfile(
    val id: String,
    val name: String,
    val provider: String = "compatible",
    val endpoint: String = "",
    val model: String = "",
    val key: String = "",
    val enabled: Boolean = true,
    val responseApi: Boolean = false,
    val reasoning: Boolean = false
)

data class AiReply(val content: String, val reasoning: String = "")

/**
 * AiManager v3.0
 * - 服务商：openai / compatible / deepseek / qwen / glm / openrouter / anthropic / gemini
 * - 新增：启用开关、Response API（OpenAI /v1/responses）、思维链解析（DeepSeek 等）
 * - 新增：连接测试 test()、账户余额查询 fetchBalanceText()（DeepSeek / OpenRouter）
 * - 新增：长历史自动裁剪，避免 token 溢出
 * - Gemini Key 走 x-goog-api-key 请求头；所有 IO 显式 UTF-8
 */
class AiManager(private val c: Context) {

    private val sec = SecureStore(c)

    // ---------- 活动请求跟踪（供 Agent「随时停止」中断单次网络等待） ----------
    @Volatile
    private var activeConn: HttpURLConnection? = null
    private val connLock = Any()
    @Volatile private var cancelRequested = false

    /** 中断当前正在进行的 AI 请求（若在等待网络则立即抛异常返回）。 */
    fun cancelActiveRequest() {
        cancelRequested = true
        synchronized(connLock) { activeConn }?.disconnect()
    }

    private fun trackConn(x: HttpURLConnection) {
        cancelRequested = false
        synchronized(connLock) { activeConn = x }
    }

    private fun untrackConn(x: HttpURLConnection) {
        synchronized(connLock) { if (activeConn === x) activeConn = null }
    }

    private val presets = mapOf(
        "openai" to Pair("https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        "compatible" to Pair("https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        "deepseek" to Pair("https://api.deepseek.com/chat/completions", "deepseek-chat"),
        "qwen" to Pair("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
        "glm" to Pair("https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash"),
        "openrouter" to Pair("https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini"),
        "anthropic" to Pair("https://api.anthropic.com/v1/messages", "claude-3-5-haiku-latest"),
        "gemini" to Pair("https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", "gemini-2.0-flash"),
        // 本地/自托管：Ollama 默认监听 11434，走 OpenAI 兼容的 /v1/chat/completions；
        // LM Studio、llama.cpp server、vLLM 等同样兼容此路径，通常无需 API Key。
        "local" to Pair("http://127.0.0.1:11434/v1/chat/completions", "llama3.1")
    )

    /** 这些服务商通常无需 API Key（本地/自托管），允许 Key 留空。 */
    private val keyOptionalProviders = setOf("local")

    fun config(): AiConfig = configOf(activeProfile())

    private fun configOf(p: AiProfile): AiConfig =
        AiConfig(p.provider, p.endpoint, p.model, p.key, p.enabled, p.responseApi, p.reasoning)

    fun profileById(id: String?): AiProfile? =
        if (id.isNullOrBlank()) null else loadProfiles().firstOrNull { it.id == id }

    /** 校验某 profile 可用（为空则用当前激活配置）。 */
    fun ensureValid(cfg: AiConfig) {
        if (!cfg.enabled) throw IllegalStateException("AI 已在设置中停用")
        if (cfg.key.isBlank() && !keyOptional(cfg.provider)) throw IllegalStateException("未配置 API Key")
        if (cfg.endpoint.isBlank()) throw IllegalStateException("未配置 API Endpoint")
        if (cfg.model.isBlank()) throw IllegalStateException("未配置模型名称")
    }

    fun saveConfig(
        provider: String,
        endpoint: String,
        model: String,
        key: String?,
        enabled: Boolean,
        responseApi: Boolean,
        reasoning: Boolean
    ) {
        val list = loadProfiles().toMutableList()
        val id = activeId()
        val idx = list.indexOfFirst { it.id == id }
        val base = if (idx >= 0) list[idx] else defaultProfile()
        val newKey = when {
            key == null -> base.key
            key.isBlank() -> ""
            else -> key.trim()
        }
        val updated = base.copy(
            provider = provider.trim().lowercase().ifBlank { "compatible" },
            endpoint = endpoint.trim(),
            model = model.trim(),
            key = newKey,
            enabled = enabled,
            responseApi = responseApi,
            reasoning = reasoning
        )
        if (idx >= 0) list[idx] = updated else list.add(updated)
        persistProfiles(list)
        sec.put("ai_active", updated.id)
    }

    fun removeKey() {
        val list = loadProfiles().toMutableList()
        val id = activeId()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) { list[idx] = list[idx].copy(key = ""); persistProfiles(list) }
    }

    // ============ 多配置管理（profile 并存） ============

    private fun newId(): String = "p" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt()

    private fun defaultProfile(): AiProfile = AiProfile(
        newId(), "默认",
        sec.get("provider").orEmpty().ifBlank { "compatible" },
        sec.get("endpoint") ?: "",
        sec.get("model") ?: "",
        sec.get("key") ?: "",
        sec.get("ai_enabled") != "0",
        sec.get("ai_response_api") == "1",
        sec.get("ai_reasoning") == "1"
    )

    private fun persistProfiles(list: List<AiProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id).put("name", p.name).put("provider", p.provider)
                .put("endpoint", p.endpoint).put("model", p.model).put("key", p.key)
                .put("enabled", p.enabled).put("responseApi", p.responseApi).put("reasoning", p.reasoning))
        }
        sec.put("ai_profiles", arr.toString())
    }

    private fun loadProfiles(): MutableList<AiProfile> {
        migrateIfNeeded()
        val raw = sec.get("ai_profiles") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<AiProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(AiProfile(
                o.optString("id"),
                o.optString("name").ifBlank { "配置 ${i + 1}" },
                o.optString("provider").ifBlank { "compatible" },
                o.optString("endpoint"),
                o.optString("model"),
                o.optString("key"),
                o.optBoolean("enabled", true),
                o.optBoolean("responseApi", false),
                o.optBoolean("reasoning", false)
            ))
        }
        return out
    }

    private fun migrateIfNeeded() {
        if (sec.get("ai_profiles") != null) return
        val p = defaultProfile()
        persistProfiles(listOf(p))
        sec.put("ai_active", p.id)
    }

    fun profiles(): List<AiProfile> = loadProfiles()

    fun activeId(): String {
        migrateIfNeeded()
        val a = sec.get("ai_active") ?: ""
        val list = loadProfiles()
        return if (list.any { it.id == a }) a else list.firstOrNull()?.id ?: ""
    }

    fun activeProfile(): AiProfile {
        migrateIfNeeded()
        val list = loadProfiles()
        val id = sec.get("ai_active") ?: ""
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
            ?: run { val p = defaultProfile(); persistProfiles(listOf(p)); sec.put("ai_active", p.id); p }
    }

    fun newBlank(name: String = "新配置"): AiProfile {
        val p = AiProfile(newId(), name)
        saveProfile(p)
        return p
    }

    fun saveProfile(p: AiProfile) {
        val list = loadProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        persistProfiles(list)
        sec.put("ai_active", p.id)
    }

    fun deleteProfile(id: String): Boolean {
        val list = loadProfiles().toMutableList()
        val new = list.filter { it.id != id }
        if (new.size == list.size) return false
        persistProfiles(new)
        if (sec.get("ai_active") == id) sec.put("ai_active", new.firstOrNull()?.id ?: "")
        return true
    }

    fun setActive(id: String) { if (loadProfiles().any { it.id == id }) sec.put("ai_active", id) }

    /** 服务商显示名 */
    fun providerLabel(p: String): String = when (p.lowercase()) {
        "openai" -> "OpenAI"
        "deepseek" -> "DeepSeek"
        "qwen" -> "通义千问"
        "glm" -> "智谱 GLM"
        "gemini" -> "Gemini"
        "anthropic" -> "Claude"
        "openrouter" -> "OpenRouter"
        "local" -> "本地 AI (Ollama/LM Studio)"
        else -> "兼容模式"
    }

    /** 当前服务商是否允许不填 API Key（本地/自托管）。 */
    fun keyOptional(provider: String = config().provider): Boolean =
        provider.lowercase() in keyOptionalProviders || isPrivateHost(config().endpoint)

    /** 判定自定义端点为私网/本机（局域网自托管、本机服务）——这类服务通常无需鉴权。 */
    private fun isPrivateHost(endpoint: String): Boolean {
        val host = Regex("^https?://([^/:]+)").find(endpoint)?.groupValues?.get(1) ?: return false
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return true
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.startsWith("172.")) {
            val b = host.split(".").getOrNull(1)?.toIntOrNull() ?: -1
            return b in 16..31
        }
        return false
    }

    fun dialog(ctx: Context, onSaved: () -> Unit = {}) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val provRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val providers = listOf("compatible", "openai", "deepseek", "qwen", "glm", "gemini", "anthropic", "openrouter", "local")
        var selected = sec.get("provider").orEmpty().ifBlank { "compatible" }
        val provider = EditText(ctx).apply { hint = "Provider"; setText(selected); textSize = 13f }
        fun renderProviderRow() {
            provRow.removeAllViews()
            providers.forEach { p ->
                val chip = com.example.myempty.githubk.ui.UiKit.chip(ctx, providerLabel(p), selected == p) {
                    selected = p; provider.setText(p); renderProviderRow()
                }
                provRow.addView(chip)
                provRow.addView(android.widget.Space(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(6.dp(ctx), 1)
                })
            }
        }
        val endpoint = EditText(ctx).apply { hint = "Endpoint（留空用预设）"; setText(sec.get("endpoint") ?: ""); textSize = 13f }
        val model = EditText(ctx).apply { hint = "Model"; setText(sec.get("model") ?: ""); textSize = 13f }
        val key = EditText(ctx).apply { hint = "API Key"; setSingleLine(true); textSize = 13f }
        box.addView(com.example.myempty.githubk.ui.UiKit.label(ctx, "服务商", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        box.addView(provRow)
        box.addView(android.widget.Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 8.dp(ctx)) })
        listOf(provider, endpoint, model, key).forEach {
            box.addView(it)
            box.addView(android.widget.Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 6.dp(ctx)) })
        }
        box.addView(com.example.myempty.githubk.ui.UiKit.label(ctx, "提示：进入“设置 → AI 配置中心”可获得完整精美配置界面（模型、开关、测试连接等）。", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        renderProviderRow()
        com.example.myempty.githubk.ui.UiKit.dialog(ctx, "AI Provider（简版）", box, onOk = {
            val p = provider.text.toString().trim().lowercase()
            val preset = presets[p]
            sec.put("provider", p)
            sec.put("endpoint", endpoint.text.toString().trim().ifBlank { preset?.first ?: "" })
            sec.put("model", model.text.toString().trim().ifBlank { preset?.second ?: "" })
            if (key.text.isNotBlank()) sec.put("key", key.text.toString().trim())
            onSaved()
        }, onCancel = {})
    }

    private fun Int.dp(c: Context): Int = (this * c.resources.displayMetrics.density).toInt()

    /** 单轮（保留兼容 2.4 接口） */
    fun runAgent(task: String, context: String): String {
        val cfg = config()
        if (!cfg.enabled) return "AI 已在设置中停用，请到“AI 配置中心”开启。"
        if (cfg.key.isBlank() && !keyOptional(cfg.provider)) return "请先在设置中配置 AI API Key。"
        return try {
            chatDetailed(listOf(ChatMessage("user", task)), context).content
        } catch (e: Throwable) {
            "AI 请求失败：${e.message}"
        }
    }

    /**
     * 多轮对话（返回纯文本内容，兼容旧调用）。
     */
    fun chat(messages: List<ChatMessage>, system: String? = null): String =
        chatDetailed(messages, system).content

    /**
     * 上下文总结辅助：把一段历史/内容压缩为要点摘要，供会话上下文管理使用。
     * prompt 控制总结风格；失败时返回降级信息而不是抛异常。
     */
    fun summarize(text: String, prompt: String = "把以下内容压缩为简洁的中文要点摘要，保留关键目标、已完成事项、待办与文件路径。"): String {
        if (text.isBlank()) return ""
        val chars = text.length
        val content = if (chars > 60000) text.take(60000) + "\n...(内容过长已截断)" else text
        return try {
            chat(listOf(ChatMessage("user", "$prompt\n\n$content")), "You are a helpful summarizer.")
        } catch (e: Throwable) {
            "（摘要失败：${e.message}）"
        }
    }

    /**
     * 视觉图片分析：上传本地图片 + 用户提问，由模型解析截图/图片。
     * 支持 OpenAI 兼容(image_url)、Gemini(inline_data)、Anthropic(image source)。
     */
    fun chatWithImage(question: String, imageDataUrl: String, system: String? = null): String {
        val cfg = config()
        if (!cfg.enabled) throw IllegalStateException("AI 已在设置中停用")
        if (cfg.key.isBlank() && !keyOptional(cfg.provider)) throw IllegalStateException("未配置 API Key")
        if (cfg.endpoint.isBlank()) throw IllegalStateException("未配置 API Endpoint")
        if (cfg.model.isBlank()) throw IllegalStateException("未配置模型名称")
        // 解析 data:image/jpeg;base64,xxxx
        val mime = Regex("^data:([^;]+);base64,").find(imageDataUrl)?.groupValues?.get(1) ?: "image/jpeg"
        val b64 = imageDataUrl.substringAfter("base64,", imageDataUrl)
        if (b64.isBlank()) return "图片解析失败：data URL 为空"
        return try {
            val provider = cfg.provider.lowercase()
            val ep = cfg.endpoint.lowercase()
            when {
                provider == "gemini" -> geminiVision(cfg, question, mime, b64, system)
                provider == "anthropic" || ep.contains("anthropic") || (ep.contains("messages") && !ep.contains("chat/completions")) ->
                    anthropicVision(cfg, question, mime, b64, system)
                else -> openAiVision(cfg, question, mime, b64, system)
            }
        } catch (e: Throwable) {
            "图片分析失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
        }
    }

    /** OpenAI Chat Completions 多模态：content = [{type:text},{type:image_url}] */
    private fun openAiVision(c: AiConfig, question: String, mime: String, b64: String, system: String?): String {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", question))
        content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
        val arr = JSONArray()
        system?.let { arr.put(JSONObject().put("role", "system").put("content", it)) }
        arr.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", c.model).put("messages", arr).toString()
        val headers = mutableMapOf<String, String>()
        if (c.key.isNotBlank()) headers["Authorization"] = "Bearer ${c.key}"
        val o = JSONObject(post(chatEndpoint(c.endpoint), headers, body))
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = msg?.optString("content") ?: ""
        return text.ifBlank { o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2) }
    }

    /** Gemini 多模态：parts = [{text},{inline_data}] */
    private fun geminiVision(c: AiConfig, question: String, mime: String, b64: String, system: String?): String {
        val base = c.endpoint.replace("{model}", c.model).replace("?key=", "&key=")
        val parts = JSONArray()
        if (system?.isNotBlank() == true) parts.put(JSONObject().put("text", system))
        parts.put(JSONObject().put("text", question))
        parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject().put("contents", contents).toString()
        val o = JSONObject(post(base, mapOf("x-goog-api-key" to c.key), body.toString()))
        val cand = o.optJSONArray("candidates")?.optJSONObject(0)
        val p = cand?.optJSONObject("content")?.optJSONArray("parts")
        val text = p?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("text").takeIf { !it.isNullOrBlank() }
            }.joinToString("")
        } ?: ""
        return text.ifBlank { o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2) }
    }

    /** Anthropic 多模态：content = [{type:text},{type:image,source}] */
    private fun anthropicVision(c: AiConfig, question: String, mime: String, b64: String, system: String?): String {
        val content = JSONArray()
        if (question.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", question))
        content.put(JSONObject().put("type", "image").put("source", JSONObject()
            .put("type", "base64").put("media_type", mime).put("data", b64)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", c.model).put("max_tokens", 4096).put("messages", messages)
        system?.let { body.put("system", it) }
        val url = if (c.endpoint.contains("messages")) c.endpoint else c.endpoint.trimEnd('/') + "/v1/messages"
        val o = JSONObject(post(url, mapOf("x-api-key" to c.key, "anthropic-version" to "2023-06-01"), body.toString()))
        val contentArr = o.optJSONArray("content")
        val text = contentArr?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val part = arr.optJSONObject(i) ?: return@mapNotNull null
                if (part.optString("type") == "text") part.optString("text") else null
            }.joinToString("")
        } ?: ""
        return text.ifBlank { o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2) }
    }

    /** 多轮对话，附带思维链。 */
    fun chatDetailed(messages: List<ChatMessage>, system: String? = null): AiReply =
        chatDetailedFor(null, messages, system)

    /**
     * 指定 AI Profile 执行对话（轻/强模型分工用）。
     * profileId 为空则使用当前激活的配置；不修改全局激活状态。
     */
    fun chatDetailedFor(profileId: String?, messages: List<ChatMessage>, system: String? = null): AiReply {
        val p = profileById(profileId) ?: activeProfile()
        val cfg = configOf(p)
        ensureValid(cfg)
        val trimmed = trimHistory(messages)
        return when (cfg.provider.lowercase()) {
            "anthropic" -> anthropic(cfg, trimmed, system)
            "gemini" -> gemini(cfg, trimmed, system)
            "compatible", "local" -> compatible(cfg, trimmed, system)
            else -> openAi(cfg, trimmed, system)
        }
    }

    /** 指定模型（轻量模型）做摘要/整理；profileId 空则用当前激活模型。 */
    fun summarizeFor(profileId: String?, text: String, prompt: String = "把以下内容压缩为简洁的中文要点摘要，保留关键目标、已完成事项、待办与文件路径。"): String {
        if (text.isBlank()) return ""
        val content = if (text.length > 60000) text.take(60000) + "\n...(内容过长已截断)" else text
        return try {
            chatDetailedFor(profileId, listOf(ChatMessage("user", "$prompt\n\n$content")), "You are a helpful summarizer.").content
        } catch (e: Throwable) {
            "（摘要失败：${e.message}）"
        }
    }

    /** 连接测试：发送最小请求，返回模型/延迟/结论。 */
    fun test(): String {
        val cfg = config()
        if (cfg.key.isBlank() && !keyOptional(cfg.provider)) return "未配置 API Key"
        if (cfg.endpoint.isBlank()) return "未配置 API Endpoint"
        val t0 = System.currentTimeMillis()
        return try {
            val reply = chatDetailed(listOf(ChatMessage("user", "Reply with exactly: OK")), null)
            val ms = System.currentTimeMillis() - t0
            val head = reply.content.trim().lines().firstOrNull()?.take(60) ?: ""
            "连接成功（${ms}ms）\n模型：${cfg.model}\n响应：${if (head.isBlank()) "(空)" else head}"
        } catch (e: Throwable) {
            "连接失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
        }
    }

    /**
     * 账户余额查询。仅对公开提供余额接口的服务商可用：
     *  DeepSeek: GET {host}/user/balance
     *  OpenRouter: GET {host}/api/v1/auth/key
     */
    fun fetchBalanceText(): String {
        val cfg = config()
        if (cfg.key.isBlank()) return "未配置 API Key"
        return try {
            val host = Regex("^(https?://[^/]+)").find(cfg.endpoint)?.groupValues?.get(1) ?: return "无法识别服务地址"
            when (cfg.provider.lowercase()) {
                "deepseek", "compatible" -> {
                    val o = JSONObject(get("$host/user/balance", cfg.key))
                    val arr = o.optJSONArray("balance_infos")
                    if (arr == null || arr.length() == 0) return "未查询到余额（服务商响应：${o.toString(2).take(200)}）"
                    val sb = StringBuilder("账户余额：\n")
                    for (i in 0 until arr.length()) {
                        val b = arr.optJSONObject(i) ?: continue
                        sb.append("  · ").append(b.optString("currency")).append(" 总余额 ")
                            .append(b.optString("total_balance"))
                            .append("（赠送 ").append(b.optString("granted_balance")).append("）\n")
                    }
                    if (o.has("is_available")) sb.append("可用状态：").append(o.optString("is_available")).append("\n")
                    sb.toString().trimEnd()
                }
                "openrouter" -> {
                    val o = JSONObject(get("$host/api/v1/auth/key", cfg.key))
                    val d = o.optJSONObject("data")
                    if (d == null) return "未查询到余额（响应：${o.toString(2).take(200)}）"
                    buildString {
                        append("OpenRouter 账户：\n")
                        append("  标签：").append(d.optString("label").ifBlank { "-" }).append("\n")
                        append("  使用量：").append(d.optString("usage")).append("\n")
                        append("  限额：").append(d.optString("limit")).append("\n")
                        append("  免费档：").append(d.optBoolean("is_free_tier")).append("\n")
                    }.trimEnd()
                }
                "openai" -> "OpenAI 未提供公开余额查询 API，请在官网控制台查看用量。"
                else -> "当前服务商（${providerLabel(cfg.provider)}）没有可用的余额查询接口。"
            }
        } catch (e: Throwable) {
            "余额查询失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
        }
    }

    private fun get(url: String, key: String): String {
        val x = URL(url).openConnection() as HttpURLConnection
        trackConn(x)
        try {
            x.requestMethod = "GET"
            x.connectTimeout = 20000
            x.readTimeout = 30000
            x.setRequestProperty("Authorization", "Bearer $key")
            x.setRequestProperty("User-Agent", "GitHubK-Studio/5.0")
            val stream = if (x.responseCode in 200..299) x.inputStream else x.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ("HTTP " + x.responseCode)
            if (x.responseCode !in 200..299) throw RuntimeException("HTTP ${x.responseCode}: ${text.take(300)}")
            return text
        } finally {
            untrackConn(x)
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val x = URL(url).openConnection() as HttpURLConnection
        trackConn(x)
        try {
            x.requestMethod = "POST"
            x.doOutput = true
            x.connectTimeout = 30000
            x.readTimeout = 150000
            headers.forEach { x.setRequestProperty(it.key, it.value) }
            x.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            x.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (x.responseCode in 200..299) x.inputStream else x.errorStream
            val text = readInterruptible(stream) ?: ("HTTP " + x.responseCode)
            if (x.responseCode !in 200..299) throw RuntimeException("HTTP ${x.responseCode}: ${text.take(500)}")
            return text
        } finally {
            untrackConn(x)
        }
    }

    /** 逐块读取响应，期间若收到取消请求则立即抛出以中断等待（使「停止」即时生效）。 */
    private fun readInterruptible(stream: java.io.InputStream?): String {
        if (stream == null) return "HTTP <no response>"
        val sb = StringBuilder()
        val reader = java.io.InputStreamReader(stream, Charsets.UTF_8)
        val buf = CharArray(8192)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            if (cancelRequested) {
                stream.close()
                throw java.io.IOException("CANCELLED")
            }
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    private fun systemPrompt(ctx: String): String = """You are GitHubK Studio, a mobile coding agent that manages Android/Flutter projects.
Workspace:
$ctx

You can call tools by returning exactly:
<TOOL name="tool_name">{"path":"...","query":"..."}</TOOL>

Available tools: list_projects, list_files, file_tree, read_file, write_file, search_code, delete_file, build, git_status, git_diff, git_branch, git_commit, git_push.

For file changes, return exact full file contents using:
<FILE path="project/relative/path">content</FILE>

Rules:
- project must be an existing project name from list_projects.
- Write complete files, never fragments.
- After changes, call build to verify. Fix errors by reading and rewriting files.
- Keep changes minimal and buildable. Do not invent paths.
- When done, summarize what you changed."""

    /** 粗估长度并裁剪过长的历史，保留最近 ~48k 字符。 */
    private fun trimHistory(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.sumOf { it.content.length } <= 48000) return messages
        val out = mutableListOf<ChatMessage>()
        var total = 0
        for (m in messages.asReversed()) {
            total += m.content.length + 8
            if (total > 48000 && out.isNotEmpty()) break
            out.add(0, m)
        }
        if (out.isEmpty()) out.addAll(messages.takeLast(1))
        return out
    }

    // ---------------- OpenAI 兼容 ----------------
    /**
     * SSE 流式聊天：返回完整回复，同时通过 onDelta 实时回调增量文本。
     * 流式解析针对 OpenAI 兼容端点（覆盖 Ollama / LM Studio / llama.cpp / vLLM 等本地服务）。
     * Anthropic / Gemini 端点暂回退到同步调用。
     */
    fun streamChat(messages: List<ChatMessage>, system: String? = null, onDelta: (String) -> Unit = {}): AiReply {
        val cfg = config()
        if (!cfg.enabled) throw IllegalStateException("AI 已在设置中停用")
        if (cfg.key.isBlank() && !keyOptional(cfg.provider)) throw IllegalStateException("未配置 API Key")
        if (cfg.endpoint.isBlank()) throw IllegalStateException("未配置 API Endpoint")
        if (cfg.model.isBlank()) throw IllegalStateException("未配置模型名称")
        val trimmed = trimHistory(messages)
        val provider = cfg.provider.lowercase()
        val ep = cfg.endpoint.lowercase()
        val isAnthropic = provider == "anthropic" || (ep.contains("anthropic") || (ep.contains("messages") && !ep.contains("chat/completions")))
        return when {
            isAnthropic -> anthropic(cfg, trimmed, system)
            else -> streamOpenAi(cfg, trimmed, system, onDelta)
        }
    }

    private fun streamOpenAi(c: AiConfig, messages: List<ChatMessage>, system: String?, onDelta: (String) -> Unit): AiReply {
        val arr = JSONArray()
        system?.let { arr.put(JSONObject().put("role", "system").put("content", it)) }
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", c.model).put("messages", arr)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .toString()
        val headers = mutableMapOf<String, String>()
        if (c.key.isNotBlank()) headers["Authorization"] = "Bearer ${c.key}"
        if (c.provider.equals("openrouter", true)) {
            headers["HTTP-Referer"] = "https://github.com"
            headers["X-Title"] = "GitHubK Studio"
        }
        val x = URL(chatEndpoint(c.endpoint)).openConnection() as HttpURLConnection
        trackConn(x)
        try {
        x.requestMethod = "POST"
        x.doOutput = true
        x.connectTimeout = 30000
        x.readTimeout = 180000
        headers.forEach { x.setRequestProperty(it.key, it.value) }
        x.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        x.setRequestProperty("Accept", "text/event-stream")
        x.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (x.responseCode !in 200..299) {
            val err = x.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            throw RuntimeException("HTTP ${x.responseCode}: ${err.take(500)}")
        }
        val sb = StringBuilder()
        val reason = StringBuilder()
        x.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (ln in lines) {
                if (cancelRequested) throw java.io.IOException("CANCELLED")
                val s = ln.trim()
                if (!s.startsWith("data:")) continue
                val data = s.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val content = delta.optString("content")
                if (content.isNotBlank() && content != "null") { sb.append(content); onDelta(content) }
                val rc = delta.optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }
                    ?: delta.optString("reasoning").takeIf { it.isNotBlank() && it != "null" }
                rc?.let { reason.append(it) }
            }
        }
        val text = sb.toString()
        return AiReply(text.ifBlank {
            // 部分服务会忽略 stream 回全量 JSON，此处试推近同步解析
            runCatching { openAi(c, messages, system).content }.getOrDefault("")
        }, if (c.reasoning) reason.toString() else "")
        } finally {
            untrackConn(x)
        }
    }



    /** OpenAI 兼容端点补全：若配置的是 base（如 .../v1），自动补 /chat/completions。 */
    private fun chatEndpoint(ep: String): String {
        val e = ep.trimEnd('/')
        val low = e.lowercase()
        return when {
            low.endsWith("/chat/completions") || low.endsWith("/responses") || low.endsWith("/completions") -> e
            else -> e + "/chat/completions"
        }
    }

    /**
     * OpenAI 兼容 / 本地端点：按 endpoint 特征自动识别协议。
     *  - 含 anthropic 或 (messages 且非 chat/completions) -> Anthropic
     *  - 以 /responses 结尾且开启 Response API -> OpenAI Responses
     *  - 其余 -> OpenAI Chat Completions（覆盖 Ollama / LM Studio / llama.cpp / vLLM 等）
     */
    private fun compatible(c: AiConfig, messages: List<ChatMessage>, system: String?): AiReply {
        val ep = c.endpoint.lowercase()
        return when {
            ep.contains("anthropic") || (ep.contains("messages") && !ep.contains("chat/completions")) ->
                anthropic(c, messages, system)
            c.responseApi && ep.trimEnd('/').endsWith("responses") -> openAiResponses(c, messages, system)
            else -> openAi(c, messages, system)
        }
    }

    private fun openAi(c: AiConfig, messages: List<ChatMessage>, system: String?): AiReply {
        // Response API：仅当显式开启且 endpoint 以 /responses 结尾时启用
        if (c.responseApi && c.endpoint.trimEnd('/').endsWith("responses")) {
            return openAiResponses(c, messages, system)
        }
        val arr = JSONArray()
        system?.let { arr.put(JSONObject().put("role", "system").put("content", it)) }
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", c.model).put("messages", arr).toString()
        val headers = mutableMapOf<String, String>()
        if (c.key.isNotBlank()) headers["Authorization"] = "Bearer ${c.key}"
        if (c.provider.equals("openrouter", true)) {
            headers["HTTP-Referer"] = "https://github.com"
            headers["X-Title"] = "GitHubK Studio"
        }
        val o = JSONObject(post(chatEndpoint(c.endpoint), headers, body))
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val content = msg?.optString("content") ?: ""
        val reasoning = msg?.optString("reasoning_content")?.takeIf { it.isNotBlank() && it != "null" }
            ?: msg?.optString("reasoning")?.takeIf { it.isNotBlank() && it != "null" }
            ?: ""
        val answer = when {
            content.isNotBlank() && content != "null" -> content
            reasoning.isNotBlank() -> "（模型本次仅返回思考内容）\n$reasoning"
            else -> o.optJSONArray("choices")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: (o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2))
        }
        return AiReply(answer, if (c.reasoning) reasoning else "")
    }

    /** OpenAI Responses API：POST /v1/responses */
    private fun openAiResponses(c: AiConfig, messages: List<ChatMessage>, system: String?): AiReply {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", c.model)
            .put("input", arr)
        if (!system.isNullOrBlank()) body.put("instructions", system)
        val headers = if (c.key.isNotBlank()) mapOf("Authorization" to "Bearer ${c.key}") else emptyMap()
        val o = JSONObject(post(c.endpoint, headers, body.toString()))
        var text = o.optString("output_text").takeIf { it.isNotBlank() && it != "null" } ?: ""
        if (text.isBlank()) {
            val output = o.optJSONArray("output")
            for (i in 0 until (output?.length() ?: 0)) {
                val item = output?.optJSONObject(i) ?: continue
                if (item.optString("type") == "message") {
                    val contentArr = item.optJSONArray("content")
                    for (j in 0 until (contentArr?.length() ?: 0)) {
                        val part = contentArr?.optJSONObject(j) ?: continue
                        val t = part.optString("text")
                        if (t.isNotBlank()) { text = t; break }
                    }
                    if (text.isNotBlank()) break
                }
            }
        }
        if (text.isBlank()) {
            text = o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2)
        }
        return AiReply(text)
    }

    // ---------------- Anthropic ----------------

    private fun anthropic(c: AiConfig, messages: List<ChatMessage>, system: String?): AiReply {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", c.model)
            .put("max_tokens", 8192)
            .put("messages", arr)
        system?.let { body.put("system", it) }
        val url = if (c.endpoint.contains("messages")) c.endpoint else c.endpoint.trimEnd('/') + "/v1/messages"
        val o = JSONObject(post(url, mapOf("x-api-key" to c.key, "anthropic-version" to "2023-06-01"), body.toString()))
        val contentArr = o.optJSONArray("content")
        val text = if (contentArr != null) {
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val part = contentArr.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            sb.toString()
        } else ""
        return AiReply(text.ifBlank { o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2) })
    }

    // ---------------- Gemini (Key 放请求头) ----------------

    private fun gemini(c: AiConfig, messages: List<ChatMessage>, system: String?): AiReply {
        val base = c.endpoint.replace("{model}", c.model).replace("?key=", "&key=")
        val contents = JSONArray()
        messages.forEach { m ->
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", m.content))))
        }
        val body = JSONObject()
        system?.let { body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
        body.put("contents", contents)
        val o = JSONObject(post(base, mapOf("x-goog-api-key" to c.key), body.toString()))
        val cand = o.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        val text = if (parts != null) {
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("text")) sb.append(part.optString("text"))
            }
            sb.toString()
        } else ""
        return AiReply(text.ifBlank { o.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: o.toString(2) })
    }
}
