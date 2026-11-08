package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 文本档**扩展区**的 4.981 九段口径（spec: specs/variables.yaml 的 save_note_classic，
 * 开关见 [TextExtensionSegments]）。
 *
 * fork 的角色扩展段从 6 段变 9 段（`CharacterData.cs:437-503`：浮点标量 / 1D / 2D 各插在同类型
 * 之后），用户变量从 6 桶变 9 桶（`VariableData.cs:858-876` 普通档、`:1080-1101` 全局档；
 * 桶号 = `(Dimension - 1) * 3 + kindIdx`，浮点落 **2 / 5 / 8**）；**系统扩展段两边都还是 8 段**
 * （fork `VariableData.cs:798-856` 没给系统区加浮点，系统浮点走经典尾段，见
 * [SaveTextFloatTailTest]）。
 *
 * 断言一律**逐行列出期望**（照 [SaveTextFloatTailTest] 的钉子），而不是数分隔符个数 ——
 * 段数一错就整块错位，只有行序能证明「读到的就是写进去的那一段」。
 */
class SaveTextExtendedSegmentTest {

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

    private fun globalSave(variables: List<SaveEntry>): SaveData = SaveData(
        fileType = SaveFileKind.GLOBAL,
        uniqueCode = 1L,
        scriptVersion = 0L,
        text = "",
        variables = variables,
        compressed = false,
    )

    private fun lines(bytes: ByteArray): List<String> {
        assertTrue(bytes.size >= 3 && bytes[0] == 0xEF.toByte(), "文本档应带 UTF-8 BOM（Config.cs:19）")
        return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8).split('\n')
    }

    /** 一个角色正文 = 2 String + 2 Int + 17 个空 Int1D = 21 行；扩展区之前的经典部分共 96 行。 */
    private val charaBlock = listOf(
        SaveEntry.StrValue("NAME", "A"),
        SaveEntry.StrValue("CALLNAME", "B"),
        SaveEntry.IntValue("ISASSI", 0L),
        SaveEntry.IntValue("NO", 0L),
        SaveEntry.StrValue("NICKNAME", "n"),
    )

    /** 与 [SaveTextFormat.write] 同形的普通档：可扩展段在标记行**之后**、按角色逐块重复。 */
    private fun assembleNormal(
        charaCount: Int = 0,
        charaExt: List<String> = List(6) { S },
        sysExt: List<String> = List(8) { S },
        userExt: List<String> = List(6) { S },
        floatTail: Boolean = true,
        marker: String = SaveTextFormat.EMU_START,
    ): ByteArray {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("1"); line("0"); line("old"); line(charaCount.toString())
        repeat(charaCount) {
            listOf("", "", "0", "0").forEach { line(it) }
            repeat(17) { line(F) }
        }
        repeat(61) { line(F) }
        if (floatTail) {
            line("0"); line("0")
            repeat(7) { line(F) }
        }
        line(marker)
        repeat(charaCount) { charaExt.forEach { line(it) } }
        sysExt.forEach { line(it) }
        userExt.forEach { line(it) }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** 全局档正文只有 GLOBAL / GLOBALS 两个数组（fork `VariableData.cs:1060-1066`，没有浮点尾段）。 */
    private fun assembleGlobal(marker: String, userExt: List<String>): ByteArray =
        (listOf("1", "0", F, F, marker) + userExt).joinToString("\n", postfix = "\n")
            .toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------------
    // 1. 写侧的九段行序
    // ------------------------------------------------------------------

    /**
     * 4.981 普通档：角色 9 段 + 系统 8 段 + 用户 9 段，浮点段就插在同类型之后
     * （`CharacterData.cs:437-503` + `VariableData.cs:858-876`）。
     */
    @Test
    fun `FLOAT_9 普通档逐行结构`() {
        val data = normal(
            text = "d",
            characters = listOf(charaBlock),
            variables = listOf(
                SaveEntry.StrArray1("US", arrayOf(null, "x")),
                SaveEntry.IntArray1("UI", longArrayOf(0, 0, 7)),
                SaveEntry.FloatArray1("UF", doubleArrayOf(0.0, 1.5, 0.0, -2.25, 0.0)),
            ),
        )
        val nl = lines(
            SaveTextFormat.write(data, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9),
        )

        assertEquals(SaveTextFormat.EMU_START, nl[95], "标记行在经典浮点尾段之后")

        assertEquals(
            listOf(
                "NICKNAME:n", S, // STR0
                S, // INT0
                S, // FLT0（fork 没有带 SAVE_EXTENDED 的系统浮点 ⇒ 空段）
                S, S, S, // STR1 / INT1 / FLT1
                S, S, S, // STR2 / INT2 / FLT2
            ),
            nl.subList(96, 106), "角色扩展 9 段（每类型后一记浮点段）",
        )
        assertTrue((106..113).all { nl[it] == S }, "系统扩展仍按 8 段")

        assertEquals(
            listOf(
                "US", "", "x", F, S, // 桶 0 STR1
                "UI", "0", "0", "7", F, S, // 桶 1 INT1
                "UF", "0", "1.5", "0", "-2.25", F, S, // 桶 2 FLT1（截到最后一个非零项 = 下标 3）
                S, // 桶 3 STR2
                S, // 桶 4 INT2
                S, // 桶 5 FLT2
                S, // 桶 6 STR3
                S, // 桶 7 INT3
                S, // 桶 8 FLT3
                "", // 文件尾（写侧以换行收尾）
            ),
            nl.subList(114, nl.size), "用户扩展 9 段的段序与形状",
        )
        assertEquals(139, nl.size, "总行数")
    }

    /**
     * 默认段集一个字节都不许多写：角色 6 段 / 用户 6 段，且浮点条目**照旧不进档**。
     *
     * 「带浮点条目」与「摘掉浮点条目」的产物逐字节相同 —— 这就是改动前写侧的等价式
     * （改动前 `kindOf` 对四种浮点条目返回 null，用户段过滤器因此永远收不到它们）。
     */
    @Test
    fun `CLASSIC_6 产物与改动前逐字节一致`() {
        val floatless = listOf(
            SaveEntry.StrArray1("US", arrayOf(null, "x")),
            SaveEntry.IntArray1("UI", longArrayOf(0, 0, 7)),
        )
        val withFloat = floatless + SaveEntry.FloatArray1("UF", doubleArrayOf(0.0, 1.5, 0.0, -2.25, 0.0))
        val classic = SaveTextFormat.write(
            normal(text = "d", characters = listOf(charaBlock), variables = withFloat),
            registry, locale = Locale.US,
        )
        assertContentEquals(
            SaveTextFormat.write(
                normal(text = "d", characters = listOf(charaBlock), variables = floatless),
                registry, locale = Locale.US,
            ),
            classic,
            "CLASSIC_6 不落浮点条目（4.980 没有承载它的桶）",
        )

        val nl = lines(classic)
        assertEquals(SaveTextFormat.EMU_START, nl[95])
        assertEquals(listOf("NICKNAME:n", S, S, S, S, S, S), nl.subList(96, 103), "角色 6 段")
        assertTrue((103..110).all { nl[it] == S }, "系统 8 段")
        assertEquals(
            listOf(
                "US", "", "x", F, S,
                "UI", "0", "0", "7", F, S,
                S, S, S, S, // STR2 / INT2 / STR3 / INT3
                "",
            ),
            nl.subList(111, nl.size), "用户 6 段",
        )
        assertEquals(127, nl.size, "总行数")
        assertTrue(nl.none { it == "UF" }, "UF 不许出现在 CLASSIC_6 产物里")
    }

    /** 全局档同样按段集分 6 / 9 段（4.980 `VariableData.cs:3818` 与 fork `:1080-1101`）。 */
    @Test
    fun `全局档用户扩展 6 段与 9 段`() {
        val data = globalSave(listOf(SaveEntry.FloatArray1("GF", doubleArrayOf(2.0))))
        val nine = lines(
            SaveTextFormat.write(data, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9),
        )
        assertEquals(SaveTextFormat.EMU_START, nine[4], "全局档：2 行头 + 2 行数组 + 标记（没有浮点尾段）")
        assertEquals(
            listOf(S, S, "GF", "2", F, S, S, S, S, S, S, S, ""),
            nine.subList(5, nine.size), "桶 0 / 1 空，桶 2 是浮点，其后 6 段空",
        )
        assertEquals(18, nine.size)

        val six = lines(SaveTextFormat.write(data, registry, locale = Locale.US))
        assertEquals(List(6) { S } + "", six.subList(5, six.size), "CLASSIC_6 全局档只写 6 段")
        assertTrue(six.none { it == "GF" }, "4.980 段集没有浮点桶")
        assertEquals(12, six.size)
    }

    // ------------------------------------------------------------------
    // 2. 读侧的九段与旧档兼容
    // ------------------------------------------------------------------

    /** 4.981 档写出来能被同段集读回（含负零的位形），且**再写一次逐字节相同**。 */
    @Test
    fun `FLOAT_9 往返全字段相等且二次写字节稳定`() {
        val data = normal(
            text = "d",
            characters = listOf(charaBlock),
            variables = listOf(
                SaveEntry.StrArray1("US", arrayOf(null, "x")),
                SaveEntry.IntArray1("UI", longArrayOf(0, 0, 7)),
                SaveEntry.FloatArray1("UF", doubleArrayOf(1.5, -0.0, -2.25)),
                SaveEntry.IntArray2("U2", 2, 2, longArrayOf(0, 3, 4, 0)),
            ),
        )
        val first = SaveTextFormat.write(data, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9)
        val back = SaveTextFormat.read(first, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)

        assertEquals("d", back.text)
        assertEquals("n", (back.characters.single().single { it.key == "NICKNAME" } as SaveEntry.StrValue).value)

        fun entry(key: String): SaveEntry = back.variables.single { it.key == key }
        assertContentEquals(
            listOf("", "x"),
            (entry("US") as SaveEntry.StrArray1).values.map { it ?: "" },
            "STR1",
        )
        assertContentEquals(longArrayOf(0, 0, 7), (entry("UI") as SaveEntry.IntArray1).values, "INT1")
        val uf = (entry("UF") as SaveEntry.FloatArray1).values
        assertContentEquals(doubleArrayOf(1.5, -0.0, -2.25), uf, "FLT1")
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(uf[1]),
            "负零不许被读成正零（档里就是 `-0` 行）",
        )
        val u2 = entry("U2") as SaveEntry.IntArray2
        assertEquals(2, u2.dim0); assertEquals(2, u2.dim1)
        assertContentEquals(longArrayOf(0, 3, 4, 0), u2.values, "INT2")

        val second = SaveTextFormat.write(back, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9)
        assertContentEquals(first, second, "同一份档二次写逐字节相同")
    }

    /** 旧档（4.980：无浮点尾段 + 角色 6 段 + 用户 6 段）照旧读得回来。 */
    @Test
    fun `4_980 旧档仍可读`() {
        val bytes = assembleNormal(
            charaCount = 1,
            floatTail = false,
            userExt = listOf(S, "UI", "0", "0", "7", F, S, S, S, S, S),
        )
        val d = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, Locale.US)
        assertEquals("old", d.text, "经典正文之后直接是标记 ⇒ 判无尾段（VariableData.cs:766-769 那段不存在）")
        assertContentEquals(
            longArrayOf(0, 0, 7),
            (d.variables.single { it.key == "UI" } as SaveEntry.IntArray1).values,
        )
    }

    /**
     * 段集声明错的后果：
     * - 空浮点段被当 4.980 读 ⇒ 尾部多出的分隔符只是不被读（原版 `LoadFromStreamExtended`
     *   读完自己的段就返回，不看文件尾）⇒ 不报错；
     * - **有内容**的浮点段被当 4.980 读 ⇒ 它落进 STR2 槽，而字符串多维段只认「直接一个分隔符」
     *   （`EraDataReader.cs:398-418`）⇒ 当场抛，不会静默错位。
     */
    @Test
    fun `段集声明错时要么忽略尾段要么报错`() {
        val nineEmpty = assembleNormal(charaCount = 1, charaExt = List(9) { S }, userExt = List(9) { S })
        assertEquals(
            1L,
            SaveTextFormat.read(nineEmpty, registry, SaveFileKind.NORMAL, Locale.US).uniqueCode,
            "全空的 9 段档用默认段集读不报错（多余分隔符不被消费）",
        )

        val nineWithData = assembleNormal(
            charaCount = 0,
            userExt = listOf(S, S, "UF", "1.5", F, S, S, S, S, S, S, S),
        )
        val e = assertFailsWith<SaveFormatException> {
            SaveTextFormat.read(nineWithData, registry, SaveFileKind.NORMAL, Locale.US)
        }
        assertTrue(e.message!!.contains("字符串多维"), "实际文案：${e.message}")

        // 反向：4.980 段集的档被当 9 段读 ⇒ 缺 3 个分隔符，撞文件尾
        val sixData = assembleNormal(charaCount = 0, userExt = List(6) { S })
        assertFailsWith<SaveFormatException> {
            SaveTextFormat.read(sixData, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)
        }
    }

    /**
     * 角色区的浮点段在 fork 里**没有变量可落**（`VariableCode.cs:96-202` 的 19 个
     * `SAVE_EXTENDED` 全是 `__INTEGER__` / `__STRING__` ⇒ `GetExtSaveList(Float, *)` 返回空表）。
     * 所以段里有值也只会被吃掉、不进条目 —— 照 `VariableData.cs:892-931` 的查表赋值口径。
     */
    @Test
    fun `角色浮点段按空段读写且内容被丢弃`() {
        val bytes = assembleNormal(
            charaCount = 1,
            charaExt = listOf(
                "NICKNAME:n", S, // STR0
                S, // INT0
                "RESULTF:2.5", S, // FLT0：真 fork 不会写，写了也必须被消费
                S, S, S, // STR1 / INT1 / FLT1
                S, S, S, // STR2 / INT2 / FLT2
            ),
            userExt = List(9) { S },
        )
        val d = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)
        assertEquals("old", d.text)
        val block = d.characters.single()
        assertEquals("n", (block.single { it.key == "NICKNAME" } as SaveEntry.StrValue).value, "STR0 段读对了才轮得到 FLT0")
        assertTrue(block.none { it.key == "RESULTF" }, "FLT0 段的值不落进条目")
        assertTrue(d.variables.none { it is SaveEntry.FloatArray1 }, "用户 9 段全空")
    }

    // ------------------------------------------------------------------
    // 3. 浮点段的取值规则
    // ------------------------------------------------------------------

    /**
     * `WriteExtended(key, double[])`（`EraDataStream.cs:949-966`）与整数段同构，但判据是
     * `!= 0.0`：截到最后一个非零项、全零连键行都不写；IEEE 下 `-0.0 == 0.0` ⇒ 负零不算非零。
     */
    @Test
    fun `浮点 1D 段的零规则`() {
        fun write1d(values: DoubleArray): List<String> = lines(
            SaveTextFormat.write(
                normal(variables = listOf(SaveEntry.FloatArray1("UF", values))),
                registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9,
            ),
        )

        // 全零（含负零）⇒ 段里只剩分隔符
        val zero = write1d(doubleArrayOf(0.0, -0.0))
        assertEquals(-1, zero.indexOf("UF"), "全零不许落键行")
        assertContentEquals(write1d(DoubleArray(0)), zero, "全零段的字节与空数组一致")
        assertContentEquals(write1d(doubleArrayOf(0.0, 0.0)), zero)

        // 中间零保留、尾部零截掉
        val mid = write1d(doubleArrayOf(1.5, 0.0, -2.25, 0.0, 0.0))
        val start = mid.indexOf("UF")
        assertTrue(start >= 0, "键行应在 FLT1 段")
        assertEquals(listOf("UF", "1.5", "0", "-2.25", F, S), mid.subList(start, start + 6), "截到最后一个非零项")

        // 负零落在中间且后面还有非零 ⇒ 落 `-0` 行（G 格式对 -0.0 的形态，EraDataStream.cs:686-690）
        val negZero = write1d(doubleArrayOf(1.5, -0.0, 2.0))
        assertEquals(listOf("UF", "1.5", "-0", "2", F, S), negZero.subList(negZero.indexOf("UF"), negZero.indexOf("UF") + 6))
    }

    /** 2D / 3D 浮点段与整数段同构（`EraDataStream.cs:968-1008` / `:1011-1067`），只把值行换成 G 格式。 */
    @Test
    fun `浮点 2D 与 3D 段的行形态`() {
        val data = normal(
            variables = listOf(
                SaveEntry.FloatArray2("U2", 2, 3, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.25)),
                SaveEntry.FloatArray3("U3", 2, 1, 2, doubleArrayOf(0.0, 1.25, 0.0, 0.5)),
            ),
        )
        val bytes = SaveTextFormat.write(data, registry, locale = Locale.US, segments = TextExtensionSegments.FLOAT_9)
        val nl = lines(bytes)
        val i2 = nl.indexOf("U2")
        assertEquals(listOf("U2", "", "0,0,-1.25", F, S), nl.subList(i2, i2 + 5), "2D：整行零落空行")
        val i3 = nl.indexOf("U3")
        assertEquals(listOf("U3", "0{", "0,1.25", "}", "1{", "0,0.5", "}", F, S), nl.subList(i3, i3 + 9), "3D：每平面 i{ … }")

        val back = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)
        val r2 = back.variables.single { it.key == "U2" } as SaveEntry.FloatArray2
        assertEquals(2, r2.dim0); assertEquals(3, r2.dim1)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.25), r2.values)
        val r3 = back.variables.single { it.key == "U3" } as SaveEntry.FloatArray3
        assertEquals(2, r3.dim0); assertEquals(1, r3.dim1); assertEquals(2, r3.dim2)
        assertContentEquals(doubleArrayOf(0.0, 1.25, 0.0, 0.5), r3.values)
    }

    /**
     * 浮点行的小数点跟随文化：`ToString("G")` / `TryParse` 都不带 InvariantCulture
     * （`EraDataStream.cs:686-690` / `:74-83`）。
     *
     * 顺带钉住「读侧文化必须与写侧一致」这件事的实际后果，且它是 **PC 同行为**：
     * de 写的 `1,5` 用 US 读 ⇒ 逗号被当分组符吃掉 ⇒ **15**（dotnet 8.0.425 实测
     * `double.TryParse("1,5")` 在 en-US 下 ok=true、值 15，因为该重载默认带
     * `NumberStyles.AllowThousands`；`<work-root>/_g8_evidence/probe-g8.txt`）。
     */
    @Test
    fun `de 文化写 1,5 且文化错读会顶到分组符口径`() {
        val de = Locale.GERMANY
        val data = normal(variables = listOf(SaveEntry.FloatArray1("UF", doubleArrayOf(1.5, 2.25))))
        val bytes = SaveTextFormat.write(data, registry, locale = de, segments = TextExtensionSegments.FLOAT_9)
        val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        val nl = text.split('\n')
        val start = nl.indexOf("UF")
        assertEquals(listOf("UF", "1,5", "2,25", F, S), nl.subList(start, start + 5), "de-DE 出逗号小数点")

        val same = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, de, TextExtensionSegments.FLOAT_9)
        assertContentEquals(
            doubleArrayOf(1.5, 2.25),
            (same.variables.single { it.key == "UF" } as SaveEntry.FloatArray1).values,
        )
        val crossed = SaveTextFormat.read(bytes, registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9)
        assertContentEquals(
            doubleArrayOf(15.0, 225.0),
            (crossed.variables.single { it.key == "UF" } as SaveEntry.FloatArray1).values,
            "文化错读时 `1,5` 的分隔符被当分组符吃掉 ⇒ 15",
        )
    }

    // ------------------------------------------------------------------
    // 4. 档位门槛
    // ------------------------------------------------------------------

    /**
     * 全局档不看档位就调用用户段 reader（`VariableEvaluator.cs:2563-2566` → fork
     * `VariableData.cs:1103-1113`），但 2D / 3D 仍受 `emu_version` 早退门槛：
     * `ReadDoubleArray2DExtended` `< 1708` 直接返回（`EraDataStream.cs:539-540`）、
     * `ReadDoubleArray3DExtended` `< 1729`（`:584-585`），而 0 维 / 1 维的
     * `ReadDoubleExtended`（:257）与 `ReadDoubleArrayExtended`（:499）**没有**早退分支
     * ⇒ 1700 档也要消费 FLT1 段的那个分隔符。
     */
    @Test
    fun `全局档按档位消费浮点段`() {
        fun read(marker: String, segs: Int) = SaveTextFormat.read(
            assembleGlobal(marker, List(segs) { S }),
            registry, SaveFileKind.GLOBAL, Locale.US, TextExtensionSegments.FLOAT_9,
        )
        read("__EMUERA_STRAT__", 3) // 1700：STR1 / INT1 / FLT1
        assertFailsWith<SaveFormatException> { read("__EMUERA_STRAT__", 2) }
        read("__EMUERA_1708_STRAT__", 6) // 1708：再加 STR2 / INT2 / FLT2
        assertFailsWith<SaveFormatException> { read("__EMUERA_1708_STRAT__", 5) }
        read("__EMUERA_1729_STRAT__", 9) // 1729：9 段齐全
        read(SaveTextFormat.EMU_START, 9)
        assertFailsWith<SaveFormatException> { read(SaveTextFormat.EMU_START, 8) }
    }

    /** 1803 以下的角色扩展只有 4 段（fork 的 Old1802 reader 也没浮点段，`CharacterData.cs:569-599`）。 */
    @Test
    fun `低档位角色段仍是 4 段`() {
        val d = SaveTextFormat.read(
            assembleNormal(
                charaCount = 1,
                charaExt = List(4) { S },
                sysExt = List(4) { S },
                userExt = List(4) { S },
                floatTail = false,
                marker = "__EMUERA_STRAT__",
            ),
            registry, SaveFileKind.NORMAL, Locale.US, TextExtensionSegments.FLOAT_9,
        )
        assertEquals("old", d.text, "1700 档：段集开关不许把角色区读成 9 段")
        assertEquals(1L, d.uniqueCode)
    }

    // ------------------------------------------------------------------
    // 5. 配置透传与端到端
    // ------------------------------------------------------------------

    /** [SaveConfig.textExtensionSegments] 与 [SaveConfig.locale] 必须真的落到编解码，否则开关是死的。 */
    @Test
    fun `SaveConfig 的段集与文化透传到 SaveCodec`() {
        val store = VariableStore().apply {
            addHero()
            declareUf()
        }
        store.writeFloat("UF", longArrayOf(1), 1.5)
        store.writeFloat("UF", longArrayOf(2), -2.25)
        val data = SaveCapture.normal(store, "d", compressed = false)

        val off = SaveCodec.write(data, store.registry, SaveConfig(systemSaveInBinary = false, locale = Locale.US))
        assertTrue(String(off, StandardCharsets.UTF_8).lineSequence().none { it == "UF" }, "默认段集不落浮点用户变量")

        val onCfg = SaveConfig(
            systemSaveInBinary = false, locale = Locale.US,
            textExtensionSegments = TextExtensionSegments.FLOAT_9,
        )
        val nl = lines(SaveCodec.write(data, store.registry, onCfg))
        val start = nl.indexOf("UF")
        assertTrue(start >= 0, "FLOAT_9 应落 UF")
        assertEquals(listOf("UF", "0", "1.5", "-2.25", F, S), nl.subList(start, start + 6))

        // `locale = null`（没配）= 每次现取 Locale.getDefault()，与显式传当前文化逐字节相同
        val implicit = SaveCodec.write(
            data, store.registry,
            SaveConfig(systemSaveInBinary = false, textExtensionSegments = TextExtensionSegments.FLOAT_9),
        )
        val explicit = SaveCodec.write(
            data, store.registry,
            SaveConfig(
                systemSaveInBinary = false, locale = Locale.getDefault(),
                textExtensionSegments = TextExtensionSegments.FLOAT_9,
            ),
        )
        assertContentEquals(explicit, implicit, "locale = null 必须等价于现取 Locale.getDefault()")

        // 读侧吃同一个开关
        val back = SaveCodec.read(SaveCodec.write(data, store.registry, onCfg), store.registry, SaveFileKind.NORMAL, config = onCfg)
        assertContentEquals(
            doubleArrayOf(0.0, 1.5, -2.25),
            (back.variables.single { it.key == "UF" } as SaveEntry.FloatArray1).values,
        )
    }

    /**
     * 端到端：`#DIMF SAVEDATA` 在 4.981 段集下**存档 ↔ 变量池**逐项相等，
     * 4.980 段集下不报错但浮点不进档（与改动前一致）。二次写逐字节相同。
     */
    @Test
    fun `真变量池往返带 DIMF SAVEDATA`() {
        for (segments in listOf(TextExtensionSegments.CLASSIC_6, TextExtensionSegments.FLOAT_9)) {
            val cfg = SaveConfig(
                systemSaveInBinary = false, zipSaveData = false, useSaveFolder = true,
                locale = Locale.US, textExtensionSegments = segments,
            )
            val src = filledStore()
            val io = MemorySaveIo()
            assertTrue(FileSystemOps(src, io, cfg).saveData(3, "描述"), "$segments 写档应成功")
            val bytes = io.files.getValue("sav/save03.sav")

            val dst = filledStore()
            FileSystemOps(dst, io, cfg).loadData(3)

            assertEquals(42L, dst.readInt("DAY", longArrayOf(0)), "$segments DAY")
            assertEquals("文本", dst.readStr("SAVESTR", longArrayOf(0)), "$segments SAVESTR")
            assertEquals(7L, dst.readInt("UI", longArrayOf(2)), "$segments UI")
            assertEquals("乙", dst.readStr("US", longArrayOf(1)), "$segments US")
            assertEquals("描述", dst.lastLoadText, "$segments SAVEDATA_TEXT")
            if (segments == TextExtensionSegments.FLOAT_9) {
                assertEquals(1.5, dst.readFloat("UF", longArrayOf(0)), 0.0, "$segments UF[0]")
                assertEquals(0.0, dst.readFloat("UF", longArrayOf(1)), 0.0, "$segments UF[1]")
                assertEquals(-2.25, dst.readFloat("UF", longArrayOf(2)), 0.0, "$segments UF[2]")
            } else {
                assertEquals(0.0, dst.readFloat("UF", longArrayOf(0)), 0.0, "4.980 段集不承载浮点")
                assertEquals(-2.25, src.readFloat("UF", longArrayOf(2)), 0.0, "源池自己得是非零")
            }

            assertTrue(FileSystemOps(dst, io, cfg).saveData(3, "描述"))
            assertContentEquals(bytes, io.files.getValue("sav/save03.sav"), "$segments 二次写应逐字节相同")
        }
    }

    /**
     * 全局档同一条路：`#DIMF GLOBAL SAVEDATA` 的浮点在 FLOAT_9 下经 SAVEGLOBAL / LOADGLOBAL
     * 回到变量池（读侧的 floatSink 接线在 [FileSystemOps.loadGlobal]），CLASSIC_6 下不落。
     */
    @Test
    fun `真变量池往返带 GLOBAL SAVEDATA 的浮点`() {
        for (segments in listOf(TextExtensionSegments.CLASSIC_6, TextExtensionSegments.FLOAT_9)) {
            val cfg = SaveConfig(
                systemSaveInBinary = false, useSaveFolder = true,
                locale = Locale.US, textExtensionSegments = segments,
            )
            val io = MemorySaveIo()
            val src = VariableStore().apply {
                declare("#DIMF", "SAVEDATA GLOBAL GF, 2")
                writeFloat("GF", longArrayOf(1), -0.75)
            }
            FileSystemOps(src, io, cfg).saveGlobal()
            val bytes = io.files.getValue("sav/global.sav")

            val dst = VariableStore().apply { declare("#DIMF", "SAVEDATA GLOBAL GF, 2") }
            assertTrue(FileSystemOps(dst, io, cfg).loadGlobal(), "$segments LOADGLOBAL 应成功")
            assertEquals(
                if (segments == TextExtensionSegments.FLOAT_9) -0.75 else 0.0,
                dst.readFloat("GF", longArrayOf(1)),
                0.0,
                "$segments GF[1]",
            )
            if (segments == TextExtensionSegments.FLOAT_9) {
                val nl = lines(bytes)
                val start = nl.indexOf("GF")
                assertEquals(listOf("GF", "0", "-0.75", F, S), nl.subList(start, start + 5), "落在桶 2")
            } else {
                assertTrue(lines(bytes).none { it == "GF" }, "4.980 段集没有浮点桶")
            }
        }
    }

    // ------------------------------------------------------------------
    // 装置（续）
    // ------------------------------------------------------------------

    /** 一个角色 + 三种用户变量（int 1D / str 1D / **float 1D**），浮点值是 `1.5 / 0 / -2.25`。 */
    private fun filledStore(): VariableStore = VariableStore().apply {
        addHero()
        declare("#DIM", "SAVEDATA UI, 3")
        declare("#DIMS", "SAVEDATA US, 2")
        declareUf()
        writeInt("DAY", longArrayOf(0), 42L)
        writeStr("SAVESTR", longArrayOf(0), "文本")
        writeInt("UI", longArrayOf(2), 7L)
        writeStr("US", longArrayOf(1), "乙")
        writeFloat("UF", longArrayOf(0), 1.5)
        writeFloat("UF", longArrayOf(2), -2.25)
    }

    private fun VariableStore.declare(keyword: String, body: String) {
        declareUserVar(DimParser.parse(keyword, body, isPrivate = false, DimParseConfig(systemSaveInBinary = false)))
    }

    private fun VariableStore.declareUf() = declare("#DIMF", "SAVEDATA UF, 3")

    private fun VariableStore.addHero(name: String = "アリス"): Int = addCharacter(
        CharacterTemplate(no = 1, name = name, callname = name, maxbase = mapOf(0 to 10L)),
    )

    /** 内存 [SaveFileIo]（与同目录其余用例同形）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path) != null
    }

    private companion object {
        /** `__EMU_SEPARATOR__` 的短名，把期望写成一行。 */
        const val S = SaveTextFormat.SEPARATOR

        /** `__FINISHED` 的短名。 */
        const val F = SaveTextFormat.FINISHED
    }
}
