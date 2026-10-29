package com.eratw.port.core.compile

/**
 * 编译期错误（ERB 语句 → 字节码）。
 *
 * 与解析层的 [com.eratw.port.core.stmt.StmtParseException] 分层：解析层保证块平衡与
 * 语法合法，编译层只对"语义上无法落成指令流"的情形报错（GOTO 目标缺失、CASE IS
 * 运算符非法、函数重名、块栈错位等）。
 *
 * [line] 为源码行号（-1 = 函数级错误，如重名）；[functionName] 为所在 @函数名。
 */
class CompileException(
    val functionName: String?,
    val line: Int,
    message: String,
) : RuntimeException("编译错误(函数:${functionName ?: "?"}, 行:$line): $message")
