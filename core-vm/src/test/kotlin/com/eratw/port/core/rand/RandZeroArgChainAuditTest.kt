package com.eratw.port.core.rand

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * RAND 上界 0 那一簇的**取值链**取证（lane randzero，2026-11-05）。
 *
 * 收件箱里「RAND 的参数必须为正数（实际 0）」有两个脚本位置：
 * - `QOL_GATHERING_DRAWING 行69` —— 130 份 / 4 台（app 0.2.34–0.2.35，11-01 14:53 ~ 11-04 14:09）
 * - `M_KOJO_EVENT_K70_31 行6087` —— 15 份 / 6 台（app 0.2.0–0.2.3）
 *
 * 既有裁定（提交 9948a5da）只证到「会抛」，本类回答**参数为什么是 0**。文案面已有钉
 * （[RandArgMessageTest] / [CompatiRandKeyTest]），这里一个字都不碰。
 *
 * ## 语料根：两簇都在 **4.980**，不在 4.981
 *
 * 上报「行 N」= **0 基**物理行下标，对应物理行 N+1（与 `PowerFloatRealCorpusTest`
 * 钉住的「SOURCE_DOWNBASE 行625 ↔ 物理 626」同一条口径；本类 [上报行号是零基下标] 自证）。
 * 按这条口径：
 * - 4.980 `ERB/魔改内容/qol/QOL_COMF445 採集する.ERB` 物理 70 =
 *   `CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)` ✅
 * - 4.981 同一函数已整段重写成权重池（`ROLL_VAL = RAND:TOTAL_WEIGHT`，物理 67 ⇒ 0 基 66），
 *   与上报的 69 对不上 ⇒ **130 份那一簇跑的是 4.980 数据根**（见 [同一式在九八一语料里行号对不上]）。
 * - 4.980 小鈴 `M_KOJO_K70_01_イベント.ERB` 物理 6088 = `今回のブーム = RAND:RESULT + 1` ✅
 *   （4.981 同一段在物理 6086 ⇒ 0 基 6085，同样对不上）。
 *
 * 所以本装置自带 4.980 根发现（[GameRoot980]），**不**复用 `RealCorpus`：那条路
 * `ERATW_CORPUS` 显式给出即权威，而语料闸常把它指到 4.981。
 *
 * ## 两条链的判词（不同源）
 *
 * ① QOL 采集 ⇒ **(a) 玩家状态下合法为 0，PC 原版同抛，ADR-005 原样复刻不修**。
 *   `FINAL_COUNT` 由 4.980 `:57-68` 的降级抽选环决定：每轮开头 `FINAL_COUNT = 0`，
 *   命中才 `FINAL_POOL:(FINAL_COUNT++) = ITEM_ID`，命中即 `BREAK`。
 *   **`BREAK` 是唯一正路出口**；靠条件出口（`TARGET_LEVEL` 一路 `--` 到 -1）出体时，
 *   出体那一轮开头的 `FINAL_COUNT = 0` 没有任何命中去改写它 ⇒ 上界恒 0 ⇒ 抛。
 *   可复现链条（逐环带语料行号）：
 *   - 起点档 `:38 TARGET_LEVEL = RAND_ADVANTAGE(MAX_LEVEL + 1, BONUS_ROLLS)`，
 *     `Misc.ERB:376-386` 的返回值域是 `0 ~ MAX_VAL-1`，**0 在域内**（`ROLLS ≥ 1` 时
 *     只是 `ROLLS` 次取最大值，抽不到 0 的条件不存在）。
 *   - 等级 `:80-91 GET_GATHERING_LEVEL(VAL)` 读 `:85 GATHER_VALUE_LEVEL = 0,500,2000,4000,7000,10000`
 *     ⇒ 等级 0 的充要条件是**价格 < 500**。
 *   - 入池门槛 `:46`（`IS_GATHERABLE(ITEM_ID, SPOT_ID) && 采集力 >= GET_GATHER_DIFFICULTY(ITEM_ID)`）
 *     与等级**不是同一个单调轴**：`:129-152` 的难度 = `SQRT(价格+1000)`（台风/冷涡支路再除 2），
 *     采集力只给出价格上界，不给价格下界。
 *   ⇒ 前置「本地点/季节所有过门槛的素材价格都 ≥ 500，而起点档恰好抽到 0」在玩家状态里成立，
 *     此时 `VALID_COUNT > 0`（`:54` 的早退不触发）但降级环逐档全空 ⇒ `RAND:0`。
 *   (b) 面已排除：本类单独钉 `:(FINAL_COUNT++)` 这个**索引位里的后置自增**真推进（命中 2 件 ⇒ 读数 2），
 *   所以那个 0 不是「我们没数上」而是「真没命中」；空池走 `:54-55` 早退，连 RAND 都不发起。
 *
 * ② 小鈴 ⇒ **(b) 移植侧读数偏了，已由 `e36e0de0`（2026-10-31 14:49）修掉**；
 *   本类把真语料那一段的**形状**钉成防回退装置。
 *   上界是**全局 `RESULT`**，由被调 `#FUNCTIONS M_KOJO_K70_KOSUZUBOOM()` 体内
 *   `:250` 的 `RESULT = 5` 留下（注释原文「種類数をここで設定」）—— 原版 `RESULT` 是
 *   系统变量表里的全局 1 维整型（`VariableData.cs:3024`），方法帧不隔离它。
 *   现场必然走到落尾：`:6084` 刚把 `CFLAG:[[小铃]]:1080 = 0`，`:6087` 的 CALLF 于是对
 *   `0 % 10 = 0` 做 SELECTCASE，而 `:251-265` 只有 CASE 1–6、**没有 CASEELSE** ⇒ 直接掉尾。
 *   原版掉尾清零 `Process.cs:1059-1066` 带前缀 `if (!state.IsFunctionMethod)` ⇒ **方法帧保留** RESULT。
 *   移植侧早期无条件投 `RESULT = 0` ⇒ 调用方读 0 ⇒ 开局中止；现由
 *   `ErbProgramCompiler.kt:335-344` 的 `!sig.isMethod` 闸承接（既有钉 `MethodFallthroughResultTest`，
 *   本类补真语料形状：无 CASEELSE 的 SELECTCASE 落空 + `RAND:RESULT + 1` 的 WHILE 环）。
 *   现场对账：本簇 15 份全部来自 **app 0.2.0 / 0.2.3 / 0.2.32**（修复随 0.2.33 出厂，
 *   `8d2745df` 2026-10-31 18:42），0.2.33+ 一条都没有 ⇒ 修复已生效，剩余上报是旧包存量。
 *   ⇒ 提交 9948a5da 把整簇判成「官方同款不修」对本簇是**过期结论**（判它时修复已在 main 上）。
 *
 * ③ 绑定面（两簇共用）：`RAND:RESULT + 1` 的上界就是 `RESULT` 本身、`+1` 救不了 0。
 *   原版 `ExpressionParser.cs:497-619` 里 `flag = varCode != __NULL__` 让「变量 `:` 后的实参」
 *   只消费**一个 term** 就收口（`case '='` 在该态直接抛 `UnexpectedOpInVarArg`），
 *   回到外层 reduceTerm 才轮到 `+`。移植侧同形，见 [RAND 冒号实参只吃一个项]；
 *   运行期读数也同形（桩记账到的上界恒为 5，不是 6）。
 *   ⇒ 两簇**不同源**：①是语料降级环的洞、②是移植侧掉尾清零。
 *
 * ## 覆盖面与卡点
 * - 4.981 的同族式 `ROLL_VAL = RAND:TOTAL_WEIGHT`（物理 67 ⇒ 0 基 66）**不在**本类判据内：
 *   它换成了权重累加池，依赖 `#DIM CONST GATHER_WEIGHT, 6` 与 `ITEMPRICE` 两级读数链。
 *   TODO(verify)：若后续上报出现「QOL_GATHERING_DRAWING 行66」这一支，要另开一路验
 *   `TOTAL_WEIGHT` 的累加与 `VALID_WEIGHT:VALID_COUNT` 回读；卡点是本路只对齐了 4.980 的行号。
 * - ①只证「可达」、不证「频率」：报告口径不含变量取值与存档，玩家现场的价格分布与
 *   起点档抽样序列拿不到，无法核对该 4 台设备的实际触发率。
 * - 上报第三形态「实际 -3（无脚本位置，app 0.2.35）」走的是同一道 `num <= 0` 闸
 *   （`VariableData.cs:848-851` 同款），但调用点是 `BeginFlow.stepTrainCallComXX` 的训练 COM 链，
 *   且 CodeEE 没被 `ProcessRuntime.stampPosition` 补上函数/行 —— 属**定位/上报面**，
 *   不在本路文件所有权内，转组长。
 */
class RandZeroArgChainAuditTest {

    // ------------------------------------------------------------------
    // 4.980 语料根（本路判据专用）
    // ------------------------------------------------------------------

    private object GameRoot980 {
        private const val DIR = "eratw-画蛇添足版eratw4.980"

        /** 命中即返回 `<4.980 根>/ERB`；找不到 null（调用方按 assumption 跳过）。 */
        fun erb(): Path? {
            val candidates = LinkedHashSet<Path>()
            System.getenv("ERATW_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { candidates.add(Paths.get(it).resolve(DIR).resolve("ERB")) }
            val cwd = Paths.get("").toAbsolutePath().normalize()
            val repoRoot = cwd.parent?.parent ?: cwd
            repoRoot.parent?.let { up ->
                candidates.add(up.resolve(DIR).resolve("ERB"))
                candidates.add(up.resolve("eratw-安卓移植计划").resolve(DIR).resolve("ERB"))
            }
            return candidates.firstOrNull { Files.isDirectory(it) }
        }
    }

    private val qolRel = "魔改内容/qol/QOL_COMF445 採集する.ERB"
    private val kosuzuRel = "口上・メッセージ関連/個人口上/070 Kosuzu [小鈴]/小鈴/M_KOJO_K70_01_イベント.ERB"

    /** 真文件按装载链同一套编码探测拆行；返回的列表**下标即 0 基行号**（= 上报口径）。 */
    private fun realLines(rel: String): List<String> {
        val root = GameRoot980.erb() ?: return emptyList()
        return LogicalLineReader.readAllLines(Files.readAllBytes(root.resolve(rel)))
    }

    // ------------------------------------------------------------------
    // 最小 ERB 装置：解析 + 编译 + 真 VariableStore + ProcessRuntime
    // ------------------------------------------------------------------

    /** RAND 上界探查桩：把收到的 bound 记账，返回值仍按 `bound-1`（不越界、可复现）。 */
    private class RecordingRand : RandProvider {
        val bounds = ArrayList<Long>()
        override fun next(bound: Long): Long {
            bounds.add(bound)
            return bound - 1
        }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("G0", OperandType.INT, 1)
            .declareVariable("G1", OperandType.INT, 1)
            .declareVariable("LVL", OperandType.INT, 1)
            .declareVariable("ITEM_ID", OperandType.INT, 1)
            .declareVariable("CHOSEN_ID", OperandType.INT, 1)
            .declareVariable("VALID_POOL", OperandType.INT, 1)
            .declareVariable("VALID_COUNT", OperandType.INT, 1)
            .declareVariable("FINAL_POOL", OperandType.INT, 1)
            .declareVariable("FINAL_COUNT", OperandType.INT, 1)
            .declareVariable("TARGET_LEVEL", OperandType.INT, 1)
            .declareVariable("BOOM", OperandType.INT, 1)
            .declareVariable("PREV_BOOM", OperandType.INT, 1)
            .declareVariable("BOOMFLAG", OperandType.INT, 1)
            .declareFunction("KOSUZUBOOM", OperandType.STR)
            .declareMethod("KOSUZUBOOM"),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "randzero.erb", i + 1, text))
        }
    }

    private class Runner(val store: VariableStore, val rand: RecordingRand, val runtime: ProcessRuntime)

    /** 只编 `@MAIN`（[body] 不含函数头）。 */
    private fun runMain(vararg body: String): Runner {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), parse(*body), setOf("MAIN"))
        return build(c, listOf(FuncMeta(index = c.functions.single().index, key = "MAIN", name = "MAIN", position = "randzero.erb")))
    }

    /** 编 `@MAIN` + 一个 `#FUNCTIONS` 方法 [callee]（掉尾或小返回）。 */
    private fun runWithMethod(callee: String, main: List<String>, methodBody: List<String>): Runner {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", callee)
        c.compileFunction(FuncSig("MAIN", 0), parse(*main.toTypedArray()), known)
        c.compileFunction(
            FuncSig(callee, 0, isMethod = true, returnsStr = true),
            parse(*methodBody.toTypedArray()),
            known,
        )
        val metas = listOf(
            FuncMeta(index = c.functions.first { it.sig.name == "MAIN" }.index, key = "MAIN", name = "MAIN", position = "randzero.erb"),
            FuncMeta(
                index = c.functions.first { it.sig.name == callee }.index,
                key = callee, name = callee, isMethod = true, returnsStr = true, position = "randzero.erb",
            ),
        )
        return build(c, metas)
    }

    private fun build(c: ErbProgramCompiler, metas: List<FuncMeta>): Runner {
        val rand = RecordingRand()
        val store = VariableStore().apply {
            randProvider = rand
            for (n in listOf("G0", "G1", "ITEM_ID", "CHOSEN_ID", "VALID_COUNT", "FINAL_COUNT", "TARGET_LEVEL", "BOOM", "PREV_BOOM", "BOOMFLAG")) {
                declareUserVar(DimSpec(n, isStr = false, dims = intArrayOf(1)))
            }
            for (n in listOf("FINAL_POOL", "LVL", "VALID_POOL")) {
                declareUserVar(DimSpec(n, isStr = false, dims = intArrayOf(500)))
            }
        }
        return Runner(store, rand, ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()))
    }

    private fun VariableStore.i0(name: String): Long = readInt(name, longArrayOf(0L))

    private fun VariableStore.set0(name: String, v: Long) = writeInt(name, longArrayOf(0L), v)

    // ------------------------------------------------------------------
    // ① 0 基口径自证 + 两簇抛点原文（真语料，不抄副本）
    // ------------------------------------------------------------------

    @Test
    fun `上报行号是零基下标`() {
        Assumptions.assumeTrue(GameRoot980.erb() != null, "缺 4.980 语料根：跳过")

        // QOL：上报「行69」⇒ 下标 69 ⇒ 物理第 70 行
        val qol = realLines(qolRel)
        assertEquals("CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)", qol[69].trim(), "上报行69 = 0 基下标 69")
        // 左右邻居做反证：整个函数体（物理 11–73）里 `RAND:` 只出现在这一行，
        // 上报的 69 只能指它 —— 偏移一格就落空（68 是 WEND、69 是空行、71 是 QTY_PENALTY）。
        assertEquals("WEND", qol[67].trim(), "下标 67 = 物理 68")
        assertEquals("", qol[68].trim(), "下标 68 = 物理 69 是空行")
        assertEquals("QTY_PENALTY = MAX(SQRT(GATHER_POW) - TARGET_LEVEL, 1)", qol[70].trim(), "下标 70 = 物理 71")
        assertEquals(
            listOf(69),
            (10..72).filter { "RAND:" in qol[it] },
            "@QOL_Gathering_Drawing 函数体里唯一的 RAND: 抛点（RANDSF / RAND_ADVANTAGE 不算）",
        )

        // 小鈴：上报「行6087」⇒ 下标 6087 ⇒ 物理第 6088 行
        val kosuzu = realLines(kosuzuRel)
        assertEquals("今回のブーム = RAND:RESULT + 1", kosuzu[6087].trim(), "上报行6087 = 0 基下标 6087")
        assertEquals("CALLF M_KOJO_K70_KOSUZUBOOM()", kosuzu[6086].trim(), "上一行就是 RESULT 的来源 CALLF")
        assertEquals("SIF 今回のブーム != 前回のブーム", kosuzu[6088].trim(), "下一行不是抛点")
    }

    @Test
    fun `上报函数名是大小写归一后的键位`() {
        Assumptions.assumeTrue(GameRoot980.erb() != null, "缺 4.980 语料根：跳过")
        val qol = realLines(qolRel)
        // 语料原文 `@QOL_Gathering_Drawing(SPOT_ID, GATHER_POW, MOD_VALUE)`（物理 11 行），
        // 上报写成全大写 ⇒ 标识符大小写不敏感、键位归一到大写，同一个 @ 头就是同一簇。
        assertTrue(qol[10].trim().startsWith("@QOL_Gathering_Drawing("), "定义头原文：${qol[10]}")
        // 该 label 无 #FUNCTION 标记，调用方是 `COMF445 採集する.ERB:939 JUMP QOL_Gathering_Drawing(...)`
        assertTrue(!qol[11].trim().startsWith("#FUNCTION"), "11 行后紧跟的不是 #FUNCTION：本 label 走 JUMP 尾跳")
        assertEquals("JUMP QOL_Gathering_Drawing(採集位置, 採集パワー, Modification)", realComf445(938).trim(), "唯一调用点")
        assertEquals("@M_KOJO_EVENT_K70_31(ARG,ARG:1)", realLines(kosuzuRel)[6066].trim(), "0 基 6066 = 物理 6067 的事件头")
    }

    private fun realComf445(index: Int): String {
        val root = GameRoot980.erb()!!
        val rel = "コマンド関連/COMF/日常系/COMF445 採集する.ERB"
        return LogicalLineReader.readAllLines(Files.readAllBytes(root.resolve(rel)))[index]
    }

    /**
     * 反证数据根：同一对文件在 4.981 里**行号对不上**，所以这两簇只能是 4.980。
     * 4.981 把 `@QOL_Gathering_Drawing` 整段重写成权重池（`ROLL_VAL = RAND:TOTAL_WEIGHT`，
     * 物理 67 ⇒ 0 基 66），小鈴那段整体前移 2 行（物理 6086 ⇒ 0 基 6085）。
     */
    @Test
    fun `同一式在九八一语料里行号对不上`() {
        val root981 = GameRoot981.erb()
        Assumptions.assumeTrue(root981 != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val qol981 = LogicalLineReader.readAllLines(Files.readAllBytes(root981!!.resolve(qolRel)))
        assertNotEquals("CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)", qol981[69].trim(), "4.981 的 0 基 69 不是上报那条式")
        assertEquals("ROLL_VAL = RAND:TOTAL_WEIGHT", qol981[66].trim(), "4.981 重写后的 RAND 行（0 基 66）")
        val kosuzu981 = LogicalLineReader.readAllLines(Files.readAllBytes(root981.resolve(kosuzuRel)))
        assertNotEquals("今回のブーム = RAND:RESULT + 1", kosuzu981[6087].trim(), "4.981 的 0 基 6087 不是上报那条式")
        assertEquals("今回のブーム = RAND:RESULT + 1", kosuzu981[6085].trim(), "4.981 同段前移 2 行（0 基 6085）")
    }

    // ------------------------------------------------------------------
    // ② 绑定面：`RAND:X + 1` 的上界只吃 X
    // ------------------------------------------------------------------

    @Test
    fun `RAND 冒号实参只吃一个项`() {
        val stmt = assertIs<AssignStmt>(parser().parseLogicalLine(LogicalLine(LineKind.CODE, "p.erb", 1, "G0:0 = RAND:G1 + 1")))
        val rhs = assertIs<SpSetArgument>(stmt.args).term
        // 顶层必须是 `+`、RAND 在左操作数里 ⇒ 上界就是 G1 本身，`+1` 在 RAND 之外，救不了 0。
        val plus = assertIs<Binary>(rhs)
        val rand = assertIs<VarRef>(plus.left)
        assertEquals("RAND", rand.name)
        assertEquals(1, rand.indices.size, "RAND 只带一个上界实参")
        assertEquals("G1", assertIs<VarRef>(rand.indices[0]).name)
        assertEquals(1L, assertIs<IntLiteral>(plus.right).value, "右操作数是字面量 1")
    }

    // ------------------------------------------------------------------
    // ③ QOL 簇：FINAL_COUNT 的取值链（降级抽样环 + 索引位里的 ++）
    // ------------------------------------------------------------------

    /**
     * 4.980 `QOL_COMF445 採集する.ERB:57-70` 的降级抽选池，逐行同形复刻：
     * 变量名照抄（VALID_POOL / VALID_COUNT / FINAL_POOL / FINAL_COUNT / TARGET_LEVEL / ITEM_ID /
     * CHOSEN_ID），只把 `GET_GATHERING_LEVEL(ITEMPRICE:ITEM_ID)` 换成 `LVL:ITEM_ID` 读数
     * （等级由测试直接铺，避免拖进 IS_GATHERABLE / GET_MATERIAL_LIST 整条物品链）。
     * 起点档 `TARGET_LEVEL` 原版是 `RAND_ADVANTAGE(MAX_LEVEL + 1, BONUS_ROLLS)` 的产物，
     * 这里按上报现场当**已知种子**给（本簇判据只关心「降级有没有出口」）。
     */
    private val qolLoop = listOf(
        "WHILE TARGET_LEVEL >= 0",
        "\tFINAL_COUNT = 0",
        "\tFOR LOCAL, 0, VALID_COUNT",
        "\t\tITEM_ID = VALID_POOL:LOCAL",
        "\t\tSIF LVL:ITEM_ID == TARGET_LEVEL",
        "\t\t\tFINAL_POOL:(FINAL_COUNT++) = ITEM_ID",
        "\tNEXT",
        "\tSIF FINAL_COUNT > 0",
        "\t\tBREAK",
        "\tTARGET_LEVEL --",
        "WEND",
        "CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)",
    )

    /** 铺池：`levels[i]` 是第 i 件素材（id = 100+i）的等级，等价于原版 LVL 读数。 */
    private fun Runner.seedPool(levels: List<Int>) {
        store.set0("VALID_COUNT", levels.size.toLong())
        levels.forEachIndexed { i, lv ->
            val id = (100 + i).toLong()
            store.writeInt("VALID_POOL", longArrayOf(i.toLong()), id)
            store.writeInt("LVL", longArrayOf(id), lv.toLong())
        }
    }

    @Test
    fun `QOL 降级命中时 FINAL_COUNT 是自增出来的真数`() {
        // 池 3 件、等级 [3,2,3]，起点 3 ⇒ 第一轮命中 2 件 ⇒ BREAK 出体。
        // 钉的是 `FINAL_POOL:(FINAL_COUNT++)` 这个**索引位里的后置自增**真推进：
        // 若不推进，FINAL_COUNT 恒 0 ⇒ `SIF FINAL_COUNT > 0` 永不成立 ⇒ 后两条用例必红 ⇒ 这里先红。
        val r = runMain(*qolLoop.toTypedArray(), "G0:0 = FINAL_COUNT", "G1:0 = RAND:FINAL_COUNT")
        r.seedPool(listOf(3, 2, 3))
        r.store.set0("FINAL_COUNT", 0L)
        r.store.set0("TARGET_LEVEL", 3L)
        r.runtime.execute()
        assertEquals(2L, r.store.i0("FINAL_COUNT"), "命中 2 件 ⇒ FINAL_COUNT = 2（索引位 ++ 有效）")
        assertEquals(3L, r.store.i0("CHOSEN_ID") - 99L, "FINAL_POOL 第 2 格（下标 1）= 第二件命中的素材 id 102（等级 3）")
        // BREAK 之后 RAND 发两次（抽选 + 再读一次只为记账上界）：两次都必须是 2，绝不许是 0
        assertEquals(listOf(2L, 2L), r.rand.bounds, "RAND 收到的上界 = FINAL_COUNT = 2")
    }

    @Test
    fun `QOL 池内等级全部高于起点档时上界合法落零`() {
        // 池 3 件、等级 [4,4,5]，起点 2 ⇒ TARGET_LEVEL 2→1→0→-1 条件出体，
        // 出体那一轮开头 FINAL_COUNT 已被重置成 0 且全程零命中 ⇒ RAND:0 抛。
        // ⇒ 上报的 0 是**语料降级环自己的洞**（原版同形，ADR-005 原样复刻不修）。
        val r = runMain(*qolLoop.toTypedArray())
        r.seedPool(listOf(4, 4, 5))
        r.store.set0("FINAL_COUNT", 0L)
        r.store.set0("TARGET_LEVEL", 2L)
        val t = runCatching { r.runtime.execute() }.exceptionOrNull()
        assertIs<CodeEE>(t, "条件出体时 FINAL_COUNT 必为 0 ⇒ RAND 抛（原版同款）")
        assertTrue(t!!.message!!.startsWith("RAND不能指定0以下的值(0)"), "抛的是上界 0：${t.message}")
        assertEquals(0L, r.store.i0("FINAL_COUNT"), "出体现场 FINAL_COUNT 读数")
        assertEquals(-1L, r.store.i0("TARGET_LEVEL"), "出体时 TARGET_LEVEL 已被减到 -1， WHILE 的条件闸收口（唯一非 BREAK 出口）")
        assertTrue(r.rand.bounds.isEmpty(), "bound 0 在取值桩之前就抛，桩不该被调到：${r.rand.bounds}")
    }

    @Test
    fun `QOL 空池走早退碰不到 RAND`() {
        // 4.980 :54-55 `SIF VALID_COUNT == 0 → RETURN 0, 0`：空池进不了降级环。
        // 排除「未初始化也算反例」——上报那个 0 只能来自降级洞，不来自空池路径。
        val r = runMain(
            "SIF VALID_COUNT == 0",
            "\tRETURN 0, 0",
            "CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)",
        )
        r.store.set0("VALID_COUNT", 0L)
        r.store.set0("FINAL_COUNT", 0L)
        r.runtime.execute()
        assertTrue(r.rand.bounds.isEmpty(), "空池分支没走 RAND：${r.rand.bounds}")
        assertEquals(0L, r.store.i0("CHOSEN_ID"), "早退 ⇒ 抽中 id 保持 0（RAND 行根本没执行）")
    }

    // ------------------------------------------------------------------
    // ④ 小鈴簇：上界 = 被调 #FUNCTIONS 留下的全局 RESULT
    // ------------------------------------------------------------------

    /**
     * 4.980 `M_KOJO_K70_01_イベント.ERB:247-265` 的 `#FUNCTIONS M_KOJO_K70_KOSUZUBOOM()`：
     * `RESULT = 5`（原文注释「種類数をここで設定」）+ **没有 CASEELSE** 的 SELECTCASE，
     * `CFLAG:1080 % 10` 落在 1–6 之外就直接落尾。CASE 值与 RETURNF 条数照抄。
     */
    private val kosuzuBoomBody = listOf(
        "@KOSUZUBOOM",
        "RESULT = 5",
        "SELECTCASE BOOMFLAG % 10",
        "\tCASE 1",
        "\t\tRETURNF \"a\"",
        "\tCASE 2",
        "\t\tRETURNF \"b\"",
        "\tCASE 3",
        "\t\tRETURNF \"c\"",
        "\tCASE 4",
        "\t\tRETURNF \"d\"",
        "\tCASE 5",
        "\t\tRETURNF \"e\"",
        "\tCASE 6",
        "\t\tRETURNF \"f\"",
        "ENDSELECT",
    )

    /** 4.980 `:6085-6091` 的抽样环（前回のブーム / CFLAG 换成标量 PREV_BOOM / BOOMFLAG）。 */
    private val kosuzuLoop = listOf(
        "WHILE !BOOMFLAG",
        "\tCALLF KOSUZUBOOM()",
        "\tBOOM = RAND:RESULT + 1",
        "\tSIF BOOM != PREV_BOOM",
        "\t\tBOOMFLAG = BOOM",
        "WEND",
    )

    @Test
    fun `小鈴 被调方法掉尾保留 RESULT 上界读到五`() {
        // 现场：进 WHILE 时 BOOMFLAG（= 原版 CFLAG:1080）必为 0（:6084 刚 `= 0`），
        // 于是 `0 % 10` 不命中任何 CASE ⇒ 被调 #FUNCTIONS 在 `RESULT = 5` 之后**落尾**。
        // 原版 Process.cs:1059-1066 的清零带 `if (!state.IsFunctionMethod)` 前缀 ⇒ 方法帧保留，
        // 上界 = 5 ⇒ `RAND:5 + 1` ∈ 1..5，不抛。
        // 移植侧早期无条件投 `RESULT = 0` ⇒ 上界 0 ⇒ 本簇 15 份「启动失败」的全部来源。
        val r = runWithMethod("KOSUZUBOOM", kosuzuLoop, kosuzuBoomBody)
        r.store.set0("BOOMFLAG", 0L)
        r.store.set0("PREV_BOOM", 0L)
        r.runtime.execute()
        assertEquals(listOf(5L), r.rand.bounds, "RAND 只发起一次，上界必须是方法体留下的 5")
        assertEquals(5L, r.store.i0("BOOM"), "BOOM = RAND:5 + 1 = 4 + 1（桩按 bound-1 返回）")
        assertEquals(5L, r.store.i0("BOOMFLAG"), "BOOM != PREV_BOOM ⇒ BOOMFLAG 落值、循环出体")
    }

    @Test
    fun `小鈴 CASE 命中走 RETURNF 时上界同样是五`() {
        // 对照：命中 CASE 时 RETURNF 直接出体，RESULT 一样保留
        // （`#FUNCTIONS` 的返回走 _RETS 通道，与 RESULT 无关）。
        val r = runWithMethod("KOSUZUBOOM", listOf("CALLF KOSUZUBOOM()", "G0:0 = RAND:RESULT"), kosuzuBoomBody)
        r.store.set0("BOOMFLAG", 3L)
        r.runtime.execute()
        assertEquals(listOf(5L), r.rand.bounds, "RETURNF 出体同样不得清 RESULT")
        assertEquals(4L, r.store.i0("G0"), "RAND:5 的桩读数 = 4")
    }

    @Test
    fun `小鈴 落尾清零只豁免方法帧`() {
        // 反向可证：若把闸做成「所有函数都不清」，非方法 label 掉尾就会留下 5 —— 那是第二个 bug。
        // 原版 Process.cs:1061-1063 只对 `!IsFunctionMethod` 清零；本用例把豁免面钉在方法上。
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), parse("G1:0 = RESULT"), setOf("MAIN"))
        c.compileFunction(FuncSig("PLAIN", 0, isMethod = false), parse("@PLAIN", "RESULT = 5"), setOf("MAIN", "PLAIN"))
        val metas = listOf(
            FuncMeta(index = c.functions.first { it.sig.name == "MAIN" }.index, key = "MAIN", name = "MAIN", position = "randzero.erb"),
            FuncMeta(index = c.functions.first { it.sig.name == "PLAIN" }.index, key = "PLAIN", name = "PLAIN", position = "randzero.erb"),
        )
        val rand = RecordingRand()
        val store = VariableStore().apply {
            randProvider = rand
            declareUserVar(DimSpec("G1", isStr = false, dims = intArrayOf(1)))
        }
        ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()).execute()
        // MAIN 自己也是非方法 ⇒ 尾清；被调 PLAIN 掉尾先清 RESULT=5 再回，MAIN 读到 0。
        assertEquals(0L, store.i0("G1"), "非方法函数掉尾清零（原版口径），豁免面不得扩大")
    }
}
