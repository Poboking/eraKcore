package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * SAVE-06：不足 16 字节的**文本**档不得整函数判 null —— 原版此时回落文本解析。
 *
 * 语义依据（逐条）：
 * - `EraBinaryDataReader.CreateReader` 对 `fs.Length < 16` 返回 null（EraBinaryDataReader.cs:790-793）；
 * - `CheckData` 在 CreateReader 为 null 时按 `EraDataReader` 逐行文本读
 *   （VariableEvaluator.cs:2176-2194）：前两行数字 + 第三行描述的极短文本档是合法存档；
 * - 回落解析失败由 CheckData 的 catch 落 `ETC_ERROR`（VariableEvaluator.cs:2225-2229）；
 *   移植侧同口径为 `SaveFormat.peek` 给 null，由 `SaveDataProbe.checkBytes` 的 null 分支
 *   落成同一 `ETC_ERROR` 状态码；
 * - `LoadFrom` 同构回落（VariableEvaluator.cs:2697-2705），装载侧由
 *   `SaveCodec.isBinary` 的 false 分支承担，不在本文件断言范围。
 *
 * 全部用例经 [TempDir] 合成小档落盘后读回字节，不触碰语料与 fixtures。
 */
class ShortTextSaveTest {

    /** 合成小档：写入临时目录再读回字节（模拟「槽位里就是这几个字节」）。 */
    private fun synthetic(root: Path, name: String, content: ByteArray): ByteArray {
        val f = root.resolve(name)
        Files.write(f, content)
        return Files.readAllBytes(f)
    }

    /** 0 字节：CreateReader 判 null（:790-793），文本回落解析失败给 null（对齐 :2225-2229 的 ETC_ERROR）。 */
    @Test
    fun `零字节档回落文本解析失败`(@TempDir root: Path) {
        val bytes = synthetic(root, "save00.sav", ByteArray(0))
        assertFalse(SaveFormat.isBinaryContainer(bytes), "0 字节判不出二进制容器（:790-793）")
        assertNull(SaveFormat.peek(bytes), "0 字节的文本回落必失败 ⇒ null（:2176-2194 + :2225-2229）")
    }

    /** 10 字节的合法文本档：回落文本解析必须读出头（VariableEvaluator.cs:2178-2194）。 */
    @Test
    fun `十六字节内的合法文本档回落解析出头`(@TempDir root: Path) {
        // "123\n5\n短\n" = 10 字节（UTF-8）：UniqueCode 123 / ScriptVersion 5 / 描述「短」
        val bytes = synthetic(root, "save01.sav", "123\n5\n短\n".toByteArray(Charsets.UTF_8))
        assertFalse(bytes.size >= 16, "夹具必须真的落在 16 字节以下，否则用例失去意义")
        assertFalse(SaveFormat.isBinaryContainer(bytes), "短文本档不是二进制容器（:790-793）")
        val head = SaveFormat.peek(bytes) ?: error("短文本档必须按原版回落文本解析出头")
        assertFalse(head.binary, "文本回落给的头不是二进制容器")
        assertFalse(head.gzip)
        assertEquals(0, head.version, "文本格式没有容器版本（:2409-2414 的三行头）")
        assertEquals(SaveFileKind.NORMAL, head.fileType, "文本头无类别可比（:2176-2194 不比类别）")
        assertEquals(123L, head.uniqueCode)
        assertEquals(5L, head.scriptVersion)
        assertEquals("短", head.text)
    }

    /** 12 字节的不可解析文本档（首行非数字）：回落失败给 null，下游落 ETC_ERROR（:2225-2229）。 */
    @Test
    fun `十六字节内的不可解析文本档回落失败`(@TempDir root: Path) {
        val bytes = synthetic(root, "save02.sav", "abc\ndef\nghi\n".toByteArray(Charsets.UTF_8))
        assertFalse(bytes.size >= 16, "夹具必须真的落在 16 字节以下，否则用例失去意义")
        assertNull(SaveFormat.peek(bytes), "首行非数字的短档文本回落必失败 ⇒ null（:2185-2191 的 toint64 抛出 → :2225-2229）")
    }

    /** 二进制真档的 15 字节截断：判不出容器，文本回落同样失败 ⇒ null。 */
    @Test
    fun `真档十五字节截断回落文本仍失败`(@TempDir root: Path) {
        val full = SaveFormat.write(
            SaveData(SaveFileKind.NORMAL, uniqueCode = 7153L, scriptVersion = 0L, text = "CH20260326 ", compressed = true),
        )
        val cut = synthetic(root, "save03.sav", full.copyOfRange(0, 15))
        assertFalse(SaveFormat.isBinaryContainer(cut), "15 字节判不出二进制容器（:790-793）")
        assertNull(SaveFormat.peek(cut), "截断档文本回落也解析失败 ⇒ null（:2176-2194 + :2225-2229）")
    }
}
