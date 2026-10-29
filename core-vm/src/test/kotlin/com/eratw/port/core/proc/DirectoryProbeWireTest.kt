package com.eratw.port.core.proc

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.DirectoryProbeFileOps
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R5：生产 FileOps 装饰器 [WriteBlockedFileOps]（[ConsoleBuiltinHost.fileOps] 的**唯一**形态）
 * 必须转发宿主可选能力位 [DirectoryProbeFileOps]。
 *
 * 旧测试用裸 [RootedFileOps]（自身实现了能力位）覆盖不到该缺陷：装饰器不实现接口时，
 * 消费点 `isDirectoryOf`（`fn/HostMethods.kt:184-186`）的 `ops as? DirectoryProbeFileOps`
 * 恒落空，退到「`list` 非空」兜底 —— **空目录**被误判为非目录：
 * `EXISTFILE("空目录")` 返回 1（应 0，FunctionMethodCreator.cs:1918 `File.Exists(目录)=False`）、
 * `ENUMFILES("空目录")` 返回 -1（应 0，:354-358 `Directory.Exists(目录)=True` 且枚举到 0 个）。
 * 本测试走真实 [ConsoleBuiltinHost] 装配，正反两面都钉死。
 */
class DirectoryProbeWireTest {

    @Test
    fun `空目录经生产装饰器仍判为目录 EXISTFILE=0 ENUMFILES=0`() {
        withTempRoot("eratw-dirprobe-empty") { root ->
            Files.createDirectory(root.resolve("emptydir"))
            val host = ConsoleBuiltinHost(root)

            // (1) 装饰器必须是能力位的实现者，否则 as? 恒落空
            assertTrue(host.fileOps is DirectoryProbeFileOps, "生产装饰器应实现 DirectoryProbeFileOps")
            assertTrue(
                (host.fileOps as DirectoryProbeFileOps).isDirectory("emptydir"),
                "空目录必须被判定为目录（Directory.Exists 口径）",
            )

            // RESULTS 是既有系统字符串数组（RESULTS_ARRAY），已注册，无需再声明。
            val store = VariableStore()
            val ctx = BuiltinEvalContext(TestEvalContext(store), store, host)

            // (2) EXISTFILE：目录 ⇒ 0（:1918 File.Exists(目录)=False）
            assertEquals(
                0L,
                (ctx.callFunction("EXISTFILE", listOf(Value.StrV("emptydir"))) as Value.IntV).v,
                "EXISTFILE(空目录) 应为 0",
            )

            // (3) ENUMFILES：空目录 ⇒ 0（:354-358 Directory.Exists(目录)=True，枚举到 0 个）
            assertEquals(
                0L,
                (ctx.callFunctionRef(
                    "ENUMFILES",
                    listOf(StrLiteral("emptydir"), StrLiteral("*"), IntLiteral(0), strRef("RESULTS")),
                ) as Value.IntV).v,
                "ENUMFILES(空目录) 应为 0",
            )
        }
    }

    @Test
    fun `普通文件与不存在路径口径不变（防过度修复）`() {
        withTempRoot("eratw-dirprobe-file") { root ->
            Files.write(root.resolve("plain.txt"), "x".toByteArray())
            val host = ConsoleBuiltinHost(root)
            val store = VariableStore()
            val ctx = BuiltinEvalContext(TestEvalContext(store), store, host)

            // 普通文件 ⇒ 1（File.Exists=True 且非目录）
            assertEquals(
                1L,
                (ctx.callFunction("EXISTFILE", listOf(Value.StrV("plain.txt"))) as Value.IntV).v,
            )
            // 不存在路径 ⇒ 0
            assertEquals(
                0L,
                (ctx.callFunction("EXISTFILE", listOf(Value.StrV("nope.txt"))) as Value.IntV).v,
            )
        }
    }

    private fun strRef(name: String, vararg idx: Long): VarRef =
        VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.STR)

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
}
