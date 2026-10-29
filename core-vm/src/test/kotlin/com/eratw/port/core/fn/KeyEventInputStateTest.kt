package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GETKEY / GETKEYTRIGGERED / ISACTIVE 的按键后端读数（原版轮询 Win32 `GetKeyState`，
 * spec: FunctionMethodCreator.cs:7805-7839 + WinInput.cs:8；注册 :9155-9162）。
 *
 * 红 E 的判据面：`TITLE.ERB:158` 的跳出条件
 * `GETTEXTBOX() != "" || GETKEYTRIGGERED(1) || GETKEYTRIGGERED(13) || GETKEYTRIGGERED(27)`
 * 在缺省 [IdleInputState]（窗口未激活）下恒假 ⇒ 演示不可跳；接 [KeyEventInputState]
 * 后必须「按下即触发一次、按住不重复、抬起后再按再触发」。
 */
private class KeyBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("KeyBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun keyCtx(host: BuiltinHost): BuiltinEvalContext {
    val store = VariableStore()
    val base = KeyBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun keyIv(ctx: BuiltinEvalContext, name: String, vk: Long): Long =
    (ctx.callFunction(name, listOf(Value.IntV(vk))) as Value.IntV).v

private fun hostOf(state: InputState): BuiltinHost = object : BuiltinHost {
    override val inputState: InputState = state
}

class KeyEventInputStateTest {

    private val enter = 13L // VK_RETURN

    @Test
    fun `空闲态按键读数全 0`() {
        val ctx = keyCtx(hostOf(IdleInputState))
        assertEquals(0L, keyIv(ctx, "GETKEY", enter))
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", enter))
        assertEquals(0L, keyIv(ctx, "ISACTIVE"))
    }

    @Test
    fun `未激活窗口按原版先归零`() {
        // spec: FunctionMethodCreator.cs:7816-7819 —— IsActive 为假时直接返回 0，不查键态
        val keys = KeyEventInputState(activeProvider = { false })
        keys.onKeyDown(13)
        val ctx = keyCtx(hostOf(keys))
        assertEquals(0L, keyIv(ctx, "GETKEY", enter))
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", enter))
    }

    @Test
    fun `一次按下产生两次触发后归静（原版沿变形态）`() {
        // Win32 的 bit0 =「自上次 GetKeyState 之后被按下过」：按下后第一次查询带 bit0，
        // 之后回到 0。core 侧 keyStateOf 把 (keyState and 1)+1 与上次读数比较
        // （spec: FunctionMethodCreator.cs:7826-7833），于是同一次按下在
        // 「带沿变位的那一次」与「沿变位回落的那一次」各触发一次，之后静默。
        // 这是原版既有形态，照复刻（ADR-005）。
        val keys = KeyEventInputState()
        val ctx = keyCtx(hostOf(keys))
        keys.onKeyDown(13)
        assertEquals(1L, keyIv(ctx, "GETKEYTRIGGERED", enter), "第一次查询：沿变位本身")
        assertEquals(1L, keyIv(ctx, "GETKEYTRIGGERED", enter), "第二次查询：沿变位回落 2→1")
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", enter), "之后静默，按住不刷")
        assertEquals(1L, keyIv(ctx, "GETKEY", enter), "GETKEY 只看按下位（:7837）")
    }

    @Test
    fun `抬起后再按产生新一轮沿变`() {
        val keys = KeyEventInputState()
        val ctx = keyCtx(hostOf(keys))
        keys.onKeyDown(13)
        keyIv(ctx, "GETKEYTRIGGERED", enter)
        keyIv(ctx, "GETKEYTRIGGERED", enter)
        keys.onKeyUp(13)
        assertEquals(0L, keyIv(ctx, "GETKEY", enter), "抬起后按下位归零")
        keys.onKeyDown(13)
        assertEquals(1L, keyIv(ctx, "GETKEYTRIGGERED", enter), "新一轮按下必须重新触发")
    }

    @Test
    fun `两次轮询之间的短按不丢`() {
        // 原版轮询会漏掉「按下又抬起」的瞬时事件；本移植把它存记到下一次查询，
        // 触屏「提交即回车」正是这种一次性事件（漏掉就等于演示不可跳）。
        val keys = KeyEventInputState()
        val ctx = keyCtx(hostOf(keys))
        keys.onKeyDown(27)
        keys.onKeyUp(27)
        assertEquals(1L, keyIv(ctx, "GETKEYTRIGGERED", 27L))
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", 27L), "存记只兑现一次")
        assertEquals(0L, keyIv(ctx, "GETKEY", 27L), "已抬起不得谎报按住")
    }

    @Test
    fun `clear 抹掉残留按下与存记`() {
        val keys = KeyEventInputState()
        val ctx = keyCtx(hostOf(keys))
        keys.onKeyDown(13)
        keys.clear()
        assertEquals(0L, keyIv(ctx, "GETKEY", enter))
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", enter))
    }

    @Test
    fun `越界键码按原版返回 0`() {
        // spec: FunctionMethodCreator.cs:7821-7824 —— 有效域 0..255
        val keys = KeyEventInputState()
        val ctx = keyCtx(hostOf(keys))
        keys.onKeyDown(300)
        assertEquals(0L, keyIv(ctx, "GETKEYTRIGGERED", 300L))
    }

    @Test
    fun `宿主未接线输入态时按未接线报错`() {
        // BuiltinHost.inputState 缺省 null（Builtins.kt:146）
        val ctx = keyCtx(object : BuiltinHost {})
        assertFailsWith<CodeEE> { keyIv(ctx, "GETKEY", enter) }
    }

    private fun keyIv(ctx: BuiltinEvalContext, name: String): Long =
        (ctx.callFunction(name, emptyList<Value?>()) as Value.IntV).v
}
