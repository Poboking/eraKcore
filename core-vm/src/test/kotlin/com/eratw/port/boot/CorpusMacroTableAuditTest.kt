package com.eratw.port.boot

import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `n-macrowire` —— `.ERH` 宏表的两条消费面与真实语料命中审计。
 *
 * ## 原版事实（C# 行号）
 *
 * 宏表**只有一个装载点、两条消费面**：
 * 1. 装载：`ErhLoader.cs:109` 认 `#DEFINE` → `:146 analyzeSharpDefine` →
 *    `:170`/`:246` 两个 `idDic.AddMacro(mac)`（全仓 `AddMacro` 仅这三处出现，
 *    `IdentifierDictionary.cs:401` 是定义）。宏名先经 `IdentifierDictionary.cs:299-340`
 *    `CheckUserMacroName` 查重（与保留字/系统方法/系统变量/既有宏/用户全局变量同名 ⇒ warnLevel 2 拒收）。
 *    装载时点：`Process.cs:307` 建 `ErhLoader`、`Process.cs:310` 把 `LexicalAnalyzer.UseMacro`
 *    置 false（**ERH 期不展开宏**）、`:313 LoadHeaderFiles`、`:319 UseMacro = idDic.UseMacro()`
 *    （`IdentifierDictionary.cs:414-416`：宏表非空才开），随后 `:321` 起才 `ErbLoader` 装 ERB。
 * 2. 消费面 A —— **读取/预处理期**（只看宏名是否存在，不看宏体）：
 *    `ErbLoader.cs:94`（`Disabled = GetMacro(token2) == null`）与 `:109`（ELSEIF 同式）。
 *    移植侧对应 `LogicalLineReader` 的可选参 `macroNames`
 *    （`core/lexer/LogicalLineReader.kt:160`/`:177`，判定式 `:130`）。
 * 3. 消费面 B —— **词法/编译期展开**（用宏体替换标识符）：
 *    `LexicalAnalyzer.cs:704 Analyse` → `:729-731` 在 `UseMacro` 时 `expandMacro(ret)`；
 *    展开器 `:942-975`（无参宏 `wc.Remove()`+`wc.InsertRange(macro.Statement)`，带参宏
 *    `expandFunctionlikeMacro`，上限 100 次超限抛 `CodeEE`）；标识符级递归在 `:283-302`。
 *    移植侧**没有**这一面：`core/lexer/LexicalAnalyzer.kt:7` 明写「宏展开（UseMacro / #DEFINE）
 *    需要全局 IdentifierDictionary，不在 M1 范围」，全仓 `expandMacro`/`UseMacro` 零命中。
 *
 * ## 本审计的结论（见各用例）
 *
 * * 面 A 对本语料**零命中**：语料全部 `[IF 参数]`/`[ELSEIF 参数]` 的实参只有 `DEBUG` 与 `NDEBUG`
 *   （共 41 行），二者都不是 22 条 `#DEFINE` 宏名 ⇒ 装置把宏表传进 `LogicalLineReader`
 *   的 `macroNames` 与不传**逐行同值**，不构成行为差 ⇒ 按契约 2.6 条件 ① 不动装置。
 * * 面 B 对本语料**有实质命中**（22 条宏里 20 条出现在语句/表达式区，合计 142 行），
 *   但面 B 不是「把已有宏表传进已有可选参数」能接的（要新造词法期展开器）⇒ 只登记，见交付报告。
 *
 * ## 22 条宏的代码区命中数（口径见 [CorpusMacroAudit]，独立反推脚本交叉验证过）
 *
 * | 宏名 | 声明处 | 代码区命中行 |
 * |---|---|---|
 * | DEF_CULC_RELATION_NULL | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:45 | 2 |
 * | DEF_COLUMN_IS_STR | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:46 | 4 |
 * | DEF_SORT_FOR_14 | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:47 | 4 |
 * | DEF_LIST_BOOLTRUE | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:57 | 2 |
 * | DEF_LIST_BOOLFALSE | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:58 | 2 |
 * | DEF_LIST_MARKPOSITIVE | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:59 | 2 |
 * | DEF_LIST_MARKNEGATIVE | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:60 | 2 |
 * | DEF_LIST_MARKNEUTRAL | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:61 | 2 |
 * | DEF_LIST_MARKHEART | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:63 | 2 |
 * | DEF_DT_KOJOEXIST | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:71 | 1 |
 * | DEF_DT_ADDNO | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:74 | 7 |
 * | DEF_DT_CID | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:75 | 9 |
 * | DEF_DT_NAME | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:76 | 3 |
 * | DEF_DT_CNAME | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:77 | 2 |
 * | 時間帯 | DIM.ERH:181 | 1 |
 * | 天候 | DIM.ERH:183 | 81 |
 * | 拠点_大家 | DIM_als.ERH:8 | 0（结案：无影响） |
 * | DT_VAGINA | fromEN/VARSET.ERH:4 | 2 |
 * | DT_ANUS | fromEN/VARSET.ERH:5 | 2 |
 * | キャラ画像表示サイズ比 | ステータス表示関連/キャラ描画共通.ERH:4 | 2 |
 * | デフォルトキャラ画像横幅 | ステータス表示関連/キャラ描画共通.ERH:5 | 10 |
 * | 各キャラ画像サイズ | ステータス表示関連/キャラ描画共通.ERH:6 | 0（结案：无影响） |
 *
 * 语料不可改动；本用例只读，未设 `ERATW_CORPUS` 且自动发现失败时优雅跳过（与 [RealCorpus] 同口径）。
 */
object CorpusMacroAudit {

    /** 语料里 22 条 `#DEFINE` 宏名（大写归一）。原版大小写不敏感：`GetMacro` 用 OrdinalIgnoreCase
     * （`IdentifierDictionary.cs:419-427` + `Config.cs` 的 `StrComper`），移植侧同样按 `uppercase` 归一。 */
    val expectedMacroNames: Set<String> = setOf(
        "DEF_CULC_RELATION_NULL", "DEF_COLUMN_IS_STR", "DEF_SORT_FOR_14",
        "DEF_LIST_BOOLTRUE", "DEF_LIST_BOOLFALSE", "DEF_LIST_MARKPOSITIVE",
        "DEF_LIST_MARKNEGATIVE", "DEF_LIST_MARKNEUTRAL", "DEF_LIST_MARKHEART",
        "DEF_DT_KOJOEXIST", "DEF_DT_ADDNO", "DEF_DT_CID", "DEF_DT_NAME", "DEF_DT_CNAME",
        "時間帯", "天候", "拠点_大家", "DT_VAGINA", "DT_ANUS",
        "キャラ画像表示サイズ比", "デフォルトキャラ画像横幅", "各キャラ画像サイズ",
    )

    /** 宏名 → 代码区命中行数（钉桩值 = 本审计的量化读数）。 */
    val expectedCodeRegionHitLines: Map<String, Int> = linkedMapOf(
        "DEF_CULC_RELATION_NULL" to 2, "DEF_COLUMN_IS_STR" to 4, "DEF_SORT_FOR_14" to 4,
        "DEF_LIST_BOOLTRUE" to 2, "DEF_LIST_BOOLFALSE" to 2, "DEF_LIST_MARKPOSITIVE" to 2,
        "DEF_LIST_MARKNEGATIVE" to 2, "DEF_LIST_MARKNEUTRAL" to 2, "DEF_LIST_MARKHEART" to 2,
        "DEF_DT_KOJOEXIST" to 1, "DEF_DT_ADDNO" to 7, "DEF_DT_CID" to 9,
        "DEF_DT_NAME" to 3, "DEF_DT_CNAME" to 2,
        "時間帯" to 1, "天候" to 81, "拠点_大家" to 0,
        "DT_VAGINA" to 2, "DT_ANUS" to 2,
        "キャラ画像表示サイズ比" to 2, "デフォルトキャラ画像横幅" to 10, "各キャラ画像サイズ" to 0,
    )

    /** 语料 `[IF 参数]`/`[ELSEIF 参数]` 出现过的实参（大写归一）。 */
    val corpusPreprocessIfArgs: Set<String> = setOf("DEBUG", "NDEBUG")

    private val DEFINE_RE = Regex("(?i)^#DEFINE\\s+([^\\s()\\[\\];,]+)")
    private val IF_RE = Regex("(?i)^\\[(?:ELSE)?IF[ \\t]+([^\\]\\s]+)")
    private val SHARP_DIM_RE = Regex("(?i)^#DIMS?\\s+([^=;]+)")
    /** 「标识符相邻」字符集：拉丁词字符 + CJK/假名 + 全角（原版读标识符靠 `LexicalAnalyzer.ReadSingleIdentifier`）。 */
    private const val ID_CHARS = "A-Za-z0-9_\u3000-\u30ff\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef"

    private fun upper(s: String): String = s.uppercase(Locale.ROOT)

    /** 解码语料文件为物理行（复用生产解码口径，避免第二真源）。 */
    fun physicalLines(path: Path): List<String> =
        runCatching { LogicalLineReader.readAllLines(Files.readAllBytes(path)) }.getOrDefault(emptyList())

    /** 面 A：`#DEFINE` 宏名集合（只扫 `.ERH` —— 原版 ERB 侧没有 `#DEFINE` 分支）。 */
    fun macroNames(files: List<Path>): Set<String> {
        val names = LinkedHashSet<String>()
        for (f in files) {
            if (!f.name.endsWith(".erh", ignoreCase = true)) continue
            for (raw in physicalLines(f)) {
                DEFINE_RE.find(raw.trim())?.let { names.add(upper(it.groupValues[1])) }
            }
        }
        return names
    }

    /** 面 A：`[IF 参数]` 落在宏名上的行（`file:行号` 列表）。整行注释跳过。 */
    fun ifLinesHittingMacroNames(files: List<Path>, macros: Set<String>): List<String> {
        val hits = ArrayList<String>()
        for (f in files) {
            val lines = physicalLines(f)
            for ((i, raw) in lines.withIndex()) {
                val t = raw.trim()
                if (t.isEmpty() || t.startsWith(";") || t.startsWith("[").not()) continue
                IF_RE.find(t)?.let { m ->
                    val arg = upper(m.groupValues[1])
                    if (arg in macros) hits.add("${f.name}:${i + 1}: ${t.take(80)}")
                }
            }
        }
        return hits
    }

    /** 面 A：语料里出现过的全部 `[IF 参数]` 实参（大写归一），供逐条核对。 */
    fun allIfArgs(files: List<Path>): Set<String> {
        val args = LinkedHashSet<String>()
        for (f in files) {
            for (raw in physicalLines(f)) {
                val t = raw.trim()
                if (!t.startsWith("[") || t.startsWith(";")) continue
                IF_RE.find(t)?.let { args.add(upper(it.groupValues[1])) }
            }
        }
        return args
    }

    /**
     * 面 B：宏名出现在**会被词法/编译期读到**的位置的行（`file:行号`，`#DEFINE` 声明行自身除外）。
     * 单遍扫描全部语料文件，一次判完所有宏名（避免逐个宏名重读 3,971 个文件）。
     *
     * 排除口径（近似词法，`TODO(verify)`：`@"..."` 逐字串内的分号、`%...%` 格式化区间等
     * 未完全按原版 `LexicalAnalyzer` 处理，只影响命中数读数，不影响面 A 结论）：
     * 1. 整行注释（首个非空白字符为 `;`）；
     * 2. 双引号计数为奇数的位置 ⇒ 落在字符串字面量内；
     * 3. 位于「引号外分号」之后 ⇒ 落在行尾注释内；
     * 4. 与相邻的拉丁词字符 / CJK / 全角字符相接 ⇒ 不是独立标识符（例如
     *    `デフォルトキャラ画像横幅退避` 不算 `デフォルトキャラ画像横幅` 的命中）。
     *
     * 大小写：按字面匹配。原版 `GetMacro` 忽略大小写（`IdentifierDictionary.cs:419-427`），
     * 已用独立脚本逐条核对过 16 条 ASCII 宏名在忽略大小写口径下命中数**完全相同**
     * （CJK 名无大小写之分），故钉桩不受该口径影响。
     */
    fun codeRegionHits(files: List<Path>, names: Collection<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, ArrayList<String>>()
        names.forEach { out[it] = ArrayList() }
        val pats = names.associateWith { n -> Regex("(?<![$ID_CHARS])${Regex.escape(n)}(?![$ID_CHARS])") }
        for (f in files) {
            val lines = physicalLines(f)
            for ((i, line) in lines.withIndex()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (t.startsWith(";")) continue
                // `#DEFINE 名 宏体` 声明行本身不算命中（原版宏名注册前不参与展开）
                if (t.length >= 7 && t.substring(0, 7).equals("#DEFINE", ignoreCase = true)) continue
                val semi = firstQuoteOutsideSemicolon(line)
                // 预筛：绝大多数行连宏名的字面量都不含，避免 22 台正则逐行扫描（语料 3,971 文件）
                val candidates = names.filter { line.contains(it) }
                if (candidates.isEmpty()) continue
                for (n in candidates) {
                    val kept = pats.getValue(n).findAll(line).map { it.range.first }.filter { p ->
                        if (line.substring(0, p).count { it == '"' } % 2 == 1) return@filter false
                        if (semi >= 0 && p >= semi) return@filter false
                        true
                    }.toList()
                    if (kept.isNotEmpty()) out.getValue(n).add("${f.name}:${i + 1}")
                }
            }
        }
        return out
    }

    /** 第一个「不在双引号串内」的分号下标；无则 -1。 */
    private fun firstQuoteOutsideSemicolon(line: String): Int {
        var inString = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inString = !inString
                ';' -> if (!inString) return i
            }
        }
        return -1
    }

    /** `#DIM` 声明行里出现的名字 token 集合（大写归一；别名宏宏体的存在性核查用）。 */
    fun dimDeclaredNames(files: List<Path>): Set<String> {
        val names = LinkedHashSet<String>()
        for (f in files) {
            for (raw in physicalLines(f)) {
                val t = raw.trim()
                if (!t.startsWith("#D")) continue
                val m = SHARP_DIM_RE.find(t) ?: continue
                m.groupValues[1].split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.forEach { names.add(upper(it)) }
            }
        }
        return names
    }
}

class CorpusMacroTableAuditTest {

    /** 语料可用时返回清单，否则返回 null（用例优雅跳过，与 corpusSmoke 的可选口径一致）。 */
    private fun corpusFiles(): List<Path>? {
        val c = RealCorpus.load() ?: run {
            println("跳过：未找到真实语料（设置 ERATW_CORPUS）")
            return null
        }
        return c.files
    }

    @Test
    fun `语料宏表恰为 22 条 DEFINE 且与审计钉桩同名`() {
        val files = corpusFiles() ?: return
        val macros = CorpusMacroAudit.macroNames(files)
        assertEquals(
            CorpusMacroAudit.expectedMacroNames.sorted(),
            macros.sorted(),
            "语料 ERH 的 #DEFINE 宏名集合与钉桩不一致",
        )
    }

    @Test
    fun `面A 宏名零命中于 IF 预处理行 —— 装置传宏表是零行为差`() {
        val files = corpusFiles() ?: return
        val macros = CorpusMacroAudit.macroNames(files)
        val args = CorpusMacroAudit.allIfArgs(files)
        // 反向证明：若扫描器漏掉实参，下面的实参集合断言就会红；若语料真有 [IF 宏名]，hits 断言会红。
        assertEquals(CorpusMacroAudit.corpusPreprocessIfArgs.sorted(), args.sorted(), "语料 [IF]/[ELSEIF] 实参集合已变")
        val hits = CorpusMacroAudit.ifLinesHittingMacroNames(files, macros)
        assertTrue(hits.isEmpty(), "宏名出现在 [IF] 位置（面 A 需接线）：\n" + hits.joinToString("\n"))
    }

    @Test
    fun `面B 逐条命中数钉桩 —— 20 条宏落在代码区共 142 行`() {
        val files = corpusFiles() ?: return
        val hits = CorpusMacroAudit.codeRegionHits(files, CorpusMacroAudit.expectedMacroNames.toList())
        val actual = LinkedHashMap<String, Int>()
        val detail = StringBuilder()
        for (n in CorpusMacroAudit.expectedMacroNames) {
            val l = hits.getValue(n)
            actual[n] = l.size
            if (l.isNotEmpty()) detail.append(n).append(" → ").append(l.take(3).joinToString(", ")).append('\n')
        }
        println("宏名代码区命中明细（前 3 处/宏）：\n$detail")
        assertEquals(CorpusMacroAudit.expectedCodeRegionHitLines, actual, "宏名代码区命中行数与钉桩不一致")
        assertEquals(142, actual.values.sum(), "代码区命中总行数")
    }

    @Test
    fun `日文别名宏的宏体确实是 DIM 声明的变量`() {
        val files = corpusFiles() ?: return
        val dims = CorpusMacroAudit.dimDeclaredNames(files)
        // `#DEFINE 日文名 中文名` 是 eraTW 的重命名别名写法：展开后落到中文名变量上。
        for (body in listOf("默认角色画像横幅", "角色画像表示尺寸比", "各角色画像尺寸", "拠点_家主")) {
            assertTrue(body.uppercase(Locale.ROOT) in dims, "别名宏宏体 $body 无 #DIM 声明（宏体表口径需复核）")
        }
    }

    @Test
    fun `读取期差值反向证明 —— IF 实参是宏名时接与不接结果不同`() {
        val lines = listOf("[IF 天候]", "PRINTL A", "[ENDIF]")
        val off = LogicalLineReader(lines, "t.erb").let { r -> readAll(r) }
        val on = LogicalLineReader(lines, "t.erb", macroNames = setOf("天候")).let { r -> readAll(r) }
        assertTrue(off.isEmpty(), "宏表未传时 [IF 宏] 分支应整块禁用")
        assertEquals(listOf("PRINTL A"), on, "宏表传入后 [IF 宏] 分支应启用")
    }

    @Test
    fun `语料实际 IF 实参 DEBUG NDEBUG 接与不接同值`() {
        // 语料只有这两种实参；DEBUG/NDEBUG 都不是宏名 ⇒ 与 macroNames 取值无关。
        val macros = CorpusMacroAudit.expectedMacroNames
        for (arg in listOf("DEBUG", "NDEBUG")) {
            val lines = listOf("[IF $arg]", "PRINTL A", "[ELSE]", "PRINTL B", "[ENDIF]")
            val off = readAll(LogicalLineReader(lines, "t.erb"))
            val on = readAll(LogicalLineReader(lines, "t.erb", macroNames = macros))
            assertEquals(off, on, "[IF $arg] 传入宏表前后读取结果不同（该实参不该是宏名）")
            assertEquals(listOf("PRINTL B"), off, "[IF $arg] 未按原版口径禁用命中分支")
        }
    }

    private fun readAll(reader: LogicalLineReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val l = try {
                reader.readLogicalLine() ?: break
            } catch (e: Exception) {
                continue
            }
            out.add(l.content)
        }
        return out
    }
}
