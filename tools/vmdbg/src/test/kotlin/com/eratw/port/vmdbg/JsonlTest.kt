package com.eratw.port.vmdbg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Jsonl.parseLine] 的 BOM 容忍（2026-10-25 工具缺陷修复的解析级单测）。
 *
 * 缺陷原形：ledger diff 的 base 台账由 PowerShell 5.1 `Out-File -Encoding utf8` 合并
 * 而成（带 EF BB BF），Java UTF-8 解码器不剥 BOM、`String.trim()` 也不剥 U+FEFF，
 * 首行解析失败被静默跳过 ⇒ 该函数失败从 base 侧消失 ⇒ diff 错报一条「新增」。
 * 同一 choke point 还服务 sweep `--resume` 预清与 `where`，故修在 parseLine。
 */
class JsonlTest {

    private fun line(fn: String, status: String): String = Jsonl.obj(
        listOf(
            "fn" to fn, "key" to fn, "status" to status, "cls" to "-",
            "line" to -1, "pc" to -1, "msg" to "", "steps" to 1, "elapsedMs" to 1,
        ),
    )

    @Test
    fun parseLineToleratesLeadingBom() {
        val plain = line("FN", "PASS")
        assertEquals(
            Jsonl.parseLine(plain),
            Jsonl.parseLine("\uFEFF$plain"),
            "行首 BOM 不应改变解析结果",
        )
        // 空白 + BOM 混合前缀同样容忍（写侧先落空白再补 BOM 的病态组合）
        assertEquals(
            Jsonl.parseLine(plain),
            Jsonl.parseLine(" \uFEFF$plain"),
            "空白+BOM 混合前缀应容忍",
        )
    }

    @Test
    fun parseLineKeepsBomInsideStringValues() {
        // 值内的 U+FEFF 不在行首，必须原样保留 —— 容忍只作用于行首
        val withInner = Jsonl.obj(listOf("fn" to "FN", "msg" to "a\uFEFFb"))
        assertEquals("a\uFEFFb", Jsonl.parseLine(withInner)?.get("msg"), "值内 U+FEFF 被误剥")
    }

    @Test
    fun parseLineStillRejectsGarbage() {
        assertNull(Jsonl.parseLine("not json"), "非对象行仍须拒绝")
        assertNull(Jsonl.parseLine(""), "空行仍须拒绝")
        assertNull(Jsonl.parseLine("\uFEFFnot json"), "BOM 后不是对象行仍须拒绝")
    }
}
