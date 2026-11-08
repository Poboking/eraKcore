package com.eratw.port.core.fn

import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParseException
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.xml.XmlPool
import java.util.Locale

/**
 * MAP / DataTable 家族（[registerMapMethods] / [registerDataTableMethods]）。
 * **文件所有者：A3**（M3-B 第二波）。
 *
 * 落地名单（33 个内建函数；合同里的第 34 条 `DT_COLUMN_OPTIONS` 是语句，见下）：
 * - MAP（12）：MAP_CREATE / MAP_EXIST / MAP_RELEASE / MAP_SET / MAP_HAS / MAP_REMOVE /
 *   MAP_CLEAR / MAP_SIZE / MAP_GET / MAP_GETKEYS / MAP_TOXML / MAP_FROMXML
 * - DT（21）：DT_CREATE / DT_EXIST / DT_RELEASE / DT_CLEAR / DT_NOCASE / DT_COLUMN_ADD /
 *   DT_COLUMN_NAMES / DT_COLUMN_EXIST / DT_COLUMN_REMOVE / DT_COLUMN_LENGTH / DT_ROW_ADD /
 *   DT_ROW_SET / DT_ROW_REMOVE / DT_ROW_LENGTH / DT_CELL_GET / DT_CELL_ISNULL /
 *   DT_CELL_GETS / DT_CELL_SET / DT_SELECT / DT_TOXML / DT_FROMXML
 *
 * 注意 `DT_COLUMN_OPTIONS` **不是函数**，是语句（FunctionIdentifier.cs:2531-2578），
 * 归语句层（StatementExecutor），不在本文件范围内；本文件只提供它的 DefaultValue 落地入口
 * [dtColumnOptionsDefault]。它的成功路径不写 RESULT（:2554-2577），基准
 * `pc-golden-root.txt:596` 的 `DT_COLUMN_OPTIONS=1` 其实是同一脚本里
 * `MAP_CREATE "GM2"`（GOLDEN_10_XMLMAP.ERB:67）这条 METHOD 语句把返回值 1 写进
 * RESULT:0 之后留下的（METHOD 语句"整型写 RESULT"：FunctionIdentifier.cs:584-604）。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - MAP：MapManagementMethod :2765-2808、MapDataOperationMethod :2810-2888、
 *   MapGetStrMethod :2890-3029、MapFromXmlMethod :3031-3077
 * - DT：DataTableManagementMethod :1926-2006、DataTableColumnManagementMethod :2008-2124、
 *   DataTableRowSetMethod :2126-2323、DataTableLengthMethod :2325-2353、
 *   DataTableRowRemoveMethod :2355-2430、DataTableCellGetMethod :2432-2543、
 *   DataTableCellSetMethod :2545-2620、DataTableSelectMethod :2622-2677、
 *   DataTableToXmlMethod :2679-2718、DataTableFromXmlMethod :2720-2763
 *
 * 容器语义（全部按 C# 原样）：
 * - MAP 名与键都是 **区分大小写** 的（`Dictionary<string,string>` 默认 ordinal 比较器，
 *   :2787/:2804），键序按插入序（`LinkedHashMap` 复刻 .NET Dictionary 的实际枚举序，
 *   基准 MAP_KEYS=K1,K2 与 MAP_TOXML 的 K1→K2 都依赖它）。
 * - DT 是列式表：列 = 名字 + 类型（1..5 = int8/int16/int32/int64/string，
 *   `builtInDTTypes` Utils.cs:116-171 + `IntToType` :198-205；名字形式 `NameToType` :207-214）+
 *   AllowDBNull + DefaultValue；行 = 单元格列表，`null` 表示 .NET 的 `DBNull.Value`。
 *   建表时 `CaseSensitive = true`（:1993-1996），DT_NOCASE(x) 把它设成 `x == 0`（:1975）。
 *   主键固定是首列 `id`（long、AllowDBNull=false、Unique，:1997-2001）。
 *   这个 flag 在本实现里管两件事（与既有钉桩同源，见 [DtTable.colIndex]）：
 *   ① **列名查找**的折叠口径（`MapDataTableMethodsTest` 的「C1 在敏感位为真时不命中」、
 *   `MapDataAuditTest` 的「DT_NOCASE 作用面是列名查找」）；
 *   ② **单元格字符串比较**（DT_SELECT 的 filter 判等与 sort 定序），②的唯一口径实现是
 *   [DtStringComparer]（FN-06 / FN-07 的收口点，残差登记见它的 KDoc 与 [dtSelect]）。
 *
 * DT_TOXML 的字节产物：.NET `DataTable.WriteXmlSchema` / `WriteXml` 的复刻。
 * 基准是 `DT_TOXML_SCHEMA_LEN=954 DATA_LEN=179`（pc-golden-root.txt:613），本实现逐字节对齐
 * 这两个长度（见 [dtSchemaXml] / [dtDataXml] 的 KDoc：换行、缩进、声明、属性集都是按长度
 * oracle 反推并固定下来的，未改动基准）。
 */
internal fun registerMapMethods(r: BuiltinRegistry) {
    // ---- MAP_CREATE / MAP_EXIST / MAP_RELEASE（MapManagementMethod :2784-2806）----
    // 建表：已存在 → 0，否则建空表 → 1；EXIST → 1/0；RELEASE：存在才删，**恒返回 1**（:2793-2798）。
    r.register(BuiltinMethod("MAP_CREATE", I_, 1, 1, evalRef = { c, e -> mapCreate(c, e) }) { c, a ->
        mapCreate(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_EXIST", I_, 1, 1, evalRef = { c, e -> mapExist(c, e) }) { c, a ->
        mapExist(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_RELEASE", I_, 1, 1, evalRef = { c, e -> mapRelease(c, e) }) { c, a ->
        mapRelease(c, a.toExprs())
    })

    // ---- MAP_SET / MAP_HAS / MAP_REMOVE / MAP_CLEAR / MAP_SIZE（:2852-2887）----
    // 表不存在一律 -1（:2856-2859）；其余路径返回 1（MAP_HAS 返回 1/0）。
    // MAP_REMOVE 删不存在的键也返回 1（:2877-2880 不检查返回值）。
    r.register(BuiltinMethod("MAP_SET", I_, 3, 3, evalRef = { c, e -> mapSet(c, e) }) { c, a ->
        mapSet(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_HAS", I_, 2, 2, evalRef = { c, e -> mapHas(c, e) }) { c, a ->
        mapHas(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_REMOVE", I_, 2, 2, evalRef = { c, e -> mapRemove(c, e) }) { c, a ->
        mapRemove(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_CLEAR", I_, 1, 1, evalRef = { c, e -> mapClear(c, e) }) { c, a ->
        mapClear(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_SIZE", I_, 1, 1, evalRef = { c, e -> mapSize(c, e) }) { c, a ->
        mapSize(c, a.toExprs())
    })

    // ---- MAP_GET / MAP_GETKEYS / MAP_TOXML（MapGetStrMethod :2944-3028）----
    r.register(BuiltinMethod("MAP_GET", S_, 2, 2, evalRef = { c, e -> mapGet(c, e) }) { c, a ->
        mapGet(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_GETKEYS", S_, 1, 3, evalRef = { c, e -> mapGetKeys(c, e) }) { c, a ->
        mapGetKeys(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_TOXML", S_, 1, 1, evalRef = { c, e -> mapToXml(c, e) }) { c, a ->
        mapToXml(c, a.toExprs())
    })

    // ---- MAP_FROMXML（:3044-3076）：目标表不存在 → 0（:3048-3051，先判存在再解析）----
    r.register(BuiltinMethod("MAP_FROMXML", I_, 2, 2, evalRef = { c, e -> mapFromXml(c, e) }) { c, a ->
        mapFromXml(c, a.toExprs())
    })

    // ---- 4.981 上游新增 MAP 增强集（自改说明 :34-41；reference/emuera-cs 零命中）----
    // 权威 = spec（builtin-functions.yaml:2241-2271，源 fork-src Creator.Method.cs:2434-2602）：
    // 返回码/边界逐条对 spec 落地——MERGE 缺表 0、REMOVEIF 缺表 0/非法条件 -1/成功返回删除条数、
    // FINDKEY 缺表 "" + 写 RESULT + 无 VAL_NE 分支（未列条件落 switch 之外）。语料只有注释里的
    // 等价实现（`_List.ERB:47/176`、`:49/340`、`:53/369`、`STRINGSET.ERB:26`），无真实调用点。
    // 参数序与语料注释一致：`MAP_FINDKEY "listName", val, "VAL_EQ"`、`MAP_REMOVEIF "listName", val, "VAL_EQ"`。
    r.register(BuiltinMethod("MAP_MERGE", I_, 2, 2, evalRef = { c, e -> mapMerge(c, e) }) { c, a ->
        mapMerge(c, a.toExprs())
    })
    r.register(BuiltinMethod("MAP_REMOVEIF", I_, 3, 3, evalRef = { c, e -> mapRemoveIf(c, e) }) { c, a ->
        mapRemoveIf(c, a.toExprs())
    })
    // 元数按 fork 的 `argumentTypeArray = [String, String, String]`（Creator.Method.cs:2505-2510）
    // 恰好 3 参；specs `name, matchValue, mode` 同口径。
    r.register(BuiltinMethod("MAP_FINDKEY", S_, 3, 3, evalRef = { c, e -> mapFindKey(c, e) }) { c, a ->
        mapFindKey(c, a.toExprs())
    })
    // fork 的实参表 3 槽、OmitStart=1（spec builtin-functions.yaml:2270「Dimension 个数由 OmitStart=1 决定」；
    // fork-src Creator.Method.cs:2575-2602）⇒ 下界 1 走原版「至少需要1个参数」文案（FunctionMethod.cs:283）。
    r.register(BuiltinMethod("MAP_TOSTRING", S_, 1, 3, omitStart = 1, evalRef = { c, e -> mapToString(c, e) }) { c, a ->
        mapToString(c, a.toExprs())
    })
}

internal fun registerDataTableMethods(r: BuiltinRegistry) {
    // ---- 表管理（DataTableManagementMethod :1958-2004）----
    // CREATE 已存在 → 0；EXIST → 1/0；RELEASE 恒 1；CLEAR 只清行、保留列 → 1，缺表 -1；
    // NOCASE(x)：CaseSensitive = (x == 0) → 1，缺表 -1（:1972-1978）。
    r.register(BuiltinMethod("DT_CREATE", I_, 1, 1, evalRef = { c, e -> dtCreate(c, e) }) { c, a ->
        dtCreate(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_EXIST", I_, 1, 1, evalRef = { c, e -> dtExist(c, e) }) { c, a ->
        dtExist(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_RELEASE", I_, 1, 1, evalRef = { c, e -> dtRelease(c, e) }) { c, a ->
        dtRelease(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_CLEAR", I_, 1, 1, evalRef = { c, e -> dtClear(c, e) }) { c, a ->
        dtClear(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_NOCASE", I_, 2, 2, evalRef = { c, e -> dtNoCase(c, e) }) { c, a ->
        dtNoCase(c, a.toExprs())
    })

    // ---- 列（DataTableColumnManagementMethod :2067-2122）----
    r.register(BuiltinMethod("DT_COLUMN_ADD", I_, 2, 4, evalRef = { c, e -> dtColumnAdd(c, e) }) { c, a ->
        dtColumnAdd(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_COLUMN_NAMES", I_, 1, 2, evalRef = { c, e -> dtColumnNames(c, e) }) { c, a ->
        dtColumnNames(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_COLUMN_EXIST", I_, 2, 2, evalRef = { c, e -> dtColumnExist(c, e) }) { c, a ->
        dtColumnExist(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_COLUMN_REMOVE", I_, 2, 2, evalRef = { c, e -> dtColumnRemove(c, e) }) { c, a ->
        dtColumnRemove(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_COLUMN_LENGTH", I_, 1, 1, evalRef = { c, e -> dtColumnLength(c, e) }) { c, a ->
        dtColumnLength(c, a.toExprs())
    })

    // ---- 行（DataTableRowSetMethod :2253-2322 / RowRemoveMethod :2383-2428）----
    // Add 第一份形态 ArgTypes = String,VariadicString,VariadicAny（3 槽）、OmitStart=1
    // ⇒ 固定前缀 1、组大小 2、个数下界 1；第二份 4 槽数组形态精确实参 4
    // （FunctionMethodCreator.cs:2141-2166，MatchVariadicGroup 判据 FunctionMethod.cs:186-193）。
    r.register(BuiltinMethod("DT_ROW_ADD", I_, 1, -1, variadicGroup = VariadicGroup(1, 2, 4, 1), evalRef = { c, e -> dtRowAdd(c, e) }) { c, a ->
        dtRowAdd(c, a.toExprs())
    })
    // Set 分支第一份 ArgTypeList 是 4 槽 String,Int,VariadicString,VariadicAny + MatchVariadicGroup，
    // OmitStart 缺省 -1 ⇒ 下限 = 槽数 4、上限变长（FunctionMethodCreator.cs:2168-2199，注册在 :9267；
    // 个数判据 FunctionMethod.cs:183/266）；第二份 5 槽数组形态单列。2/3 参在原版解析期就报
    // ArgsCountNotMatches —— CMP-70 裁决（C# 权威）把注册下界从 2 抬回 4。
    // 变参的「列名,值」成对约束（MatchVariadicGroup，:186-193）原是运行期 CodeEE
    // （dtRowAdd :1128 / dtRowSet :1214），本波起由装载期接线：声明 [VariadicGroup] 后
    // `tools/erbcc` 装载期即上屏 ParserMediator 告警（见 PairedArgLoadCheck.kt）。
    // 运行期 CodeEE 保留为**非 erbcc 装载路径**（直接 evalRef 调用 / 补丁编译装置）的兜底：
    // 对 erbc 产物相位它已成不可达防线，但删掉会让直接构造 Expr 列表的调用静默少写一对。
    // 报错文案两侧一致（MapDataAuditTest 的审计 KDoc 登记相位差已闭合）。
    r.register(BuiltinMethod("DT_ROW_SET", I_, 4, -1, variadicGroup = VariadicGroup(2, 2, 5, 4), evalRef = { c, e -> dtRowSet(c, e) }) { c, a ->
        dtRowSet(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_ROW_REMOVE", I_, 2, 3, evalRef = { c, e -> dtRowRemove(c, e) }) { c, a ->
        dtRowRemove(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_ROW_LENGTH", I_, 1, 1, evalRef = { c, e -> dtRowLength(c, e) }) { c, a ->
        dtRowLength(c, a.toExprs())
    })

    // ---- 单元格（DataTableCellGetMethod :2464-2542 / CellSetMethod :2568-2619）----
    r.register(BuiltinMethod("DT_CELL_GET", I_, 3, 4, evalRef = { c, e -> dtCellGet(c, e) }) { c, a ->
        dtCellGet(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_CELL_ISNULL", I_, 3, 4, evalRef = { c, e -> dtCellIsNull(c, e) }) { c, a ->
        dtCellIsNull(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_CELL_GETS", S_, 3, 4, evalRef = { c, e -> dtCellGets(c, e) }) { c, a ->
        dtCellGets(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_CELL_SET", I_, 3, 5, evalRef = { c, e -> dtCellSet(c, e) }) { c, a ->
        dtCellSet(c, a.toExprs())
    })

    // ---- SELECT / TOXML / FROMXML（:2644-2676 / :2699-2717 / :2734-2762）----
    r.register(BuiltinMethod("DT_SELECT", I_, 1, 4, evalRef = { c, e -> dtSelect(c, e) }) { c, a ->
        dtSelect(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_TOXML", S_, 1, 2, evalRef = { c, e -> dtToXml(c, e) }) { c, a ->
        dtToXml(c, a.toExprs())
    })
    r.register(BuiltinMethod("DT_FROMXML", I_, 3, 3, evalRef = { c, e -> dtFromXml(c, e) }) { c, a ->
        dtFromXml(c, a.toExprs())
    })
}

// ======================================================================
// 取值辅助
// ======================================================================

private val I_ = OperandType.INT
private val S_ = OperandType.STR

/** 值路径 → 表达式路径的适配：值求值入口收到的 [Value] 包成字面量，核心实现只写一份。 */
private fun List<Value?>.toExprs(): List<Expr?> = map { v ->
    when (v) {
        null -> null
        is Value.IntV -> IntLiteral(v.v)
        is Value.StrV -> StrLiteral(v.v)
        // 浮点值包成 FloatLiteral（4.981 升版；写回路径由各被调函数自行拒绝）
        is Value.FloatV -> FloatLiteral(v.v)
    }
}

private fun List<Expr?>.strAt(c: BuiltinContext, i: Int): String = this[i]?.getStrValue(c) ?: ""

private fun List<Expr?>.intAt(c: BuiltinContext, i: Int): Long = this[i]?.getIntValue(c) ?: 0L

/** 第 [i] 个参数是否字符串操作数（对应原版 `GetOperandType() == typeof(string)`）。 */
private fun List<Expr?>.isStrAt(i: Int): Boolean = this[i]?.operandType == OperandType.STR

/**
 * 数组型引用参数。
 *
 * 原版 `VariableTerm.Identifier.GetArray()` 直接返回**整条数组**（:2078/:2282/:2657），
 * 书写时给的显式下标被忽略（如 `MAP_GETKEYS("M", RESULTS:5, 1)` 也从 RESULTS:0 写起）。
 * 这里照抄：只用变量名，不看下标。
 */
private class ArrArg(val name: String) {
    fun length(c: BuiltinContext): Int = c.store.getLength(name, -1)

    /** 一维下标（下标语义同 [DestArray]）。 */
    fun index(i: Int): LongArray = longArrayOf(i.toLong())

    fun ints(c: BuiltinContext): LongArray =
        LongArray(length(c)) { c.store.readInt(name, longArrayOf(it.toLong())) }

    fun strs(c: BuiltinContext): Array<String> =
        Array(length(c)) { c.store.readStr(name, longArrayOf(it.toLong())) }
}

private fun arrArg(e: Expr?): ArrArg? = (e as? VarRef)?.let { ArrArg(it.name) }

/** 引用型目标数组（DT_SELECT 的结果数组 / DT_TOXML 的 schema 槽）。下标语义同 [ArrArg]。 */
private class DestArray(val name: String) {
    fun index(i: Int): LongArray = longArrayOf(i.toLong())
}

private fun destArray(e: Expr?, fallback: String): DestArray = DestArray((e as? VarRef)?.name ?: fallback)

/** RESULT / RESULTS 的写入都先看长度（原版是裸数组赋值，越界在 C# 侧不可达；这里做防御）。 */
private fun writeResult0(store: VariableStore, v: Long) {
    if (store.getLength("RESULT", -1) > 0) store.writeInt("RESULT", longArrayOf(0L), v)
}

// ======================================================================
// MAP 池
// ======================================================================

/**
 * MAP 池：名字 → 键值表。
 *
 * 原版是 `Dictionary<string, Dictionary<string,string>>`（:2787）。两层都用
 * `LinkedHashMap`：.NET Dictionary 的实际枚举序就是插入序（无删除时），基准的
 * `MAP_KEYS=K1,K2`（pc-golden-root.txt:556）与 `MAP_TOXML` 的 K1→K2（:557）依赖这一点。
 * 键比较区分大小写（.NET 默认 ordinal 比较器），此处 Kotlin 默认 `String.equals` 一致。
 */
private class MapPool {
    val maps = LinkedHashMap<String, LinkedHashMap<String, String>>()
}

private fun mapPool(c: BuiltinContext): MapPool = mapPoolOf(c.store)

/** 存档层（[com.eratw.port.core.save.SaveCapture]）用的池入口：同一 aux 槽，不经 [BuiltinContext]。 */
private fun mapPoolOf(store: VariableStore): MapPool = store.aux.slot("map") { MapPool() }

/**
 * 诊断口：MAP 池快照 —— 每个 map 的（名字, 元素数），只计数、不取键值内容。
 *
 * 消费方是壳层错误报告（2026-11-01 报告收编）：语料 ResourceManager 的图像簿记
 * `RM_GMAP = "map:グラフィックス"`（ResourceManager.ERH:18，注意键**含 `map:` 前缀**，
 * 该常量的值才是传给 MAP 一族的表名），它的元素数与位图簿记位占用（壳层侧读）并排，
 * 才能判「图像过多」（RM_G_ID 行257 的 THROW）是**真耗尽**（位图位打满且图像表同步涨）
 * 还是**簿记泄漏**（释放钩子被语料 [SKIPSTART] 整块禁用、位只增不放 ——
 * ResourceManager.ERB:304-365 的既定行为）。
 *
 * 竞态口径：本函数跑在崩溃线程上、VM 线程可能还活着，迭代 [MapPool.maps] 与
 * VM 线程的写入可能撞 CME —— 诊断读数允许近似，撞了就给空表（调用方对空表如实
 * 写「读数不可用」），绝不为一份读数加锁拖住 VM 线程。
 */
fun mapPoolDiagnostics(store: VariableStore): List<Pair<String, Int>> =
    runCatching { mapPoolOf(store).maps.entries.map { it.key to it.value.size } }.getOrDefault(emptyList())

/** MAP_CREATE：已存在 0，否则建空表 1（:2799-2805）。 */
private fun mapCreate(c: BuiltinContext, e: List<Expr?>): Value {
    val pool = mapPool(c)
    val name = e.strAt(c, 0)
    if (pool.maps.containsKey(name)) return Value.IntV(0L)
    pool.maps[name] = LinkedHashMap()
    return Value.IntV(1L)
}

/** MAP_EXIST：1/0（:2791-2792）。 */
private fun mapExist(c: BuiltinContext, e: List<Expr?>): Value {
    val name = e.strAt(c, 0)
    return Value.IntV(if (mapPool(c).maps.containsKey(name)) 1L else 0L)
}

/** MAP_RELEASE：存在才删，**恒返回 1**（:2793-2798）。 */
private fun mapRelease(c: BuiltinContext, e: List<Expr?>): Value {
    mapPool(c).maps.remove(e.strAt(c, 0))
    return Value.IntV(1L)
}

/** MAP_SET：缺表 -1（:2856-2859），否则写键并返回 1（:2883/:2886）。 */
private fun mapSet(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    map[e.strAt(c, 1)] = e.strAt(c, 2)
    return Value.IntV(1L)
}

/** MAP_HAS：缺表 -1，否则 1/0（:2871-2876）。 */
private fun mapHas(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    return Value.IntV(if (map.containsKey(e.strAt(c, 1))) 1L else 0L)
}

/** MAP_REMOVE：缺表 -1；删不存在的键也返回 1（:2877-2886）。 */
private fun mapRemove(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    map.remove(e.strAt(c, 1))
    return Value.IntV(1L)
}

/** MAP_CLEAR：缺表 -1，否则清空 1（:2861-2864）。 */
private fun mapClear(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    map.clear()
    return Value.IntV(1L)
}

/** MAP_SIZE：缺表 -1，否则键数（:2867-2870）。 */
private fun mapSize(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    return Value.IntV(map.size.toLong())
}

/** MAP_GET：缺表 / 缺键都给 ""（:2948-2960）。 */
private fun mapGet(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.StrV("")
    return Value.StrV(map[e.strAt(c, 1)] ?: "")
}

/**
 * MAP_GETKEYS 三形态（:2962-3027）：
 * - 1 参：键按插入序用逗号连接（:3004-3017），不碰 RESULT。
 * - 2 参 `(name, flag)`：flag == 0 → ""（:2981-2984）；否则键写 **RESULTS**、
 *   `RESULT = 键总数`（:2996），返回 `RESULTS`（= RESULTS:0，:3001）。
 *   注意第 2 参是**开关**而不是数组，这是原版的设计（不是笔误）。
 * - 3 参 `(name, destStrArray, flag)`：flag == 0 → ""（:2969-2972）；键写目标数组、
 *   `RESULT = 键总数`，返回 ""（:2997-3000）。
 */
private fun mapGetKeys(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.StrV("")
    if (e.size <= 1) return Value.StrV(map.keys.joinToString(","))
    if (e.size == 3) {
        if (e.intAt(c, 2) == 0L) return Value.StrV("")
        // 目标不是数组变量时原版在 `arguments[1] as VariableTerm` 上裸 NRE
        // （spec: FunctionMethodCreator.cs:2968-2973）；返回空串等于把「一个键都没写」
        // 伪装成「表里没有键」，折成 CodeEE（与同族 DT_ROW_ADD 的 :919 口径一致）。
        val dest = arrArg(e[1]) ?: throw CodeEE("函数 MAP_GETKEYS 的第 2 个参数必须是字符串一维数组变量")
        val len = dest.length(c)
        var i = 0
        for (k in map.keys) {
            if (i >= len) break
            c.store.writeStr(dest.name, dest.index(i), k)
            i++
        }
        writeResult0(c.store, map.size.toLong())
        return Value.StrV("")
    }
    if (e.intAt(c, 1) == 0L) return Value.StrV("")
    val len = c.store.getLength("RESULTS", -1)
    var i = 0
    for (k in map.keys) {
        if (i >= len) break
        c.store.writeStr("RESULTS", longArrayOf(i.toLong()), k)
        i++
    }
    writeResult0(c.store, map.size.toLong())
    return Value.StrV(c.store.readStr("RESULTS", longArrayOf(0L)))
}

/**
 * MAP_TOXML：`<map><p><k>K</k><v>V</v></p>…</map>`（:3020-3025）。
 * 原版是**字符串插值**，不做任何 XML 转义（键值里的 `&`/`<` 会原样出现）——照抄。
 * 缺表 → ""（:2948-2951）。
 */
private fun mapToXml(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.StrV("")
    val sb = StringBuilder("<map>")
    for ((k, v) in map) sb.append("<p><k>").append(k).append("</k><v>").append(v).append("</v></p>")
    sb.append("</map>")
    return Value.StrV(sb.toString())
}

/**
 * MAP_MERGE：把源表的键值覆盖进目标表（自改说明 :36「将源 MAP 合并到目标 MAP（覆盖同 key）」）。
 *
 * 成功 → 1；dest 或 src **任一不存在 → 0**、不抛错
 * （权威：spec builtin-functions.yaml:2246；fork-src Creator.Method.cs:2434-2452）。
 * 键比较沿用 MAP 池的区分大小写 + 插入序（覆盖已有 key 不改变其位置）。语料零真实调用点。
 */
private fun mapMerge(c: BuiltinContext, e: List<Expr?>): Value {
    val pool = mapPool(c)
    val target = pool.maps[e.strAt(c, 0)] ?: return Value.IntV(0L)
    val source = pool.maps[e.strAt(c, 1)] ?: return Value.IntV(0L)
    for ((k, v) in source) target[k] = v
    return Value.IntV(1L)
}

/** MAP 条件集：自改说明 :37 逐字列出的六个（MAP_REMOVEIF 支持全集）。 */
private val MAP_IF_CONDS = listOf("KEY_CONTAINS", "KEY_PREFIX", "KEY_SUFFIX", "VAL_CONTAINS", "VAL_EQ", "VAL_NE")

/**
 * MAP_FINDKEY 支持的条件集：fork 的 switch **没有 VAL_NE 分支**
 * （权威：spec builtin-functions.yaml:2262；fork-src Creator.Method.cs:2505-2573）——
 * 传 VAL_NE 落到 switch 之外 ⇒ 结果空。**须原样复刻，不补齐 VAL_NE**（ADR-005）。
 */
private val MAP_FINDKEY_CONDS = listOf("KEY_CONTAINS", "KEY_PREFIX", "KEY_SUFFIX", "VAL_CONTAINS", "VAL_EQ")

/** 条件名是否在 [allowed] 内（OrdinalIgnoreCase，与 MAP 池的 String 比较器口径无关）。 */
private fun isKnownCond(cond: String, allowed: List<String>): Boolean =
    allowed.any { it.equals(cond, ignoreCase = true) }

/**
 * 按条件判定一条键值对（要求 [cond] 已在调用方所属的允许集内；未列条件落在 else → false）。
 *
 * 折叠钉 [Locale.ROOT]：下面的分支是**源码里的字面量**，与 `key_contains` 这类小写写法
 * 不是两侧同折叠的键。裸 `uppercase()` 在本闸实测的 Kotlin 2.0.20 + JDK 17 上走 ASCII 快路径、
 * 不随文化折叠，但显式 `uppercase(tr)`（以及 Java 的 `toUpperCase(tr)`）会把 `i` 折成 U+0130
 * ⇒ 六个条件全落 else。无参版的安全只是**当前标准库版本**的实现细节、不是合同
 *（本仓同类键折叠口径见 specs/verify-notes.md §16 第 20 条）。
 */
private fun mapMatch(k: String, v: String, pattern: String, cond: String): Boolean =
    when (cond.uppercase(Locale.ROOT)) {
        "KEY_CONTAINS" -> k.contains(pattern)
        "KEY_PREFIX" -> k.startsWith(pattern)
        "KEY_SUFFIX" -> k.endsWith(pattern)
        "VAL_CONTAINS" -> v.contains(pattern)
        "VAL_EQ" -> v == pattern
        "VAL_NE" -> v != pattern
        else -> false
    }

/**
 * MAP_REMOVEIF：`(map, pattern, cond)` 批量删匹配的键值对。
 *
 * 权威：spec builtin-functions.yaml:2249-2256；fork-src Creator.Method.cs:2454-2503：
 * - 映射不存在 → **0**（不抛错）；
 * - mode 非法 → **-1**（不抛错）；
 * - 成功 → **删除条数**。
 * mode 支持全集六个（含 VAL_NE），全部按区分大小写的字符串比较（[mapMatch]）。
 */
private fun mapRemoveIf(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(0L)
    val cond = e.strAt(c, 2)
    if (!isKnownCond(cond, MAP_IF_CONDS)) return Value.IntV(-1L)
    val pattern = e.strAt(c, 1)
    // 先收集再删（LinkedHashMap 迭代中删除会 CME）。
    val doomed = map.filter { (k, v) -> mapMatch(k, v, pattern, cond) }.keys.toList()
    doomed.forEach { map.remove(it) }
    return Value.IntV(doomed.size.toLong())
}

/**
 * MAP_FINDKEY：`(map, pattern, cond)` 查匹配的 key，返回逗号分隔列表。
 *
 * 权威：spec builtin-functions.yaml:2257-2264；fork-src Creator.Method.cs:2505-2573：
 * - 三参必填（fork `argumentTypeArray = [String, String, String]` 无 OmitStart，:2505-2510）；
 * - 映射不存在 → `""`（不写 RESULT，与 MAP_GETKEYS 的缺表早退同口径）；
 * - 其余路径把**匹配条数**写入 `RESULT`（实现用「结果串按逗号 split 的长度」，空串记 0）；
 * - mode 只认 [MAP_FINDKEY_CONDS] 五个：fork 的 switch **无 VAL_NE 分支**，未列条件
 *   （含 VAL_NE）落到 switch 之外 ⇒ 返回 `""` 且 `RESULT=0`，**不抛错**（须原样复刻，ADR-005）。
 * TODO(verify)：缺表时是否也写 `RESULT=0`（spec 只写「返回空串」，未及 RESULT 侧；取早退不写）。
 */
private fun mapFindKey(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.StrV("")
    val pattern = e.strAt(c, 1)
    val cond = e.strAt(c, 2)
    val keys = if (isKnownCond(cond, MAP_FINDKEY_CONDS)) {
        map.entries.filter { (k, v) -> mapMatch(k, v, pattern, cond) }.map { it.key }
    } else {
        emptyList()
    }
    val result = keys.joinToString(",")
    writeResult0(c.store, if (result.isEmpty()) 0L else result.split(',').size.toLong())
    return Value.StrV(result)
}

/**
 * MAP_TOSTRING：`(map[, sep[, kvSep]])` 序列化成字符串（自改说明 :39）。
 * 缺省分隔符按「与 MAP_FROMSTRING 对称」取 `,` 与 `=`（TODO(verify)：缺省值无手册证据）。
 * 缺表 → ""（与 MAP_TOXML 同口径）。
 */
private fun mapToString(c: BuiltinContext, e: List<Expr?>): Value {
    val map = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.StrV("")
    val sep = if (e.size >= 2) e.strAt(c, 1) else ","
    val kv = if (e.size >= 3) e.strAt(c, 2) else "="
    return Value.StrV(map.entries.joinToString(sep) { (k, v) -> k + kv + v })
}

/**
 * MAP_FROMXML（:3044-3076）：
 * 1. 目标表不存在 → 0（:3048-3051，**先判存在再解析**，所以缺表时不报 XML 错）；
 * 2. `LoadXml` 失败 → CodeEE（:3061-3064）；
 * 3. `SelectNodes("/map/p")`，每个 p 里恰好一个 `./k` 与一个 `./v` 时
 *    `dict[k.InnerText] = v.InnerXml`（:3065-3074）——键取**解码后的文本**（InnerText
 *    不含注释与处理指令；纯空白文本节点已被解析层按 `PreserveWhitespace=false` 丢弃），
 *    值取子节点的**重序列化 InnerXml**（不剥标签，但按 .NET writer 口径转义文本里的
 *    `& < >`、空元素写 `<b />`、属性引号归一双引号——见 core/xml/XmlNode.kt 的探针登记；
 *    源文本 `<v>a>b</v>` 的值回读成 `a&gt;b`，与 .NET 同构）；
 * 4. 恒返回 1。
 *
 * 解析层 2026-10-24 起走 core/xml/XmlParser（ARCH1 P3b 双轨合一；原私有 MiniXml 已删）。
 * MAP_FROMXML 的原文是 `XmlDocument.LoadXml`（:3058），故用默认模式
 * （`PreserveWhitespace = false`，纯空白文本节点丢弃），与 .NET 同口径。
 */
private fun mapFromXml(c: BuiltinContext, e: List<Expr?>): Value {
    val dest = mapPool(c).maps[e.strAt(c, 0)] ?: return Value.IntV(0L)
    val src = e.strAt(c, 1)
    val root = try {
        parseXmlRoot(src)
    } catch (ex: XmlParseException) {
        // 原版模板 Lang.Error.XmlParseError = `{0}函数:"{1}"解析错误:{2}`（Lang.cs:1374 日文源
        // `{0}関数:"{1}"の解析エラー:{2}`；zhs 表 lang/emuera.zhs.xml 的 `Error.XmlParseError`），
        // 第一个实参是 base.Name（FunctionMethodCreator.cs:3063 的 `base.Name` = 本函数名），
        // 第二个实参就是**整段源文本** ⇒ 原版本就把整个文档拼进消息、
        // 不截断也不带文件名（M4-I 契约里「报错文案要截断」的前提经回读证伪）。
        // {2} 的正文：原版是 .NET XmlException.Message（框架侧文案），本移植是 XmlParseException
        // 的中文口径——模板骨架钉死、正文不逐字节对齐（M4 起既定残差）。
        throw CodeEE("MAP_FROMXML函数:\"$src\"解析错误:${ex.message}")
    }
    if (root.name != "map") return Value.IntV(1L)
    for (p in root.children) {
        if (p !is XmlElement || p.name != "p") continue
        val ks = p.childrenByName("k")
        val vs = p.childrenByName("v")
        if (ks.size == 1 && vs.size == 1) dest[ks[0].innerText] = vs[0].innerXml()
    }
    return Value.IntV(1L)
}

// ======================================================================
// DT 数据模型
// ======================================================================

/**
 * 列类型。序号即原版 `TypeToInt` 的返回值（Utils.cs:174-201），也是 DT_COLUMN_EXIST 的返回值：
 * 1=int8 / 2=int16 / 3=int32 / 4=int64 / 5=string。基准 `DT_COLUMN_EXIST=5/0`
 * （pc-golden-root.txt:595）里的 **5 是类型号（string），不是布尔真**。
 */
private enum class DtType(val code: Long, val schema: String) {
    INT8(1, "xs:byte"),
    INT16(2, "xs:short"),
    INT32(3, "xs:int"),
    INT64(4, "xs:long"),
    STRING(5, "xs:string");

    companion object {
        /** `Utils.DataTable.IntToType`（Utils.cs:203-210）：1..5 之外返回 null。 */
        fun fromCode(v: Long): DtType? = values().firstOrNull { it.code == v }

        /** `Utils.DataTable.NameToType`（Utils.cs:212-219）：int8/int16/int32/int64/string。 */
        fun fromName(n: String): DtType? = when (n) {
            "int8" -> INT8
            "int16" -> INT16
            "int32" -> INT32
            "int64" -> INT64
            "string" -> STRING
            else -> null
        }

        /** schema 里的 `type="xs:..."` 反解（DT_FROMXML 用）。 */
        fun fromSchema(n: String): DtType? = values().firstOrNull { it.schema == n }
    }
}

/** 列。`defaultValue == null` 表示 .NET 的 `DefaultValue == DBNull.Value`（无默认值）。 */
private class DtColumn(
    var name: String,
    val type: DtType,
    var allowDBNull: Boolean = true,
    var defaultValue: Any? = null,
)

/**
 * DT 族**单元格字符串**唯一的比较口径（FN-06 / FN-07 的收口点；filter 判等、filter 定序、
 * sort 定序三条都走这里，不再各写一份）。列名查找不在它的作用面内 —— 那条按
 * [DtTable.colIndex] 走 `caseSensitive` 分岔（既有钉桩）。
 *
 * 原版链路：`FunctionMethodCreator.cs:2655` 把 filter/sort 整条交给 .NET
 * `DataTable.Select(表达式, 排序)`（内部是 DataView + Index/Sort），比较器本体在框架侧、
 * 仓库零行号。框架侧行为用 dotnet 8 探针实测（临时工程，产物不落仓）；探针运行时与游戏
 * 同源 —— 反编译工程 `reference/emuera-cs/Emuera.csproj:7` 的目标框架是 netcoreapp8.0。
 * 判据要点（`DataTable.CaseSensitive` 由 :1995 建表置真、:1975 由 DT_NOCASE 改写）：
 * - **不是** UTF-16 ordinal：`Select("", "s ASC")`（CaseSensitive=true）对
 *   `Zebra,apple,Banana,abc,ABC,abd,it's` 给 `abc,ABC,abd,apple,Banana,it's,Zebra`
 *   —— 字母优先、大小写次之（ordinal 会把 ABC/Banana/Zebra 三条大写整体提前）；
 * - **是 culture 比较**：CaseSensitive=true 走 `Comparer.Default`（当前文化、判大小写），
 *   false 走忽略大小写的同一文化（`a` 与 `A` 判 0）；残差读数见下；
 * - 拉丁文区在 en-US / ja-JP / de-DE / zh-CN / fr-FR 五个文化下**读数全同**
 *   （上面的序列五文化一致）⇒ 移植侧写死一条拉丁序即可复现原版；
 * - CJK 区随文化漂移：`あ,か,ん,漢,色,Aあ,あa,、,０` 在 en-US 与 ja-JP 得
 *   `、,０,Aあ,あ,あa,か,ん,漢,色`，zh-CN 得 `、,０,漢,色,Aあ,あ,あa,か,ん`
 *   （`DataTable.Locale` 缺省即机器文化，探针：`new DataTable().Locale.Name = zh-CN`；
 *   schema 还能用 `msdata:Locale` 覆写它）⇒ **原版这条读数本身跨机不可重复**。
 *
 * 移植侧口径（写死、无文化依赖：禁 java.text.Collator 与 Locale，L3 哈希链/E2E 610 行
 * 对拍要跨机可重复）：**两级** ——
 * 1. 主序 = 逐字符简单小写折叠后的 UTF-16 码元序（折叠用 Char.lowercaseChar()，
 *    即 Character.toLowerCase 的 1:1 简单映射，**不看文化**；折叠保持长度）；
 * 2. 次序 = 仅 CaseSensitive=true 时生效：折叠相同（⇒ 等长）后逐位比「大小写等级
 *    + 码元」，等级取 小写 0 < 大写 1 < 其它 2（对应探针里 `abc` 排在 `ABC` 前、
 *    DESC 反序得 `ABC,abc`）；等级与码元全同才判 0 ⇒ 判等退化为原文精确相等。
 *    CaseSensitive=false 时折叠相同即判 0（探针：忽略大小写下 `abc`/`ABC` 是并键，
 *    ASC 与 DESC 都给 `abc,ABC` = 原序 ⇒ 与本侧稳定排序一致）。
 *
 * 与 .NET 的残差（逐条登记，全部只在**非拉丁**区可见，语料 DT_SELECT 的 34 处 / 6 个文件的
 * 排序键都是整数列或 ASCII 列，故 E2E / boot / L3 读数不受影响）：
 * - 标点与全角：原版把 `、`(U+3001)、`０`(U+FF10) 提前，本侧按码元序把它们放到末尾；
 * - 汉字：原版按文化（zh-CN 拼音、ja-JP 假名序），本侧按码元序；
 *   例 `漢,色` 在 zh-CN 排 `あ` 之前，本侧排在其后；
 * - `ß` 与 `ss`：原版判 `ß > ss`（探针 +1），本侧 U+00DF(223) > 's'(115) 同样给 +1
 *   ⇒ 此条**无残差**（登记为已核）；
 * - 变音符不做分解等价：`é` 与 `e` 原版判同键（文化二级），本侧判不同键。
 * TODO(verify)：以上残差的**方向**取自 .NET 8 + ICU（与游戏同运行时），若某台 PC 的文化
 * 使拉丁序也偏离五文化共识，则需重钉；卡点 = 无多文化实机取证通道（本机仅 zh-CN，
 * 五文化对照靠探针内切文化）。
 */
private object DtStringComparer {

    /** 大小写等级：小写 0 < 大写 1 < 其它 2（[dtSelect] 的次序级；判读见上方探针序列）。 */
    private fun caseRank(c: Char): Int = when {
        c.isLowerCase() -> 0
        c.isUpperCase() -> 1
        else -> 2
    }

    /** 简单小写折叠（1:1、与文化无关；不处理孤立代理，代理半元按原码元参与比较）。 */
    private fun fold(c: Char): Char = c.lowercaseChar()

    /**
     * 单元格字符串比较：[caseSensitive] = `DataTable.CaseSensitive`（true 才启用次序级）。
     * 大小写**多处**混排（如 Ab 与 aB）按首个差异位定序 —— 这是移植侧写死的口径，
     * 原版 culture 在这种形态上的读数未取证（TODO(verify)：语料 DT_SELECT 无此形态，
     * 卡点 = 无第二台机器/第二文化的实机对照）。
     */
    fun compare(a: String, b: String, caseSensitive: Boolean): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val fa = fold(a[i])
            val fb = fold(b[i])
            if (fa != fb) return if (fa < fb) -1 else 1
        }
        if (a.length != b.length) return if (a.length < b.length) -1 else 1
        if (!caseSensitive) return 0
        for (i in 0 until n) {
            val ca = a[i]
            val cb = b[i]
            if (ca == cb) continue
            val ra = caseRank(ca)
            val rb = caseRank(cb)
            if (ra != rb) return if (ra < rb) -1 else 1
            return if (ca < cb) -1 else 1
        }
        return 0
    }
}

/**
 * 列式表。
 *
 * 行是 `ArrayList<Any?>`（`null` = DBNull），首列固定是主键 `id`（long）。
 * 名字查找遵循 [caseSensitive]（对应 `DataTable.CaseSensitive` 影响
 * `DataColumnCollection` 的比较器，:1975）。
 */
private class DtTable(var name: String) {
    var caseSensitive = true

    /**
     * `DataTable._caseSensitiveUserSet`（DataTable.cs 的 CaseSensitive setter 无条件置位，
     * `ShouldSerializeCaseSensitive()` 返回它）——**与 [caseSensitive] 的值无关**，
     * 只记录「用户是否调过 setter」。写侧唯一读者：[dtSchemaXml] 表元素的
     * `msdata:CaseSensitive="True"/"False"`（xmlsaver.cs `HandleTable` 按
     * `table.CaseSensitive.ToString()` 输出，.NET bool 序列化恒首字母大写）。
     * 置位点 = .NET 侧三个 setter 调用点：[dtCreate]（:1995 建表显式设 true）、
     * [dtNoCase]（:1975，设 false 同样置位）、[readTableFromSchema]
     * （`ReadXmlSchema` 只在表元素带该属性时才走 setter）。
     */
    var caseSensitiveUserSet = false
    val columns = ArrayList<DtColumn>()
    val rows = ArrayList<ArrayList<Any?>>()

    /** 主键列名；null = 无主键（DT_FROMXML 读入无 key 的 schema 时）。 */
    var pkCol: String? = null

    /**
     * `DataColumnCollection` 的名字定位：折叠口径**跟着 [caseSensitive] 走**（本波不动）。
     * 钉桩两处（组长 2026-10-23 回读原文，与 `CheckName` 的两段判据一致 ——
     * `FunctionMethodCreator.cs:2198-2207`：:2200 的 id 守卫是**精确名** `name == "id"`，
     * 所以 `"ID"` 不走 id 保护；:2204 的存在性判定用 `Columns.Contains`，其折叠口径就是
     * `CaseSensitive`）：
     * - `MapDataTableMethodsTest` 的「DT 列的类型号与 NOCASE 的大小写语义」：敏感位为真时
     *   `DT_COLUMN_EXIST "T","C1"` = 0，DT_NOCASE(1) 后 = 1；
     * - `MapDataAuditTest` 的「DT_NOCASE 作用面是列名查找而非表名池」同向。
     * 消费点逐个是 [dtColumnExist]（:2086 的 `Contains`）、[dtColumnAdd]（:2118 先判后加）、
     * [dtCellGet]/[dtCellSet]/[dtRowAdd] 的行写入、[parseDtSelectFilter]/[parseDtSelectSort]
     * 的列名解析、以及 [readTableFromSchema] 的 pkCol 定位 —— 全走这一条，改它等于同时改所有点。
     * TODO(verify)：前任 worker 的 dotnet 8 探针声称 `Columns.Contains` **恒**忽略大小写
     * （`CaseSensitive=true` 下 `Contains("S")` 仍 True），与上面两条钉桩冲突。该读数若为真，
     * 影响面是**全表所有列名查找点**（含 `DT_ROW_ADD "T","ID",v` 命中 id 列后 .NET 抛
     * `DataRowNotReadOnlyException`，非 EmueraException，按 `FunctionMethodTerm.cs:20-23`
     * 无 try 的链路会直接崩引擎 —— 这属 FN-06/FN-07 之外的独立缺陷）。卡点 = 需要 PC 侧
     * 逐调用点实测 + 两条钉桩的改钉理由；不在本路范围，交组长另立任务。
     */
    fun colIndex(n: String): Int {
        for (i in columns.indices) {
            val cn = columns[i].name
            if (if (caseSensitive) cn == n else cn.equals(n, ignoreCase = true)) return i
        }
        return -1
    }

    /** `DataRowCollection.Find(id)`（按主键定位）。返回行号，-1 = 未命中。 */
    fun findRow(id: Long): Int {
        for (i in rows.indices) if (rows[i][0] == id) return i
        return -1
    }

    /**
     * `DataRow` 单列初值 —— `DataTable.NewRow()`、`DataColumnCollection.Add` 补老行、
     * ReadXml 缺元素三条路径**同一条派生规则**：`DefaultValue`，未设即 `DBNull`（本模型 null）。
     *
     * .NET Framework DataTable 语义 + 推演依据（dotnet 8 探针，netcoreapp8.0 与游戏运行时
     * 同源 —— reference/emuera-cs/Emuera.csproj:7；临时工程不落仓，编号是探针输出行标）：
     * - A1/A3：对有行的表 `Columns.Add` 后，老行新格**恒 DBNull**（数值列与字符串列同读数），
     *   不是类型零值 —— 旧实现「不可空列补 0/空串」的分支是框架性误推，已删；
     * - A5：`NewRow()` 对不可空列同样 DBNull，`Rows.Add` 当场抛 `NoNullAllowedException`
     *   ⇒ PC 上「不可空列预填 0」不可观测（该行加不进去），verify-notes §13.2 旧登记名作废；
     * - D3：不可空列设了 `DefaultValue` 则 NewRow 填默认值、`Rows.Add` 通过；
     * - B3：DBNull 格的「数字列读 0、字符串列读空串」由**取值函数的合并面**兑现
     *   （DT_CELL_GET DBNull→0 = FunctionMethodCreator.cs:2486-2490、GETS DBNull→"" =
     *   :2536-2541、ISNULL DBNull→1 = :2484-2485），存储层不需要预填字面零值。
     * 非 DBNull 约束（不可空列不许留 DBNull）的触发点在**提交边界**、不在本函数：
     * Rows.Add（[dtRowAdd]）、AllowDBNull setter（[dtColumnAdd]）、单元格写（[dtCellSet]）、
     * ReadXml 装载（[readRowsInto]）。
     */
    fun emptyCell(col: DtColumn): Any? = col.defaultValue

    /** `DataTable.NewRow()`：逐列取 [emptyCell]。 */
    fun newRow(): ArrayList<Any?> = ArrayList<Any?>(columns.size).apply {
        for (col in columns) add(emptyCell(col))
    }
}

/** DT 池 + 行 id 发生器（原版用 `Utils.TimePoint()`，:2278）。 */
private class DtPool {
    val tables = LinkedHashMap<String, DtTable>()
    var lastRowId = 0L
}

private fun dtPool(c: BuiltinContext): DtPool = dtPoolOf(c.store)

/** 存档层用的池入口：同一 aux 槽（`dt`），不经 [BuiltinContext]。 */
private fun dtPoolOf(store: VariableStore): DtPool = store.aux.slot("dt") { DtPool() }

/**
 * 行 id = `Utils.TimePoint()`（Utils.cs:464-471）= `DateTime.Now.Ticks + 已过 ticks`
 * = **100ns 自 0001-01-01 起的计数**（18 位十进制，基准 DATA_LEN=179 依赖这个位数）。
 * 折算走 [TimeMethods.netTicks]（M4-J j-dispatch 与时间族一起修正）：
 * `DateTime.Now` 是**本地墙上时间**，所以必须先把 UTC epoch 毫秒加上当时本地的 UTC 偏移
 * 再乘 10000 —— 旧实现直接 `clockMillis * 10000`，Asia/Shanghai 下与原版差 8 小时的 ticks
 * （2.88e11 ticks）。位数仍是 18 位，`DATA_LEN=179` 那条读数不受影响。
 * 严格递增由 lastRowId 保证（原版靠 Stopwatch 的 100ns 分辨率，同一毫秒内两次
 * DT_ROW_ADD 不会撞主键）；lastRowId 管不到的来源（DT_FROMXML 载入的既有行）若与
 * 自产 id 对撞，由 [dtRowAdd] 的主键唯一闸按 .NET ConstraintException 复刻
 * （RESIDUE-BX2 R3，dotnet 8 探针 R3a）。
 */
private fun nextRowId(c: BuiltinContext): Long {
    val pool = dtPool(c)
    val ticks = TimeMethods.netTicks(c.host.clockMillis(), c.host.zoneId())
    val id = if (ticks > pool.lastRowId) ticks else pool.lastRowId + 1
    pool.lastRowId = id
    return id
}

// ======================================================================
// DT：表管理
// ======================================================================

/** DT_CREATE：已存在 0；否则建表（id 主键列）并返回 1（:1988-2002）。 */
private fun dtCreate(c: BuiltinContext, e: List<Expr?>): Value {
    val pool = dtPool(c)
    val name = e.strAt(c, 0)
    if (pool.tables.containsKey(name)) return Value.IntV(0L)
    val t = DtTable(name)
    t.caseSensitive = true // :1993-1996
    t.caseSensitiveUserSet = true // CaseSensitive setter 无条件置位（DataTable.cs）
    t.columns.add(DtColumn("id", DtType.INT64, allowDBNull = false)) // :1997-1998
    t.pkCol = "id" // :2001
    pool.tables[name] = t
    return Value.IntV(1L)
}

private fun dtExist(c: BuiltinContext, e: List<Expr?>): Value =
    Value.IntV(if (dtPool(c).tables.containsKey(e.strAt(c, 0))) 1L else 0L)

/** DT_RELEASE：存在才删，**恒返回 1**（:1981-1986）。 */
private fun dtRelease(c: BuiltinContext, e: List<Expr?>): Value {
    dtPool(c).tables.remove(e.strAt(c, 0))
    return Value.IntV(1L)
}

/** DT_CLEAR：`DataTable.Clear()` 只清行、保留列；缺表 -1（:1965-1971）。 */
private fun dtClear(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    t.rows.clear()
    return Value.IntV(1L)
}

/**
 * DT_NOCASE：`CaseSensitive = (x == 0)`，缺表 -1（:1972-1978）。
 * **方向已被组长实测改钉过一次**（:1975 原文 `CaseSensitive = arguments[1].GetIntValue(exm) == 0`
 * ⇒ 传 0 = 大小写**敏感**），别再翻。该 flag 在本实现里有两个读者 =
 * [DtTable.colIndex] 的列名折叠口径 与 [DtStringComparer.compare] 的大小写次序级。
 */
private fun dtNoCase(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    t.caseSensitive = e.intAt(c, 1) == 0L
    // setter 语义：设 false 同样置 userSet（DataTable.cs 的 CaseSensitive setter 末行）
    // ⇒ 写侧表元素随后输出 msdata:CaseSensitive="False"（xmlsaver.cs HandleTable）。
    t.caseSensitiveUserSet = true
    return Value.IntV(1L)
}

// ======================================================================
// DT：列
// ======================================================================

/**
 * DT_COLUMN_ADD(name, col [, type [, allowDBNull]])（:2102-2121）：
 * - 缺表 -1（:2071-2074）；列已存在 0（:2104-2107）；
 * - 类型：第 3 参是字符串 → `NameToType`，整数 → `IntToType`；都不认 → CodeEE
 *   "指定了不支持的类型"（:2111-2115）；不给类型 → .NET 默认 **string**（:2118 `Columns.Add(name)`）；
 * - 第 4 参给 0 → AllowDBNull=false，缺省/非 0 → true（:2117）。
 *
 * **分歧 B（扩老行）+ 分歧 D（AllowDBNull=false 的提交边界）已复刻**：
 * `:2118 Columns.Add` 是表级操作 —— 所有老行同步扩到新列宽、新格按 [DtTable.emptyCell]
 * 取 DefaultValue / DBNull（旧实现只 columns.add 不碰 rows，导致 CELL 族在 rows[ri][ci]
 * 直接越界）。随后 `:2119 dataColumn.AllowDBNull = allowDBNull` 的 setter 在**表内已有行**
 * （新列刚回填全 DBNull）且置 false 时抛 .NET `DataException`（"Column 'n' has null values
 * in it."；dotnet 8 探针 A2），且整条 GetIntValue 无 try（FunctionMethodTerm.cs:20-23）
 * ⇒ 想定外中止，移植侧按分歧 C 的裸 CLR→裸 JVM 映射抛 [IllegalStateException]。
 * 探针 A2b：异常后**列已在表内**、AllowDBNull 仍 true、老行新格仍 DBNull —— 部分状态
 * 一并复刻（先 add + 回填、后抛）。空表上加不可空列不抛（探针 B4），此后缺赋值的
 * DT_ROW_ADD 在 [dtRowAdd] 的 Rows.Add 边界拦下。
 *
 * 语料可达性（T6 普查，4.980/4.981 两版）：DT_COLUMN_ADD 共 37/48 处，**0 处**带第 4 参 0，
 * 列恒先于行建（TEMP_CHARA_LIST_BY_DT.ERB:260-280、MAP_NODE_TO_XML.ERB:55-58、
 * ModularCharacterUpdate.ERB:81-84 均为 CREATE→加列→加行序）⇒ 抛点语料 0 处可达，
 * 老行×新列的 DBNull 回填亦 0 处可达（构造用例钉，MapDataTableMethodsTest / MapDataAuditTest）。
 */
private fun dtColumnAdd(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val colName = e.strAt(c, 1)
    if (t.colIndex(colName) >= 0) return Value.IntV(0L)
    val type = if (e.size >= 3) {
        val decl = if (e.isStrAt(2)) DtType.fromName(e.strAt(c, 2)) else DtType.fromCode(e.intAt(c, 2))
        decl ?: throw CodeEE("函数 DT_COLUMN_ADD 指定了不支持的类型")
    } else {
        DtType.STRING
    }
    val allowDBNull = e.size != 4 || e.intAt(c, 3) != 0L
    // :2118 Columns.Add 先行：列以**可空**态进表、老行回填（:2119 的 setter 还没跑）。
    val col = DtColumn(colName, type, allowDBNull = true)
    t.columns.add(col)
    for (row in t.rows) {
        while (row.size < t.columns.size) row.add(t.emptyCell(t.columns[row.size]))
    }
    if (!allowDBNull) {
        if (t.rows.isNotEmpty()) {
            // :2119 setter 的框架校验（探针 A2/A2b）：老行新格全 DBNull ⇒ DataException，
            // 列已入表、AllowDBNull 保持 true（部分状态照抄）。
            throw IllegalStateException(
                "复刻 .NET DataException（FunctionMethodCreator.cs:2119 AllowDBNull setter）：列 $colName 含 DBNull 值（表内已有 ${t.rows.size} 行），不能设为不可空；列已加入且保持可空",
            )
        }
        col.allowDBNull = false // 空表无 DBNull 可撞（探针 B4）
    }
    return Value.IntV(1L)
}

/**
 * DT_COLUMN_OPTIONS 语句（**不是内建函数**）的落地入口，供语句层调用。
 *
 * spec: FunctionIdentifier.cs:2531-2578 —— 只处理 `DEFAULT` 选项，写
 * `dataColumn.DefaultValue`（:2561-2575）；类型不符抛 CodeEE（:2563-2566）。
 * 缺表写 RESULT = -1（:2545-2548），缺列写 RESULT = 0（:2550-2553）；
 * **成功路径不写 RESULT**（:2554-2577 无赋值）。基准 `pc-golden-root.txt:596` 的
 * `DT_COLUMN_OPTIONS=1` 因此不是本语句的返回值，而是 `MAP_CREATE "GM2"`
 * （GOLDEN_10_XMLMAP.ERB:67，METHOD 语句把返回值写 RESULT:0，FunctionIdentifier.cs:584-604）
 * 留下的旧值。
 *
 * 语句层接线点（组长）：`StatementExecutor` 的 `FunctionCode.DT_COLUMN_OPTIONS` 分支
 * 调本函数即可；`DEFAULT` 之外的选项原版只做计数、无副作用（:2556-2577），可忽略。
 *
 * @param value 选项值；`null` 表示缺省（.NET 的 DefaultValue 回落 DBNull）。
 */
internal fun dtColumnOptionsDefault(
    store: VariableStore,
    table: String,
    column: String,
    value: Value?,
) {
    val pool = store.aux.slot("dt") { DtPool() }
    val t = pool.tables[table]
    if (t == null) {
        writeResult0(store, -1L)
        return
    }
    val ci = t.colIndex(column)
    if (ci < 0) {
        writeResult0(store, 0L)
        return
    }
    val col = t.columns[ci]
    if (value == null) {
        col.defaultValue = null
        return
    }
    if (col.type == DtType.STRING) {
        if (value !is Value.StrV) throw CodeEE("函数 DT_COLUMN_OPTIONS 的列 $column 需要字符串值")
        col.defaultValue = value.v
    } else {
        if (value !is Value.IntV) throw CodeEE("函数 DT_COLUMN_OPTIONS 的列 $column 需要整数值")
        col.defaultValue = clampTo(col.type, value.v)
    }
}

/**
 * DT_COLUMN_NAMES(name [, destStrArray])（:2076-2084）：写列名，返回列数；缺表 -1。
 * 1 参形态写 **RESULTS**（:2078 的 `RESULTS_ARRAY`）。
 *
 * 越界口径（分歧 C，已复刻）：原版循环上界是**列数**、与目标数组长度无关 ——
 * `for (int i = 0; i < dataTable.Columns.Count; i++) array[i] = ...`（:2079-2082），
 * 故列数超过目标数组长度时，第 `array.Length` 次写在 .NET 抛
 * `System.IndexOutOfRangeException`，移植侧照抛（旧实现静默 `break` 截断是移植侧自定，非原版）。
 *
 * 抛出形态的链路证据（原版是**裸 CLR 异常**，不是 CodeEE，且整个函数无返回值）：
 * - `FunctionMethodTerm.GetIntValue` 只做 `return method.GetIntValue(exm, arguments)`，
 *   整个类无 try/catch（FunctionMethodTerm.cs:20-23）；
 * - `VariableTerm` 的 `catch (Exception ex)` 只包 VariableTerm **自己**的取/写值，
 *   且命中 IOOR 后仍是 `throw;` 原样上抛（VariableTerm.cs:57-78、80-106；
 *   FixedVariableTerm.cs:71-78 同型），函数体内部的数组写不在其保护范围内；
 * - 最终落到 `Process.cs:462-477` 的顶层 catch → `handleException`（:595）的
 *   **非 EmueraException 分支**（:651-660）：屏上打 `Lang.Error.HasUnexpectedError`
 *   + `exc.GetType().ToString()` + `":"` + `exc.Message` + 堆栈 —— 即「想定外のエラー」形态。
 *
 * 移植侧据此抛 JVM 同型异常 [IndexOutOfBoundsException]；抛前 `0 until len` 的写已生效，
 * 与原版崩溃前的部分写入一致（原版同样先写完前面再越界）。
 * TODO(verify)：.NET 的 IOOR 消息文本随版本与本地化而变（"Index was outside the bounds of
 * the array." 一类），仓库内无原文行号，故下面的文案是移植侧自定，只保证**异常类型与无返回值**同构。
 */
private fun dtColumnNames(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val colCount = t.columns.size
    if (e.size <= 1) {
        val len = c.store.getLength("RESULTS", -1)
        for (i in 0 until colCount) {
            if (i >= len) throw columnNamesOutOfRange(len, i)
            c.store.writeStr("RESULTS", longArrayOf(i.toLong()), t.columns[i].name)
        }
    } else {
        val dest = arrArg(e[1]) ?: return Value.IntV(colCount.toLong())
        val len = dest.length(c)
        for (i in 0 until colCount) {
            if (i >= len) throw columnNamesOutOfRange(len, i)
            c.store.writeStr(dest.name, dest.index(i), t.columns[i].name)
        }
    }
    return Value.IntV(colCount.toLong())
}

/**
 * :2081 的 `array[i]` 无界写在越界那一拍的等价异常（分歧 C）。
 * 类型对齐 .NET `System.IndexOutOfRangeException` ⇒ JVM [IndexOutOfBoundsException]，
 * **不是** [CodeEE]（原版链路无 catch 转换，见 [dtColumnNames] 的行号证据）。
 */
private fun columnNamesOutOfRange(len: Int, i: Int): IndexOutOfBoundsException =
    IndexOutOfBoundsException(
        "DT_COLUMN_NAMES 写目标数组下标 $i 越界（目标长度 $len）——复刻 FunctionMethodCreator.cs:2081 的裸数组无界写",
    )

/** DT_COLUMN_EXIST：缺表 -1、缺列 0，命中返回**类型号 1..5**（:2089-2094）。 */
private fun dtColumnExist(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val ci = t.colIndex(e.strAt(c, 1))
    if (ci < 0) return Value.IntV(0L)
    return Value.IntV(t.columns[ci].type.code)
}

/** DT_COLUMN_REMOVE：缺表 -1；`id` 列不可删（`ToLower() != "id"`，:2096）；成功 1 / 否则 0。 */
private fun dtColumnRemove(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val colName = e.strAt(c, 1)
    val ci = t.colIndex(colName)
    if (ci < 0 || colName.lowercase() == "id") return Value.IntV(0L)
    t.columns.removeAt(ci)
    for (row in t.rows) if (ci < row.size) row.removeAt(ci)
    return Value.IntV(1L)
}

/** DT_COLUMN_LENGTH：缺表 -1，否则列数（:2351）。 */
private fun dtColumnLength(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    return Value.IntV(t.columns.size.toLong())
}

/** DT_ROW_LENGTH：缺表 -1，否则行数（:2351）。 */
private fun dtRowLength(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    return Value.IntV(t.rows.size.toLong())
}

// ======================================================================
// DT：行 / 单元格
// ======================================================================

/**
 * 写一个单元格（`SetValue`，:2210-2251）。列名 "id" 一律拒绝（:2200-2203，区分大小写）。
 *
 * `v == null → row[name] = DBNull.Value`（:2215）镜像分支的**可达性**（RESIDUE-BX2 R4
 * 探针定谳）：成对形态的空位实参在原版解析期就被 CheckArgumentTypeEx 拒绝
 * （[dtRowAdd]/[dtRowSet] 的 ArgTypeList 无 OmitStart ⇒ 缺省 -1，FunctionMethod.cs:198-205），
 * 数组形态走 string/long 重载（:2228-2248）无空位——两者都到不了这里，移植侧已在
 * 两个成对循环同文案拒绝。本分支按 :2215 原文保留：新行（[dtRowAdd]）写 DBNull
 * 不当场抛、约束在 Rows.Add 边界落（探针 R4d/R4e）；已是附加行的 DBNull 写入在
 * .NET 当场抛 NoNullAllowedException（探针 R4a/R4b）——那是 [dtCellSet]（DT_CELL_SET，
 * OmitStart=3 可省参）的路径，其不可空闸见该函数，不落这里。
 */
private fun setCell(c: BuiltinContext, t: DtTable, row: ArrayList<Any?>, colName: String, fn: String, v: Expr?) {
    if (colName == "id") throw CodeEE("函数 $fn 不能写入 id 列")
    val ci = t.colIndex(colName)
    if (ci < 0) throw CodeEE("函数 $fn 指定的列 $colName 不存在")
    val col = t.columns[ci]
    if (v == null) {
        row[ci] = null // :2215 DBNull
        return
    }
    if (col.type == DtType.STRING) {
        if (v.operandType != OperandType.STR) throw CodeEE("函数 $fn 的列 $colName 需要字符串值")
        row[ci] = v.getStrValue(c)
    } else {
        if (v.operandType != OperandType.INT) throw CodeEE("函数 $fn 的列 $colName 需要整数值")
        row[ci] = clampTo(col.type, v.getIntValue(c))
    }
}

/** `Utils.DataTable.ConvertInt`（Utils.cs:225-240）：**饱和截断**，不是回绕。 */
private fun clampTo(type: DtType, v: Long): Long = when (type) {
    DtType.INT8 -> v.coerceIn(-128L, 127L)
    DtType.INT16 -> v.coerceIn(-32768L, 32767L)
    DtType.INT32 -> v.coerceIn(-2147483648L, 2147483647L)
    else -> v
}

/**
 * DT_ROW_ADD（:2275-2319）：新行 id = TimePoint()，按「列名, 值」成对写入，
 * 返回 **行 id**（不是 1；基准丢弃该值）。
 * 也支持 4 参数组形态 `(name, strArray, anyArray, count)`（:2280-2304）。
 */
private fun dtRowAdd(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val row = t.newRow()
    row[0] = nextRowId(c) // :2278
    if (e.size == 4) {
        val names = arrArg(e[1]) ?: throw CodeEE("函数 DT_ROW_ADD 的第 2 个参数必须是字符串一维数组变量")
        val nameArr = names.strs(c)
        val cnt = e.intAt(c, 3)
        if (e.isStrAt(2)) {
            val vals = (arrArg(e[2]) ?: throw CodeEE("函数 DT_ROW_ADD 的第 3 个参数必须是数组变量")).strs(c)
            val n = minOf(nameArr.size.toLong(), cnt, vals.size.toLong()).toInt()
            for (i in 0 until n) setCell(c, t, row, nameArr[i], "DT_ROW_ADD", StrLiteral(vals[i]))
        } else {
            val vals = (arrArg(e[2]) ?: throw CodeEE("函数 DT_ROW_ADD 的第 3 个参数必须是数组变量")).ints(c)
            val n = minOf(nameArr.size.toLong(), cnt, vals.size.toLong()).toInt()
            for (i in 0 until n) setCell(c, t, row, nameArr[i], "DT_ROW_ADD", IntLiteral(vals[i]))
        }
    } else {
        var i = 1
        while (i < e.size) {
            if (i + 1 >= e.size) throw CodeEE("函数 DT_ROW_ADD 的「列名, 值」参数个数不成对")
            // 空位实参（如 `DT_ROW_ADD "T", "C", ` 尾随逗号）在原版**解析期**就被
            // CheckArgumentTypeEx 拒绝（本函数 ArgTypeList 无 OmitStart ⇒ 缺省 -1，
            // FunctionMethodCreator.cs:2140-2155；FunctionMethod.cs:198-205
            // `arguments[j] == null` 两条件皆不成立 ⇒ ArgCanNotBeNull，Lang.cs:3734，
            // zhs 文案 `{0}函数: 第{1}个参数不能省略`），
            // :2215 的 `v == null → DBNull` 分支对成对形态**不可达**。移植侧运行期
            // 同文案拒绝（RESIDUE-BX2 R4）——旧实现把空位静默写成 DBNull，方向相反。
            setCell(
                c, t, row, e.strAt(c, i), "DT_ROW_ADD",
                e[i + 1] ?: throw CodeEE("DT_ROW_ADD函数: 第${i + 2}个参数不能省略"),
            )
            i += 2
        }
    }
    // :2318 Rows.Add 是 .NET 的提交边界：行内仍有 DBNull 的**不可空**列当场抛
    // NoNullAllowedException（"Column 'n' does not allow nulls."；dotnet 8 探针 A5），
    // 异常后行不落表（探针 A5b）。id 列已先赋（:2278），只有其余不可空列会踩；
    // 设了 DefaultValue 的列已被 [DtTable.emptyCell] 填上默认值、放行（探针 D3）。
    // 链路无 try（FunctionMethodTerm.cs:20-23）⇒ 按分歧 C 的裸异常映射抛
    // [IllegalStateException]。语料 4.980/4.981 的 DT_COLUMN_ADD 0 处带第 4 参 0
    // ⇒ 除 id 外不存在不可空列，本抛点语料 0 处可达（构造用例钉）。
    // 空约束先于唯一约束（探针 R3g：重复主键 + DBNull 不可空列并存时 .NET 报
    // NoNullAllowedException 而非 ConstraintException）⇒ 本循环在前、主键闸在后。
    for ((ci, col) in t.columns.withIndex()) {
        if (!col.allowDBNull && row[ci] == null) {
            throw IllegalStateException(
                "复刻 .NET NoNullAllowedException（FunctionMethodCreator.cs:2318 Rows.Add）：列 ${col.name} 不可空而新行未赋值且无 DefaultValue，行未落表",
            )
        }
    }
    // 主键唯一约束（RESIDUE-BX2 R3）：:2001 `PrimaryKey = {id}`（:1999 `Unique = true`）
    // 在 Rows.Add 提交时对撞抛 ConstraintException——dotnet 8 探针 R3a 实测
    // "Column 'id' is constrained to be unique.  Value '1' is already present."
    // （unique 后双空格，原样），异常后行不落表（探针 R3b）。:2749-2752 的
    // `catch { return 0L; }` 只在 DataTableFromXmlMethod 内，**不**覆盖本路
    // （DataTableRowSetMethod 无 try）⇒ 与分歧 C 同判：裸 CLR 异常 → 裸 JVM 映射。
    // 无主键的表（探针 R3c：ReadXmlSchema 未声明 msdata:PrimaryKey ⇒ UniqueConstraint
    // 不存在，重复 id 照加）不设闸；nextRowId 的严格递增只保证**本池自产 id**不撞，
    // DT_FROMXML 载入的行不经它，对撞由本闸拦（构造用例钉）。
    t.pkCol?.let { pkName ->
        val pkIdx = t.colIndex(pkName)
        if (pkIdx >= 0) {
            val pkValue = row[pkIdx]
            if (pkValue != null && t.rows.any { it.size > pkIdx && it[pkIdx] == pkValue }) {
                val colName = t.columns[pkIdx].name
                throw IllegalStateException(
                    "复刻 .NET ConstraintException（FunctionMethodCreator.cs:2318 Rows.Add，主键 :2001）：" +
                        "列 $colName 受主键唯一约束，值 $pkValue 已存在" +
                        "（Column '$colName' is constrained to be unique.  Value '$pkValue' is already present.），行未落表",
                )
            }
        }
    }
    t.rows.add(row) // :2318
    return Value.IntV(row[0] as Long)
}

/**
 * DT_ROW_SET（:2265-2321）：第 2 参是**行 id**（`Rows.Find`），找不到 → **-2**（:2269-2272，
 * 基准 `DT_ROW_SET=-2` 就是 id=0 不存在）；成功返回写入的「列名, 值」对数。
 * 5 参数组形态 `(name, id, strArray, anyArray, count)`。
 */
private fun dtRowSet(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val ri = t.findRow(e.intAt(c, 1))
    if (ri < 0) return Value.IntV(-2L)
    val row = t.rows[ri]
    var n = 0L
    if (e.size == 5) {
        val nameArr = (arrArg(e[2]) ?: throw CodeEE("函数 DT_ROW_SET 的第 3 个参数必须是字符串一维数组变量")).strs(c)
        val cnt = e.intAt(c, 4)
        if (e.isStrAt(3)) {
            val vals = (arrArg(e[3]) ?: throw CodeEE("函数 DT_ROW_SET 的第 4 个参数必须是数组变量")).strs(c)
            val k = minOf(nameArr.size.toLong(), cnt, vals.size.toLong()).toInt()
            for (i in 0 until k) setCell(c, t, row, nameArr[i], "DT_ROW_SET", StrLiteral(vals[i]))
            n = k.toLong()
        } else {
            val vals = (arrArg(e[3]) ?: throw CodeEE("函数 DT_ROW_SET 的第 4 个参数必须是数组变量")).ints(c)
            val k = minOf(nameArr.size.toLong(), cnt, vals.size.toLong()).toInt()
            for (i in 0 until k) setCell(c, t, row, nameArr[i], "DT_ROW_SET", IntLiteral(vals[i]))
            n = k.toLong()
        }
    } else {
        var i = 2
        while (i < e.size) {
            if (i + 1 >= e.size) throw CodeEE("函数 DT_ROW_SET 的「列名, 值」参数个数不成对")
            // 空位实参同 [dtRowAdd] 成对形态：原版 CheckArgumentTypeEx 解析期拒绝
            //（本函数第一份 ArgTypeList 无 OmitStart ⇒ 缺省 -1，FunctionMethodCreator.cs:2168-2187；
            //FunctionMethod.cs:198-205 ⇒ ArgCanNotBeNull），运行期同文案拒绝（RESIDUE-BX2 R4）。
            // 若放行到 setCell 的 DBNull 分支，会绕过 .NET 在附加行写入点当场抛
            // NoNullAllowedException 的框架行为（探针 R4a/R4b），旧实现正是静默写 null。
            setCell(
                c, t, row, e.strAt(c, i), "DT_ROW_SET",
                e[i + 1] ?: throw CodeEE("DT_ROW_SET函数: 第${i + 2}个参数不能省略"),
            )
            i += 2
            n++
        }
    }
    return Value.IntV(n)
}

/**
 * 行主键（第 0 列）取值：原版 `DataRow.Field<long>(0)` / `Convert.ToInt64(row[0])` 遇到
 * DBNull 或非整数列会抛异常；这里统一折成 CodeEE，不用 `as Long`（null / 非 Long 会抛
 * TypeCastException 穿到执行层）。
 */
private fun rowIdOrThrow(row: List<Any?>, fn: String): Long =
    row.getOrNull(0) as? Long
        ?: throw CodeEE("函数 $fn 的行的主键（第 0 列）不是整数")

/**
 * DT_ROW_REMOVE(name, id)：按主键删；找不到 → **0**（:2416-2420，
 * 基准 `DT_ROW_REMOVE=0` 即 id=1 不存在）；成功返回删除行数。
 * 3 参形态 `(name, intArray, count)` → `id IN (...)`（:2393-2413）。
 */
private fun dtRowRemove(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    if (e.size == 3) {
        val arr = (arrArg(e[1]) ?: throw CodeEE("函数 DT_ROW_REMOVE 的第 2 个参数必须是整数一维数组变量")).ints(c)
        val cnt = e.intAt(c, 2)
        // 原版 :2397 `Math.Min((int)arguments[2].GetIntValue(exm), array.Length)`：count 先做
        // (int) 截断（Long → Int32 低 32 位回绕，与 Kotlin Long.toInt() 同）再与数组长度取 min；
        // :2398-2401 截断后 <= 0 才返回 0。
        val n = minOf(cnt.toInt(), arr.size)
        if (n <= 0) return Value.IntV(0L)
        val ids = arr.copyOf(n).toSet()
        val before = t.rows.size
        t.rows.removeAll { rowIdOrThrow(it, "DT_ROW_REMOVE") in ids }
        return Value.IntV((before - t.rows.size).toLong())
    }
    val ri = t.findRow(e.intAt(c, 1))
    if (ri < 0) return Value.IntV(0L)
    t.rows.removeAt(ri)
    return Value.IntV(1L)
}

/** 单元格定位：第 4 参非 0 → 按 id，否则按行号（:2472/:2493）。返回行号，-1 未命中。 */
private fun cellRow(c: BuiltinContext, t: DtTable, e: List<Expr?>): Int {
    val byId = e.size == 4 && e.intAt(c, 3) != 0L
    val rowKey = e.intAt(c, 1)
    return if (byId) t.findRow(rowKey) else if (rowKey in 0 until t.rows.size.toLong()) rowKey.toInt() else -1
}

/** DT_CELL_GET：缺表 0；行/列未命中 0；DBNull 0（:2470/:2490/:2506）。 */
private fun dtCellGet(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(0L)
    val ri = cellRow(c, t, e)
    val ci = t.colIndex(e.strAt(c, 2))
    if (ri < 0 || ci < 0) return Value.IntV(0L)
    val v = t.rows[ri][ci] ?: return Value.IntV(0L)
    if (v is Long) return Value.IntV(v)
    // .NET 是 Convert.ToInt64(obj)：字符串列上取整数会抛 FormatException，这里给等价报错。
    return Value.IntV((v as String).toLongOrNull() ?: throw CodeEE("函数 DT_CELL_GET 的单元格不是整数：$v"))
}

/** DT_CELL_ISNULL：缺表 -1；行/列未命中 -2；DBNull 1；否则 0（:2470/:2506/:2484-2485）。 */
private fun dtCellIsNull(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val ri = cellRow(c, t, e)
    val ci = t.colIndex(e.strAt(c, 2))
    if (ri < 0 || ci < 0) return Value.IntV(-2L)
    return Value.IntV(if (t.rows[ri][ci] == null) 1L else 0L)
}

/** DT_CELL_GETS：缺表 / 未命中 / DBNull 一律 ""（:2513-2541）。 */
private fun dtCellGets(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.StrV("")
    val ri = cellRow(c, t, e)
    val ci = t.colIndex(e.strAt(c, 2))
    if (ri < 0 || ci < 0) return Value.StrV("")
    val v = t.rows[ri][ci] ?: return Value.StrV("")
    // 分歧 G（已复刻，2026-11-05 主人指令推翻 fn-data-B1 的 CodeEE 折面）：原版 GetStrValue
    // 的 byId 分支是 `(string)obj` 强转（FMC.cs:2529），装箱整数强转抛 InvalidCastException
    // （dotnet 8 探针 C：`Unable to cast object of type 'System.Int64' to type 'System.String'.`）
    // 且链路无 try（FunctionMethodTerm.cs:20-23 → Process.cs 非 EmueraException 分支）
    // ⇒ 想定外中止；行号寻址走 obj.ToString()（:2538）不经此路。移植侧按分歧 C 的
    // 裸 CLR→裸 JVM 映射（同 [dtColumnNames] 的 IndexOutOfBoundsException 先例）抛
    // [ClassCastException]，不是 CodeEE。
    // 语料可达性（T6 普查，4.980/4.981 各 12 处）：全部带类型闸或直打字符串列 ——
    // TEMP_CHARA_LIST_BY_DT_TYPE定義.ERB 的 CASEELSE 分支 `DT_COLUMN_EXIST(...) == 5`
    // 才走 GETS、数值列走 DT_CELL_GET（:394-399 同构段），CASE 14/15 是字符串组，
    // bbas_dataset/GET_BODY_SETS 的目标列在 plugins/schema.xml 全部 xs:string，
    // ModularCharacterUpdate.ERB:129/:135 是 3 参行号形态 ⇒ 强转中止语料 0 处可达，
    // 用构造用例钉（MapDataAuditTest）。
    if (v !is String && e.size == 4 && e.intAt(c, 3) != 0L) {
        throw ClassCastException(
            "复刻 .NET InvalidCastException（FunctionMethodCreator.cs:2529 (string)obj）：DT_CELL_GETS 按 id 取到非字符串单元格",
        )
    }
    return Value.StrV(v.toString())
}

/**
 * DT_CELL_SET(name, row, col [, value [, byId]])（:2568-2619）：
 * 缺表 -1；`id` 列 0（:2580-2583）；行/列未命中 **-3**（:2618）；类型不符 **-2**（:2605）；
 * 成功 1。不给 value → 写 DBNull（:2596-2599）。
 */
private fun dtCellSet(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val colName = e.strAt(c, 2)
    if (colName.lowercase() == "id") return Value.IntV(0L)
    val byId = e.size == 5 && e.intAt(c, 4) != 0L
    val rowKey = e.intAt(c, 1)
    val ri = if (byId) t.findRow(rowKey) else if (rowKey in 0 until t.rows.size.toLong()) rowKey.toInt() else -1
    val ci = t.colIndex(colName)
    if (ri < 0 || ci < 0) return Value.IntV(-3L)
    val col = t.columns[ci]
    val v = e.getOrNull(3)
    if (v == null) {
        // :2596-2599 写 DBNull；不可空列在 .NET 的 DataRow 写入点当场抛
        // NoNullAllowedException（dotnet 8 探针 D2），异常后原值不动（探针 D2b）。
        // 列定位（:2594）先于该写入 ⇒ 与行/列未命中 -3 的判定先后同序。
        if (!col.allowDBNull) {
            throw IllegalStateException(
                "复刻 .NET NoNullAllowedException（FunctionMethodCreator.cs:2598）：列 ${col.name} 不可空，不能写 DBNull；原值保持不变",
            )
        }
        t.rows[ri][ci] = null
        return Value.IntV(1L)
    }
    if (col.type == DtType.STRING) {
        if (v.operandType != OperandType.STR) return Value.IntV(-2L)
        t.rows[ri][ci] = v.getStrValue(c)
    } else {
        if (v.operandType != OperandType.INT) return Value.IntV(-2L)
        t.rows[ri][ci] = clampTo(col.type, v.getIntValue(c))
    }
    return Value.IntV(1L)
}

// ======================================================================
// DT：SELECT / XML
// ======================================================================

/**
 * DT_SELECT(name [, filter [, sort]] [, resultArray])（:2644-2676）：
 * 返回命中行数；无目标数组时把命中行 **id** 写 RESULT（:0 = 总数，:1.. = id，:2656-2668），
 * 有目标数组时从下标 0 起写 id、不写总数（`flag == false` 分支）。
 *
 * filter/sort 走 .NET `DataTable.Select(表达式, 排序)`（:2655）。本实现复刻**语料可达子集**
 * （判据 = 原版 :2644-2677 的消费形态 + 本仓 DT 语料实见文本）：
 * - filter：`列 比较符 字面量`，多个条件以 `AND` 连接（大小写不敏感）。
 *   比较符 `=` `<>` `>` `<` `>=` `<=`；字面量 = 整数（可带正负号）或单引号字符串
 *   （串内 `'` 按 .NET 惯例双写 `''` 转义）。列名解析遵循 [DtTable.colIndex]（受 DT_NOCASE
 *   的 caseSensitive 影响，本波不动，探针争议见它的 KDoc）；字符串列的比较（判等与定序）
 *   **唯一**走 [DtStringComparer]，由 caseSensitive 决定是否启用大小写次序级 ——
 *   原版这两条同一个 flag（`Select` 的 filter 与 sort 共用框架比较器）。
 * - sort：`列 [ASC|DESC]`，多列以逗号分隔；方向缺省 ASC（.NET DataView 缺省）。
 * - 空串/缺省 filter、sort 都走全表（:2653-2655 的三元分派：null 走 Select()、
 *   空表达式走 Select("")，两者都回全表）。纯空白串按空处理是**登记微差**
 *   （原版把 " " 交给 Select，属框架语义，见 MapDataAuditTest 分歧 H 用例）。
 *
 * **超出子集的形态一律 CodeEE 并把原文带进报错，绝不静默回全表**（语料硬要求：误回全表
 * 会把不该出现的行写进 RESULT）。已知未覆盖（逐个给原版可达性判断）：
 * - `OR`、`NOT`、括号分组、`LIKE`、`IN`、`IS NULL` / `ISNOTNULL`、标量函数
 *   （LEN/CONVERT 等）、算术表达式、`%`（取模）—— 本仓 DT 语料（自室描写 / UFUFU_LOG /
 *   TEMP_CHARA_LIST_BY_DT(_FUNC) / MAP_NODE_TO_XML / Omogatari 注释）实见文本只用上面子集；
 *   TEMP_CHARA_LIST_BY_DT_FUNC.ERB:992 的注释提到「andかorで接続」但生成代码只发 `and`。
 * - 列名字面量带引号（`[my col]`）、布尔列（原版类型表只有 1..5，无布尔，不可达）。
 * - 排序段带 `[ASC]` 括号形态与 `DESC` 后再跟第二键以外的杂文本。
 * TODO(verify): .NET Select 对 DBNull 格比较返回 false（不是抛）、排序把 DBNull 排最前，
 * 均为框架语义、reference 无行号；已用 dotnet 8 探针取到同向读数（三行 `b, DBNull, a` 的
 * `Select("", "s ASC")` = `,a,b`，`Select("s <> 'a'")` = 1），本实现按此口径钉住。
 * 同键并存的行序见 [dtSelectRows] 的稳定性口径。另一条框架侧待定口径：
 * 字符串列 vs **不带引号** 的数值字面量（`strcol = 5`），.NET 可能把字面量按列类型宽松
 * 转换成 "5" 再比（reference 无行号可证）；本实现按解析期类型错抛 —— 偏严不偏松，
 * 语料生成侧（@BUILD_FILTER）对字符串列发的是 `'%…%'` 带引号形态，两侧不冲突。
 */
private fun dtSelect(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.IntV(-1L)
    val filter = if (e.size > 1 && e[1] != null) e.strAt(c, 1) else null
    val sort = if (e.size > 2 && e[2] != null) e.strAt(c, 2) else null
    val ids = dtSelectRows(t, filter, sort).map { rowIdOrThrow(it, "DT_SELECT") }
    val noDest = e.size != 4
    // 4 参形态的第 4 槽留空 / 写成非变量：原版 `(arguments[3] as VariableTerm).Identifier`
    // 裸 NRE（spec: FunctionMethodCreator.cs:2656-2657）。这里原先静默回落 RESULT，结果是
    // 「不写计数头、却从 RESULT:0 起裸写 id」两头不靠——折 CodeEE。
    if (!noDest && e[3] !is VarRef) throw CodeEE("函数 DT_SELECT 的第 4 个参数必须是数组变量")
    val dest = destArray(e.getOrNull(3), "RESULT")
    val len = c.store.getLength(dest.name, -1)
    if (noDest) {
        val n = minOf(ids.size, maxOf(len - 1, 0))
        for (i in 0 until n) c.store.writeInt(dest.name, dest.index(i + 1), ids[i])
        if (len > 0) c.store.writeInt(dest.name, dest.index(0), ids.size.toLong()) // :2667
    } else {
        val n = minOf(ids.size, len)
        for (i in 0 until n) c.store.writeInt(dest.name, dest.index(i), ids[i])
    }
    return Value.IntV(ids.size.toLong())
}

/** filter 子集的一条合取项：列号 + 比较符 + 字面量（strLit 与 intLit 二选一非 null）。 */
private class DtSelCond(val ci: Int, val op: String, val strLit: String?, val intLit: Long?)

/** sort 子集的一级：列号 + 是否 DESC。 */
private class DtSelKey(val ci: Int, val desc: Boolean)

/** filter/sort 解析或语义错误统一从这里抛（原文进报错，超出子集不许静默）。 */
private fun dtSelError(kind: String, raw: String): CodeEE =
    CodeEE("函数 DT_SELECT 的${kind}表达式超出支持子集：$raw")

private fun dtSelSkipSpace(s: String, i: Int): Int {
    var k = i
    while (k < s.length && s[k].isWhitespace()) k++
    return k
}

/**
 * 解析 filter 子集（见 [dtSelect] KDoc）。列类型与字面量类型不匹配在**解析期**就抛
 * （.NET Expression 建比较节点时报类型错，与行内容无关）。
 */
private fun parseDtSelectFilter(t: DtTable, raw: String): List<DtSelCond> {
    val conds = ArrayList<DtSelCond>()
    var i = 0
    val n = raw.length
    while (true) {
        i = dtSelSkipSpace(raw, i)
        val colStart = i
        while (i < n && !raw[i].isWhitespace() && raw[i] != '=' && raw[i] != '<' && raw[i] != '>') i++
        if (i == colStart) throw dtSelError("筛选", raw)
        val colName = raw.substring(colStart, i)
        i = dtSelSkipSpace(raw, i)
        val opStart = i
        while (i < n && (raw[i] == '=' || raw[i] == '<' || raw[i] == '>')) i++
        val op = raw.substring(opStart, i)
        if (op != "=" && op != "<>" && op != ">" && op != "<" && op != ">=" && op != "<=") {
            throw dtSelError("筛选", raw)
        }
        i = dtSelSkipSpace(raw, i)
        if (i >= n) throw dtSelError("筛选", raw)
        var strLit: String? = null
        var intLit: Long? = null
        if (raw[i] == '\'') {
            i++
            val sb = StringBuilder()
            var closed = false
            while (i < n) {
                if (raw[i] == '\'') {
                    if (i + 1 < n && raw[i + 1] == '\'') {
                        sb.append('\'')
                        i += 2
                    } else {
                        i++
                        closed = true
                        break
                    }
                } else {
                    sb.append(raw[i])
                    i++
                }
            }
            if (!closed) throw dtSelError("筛选", raw)
            strLit = sb.toString()
        } else {
            val litStart = i
            if (raw[i] == '-' || raw[i] == '+') i++
            val dStart = i
            while (i < n && raw[i].isDigit()) i++
            if (i == dStart) throw dtSelError("筛选", raw)
            intLit = raw.substring(litStart, i).toLongOrNull() ?: throw dtSelError("筛选", raw)
        }
        val ci = t.colIndex(colName)
        if (ci < 0) throw CodeEE("函数 DT_SELECT 的筛选表达式找不到列 $colName：$raw")
        val col = t.columns[ci]
        if ((col.type == DtType.STRING) != (strLit != null)) throw dtSelError("筛选", raw)
        conds.add(DtSelCond(ci, op, strLit, intLit))
        i = dtSelSkipSpace(raw, i)
        if (i >= n) break
        if (
            raw.regionMatches(i, "and", 0, 3, ignoreCase = true) &&
            (i + 3 >= n || !(raw[i + 3].isLetterOrDigit() || raw[i + 3] == '_'))
        ) {
            i += 3
        } else {
            throw dtSelError("筛选", raw)
        }
    }
    return conds
}

/** 解析 sort 子集：`列 [ASC|DESC]`（大小写不敏感）逗号分隔；缺省 ASC。 */
private fun parseDtSelectSort(t: DtTable, raw: String): List<DtSelKey> =
    raw.split(',').map { piece ->
        val p = piece.trim()
        if (p.isEmpty()) throw dtSelError("排序", raw)
        val sp = p.indexOfFirst { it.isWhitespace() }
        val colName = if (sp < 0) p else p.substring(0, sp)
        val dir = if (sp < 0) "" else p.substring(sp + 1).trim()
        val ci = t.colIndex(colName)
        if (ci < 0) throw CodeEE("函数 DT_SELECT 的排序表达式找不到列 $colName：$raw")
        val desc = when {
            dir.isEmpty() || dir.equals("ASC", ignoreCase = true) -> false
            dir.equals("DESC", ignoreCase = true) -> true
            else -> throw dtSelError("排序", raw)
        }
        DtSelKey(ci, desc)
    }

/**
 * 合取项求值：DBNull 格必不命中（探针同口径：`s <> 'a'` 对含 DBNull 的三行只给 1，
 * 见 [dtSelect] KDoc）；字符串列的比较**唯一**走 [DtStringComparer.compare]
 * （`=`/`<>` 的判等就是它的 0 判据，与 sort 同一份实现）。
 */
private fun dtSelRowMatches(t: DtTable, row: ArrayList<Any?>, conds: List<DtSelCond>): Boolean {
    for (cond in conds) {
        val cell = row.getOrNull(cond.ci) ?: return false
        val cmp = when (t.columns[cond.ci].type) {
            DtType.STRING -> {
                val s = cell as? String ?: return false
                DtStringComparer.compare(s, cond.strLit!!, t.caseSensitive)
            }
            else -> {
                val v = cell as? Long ?: return false
                v.compareTo(cond.intLit!!)
            }
        }
        val hit = when (cond.op) {
            "=" -> cmp == 0
            "<>" -> cmp != 0
            ">" -> cmp > 0
            "<" -> cmp < 0
            ">=" -> cmp >= 0
            else -> cmp <= 0
        }
        if (!hit) return false
    }
    return true
}

/**
 * 两格排序比较：null（DBNull）最前（探针读数 `Select("", "s ASC")` 把 DBNull 排第一）；
 * 字符串列唯一走 [DtStringComparer.compare]，与 filter 的 `=`/`<>` 同一份实现。
 */
private fun dtSelCompareCells(t: DtTable, rowA: ArrayList<Any?>, rowB: ArrayList<Any?>, ci: Int): Int {
    val a = rowA.getOrNull(ci)
    val b = rowB.getOrNull(ci)
    if (a == null && b == null) return 0
    if (a == null) return -1
    if (b == null) return 1
    return when (t.columns[ci].type) {
        DtType.STRING -> DtStringComparer.compare(a as String, b as String, t.caseSensitive)
        else -> (a as Long).compareTo(b as Long)
    }
}

/**
 * 按子集语义选行并排序；空白 filter/sort = 全表（登记微差见 [dtSelect] KDoc）。
 *
 * 稳定性口径：本侧用**稳定**排序（并键保留行序）。原版 .NET 的 Index/Sort 不保证稳定，
 * 但探针给了可用的一致面：CaseSensitive=false 时 `abc`/`ABC` 是并键，ASC 与 DESC 都返回
 * `abc,ABC`（= 原序），与本侧「比较判 0 + 稳定排序」完全吻合；CaseSensitive=true 时
 * 并键被 [DtStringComparer] 的大小写次序级拆开（ASC `abc,ABC`、DESC `ABC,abc`，探针同读数），
 * 因此**不依赖**稳定性也能对上。语料 DT_SELECT 的排序键都是整数列或大小写一致的 ASCII 列，
 * 并键序不可观测。
 * TODO(verify)：多键排序（`k ASC,s ASC`）在原版走 Sort 的 .NET 内部排序，探针只给到
 * 「并键保持 NewRow 序」（`a,A,a,A`），与本侧稳定序一致；无第二台机器/第二文化取证通道。
 */
private fun dtSelectRows(t: DtTable, filter: String?, sort: String?): List<ArrayList<Any?>> {
    val picked: List<ArrayList<Any?>> =
        if (filter.isNullOrBlank()) {
            t.rows.toList()
        } else {
            val conds = parseDtSelectFilter(t, filter)
            t.rows.filter { dtSelRowMatches(t, it, conds) }
        }
    if (sort.isNullOrBlank()) return picked
    val keys = parseDtSelectSort(t, sort)
    return picked.sortedWith { a, b ->
        for (k in keys) {
            val r = dtSelCompareCells(t, a, b, k.ci)
            if (r != 0) return@sortedWith if (k.desc) -r else r
        }
        0
    }
}

/**
 * DT_TOXML(name [, schemaRef])（:2699-2716）：返回 **数据** XML；**schema** 写进
 * 第 2 参的字符串数组（下标 0）。1 参形态把 schema 写 **RESULTS:1**（:2709 的 `num = 1`，
 * 怪行为照抄：RESULTS:0 留空）。缺表 → ""（:2703-2706）。
 */
private fun dtToXml(c: BuiltinContext, e: List<Expr?>): Value {
    val t = dtPool(c).tables[e.strAt(c, 0)] ?: return Value.StrV("")
    val schema = dtSchemaXml(t)
    if (e.size <= 1) {
        if (c.store.getLength("RESULTS", -1) > 1) {
            c.store.writeStr("RESULTS", longArrayOf(1L), schema) // :2708-2713
        }
    } else {
        val dest = destArray(e[1], "RESULTS")
        c.store.writeStr(dest.name, dest.index(0), schema)
    }
    return Value.StrV(dtDataXml(t))
}

/**
 * DT_FROMXML(name, schema, data)（:2734-2761）：先 `ReadXmlSchema` 再 `ReadXml`，
 * 任一步抛异常 → **0**（:2749-2752，`catch` 全捕获，含表名不匹配与装载期约束违例
 * —— [readRowsInto] 的非空/唯一约束探针 J13/J14、类型错探针 J11 都落这里）。
 * 成功后按名覆盖/新建表，返回 1。
 *
 * 解析层 2026-10-24 起走 core/xml/XmlParser（ARCH1 P3b 双轨合一；原私有 MiniXml 已删）。
 * 原文 `ReadXmlSchema` / `ReadXml` 不经过 XmlDocument，走 XmlReader 原样报告元素内容
 * —— 单元格里的纯空白（PC 真档把 " " 写成 `<NAME> </NAME>`）必须回读成 " "，
 * 故用 `preserveWhitespace = true` 模式（与原 MiniXml 的保留行为一致，见 XmlParser.parse）。
 */
private fun dtFromXml(c: BuiltinContext, e: List<Expr?>): Value {
    val name = e.strAt(c, 0)
    val t = try {
        val schemaRoot = parseXmlRoot(e.strAt(c, 1), preserveWhitespace = true)
        val dataRoot = parseXmlRoot(e.strAt(c, 2), preserveWhitespace = true)
        readTableFromSchema(schemaRoot, name).also { readRowsInto(it, dataRoot) }
    } catch (ex: Exception) {
        return Value.IntV(0L) // :2749-2752
    }
    val pool = dtPool(c)
    pool.tables[name] = t
    return Value.IntV(1L)
}

// ======================================================================
// .NET DataTable XML 序列化
// ======================================================================

private const val CRLF = "\r\n"

/** XML 文本转义（.NET XmlWriter 对文本节点转 `& < >`）。 */
private fun xmlText(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        else -> sb.append(ch)
    }
    return sb.toString()
}

/** XML 属性值转义（额外转引号）。 */
private fun xmlAttr(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        else -> sb.append(ch)
    }
    return sb.toString()
}

/**
 * `DataTable.WriteXmlSchema(StringWriter)` 的逐字节复刻。
 *
 * 调用链（原版 FunctionMethodCreator.cs:2712 调的是 **DataTable** 重载）：.NET
 * `XmlTreeGen.Save(DataTable, XmlWriter)`（xmlsaver.cs）→ `SchemaTree(xd, dt)` →
 * `FillDataSetElement(xd, null, dt)`（无 DataSet 分支）/ `HandleTable` /
 * `HandleColumn` + `AddColumnProperties` → `doc.Save(xw)`。产物形态（换行 CRLF、
 * 缩进 2 空格、schema 的 id 用 .NET 的默认 DataSet 名 `NewDataSet`）由基准长度
 * `DT_TOXML_SCHEMA_LEN=954`（pc-golden-root.txt:613）锁定：对 2 列（id/long 主键、
 * NAME/string 带 DefaultValue）表，只有这一组开关能得到 954。
 *
 * 逐条证据（.NET BCL 侧无行号，按 xmlsaver.cs / XmlDocument.cs 的方法名引）：
 * - **XML 声明无 `standalone`**：`doc.Save(xw)` 在 `WriteState.Start` 时调
 *   `WriteStartDocument()`（无参重载，XmlDocument.cs `Save(XmlWriter)`）；带
 *   `standalone="yes"` 的 `WriteStartDocument(true)` 只出现在 fileName 重载
 *   （DataSet.cs `WriteXmlSchema(string fileName)`）与次级命名空间片段
 *   （`genSecondary`）路径，本调用链都走不到。encoding 取底层 StringWriter 的
 *   UTF-16 ⇒ `encoding="utf-16"`。
 * - **结尾无换行**：`Save` 写完根结束标签后只 `xmlWriter.Flush()`（xmlsaver.cs
 *   `Save` 尾部），`doc.Save(xw)` 不追加尾随空白。
 * - **DataSet 级元素**（`FillDataSetElement` 的 No-DataSet 分支）属性序：
 *   `name` → `msdata:IsDataSet` → `msdata:MainDataTable` →
 *   `msdata:CaseSensitive`（仅 `dt.CaseSensitive` 为真，值是字面量 `"true"`）→
 *   `msdata:UseCurrentLocale`（Locale 等于当前文化时的 else 分支）。
 * - **表元素**（`HandleTable`）属性序：`name` → `msdata:CaseSensitive`（条件是
 *   `ShouldSerializeCaseSensitive()` = `_caseSensitiveUserSet`，**与值无关**；
 *   值 = `table.CaseSensitive.ToString()`，.NET bool 序列化首字母大写 ⇒
 *   `"True"`/`"False"`）→ `msdata:Locale`（用户设过 Locale 才写）。
 * - **列元素**（`HandleColumn` + `AddColumnProperties`）属性序：`name` →
 *   （msdata 块：`ReadOnly`/`AutoIncrement` 等**只看列自身 flag**）→ `type` →
 *   `default` → `minOccurs`。
 * - **主键列不输出 `ReadOnly`/`AutoIncrement`**：`AddColumnProperties` 的条件是
 *   `col.ReadOnly` / `col.AutoIncrement`，与是否主键无关。原版建表只设
 *   `CaseSensitive`（:1995）、`AllowDBNull=false` + `Unique`（:1997-1999），
 *   DT_COLUMN_ADD（:2102-2121）也没有这两个选项 ⇒ 本端口永不输出（DtColumn 无此状态）。
 * - **`minOccurs="0"` 只看 AllowDBNull**：`HandleColumn` 的
 *   `minOccurs = (col.AllowDBNull) ? 0 : 1`，DefaultValue 不参与 ⇒ 带 default 的
 *   可空列**仍**写 `minOccurs="0"`。
 *
 * FN-07 写侧（M11-E 本波落地，前身为「登记不修」）：上一版按长度反推错了组成——
 * 声明带 `standalone="yes"`、主键列带 ReadOnly/AutoIncrement、缺两处 CaseSensitive、
 * 带 default 的可空列漏 minOccurs、结尾多一个 CRLF。dotnet 8 探针（同形表实测
 * `WriteXmlSchema` 总长同为 954）证明真实组成是上述逐字形态：声明去 standalone
 * （-17）、id 列去 ReadOnly/AutoIncrement（-51）、去结尾 CRLF（-2）、DataSet 级
 * 与表级各补一处 CaseSensitive（+28+28）、NAME 列补 minOccurs（+14）—— 六项净抵零，
 * 954 不变但内容对齐 .NET。后果（本波修复）：自环 round-trip 不再丢 CaseSensitive，
 * 读回（[readTableFromSchema]）与 PC 同口径。
 */
private fun dtSchemaXml(t: DtTable): String {
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"utf-16\"?>").append(CRLF)
    sb.append("<xs:schema id=\"NewDataSet\" xmlns=\"\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"")
        .append(" xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">").append(CRLF)
    // FillDataSetElement（No-DataSet 分支）：name → IsDataSet → MainDataTable →
    // CaseSensitive（字面量 "true"，仅 caseSensitive 为真）→ UseCurrentLocale。
    sb.append("  <xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"")
        .append(xmlAttr(t.name)).append('"')
    if (t.caseSensitive) sb.append(" msdata:CaseSensitive=\"true\"")
    sb.append(" msdata:UseCurrentLocale=\"true\">").append(CRLF)
    sb.append("    <xs:complexType>").append(CRLF)
    sb.append("      <xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">").append(CRLF)
    // HandleTable：name → CaseSensitive（ShouldSerializeCaseSensitive = userSet 语义；
    // 值走 bool.ToString() ⇒ "True"/"False" 首字母大写，与 DataSet 级的字面量 "true" 不同）。
    sb.append("        <xs:element name=\"").append(xmlAttr(t.name)).append('"')
    if (t.caseSensitiveUserSet) {
        sb.append(" msdata:CaseSensitive=\"").append(if (t.caseSensitive) "True" else "False").append('"')
    }
    sb.append(">").append(CRLF)
    sb.append("          <xs:complexType>").append(CRLF)
    sb.append("            <xs:sequence>").append(CRLF)
    for (col in t.columns) {
        sb.append("              <xs:element name=\"").append(xmlAttr(col.name)).append('"')
        // AddColumnProperties：ReadOnly/AutoIncrement 只看列自身 flag —— Emuera 无设置点，永不输出。
        sb.append(" type=\"").append(col.type.schema).append('"')
        if (col.defaultValue != null) {
            sb.append(" default=\"").append(xmlAttr(col.defaultValue.toString())).append('"')
        }
        // HandleColumn：minOccurs = AllowDBNull ? 0 : 1，DefaultValue 不参与该条件。
        if (col.allowDBNull) sb.append(" minOccurs=\"0\"")
        sb.append(" />").append(CRLF)
    }
    sb.append("            </xs:sequence>").append(CRLF)
    sb.append("          </xs:complexType>").append(CRLF)
    sb.append("        </xs:element>").append(CRLF)
    sb.append("      </xs:choice>").append(CRLF)
    sb.append("    </xs:complexType>").append(CRLF)
    val pk = t.pkCol
    if (pk != null) {
        sb.append("    <xs:unique name=\"Constraint1\" msdata:PrimaryKey=\"true\">").append(CRLF)
        sb.append("      <xs:selector xpath=\".//").append(xmlAttr(t.name)).append("\" />").append(CRLF)
        sb.append("      <xs:field xpath=\"").append(xmlAttr(pk)).append("\" />").append(CRLF)
        sb.append("    </xs:unique>").append(CRLF)
    }
    sb.append("  </xs:element>").append(CRLF)
    // Save 尾部只 Flush，不写尾随换行（xmlsaver.cs Save）。
    sb.append("</xs:schema>")
    return sb.toString()
}

/**
 * `DataTable.WriteXml(StringWriter)` 的逐字节复刻。
 *
 * 与 schema 的差异（都由基准 `DATA_LEN=179` 锁定）：
 * - **没有 XML 声明**（数据段直接以 `<DocumentElement>` 开头）；
 * - 末尾**没有**换行（`</DocumentElement>` 是最后一个字节）；
 * - 换行仍是 CRLF、缩进 2 空格；DBNull 单元格整元素省略。
 *
 * 179 = 19(`<DocumentElement>`+CRLF) + 2×71(每行: 4 行标签) + 18(`</DocumentElement>`)，
 * 其中每行含两个 18 位 id（见 [nextRowId]）与 NAME 值 2 字符。
 */
private fun dtDataXml(t: DtTable): String {
    val sb = StringBuilder()
    sb.append("<DocumentElement>").append(CRLF)
    for (row in t.rows) {
        sb.append("  <").append(t.name).append('>').append(CRLF)
        for (ci in t.columns.indices) {
            val v = row.getOrNull(ci) ?: continue
            val cn = t.columns[ci].name
            sb.append("    <").append(cn).append('>').append(xmlText(v.toString()))
                .append("</").append(cn).append('>').append(CRLF)
        }
        sb.append("  </").append(t.name).append('>').append(CRLF)
    }
    sb.append("</DocumentElement>")
    return sb.toString()
}

// ----------------------------------------------------------------------
// DT_FROMXML 的 schema / data 反解
// ----------------------------------------------------------------------

/**
 * schema → [DtTable]。
 *
 * 匹配口径 = 原版 `new DataTable(name)` + `ReadXmlSchema`（FunctionMethodCreator.cs:2741-2745）：
 * 只看 **schema 里表元素的 `name`**（还有数据行的元素名，见 [readRowsInto]）。
 * `msdata:MainDataTable` 是 DataSet 元数据（`WriteXmlSchema` 的产物；语料
 * plugins/schema.xml:3 写的是宿主库名 `db`，而表元素与数据行都叫 `bbas_dataset`），
 * **不参与** DataTable 级的表名匹配 —— 旧实现拿它卡表名，导致语料
 * `BBAS_DATASET.ERB:11` 的 `DT_FROMXML "bbas_dataset"` 恒返回 0。
 * 2026-11-05 dotnet 8 探针（netcoreapp8.0 与游戏运行时同源）把这条口径从推演升级为实测：
 * - J1/J6：`ReadXmlSchema` **不消费** `msdata:MainDataTable` —— 指向不存在的 "ghost" 也
 *   静默无视，`TableName` 保持构造名、列照建；
 * - J3/J4/J7：ERB 名匹配不到**表元素名**时抛 `ArgumentException`
 *   "DataTable 'T' does not match to any DataTable in source."（与 MainDataTable 无关，
 *   J3 的 MainDataTable="db" 与元素 "db" 一致也照抛）→ [dtFromXml] 的 catch → 0；
 * - J2：装载成功后 `TableName` 不变，后续 `ReadXml` 按它匹配行元素（J8：行元素名不匹配
 *   ⇒ 静默 0 行，不是错误）；J2b：随后 `WriteXmlSchema` 输出的 MainDataTable 是表自身名。
 *
 * 找不到同名表元素时抛错（DT_FROMXML 侧全捕获成 0，:2749-2752）。
 *
 * FN-07：`msdata:CaseSensitive` **被真消费**（旧实现自认忽略它），口径见
 * [parseMsDataCase]；落到的 flag 与 [dtCreate] / [dtNoCase] 是同一个读者位
 * （[DtTable.colIndex] 的列名折叠 + [DtStringComparer] 的单元格比较），不留第二套近似实现。
 */
private fun readTableFromSchema(root: XmlElement, name: String): DtTable {
    val dataSetElem = root.childrenByName("xs:element").firstOrNull { it.attribute("msdata:IsDataSet") != null }
    // 没有 msdata:IsDataSet = 裸表 schema（DataTable.ReadXmlSchema 也接受）；此时表元素在根下。
    val tableElem = if (dataSetElem != null) {
        dataSetElem.descendantsByName("xs:element").firstOrNull { it.attribute("name")?.attrValue == name }
    } else {
        root.childrenByName("xs:element").firstOrNull { it.attribute("name")?.attrValue == name }
    } ?: throw CodeEE("schema 缺少表 $name 的定义")
    val seq = tableElem.descendantsByName("xs:sequence").firstOrNull()
        ?: throw CodeEE("schema 缺少 xs:sequence")
    val t = DtTable(name)
    // [DtTable] 的字段初值按 DT_CREATE 的建表口径写成 true（:1993-1996），但
    // `new DataTable(name)` 的框架缺省是 **false** —— 读回路径必须先复位，否则
    // 「两层都不写」的 schema 会静默落成敏感表（探针：缺属性 ⇒ False）。
    t.caseSensitive = false
    // `new DataTable(name)` 的 CaseSensitive 框架缺省是 **false**（只有 DT_CREATE 显式设
    // true，:1995）；读入侧的 effective 位只认**表元素**上的 msdata:CaseSensitive。
    // dotnet 8 探针（对 `DataTable.ReadXmlSchema` 逐层喂属性）读数：
    // 只给 DataSet 级 "true" ⇒ False；只给表级 "True"/"true" ⇒ True；
    // DataSet "true" + 表 "false" ⇒ False；DataSet "false" + 表 "true" ⇒ True
    // ⇒ DataSet 级只参与合法性校验（值非法照样整条抛，落进 :2749-2752 的 catch 变 0），
    // 不决定单表的敏感位。语料 plugins/schema.xml 两层都带（:3 = "true"、:6 = "True"）
    // ⇒ 该表判**敏感**，与 PC 一致（PC 侧 DT_TOXML 正是这个双写形态，见 [dtSchemaXml] 的登记）。
    dataSetElem?.attribute("msdata:CaseSensitive")?.attrValue?.let { parseMsDataCase(it, "DataSet") }
    // 表元素属性在才走 CaseSensitive setter（ReadXmlSchema 的装载口径；探针：缺属性 ⇒
    // 框架缺省 false 且 userSet 不置位）⇒ userSet 跟着「属性是否存在」走，
    // 与 DataTable.cs 的 _caseSensitiveUserSet 语义一致（写侧表元素据此决定是否输出）。
    val tableCaseAttr = tableElem.attribute("msdata:CaseSensitive")?.attrValue
    if (tableCaseAttr != null) {
        t.caseSensitive = parseMsDataCase(tableCaseAttr, "表元素")
        t.caseSensitiveUserSet = true
    }
    for (el in seq.childrenByName("xs:element")) {
        val colName = el.attribute("name")?.attrValue ?: continue
        val type = DtType.fromSchema(el.attribute("type")?.attrValue ?: "") ?: DtType.STRING
        t.columns.add(
            DtColumn(
                colName,
                type,
                // AllowDBNull 的读入口径（DT-FRAME 波修正）：XSD 的 element 缺省 minOccurs=1
                // （必需）⇒ .NET 把「属性缺席或值非 "0"」读成 AllowDBNull=false、`minOccurs="0"`
                // 读成 true（探针 J13：无 minOccurs 的 req 列缺元素时 ReadXml 抛非空约束
                // ConstraintException，即该列被判不可空）。写侧同构 —— dtSchemaXml 只对
                // AllowDBNull=true 的列输出 minOccurs="0"（见 dtSchemaXml），id 列恒不可空
                // （:1997-1998）恒缺席。旧实现 `|| colName != "id"` 把非 id 列的缺席误读成
                // 可空，是框架性偏差。
                allowDBNull = (el.attribute("minOccurs")?.attrValue ?: "1") == "0",
                // `default` 在 .NET 侧存的是**按列类型转换后**的值（xs:int → Int32），
                // 不是原文；不转换会让 newRow 把字符串塞进整数列，DT_SELECT 排序在
                // 1058/1084 的 `as Long` 处抛 ClassCastException。
                defaultValue = readSchemaDefault(type, el.attribute("default")?.attrValue, colName),
            ),
        )
    }
    val keyField = (dataSetElem ?: root).descendantsByName("xs:field").firstOrNull()
        ?.attribute("xpath")?.attrValue
    t.pkCol = keyField?.takeIf { t.colIndex(it) >= 0 }
    return t
}

/**
 * schema `msdata:CaseSensitive` 的取值解析（FN-07 消费点）：**任意大小写拼写**都接受
 * （dotnet 8 探针对 DataSet 级 "true"/"True"/"TRUE" 与表级 "True" 全读通；语料
 * plugins/schema.xml 正好两层拼写不同 —— :3 "true"、:6 "True"），
 * 其它文本抛错 ⇒ 整条 DT_FROMXML 走 :2749-2752 的 catch 返回 0
 * （探针：值 "1" 让 `ReadXmlSchema` 抛 `FormatException`，原版同样落 0）。
 */
private fun parseMsDataCase(raw: String, where: String): Boolean = when {
    raw.equals("true", ignoreCase = true) -> true
    raw.equals("false", ignoreCase = true) -> false
    else -> throw CodeEE("schema 的 $where 级 msdata:CaseSensitive 不是布尔值：$raw")
}

/** schema `default="..."` → 列类型值（数值列走 [clampTo]，即 `Utils.DataTable.ConvertInt`）。 */
private fun readSchemaDefault(type: DtType, raw: String?, colName: String): Any? {
    if (raw == null) return null
    if (type == DtType.STRING) return raw
    val v = raw.trim().toLongOrNull()
        ?: throw CodeEE("schema 列 $colName 的 default 不是整数：$raw")
    return clampTo(type, v)
}

/** ReadXml 装载期约束违例（探针 J13/J14 的 `ConstraintException` → [dtFromXml] 的 catch → 0）。 */
private fun readConstraintError(detail: String): IllegalStateException =
    IllegalStateException("复刻 .NET ConstraintException（DataTable.ReadXml 装载期约束）：$detail")

/**
 * data → 追加行。列按 schema 顺序映射。
 *
 * 畸形面的 .NET 官方表现（dotnet 8 探针，netcoreapp8.0 与游戏运行时同源；编号是探针输出行标）：
 * - J9：**不校验根元素名** —— 根为 `<Foo>` 的 `<Foo><bb>…</bb></Foo>` 照样按行元素名载入；
 *   J12：空根 0 行无错。旧实现卡「根必须是 DocumentElement」是移植侧自定，删。
 * - J10：行里多出的未知元素忽略；J11：整数列值非整数抛 `FormatException`（移植侧 CodeEE，
 *   同落 [dtFromXml] 的 catch → 0）；J16/J17：整数列空元素/自闭合同样 FormatException → 0；
 *   J18：字符串列空元素读 ""（非 DBNull）—— 三者与移植侧现状同读数。
 * - J13：缺**不可空**列元素且无 DefaultValue → `ConstraintException`（装载期非空约束）→ 0；
 *   D3：有 DefaultValue 则填默认值放行。缺元素初值 = [DtTable.emptyCell]（DefaultValue 或
 *   DBNull，与 NewRow 同一条派生规则）。
 * - J14：`xs:unique`/`msdata:PrimaryKey` 的唯一性在装载期校验，重复主键 → `ConstraintException` → 0。
 * 缺元素 = .NET WriteXml 对 DBNull 格的写法（整元素省略），可空列常态。
 */
private fun readRowsInto(t: DtTable, root: XmlElement) {
    for (rowNode in root.descendantsByName(t.name)) {
        val row = ArrayList<Any?>(t.columns.size)
        for (col in t.columns) {
            val cell = rowNode.childByName(col.name)
            if (cell == null) {
                val initial = t.emptyCell(col)
                if (initial == null && !col.allowDBNull) {
                    throw readConstraintError("列 ${col.name} 不可空而数据行缺元素且无 DefaultValue")
                }
                row.add(initial)
            } else {
                // innerText：单元格里只有文本/CDATA 时等于原 MiniXml 的 text；纯空白文本节点
                // 在 preserveWhitespace=true 模式下保留（Parse 侧两档口径见 XmlParser.parse）。
                row.add(
                    when (col.type) {
                        DtType.STRING -> cell.innerText
                        else -> cell.innerText.trim().toLongOrNull()
                            ?: throw CodeEE("数据列 ${col.name} 的值不是整数：${cell.innerText}")
                    },
                )
            }
        }
        t.rows.add(row)
    }
    val pk = t.pkCol ?: return
    val pki = t.colIndex(pk)
    if (pki < 0) return
    val seen = HashSet<Long>()
    for (row in t.rows) {
        val id = row.getOrNull(pki) as? Long ?: continue
        if (!seen.add(id)) throw readConstraintError("主键列 $pk 出现重复值 $id")
    }
}

// ----------------------------------------------------------------------
// MAP / DT 装载的 XML 解析入口（ARCH1 P3b 双轨合一）
// ----------------------------------------------------------------------

/**
 * MAP_FROMXML / DT_FROMXML / 存档 EM 装载共用的解析入口：解析 [src] 并返回根元素。
 *
 * 2026-10-24 起 core 内只有 core/xml/XmlParser 一套解析器（原文件内私有的 XNode + MiniXml
 * 已删；逐能力对比与取舍见 XmlParser / XmlNode 的 KDoc 与本波交付回执）：
 * - [preserveWhitespace] 透传 [XmlParser.parse]：MAP_FROMXML 用默认 false（.NET
 *   `XmlDocument.LoadXml` 的 PreserveWhitespace=false，原文 :3058），DT 装载用 true
 *   （`DataTable.ReadXmlSchema` / `ReadXml` 的 XmlReader 口径，单元格里的纯空白不能吃）；
 * - 结构错误抛 XmlParseException：MAP_FROMXML 捕获后包 CodeEE（:3061-3064），
 *   DT_FROMXML 的裸 catch 折成 0（:2749-2752），存档侧 EM 条目坏了原样冒泡
 *   （VariableData.cs 的 LOADDATA 错误路径）。
 *
 * 成功解析的文档必有根元素（XmlParser 对缺根抛 XmlParseException），`?:` 分支只是
 * 让编译器满意的保险丝，正常不可达。
 */
private fun parseXmlRoot(src: String, preserveWhitespace: Boolean = false): XmlElement =
    XmlParser.parse(src, preserveWhitespace).root ?: throw CodeEE("XML 缺少根元素")

/** 名为 [n] 的子元素（只看元素节点，文本/注释/PI/CDATA 不计；XML 名区分大小写）。 */
private fun XmlElement.childrenByName(n: String): List<XmlElement> =
    children.filterIsInstance<XmlElement>().filter { it.name == n }

/** 首个名为 [n] 的子元素。 */
private fun XmlElement.childByName(n: String): XmlElement? = childrenByName(n).firstOrNull()

/** 全部名为 [n] 的后代元素（前序，不含自身）。 */
private fun XmlElement.descendantsByName(n: String): List<XmlElement> {
    val out = ArrayList<XmlElement>()
    fun walk(e: XmlElement) {
        for (c in e.children) {
            if (c is XmlElement) {
                if (c.name == n) out.add(c)
                walk(c)
            }
        }
    }
    walk(this)
    return out
}

// ======================================================================
// 存档扩展段（emData）：MAP / XML / DT 三池的快照 / 装载导入
// ======================================================================

/**
 * 存档关联的三组名字（`GameBase` 之外的 `VarExt` 指令，`ConstantData.cs:1346-1386`）：
 * `SAVE_MAPS` / `SAVE_XMLS` / `SAVE_DTS` 与 `GLOBAL_MAPS` / `GLOBAL_XMLS` / `GLOBAL_DTS`。
 *
 * 顺序 = CSV 里的出现序（原版 `HashSet` 无序，但**写档顺序不可观测**：读回按条目名覆盖，
 * 与本表顺序无关）。空表 = 不写扩展段，`SaveMaps` 之类未配置时的原版形态。
 */
data class EmDataNames(
    val maps: List<String> = emptyList(),
    val xmls: List<String> = emptyList(),
    val dts: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = maps.isEmpty() && xmls.isEmpty() && dts.isEmpty()

    /** `LoadVariableBinary` 的接受集 = `Save* ∪ Global*`（`VariableData.cs:4099/4109/4119`）。 */
    fun union(other: EmDataNames): EmDataNames = EmDataNames(
        (maps + other.maps).distinct(),
        (xmls + other.xmls).distinct(),
        (dts + other.dts).distinct(),
    )

    companion object {
        val EMPTY: EmDataNames = EmDataNames()
    }
}

/**
 * 三张 EM 池的**存档侧**入口（只读导出 / 装载导入 / read 前清空）。
 *
 * 全部语义照 `VariableData` 的同名段：
 * - 写：`SaveEMDataToStreamBinary`（`VariableData.cs:3936-3959`，逐个 `ContainsKey` 后
 *   `WriteWithKey`；XML 写 `OuterXml`、DT 写 `WriteXmlSchema` + `WriteXml`，
 *   `EraBinaryDataWriter.cs:141-172`）；
 * - 读前清：`RemoveEMSaveData`（`:4006-4029`）/ `RemoveEMGlobalData`（`:4031-4054`）——
 *   MAP/DT 是**清空**（表本身留着）、XML 是**移除**；
 * - 读：`LoadVariableBinary`（`:4095-4124`）——只接受 `Save* ∪ Global*` 里的名字，命中即
 *   **整表替换**（`DataStringMaps[text] = value` 等）。
 *
 * 本对象只经既有私有池操作，**不改**任何 MAP_* / DT_* / XML_* 函数的既有行为
 * （`registerMapMethods` / `registerDataTableMethods` 与 `fn/XmlMethods.kt` 的注册面零改动）。
 */
internal object EmDataPools {

    /** XML 池的 aux 槽名（与 `fn/XmlMethods.kt` 的 `xmlPool` 同一个槽，契约见 `host/RuntimeAux`）。 */
    private const val XML_SLOT = "xml"

    private fun xmlPoolOf(store: VariableStore): XmlPool = store.aux.slot(XML_SLOT) { XmlPool() }

    /**
     * 只读导出（`SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary`，
     * `VariableData.cs:3911-3959`）。池里没有的名字**不产出条目**（原版 `ContainsKey` 守卫）。
     */
    fun snapshot(store: VariableStore, names: EmDataNames): List<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        val maps = mapPoolOf(store).maps
        for (n in names.maps) {
            val m = maps[n] ?: continue
            out.add(SaveEntry.MapValue(n, m.map { e -> e.key to e.value }))
        }
        val xmls = xmlPoolOf(store)
        for (n in names.xmls) {
            val doc = xmls[n] ?: continue
            out.add(SaveEntry.XmlValue(n, doc.outerXml()))
        }
        val dts = dtPoolOf(store).tables
        for (n in names.dts) {
            val t = dts[n] ?: continue
            out.add(SaveEntry.DataTableValue(n, dtSchemaXml(t), dtDataXml(t)))
        }
        return out
    }

    /**
     * 读档前清空（`RemoveEMSaveData`，`:4006-4029`；`RemoveEMGlobalData`，`:4031-4054`）。
     *
     * 官方口径的三处不对称照抄：MAP 只 `Clear()`（表还在 ⇒ `MAP_EXIST` 仍为 1）、
     * XML `Remove` 掉（`XML_EXIST` 变 0）、DT 只 `Clear()`（列/主键保留，行清空）。
     */
    fun clear(store: VariableStore, names: EmDataNames) {
        val maps = mapPoolOf(store).maps
        for (n in names.maps) maps[n]?.clear()
        val xmls = xmlPoolOf(store)
        for (n in names.xmls) xmls.release(n)
        val dts = dtPoolOf(store).tables
        for (n in names.dts) dts[n]?.rows?.clear()
    }

    /**
     * 装载导入（`LoadVariableBinary` 的 Map/Xml/DT 三支，`:4095-4124`）。
     *
     * @param allowed 接受集（`Save* ∪ Global*`，见 [EmDataNames.union]）；不在集里的条目
     *   **静默丢弃**（原版 `ContainsKey` 守卫）——不是错误。
     * @return 真正写进池的条目数
     * @throws Exception 条目内容本身坏了（XML 解析失败 / DT schema 不合法）——原版
     *   `ReadXml` / `ReadDataTable` 抛的 `XmlException` 同样会冒到 LOADDATA 的错误路径
     */
    fun restore(store: VariableStore, entries: List<SaveEntry>, allowed: EmDataNames): Int {
        if (entries.isEmpty()) return 0
        val okMaps = allowed.maps.toHashSet()
        val okXmls = allowed.xmls.toHashSet()
        val okDts = allowed.dts.toHashSet()
        var n = 0
        for (e in entries) {
            when (e) {
                is SaveEntry.MapValue -> {
                    if (e.key !in okMaps) continue
                    val dst = LinkedHashMap<String, String>(e.entries.size * 2)
                    for ((k, v) in e.entries) dst[k] = v
                    mapPoolOf(store).maps[e.key] = dst // :4101 整表替换（含键序）
                    n++
                }

                is SaveEntry.XmlValue -> {
                    if (e.key !in okXmls) continue
                    xmlPoolOf(store).put(e.key, XmlParser.parse(e.xml)) // :4111
                    n++
                }

                is SaveEntry.DataTableValue -> {
                    if (e.key !in okDts) continue
                    // 原文 `ReadDataTable`（EraBinaryDataReader → DataTable.ReadXmlSchema/ReadXml），
                    // XmlReader 口径：preserveWhitespace=true 保住字符串单元格里的纯空白
                    // （PC 真档 WriteXml 把 " " 写成 `<NAME> </NAME>`，见 XmlParser.parse）。
                    val t = readTableFromSchema(parseXmlRoot(e.schemaXml, preserveWhitespace = true), e.key)
                    readRowsInto(t, parseXmlRoot(e.dataXml, preserveWhitespace = true))
                    val pool = dtPoolOf(store)
                    pool.tables[e.key] = t // :4121
                    bumpRowId(pool, t)
                    n++
                }

                else -> {}
            }
        }
        return n
    }

    /**
     * 行 id 发生器对齐（`nextRowId` 的既有契约：严格递增，见其 KDoc）。
     *
     * 原版 `DataRow` 的 id 来自 `Utils.TimePoint()`，读档后新行仍取**当前**钟点，
     * 与档内旧 id 天然不同；本仓用 `max(当前 ticks, lastRowId + 1)` 保证不撞主键，
     * 故装载后须把 `lastRowId` 抬到档内最大 id —— 否则「存档 ts 晚于本机时钟」时
     * 新 `DT_ROW_ADD` 会与档内 id 撞车（原版靠 Stopwatch 顺序，不会）。
     */
    private fun bumpRowId(pool: DtPool, t: DtTable) {
        for (row in t.rows) {
            val id = (row.getOrNull(0) as? Long) ?: continue
            if (id > pool.lastRowId) pool.lastRowId = id
        }
    }
}
