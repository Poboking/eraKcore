package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CONFIG-WIRE U7：`_Rename.csv` 装载按 `UseRenameFile` 门控
 * （spec: `Process.cs:261-274` —— 开关开且文件存在才 `LoadEraExRenameFile`；
 * 开关开而文件缺席原版 `PrintError(MissingRename)`；`ErbLoader.cs:370`
 * 逐文件再按 `UseRenameFile && RenameDic != null` 决定应用）。
 *
 * 夹具布局照 [CorpusCompile.compile] 的口径：`root` 是 CSV 目录，配置在 `root.parent`；
 * `_Rename.csv` 由 `loadRenameDic` 在 `root.parent/CSV/_Rename.csv` 找。
 */
class RenameFileGateTest {

    @TempDir
    lateinit var tmp: Path

    private val csvRoot: Path get() = tmp.resolve("corpus")

    private val renameKey = "Use _Rename.csv file"

    private fun writeCorpus() {
        Files.createDirectories(csvRoot)
        // `[[你]]` 在 _Rename.csv 里映射到 `0`（`0 , 你`：第二列是置換前）。
        // 必须用 PRINTV 而不是 PRINTL：PRINTL 走 STR_NULLABLE 直取原文、不进词法器
        // （PrintSpec.kt:75-77），`[[` 会被当成普通字符串字面量照印，观察不到门控；
        // PRINTV 走表达式词法，未改名时撞 LexicalAnalyzer `[[` 分支（:551-561）。
        Files.write(csvRoot.resolve("01_main.ERB"), "@MAIN\nPRINTV [[你]]\n".toByteArray())
    }

    private fun writeConfig(lines: List<String>) {
        Files.write(tmp.resolve("emuera.config"), lines.joinToString("\n").toByteArray())
    }

    private fun writeRename(lines: List<String>) {
        val dir = tmp.resolve("CSV")
        Files.createDirectories(dir)
        Files.write(dir.resolve("_Rename.csv"), lines.joinToString("\n").toByteArray())
    }

    private fun warningTexts(r: CompileResult) = r.warnings.map { it.message }

    @Test
    fun `switch on applies rename dictionary`() {
        writeCorpus()
        writeRename(listOf("0 , 你"))
        writeConfig(listOf("$renameKey:YES"))
        val r = CorpusCompile.compile(csvRoot)
        assertTrue(
            r.ok,
            "failures=" + r.failures.joinToString(" | ") { "${it.file}:${it.line}:${it.message}" },
        )
        assertTrue(warningTexts(r).none { it.contains("MissingRename") })
    }

    @Test
    fun `switch off leaves two brackets to the lexer`() {
        writeCorpus()
        writeRename(listOf("0 , 你"))
        // 出厂缺省 false（ConfigSpec.kt:103，spec: ConfigData.cs:46）
        writeConfig(listOf("$renameKey:NO"))
        val r = CorpusCompile.compile(csvRoot)
        // 开关关 = 原版不装 RenameDic：`[[你]]` 原样留到 Analyse 层的 "[[ 分支"
        // （spec: LexicalAnalyzer.cs:809-813），整段切片不可用。
        assertTrue(
            !r.ok,
            "开关关时 [[ ]] 不被展开，应撞词法器报错；ok=${r.ok} failures=" +
                r.failures.joinToString(" | ") { "${it.file}:${it.line}:${it.message}" } +
                " warnings=" + warningTexts(r).joinToString(" | ") +
                " notes=" + r.notes.joinToString(" | "),
        )
        assertTrue(
            r.failures.any { it.message.contains("予期しない文字'[['") },
            "failures=" + r.failures.joinToString(" | ") { "${it.file}:${it.line}:${it.message}" },
        )
    }

    @Test
    fun `switch on but file missing warns MissingRename`() {
        // 不用 [[ ]] 的语料：文件缺席不改变编译结果，只留一条装载告警
        Files.createDirectories(csvRoot)
        Files.write(csvRoot.resolve("01_main.ERB"), "@MAIN\nPRINTL OK\n".toByteArray())
        writeConfig(listOf("$renameKey:YES"))
        val r = CorpusCompile.compile(csvRoot)
        assertTrue(r.ok, "warnings=" + warningTexts(r).joinToString(" | "))
        assertTrue(
            warningTexts(r).any { it.contains("MissingRename") },
            "warnings=" + warningTexts(r).joinToString(" | "),
        )
    }

    @Test
    fun `switch off emits no MissingRename warning`() {
        Files.createDirectories(csvRoot)
        Files.write(csvRoot.resolve("01_main.ERB"), "@MAIN\nPRINTL OK\n".toByteArray())
        writeConfig(listOf("$renameKey:NO"))
        val r = CorpusCompile.compile(csvRoot)
        assertTrue(r.ok)
        assertTrue(warningTexts(r).none { it.contains("MissingRename") })
    }
}
