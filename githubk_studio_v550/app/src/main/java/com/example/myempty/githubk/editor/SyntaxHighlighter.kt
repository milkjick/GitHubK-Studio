package com.example.myempty.githubk.editor

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/**
 * SyntaxHighlighter：轻量语法高亮（Kotlin/Java/Dart/XML/JSON/Shell/Python/Gradle）。
 * 基于关键词表 + 注释/字符串识别，无需正则引擎，适合移动端小文件。
 */
object SyntaxHighlighter {

    data class Palette(
        val keyword: Int,
        val string: Int,
        val comment: Int,
        val number: Int,
        val annotation: Int
    )

    val darkPalette = Palette(
        keyword = 0xFFFF79C6.toInt(),
        string = 0xFFA6E22E.toInt(),
        comment = 0xFF6A737D.toInt(),
        number = 0xFFE6DB74.toInt(),
        annotation = 0xFF66D9EF.toInt()
    )

    /** Android Studio / IntelliJ Darcula 配色，供 EditorPage 使用。 */
    val studioPalette = Palette(
        keyword = 0xFFCC7832.toInt(),    // 橙色关键字（fun/val/if...）
        string = 0xFF6A8759.toInt(),     // 橄榄绿字符串
        comment = 0xFF808080.toInt(),    // 灰色注释
        number = 0xFF6897BB.toInt(),     // 蓝色数字
        annotation = 0xFFBBB529.toInt()  // 黄绿色注解 @Xxx
    )

    /** GitHub 浅色主题代码调色板。 */
    val lightPalette = Palette(
        keyword = 0xFFCF222E.toInt(),   // 红
        string = 0xFF0A3069.toInt(),    // 深蓝
        comment = 0xFF6E7781.toInt(),   // 灰
        number = 0xFF0550AE.toInt(),    // 蓝
        annotation = 0xFF953800.toInt() // 橙
    )

    fun highlight(text: String, fileName: String, pal: Palette = darkPalette): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        if (text.length > 200_000) return sb // 大文件不高亮
        val keywords = keywordsFor(fileName)
        if (keywords.isEmpty()) return sb
        val n = text.length
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var stringChar = '"'
        val wordStart = IntArray(64)
        var wordCount = 0

        fun flushWord(end: Int) {
            for (w in 0 until wordCount) {
                val start = wordStart[w]
                val word = text.substring(start, end)
                if (word in keywords) {
                    sb.setSpan(ForegroundColorSpan(pal.keyword), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (word.firstOrNull()?.isDigit() == true) {
                    sb.setSpan(ForegroundColorSpan(pal.number), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            wordCount = 0
        }

        while (i < n) {
            val ch = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            if (inLineComment) {
                sb.setSpan(ForegroundColorSpan(pal.comment), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (ch == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                sb.setSpan(ForegroundColorSpan(pal.comment), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (ch == '*' && next == '/') {
                    sb.setSpan(ForegroundColorSpan(pal.comment), i + 1, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (inString) {
                sb.setSpan(ForegroundColorSpan(pal.string), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (ch == '\\' && i + 1 < n) {
                    sb.setSpan(ForegroundColorSpan(pal.string), i + 1, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i += 2
                    continue
                }
                if (ch == stringChar) inString = false
                i++
                continue
            }
            // 注释开始
            if (ch == '/' && next == '/') {
                flushWord(i)
                inLineComment = true
                sb.setSpan(ForegroundColorSpan(pal.comment), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                continue
            }
            if (ch == '/' && next == '*') {
                flushWord(i)
                inBlockComment = true
                sb.setSpan(ForegroundColorSpan(pal.comment), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                continue
            }
            // 字符串开始
            if (ch == '"' || ch == '\'') {
                flushWord(i)
                inString = true
                stringChar = ch
                sb.setSpan(ForegroundColorSpan(pal.string), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                continue
            }
            // 注解（@Xxx）
            if (ch == '@') {
                flushWord(i)
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) {
                    sb.setSpan(ForegroundColorSpan(pal.annotation), j, j + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    j++
                }
                i = j
                continue
            }
            // 单词
            if (ch.isLetterOrDigit() || ch == '_' || ch == '$') {
                if (wordCount < wordStart.size) wordStart[wordCount++] = i
                i++
                continue
            }
            // 数字
            if (ch.isDigit()) {
                var j = i
                while (j < n && (text[j].isDigit() || text[j] == '.' || text[j] == 'x' || text[j] == 'X' || text[j] == 'L' || text[j] == 'l' || text[j] == 'f' || text[j] == 'F' || text[j].isLetter() && j == i + 1 && text[i] == '0')) j++
                if (j > i) {
                    flushWord(i)
                    sb.setSpan(ForegroundColorSpan(pal.number), i, j, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = j
                    continue
                }
            }
            flushWord(i)
            i++
        }
        flushWord(n)
        return sb
    }

    private fun keywordsFor(fileName: String): Set<String> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> KOTLIN
            "java" -> JAVA
            "dart" -> DART
            "xml", "html", "htm" -> XML
            "json" -> JSON
            "gradle", "groovy" -> GROOVY
            "sh", "bash" -> SHELL
            "py" -> PYTHON
            "md" -> MARKDOWN
            "c", "h", "cpp", "hpp", "cc" -> C_LIKE
            "go" -> GO
            "rs" -> RUST
            "swift" -> SWIFT
            "js", "ts", "vue" -> JS
            "css" -> CSS
            else -> KOTLIN
        }
    }

    private val KOTLIN = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed", "abstract",
        "open", "override", "private", "public", "protected", "internal", "if", "else", "when",
        "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw",
        "import", "package", "as", "is", "in", "not", "null", "true", "false", "this", "super",
        "companion", "init", "constructor", "by", "get", "set", "lateinit", "const", "suspend",
        "inline", "tailrec", "operator", "infix", "external", "typealias", "value", "out", "vararg"
    )

    private val JAVA = setOf(
        "public", "private", "protected", "class", "interface", "enum", "static", "final", "void",
        "int", "long", "double", "float", "boolean", "char", "byte", "short", "if", "else", "for",
        "while", "do", "return", "new", "try", "catch", "finally", "throw", "throws", "import",
        "package", "extends", "implements", "this", "super", "null", "true", "false", "switch",
        "case", "default", "break", "continue", "synchronized", "abstract", "native", "transient"
    )

    private val DART = setOf(
        "class", "void", "int", "double", "String", "bool", "List", "Map", "final", "const", "var",
        "if", "else", "for", "while", "do", "return", "new", "try", "catch", "finally", "throw",
        "import", "export", "as", "is", "in", "null", "true", "false", "this", "super", "abstract",
        "extends", "implements", "mixin", "enum", "typedef", "required", "factory", "static", "get", "set"
    )

    private val XML = setOf(
        "xmlns", "android", "app", "tools", "http", "schemas", "item", "resources", "string",
        "color", "dimen", "style", "drawable", "layout", "match_parent", "wrap_content", "true", "false"
    )

    private val JSON = setOf("true", "false", "null")

    private val GROOVY = setOf(
        "apply", "dependencies", "repositories", "android", "buildscript", "plugins", "allprojects",
        "subprojects", "task", "class", "def", "if", "else", "for", "while", "return", "import",
        "true", "false", "null", "implementation", "api", "testImplementation", "release", "debug"
    )

    private val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "echo", "cd", "export", "source", "local", "true", "false"
    )

    private val PYTHON = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as",
        "try", "except", "finally", "with", "lambda", "yield", "global", "nonlocal", "pass",
        "break", "continue", "raise", "assert", "del", "in", "is", "not", "and", "or", "None",
        "True", "False"
    )

    private val MARKDOWN = setOf("#", "##", "###", "```", "-", "*")

    private val C_LIKE = setOf(
        "if", "else", "for", "while", "do", "return", "int", "void", "char", "float", "double",
        "long", "short", "unsigned", "struct", "union", "enum", "typedef", "static", "const",
        "extern", "register", "volatile", "sizeof", "switch", "case", "default", "break",
        "continue", "goto", "true", "false", "NULL"
    )

    private val GO = setOf(
        "package", "import", "func", "var", "const", "type", "struct", "interface", "map",
        "chan", "go", "defer", "if", "else", "for", "range", "return", "break", "continue",
        "switch", "case", "default", "fallthrough", "select", "nil", "true", "false"
    )

    private val RUST = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "mod", "use",
        "pub", "crate", "self", "Self", "if", "else", "match", "for", "while", "loop", "return",
        "break", "continue", "unsafe", "async", "await", "true", "false", "Option", "Result"
    )

    private val SWIFT = setOf(
        "func", "var", "let", "class", "struct", "enum", "protocol", "extension", "import",
        "if", "else", "guard", "for", "while", "repeat", "return", "switch", "case", "default",
        "break", "continue", "try", "catch", "throw", "throws", "nil", "true", "false", "self", "super"
    )

    private val JS = setOf(
        "function", "var", "let", "const", "class", "import", "export", "default", "if", "else",
        "for", "while", "do", "return", "try", "catch", "finally", "throw", "new", "this", "super",
        "null", "undefined", "true", "false", "typeof", "instanceof", "in", "of", "async", "await",
        "switch", "case", "break", "continue", "yield"
    )

    private val CSS = setOf(
        "body", "html", "div", "span", "class", "id", "color", "background", "margin", "padding",
        "display", "position", "font", "border", "width", "height", "flex", "grid", "important"
    )
}
