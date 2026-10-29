package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import java.nio.file.Files
import java.nio.file.Path

/**
 * `CHARA*.CSV` 角色模板装载。spec: ConstantData.loadCharacterData / loadCharacterDataFile /
 * toCharacterTemplate（ConstantData.cs:1257-1308 / :1431-1520 / :1568-1742）。
 *
 * 文件发现：扫 [dir] 全部子目录里匹配 `CHARA*.csv`（大小写不敏感）的文件，序 = `Config.getFiles`
 * 的深度优先前序（本层文件先出、再按目录名递归，Config.cs:678-712），**不是**全路径字典序。
 * 原版口径 = `Config.GetFiles(csvDir, "CHARA*.CSV")`（ConstantData.cs:1263），是否递归取决于
 * `SearchSubdirectory` 配置；本游戏实际放在 `CSV/Chara/` 子目录，故这里**恒定递归**，
 * 与「该游戏能装载」的观测事实一致（两种配置下结果相同：根目录没有 CHARA 文件）。
 * 顺序只在「同一 No 多份模板」时可见：原版是追加（:1492）后按 No 排序（:1503），同 No 的先后
 * 就由文件序决定。
 *
 * 行语义（loadCharacterDataFile，ConstantData.cs:1431-1521）：
 * - 首列 `NO`/`番号`（大小写不敏感）→ 开启一个新模板；同一文件里第二次出现 → 告警并**跳过该行**
 *   （原版 keep 旧模板继续追加，不是中断文件）；
 * - 模板开启前的数据行 → 告警跳过（StartedDataBeforeCharaNo）；
 * - 其余行进 [toTemplate]（数值标签，界与键口径见其 KDoc）/ CSTR 段 / 文本标签段。
 *
 * csvNo 口径：文件名里 `CHARA` 前缀后的前导数字（`Chara1 霊夢.csv` → 1；无数字 → 0）。
 * ⚠️ 原版取的是 `eraStreamReader.Filename`（ConstantData.cs:1475），而它是 Config.GetFiles 给的
 * **相对路径名**（Config.cs:654-689 拼了子目录前缀），本语料模板在 `CSV/Chara/` 下 ⇒
 * `IndexOf("CHARA")` 命中的是**目录名** `Chara`（下一字符是 `\`）⇒ PC 侧 168 个模板 csvNo 全是 0。
 * 本移植按文件名取数字（0/1/…/167）。唯一消费点是初始角色
 * `Process.cs:1289-1292 AddCharacterFromCsvNo(0 / DefaultCharacter)`，本语料 GameBase.csv 无
 * `最初からいるキャラ`（DefaultCharacter=-1）⇒ 两侧都只加 csvNo=0 的模板，无可观测差异。
 * 登记项：换一个把 CHARA 放 CSV 根的游戏时两侧才分叉。
 *
 * 未落地（登记，均需 config 接线或 core/vars 侧配合）：
 * - `CompatiCALLNAME`（CALLNAME 空时填 NAME，ConstantData.cs:1268-1277）——本语料 168 个模板都有
 *   `呼び名` 行，无可观测差异；
 * - `SetSpFlag`（CFlag[0]!=0 → IsSpchara，CharacterTemplate.cs:98-104）与重复 No 告警
 *   （ConstantData.cs:1282-1306）——本语料 No 无重复、CompatSPChara=false，告警文案级差异。
 *
 * 官方 bug 复刻（ADR-005）：CSTR 缺第 3 列时原版先告警 MissingThirdIdentifier（:1723-1726），
 * 随后 `dictionary2[value] = tokens[2]` 越界崩（IndexOutOfRangeException，:1731），被
 * loadCharacterDataFile 的 catch 吃掉（:1505-1511）⇒ **本文件剩余行全部不处理**。本移植
 * 复刻为「缺列告警 + 中止告警 + 停读本文件」。⚠️ 原版的排序在 try 块内逐文件执行
 * （ConstantData.cs:1503），中止的文件跳过当次排序；本移植在全部文件读完后统一稳定排序，
 * 仅当中止文件是最后一个文件时最终顺序才可能不同（本语料无短 CSTR 行，不触发）。
 *
 * 排序：全部文件读完后按 No 升序（ConstantData.cs:1513）。
 */
object CharaCsvLoader {

    fun load(dir: Path, registry: VariableRegistry, warnings: MutableList<String>): List<CharacterTemplate> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = findCharaFiles(dir)
        val result = ArrayList<CharacterTemplate>()
        for (path in files) {
            loadOne(path, registry, warnings, result)
        }
        result.sortBy { it.no }
        return result
    }

    /**
     * `Config.getFiles`（Config.cs:654-712）的文件序：本层文件按名序先出，随后子目录按名序递归。
     * 与「收集完再按全路径排序」不同：`Chara9.csv` 会排在 `Chara0/Chara5.csv` **之前**
     * （字典序会反过来）。原版 `Array.Sort` 排的是全路径且用当前文化比较（:681），
     * 这里退化为「同层按文件名序」，只在含非 ASCII 文件名的同层里可能与 PC 分叉（登记项）。
     */
    private fun findCharaFiles(dir: Path): List<Path> {
        val out = ArrayList<Path>()
        visit(dir, out)
        return out
    }

    private fun visit(d: Path, out: MutableList<Path>) {
        val entries = try {
            Files.list(d).use { s -> s.collect(java.util.stream.Collectors.toList()) }
        } catch (_: java.io.IOException) {
            return
        }
        val files = entries.filter { !Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        val subdirs = entries.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        for (e in files) {
            val n = e.fileName.toString()
            if (n.length >= 5 && n.substring(0, 5).equals("chara", ignoreCase = true) &&
                n.substring(5).endsWith(".csv", ignoreCase = true)
            ) {
                out.add(e)
            }
        }
        for (sub in subdirs) visit(sub, out)
    }

    /**
     * 文件名里 `CHARA` 前缀后的前导数字（`Chara1 霊夢.csv` → 1；无数字 → 0）。
     *
     * 数字集是 `char.IsDigit`（Unicode Nd，ConstantData.cs:1479），随后 `Convert.ToInt64`
     * （:1486）只吃 ASCII 十进制 ⇒ 全角数字、超 Long 范围都抛（FormatException /
     * OverflowException）→ 被 :1505-1516 的 catch 吃掉：该模板**不入表**（:1492 在抛点之后）、
     * 本文件剩余行不再读。故这里返回 null，由调用方按「中止本文件」处理。
     */
    private fun csvNoOf(path: Path): Long? {
        val name = path.fileName.toString()
        val i = name.indexOf("chara", ignoreCase = true)
        if (i < 0) return 0L
        val digits = StringBuilder()
        var j = i + 5
        while (j < name.length && name[j].isDigit()) {
            digits.append(name[j])
            j++
        }
        // 必须走 dotnetParseLong 而不是 toLongOrNull：JVM 的 Long.parseLong 认 Unicode 数字
        // （`Long.parseLong("１") == 1`），而 .NET 的 Convert.ToInt64 只吃 ASCII ⇒
        // 全角数字在 PC 上抛 FormatException（本文件不入表），在 JVM 上会静默变成 1。
        return if (digits.isEmpty()) 0L else dotnetParseLong(digits.toString())
    }

    private fun loadOne(
        path: Path,
        registry: VariableRegistry,
        warnings: MutableList<String>,
        result: MutableList<CharacterTemplate>,
    ) {
        val fileName = path.fileName.toString()
        // 可变累加器（C# 直接改模板字段；Kotlin 侧文件读完再构造不可变数据类）
        var no: Long? = null
        var csvNo: Long = 0L
        var name = ""
        var callname = ""
        var nickname = ""
        var mastername = ""
        val maxbase = HashMap<Int, Long>()
        val mark = HashMap<Int, Long>()
        val exp = HashMap<Int, Long>()
        val abl = HashMap<Int, Long>()
        val talent = HashMap<Int, Long>()
        val relation = HashMap<Int, Long>()
        val cflag = HashMap<Int, Long>()
        val equip = HashMap<Int, Long>()
        val juel = HashMap<Int, Long>()
        val cstr = HashMap<Int, String>()

        fun flush() {
            val n = no ?: return
            result.add(
                CharacterTemplate(
                    no = n, name = name, callname = callname, nickname = nickname,
                    mastername = mastername, maxbase = maxbase, mark = mark, exp = exp,
                    abl = abl, talent = talent, relation = relation, cflag = cflag,
                    equip = equip, juel = juel, cstr = cstr, csvNo = csvNo,
                ),
            )
        }

        for (raw in CsvLoader.readEnabledLines(path, warnings, fileName)) {
            val cols = CsvLoader.splitRow(raw)
            if (cols.size < 2) {
                warnings.add("$fileName: 列数不足，已跳过（$raw）")
                continue
            }
            if (cols[0].isEmpty()) {
                warnings.add("$fileName: 行首为空，已跳过（$raw）")
                continue
            }
            val tag = cols[0].uppercase()

            /** 数值容器写入（ConstantData.cs:1739-1743）：重复下标告警 VarKeyAreadyDefined，值仍覆盖。 */
            fun put(map: MutableMap<Int, Long>, key: Int, value: Long) {
                if (map.containsKey(key)) {
                    warnings.add("$fileName: 标签 $tag 的下标 $key 重复定义，后者覆盖")
                }
                map[key] = value
            }

            if (tag == "NO" || cols[0].equals("番号", ignoreCase = true)) {
                if (no != null) {
                    warnings.add("$fileName: 重复定义角色编号，已跳过（$raw）")
                    continue
                }
                // C# long.TryParse(array[1].TrimEnd())：TryParse 容忍前导空白，故两端都 trim；
                // 数字集同样只认 ASCII（全角「９」在 PC 上 TryParse=false ⇒ 跳过该行）。
                val parsed = dotnetParseLong(cols[1].trim())
                if (parsed == null) {
                    warnings.add("$fileName: 角色编号非整数，已跳过（$raw）")
                    continue
                }
                val csv = csvNoOf(path)
                if (csv == null) {
                    // :1479-1491 的 Convert.ToInt64 抛 → :1505-1516 的 catch：模板在 :1492 才 Add，
                    // 所以本文件产出 0 个模板、剩余行不再读。no 保持 null，flush() 自然空转。
                    warnings.add("$fileName: 文件名编号无法解析（UnexpectedError），本文件剩余行不再读")
                    break
                }
                no = parsed
                csvNo = csv
                continue
            }
            if (no == null) {
                warnings.add("$fileName: 编号定义前出现数据行，已跳过（$raw）")
                continue
            }
            var recognized = toTemplate(fileName, tag, cols, registry.sizes, registry.csvTables, warnings) { slot, key, value ->
                when (slot) {
                    0 -> put(mark, key, value)      // MARK / 刻印
                    1 -> put(exp, key, value)       // EXP / 経験
                    2 -> put(abl, key, value)       // ABL / 能力
                    3 -> put(maxbase, key, value)   // BASE / 基礎
                    4 -> put(talent, key, value)    // TALENT / 素質
                    5 -> put(relation, key, value)  // RELATION / 相性
                    6 -> put(cflag, key, value)     // CFLAG / フラグ
                    7 -> put(equip, key, value)     // EQUIP / 装着物
                    else -> put(juel, key, value)   // JUEL / 珠
                }
            }
            // 文本类标签单独处理（值语义不同：第 3 列原文）
            when (tag) {
                "NAME", "名前" -> { recognized = true; name = cols[1] }
                "CALLNAME", "呼び名" -> { recognized = true; callname = cols[1] }
                "NICKNAME", "あだ名" -> { recognized = true; nickname = cols[1] }
                "MASTERNAME", "主人の呼び方" -> { recognized = true; mastername = cols[1] }
                "助手", "ISASSI" -> recognized = true  // ConstantData.cs:1667-1670
                else -> Unit
            }
            if (tag == "CSTR") {
                recognized = true
                val dic = registry.csvTables[17]
                val bound = registry.sizes.charaStr1D[0]
                // 数字键吃 TrimEnd 后的开头整数（:1686）；名字键按**未 trim** 的原串反查
                // （:1695 TryGetValue(tokens[1])，告警文案同样用未 trim 原串 :1697 / :1702 / :1718），
                // 即只有行首空白被 SkipWhiteSpace 剥掉，字段内空白原样参与匹配。
                val numeric = leadingInt64(cols[1].trimEnd())
                // :1687-1691：数字键先用 **long** 判界（`p < 0 || p >= num2`）并 return，
                // 之后才做 `(int)p` 截断（:1692）。先 toInt() 会把 2^32 一类值回绕成 0/1
                // 直接命中 CSTR[0]/[1]（原版这里是 OoRArray 跳过）。
                val idx = if (numeric != null) {
                    if (numeric < 0 || numeric >= bound) {
                        warnings.add("$fileName: CSTR 下标 $numeric 越界（长度 $bound）")
                        null
                    } else {
                        numeric.toInt()
                    }
                } else {
                    dic.keywordIndex(cols[1])
                }
                if (idx == null) {
                    // 数字键越界分支已在上面告过警（对应 :1690 的单次 return），这里只补名字键未命中。
                    if (numeric == null) warnings.add("$fileName: CSTR 键未定义（${cols[1]}）")
                } else if (idx < 0 || idx >= bound) {
                    warnings.add("$fileName: CSTR 下标 $idx 越界（长度 $bound）")
                } else {
                    // :1723-1731：缺第 3 列原版先告警 MissingThirdIdentifier（:1723-1726），
                    // 随后 tokens[2] 越界崩（:1731）→ 被 :1505-1511 吃掉 ⇒ 本文件剩余行不处理
                    // （官方 bug，ADR-005 原样复刻）。
                    if (cols.size < 3) warnings.add("$fileName: CSTR 缺少第 3 列（$raw）")
                    // :1727-1730：重复下标告警 VarKeyAreadyDefined，值仍覆盖
                    if (cstr.containsKey(idx)) warnings.add("$fileName: 标签 CSTR 的下标 $idx 重复定义，后者覆盖")
                    if (cols.size < 3) {
                        warnings.add("$fileName: CSTR 处理中断，本文件剩余行不再读")
                        break
                    }
                    cstr[idx] = cols[2]
                }
            }
            // ConstantData.cs:1671-1673：未识别标签告警 CanNotInterpreted
            if (!recognized) warnings.add("$fileName: 无法识别的标签（${cols[0]}）")
        }
        flush()
    }

    /**
     * 数值类标签解析。spec: ConstantData.toCharacterTemplate（:1568-1745）。
     *
     * 键口径：第 2 列先按 `tryToInt64`（ConstantData.cs:1523-1566）解析 —— **只吃开头**的可选符号 +
     * 十进制数字（`5abc` → 5，吃 `TrimEnd()` 后的串，:1686），与值列同口径；解析成功时先做
     * `p < 0 || p >= num2` 的越界判定（:1687-1691）。失败时若该族有名字表（nameToIntDics[槽]）
     * 则按名反查（:1693-1705，用**未 trim** 的原串 `tokens[1]`，:1695 —— 只有行首空白被
     * SkipWhiteSpace 剥掉，字段内的空白原样参与匹配），两者都失败 → 告警跳过
     * （NotDefinedKey / CanNotInterpreted）。
     * 值口径：第 3 列按同一 `tryToInt64` 解析**开头整数**（可选 +/-，忽略其后的 `;注释` 等剩余字符），
     * 缺失/不可解析 → **1**（ConstantData.cs:1735-1738 `p2 = 1L`）。
     *
     * 界（`num2`）是 **CharacterIntArrayLength[下标]具名槽**，不是 CSV 名表槽号：
     * MARK→5 / EXP→4 / ABL→2 / BASE→1（MAXBASE）/ TALENT→3 / CFLAG→9 / EQUIP→12 / JUEL→10 /
     * RELATION→11（ConstantData.cs:1599-1666；同一张表见 CharacterTemplate.ArrayLength
     * CharacterTemplate.cs:63-96）。越界（键 ≥ 对应角色数组长度）→ 告警跳过（OoRArray）。
     *
     * @return 该标签是否为原版 switch 认识的数值容器标签（false = 未识别，
     * 调用方按 CanNotInterpreted 告警，ConstantData.cs:1671-1673）。
     */
    private fun toTemplate(
        fileName: String,
        tag: String,
        cols: List<String>,
        sizes: VariableSizes,
        tables: Array<com.eratw.port.core.vars.CsvNameTable>,
        warnings: MutableList<String>,
        emit: (slot: Int, key: Int, value: Long) -> Unit,
    ): Boolean {
        // slot: 本移植 CharacterTemplate 的容器编号；dicSlot: CSV 名表槽（ConstantData.cs:1603-1665 的
        // nameToIntDics[...]）；boundIdx: CharacterIntArrayLength 下标（同处 num2）
        val spec = when (tag) {
            "MARK", "刻印" -> Triple(0, 5, 5)
            "経験", "EXP" -> Triple(1, 1, 4)
            "能力", "ABL" -> Triple(2, 0, 2)
            "基礎", "BASE" -> Triple(3, 7, 1)
            "素質", "TALENT" -> Triple(4, 2, 3)
            "相性", "RELATION" -> Triple(5, -1, 11)
            "フラグ", "CFLAG" -> Triple(6, 15, 9)
            "装着物", "EQUIP" -> Triple(7, 11, 12)
            "珠", "JUEL" -> Triple(8, 3, 10)
            else -> return false
        }
        val slot = spec.first
        val dicSlot = spec.second
        val boundIdx = spec.third
        val bound = sizes.charaInt1D[boundIdx]
        // 两条路径的**入串不同**，照抄原版：数字判定吃 `tokens[1].TrimEnd()`（:1686），
        // 名字反查与告警文案吃**未 trim 的** `tokens[1]`（:1695 / :1697 / :1702 / :1712 / :1718）。
        val key: Int
        val numeric = leadingInt64(cols[1].trimEnd())
        if (numeric != null) {
            // :1687-1691 只在这里判负/越界（long 域），名字反查路径不判负
            if (numeric < 0 || numeric >= bound) {
                warnings.add("$fileName: 标签 $tag 的下标 $numeric 越界（长度 $bound）")
                return true
            }
            key = numeric.toInt()
        } else {
            val looked = if (dicSlot >= 0) tables[dicSlot].keywordIndex(cols[1]) else null
            if (looked == null) {
                warnings.add("$fileName: 标签 $tag 的键未定义（${cols[1]}）")
                return true
            }
            if (looked >= bound) {
                warnings.add("$fileName: 标签 $tag 的下标 $looked 越界（长度 $bound）")
                return true
            }
            if (looked < 0) {
                warnings.add("$fileName: 标签 $tag 的键无法解析（${cols[1]}）")
                return true
            }
            key = looked
        }
        val value = if (cols.size >= 3) leadingInt64(cols[2]) ?: 1L else 1L
        emit(slot, key, value)
        return true
    }

    /**
     * C# `ConstantData.tryToInt64`（ConstantData.cs:1523-1566）的等价物：只吃字符串**开头**的
     * 整数字面量，其后字符一律忽略（`5abc` → 5、`字面量;注释` → 字面量）。三处消费：
     * - 键列（`5abc` → 5，见 [toTemplate]）；
     * - 值列（如 `フラグ,基本服装セット,101;服装` → 101，失败以 1 兜底，
     *   ConstantData.cs:1735-1738）；
     * - CSTR 键列（同键列口径）。
     *
     * 字面量文法 = `LexicalAnalyzer.ReadInt64`（LexicalAnalyzer.cs:30-90）+ `readDigits`
     * （:92-165）：可选 +/-，然后
     * - 十进制数字串；
     * - `0x`/`0X` + 十六进制数字串（:36-45）；
     * - `0b`/`0B` + 二进制数字串（:46-51，数字串里出现 2..9 → CodeEE → 解析失败）；
     * - 其后可跟 `p`/`P`（乘底 2）或 `e`/`E`（乘底 10）+ 指数数字串（:66-88，`num4 * Math.Pow(num, num2)`
     *   后截断取整；结果超 Long 范围或 NaN/Inf → CodeEE → 解析失败）。**指数数字串本身按 mantissa 的
     *   进制读**（:77 `num2 = (int)readDigits(st, num3)` 传的是同一个 `num3`，不是 10），
     *   且 `(int)` 先截断 —— 指数位宽与 mantissa 一致。
     * 任意一步失败（空串、首字符不是十进制数字、无数字、进制溢出、指数溢出）→ null ——
     * 对应用 C# 的 `try/catch { return false; }`：**溢出不是回绕**（`long.Parse` /
     * `Convert.ToInt64` 抛 OverflowException，:143-156），原版据此把该行当作"键无法解析"跳过。
     * 首字符位置只认 ASCII `'0'..'9'`（ConstantData.cs:1545-1554 的 case 列表），故 `１`（全角）
     * 开头直接 false；出现在**其后**（`1２`）则会被 `char.IsDigit`（:105，Unicode Nd）吃进数字串，
     * 再由 `long.Parse` 拒绝 —— 见下 TODO(verify)。
     * TODO(verify)：`long.Parse` 对非 ASCII 数字串抛 FormatException 这一条按 .NET
     * `NumberFormatInfo.NativeDigits`（不变文化 = ASCII 0-9）推得，本机无 .NET 运行时未实测；
     * 影响面仅是「非首字符的全角数字键」判为解析失败还是成功解析成某个值。本语料 168 个 CHARA
     * CSV 的 10,708 条启用行里**无任何非 ASCII 数字出现在数值列**（2026-10-22 实测）⇒ 不可达。
     */
    private fun leadingInt64(raw: String): Long? {
        if (raw.isEmpty()) return null
        var i = 0
        var sign = 1L
        if (raw[0] == '+') i = 1 else if (raw[0] == '-') { sign = -1L; i = 1 }
        // ConstantData.cs:1541-1554 的 switch：首字符不是 '0'..'9' 就走 default → 直接 false
        // （`e3`/`.5`/全角数字 都不算 —— 这里只认 ASCII，与 :1545-1554 的 case 列表一致）
        if (i >= raw.length || raw[i] !in '0'..'9') return null
        val v = readInt64Literal(raw, i) ?: return null
        return sign * v
    }

    /** `readDigits` 的产物：解析出的值与数字串结束位置。 */
    private class Digits(val value: Long, val end: Int)

    /** `LexicalAnalyzer.ReadInt64`（LexicalAnalyzer.cs:30-90）的后半段，`start` 指向首个数字。 */
    private fun readInt64Literal(s: String, start: Int): Long? {
        var i = start
        var base = 10
        if (s[i] == '0' && i + 1 < s.length) {
            when (s[i + 1]) {
                'x', 'X' -> { base = 16; i += 2 }
                'b', 'B' -> { base = 2; i += 2 }
            }
        }
        val digits = readDigits(s, i, base) ?: return null
        // :66-88：先吃掉指数标记，再读指数数字串；指数读不出来同样失败。
        val mul = when (if (digits.end < s.length) s[digits.end] else '\u0000') {
            'p', 'P' -> 2
            'e', 'E' -> 10
            else -> 0
        }
        var value = digits.value
        if (mul != 0) {
            val exp = readDigits(s, digits.end + 1, base) ?: return null
            val e = exp.value.toInt() // C# `num2 = (int)readDigits(...)`：先截断成 Int
            if (e != 0) {
                val d = value.toDouble() * Math.pow(mul.toDouble(), e.toDouble())
                // :83-86：NaN / Inf / 超 Long 范围 → CodeEE(OoRInt64) → tryToInt64 false
                if (d.isNaN() || d.isInfinite() || d > 9.223372036854776E18 || d < -9.223372036854776E18) {
                    return null
                }
                value = d.toLong() // C# `(long)num5`：截断取整
            }
        }
        return value
    }

    /**
     * `LexicalAnalyzer.readDigits`（LexicalAnalyzer.cs:92-165）：按进制吃数字串并解析。
     * 失败（空数字串、非法二进制数字、ASCII 数字溢出）→ null，等价 C# 那里抛出的 CodeEE。
     */
    private fun readDigits(s: String, start: Int, base: Int): Digits? {
        var i = start
        // :95-99：数字串自身允许一个前导符号（指数部分 `1e-2` 走这条）
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        when (base) {
            16 -> while (i < s.length && (s[i].isDigit() || asciiDigit(s[i]) in 10..15)) i++
            2 -> {
                while (i < s.length && s[i].isDigit()) {
                    // :126-136：二进制数字串里出现 2..9 → CodeEE(CanNotUseBinaryNotate)
                    if (s[i] != '0' && s[i] != '1') return null
                    i++
                }
            }
            else -> while (i < s.length && s[i].isDigit()) i++
        }
        val text = s.substring(start, i)
        val value = when (base) {
            16, 2 -> parseRadix(text, base)
            else -> dotnetParseLong(text)
        } ?: return null
        return Digits(value, i)
    }

    /** ASCII 数字值（`0-9a-fA-F`）；其余字符（含全角数字）返回 -1。 */
    private fun asciiDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** .NET `long.Parse`（十进制）：只认 ASCII 数字 + 可选符号，空串/符号独存/溢出 → null。 */
    private fun dotnetParseLong(text: String): Long? {
        if (text.isEmpty()) return null
        var i = 0
        if (text[0] == '+' || text[0] == '-') i = 1
        if (i >= text.length) return null
        for (k in i until text.length) if (text[k] !in '0'..'9') return null
        return text.toLongOrNull()
    }

    /** .NET `Convert.ToInt64(text, radix)`：带符号、按 radix 解析，越界（>Long.MAX）→ null。 */
    private fun parseRadix(text: String, radix: Int): Long? {
        if (text.isEmpty()) return null
        var i = 0
        var neg = false
        if (text[0] == '+' || text[0] == '-') {
            neg = text[0] == '-'
            i = 1
        }
        if (i >= text.length) return null
        var acc = 0L
        for (k in i until text.length) {
            val d = asciiDigit(text[k])
            if (d < 0 || d >= radix) return null
            if (acc > (Long.MAX_VALUE - d) / radix) return null // OverflowException → false
            acc = acc * radix + d
        }
        return if (neg) -acc else acc
    }
}
