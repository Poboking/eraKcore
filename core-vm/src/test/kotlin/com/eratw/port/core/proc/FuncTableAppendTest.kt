package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R7 钉子 A2（CONTRACT-R7 §2 波 A）：函数表能不能靠「合并 FuncMeta 列表」支持补丁。
 *
 * ## 这个钉子存在的理由
 *
 * R7 的补丁函数不是靠 base 的静态 `_CALL` 调到的，而是靠 `TRYCALLFORM` 的**运行期按名解析**：
 * `_CALL_BY_NAME` → [ProcessRuntime.resolveFunctionIndex] → [FunctionTable.classify]。
 * 所以「补丁能不能被调到」= 「[FunctionTable] 能不能看见补丁的元数据」。
 *
 * `GameSession.kt:709` 的装配点是 `FunctionTable(metadata.functions)` —— 一次性吃一张完整列表。
 * 本测试确认三件事：
 *
 * 1. 合并列表（`base + patch`）后，补丁函数**按 `FuncMeta.index` 可查**，且 `classify` 认得出；
 * 2. 只拿 base 列表构造的表，对补丁函数必然 `NOT_FOUND` ⇒ 拼接不是可选优化，是必需步骤；
 * 3. `byFunctionIndex` 走的是**稠密数组**（`FuncMeta.kt:360`），补丁下标必须严格大于 base 的
 *    最大下标且连续，否则中间会出现 null 槽（数组按 max index 开，中间洞不报错但查询返回 null）。
 *
 * ## 结论（写进波 C 契约用）
 *
 * 波 C 只需在 `GameSession.kt:709` 把 `metadata.functions` 换成 `base + patch`，不需要给
 * [FunctionTable] 加追加 API。第 3 条是**编译期必须保证**的约束：补丁的函数下标从
 * `base.functions.size` 开始连续分配。
 */
class FuncTableAppendTest {

    /** 造一条最简非事件函数元数据。`index` 即 `Program.functions` 下标。 */
    private fun meta(index: Int, name: String): FuncMeta =
        FuncMeta(index = index, key = name.uppercase(), name = name)

    @Test
    fun `合并列表后补丁函数按 index 可查且可分类`() {
        val base = listOf(meta(0, "COMMON"), meta(1, "SYSTEM"))
        val patch = listOf(meta(2, "M_KOJO_K1_TEST"))

        val table = FunctionTable(base + patch)

        assertEquals(FunctionTable.TargetKind.NON_EVENT, table.classify("M_KOJO_K1_TEST"))
        val hit = table.nonEvent("M_KOJO_K1_TEST")
        assertNotNull(hit, "合并列表后补丁函数必须能按名查到")
        assertEquals(2, hit.index)
        assertEquals(2, table.byFunctionIndex(2)?.index, "按下标取元数据必须命中同一条")
    }

    @Test
    fun `只拿 base 列表构造的表必然看不见补丁函数`() {
        val table = FunctionTable(listOf(meta(0, "COMMON"), meta(1, "SYSTEM")))

        assertNull(table.nonEvent("M_KOJO_K1_TEST"), "未拼接时查不到，符合预期")
        assertEquals(
            FunctionTable.TargetKind.NOT_FOUND,
            table.classify("M_KOJO_K1_TEST"),
            "未拼接时按名解析返回 NOT_FOUND ⇒ resolveFunctionIndex 返回 null ⇒ TRYCALLFORM 静默跳过",
        )
    }

    @Test
    fun `补丁下标必须连续且严格大于 base 最大下标`() {
        val base = listOf(meta(0, "A"), meta(1, "B"))
        val contiguous = FunctionTable(base + listOf(meta(2, "P0"), meta(3, "P1")))

        assertEquals(3, contiguous.byFunctionIndex(3)?.index, "连续下标：末位可查")
        assertNull(contiguous.byFunctionIndex(4), "越界返回 null（FuncMeta.kt:480 的边界）")

        // 留洞的写法：数组按 max index 开，洞不报错但查询返回 null —— 这正是编译期必须
        // 保证「补丁下标从 base.functions.size 起连续分配」的理由。
        val withHole = FunctionTable(base + listOf(meta(3, "P1")))
        assertNull(withHole.byFunctionIndex(2), "下标留洞时中间槽为 null，不报错但查不到")
        assertNotNull(withHole.byFunctionIndex(3), "洞后面的条目仍在")
    }
}
