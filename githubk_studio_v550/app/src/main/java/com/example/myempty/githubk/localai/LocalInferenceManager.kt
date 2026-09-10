package com.example.myempty.githubk.localai

import android.content.Context
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.ProjectKnowledge
import com.example.myempty.githubk.terminal.BuiltinRuntime
import com.example.myempty.githubk.terminal.TermuxSandbox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
//  数据模型
// ============================================================

/** 已安装的模型权重。 */
data class InstalledModel(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val backend: String,
    val tasks: List<String>,
    val source: String,
    val installedAt: String
) {
    val sizeText: String get() = InferenceComponent.formatSize(sizeBytes)
}

/** 本地推理能力探测结果（全部通过 proot 内真实命令探测，不做假设）。 */
data class BackendStatus(
    val runtimeInstalled: Boolean,
    val sandboxReady: Boolean,
    val pythonVersion: String? = null,
    val numpyVersion: String? = null,
    val pillowVersion: String? = null,
    val onnxVersion: String? = null,
    val tfliteVersion: String? = null,
    val tesseractVersion: String? = null,
    val tesseractLangs: List<String> = emptyList(),
    val probeLog: String = ""
) {
    val hasPython: Boolean get() = !pythonVersion.isNullOrBlank()
    val hasNumpy: Boolean get() = !numpyVersion.isNullOrBlank()
    val hasPillow: Boolean get() = !pillowVersion.isNullOrBlank()
    val hasOnnx: Boolean get() = !onnxVersion.isNullOrBlank()
    val hasTflite: Boolean get() = !tfliteVersion.isNullOrBlank()
    val hasTesseract: Boolean get() = !tesseractVersion.isNullOrBlank()

    /** 是否存在任一可用的神经网络推理后端。 */
    val hasAnyNeural: Boolean get() = hasOnnx || hasTflite

    fun summary(): String = buildString {
        append("内置运行时：").append(if (runtimeInstalled) "已安装" else "未安装")
        append("；proot 沙盒：").append(if (sandboxReady) "可用" else "不可用")
        append("\npython3：").append(pythonVersion ?: "缺失")
        append("\nNumPy：").append(numpyVersion ?: "缺失")
        append("\nPillow：").append(pillowVersion ?: "缺失")
        append("\nONNX Runtime：").append(onnxVersion ?: "缺失")
        append("\nTensorFlow Lite：").append(tfliteVersion ?: "缺失")
        append("\nTesseract OCR：").append(tesseractVersion ?: "缺失")
        if (tesseractLangs.isNotEmpty()) {
            append("（语言包：").append(tesseractLangs.joinToString(",")).append("）")
        }
    }
}

/** 依赖方案：驱动「检测 → 告知 → 等待确认 → 安装」四步流程。 */
data class DependencyPlan(
    val task: String,
    val missing: List<InferenceComponent>,
    val installed: List<InferenceComponent>,
    val blockedReason: String? = null
) {
    val ready: Boolean get() = missing.isEmpty()
    val needsInstall: Boolean get() = !ready && blockedReason == null

    /** 告知文案：清单 + 用途 + 体积，供 UI 与 Agent 共用。 */
    fun describe(): String = buildString {
        append("任务：").append(InferTask.label(task)).append('\n')
        if (blockedReason != null) {
            append("无法继续：").append(blockedReason).append('\n')
        }
        if (ready) {
            append("本地推理环境已就绪，可直接离线推理（图片不离开设备）。")
        } else {
            append("检测到缺少 ").append(missing.size).append(" 项本地推理依赖，需要你确认后才能安装：\n")
            missing.forEachIndexed { i, c ->
                append(i + 1).append(". ").append(c.displayName)
                append("（").append(c.sizeText).append("，")
                append(InferBackend.label(c.backend)).append("）")
                append("\n   用途：").append(c.purpose)
                if (c.note.isNotBlank()) append("\n   说明：").append(c.note)
                append('\n')
            }
            append("以上组件全部在本机运行，安装后图片与推理结果都不会上传。是否安装？")
        }
    }

    /** 单行摘要（工具返回值首行）。 */
    fun brief(): String = if (ready) "READY task=$task"
    else "NEED_INSTALL task=$task missing=" + missing.joinToString(",") { it.id }
}

data class InstallOutcome(
    val ok: Boolean,
    val componentId: String,
    val displayName: String,
    val message: String,
    val log: String = "",
    val rolledBack: Boolean = false,
    val usedUrl: String = ""
)

// ============================================================
//  管理器
// ============================================================

/**
 * LocalInferenceManager v6.4 —— 本地推理依赖/模型的检测、安装、验证、回滚与记账。
 *
 * 目录约定（与运行时同根，保证 LD_LIBRARY_PATH 与 proot bind 天然可用）：
 *   files/runtime/lib                      ← 推理动态库（已在 LD_LIBRARY_PATH 中）
 *   files/runtime/models                   ← 模型权重与标签数据
 *   files/runtime/opt/githubk/localvision  ← 推理脚本
 *   files/localai/registry.json            ← 本机安装记账（避免重复询问）
 */
class LocalInferenceManager(private val ctx: Context) {

    private val runtime = BuiltinRuntime(ctx)

    val runtimeRoot: File get() = runtime.root
    val libDir: File get() = File(runtimeRoot, InferenceCatalog.LIB_DIR_REL)
    val modelDir: File get() = File(runtimeRoot, InferenceCatalog.MODEL_DIR_REL)
    val harnessDir: File get() = File(runtimeRoot, "opt/githubk/localvision")
    private val registryDir: File get() = File(ctx.filesDir, "localai")
    private val registryFile: File get() = File(registryDir, "registry.json")
    val installLogFile: File get() = File(registryDir, "install.log")

    private var cachedProbe: BackendStatus? = null

    // ---------------- 环境 ----------------

    fun isRuntimeInstalled(): Boolean = runtime.isInstalled()

    fun isSandboxReady(): Boolean = runCatching { TermuxSandbox.available(ctx) }.getOrDefault(false)

    /** 在 proot 运行时内执行命令并捕获输出（真实执行，不模拟）。 */
    fun exec(command: String, timeoutSec: Long = 60): String {
        if (!isRuntimeInstalled()) return ""
        return try {
            if (isSandboxReady()) {
                val out = TermuxSandbox.runCapture(ctx, runtimeRoot, command, runtime.toolchainEnv(), timeoutSec)
                if (out.isNotBlank()) return out
            }
            runtime.run(command)
        } catch (t: Throwable) {
            "[exec error] ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 流式执行（pkg/pip 这类长任务，输出实时回调）。 */
    fun execStreaming(command: String, timeoutMin: Int = 25, onLine: (String) -> Unit): Int {
        if (!isRuntimeInstalled()) return -1
        return try {
            if (isSandboxReady()) {
                TermuxSandbox.runStreaming(
                    ctx, runtimeRoot, command, runtime.toolchainEnv(), onLine, timeoutMin, false
                )
            } else -1
        } catch (t: Throwable) {
            onLine("[exec error] ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    // ---------------- 探测 ----------------

    /**
     * 真实探测各后端可用性：用带标记的 shell 命令逐项输出。
     * 解析失败一律视为「缺失」，绝不假设可用。
     */
    fun probe(force: Boolean = false): BackendStatus {
        if (!force) cachedProbe?.let { return it }
        val installed = isRuntimeInstalled()
        val sandbox = isSandboxReady()
        if (!installed) {
            return BackendStatus(false, sandbox, probeLog = "内置运行时未安装").also { cachedProbe = it }
        }
        val cmd = listOf(
            "echo \"__PY__=$(python3 -V 2>&1 | head -1)\"",
            "echo \"__NUMPY__=$(python3 -c 'import numpy;print(numpy.__version__)' 2>&1 | head -1)\"",
            "echo \"__PIL__=$(python3 -c 'import PIL;print(PIL.__version__)' 2>&1 | head -1)\"",
            "echo \"__ONNX__=$(python3 -c 'import onnxruntime;print(onnxruntime.__version__)' 2>&1 | head -1)\"",
            "echo \"__TFLITE__=$(python3 -c 'import tflite_runtime.interpreter as t;print(getattr(t,\"__version__\",\"runtime-ok\"))' 2>&1 | head -1)\"",
            "echo \"__TESS__=$(tesseract --version 2>&1 | head -1)\"",
            "echo \"__TESSLANG__=$(tesseract --list-langs 2>&1 | tail -n +2 | tr '\\n' ' ')\""
        ).joinToString("\n")
        val out = exec(cmd, 90)
        fun tag(t: String): String {
            val prefix = "__${t}__="
            val raw = out.lineSequence().map { it.trimStart() }.firstOrNull { it.startsWith(prefix) } ?: return ""
            return raw.removePrefix(prefix).trim()
        }
        fun ver(t: String): String? {
            val v = tag(t)
            if (v.isBlank()) return null
            val bad = listOf("Traceback", "Error", "error", "not found", "No module", "Can't open", "Permission denied")
            return if (bad.any { v.contains(it) }) null else v
        }
        val st = BackendStatus(
            runtimeInstalled = true,
            sandboxReady = sandbox,
            pythonVersion = ver("PY"),
            numpyVersion = ver("NUMPY"),
            pillowVersion = ver("PIL"),
            onnxVersion = ver("ONNX"),
            tfliteVersion = ver("TFLITE"),
            tesseractVersion = ver("TESS"),
            tesseractLangs = tag("TESSLANG").split(" ").map { it.trim() }.filter { it.isNotBlank() },
            probeLog = out
        )
        cachedProbe = st
        return st
    }

    fun invalidateProbe() { cachedProbe = null }

    // ---------------- 已安装组件判定 ----------------

    private fun minValidBytes(comp: InferenceComponent): Long = when (comp.kind) {
        InferKind.DATA -> 64L
        InferKind.MODEL -> 256L * 1024L
        else -> 64L
    }

    /** 组件是否已可用（pkg 类依赖探测结果，file 类依赖真实文件）。 */
    fun isInstalled(comp: InferenceComponent): Boolean {
        val st = probe()
        return when (comp.installKind) {
            InstallKind.PKG -> when (comp.id) {
                "py-python" -> st.hasPython
                "py-numpy" -> st.hasNumpy
                "py-pillow" -> st.hasPillow
                "onnxruntime" -> st.hasOnnx
                "tflite-runtime" -> st.hasTflite
                "tesseract" -> st.hasTesseract &&
                    st.tesseractLangs.contains("eng") && st.tesseractLangs.contains("chi_sim")
                else -> false
            }
            else -> {
                val f = File(runtimeRoot, comp.destRel)
                f.isFile && f.length() >= minValidBytes(comp)
            }
        }
    }

    /** 当前已安装的 catalog 组件。 */
    fun installedComponents(): List<InferenceComponent> =
        InferenceCatalog.all().filter { isInstalled(it) }

    /** 已安装模型（catalog 命中 + 用户导入）。 */
    fun installedModels(): List<InstalledModel> {
        val fromCatalog = InferenceCatalog.all()
            .filter { it.kind == InferKind.MODEL }
            .mapNotNull { comp ->
                val f = File(runtimeRoot, comp.destRel)
                if (f.isFile && f.length() >= minValidBytes(comp)) {
                    InstalledModel(
                        path = f.absolutePath, name = f.name, sizeBytes = f.length(),
                        backend = comp.backend, tasks = comp.tasks, source = comp.id, installedAt = ""
                    )
                } else null
            }
        val known = fromCatalog.map { it.path }.toSet()
        return fromCatalog + readRegistryModels().filter { it.path !in known && File(it.path).isFile }
    }

    // ---------------- 依赖方案 ----------------

    /** 按任务给出依赖方案（「检测」阶段）。 */
    fun planFor(task: String): DependencyPlan {
        val t = if (task.isBlank()) InferTask.AUTO else task
        val needed = InferenceCatalog.forTask(t)
        val st = probe()
        if (!st.runtimeInstalled) {
            return DependencyPlan(
                t, needed, emptyList(),
                blockedReason = "内置运行时未安装。请先到「设置 → IDE 环境」安装内置 Termux 运行时，再回来安装推理依赖。"
            )
        }
        if (!st.sandboxReady) {
            return DependencyPlan(
                t, needed, emptyList(),
                blockedReason = "proot 沙盒不可用（缺少 proot/loader 组件）。请先启用 Shizuku 或更换支持 proot 的设备后再试。"
            )
        }
        val installed = needed.filter { isInstalled(it) }
        var missing = needed.filterNot { isInstalled(it) }
        // OCR 场景：tesseract 已装时 python 非必需（直接 shell 调 tesseract）
        if (t == InferTask.OCR && installed.any { it.id == "tesseract" }) {
            missing = missing.filterNot { it.id == "py-python" }
        }
        return DependencyPlan(t, missing, installed)
    }

    /** 供 Agent / UI 使用的能力报告。 */
    fun statusText(): String {
        val st = probe()
        val models = installedModels()
        return buildString {
            append("【本地推理环境】\n")
            append(st.summary()).append('\n')
            append("\n【已安装模型】").append(if (models.isEmpty()) "无" else models.size.toString() + " 个").append('\n')
            models.forEach { m ->
                append("- ").append(m.name).append("（").append(m.sizeText).append("，")
                append(InferBackend.label(m.backend)).append("，")
                append(m.tasks.joinToString("/") { InferTask.label(it) }).append("）\n")
            }
            append("\n模型目录：").append(modelDir.absolutePath).append('\n')
            append("推理库目录：").append(libDir.absolutePath)
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 安装一个组件。调用方必须先经用户确认（UI 弹窗或工具审批门控）。
     * 失败时回滚临时产物；pkg 类组件尝试执行 rollbackCmd 反向卸载。
     */
    fun install(
        comp: InferenceComponent,
        onProgress: (DownloadProgress) -> Unit = {},
        onLine: ((String) -> Unit)? = null
    ): InstallOutcome {
        val log = StringBuilder()
        val say: (String) -> Unit = { s ->
            log.append(s).append('\n')
            onLine?.invoke(s)
        }
        val st = probe(force = true)
        if (!st.runtimeInstalled) {
            return InstallOutcome(false, comp.id, comp.displayName, "内置运行时未安装，无法安装推理依赖", log.toString())
        }
        if (!st.sandboxReady) {
            return InstallOutcome(false, comp.id, comp.displayName, "proot 沙盒不可用，无法安装推理依赖", log.toString())
        }
        say("[${comp.displayName}] 开始安装（${comp.installKind}）")

        val outcome = when (comp.installKind) {
            InstallKind.PKG -> installByPkg(comp, say)
            InstallKind.FILE -> installByFile(comp, onProgress, say)
            InstallKind.ARCHIVE -> installByArchive(comp, onProgress, say)
            else -> InstallOutcome(false, comp.id, comp.displayName, "未知安装类型：${comp.installKind}")
        }

        if (!outcome.ok) {
            writeInstallLog(comp, outcome, log.toString())
            val alt = InferenceCatalog.alternativeFor(comp)
            val altTip = if (alt != null) "\n替代方案：可尝试「${alt.displayName}」（${alt.sizeText}）。" else ""
            return outcome.copy(message = outcome.message + altTip, log = log.toString())
        }

        invalidateProbe()
        val (verified, detail) = verify(comp)
        say("验证结果：$detail")
        if (!verified) {
            val rolledBack = rollback(comp, say)
            invalidateProbe()
            val out = InstallOutcome(
                false, comp.id, comp.displayName,
                "安装后验证未通过：$detail" + if (rolledBack) "（已自动回滚）" else "（回滚未完全成功，请手动检查）",
                log.toString(), rolledBack, outcome.usedUrl
            )
            writeInstallLog(comp, out, log.toString())
            return out
        }
        recordInstalled(comp)
        say("完成：${comp.displayName} 已通过验证")
        val alt = InferenceCatalog.alternativeFor(comp)
        val tip = if (alt != null && comp.backend != InferBackend.NONE && !isInstalled(alt)) {
            "\n提示：如需另一种后端，可另外安装「${alt.displayName}」（两者只需其一）。"
        } else ""
        val okOut = InstallOutcome(
            true, comp.id, comp.displayName,
            "安装成功并通过验证。$tip", log.toString(), false, outcome.usedUrl
        )
        writeInstallLog(comp, okOut, log.toString())
        return okOut
    }

    private fun installByPkg(comp: InferenceComponent, say: (String) -> Unit): InstallOutcome {
        if (comp.pkgCmd.isBlank()) return InstallOutcome(false, comp.id, comp.displayName, "未配置安装命令")
        say("执行命令：${comp.pkgCmd}")
        val code = execStreaming(comp.pkgCmd, 25) { line -> say("  $line") }
        say("命令退出码=$code（-1 表示沙盒不可用或超时）")
        // 退出码非 0 不直接判失败：部分源会返回非 0 但实际已装好，交由后续真实验证判定。
        return InstallOutcome(true, comp.id, comp.displayName, "包管理命令执行完毕，进入验证", usedUrl = "")
    }

    private fun installByFile(
        comp: InferenceComponent,
        onProgress: (DownloadProgress) -> Unit,
        say: (String) -> Unit
    ): InstallOutcome {
        val urls = comp.urls()
        if (urls.isEmpty()) return InstallOutcome(false, comp.id, comp.displayName, "未配置下载地址")
        modelDir.mkdirs()
        val staging = File(modelDir, ".staging").apply { mkdirs() }
        val tempFile = File(staging, File(comp.destRel).name)
        runCatching { tempFile.delete() }
        val r = LocalDownloader.download(urls, tempFile, comp.sha256, onProgress)
        if (!r.ok) {
            runCatching { tempFile.delete() }
            return InstallOutcome(false, comp.id, comp.displayName, "下载失败：${r.error}")
        }
        val (fileOk, reason) = verifyFile(tempFile, comp)
        if (!fileOk) {
            runCatching { tempFile.delete() }
            return InstallOutcome(false, comp.id, comp.displayName, "文件校验失败：$reason", usedUrl = r.usedUrl)
        }
        val dest = File(runtimeRoot, comp.destRel)
        dest.parentFile?.mkdirs()
        var placed = runCatching { tempFile.renameTo(dest) }.getOrDefault(false)
        if (!placed) {
            placed = runCatching {
                tempFile.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                true
            }.getOrDefault(false)
        }
        runCatching { tempFile.delete() }
        if (!placed) return InstallOutcome(false, comp.id, comp.displayName, "落盘失败：${dest.absolutePath}")
        say("已落盘：${dest.absolutePath}（${InferenceComponent.formatSize(dest.length())}）")
        return InstallOutcome(true, comp.id, comp.displayName, "下载完成", usedUrl = r.usedUrl)
    }

    private fun installByArchive(
        comp: InferenceComponent,
        onProgress: (DownloadProgress) -> Unit,
        say: (String) -> Unit
    ): InstallOutcome {
        val urls = comp.urls()
        if (urls.isEmpty()) return InstallOutcome(false, comp.id, comp.displayName, "未配置下载地址")
        val staging = File(ctx.filesDir, "localai/staging").apply { mkdirs() }
        val tempFile = File(staging, File(comp.destRel).name + ".zip")
        val r = LocalDownloader.download(urls, tempFile, comp.sha256, onProgress)
        if (!r.ok) {
            runCatching { tempFile.delete() }
            return InstallOutcome(false, comp.id, comp.displayName, "下载失败：${r.error}")
        }
        val destDir = File(runtimeRoot, comp.destRel).apply { mkdirs() }
        val extracted = runCatching {
            java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("压缩包含越界路径：${entry.name}")
                    }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        }.getOrDefault(false)
        runCatching { tempFile.delete() }
        if (!extracted) return InstallOutcome(false, comp.id, comp.displayName, "解压失败")
        say("已解压到：${destDir.absolutePath}")
        return InstallOutcome(true, comp.id, comp.displayName, "解压完成", usedUrl = r.usedUrl)
    }

    /** 回滚：删除落盘文件 / 执行反向卸载命令。 */
    private fun rollback(comp: InferenceComponent, say: (String) -> Unit): Boolean {
        say("开始回滚 ${comp.displayName} …")
        return when (comp.installKind) {
            InstallKind.PKG -> {
                if (comp.rollbackCmd.isBlank()) {
                    say("该组件未提供反向卸载命令，跳过回滚（需手动处理）")
                    false
                } else {
                    val code = execStreaming(comp.rollbackCmd, 10) { say("  $it") }
                    say("回滚命令退出码=$code")
                    code == 0
                }
            }
            else -> {
                val f = File(runtimeRoot, comp.destRel)
                val ok = !f.exists() || f.delete()
                say(if (ok) "已删除 ${f.absolutePath}" else "删除失败：${f.absolutePath}")
                ok
            }
        }
    }

    // ---------------- 验证 ----------------

    /** 文件结构级校验：拦截 HTML 错误页、错误后缀、过小文件。 */
    fun verifyFile(file: File, comp: InferenceComponent): Pair<Boolean, String> {
        if (!file.isFile) return false to "文件不存在"
        if (file.length() < minValidBytes(comp)) {
            return false to "文件过小（${file.length()}B，疑似错误页或下载未完成）"
        }
        val head = runCatching {
            file.inputStream().use { ins ->
                val b = ByteArray(16)
                val n = ins.read(b)
                if (n > 0) b.copyOf(n) else ByteArray(0)
            }
        }.getOrDefault(ByteArray(0))
        val text = String(head, Charsets.ISO_8859_1)
        if (text.startsWith("<!DOCTYPE") || text.contains("<html") || text.startsWith("<?xml") ||
            text.startsWith("{\"") || text.startsWith("\"{\"")
        ) {
            return false to "内容像网页/JSON 而非模型文件（可能被镜像拦截或需要跳转认证）"
        }
        when (file.extension.lowercase()) {
            "tflite" -> {
                val id = if (head.size >= 8) String(head, 4, 4, Charsets.ISO_8859_1) else ""
                if (id != "TFL3") return false to "不是有效的 TFLite 模型（file_identifier=$id）"
            }
            "onnx" -> {
                if (head.isNotEmpty() && (head[0].toInt() and 0xff) == 0x3c) {
                    return false to "不是有效的 ONNX 模型（protobuf 头异常）"
                }
            }
            "txt", "names" -> {
                if (file.readText().isBlank()) return false to "标签文件为空"
            }
        }
        return true to "文件结构检查通过（${InferenceComponent.formatSize(file.length())}）"
    }

    /**
     * 深度验证：
     *  - pkg 类 → 重新探测是否真正可用；
     *  - onnx 模型 → 用 onnxruntime 真实加载一次会话（最强验证）；
     *  - tflite 模型 → 用 tflite_runtime 真实加载；
     *  - 其它 → 结构检查。
     */
    fun verify(comp: InferenceComponent): Pair<Boolean, String> {
        if (comp.installKind == InstallKind.PKG) {
            return if (isInstalled(comp)) true to "组件已可真实调用"
            else false to "安装后重新探测仍未发现该组件"
        }
        val f = File(runtimeRoot, comp.destRel)
        val structural = verifyFile(f, comp)
        if (!structural.first) return structural
        if (comp.kind != InferKind.MODEL) return structural
        val st = probe()
        val q = shellQuote(f.absolutePath)
        val ext = f.extension.lowercase()
        val isOnnx = ext == "onnx"
        if (isOnnx && !st.hasOnnx) return true to "文件已就位；ONNX Runtime 未安装，暂无法做加载测试"
        if (!isOnnx && !st.hasTflite) return true to "文件已就位；TFLite Runtime 未安装，暂无法做加载测试"
        val cmd = if (isOnnx) {
            "python3 -c \"import onnxruntime as ort;s=ort.InferenceSession('$q');" +
                "print('OK',[i.shape for i in s.get_inputs()],[o.shape for o in s.get_outputs()])\""
        } else {
            "python3 -c \"import tflite_runtime.interpreter as t;i=t.Interpreter('$q');" +
                "i.allocate_tensors();print('OK',i.get_input_details()[0]['shape'])\""
        }
        val out = exec(cmd, 120)
        return if (out.contains("OK")) {
            true to "模型加载测试通过：" + out.substringAfter("OK").trim().take(160)
        } else {
            false to "模型加载测试失败：" + out.trim().take(200)
        }
    }

    // ---------------- 删除 / 导入 ----------------

    /** 删除已安装组件（高风险操作，调用方必须先经用户确认）。 */
    fun remove(idOrName: String): InstallOutcome {
        val key = idOrName.trim()
        val comps = mutableListOf<InferenceComponent>()
        InferenceCatalog.find(key)?.let { comps += it }
        if (comps.isEmpty()) {
            comps += InferenceCatalog.all().filter {
                (it.displayName.contains(key, true) || it.id.contains(key, true)) && isInstalled(it)
            }
        }
        val models = installedModels().filter {
            it.name.equals(key, true) || it.path == key || it.name.contains(key, true)
        }
        if (comps.isEmpty() && models.isEmpty()) {
            return InstallOutcome(false, key, key, "未找到匹配的已安装推理库/模型：$key")
        }
        val log = StringBuilder()
        var okCount = 0
        var total = 0
        comps.forEach { comp ->
            total++
            if (comp.installKind == InstallKind.PKG) {
                if (comp.rollbackCmd.isBlank()) {
                    log.append("• ").append(comp.displayName).append("：无反向卸载命令，已跳过\n")
                } else {
                    val code = execStreaming(comp.rollbackCmd, 10) { log.append("  ").append(it).append('\n') }
                    invalidateProbe()
                    val gone = !isInstalled(comp)
                    if (gone) okCount++
                    log.append("• ").append(comp.displayName)
                        .append("：卸载命令退出码=").append(code)
                        .append(if (gone) "（已确认移除）" else "（仍在，请查看日志）").append('\n')
                }
            } else {
                val f = File(runtimeRoot, comp.destRel)
                val deleted = !f.exists() || f.delete()
                if (deleted) okCount++
                log.append("• ").append(comp.displayName).append("：")
                    .append(if (deleted) "已删除 " else "删除失败 ").append(f.absolutePath).append('\n')
            }
        }
        models.forEach { m ->
            total++
            val f = File(m.path)
            val deleted = !f.exists() || f.delete()
            if (deleted) okCount++
            log.append("• 模型 ").append(m.name).append("：")
                .append(if (deleted) "已删除" else "删除失败").append('\n')
        }
        invalidateProbe()
        removeFromRegistry(comps.map { it.id } + models.map { it.path })
        return InstallOutcome(
            okCount > 0, key, key,
            "已处理 $okCount/$total 项。可在「本地 AI 推理管理」页复核当前状态。",
            log.toString()
        )
    }

    /** 导入本地模型文件（用户自备 .onnx / .tflite）。 */
    fun importModel(src: File, task: String = InferTask.AUTO, backendHint: String = ""): InstallOutcome {
        if (!src.isFile) return InstallOutcome(false, src.name, src.name, "文件不存在：" + src.absolutePath)
        val ext = src.extension.lowercase()
        if (ext !in setOf("onnx", "tflite", "pb")) {
            return InstallOutcome(false, src.name, src.name, "仅支持 .onnx / .tflite 模型文件（当前：$ext）")
        }
        val backend = when {
            backendHint.isNotBlank() -> backendHint
            ext == "onnx" -> InferBackend.ONNX
            else -> InferBackend.TFLITE
        }
        // 未显式指定任务时按文件名推断，避免把检测模型（如 ssd_mobilenet_v1_12.onnx）当成分类模型
        val resolvedTask = if (task == InferTask.AUTO) guessTask(src.name) else task
        modelDir.mkdirs()
        val dest = File(modelDir, src.name)
        val copied = runCatching {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            true
        }.getOrDefault(false)
        if (!copied) return InstallOutcome(false, src.name, src.name, "复制失败：" + dest.absolutePath)
        val pseudo = InferenceComponent(
            id = "import:" + src.name, kind = InferKind.MODEL, displayName = src.name,
            backend = backend, tasks = listOf(resolvedTask), purpose = "用户导入模型",
            installKind = InstallKind.FILE,
            destRel = InferenceCatalog.MODEL_DIR_REL + "/" + src.name
        )
        val (v, detail) = verifyFile(dest, pseudo)
        if (!v) {
            runCatching { dest.delete() }
            return InstallOutcome(false, src.name, src.name, "模型校验失败：$detail")
        }
        recordModel(
            InstalledModel(
                path = dest.absolutePath, name = dest.name, sizeBytes = dest.length(),
                backend = backend, tasks = listOf(resolvedTask), source = "import", installedAt = now()
            )
        )
        invalidateProbe()
        return InstallOutcome(
            true, src.name, src.name,
            "已导入到 " + dest.absolutePath + "（" + InferenceComponent.formatSize(dest.length()) + "）。" +
                "推断任务：" + InferTask.label(resolvedTask) +
                (if (resolvedTask == InferTask.AUTO) "（未能从文件名判断，推理时请显式指定 task）" else "") +
                "。推理时请用 model 参数指定该文件名；如需标签文件请另行导入。"
        )
    }

    /**
     * 依据文件名推断任务（导入模型且 task = auto 时使用）。
     * 检测关键词先判断，这样 ssd_mobilenet_v1_12.onnx 不会被 mobilenet 关键词误判成分类。
     */
    private fun guessTask(fileName: String): String {
        val n = fileName.lowercase()
        val detectKeys = listOf("ssd", "yolo", "detect", "rcnn", "retina", "detr", "efficientdet", "nanodet")
        val classifyKeys = listOf(
            "mobilenet", "resnet", "squeezenet", "efficientnet", "inception",
            "vgg", "convnext", "classif", "-cls", "_cls"
        )
        if (detectKeys.any { n.contains(it) }) return InferTask.DETECT
        if (classifyKeys.any { n.contains(it) }) return InferTask.CLASSIFY
        return InferTask.AUTO
    }

    // ---------------- 记账（避免重复询问） ----------------

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun readRegistry(): JSONObject = runCatching {
        if (registryFile.isFile) JSONObject(registryFile.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun writeRegistry(o: JSONObject) {
        runCatching {
            registryDir.mkdirs()
            registryFile.writeText(o.toString(2))
        }
    }

    private fun readRegistryModels(): List<InstalledModel> = runCatching {
        val arr = readRegistry().optJSONArray("models") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            InstalledModel(
                path = o.optString("path"),
                name = o.optString("name"),
                sizeBytes = o.optLong("sizeBytes"),
                backend = o.optString("backend", InferBackend.ONNX),
                tasks = o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                source = o.optString("source", "import"),
                installedAt = o.optString("installedAt")
            )
        }
    }.getOrDefault(emptyList())

    private fun recordInstalled(comp: InferenceComponent) {
        val o = readRegistry()
        val arr = o.optJSONArray("installed") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") != comp.id) kept.put(item)
        }
        kept.put(
            JSONObject().apply {
                put("id", comp.id)
                put("name", comp.displayName)
                put("kind", comp.kind)
                put("backend", comp.backend)
                put("destRel", comp.destRel)
                put("installedAt", now())
            }
        )
        o.put("installed", kept)
        writeRegistry(o)
    }

    private fun recordModel(m: InstalledModel) {
        val o = readRegistry()
        val arr = o.optJSONArray("models") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("path") != m.path) kept.put(item)
        }
        kept.put(
            JSONObject().apply {
                put("path", m.path)
                put("name", m.name)
                put("sizeBytes", m.sizeBytes)
                put("backend", m.backend)
                put("tasks", JSONArray(m.tasks))
                put("source", m.source)
                put("installedAt", if (m.installedAt.isBlank()) now() else m.installedAt)
            }
        )
        o.put("models", kept)
        writeRegistry(o)
    }

    private fun removeFromRegistry(ids: List<String>) {
        if (ids.isEmpty()) return
        val o = readRegistry()
        val set = ids.toSet()
        o.optJSONArray("installed")?.let { arr ->
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("id") !in set) kept.put(item)
            }
            o.put("installed", kept)
        }
        o.optJSONArray("models")?.let { arr ->
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("path") !in set) kept.put(item)
            }
            o.put("models", kept)
        }
        writeRegistry(o)
    }

    /** 把安装记录写入项目知识库，避免下次同一项目重复询问。 */
    fun remember(project: Project?, comps: List<InferenceComponent>) {
        if (comps.isEmpty()) return
        val text = "本地AI推理依赖已安装（" + now() + "）：\n" +
            comps.joinToString("\n") { "- ${it.id} / ${it.displayName} / ${it.backend} / ${it.destRel}" }
        runCatching { recordInstalledNote(text) }
        if (project != null) {
            runCatching {
                ProjectKnowledge.memoryAppend(project, "本地AI推理环境", text)
            }
        }
    }

    private var lastInstallNote: String = ""

    private fun recordInstalledNote(text: String) {
        lastInstallNote = text
    }

    /** 最近一次 remember() 写入的摘要（供 UI 展示）。 */
    fun lastRememberedNote(): String = lastInstallNote

    private fun writeInstallLog(comp: InferenceComponent, outcome: InstallOutcome, log: String) {
        runCatching {
            registryDir.mkdirs()
            val head = "[${now()}] ${comp.id} -> ok=${outcome.ok} rolledBack=${outcome.rolledBack} " +
                "url=${outcome.usedUrl}\n"
            installLogFile.appendText(head + log + "\n")
            // 防止日志无限增长
            if (installLogFile.length() > 512 * 1024) {
                val tail = installLogFile.readText().takeLast(256 * 1024)
                installLogFile.writeText(tail)
            }
        }
    }

    /** 单引号包裹 shell 参数，防注入/防路径空格。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
