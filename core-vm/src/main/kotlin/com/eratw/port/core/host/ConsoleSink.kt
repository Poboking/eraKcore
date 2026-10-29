package com.eratw.port.core.host

/**
 * 行对齐方式（ALIGNMENT 语句）。spec: Process.cs:1535-1552（DisplayLineAlignment）。
 */
enum class Alignment { LEFT, CENTER, RIGHT }

/**
 * 控制台文字流宿主接口 —— 原版 EmueraConsole 中被 VM 执行路径实际调用的最小集
 * （spec: GameView/EmueraConsole.cs；调用点见 Process.cs 与 FunctionIdentifier.cs 各 DoInstruction）。
 *
 * 裁剪原则：只保留 ERB 语句可触达的输出动作；文字流缓冲/滚动/抗锯齿等 UI 细节归 M4。
 * 接口方法与语句一一对应处均已注明 spec；同一动作多个语句共用时合并为一个方法。
 *
 * 默认 [NoopConsoleSink]：吞掉全部输出，供无 UI 场景（erbcc 自检/纯求值）使用。
 */
interface ConsoleSink {
    /**
     * 逻辑行计数（LINECOUNT 伪变量）。spec: EmueraConsole.cs:509 LineCount → logicalLineCount；
     * 累加/删除规则见 EmueraConsole.cs:2971-2977（仅 IsLineEnd 的逻辑行 +1）与 :3019/3035（deleteLine 回退）。
     * 宿主未实现时恒 0（Noop 场景）。
     */
    val lineCount: Long get() = 0L

    /**
     * 打印缓冲空态：未 flush 的待决打印内容是否为空（原版 PrintBuffer.IsEmpty，
     * spec: PrintStringBuffer.cs:39-48；Flush 末尾 clearBuffer → EmueraConsole.cs:273-278）。
     * BINPUT 系语句以此决定是否先补换行：`if (!PrintBuffer.IsEmpty) NewLine()`
     * （spec: FunctionIdentifier.cs:2123-2126）。宿主不追踪缓冲时缺省视为空
     * （true → BINPUT 系不补换行）。
     */
    val printingBufferEmpty: Boolean get() = true

    /**
     * 显示行缓冲的只读快照（HTML_GETPRINTEDSTR / GETDISPLAYLINE 的数据源）。
     *
     * 对应原版 `EmueraConsole.DisplayLineList`（spec: EmueraConsole.cs:238）：
     * `GetDisplayLines`（:3546-3576）从最新一行往回数**逻辑行**，
     * `GETDISPLAYLINE`（FunctionMethodCreator.cs:8415-8440）按**显示行**下标取，
     * 两条口径都直接读这份列表。顺序 = 打印顺序（最旧在前），含折行续行与
     * REUSELASTLINE 的临时行（原版临时行同样进 displayLineList，等下一次
     * addDisplayLine 才被删，EmueraConsole.cs:2891-2893、:3053-3056）。
     *
     * null = 本宿主不追踪显示行（erbcc 自检 / 纯求值 / 既有假 sink）→ 函数按
     * 「宿主未接线」抛 CodeEE，不静默返回空表。实现方每次读都必须从自己的活状态
     * 现取快照，不得另存第二份行列表。
     */
    val displayedLines: List<LineBufferConsoleSink.DisplayLine>? get() = null

    /**
     * 打印中缓冲的**弹出侧**（HTML_POPPRINTINGSTR 的数据源，读取即消费）。
     *
     * 对应原版 `EmueraConsole.PopDisplayingLines`（spec: EmueraConsole.cs:3578-3588）：
     * 缓冲空 → 原版返回 null、函数侧转成 ""（FunctionMethodCreator.cs:6123-6127）；
     * 非空 → `printBuffer.Flush(...)` 排出的全部显示行（PrintStringBuffer.cs:179-186），
     * `Flush` 末尾 `clearBuffer()`（:273-278）⇒ 本访问器返回后缓冲必为空，第二次读到空表。
     * 这些行**不进** displayLineList（PopDisplayingLines 方法体没有 addDisplayLine），
     * 故不影响 [displayedLines]、LINECOUNT 与帧版本号（LineBufferConsoleSink.revision）。
     *
     * null = 本宿主不追踪打印缓冲 → 函数抛 CodeEE；空表 = 缓冲为空 → 函数返回 ""。
     */
    val popPrintingBuffer: List<LineBufferConsoleSink.DisplayLine>? get() = null

    /** 追加文本。spec: EmueraConsole.Print(str)（lineEnd 缺省 true） */
    fun print(text: String)

    /**
     * 追加文本并携带本段的行结束标记。spec: EmueraConsole.Print(str, lineEnd) →
     * PrintStringBuffer.Append(..., lineEnd)；标记只在 flush（[newLine]）时生效：
     * false（PRINTN）时 flush 出的显示行 IsLineEnd=false，日志文本不断行、LINECOUNT 不 +1
     * （EmueraConsole.cs:2948-2977 会把该行并入上一显示行）。
     * 默认实现丢弃标记，兼容只关心文本的宿主。spec: FunctionIdentifier.cs:146-151,203-229
     */
    fun print(text: String, lineEnd: Boolean) { print(text) }

    /** 追加文本并换行。spec: EmueraConsole.Print(str, true) → NewLine */
    fun printLine(text: String)

    /** 定宽输出（PRINTC 右对齐 / PRINTLC 左对齐，宽度由宿主按 PrintCWidth 决定）。 */
    fun printC(text: String, alignRight: Boolean)

    /** 单行独立显示（PRINTSINGLE 家族）。spec: EmueraConsole.PrintSingleLine */
    fun printSingleLine(text: String)

    /** 可点击按钮。spec: EmueraConsole.PrintButton(text, value) */
    fun printButton(text: String, value: Long)

    /** 字符串值按钮。spec: EmueraConsole.PrintButton(text, str) */
    fun printButton(text: String, value: String)

    /** 定宽按钮（PRINTBUTTONC 右对齐 / PRINTBUTTONLC 左对齐）。spec: EmueraConsole.PrintButtonC */
    fun printButtonC(text: String, value: Long, alignRight: Boolean)

    fun printButtonC(text: String, value: String, alignRight: Boolean)

    /** 无样式纯文本（PRINTPLAIN 家族，不套用用户配色）。spec: EmueraConsole.PrintPlain */
    fun printPlain(text: String)

    /** 临时行（REUSELASTLINE：替换最后一行）。spec: EmueraConsole.PrintTemporaryLine */
    fun printTemporaryLine(text: String)

    /** 分隔线（DRAWLINE：PrintBar + NewLine 的 bar 部分）。spec: EmueraConsole.PrintBar */
    fun printBar()

    /** 用参数字符串填充的自定义分隔线（CUSTOMDRAWLINE/DRAWLINEFORM）。spec: EmueraConsole.printCustomBar */
    fun printCustomBar(text: String)


    /** 换行。spec: EmueraConsole.NewLine */
    fun newLine()

    /** 从当前行向上删除 [count] 行（CLEARLINE）。spec: EmueraConsole.deleteLine */
    fun deleteLine(count: Int)

    /** 清空输入框（CLEARTEXTBOX）。spec: EmueraConsole.ClearText */
    fun clearText()

    /**
     * 进入一次输入等待前的一次通知：宿主据此冻结“当前页选项”。
     *
     * 这是右侧选项栏的唯一数据入口（见 [ConsoleOption]）。默认无操作，既有宿主
     * （Noop/诊断假 sink）零改动；[LineBufferConsoleSink] 在这里按**等待请求**过滤：
     * 非取值等待清空、数值等待只留 Long、字符串等待两种值都收，并且只取**上一次等待
     * 之后新打印的按钮**——旧页按钮不再被翻出来。
     *
     * 调用点只有语句层的 [waitInput] 与 [readAnyKey] 两个漏斗；`InputSink` 自身不查
     * 选项，避免把控制台状态耦合进通用输入队列。
     *
     * 委托型宿主（[ConsoleBridge]）**必须覆盖并转发**：接口缺省是 no-op，桥接器漏转发时
     * 真机会出现「等待 INT 但 `currentOptions()` 恒空」的整页漏识别。
     */
    fun noteInputWait(request: InputRequest) {}

    /** 前景色（SETCOLOR）。分量已由语句层校验 0-255。spec: EmueraConsole.SetStringStyle(Color) */
    fun setForeColor(r: Int, g: Int, b: Int)

    /** 背景色（SETBGCOLOR）。spec: EmueraConsole.SetBgColor */
    fun setBackColor(r: Int, g: Int, b: Int)

    fun resetForeColor()  // spec: EmueraConsole.ResetColor（RESETCOLOR）
    fun resetBackColor()  // spec: EmueraConsole.ResetBgColor（RESETBGCOLOR）

    /**
     * D 系打印的取色开关（原版 `EmueraConsole.UseSetColorStyle` 字段，spec: EmueraConsole.cs:469）。
     *
     * 原版语义：PRINT 家族每次执行都会先开打印窗口——`UseUserStyle = true`、
     * `UseSetColorStyle = !IsPrintDFunction()`（PRINT 系 FunctionIdentifier.cs:179-180、
     * PRINTDATA 系 :276-277），打印完复位 `true`（:227 / :317；PRINTSINGLE 经
     * OutputToConsole 的复位 :68 也在窗口内）。窗口内取色走 `Style` getter
     * （EmueraConsole.cs:471-489）：
     *  - `UseSetColorStyle == true`（非 D 系）：用 `userStyle`（SETCOLOR 的值生效）；
     *  - `false`（D 系，即 PRINTD/PRINTDL/PRINTVD/... 与 PRINTDATAD/... 全部 D 后缀）：
     *    若 `userStyle.Color == defaultStyle.Color` 用 `userStyle`，否则
     *    `new StringStyle(defaultStyle.Color, userStyle.FontStyle, userStyle.Fontname)`
     *    ——即**前景回落 Config.ForeColor（0xC0C0C0），字体样式/字体名保留**。
     *    两条分支的实际效果都是「前景 = 默认色」（色相同值时无差异），字体属性不回落。
     *
     * 因此宿主实现只需在 `false` 期间把打印段的**前景色**解析为默认色；`fontStyle`、
     * `fontName` 与背景色都不受影响。背景色在原版本就是独立字段 `bgColor`
     * （EmueraConsole.cs:200，不在 StringStyle 里），SETBGCOLOR 的效果在 D 系照旧。
     *
     * 还原时机按原版逐点复刻：语句层负责「打印前置 false / 打印完置 true」，接口缺省为
     * **无操作**（不追踪样式的宿主无从谈起取色；缺省体让这类实现零改动即正确）。
     * 委托型宿主（ProcessRuntime 的 ConsoleBridge）必须转发给背后的真 sink。
     * 原版窗口中途抛异常会把 false 泄漏到下一条打印语句开头（DoInstruction 无 try/finally，
     * FunctionIdentifier.cs:180→227），本开关的置位/复位时机与其逐点对应，不额外收拢。
     *
     * **全 reference 穷举**：写假的只有同一机制两处（PRINT 族 FunctionIdentifier.cs:180 与
     * PRINT_DATA 族 :277；`IsPrintDFunction()` = flag 0x800 位，:4240-4243，位由 :141-145/:244-248
     * 按名字后缀 D 置上），其余五处无条件写真（:227 / :317 / ExpressionMediator.cs:68 /
     * Process.cs:1111 / :1130 / :1150）⇒ 抑制不外溢到后续按钮行/明文行。
     *
     * **原版一处残留**（ADR-005 复刻，不是 bug）：PRINT_DATA 族 :277 写假后，`dataList.Count == 0`
     * 的空数据节分支（:278-282）`return` 走不到 :317 的写回 ⇒ `PRINTDATAD` 配空 `ENDDATA` 时开关停在
     * false，由下一条 PRINT/PRINTBUTTON/PRINTPLAIN 自己复位。
     *
     * **接线状态（M4-N n-hostconsole 记录）**：main 上语句层写点已接（`StatementExecutor` 的
     * `execPrint`/`execPrintData` 按 :180/:227 与 :277/:317 置位/复位）。
     *
     * 另一条轴 `UseUserStyle` 见 [setUseUserStyle]（它比本开关**优先**：写假时整条回
     * `defaultStyle`，本开关怎么取值都不看）。
     */
    fun setUseSetColorStyle(value: Boolean) {}

    /**
     * 系统行开关（原版 `EmueraConsole.UseUserStyle` 字段，spec: `EmueraConsole.cs:467`）。
     *
     * 它与 [setUseSetColorStyle] 是 `Style` 取值器（`EmueraConsole.cs:471-489`）的**两个轴**，
     * 合起来四路：
     *
     * ```
     * if (!UseUserStyle)              return defaultStyle;                       // (A) 整条回默认
     * if (UseSetColorStyle)           return userStyle;                          // (B) 非 D 系
     * if (userStyle.Color == defaultStyle.Color) return userStyle;               // (C) 同色无差
     * return new StringStyle(defaultStyle.Color, userStyle.FontStyle, userStyle.Fontname); // (D) D 系
     * ```
     *
     * 移植侧此前只有 (B)/(D) 两路（[setUseSetColorStyle] 一轴），`UseUserStyle` 那条轴
     * 被折叠成恒真 ⇒ **(A) 支路缺失**：系统行与错误行会带着用户的 SETCOLOR / FONTSTYLE /
     * SETFONT 出场，而原版这些行是「整条回 `defaultStyle`」。
     *
     * ## 谁写真、谁写假（全 reference 穷举）
     *
     * 写**真**五处，全在语句层（本接口的三个调用点之外还有两处按钮/明文）：
     * `FunctionIdentifier.cs:179`（PRINT 家族，含 PRINTSINGLE）、`:276`（PRINTDATA 家族）、
     * `Process.cs:1110`（PRINTBUTTON）、`:1129`（PRINTBUTTONC/LC）、`:1149`（PRINTPLAIN/FORM）。
     * 五处都在 `if (!SkipPrint)` 之内、且在求值之前。
     *
     * 写**假**六处，全在控制台内部流程，语句侧够不到：
     * `:764`（`ThrowError`，错误态）、`:2575`（`GotoTitle`）、`:2607`（Ctrl+Z 的
     * `GotoTitleAndLoadAndRepeatInput`）、`:3094`（`PrintSystemLine`）、`:3108`（`PrintError`）、
     * `:3127`（`PrintErrorButton`）。
     *
     * ## 这是一个**粘滞**开关，不是「一次打印的窗口」
     *
     * 写假之后没人会替它写回真 —— 唯一写回真的是那五处 PRINT 系语句。所以
     * `PrintError` 之后紧跟的 `PRINTSINGLE`（走 `ExpressionMediator.OutputToConsole`，
     * 它只复位 `UseSetColorStyle`，`:68`）在**原版也是默认样式**。本接口按同一粘滞口径实现，
     * 不擅自「打完就还原」。
     *
     * ## 出厂值
     *
     * C# `bool` 字段默认 **false**，原版进程启动即 false；`userStyle` 出厂也等于
     * `defaultStyle`（`:206-208`），所以第一条 PRINT 之前的打印在两支下同观感。移植侧照抄 false。
     *
     * 接口缺省为**无操作**（不追踪样式的宿主无从谈起回落；缺省体让既有实现零改动即正确）。
     * 委托型宿主（`ProcessRuntime` 的 ConsoleBridge）必须转发给背后的真 sink。
     */
    fun setUseUserStyle(value: Boolean) {}

    /** 字体样式位掩码：1=粗体 2=斜体 4=删除线 8=下划线（FONTSTYLE/FONTBOLD/...）。spec: Process.cs:1508-1530 */
    fun setFontStyle(mask: Int)

    /**
     * 当前字体样式位掩码（[setFontStyle] 的读出侧）。FONTBOLD/FONTITALIC 是 **OR 语义**
     * （spec: FunctionIdentifier.cs:1574-1606 `StringStyle.FontStyle | Bold/Italic`），
     * 语句层需读当前值再置位；宿主不跟踪样式时恒 0（原版非 Windows 平台同样无效果）。
     */
    val fontStyle: Int get() = 0

    /** 字体名（SETFONT；空串恢复默认）。spec: EmueraConsole.SetFont */
    fun setFont(name: String)

    /** 行对齐（ALIGNMENT）。spec: Process.cs:1535-1552 */
    fun setAlignment(kind: Alignment)

    /**
     * 重绘模式（REDRAW 的原始整数参数）。spec: EmueraConsole.cs:1693-1707 SetRedraw
     *
     * 实现方必须按原版折减到两态再对外暴露：bit0 = 0 → None、1 → Normal（bit1 只是
     * "立即重绘"的副作用，不改变状态）。读出侧 [redraw] 给的就是折减后的状态。
     */
    fun setRedraw(mode: Int)

    /**
     * 帧发布（让出边界的批量上屏）：把「冻结期间积攒的可见变更」一次性发布，并恢复
     * 「变更立即发布」的直通状态。spec 依据（2026-10-22 真机抖动收口）：
     *
     * - 原版是 **WinForms 单线程 + `Application.DoEvents()` 协作调度**：paint 事件只在消息泵
     *   运行点有机会执行 —— `EmueraConsole.Await`（:840-856，第一步就是
     *   `RefreshStrings(force_Paint: true)` 强制重绘，:847）与输入等待（`state = WaitInput`
     *   后主循环退出到消息循环）。脚本段内 `CLEARLINE`→重新打印的**中间态从不被画**。
     * - 本移植是 VM 线程 + UI 线程双线程，UI 随时可采样：没有帧边界约定时，清屏/重打的
     *   每个中间态都会被发布成完整帧（真机 BADAPPLE 60fps 空转时表现为高频抖动）。
     * - 语句层（[com.eratw.port.core.sem.StatementExecutor]）在两个让出边界调
     *   [publishFrame]：`AWAIT`（对应原版 Await 的强制重绘）与进入输入等待前；让出返回后调
     *   [holdFrame] 重新冻结，使下一个脚本段的变化积累成**一帧**。
     *
     * 缺省 no-op：headless 装置 / golden 管线不经让出批量语义，行为不变。
     */
    fun publishFrame() {}

    /**
     * 帧冻结（让出边界返回之后）：后续可见变更不再立即发布，只记脏，等下一次
     * [publishFrame] 一次性上屏（或 [setRedraw] 回 Normal 时解冻）。缺省 no-op，见 [publishFrame]。
     */
    fun holdFrame() {}

    /**
     * 整组样式复位 —— 原版 `EmueraConsole.ResetStyle()`（spec: EmueraConsole.cs:2838-2842）：
     * `userStyle = defaultStyle` + `alignment = DisplayLineAlignment.LEFT`，两条语句级动作
     * 一次性完成。调用点共四处：BEGIN（FunctionIdentifier.cs:2930-2936，方法体第 3 句 :2935）、
     * FORCE_BEGIN（:2947-2953，方法体逐句相同 :2952）、RESETDATA（:1836-1848，`VEvaluator.ResetData()`
     * 之后 :1847）、开局标题 `Process.beginTitle()`（Process.cs:2106-2112，`skipPrint = false`
     * 之后 :2112）。
     *
     * 复位集由 `defaultStyle` 的定义唯一决定（EmueraConsole.cs:206
     * `new StringStyle(Config.ForeColor, FontStyle.Regular, null)`；`StringStyle` 构造把空
     * 字体名归一成 `Config.FontName`，StringStyle.cs:20-27）⇒ 恰好四项：
     * 前景回 `Config.ForeColor`（与 RESETCOLOR 同一落点，FunctionIdentifier.cs:1501）、
     * 字体样式位清零（Regular）、字体名回 `Config.FontName`（与 SETFONT 空串同一落点，
     * EmueraConsole.cs:2826-2835）、对齐回 LEFT（Process.cs:1535-1552）。
     *
     * **不复位**的东西同样有出处，禁止「顺手」扩大复位面：背景色是独立字段 `bgColor`
     * （EmueraConsole.cs:200，`SetBgColor` :2844-2858 写，ResetStyle 方法体没碰它）、
     * 重绘状态（:455/:1693-1707）、打印缓冲与显示行列表（:238 等）都不在 ResetStyle 里。
     *
     * 缺省实现用逐项 setter 组合出同一效果（resetForeColor + setFontStyle(0) +
     * setFont(空串恢复默认) + setAlignment(LEFT)），因此**委托型宿主**（如
     * `ProcessRuntime.ConsoleBridge` 这类把每个 setter 转发给真 sink 的桥）不改一行
     * 就能把复位动作正确落到背后的状态上；直接持有样式字段的宿主可覆写为一次性字段
     * 赋值（[LineBufferConsoleSink] 即如此），两条路径的终态必须一致（见
     * ConsoleResetStyleTest 的对照用例）。
     */
    fun resetStyle() {
        resetForeColor()
        setFontStyle(0)
        setFont("")
        setAlignment(Alignment.LEFT)
    }

    // ------------------------------------------------------------------
    // 样式读出侧（GETCOLOR 家族 / CURRENTALIGN / CURRENTREDRAW 的数据源）
    //
    // 原版这五项都是**控制台自己持有的活状态**，不是第二份快照：
    //  - `userStyle.Color`（EmueraConsole.cs:208 初值 = `Config.ForeColor`，
    //    :2820-2824 `SetStringStyle(Color)` 改写；GETCOLOR 读的就是它，
    //    FunctionMethodCreator.cs:3662 `defaultColor ? Config.ForeColor : Console.StringStyle.Color`，
    //    而 `StringStyle` 本体就是 `userStyle`，EmueraConsole.cs:491）；
    //  - `bgColor`（:200 声明即 `= Config.BackColor`，:2844-2858 `SetBgColor`；
    //    GETBGCOLOR 读它，FunctionMethodCreator.cs:3695）；
    //  - `userStyle.FontStyle` / `userStyle.Fontname`（:2815-2818、:2826-2835；
    //    GETSTYLE / GETFONT 读它，FunctionMethodCreator.cs:3708-3729、:3743；
    //    `Fontname` 的初值在 `StringStyle` 构造里就被空值归一成 `Config.FontName`，
    //    StringStyle.cs:26-31，所以 GETFONT 永远读到一个真名字）；
    //  - `alignment`（:493-503；CURRENTALIGN 读它，FunctionMethodCreator.cs:3770-3791，
    //    枚举序 LEFT/CENTER/RIGHT = 0/1/2，DisplayLineAlignment.cs:3-8）；
    //  - `redraw`（:455 `public ConsoleRedraw Redraw => redraw`；CURRENTREDRAW 读它，
    //    FunctionMethodCreator.cs:3793-3806，取值在 :3804）。
    //
    // 因此**样式状态只允许存在于实现方一份**：宿主侧的样式快照必须由这些读数派生
    // （见 `core/fn/Builtins.kt` 的 `consoleStyleOf`），另立一份字段表必然与打印路径分叉
    // ——同一个 sink 写入、另一个对象回答读取，就是 M4-D 收过的那笔债。
    // 缺省值取原版的**出厂态**（未 SETCOLOR / 未 SETFONT / LEFT / None），不追踪样式的
    // 宿主照缺省回答即可，不伪造。
    // ------------------------------------------------------------------

    /**
     * 当前前景色 `0xRRGGBB`；**null = 未显式设置**，调用方按 `Config.ForeColor` 回落
     * （原版初值即 Config.ForeColor，EmueraConsole.cs:208 / ConfigData.cs:68，
     * RESETCOLOR 走 `SetStringStyle(Config.ForeColor)`，FunctionIdentifier.cs:1501）。
     */
    val foregroundColor: Int? get() = null

    /** 当前背景色 `0xRRGGBB`；null = 未显式设置，按 `Config.BackColor` 回落（:200 / ConfigData.cs:69）。 */
    val backgroundColor: Int? get() = null

    /** 当前字体名；null = 本宿主不追踪字体名（原版恒有值 = `Config.FontName`，StringStyle.cs:26-31）。 */
    val fontName: String? get() = null

    /**
     * 宿主环境的默认字体名（`Config.FontName`，ConfigData.cs:65；SETFONT 传空串时恢复的
     * 就是它，EmueraConsole.cs:2826-2835）。行体重建 `<font face=...>` 标签时用它判断
     * 「字体名是否偏离默认」（HtmlManager.cs:875）。null = 本宿主不追踪默认字体。
     */
    val defaultFontName: String? get() = null

    /** 当前对齐方式（ALIGNMENT 的活状态；出厂 LEFT，Process.cs:1535-1552）。 */
    val alignment: Alignment get() = Alignment.LEFT

    /**
     * 当前重绘状态，**已折减成两态**：0 = None、1 = Normal
     * （spec: ConsoleRedraw.cs:6-10 的枚举序；折减规则 EmueraConsole.cs:1693-1707）。
     * `CURRENTREDRAW` = `redraw != 0 ? 1 : 0`（FunctionMethodCreator.cs:3804）。
     */
    val redraw: Int get() = 0

    /** HTML 片段渲染（HTML_PRINT；option=附加渲染选项）。spec: EmueraConsole.PrintHtml */
    fun printHtml(text: String, option: Boolean)

    /** HTML 浮岛（HTML_PRINT_ISLAND / HTML_PRINT_ISLAND_CLEAR）。spec: EmueraConsole.PrintHTMLIsland */
    fun addHtmlIsland(text: String)
    fun clearHtmlIslands()

    /** 错误行（SAVEDATA 失败等）。spec: EmueraConsole.PrintError */
    fun printError(text: String)
}

/** 无操作控制台：erbcc 自检与纯求值路径用。 */
object NoopConsoleSink : ConsoleSink {
    /** 无缓冲可追踪，恒空（BINPUT 系不补换行）。 */
    override val printingBufferEmpty: Boolean get() = true

    override fun print(text: String) {}
    override fun printLine(text: String) {}
    override fun printC(text: String, alignRight: Boolean) {}
    override fun printSingleLine(text: String) {}
    override fun printButton(text: String, value: Long) {}
    override fun printButton(text: String, value: String) {}
    override fun printButtonC(text: String, value: Long, alignRight: Boolean) {}
    override fun printButtonC(text: String, value: String, alignRight: Boolean) {}
    override fun printPlain(text: String) {}
    override fun printTemporaryLine(text: String) {}
    override fun printBar() {}
    override fun printCustomBar(text: String) {}
    override fun newLine() {}
    override fun deleteLine(count: Int) {}
    override fun clearText() {}
    override fun setForeColor(r: Int, g: Int, b: Int) {}
    override fun setBackColor(r: Int, g: Int, b: Int) {}
    override fun resetForeColor() {}
    override fun resetBackColor() {}
    override fun setFontStyle(mask: Int) {}
    override fun setFont(name: String) {}
    override fun setAlignment(kind: Alignment) {}
    override fun setRedraw(mode: Int) {}
    override fun printHtml(text: String, option: Boolean) {}
    override fun addHtmlIsland(text: String) {}
    override fun clearHtmlIslands() {}
    override fun printError(text: String) {}
}
