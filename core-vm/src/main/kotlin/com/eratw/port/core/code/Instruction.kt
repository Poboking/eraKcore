package com.eratw.port.core.code

/**
 * 定长指令与操作数编码（ADR-004："指令定长 4×u32（opcode, a, b, c）"）。
 *
 * ## 物理布局
 *
 * 一条指令 = 4 个 32 位字，**连续存放在 `IntArray`** 里（`code[pc*4 .. pc*4+3]`）。
 *
 * | 字 | 含义 |
 * |----|------|
 * | 0 | opcode（[Opcode] 常量，0..197 连续无空洞） |
 * | 1 | 操作数 a |
 * | 2 | 操作数 b |
 * | 3 | 操作数 c |
 *
 * **为什么是 `IntArray` 而不是 `LongArray`**（这是对派单里"LongArray 4 槽"的有意偏离）：
 * u32 的宽度就是 Kotlin `Int` 的宽度，用 `LongArray` 存 4 个 u32 每条指令要 32 字节，
 * 用 `IntArray` 只要 16 字节——按 2.6M 行语料估算指令数在千万级，差出来的是几百 MB
 * 常驻内存，直接顶穿 ADR-002 的"内存 < 400MB"验收门槛。而"2 个 Long 位打包"虽然也是
 * 16 字节，但每次取操作数都要 `ushr`+mask，热循环里白白多两条指令，也没有任何收益。
 * 结论：`IntArray` 4 字/指令是"同内存、最少解码指令"的唯一选择。
 *
 * 代价：单个操作数只能放 32 位，i64 立即数放不下。i64 常量一律走**常量池**
 * （[_SET_INT][Opcode._SET_INT] 的 b 是池下标，池元素为 `Long`），顺带获得去重收益。
 * 这符合 ADR-004 的"定长"约束，也让指令流保持定长、可随机寻址（跳转 = 指令下标）。
 *
 * ## 操作数寻址模式
 *
 * 操作数语义由 **opcode 决定**（同一 opcode 的操作数种类固定），不额外占 tag 位：
 *
 * | 模式 | 编码 | 适用范围 |
 * |------|------|----------|
 * | `IMM` | 原值（u32，按无符号读） | 标志位、实参数、表达式池下标 |
 * | `SLOT_REL` | bit31 = 0，值 = 帧内槽号 | 局部变量/参数（`frameBase + x`） |
 * | `SLOT_ABS` | bit31 = 1，值 = 全局槽号 | 全局变量（与帧槽分属不同数组，不冲突） |
 * | `CONST` | 常量池下标（Long 池） | i64 立即数（[_SET_INT]） |
 * | `STR` | 字符串池下标 | 驻留字符串（[_SET_STR]/[_PRINT_LITERAL]） |
 * | `EXPR` | 表达式池下标 | 预解析的 `core.expr.Expr`（[_EVAL_INT]/[_EVAL_STR]） |
 * | `STMT` | 语句池下标（契约 C2；u32 全宽，`a` 直存不加 tag） | 数据面语句 marker（操作码 29..197，契约 C3：`a=池下标, b=0, c=NONE`） |
 * | `FUNC` | 函数表下标 | [_CALL] |
 * | `OFFSET` | 有符号 32 位整数，单位 = **指令条数**，相对**下一条**指令 | 所有跳转 |
 *
 * 跳转单位是"指令条数"而非字偏移：`pc` 就是指令下标，`JUMP` 的语义是
 * `pc' = pc + 1 + a`。这样反汇编/调试器里 pc 与源码行可以一一对应，也不会因为
 * 将来改动指令宽度而让已编译的 `.erbc` 全部失效。
 *
 * 全局槽（`SLOT_ABS`）靠 bit31 与局部槽区分：解码只用一次 `raw < 0` 比较，热路径
 * 没有额外 mask 成本；`NONE` 另取 `0xFFFFFFFF`（即 -1），只用于"可缺省"的操作数。
 */
data class Instruction(
    val op: Int,
    val a: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
) {
    val name: String get() = Opcode.nameOf(op)

    fun encodeTo(dst: IntArray, wordBase: Int) {
        dst[wordBase] = op
        dst[wordBase + 1] = a
        dst[wordBase + 2] = b
        dst[wordBase + 3] = c
    }

    companion object {
        /** 每条指令占用的 32 位字数（ADR-004：定长 4×u32）。 */
        const val WORDS: Int = 4

        fun decodeFrom(src: IntArray, wordBase: Int): Instruction =
            Instruction(src[wordBase], src[wordBase + 1], src[wordBase + 2], src[wordBase + 3])
    }
}

/**
 * 槽操作数（`SLOT_REL` / `SLOT_ABS`）的编码工具。
 *
 * 槽号空间分两段：帧内槽（每个调用帧独立）与全局槽（全 VM 唯一）。两者物理上存放在
 * 不同数组里，靠 bit31 区分，见 [Instruction] 的模式表。
 */
object SlotAddr {
    /** 全局槽标志位（bit31）。 */
    const val ABS_BIT: Int = Int.MIN_VALUE

    /** "无此操作数"哨兵（0xFFFFFFFF），用于可缺省的槽（如 `_RET` 的返回槽）。 */
    const val NONE: Int = -1

    /**
     * 全局槽号上界（含）。
     *
     * `slot or ABS_BIT` 在 `slot == Int.MAX_VALUE` 时恰好得到 `0xFFFFFFFF` —— 就是 [NONE]
     * 哨兵本身（规约 `specs/erbc-format.md:23`、`:26-28`：0xFFFFFFFF 只表示「此处为 null」）。
     * 若不排除该值，一个**真实**全局槽会被读成「无此操作数」：[isNone] 为真、[format]
     * 打印 `none`，依赖可缺省操作数的指令（`_RET` 的 a、`_CALL` 的 c 等）语义被静默改写。
     * 故合法全局槽号只有 `0 .. 0x7FFFFFFE`（bit31 之外的 31 位，且不撞哨兵）。
     */
    const val MAX_GLOBAL_SLOT: Int = Int.MAX_VALUE - 1

    /** 全局槽号 → 操作数编码。 */
    fun global(slot: Int): Int {
        // 槽号必须落在 bit31 之外的正数区间（bit31 被标志位占用），且不得撞 NONE 哨兵。
        require(slot in 0..MAX_GLOBAL_SLOT) {
            "全局槽号越界: $slot（合法范围 0..$MAX_GLOBAL_SLOT；0x7FFFFFFF 编码后等于 NONE 哨兵）"
        }
        return slot or ABS_BIT
    }

    /** 局部槽号 → 操作数编码（帧内槽本来就是非负数）。 */
    fun local(slot: Int): Int {
        require(slot >= 0) { "局部槽号不能为负: $slot" }
        return slot
    }

    fun isGlobal(raw: Int): Boolean = raw < 0 && raw != NONE

    fun isNone(raw: Int): Boolean = raw == NONE

    /** 去掉标志位后的槽号（调用方需先用 [isGlobal] 判断）。 */
    fun index(raw: Int): Int = raw and ABS_BIT.inv()

    /** 人类可读形式：`R3` / `G7` / `none`。 */
    fun format(raw: Int): String = when {
        isNone(raw) -> "none"
        isGlobal(raw) -> "G" + index(raw)
        else -> "R" + raw
    }
}
