package com.eratw.port.core.data.csv

import com.eratw.port.core.data.text.DetectedTextDecoder
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.CsvNameTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * CSV 装载通用原语。spec: `ConstantData.loadDataTo`（ConstantData.cs:1747-1839）。
 *
 * 编码口径：原版所有 CSV（29 槽 / VariableSize / .als / CHARA）都经 EraStreamReader.Open 的
 * `File.ReadAllLines(filepath, EncodingHandler.DetectEncoding(path))`（EraStreamReader.cs:52；
 * CHARA 走 OpenOnCache，Preload.readAllLinesDetectEncoding 同一条探测链，Preload.cs:19-46 →
 * Preload.GetFileLines，Preload.cs:14-17）。
 * DetectEncoding（EncodingHandler.cs:15-41）：UTF-8 BOM → 严格 UTF-8 → CP932
 * （GetEncoding(932) 带 ExceptionFallback，EncodingHandler.cs:67-71）。
 * 两条调用点都是 `File.ReadAllLines(path, encoding)`，其 StreamReader 的
 * `detectEncodingFromByteOrderMarks` 缺省为 true ⇒ BOM 由解码器消费（[decodeDetected] 第 1 步
 * 手动跳过前 3 字节即对应这一点）。
 * ⚠️ 这是「画蛇添足版」的定制：官方 1.824 无编码探测、固定 Shift-JIS（specs/verify-notes.md §4），
 * 本移植按**本游戏**反编译源对齐，属超集行为。
 * 实测本语料 CSV/ALS 共 220 个文件**全部是合法 UTF-8**（216 个带 `EF BB BF` BOM、4 个不带，
 * 非 UTF-8 命中 0）⇒ CP932 回退分支在本语料不触发，换游戏时才生效。
 * 全部回退都解不动 = 原版 File.ReadAllLines 抛异常 → Open 返回 false（EraStreamReader.cs:50-59）
 * → 上游 FailedOpenFile 告警并跳过整个文件（ConstantData.cs:1756-1760）。
 * 本装载器由 [readEnabledLines] 的带告警重载承接该语义。
 *
 * 行口径（ReadEnabledLine，EraStreamReader.cs:96-187 + LexicalAnalyzer.SkipWhiteSpace）：
 * - 换行按 `\r\n` / `\r` / `\n` 三种切分（C# `File.ReadAllLines`，EraStreamReader.cs:52）；
 * - 空行 / 纯空白行跳过（SkipWhiteSpace 后 EOS 判定，EraStreamReader.cs:124-128）；
 * - 行首空白集 = `空格` / `\t`（`(uint)c <= 32` 且只放过这两个，LexicalAnalyzer.cs:653-659）
 *   **再加上条件性的 `U+3000`**：:677-683 先 `if (current != '\u3000') break;`
 *   再 `if (!Config.SystemAllowFullSpace) return num;` —— 全角空格进不进空白集**完全由该配置决定**，
 *   配置项即 `ConfigCode.SystemAllowFullSpace`，英文名正是 `Whitespace includes full-width space`
 *   （ConfigData.cs:101）。本游戏 `emuera.config` 实测 `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`
 *   ⇒ 原版**不**剥行首全角空格，故 [stripLeadingWs] 也只剥 `空格` / `\t`。
 *   分叉后果：`　名前,X` 在原版 array[0] = 「　名前」→ 不认标签；纯全角空格行在原版**不是**空行
 *   而是一条数据行（MissingComma 级告警）。⚠️ 实测本语料 220 个 CSV/ALS 中「首个非 空格/tab
 *   的字符是 U+3000」的行 **0 条** ⇒ 两种口径在本语料逐字节同解；配成 YES 或换游戏时要接线。
 * - 剥空白后的行首 `;` → SkipWhiteSpace 里 `st.Seek(0, SeekOrigin.End)` 直达 EOS
 *   （LexicalAnalyzer.cs:662-676），该行被跳过；
 * - 返回给下游的是**剥过前导空白**的行（`CharStream.Substring()` 从 pointer 起截，
 *   CharStream.cs:73-86），行尾空白保持原样。
 * 数据等价性说明：`;` 行在 C# 是被 SkipWhiteSpace 吃掉（不是下游过滤），故这里直接滤掉不产生
 * 告警；原版若走到下游则每行一条 MissingComma 级别告警，**数据结果相同**，仅告警数不同。
 * `[[...]]` 改名只在 `useRename:true` 时生效（loadDataTo/loadAliases/loadCharacterDataFile 都传 false，
 * 故 CSV 家族生产路径永不改名；接口保留该开关供装置与换游戏时对齐原版）。
 * `{`/`}` 续行**不受 useRename 控制**（EraStreamReader.cs:130-184 在 `!disabled` 分支里）：
 * 行首 `{`（整行 trim 后恰为 `{`）把后续物理行拼成一条逻辑行，行首 `}` 单独成行即结束、
 * 带其它文字则 CodeEE；块内再出现单独的 `{` 也 CodeEE；EOF 未闭合 CodeEE。
 * 拼接分隔符 = `Config.ReplaceContinuationBR` 去掉 `"`（EraStreamReader.cs:179；
 * 本项目 `emuera.config:72` 该值为 `" "` ⇒ 单个半角空格）。本语料 220 个文件 / 22,105 行
 * 无任何以 `{`/`}` 开头的行（2026-10-21 实测），故新增分支对既有读数逐字节无影响。
 *
 * 列口径：朴素逗号切分，**无引号/转义**。证据：ConstantData.cs:311 / :1337 / :1452 / :1865 均为
 * `Split(',')`；`loadDataTo` 用的是 Span 版 Split（ConstantData.cs:1774），它写进
 * `stackalloc Range[5]` ⇒ 结果数被截到 5 段，但下游只消费第 0/1/2 段（:1781 / :1802 / :1806），
 * 与"截到 5 段"无关 ⇒ 本装载器不还原该上限。实测本语料全 220 个 CSV/ALS 的 19,763 条启用行
 * （CHARA 家族 168 个文件 / 10,708 行）**0 个双引号字符**：全文只有 3 个文件出现双引号
 * （`CSTR.csv` / `VarExtGameData.csv` / `Chara/Chara10 萃香.csv`），且**逐条都在 `;` 注释行内**
 * ⇒ 已被 [readEnabledLines] 滤掉，没有引号转义形态。
 */
object CsvLoader {

    /**
     * `EncodingHandler.DetectEncoding`（EncodingHandler.cs:15-41）的解码移植（同一口径另见
     * proc 层 BootHosts.decodeWithDetectedEncoding，那里服务 LOADTEXT，这里是 CSV 自己的分支）：
     * 1. 前 3 字节 `EF BB BF` → 跳过 BOM 严格 UTF-8（:23-26，UTF8BOMEncoding throwOnInvalidBytes）；
     * 2. 否则按 StreamReader 的默认 BOM 嗅探（:27 的构造缺省 detectBOM）识别 UTF-16/32 前导符，
     *    命中则按对应编码**宽松**解码（这些编码无异常回退，坏字节替换）；
     * 3. 否则整文件严格 UTF-8（:33-35）；
     * 4. 失败 → 严格 CP932（:37-40）；仍失败 → null（对应原版 ReadAllLines 抛异常 →
     *    Open false → 上游 FailedOpenFile 跳过文件，EraStreamReader.cs:50-59）。
     *
     * 实现放在 [DetectedTextDecoder]：Android 宿主（minSdk 21，无 `java.nio.file`）也要走同一条链。
     */
    private fun decodeDetected(raw: ByteArray): String? = DetectedTextDecoder.decode(raw)

    /** STR 槽号：ConstantData.cs:713-716 对 `j == 10` 跳过 nameToIntDics。 */
    private const val STR_SLOT = 10

    /**
     * 目录下按文件名大小写不敏感解析（Windows 文件系统不敏感；CI(Linux) 下
     * `ABL.CSV` 匹配不到 `Abl.csv`，必须显式扫描目录项，否则 29 槽会大面积空转）。
     * 目录本身不存在 → null。
     */
    fun resolveFile(dir: Path, fileName: String): Path? {
        if (!Files.isDirectory(dir)) return null
        val direct = dir.resolve(fileName)
        if (Files.isRegularFile(direct)) return direct
        val entries = try {
            Files.list(dir).use { s -> s.collect(java.util.stream.Collectors.toList()) }
        } catch (_: java.io.IOException) {
            return null
        }
        val hit = entries.firstOrNull {
            it.fileName.toString().equals(fileName, ignoreCase = true)
        } ?: return null
        return if (Files.isRegularFile(hit)) hit else null
    }

    /**
     * 读全部"启用行"（编码/BOM/注释/空行规则见类 KDoc）。
     * 读盘错误或编码探测失败抛 [IOException] —— 对应原版 Open 失败（EraStreamReader.cs:50-59）。
     */
    fun readEnabledLines(
        path: Path,
        /** 原版 `useRename`：为 true 时按 [renameDic] 替换 `[[...]]`（EraStreamReader.cs:106-119）。 */
        useRename: Boolean = false,
        /** `ParserMediator.RenameDic`（键 = `[[原名]]` 整串）。 */
        renameDic: Map<String, String> = emptyMap(),
        /**
         * `Config.ReplaceContinuationBR` 去掉 `"` 之后的分隔符（EraStreamReader.cs:179）。
         * 本作 `emuera.config:72` = `" "` ⇒ 单个半角空格，与缺省同值。
         */
        continuationSeparator: String = " ",
    ): List<String> {
        val text = decodeDetected(Files.readAllBytes(path))
            ?: throw IOException("无法识别文件编码（BOM/严格 UTF-8/CP932 均解码失败）: $path")
        val lines = splitLines(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val lineNo = i
            var raw = lines[i++]
            if (useRename) raw = applyRename(raw, renameDic)
            val t = stripLeadingWs(raw)
            if (t.isEmpty()) continue
            if (t[0] == ';') continue
            // 原版 ReadEnabledLine(disabled=false)，EraStreamReader.cs:130-134。
            if (t[0] == '}') throw continuationError("意料之外的行连接结束符号'}'", lineNo)
            if (t[0] == '{') {
                // :136-141：`{` 行必须整行只有一个 `{`（.NET Trim 两端空白）。
                if (raw.trim() != "{") {
                    throw continuationError("行连接开始符号'{'所在的行不能包含'{'以外的文字", lineNo)
                }
                val sb = StringBuilder()
                while (true) {
                    if (i >= lines.size) {
                        // :145-150：EOF 仍未遇到结束行
                        throw continuationError("使用了行连接开始符号'{'但没有找到结束符号'}'", i - 1)
                    }
                    var body = lines[i++]
                    if (useRename) body = applyRename(body, renameDic)
                    val span = body.trimStart()
                    if (span.isNotEmpty()) {
                        if (span[0] == '}') {
                            // :163-171：结束行 trim 两端后必须恰为 `}`。
                            if (span.trimEnd() == "}") break
                            throw continuationError("行连接结束符号'}'所在的行不能包含'}'以外的文字", i - 1)
                        }
                        // :172-175：块内不允许再出现单独的 `{`（注意原版只 TrimStart 后比较）。
                        if (span == "{") throw continuationError("意料之外的行连接开始符号'{'", i - 1)
                    }
                    // :178-180：原样拼接物理行 + 分隔符（不剥行首，也不过滤 `;`）。
                    sb.append(body).append(continuationSeparator)
                }
                val merged = stripLeadingWs(sb.toString())
                // 合并结果若为空/注释行，原版返回 EOS 流（上游按空行处理）；这里直接丢弃。
                if (merged.isEmpty() || merged[0] == ';') continue
                out.add(merged)
                continue
            }
            out.add(t)
        }
        return out
    }

    private fun applyRename(line: String, renameDic: Map<String, String>): String {
        if (!line.contains("[[")) return line
        var result = line
        for (m in RENAME_REGEX.findAll(line)) {
            renameDic[m.value]?.let { v -> result = result.replace(m.value, v) }
        }
        return result
    }

    private fun continuationError(message: String, lineNo: Int): CodeEE =
        CodeEE("$message（第${lineNo + 1}行）", line = lineNo)

    /** spec: EraStreamReader.cs:93 —— `regexRenameIdentifer`：`\[\[.*?\]\]`（惰性匹配）。 */
    private val RENAME_REGEX = Regex("""\[\[.*?\]\]""")

    /**
     * 带告警通道的读取：读盘/解码失败按原版 FailedOpenFile 语义告警并跳过**整个文件**
     * （ConstantData.cs:1756-1760，返回空列表）。[displayName] 用于告警文案，
     * 对应原版 EraStreamReader.Filename。
     */
    fun readEnabledLines(path: Path, warnings: MutableList<String>, displayName: String): List<String> =
        try {
            readEnabledLines(path)
        } catch (_: IOException) {
            warnings.add("$displayName: 打开文件失败（无法识别编码或读取错误），已跳过")
            emptyList()
        }

    /**
     * 与 C# `File.ReadAllLines` 同口径：`\r\n` / `\r` / `\n` 都是换行，末尾换行不产生空行
     * （EraStreamReader.cs:52 的 `File.ReadAllLines(filepath, encoding)`）。
     */
    private fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                out.add(text.substring(start, i))
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /**
     * `LexicalAnalyzer.SkipWhiteSpace`（LexicalAnalyzer.cs:647-690）的**本游戏配置下**的形态：
     * 跳过 `空格` / `\t`，并跳过 `;!;` / `;^;` 两个**标记**（不是注释）。三点与 Kotlin
     * `trimStart()` 不同：
     * 1. `trimStart()` 会吃掉 U+00A0 等 C# 视为内容的字符 —— SkipWhiteSpace 在
     *    `(uint)c <= 32` 且不是 `\t`/空格 时直接 break（:653-659）；
     * 2. `trimStart()` 会吃掉全角空格 U+3000，而原版只在 `Config.SystemAllowFullSpace` 为真时才吃
     *    （:677-684；该项默认 true，ConfigData.cs:101，英文名 `Whitespace includes full-width space`）。
     *    本游戏 `emuera.config` 的 `WHITESPACE INCLUDES FULL-WIDTH SPACE` 实测为 `NO`
     *    ⇒ 全角空格**不是**空白，不得剥（类 KDoc「行口径」条有分叉后果与实测计数）。
     *    core 尚无 config 通道，这里按**本游戏实测值**固化 ⇒ config 接线进 core 前是登记项。
     * 3. `;!;` / `;^;` 是 SkipWhiteSpace 里的**跳过标记**（:664-670 `st.Jump(3); continue;`）：
     *    它们之后的内容照常构成本行正文，该行 **不是** 注释。移植侧此前只看行首 `;` 就整行丢弃
     *    ⇒ `;!;1,name` 会被误判成注释（本语料 CSV 0 命中，`2026-10-26 R7 审查` 实测）。
     *    只有**非**标记的 `;` 才是注释（:671-676 把游标推到行尾 = EOS）；本方法对它原样返回，
     *    由调用方的 `[0] == ';'` 判定丢弃（与 `;!;` 之后剩余一个裸 `;` 的分支同解）。
     *    `;#;`（:662-663，仅 `Program.DebugMode`）无调试通道，暂不处理 ⇒ 登记项。
     * 字段内部（逗号之后）的 U+3000 一律是**内容**：loadDataTo 只按 `,` 切分、名字列不 trim
     * （ConstantData.cs:1802）。
     */
    private fun stripLeadingWs(line: String): String {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ' ' || c == '\t') { i++; continue }
            if (c == ';') {
                val rest = line.substring(i)
                if (rest.startsWith(";!;") || rest.startsWith(";^;")) { i += 3; continue }
            }
            return line.substring(i)
        }
        return ""
    }

    /** 朴素逗号切分（era CSV 无引号转义）。 */
    fun splitRow(line: String): List<String> = line.split(",")

    /**
     * 单文件名字表装载。spec: ConstantData.loadDataTo（ConstantData.cs:1747-1839）。
     *
     * - 列数 < 2 → 记录告警跳过（C# `Lang.Error.MissingComma`）；
     * - 首列非 int → 告警跳过（`FirstValueCanNotConvertToInt`）；
     * - 该槽数组长度为 0（禁用槽）→ `ProhibitedArrayName` 告警并**终止本文件**
     *   （ConstantData.cs:1786-1790）；
     * - 首列越界 → 告警跳过（`OoRArray`，ConstantData.cs:1791-1795，**没有扩容分支**——
     *   names 数组长度在 LoadData 一次性定型 `new string[MaxDataList[i]]`，ConstantData.cs:678）；
     * - 同一下标出现两次 → 告警（`VarKeyAreadyDefined`，ConstantData.cs:1796-1799）且**后写覆盖**；
     * - `names[idx] = 第 2 列`（重复下标后写覆盖，与原版 array 语义一致）；
     * - [prices] 非 null（仅 ITEM 槽）时第 3 列 → prices[idx]（`CanNotReadAmountOfMoney` 告警）。
     *
     * 关键字索引口径：原版把 `nameToIntDics` 留到 29 个 CSV 全读完再建（ConstantData.cs:711-724），
     * 遍历的是**最终 names 数组**、按**下标升序**、`!ContainsKey` 才算 —— 故同名散布多个下标时
     * 取**最小下标**，而不是文件里先出现的那一行。这里用两趟实现同一语义：先按行收成
     * 「下标→最终名字」（后写覆盖），再按下标升序 [CsvNameTable.set]。
     *
     * **STR 槽（10）只装值、不建反查**：原版 :713-716 遇到 `j == 10` 直接 continue ——
     * `Str.csv` 存的是字符串内容而不是名字，故 `names[10]`（:1802）照写、`nameToIntDics[10]`
     * 恒为空，:725-736 的别名合并也一并跳过。这里用 [CsvNameTable.setName] 写值、不写关键字，
     * 别名仍按原版读一遍（告警/重复判定不变）但不入索引。按名反查 STR 在原版走 STRNAME 槽 21
     * （SystemLayout.keywords），不是槽 10。
     *
     * 文件不存在 → 静默返回（C# `if (!File.Exists) return`），与"该游戏没这个文件"等价。
     *
     * 别名额（同目录同名 .als，如 `Abl.csv` ↔ `Abl.als`）：
     * - 调用点 ConstantData.cs:1834-1838：CSV 读完后按 `GetFileNameWithoutExtension` 拼 `.als`
     *   再装载；CSV 本身缺失时整个 loadDataTo 提前返回（:1749-1752），别名也不会读；
     * - 行语义 `loadAliases`（ConstantData.cs:1841-1903）：`整数序号,别名`；别名取第 2 列
     *   trim 后**原样**（不再切分后续列，Item.als 的 `0,ローター,3000,` 只用 `ローター`）；
     *   列数 < 2 / 首列非整数 / **同一别名文件内**别名重复 → 告警跳过；文件名大小写不敏感；
     * - 合并口径 ConstantData.cs:711-737：先 CSV 名、后别名，都只在键不存在时补，故别名
     *   不覆盖 CSV 名（[CsvNameTable.setKeyword] 即 putIfAbsent）；与 CSV 名同名的别名属于
     *   正常的"补空"，原版不告警（重复判定只看别名字典本身，:1877）；
     * - 序号**不做范围校验**：原版 `dictionary.Add(text, result)` 只记录序号（:1883），
     *   越界由使用端再查；[CsvNameTable.setKeyword] 同样不校验，保持一致——越界别名的
     *   `keywordIndex` 会原样返回该序号，消费端自行判界；
     * - 空别名（`0,` 形态）在原版会进别名字典但不进 nameToIntDics（:732 的
     *   `IsNullOrEmpty` 过滤）；此处 [CsvNameTable.setKeyword] 对空串 no-op，结果一致；
     * - 别名只进关键字反查，**不写**名字数组（C# 的 `names[]` 与 `aliases[]` 是两个容器）。
     *
     * BOM / `;` 注释 / 空行沿用 [readEnabledLines]（与原版 EraStreamReader.ReadEnabledLine 同口径）。
     */
    fun loadNameTable(
        dir: Path,
        fileName: String,
        slot: Int,
        table: CsvNameTable,
        prices: LongArray? = null,
        warnings: MutableList<String>,
    ) {
        val indexKeywords = slot != STR_SLOT
        val path = resolveFile(dir, fileName) ?: return
        val byIndex = HashMap<Int, String>()
        val seenIdx = HashSet<Int>()
        for (raw in readEnabledLines(path, warnings, fileName)) {
            val cols = splitRow(raw)
            if (cols.size < 2) {
                warnings.add("$fileName($slot): 列数不足，已跳过（$raw）")
                continue
            }
            val idx = cols[0].trim().toIntOrNull()
            if (idx == null) {
                warnings.add("$fileName($slot): 首列非整数，已跳过（$raw）")
                continue
            }
            // ConstantData.cs:1786-1790：数组长度 0（禁用槽）→ ProhibitedArrayName 告警 + break 本文件
            if (table.size == 0) {
                warnings.add("$fileName($slot): 数组长度为 0，该槽已禁用（ProhibitedArrayName），剩余行不再读")
                break
            }
            if (idx < 0 || idx >= table.size) {
                warnings.add("$fileName($slot): 下标 $idx 越界（长度 ${table.size}），已跳过")
                continue
            }
            // ConstantData.cs:1796-1799：重复下标只告警，值仍然覆盖（array[num2] = ...）
            if (!seenIdx.add(idx)) {
                warnings.add("$fileName($slot): 下标 $idx 已定义，后者覆盖（$raw）")
            }
            byIndex[idx] = cols[1]
            if (prices != null && cols.size >= 3) {
                // C# long.TryParse(source[..].TrimEnd())：TryParse 本身容忍前导空白，故这里 trim 两端
                val p = cols[2].trim().toLongOrNull()
                if (p == null) {
                    warnings.add("$fileName($slot): 价格列非整数，已跳过（$raw）")
                } else {
                    prices[idx] = p
                }
            }
        }
        // ConstantData.cs:711-724：名字索引按最终数组、下标升序建立（同名取最小下标）；
        // 槽 10 只写 names[]，不建 nameToIntDics（:713-716）。
        for (idx in byIndex.keys.sorted()) {
            val name = byIndex.getValue(idx)
            if (indexKeywords) table.set(idx, name) else table.setName(idx, name)
        }
        // ConstantData.cs:1834-1838：Path.GetDirectoryName + GetFileNameWithoutExtension + ".als"。
        // resolveFile 已按目录项大小写不敏感匹配（CI Linux 下 ABL.als 也能命中 Abl.als）。
        val stem = Path.of(fileName).fileName.toString().substringBeforeLast('.')
        val aliasPath = resolveFile(dir, "$stem.als") ?: return
        loadAliases(aliasPath, slot, table, indexKeywords, warnings)
    }

    /**
     * 同名 .als 别名装载。spec: `ConstantData.loadAliases`（ConstantData.cs:1841-1903）。
     *
     * 重复判定用**本文件内**已见别名集合（原版 `aliases[targetIndex]` 字典，:1877-1884）：
     * 只对"别名文件里出现两次同一别名"告警；别名与 CSV 名相同不算重复（合并时静默补空）。
     * 重复时保留**首次**出现的序号（C# `if ContainsKey → Warn else Add`）。
     *
     * [indexKeywords] 为 false（STR 槽 10）时仍逐行读、逐行告警，但不写关键字索引 ——
     * 原版 :725-736 的别名合并被 :713-716 的 continue 整段跳过。
     */
    private fun loadAliases(
        aliasPath: Path,
        slot: Int,
        table: CsvNameTable,
        indexKeywords: Boolean,
        warnings: MutableList<String>,
    ) {
        val where = aliasPath.fileName.toString()
        val seen = HashSet<String>()
        for (raw in readEnabledLines(aliasPath, warnings, where)) {
            val cols = splitRow(raw)
            if (cols.size < 2) {
                warnings.add("$where($slot): 列数不足，已跳过（$raw）")
                continue
            }
            // C# int.TryParse 允许首尾空白；与 loadDataTo 首列的 trim 口径一致。
            val idx = cols[0].trim().toIntOrNull()
            if (idx == null) {
                warnings.add("$where($slot): 首列非整数，已跳过（$raw）")
                continue
            }
            val alias = cols[1].trim()
            if (!seen.add(alias)) {
                warnings.add("$where($slot): 别名「$alias」已被定义，已跳过")
                continue
            }
            // setKeyword = putIfAbsent：CSV 名优先；空串 no-op；不校验序号范围（见 loadNameTable KDoc）。
            if (indexKeywords) table.setKeyword(alias, idx)
        }
    }
}
