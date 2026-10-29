package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HOST-05（CONTRACT-M11-B §2 L1）可写注入口回归：真实 [ConsoleBuiltinHost] 装配下验证
 * [WriteBlockedFileOps] 的二态写闸与 SAVETEXT / OUTPUTLOG 的真写路径。
 *
 * 判据出处（全部来自反编译 C#，不是由实现反推）：
 * - SAVETEXT = `File.WriteAllText(text, strValue, Config.SaveEncode)`
 *   （FunctionMethodCreator.cs:8010），`Config.SaveEncode` 初值即 UTF8BOMEncoding
 *   （Config.cs:19，无配置可改）⇒ 落盘字节 = EF BB BF + UTF-8 正文，catch → 0
 *   （:8012-8015），成功 → 1（:8016）；
 * - OUTPUTLOG = `File.WriteAllText(fullpath, log, EncodingHandler.UTF8BOMEncoding)`
 *   （EmueraConsole.cs:3449），内容 = GetLog 的「环境头 + 逐条显示行 ToString + AppendLine」
 *   （:3507-3545，显示行循环在 :3540-3542）；成功后 PrintSystemLine（:3474-3480）。
 * - 缺省拒写口径 = 本仓生产现状（M4-E 起），与 golden/E2E 基线一致，不许回退。
 * - 写路径边界 = [RootedFileOps] 的根内判定（BootHosts.kt 的 resolvePath：
 *   normalize 后必须仍在根内，绝对出根与「上跳出根」一律 null ⇒ 写返回 false）。
 */
class FileOpsWriteWireTest {

    // ===== 1. 缺省仍拒写（golden/E2E 零差的前提） =====

    @Test
    fun `缺省无 writeRoot 时写请求仍被拒并计数`() = withTempRoot("eratw-wire-default") { root ->
        val host = ConsoleBuiltinHost(root)
        assertEquals(0, host.writes.blockedWrites)

        assertFalse(host.fileOps.writeText("out.txt", "X", append = false), "FileOps 直写应被拒")
        assertFalse(Files.exists(root.resolve("out.txt")), "不应落盘")
        assertEquals(1, host.writes.blockedWrites, "拒写应计数")

        // SAVETEXT 函数侧同样拿 0（FunctionMethodCreator.cs:8012-8015 catch → 0）
        assertEquals(0L, wireInt(host, "SAVETEXT", str("A"), str("out.txt")))
        assertEquals(2, host.writes.blockedWrites)
        assertFalse(Files.exists(root.resolve("out.txt")))
    }

    @Test
    fun `缺省 OUTPUTLOG 被闸拒写 不落盘仍返回 1`() = withTempRoot("eratw-wire-default-log") { root ->
        val console = LineBufferConsoleSink(clientWidth = 199)
        console.printLine("LINE-A")
        val host = ConsoleBuiltinHost(root, console)
        // 写失败 = outputLog 的 catch 分支（EmueraConsole.cs:3450-3454）→ OutputLog 返回 false，
        // 函数恒返回 1（FunctionMethodCreator.cs:8765，fn 层既有口径）。
        assertEquals(1L, wireInt(host, "OUTPUTLOG", str("GOLDEN_LOG.txt")))
        assertFalse(Files.exists(root.resolve("GOLDEN_LOG.txt")), "缺省口径不得落盘")
        assertEquals(1, host.writes.blockedWrites)
    }

    // ===== 2. 注入后写成功 =====

    @Test
    fun `注入 writeRoot 后 SAVETEXT 真写且带 UTF-8 BOM`() = withTempRoot("eratw-wire-savetext") { root ->
        val host = ConsoleBuiltinHost(root, writeRoot = root)
        assertEquals(1L, wireInt(host, "SAVETEXT", str("ABC"), str("out.txt")))

        val bytes = Files.readAllBytes(root.resolve("out.txt"))
        // 原版 Config.SaveEncode = UTF8BOMEncoding（Config.cs:19）⇒ EF BB BF + UTF-8 正文
        assertEquals(6, bytes.size, "应为 6 字节（EF BB BF + ABC）")
        assertEquals(0xEF, bytes[0].toInt() and 0xFF, "BOM 第 1 字节")
        assertEquals(0xBB, bytes[1].toInt() and 0xFF, "BOM 第 2 字节")
        assertEquals(0xBF, bytes[2].toInt() and 0xFF, "BOM 第 3 字节")
        assertEquals("ABC", String(bytes, 3, 3, Charsets.UTF_8))

        // 读路径不受注入口影响，仍以 gameRoot 为根 ⇒ 写出去的文件 LOADTEXT 读得回
        assertEquals("ABC", wireStr(host, "LOADTEXT", str("out.txt")))
        assertEquals(0, host.writes.blockedWrites, "放行态下不再计数")
    }

    @Test
    fun `注入 writeRoot 后 OUTPUTLOG 真写日志`() = withTempRoot("eratw-wire-outputlog") { root ->
        val console = LineBufferConsoleSink(clientWidth = 199)
        console.printLine("LINE-A")
        val host = ConsoleBuiltinHost(root, console, writeRoot = root)
        assertEquals(1L, wireInt(host, "OUTPUTLOG", str("GOLDEN_LOG.txt")))

        // GetLog 的显示行循环（EmueraConsole.cs:3540-3542）+ UTF8BOMEncoding（:3449）
        val text = Files.readString(root.resolve("GOLDEN_LOG.txt"))
        assertEquals("\uFEFFLINE-A\r\n", text)
    }

    // ===== 3. 出根路径被拒（边界与 RootedFileOps 一致，不逃逸） =====

    @Test
    fun `注入后出根路径仍被拒`() = withTempRoot("eratw-wire-escape") { root ->
        val outside = Files.createTempDirectory("eratw-wire-outside")
        try {
            val host = ConsoleBuiltinHost(root, writeRoot = root)
            // 相对路径上跳出根（normalize 后越过根）⇒ resolvePath null ⇒ 拒写
            assertFalse(host.fileOps.writeText("../escape.txt", "X", append = false))
            // 多级上跳同样拒（".." 逐段归一后仍在根外）
            assertFalse(host.fileOps.writeText("sub/../../escape.txt", "X", append = false))
            // 绝对路径落在根外 ⇒ 拒写
            assertFalse(host.fileOps.writeText(outside.resolve("abs.txt").toString(), "X", append = false))

            assertFalse(Files.exists(outside.resolve("escape.txt")), "根外不得出现文件")
            assertFalse(Files.exists(outside.resolve("abs.txt")), "根外不得出现文件")
            assertFalse(Files.exists(root.resolve("sub")), "被拒路径不得创建父目录")
            assertFalse(Files.exists(root.parent.resolve("escape.txt")), "根外不得出现文件")
        } finally {
            runCatching {
                Files.walk(outside).use { s ->
                    s.sorted(java.util.Comparator.reverseOrder<Path>()).toList()
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    // ===== 4. OUTPUTLOG 缺 consoleLines 仍按原口径报错 =====

    @Test
    fun `OUTPUTLOG 缺 consoleLines 仍按未接线口径抛 CodeEE`() =
        withTempRoot("eratw-wire-noconsole") { root ->
            // 注入 writeRoot 只解决写路径；consoleLines 缺失的口径不变
            // （fn 层 DebugMiscMethods.outputLog 的「控制台行缓冲尚未接线」分支）
            val host = ConsoleBuiltinHost(root, null, writeRoot = root)
            val e = assertFailsWith<CodeEE> { wireInt(host, "OUTPUTLOG", str("x.log")) }
            assertTrue(e.message!!.contains("BuiltinHost.consoleLines"), "实际：${e.message}")
            assertFalse(Files.exists(root.resolve("x.log")), "报错前不应落盘")
        }

    // ===== 装置 =====

    private fun <T> withTempRoot(prefix: String, body: (Path) -> T): T {
        val root = Files.createTempDirectory(prefix)
        try {
            return body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s ->
                    s.sorted(java.util.Comparator.reverseOrder<Path>()).toList()
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** 只承接 [BuiltinEvalContext] 的变量回调；内建函数求值不经过它。 */
    private class WireBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("WireBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun wireEval(host: ConsoleBuiltinHost, name: String, vararg args: Value): Value {
        val store = VariableStore()
        val base = WireBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host).callFunction(name, args.toList())
    }

    private fun wireInt(host: ConsoleBuiltinHost, name: String, vararg args: Value): Long =
        (wireEval(host, name, *args) as Value.IntV).v

    private fun wireStr(host: ConsoleBuiltinHost, name: String, vararg args: Value): String =
        (wireEval(host, name, *args) as Value.StrV).v

    private fun str(s: String): Value = Value.StrV(s)
}
