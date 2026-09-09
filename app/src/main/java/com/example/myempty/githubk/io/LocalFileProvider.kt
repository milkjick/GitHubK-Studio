package com.example.myempty.githubk.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import android.util.Base64

/**
 * Tiny platform-only replacement for androidx.core.content.FileProvider.
 * It serves files from this app's files/cache/external app directories and validates
 * canonical paths before opening them. This removes the AndroidX AAR dependency while
 * retaining APK install/share/open functionality.
 */
class LocalFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('r')) throw FileNotFoundException("read-only provider")
        val file = resolve(uri)
        if (!file.isFile) throw FileNotFoundException(file.absolutePath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val name = resolve(uri).name.lowercase()
        return when {
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".pdf") -> "application/pdf"
            name.endsWith(".zip") -> "application/zip"
            name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val cols = projection ?: arrayOf("_display_name", "_size")
        val cursor = MatrixCursor(cols, 1)
        val row = cols.map { when (it) { "_display_name" -> file.name; "_size" -> file.length(); else -> null } }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private fun resolve(uri: Uri): File {
        val encoded = uri.lastPathSegment ?: throw FileNotFoundException("missing file")
        val path = try { String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8) } catch (_: Throwable) { throw FileNotFoundException("invalid file uri") }
        val target = File(path).canonicalFile
        val roots = listOfNotNull(
            context?.filesDir,
            context?.cacheDir,
            context?.getExternalFilesDir(null),
            context?.externalCacheDir
        ).map { it.canonicalFile }
        if (roots.none { target == it || target.path.startsWith(it.path + File.separator) }) {
            throw FileNotFoundException("path outside provider roots")
        }
        return target
    }

    companion object {
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val target = file.canonicalFile
            val roots = listOfNotNull(
                context.filesDir,
                context.cacheDir,
                context.getExternalFilesDir(null),
                context.externalCacheDir
            ).map { it.canonicalFile }
            require(roots.any { target == it || target.path.startsWith(it.path + File.separator) }) {
                "File is outside LocalFileProvider roots: ${target.path}"
            }
            val encoded = Base64.encodeToString(target.path.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return Uri.parse("content://$authority/file/$encoded")
        }
    }
}
