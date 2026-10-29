package com.eratw.port.core.host

/**
 * core 侧**唯一**的字符宽度模型：半角 1 格 / 全角 2 格。
 *
 * ## 为什么要有这个对象
 *
 * `LineBufferConsoleSink` 的折行、PRINTC 补齐、DRAWLINE 填充、按钮跨行拆分、
 * 以及 M4 渲染层需要的「段起始列」，都必须用同一套换算。四处各写一份的后果是
 * 「同一行在不同使用者眼里宽度不同」——按钮命中区与字形位置会错位。
 *
 * ## 单位与真机口径
 *
 * 单位是**半角格**（cell），不是像素，也不是 UTF-16 长度：
 * - 原版按像素测量（`StringMeasure.GetDisplayLength`，StringMeasure.cs:42-72，
 *   调用点 EmueraConsole.cs:3429/3434 的 DRAWLINE 填充、:3270-3280 的 PRINTC 补齐）；
 * - 真机上唯一权威是 `:render` 的 `GlyphCache.measureCells`（`render/.../glyph` 包）：
 *   宿主先量像素再换算成半角格，把结果作为 `clientWidth` 传进来；
 * - 本对象是**没有字体度量时**的确定性兜底（headless 测试、等宽字体），
 *   不作为真机语义：比例字体（如 `SETFONT "Saitamaar"`，
 *   空格 400 / 数字 640 / 拉丁 800 / CJK 1280，见 BOARD「M4-REALCHECK」）下
 *   两者不等价，真机折行以 GlyphCache 为准。
 *
 * 表来源 = **CP932(Shift-JIS) 字节数**（半角 1 字节 / 全角 2 字节），判定式与逐点出处写在
 * [isFullWidth]。原版屏上宽度问 GDI 像素、PRINTC 补齐宽度问字节数（`EmueraConsole.cs:3255`），
 * 等宽日文字体下两者同结论；基准 `pc-golden-root.txt:61`（99 个 `━` 填满 1797px 可绘制宽度）
 * 把 East Asian Width 里的 Ambiguous 一类符号钉成全角，故不按 EAW 建表。
 * `core/fn` 侧曾另有两份 EAW 副本（`fn/HtmlMethods.kt` 的 approxTextWidth、
 * `fn/DebugMiscMethods.kt` 的 measureWidth）加第三份「逐码元 code 小于 0x80」的判据
 * （`fn/GraphicsMethods.kt`），已由 wave/h-fncopy 全部收进本表——像素换算层各留各的，
 * **名单只有一份**（回归尺子：`core/fn/FnWidthSingleSourceTest`，三处读数必须等于
 * `CellWidth.of(s) x 9`）。
 *
 * 与「字节数」有关但**不归本表**的一条：`STRLEN`/`GETSUBSTRING` 一族走的是
 * `LangManager.setEncode(Config.Encode)` 的**配置码页**，本语料 `emuera.config:76` 是
 * 「EMUERA INTERFACE LANGUAGE:简体中文」⇒ 运行期码页是 936 而不是 932；半角假名在 932 是
 * 1 字节、在 936 是 2 字节。BOARD 的 STRLEN-CODEPAGE 行在管这件事，别把它和显示宽度混成一个数。
 *
 * TODO(verify)：emoji（U+1F300..U+1FAFF、U+2600..U+27BF）在 PC 基准里未出现，
 * 本表按「CP932 表外字 ⇒ 全角 2 格」处理（与 [isFullWidth] 的表外字同一条卡点）；
 * 接真机字体度量后以 GlyphCache 的 advanceCells 为准。
 */
object CellWidth {

    /** 半角字符占格数。 */
    const val HALF = 1

    /** 全角字符占格数。 */
    const val FULL = 2

    /**
     * 单个码位的占格数。
     *
     * 只接受**完整码位**（`String.codePointAt` 的返回值），不接受 UTF-16 代理项：
     * 单独传一个代理项半会落进「其余一律全角」按 2 格计（与 [isFullWidth] 的判据一致）；
     * 把一个增补平面字符按两个代理项分别传入会得到 4 格，按码位传入才是 2 格。
     * 遍历一律用 `i += Character.charCount(cp)`，见 [of]。
     */
    fun ofCodePoint(cp: Int): Int = if (isFullWidth(cp)) FULL else HALF

    /**
     * 整串的占格数。按码位遍历，代理对（增补平面）不会被拆成两个半角。
     *
     * 这是折行/补齐/按钮列区间的统一入口；调用方不得再自行累加。
     */
    fun of(text: String): Int {
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += ofCodePoint(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * 半角（1 格）的**四档**，其余一律全角 —— **判定式的唯一出处见本函数**。
     * 口径 = 「CP932(Shift-JIS) 里占几个字节」，不是 East Asian Width，也不是
     * 只看 ASCII 可打印段：
     *
     * 1. `U+0020..U+007E`：ASCII 可打印（Shift-JIS 单字节 0x20..0x7E，其中 0x5C 屏上是 `¥`、
     *    0x7E 是 `‾`，但都仍是半角）；
     * 2. `U+FF61..U+FF9F`：半角假名与半角符号（Shift-JIS 单字节 0xA1..0xDF）；
     * 3. `U+0000..U+001F`、`U+007F`、`U+0080`：C0 控制符、DEL 与 C1 首码点 —— `.NET` 的
     *    932 码页把它们**原样单字节透传**（0x00..0x1F、0x7F、0x80），`GetByteCount` 各得 1；
     * 4. `U+00A5`（`¥` YEN SIGN）：932 码页的 best-fit 把它编回单字节 `0x5C`，同样 1 字节。
     *
     * 第 3、4 档是本表在 2026-10-21 由 `EmueraConsole.cs:3255` 的字节数口径直接实测补上的
     * （本机 `dotnet 9.0.200` + `CodePagesEncodingProvider` 的
     * `Encoding.GetEncoding(932).GetByteCount`，逐点读数写在
     * `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`
     * 的 D 类表与 `LineFolderAuditTest` 的同名用例；本机 Temurin 17 的 `windows-31J` 对
     * 这 35 个码点逐点交叉验证同样得 1 字节，`U+0080` 走替换回退 `0x3F` 但仍是 1 字节）。
     * 补之前它们落在「其余一律 2 格」里，与 `:3255` 的读数差 1 格 ——
     * 即 M6-RENDER-AUDIT 登记的那条缺口；`U+00A5` 与 ASCII 的 `0x5C` 现在**同值**，
     * 旧 `Kernel.kt:109-110` 那条「同一个字形两个读数」的 TODO 随本档一起消掉。
     *
     * 口径的第二条证据是屏上宽度：原版问 GDI 像素（`StringMeasure.cs:42-72`），而等宽日文字体下
     * 「双字节 ⇒ 两倍半角宽」，两者同结论。基准直接钉住了这一点：
     * `fixtures/golden/baseline/pc-golden-root.txt:61`（对 `ERB/TITLE.ERB:62` 的
     * `CUSTOMDRAWLINE ━` 的采集）是 **99 个 U+2501**，而 `:253` 的 199 个 `=` 把半角步进
     * 钉成 9px、可绘制宽度 1797px（`Config.cs:461-466`）—— 99 字填满 1797px 只能是
     * 单字 18px = **2 个半角格**。
     *
     * 换表换掉的是 East Asian Width 的 Ambiguous 一类：`─ ━ │ ┃ ┼ ■ □ ▲ ▼ ◆ ○ ● ★ ♪ ※ ① ±
     * × ― ‐ “ ” … ℃ № → √ ≠ ≡ ≦ ≧ ¢ § ¬ ° ´` 在 CP932 里都是双字节（逐点字节序列实测登记在
     * `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt` 的 A 类表），
     * EAW 只算 Ambiguous → 旧表给 1 格，与上面两条口径同时冲突。
     *
     * 控制符按 1 格**不影响任何整串路径**：TAB 在测量前就被展开成 8 个空格
     * （`StringMeasure.cs:57-61`，本仓 `render` 的 `Grid.expandTabs`），所以
     * `CellWidth.of("\t")` = 1 而整串 `Grid.cells("\t")` 仍是 8；基准 `:18 :20 :22` 的
     * 三条含 TAB 行格数不变。
     *
     * TODO(verify)：CP932 **编不出来**的表外字（`— ≤ ≥ ♥ ❤ · ½ ∶ ≈` 等，逐点登记在同测试的
     * B 类表）这里按全角 2 格处理，与旧表同值、沿用旧表的 TODO：
     * 原版像素宽度取决于字体回退，基准文本里没有可对拍的读数；而 `:3255` 的字节数口径对
     * 这类字符实得 1（.NET 的替换回退按单个 `?` 计）。两种口径对表外字必然分叉，
     * 真机以 `:render` 的 `glyph/GlyphCache.measureCells` 像素度量为准（契约 M4-C 0.5）。
     * 代理项半（`U+D800..U+DFFF`）不是字符，单独传入按全角 2 计；整串一律走 [of] 按码位累加。
     *
     * TODO(verify)：**`U+203E`（OVERLINE）漏在四档之外，应补进第 4 档**。它与 `U+00A5`
     * 是同一条口径的两个特例：CP932 的 0x5C 映射 `¥`、0x7E 映射 `‾`，两者都是**真实的单字节
     * 映射**（不是替换回退），所以 `Encoding.GetEncoding(932).GetByteCount` 各得 1
     * —— 与 `EmueraConsole.cs:3255` 的 PRINTC 字节数口径、`:render` 的等宽网格都一致。
     * 本机 Temurin 17 `windows-31J` 实测：`U+00A5` → `5c`、`U+203E` → `7e`，均为 1 字节
     * （同批实测 `U+301C`/`U+2016`/`U+2212` 才是替换回退 `3f`）。本表只补了前者。
     * 未就地改的理由是**跨模块测试锚点**：`render` 的 `grid/GridWidthSingleSourceTest`
     * 把「旧表与唯一表的差集」硬编码成 D 类 3 段共 35 个码点（`D_CLASS_CODE_POINTS` 与
     * `divergenceBlocks` 两条断言），补 `U+203E` 必须同批把该表改成 4 段 36 个，否则
     * `:render:test` 报红；本路文件面不含 `render` 测试，故只登记、不落码。
     * 语料 3,971 个 ERB 与两份基准文本里 `U+203E` 零命中 ⇒ 当前读数不受影响。
     * （2026-10-22 续任以逐字节扫描复核：全语料 `*.ERB`/`*.erb` 3,818 个文件 + 两份基准文本，
     * UTF-8 形 `E2 80 BE` 共 0 命中。）
     *
     * TODO(verify)：**组合符（Unicode `Mn`，`U+0300..U+036F`）按「表外字 ⇒ 2 格」是兜底的顺带结果，
     * 不是口径结论**。本机 Temurin 17 `windows-31J` 实测 `U+0300`/`U+0301` 各编成**单个替换字节
     * `3f`**（`U+1F600` 同）⇒ `EmueraConsole.cs:3255` 的字节数口径给 1 格；原版屏上宽度又是 GDI
     * **整串**测量（`StringMeasure.cs:51-72`），组合符并进基字簇、不另占一位 —— 两条口径都不是 2。
     * **可达性实测**：全语料 3,818 个 ERB 逐字节扫描命中 **338 个组合附加符**，分布在 6 个口上文件
     * （最多一处 = `ERB/口上・メッセージ関連/個人口上/120 Aunn [あうん]/阿吽海外版/
     * M_KOJO_K120_日常系コマンド.ERB` 319 个），两份基准文本 0 命中 ⇒ 黄金基准读数不受影响，
     * 但这几条口上行在折行上会逐符偏 1 格。与 `core/fn/GraphicsGfxAuditTest.kt:294` 登记的那条
     * `e + U+0301` GFX 宽度分歧（PC 8 像素 vs 移植侧 24）是同一笔结构性债；正解是**字簇
     * （grapheme cluster）步进**，归 M4 渲染层与折行侧（本路禁改 `LineBufferConsoleSink`），
     * 且改判会同时动 `FontSidecarMetricsTest` 钉住的用例 ⇒ 登记不修正。
     */
    fun isFullWidth(cp: Int): Boolean = !isHalfWidth(cp)

    /** [isFullWidth] 的补集：CP932 里占**一个字节**的四档（逐档出处见 [isFullWidth]）。 */
    private fun isHalfWidth(cp: Int): Boolean = when {
        cp in 0x0020..0x007E -> true
        cp in 0xFF61..0xFF9F -> true
        cp in 0x0000..0x001F -> true
        cp == 0x007F || cp == 0x0080 -> true
        cp == 0x00A5 -> true
        else -> false
    }
}
