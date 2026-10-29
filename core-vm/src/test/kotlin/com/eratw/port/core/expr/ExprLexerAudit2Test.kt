package com.eratw.port.core.expr

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L04 —— 非十进制字面量（`0x` / `0b`）四档语义收口。
 *
 * 只钉**词法层**（`ErbLexer.readInt64` → `readDigits`），不覆盖表达式解析器。
 *
 * 语义权威与证据（全部为 C# `文件:行号`）：
 * - `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Parser/LexicalAnalyzer.cs:92-165` readDigits：
 *   :140 切片 `s` = 去掉 `0x`/`0b` 前缀后（含符号）的原文、:147 `Convert.ToInt64(s, fromBase)`、
 *   :149-151 FormatException → CanNotConvertToInt、:153-156 OverflowException → OoRInt64、
 *   :157-163 ArgumentOutOfRangeException → `s.IsEmpty` 走 CanNotInterpretNum（:159-161）
 * - `LexicalAnalyzer.cs:30-64`（进制前缀判定 + `retZero` 短路）与 `:124-138`（基数 2 只允许 0/1）
 * - `reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs:1858 / 3502 / 3542`（三档文案）
 *
 * 另用 reference 词法器（.NET 9 编译）逐例跑通，期望值取自其读数；四档行为与 JVM
 * `Long.parseLong(s, radix)` **不等价**（后者对满 64 位宽一律抛异常，原版是无符号 reinterpret），
 * 故移植侧必须走 `Long.parseUnsignedLong`。
 */
class ExprLexerAudit2Test {

    private val lexer = ErbLexer()

    private fun readInt(text: String, retZero: Boolean = false): Long =
        lexer.readInt64(CharStream(text), retZero, LINE)

    private fun err(text: String, retZero: Boolean = false): LexError =
        assertFailsWith(LexError::class, "期望 LexError：$text") { readInt(text, retZero) }

    // ------------------------------------------------------------------
    // 第 1 档：前缀之后一个有效数字都没有 → CanNotInterpretNum（不是 CanNotConvertToInt）
    // ------------------------------------------------------------------

    @Test
    fun `空数字串各写法都报 数値として認識できません`() {
        // spec: LexicalAnalyzer.cs:159-161（s.IsEmpty）+ Lang.cs:3502
        for (text in listOf("0x", "0X", "0b", "0B")) {
            assertEquals("数値として認識できません", err(text).message, "空串档：$text")
        }
        // 前缀后首位不属于该基数（'G'/'Z' 非十六进制）⇒ readDigits 一个字符都不消费 ⇒ 同一档
        // spec: LexicalAnalyzer.cs:112-123（基数 16 的字符集）
        for (text in listOf("0xG", "0xZ")) {
            assertEquals("数値として認識できません", err(text).message, "前缀后无有效数字：$text")
        }
        assertEquals(LINE, err("0x").lineNo)
    }

    @Test
    fun `retZero 短路把空串档折成 0 且不消费前缀之后的字符`() {
        // spec: LexicalAnalyzer.cs:54-64 —— retZero 时先判 `Current != '+' && != '-' && !IsDigit`，
        // 基数 16 再放行十六进制字母；EOS / 'G' 都不满足 ⇒ 直接 return 0，压根不进 readDigits。
        for (text in listOf("0x", "0b", "0xG")) {
            assertEquals(0L, readInt(text, retZero = true), "retZero=true：$text")
        }
        // 消费位置停在 `0x` 之后（原版同样：CharStream 只 ShiftNext 两次跳过前缀）
        val st = CharStream("0xG")
        assertEquals(0L, lexer.readInt64(st, true, LINE))
        assertEquals(2, st.currentPosition)
        // retZero=false 才落到第 1 档
        assertEquals("数値として認識できません", err("0x", retZero = false).message)
    }

    // ------------------------------------------------------------------
    // 第 2 档：非十进制带负号 —— 原版抛 ArgumentException（:149-164 三个 catch 都不覆盖）⇒ 非 CodeEE
    // reference 词法器实测：`0x-1` / `0b-1` / `0x-F` / `0X-f` 均 ERRX|ArgumentException:
    //   "String cannot contain a minus sign if the base is not 10."
    // 移植侧降级成词法错误（装载结果同样是该行失败），文案沿用 CanNotConvertToInt（Lang.cs:1858）。
    // TODO(wiring)：按 ADR-005 复刻成「非 CodeEE 异常直接崩装载」需要装载层裁决，不在本路文件内。
    // ------------------------------------------------------------------

    @Test
    fun `非十进制负号降级为转换失败且切片保留符号`() {
        for ((text, slice) in listOf("0x-1" to "-1", "0b-1" to "-1", "0x-F" to "-F", "0X-f" to "-f")) {
            val e = err(text)
            // spec: Lang.cs:1858 「"{0}"を整数値に変換できません」，{0} = 去前缀后的切片（含符号）
            assertEquals("\"$slice\"を整数値に変換できません", e.message, text)
            assertEquals(LINE, e.lineNo)
        }
        // retZero=true 也拦不住：spec: LexicalAnalyzer.cs:54 的 `Current != '-'` 让负号继续走 readDigits
        assertEquals("\"-1\"を整数値に変換できません", err("0x-1", retZero = true).message)
    }

    // ------------------------------------------------------------------
    // 第 2 档补：正号合法；只有孤零零的 `+` 才 FormatException → CanNotConvertToInt
    // ------------------------------------------------------------------

    @Test
    fun `正号前缀合法`() {
        assertEquals(1L, readInt("0x+1"))
        assertEquals(1L, readInt("0b+1"))
        assertEquals(15L, readInt("0x+F"))
        assertEquals(5L, readInt("0b+101"))
    }

    @Test
    fun `单独的加号报转换失败而合法字面量不受影响`() {
        assertEquals("\"+\"を整数値に変換できません", err("0x+").message)
        assertEquals("\"+\"を整数値に変換できません", err("0b+").message)
        assertEquals(16L, readInt("0x10"))
        assertEquals(5L, readInt("0b101"))
        assertEquals(65280L, readInt("0x00FF00"))     // DIM.ERH:571 C_GREEN 同款
        assertEquals(1147758953L, readInt("0x44696969"))
    }

    // ------------------------------------------------------------------
    // 第 3 档：满 64 位宽先按无符号解析、再原样按补码取 Int64
    // reference 实测：0xFFFFFFFFFFFFFFFF = -1、0x8000000000000000 = -9223372036854775808、
    // 0b + 64 个 1 = -1、0b1 + 63 个 0 = -9223372036854775808
    // ------------------------------------------------------------------

    @Test
    fun `满六十四位按无符号解析后补码 reinterpret`() {
        assertEquals(-1L, readInt("0xFFFFFFFFFFFFFFFF"))
        assertEquals(-1L, readInt("0xffffffffffffffff"))
        assertEquals(Long.MIN_VALUE, readInt("0x8000000000000000"))
        assertEquals(-1L, readInt("0b" + "1".repeat(64)))
        assertEquals(Long.MIN_VALUE, readInt("0b1" + "0".repeat(63)))
    }

    @Test
    fun `前导零不计入位宽`() {
        // 实测 `Convert.ToInt64("0FFFFFFFFFFFFFFFF", 16) == -1`：宽度判定看的是有效位数
        assertEquals(-1L, readInt("0x0FFFFFFFFFFFFFFFF"))
        assertEquals(-1L, readInt("0b0" + "1".repeat(64)))
        assertEquals(255L, readInt("0x00000000000000FF"))
        assertEquals(255L, readInt("0x0000000000000000000FF"))
        assertEquals(16L, readInt("0x0000000000000010"))
    }

    // ------------------------------------------------------------------
    // 第 4 档：去掉前导 0 后仍超过 64 位 → OverflowException → OoRInt64
    // spec: LexicalAnalyzer.cs:153-156 + Lang.cs:3542（{0} 同样是去前缀后的切片）
    // ------------------------------------------------------------------

    @Test
    fun `真超范围报 OoRInt64 且文本是被转换的切片`() {
        assertEquals(
            "\"10000000000000000\"は64ビット符号付き整数の範囲を超えています",
            err("0x10000000000000000").message,
        )
        assertEquals(
            "\"${"1".repeat(65)}\"は64ビット符号付き整数の範囲を超えています",
            err("0b" + "1".repeat(65)).message,
        )
        val wide = "1" + "0".repeat(16)
        assertEquals(
            "\"$wide\"は64ビット符号付き整数の範囲を超えています",
            err("0x$wide").message,
        )
        // 与第 3 档的分界：同样 64 位宽但没越过无符号上限 ⇒ 合法 reinterpret，不是溢出
        assertEquals(-1L, readInt("0b" + "1".repeat(64)))
    }

    private companion object {
        const val LINE = 7
    }
}
