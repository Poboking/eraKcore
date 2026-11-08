package com.eratw.port.core.sem

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.core.host.GRAPHICS_SINK_SLOT
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.GraphicsSinkRef
import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `ExecContext.graphics` → `RuntimeAux` 的 [GRAPHICS_SINK_SLOT] 播种（CBG 推送的读面）。
 *
 * 判据：`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE` / `CBGCLEAR*` 是**内建函数**
 * （`fn/GraphicsMethods.kt`），执行语境只有 `BuiltinContext`，拿不到 `ExecContext`；
 * 它们经 aux 槽现读 sink（见 `GraphicsMethods.pushCbgLayers` 的 KDoc）。真机在
 * `GameHost` 里 `session.runtime.execContext.graphics = AndroidGraphicsSink(...)`
 * （`app/.../GameHost.kt:3614`）——**赋值那条路**，不是构造参数。
 *
 * 另一条判据是「别让纯 JVM 装置看见新槽」：`RuntimeAux.slotNames()` 参与 L3 状态哈希
 * （`core-vm/src/test/.../l3/StateHash.kt` 的 `aux:<slot>` 分簇），缺省 Noop 时不建槽。
 */
private class SeamBaseEval : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value = Value.IntV(0)
    override fun printSystemLine(message: String) {}
}

/** 只用来区分实例的最小 GraphicsSink 实现（不能是 [NoopGraphicsSink]，那条被特判）。 */
private class SeamSink : GraphicsSink {
    val pushed = ArrayList<List<CbgLayerSnapshot>>()
    override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {}
    override fun printRect(params: List<Pair<Long, Boolean>>) {}
    override fun printSpace(params: List<Pair<Long, Boolean>>) {}
    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
    override fun removeBgImage(name: String) {}
    override fun clearBgImages() {}
    override fun setCbgLayers(layers: List<CbgLayerSnapshot>) {
        pushed.add(layers)
    }
}

private fun seamContext(sink: GraphicsSink? = null): Pair<ExecContext, VariableStore> {
    val store = VariableStore()
    val eval = SeamBaseEval()
    eval.variables = store
    val ctx = if (sink == null) {
        ExecContext(store, eval)
    } else {
        ExecContext(store, eval, graphics = sink)
    }
    return ctx to store
}

class CbgSinkSeamTest {

    @Test
    fun `缺省 Noop 不建 aux 槽`() {
        val (_, store) = seamContext()
        assertNull(
            store.aux.peek<GraphicsSinkRef>(GRAPHICS_SINK_SLOT),
            "缺省 sink 不该建槽：slotNames() 参与 L3 状态哈希",
        )
    }

    @Test
    fun `构造注入真 sink 时 aux 槽指向同一实例`() {
        val sink = SeamSink()
        val (_, store) = seamContext(sink)
        assertSame(sink, store.aux.peek<GraphicsSinkRef>(GRAPHICS_SINK_SLOT)?.sink)
    }

    @Test
    fun `构造后再赋值（真机路径）原地换引用`() {
        val (ctx, store) = seamContext()
        assertNull(store.aux.peek<GraphicsSinkRef>(GRAPHICS_SINK_SLOT))
        val sink = SeamSink()
        // GameHost.kt:3614 的形状：会话建好、屏幕布局完才换真 sink。
        ctx.graphics = sink
        assertSame(sink, store.aux.peek<GraphicsSinkRef>(GRAPHICS_SINK_SLOT)?.sink)
        // 拆回 Noop：已有槽原地改，不让旧 sink 继续收推送。
        ctx.graphics = NoopGraphicsSink
        assertSame(NoopGraphicsSink, store.aux.peek<GraphicsSinkRef>(GRAPHICS_SINK_SLOT)?.sink)
    }
}
