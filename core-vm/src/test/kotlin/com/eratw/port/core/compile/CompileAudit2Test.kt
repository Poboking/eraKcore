package com.eratw.port.core.compile

import com.eratw.port.core.code.SlotAddr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审计二波 L10 落在 `core/compile` 的局部槽位不变量。
 *
 * 原版函数私有变量的登记表**只有名字一个键**（忽略大小写）：
 * `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Statements/FunctionLabelLine.cs:17`
 * `privateVar = new Dictionary<string, UserDefinedVariableToken>(Config.StrComper)`；
 * 同文件 `:98-104` 的 `AddPrivateVariable` 先 `ContainsKey(data.Name)` 就 `return false`，
 * 变量对象（`:104` 的 `CreatePrivateVariable`）根本不创建。
 *
 * 于是 [DimSpecLayout.beginFunction] 的旧写法有两处与原版相反：
 * 1. 同名第二条声明 `curSlots[base] = nextLocal++` **覆盖**前者 —— 既白占一格帧槽，
 *    又把该名字的读写挪到了后一条声明上（原版是先到先占）；
 * 2. `slots > 1` 时只登记 `NAME:i` 形态、基名不入表，`slotOf` 两级键都查不中 ⇒
 *    该私有变量被当成未声明、委托 [GlobalNameLayout] 分一个**全局槽**，
 *    直接违反本文件顶部写下的占位口径（「VarRef.indices 不参与槽编码，一律落到该变量名的
 *    单一槽上」，`LocalLayout.kt` 已知限制第 1 条）。当前 `erbcc` 恒喂 `slots = 1`
 *    （`tools/erbcc/.../bridge/Compile.kt:37`），所以 2 只在 M2 接入下标寻址时才会炸，
 *    这里先把基名登记补上并钉住。
 *
 * 另有一条**未修**的口径由测试钉住现状，接线需求见交付报告：尺寸求值只判「可解不可解」，
 * 原版还判值域 —— `UserDefinedVariableData.cs:339-341` `singleLongTerm.Int <= 0 ||
 * Int > 1000000` ⇒ `CodeEE(OoRDefinable)` ⇒ 整行不注册。该判定在 `erbcc` 的
 * `toLocalDim` 一侧（本波文件所有权之外），本层不代劳。
 */
class CompileAudit2Test {

    private fun layout(dims: Map<String, List<LocalDim>>): DimSpecLayout =
        DimSpecLayout(GlobalNameLayout(), dims)

    @Test
    fun `同名私有声明先到先占，后到的一条整条丢弃不占槽`() {
        val l = layout(mapOf("F" to listOf(LocalDim("X", false), LocalDim("X", true))))
        assertEquals(1, l.beginFunction("F", firstLocalSlot = 4), "重名只占一格（旧写法占两格）")

        // 整型位与字符串位都命中**第一条**声明的槽：原版按名字单键，类型不参与键。
        val asInt = l.slotOf("X", false)!!
        val asStr = l.slotOf("X", true)!!
        for (raw in listOf(asInt, asStr)) {
            assertFalse(SlotAddr.isGlobal(raw), "命中的是局部槽，不是全局委托")
            assertEquals(4, SlotAddr.index(raw), "槽号 = 先到那条声明的槽（旧写法给后到的 5）")
        }
        assertEquals(setOf("X"), l.currentSlots.keys, "表里只有名字单键")
        l.endFunction()
    }

    @Test
    fun `多维私有声明恒登记基名，不漏成全局槽`() {
        val l = layout(mapOf("F" to listOf(LocalDim("ARR", false, slots = 3))))
        assertEquals(3, l.beginFunction("F", firstLocalSlot = 2))

        val hit = l.slotOf("ARR", false)!!
        assertFalse(SlotAddr.isGlobal(hit), "基名必须可查（旧写法这里漏到全局槽）")
        assertEquals(2, SlotAddr.index(hit), "基名 = 第 0 个元素槽")
        assertEquals(setOf("ARR", "ARR:1", "ARR:2"), l.currentSlots.keys)
        assertEquals(listOf(2, 3, 4), l.currentSlots.values.sorted(), "逐元素槽位连续升序，供 M2 下标寻址取用")
        l.endFunction()
    }

    @Test
    fun `声明名自带美元号时两种拼写落同一局部槽`() {
        // 语料里 #DIMS 的声明名本身就带 `$`（erbcc 原样收名），使用点也写 `A$`；
        // 测试夹具则可能喂裸名。slotOf 的两级键（`NAME$` → `NAME`）保证两种写法同槽。
        val l = layout(mapOf("F" to listOf(LocalDim("LOCAL_S$", true))))
        l.beginFunction("F", 0)
        val declared = l.slotOf("LOCAL_S$", true)!!
        val bare = l.slotOf("LOCAL_S", true)!!
        assertFalse(SlotAddr.isGlobal(declared))
        assertFalse(SlotAddr.isGlobal(bare))
        assertEquals(SlotAddr.index(declared), SlotAddr.index(bare))
        l.endFunction()
    }

    @Test
    fun `未声明变量仍委托全局布局且整型字符串分表`() {
        val l = layout(mapOf("F" to listOf(LocalDim("X", false))))
        l.beginFunction("F", 0)
        val g = l.slotOf("UNDECLARED", true)!!
        assertTrue(SlotAddr.isGlobal(g), "局部表未命中 ⇒ 全局槽（占位口径不变）")
        assertEquals("G0", SlotAddr.format(g), "字符串侧另起一张全局表")
        assertEquals("G0", SlotAddr.format(l.slotOf("OTHER", false)!!), "整型侧同理从 0 起")
        l.endFunction()
    }

    @Test
    fun `尺寸求值只判可解性，值域判定不在本层（原版另有一百万上界）`() {
        val e = DimSizeEvaluator(mapOf("MAXSIZE" to 10L))
        assertEquals(0L, e.eval("0"), "0 尺寸照求出值；原版在装载侧抛 OoRDefinable（UserDefinedVariableData.cs:339-341）")
        assertEquals(-5L, e.eval("-5"), "负值同上：本层不判值域")
        assertEquals(2_000_000L, e.eval("MAXSIZE * 200000"), "> 1000000 同上")
        assertEquals(9223372036854775807L, e.eval("9223372036854775807"), "long 上界可解（SingleLongTerm 同宽）")
        assertEquals(null, e.eval("9223372036854775808"), "溢出到 long 之外 ⇒ 不可解 ⇒ 该行不注册")
        assertEquals(3L, e.eval("MAXSIZE % 7"), "取余按 C# 语义（向零取整）")
        assertEquals(-3L, e.eval("-MAXSIZE % 7"), "余数符号随被除数，与 C# 一致")
    }
}
