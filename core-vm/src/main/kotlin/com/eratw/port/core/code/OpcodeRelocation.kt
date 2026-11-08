package com.eratw.port.core.code

/**
 * 一个操作数槽在**拼接/重定位**时的类别（specs/erbc-format.md §5.6b 的代码真源）。
 *
 * 只回答一个问题：这个槽是不是池下标、是哪个池的下标。槽号（[SlotAddr]）、函数内跳转
 * 偏移、标签下标、标志位、维数、增量一律是 [NONE] —— 它们在 base 段与续段之间语义不变，
 * 拼接时**一个字都不许动**。
 *
 * [poolName] 只供报错文案用（[NONE] 不进入任何文案分支）。
 */
enum class PoolRef(val poolName: String, val nullable: Boolean = false) {
    /** 非池引用：槽号 / 偏移 / 标签下标 / 标志位 / 立即数。平移与判界都跳过。 */
    NONE(""),

    /** i64 常量池下标。 */
    CONSTANT("常量池"),

    /** 驻留字符串池下标。 */
    STRING("字符串池"),

    /** 表达式池下标（必存，缺池即拒）。 */
    EXPR("表达式池"),

    /**
     * 表达式池下标，但允许 [ErbcFormat.NONE_REF]（-1）= 「没有这一项」。
     *
     * 目前只有 [_RETF][Opcode._RETF] 的 a（无返回值的浮点返回通道）。哨兵**不平移**：
     * -1 加上 exprBase 会落进 base 段的最后一条表达式，静默返回别人的值。
     */
    EXPR_NULLABLE("表达式池", nullable = true),

    /** 语句池下标（isa 语句 marker 的 a，契约 C3）。 */
    STATEMENT("语句池"),

    /**
     * 函数表下标。目前只有 [_CALL][Opcode._CALL] 的 a。
     *
     * 它**不是机械平移面**：零基线模块产物里 a 可以是负哨兵（super / 外部导入），
     * 链接期由 `core.patch.CallRelocation` 做语义决议（super 链 / 折 NOP / 导入 / 净偏移），
     * 决议完才是绝对下标。判界侧还要按 `functionBase` 折回（补丁调 base 公共函数）。
     */
    FUNCTION("函数表"),
}

/**
 * 一条指令三个操作数槽（a/b/c）的重定位分类。
 *
 * [at] 让消费方按槽号循环，不必为每个新操作码再写一遍 a/b/c 三支。
 */
class OperandRelocation(val a: PoolRef, val b: PoolRef, val c: PoolRef) {

    fun at(slot: Int): PoolRef = when (slot) {
        0 -> a
        1 -> b
        2 -> c
        else -> error("操作数槽号越界: $slot（合法 0..2 = a/b/c）")
    }

    companion object {
        /** 三槽都不是池引用（绝大多数操作码共用这一份，不每条指令建对象）。 */
        val EMPTY: OperandRelocation = OperandRelocation(PoolRef.NONE, PoolRef.NONE, PoolRef.NONE)
    }
}

/**
 * 逐操作码、逐操作数槽的重定位机器表（M2-1；规约 = specs/erbc-format.md §5.6b）。
 *
 * ## 为什么要有这张表
 *
 * 「哪些操作数是池引用」此前散在**四份**手写 `when` 里：链接器的平移面
 * （`core.patch.ModuleLinker.rewriteModuleInstructions`）、拼接出口的判界面
 * （[checkPoolIndexBounds]）、字节路与映射路两份逐字副本的装载校验
 * （`ErbcReader.Decoder.validate` / `loader.ErbcStream`）。四份各自维护的后果已经踩过两次：
 *
 * * T2-21 加栈码组（202..243）时上界没扩，脏字节一路装进 VM（[ErbcFormat] 的 KDoc 记着）；
 * * 4.981 追加 `_RETF`(244) 时，a 是**表达式池下标**，却因为 `isStackOp(244)` 为假、
 *   marker 区间只到 197 而**四份全都漏判**：零基线模块带 `_RETF` 链接后不平移，
 *   运行期就地求值 base 段的表达式 —— 返回别人的值，且装载期零报错。
 *
 * 所以分类只在这里写一次。消费方各自保留自己的**处置策略**（抛什么异常、什么文案、
 * 哨兵怎么折回），但「哪个槽是哪个池」一律问 [of]。
 *
 * ## 新增操作码的纪律（fail-closed）
 *
 * 表按 `0..Opcode.MAX_OPCODE` 稠密建满，**未登记的槽在类初始化时就抛** —— 不是留到
 * 某条指令执行时才静默错值。追加操作码必须同时：末尾追加常量、进 `Opcode.NAMES`、
 * 在这里 [of] 得到分类、在 specs §5.6b 补一行。漏掉第三步，`OpcodeRelocationTest`
 * 与任何一次装载都会当场红。
 *
 * ## 不变量
 *
 * * c 槽在 0..246 全域都是 [PoolRef.NONE]（`_STK_PLUS_VAR_I` 的 c 是 ±1 增量，
 *   `_CALL`/`_CALL_BY_NAME`/`_CALL_STR` 的 c 是返回槽）。将来若有 c 是池引用，
 *   消费方已经按三槽循环，不需要改代码，但 `OpcodeRelocationTest` 的 c 槽钉会红 ——
 *   那是有意的：这种变化必须过一次人工裁定。
 * * [PoolRef.FUNCTION] 只出现在 `_CALL.a`，且只走语义决议，不走机械平移。
 */
object OpcodeRelocation {

    private val TABLE: Array<OperandRelocation> = run {
        val size = Opcode.MAX_OPCODE + 1
        require(Opcode.NAMES.size == size) {
            "Opcode.NAMES 有 ${Opcode.NAMES.size} 条，但 MAX_OPCODE+1 = $size：" +
                "追加操作码必须同步扩 NAMES 与 MAX_OPCODE（只许末尾追加、不得重排）"
        }
        val t = arrayOfNulls<OperandRelocation>(size)

        fun put(op: Int, a: PoolRef = PoolRef.NONE, b: PoolRef = PoolRef.NONE, c: PoolRef = PoolRef.NONE) {
            require(t[op] == null) { "操作码 $op（${Opcode.nameOf(op)}）重复登记重定位分类" }
            t[op] = if (a == PoolRef.NONE && b == PoolRef.NONE && c == PoolRef.NONE) {
                OperandRelocation.EMPTY
            } else {
                OperandRelocation(a, b, c)
            }
        }

        // ---- VM 内部指令 0..28（操作数形态见 OpcodeTable 各常量 KDoc）----
        put(Opcode._NOP)
        put(Opcode._HALT)
        put(Opcode._SET_INT, b = PoolRef.CONSTANT)
        put(Opcode._SET_STR, b = PoolRef.STRING)
        put(Opcode._MOV_INT)
        put(Opcode._MOV_STR)
        put(Opcode._ADD)
        put(Opcode._SUB)
        put(Opcode._MUL)
        put(Opcode._DIV)
        put(Opcode._MOD)
        put(Opcode._NEG)
        put(Opcode._EQ)
        put(Opcode._NE)
        put(Opcode._LT)
        put(Opcode._LE)
        put(Opcode._GT)
        put(Opcode._GE)
        put(Opcode._NOT)
        put(Opcode._JUMP)
        put(Opcode._JUMP_IF_FALSE)
        put(Opcode._JUMP_IF_TRUE)
        put(Opcode._CALL, a = PoolRef.FUNCTION)
        put(Opcode._RET)
        put(Opcode._PRINT_INT)
        put(Opcode._PRINT_STR)
        put(Opcode._PRINT_LITERAL, a = PoolRef.STRING)
        put(Opcode._EVAL_INT, a = PoolRef.EXPR)
        put(Opcode._EVAL_STR, a = PoolRef.EXPR)

        // ---- isa 语句 marker 29..197：a = 语句池下标，b = 0，c = NONE（契约 C3）----
        for (op in ErbcFormat.MARKER_OPCODE_MIN..ErbcFormat.MARKER_OPCODE_MAX) {
            put(op, a = PoolRef.STATEMENT)
        }

        // ---- 编译器扩展 198..201：名字槽 / 标签下标，都不是池引用 ----
        put(Opcode._CALL_BY_NAME)
        put(Opcode._JUMP_BY_NAME)
        put(Opcode._JUMP_DYN)
        put(Opcode._RETS)

        // ---- 栈码组 202..243（T2-21）----
        put(Opcode._STK_PUSH_IMM, a = PoolRef.CONSTANT)
        put(Opcode._STK_PUSH_STR, a = PoolRef.STRING)
        put(Opcode._STK_DUP_I)
        put(Opcode._STK_DROP)
        put(Opcode._STK_DROP_S)
        put(Opcode._STK_PUSH_VAR_I, a = PoolRef.STRING)
        put(Opcode._STK_PUSH_VAR_S, a = PoolRef.STRING)
        put(Opcode._STK_PLUS_VAR_I, a = PoolRef.STRING)
        put(Opcode._STK_STORE_VAR_I, a = PoolRef.STRING)
        put(Opcode._STK_STORE_VAR_S, a = PoolRef.STRING)
        put(Opcode._STK_ADD)
        put(Opcode._STK_SUB)
        put(Opcode._STK_MUL)
        put(Opcode._STK_DIV)
        put(Opcode._STK_MOD)
        put(Opcode._STK_NEG)
        put(Opcode._STK_BITAND)
        put(Opcode._STK_BITOR)
        put(Opcode._STK_BITXOR)
        put(Opcode._STK_SHL)
        put(Opcode._STK_SHR)
        put(Opcode._STK_BITNOT)
        put(Opcode._STK_EQ)
        put(Opcode._STK_NE)
        put(Opcode._STK_LT)
        put(Opcode._STK_LE)
        put(Opcode._STK_GT)
        put(Opcode._STK_GE)
        put(Opcode._STK_SEQ)
        put(Opcode._STK_SNE)
        put(Opcode._STK_SLT)
        put(Opcode._STK_SLE)
        put(Opcode._STK_SGT)
        put(Opcode._STK_SGE)
        put(Opcode._STK_CONCAT)
        put(Opcode._STK_SREPEAT)
        put(Opcode._STK_I2S)
        put(Opcode._STK_JZ_POP)
        put(Opcode._STK_JNZ_POP)
        put(Opcode._STK_JZ_KEEP)
        put(Opcode._STK_POP_SLOT_I)
        put(Opcode._STK_POP_SLOT_S)

        // ---- 末尾追加段 244..246 ----
        put(Opcode._RETF, a = PoolRef.EXPR_NULLABLE)
        put(Opcode._CALL_STR)
        put(Opcode._EVAL_INT_JZ, a = PoolRef.EXPR)

        Array(size) { op ->
            requireNotNull(t[op]) {
                "操作码 $op（${Opcode.nameOf(op)}）未登记重定位分类：" +
                    "追加操作码必须在 OpcodeRelocation 与 specs/erbc-format.md §5.6b 同步登记" +
                    "（漏登记 = 池引用不平移/不判界，运行期静默读错段）"
            }
        }
    }

    /**
     * 取一条操作码的三槽分类。
     *
     * @throws IllegalArgumentException 操作码越出 `0..Opcode.MAX_OPCODE`（脏字节）。
     *   表内未登记的槽在类初始化时就已经抛过，走到这里不可能再缺。
     */
    fun of(op: Int): OperandRelocation {
        require(op in TABLE.indices) {
            "操作码 $op 越出 0..${Opcode.MAX_OPCODE}：无重定位分类（字节码损坏）"
        }
        return TABLE[op]
    }

    /** 表覆盖的操作码条数（= `Opcode.MAX_OPCODE + 1`，供 schema 闸对拍）。 */
    val size: Int get() = TABLE.size
}