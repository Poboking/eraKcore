package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 帧发布闸「解冻」路径的回归钉子（CONSOLE-FRAMEGATE，main 提交 9b2d8e9 修复面）。
 *
 * 真机病灶：LOADDATA / BADAPPLE 一类脚本段里 REDRAW 0 冻结 + 让出边界解冻的节奏被打断时，
 * 要么「画面卡在上一帧」（该发布的不发布），要么「中间态逐条上屏抖动」（不该发布的乱发布）。
 * 本类把解冻侧每条路径单独钉死：
 *
 * - REDRAW 1 解冻 + 冻结期内容一次性发布（原版回 Normal 后下一次 RefreshStrings 画出
 *   新状态；SetRedraw 的 bit1 才是强制重绘，EmueraConsole.cs:1693-1707）；
 * - REDRAW 2 冻结态下单次强制发布、闸保持冻结（原版 `RefreshStrings(force_Paint: true)`
 *   是一次性绘制副作用，不改 redraw 状态，EmueraConsole.cs:1703-1707）；
 * - 冻结期打印 / CLEARLINE 只记脏（原版 None 态 paint 早退，EmueraConsole.cs:1738-1741）；
 * - printError 强制解冻（错误态主循环必画，EmueraConsole.cs:767 置 Error 后 :328/:403
 *   的主循环分支不再被 None 挡住）；
 * - 空帧 / 重复发布幂等（revision 只在有脏时 bump）。
 *
 * 开关（frameGateEnabled=false 永不冻结）与冻结基础语义由 FrameGateToggleTest 钉，不在此重复。
 */
class FrameGateUnfreezeTest {

    /** REDRAW 0 冻结段内打印不发布；REDRAW 1 解冻一次性发布并恢复直通。 */
    @Test
    fun redraw0FreezesRedraw1UnfreezesWithOneFrame() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        val base = s.revision

        s.setRedraw(0)
        s.print("frame")
        s.newLine()
        assertEquals(base, s.revision, "REDRAW 0 冻结段内的打印中间态不发布（只记脏）")

        s.setRedraw(1)
        assertEquals(base + 1, s.revision, "REDRAW 1 解冻：冻结期间的变更一次性发布成完整帧")

        s.print("next")
        s.newLine()
        assertEquals(base + 2, s.revision, "解冻后恢复直通：新变更立即发布")
    }

    /** REDRAW 2 = None + 强制重绘：冻结态下发布一次，之后闸仍冻结。 */
    @Test
    fun redraw2FlushesOnceAndStaysFrozen() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        val base = s.revision

        s.setRedraw(0)
        s.print("f")
        s.newLine()
        s.setRedraw(2)
        assertEquals(base + 1, s.revision, "REDRAW 2 冻结态下强制发布一次（原版 bit1 强制重绘）")

        s.print("g")
        s.newLine()
        assertEquals(base + 1, s.revision, "强制只发生一次：REDRAW 2 之后闸仍冻结")

        s.publishFrame()
        assertEquals(base + 2, s.revision, "后续 publishFrame 正常解冻并发布积攒内容")
    }

    /** 空帧不发布：冻结期无可见变更时，解冻与重复 publishFrame 都不 bump revision。 */
    @Test
    fun emptyFrameAndRepeatedPublishAreIdempotent() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        val base = s.revision

        s.setRedraw(0)
        s.setRedraw(1)
        assertEquals(base, s.revision, "冻结期无变更：解冻不发布空帧")

        s.publishFrame()
        s.publishFrame()
        assertEquals(base, s.revision, "无脏时重复 publishFrame 幂等")

        s.holdFrame()
        s.publishFrame()
        assertEquals(base, s.revision, "冻结-解冻空转一圈同样不 bump")
    }

    /** 冻结态 CLEARLINE 只记脏；解冻时把删除结果以整帧发布。 */
    @Test
    fun deleteLineWhileFrozenPublishesOnUnfreeze() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.print("abc")
        s.newLine()
        val base = s.revision
        assertEquals(1L, s.lineCount)

        s.holdFrame()
        s.deleteLine(1)
        assertEquals(base, s.revision, "冻结态 CLEARLINE 的行摘除不发布（原版 None 态 paint 早退）")
        assertEquals(0L, s.lineCount, "内容本身照常摘除，只是发布节奏不同")

        s.publishFrame()
        assertEquals(base + 1, s.revision, "解冻时把删除以整帧发布")
    }

    /** 错误画面必可见：冻结态 printError 强制解冻发布，且闸保持开启。 */
    @Test
    fun printErrorForceUnfreezesWhileFrozen() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        val base = s.revision

        s.holdFrame()
        s.print("hidden")
        s.newLine()
        assertEquals(base, s.revision, "前置：冻结态普通打印不发布")

        s.printError("boom")
        assertEquals(
            base + 2,
            s.revision,
            "printError 强制解冻：先发布冻结期内容，错误行随后立即上屏（两次 bump）",
        )

        s.print("after")
        s.newLine()
        assertEquals(base + 3, s.revision, "printError 之后闸保持开启：后续打印直通发布")
    }
}
