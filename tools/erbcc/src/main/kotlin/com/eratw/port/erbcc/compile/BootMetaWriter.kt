package com.eratw.port.erbcc.compile

import com.eratw.port.core.Version
import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path

/** 侧车落盘结果（`metaPath` 的字节数 + SHA-256，供 manifest 之外的对账用）。 */
class BootMetaReport(
    val metaPath: Path,
    val bytes: ByteArray,
) {
    val size: Int get() = bytes.size

    val sha256: String get() = ErbcArtifacts.sha256Hex(bytes)
}

/**
 * `.erbc` 元数据侧车（`program.meta`）的写出端。
 *
 * ## 为什么侧车与容器同级同目录
 *
 * 装载侧拿到的永远是「一个产物文件」，它与侧车必须**成对**：同一个目录、同一个基名、
 * 不同后缀（`program.erbc` + `program.meta`）。这样分发单位仍是「一个目录树」，
 * 且不会出现「两个版本的侧车互相覆盖」。
 *
 * ## 确定性（同一输入两次写出逐字节相同）
 *
 * 文本形态完全由 [BootMetadata] 决定（`BootMetadata.encodeText()` 的规则：Map 按键排序、
 * 函数按 index 排序、表达式树后序不加缓存）。本对象只补两行头部：
 * `engine`（= `erbcc <版本>`）与 `program-sha256`（= 配对的 `.erbc` 字节摘要）——
 * 二者都是输入的函数，不含时间戳、主机名、路径。
 *
 * ## 与 `.erbc` / `manifest.txt` 的关系
 *
 * **不改容器字节、不改 manifest 字节**：侧车是旁路文件，`manifest.txt` 仍只登记
 * `program.erbc` 一条 artifact（既有字节不许变）。配对关系靠侧车自己的
 * `program-sha256` 行表达，读侧可选校验。
 *
 * ## 装载期告警段（M11-E warnmeta）
 *
 * `CorpusCompile` 把 `CompileResult.warnings`（save-15a 的 `LoadWarning(file, line, message)`
 * 清单，符号表重复定义 → 预扫描降级 → 语句层参数告警三段拼序）灌进
 * [BootMetadata.loadWarnings]，经 `encodeText` 落进侧车**段序末尾**（`str-constants` 之后、
 * `end` 之前）的 `load-warnings` 段。写出端语义（编码细节权威在 `BootMetaCodec` KDoc）：
 * - 清单非 null（生产管线恒非 null）⇒ 段必写，零告警也写 `load-warnings 0`；
 * - 清单为 null（旧调用点 / 手构元数据，没有告警通道）⇒ **整段省略**，写出的文本与
 *   引入该段之前逐字节相同——这就是「新写出·旧读不炸」的兼容通道；
 * - 旧侧车（无该段）由新读侧读回 null，`loadWarningsOrEmpty()` 给消费方空清单口径。
 *
 * 本对象不感知段内容：`text`/`write` 全部走 [BootMetadata.encodeText]，
 * 确定性判据不变（同一模型两次写出逐字节相同；告警清单本身保序直存，不排序）。
 */
object BootMetaWriter {

    /** 侧车文件名（与 `program.erbc` 同目录）。 */
    const val FILE_NAME = "program.meta"

    /** 侧车写出端标识（`engine` 行）。 */
    fun engineTag(): String = "erbcc ${Version.VERSION}"

    /**
     * 生成侧车文本（不落盘；测试用它断言确定性）。
     *
     * @param programSha256 配对的 `program.erbc` 的 SHA-256（小写十六进制）；null = 写 `-`
     */
    fun text(
        metadata: BootMetadata,
        programSha256: String? = null,
        engineTag: String = engineTag(),
    ): String = metadata.withHeader(engineTag = engineTag, programSha256 = programSha256).encodeText()

    /**
     * 写侧车（UTF-8、无 BOM、LF）。
     *
     * @param path 目标文件（父目录不存在时创建）
     * @return 实际写出的字节与路径
     */
    fun write(
        path: Path,
        metadata: BootMetadata,
        programSha256: String? = null,
        engineTag: String = engineTag(),
    ): BootMetaReport {
        val text = text(metadata, programSha256, engineTag)
        // encodeText 只产出 LF：原始 CR 一定来自被引号串转义掉的字符串内容，行边界不会被破坏。
        check(!text.contains('\r')) { "侧车文本不得含 CR（LF 换行是行结构的判据）" }
        check(text.endsWith("\n")) { "侧车文本必须以 LF 结尾" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        path.parent?.let { Files.createDirectories(it) }
        // 原子落盘（同 [AtomicWrite]）：半截侧车不能被装载侧当成合法元数据读走。
        AtomicWrite.write(path, bytes)
        return BootMetaReport(path, bytes)
    }
}
