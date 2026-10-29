package com.eratw.port.core.host

import com.eratw.port.core.data.text.DetectedTextDecoder
import com.eratw.port.core.image.PngImage

/**
 * CSV 资源表的数据面：宿主只负责扫盘、读字节、读图片头、解码，语义全在 [ResourceContents]。
 *
 * 这样 core 语义可以用假宿主做纯 JVM 定向测试，Android 侧（minSdk 21，无 `java.nio.file`）
 * 也不被 `CsvLoader` 的 `Path` 依赖挡住。
 */
interface ResourceFileHost {

    /** `resources` 子树下全部 csv 的相对路径（`/` 分隔，含目录本身）。对应 `AppContents.cs:115`。 */
    fun listCsvFiles(): List<String>

    fun readBytes(path: String): ByteArray?

    /**
     * 只读文件头取 `[width, height]`。原版在 `AppContents.cs:273-283` 直接 `LoadImage`
     * 拿到 `Bitmap.Width/Height`，这里只需尺寸（像素延迟到首访问，见 [GraphicsBuffer.createDeferred]）。
     */
    fun imageSize(path: String, bytes: ByteArray?): IntArray?

    /** 解码成 `0xAARRGGBB` 像素；失败返回 null（对应原版 `LoadImage` 返回 null，`:274-278`）。 */
    fun decode(path: String, bytes: ByteArray): PngImage?
}

/**
 * `AppContents.LoadContents` 的语义移植（`AppContents.cs:107-178` + `CreateFromCsv` `:232-370`）。
 *
 * ## 为什么必须装
 *
 * `resources` 下 CSV 声明的图片**只**存在于这张表：`SPRITECREATED` / `RM_RESOURCECHECK_LOAD`
 * 查的就是它（`AppContents.GetSprite`）。本作立绘的 XRAY 图层链是
 * `AAZ_OVERLAY_IMAGES_BY_STRING` → `RM_RESOURCECHECK_LOAD(图层名)`（`ALTER_IMAGE_SET.ERB:82`）
 * → `GDRAWSPRITE グラフィックID, リソース名`（`グラフィック合成.ERB:51`）；不装表时
 * `RM_RESOURCECHECK_LOAD` 恒 0、每层都被 `CONTINUE` 跳过，合成结果是一张空图，
 * 屏上就是「XRAY 不生效」。CSV 里声明的动画（如 `30_BG1`）同理。
 *
 * ## 与原版的逐条对齐
 *
 * - 递归扫 `resources` 下 csv（`:115`），每个文件按 `EncodingHandler.DetectEncoding` 读行（`:141`）；
 * - 行过滤：整行 `Trim()` 后空或首字符 `;` 跳过（`:147-151`）；
 * - 名字 `Trim().ToUpper`（`:238`）；第二列 `ANIME`（忽略大小写）可声明动画尺寸（`:244-262`）；
 * - 第二列必须含 `.`（`:264-268`）；同一文件内**按完整路径缓存一份解码图**，
 *   所有 sprite 共享像素（`resourceDic`，`:270-293`）；
 * - 矩形缺省整图（`:300-301`）；给满 6 列才解析矩形，宽/高非正或与父图无交集 ⇒ 丢行
 *   （`:304-325`）；8 列起解析 pos（`:327-337`）；9 列起解析帧间隔（`:338-344`）；
 *   11 列起解析目标尺寸（`:345-356`）；
 * - 名字等于当前动画 ⇒ 追加帧（`SpriteAnime.AddFrame`，`:360-368`，帧矩形裁剪口径见
 *   `SpriteAnime.cs:24-36`）；否则建静态 sprite（`:369`）；
 * - 同名重复 ⇒ 告警并丢弃后一个（`:157-165`）。
 *
 * 唯一刻意偏离：像素**延迟解码**（见 [GraphicsBuffer.createDeferred]）。原版立即解码，
 * 本语料 4 个 CSV 引用 134 张图全解码约 105 MiB，真机 512 MiB 堆不能常驻；
 * 尺寸/`IsCreated` 与依赖它的 `SPRITEWIDTH` 等读数不变。
 */
object ResourceContents {

    /**
     * 装载结果。
     *
     * @param fileCount 实际打开的 csv 数
     * @param spriteCount 登记进资源表的 sprite 数（含动画声明）
     * @param animeCount 其中动画 sprite 数
     * @param warnings 逐行告警（路径:行号 + 原因），宿主负责落日志
     */
    class Report(
        val fileCount: Int,
        val spriteCount: Int,
        val animeCount: Int,
        val warnings: List<String>,
    )

    /** 单行解析结果（[CreateFromCsv] 的返回语义，`AppContents.cs:232-370`）。 */
    private sealed interface Row {
        class Anime(val sprite: SpritePool.Sprite) : Row
        class Static(val sprite: SpritePool.Sprite) : Row
        class Frame(val animeName: String, val frame: SpritePool.Frame) : Row
    }

    fun load(pool: SpritePool, host: ResourceFileHost): Report {
        val warnings = ArrayList<String>()
        val registered = HashMap<String, SpritePool.Sprite>()
        val buffers = HashMap<String, GraphicsBuffer>()
        var animeCount = 0
        var fileCount = 0

        for (csv in host.listCsvFiles().sorted()) {
            if (!csv.endsWith(".csv", ignoreCase = true)) continue
            val raw = host.readBytes(csv)
            if (raw == null) {
                warnings += "$csv: 打开失败，已跳过"
                continue
            }
            val text = DetectedTextDecoder.decode(raw)
            if (text == null) {
                warnings += "$csv: 无法识别编码（BOM / 严格 UTF-8 / CP932 均失败），已跳过"
                continue
            }
            fileCount++
            val dir = csv.substringBeforeLast('/', "")
            // 每个文件的 currentAnime 从 null 起：同名概率由文件内局部名决定（AppContents.cs:138）。
            var currentAnime: SpritePool.Sprite? = null

            splitLines(text).forEachIndexed { index, physical ->
                if (physical.isEmpty()) return@forEachIndexed
                val line = physical.trim()
                if (line.isEmpty() || line.startsWith(';')) return@forEachIndexed
                val where = "$csv:${index + 1}"
                val tokens = splitColumns(line)
                when (val row = parseRow(tokens, dir, where, currentAnime, host, buffers, warnings)) {
                    null -> Unit
                    is Row.Frame -> {
                        if (row.animeName == currentAnime?.name) currentAnime?.frames?.add(row.frame)
                    }
                    is Row.Anime -> {
                        currentAnime = row.sprite
                        if (register(pool, registered, row.sprite, where, warnings)) animeCount++
                    }
                    is Row.Static -> {
                        currentAnime = null
                        register(pool, registered, row.sprite, where, warnings)
                    }
                }
            }
        }
        return Report(fileCount, registered.size, animeCount, warnings)
    }

    /** 同名后到者丢弃（`resourceImageDictionary.TryAdd` 失败告警，`AppContents.cs:157-165`）。 */
    private fun register(
        pool: SpritePool,
        registered: MutableMap<String, SpritePool.Sprite>,
        sprite: SpritePool.Sprite,
        where: String,
        warnings: MutableList<String>,
    ): Boolean {
        if (registered.containsKey(sprite.name)) {
            warnings += "$where: sprite 名 ${sprite.name} 已存在，后一个被丢弃"
            return false
        }
        registered[sprite.name] = sprite
        pool.putResource(sprite)
        return true
    }

    private fun parseRow(
        tokens: List<String>,
        dir: String,
        where: String,
        currentAnime: SpritePool.Sprite?,
        host: ResourceFileHost,
        buffers: MutableMap<String, GraphicsBuffer>,
        warnings: MutableList<String>,
    ): Row? {
        if (tokens.size < 2) return null
        val name = tokens[0].trim().uppercase()
        val second = tokens[1]
        if (name.isEmpty() || second.isEmpty()) return null

        if (second.equals("ANIME", ignoreCase = true)) {
            if (tokens.size < 4) {
                warnings += "$where: 动画 $name 未声明尺寸"
                return null
            }
            val w = tokens[2].toIntOrNull()
            val h = tokens[3].toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0 || w > GraphicsBuffer.MAX_IMAGE_SIZE || h > GraphicsBuffer.MAX_IMAGE_SIZE) {
                warnings += "$where: 动画 $name 尺寸非法"
                return null
            }
            return Row.Anime(SpritePool.Sprite(name, w, h, frames = ArrayList()))
        }
        if (!second.contains('.')) {
            warnings += "$where: 第二列 $second 缺少扩展名"
            return null
        }

        val fileName = second.replace('\\', '/')
        val path = if (dir.isEmpty()) fileName else "$dir/$fileName"
        val buffer = buffers[path] ?: run {
            val bytes = host.readBytes(path)
            if (bytes == null) {
                warnings += "$where: 打开 $fileName 失败"
                return null
            }
            val size = host.imageSize(path, bytes)
            if (size == null || size.size < 2) {
                warnings += "$where: 解码 $fileName 失败"
                return null
            }
            // id 必须每张父图唯一：`SpriteBitmapSource.bufferCache` 以 `buffer.id` 为键
            // （platform/SpriteBitmapSource.kt:278），共用 -1 会让不同包图错拿同一张位图。
            // 图形池的 gid 恒 >= 0，负数段是 CSV 父图专用。
            val created = GraphicsBuffer(-(buffers.size + 1))
            if (!created.createDeferred(size[0], size[1]) { host.decode(path, bytes)?.pixels }) {
                warnings += "$where: $fileName 尺寸非法（${size[0]}x${size[1]}）"
                return null
            }
            buffers[path] = created
            created
        }

        var rx = 0
        var ry = 0
        var rw = buffer.width
        var rh = buffer.height
        var posX = 0
        var posY = 0
        var delay = 1000
        var destW = rw
        var destH = rh

        if (tokens.size >= 6) {
            val x = tokens[2].toIntOrNull()
            val y = tokens[3].toIntOrNull()
            val w = tokens[4].toIntOrNull()
            val h = tokens[5].toIntOrNull()
            if (x != null && y != null && w != null && h != null) {
                if (w <= 0 || h <= 0) {
                    warnings += "$where: sprite $name 宽高非正"
                    return null
                }
                // Rectangle.IntersectsWith（`:321`）：只要求有交集，不要求完全落在父图内。
                val overlap = x < buffer.width && x + w > 0 && y < buffer.height && y + h > 0
                if (!overlap) {
                    warnings += "$where: sprite $name 超出父图"
                    return null
                }
                rx = x
                ry = y
                rw = w
                rh = h
                destW = w
                destH = h
            }
            if (tokens.size >= 8) {
                val px = tokens[6].toIntOrNull()
                val py = tokens[7].toIntOrNull()
                if (px != null && py != null) {
                    posX = px
                    posY = py
                }
                if (tokens.size >= 9) {
                    val d = tokens[8].toIntOrNull()
                    if (d != null && d <= 0) {
                        warnings += "$where: 动画 $name 帧间隔非正"
                        return null
                    }
                    // C# `int.TryParse(tokens[8], out result)` 解析失败会把 result **置 0**
                    // （不是保持初值 1000），随后 AddFrame 把 <= 0 夹到 1ms
                    // （AppContents.cs:340 / SpriteAnime.cs:72-76）。空列要照抄成 1ms。
                    delay = d ?: 0
                    if (tokens.size >= 11) {
                        val dw = tokens[9].toIntOrNull()
                        val dh = tokens[10].toIntOrNull()
                        if (dw != null && dh != null) {
                            destW = dw
                            destH = dh
                        }
                    }
                }
            }
        }

        if (currentAnime != null && currentAnime.name == name) {
            return Row.Frame(
                name,
                normalizedFrame(buffer, rx, ry, rw, rh, posX, posY, delay, currentAnime.width, currentAnime.height),
            )
        }
        return Row.Static(SpritePool.Sprite(name, destW, destH, buffer, rx, ry, rw, rh, posX, posY))
    }

    /**
     * 追加帧的裁剪口径（`SpriteAnime.AnimeFrame.Normalize`，`SpriteAnime.cs:24-36`）：
     * 把 `(Offset, SrcSize)` 与 `(0,0,DestBaseSize)` 求交，偏移取交集原点、源尺寸取交集尺寸
     * （源矩形 x/y **不动** —— 这是原版行为，照抄）。交集为空时保留帧但尺寸 0
     * （原版把 `BaseImage` 置 null，仍计入 `totaltime`，`SpriteAnime.cs:65-80`）。
     */
    private fun normalizedFrame(
        buffer: GraphicsBuffer,
        srcX: Int, srcY: Int, srcW: Int, srcH: Int,
        posX: Int, posY: Int, delay: Int,
        destW: Int, destH: Int,
    ): SpritePool.Frame {
        val left = maxOf(posX, 0)
        val top = maxOf(posY, 0)
        val right = minOf(posX + srcW, destW)
        val bottom = minOf(posY + srcH, destH)
        val w = if (right > left && bottom > top) right - left else 0
        val h = if (right > left && bottom > top) bottom - top else 0
        return SpritePool.Frame(buffer, srcX, srcY, w, h, left, top, if (delay <= 0) 1 else delay)
    }

    /**
     * C# `string.Split(',')`：保留结尾空列（`"a,b,,"` ⇒ `["a","b","",""]`）。
     *
     * Kotlin 的 `split(',')` 会吞掉结尾空串，列数一变，`tokens.size >= 9` 这类门就跟着变；
     * 而空列在原版里有语义：`int.TryParse("")` 失败会把帧间隔折成 1ms（AppContents.cs:340）。
     */
    private fun splitColumns(line: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in line.indices) {
            if (line[i] == ',') {
                out += line.substring(start, i)
                start = i + 1
            }
        }
        out += line.substring(start)
        return out
    }

    /** C# `File.ReadAllLines`：`\r\n` / `\r` / `\n` 都是换行，末尾换行不产生空行。 */
    private fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                out += text.substring(start, i)
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) out += text.substring(start)
        return out
    }
}
