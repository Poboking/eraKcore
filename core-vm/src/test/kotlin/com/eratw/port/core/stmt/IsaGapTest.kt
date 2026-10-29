package com.eratw.port.core.stmt

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.NativeMethod
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.SP_CHARA_CONFIG_IS_OFF
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.fn.spCharaEnabled
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.ExecResult
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.iVar
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.sVar
import com.eratw.port.core.sem.strArg
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ISA 缺口三条语句的落地测试（CONTRACT-M4-H §2 h-isastmt）。
 *
 * 覆盖 `golden-coverage.py` 的 MISS 名单 `ADDSPCHARA` / `CALLSHARP` / `FORCE_BEGIN`。
 * 语料实测这三条在 `ERB` 子树**零处使用**、`fixtures/golden/baseline/pc-golden-root.txt`
 * 也无对应读数 ⇒ 本文件不断言基准行，只把 C# 主源裁定的**行为**钉成断言：
 * 角色表状态、抛错文案与抛错时机、回写后的实参值、BEGIN 载荷与入口映射。
 *
 * 主源（逐条行号在实现处的 KDoc）：
 * - `ADDCHARA_Instruction`：`GameProc.Function/FunctionIdentifier.cs:1344-1397`，
 *   SP 开关 = `Runtime.Config/ConfigData.cs:106`（缺省 false）；
 * - `CALLSHARP_Instruction`：`:1169-1235`，回写在 `:1219-1231`；
 * - `BEGIN_Instruction` / `FORCE_BEGIN_Instruction`：`:2922-2937` / `:2939-2954`（方法体相同），
 *   `force` 守卫 `Runtime.Script/ProcessState.cs:169-188`。
 */

/** 只喂 configOverrides 的假体宿主（键 = emuera.config 落盘形态）。 */
private fun hostWith(overrides: Map<String, String>): BuiltinHost = object : BuiltinHost {
    override val configOverrides: Map<String, String> = overrides
}

private class IsaEnv(config: Map<String, String> = emptyMap()) {
    val store = VariableStore()
    val console = FakeConsoleSink()
    val eval = BuiltinEvalContext(TestEvalContext(store), store, hostWith(config))
    val ctx = ExecContext(variables = store, eval = eval, console = console)
    private val executor = StatementExecutor()

    /** 本地方法注册表（挂 store.aux，与产线同一取法）。 */
    val native get() = nativeMethods(store.aux)

    fun run(stmt: Statement): ExecResult = executor.execute(stmt, ctx)

    fun register(name: String, fn: (Array<NativeParam>) -> Unit) {
        native.register(name, NativeMethod { args -> fn(args) })
    }
}

private fun templates(env: IsaEnv): IsaEnv {
    env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
    env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
    return env
}

/** INT_ANY 实参形态（spec: FunctionIdentifier.cs:1352 钉死 GetArgumentBuilder(INT_ANY)）。 */
private fun charaArg(vararg nos: Long) =
    ExpressionArrayArgument(nos.map { IntLiteral(it) as com.eratw.port.core.expr.Expr? })

private fun spCharaArg(vararg nos: Long) = charaArg(*nos)

private fun callSharp(name: String, vararg rowArgs: com.eratw.port.core.expr.Expr?): SpCallSharpArgument {
    val term = StrLiteral(name)
    val arg = SpCallSharpArgument(term, emptyList(), rowArgs.toList())
    arg.isConst = true
    arg.constStr = name
    return arg
}

class IsaGapTest {

    // ====================================================================
    // 1) ADDSPCHARA —— spec: FunctionIdentifier.cs:1344-1397
    // ====================================================================

    @Test
    fun `SP 关闭时 ADDSPCHARA 抛原版文案且不追加角色`() {
        val env = templates(IsaEnv())
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.ADDSPCHARA, spCharaArg(10)) ) }
        // 文案 = Lang.Error.SPCharaConfigIsOff（Lang.cs:2266）的 zhs 表形（emuera.zhs.xml:631）
        assertEquals(SP_CHARA_CONFIG_IS_OFF, e.message)
        assertEquals(0, env.store.characters.count) // 角色表一字未动
    }

    @Test
    fun `SP 关闭时报错早于实参求值`() {
        // FunctionIdentifier.cs:1360-1363 在 :1368 的求值循环**之前**
        // ⇒ 参数换成未定义 NO 也必须是 SP 错，而不是"未定义角色"错。
        val env = templates(IsaEnv())
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.ADDSPCHARA, spCharaArg(999))) }
        assertEquals(SP_CHARA_CONFIG_IS_OFF, e.message)
        assertTrue(env.store.characters.count == 0)
    }

    @Test
    fun `SP 开关缺省关闭且语料形 NO 同关闭`() {
        // 缺省 false：ConfigData.cs:106（t: false）
        assertTrue(!spCharaEnabled(hostWith(emptyMap())))
        // 本作 emuera.config:59 实测 = `ALLOW SP CHARACTERS:NO`
        assertTrue(!spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "NO"))))
        assertTrue(spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "YES"))))
    }

    @Test
    fun `SP 开关三个键形都能命中`() {
        // ConfigData.GetItem 的「键大写化后比 Name / Text / EngText」口径
        // （ConfigData.cs:260-284；条目本体 ConfigData.cs:106）
        for (key in listOf("COMPATISPCHARA", "SPキャラを使用する", "allow sp characters")) {
            assertTrue(spCharaEnabled(hostWith(mapOf(key to "YES"))), key)
        }
        // 值解析 = parseBoolLiteral（ConfigItem.cs:227-250）：整数非 0 亦真；不可解析保留缺省 false
        assertTrue(spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "1"))))
        assertTrue(!spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "MAYBE"))))
    }

    @Test
    fun `SP 开启时 ADDSPCHARA 追加角色`() {
        val env = templates(IsaEnv(mapOf("ALLOW SP CHARACTERS" to "YES")))
        env.run(instr(FunctionCode.ADDSPCHARA, spCharaArg(20)))
        assertEquals(1, env.store.characters.count)
        assertEquals(20L, env.store.readInt("NO", longArrayOf(0)))
        assertEquals("乙", env.store.readStr("NAME", longArrayOf(0)))
    }

    @Test
    fun `SP 开启时 ADDCHARA 与 ADDSPCHARA 同路径（原版忽略 isSp 形参）`() {
        // FunctionIdentifier.cs:1376-1379 → AddCharacter_UseSp(no, isSp)
        // ConstantData.cs:1230-1238 的 sp 形参**从未被读取** ⇒ SP 身份不参与筛选，
        // 两条语句都按 NO 查同一张表（ADR-005：原样复刻，不加"SP 只能加 SP 角色"的校验）。
        val a = templates(IsaEnv(mapOf("ALLOW SP CHARACTERS" to "YES")))
        a.run(instr(FunctionCode.ADDCHARA, charaArg(10)))
        val b = templates(IsaEnv(mapOf("ALLOW SP CHARACTERS" to "YES")))
        b.run(instr(FunctionCode.ADDSPCHARA, spCharaArg(10)))
        assertEquals(1, a.store.characters.count)
        assertEquals(1, b.store.characters.count)
        assertEquals(a.store.readInt("NO", longArrayOf(0)), b.store.readInt("NO", longArrayOf(0)))
        assertEquals(a.store.readStr("NAME", longArrayOf(0)), b.store.readStr("NAME", longArrayOf(0)))
    }

    @Test
    fun `SP 开启时 ADDSPCHARA 的未定义 NO 走角色错`() {
        val env = templates(IsaEnv(mapOf("ALLOW SP CHARACTERS" to "YES")))
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.ADDSPCHARA, spCharaArg(999))) }
        // 角色表的既有错误文案（VariableStore.kt:481-485），不是 SP 文案
        assertTrue(e.message!!.contains("999"), e.message ?: "")
        assertEquals(0, env.store.characters.count)
    }

    @Test
    fun `DELCHARA 不受 SP 开关影响`() {
        // 注册位 DELCHARA 是 flagSp=false（FunctionIdentifier.cs:3947）⇒ :1360 的门永不触发
        val env = templates(IsaEnv())
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10, 20)))
        env.run(instr(FunctionCode.DELCHARA, charaArg(0)))
        assertEquals(1, env.store.characters.count)
        assertEquals(20L, env.store.readInt("NO", longArrayOf(0)))
    }

    // ====================================================================
    // 2) CALLSHARP —— spec: FunctionIdentifier.cs:1169-1235
    // ====================================================================

    @Test
    fun `CALLSHARP 回写被改到的整型实参`() {
        val env = IsaEnv()
        env.register("BUMP") { p -> p[0].intValue += 7 }
        env.store.writeInt("DAY", longArrayOf(3), 10L)
        env.run(instr(FunctionCode.CALLSHARP, callSharp("BUMP", iVar("DAY", 3))))
        // 期望 = 10 + 7：调用方变量的值被被调方改写后写回（:1228-1231）
        assertEquals(17L, env.store.readInt("DAY", longArrayOf(3)))
    }

    @Test
    fun `CALLSHARP 回写被改到的字符串实参`() {
        val env = IsaEnv()
        env.register("SETSTR") { p -> p[0].strValue = "回写值" }
        env.store.writeStr("RESULTS", longArrayOf(0), "原值")
        env.run(instr(FunctionCode.CALLSHARP, callSharp("SETSTR", sVar("RESULTS", 0))))
        assertEquals("回写值", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CALLSHARP 实参求值口径按静态类型分流`() {
        // PluginMethodParameterBuilder.cs:8-15 —— term.IsString ? strValue : intValue
        var seenInt = Long.MIN_VALUE
        var seenStr = "@unset"
        val env = IsaEnv()
        env.store.writeInt("DAY", longArrayOf(0), 42L)
        env.store.writeStr("RESULTS", longArrayOf(1), "四二")
        env.register("PEEK") { p ->
            seenInt = p[0].intValue
            seenStr = p[1].strValue
        }
        env.run(instr(FunctionCode.CALLSHARP, callSharp("PEEK", iVar("DAY", 0), sVar("RESULTS", 1))))
        assertEquals(42L, seenInt)
        assertEquals("四二", seenStr)
    }

    @Test
    fun `CALLSHARP 回写侧看接收方类型而非槽类型`() {
        // FunctionIdentifier.cs:1221-1231 的分支条件是 `variableTerm.IsString`。
        // 整型实参 + 被调方只写 strValue ⇒ 回写取的是该槽的 intValue（= 传入原值），
        // 故调用方的变量值不变（不是"被清空"，也不是"报错"）。
        val env = IsaEnv()
        env.store.writeInt("DAY", longArrayOf(2), 5L)
        env.register("STRONLY") { p -> p[0].strValue = "改了也没用" }
        env.run(instr(FunctionCode.CALLSHARP, callSharp("STRONLY", iVar("DAY", 2))))
        assertEquals(5L, env.store.readInt("DAY", longArrayOf(2)))
    }

    @Test
    fun `CALLSHARP 非变量实参只求值不回写`() {
        // :1220 `if (aExpression is VariableTerm)` —— 字面量只进不出：被调方把 0 号槽的
        // 两侧都写脏，调用方一侧的 DAY 与 RESULTS 必须一个字节都没动（不是"恰好没变"，
        // 而是根本没有可写的变量身份）。
        val env = IsaEnv()
        env.store.writeInt("DAY", longArrayOf(0), 3L)
        env.store.writeStr("RESULTS", longArrayOf(0), "不动")
        env.register("OVERWRITE") { p ->
            p[0].intValue = 999L
            p[0].strValue = "改了"
        }
        env.run(instr(FunctionCode.CALLSHARP, callSharp("OVERWRITE", IntLiteral(1), StrLiteral("s"))))
        assertEquals(3L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals("不动", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CALLSHARP 空位保持位置对齐且不崩`() {
        // 原版 RowArgs 含 null 槽时 ConvertTerm 直接 NRE（:1216 无空检查）；
        // 移植侧按「槽补 0、跳过回写」登记口径，同时钉住下标对齐（:1220 `RowArgs[i]` ↔ `array[i]`）
        val env = IsaEnv()
        env.register("SECOND") { p ->
            assertEquals(0L, p[0].intValue)
            p[1].intValue += 1
        }
        env.store.writeInt("DAY", longArrayOf(0), 8L)
        env.run(instr(FunctionCode.CALLSHARP, callSharp("SECOND", null, iVar("DAY", 0))))
        assertEquals(9L, env.store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `CALLSHARP 未注册方法报原版文案`() {
        // 装载期口径 = ParserMediator.Warn("No native method X found", isError=true)
        // （FunctionIdentifier.cs:1204）；移植侧挪到执行期抛 CodeEE，字符串原样保留
        val env = IsaEnv()
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.CALLSHARP, callSharp("ABSENT", iVar("DAY", 0))))
        }
        assertEquals("No native method ABSENT found", e.message)
    }

    @Test
    fun `CALLSHARP 名字非常量时运行期求值查表`() {
        // 原版：非常量 → useCallForm=true 且 CallFunc 保持 null，运行期 NRE（:1193-1197 / :1217）。
        // 移植侧改为现求现查（偏离已在 KDoc 登记）。
        val env = IsaEnv()
        env.register("ECHO") { p -> p[0].intValue = p[0].intValue * 2 }
        val dynamicName = StrLiteral("ECHO")
        val arg = SpCallSharpArgument(dynamicName, emptyList(), listOf(iVar("DAY", 1)))
        env.store.writeInt("DAY", longArrayOf(1), 21L)
        env.run(instr(FunctionCode.CALLSHARP, arg))
        assertEquals(42L, env.store.readInt("DAY", longArrayOf(1)))
    }

    @Test
    fun `本地方法表大小写不敏感且重名报错`() {
        // PluginManager.cs:452-470 / :550-558（大写化键 + Dictionary.Add 重名即崩）
        val env = IsaEnv()
        env.register("DO_STUFF") { }
        assertSame(env.native.find("do_stuff"), env.native.find("DO_STUFF"))
        assertTrue(env.native.has("Do_Stuff"))
        val e = assertFailsWith<CodeEE> { env.register("DO_STUFF") { } }
        assertTrue(e.message!!.contains("DO_STUFF"))
    }

    @Test
    fun `CALLSHARP 表为空时第一次调用即报错（不静默跳过）`() {
        // 本波前 CALLSHARP 落在「始终抛未实现」名单；落地后仍必须有失败面，
        // 不许为了过闸把未注册方法当成 no-op（契约 §3「不许吞异常」）
        val env = IsaEnv()
        assertFailsWith<CodeEE> { env.run(instr(FunctionCode.CALLSHARP, callSharp("NOPE"))) }
        assertTrue(env.native.names().isEmpty())
    }

    // ====================================================================
    // 3) FORCE_BEGIN —— spec: FunctionIdentifier.cs:2939-2954（与 :2922-2937 同体）
    // ====================================================================

    @Test
    fun `BEGIN 与 FORCE_BEGIN 产出同一载荷`() {
        // 两条 DoInstruction 方法体逐句相同（:2930-2936 vs :2947-2953），
        // 且都以 force:true 调 SetBegin ⇒ __CAN_BEGIN__ 守卫（ProcessState.cs:179-184）对两者皆不可达。
        for (kw in listOf("TITLE", "TRAIN", "SHOP", "ABLUP", "TURNEND", "FIRST", "AFTERTRAIN")) {
            val env = IsaEnv()
            val a = env.run(instr(FunctionCode.BEGIN, strArg(kw))) as ExecResult.Begin
            val b = env.run(instr(FunctionCode.FORCE_BEGIN, strArg(kw))) as ExecResult.Begin
            assertEquals(kw, a.target)
            assertEquals(a.target, b.target) // 无 force 位、无第二真相
        }
    }

    @Test
    fun `BEGIN 家族的目标字符串映射到同一入口切换`() {
        // proc 侧消费点 = ProcessRuntime.kt:422-432（BeginType.fromKeyword + Stop(0)，
        // 对应原版 state.Return(0L)，:2934/:2951）。语义断言：关键字 → BeginType → 目标函数名。
        val expect = mapOf(
            "TITLE" to BeginType.TITLE,
            "FIRST" to BeginType.FIRST,
            "TRAIN" to BeginType.TRAIN,
            "AFTERTRAIN" to BeginType.AFTERTRAIN,
            "ABLUP" to BeginType.ABLUP,
            "TURNEND" to BeginType.TURNEND,
            "SHOP" to BeginType.SHOP,
        )
        for ((kw, want) in expect) {
            val env = IsaEnv()
            val got = env.run(instr(FunctionCode.FORCE_BEGIN, strArg(kw))) as ExecResult.Begin
            assertSame(want, BeginType.fromKeyword(got.target))
            assertEquals(want.targetFunction, BeginType.fromKeyword(got.target).targetFunction)
        }
        assertEquals("SYSTEM_TITLE", BeginType.TITLE.targetFunction)
        assertSame(BeginType.fromKeyword("TITLE"), BeginType.fromKeyword("SYSTEM_TITLE"))
    }

    @Test
    fun `BEGIN 与 FORCE_BEGIN 对未知关键字都不在语句层报错`() {
        // 原版 SetBegin 的 default 分支抛 InvalidBeginArg（ProcessState.cs:164-166）；
        // 移植侧该判定在 proc 消费点，语句层两条语句同形同结果 ⇒ 差异为零
        val env = IsaEnv()
        val a = env.run(instr(FunctionCode.BEGIN, strArg("NOPE"))) as ExecResult.Begin
        val b = env.run(instr(FunctionCode.FORCE_BEGIN, strArg("NOPE"))) as ExecResult.Begin
        assertEquals("NOPE", a.target)
        assertEquals(a.target, b.target)
        val e = assertFailsWith<CodeEE> { BeginType.fromKeyword("NOPE") }
        assertTrue(e.message!!.contains("NOPE"))
    }

    @Test
    fun `ADDSPCHARA 与 FORCE_BEGIN 都在 FunctionCode 里有独立条目`() {
        // 防"顺手合并成别名"：注册面各占一条（:3944 / :4111），别名表另有归属
        assertEquals(OperandType.INT, charaArg(10).terms.first()!!.operandType)
        assertEquals("ADDSPCHARA", FunctionCode.ADDSPCHARA.name)
        assertEquals("FORCE_BEGIN", FunctionCode.FORCE_BEGIN.name)
        assertEquals(ArgType.STR, FunctionCode.FORCE_BEGIN.argType)
        assertEquals(ArgType.STR, FunctionCode.BEGIN.argType) // 同参数形态：差异只在名字与注册位
        assertEquals(ArgType.INT_ANY, FunctionCode.ADDSPCHARA.argType)
        assertEquals(StmtKind.CALL, FunctionCode.CALLSHARP.kind)
        assertEquals(ArgType.SP_CALLCSHARP, FunctionCode.CALLSHARP.argType)
    }
}
