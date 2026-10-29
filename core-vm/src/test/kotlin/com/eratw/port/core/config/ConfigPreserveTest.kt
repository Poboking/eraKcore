package com.eratw.port.core.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保留式写回（`EmueraConfigWriter.mergeInto`）的判据 —— CONFIG-PRESERVE 的交付面。
 *
 * 每条断言的期望形状都指回原版：跳过规则 `ConfigData.cs:433`、写出行
 * `ConfigItem.cs:53-87` / `ConfigData.cs:437-451`、读取侧键匹配 `ConfigData.cs:262-284`、
 * `:539`。最后一测直接吃语料里的真实 `emuera.config`（只读，不复制进仓）：
 * store 未修改时「读进来再合并回去」必须与原文去 BOM 后逐字符一致 —— 这条同时钉住
 * 位置保留、三条跳过规则、以及尾随空格标签（`ConfigData.cs:140`）不会被重复追加。
 */
class ConfigPreserveTest {

    @Test
    fun `未知键 注释 空行 缩进原样保留`() {
        val original = "; 顶注释\n" +
            "CUSTOM UNKNOWN:保持 原样 \n" +
            "\n" +
            "   INDENTED UNKNOWN : x:y\n" +
            "FONT SIZE:18\n" +
            "; 尾注释\n"
        val store = storeWith(ConfigKey.FontSize to ConfigValue.IntValue(20))
        val merged = EmueraConfigWriter.mergeInto(original, store, english = true)
        val lines = merged.split("\n")
        assertEquals("; 顶注释", lines[0], "注释行原样（ConfigData.cs:529-532 只把 ; 开头的行当注释）")
        assertEquals("CUSTOM UNKNOWN:保持 原样 ", lines[1], "未知键的值与尾随空格逐字符保留")
        assertEquals("", lines[2], "空行保留")
        assertEquals("   INDENTED UNKNOWN : x:y", lines[3], "未知行的缩进与键内空格保留")
        assertEquals("FONT SIZE:20", lines[4], "已知键原位替换成当前 store 的写出行")
        assertEquals("; 尾注释", lines[5], "注释仍排在已知键之前，追加块在它后面")
        assertEquals("IGNORE CASE:YES", lines[6], "缺失键从文件末尾开始按注册顺序追加")
    }

    @Test
    fun `已知键原位更新且大小写不敏感`() {
        val original = "; head\nfont name:MS Gothic\nwindow width:800\n; tail\n"
        val store = storeWith(
            ConfigKey.FontName to ConfigValue.StringValue("黑体"),
            ConfigKey.WindowX to ConfigValue.IntValue(1920),
        )
        val mergedEn = EmueraConfigWriter.mergeInto(original, store, english = true)
        val lines = mergedEn.split("\n")
        assertEquals("; head", lines[0])
        assertEquals("FONT NAME:黑体", lines[1], "小写键命中 EngText（ConfigData.cs:277），写出行用大写标签（AConfigItem.cs:23）")
        assertEquals("WINDOW WIDTH:1920", lines[2], "第二处已知键同样原位替换")
        assertEquals("; tail", lines[3], "行序不变")

        val jpOriginal = "フォント名:ＭＳ ゴシック\nウィンドウ幅:760\n"
        val mergedJp = EmueraConfigWriter.mergeInto(jpOriginal, store, english = false)
        val jpLines = mergedJp.split("\n")
        assertEquals("フォント名:黑体", jpLines[0], "日文 Text 同样命中（ConfigData.cs:273）")
        assertEquals("ウィンドウ幅:1920", jpLines[1], "english=false 时写出日文标签（ConfigItem.cs:86）")
    }

    @Test
    fun `缺失的已知键按 ConfigSpec 顺序追加到末尾`() {
        val original = "UNKNOWN KEY:1\n"
        val store = ConfigStore()
        val merged = EmueraConfigWriter.mergeInto(original, store, english = true)
        val expected = "UNKNOWN KEY:1\n" +
            EmueraConfigWriter.gameLines(store, english = true).joinToString("\n") +
            "\n"
        assertEquals(expected, merged, "追加顺序 = configArray 注册顺序（ConfigData.cs:430），跳过规则与 gameLines 同源（:433）")
        assertTrue(merged.startsWith("UNKNOWN KEY:1\nIGNORE CASE:YES"), "未知行留在原位，追加块从第二行开始")
    }

    @Test
    fun `CRLF 与末尾换行保持原文件风格`() {
        val original = "UNKNOWN KEY:1\r\nFONT SIZE:18\r\n"
        val store = storeWith(ConfigKey.FontSize to ConfigValue.IntValue(22))
        val merged = EmueraConfigWriter.mergeInto(original, store, english = true)
        assertTrue(merged.startsWith("UNKNOWN KEY:1\r\nFONT SIZE:22\r\n"), "原位替换，CRLF 不动")
        assertTrue(merged.endsWith("\r\n"), "原文以换行结尾，追加块也以换行结尾")
        assertFalse(merged.replace("\r\n", "").contains('\n'), "整份输出不应出现裸 LF")

        val noTrailing = "FONT SIZE:18"
        val mergedNoTrailing = EmueraConfigWriter.mergeInto(noTrailing, ConfigStore(), english = true)
        assertTrue(mergedNoTrailing.startsWith("FONT SIZE:18\nIGNORE CASE:YES"), "原文没有行尾时补一个分隔符再追加")
        assertFalse(mergedNoTrailing.endsWith("\n"), "原文末尾没有换行，追加后也不补（末尾风格保持）")
        assertEquals(97, mergedNoTrailing.split("\n").size, "1 条已有 + 96 条缺失 = 97 条（configArray 100 条减 3 条跳过）")
    }

    @Test
    fun `三条不写出规则命中的键保留原行且不新增`() {
        val original = "ALWAYS START DRAWLINE IN A NEW LINE:YES\n" +
            "CHANGE MASTER MAME IN DEBUG:NO\n" +
            "LATEST IDENTIFY CODE:123\n" +
            "FONT SIZE:18\n"
        // 默认 store：CompatiDRAWLINE=NO、ChangeMasterNameIfDebug=YES、LastKey=0
        // ⇒ 三条规则全部命中；原行里的值（YES / NO / 123）与 store 不同，正好证明「保留原行」。
        val merged = EmueraConfigWriter.mergeInto(original, ConfigStore(), english = true)
        assertTrue(merged.contains("ALWAYS START DRAWLINE IN A NEW LINE:YES"), "CompatiDRAWLINE 永不写出（ConfigData.cs:433 第一个条件）")
        assertTrue(merged.contains("CHANGE MASTER MAME IN DEBUG:NO"), "为 true 时不写出，原行不动（第二个条件）")
        assertTrue(merged.contains("LATEST IDENTIFY CODE:123"), "LastKey 为 0 时不写出，原行不动（第三个条件）")
        assertEquals(1, countPrefix(merged, "ALWAYS START DRAWLINE IN A NEW LINE"), "被跳过的键不得再追加一份")
        assertEquals(1, countPrefix(merged, "CHANGE MASTER MAME IN DEBUG"), "被跳过的键不得再追加一份")
        assertEquals(1, countPrefix(merged, "LATEST IDENTIFY CODE"), "被跳过的键不得再追加一份")
    }

    @Test
    fun `尾随空格的英文标签行算已存在并原位更新`() {
        val original = "CLIPBOARD- ANYKEY WAIT TRIGGER :NO\n"
        val store = storeWith(ConfigKey.CBTriggerAnyKeyWait to ConfigValue.Bool(true))
        val merged = EmueraConfigWriter.mergeInto(original, store, english = true)
        assertTrue(
            merged.startsWith("CLIPBOARD- ANYKEY WAIT TRIGGER :YES\n"),
            "标签尾随空格（ConfigData.cs:140）在读侧被 Trim 掉、永远匹配不上（:539），写侧必须认它，否则会重复追加",
        )
        assertEquals(1, countPrefix(merged, "CLIPBOARD- ANYKEY WAIT TRIGGER"), "原位置换，不追加第二份")
    }

    @Test
    fun `空原文生成整份配置且每行带行尾`() {
        val store = ConfigStore()
        val merged = EmueraConfigWriter.mergeInto("", store, english = true)
        assertEquals(
            EmueraConfigWriter.gameLines(store, english = true).joinToString("\n") + "\n",
            merged,
            "无原文可循时按 SaveConfig 的 WriteLine 口径（每行含末行都带行尾），也是首次建文件的路径",
        )
    }

    @Test
    fun `真实语料 emuera-config 未修改时合并结果与原文逐字符一致`() {
        val gameDir = locateGameDir()
        if (gameDir == null) {
            println("跳过 ConfigPreserveTest 的语料判据：未设置 ERATW_GAMEDIR / ERATW_CORPUS")
            return
        }
        val file = File(gameDir, "emuera.config")
        if (!file.isFile) {
            println("跳过 ConfigPreserveTest 的语料判据：$file 不存在")
            return
        }
        val bytes = file.readBytes()
        assertTrue(
            bytes.size > 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte(),
            "语料配置带 UTF-8 BOM（EncodingHandler.cs:23-25）：$file",
        )
        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        assertFalse(text.startsWith("\uFEFF"), "剥 BOM 后首字符不应还是 U+FEFF")
        val lines = text.split("\n").let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }

        val store = ConfigStore()
        val result = EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", lines), store)
        assertEquals(97, result.records.size, "语料 97 行，全部落记录")
        assertEquals(0, result.warnings.size, "语料没有抛错行，合并前 store 是干净的")
        val english = EffectiveConfig.of(store).englishConfigOutput
        assertTrue(english, "语料是英文标签文件（OUTPUT ENGLISH ITEMS:YES）")

        val merged = EmueraConfigWriter.mergeInto(text, store, english)
        assertEquals(text, merged, "store 未修改时保留式合并必须逐字符还原原文（含行序、行尾、尾随空格标签）")
        println("ConfigPreserve：语料 ${lines.size} 行 / 合并后逐字符一致 —— $file")
    }

    private fun storeWith(vararg pairs: Pair<ConfigKey, ConfigValue>): ConfigStore {
        val store = ConfigStore()
        for ((key, value) in pairs) {
            assertTrue(store.itemOf(key).assign(value), "$key 应当可写（未被 fixed 钉住）")
        }
        return store
    }

    private fun countPrefix(text: String, prefix: String): Int =
        text.split("\n").count { it.startsWith(prefix) }

    /**
     * 游戏目录（`emuera.config` 所在目录）：`ERATW_GAMEDIR` 优先（显式给出即权威）；
     * 否则从 `ERATW_CORPUS`（指向 `<游戏目录>/ERB`）的父目录推；都没有就跳过。
     */
    private fun locateGameDir(): File? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { env ->
            val dir = File(env)
            if (File(dir, "emuera.config").isFile) {
                return dir
            }
        }
        System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }?.let { env ->
            val parent = File(env).parentFile
            if (parent != null && File(parent, "emuera.config").isFile) {
                return parent
            }
        }
        return null
    }
}
