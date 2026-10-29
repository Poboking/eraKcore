package com.eratw.port.core.stmt

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * 红 O 复现（`TRYCALLFORM`/`CALLFORM` 动态名对过程函数全 miss）——定位探针。
 *
 * 夹具用**系统变量** `X`/`A`（单字母是系统名，`#DIM` 同名会被 CORE-06 拒），
 * 于是无需 `#DIM` 声明即可在 `LineParser(SymbolTable())` 下解析。
 */
class CallFormNameResolveReproTest {

    private fun parseErbLines(file: String, fromLine: Int, vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, file, fromLine + i, text))
        }
    }

    private fun compileProgram(vararg funcs: Pair<String, List<String>>): Program {
        val c = ErbProgramCompiler()
        val known = funcs.map { it.first.uppercase() }.toSet()
        for ((name, lines) in funcs) {
            c.compileFunction(FuncSig(name), parseErbLines("repro.erb", 1, *lines.toTypedArray()), known)
        }
        return c.build(funcs.first().first)
    }

    private fun runProgram(program: Program, store: VariableStore) {
        val table = FunctionTable(
            program.functions.mapIndexed { i, f -> FuncMeta(index = i, key = f.name.uppercase()) },
        )
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 50_000L)).execute()
    }

    @Test
    fun `A_TRYCALLFORM 常量名命中`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "TRYCALLFORM \"COM_ABLE400\"", "A = RESULT"),
                "COM_ABLE400" to listOf("@COM_ABLE400", "RETURN 7"),
            ),
            store,
        )
        assertEquals(7, store.readInt("A", longArrayOf(0)), "常量名基线：应命中")
    }

    @Test
    fun `B_TRYCALLFORM 动态名 花括号插值命中`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "X = 400", "TRYCALLFORM COM_ABLE{X}", "A = RESULT"),
                "COM_ABLE400" to listOf("@COM_ABLE400", "RETURN 7"),
            ),
            store,
        )
        assertEquals(7, store.readInt("A", longArrayOf(0)), "动态名 COM_ABLE{X} 应命中 COM_ABLE400")
    }

    @Test
    fun `C_CALLFORM 动态名 花括号插值命中`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "X = 400", "CALLFORM COM_ABLE{X}", "A = RESULT"),
                "COM_ABLE400" to listOf("@COM_ABLE400", "RETURN 7"),
            ),
            store,
        )
        assertEquals(7, store.readInt("A", longArrayOf(0)), "动态名 CALLFORM 应命中")
    }

    @Test
    fun `D_erbc 往返后 TRYCALLFORM 动态名 花括号插值命中`(@TempDir dir: Path) {
        val c = ErbProgramCompiler()
        val known = setOf("TEST", "COM_ABLE400")
        val mainInfo = c.compileFunction(
            FuncSig("TEST", 0),
            parseErbLines("repro.erb", 1, "@TEST", "X = 400", "TRYCALLFORM COM_ABLE{X}", "A = RESULT"),
            known,
        )
        val targetInfo = c.compileFunction(
            FuncSig("COM_ABLE400", 0),
            parseErbLines("repro.erb", 100, "@COM_ABLE400", "RETURN 7"),
            known,
        )
        val artifact = dir.resolve("repro.erbc")
        Files.write(artifact, ErbcWriter.encode(c.build("TEST")))

        // 真机路径：惰性分段装载（`ProgramLoader.loadMapped`），不是整池 decode
        val program = ProgramLoader.loadMapped(artifact)
        val store = VariableStore()
        val table = FunctionTable(
            listOf(
                FuncMeta(index = mainInfo.index, key = "TEST"),
                FuncMeta(index = targetInfo.index, key = "COM_ABLE400"),
            ),
        )
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 50_000L)).execute()
        assertEquals(7, store.readInt("A", longArrayOf(0)), "erbc 往返后动态名仍应命中")
    }
}
