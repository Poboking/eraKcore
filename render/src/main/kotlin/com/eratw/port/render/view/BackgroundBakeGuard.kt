package com.eratw.port.render.view

/**
 * 背景烘焙的**脏判定**（纯 JVM，无 Android API；单测在 render 模块，绘制侧使用点见
 * CanvasConsoleView 的背景段）。
 *
 * 对齐原版的触发语义 —— 原版背景只有一张烘焙位图 bakedBackground，重采样只发生在两种时刻：
 * 1. **背景内容变化**：AddBackgroundImage / ClearBackgroundImage / RemoveBackground 各自在
 *    改完 backgroundList 后调一次 BakeBackground（spec: `EmueraConsole.cs:907`、`:914`、`:921`）；
 * 2. **首次或尺寸变化**：OnPaint 每帧先 ValidateBackground(clipW, clipH)（`:1815`），
 *    bakedBackground 为 null 或宽高不匹配才重建并重烘（`:923-936`）。
 * 之外的时刻 OnPaint 只做 `DrawImage(bakedBackground, 0, 0)` 的 1:1 整幅贴图（`:1816-1820`），
 * 帧间绝不重采样。本类就是这两条判定的纯逻辑形态：绘制侧每帧调 [needsRebake]，
 * 烘完调 [markBaked] 登记快照。
 *
 * 「背景内容变化」在本侧的判定载体：
 * - **层列表换代**：GraphicsLayer 的背景表是「写侧构造新列表后一次性替换字段」的不可变快照
 *   （见 GraphicsLayer.addBackground / removeBackground / clearBackground），所以**引用相同
 *   ⇒ 内容必同**（快路径，零逐项比较）；引用不同才逐项比较 resource / depth / opacity。
 *   内容逐项相同的新列表实例（例如 CLEARBGIMAGE 后又 SETBGIMAGE 回同一组层）不触发重烘：
 *   烘出来的像素本来就一样，与原版「无条件重烘」（`:907` 无条件调用）行为级等价。
 * - **帧换代**：层里只有资源名，像素由 ImageFrameSource 按名现取。ImageFrame 的约定是
 *   「帧内容变了就换新句柄」（见其 KDoc；SpriteBitmapSource 的位图缓存正是「内容换了才换
 *   Bitmap 引用」），所以逐层比较句柄引用与源矩形。常态（缓存命中）每帧每层只是一次查表
 *   加比较，零重采样。**帧换代触发重烘是比原版更勤的保守超集**：原版 ConsoleBackground
 *   持有 sprite 引用，同名 sprite 被覆盖后 bakedBackground 要等下一次层变更才更新
 *   （`:899-921` 之外没有别的失效口）；本侧绘制口径是逐帧现取帧，不跟着重烘屏上就会停在
 *   旧像素，与逐帧现取的既有可见结果不一致 —— 为守住「纯性能改动」判据选超集，
 *   偏离点已登记（见交付回执）。
 *
 * 线程约定：只供绘制线程（View.onDraw 一侧）串行调用；层列表本身的跨线程发布由
 * GraphicsLayer 的 volatile 快照承担。
 */
class BackgroundBakeGuard {

    /** 上次烘焙时各层的帧签名（missing = 该资源当时取不到帧，该层没烘进位图）。 */
    private class FrameSig(
        val missing: Boolean,
        val handle: Any?,
        val srcX: Int,
        val srcY: Int,
        val srcW: Int,
        val srcH: Int,
    )

    /** 上次烘焙的位图宽（0 = 还没烘过）。 */
    private var bakedWidth = 0

    /** 上次烘焙的位图高。 */
    private var bakedHeight = 0

    /** 上次烘焙的层列表快照（引用比较 = 层换代检测）。 */
    private var bakedLayers: List<GraphicsLayer.BgPlacement>? = null

    /** 与 [bakedLayers] 逐层对齐的帧签名。 */
    private val bakedFrames = ArrayList<FrameSig>()

    /**
     * 这一帧要不要重烘背景（对齐 `EmueraConsole.cs:923-936` 的 ValidateBackground 与
     * `:907`/`:914`/`:921` 的三个 BakeBackground 调用点；返回 false 时绘制侧只做 1:1 贴图，
     * 对齐 `:1816-1820` 的 DrawImage 路径）。
     *
     * @param width 烘焙目标宽（内容区宽）
     * @param height 烘焙目标高
     * @param layers 当前背景层（已按 depth 降序，见 GraphicsLayer.background）
     * @param frameAt 按资源名取帧的查表口（绘制侧就是 ImageFrameSource.frame）
     */
    fun needsRebake(
        width: Int,
        height: Int,
        layers: List<GraphicsLayer.BgPlacement>,
        frameAt: (String) -> ImageFrame?,
    ): Boolean {
        // 首次（ValidateBackground :924-927 的 null 分支）或尺寸变化（:929-934 的宽高分支）
        if (bakedLayers == null || bakedWidth != width || bakedHeight != height) {
            return true
        }
        val previous = bakedLayers!!
        val sameList = previous === layers
        if (!sameList) {
            if (previous.size != layers.size) {
                return true
            }
            for (i in layers.indices) {
                val old = previous[i]
                val now = layers[i]
                if (now.resource != old.resource || now.depth != old.depth || now.opacity != old.opacity) {
                    // 层换代：Add/Clear/Remove 之后的 BakeBackground（:907/:914/:921）
                    return true
                }
            }
        }
        // 帧换代检测（句柄引用 + 源矩形；ImageFrame 的「内容变了就换新句柄」约定）。
        // bakedFrames 与 previous 逐层对齐：走到这里时层数必与上次一致。
        for (i in layers.indices) {
            val frame = frameAt(layers[i].resource)
            val old = bakedFrames[i]
            if (frame == null) {
                if (!old.missing) {
                    return true
                }
                continue
            }
            if (old.missing ||
                frame.handle !== old.handle ||
                frame.srcX != old.srcX ||
                frame.srcY != old.srcY ||
                frame.srcW != old.srcW ||
                frame.srcH != old.srcH
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 登记刚烘完的快照（烘焙侧在重烘完成后调用；分配只发生在重烘帧，
     * 稳态帧路径 [needsRebake] 零分配）。
     */
    fun markBaked(
        width: Int,
        height: Int,
        layers: List<GraphicsLayer.BgPlacement>,
        frameAt: (String) -> ImageFrame?,
    ) {
        bakedWidth = width
        bakedHeight = height
        bakedLayers = layers
        bakedFrames.clear()
        for (placement in layers) {
            val frame = frameAt(placement.resource)
            bakedFrames.add(
                if (frame == null) {
                    FrameSig(missing = true, handle = null, srcX = 0, srcY = 0, srcW = 0, srcH = 0)
                } else {
                    FrameSig(
                        missing = false,
                        handle = frame.handle,
                        srcX = frame.srcX,
                        srcY = frame.srcY,
                        srcW = frame.srcW,
                        srcH = frame.srcH,
                    )
                },
            )
        }
    }
}
