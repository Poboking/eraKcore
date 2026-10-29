package com.eratw.port.core.proc

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.GlyphMask
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.GlyphRequest
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * M11-E hostwire —— 字形掩码宿主透出链路回归（CONTRACT-M11-E §5「hostwire」判据④）。
 *
 * 钉的是**接线**而不是栅格化：`GameSession → RootedBuiltinHost → ConsoleBuiltinHost`
 * 三段之后，消费点（`fn/GraphicsMethods.kt` GDRAWTEXT 段，glyph-core 路）按契约 §4 的
 * `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost` 必须拿到**注入的同一实例**
 * （identity 断言，不复制不改写）；未注入时属性在位但为 null ⇒ 消费点按「无宿主」
 * 回落 M11-D 推进盒。
 *
 * 委托形态结论（类 KDoc 的「七项显式覆写」坑不适用于本成员）：`glyphMaskHost` 的状态住在
 * [RootedBuiltinHost]，`ConsoleBuiltinHost` 用 `GlyphMaskHostProvider by delegate` 转发
 * 即正确 —— 与 [ConfigChainSource] 同路，见 `ConsoleBuiltinHost` 类 KDoc。
 *
 * 接口签名以 CONTRACT-M11-E §4 钉死版为准（`core/host/GlyphMaskHost.kt`，glyph-core 路
 * 新建；本文件按该签名构造 [GlyphRequest] / [GlyphMask]，签名漂移会在这里编译期爆掉）。
 */
private class FakeGlyphHost : GlyphMaskHost {
    var calls = 0
    var lastCp = -1
    var lastReq: GlyphRequest? = null

    /** 固定掩码：3x2、bearing (1,1)、alpha 行主序 6 字节（契约 §4 的载体形状）。 */
    val mask = GlyphMask(
        width = 3,
        height = 2,
        bearingX = 1,
        bearingY = 1,
        alpha = ByteArray(6) { (it * 40 + 15).toByte() },
    )

    override fun glyphMask(cp: Int, req: GlyphRequest): GlyphMask? {
        calls++
        lastCp = cp
        lastReq = req
        return if (cp == 0) null else mask
    }
}

class GlyphHostWireTest {

    private fun withTempRoot(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("eratw-glyph-wire")
        try {
            body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 消费点同款取值路径：先升上 [BuiltinHost] 接口，再按可选能力判定。 */
    private fun glyphViaConsumer(host: BuiltinHost): GlyphMaskHost? =
        (host as? GlyphMaskHostProvider)?.glyphMaskHost

    // ------------------------------------------------------------------
    // RootedBuiltinHost（注入口）
    // ------------------------------------------------------------------

    /** 缺省不注入：provider 在位但属性 null ⇒ GDRAWTEXT 回落推进盒（不抛、不猜宿主）。 */
    @Test
    fun `RootedBuiltinHost 缺省无字形宿主`() = withTempRoot { root ->
        val host: BuiltinHost = RootedBuiltinHost(root)
        assertNotNull(host as? GlyphMaskHostProvider, "RootedBuiltinHost 必须实现 provider 接口")
        assertNull(glyphViaConsumer(host))
    }

    /** 注入即透出**同一实例**（不复制不改写）；请求/掩码按契约 §4 形状原样往返。 */
    @Test
    fun `RootedBuiltinHost 透出注入实例本身`() = withTempRoot { root ->
        val fake = FakeGlyphHost()
        val host: BuiltinHost = RootedBuiltinHost(root, glyphMaskHost = fake)
        val got = glyphViaConsumer(host)
        assertSame(fake, got, "透出的必须是注入的那一个实例")
        val req = GlyphRequest(fontName = "黑体", sizePx = 16f, styleMask = 1)
        assertSame(fake.mask, got!!.glyphMask(0x41, req))
        assertEquals(1, fake.calls, "调用直达注入实例")
        assertEquals(0x41, fake.lastCp)
        assertEquals("黑体", fake.lastReq?.fontName)
        assertEquals(16f, fake.lastReq?.sizePx)
        assertEquals(1, fake.lastReq?.styleMask)
        assertNull(got.glyphMask(0, req), "cp=0 走 null 分支（调用方回落推进盒）")
    }

    // ------------------------------------------------------------------
    // ConsoleBuiltinHost（委托转发）
    // ------------------------------------------------------------------

    /** `by delegate` 转发后仍同一实例 —— 判据④的中间段。 */
    @Test
    fun `ConsoleBuiltinHost 经委托转发同一实例`() = withTempRoot { root ->
        val fake = FakeGlyphHost()
        val delegate = RootedBuiltinHost(root, glyphMaskHost = fake)
        val host: BuiltinHost = ConsoleBuiltinHost(root, null, delegate)
        assertSame(fake, glyphViaConsumer(host), "委托转发了实例本身，不是第二份包装")
    }

    /** 缺省 delegate（无注入）：provider 在位、读数 null，与未接线口径一致。 */
    @Test
    fun `ConsoleBuiltinHost 未注入时给 null`() = withTempRoot { root ->
        val host: BuiltinHost = ConsoleBuiltinHost(root)
        assertNotNull(host as? GlyphMaskHostProvider, "ConsoleBuiltinHost 必须带上 provider 能力面")
        assertNull(glyphViaConsumer(host))
    }

    // ------------------------------------------------------------------
    // GameSession（生产装配全链路）
    // ------------------------------------------------------------------

    /** 全链路：构造参 → RootedBuiltinHost → ConsoleBuiltinHost（builtinHost）同一实例。 */
    @Test
    fun `GameSession 装配链透出同一实例`() {
        withTempRoot { root ->
            val fake = FakeGlyphHost()
            val s = minimalSession(root, fake)
            try {
                assertSame(fake, glyphViaConsumer(s.builtinHost), "判据④：三段之后仍是同一实例")
                // s.builtinHost 即 GameSession 装配给 ProcessRuntime 的那只宿主
                // （GameSession.kt 第 7 步：`builtinHost = ConsoleBuiltinHost(gameRoot, console, rooted, …)`），
                // 消费方 c.host 拿到的就是它，无需从运行期二次取用。
            } finally {
                s.close()
            }
        }
    }

    /** 生产缺省（不传构造参）：链路在位、宿主 null ⇒ 现网行为零差（推进盒保持）。 */
    @Test
    fun `GameSession 缺省链路无字形宿主`() {
        withTempRoot { root ->
            val s = minimalSession(root, null)
            try {
                assertNotNull(s.builtinHost as? GlyphMaskHostProvider)
                assertNull(glyphViaConsumer(s.builtinHost))
            } finally {
                s.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 最小可装配会话（同 `GameSessionTest` 的小语料口径，单函数 `SYSTEM_TITLE`）：
     * 只为走通构造期装配，不 begin。
     */
    private fun minimalSession(root: Path, glyph: GlyphMaskHost?): GameSession {
        val parser = LineParser(
            SymbolTable().declareVariable("RESULT", OperandType.INT, 0),
        )
        val stmts = listOf(
            LogicalLine(LineKind.FUNCTION_LABEL, "TEST.ERB", 1, "@SYSTEM_TITLE"),
            LogicalLine(LineKind.CODE, "TEST.ERB", 2, "PRINTL PLAIN"),
        ).mapNotNull { parser.parseLogicalLine(it) }
        val compiler = ErbProgramCompiler()
        val fb = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
        val program = compiler.build("SYSTEM_TITLE")
        val meta = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB"),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = meta,
            consoleWidth = 199,
            randSeed = 20261024L,
            glyphMaskHost = glyph,
        )
    }
}
