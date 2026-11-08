package com.eratw.port.core.update

/**
 * 块级差量（P2-13，U8）三件套的清单读数：`assets-blockdelta-file` / `-size` / `-sha256`。
 *
 * ## 为什么单独一个文件，而不是给 [UpdateManifest] 加三条键
 *
 * `UpdateManifest.kt` 是别的路次正在改的文件（波次契约 §1：清单新键区归 U4）。这条通道要的
 * 只是「**可选组**缺席时整条通道当作不存在」，而 [UpdateManifest] 已经证明了自己对可选组的
 * 处理是「忽略 + 记 warning，绝不拒清单」—— 所以这里用同一条形状判式独立读一遍，收口时由
 * 组长把它并进 `UpdateInfo`（并进之后本文件只剩键名常量与 [parse] 的调用点）。
 *
 * 判式与 `UpdateManifest.readArtifact` **逐字同口径**（文件名走 [UpdatePolicy.isArtifactFileName]、
 * 字节数走同一个 1 … 4 GiB 区间、摘要走 [UpdatePolicy.normalizeSha]），因为两侧不一致会得到
 * 「发布侧写得出、设备侧读不成」的形状：那种差量永远静默回落全量，玩家只是每次都多下几百 MiB，
 * 谁都不会报错 —— 最难查的那一类。
 *
 * ## 老客户端会怎样
 *
 * 不认识这三行 ⇒ 整行进不了它自己的 `values` 表（未知键只进告警），它照旧用
 * `assets-delta-*` 走文件级差量。**新键只加不改**是这条通道能上线的前提。
 */
object BlockDeltaManifest {

    /** 清单键名：与发布侧 `devtools/emit_update.py` 的写出行逐字同值。 */
    const val KEY_FILE: String = "assets-blockdelta-file"
    const val KEY_SIZE: String = "assets-blockdelta-size"
    const val KEY_SHA: String = "assets-blockdelta-sha256"

    /**
     * 一次读取的结果。
     *
     * @param artifact 合法齐全的块级包；缺席或形状不对为 null
     * @param problem 缺席的原因（null = 读到了）；调用方只该拿它记日志，
     *   **不该**因为它而让整条数据通道哑掉 —— 回落方向永远是「多下点字节」
     */
    class Read(val artifact: Artifact?, val problem: String?) {
        val isPresent: Boolean get() = artifact != null
    }

    /** 整组缺席（一个键都没有）时返回 (null, null)：没有差量可谈，不是错误。 */
    fun parse(values: Map<String, String>): Read {
        val keys = listOf(KEY_FILE, KEY_SIZE, KEY_SHA)
        val present = keys.filter { values.containsKey(it) }
        if (present.isEmpty()) return Read(null, null)
        if (present.size != keys.size) {
            return Read(null, "可选组 assets-blockdelta 声明不完整，缺：${(keys - present.toSet()).joinToString(" ")}")
        }
        val fileName = values.getValue(KEY_FILE)
        if (!UpdatePolicy.isArtifactFileName(fileName)) {
            return Read(null, "$KEY_FILE 不是合法文件名（只许 A-Za-z0-9._+-，不许带路径）：$fileName")
        }
        val size = values.getValue(KEY_SIZE).toLongOrNull()
        if (size == null || size <= 0L || size > UpdateManifest.MAX_ARTIFACT_BYTES) {
            return Read(null, "$KEY_SIZE 不在 1 … ${UpdateManifest.MAX_ARTIFACT_BYTES} 区间：${values.getValue(KEY_SIZE)}")
        }
        val sha = UpdatePolicy.normalizeSha(values.getValue(KEY_SHA)) ?: return Read(null, "$KEY_SHA 不是 64 位十六进制：${values.getValue(KEY_SHA)}")
        return Read(Artifact(fileName, size, sha), null)
    }

    /**
     * 清单原文版：按行切「键 值」，同 [UpdateManifest.parse] 的读法（一条一行、空格分隔、
     * 后写的盖掉先写的）。给还没把这三行接进 `UpdateInfo` 的调用点用。
     */
    fun parseManifest(text: String): Read {
        val values = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || !line.contains(' ')) continue
            val key = line.substringBefore(' ')
            if (key in listOf(KEY_FILE, KEY_SIZE, KEY_SHA)) {
                values[key] = line.substringAfter(' ')
            }
        }
        return parse(values)
    }
}
