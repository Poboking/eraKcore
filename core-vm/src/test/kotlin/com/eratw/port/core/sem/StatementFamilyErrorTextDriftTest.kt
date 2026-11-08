package com.eratw.port.core.sem

import com.eratw.port.core.lexer.LangKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语句族运行期文案的**同形漂移闸**（2026-11-05 lane errtext6）—— negrange 清单点名
 * 「官方同形字面量」的抛出点，本波判「不改-同形」：产品行一个字都不动，但字面必须与
 * `specs/error-texts.yaml` 第九节 + [LangKey] 的官方模板**逐字同形**。
 *
 * ## 为什么光有 `LangKeySourceTableTest` 不够
 * 那一闸钉的是「枚举 ↔ 两份 reference 真源」；产品行不进口径就没人管——同形一旦漂走
 * （有人「顺手」把 `函数DELDATA:` 改成 `DELDATA函数:`，或把 needle 里的半角引号改成全角），
 * 表与枚举仍绿、屏上却已经不是官方文。本文件把两件事钉死：
 *
 * 1. **装行级**（不是文件级 `contains`，2026-11-05 本仓已实测文件级是假闸）：
 *    每条 needle 必须命中至少一条**非注释、且带 `CodeEE` 的生产代码行**，命中即记下物理行号；
 *    注释里的引用不算数。
 * 2. **屏上逐字 = LangKey 展开**：每枚入表键用样例实参展开，逐字等于从官方表抄来的
 *    屏上形（样例实参与产品行插值同位）。两表行号回读归 `LangKeySourceTableTest`，这里不重复。
 *
 * 判「不改-两表不同文」「不改-无官方串」的枚（附三族）没有枚举可对照，退化为**形状钉**：
 * needle 原样在位 ⇒ 红绿任一变化都要重新走裁定，不许静默改文。
 */
class StatementFamilyErrorTextDriftTest {

    // ------------------------------------------------------------------
    // 数据面
    // ------------------------------------------------------------------

    /** 入表键的同形钉：needle = 产品行里的字面量原文（含 Kotlin 转义）；render = LangKey 展开样例。 */
    private data class Wired(val rel: String, val needle: String, val key: LangKey, val args: Array<out Any?>)

    /** 未入表（两表不同文 / ja 回退 / 无官方串）的注册形状钉。 */
    private data class RegisteredShape(val rel: String, val needle: String, val note: String)

    private val wired: List<Wired> = listOf(
        // ---- sem/StatementExecutor.kt：清单点名族（对象树支 + 扁平支同 needle）----
        Wired(F_SEM, "命令PICKUPCHARA的第\${i + 1}个参数的值(\${nos[i]})超过角色列表的范围", LangKey.OoRPickupcharaArg, arrayOf(3, 40L)),
        Wired(F_SEM, "试图引用一个尚未定义的角色", LangKey.RefUndefinedChara, emptyArray()),
        Wired(F_SEM, "命令FORCEKANA的参数超过可指定范围(0～3)", LangKey.OoRForcekanaArg, emptyArray()),
        Wired(F_SEM, "ASSERT的参数为0", LangKey.AssertArgIs0, emptyArray()),
        Wired(F_SEM, "AWAIT命令:指定了一个负值(\$v)", LangKey.AwaitArgIsNegative, arrayOf(-1)),
        Wired(F_SEM, "AWAIT命令:指定了一个超过10秒的值(\${v}ms)", LangKey.AwaitArgIsOver10Seconds, arrayOf(20000)),
        Wired(F_SEM, "对SAVEDATA的参数使用负值(\$no)", LangKey.SavedataArgIsNegative, arrayOf(-3)),
        Wired(F_SEM, "SAVEDATA的参数值过大(\$no)", LangKey.TooLargeSavedataArg, arrayOf(2147483648L)),
        Wired(F_SEM, "SAVEDATA对的提示文本使用了换行符号（由于会导致存档损坏，不能使用换行符）", LangKey.SavetextContainNewLineCharacter, emptyArray()),
        Wired(F_SEM, "LOADDATA的参数被指定为负数(\$no)", LangKey.LoaddataArgIsNegative, arrayOf(-2)),
        Wired(F_SEM, "LOADDATA的参数值(\$no)过大", LangKey.TooLargeLoaddataArg, arrayOf(9L)),
        Wired(F_SEM, "SETCOLOR的参数指定了小于0的值", LangKey.SetcolorArgLessThan0, emptyArray()),
        Wired(F_SEM, "SETCOLOR的参数指定了超过0的值", LangKey.SetcolorArgOver255, emptyArray()),
        Wired(F_SEM, "第2参数(\$bit)超出范围(0-63)", LangKey.ArgIsOoRBit, arrayOf(2, 70)),
        Wired(F_SEM, "CVARSET命令的第4参数(\$start)超出角色范围", LangKey.OoRCvarsetArg, arrayOf(4, 7)),
        Wired(F_SEM, "CVARSET命令的第5参数(\$start)超出角色范围", LangKey.OoRCvarsetArg, arrayOf(5, 7)),
        Wired(F_SEM, "角色数组变量\\\"\$name\\\"的第2参数(\$elem)超出数组范围", LangKey.OoRCharaVar, arrayOf("EXP", 2, 5L)),
        Wired(F_SEM, "ARRAYSHIFT仅对1位数组以及数组型角色变量有效", LangKey.IsUsableOnly1DVar, arrayOf("ARRAYSHIFT")),
        Wired(F_SEM, "ARRAYREMOVE仅对1位数组以及数组型角色变量有效", LangKey.IsUsableOnly1DVar, arrayOf("ARRAYREMOVE")),
        Wired(F_SEM, "ARRAYSORT仅对1位数组以及数组型角色变量有效", LangKey.IsUsableOnly1DVar, arrayOf("ARRAYSORT")),
        Wired(F_SEM, "命令ARRAYSHIFT的第四个参数(\$start)超出了数组\\\"\$name\\\"的范围", LangKey.OoRArrayShift, arrayOf(4, "ARR")),
        Wired(F_SEM, "命令ARRAYREMOVE的第二个参数(\$start)超出了数组\\\"\$name\\\"的范围", LangKey.OoRArrayRemove, arrayOf(4, "ARR")),
        Wired(F_SEM, "命令ARRAYSORT的第三参数(\$start)超出了数组\\\"\$name\\\"的范围", LangKey.OoRArraySort, arrayOf(4, "ARR")),
        Wired(F_SEM, "ARRAYCOPY命令的第1参数\\\"\$n1\\\"不是一个数组变量", LangKey.ArraycopyArgIsNotArray, arrayOf(1, "A1")),
        Wired(F_SEM, "ARRAYCOPY命令的第2参数\\\"\$n2\\\"不是一个数组变量", LangKey.ArraycopyArgIsNotArray, arrayOf(2, "A2")),
        Wired(F_SEM, "ARRAYCOPY命令的第1参数\\\"\$n1\\\"是一个角色变量(不支持)", LangKey.ArraycopyArgIsCharaVar, arrayOf(1, "C1")),
        Wired(F_SEM, "ARRAYCOPY命令的第2参数\\\"\$n2\\\"是一个角色变量(不支持)", LangKey.ArraycopyArgIsCharaVar, arrayOf(2, "C2")),
        Wired(F_SEM, "ARRAYCOPY命令的两个参数维度不一致", LangKey.DifferentArraycopyArgsDim, emptyArray()),
        Wired(F_SEM, "ARRAYCOPY命令的两个参数的数组变量类型不一致", LangKey.DifferentArraycopyArgsType, emptyArray()),
        Wired(F_SEM, "ENCODETOUNI的参数过长（目前共\${text.length}字符。最大\${cap - 1}字符）", LangKey.tooLongEncodetouniArg, arrayOf(120, 99)),
        Wired(F_SEM, "不能使用\${scope}命令", LangKey.CanNotUseInstruction, arrayOf("PRINTL")),
        Wired(F_SEM, "BAR的最大值不为正值", LangKey.MaxBarNotPositive, emptyArray()),
        Wired(F_SEM, "BAR的长度不为正值", LangKey.BarNotPositive, emptyArray()),
        Wired(F_SEM, "BAR过长", LangKey.TooLongBar, emptyArray()),
        Wired(F_SEM, "指定的颜色名\\\"\$name\\\"无效", LangKey.InvalidColorName, arrayOf("dimgrae")),
        // ---- fn/VarMethods.kt：清单点名「官方同形字面量」三支 + arrayRangeOoR ----
        Wired(F_VAR, "\${fn}命令的第\${argNo}个参数(\$value)超出数组\\\"\$varName\\\"的范围", LangKey.OoRInstructionArg, arrayOf("SUMARRAY", 2, 4000L, "FLAG")),
        Wired(F_VAR, "\${exprStr}不是变量", LangKey.IsNotVar, arrayOf("FLAG:1+2")),
        Wired(F_VAR, "\${exprStr}不是字符串型", LangKey.IsNotStr, arrayOf("STR:0")),
        Wired(F_VAR, "\${exprStr}不是整型", LangKey.IsNotInt, arrayOf("FLAG:3")),
        // ---- data/save/CharaFileIO.kt：SAVECHARA 两枚 ----
        Wired(F_CHARA, "SAVECHARA第\${argnum}参数的值超出角色登记编号的范围", LangKey.OoRSavecharaArg, arrayOf(3)),
        Wired(F_CHARA, "同一个角色登记编号(\$no)被多次指定", LangKey.DuplicateCharaNo, arrayOf(5)),
        // ---- fn/SaveMethods.kt：CHKDATA 的 ArgIsTooLarge 支是两表不同文（附三 2），
        //      这里只钉同形 needle（reference 形逐字），负值支见 registeredShapes ----
        // ---- vars/UserVariables.kt：本波接线枚（产品行必须走 LangKey，不再存字面量）----
        Wired(F_USERVAR, "LangKey.TransparentUnsupported.renderZhs()", LangKey.TransparentUnsupported, emptyArray()),
        // ---- vars/VarDef.kt：本波接线枚 ----
        Wired(F_VARDEF, "throw getSizeNonExistDim(name)", LangKey.GetSizeNonExistDim, arrayOf("G0")),
    )

    private val registeredShapes: List<RegisteredShape> = listOf(
        // 附三之一：两表不同文键的现行形状（reference 形逐字）。needle 漂走 = 该屏上换了口径，必须重新裁。
        RegisteredShape(F_SAVE, "CHKDATA函数: 第1个参数被是负值(\$no)", "ArgIsNegative reference 形（ref :913 / 根 :31 不同文）"),
        RegisteredShape(F_SAVE, "CHKDATA函数: 第1个参数(\$no)过大", "ArgIsTooLarge reference 形（ref :915 / 根 :61 不同文）"),
        RegisteredShape(F_CHARA, "SAVECHARA函数: 第\${argnum}个参数被是负值(\$no)", "ArgIsNegative reference 形"),
        RegisteredShape(F_CHARA, "SAVECHARA函数: 第\${argnum}个参数(\$no)过大", "ArgIsTooLarge reference 形"),
        RegisteredShape(F_SEM, "函数DELDATA: 第1个参数被是负值(\$no)", "ArgIsNegative **根表形** ⇒ 与权威表屏上偏差（附三之一-1，清单点名属实）"),
        RegisteredShape(F_SEM, "函数DELDATA: 第1个参数(\$no)过大", "ArgIsTooLarge 根表形 ⇒ 屏上偏差（附三之一-2）"),
        RegisteredShape(F_SEM, "ARRAYSHIFT函数: 第4个参数被是负值(\$start)", "ArgIsNegative reference 形"),
        RegisteredShape(F_SEM, "ARRAYSHIFT函数: 第5个参数被是负值(\$e)", "ArgIsNegative reference 形"),
        RegisteredShape(F_SEM, "ARRAYREMOVE函数: 第2个参数被是负值(\$start)", "ArgIsNegative reference 形"),
        RegisteredShape(F_SEM, "ARRAYSORT函数: 第3个参数被是负值(\$start)", "ArgIsNegative reference 形"),
        RegisteredShape(F_SEM, "ARRAYSORT函数: 第4个参数被是负值(\$start)", "ArgIsNegative reference 形"),
        RegisteredShape(F_VAR, "\${fn}函数: 第3个参数(\$start)超出角色位置范围", "CharacterIndexOutOfRange reference 形（ref :911 / 根 :209 不同文）"),
        RegisteredShape(F_VAR, "\${fn}函数: 第4个参数(\$end)超出角色位置范围", "CharacterIndexOutOfRange reference 形"),
        RegisteredShape(F_VAR, "\${fn}函数: 第1个参数变量不是角色变量", "ArgIsNotCharacterVar reference 形（ref :888 / 根 :37 不同文）"),
        RegisteredShape(F_SEM, "ARRAYCOPY函数: 第1个参数(\\\"\$n1\\\")不是变量名", "NotVariableName reference 形（ref :912 / 根 :547 不同文）"),
        RegisteredShape(F_SEM, "ARRAYCOPY函数: 第2个参数(\\\"\$n2\\\")不是变量名", "NotVariableName reference 形"),
        RegisteredShape(F_VAR, "第 1 个参数(\\\"\$name\\\")不是变量名", "GETNUMB 支经 fn/PureMethods.kt:72 的 codeEE 包装 ⇒ 与两表都不同形（附三之一-5，屏上偏差在册）"),
        RegisteredShape(F_USERVAR, "函数 POWER 结果为 NaN（原版 FunctionMethodCreator.cs:4177-4179）", "ResultIsNaN 两表不同文 + 行号漏上屏（附三之一-6，待组长裁）"),
        RegisteredShape(F_USERVAR, "函数 POWER 结果为无穷大（原版 FunctionMethodCreator.cs:4181-4183）", "ResultIsInfinity 两表不同文（附三之一-6）"),
        RegisteredShape(F_USERVAR, "函数 POWER 结果 \$d 超出 Int64 范围（原版 FunctionMethodCreator.cs:4185-4187）", "ResultIsOutOfTheRangeOfInt64 两表不同文（ref :924 / 根 :623）⇒ 清单「应接」被实测推翻（附三之一-6）"),
        RegisteredShape(F_USERVAR, "函数 UNICODE 第 1 个参数 \$code 越界（0-65535）（原版 FunctionMethodCreator.cs:5703-5705）", "ArgIsOutOfRange 两表不同文（ref :917 / 根 :57）"),
        RegisteredShape(F_SEM, "幂运算结果不是数值", "ResultIsNaN 自造形（附三之一-6 同判）"),
        RegisteredShape(F_SEM, "幂运算结果无限大", "ResultIsInfinity 自造形"),
        RegisteredShape(F_SEM, "幂运算结果(\$r)超过64位带符号整数的有效范围", "ResultIsOutOfTheRangeOfInt64 自造形"),
        // 附三之二：reference 表缺键 ⇒ 屏上回退 ja 原文（Lang.cs 逐字展开形）
        RegisteredShape(F_SEM, "命令ARRAYSORTの第３引数(\$start)と第4変数(\$count)の和が配列\\\"\$name\\\"の範囲を超えています", "OoRSumArray：reference 表 0 命中（仅根表 :582 有译文）⇒ ja 回退形在册（附三之二-1）"),
        // 附三之三：两表同文但占位符形进不了表
        RegisteredShape(F_SEM, "CVARSET命令使用了一个非角色变量", "CvarsetArgIsNotCharaVar（ref :523 / 根 :219 同文；ja 带 {0}、zhs 不带 ⇒ 形闸不收）"),
        // 附三之四：无官方串（移植内部概念 / fork 专有）
        RegisteredShape(F_VM, "函数下标越界(\$entryFunctionIndex)", "移植 IR 入口下标概念 ⇒ 无官方串（附三之四-1）"),
        RegisteredShape(F_VM, "函数下标越界(\$funcIndex)", "同上（_CALL 装载/执行两支）"),
        RegisteredShape(F_PROC, "函数下标越界(\$targetIndex)", "同上"),
        RegisteredShape(F_SQL, "列下标 \$idx 越界（本行共 ", "SQL_* 为 fork 专有：reference 全库 SQL_|sqlite 0 命中（附三之四-3）"),
        RegisteredShape(F_SEM, "ARRAYSHIFT函数: 第2个参数溢出（\$shift）", "官方同位无闸（原生 OverflowException）；判停已钉 verify-notes §13（附三之四-4）"),
        RegisteredShape(F_SEM, "未定义的变量 \$name", "运行期折码位，官方解析期定型（附三之四-5；本 needle 覆盖多处同形生产行）"),
        RegisteredShape(F_VAR, "函数 \$fn 的第 1 个参数必须是字符串", "GETVAR 族无官方串（附三之四-6；官方 GetVarMethod 直抛 IsNotVar/IsNotInt/IsNotStr）"),
        RegisteredShape(F_VAR, "函数 \$fn：无法解释为表达式", "同上"),
    )

    // ------------------------------------------------------------------
    // 一、装行级同形钉
    // ------------------------------------------------------------------

    @Test
    fun `入表键的产品行逐字在位且行上是 CodeEE 抛点`() {
        val misses = ArrayList<String>()
        for (w in wired) {
            val hits = codeLines(w.rel).filter { it.text.contains(w.needle) }
            if (hits.isEmpty()) {
                misses += "${w.rel} 找不到生产代码行字面量「${w.needle}」（LangKey.${w.key.name} 同形漂走）"
            }
            // 接线枚（UserVariables/VarDef）的 needle 是调用式而非字符串字面量，允许不带引号形态；
            // 其余 needle 必须出现在 CodeEE 构造行上。
            if (w.rel != F_USERVAR && w.rel != F_VARDEF) {
                val notThrow = hits.filterNot { it.text.contains("CodeEE") }
                assertTrue(notThrow.isEmpty(), "${w.rel} 的 needle 命中了非 CodeEE 行：${notThrow.map { it.no to it.text.trim() } }")
            }
        }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

    @Test
    fun `未入表注册形状的生产行逐字在位`() {
        val misses = ArrayList<String>()
        for (r in registeredShapes) {
            if (codeLines(r.rel).none { it.text.contains(r.needle) }) {
                misses += "${r.rel} 找不到形状钉「${r.needle}」（${r.note}）"
            }
        }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

    // ------------------------------------------------------------------
    // 二、屏上逐字 = LangKey 展开（样例实参与产品插值同位）
    // ------------------------------------------------------------------

    @Test
    fun `屏上样例展开逐字钉`() {
        assertEquals("命令PICKUPCHARA的第3个参数的值(40)超过角色列表的范围", LangKey.OoRPickupcharaArg.renderZhs(3, 40L))
        assertEquals("试图引用一个尚未定义的角色", LangKey.RefUndefinedChara.renderZhs())
        assertEquals("命令FORCEKANA的参数超过可指定范围(0～3)", LangKey.OoRForcekanaArg.renderZhs())
        assertEquals("ASSERT的参数为0", LangKey.AssertArgIs0.renderZhs())
        assertEquals("AWAIT命令:指定了一个负值(-1)", LangKey.AwaitArgIsNegative.renderZhs(-1))
        assertEquals("AWAIT命令:指定了一个超过10秒的值(20000ms)", LangKey.AwaitArgIsOver10Seconds.renderZhs(20000))
        assertEquals("对SAVEDATA的参数使用负值(-3)", LangKey.SavedataArgIsNegative.renderZhs(-3))
        assertEquals("SAVEDATA的参数值过大(2147483648)", LangKey.TooLargeSavedataArg.renderZhs(2147483648L))
        assertEquals(
            "SAVEDATA对的提示文本使用了换行符号（由于会导致存档损坏，不能使用换行符）",
            LangKey.SavetextContainNewLineCharacter.renderZhs(),
        )
        assertEquals("LOADDATA的参数被指定为负数(-2)", LangKey.LoaddataArgIsNegative.renderZhs(-2))
        assertEquals("LOADDATA的参数值(9)过大", LangKey.TooLargeLoaddataArg.renderZhs(9L))
        assertEquals("SETCOLOR的参数指定了小于0的值", LangKey.SetcolorArgLessThan0.renderZhs())
        assertEquals("SETCOLOR的参数指定了超过0的值", LangKey.SetcolorArgOver255.renderZhs())
        assertEquals("第2参数(70)超出范围(0-63)", LangKey.ArgIsOoRBit.renderZhs(2, 70))
        assertEquals("CVARSET命令的第4参数(7)超出角色范围", LangKey.OoRCvarsetArg.renderZhs(4, 7))
        assertEquals("CVARSET命令的第5参数(7)超出角色范围", LangKey.OoRCvarsetArg.renderZhs(5, 7))
        assertEquals("角色数组变量\"EXP\"的第2参数(5)超出数组范围", LangKey.OoRCharaVar.renderZhs("EXP", 2, 5L))
        assertEquals("ARRAYSHIFT仅对1位数组以及数组型角色变量有效", LangKey.IsUsableOnly1DVar.renderZhs("ARRAYSHIFT"))
        assertEquals("命令ARRAYSHIFT的第四个参数(4)超出了数组\"ARR\"的范围", LangKey.OoRArrayShift.renderZhs(4, "ARR"))
        assertEquals("命令ARRAYREMOVE的第二个参数(4)超出了数组\"ARR\"的范围", LangKey.OoRArrayRemove.renderZhs(4, "ARR"))
        assertEquals("命令ARRAYSORT的第三参数(4)超出了数组\"ARR\"的范围", LangKey.OoRArraySort.renderZhs(4, "ARR"))
        assertEquals("ARRAYCOPY命令的第1参数\"A1\"不是一个数组变量", LangKey.ArraycopyArgIsNotArray.renderZhs(1, "A1"))
        assertEquals("ARRAYCOPY命令的第1参数\"C1\"是一个角色变量(不支持)", LangKey.ArraycopyArgIsCharaVar.renderZhs(1, "C1"))
        assertEquals("ARRAYCOPY命令的两个参数维度不一致", LangKey.DifferentArraycopyArgsDim.renderZhs())
        assertEquals("ARRAYCOPY命令的两个参数的数组变量类型不一致", LangKey.DifferentArraycopyArgsType.renderZhs())
        assertEquals("ENCODETOUNI的参数过长（目前共120字符。最大99字符）", LangKey.tooLongEncodetouniArg.renderZhs(120, 99))
        assertEquals("不能使用PRINTL命令", LangKey.CanNotUseInstruction.renderZhs("PRINTL"))
        assertEquals("BAR的最大值不为正值", LangKey.MaxBarNotPositive.renderZhs())
        assertEquals("BAR的长度不为正值", LangKey.BarNotPositive.renderZhs())
        assertEquals("BAR过长", LangKey.TooLongBar.renderZhs())
        assertEquals("指定的颜色名\"dimgrae\"无效", LangKey.InvalidColorName.renderZhs("dimgrae"))
        assertEquals("SUMARRAY命令的第2个参数(4000)超出数组\"FLAG\"的范围", LangKey.OoRInstructionArg.renderZhs("SUMARRAY", 2, 4000L, "FLAG"))
        assertEquals("FLAG:1+2不是变量", LangKey.IsNotVar.renderZhs("FLAG:1+2"))
        assertEquals("STR:0不是字符串型", LangKey.IsNotStr.renderZhs("STR:0"))
        assertEquals("FLAG:3不是整型", LangKey.IsNotInt.renderZhs("FLAG:3"))
        assertEquals("SAVECHARA第3参数的值超出角色登记编号的范围", LangKey.OoRSavecharaArg.renderZhs(3))
        assertEquals("同一个角色登记编号(5)被多次指定", LangKey.DuplicateCharaNo.renderZhs(5))
        // 本波接线枚的屏上新形：
        assertEquals("不能将透明(Transparent)指定为颜色", LangKey.TransparentUnsupported.renderZhs())
        assertEquals("试图获取一个不存在该维度的数组变量\"G0\"的长度", LangKey.GetSizeNonExistDim.renderZhs("G0"))
    }

    @Test
    fun `透明色枚与 HtmlStrict 常量和引擎屏蔽文案逐字同形`() {
        // fn/HtmlStrict.kt 的 ERR_TRANSPARENT 常量（非本路文件面）必须继续与官方串同字。
        val html = codeLines(F_HTML).filter { it.text.contains("不能将透明(Transparent)指定为颜色") }
        assertTrue(html.isNotEmpty(), "fn/HtmlStrict.kt 的 ERR_TRANSPARENT 漂离官方串")
        // StatementExecutor 运行期支也是同串。
        val sem = codeLines(F_SEM).filter { it.text.contains("不能将透明(Transparent)指定为颜色") }
        assertTrue(sem.isNotEmpty(), "StatementExecutor 的 transparent 支漂离官方串")
    }

    // ------------------------------------------------------------------
    // 装置：源码行扫描（与 RuntimeErrorTextWiringTest 同一套相对路径候选，
    // 注释行一律不计——文件级假闸的教训）
    // ------------------------------------------------------------------

    private data class Line(val no: Int, val text: String)

    private fun coreRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/core"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 core-vm main 源码根（工作目录 ${File(".").absolutePath}）")
    }

    private val cache = HashMap<String, List<Line>>()

    private fun codeLines(rel: String): List<Line> = cache.getOrPut(rel) {
        val f = File(coreRoot(), rel)
        assertTrue(f.isFile, "缺文件 $f")
        f.readText().lines().mapIndexedNotNull { i, line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Line(i + 1, line)
        }
    }

    private companion object {
        const val F_SEM = "sem/StatementExecutor.kt"
        const val F_VAR = "fn/VarMethods.kt"
        const val F_USERVAR = "vars/UserVariables.kt"
        const val F_VARDEF = "vars/VarDef.kt"
        const val F_SAVE = "fn/SaveMethods.kt"
        const val F_CHARA = "data/save/CharaFileIO.kt"
        const val F_VM = "code/Vm.kt"
        const val F_PROC = "proc/ProcessRuntime.kt"
        const val F_SQL = "sql/SqlMethods.kt"
        const val F_HTML = "fn/HtmlStrict.kt"
    }
}
