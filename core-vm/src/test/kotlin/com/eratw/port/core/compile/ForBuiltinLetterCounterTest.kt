package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
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
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **FOR 计数器的读写同位锁**（M10 真机开局 StepLimitExceeded 的本路判定）。
 *
 * 现场：真机 `.erbc`（2026-07-17 构建）里 `FOR I, 0, 35` 的初始化/步进走 `_SET_INT/
 * _PLUS_INT` **帧槽**写，条件走 `_EVAL_INT VarRef` **VariableStore** 读，帧拆弃槽 ⇒
 * 每圈读到初值、永不收口。现行源码的契约 C1 已把「具名变量的写」唯一收进语句池
 * （spec: ErbProgramCompiler.kt compileFor 的 synthAssign/marker，KDoc:906-912
 * 「具名变量一律不进 VM 槽」；执行路 sem/StatementExecutor.kt:1552 execAssign →
 * resolveRef → VariableStore），条件读 `_EVAL_INT` 同走 VariableStore
 * （spec: Vm.kt:668 + Expr.kt:89 VarRef.getIntValue）。本组用例把这条口径钉死：
 * JVM 产线（真 VariableStore + 真 ProcessRuntime）与**真机同款 erbc 往返**都必须收口。
 *
 * ## 计数器名字的三种归属与原版口径
 *
 * - **裸单字母 A..Z = 内建 1 维整型系统变量**（spec: VariableData.cs:3040 "A"、
 *   :3048 "I"、:3065 "Z" `varTokenDic.Add(..., new Int1DVariableToken(...))`；
 *   码表 spec: VariableCode.cs:55-89 `A = 720926 … Z = 720951`）。
 *   `GetVariableToken` 在 varTokenDic 命中（spec: IdentifierDictionary.cs:475-489），
 *   不进未声明报错分支 ⇒ `FOR I, 0, 35` 合法、可收口。移植侧同名册：
 *   stmt/BuiltinSymbols.kt（"A".."Z" INT dim1）+ vars/SystemLayout.kt:41-46
 *   （进 registry G_INT1）⇒ 读写同落 `gInt1[I列][0]`（spec: VariableStore.kt:127/206）。
 * - **函数私有 `#DIM`（STATIC/DYNAMIC）**：读写同落 `privateVars[funcKey]`
 *   （spec: VariableStore.kt:903-913 userVar 先查当前帧函数表；作用域键 = 函数名，
 *   递归共享不回滚，spec: IdentifierDictionary.cs:454-471 —— 与帧槽生命周期**无关**）。
 *   私有与内建字母重名在原版直接注册失败（spec: VariableData.cs:3040 起 varTokenDic
 *   已有 "I"，重名 Add 抛；移植侧 declareUserVar 同口径 VariableStore.kt:810
 *   「与系统变量重名」），故私有计数器用非内建名。
 * - **完全未声明、也不在内建名册的名字**：解析期 CodeEE
 *   （spec: IdentifierDictionary.cs:628 `IdentifierNotFoundCodeEE(CanNotInterpreted)`，
 *   调用点 spec: ExpressionParser.cs:444）⇒ 移植侧解析器抛 [StmtParseException]
 *   （内层为 expr 层 ExprParseException「未定义的标识符」），
 *   **不可能**进循环。
 *
 * FOR 边界本体（契约 R4，spec: FunctionIdentifier.cs:3003-3006/:3401-3403）不在本组
 * 重复钉 —— ErbProgramCompilerTest 已有排他界行为锁。
 */
class ForBuiltinLetterCounterTest {

    private fun parser() =
        LineParser(
            SymbolTable()
                .declareVariable("A", OperandType.INT, 1)
                .declareVariable("CNT", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "for-counter.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parse(*lines), setOf("T"))
        return c.build("T")
    }

    /** 真产线跑一圈；maxSteps 卡 50k —— FOR 不收口必撞 StepLimit，绝无可能假绿。 */
    private fun run(program: Program, setup: (VariableStore) -> Unit = {}): VariableStore {
        val store = VariableStore()
        setup(store)
        val meta = FuncMeta(index = 0, key = "T", defIndex = 0)
        ProcessRuntime(program, store, FunctionTable(listOf(meta)), VmConfig(maxStepsPerRun = 50_000L)).execute()
        return store
    }

    @Test
    fun `FOR 内置I 排他界跑满35轮且计数器落终值`() {
        val store = run(
            compile(
                "@T",
                "FOR I, 0, 35",
                "J = J + 1",
                "NEXT",
            ),
        )
        assertEquals(35L, store.readInt("I", longArrayOf(0)), "NEXT 先步进后判定，终值=35（spec: FunctionIdentifier.cs:3401-3403）")
        assertEquals(35L, store.readInt("J", longArrayOf(0)), "体恰执行 35 轮（I=0..34，契约 R4）")
    }

    @Test
    fun `FOR 内置I 经erbc往返后仍收口（真机加载路径）`() {
        val before = compile(
            "@T",
            "FOR I, 0, 35",
            "J = J + 1",
            "NEXT",
        )
        val after = ErbcReader.decode(ErbcWriter.encode(before))
        val store = run(after)
        assertEquals(35L, store.readInt("I", longArrayOf(0)), "erbc 往返后 INIT/STEP 语句池 marker 与条件读同一落 gInt1[I列][0]")
        assertEquals(35L, store.readInt("J", longArrayOf(0)))
    }

    @Test
    fun `FOR 私有DIM计数器 写语句池读同位收口`() {
        // 私有 #DIM（STATIC/DYNAMIC 家族）计数器：写经语句池 marker → userVar → privateVars["T"]，
        // 条件 _EVAL_INT 读同路（spec: VariableStore.kt userVar/903 与 writeInt/188 同一张表），
        // 与帧拆无关 —— 这正是旧 .erbc 帧槽写丢增量的反面对照。
        val program = compile(
            "@T",
            "FOR CNT, 0, 35",
            "J = J + 1",
            "NEXT",
        )
        val store = run(program) { s ->
            s.declareUserVar(DimSpec("CNT", isStr = false, dims = intArrayOf(100), isPrivate = true), "T")
        }
        val cnt = store.privateUserVars("T").first { it.def.name == "CNT" }
        assertEquals(35L, cnt.readInt(0, 0), "私有计数器终值=35：读写同位，帧拆不弃槽（spec: IdentifierDictionary.cs:454-471）")
        assertEquals(35L, store.readInt("J", longArrayOf(0)), "体恰 35 轮")
    }

    @Test
    fun `非内建未声明名解析期拒绝（原版CanNotInterpreted方向）`() {
        val e = assertFailsWith<StmtParseException> {
            parse("@T", "FOR QQ, 0, 2", "NEXT")
        }
        assertTrue(e.message!!.contains("QQ"), "未声明名必须点名报错（spec: IdentifierDictionary.cs:628）：${e.message}")
    }

    // ------------------------------------------------------------------
    // 真语料形态两条（2026-10-21 三台设备同 pc=717295 的现场形态）：
    // 上面三条的体都是 `J = J + 1` 这种单赋值语句；真机的体是
    //   SPRITECREATE "TW_title"+TOSTR(I, "D3"), RM_TITLE, 0, I*16, 1041, 16
    // —— 带**表达式求值**（字符串拼接 + 内建函数 + 乘法），且这个函数不是根帧，
    // 而是 `TITLE.ERB:29` 用 `CALL RM_タイトル画像用意` 调进来的（全语料仅此一处调用点）。
    // 这两件事都可能让体内求值踩到 FOR 自己占的帧槽（`_LT` 比较的 slot1/slot0），
    // 于是条件永远读不到步进后的 `I`。上面三条覆盖不到，必须单独钉。
    // ------------------------------------------------------------------

    /** 与真语料同符号表口径：`RM_TITLE` 是全局 1 维整型（语料里它是 `#DIM CONST`，此处按变量喂）。 */
    private fun corpusParser() =
        LineParser(
            SymbolTable()
                .declareVariable("RM_TITLE", OperandType.INT, 1),
        )

    private fun corpusParse(vararg lines: String): List<Statement> {
        val p = corpusParser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "resource-manager.erb", i + 1, text))
        }
    }

    private fun compileCorpus(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), corpusParse(*lines), setOf("T"))
        return c.build("T")
    }

    /** 真语料那一段的体（行序与 `ResourceManager.ERB:5-10` 一致，只把 SIF/GCREATEFROMFILE 摘掉：崩溃点不在那里）。 */
    private val corpusBody = arrayOf(
        "FOR I, 0, 35",
        "SPRITECREATE \"TW_title\"+TOSTR(I, \"D3\"), RM_TITLE, 0, I*16, 1041, 16",
        "NEXT",
    )

    /** 语料里 `RM_TITLE` 是 `#DIM CONST RM_TITLE = 100000`（ResourceManager.ERH:15）→ 全局整型。 */
    private fun declareRmTitle(store: VariableStore) {
        store.declareUserVar(DimSpec("RM_TITLE", isStr = false, dims = intArrayOf(1), isPrivate = false))
        store.writeInt("RM_TITLE", longArrayOf(0), 100000L)
    }

    @Test
    fun `FOR 内置I 体为真语料SPRITECREATE表达式时仍收口`() {
        val store = run(
            compileCorpus("@T", *corpusBody),
            ::declareRmTitle,
        )
        assertEquals(35L, store.readInt("I", longArrayOf(0)), "体内带 TOSTR/拼接/乘法的表达式求值不得踩坏 FOR 的帧槽")
    }

    @Test
    fun `FOR 内置I 在被CALL进来的非根帧里仍收口`() {
        val c = ErbProgramCompiler()
        val outer = c.compileFunction(FuncSig("T"), corpusParse("@T", "CALL RM", "PRINTL DONE"), setOf("T", "RM"))
        val inner = c.compileFunction(FuncSig("RM"), corpusParse("@RM", *corpusBody), setOf("T", "RM"))
        val program = c.build("T")
        val store = VariableStore()
        declareRmTitle(store)
        ProcessRuntime(
            program,
            store,
            FunctionTable(
                listOf(
                    FuncMeta(index = outer.index, key = "T", defIndex = 0),
                    FuncMeta(index = inner.index, key = "RM", defIndex = 1),
                ),
            ),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        assertEquals(
            35L,
            store.readInt("I", longArrayOf(0)),
            "CALL 进来的帧（frameBase 非 0）里，FOR 的终值/计数临时槽与步进必须同位收口",
        )
    }

    /**
     * **生产装载档**：真机走 `ProgramLoader.loadMapped`（`ErbcStream` 的**惰性语句池**），
     * `statement(i)` 由 `recordOffsets` + `lazyEnsure` 按字节偏移现场重建
     * （`loader/ErbcStream.kt:944-957`、`:1277-1290`）。
     *
     * 上面全部用例走的都是急切档（`ErbcReader.decode` / `ErbcWriter.encode` 内存往返），
     * 而 2026-10-21 三台设备（vivo V2199A + 两台 FTL MediumPhone.arm）在同 pc=717295 撞闸时
     * A..Z 全为 0 —— 步进那条语句在设备上实际没推进 `I`。惰性档是唯一还没被任何用例执行过的装载路径
     * （`loader/LazyStatementDecodeTest` 只比 `class|code|line|raw|args 根字段`，
     * 从不比 `AssignStmt.op` 与 `dest`，也从不跑）。
     */
    @Test
    fun `FOR 内置I 经生产惰性档装载后仍收口`(@TempDir dir: Path) {
        val before = compile(
            "@T",
            "FOR I, 0, 35",
            "J = J + 1",
            "NEXT",
        )
        val f = dir.resolve("lazy-for.erbc")
        java.nio.file.Files.write(f, ErbcWriter.encode(before))

        val program = com.eratw.port.core.loader.ProgramLoader.loadMapped(f)
        assertEquals(0, program.materializedStatementCount, "loadMapped 必须是惰性档：返回时不该物化任何语句")

        val store = VariableStore()
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        assertEquals(35L, store.readInt("I", longArrayOf(0)), "惰性重建的 STEP 语句必须还是 PLUS/I[0]，否则 FOR 不收口")
        assertEquals(35L, store.readInt("J", longArrayOf(0)), "体恰 35 轮")
    }
}
