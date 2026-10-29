package com.eratw.port.perf

import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 堆对象尺寸估算（只测量，不改语义）。
 *
 * 这是**估算**，不是 JVM 的权威读数：没有 agent 就拿不到
 * `Instrumentation.getObjectSize`，故按 HotSpot 的对象布局自行建模。
 *
 * 模型（64 位 HotSpot，默认 `-XX:+UseCompressedOops`，对象对齐 8 字节）：
 * - 对象头 12B（mark 8 + klass 4），按下述字段累加后再 8 对齐；
 * - 引用 4B（压缩指针）/ 关闭压缩时 8B（[refWidth]，见 [detectRefWidth]）；
 * - long/double 8B，int/float 4B，short/char 2B，byte/boolean 1B；
 * - 数组 = 16B 头 + 元素宽度 × 长度，再 8 对齐；
 * - `String` 单独建模：JDK17 compact strings，全字符 ≤ 0xFF 走 latin1（1B/char），
 *   否则 UTF-16（2B/char）。**实测无法读 `String.value`**（java.base 强封装，
 *   `trySetAccessible` 返回 false），故按内容判定后套公式 [stringBytes]。
 *
 * 与实测堆增量的差在报告 §2 给出（`测算合计` vs `JVM 实测增量`），不做无据的「精确」宣称。
 */
internal class SizeModel(private val refWidth: Int = 4) {

    private val shallowCache = HashMap<Class<*>, Int>()

    /** 单个对象的浅尺寸（不追引用）。数组走 [arraySize]。 */
    fun objectSize(obj: Any): Long =
        if (obj.javaClass.isArray) arraySize(obj) else shallowOf(obj.javaClass).toLong()

    fun shallowOf(c: Class<*>): Int = shallowCache.getOrPut(c) {
        var size = 12
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                size += widthOf(f.type)
            }
            cl = cl.superclass
        }
        align8(size.toLong()).toInt()
    }

    fun arraySize(obj: Any): Long {
        val c = obj.javaClass
        val comp = c.componentType ?: Any::class.java
        val len = java.lang.reflect.Array.getLength(obj)
        return align8(16L + len.toLong() * widthOf(comp))
    }

    fun widthOf(t: Class<*>): Int = when (t) {
        java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
        java.lang.Integer.TYPE, java.lang.Float.TYPE -> 4
        java.lang.Short.TYPE, java.lang.Character.TYPE -> 2
        java.lang.Byte.TYPE, java.lang.Boolean.TYPE -> 1
        else -> refWidth
    }

    /**
     * 深尺寸：从 [root] 起遍历可达对象图，[visited] 去重（同一实例只计一次）。
     *
     * 跳过递归的对象：`Class`（类元数据不属堆对象）、`ClassLoader`、枚举常量
     * （`FunctionCode` 等枚举是全局单例，计进去会把常量池撑爆）。
     * 字段不可达（java.base 强封装）时只计该对象的浅尺寸，不抛异常。
     */
    fun deepSize(root: Any?, visited: IdentityHashMap<Any, Boolean>): Long {
        if (root == null) return 0
        if (visited.put(root, true) != null) return 0
        val c = root.javaClass

        if (c.isArray) {
            var sum = arraySize(root)
            val comp = c.componentType
            if (comp != null && !comp.isPrimitive) {
                for (e in (root as Array<*>)) {
                    if (e != null) sum += deepSize(e, visited)
                }
            }
            return sum
        }

        if (root is String) return stringBytes(root)

        // java.base 的 List 内部数组不可反射（强封装），改走公开 API 建模：
        // 24B 头 + 背衬数组（16 头 + 4B/槽）；元素递归。
        if (root is List<*>) {
            var s = 24L + 16L + root.size * 4L
            for (e in root) {
                if (e != null) s += deepSize(e, visited)
            }
            return s
        }

        var sum = shallowOf(c).toLong()
        if (root is Class<*> || root is ClassLoader || root is Enum<*>) return sum

        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                val opened = runCatching { f.trySetAccessible() }.getOrDefault(false)
                if (!opened) continue
                val v = runCatching { f.get(root) }.getOrNull() ?: continue
                sum += deepSize(v, visited)
            }
            cl = cl.superclass
        }
        return sum
    }

    companion object {
        /**
         * `String` 的堆占用：`String` 对象（16 头 + 4 ref + 1 coder + 4 hash ≈ 32B）
         * + 底层字节数组（16 头 + payload）。
         *
         * latin1 判定用 `char <= 0xFF` 的全扫描（不分配内存）；日文语料任一字 > 0xFF
         * 即 UTF-16，纯 ASCII 串才走 1B/char。
         */
        fun stringBytes(s: String): Long {
            val latin1 = s.none { it.code > 0xFF }
            val payload = if (latin1) s.length.toLong() else 2L * s.length
            return align8(32L + payload)
        }

        fun align8(v: Long): Long = (v + 7L) and (-8L)

        /**
         * 压缩指针是否开启（决定引用宽度）。取不到时按开启处理（-Xmx 远小于 32G 的默认）。
         */
        fun detectRefWidth(): Int = runCatching {
            val bean = java.lang.management.ManagementFactory.getPlatformMXBean(
                com.sun.management.HotSpotDiagnosticMXBean::class.java,
            )
            if (bean.getVMOption("UseCompressedOops").value == "true") 4 else 8
        }.getOrDefault(4)

        fun describeLayout(refWidth: Int): String =
            "对象头 12B/8 对齐，引用 ${refWidth}B（${if (refWidth == 4) "压缩指针 on" else "压缩指针 off"}）"
    }
}
