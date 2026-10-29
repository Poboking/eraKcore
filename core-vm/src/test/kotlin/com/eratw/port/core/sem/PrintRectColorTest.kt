package com.eratw.port.core.sem

import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记录 PRINT_RECT 带色重载投递的假 sink；单参旧入口单独计数。
 */
private class RectColorSink : GraphicsSink {
    /** 一次带色投递：参数原样 + rgb。 */
    data class RectCall(val params: List<Pair<Long, Boolean>>, val rgb: Int)

    val rectCalls = ArrayList<RectCall>()
    val spaceCalls = ArrayList<List<Pair<Long, Boolean>>>()

    /** 单参旧入口的调用次数；正常路径必须恒为 0（语句层已改调带色重载）。 */
    var legacyRectCalls = 0

    override fun printImage(
        name: String,
        nameb: String?,
        namem: String?,
        params: List<Pair<Long, Boolean>>,
    ) {
    }

    override fun printRect(params: List<Pair<Long, Boolean>>) {
        legacyRectCalls++
    }

    override fun printRect(params: List<Pair<Long, Boolean>>, rgb: Int) {
        rectCalls.add(RectCall(params, rgb))
    }

    override fun printSpace(params: List<Pair<Long, Boolean>>) {
        spaceCalls.add(params)
    }

    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}

    override fun removeBgImage(name: String) {}

    override fun clearBgImages() {}
}

/**
 * PRINT_RECT 的取色通道（GFX-05）：`SETCOLOR` 之后必须跟色，未设色回落出厂值。
 *
 * ## 语义出处
 *
 * - 原版 `PrintShape` 把执行时点的 `userStyle.Color` 冻进形状段
 *   （EmueraConsole.cs:3197-3199 → ConsoleShapePart.cs:76），
 *   `ConsoleRectangleShapePart` 用 `SolidBrush(color)` 实心填充（ConsoleRectangleShapePart.cs:46）；
 * - `SETCOLOR` 写 `userStyle.Color`（Process.cs:1409-1437 → EmueraConsole.cs:2820-2824）；
 * - `userStyle.Color` 的初值 = `Config.ForeColor` = 192,192,192
 *   （EmueraConsole.cs:208、ConfigData.cs:68），即本侧 0xC0C0C0。
 *
 * ## 装置
 *
 * 控制台用**生产实现** [LineBufferConsoleSink]：前景色的唯一事实就在它身上
 * （`ConsoleSink.foregroundColor` 的读出侧口径），`SETCOLOR`/`RESETCOLOR` 走真实语句分发；
 * 图形 sink 用记录型假实现，只观察 [GraphicsSink.printRect] 带色重载收到的 RGB。
 * 断言「单参旧入口一次都没被调用」可区分「语句层走了新重载」与「只是接口缺省实现兜底」。
 *
 * alpha 不在本文件的观察面：原版颜色没有 alpha 可传（SETCOLOR 只接受 1 个 RGB 或
 * 3 个分量，ArgumentParser.cs:1911-1955），平台侧按不透明合成，见
 * `AndroidGraphicsSink.printRect` 覆写的 KDoc。
 */
class PrintRectColorTest {

    private val parser = LineParser(SymbolTable())

    private fun stmt(text: String): Statement =
        requireNotNull(parser.parseLogicalLine(LogicalLine(LineKind.CODE, "rect.erb", 1, text))) {
            "行解析失败：$text"
        }

    /** 最小装配：生产控制台持色 + 记录型图形 sink + 真实语句执行器。 */
    private class Env {
        val store = VariableStore()
        val console = LineBufferConsoleSink()
        val sink = RectColorSink()
        val executor = StatementExecutor()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
        ).also { it.graphics = sink }
    }

    private fun run(env: Env, text: String) {
        env.executor.execute(stmt(text), env.ctx)
    }

    @Test
    fun `SETCOLOR 三分量后 PRINT_RECT 收到该 RGB`() {
        val env = Env()
        run(env, "SETCOLOR 255, 0, 0")
        run(env, "PRINT_RECT 10, 20, 300, 40")

        assertEquals(1, env.sink.rectCalls.size, "PRINT_RECT 应投递一次")
        val call = env.sink.rectCalls.single()
        assertEquals(0xFF0000, call.rgb, "填充色跟 SETCOLOR（EmueraConsole.cs:2820-2824 → :3197-3199）")
        // 取色不改变尺寸参数的口径（仍按 ERB 书写顺序原样转发）
        assertEquals(
            listOf(10L to false, 20L to false, 300L to false, 40L to false),
            call.params,
        )
        assertEquals(0, env.sink.legacyRectCalls, "语句层必须走带色重载，不能退回单参旧入口")
    }

    @Test
    fun `SETCOLOR 单参 RGB 后 PRINT_RECT 也跟色`() {
        val env = Env()
        // Process.cs:1409-1437 的 RGB 分支：从 0xRRGGBB 拆三段
        run(env, "SETCOLOR 0x00FF00")
        run(env, "PRINT_RECT 5px")

        assertEquals(listOf(0x00FF00), env.sink.rectCalls.map { it.rgb })
        assertEquals(0, env.sink.legacyRectCalls)
    }

    @Test
    fun `未 SETCOLOR 时回落 Config ForeColor`() {
        val env = Env()
        // 出厂态 userStyle.Color = Config.ForeColor（EmueraConsole.cs:208、ConfigData.cs:68）；
        // ConsoleSink.foregroundColor 为 null 只表示「未显式设置」，不是「无颜色」。
        run(env, "PRINT_RECT 100")

        assertEquals(listOf(0xC0C0C0), env.sink.rectCalls.map { it.rgb })
        assertEquals(0, env.sink.legacyRectCalls)
    }

    @Test
    fun `RESETCOLOR 后回落 Config ForeColor`() {
        val env = Env()
        // RESETCOLOR = SetStringStyle(Config.ForeColor)（FunctionIdentifier.cs:1501）
        run(env, "SETCOLOR 255, 0, 0")
        run(env, "RESETCOLOR")
        run(env, "PRINT_RECT 10, 20, 300, 40")

        assertEquals(listOf(0xC0C0C0), env.sink.rectCalls.map { it.rgb })
    }

    @Test
    fun `SETCOLOR 黑色是合法值而不是未设色`() {
        val env = Env()
        // 0x000000 是 SETCOLOR 的合法结果（Process.cs:1427-1434 只查 0..255 边界），
        // 不能被「rgb == 0 即缺省」的写法吞掉。
        run(env, "SETCOLOR 0, 0, 0")
        run(env, "PRINT_RECT 10, 20, 300, 40")

        assertEquals(listOf(0x000000), env.sink.rectCalls.map { it.rgb })
    }

    @Test
    fun `PRINT_SPACE 不经过取色通道且不受 SETCOLOR 影响`() {
        val env = Env()
        run(env, "SETCOLOR 255, 0, 0")
        run(env, "PRINT_SPACE 50")

        assertTrue(env.sink.rectCalls.isEmpty(), "PRINT_SPACE 不该产生矩形段")
        assertEquals(listOf(listOf(50L to false)), env.sink.spaceCalls, "PRINT_SPACE 的参数口径不变")
        assertEquals(0xFF0000, env.console.foregroundColor, "控制台前景色也不该被 PRINT_SPACE 改写")
    }

    @Test
    fun `只实现单参旧入口的 sink 经接口缺省实现仍收到矩形`() {
        val calls = ArrayList<List<Pair<Long, Boolean>>>()
        val legacyOnly = object : GraphicsSink {
            override fun printImage(
                name: String,
                nameb: String?,
                namem: String?,
                params: List<Pair<Long, Boolean>>,
            ) {
            }

            override fun printRect(params: List<Pair<Long, Boolean>>) {
                calls.add(params)
            }

            override fun printSpace(params: List<Pair<Long, Boolean>>) {}

            override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}

            override fun removeBgImage(name: String) {}

            override fun clearBgImages() {}
        }
        // 接口缺省实现 = 委托单参旧入口：既有实现类与测试假 sink 不改一行也能编译并收到矩形
        // （GFX-RECT 要求 1；生产侧由 AndroidGraphicsSink 覆写带色重载）。
        legacyOnly.printRect(listOf(100L to false), 0xFF0000)

        assertEquals(listOf(listOf(100L to false)), calls)
    }
}
