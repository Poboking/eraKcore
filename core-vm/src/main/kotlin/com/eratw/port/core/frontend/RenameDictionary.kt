package com.eratw.port.core.frontend

import java.nio.file.Files
import java.nio.file.Path

/**
 * `[[替换名]]` 字典装载（`CSV/_Rename.csv`）。
 *
 * 语义权威 = erbcc 全量侧 `CorpusCompile.loadRenameDic`（tools/erbcc/.../compile/
 * CorpusCompile.kt:1245-1260，本函数逐字同口径）与原版读取层
 * `EraStreamReader.cs:106-119` 的 useRename 替换：键 = `[[原名]]` 整串，值 = 新名，
 * 替换发生在**读行层**（[com.eratw.port.core.lexer.LogicalLineReader] 的 renameDic 形参），
 * 因此词法器永远不该看到 `[[`（看到即 `RenameDic == null` 分支的
 * 「字句解析中に予期しない文字'[['」，spec: LexicalAnalyzer.cs:809-813）。
 *
 * 为什么在 core：设备端**补丁编译**（PatchCompiler）与全量编译必须吃同一份改名表，
 * 否则口上包里 `CASE [[比基尼套装]]` 这类写法在 PC 能装、在手机上整包解析失败。
 */
object RenameDictionary {

    /** 文件名单（游戏根下 `CSV/_Rename.csv`，与 erbcc 的 `CSV/_Rename.csv` 同一路径口径）。 */
    const val CSV_FILE_NAME: String = "_Rename.csv"

    /**
     * 从 `CSV` 目录装载改名表；文件不存在返回 null（= 全量侧「没有改名表」的同一形态，
     * 不抛错）。[csvDir] 直接指 CSV 目录（设备端 = `<gameRoot>/CSV`）。
     *
     * 解码必须走 [com.eratw.port.core.lexer.LogicalLineReader.readAllLines]：与 erbcc 全量侧
     * `CorpusCompile.loadRenameDic` 同源，三档判据 = BOM 五族探测
     * （spec: EncodingHandler.cs:15-41）、无 BOM 严格 UTF-8（坏字节由 decoder REPORT 判失败）、
     * 非 UTF-8 回落 CP932（windows-31j）。相较 `Files.readAllLines(Path)`（JDK 17 固定
     * UTF-8、无 CP932 回落、不认 UTF-16/32 BOM、坏字节走 REPLACE 静默替换），这三档才是
     * 原版 `ParserMediator.cs:75` 按 `EncodingHandler.DetectEncoding` 读表的等价形态；任一处
     * 口径分叉都会让 `[[置換名]]` 查不到键，`[[` 漏到词法器
     * （`LexicalAnalyzer.cs:811-813` / `:825-826`）。
     *
     * 2026-11-08 更正：旧注释关于 `Files.readAllLines(Path)` 解码字符集的那句判断已由
     * JDK 17 码点实测证伪 —— 该重载与 `readAllLines(Path, UTF_8)` 逐码点相同
     * （`-Dfile.encoding=GBK` 下亦同）。两实现的真实差异是上面的三档解码能力，不是字符集
     * 随平台变化；出厂两版 `_Rename.csv` 均为 UTF-8 BOM，同源化后解码结果逐字不变。
     */
    fun load(csvDir: Path?): Map<String, String>? {
        if (csvDir == null) return null
        val candidate = csvDir.resolve(CSV_FILE_NAME)
        if (!Files.isRegularFile(candidate)) return null
        val map = HashMap<String, String>()
        for (line in com.eratw.port.core.lexer.LogicalLineReader.readAllLines(Files.readAllBytes(candidate))) {
            val t = line.trimStart('\uFEFF').trim()
            if (t.isEmpty() || t.startsWith(";")) continue
            val parts = t.split(',')
            if (parts.size == 2) map["[[${parts[1].trim()}]]"] = parts[0].trim()
        }
        return map
    }
}
