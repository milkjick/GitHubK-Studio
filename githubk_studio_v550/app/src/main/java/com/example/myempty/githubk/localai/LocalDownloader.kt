package com.example.myempty.githubk.localai

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 下载进度快照（供 UI 进度弹窗使用）。 */
data class DownloadProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val speedBytesPerSec: Double,
    val urlIndex: Int,
    val urlCount: Int,
    val stage: String = "下载中"
) {
    /** -1 表示服务器未给 Content-Length。 */
    val percent: Int
        get() = if (bytesTotal > 0L) ((bytesDone * 100L / bytesTotal).toInt()).coerceIn(0, 100) else -1

    val doneText: String get() = InferenceComponent.formatSize(bytesDone)
    val totalText: String get() = if (bytesTotal > 0L) InferenceComponent.formatSize(bytesTotal) else "未知"
    val speedText: String get() = "%.2f MB/s".format(speedBytesPerSec / 1024.0 / 1024.0)
}

data class DownloadResult(
    val ok: Boolean,
    val file: File?,
    val sha256: String?,
    val usedUrl: String,
    val error: String?
)

/**
 * LocalDownloader v6.4 —— 面向离线推理依赖的下载器。
 *
 * 与工具链下载共用同一套经验教训（见 ToolchainDownloadManager.downloadResumable）：
 *  - 先落 .part 临时文件，成功后再原子改名为正式文件，避免半包被当成有效依赖；
 *  - 强制 Accept-Encoding: identity，规避运营商代理把 ZIP/二进制当 gzip 传输；
 *  - 不使用 Range 续传（代理忽略 Range 会拼出损坏文件），失败即清缓存整段重下；
 *  - 多镜像按顺序回退，逐个记录失败原因，便于「依赖告知」失败时给出替代方案；
 *  - 仅当组件声明了 sha256 时才校验（绝不伪造校验和）。
 */
object LocalDownloader {

    private const val TAG = "LocalDownloader"
    private const val MIN_VALID_BYTES = 64L

    fun download(
        urls: List<String>,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadResult {
        if (urls.isEmpty()) return DownloadResult(false, null, null, "", "没有可用的下载地址")
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val failures = StringBuilder()

        urls.forEachIndexed { index, url ->
            runCatching { part.delete() }
            onProgress(DownloadProgress(0, 0, 0.0, index, urls.size, "连接第 ${index + 1}/${urls.size} 个地址"))
            val r = fetchOne(url, part, index, urls.size, expectedSha256, onProgress)
            if (r == null) {
                // 成功：重命名 .part -> dest
                runCatching { if (dest.exists()) dest.delete() }
                val renamed = runCatching { part.renameTo(dest) }.getOrDefault(false)
                if (!renamed) {
                    failures.append("• 落盘失败（无法重命名临时文件）\n")
                    runCatching { part.delete() }
                    return@forEachIndexed
                }
                return DownloadResult(true, dest, expectedSha256?.let { sha256(dest) }, url, null)
            }
            failures.append("• ").append(shortHost(url)).append("：").append(r).append('\n')
        }

        runCatching { part.delete() }
        return DownloadResult(
            ok = false,
            file = null,
            sha256 = null,
            usedUrl = "",
            error = "全部下载地址均失败：\n$failures"
        )
    }

    /** 成功返回 null，失败返回错误描述。 */
    private fun fetchOne(
        url: String,
        part: File,
        index: Int,
        total: Int,
        expectedSha256: String?,
        onProgress: (DownloadProgress) -> Unit
    ): String? {
        val started = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000
                readTimeout = 180000
                useCaches = false
                doInput = true
                setRequestProperty("User-Agent", "GitHubK-Studio/5.5.0")
                setRequestProperty("Accept", "application/octet-stream,application/zip,*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return "HTTP $code"
            }
            val declared = conn.contentLengthLong
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastPct = -1
            var lastBytes = 0L
            BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
                FileOutputStream(part, false).use { out ->
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buf, 0, read)
                        done += read
                        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) / 1000.0
                        val speed = done / elapsed
                        val pct = if (declared > 0L) (done * 100L / declared).toInt().coerceAtMost(100) else -1
                        if (pct != lastPct || done - lastBytes >= 1024L * 512L) {
                            lastPct = pct
                            lastBytes = done
                            onProgress(DownloadProgress(done, declared, speed, index, total))
                        }
                    }
                    out.flush()
                }
            }
            onProgress(
                DownloadProgress(
                    done, if (declared > 0L) declared else done,
                    done / ((SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) / 1000.0),
                    index, total, "校验中"
                )
            )
            if (done < MIN_VALID_BYTES) return "响应过小（${done}B），疑似错误页"
            if (expectedSha256 != null && expectedSha256.isNotBlank()) {
                val actual = sha256(part)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    return "SHA-256 校验失败（实际 ${actual.take(12)}…）"
                }
            }
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun shortHost(url: String): String = runCatching {
        val u = URL(url)
        u.host + u.path.takeLast(28)
    }.getOrDefault(url.takeLast(32))

    fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { "" }
}
