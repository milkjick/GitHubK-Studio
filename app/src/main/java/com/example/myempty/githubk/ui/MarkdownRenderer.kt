package com.example.myempty.githubk.ui

/**
 * MarkdownRenderer：轻量 Markdown → GitHub 风格 HTML。
 * 支持：标题 / 粗体 / 斜体 / 行内代码 / 代码块(带语言) / 无序/有序列表 / 链接 / 图片 /
 *       引用 / 表格 / 分隔线 / 自动链接。
 * 输出 HTML 片段（不含 <html> 包裹），由 [wrapHtml] 生成完整文档。
 */
object MarkdownRenderer {

    fun toHtml(md: String, baseUrl: String = ""): String {
        val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val sb = StringBuilder()
        var i = 0
        var inCode = false
        var codeLang = ""
        var codeBuf = StringBuilder()
        var listType = "" // "", "ul", "ol"
        var inTable = false
        var tableBuf = StringBuilder()

        fun closeList() {
            if (listType.isNotEmpty()) {
                sb.append("</$listType>\n")
                listType = ""
            }
        }

        fun closeTable() {
            if (inTable) {
                sb.append("</table>\n")
                inTable = false
                tableBuf = StringBuilder()
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // 代码块
            if (line.trimStart().startsWith("```")) {
                if (!inCode) {
                    closeList(); closeTable()
                    inCode = true
                    codeLang = line.trimStart().removePrefix("```").trim()
                    codeBuf = StringBuilder()
                } else {
                    sb.append("<pre><code")
                    if (codeLang.isNotEmpty()) sb.append(" class=\"lang-$codeLang\"")
                    sb.append(">").append(esc(codeBuf.toString().trimEnd('\n'))).append("</code></pre>\n")
                    inCode = false
                    codeLang = ""
                }
                i++
                continue
            }
            if (inCode) { codeBuf.append(line).append('\n'); i++; continue }

            // 空行
            if (line.isBlank()) { closeList(); closeTable(); sb.append("\n"); i++; continue }

            // 表格
            if (line.trimStart().startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?[\\s:\\-|]+\\|?$"))) {
                closeList(); closeTable()
                inTable = true
                tableBuf.append("<table>\n<thead><tr>")
                line.trim().trim('|').split("|").forEach { tableBuf.append("<th>").append(inline(it.trim(), baseUrl)).append("</th>") }
                tableBuf.append("</tr></thead>\n<tbody>\n")
                i++ // skip separator
                i++
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    tableBuf.append("<tr>")
                    lines[i].trim().trim('|').split("|").forEach { tableBuf.append("<td>").append(inline(it.trim(), baseUrl)).append("</td>") }
                    tableBuf.append("</tr>\n")
                    i++
                }
                tableBuf.append("</tbody>\n</table>\n")
                sb.append(tableBuf)
                inTable = false
                tableBuf = StringBuilder()
                continue
            }
            if (inTable) { closeTable() }

            // 标题
            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (h != null) {
                closeList(); closeTable()
                val level = h.groupValues[1].length
                sb.append("<h$level>").append(inline(h.groupValues[2], baseUrl)).append("</h$level>\n")
                i++; continue
            }

            // 分隔线
            if (line.matches(Regex("^([-*_])\\s*\\1\\s*\\1[\\s\\1]*$"))) {
                closeList(); closeTable()
                sb.append("<hr/>\n"); i++; continue
            }

            // 引用
            if (line.trimStart().startsWith(">")) {
                closeList(); closeTable()
                val quote = line.trimStart().removePrefix(">").trim()
                sb.append("<blockquote>").append(inline(quote, baseUrl)).append("</blockquote>\n")
                i++; continue
            }

            // 无序列表
            val ul = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            if (ul != null) {
                closeTable()
                if (listType != "ul") { closeList(); sb.append("<ul>\n"); listType = "ul" }
                sb.append("<li>").append(inline(ul.groupValues[1], baseUrl)).append("</li>\n")
                i++; continue
            }

            // 有序列表
            val ol = Regex("^\\s*(\\d+)[.、]\\s+(.*)$").find(line)
            if (ol != null) {
                closeTable()
                if (listType != "ol") { closeList(); sb.append("<ol>\n"); listType = "ol" }
                sb.append("<li>").append(inline(ol.groupValues[2], baseUrl)).append("</li>\n")
                i++; continue
            }

            // 普通段落
            closeList(); closeTable()
            sb.append("<p>").append(inline(line, baseUrl)).append("</p>\n")
            i++
        }

        if (inCode) sb.append("<pre><code>").append(esc(codeBuf.toString().trimEnd('\n'))).append("</code></pre>\n")
        closeList(); closeTable()

        return sb.toString()
    }

    /** 行内格式化：图片 / 链接 / 粗体 / 斜体 / 行内代码 / 自动链接。 */
    private fun inline(s0: String, baseUrl: String): String {
        var s = esc(s0)
        // 图片 ![alt](url)
        s = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)").replace(s) { m ->
            "<img src=\"${resolve(m.groupValues[2], baseUrl)}\" alt=\"${m.groupValues[1]}\" loading=\"lazy\"/>"
        }
        // 链接 [text](url)
        s = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)").replace(s) { m ->
            "<a href=\"${resolve(m.groupValues[2], baseUrl)}\">${m.groupValues[1]}</a>"
        }
        // 行内代码
        s = Regex("`([^`]+)`").replace(s) { "<code>${it.groupValues[1]}</code>" }
        // 粗体
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("__([^_]+)__").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        // 斜体
        s = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = Regex("(?<!_)_([^_\\n]+)_(?!_)").replace(s) { "<em>${it.groupValues[1]}</em>" }
        // 删除线
        s = Regex("~~([^~]+)~~").replace(s) { "<del>${it.groupValues[1]}</del>" }
        // 自动链接 https?://
        s = Regex("(?<![\"'>])(https?://[^\\s<)】]+)").replace(s) { "<a href=\"${it.groupValues[1]}\">${it.groupValues[1]}</a>" }
        return s
    }

    private fun resolve(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) return url
        if (url.startsWith("#")) return url
        return baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 生成完整 HTML 文档（GitHub 风格 CSS，随主题深/浅自适应）。 */
    fun wrapHtml(md: String, baseUrl: String, isLight: Boolean = false): String {
        val body = toHtml(md, baseUrl)
        val css = if (isLight) LIGHT_CSS else DARK_CSS
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>" +
            "<style>$css</style></head><body><div class=\"md\">$body</div></body></html>"
    }

    private val BASE_CSS = """
        body { margin: 0; padding: 12px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; font-size: 14px; line-height: 1.65; -webkit-text-size-adjust: 100%; }
        .md { max-width: 100%; word-wrap: break-word; }
        .md h1, .md h2, .md h3, .md h4 { margin: 18px 0 10px; font-weight: 600; line-height: 1.3; }
        .md h1 { font-size: 1.7em; padding-bottom: 6px; border-bottom: 1px solid {border}; }
        .md h2 { font-size: 1.4em; padding-bottom: 5px; border-bottom: 1px solid {border}; }
        .md h3 { font-size: 1.2em; } .md h4 { font-size: 1.05em; }
        .md p { margin: 8px 0; }
        .md a { color: #58a6ff; text-decoration: none; } .md a:hover { text-decoration: underline; }
        .md code { font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace; font-size: 0.9em; padding: 2px 5px; border-radius: 5px; background: {codeBg}; }
        .md pre { background: {codeBg}; border: 1px solid {border}; border-radius: 8px; padding: 12px; overflow-x: auto; margin: 10px 0; }
        .md pre code { background: transparent; padding: 0; font-size: 12.5px; line-height: 1.5; white-space: pre; }
        .md ul, .md ol { padding-left: 22px; margin: 8px 0; }
        .md li { margin: 3px 0; }
        .md blockquote { margin: 10px 0; padding: 4px 14px; border-left: 4px solid {border}; color: {quote}; background: {quoteBg}; border-radius: 0 6px 6px 0; }
        .md img { max-width: 100%; border-radius: 6px; margin: 8px 0; }
        .md table { border-collapse: collapse; margin: 12px 0; width: 100%; display: block; overflow-x: auto; }
        .md th, .md td { border: 1px solid {border}; padding: 7px 12px; text-align: left; }
        .md th { background: {quoteBg}; font-weight: 600; }
        .md hr { border: none; border-top: 1px solid {border}; margin: 18px 0; }
        .md del { opacity: 0.7; }
    """.trimIndent()

    private val DARK_CSS = BASE_CSS
        .replace("{border}", "#30363d")
        .replace("{codeBg}", "#161b22")
        .replace("{quote}", "#8b949e")
        .replace("{quoteBg}", "#0d1117")
        .plus(" body { background: #0d1117; color: #c9d1d9; }")

    private val LIGHT_CSS = BASE_CSS
        .replace("{border}", "#d0d7de")
        .replace("{codeBg}", "#f6f8fa")
        .replace("{quote}", "#57606a")
        .replace("{quoteBg}", "#f6f8fa")
        .plus(" body { background: #ffffff; color: #1f2328; }")
}
