package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * deep-sem 路（2026-10-26）语句执行深审的回归钉子。
 *
 * 覆盖面 = 数组族语句的**装载期 vs 运行期**判据分层与官方 zhs 文案，
 * 以及 AWAIT / RESET_STAIN 的报错文案。C# 权威与 zhs 行号逐条写在各断言上方。
 * （遵守 AGENTS.md 铁律 5：块注释正文不含注释开启字符对。）
 */
class SemDeepAuditTest {

    // ---------------- AWAIT 文案 ----------------

    @Test
    fun `AWAIT 超过10000的文案是官方的超过10秒`() {
        val env = SemTestEnv()
        // C# FunctionIdentifier.cs:2599-2602 `Lang.Error.AwaitArgIsOver10Seconds`
        // zhs:529 = `AWAIT命令:指定了一个超过10秒的值({0}ms)`，占位符为毫秒原值。
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.AWAIT, ExpressionArgument(IntLiteral(20000L))))
        }
        assertEquals("AWAIT命令:指定了一个超过10秒的值(20000ms)", e.message)
    }

    @Test
    fun `AWAIT 边界 10000 不报错`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.AWAIT, ExpressionArgument(IntLiteral(10000L))))
        assertTrue(env.executor.yieldBoundaryCount > 0L)
    }

    // ---------------- RESET_STAIN 文案 ----------------

    @Test
    fun `RESET_STAIN 越界文案是 RefUndefinedChara`() {
        val env = SemTestEnv()
        // VariableEvaluator.cs:1923-1930 抛 Lang.Error.RefUndefinedChara（zhs:319 =
        // `试图引用一个尚未定义的角色`），与 OoRCharaNum（:312）不同键。
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.RESET_STAIN, ExpressionArgument(IntLiteral(0L))))
        }
        assertEquals("试图引用一个尚未定义的角色", e.message)
    }

    // ---------------- 数组族的 1 维运行期守卫 ----------------

    @Test
    fun `ARRAYSHIFT 对二维变量抛 IsUsableOnly1DVar`() {
        val env = SemTestEnv()
        // Process.cs:1617-1620。DA 是 int2D（SystemLayout.kt:68），dimension == 2。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYSHIFT,
                    SpArrayShiftArgument(iVar("DA", 0, 0), IntLiteral(1L), IntLiteral(0L), IntLiteral(0L), null),
                ),
            )
        }
        assertEquals("ARRAYSHIFT仅对1位数组以及数组型角色变量有效", e.message)
    }

    @Test
    fun `ARRAYREMOVE 对二维变量抛 IsUsableOnly1DVar`() {
        val env = SemTestEnv()
        // Process.cs:1663-1668 —— ARRAYREMOVE 装载期不校验 1 维（ArgumentParser.cs:2076-2110），
        // 运行期拒否是语料可达路径。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYREMOVE,
                    SpArrayControlArgument(iVar("DA", 0, 0), IntLiteral(1L), IntLiteral(1L)),
                ),
            )
        }
        assertEquals("ARRAYREMOVE仅对1位数组以及数组型角色变量有效", e.message)
    }

    @Test
    fun `ARRAYSORT 对二维变量抛 IsUsableOnly1DVar`() {
        val env = SemTestEnv()
        // Process.cs:1680-1685。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYSORT,
                    SpArraySortArgument(iVar("DA", 0, 0), SortOrder.ASCENDING, IntLiteral(0L), null),
                ),
            )
        }
        assertEquals("ARRAYSORT仅对1位数组以及数组型角色变量有效", e.message)
    }

    // ---------------- 数组族的越界文案 ----------------

    @Test
    fun `ARRAYSHIFT 起始越界文案是 OoRArrayShift`() {
        val env = SemTestEnv()
        val len = env.store.getLength("FLAG")
        // VariableEvaluator.cs:710-713（整数）/ :779-782（字符串）；zhs:309。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYSHIFT,
                    SpArrayShiftArgument(iVar("FLAG", 0), IntLiteral(1L), IntLiteral(0L), IntLiteral(len.toLong()), null),
                ),
            )
        }
        assertEquals("命令ARRAYSHIFT的第四个参数($len)超出了数组\"FLAG\"的范围", e.message)
    }

    @Test
    fun `ARRAYREMOVE 起始越界文案是 OoRArrayRemove`() {
        val env = SemTestEnv()
        val len = env.store.getLength("FLAG")
        // VariableEvaluator.cs:850-853（仅整数分支判）; zhs:310。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYREMOVE,
                    SpArrayControlArgument(iVar("FLAG", 0), IntLiteral(len.toLong()), IntLiteral(1L)),
                ),
            )
        }
        assertEquals("命令ARRAYREMOVE的第二个参数($len)超出了数组\"FLAG\"的范围", e.message)
    }

    @Test
    fun `ARRAYREMOVE 字符串分支不判起始越界`() {
        val env = SemTestEnv()
        val len = env.store.getLength("SAVESTR")
        // VariableEvaluator.cs:855-875（字符串分支无 start 越界判，直接按 num<=0→num=length 走
        // Array.Copy）⇒ 起始越界在字符串分支是**空操作**而不是报错。
        env.run(
            instr(
                FunctionCode.ARRAYREMOVE,
                SpArrayControlArgument(sVar("SAVESTR", 0), IntLiteral(len.toLong()), IntLiteral(1L)),
            ),
        )
    }

    @Test
    fun `ARRAYSORT 起始越界文案是 OoRArraySort`() {
        val env = SemTestEnv()
        val len = env.store.getLength("FLAG")
        // VariableEvaluator.cs:898-901 / :923-926；zhs:311。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYSORT,
                    SpArraySortArgument(iVar("FLAG", 0), SortOrder.ASCENDING, IntLiteral(len.toLong()), null),
                ),
            )
        }
        assertEquals("命令ARRAYSORT的第三参数($len)超出了数组\"FLAG\"的范围", e.message)
    }

    @Test
    fun `ARRAYSORT 范围越界文案回落官方日文默认`() {
        val env = SemTestEnv()
        val len = env.store.getLength("FLAG")
        // VariableEvaluator.cs:908-910 / :933-935 用 OoRSumArray，但 zhs 语言包无该键
        // ⇒ 官方 zhs 运行时回落 Lang.cs:3622 日文默认（照抄纪律 ADR-005）。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYSORT,
                    SpArraySortArgument(
                        iVar("FLAG", 0),
                        SortOrder.ASCENDING,
                        IntLiteral(0L),
                        IntLiteral((len + 1).toLong()),
                    ),
                ),
            )
        }
        assertEquals(
            "命令ARRAYSORTの第３引数(0)と第4変数(${len + 1})の和が配列\"FLAG\"の範囲を超えています",
            e.message,
        )
    }

    // ---------------- ARRAYCOPY 判序与文案 ----------------

    @Test
    fun `ARRAYCOPY 名字不存在文案是 NotVariableName`() {
        val env = SemTestEnv()
        // Process.cs:1726/1738 用 Lang.Error.NotVariableName（zhs:912 =
        // `{0}函数: 第{1}个参数("{2}")不是变量名`）。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYCOPY,
                    SpCopyArrayArgument(StrLiteral("NO_SUCH_VAR"), StrLiteral("FLAG")),
                ),
            )
        }
        assertEquals("ARRAYCOPY函数: 第1个参数(\"NO_SUCH_VAR\")不是变量名", e.message)
    }

    @Test
    fun `ARRAYCOPY 标量名文案是 ArraycopyArgIsNotArray`() {
        val env = SemTestEnv()
        // Process.cs:1728-1731（IsArray1D/2D/3D 三判）→ ArraycopyArgIsNotArray（zhs:511）。
        // SAVEDATA_TEXT 是 0 维字符串（SystemLayout.kt:66-68），dimension == 0。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYCOPY,
                    SpCopyArrayArgument(StrLiteral("SAVEDATA_TEXT"), StrLiteral("FLAG")),
                ),
            )
        }
        assertEquals("ARRAYCOPY命令的第1参数\"SAVEDATA_TEXT\"不是一个数组变量", e.message)
    }

    @Test
    fun `ARRAYCOPY 数组判在角色判之前`() {
        val env = SemTestEnv()
        // Process.cs:1728-1737 的判序 = 非数组 → 角色变量。判序只在**0 维角色变量**上可见：
        // NO 是 charaInt0D（SystemLayout.kt:105-108），dimension == 0 ⇒ 命中数组键
        // （ArraycopyArgIsNotArray）而不是角色键（ArraycopyArgIsCharaVar）。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYCOPY,
                    SpCopyArrayArgument(StrLiteral("NO"), StrLiteral("FLAG")),
                ),
            )
        }
        assertEquals("ARRAYCOPY命令的第1参数\"NO\"不是一个数组变量", e.message)
    }

    @Test
    fun `ARRAYCOPY 角色一维变量文案是 ArraycopyArgIsCharaVar`() {
        val env = SemTestEnv()
        // CFLAG 是角色 1 维（dimension == 1）⇒ 过数组判、命中角色判（Process.cs:1732-1735，
        // ArraycopyArgIsCharaVar = zhs:512）。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYCOPY,
                    SpCopyArrayArgument(StrLiteral("CFLAG"), StrLiteral("FLAG")),
                ),
            )
        }
        assertEquals("ARRAYCOPY命令的第1参数\"CFLAG\"是一个角色变量(不支持)", e.message)
    }

    @Test
    fun `ARRAYCOPY 维度不一致文案`() {
        val env = SemTestEnv()
        // Process.cs:1753-1756 DifferentArraycopyArgsDim（zhs:514）。DA 是 2 维，FLAG 是 1 维。
        val e = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.ARRAYCOPY,
                    SpCopyArrayArgument(StrLiteral("DA"), StrLiteral("FLAG")),
                ),
            )
        }
        assertEquals("ARRAYCOPY命令的两个参数维度不一致", e.message)
    }

    @Test
    fun `ARRAYCOPY 同类一维数组照常复制`() {
        val env = SemTestEnv()
        // 正向对照：判序收紧后合法路径不受影响（Process.cs:1760 CopyArray）。
        env.store.writeInt("DAY", longArrayOf(0), 5L)
        env.store.writeInt("DAY", longArrayOf(1), 6L)
        env.run(
            instr(
                FunctionCode.ARRAYCOPY,
                SpCopyArrayArgument(StrLiteral("DAY"), StrLiteral("FLAG")),
            ),
        )
        assertEquals(5L, env.store.readInt("FLAG", longArrayOf(0)))
        assertEquals(6L, env.store.readInt("FLAG", longArrayOf(1)))
    }
}