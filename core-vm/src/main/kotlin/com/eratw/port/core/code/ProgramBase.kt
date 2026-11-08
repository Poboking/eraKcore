package com.eratw.port.core.code

/**
 * 补丁装载期的 **base 身份**在 code 层的表示：六个基线，与 [Program] 的六个基线参数一一对应。
 *
 * ## 它是什么
 *
 * 补丁（设备端单独编译的口上 .ERB）在**绝对下标空间**里只占一个窗口：指令流从
 * `codeBase` 字之后开始，四个池与函数表各从自己的 `*Base` 之后开始。`.erbc` 产物本身只存
 * **本段**（窗口内）条目 —— 基线不进容器，只进补丁头（`patch/PatchHeader.kt` 的七个
 * `base-*` 字段）。装载期从补丁头取出这些值，经本类交给 [ErbcReader.decode]，
 * 读出来的 [Program] 才与写侧的绝对口径逐项相等。
 *
 * ## 为什么单独一个值对象
 *
 * [ErbcWriter] / [ErbcReader] 两侧都要按同一组基线折算「本段」与「绝对」：写侧判长度自洽
 * （行号表 / 池条数 / 指令条数只比本段），读侧建 [ConstantPool] 与 [Program] 时原样喂入。
 * 一个不可变值对象让「同一份基线」在两侧**逐字段相同**，而不是各自拼六个 Int 参数
 * （拼错一位就是池下标整体漂移，且只在带基线的产物上发作）。
 *
 * ## 字段口径（单位都是「字」或「条」，与对应容器字段同单位）
 *
 * * [codeBase]：本段第一条指令之前的 base 指令流**字数**（= [Instruction.WORDS] 的整数倍）。
 *   指令口径见 [instructionBase]。
 * * [functionBase]：本段第 0 个函数的绝对下标（`_CALL` 的 `a` 已是绝对口径）。
 * * [statementBase] / [stringBase] / [longBase] / [exprBase]：本段第 0 条语句 / 字符串 /
 *   常量 / 表达式在绝对下标空间里的位置。
 *
 * 负值与非对齐值**构造期直接拒绝**（`require` 点名参数），不做「取 0」之类的静默纠正：
 * 基线错一位，产物里的每一个池下标都会落到别的条目上。
 *
 * @property codeBase 代码基线（单位：字）
 * @property functionBase 函数表基线（单位：条）
 * @property statementBase 语句池基线（单位：条）
 * @property stringBase 字符串池基线（单位：条）
 * @property longBase 常量（Long）池基线（单位：条）
 * @property exprBase 表达式池基线（单位：条）
 */
data class ProgramBase(
    val codeBase: Int = 0,
    val functionBase: Int = 0,
    val statementBase: Int = 0,
    val stringBase: Int = 0,
    val longBase: Int = 0,
    val exprBase: Int = 0,
) {
    init {
        require(codeBase >= 0) { "codeBase 不能为负: $codeBase" }
        require(codeBase % Instruction.WORDS == 0) {
            "codeBase 必须是 Instruction.WORDS(${Instruction.WORDS}) 的整数倍" +
                "（单位是字，与 CodeSource.wordCount 同口径）: $codeBase"
        }
        require(functionBase >= 0) { "functionBase 不能为负: $functionBase" }
        require(statementBase >= 0) { "statementBase 不能为负: $statementBase" }
        require(stringBase >= 0) { "stringBase 不能为负: $stringBase" }
        require(longBase >= 0) { "longBase 不能为负: $longBase" }
        require(exprBase >= 0) { "exprBase 不能为负: $exprBase" }
    }

    /** [codeBase] 的指令口径（= `codeBase / Instruction.WORDS`）：本段第一条指令的绝对 pc。 */
    val instructionBase: Int get() = codeBase / Instruction.WORDS

    companion object {
        /** 无基线：全量产物的口径，六个基线全 0（与不传基线的装载路径逐位等价）。 */
        val NONE = ProgramBase()
    }
}
