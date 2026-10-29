package com.eratw.port.render.input

/**
 * 输入历史（↑↓ 召回）—— `MainWindow.cs` 那三个字段与两个方法的原样移植：
 * - `prevInputs`：`readonly string[] prevInputs = new string[100]`（`MainWindow.cs:52`），
 *   定长 100、初值全 null，当作**左移队列**用（不是环形：`updateInputs` 里逐格左移 :947-951）；
 * - `selectedInputs`：`int selectedInputs = 100`（:54），哨兵值 100 = 「不在任何历史上，框内是新内容」；
 * - `lastSelected`：`int lastSelected = 100`（:56），记住「上一次召回落在哪一格」，
 *   用于提交后又回按方向键时接着往上走（:952-959 与 :976-984 两支）。
 *
 * ## 谁在什么时候写它
 * 只有 [record]，且只有 `PressEnterKey` 调它（`MainWindow.cs:933`，在
 * `console.PressEnterKey` **之前**）。因此：
 * - 被核心层拒绝的输入（比如 `INPUT` 等整数却提交了非数字，`EmueraConsole.cs:1125-1128`
 *   的 `long.TryParse` 失败直接 `return false`；按钮整数 `BINPUT` 同判 :1144-1147）**照样进历史**，
 *   因为入历史发生在 `console.PressEnterKey` 之前（`MainWindow.cs:933` 对 :934）；
 * - 空串提交**不进历史**，而且早退时把 `selectedInputs` 留在原值上
 *   （:940-944 的 `if (string.IsNullOrEmpty(cur)) { box.Text = ""; return; }` 绕过了 :966 的重置）。
 *   这两条都是官方行为，按 ADR-005 原样复刻，不「顺手修好」。
 *
 * ## 与提交文本的关系
 * [record] 拿到的是**已经去掉单键等待前缀**后的文本（`MainWindow.cs:927-931` 先把
 * `last_inputed` 那段切掉再 :933 入历史），本类不做那一步，它属 [InputSession]。
 *
 * 纯 JVM、无时间、无线程；数组顺序即入队顺序，读数确定性（AGENTS.md 铁律 4）。
 *
 * @param capacity 原版写死 100（`MainWindow.cs:52`），形参只为单测能把移位看得清而开，
 *   默认值就是官方值；哨兵值随容量走（:945 用的是 `prevInputs.Length` 而非字面量 100，
 *   只有 :958 那一处是硬编码的 `100`，容量等于一百时两者同值）。
 */
class InputHistory(val capacity: Int = CAPACITY) {

    /** 定长槽位，null = 该槽从未写过（对应 C# `new string[100]` 的默认值）。 */
    private val slots: Array<String?> = arrayOfNulls(capacity)

    /** 当前召回位置；[CAPACITY] 表示「没在召回」。对应 `selectedInputs`（:54）。 */
    var selectedInputs: Int = capacity
        private set

    /** 上一次召回落点；[CAPACITY] 表示「无」。对应 `lastSelected`（:56）。 */
    var lastSelected: Int = capacity
        private set

    /** 历史读数（最旧在前，null 用空串表示），只给单测与绑定层看，不参与任何决策。 */
    fun snapshot(): List<String> = slots.map { it ?: "" }

    /**
     * 提交时记一次（= `updateInputs` `MainWindow.cs:938-967`）。
     *
     * @return 本应写回文本框的内容；[HistoryEdit.SetText] 的 `selectAll` 本函数一律为
     *   false（原版这两处只赋 `Text`，不动选区：:942、:965）。
     */
    fun record(current: String): HistoryEdit {
        if (current.isEmpty()) {
            // :940-944：空串只清空文本框并**提前返回**，selectedInputs 原样留着。
            return HistoryEdit.SetText("", selectAll = false)
        }
        val newestIndex = capacity - 1
        if (selectedInputs == capacity || current != slots[newestIndex]) {
            // :947-951：整体左移一格，末尾写入本次提交。
            for (i in 0 until capacity - 1) {
                slots[i] = slots[i + 1]
            }
            slots[newestIndex] = current
            // :952-959：若这条内容正好是「当前召回位的前一条」，就顺着往上收一格。
            if (selectedInputs > 0 && selectedInputs != capacity && current == slots[selectedInputs - 1]) {
                selectedInputs -= 1
                lastSelected = selectedInputs
            } else {
                lastSelected = capacity
            }
        } else {
            // :963：与末条相同 ⇒ 只把 lastSelected 拉回当前位。
            lastSelected = selectedInputs
        }
        selectedInputs = capacity
        // :965-966：赋空串，并把召回游标复位。
        return HistoryEdit.SetText("", selectAll = false)
    }

    /**
     * ↑/↓ 召回（= `movePrev` `MainWindow.cs:969-1004`）。
     *
     * @param move -1 = ↑（更早）、1 = ↓（更新）、0 = 原版直接返回（:971-974）。
     *   方向映射来自按键侧：`Keys.Up` 传 -1（`MainWindow.cs:1437`）、`Keys.Down` 传 1（:1445），
     *   两支都在 `!console.IsInProcess` 守卫内（:1435、:1443），且先 `SuppressKeyPress`（:1434、:1442）。
     * @return 要写回文本框的内容；三种不命中情形都返回 [HistoryEdit.None]
     *   （:989 的范围外、:996 的「末条为空且下一条就是哨兵」）。
     */
    fun recall(move: Int): HistoryEdit {
        if (move == 0) {
            return HistoryEdit.None
        }
        var step = move
        val num: Int
        if (lastSelected != capacity && selectedInputs == capacity) {
            // :976-984：刚从「新内容」提交完，第一次按 ↑ 不前进（-1 被抹成 0）。
            if (step == -1) {
                step = 0
            }
            num = lastSelected + step
            lastSelected = capacity
        } else {
            num = selectedInputs + step
        }
        if (num < 0 || num > capacity) {
            return HistoryEdit.None
        }
        if (num == capacity) {
            // :991-995：走到哨兵位 = 回到空框（只赋 Text，不动选区）。
            selectedInputs = num
            return HistoryEdit.SetText("", selectAll = false)
        }
        // :996 的 `!IsNullOrEmpty(prevInputs[num]) || ++num != Length`：
        // 槽位为空时**先自增再判**是否越到哨兵；越到哨兵则整条不响应。
        var target = num
        if (slots[target].isNullOrEmpty()) {
            target += 1
            if (target == capacity) {
                return HistoryEdit.None
            }
        }
        selectedInputs = target
        // :999-1001：赋文本 + 起点 0 + 整行选中。C# 在此处可能赋 null（→ WinForms 视作空串），
        // 本层显式用空串，避免把 null 漏进模型。
        return HistoryEdit.SetText(slots[target] ?: "", selectAll = true)
    }

    companion object {
        /** 原版硬编码的 100 格（`MainWindow.cs:52`），同时充当「不在召回态」的哨兵值。 */
        const val CAPACITY: Int = 100
    }
}

/**
 * 历史层要求写回文本框的动作（本包不直接持有控件，动作由 [InputSession] 落到
 * [EmueraTextBox]）。
 */
sealed class HistoryEdit {
    /** 什么都不做（原版那两条「不响应」支）。 */
    object None : HistoryEdit()

    /** 赋文本；[selectAll] 为真时补上 `SelectionStart=0` + `SelectionLength=Text.Length`（:1000-1001）。 */
    data class SetText(val text: String, val selectAll: Boolean) : HistoryEdit()
}
