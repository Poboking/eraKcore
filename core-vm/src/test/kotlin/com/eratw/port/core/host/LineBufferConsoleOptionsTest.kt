package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 引擎级选项快照（[LineBufferConsoleSink.currentOptions]）的口径钉子。
 *
 * 判据 = 原版按钮对象 + 世代，不做文本形态分类：
 * - 只有**取值等待**才有选项；WAIT/ENTER 页即使正文里有 `[451]` 也返回空；
 * - 可点集合 = 全部现存显示行/浮岛里 `generation == lastButtonGeneration` 的按钮
 *   （`EmueraConsole.cs:2439`、`:2456`；世代推进见 `:783-823`）；
 * - 脚本走到另一行而本页没印按钮 ⇒ 旧页按钮作废；同一脚本行重试 ⇒ 世代不动；
 * - 取值形态（INT↔STR）切换而本页没重印按钮 ⇒ 旧按钮按原版作废（`newGeneration`
 *   的 `lastButtonIsInput` 分支）；
 * - 数值等待只收 Long、字符串等待两种值都收；
 * - 正文里的 `[451]` 在原版同样是按钮 —— 不按前缀形态降级（本类第一条即钉此口径）；
 * - PRINTPLAIN 不参与、HTML/浮岛按钮走同一份快照；
 * - **合并只认源按钮身份**（`ConsoleOption.groupId`）：一条按钮折行/跨 `<br>` 重放并成一枚，
 *   屏上两颗不同的按钮即使值相同也各列一枚（地图「2」与同屏菜单「2」谁也不吞谁）。
 */
class LineBufferConsoleOptionsTest {

    private fun sink(): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 40, maxLines = 200)

    /**
     * [line] = 原版 `process.getCurrentLine` 的身份位；缺省 -1 时控制台退回行水位
     * （与宿主未接线时的生产回退同一条路）。
     */
    private fun note(
        sink: LineBufferConsoleSink,
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        line: Long = -1L,
    ) {
        sink.noteInputWait(InputRequest(type = type, form = form, scriptLine = line))
    }

    @Test
    fun `自动按钮进入当前页选项`() {
        val s = sink()
        s.printLine("[0] 开始游戏")
        note(s)

        val opts = s.currentOptions()
        assertEquals(1, opts.size)
        assertEquals(0L, opts.single().value)
        assertEquals("开始游戏", opts.single().label)
        assertEquals(OptionOrigin.AUTO_TEXT, opts.single().origin)
    }

    /**
     * 正文 `[0]` 的自动按钮与 `PRINTBUTTON("[0]")` 是**两条源按钮**、值都是 0 ⇒ 栏里两枚都列。
     *
     * 旧口径按值先到先得，把后出现的那颗吞了 —— 与「地图 2 / 同屏另一枚 2 认不到」是同一条
     * 病根（2026-10-29 玩家反馈）。来源优先级（`ConsoleOptionFilter.prefer`）现在只在
     * **同一条源按钮**的几条重放之间挑代表，不再跨按钮生效。
     */
    @Test
    fun `同值的自动按钮与显式按钮各列一枚`() {
        val s = sink()
        s.printLine("[0] 自动")
        s.printButton("[0] 显式", 0L)
        s.newLine()
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(0L, 0L), opts.map { it.value }, "两条源按钮同值 ⇒ 两枚候选，谁也不吞谁")
        assertEquals(listOf("自动", "显式"), opts.map { it.label })
        assertEquals(
            listOf(OptionOrigin.AUTO_TEXT, OptionOrigin.EXPLICIT),
            opts.map { it.origin },
            "顺序按屏上行序，不按来源优先级重排",
        )
    }

    @Test
    fun `非取值等待不列选项_故事正文的自动按钮不进栏`() {
        val s = sink()
        s.printLine("「那里不是有自慰[451] 吗？」")
        note(s, form = InputRequest.RequestForm.ENTER_KEY)

        assertTrue(s.currentOptions().isEmpty(), "ENTER 等待没有选项；实际=${s.currentOptions()}")
    }

    /**
     * 原版 `ButtonStringCreator` 把打印文本里的 `[451]` 做成真的 `ConsoleButtonString`
     * （`PrintStringBuffer.fromCssToButton`，PrintStringBuffer.cs:280-292）；取值等待里
     * 它的世代同样当前 ⇒ 与正文里的真选项一样可点。旧实现按前缀形态把它降级，
     * 那是移植侧自造的置信度层，已删除。
     */
    @Test
    fun `正文内嵌的自动按钮按原版同样可点`() {
        val s = sink()
        s.printLine("「那里不是有自慰[451] 吗？」")
        note(s, type = InputRequest.InputType.STR)

        assertEquals(listOf<Any>(451L), s.currentOptions().map { it.value })
    }

    @Test
    fun `页面前进后旧菜单不复活_即使新页只打印文字`() {
        val s = sink()
        s.printLine("[0] 是    [1] 否")
        note(s)
        assertEquals(2, s.currentOptions().size)

        s.printLine("确定吗？")
        note(s, form = InputRequest.RequestForm.ENTER_KEY)
        assertTrue(s.currentOptions().isEmpty(), "WAIT 期间右侧栏应清空")

        note(s)
        assertTrue(s.currentOptions().isEmpty(), "新页没有按钮，旧页菜单不能翻出来；实际=${s.currentOptions()}")
    }

    @Test
    fun `取值等待前只打印了正文_旧菜单不复活`() {
        val s = sink()
        s.printLine("[0] 是    [1] 否")
        note(s)
        assertEquals(2, s.currentOptions().size)

        s.printLine("请输入数字：")
        note(s)
        assertTrue(s.currentOptions().isEmpty(), "新页只有文字没有按钮；实际=${s.currentOptions()}")
    }

    /**
     * 原版 `newGeneration()`（EmueraConsole.cs:783-823）用**脚本行身份**判页面推进：
     * 同一行重试世代不动（按钮仍可点）；换行且没重印按钮 ⇒ `lastButtonGeneration`
     * 前移，旧按钮作废。这里直接喂 `scriptLine`，不靠打印水位。
     */
    @Test
    fun `同一脚本行重试保留按钮_换行即作废`() {
        val s = sink()
        s.printLine("[0] 是    [1] 否")
        note(s, line = 100L)
        val first = s.currentOptions()
        assertEquals(2, first.size)

        note(s, line = 100L)
        assertEquals(first, s.currentOptions(), "同一 INPUT 行重试不能把选项清空")

        note(s, line = 101L)
        assertTrue(s.currentOptions().isEmpty(), "脚本换行且没重印按钮 ⇒ 旧页按钮作废")
    }

    @Test
    fun `只收当前世代的按钮_旧页不复活`() {
        val s = sink()
        s.printLine("[0] 旧页")
        note(s)
        assertEquals(listOf(0L), s.currentOptions().map { it.value })

        s.printLine("[1] 新页")
        note(s)
        assertEquals(listOf(1L), s.currentOptions().map { it.value }, "旧页按钮不应再次出现")
    }

    @Test
    fun `数值等待只收 Long_字符串等待两种都收`() {
        val s = sink()
        s.printButton("exit", "exit")
        s.newLine()
        s.printLine("[3] 数值")
        note(s, type = InputRequest.InputType.INT)
        assertEquals(listOf(3L), s.currentOptions().map { it.value }, "INT 等待必须排除字符串按钮")

        // 换页重印：INT→STR 的形态切换在原版会把旧世代推掉（newGeneration 的
        // lastButtonIsInput 分支，EmueraConsole.cs:800-823），所以本页必须重新打印。
        s.printButton("exit", "exit")
        s.newLine()
        s.printLine("[3] 数值")
        note(s, type = InputRequest.InputType.STR, line = 200L)
        assertEquals(setOf<Any>(3L, "exit"), s.currentOptions().map { it.value }.toSet())
    }

    /**
     * 取值形态切换而本页没重印按钮：原版 `newGeneration()` 的 INT/STR 分支会在
     * 形态变化时把 `lastButtonGeneration` 推到 `newButtonGeneration` ⇒ 旧按钮作废。
     */
    @Test
    fun `取值形态切换且未重印按钮时旧世代作废`() {
        val s = sink()
        s.printButton("exit", "exit")
        s.newLine()
        s.printLine("[3] 数值")
        note(s, type = InputRequest.InputType.INT, line = 300L)
        assertEquals(listOf(3L), s.currentOptions().map { it.value })

        note(s, type = InputRequest.InputType.STR, line = 300L)
        assertTrue(s.currentOptions().isEmpty(), "INT→STR 未重印 ⇒ 原版把旧世代推掉")
    }

    @Test
    fun `HTML 按钮与浮岛按钮都从引擎原文解析`() {
        val s = sink()
        s.printHtml(
            "<button value='0'><shape type='space' param='63px'><font color='#777777'>[   0] - ----</font></button>",
            option = false,
        )
        s.addHtmlIsland("<div><button value='5'>能力编辑</button></div>")
        note(s)

        val opts = s.currentOptions()
        val zero = opts.single { it.value == 0L }
        assertEquals(OptionOrigin.HTML, zero.origin)
        assertEquals("- ----", zero.label)
        val five = opts.single { it.value == 5L }
        assertEquals("能力编辑", five.label)
        assertEquals(OptionOrigin.HTML, five.origin)
    }

    @Test
    fun `浮岛被清后旧快照不残留`() {
        val s = sink()
        s.addHtmlIsland("<div><button value='5'>能力编辑</button></div>")
        note(s)
        assertTrue(s.currentOptions().any { it.value == 5L })

        s.clearHtmlIslands()
        note(s)
        assertTrue(s.currentOptions().isEmpty(), "浮岛清空后不能再点旧按钮；实际=${s.currentOptions()}")
    }

    /**
     * 原版进入等待前会 `PrintFlush(force: false)`（EmueraConsole.cs:1094→:1107）：
     * `HTML_PRINT …, 1`（打印缓冲里的按钮段）与未收尾的 PRINTN 都在等待点上屏。
     * 标题页的五子棋按钮就是这样出现的 —— 不 flush 的话它永远留在缓冲里。
     */
    @Test
    fun `等待点先落未收尾的 HTML 按钮段再冻结选项`() {
        val s = sink()
        s.printLine("[0] 开始游戏")
        s.printHtml("<div><button value='4'>【欢落五子棋】</button></div>", option = true)
        note(s)

        val four = s.currentOptions().single { it.value == 4L }
        assertEquals("【欢落五子棋】", four.label)
        assertEquals(OptionOrigin.HTML, four.origin)
    }

    /**
     * 浮岛按钮的时效 = 原版世代：浮岛打印后若又落了新行并换了脚本行，
     * 等待点上的 `lastButtonGeneration` 已前移（EmueraConsole.cs:783-823），旧浮岛按钮不可点。
     * 同一页重试（同脚本行）仍算本页。
     */
    @Test
    fun `浮岛按钮只在其后没落新行时算当前页`() {
        val s = sink()
        s.addHtmlIsland("<div><button value='5'>能力编辑</button></div>")
        note(s)
        assertTrue(s.currentOptions().any { it.value == 5L }, "浮岛是最后一笔输出时属于本页")

        // 同页重试：没有新行，候选沿用。
        note(s)
        assertTrue(s.currentOptions().any { it.value == 5L }, "同页重试不得把浮岛按钮丢掉")

        // 页面推进：新行落地后旧浮岛按钮过期。
        s.printLine("新页面的正文")
        s.printLine("[7] 新选项")
        note(s)
        assertEquals(listOf<Any>(7L), s.currentOptions().map { it.value }, "旧浮岛按钮不得跟着新页复活")
    }

    /**
     * 语料剧本选择页的按钮把 `<br>` 塞进 `title`：
     * `title='初始剧本<br>内容量适中…'`。旧正则 `<button([^>]*)>` 会在 title 里的
     * 第一个 `>` 截断，把标题文本当正文（真机 label 串位）。
     */
    @Test
    fun `HTML 属性引号内的尖括号不截断按钮标签`() {
        val s = sink()
        s.printHtml(
            "<button value='2010' title='初始剧本<br>内容量适中（有效输出行：7290）'>" +
                "<font color='lime'>　　　└ 初始剧本</font></button>",
            option = false,
        )
        note(s, type = InputRequest.InputType.STR)

        val opt = s.currentOptions().single()
        assertEquals(2010L, opt.value)
        assertEquals("└ 初始剧本", opt.label, "标签取按钮正文，不得混入 title 属性")
    }

    /** `value` 在 title 之后、`=` 两侧带空格，仍要取到值。 */
    @Test
    fun `HTML value 属性顺序与等号空白都兼容`() {
        val s = sink()
        s.printHtml(
            "<button title = '当前显示角色均为有多个剧本角色' value = '1004' >　　［过滤功能：开启］　　</button>",
            option = false,
        )
        note(s, type = InputRequest.InputType.STR)

        val opt = s.currentOptions().single()
        assertEquals(1004L, opt.value)
        assertEquals("［过滤功能：开启］", opt.label)
    }

    /** `<buttonx>` 不是 `<button>`；不能因为它把后面的按钮漏掉。 */
    @Test
    fun `按钮标签名边界严格匹配`() {
        val s = sink()
        s.printHtml("<buttonx value='9'>假</buttonx><button value='1'>真</button>", option = false)
        note(s)

        assertEquals(listOf(1L), s.currentOptions().map { it.value })
    }

    @Test
    fun `HTML 注释里的 button 不当选项`() {
        val s = sink()
        s.printHtml("<!-- <button value='9'>旧面板</button> --><button value='1'>新面板</button>", option = false)
        note(s)

        assertEquals(listOf(1L), s.currentOptions().map { it.value })
    }

    @Test
    fun `HTML 数值值保留 Long_大数值没有序号徽标`() {
        val s = sink()
        s.printHtml("<button value='10000'>[ EXIT ]</button>", option = false)
        note(s)

        val exit = s.currentOptions().single()
        assertEquals(10000L, exit.value)
        assertEquals(null, exit.ordinal)
        assertEquals("[ EXIT ]", exit.label)
    }

    /**
     * 游戏允许「标签印 27、按钮值 227」：地图/据点列表的 `MAIN_MAP*100+LOCAL`
     * （`DRAW_MAP.ERB:467`）。提交值必须原样用按钮 value，**不能**从标签前缀反推；
     * 前缀与 value 不一致时，标签原文保留 `[27]`、徽标不显示（不把 227 冒充成屏上的 27）。
     */
    @Test
    fun `标签前缀与按钮值不一致时以按钮值为准`() {
        val s = sink()
        s.printButton("[27] 示例地点", 227L)
        s.newLine()
        note(s, type = InputRequest.InputType.STR)

        val opt = s.currentOptions().single()
        assertEquals(227L, opt.value)
        assertEquals("227", opt.submitValue)
        assertEquals(null, opt.ordinal, "标签 27 ≠ 值 227，徽标不能写 227")
        assertEquals("[27] 示例地点", opt.label, "前缀与值不一致时保留屏上原文")
    }

    @Test
    fun `HTML 按钮同样以 value 为准_不解析标签前缀`() {
        val s = sink()
        s.printHtml("<button value='227'>[27] 示例地点</button>", option = false)
        note(s, type = InputRequest.InputType.STR)

        val opt = s.currentOptions().single()
        assertEquals(227L, opt.value)
        assertEquals("227", opt.submitValue)
        assertEquals(null, opt.ordinal)
        assertEquals("[27] 示例地点", opt.label)
    }

    @Test
    fun `标签前缀与值一致时才剥前缀并显示徽标`() {
        val s = sink()
        s.printLine("[27] 示例地点")
        note(s)

        val opt = s.currentOptions().single()
        assertEquals(27L, opt.value, "自动按钮的值来自 ButtonStringCreator 解析，不是 UI 反推")
        assertEquals(27, opt.ordinal)
        assertEquals("示例地点", opt.label)
    }

    @Test
    fun `大数值标签前缀保留_不丢屏上编号`() {
        val s = sink()
        s.printButton("[2000] 能力编辑", 2000L)
        s.newLine()
        note(s)

        val opt = s.currentOptions().single()
        assertEquals(2000L, opt.value)
        assertEquals(null, opt.ordinal)
        assertEquals("[2000] 能力编辑", opt.label, "大数值没有徽标，前缀是屏上唯一编号")
    }

    /**
     * AWAIT 轮询屏（QoL 地图/外出）不走 INPUT，`currentOptions()` 会停在上一个真正的
     * INPUT 页；`displayOptions()` 必须返回当前世代的按钮，宿主在没有等待方时用它兜底。
     */
    @Test
    fun `无等待时 displayOptions 仍列出当前世代按钮`() {
        val s = sink()
        s.printLine("[0] 是    [1] 否")
        note(s, form = InputRequest.RequestForm.ENTER_KEY)

        assertTrue(s.currentOptions().isEmpty(), "WAIT 不把按钮算作取值选项")
        assertEquals(listOf<Any>(0L, 1L), s.displayOptions().map { it.value })
    }

    @Test
    fun `displayOptions 不复活旧世代按钮`() {
        val s = sink()
        s.printLine("[0] 旧页")
        note(s)
        assertEquals(listOf<Any>(0L), s.displayOptions().map { it.value })

        s.printLine("[1] 新页")
        note(s)
        assertEquals(listOf<Any>(1L), s.displayOptions().map { it.value }, "旧页按钮不得跟着新页复活")
    }

    /**
     * 排序按显示行优先：同一段 HTML 里的 `<br>` 必须断成不同的 lineSerial，
     * 不能让“第 2 行按钮”和“第 1 行按钮”挤在同一行号里。
     */
    @Test
    fun `HTML 多行按钮按显示行排序`() {
        val s = sink()
        s.printHtml(
            "<button value='1'>A</button> <button value='2'>B</button><br>" +
                "<button value='3'>C</button>",
            option = false,
        )
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(1L, 2L, 3L), opts.map { it.value })
        assertEquals(2, opts.map { it.lineSerial }.distinct().size, "两行 HTML 必须落两个 lineSerial")
        assertTrue(opts[0].startCol < opts[1].startCol, "同一行内按列从左到右")
    }

    /**
     * 同一行里自动按钮（PRINT 的 `[10]`）与缓冲 HTML 按钮（`option=true`）混排：
     * 顺序按行内列号，不分来源；旧实现把 HTML 按钮固定挂到 flush 首行。
     */
    @Test
    fun `同一行自动按钮与缓冲 HTML 按钮按列排序`() {
        val s = sink()
        s.print("[10] 自动")
        s.printHtml("<button value='227'>[27] HTML</button>", option = true)
        s.newLine()
        note(s, type = InputRequest.InputType.STR)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(10L, 227L), opts.map { it.value })
        assertEquals(1, opts.map { it.lineSerial }.distinct().size)
        assertTrue(opts[0].startCol < opts[1].startCol)
    }

    /**
     * 按钮正文里的实体引用**只还原一次**（原版 `Html2DisplayLine` 对每个文本片段调一次
     * `Unescape`，HtmlManager.cs:558、:744-834）。
     *
     * `&amp;lt;` 还原一次得到字面串 `&lt;`（屏上就显示 `&lt;`）；若读取期再还原一次会
     * 变成 `<`，屏上内容与右侧栏分叉。抽取阶段存**未还原**的原文、出快照时统一还原，
     * 就是为把这条锁死。
     */
    @Test
    fun `HTML 按钮正文的实体只还原一次`() {
        val s = sink()
        s.printHtml("<button value='1'>&amp;lt;tag&amp;gt;</button>", option = false)
        note(s)

        val opt = s.currentOptions().single()
        assertEquals("&lt;tag&gt;", opt.label, "只还原一层：&amp;lt; → &lt;，不得再变 <")
    }

    /**
     * 一颗按钮跨 `<br>` 时，[LineBufferConsoleSink.splitHtmlLines] 在行尾补闭合标签、
     * 下一行行首重放开标签（`button` 在 `openTags` 里，HtmlManager.cs:841-852 的串级状态）。
     * 因此**每一行都能抽到同一个值**，去重后留一条，且位置落在按钮起笔那一行。
     *
     * 这条也钉住按 chunk 抽取的必要性：整段抽一次会把跨行按钮全挂到首行，
     * 行序信息（[ConsoleOption.lineSerial]）就丢了。
     */
    @Test
    fun `按钮跨 br 时按行重放仍能抽到值`() {
        val s = sink()
        s.printHtml("<button value='7'>上半<br>下半</button>", option = false)
        note(s)

        val rows = s.snapshot().size
        val opts = s.currentOptions()
        assertEquals(listOf<Any>(7L), opts.map { it.value }, "同值按钮跨行只留一条")
        assertTrue(rows >= 2, "跨 br 应落成两条显示行；实际 $rows")
        assertTrue(opts.single().startCol >= 0)
    }

    /**
     * 同一行里「按钮 + 普通文本 + 按钮」混排：列号必须把中间的可见文本宽度算进去，
     * 排序才是屏上的从左到右。列号按**累加**推进（旧实现对每颗按钮从下标 0 重算整段前缀）。
     */
    @Test
    fun `同行按钮之间夹普通文本时列号累加`() {
        val s = sink()
        s.printHtml("<button value='1'>A</button>中间文字<button value='2'>B</button>", option = false)
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(1L, 2L), opts.map { it.value })
        assertEquals(0, opts[0].startCol)
        assertEquals(1, opts[0].endCol, "A 只占一格")
        assertTrue(
            opts[1].startCol > opts[0].endCol,
            "中间文本的宽度必须计入；实际 ${opts[0].endCol} → ${opts[1].startCol}",
        )
        // 「中间文字」四个全角 = 8 半角格 ⇒ B 落在 1 + 8 = 9 列
        assertEquals(9, opts[1].startCol)
    }

    /**
     * `<clearbutton>` 里的按钮**不算可点**：原版在这段里把 `<button>` 降级成非按钮
     * （`HtmlManager.cs:1549-1551` 的 `IsButton = false`），`ButtonStringCreator` 也就不给它
     * 生成可点对象。eraTW 的 CLEARLINE 重绘正是靠这一手把上一屏的地图格子作废。
     *
     * 真机 2026-10-27 现场：从三妖精之家地图页点格子进「大厅」后，右侧栏还挂着
     * `34/31/32/33/25` 五个上一屏的格子值 —— 屏上那几行已重印成 `<clearbutton>…`，
     * 抽取不认这个标签，就当成当前页的选项挂了出来。
     */
    @Test
    fun `clearbutton 里的按钮不算可点`() {
        val s = sink()
        s.printHtml("<clearbutton><button value='34'>34</button></clearbutton>", option = false)
        note(s)

        assertTrue(
            s.currentOptions().isEmpty(),
            "clearbutton 里的按钮应降级成非按钮；实际=${s.currentOptions()}",
        )
    }

    /**
     * 同一行里「作废按钮 + 正常按钮」混排：只收后者，且**列号仍把作废按钮的正文宽度算进去**
     * （那些字还在屏上占格子，后面的按钮不能提前）。
     */
    @Test
    fun `clearbutton 只作废区间内的按钮 列号照算`() {
        val s = sink()
        s.printHtml(
            "<clearbutton><button value='1'>AA</button></clearbutton>夹<button value='2'>B</button>",
            option = false,
        )
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(2L), opts.map { it.value }, "只留 clearbutton 之外那颗")
        // AA = 2 半角格、夹 = 2 格 ⇒ B 起于 4 列
        assertEquals(4, opts.single().startCol)
        assertEquals(5, opts.single().endCol, "B 只占一格")
    }

    /**
     * `<clearbutton>` 的开标签在前一行、闭标签在后一行（[splitHtmlLines] 按显示行切开时
     * 确实会这样）—— 区间要**跨行串级**，两行的按钮都不能登记。
     */
    @Test
    fun `clearbutton 跨 br 时两行按钮都不算可点`() {
        val s = sink()
        s.printHtml(
            "<clearbutton><button value='1'>A</button><br><button value='2'>B</button></clearbutton>",
            option = false,
        )
        note(s)

        assertTrue(
            s.currentOptions().isEmpty(),
            "开在前行、闭在后行的 clearbutton 区间内按钮都不可点；实际=${s.currentOptions()}",
        )
    }

    // ------------------------------------------------------------------
    // 同值不同源：屏上有几颗按钮，栏里就列几枚（2026-10-29 玩家反馈「同屏两枚『2』只认到上面那枚」）
    // ------------------------------------------------------------------

    /**
     * 地图格子的「2」与同屏菜单的「2」：两颗按钮、同一个值，右侧栏必须**两枚都列**，
     * 排在最底下的那枚不能被吞。
     *
     * 旧口径拿 `value` 当去重键（先到先得），第二条同值按钮在引擎快照这一层就没了 ——
     * 玩家屏上明明两处都能点，栏里只有一枚。原版这两条是两份 `ConsoleButtonString`
     * （`PrintStringBuffer.cs:103-123` 每次 `AppendButton` 都新建一条，`ConsoleDisplayLine.cs:18,28`
     * 的 `buttons` 数组各占一格），点哪处提交哪处的值；渲染层的命中表本来就只按**源身份**
     * 合并（`ButtonLayer.attach` 的 `sameSource`），右侧栏现在与它同口径。
     */
    @Test
    fun `同值的两枚显式按钮都进右侧栏`() {
        val s = sink()
        s.printButton("地图地点", 2L)
        s.newLine()
        s.printButton("其他选项", 2L)
        s.newLine()
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(2L, 2L), opts.map { it.value }, "两颗按钮 ⇒ 两枚候选")
        assertEquals(listOf("地图地点", "其他选项"), opts.map { it.label }, "下面的那枚按屏上顺序列在最后")
        assertTrue(
            opts[0].lineSerial < opts[1].lineSerial,
            "行序＝屏序；实际 ${opts[0].lineSerial} / ${opts[1].lineSerial}",
        )
        assertEquals(listOf(2, 2), opts.map { it.ordinal }, "两枚都显示角标 2（屏上就都写着 2）")
    }

    /** 同一行里并排的两颗同值自动按钮（`[2] …  [2] …`）同样各列一枚。 */
    @Test
    fun `同行并排的两枚同值按钮都进右侧栏`() {
        val s = sink()
        s.printLine("[2] 森林    [2] 水池")
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(2L, 2L), opts.map { it.value }, "一行两处 [2] ⇒ 两枚候选")
        assertEquals(listOf("森林", "水池"), opts.map { it.label })
        assertTrue(opts[0].startCol < opts[1].startCol, "列序＝屏上从左到右")
    }

    /** 同一串 HTML 里两颗同值按钮（QoL 地图行 + 菜单行常这么印）：开标签原文相同也要各拿一个源编号。 */
    @Test
    fun `同一串 html 里两颗同值按钮都进右侧栏`() {
        val s = sink()
        s.printHtml("<button value='2'>地图 2</button> <button value='2'>其他选项 2</button>", option = false)
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(2L, 2L), opts.map { it.value }, "原文相同的两颗按钮不能凭原文认成同一颗")
        assertEquals(listOf("地图 2", "其他选项 2"), opts.map { it.label })
    }

    /** 两次 `HTML_PRINT` 各印一颗同值按钮 ⇒ 两个源、两枚候选。 */
    @Test
    fun `分两次 html 打印的同值按钮都进右侧栏`() {
        val s = sink()
        s.printHtml("<button value='2'>格子</button>", option = false)
        s.printHtml("<button value='2'>菜单</button>", option = false)
        note(s)

        assertEquals(
            listOf("格子", "菜单"),
            s.currentOptions().map { it.label },
            "两次打印是两条 ConsoleButtonString；实际=${s.currentOptions()}",
        )
    }

    /**
     * 跨 `<br>` 重放与同串另一颗同值按钮**共存**：重放的那几行并成一枚（同一颗按钮），
     * 另一颗同值的照列（不同源）。这条钉住「合并按源编号、不按值」两半口径同时成立 ——
     * 行首重放的开标签原文与另一颗按钮的开标签原文逐字相同，只认原文会串号。
     */
    @Test
    fun `跨 br 重放并成一枚时同串另一颗同值按钮照列`() {
        val s = sink()
        s.printHtml("<button value='2'>上<br>下</button><button value='2'>另一颗</button>", option = false)
        note(s)

        val opts = s.currentOptions()
        assertEquals(2, opts.size, "跨 br 的那颗并成一枚，另一颗同值按钮不能跟着被吞；实际=$opts")
        assertEquals(listOf("上", "另一颗"), opts.map { it.label }, "重放的分片不留第二枚")
    }

    /** 一条按钮被折行排成好几排（同一条源按钮的分片）⇒ 栏里仍只一枚。 */
    @Test
    fun `同一条按钮折成多排时右侧栏只一枚`() {
        val s = sink()
        // 28 个全角 = 56 半角格，超过 clientWidth=40 ⇒ 必然折成两排
        s.printButton("很长的按钮正文".repeat(4), 2L)
        s.newLine()
        note(s)

        assertTrue(s.snapshot().size > 1, "正文超过 40 格应折成多排；实际 ${s.snapshot().size} 排")
        assertEquals(
            listOf<Any>(2L),
            s.currentOptions().map { it.value },
            "折行是同一条按钮的重放，不是两颗；实际=${s.currentOptions()}",
        )
    }

    /** 浮岛（`HTML_PRINT_ISLAND`）里两颗同值按钮各列一枚；浮岛按钮没有行序，靠源编号分开。 */
    @Test
    fun `浮岛里两颗同值按钮都进右侧栏`() {
        val s = sink()
        s.addHtmlIsland("<button value='2'>A</button><button value='2'>B</button>")
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf<Any>(2L, 2L), opts.map { it.value }, "浮岛两处按钮 ⇒ 两枚候选；实际=$opts")
        assertEquals(listOf("A", "B"), opts.map { it.label })
    }
}
