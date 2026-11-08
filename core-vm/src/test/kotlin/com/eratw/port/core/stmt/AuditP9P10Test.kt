package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.LangKey
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W10 · AUD-p9p10 —— 挖虫 rec97 的 P9/P10 两条「静默失败」判性与落点。
 *
 * ## 判性结论（与 BOARD 原始描述有出入，以本夹具为准）
 *
 * **P9「PRINT 家族未知后缀返回 null」** —— 前提部分不成立：`PrintSpec.parse` 返回 null 后
 * `LineParser.kt` 立刻抛 `StmtParseException`，并非静默。真正的缺口只有**文案**：自造串
 * 「PRINT 语句名后缀非法」对不上 `Lang.Error.AbnormalPrint`（`Lang.cs:2250`，zhs:517
 * 「PRINT异常」）。且该断言在原版**不可达**：名字来自 `code.ToString()`
 * （`FunctionIdentifier.cs:3814`），用户写的非法后缀在 `funcDic` 查表阶段就已失败。
 * 故本条属「开发者可见诊断」（ADR-005 例外第 1 条），照抄官方文案即可。
 *
 * **P10「strDouble 把畸形数值文本静默折成 0」** —— 折 0 本身是**忠实**的：原版
 * `ArgumentParser.cs:2553-2556` 就是 `catch { doublevalue = 0.0; }`。真正偏差有三处：
 * ① 原版 `ReadDouble` 是**前缀扫描**，`1.5abc` 得 1.5 + `TooManyArg` 告警，移植 `toDoubleOrNull`
 * 得 0.0 且静默；② 该告警整个缺失；③ 逗号切分不认括号深度。
 * **但 `ArgType.STR_DOUBLE` 在原版与移植两侧都没有任何语句登记**
 * （`FunctionIdentifier.cs` 全表 0 处 `FunctionArgType.STR_DOUBLE`；`TOOLTIP_SETDELAY` 用的是
 * `INT_EXPRESSION`，:2027）⇒ 该构造器是**双侧死码**，不产生任何 ERB 可观测差异。
 * 本夹具因此直测构造器本体（`ArgBuilders.create` + `argTypeOverride`），把「忠实镜像」钉住，
 * 而不是宣称修了什么屏上行为。
 */
class AuditP9P10Test {

    private val warnings = mutableListOf<String>()

    private fun ctx(argText: String): ArgCtx {
        warnings.clear()
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 0)
            .declareFunction("F", OperandType.INT)
        return ArgCtx(
            line = 11,
            code = FunctionCode.SET,
            statementName = "SET",
            argText = argText,
            resolver = sym,
            config = ParserConfig(),
            warn = { warnings.add(it) },
            // 绕过 FunctionCode 表直取 STR_DOUBLE 构造器（该 ArgType 无语句登记，见类头）
            argTypeOverride = ArgType.STR_DOUBLE,
        )
    }

    private fun build(argText: String): StrDoubleArgument =
        assertIs<StrDoubleArgument>(ArgBuilders.create(ctx(argText)))

    // -----------------------------------------------------------------------
    // P10 一、尾参按 ReadDouble 的**前缀**读数（ArgumentParser.cs:2540-2557）
    // -----------------------------------------------------------------------

    @Test
    fun `数字前缀后接杂字保留数值并只告警参数过多`() {
        val arg = build("A, 1.5abc")
        assertEquals(1.5, arg.doubleValue, "ReadDouble 只吃前缀，余字不影响已读到的值")
        assertEquals(1, warnings.size, "余字非空 ⇒ level-1 TooManyArg，且只此一条：$warnings")
        assertTrue(warnings[0].contains("参数过多"), "文案取官方 zhs：${warnings[0]}")
        assertTrue(warnings[0].startsWith("SET"), "必须带「语句名 + 命令:」前缀：${warnings[0]}")
    }

    @Test
    fun `数字前缀后有空白分隔的第二个数字同样保留首值`() {
        // 旧实现 `"1.5 2".toDoubleOrNull()` == null ⇒ 0.0 静默；原版读 1.5 再告警。
        val arg = build("A, 1.5 2")
        assertEquals(1.5, arg.doubleValue)
        assertTrue(warnings.single().contains("参数过多"), "$warnings")
    }

    @Test
    fun `读不出实数时静默折 0 且不告警`() {
        // spec: ArgumentParser.cs:2553-2556 —— `catch { doublevalue = 0.0; }` 越过 :2548 的告警。
        // 注意与 SP_TIMES 不同（那里 :351-355 要告警「不是实数」）。
        assertEquals(0.0, build("A, abc").doubleValue)
        assertEquals(emptyList(), warnings, "解析失败是静默折 0，不是告警：$warnings")
    }

    @Test
    fun `行内注释被 SkipWhiteSpace 吞掉不算余字`() {
        assertEquals(1.5, build("A, 1.5 ;注释").doubleValue)
        assertEquals(emptyList(), warnings, "注释吞到 EOS，原版无告警：$warnings")
    }

    @Test
    fun `指数段按原版只认 e 后的负号`() {
        assertEquals(1000.0, build("A, 1e3").doubleValue)
        assertEquals(emptyList(), warnings)
        // `1e+3`：ReadDouble 不认 `e` 后的 `+`，子串退化为 "1e" ⇒ double.Parse 抛 ⇒ 静默 0.0
        assertEquals(0.0, build("A, 1e+3").doubleValue)
        assertEquals(emptyList(), warnings, "解析失败不告警：$warnings")
    }

    // -----------------------------------------------------------------------
    // P10 二、逗号切分必须认括号深度（LexicalAnalyzer.cs:836-843）
    // -----------------------------------------------------------------------

    @Test
    fun `括号内的逗号不是参数分隔点`() {
        // 旧实现用裸 `indexOfFirst` 在 `F(1` 处切断 ⇒ 首参归约失败整行作废。
        val arg = build("F(1,2), 100")
        assertEquals(100.0, arg.doubleValue, "分隔点是最外层逗号，尾参是 100")
        assertEquals(emptyList(), warnings)
        assertNotNull(arg.term, "首参必须归约成 F(1,2) 的调用项")
    }

    // -----------------------------------------------------------------------
    // P10 三、首参归约不出项 ⇒ WrongFormat（ArgumentParser.cs:2558-2563）
    // -----------------------------------------------------------------------

    @Test
    fun `首参归约不出项时报格式错误而不是塞 null`() {
        // 旧实现在无逗号分支直接 `StrDoubleArgument(term=null, 0.0)` —— 运行期空表达式位。
        val e = assertFailsWith<StmtParseException> { build("") }
        assertTrue(e.message!!.contains("格式错误"), "文案取 Lang.Error.WrongFormat（Lang.cs:1966）：${e.message}")
    }

    @Test
    fun `逗号在最前时首参同样按格式错误处理`() {
        val e = assertFailsWith<StmtParseException> { build(", 100") }
        assertTrue(e.message!!.contains("格式错误"), "${e.message}")
    }

    @Test
    fun `只有首参无逗号时尾参恒为 0 且不告警`() {
        // 原版：Analyse 吃到 EoL，ReadDouble 面对空流抛异常 ⇒ 0.0，且 catch 越过告警。
        val arg = build("A")
        assertEquals(0.0, arg.doubleValue)
        assertEquals(emptyList(), warnings, "无尾参不是「参数过多」：$warnings")
        assertNotNull(arg.term)
    }

    // -----------------------------------------------------------------------
    // P9 文案面
    // -----------------------------------------------------------------------

    @Test
    fun `PRINT 后缀断言取官方 AbnormalPrint 文案`() {
        // Lang.cs:2250 / zhs:517；ja「PRINT異常」、zhs「PRINT异常」
        assertEquals("PRINT异常", LangKey.AbnormalPrint.render())
        assertEquals("PRINT異常", LangKey.AbnormalPrint.renderJa())
        assertEquals("PRINTDATA异常", LangKey.AbnormalPrintdata.render())
    }

    @Test
    fun `LineParser 不得再出现自造的 PRINT 后缀文案`() {
        // 源码面断言：防止自造串回流（本波把「PRINT 语句名后缀非法」换成官方 zhs 文案）。
        val src = java.nio.file.Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/core/stmt/LineParser.kt")
        val text = src.readText()
        assertTrue(
            !text.contains("语句名后缀非法"),
            "PRINT 后缀失败必须用 LangKey.AbnormalPrint（FunctionIdentifier.cs:167-170），不许自造文案",
        )
        assertTrue(
            text.contains("LangKey.AbnormalPrint.render()"),
            "LineParser 必须引用 AbnormalPrint 键",
        )
    }

    // -----------------------------------------------------------------------
    // 出厂 no-op 对拍：把「两条判性都是不可观测」变成机器可查的事实
    //
    // 本波三处改动里，两处若真能影响 ERB 就会破坏 no-op 承诺：
    // ① `LineParser` 的 AbnormalPrint 分支 —— 只有当某个 `ArgType.PRINT` 的 FunctionCode
    //    名字无法回环时才触发；② `strDouble` —— 只有当某条语句登记 `ArgType.STR_DOUBLE` 时才可达。
    // 两条断言把这两个前提钉住：任一条变红都说明「本次改动已进入可观测面」，
    // 必须补端到端对拍（语料冒烟 / GOLDEN）后才能合流，不许只删断言了事。
    // -----------------------------------------------------------------------

    @Test
    fun `所有 PRINT 家族语句名的后缀都能回环`() {
        val printCodes = FunctionCode.entries.filter { it.argType == ArgType.PRINT }
        assertTrue(printCodes.isNotEmpty(), "PRINT 家族登记必须非空，否则本断言恒真、失去意义")
        val broken = printCodes.filter { PrintSpec.parse(it) == null }
        assertEquals(
            emptyList(),
            broken,
            "AbnormalPrint 分支在原版是不可达不变量（名字来自 code.ToString()，" +
                "FunctionIdentifier.cs:3814）；若此处非空则它已可被用户触达，须补屏上文案对拍",
        )
    }

    @Test
    fun `没有任何语句登记 STR_DOUBLE 参数形态`() {
        // 原版同样 0 处：`FunctionIdentifier.cs` 全表无 `FunctionArgType.STR_DOUBLE` 的登记点
        // （TOOLTIP_SETDELAY 用的是 INT_EXPRESSION，:2027）。故 strDouble 是**双侧死码**，
        // 本波修的是「忠实镜像」而非屏上行为 —— 这条断言就是那个前提的机器化。
        assertEquals(
            emptyList(),
            FunctionCode.entries.filter { it.argType == ArgType.STR_DOUBLE }.map { it.name },
            "STR_DOUBLE 一旦被登记，strDouble 即进入可观测面：本波的忠实化需补端到端对拍",
        )
    }
}
