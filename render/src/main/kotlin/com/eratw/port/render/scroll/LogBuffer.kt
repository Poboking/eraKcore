package com.eratw.port.render.scroll

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid

/**
 * 显示行环形缓冲（M4-SCROLL 存储侧，容量由构造参数给定；配置侧的 `MAXLOG` 经
 * [LogBuffer.fromConfig] 解析后传入，见「容量与淘汰」一节）。
 *
 * ## 存什么
 * 只存 `grid` 路产出的**整条** [DisplayRow]，一行一个槽位，本类**不合并、不改写行内容**。
 * PRINTN（`lineEnd = false`）留下的未结束行在这里就是一条普通整行；并入动作属于上层，
 * 入缓冲前完成 —— 原版 `addDisplayLine` 在 `displayLineList` 末行 `IsLineEnd == false` 时
 * 先 `deleteLine(1)`、再 `ShiftPositionX` + `ChangeStr` 把新旧按钮拼成一条新行后重新入列
 * （spec: `EmueraConsole.cs:2947-2968`，被删行由 `deleteLine` 负责，spec: `EmueraConsole.cs:2999-3051`）。
 * 本路只提供 [replaceLast] / [removeLast] 两个原语给上层做这件事。
 * `deleteLine` 里那套与 `MaxLog` 联动的补行（`Count == MaxLog - 1` 时补 `deletedLines`、
 * `Count == MaxLog - 2` 且 `lineNo > Count` 时把缓冲里的单行重新插回头部、收尾时
 * `Count == MaxLog` 再摘一行，spec: `EmueraConsole.cs:3022-3030`、`EmueraConsole.cs:3046-3050`）
 * 属于 DELETELINE / 临时行语义，**本路不建模**，需要时由上层用 [removeLast] + [append] 组合。
 *
 * ## 容量与淘汰
 * 追加后若保留数超过上限就丢最旧一行（spec: `EmueraConsole.cs:2988-2996`，
 * `displayLineList.Count > Config.MaxLog` 则 `RemoveAt(0)` 且 `deletedLines++`）。
 * 一次追加最多淘汰一行，所以稳态保留数恰等于 [maxLog]。
 * **[maxLog] 就是构造参数本身，本类不做任何夹取**：500 下限与 5000 默认都属于**配置解析层**，
 * 由伴生对象 [resolveMaxLog] / [fromConfig] / [withDefaultMaxLog] 承担。依据 —— 原版
 * `Config.SetConfig`（spec: `Config.cs:296`，方法体到 `Config.cs:486`）先在 `Config.cs:325`
 * 把 `MAXLOG` 读进 `Config.MaxLog`，再在同一次读取里把低于 500 的值弹 `TooSmallLogSize`
 * 配置错误框后抬成 500（spec: `Config.cs:451-455`）；默认 5000 来自 `ConfigItem` 注册行
 * （spec: `ConfigData.cs:62`）。夹取改的是**配置项的值**；而对应本类的 `displayLineList`
 * 自始至终只把 `Config.MaxLog` 当一个外来的数读（淘汰判据 spec: `EmueraConsole.cs:2988`、
 * 补行门槛 spec: `EmueraConsole.cs:3022`），它对自己的长度没有下限。
 * 换句话说：**被夹取的数与淘汰判据读的数是同一个**，所以夹取必须在交给缓冲**之前**发生，
 * 而不是在缓冲内部 —— 配置侧夹完后传进来的值即生效值，本类再夹一次就是双重政策。
 * 裸构造唯一的结构性前提是 [maxLog] 大于等于 1（0 与负数会让环形下标取模失去意义），
 * 那是数据结构的定义，不是配置政策，因此不跟 500 下限混为一谈。
 * 分析模式（`-a`）读配置时把 `MAXLOG` 强行改写成 10000（spec: `ConfigData.cs:569-572`），
 * 同样属配置侧改写；本类只用 [MAX_LOG_ANALYSIS_MODE] 记录该数，不自动改写，构造时由调用方自己传值。
 *
 * ## 行号口径（跨淘汰仍可寻址）
 * 每行有一个**绝对行号**（Long，从 0 递增）：
 *   * 分配时机与原版一致 —— 先 `line.LineNo = lineNo` 再 `lineNo++`
 *     （spec: `EmueraConsole.cs:2945` 与 `EmueraConsole.cs:2970`），
 *     所以第 n 个追加的行行号为 n-1，[totalLines] 就等于原版 `GetLineNo`（spec: `EmueraConsole.cs:507`）；
 *     折行的每一行各占一个行号，与逻辑行无关。
 *   * [firstIndex] = 最旧保留行的行号 = `totalLines - size`。原版靠它把「行号」换算成
 *     列表下标：`shift = (GetLineNo > Config.MaxLog) ? GetLineNo - Config.MaxLog : 0`
 *     然后 `LineNo - shift`（spec: `EmueraConsole.cs:1865-1868`、`EmueraConsole.cs:2402`），
 *     本类的 `totalLines - size` 与之逐项相等（淘汰后 `size == maxLog`）。
 *   * [firstIndex] 在 [append] / [removeLast] / [replaceLast] 下**单调不减**：
 *     未满时追加不移动它，满时每次追加 +1，删除尾行时 `totalLines` 与 `size` 同步回退
 *     同样多的排数（[removeLast] 按逻辑行整条出列，见其 KDoc）。
 *   * 有意不复刻原版的 32 位回绕：`lineNo == int.MaxValue` 时归零并 `lastDrawnLineNo = -1`
 *     （spec: `EmueraConsole.cs:2979-2983`）、`logicalLineCount == long.MaxValue` 时归零
 *     （spec: `EmueraConsole.cs:2984-2987`）。本类索引用 Long，归零分支实际不可达，
 *     归零会破坏上面这条单调性保证，故不建模（差异已写进交付报告）。
 *   * [clear] 把三个计数一起归零 —— 与原版 `ClearDisplay` 同口径
 *     （spec: `EmueraConsole.cs:2801-2813`：清空列表、`logicalLineCount = 0`、`deletedLines = 0`、`lineNo = 0`）。
 *
 * ## 逻辑行计数
 * [logicalLines] 对应原版 `LineCount` / `LINECOUNT` 口径：刚入列的行同时满足
 * `IsLogicalLine` 与 `IsLineEnd` 才 +1（spec: `EmueraConsole.cs:2971-2978`）。
 * 折行时只有首行带 `isLogicalLine`（spec: `PrintStringBuffer.cs:193,206`、
 * `ConsoleDisplayLine.cs:39-47`），中间行 `IsLineEnd` 默认为 true
 * （spec: `ConsoleDisplayLine.cs:16`），`IsLineEnd` 只在整批 flush 的**最后一行**被改写
 * （spec: `PrintStringBuffer.cs:183`），所以多行折出的一个逻辑行仍然只计 1。
 * 淘汰不回退该计数（spec: `EmueraConsole.cs:2988-2996` 只动 `deletedLines`）。
 *
 * 纯 JVM：无 Android API、无 `kotlin.random`、无静态可变状态（全部挂在实例上）。
 */
class LogBuffer(capacity: Int, configMaxLog: Int = capacity) {

    init {
        require(capacity >= 1) {
            "LogBuffer 的容量必须是正整数，实传 $capacity —— 500 下限属配置解析层（见 LogBuffer.fromConfig），" +
                "不在构造里夹"
        }
    }

    /**
     * 生效上限，同时就是环形容量，**等于构造参数 [capacity]，本类一字不夹**。
     * 配置侧的 500 下限与 5000 默认在 [fromConfig] / [resolveMaxLog] 里落地
     * （spec: `Config.cs:451-455`、`ConfigData.cs:62`）：夹取属于读取期，
     * 能走到构造器的值已经是生效值。调用方据此分配网格与滚动条范围。
     */
    val maxLog: Int = capacity

    /**
     * 配置原值（未经下限抬升），仅供诊断与配置回显（原版 `Dialog.Show` 提示框要按原值，
     * spec: `Config.cs:451-455`）。经 [fromConfig] 构造时是读到的配置数；裸构造时缺省等于 [maxLog]。
     */
    val requestedMaxLog: Int = configMaxLog

    /** 当前保留的显示行数（小于等于 [maxLog]；满时稳定等于 [maxLog]）。 */
    val size: Int
        get() = length

    /** 已追加的显示行总数 —— 原版 `GetLineNo`（spec: `EmueraConsole.cs:507`、`EmueraConsole.cs:2970`）。 */
    val totalLines: Long
        get() = appendCount

    /** 最旧保留行的绝对行号；空缓冲时等于 [totalLines]。单调不减（见类 KDoc）。 */
    val firstIndex: Long
        get() = appendCount - length

    /** 已被淘汰（丢最旧）的行数 —— 原版 `DeletedLines` / `deletedLines`（spec: `EmueraConsole.cs:511`、`EmueraConsole.cs:2995`）。 */
    val deletedLines: Long
        get() = firstIndex

    /** 逻辑行计数 —— 原版 `LineCount` / `logicalLineCount`（spec: `EmueraConsole.cs:509`、`EmueraConsole.cs:2971-2978`）。 */
    val logicalLines: Long
        get() = logicalCount

    private val slots: Array<DisplayRow?> = arrayOfNulls(maxLog)
    private var head = 0
    private var length = 0
    private var appendCount = 0L
    private var logicalCount = 0L

    /**
     * 追加一条显示行；超过 [maxLog] 时丢最旧（spec: `EmueraConsole.cs:2988-2996`）。
     * 传入的 [row] 原样保存，不做任何裁剪或合并。
     */
    fun append(row: DisplayRow) {
        val slot = (head + length) % maxLog
        slots[slot] = row
        if (length == maxLog) {
            // 满：slot 恰为 head，覆盖即「RemoveAt(0)」，head 前移一行
            head = (head + 1) % maxLog
        } else {
            length++
        }
        appendCount++
        if (row.isLogicalLine && row.isLineEnd) {
            logicalCount++
        }
    }

    /**
     * 删除末尾的**一个逻辑行**（上层的 PRINTN 并入与 DELETELINE 用；
     * spec: `EmueraConsole.cs:2999-3051`）。
     *
     * 直译 `deleteLine(1)` 的循环（spec: `EmueraConsole.cs:3007-3021`）：从尾部逐**显示行**
     * 出列，`num` 只在删到 `IsLogicalLine == true` 的逻辑行首排时 +1，循环因此在首排处
     * 收口 —— 折成多排的逻辑行**整条出列**，不是只删末排。每出一排 `lineNo--`
     * （spec: `EmueraConsole.cs:3013`，即 [totalLines] 逐排回退）；[logicalLines] 只在
     * 「逻辑行首排且 `IsLineEnd`」时回退一次（spec: `EmueraConsole.cs:3014-3020`），
     * 因为只有同时满足这两个标志的入列才计过数（spec: `EmueraConsole.cs:2971-2978`）。
     * [firstIndex] 保持不变：`totalLines` 与 `size` 同步回退同样多的排数。
     *
     * 粒度注意：本方法是**逻辑行**粒度，不是显示行粒度。按显示行数回撤的调用方
     * （例如快照差分的尾部回撤）必须按「逻辑行组数」计循环次数；一次调用删掉多排后
     * 若还按排数继续循环，会多删前面的行。与 `MaxLog` 联动的补行
     * （spec: `EmueraConsole.cs:3022-3030`、`:3046-3050`）本类仍不建模。
     *
     * @return 是否真的删掉了一行（空缓冲返回 false，对应原版 `displayLineList.Count != 0` 守卫，
     *   spec: `EmueraConsole.cs:3007`）
     */
    fun removeLast(): Boolean {
        if (length == 0) {
            return false
        }
        while (length > 0) {
            val slot = (head + length - 1) % maxLog
            val row = slots[slot]
            slots[slot] = null
            length--
            appendCount--
            if (row != null && row.isLogicalLine) {
                if (row.isLineEnd) {
                    logicalCount--
                }
                break
            }
        }
        return true
    }

    /**
     * 原地替换末行且**保留原绝对行号**（对应原版 PRINTN 并入后重新 `addDisplayLine`
     * 拿到的仍是刚 `deleteLine(1)` 让出的那个 `lineNo`，spec: `EmueraConsole.cs:2947-2968` + `3013`）。
     * [logicalLines] 按新旧两行的标志重算。
     *
     * @return 空缓冲返回 false
     */
    fun replaceLast(row: DisplayRow): Boolean {
        if (length == 0) {
            return false
        }
        val slot = (head + length - 1) % maxLog
        val old = slots[slot]
        slots[slot] = row
        if (old != null && old.isLogicalLine && old.isLineEnd) {
            logicalCount--
        }
        if (row.isLogicalLine && row.isLineEnd) {
            logicalCount++
        }
        return true
    }

    /**
     * 绝对行号取行；越界（含已被淘汰的号）返回 null。
     * 原版对应 `displayLineList[lineNo - shift]` 的取值路径
     * （spec: `EmueraConsole.cs:1865-1868`、`EmueraConsole.cs:2402`）。
     */
    operator fun get(index: Long): DisplayRow? {
        if (index < firstIndex || index >= appendCount || length == 0) {
            return null
        }
        return slots[(head + (index - firstIndex).toInt()) % maxLog]
    }

    /** 末行（最新一条）；空缓冲返回 null —— 原版 `LastLineIsTemporary` 的同款守卫（spec: `EmueraConsole.cs:513-524`）。 */
    fun last(): DisplayRow? = if (length == 0) null else slots[(head + length - 1) % maxLog]

    /**
     * 从绝对行号 [from] 起读至多 [count] 行，越界自动裁剪（返回条数可能少于 [count]，也可能为 0）。
     *
     * 裁剪口径抄原版绘制循环：起点 `if (num3 < 0) num3 = 0`（spec: `EmueraConsole.cs:1808-1811`），
     * 终点 `for (i = num3; i <= num2 && i < displayLineList.Count; i++)`
     * 即上界夹到列表长度（spec: `EmueraConsole.cs:1852`），
     * `num2` 越界时也先夹到 `Count - 1`（spec: `EmueraConsole.cs:1790-1793`）。
     *
     * @param from 绝对行号（与 [firstIndex] 同口径）；小于 [firstIndex] 时抬到 [firstIndex]，
     *   大于等于 [totalLines] 时返回空
     * @param count 期望行数；`<= 0` 直接返回空
     */
    fun rows(from: Int, count: Int): List<DisplayRow> = rowsAt(from.toLong(), count)

    /**
     * [rows] 的 Long 行号版本；超过 21 亿行的会话用这个
     * （原版 `lineNo` 是 `int`，见类 KDoc 的回绕说明）。
     */
    fun rowsAt(from: Long, count: Int): List<DisplayRow> {
        if (count <= 0 || length == 0) {
            return emptyList()
        }
        val start = if (from < firstIndex) firstIndex else from
        if (start >= appendCount) {
            return emptyList()
        }
        val wanted = start + count
        val endExclusive = if (wanted > appendCount || wanted < start) appendCount else wanted
        val n = (endExclusive - start).toInt()
        val out = ArrayList<DisplayRow>(n)
        var abs = start
        while (abs < endExclusive) {
            val row = slots[(head + (abs - firstIndex).toInt()) % maxLog]
            if (row != null) {
                out.add(row)
            }
            abs++
        }
        return out
    }

    /** 保留区内全部行，按行号升序（滚动与显示列表的默认读法）。 */
    fun all(): List<DisplayRow> = rowsAt(firstIndex, length)

    /**
     * 清空并让三个计数归零（原版 `ClearDisplay`，spec: `EmueraConsole.cs:2801-2813`）。
     * 清空后 [firstIndex] 与 [totalLines] 都回到 0，因此「单调不减」保证是从新的会话重新开始；
     * 上层若在 `ClearDisplay` 之后继续 [append]，行号会重新从 0 起算（与原版一致）。
     */
    fun clear() {
        var i = 0
        while (i < slots.size) {
            slots[i] = null
            i++
        }
        head = 0
        length = 0
        appendCount = 0L
        logicalCount = 0L
    }

    /**
     * 配置解析入口的产物（LOG-FLOOR：下限与默认都只在这里生效）。
     *
     * @property requested 读到的 `MAXLOG` 原值（缺省时为默认 5000，spec: `ConfigData.cs:62`）
     * @property effective 夹取后真正交给 [LogBuffer] 构造的容量；淘汰判据与滚动条都读它
     *   （spec: `EmueraConsole.cs:2988`、`EmueraConsole.cs:3022`）
     * @property raisedToFloor `requested` 低于 500 ⇒ 原版在此弹 `TooSmallLogSize` 配置错误框
     *   并把值改写成 500（spec: `Config.cs:451-455`）；上层据这个标志决定要不要提示用户，
     *   **文案里的数是 [requested] 而不是 [effective]**
     */
    data class MaxLogResolution(
        val requested: Int,
        val effective: Int,
        val raisedToFloor: Boolean,
    )

    companion object {
        /** 分析模式读配置时强改的 `MAXLOG`（spec: `ConfigData.cs:569-572`，`array[1] = "10000"`）。 */
        const val MAX_LOG_ANALYSIS_MODE = 10000

        /** 默认上限 5000（spec: `ConfigData.cs:62`）。 */
        const val MAX_LOG_DEFAULT = Grid.MAX_LOG_DEFAULT

        /** 配置侧下限 500（spec: `Config.cs:451-455`）；只被 [resolveMaxLog] 使用，构造器不读它。 */
        const val MAX_LOG_FLOOR = Grid.MAX_LOG_FLOOR

        /**
         * 把 `MAXLOG` 的配置值解析成生效容量 —— 复刻 `Config.SetConfig`（spec: `Config.cs:296`）
         * 里 `MaxLog = GetConfigValue(...)`（spec: `Config.cs:325`）之后那一段夹取
         * （spec: `Config.cs:451-455`）：低于 500 抬到 500，其余原样（原版没有上限）。
         *
         * @param configured 读到的配置值；`null` 表示配置项缺失，取默认 5000（spec: `ConfigData.cs:62`）
         */
        fun resolveMaxLog(configured: Int? = null): MaxLogResolution {
            val requested = configured ?: MAX_LOG_DEFAULT
            val raised = requested < MAX_LOG_FLOOR
            return MaxLogResolution(
                requested = requested,
                effective = if (raised) MAX_LOG_FLOOR else requested,
                raisedToFloor = raised,
            )
        }

        /** 用已解析好的 [MaxLogResolution] 建缓冲（上层要先按 [MaxLogResolution.requested] 弹框时用这条，避免二次解析）。 */
        fun fromConfig(resolution: MaxLogResolution): LogBuffer =
            LogBuffer(capacity = resolution.effective, configMaxLog = resolution.requested)

        /** [resolveMaxLog] + [fromConfig] 的合写：配置值（`null` = 缺省 5000）直接进缓冲。 */
        fun fromConfig(configured: Int? = null): LogBuffer = fromConfig(resolveMaxLog(configured))

        /** 用默认配置构造（5000，spec: `ConfigData.cs:62`；等价于 `fromConfig(null)`）。 */
        fun withDefaultMaxLog(): LogBuffer = fromConfig(null)
    }
}
