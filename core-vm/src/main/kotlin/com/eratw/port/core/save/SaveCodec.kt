package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableRegistry

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
 */
object SaveCodec {

    /** 二进制容器判定：魔数（`MAGIC_PLAIN` / `MAGIC_GZIP`，EraBinaryDataWriter.cs:39-44）**且**版本 1808。 */
    fun isBinary(bytes: ByteArray): Boolean = SaveFormat.isBinaryContainer(bytes)

    /**
     * @param kind 文本格式没有类别字节，必须由调用方给（见 [SaveTextFormat.read]）
     * @param keepKey 二进制容器里哪些变量名要留下值（见 [SaveFormat.read]）；
     *   生产读档传「当前游戏里存在的键」，旧版本存档里已删除的变量因此不建数组。
     *   文本格式逐行解析、不存在按声明尺寸分配的问题，忽略本参数。
     */
    fun read(
        bytes: ByteArray,
        registry: VariableRegistry,
        kind: SaveFileKind,
        keepKey: ((String) -> Boolean)? = null,
    ): SaveData =
        if (isBinary(bytes)) SaveFormat.read(bytes, keepKey) else SaveTextFormat.read(bytes, registry, kind)

    /** 写：按 [SaveConfig.systemSaveInBinary] 分流；压缩标志已经烘进 [SaveData.compressed]。 */
    fun write(data: SaveData, registry: VariableRegistry, config: SaveConfig): ByteArray =
        if (config.systemSaveInBinary) {
            SaveFormat.write(data)
        } else {
            SaveTextFormat.write(data, registry, config.newline, config.charset, config.writeBom)
        }
}
