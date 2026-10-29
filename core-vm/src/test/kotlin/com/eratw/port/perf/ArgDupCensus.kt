package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.stmt.Statement
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 语句 `args` 子树的**结构重复率**普查（只测量，不改语义）。
 *
 * ## 为什么另一张表
 *
 * [PoolCensus] 与 `PoolDecomp` 回答的是「这一块占多少内存」，两者都不能回答
 * 「把结构相等的节点归一成一个实例能省多少」：前者按语句各自开 visited 集（跨语句
 * 重复计入，合计是上限），后者身份去重但把每个实例都算成必需。本表补的正是
 * 「可回收上限」这一读数 —— 逐类给出实例数 / 结构形态数 / 重复实例的浅尺寸合计。
 *
 * ## 口径
 *
 * - 根集合 = 每条语句除 `rawSlot`/`String`/基本类型之外的全部对象字段（`args` +
 *   各语句自带的 `condition`/`cases`/`values`/`dest` 等）。`code`/`line` 是值类型，不进计数。
 * - **实例数**用全局 `IdentityHashMap` 去重（跨语句只算一次），故 `Σ 浅尺寸` 与
 *   `PoolDecomp` 的语句池 args 行同源、可对拍。
 * - **结构形态** = 递归哈希：`h(node) = mix(class, Σ 原子字段值哈希, Σ h(子))`。
 *   原子字段 = 基本类型/String/枚举/装箱值/基本类型数组/`Array<String>`（按内容比较）；
 *   其余对象字段（`Expr`/`Argument`/`List`/`CaseExpression`…）是子节点。
 *   列表按元素递归，`ArrayList` 的背衬数组不单独计（与 `SizeModel` 的 List 模型一致）。
 * - 64 位哈希计数：全量语料千万级节点下冲突概率 ~1e-7，报告里按「形态数」读，
 *   不与 JVM 精确读数混用。
 * - **可回收上限** = Σ_重复实例 该实例浅尺寸（近似用「同类实例平均浅尺寸」，同形态
 *   实例的浅尺寸在实测里一致）。这是**上限**：父节点也重复时，子节点的回收已经
 *   包含在父节点被丢弃的整棵子树里，逐节点相加不会重复计——每个实例只加一次。
 */
internal object ArgDupCensus {

    /** 一行：类 / 实例数（跨语句身份去重） / 结构形态数 / 浅尺寸合计 / 可回收上限。 */
    internal class Row(
        val cls: String,
        val instances: Int,
        val forms: Int,
        val shallowBytes: Long,
        val reclaimBytes: Long,
    ) {
        val dup: Int get() = instances - forms
        val dupPct: Int get() = if (instances == 0) 0 else (dup * 100.0 / instances).toInt()
    }

    internal class Report(
        val rows: List<Row>,
        val roots: Int,
        val instances: Int,
        val forms: Int,
        val shallowBytes: Long,
        val reclaimBytes: Long,
        val walkMs: Long,
        /** 字符串实例的首次持有者账单（`类.字段 条数/MiB`），定位重复字符串来源。 */
        val stringOwners: String,
    ) {
        val dup: Int get() = instances - forms

        /** MiB 文本（报告表格用，与 [PoolCensus] 的列口径一致）。 */
        fun bytes(v: Long): String = String.format("%.1f MiB", v / (1024.0 * 1024.0))

        /** 逐类表格（Markdown；[limit] = 只列前 n 行，0 = 全量）。 */
        fun renderTable(limit: Int = 0): String {
            val sb = StringBuilder()
            sb.appendLine("| 类 | 实例 | 形态 | 重复 | 重复率 | 浅尺寸 | 可回收上限 |")
            sb.appendLine("|---|---:|---:|---:|---:|---:|---:|")
            val rowsOut = if (limit > 0) rows.take(limit) else rows
            for (r in rowsOut) {
                sb.appendLine(
                    "| ${r.cls} | ${r.instances} | ${r.forms} | ${r.dup} | ${r.dupPct}% | " +
                        "${bytes(r.shallowBytes)} | ${bytes(r.reclaimBytes)} |",
                )
            }
            sb.appendLine(
                "| **合计** | **$instances** | **$forms** | **$dup** | " +
                    "**${if (instances == 0) 0 else dup * 100 / instances}%** | " +
                    "**${bytes(shallowBytes)}** | **${bytes(reclaimBytes)}** |",
            )
            return sb.toString()
        }

        fun logLines(): List<String> {
            val out = ArrayList<String>()
            out += "[dup] 语句 args 子树结构重复率（$roots 条语句 / 遍历 ${walkMs}ms；实例 $instances / 形态 $forms）"
            out += "[dup]   浅尺寸合计 ${mib(shallowBytes)}，可回收上限 ${mib(reclaimBytes)}" +
                "（${if (instances == 0) "0" else (dup * 100 / instances)}% 实例、${pct(reclaimBytes, shallowBytes)} 字节）"
            out += "[dup]   前 20 类："
            out += "| 类 | 实例 | 形态 | 重复 | 浅尺寸 | 可回收 |"
            out += "|---|---:|---:|---:|---:|---:|"
            for (r in rows.take(20)) {
                out += "| ${r.cls} | ${r.instances} | ${r.forms} | ${r.dup} (${r.dupPct}%) | " +
                    "${mib(r.shallowBytes)} | ${mib(r.reclaimBytes)} |"
            }
            out += "[dup]   字符串持有者前 15：$stringOwners"
            return out
        }
    }

    private fun pct(a: Long, b: Long): String =
        if (b <= 0) "0%" else "${(a * 1000 / b) / 10.0}%"

    private fun mib(v: Long): String = String.format("%.1f MiB", v / (1024.0 * 1024.0))

    // ------------------------------------------------------------------
    // 反射形状表（每类只构造一次）
    // ------------------------------------------------------------------

    /** 一个类的字段分类：[atoms] 值比较、[kids] 递归、[listLike] 按元素递归。 */
    private class Shape(
        val atoms: Array<Field>,
        val kids: Array<Field>,
        val listLike: Boolean,
    )

    private val shapes = HashMap<Class<*>, Shape>()

    private fun shapeOf(c: Class<*>): Shape = shapes.getOrPut(c) {
        if (List::class.java.isAssignableFrom(c)) return@getOrPut Shape(emptyArray(), emptyArray(), true)
        val atoms = ArrayList<Field>()
        val kids = ArrayList<Field>()
        val isExpr = com.eratw.port.core.expr.Expr::class.java.isAssignableFrom(c)
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.name == "rawSlot") continue
                // `Expr` 的 line/col 运行期零读者（见 ArgInterner KDoc），按「不参与结构」处理，
                // 与去重实现同一口径 —— 否则把「同一形态但行号不同」误算成不同形态。
                if (isExpr && (f.name == "line" || f.name == "col")) continue
                if (!runCatching { f.trySetAccessible() }.getOrDefault(false)) continue
                if (isAtomType(f.type)) atoms.add(f) else kids.add(f)
            }
            cl = cl.superclass
        }
        Shape(atoms.toTypedArray(), kids.toTypedArray(), false)
    }

    private fun isAtomType(t: Class<*>): Boolean = t.isPrimitive ||
        t == String::class.java ||
        t == java.lang.Integer::class.java || t == java.lang.Long::class.java ||
        t == java.lang.Boolean::class.java || t == java.lang.Double::class.java ||
        t == java.lang.Float::class.java || t == java.lang.Short::class.java ||
        t == java.lang.Character::class.java || t == java.lang.Byte::class.java ||
        t.isEnum ||
        t == IntArray::class.java || t == LongArray::class.java ||
        t == DoubleArray::class.java || t == BooleanArray::class.java ||
        t == Array<String>::class.java ||
        Number::class.java.isAssignableFrom(t)

    // ------------------------------------------------------------------
    // 遍历
    // ------------------------------------------------------------------

    private class Acc {
        /** 实例 → 结构哈希（同时充当 visited 集）。 */
        val hash = IdentityHashMap<Any, Long>()

        /** 类名 → (实例数, 浅尺寸)。 */
        val clsCount = HashMap<String, Int>()
        val clsBytes = HashMap<String, Long>()

        /** 结构哈希 → 首次出现的类名（形态去重）。 */
        val forms = HashMap<Long, String>()
        val clsForms = HashMap<String, Int>()

        /** 字符串实例 → 首次持有它的「类.字段」（定位重复字符串来自哪一侧，报告用）。 */
        val stringOwner = IdentityHashMap<Any, String>()
        val stringOwnerCount = HashMap<String, Int>()
        val stringOwnerBytes = HashMap<String, Long>()
    }

    fun census(program: Program, sizer: SizeModel): Report {
        val t0 = System.nanoTime()
        val acc = Acc()
        var roots = 0
        for (i in 0 until program.statementCount) {
            val s = program.statement(i)
            roots++
            val shape = shapeOf(s.javaClass)
            for (f in shape.kids) {
                val v = runCatching { f.get(s) }.getOrNull() ?: continue
                visit(v, acc, sizer)
            }
        }
        val rows = ArrayList<Row>()
        var totalInstances = 0
        var totalForms = 0
        var totalBytes = 0L
        var totalReclaim = 0L
        for (name in acc.clsCount.keys) {
            val n = acc.clsCount[name] ?: 0
            val forms = acc.clsForms[name] ?: 0
            val bytes = acc.clsBytes[name] ?: 0L
            val avg = if (n == 0) 0L else bytes / n
            val reclaim = (n - forms).coerceAtLeast(0).toLong() * avg
            rows += Row(name, n, forms, bytes, reclaim)
            totalInstances += n
            totalForms += forms
            totalBytes += bytes
            totalReclaim += reclaim
        }
        rows.sortByDescending { it.reclaimBytes }
        val owners = acc.stringOwnerCount.entries.sortedByDescending { acc.stringOwnerBytes[it.key] ?: 0L }
            .take(15)
            .joinToString("；") { "${it.key} ${it.value}/${String.format("%.1f", (acc.stringOwnerBytes[it.key] ?: 0L) / 1048576.0)} MiB" }
        return Report(
            rows = rows,
            roots = roots,
            instances = totalInstances,
            forms = totalForms,
            shallowBytes = totalBytes,
            reclaimBytes = totalReclaim,
            walkMs = (System.nanoTime() - t0) / 1_000_000,
            stringOwners = owners,
        )
    }

    /** 返回 [o] 的结构哈希；同一实例只走一次。 */
    private fun visit(o: Any, acc: Acc, sizer: SizeModel): Long {
        acc.hash[o]?.let { return it }
        val c = o.javaClass
        var h = c.name.hashCode().toLong()
        // String 按内容当「值节点」：浅尺寸走 compact strings 模型（32B 壳 + payload）
        val shallow = if (o is String) SizeModel.stringBytes(o) else sizer.objectSize(o)
        val name = if (o is String) "String" else (c.simpleName ?: c.name)
        acc.clsCount.merge(name, 1) { a, b -> a + b }
        acc.clsBytes.merge(name, shallow) { a, b -> a + b }

        if (o is String) {
            h = h * 31 + o.hashCode()
            if (acc.forms.putIfAbsent(h, name) == null) {
                acc.clsForms.merge(name, 1) { a, b -> a + b }
            }
            acc.hash[o] = h
            return h
        }

        val shape = shapeOf(c)
        if (shape.listLike) {
            val list = o as List<*>
            var i = 0
            for (e in list) {
                if (e is String) recordOwner(e, "List<String>", acc)
                h = h * 31 + if (e == null) -1 else visit(e, acc, sizer)
                i++
            }
            h = h * 31 + i
        } else {
            for (f in shape.atoms) {
                val v = runCatching { f.get(o) }.getOrNull()
                if (v is String) recordOwner(v, "${c.simpleName ?: c.name}.${f.name}", acc)
                h = h * 31 + atomHash(v, acc, sizer)
            }
            for (f in shape.kids) {
                val v = runCatching { f.get(o) }.getOrNull() ?: continue
                h = h * 31 + visit(v, acc, sizer)
            }
        }
        // 形态登记：哈希全局唯一化成「形态」；同哈希不同类视为冲突（报告里可见）
        if (acc.forms.putIfAbsent(h, name) == null) {
            acc.clsForms.merge(name, 1) { a, b -> a + b }
        }
        acc.hash[o] = h
        return h
    }

    /**
     * 一条语句的**纯结构哈希**（不依赖实例身份，不做记忆化）：用于「去重前后逐语句同构」
     * 的全量对拍。根集合与 [census] 相同（`rawSlot` 除外），`Expr.line/col` 不参与。
     */
    fun statementHash(s: Statement): Long {
        var h = s.javaClass.name.hashCode().toLong()
        val shape = shapeOf(s.javaClass)
        for (f in shape.atoms) h = h * 31 + pureAtomHash(runCatching { f.get(s) }.getOrNull())
        for (f in shape.kids) {
            val v = runCatching { f.get(s) }.getOrNull() ?: continue
            h = h * 31 + pureNodeHash(v)
        }
        return h
    }

    /** 诊断入口：子树的结构哈希（与 [statementHash] 同口径，不做记忆化）。 */
    fun pureHash(o: Any?): Long = if (o == null) 0L else pureNodeHash(o)

    /** 诊断入口：某类在哈希/相等判定里参与的字段（`Expr` 的 `line`/`col` 已排除）。 */
    fun hashFieldsOf(c: Class<*>): Array<Field> {
        val shape = shapeOf(c)
        return shape.atoms + shape.kids
    }

    private fun pureNodeHash(o: Any): Long {        if (o is String) return o.hashCode().toLong()
        if (o is List<*>) {
            var h = 0x51EDL
            for (e in o) h = h * 31 + if (e == null) 0L else pureNodeHash(e)
            return h * 31 + o.size
        }
        var h = o.javaClass.name.hashCode().toLong()
        val shape = shapeOf(o.javaClass)
        for (f in shape.atoms) h = h * 31 + pureAtomHash(runCatching { f.get(o) }.getOrNull())
        for (f in shape.kids) {
            val v = runCatching { f.get(o) }.getOrNull() ?: continue
            h = h * 31 + pureNodeHash(v)
        }
        return h
    }

    private fun pureAtomHash(v: Any?): Long = when (v) {
        null -> 0L
        is IntArray -> v.contentHashCode().toLong()
        is LongArray -> v.contentHashCode().toLong()
        is DoubleArray -> v.contentHashCode().toLong()
        is BooleanArray -> v.contentHashCode().toLong()
        is Array<*> -> {
            var h = 7L
            for (e in v) h = h * 31 + pureAtomHash(e)
            h
        }

        is Enum<*> -> v.name.hashCode().toLong()
        else -> v.hashCode().toLong()
    }

    /** 记录字符串实例的首次持有者（`类.字段`），用于「重复字符串在哪一侧」的账单。 */
    private fun recordOwner(s: String, owner: String, acc: Acc) {
        if (acc.stringOwner.putIfAbsent(s, owner) != null) return
        acc.stringOwnerCount.merge(owner, 1) { a, b -> a + b }
        acc.stringOwnerBytes.merge(owner, SizeModel.stringBytes(s)) { a, b -> a + b }
    }

    private fun atomHash(v: Any?, acc: Acc, sizer: SizeModel): Long = when (v) {        null -> 0L
        is String -> visit(v, acc, sizer)
        is IntArray -> v.contentHashCode().toLong()
        is LongArray -> v.contentHashCode().toLong()
        is DoubleArray -> v.contentHashCode().toLong()
        is BooleanArray -> v.contentHashCode().toLong()
        is Array<*> -> {
            var h = 7L
            for (e in v) {
                h = h * 31 + if (e is String) visit(e, acc, sizer) else atomHash(e, acc, sizer)
            }
            h
        }

        is Enum<*> -> v.name.hashCode().toLong()
        else -> v.hashCode().toLong()
    }
}
