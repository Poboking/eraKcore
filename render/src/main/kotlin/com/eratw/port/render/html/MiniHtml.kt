package com.eratw.port.render.html

import com.eratw.port.core.fn.ColorNames
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import java.util.Locale

/**
 * mini-HTML → 显示列表段（M4-HTML 路，契约 CONTRACT-M4-B §2 m-html）。
 *
 * 自绘解析器，不引 WebView（验收报告 P3-4：排版一致性优先）。语义权威是反编译
 * `MinorShift.Emuera.UI.Game/HtmlManager.cs`（下称 HM，行号均指该文件；契约里写的
 * `Runtime.Script.Statements/HtmlManager.cs` 与 `HtmlParser.cs` 在本 reference 快照
 * 不存在，HTML 解析器就是 HM 的 `tagAnalyze` :951-1678 与主循环 :529-712）。
 *
 * 支持的子集 = 原版真正用到的标签（语料 4.980 实测统计）：
 * `p[align]`、`nobr`、`b`/`i`/`s`/`u`、`br`、裸换行、`font[color|bcolor|face]`、`img`、
 * 注释 `<!-- -->` 与实体转义。HM 还认 `button`/`nonbutton`/`clearbutton`/`div`/`shape`
 * （:1005-1035、:1225-1475、:1476-1600），本 lane 按契约不覆盖，命中即抛
 * [MiniHtmlErrorKind.UNSUPPORTED_TAG]（需组长接线：带按钮的 HTML_PRINT 路径要么走
 * console-1，要么扩本路）。
 *
 * 原版行为照抄清单（ADR-005，逐条带行号，不做「顺手修好」）：
 * - 样式位是「状态机 + 异或关闭」而非标签栈：`<b><i></b></i>` 交叉闭合合法
 *   （开 :1075-1080 只 `|=`；闭 :977-983 校验位存在后 `^=`，不比对标签名）。
 * - 同位重复开 (`<b><b>`) 抛 DuplicateTag（:1075-1078）。
 * - `nobr`/`p` 只在「串首」有效——`LineHead` 初值 true，只在出现文本段后置 false，
 *   标签与换行都不重置（:94、:574、:1093-1096、:1109-1112）。
 * - `<p>` 必须带恰好一个属性 `align`，`nobr` 在 `p` 之后开是合法的（DisplayLine2Html
 *   的外壳顺序就是 `<p align=...><nobr>`，:393-402），反过来 `<nobr><p ...>` 抛
 *   DuplicateTag（:1113-1116）。
 * - `</p>`/`</nobr>` 之后再出现文本分别抛 TextAfterP/TextAfterNobr（:560-568）；
 *   但 `</p>`/`</nobr>` 缺失本身不报错（:667-674 只查 div/button/样式/字体栈）。
 * - `&nbsp;` 还原成半角空格 `U+0020` 而不是 U+00A0（:783-785）。
 * - 不带 `#` 的颜色：十六进制串能解析却仍被拒绝（InvalidColorName2，:1712-1720）。
 * - 未知开标签 / 属性结构不合法统一抛 HtmlTagError（:1677）；未知闭标签抛
 *   CanNotInterpretCloseTag（:1036-1037）。
 * - `<font face=''>` 落**默认字体**而不是外层帧的字体：帧的缺省值是 null（缺属性才继承外层，
 *   :1663-1666），空串交给 `StringStyle` 后被换成 `Config.FontName`（`StringStyle.cs:41-48`）。
 * - 数字引用与 `#` 颜色的十六进制解析是 .NET `Convert.ToInt32(s, 16)` 的口径，吃 `0x` 前缀与
 *   一个前导 `+`、不吃空白、基 16 不收负号、**8 位十六进制按 32 位回绕**（:821、:1692、:1714；
 *   逐值表在 [parseInt32Hex]）。
 * - `<br>`（及裸换行）断行在串尾不产生空尾行（PrintStringBuffer.cs:258-261
 *   只在残留非空时成行）。
 *
 * @property defaultFore 默认前景 `0xRRGGBB`（Config.ForeColor 默认 192,192,192，
 *   ConfigData.cs:68；HM :130）
 * @property defaultBack 默认背景（Config.BackColor 默认黑，ConfigData.cs:69；
 *   原版每段不带背景，背景是全局量，这里落进 [TextRun.back] 供显示列表直接使用）
 * @property defaultFontName 默认字体名（Config.FontName 默认「ＭＳ ゴシック」，
 *   ConfigData.cs:65；HM :138、StringStyle.cs:22-31）
 */
class MiniHtml(
    val defaultFore: Int = FORE_DEFAULT,
    val defaultBack: Int = BACK_DEFAULT,
    val defaultFontName: String = FONT_DEFAULT,
) {

    /**
     * 解析：标签栈/状态机按 HM `html2DisplayLine`（:529-712）+ `tagAnalyze`（:951-1678）
     * 逐行复刻。任何原版抛 CodeEE 的点都抛 [MiniHtmlException]。
     */
    fun parse(html: String): MiniHtmlDoc = Parser(html).run()

    /**
     * 展平成 [TextRun] 序列（逻辑行间插入 `"\n"` 的 [RunKind.HTML] 哨兵段；
     * `img` 是 `text` 为空串的 [RunKind.HTML] 占位段）。样式文本段 kind 恒为
     * [RunKind.TEXT]（对应原版 `ConsoleStyledString`，ConsoleStyledString.cs:21-40）。
     */
    fun toRuns(html: String): List<TextRun> = parse(html).runs

    /**
     * 纯文本长度，口径 = core-vm `HTML_STRINGLEN` 的单位语义：**半角格**
     * （全角 2 / 半角 1，见 [Grid.cells]；像素口径 `HTML_STRINGLEN(html,1)` 在默认
     * FontSize 18 下等于本值乘 9）。基准 `pc-golden-root.txt:299`
     * `HTML_STRINGLEN=4`（输入 `<i>AB</i>CD`，GOLDEN_02_PRINT.ERB:255）即本函数的期望。
     *
     * 与 HM 的一致性：原版 `HtmlLength`（:168-182）只累加**第一条显示行**的宽度，
     * 即首个 `br`/换行之前的全部文本；`img` 宽度在本 lane 记 0（core 同款近似，
     * 原版取图片像素宽，HtmlManager.cs:279-300 的 img 分支 + ConsoleImagePart）。
     * TODO(verify)：原版 HtmlLength 还受窗口宽自动折行影响（ButtonsToDisplayLines :698），
     * core 与本 lane 都未模拟——基准里没有触发自动折行的 HTML_STRINGLEN 用例。
     *
     * 注意与 core 的一处口径冲突（写进交付报告，不由本路修改）：原版
     * `HtmlLength` 走完整 `Html2DisplayLine`，**畸形 HTML 会抛 CodeEE**；
     * core 的 `htmlLength`（HtmlMethods.kt:269-296）是宽容扫描、不抛。本函数按原版抛。
     */
    fun plainLength(html: String): Int = parse(html).lines.firstOrNull()?.cells ?: 0

    /**
     * `HtmlManager.Escape`（:719-742 + 替换表 :164-166 rep/repDic）：单趟逐字符查表，
     * 转义集 `& > < " '`，`'` 用 `&apos;`。基准 `pc-golden-root.txt:301`
     * `HTML_ESCAPE=&lt;a&gt;`（输入 `<a>`，GOLDEN_02_PRINT.ERB:257）。
     */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val rep = ESCAPE_TABLE[ch]
            if (rep == null) sb.append(ch) else sb.append(rep)
        }
        return sb.toString()
    }

    /**
     * `HtmlManager.Unescape`（:744-834）：还原六内置实体（大小写不敏感 :780）与
     * 数字引用 `&#123;`/`&#x7b;`（:802-831，仅十/十六进制，0..65535）。
     * 官方行为：`&nbsp;` 还原成半角空格（:783-785）；错误分支全部抛异常。
     */
    fun unescape(text: String): String {
        if (text.indexOf('&') < 0) return text
        val sb = StringBuilder(text.length)
        var num = 0
        while (num < text.length) {
            var amp = text.indexOf('&', num)
            if (amp < 0) {
                sb.append(text, num, text.length)
                break
            }
            if (amp > num) sb.append(text, num, amp)
            num = amp
            val semi = text.indexOf(';', num)
            if (semi <= num + 1) {
                if (semi < 0) throw MiniHtmlException(MiniHtmlErrorKind.ENTITY_MISSING_SEMICOLON, text)
                throw MiniHtmlException(MiniHtmlErrorKind.ENTITY_AMP_SEMI, text)
            }
            val body = text.substring(num + 1, semi)
            num = semi + 1
            val lower = body.lowercase(Locale.ROOT)
            val named = NAMED_ENTITIES[lower]
            if (named != null) {
                sb.append(named)
                continue
            }
            if (lower[0] != '#') throw MiniHtmlException(MiniHtmlErrorKind.ENTITY_INVALID_REF, text)
            val hex = lower.length > 1 && lower[1] == 'x'
            val digits = if (hex) lower.substring(2) else lower.substring(1)
            // 十六进制分支的口径是 .NET `Convert.ToInt32(digits, 16)`（HM :821），不是 Java 的
            // `Integer.parseInt`：前缀 `0x` 与前导 `+` 都吃，负号一律非法。逐值差见
            // [parseInt32Hex] 的 KDoc 与 `html/MiniHtmlAuditTest` 的「0x 前缀」用例。
            val code = (if (hex) parseInt32Hex(digits) else digits.toIntOrNull(10))
                ?: throw MiniHtmlException(MiniHtmlErrorKind.ENTITY_INVALID_REF, text)
            if (code < 0 || code > 65535) throw MiniHtmlException(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, text)
            sb.append(code.toChar())
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // 解析器（每串一个实例，零共享状态）
    // -------------------------------------------------------------------------

    /** 字体帧：`HtmlAnalzeStateFontTag`（HM :64-72）；-1 表示未设（继承在外层合并，:1651-1667）。 */
    private inner class FontFrame(var color: Int, var bColor: Int, var face: String?)

    private inner class Parser(val html: String) {
        val lines = ArrayList<HtmlLine>()
        val segments = ArrayList<HtmlSegment>()
        val fonts = ArrayList<FontFrame>()
        var styleMask = 0
        var align = MiniHtmlAlign.LEFT
        var flagP = false
        var flagPClosed = false
        var flagNobr = false
        var flagNobrClosed = false
        var flagBr = false

        /** 原版 `HtmlAnalzeState.LineHead`（HM :94）：初值 true，只被文本段置 false（:574）。 */
        var lineHead = true
        var pos = 0

        fun run(): MiniHtmlDoc {
            // 注释与换行都是「整串预判」开关（HM :534-535），没有就不看
            val hasComment = html.contains("<!--")
            val hasReturn = html.indexOf('\n') >= 0
            while (pos < html.length) {
                var cut = html.indexOf('<', pos)
                if (hasReturn) {
                    val nl = html.indexOf('\n', pos)
                    if (nl >= 0 && (cut < 0 || cut > nl)) cut = nl
                }
                if (cut < 0) {
                    // 尾文本（:556-569）：先入段，再按 FlagPClosed / FlagNobrClosed 抛
                    addText(html.substring(pos))
                    if (flagPClosed) throw MiniHtmlException(MiniHtmlErrorKind.TEXT_AFTER_P, html)
                    if (!flagNobrClosed) break
                    throw MiniHtmlException(MiniHtmlErrorKind.TEXT_AFTER_NOBR, html)
                }
                if (cut > pos) {
                    addText(html.substring(pos, cut))
                    lineHead = false
                    pos = cut
                }
                if (hasComment && html.startsWith("<!--", pos)) {
                    val end = html.indexOf("-->", pos + 4)
                    if (end < 0) throw MiniHtmlException(MiniHtmlErrorKind.COMMENT_UNTERMINATED, html)
                    pos = end + 3
                    continue
                }
                if (html[pos] == '\n') {
                    flushLine()
                    pos++
                    continue
                }
                pos++
                val gt = tagAnalyze(pos)
                if (gt >= html.length || html[gt] != '>') {
                    throw MiniHtmlException(MiniHtmlErrorKind.TAG_UNTERMINATED, html)
                }
                pos = gt + 1
                if (flagBr) {
                    flushLine()
                    flagBr = false
                }
            }
            // 串尾残留非空才成行（HM :675-678 + PrintStringBuffer.cs:258-261）
            if (segments.isNotEmpty()) flushLine()
            // 收尾校验（HM :667-674）：样式/字体栈未清空即 TagIsNotClosed（div/button 不在子集）
            if (styleMask != 0 || fonts.isNotEmpty()) {
                throw MiniHtmlException(MiniHtmlErrorKind.TAG_NOT_CLOSED, html)
            }
            return MiniHtmlDoc(lines.toList(), align, flagNobr)
        }

        /** 复刻 `tagAnalyze`（HM :951-1678）。返回 `>` 的下标；找不到时返回 html.length 交由调用方抛。 */
        private fun tagAnalyze(start: Int): Int {
            if (start < html.length && html[start] == '/') {
                val gt = html.indexOf('>', start + 1)
                if (gt < 0) return html.length
                closeTag(html.substring(start + 1, gt).trim().lowercase(Locale.ROOT))
                return gt
            }
            var i = start
            while (i < html.length && html[i] != '>' && !html[i].isWhitespace()) i++
            val rawName = html.substring(start, i)
            i = skipWs(i)
            var attrs: List<Pair<String, String>>? = null
            if (i < html.length && html[i] != '>') {
                val parsed = readAttributes(i)
                attrs = parsed.first
                i = parsed.second
            }
            val name = rawName.lowercase(Locale.ROOT)
            // 空标签名（`<>`、`< >`）：HM :1056 跳过整个 switch，直落 :1677 HtmlTagError
            if (name.isEmpty()) throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
            openTag(name, attrs)
            return i
        }

        private fun openTag(name: String, attrs: List<Pair<String, String>>?) {
            when (name) {
                "b" -> styleOpen(Grid.STYLE_BOLD, attrs)
                "i" -> styleOpen(Grid.STYLE_ITALIC, attrs)
                "u" -> styleOpen(Grid.STYLE_UNDERLINE, attrs)
                "s" -> styleOpen(Grid.STYLE_STRIKE, attrs)
                "br" -> {
                    if (attrs != null) throw MiniHtmlException(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, html)
                    flagBr = true
                }
                "nobr" -> {
                    if (attrs != null) throw MiniHtmlException(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, html)
                    if (!lineHead) throw MiniHtmlException(MiniHtmlErrorKind.TAG_NOT_AT_LINE_HEAD, html)
                    if (flagNobr) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_TAG, html)
                    flagNobr = true
                }
                "p" -> openP(attrs)
                "font" -> openFont(attrs)
                "img" -> openImg(attrs)
                "button", "nonbutton", "clearbutton", "div", "shape" ->
                    throw MiniHtmlException(MiniHtmlErrorKind.UNSUPPORTED_TAG, html)
                else -> throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
            }
        }

        /** 样式开标签共用 `case "b"/"i"/"u"/"s"` 的 fallthrough 体（HM :1061-1080）。 */
        private fun styleOpen(bit: Int, attrs: List<Pair<String, String>>?) {
            if (attrs != null) throw MiniHtmlException(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, html)
            if (styleMask and bit != 0) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_TAG, html)
            styleMask = styleMask or bit
        }

        /** 闭标签共用 :965-1038；样式关闭不校验开标签名，只校验样式位存在后异或清除。 */
        private fun closeTag(name: String) {
            val bit = when (name) {
                "b" -> Grid.STYLE_BOLD
                "i" -> Grid.STYLE_ITALIC
                "u" -> Grid.STYLE_UNDERLINE
                "s" -> Grid.STYLE_STRIKE
                else -> 0
            }
            if (bit != 0) {
                if (styleMask and bit == 0) throw MiniHtmlException(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, html)
                styleMask = styleMask xor bit
                return
            }
            when (name) {
                "p" -> {
                    if (!flagP || flagPClosed) throw MiniHtmlException(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, html)
                    flagPClosed = true
                }
                "nobr" -> {
                    if (!flagNobr || flagNobrClosed) throw MiniHtmlException(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, html)
                    flagNobrClosed = true
                }
                "font" -> {
                    if (fonts.isEmpty()) throw MiniHtmlException(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, html)
                    fonts.removeAt(fonts.size - 1)
                }
                "button", "nonbutton", "clearbutton", "div" ->
                    throw MiniHtmlException(MiniHtmlErrorKind.UNSUPPORTED_TAG, html)
                else -> throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_CLOSE_TAG, html)
            }
        }

        private fun openP(attrs: List<Pair<String, String>>?) {
            if (attrs == null) throw MiniHtmlException(MiniHtmlErrorKind.P_WITHOUT_ATTRIBUTE, html)
            if (!lineHead) throw MiniHtmlException(MiniHtmlErrorKind.TAG_NOT_AT_LINE_HEAD, html)
            if (flagNobr) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_TAG, html)
            // 原版要求「identifier = literal」恰好一个属性后立刻 EOL（:1117-1125）
            if (attrs.size != 1) throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
            val (key, value) = attrs[0]
            if (!key.equals("align", ignoreCase = true)) {
                throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_P_ATTRIBUTE, html)
            }
            align = when (unescape(value).lowercase(Locale.ROOT)) {
                "left" -> MiniHtmlAlign.LEFT
                "center" -> MiniHtmlAlign.CENTER
                "right" -> MiniHtmlAlign.RIGHT
                else -> throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_ALIGN_VALUE, html)
            }
            flagP = true
        }

        private fun openFont(attrs: List<Pair<String, String>>?) {
            if (attrs == null) throw MiniHtmlException(MiniHtmlErrorKind.FONT_WITHOUT_ATTRIBUTE, html)
            val frame = FontFrame(-1, -1, null)
            for ((key, rawValue) in attrs) {
                val value = unescape(rawValue)
                when (key.lowercase(Locale.ROOT)) {
                    "color" -> {
                        if (frame.color >= 0) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        frame.color = stringToColor(value)
                    }
                    "bcolor" -> {
                        if (frame.bColor >= 0) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        frame.bColor = stringToColor(value)
                    }
                    "face" -> {
                        if (frame.face != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        frame.face = value
                    }
                    else -> throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_ATTRIBUTE, html)
                }
            }
            // 缺省属性从外层帧继承（HM :1651-1667）
            if (fonts.isNotEmpty()) {
                val outer = fonts.last()
                if (frame.color < 0) frame.color = outer.color
                if (frame.bColor < 0) frame.bColor = outer.bColor
                if (frame.face == null) frame.face = outer.face
            }
            fonts.add(frame)
        }

        private fun openImg(attrs: List<Pair<String, String>>?) {
            if (attrs == null) throw MiniHtmlException(MiniHtmlErrorKind.IMG_WITHOUT_ATTRIBUTE, html)
            var src: String? = null
            var srcB: String? = null
            var srcM: String? = null
            var width: Int? = null
            var height: Int? = null
            var yPos: Int? = null
            var widthPx = false
            var heightPx = false
            for ((key, rawValue) in attrs) {
                val value = unescape(rawValue)
                when (key.lowercase(Locale.ROOT)) {
                    "src" -> {
                        if (src != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        src = value
                    }
                    "srcb" -> {
                        if (srcB != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        srcB = value
                    }
                    "srcm" -> {
                        if (srcM != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        srcM = value
                    }
                    "height" -> {
                        if (height != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        val parsed = mixedNum(value)
                        height = parsed.first
                        heightPx = parsed.second
                    }
                    "width" -> {
                        if (width != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        val parsed = mixedNum(value)
                        width = parsed.first
                        widthPx = parsed.second
                    }
                    "ypos" -> {
                        if (yPos != null) throw MiniHtmlException(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, html)
                        yPos = mixedNum(value).first
                    }
                    else -> throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_ATTRIBUTE, html)
                }
            }
            if (src == null) throw MiniHtmlException(MiniHtmlErrorKind.IMG_SRC_MISSING, html)
            val fore = currentFore()
            segments.add(
                HtmlSegment.Image(
                    src = src,
                    srcB = srcB,
                    srcM = srcM,
                    width = width,
                    height = height,
                    yPos = yPos,
                    widthIsPx = widthPx,
                    heightIsPx = heightPx,
                    run = TextRun("", fore, defaultBack, styleMask, currentFont(), RunKind.HTML),
                )
            )
        }

        /** `Utils.ParseMixedNum`（Utils.cs:342-362）：整数或「整数+px」，其余抛数值解析失败。 */
        private fun mixedNum(value: String): Pair<Int, Boolean> {
            val trimmed = value.trim()
            if (trimmed.endsWith("px", ignoreCase = true)) {
                val n = trimmed.dropLast(2).trim().toIntOrNull()
                    ?: throw MiniHtmlException(MiniHtmlErrorKind.ATTRIBUTE_NOT_NUMBER, html)
                return n to true
            }
            val n = trimmed.toIntOrNull()
                ?: throw MiniHtmlException(MiniHtmlErrorKind.ATTRIBUTE_NOT_NUMBER, html)
            return n to false
        }

        /**
         * `stringToColorInt32`（HM :1680-1725）。
         *
         * 命名色不落本类：唯一权威是 core 层的 `com.eratw.port.core.fn.ColorNames`
         * （COLOR-SYNC 收口，契约 CONTRACT-M4-C §2 c-colorsync）。原版这里走 GDI+
         * `Color.FromName`（:1705）并以 `A == 0` 作「认不认」的判定（:1706），名单是框架内置的
         * KnownColor 表；`ColorNames` 逐项对齐该表得 140 名，取证命令与逐项等长等值断言写在
         * core 侧 ColorNamesTest。`:render -> :core-vm` 的单向依赖（契约 §0）正是为此准备的，
         * 收口前本类的第三份副本（60 名，含框架并不认识的 `grey` 系拼写）已删除——
         * `grey` 一类名字因此回到「未知名」口径（原版即如此，ADR-005）。
         *
         * 大小写不敏感口径不变：[ColorNames.rgb] 内部按 `Locale.ROOT` 小写化后查表。
         * `transparent` 不在表内（框架 `A == 0`），因此仍落到下面的
         * [MiniHtmlErrorKind.COLOR_TRANSPARENT_UNSUPPORTED] 分支；裸十六进制串能解析却仍被拒绝的
         * 两条官方怪癖（:1690-1701、:1712-1720）与本表无关，原样保留、逐条有测试守着。
         */
        private fun stringToColor(value: String): Int {
            if (value.isEmpty()) throw MiniHtmlException(MiniHtmlErrorKind.COLOR_REQUIRED, html)
            if (value[0] == '#') {
                // 官方结构照抄：范围检查写在 try 内（:1690-1697），抛出的 OoRColorValue
                // 被同块的裸 catch 吞掉、实际报 CanNotInterpretNumValue（:1698-1701）。
                // 即 COLOR_OUT_OF_RANGE 在原版是死代码，这里同样永不抛它。
                val digits = value.substring(1)
                val num = parseInt32Hex(digits)
                    ?: throw MiniHtmlException(MiniHtmlErrorKind.COLOR_UNPARSABLE, html)
                if (num < 0 || num > 0xFFFFFF) throw MiniHtmlException(MiniHtmlErrorKind.COLOR_UNPARSABLE, html)
                return num
            }
            val named = ColorNames.rgb(value)
            if (named != null) return named
            if (ColorNames.isTransparent(value)) {
                throw MiniHtmlException(MiniHtmlErrorKind.COLOR_TRANSPARENT_UNSUPPORTED, html)
            }
            // 官方怪癖：裸十六进制能解析，但仍抛 InvalidColorName2（:1712-1720）。
            // 「能解析」这一判定同样走 `Convert.ToInt32(str, 16)`（:1714），所以 `0x41` 也算
            // 可解析 —— 见 [parseInt32Hex]。
            if (parseInt32Hex(value) != null) throw MiniHtmlException(MiniHtmlErrorKind.COLOR_NAME_REJECTED, html)
            throw MiniHtmlException(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, html)
        }

        /**
         * 属性词法：`name = 'literal'` 三元组表（原版 `LexicalAnalyzer.Analyse` :1049
         * 产 WordCollection，font/img 按三元组循环消费，如 :1612-1621）。
         * 结构不合语法统一落 [MiniHtmlErrorKind.TAG_ERROR]（对应 :1677 的 break 路径）。
         */
        private fun readAttributes(from: Int): Pair<List<Pair<String, String>>, Int> {
            val out = ArrayList<Pair<String, String>>()
            var i = from
            while (i < html.length && html[i] != '>') {
                val nameStart = i
                while (i < html.length && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>') i++
                if (i == nameStart) throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
                val name = html.substring(nameStart, i)
                i = skipWs(i)
                if (i >= html.length || html[i] != '=') throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
                i++
                i = skipWs(i)
                if (i >= html.length || (html[i] != '\'' && html[i] != '"')) {
                    throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
                }
                val quote = html[i]
                i++
                val valueStart = i
                while (i < html.length && html[i] != quote) i++
                if (i >= html.length) throw MiniHtmlException(MiniHtmlErrorKind.TAG_ERROR, html)
                out.add(name to html.substring(valueStart, i))
                i++
                i = skipWs(i)
            }
            return out to i
        }

        private fun skipWs(from: Int): Int {
            var i = from
            while (i < html.length && html[i].isWhitespace()) i++
            return i
        }

        /** 文本段：先 Unescape 再按当前样式快照（HM :558-559、:572-573 + GetSS :128-149）。 */
        private fun addText(chunk: String) {
            val text = unescape(chunk)
            segments.add(
                HtmlSegment.Text(
                    TextRun(text, currentFore(), defaultBack, styleMask, currentFont(), RunKind.TEXT)
                )
            )
        }

        private fun currentFore(): Int {
            val top = fonts.lastOrNull()
            return if (top != null && top.color >= 0) top.color else defaultFore
        }

        /**
         * 当前字体名：取最内层 `<font face>`，**空串落默认字体**。
         *
         * 出处 `StringStyle` 的构造式（`StringStyle.cs:41-48`）：`fontname` 为空或 null 时
         * 一律换成 `Config.FontName`，而 `GetSS` 把最内层帧的 `FontName` 原样交进去
         * （`HtmlManager.cs:138,149`）。原版帧的缺省值是 `null`（只在缺属性时继承外层，
         * `HtmlManager.cs:1663-1666`），显式写 `face=''` 得到的是**空串而不是外层值**，
         * 所以它落默认字体 —— 这条与「空串 = 没写」是两件事，不许合并。
         */
        private fun currentFont(): String {
            val face = fonts.lastOrNull()?.face
            return if (face.isNullOrEmpty()) defaultFontName else face
        }

        private fun flushLine() {
            lines.add(HtmlLine(segments.toList()))
            segments.clear()
        }
    }

    companion object {
        /** Config.ForeColor 默认（ConfigData.cs:68，192,192,192）。 */
        const val FORE_DEFAULT: Int = 0xC0C0C0

        /** Config.BackColor 默认（ConfigData.cs:69，黑）。 */
        const val BACK_DEFAULT: Int = 0x000000

        /**
         * Config.FocusColor 默认（ConfigData.cs:70），与 [TextRun.DEFAULT_FOCUS_COLOR] 同一来源。
         * 本解析器只校验 `bcolor` 的形态、尚未把它落到段上（屏上显示路径的 `bcolor`
         * 归 `adapter/HtmlRunFlatten`）；这里保留常量供将来接线。
         */
        const val FOCUS_DEFAULT: Int = TextRun.DEFAULT_FOCUS_COLOR

        /** Config.FontName 默认（ConfigData.cs:65）。 */
        const val FONT_DEFAULT: String = "ＭＳ ゴシック"

        /** `HtmlManager.cs:164-166` 的 rep/repDic 替换表（同 core `htmlEscape`，防两处漂移）。 */
        private val ESCAPE_TABLE: Map<Char, String> = mapOf(
            '&' to "&amp;",
            '>' to "&gt;",
            '<' to "&lt;",
            '"' to "&quot;",
            '\'' to "&apos;",
        )

        /** 内置实体表（HM :781-800；`nbsp` 还原半角空格是官方行为）。 */
        private val NAMED_ENTITIES: Map<String, String> = mapOf(
            "nbsp" to " ",
            "amp" to "&",
            "gt" to ">",
            "lt" to "<",
            "quot" to "\"",
            "apos" to "'",
        )

        /**
         * 基数 16 的整数解析，口径 = .NET `Convert.ToInt32(string, 16)`
         * （`HtmlManager.cs:821` 的数字引用分支、`:1692` 的 `#` 颜色分支、`:1714`
         * 的「裸十六进制能不能解析」判定共用这一个函数）。
         *
         * 与 Java/Kotlin `toIntOrNull(16)` 的**逐值差异**（本机 `dotnet 9.0.200` 实测，
         * 命令与输出抄在 `html/MiniHtmlAuditTest` 的 `基 16 逐值表` 用例里）：
         *
         * | 输入 | .NET `Convert.ToInt32(s, 16)` | Kotlin `toIntOrNull(16)` |
         * |---|---|---|
         * | `41` / `00000041` / `7fffffff` | 65 / 65 / 2147483647 | 同值 |
         * | `+41` | 65 | 65 |
         * | `0x41` / `0X41` / `+0x41` | 65 | null |
         * | `0x` / `0xFF__`（非十六进制）/ `0x0x41` | FormatException | null |
         * | `-ff` / `-0x41` | ArgumentException（基 16 不收负号） | null（负数在调用方都是「失败」） |
         * | `" 41"` / `"41 "` / `"\t41"`（含空白） | FormatException（**不吃**空白） | null |
         * | `++41` / `+-41` / `+ 41` / `0x+41` / `0x-41` | FormatException（只吃**一个**前导 `+`） | null |
         * | `""` | ArgumentOutOfRangeException | null |
         * | `ffffffff` / `80000000` / `0xffffffff` | -1 / -2147483648 / -1（按 32 位回绕） | 同值 |
         * | `FFFFFFFFF` / `100000000`（溢出） | OverflowException | null |
         *
         * 以上读数全部由本机 `dotnet 9.0.200` 跑 `Convert.ToInt32(s, 16)` 实测：
         * 命令 `dotnet new console && dotnet run`（程序体就是 `try { Convert.ToInt32(s, 16) }
         * catch (Exception e) { e.GetType().Name }` 逐值打印）。三件事必须照抄：
         *
         * 1. `0x` 前缀（组内第一处真分歧）：`&#x0x41;`、`color='#0x…'`、`color='0x41'`
         *    三条输入在原版都成立，收口前本类一律判成「解析失败」。
         * 2. **8 位十六进制按 32 位回绕**：`Convert.ToInt32("ffffffff", 16)` 是 -1，不是溢出；
         *    Kotlin 的 `toIntOrNull(16)` 却以 `Int.MAX_VALUE` 为上限、直接给 null。这条会改
         *    **错误类别**：`&#xffffffff;` 在 .NET 解析成功后落进 `num4 < 0` 的越界支
         *    （`HtmlManager.cs:827-830` 的 `OoRUnicodeHtml`），而本仓原本报
         *    `InvalidCharacterReference`；`color='ffffffff'` 在 .NET 是「能解析 ⇒
         *    InvalidColorName2」（`:1714`→`:1720`），本仓原本报 `InvalidColorName`。
         * 3. **只吃一个前导 `+`**（第二处真分歧）：`"++41"`、`"+-41"`、`"0x+41"`、`"0x-41"`
         *    在 .NET 全是 FormatException，而 `toIntOrNull` 会再吃一个符号。
         *
         * 其余各行两边的**错误类别**相同（数字引用 → InvalidCharacterReference；
         * 颜色 → CanNotInterpretNumValue），故只需对齐「能不能解析」。
         * 实现因此不走 `toIntOrNull`：手写「逐位累加 + 上限 `0xFFFFFFFF` + 末尾 `toInt()` 回绕」，
         * 与 .NET 的 `uint` 累加器 + `(int)` 强转同式。
         *
         * @return 解析结果（可能为负，即 32 位回绕后的值）；.NET 会抛异常的那些输入给 null
         */
        internal fun parseInt32Hex(text: String): Int? {
            var s = text
            if (s.startsWith("+")) {
                s = s.substring(1)
            }
            // 只吃一个前导 `+`（上表 `++41`/`+-41` 两行）：Java 的 parseInt 还会再吃一个
            // 符号，那会让 .NET 判「解析失败」的输入在本类变成合法数字。
            if (s.isEmpty() || s[0] == '+' || s[0] == '-') {
                return null
            }
            if (s.length > 2 && (s.startsWith("0x") || s.startsWith("0X"))) {
                s = s.substring(2)
                // `0x+41` / `0x-41` 在 .NET 是 FormatException；剥完前缀同样要复验。
                if (s.isEmpty() || s[0] == '+' || s[0] == '-') {
                    return null
                }
            }
            if (s.isEmpty()) {
                return null
            }
            var value = 0L
            for (ch in s) {
                val digit = Character.digit(ch, 16)
                if (digit < 0) {
                    return null
                }
                value = value * 16 + digit
                // .NET 的累加器是 uint：超过 32 位才是 OverflowException（上表最后一行）
                if (value > 0xFFFFFFFFL) {
                    return null
                }
            }
            return value.toInt()
        }
    }
}
