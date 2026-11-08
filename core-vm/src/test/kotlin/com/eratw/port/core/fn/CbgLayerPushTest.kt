package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.core.host.GRAPHICS_SINK_SLOT
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.GraphicsSinkRef
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CBG 图层 → `GraphicsSink.setCbgLayers` 的推送面（core 侧）。
 *
 * ## 判据
 *
 * - 原版控制台的 `cbgList` 是绘制依据：`CBG_SetImage` 是
 *   `cbgList.Add(item); cbgList.Sort();`（`EmueraConsole.cs:645-646`），
 *   `CBG_SetButtonImage` 同形（`:679-680`），清除三条直接改列表（`:563-614`）；
 *   绘制循环按列表序从深到浅合并（`:1834-1857`）。
 * - 语料活体：4.981 `ERB/魔改内容/QOL_IMAGE.ERB:2562` 的
 *   `CBGSETG BG_ID, 0, 0, 1000` —— 先 `GCREATE` + `GDRAWSPRITE` 合成一张全屏图，
 *   再当控制台背景，depth=1000 垫在正文之下。
 *
 * ## 装置
 *
 * 走**内建函数层**（`BuiltinEvalContext`，与 `GraphicsMethodsTest` 同形），sink 经
 * `RuntimeAux` 的 [GRAPHICS_SINK_SLOT] 注入 —— 这正是产线的读面（内建函数拿不到
 * `ExecContext.graphics`，见 `GraphicsMethods.pushCbgLayers` 的 KDoc）。
 */
private class CbgPushBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("CbgPushBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 记录每一次整表推送；`current` = 最后一次（= 渲染侧此刻会看到的状态）。 */
private class RecordingCbgSink : GraphicsSink {
    val pushes = ArrayList<List<CbgLayerSnapshot>>()

    val current: List<CbgLayerSnapshot> get() = pushes.lastOrNull() ?: emptyList()

    override fun printImage(
        name: String,
        nameb: String?,
        namem: String?,
        params: List<Pair<Long, Boolean>>,
    ) {}

    override fun printRect(params: List<Pair<Long, Boolean>>) {}
    override fun printSpace(params: List<Pair<Long, Boolean>>) {}
    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
    override fun removeBgImage(name: String) {}
    override fun clearBgImages() {}

    override fun setCbgLayers(layers: List<CbgLayerSnapshot>) {
        pushes.add(layers)
    }
}

private fun cbgPushCtx(): Pair<BuiltinEvalContext, RecordingCbgSink> {
    val store = VariableStore()
    val base = CbgPushBaseCtx()
    base.variables = store
    val sink = RecordingCbgSink()
    store.aux.slot(GRAPHICS_SINK_SLOT) { GraphicsSinkRef() }.sink = sink
    return BuiltinEvalContext(base, store) to sink
}

/** 调用内建：字符串参数传 [String]，整数参数传 [Long]/[Int]。 */
private fun cbgCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    ) as Value.IntV).v

class CbgLayerPushTest {

    @Test
    fun `CBGSETG 后推一条 depth 1000 的整屏快照`() {
        val (ctx, sink) = cbgPushCtx()
        // QOL_IMAGE.ERB:2562 的形状：先 GCREATE 一张全屏图，再 CBGSETG 当背景（depth=1000）。
        assertEquals(1L, cbgCall(ctx, "GCREATE", 7, 100, 60))
        assertEquals(1L, cbgCall(ctx, "CBGSETG", 7, 0, 0, 1000))

        val snap = sink.current.single()
        assertEquals(1000, snap.depth, "depth 原样（原版 cbgList 的 zdepth）")
        assertEquals(0, snap.x)
        assertEquals(0, snap.y)
        assertNull(snap.spriteName, "gid 分支没有名字")
        assertEquals(7, snap.gid)
        assertEquals(100, snap.widthPx, "宽取 GraphicsPool 里缓冲的实际宽")
        assertEquals(60, snap.heightPx)
    }

    @Test
    fun `CBGCLEAR 后推空快照`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 7, 100, 60))
        assertEquals(1L, cbgCall(ctx, "CBGSETG", 7, 0, 0, 1000))
        assertEquals(1, sink.current.size, "先有一层")

        assertEquals(1L, cbgCall(ctx, "CBGCLEAR"))
        assertTrue(sink.current.isEmpty(), "CBG_Clear 清全部（EmueraConsole.cs:563-575）⇒ 渲染侧收到空表")
        assertEquals(2, sink.pushes.size, "每次变更各推一次")
    }

    @Test
    fun `CBGSETSPRITE 快照带 spriteName 与尺寸`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 3, 40, 20))
        assertEquals(1L, cbgCall(ctx, "SPRITECREATE", "HL_BG", 3))
        assertEquals(1L, cbgCall(ctx, "CBGSETSPRITE", "HL_BG", 5, -7, 1000))

        val snap = sink.current.single()
        assertEquals("HL_BG", snap.spriteName, "名字分支原样给（SpritePool 里按大写归一）")
        assertEquals(-1, snap.gid, "名分支没有 gid，用 -1 哨兵")
        assertEquals(1000, snap.depth)
        assertEquals(5, snap.x)
        assertEquals(-7, snap.y)
        assertEquals(40, snap.widthPx)
        assertEquals(20, snap.heightPx)
    }

    @Test
    fun `CBGREMOVERANGE 与 CBGCLEARBUTTON 也推快照`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 7, 8, 8))
        assertEquals(1L, cbgCall(ctx, "GCREATE", 8, 8, 8))
        assertEquals(1L, cbgCall(ctx, "CBGSETG", 7, 0, 0, 5))
        assertEquals(1L, cbgCall(ctx, "CBGSETG", 8, 1, 1, 1))
        assertEquals(listOf(5, 1), sink.current.map { it.depth }, "快照按 depth 降序（Add + Sort）")

        // 只清 4..10 ⇒ 5 被清、1 留下，且 depth == 0 永不清（EmueraConsole.cs:577-596）。
        assertEquals(1L, cbgCall(ctx, "CBGREMOVERANGE", 4, 10))
        assertEquals(listOf(1), sink.current.map { it.depth })

        assertEquals(1L, cbgCall(ctx, "SPRITECREATE", "BTN_SPR", 7))
        assertEquals(1L, cbgCall(ctx, "CBGSETBUTTONSPRITE", 9, "BTN_SPR", "BTN_SPR", 0, 0, 2, "TIP"))
        assertEquals(listOf(2, 1), sink.current.map { it.depth })
        // CBG_ClearButton 只删按钮层（:598-614），留下的 depth 1 那条还在。
        assertEquals(1L, cbgCall(ctx, "CBGCLEARBUTTON"))
        assertEquals(listOf(1), sink.current.map { it.depth })
    }

    // ------------------------------------------------------------------
    // B 刀：CBG 按钮链（CBGSETBUTTONSPRITE + CBGSETBMAPG / CBGREMOVEBMAP）
    // ------------------------------------------------------------------

    /**
     * 按钮层快照带齐「按下态换图」所需的四个字段。
     *
     * 判据（原版）：绘制循环按 `cbgList[num5].isButton && buttonValue == selectingCBGButtonInt`
     * 把要画的 sprite 换成 `ImgB`（`EmueraConsole.cs:1840-1843`）；`selectingCBGButtonInt`
     * 的值来自取色图取色（`:1309-1320`）。所以渲染侧必须有
     * `spriteNameB`（换哪张）、`isButton` + `buttonValue`（换不换）、`buttonMapGid`
     * （去哪张图取色）。
     */
    @Test
    fun `CBGSETBUTTONSPRITE 快照带按下图与按钮值`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 1, 8, 8))
        assertEquals(1L, cbgCall(ctx, "SPRITECREATE", "BTN_N", 1))
        assertEquals(1L, cbgCall(ctx, "SPRITECREATE", "BTN_B", 1))
        assertEquals(1L, cbgCall(ctx, "CBGSETBUTTONSPRITE", 0xFF0000, "BTN_N", "BTN_B", 3, 4, 100, "TIP"))

        val snap = sink.current.single()
        assertEquals("BTN_N", snap.spriteName, "主图（Img，按下态以外画它）")
        assertEquals("BTN_B", snap.spriteNameB, "按下图（ImgB，EmueraConsole.cs:1842）")
        assertTrue(snap.isButton, "按钮层（isButton，:1840）")
        assertEquals(0xFF0000, snap.buttonValue, "按钮值（buttonValue，:1840）")
        assertEquals(-1, snap.buttonMapGid, "还没设取色图 ⇒ -1（CBGSETBMAPG 之前）")
        assertEquals(100, snap.depth)
        assertEquals(3, snap.x)
        assertEquals(4, snap.y)
    }

    /**
     * `CBGSETBMAPG` / `CBGREMOVEBMAP` 也要推快照：`buttonMapGid` 是快照字段
     * （`CbgLayerSnapshot.buttonMapGid`），不重推渲染侧会拿着旧 gid 做按下态比对。
     *
     * 原版落点：`CBG_SetButtonMap` 只改 `cbgButtonMap` 字段（`EmueraConsole.cs:650-664`），
     * `CBG_ClearBMap` 置 null（`:616-621`）—— 列表没变，但绘制依据变了。
     */
    @Test
    fun `CBGSETBMAPG 与 CBGREMOVEBMAP 也推快照并带取色图 gid`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 1, 8, 8))
        assertEquals(1L, cbgCall(ctx, "GCREATE", 2, 8, 8))
        assertEquals(1L, cbgCall(ctx, "SPRITECREATE", "BTN_N", 1))
        assertEquals(1L, cbgCall(ctx, "CBGSETBUTTONSPRITE", 7, "BTN_N", "BTN_N", 0, 0, 9))
        assertEquals(-1, sink.current.single().buttonMapGid)
        val pushesBefore = sink.pushes.size

        assertEquals(1L, cbgCall(ctx, "CBGSETBMAPG", 2))
        assertEquals(pushesBefore + 1, sink.pushes.size, "设取色图要重推整表")
        assertEquals(2, sink.current.single().buttonMapGid, "取色图 gid 进快照")

        // 同一张图重复设置 → 0 且**不**重推（原版 :656-659 的引用比较早退）。
        val afterSet = sink.pushes.size
        assertEquals(0L, cbgCall(ctx, "CBGSETBMAPG", 2))
        assertEquals(afterSet, sink.pushes.size, "重复设置不推快照")

        assertEquals(1L, cbgCall(ctx, "CBGREMOVEBMAP"))
        assertEquals(afterSet + 1, sink.pushes.size, "撤取色图也要重推")
        assertEquals(-1, sink.current.single().buttonMapGid, "撤销后回落 -1")
    }

    // ------------------------------------------------------------------
    // A 刀：动画 CBG 的帧几何
    // ------------------------------------------------------------------

    /**
     * `CBGSETSPRITE` 打在动画 sprite 上时，快照给**帧表 + 首帧几何**，尺寸取帧而不是
     * `DestBaseSize`。
     *
     * 判据：原版 `SpriteAnime.GraphicsDraw(g, Point)` 的落位尺寸是
     * `currentFrame.SrcRectangle.Size`（`SpriteAnime.cs:144`），偏移是
     * `currentFrame.Offset`（`:142-143`）；`DestBaseSize.Height` 只用于下沿锚点
     * （`EmueraConsole.cs:1846`）⇒ 快照的 `widthPx/heightPx` = 帧尺寸、
     * `baseHeightPx` = `DestBaseSize.Height`。`FrameList[0]` 是首帧
     * （`SpriteAnime.cs:96-102` 的 `lastFrame == -1` 分支）。
     *
     * 装置：`SPRITEANIMECREATE "ANI", 60, 50`（DestBaseSize）+
     * 两帧 `(5,6,60x50,100ms)` / `(7,8,30x40,200ms)`，经 `Normalize` 后首帧被裁成
     * `55x44@(5,6)` —— 正是 `anime.xml` 里「帧比 DestBaseSize 大」的真实形态。
     */
    @Test
    fun `动画 CBG 快照带帧表 首帧尺寸与偏移正确`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "GCREATE", 1, 100, 100))
        assertEquals(1L, cbgCall(ctx, "GCREATE", 2, 100, 100))
        assertEquals(1L, cbgCall(ctx, "SPRITEANIMECREATE", "ANI", 60, 50))
        assertEquals(1L, cbgCall(ctx, "SPRITEANIMEADDFRAME", "ANI", 1, 0, 0, 60, 50, 5, 6, 100))
        assertEquals(1L, cbgCall(ctx, "SPRITEANIMEADDFRAME", "ANI", 2, 0, 0, 30, 40, 7, 8, 200))
        assertEquals(1L, cbgCall(ctx, "CBGSETSPRITE", "ANI", 10, -20, 500))

        val snap = sink.current.single()
        assertEquals("ANI", snap.spriteName)
        assertEquals(500, snap.depth)
        assertEquals(10, snap.x)
        assertEquals(-20, snap.y)
        assertEquals(0, snap.frameIndex, "推送时刻 = 首帧（SpriteAnime.cs:96-102）")
        assertEquals(2, snap.frames.size, "帧表整份下发（逐帧推进的唯一数据面）")
        assertEquals(55, snap.widthPx, "落位宽 = 首帧 SrcRectangle.Width（Normalize 裁过）")
        assertEquals(44, snap.heightPx, "落位高 = 首帧 SrcRectangle.Height")
        assertEquals(50, snap.baseHeightPx, "下沿锚点 = DestBaseSize.Height（EmueraConsole.cs:1846）")
        val f0 = snap.frames[0]
        assertEquals(55, f0.widthPx)
        assertEquals(44, f0.heightPx)
        assertEquals(5, f0.offsetX, "AnimeFrame.Offset 原样（SpriteAnime.cs:142-143）")
        assertEquals(6, f0.offsetY)
        assertEquals(100, f0.delayMs)
        val f1 = snap.frames[1]
        assertEquals(30, f1.widthPx)
        assertEquals(40, f1.heightPx)
        assertEquals(7, f1.offsetX)
        assertEquals(8, f1.offsetY)
        assertEquals(200, f1.delayMs)
    }

    /**
     * 空帧表的动画 sprite：原版 `GetCurrentFrame` 返回 null ⇒ 一个像素都不画
     * （`SpriteAnime.cs:92-95` + `:139-145`）。快照用 [CbgLayerSnapshot.NO_FRAME] 表达
     * 「这一层没有可画的帧」，渲染侧见它整层跳过（不能退化成「按位图高画一张静态图」）。
     */
    @Test
    fun `空帧表动画 CBG 快照标记 NO_FRAME`() {
        val (ctx, sink) = cbgPushCtx()
        assertEquals(1L, cbgCall(ctx, "SPRITEANIMECREATE", "EMPTY", 40, 30))
        assertEquals(1L, cbgCall(ctx, "CBGSETSPRITE", "EMPTY", 0, 0, 5))

        val snap = sink.current.single()
        assertTrue(snap.frames.isEmpty(), "没有帧 ⇒ 帧表为空（静态层的判据）")
        assertEquals(CbgLayerSnapshot.NO_FRAME, snap.frameIndex, "整层不画")
        assertEquals(0, snap.widthPx)
        assertEquals(0, snap.heightPx)
        assertEquals(30, snap.baseHeightPx, "DestBaseSize.Height 仍给（锚点数据面不受帧表影响）")
    }
}
