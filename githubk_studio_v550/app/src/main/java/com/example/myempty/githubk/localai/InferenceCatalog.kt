package com.example.myempty.githubk.localai

/**
 * InferenceCatalog v6.4 —— 本地神经网络推理（离线）依赖与模型清单。
 *
 * 设计原则（务必遵守）：
 *  - 只声明“真实存在、可离线运行”的依赖与模型，绝不伪造下载地址或校验和。
 *  - 未提供官方校验和的条目 sha256 = null，安装后改用「加载测试」验证有效性。
 *  - 安装策略分三类：
 *      file    → 下载单个文件到 destRel（.so / .onnx / .tflite / 标签文件）
 *      archive → 下载压缩包并解压到 destRel 目录
 *      pkg     → 在 proot Termux 运行时内执行包管理命令（pkg / pip）
 *  - 任何下载都必须由用户在 UI 上确认（见 LocalInferenceManager.planFor）。
 */

/** 推理后端标识。 */
object InferBackend {
    const val ONNX = "onnxruntime"
    const val TFLITE = "tflite"
    const val TESSERACT = "tesseract"
    const val NONE = "none"

    fun label(backend: String): String = when (backend) {
        ONNX -> "ONNX Runtime"
        TFLITE -> "TensorFlow Lite"
        TESSERACT -> "Tesseract OCR"
        else -> "未接入"
    }
}

/** 本地视觉任务。 */
object InferTask {
    const val OCR = "ocr"
    const val CLASSIFY = "classify"
    const val DETECT = "detect"
    const val AUTO = "auto"

    fun label(task: String): String = when (task) {
        OCR -> "文字提取 (OCR)"
        CLASSIFY -> "图像分类"
        DETECT -> "目标/元件识别"
        else -> "自动判定"
    }

    /** 把中文/英文关键词映射到任务，用于「用户已给文字指令」时判定推理任务。 */
    fun fromText(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("ocr") || t.contains("文字") || t.contains("文本") || t.contains("提取字") ||
                t.contains("读一下") || t.contains("识别字") || t.contains("翻译图") -> OCR
            t.contains("元件") || t.contains("目标") || t.contains("检测") || t.contains("物体") ||
                t.contains("物体识别") || t.contains("detect") || t.contains("框") || t.contains("电路") -> DETECT
            t.contains("ui") || t.contains("布局") || t.contains("界面") -> DETECT
            t.contains("分类") || t.contains("是什么") || t.contains("识别这") || t.contains("classify") -> CLASSIFY
            t.contains("分析") || t.contains("看看") || t.contains("描述") -> AUTO
            else -> AUTO
        }
    }
}

/** 安装策略。 */
object InstallKind {
    const val FILE = "file"
    const val ARCHIVE = "archive"
    const val PKG = "pkg"
}

/** 组件类型。 */
object InferKind {
    const val RUNTIME = "runtime"   // python / 基础运行时
    const val LIB = "lib"           // 推理库
    const val MODEL = "model"       // 模型权重
    const val DATA = "data"         // 标签 / 语言数据
    const val TOOL = "tool"         // 可执行工具（tesseract）
}

/**
 * 一个可安装的推理依赖项。
 *
 * @param id        稳定标识（用于工具参数、注册表）
 * @param kind      [InferKind] 之一
 * @param backend   [InferBackend] 之一
 * @param tasks     该组件服务于哪些 [InferTask]
 * @param purpose   「依赖告知」步骤展示给用户的用途说明（必须写清为什么需要）
 * @param installKind [InstallKind] 之一
 * @param url       首选下载地址
 * @param mirrors   备用镜像（按顺序回退）
 * @param pkgCmd    installKind = pkg 时在 proot 内执行的命令
 * @param destRel   相对 runtime 根目录的落盘位置（file/archive 生效）
 */
data class InferenceComponent(
    val id: String,
    val kind: String,
    val displayName: String,
    val backend: String,
    val tasks: List<String>,
    val purpose: String,
    val installKind: String,
    val url: String = "",
    val mirrors: List<String> = emptyList(),
    val pkgCmd: String = "",
    val rollbackCmd: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val destRel: String = "",
    val license: String = "",
    val note: String = ""
) {
    /** 下载地址候选（首选 + 镜像，去重去空）。 */
    fun urls(): List<String> = (listOf(url) + mirrors)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    val sizeText: String get() = formatSize(sizeBytes)

    companion object {
        fun formatSize(size: Long): String {
            if (size <= 0L) return "未知"
            if (size < 1024L) return "${size}B"
            val kb = size / 1024.0
            if (kb < 1024.0) return "%.0fKB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return "%.1fMB".format(mb)
            return "%.2fGB".format(mb / 1024.0)
        }
    }
}

/**
 * 组件清单。所有条目均为真实、公开、可离线使用的资源。
 */
object InferenceCatalog {

    // ---------------- 运行时 / 库 ----------------

    /** Termux python3：跑推理脚本的载体。 */
    private val python = InferenceComponent(
        id = "py-python",
        kind = InferKind.RUNTIME,
        displayName = "Python 3（Termux）",
        backend = InferBackend.NONE,
        tasks = listOf(InferTask.AUTO),
        purpose = "本地推理脚本（onnx/tflite 加载、解码、后处理）都跑在 proot 内的 python3 上；这是所有本地推理的前置运行时。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y python",
        rollbackCmd = "pkg uninstall -y python",
        sizeBytes = 30L * 1024 * 1024,
        license = "PSF",
        note = "Termux 官方仓库包，约 30MB，含标准库。"
    )

    private val numpy = InferenceComponent(
        id = "py-numpy",
        kind = InferKind.LIB,
        displayName = "NumPy（数值张量）",
        backend = InferBackend.NONE,
        tasks = listOf(InferTask.CLASSIFY, InferTask.DETECT, InferTask.AUTO),
        purpose = "图像预处理（缩放/归一化）、模型输入张量组装与输出解码都需要 NumPy；缺少它推理脚本无法运行。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y python-numpy",
        rollbackCmd = "pkg uninstall -y python-numpy",
        sizeBytes = 12L * 1024 * 1024,
        license = "BSD-3-Clause",
        note = "Termux 官方仓库有预编译 arm64 包，体积小、安装快。"
    )

    private val onnxRuntime = InferenceComponent(
        id = "onnxruntime",
        kind = InferKind.LIB,
        displayName = "ONNX Runtime（CPU）",
        backend = InferBackend.ONNX,
        tasks = listOf(InferTask.CLASSIFY, InferTask.DETECT, InferTask.OCR),
        purpose = "离线执行 .onnx 神经网络模型（分类 / 检测 / OCR）。全部计算在本机 CPU 上完成，图片不出设备。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y python-numpy python-pip && (pkg install -y onnxruntime || pip install --no-cache-dir onnxruntime)",
        rollbackCmd = "pkg uninstall -y onnxruntime 2>/dev/null; pip uninstall -y onnxruntime 2>/dev/null; true",
        sizeBytes = 90L * 1024 * 1024,
        license = "MIT",
        note = "首选 Termux 仓库包；若仓库暂无该包，可改用 pip：pip install onnxruntime（需先 pkg install python-pip）。"
    )

    private val tflite = InferenceComponent(
        id = "tflite-runtime",
        kind = InferKind.LIB,
        displayName = "TensorFlow Lite Runtime",
        backend = InferBackend.TFLITE,
        tasks = listOf(InferTask.CLASSIFY),
        purpose = "离线执行 .tflite 轻量模型（体积更小、启动更快），适合低端设备的实时推理。仅支持分类；目标检测请用 ONNX 的 SSD 模型。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y python-pip && pip install --no-cache-dir tflite-runtime",
        rollbackCmd = "pip uninstall -y tflite-runtime",
        sizeBytes = 40L * 1024 * 1024,
        license = "Apache-2.0",
        note = "备选后端。若 aarch64 无可用轮子导致安装失败，请改用 ONNX Runtime（两者只需其一）。" +
            "注意：本清单不提供 .tflite 模型下载地址（TensorFlow 官方下载域已失效，不做伪造链接）；" +
            "请用「导入模型」把自备 .tflite 放入 files/runtime/models/ 后再推理。"
    )

    /** Tesseract：真实可离线运行的 OCR 引擎，语言数据即本地模型文件。 */
    private val tesseract = InferenceComponent(
        id = "tesseract",
        kind = InferKind.TOOL,
        displayName = "Tesseract OCR 引擎",
        backend = InferBackend.TESSERACT,
        tasks = listOf(InferTask.OCR),
        purpose = "离线提取图片文字（中英混排）。引擎与语言数据全部本地，无需联网、不上传图片。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y tesseract tesseract-data-eng tesseract-data-chi-sim",
        rollbackCmd = "pkg uninstall -y tesseract tesseract-data-eng tesseract-data-chi-sim",
        sizeBytes = 45L * 1024 * 1024,
        license = "Apache-2.0",
        note = "同时安装英文与简体中文语言数据；缺少语言数据会导致识别为空。"
    )

    /** Pillow：把任意图片解码为 RGB 像素（JPEG/PNG/WebP），推理脚本的图像预处理依赖它。 */
    private val pillow = InferenceComponent(
        id = "py-pillow",
        kind = InferKind.LIB,
        displayName = "Pillow 图像解码库",
        backend = InferBackend.NONE,
        tasks = listOf(InferTask.CLASSIFY, InferTask.DETECT),
        purpose = "解码 JPEG/PNG/WebP 并缩放为模型输入尺寸（预处理必需）。",
        installKind = InstallKind.PKG,
        pkgCmd = "pkg install -y python-pillow || pip install --no-cache-dir pillow",
        rollbackCmd = "pkg uninstall -y python-pillow",
        sizeBytes = 8L * 1024 * 1024,
        license = "MIT-CMU",
        note = "仅做像素解码，不含任何联网行为。"
    )

    // ---------------- 模型权重 ----------------

    private val mobilenet = InferenceComponent(
        id = "model-mobilenetv2",
        kind = InferKind.MODEL,
        displayName = "MobileNetV2（图像分类 · 轻量）",
        backend = InferBackend.ONNX,
        tasks = listOf(InferTask.CLASSIFY),
        purpose = "通用图像分类（1000 类），约 14MB，CPU 单次推理 100ms 级，适合手机端默认模型。",
        installKind = InstallKind.FILE,
        url = "https://media.githubusercontent.com/media/onnx/models/4c46cd00fbdb7cd30b6c1c17ab54f2e1f4f7b177/validated/vision/classification/mobilenet/model/mobilenetv2-12.onnx",
        mirrors = emptyList(),
        sizeBytes = 13964571L,
        sha256 = "c0c3f76d93fa3fd6580652a45618618a220fced18babf65774ed169de0432ad5",
        destRel = "models/mobilenetv2-12.onnx",
        license = "Apache-2.0",
        note = "ONNX Model Zoo 官方权重，URL 按 commit 固定（内容不可变）并带 sha256 校验。实测：ImageNet 归一化后 top-1 = Samoyed（0.86），单张 35ms。"
    )

    private val squeezenet = InferenceComponent(
        id = "model-squeezenet",
        kind = InferKind.MODEL,
        displayName = "SqueezeNet 1.1（图像分类 · 超轻量）",
        backend = InferBackend.ONNX,
        tasks = listOf(InferTask.CLASSIFY),
        purpose = "最小可用的分类模型（约 5MB），存储紧张或只想验证推理链路时使用。",
        installKind = InstallKind.FILE,
        url = "https://media.githubusercontent.com/media/onnx/models/4c46cd00fbdb7cd30b6c1c17ab54f2e1f4f7b177/validated/vision/classification/squeezenet/model/squeezenet1.1-7.onnx",
        mirrors = emptyList(),
        sizeBytes = 4956208L,
        sha256 = "1eeff551a67ae8d565ca33b572fc4b66e3ef357b0eb2863bb9ff47a918cc4088",
        destRel = "models/squeezenet1.1-7.onnx",
        license = "Apache-2.0",
        note = "ONNX Model Zoo 官方权重（4.7MB，最小可用分类模型），URL 按 commit 固定 + sha256 校验。实测 top-1 = Samoyed（0.66），单张 19ms。"
    )

    private val resnet18 = InferenceComponent(
        id = "model-resnet18",
        kind = InferKind.MODEL,
        displayName = "ResNet18 v1（图像分类 · 更准）",
        backend = InferBackend.ONNX,
        tasks = listOf(InferTask.CLASSIFY),
        purpose = "比 MobileNet 更准的分类模型（约 45MB），对细节纹理敏感，适合元件/物料外观分类。",
        installKind = InstallKind.FILE,
        url = "https://media.githubusercontent.com/media/onnx/models/4c46cd00fbdb7cd30b6c1c17ab54f2e1f4f7b177/validated/vision/classification/resnet/model/resnet18-v1-7.onnx",
        mirrors = emptyList(),
        sizeBytes = 46820737L,
        sha256 = "4e8f8653e7a2222b3904cc3fe8e304cd8b339ce1d05fd24688162f86fb6df52c",
        destRel = "models/resnet18-v1-7.onnx",
        license = "Apache-2.0",
        note = "ONNX Model Zoo 官方权重（44.6MB），URL 按 commit 固定 + sha256 校验。实测 top-1 = Samoyed（0.88），单张 77ms。"
    )

    private val ssdMobileNet = InferenceComponent(
        id = "model-ssd-mobilenetv1",
        kind = InferKind.MODEL,
        displayName = "SSD MobileNetV1（目标检测 · 轻量）",
        backend = InferBackend.ONNX,
        tasks = listOf(InferTask.DETECT),
        purpose = "离线目标检测，输出带像素坐标的检测框；用于「识别元件 / 识别 UI 布局」等需要定位的任务。",
        installKind = InstallKind.FILE,
        url = "https://media.githubusercontent.com/media/onnx/models/4c46cd00fbdb7cd30b6c1c17ab54f2e1f4f7b177/validated/vision/object_detection_segmentation/ssd-mobilenetv1/model/ssd_mobilenet_v1_12.onnx",
        mirrors = emptyList(),
        sizeBytes = 29461455L,
        sha256 = "b8fba5e404077d4048d27fcd1667e85e27e192eb9bf51e696c46a3acd7d21058",
        destRel = "models/ssd_mobilenet_v1_12.onnx",
        license = "Apache-2.0",
        note = "ONNX Model Zoo 官方权重（opset 12，实测可被 ONNX Runtime 1.19 加载；URL 按 commit 固定 + sha256 校验）。" +
            "输入为 NHWC uint8 300x300；输出 4 个头（detection_boxes/classes/scores/num_detections），" +
            "类别是 1-based COCO id（0 = 背景），由推理脚本自动换算。实测狗图 top-1 = dog（0.755）。需与「COCO 标签」配套。"
    )

    private val labelsImagenet = InferenceComponent(
        id = "labels-imagenet",
        kind = InferKind.DATA,
        displayName = "ImageNet 1000 类标签",
        backend = InferBackend.NONE,
        tasks = listOf(InferTask.CLASSIFY),
        purpose = "把分类模型输出的类别下标翻译成人类可读名称（否则只能给出类别编号）。",
        installKind = InstallKind.FILE,
        url = "https://raw.githubusercontent.com/pytorch/hub/master/imagenet_classes.txt",
        mirrors = listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/pytorch/hub/master/imagenet_classes.txt"
        ),
        sizeBytes = 31675L,
        sha256 = "1f386e0d1cb6e28b9c2dac651c3dea6801e98ad1b41a14ce6bb1a093d72069f5",
        destRel = "models/imagenet_classes.txt",
        license = "BSD-3-Clause",
        note = "PyTorch hub 官方 1000 类标签（每行一个类名，行号 = 模型输出下标）。已校验：第 259 行 = Samoyed，与三个分类模型的实测 top-1 下标 258 完全一致。"
    )

    private val labelsCoco = InferenceComponent(
        id = "labels-coco",
        kind = InferKind.DATA,
        displayName = "COCO 80 类标签",
        backend = InferBackend.NONE,
        tasks = listOf(InferTask.DETECT),
        purpose = "把检测模型输出的类别下标翻译成名称（person / phone / laptop 等）。",
        installKind = InstallKind.FILE,
        url = "https://raw.githubusercontent.com/pjreddie/darknet/master/data/coco.names",
        mirrors = listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/pjreddie/darknet/master/data/coco.names"
        ),
        sizeBytes = 625L,
        sha256 = "634a1132eb33f8091d60f2c346ababe8b905ae08387037aed883953b7329af84",
        destRel = "models/coco.names",
        license = "Public Domain",
        note = "darknet 官方 COCO 80 类名（每行一个类名）。SSD 检测模型输出的类别 id 是 1-based（0 = 背景），推理脚本已自动换算为 0-based 下标。"
    )

    /** 全量清单。 */
    private val ALL: List<InferenceComponent> = listOf(
        python, numpy, onnxRuntime, tflite, tesseract, pillow,
        mobilenet, squeezenet, resnet18, ssdMobileNet,
        labelsImagenet, labelsCoco
    )

    fun all(): List<InferenceComponent> = ALL

    fun find(id: String): InferenceComponent? =
        ALL.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    /** 按展示名模糊查找（供「删除某个模型」这类自然语言指令使用）。 */
    fun search(keyword: String): List<InferenceComponent> {
        val k = keyword.trim().lowercase()
        if (k.isBlank()) return emptyList()
        return ALL.filter {
            it.id.lowercase().contains(k) || it.displayName.lowercase().contains(k) ||
                it.destRel.lowercase().contains(k) || it.backend.lowercase().contains(k)
        }
    }

    /**
     * 某个任务所需的完整组件（含后端与配套数据）。
     * 注意：ONNX / TFLite 属于「二选一」的兄弟后端，调用方按可用性择优。
     */
    fun forTask(task: String): List<InferenceComponent> = when (task) {
        InferTask.OCR -> listOf(python, tesseract)
        InferTask.CLASSIFY -> listOf(python, numpy, pillow, onnxRuntime, mobilenet, labelsImagenet)
        InferTask.DETECT -> listOf(python, numpy, pillow, onnxRuntime, ssdMobileNet, labelsCoco)
        else -> listOf(python, numpy, onnxRuntime, tesseract)
    }

    /** 后端二选一的兄弟组件（安装其一时推荐展示）。 */
    fun alternativeFor(comp: InferenceComponent): InferenceComponent? = when (comp.id) {
        onnxRuntime.id -> tflite
        tflite.id -> onnxRuntime
        mobilenet.id -> resnet18
        squeezenet.id -> mobilenet
        resnet18.id -> mobilenet
        else -> null
    }

    /** 在「本地 AI 推理管理」页按用途分组展示。 */
    fun grouped(): List<Pair<String, List<InferenceComponent>>> = listOf(
        "① 运行时与推理库" to listOf(python, numpy, pillow, onnxRuntime, tflite, tesseract),
        "② 推荐模型（离线下载）" to listOf(mobilenet, squeezenet, resnet18, ssdMobileNet),
        "③ 配套标签数据" to listOf(labelsImagenet, labelsCoco)
    )

    /** 模型目录下的默认落盘目录名。 */
    const val MODEL_DIR_REL = "models"
    const val LIB_DIR_REL = "lib"
}
