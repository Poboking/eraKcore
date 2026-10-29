package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TextBoxPosition] —— `MOVETEXTBOX` / `RESUMETEXTBOX` 的四态延迟生效模型。
 *
 * ## 期望值出处（`MinorShift.Emuera.Forms/MainWindow.cs`）
 * - 结构体与枚举：`TextBoxInfo` :23-30、`TextBoxState` :32-38（`WatingToChange` 的拼写错误
 *   原样保留，ADR-005）；三个字段 :44-48（`textBoxInfo`/`nextTextBoxInfo`/`textBoxState`）。
 * - 默认几何快照 :275-278，初值来自 :727-728 的 `Location=(0, ClientHeight-LineHeight)`、
 *   `Size=(Config.WindowX, LineHeight)`。
 * - `SetTextBoxPos(xOffset, yOffset, width)` :342-348 的三条夹取（:344 左沿、:345 上沿读
 *   `richTextBox1.Height`、:346 宽读 `richTextBox1.Left`，高度原样带过去），
 *   结尾只置 `WatingToChange`（:347）**不动控件**。
 * - 私有重载 `SetTextBoxPos(TextBoxInfo)` :371-376 才是真正写 `Left/Top/Size` 的那一句，
 *   调用它的只有 :352（Reset）、:358（ScrollBack）、:366（Apply）。
 * - `ResetTextBoxPos` :350-354、`ScrollBackTextBoxPos` :356-360、`ApplyTextBoxChanges` :362-369。
 * - 滚动联动 :327-340（含 :329 的 `TextBoxIgnoreScrollBarChanges` 闸门，闸门的开关方
 *   `EmueraConsole.cs:2551` 与 :2566）。
 * - 三个对外属性 :216/:218/:220；闸门属性 :214。
 * - 函数壳 `FunctionMethodCreator.cs:3079-3108`：`MOVETEXTBOX` 走 :3104 的三实参、
 *   `RESUMETEXTBOX` 走 :3098-3101 的 **丢掉全部实参**，两者都 `return 1L`（:3106），
 *   装载期仍按 `Type[3]` 校验（:3086-3091）；注册表 :9277/:9278。
 *   规约同判：`specs/builtin-functions.yaml:1474-1489`、`specs/verify-notes.md:335`。
 * - 提交后的自动复位只夹 `Changed` 态：`EmueraConsole.cs:1283-1286`
 *   （`if (window.TextBoxPosChanged) window.ResetTextBoxPos()`）。
 *
 * 触发方（哪些语句会调 `ApplyTextBoxChanges`）在 `FunctionIdentifier.cs`：
 * `INPUT` :852、`INPUTS` :896、`BINPUT`/`BINPUTS`/`ONEBINPUT`/`ONEBINPUTS` :2143/:2247/:2352/:2457；
 * `ONEINPUT`/`ONEINPUTS`/`TINPUT`/`TINPUTS`（:915-957、:959-1001、:1003-1050、:1052-1099）
 * 那四支**没有**这一句 ⇒ 本层的「延迟生效」正是为了让安卓侧不在收到 `MOVETEXTBOX` 时就搬框子。
 * （这些行号在 `:core-vm` 的装载面，本路只读、不改；单位是像素，不折半角格。）
 */
class TextBoxPositionTest {

    /** 客户区 800x600、行高 20 ⇒ 默认框几何按 :727-728 摆在最底一行。 */
    private fun box(
        clientWidth: Int = 800,
        clientHeight: Int = 600,
        default: BoxGeom = BoxGeom(left = 0, top = 580, width = 800, height = 20),
    ) = TextBoxPosition(clientWidth, clientHeight, default)

    @Test
    fun `初始是不变态且几何等于默认`() {
        val p = box()
        assertEquals(PosState.UNCHANGED, p.state, "textBoxState = Unchanged（:278）")
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied, "默认几何取构造时的快照（:275-278）")
        assertEquals(BoxGeom(0, 580, 800, 20), p.pending, "本层用默认几何起稿；原版 nextTextBoxInfo 是结构体零值（:46），" +
            "差别不可观测 —— 生效只发生在 WatingToChange/ScrollBack，而前者必先有一次请求（:347）")
        assertFalse(p.posChanged, ":216")
        assertFalse(p.posScrolledBack, ":218")
        assertFalse(p.posWaitingToChange, ":220")
    }

    @Test
    fun `请求移动只写pending不动控件`() {
        val p = box()
        p.requestMove(xOffsetPx = 100, yOffsetFromBottomPx = 40, widthPx = 300)
        assertEquals(PosState.WATING_TO_CHANGE, p.state, ":347")
        assertTrue(p.posWaitingToChange, ":220")
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied, "整段 SetTextBoxPos(int,int,int) 不碰 richTextBox1（:342-348）")
        assertEquals(BoxGeom(100, 540, 300, 20), p.pending, "上沿 = 600 - 40 - 当前高 20（:345）")
    }

    @Test
    fun `三条夹取逐字照抄`() {
        val p = box()
        p.requestMove(xOffsetPx = -10, yOffsetFromBottomPx = 0, widthPx = 10)
        assertEquals(0, p.pending.left, "左沿下界零（:344）")
        assertEquals(50, p.pending.width, "宽下界 50（:346 的那个 50）")

        p.requestMove(xOffsetPx = 1000, yOffsetFromBottomPx = 0, widthPx = 5000)
        assertEquals(750, p.pending.left, "左沿上界 = 客户宽 - 50（:344）")
        assertEquals(800, p.pending.width, "宽上界 = 客户宽 - **当前左沿**（:346 读 richTextBox1.Left）")

        p.requestMove(xOffsetPx = 0, yOffsetFromBottomPx = 700, widthPx = 100)
        assertEquals(0, p.pending.top, "从底往上数过头时下界零（:345 的 Max）")
        assertEquals(20, p.pending.height, "高度整段没被改（:346 的 Size 第二参）")
    }

    @Test
    fun `宽上界读的是当前生效的左沿`() {
        val p = box()
        p.requestMove(xOffsetPx = 300, yOffsetFromBottomPx = 0, widthPx = 5000)
        assertEquals(800, p.pending.width, "此刻 applied.left 仍是零 ⇒ 上界就是客户宽 800（:346 读 richTextBox1.Left）")
        p.applyChanges()
        assertEquals(300, p.applied.left)
        p.requestMove(xOffsetPx = 300, yOffsetFromBottomPx = 0, widthPx = 5000)
        assertEquals(500, p.pending.width, "上界改成 800 - 当前左沿 300（:346 读的是 richTextBox1.Left）")
    }

    @Test
    fun `生效后落到控件并置已改态`() {
        val p = box()
        p.requestMove(100, 40, 300)
        p.applyChanges()
        assertEquals(BoxGeom(100, 540, 300, 20), p.applied, ":366 调私有重载 :371-376")
        assertEquals(PosState.CHANGED, p.state, ":367")
        assertTrue(p.posChanged, ":216")
    }

    @Test
    fun `不变态与已改态下生效是空操作`() {
        val unchanged = box()
        unchanged.applyChanges()
        assertEquals(PosState.UNCHANGED, unchanged.state, ":364 的守卫不成立 ⇒ 整段跳过")
        assertEquals(BoxGeom(0, 580, 800, 20), unchanged.applied)

        val changed = box()
        changed.requestMove(100, 40, 300)
        changed.applyChanges()
        changed.applyChanges()
        assertEquals(BoxGeom(100, 540, 300, 20), changed.applied, "已是 CHANGED ⇒ 再 Apply 也不动（:364）")

        changed.requestMove(200, 40, 300)
        assertEquals(PosState.WATING_TO_CHANGE, changed.state, "新请求总是重新武装待生效态（:347）")
        changed.applyChanges()
        assertEquals(200, changed.applied.left, "于是下一条 INPUT 才把它搬上控件")
    }

    @Test
    fun `RESUMETEXTBOX 丢掉实参但装载期仍校验`() {
        val p = box()
        p.requestMove(100, 40, 300)
        // resume 支只调 ResetTextBoxPos，三个实参整个丢掉（FunctionMethodCreator.cs:3098-3101）
        p.reset()
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied, ":350-354 刷回默认几何")
        assertEquals(PosState.UNCHANGED, p.state, ":353")
        assertEquals(BoxGeom(100, 540, 300, 20), p.pending, ":352 只调私有重载 ⇒ nextTextBoxInfo 原样留着")

        // 复位之后再 INPUT（ApplyTextBoxChanges）也不会把丢掉的位置捡回来
        p.applyChanges()
        assertEquals(PosState.UNCHANGED, p.state, ":364 的守卫不成立")
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied)
    }

    @Test
    fun `请求之后立刻复位再请求才算生效`() {
        val p = box()
        p.requestMove(100, 40, 300)
        p.reset()
        p.applyChanges()
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied, "RESUMETEXTBOX 之后那条 INPUT 搬不动框子")
        p.requestMove(100, 40, 300)
        p.applyChanges()
        assertEquals(BoxGeom(100, 540, 300, 20), p.applied, "下一条 MOVETEXTBOX 才重新进入待生效态")
    }

    @Test
    fun `滚动联动两向`() {
        val p = box()
        p.requestMove(100, 40, 300)
        p.applyChanges()
        // 往上滚（不在底部）⇒ 收回默认位并记成 ScrollBack
        assertTrue(p.onScrollBarValueChanged(atMaximum = false), ":331-334")
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied, ":358 刷回默认")
        assertEquals(PosState.SCROLL_BACK, p.state, ":359")
        assertTrue(p.posScrolledBack, ":218")
        // 滚回底部 ⇒ 把之前请求过的位置补上
        assertTrue(p.onScrollBarValueChanged(atMaximum = true), ":335-338")
        assertEquals(BoxGeom(100, 540, 300, 20), p.applied)
        assertEquals(PosState.CHANGED, p.state)
    }

    @Test
    fun `状态不匹配的滚动事件不动东西`() {
        val p = box()
        assertFalse(p.onScrollBarValueChanged(atMaximum = false), "UNCHANGED 态 ⇒ 两条都不成立")
        assertFalse(p.onScrollBarValueChanged(atMaximum = true))
        assertEquals(PosState.UNCHANGED, p.state)
        assertEquals(BoxGeom(0, 580, 800, 20), p.applied)
    }

    @Test
    fun `程序化滚动期间闸门挡住联动`() {
        val p = box()
        p.requestMove(100, 40, 300)
        p.applyChanges()
        p.ignoreScrollBarChanges = true
        assertFalse(p.onScrollBarValueChanged(atMaximum = false), ":329 的 TextBoxIgnoreScrollBarChanges（:214）")
        assertEquals(PosState.CHANGED, p.state)
        assertEquals(BoxGeom(100, 540, 300, 20), p.applied)
        p.ignoreScrollBarChanges = false
        assertTrue(p.onScrollBarValueChanged(atMaximum = false), "闸门开回来越常联动（EmueraConsole.cs:2566 关阀）")
    }

    @Test
    fun `提交后复位只夹已改态`() {
        val waiting = box()
        waiting.requestMove(100, 40, 300)
        waiting.afterAcceptedInput()
        assertEquals(PosState.WATING_TO_CHANGE, waiting.state, "EmueraConsole.cs:1283 只判 Changed ⇒ 未生效的请求不被抹掉")
        assertEquals(BoxGeom(0, 580, 800, 20), waiting.applied)

        val changed = box()
        changed.requestMove(100, 40, 300)
        changed.applyChanges()
        changed.afterAcceptedInput()
        assertEquals(PosState.UNCHANGED, changed.state, ":1285 的 ResetTextBoxPos")
        assertEquals(BoxGeom(0, 580, 800, 20), changed.applied)

        val unchanged = box()
        unchanged.afterAcceptedInput()
        assertEquals(PosState.UNCHANGED, unchanged.state)
    }

    @Test
    fun `客户区尺寸改了下一次请求才用新值`() {
        val p = box()
        p.clientWidthPx = 400
        p.clientHeightPx = 300
        p.requestMove(xOffsetPx = 1000, yOffsetFromBottomPx = 0, widthPx = 5000)
        assertEquals(350, p.pending.left, "左沿上界 = 新宽 400 - 50（:344）")
        assertEquals(400, p.pending.width, "宽上界 = 新宽 400 - 当前左沿 0（:346）")
        assertEquals(280, p.pending.top, "上界 = 新高 300 - 当前高 20（:345）")
    }

    @Test
    fun `几何按left-top-width-height摊平`() {
        val info = BoxGeom(left = 1, top = 2, width = 3, height = 4)
        assertEquals(1, info.left, "对应 TextBoxInfo.Left（MainWindow.cs:27）")
        assertEquals(2, info.top, "对应 TextBoxInfo.Top（:25）")
        assertEquals(3, info.width, "对应 TextBoxInfo.Size.Width（:29）")
        assertEquals(4, info.height, "对应 TextBoxInfo.Size.Height")
        assertEquals(50, TextBoxPosition.MIN_WIDTH_PX, ":344 与 :346 里那个 50")
        assertEquals(BoxGeom(1, 2, 3, 4), info, "data class 等值 ⇒ 断言可整体写")
    }
}
