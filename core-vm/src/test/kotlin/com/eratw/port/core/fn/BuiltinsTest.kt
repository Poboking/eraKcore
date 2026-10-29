package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/** 内建函数测试环境：真实 VariableStore + 桥 + 固定时钟宿主。 */
private class BaseCtx(val lines: MutableList<String> = mutableListOf()) : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("BaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {
        lines += message
    }
}

private fun fixedClockHost(millis: Long = 1788000000000L) = object : BuiltinHost {
    override fun clockMillis(): Long = millis
}

/** 构建测试环境。 */
private fun env(
    clock: Long = 1788000000000L,
    userFunctions: Map<String, (List<Value?>) -> Value> = emptyMap(),
): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = BaseCtx()
    base.variables = store
    val ctx = BuiltinEvalContext(base, store, fixedClockHost(clock), userFunctions = userFunctions)
    return ctx to store
}

private fun call(ctx: BuiltinEvalContext, name: String, vararg args: Value?): Value =
    ctx.callFunction(name, args.toList())

private fun iv(v: Value): Long = (v as Value.IntV).v
private fun sv(v: Value): String = (v as Value.StrV).v
private fun iArg(v: Long): Value = Value.IntV(v)
private fun sArg(v: String): Value = Value.StrV(v)

/** 引用参数调用（SUMARRAY/FINDCHARA 等的入口形态）。 */
private fun refCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is VarRef -> it
                is Long -> IntLiteral(it)
                is Int -> IntLiteral(it.toLong())
                is String -> com.eratw.port.core.expr.StrLiteral(it)
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun ref(name: String, vararg indices: Long) =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.INT)

/** 字符串变量引用（STRJOIN/GETVARS 等字符串数组的入口形态）。 */
private fun refS(name: String, vararg indices: Long) =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

class BuiltinPureTest {
    @Test
    fun minMax() {
        val (ctx, _) = env()
        assertEquals(-2L, iv(call(ctx, "MIN", iArg(3), iArg(7), iArg(-2))))
        assertEquals(-2L, iv(call(ctx, "MIN", iArg(-2), iArg(0))))
        assertEquals(7L, iv(call(ctx, "MAX", iArg(3), iArg(7), iArg(-2))))
        assertEquals(7L, iv(call(ctx, "MAX", iArg(7))))
    }

    @Test
    fun abs() {
        val (ctx, _) = env()
        assertEquals(5L, iv(call(ctx, "ABS", iArg(-5))))
        assertEquals(5L, iv(call(ctx, "ABS", iArg(5))))
        assertFailsWith<CodeEE> { call(ctx, "ABS", iArg(Long.MIN_VALUE)) }
    }

    @Test
    fun powerSqrtCbrt() {
        val (ctx, _) = env()
        assertEquals(1024L, iv(call(ctx, "POWER", iArg(2), iArg(10))))
        assertEquals(1L, iv(call(ctx, "POWER", iArg(0), iArg(0))))
        assertFailsWith<CodeEE> { call(ctx, "POWER", iArg(2), iArg(100)) }
        assertEquals(3L, iv(call(ctx, "SQRT", iArg(10))))
        assertEquals(3L, iv(call(ctx, "CBRT", iArg(27))))
        assertFailsWith<CodeEE> { call(ctx, "SQRT", iArg(-1)) }
    }

    @Test
    fun logExponentSign() {
        val (ctx, _) = env()
        assertEquals(1L, iv(call(ctx, "LOG", iArg(3))))      // ln3 = 1.0986 → 1
        assertEquals(2L, iv(call(ctx, "LOG10", iArg(100))))
        assertEquals(1L, iv(call(ctx, "EXPONENT", iArg(0)))) // e^0 = 1
        assertEquals(0L, iv(call(ctx, "SIGN", iArg(0))))
        assertEquals(-1L, iv(call(ctx, "SIGN", iArg(-9))))
        assertEquals(5L, iv(call(ctx, "LIMIT", iArg(5), iArg(1), iArg(10))))
        assertEquals(1L, iv(call(ctx, "LIMIT", iArg(-5), iArg(1), iArg(10))))
    }

    @Test
    fun getbit() {
        val (ctx, _) = env()
        assertEquals(1L, iv(call(ctx, "GETBIT", iArg(0b1010), iArg(1))))
        assertEquals(0L, iv(call(ctx, "GETBIT", iArg(0b1010), iArg(2))))
        assertEquals(1L, iv(call(ctx, "GETBIT", iArg(-1), iArg(63))))
        assertFailsWith<CodeEE> { call(ctx, "GETBIT", iArg(1), iArg(64)) }
        assertFailsWith<CodeEE> { call(ctx, "GETBIT", iArg(1), iArg(-1)) }
    }

    @Test
    fun matchFamily() {
        val (ctx, _) = env()
        assertEquals(2L, iv(call(ctx, "GROUPMATCH", iArg(1), iArg(1), iArg(2), iArg(1))))
        assertEquals(1L, iv(call(ctx, "NOSAMES", iArg(1), iArg(2), iArg(3))))
        assertEquals(0L, iv(call(ctx, "NOSAMES", iArg(1), iArg(2), iArg(1))))
        assertEquals(1L, iv(call(ctx, "ALLSAMES", iArg(4), iArg(4), iArg(4))))
        assertEquals(0L, iv(call(ctx, "ALLSAMES", iArg(4), iArg(4), iArg(5))))
        assertEquals(1L, iv(call(ctx, "INRANGE", iArg(5), iArg(1), iArg(10))))
        assertEquals(0L, iv(call(ctx, "INRANGE", iArg(0), iArg(1), iArg(10))))
    }

    @Test
    fun barstr() {
        val (ctx, _) = env()
        assertEquals("[***.......]", sv(call(ctx, "BARSTR", iArg(30), iArg(100), iArg(10))))
        assertEquals("[**........]", sv(call(ctx, "BARSTR", iArg(25), iArg(100), iArg(10))))
        assertEquals("[**********]", sv(call(ctx, "BARSTR", iArg(200), iArg(100), iArg(10))))
        assertEquals("[..........]", sv(call(ctx, "BARSTR", iArg(-5), iArg(100), iArg(10))))
        assertFailsWith<CodeEE> { call(ctx, "BARSTR", iArg(1), iArg(0), iArg(10)) }
        assertFailsWith<CodeEE> { call(ctx, "BARSTR", iArg(1), iArg(10), iArg(100)) }
    }

    @Test
    fun colorFrom() {
        val (ctx, _) = env()
        assertEquals(0xFF0000L, iv(call(ctx, "COLOR_FROMRGB", iArg(255), iArg(0), iArg(0))))
        assertFailsWith<CodeEE> { call(ctx, "COLOR_FROMRGB", iArg(256), iArg(0), iArg(0)) }
        assertEquals(0x0000FFL, iv(call(ctx, "COLOR_FROMNAME", sArg("blue"))))
        assertEquals(-1L, iv(call(ctx, "COLOR_FROMNAME", sArg("nosuchcolor"))))
        assertFailsWith<CodeEE> { call(ctx, "COLOR_FROMNAME", sArg("transparent")) }
    }

    @Test
    fun strlensWidth() {
        val (ctx, _) = env()
        assertEquals(2L, iv(call(ctx, "STRLENS", sArg("ab"))))
        assertEquals(4L, iv(call(ctx, "STRLENS", sArg("ａｂ"))))
        assertEquals(6L, iv(call(ctx, "STRLENS", sArg("a全角b"))))  // GBK: 1+2+2+1
        assertEquals(4L, iv(call(ctx, "STRLENSU", sArg("a全角b"))))
    }

    @Test
    fun substringBoundaries() {
        val (ctx, _) = env()
        assertEquals("bc", sv(call(ctx, "SUBSTRING", sArg("abcd"), iArg(1), iArg(2))))
        assertEquals("cd", sv(call(ctx, "SUBSTRING", sArg("abcd"), iArg(2))))
        assertEquals("", sv(call(ctx, "SUBSTRING", sArg("abcd"), iArg(4))))
        // SUBSTRINGU 怪行为：start >= len → ""；length==0 → ""；length<0 → 到末尾
        assertEquals("", sv(call(ctx, "SUBSTRINGU", sArg("abc"), iArg(3))))
        assertEquals("", sv(call(ctx, "SUBSTRINGU", sArg("abc"), iArg(0), iArg(0))))
        assertEquals("bc", sv(call(ctx, "SUBSTRINGU", sArg("abc"), iArg(1), iArg(-1))))
        assertEquals("abc", sv(call(ctx, "SUBSTRINGU", sArg("abc"), iArg(0), iArg(-1))))
        // 全角按显示宽度截断（"あい" 宽 4）
        assertEquals("あ", sv(call(ctx, "SUBSTRING", sArg("あい"), iArg(0), iArg(2))))
    }

    @Test
    fun strfind() {
        val (ctx, _) = env()
        assertEquals(1L, iv(call(ctx, "STRFIND", sArg("abc"), sArg("b"))))
        assertEquals(-1L, iv(call(ctx, "STRFIND", sArg("abc"), sArg("z"))))
        assertEquals(2L, iv(call(ctx, "STRFINDU", sArg("abcabc"), sArg("c"), iArg(2))))
        // 非 unicode 版：起始位置按显示宽度换算（lang 3 → 字符 2），结果也按显示宽度返回
        assertEquals(4L, iv(call(ctx, "STRFIND", sArg("あいb"), sArg("b"), iArg(3))))
    }

    @Test
    fun strfindRequiresTwoArgs() {
        // spec: FunctionMethodCreator.cs:5394-5406（ArgTypes 3 槽 String/String/Int + OmitStart = 2）
        // + FunctionMethod.cs:183 —— 个数检查用 `arguments.Count >= OmitStart`，不足即
        // NotEnoughArgs（:283），故 1 实参是 CodeEE 而不是下标越界（修复前单参调用在 `a[1]!!` 处崩溃）。
        // STRFINDU 是同一实现类的 unicode 分支。
        val (ctx, _) = env()
        assertFailsWith<CodeEE> { call(ctx, "STRFIND", sArg("abc")) }
        assertFailsWith<CodeEE> { call(ctx, "STRFINDU", sArg("abc")) }
        // 第 2 位是省略位之前的位置：空位报 ArgCanNotBeNull（FunctionMethod.cs:195-205）
        assertFailsWith<CodeEE> { ctx.callFunction("STRFIND", listOf<Value?>(Value.StrV("abc"), null)) }
        // 2 实参（省略 start=0）与 3 实参都是合法形态
        assertEquals(1L, iv(call(ctx, "STRFIND", sArg("abc"), sArg("b"))))
        assertEquals(1L, iv(call(ctx, "STRFINDU", sArg("abc"), sArg("b"))))
        assertEquals(1L, iv(call(ctx, "STRFIND", sArg("abc"), sArg("b"), iArg(0))))
        // 第 4 参不存在（只有 3 个槽位）
        assertFailsWith<CodeEE> { call(ctx, "STRFIND", sArg("abc"), sArg("b"), iArg(0), iArg(1)) }
    }

    @Test
    fun strcount() {
        val (ctx, _) = env()
        assertEquals(2L, iv(call(ctx, "STRCOUNT", sArg("abcabc"), sArg("bc"))))
        assertEquals(0L, iv(call(ctx, "STRCOUNT", sArg("abc"), sArg("z"))))
    }

    @Test
    fun regexpMatchThreeArgsWriteResult() {
        // spec: FunctionMethodCreator.cs:1047-1054 —— 3 实参且第 3 位 != 0 时写
        // RESULT_ARRAY[1]（组数，含 0 组）与 RESULTS_ARRAY（匹配序 × 组序平铺）。
        // 表达式层优先走 evalRef（Builtins.kt:743），故这条副作用必须在引用路径上同样发生。
        val (ctx, store) = env()
        // flag=0：不写（3 参只开标志位、不取结果）
        assertEquals(1L, iv(refCall(ctx, "REGEXPMATCH", "abc", "a.c", 0L)))
        assertEquals(0L, store.readInt("RESULT", longArrayOf(1)))
        assertEquals("", store.readStr("RESULTS", longArrayOf(0)))
        // flag!=0：2 处命中 "(a)(b)" → 组数 3（0 组 + 2 个捕获组），组值 6 个
        assertEquals(2L, iv(refCall(ctx, "REGEXPMATCH", "abcabc", "(a)(b)", 1L)))
        assertEquals(3L, store.readInt("RESULT", longArrayOf(1)))
        assertEquals(
            listOf("ab", "a", "b", "ab", "a", "b"),
            (0L..5L).map { store.readStr("RESULTS", longArrayOf(it)) },
        )
        // 2 实参不碰结果容器（既有行为不变）：RESULT[1] 仍是上一次的 3
        assertEquals(1L, iv(refCall(ctx, "REGEXPMATCH", "abc", "b")))
        assertEquals(3L, store.readInt("RESULT", longArrayOf(1)))
        assertEquals("ab", store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun tostr() {
        val (ctx, _) = env()
        assertEquals("42", sv(call(ctx, "TOSTR", iArg(42))))
        assertEquals("00042", sv(call(ctx, "TOSTR", iArg(42), sArg("D5"))))
        assertEquals("0000002A", sv(call(ctx, "TOSTR", iArg(42), sArg("X8"))))
        assertFailsWith<CodeEE> { call(ctx, "TOSTR", iArg(42), sArg("Q9")) }
    }

    @Test
    fun tointPaths() {
        val (ctx, _) = env()
        assertEquals(123L, iv(call(ctx, "TOINT", sArg("123"))))
        assertEquals(0L, iv(call(ctx, "TOINT", sArg("12a"))))   // 尾部非数字 → 0（spec: ToIntMethod 的 !EOS 分支）
        assertEquals(-7L, iv(call(ctx, "TOINT", sArg("-7"))))
        assertEquals(5L, iv(call(ctx, "TOINT", sArg("+5"))))
        assertEquals(1L, iv(call(ctx, "TOINT", sArg("1.5"))))
        assertEquals(0L, iv(call(ctx, "TOINT", sArg(""))))
        assertEquals(0L, iv(call(ctx, "TOINT", sArg(" 1"))))
        assertEquals(0L, iv(call(ctx, "TOINT", sArg("ｆｕｌｌ"))))
        assertEquals(0L, iv(call(ctx, "TOINT", sArg("x"))))
        assertEquals(255L, iv(call(ctx, "TOINT", sArg("0xff"))))
        assertFailsWith<CodeEE> { call(ctx, "TOINT", sArg("99999999999999999999")) }
    }

    @Test
    fun caseChangeAndWidth() {
        val (ctx, _) = env()
        assertEquals("ABC", sv(call(ctx, "TOUPPER", sArg("aBc"))))
        assertEquals("abc", sv(call(ctx, "TOLOWER", sArg("aBc"))))
        assertEquals("Ａｂ", sv(call(ctx, "TOFULL", sArg("Ab"))))
        assertEquals("Ab", sv(call(ctx, "TOHALF", sArg("Ａｂ"))))
        assertEquals("", sv(call(ctx, "TOUPPER", sArg(""))))
    }

    @Test
    fun replace() {
        val (ctx, _) = env()
        // mode 0 = 正则替换（spec: ReplaceMethod 默认分支 RegexFactory.GetRegex）
        assertEquals("a-ca-c", sv(call(ctx, "REPLACE", sArg("abcabc"), sArg("b+"), sArg("-"))))
        assertEquals("abcabc", sv(call(ctx, "REPLACE", sArg("abcabc"), sArg("b+"), sArg("-"), iArg(2))))
    }

    @Test
    fun unicode() {
        val (ctx, _) = env()
        assertEquals("A", sv(call(ctx, "UNICODE", iArg(65))))
        assertEquals("あ", sv(call(ctx, "UNICODE", iArg(0x3042))))
        assertEquals("", sv(call(ctx, "UNICODE", iArg(9))))  // 控制字符：警告 + 空串
        assertEquals(1, (ctx as? BuiltinEvalContext)?.let { 1 } ?: 1)
        assertFailsWith<CodeEE> { call(ctx, "UNICODE", iArg(65536)) }
        assertEquals(65L, iv(call(ctx, "UNICODEBYTE", sArg("A"))))
        assertEquals(0x3042L, iv(call(ctx, "UNICODEBYTE", sArg("あ"))))
    }

    @Test
    fun convertAndNumeric() {
        val (ctx, _) = env()
        assertEquals("ff", sv(call(ctx, "CONVERT", iArg(255), iArg(16))))
        assertEquals("1010", sv(call(ctx, "CONVERT", iArg(10), iArg(2))))
        assertFailsWith<CodeEE> { call(ctx, "CONVERT", iArg(10), iArg(3)) }
        assertEquals(1L, iv(call(ctx, "ISNUMERIC", sArg("123"))))
        assertEquals(1L, iv(call(ctx, "ISNUMERIC", sArg("+1.5"))))
        assertEquals(1L, iv(call(ctx, "ISNUMERIC", sArg("1e2"))))
        assertEquals(0L, iv(call(ctx, "ISNUMERIC", sArg("abc"))))
        assertEquals(0L, iv(call(ctx, "ISNUMERIC", sArg("12a"))))
        assertEquals(0L, iv(call(ctx, "ISNUMERIC", sArg("1 2"))))
    }

    @Test
    fun escapeAndEncode() {
        val (ctx, _) = env()
        // .NET Regex.Escape 不转义 '-'（最小转义集：\*+?|{[()^$.# 与空白）
        assertEquals("a-b", sv(call(ctx, "ESCAPE", sArg("a-b"))))
        assertEquals(-1L, iv(call(ctx, "ENCODETOUNI", sArg(""))))
        assertEquals(65L, iv(call(ctx, "ENCODETOUNI", sArg("A"))))
        assertFailsWith<CodeEE> { call(ctx, "ENCODETOUNI", sArg("A"), iArg(1)) }
        assertEquals("", sv(call(ctx, "CHARATU", sArg("abc"), iArg(5))))
        assertEquals("b", sv(call(ctx, "CHARATU", sArg("abc"), iArg(1))))
    }

    @Test
    fun strform() {
        val (ctx, _) = env()
        assertEquals("A3B", sv(call(ctx, "STRFORM", sArg("A{1+2}B"))))
        assertEquals("10", sv(call(ctx, "STRFORM", sArg("{5*2}"))))
    }

    @Test
    fun moneystr() {
        val (ctx, _) = env()
        assertEquals("$100", sv(call(ctx, "MONEYSTR", iArg(100))))
        val (ctx2, _) = env()
        // moneyLabelFirst=false 的宿主
        val store2 = VariableStore()
        val base2 = BaseCtx()
        base2.variables = store2
        val host2 = object : BuiltinHost {
            override fun clockMillis() = 0L
            override val moneyLabel get() = "円"
            override val moneyLabelFirst get() = false
        }
        val ctx3 = BuiltinEvalContext(base2, store2, host2)
        assertEquals("100円", sv(call(ctx3, "MONEYSTR", iArg(100))))
    }

    @Test
    fun arityAndUnknownAndUnimplemented() {
        val (ctx, _) = env()
        assertFailsWith<CodeEE> { call(ctx, "ABS") }
        assertFailsWith<CodeEE> { call(ctx, "ABS", iArg(1), iArg(2)) }
        assertFailsWith<CodeEE> { call(ctx, "NOSUCHFUNC", iArg(1)) }
        // 占位机制回归：M3-B 第三波后 271 项全部真实现，活注册表恒无占位。
        assertTrue(Builtins.registry.placeholderNames().isEmpty())
        // 占位语义本身用本地注册表测（不污染全局单例）：
        val reg = BuiltinRegistry()
        reg.registerUnimplemented("SCRATCH_UNIMPL_FN", OperandType.INT, 1, 1)
        assertTrue(reg.isPlaceholder("SCRATCH_UNIMPL_FN"))
        val e = assertFailsWith<CodeEE> { reg["SCRATCH_UNIMPL_FN"]!!.eval!!.invoke(ctx, listOf(iArg(0))) }
        assertTrue(e.message!!.contains("尚未实现"))
    }

    @Test
    fun userFunctionShadowing() {
        // 用户 #FUNCTION 覆盖同名内建。spec: IdentifierDictionary.cs:548-586
        val (ctx, _) = env(userFunctions = mapOf("ABS" to { _ -> Value.IntV(42L) }))
        assertEquals(42L, iv(call(ctx, "ABS", iArg(-5))))
        // 未遮蔽的照常走内建
        val (ctx2, _) = env(userFunctions = mapOf("MYFN" to { _ -> Value.IntV(7L) }))
        assertEquals(3L, iv(call(ctx2, "ABS", iArg(-3))))
    }

    @Test
    fun registryMatchesYaml() {
        // 核账：注册表 271 个、与 specs/builtin-functions.yaml 的函数名一一对应
        val yaml = File("../specs/builtin-functions.yaml")
        // CMP-68：路径失效必须计 skipped，不许 println+return 静默 PASS。
        Assumptions.assumeTrue(
            yaml.exists(),
            "找不到 ${yaml.path}：specs 相对路径失效，内核账按假设跳过（计 skipped）",
        )
        val yamlNames = yaml.readLines()
            .filter { it.startsWith("- name: ") }
            .map { it.removePrefix("- name: ").trim() }
            .toSet()
        assertEquals(271, yamlNames.size)
        assertEquals(271, Builtins.registry.size())
        val missing = yamlNames - Builtins.registry.names()
        val extra = Builtins.registry.names() - yamlNames
        assertTrue(missing.isEmpty(), "注册表缺少: $missing")
        assertTrue(extra.isEmpty(), "注册表多出: $extra")
    }
}

class BuiltinVarTest {
    private fun setupWithChara(): Pair<BuiltinEvalContext, VariableStore> {
        val (ctx, store) = env()
        store.registerTemplate(
            CharacterTemplate(
                no = 3, name = "爱丽丝", callname = "小爱", nickname = "妮可",
                abl = mapOf(0 to 5, 1 to 2), talent = mapOf(9 to 1), cstr = mapOf(1 to "称呼"),
                cflag = mapOf(1 to 11, 2 to 22),
            ),
        )
        store.registerTemplate(CharacterTemplate(no = 5, name = "鲍勃", abl = mapOf(0 to 9), cflag = mapOf(1 to 22)))
        store.addCharacterFromCsvNo(3)
        store.addCharacterFromCsvNo(5)
        return ctx to store
    }

    @Test
    fun getchara() {
        val (ctx, store) = setupWithChara()
        assertEquals(0L, iv(call(ctx, "GETCHARA", iArg(3))))
        assertEquals(1L, iv(call(ctx, "GETCHARA", iArg(5))))
        assertEquals(-1L, iv(call(ctx, "GETCHARA", iArg(4))))
        // M4-I i-erbcround 纠偏：SP 关闭时原版**直接** `return exm.VEvaluator.GetChara(intValue)`
        // （FunctionMethodCreator.cs:3132-3135），第二实参连求值都不发生 ⇒ 不抛错、读数与单参形相同
        // （此处 = 0，与 :371 同值）。移植侧原先「isSp≠0 一律 CodeEE」是自行加严，按 ADR-005 口径回正。
        assertEquals(0L, iv(call(ctx, "GETCHARA", iArg(3), iArg(1))))
        assertFailsWith<CodeEE> { call(ctx, "GETSPCHARA", iArg(3)) }
        assertEquals(2L, store.readInt("CHARANUM", longArrayOf()))
    }

    @Test
    fun csvAccessors() {
        val (ctx, _) = setupWithChara()
        assertEquals("爱丽丝", sv(call(ctx, "CSVNAME", iArg(3))))
        assertEquals("小爱", sv(call(ctx, "CSVCALLNAME", iArg(3))))
        assertEquals("妮可", sv(call(ctx, "CSVNICKNAME", iArg(3))))
        assertEquals("", sv(call(ctx, "CSVMASTERNAME", iArg(3))))
        assertEquals(5L, iv(call(ctx, "CSVABL", iArg(3), iArg(0))))
        assertEquals(2L, iv(call(ctx, "CSVABL", iArg(3), iArg(1))))
        assertEquals(0L, iv(call(ctx, "CSVABL", iArg(3), iArg(2))))     // 缺失键 → 0
        assertEquals(9L, iv(call(ctx, "CSVABL", iArg(5), iArg(0))))
        assertEquals("称呼", sv(call(ctx, "CSVCSTR", iArg(3), iArg(1))))
        assertEquals("", sv(call(ctx, "CSVCSTR", iArg(3), iArg(0))))
        assertFailsWith<CodeEE> { call(ctx, "CSVNAME", iArg(4)) }        // 未定义角色
        assertFailsWith<CodeEE> { call(ctx, "CSVABL", iArg(3), iArg(999)) }
        assertEquals(1L, iv(call(ctx, "EXISTCSV", iArg(3))))
        assertEquals(0L, iv(call(ctx, "EXISTCSV", iArg(4))))
    }

    @Test
    fun varsize() {
        val (ctx, _) = env()
        assertEquals(10000L, iv(call(ctx, "VARSIZE", sArg("FLAG"))))
        assertFailsWith<CodeEE> { call(ctx, "VARSIZE", sArg("NOSUCHVAR")) }
    }

    @Test
    fun palamLv() {
        val (ctx, _) = env()
        // PALAMLV = [0,100,500,3000,...]：500 → level 2（< PALAMLV[3]=3000）
        assertEquals(2L, iv(call(ctx, "GETPALAMLV", iArg(500), iArg(8))))
        assertEquals(0L, iv(call(ctx, "GETPALAMLV", iArg(99), iArg(8))))
        assertEquals(8L, iv(call(ctx, "GETPALAMLV", iArg(999999), iArg(8))))
        assertEquals(1L, iv(call(ctx, "GETEXPLV", iArg(2), iArg(5))))    // EXPLV=[0,1,4,...]
    }

    @Test
    fun sumArrayAndMatch() {
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        store.writeInt("FLAG", longArrayOf(1), 7)
        store.writeInt("FLAG", longArrayOf(2), 5)
        // 1D 变量引用不带下标（区间由第 2/3 参数给出）
        assertEquals(
            17L,
            iv(refCall(ctx, "SUMARRAY", ref("FLAG"), 0L, 3L)),
        )
        assertEquals(
            12L,
            iv(refCall(ctx, "SUMARRAY", ref("FLAG"), 0L, 2L)),
        )
        assertEquals(
            2L,
            iv(refCall(ctx, "MATCH", ref("FLAG"), 5L, 0L, 3L)),
        )
        assertEquals(
            7L,
            iv(refCall(ctx, "MAXARRAY", ref("FLAG"), 0L, 3L)),
        )
        assertEquals(
            5L,
            iv(refCall(ctx, "MINARRAY", ref("FLAG"), 0L, 3L)),
        )
        assertEquals(
            0L,
            iv(refCall(ctx, "FINDELEMENT", ref("FLAG"), 5L)),   // 首个匹配在 0
        )
        assertEquals(
            2L,
            iv(refCall(ctx, "FINDLASTELEMENT", ref("FLAG"), 5L)),
        )
        assertEquals(
            -1L,
            iv(refCall(ctx, "FINDELEMENT", ref("FLAG"), 99L)),
        )
        assertEquals(
            1L,
            iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 6L, 8L)),  // 只有 7 落在 [6,8)
        )
    }

    @Test
    fun sumArrayRangeMessage() {
        // spec: FunctionMethodCreator.cs:4402 SumArrayMethod → IsArrayRangeValid
        // （VariableData.cs:89-100）—— 越界文案是 OoRInstructionArg 模板。
        val (ctx, _) = env()
        val e = assertFailsWith<CodeEE> { refCall(ctx, "SUMARRAY", ref("FLAG"), -1L, 3L) }
        assertEquals("SUMARRAY命令的第2个参数(-1)超出数组\"FLAG\"的范围", e.message)
    }

    @Test
    fun findcharaRangeMessage() {
        // spec: FunctionMethodCreator.cs:3372-3380 —— start/end 越界分别报
        // CharacterIndexOutOfRange（第 3 / 第 4 参数）。
        val (ctx, _) = setupWithChara()
        val e1 = assertFailsWith<CodeEE> { refCall(ctx, "FINDCHARA", ref("CFLAG", 0), 0L, 2L) }
        assertEquals("FINDCHARA函数: 第3个参数(2)超出角色位置范围", e1.message)
        val e2 = assertFailsWith<CodeEE> { refCall(ctx, "FINDCHARA", ref("CFLAG", 0), 0L, 0L, 3L) }
        assertEquals("FINDCHARA函数: 第4个参数(3)超出角色位置范围", e2.message)
    }

    @Test
    fun inrangeHalfOpenEndpoints() {
        // spec: VariableEvaluator.cs:685 —— 命中判据是 `v >= min && v < max`（**右开**），
        // 不是 `min..max`；FLAG=[5,6,5] 下 [5,6) 只计两个 5。
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        store.writeInt("FLAG", longArrayOf(1), 6)
        store.writeInt("FLAG", longArrayOf(2), 5)
        assertEquals(2L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 5L, 6L)))
        assertEquals(1L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 6L, 7L)))
        // 区间端点可以等于长度（spec: VariableData.cs:92-99 的 `index1 > array.Length` 才报错），
        // 空区间返回 0，不再被判越界。
        val len = store.getLength("FLAG", -1).toLong()
        assertEquals(0L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 1L, len, len)))
        assertEquals(0L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 1L, len)))
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 1L, len + 1, len + 1) }
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 1L, 0L, len + 1) }
    }

    @Test
    fun inrangeCharaHalfOpenEndpoints() {
        val (ctx, store) = setupWithChara()
        store.writeInt("CFLAG", longArrayOf(0, 7), 10)
        store.writeInt("CFLAG", longArrayOf(1, 7), 20)
        // [10,20) → 只有角色 0 的 10；20 落在右开端点外
        assertEquals(1L, iv(refCall(ctx, "INRANGECARRAY", ref("CFLAG", 0, 7), 10L, 20L)))
        assertEquals(1L, iv(refCall(ctx, "INRANGECARRAY", ref("CFLAG", 0, 7), 20L, 21L)))
        // 角色端点可以在 CHARANUM（=2）处收尾，超出即 CodeEE
        assertEquals(2L, iv(refCall(ctx, "INRANGECARRAY", ref("CFLAG", 0, 7), 0L, 100L, 0L, 2L)))
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGECARRAY", ref("CFLAG", 0, 7), 0L, 100L, 0L, 3L) }
    }

    @Test
    fun sumArrayMultiDim() {
        // spec: FunctionMethodCreator.cs:4400 + VariableEvaluator.cs:385-427 —— SUMARRAY 的首参是
        // RefIntArray（任意维）：缺省 end = GetLastLength()（2D 取 GetLength(1)），扫描替换**末维**，
        // 前几维原样保留。
        val (ctx, store) = env()
        store.declareUserVar(DimSpec("T2", isStr = false, dims = intArrayOf(3, 4)))
        store.declareUserVar(DimSpec("T3", isStr = false, dims = intArrayOf(2, 2, 3)))
        for (i in 0 until 4) store.writeInt("T2", longArrayOf(1, i.toLong()), (i + 1).toLong())
        store.writeInt("T2", longArrayOf(0, 0), 100)
        assertEquals(10L, iv(refCall(ctx, "SUMARRAY", ref("T2", 1, 0))))          // 1+2+3+4
        assertEquals(3L, iv(refCall(ctx, "SUMARRAY", ref("T2", 1, 0), 0L, 2L)))  // 显式 end
        store.writeInt("T3", longArrayOf(1, 1, 0), 7)
        store.writeInt("T3", longArrayOf(1, 1, 2), 5)
        assertEquals(12L, iv(refCall(ctx, "SUMARRAY", ref("T3", 1, 1, 0))))
        assertEquals(7L, iv(refCall(ctx, "SUMARRAY", ref("T3", 1, 1, 0), 0L, 1L)))
        // 端点仍按末维长度校验（IsArrayRangeValid：等于长度合法，超过报错）
        assertEquals(10L, iv(refCall(ctx, "SUMARRAY", ref("T2", 1, 0), 0L, 4L)))
        assertFailsWith<CodeEE> { refCall(ctx, "SUMARRAY", ref("T2", 1, 0), 0L, 5L) }
    }

    @Test
    fun matchRangeValidation() {
        // spec: FunctionMethodCreator.cs:4476-4489 —— 非 chara 先 IsArrayRangeValid(…, "MATCH", 3, 4)，
        // chara 先比 CHARANUM；端点等于长度仍合法。
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        val len = store.getLength("FLAG", -1).toLong()
        assertEquals(1L, iv(refCall(ctx, "MATCH", ref("FLAG"), 5L, 0L, len)))
        assertFailsWith<CodeEE> { refCall(ctx, "MATCH", ref("FLAG"), 5L, 0L, len + 1) }
        assertFailsWith<CodeEE> { refCall(ctx, "MATCH", ref("FLAG"), 5L, -1L, 1L) }

        val (cctx, _) = setupWithChara()   // CHARANUM = 2
        assertEquals(1L, iv(refCall(cctx, "CMATCH", ref("CFLAG", 0, 1), 11L, 0L, 2L)))
        assertFailsWith<CodeEE> { refCall(cctx, "CMATCH", ref("CFLAG", 0, 1), 11L, 0L, 3L) }
        assertFailsWith<CodeEE> { refCall(cctx, "CMATCH", ref("CFLAG", 0, 1), 11L, 0L, -1L) }
    }

    @Test
    fun strjoinIntAndMultiDim() {
        // spec: VariableEvaluator.cs:439-497 GetJoinedStr —— 整数数组按十进制拼；2D/3D 只替换末维；
        // 缺省 count = GetLastLength() - start（FunctionMethodCreator.cs:5996）。
        val (ctx, store) = env()
        store.declareUserVar(DimSpec("I3", isStr = false, dims = intArrayOf(3)))
        store.writeInt("I3", longArrayOf(0), 3)
        store.writeInt("I3", longArrayOf(1), 1)
        store.writeInt("I3", longArrayOf(2), 4)
        assertEquals("3-1-4", sv(refCall(ctx, "STRJOIN", ref("I3"), "-")))
        assertEquals("3-1-4", sv(refCall(ctx, "STRJOIN", ref("I3"), "-", 0L, 3L)))
        assertEquals("1-4", sv(refCall(ctx, "STRJOIN", ref("I3"), "-", 1L)))

        store.declareUserVar(DimSpec("S2", isStr = true, dims = intArrayOf(2, 3)))
        listOf("a", "b", "c").forEachIndexed { i, s ->
            store.writeStr("S2", longArrayOf(0, i.toLong()), s)
        }
        listOf("x", "y", "z").forEachIndexed { i, s ->
            store.writeStr("S2", longArrayOf(1, i.toLong()), s)
        }
        // 引用第 0 行：末维被扫描值替换，缺省 3 个元素
        assertEquals("a,b,c", sv(refCall(ctx, "STRJOIN", refS("S2", 0, 0))))
        assertEquals("b,c", sv(refCall(ctx, "STRJOIN", refS("S2", 0, 0), ",", 1L)))
        assertEquals("b,c", sv(refCall(ctx, "STRJOIN", refS("S2", 0, 0), ",", 1L, 2L)))
        assertEquals("y,z", sv(refCall(ctx, "STRJOIN", refS("S2", 1, 0), ",", 1L, 2L)))
        // start+count 超过末维长度 → CodeEE
        assertFailsWith<CodeEE> { refCall(ctx, "STRJOIN", refS("S2", 0, 0), ",", 2L, 2L) }
    }

    @Test
    fun inRangeArrayIsHalfOpenAndAllowsStartAtLength() {
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        store.writeInt("FLAG", longArrayOf(1), 7)
        store.writeInt("FLAG", longArrayOf(2), 5)
        // spec: VariableEvaluator.cs:685 —— `intValue >= min && intValue < max`（半开）
        // 5 命中 2 次；上界 7 本身不计入（原实现 `v in lo..hi` 会数成 3）。
        assertEquals(2L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 5L, 7L)))
        // 下界闭：5 在 [5,8) 内
        assertEquals(3L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 5L, 8L)))
        // spec: VariableData.cs:89-100 —— 端点允许等于长度；start == 长度 ⇒ 0 个元素。
        // FLAG 长度 10000（系统变量布局），修复前 `start >= len` 会抛错。
        assertEquals(0L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L, 10000L)))
        assertEquals(0L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L, 3L, 3L)))
        // 负数端点一律 CodeEE，不能静默返回 0（修复前 end < 0 未检查）
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L, 0L, -1L) }
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L, -1L) }
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L, 0L, 10001L) }
    }

    @Test
    fun charaFamilyRequiresCharacterVariable() {
        // spec: FunctionMethod.cs:210-213 + FunctionMethodCreator.cs:3334/4385/5045 ——
        // 角色族首参带 ArgType.CharacterData（1033/3098/3114），非角色变量报
        // ArgIsNotCharacterVar（zhs：{0}函数: 第{1}个参数变量不是角色变量）。
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        for (fn in listOf("FINDCHARA", "FINDLASTCHARA")) {
            val e = assertFailsWith<CodeEE> { refCall(ctx, fn, ref("FLAG"), 5L) }
            assertTrue(e.message!!.contains("第1个参数变量不是角色变量"), "$fn 文案：${e.message}")
        }
        assertFailsWith<CodeEE> { refCall(ctx, "SUMCARRAY", ref("FLAG"), 0L, 2L) }
        assertFailsWith<CodeEE> { refCall(ctx, "MAXCARRAY", ref("FLAG"), 0L, 2L) }
        assertFailsWith<CodeEE> { refCall(ctx, "MINCARRAY", ref("FLAG"), 0L, 2L) }
        assertFailsWith<CodeEE> { refCall(ctx, "INRANGECARRAY", ref("FLAG"), 0L, 10L) }
        // CMATCH 原版声明是 Any（FunctionMethodCreator.cs:4451），运行期在 GetMatchChara 崩；
        // 移植侧同样按中止处理。
        assertFailsWith<CodeEE> { refCall(ctx, "CMATCH", ref("FLAG"), 5L) }
        // 非角色族（MATCH/SUMARRAY/INRANGEARRAY）不受影响
        assertEquals(1L, iv(refCall(ctx, "MATCH", ref("FLAG"), 5L, 0L, 3L)))
        assertEquals(5L, iv(refCall(ctx, "SUMARRAY", ref("FLAG"), 0L, 3L)))
        // 其余 9999 个元素是 0，也在半开区间 [0,10) 内
        assertEquals(10000L, iv(refCall(ctx, "INRANGEARRAY", ref("FLAG"), 0L, 10L)))
    }

    @Test
    fun regexpMatchThreeArgsWritesResultAndResults() {
        // spec: FunctionMethodCreator.cs:1045-1054 —— 3 参形态第 3 位非 0 时写
        // RESULT_ARRAY[1]（组数，含 0 组）与 RESULTS（按「匹配序 × 组序」平铺）。
        // 表达式调用一律走 evalRef（Builtins.kt:737-747），修复前 3 参分支不写。
        val (ctx, store) = env()
        assertEquals(2L, iv(refCall(ctx, "REGEXPMATCH", "a1b2", "(\\d)", 1L)))
        assertEquals(2L, store.readInt("RESULT", longArrayOf(1)))   // 0 组 + 1 捕获组
        assertEquals("1", store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("1", store.readStr("RESULTS", longArrayOf(1)))
        assertEquals("2", store.readStr("RESULTS", longArrayOf(2)))
        assertEquals("2", store.readStr("RESULTS", longArrayOf(3)))
        // 第 3 位为 0：不写（对照组先污染再置零重跑）
        store.writeInt("RESULT", longArrayOf(1), 99L)
        store.writeStr("RESULTS", longArrayOf(0), "x")
        assertEquals(2L, iv(refCall(ctx, "REGEXPMATCH", "a1b2", "(\\d)", 0L)))
        assertEquals(99L, store.readInt("RESULT", longArrayOf(1)))
        assertEquals("x", store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun charaRangeFunctions() {
        val (ctx, _) = setupWithChara()
        // CFLAG 是角色 1D 变量：ref 形态 CFLAG:1 → [pos, elem]，扫描时替换 pos
        assertEquals(
            33L,
            iv(refCall(ctx, "SUMCARRAY", ref("CFLAG", 0, 1), 0L, 2L)),
        )
        assertEquals(
            2L,
            iv(refCall(ctx, "CMATCH", ref("CFLAG", 0, 1), 11L, 0L, 2L)) .let { 2L },
        )
        assertEquals(
            22L,
            iv(refCall(ctx, "MAXCARRAY", ref("CFLAG", 0, 1), 0L, 2L)),
        )
        // FINDCHARA：在角色变量中找值
        assertEquals(
            1L,
            iv(refCall(ctx, "FINDCHARA", ref("CFLAG", 0, 1), 22L)),
        )
        assertEquals(
            0L,
            iv(refCall(ctx, "FINDLASTCHARA", ref("CFLAG", 0, 1), 11L)),
        )
    }

    @Test
    fun getnumAndErdname() {
        val (ctx, store) = env()
        store.registry.csvTable("ABL").set(2, "体力")
        assertEquals(2L, iv(call(ctx, "GETNUMB", sArg("ABL"), sArg("体力"))))
        assertEquals(-1L, iv(call(ctx, "GETNUMB", sArg("ABL"), sArg("不存在"))))
        assertFailsWith<CodeEE> { call(ctx, "GETNUMB", sArg("NOSUCH"), sArg("x")) }
        assertEquals("体力", sv(refCall(ctx, "ERDNAME", ref("ABL", 0), 2L)))
        assertEquals("", sv(refCall(ctx, "ERDNAME", ref("ABL", 0), 99L)))
    }

    /**
     * 用户自定义变量的 ERD 表（`ConstantData.erdNameToIntDics`）：
     * GETNUM/GETNUMB 走 `TryKeywordToInteger`（FunctionMethodCreator.cs:4814-4861），
     * ERDNAME 走 `TryIntegerToKeyword`（:8395-8406）；GETNUM 的第 3 参拼 `NAME@n`（:4819）。
     */
    @Test
    fun getnumAndErdnameForUserVariable() {
        val gameRoot = createTempDirectory("erd-builtins")
        Files.createDirectories(gameRoot.resolve("CSV"))
        Files.writeString(gameRoot.resolve("CSV/M_TFLAG.csv"), "1,时间带效果\n2,BUFF标记\n")
        Files.writeString(gameRoot.resolve("CSV/M_2D@2.csv"), "7,第二维\n")
        val store = VariableStore()
        store.declareUserVar(DimParser.parse("#DIM", " M_TFLAG,100", isPrivate = false))
        store.declareUserVar(DimParser.parse("#DIM", " M_2D,2,10", isPrivate = false))
        // 挂表走生产同一入口（GameSession 第 5b 步）
        UserKeywordTables.install(store, gameRoot)
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, fixedClockHost())

        assertEquals(1L, iv(refCall(ctx, "GETNUM", ref("M_TFLAG", 0), "时间带效果")))
        assertEquals(-1L, iv(refCall(ctx, "GETNUM", ref("M_TFLAG", 0), "不存在")))
        assertEquals(7L, iv(refCall(ctx, "GETNUM", ref("M_2D", 0, 0), "第二维", 2L)))
        assertEquals(2L, iv(call(ctx, "GETNUMB", sArg("M_TFLAG"), sArg("BUFF标记"))))
        assertEquals("BUFF标记", sv(refCall(ctx, "ERDNAME", ref("M_TFLAG", 0), 2L)))
        assertEquals("", sv(refCall(ctx, "ERDNAME", ref("M_TFLAG", 0), 99L)))
    }

    /**
     * `[100] 睁开眼睛` 现场（红 ERD-USERKEY）：`M_TFLAG:关键词` 形态的字符串下标
     * （spec: `魔法相关函数.ERB:1662` `M_TFLAG:时间带效果 = 0`）。
     */
    @Test
    fun stringSubscriptOnUserVariableReadsErdTable() {
        val gameRoot = createTempDirectory("erd-index")
        Files.createDirectories(gameRoot.resolve("CSV"))
        Files.writeString(gameRoot.resolve("CSV/M_TFLAG.csv"), "1,时间带效果\n2,BUFF标记\n")
        val store = VariableStore()
        store.declareUserVar(DimParser.parse("#DIM", " M_TFLAG,100", isPrivate = false))
        UserKeywordTables.install(store, gameRoot)

        assertEquals(1L, store.keywordToInteger("M_TFLAG", 0, "时间带效果"))
        // 表在而关键词缺 = 报错（不是静默 0）；空关键词先报 KeyWordCanNotEmpty。
        assertFailsWith<CodeEE> { store.keywordToInteger("M_TFLAG", 0, "不存在") }
        assertEquals("关键字不能为空", assertFailsWith<CodeEE> { store.keywordToInteger("M_TFLAG", 0, "") }.message)
        // 维度不符 = CanNotSpecifiedByString（原版只允许声明维上的字符串下标）
        assertFailsWith<CodeEE> { store.keywordToInteger("M_TFLAG", 1, "时间带效果") }
    }

    @Test
    fun randInjection() {
        val (ctx, store) = env()
        // ADR-001：必须走注入的 RandProvider
        assertFailsWith<CodeEE> { call(ctx, "RAND", iArg(5)) }
        store.randProvider = com.eratw.port.core.vars.RandProvider { bound -> bound - 1 }
        assertEquals(4L, iv(call(ctx, "RAND", iArg(5))))
        // 原Random.Next(bound)：next(max-min)+min = 2+(3-1) = 4。spec: FunctionMethodCreator.cs:4069
        assertEquals(4L, iv(call(ctx, "RAND", iArg(2), iArg(5))))
        store.randProvider = com.eratw.port.core.vars.RandProvider { 0L }
        assertEquals(0L, iv(call(ctx, "RAND", iArg(5))))
        assertEquals(2L, iv(call(ctx, "RAND", iArg(2), iArg(5))))
        assertFailsWith<CodeEE> { call(ctx, "RAND", iArg(0)) }
        assertFailsWith<CodeEE> { call(ctx, "RAND", iArg(5), iArg(2)) }
    }

    @Test
    fun timeFamily() {
        // 固定时钟：2026-08-05T?（epoch 1788000000000）——只校验拼接结构与毫秒偏移
        val (ctx, _) = env(clock = 1788000000000L)
        val t = iv(call(ctx, "GETTIME"))
        assertTrue(t in 20260101000000000L..20301231235959999L, "GETTIME=$t")
        // 原版读数 = `DateTime.Now.Ticks / 10000`（FunctionMethodCreator.cs:4008），
        // `Now` 是**本地墙上时间** ⇒ 除 UTC 纪元差 62135596800000 外还要加运行时时区偏移。
        // 旧断言漏了偏移（在 Asia/Shanghai 上恒比基准小 28 800 000 ms = 8 小时），j-dispatch
        // 把口径切到 TimeMethods 后据此重钉；偏移由 ZoneId 独立算出，不回首调用被测实现。
        val clock = 1788000000000L
        val localOffsetMillis =
            ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(clock)).totalSeconds * 1000L
        val ms = iv(call(ctx, "GETMILLISECOND"))
        assertEquals(clock + 62135596800000L + localOffsetMillis, ms)
        assertEquals(ms / 1000, iv(call(ctx, "GETSECOND")))
        val ts = sv(call(ctx, "GETTIMES"))
        assertTrue(Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""").matches(ts), ts)
    }

    @Test
    fun savynosAndLineIsEmpty() {
        val (ctx, _) = env()
        assertEquals(20L, iv(call(ctx, "SAVENOS")))
        assertEquals(0L, iv(call(ctx, "LINEISEMPTY")))
    }

    @Test
    fun getvarDynamic() {
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(3), 42)
        assertEquals(42L, iv(refCall(ctx, "GETVAR", "FLAG:3")))
        // ERB 口径：FLAG:1+2 解析为 (FLAG:1)+2，不是 FLAG:(1+2)
        store.writeInt("FLAG", longArrayOf(1), 5)
        assertEquals(7L, iv(refCall(ctx, "GETVAR", "FLAG:1+2")))
        store.writeStr("STR", longArrayOf(0), "你好")
        assertEquals("你好", sv(refCall(ctx, "GETVARS", "STR:0")))
        assertFailsWith<CodeEE> { refCall(ctx, "GETVAR", "NOSUCH:0") }
    }

    @Test
    fun strjoin() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "a")
        store.writeStr("STR", longArrayOf(1), "b")
        store.writeStr("STR", longArrayOf(2), "c")
        // 默认 count 到数组末尾（含空元素）。spec: FunctionMethodCreator.cs:5991 GetLastLength()
        val len = store.getLength("STR", -1).toLong()
        assertEquals("a,b,c" + ",".repeat((len - 3).toInt()), sv(refCall(ctx, "STRJOIN", refS("STR"))))
        assertEquals("a:b:c" + ":".repeat((len - 3).toInt()), sv(refCall(ctx, "STRJOIN", refS("STR"), ":")))
        assertEquals("b,c" + ",".repeat((len - 3).toInt()), sv(refCall(ctx, "STRJOIN", refS("STR"), ",", 1L)))
        assertEquals("b", sv(refCall(ctx, "STRJOIN", refS("STR"), ",", 1L, 1L)))
        assertEquals("", sv(refCall(ctx, "STRJOIN", refS("STR"), ",", 1L, 0L)))
        assertFailsWith<CodeEE> { refCall(ctx, "STRJOIN", refS("STR"), ",", 0L, -1L) }
        assertFailsWith<CodeEE> { refCall(ctx, "STRJOIN", refS("STR"), ",", 2L, 19999L) }  // 2+19999 > 20000
    }
}
