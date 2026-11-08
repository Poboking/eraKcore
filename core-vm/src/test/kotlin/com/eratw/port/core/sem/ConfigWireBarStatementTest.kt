package com.eratw.port.core.sem

import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U3：BAR / BARL 语句的实部/空部字符取数点。
 *
 * C# 侧 `BAR` 语句与 `BARSTR` 函数共用 `ExpressionMediator.CreateBar`
 * （Process.cs → DoInstruction 的 SP_BAR 分支 → :155-156 `Config.BarChar1/BarChar2`）。
 * BARSTR 函数面已由 fn/ConfigWireBarCharTest 钉住（HostMethods.barChars 活读）；
 * 本类补**语句面**：`createBar` 不再硬编码 `'*'` / `'.'`，实参从 `ctx.config` 走
 * （装配链：`_Replace.csv` → GameSession 的 `SemConfig.barChar1/barChar2`）。
 *
 * 出厂缺省 `'*'` / `'.'`（ConfigData.cs:164-165）与语料无 `BAR文字` 条目 ⇒ 基准零漂移。
 * 截断语义（v 越界、max/length 非正值）CreateBar 内部自有，BARSTR 侧 BuiltinsTest 已钉，
 * 这里只钉一条负值夹零确认取数改造没碰截断。
 */
@Suppress("DEPRECATION")
class ConfigWireBarStatementTest {

    private class BarEnv(barChar1: Char, barChar2: Char) {
        val store = VariableStore()
        val console = FakeConsoleSink()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
            input = NoopInputSink,
            config = SemConfig(barChar1 = barChar1, barChar2 = barChar2),
        )
        val executor = StatementExecutor()

        fun bar(value: Long, max: Long, length: Long, code: FunctionCode = FunctionCode.BAR) {
            executor.execute(
                instr(code, SpBarArgument(iLit(value), iLit(max), iLit(length))),
                ctx,
            )
        }

        /** print() 事件按序拼接（BAR 每次一个 print）。 */
        val printed: String get() = console.printedText
    }

    @Test
    fun `Replace 字符经 SemConfig 进 BAR 语句`() {
        val env = BarEnv('#', '_')
        env.bar(30, 100, 10)
        assertEquals("[###_______]", env.printed, "ExpressionMediator.cs:155-156 的语句面")
    }

    @Test
    fun `出厂缺省下 BAR 保持星号点号 基准零漂移`() {
        val env = BarEnv('*', '.')
        env.bar(30, 100, 10)
        assertEquals("[***.......]", env.printed, "ConfigData.cs:164-165 出厂 '*' / '.'")
    }

    @Test
    fun `BARL 追加换行且字符同源`() {
        val env = BarEnv('=', '-')
        env.bar(30, 100, 10, FunctionCode.BARL)
        assertEquals("[===-------]", env.printed)
        assertEquals(
            listOf("print([===-------])", "newLine"),
            env.console.events,
            "BARL 语句在 print 后补一行（FunctionIdentifier.cs:1289-1312）",
        )
    }

    @Test
    fun `负值夹零的截断语义不受取数改造影响`() {
        val env = BarEnv('#', '_')
        env.bar(-5, 100, 10)
        assertEquals("[__________]", env.printed, "CreateBar 的 filled<0 夹零（ExpressionMediator.cs:147-150）")
    }
}
