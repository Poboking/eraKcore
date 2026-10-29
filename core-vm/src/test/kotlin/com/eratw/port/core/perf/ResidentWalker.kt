package com.eratw.port.core.perf

import com.eratw.port.perf.SizeModel
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 常驻对象图遍历器（只测量，不改语义）。
 *
 * 与 [SizeModel.deepSize] 的分工：`deepSize` 对 `java.util` 的 Map 只能计到浅尺寸
 * （`HashMap.table` / `LinkedHashMap.after` 在 java.base 强封装下 `trySetAccessible` 返回
 * false），本类对 Map/Set/List/Collection 改用**公开 API 迭代 + 容器布局建模**，其余对象
 * 仍走 [SizeModel] 的浅尺寸与字段反射。尺寸口径（对象头 12B、8 对齐、压缩指针 4B、
 * String compact strings）全部来自 [SizeModel]，这里不另立第二套对象模型。
 *
 * 语义：**身份去重**。整个遍历共用一个 `IdentityHashMap` 访问集，同一实例只计一次；
 * 因此「先消费 A、再消费 B」得到的是互斥分解（共享部分归先到者），
 * 把 A、B 交换顺序会改变归属但不改变总和。逐池分解即依赖这一点，见 [PoolDecomp]。
 *
 * 与真实堆的偏差来源（报告里逐条列）：
 * - `ArrayList.elementData` 的真实容量不可读，按 `size` 下界估（最多低估约 50% 的引用槽）；
 * - `HashMap` 表容量按 `tableSizeFor(ceil(n/0.75))` 反推，节点 32B（`HashMap.Node`）
 *   或 40B（`LinkedHashMap.Entry`），树化桶（`TreeNode` 56B）未单独建模；
 * - `Path`、`Pattern`、`StringBuilder` 等 java.base 类型的内部字段不可反射，只计浅尺寸；
 * - 枚举常量、`Class`、`ClassLoader` 只计浅尺寸（与 [SizeModel.deepSize] 同口径）。
 */
internal class ResidentWalker(val sizer: SizeModel) {

    private val visited = IdentityHashMap<Any, Boolean>(1 shl 16)
    private val fieldsByClass = HashMap<Class<*>, Array<Field>>()
    private val classBytes = HashMap<String, Long>()
    private val classCount = HashMap<String, Int>()

    private var byteTotal = 0L
    private var objectTotal = 0L

    /** 累计已归属字节（含所有 [consume] 过的对象图）。 */
    val bytes: Long get() = byteTotal

    /** 累计已访问对象数。 */
    val objects: Long get() = objectTotal

    /** 逐类模型账单（类名 → 实例数 / 字节），用于与 jcmd 类直方图对拍。 */
    fun classTop(n: Int = 20): List<Triple<String, Int, Long>> =
        classBytes.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { Triple(it.key, classCount[it.key] ?: 0, it.value) }

    /** 模型账单总量（= 所有类之和；用于核对 [bytes] 是否漏记容器背衬等直接记账项）。 */
    fun classTotalBytes(): Long = classBytes.values.sum()

    fun isVisited(o: Any?): Boolean = o != null && visited.containsKey(o)

    fun markVisited(o: Any?) {
        if (o != null) visited[o] = true
    }

    /** 消费 [root] 可达且尚未访问的部分，返回本次新增字节。 */
    fun consume(root: Any?): Long {
        val before = byteTotal
        walk(root)
        return byteTotal - before
    }

    /** 丢弃访问集（遍历结束、报告已生成后调用，避免临时占用计入下一段读数）。 */
    fun releaseVisited() {
        visited.clear()
    }

    fun walk(root: Any?) {
        if (root == null) return
        if (visited.put(root, true) != null) return
        objectTotal++
        val c = root.javaClass

        fun done(own: Long) {
            byteTotal += own
            classBytes.merge(c.name, own) { a, b -> a + b }
            classCount.merge(c.name, 1) { a, b -> a + b }
        }

        if (c.isArray) {
            done(sizer.arraySize(root))
            val comp = c.componentType
            if (comp != null && !comp.isPrimitive) {
                for (e in (root as Array<*>)) walk(e)
            }
            return
        }

        when (root) {
            is String -> return done(SizeModel.stringBytes(root))
            is Map<*, *> -> {
                done(mapOwnBytes(root))
                for ((k, v) in root) {
                    walk(k)
                    walk(v)
                }
                return
            }
            is Set<*> -> {
                done(setOwnBytes(root))
                for (e in root) walk(e)
                return
            }
            is List<*> -> {
                done(listOwnBytes(root))
                for (e in root) walk(e)
                return
            }
            is Collection<*> -> {
                done(collectionOwnBytes(root))
                for (e in root) walk(e)
                return
            }
        }

        if (root is Class<*> || root is ClassLoader || root is Enum<*>) {
            done(sizer.objectSize(root))
            return
        }
        done(sizer.objectSize(root))
        for (f in fieldsOf(c)) {
            val v = runCatching { f.get(root) }.getOrNull() ?: continue
            walk(v)
        }
    }

    // ------------------------------------------------------------------
    // 容器布局（估算）
    // ------------------------------------------------------------------

    /**
     * Map 自身开销：对象壳 + 表数组 + 每项节点。
     *
     * 迭代 `entries` 不需要反射，键值由调用方递归；本函数**不**递归键值。
     */
    fun mapOwnBytes(m: Map<*, *>): Long {
        val n = m.size
        val base = sizer.objectSize(m)
        if (n == 0) return base
        val name = m.javaClass.name
        return when {
            name.contains("IdentityHashMap") -> {
                // Object[] table（2 槽/项）+ 无节点对象；负载因子 2/3
                val slots = 2 * mapCapacity((n * 3) / 2 + 1)
                base + 16 + 4L * slots
            }
            name.contains("TreeMap") || name.contains("ConcurrentSkipList") -> base + 48L * n
            else -> base + 16 + 4L * mapCapacity(n) + nodeBytes(m) * n
        }
    }

    /** `ArrayList` 类容器的壳 + 背衬数组（容量按 size 下界估）。 */
    fun listOwnBytes(l: List<*>): Long {
        val base = sizer.objectSize(l)
        return if (l.isEmpty()) base else base + 16 + 4L * l.size
    }

    /** `HashSet` 等 Set：HashSet 壳 + 内层 HashMap 壳 + 表数组 + 32B/节点。 */
    fun setOwnBytes(s: Set<*>): Long {
        val n = s.size
        val base = sizer.objectSize(s)
        if (n == 0) return base
        return base + 48 + 16 + 4L * mapCapacity(n) + 32L * n
    }

    /** `ArrayDeque` 等通用 Collection（无 List 语义）：壳 + 背衬数组。 */
    fun collectionOwnBytes(c: Collection<*>): Long {
        val n = c.size
        val base = sizer.objectSize(c)
        return if (n == 0) base else base + 16 + 4L * n
    }

    /** 与 `HashMap.tableSizeFor` 同形的容量反推（最小 16，负载因子 0.75）。 */
    fun mapCapacity(n: Int): Int {
        if (n <= 0) return 0
        var cap = 16
        val need = (n * 4 + 2) / 3
        while (cap < need && cap < (1 shl 30)) cap = cap shl 1
        return cap
    }

    private fun nodeBytes(m: Map<*, *>): Long =
        if (m is java.util.LinkedHashMap<*, *>) 40L else 32L

    private fun fieldsOf(c: Class<*>): Array<Field> = fieldsByClass.getOrPut(c) {
        val acc = ArrayList<Field>()
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                if (runCatching { f.trySetAccessible() }.getOrDefault(false)) acc.add(f)
            }
            cl = cl.superclass
        }
        acc.toTypedArray()
    }
}
