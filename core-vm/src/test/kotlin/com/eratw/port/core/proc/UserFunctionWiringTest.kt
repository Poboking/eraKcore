package com.eratw.port.core.proc

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.config.ConfigChainLoad
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.UserFunctionKind
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P1 回归：生产装配（[ProcessRuntime]）必须把**用户函数目录 + 调用通道**接到
 * `BuiltinHost.userFunctions` / `invokeUserFunction`。
 *
 * 原版数据源 = `Process.LabelDictionary` 的非事件标签表
 * （FunctionMethodCreator.cs:8222-8304 的 EXISTFUNCTION、:8533-8572 GETMETH、
 * :8575-8614 GETMETHS、:8617-8653 EXISTMETH；GetFunctionMethod 只查该表，
 * IdentifierDictionary.cs:538-586）。
 *
 * 修复前生产宿主两项都是接口缺省 null ⇒ `EXISTMETH(@"COM…")` 恒 0、
 * `GETMETH(name, 0)` 永远落到默认值 0（语料 COMMON.ERB:4326 / QOL_USERCOM.ERB:168）。
 * 本用例只经 `ProcessRuntime.evalContext` 调用内建函数，不依赖任何 E2E 装置的自建表。
 */
class UserFunctionWiringTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexed { i, text ->
            p.parseLogicalLine(LogicalLine(LineKind.CODE, "u.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun index(c: ErbProgramCompiler, name: String): Int =
        c.functions.first { it.sig.name.equals(name, ignoreCase = true) }.index

    /** MAIN + #FUNCTION + #FUNCTIONS + 普通 @label + 一个事件（只登记元数据）。 */
    private fun runtime(host: BuiltinHost? = null): ProcessRuntime {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), emptyList())
        c.compileFunction(FuncSig("M_INT", 0, isMethod = true), parse("RETURNF 42"))
        c.compileFunction(FuncSig("M_STR", 0, isMethod = true, returnsStr = true), parse("RETURNF \"zS\""))
        c.compileFunction(FuncSig("PLAIN", 0), emptyList())
        val metas = listOf(
            FuncMeta(index = index(c, "MAIN"), key = "MAIN", name = "MAIN"),
            FuncMeta(index = index(c, "M_INT"), key = "M_INT", name = "M_INT", isMethod = true),
            FuncMeta(
                index = index(c, "M_STR"), key = "M_STR", name = "M_STR",
                isMethod = true, returnsStr = true,
            ),
            FuncMeta(index = index(c, "PLAIN"), key = "PLAIN", name = "PLAIN"),
            // 事件只用于验证「事件不进非事件函数目录」；下标取不到实际函数也无妨（不会被调用）。
            FuncMeta(index = 10_000, key = "EVENTFIRST", name = "EVENTFIRST", isEvent = true),
        )
        return ProcessRuntime(c.build("MAIN"), VariableStore(), FunctionTable(metas), VmConfig(), builtinHost = host)
    }

    private fun intFn(rt: ProcessRuntime, name: String, vararg a: Value?): Long =
        (rt.evalContext.callFunction(name, a.toList()) as Value.IntV).v

    private fun strFn(rt: ProcessRuntime, name: String, vararg a: Value?): String =
        (rt.evalContext.callFunction(name, a.toList()) as Value.StrV).v

    @Test
    fun `生产宿主经函数表回答 EXISTFUNCTION 与 EXISTMETH`() {
        val rt = runtime()
        // ExistFunctionMethod :8269-8280：方法看 MethodType（str=3 / long=2），普通 @label=1。
        assertEquals(2L, intFn(rt, "EXISTFUNCTION", Value.StrV("M_INT")))
        assertEquals(3L, intFn(rt, "EXISTFUNCTION", Value.StrV("M_STR")))
        assertEquals(1L, intFn(rt, "EXISTFUNCTION", Value.StrV("PLAIN")))
        assertEquals(0L, intFn(rt, "EXISTFUNCTION", Value.StrV("NO_SUCH")))
        // 名称大小写不敏感（Config.StrComper = OrdinalIgnoreCase，Config.cs:33）。
        assertEquals(2L, intFn(rt, "EXISTFUNCTION", Value.StrV("m_int")))
        // 事件标签不在 LabelDictionary 的非事件域（LabelDictionary.cs:63-73）→ 0。
        assertEquals(0L, intFn(rt, "EXISTFUNCTION", Value.StrV("EVENTFIRST")))

        // ExistMethMethod :8642-8651：Int64 方法 = 1、String 方法 = 2、普通 @label / 未知 = 0。
        assertEquals(1L, intFn(rt, "EXISTMETH", Value.StrV("M_INT")))
        assertEquals(2L, intFn(rt, "EXISTMETH", Value.StrV("M_STR")))
        assertEquals(0L, intFn(rt, "EXISTMETH", Value.StrV("PLAIN")))
        assertEquals(0L, intFn(rt, "EXISTMETH", Value.StrV("NO_SUCH")))
    }

    @Test
    fun `GETMETH 与 GETMETHS 经重入通道真执行用户方法`() {
        val rt = runtime()
        // GetMethMethod :8556-8571：第 1 参之后（Skip(2)）才是转传给用户方法的实参。
        assertEquals(42L, intFn(rt, "GETMETH", Value.StrV("M_INT"), Value.IntV(0)))
        assertEquals(42L, intFn(rt, "GETMETH", Value.StrV("m_int"), Value.IntV(0)))
        // 未定义 → 有默认值返回默认值（:8561-8565）。
        assertEquals(-7L, intFn(rt, "GETMETH", Value.StrV("NO_SUCH"), Value.IntV(-7)))
        // 普通 @label → CallfNonMethodFunc（:557-559），GETMETH 不捕获（:564-565）。
        assertFailsWith<CodeEE> { intFn(rt, "GETMETH", Value.StrV("PLAIN"), Value.IntV(0)) }
        // 未定义且无默认值 → NotDefinedUserFunc（:8561-8564）。
        assertFailsWith<CodeEE> { intFn(rt, "GETMETH", Value.StrV("NO_SUCH")) }

        // GetMethsMethod :8596-8613（同构的字符串通道）。
        assertEquals("zS", strFn(rt, "GETMETHS", Value.StrV("M_STR"), Value.StrV("DEF")))
        assertEquals("DEF", strFn(rt, "GETMETHS", Value.StrV("NO_SUCH"), Value.StrV("DEF")))
        assertFailsWith<CodeEE> { strFn(rt, "GETMETHS", Value.StrV("M_INT"), Value.StrV("DEF")) }
    }

    @Test
    fun `ENUMFUNC 枚举的是用户函数而不是内建函数名`() {
        val rt = runtime()
        // EnumNameMethod 的 FUNCTION 分支 = NoneventKeys（FunctionMethodCreator.cs:280）。
        assertEquals(2L, intFn(rt, "ENUMFUNCWITH", Value.StrV("M_")))
        assertEquals(1L, intFn(rt, "ENUMFUNCBEGINSWITH", Value.StrV("PLAIN")))
        assertEquals(0L, intFn(rt, "ENUMFUNCBEGINSWITH", Value.StrV("PRINTDATA")))
    }

    @Test
    fun `宿主显式提供的用户函数表与调用器优先于运行时投影`() {
        val host = object : BuiltinHost {
            override val userFunctions: Map<String, UserFunctionKind> =
                mapOf("HOSTFN" to UserFunctionKind.PLAIN)
            override val invokeUserFunction: ((String, List<Value?>) -> Value?)? =
                { _, _ -> Value.IntV(5L) }
        }
        val rt = runtime(host)
        // E2E 装置（GoldenPipeline）按符号表自建的表不能被包装层顶掉。
        assertEquals(1L, intFn(rt, "EXISTFUNCTION", Value.StrV("HOSTFN")))
        assertEquals(0L, intFn(rt, "EXISTFUNCTION", Value.StrV("M_INT")))
    }

    @Test
    fun `包装宿主保留 ConfigChainSource 面`() {
        // 生产宿主 ConsoleBuiltinHost 同时实现 ConfigChainSource；ConfigMethods.storeFromHost
        // 用 `host as? ConfigChainSource` 选三层链优先通道（ConfigMethods.kt:182-191），
        // 用户函数补丁不能把这一面吃掉。
        val chainHost = object : BuiltinHost, ConfigChainSource {
            override val configChain: ConfigChainLoad =
                ConfigChainLoad(ConfigStore(), emptyList(), emptyList(), Path.of("."))

            override fun applyChainTo(store: ConfigStore): ConfigChainLoad = configChain
        }
        val rt = runtime(chainHost)
        assertTrue(
            (rt.evalContext as BuiltinContext).host is ConfigChainSource,
            "包装后的宿主必须仍可被识别为 ConfigChainSource",
        )
        assertEquals(2L, intFn(rt, "EXISTFUNCTION", Value.StrV("M_INT")))
    }
}
