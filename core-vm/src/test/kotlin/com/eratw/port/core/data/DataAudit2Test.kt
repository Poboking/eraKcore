package com.eratw.port.core.data

import com.eratw.port.core.data.csv.CharaCsvLoader
import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameBaseLoader
import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * audit2 L06（CSV / 游戏数据装载）—— 本轮修复的边界钉死。全部合成数据，不依赖语料环境变量。
 *
 * 六条被测语义（均取反编译 C# 原样）：
 * 1. CHARA CSV 的 CSTR 数字键**先按 long 判界再 (int) 截断**（ConstantData.cs:1687-1692）——
 *    先截断会让 2^32 回绕成 0 命中 CSTR[0]；
 * 2. `tryToInt64` 的字面量文法 = `LexicalAnalyzer.ReadInt64`（LexicalAnalyzer.cs:30-90）：
 *    溢出即解析失败（不是回绕），且支持 `0x` / `0b` / `e`·`p` 指数；
 * 3. 数字判定吃 `tokens[1].TrimEnd()`（:1686），但名字反查与告警文案吃**未 trim 的** `tokens[1]`
 *    （:1695 / :1697 / :1702 / :1712 / :1718）；
 * 4. 行首空白集在**本游戏配置**（`WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`）下不含 U+3000
 *    （LexicalAnalyzer.cs:677-683 的 `Config.SystemAllowFullSpace` 门）；
 * 5. `SAVECHARA` 的重复角色号在进 `SaveChara` 之前就抛 CodeEE
 *    （FunctionIdentifier.cs:1888-1893，`DuplicateCharaNo`）；
 * 6. `GameBase.tryatoi` 的后备数字串按 `char.IsNumber`（类别 N）取段后**整段**解析
 *    （GameBase.cs:93-99），混合全角数字 ⇒ 0 而不是截断值。
 *
 * 另登记两条**有意偏离**（不测、只在装载器 KDoc 里说明）：VariableSizeLoader 的 2/3 维总元素
 * 判定用 Long 而非原版的 int×int（会回绕放行，ConstantData.cs:406 / :443），以及 CsvLoader
 * 固定 UTF-8 而不做原版的内容嗅探回退（EncodingHandler.cs:15-41）。两者本语料均不可达。
 */
class DataAudit2Test {

    // ------------------------------------------------------------------
    // 1. CSTR 数字键的判界宽度
    // ------------------------------------------------------------------

    /**
     * `CSTR,4294967296,...`：旧实现 `numeric.toInt()` = 0 ⇒ 命中 CSTR[0] 覆盖真值；
     * 原版先比 long（`p >= num2` ⇒ OoRArray）⇒ 整行跳过。默认 CSTR 长度 = 100。
     */
    @Test
    fun `CSTR 数字键按 long 判界 2^32 回绕不得命中 0 号`() {
        val dir = createTempDirectory("eratw-cstr-wrap")
        try {
            dir.resolve("Chara7 回绕.csv").writeText(
                "番号,7\n" +
                    "名前,回绕\n" +
                    "CSTR,0,零号\n" +
                    "CSTR,4294967296,回绕成0\n" +   // (int)2^32 = 0，旧实现会覆盖 CSTR[0]
                    "CSTR,4294967297,回绕成1\n",    // (int)2^32+1 = 1
            )
            val warnings = ArrayList<String>()
            val t = CharaCsvLoader.load(dir, VariableRegistry(), warnings).single()
            assertEquals("零号", t.cstr[0], "2^32 不得覆盖 CSTR[0]（原版 OoRArray 跳过）")
            assertNull(t.cstr[1], "2^32+1 不得写进 CSTR[1]")
            assertEquals(
                2, warnings.count { it.contains("CSTR 下标") && it.contains("越界") },
                warnings.toString(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 2. tryToInt64：溢出 / 进制前缀 / 指数
    // ------------------------------------------------------------------

    /**
     * 键列 18446744073709551617（2^64+1）：旧实现十进制累乘回绕成 1 ⇒ 写进 CFLAG[1]；
     * 原版 `long.Parse` 抛 OverflowException → tryToInt64 false → 按名反查 → NotDefinedKey 跳过。
     */
    @Test
    fun `模板键 Int64 溢出按解析失败跳过而不是回绕`() {
        val dir = createTempDirectory("eratw-key-overflow")
        try {
            dir.resolve("Chara8 溢出.csv").writeText(
                "番号,8\n" +
                    "名前,溢出\n" +
                    "フラグ,18446744073709551617,9\n" + // 2^64+1：旧实现回绕成 1
                    "フラグ,10,9\n",
            )
            val warnings = ArrayList<String>()
            val t = CharaCsvLoader.load(dir, VariableRegistry(), warnings).single()
            assertNull(t.cflag[1], "溢出键不得回绕成 1（ConstantData.cs:1558-1565 溢出即 false）")
            assertEquals(9L, t.cflag[10], "同文件后续合法行照常落地")
            assertTrue(
                warnings.any { it.contains("键未定义") && it.contains("18446744073709551617") },
                warnings.toString(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 同一个 `tryToInt64` 还负责 `0x` / `0b` / 指数（LexicalAnalyzer.cs:36-51 / :66-88）。
     * 旧实现按十进制只吃到首个非数字字符：`0x10` → 0（静默写错下标）、`1e1` → 1。
     */
    @Test
    fun `模板键与值支持十六进制 二进制 指数字面量`() {
        val dir = createTempDirectory("eratw-radix")
        try {
            dir.resolve("Chara9 进制.csv").writeText(
                "番号,9\n" +
                    "名前,进制\n" +
                    "フラグ,0x10,0x20\n" +   // 键 16、值 32
                    "素質,0b101,1e2\n" +     // 键 5、值 100
                    "能力,1e1,0b11\n",       // 键 10、值 3
            )
            val warnings = ArrayList<String>()
            val t = CharaCsvLoader.load(dir, VariableRegistry(), warnings).single()
            assertEquals(32L, t.cflag[16], "0x10 应为 16（不是 0）且值 0x20 = 32")
            assertNull(t.cflag[0], "0x10 不得落到 0 号")
            assertEquals(100L, t.talent[5], "0b101 = 5，值 1e2 = 100")
            assertEquals(3L, t.abl[10], "1e1 = 10，值 0b11 = 3")
            assertTrue(
                warnings.none { it.contains("越界") || it.contains("未定义") },
                warnings.toString(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 3. 名字反查用的串：判定 trimEnd、反查**不 trim**
    // ------------------------------------------------------------------

    /**
     * `能力,攻撃力 ,5`（键列带尾随空格）：原版 `tryToInt64(tokens[1].TrimEnd())`（:1686）失败，
     * 名字反查走 `dictionary3.TryGetValue(tokens[1])`（:1695）—— 喂的是**未 trim 的** `攻撃力 `，
     * 名表里没有这个键 ⇒ NotDefinedKey 跳过。先 trim 再反查会把这行"顺手救活"，属分叉。
     */
    @Test
    fun `模板键名字反查用未 trim 的原串`() {
        val dir = createTempDirectory("eratw-key-raw")
        try {
            val registry = VariableRegistry()
            registry.csvTables[0].set(1, "攻撃力")   // ABL 名表（dicSlot 0）
            dir.resolve("Chara11 空格.csv").writeText(
                "番号,11\n" +
                    "名前,空格\n" +
                    "能力,攻撃力 ,5\n" +   // 尾随空格 ⇒ 原版查不到，跳过
                    "CSTR,攻撃力 ,9\n",    // CSTR 同口径（槽 17）
            )
            val warnings = ArrayList<String>()
            val t = CharaCsvLoader.load(dir, registry, warnings).single()
            assertNull(t.abl[1], "带尾随空格的键不得命中名表（ConstantData.cs:1695 用未 trim 原串）")
            assertTrue(warnings.any { it.contains("能力") && it.contains("键未定义") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 对照组：没有尾随空格时同一行必须正常落地（证明上一条的跳过不是别的原因造成的）。 */
    @Test
    fun `模板键名字反查在无前导尾随空白时照常命中`() {
        val dir = createTempDirectory("eratw-key-hit")
        try {
            val registry = VariableRegistry()
            registry.csvTables[0].set(1, "攻撃力")
            dir.resolve("Chara12 命中.csv").writeText(
                "番号,12\n" +
                    "名前,命中\n" +
                    "能力,攻撃力,5\n",
            )
            val warnings = ArrayList<String>()
            val t = CharaCsvLoader.load(dir, registry, warnings).single()
            assertEquals(5L, t.abl[1], warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 4. 行首空白集：本游戏配置下全角空格**不是**空白
    // ------------------------------------------------------------------

    /**
     * `SkipWhiteSpace`（LexicalAnalyzer.cs:677-683）只在 `Config.SystemAllowFullSpace` 为真时才把
     * U+3000 当空白；本游戏 `emuera.config` 实测 `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO` ⇒
     * 原版**不剥**行首全角空格。连带三条可观测后果：
     * 1. `　名前,X` 的 array[0] 是「　名前」而非「名前」；
     * 2. `　;comment` 不是注释行（`;` 不在剥空白后的行首）；
     * 3. 只有全角空格的一行**不是空行**，照样产出给下游。
     */
    @Test
    fun `行首全角空格在本游戏配置下不算空白`() {
        val dir = createTempDirectory("eratw-fullwidth-ws")
        try {
            val p = dir.resolve("Ws.csv")
            p.writeText("　名前,保留\n" + ";真注释\n" + "　\n" + "能力,体力,1\n")
            val lines = CsvLoader.readEnabledLines(p)
            assertEquals(3, lines.size, "全角空格行与「全角空格+;」行原版都不跳（原版命中 0 条 ⇒ 仅口径钉死）")
            assertEquals("　名前,保留", lines[0])
            assertEquals("　", lines[1])
            assertEquals("能力,体力,1", lines[2])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 5. SAVECHARA 参数校验
    // ------------------------------------------------------------------

    /**
     * 原版 `SAVECHARA_Instruction.DoInstruction` 在调用 `SaveChara` **之前**逐项校验：
     * 负数 / `>= CHARANUM` / 与前面的参数重复都抛 CodeEE（FunctionIdentifier.cs:1873-1897）。
     * 旧实现只查范围，`SAVECHARA "f", 0, 0` 会写出两个 0 号块 —— 读回时 `AddRange` 多出角色。
     */
    @Test
    fun `SAVECHARA 重复角色号抛 CodeEE`() {
        val store = VariableStore()
        store.characters.addBare()
        store.characters.addBare()

        assertEquals(2, CharaFileIO.capture(store, listOf(0L, 1L), "t").characters.size)

        val dup = assertFailsWith<CodeEE> { CharaFileIO.capture(store, listOf(0L, 0L), "t") }
        // 文案照原版第四条校验消息（DuplicateCharaNo，lang/emuera-zhs.xml:525）：
        // 「同一个角色登记编号({0})被多次指定」——本文件旧钉的「被重复指定」是并四合一前的旧口径。
        assertTrue(dup.message?.contains("多次指定") == true, dup.message)
        assertFailsWith<CodeEE> { CharaFileIO.capture(store, listOf(-1L), "t") }
        assertFailsWith<CodeEE> { CharaFileIO.capture(store, listOf(2L), "t") }
    }

    // ------------------------------------------------------------------
    // 6. GameBase 值解析：数字串谓词是 char.IsNumber，且**整段**再解析
    // ------------------------------------------------------------------

    /**
     * `GameBase.tryatoi`（GameBase.cs:82-104）的第二条路径收「开头连续 `char.IsNumber`（类别 N）」
     * 字符（:93）后**整段** `long.TryParse`（:98）。`1２3` 因此收成 "1２3" → 拒 → 落 0；
     * 「截到首个非 ASCII 数字」的写法会得 1。对照组 `0012abc` 只收 ASCII 数字 → 12。
     */
    @Test
    fun `GameBase 值解析的 IsNumber 数字串整段拒绝而非截断`() {
        val dir = createTempDirectory("eratw-gamebase-tryatoi")
        try {
            dir.resolve("GameBase.csv").writeText("コード,1２3\nバージョン違い認める,0012abc\n")
            val w = ArrayList<String>()
            val base = GameBaseLoader.load(dir, w)
            assertEquals(0L, base.gameCode, "全角数字混进数字串 ⇒ 整段 TryParse 失败 ⇒ 0（GameBase.cs:93-99）")
            assertEquals(12L, base.allowVersion, "纯 ASCII 数字前缀照常解析成 12")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
