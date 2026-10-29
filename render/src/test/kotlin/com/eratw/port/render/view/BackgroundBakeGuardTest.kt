package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BackgroundBakeGuard] 的脏判定闸（纯 JVM）。
 *
 * 每条用例对齐的反编译基准都是 `EmueraConsole.cs`（MinorShift.Emuera.GameView）：
 * - 首次/尺寸变化才重建重烘 = ValidateBackground（`:923-936`，OnPaint 每帧调用 `:1815`）；
 * - 层内容变化重烘 = Add/Clear/Remove 之后的 BakeBackground 调用点
 *   （`:907`、`:914`、`:921`）；
 * - 稳态帧间不重采样 = OnPaint 只做 `DrawImage(bakedBackground, 0, 0)`（`:1816-1820`）。
 * 帧句柄换代重烘是本类的保守超集（原版持有 sprite 引用、等下次层变更才更新，
 * `:899-921` 之外无失效口；本侧绘制口径逐帧现取帧，不跟烘会停在旧像素），
 * 断言记的是本侧口径，出处见 BackgroundBakeGuard 的类 KDoc。
 */
class BackgroundBakeGuardTest {

    private fun placement(resource: String, depth: Int = 0, opacity: Int = 255) =
        GraphicsLayer.BgPlacement(resource, depth, opacity)

    private fun frame(handle: Any, srcX: Int = 0, srcY: Int = 0, srcW: Int = 100, srcH: Int = 50) =
        ImageFrame(handle = handle, srcX = srcX, srcY = srcY, srcW = srcW, srcH = srcH)

    private fun sourceOf(vararg pairs: Pair<String, ImageFrame?>): (String) -> ImageFrame? {
        val map = mapOf(*pairs)
        return { name -> map[name] }
    }

    private val bmpA = Any()
    private val bmpB = Any()

    @Test
    fun `首次必重烘且只有宽或高变化才再烘`() {
        // ValidateBackground :924-927（bakedBackground == null -> new + Bake）与
        // :929-934（宽高不匹配 -> Dispose + new + Bake）；宽高都没变走不到重烘。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        val source = sourceOf("bg" to frame(bmpA))
        assertTrue(guard.needsRebake(100, 50, layers, source), "首次必须重烘（:924-927）")
        guard.markBaked(100, 50, layers, source)
        assertFalse(guard.needsRebake(100, 50, layers, source), "尺寸不变不重烘（:928 的 else-if 不进）")
        assertTrue(guard.needsRebake(101, 50, layers, source), "宽变了重烘（:929 的 Width 判）")
        assertTrue(guard.needsRebake(100, 51, layers, source), "高变了重烘（:929 的 Height 判）")
    }

    @Test
    fun `稳态帧间不重烘`() {
        // OnPaint :1815-1820：ValidateBackground 过闸后只 DrawImage(bakedBackground,0,0)，
        // 同一帧序列反复画不触发任何重采样。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg", depth = 3, opacity = 128))
        val source = sourceOf("bg" to frame(bmpA))
        guard.markBaked(100, 50, layers, source)
        repeat(3) { n ->
            assertFalse(guard.needsRebake(100, 50, layers, source), "第 ${n + 1} 帧不该重烘")
        }
    }

    @Test
    fun `层换代内容不同必重烘`() {
        // Add/Clear/Remove 三条内容变化路径各调一次 BakeBackground（:907/:914/:921）。
        // 三者在本侧统一表现为「新列表实例且逐项内容不同」。
        val guard = BackgroundBakeGuard()
        val source = sourceOf("bg" to frame(bmpA), "bg2" to frame(bmpB))
        val first = listOf(placement("bg"))
        guard.markBaked(100, 50, first, source)
        // SETBGIMAGE 追加一层（AddBackgroundImage -> :907）
        val added = listOf(placement("bg"), placement("bg2", depth = -1))
        assertTrue(guard.needsRebake(100, 50, added, source), "追加一层重烘")
        // REMOVEBGIMAGE 换名（RemoveBackground -> :921）
        val removed = listOf(placement("bg2"))
        guard.markBaked(100, 50, removed, source)
        assertTrue(guard.needsRebake(100, 50, listOf(placement("bg")), source), "换名重烘")
        // CLEARBGIMAGE 再 SETBGIMAGE（Clear -> :914）
        guard.markBaked(100, 50, emptyList(), source)
        assertTrue(guard.needsRebake(100, 50, listOf(placement("bg")), source), "清空后再设重烘")
        // depth / opacity 变化同样算内容变化（BgPlacement 的另外两个字段）
        val withDepth = listOf(placement("bg", depth = 7))
        guard.markBaked(100, 50, withDepth, source)
        assertTrue(
            guard.needsRebake(100, 50, listOf(placement("bg", depth = 8)), source),
            "depth 变了重烘",
        )
        val withOpacity = listOf(placement("bg", opacity = 200))
        guard.markBaked(100, 50, withOpacity, source)
        assertTrue(
            guard.needsRebake(100, 50, listOf(placement("bg", opacity = 201)), source),
            "opacity 变了重烘",
        )
    }

    @Test
    fun `层换代但内容逐项相同不重烘`() {
        // CLEARBGIMAGE 后又 SETBGIMAGE 回同一组层：原版会无条件重烘（:907/:914 无条件调），
        // 但烘出的像素与已烘位图逐位相同，跳过重烘是行为级等价（BackgroundBakeGuard KDoc）。
        val guard = BackgroundBakeGuard()
        val source = sourceOf("bg" to frame(bmpA))
        val layers = listOf(placement("bg", depth = 2, opacity = 100))
        guard.markBaked(100, 50, layers, source)
        val sameContent = listOf(GraphicsLayer.BgPlacement("bg", 2, 100))
        assertFalse(guard.needsRebake(100, 50, sameContent, source))
    }

    @Test
    fun `帧句柄换代重烘`() {
        // 保守超集：ImageFrame 的约定是「帧内容变了就换新句柄」（其 KDoc），
        // SpriteBitmapSource 的位图缓存正是内容换了才换 Bitmap 引用（bitmapOf 的引用比较）。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        guard.markBaked(100, 50, layers, sourceOf("bg" to frame(bmpA)))
        assertTrue(
            guard.needsRebake(100, 50, layers, sourceOf("bg" to frame(bmpB))),
            "句柄换了重烘",
        )
    }

    @Test
    fun `帧源矩形变化重烘`() {
        // 句柄同（同一张拼图）但子矩形变了：帧内容同样变了，按 ImageFrame 约定换代。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        val beforeX = frame(bmpA, srcX = 0)
        guard.markBaked(100, 50, layers, sourceOf("bg" to beforeX))
        val afterX = frame(bmpA, srcX = 8)
        assertTrue(guard.needsRebake(100, 50, layers, sourceOf("bg" to afterX)), "srcX 变了重烘")
        val beforeH = frame(bmpA, srcH = 50)
        guard.markBaked(100, 50, layers, sourceOf("bg" to beforeH))
        val afterH = frame(bmpA, srcH = 60)
        assertTrue(guard.needsRebake(100, 50, layers, sourceOf("bg" to afterH)), "srcH 变了重烘")
    }

    @Test
    fun `帧缺失与出现双向重烘`() {
        // 缺帧层不进位图（绘制侧缺帧跳过的既有口径）；帧后来就绪/后来消失
        // 都是可见内容变化，必须重烘（:907 的层变更语义 + 逐帧现取的一致性）。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        val present = sourceOf("bg" to frame(bmpA))
        val absent = sourceOf()
        guard.markBaked(100, 50, layers, absent)
        assertTrue(guard.needsRebake(100, 50, layers, present), "帧从缺到有重烘")
        guard.markBaked(100, 50, layers, present)
        assertTrue(guard.needsRebake(100, 50, layers, absent), "帧从有到缺重烘")
        guard.markBaked(100, 50, layers, absent)
        assertFalse(guard.needsRebake(100, 50, layers, absent), "持续缺帧的稳态不重烘")
    }

    @Test
    fun `空层列表同样遵守首次与稳态口径`() {
        // CLEARBGIMAGE 之后（:909-913）：烘焙目标没有层；绘制侧空表直接不画，
        // 这里只钉判定本身：首次 true、稳态 false、列表再换内容（出现新层）true。
        val guard = BackgroundBakeGuard()
        val empty = emptyList<GraphicsLayer.BgPlacement>()
        assertTrue(guard.needsRebake(100, 50, empty, sourceOf()), "首次 true")
        guard.markBaked(100, 50, empty, sourceOf())
        assertFalse(guard.needsRebake(100, 50, empty, sourceOf()), "稳态 false")
        assertTrue(guard.needsRebake(100, 50, listOf(placement("bg")), sourceOf()), "出现新层 true")
    }
}
