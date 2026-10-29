package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * audit2/L11 —— `FunctionCode.flag` 全表的证据闸。
 *
 * 口径（唯一权威 = specs 下的 isa.yaml；C# 反编译只作逐名依据行）：
 * - `AInstruction.flag` 的合成终值 = `FunctionIdentifier.cs:4200-4204`
 *   （`flag = instruction.Flag | additionalFlag`）；只给 ArgumentBuilder 的重载
 *   `:4210-4218` 直接用注册行实参的 flag（`:3801-3805`）；缺省 0 走 `:3796-3799`。
 * - 位定义 `FunctionIdentifier.cs:3721-3753`，读取接口 `:4230-4308`（16 个 `Is*`，
 *   **没有** IS_TRYC=32784 的读取者）。
 *
 * 本表把 305 个枚举条目**不重不漏**地分成四块，每块钉一种证据：
 *
 * | 块 | 名数 | 证据 |
 * |---|---|---|
 * | isa.yaml 条目级 `flag`（含其 `aliases`） | 179 | 规约字段 `flag` |
 * | isa.yaml 的 `flag_by_alias`（PRINT 77 + PRINT_DATA 9） | 86 | 规约字段 `flag_by_alias` |
 * | isa.yaml 未登记 `flag` 的流程/调用/调试名 | 37 | C# 指令类构造器行 + 注册行（本文件逐个列出行号） |
 * | 移植侧伪码 `SET` / `METHOD` / `__NULL__` | 3 | C# `:3829`/`:4140`/无对应 |
 *
 * PRINT 那 77 名**运行期真值是 `PrintSpec.flag`**（由 `PrintSpec.parse` 复现 C#
 * `FunctionIdentifier.cs:85-171` 的同一遍名字后缀解析），本枚举的 `flag` 只是登记位；
 * 这里额外断言「登记值 == 运行期值」，两侧一旦漂移即炸（对拍口径见
 * `PrintFlagByAliasTest`，本用例不重复它）。
 */
class StmtFlagAudit2Test {

    private data class SpecTable(
        /** isa.yaml 条目级 `flag`，摊到该条目的 name + aliases 上（只收注册名形态） */
        val entryFlags: Map<String, Int>,
        /** PRINT / PRINT_DATA 两族的 `flag_by_alias` 合并 */
        val aliasFlags: Map<String, Int>,
    )

    private fun specFile(): File {
        for (c in listOf("../specs/isa.yaml", "specs/isa.yaml", "../../specs/isa.yaml")) {
            val f = File(c)
            if (f.isFile) return f
        }
        error("找不到 specs/isa.yaml（工作目录 ${File(".").absolutePath}）")
    }

    /**
     * 行级抽取，不引 yaml 依赖。块边界靠缩进判定：
     * `- name: X` 开条目，`  flag:` 给条目级值，`  aliases:` 可折行续（4 空格 + 逗号列表），
     * `  flag_by_alias:` 开别名块，`  flag_source_by_alias:` 显式退出（其键行形状相同但值是带引号串）。
     */
    private fun loadSpec(): SpecTable {
        val entry = LinkedHashMap<String, Int>()
        val alias = LinkedHashMap<String, Int>()
        val intKey = Regex("^    ([A-Z][A-Z0-9_]*): (\\d+)$")
        val aliasCont = Regex("^    ([A-Z][A-Z0-9_]*(?:, ?[A-Z][A-Z0-9_]*)*) *$")

        var names = ArrayList<String>()
        var flag: Int? = null
        var inAliasBlock = false
        var pendingAliasCont = false

        fun flush() {
            val f = flag
            if (f != null) names.forEach { entry.put(it, f) }
            names = ArrayList()
            flag = null
        }

        for (raw in specFile().readLines(Charsets.UTF_8)) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("- name: ")) {
                flush()
                inAliasBlock = false
                pendingAliasCont = false
                names.add(line.removePrefix("- name: ").trim())
                continue
            }
            if (line == "  flag_by_alias:") {
                inAliasBlock = true
                pendingAliasCont = false
                continue
            }
            if (line == "  flag_source_by_alias:") {
                inAliasBlock = false
                pendingAliasCont = false
                continue
            }
            if (inAliasBlock) {
                val m = intKey.find(line)
                if (m != null) alias[m.groupValues[1]] = m.groupValues[2].toInt()
                continue
            }
            // aliases 值可折行：折行只在「紧跟 aliases 行、且整行是大写名逗号表」时接受，
            // 其它 4 空格续行（语义/错误文案的换行）一律不算，避免把散文当注册名。
            if (pendingAliasCont) {
                val m = aliasCont.find(line)
                if (m != null) {
                    m.groupValues[1].split(',').forEach { n -> names.add(n.trim()) }
                    continue
                }
                pendingAliasCont = false
            }
            if (line.startsWith("  aliases:")) {
                line.removePrefix("  aliases:").split(',').forEach { n ->
                    val t = n.trim()
                    if (t.isNotEmpty()) names.add(t)
                }
                pendingAliasCont = true
                continue
            }
            val m = Regex("^  flag: (\\d+)$").find(line)
            if (m != null) flag = m.groupValues[1].toInt()
        }
        flush()
        // 只保留 FunctionCode 里真实存在的注册名（规约里的类派生名如 DO_NOTHING 不算）
        val enumNames = FunctionCode.entries.map { it.name }.toSet()
        return SpecTable(entry.filterKeys { it in enumNames }, alias)
    }

    /** isa.yaml 没有条目级 `flag`、只能按 C# 逐名钉的 37 名；值 + 依据行（类构造器置位行, 注册行）。 */
    private val csOnly: Map<String, Pair<Int, String>> = linkedMapOf(
        // CALL_Instruction：flag=33（:3505），isJump |=64（:3508），isTry |=128（:3512），
        // isTryCatch |=32784（:3516）；注册 :4040-4051，TRY* 另加 additionalFlag=2。
        "JUMP" to (97 to "FunctionIdentifier.cs:3505,3508,4040"),
        "CALL" to (33 to "FunctionIdentifier.cs:3505,4041"),
        "TRYJUMP" to (227 to "FunctionIdentifier.cs:3505,3508,3512,4042"),
        "TRYCALL" to (163 to "FunctionIdentifier.cs:3505,3512,4043"),
        "JUMPFORM" to (99 to "FunctionIdentifier.cs:3505,3508,4044"),
        "CALLFORM" to (35 to "FunctionIdentifier.cs:3505,4045"),
        "TRYJUMPFORM" to (227 to "FunctionIdentifier.cs:3505,3508,3512,4046"),
        "TRYCALLFORM" to (163 to "FunctionIdentifier.cs:3505,3512,4047"),
        "TRYCJUMP" to (33011 to "FunctionIdentifier.cs:3505,3508,3512,3516,4048"),
        "TRYCCALL" to (32947 to "FunctionIdentifier.cs:3505,3512,3516,4049"),
        "TRYCJUMPFORM" to (33011 to "FunctionIdentifier.cs:3505,3508,3512,3516,4050"),
        "TRYCCALLFORM" to (32947 to "FunctionIdentifier.cs:3505,3512,3516,4051"),
        // GOTO_Instruction：flag=37（:3644），isTry |=128（:3647），isTryCatch |=32784（:3651）；注册 :4057-4062。
        "GOTO" to (37 to "FunctionIdentifier.cs:3644,4057"),
        "TRYGOTO" to (167 to "FunctionIdentifier.cs:3644,3647,4058"),
        "GOTOFORM" to (39 to "FunctionIdentifier.cs:3644,4059"),
        "TRYGOTOFORM" to (167 to "FunctionIdentifier.cs:3644,3647,4060"),
        "TRYCGOTO" to (32951 to "FunctionIdentifier.cs:3644,3647,3651,4061"),
        "TRYCGOTOFORM" to (32951 to "FunctionIdentifier.cs:3644,3647,3651,4062"),
        // LIST 三兄弟走只给 ArgumentBuilder 的重载（:3801-3805），flag 即注册行实参。
        "TRYCALLLIST" to (147 to "FunctionIdentifier.cs:4065"),
        "TRYJUMPLIST" to (211 to "FunctionIdentifier.cs:4066"),
        // ELSEIF_Instruction：flag=53（:3086）；CASE/CASEELSE 另加 additionalFlag=2（:3966-3967）。
        "ELSE" to (53 to "FunctionIdentifier.cs:3086,3962"),
        "ELSEIF" to (53 to "FunctionIdentifier.cs:3086,3963"),
        "CASE" to (55 to "FunctionIdentifier.cs:3086,3966"),
        "CASEELSE" to (55 to "FunctionIdentifier.cs:3086,3967"),
        // ENDIF_Instruction：flag=49（:3100）；ENDIF +4（:3964）、ENDSELECT +6（:3968）、
        // DO +6（:3975）、ENDCATCH +6（:4064）、ENDFUNC +2（:4069）。
        "ENDIF" to (53 to "FunctionIdentifier.cs:3100,3964"),
        "ENDSELECT" to (55 to "FunctionIdentifier.cs:3100,3968"),
        "DO" to (55 to "FunctionIdentifier.cs:3100,3975"),
        "ENDCATCH" to (55 to "FunctionIdentifier.cs:3100,4064"),
        "ENDFUNC" to (51 to "FunctionIdentifier.cs:3100,4069"),
        // REPEAT_Instruction：flag=21（:2987），fornext 再 |=2（:2991）；FOR 注册另加 2（:3971）。
        "REPEAT" to (21 to "FunctionIdentifier.cs:2987,3969"),
        "FOR" to (23 to "FunctionIdentifier.cs:2987,2991,3971"),
        // REND_Instruction：flag=21（:3390）；NEXT 注册另加 2（:3972）。
        "REND" to (21 to "FunctionIdentifier.cs:3390,3970"),
        "NEXT" to (23 to "FunctionIdentifier.cs:3390,3972"),
        // DEBUGPRINT_Instruction：flag=14（:552），newline 再 |=256（:555）；注册 :4070-4073。
        "DEBUGPRINT" to (14 to "FunctionIdentifier.cs:552,4070"),
        "DEBUGPRINTL" to (270 to "FunctionIdentifier.cs:552,555,4071"),
        "DEBUGPRINTFORM" to (14 to "FunctionIdentifier.cs:552,4072"),
        "DEBUGPRINTFORML" to (270 to "FunctionIdentifier.cs:552,555,4073"),
    )

    private val pseudo = setOf("SET", "METHOD", "__NULL__")

    /**
     * PRINT 两族之外**也**置 IS_PRINT（8192）的 20 名 —— 原版由各自的指令类构造器直接置位，
     * 逐名给依据行（`FunctionIdentifier.cs`）。`IsPrint()`（:4275-4278，`(flag & 0x2000) == 8192`）
     * 的唯一读取点是 `Process.cs:1035` 的 skipPrint 判定，所以「会往控制台出东西、
     * 或需要被快进跳过」的语句都在这一位里，不是 PRINT 家族专属。
     * INPUT 族的 24576 / 24578 = IS_PRINT|IS_INPUT（16384，:3751）双置，同为原版事实。
     */
    private val nonPrintIsPrint: Map<String, String> = linkedMapOf(
        "BAR" to "FunctionIdentifier.cs:1296", // BAR_Instruction flag=8198；注册 :3924
        "BARL" to "FunctionIdentifier.cs:1296", // 同一类 newline 形态；注册 :3925
        "BINPUT" to "FunctionIdentifier.cs:2118", // flag=24576 = IS_PRINT|IS_INPUT
        "BINPUTS" to "FunctionIdentifier.cs:2222",
        "CLEARLINE" to "FunctionIdentifier.cs:688", // CLEARLINE_Instruction flag=8198；注册 :3939
        "FORCEWAIT" to "FunctionIdentifier.cs:769", // WAIT_Instruction(force) flag=8192；注册 :3936
        "INPUT" to "FunctionIdentifier.cs:832",
        "INPUTS" to "FunctionIdentifier.cs:876",
        "ONEBINPUT" to "FunctionIdentifier.cs:2326",
        "ONEBINPUTS" to "FunctionIdentifier.cs:2431",
        "ONEINPUT" to "FunctionIdentifier.cs:920", // flag=24578 = IS_PRINT|IS_INPUT|EXTENDED
        "ONEINPUTS" to "FunctionIdentifier.cs:964",
        "REUSELASTLINE" to "FunctionIdentifier.cs:673", // flag=8198；注册 :3940
        "TINPUT" to "FunctionIdentifier.cs:1010",
        "TINPUTS" to "FunctionIdentifier.cs:1059",
        "TONEINPUT" to "FunctionIdentifier.cs:1010", // TINPUT_Instruction(oneInput)；注册 :3932
        "TONEINPUTS" to "FunctionIdentifier.cs:1059", // TINPUTS_Instruction(oneInput)；注册 :3933
        "TWAIT" to "FunctionIdentifier.cs:805", // flag=8194 = IS_PRINT|EXTENDED；注册 :3934
        "WAIT" to "FunctionIdentifier.cs:769", // 注册 :3927
        "WAITANYKEY" to "FunctionIdentifier.cs:791", // 注册 :3935
    )

    @Test
    fun `证据四块对 305 个枚举条目不重不漏`() {
        val spec = loadSpec()
        val names = FunctionCode.entries.map { it.name }.toSet()
        assertEquals(305, names.size, "FunctionCode 枚举条目数")

        val covered = spec.entryFlags.keys + spec.aliasFlags.keys + csOnly.keys + pseudo
        val dup = covered.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dup.isEmpty(), "同一名字被多块重复登记：$dup")
        assertEquals(names, covered.toSet(), "四块证据的名字并集必须等于枚举全集")
        // 规模钉死：179 规约摊名 + 86 别名 + 37 仅 C# + 3 伪码 = 305
        assertEquals(179, spec.entryFlags.size, "isa.yaml 条目级 flag 摊到的注册名数")
        assertEquals(86, spec.aliasFlags.size, "isa.yaml flag_by_alias 的两族别名总数")
        assertEquals(37, csOnly.size, "isa.yaml 未登记 flag、只能按 C# 钉的名数")
    }

    @Test
    fun `isa_yaml 条目级 flag 覆盖的 179 名逐名一致`() {
        val spec = loadSpec()
        assertTrue(spec.entryFlags.isNotEmpty(), "isa.yaml 应能抽到条目级 flag")
        for ((name, expected) in spec.entryFlags) {
            assertEquals(expected, FunctionCode.valueOf(name).flag, "$name 的 FunctionCode.flag 与 isa.yaml 不一致")
        }
    }

    @Test
    fun `isa_yaml flag_by_alias 覆盖的 86 名逐名一致`() {
        val spec = loadSpec()
        assertEquals(86, spec.aliasFlags.size)
        for ((name, expected) in spec.aliasFlags) {
            assertEquals(expected, FunctionCode.valueOf(name).flag, "$name 的登记 flag 与 isa.yaml flag_by_alias 不一致")
        }
    }

    @Test
    fun `PRINT 家族 77 名的登记值等于运行期 PrintSpec 的 flag`() {
        // 运行期真值在 PrintSpec（C# :85-171 的名字后缀单遍解析）；枚举 flag 只登记。
        // 两者一旦漂移说明有人开始把枚举 flag 当运行期口径改，立即炸。
        val spec = loadSpec()
        val printNames = spec.aliasFlags.keys.filter { it.startsWith("PRINT") && !it.startsWith("PRINTDATA") }
        assertEquals(77, printNames.size, "PRINT 家族别名数")
        for (name in printNames) {
            val code = FunctionCode.valueOf(name)
            val parsed = PrintSpec.parse(code)
            assertTrue(parsed != null, "$name 应被 PrintSpec.parse 接受")
            assertEquals(parsed!!.flag, code.flag, "$name 的登记 flag 应等于运行期 PrintSpec.flag")
        }
    }

    @Test
    fun `isa_yaml 未登记 flag 的 37 名按 C# 依据行钉值`() {
        for ((name, ev) in csOnly) {
            assertEquals(ev.first, FunctionCode.valueOf(name).flag, "$name 的 flag（依据 $ev.second）")
        }
    }

    @Test
    fun `伪码条目 SET 为 4 METHOD 为 6 NULL 为 0`() {
        // SET：setFunc = new FunctionIdentifier("SET", SET, new SET_Instruction())（:3829），
        //       三参重载 additionalFlag 缺省 0（:4195-4198），SET_Instruction flag=4（:611）。
        assertEquals(4, FunctionCode.SET.flag, "SET = METHOD_SAFE（FunctionIdentifier.cs:611,3829,4195-4198）")
        // METHOD：methodInstruction = new METHOD_Instruction()（:3828），flag=6（:589）；
        //         方法名进表用 :4220-4228 的 ctor，`flag = instruction.Flag` 不叠加（:4140）。
        assertEquals(6, FunctionCode.METHOD.flag, "METHOD = METHOD_SAFE|EXTENDED（FunctionIdentifier.cs:589,3828,4140,4220-4228）")
        // __NULL__ 是移植侧「非指令行」占位，原版无注册、无位。
        assertEquals(0, FunctionCode.__NULL__.flag, "__NULL__ 为移植侧占位")
    }

    @Test
    fun `位义不变量 —— IS_TRYC 只落在 6 个 TRYC 名且原版无读取者`() {
        // IS_TRYC = 32768（:3737）与 PARTIAL = 16（:3729）总是成对出现（:3516 / :3651 的 |=32784）。
        val tryc = FunctionCode.entries.filter { it.flag and 32768 != 0 }.map { it.name }.sorted()
        assertEquals(
            listOf("TRYCCALL", "TRYCCALLFORM", "TRYCGOTO", "TRYCGOTOFORM", "TRYCJUMP", "TRYCJUMPFORM"),
            tryc,
            "IS_TRYC 位只应由 CALL/GOTO 的 isTryCatch 分支置上（FunctionIdentifier.cs:3514-3516,3649-3651）",
        )
        assertTrue(
            FunctionCode.entries.all { it.flag and 32768 == 0 || it.flag and 16 != 0 },
            "置 IS_TRYC 处必然同时置 PARTIAL（32784 = IS_TRYC|PARTIAL）",
        )
        // IS_PRINTDATA = 65536（:3753）只落在 PRINT_DATA 家族 9 名（基值 73746，:235）。
        val printData = FunctionCode.entries.filter { it.flag and 65536 != 0 }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "PRINTDATA", "PRINTDATAD", "PRINTDATADL", "PRINTDATADW",
                "PRINTDATAK", "PRINTDATAKL", "PRINTDATAKW", "PRINTDATAL", "PRINTDATAW",
            ),
            printData,
            "IS_PRINTDATA 位只应在 PRINT_DATA 家族（FunctionIdentifier.cs:235）",
        )
        // IS_PRINT = 8192（:3749）：PRINT 家族 77 名 + PRINT_DATA 家族 9 名 + 非 PRINT 的 20 名。
        // 20 名不是移植侧多写的位，是原版指令类构造器就置上的（依据行见 [nonPrintIsPrint]）；
        // 读取点 Process.cs:1035 的 skipPrint 判定本身就覆盖这些语句。
        val isPrint = FunctionCode.entries.filter { it.flag and 8192 != 0 }.map { it.name }.toSet()
        val printFamily = loadSpec().aliasFlags.keys
            .filter { it.startsWith("PRINT") && !it.startsWith("PRINTDATA") }.toSet()
        assertEquals(77, printFamily.size, "PRINT 家族 77 名（isa.yaml flag_by_alias）")
        assertEquals(
            printFamily + printData.toSet() + nonPrintIsPrint.keys,
            isPrint,
            "IS_PRINT 位的覆盖面必须逐名等于原版（PRINT 77 + PRINT_DATA 9 + 非 PRINT 20 = 106）",
        )
        assertEquals(106, isPrint.size, "IS_PRINT 位覆盖的总名数")
    }

    @Test
    fun `flag 为 0 的只有 6 名`() {
        // QUIT 族走 addFunction(code, arg) → additionalFlag 缺省 0（:3796-3799 + :3949,4108-4110）；
        // VARI/VARS 的指令类体内无任何 flag 赋值（:33-71），保持 AInstruction.cs:7 的默认 0（:4125-4126）。
        val zeros = FunctionCode.entries.filter { it.flag == 0 }.map { it.name }.sorted()
        // 顺序按 `sorted()` 的真实字典序：'_'(0x5F) 大于所有大写字母，故 `__NULL__` 排在最后。
        assertEquals(
            listOf("FORCE_QUIT", "FORCE_QUIT_AND_RESTART", "QUIT", "QUIT_AND_RESTART", "VARI", "VARS", "__NULL__"),
            zeros,
            "除 6 个原版零 flag 名外，只有移植侧占位 __NULL__ 为 0",
        )
    }

    // -----------------------------------------------------------------------
    // 本路第二半 —— 语句名后的分隔符语义（LineParser.splitArgs）
    // -----------------------------------------------------------------------

    private fun parseLine(text: String, fullSpace: Boolean = true): Statement? =
        LineParser(lexerConfig = LexerConfig(systemAllowFullSpace = fullSpace))
            .parseLogicalLine(LogicalLine(LineKind.CODE, "audit2.erb", 10, text))

    private fun printArgText(text: String, fullSpace: Boolean = true): String {
        val s = assertIs<PrintStmt>(parseLine(text, fullSpace), "$text 应解析成 PrintStmt")
        return (s.args as ExpressionArgument).constStr
    }

    @Test
    fun `语句名后的分号是分隔符不是注释，且只吃一个分隔字符`() {
        // spec: LogicalLineParser.cs:517-522 —— 命中 ';' ' ' '\t'（'　' 需 SystemAllowFullSpace）
        // 就只 ShiftNext() 一次，然后把整行剩余原样交给 ArgumentBuilder；
        // STR/STR_NULLABLE 通道不进词法器，直接 charStream.Substring()
        // （ArgumentParser.cs:446-476），所以分号之后的内容不会被当注释吞掉。
        assertEquals("abc", printArgText("PRINT;abc"), "PRINT;abc 的实参原文应是 abc")
        assertEquals("a;b", printArgText("PRINT a;b"), "PRINT a;b 的实参原文应是 a;b")
        assertEquals(" x", printArgText("PRINT  x"), "两个空格只吃 1 个 ⇒ 实参带一个前导空格")
        assertEquals("", printArgText("PRINT"), "名后什么都没有 = 空实参（:513-516）")
    }

    @Test
    fun `全角空格分隔符随 SystemAllowFullSpace 开关`() {
        // 开：与 ' ' 同等地位，吃掉 1 个字符（:518 的条件分支）。
        assertEquals("x", printArgText("PRINT\u3000x", fullSpace = true))
        // 关：落到 :523-527 的 InvalidLine，原版文案是 InvalidCharacterAfterInstruction1
        // （「要把该配置打开」），移植侧消息保留同一个配置名。
        val e = assertFailsWith<StmtParseException> { printArgText("PRINT\u3000x", fullSpace = false) }
        assertTrue(
            e.message!!.contains("SystemAllowFullSpace"),
            "关闭全角空格时的报错应指向该配置，实际：${e.message}",
        )
        // 其余字符一律拒绝（原版同分支）。
        assertFailsWith<StmtParseException> { parseLine("PRINT,x") }
    }
}
