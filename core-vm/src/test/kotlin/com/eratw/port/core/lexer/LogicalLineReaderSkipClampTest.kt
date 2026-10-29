package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PPState` 的 `skip` 强制位与「畸形指令不改状态」口径（CMP-39/41）。
 *
 * 原版每条指令处理完后无条件执行 `if (skip) Disabled = true`
 * （spec: ErbLoader.cs:163-166）—— 因此 `[SKIPSTART]` 内嵌的 `[IF_NDEBUG]`
 * （debugMode=false 时会把 `Disabled` 置回 false）不得放行后续行。
 *
 * 畸形指令（多余参数 / 空栈不匹配）原版是 `Warn(level 1)` 后 `break`，
 * 不改 `ppMatch` / `Disabled` / `done`（spec: ErbLoader.cs:44-52/85-90/97-126/127-141）。
 */
class LogicalLineReaderSkipClampTest {

    /** 读完整份合成件，返回（发射的逻辑行原文, 告警数）。 */
    private fun collect(vararg lines: String): Pair<List<String>, Int> {
        val reader = LogicalLineReader(lines.toList(), "fixture.erb")
        val out = ArrayList<String>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            out.add(line.content)
        }
        reader.fileEnd()
        return out to reader.warnings.size
    }

    @Test
    fun skipClampForcesDisabledAfterNestedIfNdebug() {
        // [IF_NDEBUG] 在 debugMode=false 时 disabled=false；skip 强制位必须夹回 true。
        val (emitted, _) = collect("[SKIPSTART]", "[IF_NDEBUG]", "@GHOST", "[ENDIF]", "[SKIPEND]")
        assertEquals(emptyList(), emitted, "SKIPSTART 内的 [IF_NDEBUG] 不得放行 @GHOST")
    }

    @Test
    fun skipstartWithExtraArgDoesNotOpenBlock() {
        // 多余参数 → 告警后 break，不 push / 不开 skip；随后 [SKIPEND] 因栈空再告警。
        val (emitted, warnings) = collect("[SKIPSTART extra]", "@KEPT", "[SKIPEND]", "@ALSO_KEPT")
        assertEquals(listOf("@KEPT", "@ALSO_KEPT"), emitted)
        assertEquals(2, warnings, "HasTooManyArg + UnexpectedSkipend")
    }

    @Test
    fun elseWithExtraArgKeepsIfState() {
        // [ELSE extra] 不改 disabled/done/ppMatch：IF_NDEBUG 的 enabled 分支继续。
        val (emitted, warnings) = collect(
            "[IF_NDEBUG]", "@IFBRANCH", "[ELSE extra]", "@AFTER_ELSE", "[ENDIF]", "@TAIL",
        )
        assertEquals(listOf("@IFBRANCH", "@AFTER_ELSE", "@TAIL"), emitted)
        assertEquals(1, warnings, "只报 [ELSE] 多余参数")
    }

    @Test
    fun elseifOnEmptyStackKeepsEnabled() {
        // 空栈 [ELSEIF UNDEF] 告警后 break，不得把 disabled 永久置 true。
        val (emitted, warnings) = collect("[ELSEIF UNDEF]", "@AFTER")
        assertEquals(listOf("@AFTER"), emitted)
        assertEquals(1, warnings)
    }
}
