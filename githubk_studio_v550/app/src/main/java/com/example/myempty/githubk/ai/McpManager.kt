package com.example.myempty.githubk.ai

import com.example.myempty.githubk.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * McpManager v2.6：MCP（Model Context Protocol）能力。
 * - Client 模式：连接远程 MCP Server（HTTP JSON-RPC），获取工具列表并调用
 * - Server 模式：在本地启动 HTTP MCP Server，将本 App 的 Agent 工具暴露给外部客户端
 * - 已配置 server 列表存于 SecureStore（key: mcp_servers，JSON 数组）
 */
data class McpServerInfo(
    val name: String,
    val url: String,      // 例如 http://127.0.0.1:8788
    val enabled: Boolean = true
)

data class McpToolInfo(
    val name: String,
    val description: String,
    val schema: String = "{}"
)

class McpManager(private val secure: SecureStore) {

    // ---------- Server 管理 ----------

    fun servers(): List<McpServerInfo> {
        val raw = secure.get("mcp_servers") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                McpServerInfo(o.optString("name"), o.optString("url"), o.optBoolean("enabled", true))
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun addServer(name: String, url: String): Boolean {
        if (name.isBlank() || url.isBlank()) return false
        val list = servers().filter { it.name != name }.toMutableList()
        list.add(McpServerInfo(name, url))
        save(list)
        toolCache.clear()
        return true
    }

    fun removeServer(name: String) {
        save(servers().filter { it.name != name })
        toolCache.clear()
    }

    fun toggleServer(name: String): Boolean {
        val list = servers().map {
            if (it.name == name) it.copy(enabled = !it.enabled) else it
        }
        save(list)
        toolCache.clear()
        return list.firstOrNull { it.name == name }?.enabled ?: false
    }

    private fun save(list: List<McpServerInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled))
        }
        secure.put("mcp_servers", arr.toString())
    }

    private var toolCache: MutableMap<String, List<McpToolInfo>> = mutableMapOf()

    // ---------- MCP Client ----------

    fun listTools(server: McpServerInfo): List<McpToolInfo> {
        toolCache[server.name]?.let { return it }
        val resp = call(server, "tools/list", JSONObject())
        val r = try {
            val tools = resp.getJSONArray("tools")
            (0 until tools.length()).map { i ->
                val o = tools.getJSONObject(i)
                McpToolInfo(
                    o.optString("name"),
                    o.optString("description"),
                    o.optJSONObject("inputSchema")?.toString() ?: "{}"
                )
            }
        } catch (_: Throwable) { emptyList() }
        toolCache[server.name] = r
        return r
    }

    /** 强制刷新（绕过缓存）。 */
    fun refreshTools(server: McpServerInfo): List<McpToolInfo> {
        toolCache.remove(server.name)
        return listTools(server)
    }

    fun callTool(server: McpServerInfo, toolName: String, args: JSONObject): String {
        val resp = call(server, "tools/call", JSONObject().put("name", toolName).put("arguments", args))
        return try {
            val content = resp.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                sb.append(c.optString("text", c.toString())).append("\n")
            }
            sb.toString().ifBlank { resp.toString() }
        } catch (_: Throwable) { resp.toString() }
    }

    fun allEnabledServers(): List<McpServerInfo> = servers().filter { it.enabled }

    private fun call(server: McpServerInfo, method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", System.currentTimeMillis())
            .put("method", method)
            .put("params", params)
        val conn = URL(server.url.trimEnd('/')).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(300)}")
            val root = JSONObject(text)
            return if (root.has("result")) root.getJSONObject("result") else root
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 本地 MCP Server（简化 HTTP JSON-RPC）----------

    private var serverSocket: ServerSocket? = null
    private var running = false

    fun startLocalServer(port: Int = 8788, toolProvider: ((String, JSONObject) -> String)? = null) {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val client = serverSocket!!.accept()
                    Thread {
                        try {
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readText()
                            client.outputStream.use { os ->
                                val resp = handleLocal(request, toolProvider)
                                os.write(resp.toByteArray(Charsets.UTF_8))
                                os.flush()
                            }
                        } catch (_: Throwable) {
                        } finally {
                            client.close()
                        }
                    }.start()
                }
            } catch (_: Throwable) {
                running = false
            }
        }.start()
    }

    fun stopLocalServer() {
        running = false
        serverSocket?.close()
        serverSocket = null
    }

    val isLocalRunning: Boolean get() = running

    private fun handleLocal(request: String, toolProvider: ((String, JSONObject) -> String)?): String {
        val req = JSONObject(request)
        val id = req.optLong("id", 1)
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        return try {
            val result = when (method) {
                "tools/list" -> {
                    val tools = JSONArray()
                    tools.put(JSONObject().put("name", "ping").put("description", "ping"))
                    tools.put(JSONObject().put("name", "list_projects").put("description", "列出工作区项目"))
                    tools.put(JSONObject().put("name", "build").put("description", "构建项目"))
                    tools.put(JSONObject().put("name", "git_status").put("description", "Git 状态"))
                    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("tools", tools))
                }
                "tools/call" -> {
                    val tool = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val text = if (toolProvider != null) {
                        toolProvider(tool, args)
                    } else {
                        "OK (local stub): $tool ${args.toString()}"
                    }
                    val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("content", content))
                }
                else -> {
                    JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("error", JSONObject().put("code", -32601).put("message", "method not found"))
                }
            }
            result.toString()
        } catch (e: Throwable) {
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32700).put("message", e.message ?: "parse error"))
                .toString()
        }
    }
}