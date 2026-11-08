package com.eratw.port.erbcc.compile

import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * `CorpusCompile.loadRenameDic` 的解码三档判据（B1）。
 * 与设备端 `core-vm` 的 `RenameDictionary.load`（frontend/RenameDictionary.kt:34-45）同源：
 * 都把文件字节交给 `LogicalLineReader.readAllLines`，按 BOM / 严格 UTF-8 / CP932
 * 回落三档解码（spec: EncodingHandler.cs:15-41 + ParserMediator.cs:75）。
 *
 * 反向证明：把 `loadRenameDic` 临时改回 `Files.readAllLines(candidate)` 后，
 * CP932 与 UTF-16LE 两例必红（该 JDK 重载固定 UTF-8、无回落、不认 UTF-16 BOM），
 * 恢复后全绿。UTF-8 BOM 一例改回前后都绿，钉「出厂语料读数逐字不变」。
 */
class RenameDicDecodeTest {

    @TempDir
    lateinit var tmp: Path

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    private fun writeCsv(bytes: ByteArray): Path {
        val erb = Files.createDirectories(tmp.resolve("ERB"))
        val csvDir = Files.createDirectories(tmp.resolve("CSV"))
        Files.write(csvDir.resolve("_Rename.csv"), bytes)
        return erb
    }

    private fun csv(text: String, cs: Charset, bom: ByteArray = ByteArray(0)): ByteArray =
        bom + text.toByteArray(cs)

    @Test
    fun `UTF-8 BOM 中文键逐字可读`() {
        val erb = writeCsv(csv("16,蕾米莉亚\n", Charsets.UTF_8, utf8Bom))
        val dic = CorpusCompile.loadRenameDic(erb)
        assertEquals(mapOf("[[蕾米莉亚]]" to "16"), dic, "UTF-8 BOM 首行剥 BOM 后整键可读")
    }

    @Test
    fun `无 BOM 严格 UTF-8 中文键可读`() {
        val erb = writeCsv(csv("18,雾之湖\n", Charsets.UTF_8))
        assertEquals(mapOf("[[雾之湖]]" to "18"), CorpusCompile.loadRenameDic(erb))
    }

    @Test
    fun `CP932 回落可读`() {
        // windows-31j 的片假名字节不是合法 UTF-8；修复前 Files.readAllLines 固定按
        // UTF-8 解码（无回落）会得替换字符或抛错，修复后回落 CP932 得正确键。
        val cp932 = Charset.forName("windows-31j")
        val erb = writeCsv(csv("19,レミリア\n", cp932))
        assertEquals(mapOf("[[レミリア]]" to "19"), CorpusCompile.loadRenameDic(erb))
    }

    @Test
    fun `UTF-16LE BOM 可读`() {
        val erb = writeCsv(csv("20,アビリティ\n", Charsets.UTF_16LE, utf16LeBom))
        assertEquals(mapOf("[[アビリティ]]" to "20"), CorpusCompile.loadRenameDic(erb))
    }

    @Test
    fun `与 LogicalLineReader readAllLines 同源对拍`() {
        val bytes = csv("21,霧之湖\n;注释行\n22,紅魔館\n", Charsets.UTF_8, utf8Bom)
        val erb = writeCsv(bytes)
        val expected = LogicalLineReader.readAllLines(bytes)
            .map { it.trimStart('\uFEFF').trim() }
            .filter { it.isNotEmpty() && !it.startsWith(";") }
            .mapNotNull { line ->
                line.split(',').takeIf { it.size == 2 }
                    ?.let { "[[${it[1].trim()}]]" to it[0].trim() }
            }
            .toMap()
        assertEquals(expected, CorpusCompile.loadRenameDic(erb), "逐行解码与设备端同源")
    }

    @Test
    fun `文件缺席返回 null`() {
        val erb = Files.createDirectories(tmp.resolve("ERB"))
        assertNull(CorpusCompile.loadRenameDic(erb))
    }
}
