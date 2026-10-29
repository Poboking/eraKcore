package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage

/**
 * 宿主侧图片解码通道（M10-GFX）。
 *
 * ## 它解决什么
 *
 * `GCREATEFROMFILE` / `GLOAD` 的像素来自 core 自带的解码器：`PngDecoder`（png 全绿）与
 * `WebpDecoder`（语料 2548 个 webp 里解通 136 个，有损位流明确不做）。解码失败时
 * core 按原版 `Utils.LoadImage` 的空 catch 把结果折成 0（spec: `Utils.cs:502-525`），
 * 于是**缓冲是空的**，屏上没有立绘 —— 而原版 PC 上这两条路都能出像素：`.webp` 走
 * `WebP.Load`（`Utils.cs:511-515`），其余走 `new Bitmap(path)`（`Utils.cs:518`，GDI+ 吃
 * png/jpg/bmp/gif）。也就是说失败是我们的移植缺口，不是官方行为。
 *
 * 本接口就是补上那一层的**宿主通道**：宿主（`:platform` 用 `android.graphics.BitmapFactory`）
 * 拿到字节后自行解码，core 把像素灌进 [com.eratw.port.core.host.GraphicsBuffer]。
 * `core/fn/GraphicsMethods.kt` 的 G 系计算口径与返回码**一个字都不改**：
 * 未安装本通道时（`aux.peek` 为 null，纯 JVM 测试与语料闸全是这种）行为与今天逐项相同；
 * 安装之后，原本能解通的 png 结果一致，原本解不通的按平台最优解出像素。
 *
 * ## 复刻边界（ADR-005）
 *
 * 「原版解码失败返回 0」不是要复刻的官方 bug —— 原版的失败只发生在文件真不存在/格式
 * 真不认的时候。本通道的失败口径与它一致：**解不出来就返回 null**，调用方按既有分支
 * 走（webp 签名不回落文本载体，见 `readImageFile` 的注释）。
 *
 * ## 线程
 *
 * 调用点在游戏线程（语句执行 / 内建函数求值）内，实现方**不得**触碰 Android UI 线程 API。
 * `BitmapFactory` 是纯 native 解码、可在工作线程调用。
 *
 * @see RuntimeAux
 */
interface ImageDecoderHost {
    /**
     * 解 [bytes]（宿主按 [path] 从磁盘/assets 读到的原始字节）。
     *
     * 返回的 [PngImage.pixels] 必须是 `width * height` 长度、`0xAARRGGBB` 布局的 ARGB。
     * 解不出来（格式不认 / 字节损坏 / 尺寸越 `GraphicsBuffer.MAX_IMAGE_SIZE`）返回 null，
     * 调用方回落到 core 自带解码器与文本载体。
     */
    fun decode(path: String, bytes: ByteArray): PngImage?
}

/**
 * [ImageDecoderHost] 在 `store.aux` 里的槽名（每 VM 一份，随存档/重启作废）。
 *
 * 命名遵循 `RuntimeAux` 的约定：小写、按能力家族（gfx / sprite / cbg 的邻居）。
 */
const val IMAGE_DECODER_SLOT: String = "imageDecoder"
