package com.eratw.port.core.proc

import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 全局 `#DIM` 原文 → 变量池：行内注释剥离、常量注册、两轮不动点、失败不中断。
 *
 * 直接锁死 BOOT-2 定位到的首个阻塞点成因：
 * `#DIM CONST RM_TITLE = 100000 ; …` 的 `;` 注释若未剥离，`DimParser` 会以
 * 「声明末尾有多余内容」失败（`UserVariables.kt:298`），`RM_TITLE` 不进变量池，
 * 于是 `GCREATED(RM_TITLE)` 报「未定义的变量 RM_TITLE」。
 */
class SystemVariableBootstrapTest {

    private fun decl(raw: String, file: String = "T.ERH", line: Int = 1) =
        SystemVariableBootstrap.RawDeclaration(raw, file, line)

    @Test
    fun `行尾注释被剥离`() {
        val raw = "#DIMS CONST S = \"a;b\" ; 注释"
        assertEquals("#DIMS CONST S = \"a;b\"", SystemVariableBootstrap.stripComment(raw).trimEnd())
    }

    @Test
    fun `RM_TITLE 带行尾注释仍注册为常量`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID", "リソース管理/ResourceManager.ERH", 15)),
        )

        assertEquals(1, result.declared)
        assertTrue(result.failures.isEmpty(), "不应有失败：${result.failures}")
        assertEquals(100000L, store.readInt("RM_TITLE", longArrayOf(0L)))
        assertTrue(store.isConst("RM_TITLE"))
        assertEquals(100000L, result.constants["RM_TITLE"])
    }

    @Test
    fun `普通全局变量带注释仍注册且长度正确`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIM RM_GID_BITS, 100 ; 6400枚画像対応", "リソース管理/ResourceManager.ERH", 24)),
        )

        assertEquals(1, result.declared)
        assertEquals(100, store.getLength("RM_GID_BITS", -1))
    }

    @Test
    fun `字符串常量带注释仍注册`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS CONST RM_XML_TEMP = \"xml:リソースデータベース_TEMP\" ; 処理用")),
        )

        assertEquals(1, result.declared)
        assertEquals("xml:リソースデータベース_TEMP", store.readStr("RM_XML_TEMP", longArrayOf(0L)))
    }

    @Test
    fun `续行块内逐物理行剥注释`() {
        val store = VariableStore()
        val raw = "#DIM CONST BIT3 = 0,\n    1, ; one\n    2"
        val result = SystemVariableBootstrap.install(store, listOf(decl(raw)))

        assertEquals(1, result.declared, "失败：${result.failures}")
        assertEquals(0L, store.readInt("BIT3", longArrayOf(0L)))
        assertEquals(1L, store.readInt("BIT3", longArrayOf(1L)))
        assertEquals(2L, store.readInt("BIT3", longArrayOf(2L)))
    }

    @Test
    fun `引用后置常量的尺寸在第二轮解析`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM BOOT_X, BOOT_LIMIT", "A.ERB", 1),
                decl("#DIM CONST BOOT_LIMIT = 3", "Z.ERH", 2),
            ),
        )

        assertEquals(2, result.declared, "失败：${result.failures}")
        assertEquals(3, store.getLength("BOOT_X", -1))
        assertEquals(3L, result.constants["BOOT_LIMIT"])
    }

    @Test
    fun `重名声明记失败但不中断后续`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM CONST BOOT_A = 1"),
                decl("#DIM CONST BOOT_A = 2"),
                decl("#DIM CONST BOOT_B = 3"),
            ),
        )

        assertEquals(2, result.declared)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.first().message.contains("重复定义"))
        assertEquals(3L, store.readInt("BOOT_B", longArrayOf(0L)))
    }

    @Test
    fun `BOM 前缀与空行不致命`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("\uFEFF#DIM CONST BOMVAL = 7 ; x"),
                decl("   "),
            ),
        )

        assertEquals(1, result.declared)
        assertEquals(1, result.failures.size) // 空行不是 #DIM 声明
        assertEquals(7L, store.readInt("BOMVAL", longArrayOf(0L)))
    }

    @Test
    fun `常量表合并入参里的既有常量`() {
        val store = VariableStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIM BOOT_Y, BOOT_PRESET")),
            com.eratw.port.core.vars.DimParseConfig(intConstants = mapOf("BOOT_PRESET" to 5L)),
        )

        assertEquals(1, result.declared)
        assertEquals(5, store.getLength("BOOT_Y", -1))
        assertNotNull(result.constants["BOOT_PRESET"])
    }
}
