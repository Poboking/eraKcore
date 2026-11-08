package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T3 验收边界的两条补钉（此前未被显式覆盖）：
 *
 * 1. **文本档扩展区浮点段的特值位往返**：`SaveTextFloatTailTest` 钉了 -0.0，
 *    [SaveTextExtendedSegmentTest] 钉了 -0.0 与普通值，但 `NaN` / `±Infinity` 的
 *    读→写→再读逐位一致没有钉子。写侧 [SaveTextFormat] 对三者的写法分别是
 *    `NaN` / `Infinity` / `-Infinity`（`EraDataStream.cs:686-690`），读侧
 *    `parseDoubleOrNull` 反向识别（`double.TryParse`，`:74-83`）。
 * 2. **未知键丢弃且其余键照常生效**（`save-format.md §5.3`）：文本档用户段按文件序
 *    收下未知名字（[SaveTextFormat.read] 的 `defs = null` 分支），最终由
 *    [SaveData.applyTo] 的查表口径丢弃，同一段里的已知键不受影响
 *    （原版 `VariableData.cs` 的 `TryGetValue` 同口径）。
 *
 * 断言一律走 `Double.toRawBits()`：`==` 对 `NaN` 恒 false、对 `0.0` / `-0.0` 不可分。
 */
class SaveTextFloatSpecialValueTest {

    private val registry = VariableStore().registry

    private fun normal(
        code: Long = 1L,
        version: Long = 0L,
        text: String = "",
        variables: List<SaveEntry> = emptyList(),
    ): SaveData = SaveData(
        fileType = SaveFileKind.NORMAL,
        uniqueCode = code,
        scriptVersion = version,
        text = text,
        variables = variables,
        compressed = false,
    )

    private fun lines(bytes: ByteArray): List<String> {
        assertTrue(bytes.size >= 3 && bytes[0] == 0xEF.toByte(), "文本档应带 UTF-8 BOM（Config.cs:19）")
        return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8).split('\n')
    }

    /**
     * 1D 浮点段：非有限值与 ±0、极值逐个逐位往返，且二次写字节稳定。
     *
     * 尾段截断以「最后一个 `!= 0.0` 项」为界（`EraDataStream.cs:949-966`），本电池末项是
     * 非零的 `Double.MIN_VALUE`，故整段都会落盘，不存在「被截掉看不见」的设备假绿。
     */
    @Test
    fun `文本扩展段浮点特值逐位往返且二次写稳定`() {
        val battery = doubleArrayOf(
            0.0, -0.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.MAX_VALUE, Double.MIN_VALUE,
        )
        val data = normal(text = "d", variables = listOf(SaveEntry.FloatArray1("UF", battery)))

        val first = SaveTextFormat.write(data, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9)
        val written = lines(first)
        val start = written.indexOf("UF")
        assertTrue(start >= 0, "UF 应落在 FLT1 段")
        // 写侧对 NaN / ±Inf 的形态（当前文化；EraDataStream.cs:686-690）
        assertEquals(
            listOf(
                "UF", "0", "-0", "1.5", "NaN", "Infinity", "-Infinity",
                "1.7976931348623157E+308", "4.9E-324", SaveTextFormat.FINISHED,
            ),
            written.subList(start, start + 10),
            "写侧逐值形态",
        )

        val back = SaveTextFormat.read(
            first, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9,
        )
        val arr = (back.variables.single { it.key == "UF" } as SaveEntry.FloatArray1).values
        assertEquals(battery.size, arr.size, "长度前缀由值行数给出，不得增减")
        for (i in battery.indices) {
            assertEquals(battery[i].toRawBits(), arr[i].toRawBits(), "第 $i 项特值位（${battery[i]}）")
        }

        val second = SaveTextFormat.write(
            back, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9,
        )
        assertContentEquals(first, second, "同状态二次写必须逐字节相同")
    }

    /**
     * 未知键丢弃 + 同段已知键照常生效：`SaveTextFormat.read` 对用户段保留未知名，
     * [SaveData.applyTo] 查表丢弃；已知的 `#DIM SAVEDATA UI` 不受未知项影响
     * （`VariableData.cs` 的 TryGetValue 口径；`save-format.md §5.3`）。
     */
    @Test
    fun `未知键丢弃且同段已知键照常生效`() {
        // 手动拼：头 3 行 + 角色 0 + 61 经典数组 + 尾段 + 标记 + 8 系统段 + 9 用户段。
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("1"); line("0"); line("old"); line("0")
        repeat(61) { line(SaveTextFormat.FINISHED) }
        line("0"); line("0")
        repeat(7) { line(SaveTextFormat.FINISHED) }
        line(SaveTextFormat.EMU_START)
        repeat(8) { line(SaveTextFormat.SEPARATOR) }
        // 桶 0 STR1 空；桶 1 INT1 含已知 UI 与未知 GONE；桶 2..8 空。
        line(SaveTextFormat.SEPARATOR)
        line("UI"); line("0"); line("0"); line("42"); line(SaveTextFormat.FINISHED)
        line("GONE"); line("1"); line("2"); line(SaveTextFormat.FINISHED)
        line(SaveTextFormat.SEPARATOR)
        repeat(7) { line(SaveTextFormat.SEPARATOR) }
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)

        val read = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)
        assertTrue(read.variables.any { it.key == "GONE" }, "用户段按文件序收下未知名（read 不丢）")

        val store = VariableStore().apply {
            declareUserVar(
                DimParser.parse("#DIM", "SAVEDATA UI, 4", isPrivate = false, DimParseConfig(systemSaveInBinary = true)),
            )
        }
        val report = read.applyTo(store)
        assertEquals(42L, store.readInt("UI", longArrayOf(2)), "已知键照常生效")
        assertEquals(0L, store.readInt("UI", longArrayOf(3)), "声明更长 ⇒ 尾段补 0（needInit）")
        assertTrue(report.skipped >= 1, "未知键 GONE 计入 skipped（applyTo 丢弃）")
    }
}