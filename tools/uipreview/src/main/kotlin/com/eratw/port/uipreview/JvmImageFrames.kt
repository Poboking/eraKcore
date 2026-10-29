package com.eratw.port.uipreview

import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SpritePool
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 解码后的位图像素（行主序 `0xAARRGGBB`，与 `Raster.pixels` 同构）。
 * 作为 [ImageFrame.handle] 的承载类型：render 侧只约定「不透明句柄」（ImageFrameSource.kt
 * 的 KDoc），本工具侧自己解释它。
 */
class DecodedPixels(val pixels: IntArray, val width: Int, val height: Int)

/**
 * 桌面侧的按名取图口（CONTRACT-UP2 §4.4）：[ImageFrameSource]（绘制侧现取像素）+
 * [HtmlRunFlatten.ImageSizeSource]（`<img>` 缺 width 时按纵横比推宽）双面实现。
 *
 * ## 查表顺序与 platform 同构
 *
 * 逐式对齐 `platform/.../image/SpriteBitmapSource.frameOf`（SpriteBitmapSource.kt:155-163）：
 * 1. **sprite 表**：`store.aux` 的 `"sprite"` 槽（`SpritePool`，SPRITECREATE /
 *    SPRITEANIMECREATE 写入；槽名常量与 GraphicsMethods 同名约定）；未命中才走第 2 层。
 *    已释放/被覆盖的死条目（`isCreated == false`）不画 —— 原版每条绘制路径的
 *    `IsCreated` 守卫（`SpriteTable.kt` 类文档复刻条款 5；`AppContents.cs:44-53`）。
 *    **动画 sprite 取第 0 帧静态化**：原版 `GetCurrentFrame` 是宿主时钟驱动的逐帧推进
 *    （SpriteAnime.cs:90-119），桌面泵没有动画时钟，本层恒取首帧；局限：SETANIMETIMER
 *    的重绘节奏与逐帧动画在预览里不动（`TODO(verify)`：真机对拍动画节奏后再决定
 *    要不要给泵线程加时钟）。
 * 2. **文件兜底**：按名扫描游戏根候选目录（`resources`/`img`/`dat`，`AppContents.cs:109-115`
 *    的 `Program.ContentRoot`；扩展名 `png webp jpg jpeg bmp gif`、下钻深度见 [MAX_DEPTH]、
 *    stem 与全名双索引小写、拒绝 `..` —— GameImageFiles.buildIndex/locate 的口径逐条同式）。
 *    解码用 `javax.imageio`：**webp 解不了**（JDK 无内置 webp reader）→ null → 绘制侧
 *    走 AltText/空块降级；gif 取 ImageIO 默认读出的首帧。
 *
 * ## 与原版的差异（如实登记）
 *
 * 原版 sprite 查不到时把 `<img>` 标签当文本画（ConsoleImagePart.cs:60-64），没有文件兜底层；
 * platform 侧按「用户可见的降级，不是脚本可见行为」补了同名文件层（GameImageFiles 类文档），
 * 本层与 platform 同口径 —— 差别只是「文本标签」变成「图」。
 *
 * ## 线程
 *
 * 主消费者是泵线程（绘制 + ConsoleFeed 展平询宽）；VM 线程经 [backgroundAdmissible] 谓词
 * （SETBGIMAGE 语句期）与 `HtmlRunFlatten` 也会读 ⇒ 索引与解码缓存用
 * [ConcurrentHashMap]，GraphicsBuffer 位图缓存按 `(id, pixels 数组引用)` 换代
 * （SpriteBitmapSource.bitmapOf:244-257 同判：数组引用换了才重建）。竞态下两线程可能各解
 * 一份等价位图，后到者被覆盖，无结构破坏（不为小概率竞态加锁）。
 *
 * @param gameRoot 游戏根（语料只读铁律：本类只读，不写任何文件）
 * @param aux 本 VM 的附属状态容器（只 `peek` 不 `slot`：查图不该顺手建表）
 */
class JvmImageFrames(
    private val gameRoot: Path,
    private val aux: RuntimeAux,
) : ImageFrameSource, HtmlRunFlatten.ImageSizeSource {

    /** 文件层解码缓存：资源名 → 解码产物（先查缓存再解码，GameImageFiles/SpriteBitmapSource 同纪律）。 */
    private val fileCache = ConcurrentHashMap<String, DecodedPixels>()

    /** 资源名 → 文件的索引（lazy 建一次；首次触发可能在 VM 线程或泵线程，lazy SYNCHRONIZED 兜住）。 */
    private val fileIndex: Map<String, File> by lazy { buildIndex() }

    /** GraphicsBuffer → 解码位图缓存：键 = gid，值 = (pixels 数组引用, 产物)；引用换了才重建。 */
    private val bufferCache = ConcurrentHashMap<Int, Pair<IntArray, DecodedPixels>>()

    /**
     * 按资源名取一帧（[ImageFrameSource] 实现口）。
     * 句柄 = [DecodedPixels]（整幅基图像素）；子矩形与 `DestBaseSize` 随 [ImageFrame] 几何字段走。
     */
    override fun frame(resource: String): ImageFrame? {
        if (resource.isEmpty()) return null
        spriteFrame(resource)?.let { return it }
        val decoded = fileFrame(resource) ?: return null
        return ImageFrame(
            handle = decoded,
            srcX = 0,
            srcY = 0,
            srcW = decoded.width,
            srcH = decoded.height,
            destW = 0,
            destH = 0,
        )
    }

    /**
     * `<img>` 缺 `width` 时按纵横比推宽所需的基图目标尺寸（[HtmlRunFlatten.ImageSizeSource] 实现口）。
     * 打包式与 `SpriteBitmapSource.packDestSize`（SpriteBitmapSource.kt:310-311）同式：
     * 高 16 位 = 宽、低 16 位 = 高。sprite 命中给 `DestBaseSize`（`SpriteBitmapSource.destSize:173-178`
     * 同源）；文件命中给整图尺寸（文件图无 DestBaseSize 概念，整图即基图）。
     */
    override fun destSize(resource: String): Int? {
        if (resource.isEmpty()) return null
        aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource)?.let { sprite ->
            return packDestSize(sprite.width, sprite.height)
        }
        val decoded = fileFrame(resource) ?: return null
        return packDestSize(decoded.width, decoded.height)
    }

    /**
     * 背景层准入谓词（[com.eratw.port.render.view.GraphicsLayer.backgroundAdmissible] 用，
     * 原版准入判据 = `AppContents.GetSprite(name) is SpriteF`，EmueraConsole.cs:899）。
     *
     * 工具口径的 SpriteF 近似 = 「整幅基图」：静态 sprite 且 src 子矩形覆盖整张基图
     * （SpriteG 是子矩形、SpriteAnime 是动画，都不算 —— GameImageFiles.kt 类文档的同款判据），
     * 或文件层整图命中。`TODO(verify)`：原版 SpriteF 由 `CreateSpriteF` 语义决定，反编译里
     * 没有逐字段判据可抄，卡点 = PC 实机对拍「子矩形 sprite 当 SETBGIMAGE 参数」是否被拒。
     */
    fun backgroundAdmissible(resource: String): Boolean {
        if (resource.isEmpty()) return false
        val sprite = aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource)
        if (sprite != null) {
            if (sprite.isAnime) return false
            val buffer = sprite.buffer ?: return false
            if (!buffer.isCreated) return false
            val bitmap = bufferPixels(buffer) ?: return false
            return sprite.srcX == 0 && sprite.srcY == 0 &&
                sprite.srcW == bitmap.width && sprite.srcH == bitmap.height
        }
        return fileFrame(resource) != null
    }

    /** sprite 表一跳（静态取子矩形、动画取首帧，见类文档「查表顺序」）。 */
    private fun spriteFrame(resource: String): ImageFrame? {
        val sprite = aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource) ?: return null
        if (!sprite.isCreated) return null
        sprite.frames?.let { frames ->
            // 帧表为空 ⇒ GetCurrentFrame 返回 null，一个像素都不画（SpriteAnime.cs:92-95 口径）；
            // 本层不做时钟推进，恒取首帧（局限见类文档）。
            val first = frames.firstOrNull() ?: return null
            val bitmap = bufferPixels(first.buffer) ?: return null
            if (first.srcW <= 0 || first.srcH <= 0) return null
            return ImageFrame(
                handle = bitmap,
                srcX = first.srcX,
                srcY = first.srcY,
                srcW = minOf(first.srcW, bitmap.width - first.srcX),
                srcH = minOf(first.srcH, bitmap.height - first.srcY),
                destW = sprite.width,
                destH = sprite.height,
                basePosX = sprite.posX,
                basePosY = sprite.posY,
                offsetX = first.offsetX,
                offsetY = first.offsetY,
                anime = true,
            )
        }
        val buffer = sprite.buffer ?: return null
        val bitmap = bufferPixels(buffer) ?: return null
        // 源矩形夹进位图界（SpriteBitmapSource.spriteFrame:236-240 同式）。
        val x = sprite.srcX.coerceIn(0, maxOf(0, bitmap.width - 1))
        val y = sprite.srcY.coerceIn(0, maxOf(0, bitmap.height - 1))
        val w = sprite.srcW.coerceIn(1, bitmap.width - x)
        val h = sprite.srcH.coerceIn(1, bitmap.height - y)
        return ImageFrame(
            handle = bitmap,
            srcX = x,
            srcY = y,
            srcW = w,
            srcH = h,
            destW = sprite.width,
            destH = sprite.height,
            basePosX = sprite.posX,
            basePosY = sprite.posY,
        )
    }

    /** GraphicsBuffer → 解码位图（按 gid 缓存，pixels 数组引用换了才重建）。 */
    private fun bufferPixels(buffer: GraphicsBuffer): DecodedPixels? {
        if (!buffer.isCreated) return null
        val pixels = buffer.pixels
        val cached = bufferCache[buffer.id]
        if (cached != null && cached.first === pixels) return cached.second
        val decoded = DecodedPixels(pixels, buffer.width, buffer.height)
        bufferCache[buffer.id] = pixels to decoded
        return decoded
    }

    /** 文件层一跳：缓存 → 索引/直查 → 解码（先查后解，见类文档）。 */
    private fun fileFrame(resource: String): DecodedPixels? {
        val key = resource.trim().lowercase()
        if (key.isEmpty() || key.contains("..")) return null
        fileCache[key]?.let { return it }
        val file = fileIndex[key] ?: directFile(key) ?: return null
        val decoded = decode(file) ?: return null
        fileCache[key] = decoded
        return decoded
    }

    /** 带扩展名的写法（`title.webp`）原样直查一次（GameImageFiles.locate:151-159 同式）。 */
    private fun directFile(key: String): File? {
        val direct = File(gameRoot.toFile(), key)
        return if (direct.isFile) direct else null
    }

    /** 一次性扫候选目录建索引（GameImageFiles.buildIndex:162-178 同式：stem + 全名双键）。 */
    private fun buildIndex(): Map<String, File> {
        val map = HashMap<String, File>(256)
        for (dir in CANDIDATE_DIRS) {
            val root = File(gameRoot.toFile(), dir)
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(MAX_DEPTH).forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                if (ext !in IMAGE_EXTENSIONS) return@forEach
                map.putIfAbsent(file.nameWithoutExtension.lowercase(), file)
                map.putIfAbsent(file.name.lowercase(), file)
            }
        }
        return map
    }

    /** javax.imageio 解码 → ARGB 行主序；解不动 / 超边长上限返回 null（GameImageFiles.kt:136-140 同口径）。 */
    private fun decode(file: File): DecodedPixels? = try {
        val image = ImageIO.read(file) ?: return null
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
            null
        } else {
            val pixels = IntArray(w * h)
            image.getRGB(0, 0, w, h, pixels, 0, w)
            DecodedPixels(pixels, w, h)
        }
    } catch (ignored: Exception) {
        null
    }

    /** `(width and 0xFFFF) shl 16 or (height and 0xFFFF)`：ImageSizeSource 的打包口径。 */
    private fun packDestSize(width: Int, height: Int): Int =
        (width and 0xFFFF) shl 16 or (height and 0xFFFF)

    private companion object {
        /** `GraphicsMethods.kt` 的 sprite 槽名（core 侧私有常量，platform SpriteBitmapSource 同款复述）。 */
        const val SLOT_SPRITE = "sprite"

        /** 候选目录：`resources` 是原版内容根（AppContents.cs:109-115），img/dat 是语料实测存在的变体。 */
        val CANDIDATE_DIRS = listOf("resources", "img", "dat")

        /** 索引收录的扩展名（GameImageFiles 同集；webp 进索引但 javax.imageio 解不动 → null 降级）。 */
        val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "bmp", "gif")

        /** 目录下钻深度（GameImageFiles.kt 的 MAX_DEPTH 同值；那边的 KDoc 有语料实测分布与定值理由）。 */
        const val MAX_DEPTH = 8

        /** 按名兜底的边长上限（GameImageFiles.kt:198 同值 4096）。 */
        const val MAX_SIDE = 4096
    }
}
