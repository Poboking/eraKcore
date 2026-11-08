package com.eratw.port.core.fn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L7 稳定性复查（2026-11-01）：内存取数注入口的异常回退。
 *
 * [processMemoryReading] 的 `statusReader` 是移植侧新增的注入口（Android 侧喂
 * `Debug.getMemoryInfo` 一类读数），平台读数在个别机型上会抛。旧写法直接调用取行器，
 * 异常会穿过 `BuiltinHost.memoryUsageBytes` 把 GETMEMORYUSAGE 变成 CodeEE —— 与同文件
 * `readSelfStatus` 早已确立的「不抛，内存读数不许让函数失败」口径相悖。
 *
 * 反证方式：去掉主源里的 `runCatching { statusReader() }.getOrNull()`，第一条用例会以
 * `IllegalStateException` 失败；第二条用例钉住补丁没有误伤 VmRSS 正常口径。
 */
class StabilityL7TimeTest {

    @Test
    fun `取行器抛异常时回落 used heap 不上抛`() {
        val reading = processMemoryReading { throw IllegalStateException("procfs boom") }

        assertEquals(MemoryCounter.USED_HEAP, reading.counter, "读不到就如实登记回落口径")
        assertTrue(reading.bytes > 0L, "回落读数是 Java 堆已用字节数，正常进程恒正")
    }

    @Test
    fun `取行器正常时 VmRSS 口径不变`() {
        val reading = processMemoryReading { listOf("Name:\teratw", "VmRSS:\t  4096 kB") }

        assertEquals(MemoryCounter.PROC_STATUS_VM_RSS, reading.counter)
        assertEquals(4096L * 1024L, reading.bytes, "proc(5) 的 kB × 1024")
    }

    @Test
    fun `取行器返回 null 仍走既有回落`() {
        val reading = processMemoryReading { null }

        assertEquals(MemoryCounter.USED_HEAP, reading.counter)
    }
}
