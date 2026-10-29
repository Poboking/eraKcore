package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SAVE-09a：`CompatiCALLNAME` 与 `CompatiCallEvent` 两键的运行期行为分叉（YES / NO 两态）。
 *
 * ## 原版口径（逐条 `文件:行号`，reference/emuera-cs 只读）
 *
 * **CompatiCALLNAME**（`ConfigCode.cs:56` = 52；条目出厂默认 false `ConfigData.cs:96`；
 * 回填点 `Config.cs:366`）：
 * - 捕进 `useCompatiName`：`ConstantData.cs:168`（ConstantData 构造期）；
 * - 唯一运行期效果：`ConstantData.cs:1263-1272` —— 全部 CHARA CSV 装载完、`SetSpFlag`
 *   与重号检查之前，逐模板 `if (string.IsNullOrEmpty(tmpl.Callname)) tmpl.Callname = tmpl.Name`
 *   （任务锚点 `:1268` = 内层 if 左花括号，赋值在 `:1269`）。
 *
 * **CompatiCallEvent**（`ConfigCode.cs:75` = 71；出厂默认 false `ConfigData.cs:105`；
 * 回填点 `Config.cs:376`）：运行期行为开关只有 LabelDictionary 两处「把事件标签同时登记进
 * noneventLabelDic」——`:70-73`（SortLabel 增量）与 `:181-184`（SortLabels 全量重排，
 * ErbLoader.cs:574，最终态由它决定）。下游读者（GetNonEventLabel，LabelDictionary.cs:359-366）：
 * - `CalledFunction.cs:93-114`（CallFunction）：命中即当普通函数建帧，只执行 `value2[0]`
 *   （CompareTo 排序首条，FunctionLabelLine.cs:85-96）；未命中且是事件名 ⇒
 *   CallToEventFunc（`:100-103`）；命中但是方法 ⇒ CallToUserFunc（`:106-109`）；
 * - `IdentifierDictionary.cs:554-575`（GetFunctionMethod，userDefinedOnly）：
 *   非方法 ⇒ CallfNonMethodFunc（`:557-560`）、方法 ⇒ 可调用（`:561-564`）、
 *   查不到 ⇒ null（`:572-575`）；
 * - 符号查询族：EXISTFUNCTION（FunctionMethodCreator.cs:8248-8280 / :8282-8302）、
 *   GETMETH / GETMETHS（`:8555-8569` / :8597-8611）、EXISTMETH（`:8630-8637` 捕获成 0）、
 *   ENUMFUNC 族（NoneventKeys，LabelDictionary.cs:30）；
 * - `CalledFunction.cs:62-77`（CallEventFunction）查 eventLabelDic 在前 ⇒ 事件派发不受影响。
 *
 * ## 本语料现值（2026-10-24 实测，只读语料）
 *
 * - `emuera.config:50` = `USE NAME IF CALLNAME IS EMPTY:YES`；`CSV/Chara` 下 168 个
 *   CHARA CSV 全部有非空 `呼び名` 行 ⇒ 回填零命中，生产行为与 NO 无异，基准读数不动；
 * - `emuera.config:58` = `ALLOW CALL ON EVENT FUNCTIONS:NO` ⇒ 分叉整体关闭。
 * 两态语义仍由本文件逐条钉住（覆盖表经 `BuiltinHost.configOverrides` 注入，与
 * emuera.config 落盘同形，走 ConfigMethods.storeFromHost 同一条现读通道）。
 *
 * ## 移植侧相位差（登记）
 *
 * 原版改**模板**（CSV 装载期），移植侧 `ProcessRuntime.applyCompatiCallname` 改**已入列
 * 角色的列**（会话装配收尾的 init 块）。ADDCHARA 新建角色 / CSVCALLNAME / 模板名反查
 * 三处仍取模板原值；本语料 168 模板呼び名全非空 ⇒ 不可观测（详见该方法 KDoc）。
 */
class CompatiCallConfigTest {

    private companion object {
        /** `ConfigData.cs:96` 的英文标签（emuera.config 落盘形态，GAME 组认英文）。 */
        const val CALLNAME_KEY = "USE NAME IF CALLNAME IS EMPTY"

        /** `ConfigData.cs:105` 的英文标签。 */
        const val CALL_EVENT_KEY = "ALLOW CALL ON EVENT FUNCTIONS"
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    /** 只有 main 一条 `_HALT` 的程序（CALLNAME 用：构造期 init 块即完成回填，无需执行）。 */
    private fun haltProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        return b.build(0)
    }

    /**
     * 事件程序：main(_HALT) + EVENTTRAIN 两个成员（EV1 印 M1、EV2 印 M2）+
     * EVENTLOAD 一个**方法**成员（EVLOAD 印 EM）。函数下标 = 声明序：
     * main=0、EV1=1、EV2=2、EVLOAD=3。
     */
    private fun eventProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)

        val pc1 = b.here()
        val ev1 = b.declareFunction("EV1", pc1, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("M1"), 1, 0, 2)
        b.emit(Opcode._RET, 0, 0, 0, 3)
        b.setFunctionSlots(ev1, 1)

        val pc2 = b.here()
        val ev2 = b.declareFunction("EV2", pc2, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("M2"), 1, 0, 4)
        b.emit(Opcode._RET, 0, 0, 0, 5)
        b.setFunctionSlots(ev2, 1)

        val pc3 = b.here()
        val evload = b.declareFunction("EVLOAD", pc3, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("EM"), 1, 0, 6)
        b.emit(Opcode._RET, 0, 0, 0, 7)
        b.setFunctionSlots(evload, 1)

        return b.build(0)
    }

    /** EVENTTRAIN 两成员 + EVENTLOAD 一方法成员；defIndex 即装载序（首条 = defIndex 最小）。 */
    private fun eventMetas(): List<FuncMeta> = listOf(
        FuncMeta(index = 0, key = "MAIN", defIndex = 0),
        FuncMeta(index = 1, key = "EVENTTRAIN", isEvent = true, defIndex = 10),
        FuncMeta(index = 2, key = "EVENTTRAIN", isEvent = true, defIndex = 20),
        FuncMeta(index = 3, key = "EVENTLOAD", isEvent = true, isMethod = true, defIndex = 30),
    )

    /**
     * `_CALL_BY_NAME` 动态名程序（契约 C5 的 VM 路径，b=0 无实参无 try 位）：
     * main 把名字写进 R0 后按名调用，命中则被调打印、返回后打印 AFTER。
     * 函数下标：main=0、EV1=1。
     */
    private fun callByNameProgram(name: String): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string(name), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), 0, SlotAddr.NONE, 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        b.setFunctionSlots(main, 2)

        val pc = b.here()
        val ev1 = b.declareFunction("EV1", pc, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("M1"), 1, 0, 5)
        b.emit(Opcode._RET, 0, 0, 0, 6)
        b.setFunctionSlots(ev1, 1)
        return b.build(0)
    }

    /**
     * 装配运行时：`overrides` 即宿主侧配置覆盖表（英文标签 → 值，与 emuera.config
     * 落盘同形），经 `ConfigMethods.storeFromHost` 落进本 VM 条目表 —— 与被测的现读通道
     * （`ProcessRuntime.configBool` → `ConfigMethods.store(store.aux, host)`）同一条路。
     */
    private fun runtime(
        program: Program,
        store: VariableStore,
        metas: List<FuncMeta>,
        overrides: Map<String, String> = emptyMap(),
    ): ProcessRuntime {
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = overrides
        }
        return ProcessRuntime(program, store, FunctionTable(metas), VmConfig(), builtinHost = host)
    }

    /** 一名角色：NAME 非空、CALLNAME 空（回填用例的种子）。 */
    private fun emptyCallnameStore(name: String = "霊夢"): VariableStore {
        val store = VariableStore()
        store.registerTemplate(CharacterTemplate(no = 0L, name = name, callname = ""))
        store.addCharacter(0L)
        return store
    }

    private fun callnameOf(store: VariableStore, pos: Int = 0): String =
        store.readStr("CALLNAME", longArrayOf(pos.toLong()))

    // ------------------------------------------------------------------
    // 1) CompatiCALLNAME：ConstantData.cs:168 → :1263-1272
    // ------------------------------------------------------------------

    @Test
    fun `CompatiCALLNAME YES 空 CALLNAME 角色列回填 NAME 且重复调用幂等`() {
        val store = emptyCallnameStore()
        // 开关在 ProcessRuntime 构造收尾的 init 块现读（ConstantData.cs:168 捕开关 →
        // :1263-1272 逐模板回填；本语料 emuera.config:50 = YES）
        val rt = runtime(haltProgram(), store, emptyList(), mapOf(CALLNAME_KEY to "YES"))
        assertEquals("霊夢", store.readStr("NAME", longArrayOf(0)), "种子角色 NAME")
        assertEquals("霊夢", callnameOf(store), "ConstantData.cs:1269：空 Callname 捕 Name")
        assertEquals(0, rt.applyCompatiCallname(), "二次调用零命中（已回填过）")
    }

    @Test
    fun `CompatiCALLNAME NO 空 CALLNAME 保持空`() {
        val store = emptyCallnameStore()
        // 无覆盖 = 条目出厂默认 false（ConfigData.cs:96）
        val rt = runtime(haltProgram(), store, emptyList())
        assertEquals("", callnameOf(store), "开关关闭不回填（ConstantData.cs:1263 的 if 不进入）")
        assertEquals(0, rt.applyCompatiCallname(), "关闭态显式调用同样零命中")
    }

    @Test
    fun `CompatiCALLNAME YES 非空 CALLNAME 不被 NAME 覆盖`() {
        val store = VariableStore()
        store.registerTemplate(CharacterTemplate(no = 0L, name = "霊夢", callname = "先代"))
        store.addCharacter(0L)
        runtime(haltProgram(), store, emptyList(), mapOf(CALLNAME_KEY to "YES"))
        assertEquals("先代", callnameOf(store), "ConstantData.cs:1267：仅 IsNullOrEmpty 才赋值")
    }

    @Test
    fun `CompatiCALLNAME YES NAME 也空时 CALLNAME 仍空`() {
        val store = emptyCallnameStore(name = "")
        runtime(haltProgram(), store, emptyList(), mapOf(CALLNAME_KEY to "YES"))
        // 原版此时执行 `Callname = Name`（空串赋空串，可见状态不变）；移植侧跳过写入，
        // 终态一致（ConstantData.cs:1269）。
        assertEquals("", callnameOf(store))
    }

    // ------------------------------------------------------------------
    // 2) CompatiCallEvent=YES：CALL / TRYCALL 走普通调用（LabelDictionary.cs:181-184 +
    //    CalledFunction.cs:93-114），只执行 value2[0]（= 装载最早成员）
    // ------------------------------------------------------------------

    @Test
    fun `CallEvent YES CALL 事件名只执行装载最早成员`() {
        val rt = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        rt.callFunction("EVENTTRAIN")
        // CalledFunction.cs:110-114 以 value2[0]（defIndex=10 的 EV1）建帧，IsEvent=false
        // ⇒ 只执行那一条成员；EV2（defIndex=20）不执行。
        assertEquals("M1\n", rt.output.toString())
    }

    @Test
    fun `CallEvent NO CALL 事件名抛 CallToEventFunc 原文`() {
        val rt = runtime(eventProgram(), VariableStore(), eventMetas())
        val e = assertFailsWith<CodeEE> { rt.callFunction("EVENTTRAIN") }
        // CalledFunction.cs:100-103（CallToEventFunc）：GetNonEventLabel 查不到、是事件名即抛
        assertTrue(e.message!!.contains("对事件函数\"@EVENTTRAIN\"进行CALL调用"), e.message ?: "")
    }

    @Test
    fun `CallEvent YES TRYCALL 事件名执行首成员 NO 时 try 位不豁免照抛`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        yes.tryCall("EVENTTRAIN")
        assertEquals("M1\n", yes.output.toString(), "YES：与普通函数同路（CalledFunction.cs:93-114）")

        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        // 抛错发生在 isTry 判断之前（FunctionIdentifier.cs:3575 查表就抛）⇒ try 吞不掉
        val e = assertFailsWith<CodeEE> { no.tryCall("EVENTTRAIN") }
        assertTrue(e.message!!.contains("对事件函数\"@EVENTTRAIN\"进行CALL调用"), e.message ?: "")
    }

    @Test
    fun `CallEvent YES CALLEVENT 派发不受影响仍执行全部成员`() {
        val rt = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        assertTrue(rt.executeEvent("EVENTTRAIN"))
        // CalledFunction.cs:62-77（CallEventFunction）先查 eventLabelDic 仍命中 ⇒ 派发语义
        // 与开关无关：两成员按 ONLY→PRI→NORMAL→LATER 全执行。
        assertEquals("M1\nM2\n", rt.output.toString())
    }

    @Test
    fun `CallEvent YES CALLFORM 动态名走普通调用只执行首成员`() {
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN", defIndex = 0),
            FuncMeta(index = 1, key = "EVENTTRAIN", isEvent = true, defIndex = 10),
        )
        val rt = runtime(callByNameProgram("EVENTTRAIN"), VariableStore(), metas, mapOf(CALL_EVENT_KEY to "YES"))
        rt.execute()
        // _CALL_BY_NAME → resolveFunctionIndex → eventAsNonEvent（LabelDictionary.cs:181-184
        // + CalledFunction.cs:93-114）：命中建帧执行 EV1，返回后 main 继续打印 AFTER。
        assertEquals("M1\nAFTER\n", rt.output.toString())
    }

    // ------------------------------------------------------------------
    // 3) CompatiCallEvent=YES：CALLF 家族（IdentifierDictionary.cs:554-575）
    // ------------------------------------------------------------------

    @Test
    fun `CallEvent YES CALLF 事件名抛非方法错 NO 报没有找到行内函数`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        val e1 = assertFailsWith<CodeEE> { yes.callF("EVENTTRAIN") }
        // IdentifierDictionary.cs:557-560（CallfNonMethodFunc）：GetNonEventLabel 命中 +
        // IsMethod 为假
        assertTrue(e1.message!!.contains("使用了CALLF系列的命令进行调用"), e1.message ?: "")

        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        val e2 = assertFailsWith<CodeEE> { no.callF("EVENTTRAIN") }
        // NO：GetFunctionMethod 返回 null（:572-575）⇒ NotDefinedUserFunc
        // （FunctionIdentifier.cs:1161-1163，zhs:507，不带 @ 前缀）
        assertEquals("没有找到行内函数\"EVENTTRAIN\"", e2.message)
    }

    @Test
    fun `CallEvent YES TRYCALLF 事件名照抛 NO 静默跳过`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        // TRYCALLF 的 DoInstruction 对 GetFunctionMethod 的抛出不捕获
        // （FunctionIdentifier.cs:1271-1285；只有装载期 SetJumpTo catch，:1264-1267）
        val e = assertFailsWith<CodeEE> { yes.tryCallF("EVENTTRAIN") }
        assertTrue(e.message!!.contains("使用了CALLF系列的命令进行调用"), e.message ?: "")

        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        assertFalse(no.tryCallF("EVENTTRAIN"), "NO：事件名查不到 ⇒ 静默跳过（:572-575 → null）")
    }

    @Test
    fun `CallEvent YES 事件方法可被 CALLF 执行而 CALL 抛方法错`() {
        val rt = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        // 事件标签带 #FUNCTION：GetNonEventLabel 命中且 IsMethod ⇒ UserDefinedMethodTerm
        // （IdentifierDictionary.cs:561-564）⇒ TRYCALLF/CALLF 可执行
        assertTrue(rt.tryCallF("EVENTLOAD"))
        assertEquals("EM\n", rt.output.toString())

        // 同一方法名走 CALL：CalledFunction.cs:106-109（CallToUserFunc）——事件表分支同样拒
        val e = assertFailsWith<CodeEE> { rt.callFunction("EVENTLOAD") }
        assertTrue(e.message!!.contains("#FUCNTION(S)的函数\"@EVENTLOAD\""), e.message ?: "")
    }

    // ------------------------------------------------------------------
    // 4) CompatiCallEvent=YES：符号查询族（FunctionMethodCreator.cs:8248-8304 /
    //    :8555-8611 / :8630-8637；源 = NoneventKeys，LabelDictionary.cs:30）
    // ------------------------------------------------------------------

    @Test
    fun `CallEvent YES EXISTFUNCTION 事件名答 1 事件方法答 2 NO 答 0`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        // FunctionMethodCreator.cs:8248-8280：GetNonEventLabel 非空 + 非方法 ⇒ 1
        assertEquals(Value.IntV(1L), yes.evalContext.callFunction("EXISTFUNCTION", listOf(Value.StrV("EVENTTRAIN"))))
        // :8269-8280：IsMethod + MethodType=long ⇒ 2（#FUNCTIONS 才是 3）
        assertEquals(Value.IntV(2L), yes.evalContext.callFunction("EXISTFUNCTION", listOf(Value.StrV("EVENTLOAD"))))

        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        assertEquals(Value.IntV(0L), no.evalContext.callFunction("EXISTFUNCTION", listOf(Value.StrV("EVENTTRAIN"))))
        assertEquals(Value.IntV(0L), no.evalContext.callFunction("EXISTFUNCTION", listOf(Value.StrV("EVENTLOAD"))))
    }

    @Test
    fun `CallEvent YES EXISTMETH 事件名答 0`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        // FunctionMethodCreator.cs:8630-8637：CallfNonMethodFunc 被 ExistMethMethod 捕获成 0
        assertEquals(Value.IntV(0L), yes.evalContext.callFunction("EXISTMETH", listOf(Value.StrV("EVENTTRAIN"))))
        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        assertEquals(Value.IntV(0L), no.evalContext.callFunction("EXISTMETH", listOf(Value.StrV("EVENTTRAIN"))))
    }

    @Test
    fun `CallEvent YES GETMETH 事件名抛错 NO 取默认值`() {
        val yes = runtime(eventProgram(), VariableStore(), eventMetas(), mapOf(CALL_EVENT_KEY to "YES"))
        // FunctionMethodCreator.cs:8555-8569：GetFunctionMethod 对非方法事件名抛
        // CallfNonMethodFunc，GetMethMethod 不捕获（:557-560 口径）
        val e = assertFailsWith<CodeEE> {
            yes.evalContext.callFunction("GETMETH", listOf(Value.StrV("EVENTTRAIN"), Value.IntV(42L)))
        }
        assertTrue(e.message!!.contains("不是"), e.message ?: "")

        val no = runtime(eventProgram(), VariableStore(), eventMetas())
        // :8561-8565：查不到才求默认值
        assertEquals(
            Value.IntV(42L),
            no.evalContext.callFunction("GETMETH", listOf(Value.StrV("EVENTTRAIN"), Value.IntV(42L))),
        )
    }
}
