package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.VariableSizeLoader
import com.eratw.port.core.err.CodeEE
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [g9] `VARIABLESIZE.CSV` 的**浮点档**：`VariableSizes.float1D`（= 原版
 * `VariableFloatArrayLength`）的装载、容错与进存储层的消费点。
 *
 * ## 证据（fork 4.981 = vendor emuera-skia @48494165，本路逐条实测）
 * - 浮点尺寸表声明：`ConstantData.cs:106`（`VariableFloatArrayLength`）；
 *   出厂各 10：`ConstantData.cs:211-217`（`for (...) VariableFloatArrayLength[i] = 10`）；
 * - 写入支：`ConstantData.cs:532-533`（`else if (id.IsFloat) VariableFloatArrayLength[id.CodeInt] = length`）
 *   —— 移植侧改造前**没有**这一档，只有 :530-531（整数）与 :534-535（字符串）两支；
 * - code 与槽位：`VariableCode.cs:99-101`（LOCALF=槽 0 / ARGF=槽 1 / `__COUNT_FLOAT_ARRAY__=2`）、
 *   `VariableCode.cs:124`（RESULTF 是 0 维）；
 * - 浮点属性来自**名字登记表**而不是 code 位：`VariableDescriptor.cs:108,154-155`
 *   （`Kind = Float`）⇒ 移植侧的 [VariableCode.isFloat] 只能枚举这三个 code；
 * - 消费点（档位如何变成实例长度）：`VariableData.cs:376-379`
 *   （`localvarTokenDic.Add("LOCALF", new VariableLocal(LOCALF, VariableFloatArrayLength[0], …))`）；
 * - 名字解析区分大小写：`VariableIdentifier.cs:137-151,190`（`nameDic` 以 `Enum.GetName` 为键）
 *   ⇒ CSV 里写 `localf,20` 在原版判为未知名；
 * - 浮点**不参与**主数组↔名字表联动：`ConstantData.cs:583-670` 整段没有 Float
 *   （全文件 Float 只出现在 :106-110 声明、:211-217 出厂值、:493-533 写入 switch）。
 *
 * ## 1.824 对拍面
 * 本机 `reference/emuera-cs/`（1.824）的 `ConstantData.cs` 里 `grep Float` **零命中**
 * ⇒ 浮点侧无 1.824 对照，逐个用例标 TODO(verify)；每个用例都配一条**非浮点对照**
 * （同一份 CSV 里的 int/str 行），钉住 1.824 侧未变：
 * 1.824 的对应行号 —— 出厂值 `ConstantData.cs:214,216,221,223`、写入 switch `:453,457,539,543`、
 * 重复定义 `:547`、联动 `:553,583-590`。
 */
class VarSizeFloatFaceTest {

    private fun loadText(text: String): Pair<VariableSizes, List<String>> {
        val dir = createTempDirectory("eratw-varsize-f")
        try {
            dir.resolve("VariableSize.csv").writeText(text)
            val sizes = VariableSizes()
            val warnings = ArrayList<String>()
            VariableSizeLoader.load(dir, sizes, warnings)
            return sizes to warnings
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 出厂档位
    // ------------------------------------------------------------------

    /**
     * 浮点档出厂各 10、两格（LOCALF / ARGF）。
     * TODO(verify)：1.824 无浮点档 ⇒ 只能按 fork-src ConstantData.cs:211-217 与
     * VariableCode.cs:101 的 `__COUNT_FLOAT_ARRAY__ = 2` 钉。
     *
     * 非浮点对照（1.824 可对拍）：整数 1000 / FLAG 10000 / 字符串 100 / RANDDATA 625
     * —— 与 1.824 的 `ConstantData.cs:214,216,218,221,223` 同值，本路一个都没动。
     */
    @Test
    fun `浮点档出厂十且只有两格`() {
        val s = VariableSizes()
        assertContentEquals(intArrayOf(10, 10), s.float1D, "fork-src ConstantData.cs:211-217 出厂各 10")
        assertEquals(VariableCode.FLOAT_ARRAY_COUNT, s.float1D.size, "槽数 = __COUNT_FLOAT_ARRAY__ (VariableCode.cs:101)")
        // 1.824 对照：三档出厂值互不相干，浮点档新增不影响它们
        assertEquals(1000, s.int1D[0], "1.824 ConstantData.cs:214 整数档 1000")
        assertEquals(10000, s.int1D[3], "1.824 ConstantData.cs:216 FLAG = 10000")
        assertEquals(100, s.str1D[0], "1.824 ConstantData.cs:221-222 字符串档 100")
        assertEquals(625, s.int1D[64], "1.824 ConstantData.cs:218 RANDDATA = 625")
    }

    /** 浮点 code 的位形态：带 LOCAL/ARRAY_1D/CAN_FORBID，但**不带任何类型位**。spec: fork VariableCode.cs:99-101,124 */
    @Test
    fun `浮点code不带类型位且槽位为0与1`() {
        assertEquals(0, VariableCode.lowIndex(VariableCode.LOCALF), "fork VariableCode.cs:99 ⇒ 浮点表第 0 槽")
        assertEquals(1, VariableCode.lowIndex(VariableCode.ARGF), "fork VariableCode.cs:100 ⇒ 浮点表第 1 槽")
        assertEquals(1, VariableCode.lowIndex(VariableCode.RESULTF), "fork VariableCode.cs:124 ⇒ dataFloat[1]")
        assertFalse(VariableCode.isInteger(VariableCode.LOCALF), "fork 的浮点 code 没有 __INTEGER__ 位")
        assertFalse(VariableCode.isString(VariableCode.LOCALF), "fork 的浮点 code 没有 __STRING__ 位")
        assertTrue(VariableCode.isFloat(VariableCode.LOCALF) && VariableCode.isFloat(VariableCode.ARGF))
        assertTrue(VariableCode.isFloat(VariableCode.RESULTF))
        assertEquals(1, VariableCode.dimension(VariableCode.LOCALF), "LOCALF 是 1 维（VariableDescriptor.cs:154 Array1D）")
        assertEquals(0, VariableCode.dimension(VariableCode.RESULTF), "RESULTF 是 0 维（VariableDescriptor.cs:108 Scalar）")
        assertTrue(VariableSizes().canForbid(VariableCode.LOCALF), "code 带 __CAN_FORBID__（fork VariableCode.cs:99）")
        // 1.824 对照：整数/字符串的既有判定不受影响
        assertTrue(VariableCode.isInteger(VariableCode.LOCAL_VAR) && !VariableCode.isFloat(VariableCode.LOCAL_VAR))
        assertTrue(VariableCode.isString(VariableCode.ARGS) && !VariableCode.isFloat(VariableCode.ARGS))
    }

    // ------------------------------------------------------------------
    // CSV 行 → 浮点档
    // ------------------------------------------------------------------

    /**
     * `LOCALF,20` / `ARGF,30` 改写浮点档。
     * TODO(verify)：4.981 语料的 `CSV/VariableSize.csv`（138 行）**没有任何浮点行**
     * ⇒ 原版这条支在本语料不可达，只能合成 CSV 验形态。
     *
     * 非浮点对照：同一份 CSV 里的 `FLAG,10000` / `STR,20000` 与 int/str 侧读数逐值不变
     * （1.824 `ConstantData.cs:539` / `:543` 同一条写入链）。
     */
    @Test
    fun `csv浮点行改写LOCALF与ARGF且int与str行不变`() {
        val (s, w) = loadText(
            """
            LOCALF,20
            ARGF,30
            FLAG,10000
            STR,20000
            LOCAL,200
            """.trimIndent(),
        )
        assertEquals(20, s.float1D[0], "fork ConstantData.cs:532-533")
        assertEquals(30, s.float1D[1], "fork ConstantData.cs:532-533")
        assertTrue(w.isEmpty(), "合法浮点行不该有告警：$w")
        // 1.824 对照
        assertEquals(10000, s.int1D[3], "FLAG 走 int1D，没被浮点支抢走")
        assertEquals(20000, s.str1D[1], "STR 特例（1.824 ConstantData.cs:457）")
        assertEquals(20000, s.maxDataList[10], "STR 与 STR.CSV 名字表同步")
        assertEquals(200, s.int1D[VariableCode.lowIndex(VariableCode.LOCAL_VAR)], "LOCAL 仍走 int1D")
    }

    /**
     * 浮点行**不参与**联动：`decideActualArraySize` 之后 LOCALF/ARGF 保持 CSV 值，
     * 而联动组照常同步。spec: fork ConstantData.cs:583-670 整段没有 Float。
     */
    @Test
    fun `浮点行不参与decideActualArraySize联动`() {
        val (s, w) = loadText(
            """
            LOCALF,20
            ARGF,30
            PALAM,300
            """.trimIndent(),
        )
        assertEquals(20, s.float1D[0], "浮点档不被任何联动改写")
        assertEquals(30, s.float1D[1], "浮点档不被任何联动改写")
        // 1.824 对照：PALAM ↔ PALAMNAME 联动照常（1.824 ConstantData.cs:593 一带）
        assertEquals(300, s.charaInt1D[6], "PALAM 主数组")
        assertEquals(300, s.maxDataList[3], "PALAMNAME 跟随")
        assertTrue(w.isEmpty(), "不该有告警：$w")
    }

    /** 缺行（CSV 里没有浮点行）⇒ 保持出厂 10。spec: fork ConstantData.cs:211-217 只在缺省时生效 */
    @Test
    fun `csv缺浮点行时按出厂值`() {
        val (s, w) = loadText("DAY,100\n")
        assertContentEquals(intArrayOf(10, 10), s.float1D, "没写浮点行 ⇒ 出厂 10（VariableData.cs:376-379 读到的就是这一格）")
        assertEquals(100, s.int1D[0], "1.824 对照：DAY 改写生效")
        assertTrue(w.isEmpty(), "$w")
    }

    /**
     * RESULTF 是 0 维 ⇒ 改尺寸被拒（fork ConstantData.cs:323-327 的 CanNotChange0DVarSize），
     * 浮点档一格都不动。spec: fork VariableCode.cs:124（无 ARRAY_1D 位）。
     */
    @Test
    fun `RESULTF是0维不可改尺寸`() {
        val (s, w) = loadText("RESULTF,50\n")
        assertContentEquals(intArrayOf(10, 10), s.float1D, "0 维浮点不占浮点表槽")
        assertEquals(1, w.size, "应有且只有一条「0 维不可改尺寸」：$w")
        assertTrue(w[0].contains("0 维"), "实得 ${w[0]}")
        // 1.824 对照：同族的 0 维字符串 SAVEDATA_TEXT 走同一条拒绝（同一分支）
        val (s2, w2) = loadText("SAVEDATA_TEXT,50\n")
        assertEquals(1, w2.size, "1.824 侧的 0 维拒绝口径不变：$w2")
        assertTrue(w2[0].contains("0 维"), "实得 ${w2[0]}")
        assertEquals(10, s2.float1D[0])
    }

    /**
     * 非法值（非整数 / 空 / 0 / 列数不足）：浮点行的容错与整数行**逐条同形**
     * （同一段 `changeVariableSizeData` 前置判定，fork ConstantData.cs:313-357）。
     * TODO(verify)：fork 的告警文案是 lang 表条目，本层只钉「有告警 + 档位不变」。
     */
    @Test
    fun `浮点行非法值按整数同一条容错链拒绝`() {
        val rows = listOf(
            "LOCALF,abc" to "尺寸非整数",
            "LOCALF," to "尺寸非整数",
            "LOCALF,0" to "无法为 0",
            "ARGF,1.5" to "尺寸非整数",
            "LOCALF" to "列数不足",
            "LOCALF,2000000" to "不能超过",
        )
        for ((row, expect) in rows) {
            val (s, w) = loadText("$row\n")
            assertContentEquals(intArrayOf(10, 10), s.float1D, "被拒的浮点行（$row）不该改档")
            assertEquals(1, w.size, "$row 应恰好一条告警：$w")
            assertTrue(w[0].contains(expect), "$row ⇒ 期望含「$expect」，实得 ${w[0]}")
        }
        // 1.824 对照：同样的六种形态在整数行上也各自拒绝
        for ((row, expect) in listOf(
            "FLAG,abc" to "尺寸非整数",
            "FLAG,0" to "无法为 0",
            "FLAG" to "列数不足",
            "FLAG,2000000" to "不能超过",
        )) {
            val (s, w) = loadText("$row\n")
            assertEquals(10000, s.int1D[3], "被拒的整数行（$row）不该改档（FLAG 出厂 10000，1.824 ConstantData.cs:216）")
            assertEquals(1, w.size, "$row ⇒ $w")
            assertTrue(w[0].contains(expect), "实得 ${w[0]}")
        }
    }

    /** 下限：浮点是局部变量 ⇒ 允许 <100（fork ConstantData.cs:363-367 的 IsLocal 支），非局部仍 ≥100。 */
    @Test
    fun `浮点行走局部变量下限而非一百`() {
        val (s, w) = loadText("LOCALF,1\n")
        assertEquals(1, s.float1D[0], "IsLocal ⇒ 下限是 1（fork ConstantData.cs:363-367）")
        assertTrue(w.isEmpty(), "$w")
        // 1.824 对照：非局部变量给 1 被「内部变量尺寸不能小于 100」拒（ConstantData.cs:369-373）
        val (s2, w2) = loadText("DAY,1\n")
        assertEquals(1000, s2.int1D[0], "DAY 非局部 ⇒ 1 被拒")
        assertEquals(1, w2.size, "$w2")
        assertTrue(w2[0].contains("小于 100"), "实得 ${w2[0]}")
    }

    /**
     * 负数 ⇒ CanForbid 变量按「禁用数组」落成 0（fork ConstantData.cs:347-356）。
     * LOCALF/ARGF 的 code 都带 `__CAN_FORBID__`（fork VariableCode.cs:99-100）⇒ 可禁用。
     */
    @Test
    fun `浮点行负值落成禁用零`() {
        val (s, w) = loadText("LOCALF,-1\nARGF,-9\n")
        assertEquals(0, s.float1D[0], "CanForbid + 负值 ⇒ 0 长度（禁用）")
        assertEquals(0, s.float1D[1], "CanForbid + 负值 ⇒ 0 长度（禁用）")
        assertTrue(w.isEmpty(), "负值改写不告警（原版 :352-356 只在多写尺寸时告警）：$w")
        // 1.824 对照：不可禁用的变量给负值当场拒绝（ConstantData.cs:347-351）
        val (s2, w2) = loadText("RESULT,-1\n")
        assertEquals(1000, s2.int1D[10], "RESULT 非 CanForbid ⇒ 负值不改档")
        assertEquals(1, w2.size, "$w2")
        assertTrue(w2[0].contains("不可禁用"), "实得 ${w2[0]}")
    }

    /** 重复定义（fork ConstantData.cs:541-542 的 VarSizeAlreadyDefined）：以最后一次为准 + 告警。 */
    @Test
    fun `浮点行重复定义以最后一次为准`() {
        val (s, w) = loadText("LOCALF,20\nLOCALF,40\n")
        assertEquals(40, s.float1D[0], "以最后一次为准")
        assertEquals(1, w.size, "$w")
        assertTrue(w[0].contains("重复定义"), "实得 ${w[0]}")
        // 1.824 对照：整数行的同一判定
        val (s2, w2) = loadText("DAY,200\nDAY,300\n")
        assertEquals(300, s2.int1D[0])
        assertEquals(1, w2.size, "$w2")
        assertTrue(w2[0].contains("重复定义"), "实得 ${w2[0]}")
    }

    /**
     * 名字解析区分大小写（fork VariableIdentifier.cs:137-151,190 的 `nameDic` 以
     * `Enum.GetName` 为键）⇒ `localf,20` 判未知，不改档。
     */
    @Test
    fun `小写浮点名按原版判未知`() {
        val (s, w) = loadText("localf,20\n")
        assertEquals(10, s.float1D[0], "原版 nameDic 区分大小写 ⇒ 未知名")
        assertEquals(1, w.size, "$w")
        assertTrue(w[0].contains("未知"), "实得 ${w[0]}")
        // 1.824 对照：整数行同样按大小写敏感
        val (s2, w2) = loadText("flag,10000\n")
        assertEquals(10000, s2.int1D[3], "1.824 侧同样是大小写敏感登记表 ⇒ 未命中，保持出厂 10000")
        assertEquals(1, w2.size, "$w2")
    }

    // ------------------------------------------------------------------
    // setFloat1D（与 setInt1D / setStr1D 同形的按名 API）
    // ------------------------------------------------------------------

    /** `setFloat1D` 只认 LOCALF/ARGF，且沿用 [VariableSizes.verifySize] 的正数校验。 */
    @Test
    fun `setFloat1D按名字改档并拒绝未知名与非正数`() {
        val s = VariableSizes()
        s.setFloat1D("LOCALF", 64)
        s.setFloat1D("ARGF", 128)
        assertContentEquals(intArrayOf(64, 128), s.float1D)
        assertFailsWith<CodeEE> { s.setFloat1D("RESULTF", 5) }
        assertFailsWith<CodeEE> { s.setFloat1D("FLAG", 5) }
        assertFailsWith<CodeEE> { s.setFloat1D("LOCALF", 0) }
        assertFailsWith<CodeEE> { s.setFloat1D("LOCALF", -3) }
        // 1.824 对照：既有的两个按名入口行为不变
        s.setInt1D("FLAG", 20000)
        s.setStr1D("SAVESTR", 300)
        assertEquals(20000, s.int1D[3], "整数入口未变")
        assertEquals(300, s.str1D[0], "字符串入口未变")
        assertFailsWith<CodeEE> { s.setInt1D("LOCALF", 5) }
    }

    /** `changed` 集合认浮点 code（等价 fork changedCode.Add，ConstantData.cs:541）。 */
    @Test
    fun `浮点code进changed集合且改完清零`() {
        val s = VariableSizes()
        assertTrue(s.applySize(VariableCode.LOCALF, intArrayOf(16)), "首次定义 ⇒ true")
        assertFalse(s.applySize(VariableCode.LOCALF, intArrayOf(32)), "第二次 ⇒ false（重复定义）")
        assertTrue(s.changed.contains(VariableCode.LOCALF), "changedCode 里有浮点 code")
        val w = ArrayList<String>()
        s.decideActualArraySize(w)
        assertFalse(s.changed.contains(VariableCode.LOCALF), "联动后清空 changed（fork ConstantData.cs:550）")
        // 1.824 对照：整数 code 的 changed 判定同形
        assertTrue(s.applySize(VariableCode.DAY, intArrayOf(100)))
        assertTrue(s.changed.contains(VariableCode.DAY))
    }

    // ------------------------------------------------------------------
    // 消费点：档位 → 每函数 LOCALF/ARGF 实例长度
    // ------------------------------------------------------------------

    /**
     * 浮点档被存储层读走（原版 `VariableData.cs:376-379` 把这一格交给 VariableLocal 当 size）：
     * `VARSIZE("LOCALF")` = 20、`LOCALF:19` 可写、`:20` 越界；ARGF 不受 LOCALF 行影响。
     */
    @Test
    fun `浮点档进存储层决定实例长度`() {
        val sizes = VariableSizes()
        sizes.setFloat1D("LOCALF", 20)
        val store = VariableStore(VariableRegistry(sizes))
        store.enterFunction("T")
        try {
            assertEquals(20, store.getLength("LOCALF"), "档位 20 必须是 VARSIZE 的答案")
            assertEquals(10, store.getLength("ARGF"), "没改 ARGF 行 ⇒ 仍出厂 10")
            store.writeFloat("LOCALF", longArrayOf(19), 1.5)
            assertEquals(1.5, store.readFloat("LOCALF", longArrayOf(19)), "20 档 ⇒ 第 19 格可达")
            assertFailsWith<CodeEE> { store.writeFloat("LOCALF", longArrayOf(20), 1.0) }
            assertFailsWith<CodeEE> { store.readFloat("ARGF", longArrayOf(10)) }
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：整数/字符串局部档位同一条链，且不被浮点档牵连。
        // 按 code 走 applySize：LOCAL 不在 setInt1D 的名字清单里（那两只只认
        // SystemLayout.int1D / str1D，LOCAL 在 localInt 里）。
        val sizes2 = VariableSizes()
        sizes2.applySize(VariableCode.LOCAL_VAR, intArrayOf(200))
        val store2 = VariableStore(VariableRegistry(sizes2))
        store2.enterFunction("T")
        try {
            assertEquals(200, store2.getLength("LOCAL"))
            store2.writeInt("LOCAL", longArrayOf(199), 7L)
            assertEquals(7L, store2.readInt("LOCAL", longArrayOf(199)))
            assertFailsWith<CodeEE> { store2.writeInt("LOCAL", longArrayOf(200), 1L) }
        } finally {
            store2.leaveFunction()
        }
    }

    /** 禁用的浮点档（0 长度）：任何下标都越界，等价原版 0 长度数组。spec: fork ConstantData.cs:356 */
    @Test
    fun `禁用的浮点档任何下标都越界`() {
        val sizes = VariableSizes()
        sizes.applySize(VariableCode.LOCALF, intArrayOf(0))
        val store = VariableStore(VariableRegistry(sizes))
        store.enterFunction("T")
        try {
            assertEquals(0, store.getLength("LOCALF"), "禁用数组的 VARSIZE 仍返回 0（同整数侧口径）")
            assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(0)) }
            assertFailsWith<CodeEE> { store.writeFloat("LOCALF", longArrayOf(0), 1.0) }
            // 1.824 对照：禁用浮点档不牵连整数 LOCAL（LOCAL 出厂 1000，可正常读写）
            store.writeInt("LOCAL", longArrayOf(0), 5L)
            assertEquals(5L, store.readInt("LOCAL", longArrayOf(0)))
        } finally {
            store.leaveFunction()
        }
    }
}
