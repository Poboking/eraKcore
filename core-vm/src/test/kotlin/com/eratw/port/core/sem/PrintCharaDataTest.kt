package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.e2e.LineConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * PRINT_ABL / PRINT_TALENT / PRINT_MARK / PRINT_EXP / PRINT_PALAM 语义。
 *
 * 断言口径 = pc-golden-root.txt CHARA-PRINTDATA 节（426-434 行）的逐字节字面值：
 * - :427 PRINT_ABL —— `Ｃ感覚LV5 技巧LV2 `（值 0 / 名字空的项跳过 → ABL 1-11 空档）；
 * - :428 PRINT_TALENT —— `[性別]`（方括号包裹、无空格）；
 * - :429/:430 PRINT_MARK / PRINT_EXP —— 空串，控制台 PrintFlush(force) 补成单空格行
 *   （spec: EmueraConsole.cs:3379-3382，行模型归 LineConsoleSink，语句层只产空串）；
 * - :431-434 PRINT_PALAM —— 19 项、每 5 项一行（PrintCPerLine=5）、单元右对齐 25 字节、
 *   数值右对齐宽 6、值 0 且阈值 > 0 时条形为 10 个 '.'。
 */
class PrintCharaDataTest {

    // ------------------------------------------------------------------
    // 装置：1 个伪角色 + 与基准同构的 CSV 名字表 + PALAMLV 阈值
    // ------------------------------------------------------------------

    private class Harness(console: ConsoleSink, printCPerLine: Int = 5) {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
            config = SemConfig(printCPerLine = printCPerLine),
        )
        val executor = StatementExecutor()

        init {
            // Abl.csv / Talent.csv / Exp.csv / Palam.csv 的最小同构（名字按基准 427-434 反推）
            val abl = store.registry.csvTable("ABL")
            abl.set(0, "Ｃ感覚")
            abl.set(12, "技巧")
            store.registry.csvTable("TALENT").set(2, "性別")
            store.registry.csvTable("EXP").set(5, "精験")
            val palam = store.registry.csvTable("PALAM")
            PALAM_NAMES.forEachIndexed { i, name -> palam.set(i, name) }
            store.addPseudoCharacter()
            // 角色数据（对齐基准运行：ABL 0=5、12=2；TALENT 2 非 0；PALAM 全 0）
            store.writeInt("ABL", longArrayOf(0, 0), 5)
            store.writeInt("ABL", longArrayOf(0, 12), 2)
            store.writeInt("TALENT", longArrayOf(0, 2), 2)
            // PALAMLV 1-4 = 主变量阈值（基准运行中非 0，值 0 才渲染成点条）
            store.writeInt("PALAMLV", longArrayOf(1), 100)
            store.writeInt("PALAMLV", longArrayOf(2), 500)
            store.writeInt("PALAMLV", longArrayOf(3), 3000)
            store.writeInt("PALAMLV", longArrayOf(4), 10000)
        }
    }

    private companion object {
        /** Palam.csv 0-18 号名（基准 431-434 行出现的 19 项，按序）。 */
        val PALAM_NAMES = listOf(
            "快Ｃ", "快Ｖ", "快Ａ", "快Ｂ", "快Ｍ",
            "潤滑", "恭順", "欲情", "屈服", "習得",
            "恥情", "苦痛", "恐怖", "好意", "優越",
            "反感", "不快", "抑鬱", "眠姦",
        )

        // ---- 基准字面值（pc-golden-root.txt）----

        const val GOLDEN_ABL = "Ｃ感覚LV5 技巧LV2 " // :427（尾随半角空格）
        const val GOLDEN_TALENT = "[性別]" // :428
        const val GOLDEN_MARK = " " // :429（语句产空串，控制台 flush 补空格）
        const val GOLDEN_EXP = " " // :430（同上）

        const val GOLDEN_PALAM_1 = "   快Ｃ[..........]     0   快Ｖ[..........]     0   快Ａ[..........]     0   快Ｂ[..........]     0   快Ｍ[..........]     0" // :431
        const val GOLDEN_PALAM_2 = "   潤滑[..........]     0   恭順[..........]     0   欲情[..........]     0   屈服[..........]     0   習得[..........]     0" // :432
        const val GOLDEN_PALAM_3 = "   恥情[..........]     0   苦痛[..........]     0   恐怖[..........]     0   好意[..........]     0   優越[..........]     0" // :433
        const val GOLDEN_PALAM_4 = "   反感[..........]     0   不快[..........]     0   抑鬱[..........]     0   眠姦[..........]     0" // :434
    }

    // ------------------------------------------------------------------
    // GetCharacterDataString（PRINT_ABL / TALENT / MARK / EXP 的字符串化）
    // ------------------------------------------------------------------

    @Test
    fun `ABL 行等于基准 427 字面值`() {
        val h = Harness(FakeConsoleSink())
        assertEquals(GOLDEN_ABL, PrintCharaData.characterDataString(h.ctx, 0, FunctionCode.PRINT_ABL))
    }

    @Test
    fun `TALENT 行等于基准 428 字面值`() {
        val h = Harness(FakeConsoleSink())
        assertEquals(GOLDEN_TALENT, PrintCharaData.characterDataString(h.ctx, 0, FunctionCode.PRINT_TALENT))
    }

    @Test
    fun `MARK 全 0 输出空串（基准 429 的来源）`() {
        val h = Harness(FakeConsoleSink())
        assertEquals("", PrintCharaData.characterDataString(h.ctx, 0, FunctionCode.PRINT_MARK))
    }

    @Test
    fun `EXP 无 LV 前缀`() {
        val h = Harness(FakeConsoleSink())
        h.store.writeInt("EXP", longArrayOf(0, 5), 7)
        assertEquals("精験7 ", PrintCharaData.characterDataString(h.ctx, 0, FunctionCode.PRINT_EXP))
    }

    @Test
    fun `ABL 值 0 且有名字的项同样跳过`() {
        val h = Harness(FakeConsoleSink())
        h.store.registry.csvTable("ABL").set(1, "気力")
        // 值 0 ⇒ 不出现（spec: VariableEvaluator.cs:1082）
        assertEquals(GOLDEN_ABL, PrintCharaData.characterDataString(h.ctx, 0, FunctionCode.PRINT_ABL))
    }

    @Test
    fun `角色位置越界抛官方文案`() {
        val h = Harness(FakeConsoleSink())
        val e1 = assertFailsWith<CodeEE> { PrintCharaData.characterDataString(h.ctx, 1, FunctionCode.PRINT_ABL) }
        assertEquals("试图引用一个不存在的注册角色", e1.message)
        val e2 = assertFailsWith<CodeEE> { PrintCharaData.characterParamString(h.ctx, -1, 0) }
        assertEquals("试图引用一个不存在的注册角色", e2.message)
    }

    // ------------------------------------------------------------------
    // GetCharacterParamString（PRINT_PALAM 的单元字符串化）
    // ------------------------------------------------------------------

    @Test
    fun `PALAM 值 0 渲染 10 点条并右对齐宽 6`() {
        val h = Harness(FakeConsoleSink())
        assertEquals("快Ｃ[..........]     0", PrintCharaData.characterParamString(h.ctx, 0, 0))
    }

    @Test
    fun `PALAM 跨档档字符与部分填充`() {
        val h = Harness(FakeConsoleSink())
        // 25 越过 PALAMLV 1(100)? 否 —— 直接改用落在 100-500 区间的值验证 '='
        h.store.writeInt("PALAM", longArrayOf(0, 0), 250)
        // 250 >= 100 → '='；250 >= 500? 否 → 档字符 '='、阈值 500、250*10/500 = 5 格
        assertEquals("快Ｃ[=====.....]   250", PrintCharaData.characterParamString(h.ctx, 0, 0))
    }

    @Test
    fun `PALAM 跨两档档字符升级`() {
        val h = Harness(FakeConsoleSink())
        // 600 >= 100 → '=' → 600 >= 500 → '>'；600 < 3000 → '>'、600*10/3000 = 2 格
        h.store.writeInt("PALAM", longArrayOf(0, 0), 600)
        assertEquals("快Ｃ[>>........]   600", PrintCharaData.characterParamString(h.ctx, 0, 0))
    }

    @Test
    fun `PALAM 阈值全 0 时整条档字符（cascade 到末档）`() {
        val store = VariableStore()
        val ctx = ExecContext(variables = store, eval = TestEvalContext(store))
        store.registry.csvTable("PALAM").set(0, "快Ｃ")
        store.addPseudoCharacter()
        // 全新 store 的 PALAMLV 有 SetDefaultValue 预置（[0,100,500,3000,10000,…]，
        // spec: VariableSizes.kt:13 + VariableData.cs:3485-3490）；显式清零 1-4 档才能构造
        // 阈值 0 场景：cascade 走完 mark='*'、threshold=0 → threshold<=0 分支
        for (i in 1..4) store.writeInt("PALAMLV", longArrayOf(i.toLong()), 0)
        assertEquals("快Ｃ[**********]     0", PrintCharaData.characterParamString(ctx, 0, 0))
    }

    @Test
    fun `PALAM 名空且值 0 返回 null`() {
        val h = Harness(FakeConsoleSink())
        assertNull(PrintCharaData.characterParamString(h.ctx, 0, 50))
    }

    @Test
    fun `PALAM 名空但值非 0 照常输出（名字为空串）`() {
        val h = Harness(FakeConsoleSink())
        h.store.writeInt("PALAM", longArrayOf(0, 50), 150)
        // 名字空串 + 值 150：150>=100 → '='；条形 150*10/500 = 3 格
        assertEquals("[===.......]   150", PrintCharaData.characterParamString(h.ctx, 0, 50))
    }

    // ------------------------------------------------------------------
    // 语句分派（StatementExecutor）
    // ------------------------------------------------------------------

    @Test
    fun `PRINT_ABL 语句一次 print 加 newLine`() {
        val console = FakeConsoleSink()
        val h = Harness(console)
        h.executor.execute(instr(FunctionCode.PRINT_ABL, intArg(0)), h.ctx)
        assertEquals(listOf("print(Ｃ感覚LV5 技巧LV2 )", "newLine"), console.events)
    }

    @Test
    fun `PRINT 家族 skipPrint 时整条抑制`() {
        val console = FakeConsoleSink()
        val h = Harness(console)
        h.ctx.skipPrint = true
        for (code in listOf(
            FunctionCode.PRINT_ABL,
            FunctionCode.PRINT_TALENT,
            FunctionCode.PRINT_MARK,
            FunctionCode.PRINT_EXP,
            FunctionCode.PRINT_PALAM,
        )) {
            h.executor.execute(instr(code, intArg(0)), h.ctx)
        }
        assertEquals(emptyList(), console.events)
    }

    @Test
    fun `PRINT_PALAM 语句逐字节等于基准 431-434`() {
        val console = LineConsoleSink()
        val h = Harness(console)
        h.executor.execute(instr(FunctionCode.PRINT_PALAM, intArg(0)), h.ctx)
        assertEquals(
            listOf(GOLDEN_PALAM_1, GOLDEN_PALAM_2, GOLDEN_PALAM_3, GOLDEN_PALAM_4).joinToString("\n"),
            console.capturedText(),
        )
    }

    @Test
    fun `CHARA-PRINTDATA 五语句整节等于基准 427-434`() {
        val console = LineConsoleSink()
        val h = Harness(console)
        for (code in listOf(
            FunctionCode.PRINT_ABL,
            FunctionCode.PRINT_TALENT,
            FunctionCode.PRINT_MARK,
            FunctionCode.PRINT_EXP,
            FunctionCode.PRINT_PALAM,
        )) {
            h.executor.execute(instr(code, intArg(0)), h.ctx)
        }
        assertEquals(
            listOf(
                GOLDEN_ABL,
                GOLDEN_TALENT,
                GOLDEN_MARK,
                GOLDEN_EXP,
                GOLDEN_PALAM_1,
                GOLDEN_PALAM_2,
                GOLDEN_PALAM_3,
                GOLDEN_PALAM_4,
            ).joinToString("\n"),
            console.capturedText(),
        )
    }

    @Test
    fun `PRINT_PALAM 每 PrintCPerLine 项 flush 一次`() {
        val console = FakeConsoleSink()
        val h = Harness(console, printCPerLine = 5)
        h.executor.execute(instr(FunctionCode.PRINT_PALAM, intArg(0)), h.ctx)
        val newLines = console.events.count { it == "newLine" }
        val cells = console.events.count { it.startsWith("printC(") }
        assertEquals(19, cells)
        // 15 项后的 3 次分行 + 收尾 flush（最后 4 项）= 4 次
        assertEquals(4, newLines)
    }
}
