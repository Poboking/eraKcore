package com.eratw.port.core.lexer

/**
 * CharStream —— 移植自 MinorShift.Emuera.Runtime.Utils/CharStream.cs
 * 单个逻辑行上的字符游标。流末尾用 '\u0000' 表示（与原版 EndOfString 一致）。
 * spec: CharStream.cs:10 (EndOfString = '\0')
 */
class CharStream(source: String?) {
    var source: String = source ?: ""
        private set
    var pointer: Int = 0
        private set

    val rowString: String get() = source

    val currentPosition: Int get() = pointer

    val current: Char
        get() = if (pointer >= source.length) END_OF_STRING else source[pointer]

    val next: Char
        get() = if (pointer + 1 >= source.length) END_OF_STRING else source[pointer + 1]

    val eos: Boolean get() = pointer >= source.length

    fun shiftNext() {
        pointer++
    }

    fun jump(skip: Int) {
        pointer += skip
    }

    /** spec: CharStream.cs:88-99 */
    fun substring(start: Int, length: Int): String {
        if (start >= source.length || length == 0) return ""
        val len = if (start + length > source.length) source.length - start else length
        return source.substring(start, start + len)
    }

    /** spec: CharStream.cs:146-149 */
    fun find(str: String): Int = source.indexOf(str, pointer) - pointer

    /** spec: CharStream.cs:167-181 */
    fun currentEqualTo(other: String): Boolean {
        if (pointer + other.length > source.length) return false
        for (i in other.indices) {
            if (source[pointer + i] != other[i]) return false
        }
        return true
    }

    /** spec: CharStream.cs:183-194 —— 判断当前位置起是否有三连相同符号 */
    fun tripleSymbol(): Boolean {
        if (pointer + 3 > source.length) return false
        if (source[pointer] == source[pointer + 1]) {
            return source[pointer] == source[pointer + 2]
        }
        return false
    }

    /** spec: CharStream.cs:205-223 */
    fun seek(offset: Int, origin: SeekOrigin) {
        pointer = when (origin) {
            SeekOrigin.BEGIN -> offset
            SeekOrigin.CURRENT -> pointer + offset
            SeekOrigin.END -> source.length + offset
        }
        if (pointer < 0) pointer = 0
    }

    fun seekEnd() = seek(0, SeekOrigin.END)

    companion object {
        const val END_OF_STRING = '\u0000'
    }
}

enum class SeekOrigin { BEGIN, CURRENT, END }
