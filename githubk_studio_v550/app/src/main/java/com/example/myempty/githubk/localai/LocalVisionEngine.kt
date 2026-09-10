package com.example.myempty.githubk.localai

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 单条识别结果（分类=标签+置信度；检测=标签+置信度+像素框；OCR=文本行）。 */
data class LocalVisionItem(
    val label: String,
    val score: Float = 0f,
    val box: List<Int>? = null,
    val text: String = ""
)

/** 本地视觉推理结果。 */
data class LocalVisionResult(
    val ok: Boolean,
    val task: String,
    val backend: String = "",
    val model: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val inputSize: List<Int> = emptyList(),
    val items: List<LocalVisionItem> = emptyList(),
    val elapsedMs: Long = 0L,
    val error: String = "",
    val hint: String = "",
    val rawJson: String = "",
    val log: String = ""
) {
    /** 面向用户/Agent 的可读报告。 */
    fun report(): String {
        if (!ok) {
            return buildString {
                append("本地识别失败（").append(InferTask.label(task)).append("）\n")
                append("原因：").append(error)
                if (hint.isNotBlank()) append("\n建议：").append(hint)
            }
        }
        return buildString {
            append("【本地离线识别 · ").append(InferTask.label(task)).append("】")
            append(InferBackend.label(backend)).append(" / ").append(model)
            append("（").append(elapsedMs).append("ms）\n")
            if (width > 0) append("图片：").append(width).append("×").append(height).append('\n')
            when (task) {
                InferTask.OCR -> {
                    if (items.isEmpty()) append("未识别到文字。")
                    else items.forEachIndexed { i, it ->
                        append(i + 1).append(". ").append(it.text.trim()).append('\n')
                    }
                }
                else -> {
                    if (items.isEmpty()) append("无结果。")
                    items.forEachIndexed { i, it ->
                        append(i + 1).append(". ").append(it.label)
                        append("  ").append("%.1f%%".format(it.score * 100f))
                        it.box?.let { b ->
                            append("  [框 ").append(b.joinToString(",")).append("]")
                        }
                        append('\n')
                    }
                }
            }
            append("（推理全程在本机完成，图片未上传）")
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("ok", ok)
        o.put("task", task)
        o.put("backend", backend)
        o.put("model", model)
        o.put("width", width)
        o.put("height", height)
        o.put("elapsedMs", elapsedMs)
        if (error.isNotBlank()) o.put("error", error)
        if (hint.isNotBlank()) o.put("hint", hint)
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(
                JSONObject().apply {
                    put("label", it.label)
                    if (it.score > 0f) put("score", it.score.toDouble())
                    it.box?.let { b -> put("box", JSONArray(b)) }
                    if (it.text.isNotBlank()) put("text", it.text)
                }
            )
        }
        o.put("items", arr)
        return o.toString()
    }
}

/**
 * LocalVisionEngine v6.4 —— 纯本地（离线）图像识别执行器。
 *
 * 三条真实可执行的路径，全部经用户界面确认后才会安装依赖：
 *   OCR      → shell 调 tesseract（引擎+语言数据均在 runtime 内，离线）
 *   CLASSIFY → python3 + onnxruntime / tflite_runtime 跑 .onnx / .tflite（MobileNetV2 等）
 *   DETECT   → python3 + onnxruntime 跑 SSD / YOLOv8 系列 ONNX（图内预处理 + letterbox/NMS 后处理）
 *
 * 设计原则：
 *   - 绝不伪造结果：任何一步失败都返回明确 error + hint，并给出替代方案。
 *   - 图像预处理（解码/缩放/归一化）统一走本模块的 Python harness。
 *   - 不联网、不上传：只有 pkg/pip 安装依赖时才需要网络。
 */
class LocalVisionEngine(private val ctx: Context) {

    private val mgr = LocalInferenceManager(ctx)

    private val harnessFile: File get() = File(mgr.harnessDir, "localvision.py")
    private val harnessVerFile: File get() = File(mgr.harnessDir, "harness.version")

    val manager: LocalInferenceManager get() = mgr

    // ---------------- 依赖方案 ----------------

    /** 判定任务所需依赖并返回安装方案（供「检测→告知→确认→安装」流程）。 */
    fun plan(task: String, imagePath: String = ""): DependencyPlan {
        val t = resolveTask(task, imagePath, "")
        return mgr.planFor(t)
    }

    // ---------------- 入口 ----------------

    fun run(
        imagePath: String,
        task: String = InferTask.AUTO,
        model: String = "",
        labels: String = "",
        topK: Int = 5,
        conf: Double = 0.25,
        iou: Double = 0.45,
        inputSize: Int = 0,
        langs: String = "",
        psm: Int = 6,
        normalize: Boolean = false
    ): LocalVisionResult {
        val started = System.currentTimeMillis()
        val img = File(imagePath.trim())
        if (!img.isFile) {
            return LocalVisionResult(false, task, error = "图片不存在：${imagePath.trim()}",
                hint = "请先上传/选择一张本地图片，再调用本地识别。")
        }
        val dims = readDims(img)
        val t = resolveTask(task, imagePath, model)

        // 依赖就绪性检查（真实探测）
        val plan = mgr.planFor(t)
        if (plan.blockedReason != null) {
            return LocalVisionResult(
                false, t, width = dims.first, height = dims.second,
                error = plan.blockedReason, hint = "安装完成后可再次调用。"
            )
        }
        if (plan.needsInstall) {
            return LocalVisionResult(
                false, t, width = dims.first, height = dims.second,
                error = "本地推理依赖尚未安装完整（缺少 ${plan.missing.size} 项）",
                hint = "请先确认安装：" + plan.missing.joinToString("、") { it.displayName },
                log = plan.describe()
            )
        }

        return try {
            when (t) {
                InferTask.OCR -> runOcr(img, dims, langs, psm, started)
                InferTask.DETECT -> runNeural(img, dims, t, model, labels, topK, conf, iou, inputSize, false, started)
                else -> runNeural(img, dims, t, model, labels, topK, conf, iou, inputSize, normalize, started)
            }
        } catch (t2: Throwable) {
            LocalVisionResult(
                false, t, width = dims.first, height = dims.second,
                error = "${t2.javaClass.simpleName}: ${t2.message}",
                hint = "请把该错误反馈给开发者；也可先改用云端 vision_analyze。",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    // ---------------- 任务与模型解析 ----------------

    /** 把用户文字/模型名解析成真实任务。 */
    fun resolveTask(task: String, imagePath: String, model: String): String {
        val explicit = task.trim().lowercase()
        if (explicit in listOf(InferTask.OCR, InferTask.CLASSIFY, InferTask.DETECT)) return explicit
        val byText = InferTask.fromText(task)
        if (byText != InferTask.AUTO) return byText
        val m = model.lowercase()
        if (m.contains("yolo") || m.contains("detect")) return InferTask.DETECT
        if (m.contains("ocr")) return InferTask.OCR
        if (m.contains("mobilenet") || m.contains("resnet") || m.contains("squeezenet")) return InferTask.CLASSIFY
        // 未指明：按已安装能力择优
        val st = mgr.probe()
        val models = mgr.installedModels()
        if (models.any { it.tasks.contains(InferTask.CLASSIFY) }) return InferTask.CLASSIFY
        if (models.any { it.tasks.contains(InferTask.DETECT) }) return InferTask.DETECT
        if (st.hasTesseract) return InferTask.OCR
        return InferTask.CLASSIFY
    }

    /** 解析模型文件；未指定时按任务择优选择已安装模型。 */
    private fun resolveModel(task: String, model: String): File? {
        val name = model.trim()
        if (name.isNotEmpty()) {
            val direct = File(name)
            if (direct.isFile) return direct
            val inDir = File(mgr.modelDir, name)
            if (inDir.isFile) return inDir
            val fuzzy = mgr.installedModels().firstOrNull { it.name.contains(name, true) }
            if (fuzzy != null) return File(fuzzy.path)
            return null
        }
        val preferredIds = if (task == InferTask.DETECT) {
            listOf("model-ssd-mobilenetv1")
        } else {
            listOf("model-mobilenetv2", "model-resnet18", "model-squeezenet")
        }
        val installed = mgr.installedModels()
        preferredIds.forEach { id ->
            installed.firstOrNull { it.source == id }?.let { return File(it.path) }
        }
        return installed.firstOrNull { it.tasks.contains(task) || task == InferTask.CLASSIFY }?.let { File(it.path) }
    }

    /** 解析标签文件；未指定时按任务自动配对。 */
    private fun resolveLabels(task: String, labels: String): File? {
        val name = labels.trim()
        if (name.isNotEmpty()) {
            val direct = File(name)
            if (direct.isFile) return direct
            val inDir = File(mgr.modelDir, name)
            if (inDir.isFile) return inDir
        }
        val auto = if (task == InferTask.DETECT) "coco.names" else "imagenet_classes.txt"
        val f = File(mgr.modelDir, auto)
        return if (f.isFile) f else null
    }

    private fun readDims(f: File): Pair<Int, Int> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            opts.outWidth to opts.outHeight
        } catch (_: Throwable) {
            0 to 0
        }
    }

    // ---------------- OCR：tesseract（真实 shell 调用） ----------------

    private fun runOcr(
        img: File,
        dims: Pair<Int, Int>,
        langsIn: String,
        psm: Int,
        started: Long
    ): LocalVisionResult {
        val st = mgr.probe()
        if (!st.hasTesseract) {
            return LocalVisionResult(
                false, InferTask.OCR, backend = InferBackend.TESSERACT,
                width = dims.first, height = dims.second,
                error = "Tesseract OCR 引擎未安装",
                hint = "安装「Tesseract OCR 引擎」后即可离线提取文字。",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
        val langs = if (langsIn.isNotBlank()) langsIn else {
            val want = listOf("chi_sim", "eng").filter { st.tesseractLangs.contains(it) }
            if (want.isEmpty()) st.tesseractLangs.firstOrNull() ?: "eng" else want.joinToString("+")
        }
        val safePsm = psm.coerceIn(0, 13)
        val cmd = "tesseract ${shq(img.absolutePath)} stdout -l ${shq(langs)} --psm $safePsm 2>/dev/null; " +
            "echo \"__RC__=\$?\""
        val out = mgr.exec(cmd, 180)
        val lines = out.lines()
        val rcLine = lines.lastOrNull { it.trim().startsWith("__RC__=") }
        val rc = rcLine?.substringAfter("__RC__=")?.trim()?.toIntOrNull() ?: -1
        val text = lines.filterNot { it.trim().startsWith("__RC__=") }.joinToString("\n").trim()
        if (rc != 0) {
            return LocalVisionResult(
                false, InferTask.OCR, backend = InferBackend.TESSERACT,
                width = dims.first, height = dims.second, model = "tesseract($langs)",
                error = "tesseract 退出码=$rc",
                hint = "若缺少语言包，请在「本地 AI 推理管理」内重装 Tesseract（含 eng + chi_sim）。",
                log = out.takeLast(600), elapsedMs = System.currentTimeMillis() - started
            )
        }
        val items = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { LocalVisionItem(label = it.take(60), text = it) }
        return LocalVisionResult(
            ok = true, task = InferTask.OCR, backend = InferBackend.TESSERACT,
            model = "tesseract($langs, psm=$safePsm)",
            width = dims.first, height = dims.second,
            items = items, elapsedMs = System.currentTimeMillis() - started
        )
    }

    // ---------------- 神经网络：ONNX Runtime / TFLite ----------------

    private fun runNeural(
        img: File,
        dims: Pair<Int, Int>,
        task: String,
        model: String,
        labels: String,
        topK: Int,
        conf: Double,
        iou: Double,
        inputSize: Int,
        normalize: Boolean,
        started: Long
    ): LocalVisionResult {
        val st = mgr.probe()
        val modelFile = resolveModel(task, model)
            ?: return LocalVisionResult(
                false, task, width = dims.first, height = dims.second,
                error = "未找到可用模型" + if (model.isNotBlank()) "（$model）" else "",
                hint = if (task == InferTask.DETECT)
                    "请安装「SSD MobileNetV1（目标检测）」模型，或指定 model 参数为已导入的 .onnx 文件。"
                else "请安装「MobileNetV2（图像分类）」或导入自己的 .onnx/.tflite 模型。",
                elapsedMs = System.currentTimeMillis() - started
            )
        val ext = modelFile.extension.lowercase()
        val isOnnx = ext == "onnx"
        if (task == InferTask.DETECT && !isOnnx) {
            return LocalVisionResult(
                false, task, backend = InferBackend.TFLITE, model = modelFile.name,
                width = dims.first, height = dims.second,
                error = "目标检测当前仅支持 ONNX 格式（SSD / YOLO 后处理）",
                hint = "TFLite 检测模型的输出格式高度依赖具体模型，请改用 ONNX 的 SSD MobileNetV1；或把 task 改为 classify。",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
        val missing = buildList {
            if (!st.hasPython) add("python3")
            if (!st.hasNumpy) add("NumPy")
            if (!st.hasPillow) add("Pillow")
            if (isOnnx && !st.hasOnnx) add("ONNX Runtime")
            if (!isOnnx && !st.hasTflite) add("TensorFlow Lite Runtime")
        }
        if (missing.isNotEmpty()) {
            return LocalVisionResult(
                false, task, width = dims.first, height = dims.second, model = modelFile.name,
                error = "缺少运行依赖：" + missing.joinToString("、"),
                hint = "请先在「本地 AI 推理管理」页安装这些依赖。",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
        if (!ensureHarness()) {
            return LocalVisionResult(
                false, task, width = dims.first, height = dims.second,
                error = "无法写入推理脚本（${harnessFile.absolutePath}）",
                hint = "请检查运行时目录权限。", elapsedMs = System.currentTimeMillis() - started
            )
        }
        val labelsFile = resolveLabels(task, labels)
        val cmd = buildString {
            append("python3 ").append(shq(harnessFile.absolutePath))
            append(" --task ").append(task)
            append(" --model ").append(shq(modelFile.absolutePath))
            if (labelsFile != null) append(" --labels ").append(shq(labelsFile.absolutePath))
            append(" --topk ").append(topK.coerceIn(1, 50))
            append(" --conf ").append(conf)
            append(" --iou ").append(iou)
            if (inputSize > 0) append(" --size ").append(inputSize)
            append(" --normalize ").append(if (normalize) 1 else 0)
            append(' ').append(shq(img.absolutePath))
        }
        val out = mgr.exec(cmd, 240)
        val json = extractJson(out)
            ?: return LocalVisionResult(
                false, task, width = dims.first, height = dims.second, model = modelFile.name,
                error = "推理脚本未返回有效结果",
                hint = "请把下方日志反馈给开发者。", log = out.takeLast(1200),
                elapsedMs = System.currentTimeMillis() - started
            )
        return parseHarnessJson(json, task, modelFile, labelsFile, dims, started)
    }

    private fun parseHarnessJson(
        json: String,
        task: String,
        modelFile: File,
        labelsFile: File?,
        dims: Pair<Int, Int>,
        started: Long
    ): LocalVisionResult {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: return LocalVisionResult(
                false, task, width = dims.first, height = dims.second,
                error = "结果 JSON 解析失败", log = json.takeLast(600),
                elapsedMs = System.currentTimeMillis() - started
            )
        if (!o.optBoolean("ok", false)) {
            return LocalVisionResult(
                false, task, backend = o.optString("backend"), model = modelFile.name,
                width = dims.first, height = dims.second,
                error = o.optString("error", "未知错误"), hint = o.optString("hint", ""),
                elapsedMs = o.optLong("ms", System.currentTimeMillis() - started)
            )
        }
        val items = mutableListOf<LocalVisionItem>()
        o.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val boxArr = it.optJSONArray("box")
                items += LocalVisionItem(
                    label = it.optString("label"),
                    score = it.optDouble("score", 0.0).toFloat(),
                    box = boxArr?.let { b -> (0 until b.length()).map { k -> b.optInt(k) } },
                    text = it.optString("text")
                )
            }
        }
        val inSize = o.optJSONArray("inputSize")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        return LocalVisionResult(
            ok = true, task = task,
            backend = o.optString("backend"),
            model = o.optString("model").ifBlank { modelFile.name },
            width = dims.first, height = dims.second,
            inputSize = inSize,
            items = items,
            elapsedMs = o.optLong("ms", System.currentTimeMillis() - started),
            rawJson = json
        )
    }

    /** 从混合输出中截取最后一个完整 JSON 对象。 */
    private fun extractJson(out: String): String? {
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return out.substring(start, end + 1)
    }

    // ---------------- Python harness ----------------

    private fun ensureHarness(): Boolean {
        return try {
            mgr.harnessDir.mkdirs()
            val cur = runCatching { harnessVerFile.readText().trim() }.getOrDefault("")
            if (!harnessFile.isFile || cur != HARNESS_VERSION) {
                harnessFile.writeText(HARNESS_PY)
                harnessVerFile.writeText(HARNESS_VERSION)
            }
            harnessFile.isFile
        } catch (_: Throwable) {
            false
        }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val HARNESS_VERSION = "lv1-2024"

        /**
         * 本地推理 harness：负责像素解码/缩放/归一化 + 模型执行 + 后处理。
         * 不联网；仅读取传入的图片、模型与标签文件。
         */
        private val HARNESS_PY = """
# GitHubK Studio LocalVision harness
# usage: python3 localvision.py --task classify|detect --model M [--labels L] [--topk 5]
#        [--size 224] [--conf 0.25] [--iou 0.45] [--normalize 0] IMAGE
import sys, os, json, time, argparse

def jprint(o):
    sys.stdout.write(json.dumps(o, ensure_ascii=False))
    sys.stdout.write("\n")

def fail(msg, hint="", **kw):
    o = {"ok": False, "error": str(msg)}
    if hint:
        o["hint"] = hint
    o.update(kw)
    jprint(o)
    sys.exit(0)

def read_labels(p):
    if not p or not os.path.isfile(p):
        return None
    out = []
    with open(p, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(line)
    return out

def load_rgb(path, size=None):
    import numpy as np
    from PIL import Image
    im = Image.open(path)
    im = im.convert("RGB")
    if size:
        im = im.resize((int(size), int(size)), Image.BILINEAR)
    return np.asarray(im).astype("float32") / 255.0

def normalize_imagenet(x):
    import numpy as np
    mean = np.array([0.485, 0.456, 0.406], dtype="float32")
    std = np.array([0.229, 0.224, 0.225], dtype="float32")
    return (x - mean) / std

def softmax(x):
    import numpy as np
    x = np.asarray(x, dtype="float32").reshape(-1)
    m = float(np.max(x))
    e = np.exp(x - m)
    s = float(np.sum(e))
    return e / s if s > 0 else e

def rank(scores, labels, k):
    import numpy as np
    s = np.asarray(scores, dtype="float32").reshape(-1)
    tot = float(np.sum(s))
    if not (0.98 <= tot <= 1.02):
        s = softmax(s)
    order = np.argsort(-s)[:max(1, int(k))]
    out = []
    for i in order:
        i = int(i)
        lbl = labels[i] if labels and i < len(labels) else ("class %d" % i)
        out.append({"index": i, "label": lbl, "score": float(s[i])})
    return out

def letterbox(arr, size):
    import numpy as np
    from PIL import Image
    h, w = arr.shape[:2]
    r = min(size / float(w), size / float(h))
    nw, nh = max(1, int(round(w * r))), max(1, int(round(h * r)))
    # 逐通道以 float32(F 模式) 缩放，避免 uint8 量化带来精度损失
    chans = []
    for c in range(arr.shape[2]):
        im = Image.fromarray(np.ascontiguousarray(arr[:, :, c], dtype="float32"), mode="F")
        chans.append(np.asarray(im.resize((nw, nh), Image.BILINEAR), dtype="float32"))
    a = np.stack(chans, axis=2)
    canvas = np.full((size, size, 3), 114.0 / 255.0, dtype="float32")
    dx, dy = (size - nw) // 2, (size - nh) // 2
    canvas[dy:dy + nh, dx:dx + nw, :] = a
    return canvas, r, dx, dy

def nms(boxes, scores, thr):
    import numpy as np
    if len(boxes) == 0:
        return []
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(x1[i], x1[rest])
        yy1 = np.maximum(y1[i], y1[rest])
        xx2 = np.minimum(x2[i], x2[rest])
        yy2 = np.minimum(y2[i], y2[rest])
        w = np.maximum(0.0, xx2 - xx1)
        h = np.maximum(0.0, yy2 - yy1)
        inter = w * h
        iou = inter / (areas[i] + areas[rest] - inter + 1e-9)
        order = rest[iou <= thr]
    return keep

def tflite_classify(model, image, labels, topk, size):
    import numpy as np
    import tflite_runtime.interpreter as tfl
    interp = tfl.Interpreter(model_path=model)
    interp.allocate_tensors()
    din = interp.get_input_details()[0]
    dout = interp.get_output_details()[0]
    shape = list(din["shape"])
    hw = int(size) if size and size > 0 else 224
    if len(shape) == 4 and shape[1] == shape[2] and shape[1] > 0:
        hw = int(shape[1])
    img = load_rgb(image, hw)
    if din["dtype"] == np.uint8:
        x = (img * 255.0).astype("uint8")[None, ...]
    else:
        x = img.astype(np.float32)[None, ...]
    t0 = time.time()
    interp.set_tensor(din["index"], x)
    interp.invoke()
    out = interp.get_tensor(dout["index"])
    ms = int((time.time() - t0) * 1000)
    return out, ms, [hw, hw]

def onnx_classify(model, image, labels, topk, size, normalize):
    import numpy as np
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = max(1, min(4, os.cpu_count() or 2))
    sess = ort.InferenceSession(model, sess_options=so, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    shape = list(inp.shape)
    nchw, hw = False, (int(size) if size and size > 0 else 224)
    if len(shape) == 4:
        if isinstance(shape[1], int) and shape[1] == 3:
            nchw = True
            if isinstance(shape[2], int) and shape[2] > 0:
                hw = int(shape[2])
        elif isinstance(shape[3], int) and shape[3] == 3:
            if isinstance(shape[1], int) and shape[1] > 0:
                hw = int(shape[1])
    img = load_rgb(image, hw)
    if normalize:
        img = normalize_imagenet(img)
    x = img.transpose(2, 0, 1) if nchw else img
    x = np.ascontiguousarray(x[None, ...].astype("float32"))
    t0 = time.time()
    out = sess.run(None, {inp.name: x})[0]
    ms = int((time.time() - t0) * 1000)
    return out, ms, [hw, hw]

def _ssd_like(sess):
    names = [o.name.lower() for o in sess.get_outputs()]
    if len(names) >= 4:
        return True
    for n in names:
        if "detection_boxes" in n or "detection_scores" in n:
            return True
    return False

def onnx_detect(model, image, labels, conf, iou_thr, size):
    import numpy as np
    import onnxruntime as ort
    from PIL import Image
    so = ort.SessionOptions()
    so.intra_op_num_threads = max(1, min(4, os.cpu_count() or 2))
    sess = ort.InferenceSession(model, sess_options=so, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    shape = list(inp.shape)
    # 两族模型：SSD/TF 风格（多输出、NHWC、图内含预处理与缩放）与 YOLO 系列（单输出 [N,4+类别数]）
    ssd = _ssd_like(sess)
    nhwc = len(shape) == 4 and isinstance(shape[3], int) and shape[3] == 3
    uint8_in = "uint8" in inp.type
    im0 = Image.open(image).convert("RGB")
    ow, oh = im0.size
    fed = [ow, oh]
    if ssd and nhwc:
        h_dim, w_dim = shape[1], shape[2]
        static_hw = isinstance(h_dim, int) and isinstance(w_dim, int) and h_dim > 0 and w_dim > 0
        if static_hw:
            # 固定输入尺寸：外部直接拉伸到该尺寸（不做 letterbox，与 TF-SSD 一致）
            im_in = im0.resize((int(w_dim), int(h_dim)), Image.BILINEAR)
            fed = [int(w_dim), int(h_dim)]
        else:
            # 动态输入尺寸：把原图交给模型，由其图内预处理自行缩放（外部再缩一次会显著掉点）
            im_in = im0
        x = np.asarray(im_in)
        if uint8_in:
            x = x.astype("uint8")
        else:
            x = x.astype("float32") / 255.0
    else:
        hw = int(size) if size and size > 0 else (640 if not ssd else 300)
        if len(shape) == 4:
            if ssd and isinstance(shape[1], int) and shape[1] > 0:
                hw = int(shape[1])
            elif isinstance(shape[2], int) and shape[2] > 0:
                hw = int(shape[2])
        arr = np.asarray(im0).astype("float32") / 255.0
        canvas, r, dx, dy = letterbox(arr, hw)
        x = np.clip(canvas * 255.0, 0.0, 255.0).astype("uint8") if uint8_in else canvas
        fed = [hw, hw]
    if not (ssd and nhwc) and not nhwc:
        x = x.transpose(2, 0, 1)
    x = np.ascontiguousarray(x[None, ...])
    t0 = time.time()
    outs = sess.run(None, {inp.name: x})
    ms = int((time.time() - t0) * 1000)
    items = []
    if ssd:
        o_boxes = o_cls = o_scores = None
        for i, o in enumerate(sess.get_outputs()):
            n = o.name.lower()
            a = np.asarray(outs[i])
            if "detection_boxes" in n:
                o_boxes = a
            elif "detection_classes" in n:
                o_cls = a
            elif "detection_scores" in n:
                o_scores = a
        xyxy = False
        if o_boxes is None or o_cls is None or o_scores is None:
            raw = np.asarray(outs[0])
            if raw.ndim != 4 or raw.shape[-1] < 7:
                fail("不支持的检测输出：%s" % str([list(np.asarray(o).shape) for o in outs]),
                     hint="请使用 SSD 系列（boxes/classes/scores 多输出）或 YOLOv8/YOLO11 系列 ONNX。")
            rows = raw.reshape(-1, raw.shape[-1])
            o_cls, o_scores, o_boxes = rows[:, 1], rows[:, 2], rows[:, 3:7]
            xyxy = True
        b = np.asarray(o_boxes, dtype="float32").reshape(-1, 4)
        c = np.asarray(o_cls, dtype="float32").reshape(-1)
        s = np.asarray(o_scores, dtype="float32").reshape(-1)
        n = min(len(b), len(c), len(s))
        b, c, s = b[:n], c[:n], s[:n]
        if not xyxy:
            b = b[:, [1, 0, 3, 2]]
        if b.size > 0 and float(np.max(b)) > 1.5:
            b = b / float(max(fed))
        keep = s >= float(conf)
        b, c, s = b[keep], c[keep], s[keep]
        ci = np.round(c).astype("int64")
        keep = ci > 0
        b, s, ci = b[keep], s[keep], ci[keep]
        boxes = np.stack([b[:, 0] * ow, b[:, 1] * oh, b[:, 2] * ow, b[:, 3] * oh], axis=1)
        for i in nms(boxes, s, float(iou_thr)):
            k = int(ci[i]) - 1          # SSD 类别 id 为 1-based（0 = 背景）
            lbl = labels[k] if labels and 0 <= k < len(labels) else ("class %d" % int(ci[i]))
            bx1 = max(0.0, min(float(ow), float(boxes[i][0])))
            by1 = max(0.0, min(float(oh), float(boxes[i][1])))
            bx2 = max(0.0, min(float(ow), float(boxes[i][2])))
            by2 = max(0.0, min(float(oh), float(boxes[i][3])))
            items.append({"index": k, "label": lbl, "score": float(s[i]),
                          "box": [int(round(bx1)), int(round(by1)), int(round(bx2)), int(round(by2))]})
    else:
        o = np.asarray(outs[0])
        if o.ndim == 3:
            o = o[0]
        if o.ndim != 2:
            fail("模型输出维度不支持：%s" % str(o.shape),
                 hint="YOLOv8/YOLO11 ONNX 输出应为 [N, 4+类别数]；其他检测模型请用 SSD 系列。")
        if o.shape[0] < o.shape[1]:
            o = o.T
        xywh, sc = o[:, :4], o[:, 4:]
        cls = sc.argmax(1)
        scores = sc.max(1)
        m = scores >= float(conf)
        xywh, cls, scores = xywh[m], cls[m], scores[m]
        if len(xywh) > 0:
            cx, cy, bw, bh = xywh[:, 0], xywh[:, 1], xywh[:, 2], xywh[:, 3]
            boxes = np.stack([cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0], axis=1)
            for i in nms(boxes, scores, float(iou_thr)):
                b = boxes[i]
                x1 = max(0.0, (b[0] - dx) / r)
                y1 = max(0.0, (b[1] - dy) / r)
                x2 = min(float(ow), (b[2] - dx) / r)
                y2 = min(float(oh), (b[3] - dy) / r)
                ci = int(cls[i])
                lbl = labels[ci] if labels and ci < len(labels) else ("class %d" % ci)
                items.append({"index": ci, "label": lbl, "score": float(scores[i]),
                              "box": [int(round(x1)), int(round(y1)), int(round(x2)), int(round(y2))]})
    items = sorted(items, key=lambda d: -d["score"])[:50]
    return {"items": items, "family": ("ssd" if ssd else "yolo")}, ms, fed

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", default="classify")
    ap.add_argument("--model", default="")
    ap.add_argument("--labels", default="")
    ap.add_argument("--topk", type=int, default=5)
    ap.add_argument("--size", type=int, default=0)  # 0 = 自动（分类 224 / 检测 640，优先取模型输入尺寸）
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--iou", type=float, default=0.45)
    ap.add_argument("--normalize", type=int, default=0)
    ap.add_argument("image", nargs="?", default="")
    a = ap.parse_args()

    if not a.image or not os.path.isfile(a.image):
        fail("图片不存在: %s" % a.image)
    if not a.model or not os.path.isfile(a.model):
        fail("模型文件不存在: %s" % a.model)
    try:
        import numpy  # noqa
    except Exception as e:
        fail("缺少 numpy: %s" % e, hint="pkg install -y python-numpy")
    try:
        from PIL import Image  # noqa
    except Exception as e:
        fail("缺少 Pillow: %s" % e, hint="pkg install -y python-pillow")

    labels = read_labels(a.labels)
    low = a.model.lower()
    is_onnx = low.endswith(".onnx")
    backend = "onnxruntime" if is_onnx else "tflite"
    try:
        if a.task == "detect":
            if not is_onnx:
                fail("目标检测仅支持 ONNX 模型",
                     hint="请使用 SSD MobileNetV1 或 YOLOv8 系列（ONNX）；TFLite 检测需模型专属后处理。", backend=backend)
            res, ms, dims = onnx_detect(a.model, a.image, labels, a.conf, a.iou, a.size)
            items = res["items"]
        else:
            if is_onnx:
                out, ms, dims = onnx_classify(a.model, a.image, labels, a.topk, a.size, a.normalize)
            else:
                out, ms, dims = tflite_classify(a.model, a.image, labels, a.topk, a.size)
            items = rank(out, labels, a.topk)
    except Exception as e:
        fail("%s: %s" % (type(e).__name__, e), hint="请确认模型与依赖匹配（.onnx 需 onnxruntime，.tflite 需 tflite_runtime）。")

    jprint({
        "ok": True,
        "task": a.task,
        "backend": backend,
        "model": os.path.basename(a.model),
        "inputSize": dims,
        "labels": len(labels) if labels else 0,
        "ms": ms,
        "items": items,
    })

if __name__ == "__main__":
    main()
""".trimIndent()
    }
}
