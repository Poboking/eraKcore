package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 全局（非私有）REF #DIM 的装载期口径。
 *
 * 原版可观测行为 = 装载失败：ERH 装载对任何 Reference 声明抛 NotImplCodeEE
 * （ErhLoader.cs:262-265，先于 :267 的 CreateUserDefVariable 调用），REF 与 GLOBAL
 * 关键字解析期互斥（UserDefinedVariableData.cs:99-102）；全局建表处 VariableData.cs:3246
 * 不查 Reference，其对 REF 的 Static 0 长形态（尺寸恒 0，UserDefinedVariableData.cs:309-311）
 * 是不可达死代码。NotImplCodeEE 文案 =「该功能不能在现版本使用」（NotImplCodeEE.cs:5-15，
 * lang.emuera-zhs.xml:824；specs/variables.yaml scoping.ref）。
 */
class GlobalRefDimTest {

    private fun p(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIM", body, isPrivate, DimParseConfig())

    // ------------------------------------------------------------------
    // 1) 非私有 REF 声明：装载期拒绝，文案逐字对齐 NotImplCodeEE
    // ------------------------------------------------------------------

    @Test
    fun `非私有 REF 声明报该功能不能在现版本使用`() {
        // ERH `#DIM REF X`（无 GLOBAL 关键字）：解析可过（isRef && !isPrivate），
        // 建表时按原版 ErhLoader.cs:262-265 拒绝。
        val spec = p("REF REF_G, 0")
        assertTrue(spec.isRef && !spec.isPrivate, "前置：解析层放行非私有 REF")
        val e = assertFailsWith<CodeEE> { VariableStore().declareUserVar(spec) }
        assertEquals("该功能不能在现版本使用", e.message, "实际=${e.message}")
    }

    @Test
    fun `GLOBAL 标志的 REF 合成声明同样拒绝`() {
        // 手工构造 isRef+isGlobal（解析层产不出该组合，见下一条）：命中同一守卫。
        val spec = DimSpec("REF_G2", false, intArrayOf(0), isRef = true, isGlobal = true)
        val e = assertFailsWith<CodeEE> { VariableStore().declareUserVar(spec) }
        assertEquals("该功能不能在现版本使用", e.message, "实际=${e.message}")
    }

    // ------------------------------------------------------------------
    // 2) REF 与 GLOBAL 关键字解析期互斥（UserDefinedVariableData.cs:99-102）
    // ------------------------------------------------------------------

    @Test
    fun `GLOBAL 与 REF 关键字解析期互斥`() {
        assertFailsWith<CodeEE> { p("GLOBAL REF X, 0") }
        assertFailsWith<CodeEE> { p("REF GLOBAL X, 0") }
    }

    // ------------------------------------------------------------------
    // 3) 私有/local REF 形态行为不变（原版 CreatePrivateVariable 走 ReferenceToken，
    //    VariableData.cs:3295-3309）
    // ------------------------------------------------------------------

    @Test
    fun `私有 REF 声明仍成功且按函数作用域隔离`() {
        val store = VariableStore()
        val spec = p("REF REF_P, 0", isPrivate = true)
        val v = store.declareUserVar(spec, "FOO")
        assertTrue(v.spec.isRef, "私有 REF 应建 RefUserVar 形态")
        // 已入私有作用域：同函数重复定义报错、换函数可再声明。
        assertFailsWith<CodeEE> { store.declareUserVar(p("REF REF_P, 0", isPrivate = true), "FOO") }
        store.declareUserVar(p("REF REF_P, 0", isPrivate = true), "BAR")
    }
}
