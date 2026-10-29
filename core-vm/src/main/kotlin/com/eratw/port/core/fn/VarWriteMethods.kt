package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.stmt.toExprToken
import com.eratw.port.core.vars.RefUserVar

/**
 * 变量写入类函数（[registerVarWriteMethods]）。
 *
 * 落地名单（5 条）：SETVAR / VARSETEX / EXISTVAR / ARRAYMSORT / ARRAYMSORTEX。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - ExistVarMethod :437-499，注册 :9208
 * - ArrayMultiSortExMethod :501-734，注册 :9224
 * - SetVarMethod :736-784，注册 :9222
 * - VarSetExMethod :786-928，注册 :9223
 * - ArrayMultiSortMethod :5080-5258，注册 :9122
 * 变量读写通道：`MinorShift.Emuera.GameData.Variable/VariableData.cs`（SetValueAll）与
 * `VariableStore.setAll` / `writeInt` / `writeStr`。
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:535-549`
 * （SETVAR=1 NOW=456、VARSETEX=9,9,9、EXISTVAR=1,6,0、MSORT_KA/MSORT_KB）。
 *
 * 刻意照抄的原版行为：
 * - SETVAR 的类型错误文案是**反的**：字符串变量收到非字符串值报 IsNotInt
 *   （"不是整型"），整数变量收到非整数报 IsNotStr（"不是字符串型"，:766-777）。
 * - VARSETEX 三维变量的结束下标默认值是 0（:819 的第二个 `IsArray2D` 显然应为
 *   `IsArray3D`），而不是第三维长度；三维写入还交换了第 0/1 维（:854、:919）。
 * - ARRAYMSORTEX 的第三个参数为 0（显式传入）时直接返回 0（:571-574），
 *   而 ARRAYMSORT 没有这个参数。
 */
internal fun registerVarWriteMethods(r: BuiltinRegistry) {
    val I = OperandType.INT

    fun refInt(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Expr?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max, evalRef = { c, e -> Value.IntV(fn(c, e)) }))
    }

    // ==================================================================
    // SETVAR：字符串变量名 → 动态解析为变量引用 → 写单个元素
    // ==================================================================

    // spec: FunctionMethodCreator.cs:736-784（2 个参数，第二个为 Any）
    refInt("SETVAR", 2, 2) { c, a ->
        val text = stringArg(c, a, 0, "SETVAR")
        val ref = requireWritableVarRef(c, "SETVAR", text)
        val targetIsStr = ref.operandType == OperandType.STR

        val value = a.getOrNull(1)
            ?: throw CodeEE("函数 SETVAR：缺少第 2 个参数")
        // spec: :764-779 —— 文案是反的：字符串变量报 IsNotInt，整数变量报 IsNotStr
        if (targetIsStr) {
            if (value.operandType != OperandType.STR) throw CodeEE("${text}不是整型")
            c.store.writeStr(ref.name, elementIndices(c, ref), value.getStrValue(c))
        } else {
            if (value.operandType != OperandType.INT) throw CodeEE("${text}不是字符串型")
            c.store.writeInt(ref.name, elementIndices(c, ref), value.getIntValue(c))
        }
        1L
    }

    // ==================================================================
    // VARSETEX：区间赋值
    // ==================================================================

    // spec: FunctionMethodCreator.cs:786-928（2..5 个参数；OmitStart=2）
    refInt("VARSETEX", 2, 5) { c, a ->
        val text = stringArg(c, a, 0, "VARSETEX")
        val ref = requireWritableVarRef(c, "VARSETEX", text)
        val dim = targetDimension(c, ref)
        // C# 的 arguments.Count 含省略位；本实现的 Expr 列表长度与源码书写一致。
        val startL = if (a.size >= 4) intArg(c, a, 3, "VARSETEX") else 0L
        val endL = if (a.size == 5) {
            intArg(c, a, 4, "VARSETEX")
        } else {
            when (dim) {
                1 -> c.store.getLength(ref.name).toLong()
                2 -> c.store.getLength(ref.name, 1).toLong()
                // spec: :819 —— 三维分支重复判断 IsArray2D，故默认结束下标恒为 0
                else -> 0L
            }
        }
        // 区间端点先按 Long 判界再 toInt：裸 toInt 会把 2^32 回绕成 0，让越界端点伪装成
        // 合法区间（0..N 的全量覆盖）。判界口径同 VariableStore.checkRangeEndpoints
        // （端点可等于长度、负终点=空区间不报），长度取被枚举的那一维。
        when (dim) {
            1 -> {
                val len = c.store.getLength(ref.name).toLong()
                checkRangeEndpoint(text, startL, len, isStart = true)
                checkRangeEndpoint(text, endL, len, isStart = false)
            }
            2 -> {
                val len = c.store.getLength(ref.name, 1).toLong()
                checkRangeEndpoint(text, startL, len, isStart = true)
                checkRangeEndpoint(text, endL, len, isStart = false)
            }
            3 -> {
                val len = c.store.getLength(ref.name, 2).toLong()
                checkRangeEndpoint(text, startL, len, isStart = true)
                checkRangeEndpoint(text, endL, len, isStart = false)
            }
            else -> Unit // 0 维 / 非数组：原版没有命中任何数组分支，整体 no-op
        }
        val start = startL.toInt()
        val end = endL.toInt()
        val all = a.size < 3 || (a[2]?.getIntValue(c) ?: 0L) != 0L

        if (ref.operandType == OperandType.STR) {
            val valueExpr = a.getOrNull(1)
            // spec: :824-826 —— 字符串目标收到非字符串值时抛 SetStrToInt
            if (valueExpr != null && valueExpr.operandType != OperandType.STR) {
                throw CodeEE("尝试将字符串赋值给非字符串变量\"$text\"")
            }
            val value = valueExpr?.getStrValue(c) ?: ""
            writeRangeStr(c, text, ref, value, start, end, all)
        } else {
            val valueExpr = a.getOrNull(1)
            // spec: :861-863 —— 整数目标收到非整数时抛 SetIntToStr
            if (valueExpr != null && valueExpr.operandType != OperandType.INT) {
                throw CodeEE("尝试将整数赋值给非整数型变量\"$text\"")
            }
            val value = valueExpr?.getIntValue(c) ?: 0L
            writeRangeInt(c, text, ref, value, start, end, all)
        }
        1L
    }

    // ==================================================================
    // EXISTVAR
    // ==================================================================

    // spec: FunctionMethodCreator.cs:437-499 —— 第二参数为 0/省略时返回标志位，
    // 非 0 时尝试把第一参数解析成表达式（能解析 → 1，否则 0）。
    refInt("EXISTVAR", 1, 2) { c, a ->
        val name = stringArg(c, a, 0, "EXISTVAR")
        val mode = optInt(c, a, 1, 0)
        if (mode == 0L) {
            existVarFlags(c, name)
        } else {
            try {
                parseExpression(c, "EXISTVAR", name)
                1L
            } catch (_: Exception) {
                // spec: :489-497 —— 任何解析失败都归 0
                0L
            }
        }
    }

    // ==================================================================
    // ARRAYMSORT / ARRAYMSORTEX
    // ==================================================================

    // spec: :5080-5258 —— 第一个参数是键，后续**所有**参数（含键自身）一起重排。
    // ArgTypes[0] = RefAny1D，1..n = 281（Ref|Array|Any|Variadic），OmitStart=1。
    refInt("ARRAYMSORT", 1, -1) { c, a ->
        val key = requireArray(c, "ARRAYMSORT", a[0], "第 1 个参数", requireOneD = true)
        val order = buildSortOrder(key)
        for (i in a.indices) {
            val view = requireArray(c, "ARRAYMSORT", a[i], "第 ${i + 1} 个参数")
            if (!view.reorder(order)) return@refInt 0L
        }
        1L
    }

    // spec: :501-734 —— 参数 0 可为变量或变量名字符串，参数 1 必须是字符串一维数组
    // （数组里每一项都是待重排变量的名字）；参数 2 = 升序开关，参数 3 = 参与排序的元素数。
    refInt("ARRAYMSORTEX", 2, 4) { c, a ->
        val key = convertKey(c, "ARRAYMSORTEX", a[0], requireOneD = false)
        // spec: :569 —— `arguments.Count < 3 || arguments[2] == null || GetIntValue() != 0`
        // ⇒ 第 3 位**省略/空位**一律升序（空位不是 0，不能按「=0 即降序」处理）；
        // 显式空位形态如 `ARRAYMSORTEX K, N, , 3` 同样升序。
        val ascending = a.size < 3 || a[2] == null || a[2]!!.getIntValue(c) != 0L
        val num = if (a.size < 4) -1L else intArg(c, a, 3, "ARRAYMSORTEX")
        // spec: :570-574 —— 显式 0 直接放弃
        if (num == 0L) return@refInt 0L
        val order = buildSortOrderEx(key, ascending, num)
            ?: return@refInt 0L

        val names = requireArray(c, "ARRAYMSORTEX", a[1], "第 2 个参数")
        if (!names.isStr || names.dimension != 1) {
            throw CodeEE("函数 ARRAYMSORTEX：第 2 个参数必须是字符串一维数组")
        }
        // spec: :625-630 —— 先把所有名字转换成 VariableTerm（list3），再逐个重排；
        // 因此任一名字非法时此前数组都还未被改动。
        val views = ArrayList<ArrayView>(names.rowCount)
        for (i in 0 until names.rowCount) {
            views.add(convertArray(c, "ARRAYMSORTEX", names.readStr(i, 0)))
        }
        for (view in views) {
            if (!view.reorder(order)) return@refInt 0L
        }
        1L
    }
}

// ---------------------------------------------------------------------------
// 动态表达式解析
// ---------------------------------------------------------------------------

private fun parseExpression(c: BuiltinContext, fn: String, text: String): Expr {
    return try {
        // 词法也在 try 内：LexError 不是 CodeEE，漏到执行层会穿过 TRYC（SETVAR/SETVARS/
        // ARRAYMSORTEX 走这里；原版 LexicalAnalyzer 抛的就是 CodeEE，spec: :501-734）。
        // 词法配置走 VM 条目表（SystemAllowFullSpace；spec: LexicalAnalyzer.cs:748-754 + Config.cs:370）。
        val tokens = ErbLexer(ConfigMethods.lexerConfig(c)).analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, 1)
        val parser = ExprParser(tokens.map { it.toExprToken() }, storeResolver(c), c.parserConfig)
        parser.reduceExpressionTerm() ?: throw CodeEE("函数 $fn：无法解释为表达式\"$text\"")
    } catch (e: LexError) {
        throw CodeEE("函数 $fn：${e.message}")
    } catch (e: ExprEvalException) {
        throw CodeEE("函数 $fn：${e.message}")
    } catch (e: ExprParseException) {
        throw CodeEE("函数 $fn：${e.message}")
    }
}

/**
 * 解析变量名并校验「可写变量引用」。
 *
 * spec: FunctionMethodCreator.cs:758-762 / :812-816 —— 非 VariableTerm、Identifier 为空
 * 或 IsConst 一律报 IsNotVar。Kotlin 侧以 [VarRef.isConst] 对应 IsConst。
 */
private fun requireWritableVarRef(c: BuiltinContext, fn: String, text: String): VarRef {
    val ast = parseExpression(c, fn, text)
    val ref = ast as? VarRef ?: throw CodeEE("$text 不是变量")
    if (ref.isConst) throw CodeEE("$text 不是变量")
    if (ref.isNoArg && ref.indices.isEmpty() && targetDimension(c, ref) >= 2) {
        // spec: VariableNoArgTerm.SetValue → MissingVarArg（VariableNoArgTerm.cs:21-36）
        throw CodeEE("变量 $text 的下标被省略")
    }
    return ref
}

private fun elementIndices(c: BuiltinContext, ref: VarRef): LongArray =
    LongArray(ref.indices.size) { ref.indices[it].getIntValue(c) }

private fun stringArg(c: BuiltinContext, a: List<Expr?>, i: Int, fn: String): String =
    (a.getOrNull(i)?.toValue(c) as? Value.StrV)?.v
        ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是字符串")

private fun intArg(c: BuiltinContext, a: List<Expr?>, i: Int, fn: String): Long =
    a.getOrNull(i)?.getIntValue(c)
        ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是整数")

private fun optInt(c: BuiltinContext, a: List<Expr?>, i: Int, def: Long): Long =
    if (i < a.size && a[i] != null) a[i]!!.getIntValue(c) else def

// ---------------------------------------------------------------------------
// VARSETEX 写入
// ---------------------------------------------------------------------------

private fun targetDimension(c: BuiltinContext, ref: VarRef): Int =
    c.store.resolveVarInfo(ref.name)?.dimension ?: ref.indices.size

/**
 * VARSETEX 区间端点先按 Long 判界再 toInt：裸 toInt 会把 2^32 回绕成 0，让越界端点
 * 伪装成合法区间。判界口径同 VariableStore.checkRangeEndpoints（端点可等于长度、
 * 负终点 = 空区间不报）。
 */
private fun checkRangeEndpoint(text: String, v: Long, len: Long, isStart: Boolean) {
    if (isStart && (v < 0L || v > len)) {
        throw CodeEE("变量 $text 的区间起点 $v 越界（长度 $len）")
    }
    if (!isStart && v > len) {
        throw CodeEE("变量 $text 的区间终点 $v 越界（长度 $len）")
    }
}

/**
 * VARSETEX 引用下标 → Int。C# 数组下标位对 long 做**带溢出检查**的转换（反编译呈
 * `(int)checked((nint)x)`：FunctionMethodCreator.cs:843/854/892/919），溢出抛异常而
 * 不是回绕；裸 toInt 会把 2^32 回绕成 0 静默写错行。这里只按 Int 域判界（越界 CodeEE），
 * 数组域越界仍由写入通道的 checkElement 报，与 C# 的 IndexOutOfRangeException 位对齐。
 */
private fun checkedSubscript(raw: Long, text: String, dim: Int): Int {
    if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
        throw CodeEE("函数 VARSETEX：变量 $text 的第 ${dim + 1} 维下标 $raw 超出 32 位整型范围")
    }
    return raw.toInt()
}

/**
 * 一维：SetValueAll 直接覆盖区间；角色变量写 0 号角色（spec: :834/:871 传 charaPos=0）。
 * 二维/三维：原版走 `GetArray()`（角色数据会抛 CallCharaVarAsVar，VariableToken.cs:283-296）。
 */
private fun writeRangeStr(
    c: BuiltinContext,
    text: String,
    ref: VarRef,
    value: String,
    start: Int,
    end: Int,
    all: Boolean,
) {
    val dim = targetDimension(c, ref)
    when (dim) {
        1 -> c.store.setAllStr(ref.name, value, start, end, charaPos = 0)
        2 -> {
            requirePlainArray(c, ref, "VARSETEX")
            val idx = elementIndices(c, ref)
            // C# :843 —— i0 作数组下标，long→int 是带溢出检查的转换（见 checkedSubscript）
            val i0 = checkedSubscript(idx.getOrElse(0) { 0L }, text, 0)
            // C# :841 —— i1 只作循环下界，源码显式 (int) 无溢出检查 ⇒ 保留截断语义
            val i1 = idx.getOrElse(1) { 0L }.toInt()
            for (i in maxOf(start, i1) until end) {
                c.store.writeStr(ref.name, longArrayOf(i0.toLong(), i.toLong()), value)
            }
        }
        3 -> {
            requirePlainArray(c, ref, "VARSETEX")
            val idx = elementIndices(c, ref)
            // C# :854 —— i1/i0 作数组下标，带溢出检查的转换
            val i0 = checkedSubscript(idx.getOrElse(0) { 0L }, text, 0)
            val i1 = checkedSubscript(idx.getOrElse(1) { 0L }, text, 1)
            // C# :852 —— i2 只作循环下界（显式 (int)，无溢出检查）⇒ 保留截断语义
            val i2 = idx.getOrElse(2) { 0L }.toInt()
            // spec: :854 —— 第 0/1 维交换
            for (j in maxOf(start, i2) until end) {
                c.store.writeStr(ref.name, longArrayOf(i1.toLong(), i0.toLong(), j.toLong()), value)
            }
        }
        else -> Unit // 0 维 / 非数组：原版没有任何分支命中，返回 1
    }
}

private fun writeRangeInt(
    c: BuiltinContext,
    text: String,
    ref: VarRef,
    value: Long,
    start: Int,
    end: Int,
    all: Boolean,
) {
    val dim = targetDimension(c, ref)
    when (dim) {
        1 -> c.store.setAll(ref.name, value, start, end, charaPos = 0)
        2 -> {
            requirePlainArray(c, ref, "VARSETEX")
            val idx = elementIndices(c, ref)
            // C# :882/:890 —— i1 只作循环下界（显式 (int)，无溢出检查）⇒ 保留截断语义
            val i1 = idx.getOrElse(1) { 0L }.toInt()
            if (all) {
                val rows = c.store.getLength(ref.name, 0)
                for (k in 0 until rows) {
                    for (l in maxOf(start, i1) until end) {
                        c.store.writeInt(ref.name, longArrayOf(k.toLong(), l.toLong()), value)
                    }
                }
            } else {
                // C# :892 —— i0 作数组下标，带溢出检查的转换；all 分支不取 i0（:876 只是
                // 求值成 long，从不转 Int），故检查只在非 all 分支做
                val i0 = checkedSubscript(idx.getOrElse(0) { 0L }, text, 0)
                for (m in maxOf(start, i1) until end) {
                    c.store.writeInt(ref.name, longArrayOf(i0.toLong(), m.toLong()), value)
                }
            }
        }
        3 -> {
            requirePlainArray(c, ref, "VARSETEX")
            val idx = elementIndices(c, ref)
            // C# :908/:917 —— i2 只作循环下界（显式 (int)，无溢出检查）⇒ 保留截断语义
            val i2 = idx.getOrElse(2) { 0L }.toInt()
            if (all) {
                val d0 = c.store.getLength(ref.name, 0)
                val d1 = c.store.getLength(ref.name, 1)
                for (n in 0 until d0) {
                    for (m in 0 until d1) {
                        for (j in maxOf(start, i2) until end) {
                            c.store.writeInt(
                                ref.name,
                                longArrayOf(n.toLong(), m.toLong(), j.toLong()),
                                value,
                            )
                        }
                    }
                }
            } else {
                // C# :919 —— i1/i0 作数组下标，带溢出检查的转换；all 分支不取（:898-900）
                val i0 = checkedSubscript(idx.getOrElse(0) { 0L }, text, 0)
                val i1 = checkedSubscript(idx.getOrElse(1) { 0L }, text, 1)
                // spec: :919 —— 第 0/1 维交换
                for (j in maxOf(start, i2) until end) {
                    c.store.writeInt(
                        ref.name,
                        longArrayOf(i1.toLong(), i0.toLong(), j.toLong()),
                        value,
                    )
                }
            }
        }
        else -> Unit
    }
}

/** 二维/三维写入前确认不是角色变量：原版 `VariableToken.GetArray()` 对角色数据抛错。 */
private fun requirePlainArray(c: BuiltinContext, ref: VarRef, fn: String) {
    val info = c.store.resolveVarInfo(ref.name)
    if (info?.isCharacterData == true) {
        throw CodeEE("函数 $fn：变量 ${ref.name} 是角色变量，不能整体操作")
    }
}

// ---------------------------------------------------------------------------
// EXISTVAR / 数组视图
// ---------------------------------------------------------------------------

/** spec: FunctionMethodCreator.cs:459-488 —— 1=int、2=string、4=const、8=二维、16=三维。 */
private fun existVarFlags(c: BuiltinContext, name: String): Long {
    val info = c.store.resolveVarInfo(name) ?: return 0L
    var flags = 0L
    if (info.operandType == OperandType.INT) flags = flags or 1L
    if (info.operandType == OperandType.STR) flags = flags or 2L
    if (info.isConst) flags = flags or 4L
    if (info.dimension == 2) flags = flags or 8L
    if (info.dimension == 3) flags = flags or 16L
    return flags
}

/**
 * 整块数组的读写视图（对应原版 `VariableToken.GetArray()`）。
 *
 * 原版排序函数拿到的是底层数组本体，**忽略变量引用上写的下标**（例如 `ARRAYMSORT A:2, B`
 * 排的是整个 A）。本类按名字访问整块，维度从 [VariableStore.getLength] 取。
 */
private class ArrayView(
    private val c: BuiltinContext,
    val name: String,
    val info: VarInfo,
    val dimension: Int,
) {
    val isStr: Boolean = info.operandType == OperandType.STR
    val rowCount: Int = length(0)

    fun length(dim: Int): Int {
        if (dimension == 1) return oneDLength()
        // spec: ReferenceToken.cs:45-56 / :116-123 —— 多维 REF 的 GetArray() 返回绑定目标
        // 数组本体，GetLength(d) 即目标形状，未绑定抛 EmptyRefVar；VariableStore.getLength
        // 已按该口径实现（绑定点随目标记录 targetDims）。排序据此对目标逐行重排
        // （FunctionMethodCreator.cs:5171-5243），与普通二维/三维数组同一路径。
        return c.store.getLength(name, dim)
    }

    private fun oneDLength(): Int {
        val uv = c.store.userVar(name)
        if (uv is RefUserVar) {
            uv.targetInt?.let { return it.size }
            uv.targetStr?.let { return it.size }
            throw CodeEE("引用变量 $name 未绑定目标（EmptyRefVar）")
        }
        return c.store.getLength(name)
    }

    /** 一维直接用下标；多维沿第 0 维逐行。返回该行的元素个数。 */
    fun rowWidth(): Int {
        if (dimension == 1) return 1
        var n = 1
        for (d in 1 until dimension) n *= length(d)
        return n
    }

    fun indices(row: Int, col: Int): LongArray = when (dimension) {
        1 -> longArrayOf(row.toLong())
        2 -> longArrayOf(row.toLong(), col.toLong())
        else -> {
            val d1 = length(1)
            longArrayOf(row.toLong(), (col / d1).toLong(), (col % d1).toLong())
        }
    }

    fun readInt(row: Int, col: Int): Long = c.store.readInt(name, indices(row, col))
    fun readStr(row: Int, col: Int): String = c.store.readStr(name, indices(row, col))
    fun writeInt(row: Int, col: Int, v: Long) = c.store.writeInt(name, indices(row, col), v)
    fun writeStr(row: Int, col: Int, v: String) = c.store.writeStr(name, indices(row, col), v)

    fun readStrRow(row: Int): String = readStr(row, 0)

    /** spec: :5145-5187 / :5190-5243 —— 只重排前 order.size 个元素/行，克隆语义相同。 */
    fun reorder(order: IntArray): Boolean {
        if (rowCount < order.size) return false
        if (dimension == 1) {
            // order[m] 是「写到位置 m 的元素」在原数组里的下标
            val snapshot = Array(order.size) { readStrOrInt(order[it]) }
            for (m in order.indices) writeCell(m, snapshot[m])
            return true
        }
        val width = rowWidth()
        val snapshot = Array(order.size) { r ->
            Array(width) { col ->
                if (isStr) Value.StrV(readStr(order[r], col)) else Value.IntV(readInt(order[r], col))
            }
        }
        for (m in order.indices) {
            for (col in 0 until width) writeCell(m, col, snapshot[m][col])
        }
        return true
    }

    private fun readStrOrInt(i: Int): Value =
        if (isStr) Value.StrV(readStr(i, 0)) else Value.IntV(readInt(i, 0))

    private fun writeCell(row: Int, value: Value) {
        when (value) {
            is Value.IntV -> writeInt(row, 0, value.v)
            is Value.StrV -> writeStr(row, 0, value.v)
        }
    }

    private fun writeCell(row: Int, col: Int, value: Value) {
        when (value) {
            is Value.IntV -> writeInt(row, col, value.v)
            is Value.StrV -> writeStr(row, col, value.v)
        }
    }
}

private fun requireArray(
    c: BuiltinContext,
    fn: String,
    expr: Expr?,
    argLabel: String,
    requireOneD: Boolean = false,
): ArrayView {
    val ref = expr as? VarRef
        ?: throw CodeEE("函数 $fn 的${argLabel}必须是变量引用")
    val info = c.store.resolveVarInfo(ref.name)
        ?: throw CodeEE("函数 $fn：未定义的变量 ${ref.name}")
    if (info.isConst) throw CodeEE("函数 $fn：变量 ${ref.name} 是常量，不能重排")
    if (info.isCharacterData) {
        throw CodeEE("函数 $fn：变量 ${ref.name} 是角色变量，不能重排")
    }
    if (info.dimension !in 1..3) {
        throw CodeEE("函数 $fn：变量 ${ref.name} 不是数组")
    }
    if (requireOneD && info.dimension != 1) {
        throw CodeEE("函数 $fn：$argLabel 必须是 1 维数组")
    }
    return ArrayView(c, ref.name, info, info.dimension)
}

/** ARRAYMSORTEX 的键：变量引用原样使用（不做一维校验，原版 :575），字符串则动态解析。 */
private fun convertKey(c: BuiltinContext, fn: String, expr: Expr?, requireOneD: Boolean): ArrayView {
    if (expr is VarRef) return requireArray(c, fn, expr, "第 1 个参数", requireOneD)
    val name = (expr?.toValue(c) as? Value.StrV)?.v
        ?: throw CodeEE("函数 $fn 的第 1 个参数必须是变量或变量名字符串")
    return convertArray(c, fn, name)
}

/** 名字 → 变量（原版 GetConvertedTerm，:556-565；const/角色/非数组的报错在 CheckVariableTerm）。 */
private fun convertArray(c: BuiltinContext, fn: String, name: String): ArrayView {
    val ast = parseExpression(c, fn, name)
    val ref = ast as? VarRef ?: throw CodeEE("函数 $fn：\"$name\" 不是变量")
    val info = c.store.resolveVarInfo(ref.name)
        ?: throw CodeEE("函数 $fn：未定义的变量 ${ref.name}")
    if (info.isConst) throw CodeEE("函数 $fn：\"$name\" 是常量")
    if (info.isCharacterData) throw CodeEE("函数 $fn：\"$name\" 是角色变量")
    if (info.dimension !in 1..3) throw CodeEE("函数 $fn：\"$name\" 不是数组")
    return ArrayView(c, ref.name, info, info.dimension)
}

// ---------------------------------------------------------------------------
// 排序键
// ---------------------------------------------------------------------------

/** spec: :5101-5138 —— 遇到第一个 0 / 空串即截止（键的剩余元素不参与）。 */
private fun buildSortOrder(key: ArrayView): IntArray {
    if (key.dimension != 1) throw CodeEE("函数 ARRAYMSORT：第 1 个参数必须是 1 维数组")
    val n = key.rowCount
    val indices = ArrayList<Int>(n)
    if (key.isStr) {
        for (i in 0 until n) {
            val s = key.readStrRow(i)
            if (s.isEmpty()) break
            indices.add(i)
        }
        return indices.sortedWith { x, y -> key.readStrRow(x).compareTo(key.readStrRow(y)) }.toIntArray()
    }
    for (i in 0 until n) {
        val v = key.readInt(i, 0)
        if (v == 0L) break
        indices.add(i)
    }
    // 原版比较器 `Math.Sign(a.Key - b.Key)`（FMC.cs:5117）：unchecked long 减法**回绕**——
    // 两键跨 0 距离 ≥2^63 时给出反向读数（病态序照原版复刻；2026-10-26 fixcore 审查 B3）。
    // 字符串键的 CurrentCulture 序（:5132）与 ordinal 的分歧见 verify-notes §16 登记（待 .NET 序例）。
    return indices.sortedWith { x, y ->
        val d = key.readInt(x, 0) - key.readInt(y, 0)
        if (d < 0) -1 else if (d > 0) 1 else 0
    }.toIntArray()
}

/**
 * spec: :567-608。
 *
 * 与 ARRAYMSORT 的两处差异：
 * - `num`（第 4 参数）可限定参与排序的元素数；-1 = 到第一个 0 为止。
 * - 整数键遇到 0 静默截止；字符串键在 num==-1 时遇到空串 **返回 0**（:600-603）。
 */
private fun buildSortOrderEx(key: ArrayView, ascending: Boolean, num: Long): IntArray? {
    if (key.dimension != 1) {
        // TODO(verify): 原版对二维键会强转 long[]（:580），实际抛 InvalidCastException；
        // 这里改为可读的 CodeEE，黄金用例不覆盖多维键。
        throw CodeEE("函数 ARRAYMSORTEX：键必须是 1 维数组")
    }
    val n = key.rowCount
    val limit = if (num > 0) minOf(num, n.toLong()).toInt() else n
    val indices = ArrayList<Int>(limit)
    if (key.isStr) {
        for (j in 0 until limit) {
            val s = key.readStrRow(j)
            if (num == -1L && s.isEmpty()) return null
            indices.add(j)
        }
        return indices.sortedWith { x, y ->
            val c = key.readStrRow(x).compareTo(key.readStrRow(y))
            if (ascending) c else -c
        }.toIntArray()
    }
    for (i in 0 until limit) {
        if (num != -1L || key.readInt(i, 0) != 0L) {
            indices.add(i)
        } else {
            break
        }
    }
    // 回绕比较器同 buildSortOrder（FMC.cs:590 的 Math.Sign(a.Key - b.Key) ± 升降位）。
    return indices.sortedWith { x, y ->
        val d = key.readInt(x, 0) - key.readInt(y, 0)
        val c = if (d < 0) -1 else if (d > 0) 1 else 0
        if (ascending) c else -c
    }.toIntArray()
}
