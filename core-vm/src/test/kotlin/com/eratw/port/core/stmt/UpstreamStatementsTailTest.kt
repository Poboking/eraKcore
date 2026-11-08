package com.eratw.port.core.stmt

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.StatementAliases
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.sem.IMAGE_LAYER_SLOT
import com.eratw.port.core.sem.ImageLayerRegistry
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.SequenceInputState
import com.eratw.port.core.sem.SEQUENCE_INPUT_SLOT
import com.eratw.port.core.sem.instr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 4.981 上游两条语句（st-stmts 路）：SEQUENCEINPUT 与 SETIMAGELAYERL。
 *
 * 覆盖：
 * - 枚举追加位置（只允许末尾追加：`StatementExecutor` 用 `FunctionCode.entries[op]` 还原，
 *   `PrintStmt` 把 `argType.ordinal` 编进 `.erbc`，插中间会让旧产物错位）；
 * - 语料原样解析（`ERB/コマンド関連/USERCOM.ERB:177`、`ERB/魔改内容/PRINT系/Flan_UI.ERB:153`
 *   与 :296/:314/:325 的 6 参形态）；
 * - 参数校验（minArg = 2、第 1 槽必须 STR、第 2 槽必须 INT、超 8 个静默丢弃）；
 * - SETIMAGELAYERL 真落地（按 depth 登记图层 + 行锚定 lineNo + followScroll、height <= 0 回落 sprite 高度）；
 * - SEQUENCEINPUT 预置序列被下一次取值等待消费、不阻塞宿主。
 *
 * 语义锚见各用例的 KDoc；行号口径一律指 fork 源码（见 specs/isa.yaml 的 source 行）。
 */
class UpstreamStatementsTailTest {

    // ------------------------------------------------------------------
    // 追加位置 / 注册
    // ------------------------------------------------------------------

    @Test
    fun `两条新语句追加在枚举末尾且按名可查`() {
        val entries = FunctionCode.entries
        assertEquals(FunctionCode.SETIMAGELAYERL, entries.last(), "SETIMAGELAYERL 必须是最后一条")
        assertEquals(FunctionCode.SEQUENCEINPUT, entries[entries.size - 2], "SEQUENCEINPUT 紧随其后")
        assertEquals(FunctionCode.TRYCCALLSTR, entries[entries.size - 3], "既有末条 TRYCCALLSTR 位置不动")
        assertSame(FunctionCode.SEQUENCEINPUT, FunctionCode.BY_NAME["SEQUENCEINPUT"])
        assertSame(FunctionCode.SETIMAGELAYERL, FunctionCode.BY_NAME["SETIMAGELAYERL"])
        assertEquals(ArgType.STR_EXPRESSION, FunctionCode.SEQUENCEINPUT.argType)
        assertEquals(ArgType.SP_SETIMAGELAYERL, FunctionCode.SETIMAGELAYERL.argType)
        // flag 6 = METHOD_SAFE | EXTENDED（spec: Instraction.Child.cs:4166；method 合成的同值见
        // FunctionIdentifier.cs:4200-4204）。
        assertEquals(6, FunctionCode.SEQUENCEINPUT.flag)
        assertEquals(6, FunctionCode.SETIMAGELAYERL.flag)
        assertEquals(StmtKind.INPUT, FunctionCode.SEQUENCEINPUT.kind)
        assertEquals(StmtKind.DISPLAY, FunctionCode.SETIMAGELAYERL.kind)
        // marker 区间校验用的别名（运行期身份仍是语句池名）。
        assertEquals(Opcode.SETBGIMAGE, StatementAliases.BY_NAME.getValue("SETIMAGELAYERL").opcode)
        assertEquals(Opcode.DO_NOTHING, StatementAliases.BY_NAME.getValue("SEQUENCEINPUT").opcode)
    }

    // ------------------------------------------------------------------
    // 语料原样解析
    // ------------------------------------------------------------------

    @Test
    fun `SETIMAGELAYERL 语料七参形态解析成八槽声明的表达式列表`() {
        // Flan_UI.ERB:153 原样（7 实参，第 8 槽 CM_ARRAY 缺省）。
        val stmt = parseErbLines(
            "SETIMAGELAYERL @\"flan_frame_{L_FRAME_GID}\", 1, (0 - L_FRAME_PAD_X - L_SHAPE_SHIFT), " +
                "(0 - L_FRAME_PAD_Y), L_FRAME_W, L_FRAME_H, 255",
        ).single()
        assertEquals(FunctionCode.SETIMAGELAYERL, stmt.code)
        val args = stmt.args as ExpressionsArgument
        assertEquals(7, args.terms.size, "7 个实参")
        assertEquals(args.terms.size, args.types.size, "编码器要求 types 与 terms 等长")
        assertEquals(ArgValueType.STR, args.types[0])
        listOf(1, 2, 3, 4, 5, 6).forEach { assertEquals(ArgValueType.INT, args.types[it]) }
    }

    @Test
    fun `SETIMAGELAYERL 语料六参形态与超八参静默丢弃`() {
        // Flan_UI.ERB:296/:314/:325 原样（6 实参 ⇒ opacity 取缺省 255、CM_ARRAY 缺省）。
        val six = parseErbLines("SETIMAGELAYERL @\"flan_frame_{L_FRAME_GID}\", 1, 0, 0, L_FRAME_W, L_FRAME_H")
            .single()
        val sixArgs = six.args as ExpressionsArgument
        assertEquals(6, sixArgs.terms.size)
        assertEquals(6, sixArgs.types.size)
        // spec: ArgumentBuilder.cs:2719-2725 —— 只取 terms[0..7]，第 9 个起静默丢弃。
        val nine = parseErbLines("SETIMAGELAYERL \"s\", 1, 0, 0, 0, 0, 255, 0, 9").single()
        val nineArgs = nine.args as ExpressionsArgument
        assertEquals(8, nineArgs.terms.size, "超 8 个实参被丢弃")
        assertEquals(ArgValueType.VOID, nineArgs.types[7], "第 8 槽 CM_ARRAY 声明为 Void")
    }

    @Test
    fun `SEQUENCEINPUT 语料原样解析成字符串表达式`() {
        // USERCOM.ERB:177 原样（全包唯一调用点）。
        val stmt = parseErbLines("SEQUENCEINPUT @\"-1\\n%MACRO_PRESET:MACRO_INDEX%\"").single()
        assertEquals(FunctionCode.SEQUENCEINPUT, stmt.code)
        val arg = stmt.args
        assertTrue(arg is ExpressionArgument, "STR_EXPRESSION 形态 = 单表达式")
        assertTrue((arg as ExpressionArgument).term != null)
    }

    @Test
    fun `SETIMAGELAYERL 参数校验 首槽必须字符串 次槽必须整数 至少两参`() {
        // spec: ArgumentBuilder.cs:2691 minArg = 2、:2706-2715 只校验 terms[0] / terms[1]。
        val notEnough = assertFailsWith<StmtParseException> { parseErbLines("SETIMAGELAYERL \"a\"") }
        assertTrue(notEnough.message!!.contains("SETIMAGELAYERL"), "报错带语句名: ${notEnough.message}")
        val wrongFirst = assertFailsWith<StmtParseException> { parseErbLines("SETIMAGELAYERL 1, 2") }
        assertTrue(wrongFirst.message!!.contains("SETIMAGELAYERL"))
        val wrongSecond = assertFailsWith<StmtParseException> { parseErbLines("SETIMAGELAYERL \"a\", \"b\"") }
        assertTrue(wrongSecond.message!!.contains("SETIMAGELAYERL"))
        // 第 3 槽起不校验类型：字符串也能过（原版同样不查）。
        val thirdStr = parseErbLines("SETIMAGELAYERL \"a\", 1, \"b\"").single()
        assertEquals(3, (thirdStr.args as ExpressionsArgument).terms.size)
    }

    // ------------------------------------------------------------------
    // SETIMAGELAYERL 执行
    // ------------------------------------------------------------------

    @Test
    fun `SETIMAGELAYERL 按 depth 登记图层并保留行锚定位`() {
        // spec: Instraction.Child.cs:4176-4207 —— 缺省 0/0/0/0、opacity 255、GetLineNo 锚定、FollowScroll = true。
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETIMAGELAYERL, layerL(StrLiteral("flan_frame_1"), IntLiteral(10), IntLiteral(5), IntLiteral(6), IntLiteral(100), IntLiteral(200), IntLiteral(128))))
        val registry = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!
        val layer = registry.get(10L)!!
        assertEquals("flan_frame_1", layer.spriteName)
        assertEquals(5, layer.xpos)
        assertEquals(6, layer.ypos)
        assertEquals(100, layer.width)
        assertEquals(200, layer.height)
        assertEquals(128, layer.opacity)
        assertTrue(layer.followScroll, "L 版恒 true")
        assertEquals(0, layer.lineNo, "假宿主不追踪显示行 ⇒ 回落 lineCount(0)")
        assertEquals(listOf(10L), registry.layers.map { it.depth })
    }

    @Test
    fun `SETIMAGELAYERL 同 depth 覆盖且缺省槽取原版默认值`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETIMAGELAYERL, layerL(StrLiteral("a"), IntLiteral(3), IntLiteral(1), IntLiteral(2))))
        val first = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!.get(3L)!!
        assertEquals(255, first.opacity, "opacity 缺省 255（spec: Instraction.Child.cs:4180-4181）")
        assertEquals(0, first.width)
        assertEquals(0, first.height, "未提供 height 且 sprite 不存在 ⇒ 保持 0")
        // 同 depth 覆盖（原版 Console.SetImageLayer 按 depth 索引同一张表）。
        env.run(instr(FunctionCode.SETIMAGELAYERL, layerL(StrLiteral("b"), IntLiteral(3), IntLiteral(7))))
        val registry = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!
        assertEquals(1, registry.layers.size)
        assertEquals("b", registry.get(3L)!!.spriteName)
        assertEquals(7, registry.get(3L)!!.xpos)
    }

    @Test
    fun `SETIMAGELAYERL height 非正时回落 sprite 目标高度`() {
        // spec: Instraction.Child.cs:4192-4198 —— AppContents.GetSprite(name).DestBaseSize.Height。
        val env = SemTestEnv()
        val pool = env.store.aux.slot(SPRITE_SLOT) { SpritePool() }
        val buffer = GraphicsBuffer(0).also { it.create(40, 60) }
        pool.put(SpritePool.Sprite("FLAN_FRAME_1", 40, 60, buffer = buffer))
        // 名字大小写不敏感（find 走 uppercase）。
        env.run(instr(FunctionCode.SETIMAGELAYERL, layerL(StrLiteral("flan_frame_1"), IntLiteral(4), IntLiteral(0), IntLiteral(0), IntLiteral(0), IntLiteral(0))))
        val layer = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!.get(4L)!!
        assertEquals(60, layer.height)
        assertEquals(0, layer.width, "width 不回落（原版只回落 height）")
    }

    @Test
    fun `SETIMAGELAYERL 扁平通道与对象树通道读数一致`() {
        val env = SemTestEnv()
        // 走生产路径：FlatStatements 编码 → 直读解码（含 7 个类型槽 + 7 个表达式槽）。
        env.runFlat(parseErbLines("SETIMAGELAYERL \"flan_frame_1\", 5, 3, 4, 100, 0, 200").single())
        val layer = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!.get(5L)!!
        assertEquals("flan_frame_1", layer.spriteName)
        assertEquals(3, layer.xpos)
        assertEquals(4, layer.ypos)
        assertEquals(100, layer.width)
        assertEquals(0, layer.height, "sprite 不存在 ⇒ height 保持 0")
        assertEquals(200, layer.opacity)
        // 最小两参形态经扁平通道也不越界（后 6 槽按缺省读）。
        env.runFlat(parseErbLines("SETIMAGELAYERL \"b\", 6").single())
        val minimal = env.store.aux.peek<ImageLayerRegistry>(IMAGE_LAYER_SLOT)!!.get(6L)!!
        assertEquals(255, minimal.opacity)
        assertEquals(0, minimal.xpos)
    }

    // ------------------------------------------------------------------
    // SEQUENCEINPUT 执行
    // ------------------------------------------------------------------

    @Test
    fun `SEQUENCEINPUT 预置序列被下一次 TINPUT 消费且不触宿主`() {
        // spec: EmueraConsole.cs:699-722、:780-788 —— WaitInput 首行看 hasSequenceInput，
        // 有预置串就直接按回车推进（原版 PressEnterKey 按换行拆段，:1419-1530）。
        val env = SemTestEnv(InputOutcome(intValue = 99))
        env.run(instr(FunctionCode.SEQUENCEINPUT, ExpressionArgument(StrLiteral("-1\nABC"))))
        env.run(instr(FunctionCode.TINPUT, tInputArg()))
        assertEquals(-1L, env.store.readInt("RESULT", longArrayOf(0)), "第一段 -1 写 RESULT")
        assertTrue(env.input.requests.isEmpty(), "序列命中时未进宿主等待")
        // 第二段 "ABC" 解析不动整数 ⇒ RESULT 0；队列取空后回到宿主等待。
        env.run(instr(FunctionCode.TINPUT, tInputArg()))
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)))
        assertTrue(env.input.requests.isEmpty(), "第二段仍不触宿主")
        env.run(instr(FunctionCode.TINPUT, tInputArg()))
        assertEquals(1, env.input.requests.size, "序列耗尽后才真正等待")
        assertEquals(99L, env.store.readInt("RESULT", longArrayOf(0)), "宿主返回覆盖上一段读数")
    }

    @Test
    fun `SEQUENCEINPUT 扁平通道同样被消费`() {
        val env = SemTestEnv(InputOutcome(intValue = 99))
        env.runFlat(instr(FunctionCode.SEQUENCEINPUT, ExpressionArgument(StrLiteral("42"))))
        env.runFlat(instr(FunctionCode.TINPUT, tInputArg()))
        assertEquals(42L, env.store.readInt("RESULT", longArrayOf(0)))
        assertTrue(env.input.requests.isEmpty())
        assertTrue(!env.store.aux.peek<SequenceInputState>(SEQUENCE_INPUT_SLOT)!!.hasValue,
            "取空即清 hasSequenceInput")
    }

    @Test
    fun `SEQUENCEINPUT 字符串等待整段原样交付`() {
        val env = SemTestEnv(InputOutcome(strValue = "HOST"))
        env.run(instr(FunctionCode.SEQUENCEINPUT, ExpressionArgument(StrLiteral("甲\n乙"))))
        env.run(instr(FunctionCode.TINPUTS, tInputArg(strDef = true)))
        assertEquals("甲", env.store.readStr("RESULTS", longArrayOf(0)))
        assertTrue(env.input.requests.isEmpty())
        env.run(instr(FunctionCode.TINPUTS, tInputArg(strDef = true)))
        assertEquals("乙", env.store.readStr("RESULTS", longArrayOf(0)), "第二段原样交付")
        env.run(instr(FunctionCode.TINPUTS, tInputArg(strDef = true)))
        assertEquals("HOST", env.store.readStr("RESULTS", longArrayOf(0)), "队列耗尽后回落到宿主")
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 8 槽声明类型（spec: ArgumentBuilder.cs:2687-2690），只取与实际实参等长的前缀。 */
    private val layerTypes = listOf(
        ArgValueType.STR,
        ArgValueType.INT, ArgValueType.INT, ArgValueType.INT,
        ArgValueType.INT, ArgValueType.INT, ArgValueType.INT,
        ArgValueType.VOID,
    )

    private fun layerL(vararg terms: Expr?): ExpressionsArgument =
        ExpressionsArgument(layerTypes.take(terms.size), terms.toList())

    private fun tInputArg(strDef: Boolean = false) = SpTInputsArgument(
        IntLiteral(100),
        if (strDef) StrLiteral("") else IntLiteral(0),
        null, null, null, null,
    )

    /**
     * 语料解析用的符号表：Flan_UI 的 `L_FRAME_*` 常量与 USERCOM 的 `MACRO_PRESET` 数组
     * （`%MACRO_PRESET:MACRO_INDEX%` 是「变量 + `:` 下标」形态，不是函数调用）。
     */
    private fun corpusSymbols(): SymbolTable = SymbolTable()
        .declareVariable("L_FRAME_GID", OperandType.INT, 0)
        .declareVariable("L_FRAME_PAD_X", OperandType.INT, 0)
        .declareVariable("L_SHAPE_SHIFT", OperandType.INT, 0)
        .declareVariable("L_FRAME_PAD_Y", OperandType.INT, 0)
        .declareVariable("L_FRAME_W", OperandType.INT, 0)
        .declareVariable("L_FRAME_H", OperandType.INT, 0)
        .declareVariable("MACRO_PRESET", OperandType.STR, 1)

    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(corpusSymbols())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "st-stmts.erb", i + 1, text))
        }
    }
}
