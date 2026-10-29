package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 多值赋值（SpSetArrayArgument → `VariableStore.setValues`）对**非数组目标**的拒绝口径
 * （AIOOBE 崩溃修复的钉桩，2026-10-25）。
 *
 * 原版链路（证据）：
 * - 解析期 `ArgumentParser.cs:1044-1097`：字符串变量的 `'=`（AssignmentStr）右值含多个
 *   顶层段时构造 `SpSetArrayArgument`（整数变量的普通 `=` 同理，`:946-981`）。
 * - 执行期 `FunctionIdentifier.cs:612-637`：`VariableDest.SetValue(long[]/string[], exm)`。
 * - 该数组形态只被**数组 token** 覆写（`VariableData.cs` 的 Int1D/Int2D/Int3D/CharaInt1D/
 *   CharaInt2D/CharaStr1D/Local 族/UserDefined 族/Reference 族）；0 维字符串与伪变量的 token
 *   **没有**数组形态 ⇒ 落 `VariableToken.cs:177-185` 基类：
 *   `SetValue(long[])` 抛 CallNDStrAsInt（zhs xml:352）、`SetValue(string[])` 抛
 *   CallNDIntAsStr（zhs xml:353）；PseudoVariableToken 家族四形态全覆写为
 *   AssignToPseudoVar（`VariableData.cs:802-812`，zhs xml:373）。
 *
 * 旧实现（修复前）的病灶：`setValues` 不分派 token 形态、直接按元素位循环写 ——
 * 0 维字符串目标（SAVEDATA_TEXT / WINDOW_TITLE）的下标数组为空，`indices[size-1]`
 * 抛 IndexOutOfBoundsException（内部崩溃，ERB `SAVEDATA_TEXT '= "a", "b"` 实测）；
 * C_INT0/C_STR0 则先写 0 号位、后续位才报越界（半写态）。两条都与原版「整条拒绝、
 * 零写入」不符，本文件钉住修复后的口径。
 */
class SetMultiAssignNonArrayTest {

    // ------------------------------------------------------------------
    // 装置（与 SetAddConstTest 同形）
    // ------------------------------------------------------------------

    private fun fixture(withChara: Boolean = false): Pair<SemTestEnv, LineParser> {
        val env = SemTestEnv()
        if (withChara) env.store.characters.addBare()
        return env to LineParser(SymbolTable())
    }

    private fun SemTestEnv.runLine(parser: LineParser, text: String) = run(parser.parseCodeLine(text, 1)!!)

    private fun assertCodeEE(block: () -> Unit): CodeEE {
        val e = assertFailsWith<CodeEE>(block = block)
        return e
    }

    // ------------------------------------------------------------------
    // 1. 0 维字符串（G_STR0 / 伪变量）：修复前 AIOOBE，修复后原版 CallNDIntAsStr
    // ------------------------------------------------------------------

    /** 修复前：`indices[-1]` IndexOutOfBoundsException。原版 = CallNDIntAsStr（xml:353）。 */
    @Test
    fun `SAVEDATA_TEXT 字符串多值赋值报 CallNDIntAsStr`() {
        val (env, parser) = fixture()
        val e = assertCodeEE { env.runLine(parser, "SAVEDATA_TEXT '= \"a\", \"b\"") }
        assertEquals("非字符串数组变量\"SAVEDATA_TEXT\"被当作字符串调用", e.message)
        // 整条拒绝：单值写不受影响（G_STR0 的标量 SetValue 在原版由 StrVariableToken 覆写，
        // VariableData.cs:313-315）。
        env.runLine(parser, "SAVEDATA_TEXT '= \"x\"")
        assertEquals("x", env.store.readStr("SAVEDATA_TEXT", longArrayOf()))
    }

    /** WINDOW_TITLE 是唯一可写伪变量；其 token（VariableData.cs:943-958）没有数组形态。 */
    @Test
    fun `WINDOW_TITLE 字符串多值赋值报 CallNDIntAsStr`() {
        val (env, parser) = fixture()
        val e = assertCodeEE { env.runLine(parser, "WINDOW_TITLE '= \"a\", \"b\"") }
        assertEquals("非字符串数组变量\"WINDOW_TITLE\"被当作字符串调用", e.message)
        env.runLine(parser, "WINDOW_TITLE '= \"标题\"")
        assertEquals("标题", env.store.windowTitle)
    }

    /** 存储层直呼入口同口径（崩溃点本身）。 */
    @Test
    fun `setValues 字符串重载空下标报 CodeEE 而非 AIOOBE`() {
        val store = VariableStore()
        val e = assertFailsWith<CodeEE> {
            store.setValues("SAVEDATA_TEXT", longArrayOf(), arrayOf("a", "b"))
        }
        assertEquals("非字符串数组变量\"SAVEDATA_TEXT\"被当作字符串调用", e.message)
    }

    // ------------------------------------------------------------------
    // 2. 0 维角色变量（C_INT0 / C_STR0）：修复前半写态，修复后整条拒绝
    // ------------------------------------------------------------------

    /**
     * `NO:0 = 1, 2`：原版 CharaIntVariableToken（VariableData.cs:412-441）未覆写
     * `SetValue(long[])` ⇒ 基类 CallNDStrAsInt（xml:352），**零写入**。
     * 修复前先写 NO:0=1、写 NO:1 才报角色越界。
     */
    @Test
    fun `NO 整数多值赋值整条拒绝且零写入`() {
        val (env, parser) = fixture(withChara = true)
        val e = assertCodeEE { env.runLine(parser, "NO:0 = 1, 2") }
        assertEquals("非数值数组变量\"NO\"被当作数值调用", e.message)
        assertEquals(0L, env.store.readInt("NO", longArrayOf(0)), "整条拒绝：0 号位不得被写入")
    }

    /** 同上，字符串侧（CharaStrVariableToken，VariableData.cs:490-512，无数组形态覆写）。 */
    @Test
    fun `NAME 字符串多值赋值整条拒绝且零写入`() {
        val (env, parser) = fixture(withChara = true)
        val e = assertCodeEE { env.runLine(parser, "NAME:0 '= \"a\", \"b\"") }
        assertEquals("非字符串数组变量\"NAME\"被当作字符串调用", e.message)
        assertEquals("", env.store.readStr("NAME", longArrayOf(0)), "整条拒绝：0 号位不得被写入")
    }

    // ------------------------------------------------------------------
    // 3. 伪变量整数通道：解析期先拦 LeftIsConst；存储层分派仍留 AssignToPseudoVar
    // ------------------------------------------------------------------

    /**
     * `CHARANUM` 是 `__UNCHANGEABLE__` 伪变量 ⇒ `SET` 在**解析期**就报「左值是常量」，
     * 三种形态（多值 / 单值 / 增量）都到不了 `VariableStore`。
     *
     * spec: `ArgumentParser.cs:903-907`（先判 `IsConst` 再分派运算符）；
     * `VariableCode.CHARANUM = 12713985`（0xC20001）含 `__UNCHANGEABLE__`(0x400000)
     * （`VariableCode.cs:11/155`）⇒ `VariableToken.cs:44` 的 `IsConst` 为真；
     * `assignwarn(level=2)` 置 `line.IsError`（`ArgumentBuilder.cs:19-28`）。
     *
     * 2026-10-25 本处曾钉执行期 `AssignToPseudoVar`（`试图赋值给伪变量"CHARANUM"`）——
     * 对本语句不可达（`VariableStore` 的 KDoc 当时把 CHARANUM 当成非 const，2026-10-26 深审波
     * 按 `VariableCode` 实值改钉）。
     */
    @Test
    fun `CHARANUM 赋值在解析期即被拒为常量`() {
        val (_, parser) = fixture()
        for (text in listOf("CHARANUM = 1, 2", "CHARANUM = 5", "CHARANUM += 1")) {
            val e = assertFailsWith<StmtParseException>("$text 应在解析期失败") {
                parser.parseCodeLine(text, 1)!!
            }
            assertTrue(e.message!!.contains("左值是常量"), "实际文案: ${e.message}")
        }
    }

    /**
     * 存储层的伪变量分派仍按 `PseudoVariableToken.SetValue` 覆写报 `AssignToPseudoVar`
     * （`VariableData.cs:797-812`，zhs xml:373）——它只由宿主 API / 直调写入口到达，
     * 不经 ERB 语句，故此处直接调 [VariableStore.setValues] 钉住该守卫不被删除。
     */
    @Test
    fun `存储层伪变量多值写入仍整条拒绝并报 AssignToPseudoVar`() {
        val (env, _) = fixture()
        val e = assertCodeEE { env.store.setValues("CHARANUM", longArrayOf(0), longArrayOf(1, 2)) }
        assertEquals("试图赋值给伪变量\"CHARANUM\"", e.message)
    }

    // ------------------------------------------------------------------
    // 4. 回归：正常数组的多值赋值不受守卫影响
    // ------------------------------------------------------------------

    @Test
    fun `普通数组多值赋值照常落盘`() {
        val (env, parser) = fixture()
        env.runLine(parser, "DAY = 5, 6")
        assertEquals(5L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals(6L, env.store.readInt("DAY", longArrayOf(1)))
        env.runLine(parser, "RESULTS '= \"r0\", \"r1\"")
        assertEquals("r0", env.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("r1", env.store.readStr("RESULTS", longArrayOf(1)))
    }

    @Test
    fun `普通数组带下标多值赋值照常落盘`() {
        val (env, parser) = fixture()
        env.runLine(parser, "DAY:3 = 7, 8")
        assertEquals(7L, env.store.readInt("DAY", longArrayOf(3)))
        assertEquals(8L, env.store.readInt("DAY", longArrayOf(4)))
        env.runLine(parser, "STR:2 '= \"s2\", \"s3\"")
        assertEquals("s2", env.store.readStr("STR", longArrayOf(2)))
        assertEquals("s3", env.store.readStr("STR", longArrayOf(3)))
    }

    /** SET 多值路径的语句形态钉桩：多右值确实构造 SpSetArrayArgument（ArgumentParser.cs:980/:1096）。 */
    @Test
    fun `多右值解析为 SpSetArrayArgument`() {
        val (_, parser) = fixture()
        val stmt = parser.parseCodeLine("DAY = 1, 2", 1)!! as com.eratw.port.core.stmt.AssignStmt
        assertEquals(FunctionCode.SET, stmt.code)
        assertTrue(stmt.args is com.eratw.port.core.stmt.SpSetArrayArgument, "实际 ${stmt.args.shape}")
    }
}
