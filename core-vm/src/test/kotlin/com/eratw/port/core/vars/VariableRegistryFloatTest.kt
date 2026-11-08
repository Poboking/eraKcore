package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [g9] `LOCALF` / `ARGF` / `RESULTF` 从存储层的**散装名字**收进 [VariableRegistry]。
 *
 * 改造前：三名的名字判定硬写在 `VariableStore.builtinFloatOf` 里（三个字符串比较），
 * 注册表查不到它们 ⇒ `VARSIZE("LOCALF")` 靠存储层特例兜、`#DIM LOCALF` 也不与系统变量比名。
 * 改造后：名 / code / 类型 / 维度 / VARSIZE 档位一次登记（[Storage.L_FLOAT]、
 * [Storage.G_FLOAT0]），存储层只按 `storage` 决定数据落点。
 *
 * ## 证据（fork 4.981 = vendor emuera-skia @48494165）
 * - LOCALF/ARGF 登记在 `localvarTokenDic`（**不在** varTokenDic）：`VariableData.cs:376-379`
 *   （与 LOCAL/ARG/LOCALS/ARGS 同一张表，见 `:367-374`）；
 * - RESULTF 登记在 `varTokenDic`：`VariableData.cs:182`，本体是 `dataFloat[1]`
 *   （列长 `__COUNT_STRING__` = 2：`VariableData.cs:155`；读写入口
 *   `VariableEvaluator.cs:3105-3108` 取 `RESULTF & __LOWERCASE__` = 1）；
 * - 三名的 Kind/Dimension/Attribute：`VariableDescriptor.cs:108`（RESULTF：Float/Scalar/Extended）、
 *   `:154-155`（LOCALF/ARGF：Float/Array1D/CanForbid|Local|Extended）；
 * - 非存档：三者 code 都带 `__EXTENDED__`（`VariableCode.cs:99-101,124`）⇒
 *   `VariableToken.cs:88-154` 的 IsSavedata 判否，移植侧由 [VariableCode.isSavedata] 同判。
 *
 * ## 1.824 对拍面
 * 1.824 没有任何浮点变量（`reference/emuera-cs` 全树 `grep Float` 零命中）⇒ 浮点侧
 * TODO(verify)；每个用例都带一条 1.824 侧的对照断言（LOCAL/ARG/LOCALS/ARGS/FLAG/RESULT…），
 * 钉住「系统表里既有的名字与判定一个都没变」。
 */
class VariableRegistryFloatTest {

    private val sizes = VariableSizes()
    private val registry = VariableRegistry(sizes)

    // ------------------------------------------------------------------
    // 登记内容
    // ------------------------------------------------------------------

    /**
     * LOCALF/ARGF：1 维浮点、每函数一份的形态（[Storage.L_FLOAT]）、默认档 10。
     * TODO(verify)：1.824 无此三名。
     */
    @Test
    fun `LOCALF与ARGF登记为浮点局部`() {
        for ((name, slot) in listOf("LOCALF" to 0, "ARGF" to 1)) {
            val d = assertNotNull(registry.def(name), "$name 必须在系统表里")
            assertEquals(VarType.FLOAT, d.type, "$name 的类型（fork VariableDescriptor.cs:154-155 Kind=Float）")
            assertEquals(Storage.L_FLOAT, d.storage, "$name 的形态：每函数一份的浮点局部")
            assertEquals(slot, d.index, "$name 的浮点表槽位（fork VariableCode.cs:99-100）")
            assertEquals(1, d.dimension, "$name 是 1 维")
            assertEquals(1, d.argCount, "1 维 ⇒ 恰好一个下标")
            assertEquals(10, d.length(0), "出厂档位 10（fork ConstantData.cs:211-217）")
            assertFalse(d.isConst, "$name 可写")
            assertFalse(d.isSavedata, "带 EXTENDED ⇒ 不入档（fork VariableToken.cs:88-154）")
            assertFalse(d.isCharacterData)
            assertFalse(d.isGlobal)
            assertFalse(d.isForbid, "出厂不是禁用形态")
        }
        // 1.824 对照：既有的四个局部名形态不变
        for (n in listOf("LOCAL", "ARG")) {
            val d = registry.mustDef(n)
            assertEquals(Storage.L_INT, d.storage, "$n 的既有形态不变")
            assertEquals(VarType.INT, d.type, "$n 仍是整数")
            assertEquals(1000, d.length(0), "$n 出厂 1000（1.824 ConstantData.cs:214）")
            assertFalse(d.isSavedata)
        }
        for (n in listOf("LOCALS", "ARGS")) {
            val d = registry.mustDef(n)
            assertEquals(Storage.L_STR, d.storage, "$n 的既有形态不变")
            assertEquals(VarType.STR, d.type, "$n 仍是字符串")
            assertEquals(100, d.length(0), "$n 出厂 100（1.824 ConstantData.cs:221-222）")
            assertFalse(d.isSavedata)
        }
    }

    /** RESULTF：0 维浮点、落在内建标量列第 1 格（[Storage.G_FLOAT0]）。spec: fork VariableData.cs:182,155 */
    @Test
    fun `RESULTF登记为零维浮点且槽位取code低十六位`() {
        val d = assertNotNull(registry.def("RESULTF"))
        assertEquals(VarType.FLOAT, d.type)
        assertEquals(Storage.G_FLOAT0, d.storage)
        assertEquals(1, d.index, "dataFloat[1]（fork VariableEvaluator.cs:3105-3108 的 RESULTF & __LOWERCASE__）")
        assertEquals(0, d.dimension, "Scalar（fork VariableDescriptor.cs:108）")
        assertEquals(0, d.argCount, "0 维 ⇒ 不带下标")
        assertFalse(d.isSavedata)
        assertFalse(d.isConst, "脚本可直写 RESULTF（fork FloatScalarVariableToken 有 SetValue）")
        // 1.824 对照：同族的 0 维字符串登记形态不变
        val s = registry.mustDef("SAVEDATA_TEXT")
        assertEquals(Storage.G_STR0, s.storage)
        assertEquals(0, s.dimension)
    }

    /** 三名互不遮蔽：注册表里各有一条，且 `def` 大小写不敏感（原版 Config.cs:33 OrdinalIgnoreCase）。 */
    @Test
    fun `浮点名在表里唯一且大小写不敏感`() {
        for (n in listOf("LOCALF", "ARGF", "RESULTF")) {
            assertEquals(registry.mustDef(n), registry.def(n.lowercase()), "$n 的大小写形态应命中同一条")
        }
        assertEquals(3, registry.all().count { it.type == VarType.FLOAT }, "系统表里恰好三条浮点")
        // 1.824 对照：既有名字不重不漏
        assertNotNull(registry.def("LOCAL"))
        assertNotNull(registry.def("RESULT"))
        assertNull(registry.def("LOCALFX"), "不存在的名字仍返回 null")
    }

    // ------------------------------------------------------------------
    // 对外读写行为
    // ------------------------------------------------------------------

    /**
     * 浮点通道的行为与改造前一致：`LOCALF`/`ARGF` 按函数一份、`RESULTF` 一格、
     * 函数外不可达、0 维不带下标。
     */
    @Test
    fun `浮点读写行为与改造前一致`() {
        val store = VariableStore(registry)
        store.writeFloat("RESULTF", longArrayOf(), 2.5)
        assertEquals(2.5, store.readFloat("RESULTF", longArrayOf()), "RESULTF 走标量列")
        assertFailsWith<CodeEE> { store.readFloat("RESULTF", longArrayOf(0)) }
        assertFailsWith<CodeEE> { store.getLength("RESULTF") }
        assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(0)) }
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(3), 1.25)
            assertEquals(1.25, store.readFloat("LOCALF", longArrayOf(3)))
            assertEquals(0.0, store.readFloat("ARGF", longArrayOf(3)), "未写过 ⇒ 0.0（fork VariableData.cs:668-669）")
        } finally {
            store.leaveFunction()
        }
    }

    /**
     * 整型/字符串通道对三名的报错：仍是 CodeEE，但文案要说清「是浮点变量」
     * （改造前这三名在系统表里查不到，报的是「未定义的变量」；登记后按类型报）。
     */
    @Test
    fun `整型与字符串通道拒绝浮点名`() {
        val store = VariableStore(registry)
        store.enterFunction("T")
        try {
            val e1 = assertFailsWith<CodeEE> { store.readInt("LOCALF", longArrayOf(0)) }
            assertTrue(e1.message.orEmpty().contains("是浮点变量"), "实得 ${e1.message}")
            assertTrue(e1.message.orEmpty().contains("不能当整数读取"), "实得 ${e1.message}")
            val e2 = assertFailsWith<CodeEE> { store.writeInt("ARGF", longArrayOf(0), 1L) }
            assertTrue(e2.message.orEmpty().contains("不能写入整数"), "实得 ${e2.message}")
            val e3 = assertFailsWith<CodeEE> { store.readStr("RESULTF", longArrayOf()) }
            assertTrue(e3.message.orEmpty().contains("是浮点变量"), "实得 ${e3.message}")
            val e4 = assertFailsWith<CodeEE> { store.writeStr("LOCALF", longArrayOf(0), "x") }
            assertTrue(e4.message.orEmpty().contains("不能写入字符串"), "实得 ${e4.message}")
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：既有的两型互斥文案逐字不变
        val s2 = VariableStore()
        val a = assertFailsWith<CodeEE> { s2.readInt("RESULTS", longArrayOf(0)) }
        assertTrue(a.message.orEmpty().contains("是字符串变量，不能当整数读取"), "实得 ${a.message}")
        val b = assertFailsWith<CodeEE> { s2.writeStr("RESULT", longArrayOf(0), "x") }
        assertTrue(b.message.orEmpty().contains("是整数变量，不能写入字符串"), "实得 ${b.message}")
    }

    /** 浮点名的解析期元信息：`resolveVarInfo` 现在给得出 FLOAT / 维数（改造前返回 null）。 */
    @Test
    fun `解析期元信息认得浮点名`() {
        val store = VariableStore(registry)
        store.enterFunction("T")
        try {
            val lf = store.resolveVarInfo("LOCALF")
            assertNotNull(lf)
            assertEquals(OperandType.FLOAT, lf.operandType)
            assertEquals(1, lf.dimension)
            val rf = store.resolveVarInfo("RESULTF")
            assertNotNull(rf)
            assertEquals(0, rf.dimension)
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：既有名字的解析期形态不变
        assertEquals(OperandType.INT, store.resolveVarInfo("RESULT")?.operandType)
        assertEquals(OperandType.STR, store.resolveVarInfo("RESULTS")?.operandType)
    }

    /**
     * 三名的**用户重声明**被拒（与系统变量重名）：原版 LOCALF/ARGF/RESULTF 都在
     * 名字登记表里（fork `VariableIdentifier.cs:190` 的 nameDic 收全部枚举名）⇒
     * `#DIM` 这类名字在装载期即不可用（fork `LogicalLineParser.cs:270-286` 的
     * `AddPrivateVariable` ⇒ `VarNameAlreadyUsed`）。
     */
    @Test
    fun `用户声明浮点名按系统变量重名拒绝`() {
        val store = VariableStore(registry)
        for (n in listOf("LOCALF", "ARGF", "RESULTF")) {
            val e = assertFailsWith<CodeEE> { store.declareUserVar(DimParser.parse("#DIM", n, false)) }
            assertTrue(e.message.orEmpty().contains("与系统变量重名"), "$n ⇒ 实得 ${e.message}")
            val ef = assertFailsWith<CodeEE> { store.declareUserVar(DimParser.parse("#DIMF", "$n, 3", false)) }
            assertTrue(ef.message.orEmpty().contains("与系统变量重名"), "$n ⇒ 实得 ${ef.message}")
        }
        // 1.824 对照：既有系统变量名的同一判定不变
        val e2 = assertFailsWith<CodeEE> { store.declareUserVar(DimParser.parse("#DIM", "FLAG", false)) }
        assertTrue(e2.message.orEmpty().contains("与系统变量重名"), "实得 ${e2.message}")
        store.declareUserVar(DimParser.parse("#DIM", "MYVAR, 3", false))
        assertEquals(0L, store.readInt("MYVAR", longArrayOf(0)), "非重名声照常可用")
    }

    /**
     * 浮点声明的**私有同名**不再可能（上一条已拒），因此浮点解析序退化成
     * 「用户变量（其他名）→ 系统浮点」；这里钉住普通用户浮点变量仍优先于系统名查询链。
     */
    @Test
    fun `用户浮点变量与系统浮点名互不串台`() {
        val store = VariableStore(registry)
        store.declareUserVar(DimParser.parse("#DIMF", "RATE, 2 = 0.5", false))
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(0), 9.0)
            assertEquals(9.0, store.readFloat("LOCALF", longArrayOf(0)))
            assertEquals(0.5, store.readFloat("RATE", longArrayOf(1)), "用户 #DIMF 走自己的列")
            store.writeFloat("RATE", longArrayOf(1), 3.0)
            assertEquals(3.0, store.readFloat("RATE", longArrayOf(1)))
            assertEquals(9.0, store.readFloat("LOCALF", longArrayOf(0)), "互不牵连")
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：整型用户变量与系统名的读写互不牵连
        store.declareUserVar(DimParser.parse("#DIM", "CNT, 2", false))
        store.writeInt("CNT", longArrayOf(1), 4L)
        assertEquals(4L, store.readInt("CNT", longArrayOf(1)))
        assertEquals(0L, store.readInt("FLAG", longArrayOf(1)), "系统 FLAG 不受用户变量影响")
    }

    // ------------------------------------------------------------------
    // 存档面与枚举序
    // ------------------------------------------------------------------

    /** 三名都不是存档条目：`savedataDefs` 不含它们；varTokenDic 序把它们放在兜底尾部。 */
    @Test
    fun `浮点名不入档`() {
        val store = VariableStore(registry)
        val saved = store.savedataDefs().map { it.name }
        for (n in listOf("LOCALF", "ARGF", "RESULTF")) {
            assertFalse(n in saved, "$n 不该进存档枚举（fork VariableToken.cs:88-154 的 EXTENDED 判否）")
        }
        val ordered = registry.allInVarTokenDicOrder().map { it.name }
        for (n in listOf("LOCALF", "ARGF", "RESULTF")) {
            assertTrue(n in ordered, "$n 必须被兜底尾部携带，不许丢")
        }
        assertTrue(ordered.indexOf("LOCALF") > ordered.indexOf("RAND"), "兜底尾部在显式序之后")
        // 1.824 对照：LOCAL 族同样不入档、同样由尾部携带
        assertFalse("LOCAL" in saved)
        assertTrue("LOCAL" in ordered)
        assertTrue("FLAG" in saved, "1.824 侧该入档的仍入档")
    }
}
