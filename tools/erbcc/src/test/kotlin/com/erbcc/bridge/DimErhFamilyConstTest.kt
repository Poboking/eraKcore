package com.erbcc.bridge

import com.eratw.port.erbcc.DimKeyword
import com.eratw.port.erbcc.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * `#DIM` / `#DEFINE` 常量折叠对拍（M6B-FRONT 审计的 erbcc 侧）。
 *
 * 口径链（全部在 [sizeConsts]）：
 * - `#DEFINE 名 体`：体是表达式，可引用其它宏/常量（原版词法层逐标识符展开，
 *   spec: LexicalAnalyzer.cs:285-307，循环上限 100 :18/:960-963）；
 * - `#DIM CONST 名 = 初值`：取首个顶层逗号段（裸名 = 元素 0，spec: VariableTerm.cs:337-340；
 *   CONST 恒 1 维，UserDefinedVariableData.cs:438-441），字符串 CONST 不进整型表；
 * - 多轮定点迭代 = ERH 装载尾部 analyzeSharpDimLines 的重试队列（spec: ErhLoader.cs:249-340）。
 *
 * 语料侧（`ERATW_CORPUS` 缺失则跳过）：DIM.ERH / DIM_als.ERH 家族的实际折叠读数。
 */
class DimErhFamilyConstTest {

    // ------------------------------------------------------------------
    // 合成：宏套宏、表达式、CONST 链、字符串 CONST、多元素 CONST
    // ------------------------------------------------------------------

    @Test
    fun `宏套宏与 CONST 链按定点迭代折叠`() {
        val erh = SymbolTable.collectErhSymbols(
            """
            #DEFINE MY_BASE 10
            #DEFINE MY_MID MY_BASE + 5
            #DEFINE MY_NEST MY_MID * 2
            #DEFINE EMPTY
            #DIM CONST DIRECT = 7
            #DIM CONST CHAIN = MY_NEST + DIRECT
            #DIMS CONST STR_C = "文字"
            #DIM CONST ARR = 3, 40, 500
            """.trimIndent(),
        )
        val consts = sizeConsts(erh.macros, erh.dimConsts)

        // 宏展开等价于原版逐标识符替换：MY_NEST = (10 + 5) * 2
        // 注：原名 BASE 是 Emuera 系统变量，`#DEFINE BASE` 在原版即 level 2 报错
        //（CheckUserMacroName，spec: IdentifierDictionary.cs:320-323）⇒ 改用 MY_ 前缀。
        assertEquals(10L, consts["MY_BASE"])
        assertEquals(15L, consts["MY_MID"])
        assertEquals(30L, consts["MY_NEST"])
        // CONST 初值可引用宏结果：CHAIN = 30 + 7
        assertEquals(37L, consts["CHAIN"])
        // 多元素 CONST 的裸名 = 元素 0（spec: VariableTerm.cs:337-340）
        assertEquals(3L, consts["ARR"])
        // 空体宏只供 [IF 名] 判定，不进数值表（spec: ErhLoader.cs:167-171）
        assertFalse(consts.containsKey("EMPTY"))
        // 字符串 CONST 不进整型常量表（#DIMS，spec: UserDefinedVariableData.cs:308）
        assertFalse(consts.containsKey("STR_C"))
    }

    @Test
    fun `互参照的宏与常量双双不纳入且不抛`() {
        // 原版 GetMacro 展开有 MacroOverLimit（LexicalAnalyzer.cs:960-963）；erbcc 侧按
        // 「整轮无进展就停」的定点口径丢弃（sizeConsts 出口），既不折叠也不报错
        val erh = SymbolTable.collectErhSymbols(
            """
            #DEFINE P Q
            #DEFINE Q P
            #DIM CONST R = R + 1
            """.trimIndent(),
        )
        val consts = sizeConsts(erh.macros, erh.dimConsts)
        assertTrue(consts.isEmpty(), "自参照链不得被折叠成任何值：$consts")
    }

    // ------------------------------------------------------------------
    // 真语料：DIM.ERH 家族的实际读数（ERATW_CORPUS 缺失则 skip）
    // ------------------------------------------------------------------

    @Test
    fun `DIM ERH 家族的常量折叠读数`() {
        val raw = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        assumeTrue(raw != null, "未设置 ERATW_CORPUS，跳过真语料 #DIM 折叠用例")
        val corpus = Path.of(raw!!)
        assumeTrue(Files.isDirectory(corpus), "语料目录不存在: $corpus")

        val table = SymbolTable.build(corpus)
        val consts = sizeConsts(table)
        fun c(name: String): Long? = consts[name.uppercase()]

        // DIM.ERH:2 / :7 / :10（`#DIM CONST 名 = 纯整数`）
        assertEquals(20260326L, c("NOW_VERSION"), "DIM.ERH:2 NOW_VERSION")
        assertEquals(8L, c("本体バージョン"), "DIM.ERH:7 本体バージョン")
        assertEquals(700L, c("REQUIRED_TRUST_恋慕"), "DIM.ERH:10 REQUIRED_TRUST_恋慕")
        // DIM_als.ERH:26（地图 ID 常量，CJK 名）
        assertEquals(310L, c("霧の湖"), "DIM_als.ERH:26 霧の湖")
        // DIM.ERH:618（`0x` 色值，DIM-HEX-FOLD 的真语料锚点）
        assertEquals(0xC070C0L, c("C_P_PURPLE"), "DIM.ERH:618 C_P_PURPLE")
        // DIM.ERH:3 是 `#DIMS CONST eraTW_Version = "4.980"` → 字符串，不进整型表
        assertFalse(consts.containsKey("ERATW_VERSION"), "字符串 CONST 不得进整型表")

        // 读数：ERH 侧 CONST 声明总数 / 折叠成功数（不可折叠者按「该行无效、变量不注册」降级，
        // spec: UserDefinedVariableData.cs:325-341 + ErhLoader.cs:249-340）
        val intConsts = table.erhDimConsts.filter { !it.isStr && DimKeyword.CONST in it.keywords }
        val resolved = intConsts.count { c(it.name) != null }
        println("[front-audit] ERH #DIM CONST（非字符串）${intConsts.size} 条，sizeConsts 折叠出 $resolved 条")
        assertNotNull(c("NOW_VERSION"))
        assertTrue(
            resolved.toLong() * 4 >= intConsts.size.toLong() * 3,
            "折叠率不应低于 75%：$resolved/${intConsts.size}",
        )
    }
}
