package com.eratw.port.render.view

/**
 * 绘制侧的取图口（M10-GFX）：显示列表里的 [com.eratw.port.render.displaylist.InlineGraphic.Image]
 * 段带的是**资源名**，像素由本接口在画的那一刻现取。
 *
 * ## 为什么名字进列表、像素不进
 *
 * 原版正是这个分工：`ConsoleImagePart` 构造时把名字查成 `ASprite`
 * （spec: `AppContents.cs:39-51` 的 `GetSprite`，调用点 `ConsoleImagePart.cs:60`），
 * 之后 `DrawTo` 只用手上那份引用画（`:159-184`）—— 显示节点里从来没有像素缓冲，
 * 像素住在 `AppContents` 的表里。移植侧同构：显示列表是**跨帧复用的保留缓存**
 * （`displaylist/DisplayList` 的「干净行复用同一个 DrawCmd 实例」不变量），
 * 把 `IntArray` 挂进缓存会让一张位图随缓存常驻，且 `SPRITECREATE` 覆盖同名 sprite 后
 * 缓存里还是旧像素。查表放在绘制侧，换代天然即时。
 *
 * ## 为什么 handle 是 `Any?`
 *
 * `:render` 是纯 JVM 模块（AGENTS.md 铁律 4：禁 Android API），不能出现 `Bitmap` 类型；
 * 而真像素在 Android 上就是 `android.graphics.Bitmap`。所以这里只约定「一个不透明句柄 +
 * 几何整数」，由消费方（`:render-canvas`）按自己的类型解释，生产方（`:platform`）负责造。
 * 几何字段是绘制侧裁剪与缩放所需的最小集，句柄本身不参与 [equals]：
 * 帧内容变了就换新句柄，绘制侧不缓存句柄里的东西。
 *
 * @property handle 像素源句柄（Android 上是 `android.graphics.Bitmap`；null = 画不出来）
 * @property srcX 源子矩形左沿（sprite 是拼图里的一块，spec: `AppContents.cs:80-105`）
 * @property srcY 源子矩形上沿
 * @property srcW 源子矩形宽（<= 0 时绘制侧按位图整幅处理）
 * @property srcH 源子矩形高
 * @property destW `ASprite.DestBaseSize.Width`；<= 0 = 脚本没指定，按源宽画
 * @property destH `ASprite.DestBaseSize.Height`；<= 0 = 脚本没指定，按源高画
 * @property basePosX `ASprite.DestBasePosition.X`（`SPRITECREATE` 第 7 参 `xpos`；动画恒 0）
 * @property basePosY `ASprite.DestBasePosition.Y`（第 8 参 `ypos`）
 * @property offsetX 动画**当前帧**的 `AnimeFrame.Offset.X`（静态恒 0）
 * @property offsetY 动画当前帧的 `AnimeFrame.Offset.Y`
 * @property anime 是否 `SpriteAnime`（决定落位算式走不带门的那一支，见 [GraphicGeometry.spriteDestRect]）
 */
class ImageFrame(
    val handle: Any?,
    val srcX: Int = 0,
    val srcY: Int = 0,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val destW: Int = 0,
    val destH: Int = 0,
    val basePosX: Int = 0,
    val basePosY: Int = 0,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val anime: Boolean = false,
) {
    val usable: Boolean get() = handle != null

    /**
     * 有效宽：优先 `DestBaseSize.Width`，脚本没给时退回源子矩形宽。
     *
     * 「没给就等于源宽」这一条有出处：不给 `destSize` 的那条 `CreateSpriteG` 重载
     * 把 `rect.Size` 原样当目的尺寸转发（spec: `AppContents.cs:91-94`），
     * 于是 `DestBaseSize` 与 `SrcRectangle` 的宽高是同一个矩形
     * （spec: `SpriteG.cs:18-22` → `ASpriteSingle.cs:43-48` → `ASprite.cs:13-25`）。
     * TODO(verify)：`ASprite` 构造里对**负**的 `DestBaseSize` 是取绝对值
     * （spec: `ASprite.cs:16-23`），本层的 `destW > 0` 判据会把负值当成「没给」退源宽；
     * 语料没有负的 `SPRITECREATE` 尺寸写法可钉，卡点＝量不到 PC 上这张图到底画多大。
     * 需要连负尺寸一起算的调用方走 `GraphicGeometry.inlineBox`（那条按
     * `ConsoleImagePart.cs:83-92` 取绝对值）。
     */
    val width: Int get() = if (destW > 0) destW else srcW

    /** 有效高：优先 `DestBaseSize.Height`，脚本没给时退回源子矩形高（同 [width] 的出处与负值说明）。 */
    val height: Int get() = if (destH > 0) destH else srcH
}

/**
 * 资源名 → 帧。实现方必须是**线程安全**的（VM 线程写 sprite 表、渲染线程读）。
 * 实现：`:platform` 的 `SpriteBitmapSource`。
 */
interface ImageFrameSource {
    /** 按资源名取一帧；查不到返回 null（绘制侧按原版口径什么都不画）。 */
    fun frame(resource: String): ImageFrame?
}
