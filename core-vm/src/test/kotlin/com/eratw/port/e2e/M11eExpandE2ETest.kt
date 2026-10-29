package com.eratw.port.e2e

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.LineBufferConsoleSink
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M11-E 扩面装置：把 M11 这一轮只在合成单测里钉过的语义拉进 E2E 全链。
 *
 * 管线与 [E2EGoldenTest] 同一套（`GoldenPipeline.build` → `newRuntime` → 真执行），
 * 但跑的是**另一套 fixture**（`fixtures/golden-m11e`）：既有 15 节 610 行对着
 * PC 权威基准 `pc-golden-root.txt`，往里加语句就是造假；扩面只能另起一套
 * **移植侧自产快照**（`fixtures/golden-m11e/baseline/self-golden-root.txt`）。
 *
 * 快照纪律：文件不存在或 `ERATW_E2E_UPDATE=1` 时重写；其余时候逐行对拍，
 * 实现变动撞红 —— 这是回归闸，不是文档。
 *
 * 覆盖面与「为什么观察点选在这里」逐节写在 fixture 的段头注释里。
 * 本文件只放两类断言：
 *  1. 打印轨迹里的**返回值**（`PRINTFORML` 行）——逐条硬断言，快照只兜底；
 *  2. 宿主 sink 收到的**调用序列**（`PRINT_RECT` 带色重载、DELCHARA 越界文案）。
 */
class M11eExpandE2ETest {

    companion object {
        /** gradle test JVM 的 cwd = 模块目录（core-vm/），fixtures 在仓库根 —— 双路径探测。 */
        private val fixtureDir: Path = sequenceOf(
            File("fixtures/golden-m11e"),
            File("../fixtures/golden-m11e"),
        ).first { it.isDirectory }.toPath().toAbsolutePath().normalize()

        private val baselineDir: Path = fixtureDir.resolve("baseline")
        private val selfBaseline: File = baselineDir.resolve("self-golden-root.txt").toFile()

        /** 报告目录与 [E2EGoldenTest] 同一个 build 目录，但文件名独立（不覆盖 15 节的报告）。 */
        private val reportDir: File = File("build/e2e-report").apply { mkdirs() }

        /**
         * 开跑前播种两个「文件在、内容不可读」的槽位（SAVE-13 / SAVE-14 的唯一造法）：
         * `sav/save05.sav` = 40 字节 0xFF（存档头解析必失败）、
         * `dat/chara_M11E_BAD.dat.dat` = 同样的坏字节（`CharaFileIO.fileNameOf` 拼名）。
         */
        private fun seedCorruptFiles(root: Path) {
            val bad = ByteArray(40) { 0xFF.toByte() }
            Files.createDirectories(root.resolve("sav"))
            Files.write(root.resolve("sav").resolve("save05.sav"), bad)
            Files.createDirectories(root.resolve("dat"))
            Files.write(root.resolve("dat").resolve("chara_M11E_BAD.dat.dat"), bad)
        }

        /** 快照指纹（回执用：`sha256sum` 同值，小写十六进制）。 */
        private fun sha256Of(f: File): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
    }

    /** 记录 `PRINT_RECT` 带色重载投递的假 sink；单参旧入口单独计数（正常路径必须恒 0）。 */
    private class RectRecorder : GraphicsSink {
        data class Rect(val params: List<Pair<Long, Boolean>>, val rgb: Int)

        val rects = ArrayList<Rect>()
        var legacyRects = 0
        var spaces = 0

        override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {}
        override fun printRect(params: List<Pair<Long, Boolean>>) { legacyRects++ }
        override fun printRect(params: List<Pair<Long, Boolean>>, rgb: Int) { rects.add(Rect(params, rgb)) }
        override fun printSpace(params: List<Pair<Long, Boolean>>) { spaces++ }
        override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
        override fun removeBgImage(name: String) {}
        override fun clearBgImages() {}
    }

    /**
     * 生产控制台的 E2E 门面 + 前景色转发。
     *
     * [ProductionConsoleSink] 只映射显示行/样式快照，**没有转发** `foregroundColor`
     * （[com.eratw.port.core.host.ConsoleSink] 的缺省实现是 null）；而 GFX-05 的观察点
     * 正是「执行时点控制台前景色」。底表 [LineBufferConsoleSink] 自己记着这个值，
     * 这里按接口委托 + 一条覆写转发即可，不改既有门面（15 节读数零影响）。
     */
    private class ForeColorConsole(private val buffer: LineBufferConsoleSink) :
        GoldenConsoleSink by ProductionConsoleSink(buffer) {
        override val foregroundColor: Int? get() = buffer.foregroundColor
    }

    // ------------------------------------------------------------------
    // 1) 全节执行 + 打印轨迹硬断言 + 自产快照对拍
    // ------------------------------------------------------------------

    @Test
    fun `M11E 扩面全节执行与自产快照逐字节一致`() {
        val model = GoldenPipeline.build(fixtureDir)
        assertEquals(
            0,
            model.compileFailures.size,
            "切片编译失败:\n" + model.compileFailures.joinToString("\n"),
        )

        val buffer = GoldenPipeline.newConsoleBuffer()
        val console = ForeColorConsole(buffer)
        val gfx = RectRecorder()
        val rt = GoldenPipeline.newRuntime(
            model,
            console,
            stepCap = 2_000_000,
            graphics = gfx,
            seedIoRoot = { root -> seedCorruptFiles(root) },
        )

        val results = ArrayList<Pair<String, String>>()
        for (u in model.units) {
            if (u.skippable) continue
            val outcome = try {
                rt.callFunction(u.sigKey)
                "PASS\t-"
            } catch (e: CodeEE) {
                "FAIL\t" + GoldenPipeline.describeFailure(model.program, model, e)
            } catch (e: Exception) {
                "ERROR\t" + e::class.simpleName + ": " + e.message
            }
            results.add(u.title to outcome)
        }
        val notPass = results.filterNot { it.second.startsWith("PASS") }
        assertTrue(notPass.isEmpty(), "节未全 PASS:\n" + notPass.joinToString("\n") { it.first + " => " + it.second })

        val lines = console.snapshot()

        // ---- 逐节硬断言（返回值面）----
        assertHas(lines, "CASE_TO_HITS=111")          // b∈[a,c] + a∈[a,c] + z∈[m,z]，zz 不命中
        assertHas(lines, "FOR_STEP_SNAPSHOT=123456789 STEP_END=100") // 步长只求值一次
        assertHas(lines, "M11E_CORE02_HIT_RAN")   // 命中侧：目标确实被调用
        // CORE-02 / CORE-10 两侧判据（PC 口径，spec: FunctionIdentifier.cs:3566-3570 先判名、
        // :3589-3598 命中后才 ConvertArg）：
        //   缺失侧 ⇒ 实参一次都不求值（整条调用不发射，RAND 0 抽）；
        //   命中侧 ⇒ 实参恰好求值一次（RAND 1 抽 ⇒ H0/H1 整体后移一格）。
        // 2026-10-24 起两侧都钉死。此前命中侧读数 `TRYFORM_HIT_ARG_USED=0` 是**假读数**：
        // 被调标签当时写成 `@M11E_CORE02_HIT(DUMMY)`，形参 `DUMMY` 未声明 ⇒ 解析期
        // ArgCanOnlyAssignableVar 拒绝（spec: ErbLoader.cs:651-652 + 680-684），装置侧
        // catch 里 `continue` 把失败行吞掉，导致该标签的 PRINTL 落进上一个函数体
        // （M11E_CORE02），于是「函数表里没有 M11E_CORE02_HIT，却打出了 HIT_RAN」。
        // 装置已改为与生产 erbcc 同口径（切片作废 + 失败留痕），fixture 形参改 `ARG`。
        assertHas(lines, "TRYFORM_MISS_ARG_FREE=1")
        assertHas(lines, "TRYFORM_HIT_ARG_USED=1 H0=96 H1=59 R1=96 R2=59")
        assertHas(lines, "ALLSAMES_SHORT_SIDE=0")     // 命中不同后余参不求值
        assertHas(lines, "ALLSAMES_FULL_SIDE=1")      // 全同则逐项求值一次
        assertHas(lines, "CHKDATA_CORRUPT=4 DESC=存档数据已损坏") // SAVE-14 ETC_ERROR
        assertHas(lines, "CHKDATA_MISSING=1 DESC=----")          // 空槽仍是 FILENOTFOUND
        assertHas(lines, "CHKDATA_MEMORY=0 DESC=M11E SEVEN")     // 记忆体槽不受影响
        assertHas(lines, "SAVECHARA_FILE=1 NUM=1")
        assertHas(lines, "BEFORE_LOAD_NUM=0")
        assertHas(lines, "AFTER_GOOD_NUM=1")          // 好档照常追加角色
        assertHas(lines, "AFTER_BAD_NUM=1 BAD_FILE=1") // SAVE-13：坏档不追加、也不抛
        assertHas(lines, "SPRITE_NEG_SIZE=30,40")     // 负尺寸取绝对值
        assertHas(lines, "SPRITE_POS_SIZE=30,40")
        assertHas(lines, "GDRAWG_10=1")
        assertHas(lines, "GDRAWG_10_PIX=4294901760")  // 0xFFFF0000 直通
        assertHas(lines, "GDRAWG_11=1")               // 11 参形态不报错
        assertHas(lines, "DC_START=3")
        assertHas(lines, "DC_SINGLE=2")
        assertHas(lines, "DC_MULTI=0")                // 两参按删除前位置解释
        assertHas(lines, "DC_ARRAY=0")                // 数组元素作两参：同一条数组重载
        // DT_TOXML 写侧的 .NET 真实组成（M11-E `dttoxml` 落地，此前是「按长度反推」的错形态）：
        // 声明**不带** standalone、主键列**不带** ReadOnly/AutoIncrement（`AddColumnProperties`
        // 只看列自身 flag）、表元素与 DataSet 元素各带一处 msdata:CaseSensitive。
        // PK=1 指的是表级 `<xs:unique msdata:PrimaryKey="true">`（.NET 把主键表达成 unique 约束），
        // 不是列上的属性 —— 两者别混。
        assertTrue(
            lines.any {
                it.startsWith("DT_SCHEMA_LEN=") &&
                    "STANDALONE=0" in it && "PK=1" in it && "AUTOINC=0" in it && "CASESENS=1" in it
            },
            "DT_TOXML schema 形态与 .NET WriteXmlSchema 不符：\n" + lines.filter { it.startsWith("DT_") }.joinToString("\n"),
        )
        assertHas(lines, "DT_ROUNDTRIP=R1")

        // ---- 调用序列面：PRINT_RECT 取色（GFX-05）----
        assertEquals(2, gfx.rects.size, "PRINT_RECT 应投递两次")
        assertEquals(0xFF0000, gfx.rects[0].rgb, "SETCOLOR 之后必须跟色")
        assertEquals(listOf(10L to false, 20L to false, 300L to false, 40L to false), gfx.rects[0].params)
        assertEquals(0xC0C0C0, gfx.rects[1].rgb, "RESETCOLOR 后回落 Config.ForeColor = 192,192,192")
        assertEquals(listOf(5L to true), gfx.rects[1].params, "5px 形态：单参 + 像素标记")
        assertEquals(0, gfx.legacyRects, "语句层必须走带色重载，不能退回单参旧入口")

        // ---- 自产快照对拍 ----
        val actual = GoldenNormalizer.normalize(console.capturedText())
        val update = System.getenv("ERATW_E2E_UPDATE") == "1"
        val baselineWasNew = !selfBaseline.exists()
        if (update || baselineWasNew) GoldenNormalizer.writeBaseline(selfBaseline, actual)
        val expected = GoldenNormalizer.readBaseline(selfBaseline)
        val diff = GoldenNormalizer.diff(expected, actual)

        File(reportDir, "m11e-expand.norm.txt").writeText(actual.joinToString("\n") + "\n", Charsets.UTF_8)
        File(reportDir, "m11e-expand-sections.csv").writeText(
            buildString {
                appendLine("节,状态,明细")
                results.forEach { (t, o) -> appendLine("\"$t\",\"${o.substringBefore('\t')}\",\"${o.substringAfter('\t').replace("\"", "'")}\"") }
            },
            Charsets.UTF_8,
        )
        File(reportDir, "m11e-expand-report.md").writeText(
            buildString {
                appendLine("# M11-E 扩面 E2E（自产快照，非 PC 基准）")
                appendLine("- fixtures: $fixtureDir")
                appendLine("- 快照: ${selfBaseline.absolutePath}（${expected.size} 行 / 实际 ${actual.size} 行）")
                appendLine("- 快照 sha256: ${sha256Of(selfBaseline)}" +
                    if (baselineWasNew) "（本次首跑生成）" else if (update) "（ERATW_E2E_UPDATE=1 重刷）" else "")
                appendLine("- stats: ${model.stats}")
                appendLine("- 节: " + results.joinToString(" | ") { it.first + "=" + it.second.substringBefore('\t') })
                appendLine()
                appendLine(diff.preview)
            },
            Charsets.UTF_8,
        )
        assertTrue(
            diff.identical,
            "输出与自产快照不一致（基准 ${expected.size} 行 / 实际 ${actual.size} 行）：\n" + diff.preview,
        )
    }

    // ------------------------------------------------------------------
    // 2) DELCHARA 两条重载的越界文案（CodeEE 不能在 ERB 侧捕获，单开一条用例）
    // ------------------------------------------------------------------

    @Test
    fun `DELCHARA 重载分流文案`() {
        val model = GoldenPipeline.build(fixtureDir)
        fun failureOf(sigKey: String): CodeEE {
            val rt = GoldenPipeline.newRuntime(model, GoldenPipeline.newConsole(), stepCap = 100_000)
            return assertFailsWith<CodeEE>("$sigKey 应抛 CodeEE") { rt.callFunction(sigKey) }
        }

        // 单参重载：:1245 传 charaNo.ToString() => 带越界编号
        assertEquals("试图删除不存在的注册角色(99)", failureOf("M11E_DELCHARA_SINGLE_OOR").message)
        // 数组参重载：:1259 传 charaNoList.ToString() => 官方 bug，CLR 上只有类型名
        assertEquals("试图删除不存在的注册角色(System.Int64[])", failureOf("M11E_DELCHARA_MULTI_OOR").message)
    }

    private fun assertHas(lines: List<String>, expected: String) {
        assertTrue(
            lines.contains(expected),
            "缺期望行 [$expected]；实际含该前缀的行：\n" +
                lines.filter { it.substringBefore('=') == expected.substringBefore('=') }.joinToString("\n"),
        )
    }
}
