package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.ExecResult
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M4-L `l-stmtaudit`：BEGIN / CALL / CHARA 三族语句逐条 C# 对拍（CONTRACT-M4-K §2.2 +
 * CONTRACT-M4-L §1 的 `l-stmtaudit` 行）。起点 = main@8fd3fb3。
 *
 * 本文件是**对拍审计产物**，不是补功能：产品侧零改动，所有分歧按「已写回归 / 需组长接线 /
 * 登记不修正」三分法处置。C# 主源根 = `reference/emuera-cs/`，语句类体在
 * `MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs`（注意：不在
 * `MinorShift.Emuera.Runtime.Script.Statements/` 下，M4-K §2.2 的写法是简称）。
 *
 * ## P0 · resetStyle 四处调用点查实（已复核，逐点带行号）
 *
 * 复位集恰四项 = 前景 / 样式位 / 字体名 / 对齐；**不含** bgColor、redraw、行缓冲态。
 * 依据：`EmueraConsole.cs:2838-2842`（方法体两句：`style = defaultStyle;` 与 `alignment = LEFT;`）
 * + `EmueraConsole.cs:206`（`defaultStyle` 出厂值）+ `StringStyle.cs:20-27`（四项字段）；
 * bgColor 是 `EmueraConsole.cs:200` 的独立字段、redraw 是 `:455`，都不在方法体里。
 *
 * | 调用点 | C# 出处 | 原版复位 | 移植侧现状（main@8fd3fb3） |
 * |---|---|---|---|
 * | BEGIN | `FunctionIdentifier.cs:2935`（类体 2922-2937：`:2933` SetBegin → `:2934` Return(0) → `:2935` ResetStyle） | 是，第 3 句 | **已接**：`core/proc/ProcessRuntime.kt:501`（`ExecResult.Begin` 分支，注释自证 :498-500） |
 * | FORCE_BEGIN | `FunctionIdentifier.cs:2952`（类体 2939-2954，方法体与 BEGIN 逐句相同） | 是 | **已接**：语句层两条语句同产 `ExecResult.Begin`（`IsaGapTest` 已钉）⇒ 同一 :501 分支覆盖 |
 * | RESETDATA | `FunctionIdentifier.cs:1847`（类体 1836-1849：`:1846` `VEvaluator.ResetData()` → `:1847` `Console.ResetStyle()`） | 是，**无条件**跟在 ResetData 之后 | **已接**（M4-M `m-resetstyle`）：`core/sem/StatementExecutor.kt` RESETDATA 分支在 `ctx.variables.resetData()` 之后紧跟 `ctx.console.resetStyle()`，同 `ProcessRuntime.kt:501` 的接缝；本文件那条原钉「零控制台动作」的用例已按事前约定改钉四项序列 |
 * | `Process.beginTitle()` | `Process.cs:2112`（方法体 2105-…：`:2111` `skipPrint = false` → `:2112` `console.ResetStyle()` → `:2113` `deleteAllPrevState()`） | 是 | **未接**：移植侧对位 = `core/proc/BeginFlow.kt:358-366`（`stepTitle()`），无任何复位调用 ⇒ 需组长接线 |
 *
 * 另两处**相邻但不是复位点**的原版入口，避免下一任误接：
 * - `Process.BeginTitle()`（公开版，`Process.cs:480-485`）只调 `vEvaluator.ResetData()`（`:482`）+
 *   `state.Begin(BeginType.TITLE)`，**没有** `ResetStyle()` ⇒ 这里不该补复位。
 * - `RESETGLOBAL_Instruction`（`FunctionIdentifier.cs:1851-…`）方法体只 `ResetGlobalData()`，同样不复位。
 *
 * 移植侧的**反向多出面**（原版没有的动作）：`StatementExecutor.kt:723-725` 在 RESETDATA 分支里
 * 清 `skipPrint` / `mesSkip` / `saveSkip`。原版 `skipPrint = false` 属于 `beginTitle`
 * （`Process.cs:2111`），RESETDATA 方法体（`:1844-1848`）只有 ResetData + ResetStyle 两句；
 * `mesSkip` 属于 `Console.MesSkip`、`saveSkip` 无对应 ⇒ 属「一处语句清了三个别的入口的状态」，
 * 本路按**登记不修正**处置（ADR-005 只约束复刻官方 bug，不约束移植侧自造的清位；
 * 若组长认为要摘，摘 `skipPrint` 一行迁到 `BeginFlow.stepTitle()`）。
 *
 *
 * ## 剩余单元（TODO，逐条待补）
 * - P1 BEGIN 族本体：跳转/事件派发口径、错误文案 Lang 原文（`FunctionIdentifier.cs:2922-2954`
 *   + `ProcessState.cs:135-167` SetBegin / `:169-188` force 守卫 / `:352-362` 消费点）。
 * - P2 CALL 族语句层：CALL / TRYCALL / CALLFORMF / EVENTCALL 的实参传递与形参绑定**相位**；
 *   对面 M6-REF-ARGS 的逐位绑定已由 `core/compile/RefArgBindingTest.kt` 钉过，本路只查语句层。
 * - P3 CHARA 族语句层：CHARAADD / CHARADEL / CHARACOPY / CHARAINIT 的返回值、越界文案、
 *   UniqueCode 面（第一维上界 = 活角色数已由 `core/vars/CharaDimBoundTest.kt` 钉过，不重复）。
 */
class StmtFamilyAuditTest {

    // ------------------------------------------------------------------
    // P0 · resetStyle 复位面（调用点已接的一处，先钉复位集边界）
    // ------------------------------------------------------------------

    /**
     * 复位面恰四项：走 [com.eratw.port.core.host.ConsoleSink.resetStyle] 的接口缺省组合
     * （组合体 = resetForeColor + setFontStyle(0) + setFont("") + setAlignment(LEFT)），
     * 断言**序列与集合**，确保接线方（RESETDATA / beginTitle）复用它时不会顺带清背景色、
     * redraw 或待决打印缓冲 —— 这三项在原版方法体里不存在
     * （`EmueraConsole.cs:2838-2842` 只写 `style` 与 `alignment` 两个字段）。
     */
    @Test
    fun `resetStyle 组合恰四项动作 不含背景与重绘`() {
        val sink = FakeConsoleSink()
        sink.resetStyle()
        assertEquals(
            listOf("resetForeColor", "setFontStyle(0)", "setFont()", "setAlignment(LEFT)"),
            sink.events,
            "复位集 = 前景/样式位/字体名/对齐四项（EmueraConsole.cs:2838-2842 + StringStyle.cs:20-27）",
        )
    }

    /**
     * RESETDATA 的**变量复位**成立（`FunctionIdentifier.cs:1846` → `VEvaluator.ResetData()`，
     * 移植侧 `core/vars/VariableStore.kt:1236-1240` = 局部复位 + 系统数组复位 + 清空角色），
     * `:1847` 的 `Console.ResetStyle()` **已接线**（M4-M `m-resetstyle`，落点
     * `core/sem/StatementExecutor.kt` RESETDATA 分支：`ctx.variables.resetData()` 之后
     * 紧跟 `ctx.console.resetStyle()`）⇒ 本用例按类头 KDoc 的事前约定，从「钉现状 = 零控制台动作」
     * 改钉为**四项复位序列**（集合与类头第 1 例同一口径：前景/样式位/字体名/对齐，
     * `EmueraConsole.cs:2838-2841` + `StringStyle.cs:20-27`，**不含** bgColor `:200` 与 redraw `:455`）。
     *
     * 复位后果（原版一侧）：RESETDATA 之后脚本若不重设字色/字体/对齐，首屏不再被上一局样式污染。
     * 移植侧的反向多出面保持不变：`ctx.skipPrint/mesSkip/saveSkip` 三位在原版 RESETDATA 方法体里
     * 不存在（`Process.cs:2111` 的 `skipPrint = false` 属 beginTitle，不是 RESETDATA），登记不修。
     */
    @Test
    fun `RESETDATA 复位变量并紧跟四项样式复位`() {
        val env = AuditEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.writeInt("DAY", longArrayOf(0), 7L)
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10)))
        assertEquals(1, env.store.characters.count, "前置：角色已入表")
        // 把样式弄脏（走 sink 本体，等价于 SETCOLOR/SETFONT/ALIGNMENT 的落点），再清空事件记录看增量。
        env.console.setForeColor(255, 0, 0)
        env.console.setFontStyle(9)
        env.console.setFont("楷体")
        env.console.setAlignment(Alignment.RIGHT)
        env.console.events.clear()

        env.run(instr(FunctionCode.RESETDATA))

        assertEquals(0, env.store.characters.count, "ResetData 清空角色表（:1846 → deleteAllCharacters）")
        assertEquals(0L, env.store.readInt("DAY", longArrayOf(0)), "系统数组复位（resetValues）")
        assertEquals(
            listOf("resetForeColor", "setFontStyle(0)", "setFont()", "setAlignment(LEFT)"),
            env.console.events,
            "RESETDATA 复位变量后必须紧跟样式复位四项（FunctionIdentifier.cs:1847）；多一项或少一项都算分歧",
        )
    }

    /**
     * RESETDATA **不清** GLOBAL / GLOBALS（官方口径，非移植侧的偷懒）：
     * `VariableData.cs:3398-3399` 的 `SetDefaultValue` 把 GLOBAL(63) 排除在清理范围外，
     * GLOBALS 同理 ⇒ 移植侧 `VariableStore.kt:1154-1158`（`if (i == globalIndex) continue`）
     * 与 `:1168-1171` 复刻了这条（GLOBAL = 整型表第 63 列，`VariableData.cs:3398-3399`
     * 的 `case 63: continue`；GLOBALS = 字符串表第 5 列，`:3416-3417` 的 `case 5: continue`；
     * 同处 `:3395-3397` 的 `case 60` 把 ITEMPRICE 刷回常量表，移植侧
     * `VariableStore.kt:1161-1163` 用 `itemPriceSource` 复刻）。
     *
     * 归属本路的原因是它与 `RESETGLOBAL_Instruction`（`FunctionIdentifier.cs:1851-1863`，
     * 方法体只 `ResetGlobalData()`、**不复位样式**）构成一对：两条语句的清理面互补，
     * 接线时不能把 RESETDATA 的复位面扩成「全清」。
     */
    @Test
    fun `RESETDATA 保留 GLOBAL 与 GLOBALS 只有 RESETGLOBAL 清它们`() {
        val env = AuditEnv()
        env.store.writeInt("GLOBAL", longArrayOf(0), 123L)
        env.store.writeStr("GLOBALS", longArrayOf(0), "留存")
        env.run(instr(FunctionCode.RESETDATA))
        assertEquals(123L, env.store.readInt("GLOBAL", longArrayOf(0)), "GLOBAL 不在 SetDefaultValue 清理范围（VariableData.cs:3398-3399）")
        assertEquals("留存", env.store.readStr("GLOBALS", longArrayOf(0)), "GLOBALS 同上")

        env.run(instr(FunctionCode.RESETGLOBAL))
        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(0)), "RESETGLOBAL 才清（FunctionIdentifier.cs:1851-1863）")
        assertEquals("", env.store.readStr("GLOBALS", longArrayOf(0)))
    }

    /**
     * RESETDATA 分支的**自造清位**登记：原版方法体 `FunctionIdentifier.cs:1844-1848` 只有
     * ResetData + ResetStyle 两句，`skipPrint = false` 属于 `Process.beginTitle()`
     * 的 `Process.cs:2111`（与 ResetStyle 相邻两句移植侧都没接）。移植侧在
     * `StatementExecutor.kt:723-725` 额外清 `skipPrint` / `mesSkip` / `saveSkip`。
     * 处置 = **登记不修正**（ADR-005 只要求复刻官方 bug，不要求摘除移植侧自造的清位；
     * 真要迁位，`skipPrint` 一行该迁进 `BeginFlow.stepTitle()`，与 :2112 的复位同批）。
     */
    @Test
    fun `RESETDATA 顺带清跳过位 原版该位由 beginTitle 清`() {
        val env = AuditEnv()
        env.ctx.skipPrint = true
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.RESETDATA))
        assertEquals(false, env.ctx.skipPrint, "移植侧清（StatementExecutor.kt:723）")
        assertEquals(false, env.ctx.mesSkip, "移植侧清（:724）")
    }

    // ------------------------------------------------------------------
    // P1 · BEGIN 族本体（关键字集合 / 大小写 / 文案 / 副作用）
    // ------------------------------------------------------------------

    /**
     * 原版 `SetBegin(string, bool)`（`ProcessState.cs:135-167`）是**裸 C# string switch**：
     * `case "SHOP"`（:139）、`"TRAIN"`（:143）、`"AFTERTRAIN"`（:146）、`"ABLUP"`（:149）、
     * `"TURNEND"`（:152）、`"FIRST"`（:155）、`"TITLE"`（:161），其余走 `default` →
     * `CodeEE(Lang.Error.InvalidBeginArg)`（:164-166）。**不参与** `Config.StrComper`
     * 大小写口径 ⇒ 小写 `begin shop` 原版必报错。
     *
     * 移植侧 `core/proc/BeginType.kt` 原做 `keyword.trim().uppercase()`；**m-resetstyle 路已收紧**
     * （语料实测 `^\s*begin ` 23 行全大写、无小写写法 ⇒ 大小写敏感不打掉语料合法写法），
     * 与原版裸 switch 对齐。仍保留的**超集**：① 前后空格 trim；② 函数名形关键字
     * （`EVENTSHOP`/`EVENTFIRST`/`SYSTEM_TITLE`/…，七个别名分支）。原版 `BEGIN EVENTSHOP` 落 default 抛错。
     * 前 KDoc 曾写「关键字大小写不敏感（原版 Config.StrComper）」——该依据不成立（原版是裸
     * switch），已由本路更正。
     */
    @Test
    fun `BEGIN 关键字集合是原版七词表的超集`() {
        // ① 七个原版关键字逐一对齐 ProcessState.cs:139-161 的 case 表
        val canonical = mapOf(
            "SHOP" to BeginType.SHOP,
            "TRAIN" to BeginType.TRAIN,
            "AFTERTRAIN" to BeginType.AFTERTRAIN,
            "ABLUP" to BeginType.ABLUP,
            "TURNEND" to BeginType.TURNEND,
            "FIRST" to BeginType.FIRST,
            "TITLE" to BeginType.TITLE,
        )
        for ((kw, want) in canonical) assertSame(want, BeginType.fromKeyword(kw))
        // ② 大小写口径：m-resetstyle 路已按语料实测收紧为敏感（23/23 全大写、无小写写法），
        // 与原版裸 switch（ProcessState.cs:139-161）一致 ⇒ 小写现与原版同形：落 default 抛错。
        assertFailsWith<CodeEE> { BeginType.fromKeyword("shop") }
        // ③ 仍保留的超集读数：trim（原版 :2932 不 trim）与函数名形关键字（原版 :164-166 拒绝）。
        assertSame(BeginType.SHOP, BeginType.fromKeyword("  SHOP  "), "原版不做 trim")
        assertSame(BeginType.SHOP, BeginType.fromKeyword("EVENTSHOP"), "原版 BEGIN EVENTSHOP → InvalidBeginArg（:164-166）")
        assertSame(BeginType.TITLE, BeginType.fromKeyword("SYSTEM_TITLE"))
    }

    /**
     * 未知关键字的**文案**：原版 = `Lang.Error.InvalidBeginArg`
     * （jp 原文 `Lang.cs:3118` = `BEGINのキーワード"{0}"は未定義です`；
     * 本作使用的 zhs 表形 `lang.emuera-zhs.xml:734` = `BEGIN的关键字"{0}"没有定义`，
     * 含**成对直引号**；实参 = 关键字原样，`ProcessState.cs:164-166`）。
     * 移植侧原自造 `无效的BEGIN参数：$keyword`（无引号、文案不同）——**m-resetstyle 路已换原文串**。
     */
    @Test
    fun `BEGIN 未知关键字上屏原文 zhs 表形`() {
        val e = assertFailsWith<CodeEE> { BeginType.fromKeyword("NOPE") }
        assertEquals("""BEGIN的关键字"NOPE"没有定义""", e.message, "lang.emuera-zhs.xml:734 表形（成对直引号）")
        // 关键字按原样插入（不 trim 不换大小写），同原版 string.Format(..., keyword)。
        val e2 = assertFailsWith<CodeEE> { BeginType.fromKeyword(" noPé ") }
        assertEquals("""BEGIN的关键字" noPé "没有定义""", e2.message)
    }

    /**
     * `SetBegin` 的**关键字附带副作用**移植侧缺失：原版 `SHOP`（`ProcessState.cs:140-141`）与
     * `FIRST`（`:156-157`）两个 case 在置 `begintype` 之前还要
     * `AppContents.UnloadTempLoadedConstImageNames()` + `...GraphicsImageNames()`
     * （即 `BEGIN SHOP` / `BEGIN FIRST` 会丢弃临时载入的图名表，`TRAIN`/`TITLE`/… 不会）。
     * 移植侧 `BeginType.fromKeyword` 纯映射、无副作用；全仓这两个 unload 只在
     * `core/proc/BeginFlow.kt:955-956` 出现（挂在别的事件上，另有出处 `Process.cs:2671-2672`），
     * BEGIN 语句路径没有。
     * 当前**无可观测量**：两个宿主方法是空实现（`BeginFlow.kt:112`/`:118` 都是 `{}`），
     * 移植侧尚无临时图名表（`core/host/SaveLoadHandoff.kt:45-46` 已登记同一卡点）。
     * ⇒ 处置 = 登记不修正 + **需组长接线**（图像层落地时把这两个 unload 挂到 SHOP/FIRST 入口，
     * 而不是挂到「所有 BEGIN」）。
     */
    @Test
    fun `BEGIN 语句层无副作用 原版 SHOP 与 FIRST 附带卸载临时图名表`() {
        // 移植侧：同一关键字重复解析不产生任何状态变化（BeginType 是 enum，fromKeyword 纯函数）
        assertSame(BeginType.fromKeyword("SHOP"), BeginType.fromKeyword("SHOP"))
        assertSame(BeginType.fromKeyword("FIRST"), BeginType.fromKeyword("FIRST"))
        // 原版侧的方法体行号见本用例 KDoc（ProcessState.cs:140-141 / :156-157）
    }

    // ------------------------------------------------------------------
    // P2 · CALL 族语句层（未定义目标：抛错相位 + 文案）
    // ------------------------------------------------------------------

    /**
     * CALL 族**相位对拍**（只查语句层，`RefArgBindingTest` 已钉的逐位绑定不重复）：
     * 原版 `CALL_Instruction`（类体 `FunctionIdentifier.cs:3489-3597`）——
     * - 装载期 `SetJumpTo`（:3522-3559）：非常量名 → `useCallForm = true` 后**直接 return**
     *   （:3524-3528）；查不到且非 try → 只把名字记进 `FunctionoNotFoundName`（:3531-3535），
     *   **装载期不报错**；查到 → 缓存 `JumpTo`/`CallFunc`/`UDFArgument`，其中形参转换
     *   `ConvertArg` 在装载期就做、失败即 `ParserMediator.Warn(isError = true)`（:3547-3552）。
     * - 运行期 `DoInstruction`（:3560-3597）：命中判空**在前**（:3574），
     *   `!isTry → throw CodeEE(NotDefinedFunc)`（:3577-3579）、`isTry → 有 JumpToEndCatch 则跳、
     *   否则 return`（:3580-3584）；实参绑定 `ConvertArg` 在**其后**才兜底执行（:3587-3592），
     *   最后 `IntoFunction`（:3595）。⇒ 目标缺失时**实参不求值、不产生副作用**。
     * - `IsJump` 位是**每次调用现写**的（:3586 `calledFunction.IsJump = isJump`），
     *   同一 `CalledFunction` 可被 CALL 与 JUMP 复用；移植侧把该位烘进调用点字节码
     *   （`core/compile/ErbProgramCompiler.kt:1130`/`:1665` 的 `JUMP_BIT`，`core/code/Vm.kt:507-510`
     *   按位分派 callInto/jumpInto）⇒ 形态不同、逐调用点读数等价（登记不修正）。
     *
     * 文案分歧：原版未定义 = `Lang.Error.NotDefinedFunc`
     * （jp `Lang.cs:2262` = `指定された関数名"@{0}"は存在しません`；zhs 表形
     * `lang.emuera-zhs.xml:520` = `没有找到指定的函数"@{0}"`，注意**带 @ 前缀与引号**）；
     * 移植侧两条抛出点（`core/proc/ProcessRuntime.kt` 的根级 `resolveForCall`、
     * `core/code/Vm.kt` 的 `_CALL_BY_NAME` 动态名分支）曾各自造 `函数 $name 未定义`。
     * ⇒ **M4-M `m-callmes` 已接线**（两处同批换成 zhs 原文，整串断言见
     * `core/proc/CallMesTextTest.kt` 的交叉用例）。
     */
    @Test
    fun `CALL 未定义目标上屏 zhs 原文 且 TRYCALL 静默返回 null`() {
        val store = VariableStore()
        val rt = minimalRuntime(store)
        val e = assertFailsWith<CodeEE> { rt.callFunction("ABSENT") }
        assertEquals("没有找到指定的函数\"@ABSENT\"", e.message, "M4-M m-callmes 已换成 zhs 表形原文（zhs:520）")
        assertNull(rt.tryCall("ABSENT"), "TRYCALL 未定义 → null 静默跳过（FunctionIdentifier.cs:3580-3584）")
        // 原版 isTry 分支不写 RESULT（本路复核：:3580-3584 方法体无 RESULT 赋值），
        // 移植侧 VM 的 try 位只在给了标志槽 c 时写 0（Vm.kt:554）⇒ 语句层不观测 RESULT 差异。
    }

    // ------------------------------------------------------------------
    // P3 · CHARA 族语句层（返回值 / 越界文案 / 相位 / UniqueCode）
    // ------------------------------------------------------------------

    /**
     * 语句层**名字表**校正（任务书的 CHARAADD / CHARADEL / CHARACOPY / CHARAINIT 在
     * `FunctionIdentifier.cs` 内查无对应类）：角色增删在原版是**一个类三个注册位**
     * —— `ADDCHARA_Instruction`（类体 :1344-1395）经
     * `addFunction(ADDCHARA, isSp:false/isDel:false)`（:3943）、
     * `ADDSPCHARA`（:3944，isSp:true）、`DELCHARA`（:3947，isDel:true）注册；
     * 另有 `ADDVOIDCHARA_Instruction`（:1397 起）。拷贝面在求值层
     * （`VariableEvaluator.cs:1355-1366` `CopyChara`，移植侧 `VariableStore.kt:632-635`），
     * 不是本族语句。⇒ 交付里按这份名单对拍，勿再按任务书四字面找。
     */
    @Test
    fun `CHARA 族语句不写 RESULT 三条共用一个指令类`() {
        val env = AuditEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        env.store.writeInt("RESULT", longArrayOf(0), 777L)

        env.run(instr(FunctionCode.ADDCHARA, charaArg(10, 20)))
        assertEquals(2, env.store.characters.count)
        assertEquals(777L, env.store.readInt("RESULT", longArrayOf(0)), "ADDCHARA 方法体（:1357-1395）无 RESULT 赋值")

        env.run(instr(FunctionCode.DELCHARA, charaArg(0)))
        assertEquals(1, env.store.characters.count)
        assertEquals(777L, env.store.readInt("RESULT", longArrayOf(0)), "DELCHARA 同分支同结论（isDel 位 :1354）")
    }

    /**
     * **DELCHARA 的越界文案分歧 + 一条官方 bug**（本路唯一「须复刻但没复刻」的实质分歧）：
     * 原版按实参个数走两个重载（`FunctionIdentifier.cs:1384-1393`：`array.Length == 1` →
     * `DelCharacter(long)`，否则 → `DelCharacter(long[])`），两处的报错串**不同**：
     * - 单参：`VariableEvaluator.cs:1241-1249` —— `charaNo < 0 || charaNo >= Count` →
     *   `CodeEE(format(OoRDelChara, charaNo.ToString()))`（编号正常入串）；
     * - 多参：`VariableEvaluator.cs:1251-1261` —— 同样判越界，但格式化传的是
     *   **`charaNoList.ToString()`（数组对象本身）** ⇒ CLR 上屏 =
     *   `试图删除不存在的注册角色(System.Int64[])`，**编号丢失**。
     *   这是官方 bug，按 ADR-005 须原样复刻。
     * 移植侧原先把两个重载合成一条 `VariableStore.deleteCharacters(LongArray)`
     * （`core/sem/StatementExecutor.kt:599-602` → `core/vars/VariableStore.kt`），
     * 越界用自造串 `DELCHARA 失败：角色位置 $p 越界（角色数 N）`，
     * 既不是 Lang 原文（zhs 表形 `lang.emuera-zhs.xml:314` =
     * `试图删除不存在的注册角色({0})`）、也不区分单参/多参。
     * 重复检测两侧都有，但**判重依据不同**：原版多参路径边收集边按**对象**判重
     * （`VariableEvaluator.cs:1262-1265` 的 `list.Contains(characterData)`，串 =
     * `DuplicateDelChara` 带编号，zhs 表形 `lang.emuera-zhs.xml:315`），移植侧按**位置**判重
     * （`VariableStore.kt`）。两侧在「同一位置写两次」这一常见形态上读数一致；
     * 差异只在原版的对象级判重能识别「不同位置指向同一角色」——移植侧的角色表不存在这种别名
     * （CharacterList 每个元素都由 `new CharacterData(...)` 产生、`CharacterData` 未覆写 Equals），
     * 故列为**登记不修正**。
     *
     * **接线后重钉（M4-M `m-delchara` 合流，组长落表）**：本用例原来钉的是「移植侧不区分
     * 单参/多参」的现状，现已复刻分岔与官方 bug ⇒ 分流点按原版放在**语句层**
     * （`core/sem/StatementExecutor.kt:747-763` 的 DELCHARA 分支按 `ps.size == 1` 二选一，
     * 对应 `FunctionIdentifier.cs:1387-1394`）；`VariableStore.deleteCharacter` 只承载单参
     * 语义（报编号）、`deleteCharacters` 只承载数组参语义（报 `System.Int64[]`）。
     * `VariableEvaluator.cs:1266` 之后的次序亦已读完：原版是「边校验边 Dispose、校验全过后
     * 才 Remove」（`:1269-1272`），失败路径会留下「已 Dispose 仍在表内」的半死态；该状态在
     * 原版无可观测出口（CodeEE 穿出 `DoInstruction` 后会话进 Error 态、不再执行语句，
     * Process.cs:462-476 / EmueraConsole.cs:757-768 / :1090-1100），故取处置 B（不可观测），
     * SoA 侧校验期零副作用即等价。
     */
    @Test
    fun `DELCHARA 越界 单参报编号多参照原版复刻 System_Int64_串`() {
        val env = AuditEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10)))
        // 单参越界（原版 :1241-1249 报编号 → zhs 表形 :314）
        val single = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.DELCHARA, charaArg(5))) }
        assertEquals("试图删除不存在的注册角色(5)", single.message)
        // 多参越界（原版 :1259 把数组对象塞进 format ⇒ 上屏 System.Int64[]、编号丢失）
        val multi = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.DELCHARA, charaArg(0, 5))) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", multi.message)
        assertNotEquals(single.message, multi.message, "两条重载的串必须不同（原版就是不同）")
        assertEquals(1, env.store.characters.count, "两次越界都不改变角色表")
    }

    /**
     * CHARA 族的**求值相位**（与原版一致，钉成回归）：原版 ADDCHARA 系在循环内**即时追加**
     * （`FunctionIdentifier.cs:1368-1381`，每个实参求值后马上 `AddCharacter`），
     * DELCHARA 则先**整体求值收集**再统一删（:1364-1370 收集 → :1383-1392 删除）。
     * 移植侧同形：追加在 for 循环内逐条落（`StatementExecutor.kt:717`），
     * 删除先 `map { getIntValue }` 收齐再分流（:756-761）。
     * ⇒ 「`DELCHARA 0 1` 的第二个位置读的是删除前的表」这条读数两侧一致；
     * 若日后把 DELCHARA 改成边删边求值，本用例必红。
     */
    @Test
    fun `DELCHARA 多参按删除前的位置解释`() {
        val env = AuditEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10, 20)))
        assertEquals("甲", env.store.readStr("NAME", longArrayOf(0)))
        env.run(instr(FunctionCode.DELCHARA, charaArg(0, 1)))
        assertEquals(0, env.store.characters.count, "两个位置都在删除前的表上求值 ⇒ 全清")
    }

    // ------------------------------------------------------------------
    // CALLFORM 族实参求值相位（M11-B CORE-02）
    // ------------------------------------------------------------------

    /**
     * **原版语义**（M11-B CORE-02 修复后的回归钉，替代原「TODO(P2) 求值相位分歧」现状钉）：
     * 常量名的 TRY 族 FORM 调用在解析期经 Restructure 折叠成常量
     * （spec: ArgumentParser.cs:726 + :781-789），装载期 SetJumpTo 绑成 CallFunc，
     * 运行期 `FunctionIdentifier.cs:3566-3570` 直接取绑定、`:3577` 的 null 判定
     * **先于** `:3590-3597` 的 ConvertArg（实参求值）——目标缺失时 TRY 族 `:3583-3588`
     * 直接 return，实参**一次都不求值**。修复后移植侧把纯文本函数名折叠成 StrLiteral
     * （`ArgBuilders.foldTryConstFormName`），编译器走静态名分支：目标缺失整行不发射，
     * 实参求值指令根本不存在。
     *
     * **残余分歧（需组长接线，本路文件面进不了编译器/VM）**：
     * 1. 动态名（`TRYCALLFORM "A{X}"` 一类）与全部非 TRY 形态（CALLFORM/JUMPFORM）仍走
     *    `_CALL_BY_NAME` 动态通道，实参由 `stageArgs` 在**名字求值之前**发射求值指令
     *    （`core/compile/ErbProgramCompiler.kt` compileJumpLike 的 FORM 分支：stageArgs 先于
     *    emitCallByName/evalTemp(nameTerm)）⇒ 目标缺失时实参已被求值一次，与原版
     *    `:3577` 先判后转（ConvertArg）的次序相反。非 TRY 缺失是致命 CodeEE 路径
     *    （:3581），实参先消费在屏上不可观测；TRY 族动态名缺失是静默跳过，多消费的
     *    RAND 会移位后续随机序列。
     * 2. TRYCCALLFORM/TRYCJUMPFORM 的**常量名**同样未折：动态名的两步式发射
     *    （`_CALL_BY_NAME`+`_JUMP_IF_TRUE`+`_JUMP`，契约 R3）已被 ErbProgramCompilerTest
     *    的 tryccallform 两步式发射形态用例按常量名 `TRYCCALLFORM "GHOST"` 钉死，
     *    改钉归组长（C# 证据同上，:3577 先于 :3590-3597）。
     * 接线方案：编译器先发射名字求值、再把实参求值挪进「命中才走」的分支（或 VM 侧
     * `_CALL_BY_NAME` 延迟实参），动 `ErbProgramCompiler.kt` 与 `Vm.kt`，归组长。
     */
    @Test
    fun `TRYCALLFORM 常量名目标缺失 实参RAND不消费`() {
        val rand = CountingRand()
        val store = VariableStore()
        store.randProvider = rand
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "TRYCALLFORM \"NO_SUCH_FUNC\", RAND:100", "A = 1"),
            ),
            store,
        )
        assertEquals(0, rand.calls, "目标缺失 ⇒ 原版 :3577 先于 :3592 ConvertArg，实参不求值")
        assertEquals(1, store.readInt("A", longArrayOf(0)), "TRY 未命中跳过本行（:3587 return）后继续执行")
    }

    @Test
    fun `TRYCALLFORM 常量名目标存在 调用与实参求值不变`() {
        val rand = CountingRand()
        val store = VariableStore()
        store.randProvider = rand
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "TRYCALLFORM \"HIT_FUNC\", RAND:100", "A = 2"),
                "HIT_FUNC" to listOf("@HIT_FUNC(ARG)", "B = 7"),
            ),
            store,
        )
        assertEquals(1, rand.calls, "目标存在 ⇒ 实参照常求值一次（语料既有站点行为不变的前提）")
        assertEquals(7, store.readInt("B", longArrayOf(0)), "被调函数确实执行")
        assertEquals(2, store.readInt("A", longArrayOf(0)), "调用返回后顺序继续")
    }

    /**
     * 非 TRY 的 CALLFORM 维持动态通道：目标缺失在**执行到该行**时按原版 :3581 的
     * NotDefinedFunc 文案抛 CodeEE（zhs 表形 = core/code/Vm.kt `_CALL_BY_NAME`
     * 非 try 分支的逐字串）。解析期/装载期都不报 —— 原版装载成功（SetJumpTo 对
     * 非常量名只置 useCallForm），错误时机 = 执行期。
     */
    @Test
    fun `CALLFORM 目标缺失 执行期按原版文案抛CodeEE`() {
        val store = VariableStore()
        val e = assertFailsWith<CodeEE> {
            runProgram(
                compileProgram(
                    "TEST" to listOf("@TEST", "CALLFORM \"NO_SUCH_FUNC\", 1"),
                ),
                store,
            )
        }
        assertEquals("没有找到指定的函数\"@NO_SUCH_FUNC\"", e.message)
    }

    // TODO(P3)：原版 DelCharacter(long[]) 的 Dispose/Remove 次序（VariableEvaluator.cs:1266 之后）
    //           未逐行读完 ⇒ 判重按对象 vs 按位置的等价性存疑，见上一用例的 TODO(verify)。
    // TODO(P3)：UniqueCode 面：`FunctionIdentifier.cs` 的 ADDCHARA/ADDSPCHARA/DELCHARA 方法体
    //           （:1357-1395）内**无**任何 UniqueCode/EXLCODE 读写（逐句读完确认），
    //           移植侧 `core/vars/VariableStore.kt` + `core/sem/StatementExecutor.kt` grep
    //           `uniqueCode|UniqueCode` 零命中 ⇒ 语句层两侧都是零涉及（结论，非分歧）。
    //           卡点：未做全仓 grep，UniqueCode 若在 fn/loader 层存在，属别的 lane。

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /** 语句层对拍环境（与 IsaGapTest 的私有夹具同形；两处私有类型互不可见，故本文件自带一份）。 */
    private class AuditEnv {
        val store = VariableStore()
        val console = FakeConsoleSink()
        val eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT)
        val ctx = ExecContext(variables = store, eval = eval, console = console)
        private val executor = StatementExecutor()

        fun run(stmt: Statement): ExecResult = executor.execute(stmt, ctx)
    }

    /** 只够建表的极简 Program：main 一条 `_HALT`，供 CALL 族按名解析路径用。 */
    private fun minimalRuntime(store: VariableStore): ProcessRuntime {
        val b = CodeBuilder()
        val pc = b.here()
        b.emit(Opcode._HALT)
        val main = b.declareFunction("main", pc, arity = 0, slotCount = 1)
        return ProcessRuntime(
            b.build(entryFunction = main),
            store,
            FunctionTable(listOf(FuncMeta(index = main, key = "MAIN", argIntCount = 0))),
        )
    }

    // ---- CALLFORM 族求值相位用例的编译/运行装置（形态照 CaseRangeAndStepTest，纯 JVM 不落盘）----

    /** ERB 文本行 → 语句（真 [LineParser]；`@`/`$`/`#` 行首分派与语料装载同口径）。 */
    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "callform-audit.erb", i + 1, text))
        }
    }

    /**
     * 多函数编译：`known` = 全部函数名（整程序装载口径）；入口 = 首个函数。
     *
     * 头部形参（`@F(A, B$)`）必须进 [FuncSig]：原版 `ConvertArg` 按 `TopLabel.Arg.Length`
     * 判实参过多（spec: CalledFunction.cs:136-140），arity=0 时**任何**带实参的调用都是
     * 硬错 —— 装置若一律 arity=0，带实参的用例会被这条判据挡在门外（2026-10-26）。
     */
    private fun compileProgram(vararg funcs: Pair<String, List<String>>): Program {
        val c = ErbProgramCompiler()
        val known = funcs.map { it.first.uppercase() }.toSet()
        for ((name, lines) in funcs) {
            val params = headerParams(lines.first())
            c.compileFunction(
                FuncSig(name, arity = params.size, paramNames = params),
                parseErbLines(*lines.toTypedArray()),
                known,
            )
        }
        return c.build(funcs.first().first)
    }

    /** `@F(A, B$)` → `[A, B$]`；无括号/空括号 → `[]`（与 FuncSig 的「无头部形参名」同义）。 */
    private fun headerParams(labelLine: String): List<String> {
        val open = labelLine.indexOf('(')
        val close = labelLine.lastIndexOf(')')
        if (open < 0 || close <= open) return emptyList()
        return labelLine.substring(open + 1, close)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 从 Program.functions 重建函数表后执行（`_CALL`/`_CALL_BY_NAME` 两通道都能解析）。 */
    private fun runProgram(program: Program, store: VariableStore) {
        val table = FunctionTable(
            program.functions.mapIndexed { i, f -> FuncMeta(index = i, key = f.name.uppercase()) },
        )
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 50_000L)).execute()
    }

    /** 计数随机源：断言「实参里的 RAND 是否被消费」的探针（ADR-001 注入，禁 kotlin.random）。 */
    private class CountingRand : RandProvider {
        var calls = 0
        override fun next(bound: Long): Long {
            calls++
            return 0L
        }
    }
}

/** INT_ANY 实参形态（角色号字面量；同 IsaGapTest 的文件私有 charaArg，互不冲突）。 */
private fun charaArg(vararg nos: Long) = ExpressionArrayArgument(
    nos.map { IntLiteral(it) as Expr? },
)


