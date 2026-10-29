package com.eratw.port.erbcc.compile

import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** ERH 装载拓扑问题的分类。 */
enum class ErhIssueKind {
    /** `#INCLUDE` / `#INCLUDEFROM` 行 —— PC 没有这个指令（见 [ErhLoadAudit] KDoc）。 */
    INCLUDE_DIRECTIVE,

    /** 跨文件 `#DEFINE` 重名（= 「同一个 ERH 被装两遍」在 PC 上的真实症状）。 */
    DUPLICATE_MACRO,

    /** 跨文件全局 `#DIM`/`#DIMS` 重名。 */
    DUPLICATE_GLOBAL_DIM,

    /** 同一份 ERH 在装载清单里出现两次（同一物理文件被列两遍）。 */
    DUPLICATE_FILE_IDENTITY,

    /** ERH 读不出来（无法参与装载拓扑判定）。 */
    UNREADABLE,
}

/** 一条 ERH 装载拓扑问题（[file] 为调用方给的路径串，[line] 为 1 基物理行号）。 */
data class ErhIssue(val kind: ErhIssueKind, val file: String, val line: Int, val message: String) {
    override fun toString(): String = "$file:$line [$kind] $message"
}

/**
 * ERH 装载拓扑审计：把「PC 没有 `#INCLUDE`」这条事实变成可执行判据。
 *
 * ## 语义结论（先钉死前提，再谈检测）
 *
 * 原版**不存在** `#INCLUDE` / `#INCLUDEFROM` 指令：ERH 由**目录扫描**整体装载
 * （`ErhLoader.cs:42` 的 `Config.GetFiles(headerDir, "*.ERH")` → `Config.cs:644-706`
 * 的递归 + 按名排序遍历），每个文件在清单里只出现一次；ERB 同理（`ErbLoader.cs:216-237`）。
 * 因此：
 *
 * - **重复包含**：不是「PC 跳过还是重复展开」的问题 —— 没有包含动作，只有「目录里有一份」
 *   还是「有两份」。真正的等价症状是**同一份 ERH 被列两遍**（同一物理路径进清单两次），
 *   或把一份 ERH 拷成两个文件：第二次的 `#DEFINE` 撞 `CheckUserMacroName` 的 UserMacro 冲突
 *   → `warnLevel = 2`（`IdentifierDictionary.cs:324-327`）→ `noError = false`
 *   （`ErhLoader.cs:155-164`）→ **整条 ERH 链中断**（`ErhLoader.cs:90-93` 的 `return false`）。
 *   全局 `#DIM` 重名同理：`CheckUserVarName` 命中 UserGlobalVariable → level 2
 *   （`IdentifierDictionary.cs:287-290`）→ `throw CodeEE`（`UserDefinedVariableData.cs:277-289`）
 *   → `Warn(…, 2)` 且 `result = false`（`ErhLoader.cs:328-331`）。
 * - **自包含 / 互包含死循环**：包含图不存在 ⇒ 递归不可表达。本审计**不**去解析、展开或
 *   跟踪任何包含路径 —— 那会凭空造出一套 PC 没有的语义（真出现 `#INCLUDE` 行，PC 的处理是
 *   「未知预处理指令」而不是「递归装载」）。
 * - **相对路径解析基准**：不存在「相对当前文件 / 相对根」的分歧。文件身份 = 打开时的绝对
 *   路径（`ErhLoader.cs:81` 的 `OpenOnCache(filepath, filename)`），清单里的显示名 = 相对
 *   headerDir 的路径（`Config.cs:658-677` 由 `dir.Substring(rootdir.Length)` 拼出）。
 *
 * ## 检测什么
 *
 * 1. `#INCLUDE` / `#INCLUDEFROM` 行 → [ErhIssueKind.INCLUDE_DIRECTIVE]。PC 只认
 *    `DEFINE` / `FUNCTION` / `FUNCTIONS` / `DIM` / `DIMS`（`ErhLoader.cs:109-127`），
 *    其余一律 `CodeEE(UnknownPreprocessorInSharpLine)` → `Warn(…, 2)` → 中止
 *    （`ErhLoader.cs:130-137`）。所以这类行**不是**「被跳过的包含」，而是**整批装载失败点**。
 * 2. 跨文件宏重名 → [ErhIssueKind.DUPLICATE_MACRO]（先到者胜；`Symbols.collectErhSymbols`
 *    的 `putIfAbsent` 合并是同一口径，但合并本身静默，这里把「后到者被弃」显式化）。
 * 3. 跨文件全局变量重名 → [ErhIssueKind.DUPLICATE_GLOBAL_DIM]。
 * 4. 同一物理文件出现两次 → [ErhIssueKind.DUPLICATE_FILE_IDENTITY]。
 *
 * 全部 finding 都是「原版 level 2」语义，但**不改变 erbcc 的成败判定**（与 `dimWarnings`
 * 同口径：`Main.kt` 只打 stderr）。本语料 153 个 ERH：0 条 include、0 条重名。
 */
object ErhLoadAudit {

    /** 与 `CorpusScan` 同一份修饰符表（`UserDefinedVariableData.cs:54-264`）。 */
    private val MODIFIERS = listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

    /**
     * 审计一批 ERH。
     *
     * @param erhFiles 待审计的 ERH 路径，**顺序即装载序**（`ErhLoader.cs:42-62`）。PC 的装载序是
     *   目录遍历序（`Config.cs:678-704`：每层按名排序、递归子目录）；调用方给的是与编译输入同源
     *   的路径字符串序 —— 两者只在「先到者」的归属上可能不同，不影响「是否报重名」。
     */
    fun audit(erhFiles: List<Path>): List<ErhIssue> {
        val issues = ArrayList<ErhIssue>()
        val seenFiles = HashMap<String, String>()          // 物理身份 → 首次出现的位置串
        val macroOwner = HashMap<String, String>()         // 宏名（大写）→ 首次定义位置
        val globalOwner = HashMap<String, String>()        // 变量名（大写）→ 首次声明位置

        for (path in erhFiles) {
            val abs = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            val identity = try {
                path.toRealPath().toString().replace('\\', '/')
            } catch (_: Exception) {
                abs
            }
            val first = seenFiles.putIfAbsent(identity, abs)
            if (first != null) {
                issues += ErhIssue(
                    ErhIssueKind.DUPLICATE_FILE_IDENTITY,
                    abs, 0,
                    "同一份 ERH 进了两次装载清单（首次: $first）—— PC 会装两遍，" +
                        "第二遍的 #DEFINE/#DIM 撞名即 level 2 中止整条 ERH 链（ErhLoader.cs:90-93/155-164）",
                )
                continue
            }

            val lines = try {
                LogicalLineReader.readAllLines(Files.readAllBytes(path))
            } catch (e: Exception) {
                issues += ErhIssue(
                    ErhIssueKind.UNREADABLE,
                    abs, 0,
                    "读取失败: ${e::class.simpleName}: ${e.message}（该文件的宏/全局变量全部缺席）",
                )
                continue
            }

            for ((index, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (!line.startsWith("#")) continue
                val body = line.substring(1).trim()
                val token = body.takeWhile { !it.isWhitespace() }
                val kw = token.uppercase(Locale.ROOT)
                val rest = body.substring(token.length).trim()
                val at = "$abs:${index + 1}"
                when (kw) {
                    "INCLUDE", "INCLUDEFROM" -> issues += ErhIssue(
                        ErhIssueKind.INCLUDE_DIRECTIVE,
                        abs, index + 1,
                        "#$kw 不是 PC 的预处理指令（ErhLoader.cs:109-127 只认 DEFINE/FUNCTION[S]/DIM[S]）" +
                            "→ 原版 CodeEE(UnknownPreprocessorInSharpLine) level 2 并中止整条 ERH 链；" +
                            "ERH 靠目录扫描装载（ErhLoader.cs:42），本行不展开、不解析相对路径",
                    )
                    "DEFINE" -> {
                        val name = rest.takeWhile { !it.isWhitespace() && it != '(' }
                        if (name.isEmpty()) continue
                        val key = name.uppercase(Locale.ROOT)
                        val owner = macroOwner.putIfAbsent(key, at)
                        if (owner != null) {
                            issues += ErhIssue(
                                ErhIssueKind.DUPLICATE_MACRO,
                                abs, index + 1,
                                "#DEFINE $name 与 $owner 重名 —— 原版 CheckUserMacroName 判 UserMacro 冲突 " +
                                    "level 2（IdentifierDictionary.cs:324-327 → ErhLoader.cs:155-164）→ 中止；" +
                                    "erbcc 先到者胜，本条被弃",
                            )
                        }
                    }
                    "DIM", "DIMS" -> {
                        val name = dimName(rest) ?: continue
                        val key = name.uppercase(Locale.ROOT)
                        val owner = globalOwner.putIfAbsent(key, at)
                        if (owner != null) {
                            issues += ErhIssue(
                                ErhIssueKind.DUPLICATE_GLOBAL_DIM,
                                abs, index + 1,
                                "#$kw $name 与 $owner 重名 —— 原版 CheckUserVarName 判 UserGlobalVariable 冲突 " +
                                    "level 2（IdentifierDictionary.cs:287-290 → UserDefinedVariableData.cs:277-289 " +
                                    "→ ErhLoader.cs:328-331）→ 后到者不注册",
                            )
                        }
                    }
                }
            }
        }
        return issues
    }

    /** `#DIM [修饰符…] 名 …` 里的名字（修饰符后可跟任意空白，同 `CorpusScan` 的口径）。 */
    private fun dimName(rest: String): String? {
        var head = rest
        var changed = true
        while (changed) {
            changed = false
            // 原版修饰符比较走 Config.StringComparison（大小写不敏感，UserDefinedVariableData.cs:54-58）
            val upper = head.uppercase(Locale.ROOT)
            for (mod in MODIFIERS) {
                if (upper.length > mod.length && upper.startsWith(mod) && upper[mod.length].isWhitespace()) {
                    head = head.substring(mod.length).trim()
                    changed = true
                    break
                }
            }
        }
        val name = head.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }
        return name.ifEmpty { null }
    }
}
