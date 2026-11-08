package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [g9] [VarWriteProbe] 的**浮点通道**：`onWriteFloat` 能在调试/探针面观测浮点变量写入。
 *
 * 挂点与整数/字符串支逐字同形：写入**入口、校验前**（[VariableStore.writeFloat] →
 * `writeFloatEntry` 的守卫位），所以校验失败的写入也发事件、`value` = 请求值。
 *
 * 为什么单开一个通道而不是把 double 折进整数通道：`toRawBits()` 会让调试器显示一个
 * 看不懂的巨大整数，比不报更糟（这一条是改造前 KDoc 里写明「本轮不接」的理由；
 * 接的方式 = 接口第三个**默认空体**方法 ⇒ app / vmdbg 的既有实现方一行不改也照常编译）。
 *
 * 浮点本身是 4.981 fork 的第三型（spec: fork-src VariableCode.cs:99-101,124；
 * VariableDescriptor.cs:108,154-155），1.824 完全没有 ⇒ 浮点侧 TODO(verify)；
 * 每个用例都配 1.824 侧的整数/字符串对照，钉住那两个通道的行为逐字未变。
 */
class VarWriteProbeFloatTest {

    /** 记录型探针：三个通道各自记一条 `name|下标|值`。 */
    private class Recorder(vararg watchNames: String) : VarWriteProbe {
        private val watch = watchNames.toHashSet()
        val ints = ArrayList<String>()
        val strs = ArrayList<String>()
        val floats = ArrayList<String>()

        override fun watches(name: String): Boolean = name in watch

        override fun onWriteInt(name: String, indices: LongArray, value: Long) {
            ints += "$name|${indices.joinToString(",")}|$value"
        }

        override fun onWriteStr(name: String, indices: LongArray, value: String) {
            strs += "$name|${indices.joinToString(",")}|$value"
        }

        override fun onWriteFloat(name: String, indices: LongArray, value: Double) {
            floats += "$name|${indices.joinToString(",")}|$value"
        }
    }

    private fun storeWithProbe(p: VarWriteProbe): VariableStore =
        VariableStore().also { it.writeProbe = p }

    // ------------------------------------------------------------------
    // 浮点通道
    // ------------------------------------------------------------------

    /**
     * `RESULTF`（0 维）与 `LOCALF`（每函数 1 维）的写都发事件，且带**原拼写**与下标。
     * TODO(verify)：1.824 无浮点写可对照，只对照整数/字符串两通道未变。
     */
    @Test
    fun `浮点写发事件`() {
        val rec = Recorder("RESULTF", "LOCALF")
        val store = storeWithProbe(rec)
        store.writeFloat("RESULTF", longArrayOf(), 2.5)
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(3), 1.25)
        } finally {
            store.leaveFunction()
        }
        assertEquals(listOf("RESULTF||2.5", "LOCALF|3|1.25"), rec.floats, "两个浮点名都要出事件")
        assertTrue(rec.ints.isEmpty() && rec.strs.isEmpty(), "浮点写不许串进整数/字符串通道：${rec.ints}${rec.strs}")
    }

    /** 名单未命中 ⇒ 零回调；命中浮点名也不影响同名整型监视（名单按名字判定，与类型无关）。 */
    @Test
    fun `名单判定与整数通道互不串台`() {
        val rec = Recorder("LOCALF", "FLAG")
        val store = storeWithProbe(rec)
        store.writeInt("FLAG", longArrayOf(0), 7L) // 1.824 对照：整数通道照旧
        store.enterFunction("T")
        try {
            store.writeFloat("ARGF", longArrayOf(0), 1.0) // 未监视 ⇒ 不回调
            store.writeFloat("LOCALF", longArrayOf(0), 1.0)
        } finally {
            store.leaveFunction()
        }
        assertEquals(1, rec.floats.size, "只有监视名单里的浮点名出事件：${rec.floats}")
        assertEquals(listOf("FLAG|0|7"), rec.ints, "整数通道逐字不变")
    }

    /**
     * 挂点在**校验前**：下标越界的浮点写也先出事件，随后才抛 CodeEE
     * （与 `writeIntEntry` 的既有口径同形，1.824 侧已验收）。
     */
    @Test
    fun `校验失败的浮点写也发事件`() {
        val rec = Recorder("LOCALF")
        val store = storeWithProbe(rec)
        store.enterFunction("T")
        try {
            assertFailsWith<CodeEE> { store.writeFloat("LOCALF", longArrayOf(99), 5.0) }
            assertEquals(listOf("LOCALF|99|5.0"), rec.floats, "事件在入口，value = 请求值")
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：整数侧同一形态
        val rec2 = Recorder("LOCAL")
        val store2 = storeWithProbe(rec2)
        store2.enterFunction("T")
        try {
            assertFailsWith<CodeEE> { store2.writeInt("LOCAL", longArrayOf(99999), 5L) }
            assertEquals(listOf("LOCAL|99999|5"), rec2.ints)
        } finally {
            store2.leaveFunction()
        }
    }

    /** 复合赋值（`+=` / `++` 走的 `plusFloat` → `writeFloatEntry`）同样出事件。 */
    @Test
    fun `浮点增量也发事件`() {
        val rec = Recorder("RATE", "LOCALF")
        val store = storeWithProbe(rec)
        store.declareUserVar(DimParser.parse("#DIMF", "RATE, 2 = 1.0", false))
        store.plusFloat("RATE", longArrayOf(1), 0.5)
        store.enterFunction("T")
        try {
            store.plusFloat("LOCALF", longArrayOf(0), 2.0)
        } finally {
            store.leaveFunction()
        }
        assertEquals(listOf("RATE|1|1.5", "LOCALF|0|2.0"), rec.floats, "用户 #DIMF 与系统浮点局部都走同一条写通道")
        assertEquals(1.5, store.readFloat("RATE", longArrayOf(1)))
        // 1.824 对照：整数 `+=` 走系统变量分支时同样出事件（plusIntEntry 的定义支复用 writeIntEntry）
        val rec2 = Recorder("FLAG")
        val store2 = storeWithProbe(rec2)
        store2.plusInt("FLAG", longArrayOf(1), 3L)
        assertEquals(listOf("FLAG|1|3"), rec2.ints)
        // 既有的一次**两型不对称**（本路原样保留，不顺手改）：plusInt 的用户变量支直接落
        // `e.writeInt`，不经 writeIntEntry ⇒ 不发事件；浮点支没有这条内联，照样经 writeFloatEntry。
        val rec3 = Recorder("CNT")
        val store3 = storeWithProbe(rec3)
        store3.declareUserVar(DimParser.parse("#DIM", "CNT, 2", false))
        store3.plusInt("CNT", longArrayOf(1), 3L)
        assertTrue(rec3.ints.isEmpty(), "整数用户变量的 += 今天不出事件（改造前后同值）")
        assertEquals(3L, store3.readInt("CNT", longArrayOf(1)), "只是不发事件，写入语义照常")
    }

    /** 批量赋值（[VariableStore.setValues] 的浮点形态）逐元素出事件，与标量写同一函数体。 */
    @Test
    fun `浮点数组写逐元素发事件`() {
        val rec = Recorder("RATE")
        val store = storeWithProbe(rec)
        store.declareUserVar(DimParser.parse("#DIMF", "RATE, 3", false))
        store.setValues("RATE", longArrayOf(0), doubleArrayOf(1.0, 2.5, 3.5))
        assertEquals(listOf("RATE|0|1.0", "RATE|1|2.5", "RATE|2|3.5"), rec.floats)
        assertEquals(2.5, store.readFloat("RATE", longArrayOf(1)))
    }

    // ------------------------------------------------------------------
    // 兼容性：既有实现方（只实现两个通道）不改一行仍可用
    // ------------------------------------------------------------------

    /**
     * 只覆写 `watches` + `onWriteInt` 的既有形态实现方：写浮点不炸、不发浮点事件
     * （`onWriteFloat` 是**默认空体** ⇒ app/DevState.kt 与 tools/vmdbg 的实现方无需改动）。
     */
    @Test
    fun `既有实现方不改一行照常编译且不崩`() {
        val seen = ArrayList<String>()
        val legacy = object : VarWriteProbe {
            override fun watches(name: String): Boolean = name == "LOCALF" || name == "FLAG"

            override fun onWriteInt(name: String, indices: LongArray, value: Long) {
                seen += name
            }
        }
        val store = storeWithProbe(legacy)
        store.writeInt("FLAG", longArrayOf(0), 1L)
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(0), 9.5)
            assertEquals(9.5, store.readFloat("LOCALF", longArrayOf(0)), "浮点写入语义不受探针影响")
        } finally {
            store.leaveFunction()
        }
        assertEquals(listOf("FLAG"), seen, "整数事件照旧，浮点事件被默认空体吞掉")
    }

    /** 探针实现抛异常 ⇒ 被 runCatching 吞弃，不反噬写入（与整数/字符串支同判据）。 */
    @Test
    fun `探针异常不反噬浮点写入`() {
        val boom = object : VarWriteProbe {
            override fun watches(name: String): Boolean = true

            override fun onWriteFloat(name: String, indices: LongArray, value: Double) {
                throw IllegalStateException("探针炸了")
            }
        }
        val store = storeWithProbe(boom)
        store.writeFloat("RESULTF", longArrayOf(), 4.0)
        assertEquals(4.0, store.readFloat("RESULTF", longArrayOf()), "写入照常落库")
        // 1.824 对照：整数支同一判据
        val store2 = storeWithProbe(object : VarWriteProbe {
            override fun watches(name: String): Boolean = true

            override fun onWriteInt(name: String, indices: LongArray, value: Long) {
                throw IllegalStateException("探针炸了")
            }
        })
        store2.writeInt("FLAG", longArrayOf(0), 3L)
        assertEquals(3L, store2.readInt("FLAG", longArrayOf(0)))
    }

    /** 探针为 null（生产缺省）⇒ 写浮点零行为变化。 */
    @Test
    fun `无探针时浮点写不受影响`() {
        val store = VariableStore()
        store.writeFloat("RESULTF", longArrayOf(), 1.5)
        assertEquals(1.5, store.readFloat("RESULTF", longArrayOf()))
        // 0 维浮点没有数组形态（与改造前同口径，探针改动不碰这条）
        assertFailsWith<CodeEE> { store.setValues("RESULTF", longArrayOf(0), doubleArrayOf(1.0)) }
        // 1.824 对照：整数侧同形
        store.writeInt("FLAG", longArrayOf(0), 2L)
        assertEquals(2L, store.readInt("FLAG", longArrayOf(0)))
    }
}
