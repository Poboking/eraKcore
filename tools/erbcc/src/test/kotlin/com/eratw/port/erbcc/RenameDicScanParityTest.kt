package com.eratw.port.erbcc

import com.eratw.port.erbcc.compile.CorpusCompile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * ENGFIX2 B4 任务 B：`Main.kt` scan 子命令的第三份 `loadRenameDic` 与基准
 * [CorpusCompile.loadRenameDic] 同源化的定向用例（三档判据 + 逐字节对拍）。
 *
 * 反向证明：把 `Main.kt` 的 `loadRenameDic` 临时改回 `file.readText()` 后，
 * CP932 / UTF-16LE 两例必红（固定 UTF-8、无回落、不认 UTF-16 BOM）；恢复后全绿。
 * UTF-8 BOM 一例改回前后都绿，钉「出厂语料读数逐字不变」——同源化的定位是消除
 * 实现分叉，不是修出厂表的解码结果（JDK 17 实测 `IDENTICAL=true`，
 * 13 条白名单真根在出包根 `CSV/_Rename.csv` 缺键 + 语料侧日文字形常量）。
 */
class RenameDicScanParityTest {

    @TempDir
    lateinit var tmp: Path

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun csv(text: String, cs: Charset, bom: ByteArray = ByteArray(0)): ByteArray =
        bom + text.toByteArray(cs)

    private fun scanCsv(bytes: ByteArray): Path {
        val p = tmp.resolve("scan_rename.csv")
        Files.write(p, bytes)
        return p
    }

    /** 基准夹具：`<tmp>/ERB` 是语料根，`<tmp>/CSV/_Rename.csv` 是它按父目录探测的表。 */
    private fun baselineRoot(bytes: ByteArray): Path {
        val root = Files.createDirectories(tmp.resolve("ERB"))
        Files.createDirectories(tmp.resolve("CSV"))
        Files.write(tmp.resolve("CSV/_Rename.csv"), bytes)
        return root
    }

    @Test
    fun `UTF-8 BOM 中文键逐字可读`() {
        val p = scanCsv(csv("16,蕾米莉亚\n", Charsets.UTF_8, utf8Bom))
        assertEquals(mapOf("[[蕾米莉亚]]" to "16"), loadRenameDic(p))
    }

    @Test
    fun `无 BOM 严格 UTF-8 中文键可读`() {
        val p = scanCsv(csv("18,雾之湖\n", Charsets.UTF_8))
        assertEquals(mapOf("[[雾之湖]]" to "18"), loadRenameDic(p))
    }

    @Test
    fun `CP932 回落可读`() {
        // windows-31j 片假名字节不是合法 UTF-8：旧 readText 固定按 UTF-8 + REPLACE
        // 静默替换，键会带 U+FFFD；同源化后回落 CP932 得正确键（反向证明主靶）。
        val p = scanCsv(csv("19,レミリア\n", Charset.forName("windows-31j")))
        assertEquals(mapOf("[[レミリア]]" to "19"), loadRenameDic(p))
    }

    @Test
    fun `UTF-16LE BOM 可读`() {
        val p = scanCsv(csv("20,アビリティ\n", Charsets.UTF_16LE, utf16LeBom))
        assertEquals(mapOf("[[アビリティ]]" to "20"), loadRenameDic(p))
    }

    @Test
    fun `UTF-16BE BOM 可读`() {
        val p = scanCsv(csv("21,博麗霊夢\n", Charsets.UTF_16BE, utf16BeBom))
        assertEquals(mapOf("[[博麗霊夢]]" to "21"), loadRenameDic(p))
    }

    @Test
    fun `与基准逐字节同源对拍 四档编码`() {
        val cases = listOf(
            csv("22,霧之湖\n;注释\n23,紅魔館\n", Charsets.UTF_8, utf8Bom),
            csv("24,雾之湖\n", Charsets.UTF_8),
            csv("25,レミリア\n", Charset.forName("windows-31j")),
            csv("26,アビリティ\n", Charsets.UTF_16LE, utf16LeBom),
        )
        for (bytes in cases) {
            val baseline = CorpusCompile.loadRenameDic(baselineRoot(bytes))
            val scan = loadRenameDic(scanCsv(bytes))
            assertEquals(baseline, scan, "scan 与基准必须共用一份解码，逐键相同")
        }
    }

    @Test
    fun `注释行与非两列行过滤与基准一致`() {
        val bytes = csv("27,甲\n; 忽略\n28,乙,多余列\n\n29,丙\n", Charsets.UTF_8, utf8Bom)
        assertEquals(
            CorpusCompile.loadRenameDic(baselineRoot(bytes)),
            loadRenameDic(scanCsv(bytes)),
        )
    }
}
