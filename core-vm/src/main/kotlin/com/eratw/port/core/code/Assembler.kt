package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.stmt.Statement

/** 汇编期错误（文本 → 字节码）。 */
class AssemblyException(
    val line: Int,
    message: String,
) : RuntimeException("汇编错误(行:$line): $message")

/**
 * 文本汇编器：`asm("_SET_INT R0, 42")`。
 *
 * 只服务**测试与调试**（派单要求"文本 ↔ 字节码"），不是 erbcc 的前端——erbcc 从
 * ERB 源码直接构建 [CodeBuilder]，不经过这套文本语法。
 *
 * ## 语法
 *
 * ```
 * ; 注释（; 到行尾）
 * .func main slots=2          ; 声明函数：名字 + 形参数 + 帧槽数（可省，自动推断）
 *   _SET_INT R0, 0            ; 帧内槽写作 R<n>
 *   _SET_STR R1, "hello"      ; 字符串字面量
 *   _SET_INT G3, 1            ; 全局槽写作 G<n>
 *   _EVAL_INT $0, R0          ; $<k> = 调用方传入的第 k 个 Expr（见 assemble 的 exprs 参数）
 *   PRINT stmt#3              ; stmt#<k> = 语句池条目（见下）
 * loop:
 *   _ADD R0, R0, R1
 *   _LT R2, R0, R3
 *   _JUMP_IF_TRUE R2, loop    ; 目标可写标签或原始偏移
 *   _JUMP loop
 *   _CALL fib, 1, R2          ; 函数名（前向引用可）, 实参数, 返回目标槽
 *   _RET R2                   ; 省略操作数 = none（返回 0）
 *   _HALT
 * ```
 *
 * 操作数分隔用逗号或空白皆可。
 *
 * ## 语句池操作数 `stmt#<k>`（契约 C2/C3）
 *
 * ERB 语句助记符（[StatementAliases.resolve] 能解析的名字，操作码 29..197）接受**唯一**
 * 操作数形态 `stmt#<k>`：发射契约 C3 的 marker 编码
 * `op=语句操作码, a=k, b=0, c=SlotAddr.NONE(-1)`。`k` 是 [assemble] 的 `statements`
 * 参数的**列表下标**——该列表在汇编开始时**按序逐条**注册进语句池（恒不合并、不去重），
 * 因此池下标 == 列表下标 == marker 的 `a`。越界的 `k` 报 AssemblyException。
 *
 * 其余语句助记符仍做**原样发射**（0..3 个立即数，供 VM 未接线时显式失败）；
 * 反汇编器把语句操作码的 `a, b, c` 原样打印为整数（marker 即 `NAME k, 0, -1`），
 * 按立即数重新解析即可字节级往返。
 *
 * 标签作用域是**全局**的。原版 `$label` 是函数内局部作用域（isa.yaml: GOTO 语义），
 * 编译器会给标签加函数名前缀；这里保持全局以简化测试。
 */
object Assembler {

    /**
     * 汇编一段文本。
     *
     * @param exprs `$<k>` 操作数引用的预解析表达式（`core.expr.Expr`），由调用方用
     *   `ExprParser` 解析好后传入；VM 的 `_EVAL_*` 直接调用其求值方法。
     * @param statements `stmt#<k>` 操作数引用的语句 AST（`core.stmt.Statement`）。
     *   列表整体按序进池（下标稳定、不去重，见 [ConstantPool.addStatement]），
     *   供语句分派钩子（契约 C4 `VmHost.executeStatement`）按 `a` 取回实例。
     */
    fun assemble(
        text: String,
        exprs: List<Expr> = emptyList(),
        statements: List<Statement> = emptyList(),
    ): Program {
        val parser = Parser(text, exprs, statements)
        return parser.parse()
    }
}

/** 操作数 token：区分字符串字面量与普通标识符/数字。 */
private data class Tok(val text: String, val quoted: Boolean)

/** 语句池操作数前缀：`stmt#<k>`（k = 语句池下标，见 Assembler KDoc 与契约 C2/C3）。 */
private const val STMT_PREFIX = "stmt#"

private class Parser(
    private val text: String,
    private val externalExprs: List<Expr>,
    private val externalStatements: List<Statement>,
) {

    private val b = CodeBuilder()
    private val labels = HashMap<String, Int>()

    /** (pc, 操作数字, 标签名) —— 前向跳转回填。 */
    private val jumpFixups = ArrayList<JumpFixup>()

    /** (pc, 函数名, 行号) —— 前向函数引用回填。 */
    private val callFixups = ArrayList<CallFixup>()

    private var funcIndex = -1
    private var funcMaxSlot = -1
    private var declaredSlots = 0

    /**
     * 是否已经**结束过**任意一个函数（[finishFunction] 真的关掉过一个函数体）。
     *
     * 隐式 `main`（见 [ensureFunction]）只在「文本开头、任何 `.func`/`.end` 之前」成立：
     * 一旦某个函数被关闭，后续再出现裸指令就是「函数之外」，必须显式失败。
     * 旧实现没有这条判据，于是 `.end` 之后再写指令会静默再声明一个 `main`
     * （`CodeBuilder.declareFunction` 抛的 `IllegalArgumentException` 或 —— 若前一个函数不叫
     * main —— 用**最后那个函数**的 `funcMaxSlot` 回填 index 0 的帧大小，静默污染前一函数的槽数）。
     */
    private var closedAnyFunction = false
    private var currentLine = 0

    private data class JumpFixup(val pc: Int, val operand: Int, val label: String)
    private data class CallFixup(val pc: Int, val funcName: String, val line: Int)

    fun parse(): Program {
        // 语句池预注册：整表按序进池（不去重），保证 `stmt#k` 的 k == 池下标 == 列表下标。
        for (s in externalStatements) b.statement(s)

        val rawLines = text.split('\n')
        for ((idx, raw) in rawLines.withIndex()) {
            currentLine = idx + 1
            val line = stripComment(raw)
            val toks = tokenize(line)
            if (toks.isEmpty()) continue
            var start = 0
            // 标签：`name:` 独占或作为行首
            while (start < toks.size && !toks[start].quoted && toks[start].text.endsWith(":")) {
                val name = toks[start].text.dropLast(1)
                if (name.isNotEmpty()) defineLabel(name)
                start++
            }
            if (start >= toks.size) continue
            val head = toks[start]
            if (head.text.startsWith('.')) {
                directive(head.text.lowercase(), toks.subList(start + 1, toks.size))
                continue
            }
            val ops = toks.subList(start + 1, toks.size)
            instruction(head.text, ops)
        }

        finishFunction()
        if (b.here() == 0) throw AssemblyException(currentLine, "空程序")

        // 回填跳转
        for (f in jumpFixups) {
            val target = labels[f.label] ?: throw AssemblyException(currentLine, "未定义的标签: ${f.label}")
            b.patch(f.pc, f.operand, target - (f.pc + 1))
        }
        // 回填函数引用
        for (f in callFixups) {
            val index = b.functionIndex(f.funcName)
            if (index < 0) throw AssemblyException(f.line, "未定义的函数: ${f.funcName}")
            b.patch(f.pc, 1, index)
        }

        return b.build(0)
    }

    // ------------------------------------------------------------------
    // 行级解析
    // ------------------------------------------------------------------

    private fun directive(name: String, args: List<Tok>) {
        when (name) {
            ".func" -> {
                if (args.isEmpty()) fail("`.func` 缺少函数名")
                if (args[0].quoted) fail("函数名不能是带引号的字符串: ${args[0].text}")
                // `#` 前缀是 `_CALL #<下标>` 的原始下标语法：名为 `#5` 的函数经反汇编打回
                // `_CALL #5, ...` 会被回编成「下标 5」而不是「名为 #5 的函数」，往返静默
                // 换函数（真差异）。声明期显式失败，不让损坏下沉到字节码。
                if (args[0].text.startsWith('#')) {
                    fail("函数名不能以 # 开头（与 _CALL #<下标> 语法冲突）: ${args[0].text}")
                }
                if (b.functionIndex(args[0].text) >= 0) fail("函数重复声明: ${args[0].text}")
                finishFunction()
                var arity = 0
                var slots = 0
                for (t in args.drop(1)) {
                    val kv = t.text.split('=', limit = 2)
                    if (kv.size != 2) fail("无法解析的函数属性: ${t.text}")
                    when (kv[0].lowercase()) {
                        "arity" -> arity = parseInt(kv[1], "arity")
                        "slots" -> slots = parseInt(kv[1], "slots")
                        else -> fail("未知函数属性: ${kv[0]}")
                    }
                }
                declaredSlots = slots
                funcMaxSlot = slots - 1
                funcIndex = b.declareFunction(args[0].text, b.here(), arity, slots)
            }

            ".end" -> finishFunction()

            // 函数内 `$标签` 表（[Disassembler] 逐条打回；`_JUMP_DYN` 的 a 是这张表的下标，
            // 不落盘就等于 asm(disasm(p)) 把 `_JUMP_DYN` 指到不存在的标签上）。
            ".label" -> {
                if (funcIndex < 0) fail("`.label` 必须出现在某个 `.func` 之后")
                if (args.size != 2) fail("`.label` 需要 <名字> <pc> 两个参数，实际 ${args.size} 个")
                val pc = parseInt(args[1].text, "标签 pc")
                b.declareLabel(funcIndex, args[0].text, pc)
            }

            else -> fail("未知指示符: $name")
        }
    }

    private fun finishFunction() {
        if (funcIndex < 0) return
        b.setFunctionSlots(funcIndex, maxOf(declaredSlots, funcMaxSlot + 1))
        funcIndex = -1
        closedAnyFunction = true
    }

    private fun defineLabel(name: String) {
        if (name in labels) fail("标签重复定义: $name")
        labels[name] = b.here()
    }

    private fun instruction(mnemonic: String, ops: List<Tok>) {
        ensureFunction()
        val upper = mnemonic.uppercase()
        val op = Opcode.BY_NAME[upper]
            ?: StatementAliases.resolve(upper)
            ?: fail("未知助记符: $mnemonic")

        if (Opcode.isInternal(op)) {
            emitInternal(op, ops)
        } else if (Opcode.isStackOp(op)) {
            // 栈码组（202..243）：操作数形态见 [Opcode] 各常量 KDoc；
            // 与 [Disassembler] 的打印逐字对应，保证 asm(disasm(p)) 往返。
            emitStack(op, ops)
        } else if (Opcode.isExtension(op)) {
            // 编译器扩展指令（198..201）：操作数语义见 [Opcode] 各常量 KDoc；
            // 形态与 [Disassembler] 的打印逐字对应，保证 asm(disasm(p)) 往返。
            emitExtension(op, ops)
        } else if (ops.size == 1 && !ops[0].quoted && ops[0].text.startsWith(STMT_PREFIX, ignoreCase = true)) {
            // marker（契约 C3）：op=语句操作码, a=语句池下标, b=0, c=NONE
            statementOperand(op, ops[0])
        } else {
            // 169 条 ERB 语句：操作数原样发射（0..3 个立即数）
            if (ops.size > 3) fail("${Opcode.nameOf(op)} 最多 3 个操作数")
            for (t in ops) {
                if (t.text.startsWith(STMT_PREFIX, ignoreCase = true)) {
                    fail("`$STMT_PREFIX` 只能作为语句助记符的唯一操作数")
                }
            }
            val words = IntArray(3)
            for (i in ops.indices) words[i] = parseInt(ops[i].text, "操作数")
            b.emit(op, words[0], words[1], words[2], currentLine)
        }
    }

    /** `stmt#<k>` → 契约 C3 marker 编码；k 为语句池下标（= assemble 的 statements 列表下标）。 */
    private fun statementOperand(op: Int, tok: Tok) {
        val k = parseInt(tok.text.drop(STMT_PREFIX.length), "语句池下标")
        if (k !in externalStatements.indices) {
            fail("语句池下标越界: $k（共 ${externalStatements.size} 个；marker 语义: ${Opcode.nameOf(op)}）")
        }
        // k 已在 parse() 开头整体注册（池下标 == 列表下标），这里不再追加。
        b.emit(op, k, 0, SlotAddr.NONE, currentLine)
    }

    private fun emitInternal(op: Int, ops: List<Tok>) {
        when (op) {
            Opcode._NOP, Opcode._HALT -> {
                expectOps(ops, 0, Opcode.nameOf(op))
                b.emit(op, 0, 0, 0, currentLine)
            }

            Opcode._SET_INT -> {
                expectOps(ops, 2, "_SET_INT")
                b.emit(op, slot(ops[0]), b.constant(parseLong(ops[1].text)), 0, currentLine)
            }

            Opcode._SET_STR -> {
                expectOps(ops, 2, "_SET_STR")
                if (!ops[1].quoted) fail("_SET_STR 的第二个操作数必须是字符串字面量")
                b.emit(op, slot(ops[0]), b.string(ops[1].text), 0, currentLine)
            }

            Opcode._MOV_INT, Opcode._MOV_STR, Opcode._NEG -> {
                expectOps(ops, 2, Opcode.nameOf(op))
                b.emit(op, slot(ops[0]), slot(ops[1]), 0, currentLine)
            }

            Opcode._ADD, Opcode._SUB, Opcode._MUL, Opcode._DIV, Opcode._MOD,
            Opcode._EQ, Opcode._NE, Opcode._LT, Opcode._LE, Opcode._GT, Opcode._GE,
            -> {
                expectOps(ops, 3, Opcode.nameOf(op))
                b.emit(op, slot(ops[0]), slot(ops[1]), slot(ops[2]), currentLine)
            }

            Opcode._NOT -> {
                expectOps(ops, 2, "_NOT")
                b.emit(op, slot(ops[0]), slot(ops[1]), 0, currentLine)
            }

            Opcode._JUMP -> {
                expectOps(ops, 1, "_JUMP")
                val pc = b.emit(op, 0, 0, 0, currentLine)
                jumpOperand(pc, 1, ops[0])
            }

            Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> {
                expectOps(ops, 2, Opcode.nameOf(op))
                val pc = b.emit(op, slot(ops[0]), 0, 0, currentLine)
                jumpOperand(pc, 2, ops[1])
            }

            Opcode._CALL -> {
                if (ops.isEmpty()) fail("_CALL 缺少函数")
                val argc = if (ops.size >= 2) parseInt(ops[1].text, "实参数") else 0
                val retDst = if (ops.size >= 3) slot(ops[2]) else SlotAddr.NONE
                if (ops.size > 3) fail("_CALL 最多 3 个操作数")
                val fname = ops[0].text
                if (fname.startsWith('#')) {
                    b.emit(op, parseInt(fname.drop(1), "函数下标"), argc, retDst, currentLine)
                } else {
                    val pc = b.emit(op, 0, argc, retDst, currentLine)
                    callFixups += CallFixup(pc, fname, currentLine)
                }
            }

            Opcode._RET -> {
                if (ops.size > 1) fail("_RET 最多 1 个操作数")
                val src = ops.firstOrNull()?.let { slot(it) } ?: SlotAddr.NONE
                b.emit(op, src, 0, 0, currentLine)
            }

            Opcode._PRINT_INT, Opcode._PRINT_STR -> {
                if (ops.isEmpty() || ops.size > 2) fail("${Opcode.nameOf(op)} 需要 1..2 个操作数")
                val flags = if (ops.size == 2) parseInt(ops[1].text, "标志位") else 0
                b.emit(op, slot(ops[0]), flags, 0, currentLine)
            }

            Opcode._PRINT_LITERAL -> {
                if (ops.isEmpty() || ops.size > 2) fail("_PRINT_LITERAL 需要 1..2 个操作数")
                if (!ops[0].quoted) fail("_PRINT_LITERAL 的第一个操作数必须是字符串字面量")
                val flags = if (ops.size == 2) parseInt(ops[1].text, "标志位") else 0
                b.emit(op, b.string(ops[0].text), flags, 0, currentLine)
            }

            Opcode._EVAL_INT, Opcode._EVAL_STR -> {
                expectOps(ops, 2, Opcode.nameOf(op))
                b.emit(op, exprIndex(ops[0]), slot(ops[1]), 0, currentLine)
            }

            else -> fail("未支持的内置指令: ${Opcode.nameOf(op)}")
        }
    }

    /**
     * 编译器扩展指令（198..201）的汇编写回。
     *
     * 操作数形态与 [Disassembler] 的 `format` 分支一一对应：
     * - `_CALL_BY_NAME <名字槽>, <实参数|标志位>, <返回槽>`（3 个操作数）
     * - `_JUMP_BY_NAME <名字槽>, <try 标志>[, <命中标志槽>]`（2..3 个；省略 = none）
     * - `_JUMP_DYN <标签下标>`（1 个）
     * - `_RETS [<字符串槽>]`（0..1 个；省略 = none）
     */
    private fun emitExtension(op: Int, ops: List<Tok>) {
        when (op) {
            Opcode._CALL_BY_NAME -> {
                expectOps(ops, 3, "_CALL_BY_NAME")
                b.emit(
                    op, slot(ops[0]), parseInt(ops[1].text, "实参数/标志位"), slot(ops[2]),
                    currentLine,
                )
            }

            Opcode._JUMP_BY_NAME -> {
                if (ops.size !in 2..3) fail("_JUMP_BY_NAME 需要 2..3 个操作数，实际 ${ops.size}")
                val c = if (ops.size == 3) slot(ops[2]) else SlotAddr.NONE
                b.emit(op, slot(ops[0]), parseInt(ops[1].text, "try 标志"), c, currentLine)
            }

            Opcode._JUMP_DYN -> {
                expectOps(ops, 1, "_JUMP_DYN")
                b.emit(op, parseInt(ops[0].text, "标签下标"), 0, 0, currentLine)
            }

            Opcode._RETS -> {
                if (ops.size > 1) fail("_RETS 最多 1 个操作数")
                val src = ops.firstOrNull()?.let { slot(it) } ?: SlotAddr.NONE
                b.emit(op, src, 0, 0, currentLine)
            }

            else -> fail("未支持的扩展指令: ${Opcode.nameOf(op)}")
        }
    }

    /**
     * 栈码组（202..243，T2-21）的汇编写回。
     *
     * 操作数形态与 [Disassembler] 的 `format` 分支一一对应：
     * - `_STK_PUSH_IMM <i64 立即数>` —— 进常量池（与 `_SET_INT` 同口径，去重后按序落池）
     * - `_STK_PUSH_STR "<字符串>"` —— 进字符串池
     * - `_STK_PUSH_VAR_I|_S <"变量名">, <维数>` / `_STK_STORE_VAR_I|_S` 同构
     * - `_STK_PLUS_VAR_I <"变量名">, <维数>, <增量>`
     * - `_STK_JZ_POP|_STK_JNZ_POP|_STK_JZ_KEEP <标签或原始偏移>`（偏移落在 a）
     * - `_STK_POP_SLOT_I|_S <槽>`（`R<n>`/`G<n>`/`none`）
     * - 其余（算术/比较/字符串/`_STK_DUP_I`/`_STK_DROP`/`_STK_DROP_S`/`_STK_I2S`）无操作数
     *
     * 变量名走**字符串池**而不是裸标识符：变量名大小写与 Unicode 保持原样，落池后由
     * [Program.string] 直接取用，汇编期不做大小写归一（归一属变量层，口径见 VariableStore）。
     */
    private fun emitStack(op: Int, ops: List<Tok>) {
        when (op) {
            Opcode._STK_PUSH_IMM -> {
                expectOps(ops, 1, "_STK_PUSH_IMM")
                if (ops[0].quoted) fail("_STK_PUSH_IMM 的操作数必须是整数立即数，不能是字符串")
                b.emit(op, b.constant(parseLong(ops[0].text)), 0, 0, currentLine)
            }

            Opcode._STK_PUSH_STR -> {
                expectOps(ops, 1, "_STK_PUSH_STR")
                if (!ops[0].quoted) fail("_STK_PUSH_STR 的操作数必须是字符串字面量")
                b.emit(op, b.string(ops[0].text), 0, 0, currentLine)
            }

            Opcode._STK_PUSH_VAR_I, Opcode._STK_PUSH_VAR_S,
            Opcode._STK_STORE_VAR_I, Opcode._STK_STORE_VAR_S,
            -> {
                expectOps(ops, 2, Opcode.nameOf(op))
                if (!ops[0].quoted) fail("${Opcode.nameOf(op)} 的第一个操作数必须是变量名字符串")
                b.emit(op, b.string(ops[0].text), parseInt(ops[1].text, "维数"), 0, currentLine)
            }

            Opcode._STK_PLUS_VAR_I -> {
                expectOps(ops, 3, "_STK_PLUS_VAR_I")
                if (!ops[0].quoted) fail("_STK_PLUS_VAR_I 的第一个操作数必须是变量名字符串")
                b.emit(
                    op,
                    b.string(ops[0].text),
                    parseInt(ops[1].text, "维数"),
                    parseInt(ops[2].text, "增量"),
                    currentLine,
                )
            }

            Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP -> {
                expectOps(ops, 1, Opcode.nameOf(op))
                val pc = b.emit(op, 0, 0, 0, currentLine)
                jumpOperand(pc, 1, ops[0])
            }

            Opcode._STK_POP_SLOT_I, Opcode._STK_POP_SLOT_S -> {
                expectOps(ops, 1, Opcode.nameOf(op))
                b.emit(op, slot(ops[0]), 0, 0, currentLine)
            }

            Opcode._STK_DUP_I, Opcode._STK_DROP, Opcode._STK_DROP_S,
            Opcode._STK_ADD, Opcode._STK_SUB, Opcode._STK_MUL, Opcode._STK_DIV, Opcode._STK_MOD,
            Opcode._STK_NEG, Opcode._STK_BITAND, Opcode._STK_BITOR, Opcode._STK_BITXOR,
            Opcode._STK_SHL, Opcode._STK_SHR, Opcode._STK_BITNOT,
            Opcode._STK_EQ, Opcode._STK_NE, Opcode._STK_LT, Opcode._STK_LE, Opcode._STK_GT,
            Opcode._STK_GE, Opcode._STK_SEQ, Opcode._STK_SNE, Opcode._STK_SLT, Opcode._STK_SLE,
            Opcode._STK_SGT, Opcode._STK_SGE, Opcode._STK_CONCAT, Opcode._STK_SREPEAT,
            Opcode._STK_I2S,
            -> {
                expectOps(ops, 0, Opcode.nameOf(op))
                b.emit(op, 0, 0, 0, currentLine)
            }

            else -> fail("未支持的栈码指令: ${Opcode.nameOf(op)}")
        }
    }

    private fun jumpOperand(pc: Int, operand: Int, tok: Tok) {
        val raw = tok.text
        val asNumber = raw.toIntOrNull()
        if (asNumber != null) {
            b.patch(pc, operand, asNumber)
        } else {
            jumpFixups += JumpFixup(pc, operand, raw)
        }
    }

    private fun exprIndex(tok: Tok): Int {
        if (!tok.text.startsWith('$')) fail("表达式操作数必须写作 \\$<k>: ${tok.text}")
        val k = parseInt(tok.text.drop(1), "表达式下标")
        if (k !in externalExprs.indices) fail("表达式下标越界: $k（共 ${externalExprs.size} 个）")
        return b.expr(externalExprs[k])
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun ensureFunction() {
        if (funcIndex >= 0) return
        // 已结束过某个函数 ⇒ 这条裸指令落在「函数之外」。隐式 main 只在文本开头成立，
        // 显式失败而不是静默再声明一个 main（见 [closedAnyFunction]）。
        if (closedAnyFunction) {
            fail("指令出现在函数之外（`.end` 之后或 `.func` 之间）：隐式 main 只在文本未出现任何 `.func`/`.end` 时成立")
        }
        // 未声明 .func：整段文本隐式成为一个名为 main 的函数（方便单指令测试）
        funcMaxSlot = -1
        declaredSlots = 0
        funcIndex = b.declareFunction("main", b.here(), 0, 0)
    }

    /** 槽操作数：`R<n>`（帧内）/ `G<n>`（全局）/ `none`。 */
    private fun slot(tok: Tok): Int {
        if (tok.quoted) fail("槽操作数不能是字符串: ${tok.text}")
        val s = tok.text
        if (s.equals("none", ignoreCase = true)) return SlotAddr.NONE
        return when {
            s.startsWith("R") || s.startsWith("r") -> {
                val n = parseInt(s.substring(1), "局部槽号")
                if (n < 0) fail("局部槽号不能为负: $n")
                if (n > funcMaxSlot) funcMaxSlot = n
                SlotAddr.local(n)
            }

            s.startsWith("G") || s.startsWith("g") -> {
                val n = parseInt(s.substring(1), "全局槽号")
                if (n < 0) fail("全局槽号不能为负: $n")
                b.noteGlobalSlot(n)
                SlotAddr.global(n)
            }

            else -> fail("无法识别的槽操作数: $s（应写作 R<n>/G<n>/none）")
        }
    }

    private fun expectOps(ops: List<Tok>, n: Int, what: String) {
        if (ops.size != n) fail("$what 需要 $n 个操作数，实际 ${ops.size}")
    }

    private fun parseInt(s: String, what: String): Int =
        s.toIntOrNull() ?: fail("无法解析的$what: $s")

    private fun parseLong(s: String): Long =
        s.toLongOrNull() ?: fail("无法解析的立即数: $s")

    private fun fail(message: String): Nothing = throw AssemblyException(currentLine, message)

    private fun stripComment(line: String): String {
        var inStr = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && inStr -> i++
                ch == '"' -> inStr = !inStr
                ch == ';' && !inStr -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun tokenize(line: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++

                ch == '"' -> {
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < line.length) {
                        val c = line[i]
                        if (c == '\\' && i + 1 < line.length) {
                            sb.append(
                                when (val e = line[i + 1]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    '"' -> '"'
                                    '\\' -> '\\'
                                    else -> e
                                },
                            )
                            i += 2
                            continue
                        }
                        if (c == '"') {
                            closed = true
                            i++
                            break
                        }
                        sb.append(c)
                        i++
                    }
                    if (!closed) throw AssemblyException(currentLine, "字符串字面量未闭合")
                    out += Tok(sb.toString(), quoted = true)
                }

                else -> {
                    val start = i
                    while (i < line.length && !line[i].isWhitespace() && line[i] != ',') i++
                    out += Tok(line.substring(start, i), quoted = false)
                }
            }
        }
        return out
    }
}
