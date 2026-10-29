package com.eratw.port.boot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 开局冒烟的报告闸（**默认不执行**，避免常规 `:core-vm:test` 变红）。
 *
 * `ERATW_BOOT=1` 时才要求报告可读且能定位首个阻塞点：
 * - 报告已由 `:core-vm:bootSmoke` 产出 → 直接读（推荐，JavaExec 有 6g 堆）；
 * - 否则本测试调用 [GameBootSmoke.run] 现场生成（test worker 仅 3g，全量可能 OOM，
 *   装置会自行降级到前 400 文件并在报告注明；`ERATW_BOOT_FORCE=1` 可强制重跑）。
 *
 * **不断言游戏必须成功**：本装置是测量，不是验收。TITLE/FIRST 抛错同样算「已定位阻塞点」。
 */
class GameBootSmokeTest {

    @Test
    fun `ERATW_BOOT=1 时报告可定位首个阻塞点`() {
        if (System.getenv("ERATW_BOOT") != "1") return

        val report = reportPath()
        if (!Files.isRegularFile(report) || System.getenv("ERATW_BOOT_FORCE") == "1") {
            GameBootSmoke.run()
        }
        assertTrue(Files.isRegularFile(report), "报告未生成：$report（先跑 :core-vm:bootSmoke）")

        val text = Files.readString(report)
        assertTrue(text.contains("## 1. 编译规模"), "报告缺少编译规模小节")
        assertTrue(text.contains("## 2. BEGIN 结果"), "报告缺少 BEGIN 结果小节")
        assertTrue(text.contains("## 首个阻塞点"), "报告缺少首个阻塞点小节")

        val section = text.substringAfter("## 首个阻塞点")
        // 可定位 = 明确写「无阻塞」，或给出五元组里的 message（其余字段同段落一并有）
        assertTrue(
            section.contains("无阻塞") || section.contains("- message:"),
            "首个阻塞点不可定位（既无「无阻塞」也无 message）",
        )
        if (section.contains("- message:")) {
            assertTrue(section.contains("- function:"), "阻塞点缺少函数名")
            assertTrue(section.contains("- pc:"), "阻塞点缺少 pc")
            assertTrue(section.contains("- 语句:"), "阻塞点缺少语句描述")
        }
    }

    private fun reportPath(): Path {
        val override = System.getenv("ERATW_BOOT_REPORT")?.trim()?.takeIf { it.isNotEmpty() }
        return if (override != null) {
            Path.of(override).toAbsolutePath().normalize()
        } else {
            Path.of("build", "boot-report.md").toAbsolutePath().normalize()
        }
    }
}
