package com.eratw.port.core.data

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 存档内核（`core/data/save`）对**真实样本**的验收：6 个 PC 原版存档
 * （`save00-03.sav` / `save1000.sav` / `global.sav`）逐个 read + 版本/游戏码/描述断言，
 * 再做 `write(read(f)) == f` 的逐字节幂等判定。
 *
 * 样本位置（只读，仓内跟踪）：`.workbuddy/sav-backup/sav-20261018-110513/`。
 * 从 gradle test JVM 的 cwd（模块目录 `core-vm/`）上溯两级即仓库根。
 *
 * 口径：所有期望值都取**样本自身的实测字面量**（下表），不用实现算出来的值当期望
 * （CONTRACT-M3-B2 §3）。幂等失败时报告首个差异偏移 + 期望/实际字节。
 */
class SaveFormatTest {

    private val samplesDir: File = sequenceOf(
        File("../../.workbuddy/sav-backup/sav-20261018-110513"),
        File(".workbuddy/sav-backup/sav-20261018-110513"),
        File("../.workbuddy/sav-backup/sav-20261018-110513"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到真实存档样本目录（cwd=${File(".").absolutePath}）")

    /** 样本清单：文件名 → (类别, UniqueCode, ScriptVersion, 描述文本)。 */
    private data class Expect(
        val name: String,
        val kind: SaveFileKind,
        val uniqueCode: Long,
        val scriptVersion: Long,
        val text: String,
    )

    // ------------------------------------------------------------------
    // 读取断言
    // ------------------------------------------------------------------

    @Test
    fun `六个真实样本读取并断言版本游戏码描述`() {
        val rows = ArrayList<String>()
        for (f in sampleFiles()) {
            val isGlobal = f.name == "global.sav"
            val bytes = f.readBytes()
            val d = SaveFormat.read(bytes)
            assertNotNull(d, "${f.name}: read 返回 null")
            assertEquals(SaveFormat.VERSION, d.version, "${f.name}: 版本")
            assertEquals(1808, d.version, "${f.name}: 版本应为 1808")
            assertEquals(
                if (isGlobal) SaveFileKind.GLOBAL else SaveFileKind.NORMAL,
                d.fileType, "${f.name}: 类别",
            )
            assertEquals(7153L, d.uniqueCode, "${f.name}: UniqueCode 实测字面值")
            assertEquals(0L, d.scriptVersion, "${f.name}: ScriptVersion 实测字面值")
            if (isGlobal) {
                assertTrue(d.characters.isEmpty(), "${f.name}: Global 不应有角色块")
            } else {
                assertEquals(168, d.characters.size, "${f.name}: 角色数实测字面值")
            }
            rows.add(
                "%-14s kind=%s code=%d ver=%d chars=%d desc=%s".format(
                    f.name, d.fileType, d.uniqueCode, d.scriptVersion, d.characters.size, d.text,
                ),
            )
        }
        File("build/sav-headers.txt").also { it.parentFile.mkdirs() }
            .writeText(rows.joinToString("\n") + "\n", Charsets.UTF_8)
        assertEquals(6, rows.size, "样本数")
    }

    /** 描述文本（SAVEDATA_TEXT）实测字面量片段：时间戳 + 天数行 + 版本尾巴（空白由幂等测试兜底）。 */
    @Test
    fun `描述文本逐字断言语料`() {
        val d0 = SaveFormat.read(samplesDir.resolve("save00.sav").readBytes())
        assertTrue(d0.text.startsWith("2026/05/18 14:22:54 "), "save00 时间戳: ${d0.text}")
        assertTrue(d0.text.contains("第  1日(春之月1日)"), "save00 天数行（两空格实测量）: ${d0.text}")
        assertTrue(d0.text.endsWith("Ver 4.980 - 日语交流用画蛇添足版CH20260326 "), "save00 版本尾巴（含尾空格，实测）: ${d0.text}")
        val d1000 = SaveFormat.read(samplesDir.resolve("save1000.sav").readBytes())
        assertTrue(
            d1000.text.endsWith("Ver 4.980 - 日语交流用画蛇添足版CH20260326 *自动存档*"),
            "save1000.sav 描述尾巴（实测）: ${d1000.text}",
        )
    }

    /** save00-03 同属一次会话：UniqueCode/ScriptVersion 必须一致（原版同一游戏码）。 */
    @Test
    fun `同批存档的游戏码与脚本版本一致`() {
        val heads = sampleFiles().filter { it.name.startsWith("save00") || it.name.startsWith("save01") ||
            it.name.startsWith("save02") || it.name.startsWith("save03") }
            .map { SaveFormat.read(it.readBytes()) }
        val code = heads.first().uniqueCode
        assertTrue(heads.all { it.uniqueCode == code }, "save00-03 的 UniqueCode 应一致，实测 ${heads.map { it.uniqueCode }}")
    }

    /** global.sav 是 Global 类别（FileType=1），没有角色块。spec: VariableEvaluator.cs:2483-2493 */
    @Test
    fun `global 存档类别为 Global`() {
        val g = samplesDir.resolve("global.sav")
        assertTrue(g.isFile, "缺少 global.sav")
        val d = SaveFormat.read(g.readBytes())
        assertEquals(SaveFileKind.GLOBAL, d.fileType, "global.sav 类别")
        assertTrue(d.characters.isEmpty(), "Global 存档不应有角色块")
    }

    // ------------------------------------------------------------------
    // 往返幂等
    // ------------------------------------------------------------------

    @Test
    fun `write-read 逐字节幂等`() {
        var ok = 0
        val diffs = ArrayList<String>()
        for (f in sampleFiles()) {
            val src = f.readBytes()
            val back = try {
                SaveFormat.write(SaveFormat.read(src))
            } catch (e: SaveFormatException) {
                diffs.add("${f.name}: 解析失败 ${e.message}")
                continue
            }
            if (back.contentEquals(src)) {
                ok++
            } else {
                diffs.add("${f.name}: 差异 lenSrc=${src.size} lenBack=${back.size} ${firstDiff(src, back)}")
            }
        }
        println("[SaveFormatTest] 幂等 $ok/${sampleFiles().size}")
        assertTrue(diffs.isEmpty(), "往返非幂等:\n" + diffs.joinToString("\n"))
    }

    /** 只解 payload → 重编码 payload 的幂等（剥离 GZip 外壳，定位是编码器还是压缩器）。 */
    @Test
    fun `payload 重编码逐字节幂等`() {
        var ok = 0
        val diffs = ArrayList<String>()
        for (f in sampleFiles()) {
            val src = f.readBytes()
            val d = try { SaveFormat.read(src) } catch (e: SaveFormatException) {
                diffs.add("${f.name}: 解析失败 ${e.message}"); continue
            }
            val back = SaveFormat.encodePayload(d)
            val original = SaveFormat.sourcePayloadOf(src)
            if (original != null && back.contentEquals(original)) ok++
            else if (original != null) diffs.add("${f.name}: payload 差异 ${firstDiff(original, back)}")
        }
        println("[SaveFormatTest] payload 幂等 $ok/${sampleFiles().size}")
        assertTrue(diffs.isEmpty(), "payload 非幂等:\n" + diffs.joinToString("\n"))
    }

    // ------------------------------------------------------------------
    // 角色存档双向往返（CharaFileIO）
    // ------------------------------------------------------------------

    /**
     * SAVECHARA → LOADCHARA 的功能闭环：`CSTR:0:0 = "CDATA"` 必须原样回读。
     * 判据即 GOLDEN_08_IO 的 `AFTER-LOAD NUM=1 CSTR=CDATA`（pc-golden-root.txt:498）。
     */
    @Test
    fun `角色存档 capture-load 往返保真`() {
        val src = VariableStore()
        src.characters.addBare()
        src.writeStr("CSTR", longArrayOf(0, 0), "CDATA")
        src.characters.addBare() // 第二个角色：不应被 nos=[0] 带走
        src.writeStr("CSTR", longArrayOf(1, 0), "OTHER")

        val bytes = CharaFileIO.encode(src, listOf(0L), "golden chara")
        val payload = SaveFormat.sourcePayloadOf(bytes)
        assertNotNull(payload, "CHARVAR 应有 GZip 容器")
        assertEquals(0xFF, payload.last().toInt() and 0xFF, "CHARVAR payload 末尾应补 EOF（VariableEvaluator.cs:2270）")

        val head = SaveFormat.read(bytes)
        assertEquals(SaveFileKind.CHARVAR, head.fileType, "类别")
        assertEquals("golden chara", head.text, "描述")
        assertEquals(1, head.characters.size, "只打包 nos 指定的角色")

        // 自产文件也应逐字节幂等（编码器与解码器同构）
        assertTrue(
            SaveFormat.write(head).contentEquals(bytes),
            "CHARVAR write(read(f)) 应逐字节幂等",
        )

        val dst = VariableStore()
        assertTrue(CharaFileIO.load(bytes, dst), "load 应成功（RESULT=1，spec: VariableEvaluator.cs:2316）")
        assertEquals(1, dst.characters.count, "AFTER-LOAD NUM")
        assertEquals("CDATA", dst.readStr("CSTR", longArrayOf(0, 0)), "CSTR 往返")
    }

    /** LOADCHARA 追加而非覆盖：同文件 load 两次 → 角色数 2（原版 AddRange 语义，:2315）。 */
    @Test
    fun `角色存档 load 追加不清空`() {
        val src = VariableStore()
        src.characters.addBare()
        src.writeStr("CSTR", longArrayOf(0, 0), "CDATA")
        val bytes = CharaFileIO.encode(src, listOf(0L), "t")

        val dst = VariableStore()
        dst.characters.addBare() // 既有 1 个角色（原版 CharacterList.AddRange 不清空）
        assertTrue(CharaFileIO.load(bytes, dst))
        assertTrue(CharaFileIO.load(bytes, dst))
        assertEquals(3, dst.characters.count, "1 + 2 次 load")
        assertEquals("CDATA", dst.readStr("CSTR", longArrayOf(1, 0)))
        assertEquals("CDATA", dst.readStr("CSTR", longArrayOf(2, 0)))
    }

    /** `chara_<name>.dat` 文件名与 CHKCHARADATA/FIND_CHARADATA 的拼接口径一致。 */
    @Test
    fun `角色存档文件名拼接`() {
        assertEquals("chara_GOLDEN_CHARA.dat.dat", CharaFileIO.fileNameOf("GOLDEN_CHARA.dat"),
            "savename 自带 .dat 会二次追加（原版坑，FunctionMethodCreator 结果 F0=GOLDEN_CHARA.dat）")
        assertEquals("chara_A.dat", CharaFileIO.fileNameOf("A"))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun sampleFiles(): List<File> {
        val names = listOf("save00.sav", "save01.sav", "save02.sav", "save03.sav", "save1000.sav", "global.sav")
        return names.map {
            val f = samplesDir.resolve(it)
            assertTrue(f.isFile, "缺少样本 $it（目录 ${samplesDir.absolutePath}）")
            f
        }
    }

    private fun firstDiff(a: ByteArray, b: ByteArray): String {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) {
                return "@$i 期望=0x%02X 实际=0x%02X".format(a[i].toInt() and 0xFF, b[i].toInt() and 0xFF)
            }
        }
        return "长度差 @$n"
    }
}
