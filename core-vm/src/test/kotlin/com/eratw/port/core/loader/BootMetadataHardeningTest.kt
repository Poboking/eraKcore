package com.eratw.port.core.loader

import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimSpec
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * meta 侧车的**硬化判据**（CONTRACT-CMP §3 L02 顺带项：CMP-07 / CMP-83 / CMP-82 两项）。
 *
 * 与 `BootMetadataTest`（往返/编码面）分工：这里只钉「外部输入不可信」的四条边界。
 *
 * 1. **CMP-07 严格 UTF-8**：读侧此前用 `Files.newBufferedReader(UTF_8)`，REPORT 行为
 *    **隐式依赖 `CharsetDecoder` 的默认动作**、失败原因也不进消息；同工程 erbc 侧是显式
 *    REPORT（`StrictUtf8.kt:51-53`，`onMalformedInput(REPORT).onUnmappableCharacter(REPORT)`）。
 *    装机后存储位腐 ⇒ 必须**显式**当场失败，并在消息里指名「严格 UTF-8」。
 *    （实测更正：桌面 JDK 17 的 `newBufferedReader` 走 `cs.newDecoder()`，默认动作是
 *    REPORT 而不是 REPLACE —— CMP-07 原文的「默认 REPLACE」在桌面不成立；ART 侧
 *    `TODO(verify)`。本条修的是「契约显式化 + 失败可诊断」。）
 * 2. **CMP-83 `defaultsInt` 空数组**：`encode` 把非 null 的空 `LongArray` 写成空缺格，
 *    读侧字段整体左移报「载荷意外结束」⇒ 模型 → 文本 → 模型不是互逆的。
 * 3. **CMP-82a `function index` 值域**：index 直通 `FunctionTable.byIndex` 的
 *    `arrayOfNulls(max + 1)`；近 `Int.MAX_VALUE` 时 +1 回绕成负长度 ⇒
 *    `NegativeArraySizeException`（`Error`，装载链的 `catch` 接不住）。
 * 4. **CMP-82b `str-constants` 计数闸**：它是唯一没走 `checkCount` 的段 ⇒ 计数虚报时
 *    报的是「提前结束」而不是其余段一致的「声明 N 条，但后续只剩 M 条」。
 */
class BootMetadataHardeningTest {

    // ------------------------------------------------------------------
    // 1. CMP-07：读侧严格 UTF-8
    // ------------------------------------------------------------------

    @Test
    fun `侧车文件含非法 UTF-8 字节时读失败而不是替换成 U+FFFD`(@TempDir dir: Path) {
        val raw = "漢字の原文"
        val meta = BootMetadata(
            globalDims = listOf(BootMetadata.RawDim(raw, "腐坏.ERH", 1)),
        )
        val bytes = meta.encodeBytes()
        // 把 raw 值里「漢」的首字节（0xE6）改成孤立的 0xFF：严格 UTF-8 下必然非法
        val needle = raw.toByteArray(Charsets.UTF_8)
        val at = indexOf(bytes, needle)
        assertTrue(at >= 0, "夹具自检：编码后的侧车必须逐字含有 raw 原文")
        bytes[at] = 0xFF.toByte()
        val path = dir.resolve("program.meta")
        Files.write(path, bytes)

        val e = assertFailsWith<BootMetaFormatException> {
            BootMetadata.read(path)
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("严格 UTF-8"),
            "读取失败必须指名严格 UTF-8 解码（实际：$message）",
        )
    }

    @Test
    fun `合法 UTF-8 的日文原文读回逐字不变`(@TempDir dir: Path) {
        val raw = "漢字の原文 — 全角，半角"
        val meta = BootMetadata(globalDims = listOf(BootMetadata.RawDim(raw, "正常.ERH", 1)))
        val path = dir.resolve("program.meta")
        Files.write(path, meta.encodeBytes())
        assertEquals(raw, BootMetadata.read(path).globalDims[0].raw, "合法 UTF-8 不得被改写")
    }

    // ------------------------------------------------------------------
    // 2. CMP-83：defaultsInt 空数组（非 null）往返
    // ------------------------------------------------------------------

    @Test
    fun `defaultsInt 空数组非 null 往返仍是空数组而不是字段左移`() {
        val meta = BootMetadata(
            privateDims = listOf(
                BootMetadata.PrivateDim(
                    "FUNC",
                    DimSpec("EMPTY", isStr = false, dims = intArrayOf(2), defaultsInt = LongArray(0)),
                ),
            ),
        )
        val text = meta.encodeText()
        val decoded = BootMetadata.parse(text)
        val got = decoded.privateDims[0].spec.defaultsInt
        assertTrue(got != null, "空数组（非 null）读回后不得变成 null：$text")
        assertEquals(0, got!!.size, "空数组读回后仍是空数组")
        assertEquals(text, decoded.encodeText(), "二次编码必须逐字节闭环")
    }

    @Test
    fun `defaultsInt 为 null 仍写短横线并读回 null`() {
        val meta = BootMetadata(
            privateDims = listOf(
                BootMetadata.PrivateDim("FUNC", DimSpec("NONE", isStr = false, dims = intArrayOf(1), defaultsInt = null)),
            ),
        )
        val text = meta.encodeText()
        assertTrue(
            text.contains("private-dim \"FUNC\" \"NONE\"") && text.contains(" 1 - -\n"),
            "null 仍写短横线、且 defaultsStr 段不被顶掉: $text",
        )
        val got = BootMetadata.parse(text).privateDims[0].spec.defaultsInt
        assertTrue(got == null, "null 必须读回 null")
    }

    // ------------------------------------------------------------------
    // 3. CMP-82a：function index 值域闸
    // ------------------------------------------------------------------

    @Test
    fun `function index 近 Int MAX 被拒绝而不是让 byIndex 分配负长度数组`() {
        val text = BootMetadata(
            functions = listOf(FuncMeta(index = 0, key = "MAIN", name = "MAIN")),
        ).encodeText()
        val bad = text.replaceFirst("function 0 0 ", "function 2147483647 0 ")
        assertTrue(bad != text, "夹具自检：必须改到 function index 字段")
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        assertTrue(
            (e.message ?: "").contains("function index"),
            "拒绝消息必须指名 function index（实际：${e.message}）",
        )
    }

    @Test
    fun `function index 为负被拒绝`() {
        val text = BootMetadata(
            functions = listOf(FuncMeta(index = 0, key = "MAIN", name = "MAIN")),
        ).encodeText()
        val bad = text.replaceFirst("function 0 0 ", "function -1 0 ")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
    }

    @Test
    fun `function index 稀疏但合法（10000）仍可读`() {
        val text = BootMetadata(
            functions = listOf(FuncMeta(index = 10_000, key = "EVENTFIRST", name = "EVENTFIRST", isEvent = true)),
        ).encodeText()
        assertEquals(10_000, BootMetadata.parse(text).functions[0].index, "稀疏 index 是既有合法形态")
    }

    // ------------------------------------------------------------------
    // 4. CMP-82b：str-constants 计数闸（与其余段同一口径）
    // ------------------------------------------------------------------

    @Test
    fun `str-constants 计数虚报被 checkCount 拦下而不是拖到记录结束`() {
        val text = BootMetadata(
            dimParseConfig = DimParseConfig(strConstants = mapOf("A" to "1")),
        ).encodeText()
        val bad = text.replaceFirst("str-constants 1", "str-constants 5")
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        val message = e.message ?: ""
        assertTrue(
            message.contains("声明 5 条") && message.contains("只剩"),
            "必须报与其余段一致的计数口径（实际：$message）",
        )
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
