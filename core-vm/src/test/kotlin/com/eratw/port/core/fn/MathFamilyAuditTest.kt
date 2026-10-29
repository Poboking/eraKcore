package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 数学族逐条对拍（权威 = `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）。
 *
 * ## 对拍表（C# 行号 → 本仓实现 → 结论）
 *
 * | C# 类体 | 函数 | 本仓实现 | 结论 |
 * |---|---|---|---|
 * | 4075-4137 MaxMethod（`new MaxMethod(max:false)` = MIN） | MIN / MAX | `PureMethods.kt:725-726` | 一致 |
 * | 4139-4157 AbsMethod | ABS | `PureMethods.kt:728-732` | 一致（文案族级差异） |
 * | 4159-4191 PowerMethod | POWER | `PureMethods.kt:734-742` | 一致（判序同构） |
 * | 4193-4211 SqrtMethod | SQRT | `PureMethods.kt:744-748` | 一致 |
 * | 4213-4231 CbrtMethod | CBRT | `PureMethods.kt:750-754` | 一致（截断读数实测同值） |
 * | 4233-4276 LogMethod（`LOG10` = `new LogMethod(10.0)`） | LOG / LOG10 | `PureMethods.kt:756-765` | 一致 |
 * | 4278-4304 ExpMethod | EXPONENT | `PureMethods.kt:767-774` | 一致 |
 * | 4306-4319 SignMethod | SIGN | `PureMethods.kt:776-779` | 一致 |
 * | 4321-4350 GetLimitMethod | LIMIT | `PureMethods.kt:781-784` | 一致（含 lo>hi） |
 * | 4766-4790 GetbitMethod | GETBIT | `PureMethods.kt:786-790` | 一致 |
 * | 4984-5010 InRangeMethod | INRANGE | `PureMethods.kt:807-810` | 一致 |
 * | 4509-4556 GroupMatchMethod | GROUPMATCH | `PureMethods.kt:792-795` | **分歧 G**（STR 首参）· 归 M6-BOOT-GROUPMATCH |
 * | 4557-4605 NosamesMethod | NOSAMES | `PureMethods.kt:796-800` | **分歧 G**（STR 首参）· 同上 |
 * | 4606-4652 AllsamesMethod | ALLSAMES | `PureMethods.kt:802-805` | **分歧 G**（STR 首参）· 同上 |
 *
 * ## 分歧 G 不在本文件钉现状
 *
 * 三者的参数声明是 `ArgType.Any` + `VariadicSameAsFirst`（:4568-4569、:4617-4618、:4514-4515）：
 * 首参为字符串时原版走 `GetStrValue` 逐项比较（:4592-4600、:4640-4647），本仓一律 `iv()` 取整数。
 * 这条归 BOARD「M6-BOOT-GROUPMATCH」在办（同一段行 792-805），本路只登记、不写会与其冲突的断言。
 *
 * ## 双精度读数为什么可以钉
 *
 * `POWER`/`SQRT`/`CBRT`/`LOG` 两侧都是 `Math.Pow` / `Math.Sqrt` / `Math.Log` 后 `(long)` 截断
 * （:4189、:4209、:4229、:4274），截断方向同为「向零」。下面钉的期望值来自本机 dotnet 8.0.421
 * net8.0 探针实测（与 `Emuera.csproj` 的 `netcoreapp8.0` 同代）：CBRT(27)=3、CBRT(64)=3、
 * CBRT(125)=4、CBRT(1000)=9、CBRT(2)=1 —— 注意 64/125/1000 都不是数学上的整根，
 * 这是 `1.0/3.0` 的浮点误差被截断后的原版读数。
 * TODO(verify)：两侧 pow 的末位（ULP）实现不同源，极端入参仍可能差 1；本表只钉实测过的样本。
 */
private class MaBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MaBase 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun maCtx(): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {})
}

private fun maI(ctx: BuiltinEvalContext, name: String, vararg a: Long): Long =
    (ctx.callFunction(name, a.map { Value.IntV(it) }) as Value.IntV).v

class MathFamilyAuditTest {

    @Test
    fun `MIN MAX 可变参数与单参`() {
        val c = maCtx()
        // spec: :4119-4135 —— 首参起逐项比较，isMax 由构造参数定边
        assertEquals(1L, maI(c, "MIN", 3, 1, 2))
        assertEquals(3L, maI(c, "MAX", 3, 1, 2))
        assertEquals(7L, maI(c, "MIN", 7))
        assertEquals(7L, maI(c, "MAX", 7))
        assertEquals(Long.MIN_VALUE, maI(c, "MIN", 0, Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, maI(c, "MAX", 0, Long.MIN_VALUE, Long.MAX_VALUE))
        // 负值：MIN 走 `num > intValue` 分支，不受符号影响
        assertEquals(-9L, maI(c, "MIN", -1, -9, 5))
        assertEquals(5L, maI(c, "MAX", -1, -9, 5))
    }

    @Test
    fun `ABS 与 Int64 最小值`() {
        val c = maCtx()
        assertEquals(5L, maI(c, "ABS", -5))
        assertEquals(5L, maI(c, "ABS", 5))
        assertEquals(0L, maI(c, "ABS", 0))
        // spec: :4151-4154 —— long.MinValue 无正值可表示，抛 CodeEE
        val e = assertFailsWith<CodeEE> { maI(c, "ABS", Long.MIN_VALUE) }
        // 文案分歧（族级）：原版 Lang.Error.MinInt64CanNotApplyABS
        // = "{0}函数: 无法对带符号64位整数的最小值({1})取绝对值"（lang.emuera-zhs.xml:941）
        assertTrue(e.message!!.contains("ABS"), e.message!!)
    }

    @Test
    fun `POWER 的判序 溢出与死区`() {
        val c = maCtx()
        // spec: :4176-4189 —— NaN → Infinity → |x|>=9.223372036854776E18 → (long)
        assertEquals(1024L, maI(c, "POWER", 2, 10))
        assertEquals(1594323L, maI(c, "POWER", 3, 13))
        assertEquals(1L, maI(c, "POWER", 7, 0))
        assertEquals(1L, maI(c, "POWER", -8, 0))
        assertFailsWith<CodeEE> { maI(c, "POWER", 2, 63) }        // 9.223372036854776E18 命中上界
        assertFailsWith<CodeEE> { maI(c, "POWER", -2, 63) }
        assertFailsWith<CodeEE> { maI(c, "POWER", 0, -1) }        // Infinity 分支（:4181-4184）
        assertEquals(-1L, maI(c, "POWER", -1, 1))                 // 奇数负底：合法
        assertEquals(-8L, maI(c, "POWER", -2, 3))
    }

    @Test
    fun `NOSAMES ALLSAMES 的整数口径`() {
        val c = maCtx()
        // spec: :4585-4601 —— Distinct().Count() != Count ⇒ 0
        assertEquals(0L, maI(c, "NOSAMES", 1, 2, 2))
        assertEquals(1L, maI(c, "NOSAMES", 1, 2, 3))
        // spec: FunctionMethod.cs:183/283 —— 尾部 Variadic ⇒ flag3 要求 count>=2（单参 NotEnoughArgs）
        assertFailsWith<CodeEE> { maI(c, "NOSAMES", 5) }
        // spec: :4629-4636 —— 首参起全部相等 ⇒ 1
        assertEquals(1L, maI(c, "ALLSAMES", 2, 2, 2))
        assertEquals(0L, maI(c, "ALLSAMES", 2, 2, 3))
        assertFailsWith<CodeEE> { maI(c, "ALLSAMES", 7) }
        // 整数路径与字符串分支（分歧 G，:4592-4600 / :4640-4647）无关：后者归 M6-BOOT-GROUPMATCH
    }

    @Test
    fun `SQRT 与 CBRT 的截断读数`() {
        val c = maCtx()
        assertEquals(1L, maI(c, "SQRT", 2))
        assertEquals(1000L, maI(c, "SQRT", 1000000))
        assertEquals(0L, maI(c, "SQRT", 0))
        // spec: :4229 —— `(long)Math.Pow(v, 1.0/3.0)`：64/125/1000 都被截断少 1
        assertEquals(3L, maI(c, "CBRT", 27))
        assertEquals(3L, maI(c, "CBRT", 64))
        assertEquals(4L, maI(c, "CBRT", 125))
        assertEquals(9L, maI(c, "CBRT", 1000))
        assertEquals(1L, maI(c, "CBRT", 2))
        assertEquals(2L, maI(c, "CBRT", 8))
        // 负数在 :4205-4208 / :4225-4228 直接抛（不是 NaN 分支）
        assertFailsWith<CodeEE> { maI(c, "SQRT", -1) }
        assertFailsWith<CodeEE> { maI(c, "CBRT", -1) }
    }

    @Test
    fun `LOG LOG10 与 EXPONENT`() {
        val c = maCtx()
        assertEquals(2L, maI(c, "LOG", 10))       // 自然对数，:4261
        assertEquals(0L, maI(c, "LOG", 1))
        assertEquals(3L, maI(c, "LOG10", 1000))
        assertEquals(0L, maI(c, "LOG10", 1))
        assertEquals(2L, maI(c, "EXPONENT", 1))
        assertEquals(1L, maI(c, "EXPONENT", 0))
        // :4253-4259 / :4289-4301
        assertFailsWith<CodeEE> { maI(c, "LOG", 0) }
        assertFailsWith<CodeEE> { maI(c, "LOG10", -1) }
        assertFailsWith<CodeEE> { maI(c, "EXPONENT", 50) }   // 5.18E21 越界（`Infinity` 分支在 710 附近才触发）
    }

    @Test
    fun `SIGN LIMIT GETBIT INRANGE`() {
        val c = maCtx()
        assertEquals(-1L, maI(c, "SIGN", -7))
        assertEquals(0L, maI(c, "SIGN", 0))
        assertEquals(1L, maI(c, "SIGN", 7))
        assertEquals(-1L, maI(c, "SIGN", Long.MIN_VALUE))    // Math.Sign 两侧同
        // LIMIT：先比下界再比上界（:4340-4347），lo>hi 时返回 lo
        assertEquals(5L, maI(c, "LIMIT", 9, 1, 5))
        assertEquals(1L, maI(c, "LIMIT", 0, 1, 5))
        assertEquals(3L, maI(c, "LIMIT", 3, 1, 5))
        assertEquals(5L, maI(c, "LIMIT", 3, 5, 1))
        // GETBIT：算术右移 + &1（:4788），bit 域 0..63
        assertEquals(1L, maI(c, "GETBIT", 5, 0))
        assertEquals(0L, maI(c, "GETBIT", 5, 1))
        assertEquals(1L, maI(c, "GETBIT", 4, 2))
        assertEquals(1L, maI(c, "GETBIT", -1, 63))
        assertFailsWith<CodeEE> { maI(c, "GETBIT", 5, 64) }
        assertFailsWith<CodeEE> { maI(c, "GETBIT", 5, -1) }
        // INRANGE：闭区间（:4999-5006）
        assertEquals(1L, maI(c, "INRANGE", 3, 1, 5))
        assertEquals(1L, maI(c, "INRANGE", 1, 1, 5))
        assertEquals(1L, maI(c, "INRANGE", 5, 1, 5))
        assertEquals(0L, maI(c, "INRANGE", 0, 1, 5))
        assertEquals(0L, maI(c, "INRANGE", 6, 1, 5))
    }
}
