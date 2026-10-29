package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * 红 J（新游戏入口无限循环）的**产物路径**回归锁：`.erbc` 往返后 `FOR`/`NEXT` 的步进语句
 * 必须仍是**增量**语义。
 *
 * ## 原版语义（证据）
 * - `FOR` 的循环变量在 `NEXT` 处按步长推进；`CONTINUE` 也先步进再判断
 *   （`FunctionIdentifier.cs` 的 `FOR_NEXT`/`CONTINUE` 分支；本仓 `specs/isa.yaml` 的 `NEXT`/`CONTINUE`）。
 * - 常量增量在解析期折成 `Term=null, IsConst=true, AddConst=true, ConstInt=±N`
 *   （`ArgumentParser.cs:922-940`、`:1004-1015`；`-=` 的负号已带上，`:1011`）。
 * - 执行期 `IsConst ? ConstInt : Term.GetIntValue`，`AddConst ? ChangeValue : SetValue`
 *   （`FunctionIdentifier.cs:647-658`）；`ChangeValue` 见 `VariableTerm.cs:202-220`。
 *
 * ## 锁的是什么
 * 若写/读两侧把折叠形态退化成「`dest = 常量`」（`isConst`/`constInt`/`addConst` 任一丢失，
 * 或折成 `Term=IntLiteral(N)` + `AddConst=false`），`NEXT` 的 `CNT += 1` 就变成 `CNT = 1`：
 * 循环变量永远停在下界，`NEXT` 的回边永真 —— 正是语料
 * `ステータス計算関連/ABL/ABL.ERB:79-131`（`FOR LOCAL,0,4` ＋ `:130 GOTO AGAIN`）
 * 与 `:88`/`:99`（`FOR LOCAL:1,0,100`）在真机 `.erbc` 上打转 5,000,000 步的成因：
 * 档位/珠位下标恒为 1，`ABL:(角色):(上げるABL) += 1` 与 `JUEL:… -= …` 都到不了。
 *
 * 因此这里跑「解析 → 编译 → `.erbc` 编码 → `ProgramLoader.loadMapped`（真机同款惰性装载）
 * → 执行」全链，同时钉住**编译产物形态**与**运行时落库**。
 */
class ForStepErbcRegressionTest {

    @Test
    fun `erbc 往返后 NEXT 的 FOR 步进仍是增量（含二维动态下标常量增量）`(@TempDir dir: Path) {
        val symbols = SymbolTable()
            .declareVariable("CNT", OperandType.INT, 0)
            .declareVariable("TOTAL", OperandType.INT, 0)
            .declareVariable("MAT", OperandType.INT, 2)
            .declareVariable("SEL", OperandType.INT, 0)
            .declareVariable("IDX", OperandType.INT, 0)
        val parser = LineParser(symbols)
        val lines = listOf(
            "@T",
            "TOTAL = 0",
            "SEL = 1",
            "IDX = 2",
            "FOR CNT,0,4",
            "TOTAL += 1",
            "MAT:SEL:IDX += 2",
            "NEXT",
        )
        val stmts = lines.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            parser.parseLogicalLine(LogicalLine(kind, "forstep.erb", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        val info = compiler.compileFunction(FuncSig("T", 0), stmts, setOf("T"))
        val bytes = ErbcWriter.encode(compiler.build("T"))
        val artifact = dir.resolve("forstep.erbc")
        Files.write(artifact, bytes)

        // 真机路径：惰性分段装载（`ProgramLoader.loadMapped`），不是整池 decode
        val program = ProgramLoader.loadMapped(artifact)
        // 形态判据：步进语句必须是折叠常量增量，而不是 `dest = 常量`
        val steps = (0 until program.statementCount)
            .map { program.statement(it) }
            .filterIsInstance<AssignStmt>()
            .filter { it.dest.name == "CNT" }
        assertEquals(2, steps.size, "FOR CNT,0,4 应有两条 CNT 赋值（FOR INIT 与 FOR STEP），实际 $steps")
        // `$AGAIN` 型循环的**唯一推进器**：NEXT 处的步进语句必须是 ChangeValue（增量），
        // 一旦退化成 SetValue（`CNT = 1` / `CNT = 0`），循环变量永停在下界 ⇒ 回边永真。
        val step = steps.firstOrNull { (it.args as SpSetArgument).addConst }
            ?: error("FOR STEP 丢了 AddConst（落库退化成 SetValue）：$steps")
        val arg = step.args as SpSetArgument
        assertEquals(true, arg.addConst, "步进必须走 ChangeValue 而不是 SetValue（FunctionIdentifier.cs:647-658）")
        val delta = if (arg.isConst) arg.constInt else (arg.term as com.eratw.port.core.expr.IntLiteral).value
        assertEquals(1L, delta, "FOR 默认步长 1（若退化成 SetValue/0 增量，循环变量永停在下界）")

        val store = VariableStore()
        store.declareUserVar(DimSpec("CNT", false, intArrayOf()))
        store.declareUserVar(DimSpec("TOTAL", false, intArrayOf()))
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(3, 4)))
        store.declareUserVar(DimSpec("SEL", false, intArrayOf()))
        store.declareUserVar(DimSpec("IDX", false, intArrayOf()))
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = info.index, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 100_000L),
        ).execute()

        assertEquals(4L, store.readInt("TOTAL", longArrayOf()), "循环体必须走满 4 轮（步进若退化成 CNT=1 则永不退出）")
        assertEquals(4L, store.readInt("CNT", longArrayOf()), "NEXT 退出时循环变量等于终值")
        assertEquals(8L, store.readInt("MAT", longArrayOf(1, 2)), "4 轮 × 2，且下标表达式按动态值落点")
        assertEquals(0L, store.readInt("MAT", longArrayOf(0, 2)), "下标表达式不得落到 0 号元素")
    }
}
