package com.eratw.port.render.view

/**
 * 保留面（retained surface）的每帧刷新判定 —— **纯逻辑，零 Android 依赖**，可在桌面 JVM 单测。
 *
 * ## 为什么要有这个类（2026-10-26 真机黑屏复盘）
 *
 * 安卓自绘 View 曾经直接把「本帧跳过没变的行」当成绘制逻辑：`onDraw` 里签名没变的行
 * 一个像素都不落（`CanvasConsoleView` 旧实现），前提是「屏上还留着上一帧的像素」。
 * 这个前提在安卓不成立 —— 所有失效点都是无参 [android.view.View.invalidate]，
 * 硬件加速下每帧重录整个 RenderNode，画布从空白开始；没落笔的地方透出
 * `setBackgroundColor(Color.BLACK)` ⇒ 黑。两个真机症状同源于此：
 *
 * 1. 点选项后 `submitInput` 无条件置脏并要求重绘，而这一刻 `revision` 未变、版面未变
 *    ⇒ 判定为「零行要画」⇒ 整屏黑，直到该回合脚本跑完出新内容才恢复；
 * 2. 色付き地图每 tick 原地重印整页，行下标稳定 ⇒ 只有含动画格的行签名变
 *    ⇒ 其余行被跳过 ⇒ 大面积黑，只剩「会动的地图字符」。
 *
 * 原版没有这个坑，因为它是**纯即时全量重绘**：每次 paint 先 `graph.Clear(bgColor)`
 * 再把视口内每一行重画一遍（spec: `EmueraConsole.cs:1816` 与 `:1852-1856`），
 * 像素从不作为状态载体。所以「某行没变化」在原版不是一个分支。
 *
 * ## 本类的口径
 *
 * 增量的正确载体是**我们自己持有的那张屏幕位图**（调用方的 surface），不是屏幕本身：
 *
 * - **[plan]** 只回答一件事：**这一帧哪些行需要重新栅格化进 surface**；
 * - **贴出（blit）永远是整幅、每帧必做** —— 这条不写在返回值里，是因为它不该有例外：
 *   调用方拿到 plan 后无论如何都要把 surface 整体贴到传入的 Canvas 上。
 *   「不贴」正是黑屏的定义。判定测试见 `ScreenSurfacePlannerTest`。
 *
 * 于是稳态帧的成本 = 「重栅格化 1-2 行」加「一次整幅位图拷贝」，既保住增量的性能，
 * 又不再依赖平台帮我们留像素。
 *
 * ## 必须整幅重栅格化（[full]）的时机
 *
 * 任何「surface 里的内容与当前版面不再一一对应」的事件：
 *
 * - **[notifySurfaceLost]**：surface 刚建 / 尺寸或内边距变化后重建 / View 从窗口摘掉 —— 里面是脏数据；
 * - 版面指纹变化：滚动（`minRow`）、行顶与行高、内容区尺寸、按下态 —— 同一行换了位置，跳过会留错位残影；
 * - 可见窗口本身变化（起点行号或行数）—— 判定器自己兜底，**不信任调用方的指纹一定含这两项**：
 *   漏了就整幅，代价只是多画一帧，不会把「另一个位置的第 i 行」当成同一行；
 * - 含内联图形：图片/色块高度可以超行高并有意溢出到相邻行，逐行 clip 会切出带状断层
 *   （2026-10-24 真机实证：标题图错位横带）⇒ 走整幅。**在场即整幅**：绘制路径对含图形的帧
 *   无条件全画，判定结论必须如实说「这帧是整幅」，否则取证读数会低报；
 * - 有背景图层：背景在行之下，逐行重画会盖掉它 ⇒ 保守走整幅（与旧实现同口径）；
 * - **显式置脏**（旧实现里的 `forceRedraw`：`requestRedraw` / `notifyGraphicsChanged`）
 *   —— 旧代码写了这个标志却从不读它，「置脏即整屏重画」是空头承诺，本类把它接上。
 *
 * 注意最后一条与症状 1 的关系：置脏**不再**意味着「必须重画可见行」，
 * 它只意味着「必须重栅格化」；而画面本身靠每帧整幅贴出来保底，
 * 所以内容没变的置脏帧 = 重画一遍同样的内容 = 屏幕上还是原来那一屏（与原版等待期保留上一屏同形）。
 */
class ScreenSurfacePlanner {

    /**
     * 上一次栅格化时的可见窗口：起点行号 加 逐行内容签名。
     *
     * 只留**一屏**（不是全历史按行号的字典）：指纹相等时可见窗口必然相等，增量帧只可能
     * 与上一帧同窗口比较；窗口一变就整幅（见 [plan]），基线随整幅帧重建。因此这里既不
     * 需要按历史行号累加（内存有界），也不走 `HashMap<Int, Int>`（行号几乎都大于 128，
     * 每帧每行都要装箱一次，与稳态零分配的契约冲突）。
     */
    private var prevMinRow = Int.MIN_VALUE
    private var prevSigs = IntArray(0)
    private var prevCount = 0

    /** 上一次栅格化时的版面指纹；任何不等都判 [full]。 */
    private var lastFingerprint = Fingerprint_unset

    /** 上一次判定是否含内联图形（图形进出 surface 的裁剪口径不同）。 */
    private var lastGraphic = true

    /** 上一次判定是否有背景层（**撤背景**同样必须整幅：留在面上的背景像素没人清）。 */
    private var lastBackground = true

    /** [raster] 的复用缓冲；有效长度 = 最近一次 [plan] 的行数。 */
    private var raster = BooleanArray(0)

    /**
     * 最近一次 [plan] 的结论：本帧要不要整幅重栅格化。
     *
     * `@Volatile`：读它的取证口（DevTools `stats`）在别的线程，量级允许旧值但不该看不见。
     */
    @Volatile
    var full: Boolean = true
        private set

    /** 最近一次 [plan] 的行数（[raster] 的有效长度）。 */
    @Volatile
    var rowCount: Int = 0
        private set

    /** 取证读数：自 [resetStatistics] 以来累计的整幅帧数与增量帧数。 */
    @Volatile
    var fullFrames: Long = 0L
        private set
    @Volatile
    var incrementalFrames: Long = 0L
        private set
    /** 取证读数（累计）：重栅格化过的行数总和。 */
    @Volatile
    var rasteredRows: Long = 0L
        private set
    /** **本帧**重栅格化的行数（0 是合法值，但绝不允许因此不贴图 —— 见类文档）。 */
    @Volatile
    var lastRasteredRows: Int = 0
        private set

    /** 清零累计读数（DevTools 的 `stats` 每次读完调它，口径同帧耗时分解）。 */
    fun resetStatistics() {
        fullFrames = 0L
        incrementalFrames = 0L
        rasteredRows = 0L
    }

    /** surface 被丢弃或重建（尺寸变化、重新附着）后调用：下一帧必然 [full]。 */
    fun notifySurfaceLost() {
        lastFingerprint = Fingerprint_unset
        prevMinRow = Int.MIN_VALUE
        prevCount = 0
        lastGraphic = true
        lastBackground = true
    }

    /** 全部状态作废（例如装配了新的 ConsoleScreen）。 */
    fun reset() = notifySurfaceLost()

    /**
     * 本帧判定。
     *
     * @param minRow 本帧第一个可见行的绘制口径行号
     * @param sigs 长度 ≥ [rowCount] 的复用缓冲，`sigs[i]` = 第 i 个可见行的内容签名
     *   （调用方按行算，口径同旧实现的 `rowSignature`：字形内容 加 颜色 加 样式 加 按下态）
     * @param rowCount 本帧可见行数
     * @param fingerprint 整帧版面指纹（行顶/行高/内容区/按下态）
     * @param graphicPresent 本帧命令表里是否有内联图形段
     * @param backgroundPresent 是否有背景图层
     * @param forced 显式置脏（`requestRedraw` / `notifyGraphicsChanged`）
     * @return 复用缓冲：`result[i] = true` ⇒ 第 i 个可见行需要重新栅格化进 surface
     */
    fun plan(
        minRow: Int,
        sigs: IntArray,
        rowCount: Int,
        fingerprint: Int,
        graphicPresent: Boolean,
        backgroundPresent: Boolean,
        forced: Boolean,
    ): BooleanArray {
        if (raster.size < rowCount) raster = BooleanArray(rowCount.coerceAtLeast(16))
        if (prevSigs.size < rowCount) prevSigs = IntArray(rowCount.coerceAtLeast(16))
        // 窗口（起点行号 / 行数）变了就不能拿上一帧的签名比：同一个「第 i 行」已经换了位置。
        // 判定器不假设调用方的指纹一定包含这两项 —— 漏了就整幅，最多多画一帧。
        val windowChanged = minRow != prevMinRow || rowCount != prevCount
        val needFull = forced ||
            backgroundPresent ||
            // 含内联图形：绘制路径无条件整幅全画（图片可溢出到相邻行），本帧就是整幅帧。
            graphicPresent ||
            graphicPresent != lastGraphic ||
            backgroundPresent != lastBackground ||
            fingerprint != lastFingerprint ||
            lastFingerprint == Fingerprint_unset ||
            windowChanged
        full = needFull
        this.rowCount = rowCount
        if (needFull) {
            fullFrames += 1L
            lastFingerprint = fingerprint
            lastGraphic = graphicPresent
            lastBackground = backgroundPresent
            for (i in 0 until rowCount) {
                raster[i] = true
                prevSigs[i] = sigs[i]
            }
            for (i in rowCount until raster.size) raster[i] = false
            prevMinRow = minRow
            prevCount = rowCount
            rasteredRows += rowCount.toLong()
            lastRasteredRows = rowCount
            return raster
        }
        incrementalFrames += 1L
        var drawn = 0L
        for (i in 0 until rowCount) {
            val sig = sigs[i]
            if (prevSigs[i] != sig) {
                raster[i] = true
                prevSigs[i] = sig
                drawn += 1L
            } else {
                raster[i] = false
            }
        }
        for (i in rowCount until raster.size) raster[i] = false
        rasteredRows += drawn
        lastRasteredRows = drawn.toInt()
        return raster
    }

    /** 第 [index] 个可见行本帧是否需要重栅格化（越界视为 false）。 */
    fun needsRaster(index: Int): Boolean = index in 0 until rowCount && raster[index]

    /** 取证读数：签名基线覆盖的可见行数（内存有界，恒等于上一帧的行数）。 */
    fun trackedRows(): Int = prevCount

    private companion object {
        /** 哨兵值：0 是合法指纹，所以用 Int.MIN_VALUE 表示「还没画过」。 */
        const val Fingerprint_unset = Int.MIN_VALUE
    }
}
