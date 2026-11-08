package com.eratw.port.core.vars

import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.err.DIVIDE_BY_ZERO_TEXT
import com.eratw.port.core.err.MULTIPLY_10K_TO_STR_TEMPLATE
import com.eratw.port.core.err.MULTIPLY_NEGATIVE_TO_STR_TEMPLATE
import com.eratw.port.core.err.notDefinedLabelName
import com.eratw.port.core.err.notDefinedLabelNameText
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.fn.xmlParseErrorMessage
import com.eratw.port.core.fn.xmlPathErrorMessage
import com.eratw.port.core.lexer.LangKey
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.iVar
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpVarSetArgument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 运行期文案接线闸（2026-11-05 lane errtext4）—— 上一路散在三处「字面量层」的运行期串
 * 收进 `enum LangKey` + 仓内 error-texts 文案表之后，本文件钉三件事：
 *
 * 1. **接线面真的走到官方串**：`VariableStore` 的区间端点闸（官方 `OoRInstructionArg`）
 *    与取长度闸（官方 `GetSizeDimError`）从公开入口打出去就是逐字官方文；
 *    动态 JUMP 的标签缺失走 `err/LabelErrorText.kt`（官方 `NotDefinedLabelName`，带 `$`）。
 * 2. **漂移闸**：本波没动的 sibling 层（`err/OperatorErrorText.kt`、`fn/XmlMethods.kt` 的
 *    XML 两枚、`vars/VarErrorText.kt` 已改走 LangKey 的四枚）与 [LangKey] 的模板必须同字，
 *    否则「表 ↔ 枚举 ↔ 抛出点」三层就只对上两层。
 * 3. **防回潮**：退役的自造串不许在产品代码行里复活（形状照 `err/OperatorErrorTextTest.kt:116`）。
 *
 * 官方出处（两张 zhs 表逐字同文；行号 = 语料根表 / reference 内嵌表）：
 *
 * | 键 | 日文 `Lang.cs` | 语料根表 | reference 表 | 原版抛出点 |
 * |---|---|---|---|---|
 * | OoRInstructionArg | :1626 | :575 | :361 | UserDefinedVariableToken.cs:95/99、ReferenceToken.cs:83/87、CharaVariableToken.cs:77/81 |
 * | GetSizeDimError | :1630 | :329 | :362 | ReferenceToken.cs:40、UserDefinedVariableToken.cs:46 |
 * | NotDefinedLabelName | :2342 | :513 | :540 | FunctionIdentifier.cs:3703（CodeEE）、:3668（warn） |
 * | DivideByZero | :1326 | :236 | :286 | OperatorMethodManager.cs:134/:153 |
 * | MultiplyNegativeToStr | :1318 | :486 | :284 | OperatorMethodManager.cs:99 |
 * | Multiply10kToStr | :1322 | :485 | :285 | OperatorMethodManager.cs:103 |
 * | XmlGetError | :1330 | :770 | :287 | FunctionMethodCreator.cs 的 XML_GET 家族 |
 * | XmlGetPathError | :1334 | :771 | :288 | 同上（XPath 支） |
 *
 * ## 扫描面的边界（别「顺手」加宽）
 * 防回潮只扫**已接线波次实际改过**的产品文件：errtext4 面是 `vars/VariableStore.kt` 与
 * `code/Vm.kt`；negrange 面（2026-11-05）追加 `sem/StatementExecutor.kt`（VARSET 负端点双闸
 * 接 OoRInstructionArg）、`fn/VarWriteMethods.kt`（IsNotVar/IsNotInt/IsNotStr/SetStrToInt/
 * SetIntToStr/MissingVarArg/CallCharaVarAsVar 七处接 LangKey）与 `vars/VariableStore.kt`
 * 的 REF 超维支 + isArrayRangeValid 端点两闸；errtext6 面（2026-11-05）追加
 * `vars/VarDef.kt`（超维支接 GetSizeNonExistDim）与 `vars/UserVariables.kt`
 * （colorFromName transparent 支接 TransparentUnsupported）。
 * `fn/VarWriteMethods.kt` 的 checkRangeEndpoint（VARSETEX 端点闸）与 checkedSubscript
 * **仍用自造串** —— 官方 VARSETEX 无端点校验（越界即裸 IndexOutOfRange，
 * FunctionMethodCreator.cs:834/:871 + VariableData.cs:48-54），判据见 specs/error-texts.yaml
 * 附二；不属回潮。
 */
class RuntimeErrorTextWiringTest {

    // ------------------------------------------------------------------
    // 一、接线面：从公开入口打出去就是官方串
    // ------------------------------------------------------------------

    @Test
    fun `VARSET 区间终点越界走官方 OoRInstructionArg`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1000)))
        // 界内（端点可等于长度）照常写
        s.setAll("G0", 7L, 0, 1000)
        assertEquals(7L, s.readInt("G0", longArrayOf(999)))
        val e = assertFailsWith<CodeEE> { s.setAll("G0", 7L, 0, 2000) }
        assertEquals(
            "VARSET命令的第4个参数(2000)超出数组\"G0\"的范围",
            e.message,
            "Error.OoRInstructionArg 逐字（Lang.cs:1626；喂法 = VariableEvaluator.cs:275 的 \"VARSET\", 3L, 4L）",
        )
    }

    @Test
    fun `VARSET 区间起点越界报第3参数`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1000)))
        val e = assertFailsWith<CodeEE> { s.setAll("G0", 7L, -1, 5) }
        assertEquals("VARSET命令的第3个参数(-1)超出数组\"G0\"的范围", e.message, "起点是 VARSET 的第 3 参")
        // 字符串通道同一条（setAllStr 的 G_STR1 支）
        s.declareUserVar(DimSpec("S0", isStr = true, dims = intArrayOf(10)))
        val e2 = assertFailsWith<CodeEE> { s.setAllStr("S0", "x", 0, 11) }
        assertEquals("VARSET命令的第4个参数(11)超出数组\"S0\"的范围", e2.message)
    }

    @Test
    fun `多维变量取整体长度走官方 GetSizeDimError`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("MM", isStr = false, dims = intArrayOf(3, 4)))
        val e = assertFailsWith<CodeEE> { s.getLength("MM") }
        assertEquals("试图获取一个2维数组变量\"MM\"的长度", e.message, "Error.GetSizeDimError 逐字（Lang.cs:1630）")
        // 实参顺序与日文模板同为「维数在前、变量名在后」（UserDefinedVariableToken.cs:46）
        assertEquals(LangKey.GetSizeDimError.renderZhs(2, "MM"), e.message)
    }

    @Test
    fun `多维 REF 取整体长度也走同一条`() {
        val s = VariableStore()
        // 全局 REF 被 NotImplCodeEE 挡下，REF 只能作函数私有变量声明并先进帧（同 VarErrorTextTest 的装置）
        s.declareUserVar(DimSpec("UR", isStr = false, dims = intArrayOf(0, 0), isRef = true, isPrivate = true), "F")
        s.enterFunction("F")
        (s.userVar("UR") as RefUserVar).bindInt(LongArray(4))
        val e = assertFailsWith<CodeEE> { s.getLength("UR") }
        assertEquals("试图获取一个2维数组变量\"UR\"的长度", e.message, "ReferenceToken.cs:40 的 GetSizeDimError")
        s.leaveFunction()
    }

    @Test
    fun `动态跳转的标签缺失带美元 sigil`() {
        assertEquals(
            "当前函数中不存在指定的标签名\"\$NOWHERE\"",
            notDefinedLabelNameText("NOWHERE"),
            "屏上带标签 sigil；旧字面量在 Kotlin 里把花括号加美元符当插值，串塌成 标签名\"0\"",
        )
        assertEquals("当前函数中不存在指定的标签名\"\$NOWHERE\"", notDefinedLabelName("NOWHERE").message)
        // 喂原文（原版 FunctionIdentifier.cs:3703 传未大写化的 text），大小写不许被调用点改
        assertEquals(
            "当前函数中不存在指定的标签名\"\$nowhere\"",
            notDefinedLabelNameText("nowhere"),
        )
    }

    // ------------------------------------------------------------------
    // 一之二、lane negrange（2026-11-05）：VARSET 负端点 / REF 超维 / isArrayRangeValid
    // ------------------------------------------------------------------

    /**
     * 语句层负端点双闸（`execVarSetFlat` 与对象树 `execVarSet`）退役自造串
     * 「VARSET命令的区间起点(-3)为负值」/「…终点(-5)为负值」，接官方 `OoRInstructionArg`。
     * 喂法照 `VariableEvaluator.cs:275/:303`：起点是 VARSET 的第 3 参、终点第 4 参
     * （VariableData.cs:89-100 等四处 token 的 `IsArrayRangeValid` 对 index<0 抛的就是这一枚键）。
     * 两路都打：`run` = 对象树入口（Android 生产默认）、`runFlat` = 扁平直读入口（归因/装置通道）。
     */
    @Test
    fun `VARSET 显式负端点双路都走官方 OoRInstructionArg`() {
        val negStart = instr(
            FunctionCode.VARSET,
            SpVarSetArgument(iVar("FLAG", 0), IntLiteral(7), IntLiteral(-3), IntLiteral(5)),
        )
        val negEnd = instr(
            FunctionCode.VARSET,
            SpVarSetArgument(iVar("FLAG", 0), IntLiteral(7), null, IntLiteral(-5)),
        )
        val oldEnv = SemTestEnv()
        val t1 = assertFailsWith<CodeEE> { oldEnv.run(negStart) }
        assertEquals("VARSET命令的第3个参数(-3)超出数组\"FLAG\"的范围", t1.message, "负起点 = 第 3 参（对象树路）")
        val t2 = assertFailsWith<CodeEE> { oldEnv.run(negEnd) }
        assertEquals("VARSET命令的第4个参数(-5)超出数组\"FLAG\"的范围", t2.message, "负终点 = 第 4 参（对象树路）")
        val flatEnv = SemTestEnv()
        val f1 = assertFailsWith<CodeEE> { flatEnv.runFlat(negStart) }
        assertEquals("VARSET命令的第3个参数(-3)超出数组\"FLAG\"的范围", f1.message, "双路口径一致（扁平直读）")
        val f2 = assertFailsWith<CodeEE> { flatEnv.runFlat(negEnd) }
        assertEquals("VARSET命令的第4个参数(-5)超出数组\"FLAG\"的范围", f2.message, "双路口径一致（扁平直读）")
        // 判停语义没动：负端点仍抛 CodeEE（R22-1），只是文案与出处通道换成官方。
        assertEquals(LangKey.OoRInstructionArg.renderZhs("VARSET", 3, -3L, "FLAG"), t1.message)
    }

    /** REF 变量 `VARSIZE UR, N` 超维：官方 `GetSizeNonExistDim`（ReferenceToken.cs:55）。 */
    @Test
    fun `REF 超维取长度走官方 GetSizeNonExistDim`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("UR", isStr = false, dims = intArrayOf(0, 0), isRef = true, isPrivate = true), "F")
        s.enterFunction("F")
        (s.userVar("UR") as RefUserVar).bindInt(LongArray(4))
        // 界内照常（dimension=1 < rank=2）
        val e = assertFailsWith<CodeEE> { s.getLength("UR", 2) }
        assertEquals("试图获取一个不存在该维度的数组变量\"UR\"的长度", e.message, "Error.GetSizeNonExistDim 逐字（Lang.cs:1634）")
        assertEquals(LangKey.GetSizeNonExistDim.renderZhs("UR"), e.message)
        s.leaveFunction()
    }

    /**
     * `isArrayRangeValid`（官方 IsArrayRangeValid 的移植镜像）端点两闸：自造串
     * 「X 的第 1/2 个区间参数 N 越界（变量 Y 长度 L）」退役 ⇒ 官方 `OoRInstructionArg`。
     * 参数号默认 1/2、调用点可覆盖（官方各家自带：VARSET=3/4、SUMARRAY=2/3…）。
     */
    @Test
    fun `isArrayRangeValid 端点两闸走官方 OoRInstructionArg`() {
        val s = VariableStore()
        // isArrayRangeValid 走注册表支（registry.def），用系统全局 FLAG（长度 10000）
        s.isArrayRangeValid("FLAG", longArrayOf(0), 0, 10000) // 端点可等于长度 ⇒ 不抛
        val e1 = assertFailsWith<CodeEE> { s.isArrayRangeValid("FLAG", longArrayOf(0), -1, 10) }
        assertEquals("SET命令的第1个参数(-1)超出数组\"FLAG\"的范围", e1.message)
        val e2 = assertFailsWith<CodeEE> { s.isArrayRangeValid("FLAG", longArrayOf(0), 0, 10001) }
        assertEquals("SET命令的第2个参数(10001)超出数组\"FLAG\"的范围", e2.message)
        // 参数号由调用点覆盖：喂 SUMARRAY 的官方口径（2/3，FunctionMethodCreator.cs:4404）
        val e3 = assertFailsWith<CodeEE> { s.isArrayRangeValid("FLAG", longArrayOf(0), 0, 10001, "SUMARRAY", 2, 3) }
        assertEquals("SUMARRAY命令的第3个参数(10001)超出数组\"FLAG\"的范围", e3.message)
    }

    /**
     * lane errtext6（2026-11-05）：`VarDef.length` 超维支（注册表 + 用户变量两条
     * `VariableStore.getLength` 通道共用的终点）退役自造串「变量 X 不存在第 N 维（维度 R）」，
     * 接官方 `GetSizeNonExistDim`（`Lang.cs:1634`；原版 `UserDefinedVariableToken.cs:55`、
     * `LocalVariableToken.cs:36`、`VariableData.cs:165/:265`）。屏上旧→新：
     * `变量 G0 不存在第 2 维（维度 1）` ⇒ `试图获取一个不存在该维度的数组变量"G0"的长度`；
     * 判界 `dim < 0 || dim >= dims.size` 一字未动。
     */
    @Test
    fun `用户变量与注册表超维取长度走官方 GetSizeNonExistDim`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(10)))
        assertEquals(10, s.getLength("G0", 0), "界内照常")
        val e = assertFailsWith<CodeEE> { s.getLength("G0", 1) }
        assertEquals("试图获取一个不存在该维度的数组变量\"G0\"的长度", e.message, "VarDef.length 超维支（Error.GetSizeNonExistDim 逐字）")
        // 注册表支（系统全局 1 维 FLAG 取第 2 维）终点同一个 VarDef.length ⇒ 同串同源。
        val e2 = assertFailsWith<CodeEE> { s.getLength("FLAG", 1) }
        assertEquals("试图获取一个不存在该维度的数组变量\"FLAG\"的长度", e2.message)
        assertEquals(LangKey.GetSizeNonExistDim.renderZhs("G0"), e.message)
    }

    /**
     * lane errtext6 的接线形状（装行级）：两枚接线点必须走真源 helper/LangKey，
     * 不许在抛出点再抄一份字面量。同形未接线的家族面归
     * `core/sem/StatementFamilyErrorTextDriftTest`（行级 needle + 屏上展开钉）。
     */
    @Test
    fun `errtext6 接线点走真源而不是抄字面量`() {
        val varDef = nonCommentLines("vars/VarDef.kt")
        assertTrue(
            varDef.any { it.contains("throw getSizeNonExistDim(name)") },
            "VarDef.length 超维支必须调 vars/VarErrorText.kt 的 getSizeNonExistDim",
        )
        val uv = nonCommentLines("vars/UserVariables.kt")
        assertTrue(
            uv.any { it.contains("LangKey.TransparentUnsupported.renderZhs()") },
            "colorFromName 的 transparent 支必须由 LangKey 供词（旧自造串把 C# 行号漏上屏）",
        )
    }

    // ------------------------------------------------------------------
    // 二、漂移闸：sibling 字面量层必须与 LangKey 同字
    // ------------------------------------------------------------------

    @Test
    fun `运算符族常量与枚举模板同字`() {
        assertEquals(LangKey.DivideByZero.renderZhs(), DIVIDE_BY_ZERO_TEXT)
        assertEquals(LangKey.MultiplyNegativeToStr.templateZhs, MULTIPLY_NEGATIVE_TO_STR_TEMPLATE)
        assertEquals(LangKey.Multiply10kToStr.templateZhs, MULTIPLY_10K_TO_STR_TEMPLATE)
    }

    @Test
    fun `XML_GET 两族文案与枚举模板同字`() {
        assertEquals(
            LangKey.XmlGetError.renderZhs("<a/>", "缺少根元素（位置 0）"),
            xmlParseErrorMessage("XML_GET", "<a/>", "缺少根元素（位置 0）"),
        )
        assertEquals(
            LangKey.XmlGetPathError.renderZhs("/r/[", "XPath 括号不匹配"),
            xmlPathErrorMessage("XML_GET", "/r/[", "XPath 括号不匹配"),
        )
        // 非 XML_GET 支是另一形（两表不同文，组长裁定不动）⇒ 不许误纳进本闸
        assertTrue(
            xmlParseErrorMessage("XML_SET", "<a/>", "x") != LangKey.XmlGetError.renderZhs("<a/>", "x"),
            "XML_SET 支仍走 reference 表的 {0}函数:\"{1}\"解析错误:{2} 形",
        )
    }

    @Test
    fun `vars 族四个 helper 与枚举模板同字`() {
        assertEquals(LangKey.OoRVarArg.renderZhs("ITEMNAME", 1, 1000L), oorVarArg("ITEMNAME", 1, 1000L).message)
        assertEquals(
            LangKey.OoRCharaVarArg.renderZhs("EXP", 2, 100L),
            oorCharaVarArg("EXP", 2, 100L).message,
        )
        assertEquals(LangKey.ZeroDVarHasArg.renderZhs("ZZ"), zeroDVarHasArg("ZZ").message)
        assertEquals(LangKey.EmptyRefVar.renderZhs("UR"), emptyRefVar("UR").message)
        // 上一路的读数一字没动（本波只是把字面量搬进枚举），旧钉仍在位
        assertEquals("数组变量\"ITEMNAME\"的第1参数(1000)超出数组范围", oorVarArg("ITEMNAME", 1, 1000L).message)
    }

    /**
     * negrange 新收编 8 枚的**屏上原文逐字钉**（两张 zhs 表同文，行号见 specs/error-texts.yaml
     * 第八节；真源回读由 `LangKeySourceTableTest` 负责，这里钉的是「展开后屏上长什么样」）。
     * 注意官方形态的三个易错点：IsNotVar/IsNotInt/IsNotStr 的 zhs **不带引号、实参紧贴文案**
     * （自造旧形多一个半角空格）；SetStrToInt/SetIntToStr/MissingVarArg/GetSizeNonExistDim/
     * CallCharaVarAsVar 带半角直引号。
     */
    @Test
    fun `negrange 收编的八枚 zhs 模板逐字钉`() {
        assertEquals("FLAG:1+2不是变量", LangKey.IsNotVar.renderZhs("FLAG:1+2"))
        assertEquals("STR:0不是整型", LangKey.IsNotInt.renderZhs("STR:0"))
        assertEquals("FLAG:3不是字符串型", LangKey.IsNotStr.renderZhs("FLAG:3"))
        assertEquals("尝试将字符串赋值给非字符串变量\"STR\"", LangKey.SetStrToInt.renderZhs("STR"))
        assertEquals("尝试将整数赋值给非整数型变量\"FLAG\"", LangKey.SetIntToStr.renderZhs("FLAG"))
        assertEquals("变量\"GLOBAL59\"缺少必要的参数", LangKey.MissingVarArg.renderZhs("GLOBAL59"))
        assertEquals("试图获取一个不存在该维度的数组变量\"UR\"的长度", LangKey.GetSizeNonExistDim.renderZhs("UR"))
        assertEquals("角色变量\"EXP\"被当作非角色变量调用", LangKey.CallCharaVarAsVar.renderZhs("EXP"))
        // vars 层 helper 同字（ getSizeNonExistDim 是本波新增的唯一 helper 出口）
        assertEquals(LangKey.GetSizeNonExistDim.renderZhs("ZZ"), getSizeNonExistDim("ZZ").message)
    }

    /**
     * 抛出点的**接线形状**（文件级）：VarWriteMethods 的七枚官方串必须由 LangKey 供词、
     * StatementExecutor 的两路负端点闸必须调 vars 层唯一 helper，不许再抄第二份字面量。
     */
    @Test
    fun `negrange 接线点走 LangKey 真源而不是抄字面量`() {
        val vw = readMainSource("fn/VarWriteMethods.kt")
        for (call in listOf(
            "LangKey.IsNotVar.renderZhs(text)",
            "LangKey.IsNotInt.renderZhs(text)",
            "LangKey.IsNotStr.renderZhs(text)",
            "LangKey.SetStrToInt.renderZhs(text)",
            "LangKey.SetIntToStr.renderZhs(text)",
            "LangKey.MissingVarArg.renderZhs(ref.name)",
            "LangKey.CallCharaVarAsVar.renderZhs(ref.name)",
        )) {
            assertTrue(vw.contains(call), "fn/VarWriteMethods.kt 抛点必须经 $call")
        }
        val sem = readMainSource("sem/StatementExecutor.kt")
        assertTrue(sem.contains("oorInstructionArg(\"VARSET\", 3,"), "负起点闸要调 vars 层 oorInstructionArg（第 3 参）")
        assertTrue(sem.contains("oorInstructionArg(\"VARSET\", 4,"), "负终点闸要调 vars 层 oorInstructionArg（第 4 参）")
        val varErr = readMainSource("vars/VarErrorText.kt")
        for (k in listOf("IsNotVar", "IsNotInt", "IsNotStr", "SetStrToInt", "SetIntToStr", "MissingVarArg", "GetSizeNonExistDim", "CallCharaVarAsVar")) {
            assertTrue(LangKey.valueOf(k).templateZhs.isNotEmpty(), "LangKey.$k 必须已登记")
        }
        assertTrue(varErr.contains("LangKey.GetSizeNonExistDim"), "GetSizeNonExistDim 由 LangKey 供词")
        val store = readMainSource("vars/VariableStore.kt")
        assertTrue(store.contains("getSizeNonExistDim("), "REF 超维支要调 vars/VarErrorText.kt 的 getSizeNonExistDim")
    }

    // ------------------------------------------------------------------
    // 三、防回潮：退役自造串不许在产品代码行复活
    // ------------------------------------------------------------------

    @Test
    fun `退役的自造串不再出现在本波改过的产品代码行`() {
        val retired = mapOf(
            "vars/VariableStore.kt" to listOf(
                "的区间起点",
                "的区间终点",
                "维，必须指定维度",
                // negrange：REF 超维支与 isArrayRangeValid 端点两闸
                "维（维度",
                "个区间参数",
            ),
            "code/Vm.kt" to listOf(
                "当前函数中不存在指定的标签名",
            ),
            // negrange（2026-11-05）VARSET 负端点双闸：只钉 VARSET 前缀两串 ——
            // 「为负值」裸形不许当针（AWAIT/SAVEDATA/DELDATA 的官方同形字面量合法在位）。
            "sem/StatementExecutor.kt" to listOf(
                "VARSET命令的区间起点",
                "VARSET命令的区间终点",
            ),
            // negrange：SETVAR/VARSETEX 七处接线（needle 取旧字面量的独有片段；
            // convertArray 的 `"$name" 不是变量` 属两表不同文的 CheckVariableTerm 族，
            // 判「不动」，其 needle 形态 `$name 不是变量` 不与此处 `$text 不是变量` 冲突）。
            "fn/VarWriteMethods.kt" to listOf(
                "text 不是变量",
                "text}不是整型",
                "text}不是字符串型",
                "的下标被省略",
                "尝试将字符串赋值给非字符串变量",
                "尝试将整数赋值给非整数型变量",
                "是角色变量，不能整体操作",
            ),
            // errtext6（2026-11-05）：VarDef 超维支退役「变量 X 不存在第 N 维（维度 R）」，
            // 接线枚只扫**本波改过的文件**——float/CSV 两支（VariableStore.kt:1414/:1423）
            // 按 negrange 附二 #6 的 TODO(verify) 保留旧形，扫描面因此不含 VariableStore 的「不存在第」。
            "vars/VarDef.kt" to listOf(
                "不存在第",
                "维（维度",
            ),
            "vars/UserVariables.kt" to listOf(
                "不支持 transparent",
            ),
        )
        val hits = ArrayList<String>()
        for ((rel, needles) in retired) {
            val lines = readMainSource(rel).lines()
            lines.forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                for (needle in needles) if (line.contains(needle)) hits += "$rel:${i + 1} 命中「$needle」"
            }
        }
        assertTrue(hits.isEmpty(), "以下代码行仍在抛退役串：\n${hits.joinToString("\n")}")
    }

    @Test
    fun `接线点必须走单一真源 helper 而不是自己抄字面量`() {
        val store = readMainSource("vars/VariableStore.kt")
        assertTrue(store.contains("oorInstructionArg("), "区间端点闸要调 vars/VarErrorText.kt 的 oorInstructionArg")
        assertTrue(store.contains("getSizeDimError("), "取长度闸要调 vars/VarErrorText.kt 的 getSizeDimError")
        val vm = readMainSource("code/Vm.kt")
        assertTrue(vm.contains("notDefinedLabelNameText("), "JUMP 标签缺失要调 err/LabelErrorText.kt 的真源")
        val label = readMainSource("err/LabelErrorText.kt")
        assertTrue(label.contains("LangKey.NotDefinedLabelName"), "标签族以 LangKey 为字面量唯一持有者")
        val varErr = readMainSource("vars/VarErrorText.kt")
        val keys = listOf(
            "OoRVarArg", "OoRCharaVarArg", "ZeroDVarHasArg", "EmptyRefVar",
            "OoRInstructionArg", "GetSizeDimError",
        )
        for (k in keys) {
            assertTrue(varErr.contains("LangKey.$k"), "vars 族 ${k} 必须由 LangKey 供词")
        }
    }

    // ------------------------------------------------------------------
    // 四、行号兄弟面：全工程只许一处 `+1`，打印点一律走 erbDisplayLine
    // ------------------------------------------------------------------

    @Test
    fun `编译与解析消息吃的是归一后的 1 基行号`() {
        // 字段仍是内部 0 基（键面不许改基），只有消息是 1 基 —— 与 CodeEE.displayLine 同一出口。
        val ce = CompileException("TRAIN", 5, "GOTO 目标缺失")
        assertEquals(5, ce.line, "line 字段保持 0 基下标")
        assertEquals("编译错误(函数:TRAIN, 行:6): GOTO 目标缺失", ce.message)
        assertEquals("编译错误(函数:?, 行:-1): 函数重名", CompileException(null, -1, "函数重名").message)

        val ex = ExprParseException(0, 3, "表达式中不允许 '='")
        assertEquals(0, ex.line, "line 字段保持 0 基下标")
        assertEquals("解析错误(行:1,列:3): 表达式中不允许 '='", ex.message)
        assertEquals("解析错误(行:-1,列:-1): 不该出现的符号", ExprParseException(-1, -1, "不该出现的符号").message)
    }

    /**
     * 「行:N」前缀的**每一个**产品装配点都必须**就地**调 [com.eratw.port.core.err.erbDisplayLine]。
     *
     * 判定颗粒度 = 打前缀的那一行自己（装行级），**不是**整文件 `contains`：
     * 2026-11-05 实测旧形状是假闸 —— 把 `compile/CompileError.kt` 的消息改成自己算
     * `if (line >= 0) line + 1 else line`（第二处换算，违规形状）并删掉 import，
     * 本类的文件级 `contains("erbDisplayLine")` 照样被 KDoc 里的函数名喂绿、整轮 BUILD SUCCESSFUL
     * ⇒ 该文件级判据一条都不红。改成装行级后，同一破坏立刻红在本用例上。
     *
     * 例外一律写进 [ALREADY_ONE_BASED_AT_SOURCE] 并附理由；不在名单里的新装配点自动受管，
     * 名单里的文件一旦接上唯一出口也不会红（判据只看「有没有红」，不要求名单被命中）。
     */
    @Test
    fun `带行号前缀的消息装配点都就地调唯一出口`() {
        val prefixes = listOf("编译错误(函数:", "解析错误(行:", "语句解析错误(行:", "汇编错误(行:")
        val offenders = ArrayList<String>()
        for (f in mainSources()) {
            val rel = relOf(f)
            f.readText().lines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (prefixes.none { line.contains(it) }) return@forEachIndexed
                if (line.contains("erbDisplayLine")) return@forEachIndexed
                if (rel in ALREADY_ONE_BASED_AT_SOURCE) return@forEachIndexed
                offenders += "$rel:${i + 1} → ${line.trim().take(90)}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "以下消息装配点自己换算行号（全工程只许 erbDisplayLine 一处 +1）：\n${offenders.joinToString("\n")}",
        )
    }

    /**
     * 已登记的**不吃本出口**的装配点 ⇒ 键面坐标系不同源，逐个写明理由，别默默漏掉。
     * `code/Assembler.kt` 的 `汇编错误(行:N)` 打的是**文本 asm 自己**的行：
     * `Assembler.kt:122` 的 `currentLine = idx + 1` 在词法侧就已经 1 基（`:111` 初值 0），
     * 与 ERB `Statement.line` 的 0 基键面无关 ⇒ 再走一次 erbDisplayLine 反而会多一行。
     */
    private val ALREADY_ONE_BASED_AT_SOURCE = setOf("code/Assembler.kt")

    // ------------------------------------------------------------------
    // 五、源码定位（与 ErrorTextRenderTest / OperatorErrorTextTest 同一套相对路径候选）
    // ------------------------------------------------------------------

    private fun mainCoreRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/core"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 core-vm main 源码根（工作目录 ${File(".").absolutePath}）")
    }

    private fun readMainSource(relative: String): String {
        val f = File(mainCoreRoot(), relative)
        assertTrue(f.isFile, "缺文件 $f")
        return f.readText()
    }

    /** 非注释行的原文列表（装行级判据的底座；KDoc/行注释不喂针）。 */
    private fun nonCommentLines(relative: String): List<String> =
        readMainSource(relative).lines().filter {
            val t = it.trimStart()
            !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
        }

    private fun mainSources(): List<File> = mainCoreRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    // 统一成 '/'：Windows 下 substring 出来的是反斜杠，会让名单比对与报错路径两处口径不一致
    private fun relOf(f: File): String =
        f.absolutePath.substring(mainCoreRoot().absolutePath.length + 1).replace('\\', '/')
}
