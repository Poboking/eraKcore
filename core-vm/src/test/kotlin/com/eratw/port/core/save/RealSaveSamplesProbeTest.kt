package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 外部真实存档备份（SANYOUSEI PC 4.980，2026-03-26）只读探测。
 *
 * 目录由 `ERATW_SAVE_SAMPLES` 指定；未设置时跳过，不把本机路径写进仓库。
 * 对每份 `.sav`：容器头 / 头探测 / 整帧读取 / **重编码逐字节回环**。
 * 回环失败即保存写侧与 PC 产物不一致的真实缺陷（不修测试凑绿）。
 */
class RealSaveSamplesProbeTest {

    @Test
    fun `外部真实存档只读解析与逐字节回环`() {
        val dir = System.getenv("ERATW_SAVE_SAMPLES")?.trim()?.takeIf { it.isNotEmpty() }
        val maybeRoot = dir?.let { Path.of(it) }
        Assumptions.assumeTrue(
            maybeRoot != null && Files.isDirectory(maybeRoot),
            "ERATW_SAVE_SAMPLES 未设置或不是目录：$dir",
        )
        val root = maybeRoot!!
        val files = Files.list(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sav") }
                .sorted()
                .toList()
        }
        assertTrue(files.isNotEmpty(), "目录下没有 .sav：$dir")

        for (f in files) {
            val bytes = Files.readAllBytes(f)
            val name = f.fileName.toString()
            val head = SaveFormat.peek(bytes)
            assertNotNull(head, "$name 头探测")
            assertTrue(head.binary, "$name 应为二进制容器")
            assertEquals(1808, head.version, "$name 容器版本")

            val d = SaveFormat.read(bytes)
            println(
                "[save-probe] $name size=${bytes.size} gzip=${head.gzip} code=${head.uniqueCode} " +
                    "scriptVersion=${head.scriptVersion} kind=${d.fileType} chars=${d.characters.size} " +
                    "text=${d.text.take(32).replace('\n', ' ')}",
            )
            if (name != "global.sav") {
                assertEquals(SaveFileKind.NORMAL, d.fileType, "$name 类别")
                assertTrue(d.characters.isNotEmpty(), "$name 角色块为空")
            } else {
                assertEquals(SaveFileKind.GLOBAL, d.fileType, "$name 类别")
            }
            assertTrue(
                SaveFormat.write(d).contentEquals(bytes),
                "$name 重编码必须与 PC 原字节逐字节相同",
            )

            // 语义读回：描述里的「第 N 日」必须落到系统变量 DAY（变量名在 PC 宏里固定）。
            // 只做字节回环无法发现「值写进了错变量」；这里用新变量池真还原一遍。
            val day = Regex("""第\s*(\d+)日""").find(d.text)?.groupValues?.get(1)?.toLongOrNull()
            if (d.fileType == SaveFileKind.NORMAL) {
                assertNotNull(day, "$name 描述里应带「第 N 日」：${d.text}")
                val store = VariableStore()
                val report = d.applyTo(store)
                assertEquals(d.characters.size, report.characters, "$name 角色块数")
                assertEquals(
                    day, store.readInt("DAY", longArrayOf(0)),
                    "$name DAY 必须等于描述中的第 N 日（语义读回落错变量会在这里红）",
                )
                println("[save-apply] $name applied=${report.applied} skipped=${report.skipped} DAY=$day")
            }
        }
    }
}
