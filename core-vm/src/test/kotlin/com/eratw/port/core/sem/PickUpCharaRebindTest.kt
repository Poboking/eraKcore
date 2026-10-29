package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PICKUPCHARA 的去重 / 指针重绑 / 豁免实参，与 SORTCHARA 指针判界的 Long 口径。
 *
 * ## 语义根（全部回读原文，逐条带行号）
 * - 语句层：`Process.cs:1267-1283` —— 逐实参「求值 → 判界」交错；实参本身是
 *   MASTER/ASSI/TARGET 变量引用时**豁免判界**（`:1277-1280` 判 `aExpression2 is
 *   VariableTerm && Identifier.Code ∈ {MASTER,ASSI,TARGET}`，三条变量可合法取 -1）。
 * - 实体操作：`VariableEvaluator.cs:1288-1353`（PickUpChara）——
 *   `:1290-1293` 抄旧值后把 MASTER/ASSI/TARGET 全部写 -1 ⇒ **没被选中的指针归 -1**
 *   （不是保持旧值）；`:1295-1301` 收集时**去重**（`list.Contains`）且**跳过负数**；
 *   `:1303-1317` 逐位交换就位，重绑条件是「当前表项值 == 指针旧值」（表项会被
 *   `:1310-1312` 改写，所以重绑看的是交换后的位置，不是原始实参）；
 *   `:1319-1326` 尾段降序删除。
 * - SORTCHARA 指针判界：`VariableEvaluator.cs:1414-1426`（`MASTER >= 0 && MASTER <
 *   CharacterList.Count`，两侧都是 long）与 `:1459-1466`（TARGET/ASSI 按**对象**重定位，
 *   旧值非法时不动）。判界先 toInt 会把 2^32 回绕成 0 ⇒ 把「无主人公」误判成钉 0 号位。
 * - SORTCHARA 键的整/串分流：`VariableEvaluator.cs:1408`（SetSortKey 按
 *   `sortkey.IsString` 分支）；用户角色字符串列（CVARS）也走串分支。
 *
 * ## 本文件的断言口径
 * 角色次序一律断 NO 序列；指针断 MASTER/TARGET/ASSI 的 [0] 读数。
 */
class PickUpCharaRebindTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 建语料：NO = 10/20/30/40 四只，依次入表。 */
    private fun envWith4(): SemTestEnv {
        val env = SemTestEnv()
        for (no in longArrayOf(10, 20, 30, 40)) {
            env.store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
        }
        env.run(instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(listOf(IntLiteral(10), IntLiteral(20), IntLiteral(30), IntLiteral(40)))))
        return env
    }

    private fun noSequence(store: com.eratw.port.core.vars.VariableStore): List<Long> =
        (0 until store.characters.count).map { store.readInt("NO", longArrayOf(it.toLong())) }

    private fun pickup(env: SemTestEnv, vararg nos: Long) =
        env.run(instr(FunctionCode.PICKUPCHARA, ExpressionArrayArgument(nos.map { IntLiteral(it) })))

    // ------------------------------------------------------------------
    // PICKUPCHARA：保留次序与删除
    // ------------------------------------------------------------------

    @Test
    fun `PICKUPCHARA 按参数顺序保留并删除其余`() {
        val env = envWith4()
        pickup(env, 2, 0)
        assertEquals(listOf(30L, 10L), noSequence(env.store))
        assertEquals(2, env.store.characters.count)
    }

    @Test
    fun `PICKUPCHARA 重复实参按原版去重不损坏数据`() {
        // VariableEvaluator.cs:1296 `!list.Contains(NoList[i])` —— 重复下标只收一次。
        // 旧实现把重复下标直接塞进 reorder 全排列，同一位被复制两次、另一只角色数据被吞。
        val env = envWith4()
        pickup(env, 1, 1)
        assertEquals(listOf(20L), noSequence(env.store))
        assertEquals(1, env.store.characters.count)
    }

    @Test
    fun `PICKUPCHARA 非豁免负数实参报错`() {
        // Process.cs:1277-1281 —— 字面量 -1 不是 MASTER/ASSI/TARGET 引用，判界照抛。
        val env = envWith4()
        val err = assertFailsWith<CodeEE> { pickup(env, -1) }
        assertTrue(err.message!!.contains("第1个参数"), err.message!!)
        assertEquals(4, env.store.characters.count) // 报错在判界期，角色表不动
    }

    @Test
    fun `PICKUPCHARA 全负数实参删空并把指针归 -1`() {
        // 豁免引用取 -1 合法（未设置态）；picked 空 ⇒ 全部删除（:1319-1326 的 num 降序循环）。
        val env = envWith4()
        env.store.writeInt("TARGET", longArrayOf(0), -1)
        env.run(
            instr(
                FunctionCode.PICKUPCHARA,
                ExpressionArrayArgument(listOf(iVar("TARGET", 0))),
            ),
        )
        assertEquals(0, env.store.characters.count)
        assertEquals(-1L, env.store.readInt("TARGET", longArrayOf(0)))
    }

    @Test
    fun `PICKUPCHARA 豁免引用带越界值经交换判界报错`() {
        // 豁免只免语句层判界；picked=[100] 进 PickUpChara 后 :1306 SwapChara(100, j)
        // 按 long 判界抛 OoRSwapChara（VariableEvaluator.cs:1378-1384）。
        val env = envWith4()
        env.store.writeInt("TARGET", longArrayOf(0), 100)
        val err = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.PICKUPCHARA, ExpressionArrayArgument(listOf(iVar("TARGET", 0)))))
        }
        assertEquals("试图替换一个不存在的注册角色", err.message)
    }

    // ------------------------------------------------------------------
    // PICKUPCHARA：指针重绑
    // ------------------------------------------------------------------

    @Test
    fun `PICKUPCHARA 被选中指针重绑到新位置 未选中归 -1`() {
        // :1290-1293 抄旧值后全写 -1；:1313-1316 按表项命中重绑。
        val env = envWith4()
        env.store.writeInt("MASTER", longArrayOf(0), 0)
        env.store.writeInt("TARGET", longArrayOf(0), 1)
        env.store.writeInt("ASSI", longArrayOf(0), 3)
        pickup(env, 3, 1) // 40→0 位、20→1 位；MASTER 指 0 号（未选）
        assertEquals(listOf(40L, 20L), noSequence(env.store))
        assertEquals(1L, env.store.readInt("TARGET", longArrayOf(0))) // 旧 1 号 → 新 1 位
        assertEquals(0L, env.store.readInt("ASSI", longArrayOf(0))) // 旧 3 号 → 新 0 位
        assertEquals(-1L, env.store.readInt("MASTER", longArrayOf(0))) // 未选中 → -1
    }

    @Test
    fun `PICKUPCHARA 重绑看交换后的表项 原版口径`() {
        // TARGET=0，PICKUPCHARA 2,0：j=0 的表项是 2 ≠ 0（旧 TARGET），j=1 时表项已被
        // :1310-1312 改写成 2，仍旧 ≠ 0 ⇒ TARGET 最终 -1，尽管 0 号角色确实被选中。
        // 这是 :1313-1316 用「当前表项」而非「原始实参」判重的直接后果，原样复刻。
        val env = envWith4()
        env.store.writeInt("TARGET", longArrayOf(0), 0)
        pickup(env, 2, 0)
        assertEquals(listOf(30L, 10L), noSequence(env.store))
        assertEquals(-1L, env.store.readInt("TARGET", longArrayOf(0)))
    }

    @Test
    fun `PICKUPCHARA 单选指针指向角色时重绑`() {
        val env = envWith4()
        env.store.writeInt("TARGET", longArrayOf(0), 2)
        pickup(env, 2)
        assertEquals(listOf(30L), noSequence(env.store))
        assertEquals(0L, env.store.readInt("TARGET", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // SORTCHARA：指针判界的 Long 口径
    // ------------------------------------------------------------------

    /** MASTER=-1（无主人公、全量排序）+ BASE:*,0 = 30/10/20 的三只角色。 */
    private fun envForSort(): SemTestEnv {
        val env = SemTestEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        env.store.registerTemplate(CharacterTemplate(no = 30, name = "丙", csvNo = 30))
        env.run(instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(listOf(IntLiteral(10), IntLiteral(20), IntLiteral(30)))))
        env.store.writeInt("MASTER", longArrayOf(0), -1)
        env.store.writeInt("BASE", longArrayOf(0, 0), 30)
        env.store.writeInt("BASE", longArrayOf(1, 0), 10)
        env.store.writeInt("BASE", longArrayOf(2, 0), 20)
        return env
    }

    private fun sortBase(env: SemTestEnv, order: SortOrder = SortOrder.ASCENDING) =
        env.run(instr(FunctionCode.SORTCHARA, SpSortcharaArgument(iVar("BASE", 0, 0), order)))

    @Test
    fun `SORTCHARA MASTER 超大占位值按 long 判界不钉位`() {
        // VariableEvaluator.cs:1414 `MASTER >= 0 && MASTER < Count` 是 long 比较；
        // 先 toInt 会把 2^32 回绕成 0 ⇒ 误钉 0 号位，BASE=10 的角色到不了表头。
        // 排序结果按 NO 序列断：BASE 10/20/30 分属 NO 20/30/10。
        val env = envForSort()
        env.store.writeInt("MASTER", longArrayOf(0), 4294967296L)
        sortBase(env)
        assertEquals(listOf(20L, 30L, 10L), noSequence(env.store))
    }

    @Test
    fun `SORTCHARA 排序后 TARGET 重绑到新位置`() {
        // :1459-1466 按对象重定位：旧 2 号（NO 30、BASE 20）排到新 1 位。
        val env = envForSort()
        env.store.writeInt("TARGET", longArrayOf(0), 2)
        sortBase(env)
        assertEquals(listOf(20L, 30L, 10L), noSequence(env.store))
        assertEquals(1L, env.store.readInt("TARGET", longArrayOf(0)))
    }

    @Test
    fun `SORTCHARA TARGET 超大占位值不重绑`() {
        // 旧值 2^32 落不进 [0, Count) ⇒ characterData2 == null（:1419-1421），TARGET 原值保留。
        // 先 toInt 会回绕成 0，把 TARGET 改写成旧 0 号角色的新位置。
        val env = envForSort()
        env.store.writeInt("TARGET", longArrayOf(0), 4294967296L)
        sortBase(env)
        assertEquals(4294967296L, env.store.readInt("TARGET", longArrayOf(0)))
    }

    @Test
    fun `SORTCHARA 钉住 MASTER 时其余角色排序`() {
        // :1431-1450 —— MASTER 合法时摘出排序再插回原位。
        // MASTER=1（NO 20、BASE 10）钉在 1 位；其余按 BASE 升序 NO30(20) → NO10(30)。
        val env = envForSort()
        env.store.writeInt("MASTER", longArrayOf(0), 1)
        sortBase(env)
        assertEquals(listOf(30L, 20L, 10L), noSequence(env.store))
        assertEquals(1L, env.store.readInt("MASTER", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // SORTCHARA：用户角色字符串列作键
    // ------------------------------------------------------------------

    @Test
    fun `SORTCHARA 用户角色字符串变量按串排序`() {
        // 键的整/串分流按变量类型（VariableEvaluator.cs:1408 SetSortKey 的 IsString 分支）；
        // 用户 CVARS 列不在 registry.def，必须先查用户变量表，否则被误判成整数键。
        val env = envForSort()
        env.store.declareUserVar(
            DimSpec(name = "UCNAME", isStr = true, dims = intArrayOf(1), isCharaData = true),
        )
        env.store.writeStr("UCNAME", longArrayOf(0, 0), "B")
        env.store.writeStr("UCNAME", longArrayOf(1, 0), "A")
        env.store.writeStr("UCNAME", longArrayOf(2, 0), "C")
        env.run(instr(FunctionCode.SORTCHARA, SpSortcharaArgument(iVar("UCNAME", 0, 0), SortOrder.ASCENDING)))
        assertEquals(listOf(20L, 10L, 30L), noSequence(env.store))
        assertEquals("A", env.store.readStr("UCNAME", longArrayOf(0, 0)))
    }
}
