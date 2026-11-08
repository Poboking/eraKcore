package com.eratw.port.core.proc

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AUD-b5-linestamp 的判据：脚本行身份位必须**恒非负**、按**串内容**区分、且同函数同 pc 稳定。
 *
 * 背景（挖虫波 W9 判活）：旧打包 `(key.hashCode().toLong() shl 32) xor pc` 把 JVM
 * `String.hashCode` 的符号位带进 token，负 hash 键整词为负，被消费端
 * `LineBufferConsoleSink` 的 `scriptLine >= 0L`「已接线」哨兵吞掉 ⇒ 那一半函数的
 * 身份位静默退化成行水位（原版判据是行对象引用恒等，`EmueraConsole.cs:789-797`）。
 *
 * 这些用例只钉 [LineIdentityIds] 的打包契约；接线面（`ProcessRuntime.scriptLineTokenNow`
 * 走同一实例、表挂 `store.aux`）由源码引用与既有等待链用例覆盖。
 */
class ScriptLineTokenIdentityTest {

    @Test
    fun `负 hash 的函数键 token 仍非负 不被哨兵吞掉`() {
        val ids = LineIdentityIds()
        // 语料实测负 hash 的三个键（BOARD AUD-b5-linestamp 原样读数）。
        for (key in listOf("TITLE_MAIN", "TESTLIFE", "H_EVAL")) {
            assertTrue(key.hashCode() < 0, "$key 的 hashCode 应为负（判据前提）：${key.hashCode()}")
            val token = ids.tokenOf(key, 42)
            assertTrue(token >= 0L, "$key 的 token 必须非负，实为 $token")
        }
    }

    @Test
    fun `同 hashCode 的不同函数键不折叠`() {
        // JVM 规范里 "Aa" 与 "BB" 的 hashCode 同为 2112 —— 旧写法会把它们折成同一身份位。
        assertEquals("Aa".hashCode(), "BB".hashCode(), "判据前提：两者 hashCode 相同")
        val ids = LineIdentityIds()
        val a = ids.tokenOf("Aa", 7)
        val b = ids.tokenOf("BB", 7)
        assertNotEquals(a, b, "身份位按串内容分配，同 hash 不同串不得折叠")
        assertEquals(2, ids.size, "两条键各占一个 id")
    }

    @Test
    fun `同函数同 pc 稳定 换 pc 或换函数即变`() {
        val ids = LineIdentityIds()
        val t1 = ids.tokenOf("TITLE_MAIN", 100)
        val t2 = ids.tokenOf("TITLE_MAIN", 100)
        assertEquals(t1, t2, "同一函数同一 pc 恒同值（消费端只做相等比较）")
        assertNotEquals(t1, ids.tokenOf("TITLE_MAIN", 101), "换语句（pc 变）即变")
        assertNotEquals(t1, ids.tokenOf("OTHER_FUNC", 100), "换函数即变")
        assertEquals(2, ids.size, "重复取键不新增 id")
    }

    @Test
    fun `token 布局是高 32 位函数 id 低 32 位 pc`() {
        val ids = LineIdentityIds()
        val first = ids.idOf("TITLE_MAIN")
        val second = ids.idOf("TESTLIFE")
        assertEquals(0, first, "首个键拿 0 号 id（单调、从 0 起）")
        assertEquals(1, second, "次个键拿 1 号 id")
        val pc = 0x7fff_ffff
        assertEquals((0L shl 32) or pc.toLong(), ids.tokenOf("TITLE_MAIN", pc))
        assertEquals((1L shl 32) or pc.toLong(), ids.tokenOf("TESTLIFE", pc))
    }

    @Test
    fun `id 分配与 hashCode 符号无关且单调`() {
        val ids = LineIdentityIds()
        val keys = listOf("TITLE_MAIN", "H_EVAL", "TESTLIFE", "Aa", "BB", "ZZ")
        val assigned = keys.map { ids.idOf(it) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), assigned, "按首次见到顺序单调发号")
        assertTrue(assigned.all { it >= 0 }, "全部 id 非负 ⇒ token 高 32 位不带符号位")
    }

    /** 成因反证：旧打包公式对负 hash 键确实产出负 token（否则本条就是空谈）。 */
    @Test
    fun `旧打包公式对负 hash 键确实产出负 token`() {
        val key = "TITLE_MAIN"
        val pc = 42
        val legacy = (key.hashCode().toLong() shl 32) xor (pc.toLong() and 0xffffffffL)
        assertTrue(legacy < 0L, "旧写法对 $key 产出负 token（实为 $legacy）⇒ 会被 >= 0 哨兵吞")
        assertTrue(LineIdentityIds().tokenOf(key, pc) >= 0L, "新写法同键同 pc 必须非负")
    }

    /** 源码面：生产端不许回退到 `String.hashCode` 打包（那是本条的成因）。 */
    @Test
    fun `生产端不再用 String hashCode 打包身份位`() {
        val p = java.nio.file.Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/core/proc/ProcessRuntime.kt")
        assertTrue(java.nio.file.Files.exists(p), "源码面必须真的读到 $p")
        val text = p.readText()
        val start = text.indexOf("private fun scriptLineTokenNow")
        assertTrue(start >= 0, "打包点 scriptLineTokenNow 必须存在")
        val next = text.indexOf("private fun ", start + 10)
        val body = text.substring(start, if (next > start) next else text.length)
        assertTrue(body.contains("LineIdentityIds"), "身份位必须走单调 id 表")
        assertTrue(
            !body.contains("hashCode()"),
            "不得回退到 String.hashCode 打包 —— 负 hash 整词为负，会被消费端 >= 0 哨兵吞掉",
        )
    }
}
