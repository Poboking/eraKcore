package com.eratw.port.core.data.csv

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.DimFoldArg
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * ERD 用户变量关键字表（spec: `ErhLoader.cs:344-392 PrepareERDFileNames` +
 * `ConstantData.cs:758-803 UserDefineLoadData` / `:1090-1190 GetKeywordDictionary`）。
 *
 * 语料实测三种来源各一例：
 * - `CSV/M_TFLAG.csv` → 一维变量 `M_TFLAG`（`ERB/.../#DIM M_TFLAG, 10` 一类）；
 * - `CSV/M_FLAG.csv`、`CSV/M_CFLAG.csv` 同形；
 * - `ERB/カラム機能/COLUMNDIV@2.ERD` → 二维变量 `COLUMNDIV` 的**第二维**表。
 */
class UserKeywordTablesTest {

    private fun store(): VariableStore =
        VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    private fun dim(body: String, isPrivate: Boolean = false): DimSpec =
        DimParser.parse("#DIM", body, isPrivate)

    private fun gameRoot(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("erd-test")
        for ((rel, text) in files) {
            val p = root.resolve(rel)
            p.parent?.createDirectories()
            p.writeText(text)
        }
        return root
    }

    @Test
    fun `CSV 顶层表挂到一维用户变量`() {
        val root = gameRoot(
            mapOf(
                "CSV/M_TFLAG.csv" to ";魔法设置\r\n1,锻炼魔法CD\r\n2,转运魔法CD\r\n",
                "ERB/Main.ERB" to "@INIT\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("M_TFLAG, 10"))
        UserKeywordTables.install(s, root)

        assertEquals(1, s.userKeywordTable("M_TFLAG", 0)?.get("锻炼魔法CD"))
        assertEquals(2L, s.keywordToInteger("M_TFLAG", 0, "转运魔法CD"))
        // 未登记的关键字 = CodeEE（不是静默 0）。
        val e = assertFailsWith<CodeEE> { s.keywordToInteger("M_TFLAG", 0, "不存在") }
        assertEquals(true, (e.message ?: "").contains("未定义关键字"), e.message)
    }

    @Test
    fun `ERB 递归 ERD 文件的维度后缀只挂对应下标位`() {
        val root = gameRoot(
            mapOf(
                "ERB/カラム機能/COLUMNDIV@2.ERD" to
                    "0,COLUNMDIV_CREATED\r\n1,COLUNMDIV_WIDTH\r\n2,COLUNMDIV_HEIGHT\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("COLUMNDIV, 10, 20"))
        UserKeywordTables.install(s, root)

        // `@2` = 元素第二维 ⇒ 完整下标位 1（非角色变量无前置 chara 下标）。
        assertEquals(1, s.userKeywordTable("COLUMNDIV", 1)?.get("COLUNMDIV_WIDTH"))
        assertNull(s.userKeywordTable("COLUMNDIV", 0), "第一维没有表，不得误挂")
        assertEquals(2L, s.keywordToInteger("COLUMNDIV", 1, "COLUNMDIV_HEIGHT"))
    }

    @Test
    fun `CHARADATA 的下标位整体后移一位`() {
        val root = gameRoot(
            mapOf("CSV/M_TFLAG.csv" to "1,锻炼魔法CD\r\n"),
        )
        val s = store()
        s.declareUserVar(dim("CHARADATA M_TFLAG, 10"))
        UserKeywordTables.install(s, root)

        // `M_TFLAG:chara:关键字` → 关键字在完整下标位 1。
        assertEquals(1L, s.keywordToInteger("M_TFLAG", 1, "锻炼魔法CD"))
        assertNull(s.userKeywordTable("M_TFLAG", 0))
    }

    @Test
    fun `私有声明共享同名表且越界下标行被丢弃`() {
        val root = gameRoot(
            mapOf(
                // 下标 15 越界（extent=10）、下标列非数字、空名字都要丢。
                "CSV/PRIV.csv" to "1,OK\r\n15,OOR\r\nx,BAD\r\n2,\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("PRIV, 10", isPrivate = true), "F1")
        s.declareUserVar(dim("PRIV, 10", isPrivate = true), "F2")
        UserKeywordTables.install(s, root)

        // 同名私有变量跨函数共用一张表（C# erdNameToIntDics 同样只按名字做键）。
        assertEquals(mapOf("OK" to 1), s.userKeywordTable("PRIV", 0))
    }

    @Test
    fun `读不开的表跳过且不拖垮其它变量`() {
        val root = gameRoot(
            mapOf("CSV/GOOD.csv" to "1,FOO\r\n"),
        )
        // CP932 的单字节前导 0x81 没有后继 ⇒ 两种编码都解不开（C# 是 FailedOpenFile 告警）。
        Files.write(root.resolve("CSV/BAD.csv"), byteArrayOf(0x81.toByte()))

        val s = store()
        s.declareUserVar(dim("GOOD, 5"))
        s.declareUserVar(dim("BAD, 5"))
        UserKeywordTables.install(s, root)

        assertEquals(1L, s.keywordToInteger("GOOD", 0, "FOO"))
        // 读不开 ⇒ 原版 `loadDataTo` 的 FailedOpenFile 只告警、整张文件跳过，`names[29]`
        // 保持全空；但随后的 `erdNameToIntDics.Add(varname, dictionary2)` 是**无条件**的
        // （ConstantData.cs:783-792）⇒ 表**在**，只是里面一个关键字都没有。
        assertEquals(emptyMap(), s.userKeywordTable("BAD", 0), "空表也要登记")
        val e = assertFailsWith<CodeEE> { s.keywordToInteger("BAD", 0, "FOO") }
        assertEquals("BAD.csv 中未定义关键字 \"FOO\"", e.message, "空表 ⇒ 键不在，不是「没有表」")
    }

    /**
     * 空表登记语义（本轮收口）：`UserDefineLoadData` 结尾的
     * `erdNameToIntDics.Add(varname, dictionary2)`（ConstantData.cs:783-792）无条件执行，
     * `dictionary2` 由 `dictionary` 投影而来、可以是空字典。所以「表文件存在但一行都没落下」
     * 与「该变量根本没有表文件」在原版是**两种不同状态**，报错文案也不同：
     * - 空表 ⇒ `GetKeywordDictionary` 拿得到字典 ⇒ 缺键报 `CSV 中未定义关键字`（VariableStrArgTerm）；
     * - 无表 ⇒ 直接 `不能用字符串指定`（ConstantData.cs:1090-1190 的 VAR 分支前置判定）。
     * 旧实现把前者 `return null` 掉，等于把「空表」误报成「没有表」。
     */
    @Test
    fun `表文件存在但全部行被丢弃时仍登记空表`() {
        val root = gameRoot(
            mapOf(
                // 三行全被丢：下标非数字、下标越界（extent=4）、空名字。
                "CSV/ALLDROP.csv" to "x,BAD\r\n99,OOR\r\n2,\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("ALLDROP, 4"))
        s.declareUserVar(dim("NOTABLE, 4"))
        UserKeywordTables.install(s, root)

        assertEquals(emptyMap(), s.userKeywordTable("ALLDROP", 0), "有文件但无有效行 ⇒ 登记空表")
        assertNull(s.userKeywordTable("NOTABLE", 0), "没有表文件的变量仍然无表")
        assertEquals(
            "ALLDROP.csv 中未定义关键字 \"ANY\"",
            assertFailsWith<CodeEE> { s.keywordToInteger("ALLDROP", 0, "ANY") }.message,
        )
        assertEquals(
            "变量 NOTABLE 的第 1 个参数不能用字符串指定（未找到 ERD 表 NOTABLE.csv）",
            assertFailsWith<CodeEE> { s.keywordToInteger("NOTABLE", 0, "ANY") }.message,
        )
    }

    // ------------------------------------------------------------------
    // CMP-59 数据面：装载期折叠用的快照（`GETNUM` 第二跳）
    // ------------------------------------------------------------------

    /**
     * 键口径 = 原版 `erdNameToIntDics`（`ConstantData.cs:783-792`）：1 维 `NAME`、
     * 多维 `NAME@n`（n 从 1 起）；`GetnumMethod` 在 `FunctionMethodCreator.cs:4819` 拼的
     * 就是这个形态。折叠链要与 [UserKeywordTables.install] 同源（同一份 discover/load）。
     */
    @Test
    fun `foldSnapshot 供 GETNUM 第二跳 NAME 与 NAME@n`() {
        val root = gameRoot(
            mapOf(
                "CSV/M_TFLAG.csv" to "1,锻炼魔法CD\r\n2,转运魔法CD\r\n",
                "ERB/カラム機能/COLUMNDIV@2.ERD" to "0,COLUNMDIV_CREATED\r\n1,COLUNMDIV_WIDTH\r\n",
            ),
        )
        val snap = UserKeywordTables.foldSnapshot(root)
        assertEquals(mapOf("锻炼魔法CD" to 1, "转运魔法CD" to 2), snap["M_TFLAG"])
        assertEquals(
            mapOf("COLUNMDIV_CREATED" to 0, "COLUNMDIV_WIDTH" to 1),
            snap["COLUMNDIV@2"],
            "多维表键 = 变量名@维号（1 起）",
        )
        assertNull(snap["COLUMNDIV"], "第一维没有表，不得凭空补一张")

        val folder = DimFunctionFolders.pureStatic(keywordTables = snap)
        assertEquals(
            1L,
            folder.foldInt(
                "GETNUM",
                listOf(DimFoldArg.IdentArg("M_TFLAG"), DimFoldArg.StrArg("锻炼魔法CD")),
            ),
            "两参形态：第二跳键 = NAME",
        )
        assertEquals(
            1L,
            folder.foldInt(
                "GETNUM",
                listOf(
                    DimFoldArg.IdentArg("COLUMNDIV"),
                    DimFoldArg.StrArg("COLUNMDIV_WIDTH"),
                    DimFoldArg.IntArg(2),
                ),
            ),
            "三参形态：第二跳键 = NAME@第三参",
        )
    }

    /** 系统变量名不可能被用户 `#DIM` 重声明 ⇒ 同名 CSV 不得被当成 ERD 表（见 [foldSnapshot] KDoc）。 */
    @Test
    fun `foldSnapshot 排除系统变量名候选表`() {
        val root = gameRoot(
            mapOf(
                "CSV/TIME.csv" to "0,朝\r\n1,昼\r\n",
                "CSV/M_TFLAG.csv" to "1,FOO\r\n",
            ),
        )
        val snap = UserKeywordTables.foldSnapshot(root, systemNames = setOf("TIME"))
        assertNull(snap["TIME"], "系统变量 TIME 不是 ERD 用户变量，必须排除")
        assertEquals(mapOf("FOO" to 1), snap["M_TFLAG"])
    }

    /** 重键表整张跳过（失败方向 = 不折；告警归 [install] 的 DuplicateErdKey 通道）。 */
    @Test
    fun `foldSnapshot 重键表整张跳过`() {
        val root = gameRoot(mapOf("CSV/DUP.csv" to "0,SAME\r\n1,SAME\r\n"))
        assertEquals(emptyMap(), UserKeywordTables.foldSnapshot(root))
    }

    /**
     * 全语料读数（只读诊断；`ERATW_GAMEDIR` 缺失时跳过）：给「CMP-59 数据面」一组实测数字。
     *
     * 系统变量名从 [GameDataLoader] 的 registry 取（与生产 `CorpusCompile` / `GameSession`
     * 同源）。语料实测 6 张（2026-10-24）：`COLUMNDIV@2` / `EI_BASE` / `EI_FLAG` /
     * `M_CFLAG` / `M_FLAG` / `M_TFLAG`（`_Rename` 一类引擎内部 csv 按 [foldSnapshot] 的
     * KDoc 不计）。语料的 ERD 表变了要同步改这里。
     */
    @Test
    fun `全语料 ERD 表快照读数`() {
        val game = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }
        Assumptions.assumeTrue(game != null, "未提供 ERATW_GAMEDIR，跳过全量读数")
        val root = Path.of(game!!)
        val registry = GameDataLoader.load(root.resolve("CSV"))?.registry
        val systemNames = registry?.all()?.mapTo(HashSet()) { it.name.uppercase() } ?: emptySet()
        val snap = UserKeywordTables.foldSnapshot(root, systemNames)
        println(
            "CMP-59 全语料 ERD 表快照：${snap.size} 张 → ${snap.keys.sorted()}；" +
                "系统名排除 ${systemNames.size} 个",
        )
        assertEquals(
            listOf("COLUMNDIV@2", "EI_BASE", "EI_FLAG", "M_CFLAG", "M_FLAG", "M_TFLAG"),
            snap.keys.sorted(),
            "语料实测 6 张；变多/变少都要写出处",
        )
        assertEquals(0, snap["EI_FLAG"]?.get("栄養"), "EI_FLAG 的下标 0 = 栄養（CSV/EI_FLAG.csv:1）")
    }
}
