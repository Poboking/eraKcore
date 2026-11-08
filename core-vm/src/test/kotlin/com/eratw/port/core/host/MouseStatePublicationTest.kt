package com.eratw.port.core.host

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T9（2026-11 挖虫波 W2-gfxconc）：[MouseState] 的跨线程发布口径。
 *
 * 写侧是 UI 线程（`GameHost.installMouse` / `updatePointer`：成对写坐标与尺寸），
 * 读侧是 VM 线程（`MouseMethods` 经 [mouseStateOf] 取用；MOUSEX/MOUSEY/MOUSEB）。
 * 旧实现全是普通 `var`：既无可见性承诺，坐标**对**还可能撕裂 —— 命中测试拿到
 * 「新 X + 旧 Y」的混叠点（原版 WinForms 单线程不存在这个窗口）。
 *
 * 新口径（类 KDoc 的线程契约）：标量 @Volatile；指针坐标与客户区尺寸各收拢成一枚
 * 不可变快照，[MouseState.setPointerPosition]/[MouseState.setClientSize] 一次整对发布，
 * [MouseState.refreshPointingForButton]/[MouseState.isPointerInClientArea] 一次取一对。
 */
class MouseStatePublicationTest {

    /** 成对写压测：UI 线程只在 (1,2) 与 (3,4) 两对之间整对换代，混叠对出现次数必须为 0。 */
    @Test
    fun `整对换代下命中测试永远看到完整坐标对`() {
        val mouse = MouseState()
        mouse.windowCreated = true
        mouse.setClientSize(100, 100)
        // T16 装置加固：起飞前先预热一枚合法坐标对，消除「首写之前读到初值」的假混叠窗口。
        // 旧装置 reader/writer 同起抢跑：writer 首次 setPointerPosition 之前 pointerPos 仍是
        // 默认 (0,0)，而 (0,0) 恰好落在 100×100 客户区内 ⇒ reader 会合法地以 (0,0) 调
        // hitTest，被下方「非 (1,2)/(3,4) 即混叠」断言误判（复现观测：observed 恒为 {0,0}，
        // mixed 在 1 到 196751 之间随调度浮动）。main 线程在 start() 之前发布 (1,2)，经
        // Thread.start 的 happens-before 边对 reader 可见 ⇒ reader 此后只会采到 (1,2)/(3,4)
        // 两枚完整对。真撕裂面（新 X + 旧 Y，即 (1,4)/(3,2)）不在预热值之内，仍会被断言捕获，
        // 未被掩盖。
        mouse.setPointerPosition(1, 2)
        val stop = AtomicBoolean(false)
        val mixed = AtomicInteger(0)
        val seen = AtomicInteger(0)
        mouse.hitTest = { x, y ->
            seen.incrementAndGet()
            if (!((x == 1 && y == 2) || (x == 3 && y == 4))) mixed.incrementAndGet()
            ConsoleButtonPointing(isButton = true, inputs = "OK")
        }
        val writer = Thread({
            var flip = false
            while (!stop.get()) {
                flip = !flip
                if (flip) mouse.setPointerPosition(1, 2) else mouse.setPointerPosition(3, 4)
            }
        }, "mouse-writer")
        val reader = Thread({
            while (!stop.get()) mouse.refreshPointingForButton()
        }, "mouse-reader")
        writer.start()
        reader.start()
        Thread.sleep(250)
        stop.set(true)
        writer.join(30_000); reader.join(30_000)
        assertFalse(writer.isAlive); assertFalse(reader.isAlive)
        assertTrue(seen.get() > 0, "读侧至少要命中一次刷新路径")
        assertEquals(0, mixed.get(), "坐标对不得跨快照混叠（旧散字段实现的撕裂面）")
    }

    /** 尺寸 + 坐标两对同时换代：越界判定看到的必须都是整对值。 */
    @Test
    fun `尺寸与坐标各自整对发布`() {
        val mouse = MouseState()
        mouse.windowCreated = true
        mouse.setClientSize(10, 10)
        mouse.setPointerPosition(5, 5)
        assertTrue(mouse.isPointerInClientArea())
        // 缩到 4x4：坐标未动，(5,5) 出界
        mouse.setClientSize(4, 4)
        assertFalse(mouse.isPointerInClientArea())
        // 整对写坐标回到 2x2 内
        mouse.setPointerPosition(2, 2)
        assertTrue(mouse.isPointerInClientArea())
        // mouseY 的原版位移口径（-clientHeight）不回归
        mouse.setClientSize(10, 20)
        mouse.setPointerPosition(7, 8)
        assertEquals(7L, mouse.mouseX())
        assertEquals((8 - 20).toLong(), mouse.mouseY())
    }

    /** 散字段 setter 的兼容形态保留：单字段赋值仍可读可写（app 侧调用形态不变的前提）。 */
    @Test
    fun `散字段读写保持源码兼容`() {
        val mouse = MouseState()
        mouse.positionX = 3
        mouse.positionY = 4
        mouse.clientWidth = 8
        mouse.clientHeight = 9
        assertEquals(3, mouse.positionX)
        assertEquals(4, mouse.positionY)
        assertEquals(8, mouse.clientWidth)
        assertEquals(9, mouse.clientHeight)
        mouse.setPointerPosition(1, 1)
        assertEquals(1, mouse.positionX)
        assertEquals(1, mouse.positionY)
    }

    /** 窗口未创建口径不回归：MOUSEX/MOUSEY 均 0，MOUSEB 刷新视为不可用（原版此处 NRE）。 */
    @Test
    fun `窗口未创建时读数为零且不刷新指向态`() {
        val mouse = MouseState()
        mouse.setPointerPosition(10, 10)
        mouse.setClientSize(100, 100)
        assertEquals(0L, mouse.mouseX())
        assertEquals(0L, mouse.mouseY())
        val marker = ConsoleButtonPointing(isButton = true, inputs = "OLD")
        mouse.pointing = marker
        mouse.hitTest = { _, _ -> ConsoleButtonPointing(isButton = true, inputs = "NEW") }
        assertFalse(mouse.refreshPointingForButton())
        assertEquals(marker, mouse.pointing)
        assertEquals("OLD", mouse.buttonValue())
    }
}
