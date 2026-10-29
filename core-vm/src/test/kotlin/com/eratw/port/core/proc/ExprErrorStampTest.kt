package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 表达式求值错的**位置盖章**与**跳过意图单独记位**（2026-10-25 真机两红的收口测试）：
 *
 * 1. 语句层：sem 抛出的 ExprEvalException（如 `str * 负数`，原版本身就是 CodeEE ——
 *    OperatorMethodManager.cs:99-101 的 `MultiplyNegativeToStr`）在
 *    `ProcessRuntime.executeStatementRaw` 盖上函数/行/pc 再上抛。裸穿的话失败屏没有
 *    「（函数 行N）」现场 —— 真机「负数(-16)不能与字符串相乘」启动失败无定位即此因
 *    （describe 的 scriptLocation 只认 CodeEE 的 funcName/line 字段）。
 * 2. 输入侧：宿主在**无等待方**时按原版丢弃提交文本（`PressEnterKey` 的 Running 早退，
 *    EmueraConsole.cs:1374-1377），但 `MesSkip = keySkip` 在早退**之前**（:1373）——
 *    `QueueInputSink.noteSkipIntent` 就是这条「只剩跳过意图」的落点，不进队列。
 */
class ExprErrorStampTest {

    /** 与 SoundSinkInjectionTest 同款：空 MAIN 的最小运行时（`_HALT`）。 */
    private fun runtime(): ProcessRuntime = ProcessRuntime(
        Assembler.assemble("_HALT"),
        VariableStore(),
        FunctionTable(emptyList()),
        VmConfig(),
    )

    /** 带 functionName/line/pc 的只读 StmtEnv（盖章素材）。 */
    private class Env(override val line: Int) : StmtEnv {
        override val functionKey: String = "UPDATE_MAIN_DRAWLINE_SECTION"
        override val pc: Int = 12
        override val functionName: String = "UPDATE_MAIN_DRAWLINE_SECTION"
    }

    /** `PRINTFORM x%"*" * -3%` —— 内嵌字符串乘法，乘数为负。 */
    private fun badPrintForm(line: Int): Statement {
        val mult = Binary(
            OperatorCode.MULT,
            StrLiteral("*"),
            IntLiteral(-3),
            OperandType.STR,
            line,
        )
        val form = FormStringTerm(
            listOf(
                FormPart.Text("x"),
                FormPart.Embedded(mult, FormConversion.Percent()),
            ),
            line,
        )
        val spec = PrintSpec.parse(FunctionCode.PRINTFORM)
            ?: error("PRINTFORM 必须能解析出 PrintSpec")
        return PrintStmt(spec, ExpressionArgument(form), line, "PRINTFORM x")
    }

    @Test
    fun `表达式错误盖章为带位置的 CodeEE`() {
        val rt = runtime()
        val e = assertFailsWith<CodeEE> { rt.executeStatement(badPrintForm(357), Env(357)) }
        // 文案 = Expr.kt 的原版复刻（MultStrInt 的 MultiplyNegativeToStr）
        assertEquals("负数(-3)不能与字符串相乘", e.message)
        // 盖章三件套：函数名 + 源码行 + pc（失败屏 scriptLocation 读的就是这几个字段）
        assertEquals("UPDATE_MAIN_DRAWLINE_SECTION", e.funcName)
        assertEquals(357, e.line)
        assertEquals(12, e.pc)
    }

    @Test
    fun `noteSkipIntent 只记跳过意图不落队列`() {
        val sink = QueueInputSink()
        assertFalse(sink.isAwaiting, "未在等待时 isAwaiting 为假（宿主据此丢弃文本）")
        sink.noteSkipIntent()
        assertEquals(0, sink.pendingCount, "跳过意图不占队列位（原版文本被丢弃）")
        assertTrue(sink.consumeMesSkip(), "下一次等待消费时读到跳过意图（MesSkip = keySkip 先行）")
        assertFalse(sink.consumeMesSkip(), "读一次即清")
    }
}
