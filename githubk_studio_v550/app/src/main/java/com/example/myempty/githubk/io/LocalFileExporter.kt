package com.example.myempty.githubk.io

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把 App 工作区 / 内部缓存里的文件“下载 / 导出”到手机本地（公共 Download 目录）。
 * - API 29+ 使用 MediaStore.Downloads（免存储权限，文件管理器可见）
 * - 低版本回退到 legacy 公共路径
 * 供「工作区项目下载到本地」「仓库源码 ZIP 保存到本地」等页面功能复用。
 */
object LocalFileExporter {

    const val SUB_DIR = "GitHubK Studio"

    /** @return (是否成功, 给用户看的保存位置说明) */
    fun exportToDownloads(context: Context, src: File, displayName: String? = null): Pair<Boolean, String> {
        if (!src.exists() || src.length() <= 0L) return false to "源文件不存在或为空"
        val name = displayName ?: src.name
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false to "无法创建下载项"
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return false to "无法写入下载目录"
                true to "Download/$SUB_DIR/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SUB_DIR
                ).apply { mkdirs() }
                val dest = File(dir, name)
                src.copyTo(dest, overwrite = true)
                true to dest.absolutePath
            }
        } catch (e: Throwable) {
            false to (e.message ?: "导出失败")
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "deb" -> "application/vnd.debian.binary-package"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt", "md", "kt", "java", "dart", "xml", "json", "yml", "yaml", "gradle", "toml" -> "text/plain"
        else -> "application/octet-stream"
    }
}
