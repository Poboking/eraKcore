package com.eratw.port.core.host

/**
 * 一帧动画 CBG 的**帧几何**（原版 `SpriteAnime.AnimeFrame` 的绘制面，`SpriteAnime.cs:12-42`）。
 *
 * 原版动画帧在绘制时只用到四件事（`SpriteAnime.GraphicsDraw(g, Point)`，`SpriteAnime.cs:137-146`）：
 * `SrcRectangle.Size`（落位**尺寸**）、`Offset`（在 `DestBasePosition` 之上的**额外**平移）、
 * `DelayTimeMs`（帧时长，驱动逐帧推进）、`SrcRectangle`（源子矩形，像素侧由宿主按它取帧）。
 * 前两个是几何、第三个是时间轴，第四个不是本 DTO 的字段 —— 像素归渲染侧（`SpriteTable` /
 * 平台 `SpriteBitmapSource.spriteFrame`），core 只给「这一帧多大、偏多少、占多久」。
 *
 * **`Offset` 是已经过 `AnimeFrame.Normalize` 的裁剪结果**（`SpriteAnime.cs:24-36`）：
 * 帧矩形与 `DestBaseSize` 求交后 `Offset` 被回写成交集左上角、尺寸被裁成交集 ——
 * 移植侧这一步在 `SPRITEANIMEADDFRAME` 的语句层已做（`fn/GraphicsMethods.kt` 的
 * `Normalize` 段），本 DTO 拿到的就是裁好的值。
 *
 * @property widthPx 帧宽 = `SrcRectangle.Width`（绘制目标矩形的宽）
 * @property heightPx 帧高 = `SrcRectangle.Height`
 * @property offsetX 帧偏移 x = `AnimeFrame.Offset.X`（叠在 `DestBasePosition` 之上，`:142-143`）
 * @property offsetY 帧偏移 y = `AnimeFrame.Offset.Y`
 * @property delayMs 帧时长（毫秒）；原版入表时夹到 ≥1（`SpriteAnime.cs:72-76`），
 *   逐帧推进的 `% totaltime` 里用的就是这个夹后的值（`:78`、`:108`）
 */
data class CbgFrameGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val offsetX: Int,
    val offsetY: Int,
    val delayMs: Int,
)

/**
 * 一条 CBG 图层的**渲染侧快照**（CBGSETG / CBGSETSPRITE / CBGSETBUTTONSPRITE 的消费面）。
 *
 * 字段取**最小可用集**：摆位、深度、身份、像素尺寸、按钮态与动画帧几何。真正的像素归渲染侧
 * 自己的精灵表（`render` 的 `SpriteTable`）；core 这边只给「画哪张、画在哪、多深、哪一帧」。
 *
 * ## 坐标语义
 *
 * [x] / [y] 是原版 `CBG_SetImage` 收下的**原始点**（`EmueraConsole.cs:645-646` 存进
 * `ClientBackGroundImage.x/y`）：绘制时 x 直接当画布 x，y 是**下沿锚定**偏移 ——
 * 落位 y = `y + 画布内容高 - DestBaseSize.Height`（`EmueraConsole.cs:1846`）。
 * 因此 [y] 与 `SpritePlacement.yPx` 同口径（负数即向下压），**不随滚动变化**
 * （CBG 是整屏层，不参与行滚动）。
 *
 * ## 动画 CBG（A 刀：`CBGSETSPRITE` 接到动画 sprite）
 *
 * 原版动画图层**不走** `DestBaseSize` 落位尺寸：`cbgList` 那条循环把
 * `new Point(x, y + MainPicBox.Height - aSprite.DestBaseSize.Height)` 交给
 * `aSprite.GraphicsDraw(g, Point)`（`EmueraConsole.cs:1846`），而 `SpriteAnime` 的 Point 重载
 * 再叠 `DestBasePosition` 与**当前帧** `Offset`、落位尺寸用 **`SrcRectangle.Size`**
 * （`SpriteAnime.cs:137-146`）。所以动画层的正确读数是：
 * - 下沿锚点高 = [baseHeightPx]（`DestBaseSize.Height`，不是帧高）；
 * - 落位尺寸 = [widthPx]/[heightPx]（当前帧的 `SrcRectangle.Size`）；
 * - 额外平移 = [frames] 里该帧的 `offsetX/offsetY`。
 *
 * 帧表的**时间轴**（哪一帧是「当前帧」）不由本快照决定：core 的 CBG 推送只发生在
 * `CBG*` 语句执行时（`fn/GraphicsMethods.pushCbgLayers`），没有逐帧重推通道，也没有
 * 动画时钟的数据面。因此快照给**完整帧表** [frames] + 推送时刻的帧号 [frameIndex]
 * （恒 0 = 原版「首次取帧取第 0 帧」，`SpriteAnime.cs:96-102`，与 `GDRAWSPRITE` 的
 * GFX-07 同口径），逐帧推进由渲染侧按 [frames] 的 `delayMs` 自算
 * （`render` 的 `CbgDrawOrder.frameAt` + `SpriteAnimeTimeline`）。
 *
 * @property depth 层级：**降序**绘制，depth 大先落笔、靠底；`depth == 0` 与正文同批
 *   （正文随后画 ⇒ 仍在正文之下）；`depth < 0` 在正文之后落笔 ⇒ 压住正文
 *   （`EmueraConsole.cs:1834-1857` 的双路归并）
 * @property x 画布像素 x（原版直接作 `Point.X`，`:1846`）
 * @property y 下沿锚定偏移（见类 KDoc「坐标语义」）
 * @property spriteName 按名解析的图层（`CBGSETSPRITE` / `CBGSETBUTTONSPRITE`）；null = gid 图层
 * @property gid 按缓冲编号解析的图层（`CBGSETG`）；无 gid 时 = -1
 * @property widthPx 落位宽（动画 = 当前帧 `SrcRectangle.Width`，静态 = 缓冲/精灵宽；
 *   0 = core 侧查不到：图表未创建 / gid 未建）
 * @property heightPx 落位高（同上）
 * @property spriteNameB 按下态改画的按钮图（`ClientBackGroundImage.ImgB`，
 *   `EmueraConsole.cs:1840-1843` 在 `isButton && buttonValue == selectingCBGButtonInt`
 *   时把要画的 sprite 换成它）；null = 没有第二张图（按下也画主图）
 * @property isButton 是否按钮层（`ClientBackGroundImage.isButton`，`:1840`）
 * @property buttonValue 按钮值（`ClientBackGroundImage.buttonValue`，`:1840`）：
 *   与取色图（[buttonMapGid]）在该点像素的 `& 0xFFFFFF` 比对，相等即「按下态」
 * @property buttonMapGid 取色图 gid（`cbgButtonMap`，`EmueraConsole.cs:650-664`）；
 *   -1 = 没有取色图。**同一份快照里的每条图层带的是同一个值** —— 取色图是控制台级
 *   单值（`cbgButtonMap` 字段），不是逐层字段
 * @property baseHeightPx 下沿锚点高 = `DestBaseSize.Height`（`:1846`）；
 *   0 = 渲染侧按位图高回落（静态层的等价退化）
 * @property frames 动画帧表（`SpriteAnime.FrameList` 的几何面）；**空 = 静态层**
 * @property frameIndex 推送时刻的当前帧号（静态层恒 0；动画层 = 0 = 原版首帧）。
 *   [NO_FRAME]（-1）= 动画 sprite 没有任何帧 ⇒ 原版 `GetCurrentFrame` 返回 null、
 *   一个像素都不画（`SpriteAnime.cs:92-95` + `:139-145`）；渲染侧见此值必须整层跳过
 */
data class CbgLayerSnapshot(
    val depth: Int,
    val x: Int,
    val y: Int,
    val spriteName: String?,
    val gid: Int,
    val widthPx: Int,
    val heightPx: Int,
    val spriteNameB: String? = null,
    val isButton: Boolean = false,
    val buttonValue: Int = 0,
    val buttonMapGid: Int = -1,
    val baseHeightPx: Int = 0,
    val frames: List<CbgFrameGeometry> = emptyList(),
    val frameIndex: Int = 0,
) {
    companion object {
        /**
         * 「这一层没有可画的帧」（`SpriteAnime.GetCurrentFrame` 返回 null，
         * `SpriteAnime.cs:92-95`）。不是合法帧号：帧号从 0 起。
         */
        const val NO_FRAME: Int = -1
    }
}

/** `GraphicsSink` 在 `RuntimeAux` 里的槽名（函数层拿不到 `ExecContext` 时的唯一读面）。 */
const val GRAPHICS_SINK_SLOT: String = "graphicsSink"

/**
 * [GRAPHICS_SINK_SLOT] 的载体：**可变引用**。
 *
 * 为什么不是直接把 sink 塞进槽：`ExecContext.graphics` 是 `var`（真机在会话构造完成后
 * 才换上 `AndroidGraphicsSink`，见 `ExecContext.graphics` 的 KDoc）。原地替换
 * [sink] 字段就不用重建槽，也不会让已经取过引用的读者看到旧对象。
 */
class GraphicsSinkRef(var sink: GraphicsSink = NoopGraphicsSink)

/**
 * 图像/资源宿主。语句层只收集参数，实际加载与绘制归 M4。
 *
 * spec: FunctionIdentifier.cs:404-504（PRINT_IMG/PRINT_RECT/PRINT_SPACE）、
 * 1519-1572（SETBGIMAGE/REMOVEBGIMAGE/CLEARBGIMAGE）→ EmueraConsole → ImageHolder 调用面。
 *
 * ## 生命周期（2026-10-22 L09 续任复核，逐条对反编译）
 *
 * - **两族动作的时机不同，宿主别混排**：PRINT_ 族只是往打印缓冲挂一个节点，成型在换行 flush ——
 *   `PrintImg` = `printBuffer.Append(new ConsoleImagePart(...))`（EmueraConsole.cs:3192-3195）、
 *   `PrintShape` 同形（:3197-3201）。SETBGIMAGE / REMOVEBGIMAGE / CLEARBGIMAGE 是**立即生效**，
 *   各自当场 `BakeBackground()`（:897-919）。
 * - **SkipPrint 只闸住 PRINT_ 族**：三条语句体整段包在 `if (!GlobalStatic.Process.SkipPrint)` 里
 *   （FunctionIdentifier.cs:414 / :454 / :485），背景三条没有这道闸（:1541 / :1556 / :1570）
 *   ⇒ 跳过打印时背景照样换掉。sem 侧同口径（PRINT_ 带 `!ctx.skipPrint`、SETBGIMAGE 系不带）。
 * - **图取不到时的两种静默回落**（都不报错、不抛）：`ConsoleImagePart` 取不到 sprite 时把
 *   `<img ...>` 标签文本当正文打印（ConsoleImagePart.cs:60-65，宽度走整串像素测量 :110-113）；
 *   `AddBackgroundImage` 取不到 sprite 时连 `BakeBackground()` 都不执行，整条语句空转
 *   （EmueraConsole.cs:899-907）。
 *
 * ## 参数口径
 *
 * - [printImage] 的 nameb / namem（**本接口旧注释把两者说反过**）：
 *   `nameb` = 选中/聚焦时改画的**按钮图**（标签 `srcb` → `ButtonResourceName` → `cImageB`，
 *   ConsoleImagePart.cs:29 / :43 / :94-96，绘制点 :159-166）；
 *   `namem` = **遮罩/映射图**（`srcm` → `MappingGraphName` → `cImageM`，:13 / :44 / :98-100，
 *   按钮命中区靠它取色 `SpriteGetColor`，:137-154）。
 * - 空串口径不对称：`nameb` 在**语句层**就被折成 null（FunctionIdentifier.cs:423-426），
 *   `namem` 没有这一步（只有 `IsNullOrEmpty` 参与标签拼装，:421-422 + ConsoleImagePart.cs:51-54）
 *   ⇒ [printImage] 收到的 `nameb` 为 null 与 "" 是两回事，宿主按原版对齐时以 null 为准。
 * - [params] 是 ERB 书写顺序的 `Param[0..2]` 原样转发（FunctionIdentifier.cs:427-439），
 *   而原版把它喂给 `PrintImg(name, nameb, namem, height, width, ypos)`
 *   （EmueraConsole.cs:3192）时是 **`Param[1]`→height、`Param[0]`→width、`Param[2]`→ypos**
 *   —— 即下标 0 落 width 形参，形参名与下标顺序不同名。缺位传 null（`Param.Length` 判定 :427-439），
 *   数值一律 `(int)` 截断。pair 的 `second` = `isPx`：true 像素、false 百分比（相对
 *   `Config.FontSize` 折算，ConsoleImagePart.cs:66 / :76-81；宽 0 时按图片比例反推 :67-71）。
 * - [setBgImage]：[depth] 缺省 0（FunctionIdentifier.cs:1530 / :1533-1536）；[opacity] 传的是
 *   **0-255 的原样整数**，原版缺省 1.0 并把实参 `/255f`（:1532 / :1537-1540）⇒ 折算归宿主。
 *   背景表按 depth **降序**插放，且比较器对相等 key 恒返回 -1（EmueraConsole.cs:904）
 *   ⇒ 等深两张图的相对次序由不稳定排序决定，属官方行为，勿「顺手」改成稳定排序。
 * - [removeBgImage] 删不存在的名字时原版是 `RemoveAt(-1)` ⇒ `ArgumentOutOfRangeException`
 *   未捕获即崩（EmueraConsole.cs:917）。移植侧按 ADR-005 的崩溃类例外不复刻：宿主应
 *   「无匹配则不动、不重烘」。TODO(verify)：若要严格复刻崩溃，需 sem 侧先有异常出口。
 */
interface GraphicsSink {
    /** PRINT_IMG：name/名 + nameb(按钮图)/namem(遮罩·映射图) + 宽高与附加参数（px 或百分比，isPx 区分）。口径见类 KDoc「参数口径」。 */
    fun printImage(
        name: String,
        nameb: String?,
        namem: String?,
        params: List<Pair<Long, Boolean>>,
    )

    /** PRINT_RECT：矩形色块尺寸序列（无颜色通道的旧入口，实现方按缺省色填充）。 */
    fun printRect(params: List<Pair<Long, Boolean>>)

    /**
     * PRINT_RECT 带填充色：[rgb] 为 `0xRRGGBB`，取语句执行时控制台的当前前景色。
     *
     * 原版 `PrintShape` 把 `userStyle.Color` 直接交给形状段
     * （EmueraConsole.cs:3197-3199 → ConsoleShapePart.cs:76），出厂值 =
     * `Config.ForeColor` = 192,192,192（ConfigData.cs:68）；`SETCOLOR` 之后跟色
     * （EmueraConsole.cs:2820-2824 写 `userStyle.Color`）。语句层未设色时传缺省值，
     * 见 [ConsoleSink.foregroundColor] 的 null 口径。
     *
     * 缺省实现委托单参 [printRect]：既有实现类与测试假 sink 不改一行也能编译，
     * 代价是它们收不到颜色（按各自原有口径出块）。生产实现应覆写本重载。
     *
     * **alpha 恒不透明**：原版颜色没有 alpha 通道可传 —— `SETCOLOR` 只接受 1 个
     * RGB 或 3 个分量（ArgumentParser.cs:1911-1955 的 SP_COLOR 参数表），落点是三实参
     * `Color.FromArgb(r, g, b)`（Process.cs:1436，A=255）；`SETCOLORBYNAME` 还显式
     * 拒绝 A==0 的名字（Process.cs:1444）；`Config.ForeColor` 同样是三实参
     * `Color.FromArgb(192,192,192)`（ConfigData.cs:68）。实现方一律按 `0xRRGGBB`
     * 补 0xFF 填充，不要把 [rgb] 高位当 alpha 用。
     */
    fun printRect(params: List<Pair<Long, Boolean>>, rgb: Int) {
        printRect(params)
    }

    /** PRINT_SPACE：空白宽度。 */
    fun printSpace(params: List<Pair<Long, Boolean>>)

    fun setBgImage(name: String, depth: Long?, opacity: Long?)

    fun removeBgImage(name: String)

    fun clearBgImages()

    /**
     * TEXT_BGC_ON：设置正文区背景色（4.981 上游新增，本层只转发两个整数）。
     *
     * 缺省实现空转：既有实现类与测试假 sink 不改一行也能编译，渲染侧真正上色归 M4。
     *
     * **关闭哨兵**：`TEXT_BGC_OFF` 不另设入口，以 `rgb < 0` 关闭 —— 语句层在
     * `sem.StatementExecutor` 的 `TEXT_BGC_OFF` 分支（扁平分派与对象树分派各一处）
     * 传 `(-1, 0)`，测试 `UpstreamStatementsTest` 亦按「负色哨兵」断言 ⇒ 渲染侧覆写本方法时，
     * 须把 `rgb < 0` 识别为「清除衬底」而非取色。
     * TODO(verify)：`TEXT_BGC_ON` 的实参口径（`QOL_IMAGE.ERB:2564` 为「颜色整数, 不透明度」
     * 两参）本机 1.824 reference 无对应物（`Instraction.Child.cs` 等 fork 实现缺失，全文零命中）；
     * [opacity] 取值域按 specs 取证为**百分比 0..100**（可达依据：`specs/isa.yaml` 的 `TEXT_BGC_ON` 条，
     * 记不透明度百分比 + `(rgb<<8)+(alpha&0xFF)` 装载期折叠）。
     * `ArgumentBuilder.cs:2074-2099` = fork-src 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）
     * —— 本机该文件为 118 行 stub，无 `SP_COLOR_ALPHA`/折叠实现。本层只原样转发两整数、
     * 不做百分比折算（折算与实参域复核归渲染侧落地时进行）。
     */
    fun setTextBgColor(rgb: Long, opacity: Long) {}

    /**
     * SETIMAGELAYERL：登记/覆盖一条**行锚定**图层（4.981 上游新增）。
     *
     * 缺省实现空转：既有实现类与测试假体经接口缺省实现自然空转，不改一行也能编译；
     * 真正落笔归 M4（`render` 侧的 `GraphicsLayer` / `CanvasConsoleView`）。
     *
     * **字段口径**（与 `sem.StatementExecutor.execSetImageLayerL` 的 aux 登记逐项一致）：
     * - [lineNo] = **显示行索引**（原版 `Console.GetLineNo`，`EmueraConsole.cs:507` 的 `lineNo`；
     *   非 `LINECOUNT` 的逻辑行数）；
     * - [opacity] 原样 0..255（原版把它 `/255f` 折算，折算归宿主）；
     * - `height <= 0` 的回落（sprite 的 `DestBaseSize.Height`）与 [lineNo] 的显示行口径
     *   **已由 core 算好**（`Instraction.Child.cs:4192-4198` / `:4188`，该整文件不在本机
     *   1.824 reference，行号不可本机取证，见 `specs/verify-notes.md` 23.16），本层原样转发；
     * - [followScroll] 恒 true（L 版最后一个实参 `Instraction.Child.cs:4207`）。
     *
     * **屏幕坐标**：`x = DrawingParam_ShapePositionShift + xpos`、
     * `y = GetLinePointY(lineNo) + LineHeight - ClientHeight + (imageHeight - LineHeight) + ypos`
     * （`specs/isa.yaml:2574`）；本层只转 `xpos/ypos` 与 [lineNo]，换算由渲染侧做
     * （core 层没有 `LineHeight`/`ClientHeight`/`GetLinePointY` 三项数据面）。
     * 同 depth 覆盖（原版 `Console.SetImageLayer` 按 depth 索引同一张表）。
     */
    fun setImageLayer(
        spriteName: String,
        depth: Long,
        lineNo: Int,
        xpos: Int,
        ypos: Int,
        width: Int,
        height: Int,
        opacity: Int,
        followScroll: Boolean,
    ) {}

    /**
     * CBG 图层**整表快照**（`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE` / `CBGCLEAR`
     * / `CBGCLEARBUTTON` / `CBGREMOVERANGE` 的渲染转发面）。
     *
     * ## 为什么推整表而不是增量
     *
     * 原版 `EmueraConsole.cbgList` 是**一个有序列**（`cbgList.Add(item); cbgList.Sort();`，
     * `EmueraConsole.cs:645-646` / `:679-680`，比较器 `:58-65` = zdepth 降序），六条语句
     * 各自增删之后列表形态才是绘制依据。移植侧把同一份顺序放在 `CbgPool.layers`，
     * 因此每次变更后整表推一份 [CbgLayerSnapshot] 列表（已按 depth 降序）。
     * 增量协议会让渲染侧复刻一遍排序与删除语义，重复实现比多推几个元素更贵。
     *
     * **取色图也是这张表的一部分**：`CBGSETBMAPG` / `CBGREMOVEBMAP` 只改
     * `cbgButtonMap`（`EmueraConsole.cs:650-664` / `:616-621`），而它随每次快照重复进
     * [CbgLayerSnapshot.buttonMapGid] —— 所以这两条语句同样要**重推整表**，否则渲染侧
     * 手里的取色图 gid 会停在上一次推送。没有按钮图层时取色图对绘制无影响
     * （没有层可换图/命中），空表推送即等于「取色图不可见」，与原版一致。
     *
     * ## 缺省实现空转
     *
     * 既有实现类与测试假 sink 不改一行也能编译；`NoopGraphicsSink` 与纯 JVM 装置因此
     * 零行为差。生产实现是 `render` 的 `GraphicsSinkBridge`（写进
     * `GraphicsLayer.cbgLayers`）。
     *
     * @param layers 当前 CBG 池的只读快照，**已按 depth 降序**；空表 = 清空
     */
    fun setCbgLayers(layers: List<CbgLayerSnapshot>) {}
}

object NoopGraphicsSink : GraphicsSink {
    override fun printImage(
        name: String,
        nameb: String?,
        namem: String?,
        params: List<Pair<Long, Boolean>>,
    ) {
    }

    override fun printRect(params: List<Pair<Long, Boolean>>) {}
    override fun printSpace(params: List<Pair<Long, Boolean>>) {}
    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
    override fun removeBgImage(name: String) {}
    override fun clearBgImages() {}
}
