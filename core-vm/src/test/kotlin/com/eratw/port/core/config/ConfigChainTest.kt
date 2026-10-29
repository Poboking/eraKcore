package com.eratw.port.core.config

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.BuiltinRegistry
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.LangError
import com.eratw.port.core.fn.registerConfigMethods
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `default.config` / `fixed.config` 三层装载链 + `GETCONFIG` / `GETCONFIGS` 的回归。
 *
 * ## 判据对应
 * * 「三层各自读到的行数 + 合并后可见键数」= [三层链的行数与合并后可见键数读数]（合成文件）
 *   与 [语料三层文件的实测读数与钉位]（真实语料）；
 * * 三个优先级用例 = [fixed 层覆盖 default 与用户 config]、[用户 config 覆盖 default]、
 *   [fixed 键拒绝任何后续写回]（fixed 不被用户/设置页覆盖，含设置页那条写入器守卫）；
 * * **2 个用例吃 `ERATW_GAMEDIR` 环境变量**（[语料三层文件的实测读数与钉位]、
 *   [语料装载链上的 GETCONFIG 读数]）；未设置或语料缺文件时 `assumeTrue` 判跳过。
 *
 * ## 语义出处
 * C# 侧的链是 `ConfigData.LoadConfig`（`reference/emuera-cs/MinorShift.Emuera.Runtime.Config/ConfigData.cs:479-510`）
 * 与 `loadConfig`（`:512-592`）；钉位是 `:573-576` 加 `ConfigItem.cs:20-27`、`:96-99` 两道守卫。
 * 函数侧是 `FunctionMethodCreator.cs:6021-6074`（`GetConfigMethod` 类体）+ `:9148-9149`（注册表）
 * + `ConfigData.cs:350-422`（`GetConfigValueInERB`）。逐行落点见 [ConfigMethods] 与
 * [EmueraConfigParser.loadChainFromDisk] 的 KDoc。
 */
class ConfigChainTest {

    // ------------------------------------------------------------------
    // 装载链：优先级、行数、可见键
    // ------------------------------------------------------------------

    @Test
    fun `fixed 层覆盖 default 与用户 config`() {
        val root = newGameDir()
        writeCsv(root, "_default.config", "WINDOW WIDTH:1000", "USE MOUSE:NO", "FONT SIZE:12")
        writeExe(root, "emuera.config", "WINDOW WIDTH:800", "USE MOUSE:YES")
        writeCsv(root, "_fixed.config", "WINDOW WIDTH:600")

        val load = chainAt(root)
        assertEquals(600, load.store.itemOf(ConfigKey.WindowX).int(), "fixed 是最后一层且最高优先级（ConfigData.cs:493）")
        assertEquals(12, load.store.itemOf(ConfigKey.FontSize).int(), "只被 default 层写过的键保持 default 层的值（注册默认 18，这里是 default.config 写的 12）")
        assertTrue(load.store.itemOf(ConfigKey.UseMouse).bool(), "emuera 层写过、fixed 层没提的键不受影响")
        assertTrue(load.store.itemOf(ConfigKey.WindowX).fixed, "fixed 层解析成功的条目要置钉（ConfigData.cs:573-576）")
        assertFalse(load.store.itemOf(ConfigKey.FontSize).fixed, "没出现在 fixed 层的条目不得被钉")
    }

    @Test
    fun `用户 config 覆盖 default`() {
        val root = newGameDir()
        writeCsv(root, "_default.config", "WINDOW WIDTH:1000", "SHOW MENU:NO")
        writeExe(root, "emuera.config", "WINDOW WIDTH:800")

        val load = chainAt(root)
        assertEquals(800, load.store.itemOf(ConfigKey.WindowX).int(), "emuera 在 default 之后读（ConfigData.cs:491-492）")
        assertFalse(load.store.itemOf(ConfigKey.UseMenu).bool(), "default 层对 emuera 没提的键生效（注册默认是 YES）")
        assertFalse(load.store.itemOf(ConfigKey.WindowX).fixed, "fix 只在第三次调用为 true（ConfigData.cs:491-493）")
        assertEquals(ConfigLayerPresence.ABSENT, load.layer(ConfigChainRole.FIXED).presence, "没有 fixed 文件时该层静默缺席")
        assertEquals(0, load.fixedLineCount)
        assertEquals(2, load.results.size, "只有两层在位时 results 只有两条（spec: ConfigData.cs:514-517 的静默 return false）")
        // 用户 config 覆盖过 default 的键，之后仍能被设置页写回
        assertTrue(load.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(900)), "未钉住的条目可写回")
        assertEquals(900, load.store.itemOf(ConfigKey.WindowX).int())
    }

    @Test
    fun `fixed 键拒绝任何后续写回`() {
        val root = newGameDir()
        writeCsv(root, "_fixed.config", "WINDOW WIDTH:600", "USE MOUSE:NO")
        val load = chainAt(root)
        val windowX = load.store.itemOf(ConfigKey.WindowX)

        // 设置页走 ConfigItem.Value 的写入器（守卫 spec: ConfigItem.cs:20-27）
        assertFalse(windowX.assign(ConfigValue.IntValue(1000)), "钉住的条目必须静默拒绝写入，否则 fixed.config 就白读了")
        assertEquals(600, windowX.int())
        // 之后再读一个普通文件也改不动（守卫 spec: ConfigItem.cs:96-99，先判钉、连 Trim 都不做）
        val again = EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", listOf("WINDOW WIDTH:700")), load.store)
        assertEquals(listOf(ConfigLineStatus.ALREADY_FIXED), again.records.map { it.status })
        assertEquals(600, windowX.int())
        assertEquals(0, again.appliedCount)
        // 布尔型同样被钉
        assertFalse(load.store.itemOf(ConfigKey.UseMouse).assign(ConfigValue.Bool(true)))
        assertEquals(setOf(ConfigKey.WindowX, ConfigKey.UseMouse), load.fixedKeys)
    }

    @Test
    fun `三层链的行数与合并后可见键数读数`() {
        val root = newGameDir()
        // 5 行：2 行落条目、1 行注释、1 行空、1 行未知键
        writeCsv(root, "_default.config", "WINDOW WIDTH:1000", "; 注释行不计", "", "NOT A KEY:1", "FONT SIZE:14")
        // 写成「末尾带换行」的 2 行 —— 用来钉 ReadLine 的行数口径；用户 config 在 exe 目录，不在 CSV
        writeExeNewline(root, "emuera.config", "WINDOW WIDTH:800", "USE MOUSE:YES")
        writeCsv(root, "_fixed.config", "SHOW MENU:NO")

        val load = chainAt(root)
        assertEquals(5, load.defaultLineCount, "default 层 5 行（空行与未知键都算行，只是不落条目）")
        assertEquals(2, load.emueraLineCount, "行尾换行不产生额外的空行（ReadLine 口径）")
        assertEquals(1, load.fixedLineCount)
        assertEquals(listOf(2, 2, 1), load.perLayerKeys.map { it.size }, "default 2 / emuera 2 / fixed 1")
        assertEquals(4, load.touchedKeys.size, "并集 = WindowX, FontSize, UseMouse, ShowMenu")
        assertEquals(setOf(ConfigKey.WindowX, ConfigKey.FontSize, ConfigKey.UseMouse, ConfigKey.UseMenu), load.touchedKeys)
        assertEquals(123, load.store.items.size, "条目表本身 123 条（configArray 100 + debugArray 7 + replaceArray 16）")
        assertEquals(1, load.result(ConfigChainRole.DEFAULT)!!.unmatchedKeys.size, "NOT A KEY 那行静默丢弃（ConfigData.cs:540-543）")
        assertEquals(5, load.result(ConfigChainRole.DEFAULT)!!.records.size, "每行都留一条记录")
        assertTrue(load.warnings.isEmpty(), "未知键/无冒号行都不告警（ConfigData.cs:535-543 的静默 continue）")
        assertTrue(load.abortedLayers.isEmpty())
    }

    @Test
    fun `下划线形优先且只认第一个存在的候选`() {
        val root = newGameDir()
        writeCsv(root, "_default.config", "WINDOW WIDTH:1000")
        writeCsv(root, "default.config", "WINDOW WIDTH:2000")
        writeCsv(root, "_fixed.config", "USE MOUSE:NO")
        writeCsv(root, "fixed.config", "USE MOUSE:YES")

        val load = chainAt(root)
        assertEquals("_default.config", load.layer(ConfigChainRole.DEFAULT).name)
        assertEquals("_fixed.config", load.layer(ConfigChainRole.FIXED).name)
        assertEquals(1000, load.store.itemOf(ConfigKey.WindowX).int(), "default.config 压根不读（ConfigData.cs:483-486）")
        assertFalse(load.store.itemOf(ConfigKey.UseMouse).bool(), "fixed.config 同理（:487-490）")
    }

    @Test
    fun `三层都缺时静默跳过且不发明默认值`() {
        val root = newGameDir()
        val store = ConfigStore()
        val defaultsBefore = store.snapshot()
        val load = EmueraConfigParser.loadChainFromDisk(root, store, csvDir(root))

        assertEquals(
            listOf(ConfigLayerPresence.ABSENT, ConfigLayerPresence.ABSENT, ConfigLayerPresence.ABSENT),
            load.layers.map { it.presence },
        )
        assertTrue(load.results.isEmpty(), "三次 loadConfig 都走 File.Exists 的 return false（ConfigData.cs:514-517）")
        assertTrue(load.warnings.isEmpty())
        assertEquals(0, load.touchedKeys.size)
        assertEquals(defaultsBefore, load.store.snapshot(), "缺文件 ⇒ 一个值都不许动，出厂默认来自 setDefault 而不是本层编造")
        assertEquals(760, load.store.itemOf(ConfigKey.WindowX).int(), "窗口宽默认值取自 ConfigData.cs:56 的注册实参")
        assertNull(load.result(ConfigChainRole.DEFAULT))
    }

    @Test
    fun `csv 目录名按大小写不敏感定位`() {
        val root = Files.createTempDirectory("eratw-config-chain").also { Files.createDirectories(it.resolve("CSV")) }
        write(root.resolve("CSV").resolve("_fixed.config"), listOf("WINDOW WIDTH:600"))
        // 不传 csvDir：原版 Program.cs:207 的字面量是小写 "csv"，Windows 上恒等价于语料的 "CSV"，
        // JVM 侧大小写敏感 ⇒ 由 resolveCsvDir 兜住。这里只断言「找到了」，不断言找到的拼写。
        val load = EmueraConfigParser.loadChainFromDisk(root, ConfigStore())
        assertEquals(600, load.store.itemOf(ConfigKey.WindowX).int(), "CSV/_fixed.config 没被读到的话这里是 760")
        assertTrue(Files.isDirectory(load.csvDir))
        assertEquals(ConfigLayerPresence.PRESENT, load.layer(ConfigChainRole.FIXED).presence)
    }

    @Test
    fun `枚举行改得了值但钉不住`() {
        val root = newGameDir()
        writeCsv(root, "_fixed.config", "WINDOW WIDTH:640", "DRAWING INTERFACE:WINAPI")
        val load = chainAt(root)

        assertEquals("WINAPI", load.store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "值照样被改写")
        assertFalse(load.store.itemOf(ConfigKey.TextDrawingMode).fixed, "TryParse 的枚举分支不置 flag ⇒ 不置钉（ConfigItem.cs:172-211 + :212）")
        assertTrue(load.store.itemOf(ConfigKey.WindowX).fixed)
        assertEquals(setOf(ConfigKey.WindowX), load.fixedKeys)
        assertNull(load.result(ConfigChainRole.FIXED)!!.abortedAtLine, "枚举行是 APPLIED，不是 PARSE_ERROR")
        assertEquals(2, load.result(ConfigChainRole.FIXED)!!.appliedCount)
    }

    @Test
    fun `compati-drawline 那行落到另一个条目上`() {
        val root = newGameDir()
        writeCsv(root, "_default.config", "DRAWLINEを常に新しい行で行う:YES")
        val load = chainAt(root)
        assertTrue(load.store.itemOf(ConfigKey.CompatiLinefeedAs1739).bool(), "读取期重定向（ConfigData.cs:544-547）")
        assertFalse(load.store.itemOf(ConfigKey.CompatiDRAWLINE).bool(), "自己那份条目不动")
        assertEquals(setOf(ConfigKey.CompatiLinefeedAs1739), load.perLayerKeys.first(), "可见键记的是目标条目")
    }

    @Test
    fun `fixed 层之后再读同键不会二次置钉但也不会回退`() {
        val root = newGameDir()
        writeCsv(root, "_fixed.config", "USE MOUSE:NO")
        writeExe(root, "emuera.config", "USE MOUSE:YES")
        // 装载链的真实顺序（default→emuera→fixed）下 fixed 赢；反过来读就丢失钉住的值 —— 这条把顺序本身钉住
        val load = chainAt(root)
        assertFalse(load.store.itemOf(ConfigKey.UseMouse).bool())
        val wrongOrder = ConfigStore()
        EmueraConfigParser.loadGameFile(ConfigFile("_fixed.config", listOf("USE MOUSE:NO")), wrongOrder, fix = true)
        EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", listOf("USE MOUSE:YES")), wrongOrder)
        assertFalse(wrongOrder.itemOf(ConfigKey.UseMouse).bool(), "反序时用户值被 ALREADY_FIXED 挡下 —— 与正序同为 NO，但记录状态不同")
        assertEquals(ConfigLineStatus.ALREADY_FIXED, EmueraConfigParser.loadGameFile(ConfigFile("x", listOf("USE MOUSE:YES")), wrongOrder).records.single().status)
    }

    // ------------------------------------------------------------------
    // GETCONFIG / GETCONFIGS
    // ------------------------------------------------------------------

    @Test
    fun `int 形的具体读数`() {
        val store = storeWith(
            "IGNORE CASE:YES", // 默认分支：ValueToString == "YES" ⇒ 1（ConfigData.cs:405-412）
            "MAKE AUTOSAVES:NO", // 专属分支：bool ⇒ 0（:357-364）
            "WINDOW WIDTH:1800", // int ⇒ long（:365-374）
            "TEXT COLOR:192,192,192", // Color ⇒ (R*256+G)*256+B（:375-382）
            "MAX HISTORY LOG LINES:400", // 默认分支的纯数字 ⇒ long；条目不被 SetConfig 的夹取回写
        )
        assertEquals(1L, ConfigMethods.valueInErb(store, "IGNORE CASE").asInt())
        assertEquals(0L, ConfigMethods.valueInErb(store, "MAKE AUTOSAVES").asInt())
        assertEquals(1800L, ConfigMethods.valueInErb(store, "WINDOW WIDTH").asInt())
        assertEquals(12632256L, ConfigMethods.valueInErb(store, "TEXT COLOR").asInt(), "(192*256+192)*256+192 == 0xC0C0C0")
        assertEquals(400L, ConfigMethods.valueInErb(store, "MAX HISTORY LOG LINES").asInt(), "读的是条目原值，不是 Config.MaxLog 的夹取值")
        assertEquals(4L, ConfigMethods.valueInErb(store, "PBANDの初期値").asInt(), "pbandDef 专属分支（:383-386）；replaceArray 的条目主文件链写不动，取注册默认 4")
        assertEquals(1L, ConfigMethods.valueInErb(store, "単位の位置").asInt(), "MoneyFirst 走专属分支（:357-364），注册默认 true")
    }

    @Test
    fun `string 形的具体读数`() {
        val store = storeWith(
            "FONT NAME:ＭＳ ゴシック",
            "DRAWING INTERFACE:WINAPI",
            "RIKAI- BACK COLOR:10,20,30",
        )
        assertEquals("ＭＳ ゴシック", ConfigMethods.valueInErb(store, "FONT NAME").asStr(), "七个 string 条目之一（ConfigData.cs:387-396）")
        assertEquals("WINAPI", ConfigMethods.valueInErb(store, "DRAWING INTERFACE").asStr(), "TextDrawingMode 专属分支给常量名（:401-403）")
        assertEquals("*", ConfigMethods.valueInErb(store, "BAR文字1").asStr(), "char ⇒ 单字符串（:397-400）；注册默认 '*'。键只能用日文标签——replaceArray 的查找不认英文标签（ConfigData.cs:298-316）")
        assertEquals(
            "10,20,30",
            ConfigMethods.valueInErb(store, "RIKAI- BACK COLOR").asStr(),
            "没进 Color case 的色条目落到默认分支，long.TryParse 失败 ⇒ 回字符串（:404-421）",
        )
        assertEquals("\$", ConfigMethods.valueInErb(store, "お金の単位").asStr(), "MoneyLabel 是 replaceArray 条目：主文件链改不动它，只能拿到 ConfigData.cs:160 注册的默认值（美元符）")
        assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "CURRENCY SYMBOL") }
    }

    @Test
    fun `两形不匹配时报原版文案`() {
        assertEquals(
            "GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)",
            LangError.invalidType(ConfigMethods.GETCONFIG, ConfigMethods.GETCONFIGS),
            "Lang.cs:3914 + 实参 (base.Name, \"GETCONFIGS\")（FunctionMethodCreator.cs:6063）",
        )
        assertEquals(
            "GETCONFIGS関数: 型が違います (GETCONFIG関数を使用してください)",
            LangError.invalidType(ConfigMethods.GETCONFIGS, ConfigMethods.GETCONFIG),
            "同一条文案的反向形（:6072）",
        )
        assertEquals(
            "文字列\"ない設定\"は適切なコンフィグ名ではありません",
            LangError.invalidConfigName("ない設定"),
            "Lang.cs:1742（ConfigData.cs:355）",
        )
        assertEquals(
            "コンフィグ文字列\"x\"の値の取得は許可されていません",
            LangError.notAllowGetConfigValue("x"),
            "Lang.cs:1746（ConfigData.cs:419）",
        )
        assertEquals(
            "GETCONFIG関数: 第1引数が空文字列です",
            LangError.argIsEmptyString(ConfigMethods.GETCONFIG, 1),
            "Lang.cs:3902 + 实参 (base.Name, 1)（:6046）；序号是字面量 1",
        )
    }

    @Test
    fun `未知键与空串实参的报错`() {
        val store = storeWith("WINDOW WIDTH:1800")
        val e = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "ウィンドウ幅X") }
        assertEquals("文字列\"ウィンドウ幅X\"は適切なコンフィグ名ではありません", e.message, "未知键不返回 0/空串，直接抛（ConfigData.cs:353-356）")

        val ctx = builtinContext(store)
        val getInt = BuiltinRegistry().also { registerConfigMethods(it) }[ConfigMethods.GETCONFIG]!!
        val empty = assertFailsWith<CodeEE> { getInt.eval!!.invoke(ctx, listOf(Value.StrV(""))) }
        assertEquals("GETCONFIG関数: 第1引数が空文字列です", empty.message)
        val notStr = assertFailsWith<CodeEE> { getInt.eval!!.invoke(ctx, listOf(Value.IntV(1L))) }
        assertEquals("函数 GETCONFIG 的第 1 个参数必须是字符串", notStr.message, "移植侧的运行期守卫；原版在解析期由 CheckArgumentType 拦下（:6037）")
        assertEquals(1800L, (getInt.eval!!.invoke(ctx, listOf(Value.StrV("WINDOW WIDTH"))) as Value.IntV).v)
        assertFailsWith<CodeEE> { getInt.eval!!.invoke(ctx, listOf(Value.StrV("存在しない"))) }
    }

    @Test
    fun `查找只认日英标签不认枚举名`() {
        val store = storeWith("WINDOW WIDTH:1800")
        assertEquals(1800L, ConfigMethods.valueInErb(store, "window width").asInt(), "键在查表内部大写化（ConfigData.cs:264），调用方原样传即可")
        assertEquals(1800L, ConfigMethods.valueInErb(store, "ウィンドウ幅").asInt(), "日文标签形同样可用（:273）")
        // Name 形命不中：AConfigItem.cs:21 的 Name 没有被大写化，而查询键被大写化（原版缺陷，ADR-005 不修）
        val e = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "WindowX") }
        assertEquals("文字列\"WindowX\"は適切なコンフィグ名ではありません", e.message)
        // 空串走的是函数层的空判，不是查表
        assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "") }
    }

    @Test
    fun `list-long 条目回吐 dotnet 运行时类型名`() {
        val store = ConfigStore()
        val v = ConfigMethods.valueInErb(store, "EXPLVの初期値")
        assertEquals("System.Collections.Generic.List`1[System.Int64]", v.asStr(), "ConfigItem.cs:81 落到 val.ToString()；原版就是这么吐的，GETCONFIGS 能观察到")
    }

    @Test
    fun `注册表接线后共用同一个 aux 槽`() {
        val root = newGameDir()
        writeCsv(root, "_default.config", "FONT NAME:ＭＳ ゴシック")
        writeCsv(root, "_fixed.config", "WINDOW WIDTH:600", "USE MOUSE:NO")
        val load = chainAt(root)
        val ctx = builtinContext(ConfigStore())
        val registry = BuiltinRegistry().also { registerConfigMethods(it) }
        val getInt = registry[ConfigMethods.GETCONFIG]!!
        val getStr = registry[ConfigMethods.GETCONFIGS]!!

        assertSame(ConfigMethods.store(ctx), ConfigMethods.store(ctx), "aux 槽只建一次（铁律 4：状态挂 store.aux）")
        ConfigMethods.install(ctx, load)
        assertSame(load.store, ConfigMethods.store(ctx), "install 之后读数吃装载链的条目表")
        assertEquals(600L, (getInt.eval!!.invoke(ctx, listOf(Value.StrV("WINDOW WIDTH"))) as Value.IntV).v)
        assertEquals(0L, (getInt.eval!!.invoke(ctx, listOf(Value.StrV("USE MOUSE"))) as Value.IntV).v)
        assertEquals(
            "GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)",
            assertFailsWith<CodeEE> { getInt.eval!!.invoke(ctx, listOf(Value.StrV("FONT NAME"))) }.message,
        )
        assertEquals("ＭＳ ゴシック", (getStr.eval!!.invoke(ctx, listOf(Value.StrV("FONT NAME"))) as Value.StrV).v)
        assertEquals(
            "GETCONFIGS関数: 型が違います (GETCONFIG関数を使用してください)",
            assertFailsWith<CodeEE> { getStr.eval!!.invoke(ctx, listOf(Value.StrV("USE MOUSE"))) }.message,
        )
        assertEquals(OperandType.INT, getInt.retType, "typeisInt=true ⇒ long（FunctionMethodCreator.cs:6030）")
        assertEquals(OperandType.STR, getStr.retType, "typeisInt=false ⇒ string（:6035）")
        assertEquals(1, getInt.minArgs)
        assertEquals(1, getInt.maxArgs, "两形的参数都是 1 个 string（:6037）")
        assertEquals(1, getStr.minArgs)
        assertEquals(1, getStr.maxArgs)
    }

    @Test
    fun `宿主单层覆盖表仍能播种条目表`() {
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = mapOf("WINDOW WIDTH" to "1234", "USE MOUSE" to "NO")
        }
        val store = ConfigMethods.storeFromHost(host)
        assertEquals(1234, store.itemOf(ConfigKey.WindowX).int())
        assertFalse(store.itemOf(ConfigKey.UseMouse).bool())
        assertFalse(store.itemOf(ConfigKey.WindowX).fixed, "宿主覆盖表不是 fixed.config，不许钉位（ConfigData.cs:491-493 只有第三次 fix: true）")
        assertEquals(480, store.itemOf(ConfigKey.WindowY).int(), "没被覆盖的键保持注册默认（`ConfigSpec` 里 WindowY 的注册实参 480）")
    }

    // ------------------------------------------------------------------
    // 语料真实文件（2 个用例吃 ERATW_GAMEDIR）
    // ------------------------------------------------------------------

    @Test
    fun `语料三层文件的实测读数与钉位`() {
        val gameDir = corpusGameDir() ?: return
        val load = EmueraConfigParser.loadChainFromDisk(gameDir, ConfigStore())

        assertEquals("_default.config", load.layer(ConfigChainRole.DEFAULT).name, "语料里是 CSV/_default.config（不是 default.config）")
        assertEquals("_fixed.config", load.layer(ConfigChainRole.FIXED).name)
        assertEquals("emuera.config", load.layer(ConfigChainRole.EMUERA).name)
        assertEquals(17, load.defaultLineCount, "CSV/_default.config 17 行（末行无换行符）")
        assertEquals(97, load.emueraLineCount, "语料 emuera.config 97 行，与 EmueraConfigGoldenTest 的外部读数一致")
        assertEquals(8, load.fixedLineCount, "CSV/_fixed.config 8 行（末行无换行符）")

        assertEquals(listOf(17, 96, 8), load.perLayerKeys.map { it.size }, "三层各自落到条目上的键数")
        assertEquals(96, load.touchedKeys.size, "并集 96：default 的 17 键与 fixed 的 8 键都包含在 emuera 的 96 键里")
        assertEquals(1, load.result(ConfigChainRole.EMUERA)!!.unmatchedKeys.size, "只有 CLIPBOARD- ANYKEY WAIT TRIGGER 那行匹配不上")
        assertTrue(load.warnings.isEmpty(), "语料三层都没有解析错误")
        assertTrue(load.abortedLayers.isEmpty())

        // 优先级读数（default 与 emuera 取值不同的键）
        assertEquals(1800, load.store.itemOf(ConfigKey.WindowX).int(), "default 写 1000、emuera 写 1800 ⇒ 1800")
        assertEquals(973, load.store.itemOf(ConfigKey.WindowY).int(), "default 800 ⇒ emuera 973")
        assertEquals(18, load.store.itemOf(ConfigKey.FontSize).int(), "default 14 ⇒ emuera 18")
        assertEquals(18, load.store.itemOf(ConfigKey.LineHeight).int())
        assertEquals("黑体", load.store.itemOf(ConfigKey.FontName).string(), "default 的 ＭＳ ゴシック 被覆盖")

        // fixed 的八个键：都被 emuera 写过一次、又被 fixed 重写并钉住
        val fixedExpected = setOf(
            ConfigKey.IgnoreCase, ConfigKey.UseRenameFile, ConfigKey.UseReplaceFile, ConfigKey.ButtonWrap,
            ConfigKey.SearchSubdirectory, ConfigKey.SortWithFilename, ConfigKey.SystemSaveInBinary, ConfigKey.UseERD,
        )
        assertEquals(fixedExpected, load.fixedKeys, "CSV/_fixed.config 的 8 行全是 bool ⇒ 全部置钉（枚举行才钉不住）")
        assertTrue(load.result(ConfigChainRole.EMUERA)!!.appliedKeys.containsAll(fixedExpected), "八个键在用户 config 里都有同名行")
        assertTrue(load.result(ConfigChainRole.FIXED)!!.appliedKeys.containsAll(fixedExpected))
        for (key in fixedExpected) {
            val item = load.store.itemOf(key)
            assertTrue(item.bool(), "$key 在语料三层里一致为 YES")
            assertFalse(item.assign(ConfigValue.Bool(false)), "$key 已被 fixed 钉住，设置页写不回去（ConfigItem.cs:20-27）")
            assertTrue(item.fixed)
        }
        // 用户 config 覆盖过 default、但不在 fixed 里的键仍然可写
        assertTrue(load.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(1000)))
    }

    @Test
    fun `语料装载链上的 GETCONFIG 读数`() {
        val gameDir = corpusGameDir() ?: return
        val load = EmueraConfigParser.loadChainFromDisk(gameDir, ConfigStore())
        val store = load.store

        assertEquals(12632256L, ConfigMethods.valueInErb(store, "TEXT COLOR").asInt(), "192,192,192 ⇒ 0xC0C0C0")
        assertEquals(1800L, ConfigMethods.valueInErb(store, "WINDOW WIDTH").asInt())
        assertEquals("黑体", ConfigMethods.valueInErb(store, "フォント名").asStr(), "日文标签形同样可用")
        assertEquals("TEXTRENDERER", ConfigMethods.valueInErb(store, "描画インターフェース").asStr())
        assertEquals(1L, ConfigMethods.valueInErb(store, "USE ERD").asInt(), "只被 fixed 写过的键")
        assertEquals(0L, ConfigMethods.valueInErb(store, "ERAMAKER COMPATIBILITY WARNING").asInt())
        assertEquals(
            "文字列\"マウスを使う\"は適切なコンフィグ名ではありません",
            assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "マウスを使う") }.message,
            "注册表里的日文标签是「マウスを使用する」，写错的键照样要报原文文案",
        )
        // 装载链之后再过一遍生效层：语料那 4 个可夹取的键都在界内，条目值不应被改写
        val effective = EffectiveConfig.of(store)
        assertEquals(1800, effective.windowX)
        assertEquals(18, effective.fontSize)
        assertEquals(store, effective.source, "EffectiveConfig 只是只读视图，不回写条目")
        assertEquals(1800, store.itemOf(ConfigKey.WindowX).int())
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun csvDir(root: Path): Path = root.resolve(CSV_DIR_NAME)

    private fun chainAt(root: Path, store: ConfigStore = ConfigStore()): ConfigChainLoad =
        EmueraConfigParser.loadChainFromDisk(root, store, csvDir(root))

    /** 造一个只有 `csv/` 空目录的游戏根。 */
    private fun newGameDir(): Path = Files.createTempDirectory("eratw-config-chain").also {
        Files.createDirectories(csvDir(it))
    }

    private fun writeCsv(root: Path, name: String, vararg lines: String): Path =
        write(csvDir(root).resolve(name), lines.toList())

    /** 写到 exe 目录并让文件以换行结尾 —— 用来钉住 `ReadLine` 的行数口径。 */
    private fun writeExeNewline(root: Path, name: String, vararg lines: String): Path =
        write(root.resolve(name), lines.toList() + "")

    private fun writeExe(root: Path, name: String, vararg lines: String): Path =
        write(root.resolve(name), lines.toList())

    private fun write(file: Path, lines: List<String>): Path {
        Files.createDirectories(file.parent)
        Files.write(file, lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return file
    }

    private fun storeWith(vararg lines: String): ConfigStore {
        val store = ConfigStore()
        EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", lines.toList()), store)
        return store
    }

    private fun Value.asInt(): Long = (this as Value.IntV).v

    private fun Value.asStr(): String = (this as Value.StrV).v

    /**
     * 只为 [ConfigMethods] 的两个函数形服务的最小语境：条目表由 [ConfigMethods.install] 装进
     * `store.aux` 槽，变量池与宿主钩子都用默认（本判据不碰它们）。
     */
    private fun builtinContext(store: ConfigStore): BuiltinEvalContext {
        val variables = VariableStore()
        val base = ChainEvalContext().apply { this.variables = variables }
        val ctx = BuiltinEvalContext(base, variables)
        ConfigMethods.install(ctx, store)
        return ctx
    }

    /**
     * 语料游戏根目录（含 `emuera.config` 与 `CSV/_default.config` 的那一层）。
     * 只认 `ERATW_GAMEDIR`（本路判据指定的变量名）；没设就跳过，不猜路径。
     */
    private fun corpusGameDir(): Path? {
        val env = System.getenv("ERATW_GAMEDIR") ?: return null
        val dir = Paths.get(env)
        if (!Files.isRegularFile(dir.resolve(EMUERA_CONFIG_FILE_NAME))) return null
        // csv 目录走装载链同一个定位器（语料实测拼写是 `CSV`，原版字面量是 `csv`）
        val csv = EmueraConfigParser.resolveCsvDir(dir)
        assumeTrue(
            Files.isRegularFile(csv.resolve("_default.config")),
            "语料缺 CSV/_default.config：跳过三层链判据",
        )
        assumeTrue(
            Files.isRegularFile(csv.resolve("_fixed.config")),
            "语料缺 CSV/_fixed.config：跳过三层链判据",
        )
        return dir
    }
}

/** 只实现 [EvalContext] 的两个必需成员（与 `fn/ColorNamesTest.kt:209-214` 同款夹具）。 */
private class ChainEvalContext : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("夹具不该调用函数 $name")
}
