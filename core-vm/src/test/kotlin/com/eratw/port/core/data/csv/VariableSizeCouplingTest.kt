package com.eratw.port.core.data.csv

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableSizes
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `VariableSize.csv` 全语义（VARSIZE-FULL）：
 * ConstantData.changeVariableSizeData（ConstantData.cs:309-551）+
 * decideActualArraySize（:553-670）。
 *
 * 覆盖：1 维既有行为、ITEMPRICE/ITEMNAME 与 STR 特例、23 组主数组↔名字表联动
 * （契约 16 组 + C# 调用清单其余 7 组）、2 维/3 维与上限、重复定义、未知/禁改、
 * 以及真实语料（`ERATW_CORPUS`，缺省 assume-skip）全行零告警。
 */
class VariableSizeCouplingTest {

    // ------------------------------------------------------------------
    // 合成数据辅助
    // ------------------------------------------------------------------

    private fun loadText(text: String): Pair<VariableSizes, List<String>> {
        val dir = createTempDirectory("eratw-varsize")
        try {
            dir.resolve("VariableSize.csv").writeText(text)
            val sizes = VariableSizes()
            val warnings = ArrayList<String>()
            VariableSizeLoader.load(dir, sizes, warnings)
            return sizes to warnings
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 主数组名 → 读取其 1 维尺寸。 */
    private data class Coupled(
        val main: String,
        val name: String,
        val readMain: (VariableSizes) -> Int,
        val nameSlot: Int,
    )

    /** 契约点名的 16 组（ABL…STR）。 */
    private val contract16 = listOf(
        Coupled("ABL", "ABLNAME", { it.charaInt1D[2] }, 0),      // ABL 槽 2 / ABLNAME 槽 0
        Coupled("TALENT", "TALENTNAME", { it.charaInt1D[3] }, 2),
        Coupled("EXP", "EXPNAME", { it.charaInt1D[4] }, 1),
        Coupled("MARK", "MARKNAME", { it.charaInt1D[5] }, 5),
        Coupled("BASE", "BASENAME", { it.charaInt1D[0] }, 7),
        Coupled("SOURCE", "SOURCENAME", { it.charaInt1D[7] }, 8),
        Coupled("EX", "EXNAME", { it.charaInt1D[8] }, 9),
        Coupled("EQUIP", "EQUIPNAME", { it.charaInt1D[12] }, 11),
        Coupled("TEQUIP", "TEQUIPNAME", { it.charaInt1D[13] }, 12),
        Coupled("FLAG", "FLAGNAME", { it.int1D[3] }, 13),
        Coupled("TFLAG", "TFLAGNAME", { it.int1D[4] }, 14),
        Coupled("CFLAG", "CFLAGNAME", { it.charaInt1D[9] }, 15),
        Coupled("TCVAR", "TCVARNAME", { it.charaInt1D[20] }, 16),
        Coupled("CSTR", "CSTRNAME", { it.charaStr1D[0] }, 17),
        Coupled("STAIN", "STAINNAME", { it.charaInt1D[14] }, 18),
        Coupled("STR", "STRNAME", { it.str1D[1] }, 21),
    )

    /** C# decideActualArraySize 调用清单里其余 7 组（ConstantData.cs:600-607）。 */
    private val extra7 = listOf(
        Coupled("TSTR", "TSTRNAME", { it.str1D[6] }, 22),
        Coupled("SAVESTR", "SAVESTRNAME", { it.str1D[0] }, 23),
        Coupled("GLOBAL", "GLOBALNAME", { it.int1D[63] }, 24),
        Coupled("GLOBALS", "GLOBALSNAME", { it.str1D[5] }, 25),
        Coupled("DAY", "DAYNAME", { it.int1D[0] }, 26),
        Coupled("TIME", "TIMENAME", { it.int1D[22] }, 27), // TIME 的 code 低 16 位是 22，不是 20
        Coupled("MONEY", "MONEYNAME", { it.int1D[1] }, 28),
    )

    // ------------------------------------------------------------------
    // 1 维既有行为与特例
    // ------------------------------------------------------------------

    @Test
    fun `既有 setter 与 1 维装载行为不变`() {
        val sizes = VariableSizes()
        sizes.setInt1D("ITEM", 2000)
        assertEquals(2000, sizes.int1D[2])
        sizes.setStr1D("SAVESTR", 150)
        assertEquals(150, sizes.str1D[0])
        assertEquals(setOf(VariableCode.ITEM, VariableCode.SAVESTR), sizes.changed)
        // RANDDATA 仍禁改（ConstantData.cs:328-332）
        assertFailsWith<CodeEE> { sizes.setInt1D("RANDDATA", 625) }
        assertEquals(625, sizes.int1D[64])
        // 字符串变量不能走整数 setter（既有 API 面）
        assertFailsWith<CodeEE> { sizes.setInt1D("STR", 100) }

        val (loaded, warnings) = loadText("ITEM,2000\nSAVESTR,150\nRESULTS,200\n")
        assertEquals(2000, loaded.int1D[2])
        assertEquals(150, loaded.str1D[0])
        assertEquals(200, loaded.str1D[2])
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `ITEMPRICE 与 ITEMNAME 双向同步`() {
        val (a, wa) = loadText("ITEMNAME,1500\n")
        assertEquals(1500, a.maxDataList[6])
        assertEquals(1500, a.int1D[60])
        assertTrue(wa.isEmpty(), wa.toString())

        val (b, wb) = loadText("ITEMPRICE,1600\n")
        assertEquals(1600, b.maxDataList[6])
        assertEquals(1600, b.int1D[60])
        assertTrue(wb.isEmpty(), wb.toString())

        // 同一文件先 ITEMNAME 后 ITEMPRICE：两行都落地，后者覆盖（两个不同 code，不算重复）
        val (c, wc) = loadText("ITEMNAME,1500\nITEMPRICE,1700\n")
        assertEquals(1700, c.maxDataList[6])
        assertEquals(1700, c.int1D[60])
        assertTrue(wc.isEmpty(), wc.toString())
    }

    @Test
    fun `STR 同时改字符串表与 STRNAME 名字表`() {
        val (s, w) = loadText("STR,30000\n")
        assertEquals(30000, s.str1D[1])
        assertEquals(30000, s.maxDataList[10])
        assertEquals(30000, s.maxDataList[21]) // 只改主数组 → STRNAME 跟随（:577-580）
        assertTrue(w.isEmpty(), w.toString())
    }

    // ------------------------------------------------------------------
    // 23 组联动
    // ------------------------------------------------------------------

    @Test
    fun `16 组主数组与名字表联动`() {
        for (spec in contract16) {
            // 只改主数组 → 名字表跟随（C# :577-580）
            val (mainOnly, w1) = loadText("${spec.main},200\n")
            assertEquals(200, spec.readMain(mainOnly), "${spec.main} 主数组")
            assertEquals(200, mainOnly.maxDataList[spec.nameSlot], "${spec.name} 名字表")
            assertTrue(w1.isEmpty(), "${spec.main}: $w1")

            // 只改名字表 → 主数组跟随（C# :573-576）
            val (nameOnly, w2) = loadText("${spec.name},210\n")
            assertEquals(210, spec.readMain(nameOnly), "${spec.main} 跟随名字表")
            assertEquals(210, nameOnly.maxDataList[spec.nameSlot])
            assertTrue(w2.isEmpty(), "${spec.name}: $w2")

            // 两边都改且相等 → 不告警
            val (same, w3) = loadText("${spec.main},220\n${spec.name},220\n")
            assertEquals(220, spec.readMain(same), "${spec.main} 同尺寸")
            assertEquals(220, same.maxDataList[spec.nameSlot], "${spec.name} 同尺寸")
            assertTrue(w3.isEmpty(), "${spec.main}/${spec.name} 同尺寸: $w3")

            // 两边都改且不等 → 双方取 max 并告警（C# :557-571）
            val (diff, w4) = loadText("${spec.main},230\n${spec.name},240\n")
            assertEquals(240, spec.readMain(diff), "${spec.main} 取 max")
            assertEquals(240, diff.maxDataList[spec.nameSlot], "${spec.name} 取 max")
            assertTrue(
                w4.any { it.contains(spec.main) && it.contains(spec.name) && it.contains("最大值") },
                "${spec.main}/${spec.name} 冲突告警: $w4",
            )
        }
    }

    @Test
    fun `C 调用清单其余 7 组联动`() {
        for (spec in extra7) {
            val (mainOnly, w1) = loadText("${spec.main},300\n")
            assertEquals(300, spec.readMain(mainOnly), "${spec.main} 主数组")
            assertEquals(300, mainOnly.maxDataList[spec.nameSlot], "${spec.name} 名字表")
            assertTrue(w1.isEmpty(), "${spec.main}: $w1")

            val (nameOnly, w2) = loadText("${spec.name},310\n")
            assertEquals(310, spec.readMain(nameOnly), "${spec.main} 跟随名字表")
            assertEquals(310, nameOnly.maxDataList[spec.nameSlot])
            assertTrue(w2.isEmpty(), "${spec.name}: $w2")
        }
    }

    @Test
    fun `PALAM JUEL 与 PALAMNAME 专例`() {
        // 两个主数组都在 changed → n=max(250,300)=300，名字表取 max(280,300)
        val (s, w) = loadText("PALAM,250\nJUEL,300\nPALAMNAME,280\n")
        assertEquals(300, s.maxDataList[3])
        assertEquals(250, s.charaInt1D[6]) // 不等于 n，保持原值（C# 原样）
        assertEquals(300, s.charaInt1D[10])
        assertTrue(w.any { it.contains("PALAM") }, w.toString())

        // 只改 PALAMNAME 且小于默认 JUEL(200) → PALAM 跟随名字表、名字表被 JUEL 顶大
        val (t, w2) = loadText("PALAMNAME,150\n")
        assertEquals(150, t.charaInt1D[6])
        assertEquals(200, t.maxDataList[3])
        assertTrue(w2.any { it.contains("PALAMNAME") }, w2.toString())
    }

    @Test
    fun `CDFLAG 与 CDFLAGNAME1 2 专例`() {
        // 只改 CDFLAG → CDFLAGNAME1/2 槽跟随
        val (a, wa) = loadText("CDFLAG,100,120\n")
        assertEquals(100, a.maxDataList[19])
        assertEquals(120, a.maxDataList[20])
        assertContentEquals(intArrayOf(100, 120), a.charaInt2D[0])
        assertTrue(wa.isEmpty(), wa.toString())

        // 只改名字 → CDFLAG 2 维跟随
        val (b, wb) = loadText("CDFLAGNAME1,100\nCDFLAGNAME2,120\n")
        assertContentEquals(intArrayOf(100, 120), b.charaInt2D[0])
        assertTrue(wb.isEmpty(), wb.toString())

        // 两边都改且不一致 → C# 抛 CodeEE，本移植告警并保留 CDFLAG 侧
        val (c, wc) = loadText("CDFLAG,100,120\nCDFLAGNAME1,100\nCDFLAGNAME2,130\n")
        assertContentEquals(intArrayOf(100, 120), c.charaInt2D[0])
        assertTrue(wc.any { it.contains("CDFLAG") && it.contains("不一致") }, wc.toString())
    }

    // ------------------------------------------------------------------
    // 2 维 / 3 维
    // ------------------------------------------------------------------

    @Test
    fun `2 维 语料六项解析与写入`() {
        val (s, w) = loadText(
            "DITEMTYPE,50,60\nDA,51,61\nDB,52,62\nDC,53,63\nDD,54,64\nDE,55,65\n",
        )
        assertTrue(w.isEmpty(), w.toString())
        for (i in 0..5) {
            assertEquals(50 + i, s.int2D[i][0], "int2D[$i][0]")
            assertEquals(60 + i, s.int2D[i][1], "int2D[$i][1]")
        }
    }

    @Test
    fun `2 维上限与参数错误`() {
        // 每维 1..1,000,000，总元素 ≤1,000,000（ConstantData.cs:396-410）
        val (ok, okW) = loadText("DA,1000,1000\n")
        assertContentEquals(intArrayOf(1000, 1000), ok.int2D[1])
        assertTrue(okW.isEmpty(), okW.toString())

        val (overTotal, w1) = loadText("DA,1001,1000\n")
        assertContentEquals(intArrayOf(100, 100), overTotal.int2D[1])
        assertTrue(w1.any { it.contains("1000000") }, w1.toString())

        val (overDim, w2) = loadText("DA,1000001,1\n")
        assertContentEquals(intArrayOf(100, 100), overDim.int2D[1])
        assertTrue(w2.any { it.contains("1000000") }, w2.toString())

        val (missing, w3) = loadText("DA,100\n")
        assertTrue(w3.any { it.contains("缺少第 2 个尺寸") }, w3.toString())

        val (badSecond, w4) = loadText("DA,100,abc\n")
        assertTrue(w4.any { it.contains("第 2 个尺寸非整数") }, w4.toString())

        val (zero, w5) = loadText("DA,0,100\n")
        // ConstantData.cs:342-345：0 一律 ArrayLengthIs0 拒绝（禁用要写负值）
        assertTrue(w5.any { it.contains("无法为 0") }, w5.toString())

        val (extra, w6) = loadText("DA,120,130,5\n")
        assertContentEquals(intArrayOf(120, 130), extra.int2D[1])
        assertTrue(w6.any { it.contains("忽略") }, w6.toString())
    }

    @Test
    fun `3 维解析与 10M 上限`() {
        val (s, w) = loadText("TA,10,20,30\n")
        assertContentEquals(intArrayOf(10, 20, 30), s.int3D[0])
        assertTrue(w.isEmpty(), w.toString())

        // 3 维总元素上限 10,000,000（ConstantData.cs:443-447）
        val (edge, edgeW) = loadText("TB,1000,1000,10\n")
        assertContentEquals(intArrayOf(1000, 1000, 10), edge.int3D[1])
        assertTrue(edgeW.isEmpty(), edgeW.toString())

        val (over, w1) = loadText("TA,101,100,1000\n")
        assertContentEquals(intArrayOf(100, 100, 100), over.int3D[0])
        assertTrue(w1.any { it.contains("10000000") }, w1.toString())

        val (overDim, w2) = loadText("TA,1000001,1,1\n")
        assertContentEquals(intArrayOf(100, 100, 100), overDim.int3D[0])
        assertTrue(w2.any { it.contains("1000000") }, w2.toString())

        val (missing, w3) = loadText("TA,10,20\n")
        assertTrue(w3.any { it.contains("缺少第 3 个尺寸") }, w3.toString())

        val (badThird, w4) = loadText("TA,10,20,abc\n")
        assertTrue(w4.any { it.contains("第 3 个尺寸非整数") }, w4.toString())

        val (extra, w5) = loadText("TA,10,20,30,40\n")
        assertContentEquals(intArrayOf(10, 20, 30), extra.int3D[0])
        assertTrue(w5.any { it.contains("忽略") }, w5.toString())
    }

    // ------------------------------------------------------------------
    // 告警面
    // ------------------------------------------------------------------

    @Test
    fun `重复定义取最后一次并告警`() {
        val (s, w) = loadText("ITEM,200\nITEM,300\n")
        assertEquals(300, s.int1D[2])
        assertTrue(w.any { it.contains("重复定义") && it.contains("ITEM") }, w.toString())

        val (t, wt) = loadText("ITEMNAME,1200\nITEMNAME,1300\n")
        assertEquals(1300, t.maxDataList[6])
        assertEquals(1300, t.int1D[60])
        assertTrue(wt.any { it.contains("重复定义") && it.contains("ITEMNAME") }, wt.toString())
    }

    @Test
    fun `未知 0 维 计算变量与禁改`() {
        val (s, w) = loadText(
            "FOO,100\nRANDDATA,625\nRAND,10\nNAME,100\nITEM,99\nITEM,1000001\n",
        )
        assertTrue(w.any { it.contains("未知") && it.contains("FOO") }, w.toString())
        assertTrue(w.any { it.contains("不可修改") && it.contains("RANDDATA") }, w.toString())
        assertTrue(w.any { it.contains("不可修改") && it.contains("RAND") }, w.toString())
        assertTrue(w.any { it.contains("0 维") && it.contains("NAME") }, w.toString())
        assertTrue(w.any { it.contains("不能小于 100") && it.contains("ITEM,99") }, w.toString())
        assertTrue(w.any { it.contains("不能超过 1000000") }, w.toString())
        assertEquals(625, s.int1D[64])
        assertEquals(1000, s.int1D[2]) // 99 与 1000001 都被拒绝
    }

    @Test
    fun `无 VariableSize csv 时默认值不变且静默`() {
        val dir = createTempDirectory("eratw-varsize")
        try {
            val sizes = VariableSizes()
            val warnings = ArrayList<String>()
            VariableSizeLoader.load(dir, sizes, warnings)
            assertTrue(warnings.isEmpty(), warnings.toString())
            assertEquals(1000, sizes.maxDataList[6])
            assertEquals(20000, sizes.maxDataList[10])
            assertEquals(1000, sizes.int1D[60])
            assertEquals(20000, sizes.str1D[1])
            assertTrue(sizes.changed.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 真实语料
    // ------------------------------------------------------------------

    @Test
    fun `真实语料 VariableSize csv 全行零告警`() {
        val csvDir = findRealCsvDir()
        assumeTrue(csvDir != null, "未设置 ERATW_CORPUS 或找不到 CSV/VariableSize.csv")
        val sizes = VariableSizes()
        val warnings = ArrayList<String>()
        VariableSizeLoader.load(csvDir!!, sizes, warnings)

        // 旧 5 条「未知或不可改尺寸」必须消失（更强：整份文件零告警）
        assertTrue(warnings.isEmpty(), "不应有任何告警，实际：$warnings")
        for (old in listOf("ITEMNAME", "ABLNAME", "TALENTNAME", "EXPNAME", "MARKNAME")) {
            assertFalse(warnings.any { it.contains(old) }, "旧告警残留：$old")
        }

        // ITEMNAME,1000：名字表槽 6 与 ITEMPRICE 主数组同步
        assertEquals(1000, sizes.maxDataList[6])
        assertEquals(1000, sizes.int1D[60])
        // 契约 16 组（语料两侧都写的按同值、只写一侧的按跟随）
        assertEquals(100, sizes.maxDataList[0]); assertEquals(100, sizes.charaInt1D[2])      // ABL
        assertEquals(1000, sizes.maxDataList[2]); assertEquals(1000, sizes.charaInt1D[3])    // TALENT
        assertEquals(200, sizes.maxDataList[1]); assertEquals(200, sizes.charaInt1D[4])      // EXP
        assertEquals(100, sizes.maxDataList[5]); assertEquals(100, sizes.charaInt1D[5])      // MARK
        assertEquals(100, sizes.maxDataList[7]); assertEquals(100, sizes.charaInt1D[0])      // BASE
        assertEquals(250, sizes.maxDataList[8]); assertEquals(250, sizes.charaInt1D[7])      // SOURCE
        assertEquals(100, sizes.maxDataList[9]); assertEquals(100, sizes.charaInt1D[8])      // EX
        assertEquals(100, sizes.maxDataList[11]); assertEquals(100, sizes.charaInt1D[12])    // EQUIP
        assertEquals(1000, sizes.maxDataList[12]); assertEquals(1000, sizes.charaInt1D[13])  // TEQUIP
        assertEquals(10000, sizes.maxDataList[13]); assertEquals(10000, sizes.int1D[3])      // FLAG
        assertEquals(1000, sizes.maxDataList[14]); assertEquals(1000, sizes.int1D[4])        // TFLAG
        assertEquals(10000, sizes.maxDataList[15]); assertEquals(10000, sizes.charaInt1D[9]) // CFLAG
        assertEquals(1000, sizes.maxDataList[16]); assertEquals(1000, sizes.charaInt1D[20])  // TCVAR（只写主数组）
        assertEquals(100, sizes.maxDataList[17]); assertEquals(100, sizes.charaStr1D[0])     // CSTR（只写主数组）
        assertEquals(100, sizes.maxDataList[18]); assertEquals(100, sizes.charaInt1D[14])    // STAIN（只写主数组）
        assertEquals(20000, sizes.maxDataList[10]); assertEquals(20000, sizes.str1D[1])      // STR
        assertEquals(20000, sizes.maxDataList[21])                                          // STRNAME 跟随
        // 其余 7 组
        assertEquals(200, sizes.maxDataList[22]); assertEquals(200, sizes.str1D[6])          // TSTR
        assertEquals(100, sizes.maxDataList[23]); assertEquals(100, sizes.str1D[0])          // SAVESTR
        assertEquals(100, sizes.maxDataList[24]); assertEquals(100, sizes.int1D[63])         // GLOBAL
        assertEquals(100, sizes.maxDataList[25]); assertEquals(100, sizes.str1D[5])          // GLOBALS
        assertEquals(100, sizes.maxDataList[26]); assertEquals(100, sizes.int1D[0])          // DAY
        assertEquals(100, sizes.maxDataList[27]); assertEquals(100, sizes.int1D[22])         // TIME（low=22）
        assertEquals(100, sizes.maxDataList[28]); assertEquals(100, sizes.int1D[1])          // MONEY

        // 6 个 2 维项（DITEMTYPE/DA/DB/DC/DD/DE）
        for (i in 0..5) {
            assertContentEquals(intArrayOf(100, 100), sizes.int2D[i], "int2D[$i]")
        }
        // 3 维语料无实例 → 默认 100^3 不动
        assertContentEquals(intArrayOf(100, 100, 100), sizes.int3D[0])
        assertContentEquals(intArrayOf(100, 100, 100), sizes.int3D[1])
        // 局部变量（LOCAL/ARG/LOCALS/ARGS）
        assertEquals(200, sizes.int1D[61])
        assertEquals(200, sizes.int1D[62])
        assertEquals(100, sizes.str1D[3])
        assertEquals(100, sizes.str1D[4])
        // VariableRegistry.buildLocals 消费同一尺寸表，不再写死 1000/100
        val registry = com.eratw.port.core.vars.VariableRegistry(sizes)
        assertEquals(200, registry.def("LOCAL")!!.dims[0])
        assertEquals(200, registry.def("ARG")!!.dims[0])
        assertEquals(100, registry.def("LOCALS")!!.dims[0])
        assertEquals(100, registry.def("ARGS")!!.dims[0])
        // 常规 1 维读数抽查
        assertEquals(100, sizes.int1D[0])       // DAY
        assertEquals(1000, sizes.int1D[2])      // ITEM
        assertEquals(500, sizes.int1D[10])      // RESULT
        assertEquals(200, sizes.int1D[30])      // A（low=30）
        assertEquals(200, sizes.int1D[55])      // Z（low=55）
        assertEquals(200, sizes.str1D[2])       // RESULTS
    }

    /** `ERATW_CORPUS` 可能指游戏根、ERB 或 CSV；向上找 `CSV/VariableSize.csv`。 */
    private fun findRealCsvDir(): Path? {
        val env = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var current: Path? = Path.of(env)
        while (current != null) {
            if (Files.isDirectory(current)) {
                if (CsvLoader.resolveFile(current, "VariableSize.csv") != null) return current
                val csv = current.resolve("CSV")
                if (Files.isDirectory(csv) && CsvLoader.resolveFile(csv, "VariableSize.csv") != null) {
                    return csv
                }
            }
            current = current.parent
        }
        return null
    }
}
