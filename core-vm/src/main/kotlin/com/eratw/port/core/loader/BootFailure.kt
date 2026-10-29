package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcFormatException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/**
 * 开机装载失败的分支（宿主按它挑屏幕文案，本类不生成排版）。
 *
 * 四个分支对应真机唯一会看到的四种装载失败，判据全部来自装载层已经抛出的异常，
 * 不额外解析产物：
 *
 * | 分支 | 触发 | 宿主文案（既有句子） |
 * |---|---|---|
 * | [HEAP_TOO_SMALL] | `OutOfMemoryError`（含 cause 链）或装载前堆预检不足 | 「启动失败：堆不足（…）」= [BootFailures.HEAP_SHORTAGE_TEXT] |
 * | [MISSING_ARTIFACT] | `program.erbc` / `program.meta` 不存在 | 「缺少 <产物目录> 下的 program.erbc / program.meta」 |
 * | [CORRUPT_ARTIFACT] | 容器或侧车格式坏（magic / 版本 / 段表 / 段长 / 计数 / 侧车语法） | 「启动失败：<装载器原话>」 |
 * | [MAPPING_FAILED] | 文件在但打不开或映射不了（占用 / 权限 / 地址空间） | 「启动失败：<装载器原话>」 |
 *
 * 文案来源是装载层既有句子（`打开失败` / `读取失败` / `映射 [a,b) 失败`，见
 * [MappedErbc.open]、[MappedErbc.mapWindow]、[ProgramLoader] 与 [BootMetadata.read]），
 * 本类只决定走哪一条，不新增措辞。堆不足是唯一一条**不是**装载器原话的分支：
 * 它的句子固定为 [BootFailures.HEAP_SHORTAGE_TEXT]，不把 `Java heap space` 这类原话落屏。
 */
enum class BootFailureKind {
    /**
     * 堆不够（`OutOfMemoryError` 或 [ProgramLoader.estimateHeap] 的预检不足）。
     *
     * **前置**：判定次序在缺失/文件系统/格式之前 —— `OutOfMemoryError` 的 cause 链里
     * 可能挂着装载器的包装异常，但观众要看的只有「堆不足」这一句。
     */
    HEAP_TOO_SMALL,

    /** 产物文件不存在（装载前二次确认或打开时 `NoSuchFileException`）。 */
    MISSING_ARTIFACT,

    /** 产物存在但容器/侧车格式坏（magic、版本、段表、段长、计数、侧车文本）。 */
    CORRUPT_ARTIFACT,

    /** 文件存在、格式未必坏，但打不开/映射不了（被占用、权限、地址空间不足）。 */
    MAPPING_FAILED,

    /** 其余运行期错误（脚本 `CodeEE`、装配期 `require` 等），按原有「启动失败：<消息>」口径。 */
    OTHER,
}

/**
 * [BootFailures.classify] 的结果。
 *
 * @param kind 分派依据。
 * @param detail 装载器给出的可读原文（一般带产物路径）；[BootFailureKind.MISSING_ARTIFACT]
 *   时是缺失文件路径，[BootFailureKind.HEAP_TOO_SMALL] 时固定为
 *   [BootFailures.HEAP_SHORTAGE_TEXT]。宿主只在非缺失分支把它接到「启动失败：」后面。
 */
data class BootFailure(
    val kind: BootFailureKind,
    val detail: String?,
)

/**
 * 把装载/运行期异常分类到 [BootFailureKind]，供宿主选文案。
 *
 * 判定次序是「堆 > 缺失 > 文件系统 > 格式 > 其它」：`OutOfMemoryError` 单独先扫一遍
 * （见 [BootFailureKind.HEAP_TOO_SMALL]），随后装载器把 IO 异常包成
 * [ErbcLoadException]（格式族）时，`cause` 链里的 [NoSuchFileException] /
 * [FileSystemException] 才是真正原因，不能因为外层是格式异常就误报「段损坏」。
 * 只沿 `cause` 链走有界层数，畸形 cause 成环也不会自旋。
 *
 * 本类不读文件、不碰 Android API，宿主与纯 JVM 测试共用同一判断。
 */
object BootFailures {

    /** `cause` 链最多看这么多层（正常链路 ≤ 4：包装 → IO → …）。 */
    private const val MAX_CAUSE_DEPTH = 16

    /**
     * 堆不足的唯一文案（`OutOfMemoryError` 与装载前预检共用；宿主只加「启动失败：」标题）。
     *
     * 机器容量口径来自实测读数（`ProgramLoader.estimatedHeapBytes` 的标定表）：
     * 全量产物 `program.erbc` ≈ 316 MiB + `program.meta` ≈ 47 MiB，段 3 惰性化之后
     * **解码内部实时高水位**实测 342.6 MiB、装载后 post-GC 常驻 +100.5 MiB；
     * 旧口径「装载后常驻 628.6 MiB」是整池解码时代的读数，512 MB 堆在那时才必 OOM
     * （M5-BOOT/M8-DEVICE-BOOT 真机实测）。走到这句说明连惰性档的高水位都没装下，
     * 所以仍按机型档位提示。`largeHeap` 已在 `app/src/main/AndroidManifest.xml:6` 打开。
     */
    const val HEAP_SHORTAGE_TEXT =
        "堆不足（全量装载的解码峰值约需 350–420 MiB；largeHeap 已开，仍不足请用 ≥6 GB RAM 机型）"

    /** 分类入口。永远返回非 null；无法归类的进 [BootFailureKind.OTHER]。 */
    fun classify(t: Throwable): BootFailure {
        // 前置：堆不足先于一切（OOM 的 cause 链里可能挂着装载器包装或 FileSystemException）。
        var probe: Throwable? = t
        var probeDepth = 0
        while (probe != null && probeDepth < MAX_CAUSE_DEPTH) {
            if (probe is OutOfMemoryError) {
                return BootFailure(BootFailureKind.HEAP_TOO_SMALL, HEAP_SHORTAGE_TEXT)
            }
            probe = probe.cause
            probeDepth++
        }

        var node: Throwable? = t
        var depth = 0
        var missing: NoSuchFileException? = null
        var fileSystem: FileSystemException? = null
        var format: Throwable? = null
        while (node != null && depth < MAX_CAUSE_DEPTH) {
            val n = node
            if (missing == null && n is NoSuchFileException) missing = n
            if (fileSystem == null && n is FileSystemException) fileSystem = n
            if (format == null && (n is ErbcFormatException || n is BootMetaFormatException)) format = n
            node = n.cause
            depth++
        }

        if (missing != null) {
            return BootFailure(BootFailureKind.MISSING_ARTIFACT, missing.file ?: messageOf(missing))
        }
        if (fileSystem != null) {
            // 外层若是装载器包装（ErbcLoadException），它已经带了产物路径与「打开失败 / 映射 … 失败」；
            // 裸 FileSystemException 才按 ProgramLoader.fileBytes 的「读取失败（类型: 原因）」口径补全。
            val detail = (t as? ErbcFormatException)?.message?.takeIf { it.isNotBlank() }
                ?: fileSystemDetail(fileSystem)
            return BootFailure(BootFailureKind.MAPPING_FAILED, detail)
        }
        if (format != null) {
            val detail = (t as? ErbcFormatException)?.message?.takeIf { it.isNotBlank() }
                ?: (t as? BootMetaFormatException)?.message?.takeIf { it.isNotBlank() }
                ?: messageOf(format)
            return BootFailure(BootFailureKind.CORRUPT_ARTIFACT, detail)
        }
        return BootFailure(BootFailureKind.OTHER, messageOf(t))
    }

    /** 与 [ProgramLoader] 的 `读取失败（类型: 原因）` 同一措辞，补上出事的路径。 */
    private fun fileSystemDetail(e: FileSystemException): String {
        val reason = e.reason?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: e.javaClass.simpleName
        val path = e.file?.takeIf { it.isNotBlank() }
        val body = "读取失败（${e.javaClass.simpleName}: $reason）"
        return if (path == null) body else "$path: $body"
    }

    private fun messageOf(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
