package com.example.myempty.githubk.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AgentChatStore v1.0
 *
 * 对话式 AI 工作台的会话模型与持久化层：
 * - 消息分为四类：USER 用户提问 / AI AI 文本回复 / TOOL 工具执行日志卡 / SYSTEM 系统通知
 * - 会话按项目隔离（projectKey = 项目 name，空项目用 __global__），互不干扰
 * - 每条消息带 ts/id；工具消息记录工具名、参数、返回结果、成功与否
 * - 支持：新建/切换/清空/删除会话；导出 markdown；token 估算；构建模型历史
 *
 * 文件布局：filesDir/agent_chats/<projectKey>/sessions.json
 *                       /<projectKey>/<sessionId>.json
 */
data class ChatMsg(
    val id: Long,
    val kind: String,          // USER | AI | TOOL | SYSTEM
    val text: String = "",     // 展示文本（工具卡为结果摘要）
    val toolName: String = "", // kind=TOOL 时：工具名
    val toolArgs: String = "", // kind=TOOL 时：参数 JSON
    val toolOk: Boolean = true,
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("text", text)
        .put("toolName", toolName)
        .put("toolArgs", toolArgs)
        .put("toolOk", toolOk)
        .put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject): ChatMsg = ChatMsg(
            id = o.optLong("id", System.currentTimeMillis()),
            kind = o.optString("kind", "AI"),
            text = o.optString("text", ""),
            toolName = o.optString("toolName", ""),
            toolArgs = o.optString("toolArgs", ""),
            toolOk = o.optBoolean("toolOk", true),
            ts = o.optLong("ts", System.currentTimeMillis())
        )
    }
}

/** 会话（对话上下文按项目隔离）。 */
class ChatSession(
    val id: String,
    val projectKey: String,
    var title: String,
    var createdAt: Long,
    var updatedAt: Long,
    val msgs: MutableList<ChatMsg> = mutableListOf(),
    var summary: String = "",          // 自动压缩生成的【历史摘要】文本
    var summarizedCount: Int = 0,      // 已总结消息数（被压缩进摘要的消息条数）
    var tokenEstimate: Int = 0,        // 最近一次估算 token
    var tag: String = ""               // v6.0 对话标签：开发/排错/文档/迭代/其它
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        msgs.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("projectKey", projectKey)
            .put("title", title)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("summary", summary)
            .put("summarizedCount", summarizedCount)
            .put("tokenEstimate", tokenEstimate)
            .put("tag", tag)
            .put("msgs", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): ChatSession {
            val arr = o.optJSONArray("msgs") ?: JSONArray()
            val list = mutableListOf<ChatMsg>()
            for (i in 0 until arr.length()) list.add(ChatMsg.fromJson(arr.optJSONObject(i) ?: continue))
            return ChatSession(
                id = o.optString("id", ""),
                projectKey = o.optString("projectKey", "__global__"),
                title = o.optString("title", "新对话"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                msgs = list,
                summary = o.optString("summary", ""),
                summarizedCount = o.optInt("summarizedCount", 0),
                tokenEstimate = o.optInt("tokenEstimate", 0),
                tag = o.optString("tag", "")
            )
        }
    }
}

data class SessionMeta(val id: String, val title: String, val updatedAt: Long, val msgCount: Int, val summarized: Int, val tag: String = "")

/** 全局单例会话存储。 */
object AgentChatStore {
    private const val MAX_SAVE_SESSIONS = 50

    fun rootDir(c: Context): File = File(File(c.filesDir, "agent_chats"), "")

    private fun projectDir(c: Context, projectKey: String): File =
        File(rootDir(c), sanitize(projectKey.ifBlank { "__global__" }))

    private fun sessionsFile(c: Context, projectKey: String): File = File(projectDir(c, projectKey), "sessions.json")

    private fun sanitize(s: String): String =
        s.replace(Regex("[^\\w\\-.]"), "_").take(80).ifBlank { "__global__" }

    fun safeProjectKey(c: Context, name: String?): String = sanitize(name ?: "").let { if (it == "__global__" || it.isBlank()) "__global__" else it }

    // ---------------- 会话列表 ----------------

    fun listSessions(c: Context, projectKey: String): List<SessionMeta> = runCatching {
        val f = sessionsFile(c, projectKey)
        if (!f.isFile) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return@map null
            SessionMeta(
                o.optString("id"),
                o.optString("title", "对话"),
                o.optLong("updatedAt", 0),
                o.optInt("msgCount", 0),
                o.optInt("summarized", 0),
                o.optString("tag", "")
            )
        }.filterNotNull().sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    private fun writeSessions(c: Context, projectKey: String, metas: List<SessionMeta>) {
        val dir = projectDir(c, projectKey)
        dir.mkdirs()
        val arr = JSONArray()
        metas.sortedByDescending { it.updatedAt }.take(MAX_SAVE_SESSIONS).forEach { m ->
            arr.put(JSONObject()
                .put("id", m.id).put("title", m.title).put("updatedAt", m.updatedAt)
                .put("msgCount", m.msgCount).put("summarized", m.summarized)
                .put("tag", m.tag))
        }
        sessionsFile(c, projectKey).writeText(arr.toString())
    }

    fun ensureSession(c: Context, projectKey: String, id: String): ChatSession {
        val loaded = loadSession(c, projectKey, id)
        if (loaded != null) return loaded
        return ChatSession(id, projectKey, "新对话", System.currentTimeMillis(), System.currentTimeMillis())
    }

    fun loadSession(c: Context, projectKey: String, id: String): ChatSession? = runCatching {
        val f = File(projectDir(c, projectKey), "$id.json")
        if (!f.isFile) return null
        ChatSession.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    fun saveSession(c: Context, s: ChatSession) {
        runCatching {
            val dir = projectDir(c, s.projectKey)
            dir.mkdirs()
            s.updatedAt = System.currentTimeMillis()
            // 消息文件
            File(dir, "${s.id}.json").writeText(s.toJson().toString())
            // 更新会话元数据
            val metas = listSessions(c, s.projectKey).toMutableList()
            metas.removeAll { it.id == s.id }
            metas.add(SessionMeta(s.id, s.title, s.updatedAt, s.msgs.size, s.summarizedCount, s.tag))
            writeSessions(c, s.projectKey, metas)
        }
    }

    fun deleteSession(c: Context, projectKey: String, id: String) {
        runCatching {
            File(projectDir(c, projectKey), "$id.json").delete()
            val metas = listSessions(c, projectKey).filterNot { it.id == id }
            writeSessions(c, projectKey, metas)
        }
    }

    fun activeSessionId(c: Context, projectKey: String): String {
        val f = File(projectDir(c, projectKey), ".active")
        return runCatching { if (f.isFile) f.readText().trim() else "" }.getOrDefault("")
    }

    fun setActiveSession(c: Context, projectKey: String, id: String) {
        runCatching { File(projectDir(c, projectKey), ".active").writeText(id) }
    }

    // ---------------- 工具 ----------------

    fun addMsg(s: ChatSession, kind: String, text: String = "", toolName: String = "", toolArgs: String = "", toolOk: Boolean = true): ChatMsg {
        val m = ChatMsg(System.currentTimeMillis(), kind, text, toolName, toolArgs, toolOk, System.currentTimeMillis())
        s.msgs.add(m)
        if (s.msgs.size > 400) s.msgs.removeAt(0)
        return m
    }

    fun removeMsg(s: ChatSession, id: Long) {
        s.msgs.removeAll { it.id == id }
    }

    fun clearMsgs(s: ChatSession) {
        s.msgs.clear()
        s.summary = ""
        s.summarizedCount = 0
        s.tokenEstimate = 0
    }

    /** 导出全部对话为 markdown 文本。 */
    fun exportText(s: ChatSession): String {
        val sb = StringBuilder()
        sb.appendLine("# AI 工作台对话导出 · ${s.title}")
        sb.appendLine("项目：${s.projectKey}  会话：${s.id}")
        sb.appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("消息总数：${s.msgs.size}  已总结消息数：${s.summarizedCount}")
        if (s.summary.isNotBlank()) sb.appendLine("\n## 历史摘要\n${s.summary}")
        sb.appendLine("\n---")
        s.msgs.forEach { m ->
            when (m.kind) {
                "USER" -> sb.appendLine("\n🧑 **用户**（${time(m.ts)}）\n${m.text}")
                "AI" -> sb.appendLine("\n🤖 **AI**（${time(m.ts)}）\n${m.text}")
                "TOOL" -> {
                    val icon = if (m.toolOk) "✅" else "❌"
                    sb.appendLine("\n$icon **工具 ${m.toolName}**（${time(m.ts)}）")
                    if (m.toolArgs.isNotBlank()) sb.appendLine("参数：`${m.toolArgs}`")
                    sb.appendLine("结果：\n```\n${m.text.take(4000)}\n```")
                }
                "SYSTEM" -> sb.appendLine("\n🔔 **系统**：${m.text}")
            }
        }
        return sb.toString()
    }

    private fun time(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))

    // ---------------- token 估算 / 模型历史 ----------------

    /** 近似 token 估算：中文≈1 token/字，其余≈1 token/3.5 字符。 */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch in '\u4e00'..'\u9fff' || ch in '\u3000'..'\u303f') cjk++ else other++
        }
        return (cjk + other / 3.5f).toInt() + 1
    }

    /** 估算整段会话上下文占用 token（含摘要）。 */
    fun estimateSessionTokens(s: ChatSession): Int {
        var total = 0
        for (m in s.msgs) {
            when (m.kind) {
                "USER" -> total += estimateTokens(m.text)
                "AI" -> total += estimateTokens(m.text)
                "SYSTEM" -> total += estimateTokens(m.text)
                "TOOL" -> total += estimateTokens(m.toolName + m.toolArgs + m.text).let { it / 3 }
            }
        }
        if (s.summary.isNotBlank()) total += estimateTokens("【历史摘要】" + s.summary)
        return total
    }

    /** 转 AiManager 可用的多轮对话历史（user/assistant 交替，不含工具卡与纯系统通知）。 */
    fun chatHistory(s: ChatSession): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (m in s.msgs) {
            when (m.kind) {
                "USER" -> out.add(ChatMessage("user", m.text))
                "AI" -> out.add(ChatMessage("assistant", m.text))
                else -> Unit
            }
        }
        // 防止首条不是 user（模型要求以 user 开头更稳妥）
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }

    /** 消息里记录被压缩掉的条数并写入摘要。 */
    fun applySummary(s: ChatSession, summaryText: String, consumed: Int) {
        if (summaryText.isNotBlank()) {
            s.summary = if (s.summary.isBlank()) summaryText else s.summary + "\n" + summaryText
        }
        s.summarizedCount += consumed
        s.tokenEstimate = estimateSessionTokens(s)
    }
}
