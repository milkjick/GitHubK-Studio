package com.example.myempty.githubk.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.myempty.githubk.io.LocalFileProvider
import com.example.myempty.githubk.core.Project
import java.io.File

data class ApkInfo(val file: File, val size: Long, val modified: Long)

/**
 * ApkManager v2.5
 * - FileProvider 正确授权
 * - 安装 / 分享 / 导出（导出到 Download）
 * - 历史扫描（仅限项目目录）
 */
class ApkManager(private val context: Context) {

    fun history(projects: List<Project>): List<ApkInfo> {
        return projects.flatMap { project ->
            project.path.walkTopDown()
                .filter { file -> file.isFile && file.extension.equals("apk", true) }
                .map { file -> ApkInfo(file, file.length(), file.lastModified()) }
                .toList()
        }.sortedByDescending { it.modified }
    }

    fun info(file: File): String = "文件：${file.name}\n大小：${file.length() / 1024} KB\n路径：${file.absolutePath}"

    fun install(file: File) {
        val uri = LocalFileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun share(file: File) {
        val uri = LocalFileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享 APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun export(file: File): File {
        val dest = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            file.name
        )
        try {
            file.copyTo(dest, overwrite = true)
        } catch (_: Throwable) {
            // fallback to files dir
        }
        return dest
    }

    /** 通用文件分享（源码 ZIP 等，非 APK）。 */
    fun shareFile(file: File, mime: String = "application/octet-stream", title: String = "分享文件") {
        val uri = LocalFileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openFile(file: File) {
        val uri: Uri = LocalFileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = when (file.extension.lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "txt", "md", "kt", "java", "dart", "xml" -> "text/plain"
            "zip" -> "application/zip"
            else -> "*/*"
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}