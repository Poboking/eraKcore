package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GOTOFORM/TRYGOTOFORM 标签解析面（lane labeldollar，2026-11-04）——把三处口径钉成用例：
 *
 * 1. **`$` 前缀**：官方只在标签**定义行**剥 `$`（LogicalLineParser.cs:316-328），
 *    查表侧从不剥（LabelDictionary.cs:339-348 的键 = 剥过定义行 `$` 的名字；
 *    ReadString/StrForm 原样保留引用参数的 `$`，LexicalAnalyzer.cs:330-399/1133-1147 +
 *    ArgumentParser.cs:725-730）。⇒ 动态名 `$TAIL` 对官方与移植侧都恒 miss；
 *    静态通道 `GOTO $TAIL` 的 `trimStart('$')` 宽容是移植侧偏差（登记于 specs/isa.yaml
 *    GOTO 条目注释块，语料 0 处 `GOTO $`，不在本文件钉死）。
 * 2. **未命中屏上文案**：zhs 模板 `当前函数中不存在指定的标签名"${0}"`
 *    （lang.emuera-zhs.xml:540）**自带字面 `$`**，{0} = 求值名**原文**
 *    （FunctionIdentifier.cs:3703 传 `text`，不大写化、不剥 `$`）。真机报错簇修复点：
 *    移植侧此前漏模板 `$`（输出 `名"TAIL"` vs PC `名"$TAIL"`），本文件逐字钉死屏上原文；
 *    求值名自带 `$` 时 PC 给 `名"$$TAIL"`（双 `$`，官方形状照抄，ADR-005）。
 * 3. **作用域**：官方内层字典键 = FunctionLabelLine **对象引用**
 *    （LabelDictionary.cs:382-389 + 339-348；FunctionLabelLine.cs:13 无 Equals 覆盖），
 *    查表传 `CurrentCalled.CurrentLabel`（CalledFunction.cs:204-207）⇒ 同名标签跨函数
 *    **永不互串**，只查正在执行的那条定义。TRY 未命中 = `JumpToEndCatch`（仅 TRYC 装载期
 *    配对写入，ErbLoader.cs:1339-1358）否则**跳过本行**（:3699-3709）——不存在 `$CATCH` 标签。
 * 4. **大小写/全角**：官方比较器 = OrdinalIgnoreCase（Config.cs:33/299-308；
 *    LabelDictionary.cs:16；IgnoreCase 默认 true，ConfigData.cs:45，本游戏 emuera.config
 *    未关）= 逐字符 ASCII 折叠；移植侧 = `String.uppercase()`（全 Unicode 映射）。
 *    下面 `uppercase 折叠实测` 用最小断言**实测**（非推理）两侧差异的形状：汉字恒等、
 *    全角大写形恒等（双语料 6 条含全角拉丁标签即此形 ⇒ 同果），而全角小写/ß/ﬁ/ı 会
 *    合并官方判不同的键（移植侧多命中方向；语料 0 处可达，登记不修，理由见 isa.yaml）。
 *
 * 行号口径：CodeEE 的消息**不含行号**（位置走结构化字段，[com.eratw.port.core.err.CodeEE]
 * 的 KDoc），本文件断言逐字消息 + 结构字段各归各。
 */
class LabelResolutionFidelityTest {

    private fun ordinalIgnoreCaseEqual(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            var ca = a[i]
            var cb = b[i]
            if (ca in 'a'..'z') ca -= 32
            if (cb in 'a'..'z') cb -= 32
            if (ca != cb) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // 4. String.uppercase() 实测（任务点名：不许推理代替）
    // ------------------------------------------------------------------

    @Test
    fun `uppercase 折叠实测`() {
        // 汉字/和字：uppercase() 恒等 ⇒ 与 OrdinalIgnoreCase 同果（两侧都不动这些码位）。
        assertEquals("游戏前的选择设定", "游戏前的选择设定".uppercase())
        assertEquals("抽選終わり", "抽選終わり".uppercase())
        assertEquals("Ｍ絶頂系", "Ｍ絶頂系".uppercase()) // 语料实形（061 妹紅 M_KOJO_K61_コマンド絶頂.ERB:491）
        assertEquals("COUNTERＣ", "COUNTERＣ".uppercase()) // 语料实形（EVENT_MESSAGE_COM_セクハラ.ERB:983）
        // 全角小写拉丁：uppercase() 折叠到全角大写 ⇒ 与 OrdinalIgnoreCase（不折叠非 ASCII）分叉。
        assertEquals("ＴＡＩＬ", "ｔａｉｌ".uppercase(Locale.ROOT))
        assertTrue(!ordinalIgnoreCaseEqual("ｔａｉｌ", "ＴＡＩＬ"), "官方 Ordinal 折叠不合并全角小写/大写")
        // 德语 ß / ligature / 点线 i：全映射折叠会**扩长/换源**，Ordinal 一律不合并。
        assertEquals("SS", "ß".uppercase(Locale.ROOT))
        assertEquals("FI", "ﬁ".uppercase(Locale.ROOT))
        assertEquals("I", "ı".uppercase(Locale.ROOT))
        assertTrue(!ordinalIgnoreCaseEqual("ß", "SS") && !ordinalIgnoreCaseEqual("ﬁ", "FI"))
        // ASCII 面：两侧恒同。
        assertTrue(ordinalIgnoreCaseEqual("tail", "TAIL") && "tail".uppercase() == "TAIL")
    }

    // ------------------------------------------------------------------
    // VM 通道 `_JUMP_BY_NAME`：命中/未命中形状 + 屏上文案逐字
    // ------------------------------------------------------------------

    /** 造一个入口函数：先把名字写进 local(0)，再 `_JUMP_BY_NAME`，未跳走则打印 FALLBACK 后 _HALT。 */
    private class JumpProbe(val nameText: String, val tryBit: Int = 0) {
        val b = CodeBuilder()
        val main: Int

        init {
            main = b.declareFunction("main", 0, 0, 2)
            b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string(nameText), 0, 1)
            b.emit(Opcode._JUMP_BY_NAME, SlotAddr.local(0), tryBit, 0, 2)
            b.emit(Opcode._PRINT_LITERAL, b.string("FALLBACK"), 0, 0, 3)
            b.emit(Opcode._HALT, 0, 0, 0, 4)
        }

        /** 在本函数里补一个 `$标签`（name 由 declareLabel 大写归一）。 */
        fun label(name: String) {
            val target = b.here()
            b.emit(Opcode._PRINT_LITERAL, b.string("HIT:$name"), 0, 0, 5)
            b.emit(Opcode._HALT, 0, 0, 0, 6)
            b.declareLabel(main, name, target)
        }

        fun run(): Pair<Long, String> {
            val host = StringHost()
            val r = VirtualMachine(b.build(main), host).run()
            return r to host.output.toString()
        }

        fun runExpectCodeEE(): CodeEE =
            assertFailsWith<CodeEE> { VirtualMachine(b.build(main), StringHost()).run() }
    }

    @Test
    fun `动态未命中屏上文案含模板字面 dollar 且名字原文照登`() {
        // 官方抛点 FunctionIdentifier.cs:3703：CodeEE(format(NotDefinedLabelName, text))，
        // text = 求值名原文（未大写化）；模板 = `当前函数中不存在指定的标签名"${0}"`
        // （lang.emuera-zhs.xml:540，字面 `$` 在模板里）。
        val probe = JumpProbe("NOPE")
        val e = probe.runExpectCodeEE()
        assertEquals("当前函数中不存在指定的标签名\"\$NOPE\"", e.message)
        // 行号不参与消息（位置在结构字段，0 基下标）：
        assertEquals(2, e.line, "位置走 CodeEE.line（0 基指令行下标），不进消息")
        assertTrue("行" !in e.message.orEmpty(), "消息不含行号，实际：${e.message}")
    }

    @Test
    fun `动态未命中消息里的名字不大写化`() {
        // spec: FunctionIdentifier.cs:3696-3697/3703 —— 传 text 原文。
        val e = JumpProbe("nope").runExpectCodeEE()
        assertEquals("当前函数中不存在指定的标签名\"\$nope\"", e.message)
    }

    @Test
    fun `动态名自带 dollar 时查表恒 miss 且屏上双 dollar 照抄官方`() {
        // 官方从不剥引用参数的 `$`：键 "$TAIL" 对剥过定义行 `$` 的 "TAIL" 恒不等
        // （LabelDictionary.cs:339-348/382-389 + LogicalLineParser.cs:316-328）；
        // 模板 `$` + 原文 `$TAIL` ⇒ 屏上 `名"$$TAIL"`（双 `$` 是官方形状，ADR-005 照抄）。
        val probe = JumpProbe("\$TAIL")
        probe.label("TAIL") // 同名标签存在也不许命中
        val e = probe.runExpectCodeEE()
        assertEquals("当前函数中不存在指定的标签名\"\$\$TAIL\"", e.message)
    }

    @Test
    fun `TRY 位动态名带 dollar 未命中照旧跳过本行不报错`() {
        // spec: FunctionIdentifier.cs:3699-3709 —— isTry 且无 JumpToEndCatch（纯 TRY 系）
        // ⇒ 什么都不做，主循环 ShiftNextLine 落下一行（= 跳过本行；不存在 `$CATCH` 查找）。
        val probe = JumpProbe("\$TAIL", tryBit = Opcode.TRY_FLAG)
        probe.label("TAIL")
        val (_, out) = probe.run()
        assertEquals("FALLBACK", out, "TRY 未命中 = 顺序落到下一行，不跳标签也不抛")
    }

    @Test
    fun `ASCII 大小写两侧归一命中`() {
        // 官方 OrdinalIgnoreCase 与移植侧 uppercase() 在 ASCII 面恒同 ⇒ 钉命中形状。
        val probe = JumpProbe("tail")
        probe.label("TAIL")
        val (_, out) = probe.run()
        assertEquals("HIT:TAIL", out)
    }

    @Test
    fun `汉字与全角实形标签逐字节同形引用命中`() {
        // 双语料 6 条含全角拉丁 `$标签`（COUNTERＣ/Ｃ/Ｖ、Ｍ絶頂系）的引用都是逐字节同形
        // （如 M_KOJO_K61_コマンド絶頂.ERB:93 `GOTO Ｍ絶頂系` ↔ :491 `$Ｍ絶頂系`），
        // 两种折叠口径给同一命中。
        val p1 = JumpProbe("Ｍ絶頂系")
        p1.label("Ｍ絶頂系")
        assertEquals("HIT:Ｍ絶頂系", p1.run().second)
        val p2 = JumpProbe("抽選終わり")
        p2.label("抽選終わり")
        assertEquals("HIT:抽選終わり", p2.run().second)
    }

    @Test
    fun `全角小写引用命中是移植侧多命中登记不修`() {
        // 移植侧：declareLabel 与查表都走 uppercase()，"ｔａｉｌ"→"ＴＡＩＬ" ⇒ 命中。
        // 官方：OrdinalIgnoreCase 不折叠全角 ⇒ "ｔａｉｌ" ≠ "ＴＡＩＬ" = 未命中报错。
        // 语料 0 处此形（含全角小写引用的标签/任何 GOTOFORM 都不存在），按 isa.yaml
        // GOTO 条目注释块登记为口径差，不在本波改折叠实现（全工程键归一牵连面）。
        val probe = JumpProbe("ｔａｉｌ")
        probe.label("ＴＡＩＬ")
        assertEquals("HIT:ＴＡＩＬ", probe.run().second, "移植侧多命中形状钉死（官方此处 miss）")
    }

    @Test
    fun `同名标签跨函数不互串只查当前函数`() {
        // 官方内层字典键 = FunctionLabelLine 对象引用（LabelDictionary.cs:382-389 +
        // 339-348，FunctionLabelLine.cs:13 无 Equals 覆盖），CurrentCalled.CurrentLabel
        // （CalledFunction.cs:204-207）⇒ holder 的 `L` 对 main 不可见。
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("L"), 0, 1)
        b.emit(Opcode._JUMP_BY_NAME, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        val holderEntry = b.here()
        val holder = b.declareFunction("holder", holderEntry, 0, 2)
        b.declareLabel(holder, "L", holderEntry)
        b.emit(Opcode._PRINT_LITERAL, b.string("holder-runs"), 0, 0, 4)
        b.emit(Opcode._RET, 0, 0, 0, 5)
        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(main), StringHost()).run() }
        assertEquals("当前函数中不存在指定的标签名\"\$L\"", e.message)
    }

    // ------------------------------------------------------------------
    // LabelTable 通道（ProcessRuntime.resolveLabel / tryResolveLabel + 侧车键归一）
    // ------------------------------------------------------------------

    private class MapLabelTable(
        private val map: Map<String, Map<String, Int>>,
    ) : ProcessRuntime.LabelTable {
        override fun labelPc(functionKey: String, label: String): Int? = map[functionKey]?.get(label)
    }

    private fun runtime(): ProcessRuntime {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return ProcessRuntime(b.build(main), VariableStore(), FunctionTable(emptyList()))
    }

    @Test
    fun `resolveLabel 未定义消息与 VM 通道同口径`() {
        // 两条入口对同一 zhs 模板必须给同一屏上原文（模板字面 `$` + 名字原文）。
        val rt = runtime()
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 12))))
        val e = assertFailsWith<CodeEE> { rt.resolveLabel("FOO", "miss") }
        assertEquals("当前函数中不存在指定的标签名\"\$miss\"", e.message, "{0} = 原文不大写化（:3703）")
    }

    @Test
    fun `tryResolveLabel 对 dollar 前缀不宽容与官方同形`() {
        // 查表侧从不剥 `$`：键 "$LOOP" 对表内 "LOOP" 恒 miss。
        val rt = runtime()
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 12))))
        assertNull(rt.tryResolveLabel("FOO", "\$LOOP"))
        assertEquals(12, rt.tryResolveLabel("foo", "loop"), "ASCII 大小写归一命中")
    }

    @Test
    fun `tryResolveLabel 汉字标签名命中且函数键分域`() {
        // 汉字 uppercase() 恒等（实测见 `uppercase 折叠实测`）⇒ 表键与查键同形；
        // 同名标签分属两个函数键 ⇒ 各自命中各自的 pc（官方按定义实例分域的按键等价物）。
        val rt = runtime()
        rt.installLabelTable(
            MapLabelTable(
                mapOf(
                    "游戏前的选择设定" to mapOf("抽選終わり" to 1),
                    "SYSTEM_TITLE" to mapOf("抽選終わり" to 2),
                ),
            ),
        )
        assertEquals(1, rt.tryResolveLabel("游戏前的选择设定", "抽選終わり"))
        assertEquals(2, rt.tryResolveLabel("system_title", "抽選終わり"))
        assertNull(rt.tryResolveLabel("不存在函数", "抽選終わり"))
    }
}
