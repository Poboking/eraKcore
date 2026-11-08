package com.eratw.port.core.sql

import com.eratw.port.core.diag.FileReadTrail
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinContext
import com.eratw.port.core.fn.BuiltinMethod
import com.eratw.port.core.fn.BuiltinRegistry
import com.eratw.port.core.proc.FileReadAttempt
import com.eratw.port.core.proc.FileRootReadout
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParseException
import com.eratw.port.core.xml.XmlParser

/**
 * SQL 内建函数族（[registerSqlMethods]）。W2 窄子集，**文件所有者：W2/u2-sql**。
 *
 * 上游 fork 自加的 15 个 SQL 内建（`画蛇又添足版自改emuera相关说明.txt:22-30`、`:249-256`）
 * 加上 `SQL_ESCAPE`（同文档 :22-30 提到的转义辅助）。原版 Emuera 没有这组函数，
 * `reference/emuera-cs` 里查不到对应实现（grep SQL_CONNECT / SQL_EXECUTE 零命中），
 * 所以函数面口径只按**语料调用形态**钉住：
 * - `SQL_CONNECT "name"[, "Data Source=path"]`：建命名连接，返回 1。
 * - `SQL_EXECUTE_NONQUERY "db", "SQL"` / `SQL_P_EXECUTE_NONQUERY "db", "SQL", @0...`：
 *   执行非查询语句，返回**受影响行数**（`specs/builtin-functions.yaml:2305-2312`；source
 *   `Creator.Method.cs:10263-10270`、`SqlManager.cs:96-121`，本机无 fork-src）；语料从不读该返回值。
 * - `SQL_EXECUTE_SCALAR_STRING "db", "SQL"` / `SQL_P_EXECUTE_SCALAR_STRING`：
 *   第一行第一列文本，无结果 `""`（qol_dish.ERB:59-62 的注释口径）。
 * - `SQL_EXECUTE_SCALAR_LONG "db", "SQL"` / `SQL_P_EXECUTE_SCALAR_LONG`：
 *   第一行第一列整数，无结果 `0`（qol_dish.ERB:64-67 的注释口径）。
 * - `SQL_EXECUTE_READER "db", "SQL"` / `SQL_P_EXECUTE_READER`：返回 reader 句柄。
 * - `SQL_READER_READ`(句柄)、`SQL_READER_GET_LONG`/`GET_STRING`/`ISNULL`(句柄, 列下标)、
 *   `SQL_READER_CLOSE`（qol_dish.ERB:467-483、qol_graph_query.ERB:94-99 的用法）。
 *   `SQL_READER_READ` 对无效句柄返回 0（不抛错，`specs/builtin-functions.yaml:2321-2327`），
 *   而 `GET_LONG`/`GET_STRING`/`ISNULL` 对无效句柄仍抛 CodeEE「无效的 Reader ID」（同文件 :2334/:2342）。
 * - `SQL_IMPORT_MAP_XML "db", "mapName", "path"`：把 XML 的 k/v 对灌进 MAP 表
 *   （qol_db.ERB:37-38 的说明、:61-62 的调用）。失败口径同样按 spec（未连接返 0 不抛错、
 *   文件找不到抛 CodeEE、成功返回 1）。
 * - `SQL_ESCAPE "text"`：把单引号成对转义（`''`），用于拼接无参数化语句。
 *
 * 返回值口径：`SQL_EXECUTE_NONQUERY` 的「受影响行数」、`SQL_READER_READ` 的「无效句柄返 0
 * 不抛错」与 `SQL_IMPORT_MAP_XML` 的「未连接返 0 / 文件缺失抛 / 成功返 1」均有
 * `specs/builtin-functions.yaml` 各自条目的明文权威（fork-src 直证），按 spec 对齐；
 * 仍无 fork-src 直证、按惯例实现的残余：`SQL_CONNECT` 的连接串重绑语义、
 * SCALAR 族把非数值文本折 0（fork 是 `Convert.ToInt64` 抛/进位口径，语料全走数值列
 * 不可对拍）、SELECT 交给 NONQUERY 时返回 0（不对齐 Microsoft.Data.Sqlite 的 -1），
 * 登记 TODO(verify) 见交付消息。
 *
 * 状态一律挂 `store.aux` 的 `"sql"` 槽（[SqlSession]）：铁律 4 禁静态可变全局，
 * `RuntimeAux.clear()` 即原版 `GlobalStatic.Reset()` 里的 `SqlManager.CloseAll()`
 * （自改说明 :258-259）。
 */
internal fun registerSqlMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    fun intVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }

    fun strVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }

    // ---- 连接 ----

    // 语料里每个 @..._INIT 都重连一次（qol_item.ERB:64 等），必须幂等；
    // 第二参数缺省 = 沿用已有连接（qol_db.ERB:60 的 SQL_CONNECT "TR_DB"）。
    intVal("SQL_CONNECT", 1, 2) { c, a ->
        val name = argText(a, 0, "SQL_CONNECT")
        val connectionString = if (a.size > 1) argText(a, 1, "SQL_CONNECT") else null
        sqlSession(c).connect(name, connectionString)
        1L
    }

    // ---- 非查询 ----

    intVal("SQL_EXECUTE_NONQUERY", 2, 2) { c, a ->
        affectedRows(
            execute(c, "SQL_EXECUTE_NONQUERY", argText(a, 0, "SQL_EXECUTE_NONQUERY"),
                argText(a, 1, "SQL_EXECUTE_NONQUERY"), emptyList()),
        )
    }

    // 可变参数：qol_dish.ERB:99 / qol_fish.ERB:98 的 SQL_P_EXECUTE_* 带 0 个占位参数。
    intVal("SQL_P_EXECUTE_NONQUERY", 2, -1) { c, a ->
        affectedRows(
            execute(c, "SQL_P_EXECUTE_NONQUERY", argText(a, 0, "SQL_P_EXECUTE_NONQUERY"),
                argText(a, 1, "SQL_P_EXECUTE_NONQUERY"), sqlParams(a, 2)),
        )
    }

    // ---- 单值查询 ----

    strVal("SQL_EXECUTE_SCALAR_STRING", 2, 2) { c, a ->
        scalarString(
            execute(c, "SQL_EXECUTE_SCALAR_STRING", argText(a, 0, "SQL_EXECUTE_SCALAR_STRING"),
                argText(a, 1, "SQL_EXECUTE_SCALAR_STRING"), emptyList()),
        )
    }

    strVal("SQL_P_EXECUTE_SCALAR_STRING", 2, -1) { c, a ->
        scalarString(
            execute(c, "SQL_P_EXECUTE_SCALAR_STRING", argText(a, 0, "SQL_P_EXECUTE_SCALAR_STRING"),
                argText(a, 1, "SQL_P_EXECUTE_SCALAR_STRING"), sqlParams(a, 2)),
        )
    }

    intVal("SQL_EXECUTE_SCALAR_LONG", 2, 2) { c, a ->
        scalarLong(
            execute(c, "SQL_EXECUTE_SCALAR_LONG", argText(a, 0, "SQL_EXECUTE_SCALAR_LONG"),
                argText(a, 1, "SQL_EXECUTE_SCALAR_LONG"), emptyList()),
        )
    }

    intVal("SQL_P_EXECUTE_SCALAR_LONG", 2, -1) { c, a ->
        scalarLong(
            execute(c, "SQL_P_EXECUTE_SCALAR_LONG", argText(a, 0, "SQL_P_EXECUTE_SCALAR_LONG"),
                argText(a, 1, "SQL_P_EXECUTE_SCALAR_LONG"), sqlParams(a, 2)),
        )
    }

    // ---- reader ----

    intVal("SQL_EXECUTE_READER", 2, 2) { c, a ->
        openReader(c, "SQL_EXECUTE_READER", argText(a, 0, "SQL_EXECUTE_READER"),
            argText(a, 1, "SQL_EXECUTE_READER"), emptyList())
    }

    intVal("SQL_P_EXECUTE_READER", 2, -1) { c, a ->
        openReader(c, "SQL_P_EXECUTE_READER", argText(a, 0, "SQL_P_EXECUTE_READER"),
            argText(a, 1, "SQL_P_EXECUTE_READER"), sqlParams(a, 2))
    }

    // 推进一行；到末尾返回 0。句柄不存在/已关闭同样返回 0、不抛错
    // （specs/builtin-functions.yaml:2321-2327：「readerId 无效时返回 0（不抛错）」）。
    intVal("SQL_READER_READ", 1, 1) { c, a ->
        val currentReader = sqlSession(c).findReader(argLong(a, 0, "SQL_READER_READ"))
        if (currentReader != null && currentReader.read()) 1L else 0L
    }

    intVal("SQL_READER_GET_LONG", 2, 2) { c, a ->
        when (val v = readerCell(c, a, "SQL_READER_GET_LONG")) {
            is SqlValue.VInt -> v.v
            is SqlValue.VText -> v.v.toLongOrNull()
                ?: throw CodeEE("函数 SQL_READER_GET_LONG：第 ${cellIndex(a).toInt() + 1} 列不是数值（\"${v.v}\"）")
            SqlValue.VNull -> 0L
        }
    }

    strVal("SQL_READER_GET_STRING", 2, 2) { c, a ->
        readerCell(c, a, "SQL_READER_GET_STRING").asText()
    }

    intVal("SQL_READER_ISNULL", 2, 2) { c, a ->
        if (readerCell(c, a, "SQL_READER_ISNULL") === SqlValue.VNull) 1L else 0L
    }

    // 句柄不存在也返回 1：语料里 CLOSE 的位置固定（qol_graph_query.ERB:147-148 关两次），
    // 重复关闭不应中断脚本。
    intVal("SQL_READER_CLOSE", 1, 1) { c, a ->
        sqlSession(c).closeReader(argLong(a, 0, "SQL_READER_CLOSE"))
        1L
    }

    // ---- MAP XML 导入 ----

    // 口径全部按 spec（specs/builtin-functions.yaml 的 SQL_IMPORT_MAP_XML 段，source
    // fork-src Creator.Method.cs:10343-10350、SqlManager.cs:285-339）：
    // - dbName 未连接 → 返回 0、**不抛错**（与 SQL_EXECUTE_* 族的「未连接抛」不同轴）；
    // - 文件路径找不到 → 抛 CodeEE（文案前缀「SQL_IMPORT_MAP_XML」）——4.981 随包
    //   plugins/tw_csv_chs.xml / tw_taste_chs.xml 恒在（qol_db.ERB:61-62 的两条调用无
    //   存在性检查，靠的是随包文件恒在），主语料两侧同值；
    // - XML 解析失败 → 抛 CodeEE（禁止静默空表）；
    // - 成功 → 返回 1（不是导入行数；语料按裸语句调用、从不读返回值）。
    intVal("SQL_IMPORT_MAP_XML", 3, 3) { c, a ->
        val dbName = argText(a, 0, "SQL_IMPORT_MAP_XML")
        val tableName = argText(a, 1, "SQL_IMPORT_MAP_XML")
        val path = argText(a, 2, "SQL_IMPORT_MAP_XML")
        val db = sqlSession(c).find(dbName) ?: return@intVal 0L
        val fileOps = c.host.fileOps
            ?: throw CodeEE("函数 SQL_IMPORT_MAP_XML：宿主未提供文件读取能力")
        val text = fileOps.readText(path)
        if (text == null) {
            // 判读通道（2026-11-05 report-sqlroot）：**屏上文案一字不动**，把「试的哪个根、
            // 解析落点、是否出根被拒」记进 store.aux 的读轴留痕 + System.err（通道选型与
            // 隐私口径见 FileReadTrail 的 KDoc；官方口径不可核——fork 专有函数，ADR-005 不动原文）。
            recordFileReadFailure(c, "SQL_IMPORT_MAP_XML", path)
            throw CodeEE("SQL_IMPORT_MAP_XML：找不到文件 $path")
        }
        val pairs = parseMapXml(text, path)
        importKeyValueRows(db, tableName, pairs)
        1L
    }

    // ---- 转义 ----

    strVal("SQL_ESCAPE", 1, 1) { _, a -> argText(a, 0, "SQL_ESCAPE").replace("'", "''") }
}

// ---------------------------------------------------------------------------
// 参数与结果
// ---------------------------------------------------------------------------

private fun sqlSession(c: BuiltinContext): SqlSession = c.store.aux.slot("sql") { SqlSession() }

/**
 * 记一次失败的宿主文件读（只判读、不改语义；永远不抛）。
 *
 * 读数来源：`fileOps as? FileRootReadout`（生产形态 WriteBlockedFileOps → RootedFileOps
 * 都实现了该只读能力位；自定义宿主桩拿不到根 ⇒ 记「无根读数」形状，仍比只报相对路径多
 * 「宿主未提供根读数」这一条判据）。落点：`store.aux` 的 [FileReadTrail] 槽
 * （铁律 4：状态随 VM，不挂静态）。宿主接进报告只需一行
 * `FileReadTrail.trailLines(session.store)` → [com.eratw.port.core.diag.ErrorReport] 的
 * `extraSectionLines`（接线归组长，见交付）。
 */
private fun recordFileReadFailure(c: BuiltinContext, function: String, path: String) {
    runCatching {
        val attempt = (c.host.fileOps as? FileRootReadout)?.describeReadAttempt(path)
            ?: FileReadAttempt(
                request = path, root = null, resolved = null,
                rejectedOutOfRoot = false, regularFile = false, isDirectory = false,
            )
        c.store.aux.slot(FileReadTrail.AUX_KEY) { FileReadTrail() }.record(function, attempt)
    }
}

private fun execute(
    c: BuiltinContext,
    fn: String,
    connection: String,
    sql: String,
    params: List<SqlValue>,
): SqlExecResult = executeSql(sqlSession(c).require(connection, fn), sql, params)

/**
 * 可选变参从下标 [start] 起按序绑定到 `@0..@N`。
 *
 * 绑定**全部按文本**：fork 的 `SQL_P_*` 形参表是
 * `ArgTypeList{ ArgTypes={String,String,VariadicString}, OmitStart=2 }`
 * （specs/verify-notes.md §23.2，source fork-src Creator.Method.cs:2653-2743）⇒
 * 首个 SQL 串之后的实参一律先 `GetStrValue` 转十进制串再绑 SQLite TEXT
 * （specs/builtin-functions.yaml 的 SQL_P_EXECUTE_NONQUERY 段明文「整数实参自动转十进制串」）。
 * 4.981 语料自身全部用 `TOSTR(...)` 包整数（qol_mushi.ERB:114、qol_graph_init.ERB:129 等），
 * 主语料两侧同值；裸整数实参只在第三方口上可达 —— 按 INTEGER 绑定会让
 * `WHERE k = '123'` 这类 SQL 字符串字面量比较对整数存储静默不命中（SQLite 跨型比较）。
 * 浮点（4.981 / u1-float 引入的第四种 Value）同样按十进制文本绑定。
 */
private fun sqlParams(args: List<Value?>, start: Int): List<SqlValue> =
    args.drop(start).map { v ->
        when (v) {
            is Value.IntV -> SqlValue.VText(v.v.toString())
            is Value.StrV -> SqlValue.VText(v.v)
            is Value.FloatV -> SqlValue.VText(v.v.toString())
            null -> SqlValue.VNull
        }
    }

private fun scalarString(result: SqlExecResult): String = when (result) {
    is SqlExecResult.Rows -> result.rows.firstOrNull()?.firstOrNull()?.asText() ?: ""
    is SqlExecResult.Affected -> ""
}

private fun scalarLong(result: SqlExecResult): Long = when (result) {
    is SqlExecResult.Rows -> when (val v = result.rows.firstOrNull()?.firstOrNull()) {
        is SqlValue.VInt -> v.v
        is SqlValue.VText -> v.v.toLongOrNull() ?: 0L
        else -> 0L
    }
    is SqlExecResult.Affected -> 0L
}

/**
 * `SQL_EXECUTE_NONQUERY` 的返回值：受影响行数（`specs/builtin-functions.yaml:2305-2312`）。
 *
 * 上游 fork 的 `ExecuteNonQuery` 透传 `SqliteCommand.ExecuteNonQuery()` 的 `int` 返回值，
 * spec 明文为「返回受影响行数」（source `Creator.Method.cs:10263-10270`、`SqlManager.cs:96-121`；
 * 本机无 fork-src ⇒ 只按 spec 口径）。移植侧即 `SqlExecResult.Affected.count`
 * （INSERT/UPDATE/DELETE 为命中行数）。语料从不把 SELECT 交给 NONQUERY，误用时返回 0
 * （返回 `Rows`；不对齐 Microsoft.Data.Sqlite 对 SELECT 的 -1 口径，无 fork 证据 ⇒ TODO(verify)）。
 */
private fun affectedRows(result: SqlExecResult): Long = when (result) {
    is SqlExecResult.Affected -> result.count.toLong()
    is SqlExecResult.Rows -> 0L
}

private fun openReader(
    c: BuiltinContext,
    fn: String,
    connection: String,
    sql: String,
    params: List<SqlValue>,
): Long {
    val result = execute(c, fn, connection, sql, params)
    val rows = (result as? SqlExecResult.Rows)?.rows ?: emptyList()
    return sqlSession(c).openReader(emptyList(), rows).toLong()
}

private fun reader(c: BuiltinContext, args: List<Value?>, fn: String): SqlReader =
    sqlSession(c).reader(argLong(args, 0, fn), fn)

private fun cellIndex(args: List<Value?>): Long = argLong(args, 1, "SQL_READER_GET_*")

private fun readerCell(c: BuiltinContext, args: List<Value?>, fn: String): SqlValue {
    val row = reader(c, args, fn).current
        ?: throw CodeEE("函数 $fn：reader 尚未 READ 到有效行（先调用 SQL_READER_READ 并确认返回 1）")
    val idx = cellIndex(args).toInt()
    if (idx < 0 || idx >= row.size) {
        throw CodeEE("函数 $fn：列下标 $idx 越界（本行共 ${row.size} 列）")
    }
    return row[idx]
}

/** 必填实参按文本取用；整数实参也接受（连接名/语句在语料里可能经 TOSTR 传来）。 */
private fun argText(args: List<Value?>, i: Int, fn: String): String {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return when (v) {
        is Value.IntV -> v.v.toString()
        is Value.StrV -> v.v
        is Value.FloatV -> v.v.toString()
    }
}

private fun argLong(args: List<Value?>, i: Int, fn: String): Long {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return (v as? Value.IntV)?.v
        ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是数值")
}

/**
 * `SQL_IMPORT_MAP_XML` 的 XML 形态（`plugins/tw_csv_chs.xml`）：
 * `<map><p><k>键</k><v>值</v></p>...</map>`。
 *
 * k/v 取 `innerText`（与 XML_GET 的文本口径一致）。根节点缺失、或一个 k/v 对都没有时
 * 抛 CodeEE —— 硬约束是「不允许静默返回空结果」，空表会让后续 `SELECT v ... WHERE k = ...`
 * 全部悄悄查空。
 */
private fun parseMapXml(text: String, path: String): List<Pair<String, String>> {
    val doc = try {
        XmlParser.parse(text)
    } catch (e: XmlParseException) {
        throw CodeEE("函数 SQL_IMPORT_MAP_XML：XML 解析失败（$path）：${e.message}")
    }
    val root = doc.root
        ?: throw CodeEE("函数 SQL_IMPORT_MAP_XML：$path 没有根元素")
    val pairs = ArrayList<Pair<String, String>>()
    for (node in root.children) {
        if (node !is XmlElement) continue
        val k = node.children.filterIsInstance<XmlElement>().firstOrNull { it.name == "k" }?.innerText
        val v = node.children.filterIsInstance<XmlElement>().firstOrNull { it.name == "v" }?.innerText
        if (k != null && v != null) pairs.add(k to v)
    }
    if (pairs.isEmpty()) {
        throw CodeEE("函数 SQL_IMPORT_MAP_XML：$path 里没有找到 <p><k>..</k><v>..</v></p> 键值对")
    }
    return pairs
}
