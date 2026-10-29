package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.CharStream

/**
 * PRINT 家族的后缀解析结果 —— 逐行移植 PRINT_Instruction 的构造函数
 * （spec: FunctionIdentifier.cs:85-171）。
 *
 * ERB 的 PRINT 家族用**语句名后缀**编码行为：PRINT[V|S|FORM|FORMS][LC|C][K][D][N][L|W]，
 * 其中 V/S/FORM 决定参数形态（对应四个 ArgumentBuilder），其余位只影响输出效果。
 */
data class PrintSpec(
    val code: FunctionCode,
    val name: String,
    /** 参数形态：SP_PRINTV / STR_EXPRESSION / FORM_STR_NULLABLE / STR_NULLABLE */
    val argType: ArgType,
    /** 是否末尾换行（N 后缀为 false）。spec: FunctionIdentifier.cs:75, 146-151 */
    val isLineEnd: Boolean,
    /** SINGLE：单行独立显示。spec: FunctionIdentifier.cs:90-94 */
    val isSingleLine: Boolean = false,
    /** V：逐项输出表达式。spec: FunctionIdentifier.cs:95-100 */
    val isPrintV: Boolean = false,
    /** S：按字符串表达式输出。spec: FunctionIdentifier.cs:101-105 */
    val isS: Boolean = false,
    /** FORMS：先转义再按 FORM 串展开。spec: FunctionIdentifier.cs:106-111 */
    val isForms: Boolean = false,
    /** C：右对齐定宽输出。spec: FunctionIdentifier.cs:127-135 */
    val isC: Boolean = false,
    /** LC：左对齐定宽输出。spec: FunctionIdentifier.cs:121-126 */
    val isLC: Boolean = false,
    /** K：字符串类型转换。spec: FunctionIdentifier.cs:136-140 */
    val isK: Boolean = false,
    /** D：调试配色。spec: FunctionIdentifier.cs:141-145 */
    val isD: Boolean = false,
    /** N：不换行且下次可跳过。spec: FunctionIdentifier.cs:146-151 */
    val isN: Boolean = false,
    /** 原版 AInstruction.flag 的终值（含 8192 基值）。 */
    val flag: Int,
) {
    companion object {
        /**
         * 解析 PRINT 家族语句名。失败返回 null（原版抛 ExeEE，spec: FunctionIdentifier.cs:167-170）。
         */
        fun parse(code: FunctionCode): PrintSpec? {
            val name = code.name
            if (!name.startsWith("PRINT")) return null
            val st = CharStream(name)
            var flag = 8192
            st.jump(5) // 跳过 "PRINT"
            var isSingle = false
            var isV = false
            var isS = false
            var isForms = false
            var argType: ArgType
            if (st.currentEqualTo("SINGLE")) {
                flag = flag or 1026
                isSingle = true
                st.jump(6)
            }
            if (st.currentEqualTo("V")) {
                argType = ArgType.SP_PRINTV
                isV = true
                st.jump(1)
            } else if (st.currentEqualTo("S")) {
                argType = ArgType.STR_EXPRESSION
                isS = true
                st.jump(1)
            } else if (st.currentEqualTo("FORMS")) {
                argType = ArgType.STR_EXPRESSION
                isForms = true
                st.jump(5)
            } else if (st.currentEqualTo("FORM")) {
                argType = ArgType.FORM_STR_NULLABLE
                st.jump(4)
            } else {
                argType = ArgType.STR_NULLABLE
            }
            var isLC = false
            var isC = false
            if (st.currentEqualTo("LC")) {
                flag = flag or 2
                isLC = true
                st.jump(2)
            } else if (st.currentEqualTo("C")) {
                if (name == "PRINTFORMC") flag = flag or 2
                isC = true
                st.jump(1)
            }
            var isK = false
            if (st.currentEqualTo("K")) {
                flag = flag or 4098
                isK = true
                st.jump(1)
            }
            var isD = false
            if (st.currentEqualTo("D")) {
                flag = flag or 2050
                isD = true
                st.jump(1)
            }
            var isLineEnd = true
            var isN = false
            if (st.currentEqualTo("N")) {
                isLineEnd = false
                isN = true
                flag = flag or 512
                st.jump(1)
            }
            if (st.currentEqualTo("L")) {
                flag = flag or 256
                flag = flag or 4
                st.jump(1)
            } else if (st.currentEqualTo("W")) {
                flag = flag or 768
                st.jump(1)
            } else {
                flag = flag or 4
            }
            if (!st.eos) return null
            return PrintSpec(
                code = code, name = name, argType = argType, isLineEnd = isLineEnd,
                isSingleLine = isSingle, isPrintV = isV, isS = isS, isForms = isForms,
                isC = isC, isLC = isLC, isK = isK, isD = isD, isN = isN, flag = flag,
            )
        }
    }
}
