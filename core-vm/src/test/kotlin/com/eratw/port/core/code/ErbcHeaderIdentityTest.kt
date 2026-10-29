package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CMP-72 / CMP-75：`ErbcHeader` 持**可变** `IntArray`、`data class` 自动 `equals` 退化成引用比较、
 * 构造时不拷贝。
 *
 * ## 缺陷形态
 *
 * `ErbcHeader` **改前**是 data class（`ErbcFormat.kt:366-384`；现 `:377` 的普通 class），
 * `offsets: IntArray` 是主构造属性：
 *
 * * `equals` / `hashCode` 由编译器按**属性**生成，而 `IntArray` 的 `equals` 是**引用**比较
 *   ⇒ 两个内容相同的头判不相等、`hashCode` 也不同。任何「头相等」断言（单测、缓存键、
 *   装载期重复探测）都只能靠 `offsets.toList()` 手工绕开。
 * * 构造参数不拷贝 ⇒ 调用方（或解码器）事后改那个数组，头里的段表跟着变；
 *   头是「文件头快照」，快照能被外部改写等于快照失效。
 *
 * ## 判据
 *
 * 内容相等即相等；构造入参、读出结果都与内部状态**隔离**（两个方向都不得穿透）。
 * 副本成本 = 段表槽数（v2 = 9 个 int），远小于一次装载。
 */
class ErbcHeaderIdentityTest {

    /** v2 段表：段 0 STRING_POOL = 46、段 5 CODE = 120、段 8 PARAM_NAMES = 300，其余缺席。 */
    private val tableA = intArrayOf(46, 0, 0, 0, 0, 120, 0, 0, 300)

    private fun header(offsets: IntArray = tableA.copyOf()) = ErbcHeader(2, 0x0011, 9, offsets)

    /** 内容相同 ⇒ 相等且 `hashCode` 相同（`IntArray` 必须按内容比，不能按引用）。 */
    @Test
    fun `段表按内容判等 - 同内容头相等且 hash 相同`() {
        val a = header()
        val b = header()
        assertEquals(a, b, "两个同内容 ErbcHeader 必须相等（data class 的 IntArray 属性默认引用比较）")
        assertEquals(a.hashCode(), b.hashCode(), "相等对象的 hashCode 必须相同")

        assertNotEquals(a, header(intArrayOf(46, 0, 0, 0, 0, 121, 0, 0, 300)), "段表内容不同必须判不等")
        assertNotEquals(a, ErbcHeader(1, 0x0011, 9, tableA.copyOf()), "版本不同必须判不等")
        assertNotEquals(a, ErbcHeader(2, 0x0012, 9, tableA.copyOf()), "标志位不同必须判不等")
        assertNotEquals(a, ErbcHeader(2, 0x0011, 7, tableA.copyOf()), "段表槽数不同必须判不等")
    }

    /** 构造之后改入参数组，不得穿透到头的段表（防御性拷贝·进）。 */
    @Test
    fun `构造后改动入参数组不影响头 - 防御性拷贝`() {
        val src = tableA.copyOf()
        val h = ErbcHeader(2, 0x0011, 9, src)
        src[0] = 99
        src[5] = 98
        assertEquals(46, h.offsets[0], "入参数组被改后头里的段表不得跟着变")
        assertEquals(120, h.offsets[5])
    }

    /** 读出的段表是副本：改它不得穿透到头的内部状态（防御性拷贝·出）。 */
    @Test
    fun `读出的段表改动不影响头 - 读侧隔离`() {
        val h = header()
        h.offsets[0] = 99
        assertEquals(46, h.offsets[0], "读出的段表数组若是内部实例，外部就能改写头")
        // 两次读到的必须是不同实例，否则「读侧隔离」只是假象
        val first = h.offsets
        val second = h.offsets
        first[1] = 77
        assertEquals(0, second[1], "每次读都应拿到独立副本")
    }

    /** 段存在性判定与 toString 读的就是段表（拷贝化之后行为不变）。 */
    @Test
    fun `段存在性判定与诊断串不受拷贝化影响`() {
        val h = header()
        assertEquals(true, h.hasSection(ErbcFormat.SECTION_STRINGS))
        assertEquals(true, h.hasSection(ErbcFormat.SECTION_CODE))
        assertEquals(true, h.hasSection(ErbcFormat.SECTION_PARAM_NAMES))
        assertEquals(false, h.hasSection(ErbcFormat.SECTION_CONSTANTS))
        assertEquals(false, h.hasSection(99), "越界段号不得抛，按「不存在」处理")
        assertEquals("ErbcHeader(version=2, flags=0x11, STRING_POOL, CODE, PARAM_NAMES)", h.toString())
        assertEquals(false, h.isV1)
        assertEquals(46, h.firstSectionOffset(), "首段正文最小偏移 = 10 + 9*4")
    }
}
