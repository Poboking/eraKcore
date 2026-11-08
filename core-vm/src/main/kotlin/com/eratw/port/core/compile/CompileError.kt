package com.eratw.port.core.compile

import com.eratw.port.core.err.erbDisplayLine

/**
 * 编译期错误（ERB 语句 → 字节码）。
 *
 * 与解析层的 [com.eratw.port.core.stmt.StmtParseException] 分层：解析层保证块平衡与
 * 语法合法，编译层只对"语义上无法落成指令流"的情形报错（GOTO 目标缺失、CASE IS
 * 运算符非法、函数重名、块栈错位等）。
 *
 * [line] 为**内部 0 基**源码行下标（-1 = 函数级错误，如重名）；[functionName] 为所在 @函数名。
 * 键面（`Program.lines` / 切片语句 / 降级清单）继续吃 [line] 那个 0 基下标，**不改**；
 * 只有给人读的消息串按 [erbDisplayLine] 归一到 1 基 —— 全工程只许那一处 `+1`，
 * 与 [com.eratw.port.core.err.CodeEE.displayLine] / `stmt/ParseSupport.kt:45` 同一个出口
 * （原版也不在打印点补偏移，而是在 `ScriptPosition.cs:15-17` 的构造体里归一，
 * 于是 `Lang.cs:3334`「{0}の{1}行目で」、`Lang.cs:950`「警告Lv{0}:{1}:{2}行目:{3}」
 * 吃到的都是 1 基）。
 */
class CompileException(
    val functionName: String?,
    val line: Int,
    message: String,
) : RuntimeException("编译错误(函数:${functionName ?: "?"}, 行:${erbDisplayLine(line)}): $message")
