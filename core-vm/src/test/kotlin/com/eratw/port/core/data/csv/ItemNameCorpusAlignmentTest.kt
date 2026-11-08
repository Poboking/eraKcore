package com.eratw.port.core.data.csv

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 簇 4（0.2.39「变量 ITEMNAME 的第 1 维下标 1000 越界（长度 1000）」）的**同源性实测闸**
 * —— 起点 = main@e208ced5（worker lane v039）。
 *
 * 上报读数：2 份 / 2 台 / app 0.2.39，ERB 帧链 `QOL_ITEMDATA_ORIGINAL ← QOL_ITEM_DATA_REGISTER
 * ← QOL_ITEM_INIT ← QOL_DB_INIT ← SYSTEM_TITLE`，抛点 `VariableStore.checkFlat` ← `readStrEntry`。
 *
 * 定性（13da05bd 的结论，本闸逐条实测复核）：**不是引擎缺陷，是出包混装**。
 * - 脚本面（4.981 专属，4.980 无此文件）：`ERB/魔改内容/qol/qol_item.ERB:113`
 *   `FOR ITEM_ID, 1, 1200 ;qol custom code`，循环体 `:114` 调 `QOL_ITEMDATA_ORIGINAL(ITEM_ID, …)`，
 *   该函数 `:402` 读 `ITEMNAME:ARG` ⇒ 最大访问下标 = 1199。
 * - 数据面：`CSV/VariableSize.csv:74` 4.981 = `ITEMNAME,1200`、4.980 = `ITEMNAME,1000`
 *   （`ITEMNAME` 与 `ITEMPRICE` 双向同步见同文件 :73 注释）。
 * ⇒ 4.981 脚本 + 4.981 数据：1199 < 1200，不越界；4.981 脚本 + 4.980 数据：下标 1000 起越界，
 * 与上报的「下标 1000 / 长度 1000」逐字对上。
 *
 * 引擎面与原版一致，不动（ADR-005）：越界读在原版同样抛 CodeEE
 * （spec: VariableTerm.cs:80-104 捕获 IndexOutOfRange 后 `Identifier.CheckElement` →
 * `VariableData.cs:81-87` 抛 `Lang.Error.OoRVarArg`，spec: Lang.cs:1638）。
 * 2026-11-04（lane errtext）已收口：`VariableStore.checkFlat` 现走
 * `core/vars/VarErrorText.kt` 的 `oorVarArg` ⇒ 上屏原文 =
 * `数组变量"X"的第1参数(N)超出数组范围`（官方串出处：语料根 `lang/emuera.zhs.xml:585`
 * / reference 表 `:364`，两表同文）。官方串**不带长度**，故本闸只按「变量名 + 下标」子串断言，
 * 长度由 `itemNameSize` 那条读数自证。
 *
 * 修法落点不在本路：出包同源的硬闸在 `devtools/pack_release.py`（`data_source_alignment` +
 * `ERATW_GAMEDIR` 缺省改「语料父目录 > 4.981 根 > 4.980 根」），devtools 不归 worker。
 *
 * 用例名里的 4981 / 4980 就是 4.981 / 4.980：反引号名会直接成为 JVM 方法名，点号是非法字符。
 */
class ItemNameCorpusAlignmentTest {

    private val gameRoot: Path? = GameRoot981.load()
    private val erbRoot: Path? = GameRoot981.erb()
    private val qolRel = "魔改内容/qol/qol_item.ERB"

    private companion object {
        /** 4.981 的循环上界行与 `ITEMNAME` 读取行（实测物理行号，1 基）。 */
        const val LOOP_LINE = 113
        const val READ_LINE = 402
        const val ITEMNAME = "ITEMNAME"
    }

    /** 退役的 4.980 游戏根（同源性反例要用它的数据），缺失时相关用例按假设跳过。 */
    private fun retiredRoot980(): Path? {
        val name = "eratw-画蛇添足版eratw4.980"
        val candidates = listOf(
            gameRoot?.parent?.resolve(name),
        )
        return candidates.filterNotNull().firstOrNull { Files.isRegularFile(it.resolve("CSV/VariableSize.csv")) }
    }

    private fun itemNameSize(csvDir: Path): Long {
        val sizes = VariableSizes()
        val warnings = ArrayList<String>()
        VariableSizeLoader.load(csvDir, sizes, warnings)
        val dims = requireNotNull(DimVarSizes.ofRegistry(VariableRegistry(sizes))[ITEMNAME]) {
            "VariableSize.csv 必须给出 $ITEMNAME 的尺寸；告警=$warnings"
        }
        return dims[0]
    }

    /** 真语料的 `FOR ITEM_ID, 1, N` 上界（直接读那行原文，不抄副本）。 */
    private fun realLoopBound(): Long {
        val file = erbRoot!!.resolve(qolRel)
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(file))
        val raw = lines[LOOP_LINE - 1].trim()
        assertTrue(raw.startsWith("FOR ITEM_ID, 1,"), "$qolRel:$LOOP_LINE 原文形态与上报口径不一致：$raw")
        return raw.substringBefore(';').trimEnd().split(',').last().trim().toLong()
    }

    private fun readItemName(csvDir: Path, index: Long): String {
        val sizes = VariableSizes()
        VariableSizeLoader.load(csvDir, sizes, ArrayList())
        val store = VariableStore(VariableRegistry(sizes))
        return store.readStr(ITEMNAME, longArrayOf(index))
    }

    @Test
    fun `真语料 4981 包的循环上界与 ITEMNAME 长度同源时不越界`() {
        val root = gameRoot
        val erb = erbRoot
        Assumptions.assumeTrue(root != null && erb != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val csv = root!!.resolve("CSV")
        val bound = realLoopBound()
        assertEquals(1200L, bound, "$qolRel:$LOOP_LINE 的循环上界（4.981 新增的 QOL 物品库）")
        val size = itemNameSize(csv)
        assertEquals(1200L, size, "4.981 CSV/VariableSize.csv:74 的 ITEMNAME 长度")
        // 最大访问下标 = bound-1：同源包必须读得动
        assertEquals(bound - 1L, size - 1L, "循环上界与数组长度的关系就是这条包能不能开局")
        assertEquals("", readItemName(csv, size - 1L), "同源包读 ITEMNAME:${size - 1} 不抛")
        // 越界档（下标 = 长度）仍照原版抛
        val e = assertFailsWith<CodeEE> { readItemName(csv, size) }
        assertTrue(ITEMNAME in (e.message ?: ""), "抛的是 ITEMNAME 的越界：${e.message}")
    }

    @Test
    fun `4981 脚本配 4980 数据必复现上报的越界读数`() {
        val root = gameRoot
        val retired = retiredRoot980()
        Assumptions.assumeTrue(root != null && retired != null, "缺 4.981 或退役 4.980 语料根：跳过混装档")
        val csv980 = retired!!.resolve("CSV")
        val bound = realLoopBound()
        val size980 = itemNameSize(csv980)
        assertEquals(1000L, size980, "4.980 CSV/VariableSize.csv:74 的 ITEMNAME 长度")
        assertTrue(bound > size980, "混装的定义就是脚本上界超过数据长度（$bound > $size980）")
        val e = assertFailsWith<CodeEE> { readItemName(csv980, size980) }
        // 上报原文：变量 ITEMNAME 的第 1 维下标 1000 越界（长度 1000）
        assertTrue(ITEMNAME in (e.message ?: ""), "抛的是 ITEMNAME：${e.message}")
        assertTrue("1000" in (e.message ?: ""), "混装档复现的正是上报的下标与长度：${e.message}")
    }

    @Test
    fun `真语料 402 行的读取形态就是 ITEMNAME 下标访问`() {
        val erb = erbRoot
        Assumptions.assumeTrue(erb != null, "缺 4.981 语料根：跳过")
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(erb!!.resolve(qolRel)))
        val raw = lines[READ_LINE - 1].trim()
        assertEquals("SIF ITEMNAME:ARG == \"\"", raw, "$qolRel:$READ_LINE 原文（上报抛点的读取行）")
    }
}
