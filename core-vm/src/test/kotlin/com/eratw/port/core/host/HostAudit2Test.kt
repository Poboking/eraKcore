package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.PrintCharaData
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.Charset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 宿主胶水层（L09）的边界尺子 —— 每条断言都对着反编译 C# 的手算值。
 *
 * 覆盖三处 2026-10-22 审计出的真实缺陷（改前/改后差一读数即报红）与两块容易被
 * 「顺手修好」的官方口径：
 * 1. `ButtonStringCreator.readDigits` 的有符号整数下界（`[-9223372036854775808]`）；
 * 2. `SemConfig.timeupLabel` 的出厂串（`ConfigData.cs:170` 是繁体 `時間切れ`）；
 * 3. `PrintCharaData.characterParamString` 的 `%6d` 必须按不变文化格式化
 *    （`VariableEvaluator.cs:1193-1198` 的 `CultureInfo.InvariantCulture`）。
 *
 * 另钉四块口径（2026-10-22 续任补）：
 * - `CellWidth` 的四档半角 / 全角 / 按码位累加，并用本机 JVM 的 `windows-31J` 逐点复现
 *   `EmueraConsole.cs:3255` 那条字节数证据（含 `U+203E` 与组合符的分叉登记）；
 * - `readDigits` 改造赖以成立的「符号连同数值进进制解析」在各基下的读数（含正则挡掉的指数符号）；
 * - `PRINT_PALAM` 的两条下标：数值随角色、阈值随全局；`PRINT_ABL` 的名表随全局；
 * - `SemConfig` 的原版出厂值。
 */
class HostAudit2Test {

    // ------------------------------------------------------------------
    // CellWidth：四档半角 / 全角 / 按码位累加
    // ------------------------------------------------------------------

    /** 半角四档与全角的代表点（逐档出处见 [CellWidth.isFullWidth] 的 KDoc）。 */
    @Test
    fun `宽度表四档半角与全角代表点`() {
        // 1. ASCII 可打印；其中 0x5C 屏上是 ¥、0x7E 屏上是 ‾，仍是 1 格
        for (cp in intArrayOf(0x0020, 0x0041, 0x005C, 0x007E)) {
            assertEquals(1, CellWidth.ofCodePoint(cp), "ASCII 半角 U+%04X".format(cp))
        }
        // 2. 半角假名与半角符号（Shift-JIS 单字节 0xA1..0xDF）
        for (cp in intArrayOf(0xFF61, 0xFF71, 0xFF9F)) {
            assertEquals(1, CellWidth.ofCodePoint(cp), "半角假名 U+%04X".format(cp))
        }
        // 3. C0 控制符 / DEL / C1 首码点：932 原样单字节透传
        for (cp in intArrayOf(0x0000, 0x0009, 0x001F, 0x007F, 0x0080)) {
            assertEquals(1, CellWidth.ofCodePoint(cp), "控制符 U+%04X".format(cp))
        }
        // 4. ¥ 走 932 的 best-fit 回 0x5C
        assertEquals(1, CellWidth.ofCodePoint(0x00A5), "U+00A5")
        // 全角侧：全角空格、CJK、全角标点
        for (cp in intArrayOf(0x3000, 0x4E00, 0xFF01, 0x2501)) {
            assertEquals(2, CellWidth.ofCodePoint(cp), "全角 U+%04X".format(cp))
        }
    }

    /**
     * 整串按**码位**累加：增补平面字符是 2 格而不是「两个代理项各 2 格」。
     * 逐码元累加是 `render` 的 `Grid.cells` 口径（已登记的驻留差异），本表不是。
     */
    @Test
    fun `整串按码位累加且代理对不拆`() {
        assertEquals(3, CellWidth.of("あa"))
        assertEquals(10, CellWidth.of("Ｃ感覚LV5 "))
        val emoji = "\uD83D\uDE00" // U+1F600
        assertEquals(2, CellWidth.of(emoji))
        assertEquals(2, CellWidth.ofCodePoint(0x1F600))
        // 孤立代理项不是字符：单独传入按全角 2 计（KDoc 的已知边界）
        assertEquals(2, CellWidth.ofCodePoint(0xD83D))
        // 同一串按码元累加会得 4 格 —— 与 [CellWidth.of] 的粒度差是登记项，不是回归
        assertEquals(4, emoji.length * 2)
    }

    /**
     * `U+203E`：CP932 的 0x7E 真实单字节映射（`windows-31J` 实测 `E2 80 BE` → `7e`），
     * 与 `U+00A5` ↔ `0x5C` 对称 ⇒ 按 `EmueraConsole.cs:3255` 的字节数口径应为 **1 格**。
     *
     * 本表暂按 2 格（唯一表补它会撞 `render` 的 D 类表 35 个码点断言，见 `CellWidth`
     * 末尾的 TODO(verify)）—— 这里钉住**现状**，等跨模块同批改完把期望改成 1。
     */
    @Test
    fun `U+203E 现状按全角计（已登记的缺口）`() {
        assertEquals(2, CellWidth.ofCodePoint(0x203E))
        assertEquals(3, CellWidth.of("a\u203E"))
    }

    // ------------------------------------------------------------------
    // ButtonStringCreator：数值下界
    // ------------------------------------------------------------------

    private fun selectable(text: String): ButtonStringCreator.ButtonPrimitive =
        ButtonStringCreator.splitButton(text).single()

    /**
     * `[-9223372036854775808]` 仍是按钮，`Input = long.MinValue`。
     *
     * 原版路径：`isButtonCore`（`ButtonStringCreator.cs:168-192`）→ `LexicalAnalyzer.ReadInt64`
     * （`LexicalAnalyzer.cs:30-90`）→ `readDigits` 的 `long.Parse(s)`（`:145`，`s` 含符号，
     * 见 `:94-101`）⇒ `long.MinValue` 合法，不抛 `OverflowException`。
     * 「取绝对值再取负」的写法在 |−2^63| > `Long.MAX_VALUE` 处溢出 ⇒ 该 token 被误判成
     * 不可选，整行也随之失去按钮（`num <= 1` 分支 `:63-73`）。
     */
    @Test
    fun `有符号整数下界仍是按钮`() {
        val min = selectable("[-9223372036854775808]")
        assertTrue(min.canSelect, "long.MinValue 原版 long.Parse 正常返回 ⇒ 可选")
        assertEquals(Long.MIN_VALUE, min.input)

        // 十六进制同型：Convert.ToInt64("-8000000000000000", 16)（:147）
        val hex = selectable("[0x-8000000000000000]")
        assertTrue(hex.canSelect)
        assertEquals(Long.MIN_VALUE, hex.input)

        // 下界再减一 / 上界加一：两边都抛（Overflow → CodeEE → catch ⇒ 不可选）
        assertTrue(!selectable("[-9223372036854775809]").canSelect)
        assertTrue(!selectable("[9223372036854775808]").canSelect)
        // 上界本身可选
        assertEquals(Long.MAX_VALUE, selectable("[9223372036854775807]").input)
    }

    /** 指数路径的溢出边界（`ReadInt64` 的 `num5 > 9.223372036854776E18` 判定，:80-88）。 */
    @Test
    fun `指数溢出一律不是按钮`() {
        assertEquals(9_000_000_000_000_000_000L, selectable("[9e18]").input)
        assertTrue(!selectable("[1e19]").canSelect, "1e19 越界 ⇒ CodeEE ⇒ 不是按钮")
        assertTrue(!selectable("[1e]").canSelect, "指数位没有数字 ⇒ CodeEE")
        assertTrue(!selectable("[+]").canSelect, "只有符号 ⇒ long.Parse 抛")
        assertTrue(!selectable("[-]").canSelect)
        // 基 16 的 p 指数（指数位也按基 16 读，:74-78）
        assertEquals(64L, selectable("[0x10p2]").input)
    }

    /** 切分结果必须逐字覆盖入参（`LineBufferConsoleSink` 按字符区间回填样式靠它）。 */
    @Test
    fun `切分结果逐字覆盖入参`() {
        for (text in listOf(
            "[-9223372036854775808]",
            "a[-9223372036854775808]b[1]",
            "[1] x [-9223372036854775808] y [2]",
        )) {
            assertEquals(text, ButtonStringCreator.splitButton(text).joinToString("") { it.text }, text)
        }
    }

    // ------------------------------------------------------------------
    // PrintCharaData：数值格式化按不变文化
    // ------------------------------------------------------------------

    /** 基准 `pc-golden-root.txt:431` 的单元字面值（PrintC 之前的原串）。 */
    @Test
    fun `PRINT_PALAM 单元右对齐宽 6 且数字为 ASCII`() {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = FakeConsoleSink(),
            config = SemConfig(printCPerLine = 5),
        )
        store.registry.csvTable("PALAM").set(0, "快Ｃ")
        store.addPseudoCharacter()
        store.writeInt("PALAMLV", longArrayOf(1), 100)
        store.writeInt("PALAMLV", longArrayOf(2), 500)
        store.writeInt("PALAMLV", longArrayOf(3), 3000)
        store.writeInt("PALAMLV", longArrayOf(4), 10000)

        val cell = PrintCharaData.characterParamString(ctx, 0, 0)
        assertEquals("快Ｃ[..........]     0", cell)

        // 数值含负数时同样右对齐宽 6、符号在数字前
        store.writeInt("PALAM", longArrayOf(0, 0), -42)
        assertEquals("快Ｃ[..........]   -42", PrintCharaData.characterParamString(ctx, 0, 0))
    }

    /**
     * 本地化区域下数字必须仍是 ASCII：C# 侧 `AppendFormatted(num, 6)` 带
     * `CultureInfo.InvariantCulture`（`VariableEvaluator.cs:1193-1198`），而 JVM 的
     * `java.util.Formatter` 用默认区域时会替换成该区域的数字（ar（阿拉伯语）区域
     * 零位是 U+0660，`"%6d"` 得阿拉伯-印度数字）。`"%6d".format(...)` 在本机 JDK 17
     * 上实测会把 `42` 印成 `٤٢` —— 真机阿拉伯语系统语言下同样是这个读数。
     */
    @Test
    fun `阿拉伯语区域下数值仍是 ASCII`() {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = FakeConsoleSink(),
        )
        store.registry.csvTable("PALAM").set(0, "快Ｃ")
        store.addPseudoCharacter()
        store.writeInt("PALAMLV", longArrayOf(1), 100)
        store.writeInt("PALAMLV", longArrayOf(2), 500)
        store.writeInt("PALAMLV", longArrayOf(3), 3000)
        store.writeInt("PALAM", longArrayOf(0, 0), 1234)

        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            // 阈值级联到第 2 档（'=' 100 → '>' 500，1234 < 3000）；条 = 4 个 '>' + 6 个 '.'
            val cell = PrintCharaData.characterParamString(ctx, 0, 0)!!
            assertEquals("快Ｃ[>>>>......]  1234", cell, "条形与数字都与默认区域无关")
            assertEquals("  1234", cell.takeLast(6), "右对齐宽 6、数字为 ASCII")
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ------------------------------------------------------------------
    // SemConfig：原版出厂值
    // ------------------------------------------------------------------

    /**
     * `SemConfig.timeupLabel` 的出厂串必须是 `ConfigData.cs:170` 的 `"時間切れ"`
     * （繁体 `時`/`間`）。消费者：`FunctionIdentifier.cs:1033` / `:1082` 的
     * `TimeUpMes = ... : Config.TimeupLabel`。语料 `emuera.config` 无 `TIME UP MESSAGE`
     * 行 ⇒ 生产路径就是这条出厂串（`GameSession.DEFAULT_SEM_CONFIG` 只覆写 printCPerLine）。
     */
    @Test
    fun `SemConfig 出厂值与 ConfigData 一致`() {
        val c = SemConfig()
        assertEquals("時間切れ", c.timeupLabel)
        assertEquals(3, c.printCPerLine, "ConfigData.cs:65")
        assertEquals(20, c.saveDataNos, "SaveDataNos")
        assertEquals("$", c.moneyLabel, "replaceArray MoneyLabel")
        assertTrue(c.moneyFirst, "MoneyFirst 缺省 true")
        assertEquals(listOf(0L, 0L, 2L, 1L, 8L), c.stainDefault.toList(), "StainDefault")
        assertTrue(!c.useNewRandom)
    }

    // ------------------------------------------------------------------
    // ButtonStringCreator：`readDigits` 改造（符号连同数值进进制解析）的承重假设
    // ------------------------------------------------------------------

    /**
     * `text.toLongOrNull(base)` 必须吃掉带符号串，才谈得上与 C# 同值 ——
     * 基 10 走 `long.Parse(s)`（LexicalAnalyzer.cs:145），基 16/2 走
     * `Convert.ToInt64(s, fromBase)`（:147），两处的 `s` 都含符号（`currentPosition`
     * 取在符号之前，:94-101）。这里逐个基把符号读数钉死，防止有人把改造退回
     * 「剥符号 + 取绝对值」那条在 `Long.MIN_VALUE` 溢出的写法。
     *
     * 符号能不能进到这一步由原版正则决定：`ButtonStringCreator.cs:11` 的
     * `([0][xXbB])?[+-]?` 把 `0x`/`0b` 前缀排在符号**之前** ⇒ `0x+10` 是按钮、`+0x10` 不是。
     */
    @Test
    fun `带符号的各基读数与 longParse Convert 同值`() {
        assertEquals(5L, selectable("[+5]").input, "基 10 正号 = long.Parse(\"+5\")")
        assertEquals(16L, selectable("[0x+10]").input, "基 16 正号 = Convert.ToInt64(\"+10\", 16)")
        assertEquals(-16L, selectable("[0x-10]").input, "基 16 负号（:147 的二补码口径）")
        assertEquals(5L, selectable("[0b+101]").input, "基 2 正号")
        assertEquals(-5L, selectable("[0b-101]").input, "基 2 负号")
        assertEquals(0L, selectable("[-0]").input, "long.Parse(\"-0\") == 0")
        assertTrue(!selectable("[+0x10]").canSelect, "正则把前缀排在符号之前 ⇒ +0x10 不匹配 :11")
    }

    /**
     * 指数的符号**进不到**按钮判定：原版正则的指数段是 `([eEpP][0-9]+)?`
     * （`ButtonStringCreator.cs:11`，不含 `[+-]`），所以 `ReadInt64` 里「指数位也调
     * `readDigits`、而 `readDigits` 认符号」（LexicalAnalyzer.cs:74-78 → :96-99）这条
     * 能力在按钮路径上永远不会被触发。移植侧与原版同判：**不是按钮**。
     */
    @Test
    fun `指数带号一律不是按钮`() {
        assertEquals(100L, selectable("[1e2]").input, "1 * 10^2（LexicalAnalyzer.cs:80-88）")
        assertEquals(64L, selectable("[0x10p2]").input, "p 指数位也按基 16 读（:74-78）")
        assertTrue(!selectable("[1e+2]").canSelect, "正则指数段不含 [+-]")
        assertTrue(!selectable("[1e-2]").canSelect, "同上：原版 ReadInt64 会算出 0，但走不到那一步")
        assertTrue(!selectable("[0x1p+3]").canSelect, "基 16 的 p 指数带号同样被正则挡在门外")
    }

    // ------------------------------------------------------------------
    // CellWidth：第 3、4 档的码页证据（`EmueraConsole.cs:3255` 的字节数口径）
    // ------------------------------------------------------------------

    /**
     * PRINTC 补齐问的是 `Encoding.GetEncoding("Shift-JIS").GetByteCount(str)`
     * （EmueraConsole.cs:3255），即「CP932 里占几个字节」。本用例用本机 JVM 的
     * `windows-31J`（同一张微软 CP932 表）把 [CellWidth] 四档的证据逐点钉住 ——
     * 与 `CellWidth.isFullWidth` KDoc 里「本机 Temurin 17 实测」那批读数一一对应。
     * 不支持该码页的 JVM（部分 Android ROM）直接返回跳过；闸机（Temurin 17）必然执行。
     */
    @Test
    fun `码页字节数逐点钉住四档判据`() {
        if (!Charset.isSupported("windows-31J")) return
        val sjis = Charset.forName("windows-31J")
        fun bytes(cp: Int): List<Int> =
            String(Character.toChars(cp)).toByteArray(sjis).map { it.toInt() and 0xFF }
        // 第 1 档 ASCII 真单字节映射（0x5C / 0x7E 屏上是 ¥ 与 ‾，字节数仍是 1）
        assertEquals(listOf(0x20), bytes(0x0020))
        assertEquals(listOf(0x5C), bytes(0x005C))
        assertEquals(listOf(0x7E), bytes(0x007E))
        // 第 2 档半角假名 = 单字节 A1..DF
        assertEquals(listOf(0xA1), bytes(0xFF61))
        assertEquals(listOf(0xDF), bytes(0xFF9F))
        // 第 3 档 C0 / DEL 原样透传（U+0080 走替换回退，但同样 1 字节）
        assertEquals(listOf(0x00), bytes(0x0000))
        assertEquals(listOf(0x1F), bytes(0x001F))
        assertEquals(listOf(0x7F), bytes(0x007F))
        assertEquals(1, bytes(0x0080).size, "U+0080 替换回退仍 1 字节")
        // 第 4 档 ¥ ↔ 0x5C；待补的 U+203E ↔ 0x7E 是同一条口径的另一半
        assertEquals(listOf(0x5C), bytes(0x00A5))
        assertEquals(listOf(0x7E), bytes(0x203E))
        // 全角侧双字节 ⇒ 2 格（基准 :61 的 99 个 `━` 钉住的就是这一档）
        assertEquals(2, bytes(0x2501).size, "━ = 84 AA")
        assertEquals(2, bytes(0x3000).size, "全角空格")
        // 表外字：字节数口径各得 1（替换回退 3F），本表按 2 格 ⇒ 两条口径的分叉已登记
        assertEquals(listOf(0x3F), bytes(0x301C))
        assertEquals(listOf(0x3F), bytes(0x0300))
        assertEquals(listOf(0x3F), bytes(0x1F600))
    }

    /**
     * 表外字（含组合符 `Mn`、emoji、`U+203E`）的**当前**读数一律 2 格。
     * 上面那条用例给出字节数口径（1 字节）与像素口径（组合符并进基字簇）都不是 2 ——
     * 分叉的登记与卡点见 `CellWidth.isFullWidth` 末尾三个 TODO(verify)；本用例只钉现状，
     * 谁改判必须同时动 `render` 的 D 类表与本用例。
     *
     * `e` + `U+0301` = 3 格正是 `core/fn/GraphicsGfxAuditTest.kt:294` 那条
     * 「PC 8 像素 vs 移植侧 24 像素」里 24 的来源（退化网格 = 格数 x sizePx / 2，size=16）。
     */
    @Test
    fun `表外字与组合符当前一律 2 格`() {
        for (cp in intArrayOf(0x0300, 0x0301, 0x0344, 0x1F600, 0x301C, 0x203E)) {
            assertEquals(2, CellWidth.ofCodePoint(cp), "U+%04X".format(cp))
        }
        assertEquals(3, CellWidth.of("e\u0301"), "e(1) + 组合符 U+0301(2) —— 字簇步进缺失的直接读数")
        assertEquals(2, CellWidth.of("\u00E9"), "同一字簇的 NFC 预组合形 U+00E9：不在四档内 ⇒ 2 格")
    }

    // ------------------------------------------------------------------
    // PrintCharaData：两条下标不是同一个数组
    // ------------------------------------------------------------------

    /**
     * PRINT_PALAM 单元里两个下标来源不同（原版逐句如此，移植必须同构）：
     * - 数值 = **被打印角色**的 PALAM 槽：`varData.CharacterList[target].DataIntegerArray[6][paramCode]`
     *   （VariableEvaluator.cs:1148）；
     * - 名字 = 全局 CSV 名表：`constant.GetCsvNameList(PALAMNAME)[paramCode]`（:1150）；
     * - 阈值 = **全局** PALAMLV：`varData.DataIntegerArray[6]` 的 `array[1..4]`
     *   （:1149 + :1160/:1164/:1169/:1174），与 target 无关。
     *
     * 两个角色共用同一份阈值 ⇒ 只有条形与数字随角色变，档位级联的落点不变。
     * （sem 侧的 `PrintCharaDataTest` 只有 1 个伪角色，钉不出这条区别。）
     */
    @Test
    fun `PRINT_PALAM 数值按角色下标、阈值按全局下标`() {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = FakeConsoleSink(),
        )
        store.registry.csvTable("PALAM").set(0, "快Ｃ")
        store.addPseudoCharacter()
        store.addPseudoCharacter()
        store.writeInt("PALAMLV", longArrayOf(1), 100)
        store.writeInt("PALAMLV", longArrayOf(2), 500)
        store.writeInt("PALAMLV", longArrayOf(3), 3000)
        store.writeInt("PALAMLV", longArrayOf(4), 10000)
        store.writeInt("PALAM", longArrayOf(0, 0), 100)
        store.writeInt("PALAM", longArrayOf(1, 0), 5000)

        // char0：100 ≥ 档1 → '='，阈值取档2=500 → 100*10/500 = 2 格
        assertEquals("快Ｃ[==........]   100", PrintCharaData.characterParamString(ctx, 0, 0))
        // char1：级联到档3 → '*'，阈值取档4=10000 → 5000*10/10000 = 5 格
        assertEquals("快Ｃ[*****.....]  5000", PrintCharaData.characterParamString(ctx, 1, 0))
        // 角色下标越界的文案与 :1144-1147 同源（官方简中包 Error.OoRCharaNum，lang emuera-zhs.xml:312）
        val e = assertFailsWith<CodeEE> { PrintCharaData.characterParamString(ctx, 2, 0) }
        assertEquals("试图引用一个不存在的注册角色", e.message)
    }

    /**
     * PRINT_ABL 家族的数据取的是**角色自己的**槽（`CharacterData.DataIntegerArray[2]`，
     * VariableEvaluator.cs:1078），名表是全局 CSV（:1079），遍历上界取两者较小
     * （:1080）⇒ 两个角色同一份名表、各自的值。
     */
    @Test
    fun `PRINT_ABL 的两个角色共用名表`() {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = FakeConsoleSink(),
        )
        store.registry.csvTable("ABL").set(0, "Ｃ感覚")
        store.addPseudoCharacter()
        store.addPseudoCharacter()
        store.writeInt("ABL", longArrayOf(0, 0), 5)
        store.writeInt("ABL", longArrayOf(1, 0), 2)
        assertEquals("Ｃ感覚LV5 ", PrintCharaData.characterDataString(ctx, 0, FunctionCode.PRINT_ABL))
        assertEquals("Ｃ感覚LV2 ", PrintCharaData.characterDataString(ctx, 1, FunctionCode.PRINT_ABL))
    }
}
