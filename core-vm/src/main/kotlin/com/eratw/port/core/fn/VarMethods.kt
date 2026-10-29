package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.stmt.toExprToken
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VariableStore

/**
 * 第二批：依赖变量池的函数（CSV 访问器、数组区间统计、VARSIZE、GETVAR 家族）。
 *
 * 需要引用参数（变量身份）的函数（SUMARRAY/FINDCHARA/GETNUM 等）实现为
 * [BuiltinMethod.evalRef]——第一参数保持 [Expr] 形态，取出 [VarRef] 后：
 * - 元素下标经 ctx 求值（对应原版 VariableTerm.GetElementInt）；
 * - 数值读取经 [VariableStore.readInt]（角色区间扫描时第 0 维替换为循环变量，
 *   对应原版 GetArraySumChara 的 `new long[2] { i, p.Index2 }`，spec: VariableEvaluator.cs:429-437）。
 *
 * ## SP 门控的唯一判定源（CONTRACT-M4-I §2 i-erbcround「顺带收」）
 *
 * 本文件原先把「SP 角色功能未开启」的字面量写死在每个函数体里，等于绕过了兼容性开关
 * （`CompatiSPChara` 打开后仍一律抛错）。现统一为：
 * - 判定源 = [spCharaEnabled]（CompatiOptions.kt，缺省 false，spec: ConfigData.cs:106）；
 * - 文案 = [SP_CHARACTER_FEATURE_DISABLED]（`Lang.cs:3826` 的 zhs 表形；
 *   抛出点 `FunctionMethodCreator.cs:3168` / `:3224` / `:3258` / `:3315` / `:3414`）。
 *   语句层 `ADDSPCHARA` 用的是另一条 id（[SP_CHARA_CONFIG_IS_OFF]），两条日文源文案逐字相同
 *   但 zhs 译文不同（`lang/emuera.zhs.xml:631` 与 `:632`），故**不能合并成一个串**。
 */
internal fun registerVarMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR
    fun int(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }
    fun str(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }
    fun refInt(
        name: String,
        min: Int,
        max: Int,
        omitStart: Int = -1,
        fn: (BuiltinContext, List<Expr?>) -> Long,
    ) {
        r.register(
            BuiltinMethod(name, I, min, max, omitStart, evalRef = { c, e -> Value.IntV(fn(c, e)) }),
        )
    }
    fun refStr(
        name: String,
        min: Int,
        max: Int,
        omitStart: Int = -1,
        fn: (BuiltinContext, List<Expr?>) -> String,
    ) {
        r.register(
            BuiltinMethod(name, S, min, max, omitStart, evalRef = { c, e -> Value.StrV(fn(c, e)) }),
        )
    }

    // ---- 角色检索 / CSV 访问器 ----

    // spec: FunctionMethodCreator.cs:3110-3154 GetcharaMethod + VariableEvaluator.cs:1551-1573
    // 原版分两段，两段对第二实参的态度**不同**：
    //   ① SP 关闭（:3128-3131）：`if (!Config.CompatiSPChara) return GetChara(no);`
    //      —— 第二实参连求值都不发生 ⇒ `GETCHARA 10, 1` 在 SP 关闭下不报错、按 NO 线性首命中
    //      （官方形态，ADR-005 原样复刻，不「顺手修好」成报错）。
    //   ② SP 开启（:3133-3151）：isSp=0 走 `GetChara_UseSp(no, false)`；
    //      isSp≠0 先按非 SP 查，返回 -1 再按 SP 查（两级回落）。
    // SP 身份谓词 = `CFlag[0] != 0 == getSp`（GetChara_UseSp，spec: VariableEvaluator.cs:1563-1573）；
    // 移植侧 CFLAG 就是角色 1D 整数变量（SystemLayout.kt:105、VariableSizes.kt:100、
    // 装载写回 VariableStore.kt:524），故该谓词可逐字复现，见 [findCharaByNoSp]。
    int("GETCHARA", 1, 2) { c, a ->
        val no = iv(a[0]!!)
        if (!spCharaEnabled(c.host)) {
            findCharaByNo(c, no)
        } else {
            val wantSp = optInt(a, 1, 0) != 0L
            if (!wantSp) {
                findCharaByNoSp(c, no, sp = false)
            } else {
                val nonSp = findCharaByNoSp(c, no, sp = false)
                if (nonSp != -1L) nonSp else findCharaByNoSp(c, no, sp = true)
            }
        }
    }
    // spec: FunctionMethodCreator.cs:3155-3174 GetspcharaMethod ——
    // SP 关闭时在**求实参之前**抛 SPCharacterFeatureDisabled（:3166-3169）；
    // SP 开启则 `GetChara_UseSp(no, getSp: true)`（:3171）。
    int("GETSPCHARA", 1, 1) { c, a ->
        if (!spCharaEnabled(c.host)) throw CodeEE(SP_CHARACTER_FEATURE_DISABLED)
        findCharaByNoSp(c, iv(a[0]!!), sp = true)
    }
    // spec: FunctionMethodCreator.cs:3175-3229 CsvStrDataMethod + VariableEvaluator.cs:1584-1634
    // 模板不存在 → CodeEE（RefUndefinedChara）
    // 门控口径 = `!Config.CompatiSPChara && isSp != 0` 才抛（:3222-3225）。
    // SP 开启时 isSp 只透传给 `GetCharacterTemplate_UseSp`，而该方法的 `sp` 形参
    // **全方法体未被读取**（spec: ConstantData.cs:1230-1238 只看 index）
    // ⇒ SP 形参与非 SP 形参同一条查找路（官方缺陷原样复刻，ADR-005）。
    for ((fnName, field) in mapOf(
        "CSVNAME" to "name", "CSVCALLNAME" to "callname",
        "CSVNICKNAME" to "nickname", "CSVMASTERNAME" to "mastername",
    )) {
        str(fnName, 1, 2) { c, a ->
            val no = iv(a[0]!!)
            if (!spCharaEnabled(c.host) && optInt(a, 1, 0) != 0L) throw CodeEE(SP_CHARACTER_FEATURE_DISABLED)
            val tmpl = c.store.template(no) ?: throw codeEE(fnName, "引用未定义的角色（NO $no）")
            when (field) {
                "name" -> tmpl.name
                "callname" -> tmpl.callname
                "nickname" -> tmpl.nickname
                else -> tmpl.mastername
            }
        }
    }
    // spec: FunctionMethodCreator.cs:3230-3263 CsvcstrMethod（门控 :3255-3258，
    // SP 形参同样落进未被读取的 GetCharacterTemplate_UseSp）
    str("CSVCSTR", 2, 3) { c, a ->
        val no = iv(a[0]!!)
        val idx = iv(a[1]!!)
        if (!spCharaEnabled(c.host) && optInt(a, 2, 0) != 0L) throw CodeEE(SP_CHARACTER_FEATURE_DISABLED)
        val tmpl = c.store.template(no) ?: throw codeEE("CSVCSTR", "引用未定义的角色（NO $no）")
        val size = csvArraySize(c, "CSTR")
        if (idx < 0 || idx >= size) throw codeEE("CSVCSTR", "下标 $idx 越界（CSTR 长度 $size）")
        tmpl.cstr[idx.toInt()] ?: ""
    }
    // spec: FunctionMethodCreator.cs:3264-3320 CsvDataMethod + VariableEvaluator.cs:1637-1670
    // 模板不存在 → CodeEE；下标越界（≥对应变量长度）→ CodeEE；键缺失 → 0
    // 门控口径 = `!Config.CompatiSPChara && isSp != 0` 才抛（:3313-3316）。
    for ((fnName, mapField, varName) in listOf(
        Triple("CSVBASE", "maxbase", "BASE"),
        Triple("CSVABL", "abl", "ABL"),
        Triple("CSVMARK", "mark", "MARK"),
        Triple("CSVEXP", "exp", "EXP"),
        Triple("CSVRELATION", "relation", "RELATION"),
        Triple("CSVTALENT", "talent", "TALENT"),
        Triple("CSVCFLAG", "cflag", "CFLAG"),
        Triple("CSVEQUIP", "equip", "EQUIP"),
        Triple("CSVJUEL", "juel", "JUEL"),
    )) {
        int(fnName, 2, 3) { c, a ->
            val no = iv(a[0]!!)
            val idx = iv(a[1]!!)
            if (!spCharaEnabled(c.host) && optInt(a, 2, 0) != 0L) throw CodeEE(SP_CHARACTER_FEATURE_DISABLED)
            val tmpl = c.store.template(no) ?: throw codeEE(fnName, "引用未定义的角色（NO $no）")
            val size = csvArraySize(c, varName)
            if (idx < 0 || idx >= size) throw codeEE(fnName, "下标 $idx 越界（$varName 长度 $size）")
            (when (mapField) {
                "maxbase" -> tmpl.maxbase[idx.toInt()]
                "abl" -> tmpl.abl[idx.toInt()]
                "mark" -> tmpl.mark[idx.toInt()]
                "exp" -> tmpl.exp[idx.toInt()]
                "relation" -> tmpl.relation[idx.toInt()]
                "talent" -> tmpl.talent[idx.toInt()]
                "cflag" -> tmpl.cflag[idx.toInt()]
                "equip" -> tmpl.equip[idx.toInt()]
                else -> tmpl.juel[idx.toInt()]
            } ?: 0L)
        }
    }
    // spec: FunctionMethodCreator.cs:3388-3419 ExistCsvMethod + VariableEvaluator.cs:1575-1582
    // 门控 :3412-3415；getSp 同样只透传给未被读取 sp 形参的模板查找（ConstantData.cs:1230-1238）。
    int("EXISTCSV", 1, 2) { c, a ->
        if (!spCharaEnabled(c.host) && optInt(a, 1, 0) != 0L) throw CodeEE(SP_CHARACTER_FEATURE_DISABLED)
        if (c.store.template(iv(a[0]!!)) != null) 1L else 0L
    }

    // spec: FunctionMethodCreator.cs:3321-3387 FindcharaMethod + VariableEvaluator.FindChara
    refInt("FINDCHARA", 2, 4) { c, e -> findCharaVar(c, e, last = false) }
    refInt("FINDLASTCHARA", 2, 4) { c, e -> findCharaVar(c, e, last = true) }

    // ---- 数组区间统计（引用参数形态）----

    // spec: FunctionMethodCreator.cs:4352-4415 SumArrayMethod + VariableEvaluator.cs:385-437
    // 首参是 RefIntArray（26 = Ref|Array|Int）⇒ 1D/2D/3D 都合法：GetArraySum 的 2D/3D 分支
    // 用 `[p.Index1, l]` / `[p.Index1, p.Index2, m]`，缺省 end 取 GetLastLength()（:4400）。
    refInt("SUMARRAY", 1, 3) { c, e ->
        rangeFold(c, e, "SUMARRAY", 0L, multiDim = true) { acc, v -> acc + v }
    }
    refInt("SUMCARRAY", 1, 3) { c, e -> charaRangeFold(c, e, "SUMCARRAY", 0L) { acc, v -> acc + v } }
    // spec: FunctionMethodCreator.cs:4416-4508 MatchMethod
    refInt("MATCH", 2, 4) { c, e -> matchCount(c, e, "MATCH", chara = false) }
    refInt("CMATCH", 2, 4) { c, e -> matchCount(c, e, "CMATCH", chara = true) }
    // spec: FunctionMethodCreator.cs:4653-4765 MaxArrayMethod + VariableEvaluator.cs:637-671
    refInt("MAXARRAY", 1, 3) { c, e -> rangeFold(c, e, "MAXARRAY", null) { acc, v -> maxOf(acc, v) } }
    refInt("MAXCARRAY", 1, 3) { c, e -> charaRangeFold(c, e, "MAXCARRAY", null) { acc, v -> maxOf(acc, v) } }
    refInt("MINARRAY", 1, 3) { c, e -> rangeFold(c, e, "MINARRAY", null) { acc, v -> minOf(acc, v) } }
    refInt("MINCARRAY", 1, 3) { c, e -> charaRangeFold(c, e, "MINCARRAY", null) { acc, v -> minOf(acc, v) } }
    // spec: FunctionMethodCreator.cs:4906-4983 FindElementMethod —— 5 槽 + OmitStart=2
    // ⇒ 实参 2..5（第 3/4/5 位 = start / end / isExact，均可省略）。
    refInt("FINDELEMENT", 2, 5, omitStart = 2) { c, e -> findElement(c, e, "FINDELEMENT", last = false) }
    refInt("FINDLASTELEMENT", 2, 5, omitStart = 2) { c, e ->
        findElement(c, e, "FINDLASTELEMENT", last = true)
    }
    // spec: FunctionMethodCreator.cs:5011-5079 InRangeArrayMethod
    refInt("INRANGEARRAY", 3, 5) { c, e -> inRangeCount(c, e, "INRANGEARRAY", chara = false) }
    refInt("INRANGECARRAY", 3, 5) { c, e -> inRangeCount(c, e, "INRANGECARRAY", chara = true) }

    // ---- 变量元信息 ----

    // spec: FunctionMethodCreator.cs:3420-3475 VarsizeMethod（名字字符串参数）
    int("VARSIZE", 1, 2) { c, a ->
        val name = sv(a[0]!!)
        val dim = optInt(a, 1, 0).toInt()
        // spec: FunctionMethodCreator.cs:3449-3452 —— Config.VarsizeDimConfig 为真且维度 > 0 时
        // 下标先减一，再取 GetLength（ERD 口径）；出厂默认 false ⇒ 行为与旧实现逐位相同。
        val eff = if (c.store.config.varsizeDimConfig && dim > 0) dim - 1 else dim
        c.store.getLength(name, eff).toLong()
    }
    // spec: FunctionMethodCreator.cs:377-436 GetVarMethod/GetVarsMethod —— 动态表达式求值
    r.register(BuiltinMethod("GETVAR", I, 1, 1, evalRef = { c, e -> getVarValue(c, e, wantStr = false) }))
    r.register(BuiltinMethod("GETVARS", S, 1, 1, evalRef = { c, e -> getVarValue(c, e, wantStr = true) }))

    // ---- 关键字表 ----

    // spec: FunctionMethodCreator.cs:4835-4863 GetnumBMethod（名字字符串参数）
    int("GETNUMB", 2, 2) { c, a ->
        val name = sv(a[0]!!)
        val keyword = sv(a[1]!!)
        // 原版 GetVariableToken 认系统与用户变量（含私有）；这里同样走全量变量表。
        c.store.resolveVarInfo(name)
            ?: throw codeEE("GETNUMB", "第 1 个参数(\"$name\")不是变量名")
        // 原版 TryKeywordToInteger 失败返回 -1，不抛错（ConstantData.cs:849-871）
        c.store.keywordIndexOf(name, null, keyword) ?: -1L
    }
    // spec: FunctionMethodCreator.cs:4792-4834 GetnumMethod（引用参数：变量 + 关键字 [+ 维度]）
    refInt("GETNUM", 2, 3) { c, argExprs ->
        val ref = refVar(argExprs, "GETNUM")
        val keyword = (argExprs.getOrNull(1)?.toValue(c) as? Value.StrV)?.v ?: ""
        // 第 3 参是 ERD 的维度号（1 起，拼成 `变量名@n`，:4819）；省略 = 1 维表。
        val dimSuffix = (argExprs.getOrNull(2)?.toValue(c) as? Value.IntV)?.v
        c.store.keywordIndexOf(ref.name, dimSuffix, keyword) ?: -1L
    }
    // spec: FunctionMethodCreator.cs:8373-8414 ErdNameMethod —— 关键字反查（ERD 表）
    refStr("ERDNAME", 2, 3) { c, argExprs ->
        val ref = refVar(argExprs, "ERDNAME")
        val idx = (argExprs.getOrNull(1)?.toValue(c) as? Value.IntV)?.v ?: return@refStr ""
        val dimSuffix = (argExprs.getOrNull(2)?.toValue(c) as? Value.IntV)?.v
        c.store.keywordNameOf(ref.name, dimSuffix, idx) ?: ""
    }
    // spec: FunctionMethodCreator.cs:4864-4905 GetPalamLVMethod/GetExpLVMethod + VariableEvaluator.cs:241-263
    int("GETPALAMLV", 2, 2) { c, a ->
        lvOf(c, "PALAMLV", iv(a[0]!!), iv(a[1]!!))
    }
    int("GETEXPLV", 2, 2) { c, a ->
        lvOf(c, "EXPLV", iv(a[0]!!), iv(a[1]!!))
    }
    // spec: FunctionMethodCreator.cs:5968-6020 JoinMethod + VariableEvaluator.cs:439-497 GetJoinedStr
    // 首参是 RefAnyArray（2073 = AllowConstRef|Ref|Array|Any）⇒ **整数数组**与 2D/3D 同样合法：
    // GetJoinedStr 对 int 数组取十进制字符串、对 2D/3D 只替换末维（`[p.Index1, index1+i]` /
    // `[p.Index1, p.Index2, index1+j]`，:464-496）；缺省 count = GetLastLength() - start
    // （:5996、VariableTerm.cs:268-280）。
    refStr("STRJOIN", 1, 4) { c, argExprs ->
        val ref = refVar(argExprs, "STRJOIN")
        val delimiter = (argExprs.getOrNull(1)?.toValue(c) as? Value.StrV)?.v ?: ","
        val start = if (argExprs.size >= 3 && argExprs[2] != null) argExprs[2]!!.getIntValue(c) else 0L
        val len = lastDimLength(c, ref.name)
        val count = if (argExprs.size >= 4 && argExprs[3] != null) argExprs[3]!!.getIntValue(c) else len - start
        // spec: :5995-5998 —— count 为负报 ArgIsNegative（第 4 位）
        if (count < 0) throw codeEE("STRJOIN", "第 4 个参数 $count 为负数")
        // 区间校验对应 FixedVariableTerm.IsArrayRangeValid（spec: VariableData.cs:89-100）：
        // 端点允许等于该维长度，故 start==len && count==0 是合法空区间。
        if (start < 0 || start > len || start + count > len) {
            throw codeEE("STRJOIN", "区间 [$start,${start + count}) 越界（变量 ${ref.name} 长度 $len）")
        }
        if (count == 0L) return@refStr ""
        val elem = elemIndices(c, ref)
        val isStr = varIsStr(c, ref.name)
        (start until start + count).joinToString(delimiter) { i ->
            if (isStr) {
                c.store.readStr(ref.name, elem + longArrayOf(i))
            } else {
                c.store.readInt(ref.name, elem + longArrayOf(i)).toString()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 内部辅助
// ---------------------------------------------------------------------------

/** 对应变量数组的元素长度（CSV 访问器的越界口径，spec: VariableEvaluator.cs:1644）。 */
private fun csvArraySize(c: BuiltinContext, varName: String): Long {
    val def = c.store.registry.def(varName)
        ?: throw CodeEE("未定义的变量 $varName")
    return def.dims.first().toLong()
}

/** GETCHARA：在角色列表中按 NO 线性查找（spec: VariableEvaluator.cs:1551-1561）。 */
private fun findCharaByNo(c: BuiltinContext, no: Long): Long {
    for (i in 0 until c.store.characters.count) {
        if (c.store.readInt("NO", longArrayOf(i.toLong())) == no) return i.toLong()
    }
    return -1L
}

/**
 * `GetChara_UseSp` 的复现（spec: VariableEvaluator.cs:1563-1573）：按 NO 线性首命中，
 * 且谓词多一条 `CFlag[0] != 0 == getSp`；找不到返回 -1（不抛错）。
 *
 * `CFlag` 在移植侧就是角色 1D 整数变量 `CFLAG`：声明见 SystemLayout.kt:105、
 * 长度见 VariableSizes.kt:100（`charaInt1D[9] = 1000`）、装载时由模板逐位写回
 * VariableStore.kt:524 ⇒ 这里读的下标形态是「角色位 + flag 号 0」。
 */
private fun findCharaByNoSp(c: BuiltinContext, no: Long, sp: Boolean): Long {
    for (i in 0 until c.store.characters.count) {
        if (c.store.readInt("NO", longArrayOf(i.toLong())) != no) continue
        val isSpChara = c.store.readInt("CFLAG", longArrayOf(i.toLong(), 0L)) != 0L
        if (isSpChara == sp) return i.toLong()
    }
    return -1L
}

/** 引用参数 → VarRef（拒绝非变量引用）。 */
internal fun refVar(argExprs: List<Expr?>, fn: String): VarRef {
    val first = argExprs.firstOrNull() as? VarRef
        ?: throw CodeEE("函数 $fn 的第 1 个参数必须是变量引用")
    return first
}

/**
 * 区间扫描的固定下标：原版 GetArraySum/GetMatch/GetJoinedStr/FindElement 一律用扫描值
 * **替换 VarRef 的最后一个下标**，前面的下标原样保留
 * （spec: VariableEvaluator.cs:385-427 —— 1D `new long[1]{k}`、2D `[p.Index1,l]`、
 *  3D `[p.Index1,p.Index2,m]`；角色 1D `[p.Index1,i]`）。
 * 解析期会为省略下标补 0/TARGET（Parser.buildVariableTerm），补全值不是调用方给的固定下标，
 * 不能拼在扫描值前面（否则 1D 变量会得到 2 个下标）。
 */
private fun elemIndices(c: BuiltinContext, ref: VarRef): LongArray =
    ref.indices.dropLast(1).map { it.getIntValue(c) }.toLongArray()

/**
 * 变量「最后一维」的元素个数 = 原版 `VariableTerm.GetLastLength()`
 * （spec: VariableTerm.cs:268-280）：1D 取整个数组长度、2D 取 `GetLength(1)`、
 * 3D 取 `GetLength(2)`，非数组返回 0。
 *
 * [VarInfo.dimension] 只数元素维、不含角色位置（VarDef 的 dims 口径），故 CFLAG
 * （1 维角色变量）走 `GetLength(0)` = 元素长度，与 `GetLastLength` 的 `IsArray1D` 分支同值。
 */
private fun lastDimLength(c: BuiltinContext, name: String): Long {
    val dim = c.store.resolveVarInfo(name)?.dimension ?: 1
    if (dim <= 0) return 0L
    return c.store.getLength(name, dim - 1).toLong()
}

/**
 * 数组区间折叠（SUM/MAX/MINARRAY）。区间端点校验对应
 * `FixedVariableTerm.IsArrayRangeValid`（spec: VariableData.cs:89-100）：端点落在 [0, 长度]，
 * **等于长度合法**（空区间），越界报 OoRInstructionArg。
 *
 * [multiDim] 区分首参的 ArgType 口径：
 * - SUMARRAY 是 `RefIntArray`（26，任意维）⇒ 长度取末维 `GetLastLength()`，
 *   扫描时替换末维、前几维原样保留（spec: FunctionMethodCreator.cs:4400 +
 *   VariableEvaluator.cs:385-427 的 2D/3D 分支）；
 * - MAX/MINARRAY 是 `RefAny1D`（2090，仅 1 维）⇒ 长度取 `GetLength()`，
 *   多维变量一律报「必须指定维度」（与 GetMaxArray 只认 `new long[1]{i}` 一致）。
 *
 * MAX/MIN 版（initial=null）先读 start 元素——start==len 时由 readInt 抛 OoR，
 * 与原版 GetMaxArray 对空区间的行为一致（spec: VariableEvaluator.cs:637-639）。
 */
private fun rangeFold(
    c: BuiltinContext,
    argExprs: List<Expr?>,
    fn: String,
    initial: Long?,
    multiDim: Boolean = false,
    op: (Long, Long) -> Long,
): Long {
    val ref = refVar(argExprs, fn)
    // 求值顺序照抄原版 SumArrayMethod.GetIntValue：start（:4395）→ end（:4396，缺省取长度）
    // → GetFixedVariableTerm（:4397）。实参表达式可能带副作用，原版按这个次序触碰它们。
    val start = if (argExprs.size >= 2 && argExprs[1] != null) argExprs[1]!!.getIntValue(c) else 0L
    val len = if (multiDim) {
        lastDimLength(c, ref.name)
    } else {
        c.store.getLength(ref.name, -1).toLong()
    }
    val end = if (argExprs.size >= 3 && argExprs[2] != null) argExprs[2]!!.getIntValue(c) else len
    val elem = elemIndices(c, ref)
    // 越界文案走 OoRInstructionArg 模板（spec: VariableData.cs:92-99，见 [arrayRangeOoR]）。
    if (start < 0 || start > len) {
        throw arrayRangeOoR(fn, 2, start, ref.name)
    }
    if (end < 0 || end > len) {
        throw arrayRangeOoR(fn, 3, end, ref.name)
    }
    var acc = initial
    if (acc == null && start == end) {
        // 原版 GetMaxArray 对空区间仍会读 arr[start]（start==len 时抛 OoR）
        return c.store.readInt(ref.name, elem + longArrayOf(start))
    }
    for (i in start until end) {
        val v = c.store.readInt(ref.name, elem + longArrayOf(i))
        acc = if (acc == null) v else op(acc, v)
    }
    return acc ?: 0L
}

/** 角色区间折叠（SUMC/MAXC/MINCARRAY）：区间参数是**角色位置**范围（spec: VariableEvaluator.cs:429-437,658-671）。 */
private fun charaRangeFold(
    c: BuiltinContext,
    argExprs: List<Expr?>,
    fn: String,
    initial: Long?,
    op: (Long, Long) -> Long,
): Long {
    val ref = requireCharacterVar(c, refVar(argExprs, fn), fn)
    val full = ref.indices.map { it.getIntValue(c) }.toLongArray()
    if (full.isEmpty()) throw CodeEE("函数 $fn 的第 1 个参数必须是带下标的角色变量引用")
    val elem = full.drop(1).toLongArray()  // 第 0 维是角色位置，扫描时替换
    val count = c.store.characters.count
    val start = if (argExprs.size >= 2 && argExprs[1] != null) argExprs[1]!!.getIntValue(c) else 0L
    val end = if (argExprs.size >= 3 && argExprs[2] != null) argExprs[2]!!.getIntValue(c) else count.toLong()
    if (start >= count || start < 0 || end > count || end < 0) {
        throw codeEE(fn, "角色区间 [$start,$end) 越界（角色数 $count）")
    }
    var acc = initial
    for (i in start until end) {
        val v = c.store.readInt(ref.name, longArrayOf(i) + elem)
        acc = if (acc == null) v else op(acc, v)
    }
    return acc ?: 0L
}

/**
 * MATCH/CMATCH：统计区间内等于 value 的元素个数。
 *
 * 区间先校验后取数据（spec: FunctionMethodCreator.cs:4476-4489）：
 * - 非 chara（MATCH）：`IsArrayRangeValid(num, num2, "MATCH", 3, 4)` —— 端点落在 [0, 长度]，
 *   等于长度合法（spec: VariableData.cs:92-99）；
 * - chara（CMATCH）：`num >= CHARANUM || num < 0 || num2 > CHARANUM || num2 < 0` → CodeEE。
 */
private fun matchCount(c: BuiltinContext, argExprs: List<Expr?>, fn: String, chara: Boolean): Long {
    val ref = refVar(argExprs, fn)
    if (chara) requireCharacterVar(c, ref, fn)
    val isStr = varIsStr(c, ref.name)
    var count = 0L
    if (!chara) {
        val elem = elemIndices(c, ref)
        val len = c.store.getLength(ref.name, -1).toLong()
        val start = if (argExprs.size >= 3 && argExprs[2] != null) argExprs[2]!!.getIntValue(c) else 0L
        val end = if (argExprs.size >= 4 && argExprs[3] != null) argExprs[3]!!.getIntValue(c) else len
        if (start < 0 || start > len) throw arrayRangeOoR(fn, 3, start, ref.name)
        if (end < 0 || end > len) throw arrayRangeOoR(fn, 4, end, ref.name)
        if (isStr) {
            val target = (argExprs[1]?.toValue(c) as? Value.StrV)?.v ?: ""
            for (i in start until end) {
                if (c.store.readStr(ref.name, elem + longArrayOf(i)) == target) count++
            }
        } else {
            val target = argExprs[1]?.getIntValue(c) ?: 0L
            for (i in start until end) {
                if (c.store.readInt(ref.name, elem + longArrayOf(i)) == target) count++
            }
        }
    } else {
        val full = ref.indices.map { it.getIntValue(c) }.toLongArray()
        val elem = full.drop(1).toLongArray()
        val n = c.store.characters.count
        val start = if (argExprs.size >= 3 && argExprs[2] != null) argExprs[2]!!.getIntValue(c) else 0L
        val end = if (argExprs.size >= 4 && argExprs[3] != null) argExprs[3]!!.getIntValue(c) else n.toLong()
        if (start >= n || start < 0 || end > n || end < 0) {
            throw CodeEE("函数 $fn 的角色范围无效（$start,$end，角色数 $n）")
        }
        if (isStr) {
            val target = (argExprs[1]?.toValue(c) as? Value.StrV)?.v ?: ""
            for (i in start until end) {
                if (c.store.readStr(ref.name, longArrayOf(i) + elem) == target) count++
            }
        } else {
            val target = argExprs[1]?.getIntValue(c) ?: 0L
            for (i in start until end) {
                if (c.store.readInt(ref.name, longArrayOf(i) + elem) == target) count++
            }
        }
    }
    return count
}

/**
 * FINDELEMENT / FINDLASTELEMENT：区间内查找命中的元素下标，找不到 -1。
 *
 * 槽位与省略位（spec: FunctionMethodCreator.cs:4915-4927）：
 * 5 个 `ArgTypes` 槽 + `OmitStart = 2` ⇒ 实参 2..5，第 3 位起可省略
 * （`arguments.Count >= OmitStart` 才通过个数检查，spec: FunctionMethod.cs:183）。
 * 空实参只允许落在**第 3 位及之后**：`j < OmitStart` 的空位报 `ArgCanNotBeNull`
 * （spec: FunctionMethod.cs:195-205）。
 *
 * 槽位语义（spec: :4940-4945）：
 * - 第 3 位 = start，缺省 0；
 * - 第 4 位 = end，缺省 `variableTerm.GetLength()`（1 维 = 数组长度；角色 1 维 = 该角色那一行的
 *   元素长度，spec: CharaVariableToken.cs:30-41）；
 * - 第 5 位 = isExact，缺省 0 —— **不是起始下标**，只在字符串数组分支生效。
 *
 * 首参类型 2089 = `AllowConstRef | RefAny1D`（:4921）⇒ 1 维数组；第二参 `SameAsFirst`
 * （:4922）⇒ 与首参同型。区间端点先过 `FixedVariableTerm.IsArrayRangeValid(..., 3, 4)`
 * （:4947）：该调用**先 `CheckElement(transporter)`**（引用自身下标的逐维校验，即便末位
 * 随后被扫描值替换），再判端点落在 **[0, 长度]** 闭区间（等于长度合法），越界报
 * `OoRInstructionArg`（spec: VariableData.cs:89-100）。空区间 `start >= end` 直接 -1
 * （spec: VariableEvaluator.cs:561-564 / :591-594）——**先于**触碰数组数据。
 *
 * 字符串数组的第二参按**正则**解释（`RegexFactory.GetRegex`，:4956；非法正则报
 * `InvalidRegexArg`，:4958-4961）；`isExact != 0` 的判据是「`Regex.Match` 的**首个**命中
 * 恰好覆盖整串」（`text.Length == match.Length`，spec: VariableEvaluator.cs:601-605、:620-624），
 * 不是锚定整串匹配 —— 见 [findElementWholeMatch]。非精确走 `IsMatch`（任意位置命中）。
 * 整数数组分支不读 isExact（spec: VariableEvaluator.cs:559-587）。
 */
private fun findElement(c: BuiltinContext, argExprs: List<Expr?>, fn: String, last: Boolean): Long {
    // OmitStart = 2：第 1、2 位不可省略（spec: FunctionMethod.cs:195-205）
    for (j in 0 until 2) {
        if (argExprs.getOrNull(j) == null) throw CodeEE("${fn}函数: 第${j + 1}个参数不能省略")
    }
    val ref = refVar(argExprs, fn)
    // 求值顺序照抄原版 GetIntValue：start（:4940）→ end（:4941，缺省取 GetLength()）
    // → isExact（:4942-4945）→ 引用自身的下标（:4946 GetFixedVariableTerm）。
    // 顺序有意义：实参表达式可能带副作用，原版就是按这个次序触碰它们的。
    val start = argExprs.getOrNull(2)?.getIntValue(c) ?: 0L
    val len = c.store.getLength(ref.name, -1).toLong()
    val end = argExprs.getOrNull(3)?.getIntValue(c) ?: len
    val exact = argExprs.getOrNull(4)?.getIntValue(c)?.let { it != 0L } ?: false
    // :4947 的 CheckElement(transporter)：末位下标虽然会被扫描值替换掉，原版仍然求值并
    // 逐维校验（spec: VariableData.cs:89-100、CharaVariableToken.cs:56-70）。这里读一次
    // 「完整下标」处的元素，让存储层把维数/越界/IsForbid 的判定原样带出来 —— 不另写一份检查。
    val own = LongArray(ref.indices.size) { ref.indices[it].getIntValue(c) }
    if (own.isNotEmpty()) {
        if (ref.operandType == OperandType.STR) {
            c.store.readStr(ref.name, own)
        } else {
            c.store.readInt(ref.name, own)
        }
    }
    // 扫描值替换**最后一个下标**（spec: VariableEvaluator.cs:565 / :595 取
    // `GetArray()` / `GetArrayChara(Index1)` 后按下标扫）：1 维数组 → `[i]`（整条数组）；
    // 角色 1 维 → `[charPos, i]`（该角色那一行）。
    val elem = if (own.isEmpty()) own else own.copyOf(own.size - 1)
    if (start < 0 || start > len) throw arrayRangeOoR(fn, 3, start, ref.name)
    if (end < 0 || end > len) throw arrayRangeOoR(fn, 4, end, ref.name)
    if (start >= end) return -1L
    val range = if (last) (end - 1 downTo start) else (start until end)
    if (ref.operandType == OperandType.STR) {
        val pattern = (argExprs[1]?.toValue(c) as? Value.StrV)?.v ?: ""
        val regex = try {
            findElementRegex(pattern)
        } catch (e: Exception) {
            // spec: FunctionMethodCreator.cs:4958-4961 + Lang.Error.InvalidRegexArg
            throw CodeEE("${fn}函数: 第2参数无法解析为正则表达式: ${e.message}")
        }
        for (i in range) {
            val text = c.store.readStr(ref.name, elem + longArrayOf(i))
            val hit = if (exact) findElementWholeMatch(regex, text) else regex.containsMatchIn(text)
            if (hit) return i
        }
        return -1L
    }
    val target = argExprs[1]?.getIntValue(c) ?: 0L
    for (i in range) {
        if (c.store.readInt(ref.name, elem + longArrayOf(i)) == target) return i
    }
    return -1L
}

/**
 * `isExact` 的命中判据（spec: VariableEvaluator.cs:601-608 / :620-627）。
 *
 * 原版是 `Match m = target.Match(text); m.Success && text.Length == m.Length`：
 * `Regex.Match` **不锚定**，返回最左命中，只有这次命中恰好覆盖整串才算命中。
 * 这与「整串锚定匹配」不等价：模式 `a|abc` 对文本 `abc` 的最左命中是 `a`（长度 1），
 * 原版判**不命中**；而锚定匹配会为吃满整串回溯到 `abc` 判成命中。
 * 同理 `.+?` 这类惰性量词：最左命中取最短，原版判不命中。
 *
 * 所以这里取 [Regex.find] 的**首个**命中（与 .NET 同为 leftmost-first），再比长度；
 * 命中起点晚于 0 时其长度必然小于文本长度，故不必另判起点。
 */
private fun findElementWholeMatch(regex: Regex, text: String): Boolean {
    val m = regex.find(text) ?: return false
    return m.value.length == text.length
}

/**
 * `IsArrayRangeValid` 越界文案：`{0}命令的第{1}个参数({2})超出数组"{3}"的范围`
 * （spec: Lang.Error.OoRInstructionArg、VariableData.cs:92-99 —— 模板实参依次是
 * 函数名、1 基参数号、端点值、变量名）。
 */
private fun arrayRangeOoR(fn: String, argNo: Int, value: Long, varName: String): CodeEE =
    CodeEE("${fn}命令的第${argNo}个参数($value)超出数组\"$varName\"的范围")

/**
 * `RegexFactory.GetRegex` 的等价物（`new Regex(pattern, RegexOptions.Compiled)`，
 * spec: RegexFactory.cs:19）。与 PureMethods 里的同名一层（`dotNetRegex`，文件私有）同方言：
 * `(?U)` = UNICODE_CHARACTER_CLASS，对齐 .NET 预定义字符类的 Unicode 口径。
 */
private fun findElementRegex(pattern: String): Regex = dotNetRegex(pattern)

/** INRANGEARRAY / INRANGECARRAY：统计落在 **[low, high)** 区间内的元素个数。
 *  spec: FunctionMethodCreator.cs:5011-5078 InRangeArrayMethod（arity 3..5，OmitStart=3）——
 *  第 4 参 start 缺省 0；第 5 参 end 缺省（非 chara）= 数组长度、（chara）= CHARANUM。
 *  命中判据是 `value >= low && value < high`（**右开**，spec: VariableEvaluator.cs:685 / :699），
 *  不是闭区间 —— 端点 high 本身不计（对照：三参纯函数 INRANGE 是**闭区间**，
 *  spec: FunctionMethodCreator.cs:5002-5006 `intValue < min || intValue > max` → 0）。
 *  非 chara 的区间校验走 IsArrayRangeValid（端点可等于长度，spec: VariableData.cs:92-99）；
 *  start == 长度时循环体不执行、返回 0（spec: VariableEvaluator.cs:682 的 `i < end`）。
 *  chara 变体的范围校验：start >= CHARANUM || start < 0 || end > CHARANUM || end < 0 → CodeEE。 */
private fun inRangeCount(c: BuiltinContext, argExprs: List<Expr?>, fn: String, chara: Boolean): Long {
    val ref = refVar(argExprs, fn)
    if (chara) requireCharacterVar(c, ref, fn)
    val lo = argExprs[1]?.getIntValue(c) ?: 0L
    val hi = argExprs[2]?.getIntValue(c) ?: 0L
    val start = if (argExprs.size >= 4 && argExprs[3] != null) argExprs[3]!!.getIntValue(c) else 0L
    var count = 0L
    if (!chara) {
        val elem = elemIndices(c, ref)
        val len = c.store.getLength(ref.name, -1).toLong()
        val end = if (argExprs.size >= 5 && argExprs[4] != null) argExprs[4]!!.getIntValue(c) else len
        // IsArrayRangeValid(index1, index2, "INRANGEARRAY", 4, 5)：端点落在 [0, 长度]，等于长度合法
        // （spec: FunctionMethodCreator.cs:5068 + VariableData.cs:92-99；start == len ⇒ 0 个元素）
        if (start < 0 || start > len) throw arrayRangeOoR(fn, 4, start, ref.name)
        if (end < 0 || end > len) throw arrayRangeOoR(fn, 5, end, ref.name)
        for (i in start until end) {
            val v = c.store.readInt(ref.name, elem + longArrayOf(i))
            if (v >= lo && v < hi) count++
        }
    } else {
        val full = ref.indices.map { it.getIntValue(c) }.toLongArray()
        val elem = full.drop(1).toLongArray()
        val charaNum = c.store.characters.count.toLong()
        val end = if (argExprs.size >= 5 && argExprs[4] != null) argExprs[4]!!.getIntValue(c) else charaNum
        if (start >= charaNum || start < 0 || end > charaNum || end < 0) {
            throw CodeEE("函数 $fn 的角色范围无效（$start,$end，角色数 $charaNum）")
        }
        for (i in start until end) {
            val v = c.store.readInt(ref.name, longArrayOf(i) + elem)
            if (v >= lo && v < hi) count++
        }
    }
    return count
}

/**
 * 角色族函数的第 1 参数必须是角色变量（原版 `ArgType.CharacterData` 位）。
 *
 * spec: FunctionMethod.cs:210-213 —— 装载期对 `CharacterData` 位要求
 * `arguments[j] is VariableTerm && Identifier.IsCharacterData`，失败报
 * `Lang.Error.ArgIsNotCharacterVar`（Lang.cs:3738；zhs 形
 * `MinorShift.Emuera.Properties.lang.emuera-zhs.xml:888`
 * = `{0}函数: 第{1}个参数变量不是角色变量`）。
 * 声明处：FINDCHARA/FINDLASTCHARA 的 1033（FunctionMethodCreator.cs:3334）、
 * SUMC/MAXC/MINCARRAY 的 3098（:4385）、INRANGECARRAY 的 3114（:5045）。
 *
 * CMATCH 的声明是 `ArgType.Any`（:4451），原版装载期不拦；但它在
 * `VariableEvaluator.GetMatchChara`（:532-557）按角色布局取 `p.Index2/Index3` 并拼
 * 3 个下标，非角色变量在那里越界崩溃 ⇒ 移植侧同样按中止处理（CodeEE）。
 */
private fun requireCharacterVar(c: BuiltinContext, ref: VarRef, fn: String): VarRef {
    val info = c.store.resolveVarInfo(ref.name)
    if (info?.isCharacterData != true) throw CodeEE("${fn}函数: 第1个参数变量不是角色变量")
    return ref
}

/** FINDCHARA：角色变量扫描（spec: VariableEvaluator.FindChara）。 */
private fun findCharaVar(c: BuiltinContext, argExprs: List<Expr?>, fn: String = "FINDCHARA", last: Boolean): Long {
    // spec: FunctionMethodCreator.cs:3334 的 1033 = CharacterData|RefAny ⇒ 必须角色变量
    val ref = requireCharacterVar(c, refVar(argExprs, fn), fn)
    val count = c.store.characters.count
    var start = 0L
    var end = count.toLong()
    if (argExprs.size >= 3 && argExprs[2] != null) start = argExprs[2]!!.getIntValue(c)
    if (argExprs.size >= 4 && argExprs[3] != null) end = argExprs[3]!!.getIntValue(c)
    // 越界文案走 CharacterIndexOutOfRange 模板（spec: FunctionMethodCreator.cs:3372-3380、
    // Lang.cs:3830「{0}関数: 第{1}引数({2})はキャラクタ位置の範囲外です」、
    // lang.emuera-zhs.xml:911「{0}函数: 第{1}个参数({2})超出角色位置范围」）。
    // 判界口径：start 落 [0, count)，end 落 [0, count]（end==count 合法，空区间）。
    if (start < 0 || start >= count) throw CodeEE("${fn}函数: 第3个参数($start)超出角色位置范围")
    if (end < 0 || end > count) throw CodeEE("${fn}函数: 第4个参数($end)超出角色位置范围")
    // FindcharaMethod 取 GetElementInt(1)/(1,2)：元素下标 = VarRef 下标**丢弃角色位**，
    // 扫描值 i 替换角色位（[i] + elem），与区间函数的“替换末维”口径不同
    // （spec: FunctionMethodCreator.cs:3346-3359）。
    val elem = ref.indices.drop(1).map { it.getIntValue(c) }.toLongArray()
    val range = if (last) (end - 1 downTo start) else (start until end)
    if (varIsStr(c, ref.name)) {
        val target = (argExprs[1]?.toValue(c) as? Value.StrV)?.v ?: ""
        for (i in range) {
            if (c.store.readStr(ref.name, longArrayOf(i) + elem) == target) return i
        }
        return -1L
    }
    val target = argExprs[1]?.getIntValue(c) ?: 0L
    for (i in range) {
        if (c.store.readInt(ref.name, longArrayOf(i) + elem) == target) return i
    }
    return -1L
}

private fun varIsStr(c: BuiltinContext, name: String): Boolean =
    c.store.resolveVarInfo(name)?.let { it.operandType == OperandType.STR } ?: false

/** GETVAR/GETVARS：动态解析变量表达式字符串并求值（spec: FunctionMethodCreator.cs:377-436）。 */
private fun getVarValue(c: BuiltinContext, argExprs: List<Expr?>, wantStr: Boolean): Value {
    val fn = if (wantStr) "GETVARS" else "GETVAR"
    val exprStr = (argExprs.firstOrNull()?.toValue(c) as? Value.StrV)?.v
        ?: throw CodeEE("函数 $fn 的第 1 个参数必须是字符串")
    val ast = try {
        // 词法/语法都在 try 内：LexError 不是 CodeEE，漏到执行层就会穿过 TRYC（原版
        // LexicalAnalyzer 抛的正是 CodeEE，见 spec: FunctionMethodCreator.cs:377-436）。
        // 词法配置走 VM 条目表（SystemAllowFullSpace 等；spec: LexicalAnalyzer.cs:748-754 的
        // 静态字段读取点 Config.cs:370 —— 移植侧等价物是 ConfigMethods.lexerConfig）。
        val tokens = ErbLexer(ConfigMethods.lexerConfig(c)).analyse(CharStream(exprStr), LexEndWith.EoL, LexAnalyzeFlag.NONE, 1)
        val parser = ExprParser(tokens.map { it.toExprToken() }, storeResolver(c), c.parserConfig)
        parser.reduceExpressionTerm() ?: throw CodeEE("函数 $fn：无法解释为表达式 \"$exprStr\"")
    } catch (e: LexError) {
        throw CodeEE("函数 $fn：${e.message}")
    } catch (e: ExprEvalException) {
        // 动态解析失败统一按 CodeEE 报告（spec: FunctionMethodCreator.cs:377-436 GetVarMethod）
        throw CodeEE("函数 $fn：${e.message}")
    } catch (e: ExprParseException) {
        throw CodeEE("函数 $fn：${e.message}")
    }
    // 原版限定目标是变量（拒绝 IsCalc 表达式）。TODO(verify): 非变量表达式的报错口径
    val v = ast.toValue(c)
    if (wantStr) {
        if (v !is Value.StrV) throw CodeEE("函数 GETVARS：表达式 \"$exprStr\" 不是字符串变量")
        return v
    }
    if (v !is Value.IntV) throw CodeEE("函数 GETVAR：表达式 \"$exprStr\" 不是整数变量")
    return v
}

/**
 * 关键字 → 编号（原版 `TryKeywordToInteger`，spec: ConstantData.cs:849-872）。
 *
 * 查找序与 C# 一致：用户变量查 ERD 表（`NAME` / `NAME@[dimSuffix]`），系统变量查
 * 内建 29 槽 CSV 表（[SystemLayout.keywords]）。未命中返回 null，调用方按原版
 * 折成 -1 / 空串，**不抛错**。
 */
private fun VariableStore.keywordIndexOf(varName: String, dimSuffix: Long?, keyword: String): Long? {
    if (userVar(varName) != null) {
        return userKeywordTableBySuffix(varName, dimSuffix)?.get(keyword)?.toLong()
    }
    val spec = SystemLayout.keywords[varName.uppercase()] ?: return null
    val table = runCatching { registry.csvTable(spec.table) }.getOrNull() ?: return null
    return table.keywordIndex(keyword)?.toLong()
}

/**
 * 编号 → 关键字（[keywordIndexOf] 的反查，原版 `TryIntegerToKeyword`，
 * spec: ConstantData.cs:873-895）。未命中返回 null，调用方按原版折成空串。
 */
private fun VariableStore.keywordNameOf(varName: String, dimSuffix: Long?, value: Long): String? {
    if (userVar(varName) != null) {
        return userKeywordNameBySuffix(varName, dimSuffix, value)
    }
    val spec = SystemLayout.keywords[varName.uppercase()] ?: return null
    val table = runCatching { registry.csvTable(spec.table) }.getOrNull() ?: return null
    if (value < 0 || value >= table.size) return null
    return table[value.toInt()]
}

/** GETPALAMLV/GETEXPLV（spec: VariableEvaluator.cs:241-263）。 */
private fun lvOf(c: BuiltinContext, tableVar: String, value: Long, maxLv: Long): Long {
    for (i in 0 until maxLv) {
        if (value < c.store.readInt(tableVar, longArrayOf(i + 1))) return i
    }
    return maxLv
}
