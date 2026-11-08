package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `CorpusScan.scan` 的 `#FUNCTION`/`#FUNCTIONS` 预扫描口径（funcTypes 表）。
 *
 * 语义权威：C# 装载期 `LogicalLineParser.ParseSharpLine` 对 `#FUNCTION[S]` 的合法性判定
 * （LogicalLineParser.cs:151-213），与 `ErbLoader.loadErb` 的 `#` 行归属规则
 * （ErbLoader.cs:413-424：logicalLine2 必须仍是 FunctionLabelLine）。
 * 语料基线核验（3,971 文件）：现有语料无事件/系统标签声明、无同块重复声明、
 * 无函数中间散落 `#FUNCTION` —— 本组修复对语料是 no-op，只钉住首差。
 */
class CorpusScanTest {

    private fun scanOne(dir: Path, name: String, text: String): CorpusScan {
        val f = dir.resolve(name)
        f.writeText(text)
        return CorpusScan.scan(listOf(f))
    }

    @Test
    fun funcTypesFirstWinsWithinBlockAndRejectsEventSystemLabels(@TempDir dir: Path) {
        // spec: LogicalLineParser.cs:161-176 —— 同一标签行重复声明 AlreadySharpDeclared
        // 后 return false，MethodType 先到者胜；:178-182 —— 事件/系统标签
        //（ParseLabelLine.cs:352-362 Depth==0）声明无效（UseSharpInSystemFunc）。
        val scan = scanOne(
            dir,
            "a.ERB",
            """
            @FN1
            #FUNCTION
            	RETURN
            @FN2
            #FUNCTIONS
            #FUNCTION
            	RETURN
            @EVENTFIRST
            #FUNCTION
            	RETURN
            @COM0
            #FUNCTION
            	RETURN
            @COM_ABLE3
            #FUNCTIONS
            	RETURN
            @STRFN
            #FUNCTIONS
            	RETURN
            """.trimIndent(),
        )
        assertEquals(OperandType.INT, scan.funcTypes["FN1"], "普通 #FUNCTION → INT")
        assertEquals(
            OperandType.STR,
            scan.funcTypes["FN2"],
            "同块交叉重复先到者胜（#FUNCTIONS 已接受，后到的 #FUNCTION 被拒，cs:168-176）",
        )
        assertFalse("EVENTFIRST" in scan.funcTypes, "事件标签不得登记为方法（UseSharpInSystemFunc）")
        assertFalse("COM0" in scan.funcTypes, "系统标签（^COM[0-9]+$）不得登记为方法")
        assertFalse("COM_ABLE3" in scan.funcTypes, "系统标签（^COM_ABLE[0-9]+$）不得登记为方法")
        assertEquals(OperandType.STR, scan.funcTypes["STRFN"], "普通 #FUNCTIONS → STR")
    }

    @Test
    fun funcTypesAcceptsForkFloatDeclaration(@TempDir dir: Path) {
        // fork-src LogicalLineParser.cs:141-183：`#FUNCTIONF` 是第三种 MethodType
        //（EraType.Float）。同一标签行的第二次声明仍按 :161-176 先到者胜。
        val scan = scanOne(
            dir,
            "c.ERB",
            """
            @FLT1
            #FUNCTIONF
            	RETURNF 1.5
            @FLT2
            #FUNCTIONF
            #FUNCTION
            	RETURNF 1.5
            @FLT3
            #FUNCTION
            #FUNCTIONF
            	RETURN
            """.trimIndent(),
        )
        assertEquals(OperandType.FLOAT, scan.funcTypes["FLT1"], "#FUNCTIONF → FLOAT")
        assertEquals(OperandType.FLOAT, scan.funcTypes["FLT2"], "同块先到者胜（FUNCTIONF 已接受）")
        assertEquals(OperandType.INT, scan.funcTypes["FLT3"], "同块先到者胜（FUNCTION 已接受）")
    }

    @Test
    fun funcTypesIgnoresNonAdjacentSharpLines(@TempDir dir: Path) {        // spec: ErbLoader.cs:413-424 —— '#' 行的 logicalLine2 必须仍是 FunctionLabelLine：
        // 普通行（:481-485 解析非 null 并经 addLine :508 更新 logicalLine2）使归属失效，
        // 函数中间的散落 #FUNCTION[S] 原版报 InvalidSharp（:415-418）。
        // 纯注释行不打断归属：ParseLine 对 ';' 行返回 null（LogicalLineParser.cs:387-391，
        // 词法 SkipWhiteSpace LexicalAnalyzer.cs:662-676），ErbLoader.cs:482-485 continue
        // 不更新 logicalLine2；空行同理（EraStreamReader.cs:107-110）。
        val scan = scanOne(
            dir,
            "b.ERB",
            """
            @STALE
            	PRINTL X
            #FUNCTIONS

            @EVFN
            ;注释行不打断归属
            #FUNCTION
            	RETURN
            """.trimIndent(),
        )
        assertFalse(
            "STALE" in scan.funcTypes,
            "函数中间的 #FUNCTIONS 不归属（原版 InvalidSharp）",
        )
        assertEquals(OperandType.INT, scan.funcTypes["EVFN"], "@ 行与 # 行之间的注释行不打断归属")
    }

    @Test
    fun funcTypesLastBlockWinsAcrossDuplicateDefinitions(@TempDir dir: Path) {
        // 跨定义块（同名 @ 多处定义）：每个 @ 行是独立的 FunctionLabelLine 对象，
        // 各自的 #FUNCTION[S] 都合法；生效者按非事件标签「最后装载者胜」
        //（spec: LabelDictionary.cs:60-68 noneventLabelDic[labelName] = label 覆盖写）。
        val scan = scanOne(
            dir,
            "c.ERB",
            """
            @DUPFN
            #FUNCTION
            	RETURN
            @DUPFN
            #FUNCTIONS
            	RETURN
            """.trimIndent(),
        )
        assertEquals(
            OperandType.STR,
            scan.funcTypes["DUPFN"],
            "同名两个定义块各自合法声明，生效者为最后装载的块",
        )
        assertTrue(scan.funcTypes.size == 1, "funcTypes 不应有多余条目: ${scan.funcTypes.keys}")
    }

    @Test
    fun dimModifierStrippingIsWordLevelAndCaseInsensitive(@TempDir dir: Path) {
        // A2（挖虫波第三批）：`#DIM` 修饰符按**词**判定、大小写不敏感。
        // spec: UserDefinedVariableData.cs:54-58 —— 词流由 `WordCollection` 的 IdentifierWord
        // 给出（词界 = 空白 / `,` / `=` / `;`），关键字比对用 `Config.StringComparison`
        //（emuera.config:1 IGNORE CASE:YES ⇒ OrdinalIgnoreCase）；:262-263 —— 循环里遇到的
        // **第一个非关键字**整词才是 Name（loader.md D.8.1「CONSTX 整体是名字」）。
        // 旧实现三处偏：① `startsWith(mod)` 大小写敏感 ⇒ `#DIM const X` 把 "const" 当名字登记；
        // ② 词界只认空白；③ 循环后还有 `contains("CHARADATA")` / `startsWith("CONST")`
        //    两个无词界兜底 ⇒ `CONSTZ` 被误判成 CONST、`CHARADATA_TYPES` 被误判成角色数据变量
        //    （语料实况 fromEN/DEBUG_TRANSLATIONS.ERB:126-127，本次唯一两处读数差）。
        val scan = scanOne(
            dir,
            "d.ERB",
            """
            @F
            #DIM const X, 5
            #DIMS CHARADATA_TYPES, 3
            #DIM CONSTZ, 4
            #DIM REF_W, 2
            #DIM CHARADATA BAR, 1000
            """.trimIndent(),
        )
        val vars = scan.privateVars.values.single()
        assertEquals(
            setOf("X", "CHARADATA_TYPES", "CONSTZ", "REF_W", "BAR"),
            vars.keys,
            "名字必须是「第一个非关键字整词」，不得登记 const/CONST 这类修饰符",
        )
        assertTrue(vars.getValue("X").isConst, "`const`（小写）仍是修饰符")
        assertFalse(vars.getValue("CONSTZ").isConst, "CONSTZ 整词是名字，不是 CONST（cs:262-263）")
        assertFalse(vars.getValue("REF_W").isConst, "REF_W 整词是名字")
        assertFalse(
            vars.getValue("CHARADATA_TYPES").isCharacterData,
            "CHARADATA_TYPES 整词是名字，contains 兜底把它误判成角色数据变量",
        )
        assertTrue(vars.getValue("BAR").isCharacterData, "真写在前面的 CHARADATA 修饰符仍然生效")
        assertEquals(OperandType.STR, vars.getValue("CHARADATA_TYPES").operandType, "#DIMS ⇒ 字符串")
    }

    @Test
    fun dimModifierStrippingAcceptsCommaSeparatedModifier(@TempDir dir: Path) {
        // 词界不只是空白：`,` / `=` 之后同样是词界（IdentifierWord 的分词口径）。
        // 旧循环 `head[mod.length].isWhitespace()` 会把 `#DIM CONST,` 之类的形态漏成名字。
        val scan = scanOne(
            dir,
            "e.ERB",
            """
            @F
            #DIM STATIC, 3
            #DIM NORMAL, 3
            """.trimIndent(),
        )
        val vars = scan.privateVars.values.single()
        assertFalse("STATIC" in vars.keys, "`STATIC,` 的 STATIC 是关键字而非名字")
        assertEquals(setOf("NORMAL"), vars.keys)
    }
}
