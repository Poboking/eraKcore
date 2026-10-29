package com.eratw.port.core.code

/**
 * 反汇编器：字节码 → 文本。与 [Assembler] 构成往返（测试锁死两者一致）。
 *
 * 输出是**规范形式**：跳转一律显示为标签 `L<pc>`，函数入口显示为 `.func` 指示符，
 * 因此 `asm(disasm(p), p.expressions())` 会得到与原程序一模一样的指令字
 * （常量池/字符串池去重后顺序也一致，因为遍历顺序相同）。
 */
object Disassembler {

    fun disassemble(program: Program): String {
        val sb = StringBuilder()
        sb.append("; disassembled, ").append(program.instructionCount).append(" instructions\n")

        // 收集跳转目标（这些 pc 需要打标签）
        val targets = HashSet<Int>()
        for (pc in 0 until program.instructionCount) {
            val ins = program.instructionAt(pc)
            when (ins.op) {
                Opcode._JUMP -> targets += pc + 1 + ins.a
                Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> targets += pc + 1 + ins.b
                // 栈码组的三条跳转：a 与 _JUMP 同口径（相对下一条指令的偏移），
                // 同样打标签，否则回编时 `L<pc>` 无处可查。
                Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP ->
                    targets += pc + 1 + ins.a
            }
        }

        // 同一 entryPc 上可挂**多条**函数：汇编器 `.func a` 紧跟 `.func b` 且 a 无指令时
        // 两者共享入口（a.entryPc == b.entryPc）。旧实现用 HashMap 按 entryPc 覆写，
        // 只打印最后一条 —— asm(disasm(p)) 静默丢函数（往返差异，真 bug）。
        // groupBy 保声明序，全打印。
        //
        // entryPc == instructionCount 的**拖尾空函数**（`.func tail` 后无指令）没有任何
        // pc 命中它，旧实现同样整条丢失；循环后按声明序补打（汇编器 EOF 的
        // finishFunction 会把它登记回去，函数表逐字段复原）。
        val funcsAt = program.functions.groupBy { it.entryPc }

        for (pc in 0 until program.instructionCount) {
            for (f in funcsAt[pc].orEmpty()) appendFuncHeader(sb, f)
            if (pc in targets) sb.append('L').append(pc).append(":\n")
            sb.append("    ").append(format(program, pc)).append('\n')
        }
        // 跳转目标恰为指令流末尾（= instructionCount）时没有任何 pc 命中它，标签会丢 —
        // 回编报「未定义的标签」。补打在指令流之后：汇编器 defineLabel 落在 here()==count，
        // 偏移精确复原。目标**超过** count 的跳转（VM 取指即越界）无法用标签表示，
        // 保持显式失败，不静默改写偏移。
        val end = program.instructionCount
        if (end in targets) sb.append('L').append(end).append(":\n")
        for (f in program.functions) {
            if (f.entryPc >= program.instructionCount) appendFuncHeader(sb, f)
        }
        return sb.toString()
    }

    private fun appendFuncHeader(sb: StringBuilder, f: FunctionInfo) {
        sb.append("\n.func ").append(f.name)
            .append(" arity=").append(f.arity)
            .append(" slots=").append(f.slotCount).append('\n')
        // 函数内 `$标签` 表：`_JUMP_DYN` 的 a 是**这张表的下标**（[FunctionInfo.labelPcs]），
        // 不打印就等于 asm(disasm(p)) 把 `_JUMP_DYN` 指向空表 —— 指令字虽同，运行期跳到不存在的标签。
        // 名字走引号（可含空格），pc 直接打数字；`Assembler` 的 `.label` 指令原样登记回本表。
        for (i in f.labelNames.indices) {
            sb.append(".label ").append(quote(f.labelNames[i])).append(' ').append(f.labelPcs[i]).append('\n')
        }
    }

    private fun format(program: Program, pc: Int): String {
        val ins = program.instructionAt(pc)
        val a = ins.a
        val b = ins.b
        val c = ins.c
        return when (ins.op) {
            Opcode._NOP, Opcode._HALT -> ins.name

            Opcode._SET_INT -> "${ins.name} ${SlotAddr.format(a)}, ${program.constant(b)}"

            Opcode._SET_STR -> "${ins.name} ${SlotAddr.format(a)}, ${quote(program.string(b))}"

            Opcode._MOV_INT, Opcode._MOV_STR, Opcode._NEG, Opcode._NOT ->
                "${ins.name} ${SlotAddr.format(a)}, ${SlotAddr.format(b)}"

            Opcode._ADD, Opcode._SUB, Opcode._MUL, Opcode._DIV, Opcode._MOD,
            Opcode._EQ, Opcode._NE, Opcode._LT, Opcode._LE, Opcode._GT, Opcode._GE,
            -> "${ins.name} ${SlotAddr.format(a)}, ${SlotAddr.format(b)}, ${SlotAddr.format(c)}"

            Opcode._JUMP -> "${ins.name} L${pc + 1 + a}"

            Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE ->
                "${ins.name} ${SlotAddr.format(a)}, L${pc + 1 + b}"

            Opcode._CALL -> {
                val fname = program.functions.getOrNull(a)?.name ?: "#$a"
                "${ins.name} $fname, $b, ${SlotAddr.format(c)}"
            }

            Opcode._RET -> if (SlotAddr.isNone(a)) "_RET" else "_RET ${SlotAddr.format(a)}"

            Opcode._PRINT_INT, Opcode._PRINT_STR ->
                "${ins.name} ${SlotAddr.format(a)}" + if (b != 0) ", $b" else ""

            Opcode._PRINT_LITERAL ->
                "${ins.name} ${quote(program.string(a))}" + if (b != 0) ", $b" else ""

            Opcode._EVAL_INT, Opcode._EVAL_STR -> "${ins.name} \$$a, ${SlotAddr.format(b)}"

            // 编译器扩展指令（198..201）：形态与 Assembler.emitExtension 逐字对应。
            Opcode._CALL_BY_NAME -> "${ins.name} ${SlotAddr.format(a)}, $b, ${SlotAddr.format(c)}"

            Opcode._JUMP_BY_NAME ->
                "${ins.name} ${SlotAddr.format(a)}, $b" +
                    if (SlotAddr.isNone(c)) "" else ", ${SlotAddr.format(c)}"

            Opcode._JUMP_DYN -> "${ins.name} $a"

            Opcode._RETS -> if (SlotAddr.isNone(a)) "_RETS" else "_RETS ${SlotAddr.format(a)}"

            // 栈码组（202..243）：形态与 Assembler.emitStack 逐字对应，保证 asm(disasm(p)) 往返。
            // 逐条列出（不写 `in Opcode.STACK_OPS -> 名字` 的兜底）：新增栈码却忘了配形态时
            // 会掉进下面的 else 打成 `名字 a, b, c`，回编即显式失败，不会静默丢操作数。
            Opcode._STK_PUSH_IMM -> "${ins.name} ${program.constant(a)}"

            Opcode._STK_PUSH_STR -> "${ins.name} ${quote(program.string(a))}"

            Opcode._STK_PUSH_VAR_I, Opcode._STK_PUSH_VAR_S,
            Opcode._STK_STORE_VAR_I, Opcode._STK_STORE_VAR_S,
            -> "${ins.name} ${quote(program.string(a))}, $b"

            Opcode._STK_PLUS_VAR_I -> "${ins.name} ${quote(program.string(a))}, $b, $c"

            Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP ->
                "${ins.name} L${pc + 1 + a}"

            Opcode._STK_POP_SLOT_I, Opcode._STK_POP_SLOT_S -> "${ins.name} ${SlotAddr.format(a)}"

            Opcode._STK_DUP_I, Opcode._STK_DROP, Opcode._STK_DROP_S,
            Opcode._STK_ADD, Opcode._STK_SUB, Opcode._STK_MUL, Opcode._STK_DIV, Opcode._STK_MOD,
            Opcode._STK_NEG, Opcode._STK_BITAND, Opcode._STK_BITOR, Opcode._STK_BITXOR,
            Opcode._STK_SHL, Opcode._STK_SHR, Opcode._STK_BITNOT,
            Opcode._STK_EQ, Opcode._STK_NE, Opcode._STK_LT, Opcode._STK_LE, Opcode._STK_GT,
            Opcode._STK_GE, Opcode._STK_SEQ, Opcode._STK_SNE, Opcode._STK_SLT, Opcode._STK_SLE,
            Opcode._STK_SGT, Opcode._STK_SGE, Opcode._STK_CONCAT, Opcode._STK_SREPEAT,
            Opcode._STK_I2S,
            -> ins.name

            // ERB 语句：操作数语义未定（M2 第二批），原样打印三个字保证往返一致
            else -> "${ins.name} $a, $b, $c"
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
