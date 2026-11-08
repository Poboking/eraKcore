package com.eratw.port.erbcc.compile

import com.eratw.port.core.lexer.LogicalLineReader

/**
 * `_Rename.csv` 置換名辞書的**唯一**解码实现（spec: ParserMediator.LoadEraExRenameFile，
 * 行形状「置換後 , [[置換前]]」，逗号恰两列）。
 *
 * 装载相位同源三档判据（由 [LogicalLineReader.readAllLines] 单点实现，见其 :300-351；
 * 与设备端 `core-vm/.../frontend/RenameDictionary.kt:34-45` 一致）：
 * ① BOM 五族探测（UTF-8 / UTF-32LE / UTF-16LE / UTF-16BE / UTF-32BE，顺序照 .NET
 * `StreamReader.DetectEncoding`，`EncodingHandler.cs:15-41` 只显式判 UTF-8 BOM 三字节，
 * 其余交由 `new StreamReader(path, UTF8Encoding)` 探测）；② 无 BOM 严格 UTF-8 解码，
 * 坏字节由 decoder REPORT 判失败、不静默替换；③ 非 UTF-8 回落 CP932（windows-31j，
 * `EncodingHandler.cs:37-40`）。这正是原版
 * `File.ReadAllLines(filepath, EncodingHandler.DetectEncoding(filepath))`
 * （`ParserMediator.cs:75`）按内容探测编码的等价形态。
 *
 * 相较 `Files.readAllLines(Path)` 或 `Path.readText`（JDK 17 固定 UTF-8、无 CP932 回落、
 * 不认 UTF-16/32 BOM、坏字节走 REPLACE 静默替换），三档能力是唯一实质差异。查不到键会把
 * `[[` 漏给词法器（LexicalAnalyzer.kt:552-559，原版 LexicalAnalyzer.cs:811-813）。出厂两版
 * 表均为 UTF-8 BOM，改后解码结果逐字不变 —— 本改动的定位是消除实现分叉，不是修出厂语料。
 *
 * 调用方只有两处：[CorpusCompile.loadRenameDic]（编译子命令）与 erbcc `Main.kt` 的 scan
 * 子命令；两边都只负责把文件字节取出来交给本函数，不另造一份解码（第四种实现）。
 */
internal fun decodeRenameDic(bytes: ByteArray): Map<String, String> {
    val map = HashMap<String, String>()
    for (line in LogicalLineReader.readAllLines(bytes)) {
        val t = line.trimStart('\uFEFF').trim()
        if (t.isEmpty() || t.startsWith(";")) continue
        val parts = t.split(',')
        if (parts.size == 2) map["[[${parts[1].trim()}]]"] = parts[0].trim()
    }
    return map
}
