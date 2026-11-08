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

/**
 * 外部真实存档备份（SANYOUSEI PC 4.980，2026-03-26）只读探测。
 *
 * 对每份 `.sav`：容器头 / 头探测 / 整帧读取 / **重编码逐字节回环**。
 * 回环失败即保存写侧与 PC 产物不一致的真实缺陷（不修测试凑绿）。
 *
 * 样本目录见 [samplesRootOrFail]（savepin 波 2026-11-05：**没有跳过这条路了**，拿不到样本判红）。
 */
class RealSaveSamplesProbeTest {

    /**
     * 真存档样本目录：公开仓**只认显式来源** —— `-PsaveSamples=<目录>`（由
     * `core-vm/build.gradle.kts` 的 `wireRealSaveSamples` 导出成 `ERATW_SAVE_SAMPLES`）
     * 或环境变量 `ERATW_SAVE_SAMPLES`。公开仓不携带任何内部存档备份路径；没有显式来源
     * 时按公开适配口径跳过（上游是硬判红）。
     *
     * 「在位」的形态判据 = 目录存在**且至少有一份 `.sav`**（空目录按不在位）；显式指了但
     * 目录不可用仍判红 —— 闸指了样本目录就必须能用，不退回落、不跳过。
     */
    private fun samplesRootOrFail(): Path {
        val fromEnv = System.getenv("ERATW_SAVE_SAMPLES")?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                // 公开仓不含内部存档样本：按公开适配口径跳过。
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 ERATW_SAVE_SAMPLES：跳过真存档样本判据")
                throw AssertionError("unreachable")
            }
        val p = Path.of(fromEnv)
        if (hasSav(p)) return p
        throw AssertionError(
            "ERATW_SAVE_SAMPLES=$fromEnv 已设但不是「有 .sav 的目录」（规整后 ${p.toAbsolutePath().normalize()}：" +
                dirVerdict(p) + "）。闸指了样本目录就必须能用，不退回落、不跳过。",
        )
    }

    private fun hasSav(dir: Path): Boolean = savFiles(dir).isNotEmpty()

    private fun savFiles(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sav") }
                    .sorted()
                    .toList()
            }
        }

    /** 目录侧证：不存在 / 不是目录 / 是目录但里面有什么（判红文案用，不截断到看不见证据）。 */
    private fun dirVerdict(dir: Path): String = when {
        !Files.exists(dir) -> "路径不存在"
        !Files.isDirectory(dir) -> "不是目录"
        else -> runCatching {
            val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            "共 ${names.size} 项，内容 = $names"
        }.getOrElse { "列目录失败：${it.message}" }
    }

    @Test
    fun `外部真实存档只读解析与逐字节回环`() {
        val root = samplesRootOrFail()
        println("[save-probe] 样本目录 = $root（${dirVerdict(root)}）")
        val files = savFiles(root)
        assertTrue(files.isNotEmpty(), "目录下没有 .sav：$root")

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
