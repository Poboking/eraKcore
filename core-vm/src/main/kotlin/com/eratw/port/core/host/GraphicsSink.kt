package com.eratw.port.core.host

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
    /** PRINT_IMG：name/nameb(遮罩)/namem + 宽高与附加参数（px 或百分比，isPx 区分）。 */
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
