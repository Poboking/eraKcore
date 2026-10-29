package com.eratw.port.core.vars

/**
 * CSV 常量名字表（`ABLNAME` / `ITEMNAME` / `STRNAME` … 的底层数据）。
 *
 * 原版：`ConstantData.names` 声明为 `new string[30][]`（spec: ConstantData.cs:114），
 * 但只有前 29 槽按 `MaxDataList[i]` 分配并装载（spec: ConstantData.cs:676-680，长度可被
 * VariableSize.CSV 改写，spec: ConstantData.cs:309-551）；关键字反查表
 * `nameToIntDics[29]`（spec: ConstantData.cs:120, 711-724）。本类把"名字数组"与
 * "关键字反查"放在一起，M3 CSV 装载层逐个 [set] / [setKeyword] 填充。
 */
class CsvNameTable(val slot: Int, val capacity: Int) {
    private val names = arrayOfNulls<String>(capacity)
    private val keywords = HashMap<String, Int>(capacity)

    operator fun get(index: Int): String = names[index] ?: ""

    /**
     * 只写名字、不建关键字反查。STR 槽（10）专用：原版 `nameToIntDics` 建表时
     * `j == 10` 直接 continue（ConstantData.cs:713-716），而 `names[10]` 仍由
     * loadDataTo 填充（ConstantData.cs:1802）。
     */
    fun setName(index: Int, name: String) {
        if (index < 0 || index >= capacity) {
            throw CodeEE("CSV 槽位 $slot 的名字下标 $index 越界（长度 $capacity）")
        }
        names[index] = name
    }

    /** CSV 第 1 列的名字；index 即该表内的编号。 */
    fun set(index: Int, name: String) {
        setName(index, name)
        if (name.isNotEmpty()) keywords.putIfAbsent(name, index)
    }

    /** CSV 第 2 列起的别名/显式编号；已存在同名不改写（`!ContainsKey` 先输原则）。spec: ConstantData.cs:725-735 */
    fun setKeyword(keyword: String, index: Int) {
        if (keyword.isEmpty()) return
        keywords.putIfAbsent(keyword, index)
    }

    fun keywordIndex(keyword: String): Int? = keywords[keyword]

    /** 只读关键字表视图（`#DIM` 装载期折叠 `GETNUM(...)` 用；spec: ConstantData.cs:849-875）。 */
    fun keywordMap(): Map<String, Int> = keywords

    /** 只读视图的长度（= MaxDataList[slot]）。 */
    val size: Int get() = capacity

    fun copyNamesTo(dest: StringArraySink) {
        for (i in 0 until capacity) names[i]?.let { dest.set(i, it) }
    }

    fun clear() {
        names.fill(null)
        keywords.clear()
    }
}

/** 让 [CsvNameTable] 不必依赖具体存储即可回填（STR 初始化用）。 */
fun interface StringArraySink {
    fun set(index: Int, value: String)
}

/**
 * 系统变量元信息表。
 *
 * 构造后只读。用户 #DIM 变量**不**在这里注册（它们是运行期声明，见 [UserVarSpace]）。
 */
class VariableRegistry(
    val sizes: VariableSizes = VariableSizes(),
    val gameBase: GameBaseData = GameBaseData(),
) {
    private val byName = LinkedHashMap<String, VarDef>()

    /** 29 组 CSV 名字表，下标 = CSV 槽位（0..28）。 */
    val csvTables: Array<CsvNameTable> = Array(29) { CsvNameTable(it, sizes.maxDataList[it]) }

    /**
     * 表名 → 表，**构造期一次建好、运行期只读**（PERF 2026-10-26）。
     *
     * 旧 [csvTable] 是「线性扫 29 张表 × 每次 `substringBefore('.')` 新建 String ×
     * `equals(ignoreCase)`」，而它的调用方全在热路径上（关键字下标、PRINT_PALAM 的
     * 100 个参数逐项取表、ITEM 名表）：真机采样里 `csvTable` 独占 35% 脚本时间
     * （`jfr-turn.jfr`：692/1989 样本入栈）。表集合构造后不再变（`csvTables` 是定长数组、
     * 槽位名来自 `SystemLayout.csvFiles` 常量表），故映射可一次定死。
     *
     * 键同时收「原文」与「小写」两种写法：调用方传的多是常量原文（`ITEM` / `PALAM` /
     * `CDFLAG1`），命中走一次 `HashMap` 查表（String 的 hashCode 已缓存，零分配）；
     * 未命中才回落到原来的线性扫描（未知表名照旧抛 `CodeEE`）。
     * 只读表 ⇒ 多线程查安全（不在运行期 `getOrPut`）。
     */
    private val csvTableIndex: Map<String, CsvNameTable> = buildMap {
        for (slot in csvTables.indices) {
            val raw = SystemLayout.csvFiles[slot].substringBefore('.')
            put(raw, csvTables[slot])
            put(raw.lowercase(), csvTables[slot])
        }
    }

    init {
        buildIntegerArrays()
        buildStringArrays()
        buildCharacterArrays()
        buildLocals()
        buildPseudo()
        buildCsvConstants()
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    private fun add(def: VarDef) {
        val key = def.name.uppercase()
        if (byName.containsKey(key)) throw CodeEE("变量名重复注册：${def.name}")
        byName[key] = def
    }

    private fun buildIntegerArrays() {
        for ((name, code) in SystemLayout.int1D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.G_INT1,
                    index = idx,
                    dims = intArrayOf(sizes.int1D[idx]),
                    isConst = VariableCode.isConst(code),
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = VariableCode.isGlobal(code),
                    keywordDim = SystemLayout.keywords[name]?.dim ?: -1,
                    keywordTable = SystemLayout.keywords[name]?.table,
                    keywordTableByDim = SystemLayout.keywords[name]?.byDim ?: false,
                ),
            )
        }
        for ((name, code) in SystemLayout.int2D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.G_INT2,
                    index = idx,
                    dims = sizes.int2D[idx].copyOf(),
                    isConst = VariableCode.isConst(code),
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                ),
            )
        }
        for ((name, code) in SystemLayout.int3D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.G_INT3,
                    index = idx,
                    dims = sizes.int3D[idx].copyOf(),
                    isConst = VariableCode.isConst(code),
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                ),
            )
        }
    }

    private fun buildStringArrays() {
        for ((name, code) in SystemLayout.str1D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.G_STR1,
                    index = idx,
                    dims = intArrayOf(sizes.str1D[idx]),
                    isConst = VariableCode.isConst(code),
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = VariableCode.isGlobal(code),
                    keywordDim = SystemLayout.keywords[name]?.dim ?: -1,
                    keywordTable = SystemLayout.keywords[name]?.table,
                    keywordTableByDim = SystemLayout.keywords[name]?.byDim ?: false,
                ),
            )
        }
        for ((name, code) in SystemLayout.str0D) {
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.G_STR0,
                    index = 0,
                    dims = intArrayOf(),
                    isConst = VariableCode.isConst(code),
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                ),
            )
        }
    }

    private fun buildCharacterArrays() {
        for ((name, code) in SystemLayout.charaInt0D) {
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.C_INT0,
                    index = VariableCode.lowIndex(code),
                    dims = intArrayOf(),
                    isConst = false,
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                ),
            )
        }
        for ((name, code) in SystemLayout.charaStr0D) {
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.C_STR0,
                    index = VariableCode.lowIndex(code),
                    dims = intArrayOf(),
                    isConst = false,
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                ),
            )
        }
        for ((name, code) in SystemLayout.charaInt1D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.C_INT1,
                    index = idx,
                    dims = intArrayOf(sizes.charaInt1D[idx]),
                    isConst = false,
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                    keywordDim = SystemLayout.keywords[name]?.dim ?: -1,
                    keywordTable = SystemLayout.keywords[name]?.table,
                    keywordTableByDim = SystemLayout.keywords[name]?.byDim ?: false,
                ),
            )
        }
        for ((name, code) in SystemLayout.charaStr1D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.C_STR1,
                    index = idx,
                    dims = intArrayOf(sizes.charaStr1D[idx]),
                    isConst = false,
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                    keywordDim = SystemLayout.keywords[name]?.dim ?: -1,
                    keywordTable = SystemLayout.keywords[name]?.table,
                    keywordTableByDim = SystemLayout.keywords[name]?.byDim ?: false,
                ),
            )
        }
        for ((name, code) in SystemLayout.charaInt2D) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.C_INT2,
                    index = idx,
                    dims = sizes.charaInt2D[idx].copyOf(),
                    isConst = false,
                    isSavedata = VariableCode.isSavedata(code, name),
                    isGlobal = false,
                    keywordDim = SystemLayout.keywords[name]?.dim ?: -1,
                    keywordTable = SystemLayout.keywords[name]?.table,
                    keywordTableByDim = SystemLayout.keywords[name]?.byDim ?: false,
                ),
            )
        }
    }

    private fun buildLocals() {
        for ((name, code) in SystemLayout.localInt) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.L_INT,
                    index = idx,
                    // 默认长度吃 VariableSize.csv（LOCAL/ARG 行），可被函数参数声明拉大（spec: LabelDictionary.cs:66-67）
                    dims = intArrayOf(sizes.int1D[idx]),
                    isConst = false,
                    isSavedata = false,
                    isGlobal = false,
                ),
            )
        }
        for ((name, code) in SystemLayout.localStr) {
            val idx = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.L_STR,
                    index = idx,
                    dims = intArrayOf(sizes.str1D[idx]),
                    isConst = false,
                    isSavedata = false,
                    isGlobal = false,
                ),
            )
        }
    }

    private fun buildPseudo() {
        for ((name, code, kind) in SystemLayout.pseudo) {
            val pseudoDim = VariableCode.dimension(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = if (VariableCode.isString(code)) VarType.STR else VarType.INT,
                    storage = Storage.PSEUDO,
                    index = 0,
                    dims = IntArray(pseudoDim), // RAND 是 1 维（带一个上界参数），其余 0 维
                    isConst = VariableCode.isConst(code),
                    isSavedata = false,
                    isGlobal = false,
                    pseudo = kind,
                ),
            )
        }
        for ((name, code) in SystemLayout.gameBaseStr) {
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.PSEUDO,
                    index = 0,
                    dims = intArrayOf(),
                    isConst = true, // ConstantToken 赋值抛错，spec: VariableData.cs:602-634
                    isSavedata = false,
                    isGlobal = false,
                    pseudo = PseudoKind.GAMEBASE_STR,
                ),
            )
        }
        for ((name, code) in SystemLayout.gameBaseInt) {
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.INT,
                    storage = Storage.PSEUDO,
                    index = 0,
                    dims = intArrayOf(),
                    isConst = true,
                    isSavedata = false,
                    isGlobal = false,
                    pseudo = PseudoKind.GAMEBASE_INT,
                ),
            )
        }
    }

    private fun buildCsvConstants() {
        for ((name, code) in SystemLayout.csvConsts) {
            val slot = VariableCode.lowIndex(code)
            add(
                VarDef(
                    name = name,
                    code = code,
                    type = VarType.STR,
                    storage = Storage.CSV_CONST,
                    index = slot,
                    dims = intArrayOf(sizes.maxDataList[slot]),
                    isConst = true,
                    isSavedata = false,
                    isGlobal = false,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 名字归一记忆（2026-10-23 真机性能点）。
     *
     * 运行期每次变量访问都会落到 [def]；`String.uppercase()` 在 ART 上走 ICU CaseMapper
     * （逐字符 `String.charAt` + `Character.toUpperCaseImpl`），世界初始化那页实测占
     * VM 线程采样 9%。变量名集合有限，用一张名字表把归一结果记下来，命中路径只剩一次
     * String.hashCode（ART 自带缓存）+ equals。
     */
    private val nameKeys = HashMap<String, String>(4096)

    private fun keyOf(name: String): String {
        nameKeys[name]?.let { return it }
        val u = name.uppercase()
        nameKeys[name] = u
        return u
    }

    /**
     * 原文名 → 定义的单跳索引（PERF 2026-10-26）。
     *
     * [def] 原来走 `byName[keyOf(name)]`：先查一次大写归一记忆表、再用大写键查一次
     * `byName` —— 每次变量访问两次 `HashMap.get`。真机采样里 `keyOf`+`def`+`byName`
     * 三级合计占 12%（`jfr-turn2.jfr`），而语料里的变量名**本来就写成大写**
     * （`CFLAG` / `TALENT` / `MAIN_MAP`），原文即归一结果。
     *
     * 本表在构造完成后按需建一次（`add()` 只在构造期被调用，`byName` 之后不再变），
     * 同时收 [VarDef.name] 原文与 [VarDef.upperName] 两个键 ⇒ 命中路径一次 `HashMap.get`。
     * 未命中（例如语料写成小写 `cflag`）仍回落 [keyOf] 那条原路径，语义逐字不变。
     * `lazy(PUBLICATION)`：并发首用时各自建一份同内容表，无锁、无副作用。
     */
    private val byRawName: Map<String, VarDef> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val m = HashMap<String, VarDef>(byName.size * 2 + 8)
        for (d in byName.values) {
            m[d.name] = d
            m[d.upperName] = d
        }
        m
    }

    /**
     * [def] 的**单条记忆**（PERF 2026-10-26）。
     *
     * 同一个变量名在环体里被连续访问上百万次（`CFLAG` / `TALENT` …），而 [def] 每次都要
     * 一次 `HashMap.get`（真机采样：`def` 入栈 12%、`HashMap.getNode` 独占 40%）。
     * 名字串来自编译产物里的同一个 `VarRef` ⇒ 比较走引用快路径。
     *
     * **判据是「命中值自己的名字与请求相同」而不是「记住上次请求的名字」**：
     * 只读一个引用字段（引用读在 JVM 上不会撕裂），并发下最坏是未命中而重算，
     * 不会把 A 变量的定义当成 B 的返回。注册表构造完成后不可变（`add()` 只由
     * 构造期各 build* 调用）⇒ 这条记忆不需要任何失效点。
     * 查不到（null）不记忆：未知名字本来就该走完整路径抛错，且它不在热路径上。
     */
    private var lastDef: VarDef? = null

    /** 名字大小写不敏感（原版 Config.StringComparison 默认忽略大小写）。 */
    fun def(name: String): VarDef? {
        val memo = lastDef
        if (memo != null && (memo.name == name || memo.upperName == name)) return memo
        val d = byRawName[name] ?: byName[keyOf(name)]
        if (d != null) lastDef = d
        return d
    }

    fun mustDef(name: String): VarDef =
        def(name) ?: throw CodeEE("未定义的变量 $name")

    fun all(): Collection<VarDef> = byName.values

    /**
     * PC 存档写侧的枚举序 = `varTokenDic` 的 Add 序（VariableData.cs:3014-3179；
     * 消费点 = VariableData.cs:3982 / CharacterData.cs:515 的 foreach 字典枚举）。
     *
     * [all] 返回的是本类构造的注册序，按存储形态分组（1D INT → 2D INT → 3D INT →
     * 1D/0D STR → 角色 → 局部 → 伪变量 → CSV/GAMEBASE 常量），与 C# 的逐条 Add 序不同：
     * C# 的 SAVESTR/TSTR（:3068-3069）夹在 1D 整数（:3014-3067）与 2D/3D 整数（:3103-3110）
     * 之间。未改档靠 sourceFile 原字节短路看不出来；改档后整档重写，条目序必须用本序
     * 才与 PC 一致（specs/save-format.md §5.3 第 3 条）。
     *
     * 组内各表的相对序已与 C# 对齐（SystemLayout 的 KDoc），只有两处由本函数显式纠正：
     * str1D 的 C# 序是 SAVESTR, TSTR, STR, RESULTS, GLOBALS（:3068-3072），而
     * SystemLayout.str1D 是 SAVESTR, STR, RESULTS, GLOBALS, TSTR；GAMEBASE_AUTHER
     * 先于 GAMEBASE_AUTHOR（:3141-3142）。ITEMPRICE 在 :3111 单独注册（TA/TB 之后）。
     *
     * LOCAL/ARG/LOCALS/ARGS 在 C# 落 localvarTokenDic（:3181-3187）、不在 varTokenDic，
     * 它们永远不会成为存档条目（LOCAL_FLAG/EXTENDED 位 ⇒ isSavedata=false），
     * 由尾部的完整性兜底追加，保证本序不丢任何已注册变量。
     */
    fun allInVarTokenDicOrder(): List<VarDef> {
        val out = ArrayList<VarDef>(byName.size)
        val seen = HashSet<String>(byName.size)
        fun push(name: String) {
            if (seen.add(name.uppercase())) def(name)?.let { out.add(it) }
        }
        // :3014-3067 —— 1 维整数（GLOBAL/RANDDATA 也在本段；ITEMPRICE 见 :3111）
        for ((name, _) in SystemLayout.int1D) if (name != "ITEMPRICE") push(name)
        // :3068-3069 —— SAVESTR、TSTR 在 1D 整数之后、2D 整数之前
        push("SAVESTR")
        push("TSTR")
        // :3070-3072 —— 剩余 1 维字符串（STR, RESULTS, GLOBALS 的组内相对序两表一致）
        for ((name, _) in SystemLayout.str1D) push(name)
        // :3073 —— 0 维字符串
        for ((name, _) in SystemLayout.str0D) push(name)
        // :3074-3102 —— 角色变量：0D 整数 → 1D 整数 → 0D 字符串 → 1D 字符串 → 2D 整数
        for ((name, _) in SystemLayout.charaInt0D) push(name)
        for ((name, _) in SystemLayout.charaInt1D) push(name)
        for ((name, _) in SystemLayout.charaStr0D) push(name)
        for ((name, _) in SystemLayout.charaStr1D) push(name)
        for ((name, _) in SystemLayout.charaInt2D) push(name)
        // :3103-3110 —— 2D / 3D 整数
        for ((name, _) in SystemLayout.int2D) push(name)
        for ((name, _) in SystemLayout.int3D) push(name)
        // :3111 —— ITEMPRICE（Int1DConstantToken）
        push("ITEMPRICE")
        // :3112-3139 —— CSV 常量名字表
        for ((name, _) in SystemLayout.csvConsts) push(name)
        // :3140-3152 —— GAMEBASE（AUTHER 先于 AUTHOR，:3141-3142）
        push("GAMEBASE_AUTHER")
        for ((name, _) in SystemLayout.gameBaseStr) push(name)
        for ((name, _) in SystemLayout.gameBaseInt) push(name)
        // :3153-3179 —— 伪变量（DebugMode 与否只改实现类，名字与序一致）
        for ((name, _, _) in SystemLayout.pseudo) push(name)
        // 完整性兜底：registry 里 varTokenDic 序没覆盖到的（LOCAL 族及将来新增）追加尾部。
        for (d in byName.values) if (seen.add(d.name.uppercase())) out.add(d)
        return out
    }

    fun csvTable(table: String): CsvNameTable {
        csvTableIndex[table]?.let { return it }
        // 回落：表名不是构造期那 29 个槽位名的原文/小写形态（历史调用点可能传大小写混写）。
        // 线性扫描保持原判据（`substringBefore('.')` + 大小写不敏感），语义与原实现逐字相同。
        return csvTables.firstOrNull { SystemLayout.csvFiles[it.slot].substringBefore('.').equals(table, true) }
            ?: throw CodeEE("未知的 CSV 名字表 $table")
    }

    /**
     * 全部内置关键字变量的表（变量名大写 → 关键字表）。
     *
     * 消费点 = `GETNUM(变量, "关键字")` 的装载期折叠（spec: `FunctionMethodCreator.cs:4814-4832`
     * 经 `ConstantData.TryKeywordToInteger`）。表名 → 槽位顺序见 [com.eratw.port.core.data.csv.GameDataLoader.SLOT_FILES]。
     */
    fun systemKeywordTables(): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((name, spec) in SystemLayout.keywords) {
            // 槽位名与 `SystemLayout.csvFiles` 同源（不再抄一份 29 项映射）。
            val slot = SystemLayout.csvFiles.indexOfFirst {
                it.substringBefore('.').equals(spec.table, ignoreCase = true)
            }
            if (slot < 0) continue
            out[name] = csvTables[slot].keywordMap()
        }
        return out
    }

    /**
     * 字符串下标 → 整数下标。对应 `ConstantData.GetKeywordDictionary` 的校验逻辑
     * （spec: ConstantData.cs:914-1204）：除了表查询，还强制"关键字只能出现在指定维度上"。
     *
     * @param dim 完整下标数组中的位置（角色变量从 0=角色位置 起算）
     */
    fun keywordIndex(varName: String, dim: Int, keyword: String): Int {
        val spec = SystemLayout.keywords[varName.uppercase()]
            ?: throw CodeEE("变量 $varName 不能用字符串指定下标")
        val tableName = when {
            spec.byDim -> when (dim) {
                1 -> "CDFLAG1"
                2 -> "CDFLAG2"
                else -> throw CodeEE(
                    "变量 $varName 的第 ${dim + 1} 个参数不能用字符串指定" +
                        "（CDFLAG 请用 CDFLAGNAME1/CDFLAGNAME2）",
                )
            }
            dim != spec.dim -> throw CodeEE("变量 $varName 的第 ${dim + 1} 个参数不能用字符串指定")
            else -> spec.table
        }
        val table = csvTable(tableName)
        return table.keywordIndex(keyword)
            ?: throw CodeEE("$tableName 中未定义关键字 \"$keyword\"")
    }
}
