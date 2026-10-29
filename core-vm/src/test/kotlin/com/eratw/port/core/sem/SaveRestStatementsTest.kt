package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.stmt.FunctionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M6-SAVE-REST 语句层收尾：`LOADDATA` / `DELDATA` 的参数口径与读档后的控制流。
 *
 * 断言全是 C# 行号的直接映射，不用实现算出来的值当「原版读数」：
 * - `LOADDATA`：`Process.cs:1844-1851` 的两道门 + `:1860-1862` 的清栈/状态跳转；
 * - `DELDATA`：`FunctionIdentifier.cs:1944-1957` → `toUInt32inArg`（`:4320-4331`）；
 * - 文案：`Lang.Error.LoaddataArgIsNegative`（lang/emuera.zhs.xml:441）、
 *   `TooLargeLoaddataArg`（:672）、`ArgIsNegative`（:31）、`ArgIsTooLarge`（:61）。
 */
class SaveRestStatementsTest {

    /** 只记参数的 `SystemOps`：参数门在 sem，落盘不在本文件范围。 */
    private class Recorder : SystemOps {
        val loadDataCalls = ArrayList<Int>()
        val delDataCalls = ArrayList<Int>()

        override fun loadData(no: Int) {
            loadDataCalls.add(no)
        }

        override fun delData(no: Int) {
            delDataCalls.add(no)
        }
    }

    // ------------------------------------------------------------------
    // LOADDATA（Process.cs:1841-1863）
    // ------------------------------------------------------------------

    /** `:1844-1847` 负值门，文案 `LOADDATA的参数被指定为负数({0})`。 */
    @Test
    fun `LOADDATA 负值报 LoaddataArgIsNegative`() {
        val env = SemTestEnv()
        val ops = Recorder()
        env.ctx.systemOps = ops
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.LOADDATA, intArg(-1))) }
        assertEquals("LOADDATA的参数被指定为负数(-1)", e.message)
        assertEquals(emptyList(), ops.loadDataCalls, "参数门在调用宿主之前")
    }

    /**
     * `:1848-1851` 上界门（本次新增）：`> Int.MAX_VALUE` → `TooLargeLoaddataArg`。
     * 边界值 `Int.MAX_VALUE` 本身必须放行 —— 老实现 `no.toInt()` 会把它静默截断/回绕。
     */
    @Test
    fun `LOADDATA 过大报 TooLargeLoaddataArg 且边界放行`() {
        val env = SemTestEnv()
        val ops = Recorder()
        env.ctx.systemOps = ops
        val big = Int.MAX_VALUE.toLong() + 1
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.LOADDATA, intArg(big))) }
        assertEquals("LOADDATA的参数值(2147483648)过大", e.message)
        assertEquals(emptyList(), ops.loadDataCalls)

        env.run(instr(FunctionCode.LOADDATA, intArg(Int.MAX_VALUE.toLong())))
        assertEquals(listOf(Int.MAX_VALUE), ops.loadDataCalls, "Int.MAX_VALUE 合法（原版 toInt 直通）")
    }

    /**
     * `:1860-1862`：成功后 `ClearFunctionList()` + `SystemState = LoadData_DataLoaded`，
     * 随后 `return false` —— 脚本帧不再往下跑。
     *
     * 本仓的等价出口是 [ExecResult.Quit]（`ProcessRuntime.kt:468` → `StmtOutcome.Stop(0)`）；
     * 状态位本身由 `FileSystemOps.loadData` 经 `SaveLoadHandoff` 记账（见 `SaveEmPoolTest`）。
     * `Normal` 会让下一条语句继续执行，与「清空函数栈」相反，故这里钉死非 Normal。
     */
    @Test
    fun `LOADDATA 成功结束脚本帧`() {
        val env = SemTestEnv()
        val ops = Recorder()
        env.ctx.systemOps = ops
        val r = env.run(instr(FunctionCode.LOADDATA, intArg(3)))
        assertEquals(ExecResult.Quit(), r)
        assertEquals(listOf(3), ops.loadDataCalls)
    }

    // ------------------------------------------------------------------
    // DELDATA（FunctionIdentifier.cs:1944-1957）
    // ------------------------------------------------------------------

    /**
     * `toUInt32inArg(value, "DELDATA", 1)`（`:4320-4331`）：先负值、后过大，两条分支各有
     * 自己的字面文案（`函数{0}: 第{1}个参数…`）——旧实现第二条缺失、第一条用了 LOADDATA 的
     * 专用文案（`DELDATA的参数被指定为负数`，Lang 里没有这一条）。
     */
    @Test
    fun `DELDATA 负值与过大的两条文案`() {
        val env = SemTestEnv()
        val ops = Recorder()
        env.ctx.systemOps = ops

        val neg = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.DELDATA, intArg(-3))) }
        assertEquals("函数DELDATA: 第1个参数被是负值(-3)", neg.message)

        val big = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.DELDATA, intArg(Int.MAX_VALUE.toLong() + 1)))
        }
        assertEquals("函数DELDATA: 第1个参数(2147483648)过大", big.message)

        assertEquals(emptyList(), ops.delDataCalls, "两条门都在调用宿主之前")
        env.run(instr(FunctionCode.DELDATA, intArg(7)))
        assertEquals(listOf(7), ops.delDataCalls)
    }
}
