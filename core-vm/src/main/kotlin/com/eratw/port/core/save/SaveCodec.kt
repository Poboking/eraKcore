package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableRegistry
import java.util.Locale

/**
 * 存档字节 ↔ [SaveData] 的分派（二进制容器 vs 文本格式）。
 *
 * 判据 = **魔数命中且容器版本恰为 1808**，即原版 `EraBinaryDataReader.CreateReader`
 * 返回非 null 的条件（EraBinaryDataReader.cs:790-824：魔数 `:796-799`、版本 `:820-824`）。
 * 任一条不满足时原版返回 null，调用方紧接着**按文本读同一份字节**
 * （`VariableEvaluator.cs:2176-2178` 的 CheckData 分支、`:2697-2705` 的 LoadFrom 分支），
 * 本层同口径回落 [SaveTextFormat]，不再自己抛「二进制解析失败」。
 *
 * 与旧口径的差别：旧实现只看魔数，于是「魔数对但版本非 1808」的容器被判成二进制并直接抛
 * [com.eratw.port.core.data.save.SaveFormatException]，原版则当作文本再读一次（多半在
 * 第一行数字处失败 → FileEE → `ETC_ERROR`）。
 *
 * 壳通过但正文损坏（GZip 解压失败 / 条目结构非法）**不**回落文本：原版 `CreateReader`
 * 能解出壳、随后 `LoadFromStreamBinary` 自己抛 FileEE；本层由 [SaveFormat.read] 抛
 * [com.eratw.port.core.data.save.SaveFormatException]，调用方（`FileSystemOps` 的
 * `LOADDATA` / `LOADGLOBAL`）已按「读档失败」收敛，文本回落对二进制头也解析不出任何东西。
 */
object SaveCodec {

    /**
     * 二进制容器判定：魔数（`MAGIC_PLAIN` / `MAGIC_GZIP`，EraBinaryDataWriter.cs:39-44）**且**版本 1808。
     *
     * 只看**容器壳**（[SaveFormat.isContainerShell]），不解压正文：原版 `CreateReader`
     * 返回非 null 的判据就是这两条，正常路径下正文完整性由紧接着的 [read] 负责
     * （解压/解析失败都会落成 [SaveFormatException]，调用方的错误路径与文本回落结局相同）。
     * 旧实现复用 [SaveFormat.isBinaryContainer]（壳 + 全量解压）做分派，于是每次读档
     * 先整份解压一遍判容器、再整份解压一遍解析正文（2026-10-31 玩家「多创几个档后
     * 加载变慢」的读档侧）；存档列表的 `CHKDATA` 另由 [SaveFormat.peekHeader] 的头前缀解压收口。
     */
    fun isBinary(bytes: ByteArray): Boolean = SaveFormat.isContainerShell(bytes)

    /**
     * @param kind 文本格式没有类别字节，必须由调用方给（见 [SaveTextFormat.read]）
     * @param keepKey 二进制容器里哪些变量名要留下值（见 [SaveFormat.read]）；
     *   生产读档传「当前游戏里存在的键」，旧版本存档里已删除的变量因此不建数组。
     *   文本格式逐行解析、不存在按声明尺寸分配的问题，忽略本参数。
     * @param config 只取文本分支的两项：[SaveConfig.textExtensionSegments]（扩展区段集，
     *   二进制容器自带版本头，不受它影响）与 [SaveConfig.locale]（`null` = 现取
     *   `Locale.getDefault()`，与旧行为一致）。
     */
    fun read(
        bytes: ByteArray,
        registry: VariableRegistry,
        kind: SaveFileKind,
        keepKey: ((String) -> Boolean)? = null,
        config: SaveConfig = SaveConfig.DEFAULT,
    ): SaveData =
        if (isBinary(bytes)) {
            SaveFormat.read(bytes, keepKey)
        } else {
            SaveTextFormat.read(
                bytes, registry, kind,
                locale = localeOf(config), segments = config.textExtensionSegments,
            )
        }

    /** 写：按 [SaveConfig.systemSaveInBinary] 分流；压缩标志已经烘进 [SaveData.compressed]。 */
    fun write(data: SaveData, registry: VariableRegistry, config: SaveConfig): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeTo(data, registry, config, out)
        return out.toByteArray()
    }

    /**
     * 流式写（OOM-B 波，2026-11-08）：二进制档由 [SaveFormat.writeTo] 直接写 [out]；
     * 文本档没有流式编码器（[SaveTextFormat.write] 只回 ByteArray），仍物化后一次写入。
     */
    fun writeTo(
        data: SaveData,
        registry: VariableRegistry,
        config: SaveConfig,
        out: java.io.OutputStream,
    ) {
        if (config.systemSaveInBinary) {
            SaveFormat.writeTo(data, out)
        } else {
            out.write(
                SaveTextFormat.write(
                    data, registry, config.newline, config.charset, config.writeBom,
                    localeOf(config), config.textExtensionSegments,
                ),
            )
        }
    }

    /** 「文化没配 ⇒ 每次调用现取 `Locale.getDefault()`」的落点，理由见 [SaveConfig.locale]。 */
    private fun localeOf(config: SaveConfig): Locale = config.locale ?: Locale.getDefault()
}
