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
    fun funcTypesIgnoresNonAdjacentSharpLines(@TempDir dir: Path) {
        // spec: ErbLoader.cs:413-424 —— '#' 行的 logicalLine2 必须仍是 FunctionLabelLine：
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
}
