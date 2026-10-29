package com.eratw.port.render.view

/**
 * 可开关的帧统计（W8-RENDERPERF，**仅 Debug 打开**）。
 *
 * ## 为什么要它
 *
 * 「真机滚动不掉帧」需要一条可复现的读数，而不是靠手感：每次绘制结束打一行
 * `render.frame ms=<毫秒> draw=<绘制命令数> rows=<重绘行数>`。
 * `draw` 与 `rows` 直接对应当前帧的工作量（整屏重绘 vs 按行重绘两种形态的区别一眼可读），
 * `ms` 是同一帧从 `ConsoleScreen.refresh` 到画完的墙上时间。
 *
 * ## 与原版节流器的关系（2026-10-21 审计登记）
 *
 * 原版唯一的帧计时器是 `EmueraConsole.cs:156` 的 `Stopwatch _frameDeltaTimer`，
 * 用途不是**打点**而是**限帧**：`RefreshStrings` 在 :1758 用
 * `_frameDeltaTimer.ElapsedMilliseconds < msPerFrame` 直接丢弃这一帧的重绘请求，
 * `OnPaint` 末尾 :1803 再 `Restart()`。本类**不是**那条逻辑的移植，也不改变任何绘制决定
 * （它只读不写屏）；限帧那一路归显示列表/宿主侧，读数口径不同，别把两边的数字对照。
 * 本类因此没有逐行的 C# 依据 —— 它是移植工程自加的观测件（AGENTS.md 铁律 1 的例外只在这种
 * 「官方没有对应物」的件上成立，语义件一律要行号）。
 *
 * ## 怎么开关
 *
 * 默认**关**（[enabled] 为 false 时全部方法一次分支就返回，不取时钟、不拼串、不分配）。
 * 打开方式只有一条：构造一个 `enabled = true` 的实例，挂到 `ConsoleScreen` 的 `stats`
 * 参数上（`ConsoleFrame` 会共用同一个实例）。release 构建不要注入实例即可零成本；
 * debug 构建可以传 [fromProperty]（读系统属性 `eratw.render.stats`），
 * 或者直接用 `FrameStats(enabled = true, sink = { android.util.Log.d("eratw", it) })`
 * —— 输出目标是回调，本模块不 import 任何平台 API（AGENTS.md 铁律 4）。
 *
 * ## 状态与线程
 *
 * 全部状态是实例字段，无静态可变全局、无线程：按 ADR-003 只在渲染线程用。
 * [clock] 可注入，单测因此不依赖真实时钟也能钉住 `ms` 的取整口径。
 *
 * @property enabled 是否真的计时与输出；false 时本类不产生任何分配
 * @property sink 输出回调（只在 [enabled] 为 true 且一帧收尾时调用一次）
 * @property clock 单调时钟（纳秒）；默认 [System.nanoTime]
 */
class FrameStats(
    val enabled: Boolean = false,
    private val sink: (String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {

    /** 已收尾的帧数。 */
    var frames: Long = 0L
        private set

    /** 最近一帧的耗时（毫秒，向下取整）。 */
    var lastMs: Long = -1L
        private set

    /** 最近一帧的绘制命令数（[composeInto] 整屏 = 全部命令；[repaintRows] = 行段内的命令）。 */
    var lastDraw: Int = 0
        private set

    /** 最近一帧重绘的行数（整屏 = `rowCount`；按行重绘 = 各段行数之和）。 */
    var lastRows: Int = 0
        private set

    /** 最近一帧输出的那一行（[enabled] 为 false 时保持空串）。 */
    var lastLine: String = ""
        private set

    /** 本帧开始的时间戳（[beginFrame] 写入）；未 [beginFrame] 就 [endFrame] 时按 0 毫秒记。 */
    private var startedNanos: Long = 0L

    /** 是否已 [beginFrame] 且还没收尾。 */
    private var open: Boolean = false

    /**
     * 记一次帧起点。由 `ConsoleScreen.refresh`（提交侧）调用 —— `ms` 因此覆盖
     * 「提交 + 绘制」整帧，而不是只有画的那一段。
     */
    fun beginFrame() {
        if (!enabled) {
            return
        }
        startedNanos = clock()
        open = true
    }

    /**
     * 收尾一帧：算 `ms`、记读数、输出一行。
     *
     * @param draw 本帧绘制命令数
     * @param rows 本帧重绘行数
     * @return 输出的那一行；[enabled] 为 false 时返回 null（不拼串、不分配）
     */
    fun endFrame(draw: Int, rows: Int): String? {
        if (!enabled) {
            return null
        }
        // 没有配对的 beginFrame（例如只画不提交、或一帧里画了两次）时按 0 毫秒记，
        // 不拿上一次的起点算出一个假的长帧。
        val elapsed = if (open) clock() - startedNanos else 0L
        open = false
        val ms = if (elapsed < 0L) 0L else elapsed / NANOS_PER_MILLI
        frames++
        lastMs = ms
        lastDraw = draw
        lastRows = rows
        val line = format(ms, draw, rows)
        lastLine = line
        sink(line)
        return line
    }

    /** 丢掉未收尾的帧（尺寸变化 / 表面重建时调用：那一帧不算数）。 */
    fun discardFrame() {
        open = false
    }

    companion object {
        /** 打开统计的系统属性名（`-Deratw.render.stats=true`，只在 debug 构建里读）。 */
        const val STATS_PROPERTY: String = "eratw.render.stats"

        /** 一毫秒的纳秒数。 */
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * 帧统计的单行格式 —— **只有这一处**拼串，读日志的脚本因此不会随实现漂移。
         * 形如 `render.frame ms=3 draw=57 rows=54`。
         */
        fun format(ms: Long, draw: Int, rows: Int): String = "render.frame ms=$ms draw=$draw rows=$rows"

        /** 按系统属性 `eratw.render.stats` 决定开关（debug 构建用，release 别调）。 */
        fun fromProperty(sink: (String) -> Unit = {}): FrameStats =
            FrameStats(enabled = java.lang.Boolean.getBoolean(STATS_PROPERTY), sink = sink)
    }
}
