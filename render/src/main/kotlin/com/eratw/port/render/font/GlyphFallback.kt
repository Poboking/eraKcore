package com.eratw.port.render.font

/**
 * **缺字回落链的唯一权威** —— 链的成员与顺序只在这里组装，两个消费者都读它。
 *
 * ## 为什么收成一处
 *
 * 「这一字形缺字时往哪一族找」原先在两个地方各写一遍：
 *
 * - **度量尺**（[com.eratw.port.render.glyph.FontWidthRuler.compute]）：逐跳问 sidecar
 *   （`fonts/<slug>-advance.txt`）有没有该码点这一行，首命中取它的 advance —— 否则
 *   「按哪一族的表推进」会和「画面由哪一族画出」分家，`SETFONT` 段整行错位；
 * - **绘制**（`:render-canvas` 的 `CanvasConsoleView.effectiveFamily`）：逐跳问
 *   `Paint.hasGlyph`，首命中即换 `Typeface`。
 *
 * 两处的**判据**只能各用各的（能力源不同：一边读随包 sidecar 的覆盖行，一边问平台字体栈），
 * 但**链的成员与顺序**必须同源，否则同一码位在度量侧落在第 2 跳、在绘制侧落在第 3 跳，
 * 屏上就是「按 A 族推进、用 B 族画」的错格。本对象把这一半收成纯函数，两处改为同源。
 *
 * ## 纪律
 *
 * - 只读 [BundledFontFamilies] 的登记表：**不持有可变状态、不缓存**（登记基数 8，
 *   每次新建 ≤3 元素列表，热路径上的代价可以忽略；缓存会引入「换表后链不更新」的隐性分叉）。
 * - [resolve] 自身逻辑不抛、永不返回 null；全落空返回请求族本体（屏上是 `.notdef` 方框，
 *   与 `BundledFontFamilies.resolve` 的「null 提前折成默认值」同一纪律）。谓词抛出的异常
 *   原样上抛 —— 「问不到」不等于「没有」，吞异常是调用方那一层的事。
 * - 本对象不知道「有没有该字形」：谓词由调用方给，链只回答「按什么顺序问」。
 *
 * ## 与原版的关系（不是复刻目标）
 *
 * 原版缺字靠 GDI 的 font linking 挂系统字体（`FontFactory.cs:33-50` 逐个
 * `fontFamily.Name == text` 匹配 —— 比较在 `:40`、匹配不到交 `new Font(text, ...)` 在 `:48`；
 * `:52-54` 异常后 `return null`），结果随机器装的
 * 字体变化。移植侧的「固定随包链」是 ADR-001 的确定性设计：整链只含随包族 ⇒ 同一份 APK
 * 在任何机器上缺字表现一致（链尾 [BundledFontFamilies.UNIFONT] 只差 47 个码点）。
 *
 * TODO(verify)：两处谓词（sidecar 有行 / `Paint.hasGlyph`）本波**不统一** —— 统一要有真机
 * 截图逐码位对账的读数支撑（哪些码点 sidecar 有行但平台字体栈没有字形，反之亦然）。
 * 卡点：本机 `adb devices` 为空，无真机像素读数；对账前把两处判据合成一个只会让其中一侧
 * 说谎，故本波只统一链的成员与顺序。
 */
object GlyphFallback {

    /**
     * 全链（请求族在**首位**，其后是 [BundledFontFamilies.fallbacksFor] 给的兜底段）。
     *
     * 成员按 [BundledFontFamily.id] 去重，顺序即优先级；每次调用新建列表（无缓存、无状态）。
     * 当前登记表下：`chainFor(Sarasa) = [Sarasa, Unifont]`、
     * `chainFor(Unifont) = [Unifont, Sarasa]`、其余登记族 `= [X, Sarasa, Unifont]`。
     *
     * @param requested 请求族（由 [BundledFontFamilies.resolve] 归一后的登记族）
     */
    fun chainFor(requested: BundledFontFamily): List<BundledFontFamily> {
        val chain = ArrayList<BundledFontFamily>(CHAIN_SLOTS)
        val seen = HashSet<String>(CHAIN_SLOTS)
        for (candidate in listOf(requested) + BundledFontFamilies.fallbacksFor(requested)) {
            if (seen.add(candidate.id)) {
                chain.add(candidate)
            }
        }
        return chain
    }

    /**
     * 按 [chainFor] 的顺序逐跳问 [canRender]，**首命中即返回**（命中之后不再调谓词）；
     * 全落空返回 [requested] 本体（`===`，不是等值的新实例，也不是 null）。
     *
     * 谓词抛异常原样向上抛（调用方自己决定要不要吞）：本对象不假装「问不到字形」等于
     * 「没有字形」，那是两件事 —— `CanvasConsoleView.hasGlyph` 已在它自己那一层把
     * `RuntimeException` 折成 `true`（保持旧行为）。
     *
     * @param requested 请求族（链首，也是全落空时的返回值）
     * @param canRender 该族能否画出这个字形；同一码位的所有跳共用同一个谓词
     */
    fun resolve(
        requested: BundledFontFamily,
        canRender: (BundledFontFamily) -> Boolean,
    ): BundledFontFamily {
        for (candidate in chainFor(requested)) {
            if (canRender(candidate)) {
                return candidate
            }
        }
        return requested
    }

    /**
     * 链的预分配槽位：登记 8 族时全链 ≤3（请求族 + 最多两跳兜底）；取 4 让 `HashSet`
     * 在 0.75 装载因子下不必扩容。只是预分配提示，不参与任何语义。
     */
    private const val CHAIN_SLOTS: Int = 4
}
