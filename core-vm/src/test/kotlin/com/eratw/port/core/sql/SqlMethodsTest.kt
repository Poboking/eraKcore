package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SQL 窄子集的内建层测试（W2/u2-sql）。判据全部来自语料调用形态：
 * - `qol_dish.ERB:97-166` 的建表 / 参数化 INSERT / BEGIN+COMMIT / VACUUM
 * - `qol_graph_query.ERB:94-99`、`qol_item.ERB:526-545` 的 reader 遍历
 * - `qol_db.ERB:61-62` 的 SQL_IMPORT_MAP_XML
 *
 * 五组必交付用例各一个测试方法；另加 IMPORT_MAP_XML / 数据源共享一条。
 */

private class SqlBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SqlBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private class SqlHost(override val fileOps: FileOps? = null) : BuiltinHost

private class SqlFiles(private val files: MutableMap<String, String>) : FileOps {
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class SqlEnv(files: MutableMap<String, String>? = null) {
    private val base = SqlBaseCtx()
    private val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        val fileOps = files?.let { SqlFiles(it) }
        ctx = BuiltinEvalContext(base, store, SqlHost(fileOps))
    }
}

private fun intOf(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (c.callFunction(name, args.toList()) as Value.IntV).v

private fun strOf(c: BuiltinEvalContext, name: String, vararg args: Value): String =
    (c.callFunction(name, args.toList()) as Value.StrV).v

private fun q(s: String): Value = Value.StrV(s)

private fun n(v: Long): Value = Value.IntV(v)

class SqlMethodsTest {

    private fun connected(name: String, source: String): BuiltinEvalContext {
        val c = SqlEnv().ctx
        assertEquals(1L, intOf(c, "SQL_CONNECT", q(name), q("Data Source=$source")))
        return c
    }

    // 用例①：建表 → INSERT OR REPLACE → SELECT（WHERE/ORDER BY/LIMIT/IN/COUNT）→ UPDATE/DELETE → DROP
    @Test
    fun `建表插入替换查询更新删除与 DROP 闭环`() {
        val c = connected("T", "test.db")

        intOf(
            c, "SQL_EXECUTE_NONQUERY", q("T"),
            q("CREATE TABLE IF NOT EXISTS ITEMS (id INTEGER PRIMARY KEY, name TEXT, price INTEGER DEFAULT 0, tag TEXT)"),
        )
        // IF NOT EXISTS 幂等：重复执行不抛错
        intOf(c, "SQL_EXECUTE_NONQUERY", q("T"), q("CREATE TABLE IF NOT EXISTS ITEMS (id INTEGER PRIMARY KEY, name TEXT)"))

        intOf(
            c, "SQL_P_EXECUTE_NONQUERY", q("T"),
            q("INSERT OR REPLACE INTO ITEMS (id, name, price, tag) VALUES (@0, @1, @2, @3)"),
            q("1"), q("苹果"), q("10"), q("水果"),
        )
        intOf(
            c, "SQL_P_EXECUTE_NONQUERY", q("T"),
            q("INSERT OR REPLACE INTO ITEMS (id, name, price, tag) VALUES (@0, @1, @2, @3)"),
            q("2"), q("萝卜"), q("7"), q("蔬菜"),
        )
        // OR REPLACE 命中主键 1：整行替换
        intOf(
            c, "SQL_P_EXECUTE_NONQUERY", q("T"),
            q("INSERT OR REPLACE INTO ITEMS (id, name, price, tag) VALUES (@0, @1, @2, @3)"),
            q("1"), q("梨"), q("12"), q("水果"),
        )

        assertEquals(2L, intOf(c, "SQL_EXECUTE_SCALAR_LONG", q("T"), q("SELECT COUNT(*) FROM ITEMS")))
        assertEquals(
            "梨",
            strOf(c, "SQL_P_EXECUTE_SCALAR_STRING", q("T"), q("SELECT name FROM ITEMS WHERE id = @0"), q("1")),
        )
        // IN 列表 + 参数
        assertEquals(
            2L,
            intOf(
                c, "SQL_P_EXECUTE_SCALAR_LONG", q("T"),
                q("SELECT COUNT(*) FROM ITEMS WHERE id IN (@0, @1)"), n(1), n(2),
            ),
        )
        // ORDER BY price DESC + LIMIT 1
        assertEquals(
            "梨",
            strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("T"), q("SELECT name FROM ITEMS ORDER BY price DESC LIMIT 1")),
        )

        intOf(c, "SQL_EXECUTE_NONQUERY", q("T"), q("UPDATE ITEMS SET price = 20, name = '青梅' WHERE id = 2"))
        assertEquals(
            1L,
            intOf(c, "SQL_P_EXECUTE_SCALAR_LONG", q("T"), q("SELECT COUNT(*) FROM ITEMS WHERE name = @0"), q("青梅")),
        )
        assertEquals(
            20L,
            intOf(c, "SQL_P_EXECUTE_SCALAR_LONG", q("T"), q("SELECT price FROM ITEMS WHERE id = @0"), n(2)),
        )

        intOf(c, "SQL_EXECUTE_NONQUERY", q("T"), q("DELETE FROM ITEMS WHERE id = 2"))
        assertEquals(1L, intOf(c, "SQL_EXECUTE_SCALAR_LONG", q("T"), q("SELECT COUNT(*) FROM ITEMS")))

        intOf(c, "SQL_EXECUTE_NONQUERY", q("T"), q("DROP TABLE IF EXISTS ITEMS"))
        // 已知差异（KDoc）：缺表按空表语义，不抛 no such table
        assertEquals("", strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("T"), q("SELECT name FROM ITEMS")))
    }

    // 用例②：参数化族与非参数化族行为等价（含引号 / 逗号转义）
    @Test
    fun `参数化与非参数化等价含引号与逗号转义`() {
        val c = connected("TS", "eq.db")
        intOf(c, "SQL_EXECUTE_NONQUERY", q("TS"), q("CREATE TABLE IF NOT EXISTS NOTES (id INTEGER PRIMARY KEY, body TEXT)"))

        val tricky = "他说：'你好, 世界'"
        // SQL_ESCAPE 把单引号成对（SQLite 的字符串内转义口径）
        val escaped = strOf(c, "SQL_ESCAPE", q(tricky))
        assertEquals("他说：''你好, 世界''", escaped)

        intOf(c, "SQL_EXECUTE_NONQUERY", q("TS"), q("INSERT INTO NOTES (id, body) VALUES (1, '$escaped')"))
        intOf(c, "SQL_P_EXECUTE_NONQUERY", q("TS"), q("INSERT INTO NOTES (id, body) VALUES (@0, @1)"), n(2), q(tricky))

        // 两条路径写入的文本完全一致
        assertEquals(tricky, strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("TS"), q("SELECT body FROM NOTES WHERE id = 1")))
        assertEquals(
            tricky,
            strOf(c, "SQL_P_EXECUTE_SCALAR_STRING", q("TS"), q("SELECT body FROM NOTES WHERE id = @0"), n(2)),
        )
        // P 族带 0 个参数（语料 qol_dish.ERB:99 的形态）
        assertEquals(
            "",
            strOf(c, "SQL_P_EXECUTE_SCALAR_STRING", q("TS"), q("SELECT body FROM NOTES WHERE id = 99")),
        )
        // 无结果回落口径：字符串 "" / 整数 0
        assertEquals("", strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("TS"), q("SELECT body FROM NOTES WHERE id = 99")))
        assertEquals(0L, intOf(c, "SQL_EXECUTE_SCALAR_LONG", q("TS"), q("SELECT id FROM NOTES WHERE id = 99")))
    }

    // 用例③：reader 句柄 READ → GET_LONG / GET_STRING / ISNULL → CLOSE
    @Test
    fun `reader 句柄推进取值与关闭`() {
        val c = connected("R", "r.db")
        intOf(
            c, "SQL_EXECUTE_NONQUERY", q("R"),
            q("CREATE TABLE IF NOT EXISTS R (id INTEGER PRIMARY KEY, name TEXT, score INTEGER)"),
        )
        intOf(
            c, "SQL_P_EXECUTE_NONQUERY", q("R"),
            q("INSERT OR REPLACE INTO R (id, name, score) VALUES (@0, @1, @2)"),
            n(1), q("甲"), n(10),
        )
        intOf(
            c, "SQL_P_EXECUTE_NONQUERY", q("R"),
            q("INSERT OR REPLACE INTO R (id, name, score) VALUES (@0, @1, @2)"),
            n(2), q("乙"), n(20),
        )
        intOf(c, "SQL_P_EXECUTE_NONQUERY", q("R"), q("INSERT OR REPLACE INTO R (id, name, score) VALUES (@0, @1, NULL)"), n(3), q("丙"))

        val handle = intOf(c, "SQL_EXECUTE_READER", q("R"), q("SELECT id, name, score FROM R ORDER BY id"))
        assertTrue(handle > 0)

        assertEquals(1L, intOf(c, "SQL_READER_READ", n(handle)))
        assertEquals(1L, intOf(c, "SQL_READER_GET_LONG", n(handle), n(0)))
        assertEquals("甲", strOf(c, "SQL_READER_GET_STRING", n(handle), n(1)))
        assertEquals(0L, intOf(c, "SQL_READER_ISNULL", n(handle), n(2)))
        assertEquals(10L, intOf(c, "SQL_READER_GET_LONG", n(handle), n(2)))

        assertEquals(1L, intOf(c, "SQL_READER_READ", n(handle)))
        assertEquals(2L, intOf(c, "SQL_READER_GET_LONG", n(handle), n(0)))
        assertEquals("乙", strOf(c, "SQL_READER_GET_STRING", n(handle), n(1)))
        assertEquals(0L, intOf(c, "SQL_READER_ISNULL", n(handle), n(2)))

        assertEquals(1L, intOf(c, "SQL_READER_READ", n(handle)))
        assertEquals(3L, intOf(c, "SQL_READER_GET_LONG", n(handle), n(0)))
        // 第三行 score 为 NULL：ISNULL=1，GET_STRING 空串，GET_LONG 0
        assertEquals(1L, intOf(c, "SQL_READER_ISNULL", n(handle), n(2)))
        assertEquals("", strOf(c, "SQL_READER_GET_STRING", n(handle), n(2)))
        assertEquals(0L, intOf(c, "SQL_READER_GET_LONG", n(handle), n(2)))

        assertEquals(0L, intOf(c, "SQL_READER_READ", n(handle)))
        // 重复关闭幂等（语料 qol_graph_query.ERB:147-148 有两条关闭路径）
        assertEquals(1L, intOf(c, "SQL_READER_CLOSE", n(handle)))
        assertEquals(1L, intOf(c, "SQL_READER_CLOSE", n(handle)))
        // specs/builtin-functions.yaml:2321-2327：句柄已关闭（无效）时 READ 返回 0、不抛错
        assertEquals(0L, intOf(c, "SQL_READER_READ", n(handle)))
    }

    // 用例④：不支持的语法必须抛错（消息带语句原文），禁止静默空结果
    @Test
    fun `不支持的语法必须抛错且带语句原文`() {
        val c = connected("U", "u.db")
        intOf(c, "SQL_EXECUTE_NONQUERY", q("U"), q("CREATE TABLE IF NOT EXISTS A (id INTEGER PRIMARY KEY, name TEXT)"))
        intOf(c, "SQL_EXECUTE_NONQUERY", q("U"), q("CREATE TABLE IF NOT EXISTS B (id INTEGER PRIMARY KEY, a_id INTEGER)"))

        val unsupported = listOf(
            "SELECT name FROM A JOIN B ON A.id = B.a_id",
            "SELECT name FROM (SELECT name FROM A)",
            "ALTER TABLE A ADD COLUMN extra TEXT",
            "PRAGMA table_info(A)",
            "CREATE TRIGGER trg AFTER INSERT ON A BEGIN SELECT 1; END",
        )
        for (sql in unsupported) {
            val e = assertFailsWith<CodeEE> { intOf(c, "SQL_EXECUTE_NONQUERY", q("U"), q(sql)) }
            val message = e.message ?: ""
            assertTrue(message.contains("SQL"), "错误消息应说明 SQL 失败：$message")
            assertTrue(message.contains(sql), "错误消息应带语句原文：$message")
        }
    }

    // 用例⑤：VACUUM 空操作、BEGIN + COMMIT 成对不报错
    @Test
    fun `VACUUM 为空操作且 BEGIN 与 COMMIT 成对不报错`() {
        val c = connected("V", "v.db")
        intOf(c, "SQL_EXECUTE_NONQUERY", q("V"), q("CREATE TABLE IF NOT EXISTS V (id INTEGER PRIMARY KEY, v TEXT)"))

        // specs/builtin-functions.yaml:2305-2312：NONQUERY 返回受影响行数。
        // BEGIN/COMMIT 的引擎计数为 1（无 fork-src 直证，登记 TODO(verify)）；VACUUM 影响 0 行。
        assertEquals(1L, intOf(c, "SQL_EXECUTE_NONQUERY", q("V"), q("BEGIN TRANSACTION")))
        intOf(c, "SQL_EXECUTE_NONQUERY", q("V"), q("INSERT OR REPLACE INTO V (id, v) VALUES (1, 'x')"))
        assertEquals(1L, intOf(c, "SQL_EXECUTE_NONQUERY", q("V"), q("COMMIT")))
        assertEquals(0L, intOf(c, "SQL_EXECUTE_NONQUERY", q("V"), q("VACUUM")))

        assertEquals(1L, intOf(c, "SQL_EXECUTE_SCALAR_LONG", q("V"), q("SELECT COUNT(*) FROM V")))
        assertEquals("x", strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("V"), q("SELECT v FROM V WHERE id = 1")))
    }

    // 附加：SQL_IMPORT_MAP_XML 灌键值对、同名数据源共享库、缺文件抛 CodeEE。
    // 失败口径按 specs/builtin-functions.yaml 的 SQL_IMPORT_MAP_XML 段（source fork-src
    // Creator.Method.cs:10343-10350、SqlManager.cs:285-339）：未连接返 0 不抛错、
    // 文件找不到抛 CodeEE、成功返回 1 而不是导入行数（路次 981a 对齐，原断言钉的是
    // 无 fork-src 证据期的「文件读不到返回 0 / 返回行数」旧口径）。
    @Test
    fun `导入 MAP XML 且同数据源连接共享库`() {
        val files = mutableMapOf(
            "plugins/tw_csv_chs.xml" to "<map><p><k>A</k><v>甲</v></p><p><k>B</k><v>乙</v></p></map>",
        )
        val c = SqlEnv(files).ctx

        assertEquals(1L, intOf(c, "SQL_CONNECT", q("TR_DB")))
        // 成功返回 1（不是导入的键值对数；语料按裸语句调用、从不读返回值）
        assertEquals(
            1L,
            intOf(c, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/tw_csv_chs.xml")),
        )
        assertEquals("乙", strOf(c, "SQL_EXECUTE_SCALAR_STRING", q("TR_DB"), q("SELECT v FROM CSV_MAP WHERE k = 'B'")))

        // 同一 Data Source 的两个连接名看到同一份内存库（真实 SQLite 下是同一文件）
        intOf(c, "SQL_CONNECT", q("D1"), q("Data Source=plugins/qol_data.db"))
        intOf(c, "SQL_CONNECT", q("D2"), q("Data Source=plugins/qol_data.db"))
        intOf(c, "SQL_EXECUTE_NONQUERY", q("D1"), q("CREATE TABLE IF NOT EXISTS M (k TEXT PRIMARY KEY, v TEXT)"))
        intOf(c, "SQL_EXECUTE_NONQUERY", q("D1"), q("INSERT OR REPLACE INTO M (k, v) VALUES ('a', '1')"))
        assertEquals(1L, intOf(c, "SQL_EXECUTE_SCALAR_LONG", q("D2"), q("SELECT COUNT(*) FROM M")))

        // dbName 未连接 → 返回 0、不抛错（与 SQL_EXECUTE_* 族的「未连接抛」不同轴）
        assertEquals(
            0L,
            intOf(c, "SQL_IMPORT_MAP_XML", q("NO_DB"), q("CSV_MAP"), q("plugins/tw_csv_chs.xml")),
        )
        // 文件找不到 → 抛 CodeEE，文案前缀「SQL_IMPORT_MAP_XML」
        val e = assertFailsWith<CodeEE> {
            intOf(c, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("plugins/missing.xml"))
        }
        assertTrue(
            e.message!!.startsWith("SQL_IMPORT_MAP_XML"),
            "缺文件报错必须带 SQL_IMPORT_MAP_XML 前缀，实际：${e.message}",
        )
    }

    // 用例⑥（specs/builtin-functions.yaml:2305-2312）：SQL_EXECUTE_NONQUERY 返回受影响行数，
    // 不再恒返回 1。命中行数逐档钉住：INSERT/UPDATE/DELETE 各 1 与 0 行。
    @Test
    fun nonQueryReturnsAffectedRowCount() {
        val c = connected("NQ", "nq.db")
        intOf(c, "SQL_EXECUTE_NONQUERY", q("NQ"), q("CREATE TABLE IF NOT EXISTS NQ (id INTEGER PRIMARY KEY, v TEXT)"))
        // 单行插入影响 1 行
        assertEquals(
            1L,
            intOf(c, "SQL_P_EXECUTE_NONQUERY", q("NQ"), q("INSERT INTO NQ (id, v) VALUES (@0, @1)"), n(1), q("a")),
        )
        assertEquals(
            1L,
            intOf(c, "SQL_P_EXECUTE_NONQUERY", q("NQ"), q("INSERT INTO NQ (id, v) VALUES (@0, @1)"), n(2), q("b")),
        )
        // INSERT OR REPLACE 命中主键的整行替换影响 1 行
        assertEquals(
            1L,
            intOf(c, "SQL_P_EXECUTE_NONQUERY", q("NQ"), q("INSERT OR REPLACE INTO NQ (id, v) VALUES (@0, @1)"), n(1), q("A")),
        )
        // WHERE 命中 1 行的 UPDATE 影响 1 行
        assertEquals(1L, intOf(c, "SQL_EXECUTE_NONQUERY", q("NQ"), q("UPDATE NQ SET v = 'A' WHERE id = 1")))
        // WHERE 未命中任何行的 UPDATE 影响 0 行
        assertEquals(0L, intOf(c, "SQL_EXECUTE_NONQUERY", q("NQ"), q("UPDATE NQ SET v = 'A' WHERE id = 99")))
        // 清空表影响 2 行
        assertEquals(2L, intOf(c, "SQL_EXECUTE_NONQUERY", q("NQ"), q("DELETE FROM NQ")))
        // WHERE 未命中的 DELETE 影响 0 行
        assertEquals(0L, intOf(c, "SQL_EXECUTE_NONQUERY", q("NQ"), q("DELETE FROM NQ WHERE id = 99")))
    }

    // 用例⑦（specs/builtin-functions.yaml:2321-2327）：SQL_READER_READ 对无效句柄（从未分配
    // 或已关闭）返回 0、不抛错；而 GET_LONG 对无效句柄仍抛 CodeEE（同文件 :2334）。
    @Test
    fun readerReadOnInvalidHandleReturnsZero() {
        val c = connected("IR", "ir.db")
        // 从未分配过的句柄
        assertEquals(0L, intOf(c, "SQL_READER_READ", n(999)))
        intOf(c, "SQL_EXECUTE_NONQUERY", q("IR"), q("CREATE TABLE IF NOT EXISTS IR (id INTEGER PRIMARY KEY)"))
        intOf(c, "SQL_P_EXECUTE_NONQUERY", q("IR"), q("INSERT INTO IR (id) VALUES (@0)"), n(1))
        val handle = intOf(c, "SQL_EXECUTE_READER", q("IR"), q("SELECT id FROM IR"))
        assertEquals(1L, intOf(c, "SQL_READER_CLOSE", n(handle)))
        // 已关闭的句柄
        assertEquals(0L, intOf(c, "SQL_READER_READ", n(handle)))
        // GET_LONG 对同一个无效句柄仍按 spec 抛错
        assertFailsWith<CodeEE> { intOf(c, "SQL_READER_GET_LONG", n(handle), n(0)) }
    }
}
