package com.eratw.port.render.view

import kotlin.math.abs

/**
 * 「适应屏幕」字号换代的**防抖闸**（纯 JVM，可单测）。
 *
 * ## 为什么需要它（2026-10-29 玩家反馈「外出界面会出现闪动」）
 *
 * `CanvasConsoleView.refitIfPageChanged` 的判据是「本页量到几行」——行数变 ⇒ 重算字号 ⇒
 * 整页重排。对**翻页**这是对的；对**同一页的整屏重印**却是错的：QoL 地图/外出屏
 * （`ERB/魔改内容/qol/qol_MAP.ERB:34-44` 的 `$MAP_LOOP`）每 `ANIMATERECOLOREDMAPS`
 * 毫秒就 `CLEARLINE LINECOUNT - LINESTART` + 整屏重印一次，而 `pageExtentRows()`
 * 是「从底部往上数连续非空行、遇空行即停」（`ConsoleScreen.pageExtentRows` 的 KDoc），
 * 只要末尾多/少一行（`UI_MESSAGE` 红字出现或消失、末尾空行的归属变化），
 * 量到的行数就会在 N 与 N±1 之间摆 —— 而**字号是行数的函数**，于是整页在两种字号之间
 * 来回跳，玩家看到的就是「页面一直在闪」。
 *
 * 更糟的是它会自激：`applyPageFit` 换字号后 `syncFeedLayout` 会重交一次快照
 * （`CanvasConsoleView` 的既有注释也写了「重展平后页面行数可能变化，下一帧 refit 会再收敛一次」），
 * 行数因此再变一次 ⇒ 又换字号 ⇒ 循环。
 *
 * ## 口径
 *
 * **同一个行数必须连续 [stableFrames] 次被量到、且与已生效档位差 [deadbandRows] 行以上才生效**。
 *
 * 两道闸各管一种抖动：
 * - **[stableFrames]（连续帧）** 吃单帧误读：量到一次就不该换字号。
 * - **[deadbandRows]（行数死区）** 吃**重印节拍**的摆动。重印不是每帧发生的：地图/外出屏的节拍是
 *   `ANIMATERECOLOREDMAPS`（语料 `emuera.config:28` 的 FPS:5 ⇒ 200 ms），一屏在 60 Hz 下能稳稳量到
 *   **十几次同一读数** ⇒ 光靠「连续两帧」根本挡不住，于是每个重印周期换一次字号、整页尺寸来回跳
 *   （2026-10-29 真机反馈：「一向上滑动就会抖动」——抖动就是整页字号在两种档之间跳）。
 *   差 [deadbandRows] 行以内按「同一档」处理：字号不动。真翻页的行数差远大于这个数（标题页 3 行 ↔
 *   存档页 49 行），照样立刻生效；代价是「翻页后页数只差两三行」时字号停在旧档，
 *   多出来的两三行靠滚动看 —— 与 [PageFitter] 的「字号优先，装不下就滚动」同口径。
 *
 * ## 与「应用过什么」的关系
 *
 * [appliedRows] 是**已生效**的行数（-1 = 还没应用过）。调用方不必自己再存一份：
 * 量到与它相同的行数时 [accept] 恒返回 false（幂等），这也顺手挡掉了「同一行数重复应用」。
 *
 * 线程口径：只在渲染线程（`onDraw` / `pump` 那一侧）使用，无锁。
 */
class PageFitGate(
    /** 同一行数要连续量到几次才生效。2 = 防单帧误读；调大更稳但翻页后字号落得更慢。 */
    private val stableFrames: Int = DEFAULT_STABLE_FRAMES,
    /** 与 [appliedRows] 相差多少个行以内**不换字号**（重印节拍的摆动幅度；见类文档「口径」）。 */
    private val deadbandRows: Int = DEFAULT_DEADBAND_ROWS,
) {

    init {
        require(stableFrames >= 1) { "stableFrames 必须 ≥ 1，实际 $stableFrames" }
        require(deadbandRows >= 0) { "deadbandRows 不能为负，实际 $deadbandRows" }
    }

    /** 已生效的页行数；-1 = 还没应用过任何档位。 */
    var appliedRows: Int = -1
        private set

    /** 正在累积确认的行数；-1 = 没有待确认值。 */
    private var pendingRows: Int = -1

    /** [pendingRows] 已经连续被量到几次。 */
    private var pendingStreak: Int = 0

    /**
     * 是否还有待确认的行数（调用方据此决定「这一帧要不要再量一次」）。
     *
     * 存在理由：`refitIfPageChanged` 用「日志行数没变就早退」省掉每帧的 200 行扫描；
     * 一旦进入待确认态，日志行数可能恰好不再变化（页面静止），早退会让确认永远凑不满。
     */
    val hasPending: Boolean get() = pendingRows >= 0

    /**
     * 交一个本帧量到的行数，返回**是否应当在这次应用它**。
     *
     * @param rows `ConsoleScreen.pageExtentRows()` 的读数（调用方已把 0 折成兜底值）
     */
    fun accept(rows: Int): Boolean {
        if (withinDeadband(rows)) {
            // 已生效档位、或重印节拍摆出来的那点差：不换字号，也不留待确认值。
            pendingRows = -1
            pendingStreak = 0
            return false
        }
        if (rows == pendingRows) {
            pendingStreak++
        } else {
            pendingRows = rows
            pendingStreak = 1
        }
        if (pendingStreak < stableFrames) return false
        appliedRows = rows
        pendingRows = -1
        pendingStreak = 0
        return true
    }

    /** 本读数与**已生效**档位的差不超过 [deadbandRows]（`appliedRows < 0` = 还没应用过，恒 false）。 */
    private fun withinDeadband(rows: Int): Boolean =
        appliedRows >= 0 && abs(rows - appliedRows) <= deadbandRows

    companion object {
        /** 缺省确认帧数：2 帧 ≈ 33 ms（60 Hz），吃掉单帧误读；翻页无感。 */
        const val DEFAULT_STABLE_FRAMES: Int = 2

        /**
         * 缺省行数死区：2 行。
         *
         * 依据是上一波对同一段重印的代码读数（BOARD 2026-10-29「外出界面闪动」条）：`pageExtentRows()`
         * 在 N 与 **N±1** 之间摆（`UI_MESSAGE` 红字的出现/消失、末尾空行的归属），留一行余量。
         * TODO(verify)：真机复看地图屏 —— 若摆幅实测超过 2 行，这里的正确形态是「按节拍静默一段
         * 时间再换档」（需要调用方把时钟递给本闸），而不是凭感觉把死区调大。
         */
        const val DEFAULT_DEADBAND_ROWS: Int = 2
    }
}
