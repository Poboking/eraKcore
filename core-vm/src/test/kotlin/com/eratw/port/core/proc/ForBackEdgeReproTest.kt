package com.eratw.port.core.proc

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 红 K（GET_MAPID-SPIN）最小同形复现（2026-10-25，三变体全绿 = 桌面排除证据）。
 *
 * 真机现场（第十六程取证）：`GMF_METHOD_RESTORE`（幻想乡地图重修.ERB:70）四条 FOR 连排，
 * 前三条（字面终值）走完，第四条 `FOR LOCAL,0,CHARANUM`（变量终值）计数器恒 0 死转，
 * trace 只见环体(#22608)↔步进(#22609) 正常交替执行。本装置镜像该形态：前环字面终值 +
 * 双数组写体，后环变量终值 + `CALL` 进 `#FUNCTION` + 模运算三元体，步数闸 2 万。
 *
 * **三个变体在桌面全部正常步进**（直编 / erbc 往返 / 字面量对照），配合
 * RedKDisasmProbeTest 的「真产物字节码健康」读数：红 K 病灶不在编译器面与 erbc 往返面，
 * 需要真语料运行态（CFLAG 数据 / 会话状态）才触发 —— 留设备侧取证；本组测试是
 * 「同形不转」的排除证据锁，任何后续编译器/编码改动若让这三条变红即是回归。
 */
class ForBackEdgeReproTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("X", OperandType.INT, 1)
            .declareVariable("Y", OperandType.INT, 1)
            .declareVariable("CNT", OperandType.INT, 1)
            .declareVariable("N", OperandType.INT, 1)
            .declareFunction("GETMAPID", OperandType.INT)
            .declareMethod("GETMAPID"),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
    }

    /** 镜像红 K 的双环形态；`varEnd` 切换后环终值形态（字面量 / 变量 N）。 */
    private fun body(varEnd: Boolean): Array<String> = arrayOf(
        "@SYSTEM_TITLE",
        "N = 4",
        "FOR LOCAL, 0, 100",
        "X:LOCAL = LOCAL",
        "Y:LOCAL = LOCAL",
        "NEXT",
        if (varEnd) "FOR LOCAL, 0, N" else "FOR LOCAL, 0, 4",
        "CALL GETMAPID, Y:LOCAL",
        "CNT = (RESULT == 1 && LOCAL % 100 != 0 && LOCAL % 100 != 99) ? LOCAL # 0",
        "NEXT",
        "PRINTFORML DONE X={X} CNT={CNT}",
        "@GETMAPID(ARG)",
        "#FUNCTION",
        "RETURNF ARG / 100",
    )

    private data class Built(
        val program: com.eratw.port.core.code.Program,
        val metadata: BootMetadata,
    )

    private fun build(varEnd: Boolean): Built {
        val compiler = ErbProgramCompiler()
        val stmts = parse(*body(varEnd))
        val title = stmts.drop(1).takeWhile { it !is FuncLabelStmt }
        val getmap = stmts.drop(1).dropWhile { it !is FuncLabelStmt }.drop(1)
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"), title, setOf("SYSTEM_TITLE", "GETMAPID"),
        )
        // 形参必须显式声明：原版 `CALL F(x)` 时 `ConvertArg` 按 `TopLabel.Arg.Length` 建数组，
        // 未声明形参（arity=0）⇒ TooManyFuncArgs（CalledFunction.cs:136-140）。本骨架
        // `CALL GETMAPID, Y:LOCAL` 传 1 个实参，故 `@GETMAPID(ARG)` 的 arity 必须是 1。
        val gfb: FunctionBytecode = compiler.compileFunction(
            FuncSig("GETMAPID", arity = 1, isMethod = true, paramNames = listOf("ARG")),
            getmap,
            setOf("SYSTEM_TITLE", "GETMAPID"),
        )
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels, "GETMAPID" to gfb.labels),
            dataSections = emptyMap(),
            globalDims = listOf(
                BootMetadata.RawDim("#DIM GLOBAL X, 100", "TEST.ERB", 1),
                BootMetadata.RawDim("#DIM GLOBAL Y, 100", "TEST.ERB", 2),
                BootMetadata.RawDim("#DIM GLOBAL CNT", "TEST.ERB", 3),
                BootMetadata.RawDim("#DIM GLOBAL N", "TEST.ERB", 4),
            ),
            privateDims = emptyList(),
            functions = listOf(
                FuncMeta(index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE", defIndex = 0, position = "TEST.ERB:1"),
                FuncMeta(index = gfb.index, key = "GETMAPID", name = "GETMAPID", defIndex = gfb.index, position = "TEST.ERB:12"),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB", "GETMAPID" to "TEST.ERB"),
        )
        return Built(program, metadata)
    }

    private fun sessionOf(b: Built, dir: Path): GameSession = GameSession(
        gameRoot = dir,
        program = b.program,
        metadata = b.metadata,
        randSeed = 20260226L,
        maxStepsPerRun = 20_000,
    )

    @Test
    fun `变量终值后环正常步进 字面量形态对照`(@TempDir dir: Path) {
        // 对照组：后环终值也是字面量（红 K 现场前三条环的形态）。
        val s = sessionOf(build(varEnd = false), dir)
        try {
            assertTrue(s.begin(BeginType.TITLE), "对照组应正常执行完（转了会 StepLimitExceeded）")
            assertTrue(
                s.console.loggedLines().any { it.startsWith("DONE X=0 CNT=0") },
                "实为 ${s.console.loggedLines().firstOrNull()}",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `变量终值后环正常步进 红K形态`(@TempDir dir: Path) {
        // 红K形态：前环字面终值、后环变量终值（CHARANUM 同位）+ CALL 进 #FUNCTION + 三元体。
        val s = sessionOf(build(varEnd = true), dir)
        try {
            assertTrue(s.begin(BeginType.TITLE), "红K形态若复现将在此抛 StepLimitExceeded")
            assertTrue(
                s.console.loggedLines().any { it.startsWith("DONE X=0 CNT=0") },
                "实为 ${s.console.loggedLines().firstOrNull()}",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `erbc 往返后红K形态不转`(@TempDir dir: Path) {
        // 分叉维度③：同一程序经 ErbcWriter→loadMapped 往返后执行（设备装载路径形态）。
        val b = build(varEnd = true)
        val encoded = ErbcWriter.encodeIndexed(b.program)
        val tmp = Files.createDirectories(dir.resolve("erbc.v1"))
        Files.write(tmp.resolve("program.erbc"), encoded.bytes)
        val loaded = ProgramLoader.loadMapped(tmp.resolve("program.erbc")) { }
        val s = sessionOf(Built(loaded, b.metadata), dir)
        try {
            assertTrue(s.begin(BeginType.TITLE), "erbc 往返后红K形态转了（往返回归）")
            assertTrue(
                s.console.loggedLines().any { it.startsWith("DONE X=0 CNT=0") },
                "实为 ${s.console.loggedLines().firstOrNull()}",
            )
        } finally {
            s.close()
        }
    }
}
