package com.eratw.port.core.fn

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 红 E（DEMO 不可跳）的行为锁 —— HOST-04 修复链的 headless 复现。
 *
 * ## 现象与根因
 * 新局标题 20s 无输入自动进 BADAPPLE 演示（6573 帧 ≈ 110 分钟不可跳）。根因是标题循环的
 * 跳出条件恒假（TITLE.ERB 语料行号 grep -n 实测为 157）：
 * `IF GETTEXTBOX() != "" || GETKEYTRIGGERED(1) || GETKEYTRIGGERED(13) || GETKEYTRIGGERED(27)`
 * —— 旧 [ConsoleBuiltinHost] 的 inputState 恒 [IdleInputState] ⇒ isActive=false
 * ⇒ keyStateOf 首门就归零（FunctionMethodCreator.cs:7816-7819）。
 *
 * ## HOST-04 修复链（本测试逐段钉住）
 * 1. [KeyEventInputState]：离散按键事件 → Win32 GetKeyState 读数（bit15 按下、bit0 沿变；
 *    短按存记不丢，触屏「提交即回车」的一次性事件因此可见）。
 * 2. `InputMethods.keyStateOf`：GETKEY / GETKEYTRIGGERED 判定逐行对照 C#
 *    `GetKeyStateMethod.GetIntValue`（FunctionMethodCreator.cs:7814-7838）。
 * 3. `GameSession(inputState = …)` → `ConsoleBuiltinHost(inputState = …)` 注入点；
 *    真机侧 `GameHost.keyInput` 接 Activity 按键与触屏左键沿变（app 层，只读不碰）。
 *
 * ## 三层装置（自底向上）
 * - **函数层**：真 [BuiltinEvalContext] + 真 [BuiltinHost]（构造照同模块
 *   [KeyEventInputStateTest] 先例），直调 GETKEY / GETKEYTRIGGERED。
 * - **表达式层**：真 [LineParser] 解析 TITLE.ERB:157 原文得 [IfStmt]，求值
 *   `condition.getIntValue` —— `||` 短路序与 C# 一致（Expr.kt 的 OR 分支，
 *   spec: OperatorMethodManager.cs:393-409）。
 * - **引擎层**：真 [ErbProgramCompiler] + 真 [ProcessRuntime]，把同一条件编进 `@TITLE`
 *   函数体；每次 `execute()` = 标题循环「AWAIT 一帧后求值一次 IF」，帧间用
 *   onKeyDown / onKeyUp 注入事件。VM 的 `_EVAL_INT` 复用 `Expr.getIntValue`
 *   （Vm.kt:744-747），走的正是函数层那条链。
 *
 * ## 口径声明（ADR-005）
 * 「一次按下触发两次」是原版既有形态（沿变位本身 + 沿变位回落 2→1，即
 * FunctionMethodCreator.cs:7826-7833 的 keytoggle 比较），照复刻不修。keytoggle 表按
 * C# `static short[256]`（:9035-9039）的语义放在每 VM 附属状态（aux 槽 "keytoggle"），
 * 多次查询之间**没有**额外复位点 —— 与本测试的多帧用例互证。
 */
class DemoSkipKeyTest {

    // ==================================================================
    // 1. 函数层：GETKEY / GETKEYTRIGGERED 读数（spec: FunctionMethodCreator.cs:7814-7838）
    // ==================================================================

    @Test
    fun `标题三键无输入时全部静默`() {
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        for (vk in TITLE_KEYS) {
            assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", vk.toLong()), "未按键不得触发（:7833）")
            assertEquals(0L, fnInt(eval, "GETKEY", vk.toLong()), "未按键 GETKEY 为 0（:7837）")
        }
    }

    @Test
    fun `按下回车当帧即触发且按下位为真`() {
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(VK_RETURN)
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong()), "按下沿变本身即触发（:7833）")
        assertEquals(1L, fnInt(eval, "GETKEY", VK_RETURN.toLong()), "按住期间 GETKEY 恒 1（:7837）")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_LBUTTON.toLong()), "别的键不受影响（:7825 只读目标键）")
    }

    @Test
    fun `一次按住按原版形态触发两次后归静`() {
        // Win32 bit0 =「自上次 GetKeyState 之后被按下过」：keyStateOf 把 (keyState and 1)+1
        // 存进 keytoggle 并与上次读数比较（:7826-7833），一次按住因此触发两次（沿变位本身
        // 0→2、沿变位回落 2→1），第三次起静默。原版既有形态，照复刻（ADR-005）。
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(VK_ESCAPE)
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_ESCAPE.toLong()), "第一次：沿变位本身")
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_ESCAPE.toLong()), "第二次：沿变位回落 2→1")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_ESCAPE.toLong()), "之后静默，按住不重复刷")
        assertEquals(1L, fnInt(eval, "GETKEY", VK_ESCAPE.toLong()), "GETKEY 只看按下位（:7837）")
    }

    @Test
    fun `松开后按下位归零再按重新触发`() {
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(VK_RETURN)
        fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong())
        fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong())
        keys.onKeyUp(VK_RETURN)
        assertEquals(0L, fnInt(eval, "GETKEY", VK_RETURN.toLong()), "抬起后按下位归零（:7837）")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong()), "抬起且无新沿变不触发（:7833）")
        keys.onKeyDown(VK_RETURN)
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong()), "新一轮按下必须重新触发")
    }

    @Test
    fun `帧间短按被存记不丢且不谎报按住`() {
        // 触屏「提交即回车」是两次轮询之间完成的一次性事件：onKeyDown 后 onKeyUp，
        // 下一次查询仍须看到这次按下（否则 TITLE.ERB:157 永假），且已抬起不得报按住。
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(VK_ESCAPE)
        keys.onKeyUp(VK_ESCAPE)
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_ESCAPE.toLong()), "短按存记必须兑现给 GETKEYTRIGGERED")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_ESCAPE.toLong()), "存记只兑现一次")
        assertEquals(0L, fnInt(eval, "GETKEY", VK_ESCAPE.toLong()), "沿变已被查询消费，随后 GETKEY 不得谎报按住")
    }

    @Test
    fun `有效域外键码按原版归零`() {
        // spec: FunctionMethodCreator.cs:7821-7824 —— 有效域 0..255，越界在查键态之前归零
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(256)
        keys.onKeyDown(300)
        keys.onKeyDown(VK_RETURN)
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", 256L), "256 越界（:7821-7824）")
        assertEquals(0L, fnInt(eval, "GETKEY", 300L), "300 越界（:7821-7824）")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", -1L), "负数越界（:7821-7824）")
        assertEquals(1L, fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong()), "域内键不受越界查询影响")
    }

    @Test
    fun `窗口未激活时三键归零`() {
        // spec: FunctionMethodCreator.cs:7816-7819 —— IsActive 为假先归零，不查键态
        val keys = KeyEventInputState(activeProvider = { false })
        val eval = demoEvalOf(demoHostOf(keys))
        keys.onKeyDown(VK_RETURN)
        keys.onKeyDown(VK_LBUTTON)
        assertEquals(0L, fnInt(eval, "GETKEY", VK_RETURN.toLong()), "未激活 GETKEY 归零（:7816-7819）")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_RETURN.toLong()), "未激活 GETKEYTRIGGERED 归零（:7816-7819）")
        assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", VK_LBUTTON.toLong()), "未激活 GETKEYTRIGGERED 归零（:7816-7819）")
    }

    @Test
    fun `headless 缺省宿主三键恒零`() {
        // ConsoleBuiltinHost 缺省 inputState = IdleInputState（headless 零行为差）；
        // 这条同时是修复前「演示不可跳」的读数形态：isActive=false ⇒ 全部归零。
        val eval = demoEvalOf(demoHostOf(IdleInputState, DemoTextBox("")))
        for (vk in TITLE_KEYS) {
            assertEquals(0L, fnInt(eval, "GETKEYTRIGGERED", vk.toLong()), "空闲态不触发（:7816-7819）")
            assertEquals(0L, fnInt(eval, "GETKEY", vk.toLong()), "空闲态 GETKEY 归零（:7816-7819）")
        }
    }

    // ==================================================================
    // 2. 表达式层：TITLE.ERB:157 跳出条件（真 LineParser 解析原文）
    // ==================================================================

    @Test
    fun `TITLE 跳出条件原文可解析为 IF`() {
        val stmt = assertIs<IfStmt>(parseLine(TITLE_BREAK_CONDITION), "TITLE.ERB:157 必须是 IF 语句")
        assertEquals(FunctionCode.IF, stmt.code)
    }

    @Test
    fun `空闲后端下 TITLE 跳出条件恒假（红 E 复现）`() {
        // 修复前的形态：GETTEXTBOX 为空 + IdleInputState ⇒ 三个析取项全 0 ⇒ 永假。
        val stmt = assertIs<IfStmt>(parseLine(TITLE_BREAK_CONDITION))
        val eval = demoEvalOf(demoHostOf(IdleInputState, DemoTextBox("")))
        assertEquals(
            0L,
            stmt.condition.getIntValue(eval),
            "无输入后端时跳出条件必须恒假（FunctionMethodCreator.cs:7816-7819）",
        )
    }

    @Test
    fun `接入键后端后短按回车使跳出条件为真`() {
        val stmt = assertIs<IfStmt>(parseLine(TITLE_BREAK_CONDITION))
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys, DemoTextBox("")))
        keys.onKeyDown(VK_RETURN)
        keys.onKeyUp(VK_RETURN)
        assertEquals(
            1L,
            stmt.condition.getIntValue(eval),
            "短按回车后 GETKEYTRIGGERED(13) 置真 ⇒ 可跳离 DEMO（:7833）",
        )
    }

    @Test
    fun `触屏左键短按同样满足跳出条件`() {
        // AndroidKeyCodes.VK_LBUTTON = 0x01：GameHost 把触屏动作折算成左键沿变，
        // 对应 TITLE.ERB:157 的第一个析取项 GETKEYTRIGGERED(1)。
        val stmt = assertIs<IfStmt>(parseLine(TITLE_BREAK_CONDITION))
        val keys = KeyEventInputState()
        val eval = demoEvalOf(demoHostOf(keys, DemoTextBox("")))
        keys.onKeyDown(VK_LBUTTON)
        keys.onKeyUp(VK_LBUTTON)
        assertEquals(
            1L,
            stmt.condition.getIntValue(eval),
            "触屏左键短按同样可跳（:7833）",
        )
    }

    @Test
    fun `文本框有内容走另一条跳出路径且不消耗按键沿变`() {
        // GETTEXTBOX() != "" 为真时 || 短路（Expr.kt OR 分支，spec: OperatorMethodManager.cs:393-409）：
        // 右侧三个 GETKEYTRIGGERED 不求值 ⇒ 不推进 keytoggle。随后的空框查询必须仍然看得到
        // 之后发生的按键（证明短路没有偷跑沿变）。
        val box = DemoTextBox("玩家输入")
        val keys = KeyEventInputState()
        val stmt = assertIs<IfStmt>(parseLine(TITLE_BREAK_CONDITION))
        val eval = demoEvalOf(demoHostOf(keys, box))
        assertEquals(1L, stmt.condition.getIntValue(eval), "文本框非空即跳出（TITLE.ERB:157 首析取项）")
        box.setText("")
        assertEquals(0L, stmt.condition.getIntValue(eval), "清空后无按键必须回假")
        keys.onKeyDown(VK_RETURN)
        keys.onKeyUp(VK_RETURN)
        assertEquals(1L, stmt.condition.getIntValue(eval), "短路未消耗沿变：随后的按键仍可跳")
    }

    // ==================================================================
    // 3. 引擎层：真编译 + 真 VM 的标题循环帧轮询
    // ==================================================================

    @Test
    fun `标题循环连续多帧无输入不跳出`() {
        // 每帧一次 IF 求值（AWAIT 之后的查询点）：keytoggle 在查询间无额外复位，
        // 无按键时每帧都必须重算为假 —— 修复后仍然不能「自动跳出」。
        val frames = DemoTitleFrames(KeyEventInputState())
        repeat(6) { i ->
            assertEquals(0L, frames.frame(), "第 ${i + 1} 帧无输入不得跳出（TITLE.ERB:157）")
        }
    }

    @Test
    fun `帧间短按回车下一帧跳出`() {
        val keys = KeyEventInputState()
        val frames = DemoTitleFrames(keys)
        assertEquals(0L, frames.frame(), "第 1 帧无输入")
        assertEquals(0L, frames.frame(), "第 2 帧无输入")
        keys.onKeyDown(VK_RETURN)
        keys.onKeyUp(VK_RETURN)
        assertEquals(1L, frames.frame(), "帧间短按必须在下一帧查询可见（HOST-04 修复链）")
        assertEquals(1L, frames.frame(), "跳出标志落库后跨帧保持（J 留在 VariableStore）")
    }

    @Test
    fun `帧间短按触屏左键下一帧跳出`() {
        val keys = KeyEventInputState()
        val frames = DemoTitleFrames(keys)
        assertEquals(0L, frames.frame(), "第 1 帧无输入")
        keys.onKeyDown(VK_LBUTTON)
        keys.onKeyUp(VK_LBUTTON)
        assertEquals(1L, frames.frame(), "触屏左键沿变在下一帧可求值为真（AndroidKeyCodes.VK_LBUTTON=0x01）")
    }
}

// ---------------------------------------------------------------------------
// 装置（文件级 private，形态照同模块 KeyEventInputStateTest.kt 先例）
// ---------------------------------------------------------------------------

/** 表达式求值的基础语境（变量直通真实 [VariableStore]；函数一律走内建注册表）。 */
private class DemoBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DemoBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 文本框后端（GETTEXTBOX 的数据源）：文本可换，动作空转。
 *  [TextBoxHost.text] 是 val + 显式 [setText]：var 覆写会合成同 JVM 签名的 setter 撞车，
 *  所以用私有后备字段。 */
private class DemoTextBox(initial: String = "") : TextBoxHost {
    private var backing = initial
    override val text: String get() = backing
    override fun setText(text: String) {
        backing = text
    }

    override fun moveTextBox(x: Int, y: Int, width: Int) {}
    override fun resetTextBoxPos() {}
}

/** 只接输入后端（与文本框）的宿主，其余能力走接口缺省。 */
private fun demoHostOf(state: InputState, box: TextBoxHost? = null): BuiltinHost =
    object : BuiltinHost {
        override val inputState: InputState = state
        override val textBox: TextBoxHost? = box
    }

/** 函数层求值语境（构造照 [KeyEventInputStateTest] 先例）。 */
private fun demoEvalOf(host: BuiltinHost): BuiltinEvalContext {
    val store = VariableStore()
    val base = DemoBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

/** 直调一个内建整数函数。 */
private fun fnInt(eval: BuiltinEvalContext, name: String, vararg args: Long): Long =
    (eval.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

/** 解析一条 ERB 代码行（行号占位用 TITLE.ERB 实测行号）。 */
private fun parseLine(text: String): Statement? =
    LineParser(SymbolTable()).parseLogicalLine(LogicalLine(LineKind.CODE, "title-demo.erb", 157, text))

/**
 * 引擎层帧装置：TITLE.ERB:157 的跳出条件编进 `@TITLE`，每次 [frame] 跑一遍
 * （= 标题循环一次 AWAIT 之后的 IF 求值），返回跳出标志 J 的读数。
 *
 * [ProcessRuntime] 的 `builtinHost` 注入点让 VM 表达式求值拿到调用方同一个
 * [KeyEventInputState] 实例 —— 帧间注入的按键事件由此进入沿变 / keytoggle 链。
 */
private class DemoTitleFrames(private val keys: KeyEventInputState) {
    private val store = VariableStore()
    private val runtime = ProcessRuntime(
        program = compileTitle(),
        store = store,
        functionTable = FunctionTable(listOf(FuncMeta(index = 0, key = "TITLE", defIndex = 0))),
        config = VmConfig(maxStepsPerRun = 50_000L),
        builtinHost = demoHostOf(keys, DemoTextBox("")),
    )

    /** 跑一帧并读 J（IF 成立时置 1，落 VariableStore 跨帧保留）。 */
    fun frame(): Long {
        runtime.execute()
        return store.readInt("J", longArrayOf(0))
    }

    private fun compileTitle(): Program {
        val p = LineParser(SymbolTable())
        val stmts = listOf("@TITLE", TITLE_BREAK_CONDITION, "J = 1", "ENDIF")
            .mapIndexedNotNull { i, text ->
                val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
                p.parseLogicalLine(LogicalLine(kind, "title-demo.erb", i + 1, text))
            }
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TITLE"), stmts, setOf("TITLE"))
        return c.build("TITLE")
    }
}

/**
 * TITLE.ERB:157 的跳出条件原文（行尾空白已去；语料只读，行号 grep -n 实测）。
 *
 * 三个虚拟键码 = [VK_LBUTTON] / [VK_RETURN] / [VK_ESCAPE]，与 core/host/AndroidKeyCodes.kt
 * 的常量同值（0x01 / 0x0D / 0x1B）。
 */
private const val TITLE_BREAK_CONDITION =
    "IF GETTEXTBOX() != \"\" || GETKEYTRIGGERED(1) || GETKEYTRIGGERED(13) || GETKEYTRIGGERED(27)"

/** Win32 虚拟键码 VK_LBUTTON（触屏左键）。 */
private const val VK_LBUTTON = 1

/** Win32 虚拟键码 VK_RETURN。 */
private const val VK_RETURN = 13

/** Win32 虚拟键码 VK_ESCAPE。 */
private const val VK_ESCAPE = 27

/** TITLE.ERB:157 跳出条件涉及的三个键（遍历用）。 */
private val TITLE_KEYS = listOf(VK_LBUTTON, VK_RETURN, VK_ESCAPE)
