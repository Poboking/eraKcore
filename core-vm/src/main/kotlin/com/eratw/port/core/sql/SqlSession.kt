package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE

/**
 * 窄子集 SQL 引擎的**会话侧状态**（连接表 / 内存库 / reader 句柄表）。
 *
 * 上游自加的 SQL 内建（`画蛇又添足版自改emuera相关说明.txt:22-30`、`:249-256`）在 .NET 侧
 * 由 `SqlManager` 持有 `SQLiteConnection` 与 `SQLiteDataReader`；移植侧**不解析 SQLite 文件格式**
 * （契约硬约束：plugins 目录下的 db 文件只是缓存），改用内存库，状态全部挂在
 * `store.aux` 的 `"sql"` 槽（铁律 4：禁静态可变全局；`RuntimeAux.clear()` 即原版
 * `GlobalStatic.Reset()` 里的 `SqlManager.CloseAll()`，见自改说明 :258-259）。
 *
 * 连接名 → 库的映射按语料实际写法：
 * - `SQL_CONNECT "QOL_DISH_DB", "Data Source=plugins/qol_data.db"`（qol_item.ERB:64 等）
 * - `SQL_CONNECT GRAPH_DB_NAME, "Data Source=plugins/qol_data.db"`（qol_graph_init.ERB:37，
 *   而 `#DIMS CONST GRAPH_DB_NAME = "QOL_DISH_DB"`，qol_graph.ERH:21）
 * - `SQL_CONNECT "IMG_INDEX_DB", "Data Source=plugins/img_index.db"`（qol_tachie.ERB:35）
 * - `SQL_CONNECT "PORTRAIT_MAP_DB", "Data Source=plugins/portrait_map.db"`（qol_portrait_map.ERB:41）
 * - `SQL_CONNECT "TR_DB"`（qol_db.ERB:60，无 Data Source，随后的表由 SQL_IMPORT_MAP_XML 灌入）
 * 同一个 Data Source 的多个连接名指向**同一份内存库**（真实 SQLite 下是同一个文件），
 * 所以 `QOL_DISH_DB` 与 `GRAPH_DB_NAME` 看到的是同一组表。
 */
internal sealed class SqlValue {
    data class VInt(val v: Long) : SqlValue()
    data class VText(val v: String) : SqlValue()
    object VNull : SqlValue()

    /** 文本形态（`GET_STRING` / `SCALAR_STRING` 用；null 折成空串）。 */
    fun asText(): String = when (this) {
        is VInt -> v.toString()
        is VText -> v
        VNull -> ""
    }
}

/** 列类型亲和性（SQLite 的 affinity 规则，只保留语料用到的三档）。 */
internal enum class SqlAffinity { INTEGER, TEXT, NONE }

internal class SqlColumn(
    val name: String,
    val affinity: SqlAffinity,
    val notNull: Boolean = false,
    val default: SqlValue? = null,
)

/**
 * 一张内存表。`rows` 每行长度恒等于 `columns.size`；单元格三态 [SqlValue]。
 *
 * 主键 [keyColumns] 决定 `INSERT OR REPLACE` 与 `ON CONFLICT` 的冲突判定
 * （单列 `PRIMARY KEY` 或表级 `PRIMARY KEY (a, b, ...)`）。
 */
internal class SqlTable(val name: String, val columns: MutableList<SqlColumn>) {
    val rows: MutableList<MutableList<SqlValue>> = ArrayList()
    var keyColumns: List<Int> = emptyList()

    /** 列名查找：SQLite 标识符不区分大小写（`CHARA_NO` 与 `chara_no` 同列）。 */
    fun indexOf(columnName: String): Int? {
        for (i in columns.indices) {
            if (columns[i].name.equals(columnName, ignoreCase = true)) return i
        }
        return null
    }
}

internal class SqlDatabase {
    /** 表名小写 → 表。语料里建表用大写、查询可能小写，故一律折叠。 */
    val tables = LinkedHashMap<String, SqlTable>()

    fun table(name: String): SqlTable? = tables[name.lowercase()]

    /** BEGIN/COMMIT 配对计数。内存实现里事务是空操作（见 [executeSql] 的 BEGIN/COMMIT 分支）。 */
    var transactionDepth: Int = 0
}

/** 一次查询的快照游标。行在 [SQL_EXECUTE_READER] 执行时就拷好，后续写库不影响它。 */
internal class SqlReader(val columns: List<String>, val rows: List<List<SqlValue>>) {
    private var pos = -1

    fun read(): Boolean {
        pos += 1
        return pos < rows.size
    }

    val current: List<SqlValue>? get() = if (pos in rows.indices) rows[pos] else null
}

/**
 * 每 VM 一份的 SQL 会话。**不是** object 单例（铁律 4）：由 `BuiltinContext.store.aux` 的
 * `"sql"` 槽创建，随运行时重置一起丢弃。
 */
internal class SqlSession {
    private val connections = HashMap<String, SqlDatabase>()
    private val dataSources = HashMap<String, SqlDatabase>()
    private val readers = HashMap<Int, SqlReader>()
    private var nextHandle = 1

    /**
     * `SQL_CONNECT name[, "Data Source=path"]`。
     *
     * 同名连接已存在且未给新连接串 → 复用（语料里每个 `@..._INIT` 都会重连一次，
     * 重复 SQL_CONNECT 必须幂等）。带 Data Source 时按 **数据源路径**共享库。
     */
    fun connect(name: String, connectionString: String?): SqlDatabase {
        val key = name.lowercase()
        val existing = connections[key]
        if (connectionString == null && existing != null) return existing
        val source = dataSourceOf(connectionString)
        val db = when {
            source != null -> dataSources.getOrPut(source.lowercase()) { SqlDatabase() }
            existing != null -> existing
            else -> SqlDatabase()
        }
        connections[key] = db
        return db
    }

    fun require(name: String, fn: String): SqlDatabase =
        connections[name.lowercase()]
            ?: throw CodeEE("函数 $fn：数据库连接 \"$name\" 不存在（先执行 SQL_CONNECT \"$name\"）")

    /**
     * 按名取连接；不存在返回 null（不抛错）。
     *
     * 唯一消费者是 `SQL_IMPORT_MAP_XML`：spec 明文「dbName 未连接返回 0（不抛错）」
     * （specs/builtin-functions.yaml 的 SQL_IMPORT_MAP_XML 段，source fork-src
     * SqlManager.cs:285-339），与 `SQL_EXECUTE_*` 族的「未连接抛 CodeEE」不同轴。
     */
    fun find(name: String): SqlDatabase? = connections[name.lowercase()]

    fun openReader(columns: List<String>, rows: List<List<SqlValue>>): Int {
        val handle = nextHandle
        nextHandle += 1
        readers[handle] = SqlReader(columns, rows)
        return handle
    }

    /** 按句柄取 reader；不存在/已关闭返回 null（`SQL_READER_READ` 据 spec 返 0，不抛错）。 */
    fun findReader(handle: Long): SqlReader? = readers[handle.toInt()]

    /** 按句柄取 reader；不存在/已关闭抛 CodeEE（`GET_*`/`ISNULL` 据 spec 抛「无效的 Reader ID」）。 */
    fun reader(handle: Long, fn: String): SqlReader =
        findReader(handle) ?: throw CodeEE("函数 $fn：reader 句柄 $handle 不存在或已关闭")

    fun closeReader(handle: Long) {
        readers.remove(handle.toInt())
    }

    /** `Data Source=...` 的取值；无 `=` 的连接串按纯路径处理（原版 `SQLiteConnection` 的宽容口径）。 */
    private fun dataSourceOf(connectionString: String?): String? {
        if (connectionString == null) return null
        for (part in connectionString.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase().replace(" ", "")
            if (key == "datasource" || key == "filename") {
                return part.substring(eq + 1).trim().ifEmpty { null }
            }
        }
        val whole = connectionString.trim()
        return if (whole.isEmpty() || whole.contains('=')) null else whole
    }
}
