package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W8-gen 定向单测 —— `updatedGeneration` 置脏通道（[LineBufferConsoleSink.markGenerationUpdated]）
 * 对位 `Process.cs:2433 console.updatedGeneration = true`（EVENTCOMEND 分支①）的世代行为。
 *
 * 钉的基准（本地 reference 实测行号）：
 * - 按钮可点判据 `Generation == lastButtonGeneration`（`EmueraConsole.cs:1153`/`:1219`
 *   键入匹配、`:1753`/`:2439`/`:2448` 鼠标路径）——移植侧
 *   `LineBufferConsoleSink.collectGenerationOptionsLocked` 的 `generation != gen` 过滤；
 * - 等待入口 `newGeneration()`（`EmueraConsole.cs:783-828`）：`!updatedGeneration &&
 *   脚本行变了` ⇒ `lastButtonGeneration = newButtonGeneration`（旧页全灭，`:789-792`）；
 *   否则只清标志、世代不动（`:793-796`）——移植侧 `newGeneration`（`LineBufferConsoleSink.kt:607-633`）；
 * - 分支①的置位是**字段直写**（`Process.cs:2433`，字段 `EmueraConsole.cs:106`），
 *   **不是** `UpdateGeneration()`（`EmueraConsole.cs:770-774`，按钮构造专用、连
 *   lastButtonGeneration 一起前移）——见「整句映射」反例用例。
 */
class GenerationRedisplayTest {

    private fun intWait(scriptLine: Long) = InputRequest(
        type = InputRequest.InputType.INT,
        scriptLine = scriptLine,
    )

    /** 一页菜单按钮（缓冲态，进入等待入口时才 flush 落行、盖世代）。 */
    private fun LineBufferConsoleSink.printMenu() {
        printButton("清掃", 410L)
        printButton("休息", 411L)
    }

    private fun valuesOf(sink: LineBufferConsoleSink): List<Long> =
        sink.currentOptions().map { it.value as Long }

    /**
     * `Process.cs:2433` 主行为：非法输入重显菜单（重等不重印）后，脚本行已推进，
     * 置脏让下一次等待入口走 `:793-796` 的 else 支 ⇒ 屏上旧菜单按钮保持可点。
     */
    @Test
    fun `置位后脚本行推进的重等不推进世代 旧菜单保持可点`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(intWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink), "第一页菜单可点")

        // 指令链收尾的临时行（非空形态：不删行，菜单还在屏上）+ :2433 置脏。
        sink.printTemporaryLine("无效的值")
        sink.markGenerationUpdated()

        sink.noteInputWait(intWait(202L))
        assertEquals(
            listOf(410L, 411L),
            valuesOf(sink),
            "EmueraConsole.cs:789-796 else 支 —— lastButtonGeneration 不动，旧菜单仍是当前页",
        )
    }

    /**
     * 反面（判性锚点）：同样的序列**不置脏** ⇒ `:789-792` 真支把 lastButtonGeneration
     * 前移到 newButtonGeneration（上一轮 INT 等待已把它顶到 +1）⇒ 旧菜单整页失配。
     * 这就是 BOARD 缺口「点了没反应」的机制，也是置位通道存在的意义。
     */
    @Test
    fun `无置位时脚本行推进的重等使旧菜单整页失配`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(intWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink))

        sink.printTemporaryLine("无效的值")
        // 不调 markGenerationUpdated —— 复现缺口原状。

        sink.noteInputWait(intWait(202L))
        assertEquals(
            emptyList(),
            valuesOf(sink),
            "EmueraConsole.cs:789-792 真支 —— 旧按钮世代失配（缺口症状的可测形态）",
        )
    }

    /**
     * 通道语义反例：若在本点位按 `EmueraConsole.UpdateGeneration()`（`:770-774`）整句接
     * （`lastButtonGeneration = newButtonGeneration` + 置脏），旧按钮照样死 ——
     * 用「重等前打印一颗新按钮」复现该终态（盖戳同样把 last 抬到 new 并置脏，
     * `ConsoleButtonString.cs:91-92` + `:770-774`）证明「只置脏」是唯一正确映射。
     */
    @Test
    fun `整句UpdateGeneration映射在重显点位同样杀死旧菜单`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(intWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink))

        // UpdateGeneration 整句语义的等价终态：last = new = 1 且置脏（新按钮盖戳）。
        sink.printButton("重印按钮", 999L)
        sink.noteInputWait(intWait(202L))
        assertEquals(
            listOf(999L),
            valuesOf(sink),
            "lastButtonGeneration 被抬到新页 ⇒ 旧菜单不可点 —— :2433 不能走 :770-774 语义",
        )
    }

    /**
     * 连续两次非法输入（`:2433` 每轮分支①都执行）：每轮重等都置脏 ⇒ 旧菜单始终可点。
     */
    @Test
    fun `连续非法输入重显旧菜单始终可点`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(intWait(101L))

        sink.markGenerationUpdated()
        sink.noteInputWait(intWait(202L))
        assertEquals(listOf(410L, 411L), valuesOf(sink), "第一次重显后仍可点")

        sink.markGenerationUpdated()
        sink.noteInputWait(intWait(303L))
        assertEquals(
            listOf(410L, 411L),
            valuesOf(sink),
            "第二次重显后仍可点 —— INT 支不重复顶世代（:802-810 的 last != new 分支）",
        )
    }

    /** 通道只置脏：无等待时调用不产生任何可见副作用（无按钮落行、无快照变化）。 */
    @Test
    fun `通道本身不产生可见副作用`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        val before = sink.lines().size
        sink.markGenerationUpdated()
        assertEquals(before, sink.lines().size, "不改行表")
        assertTrue(sink.currentOptions().isEmpty(), "不改快照")
        assertTrue(sink.printingBufferEmpty, "不动打印缓冲")
    }

    // ------------------------------------------------------------------
    // 裸 INPUT（ANY_VALUE）的归支口径：原版 switch 把 AnyValue 归 STR 支
    // （EmueraConsole.cs:812-814），lastButtonIsInput 置 false；移植侧裸 INPUT
    // 构造为 type=INT + form=ANY_VALUE（StatementExecutor 的交付口径），世代侧
    // 必须按 form 归 STR 支，否则 flag 与 PC 反向，失配态重等的旧页生死错位。
    // ------------------------------------------------------------------

    private fun anyValueWait(scriptLine: Long) = InputRequest(
        type = InputRequest.InputType.INT,
        form = InputRequest.RequestForm.ANY_VALUE,
        scriptLine = scriptLine,
    )

    private fun strWait(scriptLine: Long) = InputRequest(
        type = InputRequest.InputType.STR,
        scriptLine = scriptLine,
    )

    /**
     * ANY（flag=false）→ 置脏 → INT 失配重等：`!flag` 为真 ⇒ last 前移 ⇒ 旧页死
     * （`EmueraConsole.cs:806-809`）。若 ANY 被误归 INT 支（flag=true），此处旧页会
     * 活着 —— 本用例即两种归支的可观测分岔。
     */
    @Test
    fun `裸INPUT归STR支 失配后INT重等按PC口径杀死旧页`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(anyValueWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink), "ANY 等待后旧菜单可点（last==按钮世代）")

        sink.markGenerationUpdated()
        sink.noteInputWait(intWait(202L))
        assertEquals(
            emptyList(),
            valuesOf(sink),
            "EmueraConsole.cs:806-809 —— ANY 归 STR 支 ⇒ flag=false ⇒ INT 失配重等前移 last，旧页死",
        )
    }

    /** 镜像：ANY（flag=false）→ 置脏 → STR 失配重等：flag 为假 ⇒ last 不动 ⇒ 旧页活（`:819-822`）。 */
    @Test
    fun `裸INPUT归STR支 失配后STR重等保旧页`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(anyValueWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink))

        sink.markGenerationUpdated()
        sink.noteInputWait(strWait(202L))
        assertEquals(
            listOf(410L, 411L),
            valuesOf(sink),
            "EmueraConsole.cs:819-822 —— flag=false 不前移 last，旧菜单保持可点",
        )
    }

    /** 同型重试护栏：ANY→ANY 同行重试走 STR 支失配分支，旧页活（与 PC STR 重试语义同型）。 */
    @Test
    fun `裸INPUT同行重试保旧页 与原版STR重试同型`() {
        val sink = LineBufferConsoleSink(clientWidth = 80)
        sink.printMenu()
        sink.noteInputWait(anyValueWait(101L))
        assertEquals(listOf(410L, 411L), valuesOf(sink))

        sink.noteInputWait(anyValueWait(101L))
        assertEquals(
            listOf(410L, 411L),
            valuesOf(sink),
            "EmueraConsole.cs:815-822 —— STR 支同行重试不动 last，旧菜单可点",
        )
    }
}
