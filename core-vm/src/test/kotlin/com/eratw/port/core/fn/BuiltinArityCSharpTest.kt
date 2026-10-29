package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CMP-70 规格双表闸发现的 4 条「注册表 vs specs/C# 实参表」分歧的**裁决钉子**（N8，2026-10-24）。
 *
 * 裁决口径：三条表里 **C# 实参表是权威**（`specs/builtin-functions.yaml` 的 `args` 与它一致，
 * 错的都是 `Builtins` 注册值）。每条都写 C# 行号：
 *
 * - `XML_REPLACE_BYNAME`：byName 重载把基类三份 ArgTypeList 整体覆盖成**一份** 4 槽
 *   `String,String,String,Int` + `OmitStart = 3`（FunctionMethodCreator.cs:1787-1805，注册在 :9239）
 *   ⇒ 合法元数 **3..4**。2 参整文档替换只属于 `XML_REPLACE`（:1751-1783 第一份 `Any,String`）。
 * - `DT_ROW_SET`：Set 分支第一份 ArgTypeList 是 4 槽 `String,Int,VariadicString,VariadicAny` +
 *   `MatchVariadicGroup`，`OmitStart` 缺省 -1（FunctionMethodCreator.cs:2168-2199，注册在 :9267）
 *   ⇒ 下限 = 槽数 **4**、上限变长；第二份 5 槽数组形态单列。`OmitStart=-1` 时
 *   `FunctionMethod.cs:183/266` 用 `arguments.Count >= ArgTypes.Count` 判个数。
 * - `GSETFONT`：4 槽 `Int,String,Int,Int` + `OmitStart = 2`（:6331-6344，注册在 :9186）
 *   ⇒ **2..4**。2 参在原版**解析期合法**、运行期 `:6360` 无条件读 `arguments[2]` 抛
 *   `ArgumentOutOfRangeException`；移植侧没有崩溃通道，由 `intArg` 抛 CodeEE（GraphicsMethods.kt:1012-1014）。
 * - `ISPLAYINGSOUND`：1 槽 `Int` + `OmitStart = 0`（:8835-8842，注册在 :9309）⇒ **0..1**。
 *   `OmitStart` 是**最少实参个数**（`arguments.Count >= OmitStart`，FunctionMethod.cs:183），
 *   不是「恰好一个」：0 参在原版解析期合法、运行期 `:8848` 直接读 `arguments[0]` 越界；
 *   移植侧求值体改成显式 CodeEE（HostMethods.kt 的 ISPLAYINGSOUND 分支）。
 *
 * 移植侧个数闸 `BuiltinMethod.checkArity`（Builtins.kt）在**运行期**调用（`callFunction` /
 * `callFunctionRef`），所以「原版解析期报错」在移植侧落成「同一行、函数体未执行就 CodeEE」——
 * 相位不同、可观测结果都是该行脚本错误。语料影响：全量 ERB 里这 4 个名字的调用 0 处
 * （XML_REPLACE_BYNAME / DT_ROW_SET / ISPLAYINGSOUND 各 0，GSETFONT 6 处全是 3/4 参），
 * 所以收紧下界不会让任何现有脚本从能跑变不能跑。
 */
class BuiltinArityCSharpTest {

    private fun method(name: String): BuiltinMethod =
        requireNotNull(Builtins.registry[name]) { "$name 不在 Builtins 注册表" }

    private data class Expect(val name: String, val min: Int, val max: Int, val evidence: String)

    /** 4 条的期望元数；`max = -1` 表示变长。 */
    private val expected = listOf(
        Expect(
            "XML_REPLACE_BYNAME", 3, 4,
            "FunctionMethodCreator.cs:1787-1805（byName 一份 4 槽 String,String,String,Int + OmitStart=3）",
        ),
        Expect(
            "DT_ROW_SET", 4, -1,
            "FunctionMethodCreator.cs:2168-2199（Set 分支 4 槽变长 + MatchVariadicGroup，OmitStart 缺省 -1）",
        ),
        Expect(
            "GSETFONT", 2, 4,
            "FunctionMethodCreator.cs:6331-6344（4 槽 Int,String,Int,Int + OmitStart=2）",
        ),
        Expect(
            "ISPLAYINGSOUND", 0, 1,
            "FunctionMethodCreator.cs:8835-8842（1 槽 Int + OmitStart=0 ⇒ 0..1）",
        ),
    )

    @Test
    fun `四条注册表元数与 C# 实参表一致`() {
        val bad = expected.mapNotNull { e ->
            val m = method(e.name)
            if (m.minArgs == e.min && m.maxArgs == e.max) {
                null
            } else {
                "${e.name}: 注册 ${m.minArgs}..${m.maxArgs}，C# ${e.min}..${e.max}（${e.evidence}）"
            }
        }
        assertEquals(emptyList<String>(), bad, "注册表与 C# 实参表分歧（C# 是权威，specs 已与 C# 一致）：\n  ${bad.joinToString("\n  ")}")
    }

    @Test
    fun `原版实参表拒绝的短参形态在移植侧数目闸就抛 CodeEE`() {
        // XML_REPLACE_BYNAME 2 参：原版解析期 NotEnoughArgs（OmitStart=3）；移植侧运行期同位置 CodeEE。
        assertFailsWith<CodeEE>("XML_REPLACE_BYNAME 2 参必须被个数闸挡住") {
            method("XML_REPLACE_BYNAME").checkArity(2)
        }
        // DT_ROW_SET 2/3 参：原版解析期 ArgsCountNotMatches（下限 4）；移植侧运行期 CodeEE。
        for (n in 2..3) {
            assertFailsWith<CodeEE>("DT_ROW_SET $n 参必须被个数闸挡住") { method("DT_ROW_SET").checkArity(n) }
        }
        // 反证：合法下界不许被误挡（避免把上一条写成「一律拒绝」）。
        method("XML_REPLACE_BYNAME").checkArity(3)
        method("DT_ROW_SET").checkArity(4)
    }

    @Test
    fun `原版实参表放行的短参形态过闸后由函数体给 CodeEE 而不是崩溃`() {
        // GSETFONT 2 参：原版 :6360 的 arguments[2] 越界（ArgumentOutOfRangeException）；
        // 移植侧过个数闸后由 [GraphicsMethods] 的 intArg 抛 CodeEE（:1012-1014 的「缺少第 N 个参数」）。
        // 这条只在「图形缓冲已创建」分支可达 —— 直接调求值体会先读到未创建的缓冲并返回 0
        // （与 :6355-6358 同口径），所以这里只钉个数闸放行；函数体那半由 intArg 既有实现兜住。
        method("GSETFONT").checkArity(2)
        // ISPLAYINGSOUND 0 参：原版 :8848 的 arguments[0] 越界；移植侧求值体显式 CodeEE。
        method("ISPLAYINGSOUND").checkArity(0)
        assertFailsWith<CodeEE>("ISPLAYINGSOUND 0 参必须在求值期给出 CodeEE（原版此处 arguments[0] 越界）") {
            method("ISPLAYINGSOUND").eval!!(NoopContext(), emptyList())
        }
    }

    /** 只给这两个函数用到的最小上下文：两个分支都在碰宿主/存储之前就抛了。 */
    private class NoopContext : BuiltinContext {
        override val store = VariableStore()
        override val host: BuiltinHost = BuiltinHost.DEFAULT
        override val variables: VariableReader get() = store

        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("测试上下文不应被调用：$name")
    }
}
