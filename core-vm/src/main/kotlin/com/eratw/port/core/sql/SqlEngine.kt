package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE

/**
 * 窄子集 SQL 引擎：词法 → 语句 AST → 在内存库上执行。
 *
 * 支持面**按语料实测**（`eraTW4.981-261017-EAGLOV3/ERB` 里 21 个文件的 210 条 SQL）：
 * - DDL：`CREATE TABLE [IF NOT EXISTS] name (列定义..., [PRIMARY KEY (a, b)])`、
 *   `DROP TABLE [IF EXISTS] name`
 * - DML：`INSERT [OR REPLACE] INTO ... VALUES (...)`、`INSERT ... ON CONFLICT(..) DO UPDATE SET c = excluded.c`、
 *   `UPDATE t SET c = v [WHERE ...]`、`DELETE FROM t [WHERE ...]`
 * - 查询：`SELECT [DISTINCT] 列|COUNT(*) FROM t [WHERE ...] [GROUP BY ...] [ORDER BY ...] [LIMIT n]`
 * - 事务：`BEGIN [TRANSACTION]` / `COMMIT`（内存实现下是空操作）、`VACUUM`（空操作）
 * - 表达式：`= != <> < > <= >=`、`AND` / `OR` / `NOT`、`IN (...)`、列引用、数字/字符串/NULL 字面量、
 *   参数占位 `@0..@N`、`excluded.col`
 *
 * **不支持的一律抛错**（硬约束：禁止静默返回空结果）。grep 语料确认 0 用法的
 * JOIN / 子查询 / ALTER / PRAGMA / 触发器 / UNION / HAVING / 视图 / LIKE / BETWEEN / 算术运算，
 * 一旦出现就在解析期报 `SQL 不支持：...（语句：原文）`，让下次升版立刻暴露而不是悄悄跑错数据。
 *
 * 证据：SQL 是上游 fork 自加（`画蛇又添足版自改emuera相关说明.txt:22-30`、`:249-256`），
 * 原版 Emuera 无反编译参考可对，故语义只按语料调用形态钉住；返回值口径见
 * `SqlMethods.kt` 的 TODO(verify)。
 *
 * 已知与真实 SQLite 的差异（都在语料观察不到的面）：
 * - 缺表时 `SELECT/UPDATE/DELETE` 返回空/0 行而不是抛 "no such table"：`plugins/img_index.db`
 *   是外部生成的缓存（qol_tachie.ERB:8-10 明确「ERB 层只查询，不建表不灌数据」），
 *   移植侧不解析 SQLite 文件格式，只能按空缓存语义。
 * - `BEGIN/COMMIT` 不产生回滚点：内存库没有日志，脚本中途抛错时已写入的行不回滚。
 * - 排序是 UTF-16 码位序（`String.compareTo`），SQLite 是 BINARY 字节序；语料键都是 ASCII。
 */
internal sealed class SqlExecResult {
    class Rows(val rows: List<List<SqlValue>>) : SqlExecResult()
    class Affected(val count: Int) : SqlExecResult()
}

internal fun executeSql(db: SqlDatabase, sql: String, params: List<SqlValue>): SqlExecResult {
    val stmt = SqlParser(sql).parseStatement()
    return when (stmt) {
        is Stmt.CreateTable -> SqlExecResult.Affected(createTable(db, stmt, sql))
        is Stmt.DropTable -> SqlExecResult.Affected(dropTable(db, stmt, sql))
        is Stmt.Insert -> SqlExecResult.Affected(insertRows(db, stmt, params, sql))
        is Stmt.Select -> select(db, stmt, params, sql)
        is Stmt.Update -> SqlExecResult.Affected(updateRows(db, stmt, params, sql))
        is Stmt.Delete -> SqlExecResult.Affected(deleteRows(db, stmt, params, sql))
        is Stmt.Begin -> {
            db.transactionDepth += 1
            SqlExecResult.Affected(1)
        }
        is Stmt.Commit -> {
            if (db.transactionDepth > 0) db.transactionDepth -= 1
            SqlExecResult.Affected(1)
        }
        is Stmt.Vacuum -> SqlExecResult.Affected(0)
    }
}

/**
 * `SQL_IMPORT_MAP_XML` 的落地：把 (k, v) 对灌进 `tableName`（不存在则按
 * `k TEXT PRIMARY KEY, v TEXT` 建表；存在则先清空再灌，对应「导入即重载」）。
 *
 * 语料形态：`plugins/tw_csv_chs.xml` / `tw_taste_chs.xml` 是 `<map><p><k>..</k><v>..</v></p>...`，
 * 查询侧固定 `SELECT v FROM CSV_MAP WHERE k = '...'`（CSV.ERB:9、DISH_TASTE.ERB:24 等）。
 */
internal fun importKeyValueRows(db: SqlDatabase, tableName: String, pairs: List<Pair<String, String>>): Int {
    val table = db.table(tableName) ?: SqlTable(
        tableName,
        mutableListOf(
            SqlColumn("k", SqlAffinity.TEXT),
            SqlColumn("v", SqlAffinity.TEXT),
        ),
    ).also {
        it.keyColumns = listOf(0)
        db.tables[tableName.lowercase()] = it
    }
    table.rows.clear()
    for ((k, v) in pairs) {
        table.rows.add(mutableListOf(SqlValue.VText(k), SqlValue.VText(v)))
    }
    return table.rows.size
}

// ===========================================================================
// 语句 AST（只在本文件内可见）
// ===========================================================================

private sealed class Stmt {
    class CreateTable(
        val name: String,
        val ifNotExists: Boolean,
        val columns: List<ColumnDef>,
        val primaryKey: List<String>?,
    ) : Stmt()

    class DropTable(val name: String, val ifExists: Boolean) : Stmt()

    class Insert(
        val table: String,
        val orReplace: Boolean,
        val columns: List<String>?,
        val rows: List<List<Expr>>,
        val onConflict: OnConflict?,
    ) : Stmt()

    class Update(val table: String, val sets: List<Pair<String, Expr>>, val where: Expr?) : Stmt()

    class Delete(val table: String, val where: Expr?) : Stmt()

    class Select(
        val table: String,
        val distinct: Boolean,
        val items: List<SelectItem>,
        val where: Expr?,
        val groupBy: List<String>,
        val orderBy: List<OrderKey>,
        val limit: Long?,
    ) : Stmt()

    object Begin : Stmt()
    object Commit : Stmt()
    object Vacuum : Stmt()
}

private class ColumnDef(
    val name: String,
    val affinity: SqlAffinity,
    val notNull: Boolean,
    val default: SqlValue?,
    val primaryKey: Boolean,
)

private sealed class SelectItem {
    class Column(val name: String) : SelectItem()
    object CountStar : SelectItem()
}

private class OrderKey(val column: String, val descending: Boolean)

private class OnConflict(
    val targetColumns: List<String>?,
    val doNothing: Boolean,
    val sets: List<Pair<String, Expr>>,
)

private sealed class Expr {
    class Lit(val value: SqlValue) : Expr()
    class Column(val name: String) : Expr()
    class Param(val index: Int) : Expr()
    class Excluded(val name: String) : Expr()
    class Compare(val op: String, val left: Expr, val right: Expr) : Expr()
    class And(val left: Expr, val right: Expr) : Expr()
    class Or(val left: Expr, val right: Expr) : Expr()
    class Not(val inner: Expr) : Expr()
    class InList(val target: Expr, val items: List<Expr>) : Expr()
}

// ===========================================================================
// 词法
// ===========================================================================

private enum class TokKind { IDENT, NUMBER, STRING, PARAM, PUNCT, END }

private class Tok(val kind: TokKind, val text: String, val num: Long = 0L)

private fun tokenize(sql: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < sql.length) {
        val ch = sql[i]
        when {
            ch.isWhitespace() -> i += 1
            ch == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                while (i < sql.length && sql[i] != '\n') i += 1
            }
            ch == '\'' -> {
                val sb = StringBuilder()
                i += 1
                var closed = false
                while (i < sql.length) {
                    val c = sql[i]
                    if (c == '\'') {
                        if (i + 1 < sql.length && sql[i + 1] == '\'') {
                            sb.append('\'')
                            i += 2
                        } else {
                            i += 1
                            closed = true
                            break
                        }
                    } else {
                        sb.append(c)
                        i += 1
                    }
                }
                if (!closed) throw sqlError(sql, "字符串字面量没有闭合的单引号")
                out.add(Tok(TokKind.STRING, sb.toString()))
            }
            ch.isDigit() -> {
                val start = i
                while (i < sql.length && sql[i].isDigit()) i += 1
                val text = sql.substring(start, i)
                out.add(Tok(TokKind.NUMBER, text, text.toLongOrNull() ?: throw sqlError(sql, "数字超范围：$text")))
            }
            ch == '@' -> {
                var j = i + 1
                while (j < sql.length && sql[j].isDigit()) j += 1
                if (j == i + 1) throw sqlError(sql, "无法解析的 @ 记法（参数占位符必须写成 @0、@1…）")
                val idx = sql.substring(i + 1, j).toIntOrNull() ?: throw sqlError(sql, "参数占位符序号超范围")
                out.add(Tok(TokKind.PARAM, idx.toString(), idx.toLong()))
                i = j
            }
            isIdentStart(ch) -> {
                val start = i
                while (i < sql.length && isIdentPart(sql[i])) i += 1
                out.add(Tok(TokKind.IDENT, sql.substring(start, i)))
            }
            else -> {
                val two = if (i + 1 < sql.length) sql.substring(i, i + 2) else ""
                if (two == "!=" || two == "<>" || two == "<=" || two == ">=") {
                    out.add(Tok(TokKind.PUNCT, two))
                    i += 2
                } else if (ch in "(),;*=<>+-./") {
                    out.add(Tok(TokKind.PUNCT, ch.toString()))
                    i += 1
                } else {
                    throw sqlError(sql, "无法解析的字符 '$ch'")
                }
            }
        }
    }
    out.add(Tok(TokKind.END, ""))
    return out
}

private fun isIdentStart(ch: Char): Boolean = ch.isLetter() || ch == '_'

private fun isIdentPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'

// ===========================================================================
// 语法
// ===========================================================================

private class SqlParser(private val sql: String) {
    private val toks = tokenize(sql)
    private var pos = 0

    fun parseStatement(): Stmt {
        skipSemicolons()
        val stmt = when {
            atEnd() -> throw sqlError(sql, "空语句")
            matchWord("CREATE") -> parseCreate()
            matchWord("DROP") -> parseDrop()
            matchWord("INSERT") -> parseInsert()
            matchWord("SELECT") -> parseSelect()
            matchWord("UPDATE") -> parseUpdate()
            matchWord("DELETE") -> parseDelete()
            matchWord("BEGIN") -> {
                matchWord("TRANSACTION")
                matchWord("DEFERRED")
                matchWord("IMMEDIATE")
                matchWord("EXCLUSIVE")
                Stmt.Begin
            }
            matchWord("COMMIT") -> {
                matchWord("TRANSACTION")
                Stmt.Commit
            }
            matchWord("VACUUM") -> Stmt.Vacuum
            else -> throw sqlError(sql, "不支持的 SQL 语句（首词 \"${peek().text}\"）")
        }
        skipSemicolons()
        if (!atEnd()) {
            throw sqlError(sql, "一条语句串里只允许单条 SQL（多余片段 \"${peek().text}\"）")
        }
        return stmt
    }

    // ---- DDL ----

    private fun parseCreate(): Stmt {
        expectWord("TABLE")
        var ifNotExists = false
        if (matchWord("IF")) {
            expectWord("NOT")
            expectWord("EXISTS")
            ifNotExists = true
        }
        val name = expectIdent("表名")
        expectPunct('(')
        val columns = ArrayList<ColumnDef>()
        var primaryKey: List<String>? = null
        while (true) {
            if (peekWord() == "PRIMARY" && toks[pos + 1].kind == TokKind.PUNCT && toks[pos + 1].text == "(") {
                next()
                primaryKey = parseColumnNameList()
            } else if (peekWord() == "CONSTRAINT" || peekWord() == "UNIQUE" || peekWord() == "CHECK" ||
                peekWord() == "FOREIGN"
            ) {
                throw unsupported(sql, "表级约束 ${peek().text}")
            } else {
                columns.add(parseColumnDef())
            }
            if (matchPunct(',')) continue
            expectPunct(')')
            break
        }
        if (columns.isEmpty()) throw sqlError(sql, "CREATE TABLE 至少需要一列")
        return Stmt.CreateTable(name, ifNotExists, columns, primaryKey)
    }

    private fun parseColumnDef(): ColumnDef {
        val name = expectIdent("列名")
        var affinity = SqlAffinity.NONE
        val word = peekWord()
        if (word != null && word !in COLUMN_CONSTRAINTS) {
            next()
            affinity = affinityOfType(word)
            if (matchPunct('(')) {
                // VARCHAR(n) / DECIMAL(p, s) 之类的长度参数：对窄子集无影响，跳过
                while (!matchPunct(')')) {
                    if (atEnd()) throw sqlError(sql, "列类型参数没有闭合")
                    next()
                }
            }
        }
        var notNull = false
        var default: SqlValue? = null
        var primaryKey = false
        while (true) {
            when {
                matchWord("NOT") -> {
                    expectWord("NULL")
                    notNull = true
                }
                matchWord("NULL") -> notNull = false
                matchWord("DEFAULT") -> {
                    if (default != null) throw sqlError(sql, "列 \"$name\" 出现多个 DEFAULT")
                    default = parseLiteralValue()
                }
                matchWord("PRIMARY") -> {
                    expectWord("KEY")
                    primaryKey = true
                }
                matchWord("AUTOINCREMENT") -> {
                    // 自增语义不在窄子集：语料里 INSERT 总是显式给主键（qol_*.ERB 的 VALUES 列表）
                    throw unsupported(sql, "AUTOINCREMENT")
                }
                else -> break
            }
        }
        return ColumnDef(name, affinity, notNull, default, primaryKey)
    }

    private fun parseDrop(): Stmt {
        expectWord("TABLE")
        var ifExists = false
        if (matchWord("IF")) {
            expectWord("EXISTS")
            ifExists = true
        }
        return Stmt.DropTable(expectIdent("表名"), ifExists)
    }

    // ---- DML ----

    private fun parseInsert(): Stmt {
        var orReplace = false
        if (matchWord("OR")) {
            when {
                matchWord("REPLACE") -> orReplace = true
                else -> throw unsupported(sql, "INSERT OR ${peek().text}")
            }
        }
        expectWord("INTO")
        val table = expectIdent("表名")
        var columns: List<String>? = null
        if (matchPunct('(')) {
            columns = parseColumnNameListAfterOpen()
        }
        expectWord("VALUES")
        val rows = ArrayList<List<Expr>>()
        while (true) {
            expectPunct('(')
            rows.add(parseExprList())
            expectPunct(')')
            if (!matchPunct(',')) break
        }
        val onConflict = parseOnConflictOrNull()
        return Stmt.Insert(table, orReplace, columns, rows, onConflict)
    }

    private fun parseOnConflictOrNull(): OnConflict? {
        if (!matchWord("ON")) return null
        expectWord("CONFLICT")
        var target: List<String>? = null
        if (matchPunct('(')) target = parseColumnNameListAfterOpen()
        expectWord("DO")
        return when {
            matchWord("NOTHING") -> OnConflict(target, doNothing = true, sets = emptyList())
            else -> {
                expectWord("UPDATE")
                expectWord("SET")
                OnConflict(target, doNothing = false, sets = parseAssignments())
            }
        }
    }

    private fun parseUpdate(): Stmt {
        val table = expectIdent("表名")
        expectWord("SET")
        val sets = parseAssignments()
        val where = if (matchWord("WHERE")) parseExpr() else null
        return Stmt.Update(table, sets, where)
    }

    private fun parseAssignments(): List<Pair<String, Expr>> {
        val sets = ArrayList<Pair<String, Expr>>()
        while (true) {
            val col = expectIdent("列名")
            expectPunct('=')
            sets.add(col to parseExpr())
            if (!matchPunct(',')) break
        }
        return sets
    }

    private fun parseDelete(): Stmt {
        expectWord("FROM")
        val table = expectIdent("表名")
        val where = if (matchWord("WHERE")) parseExpr() else null
        return Stmt.Delete(table, where)
    }

    // ---- SELECT ----

    private fun parseSelect(): Stmt {
        var distinct = false
        if (matchWord("DISTINCT")) distinct = true else matchWord("ALL")
        val items = ArrayList<SelectItem>()
        while (true) {
            items.add(parseSelectItem())
            if (!matchPunct(',')) break
        }
        expectWord("FROM")
        if (peekPunct() == "(") throw unsupported(sql, "FROM 后的子查询")
        val table = expectIdent("表名")
        val joinedWord = peekWord()
        if (joinedWord != null && joinedWord in JOIN_WORDS) throw unsupported(sql, "JOIN（$joinedWord）")
        val where = if (matchWord("WHERE")) parseExpr() else null
        var groupBy = emptyList<String>()
        if (matchWord("GROUP")) {
            expectWord("BY")
            groupBy = parseColumnNameListNoParen()
        }
        if (matchWord("HAVING")) throw unsupported(sql, "HAVING")
        var orderBy = emptyList<OrderKey>()
        if (matchWord("ORDER")) {
            expectWord("BY")
            orderBy = parseOrderList()
        }
        var limit: Long? = null
        if (matchWord("LIMIT")) {
            limit = expectNumber()
            if (matchWord("OFFSET")) throw unsupported(sql, "LIMIT ... OFFSET")
        }
        return Stmt.Select(table, distinct, items, where, groupBy, orderBy, limit)
    }

    private fun parseSelectItem(): SelectItem {
        if (peekPunct() == "*") throw unsupported(sql, "SELECT *")
        if (peekWord() == "COUNT") {
            next()
            expectPunct('(')
            if (!matchPunct('*')) throw unsupported(sql, "COUNT 只支持 COUNT(*)")
            expectPunct(')')
            return SelectItem.CountStar
        }
        val name = expectIdent("列名或 COUNT(*)")
        if (matchWord("AS")) throw unsupported(sql, "列别名（AS）")
        return SelectItem.Column(name)
    }

    private fun parseOrderList(): List<OrderKey> {
        val out = ArrayList<OrderKey>()
        while (true) {
            val col = expectIdent("排序列名")
            var desc = false
            if (matchWord("DESC")) desc = true else matchWord("ASC")
            out.add(OrderKey(col, desc))
            if (!matchPunct(',')) break
        }
        return out
    }

    // ---- 表达式 ----

    private fun parseExpr(): Expr = parseOr()

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (matchWord("OR")) left = Expr.Or(left, parseAnd())
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (matchWord("AND")) left = Expr.And(left, parseNot())
        return left
    }

    private fun parseNot(): Expr {
        if (matchWord("NOT")) return Expr.Not(parseNot())
        if (peekWord() == "NOT" || peekWord() == "EXISTS") throw unsupported(sql, peekWord()!!)
        return parseCompare()
    }

    private fun parseCompare(): Expr {
        val left = parseOperand()
        if (peekWord() == "IN") {
            next()
            expectPunct('(')
            val items = ArrayList<Expr>()
            if (peekPunct() != ")") {
                while (true) {
                    items.add(parseOperand())
                    if (!matchPunct(',')) break
                }
            }
            expectPunct(')')
            return Expr.InList(left, items)
        }
        if (peekWord() == "IS") throw unsupported(sql, "IS / IS NULL（请改用 = / != 或 IN）")
        if (peekWord() == "LIKE" || peekWord() == "BETWEEN") throw unsupported(sql, peekWord()!!)
        val op = when {
            peekPunct() == "=" -> "="
            peekPunct() == "!=" -> "!="
            peekPunct() == "<>" -> "!="
            peekPunct() == "<" -> "<"
            peekPunct() == ">" -> ">"
            peekPunct() == "<=" -> "<="
            peekPunct() == ">=" -> ">="
            else -> return left
        }
        next()
        return Expr.Compare(op, left, parseOperand())
    }

    private fun parseOperand(): Expr {
        if (peekPunct() == "-") {
            next()
            val n = expectNumber()
            if (n == Long.MIN_VALUE) throw sqlError(sql, "数字超范围")
            return Expr.Lit(SqlValue.VInt(-n))
        }
        if (matchPunct('(')) {
            val inner = parseExpr()
            expectPunct(')')
            return inner
        }
        val tok = peek()
        return when (tok.kind) {
            TokKind.NUMBER -> {
                next()
                Expr.Lit(SqlValue.VInt(tok.num))
            }
            TokKind.STRING -> {
                next()
                Expr.Lit(SqlValue.VText(tok.text))
            }
            TokKind.PARAM -> {
                next()
                Expr.Param(tok.text.toInt())
            }
            TokKind.IDENT -> {
                next()
                when (tok.text.uppercase()) {
                    "NULL" -> Expr.Lit(SqlValue.VNull)
                    "TRUE" -> Expr.Lit(SqlValue.VInt(1))
                    "FALSE" -> Expr.Lit(SqlValue.VInt(0))
                    "EXCLUDED" -> {
                        expectPunct('.')
                        Expr.Excluded(expectIdent("excluded 列名"))
                    }
                    else -> Expr.Column(tok.text)
                }
            }
            else -> throw sqlError(sql, "表达式里出现无法解析的 \"${tok.text}\"")
        }
    }

    private fun parseExprList(): List<Expr> {
        val out = ArrayList<Expr>()
        while (true) {
            out.add(parseExpr())
            if (!matchPunct(',')) break
        }
        return out
    }

    private fun parseLiteralValue(): SqlValue {
        if (peekPunct() == "-") {
            next()
            val n = expectNumber()
            if (n == Long.MIN_VALUE) throw sqlError(sql, "数字超范围")
            return SqlValue.VInt(-n)
        }
        val tok = peek()
        return when (tok.kind) {
            TokKind.NUMBER -> {
                next()
                SqlValue.VInt(tok.num)
            }
            TokKind.STRING -> {
                next()
                SqlValue.VText(tok.text)
            }
            TokKind.IDENT -> {
                next()
                when (tok.text.uppercase()) {
                    "NULL" -> SqlValue.VNull
                    "TRUE" -> SqlValue.VInt(1)
                    "FALSE" -> SqlValue.VInt(0)
                    else -> throw sqlError(sql, "DEFAULT 只支持字面量，遇到 \"${tok.text}\"")
                }
            }
            else -> throw sqlError(sql, "DEFAULT 只支持字面量")
        }
    }

    private fun parseColumnNameList(): List<String> {
        expectPunct('(')
        return parseColumnNameListAfterOpen()
    }

    private fun parseColumnNameListAfterOpen(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            out.add(expectIdent("列名"))
            if (!matchPunct(',')) break
        }
        expectPunct(')')
        return out
    }

    private fun parseColumnNameListNoParen(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            out.add(expectIdent("列名"))
            if (!matchPunct(',')) break
        }
        return out
    }

    // ---- 词法辅助 ----

    private fun atEnd(): Boolean = peek().kind == TokKind.END

    private fun peek(): Tok = toks[pos]

    private fun peekWord(): String? {
        val t = peek()
        return if (t.kind == TokKind.IDENT) t.text.uppercase() else null
    }

    private fun peekPunct(): String? {
        val t = peek()
        return if (t.kind == TokKind.PUNCT) t.text else null
    }

    private fun next(): Tok = toks[pos].also { if (pos < toks.size - 1) pos += 1 }

    private fun matchWord(word: String): Boolean {
        val t = peek()
        if (t.kind == TokKind.IDENT && t.text.equals(word, ignoreCase = true)) {
            next()
            return true
        }
        return false
    }

    private fun expectWord(word: String) {
        if (!matchWord(word)) throw sqlError(sql, "期望关键字 $word，实际是 \"${peek().text}\"")
    }

    private fun matchPunct(p: Char): Boolean {
        val t = peek()
        if (t.kind == TokKind.PUNCT && t.text.length == 1 && t.text[0] == p) {
            next()
            return true
        }
        return false
    }

    private fun expectPunct(p: Char) {
        if (!matchPunct(p)) throw sqlError(sql, "期望 \"$p\"，实际是 \"${peek().text}\"")
    }

    private fun expectIdent(what: String): String {
        val t = peek()
        if (t.kind != TokKind.IDENT) throw sqlError(sql, "期望$what，实际是 \"${t.text}\"")
        next()
        return t.text
    }

    private fun expectNumber(): Long {
        val t = peek()
        if (t.kind != TokKind.NUMBER) throw sqlError(sql, "期望数字，实际是 \"${t.text}\"")
        next()
        return t.num
    }

    private fun skipSemicolons() {
        while (peekPunct() == ";") next()
    }

    private companion object {
        val COLUMN_CONSTRAINTS = setOf(
            "PRIMARY", "NOT", "NULL", "DEFAULT", "UNIQUE", "CHECK",
            "REFERENCES", "COLLATE", "AUTOINCREMENT", "CONSTRAINT", "GENERATED",
        )
        val JOIN_WORDS = setOf(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER", "UNION",
        )
    }
}

private fun affinityOfType(type: String): SqlAffinity = when (type.uppercase()) {
    "INTEGER", "INT", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "NUMERIC", "DECIMAL" -> SqlAffinity.INTEGER
    "TEXT", "VARCHAR", "NVARCHAR", "CHAR", "NCHAR", "CLOB", "STRING" -> SqlAffinity.TEXT
    "REAL", "DOUBLE", "FLOAT" -> SqlAffinity.NONE
    "BLOB", "NONE" -> SqlAffinity.NONE
    else -> SqlAffinity.NONE
}

// ===========================================================================
// 执行：DDL
// ===========================================================================

private fun createTable(db: SqlDatabase, stmt: Stmt.CreateTable, sql: String): Int {
    val existing = db.table(stmt.name)
    if (existing != null) {
        if (stmt.ifNotExists) return 0
        throw sqlError(sql, "表 \"${stmt.name}\" 已存在（CREATE TABLE 需要 IF NOT EXISTS）")
    }
    val columns = stmt.columns.map {
        SqlColumn(it.name, it.affinity, it.notNull, it.default)
    }.toMutableList()
    val table = SqlTable(stmt.name, columns)
    val pkNames = ArrayList<String>()
    for (def in stmt.columns) if (def.primaryKey) pkNames.add(def.name)
    stmt.primaryKey?.let { pkNames.addAll(it) }
    table.keyColumns = pkNames.map { name ->
        table.indexOf(name) ?: throw sqlError(sql, "PRIMARY KEY 引用了不存在的列 \"$name\"")
    }
    db.tables[stmt.name.lowercase()] = table
    return 1
}

private fun dropTable(db: SqlDatabase, stmt: Stmt.DropTable, sql: String): Int {
    val key = stmt.name.lowercase()
    if (db.tables.remove(key) != null) return 1
    if (stmt.ifExists) return 0
    throw sqlError(sql, "表 \"${stmt.name}\" 不存在（DROP TABLE 需要 IF EXISTS）")
}

// ===========================================================================
// 执行：DML
// ===========================================================================

private fun insertRows(db: SqlDatabase, stmt: Stmt.Insert, params: List<SqlValue>, sql: String): Int {
    val table = db.table(stmt.table)
        ?: throw sqlError(sql, "表 \"${stmt.table}\" 不存在（INSERT 前先 CREATE TABLE）")
    var count = 0
    for (valueExprs in stmt.rows) {
        val proposed = buildProposedRow(table, stmt.columns, valueExprs, params, sql)
        val conflict = findConflict(table, proposed)
        when {
            stmt.onConflict != null -> {
                if (conflict < 0) {
                    table.rows.add(proposed)
                } else if (!stmt.onConflict.doNothing) {
                    val existing = table.rows[conflict]
                    for ((col, expr) in stmt.onConflict.sets) {
                        val idx = table.indexOf(col)
                            ?: throw sqlError(sql, "ON CONFLICT 的 SET 引用了不存在的列 \"$col\"")
                        val env = EvalEnv(sql, table, params, existing, proposed)
                        existing[idx] = applyAffinity(evalExpr(expr, env), table.columns[idx].affinity)
                    }
                }
            }
            conflict >= 0 && stmt.orReplace -> {
                table.rows.removeAt(conflict)
                table.rows.add(proposed)
            }
            conflict >= 0 -> throw sqlError(
                sql,
                "主键冲突：表 \"${table.name}\" 里已有相同主键的行（语料约定用 INSERT OR REPLACE 或 ON CONFLICT）",
            )
            else -> table.rows.add(proposed)
        }
        count += 1
    }
    return count
}

private fun buildProposedRow(
    table: SqlTable,
    columnNames: List<String>?,
    valueExprs: List<Expr>,
    params: List<SqlValue>,
    sql: String,
): MutableList<SqlValue> {
    val env = EvalEnv(sql, table, params, null, null)
    val provided = if (columnNames == null) {
        if (valueExprs.size != table.columns.size) {
            throw sqlError(sql, "VALUES 的列数 ${valueExprs.size} 与表 ${table.name} 的列数 ${table.columns.size} 不一致")
        }
        valueExprs.mapIndexed { i, e -> i to e }
    } else {
        if (columnNames.size != valueExprs.size) {
            throw sqlError(sql, "INSERT 的列名个数 ${columnNames.size} 与 VALUES 的值个数 ${valueExprs.size} 不一致")
        }
        columnNames.mapIndexed { i, name ->
            val idx = table.indexOf(name) ?: throw sqlError(sql, "列 \"$name\" 不是表 ${table.name} 的列")
            idx to valueExprs[i]
        }
    }
    val row = MutableList<SqlValue>(table.columns.size) { SqlValue.VNull }
    val assigned = BooleanArray(table.columns.size)
    for ((idx, expr) in provided) {
        if (assigned[idx]) throw sqlError(sql, "INSERT 里列 \"${table.columns[idx].name}\" 重复出现")
        assigned[idx] = true
        row[idx] = applyAffinity(evalExpr(expr, env), table.columns[idx].affinity)
    }
    for (i in table.columns.indices) {
        if (assigned[i]) {
            if (row[i] is SqlValue.VNull && table.columns[i].notNull) {
                throw sqlError(sql, "列 \"${table.columns[i].name}\" 是 NOT NULL，不能写入 NULL")
            }
            continue
        }
        val def = table.columns[i].default
        row[i] = when {
            def != null -> def
            table.columns[i].notNull ->
                throw sqlError(sql, "列 \"${table.columns[i].name}\" 是 NOT NULL 且没有 DEFAULT，INSERT 必须给值")
            else -> SqlValue.VNull
        }
    }
    return row
}

/** 主键冲突行下标（-1 = 无冲突；无主键表恒 -1）。 */
private fun findConflict(table: SqlTable, proposed: List<SqlValue>): Int {
    if (table.keyColumns.isEmpty()) return -1
    for (i in table.rows.indices) {
        val row = table.rows[i]
        var same = true
        for (k in table.keyColumns) {
            if (!valuesEqual(row[k], proposed[k])) {
                same = false
                break
            }
        }
        if (same) return i
    }
    return -1
}

private fun updateRows(db: SqlDatabase, stmt: Stmt.Update, params: List<SqlValue>, sql: String): Int {
    val table = db.table(stmt.table) ?: return 0
    var affected = 0
    for (row in table.rows) {
        val env = EvalEnv(sql, table, params, row, null)
        if (stmt.where != null && !evalPredicate(stmt.where, env)) continue
        for ((col, expr) in stmt.sets) {
            val idx = table.indexOf(col) ?: throw sqlError(sql, "SET 引用了表 ${table.name} 里不存在的列 \"$col\"")
            row[idx] = applyAffinity(evalExpr(expr, env), table.columns[idx].affinity)
        }
        affected += 1
    }
    return affected
}

private fun deleteRows(db: SqlDatabase, stmt: Stmt.Delete, params: List<SqlValue>, sql: String): Int {
    val table = db.table(stmt.table) ?: return 0
    val before = table.rows.size
    if (stmt.where == null) {
        table.rows.clear()
    } else {
        val keep = ArrayList<MutableList<SqlValue>>(table.rows.size)
        for (row in table.rows) {
            val env = EvalEnv(sql, table, params, row, null)
            if (!evalPredicate(stmt.where, env)) keep.add(row)
        }
        table.rows.clear()
        table.rows.addAll(keep)
    }
    return before - table.rows.size
}

// ===========================================================================
// 执行：SELECT
// ===========================================================================

private fun select(db: SqlDatabase, stmt: Stmt.Select, params: List<SqlValue>, sql: String): SqlExecResult {
    val table = db.table(stmt.table)
    if (table == null) {
        // 见文件头「已知差异」：外部生成的 db 缓存表缺失时按空表语义
        return SqlExecResult.Rows(emptyList())
    }
    val columnIndices = resolveSelectColumns(table, stmt, sql)
    val groupIndices = stmt.groupBy.map { name ->
        table.indexOf(name) ?: throw sqlError(sql, "GROUP BY 引用了不存在的列 \"$name\"")
    }
    val orderKeys = stmt.orderBy.map { key ->
        val idx = table.indexOf(key.column)
            ?: throw sqlError(sql, "ORDER BY 引用了不存在的列 \"${key.column}\"")
        Triple(idx, key.descending, key.column)
    }

    val filtered = ArrayList<MutableList<SqlValue>>()
    val hasCount = stmt.items.any { it is SelectItem.CountStar }
    for (row in table.rows) {
        val env = EvalEnv(sql, table, params, row, null)
        if (stmt.where != null && !evalPredicate(stmt.where, env)) continue
        filtered.add(row)
    }

    // 输出对 + 排序源行（分组时用组内首行；COUNT-only 时可能为 null）
    val pairs = ArrayList<Pair<List<SqlValue>, MutableList<SqlValue>?>>()
    if (groupIndices.isEmpty()) {
        if (hasCount && stmt.items.size == 1) {
            pairs.add(listOf(SqlValue.VInt(filtered.size.toLong())) to filtered.firstOrNull())
        } else {
            for (row in filtered) {
                pairs.add(projectRow(table, columnIndices, row) to row)
            }
        }
    } else {
        val groups = LinkedHashMap<List<SqlValue>, MutableList<MutableList<SqlValue>>>()
        for (row in filtered) {
            val key = groupIndices.map { row[it] }
            groups.getOrPut(key) { ArrayList() }.add(row)
        }
        for ((_, rowsInGroup) in groups) {
            val rep = rowsInGroup.firstOrNull()
            val out = ArrayList<SqlValue>(stmt.items.size)
            for (item in stmt.items) {
                out.add(
                    when (item) {
                        is SelectItem.CountStar -> SqlValue.VInt(rowsInGroup.size.toLong())
                        is SelectItem.Column -> {
                            val idx = table.indexOf(item.name)
                                ?: throw sqlError(sql, "SELECT 引用了不存在的列 \"${item.name}\"")
                            rep?.get(idx) ?: SqlValue.VNull
                        }
                    },
                )
            }
            pairs.add(out to rep)
        }
    }

    var result: List<Pair<List<SqlValue>, MutableList<SqlValue>?>> = pairs
    if (stmt.distinct) {
        val seen = LinkedHashSet<List<SqlValue>>()
        result = result.filter { seen.add(it.first) }
    }
    if (orderKeys.isNotEmpty()) {
        result = result.sortedWith(Comparator { a, b ->
            for ((idx, desc, _) in orderKeys) {
                val av = a.second?.get(idx) ?: SqlValue.VNull
                val bv = b.second?.get(idx) ?: SqlValue.VNull
                val c = compareNullable(av, bv)
                if (c != 0) return@Comparator if (desc) -c else c
            }
            0
        })
    }
    val limit = stmt.limit
    if (limit != null) result = result.take(limit.coerceAtLeast(0L).toInt())
    return SqlExecResult.Rows(result.map { it.first })
}

private fun resolveSelectColumns(table: SqlTable, stmt: Stmt.Select, sql: String): List<Int?> =
    stmt.items.map { item ->
        when (item) {
            is SelectItem.CountStar -> null
            is SelectItem.Column -> table.indexOf(item.name)
                ?: throw sqlError(sql, "SELECT 引用了不存在的列 \"${item.name}\"")
        }
    }

private fun projectRow(table: SqlTable, indices: List<Int?>, row: List<SqlValue>): List<SqlValue> =
    indices.map { if (it == null) SqlValue.VNull else row[it] }

// ===========================================================================
// 表达式求值
// ===========================================================================

private data class EvalEnv(
    val sql: String,
    val table: SqlTable,
    val params: List<SqlValue>,
    val row: List<SqlValue>?,
    val excluded: List<SqlValue>?,
)

private fun evalExpr(e: Expr, env: EvalEnv): SqlValue = when (e) {
    is Expr.Lit -> e.value
    is Expr.Param -> env.params.getOrNull(e.index)
        ?: throw sqlError(
            env.sql,
            "参数化查询缺少第 @${e.index} 个实参（本次只传了 ${env.params.size} 个）",
        )
    is Expr.Column -> {
        val idx = env.table.indexOf(e.name)
            ?: throw sqlError(env.sql, "列 \"${e.name}\" 不是表 ${env.table.name} 的列")
        env.row?.get(idx) ?: throw sqlError(env.sql, "此处不能引用列 \"${e.name}\"（没有当前行）")
    }
    is Expr.Excluded -> {
        val idx = env.table.indexOf(e.name)
            ?: throw sqlError(env.sql, "excluded.${e.name} 不是表 ${env.table.name} 的列")
        env.excluded?.get(idx)
            ?: throw sqlError(env.sql, "excluded.${e.name} 只能出现在 ON CONFLICT DO UPDATE 里")
    }
    is Expr.Compare, is Expr.And, is Expr.Or, is Expr.Not, is Expr.InList ->
        SqlValue.VInt(if (evalPredicate(e, env)) 1L else 0L)
}

private fun evalPredicate(e: Expr, env: EvalEnv): Boolean = when (e) {
    is Expr.And -> evalPredicate(e.left, env) && evalPredicate(e.right, env)
    is Expr.Or -> evalPredicate(e.left, env) || evalPredicate(e.right, env)
    is Expr.Not -> !evalPredicate(e.inner, env)
    is Expr.Compare -> evalCompare(e.op, e.left, e.right, env)
    is Expr.InList -> evalInList(e, env)
    else -> truthy(evalExpr(e, env))
}

private fun truthy(v: SqlValue): Boolean = when (v) {
    is SqlValue.VInt -> v.v != 0L
    is SqlValue.VText -> v.v.trim().toLongOrNull()?.let { it != 0L } ?: false
    SqlValue.VNull -> false
}

private fun evalCompare(op: String, left: Expr, right: Expr, env: EvalEnv): Boolean {
    var lv = evalExpr(left, env)
    var rv = evalExpr(right, env)
    val lAff = affinityOfExpr(left, env.table)
    val rAff = affinityOfExpr(right, env.table)
    when {
        lAff != null && rAff == null -> rv = applyAffinity(rv, lAff)
        rAff != null && lAff == null -> lv = applyAffinity(lv, rAff)
        lAff != null && rAff != null -> {
            lv = applyAffinity(lv, lAff)
            rv = applyAffinity(rv, rAff)
        }
    }
    // SQLite：NULL 与任何值的比较都是 UNKNOWN（谓词里按 false 处理）
    if (lv is SqlValue.VNull || rv is SqlValue.VNull) return false
    val c = compareValues(lv, rv)
    return when (op) {
        "=" -> c == 0
        "!=" -> c != 0
        "<" -> c < 0
        ">" -> c > 0
        "<=" -> c <= 0
        ">=" -> c >= 0
        else -> throw sqlError(env.sql, "未知比较运算符 $op")
    }
}

private fun evalInList(e: Expr.InList, env: EvalEnv): Boolean {
    val targetAff = affinityOfExpr(e.target, env.table)
    var target = evalExpr(e.target, env)
    for (item in e.items) {
        var iv = evalExpr(item, env)
        val itemAff = affinityOfExpr(item, env.table)
        var tv = target
        when {
            targetAff != null -> iv = applyAffinity(iv, targetAff)
            itemAff != null -> tv = applyAffinity(tv, itemAff)
        }
        if (tv is SqlValue.VNull || iv is SqlValue.VNull) continue
        if (compareValues(tv, iv) == 0) return true
    }
    return false
}

private fun affinityOfExpr(e: Expr, table: SqlTable): SqlAffinity? = when (e) {
    is Expr.Column -> table.indexOf(e.name)?.let { table.columns[it].affinity }
    is Expr.Excluded -> table.indexOf(e.name)?.let { table.columns[it].affinity }
    else -> null
}

/** 值类型转换（`SQLite` 的列亲和性）：INTEGER 列把数字样文本转数字，TEXT 列把数字转文本。 */
private fun applyAffinity(v: SqlValue, affinity: SqlAffinity): SqlValue = when (affinity) {
    SqlAffinity.INTEGER -> when (v) {
        is SqlValue.VText -> v.v.trim().toLongOrNull()?.let { SqlValue.VInt(it) } ?: v
        else -> v
    }
    SqlAffinity.TEXT -> when (v) {
        is SqlValue.VInt -> SqlValue.VText(v.v.toString())
        else -> v
    }
    SqlAffinity.NONE -> v
}

private fun compareValues(a: SqlValue, b: SqlValue): Int = when {
    a is SqlValue.VInt && b is SqlValue.VInt -> a.v.compareTo(b.v)
    a is SqlValue.VText && b is SqlValue.VText -> a.v.compareTo(b.v)
    // SQLite 的存储类排序：NULL < 数字 < 文本
    a is SqlValue.VInt && b is SqlValue.VText -> -1
    a is SqlValue.VText && b is SqlValue.VInt -> 1
    else -> 0
}

private fun compareNullable(a: SqlValue, b: SqlValue): Int = when {
    a is SqlValue.VNull && b is SqlValue.VNull -> 0
    a is SqlValue.VNull -> -1
    b is SqlValue.VNull -> 1
    else -> compareValues(a, b)
}

private fun valuesEqual(a: SqlValue, b: SqlValue): Boolean = when {
    a is SqlValue.VNull && b is SqlValue.VNull -> true
    a is SqlValue.VNull || b is SqlValue.VNull -> false
    else -> compareValues(a, b) == 0
}

// ===========================================================================
// 错误文案
// ===========================================================================

/** 解析/执行错误带**语句原文**，便于在语料里定位（硬约束：不支持必须抛错）。 */
internal fun sqlError(sql: String, detail: String): CodeEE =
    CodeEE("SQL 执行失败：$detail（语句：${sql.trim()}）")

private fun unsupported(sql: String, what: String): CodeEE =
    CodeEE("SQL 不支持：$what（语句：${sql.trim()}）")
