package com.eratw.port.core.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CSV 资源表**端点取证行**的形状钉子。
 *
 * 现网 app 0.2.39 的实证是 erbc 编 4.981 语料、随包 CSV 资源表却来自 4.980 游戏根：
 * 立绘整块不出现，而崩溃报告里一个字都看不出来，只能靠上报端点编号反推。把
 * [ResourceContents.censusLine] 的三条读数挂进报告后，端点侧按
 * [ResourceContents.CENSUS_LINE_PREFIX] 分桶即可一眼判：完整 4.981 应是 266 / 2795
 * 附近，读出个位数就是随包数据又装错了。
 *
 * 纯 JVM、不碰宿主与像素：这里锁的是**形状**（前缀、键名、三条读数各归其位），
 * 装载语义由 [ResourceContentsTest] 覆盖。
 */
class ResourceContentsCensusTest {

    @Test
    fun `完整语料量级的三条读数折成一行`() {
        val line = ResourceContents.censusLine(ResourceContents.Report(266, 2795, 27, listOf("a:1", "a:2")))
        assertEquals("CSV 资源表：文件=266 sprite=2795 警告=2", line)
        assertTrue(line.startsWith(ResourceContents.CENSUS_LINE_PREFIX))
    }

    @Test
    fun `告警数是告警表条数不是动画条数`() {
        val line = ResourceContents.censusLine(ResourceContents.Report(10, 20, 9, List(7) { "w" }))
        assertTrue(line.contains("警告=7"))
        assertFalse(line.contains("动画="), "端点只读三条，动画数另有 logcat 出口")
    }

    @Test
    fun `装错数据时读数如实给个位数不伪造完整值`() {
        val line = ResourceContents.censusLine(ResourceContents.Report(4, 134, 0, emptyList()))
        assertEquals("CSV 资源表：文件=4 sprite=134 警告=0", line)
        assertTrue(line.contains("文件=4 sprite=134"), "端点靠这一眼判同源缺陷：$line")
    }

    @Test
    fun `未装载行与已装载行同前缀且不假装读到零`() {
        val unloaded = ResourceContents.censusUnloadedLine()
        assertTrue(unloaded.startsWith(ResourceContents.CENSUS_LINE_PREFIX))
        assertTrue(unloaded.contains("未装载"))
        assertFalse(unloaded.contains("文件=0"), "未装载不许伪装成空表读数，否则与装错数据分不开")
    }
}
