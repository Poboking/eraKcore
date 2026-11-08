package com.eratw.port.core.fn

import com.eratw.port.core.host.AndroidKeyCodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 执行期点击意图槽的读点语义（[KeyEventInputState] 类文档「执行期点击意图」节）。
 *
 * 五件事各锁一条判据：
 * 1. 镜像被覆写（`SETTEXTBOX MOUSEB()` 的空串）时，VK_RETURN 读点把意图补写回去；
 * 2. 镜像仍握着意图时不做任何写（否则下一轮轮询会拿到第二次触发）；
 * 3. VK_LBUTTON 读点：镜像握着意图 ⇒ 只作废（左键支的 MOUSEB 已消费）；镜像被覆写 ⇒
 *    保留给回车支补写（左键支读 MOUSEB、不读镜像，此处清槽会吞掉兜底）；
 * 4. 空落点不入槽（不携带信息，补写只会清掉脚本状态串）；
 * 5. 新提交与 `clear()` 会作废待交付意图。
 */
class KeyEventInputStateClickIntentTest {

    /** 可观测的镜像端口：记录写入次数，读取当前值。 */
    private class RecordingMirror(initial: String = "") : InputMirrorPort {
        var value: String = initial
        var writes: Int = 0

        override fun read(): String = value

        override fun write(text: String) {
            value = text
            writes++
        }
    }

    /** 一次「按下并立即抬起」= `GameHost.fireVirtualKeyEdge`。 */
    private fun edge(state: KeyEventInputState, vk: Int) {
        state.onKeyDown(vk)
        state.onKeyUp(vk)
    }

    @Test
    fun `镜像被覆写时回车读点补写点击意图`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_RETURN)
        state.noteClickIntent("500")

        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN), "短按存记应报 0x8001")
        assertEquals("500", mirror.value, "镜像被 SETTEXTBOX 覆写后，回车读点应把点击补写回去")
        assertEquals(1, mirror.writes, "一笔意图只补写一次")
        assertEquals(0, state.keyState(AndroidKeyCodes.VK_RETURN), "沿变一次性，补写不应制造第二次触发")
    }

    @Test
    fun `镜像仍握着意图时回车读点不重写`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "500")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_RETURN)
        state.noteClickIntent("500")

        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes, "镜像已握着同一份文本时不写：再写会让下一轮轮询再吃一次命令")
        assertEquals("500", mirror.value)
    }

    @Test
    fun `镜像握着意图时左键读点只作废不补写`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "500")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_LBUTTON)
        state.noteClickIntent("500")

        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_LBUTTON))
        assertEquals(0, mirror.writes, "左键支消费的是 MOUSEB()，镜像已握着同一份文本时不该重写")
        // 已被左键读点收走：紧接着的回车读点不再补写（防同一次点击被两支各消费一次）。
        edge(state, AndroidKeyCodes.VK_RETURN)
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes)
    }

    @Test
    fun `镜像被覆写时左键读点保留意图给回车支`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_LBUTTON)
        state.noteClickIntent("500")

        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_LBUTTON))
        assertEquals(0, mirror.writes, "左键读点不改写镜像")
        // 左键支读 MOUSEB() 拿不到值 ⇒ 意图必须留给紧随其后的回车读点补写
        // （真机「点好几次才切换地图」的兜底路径）。
        edge(state, AndroidKeyCodes.VK_RETURN)
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals("500", mirror.value)
        assertEquals(1, mirror.writes)
    }

    @Test
    fun `空落点不记意图也不改写镜像`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "状态串")
        state.mirror = mirror
        state.noteClickIntent("")

        edge(state, AndroidKeyCodes.VK_RETURN)
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes, "空落点不携带信息，补写只会清掉脚本状态串")
        assertEquals("状态串", mirror.value)
    }

    @Test
    fun `新提交与 BEGIN 复位都会作废待交付意图`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "")
        state.mirror = mirror

        state.noteClickIntent("500")
        state.clearClickIntent()
        edge(state, AndroidKeyCodes.VK_RETURN)
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes, "显式作废后读点不应再补写")

        state.noteClickIntent("500")
        state.clear()
        edge(state, AndroidKeyCodes.VK_RETURN)
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes, "clear() 应连意图一起清空")
    }
}
