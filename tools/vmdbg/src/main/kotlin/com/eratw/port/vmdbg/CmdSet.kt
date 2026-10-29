package com.eratw.port.vmdbg

import com.eratw.port.core.err.CodeEE
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `set` / `cheat`：状态注入（DBG4 P1）—— 写侧命令，与读侧的 `var` / `vars` 对偶。
 *
 * ## set
 *
 * `set <名字>[i,j,...] = <值>`：值解析顺序 = 首尾成对双引号 → 字符串字面量（剥引号，
 * 不做转义还原）；能解析成 [Long] → 整数；其余 → 原样按字符串写（整数变量会得到
 * 可读的 CodeEE 文案）。名字与下标语法与 `var` 同形（[CmdVar]），下标缺省 `[0]`。
 * 名字统一大写归一后走 [com.eratw.port.core.vars.VariableStore.writeInt] /
 * [com.eratw.port.core.vars.VariableStore.writeStr]（VariableStore.kt:250/:274 的公开
 * 写入单点，不发明第二条写路径）。写前读旧值（先整数后字符串，与 [CmdVar] 同一套
 * 回退）、写后立即回读，输出 old→new 对。
 *
 * **确认形态 = 审计 trail**（agent 无交互，不做交互式确认）：每次成功 set/cheat 追加一行
 * JSONL 到 `<work>/audit.jsonl`，字段 `ts`（epoch 毫秒）、`op`（set/cheat 原词）、`name`、
 * `idx`（数字数组）、`old` / `new`（统一字符串形式，含 null = 写前不可读）。
 *
 * 未定义名 / 维度不符 / 类型不符 / 常量赋值一律 [CodeEE] → `status=CODEEE` + 原文案 +
 * 退出码 2，不抛裸栈。
 *
 * ## cheat
 *
 * 零脑补配方（DBG4 规格 §3）：`cheat money <N>` = `MONEY[0] = N`、`cheat day <N>` =
 * `DAY[0] = N`（两变量都在 [com.eratw.port.core.vars.SystemLayout].int1D 注册，
 * SystemLayout.kt:15-16，G_INT1 可写）；`cheat raw <名字>[i]=<值>` 是 [run] 的别名。
 * eraTW 专用配方（无敌 / 全解锁 / 好感度等）涉及具体变量语义，未按语料核实前**不猜**
 * ——TODO(verify)：留组长按语料 CSV 核后追加。
 */
object CmdSet {

    /** set / cheat 共用的写入主体；[op] 只进审计行（set 或 cheat）。 */
    fun apply(
        assembled: Assembler.Assembled,
        a: Args,
        op: String,
        name: String,
        indices: LongArray,
        rhs: String,
        out: PrintStream,
    ): Int {
        val store = assembled.session.store
        val uName = name.uppercase()
        val old = readAny(store, uName, indices)
        val parsed = parseValue(rhs) ?: throw CliError("值解析失败: $rhs（整数，或首尾成对的双引号字符串）")
        try {
            when (parsed) {
                is Long -> store.writeInt(uName, indices, parsed)
                is String -> store.writeStr(uName, indices, parsed)
            }
        } catch (e: CodeEE) {
            out.println("status=CODEEE")
            out.println("message=${e.message}")
            return 2
        }
        val newStr = readAny(store, uName, indices)
        out.println("status=PASS")
        out.println("name=$uName")
        out.println("idx=${indices.joinToString(",", "[", "]")}")
        out.println("old=${old ?: "<unreadable>"}")
        out.println("new=${newStr ?: "<unreadable>"}")
        appendAudit(a, op, uName, indices, old, newStr)
        return 0
    }

    /** `set <名字>[i,j,...] = <值>`；LHS/RHS 用第一个 `=` 切分（值里可以再出现 `=`）。 */
    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val expr = a.positional.drop(1).joinToString(" ").trim()
        if (expr.isEmpty()) throw CliError("""用法: set <名字>[i,j,...] = <整数 或 "字符串">""")
        val eq = expr.indexOf('=')
        if (eq <= 0) throw CliError("set 表达式缺 '=': $expr")
        val (name, indices) = parseLhs(expr.substring(0, eq).trim())
        val rhs = expr.substring(eq + 1).trim()
        val assembled = Assembler.assemble(a)
        return apply(assembled, a, "set", name, indices, rhs, out)
    }

    /**
     * `cheat money <N>` / `cheat day <N>` / `cheat raw <名字>[i]=<值>`。
     * 参数校验先于装配（真产物装配贵，坏用法不该付装载的钱）。
     */
    fun runCheat(a: Args, out: PrintStream, err: PrintStream): Int {
        val recipe = a.positional.getOrNull(1)?.lowercase()
            ?: throw CliError("用法: cheat money <N> | cheat day <N> | cheat raw <名字>[i]=<值>")
        when (recipe) {
            "money" -> {
                val n = a.positional.getOrNull(2)?.toLongOrNull()
                    ?: throw CliError("cheat money 需要 <N> 整数实参")
                return dispatch(a, "MONEY", longArrayOf(0L), n.toString(), "cheat", out)
            }
            "day" -> {
                val n = a.positional.getOrNull(2)?.toLongOrNull()
                    ?: throw CliError("cheat day 需要 <N> 整数实参")
                return dispatch(a, "DAY", longArrayOf(0L), n.toString(), "cheat", out)
            }
            "raw" -> {
                val expr = a.positional.drop(2).joinToString(" ").trim()
                val eq = expr.indexOf('=')
                if (eq <= 0) throw CliError("cheat raw 表达式缺 '=': $expr")
                val (name, indices) = parseLhs(expr.substring(0, eq).trim())
                return dispatch(a, name, indices, expr.substring(eq + 1).trim(), "cheat", out)
            }
            else -> throw CliError(
                "未知 cheat 配方: $recipe（可用: money / day / raw；eraTW 专用配方待按语料核实后追加，见 TODO(verify)）",
            )
        }
    }

    /** 先校验后装配的共用桥（cheat 各配方用）。 */
    private fun dispatch(
        a: Args,
        name: String,
        indices: LongArray,
        rhs: String,
        op: String,
        out: PrintStream,
    ): Int {
        val assembled = Assembler.assemble(a)
        return apply(assembled, a, op, name, indices, rhs, out)
    }

    /** `名字[下标表]` → (名字, 下标)；与 [CmdVar] 同形，缺省下标 `[0]`。 */
    fun parseLhs(lhs: String): Pair<String, LongArray> {
        val open = lhs.indexOf('[')
        val name = if (open >= 0) lhs.substring(0, open).trim() else lhs.trim()
        if (name.isEmpty()) throw CliError("set/cheat 目标名为空: $lhs")
        if (open < 0) return name to longArrayOf(0L)
        val close = lhs.indexOf(']', open)
        if (close < 0) throw CliError("下标缺闭括号: $lhs")
        val body = lhs.substring(open + 1, close)
        if (body.isBlank()) throw CliError("下标为空: $lhs")
        val idx = body.split(',').map { seg ->
            seg.trim().toLongOrNull() ?: throw CliError("下标不是整数: ${seg.trim()}（$lhs）")
        }
        return name to idx.toLongArray()
    }

    /**
     * 值字面量 → [Long] / [String]：首尾成对双引号 = 字符串（剥引号，不做转义还原）；
     * 其余先试整数，失败按原样字符串（整数变量会得到可读 CodeEE）。
     */
    fun parseValue(rhs: String): Any? = when {
        rhs.length >= 2 && rhs.first() == '"' && rhs.last() == '"' -> rhs.substring(1, rhs.length - 1)
        else -> rhs.toLongOrNull() ?: rhs
    }

    /** 先整数后字符串读旧/新值；两路都 [CodeEE] 返回 null（统一字符串形式输出）。 */
    private fun readAny(store: com.eratw.port.core.vars.VariableStore, name: String, indices: LongArray): String? {
        try {
            return store.readInt(name, indices).toString()
        } catch (_: CodeEE) {
        }
        return try {
            store.readStr(name, indices)
        } catch (_: CodeEE) {
            null
        }
    }

    /**
     * 审计 trail：`<work>/audit.jsonl` 追加一行。行手工拼装而不是 [Jsonl].obj ——
     * 本行带数字数组（idx），Jsonl.obj 只出平面对象；字符串转义仍复用 [Jsonl.escape]。
     */
    private fun appendAudit(
        a: Args,
        op: String,
        name: String,
        indices: LongArray,
        old: String?,
        new: String?,
    ) {
        val work = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        val line = buildString {
            append("{\"ts\":").append(System.currentTimeMillis())
            append(",\"op\":\"").append(Jsonl.escape(op)).append('"')
            append(",\"name\":\"").append(Jsonl.escape(name)).append('"')
            append(",\"idx\":[").append(indices.joinToString(",")).append(']')
            append(",\"old\":").append(jsonValue(old))
            append(",\"new\":").append(jsonValue(new))
            append("}")
        }
        try {
            Files.write(
                work.resolve("audit.jsonl"),
                (line + "\n").toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            // 审计是旁路：写不进去不回滚注入本体，但要让人看见（stderr），不吞无声。
            System.err.println("vmdbg: audit.jsonl 追加失败: ${e.message}")
        }
    }

    /** old/new 统一字符串形式（null = 写前不可读 / 写后不可读，JSON null）。 */
    private fun jsonValue(v: String?): String =
        if (v == null) "null" else "\"" + Jsonl.escape(v) + "\""

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}
