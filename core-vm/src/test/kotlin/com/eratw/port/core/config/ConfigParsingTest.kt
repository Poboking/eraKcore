package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解析器语义测试 —— 每条断言的期望值都指回 C# 的具体行，语料缺失时也能跑（本路第二道闸）。
 *
 * 覆盖的是 `emuera.config` 读取链上「只有原版这么干」的形状：键匹配范围、冒号切分与特例、
 * 类型分派的四种失败姿势（抛错 / 静默保留 / 空值早退 / 钉死早退）、抛错后整个文件剩余行作废，
 * 以及 `_Replace.csv` 与 `debug.config` 两条同族但不同的读法。
 */
class ConfigParsingTest {

    private fun newStore(): ConfigStore = ConfigStore()

    private fun loadGame(
        store: ConfigStore,
        vararg lines: String,
        fix: Boolean = false,
        analysisMode: Boolean = false,
    ): ConfigFileLoadResult =
        EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", lines.toList()), store, fix, analysisMode)

    // -----------------------------------------------------------------
    // 键匹配范围（ConfigData.cs:262-284 / :298-316 / :330-348）
    // -----------------------------------------------------------------

    @Test
    fun `键大小写无关且日英标签都认（仅 configArray 认英文）`() {
        val store = newStore()
        assertEquals(ConfigKey.IgnoreCase, loadGame(store, "ignore case:NO").records.single().matchedKey)
        assertFalse(store.itemOf(ConfigKey.IgnoreCase).bool())
        loadGame(store, "大文字小文字の違いを無視する:YES")
        assertTrue(store.itemOf(ConfigKey.IgnoreCase).bool())
        // Name 分支：C# 把键 ToUpper 后与未大写的 Name 比（ConfigData.cs:264 + AConfigItem.cs:21），
        // 于是全小写混合的枚举名永远等不上；"IGNORECASE" 只能靠 Text/EngText 命中。
        assertNull(store.resolveGame("IGNORECASE"))
        assertNull(store.resolveGame("IGNORE_CASE"))
    }

    @Test
    fun `尾随空格标签让那一行永远匹配不上（官方 bug 原样复刻）`() {
        val store = newStore()
        val r = loadGame(store, "CLIPBOARD- ANYKEY WAIT TRIGGER:NO")
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, r.records.single().status)
        assertFalse(store.itemOf(ConfigKey.CBTriggerAnyKeyWait).bool())
        // 连原版自己写出的那一行（冒号前带空格）也读不回来：读侧把键 `Trim()` 掉了
        // （ConfigData.cs:539），而标签侧的空格还在（AConfigItem.cs:23 只大写不裁剪）。
        // 也就是说这个条目**只能靠 ConfigCode 寻址**（设置界面正是这么做的：
        // `setCheckBox(..., ConfigCode.CBTriggerAnyKeyWait)` spec: ConfigDialog.cs:914、
        // 回写走 `GetConfigItem(ConfigCode...)` spec: ConfigDialog.cs:1101，
        // 两者都是 spec: ConfigData.cs:250-260 的代码寻址），文本路径彻底堵死。
        val r2 = loadGame(store, "CLIPBOARD- ANYKEY WAIT TRIGGER :YES")
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, r2.records.single().status)
        assertFalse(store.itemOf(ConfigKey.CBTriggerAnyKeyWait).bool())
    }

    @Test
    fun `主文件只查 configArray，replace 与 debug 条目在主文件里认不到`() {
        val store = newStore()
        val r = loadGame(store, "Currency symbol:¥", "Show debug window on startup:NO")
        assertTrue(r.records.all { it.status == ConfigLineStatus.UNMATCHED_KEY })
        assertEquals("${'$'}", store.itemOf(ConfigKey.MoneyLabel).string())
        assertTrue(store.itemOf(ConfigKey.DebugShowWindow).bool())
    }

    // -----------------------------------------------------------------
    // 行级形状（ConfigData.cs:529-543）
    // -----------------------------------------------------------------

    @Test
    fun `空行 注释 无冒号 空值四类都不改值`() {
        val store = newStore()
        val r = loadGame(store, "", ";注释", " ;行首空格加假键:YES", "NO COLON HERE", "IGNORE CASE:")
        assertEquals(
            listOf(
                ConfigLineStatus.BLANK,
                ConfigLineStatus.COMMENT,
                ConfigLineStatus.UNMATCHED_KEY, // 没有整行 Trim，`;` 不在第 0 位 ⇒ 去查键，查不到就静默跳过
                ConfigLineStatus.NO_SEPARATOR,
                ConfigLineStatus.EMPTY_VALUE,
            ),
            r.records.map { it.status },
        )
        assertTrue(store.itemOf(ConfigKey.IgnoreCase).bool(), "空值时 TryParse 在 ConfigItem.cs:92-95 就返回了")
        assertEquals(0, r.warnings.size)
    }

    // -----------------------------------------------------------------
    // 各类型分派（ConfigItem.cs:101-211）
    // -----------------------------------------------------------------

    @Test
    fun `bool 认数字与大写无关的 yes-no-true-false，以及区分大小写的前后`() {
        val store = newStore()
        loadGame(
            store,
            "USE MOUSE:1",
            "SHOW MENU:0",
            "USE _RENAME.CSV FILE:True",
            "USE _REPLACE.CSV FILE:false",
            "WHITESPACE INCLUDES FULL-WIDTH SPACE:前",
            "IMITATE BEHAVIOR FOR RAND:後",
            "ERAMAKER COMPATIBILITY WARNING:nO",
        )
        assertEquals(
            listOf(true, false, true, false, true, false, false),
            listOf(
                store.itemOf(ConfigKey.UseMouse).bool(),
                store.itemOf(ConfigKey.UseMenu).bool(),
                store.itemOf(ConfigKey.UseRenameFile).bool(),
                store.itemOf(ConfigKey.UseReplaceFile).bool(),
                store.itemOf(ConfigKey.SystemAllowFullSpace).bool(),
                store.itemOf(ConfigKey.CompatiRAND).bool(),
                store.itemOf(ConfigKey.WarnBackCompatibility).bool(),
            ),
        )
    }

    @Test
    fun `抛错行会作废本文件剩余全部行（try 包在 while 外面）`() {
        val store = newStore()
        val r = loadGame(store, "USE MOUSE:NO", "SHOW MENU:はい", "USE _RENAME.CSV FILE:YES")
        assertEquals(ConfigKey.UseMouse, r.records[0].matchedKey)
        assertEquals(ConfigLineStatus.PARSE_ERROR, r.records[1].status)
        assertEquals(2, r.records.size, "第 3 行根本没被读到")
        assertEquals(1, r.droppedLines, "总 3 行、停在第 2 行 ⇒ 丢弃 1 行")
        assertNull(r.records.getOrNull(2))
        assertEquals(ConfigParseException.LangKey.INVALID_SPECIFICATION, r.records[1].error!!.langKey)
        assertEquals(2, r.records[1].error!!.lineNo, "1 基行号：SHOW MENU 在文件第 2 行")
        assertEquals(1, r.warnings.single().level, "ConfigData.cs:581 的 level 恒为 1")
        assertFalse(store.itemOf(ConfigKey.UseMouse).bool(), "抛错前的行已经生效")
        assertFalse(store.itemOf(ConfigKey.UseRenameFile).bool(), "抛错后的行不再生效")
    }

    @Test
    fun `int 与 long 的非数字即抛，溢出同抛`() {
        val store = newStore()
        loadGame(store, "WINDOW WIDTH: 760 ")
        assertEquals(760, store.itemOf(ConfigKey.WindowX).int())
        val bad = loadGame(store, "WINDOW WIDTH:abc")
        assertEquals(ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS, bad.records.single().error!!.langKey)
        assertEquals(760, store.itemOf(ConfigKey.WindowX).int(), "抛错发生在赋值之前，旧值保留")
        loadGame(store, "LATEST IDENTIFY CODE:1234567890123")
        assertEquals(1234567890123L, store.itemOf(ConfigKey.LastKey).long())
        val overflow = loadGame(store, "WINDOW WIDTH:3000000000")
        assertEquals(ConfigLineStatus.PARSE_ERROR, overflow.records.single().status, "int.TryParse 对溢出返回 false（ConfigItem.cs:129-132）")
    }

    @Test
    fun `color 取前三段且逐段限 0-255，段数不足即抛`() {
        val store = newStore()
        loadGame(store, "TEXT COLOR: 10 , 20 , 30")
        assertEquals(ConfigValue.ColorValue(10, 20, 30), store.itemOf(ConfigKey.ForeColor).color())
        loadGame(store, "BACKGROUND COLOR:1,2,3,4")
        assertEquals(ConfigValue.ColorValue(1, 2, 3), store.itemOf(ConfigKey.BackColor).color(), "第四段被忽略（ConfigItem.cs:260-271 只取 0..2）")
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "HIGHLIGHT COLOR:1,2").records.single().status)
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "HIGHLIGHT COLOR:1,2,256").records.single().status)
    }

    @Test
    fun `string 列表按逗号切并逐项 trim`() {
        val store = newStore()
        loadGame(store, "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT: xml , json ")
        assertEquals(listOf("xml", "json"), store.itemOf(ConfigKey.ValidExtension).stringList())
    }

    @Test
    fun `枚举认常量名也认序号，未定义的序号照样解析成功（Enum 的隐式行为）`() {
        val store = newStore()
        loadGame(store, "DRAWING INTERFACE:2")
        assertEquals("WINAPI", store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "TextDrawingMode.cs:3-8 声明序 GRAPHICS=0 TEXTRENDERER=1 WINAPI=2")
        loadGame(store, "drawing interface:textrenderer")
        assertEquals("TEXTRENDERER", store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "名字分支忽略大小写（ConfigItem.cs:174 的 ignoreCase: true）")
        // 数字串走的是「底层类型装得下就算成功」这条路，5 处调用都没有 Enum.IsDefined 守卫
        // （spec: ConfigItem.cs:174、:182、:190、:198、:206）；未定义值的 ToString 回吐数字串
        // 本身（spec: ConfigItem.cs:81）。本机 dotnet 9.0.2 实测，证据见 ConfigEnumKind 类 KDoc。
        val nine = loadGame(store, "DEFAULT ANSI ENCODING:9")
        assertEquals(ConfigLineStatus.APPLIED, nine.records.single().status, "UseLanguage 只有 0..3，但 9 不是错误（原版不判 IsDefined）")
        assertEquals("9", store.itemOf(ConfigKey.useLanguage).enumConstant())
        loadGame(store, "DRAWING INTERFACE:+1")
        assertEquals("TEXTRENDERER", store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "带正号的十进制实测解析为 1 ⇒ 命中已定义序号 ⇒ 存常量名")
        loadGame(store, "REDUCE ARGUMENT ON LOAD:0001")
        assertEquals("ONCE", store.itemOf(ConfigKey.ReduceArgumentOnLoad).enumConstant(), "前导零被规范化：ReduceArgumentOnLoadFlag.cs:3-8 ⇒ YES=0 ONCE=1 NO=2")
        // 仍然抛错的三种：既不是名字也不是十进制 / 超 Int32 / 逗号组合（非 flags 枚举）
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "DRAWING INTERFACE:MAYBE").records.single().status)
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "DRAWING INTERFACE:2147483648").records.single().status, "超 Int32 ⇒ TryParse 返回 false ⇒ InvalidSpecification")
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "DRAWING INTERFACE:1,2").records.single().status, "逗号组合只对 flags 枚举有效；这 5 个都不是 flags（TextDrawingMode.cs 等无 Flags 特性）")
        assertEquals(ConfigLineStatus.PARSE_ERROR, loadGame(store, "REDUCE ARGUMENT ON LOAD:MAYBE").records.single().status)
    }

    // -----------------------------------------------------------------
    // 钉位：枚举行是唯一「解析成功但原版返回 false」的类型
    // -----------------------------------------------------------------

    @Test
    fun `fixed 文件钉不住枚举条目（枚举分支从不给 flag 赋值）`() {
        val store = newStore()
        loadGame(
            store,
            "DRAWING INTERFACE:GRAPHICS",
            "OUTPUT ENGLISH ITEMS IN THE CONFIG FILE:YES",
            "WINDOW WIDTH:900",
            "USE MOUSE:NO",
            "TEXT EDITOR:editor.exe",
            "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:erb",
            fix = true,
        )
        // 值全都写进去了
        assertEquals("GRAPHICS", store.itemOf(ConfigKey.TextDrawingMode).enumConstant())
        assertEquals(900, store.itemOf(ConfigKey.WindowX).int())
        // 但 TryParse 的返回值决定钉不钉：int/bool/string/List 会置 true（ConfigItem.cs:129、:104、:162、:167），
        // 5 个枚举分支从不碰 flag（ConfigItem.cs:172-211 + 末尾 :212 的 return flag）
        assertTrue(store.itemOf(ConfigKey.WindowX).fixed)
        assertTrue(store.itemOf(ConfigKey.UseMouse).fixed)
        assertTrue(store.itemOf(ConfigKey.TextEditor).fixed)
        assertTrue(store.itemOf(ConfigKey.ValidExtension).fixed)
        assertTrue(store.itemOf(ConfigKey.EnglishConfigOutput).fixed)
        assertFalse(store.itemOf(ConfigKey.TextDrawingMode).fixed, "枚举行 APPLIED 但不置钉 —— 官方怪癖，原样复刻（ADR-005）")
        assertEquals(ConfigLineStatus.APPLIED, loadGame(store, "DRAWING INTERFACE:WINAPI").records.single().status)
        assertEquals("WINAPI", store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "没被钉住 ⇒ 后一个文件还能覆盖它")
        assertEquals(ConfigLineStatus.ALREADY_FIXED, loadGame(store, "WINDOW WIDTH:1000").records.single().status)
        assertEquals(900, store.itemOf(ConfigKey.WindowX).int())
    }

    // -----------------------------------------------------------------
    // 三个特殊条目（ConfigData.cs:544-572）
    // -----------------------------------------------------------------

    @Test
    fun `CompatiDRAWLINE 的行改写进 CompatiLinefeedAs1739，自己的条目不动`() {
        val store = newStore()
        val r = loadGame(store, "ALWAYS START DRAWLINE IN A NEW LINE:YES")
        assertEquals(ConfigLineStatus.APPLIED, r.records.single().status)
        assertEquals(ConfigKey.CompatiLinefeedAs1739, r.records.single().matchedKey)
        assertTrue(store.itemOf(ConfigKey.CompatiLinefeedAs1739).bool())
        assertFalse(store.itemOf(ConfigKey.CompatiDRAWLINE).bool(), "被重定向后原条目仍保持默认")
        assertTrue(EmueraConfigWriter.gameLines(store, english = true).none { it.startsWith("ALWAYS START DRAWLINE") }, "CompatiDRAWLINE 永不写出（ConfigData.cs:433）")
    }

    @Test
    fun `TextEditor 的冒号只在后一段以反斜杠开头时才接回`() {
        val store = newStore()
        loadGame(store, """TEXT EDITOR:C:\tools\ed.exe""")
        assertEquals("""C:\tools\ed.exe""", store.itemOf(ConfigKey.TextEditor).string())
        loadGame(store, """TEXT EDITOR:C:\a\b --log:D:\x""")
        assertEquals("""C:\a\b --log:D:\x""", store.itemOf(ConfigKey.TextEditor).string(), "段数大于 3 时第 4 段起无条件接回（ConfigData.cs:555-562）")
        loadGame(store, "TEXT EDITOR:C:/a:b")
        assertEquals("C:b", store.itemOf(ConfigKey.TextEditor).string(), "第 3 段以正斜杠开头 ⇒ 不接回，而第 4 段仍接回 ⇒ 值被折成 C:b。官方形状，原样保留")
    }

    @Test
    fun `EditorArgument 绕过 TryParse 保留原空白，且 fixed 文件也钉不住它`() {
        val store = newStore()
        loadGame(store, "TEXT EDITOR COMMAND LINE ARGUMENTS:  +{line}  ", fix = true)
        assertEquals("  +{line}  ", store.itemOf(ConfigKey.EditorArgument).string(), "直写 parts[1]，不 Trim（ConfigData.cs:566）")
        assertFalse(store.itemOf(ConfigKey.EditorArgument).fixed, "continue 在置 Fixed 之前（ConfigData.cs:567）")
        loadGame(store, "TEXT EDITOR COMMAND LINE ARGUMENTS:{line}")
        assertEquals("{line}", store.itemOf(ConfigKey.EditorArgument).string())

        loadGame(store, "MAX HISTORY LOG LINES:4000", fix = true)
        assertEquals(4000, store.itemOf(ConfigKey.MaxLog).int())
        assertTrue(store.itemOf(ConfigKey.MaxLog).fixed)
        val after = loadGame(store, "MAX HISTORY LOG LINES:900")
        assertEquals(ConfigLineStatus.ALREADY_FIXED, after.records.single().status)
        assertEquals(4000, store.itemOf(ConfigKey.MaxLog).int(), "Fixed 时 TryParse 在 ConfigItem.cs:96-99 直接返回")
        store.unfixGameItems()
        assertFalse(store.itemOf(ConfigKey.MaxLog).fixed)
    }

    @Test
    fun `分析模式把 MAXLOG 强行改写成 10000`() {
        val store = newStore()
        loadGame(store, "MAX HISTORY LOG LINES:900", analysisMode = true)
        assertEquals(10000, store.itemOf(ConfigKey.MaxLog).int(), "ConfigData.cs:569-572 在 TryParse 之前替换 array[1]")
    }

    // -----------------------------------------------------------------
    // 另外两个同族加载器
    // -----------------------------------------------------------------

    @Test
    fun `debug 文件只认日文标签`() {
        val store = newStore()
        val r = EmueraConfigParser.loadDebugFile(ConfigFile("debug.config", listOf("Show debug window on startup:NO", "デバッグウィンドウ幅:800")), store)
        assertEquals(listOf(ConfigLineStatus.UNMATCHED_KEY, ConfigLineStatus.APPLIED), r.records.map { it.status })
        assertEquals(800, store.itemOf(ConfigKey.DebugWindowWidth).int())
        assertTrue(store.itemOf(ConfigKey.DebugShowWindow).bool())
    }

    @Test
    fun `replace 文件把第一个分隔符之后的原文整段当值`() {
        val store = newStore()
        val r = EmueraConfigParser.loadReplaceFile(
            ConfigFile(
                "_Replace.csv",
                listOf(
                    " ;行首空格的注释在这条路径算注释",
                    "お金の単位:$, USD:",
                    "汚れの初期値:0/0/2/1/8",
                    "BAR文字2:xy",
                    "バー文字2:xy",
                ),
            ),
            store,
        )
        assertEquals(ConfigLineStatus.COMMENT, r.records[0].status, "整行先 Trim（ConfigData.cs:607），与主文件的第 0 位判定不同")
        assertEquals("$, USD:", store.itemOf(ConfigKey.MoneyLabel).string(), "substring 取到行尾（ConfigData.cs:617-619）")
        assertEquals(listOf(0L, 0L, 2L, 1L, 8L), store.itemOf(ConfigKey.StainDefault).longList())
        // char 型解析失败不抛错，静默保留旧值（ConfigItem.cs:119-126）
        assertEquals(ConfigLineStatus.SILENT_KEEP_DEFAULT, r.records[3].status)
        assertEquals('*', store.itemOf(ConfigKey.BarChar1).char())
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, r.records[4].status, "键写错时 ?. 让整行静默跳过（ConfigData.cs:622）")
        loadReplaceOne(store, "BAR文字2:=")
        assertEquals('=', store.itemOf(ConfigKey.BarChar2).char())
    }

    @Test
    fun `replace 的键尾带空格时偏移按未裁剪的首段长度算（语料里就是这个形状）`() {
        val store = newStore()
        // spec: ConfigData.cs:617-619 —— `num = array[0].Length + 1`，用的是 Split 出来的
        // **原始**首段长度，不是 Trim 后的键长。语料 `CSV/_Replace.csv:7`、`:9`、`:14`、`:17`
        // 四行都是「键 空格 逗号」，所以这条不是想象中的形状。
        val r = EmueraConfigParser.loadReplaceFile(
            ConfigFile(
                "_Replace.csv",
                listOf(
                    "お金の単位 , ￥",
                    "単位の位置 , 前",
                    "販売道具数,0",
                    "汚れの初期値, 0/0/2/1/8/0/1/8",
                ),
            ),
            store,
        )
        assertEquals(
            listOf(
                ConfigLineStatus.APPLIED,
                ConfigLineStatus.APPLIED,
                ConfigLineStatus.UNMATCHED_KEY, // 语料把「販売アイテム数」写成「販売道具数」⇒ 静默跳过（:622 的 `?.`）
                ConfigLineStatus.APPLIED,
            ),
            r.records.map { it.status },
        )
        // 值 = 「 ￥」，TryParse 的 Trim（ConfigItem.cs:163）收成「￥」；
        // 若按 Trim 后的键长算偏移，值会变成「, ￥」——逗号被留在值里。
        assertEquals("￥", store.itemOf(ConfigKey.MoneyLabel).string())
        assertEquals(" ￥", r.records[0].rawValue, "记录里留的是裁剪前的原文，便于回显语料那一行到底读到了什么")
        // 「 前」必须能被 tryStringToBool 认下（Ordinal 比较，ConfigItem.cs:244）；
        // 偏移算错就会得到「, 前」⇒ 抛 CodeEE ⇒ 按 :627 的 try 包住整个 while，后面的行全部作废。
        assertTrue(store.itemOf(ConfigKey.MoneyFirst).bool())
        assertEquals(listOf(0L, 0L, 2L, 1L, 8L, 0L, 1L, 8L), store.itemOf(ConfigKey.StainDefault).longList(), "首个逗号后是空格，逐项 Trim 后仍是十进制（ConfigItem.cs:151）")
        assertEquals(0, r.warnings.size)
        assertNull(r.abortedAtLine)
    }

    private fun loadReplaceOne(store: ConfigStore, line: String) {
        val r = EmueraConfigParser.loadReplaceFile(ConfigFile("_Replace.csv", listOf(line)), store)
        assertEquals(ConfigLineStatus.APPLIED, r.records.single().status)
    }

    @Test
    fun `long 列表解析中途抛错时旧值已被 Clear 只剩前缀`() {
        val store = newStore()
        val r = EmueraConfigParser.loadReplaceFile(ConfigFile("_Replace.csv", listOf("EXPLVの初期値:0/1/x/4")), store)
        assertEquals(ConfigLineStatus.PARSE_ERROR, r.records.single().status)
        assertEquals(listOf(0L, 1L), store.itemOf(ConfigKey.ExpLvDef).longList(), "ConfigItem.cs:147 的 Value.Clear() 在解析之前")
        assertNotEquals(ConfigSpecTable.specOf(ConfigKey.ExpLvDef)!!.defaultValue, store.itemOf(ConfigKey.ExpLvDef).value)
        assertEquals(0, r.droppedLines, "单行文件停在第 1 行 ⇒ 后面没有可丢的行")
    }

    // -----------------------------------------------------------------
    // 存储层
    // -----------------------------------------------------------------

    @Test
    fun `copy 带钉位且与原件互不影响`() {
        val store = newStore()
        loadGame(store, "MAX HISTORY LOG LINES:4000", fix = true)
        val copy = store.copy()
        assertEquals(4000, copy.itemOf(ConfigKey.MaxLog).int())
        assertTrue(copy.itemOf(ConfigKey.MaxLog).fixed)
        loadGame(copy, "USE MOUSE:NO")
        assertTrue(store.itemOf(ConfigKey.UseMouse).bool())
        assertFalse(copy.itemOf(ConfigKey.UseMouse).bool())
        loadGame(copy, "MAX HISTORY LOG LINES:900")
        assertEquals(4000, copy.itemOf(ConfigKey.MaxLog).int())
    }

    @Test
    fun `链式加载的顺序就是优先级`() {
        val store = newStore()
        val results = EmueraConfigParser.loadGameChain(
            store,
            defaultFile = ConfigFile("default.config", listOf("USE MOUSE:NO", "SHOW MENU:NO")),
            emueraFile = ConfigFile("emuera.config", listOf("USE MOUSE:YES")),
            fixedFile = ConfigFile("fixed.config", listOf("SHOW MENU:YES")),
        )
        assertEquals(3, results.size)
        assertTrue(store.itemOf(ConfigKey.UseMouse).bool(), "emuera.config 覆盖 default.config（ConfigData.cs:491-492）")
        assertTrue(store.itemOf(ConfigKey.UseMenu).bool(), "fixed.config 覆盖两者（ConfigData.cs:493）")
        assertTrue(store.itemOf(ConfigKey.UseMenu).fixed)
        assertFalse(store.itemOf(ConfigKey.UseMouse).fixed, "只有 fixed 那次置钉")
    }

    @Test
    fun `条目表的规模与分组是外部读数`() {
        assertEquals(100, ConfigSpecTable.gameCount, "configArray：ConfigData.cs:45-151 共 107 行，其中 ValidExtension 占 120-127 八行 ⇒ 100 条")
        assertEquals(7, ConfigSpecTable.debugCount, "debugArray：ConfigData.cs:152-158")
        assertEquals(16, ConfigSpecTable.replaceCount, "replaceArray：ConfigData.cs:159-174")
        assertEquals(123, ConfigSpecTable.all.size)
        assertEquals(124, ConfigKey.entries.size, "ConfigCode.cs:5-128 每行一个成员，共 124 个")
        assertNull(ConfigSpecTable.specOf(ConfigKey.UseImageBuffer), "枚举里有、条目表里没有（ConfigCode.cs:15）")
        assertEquals(ConfigKey.IgnoreCase, ConfigKey.fromCode(0))
        assertEquals(ConfigKey.CBMinTimer, ConfigKey.fromCode(272))
        assertNull(ConfigKey.fromCode(29), "29 与 33 在原版枚举里就是空档")
    }

    @Test
    fun `debug 与 replace 两组的缺省值与类型逐条钉住`() {
        // 语料那份 emuera.config 只能反向校验 configArray 的默认值（本路 golden 判据 3），
        // debugArray / replaceArray 没有对应的磁盘文件，所以这 23 条只能在这里手钉。
        // 这里刻意走 bool/int/char/longList 这些**带类型转换**的读数口（ConfigItem.kt:49-63），
        // 于是「值抄错」和「类型抄错」都会在这里炸，而不是拖到消费点。
        // 每条尾注释是 ConfigData.cs 的注册行号，值就是那一行 `ConfigCode` 之后的第三个实参。
        val store = ConfigStore()
        assertTrue(store.itemOf(ConfigKey.DebugShowWindow).bool()) // :152
        assertTrue(store.itemOf(ConfigKey.DebugWindowTopMost).bool()) // :153
        assertEquals(400, store.itemOf(ConfigKey.DebugWindowWidth).int()) // :154
        assertEquals(300, store.itemOf(ConfigKey.DebugWindowHeight).int()) // :155
        assertFalse(store.itemOf(ConfigKey.DebugSetWindowPos).bool()) // :156
        assertEquals(0, store.itemOf(ConfigKey.DebugWindowPosX).int()) // :157
        assertEquals(0, store.itemOf(ConfigKey.DebugWindowPosY).int()) // :158
        assertEquals(7, store.itemsOf(ConfigGroup.DEBUG).size)

        assertEquals("${'$'}", store.itemOf(ConfigKey.MoneyLabel).string()) // :159
        assertTrue(store.itemOf(ConfigKey.MoneyFirst).bool()) // :160
        assertEquals("Now Loading...", store.itemOf(ConfigKey.LoadLabel).string()) // :161
        assertEquals(100, store.itemOf(ConfigKey.MaxShopItem).int()) // :162
        assertEquals("-", store.itemOf(ConfigKey.DrawLineString).string()) // :163 —— 空串才回落，缺省本来就是 "-"（Config.cs:726-729）
        assertEquals('*', store.itemOf(ConfigKey.BarChar1).char()) // :164
        assertEquals('.', store.itemOf(ConfigKey.BarChar2).char()) // :165
        assertEquals("最初からはじめる", store.itemOf(ConfigKey.TitleMenuString0).string()) // :166
        assertEquals("ロードしてはじめる", store.itemOf(ConfigKey.TitleMenuString1).string()) // :167
        assertEquals(1, store.itemOf(ConfigKey.ComAbleDefault).int()) // :168
        assertEquals(listOf(0L, 0L, 2L, 1L, 8L), store.itemOf(ConfigKey.StainDefault).longList()) // :169
        assertEquals("時間切れ", store.itemOf(ConfigKey.TimeupLabel).string()) // :170
        assertEquals(listOf(0L, 1L, 4L, 20L, 50L, 200L), store.itemOf(ConfigKey.ExpLvDef).longList()) // :171
        assertEquals(
            listOf(0L, 100L, 500L, 3000L, 10000L, 30000L, 60000L, 100000L, 150000L, 250000L),
            store.itemOf(ConfigKey.PalamLvDef).longList(), // :172
        )
        assertEquals(4L, store.itemOf(ConfigKey.pbandDef).long()) // :173
        assertEquals(0L, store.itemOf(ConfigKey.RelationDef).long()) // :174
        assertEquals(16, store.itemsOf(ConfigGroup.REPLACE).size)
    }
}
