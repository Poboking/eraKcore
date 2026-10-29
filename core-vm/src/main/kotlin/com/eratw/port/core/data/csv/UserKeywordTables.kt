package com.eratw.port.core.data.csv

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.UserVar
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * ERD 用户变量关键字表装载（spec: `ErhLoader.cs:342-388 PrepareERDFileNames` +
 * `ConstantData.cs:758-793 UserDefineLoadData` / `:914-1204 GetKeywordDictionary`）。
 *
 * ## 文件来源与命名
 * - `<gameRoot>/CSV` 目录**顶层**的 csv，与 `<gameRoot>/ERB` 下**递归**的 erd；
 *   文件名去扩展名、大写后 = 变量名（`M_TFLAG.csv` → 变量 `M_TFLAG`）。
 * - 二维/三维变量逐维用 `<变量名>@<第几维>`（spec: `ErhLoader.cs:283-313`）。
 * - **同名多份全部收进同一个清单**（`ErhLoader.cs:349-367` 与 `:369-387` 都是
 *   `erdFileNames[key].Add(text)` 的追加分支，不是"先到者胜"）；先 ERB 的 erd、后 CSV 的 csv，
 *   顺序即 `UserDefineLoadData` 里 `foreach (string filepath in filepaths)` 的读取序
 *   （ConstantData.cs:761）。原版用 `Directory.GetFiles` 返回目录序（不排序），
 *   这里逐项 `sorted()` 保证同一份语料两次装载得到同一份表 —— 只影响报错文案里的
 *   「文件1/文件2」归属，不影响表内容（重键一律直接报错）。
 * - `Config.UseERD` 的开关在调用侧（`GameSession` 第 5b 步），本类不判。
 *
 * ## 行口径（一张表 = 一个 `names[29]` 数组，ConstantData.cs:763-766 + :1747-1839）
 * 每行 `下标,关键字[,额外列]`，第二列是名字、不 trim（:1802）。编码/BOM/注释/空行规则与
 * 29 槽 CSV 同源，复用 [CsvLoader.readEnabledLines]。逐行落进"下标 → 名字"数组：
 * - 列数不足 / 首列非整数 / 下标越界（`< 0` 或 `>= 声明长度`）→ 该行丢弃
 *   （:1775-1795 的 MissingComma / FirstValueCanNotConvertToInt / OoRArray 三条告警分支）；
 * - **同一下标出现两次 → 后者覆盖**（:1796-1802 只告警 `VarKeyAreadyDefined`，
 *   `array[num2] = ...` 无条件写）；空名行同样参与覆盖，所以 `1,A` + `1,` 的结果是 A **消失**；
 * - 数组长度 0（`#DIM X, 0`）→ `ProhibitedArrayName` 告警并终止整张文件（:1786-1790），
 *   等价于这里读不到任何关键字。
 * 告警通道：core 侧 ERD 装载没有 `ParserMediator`，以上四类告警静默丢行（只登记项，
 * 见交付报告；原版靠 level 1 告警提示，不改变表内容）。
 * 同名的 als 别档**不参与** erd 表：原版 `loadDataTo` 虽然会把边车 als 读进 `aliases[29]`
 * （:1834-1837），但 :767-781 的累积只扫 `names[29]` 数组，`GetKeywordDictionary` 的
 * VAR 分支也只查 `erdNameToIntDics`（:1113-1185）⇒ 别名进不了用户变量关键字表。
 *
 * ## 重复判定（SAVE-08）
 * 按 `filepaths` 顺序、对每个下标**升序**扫（:767-768）扫进一张累积字典
 * （关键字 → 下标 + 首次出现的文件，即 `ErdDictInfo`）：
 * - 关键字已在字典里 ⇒ CodeEE `DuplicateErdKey`（:771-773）。**文件内**（同一张表的两个下标）
 *   与**跨文件**（同名多份表里都出现该关键字）都算，因为累积字典跨文件、但每次调用新建
 *   （:760）；下标相同也算重复，因为字典的键是关键字而不是下标。
 * - 越界的行先被数组边界挡掉，所以「关键字在范围内出现一次 + 越界出现一次」**不报**
 *   （:1791-1795）。
 * - 文件循环全部走完才检查变量名是否已登记 ⇒ CodeEE `DuplicateVariableDefine`（:783-786）。
 *   即 `DuplicateErdKey` 一定先于 `DuplicateVariableDefine`。
 * - 抛错时整张表**不会**登记（:792 的 Add 到不了）⇒ 之后用字符串下标读该变量报
 *   「不能用字符串指定」，而不是「未定义关键字」。
 * - **按行隔离（M11-A 收口余项）**：原版是在逐条 `#DIM` 行的 try/catch 里把 CodeEE 降级成
 *   level 2 告警并继续装载其它行（`ErhLoader.cs:328-332`），所以一个变量的表坏了**不影响**
 *   别的变量、更不影响程序起不起来。移植侧挂表点同样在 [install] 的逐变量循环里
 *   catch `CodeEE`：该条不挂表、把官方文案连同 `erd名#下标位` 前缀落进 [warnings] 清单
 *   （与 `GameSession.privateDimFailures` 同形的装载期清单，由 `GameSession.erdWarnings` 交出）。
 *   会话装配期因此不再因为一条重键而整局失败 —— 那正是原版的行为。
 *
 * ## 声明名撞 ERD 关键字（SAVE-09b，`Config.CheckDuplicateIdentifier`）
 * 原版在 `UserDefinedVariableData.Create` 里判 `Config.UseERD && Config.CheckDuplicateIdentifier`
 * 后调 `ConstantData.isDefinedErd`（UserDefinedVariableData.cs:269-272 + ConstantData.cs:795-806）：
 * 拿本行**声明名**扫**此前已登记**的 ERD 表，命中即 level 1 告警（只告警，不动表、不动登记）。
 * 顺序敏感是原样复刻的：判据在 `Create()` 内、本行自己的表装载之前（ErhLoader.cs:261 vs :279），
 * 所以「声明名 = 自己表的关键字」与「声明名 = 后声明表的关键字」都不告警。移植侧的挂表点
 * （[install]）按声明序走同一套次序：先查已登记的表，再挂本变量的表。
 * 语料现值 `emuera.config:71` = **YES**，与出厂缺省（ConfigData.cs:118 的 false）不同，
 * 但独立实测（2026-10-24，Haruki；口径 = ERB 子树全部 `.ERB`/`.ERH` 的 `#DIM` 行）：
 * 声明行 20,141 条、去重名 3,572 个（大写口径）；会被 [install] 载入的表只有 1 张
 * （`CSV/EI_FLAG.csv`，43 个关键字 —— 基名命中 `#DIM` 名 `EI_FLAG`；ERB 树下唯一 `.erd`
 * 是 `COLUMNDIV@2.ERD`，无同名 `#DIM`），与全部 `#DIM` 名**零交集**（大小写敏感与大写
 * 口径均为 0）⇒ 本开关在语料上不产生告警（基准读数不变）。
 *
 * ## 变量名重复登记在移植侧的可达性
 * 原版这条守卫基本到不了：同名 `#DIM` 走 `idDic.AddUseDefinedVariable`，
 * `varTokenDic`/`nameDic` 都是 `Dictionary.Add`，重名先炸（IdentifierDictionary.cs:394-399），
 * 且 `erdNameToIntDics` 是大小写敏感的普通字典（ConstantData.cs:118）。移植侧把
 * **函数私有 `#DIM` 也挂表**（原版只对 ERH 的 `#DIM` 挂表，dimlines 只收 isPrivate:false 的行，
 * ErhLoader.cs:120-124），同名多份私有声明共用一张表是既有口径（见 UserKeywordTablesTest 的
 * 「私有声明共享同名表」用例），所以这里用 [install] 的 `seen` 清单把
 * 「同一 erd 变量名 + 同一下标位 + 同一份文件清单」的重复登记先吸掉；
 * 剩下能走到守卫的只有"同一个 erd 变量名挂到两个不同下标位"（同名同时有 chara 与非 chara 声明），
 * 那时原版也是报错（只是崩在声明层），这里报官方 `DuplicateVariableDefine` 文案。
 *
 * ## 挂表
 * [install] 只对**已注册**的用户变量挂表：全局 `#DIM` + 侧车私有 `#DIM` 都走同一份
 * `VariableStore`。用户变量的字符串下标在 [VariableStore.keywordToInteger] 里查这张表；
 * 缺表/缺关键字分别报「不能用字符串指定」/「CSV 中未定义关键字」。
 * 挂载槽位是（变量名，完整下标位），与原版 erd 变量名（`NAME` / `NAME@n`）不是一一对应：
 * 同名 1 维声明与同名 2 维声明的 `@1` 会落到同一个槽互相覆盖，这是挂载形态的限制、
 * 本路不改（写进交付报告「需组长接线」）。
 */
object UserKeywordTables {

    /** 变量名（大写，含 `@维` 后缀）→ 该名的**全部**表文件（先 erd 后 csv）。 */
    fun discover(gameRoot: Path): Map<String, List<Path>> {
        val out = LinkedHashMap<String, MutableList<Path>>()
        val erbDir = gameRoot.resolve("ERB")
        if (Files.isDirectory(erbDir)) {
            runCatching {
                Files.walk(erbDir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .sorted()
                        .forEach { collect(out, it, ".erd") }
                }
            }
        }
        val csvDir = gameRoot.resolve("CSV")
        if (Files.isDirectory(csvDir)) {
            runCatching {
                Files.list(csvDir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .sorted()
                        .forEach { collect(out, it, ".csv") }
                }
            }
        }
        return out
    }

    private fun collect(out: MutableMap<String, MutableList<Path>>, path: Path, suffix: String) {
        val name = path.fileName.toString()
        if (!name.endsWith(suffix, ignoreCase = true)) return
        out.getOrPut(baseKey(name)) { ArrayList() }.add(path)
    }

    private fun baseKey(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return (if (dot >= 0) fileName.substring(0, dot) else fileName).uppercase()
    }

    /**
     * 单张表文件 → 「下标 → 关键字」终值（原版一个文件对应一个 `names[29]` 数组，
     * ConstantData.cs:763-766 + :1747-1839）。
     *
     * 读不开返回空表（C# 是 FailedOpenFile 告警后跳过整个文件，:1756-1760）。
     * **空关键字也占位**（`1,A` 后跟 `1,` 的结果是 1 号位没有名字），与原版
     * `array[num2] = ...` 的无条件写一致；越界行不在此过滤（范围由声明长度决定，
     * 见 [load]）。
     */
    fun namesByIndex(path: Path): Map<Int, String> {
        val lines = runCatching { CsvLoader.readEnabledLines(path) }.getOrElse { return emptyMap() }
        val out = LinkedHashMap<Int, String>()
        for (line in lines) {
            val cols = line.split(',')
            if (cols.size < 2) continue
            val index = cols[0].trim().toIntOrNull() ?: continue
            if (index < 0) continue
            out[index] = cols[1]
        }
        return out
    }

    /**
     * 一个 erd 变量名的全部表文件 → 关键字表（原版 `UserDefineLoadData` 的字典累积部分，
     * ConstantData.cs:758-793：文件循环 :761-782 + 建 `dictionary2` :787-791）。
     *
     * [varname] 只用于报错文案；[varlength] = 该维的声明长度（数组边界）。
     * 同一关键字落在两个下标（同文件或跨文件）⇒ CodeEE `DuplicateErdKey`。
     */
    fun load(
        files: List<Path>,
        varname: String,
        varlength: Int,
        cache: MutableMap<Path, Map<Int, String>> = HashMap(),
    ): Map<String, Int> {
        val table = LinkedHashMap<String, Int>()
        val origin = HashMap<String, Path>()
        for (file in files) {
            val byIndex = cache.getOrPut(file) { namesByIndex(file) }
            for (i in 0 until varlength) {
                val keyword = byIndex[i]
                if (keyword.isNullOrEmpty()) continue
                val first = origin[keyword]
                if (first != null) throw CodeEE(duplicateErdKey(varname, keyword, first, file))
                table[keyword] = i
                origin[keyword] = file
            }
        }
        return table
    }

    /**
     * 装载期折叠用的 ERD 关键词快照：`变量名` / `变量名@维号`（大写）→ 关键字表。
     *
     * 消费点 = `GETNUM(变量, "关键字"[, 维号])` 的装载期折叠
     * （[com.eratw.port.core.vars.DimFunctionFolders.pureStatic] 的 `keywordTables`）。
     * 原版查找序（`ConstantData.cs:849-871` `TryKeywordToInteger`）：第一跳查内置 29 张
     * 名字表（`:857-861`），未命中再查 `erdNameToIntDics`（`:866-870`）。折叠点发生在
     * `#DIM` 注册**之前**（全局 `#DIM` 的尺寸折叠要先于 [install]），此时没有「已声明变量」
     * 可查，只能按表文件独立取表，故单独给出本快照。
     *
     * 键口径 = 原版 `erdNameToIntDics`（`ConstantData.cs:783-792`）：1 维 = `NAME`，
     * 多维逐维 = `NAME@n`（n 从 1 起）。与 `GetnumMethod` 在
     * `FunctionMethodCreator.cs:4819` 拼的「标识符名 + @ + 第三参」同形
     * （两参形态 = `NAME`）。值 = 关键字 → 下标。
     *
     * ## 与 [install] 的两处已知差异（TODO(verify)）
     * - **长度上界取文件内最大下标 + 1**，不是声明里的 `dims[n]`：折叠点拿不到声明。
     *   文件行不越过声明长度时两口径同值；越过时本函数会多收越界行（原版
     *   `UserDefineLoadData` 按 `varlength` 建数组、越界行丢弃，`ConstantData.cs:763-778`）。
     *   语料实测 6 张表（`COLUMNDIV@2` / `EI_BASE` / `EI_FLAG` / `M_CFLAG` / `M_FLAG` /
     *   `M_TFLAG`）都不超过声明的维长 ⇒ 当前 0 差异。
     * - **重键表整张跳过**：原版在 `#DIM` 声明点抛 `DuplicateErdKey`（`ConstantData.cs:771-773`，
     *   由 `ErhLoader.cs:328-332` 降级成 level 2 告警）。本函数是折叠回退面，
     *   失败方向 = 不折（绝不替坏表折出一个下标），告警留给 [install]。
     * - **`_` 前缀的引擎内部 csv（`_Rename` / `_Replace`）不计**：原版 `PrepareERDFileNames`
     *   会把它们列进候选（`ErhLoader.cs:360-388`），但它们不是「下标 → 名字」表，
     *   按 [namesByIndex] 读出来是伪表；折叠面不能拿伪表折出下标。
     *
     * @param systemNames 系统变量名（大写）：[discover] 会把 CSV 目录下**所有** csv 的基名当
     *   候选表（原版 `PrepareERDFileNames` 同口径，`ErhLoader.cs:360-388`），但只有用户
     *   `#DIM` 才会真的挂表（`ConstantData.cs:758-793`）。系统变量名不可能被用户 `#DIM`
     *   重声明，故一律排除 —— 否则 `GETNUM(TIME, …)` 一类会在折叠面拿到一张原版根本没有的
     *   「ERD 表」，把本该 -1/失败的形态折出具体下标。
     */
    fun foldSnapshot(
        gameRoot: Path,
        systemNames: Set<String> = emptySet(),
    ): Map<String, Map<String, Int>> {
        val groups = discover(gameRoot)
        if (groups.isEmpty()) return emptyMap()
        val cache = HashMap<Path, Map<Int, String>>()
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((name, files) in groups) {
            if (name.startsWith("_")) continue
            if (name.uppercase() in systemNames) continue
            var extent = 0
            for (f in files) {
                val byIndex = cache.getOrPut(f) { namesByIndex(f) }
                val max = byIndex.keys.maxOrNull() ?: -1
                if (max + 1 > extent) extent = max + 1
            }
            if (extent <= 0) continue
            val table = runCatching { load(files, name, extent, cache) }.getOrNull() ?: continue
            if (table.isNotEmpty()) out[name] = table
        }
        return out
    }

    /**
     * 把 `<gameRoot>` 下所有匹配的 ERD 表挂到 [store] 的用户变量上。
     *
     * 只处理**已声明**的变量（C# 在 `#DIM` 声明点装载，表名对不上就根本不会读）。
     * 同一变量名在多个函数私有声明时共享同一张表（`seen` 清单；C# `erdNameToIntDics`
     * 也只用名字做键，ConstantData.cs:783-792）。重复登记 ⇒ CodeEE `DuplicateVariableDefine`。
     *
     * @param warnings 装载期告警清单：某个变量的表判定失败（重键 / 重复登记）时，官方文案
     *   连同 `erd名#下标位` 前缀落进这里，该变量不挂表，**其余变量继续挂**
     *   （spec: ErhLoader.cs:328-332 —— 原版在逐条 `#DIM` 的 try/catch 里把 CodeEE 降级成
     *   level 2 告警并继续装载，会话不会因为一条重键而起不来）。缺省丢弃清单 = 只要不炸，
     *   调用侧想拿到文案就自己传一个可变小集合（生产 = `GameSession.erdWarnings`）。
     *   [checkDuplicateIdentifier] 打开时，level 1 的「声明名撞 ERD 关键字」告警也进这里
     *   （官方文案自带变量名与表名，无需前缀）。
     * @param checkDuplicateIdentifier `Config.CheckDuplicateIdentifier`（ConfigData.cs:118 出厂 false；
     *   语料 `emuera.config:71` 为 **YES**）。原版在 `UserDefinedVariableData.Create` 里判
     *   `Config.UseERD && Config.CheckDuplicateIdentifier` 后调 `ConstantData.isDefinedErd`
     *   （UserDefinedVariableData.cs:269-272 + ConstantData.cs:795-806）：拿**本行声明名**去扫
     *   **此前已登记**的 ERD 表，命中即 `ParserMediator.Warn(..., level 1)` —— 只告警，不改表、
     *   不改登记。调用侧的 `UseERD` 门在 `GameSession`（关掉时原版连表都不扫，这里也不进来）。
     */
    fun install(
        store: VariableStore,
        gameRoot: Path,
        warnings: MutableList<String> = mutableListOf(),
        checkDuplicateIdentifier: Boolean = false,
    ) {
        val files = discover(gameRoot)
        if (files.isEmpty()) return
        val cache = HashMap<Path, Map<Int, String>>()
        val vars: Collection<UserVar> =
            store.allUserVars() + store.allPrivateUserVars()
        val seen = HashSet<String>()
        // 原版 erdNameToIntDics（ConstantData.cs:783-792）：键 = `NAME` / `NAME@n`。
        val registered = HashSet<String>()
        // 已登记的表（erd 名 → 关键字表），`isDefinedErd` 的扫描面（ConstantData.cs:795-806）。
        val loaded = ArrayList<Pair<String, Map<String, Int>>>(8)
        for (v in vars) {
            val spec = v.spec
            // spec: UserDefinedVariableData.cs:269-272 —— 判据在 `Create()` 里、**本行自己的表装载之前**，
            // 所以「声明名 == 自己表里的关键字」不会告警（那张表此刻还没登记），
            // 而「声明名 == 后声明的表的某关键字」也不会告警（顺序敏感，原样复刻）。
            if (checkDuplicateIdentifier) checkDefinedErd(spec.name, loaded, warnings)
            if (spec.isRef) continue
            val dims = spec.dims
            if (dims.isEmpty()) continue
            val nameUpper = spec.name.uppercase()
            if (dims.size == 1) {
                val group = files[nameUpper] ?: continue
                val dim = if (spec.isCharaData) 1 else 0
                val table = attach(store, v, nameUpper, dim, dims[0], group, cache, seen, registered, warnings)
                if (table != null) loaded += nameUpper to table
            } else {
                for (e in dims.indices) {
                    val erdName = "$nameUpper@${e + 1}"
                    val group = files[erdName] ?: continue
                    val dim = e + if (spec.isCharaData) 1 else 0
                    val table = attach(store, v, erdName, dim, dims[e], group, cache, seen, registered, warnings)
                    if (table != null) loaded += erdName to table
                }
            }
        }
    }

    /**
     * `ConstantData.isDefinedErd`（ConstantData.cs:795-806）：对**每张**已登记的表各查一次关键字，
     * 命中就发一条 level 1 告警 —— 同一个名字落在两张表里会发两条（原版是双层 foreach，
     * 内层 `key == varname` 用序数比较，大小写敏感，`Dictionary<string,int>` 的默认比较器同源）。
     *
     * 文案 `Lang.Error.IsDefinedErdVariable`（Lang.cs:3986）没有 zhs/eng 译文
     * （`lang.emuera-zhs.xml` / `-eng.xml` 均无该 id），按官方日文原文出。
     */
    private fun checkDefinedErd(
        varname: String,
        loaded: List<Pair<String, Map<String, Int>>>,
        warnings: MutableList<String>,
    ) {
        for ((erdName, table) in loaded) {
            if (table.containsKey(varname)) warnings += isDefinedErd(varname, erdName)
        }
    }

    /**
     * 逐条挂表的隔离点（spec: ErhLoader.cs:328-332）。
     *
     * 原版对每条 `#DIM` 都套 try/catch：CodeEE（重键 / 重复登记）降级成 level 2 告警，
     * 继续装下一条。这里同形 —— 失败只让**该 erd 名 + 该下标位**不挂表，清单里留官方文案，
     * 会话装配不再被一条坏表打死。前缀 `erd名#下标位` 是本侧的定位辅助（原版靠 `ScriptPosition`
     * 指出是哪一行 `#DIM`，移植侧挂表点没有逐行位置，见类 KDoc 的已知差异）。
     *
     * 返回**本次真正登记进 `erdNameToIntDics` 等价物**的表（供 [checkDefinedErd] 扫描）；
     * 重复登记被 `seen` 吸掉、或判定失败时返回 null。**空表照样返回**（原版登记是无条件的，
     * 见 [register]）—— 空字典在 [checkDefinedErd] 的 `containsKey` 上恒为假，扫描面不受影响。
     */
    private fun attach(
        store: VariableStore,
        v: UserVar,
        erdName: String,
        dim: Int,
        extent: Int,
        group: List<Path>,
        cache: MutableMap<Path, Map<Int, String>>,
        seen: MutableSet<String>,
        registered: MutableSet<String>,
        warnings: MutableList<String>,
    ): Map<String, Int>? = try {
        register(store, v, erdName, dim, extent, group, cache, seen, registered)
    } catch (e: CodeEE) {
        warnings += "$erdName#$dim: ${e.message}"
        null
    }

    private fun register(
        store: VariableStore,
        v: UserVar,
        erdName: String,
        dim: Int,
        extent: Int,
        group: List<Path>,
        cache: MutableMap<Path, Map<Int, String>>,
        seen: MutableSet<String>,
        registered: MutableSet<String>,
    ): Map<String, Int>? {
        // 同名多份声明（私有 #DIM 共用一张表）在这里就返回，够不到下面的守卫。
        // 分隔符用 NUL：路径里不会出现，避免 `A|B` 这种文件名把清单串拼成同一个键。
        val dedupe = "$erdName#$dim\u0000${group.joinToString("\u0000")}"
        if (!seen.add(dedupe)) return null
        val table = load(group, erdName, extent, cache)
        // 守卫位置与原版一致：文件循环之后、登记之前（ConstantData.cs:783-786）。
        if (!registered.add(erdName)) throw CodeEE(duplicateVariableDefine(erdName))
        // 空表**照样登记**：原版 `erdNameToIntDics.Add(varname, dictionary2)` 是无条件执行的
        // （ConstantData.cs:783-792 —— `dictionary2` 由 `dictionary` 投影而来，可以是空字典）。
        // 差别只落在报错文案上，而且两者都是原版文案、只是归属不同：
        //   登记了空表 ⇒ `keywordToInteger` 走到「<NAME>.csv 中未定义关键字」（表在、键不在）；
        //   不登记     ⇒ 提前报「不能用字符串指定（未找到 ERD 表）」（该变量压根没有表）。
        // 旧实现在这里 `return null`，把「表是空的」误报成「没有表」。
        store.registerKeywordTable(v.spec.name, dim, table)
        return table
    }

    /**
     * 官方文案：`Lang.Error.DuplicateErdKey`（Lang.cs:978，中文版
     * lang.emuera-zhs.xml:199）。实参序 = 变量名、关键字、首次定义的文件、当前文件
     * （调用点 ConstantData.cs:773）。
     */
    private fun duplicateErdKey(varname: String, keyword: String, file1: Path, file2: Path): String =
        "变量\"$varname\"的替换名\"$keyword\"的定义重复。（文件1 - $file1）（文件2 - $file2）"

    /** 官方文案：`Lang.Error.DuplicateVariableDefine`（Lang.cs:982，中文版 xml:200；调用点 :785）。 */
    private fun duplicateVariableDefine(varname: String): String = "变量${varname}的定义重复"

    /**
     * 官方文案：`Lang.Error.IsDefinedErdVariable`（Lang.cs:3986；调用点 ConstantData.cs:803）。
     * 实参序 = 声明名、已登记的 erd 表名。**无 zhs/eng 译文**，用日文原文。
     */
    private fun isDefinedErd(varname: String, erdName: String): String =
        "変数名\"$varname\"は既にERD変数\"$erdName\"の定義に使われており、予期せぬ配列参照を引き起こす場合があります"
}
