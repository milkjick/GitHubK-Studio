package com.example.myempty.githubk.ai

import org.json.JSONObject

/**
 * AgentProtocol v2.5
 * 解析 AI 响应中的 <TOOL> 与 <FILE> 标签。
 */
data class ToolCall(val name: String, val args: JSONObject)

data class FileWrite(val path: String, val content: String)

object AgentProtocol {

    fun parseTools(text: String): List<ToolCall> {
        val re = Regex("""<TOOL\s+name="([^"]+)">(.*?)</TOOL>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(text).map { m ->
            val raw = m.groupValues[2].trim()
            val args = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            ToolCall(m.groupValues[1].trim().lowercase(), args)
        }.toList()
    }

    fun hasTools(text: String): Boolean = Regex("<TOOL\\s+name=").containsMatchIn(text)

    fun parseFiles(text: String): List<FileWrite> {
        val re = Regex("""<FILE\s+path="([^"]+)">(.*?)</FILE>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(text).map { m ->
            FileWrite(m.groupValues[1].trim(), m.groupValues[2].trimStart('\n'))
        }.toList()
    }

    /** 去除文本中的工具/文件标签，保留自然语言部分。 */
    fun stripTags(text: String): String =
        text.replace(Regex("""<TOOL\s+name="[^"]*">.*?</TOOL>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<FILE\s+path="[^"]*">.*?</FILE>""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
}