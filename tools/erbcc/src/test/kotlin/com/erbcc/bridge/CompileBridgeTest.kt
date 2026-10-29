package com.erbcc.bridge

import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.LocalDim
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.erbcc.DimKeyword
import com.eratw.port.erbcc.DimSpec
import com.eratw.port.erbcc.LabelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * erbcc → core-vm 桥的单测：dimSpecs → LocalDim 映射（D.8 语义）与布局端到端。
 */
class CompileBridgeTest {

    private fun spec(
        name: String,
        isStr: Boolean = false,
        dims: List<String> = listOf("1"),
        keywords: Set<DimKeyword> = emptySet(),
        init: String? = null,
    ) = DimSpec(name, isStr, dims, keywords, init)

    private fun def(
        key: String,
        isEvent: Boolean = false,
        defIndex: Int = 0,
        specs: List<DimSpec> = emptyList(),
    ) = LabelDef(
        name = key, key = key, file = "t.erb", lineNo = 1, fileIndex = 1, defIndex = defIndex,
        isEvent = isEvent, isSystem = false, isMethod = false, isOnly = false, isPri = false,
        isLater = false, isSingle = false, dimSpecs = specs,
    )

    // ---- toLocalDim -------------------------------------------------------

    @Test
    fun `普通 DIM 映射为局部槽声明`() {
        val d = spec("LOCAL_X", dims = listOf("3")).toLocalDim()
        // L08 收口后 slots = 各维元素乘积（UserDefinedVariableData.cs:325-328），不再恒 1。
        assertEquals(LocalDim("LOCAL_X", false, slots = 3, ref = false), d)
    }

    @Test
    fun `DIMS 映射 isStr`() {
        val d = spec("LOCAL_S", isStr = true).toLocalDim()
        assertEquals(true, d!!.isStr)
    }

    @Test
    fun `全局侧关键字不进局部表`() {
        for (kw in setOf(DimKeyword.GLOBAL, DimKeyword.SAVEDATA, DimKeyword.CHARADATA)) {
            assertNull(spec("GV", keywords = setOf(kw)).toLocalDim(), "$kw 必须走全局声明")
        }
    }

    @Test
    fun `REF 尺寸必须为 0 且标记 ref`() {
        // CanNotSizedRef（UserDefinedVariableData.cs:329-335）：REF 每段只许空/0，非 0 一律丢弃。
        assertEquals(
            LocalDim("R", false, slots = 1, ref = true),
            spec("R", dims = listOf("", ""), keywords = setOf(DimKeyword.REF)).toLocalDim(),
            "REF 空段 ⇒ slots=1、ref=true",
        )
        assertNull(
            spec("R", dims = listOf("5"), keywords = setOf(DimKeyword.REF)).toLocalDim(),
            "REF 显式尺寸 5 违 CanNotSizedRef ⇒ 不收集",
        )
        assertNull(
            spec("R", dims = listOf("A+B", "UNKNOWN"), keywords = setOf(DimKeyword.REF)).toLocalDim(consts = emptyMap()),
            "REF 非空段同样违 CanNotSizedRef ⇒ 不收集",
        )
    }

    @Test
    fun `尺寸不可解该行不收集`() {
        assertNull(spec("A", dims = listOf("MAXSIZE")).toLocalDim(consts = emptyMap()), "常量缺失 → 原版该行不注册")
        assertNull(spec("A", dims = listOf("1", "@")).toLocalDim())
        assertEquals(5, spec("A", dims = listOf("MAXSIZE")).toLocalDim(consts = mapOf("MAXSIZE" to 5L))!!.slots, "常量可解即收集，slots=元素数")
    }

    // ---- buildFuncDims ----------------------------------------------------

    @Test
    fun `非事件重名由调用方按胜者喂入`() {
        // buildFuncDims 按 def 逐条映射（含事件组）；非事件重名的胜者过滤是调用方职责
        //（DuplicateGroup.winner = 最后装载者，specs/loader.md D.8.2）——这里锁映射纯度
        val winner = def("F", specs = listOf(spec("WINNER_DIM")))
        val dims = buildFuncDims(listOf(winner))
        assertEquals(listOf(LocalDim("WINNER_DIM", false)), dims["F"])
    }

    @Test
    fun `事件成员带 defIndex 键`() {
        val e1 = def("EV", isEvent = true, defIndex = 0, specs = listOf(spec("L1")))
        val e2 = def("EV", isEvent = true, defIndex = 1, specs = listOf(spec("L2")))
        val dims = buildFuncDims(listOf(e1, e2))
        assertEquals(listOf(LocalDim("L1", false)), dims["EV#0"])
        assertEquals(listOf(LocalDim("L2", false)), dims["EV#1"])
    }

    // ---- sizeConsts：宏/ERH 常量 → 尺寸求值常量表（erbcc 只收原文，求值在此）----

    private fun konst(name: String, init: String, isStr: Boolean = false) =
        spec(name, isStr = isStr, dims = emptyList(), keywords = setOf(DimKeyword.CONST), init = init)

    @Test
    fun `宏尺寸经 sizeConsts 求值后注册`() {
        val macros = mapOf(
            "MAXSIZE" to "300",                 // 纯整数体
            "CHAIN" to "MAXSIZE",               // 宏→宏（定点迭代，≙ ErhLoader.cs:249-340 重试队列）
            "EXPR" to "1 + 2",                  // 表达式形态（DimSizeEvaluator 已支持 → 打通）
            "VARREF" to "TIME:2",               // 变量引用 → 不可求值，剔除
            "STR" to "\"文字列\"",               // 字符串体 → 剔除
            "FLAG" to "",                       // 无替换式宏 → 剔除
            "SELF" to "SELF + 1",               // 自参照 → 永远不可解，剔除且不报错
        )
        val consts = sizeConsts(macros, emptyList())
        assertEquals(mapOf("MAXSIZE" to 300L, "CHAIN" to 300L, "EXPR" to 3L), consts)
        // `#DIM A 宏名`：宏可解 → 注册；不可解 → 按 D.8 不收集（null，不抛错）
        assertNotNull(spec("A", dims = listOf("CHAIN")).toLocalDim(consts))
        assertNotNull(spec("A", dims = listOf("EXPR*2")).toLocalDim(consts))
        assertNull(spec("A", dims = listOf("VARREF")).toLocalDim(consts), "不可求值仍不收集且不报错")
        assertNull(spec("A", dims = listOf("SELF")).toLocalDim(consts))
    }

    @Test
    fun `ERH DIM CONST 接入尺寸常量表`() {
        val consts = sizeConsts(
            emptyMap(),
            listOf(
                konst("人物数量上限", "200"),
                konst("EXPR_C", "1 + 2"),
                konst("ARR", "0, 100, 500"),        // 多元素：裸名 = 元素 0（spec: VariableTerm.cs:337-340）
                konst("HEX", "0xD5DEDF"),           // 十六进制面值（spec: LexicalAnalyzer.cs:36-45）→ 纳入
                konst("S", "文字列", isStr = true), // 字符串 CONST 不进整型表
                konst("CYCLE", "CYCLE_B"),
                konst("CYCLE_B", "CYCLE"),          // 互参照循环 → 双双不纳入、不报错
            ),
        )
        assertEquals(
            mapOf("人物数量上限" to 200L, "EXPR_C" to 3L, "ARR" to 0L, "HEX" to 0xD5DEDFL),
            consts,
        )
        assertNotNull(spec("A", dims = listOf("人物数量上限", "3")).toLocalDim(consts))
        // HEX = 0xD5DEDF = 14,016,223 > 1_000_000 ⇒ OoRDefinable 丢弃（UserDefinedVariableData.cs:337-340）
        assertNull(spec("A", dims = listOf("HEX")).toLocalDim(consts), "HEX 超 1e6 上限 ⇒ 不收集")
        assertNotNull(spec("A", dims = listOf("0x10")).toLocalDim(emptyMap()), "0x 字面量可作尺寸（值在域内）")
        assertNull(spec("A", dims = listOf("BAD_HEX")).toLocalDim(consts), "未知标识符仍不收集")
    }

    @Test
    fun `macroConsts 兼容入口委托 sizeConsts`() {
        // 原「纯整数才纳入」升级为 DimSizeEvaluator 表达式口径（超集），不可解者照旧剔除
        assertEquals(mapOf("A" to 5L, "B" to 7L), macroConsts(mapOf("A" to "5", "B" to "1+2+4", "C" to "X")))
    }

    // ---- 全语料：收集前后「因尺寸不可求值而丢弃的声明数」对比（ERATW_CORPUS 缺失则 skip）----

    @Test
    fun `全语料 宏与常量收集恢复被丢弃的尺寸声明`() {
        val raw = System.getenv("ERATW_CORPUS") ?: System.getProperty("eratw.corpus")
        org.junit.jupiter.api.Assumptions.assumeTrue(!raw.isNullOrBlank(), "未提供语料路径，跳过全量对比")
        val corpus = java.nio.file.Path.of(raw!!)
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(corpus), "语料目录不存在: $corpus")
        val table = com.eratw.port.erbcc.SymbolTable.build(corpus)
        val consts = sizeConsts(table)
        var dropBefore = 0
        var dropAfter = 0
        var total = 0
        for (key in table.nameKeys) {
            for (d in table.defsOf(key)) {
                if (d.file.startsWith("GOLDEN_")) continue   // E2E 复制件不入基线（同 SymbolsTest 口径）
                for (s in d.dimSpecs) {
                    total++
                    if (s.droppedByUnresolvableDim(emptyMap())) dropBefore++
                    if (s.droppedByUnresolvableDim(consts)) dropAfter++
                }
            }
        }
        println("erbcc 全语料: 函数体 dimSpecs $total 条；尺寸不可求值丢弃 收集前 $dropBefore → 收集后 $dropAfter")
        // 宏/常量收集基线（语料 = 4.980 + fromEN 合集，ERH 全集扫描）
        assertEquals(22, table.config.macros.size, "ERH #DEFINE 宏（名→展开体）总数")
        assertTrue(dropAfter <= dropBefore, "收集常量表只会恢复注册，不得增加丢弃")
    }

    // ---- 端到端：erbcc dimSpecs → core-vm 布局 ------------------------------

    @Test
    fun `dimSpecs 经布局进帧内局部槽`() {
        // 端到端：erbcc dimSpecs → buildFuncDims → core-vm DimSpecLayout 的**局部槽布局**。
        // （契约 C1/C3 后赋值语句改走语句池 marker，编译器不再发 `_SET_INT`，本用例因此
        // 直接驱动布局接口验证「私有 #DIM 落帧内局部槽、表外名回落全局占位槽」——
        // 这正是 erbcc 侧数据经桥进入 core-vm 布局的部分，编译器发射归 A3。）
        val dims = buildFuncDims(listOf(def("F", specs = listOf(spec("LOCAL_X"), spec("LOCAL_S", isStr = true)))))
        val layout = DimSpecLayout(GlobalNameLayout(), dims)
        val used = layout.beginFunction("F", firstLocalSlot = 8)
        assertEquals(2, used, "两条私有声明各占 1 局部槽（当前 slots=1 占位口径）")
        val raw = layout.slotOf("LOCAL_X", false)
        assertNotNull(raw, "私有变量应命中局部表")
        assertTrue(!SlotAddr.isGlobal(raw!!) && !SlotAddr.isNone(raw), "dimSpecs 私有变量落帧内局部槽（实际 ${SlotAddr.format(raw)}）")
        assertEquals(SlotAddr.local(8), raw, "局部槽从 firstLocalSlot 起分配")
        val sraw = layout.slotOf("LOCAL_S", true)
        assertEquals(SlotAddr.local(9), sraw, "DIMS 同名表内第二槽")
        val g = layout.slotOf("OTHER_GLOBAL_V", false)
        assertNotNull(g)
        assertTrue(SlotAddr.isGlobal(g), "局部表未命中 → 全局占位布局")
        layout.endFunction()
    }
}
