package com.eratw.port.core.sql

import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.diag.FileReadTrail
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「找不到文件 X」族的**可判性验收**（2026-11-05 report-sqlroot）。
 *
 * 复现装置（不依赖语料）：同一句 `SQL_IMPORT_MAP_XML` 相对读，分别把宿主根装成
 * 「游戏根（表在位）」与「沙盒根（表没镜像）」—— 正是 2026-11-04 那红的两种落点。
 * 判据：
 * 1. **屏上原文一字未动**：CodeEE 消息逐字等于「SQL_IMPORT_MAP_XML：找不到文件 <相对路径>」
 *    （ADR-005：该函数是 eraTW fork 专有，reference/emuera-cs 全库 0 命中，官方口径不可核，不动文案）；
 * 2. 失败读进 [FileReadTrail]：读数行**一眼指出试的是哪个根**、解析落点、
 *    「出根被拒」与「根内缺文件」分得开；
 * 3. 成功读零留痕（只加可见性，不加行为）；
 * 4. 接进 [ErrorReport.extraSectionLines] 后整份报告能分出两根（端到端一行接线）。
 *
 * **摘除臂**：删掉 `SqlMethods.recordFileReadFailure` 的调用（或 `FileReadTrail.trailLines`
 * 的返回），本类用例①②③④里所有「读数行存在/含根/含出根被拒」断言即红——
 * 读数断掉的失败与屏上文案无关，文案用例仍绿（正是「只加可见性」的形状）。
 */
class SqlFileRootDiagnosticsTest {

    // ---- 装置（harness 形状同 SqlMethodsTest，自建避免跨文件私有依赖）----

    private class DiagBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("DiagBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** 无根读数的自定义宿主桩（FileOps 不按根解析，`as? FileRootReadout` 落空）。 */
    private class StubHost(override val fileOps: FileOps?) : BuiltinHost

    private class StubFiles : FileOps {
        override fun exists(path: String): Boolean = false
        override fun readText(path: String): String? = null
        override fun writeText(path: String, text: String, append: Boolean): Boolean = false
        override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
    }

    private class Env(val ctx: BuiltinEvalContext, val store: VariableStore)

    private fun env(host: BuiltinHost): Env {
        val base = DiagBaseCtx()
        val store = VariableStore()
        base.variables = store
        return Env(BuiltinEvalContext(base, store, host), store)
    }

    private fun intOf(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
        (c.callFunction(name, args.toList()) as Value.IntV).v

    private fun q(s: String): Value = Value.StrV(s)

    private fun trailOf(store: VariableStore): FileReadTrail =
        store.aux.peek<FileReadTrail>(FileReadTrail.AUX_KEY)
            ?: error("失败读没有建出 FileReadTrail 槽（读轴留痕断线）")

    // ---- ① 两根装置：读数一眼分出是哪一个根 ----

    @Test
    fun `同一句相对读在游戏根成功在沙盒根失败且读数点出试的根`() {
        val gameRoot = Files.createTempDirectory("eratw-gameroot")
        val sandboxRoot = Files.createTempDirectory("eratw-sandboxroot")
        val rel = "plugins/tw_csv_chs.xml"
        Files.createDirectories(gameRoot.resolve("plugins"))
        Files.write(gameRoot.resolve(rel), "<map><p><k>A</k><v>甲</v></p></map>".toByteArray(Charsets.UTF_8))

        // 游戏根 = 生产形状（会话宿主根取游戏根）：成功、**零留痕**。
        val game = env(ConsoleBuiltinHost(gameRoot))
        assertEquals(1L, intOf(game.ctx, "SQL_CONNECT", q("TR_DB")))
        assertEquals(1L, intOf(game.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q(rel)))
        assertTrue(FileReadTrail.trailLines(game.store).isEmpty(), "成功读一行都不许记（只加可见性）")

        // 沙盒根 = 2026-11-04 装置红的形状：同一句相对读落空。
        val sandbox = env(ConsoleBuiltinHost(sandboxRoot))
        assertEquals(1L, intOf(sandbox.ctx, "SQL_CONNECT", q("TR_DB")))
        val e = assertFailsWith<CodeEE> {
            intOf(sandbox.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q(rel))
        }
        // 屏上原文逐字未动（ADR-005；文案是否可改不归本路裁）。
        assertEquals("SQL_IMPORT_MAP_XML：找不到文件 $rel", e.message)

        val lines = FileReadTrail.trailLines(sandbox.store)
        assertEquals(2, lines.size, "应为「说明一行 + 读数一行」，实际：$lines")
        val readout = lines[1]
        assertTrue(readout.startsWith("SQL_IMPORT_MAP_XML "), "读数行必须点出函数名：$readout")
        assertTrue(readout.contains(sandboxRoot.toString()), "读数必须含**试过的宿主根**（沙盒根）：$readout")
        assertFalse(readout.contains(gameRoot.toString()), "沙盒根的失败不能混进游戏根字样：$readout")
        assertTrue(readout.contains("请求=$rel"), "读数必须含原样请求路径：$readout")
        assertTrue(readout.contains("常规文件=否"), "落点不存在要明说：$readout")
        assertFalse(readout.contains("出根被拒"), "根内缺文件不是越界拦截：$readout")

        // 游戏根装置对**同一形状**的缺表失败（plugins/inside.xml），读数换根 ⇒ 两根一眼可分。
        val e2 = assertFailsWith<CodeEE> {
            intOf(game.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/inside.xml"))
        }
        assertEquals("SQL_IMPORT_MAP_XML：找不到文件 plugins/inside.xml", e2.message)
        val readoutGame = trailOf(game.store).lines().single()
        assertTrue(readoutGame.contains(gameRoot.toString()), "游戏根装置读数含游戏根：$readoutGame")
        assertFalse(readoutGame.contains(sandboxRoot.toString()))

        // 端到端：接线一行进 extraSectionLines 后，整份报告分出是哪一个根。
        val report = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-11-05 00:00:00",
            errorType = "com.eratw.port.core.err.CodeEE",
            errorMessage = e.message!!,
            extraSectionLines = FileReadTrail.trailLines(sandbox.store),
        ).render()
        assertTrue(report.contains("[补充现场]"))
        assertTrue(report.contains("文件读取现场"))
        assertTrue(report.contains(sandboxRoot.toString()))
        assertFalse(report.contains(gameRoot.toString()))
        // 口径行原样在（本路没动隐私声明）。
        assertTrue(report.contains("不含存档数据与变量取值"))
    }

    // ---- ② 「出根被拒」与「根内缺文件」在读数里分得开 ----

    @Test
    fun `出根被拒的失败读写明越界闸拦截`() {
        val root = Files.createTempDirectory("eratw-outroot")
        val env = env(ConsoleBuiltinHost(root))
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("TR_DB")))

        val e = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("../outside.xml"))
        }
        assertEquals("SQL_IMPORT_MAP_XML：找不到文件 ../outside.xml", e.message)

        val entries = trailOf(env.store).lines()
        assertTrue(entries[0].contains("出根被拒"), "越界拦截要读得出来：${entries[0]}")
        assertFalse(entries[0].contains("常规文件"))

        // 同宿主的根内缺文件 ⇒ 另一支读数，两支不混。
        assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/none.xml"))
        }
        val second = trailOf(env.store).lines()[1]
        assertTrue(second.contains("常规文件=否"), "根内缺文件走存在性读数：$second")
        assertFalse(second.contains("出根被拒"))
    }

    // ---- ③ 无根读数的自定义宿主：不崩、记「宿主未提供根读数」 ----

    @Test
    fun `自定义宿主没有根读数时读数降级而不是抛`() {
        val env = env(StubHost(StubFiles()))
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("TR_DB")))
        val e = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/x.xml"))
        }
        assertEquals("SQL_IMPORT_MAP_XML：找不到文件 plugins/x.xml", e.message)
        val line = trailOf(env.store).lines().single()
        assertTrue(line.contains(FileReadTrail.NO_ROOT_MARK), "桩宿主应记「无根读数」：$line")
    }

    // ---- ④ 环形缓冲：旧读数被顶掉、不无限长 ----

    @Test
    fun `连续失败只保留最近十六条旧的先出环`() {
        val root = Files.createTempDirectory("eratw-ring")
        val env = env(ConsoleBuiltinHost(root))
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("TR_DB")))
        for (i in 0 until 20) {
            assertFailsWith<CodeEE> {
                intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/f%02d.xml".format(i)))
            }
        }
        val entries = trailOf(env.store).lines()
        assertEquals(16, entries.size)
        assertTrue(entries.last().contains("f19.xml"), "最新一条必须在：${entries.last()}")
        assertFalse(entries.any { it.contains("f03.xml") }, "第 4 条（f03）应已被顶出环")
        assertTrue(entries.first().contains("f04.xml"), "环首应是第 5 次失败：${entries.first()}")
    }

    // ---- ⑤ 隐私退化口径：fullRoot=false 只留末两节 ----

    @Test
    fun `掩码口径只保留根路径末两节`() {
        val root: Path = Files.createTempDirectory("eratw-mask").resolve("host-root")
        Files.createDirectories(root)
        val env = env(ConsoleBuiltinHost(root))
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("TR_DB")))
        assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/none.xml"))
        }
        val masked = FileReadTrail.trailLines(env.store, fullRoot = false)
        assertFalse(masked.any { it.contains(root.toString()) }, "掩码口径不得出现绝对根：$masked")
        assertTrue(masked[1].contains("host-root"), "末段仍要读得出是哪一格：${masked[1]}")
    }

    // ---- ⑥ 未连接分支不触发留痕（返 0 不抛的既有口径原样） ----

    @Test
    fun `未连接库返零不抛也不留痕`() {
        val env = env(ConsoleBuiltinHost(Files.createTempDirectory("eratw-nodb")))
        assertEquals(0L, intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("NO_DB"), q("CSV_MAP"), q("plugins/x.xml")))
        assertTrue(FileReadTrail.trailLines(env.store).isEmpty())
    }
}
