package com.eratw.port.core.code

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.loader.ErbcLoadException
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-1 重定位机器表的 schema 闸（specs/erbc-format.md §5.6b）。
 *
 * ## 这个闸治什么
 *
 * 「哪个操作数槽是哪个池」此前散在四份手写 when 里（链接器平移面、拼接判界面、字节路
 * 装载校验、映射路装载校验）。4.981 追加 `_RETF`(244) 时四份**一起**漏判：它的 a 是
 * 表达式池下标，但 `isStackOp(244)` 为假、marker 区间只到 197 ⇒ 既不平移也不判界。
 * 零基线模块带 `_RETF` 链接后运行期就地求值 **base 段**的表达式：返回别人的值，
 * 装载期零报错。范围闸对了不等于操作数对了（[ErbcFormat.OPCODE_MAX] 的 KDoc 记着前两次）。
 *
 * ## 对拍为什么不循环
 *
 * [specRef] 是规约表的**独立复述**（手写在测试里，不从 [OpcodeRelocation] 取）。
 * 四个消费方各自被探针喂真实产物，断言其行为 == [specRef]：
 *
 * - 表本身：逐操作码逐槽对拍 [specRef]（0..246 × a/b/c = 741 格）；
 * - 链接器：一个 247 函数的零基线模块真链接一次，逐槽断言「该平移的平移了、不该动的没动」；
 * - 拼接判界面：[checkPoolIndexBounds] 逐槽喂越界值（必拒）与巨值（非池引用槽必放行）；
 * - 两条装载路：逐池引用槽编码真实容器，字节路与映射路都必须拒且**文案逐字相同**，
 *   合法形态必须放行（否则「一律拒」也能骗过负控）。
 *
 * 追加操作码而漏登记分类：[OpcodeRelocation] 类初始化即抛，本闸第一条就红。
 */
class OpcodeRelocationTest {

    // ------------------------------------------------------------------
    // 规约表的独立复述（specs/erbc-format.md §5.6b；不从机器表取）
    // ------------------------------------------------------------------

    /** 非池引用槽的探针值：大到不可能是任何池的合法下标，且非负、不撞 NONE 哨兵。 */
    private val mark = 0x51515151

    private fun specRef(op: Int, slot: Int): PoolRef {
        // 不变量 1：c 槽在 0..246 全域都不是池引用。
        if (slot == 2) return PoolRef.NONE
        // isa 语句 marker：a = 语句池下标，b = 0，c = NONE（契约 C3）。
        if (op in 29..197) return if (slot == 0) PoolRef.STATEMENT else PoolRef.NONE
        val refSlot = when (op) {
            Opcode._SET_INT -> 1
            Opcode._SET_STR -> 1
            Opcode._PRINT_LITERAL -> 0
            Opcode._EVAL_INT, Opcode._EVAL_STR, Opcode._EVAL_INT_JZ -> 0
            Opcode._CALL -> 0
            Opcode._STK_PUSH_IMM -> 0
            Opcode._STK_PUSH_STR,
            Opcode._STK_PUSH_VAR_I,
            Opcode._STK_PUSH_VAR_S,
            Opcode._STK_PLUS_VAR_I,
            Opcode._STK_STORE_VAR_I,
            Opcode._STK_STORE_VAR_S,
            -> 0
            Opcode._RETF -> 0
            else -> return PoolRef.NONE
        }
        if (slot != refSlot) return PoolRef.NONE
        return when (op) {
            Opcode._SET_INT, Opcode._STK_PUSH_IMM -> PoolRef.CONSTANT
            Opcode._SET_STR, Opcode._PRINT_LITERAL -> PoolRef.STRING
            Opcode._EVAL_INT, Opcode._EVAL_STR, Opcode._EVAL_INT_JZ -> PoolRef.EXPR
            Opcode._CALL -> PoolRef.FUNCTION
            Opcode._RETF -> PoolRef.EXPR_NULLABLE
            else -> PoolRef.STRING
        }
    }

    /** 函数内跳转偏移与 `_JUMP_DYN` 的标签下标：探针里必须给合法值，否则装载期跳转闸先拒。 */
    private fun isJumpOperand(op: Int, slot: Int): Boolean = when (op) {
        Opcode._JUMP, Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP, Opcode._JUMP_DYN -> slot == 0
        Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> slot == 1
        // 条件位融合超指令（246）：跳转偏移在 c、相对下一条指令（与 _JUMP_IF_FALSE 同档）。
        Opcode._EVAL_INT_JZ -> slot == 2
        else -> false
    }

    /** 探针操作数：池引用槽给 0（合法下标），跳转槽给 0，其余给 [mark]；可覆盖单槽。 */
    private fun operands(op: Int, overrideSlot: Int = -1, overrideValue: Int = 0): IntArray =
        IntArray(3) { slot ->
            when {
                slot == overrideSlot -> overrideValue
                specRef(op, slot) != PoolRef.NONE -> 0
                isJumpOperand(op, slot) -> 0
                else -> mark
            }
        }

    private fun slotName(slot: Int): String = "abc"[slot].toString()

    private fun ctx(op: Int, slot: Int): String = "${Opcode.nameOf(op)}($op) 的 ${slotName(slot)} 槽"

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 四池各 1 条、单函数两指令的最小探针程序（[checkPoolIndexBounds] 与两条装载路共用）。 */
    private fun probeProgram(op: Int, v: IntArray, exprs: Int = 1): Program {
        val b = CodeBuilder()
        b.declareFunction("F", 0, arity = 0, slotCount = 4)
        b.constant(1L)
        b.string("s")
        repeat(exprs) { b.expr(IntLiteral(it.toLong(), it, 0)) }
        b.statement(RendStmt(VoidArgument(), 0, "PROBE"))
        b.emit(op, v[0], v[1], v[2], 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        return b.build(0)
    }

    /**
     * base：四池计数互不相同且非零（常量 3 / 字符串 5 / 表达式 7 / 语句 11），
     * 这样「平移了哪个基线」在读数上可区分，错平移一格就露馅。
     */
    private fun compileBase(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", 0, arity = 0, slotCount = 4)
        repeat(3) { b.emit(Opcode._SET_INT, 0, b.constant(100L + it), 0, it) }
        repeat(5) { b.emit(Opcode._SET_STR, 1, b.string("base$it"), 0, it) }
        repeat(7) { b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(it.toLong(), it, 0)), 2, 0, it) }
        repeat(11) {
            b.emit(Opcode.REND, b.statement(RendStmt(VoidArgument(), it, "BASE_REND_$it")), 0, SlotAddr.NONE, it)
        }
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 90)
        b.declareFunction("BASEFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 91)
        return b.build(entry)
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"), FuncMeta(index = 1, key = "BASEFN"))

    /** 零基线模块：246 个函数，每个函数的首指令是一条探针操作码（次指令 `_RET` 供跳转归属）。 */
    private fun probeModule(): LinkableModule {
        val b = CodeBuilder()
        b.constant(7L)
        b.string("mod串")
        b.expr(IntLiteral(9L, 0, 0))
        b.statement(RendStmt(VoidArgument(), 0, "MOD_REND"))
        val metas = ArrayList<FuncMeta>(Opcode.MAX_OPCODE + 1)
        for (op in 0..Opcode.MAX_OPCODE) {
            val name = "OP" + op.toString().padStart(3, '0')
            val entryPc = b.here()
            val fn = b.declareFunction(name, entryPc, arity = 0, slotCount = 4)
            // _JUMP_DYN 的 a 是函数内标签下标：每个函数都挂一个，探针给 0 才合法。
            b.declareLabel(fn, "L", entryPc + 1)
            val v = operands(op)
            b.emit(op, v[0], v[1], v[2], op)
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, op)
            metas.add(FuncMeta(index = op, key = name))
        }
        val prog = b.build(0)
        return object : LinkableModule {
            override val descriptor: ModuleDescriptor = ModuleDescriptor(packageId = "probe.opcodes")
            override val program: Program = prog
            override val functions: List<FuncMeta> = metas
            override fun signatureHashOf(functionKey: String): String = ""
        }
    }

    // ------------------------------------------------------------------
    // 1. 表本身
    // ------------------------------------------------------------------

    @Test
    fun `表稠密覆盖全部操作码且与规约逐槽一致`() {
        assertEquals(Opcode.MAX_OPCODE + 1, OpcodeRelocation.size, "表必须按 0..MAX_OPCODE 稠密建满")
        assertEquals(Opcode.NAMES.size, OpcodeRelocation.size, "表与名字表同长（追加操作码漏扩 NAMES 也在这里红）")
        assertEquals(247, OpcodeRelocation.size, "当前末条是 _EVAL_INT_JZ(246)；追加操作码时本钉跟着改")
        for (op in 0..Opcode.MAX_OPCODE) {
            val rel = OpcodeRelocation.of(op)
            for (slot in 0..2) {
                assertEquals(specRef(op, slot), rel.at(slot), ctx(op, slot))
            }
        }
    }

    @Test
    fun `越界操作码取分类当场拒`() {
        for (op in listOf(-1, Int.MIN_VALUE, Opcode.MAX_OPCODE + 1, 500, Int.MAX_VALUE)) {
            val ex = assertFailsWith<IllegalArgumentException>("op=$op") { OpcodeRelocation.of(op) }
            assertTrue(ex.message!!.contains("$op"), "报错要点名真值，实际: ${ex.message}")
        }
        // 槽号只由消费方的 0..2 循环喂进来 ⇒ 越界是内部不变量破坏（IllegalStateException），
        // 与上面「字节码里的 op 越界」（IllegalArgumentException）是两种性质的拒绝。
        assertFailsWith<IllegalStateException> { OperandRelocation.EMPTY.at(3) }
        assertFailsWith<IllegalStateException> { OperandRelocation.EMPTY.at(-1) }
    }

    @Test
    fun `FUNCTION只在CALL的a槽且EXPR_NULLABLE只在RETF的a槽`() {
        val functionSlots = ArrayList<String>()
        val nullableSlots = ArrayList<String>()
        for (op in 0..Opcode.MAX_OPCODE) {
            for (slot in 0..2) {
                when (OpcodeRelocation.of(op).at(slot)) {
                    PoolRef.FUNCTION -> functionSlots.add(ctx(op, slot))
                    PoolRef.EXPR_NULLABLE -> nullableSlots.add(ctx(op, slot))
                    else -> Unit
                }
            }
        }
        // 不变量 2：这两类各只有一处。多出来一处 = 有人加了新通道却没接过处置策略
        //（FUNCTION 要走 CallRelocation 语义决议，EXPR_NULLABLE 的哨兵不许平移）。
        assertEquals(listOf(ctx(Opcode._CALL, 0)), functionSlots)
        assertEquals(listOf(ctx(Opcode._RETF, 0)), nullableSlots)
    }

    // ------------------------------------------------------------------
    // 2. 链接器平移面
    // ------------------------------------------------------------------

    @Test
    fun `链接器平移面与规约逐槽一致`() {
        val base = compileBase()
        val baseInstr = base.instructionCount
        val funcStart = base.functions.size
        val longBase = base.constantCount
        val stringBase = base.stringCount
        val exprBase = base.exprCount
        val stmtBase = base.statementCount
        // 四个基线互不相同且非零：错平移一格、或把不该动的槽平移了，读数上都看得出来。
        assertEquals(listOf(3, 5, 7, 11), listOf(longBase, stringBase, exprBase, stmtBase))

        val linked = ModuleLinker.link(base, baseMetas, listOf(probeModule()), ResolverPolicy.MODERN).program
        for (op in 0..Opcode.MAX_OPCODE) {
            val pc = baseInstr + op * 2
            assertEquals(op, linked.op(pc), "pc=$pc 的操作码")
            val raw = operands(op)
            for (slot in 0..2) {
                val ref = specRef(op, slot)
                val expected = when {
                    // `_CALL.a` 是语义决议（模块内 a=0 → 模块首函数的绝对物理下标），不是机械平移。
                    op == Opcode._CALL && slot == 0 -> funcStart
                    ref == PoolRef.NONE -> raw[slot]
                    ref == PoolRef.CONSTANT -> raw[slot] + longBase
                    ref == PoolRef.STRING -> raw[slot] + stringBase
                    ref == PoolRef.EXPR || ref == PoolRef.EXPR_NULLABLE -> raw[slot] + exprBase
                    ref == PoolRef.STATEMENT -> raw[slot] + stmtBase
                    else -> error("规约复述漏了类别: $ref")
                }
                val actual = when (slot) {
                    0 -> linked.a(pc)
                    1 -> linked.b(pc)
                    else -> linked.c(pc)
                }
                assertEquals(expected, actual, "${ctx(op, slot)}（$ref）链接后")
            }
        }
    }

    @Test
    fun `RETF哨兵不平移且真实表达式下标随基线平移`() {
        val base = compileBase()
        val exprBase = base.exprCount
        assertTrue(exprBase > 0, "base 表达式池必须非空，否则「哨兵被平移」测不出来")
        val b = CodeBuilder()
        b.expr(IntLiteral(9L, 0, 0))
        val noneFn = b.declareFunction("RFNONE", 0, arity = 0, slotCount = 1)
        b.emit(Opcode._RETF, ErbcFormat.NONE_REF, 0, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        val exprPc = b.here()
        b.declareFunction("RFEXPR", exprPc, arity = 0, slotCount = 1)
        b.emit(Opcode._RETF, 0, 0, 0, 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 3)
        val module = object : LinkableModule {
            override val descriptor = ModuleDescriptor(packageId = "probe.retf")
            override val program = b.build(noneFn)
            override val functions = listOf(FuncMeta(index = 0, key = "RFNONE"), FuncMeta(index = 1, key = "RFEXPR"))
            override fun signatureHashOf(functionKey: String): String = ""
        }
        val linked = ModuleLinker.link(base, baseMetas, listOf(module), ResolverPolicy.MODERN).program
        val off = base.instructionCount
        // 缺陷本体：-1 若被平移就变成 exprBase-1，运行期求值 base 段末条表达式（返回别人的值）。
        assertEquals(ErbcFormat.NONE_REF, linked.a(off), "_RETF 的 NONE_REF 必须原样穿过链接")
        val shiftedPc = off + exprPc
        assertEquals(exprBase, linked.a(shiftedPc), "_RETF 的真实表达式下标必须加 exprBase")
        assertEquals(
            9L,
            (linked.expr(linked.a(shiftedPc)) as IntLiteral).value,
            "链接后 _RETF.a 必须仍指向模块自己那条表达式（漏平移时会读到 base 段的别人的表达式）",
        )
    }

    // ------------------------------------------------------------------
    // 3. 拼接判界面
    // ------------------------------------------------------------------

    @Test
    fun `拼接判界面与规约逐槽一致`() {
        for (op in 0..Opcode.MAX_OPCODE) {
            // 合法形态必须放行（否则「一律拒」也能骗过下面的负控）。
            checkPoolIndexBounds(probeProgram(op, operands(op)))
            for (slot in 0..2) {
                val ref = specRef(op, slot)
                if (ref == PoolRef.NONE) {
                    // 非池引用槽塞巨值也不判：判了就是把槽号/偏移/标志位当池下标。
                    checkPoolIndexBounds(probeProgram(op, operands(op, slot, mark)))
                } else {
                    // 探针程序四池与函数表各 1 条 ⇒ 下标 1 恰好越界。
                    val ex = assertFailsWith<IllegalArgumentException>("${ctx(op, slot)}（$ref）应判界") {
                        checkPoolIndexBounds(probeProgram(op, operands(op, slot, 1)))
                    }
                    assertTrue(ex.message!!.contains(ref.poolName), "${ctx(op, slot)} 文案要点名池，实际: ${ex.message}")
                }
            }
        }
    }

    @Test
    fun `拼接判界放行RETF哨兵与零基线模块的调用哨兵`() {
        // 哨兵 = 没有这一项，不判界（表达式池空也放行）。
        checkPoolIndexBounds(probeProgram(Opcode._RETF, intArrayOf(ErbcFormat.NONE_REF, mark, mark), exprs = 0))
        // 零基线模块形态：_CALL.a 的负哨兵链接期才回填。
        val p = probeProgram(Opcode._CALL, intArrayOf(-7, mark, mark))
        checkPoolIndexBounds(p, allowCallSentinels = true)
        val ex = assertFailsWith<IllegalArgumentException> { checkPoolIndexBounds(p) }
        assertTrue(ex.message!!.contains("函数表"), "拼接完成的产物不许残留哨兵，实际: ${ex.message}")
    }

    @Test
    fun `脏操作码在拼接路当场拒而不是留给VM`() {
        for (op in listOf(Opcode.MAX_OPCODE + 1, 500, -1)) {
            val ex = assertFailsWith<IllegalArgumentException>("op=$op") {
                checkPoolIndexBounds(probeProgram(op, intArrayOf(0, 0, 0)))
            }
            assertTrue(ex.message!!.contains("$op"), "报错要点名真值，实际: ${ex.message}")
        }
    }

    // ------------------------------------------------------------------
    // 4. 两条装载路
    // ------------------------------------------------------------------

    /**
     * 映射路走宿主真入口 [ProgramLoader.loadMapped]（含文件映射与段布局校验）。
     * `ProgramLoader.withSource` 会把解码异常包成带文件路径的 [ErbcLoadException]，
     * 判定与文案都在 cause 上 —— 对拍要拿 cause 的 message，不是外层那句带路径的。
     */
    private fun mappedDecodeFailure(f: Path): ErbcDecodeException {
        val e = assertFailsWith<ErbcLoadException>("映射路 ${f.fileName}") { ProgramLoader.loadMapped(f) }
        return assertIs<ErbcDecodeException>(e.cause, "映射路的真因必须是解码异常")
    }

    @Test
    fun `两条装载路的池判据与规约逐条一致`(@TempDir dir: Path) {
        for (op in 0..Opcode.MAX_OPCODE) {
            for (slot in 0..2) {
                val ref = specRef(op, slot)
                if (ref == PoolRef.NONE) continue
                val where = ctx(op, slot)
                val bad = ErbcWriter.encode(probeProgram(op, operands(op, slot, 1)))
                val good = ErbcWriter.encode(probeProgram(op, operands(op)))

                val e1 = assertFailsWith<ErbcDecodeException>("字节路 $where（$ref）") { ErbcReader.decode(bad) }
                val want = if (ref == PoolRef.FUNCTION) "函数下标" else ref.poolName
                assertTrue(e1.message!!.contains(want), "$where 文案要点名池，实际: ${e1.message}")
                assertTrue(e1.message!!.contains("越界"), "$where 实际: ${e1.message}")
                // 正控：同一形态只把下标改回 0 就必须装载成功。
                ErbcReader.decode(good)

                // 映射路是字节路的逐字副本：判定与文案都必须同源（单边改就是漂移）。
                // 用独立文件名：成功装载的映射路会把通道留给惰性语句池，覆写同一路径在 Windows 上会撞句柄。
                val f = dir.resolve("bad-$op-$slot.erbc")
                Files.write(f, bad)
                val e2 = mappedDecodeFailure(f)
                assertEquals(e1.message, e2.message, "$where 两条装载路文案")
            }
        }
    }

    @Test
    fun `RETF缺表达式池声明时两条装载路同口径`(@TempDir dir: Path) {
        val opts = ErbcEncodeOptions(includeExpressions = false)
        // 哨兵 + 声明不含表达式池 = 合法（无返回值的浮点返回通道）。
        val none = ErbcWriter.encode(probeProgram(Opcode._RETF, intArrayOf(ErbcFormat.NONE_REF, 0, 0), exprs = 0), opts)
        ErbcReader.decode(none)
        val nf = dir.resolve("retf-none.erbc")
        Files.write(nf, none)
        ProgramLoader.loadMapped(nf)

        // 真实下标 + 声明不含表达式池 = 拒载，文案与 _EVAL_* 同源。
        val bad = ErbcWriter.encode(probeProgram(Opcode._RETF, intArrayOf(0, 0, 0), exprs = 0), opts)
        val e1 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bad) }
        assertTrue(e1.message!!.contains("本文件声明不含表达式池"), "实际: ${e1.message}")
        assertTrue(e1.message!!.contains("_RETF"), "实际: ${e1.message}")
        val bf = dir.resolve("retf-noexpr.erbc")
        Files.write(bf, bad)
        val e2 = mappedDecodeFailure(bf)
        assertEquals(e1.message, e2.message, "两条装载路文案")
    }
}