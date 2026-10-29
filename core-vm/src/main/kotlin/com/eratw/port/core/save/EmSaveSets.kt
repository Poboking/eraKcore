package com.eratw.port.core.save

import com.eratw.port.core.fn.EmDataNames
import java.nio.file.Files
import java.nio.file.Path

/**
 * 存档扩展段（Map/Xml/DT）的**名字表**：`VarExt*.csv` 的九条指令按存档种类分组。
 *
 * 原版把九个 `HashSet` 放在 `ConstantData`（`ConstantData.cs:132-156`，实读：`:132` GlobalSaveMaps、
 * `:135` SaveMaps、`:138` GlobalSaveXmls、`:141` SaveXmls、`:144` GlobalSaveDTs、`:147` SaveDTs、
 * `:150/153/156` StaticMaps/StaticXmls/StaticDTs），由
 * `loadGlobalVarExSetting` 从 **`Directory.GetFiles(csvPath, "VarExt*.csv", AllDirectories)`**
 * 逐行读入（`:1309-1408`）：
 * - `SAVE_MAPS` / `SAVE_XMLS` / `SAVE_DTS` → 普通存档（`VariableData.cs:3936-3959`）；
 * - `GLOBAL_MAPS` / `GLOBAL_XMLS` / `GLOBAL_DTS` → 全局存档（`:3911-3934`）；
 * - `STATIC_MAPS` / `STATIC_XMLS` / `STATIC_DTS` → **既不写档也不读档**，只在 RESETGLOBAL 清（见下）。
 *
 * 读档时的接受集是前两者的并集（`VariableData.cs:4099/4109/4119`：`Save* || Global*`），
 * 故 [FileSystemOps] 装载时传 `save.union(global)`。
 *
 * ## 解析口径（逐条照 `ConstantData.cs:1334-1408` + 行读取侧 `EraStreamReader`）
 *
 * - 行切分 = `File.ReadAllLines`（EraStreamReader.cs:51）：`\r\n`、`\r`、`\n` 三种行尾都算，
 *   所以纯 CR（老 Mac 导出）的 CSV 也是逐行读；
 * - 只认 `ReadEnabledLine` 的行（EraStreamReader.cs:96-129）：先按
 *   `LexicalAnalyzer.SkipWhiteSpace`（LexicalAnalyzer.cs:647-686）跳过行首的 `' '`/`\t`，
 *   遇 `;` 即整行作废（`:668-672` 的 `st.Seek(0, SeekOrigin.End)`），全空白行跳过；
 *   `;!;` / `;^;` 三个字符是被**跳过的标记**而非注释（`:664-670`），其后内容照读；
 *   `;#;` 只在 `Program.DebugMode` 下同等待遇（`:662-663`），默认（非调试）按注释处理；
 *   全角空格 U+3000 **不跳**（`:676-680` 受 `Config.SystemAllowFullSpace` 门，eratw 的
 *   emuera.config 是 `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`），故行首全角空格会让该行落空；
 * - `body.Split(',')`，`array[0]` **不 trim**、只按 [ignoreCase] 比较指令名（
 *   `array[0].Equals("SAVE_MAPS", Config.StringComparison)`，ConstantData.cs:1353 等九处；
 *   `Config.StringComparison` 由 `IgnoreCase` 决定，`Config.cs:299-307`，而 `IgnoreCase`
 *   的出厂默认是 true（`ConfigData.cs:45` `t: true`）且 eratw 的 emuera.config 明写
 *   `IGNORE CASE:YES` ⇒ 本层 [ignoreCase] 缺省 true。`SAVE_DTS ` （带尾空格）这类
 *   「指令名后有空格」的行两侧都不认；
 * - 后段逐个 `Trim()`（ConstantData.cs:1350 等九处），**空串照样入表**（原版
 *   `HashSet.Add("")`）——写侧 `DataStringMaps.ContainsKey("")` 恒假故不可观测，
 *   但读侧接受集里确实有这一条，故不复刻成过滤；
 * - `array.Length < 2`（无逗号）与 `array[0].Length == 0` 的行整体忽略（原版只 Warn，
 *   不中断，ConstantData.cs:1338/1342）；
 * - 同一条指令可重复出现（`HashSet.Add` 幂等）；名字表本身**大小写敏感**
 *   （`new HashSet<string>()` 用默认 Ordinal 比较器，ConstantData.cs:132-156，
 *   不受 `Config.StringComparison` 影响）⇒ `dt_x` 与 `DT_X` 是两条。
 *
 * ## 三口径（原版第 7–9 条指令：`STATIC_*`）
 *
 * `ConstantData.cs:1388-1408` 还有三条 `STATIC_XMLS` / `STATIC_MAPS` / `STATIC_DTS`，
 * 落到 `ConstantData.cs:150/153/156` 的 `StaticMaps`/`StaticXmls`/`StaticDTs` 三个 `HashSet`。
 * 这三张表**只出现在复位口径里**（`VariableData.cs:4056-4079` 的 `RemoveEMStaticData`，
 * 由 `VariableEvaluator.cs:1351` 的 `ResetGlobalData` 调用）：
 * - **存盘侧没有** `SaveStaticEMDataToStreamBinary`（全仓 grep 只找到 `SaveEMDataToStreamBinary`
 *   `:3936` 与 `SaveGlobalEMDataToStreamBinary` `:3911` 两支）⇒ static 容器**永不写档**；
 * - **装载侧也不接受**：`LoadVariableBinary` 的 Map/Xml/DT 三支只判 `Save* || Global*`
 *   （`:4099` / `:4109` / `:4119`，实读无 `Static*` 支）⇒ static 名字**读不回来**。
 *
 * 故 [staticSets] 只能用于 RESETGLOBAL 的清空，**绝不能**并进 [loadAccept]，也不参与
 * [SaveCapture] 的抓取（并进去会凭空多出存档段、破逐字节幂等）。
 *
 * ## 与原版的一处**已登记**差异（不硬造实现）
 *
 * `ConstantData.cs:1311-1316` 只清 6 张表里的 4 张 Xml/Map + `StaticMaps`/`StaticXmls`，
 * **三张 `*DTs` 都不清**（`:1311-1316` 无 `SaveDTs.Clear()`/`GlobalSaveDTs.Clear()`/`StaticDTs.Clear()`）：
 * 原版重复调用 `loadGlobalVarExSetting` 时 DT 名单会累积。移植侧 [parse] 每次返回全新实例、
 * 天然不累积。本语料只调用一次（开局装载），两侧读数相同 ⇒ 差异不可观测，仅登记。
 *
 * 目录里没有 `VarExt*.csv` 时九张表都是空 = 不写/不读扩展段（原版同样清空六表）。
 */
data class EmSaveSets(
    val save: EmDataNames = EmDataNames.EMPTY,
    val global: EmDataNames = EmDataNames.EMPTY,
    /**
     * `STATIC_MAPS`/`STATIC_XMLS`/`STATIC_DTS` 三张名单（`ConstantData.cs:1388-1408`）。
     *
     * 唯一消费者是 RESETGLOBAL 的清空步（`VariableData.cs:4056-4079`）：
     * `EmDataPools.clear(store, em.staticSets)`。既不进 [loadAccept] 也不进抓取侧。
     */
    val staticSets: EmDataNames = EmDataNames.EMPTY,
) {

    /** 装载接受集（`Save* ∪ Global*`，`VariableData.cs:4099`；`staticSets` **不在**此集，见类 KDoc）。 */
    val loadAccept: EmDataNames get() = save.union(global)

    companion object {

        val EMPTY: EmSaveSets = EmSaveSets()

        /**
         * 解析 `<csvDir>/VarExt*.csv`（递归）。
         *
         * 文件顺序按名字排序，只为让读档条目顺序确定（原版 `GetFiles` 是文件系统序，
         * 而**顺序不可观测**：读回按条目名整表替换，与先后无关）。
         *
         * 编码：九条指令名都是 ASCII，故按 UTF-8 解码（BOM 剥掉）即可；
         * 非 ASCII 字节即便解成替换字符也不影响指令匹配。CSV 内容本身不参与语义。
         * （原版用 `EncodingHandler.DetectEncoding` 探测，EraStreamReader.cs:51；
         * 指令名两侧的差别只在「非 UTF-8 CSV 里的非 ASCII 名字」这种本就不参与匹配的场合。）
         *
         * @param ignoreCase 指令名是否忽略大小写 = 原版 `Config.StringComparison`
         *   （由 `IgnoreCase` 决定，Config.cs:299-307；出厂默认 true，ConfigData.cs:45，
         *   eratw 的 emuera.config 明写 `IGNORE CASE:YES`）。宿主装配时应把 `ConfigStore`
         *   的 `IgnoreCase` 现值传进来（`GameSession` 的调用点目前用缺省值）。
         *
         * 注意这个不敏感**只作用于指令名**：名单里的容器名走 `HashSet.Add`/`Contains`，
         * 是 .NET 默认序数比较（`ConstantData.cs:132-156` 声明为 `HashSet<string>`，
         * `VariableData.cs:4099` 的 `SaveMaps.Contains(text)` 同理）⇒ 移植侧名字表按
         * 原样字符串保存、比较时区分大小写，与本实现一致。
         *
         * 近似登记：Kotlin 的 `equals(ignoreCase = true)` 走 Unicode 大小写折叠，与 .NET
         * `OrdinalIgnoreCase`（ASCII ToUpperInvariant 口径）在非 ASCII 指令名上可能不同；
         * 九条指令名全为 ASCII ⇒ 本语料无差异可观测。TODO(verify)：非 ASCII 情形未验证。
         */
        fun parse(csvDir: Path, ignoreCase: Boolean = true): EmSaveSets {
            if (!Files.isDirectory(csvDir)) return EMPTY
            val files = try {
                Files.walk(csvDir).use { s ->
                    s.filter { Files.isRegularFile(it) && matchesPattern(it) }
                        .sorted()
                        .collect(java.util.stream.Collectors.toList())
                }
            } catch (_: Exception) {
                return EMPTY
            }
            val saveMaps = LinkedHashSet<String>()
            val saveXmls = LinkedHashSet<String>()
            val saveDts = LinkedHashSet<String>()
            val globalMaps = LinkedHashSet<String>()
            val globalXmls = LinkedHashSet<String>()
            val globalDts = LinkedHashSet<String>()
            val staticMaps = LinkedHashSet<String>()
            val staticXmls = LinkedHashSet<String>()
            val staticDts = LinkedHashSet<String>()
            for (f in files) {
                val text = try {
                    String(Files.readAllBytes(f), Charsets.UTF_8).removePrefix("\uFEFF")
                } catch (_: Exception) {
                    continue
                }
                for (raw in splitLines(text)) {
                    val body = lineBody(raw) ?: continue
                    val parts = body.split(',')
                    if (parts.size < 2) continue
                    val directive = parts[0]
                    if (directive.isEmpty()) continue
                    val names = parts.drop(1).map { it.trim() }
                    when {
                        directive.equals("SAVE_MAPS", ignoreCase) -> saveMaps.addAll(names)
                        directive.equals("SAVE_XMLS", ignoreCase) -> saveXmls.addAll(names)
                        directive.equals("SAVE_DTS", ignoreCase) -> saveDts.addAll(names)
                        directive.equals("GLOBAL_MAPS", ignoreCase) -> globalMaps.addAll(names)
                        directive.equals("GLOBAL_XMLS", ignoreCase) -> globalXmls.addAll(names)
                        directive.equals("GLOBAL_DTS", ignoreCase) -> globalDts.addAll(names)
                        // :1388-1408（次序照原版：XMLS → MAPS → DTS）；只服务 RESETGLOBAL
                        directive.equals("STATIC_XMLS", ignoreCase) -> staticXmls.addAll(names)
                        directive.equals("STATIC_MAPS", ignoreCase) -> staticMaps.addAll(names)
                        directive.equals("STATIC_DTS", ignoreCase) -> staticDts.addAll(names)
                        else -> {}
                    }
                }
            }
            return EmSaveSets(
                save = EmDataNames(saveMaps.toList(), saveXmls.toList(), saveDts.toList()),
                global = EmDataNames(globalMaps.toList(), globalXmls.toList(), globalDts.toList()),
                staticSets = EmDataNames(staticMaps.toList(), staticXmls.toList(), staticDts.toList()),
            )
        }

        /**
         * `File.ReadAllLines` 的切行：`\r\n` / `\r` / `\n` 三种行尾都算
         * （EraStreamReader.cs:51；`\n\n` 之间的空行由 [lineBody] 判掉）。
         */
        private fun splitLines(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when (c) {
                    '\r' -> {
                        out.add(sb.toString()); sb.setLength(0)
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                    }

                    '\n' -> {
                        out.add(sb.toString()); sb.setLength(0)
                    }

                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }

        /**
         * 行首空白/注释跳过后的正文（`LexicalAnalyzer.SkipWhiteSpace`，LexicalAnalyzer.cs:647-686
         * 的语义；`EraStreamReader.ReadEnabledLine` :123-128 就是先跑它再判 EOS）。
         *
         * @return null = 整行作废（全空白行，或以 `;` 注释收尾后没有正文）
         */
        private fun lineBody(line: String): String? {
            var i = 0
            while (true) {
                if (i >= line.length) return null
                val c = line[i]
                if (c == ' ' || c == '\t') { i++; continue }
                if (c.code <= 32) return line.substring(i)
                if (c == ';') {
                    val rest = line.substring(i)
                    if (rest.startsWith(";!;") || rest.startsWith(";^;")) { i += 3; continue }
                    return null
                }
                return line.substring(i)
            }
        }

        /** `Directory.GetFiles(csvPath, "VarExt*.csv")`：文件名前缀 + 后缀，大小写不敏感（Windows 口径）。 */
        private fun matchesPattern(p: Path): Boolean {
            val n = p.fileName?.toString() ?: return false
            return n.startsWith("VarExt", ignoreCase = true) && n.endsWith(".csv", ignoreCase = true)
        }
    }
}
