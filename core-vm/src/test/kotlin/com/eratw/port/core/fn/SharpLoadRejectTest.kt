package com.eratw.port.core.fn

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A1 拆桩后的负控（非空转）：**无桩 = 装载期拒载**，且拒载只波及出错那一行。
 *
 * 原版相位（本轮所有断言的锚）：
 * - 联结期 `CALLSHARP_Instruction.SetJumpTo` 对常量名查 `PluginManager.HasMethod`，未命中
 *   `ParserMediator.Warn(..., isError: true)`（FunctionIdentifier.cs:1201-1204）；
 * - Warn 把 `line.IsError = true` 与 `ErrMes` 记在**那一行**上，并进 warningList
 *   （ParserMediator.cs:125-136），装载完 `FlushWarningList` 统一上屏（:143-158）——
 *   **该行不执行也见警告**；
 * - 装载趟只对被标错的行 continue，同文件其余行照常挂载（ErbLoader.cs:1598-1600）；
 * - 真执行到该行才由 `throw new CodeEE(currentLine.ErrMes)` 抛出记录值（Process.cs:1017-1019）。
 *
 * 一个 program 里放三种东西：一条**未注册** CALLSHARP（`ModelSet`）、一条**已注册** CALLSHARP
 * （`Chat`，本用例只在本地注册表登记，与 app 生产装配无关）、一个普通可执行函数。四条断言：
 * (a) 装载趟只报未注册那条；(b) 再预填一次不重复报（幂等）；(c) 普通函数照常执行、
 * 已注册那条照常执行到方法体；(d) 未注册那条执行期抛与装载期警告逐字同一句。
 */
class SharpLoadRejectTest {

    private companion object {
        const val REJECT_MESSAGE = "No native method ModelSet found"
    }

    /** 语料侧的 5 个 `#DIMS` 变量 + 负控自己的普通变量与三个被 CALL 的目标。 */
    private fun parser(): LineParser =
        LineParser(
            SymbolTable()
                .declareVariable("模型API", OperandType.STR, 0)
                .declareVariable("模型Key", OperandType.STR, 0)
                .declareVariable("模型名称", OperandType.STR, 0)
                .declareVariable("AI对话返回信息", OperandType.STR, 0)
                .declareVariable("返回对话", OperandType.STR, 0)
                .declareVariable("PLAIN_BOX", OperandType.INT, 0)
                .declareFunction("PLAIN_PROBE", OperandType.INT)
                .declareFunction("SHARP_HIT_PROBE", OperandType.INT)
                .declareFunction("SHARP_REJECT_PROBE", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "sharp-reject.erb", i + 1, text))
        }
    }

    /**
     * 同一 program 三个函数 + 一个驱动：
     * - `@SHARP_REJECT_PROBE`：未注册名 `ModelSet`（4.981 语料 `对话调用.erb:35` 的原形）；
     * - `@SHARP_HIT_PROBE`：已注册名 `Chat`（`对话调用.erb:36` 的原形）；
     * - `@PLAIN_PROBE`：普通可执行函数（写一个全局量，掉尾 RESULT 清零不碰它）；
     * - `@SHARP_MIX_DRIVER`（入口）：按「普通 → 已注册 → 未注册」的顺序 CALL。
     */
    private fun compileMix(): Program {
        val c = ErbProgramCompiler()
        val known = setOf("SHARP_REJECT_PROBE", "SHARP_HIT_PROBE", "PLAIN_PROBE", "SHARP_MIX_DRIVER")
        c.compileFunction(
            FuncSig("SHARP_REJECT_PROBE", 0),
            parse("@SHARP_REJECT_PROBE", "CALLSHARP ModelSet(模型API, 模型Key, 模型名称)"),
            known,
            ExprCallTable(),
        )
        c.compileFunction(
            FuncSig("SHARP_HIT_PROBE", 0),
            parse("@SHARP_HIT_PROBE", "CALLSHARP Chat(AI对话返回信息, 返回对话)"),
            known,
            ExprCallTable(),
        )
        c.compileFunction(
            FuncSig("PLAIN_PROBE", 0),
            parse("@PLAIN_PROBE", "PLAIN_BOX = 7"),
            known,
            ExprCallTable(),
        )
        c.compileFunction(
            FuncSig("SHARP_MIX_DRIVER", 0),
            parse(
                "@SHARP_MIX_DRIVER",
                "CALL PLAIN_PROBE()",
                "CALL SHARP_HIT_PROBE()",
                "CALL SHARP_REJECT_PROBE()",
            ),
            known,
            ExprCallTable(),
        )
        return c.build("SHARP_MIX_DRIVER")
    }

    private fun declareVars(store: VariableStore) {
        for (name in listOf("模型API", "模型Key", "模型名称", "AI对话返回信息", "返回对话")) {
            store.declareUserVar(DimSpec(name, isStr = true, dims = IntArray(0), isGlobal = true))
        }
        store.declareUserVar(DimSpec("PLAIN_BOX", isStr = false, dims = IntArray(0), isGlobal = true))
    }

    private fun runtimeWith(program: Program, store: VariableStore): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
        )

    @Test
    fun `无桩负控 装载趟只拒未注册那条 普通函数与已注册行照常执行`() {
        val store = VariableStore()
        declareVars(store)
        val chatHits = ArrayList<List<String>>()
        nativeMethods(store.aux).register("Chat") { params: Array<NativeParam> ->
            chatHits.add(params.map { it.strValue })
        }
        val program = compileMix()
        assertTrue(program.flatStatementsEnabled, "本负控走 flat 生产链（Android 生产默认口径）")

        // (a) 装载趟：只报未注册的 ModelSet；已注册的 Chat 静默绑死。
        assertEquals(
            listOf(REJECT_MESSAGE),
            prefillSharpLinks(store.aux, program),
            "已注册名不进警告，未注册名在装载趟就报（FunctionIdentifier.cs:1204 相位）",
        )
        // (b) 幂等：已裁决行不重查、不重报。
        assertEquals(
            emptyList(),
            prefillSharpLinks(store.aux, program),
            "第二次预填不重复报（SharpLinkTable 已落笔的行跳过）",
        )

        // (c) + (d)：普通函数先跑完，已注册行跑进方法体，未注册行才抛记录值。
        val rt = runtimeWith(program, store)
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(REJECT_MESSAGE, e.message, "执行期抛装载趟记录值（Process.cs:1017-1019）")
        assertEquals(
            7L,
            store.readInt("PLAIN_BOX", longArrayOf()),
            "普通函数照常执行（ErbLoader.cs:1598-1600 只对被标错的行 continue）",
        )
        assertEquals(1, chatHits.size, "已注册的 Chat 照常执行到方法体")
    }
}
