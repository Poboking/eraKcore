package com.eratw.port.core.compile

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 金标 fixtures/golden 全量编译：13 个 GOLDEN_*.ERB 必须全部编译通过（0 编译错误）。
 */
class GoldenCompileTest {

    @Test
    fun golden全量编译零错误() {
        // gradle test 工作目录是模块目录（core-vm/），fixtures 在仓库根
        val dir = sequenceOf(File("fixtures/golden"), File("../fixtures/golden")).first { it.isDirectory }
        val root: Path = dir.toPath()
        val files = CorpusCompileValidator.listErbFiles(root)
        assertTrue(files.isNotEmpty(), "金标 fixtures 缺失: $root")
        val r = CorpusCompileValidator.compile(files, root.parent)
        println(
            "金标编译：文件 ${r.files} 函数 ${r.functions} 事件入口 ${r.eventEntries} " +
                "指令 ${r.instructions} 全局槽 ${r.globalSlots} 编译失败 ${r.failures.size} " +
                "解析失败切片 ${r.parseFailures} 解析失败行 ${r.parseFailureLines} 耗时 ${r.elapsedMs}ms",
        )
        r.failures.take(20).forEach { println("  失败 ${it.file}:${it.line} [${it.function}] ${it.message}") }
        // 契约 R8：解析失败同样判闸（旧版只查 failures，"0 解析失败"是报表伪影）
        r.parseFailureSamples.take(20).forEach { println("  解析失败 $it") }
        assertTrue(r.ok(), "金标编译失败 ${r.failures.size} 条 / 解析失败 ${r.parseFailures} 切片（${r.parseFailureLines} 行）")
        assertTrue(r.functions > 0, "未编译出任何函数")
    }
}
