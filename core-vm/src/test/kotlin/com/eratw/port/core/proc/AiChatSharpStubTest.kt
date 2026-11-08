package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.prefillSharpLinks
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 4.981 AI对话补丁 CALLSHARP 形状在 flat 生产链上的钉（A1 拆桩后的形态）。
 *
 * 语料形状（`ERB/DLC/AI对话补丁/对话调用.erb:35-36`，全语料仅有的**可达** CALLSHARP）：
 * ```
 * CALLSHARP ModelSet(模型API, 模型Key, 模型名称)   ;三实参全是常量变量，无出参
 * CALLSHARP Chat(AI对话返回信息, 返回对话)          ;两实参全是字符串变量，出参靠回写
 * ```
 * 9 参 Chat 变体（:54）整段被 SKIPSTART 包住，预处理期不编译 ⇒ 不进本钉。
 *
 * 本版不注册任何 CALLSHARP 本机方法（app `GameHost.wireCorpusNativeMethods` 的
 * `ModelSet` / `Chat` 两个降级桩已拆）⇒ **无桩 = 装载期拒载**：装载趟 [prefillSharpLinks]
 * 扫常量名查表未命中，按原版联结期相位记行级错误并收警告
 * （`FunctionIdentifier.cs:1201-1204` Warn(isError:true) → `ParserMediator.cs:125-136` 记
 * line.IsError 与 ErrMes、`:143-158` 装载完统一上屏），该行**不执行也见警告**；真走到那一行
 * 才抛记录值（`Process.cs:1017-1019`）。本用例把两半一起钉住：装载趟返回的警告文案与执行期
 * 抛出的 [CodeEE] 逐字同源。
 *
 * 「只拒未注册的那条、同文件普通函数照常执行」的负控在 `core/fn/SharpLoadRejectTest`。
 */
class AiChatSharpStubTest {

    /** 与 RefExprCallLoweringTest 同源的最小编译夹具（源文本 → 语句 → flat Program）。 */
    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("模型API", OperandType.STR, 0)
                .declareVariable("模型Key", OperandType.STR, 0)
                .declareVariable("模型名称", OperandType.STR, 0)
                .declareVariable("AI对话返回信息", OperandType.STR, 0)
                .declareVariable("返回对话", OperandType.STR, 0),
        )
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "对话调用.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compileChatAction(): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("CHAT_ACTION_LIKE", 0),
            parse(
                "@CHAT_ACTION_LIKE",
                "CALLSHARP ModelSet(模型API, 模型Key, 模型名称)",
                "CALLSHARP Chat(AI对话返回信息, 返回对话)",
            ),
            setOf(),
            com.eratw.port.core.compile.ExprCallTable(),
        )
        return c.build("CHAT_ACTION_LIKE")
    }

    /** 语料侧这 5 个变量是 `AI返回参数.ERH` 的全局 `#DIMS`；运行期 store 需同名可解析。 */
    private fun declareCorpusVars(store: VariableStore) {
        for (name in listOf("模型API", "模型Key", "模型名称", "AI对话返回信息", "返回对话")) {
            store.declareUserVar(
                com.eratw.port.core.vars.DimSpec(name, isStr = true, dims = IntArray(0), isGlobal = true),
            )
        }
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
    fun `无桩时 装载趟拒载两处未命中 执行到首个才抛同一句`() {
        val program = compileChatAction()
        assertTrue(program.flatStatementsEnabled, "本钉必须走 flat 生产链")
        val store = VariableStore()
        declareCorpusVars(store)
        // 装载趟：两个名字都没有本机方法 ⇒ 两句都在（该行不执行也见警告 = 原版装载完统一上屏的相位）。
        assertEquals(
            listOf("No native method ModelSet found", "No native method Chat found"),
            prefillSharpLinks(store.aux, program),
            "无桩 = 装载期拒载：警告按语句池序逐行收，不等执行",
        )
        val rt = runtimeWith(program, store)
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method ModelSet found", e.message, "执行期抛装载趟记录值，ModelSet 先于 Chat")
    }
}
