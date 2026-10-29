package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RESETGLOBAL / ADDCOPYCHARA 两条语句的**原版口径**审计用例（M4-N n-stmtreset）。
 *
 * 覆盖两组实测结论（每条都在注释里带 C# 出处，语义权威是 specs + 反编译源）：
 *
 * RESETGLOBAL：原版一条指令 = `ResetGlobalData()` 的**三步**
 * （spec: FunctionIdentifier.cs:1859-1862 → VariableEvaluator.cs:1348-1353）——
 * ① `RemoveEMGlobalData()`（VariableData.cs:4031-4054）清 GLOBAL_MAPS/XMLS/DTS 命名的池；
 * ② `RemoveEMStaticData()`（:4056-4079）同型三遍走 STATIC_* 三表；
 * ③ `SetDefaultGlobalValue()`（:3347-3363）= GLOBAL 整表置 0（:3349-3354）、
 * GLOBALS **置 null**（:3355-3358，:3357）、用户 `#DIM GLOBAL` 变量逐个
 * `SetDefault()`（:3359-3362）。
 * 本文件把 ③ 的三件事各自钉一条用例，并钉住「RESETGLOBAL 不外溢」的边界
 * （DAY / STATIC 用户变量 / 角色表都不动）；② 因本工程 `EmSaveSets` 未解析
 * `STATIC_MAPS`/`STATIC_XMLS`/`STATIC_DTS`（ConstantData.cs:1389-1405 的三条指令
 * 在本仓 EmSaveSets.kt:83-89 的 switch 里没有分支）而**不可达**，登记为需组长接线。
 *
 * ADDCOPYCHARA：原版次序是「**先校验 → 后追加 → 再复制**」
 * （spec: FunctionIdentifier.cs:1455-1462 → VariableEvaluator.cs:1368-1376）：
 * :1370-1373 边界校验抛 `CodeEE(Lang.Error.NotExistFromCopyChara.Text)`
 * （文案 lang.emuera-zhs.xml:316「复制的原角色不存在」），
 * :1374 才 `AddPseudoCharacter()`，:1375
 * `CharacterList[x].CopyTo(CharacterList[Count-1])` ⇒ **源 = 实参、目标 = 新尾元素**。
 * 本文件的方向用例就是本路的反向证明：把 StatementExecutor 的调用点退回
 * `copyCharacter(pos, src)`（旧写法）后，源角色会被刚追加的裸伪角色覆盖，
 * 越界用例还会多留一只裸角色 ⇒ 两条用例同时变红。
 */
class ResetGlobalAuditTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 注册两只能区分的模板（甲 NO=10、乙 NO=20）。 */
    private fun envWithTwoTemplates(): SemTestEnv {
        val env = SemTestEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        return env
    }

    private fun addChara(env: SemTestEnv, vararg nos: Long) =
        env.run(instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(nos.map { IntLiteral(it) })))

    /** ADDCOPYCHARA 的参数形态是 INT_ANY 表达式数组（spec: FunctionIdentifier.cs:1451）。 */
    private fun addCopyChara(env: SemTestEnv, vararg srcs: Long) =
        env.run(instr(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(srcs.map { IntLiteral(it) })))

    private fun resetGlobal(env: SemTestEnv) = env.run(instr(FunctionCode.RESETGLOBAL))

    // ------------------------------------------------------------------
    // ADDCOPYCHARA：方向（源 = 实参、目标 = 新尾元素）
    // ------------------------------------------------------------------

    /**
     * 原版 :1375 的源/目标方向：`CharacterList[(int)x].CopyTo(CharacterList[Count-1])`
     * ⇒ 尾元素拿到源内容，源本身一个字节都不许变。
     *
     * 移植侧旧写法 `copyCharacter(pos, src)` 落到 `CharacterTable.copy(dst, src)`
     * （CharacterTable.kt:170 首形参是 **dst**）后变成 dst=src、src=pos，
     * 即「把裸伪角色写回源位」⇒ 本用例的源侧断言红。
     */
    @Test
    fun `ADDCOPYCHARA 把实参角色复制到新追加的尾元素`() {
        val env = envWithTwoTemplates()
        addChara(env, 10)
        // 给源角色打上专属内容（源与目标内容必须不同，否则方向不可区分）
        env.store.writeInt("BASE", longArrayOf(0, 0), 111)
        env.store.writeInt("CFLAG", longArrayOf(0, 3), 77)
        env.store.writeInt("DAY", longArrayOf(0), 5)

        addCopyChara(env, 0)

        assertEquals(2, env.store.characters.count)
        // 目标 = 尾元素：整只角色（含 NO 列）都来自源
        assertEquals(10L, env.store.readInt("NO", longArrayOf(1)))
        assertEquals(111L, env.store.readInt("BASE", longArrayOf(1, 0)))
        assertEquals(77L, env.store.readInt("CFLAG", longArrayOf(1, 3)))
        // 源必须原样完好：反号写法在这里会把 0 号覆盖成裸伪角色
        assertEquals(10L, env.store.readInt("NO", longArrayOf(0)))
        assertEquals(111L, env.store.readInt("BASE", longArrayOf(0, 0)))
        assertEquals(77L, env.store.readInt("CFLAG", longArrayOf(0, 3)))
        // DAY 不是角色变量：复制角色不该动它（边界防外溢）
        assertEquals(5L, env.store.readInt("DAY", longArrayOf(0)))
    }

    /** 两只不同源各复制一次：尾元素按实参顺序分别拿到甲/乙（方向用例的第二重钉）。 */
    @Test
    fun `ADDCOPYCHARA 多实参逐个追加并按各自源复制`() {
        val env = envWithTwoTemplates()
        addChara(env, 10, 20)
        env.store.writeInt("BASE", longArrayOf(0, 0), 111)
        env.store.writeInt("BASE", longArrayOf(1, 0), 222)

        addCopyChara(env, 0, 1)

        assertEquals(4, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(2)))
        assertEquals(111L, env.store.readInt("BASE", longArrayOf(2, 0)))
        assertEquals(20L, env.store.readInt("NO", longArrayOf(3)))
        assertEquals(222L, env.store.readInt("BASE", longArrayOf(3, 0)))
        // 两只源各自完好
        assertEquals(111L, env.store.readInt("BASE", longArrayOf(0, 0)))
        assertEquals(222L, env.store.readInt("BASE", longArrayOf(1, 0)))
    }

    /**
     * 越界实参：原版在 `AddPseudoCharacter()` **之前**抛错
     * （spec: VariableEvaluator.cs:1370-1373 早于 :1374），角色表长度不变。
     * 旧写法先追加再复制 ⇒ 表里多留一只裸角色（半死态分歧），本用例即钉这条。
     */
    @Test
    fun `ADDCOPYCHARA 越界实参抛原版错且不落下裸角色`() {
        val env = envWithTwoTemplates()
        addChara(env, 10)
        env.store.writeInt("BASE", longArrayOf(0, 0), 111)

        // 上界：Count == 1 ⇒ x=1 已越界（:1370 是 `>=`，不是 `>`）
        val over = assertFailsWith<CodeEE> { addCopyChara(env, 1) }
        assert(over.message!!.contains("复制的原角色不存在")) { "文案须为原版 NotExistFromCopyChara：${over.message}" }
        // 远端越界
        assertFailsWith<CodeEE> { addCopyChara(env, 99) }
        // 负数越界
        assertFailsWith<CodeEE> { addCopyChara(env, -1) }

        // 三次失败一次角色都不许新增（原版三次都在 :1374 之前就抛出）
        assertEquals(1, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(0)))
        assertEquals(111L, env.store.readInt("BASE", longArrayOf(0, 0)))
    }

    /** 空表（一只角色都没有）时任何实参都越界：`Count` 为 0 ⇒ 0 也非法。 */
    @Test
    fun `ADDCOPYCHARA 在无角色时同样先抛错`() {
        val env = SemTestEnv()
        assertEquals(0, env.store.characters.count)
        val e = assertFailsWith<CodeEE> { addCopyChara(env, 0) }
        assert(e.message!!.contains("复制的原角色不存在")) { "文案须为原版 NotExistFromCopyChara：${e.message}" }
        assertEquals(0, env.store.characters.count)
    }

    // ------------------------------------------------------------------
    // RESETGLOBAL：第三步 SetDefaultGlobalValue 的三件事
    // ------------------------------------------------------------------

    /** ③-1：GLOBAL 整表置 0。spec: VariableData.cs:3349-3354（`dataIntegerArray[63]`）。 */
    @Test
    fun `RESETGLOBAL 归零 GLOBAL 整表`() {
        val env = SemTestEnv()
        env.store.writeInt("GLOBAL", longArrayOf(5), 7)
        env.store.writeInt("GLOBAL", longArrayOf(62), 8)
        resetGlobal(env)
        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(5)))
        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(62)))
    }

    /**
     * ③-2：GLOBALS 复位。原版是**置 null**（spec: VariableData.cs:3355-3358，:3357）。
     * 本仓字符串槽是驻留池下标、0 号槽出厂即空串（StringTable.kt:19-22），
     * 没有「null 与空串」的可观测二分（`ISINITIALNULLSTR` 一类判空语义本仓不存在）
     * ⇒ 落地口径为 fill(0)，读数即空串。TODO(verify)：若要复刻 null 语义，
     * 需先给 StringTable 加「未赋值」哨兵，属跨层接线，不在本路独占面。
     */
    @Test
    fun `RESETGLOBAL 复位 GLOBALS 全列`() {
        val env = SemTestEnv()
        env.store.writeStr("GLOBALS", longArrayOf(2), "旧值")
        env.store.writeStr("GLOBALS", longArrayOf(4), "另一值")
        resetGlobal(env)
        assertEquals("", env.store.readStr("GLOBALS", longArrayOf(2)))
        assertEquals("", env.store.readStr("GLOBALS", longArrayOf(4)))
    }

    /**
     * ③-3：用户 `#DIM GLOBAL` 整数变量回到**声明初值**而不是归零。
     *
     * `SetDefault()` 的实现口径（这是本路「别按直觉」的前置检查结论）：
     * `StaticInt1DVariableToken` 构造期把 `data.DefaultInt` 存进 `defArray`
     * （spec: VariableData.cs:1303-1309），`SetDefault()`（:1323-1332）
     * 先把整表 `Clear()`、再把 `defArray` 回填 ⇒ 「回到 `#DIM`/`DEFAULT` 的初值」，
     * 只有**没声明初值**时（`defArray == null`）才等价于归零。
     * 入表面：`CreateUserDefVariable` 里 `IsGlobal` 分支 → `userDefinedGlobalVarList`
     * （:3246-3268），`SetDefaultGlobalValue` 逐个复位（:3359-3362）。
     */
    @Test
    fun `RESETGLOBAL 把 GLOBAL 用户整数变量复位到声明初值`() {
        val env = SemTestEnv()
        env.store.declareUserVar(
            DimSpec(
                name = "NGVAR", isStr = false, dims = intArrayOf(3),
                isGlobal = true, defaultsInt = longArrayOf(0, 42, 0),
            ),
        )
        env.store.writeInt("NGVAR", longArrayOf(0), 7)
        env.store.writeInt("NGVAR", longArrayOf(1), 8)
        env.store.writeInt("NGVAR", longArrayOf(2), 9)

        resetGlobal(env)

        assertEquals(0L, env.store.readInt("NGVAR", longArrayOf(0)))
        // 这一条就是「归零」与「回初值」的分水岭：归零写法给 0，原版给 42
        assertEquals(42L, env.store.readInt("NGVAR", longArrayOf(1)))
        assertEquals(0L, env.store.readInt("NGVAR", longArrayOf(2)))
    }

    /** ③-3 的字符串面：`StaticStr1DVariableToken.SetDefault`（:1595-1606）回填 defArray。 */
    @Test
    fun `RESETGLOBAL 把 GLOBAL 用户字符串变量复位到声明初值`() {
        val env = SemTestEnv()
        env.store.declareUserVar(
            DimSpec(
                name = "NGSAR", isStr = true, dims = intArrayOf(2),
                isGlobal = true, defaultsStr = listOf("初", "值"),
            ),
        )
        env.store.writeStr("NGSAR", longArrayOf(0), "改")
        env.store.writeStr("NGSAR", longArrayOf(1), "改2")
        resetGlobal(env)
        assertEquals("初", env.store.readStr("NGSAR", longArrayOf(0)))
        assertEquals("值", env.store.readStr("NGSAR", longArrayOf(1)))
    }

    /**
     * 未声明初值时 `SetDefault()` 才等价于归零：
     * `defArray == null` 分支（spec: VariableData.cs:1328-1331 的 if 不成立、
     * 字符串面 :1598-1601 的 `Array.Clear`）。
     */
    @Test
    fun `RESETGLOBAL 对未声明初值的 GLOBAL 用户变量等价归零`() {
        val env = SemTestEnv()
        env.store.declareUserVar(DimSpec(name = "NGPLAIN", isStr = false, dims = intArrayOf(4), isGlobal = true))
        env.store.writeInt("NGPLAIN", longArrayOf(1), 123)
        resetGlobal(env)
        assertEquals(0L, env.store.readInt("NGPLAIN", longArrayOf(1)))
    }

    // ------------------------------------------------------------------
    // RESETGLOBAL 的边界：不得外溢到 RESETDATA 的复位面
    // ------------------------------------------------------------------

    /**
     * RESETGLOBAL 只碰 GLOBAL / GLOBALS / GLOBAL 用户变量：
     * 其余系统数组由 `SetDefaultValue`（spec: VariableData.cs:3385-3514）复位，
     * 而 `SetDefaultGlobalValue`（:3347-3363）逐行读下来根本不动它们 ⇒ DAY 原样保留。
     */
    @Test
    fun `RESETGLOBAL 不清其余系统数组`() {
        val env = SemTestEnv()
        env.store.writeInt("DAY", longArrayOf(0), 5)
        env.store.writeInt("FLAG", longArrayOf(0), 6)
        env.store.writeStr("RESULTS", longArrayOf(0), "文")
        resetGlobal(env)
        assertEquals(5L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals(6L, env.store.readInt("FLAG", longArrayOf(0)))
        assertEquals("文", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    /**
     * RESETGLOBAL 不清角色表、不复位 STATIC 用户变量、不复位函数局部
     * （spec: VariableEvaluator.cs:1348-1353 的三步里没有这三件事；
     * 角色与局部只由 ResetData（:1336-1346）管）。
     */
    @Test
    fun `RESETGLOBAL 不动角色表与静态用户变量`() {
        val env = envWithTwoTemplates()
        addChara(env, 10)
        env.store.declareUserVar(
            DimSpec(
                name = "NSTAT", isStr = false, dims = intArrayOf(2),
                isStatic = true, isGlobal = false, defaultsInt = longArrayOf(0, 9),
            ),
        )
        env.store.writeInt("NSTAT", longArrayOf(0), 1)
        env.store.writeInt("NSTAT", longArrayOf(1), 2)

        resetGlobal(env)

        assertEquals(1, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(0)))
        // STATIC 用户变量属 SetDefaultLocalValue 面（:3365-3375），RESETGLOBAL 不许碰
        assertEquals(1L, env.store.readInt("NSTAT", longArrayOf(0)))
        assertEquals(2L, env.store.readInt("NSTAT", longArrayOf(1)))
    }

    /**
     * 与 RESETDATA 的互补不对称（原版两处 `continue` 是硬证据）：
     * `SetDefaultValue` 显式**跳过** GLOBAL（spec: VariableData.cs:3398-3399）
     * 与 GLOBALS（:3417-3418 的 `case 5: continue`），
     * 所以 RESETDATA 既不清 GLOBAL 也不清 GLOBALS —— 这正是 RESETGLOBAL 存在的理由。
     * （移植侧对应 `VariableStore.resetValues` 里 GLOBAL / GLOBALS 各一处 `continue`。）
     */
    @Test
    fun `RESETDATA 不清 GLOBAL 与 GLOBALS`() {
        val env = SemTestEnv()
        env.store.writeInt("GLOBAL", longArrayOf(5), 7)
        env.store.writeStr("GLOBALS", longArrayOf(2), "留")
        env.store.writeInt("DAY", longArrayOf(0), 5)
        env.run(instr(FunctionCode.RESETDATA))
        assertEquals(7L, env.store.readInt("GLOBAL", longArrayOf(5)))
        assertEquals("留", env.store.readStr("GLOBALS", longArrayOf(2)))
        // 对照：RESETDATA 确实复位了普通系统数组与角色表
        assertEquals(0L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals(0, env.store.characters.count)
    }

    /**
     * RESETGLOBAL 第一步（`RemoveEMGlobalData`）的可达性：
     * 会话未装配存档接线（`SaveWiring.of(store) == null`）时名字表为空 ⇒ 空操作，
     * 既不抛错也不影响后两步（原版 `ConstantData.cs:1311-1316` 的六表清空形态同理）。
     *
     * 已接的部分：本路把 `EmDataPools.clear(store, emSets.global)` 接进了语句层
     * （spec: VariableData.cs:4031-4054）。**未接**的部分见交付「需组长接线」：
     * `STATIC_MAPS`/`STATIC_XMLS`/`STATIC_DTS` 在 EmSaveSets 里没有解析分支，
     * 故 `RemoveEMStaticData`（:4056-4079）目前无从取名单。
     */
    @Test
    fun `RESETGLOBAL 在未装配存档接线时对池只做空操作`() {
        val env = SemTestEnv()
        env.store.writeInt("GLOBAL", longArrayOf(0), 3)
        resetGlobal(env)
        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(0)))
    }
}
