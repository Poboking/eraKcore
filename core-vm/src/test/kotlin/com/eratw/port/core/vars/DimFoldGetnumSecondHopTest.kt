package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CMP-59 必红夹具：`GETNUM` 的**第二跳**（内置关键字表未命中 → 再查 `名@pos` 的 ERD 表）。
 *
 * 原版查找序（`ConstantData.cs:849-871` `TryKeywordToInteger`）：
 * 1. `:857-861` 先查内置表 `GetKeywordDictionary(code, index, null)`，命中即返回；
 * 2. `:866-870` **内置表未命中**（`TryGetValue` false）时仍继续，按 `varname` 查
 *    `erdNameToIntDics`（用户 ERD 表）——**没有提前 return**；
 * 3. 两跳都不中才返回 false，`GetnumMethod.GetIntValue`（`FunctionMethodCreator.cs:4814-4825`）
 *    把它折成 **-1**。
 *
 * 而 `varname` 的构造在 `:4819`：两参形态 = 变量名本身；三参形态 =
 * `变量名@<第三参>`。所以 `GETNUM(X, "k", 2)` 的第二跳键是 `X@2`。
 *
 * 修复前 `PureStaticDimFunctionFolder.getnum`（`UserVariables.kt`）在内置表存在但键未命中时
 * **直接返回 -1**（早退），第二跳永远走不到 ⇒ 本用例红。
 */
class DimFoldGetnumSecondHopTest {

    /** 合成表：TALENT 是内置表（只含一个键），TALENT@2 是 ERD 表（`名@pos` 形态）。 */
    private val tables = mapOf(
        "TALENT" to mapOf("人类" to 190),
        "TALENT@2" to mapOf("猫" to 7),
    )

    private val folder = DimFunctionFolders.pureStatic(keywordTables = tables)

    private fun getnum(name: String, key: String, pos: Long? = null): Long? =
        folder.foldInt(
            "GETNUM",
            if (pos == null) {
                listOf(DimFoldArg.IdentArg(name), DimFoldArg.StrArg(key))
            } else {
                listOf(DimFoldArg.IdentArg(name), DimFoldArg.StrArg(key), DimFoldArg.IntArg(pos))
            },
        )

    @Test
    fun `内置表未命中再查 name@pos 的 ERD 表`() {
        // 第一跳命中（对照）
        assertEquals(190L, getnum("TALENT", "人类"), "内置表命中")
        // 第二跳命中：内置表有 TALENT 但没有「猫」，ERD 表 TALENT@2 有
        assertEquals(
            7L,
            getnum("TALENT", "猫", 2),
            "内置表未命中必须落 NAME@pos 的 ERD 表（ConstantData.cs:866-870）",
        )
        // 两跳都不中 → -1（:4821-4825）；不许折成 null（那是「表缺席」的失败方向）
        assertEquals(-1L, getnum("TALENT", "无此关键字", 9), "两跳都未命中折 -1")
        // 两参形态只有内置表这一跳（:4819 varname = NAME，无 @ 后缀）
        assertEquals(-1L, getnum("TALENT", "猫"), "两参形态没有 ERD@pos 表可查 ⇒ -1")
    }

    @Test
    fun `表完全缺席仍保持不折叠的失败方向`() {
        // 用户变量在两张表里都没有：本层无法确认变量是否存在，返回 null
        // （旧口径「表确实不存在时返回 null」，不为 -1 背书）
        assertNull(getnum("MY_USER_VAR", "任意", 1), "无表 ⇒ 不折叠")
        assertNull(getnum("MY_USER_VAR", "任意"), "无表（两参）⇒ 不折叠")
    }
}
