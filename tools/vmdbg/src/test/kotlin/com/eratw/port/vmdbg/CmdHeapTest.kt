package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `heap` 子命令（obs-heap512）的自证面单测：CLI 分发接上了、读数来自本进程 Runtime。
 *
 * 本用例不校验 512m —— Gradle Test 的挡位由 build.gradle.kts 的 maxHeapSize=1g 决定；
 * 「512m 挡位透传是否真的生效」由 devtools 的实跑自证（desktop.py --xmx 512m cmd heap →
 * jvmMaxHeapBytes=536870912），不在单测里伪造进程挡位。
 */
class CmdHeapTest {

    @Test
    fun heapCommandPrintsEffectiveJvmTier() {
        val buf = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val rc = Cli.run(arrayOf("heap"), PrintStream(buf), PrintStream(err))
        assertEquals(0, rc, err.toString())
        val text = buf.toString()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        // 键在、值 = 本进程 Runtime 的真实 maxMemory（证明读的是生效值而非命令行回显）。
        assertEquals("jvmMaxHeapBytes=${Runtime.getRuntime().maxMemory()}", lines.first { "jvmMaxHeapBytes=" in it })
        assertTrue("jvmMaxHeapMiB=" in text, text)
        assertTrue("jvmVersion=" in text, text)
    }
}
