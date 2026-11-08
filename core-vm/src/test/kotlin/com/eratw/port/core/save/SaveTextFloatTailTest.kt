package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 文本（经典）存档的 **4.981 浮点尾段**（spec: specs/variables.yaml 的 save_note_classic）。
 *
 * fork 在经典正文（60 Int1D + SAVESTR）之后、`__EMUERA_1808_STRAT__` 之前追加：
 * - `dataFloat` 2 个 double，一行一个 `value.ToString("G")`（EraDataStream.cs:686-690）；
 * - `dataFloatArray` 7 个数组，**逐个全写不截尾零**，各自 `__FINISHED` 收尾（:719-727）。
 *
 * 4.980 / 1.824 的经典档没有这段，读侧靠「经典正文后下一行是不是数字」分流
 * （spec 同名条目；证据 VariableData.cs:766-769 写、:792-795 读）。
 */
class SaveTextFloatTailTest {

    private val registry = VariableStore().registry

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun normal(
        code: Long = 1L,
        version: Long = 0L,
        text: String = "",
        characters: List<List<SaveEntry>> = emptyList(),
        variables: List<SaveEntry> = emptyList(),
    ): SaveData = SaveData(
        fileType = SaveFileKind.NORMAL,
        uniqueCode = code,
        scriptVersion = version,
        text = text,
        characters = characters,
        variables = variables,
        compressed = false,
    )

    private fun lines(bytes: ByteArray): List<String> {
        assertTrue(bytes.size >= 3 && bytes[0] == 0xEF.toByte(), "文本档应带 UTF-8 BOM（Config.cs:19）")
        return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8).split('\n')
    }

    /**
     * 手拼一份文本普通档（`VariableEvaluator.cs:2409-2426` 的布局）：
     * 4 行头 + 角色正文 + 61 行空经典数组 + [classicTail] + 标记 + 扩展段。
     */
    private fun assemble(
        charaCount: Int = 0,
        charaBody: List<String> = emptyList(),
        charaSegs: Int = 6,
        classicTail: List<String> = emptyList(),
        marker: String? = SaveTextFormat.EMU_START,
        sysSegs: Int = 8,
        userSegs: Int = 6,
    ): ByteArray {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("1")
        line("0")
        line("old")
        line(charaCount.toString())
        repeat(charaCount) {
            val body = charaBody.ifEmpty {
                listOf(
                    "", "", "0", "0",
                ) + List(17) { SaveTextFormat.FINISHED }
            }
            body.forEach { line(it) }
        }
        repeat(61) { line(SaveTextFormat.FINISHED) }
        classicTail.forEach { line(it) }
        if (marker != null) line(marker)
        repeat(charaCount * charaSegs) { line(SaveTextFormat.SEPARATOR) }
        repeat(sysSegs) { line(SaveTextFormat.SEPARATOR) }
        repeat(userSegs) { line(SaveTextFormat.SEPARATOR) }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // 1. 4.981 行序
    // ------------------------------------------------------------------

    /**
     * 尾段位置：SAVESTR 之后（第 65/66 行标量、67..73 为 7 个 FINISHER），
     * `__EMUERA_1808_STRAT__` 顺延到第 74 行。
     */
    @Test
    fun `4_981 尾段位置在经典正文之后扩展区之前`() {
        val nl = lines(SaveTextFormat.write(normal(text = "d"), registry, locale = Locale.US))

        assertEquals("1", nl[0])
        assertEquals("0", nl[1])
        assertEquals("d", nl[2])
        assertEquals("0", nl[3])
        assertTrue((4..64).all { nl[it] == SaveTextFormat.FINISHED }, "60 Int1D + SAVESTR")

        assertEquals("0", nl[65], "dataFloat[0]（未命名槽，未建模 ⇒ 0）")
        assertEquals("0", nl[66], "dataFloat[1]（RESULTF，IsSavedata=false ⇒ 0）")
        assertTrue((67..73).all { nl[it] == SaveTextFormat.FINISHED }, "7 个系统 dataFloatArray 恒空")
        assertEquals(SaveTextFormat.EMU_START, nl[74])
        assertTrue((75..88).all { nl[it] == SaveTextFormat.SEPARATOR }, "8 系统 + 6 用户扩展段")
        assertEquals("", nl[89])
        assertEquals(90, nl.size)
    }

    /**
     * 角色正文的次序是 str / int / **float** / intArray / strArray / floatArray
     * （`CharacterData.cs:392-408`），且 fork 的 `__COUNT_SAVE_CHARACTER_FLOAT__ = 0`
     * （VariableCode.cs:137）、`__COUNT_SAVE_CHARACTER_FLOAT_ARRAY__ = 0`（:166）
     * ⇒ 即使条目里塞了浮点，也不许出现在角色正文里、不许挪动后续行号。
     */
    @Test
    fun `角色正文次序 float 计数为零不落字节`() {
        val card = listOf(
            SaveEntry.StrValue("NAME", "A"),
            SaveEntry.StrValue("CALLNAME", "B"),
            SaveEntry.IntValue("ISASSI", 1L),
            SaveEntry.IntValue("NO", 0L),
            SaveEntry.IntArray1("CFLAG", longArrayOf(0L, 5L)),
            SaveEntry.FloatValue("RESULTF", 1.5), // 不属经典角色段，必须被忽略
        )
        val nl = lines(SaveTextFormat.write(normal(characters = listOf(card)), registry, locale = Locale.US))

        assertEquals("1", nl[3], "角色数")
        assertEquals("A", nl[4], "NAME")
        assertEquals("B", nl[5], "CALLNAME")
        assertEquals("1", nl[6], "ISASSI（int 在 float 之前）")
        assertEquals("0", nl[7], "NO")
        // 17 个 Int1D 从第 8 行起；CFLAG 是槽 9，带一个非零 ⇒ 2 值 + FINISHER（行 17..19）
        assertEquals(listOf("0", "5", SaveTextFormat.FINISHED), nl.subList(17, 20), "CFLAG 仅截到最后一个非零")
        assertEquals(SaveTextFormat.FINISHED, nl[26], "第 17 个 Int1D 收尾；float 计数 0 不插行")
        assertTrue((27..87).all { nl[it] == SaveTextFormat.FINISHED }, "全局 60 Int1D + SAVESTR")
        assertEquals("0", nl[88], "角色块之后的全局 dataFloat[0]")
        assertEquals("0", nl[89], "全局 dataFloat[1]")
        assertTrue((90..96).all { nl[it] == SaveTextFormat.FINISHED }, "7 个空数组")
        assertEquals(97, nl.indexOf(SaveTextFormat.EMU_START), "7 个空数组 FINISHER 之后才是标记")

        // 读回：角色块按同一次序解析，浮点行没有错位
        val back = SaveTextFormat.read(
            SaveTextFormat.write(normal(characters = listOf(card)), registry, locale = Locale.US),
            registry,
            SaveFileKind.NORMAL,
            Locale.US,
        )
        val block = back.characters.single()
        assertEquals("A", (block.first { it.key == "NAME" } as SaveEntry.StrValue).value)
        assertEquals("B", (block.first { it.key == "CALLNAME" } as SaveEntry.StrValue).value)
        assertEquals(1L, (block.first { it.key == "ISASSI" } as SaveEntry.IntValue).value)
        val cflag = (block.first { it.key == "CFLAG" } as SaveEntry.IntArray1).values
        assertEquals(listOf(0L, 5L), cflag.take(2), "CFLAG 前两格；其余按声明长度补 0")
        assertTrue(cflag.drop(2).all { it == 0L })
    }

    // ------------------------------------------------------------------
    // 2. 往返
    // ------------------------------------------------------------------

    /** 4.981 形状：写 → 读 → 再写，逐字节相同（尾段被正确跳读，不丢不错位）。 */
    @Test
    fun `4_981 写读再写逐字节相同`() {
        val data = normal(
            code = 7153L,
            version = 0L,
            text = "第 1 日",
            characters = listOf(
                listOf(
                    SaveEntry.StrValue("NAME", "灵梦"),
                    SaveEntry.IntValue("NO", 0L),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(0L, 7L)),
                ),
            ),
            variables = listOf(SaveEntry.IntArray1("MONEY", longArrayOf(0L, 0L, 0L, 987654L))),
        )
        val first = SaveTextFormat.write(data, registry, locale = Locale.US)
        val readBack = SaveTextFormat.read(first, registry, SaveFileKind.NORMAL, Locale.US)
        val second = SaveTextFormat.write(readBack, registry, locale = Locale.US)
        assertContentEquals(first, second, "尾段存在时 写→读→写 必须逐字节稳定")

        assertEquals("第 1 日", readBack.text)
        assertEquals(1, readBack.characters.size)
        assertEquals(
            987654L,
            (readBack.variables.first { it.key == "MONEY" } as SaveEntry.IntArray1).values[3],
        )
    }

    // ------------------------------------------------------------------
    // 3. 4.980 老档
    // ------------------------------------------------------------------

    /** 4.980 / 1.824 的经典档没有尾段：标记紧跟 SAVESTR，必须照常读、不能当成「尾段缺项」报错。 */
    @Test
    fun `4_980 无尾段档仍可读`() {
        val old = assemble() // classicTail 为空 ⇒ SAVESTR 下一行就是 EMU_START
        val d = SaveTextFormat.read(old, registry, SaveFileKind.NORMAL, Locale.US)
        assertEquals(1L, d.uniqueCode)
        assertEquals("old", d.text)
        assertTrue(d.variables.any { it.key == "MONEY" }, "经典数组照常进变量表")
        assertTrue(d.characters.isEmpty())

        // 正文后直接 EOF（无扩展区）同样合法（VariableEvaluator.cs:2453-2456）
        val noMarker = assemble(marker = null)
        assertEquals("old", SaveTextFormat.read(noMarker, registry, SaveFileKind.NORMAL, Locale.US).text)
    }

    // ------------------------------------------------------------------
    // 4. 超长 / 截短 / FINISHER 边界
    // ------------------------------------------------------------------

    /**
     * 数组声明长度 0 时原版 `ReadDoubleArray` 对超出长度的行 `continue`、**不做 TryParse**
     * （EraDataStream.cs:124-125）⇒ 值行（即使是非数字）只被消费，FINISHER 即停。
     */
    @Test
    fun `尾段数组超声明长度只跳过不解析`() {
        val tail = listOf("1", "-2") + buildList {
            addAll(listOf("3.25", "bogus", "")) // 第一个数组：声明 0 格，全是「超长」
            add(SaveTextFormat.FINISHED)
            repeat(6) { add(SaveTextFormat.FINISHED) }
        }
        val d = SaveTextFormat.read(assemble(classicTail = tail), registry, SaveFileKind.NORMAL, Locale.US)
        assertEquals("old", d.text)
    }

    /** 7 个数组少一个（第 7 个先撞上标记/EOF）⇒ 必须报错，不许静默错位。 */
    @Test
    fun `尾段数组缺 FINISHER 报错`() {
        val tail = listOf("0", "0") + List(6) { SaveTextFormat.FINISHED } // 只有 6 个
        assertFailsWith<SaveFormatException>("第 7 个数组会一路吃到 EOF") {
            SaveTextFormat.read(assemble(classicTail = tail), registry, SaveFileKind.NORMAL, Locale.US)
        }
    }

    /** 标量截短：只给 1 个 double 就没有下文，同样必须报错。 */
    @Test
    fun `尾段标量截短报错`() {
        val tail = listOf("0") // 1 个标量（应有 2 个）
        assertFailsWith<SaveFormatException> {
            SaveTextFormat.read(assemble(classicTail = tail), registry, SaveFileKind.NORMAL, Locale.US)
        }
    }

    /** 恰好 7 个 FINISHER：边界成立，标记与扩展段继续被正确消费。 */
    @Test
    fun `尾段恰好七个 FINISHER 后继续读扩展区`() {
        val tail = listOf("0", "0") + List(7) { SaveTextFormat.FINISHED }
        val d = SaveTextFormat.read(assemble(classicTail = tail), registry, SaveFileKind.NORMAL, Locale.US)
        assertEquals("old", d.text)
    }

    // ------------------------------------------------------------------
    // 5. .NET G 格式与当前文化
    // ------------------------------------------------------------------

    /**
     * 写侧逐字取 `value.ToString("G")`（EraDataStream.cs:686-690）。期望串由本机
     * dotnet 8.0.425 探针取得：零写 `0`、负零写 `-0`、整数不带 `.0`；十进制指数
     * -4..16 用定点，其余 `E±NN`（至少两位）。
     */
    @Test
    fun `浮点写法对齐 dotnet G 格式`() {
        val cases = listOf(
            0.0 to "0",
            -0.0 to "-0",
            1.0 to "1",
            1.5 to "1.5",
            0.1 + 0.2 to "0.30000000000000004",
            1e-5 to "1E-05",
            1e-4 to "0.0001",
            1e16 to "10000000000000000",
            1e17 to "1E+17",
            1234567890123456.8 to "1234567890123456.8",
            9.9e-5 to "9.9E-05",
            Double.MAX_VALUE to "1.7976931348623157E+308",
        )
        for ((v, expected) in cases) {
            val nl = lines(
                SaveTextFormat.write(
                    normal(variables = listOf(SaveEntry.FloatValue("RESULTF", v))),
                    registry,
                    locale = Locale.US,
                ),
            )
            assertEquals(expected, nl[66], "$v 的 .NET G 写法")
        }
    }

    /** de-DE：小数点用 `,`，指数段不本地化；用同一文化读回不得抛错（TryParse 当前文化，:74-83）。 */
    @Test
    fun `浮点写法与解析跟随当前文化`() {
        val de = Locale.GERMANY
        val nl = lines(
            SaveTextFormat.write(
                normal(variables = listOf(SaveEntry.FloatValue("RESULTF", 1.5))),
                registry,
                locale = de,
            ),
        )
        assertEquals("1,5", nl[66], "de-DE 的小数点")

        val tiny = lines(
            SaveTextFormat.write(
                normal(variables = listOf(SaveEntry.FloatValue("RESULTF", 1.23456789012345e-5))),
                registry,
                locale = de,
            ),
        )
        assertEquals("1,23456789012345E-05", tiny[66], "指数段保持 E-05 形态")

        // 同文化读回：1,5 必须能被解析（否则会在尾段第一行抛「数字不合法」）
        val d = SaveTextFormat.read(assemble(classicTail = listOf("1,5", "-2,25") + List(7) { SaveTextFormat.FINISHED }),
            registry, SaveFileKind.NORMAL, de)
        assertEquals("old", d.text)
    }

    /**
     * 4.980 老档的下一行是标记、不是数字 ⇒ 按「无尾段」分流，不在标记行上报错。
     * 反证：把同一位置换成数字就必须进尾段分支（下面用截短用例证明它真的进了）。
     */
    @Test
    fun `尾段探测只认数字行`() {
        val d = SaveTextFormat.read(assemble(classicTail = emptyList()), registry, SaveFileKind.NORMAL, Locale.US)
        assertEquals("old", d.text)

        assertFailsWith<SaveFormatException>("首行是数字 ⇒ 已进尾段，缺数组 FINISHER 必须报错") {
            SaveTextFormat.read(assemble(classicTail = listOf("0", "0")), registry, SaveFileKind.NORMAL, Locale.US)
        }
    }
}
