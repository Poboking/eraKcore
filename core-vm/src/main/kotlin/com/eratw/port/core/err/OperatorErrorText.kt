package com.eratw.port.core.err

/**
 * 运算符层运行期报错文案 —— 官方 `Lang.Error` 的 zhs 译文逐字（单一真源，别在调用点抄第二份）。
 *
 * 为什么要有这一层：同一条运算符错误在移植侧有**两条通道**（表达式求值
 * `core/expr/Expr.kt` 与字节码 `core/code/Vm.kt` 的 `_STK_*` 支），抄成两份就等于给官方
 * 原文留了「被某一路顺手改对」的口子（同 `ProcessRuntime.intToStrArgValue` 的理由）。
 *
 * 口径与照抄纪律见 `eratw-android/specs/` 目录下的 error-texts 文案表头（内核/契约层取 ja、
 * 使用者面诊断取 zhs；只做按序号原位替换，不 trim、不改引号形态、不修误译，ADR-005）。
 * 运算符报错是玩家屏上的 CodeEE ⇒ 取 zhs。下列每条都**两份 zhs 表同文**，
 * 所以本组对齐不受「语料根表 vs reference 表」的口径之争影响：
 *
 * | 键 | 日文原文 | 语料根 lang 表 | reference 表 | 原版抛出点 |
 * |---|---|---|---|---|
 * | DivideByZero | Lang.cs:1326 | emuera.zhs.xml:236 | emuera-zhs.xml:286 | OperatorMethodManager.cs:134（DivIntInt）/ :153（ModIntInt） |
 * | MultiplyNegativeToStr | Lang.cs:1318 | emuera.zhs.xml:486 | emuera-zhs.xml:284 | OperatorMethodManager.cs:99（MultStrInt） |
 * | Multiply10kToStr | Lang.cs:1322 | emuera.zhs.xml:485 | emuera-zhs.xml:285 | OperatorMethodManager.cs:103（MultStrInt） |
 *
 * 2026-11-04（lane errtext）收口：此前两条通道用的是自造串「除数为 0」与
 * 「负数(N)不能与字符串相乘」/「重复次数(N)不能大于等于 10000」；玩家上报里
 * 「除数为 0」（0.2.0/0.2.34/0.2.35 共 4 份）与「重复次数(10005)不能大于等于 10000」
 * （0.2.37 共 2 份）就是这两族。
 */

/** `Error.DivideByZero`（0 占位符）：整除与取余共用，本层四个操作码也共用这一条。 */
internal const val DIVIDE_BY_ZERO_TEXT = "除数为0的除法运算"

/** `Error.MultiplyNegativeToStr` 模板（字符串乘法的负实参支）。 */
internal const val MULTIPLY_NEGATIVE_TO_STR_TEMPLATE = "字符串无法与负值({0})进行相乘运算"

/** `Error.Multiply10kToStr` 模板（字符串乘法的 10000 及以上支）。 */
internal const val MULTIPLY_10K_TO_STR_TEMPLATE = "字符串无法与超过10000的值({0})进行相乘运算"

/** 按序号原位替换官方模板的 `{0}`，除此之外不做任何加工。 */
internal fun renderMultiplyToStrLimit(template: String, value: Long): String =
    template.replace("{0}", value.toString())
