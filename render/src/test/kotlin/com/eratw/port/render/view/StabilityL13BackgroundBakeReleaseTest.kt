package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L13 稳定性波：背景烘焙判定的**释放口**（[BackgroundBakeGuard.invalidate]）。
 *
 * ## 被钉住的那条配对
 *
 * 绘制侧（`CanvasConsoleView`）在 `onDetachedFromWindow` 与 `releaseSession` 里把 `backing`
 * 位图交还给 GC，同时必须调本函数作废判定。两件事分开做时有一条**静默失败**路径：
 *
 * - 只丢位图、不作废：宽高、层列表、帧句柄都没变 ⇒ [BackgroundBakeGuard.needsRebake] 判
 *   false ⇒ 绘制侧拿不到位图就一直跳过贴图 ⇒ 背景永久消失，且**不抛异常**（不是崩溃，
 *   是再也画不出来），只有下一次尺寸变化才会自己好。
 * - 只作废、不丢位图：只是多一次重采样，看得见的像素不变（重烘输入相同）。
 *
 * 原版的对照：`EmueraConsole.cs:924-927` 的 ValidateBackground 在 bakedBackground 为 null
 * 时无条件重建重烘；`:929-934` 才是宽高闸。本函数把「位图没了」这件事显式地喂回判定，
 * 与那条 null 分支同形，不存在语义改动。
 *
 * ## 证伪手法
 *
 * 把 [BackgroundBakeGuard.invalidate] 的函数体清空（或删掉 `bakedLayers = null` 一行），
 * 用例 `作废后同一输入必重烘` 立刻失败（首次断言期望 true、实到 false）；
 * 恢复函数体后重新变绿。`释放位图却不作废会一直判稳态` 记录的是不做配对时的失败形态，
 * 它用的是同一个判定入口、同一组输入。
 */
class StabilityL13BackgroundBakeReleaseTest {

    private fun placement(resource: String, depth: Int = 0, opacity: Int = 255) =
        GraphicsLayer.BgPlacement(resource, depth, opacity)

    private fun frame(handle: Any, srcX: Int = 0, srcY: Int = 0, srcW: Int = 100, srcH: Int = 50) =
        ImageFrame(handle = handle, srcX = srcX, srcY = srcY, srcW = srcW, srcH = srcH)

    private fun sourceOf(vararg pairs: Pair<String, ImageFrame?>): (String) -> ImageFrame? {
        val map = mapOf(*pairs)
        return { name -> map[name] }
    }

    private val bmp = Any()

    @Test
    fun `作废后同一输入必重烘`() {
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg", depth = 2, opacity = 200))
        val source = sourceOf("bg" to frame(bmp))

        guard.markBaked(100, 50, layers, source)
        assertFalse(guard.needsRebake(100, 50, layers, source), "预热：稳态不该重烘")

        guard.invalidate()
        assertTrue(
            guard.needsRebake(100, 50, layers, source),
            "作废后即便宽高、层、帧句柄全同也必须重烘（否则位图已释放却不再烘焙）",
        )

        guard.markBaked(100, 50, layers, source)
        assertFalse(guard.needsRebake(100, 50, layers, source), "重烘登记后应恢复稳态")
    }

    @Test
    fun `作废可重复调用且未烘过也不抛`() {
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        val source = sourceOf("bg" to frame(bmp))

        guard.invalidate()
        guard.invalidate()
        assertTrue(guard.needsRebake(100, 50, layers, source), "未烘过时作废仍是首次重烘")

        guard.markBaked(100, 50, layers, source)
        guard.invalidate()
        guard.invalidate()
        assertTrue(guard.needsRebake(100, 50, layers, source), "连续作废保持「下次必烘」")
    }

    @Test
    fun `作废后换层也能正常重烘`() {
        // 作废清的是基线不是能力：之后重新登记，层换代 / 尺寸变化两条既有判据照常工作。
        val guard = BackgroundBakeGuard()
        val source = sourceOf("bg" to frame(bmp), "bg2" to frame(Any()))
        guard.markBaked(100, 50, listOf(placement("bg")), source)
        guard.invalidate()
        guard.markBaked(100, 50, listOf(placement("bg")), source)
        assertFalse(guard.needsRebake(100, 50, listOf(placement("bg")), source))
        assertTrue(guard.needsRebake(100, 50, listOf(placement("bg2")), source), "换层仍重烘")
        assertTrue(guard.needsRebake(101, 50, listOf(placement("bg")), source), "宽变化仍重烘")
    }

    @Test
    fun `释放位图却不作废会一直判稳态`() {
        // 失败形态的记录（不是要修的语义）：判定只看输入快照，不知道位图已经被释放。
        // 这条用例存在，是为了让「丢位图必须同时作废」的配对理由可执行地留在仓里。
        val guard = BackgroundBakeGuard()
        val layers = listOf(placement("bg"))
        val source = sourceOf("bg" to frame(bmp))
        guard.markBaked(100, 50, layers, source)
        // 模拟 onDetachedFromWindow 只丢了 backing（不调 invalidate 的旧写法）。
        repeat(3) {
            assertFalse(
                guard.needsRebake(100, 50, layers, source),
                "无作废时判定不会自己发现位图没了",
            )
        }
    }
}
