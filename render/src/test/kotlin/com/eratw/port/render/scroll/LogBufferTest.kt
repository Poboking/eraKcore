package com.eratw.port.render.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LogBuffer] 的淘汰序、行号连续性与裁剪判据，外加 `MAXLOG` 的配置解析入口。
 * 期望值一律来自 `EmueraConsole.cs` 与 `Config*.cs` 的行号口径，不是实现回读。
 *
 * ## 夹具口径（LOG-FLOOR 之后的口径）
 * 500 下限属**配置解析层**：原版 `Config.SetConfig`（spec: `Config.cs:296`）在 `Config.cs:325`
 * 读入 `MAXLOG` 原值，再在 `Config.cs:451-455` 把低于 500 的值弹框后抬成 500 —— 夹的是配置项，
 * 不是 `displayLineList` 自身的不变量（spec: `EmueraConsole.cs:2988` 只把 `Config.MaxLog` 当外来的数读）。
 * 所以 [LogBuffer] 的裸构造接受任意（正）容量，`LogBuffer(20)` 就是 20 行的环形缓冲，
 * 淘汰类用例不必再铺 500 行；要观察 500 下限与 5000 默认就改走 [LogBuffer.resolveMaxLog] /
 * [LogBuffer.fromConfig]（`LogBuffer(10)` 那类被抬升的配置值由 `fromConfig(10)` 表达）。
 * 上一版为了迁就地塞给裸构造的下限已从这里撤走，**断言语义一条没减**：
 * 只把与容量相关的字面量按新夹具重算，下限/默认/原值留档这三件事全部改由配置入口断言。
 */
class LogBufferTest {

    /** 保留区内必须取得到该行，取不到即失败（比 `!!` 好读）。 */
    private fun keep(buf: LogBuffer, index: Long) = assertNotNull(buf[index], "绝对行号 $index 应仍在保留区")

    @Test
    fun `默认上限五千时前五千行不淘汰`() {
        // spec: ConfigData.cs:62（MAXLOG 默认 5000）、EmueraConsole.cs:2988（`Count > MaxLog` 才淘汰）
        // 缺省值走配置入口：fromConfig(null) = 读不到 MAXLOG ⇒ 5000，不夹（5000 不低于 500）
        val buf = LogBuffer.fromConfig(null)
        assertEquals(5000, buf.maxLog)
        assertEquals(5000, buf.requestedMaxLog, "缺省即默认值本身，没有原值可留档")
        buf.appendRun(0, 5000)
        assertEquals(5000, buf.size)
        assertEquals(5000L, buf.totalLines)
        assertEquals(0L, buf.firstIndex)
        assertEquals(0L, buf.deletedLines)
        assertEquals("L0", marker(keep(buf, 0L)))
        assertEquals(5000L, buf.logicalLines)
    }

    @Test
    fun `第 5001 行挤掉最旧一行`() {
        // spec: EmueraConsole.cs:2988-2996 —— 追加后 Count(5001) > MaxLog(5000) ⇒ RemoveAt(0) 且 deletedLines++
        val buf = LogBuffer(5000)
        buf.appendRun(0, 5000)
        buf.append(rowOf("L5000"))
        assertEquals(5000, buf.size, "稳态保留数恰等于 maxLog")
        assertEquals(5001L, buf.totalLines)
        assertEquals(1L, buf.firstIndex)
        assertEquals(1L, buf.deletedLines)
        assertNull(buf[0L], "行号 0 已淘汰")
        assertEquals("L1", marker(keep(buf, 1L)))
        assertEquals("L5000", marker(assertNotNull(buf.last())))
        val window = buf.rows(0, 5000)
        assertEquals(5000, window.size)
        assertEquals("L1", marker(window.first()))
        assertEquals("L5000", marker(window.last()))
    }

    @Test
    fun `淘汰逐行推进且 firstIndex 单调不减`() {
        val buf = LogBuffer(5000)
        buf.appendRun(0, 5000)
        var prevFirst = buf.firstIndex
        for (i in 5000 until 5100) {
            buf.append(rowOf("L$i"))
            assertEquals((i + 1).toLong(), buf.totalLines)
            assertEquals(buf.totalLines - buf.size, buf.firstIndex)
            assertTrue(buf.firstIndex >= prevFirst, "firstIndex 必须单调不减")
            prevFirst = buf.firstIndex
            assertEquals(5000, buf.size, "一次追加最多淘汰一行")
            assertNull(buf[buf.firstIndex - 1])
            assertEquals("L$i", marker(assertNotNull(buf.last())))
            assertEquals("L${buf.firstIndex}", marker(keep(buf, buf.firstIndex)))
        }
        assertEquals(100L, buf.firstIndex)
        assertEquals(5000, buf.size)
    }

    @Test
    fun `小容量同样跨淘汰推进 firstIndex`() {
        // 裸构造不再被抬到 500 ⇒ 20 行的环形缓冲能直接观察淘汰（判据与上一条同一条 spec）
        // spec: EmueraConsole.cs:2988-2996、EmueraConsole.cs:2945+2970（行号分配）
        val buf = LogBuffer(20)
        assertEquals(20, buf.maxLog)
        buf.appendRun(0, 20)
        assertEquals(0L, buf.firstIndex, "刚填满不淘汰")
        var prevFirst = buf.firstIndex
        for (i in 20 until 40) {
            buf.append(rowOf("L$i"))
            assertEquals((i + 1).toLong(), buf.totalLines)
            assertEquals(20, buf.size, "一次追加最多淘汰一行")
            assertTrue(buf.firstIndex >= prevFirst, "firstIndex 必须单调不减")
            prevFirst = buf.firstIndex
            assertNull(buf[buf.firstIndex - 1])
            assertEquals("L$i", marker(assertNotNull(buf.last())))
            assertEquals("L${buf.firstIndex}", marker(keep(buf, buf.firstIndex)))
        }
        assertEquals(20L, buf.firstIndex, "40 行 - 20 容量")
        assertEquals(20L, buf.deletedLines)
        assertEquals(20, buf.size)
    }

    @Test
    fun `配置低于五百被抬到五百`() {
        // spec: Config.cs:451-455 —— if (MaxLog < 500) { 提示 TooSmallLogSize; MaxLog = 500; }
        // 这条政策归配置入口（LOG-FLOOR）：resolveMaxLog 夹、fromConfig 用夹后的值建缓冲。
        assertEquals(500, LogBuffer.MAX_LOG_FLOOR, "下限数值本身（spec: Config.cs:451）")
        assertEquals(5000, LogBuffer.MAX_LOG_DEFAULT, "默认值（spec: ConfigData.cs:62）")
        val requested = intArrayOf(1, 100, 499, 0, -1, Int.MIN_VALUE)
        for (r in requested) {
            val res = LogBuffer.resolveMaxLog(r)
            assertEquals(500, res.effective, "requested=$r")
            assertEquals(r, res.requested, "原值必须留档：原版按原值弹框（spec: Config.cs:453）")
            assertTrue(res.raisedToFloor, "requested=$r 低于下限，要给出提示标志")
            val buf = LogBuffer.fromConfig(r)
            assertEquals(500, buf.maxLog, "requested=$r ⇒ 生效容量 500")
            assertEquals(r, buf.requestedMaxLog, "原始配置值留作诊断（spec: Config.cs:451-455）")
        }
        // 恰等于下限不抬
        assertEquals(500, LogBuffer.resolveMaxLog(500).effective)
        assertFalse(LogBuffer.resolveMaxLog(500).raisedToFloor)
        assertEquals(500, LogBuffer.fromConfig(500).maxLog)
        // 原版只有下限、没有上限：501 与 20000 原样生效
        assertEquals(501, LogBuffer.resolveMaxLog(501).effective)
        assertEquals(501, LogBuffer.fromConfig(501).maxLog)
        assertFalse(LogBuffer.resolveMaxLog(501).raisedToFloor)
        assertEquals(20000, LogBuffer.fromConfig(20000).maxLog)
        assertFalse(LogBuffer.resolveMaxLog(20000).raisedToFloor)
        // 分析模式把 MAXLOG 强改成 10000（spec: ConfigData.cs:569-572）；配置侧改写，入口原样放行
        assertEquals(10000, LogBuffer.fromConfig(LogBuffer.MAX_LOG_ANALYSIS_MODE).maxLog)
        assertFalse(LogBuffer.resolveMaxLog(LogBuffer.MAX_LOG_ANALYSIS_MODE).raisedToFloor)
        // 缺省 = 默认 5000，且不夹
        val def = LogBuffer.resolveMaxLog(null)
        assertEquals(5000, def.requested)
        assertEquals(5000, def.effective)
        assertFalse(def.raisedToFloor)
        assertEquals(5000, LogBuffer.withDefaultMaxLog().maxLog, "withDefaultMaxLog 等价 fromConfig(null)")
    }

    @Test
    fun `抬升后的下限同样参与淘汰`() {
        // 配置写 10 ⇒ 生效 500 ⇒ 第 501 行才触发淘汰（判据同 EmueraConsole.cs:2988）
        val buf = LogBuffer.fromConfig(10)
        assertEquals(10, buf.requestedMaxLog, "原值只作诊断，不参与淘汰判据")
        assertEquals(500, buf.maxLog)
        buf.appendRun(0, 500)
        assertEquals(0L, buf.firstIndex, "未满 500 不淘汰")
        buf.append(rowOf("L500"))
        assertEquals(1L, buf.firstIndex)
        assertEquals(500, buf.size)
    }

    @Test
    fun `裸构造按传入容量生效不夹取`() {
        // LOG-FLOOR 的另一半：构造器不再读 500 下限。低于下限的值照样是合法容量。
        val caps = intArrayOf(1, 2, 3, 20, 100, 499, 500, 501, 5000, 20000, LogBuffer.MAX_LOG_ANALYSIS_MODE)
        for (c in caps) {
            val buf = LogBuffer(c)
            assertEquals(c, buf.maxLog, "capacity=$c 必须原样生效")
            assertEquals(c, buf.requestedMaxLog, "裸构造没有配置原值可留档，两者相同")
        }
        // 下限只归配置层 ⇒ 同一个 10，裸构造是 10 行、fromConfig 是 500 行
        assertEquals(10, LogBuffer(10).maxLog)
        assertEquals(500, LogBuffer.fromConfig(10).maxLog)
        // 极小容量也真的能按自身上限淘汰（spec: EmueraConsole.cs:2988 读的就是这个数）
        val one = LogBuffer(1)
        one.append(rowOf("L0"))
        one.append(rowOf("L1"))
        assertEquals(1, one.size)
        assertEquals(1L, one.firstIndex)
        assertNull(one[0L])
        assertEquals("L1", marker(assertNotNull(one.last())))
    }

    @Test
    fun `构造只校验容量为正`() {
        // 结构性前提（不是配置政策）：0 与负数让环形下标取模无意义 ⇒ 直接拒；
        // 低于 500 的正数属合法容量，交给配置入口去夹（spec: Config.cs:451-455）。
        for (bad in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException>("capacity=$bad 应在构造期被拒") { LogBuffer(bad) }
            assertTrue(e.message!!.contains("容量"), "要给出可读原因，实际：${e.message}")
        }
        // 同一个坏值经配置入口就不会抛：下限把它抬到 500
        assertEquals(500, LogBuffer.fromConfig(Int.MIN_VALUE).maxLog)
    }

    @Test
    fun `行号跨淘汰仍连续可寻址`() {
        // 原版口径：line.LineNo = lineNo 后 lineNo++（EmueraConsole.cs:2945、EmueraConsole.cs:2970）；
        // 读取时 shift = (GetLineNo > MaxLog) ? GetLineNo - MaxLog : 0（EmueraConsole.cs:1865-1868、EmueraConsole.cs:2402）。
        // 夹具：容量 20（裸构造不再被抬到 500），70 行 ⇒ 淘汰 50 行。
        val buf = LogBuffer(20)
        buf.appendRun(0, 70)
        assertEquals(70L, buf.totalLines)
        assertEquals(50L, buf.firstIndex, "70 - 20")
        assertEquals(20, buf.size)
        assertNull(buf[49L])
        assertEquals("L50", marker(keep(buf, 50L)))
        assertEquals("L69", marker(keep(buf, 69L)))
        assertNull(buf[70L], "最新行的下一号不存在")
        val shiftFromCs = if (buf.totalLines > buf.maxLog.toLong()) buf.totalLines - buf.maxLog else 0L
        assertEquals(shiftFromCs, buf.firstIndex, "与原版 shift 逐项相等")
    }

    @Test
    fun `rows 两端越界都裁剪`() {
        // 夹具：容量 20（裸构造按传入值），追加 40 行 ⇒ firstIndex 20，可用行号 20..39。
        val buf = LogBuffer(20)
        buf.appendRun(0, 40)
        assertEquals(20L, buf.firstIndex)
        val fromZero = buf.rows(0, 10)
        assertEquals(10, fromZero.size, "起点抬到 firstIndex，终点不越界")
        assertEquals("L20", marker(fromZero.first()))
        assertEquals("L29", marker(fromZero.last()))
        val tail = buf.rows(39, 10)
        assertEquals(1, tail.size)
        assertEquals("L39", marker(tail.first()))
        assertTrue(buf.rows(40, 10).isEmpty(), "起点等于 totalLines ⇒ 空")
        val negative = buf.rows(-5, 3)
        assertEquals(3, negative.size, "负起点被抬到 firstIndex 而不是报错")
        assertEquals("L20", marker(negative.first()))
        assertTrue(buf.rows(20, 0).isEmpty())
        assertTrue(buf.rows(20, -1).isEmpty())
        assertEquals(20, buf.rows(20, 9999).size, "count 超界截到保留行数")
        val all = buf.all()
        assertEquals(20, all.size)
        assertEquals("L20", marker(all.first()))
        assertEquals("L39", marker(all.last()))
    }

    @Test
    fun `空缓冲读法安全`() {
        val buf = LogBuffer(20)
        assertEquals(0, buf.size)
        assertEquals(0L, buf.totalLines)
        assertEquals(0L, buf.firstIndex)
        assertTrue(buf.rows(0, 5).isEmpty())
        assertTrue(buf.all().isEmpty())
        assertNull(buf.last())
        assertNull(buf[0L])
        assertFalse(buf.removeLast())
        assertFalse(buf.replaceLast(rowOf("L0")))
    }

    @Test
    fun `逻辑行只在首行且行末时计数`() {
        // spec: EmueraConsole.cs:2971-2978 —— 刚入列的行同时满足 IsLogicalLine 与 IsLineEnd 才 ++；
        // 折行只有首行带 isLogicalLine（PrintStringBuffer.cs:193、PrintStringBuffer.cs:206），
        // 中间行 IsLineEnd 取默认 true（ConsoleDisplayLine.cs:16），
        // IsLineEnd 只在整批 flush 的末行被改写（PrintStringBuffer.cs:183）。
        val buf = LogBuffer(20)
        buf.append(rowOf("A1", logical = true, lineEnd = true))
        buf.append(rowOf("A2", logical = false, lineEnd = true))
        buf.append(rowOf("A3", logical = false, lineEnd = true))
        assertEquals(3L, buf.totalLines, "折出的每一行各占一个行号")
        assertEquals(1L, buf.logicalLines, "三行仍是一个逻辑行")
        buf.append(rowOf("B1", logical = true, lineEnd = false))
        assertEquals(1L, buf.logicalLines, "PRINTN 未结束行不计")
        assertEquals(4L, buf.totalLines)
    }

    @Test
    fun `PRINTN 未结束行原样存储并由上层并入`() {
        // 原版并入：末行 IsLineEnd == false ⇒ deleteLine(1) + ShiftPositionX + ChangeStr 后重新入列
        //（EmueraConsole.cs:2947-2968、EmueraConsole.cs:2999-3051）。本路只给原语，不碰行内容。
        val buf = LogBuffer(20)
        buf.append(rowOf("PRINTN片段", logical = true, lineEnd = false))
        assertEquals("PRINTN片段", marker(assertNotNull(buf.last())), "未结束行按整行原样保存")
        assertEquals(0L, buf.logicalLines)
        assertTrue(buf.replaceLast(rowOf("PRINTN片段并入后续", logical = true, lineEnd = true)))
        assertEquals(1L, buf.totalLines, "并入后行号不变（deleteLine 让出、addDisplayLine 复用）")
        assertEquals(0L, buf.firstIndex)
        assertEquals("PRINTN片段并入后续", marker(assertNotNull(buf.last())))
        assertEquals(1L, buf.logicalLines, "并入后闭合一个逻辑行")
        assertEquals(1, buf.size)
    }

    @Test
    fun `removeLast 让出末行但不动 firstIndex`() {
        // spec: EmueraConsole.cs:3007-3021 —— 末行出列、lineNo--，
        // 被删行若 IsLogicalLine 且 IsLineEnd 则 logicalLineCount--。
        // 夹具：容量 20（裸构造按传入值），追加 50 行 ⇒ firstIndex 30，有 30 行淘汰可观察。
        val buf = LogBuffer(20)
        buf.appendRun(0, 50) // firstIndex 30
        assertEquals(30L, buf.firstIndex)
        assertTrue(buf.removeLast())
        assertEquals(49L, buf.totalLines)
        assertEquals(19, buf.size)
        assertEquals(30L, buf.firstIndex, "删尾行不影响最旧行号，单调性保持")
        assertEquals(30L, buf.deletedLines)
        assertNull(buf[49L], "让出的行号可被下一次 append 复用")
        assertEquals("L48", marker(assertNotNull(buf.last())))
        assertEquals(49L, buf.logicalLines)
        buf.append(rowOf("L49"))
        assertEquals(50L, buf.totalLines)
        assertEquals(30L, buf.firstIndex)
        assertEquals("L49", marker(keep(buf, 49L)))
    }

    @Test
    fun `removeLast 按逻辑行整条出列`() {
        // spec: EmueraConsole.cs:3007-3021 —— deleteLine(1) 从尾部逐**显示行**出列，
        // `num` 只在删到 IsLogicalLine == true 的首排时 +1，因此在首排处收口：
        // 折成多排的逻辑行整条出列。每排各 `lineNo--`（:3013），
        // logicalLineCount 只回退首排那一次（:3014-3020）。
        // 夹具按 LineFolder 的折行形态造：首排 logical+lineEnd，末排 logical=false+lineEnd=false
        // （PrintStringBuffer.cs:193,206 的首行标记 + :183 只改写末排的 IsLineEnd）。
        val buf = LogBuffer(20)
        buf.append(rowOf("あああああ", logical = true, lineEnd = true))
        buf.append(rowOf("あ", logical = false, lineEnd = false))
        assertEquals(2L, buf.totalLines)
        assertEquals(1L, buf.logicalLines, "首排 logical+lineEnd 已计过一个逻辑行")
        assertTrue(buf.removeLast())
        assertEquals(0, buf.size, "两排显示行都出列，不许只删末排")
        assertEquals(0L, buf.totalLines, "lineNo 逐排回退（:3013）")
        assertEquals(0L, buf.logicalLines, "logicalLineCount 只回退首排那一次（:3014-3020）")
        assertEquals(0L, buf.firstIndex, "totalLines 与 size 同步回退，firstIndex 不动")
    }

    @Test
    fun `clear 后计数归零并重新从零编号`() {
        // spec: EmueraConsole.cs:2801-2813 —— displayLineList.Clear()、logicalLineCount=0、
        // deletedLines=0、lineNo=0、lastDrawnLineNo=-1、verticalScrollBarUpdate()。
        // 夹具：容量 20（裸构造按传入值），追加 50 行使 firstIndex 停在 30。
        val buf = LogBuffer(20)
        buf.appendRun(0, 50)
        assertEquals(30L, buf.firstIndex)
        buf.clear()
        assertEquals(0, buf.size)
        assertEquals(0L, buf.totalLines)
        assertEquals(0L, buf.firstIndex)
        assertEquals(0L, buf.logicalLines)
        assertTrue(buf.all().isEmpty())
        assertNull(buf[30L], "清空后旧行号一律失效（清空前它是最旧保留行）")
        assertNull(buf[49L], "清空前最新一行的行号同样失效")
        buf.append(rowOf("N0"))
        assertEquals(0L, buf.firstIndex)
        assertEquals(1L, buf.totalLines)
        assertEquals("N0", marker(keep(buf, 0L)), "ClearDisplay 之后 lineNo 从 0 重新起算")
    }
}
