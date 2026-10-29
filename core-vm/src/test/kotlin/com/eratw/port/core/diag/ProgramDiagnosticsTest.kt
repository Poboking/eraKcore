package com.eratw.port.core.diag

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.StmtInternAudit
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ProgramDiagnostics] 对基线（原 `GameHost.kt:1111-1257`，main@ac0c7726）的逐行一致性。
 *
 * 判据（CONTRACT-ARCH1 §4 vmdiag / §5）：诊断输出文本与 GameHost 旧实现逐字节一致——
 * 真机 logcat 与 pool-audit.txt 的判读（2026-10-22 死循环事件复盘）靠格式，格式变了
 * 判据就废。本测试把旧实现的代表性输出**手抄**进断言；`System.identityHashCode` 与
 * `Program.toString` 里的身份哈希是每次 JVM 运行都漂的读数，用 [mask] 归一成 `@#`
 * 后再比对（旧实现在真机上同样逐次漂移，判读时本来就跳过这段）。
 *
 * 造程序用 [Assembler]（`WAIT stmt#k` 走契约 C3 的 marker 编码）+ [ErbcWriter.encode]
 * 落盘 + [ProgramLoader.loadMapped] 装载，复刻生产装载链；畸形路径用坏产物、越界窗口
 * 与越界 pc 走 runCatching 容错面。
 */
class ProgramDiagnosticsTest {

    // ==================================================================
    // 样题与工具
    // ==================================================================

    /** 赋值语句 `I = 5`（行 7）：dest 与 SpSetArgument.dest 同一 VarRef，brief 输出 `I[0]`。 */
    private fun assignI5(): AssignStmt {
        val dest = VarRef("I", listOf(IntLiteral(0L, 7, 0)), operandType = OperandType.INT, line = 7, col = 0)
        return AssignStmt(dest, OperatorCode.ASSIGNMENT, SpSetArgument(dest, IntLiteral(5L, 7, 0)), 7, "I = 5")
    }

    /** 赋值语句 `I += 1`（行 8）：op=PLUS、addConst=true，brief 输出 `I[0]` + `Int(1)`。 */
    private fun assignIPlus1(): AssignStmt {
        val dest = VarRef("I", listOf(IntLiteral(0L, 8, 0)), operandType = OperandType.INT, line = 8, col = 0)
        val sp = SpSetArgument(dest, IntLiteral(1L, 8, 0))
        sp.addConst = true
        return AssignStmt(dest, OperatorCode.PLUS, sp, 8, "I += 1")
    }

    /** WAIT 指令语句（行 9）：非 AssignStmt，brief 只有类名 + 行号。 */
    private fun waitStmt(): InstructionStmt = InstructionStmt(FunctionCode.WAIT, VoidArgument(), 9, "WAIT")

    /**
     * 语句池 3 条（2 条 AssignStmt + 1 条 WAIT）+ 3 条 marker + _HALT 的最小程序：
     * 汇编 → 落盘 → 按生产链装载，返回执行用的实例（与 GameHost 装载后传入同口径）。
     */
    private fun loadedProgram(artifact: Path): Program {
        val assembled = Assembler.assemble(
            """
            .func main slots=1
              WAIT stmt#0
              WAIT stmt#1
              WAIT stmt#2
              _HALT
            """,
            statements = listOf(assignI5(), assignIPlus1(), waitStmt()),
        )
        Files.write(artifact, ErbcWriter.encode(assembled))
        return ProgramLoader.loadMapped(artifact)
    }

    /** 身份哈希归一：`@<hex|十进制|负数>` → `@#`（旧实现真机读数里这段逐次漂移，判读跳过）。 */
    private fun mask(s: String): String = s.replace(Regex("@[0-9a-fA-F-]+"), "@#")

    // ==================================================================
    // 语句池对账（auditStatementPoolAt）
    // ==================================================================

    @Test
    fun `对账主路径（双实例一致 + 实例3 先物化 + shape 探针）`(@TempDir dir: Path) {
        val artifact = dir.resolve("good.erbc")
        val program = loadedProgram(artifact)
        val lines = ProgramDiagnostics.auditStatementPoolAt(program, artifact, 0, 2, 1)
        val masked = lines.map(::mask)

        // 语句池对账段（pool-audit 11 行）——旧 GameHost.auditStatementPoolAt 的逐行格式手抄：
        // 首行 stmtCount/second、每下标三行、实例3 一行；双实例一致时不打告警行。
        assertEquals(
            listOf(
                "pool-audit stmtCount=3 second=com.eratw.port.core.code.Program@#",
                "pool-audit #0",
                "  实例1(执行用) @# AssignStmt(line=7) dest=I[0] op=ASSIGNMENT spDest=I[0] spTerm=Int(5)",
                "  实例2(新装载) @# AssignStmt(line=7) dest=I[0] op=ASSIGNMENT spDest=I[0] spTerm=Int(5)",
                "pool-audit #1",
                "  实例1(执行用) @# AssignStmt(line=8) dest=I[0] op=PLUS spDest=I[0] spTerm=Int(1)",
                "  实例2(新装载) @# AssignStmt(line=8) dest=I[0] op=PLUS spDest=I[0] spTerm=Int(1)",
                "pool-audit #2",
                "  实例1(执行用) @# InstructionStmt(line=9)",
                "  实例2(新装载) @# InstructionStmt(line=9)",
                "pool-audit 实例3(先物化#1) @# AssignStmt(line=8) dest=I[0] op=PLUS spDest=I[0] spTerm=Int(1)",
            ),
            masked.subList(0, 11),
            "pool-audit 段与旧 GameHost 实现逐行不一致",
        )

        // shape 探针段：VarRef 全字段逐条（桌面 JVM 口径：accessible 恒 true、static 只
        // Companion 与 NO_INDICES 两条）。逐行手抄旧 GameHost 格式，集合以 2026-10-24 组长
        // 过闸实测反射输出为准：11 个 declaredFields，其中 NO_INDICES 是 Kotlin 2.0.20 把
        // private companion 的 backing field 提升到外层类生成的 static（type=long[] 落
        // isAtomTypeCompat 的 LongArray 清单 → atom=true）；atom 分类是 isAtomTypeCompat 的
        // 2026-10-22 快照口径——Expr[]（indexArray）分 false，不随 CMP-14 放宽漂移。
        val shapeLines = masked.filter { it.startsWith("shape VarRef.") }
        assertEquals(
            setOf(
                "shape VarRef.name: type=String accessible=true atom=true static=false",
                "shape VarRef.indices: type=List accessible=true atom=false static=false",
                "shape VarRef.isNoArg: type=boolean accessible=true atom=true static=false",
                "shape VarRef.operandType: type=OperandType accessible=true atom=true static=false",
                "shape VarRef.isConst: type=boolean accessible=true atom=true static=false",
                "shape VarRef.subKey: type=String accessible=true atom=true static=false",
                "shape VarRef.line: type=int accessible=true atom=true static=false",
                "shape VarRef.col: type=int accessible=true atom=true static=false",
                "shape VarRef.indexArray: type=Expr[] accessible=true atom=false static=false",
                "shape VarRef.Companion: type=Companion accessible=true atom=false static=true",
                "shape VarRef.NO_INDICES: type=long[] accessible=true atom=true static=true",
            ),
            shapeLines.toSet(),
            "shape 探针与旧 GameHost 实现逐行不一致（字段集或分类漂了）",
        )
        // 折叠审计关着时不动读数（末尾没有 fold-audit 行），且探针外无多余输出。
        assertTrue(masked.none { it.startsWith("fold-audit:") }, "审计关闭时不得出现 fold-audit 行")
        assertEquals(11 + shapeLines.size, masked.size, "对账输出出现了计划外的行")
    }

    @Test
    fun `第二实例装载失败时对账降级并逐下标告警（旧实现同口径）`(@TempDir dir: Path) {
        val artifact = dir.resolve("good.erbc")
        val program = loadedProgram(artifact)
        // 产物损坏 = 第二/第三实例 loadMapped 失败（旧实现 runCatching.getOrNull → null）：
        // 用独立路径，避免 Windows 上截断被第一个实例的惰性池通道映射住的文件。
        val corrupt = dir.resolve("corrupt.erbc")
        Files.write(corrupt, byteArrayOf(0x00, 0x01, 0x02, 0x03))

        val lines = ProgramDiagnostics.auditStatementPoolAt(program, corrupt, 0, 2, 1)
        val masked = lines.map(::mask)
        assertEquals(
            listOf(
                "pool-audit stmtCount=3 second=null",
                "pool-audit #0",
                "  实例1(执行用) @# AssignStmt(line=7) dest=I[0] op=ASSIGNMENT spDest=I[0] spTerm=Int(5)",
                "  实例2(新装载) @# (null)",
                "  ⚠ 双实例不一致！",
                "pool-audit #1",
                "  实例1(执行用) @# AssignStmt(line=8) dest=I[0] op=PLUS spDest=I[0] spTerm=Int(1)",
                "  实例2(新装载) @# (null)",
                "  ⚠ 双实例不一致！",
                "pool-audit #2",
                "  实例1(执行用) @# InstructionStmt(line=9)",
                "  实例2(新装载) @# (null)",
                "  ⚠ 双实例不一致！",
            ),
            masked.subList(0, 13),
            "second=null 的降级路径与旧 GameHost 实现逐行不一致",
        )
        // 第三实例同样装载失败 → 无实例3行；shape 探针照常输出（旧实现它在 if (third != null) 之外）。
        assertTrue(masked.none { it.startsWith("pool-audit 实例3") }, "third=null 时不得有实例3行")
        assertTrue(masked.any { it.startsWith("shape VarRef.") }, "shape 探针不随第三实例失败缺席")
    }

    @Test
    fun `窗口越界下标按直读失败打印不抛（旧实现 runCatching 同口径）`(@TempDir dir: Path) {
        val artifact = dir.resolve("good.erbc")
        val program = loadedProgram(artifact)
        // lo=-1：statement(-1) 越界 → null → brief 打 (null)；双实例都是 null 时旧实现
        // 的 `s1?.toString() != s2?.toString()` 不成立（null != null = false），**不打告警行**。
        val lines = ProgramDiagnostics.auditStatementPoolAt(program, artifact, -1, 0, 0)
        val masked = lines.map(::mask)
        assertEquals("pool-audit #-1", masked[1])
        assertEquals("  实例1(执行用) @# (null)", masked[2])
        assertEquals("  实例2(新装载) @# (null)", masked[3])
        assertEquals("pool-audit #0", masked[4])
        assertEquals(
            "  实例1(执行用) @# AssignStmt(line=7) dest=I[0] op=ASSIGNMENT spDest=I[0] spTerm=Int(5)",
            masked[5],
        )
    }

    @Test
    fun `折叠审计开启时读数随对账返回（旧行为同口径）`(@TempDir dir: Path) {
        val prev = StmtInternAudit.enabled
        StmtInternAudit.enabled = true
        try {
            val artifact = dir.resolve("good.erbc")
            val program = loadedProgram(artifact)
            val lines = ProgramDiagnostics.auditStatementPoolAt(program, artifact, 0, 0, 0)
            val audit = lines.dropWhile { !it.startsWith("fold-audit:") }
            assertTrue(audit.isNotEmpty(), "StmtInternAudit.enabled 开启时读数必须随对账返回")
            assertTrue(audit.first().startsWith("fold-audit: 总折叠 "), "首行摘要格式与 FoldAuditState.drain 一致")
            assertTrue(audit.first().contains("可疑（关键摘要不同）0 次"), "健康程序零 RED")
            assertTrue(audit.none { it.startsWith("!!折叠") }, "健康程序不得有 RED 明细")
        } finally {
            StmtInternAudit.drain() // 清残留，防污染后续测试（StmtArgInterningTest 同款收尾）
            StmtInternAudit.enabled = prev
        }
    }

    // ==================================================================
    // 死循环现场（deadLoopSiteLines）
    // ==================================================================

    @Test
    fun `死循环窗口转储与 GameHost 旧实现逐行一致`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 0
              WAIT stmt#0
              WAIT stmt#1
              _HALT
            """,
            statements = listOf(assignI5(), waitStmt()),
        )
        // 撞闸 pc=1（WAIT stmt#0 的 marker）：窗口 lo=0..hi=3，内部指令（_SET_INT/_HALT）跳过。
        val step = StepLimitExceeded(steps = 100L, line = 3, pc = 1, funcName = "main", callChain = null)
        val lines = ProgramDiagnostics.deadLoopSiteLines(step, program, null).map(::mask)
        assertEquals(
            listOf(
                // 首行：pc/行号/fn/steps/四指令字 + 调用链（null → ?）。WAIT=151（isa.yaml INPUT 段：
                // OpcodeTable NAMES 的 TWAIT(150)/WAIT(151)/WAITANYKEY(152)，KIND_RANGES INPUT=138..152，
                // FunctionCode.kt:460 WAIT kind=INPUT 同证）。line=4 = fixture 实际读数：raw 字符串未
                // trimIndent，首空行使 Assembler currentLine 整体 +1（"WAIT stmt#0" 在文本第 4 行），
                // 与旧实现同式（program.lineOf(pc)）对同一 fixture 的产出一致。
                "死循环指令现场 pc=1 line=4 fn=main steps=100 words=[151,0,0,-1] 调用链=?",
                // 窗口行：stmt=Statement.toString；dest/spDest=VarRef data class toString；addConst=false
                "死循环窗口 p1 op151 a0@# stmt=AssignStmt(SET, line=7) | dest=VarRef(name=I, indices=[IntLiteral(value=0, line=7, col=0)], isNoArg=false, operandType=INT, isConst=false, subKey=null, line=7, col=0) op=ASSIGNMENT spDest=VarRef(name=I, indices=[IntLiteral(value=0, line=7, col=0)], isNoArg=false, operandType=INT, isConst=false, subKey=null, line=7, col=0) spTerm=IntLiteral(value=5, line=7, col=0) addConst=false",
                "死循环窗口 p2 op151 a1@# stmt=InstructionStmt(WAIT, line=9)",
            ),
            lines,
            "死循环窗口转储与旧 GameHost.logDeadLoopSite 逐行不一致",
        )
    }

    @Test
    fun `死循环表达式行与字母快照与旧实现一致`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _EVAL_INT ${'$'}0, R1
              _HALT
            """,
            exprs = listOf(IntLiteral(9L, 1, 1)),
        )
        // 撞闸 pc=0 是 _EVAL_INT(27)、a=0 落在表达式池内 → 附表达式行；store 给默认池 → A–Z 全 0。
        // line=3 = fixture 实际读数：raw 字符串未 trimIndent，首空行使 Assembler currentLine +1
        // （"_EVAL_INT" 在文本第 3 行），旧实现同式（program.lineOf(pc)）对同一 fixture 产出一致。
        val step = StepLimitExceeded(steps = 7L, line = 2, pc = 0, funcName = "main", callChain = "@MAIN")
        val lines = ProgramDiagnostics.deadLoopSiteLines(step, program, VariableStore())
        assertEquals(
            listOf(
                "死循环指令现场 pc=0 line=3 fn=main steps=7 words=[27,0,1,0] 调用链=@MAIN",
                "死循环表达式 expr=IntLiteral(value=9, line=1, col=1)",
                "死循环字母 A:0;B:0;C:0;D:0;E:0;F:0;G:0;H:0;I:0;J:0;K:0;L:0;M:0;N:0;O:0;P:0;Q:0;R:0;S:0;T:0;U:0;V:0;W:0;X:0;Y:0;Z:0;",
            ),
            lines,
            "表达式行 / 字母快照行与旧 GameHost 实现逐行不一致",
        )
    }

    @Test
    fun `非死循环异常与缺程序返回空（旧实现提前返回同口径）`() {
        val program = Assembler.assemble("_HALT")
        assertTrue(
            ProgramDiagnostics.deadLoopSiteLines(RuntimeException("与死循环无关"), program, null).isEmpty(),
            "非 StepLimitExceeded 必须空表（旧实现 as? 失败即 return）",
        )
        assertTrue(
            ProgramDiagnostics.deadLoopSiteLines(StepLimitExceeded(1L, 0, 0, null), null, null).isEmpty(),
            "program=null 必须空表（旧实现 session 未建好即 return）",
        )
    }

    @Test
    fun `窗口内非 marker 不打印（旧实现过滤条件同口径）`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 1
              _ADD R0, R0, R0
              _HALT
            """,
        )
        // pc=0 落在 _SET_INT（内部指令）：窗口 0..2 全是内部指令，无任何窗口行；_EVAL_INT 分支也不命中。
        // line=3 = fixture 实际读数（raw 字符串未 trimIndent，"_SET_INT" 在文本第 3 行，同上两条口径）。
        val step = StepLimitExceeded(steps = 3L, line = 2, pc = 0, funcName = "main", callChain = null)
        val lines = ProgramDiagnostics.deadLoopSiteLines(step, program, null)
        assertEquals(
            listOf("死循环指令现场 pc=0 line=3 fn=main steps=3 words=[2,0,0,0] 调用链=?"),
            lines,
            "纯内部指令窗口不得产出窗口行（op 0..28 被 isInternal 过滤）",
        )
    }

    @Test
    fun `pc 越界按旧口径上抛由调用方 runCatching 兜底`() {
        val program = Assembler.assemble("_HALT")
        val step = StepLimitExceeded(steps = 1L, line = 0, pc = 999, funcName = null, callChain = null)
        // 旧实现把整段转储包进 runCatching、onFailure 落「死循环现场转储失败」；下沉后这层
        // 兜底随调用点保留（GameHost :754 接线 patch），core 侧如实上抛让宿主落该行。
        assertFailsWith<IndexOutOfBoundsException> {
            ProgramDiagnostics.deadLoopSiteLines(step, program, null)
        }
    }
}
