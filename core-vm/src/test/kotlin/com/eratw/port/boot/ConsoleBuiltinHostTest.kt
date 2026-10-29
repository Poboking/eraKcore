package com.eratw.port.boot

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.consoleStyleOf
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ConsoleTextBox
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CONSOLE-HOST（M5-A h-host）：生产类 [ConsoleBuiltinHost] 的委托口径回归。
 *
 * 被测的是 M4-E 在开局装置里补的 `BootBuiltinHost` 生产化后的四个显式覆写。
 * Kotlin 的 `BuiltinHost by delegate` 会把**带默认实现**的接口成员转发给委托对象，
 * 默认实现里读的是委托的 `consoleSink`（[RootedBuiltinHost] 没有控制台，恒 null），
 * 看不到本类属性 —— 所以 consoleSink / consoleStyle / lineIsEmpty / configOverrides
 * 必须在类体里覆写。本类的断言一律经 `BuiltinHost` 接口引用读，专门压这条陷阱。
 *
 * 语义出处（逐项与生产类 KDoc 同源）：
 * - 样式状态只住在控制台对象上：`userStyle`（EmueraConsole.cs:208）、`StringStyle`（:491）、
 *   `bgColor`（:200）、`Alignment`（:493-503）、`Redraw`（:455）；读出侧
 *   FunctionMethodCreator.cs:3648-3745、:3793-3806；
 * - LINEISEMPTY = `PrintBuffer.IsEmpty`（EmueraConsole.cs:505；PrintStringBuffer.cs:39-48），
 *   即**待决打印缓冲**空态，不是上一显示行；
 * - configOverrides 读 `<root>/emuera.config`（ConfigData.cs:33、:491-492；键大写形
 *   AConfigItem.cs:22-23）；
 * - 写失败返回 0：FunctionMethodCreator.cs:8010-8014（SAVETEXT 的 catch → 0L）。
 */
class ConsoleBuiltinHostTest {

    private fun <T> withTempRoot(prefix: String, body: (Path) -> T): T {
        val root = Files.createTempDirectory(prefix)
        try {
            return body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 生产宿主 → 内建函数求值上下文（同 ConsoleQueryMethodsTest 的假宿主形状）。 */
    private fun hostCtx(host: BuiltinHost): BuiltinEvalContext {
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                throw CodeEE("hostCtx 不应被直接调用：$name")
            override fun printSystemLine(message: String) {}
        }
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun iv(ctx: BuiltinEvalContext, name: String): Long =
        (ctx.callFunction(name, emptyList()) as Value.IntV).v

    @Test
    fun `委托后 consoleSink 与 consoleStyle 读本类属性而不是委托`() =
        withTempRoot("eratw-console-host") { root ->
            val console = LineBufferConsoleSink(clientWidth = 200)
            console.setForeColor(12, 34, 56)
            console.setBackColor(200, 100, 50)
            console.setFontStyle(5)
            console.setFont("Saitamaar")
            console.setAlignment(Alignment.CENTER)
            console.setRedraw(1)

            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // 接口委托的默认实现会在 RootedBuiltinHost 上求值 → null；显式覆写必须给出本类的 sink。
            assertSame(console, host.consoleSink, "consoleSink 被转发给了委托对象")

            // 缺省 consoleStyle 也走委托的 consoleSink（null）→ null；这里必须拿到现算快照。
            val style = assertNotNull(host.consoleStyle, "consoleStyle 被转发给了委托对象")
            assertEquals(listOf(12, 34, 56), style.fore?.toList())
            assertEquals(listOf(200, 100, 50), style.back?.toList())
            assertEquals(5, style.fontStyleMask)
            assertEquals("Saitamaar", style.fontName)
            assertEquals(1, style.alignment) // CENTER = 1（DisplayLineAlignment.cs:3-8 声明序）
            assertEquals(1, style.redraw)

            // 与唯一装配点 consoleStyleOf(console) 逐项同值：快照是派生视图，不是第二份状态。
            val expected = consoleStyleOf(console)
            assertEquals(expected.defFore.toList(), style.defFore.toList())
            assertEquals(expected.defBack.toList(), style.defBack.toList())
            assertEquals(expected.focus.toList(), style.focus.toList())
            assertEquals(expected.fontStyleMask, style.fontStyleMask)
            assertEquals(expected.fontName, style.fontName)
            assertEquals(expected.alignment, style.alignment)
            assertEquals(expected.redraw, style.redraw)
        }

    @Test
    fun `lineIsEmpty 跟随 printingBufferEmpty 而不是显示行`() =
        withTempRoot("eratw-console-buffer") { root ->
            val console = LineBufferConsoleSink(clientWidth = 200)
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            assertTrue(console.printingBufferEmpty)
            assertTrue(host.lineIsEmpty, "初始无待决内容 → LINEISEMPTY=1")

            // PRINTN/PRINT 只入缓冲，flush 前 lineIsEmpty 必须为 false（EmueraConsole.cs:505）。
            console.print("待决")
            assertFalse(console.printingBufferEmpty)
            assertFalse(host.lineIsEmpty, "有未 flush 内容 → LINEISEMPTY=0")

            console.newLine()
            assertTrue(console.printingBufferEmpty)
            // 此时显示行已经产生（上一显示行非空），但 lineIsEmpty 仍为 true：
            // 口径是「待决缓冲空」而不是「上一显示行是否为空」，M4-E 的装置行为原样保留。
            assertTrue(console.lines().isNotEmpty())
            assertTrue(host.lineIsEmpty, "flush 后必空 → LINEISEMPTY=1")
        }

    @Test
    fun `configOverrides 与 RootedBuiltinHost 同源 其余成员仍走委托`() =
        withTempRoot("eratw-console-config") { root ->
            // 语料形状的键行（BOM 省略；键形与 EmueraConfigGoldenTest 的外部读数同源）：
            Files.writeString(
                root.resolve("emuera.config"),
                "USE SAV FOLDER:YES\n" +
                    "DEFAULT ANSI ENCODING:CHINESE_HANS\n" +
                    "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n",
            )
            val delegate = RootedBuiltinHost(root) { 1_234_567L }
            val host: BuiltinHost = ConsoleBuiltinHost(root, null, delegate)

            // 同源 = 同一个 Map 实例：显式转发，而不是在委托对象之外再装配一份表。
            assertSame(
                delegate.configOverrides,
                host.configOverrides,
                "configOverrides 被转发给了委托对象（应读委托的同一份表）",
            )
            assertEquals("CHINESE_HANS", host.configOverrides["DEFAULT ANSI ENCODING"])
            assertEquals(
                "xml,txt,json",
                host.configOverrides["VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT"],
            )
            assertEquals(3, host.configOverrides.size)

            // 未覆写的成员（时钟）继续走接口委托：注入值必须原样透出。
            assertEquals(1_234_567L, host.clockMillis())
        }

    @Test
    fun `写请求仍被拦截且读枚举按游戏根解析`() =
        withTempRoot("eratw-console-writes") { root ->
            Files.createDirectories(root.resolve("resources/sub"))
            Files.writeString(root.resolve("resources/sub/a.xml"), "<sprites/>")
            Files.writeString(root.resolve("resources/sub/b.txt"), "keep")

            val host = ConsoleBuiltinHost(root)
            val ops = host.fileOps

            // 读 / 存在 / 递归枚举（ENUMFILES 口径）原样透传 RootedFileOps。
            assertEquals("<sprites/>", ops.readText("resources/sub/a.xml"))
            assertTrue(ops.exists("resources/sub/a.xml"))
            assertEquals(listOf("resources/sub/a.xml"), ops.list("resources", "*.xml", true))

            // 写一律拒绝并计数，且不落盘（语料只读铁律）。
            assertFalse(ops.writeText("resources/sub/LOG.txt", "x", false))
            assertFalse(Files.exists(root.resolve("resources/sub/LOG.txt")), "被拦截的写不许落盘")
            assertFalse(ops.writeText("resources/sub/b.txt", "overwrite", false))
            assertEquals("keep", Files.readString(root.resolve("resources/sub/b.txt")))
            assertEquals(2, host.writes.blockedWrites)

            // 越界路径按「不存在」处理（BootHosts.resolvePath 的根边界）。
            assertNull(ops.readText("../outside.txt"))
            assertFalse(ops.exists("../../etc/passwd"))

            // 生产类保留写拦截能力：fileOps 恒为只读装饰，append 分支同样拒绝。
            assertFalse(ops.writeText("resources/sub/c.txt", "x", true))
            assertEquals(3, host.writes.blockedWrites)
        }

    @Test
    fun `textBox 覆写后控制台状态可读写`() =
        withTempRoot("eratw-console-textbox") { root ->
            val host: BuiltinHost = ConsoleBuiltinHost(root)
            val box = assertNotNull(host.textBox, "经 BuiltinHost 接口读到本类覆写，而不是委托的 null")
            box.setText("hello")
            assertEquals("hello", box.text)
            box.moveTextBox(10, 20, 300)
            val state = box as ConsoleTextBox
            assertEquals(Triple(10, 20, 300), Triple(state.x, state.y, state.width))
            box.resetTextBoxPos()
            assertEquals(Triple(0, 0, 0), Triple(state.x, state.y, state.width))
        }

    @Test
    fun `无控制台时三项控制台属性按未接线口径回答`() =
        withTempRoot("eratw-console-none") { root ->
            val host: BuiltinHost = ConsoleBuiltinHost(root)
            assertNull(host.consoleSink)
            assertNull(host.consoleStyle, "consoleSink 为 null 时不许编造快照")
            assertFalse(host.lineIsEmpty, "未接线 → false（BuiltinHost 的缺省口径）")
        }

    // ===== GFX-06：脚本像素尺度三件套（fontSize / clientWidth / clientHeight）=====

    @Test
    fun `缺省像素尺度保持出厂值 headless 零行为差`() =
        withTempRoot("eratw-console-metrics-default") { root ->
            val host: BuiltinHost = ConsoleBuiltinHost(root)
            // 出厂缺省逐项钉死：ConfigData.cs:66 FontSize=18、:56 WindowX=760、
            // :57,67 WindowY(480)-LineHeight(19)=461。经接口引用读 —— 构造属性覆写
            // 不许落在委托（RootedBuiltinHost）上，否则注入值全部失踪。
            assertEquals(18L, host.fontSize)
            assertEquals(BuiltinHost.DEFAULT_FONT_SIZE, host.fontSize)
            assertEquals(760L, host.clientWidth)
            assertEquals(BuiltinHost.DEFAULT_CLIENT_WIDTH, host.clientWidth)
            assertEquals(461L, host.clientHeight)
            assertEquals(BuiltinHost.DEFAULT_CLIENT_HEIGHT, host.clientHeight)
        }

    @Test
    fun `注入真机度量后 像素尺度内建按注入值回答`() =
        withTempRoot("eratw-console-metrics-inject") { root ->
            // 真机形状：字号 42px、内容区 2400x1100 px；sink 列宽（半角格）与像素是两个量，
            // 互不影响（折行口径不动，GFX-06 只换 CLIENTWIDTH 一族的读数）。
            val console = LineBufferConsoleSink(clientWidth = 110)
            val host: BuiltinHost = ConsoleBuiltinHost(
                root,
                console,
                fontSize = 42,
                clientWidth = 2400,
                clientHeight = 1100,
            )
            assertEquals(42L, host.fontSize, "构造属性覆写必须压过接口委托的转发")
            assertEquals(2400L, host.clientWidth)
            assertEquals(1100L, host.clientHeight)

            // 函数层链路：CLIENTWIDTH()/CLIENTHEIGHT() 现读 host
            // （ConsoleQueryMethods.kt:131/136，原版 ClientSizeMethod FunctionMethodCreator.cs:6789-6797）。
            val ctx = hostCtx(host)
            assertEquals(2400L, iv(ctx, "CLIENTWIDTH"))
            assertEquals(1100L, iv(ctx, "CLIENTHEIGHT"))
            // 控制台折行仍按 sink 列宽 110，不被像素注入波及。
            assertEquals(110, console.clientWidth)
        }

    @Test
    fun `注入的 fontSize 进入 HTML 家族换算基准`() =
        withTempRoot("eratw-console-metrics-font") { root ->
            val host: BuiltinHost = ConsoleBuiltinHost(root, null, fontSize = 42)
            val ctx = hostCtx(host)
            // HTML_STRINGLEN 第二参非 0 = 原始像素宽（HtmlMethods.kt:72-83，spec
            // FunctionMethodCreator.cs:58-66）；宿主未接 measureText 时走内置等宽近似
            // approxTextWidth(s, host.fontSize)：全角 = 1 个 fontSize、半角 = 一半。
            // 全角「一」与两个半角 AB 在 42px 下都 ≈ 42px；缺省 18 时同为 18px ——
            // 换算基准真的从 18 换成了注入值，才对得上真机字形。
            fun plainLen(s: String): Long =
                (ctx.callFunction("HTML_STRINGLEN", listOf(Value.StrV(s), Value.IntV(1))) as Value.IntV).v
            assertEquals(42L, plainLen("一"), "全角 = fontSize px（注入 42）")
            assertEquals(42L, plainLen("AB"), "半角 = fontSize/2 px（注入 42）")
        }
}
