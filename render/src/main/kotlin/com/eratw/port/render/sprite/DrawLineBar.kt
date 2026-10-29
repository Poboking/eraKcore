package com.eratw.port.render.sprite

import com.eratw.port.render.Grid

/**
 * DRAWLINE / CUSTOMDRAWLINE / DRAWLINEFORM 的整行重复字符生成（契约 §2 m-sprite）。
 *
 * 逐行复刻原版 `EmueraConsole.getStBar`（`MinorShift.Emuera.GameView/EmueraConsole.cs:3420-3437`）：
 * 先整段重复追加直到测量宽度**不小于** `DrawableWidth`，再**一次删一个 UTF-16 字符**
 * 直到不大于 —— 收尾时常截掉重复单元的一半（如 `"--2.."` 型行尾单横杠），
 * 基准 `pc-golden-root.txt:255` 实测 `-2` 串 199 字符、结尾为 `-`，官方行为原样复刻
 * （删除循环 `:3431-3435` 的 `Remove(Length-1, 1)` 按 char 删，代理对字符会被砍半，同样照抄）。
 *
 * 三条语句的差异只在**计算时机与参数来源**，生成本身同一个函数：
 * - DRAWLINE：打印启动期由 `setStBar(Config.DrawLineString)` 预生成的 `stBar`
 *   （`Process.cs:259`、`EmueraConsole.cs:3439-3442`、`PrintBar` `:3388-3393`；
 *   运行期分发 `Process.cs:1155-1160`）；`DrawLineString` 空配置回退 `"-"`
 *   （`Config.cs:725-729`，语料 `emuera.config` 无该键覆盖，基准 `:254` 即 `-` 串）。
 * - CUSTOMDRAWLINE：**解析期**就地 `getStBar` 成常量串，执行时原文打印
 *   （`FunctionIdentifier.cs:506-538`，`CreateArgument` `:514-527`、`DoInstruction` `:530-537`
 *   走 `printCustomBar(ConstStr, isConst = true)`，`isConst` 分支不再重测
 *   `EmueraConsole.cs:3404-3407`）。
 * - DRAWLINEFORM：**每次执行**重算 `getStBar(表达式值)`
 *   （`Process.cs:1162-1169` 走 `printCustomBar(text, isConst = false)` `:3408-3411`）。
 * - 空串：`printCustomBar` 抛 `CodeEE`（`EmueraConsole.cs:3398-3400`）；本类以
 *   [IllegalStateException] 对应（函数层再包成运行期错误）。CUSTOMDRAWLINE 无参在
 *   解析期抛 MissingArg（`FunctionIdentifier.cs:516-520`，`getStBar` 即时生成在 `:522`），归语句层，不在本类。
 *
 * 宽度模型（重设计区，ADR-001：不信 GDI 度量，整数网格强制对齐）：
 * - `DrawableWidth = WindowX - max(2, FontSize/6)`（`Config.cs:461-466`；语料
 *   `emuera.config:12` WINDOW WIDTH 1800、`:22` FONT SIZE 18 → shift 3 → 1797）；
 * - 半角步进 9px 由**基准反推唯一确定**：基准 `:253` 是 199 个 `=`，
 *   整数像素步进 adv 须满足 `199*adv <= 1797 < 200*adv` → `8.985 < adv <= 9.03` → adv = 9；
 * - 全角按 18px（2 半角格，`Grid.cellWidth` 口径），TAB 先按 8 空格展开
 *   （`StringMeasure.cs:57-61` 仅 GRAPHICS 模式展开；基准采集走 TEXTRENDERER 的整串
 *   `TextRenderer.MeasureText`，`StringMeasure.cs:67-69`，等宽字体下与线性模型一致）。
 *   TODO(verify)：全角/代理对字符的步进没有基准样本钉死（基准只出现半角），真机需复核。
 *
 * @property clientWidth 窗口宽（半角网格模型下即像素宽；基准 `CLIENTWIDTH=1800`，
 *   `pc-golden-root.txt:689`）
 * @property fontSizePx 字体像素高（语料 `FONT SIZE:18`，影响 shift）
 * @property halfCellPx 半角字符步进（基准反推为 9，见类 KDoc）
 */
class DrawLineBar(
    val clientWidth: Int = Grid.DEFAULT_CLIENT_WIDTH,
    val fontSizePx: Int = DEFAULT_FONT_SIZE_PX,
    val halfCellPx: Int = DEFAULT_HALF_CELL_PX,
) {

    /** `DrawingParam_ShapePositionShift = max(2, FontSize/6)`（`Config.cs:461-465`）。 */
    val shapeShift: Int get() = maxOf(2, fontSizePx / 6)

    /** `Config.DrawableWidth = WindowX - shift`（`Config.cs:466`）。 */
    val drawableWidth: Int get() = clientWidth - shapeShift

    /**
     * 整串测量：半角 [halfCellPx]px、全角两倍，TAB 先展开成 8 空格
     * （对应原版 `stringMeasure.GetDisplayLength(s, Config.DefaultFont)`，
     * `EmueraConsole.cs:3429/3434` 调用点、`StringMeasure.cs:51-72` 实现）。
     */
    fun measurePx(s: String): Int = Grid.cells(s) * halfCellPx

    /**
     * DRAWLINE 用的默认分隔线（`Config.DrawLineString`，空回退 `"-"`，
     * `Config.cs:725-729`）；对应原版启动期 `setStBar` 生成的 `stBar`
     * （`Process.cs:259`），一旦生成不再随配置变化。
     */
    val defaultBar: String by lazy { build(DEFAULT_BAR_STR) }

    /**
     * `getStBar` 复刻：重复 [barStr] 生成整行；空串抛 [IllegalStateException]
     * （原版 `CodeEE`，`EmueraConsole.cs:3398-3400`）。
     *
     * 两处官方落点原样保留（ADR-005，都由 `num` 的硬编码初值 0 决定，`EmueraConsole.cs:3424`）：
     * - `drawableWidth == 0`：追加判据 `num < DrawableWidth`（`:3426`）与删除判据
     *   `num > DrawableWidth`（`:3431`）对 0 都不成立 ⇒ 一次都不测，直接返回循环外那一次
     *   `Append`（`:3423`）的整个重复单元，哪怕它比 0 宽；
     * - `drawableWidth < 0`：追加循环同样不进，删除循环先把那一次 `Append` 删空、再对空串
     *   走 `Remove(Length - 1, 1)` —— 空串测量恒 0（`StringMeasure.cs:53-55`）使判据永远成立，
     *   于是原版自己在 `EmueraConsole.cs:3433` 越界抛出。本实现同样落
     *   [IndexOutOfBoundsException]（`deleteCharAt` 的同族），不做「返回空串」的顺手修复。
     */
    fun build(barStr: String): String {
        if (barStr.isEmpty()) {
            throw IllegalStateException("DRAWLINE 的重复串不能为空（原版 CodeEE：EmueraConsole.cs:3398-3400）")
        }
        // 初值与原版逐行对应：先 Append 一次、num=0，进循环才测量（EmueraConsole.cs:3422-3424）
        val sb = StringBuilder()
        sb.append(barStr)
        var num = 0
        while (num < drawableWidth) {
            sb.append(barStr)
            num = measurePx(sb.toString())
        }
        // 逐 char 删除（代理对砍半的官方怪癖原样保留，EmueraConsole.cs:3431-3435）
        while (num > drawableWidth) {
            sb.deleteCharAt(sb.length - 1)
            num = measurePx(sb.toString())
        }
        return sb.toString()
    }

    companion object {
        /** 语料无 DRAW_LINE 覆盖，走 `Config.cs:725-729` 的空值回退 `"-"`。 */
        const val DEFAULT_BAR_STR: String = "-"

        /** 语料 `emuera.config:22` 的 FONT SIZE。 */
        const val DEFAULT_FONT_SIZE_PX: Int = 18

        /** 半角步进 9px：由基准 `pc-golden-root.txt:253`（199 个 `=`）反推唯一整数解，见类 KDoc。 */
        const val DEFAULT_HALF_CELL_PX: Int = 9
    }
}
