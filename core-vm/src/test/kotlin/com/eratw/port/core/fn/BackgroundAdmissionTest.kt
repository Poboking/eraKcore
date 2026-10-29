package com.eratw.port.core.fn

import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.SpritePool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BackgroundAdmission.admissible] 的五态判定表 + 边界。
 *
 * 期望值全部写死，基准是下沉前的唯一判定点 `app/.../GameHost.kt:913-918` 的原 lambda
 * （本波 P2b 把它下沉成 core 纯函数，判定式逐字等价），以及它引用的 C# 语义
 * （`EmueraConsole.cs:899`、`AppContents.cs:39-51`）。fixture 按两条真实生产路径构造：
 * - SPRITECREATE（`core/fn/GraphicsMethods.kt:782-787`）：静态 sprite，2 参 = rect 缺省
 *   整幅（:758），6/10 参 = 子矩形 + 缩放 destSize（:763-781）；
 * - SPRITEANIMECREATE（:883）：动画 sprite，frames 空表起步、不带 buffer。
 */
class BackgroundAdmissionTest {

    /** 建一块已创建的缓冲（像素内容与本判定无关，全 0 即可）。 */
    private fun buf(id: Int, w: Int, h: Int): GraphicsBuffer =
        GraphicsBuffer(id).apply { create(w, h) }

    @Test
    fun `态一 null 字典未命中判 false`() {
        // GameHost.kt:914 `s != null`；AppContents.GetSprite 未命中返回 null（AppContents.cs:46-50）。
        assertFalse(BackgroundAdmission.admissible(null))
    }

    @Test
    fun `态二a 静态 sprite 缺 buffer 判 false`() {
        // GameHost.kt:915 `s.isCreated` = buffer?.isCreated == true（GraphicsBuffer.kt:843），
        // buffer 缺失直接 false；:916 的 `g != null` 守卫是同一事实的第二道闸。
        // 语句路径不会产出这种 sprite（SPRITECREATE 要求 g.isCreated，GraphicsMethods.kt:755），
        // 但类型允许，原 lambda 显式兜住 —— 这里钉住防御分支不走样。
        val s = SpritePool.Sprite("NOBUF", 4, 4)
        assertFalse(BackgroundAdmission.admissible(s))
    }

    @Test
    fun `态二b 基图已释放判 false`() {
        // GDISPOSE 后 sprite 登记项不删、isCreated 转 false（ProcessRuntime.kt:2221-2222，
        // ASpriteSingle.cs:24-34 的 BaseImage.IsCreated）。SETBGIMAGE 打在释放之后与 PC 同判。
        val g = buf(1, 2, 2)
        val s = SpritePool.Sprite("DEAD", 2, 2, g, 0, 0, 2, 2)
        assertTrue(BackgroundAdmission.admissible(s), "前置：释放前整幅 src 应判过")
        g.dispose()
        assertFalse(BackgroundAdmission.admissible(s))
    }

    @Test
    fun `态三 动画 sprite 判 false`() {
        // GameHost.kt:915 `!s.isAnime`。动画 sprite 的 isCreated 恒 true（SpriteAnime.cs:54），
        // 必须显式排除；SPRITEANIMECREATE 起步就是空帧表（GraphicsMethods.kt:883）。
        val s = SpritePool.Sprite("ANIME", 2, 2, frames = ArrayList())
        assertFalse(BackgroundAdmission.admissible(s))
    }

    @Test
    fun `态三b 动画 sprite 即使同设 buffer 也判 false`() {
        // 类型允许 frames 与 buffer 同设；原 lambda 的 isAnime 在 isCreated 之后独立判
        // （GameHost.kt:915），动画身份一票否决，不靠 isCreated 间接挡。
        val s = SpritePool.Sprite("BOTH", 2, 2, buf(2, 2, 2), 0, 0, 2, 2, frames = ArrayList())
        assertFalse(BackgroundAdmission.admissible(s))
    }

    @Test
    fun `态四 部分矩形 src 判 false`() {
        // SPRITECREATE 6 参子矩形形态（GraphicsMethods.kt:763-773）：四个分量各错一个的
        // 变体全 false —— GameHost.kt:916 的四个等式缺一不可（AppContents.cs:80-89 的
        // SpriteG 子矩形；C# 里 is SpriteF 直接拒）。
        val g = buf(3, 4, 4)
        assertFalse(BackgroundAdmission.admissible(SpritePool.Sprite("SX", 1, 2, g, 1, 0, 3, 4)), "srcX 非 0")
        assertFalse(BackgroundAdmission.admissible(SpritePool.Sprite("SY", 2, 1, g, 0, 1, 4, 3)), "srcY 非 0")
        assertFalse(BackgroundAdmission.admissible(SpritePool.Sprite("SW", 2, 4, g, 0, 0, 3, 4)), "srcW 不足")
        assertFalse(BackgroundAdmission.admissible(SpritePool.Sprite("SH", 4, 3, g, 0, 0, 4, 3)), "srcH 不足")
    }

    @Test
    fun `态五 整幅 src 判 true`() {
        // SPRITECREATE 2 参：rect 缺省 = 0,0,g.width,g.height（GraphicsMethods.kt:758），
        // 即「全矩形参」—— 原版这是 SpriteG 会被 is SpriteF 拒，移植侧按全矩形参近似判过
        // （GameHost.kt:911-912 的近似声明；像素覆盖等价）。
        val g = buf(4, 3, 2)
        assertTrue(BackgroundAdmission.admissible(SpritePool.Sprite("FULL", 3, 2, g, 0, 0, 3, 2)))
        // 最小缓冲 1×1 的整幅也过：isCreated 只要求宽高 > 0（GraphicsBuffer.kt:129）。
        val one = buf(5, 1, 1)
        assertTrue(BackgroundAdmission.admissible(SpritePool.Sprite("ONE", 1, 1, one, 0, 0, 1, 1)))
    }

    @Test
    fun `边界 srcW 与 bufferW 同为 0 的退化全矩形判 false`() {
        // GameHost.kt:916 的覆盖等式在 0==0 时会「空真」，但 isCreated（buffer 宽高必须
        // 大于 0，GraphicsBuffer.kt:129）先短路 —— 证明等式不是判定的充分条件。
        // 直接构造 0×0 buffer + 全 0 src：没有 isCreated 闸的话这里会是 true。
        val never = GraphicsBuffer(6)
        assertFalse(BackgroundAdmission.admissible(SpritePool.Sprite("ZERO", 0, 0, never, 0, 0, 0, 0)))
    }

    @Test
    fun `边界 destSize 缩放不参与判定`() {
        // SPRITECREATE 10 参：src 整幅 + DestBaseSize 放大（GraphicsMethods.kt:778-781）。
        // 原 lambda 只看 src 覆盖（GameHost.kt:916），不读 width/height —— 判过。
        // C# 的 is SpriteF 会拒这个 SpriteG；这条用例把「全矩形参近似」的边界钉死，
        // 防止将来有人顺手把 destSize 加进判定而无声改行为。
        val g = buf(7, 2, 2)
        assertTrue(BackgroundAdmission.admissible(SpritePool.Sprite("SCALED", 4, 6, g, 0, 0, 2, 2)))
    }
}
