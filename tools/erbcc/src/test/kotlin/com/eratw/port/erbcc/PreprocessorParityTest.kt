package com.eratw.port.erbcc

import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 预处理单源守卫（CMP-38/39/41）：同一份合成 ERB 分别喂给 erbcc 侧
 * [ErbPreprocessor] 与 core 侧 [LogicalLineReader]，按「物理行 → 发射的逻辑行原文」
 * 做**逐字节**对拍。
 *
 * 原版只有一份 `PPState`（spec: ErbLoader.cs:25-181）；两档预处理器分叉会让
 * 「编译期符号表/预扫」看到运行期根本不编译的行（幽灵 `@函数`、私有 `#DIM`）。
 *
 * 最小必红件（CMP-39）：`[SKIPSTART]` 内嵌 `[IF_NDEBUG]` —— 原版 `skip` 强制位在
 * 每条指令处理后把 `Disabled` 夹回 true（spec: ErbLoader.cs:163-166），因此
 * `@GHOST` 两侧都不得发射，也不得被 [SymbolTable.scanLabels] 登记成标签。
 *
 * CMP-41：`[ELSE]`/`[ELSEIF]`/`[SKIPSTART]`/`[IF]` 的多余参数、空栈不匹配时原版
 * 是 `Warn(level 1)` 后 **break**（不改栈/`disabled`/`done`），spec:
 * ErbLoader.cs:44-52/85-90/97-126/127-141；`[IF A B]` 只把第二个 identifier 当宏名
 * （spec: ErbLoader.cs:393-395）。
 */
class PreprocessorParityTest {

    /** 一侧的观测结果：发射的逻辑行原文（顺序保持）与告警原文。 */
    private data class Side(val emitted: List<String>, val warnings: List<String>)

    /** erbcc 侧：逐物理行喂 [ErbPreprocessor]，收集既未被消费也未被禁用的行。 */
    private fun erbccSide(
        lines: List<String>,
        debugMode: Boolean = false,
        macros: Set<String> = emptySet(),
    ): Side {
        val pp = ErbPreprocessor(debugMode, macros, "fixture.erb")
        val out = ArrayList<String>()
        for ((i, raw) in lines.withIndex()) {
            // 调用方（Symbols.scanLabels / CorpusScan）传入的都是 trim 后的逻辑行
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (pp.process(line, i + 1)) continue
            if (pp.disabled) continue
            out.add(line)
        }
        pp.fileEnd(lines.size)
        return Side(out, pp.warnings.map { it.message })
    }

    /** core 侧：同一份物理行走 [LogicalLineReader]（含 `{}` 续行/注释归约/分类）。 */
    private fun coreSide(
        lines: List<String>,
        debugMode: Boolean = false,
        macros: Set<String> = emptySet(),
    ): Side {
        val reader = LogicalLineReader(
            lines,
            "fixture.erb",
            LexerConfig(debugMode = debugMode),
            macroNames = macros,
        )
        val out = ArrayList<String>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            out.add(line.content)
        }
        reader.fileEnd()
        return Side(out, reader.warnings.map { it.message })
    }

    /** 逐字节对拍：两侧发射行集合（含顺序）按 UTF-8 编码后必须完全一致。 */
    private fun assertParity(
        lines: List<String>,
        debugMode: Boolean = false,
        macros: Set<String> = emptySet(),
    ) {
        val core = coreSide(lines, debugMode, macros)
        val erbcc = erbccSide(lines, debugMode, macros)
        assertEquals(
            core.emitted,
            erbcc.emitted,
            "两档预处理器发射行集合必须完全相同（core vs erbcc）",
        )
        assertContentEquals(
            core.emitted.joinToString("\n").toByteArray(Charsets.UTF_8),
            erbcc.emitted.joinToString("\n").toByteArray(Charsets.UTF_8),
            "两档预处理器发射行集合按 UTF-8 编码后必须逐字节相同（core vs erbcc）",
        )
    }

    // ==================================================================
    // CMP-39 必红夹具：SKIPSTART 内的嵌套 IF 不得把 disabled 置回 false
    // ==================================================================

    @Test
    fun skipClampDropsNestedIfNdebugGhost() {
        val fixture = listOf("[SKIPSTART]", "[IF_NDEBUG]", "@GHOST", "[ENDIF]", "[SKIPEND]")
        // 幽灵登记：预扫侧不得把 GHOST 当成函数标签（缺 skip 强制位时这里是红点）
        assertEquals(
            emptyList(),
            SymbolTable.scanLabels(fixture.joinToString("\n")).map { it.name },
            "符号表不得登记 SKIPSTART 内的 @GHOST",
        )
        // core 侧已有 skip 强制位（LogicalLineReader.kt:126），作对照基准
        assertEquals(emptyList(), coreSide(fixture).emitted, "core: skip 内 [IF_NDEBUG] 不得放行 @GHOST")
        // erbcc 侧缺 skip 强制位时 @GHOST 会被发射
        assertEquals(emptyList(), erbccSide(fixture).emitted, "erbcc: skip 内 [IF_NDEBUG] 不得放行 @GHOST")
        assertParity(fixture)
    }

    // ==================================================================
    // CMP-41：多余参数 / 空栈 / 重复 SKIPSTART → warn 后不改状态
    // ==================================================================

    @Test
    fun skipstartWithExtraArgDoesNotOpenBlock() {
        // [SKIPSTART extra] 在原版走 HasTooManyArg（ErbLoader.cs:44-48）后 break，
        // 不 push、不开 skip ⇒ 后面两行都该发射；随后的 [SKIPEND] 因栈空再告警一次。
        val fixture = listOf("[SKIPSTART extra]", "@KEPT", "[SKIPEND]", "@ALSO_KEPT")
        val core = coreSide(fixture)
        assertEquals(listOf("@KEPT", "@ALSO_KEPT"), core.emitted)
        assertEquals(2, core.warnings.size, "HasTooManyArg + UnexpectedSkipend")
        assertEquals(2, erbccSide(fixture).warnings.size)
        assertParity(fixture)
    }

    @Test
    fun duplicateSkipstartDoesNotNest() {
        // 原版重复 [SKIPSTART] 走 DuplicateSkipstart（ErbLoader.cs:49-53）后 break，不嵌套；
        // 第一个 [SKIPEND] 就结束整个区间 ⇒ @OUTER 恢复发射，第二个 [SKIPEND] 告警。
        val fixture = listOf("[SKIPSTART]", "[SKIPSTART]", "@INNER", "[SKIPEND]", "@OUTER", "[SKIPEND]")
        val core = coreSide(fixture)
        assertEquals(listOf("@OUTER"), core.emitted)
        assertEquals(2, core.warnings.size, "DuplicateSkipstart + UnexpectedSkipend")
        assertParity(fixture)
    }

    @Test
    fun elseWithExtraArgKeepsIfState() {
        // [ELSE extra] 原版 HasTooManyArg（ErbLoader.cs:112-117）后 break：
        // disabled/done/ppMatch 全不动 ⇒ IF_NDEBUG 的 enabled 分支继续，@AFTER_ELSE 仍发射。
        val fixture = listOf("[IF_NDEBUG]", "@IFBRANCH", "[ELSE extra]", "@AFTER_ELSE", "[ENDIF]", "@TAIL")
        val core = coreSide(fixture)
        assertEquals(listOf("@IFBRANCH", "@AFTER_ELSE", "@TAIL"), core.emitted)
        assertEquals(1, core.warnings.size, "只报 [ELSE] 多余参数")
        assertEquals(1, erbccSide(fixture).warnings.size)
        assertParity(fixture)
    }

    @Test
    fun elseifOnEmptyStackKeepsEnabled() {
        // 空栈 [ELSEIF UNDEF] 原版 IsInvalid（ErbLoader.cs:103-107）后 break，
        // 不得把 disabled 永久置 true（旧实现会让后面整段静默不编译）。
        val fixture = listOf("[ELSEIF UNDEF]", "@AFTER")
        val core = coreSide(fixture)
        assertEquals(listOf("@AFTER"), core.emitted)
        assertEquals(1, core.warnings.size)
        assertEquals(1, erbccSide(fixture).warnings.size)
        assertParity(fixture)
    }

    @Test
    fun ifTakesOnlySecondIdentifierAsMacroName() {
        // spec: ErbLoader.cs:393-395 —— 指令名后只读**一个** identifier 当 token2；
        // [IF A B] 的宏名是 A，B 只触发「[]の使い方が不正です」级别告警，不影响分支判定。
        val fixture = listOf("[IF A B]", "@AFTER")
        val core = coreSide(fixture, macros = setOf("A"))
        assertEquals(listOf("@AFTER"), core.emitted, "A 已定义 ⇒ 分支 enabled")
        assertParity(fixture, macros = setOf("A"))
    }

    @Test
    fun leadingSpaceAfterBracketMakesKeywordEmpty() {
        // spec: ErbLoader.cs:393 + LexicalAnalyzer.cs:310-325 + core LogicalLineReader.kt:217 ——
        // `[` 后紧跟空白时 `ReadSingleIdentifier` 从不跳前导空白 ⇒ keyword 读成**空串**：
        // 该行落 `default` 到「未知指令」，**不**改变 disabled/栈。
        // 旧 erbcc 在 keyword 前多跳了一处空白，会把 `[ IF A]` 当有效 IF 开区间（A 已定义 ⇒
        // disabled=false，看似无差），但 `[ IF UNDEF]` 会反向把后面整段静默禁用 —— 与 core/原版分叉。
        val fixture = listOf("[ IF UNDEF]", "@AFTER")
        // core 侧：keyword 空 ⇒ Unrecognized，既不开区间也不置 disabled，@AFTER 照发射
        assertEquals(listOf("@AFTER"), coreSide(fixture).emitted)
        // erbcc 侧必须与 core 同口径（旧实现会因 disabled=true 丢掉 @AFTER）
        assertEquals(listOf("@AFTER"), erbccSide(fixture).emitted)
        assertParity(fixture)
    }
}
