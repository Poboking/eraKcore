package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B5 + A1 第 3/4 条收口（2026-11-07）：常量闸回解析期后，CALLSHARP 的常量实参照常回写。
 *
 * 语义权威（官方 C#）：
 * - 回写环只看「实参是不是变量项」：`FunctionIdentifier.cs:1219-1231`；
 * - 官方存储层写入口**没有**常量闸：`VariableData.cs:1340-1344`（INT 单格）、
 *   `:1614-1618`（STR 单格）都只 `array[arguments[0]] = value`；全文件唯一的 `IsConst`
 *   读点在 `:4089`（存档装载白名单）；
 * - 对常量的拦截在**解析期**：赋值左值 `ArgumentParser.cs:903-907`（LeftIsConst）、
 *   VARSET 目标 `:1784-1788`、SAVEVAR 目标 `:2197-2201`、可变实参位
 *   `ArgumentBuilder.cs:92-105`。移植侧同形闸在 `stmt/ArgBuilders.kt`（set :1768 等）。
 *
 * 因此「本地方法故意改写 `#DIMS CONST` 实参槽」时官方让它变值 —— 本闸钉住这条读数，
 * 并在两条执行通道（扁平语句池 / 对象树 = Android 生产默认）上各钉一遍。
 * 第二条用例反向钉「常量目标仍在解析期被拒」：存储层的闸删掉之后，装载期闸是唯一的一道。
 */
class CallSharpConstWriteBackTest {

    private fun resolver(): SymbolTable = SymbolTable()
        .declareVariable("KEEP_I", OperandType.INT, 1, isConst = true)
        .declareVariable("KEEP_S", OperandType.STR, 1, isConst = true)
        .declareVariable("PLAIN", OperandType.INT, 1)

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(resolver())
        return lines.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            p.parseLogicalLine(LogicalLine(kind, "constwrite.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("SHARP_CONST_PROBE", 0),
            parse("@SHARP_CONST_PROBE", *lines),
            setOf(),
            ExprCallTable(),
        )
        return c.build("SHARP_CONST_PROBE")
    }

    /** 常量用户变量：#DIM CONST KEEP_I = 5 / #DIMS CONST KEEP_S = 'x' 的存储层等价物。 */
    private fun storeWithConsts(): VariableStore {
        val store = VariableStore()
        store.declareUserVar(
            DimSpec(
                name = "KEEP_I", isStr = false, dims = intArrayOf(1), isConst = true,
                defaultsInt = longArrayOf(5L),
            ),
        )
        store.declareUserVar(
            DimSpec(
                name = "KEEP_S", isStr = true, dims = intArrayOf(1), isConst = true,
                defaultsStr = listOf("x"),
            ),
        )
        // 对照组：同处声明、无 CONST —— 常量位被放行不该顺带放过「普通变量也写不进去」的反向病。
        store.declareUserVar(DimSpec(name = "PLAIN", isStr = false, dims = intArrayOf(1)))
        return store
    }

    private fun runtimeWith(program: Program, store: VariableStore): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
        )

    /** 对象树通道（`flatStmtSource == null`）= `GameHost` 的 `flatStatements = false` 生产档。 */
    private fun asTreeChannel(program: Program): Program {
        program.flatStatementsEnabled = false
        return program
    }

    /** 注册一个**故意改写两个实参槽**的本地方法（对应那类会回写常量位的宿主方法）。 */
    private fun registerBump(store: VariableStore) {
        nativeMethods(store.aux).register("Bump") { p ->
            p[0].intValue += 1
            p[1].strValue += "!"
        }
    }

    @Test
    fun `扁平通道 常量实参槽被本地方法改写`() {
        val store = storeWithConsts()
        registerBump(store)
        runtimeWith(compile("CALLSHARP Bump(KEEP_I, KEEP_S)"), store).execute()
        assertEquals(6L, store.readInt("KEEP_I", longArrayOf(0)), "官方回写环对常量位不豁免（FunctionIdentifier.cs:1219-1231）")
        assertEquals("x!", store.readStr("KEEP_S", longArrayOf(0)), "字符串常量位同口径")
    }

    @Test
    fun `对象树通道 常量实参槽被本地方法改写`() {
        val store = storeWithConsts()
        registerBump(store)
        runtimeWith(asTreeChannel(compile("CALLSHARP Bump(KEEP_I, KEEP_S)")), store).execute()
        assertEquals(6L, store.readInt("KEEP_I", longArrayOf(0)), "Android 生产档（flatStatements=false）同读数")
        assertEquals("x!", store.readStr("KEEP_S", longArrayOf(0)))
    }

    @Test
    fun `常量目标仍在解析期被拒`() {
        // spec: ArgumentParser.cs:903-907 的 LeftIsConst —— 解析期报错、不产语句（移植侧 fail 抛）。
        val e = assertFailsWith<StmtParseException> { parse("KEEP_I = 3") }
        assertTrue("常量" in e.toString(), "解析期文案要点名常量：${e.message}")
    }

    @Test
    fun `非常量目标照常可写`() {
        val store = storeWithConsts()
        runtimeWith(compile("PLAIN = 9"), store).execute()
        assertEquals(9L, store.readInt("PLAIN", longArrayOf(0)), "对照组：普通用户变量不受影响")
    }
}
