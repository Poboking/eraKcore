package com.eratw.port.core.sem

import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 图形语句 → [GraphicsSink] 的**注入点**回归（M10-GFX 第 2 步：此前真机上这条线恒为 no-op）。
 *
 * ## 钉的是哪三件事
 *
 * 1. **缺省不接**：`ExecContext.graphics` 出厂就是 [NoopGraphicsSink]，六条图形语句照常推进、
 *    一个像素都不产生。语料闸 / E2E / bootSmoke 走的就是这条（`ProcessRuntime.graphicsSink`
 *    的新参数默认值与它同一个对象），所以这条用例是**反向防误接** —— 谁把 sink 改成
 *    「无条件真投递」，这里立刻炸，也就立刻把 4,893,609 指令那条闸打回重跑。
 * 2. **换上就生效**：`GameHost.installGraphics` 走的是 `execContext.graphics = AndroidGraphicsSink(…)`
 *    这条等价路（该字段是 `var`；语句层每次都现读 `ctx.graphics`，见
 *    `StatementExecutor.kt:385-422`）。本用例用同一个写法装 recorder，
 *    逐条核对**投递顺序与参数**，等价于「构造时传 `graphicsSink`」那条路。
 * 3. **参数口径**：`PRINT_IMG` 的 `param[0]=宽`、`param[1]=高`、`param[2]=ypos`
 *    （spec: `FunctionIdentifier.cs:427-439` —— C# 第 4 个实参取 `Param[1]` 且形参名叫 `height`，
 *    第 5 个取 `Param[0]` 形参名 `width`，`AndroidGraphicsSink` 按这个读法逐值一致）；
 *    `SETBGIMAGE` 的名字走 FORM 串求值（`StatementExecutor.kt:410` 的 `evalStr`），深度与不透明度
 *    两项本仓按整数取、原版按 `long.Parse(取文本)` 取（spec: `FunctionIdentifier.cs:1535`、`:1539`）
 *    —— 这条分歧由「背景三条语句…」用例按**现状**钉住并写明了卡点与修法归属。
 *
 * ## 顺带钉住的一条官方不对称
 *
 * `SKIPDISP` 之后 `PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE` 整条不投递
 * （spec: `FunctionIdentifier.cs:414`、`:454`、`:485` 三处 `if (!GlobalStatic.Process.SkipPrint)`），
 * 但 `SETBGIMAGE` / `REMOVEBGIMAGE` / `CLEARBGIMAGE` **照旧投递**
 * （spec: `:1519-1570` 三条都没有这个守卫）。本文件第三组用例钉的就是这个差别 ——
 * 背景层不是打印内容，跳过显示不影响它。
 *
 * 装置用 [SemTestEnv] 与真实行解析器（`LineParser`）：语句从 ERB 原文进来，
 * 不手搓 AST，免得测的是装置而不是解析器。
 */
private class RecordingGraphicsSink : GraphicsSink {
    val events = ArrayList<String>()

    override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {
        events.add(
            "img:$name|b=${nameb ?: "-"}|m=${namem ?: "-"}|" +
                params.joinToString(",") { "${it.first}${if (it.second) "px" else "%"}" },
        )
    }

    override fun printRect(params: List<Pair<Long, Boolean>>) {
        events.add("rect:" + params.joinToString(",") { "${it.first}${if (it.second) "px" else "%"}" })
    }

    override fun printSpace(params: List<Pair<Long, Boolean>>) {
        events.add("space:" + params.joinToString(",") { "${it.first}${if (it.second) "px" else "%"}" })
    }

    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {
        events.add("bg:$name|d=${depth ?: "-"}|o=${opacity ?: "-"}")
    }

    override fun removeBgImage(name: String) { events.add("bg-:$name") }
    override fun clearBgImages() { events.add("bg-clear") }
}

class GraphicsSinkInjectionTest {

    private val parser = LineParser(SymbolTable())

    private fun stmt(text: String): Statement =
        requireNotNull(parser.parseLogicalLine(LogicalLine(LineKind.CODE, "gfx.erb", 1, text))) {
            "行解析失败：$text"
        }

    private fun envWith(sink: GraphicsSink): SemTestEnv = SemTestEnv().also { it.ctx.graphics = sink }

    // ==================================================================
    // 1) 缺省 = NoopGraphicsSink（语料闸 / E2E 走的那条）
    // ==================================================================

    @Test
    fun `缺省注入是 NoopGraphicsSink 且六条语句都照常推进`() {
        val env = SemTestEnv()
        assertSame(
            NoopGraphicsSink, env.ctx.graphics,
            "ExecContext.graphics 的出厂值必须是 NoopGraphicsSink（语料闸与 E2E 的读数前提）",
        )
        for (line in GRAPHICS_LINES) {
            assertEquals(ExecResult.Normal, env.run(stmt(line)), "语句 $line 的返回语义不该变")
        }
        // 没有 sink 就没有任何副作用：控制台事件表必须一条都不多
        assertTrue(env.console.events.isEmpty(), "图形语句不该产生控制台输出：${env.console.events}")
    }

    // ==================================================================
    // 2) 装上就生效：GameHost 用的就是 var 赋值这条路
    // ==================================================================

    @Test
    fun `会话建好之后再换 sink 与构造时传参等价`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        assertSame(sink, env.ctx.graphics)
        env.run(stmt("PRINT_IMG \"a.webp\""))
        assertEquals(listOf("img:a.webp|b=-|m=-|"), sink.events)

        // 换掉第二次：语句层现读 ctx.graphics，不存在「构造期快照」
        val second = RecordingGraphicsSink()
        env.ctx.graphics = second
        env.run(stmt("CLEARBGIMAGE"))
        assertEquals(listOf("bg-clear"), second.events)
        assertEquals(1, sink.events.size, "旧 sink 不该再收到东西")
    }

    @Test
    fun `PRINT_IMG 的参数槽位是 宽 高 ypos`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        // spec: FunctionIdentifier.cs:427-439 —— Param[0]→width、Param[1]→height、Param[2]→ypos
        env.run(stmt("PRINT_IMG \"立绘.webp\", 300px, 200px, 40px"))
        assertEquals(listOf("img:立绘.webp|b=-|m=-|300px,200px,40px"), sink.events)
    }

    @Test
    fun `PRINT_IMG 的 nameb 与 namem 按位置取且空串原样交出`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        env.run(stmt("PRINT_IMG \"a.webp\", \"b.webp\", \"m.webp\", 10px"))
        assertEquals(listOf("img:a.webp|b=b.webp|m=m.webp|10px"), sink.events)

        // FunctionIdentifier.cs:423-426 的「空串置 null」在宿主侧做（AndroidGraphicsSink
        // 的 nameb?.takeIf { it.isNotEmpty() }），语句层这里必须**原样**交出空串，
        // 否则 sink 侧的复刻点就失去判据。
        sink.events.clear()
        env.run(stmt("PRINT_IMG \"a.webp\", \"\", \"m.webp\", 10px"))
        assertEquals(listOf("img:a.webp|b=|m=m.webp|10px"), sink.events)
    }

    @Test
    fun `PRINT_IMG 与 PRINT_RECT 不带 px 时是百分比标记`() {
        val sink = RecordingGraphicsSink()
        envWith(sink).run(stmt("PRINT_IMG \"a.webp\", 150, 10"))
        assertEquals(listOf("img:a.webp|b=-|m=-|150%,10%"), sink.events)

        val rect = RecordingGraphicsSink()
        envWith(rect).run(stmt("PRINT_RECT 10, 20, 300, 40"))
        assertEquals(listOf("rect:10%,20%,300%,40%"), rect.events)

        val shape = RecordingGraphicsSink()
        envWith(shape).run(stmt("PRINT_SPACE 50"))
        assertEquals(listOf("space:50%"), shape.events)
    }

    /**
     * `SETBGIMAGE` 的三参与一参形态。**这条用例同时钉住一处与 PC 的分歧（不修，交组长接线）**：
     *
     * 原版把三个参数都当**FORM 串**取文本再 `long.Parse`
     * （spec: `FunctionIdentifier.cs:1535` 的 `depth = long.Parse(TermList[1].GetStrValue(exm))`、
     * `:1539` 的 `opacity = (float)long.Parse(TermList[2].GetStrValue(exm)) / 255f`），
     * 所以 `SETBGIMAGE bg2.webp, 5, 200` 在 PC 上得到 depth 5、opacity 200。
     * 本仓 `StatementExecutor.kt:411-412` 对 FORM 串项调的是 `getIntValue` ⇒ **实读 0/0**，
     * 换到宿主侧就是不透明 0（背景整层不可见）。
     *
     * 判据状态：本作语料 `SETBGIMAGE` **0 处**（`ERB` 全树 grep 实测），所以这条不影响本波屏上
     * 取证；修法（两项改成 `evalStr(...).toLongOrNull() ?: 0L`）落在 `StatementExecutor.kt`，
     * 不是本路独占文件 ⇒ 按「现状钉住 + 需组长接线」处理，改的时候这条期望值要一起翻。
     */
    @Test
    fun `背景三条语句交出名字 深度 不透明度`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        // 语料里没有 SETBGIMAGE（实测 0 处），形态按原版 ERB 的规范写法：FORM 串**不带引号**。
        // 1 参形态：depth/opacity 都没给（原版在这里取 0 与 1f，FunctionIdentifier.cs:1530/1532）
        env.run(stmt("SETBGIMAGE bg.webp"))
        // 3 参形态：本仓当前把 FORM 串项按整数取 ⇒ 两个数都塌成 0（见本用例 KDoc）
        env.run(stmt("SETBGIMAGE bg2.webp, 5, 200"))
        env.run(stmt("REMOVEBGIMAGE bg.webp"))
        env.run(stmt("CLEARBGIMAGE"))
        assertEquals(
            listOf(
                "bg:bg.webp|d=-|o=-",
                "bg:bg2.webp|d=0|o=0",
                "bg-:bg.webp",
                "bg-clear",
            ),
            sink.events,
        )
    }

    // ==================================================================
    // 3) SKIPDISP 的官方不对称
    // ==================================================================

    @Test
    fun `SKIPDISP 抑制内联图形但不抑制背景层`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        env.run(instr(FunctionCode.SKIPDISP, intArg(1)))
        assertTrue(env.ctx.skipPrint, "SKIPDISP 1 应置位 skipPrint")

        env.run(stmt("PRINT_IMG \"a.webp\", 10px"))
        env.run(stmt("PRINT_RECT 100"))
        env.run(stmt("PRINT_SPACE 50"))
        assertEquals(emptyList(), sink.events, "三条内联段在 SKIPDISP 下整条不投递（spec: FunctionIdentifier.cs:414、:454、:485 三处守卫）")

        // 背景三条没有 SkipPrint 守卫（:1520-1567）
        env.run(stmt("SETBGIMAGE bg.webp"))
        env.run(stmt("REMOVEBGIMAGE bg.webp"))
        env.run(stmt("CLEARBGIMAGE"))
        assertEquals(listOf("bg:bg.webp|d=-|o=-", "bg-:bg.webp", "bg-clear"), sink.events)
    }

    @Test
    fun `SKIPDISP 之后重新放开仍能投递`() {
        val sink = RecordingGraphicsSink()
        val env = envWith(sink)
        env.run(instr(FunctionCode.SKIPDISP, intArg(1)))
        env.run(stmt("PRINT_IMG \"a.webp\", 10px"))
        assertTrue(sink.events.isEmpty())
        env.run(instr(FunctionCode.SKIPDISP, intArg(0)))
        env.run(stmt("PRINT_IMG \"a.webp\", 10px"))
        assertEquals(listOf("img:a.webp|b=-|m=-|10px"), sink.events)
    }

    private companion object {
        /** 六条图形语句各取一种真实形态（与 [GRAPHICS_LINES] 逐条对应到 C# 的六个入口）。 */
        val GRAPHICS_LINES = listOf(
            "PRINT_IMG \"a.webp\", 300px, 200px, 40px",
            "PRINT_RECT 10px, 20px, 300px, 40px",
            "PRINT_SPACE 50",
            "SETBGIMAGE bg.webp, 5, 200",
            "REMOVEBGIMAGE bg.webp",
            "CLEARBGIMAGE",
        )
    }
}
