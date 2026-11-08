package com.eratw.port.core.lexer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 文案通道的**真源回读闸**（2026-11-05 lane errtext4）—— `enum LangKey` 的每一项都直接回读
 * reference 仓里的两份官方源，而不是只跟 `specs/error-texts.yaml` 互相对表。
 *
 * ## 为什么不是「表对枚举」就够了
 * `ErrorTextRenderTest` 做的是 `specs/error-texts.yaml` ↔ `LangKey` 的双向对账：两边一起漂
 * 就查不出来（上一路把 9 枚运行期键补进通道时，表与枚举都只有作者抄的那一份依据）。
 * 本文件把每一行读数钉到物理文件上：
 * - `templateJa` = `reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`
 *   的 [LangKey.langCsLine] 那一行的 `TranslatableString <键名>` 属性字面量；
 * - `templateZhs` = `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml`
 *   的 [LangKey.zhsXmlLine] 那一行的 `tr id="Error.<键名>"` 的 CDATA 文本。
 *
 * ## 通道级事实（本文件第 3 个用例把它钉住）
 * [LangKey.zhsXmlLine] 索引的是**仓内 reference 内嵌表**，不是语料根的 `lang/emuera.zhs.xml`：
 * 两表同文但行号不同源。实测 `MacroOverLimit` 在 reference 表 :843（枚举记的正是 843），
 * 在语料根表是 :455；而语料根表的 :843 是 `SystemLine.DoYouOverwrite` ⇒ 若按语料根表读，
 * 整条通道的 42+12 枚键会全部错位。语料根表的行号按键记在
 * `specs/error-texts.yaml` 的 `zhs_root_xml` 字段（本轮新增的 14 条都记了；文末附录的表达式族
 * 仍是注释段、不进口径）。
 */
class LangKeySourceTableTest {

    private val langCs: File by lazy {
        File(repoRoot(), "reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs")
    }
    private val refTable: File by lazy {
        File(repoRoot(), "reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml")
    }
    private val specFile: File by lazy {
        listOf(
            File("../specs/error-texts.yaml"),
            File("specs/error-texts.yaml"),
            File("../../specs/error-texts.yaml"),
        ).first { it.isFile }
    }

    private fun repoRoot(): File {
        val candidates = listOf(File(".."), File("."), File("../.."))
        return candidates.firstOrNull { File(it, "reference/emuera-cs").isDirectory }
            ?: run {
                // 公开仓不含 reference/emuera-cs（内部参考源码）：按公开适配口径跳过。
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 reference/emuera-cs：跳过文案真源回读闸")
                error("unreachable")
            }
    }

    private fun File.linesUtf8(): List<String> = readText().removePrefix("").split("\n")

    // ------------------------------------------------------------------
    // 一、逐项回读两份官方源
    // ------------------------------------------------------------------

    @Test
    fun `每个键的日文模板逐字等于 Lang_cs 那一行的属性字面量`() {
        val lines = langCs.linesUtf8()
        for (key in LangKey.entries) {
            val line = lines[key.langCsLine - 1]
            val name = csharpPropertyName(line)
            assertEquals(key.name, name, "Lang.cs:${key.langCsLine} 不是 `Lang.Error.${key.name}` 的定义行")
            assertEquals(
                key.templateJa,
                csharpStringLiteral(line),
                "Lang.cs:${key.langCsLine} 的日文模板与 LangKey.${key.name}.templateJa 不同字",
            )
        }
    }

    @Test
    fun `每个键的中文模板逐字等于 reference 内嵌表那一行的 CDATA`() {
        val lines = refTable.linesUtf8()
        for (key in LangKey.entries) {
            val line = lines[key.zhsXmlLine - 1]
            assertEquals(
                "Error.${key.name}",
                trId(line),
                "reference 内嵌表 :${key.zhsXmlLine} 的 tr id 不是 Error.${key.name}" +
                    "（⇒ zhsXmlLine 索引的必须是 reference 表，不是语料根表）",
            )
            assertEquals(
                key.templateZhs,
                cdata(line),
                "reference 内嵌表 :${key.zhsXmlLine} 的译文与 LangKey.${key.name}.templateZhs 不同字",
            )
        }
    }

    // ------------------------------------------------------------------
    // 二、照抄纪律的硬点：`$` 是文案本体，不是 Kotlin 模板符号
    // ------------------------------------------------------------------

    @Test
    fun `标签名文案的美元符是文案本体不是模板符号`() {
        // 官方两张表都写作 标签名"${0}"（日文 ラベル名"${0}"）；Kotlin 侧少写一个反斜杠时
        // `${0}` 被当插值取实参 0，屏上塌成「标签名"0"」—— code/Vm.kt 的旧字面量正是这个缺陷，
        // 本波把它接到 LangKey 上（同形缺陷仍在 proc/ProcessRuntime.kt:3568，非本路文件面）。
        val rendered = LangKey.NotDefinedLabelName.renderZhs("NOWHERE")
        assertEquals("当前函数中不存在指定的标签名\"\$NOWHERE\"", rendered)
        assertTrue(rendered.contains("\$"), "屏上必须带标签 sigil：$rendered")
        assertEquals(
            "指定されたラベル名\"\$NOWHERE\"は現在の関数内に存在しません",
            LangKey.NotDefinedLabelName.renderJa("NOWHERE"),
            "日文形同样带 sigil（Lang.cs:2342）",
        )
    }

    // ------------------------------------------------------------------
    // 三、通道级事实：zhsXmlLine 索引哪张表
    // ------------------------------------------------------------------

    @Test
    fun `zhsXmlLine 索引的是 reference 内嵌表而不是语料根表`() {
        val refLines = refTable.linesUtf8()
        // 枚举记的是 843 ⇒ 只有把它当 reference 表读才命中；语料根表的同一行是 SystemLine 族。
        assertEquals(843, LangKey.MacroOverLimit.zhsXmlLine)
        assertEquals("Error.MacroOverLimit", trId(refLines[843 - 1]))
        val sameText = cdata(refLines[843 - 1]) == LangKey.MacroOverLimit.templateZhs
        assertTrue(sameText, "reference 表 :843 必须与枚举的 zhs 模板同字")
        // 反证：两张表同文但不同行号 ⇒ 全文件统一按语料根表读必然错位。
        assertTrue(
            trId(refLines[455 - 1]) != "Error.MacroOverLimit" || refLines.size < 455,
            "reference 表的 :455 不该是 MacroOverLimit（那一枚行号属于语料根表）",
        )
    }

    // ------------------------------------------------------------------
    // 四、specs 的 zhs_root_xml 记账面（语料根表不在仓内，只钉「有没有记」）
    // ------------------------------------------------------------------

    @Test
    fun `新增的运行期键都在文案表里记了语料根表行号`() {
        val rows = loadSpecRows()
        val runtimeKeys = listOf(
            "DivideByZero", "MultiplyNegativeToStr", "Multiply10kToStr", "XmlGetError", "XmlGetPathError",
            "ZeroDVarHasArg", "OoRCharaVarArg", "OoRInstructionArg", "GetSizeDimError", "OoRVarArg",
            "EmptyRefVar", "NotDefinedLabelName", "GetSizeCharaVarWithoutDim", "GetSize0DCharaVar",
            // 2026-11-05 lane negrange「区间/下标/越界族」收编的 8 枚（两张 zhs 表逐字同文）：
            "IsNotVar", "IsNotInt", "IsNotStr", "SetStrToInt", "SetIntToStr",
            "MissingVarArg", "GetSizeNonExistDim", "CallCharaVarAsVar",
            // 2026-11-05 lane errtext6「negrange 需接线清单」同形收编的 32 枚（逐枚双表验过同文；
            // 行号对账由上一个用例「语料根表可读时逐个核对」实跑，见 specs 第九节）：
            "OoRArrayShift", "OoRArrayRemove", "OoRArraySort", "OoRCharaVar", "IsUsableOnly1DVar",
            "AwaitArgIsNegative", "AwaitArgIsOver10Seconds",
            "SavedataArgIsNegative", "TooLargeSavedataArg", "SavetextContainNewLineCharacter",
            "LoaddataArgIsNegative", "TooLargeLoaddataArg",
            "OoRSavecharaArg", "DuplicateCharaNo", "OoRPickupcharaArg", "RefUndefinedChara",
            "OoRForcekanaArg", "AssertArgIs0", "SetcolorArgLessThan0", "SetcolorArgOver255",
            "ArgIsOoRBit", "OoRCvarsetArg",
            "ArraycopyArgIsNotArray", "ArraycopyArgIsCharaVar",
            "DifferentArraycopyArgsDim", "DifferentArraycopyArgsType",
            "MaxBarNotPositive", "BarNotPositive", "TooLongBar",
            "TransparentUnsupported", "InvalidColorName",
            "tooLongEncodetouniArg", "CanNotUseInstruction",
        )
        for (k in runtimeKeys) {
            val row = rows[k] ?: error("specs/error-texts.yaml 缺 $k")
            val rootLine = row["zhs_root_xml"]?.toIntOrNull()
            val refLine = row["zhs_xml"]?.toIntOrNull()
            assertTrue(rootLine != null && rootLine > 0, "$k 必须记 zhs_root_xml（语料根表行号），实际：$row")
            assertTrue(refLine != null && refLine > 0, "$k 必须记 zhs_xml（reference 表行号）")
            assertEquals(LangKey.valueOf(k).zhsXmlLine, refLine, "$k 的 zhs_xml 与枚举不同源")
            // 两表行号不同源：同一枚键在两张表里的行号不许被写成同一个数（除非确实重合）。
            assertTrue(
                rootLine != refLine,
                "$k 的 zhs_root_xml 与 zhs_xml 相同 ⇒ 至少有一列是抄错的（两表行数不同源）",
            )
        }
    }

    @Test
    fun `语料根表可读时逐个核对 zhs_root_xml 的行号与文本`() {
        // 语料根表只物理存在于共享根（主 worktree 才有 `eratw-画蛇添足版eratw4.980`），
        // 别的 worktree 里按相对路径找不到 ⇒ 优先吃闸门导出的 `ERATW_GAMEDIR`（agents.md §5，
        // 与语料门同源），再退回相对候选；都没有才按 JUnit assumption 跳过 —— 别把「没跑」看成绿。
        val fromEnv = System.getenv("ERATW_GAMEDIR")?.let { File(it, "lang/emuera.zhs.xml") }?.takeIf { it.isFile }
        val root = listOfNotNull(fromEnv) + listOf(
            File("../eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml"),
            File("../../eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml"),
        ).filter { it.isFile }.ifEmpty { listOf(null) }
        val file = root.firstOrNull()
        Assumptions.assumeTrue(file != null, "本 worktree 没有语料根表（且未导出 ERATW_GAMEDIR）：跳过根表行号复核")
        val lines = file!!.linesUtf8()
        for ((id, row) in loadSpecRows()) {
            val rootLine = row["zhs_root_xml"]?.toIntOrNull() ?: continue
            val line = lines[rootLine - 1]
            assertEquals("Error.$id", trId(line), "语料根表 :$rootLine 不是 Error.$id（$id.zhs_root_xml 抄错）")
            assertEquals(row["zhs"], cdata(line), "语料根表 :$rootLine 的译文与表记不同字（$id 两表可能不同文）")
        }
    }

    // ------------------------------------------------------------------
    // 五、只认本表固定格式的最小解析（与 ErrorTextRenderTest 同一口径，不引第三方 YAML）
    // ------------------------------------------------------------------

    private fun loadSpecRows(): Map<String, Map<String, String>> {
        val records = mutableListOf<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        var blockKey: String? = null
        val block = StringBuilder()
        fun flushBlock() {
            val k = blockKey ?: return
            cur?.set(k, block.toString())
            blockKey = null
            block.setLength(0)
        }
        for (line in specFile.readLines()) {
            if (line.startsWith("  - id: ")) {
                flushBlock()
                val m = linkedMapOf<String, String>()
                m["id"] = line.removePrefix("  - id: ").trim()
                records.add(m)
                cur = m
                continue
            }
            if (blockKey != null) {
                if (line.startsWith("      ")) {
                    if (block.isNotEmpty()) block.append('\n')
                    block.append(line.substring(6))
                    continue
                }
                flushBlock()
            }
            val fm = Regex("^    ([a-z_]+): (.*)$").find(line) ?: continue
            if (cur == null) continue
            if (fm.groupValues[2] == "|-") blockKey = fm.groupValues[1] else cur[fm.groupValues[1]] = fm.groupValues[2]
        }
        flushBlock()
        return records.associate { it.getValue("id") to it }
    }

    // ------------------------------------------------------------------
    // 五、两份官方源的取词器（不引 XML / Roslyn，只按本仓固定格式取字）
    // ------------------------------------------------------------------

    private fun csharpPropertyName(line: String): String? {
        val i = line.indexOf("TranslatableString ")
        if (i < 0) return null
        return line.substring(i + "TranslatableString ".length).split(" ", "\t")[0].trim()
    }

    private fun csharpStringLiteral(line: String): String? {
        val i = line.indexOf("new TranslatableString(")
        if (i < 0) return null
        var body = line.substring(i + "new TranslatableString(".length).trim()
        if (body.endsWith(");")) body = body.dropLast(2)
        if (body.endsWith(")")) body = body.dropLast(1)
        body = body.trim()
        if (!body.startsWith("\"") || !body.endsWith("\"") || body.length < 2) return null
        return unescapeCs(body.substring(1, body.length - 1))
    }

    private fun unescapeCs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> { sb.append(c); sb.append(n) }
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun trId(line: String): String? = Regex("id=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    private fun cdata(line: String): String? {
        val a = line.indexOf("<![CDATA[")
        if (a < 0) return null
        val b = line.indexOf("]]>", a)
        if (b < 0) return null
        return line.substring(a + "<![CDATA[".length, b)
    }
}
