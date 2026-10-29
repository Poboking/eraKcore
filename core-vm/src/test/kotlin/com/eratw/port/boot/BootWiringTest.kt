package com.eratw.port.boot

import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BOOT-2 接线的最小可复现（**不开语料**，常规 `:core-vm:test` 就会跑）。
 *
 * 覆盖 `:core-vm:bootSmoke` 装置侧新接的两条能力，语料原文形态做夹具：
 * 1. [SystemVariableBootstrap.install] 把「带 `;` 行内注释 + 非十进制字面量」的
 *    `#DIM CONST` 真的装进变量池（RM_TITLE 一类失踪的根因，
 *    spec: LexicalAnalyzer.cs:919-929 剥注释 + :30-90 读字面量）；
 * 2. `install` 的不动点重试能吃下「尺寸引用后面才出现的 16 进制常量」
 *    （运行期路径；erbcc 的 `sizeConsts` 自 2026-10-23 起同样支持 `0x`，
 *    见 `DimSizeEvaluator`，spec: ErhLoader.cs:315-337）；
 * 3. [ConsoleBuiltinHost] 的语料只读口径：读/枚举按游戏根目录（ExeDir）解析，写一律拒绝并计数。
 *
 * 夹具取自语料实文（`リソース管理/ResourceManager.ERH:15/:23`、`DIM.ERH:571`、
 * `DIM.ERH:316` 的形状），不是随手编的字符串。
 */
class BootWiringTest {

    private fun decl(raw: String, file: String = "夹具.ERH", line: Int = 1) =
        SystemVariableBootstrap.RawDeclaration(raw, file, line)

    @Test
    fun `带行尾注释的十六进制与 p 后缀全局常量能装进变量池`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID", "リソース管理/ResourceManager.ERH", 15),
                decl("#DIM CONST C_GREEN = 0x00FF00", "DIM.ERH", 571),
                decl("#DIM CONST 状態_攻撃 = 1p5", "DIM.ERH", 316),
                decl("#DIM RM_GID_BITS, 100 ; 6400枚画像対応", "リソース管理/ResourceManager.ERH", 23),
            ),
        )
        assertTrue(r.failures.isEmpty(), "装载不应有失败：${r.failures.map { it.message }}")
        assertEquals(4, r.declared)
        // 运行期按名可读 —— 这正是旧装置挡在 ResourceManager.ERB:6 的那一步
        assertEquals(100000L, store.readInt("RM_TITLE", longArrayOf(0L)))
        assertEquals(65280L, store.readInt("C_GREEN", longArrayOf(0L)))     // 0x00FF00
        assertEquals(32L, store.readInt("状態_攻撃", longArrayOf(0L)))       // 1p5 = 2^5
        assertEquals(100, store.getLength("RM_GID_BITS"))
        assertTrue(store.isConst("RM_TITLE"))
        // 标量 CONST 同时进入尺寸求值常量表（名按大写）
        assertEquals(100000L, r.constants["RM_TITLE"])
        assertEquals(65280L, r.constants["C_GREEN"])
        assertEquals(32L, r.constants["状態_攻撃"])
    }

    @Test
    fun `尺寸引用后置的十六进制常量由不动点重试消化`() {
        val store = VariableStore()
        // 顺序是反的：尺寸引用了**后面**才声明的常量。运行期 install 靠第二轮重试
        //（spec: ErhLoader.cs:315-337 同构）；erbcc 的 sizeConsts 自 2026-10-23 起也支持
        // 0x（DimSizeEvaluator，LexicalAnalyzer.cs:36-45），本用例锁定的是运行期路径。
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM 配列_C, SIZE_C, 2", "夹具.ERH", 2),
                decl("#DIM CONST SIZE_C = 0x10", "夹具.ERH", 1),
            ),
            DimParseConfig(),
        )
        assertTrue(r.failures.isEmpty(), "装载不应有失败：${r.failures.map { it.message }}")
        assertEquals(2, r.declared)
        assertEquals(16, store.getLength("配列_C", 0))
        assertEquals(2, store.getLength("配列_C", 1))
    }

    @Test
    fun `宏体的 p 后缀尺寸同样可求值`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIM 位旗標配列, BITFLAG", "夹具.ERH", 1)),
            DimParseConfig(macros = mapOf("BITFLAG" to "1p4")),
        )
        assertTrue(r.failures.isEmpty(), "装载不应有失败：${r.failures.map { it.message }}")
        assertEquals(16, store.getLength("位旗標配列"))
    }

    @Test
    fun `宿主按游戏根解析读与枚举，写请求一律拒绝`() {
        val root: Path = Files.createTempDirectory("eratw-boot-host")
        try {
            Files.createDirectories(root.resolve("resources/sub"))
            Files.writeString(root.resolve("resources/sub/a.xml"), "<sprites/>")
            Files.writeString(root.resolve("resources/sub/b.txt"), "keep")
            val host = ConsoleBuiltinHost(root)
            val ops = host.fileOps
            assertNotNull(ops, "RootedBuiltinHost 必须给出 fileOps")
            // 读相对 ExeDir 解析（原版 Program.ExeDir 口径，core/proc/BootHosts.kt:19-33）
            assertEquals("<sprites/>", ops.readText("resources/sub/a.xml"))
            assertTrue(ops.exists("resources/sub/a.xml"))
            // 递归枚举 = ENUMFILES("resources", "*.xml", 1)（ResourceManager.ERB:46），
            // 返回相对根、`/` 分隔、按字典序
            assertEquals(listOf("resources/sub/a.xml"), ops.list("resources", "*.xml", true))
            // 写：拒绝 + 返回 false（原版 SAVETEXT 的失败值 0），且**不落盘**
            assertFalse(ops.writeText("resources/sub/LOG.txt", "x", false))
            assertFalse(Files.exists(root.resolve("resources/sub/LOG.txt")))
            assertFalse(ops.writeText("resources/sub/b.txt", "overwrite", false))
            assertEquals("keep", Files.readString(root.resolve("resources/sub/b.txt")))
            assertEquals(2, host.writes.blockedWrites)
            // 越界路径按「不存在」处理（BootHosts.kt:156-162 的 resolvePath）
            assertNull(ops.readText("../outside.txt"))
            assertFalse(ops.exists("../../etc/passwd"))
        } finally {
            runCatching { root.toFile().deleteRecursively() }
        }
    }
}
