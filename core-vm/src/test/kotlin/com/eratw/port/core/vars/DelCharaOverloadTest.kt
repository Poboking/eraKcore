package com.eratw.port.core.vars

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * M4-M `m-delchara`：DELCHARA 两条重载的分流 + 文案原文 + 官方 bug 复刻
 * （CONTRACT-M4-M §2.3）。起点 = main@a67c3d5。
 *
 * ## 语义根（全部回读原文，逐条带行号）
 * - 选型判据：`FunctionIdentifier.cs:1344-1397`（ADDCHARA / ADDSPCHARA / DELCHARA 同一个类
 *   三个注册位 `:3943/:3944/:3947`，DELCHARA = `flagDel: true`；`:1352` 实参 = INT_ANY）。
 *   isDel 只求值收集（`:1371-1375`），删除前按**实参个数**二选一：
 *   `:1387-1390` `array.Length == 1` → `DelCharacter(array[0])`；
 *   `:1391-1394` 否则（含 0 个实参）→ `DelCharacter(array)`。
 * - 单参：`VariableEvaluator.cs:1241-1249` —— `:1245`
 *   `CodeEE(format(OoRDelChara, charaNo.ToString()))` ⇒ **带具体编号**；
 *   `:1247` Dispose → `:1248` RemoveAt。
 * - 数组参：`VariableEvaluator.cs:1251-1273` —— `:1259` 的 format 实参写成
 *   **`charaNoList.ToString()`（数组对象本身）** ⇒ .NET 上屏固定串
 *   `System.Int64[]`、**编号丢失**（官方 bug，ADR-005 原样复刻）；
 *   `:1262-1265` 按**对象**判重（`list.Contains(characterData)`，CharacterData 未覆写
 *   Equals ⇒ 引用相等），串 = `DuplicateDelChara` **带编号**（`:1264` 传 `num.ToString()`）；
 *   `:1266-1267` 入 list 后立刻 Dispose；`:1269-1272` 全部校验通过后才按实参顺序
 *   `CharacterList.Remove(item)`。
 * - 文案原文（zhs）：`MinorShift.Emuera.Properties.lang.emuera-zhs.xml:314`
 *   `试图删除不存在的注册角色({0})`、`:315` `相同的注册角色编号({0})被重复指定`
 *   （注册处 Lang.cs:1438 / :1442，JP 出厂值同文件可见）。
 *
 * ## 本文件的断言口径
 * 全部断**上屏原文整串**（不是「抛了 CodeEE」），并把「单参带编号 / 数组参丢编号」这条
 * 差异钉成互不相等的两个串 —— 任一侧被「顺手修好」都会红。
 */
class DelCharaOverloadTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun storeWith(vararg nos: Long): VariableStore {
        val s = VariableStore()
        nos.forEach { s.registerTemplate(CharacterTemplate(no = it, name = "角色$it", csvNo = it)) }
        nos.forEach { s.addCharacter(it) }
        return s
    }

    /** 余表的 NO 序列（删除次序等价性的观测量）。 */
    private fun noSequence(s: VariableStore): List<Long> =
        (0 until s.characters.count).map { s.readInt("NO", longArrayOf(it.toLong())) }

    private fun charaNum(s: VariableStore): Long = s.readInt("CHARANUM", longArrayOf())

    /** 只到语句层 DELCHARA 分支的最小语境（该分支文件本路由他路独占，此处只调用不修改）。 */
    private class StmtEnv {
        val store = VariableStore()
        private val console = FakeConsoleSink()
        private val eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT)
        private val ctx = ExecContext(variables = store, eval = eval, console = console)
        private val executor = StatementExecutor()

        fun templateNos(vararg nos: Long): StmtEnv {
            nos.forEach { store.registerTemplate(CharacterTemplate(no = it, name = "角色$it", csvNo = it)) }
            return this
        }

        fun add(vararg nos: Long) { nos.forEach { store.addCharacter(it) } }

        fun del(vararg nos: Long) = executor.execute(instr(FunctionCode.DELCHARA, charaArg(*nos)), ctx)
    }

    // ------------------------------------------------------------------
    // 单元 1 · 单参重载：越界带编号（VariableEvaluator.cs:1241-1249）
    // ------------------------------------------------------------------

    /**
     * spec: `VariableEvaluator.cs:1243`（`charaNo < 0 || charaNo >= Count`）+ `:1245`
     * （format 实参 `charaNo.ToString()`）+ zhs 表形 `lang.emuera-zhs.xml:314`。
     * 角色数**不进串**（原版文案里没有 Count），旧实现的「（角色数 N）」尾巴是自造的。
     */
    @Test
    fun `单参越界 报具体编号的原文串`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacter(5) }
        assertEquals("试图删除不存在的注册角色(5)", e.message)
        assertEquals(3L, charaNum(s), "越界不改表（:1245 先于 :1247 抛）")
        assertEquals(listOf(10L, 20L, 30L), noSequence(s))
    }

    /**
     * 负编号同样入串：`:1243` 的 `charaNo < 0` 与 `:1245` 的 `charaNo.ToString()` 对
     * long 取原值 ⇒ -1 上屏 `-1`（不是 4294967295、也不是绝对值）。
     */
    @Test
    fun `单参越界 负编号原样入串`() {
        val s = storeWith(10)
        val e = assertFailsWith<CodeEE> { s.deleteCharacter(-1) }
        assertEquals("试图删除不存在的注册角色(-1)", e.message)
        assertEquals(1L, charaNum(s))
    }

    // ------------------------------------------------------------------
    // 单元 2 · 数组参重载：官方 bug 复刻（VariableEvaluator.cs:1251-1261）
    // ------------------------------------------------------------------

    /**
     * **官方 bug 原样复刻（ADR-005）**：`VariableEvaluator.cs:1259` 把 format 实参写成
     * `charaNoList.ToString()`（数组对象本身），CLR 对数组只给类型全名 ⇒ 上屏
     * `试图删除不存在的注册角色(System.Int64[])`，**出错的那个编号不见了**。
     * 对照 `:1245` 单参版传 `charaNo.ToString()` ⇒ 两条重载文案必然不同。
     * 串里的 `System.Int64[]` 与元素个数、内容、越界位置**全无关**（固定串）。
     */
    @Test
    fun `数组参越界 复刻官方bug 编号丢失上屏数组类型全名`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 5)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertEquals(3L, charaNum(s), "抛在 :1259，Remove 循环 :1269 不可达 ⇒ 原版表也不变")
        assertEquals(listOf(10L, 20L, 30L), noSequence(s))
    }

    /**
     * 同一条数组参路径，**首元素**就越界 vs **末元素**越界：两次的串一字不差
     * （原版丢的是整个数组对象的 ToString，与 `num` 无关）。
     */
    @Test
    fun `数组参越界 越界位置不同串不变`() {
        val s = storeWith(10, 20, 30)
        val first = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(9, 0)) }
        val last = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 9)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", first.message)
        assertEquals(first.message, last.message, ":1259 的实参与出错位置无关")
    }

    /**
     * 反向防回归：**单参与数组参两条路径的串必须不同**。
     * 移植侧曾把两条重载合成一条（同串），把这条差异钉死，防「顺手修好」把差异抹平。
     */
    @Test
    fun `单参与数组参文案不同 单参带编号 数组参丢编号`() {
        val s = storeWith(10, 20, 30)
        val single = assertFailsWith<CodeEE> { s.deleteCharacter(5) }
        val array = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 5)) }
        assertNotEquals(single.message, array.message, "FunctionIdentifier.cs:1387-1394 分流的唯一可见后果")
        assertEquals("试图删除不存在的注册角色(5)", single.message)
        assertEquals("试图删除不存在的注册角色(System.Int64[])", array.message)
    }

    // ------------------------------------------------------------------
    // 单元 3 · 语句层按实参个数分流（FunctionIdentifier.cs:1385-1395）
    // ------------------------------------------------------------------

    /**
     * 语句层读数：`DELCHARA 5`（1 个实参）→ `:1389` 单参重载 → **带编号**；
     * `DELCHARA 0 5`（2 个实参）→ `:1393` 数组参重载 → `System.Int64[]`。
     * 分流点已按原版上移到 `core/sem/StatementExecutor.kt` 的 DELCHARA 分支
     * （`if (ps.size == 1)`），`VariableStore` 两个入口各自只剩单一语义。
     */
    @Test
    fun `语句层按实参个数分流 一实参报编号 二实参报数组串`() {
        val env = StmtEnv().templateNos(10, 20, 30)
        env.add(10, 20, 30)
        val single = assertFailsWith<CodeEE> { env.del(5) }
        assertEquals("试图删除不存在的注册角色(5)", single.message)
        val pair = assertFailsWith<CodeEE> { env.del(0, 5) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", pair.message)
        assertEquals(3L, charaNum(env.store), "两次越界都不改表")
        // 三实参同样是数组参路径（分流只看个数，不看值）
        val triple = assertFailsWith<CodeEE> { env.del(0, 1, 9) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", triple.message)
    }

    /**
     * 分流**只在语句层**：直接调数组入口时，1 个元素的数组也走
     * `VariableEvaluator.cs:1251-1273` 的数组参路径（越界丢编号），不会再被内部转成单参。
     * 这条钉住「分流上移」——若有人把 `size == 1` 早退塞回 `deleteCharacters`，本用例必红
     * （会读到 `(5)` 而不是 `System.Int64[]`）。
     */
    @Test
    fun `数组入口单元素数组仍按数组语义 报数组串`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(5)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertEquals(3L, charaNum(s))
        // 对照：同一个越界编号经语句层（1 个实参）走单参入口，带编号
        val env = StmtEnv().templateNos(10, 20, 30)
        env.add(10, 20, 30)
        assertEquals(
            "试图删除不存在的注册角色(5)",
            assertFailsWith<CodeEE> { env.del(5) }.message,
        )
    }

    /** 零实参：`array.Length == 0` ⇒ `:1391-1394` 数组入口，`:1254` 循环零次 ⇒ 整体 no-op。 */
    @Test
    fun `语句层零实参走数组入口整体无操作`() {
        val env = StmtEnv().templateNos(10, 20)
        env.add(10, 20)
        env.del()
        assertEquals(2L, charaNum(env.store))
        assertEquals(listOf(10L, 20L), noSequence(env.store))
    }

    // ------------------------------------------------------------------
    // 单元 4 · 重复指定（VariableEvaluator.cs:1262-1265）
    // ------------------------------------------------------------------

    /**
     * 判重串 = `DuplicateDelChara`（zhs `lang.emuera-zhs.xml:315`），`:1264` 的 format
     * 实参是 `num.ToString()` ⇒ **这条原版没写坏，带编号**（与 :1259 的越界串相对照）。
     * 判重相位在越界判定**之后**（`:1257` 早于 `:1262`）且按实参顺序逐个判：
     * `0 1 0` 的第三个元素才撞上重复。
     */
    @Test
    fun `数组参重复指定 报编号的原文串`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 1, 0)) }
        assertEquals("相同的注册角色编号(0)被重复指定", e.message)
        assertEquals(3L, charaNum(s), "抛在 :1264，Remove 循环 :1269 不可达")
        assertEquals(listOf(10L, 20L, 30L), noSequence(s))
    }

    /**
     * 同一元素上**越界优先于判重**（`:1257` 在 `:1262` 之前）：`9 9` 撞的是越界分支
     * （编号 9 越界），不是重复分支 ⇒ 上屏 `System.Int64[]`。
     * 旧实现两分支都是自造串，读不出这个先后。
     */
    @Test
    fun `数组参同位置既越界又重复时越界分支优先`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(9, 9)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertEquals(3L, charaNum(s))
    }

    // ------------------------------------------------------------------
    // 单元 5 · 成功路径的顺序读数与次序等价性
    // ------------------------------------------------------------------

    /**
     * 正常删除后的顺序读数：摘掉 {1,3} 后余表必为 `10 20 50`（左移保序）。
     * 原版 `:1269-1272` 按**实参顺序**逐个 `Remove(item)`，移植侧
     * `VariableStore.deleteCharacters` 按**位置降序** removeAt；两者摘除的是同一组互异
     * 元素 ⇒ 余下的相对顺序与摘除次序无关（CharacterTable.removeAt 只做区间左移）。
     * 本用例把两种实参顺序钉成同一读数，就是这个等价性的观测量。
     */
    @Test
    fun `数组参正常删除后余表顺序与实参顺序无关`() {
        val a = storeWith(10, 20, 30, 40, 50)
        a.deleteCharacters(longArrayOf(3, 1))
        assertEquals(listOf(10L, 30L, 50L), noSequence(a))
        assertEquals(3L, charaNum(a))

        val b = storeWith(10, 20, 30, 40, 50)
        b.deleteCharacters(longArrayOf(1, 3))
        assertEquals(noSequence(a), noSequence(b), "摘除次序不改变余表（成功路径两侧等价）")
    }

    /**
     * 单参重载的余表读数（`:1248` RemoveAt 左移）+ 删除末尾元素的边界。
     */
    @Test
    fun `单参正常删除左移读数`() {
        val s = storeWith(10, 20, 30)
        s.deleteCharacter(1)
        assertEquals(listOf(10L, 30L), noSequence(s))
        s.deleteCharacter(1)
        assertEquals(listOf(10L), noSequence(s))
        assertEquals("试图删除不存在的注册角色(1)", assertFailsWith<CodeEE> { s.deleteCharacter(1) }.message)
    }

    /**
     * 越界判据取的是**删除前**的 Count（`:1257` 在循环内、`Remove` 在 `:1269` 之后）：
     * 4 只角色删 `{1,2,3}` 时，位置 3 仍按 Count=4 判定合法。若改成「边校验边删」
     * （count 递减）这里会误报越界 ⇒ 本用例钉住校验期不改表。
     */
    @Test
    fun `数组参校验期不缩表 位置按删除前的角色数判定`() {
        val s = storeWith(10, 20, 30, 40)
        s.deleteCharacters(longArrayOf(3, 2, 1))
        assertEquals(listOf(10L), noSequence(s))
        assertEquals(1L, charaNum(s))
    }

    /** 零实参形态：`:1387-1394` 走数组参重载，`:1254` 循环零次、`:1269` 空转 ⇒ 整体 no-op。 */
    @Test
    fun `数组参空数组整体无操作`() {
        val s = storeWith(10, 20)
        s.deleteCharacters(longArrayOf())
        assertEquals(listOf(10L, 20L), noSequence(s))
        assertEquals(2L, charaNum(s))
    }

    // ------------------------------------------------------------------
    // 单元 6 · 失败路径的 Dispose 相位：半死态取「不可观测」处置（B）
    // ------------------------------------------------------------------

    /**
     * **原版 :1266-1272 读完的结论**：原版是「边校验边 `Dispose`、全部校验通过后才
     * `Remove`」。于是在第 k 个元素抛 CodeEE（越界 `:1259` 或重复 `:1264`）的时刻，
     * 前 k 个元素**已经 Dispose 但仍在 CharacterList 里**——`CharacterData.Dispose`
     * （CharacterData.cs:756-770）只把本实例的 dataIntegerArray / dataStringArray /
     * dataIntegerArray2D 元素置 null，不写共享状态、不动 Count 与顺序。
     *
     * 处置 = **B（证明不可观测）**，本侧不表示半死态：
     * - 异常从 `DoInstruction`（Process.cs:1047）穿出 `runScriptProc`，由 `DoScript` 顶层
     *   catch（Process.cs:462-476）→ `handleException`（Process.cs:595-643）打印；
     *   `console.ThrowError`（EmueraConsole.cs:757-768）置 `ConsoleState.Error`，
     *   `RunEmueraProgram`（EmueraConsole.cs:1090-1100）在 Error 态不再调 `DoScript`
     *   ⇒ 同一会话里没有下一条语句能读到那半个表；
     * - 回标题 `GotoTitle`（EmueraConsole.cs:2570-2578）→ `BeginTitle`
     *   （Process.cs:480-485）→ `vEvaluator.ResetData()` 把半死表整体丢弃。
     * ⇒ 抛错瞬间唯一可观测的三件事（异常文案、CHARANUM、按编号读角色）在两侧一致。
     * 本用例钉这个读数（本侧校验期零副作用：前缀角色完好、照常可读）。
     *
     * 成功路径两侧等价（`Dispose` 不写共享状态、`Remove` 的次序不影响余表顺序）。
     */
    @Test
    fun `失败路径现状钉桩 抛错后前缀角色完好可读 原版此处已释放`() {
        val s = storeWith(10, 20, 30)
        assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 1, 9)) }
        // 前两个位置在原版已被 Dispose（:1267）但仍在表内；本侧零副作用，读数一致
        assertEquals(3L, charaNum(s))
        assertEquals(listOf(10L, 20L, 30L), noSequence(s))
        assertEquals("角色10", s.readStr("NAME", longArrayOf(0)))
        assertEquals("角色20", s.readStr("NAME", longArrayOf(1)))
        // 单参路径两侧无此分歧：`:1243` 的判定在 `:1247` 之前，抛错时没有任何 Dispose 发生
        assertFailsWith<CodeEE> { s.deleteCharacter(7) }
        assertEquals(listOf(10L, 20L, 30L), noSequence(s))
    }

    /**
     * 半死态（B：不可观测）的**三项可观测面**逐项钉死——失败路径的返回值、后续 CHARANUM、
     * 后续按编号取角色，以及「后续还能正常删」：
     * - 返回值：只有 CodeEE，文案走 `:1259` 的固定串（编号丢失，ADR-005）；
     * - 后续 CHARANUM：`:1269-1272` 的 Remove 循环不可达 ⇒ 不缩表；
     * - 后续按编号取角色：前缀 0 号在原版已被 Dispose（`:1267`），但原版在 Error 态不会再
     *   执行任何语句（Process.cs:462-476 / EmueraConsole.cs:757-768 / :1090-1100）⇒ 不可观测；
     *   本侧该位置完整可读，读数与「未 Dispose」相同；
     * - 后续删除：表里没有「已死」占位，`DELCHARA 0` 照常成功并左移。
     */
    @Test
    fun `失败路径半死态不可观测 返回值CHARANUM与按编号取角色均不变`() {
        val s = storeWith(10, 20, 30)
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 9)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertEquals(3L, charaNum(s))
        // 按编号取角色（原版 :1267 已 Dispose 的前缀位）
        assertEquals(10L, s.readInt("NO", longArrayOf(0)))
        assertEquals("角色10", s.readStr("NAME", longArrayOf(0)))
        assertEquals(20L, s.readInt("NO", longArrayOf(1)))
        assertEquals(30L, s.readInt("NO", longArrayOf(2)))
        // 表里没有半死占位：后续删除照常
        s.deleteCharacter(0)
        assertEquals(2L, charaNum(s))
        assertEquals(listOf(20L, 30L), noSequence(s))
    }

    /**
     * 判重依据的口径差（`:1262-1265` 按**对象**、移植侧按**位置**）：
     * 原版 CharacterList 的每个元素都由 `new CharacterData(...)` 产生
     * （`:1209/:1220/:1231/:1238/:2315` 配合 `:2310` 的 new、`:2449/:2634`；
     * `COPYCHARA` 只是 `CopyTo` 值拷贝 `:1366`，不把实例搬进表），
     * CharacterData 又没覆写 Equals ⇒ 可达状态里「按对象判重」与「按位置判重」同解。
     * 本用例钉的就是这个同解读数；若日后出现别名角色（同实例多位置），本用例不够、
     * 需按 :1262 改成对象判重 ⇒ TODO(verify)（卡点：SoA 角色表表达不了实例别名）。
     */
    @Test
    fun `数组参重复位置判重读数与原版对象判重一致`() {
        val s = storeWith(10, 20, 30)
        assertEquals(
            "相同的注册角色编号(2)被重复指定",
            assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(2, 0, 2)) }.message,
        )
        // 位置互异但值域合法：原版不会因「对象判重」误报（列表里没有别名）
        s.deleteCharacters(longArrayOf(2, 0, 1))
        assertEquals(0L, charaNum(s))
    }
}

/**
 * DELCHARA 的 INT_ANY 实参形态（`FunctionIdentifier.cs:1352` 的 ArgumentParser 选型；
 * 移植侧入参形状同 `core/sem/StatementExecutor.kt:599-601`）。
 * 文件私有，与 `core/stmt/StmtFamilyAuditTest.kt`、`core/fn/IsaGapTest.kt` 的同名
 * 文件私有辅助互不冲突。
 */
private fun charaArg(vararg nos: Long) =
    ExpressionArrayArgument(nos.map { IntLiteral(it) as Expr? })
