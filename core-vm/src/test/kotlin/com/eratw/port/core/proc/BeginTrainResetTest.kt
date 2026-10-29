package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ProcessRuntime.beginTrainReset`（`VariableEvaluator.UpdateInBeginTrain`，
 * `VariableEvaluator.cs:1668-1718`）的定向单测 —— R12 登记高缺陷：BEGIN TRAIN 复位
 * 曾只复位 3 个标量，漏掉全局 TFLAG/TSTR 与逐角色 6 个整数段 + STAIN 默认值。
 *
 * 触发路径 = 真实系统状态机：`beginStep(BeginType.TRAIN)` → `BeginFlow.select` 置
 * `Train_Begin` → `stepTrainBegin` → `calls.beginTrainReset()`（`BeginFlow.kt:812`）。
 * 配置口径与生产同一条现读通道（`ConfigMethods.store(store.aux, host)`，
 * 经 `BuiltinHost.configOverrides` 注入，与 CompatiCallConfigTest 同形）。
 */
class BeginTrainResetTest {

    private companion object {
        /** `ConfigData.cs:169` 的英文标签（GAME 组认英文，与 emuera.config 落盘同形）。 */
        const val STAIN_KEY = "Default Stain"

        /** `ConfigData.cs:169` 的出厂默认（StainDefault）。 */
        val DEFAULT_STAIN = listOf(0L, 0L, 2L, 1L, 8L)
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    /** 只有 main 一条 `_HALT` 的程序（复位在构造后的 BEGIN 步触发，无需其它字节码）。 */
    private fun haltProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        return b.build(0)
    }

    private fun runtime(
        store: VariableStore,
        overrides: Map<String, String> = emptyMap(),
    ): ProcessRuntime {
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = overrides
        }
        return ProcessRuntime(haltProgram(), store, FunctionTable(emptyList()), VmConfig(), builtinHost = host)
    }

    /** 一名角色（模板无 CSV 列 ⇒ 初始各整数段为 0）。 */
    private fun oneCharaStore(): VariableStore {
        val store = VariableStore()
        store.registerTemplate(CharacterTemplate(no = 0L, name = "霊夢", callname = ""))
        store.addCharacter(0L)
        return store
    }

    /** 变量（含角色列）的元素数。 */
    private fun elemCount(store: VariableStore, name: String): Int =
        store.registry.def(name)!!.dims.first()

    private fun charaRead(store: VariableStore, name: String, pos: Int, elem: Int): Long =
        store.readInt(name, longArrayOf(pos.toLong(), elem.toLong()))

    private fun charaWrite(store: VariableStore, name: String, pos: Int, elem: Int, v: Long) =
        store.writeInt(name, longArrayOf(pos.toLong(), elem.toLong()), v)

    /** 整列全部写成 [v]。 */
    private fun fillChara(store: VariableStore, name: String, pos: Int, v: Long) {
        for (e in 0 until elemCount(store, name)) charaWrite(store, name, pos, e, v)
    }

    // ------------------------------------------------------------------
    // 1) 全局 TFLAG 整表清 0（VariableEvaluator.cs:1673-1676）
    // ------------------------------------------------------------------

    @Test
    fun `BEGIN TRAIN 将全局 TFLAG 整表复位为 0`() {
        val store = oneCharaStore()
        val n = elemCount(store, "TFLAG")
        store.writeInt("TFLAG", longArrayOf(0L), 7L)
        store.writeInt("TFLAG", longArrayOf((n - 1).toLong()), 9L)
        store.writeInt("TFLAG", longArrayOf((n / 2).toLong()), 3L)

        runtime(store).beginStep(BeginType.TRAIN)

        assertEquals(0L, store.readInt("TFLAG", longArrayOf(0L)), ":1674 逐元素清 0")
        assertEquals(0L, store.readInt("TFLAG", longArrayOf((n / 2).toLong())), ":1674 逐元素清 0")
        assertEquals(0L, store.readInt("TFLAG", longArrayOf((n - 1).toLong())), ":1674 整表覆盖到末位")
    }

    // ------------------------------------------------------------------
    // 2) 全局 TSTR 整表清空串（VariableEvaluator.cs:1677-1680）
    // ------------------------------------------------------------------

    @Test
    fun `BEGIN TRAIN 将全局 TSTR 整表复位为空串`() {
        val store = oneCharaStore()
        val n = elemCount(store, "TSTR")
        store.writeStr("TSTR", longArrayOf(0L), "x")
        store.writeStr("TSTR", longArrayOf((n - 1).toLong()), "y")

        runtime(store).beginStep(BeginType.TRAIN)

        assertEquals("", store.readStr("TSTR", longArrayOf(0L)), ":1679 逐元素清空串")
        assertEquals("", store.readStr("TSTR", longArrayOf((n - 1).toLong())), ":1679 整表覆盖到末位")
    }

    // ------------------------------------------------------------------
    // 3) 逐角色 GOTJUEL/TEQUIP/EX/PALAM/SOURCE/TCVAR 全清
    //    （VariableEvaluator.cs:1681-1699、:1704-1717）
    // ------------------------------------------------------------------

    @Test
    fun `BEGIN TRAIN 逐角色清空 GOTJUEL TEQUIP EX PALAM SOURCE TCVAR`() {
        val store = oneCharaStore()
        val names = listOf("GOTJUEL", "TEQUIP", "EX", "PALAM", "SOURCE", "TCVAR")
        for (name in names) fillChara(store, name, 0, 5L)

        runtime(store).beginStep(BeginType.TRAIN)

        for (name in names) {
            val n = elemCount(store, name)
            assertEquals(0L, charaRead(store, name, 0, 0), "$name 首元素清 0")
            assertEquals(0L, charaRead(store, name, 0, n - 1), "$name 末元素清 0")
        }
    }

    // ------------------------------------------------------------------
    // 4) STAIN 复位为 Config.StainDefault（VariableEvaluator.cs:1700 → :1903-1918）
    // ------------------------------------------------------------------

    @Test
    fun `BEGIN TRAIN 将角色 STAIN 复位为 Config 的 StainDefault`() {
        val store = oneCharaStore()
        fillChara(store, "STAIN", 0, 5L) // 先污染

        runtime(store, mapOf(STAIN_KEY to "9/8/7")).beginStep(BeginType.TRAIN)

        val n = elemCount(store, "STAIN")
        val expected = listOf(9L, 8L, 7L)
        for (e in 0 until n) {
            assertEquals(
                expected.getOrElse(e) { 0L },
                charaRead(store, "STAIN", 0, e),
                ":1906-1918 array[i] = i < Count ? StainDefault[i] : 0（超出列表高位归零）",
            )
        }
    }

    @Test
    fun `BEGIN TRAIN 未覆盖配置时 STAIN 取出厂默认 StainDefault`() {
        val store = oneCharaStore()
        fillChara(store, "STAIN", 0, 5L)

        runtime(store).beginStep(BeginType.TRAIN)

        val n = elemCount(store, "STAIN")
        for (e in 0 until n) {
            assertEquals(
                DEFAULT_STAIN.getOrElse(e) { 0L },
                charaRead(store, "STAIN", 0, e),
                "ConfigData.cs:169 默认 {0,0,2,1,8}，其余归零",
            )
        }
    }
}
