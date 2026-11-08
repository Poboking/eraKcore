package com.eratw.port.render.view

import com.eratw.port.core.host.CbgFrameGeometry
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.sprite.SpriteAnimeTimeline

/**
 * CBG 图层的三条纯 JVM 原语：**单趟 depth 归并**、**动画帧几何**、**按钮取色图命中**。
 *
 * 放在 `render/view` 的依据与 [ScreenComposer] 同：本文件是[绘制]口径的下沉面，
 * `platform` / `render-canvas` / `tools/uipreview` 都依赖 `render`，反向不成立；
 * 真机与 headless 用同一份函数，才不会「预览对、真机错」。
 *
 * ## 为什么要有它（C 刀：CBG 与 div 必须同一次归并）
 *
 * 原版 `EmueraConsole.cs:1829-1873` 是**一个** while 循环，每轮取
 * `num7 = Math.Max(逃逸段头部 depth, cbgList 头部 zdepth)`（`:1836`），
 * 先判 `cbgList` 头（`:1837-1849`），再判 `num7 == 0` 画正文（`:1850-1857`），
 * 最后判逃逸段头（`:1858-1872`）—— 三者在**同一个** depth 序上归并，不是
 * 「先全部 CBG、再 div、再正文」的两段式。移植侧原先的两段式（`ScreenComposer.composeInto`）
 * 在「CBG depth=1 + div depth=2」这类形态下会画成 CBG1 → div2，与原版 div2 → CBG1 相反。
 * 本文件把这条归并收成一个纯函数，绘制侧只按它返回的次序落笔。
 *
 * ## 归并序（从先到后 = 从屏底到屏顶）
 *
 * - 三侧各自先按 depth **稳定降序**排（同 depth 保持入参序：core 的 `CbgPool.add` 用
 *   `sortByDescending`，`DivOverlayPlanner` 用 `sortByDescending`，两侧都已稳定）；
 * - 每一「轮」取三侧头的最大值 `d`，消费**至多一个** CBG 侧条目（CBG 优先于立绘，
 *   沿用既有口径）与**至多一个** div 条目 —— 同 depth 时 CBG 先于 div，依据是
 *   `EmueraConsole.cs:1837-1849` 的 `if` 在 `:1858-1872` 之前；
 * - 正文（[Step.Text]）落在 `textDepth`（= 0）那一轮：该轮先画本轮的 CBG 侧条目、
 *   再画正文、再画本轮的 div 条目。原版 `num7 == 0` 那轮正是这个次序
 *   （`:1837-1848` → `:1850-1857` → `:1858-1872`），而 `front` 侧的 depth 0 块
 *   因此压在正文之上（`DivOverlayPlanner` 把 `depth > 0` 归 behind、其余归 front，
 *   `DivOverlay.kt:190`）。
 * - **正文只画一次**：原版在每一轮 `num7 == 0` 都会重画正文（幂等，正文块不透明），
 *   移植侧线性化成一次。depth 0 的 CBG 层在原版被 `ArgumentOutOfRangeException.ThrowIfZero`
 *   拒绝（`EmueraConsole.cs:638` 与 `:668`），所以「正文之上再冒出一层 depth 0 CBG」
 *   不可达；depth 0 的立绘只出现在测试装置里（生产无 producer）。
 *
 * ## 与旧两段式的关系
 *
 * 无 div 且无 CBG 时本函数退化成「正文一条步骤」；只有立绘时退化成旧的两段
 * （z >= 0 → 正文 → z < 0）。这两条退化的像素结果与 `ScreenComposer.composeInto` 的
 * 旧快路径逐像素相同（单测对拍）。
 */
object CbgDrawOrder {

    /**
     * 归并后的**一步**绘制动作（从屏底到屏顶的顺序）。
     *
     * @property depth 这一步的 depth（正文 = `textDepth`）
     */
    sealed interface Step {
        val depth: Int

        /** 画一条 CBG 图层（`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE`）。 */
        data class Cbg(val layer: CbgLayerSnapshot) : Step {
            override val depth: Int get() = layer.depth
        }

        /** 画一条立绘摆位（`SPRITE*` 系列；与 CBG 同在原版 `cbgList` 里）。 */
        data class Sprite(val placement: SpritePlacement) : Step {
            override val depth: Int get() = placement.zdepth
        }

        /**
         * 画一颗 div 覆盖块。
         *
         * @property blockIndex 该块在 `DivOverlayFrame.behind + front` 拼接序里的下标 ——
         *   与 `ButtonLayer.DivFocus.blockIndex` / `ButtonLayer.divTargets()` 同一套编号，
         *   按下/焦点判定靠它比对，**不许**在归并里重排这个值（它跟块走，不跟绘制序走）。
         */
        data class Div(val block: DivOverlayPlanner.Block, val blockIndex: Int) : Step {
            override val depth: Int get() = block.depth
        }

        /** 画正文（一行行 [com.eratw.port.render.displaylist.DrawCmd]）。 */
        data class Text(override val depth: Int) : Step
    }

    /**
     * 单趟 depth 归并（见类 KDoc「归并序」）。
     *
     * @param cbgLayers CBG 图层快照（core 推来的已按 depth 降序；本函数不依赖该前提，自己再稳排）
     * @param divBehind `DivOverlayFrame.behind`（depth > 0，画在正文之前）
     * @param divFront `DivOverlayFrame.front`（depth <= 0，画在正文之后）
     * @param textDepth 正文槽的 depth（原版恒 0，`EmueraConsole.cs:1850`）
     * @param sprites 立绘摆位（可选；原版与 CBG 同在 `cbgList` 里参与同一次归并）
     */
    fun merge(
        cbgLayers: List<CbgLayerSnapshot>,
        divBehind: List<DivOverlayPlanner.Block> = emptyList(),
        divFront: List<DivOverlayPlanner.Block> = emptyList(),
        textDepth: Int = 0,
        sprites: List<SpritePlacement> = emptyList(),
    ): List<Step> {
        val cbg = sortedDesc(cbgLayers) { it.depth }
        val spr = sortedDesc(sprites) { it.zdepth }
        // div 的下标按 behind + front 拼接序固定下来（ButtonLayer 的 blockIndex 口径），
        // 排序只搬 (block, index) 这一对，不改 index。
        val divIndexed = ArrayList<Pair<DivOverlayPlanner.Block, Int>>(divBehind.size + divFront.size)
        for ((i, b) in divBehind.withIndex()) divIndexed.add(b to i)
        for ((i, b) in divFront.withIndex()) divIndexed.add(b to divBehind.size + i)
        val div = sortedDesc(divIndexed) { it.first.depth }

        var ci = 0
        var si = 0
        var di = 0
        var textDone = false
        val out = ArrayList<Step>(cbg.size + spr.size + div.size + 1)
        while (ci < cbg.size || si < spr.size || di < div.size) {
            // 三侧头里取最大；**空侧用 null 而不是 Int.MIN_VALUE 哨兵** —— depth 可以是
            // Int.MIN_VALUE（语料合法的「最上层」写法），拿它当「这一侧没有」会把那一层
            // 永远留在队里（死循环）。见 CbgDrawOrderTest 的 `IntMIN` 用例。
            val cd: Int? = if (ci < cbg.size) cbg[ci].depth else null
            val sd: Int? = if (si < spr.size) spr[si].zdepth else null
            val dd: Int? = if (di < div.size) div[di].first.depth else null
            val d = maxOf(cd ?: NO_DEPTH, sd ?: NO_DEPTH, dd ?: NO_DEPTH)
            // 全部层都已跨到正文槽之下 ⇒ 正文先画（整数档写法；原版靠 `cbgList` 里常驻
            // 一条 depth 0 的占位层触发 `num7 == 0`，`EmueraConsole.cs:574`）。
            if (!textDone && d < textDepth) {
                out.add(Step.Text(textDepth))
                textDone = true
            }
            // 本轮至多一个 CBG 侧条目：CBG 优先于立绘（同 depth 的并列序，见类 KDoc）。
            if (cd != null && cd == d) {
                out.add(Step.Cbg(cbg[ci]))
                ci++
            } else if (sd != null && sd == d) {
                out.add(Step.Sprite(spr[si]))
                si++
            }
            // 正文槽那一轮：本轮的 CBG 侧条目之后、本轮的 div 条目之前（原版 :1837-1872 的序）。
            if (!textDone && d == textDepth) {
                out.add(Step.Text(textDepth))
                textDone = true
            }
            if (dd != null && dd == d) {
                out.add(Step.Div(div[di].first, div[di].second))
                di++
            }
        }
        if (!textDone) out.add(Step.Text(textDepth))
        return out
    }

    // ------------------------------------------------------------------
    // 动画帧几何（A 刀）
    // ------------------------------------------------------------------

    /**
     * 解析一层 CBG 在 [elapsedMs] 时刻该用哪一帧的几何；null = 静态层或没有可画的帧。
     *
     * 取帧口径**复用** [SpriteAnimeTimeline.frameAt]（同一份 `% totaltime` + `<= 0` 闭区间，
     * 权威 `SpriteAnime.cs:108-117`），不在这里写第二份 —— 那个纯函数的单测
     * （`SpriteAnimeTimelineTest`）因此同时钉住本函数的帧序。
     *
     * @param elapsedMs 该层动画已播放的毫秒数；**null = 不推进**，用 DTO 自带的
     *   [CbgLayerSnapshot.frameIndex]（core 恒给首帧 = 原版 `lastFrame == -1` 分支，
     *   `SpriteAnime.cs:96-102`）。真机的逐帧推进由宿主喂时钟（见 [CbgAnimeClock]）。
     * @return 该帧的几何；[CbgLayerSnapshot.NO_FRAME] 或帧号越界时返回 null（整层不画）
     */
    fun frameGeometry(layer: CbgLayerSnapshot, elapsedMs: Long?): CbgFrameGeometry? {
        val frames = layer.frames
        if (frames.isEmpty()) return null
        val index = if (elapsedMs == null) {
            layer.frameIndex
        } else {
            val delays = IntArray(frames.size) { frames[it].delayMs }
            SpriteAnimeTimeline.frameAt(elapsedMs, delays)
        }
        if (index < 0 || index >= frames.size) return null
        return frames[index]
    }

    /**
     * 一层 CBG 的动画计时起点（原版 `SpriteAnime.StartTime` / `lastFrame`，
     * `SpriteAnime.cs:48-52`、`:96-102`）。
     *
     * 公共 API（不是内部件）的理由：这是**唯一**让 `CbgLayerSnapshot.frames` 真正逐帧推进的
     * 东西 —— core 的推送只在 `CBG*` 语句执行时发生（没有逐帧重推通道），所以「动画 CBG 会动」
     * 完全押在绘制侧喂时钟上。headless 的 [ConsoleFrame] 与真机的 Canvas 各持一份，
     * 语义逐字相同（起点 = 该层第一次被画的时刻）。
     *
     * 键 = [keyOf]（`depth:spriteName`）；层消失后由 [retainOnly] 剪掉，不随会话无限增长。
     * **非线程安全**：归绘制侧单线程独占（与 [ScreenComposer] / [ConsoleFrame] 的 ADR-003 口径）。
     *
     * @param timeSource 单调毫秒时钟（默认 `System.nanoTime()/1_000_000`；测试注入假时钟）
     */
    class CbgAnimeClock(private val timeSource: () -> Long = DEFAULT_TIME_SOURCE) {

        private val starts = HashMap<String, Long>()

        /** 该层已播放的毫秒数；第一次问它时记为起点并返回 0（原版首帧）。 */
        fun elapsedMsOf(layer: CbgLayerSnapshot): Long {
            val key = keyOf(layer)
            val now = timeSource()
            val start = starts.getOrPut(key) { now }
            return now - start
        }

        /** 只留下 [layers] 里还存在的键（层被清掉后释放起点，避免无界增长）。 */
        fun retainOnly(layers: List<CbgLayerSnapshot>) {
            if (starts.isEmpty()) return
            val live = HashSet<String>(layers.size)
            for (layer in layers) live.add(keyOf(layer))
            starts.keys.retainAll(live)
        }

        /** 清空全部起点（会话重启 / 帧失效）。 */
        fun reset() {
            starts.clear()
        }

        private companion object {
            /** 默认时钟：单调毫秒（不读墙钟，`System.nanoTime` 不受系统时间回拨影响）。 */
            val DEFAULT_TIME_SOURCE: () -> Long = { System.nanoTime() / 1_000_000L }

            /**
             * 层身份键。用 `depth + spriteName` 而不是图层对象：`CbgLayerSnapshot` 是 data class，
             * 每次推送都是新实例、`hashCode` 还带整张帧表 —— 当 Map 键既慢又会在「同样的层
             * 重推一次」时丢失起点（动画重头播）。名字 + depth 正是原版一张 `cbgList` 条目的
             * 可辨身份（同 depth 同名的两层在原版也不可区分，见 `ClientBackGroundImage` 无 id）。
             */
            fun keyOf(layer: CbgLayerSnapshot): String =
                "${layer.depth}:${layer.spriteName ?: "#gid${layer.gid}"}"
        }
    }

    // ------------------------------------------------------------------
    // 按钮取色图命中（B 刀）
    // ------------------------------------------------------------------

    /**
     * 一次 CBG 按钮命中（渲染侧像素坐标 → 命中层 + 取色图 gid + 取样点）。
     *
     * @property value 取色值 = `pixel.ToArgb() & 0xFFFFFF`（原版 `EmueraConsole.cs:1318`）
     * @property sampleX 取色图上的取样点 x（原点 = 取色图左上角）
     * @property sampleY 取色图上的取样点 y
     * @property buttonMapGid 命中所用的取色图 gid（= 快照里的 `buttonMapGid`）
     * @property layerIndices 命中的按钮层下标（`layers` 入参下标；`isButton &&
     *   buttonValue == value`，原版绘制时按同一条比对换 `spriteNameB`，`:1840-1843`）
     */
    data class CbgButtonHit(
        val value: Int,
        val sampleX: Int,
        val sampleY: Int,
        val buttonMapGid: Int,
        val layerIndices: List<Int>,
    )

    /**
     * 画布像素 → **取色图上的取样点**（原版 `MouseDown` / `MoveMouse` 的坐标换算）。
     *
     * 原版（`EmueraConsole.cs:1306-1313`、`:2273-2278`）：
     * ```
     * point2   = point;  point2.Y  -= ClientHeight;      // 画布 y → CBG 坐标（下沿为原点）
     * point3   = point2; point3.Y  += cbgButtonMap.Height;
     * if (0 <= point3.X < W && 0 <= point3.Y < H) 取色
     * ```
     * 即取色图与画布**底对齐**：`sampleY = py - canvasHeightPx + mapHeightPx`、
     * `sampleX = px`。取色图与画布等高时（常见形态）就是直取。
     *
     * @param px 画布像素 x（客户区左沿为 0）
     * @param py 画布像素 y（**顶部为 0**，向下增 —— 与原版 `PointToClient` 同向）
     * @param canvasHeightPx 画布高（原版 `ClientHeight` / `MainPicBox.Height`）
     * @param mapWidthPx 取色图宽（`cbgButtonMap.Width`）
     * @param mapHeightPx 取色图高（`cbgButtonMap.Height`）
     * @return 取色图上的取样点；越界（原版 `:1313` 的三条界判）返回 null = 不取色
     */
    fun buttonMapSamplePoint(
        px: Int,
        py: Int,
        canvasHeightPx: Int,
        mapWidthPx: Int,
        mapHeightPx: Int,
    ): IntArray? {
        if (mapWidthPx <= 0 || mapHeightPx <= 0) return null
        val sx = px
        val sy = py - canvasHeightPx + mapHeightPx
        if (sx < 0 || sy < 0 || sx >= mapWidthPx || sy >= mapHeightPx) return null
        return intArrayOf(sx, sy)
    }

    /**
     * 画布像素 → 取色图命中（**纯函数**：取色图像素由调用方按 [colorAt] 给）。
     *
     * 逐式对应原版鼠标按下（`EmueraConsole.cs:1300-1337`）：
     * 1. 有取色图（`cbgButtonMap != null && IsCreated`）才做（`:1309`）；
     * 2. 取样点越界 ⇒ 不命中（`:1313`）；
     * 3. `pixel.A == 255` 才算命中（`:1316`）—— 半透明/透明像素是「这里没有按钮」；
     * 4. 命中值 = `pixel.ToArgb() & 0xFFFFFF`（`:1318`）；
     * 5. 按下态换图：绘制侧拿这个值比对每层的 `buttonValue`（`:1840-1843`），
     *    本函数顺手把命中的层下标一起给出。
     *
     * @param colorAt 取色图像素读取口 `(x, y) -> ARGB`（宿主实现；canvas 用 `Bitmap.getPixel`，
     *   headless 用测试数组）。**只在取样点被调用一次**。
     * @param layers 当前 CBG 快照（取色图 gid 从第一条图层读 —— core 把它重复写进每条，
     *   见 `CbgLayerSnapshot.buttonMapGid` 的 KDoc；空表 = 没有按钮可取，返回 null）
     * @return 命中；未命中 / 没有取色图 / 取样点越界 一律 null
     */
    fun hitCbgButton(
        px: Int,
        py: Int,
        canvasHeightPx: Int,
        mapWidthPx: Int,
        mapHeightPx: Int,
        colorAt: (x: Int, y: Int) -> Int,
        layers: List<CbgLayerSnapshot>,
    ): CbgButtonHit? {
        if (layers.isEmpty()) return null
        val mapGid = layers[0].buttonMapGid
        if (mapGid < 0) return null
        val sample = buttonMapSamplePoint(px, py, canvasHeightPx, mapWidthPx, mapHeightPx) ?: return null
        val argb = colorAt(sample[0], sample[1])
        if ((argb ushr 24) != 0xFF) return null
        val value = argb and 0xFFFFFF
        val hits = ArrayList<Int>(2)
        for ((i, layer) in layers.withIndex()) {
            if (layer.isButton && layer.buttonValue == value) hits.add(i)
        }
        return CbgButtonHit(value, sample[0], sample[1], mapGid, hits)
    }

    /** 「这一侧没有更多条目」的哨兵；`Int.MIN_VALUE` 是合法 depth（语料确有 `zdepth = Int.MIN`）。 */
    private const val NO_DEPTH: Int = Int.MIN_VALUE

    /**
     * 稳定降序（同 depth 保持入参序）。
     *
     * 不直接用 `sortedByDescending`：`List.sort` 在 Kotlin 是稳定排序，语义上确实够，
     * 但入参往往**已经**有序（core 的 `CbgPool.add` / `DivOverlayPlanner` 都已排好），
     * 这里先判「本来就非递增」直接返回，热路径（每帧归并）零分配。
     */
    private fun <T> sortedDesc(items: List<T>, depthOf: (T) -> Int): List<T> {
        val n = items.size
        if (n <= 1) return items
        var ordered = true
        var i = 1
        while (i < n) {
            if (depthOf(items[i - 1]) < depthOf(items[i])) {
                ordered = false
                break
            }
            i++
        }
        if (ordered) return items
        return items.sortedByDescending(depthOf)
    }
}
