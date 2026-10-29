package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 红 J（NEWGAME-STEP-SPIN）机制复现：`JUEL_DEMAND` 返回值链（装置第十五程登记，复现 3/3）。
 *
 * 语料 `ABL.ERB:79-131` 的 `FOR LOCAL,0,4 { $AGAIN … GOTO AGAIN }` 是升级函数里唯一无界回边，
 * 终局**全**靠被调链的返回值：
 * - `JUEL_DEMAND_N` 的 `SIF ARG > 0 → RETURNF -1`（同一轮已升级过 ⇒ CALLER `< 0 → CONTINUE` 推进
 *   FOR 计数器）；
 * - 正值需求与 `JUEL` 比对不足 ⇒ `CONTINUE`；
 * - `== -2` ⇒ `BREAK`。
 * `GOTO AGAIN` 不推进 FOR 计数器 ⇒ 链上任一环把返回值吞成 0（既非 -1/-2、又无正值需求）⇒
 * `CONTINUE` 不可达 ⇒ LOCAL 永停 0 ⇒ `GOTO AGAIN` 死转，直到 5,000,001 步闸
 * （装置死点三轮全落 CALL→返回值消费点，与该模型吻合）。
 *
 * 被调链的机制形态（语料 `ABL_UP_DATA.ERB:1-53/:114-169` 原样缩样）：
 * 1. `JUEL_DEMAND_N`：`VARSET LOCAL` → 把需求写进**具名下标槽**（如 `LOCAL:11`）→
 *    `RETURNF LOCAL:(ARG:1)` **动态下标**读回（ARG:1 = 调用方扫到的宝珠种类）；
 * 2. 分派器 `JUEL_DEMAND`：`#DIM RETV` 私有声明接被调返回值 → `SIF RETV < 0 → RETURNF R` →
 *    `RETURNF R`（真语料是 `CALC_ABL_DEMAND` 缩放，与返回值传播无关，不进本复现）。
 *
 * 本类用**真管线**（LineParser + ErbProgramCompiler + ExprCallTable 降级 + 真 [VariableStore]
 * 帧 + [ProcessRuntime]）逐段锁死：动态读、分派器传播、`-1` 终局、整循环终止。
 * 修复权 core-vm（第十五程嫌疑①三连抬档：`LOCAL:(ARG:1)` 动态下标返回槽 / 表达式位调用
 * 返回值链）。
 */
class JuelDemandChainReproTest {

    // ------------------------------------------------------------------
    // 夹具：语料机制的缩样（ABL_UP_DATA.ERB 同构）
    // ------------------------------------------------------------------

    /** 缩样源码：分派器 + 被调（写 LOCAL:11/12，按 ARG:1 动态读回）。形参全走 ARG 位（真函数同形）。 */
    private val demandChain = listOf(
        "@DEMAND(ARG, ARG:1, ARG:2)",
        "#FUNCTION",
        "#DIM RETV",
        "IF ARG == 0",
        "\tRETV = DEMAND_N(ARG:1, ARG:2)",
        "ENDIF",
        "SIF RETV < 0",
        "\tRETURNF RETV",
        "RETURNF CALC(ARG, RETV)",
        "",
        "@CALC(ARG, ARG:1)",
        "#FUNCTION",
        "SIF (RAIT:ARG) == 0",
        "\tRAIT:ARG = 100",
        "SIF ARG:1 == 0",
        "\tRETURNF 0",
        "RETURNF MAX(1, ARG:1 * 100 / RAIT:ARG)",
        "",
        "@DEMAND_N(ARG, ARG:1)",
        "#FUNCTION",
        "JEWELS:30 = ARG",
        "JEWELS:31 = ARG:1",
        "SIF ARG > 1",
        "\tRETURNF -1",
        "VARSET LOCAL",
        "IF ARG == 0",
        "\tSELECTCASE ARG:1",
        "\t\tCASE 11",
        "\t\t\tLOCAL:11 = 200",
        "\t\tCASE 12",
        "\t\t\tLOCAL:12 = 30",
        "\t\tCASEELSE",
        "\t\t\tLOCAL:11 = 0",
        "\tENDSELECT",
        "ELSEIF ARG == 1",
        "\tLOCAL:0 = 1000",
        "ENDIF",
        "\tTIMES LOCAL:11 , 0.50",
        "\tTIMES LOCAL:12 , 0.50",
        "\tRETURNF LOCAL:(ARG:1)",
    )

    /** 直读链的三连发（绕开循环，逐环断言）。DEMAND(ARG,ARG:1,ARG:2) 的 ARG:1=PAT、ARG:2=IDX。 */
    private val directProbe = listOf(
        "@SYSTEM_TITLE",
        "JEWELS:0 = DEMAND_N(0, 11)",
        "JEWELS:1 = DEMAND_N(0, 12)",
        "JEWELS:2 = DEMAND_N(0, 0)",
        "JEWELS:3 = DEMAND_N(1, 0)",
        "JEWELS:6 = DEMAND_N(2, 0)",
        "JEWELS:4 = DEMAND(0, 0, 11)",
        "JEWELS:5 = DEMAND(0, 1, 0)",
        "JEWELS:7 = DEMAND(0, 2, 0)",
    )

    /** ABL.ERB:79-131 的循环形状（FOR + $AGAIN + GOTO AGAIN + 不足闸 + -1 终局）。 */
    private val spinLoop = listOf(
        "@SYSTEM_TITLE",
        "JEWELS:11 = 250",
        "JEWELS:12 = 500",
        "FOR LOCAL, 0, 4",
        "\t\$AGAIN",
        "\tSIF DEMAND(0, LOCAL, 0) == -2",
        "\t\tBREAK",
        "\tSIF DEMAND(0, LOCAL, 0) < 0",
        "\t\tCONTINUE",
        "\tLOCAL:6 = 0",
        "\tFOR LOCAL:1, 0, 120",
        "\t\tIF DEMAND(0, LOCAL, LOCAL:1) > 0",
        "\t\t\tIF JEWELS:(LOCAL:1) < DEMAND(0, LOCAL, LOCAL:1)",
        "\t\t\t\tLOCAL:6 = LOCAL:6 + 1",
        "\t\t\t\tBREAK",
        "\t\t\tENDIF",
        "\t\tENDIF",
        "\tNEXT",
        "\tSIF LOCAL:6",
        "\t\tCONTINUE",
        "\tFOR LOCAL:1, 0, 120",
        "\t\tJEWELS:(LOCAL:1) -= DEMAND(0, LOCAL, LOCAL:1)",
        "\tNEXT",
        "\tGOTO AGAIN",
        "NEXT",
        "PRINTL DONE",
    )

    // ------------------------------------------------------------------
    // 装置：真 LineParser + 真 ErbProgramCompiler（表达式位降级表）+ 真 VariableStore
    // ------------------------------------------------------------------

    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("JEWELS", OperandType.INT, 1)
                .declareVariable("RAIT", OperandType.INT, 1)
                // RETV = DEMAND 的私有 #DIM：裸解析器没有 ScopedResolver 的 dimLines 面，
                // 解析期先按普通变量名放行；运行期由 store 的私有帧（functionKey=DEMAND）解析。
                .declareVariable("RETV", OperandType.INT, 1)
                .declareFunction("DEMAND", OperandType.INT)
                .declareFunction("DEMAND_N", OperandType.INT)
                .declareFunction("CALC", OperandType.INT),
        )
        return lines.mapIndexedNotNull { i, raw ->
            val text = raw.trimStart()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "redj-repro.erb", i + 1, text))
        }
    }

    /** 多函数编译；`isMethod` 全按方法（体带 RETURNF，原版即 #FUNCTION 形态）。 */
    private fun compile(
        entry: String,
        funcs: List<Pair<String, List<String>>>,
    ): Program {
        val c = ErbProgramCompiler()
        val known = funcs.map { it.first.uppercase() }.toSet()
        val tbl = ExprCallTable(intMethods = setOf("DEMAND", "DEMAND_N", "CALC"), strMethods = emptySet(), nonMethodLabels = emptySet())
        for ((name, lines) in funcs) {
            // paramNames = 头部形参项**原文**（带下标，FuncLabelStmt.paramRefs 同形；生产
            // CorpusCompile.kt:639 传的就是它）——bindCallArgs 按 `split(':')` 取 ARG:i 的 i。
            val (arity, params) = when (name) {
                "DEMAND" -> 3 to listOf("ARG", "ARG:1", "ARG:2")
                "DEMAND_N" -> 2 to listOf("ARG", "ARG:1")
                "CALC" -> 2 to listOf("ARG", "ARG:1")
                else -> 0 to emptyList()
            }
            c.compileFunction(
                FuncSig(name, arity, isMethod = name != entry, paramNames = params),
                parseErbLines(*lines.toTypedArray()),
                known,
                tbl,
            )
        }
        return c.build(entry)
    }

    private fun newStore(): VariableStore = VariableStore().also {
        // 全局宝珠数组 + 升级计数 + 不足旗标（原版语义里是 JUEL/ABL；缩样用用户全局承载终局状态）。
        // 计数/旗标按 1 维声明（解析期 SymbolTable 同为 1 维，`X += 1` 读的是 X[0]）。
        it.declareUserVar(DimParser.parse("#DIM", "JEWELS, 120", isPrivate = false))
        // CALC 的自举目标：GLOBAL 用户数组（真语料是 ERH 的 #DIM GLOBAL SAVEDATA ABL_UP_RAIT, 100）
        it.declareUserVar(DimParser.parse("#DIM", "RAIT, 100", isPrivate = false))
        // DEMAND 的 `#DIM RETV` 私有声明（切片 dimLines → 私有注册，语料装载同口径）
        it.declareUserVar(DimParser.parse("#DIM", "RETV", isPrivate = true), functionKey = "DEMAND")
    }

    private fun runProgram(program: Program, maxSteps: Long = 500_000L): Pair<VariableStore, StringBuilder> {
        val store = newStore()
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i, key = f.name.uppercase(),
                    argIntCount = f.arity,
                )
            },
        )
        val out = StringBuilder()
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = maxSteps), printSink = { out.append(it) }).execute()
        return store to out
    }

    // ------------------------------------------------------------------
    // 逐环锁
    // ------------------------------------------------------------------

    @Test
    fun `被调动态下标读回与分派器传播逐环正确`() {
        directProbe.forEachIndexed { i, l -> println("[src] $i: $l") }
        val p = compile(
            "SYSTEM_TITLE",
            listOf(
                "SYSTEM_TITLE" to directProbe,
                "DEMAND" to demandChain.subList(0, 9),
                "DEMAND_N" to demandChain.subList(demandChain.indexOf("@DEMAND_N(ARG, ARG:1)"), demandChain.size),
                "CALC" to demandChain.subList(10, demandChain.indexOf("@DEMAND_N(ARG, ARG:1)")),
            ),
        )
        val (store, _) = runProgram(p)
        // 被调自观测：最后一次直调是 (2,0)（其后的 DEMAND(0,·,·) 是另一函数的帧）
        assertEquals(2L, store.readInt("JEWELS", longArrayOf(30)), "被调最后一次看到的 ARG")
        assertEquals(0L, store.readInt("JEWELS", longArrayOf(31)), "被调最后一次看到的 ARG:1")
        assertEquals(100L, store.readInt("JEWELS", longArrayOf(0)), "DEMAND_N(0,11)：写 LOCAL:11 → TIMES 0.50 → 动态读回")
        assertEquals(15L, store.readInt("JEWELS", longArrayOf(1)), "DEMAND_N(0,12)：同上，第二具名槽")
        assertEquals(0L, store.readInt("JEWELS", longArrayOf(2)), "DEMAND_N(0,0)：未写槽 VARSET 后读 0")
        assertEquals(1000L, store.readInt("JEWELS", longArrayOf(3)), "DEMAND_N(1,0)：模式 1（快Ｃの珠）→ LOCAL:0=1000")
        assertEquals(-1L, store.readInt("JEWELS", longArrayOf(6)), "DEMAND_N(2,0)：SIF ARG>1 → RETURNF -1（终局路径）")
        assertEquals(100L, store.readInt("JEWELS", longArrayOf(4)), "DEMAND(0,0,11)：分派器 RETV 接被调返回值过 CALC 自举")
        assertEquals(1000L, store.readInt("JEWELS", longArrayOf(5)), "DEMAND(0,1,0)：分派器透传模式 1 需求")
        assertEquals(-1L, store.readInt("JEWELS", longArrayOf(7)), "DEMAND(0,2,0)：分派器透传 -1")
    }

    @Test
    fun `序列调用的 ARG 绑定二分`() {
        // 最小序列：先 (1,0) 再 (2,0)。单次 (2,0) 是对的（独立用例），若两连后 (2,0) 破
        // ⇒ 前次调用污染了 ARG 绑定面（localsByFunc 按函数持久、跨调用不回滚）。
        val p = compile(
            "SYSTEM_TITLE",
            listOf(
                "SYSTEM_TITLE" to listOf(
                    "@SYSTEM_TITLE",
                    "JEWELS:0 = DEMAND_N(1, 0)",
                    "JEWELS:1 = DEMAND_N(2, 0)",
                ),
                "DEMAND" to demandChain.subList(0, 9),
                "CALC" to demandChain.subList(10, demandChain.indexOf("@DEMAND_N(ARG, ARG:1)")),
                "DEMAND_N" to demandChain.subList(demandChain.indexOf("@DEMAND_N(ARG, ARG:1)"), demandChain.size),
            ),
        )
        val (store, _) = runProgram(p)
        assertEquals(1000L, store.readInt("JEWELS", longArrayOf(0)), "第一次 DEMAND_N(1,0) → 1000")
        assertEquals(-1L, store.readInt("JEWELS", longArrayOf(1)), "第二次 DEMAND_N(2,0) → -1")
    }

    @Test
    fun `升级循环按原版语义终止且扣款正确`() {
        val p = compile(
            "SYSTEM_TITLE",
            listOf("SYSTEM_TITLE" to spinLoop) +
                listOf(
                    "DEMAND" to demandChain.subList(0, 9),
                    "CALC" to demandChain.subList(10, demandChain.indexOf("@DEMAND_N(ARG, ARG:1)")),
                    "DEMAND_N" to demandChain.subList(demandChain.indexOf("@DEMAND_N(ARG, ARG:1)"), demandChain.size),
                ),
        )
        val (store, out) = runProgram(p)
        assertEquals(50L, store.readInt("JEWELS", longArrayOf(11)), "恰好一轮买得起：250 - 200")
        assertEquals(470L, store.readInt("JEWELS", longArrayOf(12)), "500 - 30")
        assertTrue(out.contains("DONE"), "循环必须终止（broken chain 会在这里撞步数闸）: $out")
    }

    @Test
    fun `容器往返后升级循环仍按原版语义终止`() {
        // 设备跑的是 .erbc 容器：内存编译（桌面）有的 paramNames/callArgs 要靠 v2 的
        // SECTION_PARAM_NAMES / SECTION_CALL_ARGS 两段活过序列化。本用例 = 设备面的等价复现：
        // 编译 → encodeIndexed（生产编码档）→ 落盘 → ProgramLoader.loadMapped（生产装载档：
        // 流式 mapped · 惰性池，带预取线程）→ 跑。红 ⇒ 容器/装载路径丢了绑定元数据
        // （bindCallArgs 退化成类型桶/不绑），与装置「桌面不复现、真机 3/3」的观测同构。
        val before = compile(
            "SYSTEM_TITLE",
            listOf("SYSTEM_TITLE" to spinLoop) +
                listOf(
                    "DEMAND" to demandChain.subList(0, 9),
                    "CALC" to demandChain.subList(10, demandChain.indexOf("@DEMAND_N(ARG, ARG:1)")),
                    "DEMAND_N" to demandChain.subList(demandChain.indexOf("@DEMAND_N(ARG, ARG:1)"), demandChain.size),
                ),
        )
        val encoded = com.eratw.port.core.code.ErbcWriter.encodeIndexed(before)
        val erbc = java.nio.file.Files.createTempFile("redj", ".erbc")
        java.nio.file.Files.write(erbc, encoded.bytes)
        // 生产装载档（mapped · 惰性池）
        val mapped = com.eratw.port.core.loader.ProgramLoader.loadMapped(erbc)
        runMapped(mapped)
        // 对照档：整池 eager decode
        val decoded = com.eratw.port.core.code.ErbcReader.decode(encoded.bytes)
        val (store, out) = runProgram(decoded)
        assertEquals(50L, store.readInt("JEWELS", longArrayOf(11)), "容器往返后：恰好一轮买得起 250 - 200")
        assertEquals(470L, store.readInt("JEWELS", longArrayOf(12)), "容器往返后：500 - 30")
        assertTrue(out.contains("DONE"), "容器往返后循环必须终止: $out")
    }

    private fun runMapped(program: Program) {
        val store = newStore()
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(index = i, key = f.name.uppercase(), argIntCount = f.arity)
            },
        )
        val out = StringBuilder()
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 500_000L), printSink = { out.append(it) }).execute()
        assertEquals(50L, store.readInt("JEWELS", longArrayOf(11)), "mapped 装载后：恰好一轮买得起 250 - 200")
        assertEquals(470L, store.readInt("JEWELS", longArrayOf(12)), "mapped 装载后：500 - 30")
        assertTrue(out.contains("DONE"), "mapped 装载后循环必须终止（设备红 J 的等价判据）: $out")
    }
}
