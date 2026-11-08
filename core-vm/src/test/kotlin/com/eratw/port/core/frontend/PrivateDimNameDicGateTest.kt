package com.eratw.port.core.frontend

import com.eratw.port.core.stmt.BuiltinSymbols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CORE-06 收口（W7-judge）：私有 `#DIM` 的 nameDic 查表半（
 * spec: IdentifierDictionary.cs:360-390）在
 * [com.eratw.port.core.frontend.Symbols.checkUserPrivateVarName] /
 * [com.eratw.port.core.frontend.LabelTable.scanLabels] 的接线回归。
 *
 * 判据逐条（报错级别 warnLevel 2 → `UserDefinedVariableData.cs:277-292` CodeEE →
 * 该行无效、变量不注册，口径同已入册的 VarNameAlreadyUsed 族，specs/error-texts.yaml 第五节）：
 *  - 撞保留字 → zhs:873（spec: IdentifierDictionary.cs:364-367）；
 *  - 撞系统方法/系统指令 → zhs:874（:368-372）；
 *  - 撞系统变量 → zhs:875（:373-376）；
 *  - 撞已定义宏 → zhs:876（:377-380；nameDic 的 UserMacro 类由 ERH 装载先于 ERB 登记，
 *    spec: ErhLoader.cs:155-164，本侧由 SymbolConfig.macros 传入）；
 *  - **不撞**的名字照常收集——特别是 `#DIM line`：原版 varTokenDic 并无 LINE
 *    （对应物 __FILE__ 系列，spec: VariableData.cs:3168-3179），移植侧曾误登记
 *    LINE/FILE/FUNCTION（已剔除，BuiltinSymbols.kt:21-27 覆盖口径 KDoc），直接接查表
 *    会把语料 15 处 `#DIM [DYNAMIC] LINE`（SEX_TATTOO.ERB:52 等）全部误拒。
 */
class PrivateDimNameDicGateTest {

    private fun scan(
        text: String,
        config: SymbolConfig = SymbolConfig(),
    ): Pair<List<RawLabel>, MutableList<LoadWarning>> {
        val warnings = mutableListOf<LoadWarning>()
        val labels = LabelTable.scanLabels(text, config, "GATE.ERB", warnings)
        return labels to warnings
    }

    @Test
    fun `LINE 不在内建变量表里（防回归：剔除项不得回流）`() {
        // spec: VariableData.cs:2962-3200 的 varTokenDic 全集无 "LINE"；
        // localvarTokenDic 只有 LOCAL/ARG/LOCALS/ARGS（:3181-3187）。
        assertNull(BuiltinSymbols.variable("LINE"), "LINE 曾被误登记，回流即误拒语料")
        assertNull(BuiltinSymbols.variable("FILE"))
        assertNull(BuiltinSymbols.variable("FUNCTION"))
        assertNull(Symbols.checkUserPrivateVarName("line"))
        assertNull(Symbols.checkUserPrivateVarName("FUNCTION"))
    }

    @Test
    fun `语料形状的 DIM LINE 与 DIMS LINE 逗号尺寸照常收集`() {
        // 实形对照：DLC/SEX_TATTOO/SEX_TATTOO.ERB:52 `#DIM line`、:240 `#DIM line, 2`、
        // fromEN/MISC.ERB:1054 `#DIMS LINE`、カラム機能(div).ERB:5 `#DIM DYNAMIC LINE`。
        val (labels, warnings) = scan(
            """
            @MAIN
            #DIM line
            #DIM DYNAMIC page
            @SUB
            #DIMS LINE, 2
            """.trimIndent() + "\n",
        )
        assertEquals(2, labels.size)
        assertEquals(listOf("line", "page"), labels[0].dimSpecs.map { it.name })
        assertEquals(listOf("LINE"), labels[1].dimSpecs.map { it.name })
        assertEquals(emptyList<String>(), warnings.map { it.message }, "nameDic 接线不得新增误拒")
    }

    @Test
    fun `撞四类内建名的私有 DIM 逐类被拒且不注册`() {
        val (labels, warnings) = scan(
            """
            @MAIN
            #DIM INT
            #DIM PRINTL
            #DIM STRLENS
            #DIM FLAG
            #DIM GOOD_SLOT
            """.trimIndent() + "\n",
        )
        // 保留字 zhs:873；方法/指令 zhs:874；系统变量 zhs:875（同 checkUserVarName 一族文案）。
        assertEquals(
            listOf(
                "变量名\"INT\"为Emuera的保留字",
                "变量名\"PRINTL\"为Emuera所使用的变量或者命令名",
                "变量名\"STRLENS\"为Emuera所使用的变量或者命令名",
                "变量名\"FLAG\"为Emuera所使用的变量名",
            ),
            warnings.map { it.message },
        )
        assertEquals(listOf(2, 3, 4, 5), warnings.map { it.line }, "定位到各自声明行（1 基）")
        assertEquals("GATE.ERB", warnings.first().file)
        assertEquals(listOf("GOOD_SLOT"), labels.single().dimSpecs.map { it.name }, "被拒的 4 条不进 dimSpecs")
    }

    @Test
    fun `撞 ERH 宏的私有 DIM 按已定义宏被拒`() {
        // spec: IdentifierDictionary.cs:377-380（UserMacro → VarNameAlreadyUsedMacro, zhs:876）；
        // 宏先于 ERB 全部进 nameDic（ErhLoader.cs:155-164）。
        val config = SymbolConfig(macros = mapOf("MYW" to "42"))
        val (labels, warnings) = scan("@MAIN\n#DIM MYW\n#DIM OTHER\n", config)
        assertEquals(listOf("变量名\"MYW\"为已定义的宏"), warnings.map { it.message })
        assertEquals(listOf("OTHER"), labels.single().dimSpecs.map { it.name })
    }

    @Test
    fun `语法三闸与查表闸的先后照抄原版`() {
        // 非法符号先于查表（:348-352 → :360）；`A+B` 同时撞查表也撞符号闸，报符号闸。
        assertEquals(
            "变量名\"A+B\"包含\"_\"以外的符号",
            Symbols.checkUserPrivateVarName("A+B", setOf("A+B")),
        )
        // 数字开头先于查表（:353-358）。
        assertEquals("变量名\"1X\"以半角数字开始", Symbols.checkUserPrivateVarName("1X"))
        // 缺名（TODO(verify) 标注 parseDimSpec 保证不可达，此处只钉函数本体行为）。
        assertEquals("没有标签名", Symbols.checkUserPrivateVarName(""))
    }

    @Test
    fun `全局通道 checkUserVarName 行为不变（私有接线不回写全局口径）`() {
        // 私有变体独有的是「数字开头」与「空名」；全局侧维持原五段判定。
        assertEquals("变量名\"FLAG\"为Emuera所使用的变量名", Symbols.checkUserVarName("FLAG"))
        assertNull(Symbols.checkUserVarName("1X"), "全局 CheckUserVarName 没有数字开头闸（spec: :258-297）")
    }
}
