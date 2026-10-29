package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.ArgsEndWith
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.fn.ColorNames
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode as ExprOperator
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.TermEndWith
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.CurlyBraceSubWord
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.PercentSubWord
import com.eratw.port.core.lexer.StrForm
import com.eratw.port.core.lexer.SubWord
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.TripleSymbolSubWord
import com.eratw.port.core.lexer.YenAtSubWord
import java.nio.file.Path

/**
 * `#DIM` / `#DIMS` 声明解析结果。
 *
 * 字段语义与 `UserDefinedVariableData` 一一对应（spec: UserDefinedVariableData.cs:11-37）。
 * [dims] 是各维长度；REF 变量为全 0（原版在 return 前未写 Lengths，spec: UserDefinedVariableData.cs:450-453）。
 */
class DimSpec(
    val name: String,
    val isStr: Boolean,
    val dims: IntArray,
    val isConst: Boolean = false,
    val isRef: Boolean = false,
    val isGlobal: Boolean = false,
    val isSavedata: Boolean = false,
    val isCharaData: Boolean = false,
    val isStatic: Boolean = true,
    val isPrivate: Boolean = false,
    val defaultsInt: LongArray? = null,
    val defaultsStr: List<String>? = null,
) {
    val dimension: Int get() = dims.size

    val totalSize: Int
        get() {
            var n = 1
            for (d in dims) n *= d
            return n
        }

    /** 生成用户变量的 VarDef（storage=USER，槽位由 VariableStore 分配）。 */
    fun toVarDef(index: Int, chara: Boolean, code: Int): VarDef = VarDef(
        name = name,
        code = code,
        type = if (isStr) VarType.STR else VarType.INT,
        storage = Storage.USER,
        index = index,
        dims = if (isRef) IntArray(dims.size) else dims.copyOf(),
        isConst = isConst,
        isSavedata = isSavedata,
        isGlobal = isGlobal,
        isCharacterData = chara,
    )

    override fun toString(): String =
        "DimSpec($name, str=$isStr, dims=${dims.toList()}, const=$isConst, ref=$isRef, " +
            "global=$isGlobal, save=$isSavedata, chara=$isCharaData, static=$isStatic, private=$isPrivate)"
}

/** #DIM 解析期配置。 */
data class DimParseConfig(
    /** 非二进制存档时，SAVEDATA 字符串变量受维度限制（spec: UserDefinedVariableData.cs:465-475）。 */
    val systemSaveInBinary: Boolean = false,
    /**
     * `#DEFINE` 宏（名大写 → 展开文本）。来源：ERH 装载（ErhLoader.cs:170/246 是
     * IdentifierDictionary.AddMacro 的全部调用点，本版 ERB 内 #DEFINE 不注册宏）。
     */
    val macros: Map<String, String> = emptyMap(),
    /**
     * 整型常量（名大写 → 值）。来源：ERH `#DIM CONST X = 200` / `#DEFINE X 200` 等已求值常量，
     * 对应原版 ConstantData 静态可折叠表（求值时序见 [DimParser] KDoc）。
     */
    val intConstants: Map<String, Long> = emptyMap(),
    /**
     * 字符串常量（名大写 → 值）。来源：ERH `#DIMS CONST X = "..."` 等已求值标量常量。
     *
     * 初值段每项经 `ReduceArguments` + `Restructure` 折叠为 `SingleTerm`，字符串常量
     * 折成 `SingleStrTerm`（spec: UserDefinedVariableData.cs:405-413）。语料实况
     * `グラフィック特殊効果.ERH:19 #DIMS CONST EFFNAME_SPLIT = ","` → `.ERB:64` 的
     * `#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT`。表由
     * `SystemVariableBootstrap.rememberConstant` 按 `isStr` 分流填充（h-savebinary 已接线，
     * 语料实测 86 项），未注册的字符串常量名仍按原版 InitialValueOnlyConst 方向失败。
     */
    val strConstants: Map<String, String> = emptyMap(),
    /**
     * 装载期可折叠函数方法的回调（默认 null = 保持「函数调用一律失败」的现状口径）。
     *
     * 原版依据：`FunctionMethodTerm.cs:35-56` —— `CanRestructure` 的函数方法在
     * `Restructure` 阶段若实参全为 `SingleTerm` 就折成 `SingleLongTerm`/`SingleStrTerm`
     * （调 `GetIntValue`/`GetStrValue(exm)`）；`GlobalStatic.EMediator` 在 ErhLoader
     * 之前已装配（`Process.cs:304-305`），所以装载期折叠成立。
     * 注入即复刻这条折叠面；**未注入时** [DimParser] 的函数调用仍按原版非 `SingleTerm`
     * 方向失败（`UserDefinedVariableData.cs:325-326` / `:406-408`），不许静默变成功。
     *
     * 纯 JVM 侧的静态实现见 [DimFunctionFolders.pureStatic]（VARSIZE / POWER /
     * COLOR_FROMNAME / UNICODE）；状态（VARSIZE 尺寸表）走参数注入，不落静态可变全局
     * （AGENTS 铁律 4）。
     */
    val functionFolder: DimFunctionFolder? = null,
)

/** 装载期函数折叠的实参形态（已折成 `SingleTerm` 的字面段）。 */
sealed class DimFoldArg {
    /** `SingleLongTerm`（spec: FunctionTerm 类的整型单项）。 */
    data class IntArg(val value: Long) : DimFoldArg()

    /** `SingleStrTerm`（spec: FunctionMethodCreator.cs 里 `arguments[i].GetStrValue` 的入参形）。 */
    data class StrArg(val text: String) : DimFoldArg()

    /**
     * 裸标识符实参 —— 只用于第一参是变量名的可折叠函数（`GETNUM(TALENT, "人类")`，
     * spec: `FunctionMethodCreator.cs:4792-4832` 第一参是 `VariableTerm`，不参与
     * `SingleTerm` 折叠；`UniqueRestructure` 只折第二参）。
     */
    data class IdentArg(val name: String) : DimFoldArg()
}

/**
 * 装载期可折叠函数方法接口（对应原版 `FunctionMethod.CanRestructure`，
 * spec: FunctionMethodTerm.cs:35-56）。
 *
 * [foldInt]/[foldStr] 接收大写化的函数名与已折叠实参；返回 null = 本回调不折叠它，
 * [DimParser] 按现状失败口径报错（原版 `UniqueRestructure` 返回 false 的方向，
 * spec: FunctionMethodCreator.cs:3463-3472）；允许直接抛 [CodeEE]
 * （原版 `GetIntValue` 在折叠期抛错的方向，spec: FunctionMethodCreator.cs:4177-4187）。
 */
interface DimFunctionFolder {
    fun foldInt(name: String, args: List<DimFoldArg>): Long?

    fun foldStr(name: String, args: List<DimFoldArg>): String?
}

/** 折叠回调的构造入口（组长在 boot / erbcc 侧车注入用）。 */
object DimFunctionFolders {

    /**
     * 四个纯静态函数的默认实现；其余名字返回 null（保持失败口径）。
     *
     * @param varSizes `VARSIZE` 的尺寸表（大写变量名 → 各维长度），对应原版
     *   `GlobalStatic.IdentifierDictionary.GetVariableToken(...).GetLength(n)`
     *   （spec: FunctionMethodCreator.cs:3443 / :3453 / :3465）。REF 变量原版
     *   `UniqueRestructure` 直接返回 false（:3466-3469），调用方不要把 REF 放进本表。
     */
    fun pureStatic(
        varSizes: Map<String, LongArray> = emptyMap(),
        /** 内置关键字表（变量名大写 → 关键字表），`GETNUM` 用。 */
        keywordTables: Map<String, Map<String, Int>> = emptyMap(),
        /** `GETLINESTR` 的填充宽度（`LineBufferConsoleSink.clientWidth` 口径；缺省 1800）。 */
        consoleWidth: Int = 1800,
    ): DimFunctionFolder =
        PureStaticDimFunctionFolder(varSizes, keywordTables, consoleWidth)
}

/**
 * [DimFunctionFolders.pureStatic] 的实现。逐条依据：
 * - `VARSIZE`：注册 `FunctionMethodCreator.cs:3437-3438`（CanRestructure + HasUniqueRestructure）；
 *   `UniqueRestructure` :3456-3473（实参 SingleTerm 且标识符在册且非 REF 才折）；
 *   `GetIntValue` :3441-3454（`GetLength(num)`，第二实参是维下标）。
 * - `POWER`：注册 :4169（CanRestructure）；`GetIntValue` :4172-4190
 *   （`Math.Pow` + NaN/Infinity/Int64 越界三抛，(long) 向零截断）。
 * - `COLOR_FROMNAME`：注册 :3814；`GetIntValue` :3817-3835（`Color.FromName`，
 *   命中折 `(R<<16)+(G<<8)+B`，`transparent` 抛 CodeEE :3828-3830，未知名折 -1 :3832；
 *   名单与大小写口径由 [ColorNames] 唯一权威承接）。
 * - `UNICODE`：注册 :5697；`GetStrValue` :5700-5720（0..65535 越界抛 :5703-5705；
 *   控制字符只告警并返回空串 :5707-5717（装载期 `getCurrentLine` 为 null 走
 *   `ParserMediator.Warn` 分支）；否则取低 16 位单 UTF-16 码元 :5719）。
 * - 位移 `<<`/`>>` 不在本类（它们是运算符，走 [DimParser] 的 Folder，
 *   spec: OperatorMethodManager.cs:523-534 / :509-520）。
 */
private class PureStaticDimFunctionFolder(
    private val varSizes: Map<String, LongArray>,
    private val keywordTables: Map<String, Map<String, Int>> = emptyMap(),
    private val consoleWidth: Int = 1800,
) : DimFunctionFolder {

    override fun foldInt(name: String, args: List<DimFoldArg>): Long? = when (name) {
        "VARSIZE" -> varsize(args)
        "POWER" -> power(args)
        "COLOR_FROMNAME" -> colorFromName(args)
        "GETNUM" -> getnum(args)
        "STRLENSU" -> strlenu(args)
        else -> null
    }

    override fun foldStr(name: String, args: List<DimFoldArg>): String? = when (name) {
        "UNICODE" -> unicode(args)
        "GETLINESTR" -> getLineStr(args)
        else -> null
    }

    /**
     * `GETNUM(变量, "关键字"[, pos])`（spec: `FunctionMethodCreator.cs:4814-4832`）：
     * 第二参必须是字符串字面量；第一参是变量名。命中返回下标，未命中返回 **-1**
     * （C# `TryKeywordToInteger` 失败分支，:4821-4825）。查找序同 C#
     * （`ConstantData.cs:849-871`）：**先内置表，未命中再查 `变量名[@pos]` 的 ERD 表**，
     * 两跳都不中才 -1。表确实不存在时返回 null（本层无法确认变量是否存在，保持失败方向
     * 而不是伪造 -1）。
     *
     * CMP-59：修复前在内置表存在但键未命中时**提前 return -1**，第二跳永远走不到；
     * 原版 `:858` 的 `TryGetValue` 失败后并**不 return**，继续走 `:866-870` 的
     * `erdNameToIntDics[varname]`。varname 的三参形态 = `变量名@第三参`（`:4819`）。
     */
    private fun getnum(args: List<DimFoldArg>): Long? {
        if (args.size !in 2..3) return null
        val ident = (args[0] as? DimFoldArg.IdentArg)?.name?.uppercase() ?: return null
        val key = (args[1] as? DimFoldArg.StrArg)?.text ?: return null
        // 第一跳：内置关键字字典（ConstantData.cs:857-861）
        val builtin = keywordTables[ident]
        builtin?.get(key)?.let { return it.toLong() }
        // 第二跳：`变量名[@pos]` 的 ERD 表（:866-870）。两参形态的 varname = 变量名本身，
        // 与第一跳同键（合并表口径下已查过，命中即走上面的 return），故只差 `@pos` 形态。
        val erdKey = if (args.size == 3) {
            val pos = (args[2] as? DimFoldArg.IntArg)?.value ?: return null
            "$ident@$pos"
        } else {
            ident
        }
        val erd = keywordTables[erdKey]
        if (erd != null) return (erd[key] ?: -1).toLong()
        // 表缺席：内置表在册 ⇒ 原版 TryKeywordToInteger 返回 false，GETNUM 折 -1（:4821-4825）；
        // 连内置表都没有 ⇒ 本层无法确认变量存在，保持「不折叠」的失败方向。
        return if (builtin != null) -1L else null
    }

    /** `STRLENSU(s)`（spec: `FunctionMethodCreator.cs:5275-5288`，UTF-16 码元数）。 */
    private fun strlenu(args: List<DimFoldArg>): Long? {
        if (args.size != 1) return null
        val text = (args[0] as? DimFoldArg.StrArg)?.text ?: return null
        return text.length.toLong()
    }

    /**
     * `GETLINESTR(bar)`（spec: `FunctionMethodCreator.cs:5894-5911` + `EmueraConsole.getStBar`
     * `:3420-3436`）：把 `bar` 重复/裁剪到可绘制宽度。空串抛 CodeEE（:5906-5909）。
     *
     * 本移植用 [com.eratw.port.core.host.CellWidth] 的格数口径（与控制台折行同一把尺）。
     * 装载期宽度由调用方注入（[consoleWidth]）；erbcc 侧与运行期审计统一用 PC 基准
     * 可绘制宽 199 格（`CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS`），设备实际宽度不同时
     * 该布局变量按编译期值固定（`.erbc` 侧车的既定取舍）。
     */
    private fun getLineStr(args: List<DimFoldArg>): String? {
        if (args.size != 1) return null
        val text = (args[0] as? DimFoldArg.StrArg)?.text ?: return null
        if (text.isEmpty()) throw CodeEE("関数 GETLINESTR 的第 1 个参数不能为空串")
        if (consoleWidth <= 0) return text
        val sb = StringBuilder(text)
        var cells = com.eratw.port.core.host.CellWidth.of(text)
        if (cells <= 0) return text
        while (cells < consoleWidth) {
            sb.append(text)
            cells += com.eratw.port.core.host.CellWidth.of(text)
        }
        while (cells > consoleWidth && sb.isNotEmpty()) {
            sb.deleteCharAt(sb.length - 1)
            cells = com.eratw.port.core.host.CellWidth.of(sb.toString())
        }
        return sb.toString()
    }

    private fun varsize(args: List<DimFoldArg>): Long? {
        // spec: FunctionMethodCreator.cs:3430-3435 —— 实参签名 (String[, Int])，OmitStart=1。
        if (args.size !in 1..2) return null
        val key = (args[0] as? DimFoldArg.StrArg)?.text?.uppercase() ?: return null
        val dims = varSizes[key] ?: return null // 不在册 / REF ⇒ 不折叠（:3465-3469 返 false 方向）
        val n = if (args.size == 1) 0 else {
            val raw = (args[1] as? DimFoldArg.IntArg)?.value ?: return null
            // spec: :3449-3452 —— Config.VarsizeDimConfig 为真且 num>0 时下标减一后再取
            // GetLength。TODO(verify)：该配置项出厂默认未钉，语料 #DIM 行全部单参形态，
            // 本实现先按「不减一」处理；两参形态由后续波次对拍收口。
            raw.toInt().takeIf { it >= 0 } ?: return null
        }
        // spec: :3453 GetLength(n)；下标越界不折叠（失败方向，交调用方报「不是编译期常量」）。
        return dims.getOrNull(n)
    }

    private fun power(args: List<DimFoldArg>): Long? {
        if (args.size != 2) return null
        val base = (args[0] as? DimFoldArg.IntArg)?.value ?: return null
        val exp = (args[1] as? DimFoldArg.IntArg)?.value ?: return null
        val d = Math.pow(base.toDouble(), exp.toDouble())
        if (d.isNaN()) throw CodeEE("函数 POWER 结果为 NaN（原版 FunctionMethodCreator.cs:4177-4179）")
        if (d.isInfinite()) throw CodeEE("函数 POWER 结果为无穷大（原版 FunctionMethodCreator.cs:4181-4183）")
        if (d >= 9.223372036854776E18 || d <= -9.223372036854776E18) {
            throw CodeEE("函数 POWER 结果 $d 超出 Int64 范围（原版 FunctionMethodCreator.cs:4185-4187）")
        }
        return d.toLong() // (long)num：向零截断，Kotlin Double.toLong 同语义
    }

    private fun colorFromName(args: List<DimFoldArg>): Long? {
        if (args.size != 1) return null
        val name = (args[0] as? DimFoldArg.StrArg)?.text ?: return null
        if (ColorNames.isTransparent(name)) {
            throw CodeEE("函数 COLOR_FROMNAME 不支持 transparent（原版 FunctionMethodCreator.cs:3828-3830）")
        }
        // 命中 = (R<<16)+(G<<8)+B（:3824）；未命中 = -1（:3832）——ColorNames 同口径。
        return ColorNames.rgbOrUnknown(name)
    }

    private fun unicode(args: List<DimFoldArg>): String? {
        if (args.size != 1) return null
        val code = (args[0] as? DimFoldArg.IntArg)?.value ?: return null
        if (code < 0 || code > 65535) {
            throw CodeEE("函数 UNICODE 第 1 个参数 $code 越界（0-65535）（原版 FunctionMethodCreator.cs:5703-5705）")
        }
        // 控制字符：原版只 PrintSystemLine/Warn 告警后返回空串（:5707-5717），装载期不中断。
        if ((code < 31 && code != 10L && code != 13L) || (code in 127..159)) return ""
        // (char)intValue = 单个 UTF-16 码元（:5719），代理区码点同样只取低 16 位（官方形态）。
        return code.toInt().toChar().toString()
    }
}

/**
 * 便捷入口：在既有配置上挂 [DimFunctionFolders.pureStatic] 折叠回调
 * （boot 装置 / erbcc 侧车 / SystemVariableBootstrap 的注入点，组长接线用）。
 * 不挂回调的构造点行为完全不变（保持「函数调用失败」现状口径）。
 */
fun DimParseConfig.withPureStaticDimFolders(
    varSizes: Map<String, LongArray> = emptyMap(),
    keywordTables: Map<String, Map<String, Int>> = emptyMap(),
    consoleWidth: Int = 1800,
): DimParseConfig = copy(functionFolder = DimFunctionFolders.pureStatic(varSizes, keywordTables, consoleWidth))

/**
 * 装载期 `#DIM` 折叠面的**一次快照** —— 三处消费点共用同一个实例：
 * erbcc `CorpusCompile.compile`（写侧车）、boot 装置 `RealCorpusCompile`（装置读数）、
 * `GameBootSmoke` 交给 `SystemVariableBootstrap.install` 的那条 cfg。
 *
 * 为什么必须整面带过去：折叠面不止 VARSIZE 的尺寸表。`GETNUM` 要关键字表、
 * `GETLINESTR` 要条宽；只传 varSizes 而让其余吃默认值，同一行
 * `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))` 会在 erbcc 折成 99、
 * 在装置折成 900（默认 1800 格）—— 侧车与装置读数直接分叉。
 * 所以本类把三件套绑成一个对象，消费点只许经 [apply] 挂配置。
 *
 * 纯 JVM：无静态可变全局，快照由调用点构造后显式注入（AGENTS 铁律 4）。
 */
class DimFoldSnapshot(
    /** VARSIZE 尺寸表（大写变量名 → 各维长度），装载时点见 [DimVarSizes.systemSnapshot]。 */
    val varSizes: Map<String, LongArray>,
    /** GETNUM 的内置关键字表（大写变量名 → 关键字→下标）；缺表时 GETNUM 保持失败方向。 */
    val keywordTables: Map<String, Map<String, Int>> = emptyMap(),
    /** GETLINESTR 的填充宽度（半角格）；PC 基准 = [PC_CONSOLE_WIDTH_CELLS]。 */
    val consoleWidth: Int = PC_CONSOLE_WIDTH_CELLS,
) {
    /** 把本快照的折叠面挂到 [cfg]；三处消费点只走这一个入口。 */
    fun apply(cfg: DimParseConfig): DimParseConfig =
        cfg.withPureStaticDimFolders(varSizes, keywordTables, consoleWidth)

    companion object {
        /**
         * PC 基准可绘制宽（半角格）：1797px / 9px 步进 = 199（`pc-golden-root.txt:689`）。
         * erbcc 的 `CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS` 与本值同源（同一把尺）。
         */
        const val PC_CONSOLE_WIDTH_CELLS = 199

        /**
         * 按装载时点构造：`VariableSize.csv` 先于 ERH（spec: Process.cs:292-293 建
         * ConstantData 并 LoadData → :300-301 建 IdentifierDictionary → :309/:313 装 ERH）。
         *
         * @param csvDir `CSV` 目录；null = 不装载 CSV（出厂 [VariableSizes]）。
         * @param keywordTables 与运行期同一份 CSV 名字表（`VariableRegistry.systemKeywordTables`）。
         */
        fun load(
            csvDir: Path?,
            keywordTables: Map<String, Map<String, Int>> = emptyMap(),
            consoleWidth: Int = PC_CONSOLE_WIDTH_CELLS,
        ): DimFoldSnapshot =
            DimFoldSnapshot(DimVarSizes.systemSnapshot(csvDir), keywordTables, consoleWidth)
    }
}

/**
 * `#DIM` / `#DIMS` 行解析器。
 *
 * ERB 原文形如（关键字顺序任意，冲突即 CodeEE）：
 * ```
 * #DIM       CONST HOGE = 1, 2, 3
 * #DIM       DYNAMIC BUFFER, 10
 * #DIMS      NAMES, 20
 * #DIM       CHARADATA CV, 10, 10
 * #DIMREF    R, 0
 * ```
 * 规则来自 `UserDefinedVariableData.Create`（spec: UserDefinedVariableData.cs:44-477）。
 *
 * 尺寸/初值都按原版交给表达式层折叠，不是「读一个记号」：
 * 尺寸段 `ReduceIntegerTerm(...).Restructure(null)` 要求 `SingleLongTerm`
 * （spec: UserDefinedVariableData.cs:325-326），初值段 `ReduceArguments(...)` 逐项
 * `Restructure` 要求 `SingleTerm`（spec: UserDefinedVariableData.cs:375 / :406-408）。
 * 本实现据此接了编译期常量折叠 [DimParser.Folder]：`+ - * / %`、一元负号、括号、
 * 已注册常量名（[DimParseConfig.intConstants] / [DimParseConfig.strConstants]）与
 * `#DEFINE` 宏（[DimParseConfig.macros]，宏体自身可以是表达式）都参与求值；
 * `@"..."` 插值字面量在所有子式折叠成常量后取字面值
 * （spec: StrForm.cs:118 / StrFormTerm.cs:27-39）。
 * 未落地（M4-M `m-dimfold` 已接，见下「已接」段；仅比较运算符与 GETNUM/STRLENSU/GETLINESTR
 * 保持失败方向）：
 * - ~~位运算符 `<<`/`>>`/`&`/`|`/`^` 未接~~ 已接 `<<`/`>>`（spec: OperatorMethodManager.cs:523-534
 *   注册 :741 / :509-520 注册 :740，折叠形态 `(long)a << (int)b`；原版 `&`/`|` 是**逻辑**与或
 *   （spec: OperatorMethodManager.cs:375-410），#DIM 行无使用需求，未接，比较运算符同）。
 * - ~~装载期可折叠的函数方法未接~~ 已接注入面（[DimParseConfig.functionFolder]；原版
 *   `FunctionMethodTerm.cs:35-56`：`CanRestructure` 且实参全 `SingleTerm` 即折成
 *   `GetValue(exm)`，`GlobalStatic.EMediator` 在 ErhLoader 之前装配（`Process.cs:304-305`），
 *   「装载期 exm 不可用」不成立）；纯静态四个 VARSIZE / POWER / COLOR_FROMNAME / UNICODE
 *   由 [DimFunctionFolders.pureStatic] 实现，GETNUM / STRLENSU / GETLINESTR 需外部数据源，
 *   本路未实现（回调返回 null = 未注入时一致地保持失败口径）。
 * - ~~`reduceStr` 不接字符串 `+` 拼接~~ 已接（spec: OperatorMethodManager.cs:28-33 + :41-44，
 *   语料 `CN_GRAPHIC/Main.ERH:10/:300` 两条）；`@"..."` 内 `%%UNICODE(...)%%` 子式的折叠随
 *   UNICODE 注入自动生效（spec: StrFormTerm.cs:27-29 → StrForm.cs:364-376，语料 `DIM.ERH:708`）。
 * 变量引用作尺寸/初值仍按原版失败（`InitialValueOnlyConst`，`UserDefinedVariableData.cs:406-408`）。
 *
 * 字面量进制（BOOT-2 接线，见 [DimParser.readInt64At]）：尺寸段与初值段的整数字面量
 * 与原版共用 `LexicalAnalyzer.ReadInt64` —— 支持 `0x`/`0X`（16 进制）、`0b`/`0B`
 * （2 进制）、`p`/`P`（乘 2 的 n 次方）、`e`/`E`（乘 10 的 n 次方）四种进位形态，
 * 官方「指数为 0 时跳过缩放」的怪癖原样保留（ADR-005）。
 * 语料实测（本路对全语料 28,866 条 #DIM/#DIMS 行逐 token 扫描）：16 进制面值 208 个
 * （`DIM.ERH:571` 起 C_* 色值、`CASINO/CASINO_SLOT_HELP.ERB:17` 起 色_*）、
 * `p` 后缀 164 个（`DIM.ERH:311-318` 起的位标志）；`0b` 与 `e` 形态在 #DIM 行里 0 个
 * （仍按原版补齐，两形态的 C# 出处见 readInt64At 的逐条行号）。
 *
 * 原版求值时序（尺寸为标识符/表达式的合法依据，spec: UserDefinedVariableData.cs:300-343）：
 * 1. ERH 先于 ERB 装载（ErbLoader 前置 ErhLoader），`#DIM CONST 人物数量上限 = 200`
 *    （如 DIM.ERH:209）先注册进 ConstantData；
 * 2. ERB 装载期 `#DIM 集合, 人物数量上限, 3` 的每段尺寸由
 *    `ExpressionParser.ReduceIntegerTerm(wc, TermEndWith.Comma_Assignment).Restructure(null)`
 *    静态求值（cs:325-341）：`#DEFINE` 宏在词法层展开，ERH `#DIM CONST` / 全局常量
 *    标识符在 Restructure 静态折叠（ConstantData 提供），结果须为 `SingleLongTerm`，
 *    且 1 <= 值 <= 1000000（cs:337-340），否则 CodeEE（HasNotExpressionAfterComma / OoRDefinable）。
 * 3. 因此 erbcc（tools/erbcc）收集的 `DimSpec.dims` 保留**原文**，宏展开 + 常量表查询
 *    交下游编译器；求值失败的尺寸按原版语义保持报错（不回退为任何默认值）。
 */
object DimParser {
    /**
     * `#DIM` 行首可出现的关键字全集（消费点 [keywordOf]，按长度分档零分配判定）。
     * 只作单一真源的清单用途：新增关键字必须同时补 [keywordOf] 的分档。
     */
    internal val KEYWORDS = setOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

    /**
     * @param keyword `#DIM` 或 `#DIMS`
     * @param body    关键字之后的整行文本（已去掉 `#DIM` 前缀）
     * @param isPrivate 是否位于函数体内（原版 DimLineWC.IsPrivate）
     */
    fun parse(
        keyword: String,
        body: String,
        isPrivate: Boolean,
        config: DimParseConfig = DimParseConfig(),
    ): DimSpec {
        val isStr = when (keyword.uppercase()) {
            "#DIM" -> false
            "#DIMS" -> true
            else -> throw CodeEE("未知的 #DIM 关键字 $keyword")
        }
        // 一条声明**至多**建一只词法器（mem6，[LazyLexer] 慢路才真造）：`readInt64` 只依赖入参游标，但 `ErbLexer`
        // 每次构造都要建一张 30 字符的终止符 `LinkedHashSet`（约 1.5 KiB），旧写法按
        // **每个数字面量**新建一只 ⇒ 一条 `#DIM CONST X = 1,2,3` 白付 4 次。
        // 实例随本条声明一起作废，`warnings` 因此不会跨声明串味（AGENTS 铁律 4：不留静态可变全局）。
        val lexer = LazyLexer()
        val tokens = lex(body, lexer)
        var i = 0

        var const = false
        var ref = false
        var global = false
        var save = false
        var chara = false
        var static = true
        var scopeSeen = false // DYNAMIC/STATIC 是否已显式出现

        while (i < tokens.size) {
            val t = tokens[i]
            // 判据与旧写法（`t.text.uppercase() !in KEYWORDS`）逐字等价，见 [keywordOf]。
            val kw = if (t is Tok.Word) keywordOf(t.text) else null
            if (kw == null) break
            i++
            when (kw) {
                "CONST" -> {
                    if (chara) throw conflict(kw, "CHARADATA")
                    if (global) throw conflict(kw, "GLOBAL")
                    if (save) throw conflict(kw, "SAVEDATA")
                    if (ref) throw conflict(kw, "REF")
                    if (!static) throw conflict(kw, "DYNAMIC")
                    if (const) throw CodeEE("关键字重复：CONST")
                    const = true
                }
                "REF" -> {
                    if (scopeSeen && static) throw conflict(kw, "STATIC")
                    if (chara) throw conflict(kw, "CHARADATA")
                    if (global) throw conflict(kw, "GLOBAL")
                    if (save) throw conflict(kw, "SAVEDATA")
                    if (const) throw conflict(kw, "CONST")
                    if (ref) throw CodeEE("关键字重复：REF")
                    ref = true
                    static = false
                }
                "DYNAMIC" -> {
                    if (!isPrivate) throw CodeEE("DYNAMIC 只能用于函数内的变量声明")
                    if (chara) throw conflict(kw, "CHARADATA")
                    if (const) throw conflict(kw, "CONST")
                    if (scopeSeen) {
                        if (static) throw CodeEE("STATIC 与 DYNAMIC 不能同时指定")
                        throw CodeEE("关键字重复：DYNAMIC")
                    }
                    scopeSeen = true
                    static = false
                }
                "STATIC" -> {
                    if (!isPrivate) throw CodeEE("STATIC 只能用于函数内的变量声明")
                    if (chara) throw conflict(kw, "CHARADATA")
                    if (scopeSeen) {
                        if (!static) throw CodeEE("STATIC 与 DYNAMIC 不能同时指定")
                        throw CodeEE("关键字重复：STATIC")
                    }
                    if (ref) throw conflict(kw, "REF")
                    scopeSeen = true
                    static = true
                }
                "GLOBAL" -> {
                    if (isPrivate) throw CodeEE("GLOBAL 不能用于函数内的变量声明")
                    if (chara) throw conflict(kw, "CHARADATA")
                    if (ref) throw conflict(kw, "REF")
                    if (const) throw conflict(kw, "CONST")
                    if (scopeSeen) throw CodeEE("GLOBAL 不能与 STATIC/DYNAMIC 同时指定")
                    global = true
                }
                "SAVEDATA" -> {
                    if (isPrivate) throw CodeEE("SAVEDATA 不能用于函数内的变量声明")
                    if (scopeSeen) throw CodeEE("SAVEDATA 不能与 STATIC/DYNAMIC 同时指定")
                    if (ref) throw conflict(kw, "REF")
                    if (const) throw conflict(kw, "CONST")
                    if (save) throw CodeEE("关键字重复：SAVEDATA")
                    save = true
                }
                "CHARADATA" -> {
                    if (isPrivate) throw CodeEE("CHARADATA 不能用于函数内的变量声明")
                    if (ref) throw conflict(kw, "REF")
                    if (const) throw conflict(kw, "CONST")
                    if (scopeSeen) throw CodeEE("CHARADATA 不能与 STATIC/DYNAMIC 同时指定")
                    if (global) throw conflict(kw, "GLOBAL")
                    if (chara) throw CodeEE("关键字重复：CHARADATA")
                    chara = true
                }
            }
        }

        val nameTok = tokens.getOrNull(i) as? Tok.Word ?: throw CodeEE("$keyword 后面缺少变量名")
        val name = nameTok.text
        i++
        // 原版关键字与变量名之间必须有空白（WordCollection 的 IdentifierWord 序列）；
        // 这里 token 化时天然要求分隔，`CONSTX` 会被当成名字。

        val dims = IntVec()
        var hasEq = false
        if (tokens.getOrNull(i) is Tok.Sym && (tokens[i] as Tok.Sym).text == ",") {
            // spec: UserDefinedVariableData.cs:300-343 —— 每轮循环先消费一个逗号：
            // - 非 REF：逗号后必须是尺寸表达式，`X, 3,` 的尾随逗号抛
            //   HasNotExpressionAfterComma（:321-324）；
            // - REF：每个逗号记一维 0（:309-320），尺寸表达式只能是 0（:329-335），
            //   且末维可以为空 ⇒ `#DIM REF X,,` 是 2 维（语料 集合.ERB:121
            //   `#DIM REF 集合,,`、Add_Misc.ERB:1324 `#DIM REF MOVEMENT_INFO,,`）。
            while (i < tokens.size) {
                val t = tokens[i]
                if (t is Tok.Sym && t.text == "=") break
                if (!(t is Tok.Sym && t.text == ",")) {
                    throw CodeEE("$keyword $name 的尺寸列表格式错误")
                }
                i++
                if (ref) {
                    dims.add(0)
                    if (i >= tokens.size) break
                    if (tokens[i] is Tok.Sym && (tokens[i] as Tok.Sym).text == ",") continue
                }
                if (i >= tokens.size) {
                    throw CodeEE("$keyword $name 在第 ${dims.size + 1} 个逗号后缺少尺寸表达式")
                }
                // spec: UserDefinedVariableData.cs:325-326 —— 尺寸段由
                // `ExpressionParser.ReduceIntegerTerm(wc, TermEndWith.Comma_Assignment)
                //  .Restructure(null)` 折叠，且结果必须是 SingleLongTerm；
                // 因此 `#DEFINE` 宏、ERH `#DIM CONST` 常量、`+ - * / %`、一元负号、括号
                // 都可以参与（语料实况 CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:25
                // `#DIM CHRLIST , 人物数量上限 + 10 ;いちおう大きめに取っておく`、
                // DIM.ERH:215 `#DIM ソート項目, (簡易能力表示最大页数的番号 + 1) * 10`），
                // 函数调用 / 未定义标识符仍失败（HasNotExpressionAfterComma）。
                val folder = Folder(tokens, i, config, keyword, name, lexer)
                val v = folder.reduceInt()
                i = folder.i
                if (ref) {
                    // spec: :329-335 —— REF 的尺寸只能写 0（本实现已按逗号记过这一维）
                    if (v != 0L) throw CodeEE("REF 变量的尺寸只能写 0")
                } else {
                    // spec: UserDefinedVariableData.cs:337-340 OoRDefinable（1..1000000）
                    if (v <= 0 || v > 1_000_000) {
                        throw CodeEE("$keyword $name 的尺寸 $v 越界（1..1000000）")
                    }
                    dims.add(v.toInt())
                }
            }
        }
        if (tokens.getOrNull(i) is Tok.Sym && (tokens[i] as Tok.Sym).text == "=") {
            hasEq = true
            i++
        }

        var defaultsInt: LongArray? = null
        var defaultsStr: List<String>? = null
        if (hasEq) {
            if (ref) throw CodeEE("REF 变量不能设置初值")
            if (dims.size >= 2) throw CodeEE("多维变量不能设置初值")
            if (chara) throw CodeEE("CHARADATA 变量不能设置初值")
            val ints = LongVec()
            val strs = ArrayList<String>()
            // spec: UserDefinedVariableData.cs:375 `ReduceArguments(wc, ArgsEndWith.EoL, false)`
            // 先把 `=` 后的整段按**顶层逗号**切成若干 AExpression，再逐项
            // `Restructure` 并要求 `SingleTerm`（:405-408 InitialValueOnlyConst）、
            // 类型与变量一致（:410-411 NotMatchVarTypeAndInitialValue）、
            // 取 `SingleStrTerm.Str` / `SingleLongTerm.Int`（:413-417）。
            while (i < tokens.size) {
                val folder = Folder(tokens, i, config, keyword, name, lexer)
                val v: Any = if (isStr) folder.reduceStr() else folder.reduceInt()
                i = folder.i
                if (isStr) strs.add(v as String) else ints.add(v as Long)
                if (tokens.getOrNull(i) is Tok.Sym && (tokens[i] as Tok.Sym).text == ",") {
                    i++
                    // 拖尾逗号（语料实况 `#DIM CONST MUSHI_MAXSELECT = 3,   ;戦闘に出す数`，
                    // コマンド関連/COMF/日常系/MUSHI_ERH.ERH:2）：**保持既有宽松口径**，
                    // 剥注释后行尾的 `,` 不再要求跟一个初值。
                    // TODO(verify): 反编译 `ReduceArguments`（spec: ExpressionParser.cs:292-304）
                    // 在 `,` 后递归 `local()` 遇到 `\0` 直接 return，随后
                    // `terms.addLast(terms2.LastValue)`（:302）——链路里疑似插了个 null 项，
                    // 落到 `UserDefinedVariableData.cs:396-399` 就是 ArrayVarCanOmitInitialValue
                    // 方向的报错。反编译里 OperatorTerm/LinkedList 语义不完整，
                    // 无法在没有证据的情况下翻转这条口径（翻转会让 5 条语料声明新失败），
                    // 故此处按「不新增失败」处理，交由后续用真 Emuera 对拍收口。
                    if (i >= tokens.size) break
                } else {
                    break
                }
            }
            if ((isStr && strs.isEmpty()) || (!isStr && ints.size == 0)) {
                throw CodeEE("$keyword $name 的初值不能为空")
            }
            if (dims.size == 1) {
                val n = dims[0]
                val cnt = if (isStr) strs.size else ints.size
                if (cnt > n) throw CodeEE("初值个数 $cnt 超过数组长度 $n")
                if (const && cnt != n) throw CodeEE("CONST 变量的初值个数必须等于数组长度")
            }
            if (isStr) defaultsStr = strs else defaultsInt = ints.toLongArray()
            if (dims.isEmpty()) {
                dims.add(if (isStr) strs.size else ints.size)
            }
        } else {
            if (const) throw CodeEE("CONST 变量必须设置初值")
        }

        if (i < tokens.size) throw CodeEE("$keyword $name 声明末尾有多余内容")

        if (dims.isEmpty()) dims.add(1)
        if (const && dims.size > 1) throw CodeEE("CONST 变量不能是多维数组")
        if (chara && dims.size > 2) throw CodeEE("CHARADATA 变量最多 2 维")
        if (dims.size > 3) throw CodeEE("变量最多 3 维")

        if (ref) {
            return DimSpec(
                name = name, isStr = isStr, dims = IntArray(dims.size),
                isConst = false, isRef = true, isGlobal = false, isSavedata = false,
                isCharaData = false, isStatic = false, isPrivate = isPrivate,
            )
        }

        var total = 1L
        for (k in 0 until dims.size) total *= dims[k]
        if (total <= 0 || total > 1_000_000) throw CodeEE("变量 $name 的总元素数 $total 越界（1..1000000）")

        if (!isPrivate && save && !config.systemSaveInBinary) {
            if (isStr && dims.size > 1) throw CodeEE("非二进制存档下，SAVEDATA 字符串变量不能是多维")
            if (chara) throw CodeEE("非二进制存档下，SAVEDATA 不能是 CHARADATA 变量")
        }

        return DimSpec(
            name = name, isStr = isStr, dims = dims.toIntArray(),
            isConst = const, isRef = ref, isGlobal = global, isSavedata = save,
            isCharaData = chara, isStatic = static, isPrivate = isPrivate,
            defaultsInt = defaultsInt, defaultsStr = defaultsStr,
        )
    }

    private fun conflict(kw: String, other: String): CodeEE = CodeEE("$kw 与 $other 不能同时指定")

    /**
     * **单条尺寸表达式的唯一折叠入口**（CMP-45 单源）。
     *
     * 布局桥 `DimSizeEvaluator`（`core/compile/LocalLayout.kt`）与侧车/运行期的
     * `DimParser.parse` 共用这一条实现：同一套 `lex` 记号流 + 同一个 [Folder]（⇒ 同一张
     * 宏/常量表、同一个 [DimParseConfig.functionFolder] 折叠回调、同一套运算符优先级）。
     * 因此 `VARSIZE("RELATION")`、`POWER(2, 32)` 一类在 `#DIM` 行里能折的形态，在布局面
     * 也能折出**同一个数**，不会再出现「侧车/运行期有 200、布局面没有」的三方分叉。
     *
     * 口径与尺寸段完全一致（`UserDefinedVariableData.cs:325-328`）：
     * - 结果必须是**单值**（`SingleLongTerm`）：`1, 2`、`1 +` 这类带尾巴的文本返回 null；
     * - 求值失败（未知标识符 / 函数回调缺席 / 回调抛 CodeEE / 字面量溢出）一律返回 null，
     *   对应原版「该行无效、变量不注册」的方向（D.8）——**本函数不抛**；
     * - 值域（1..1000000 / REF 只能 0）**不在这里判**：`DimSizeEvaluator` 的既有契约
     *   只判可解性（`CompileAudit2Test` 钉着 `0`/`-5`/`2000000` 都能求值），值域由
     *   声明层（[parse] 与 erbcc 桥的 `dimDropReason`）按同一组行号判。
     *
     * @return 可解时返回该整数；任何不可解形态返回 null。
     */
    fun evalDimExpr(text: String, config: DimParseConfig = DimParseConfig()): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val lexer = LazyLexer()
        return try {
            val toks = lex(t, lexer)
            if (toks.isEmpty()) return null
            val f = Folder(toks, 0, config, "#DIM", "<尺寸表达式>", lexer)
            val v = f.reduceInt()
            // reduceInt 的段终止还认 `,`/`=`（原版 TermEndWith.Comma_Assignment）；
            // 单条尺寸表达式必须整段吃干净，否则就是非 SingleLongTerm。
            if (f.i == toks.size) v else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 尺寸/初值标识符求值：intConstants 直接查表；macros 展开后**可以是表达式**
     * （`#DEFINE SZ 人物数量上限 + 10`），故宏体交回 [Folder] 折叠。
     *
     * 求值时序（spec: UserDefinedVariableData.cs:325-341 + ConstantData.cs）：
     * 1. ERH 先于 ERB 全量装载（ErbLoader 前置 ErhLoader），ERH `#DIM CONST 人物数量上限 = 200`
     *    / `#DEFINE X 200` 在装载 ERB 前就已进入 ConstantData / IdentifierDictionary；
     * 2. ERB 装载期 #DIM 尺寸段才由 `ExpressionParser.ReduceIntegerTerm(...).Restructure(null)`
     *    静态求值（宏在词法层展开、常量标识符在 Restructure 折叠），要求结果为 SingleLongTerm。
     *
     * 宏体的数字面值走 [tryToInt64]（与原版 `ConstantData.tryToInt64` 同一条路径，
     * spec: ConstantData.cs:1523-1565：先剥正负号 spec: ConstantData.cs:1530-1541，
     * 再调 `LexicalAnalyzer.ReadInt64` spec: ConstantData.cs:1557-1558，
     * 任何异常一律「求值失败」不抛 spec: ConstantData.cs:1560-1563）。
     *
     * 展开链上限 [MACRO_HOPS]=8（原版 `IdentifierDictionary` 有同名保护）；
     * 返回 null = 求值失败，由调用方决定错误口径。
     */
    private fun resolveDimConstant(
        word: String,
        config: DimParseConfig,
        lexer: LazyLexer,
        nesting: Int = 0,
    ): Long? {
        val key = word.uppercase()
        config.intConstants[key]?.let { return it }
        var text = config.macros[key] ?: return null
        var hops = 0
        while (hops < MACRO_HOPS) {
            val t = text.trim()
            config.intConstants[t.uppercase()]?.let { return it }
            // 宏体含运算符 → 表达式形态，交回 Folder 折叠（`#DEFINE SZ 上限 + 10`）。
            // 判据先于面值：tryToInt64 与原版一样**不校验剩余字符**
            //（spec: ConstantData.cs:1557-1558 调用后直接 return true），
            // 若先走它，`1 + 1` 会被读成 1。
            if (isExpressionForm(t)) {
                if (nesting >= 4) return null
                val toks = try { lex(t, lexer) } catch (e: Exception) { return null }
                return try {
                    Folder(toks, 0, config, "#DIM", word, lexer, nesting + 1).reduceInt()
                } catch (e: CodeEE) {
                    null
                }
            }
            tryToInt64(t, lexer)?.let { return it }
            val m = config.macros[t.uppercase()] ?: return null
            text = m
            hops++
        }
        return null
    }

    /** 除首位符号外还含 `+ * / % ( )` 的文本按表达式处理（`-` 在 `1e-5` 之类面值里不出现）。 */
    private fun isExpressionForm(t: String): Boolean =
        t.startsWith("(") || t.any { c -> c in "+*/%()" } ||
            (t.length > 1 && t.substring(1).contains('-'))

    private const val MACRO_HOPS = 8

    // ------------------------------------------------------------------
    // 整数字面量（0x / 0b / p / e）
    // ------------------------------------------------------------------

    /**
     * 字面量读取的**唯一口径** = [ErbLexer.readInt64]（spec: LexicalAnalyzer.cs:30-90，
     * 进制与后缀的完整规则另见同文件 readDigits spec: LexicalAnalyzer.cs:92-165）。
     *
     * 原版 `#DIM` 的尺寸段与初值段都不是「自己解析数字」，而是把整行交给词法器：
     * - 数字起头（`0`..`9`，spec: LexicalAnalyzer.cs:755-764）一律调
     *   `ReadInt64(st, retZero: false)` 生成 `LiteralIntegerWord`（spec: LexicalAnalyzer.cs:765）；
     * - 尺寸段随后由 `ExpressionParser.ReduceIntegerTerm(...).Restructure(null)` 折叠，
     *   要求 `SingleLongTerm`（spec: UserDefinedVariableData.cs:325）；
     * - 初值段由 `ExpressionParser.ReduceArguments(wc, ArgsEndWith.EoL, false)` 逐项
     *   `Restructure` 成 `SingleTerm`，非常量报 InitialValueOnlyConst
     *   （spec: UserDefinedVariableData.cs:375 / :408 / :420）。
     *
     * 因此本层要支持的四种进位形态逐条来自 ReadInt64：
     * 1. `0x` / `0X` → 16 进制（spec: LexicalAnalyzer.cs:36-45，数字集 :112-123）；
     * 2. `0b` / `0B` → 2 进制，出现 2..9 直接 CodeEE CanNotUseBinaryNotate
     *    （spec: LexicalAnalyzer.cs:46-51 / :124-139，抛点 :133-136）；
     * 3. `p` / `P` → 乘 2 的 n 次方（spec: LexicalAnalyzer.cs:66-69）；
     * 4. `e` / `E` → 乘 10 的 n 次方（spec: LexicalAnalyzer.cs:70-73）。
     *
     * 两条**官方怪癖原样保留**（ADR-005，不许顺手修）：
     * - 指数部分用**与尾数同一种进制**读（spec: LexicalAnalyzer.cs:77 `readDigits(st, num3)`），
     *   所以 `1p10` 是 1 × 2^10 = 1024（尾数十进制 → 指数按十进制读），
     *   而 `0x10p10` 是 16 × 2^16 = 1048576（尾数十六进制 → 指数里的 `10` 读作 16）；
     * - 指数为 0 时**跳过整个缩放**（spec: LexicalAnalyzer.cs:80 `if (num != 0 && num2 != 0)`），
     *   `1p0` = 1；缩放后越界即 CodeEE OoRInt64（spec: LexicalAnalyzer.cs:83-86）。
     *
     * 词法错误转成本层的 [CodeEE]：原版这两层本来就是同一种异常
     * （spec: LexicalAnalyzer.cs:141-164 把 FormatException/OverflowException 都转 CodeEE），
     * 而 `SystemVariableBootstrap.install` 只兜 CodeEE（不动点重试环的判据），
     * 让 LexError 逃出去会中断整个装载。
     *
     * 词法器**由调用方按声明传入一只**（mem6 2026-10-28）：`readInt64` 只依赖入参游标、
     * 不碰实例状态，但 `ErbLexer` 每次构造都要建一张 30 字符的终止符集（约 1.5 KiB），
     * 旧写法「每个数字面量一只」在装载期白付了 7,201×2 次。`warnings` 列表随声明一起作废，
     * 既不跨声明串味，也不是静态可变全局（AGENTS 铁律 4）。
     *
     * 游标定位走 `CharStream(s) + jump(from)`，不再 `s.substring(from)` 复制行尾：
     * 报错文本只截「数字本身」（`readDigits` 的 `st.substring(startPos, 消费长度)`，
     * spec: LexicalAnalyzer.cs:92-165），两种形态下截出来逐字相同；`LexError.column`
     * 会因此从「相对行尾」变成「相对整行」，而本层的包装只取 `e.message`。
     */
    private fun readInt64At(s: String, from: Int, lexer: LazyLexer): Pair<Long, Int> {
        val st = CharStream(s)
        st.jump(from)
        val value = try {
            lexer.get().readInt64(st, retZero = false, lineNo = -1)
        } catch (e: LexError) {
            throw CodeEE("整数字面量非法：${e.message}")
        }
        return value to st.currentPosition
    }

    /**
     * 字符串 → 整数（宏体面值）。逐条对齐 `ConstantData.tryToInt64`
     * （spec: ConstantData.cs:1523-1565）：空串 false（:1525-1529）、剥一个正负号
     * （:1532-1541）、剥完后首字符必须是 ASCII 数字否则 false（:1542-1554，
     * `default: return false`）、其余交给 ReadInt64（:1557-1558）、
     * 任何异常都当 false（:1560-1563）。**不校验剩余字符**——原版同样不管（:1557
     * 调用后直接 `return true`），故 `1p1x` 在原版也取 2。
     */
    private fun tryToInt64(text: String, lexer: LazyLexer): Long? {
        if (text.isEmpty()) return null
        var sign = 1L
        var from = 0
        when (text[0]) {
            '+' -> from = 1
            '-' -> {
                sign = -1L
                from = 1
            }
        }
        if (from >= text.length) return null
        if (text[from] !in '0'..'9') return null
        return try {
            sign * readInt64At(text, from, lexer).first
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private sealed interface Tok {
        data class Word(val text: String) : Tok
        data class Number(val value: Long) : Tok
        data class Str(val text: String) : Tok
        data class Sym(val text: String) : Tok

        /**
         * `@"..."` 逐字格式串，`lits.size == inners.size + 1`（与原版 `StrFormWord` 的
         * `strs`/`subwords` 同构，spec: StrFormWord.cs:5-10）。
         * 生成点 spec: LexicalAnalyzer.cs:886-900（`@` 紧跟 `"` 时调
         * `AnalyseFormattedString(st, FormStrEndWith.DoubleQuotation, trim: false)`），
         * 体内规则 spec: LexicalAnalyzer.cs:1073-1230。
         * [inners] 每项是 `{}` 或 `%%` 包住的子表达式**原文**，交 [Folder] 折叠。
         */
        data class Format(val lits: List<String>, val inners: List<String>) : Tok
    }

    /**
     * 独立成记号的运算符字符。原版把运算符逐字符交给词法器
     * （spec: LexicalAnalyzer.cs:767-791 运算符分支 + ReadOperator spec: LexicalAnalyzer.cs:404-460），
     * 尺寸/初值段再按运算符优先级折叠，故 `A + 1`、`(A + 1) * 10` 是合法尺寸写法。
     */
    private const val SYMS = "+-*/%(),=<>"

    private fun isWordBreak(c: Char): Boolean = c.isWhitespace() || c in SYMS || c == '"'

    /**
     * 一条声明共用的词法器持有者（mem6 2026-10-28）。
     *
     * 为什么必须**惰性**：`ErbLexer()` 一次构造 = **2,456 B**（实测标定见
     * `perf/LoadChurnProbeTest` 的「诊断：新建 ErbLexer × N」；`LexerConfig` 只 24 B，
     * 大头是那条 30 字符的 `identifierTerminators` 集）。语料 7,201 条全局 `#DIM` 的
     * `DimParser.parse` 单价是 3,542 B，其中一多半就是这一只词法器。
     * 而装载期面值绝大多数是**纯十进制**（0x 208 个、p 后缀 164 个，其余全是十进制），
     * 走 [fastDecimalAt] 快路后压根不需要词法器 ⇒ 一条声明只分配一只**都省掉**。
     */
    private class LazyLexer {
        private var lexer: ErbLexer? = null

        fun get(): ErbLexer = lexer ?: ErbLexer().also { lexer = it }
    }

    /**
     * 纯十进制面值快路（[lex] 的数字分支入口，`s[from]` 必是 ASCII 数字）。
     *
     * 命中时 `ErbLexer.readInt64` 的产物**逐字等于**本函数（可证）：
     * - 前缀分支要求 `s[from]=='0' && s[from+1] in x/X/b/B`（spec: LexicalAnalyzer.cs:36-51 /
     *   本仓 `LexicalAnalyzer.kt:89-94`）⇒ 尾随分隔符判据把它排除（`x`/`b` 不在分隔集里）；
     * - `retZero=false` ⇒ :95-98 的零值分支不参与；
     * - `readDigits(10)`（:128-165）消费「可选符号 + 连续 ASCII 数字」，本入口从数字起头、
     *   扫描界与它逐字符同（`isDigit` 认 Unicode 数字，本快路只认 `0..9`，故尾随非 ASCII
     *   数字字符时返回 null 交回词法器）；
     * - 校验段 `s.any { it != '+' && it != '-' && it !in '0'..'9' }`（:151）对纯数字恒假
     *   ⇒ 不抛 CanNotConvertToInt；
     * - 长度 ≤ 18 位十进制必 ≤ 999,999,999,999,999,999 < `Long.MAX_VALUE` ⇒ 不抛 OoRInt64；
     * - 缩放段认 `p/P/e/E` 后缀（:100-108）⇒ 尾随判据把它排除，`expBase` 恒 0、
     *   :111 的 `if (expBase != 0 && exp != 0)` 整段不执行（官方「指数为 0 跳过缩放」的
     *   怪癖 ADR-005 在无后缀时本就是「原值」，与本快路同值）。
     *
     * 任何一点不确定（尾随字符不是分隔符、位数 > 18、含 Unicode 数字）一律返回 null，
     * 交回 [readInt64At] 那条唯一真源 —— **失败方向与文案都不变**，只是不快进。
     *
     * @return 命中时 (值, 游标下一位)；不命中 null。
     */
    private fun fastDecimalAt(s: String, from: Int): Pair<Long, Int>? {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        val len = i - from
        if (len == 0 || len > 18) return null
        if (i < s.length && !isNumDelimiter(s[i])) return null
        var v = 0L
        for (k in from until i) v = v * 10 + (s[k] - '0')
        return v to i
    }

    /**
     * 数字面值后面**允许**出现的分隔符：空白、[SYMS] 里的运算符、字符串定界符与
     * 行内注释起手段 `;`（[lex] 自己把 `;` 当行尾）。其余字符（`x`/`b`/`p`/`e`/全角数字…）
     * 说明这条面值不是「纯十进制 + 分隔符」形态，退回词法器。
     */
    private fun isNumDelimiter(c: Char): Boolean =
        c.isWhitespace() || c in SYMS || c == '"' || c == '\'' || c == ';'

    /**
     * 宏体面值的快路（[tryToInt64]）：整串就是 ≤ 18 位 ASCII 数字时，
     * `ConstantData.tryToInt64` 那条「剥符号 → ReadInt64 → 不校验剩余字符」的链路
     * （spec: ConstantData.cs:1523-1565）与 [fastDecimalAt] 同值；形态不符则 null 交回原路。
     */
    private fun fastDecimalWhole(s: String): Long? {
        if (s.isEmpty() || s.length > 18) return null
        var v = 0L
        for (c in s) {
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return v
    }

    /**
     * 单个记号 → **复用同一实例**的 `Tok.Sym`（mem6 2026-10-28）。
     *
     * 旧写法是 `Tok.Sym(c.toString())`：每个运算符都新建一个单字符 `String` 再加一个
     * `data class` 壳。语料 7,201 条全局 `#DIM` 平均 2-3 个运算符 ⇒ 约 4.5 万次、
     * 每次约 64 B 的纯垃圾分配（`session.dims` 那 32.1 MiB 的构成之一）。
     * `Tok.Sym` 是不可变 data class，共享实例对 `==`/`is Tok.Sym`/`t.text == ","`
     * 三种既有用法都逐字等价（相等判据本来就走 `text` 的内容比较）。
     * 双字符位移 `<<`/`>>` 同样各留一份常量。
     */
    private fun symOf(c: Char): Tok.Sym = when (c) {
        ',' -> SYM_COMMA
        '=' -> SYM_EQ
        '(' -> SYM_LP
        ')' -> SYM_RP
        '+' -> SYM_PLUS
        '-' -> SYM_MINUS
        '*' -> SYM_STAR
        '/' -> SYM_SLASH
        '%' -> SYM_PERCENT
        '<' -> SYM_LT
        '>' -> SYM_GT
        else -> Tok.Sym(c.toString())
    }

    private val SYM_COMMA = Tok.Sym(",")
    private val SYM_EQ = Tok.Sym("=")
    private val SYM_LP = Tok.Sym("(")
    private val SYM_RP = Tok.Sym(")")
    private val SYM_PLUS = Tok.Sym("+")
    private val SYM_MINUS = Tok.Sym("-")
    private val SYM_STAR = Tok.Sym("*")
    private val SYM_SLASH = Tok.Sym("/")
    private val SYM_PERCENT = Tok.Sym("%")
    private val SYM_LT = Tok.Sym("<")
    private val SYM_GT = Tok.Sym(">")
    private val SYM_SHL = Tok.Sym("<<")
    private val SYM_SHR = Tok.Sym(">>")

    /**
     * `#DIM` 行首关键字识别（**零分配**版，mem6 2026-10-28）。
     *
     * 旧写法 `t.text.uppercase() !in KEYWORDS` 对**每个行首 token**都要产一份大写串
     * （`upper()` 只在「无字符变化」时才返回自身，语料里大小写混写与半角片假名的声明不少）。
     * 关键字集合就是 [KEYWORDS] 那 7 个纯 ASCII 词，按长度分档 + `equals(ignoreCase = true)`
     * 逐字符比较，命中判据与「先大写化再查集合」逐字等价（Kotlin 的大小写不敏感比较
     * 走的也是 `Char.toUpperCase` 相等，与 `String.uppercase()` 同一套单字符映射；
     * 会因「一字符大写化成多字符」而 length 变化的形态在两档下都不命中）。
     * 返回**大写形**，与原实现 `val kw = t.text.uppercase()` 喂进 `when (kw)` 的值一致。
     */
    private fun keywordOf(text: String): String? = when (text.length) {
        3 -> if (text.equals("REF", true)) "REF" else null
        5 -> if (text.equals("CONST", true)) "CONST" else null
        6 -> when {
            text.equals("STATIC", true) -> "STATIC"
            text.equals("GLOBAL", true) -> "GLOBAL"
            else -> null
        }
        7 -> if (text.equals("DYNAMIC", true)) "DYNAMIC" else null
        8 -> if (text.equals("SAVEDATA", true)) "SAVEDATA" else null
        9 -> if (text.equals("CHARADATA", true)) "CHARADATA" else null
        else -> null
    }

    /**
     * 尺寸段的定长向量（mem6）：`dims` 最多 4 项（超过 3 维在 [parse] 尾部就 CodeEE），
     * 旧写法 `ArrayList<Int>` 每一项都要一个 `Integer` 壳（> 127 的尺寸不命中缓存），
     * 换成一条 `IntArray` + 计数，只在建 `DimSpec` 时截一次。
     */
    private class IntVec(initial: Int = 4) {
        private var data = IntArray(initial)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun isEmpty(): Boolean = size == 0

        operator fun get(i: Int): Int = data[i]

        fun toIntArray(): IntArray = data.copyOf(size)
    }

    /** 整型初值段的定长向量（同 [IntVec]，元素是 `long`；旧写法是 `ArrayList<Long>`）。 */
    private class LongVec(initial: Int = 4) {
        private var data = LongArray(initial)
        var size = 0
            private set

        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toLongArray(): LongArray = data.copyOf(size)
    }

    private fun lex(s: String, lexer: LazyLexer): List<Tok> {
        val out = ArrayList<Tok>(16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                // 行尾注释：`;` 起整段丢弃（spec: LexicalAnalyzer.cs:917-930
                // case ';'：非 ";!;" 时 st.Seek(0, SeekOrigin.End) 后 return）。
                // `#DIM CHRLIST , 人物数量上限 + 10 ;いちおう大きめに取っておく` 靠这条才读得出尺寸。
                c == ';' -> return out
                c == '@' && i + 1 < s.length && s[i + 1] == '"' -> {
                    val (tok, next) = readFormatString(s, i + 2)
                    out.add(tok)
                    i = next
                }
                c == ',' || c == '=' -> {
                    out.add(symOf(c))
                    i++
                }
                // 位移双字符（M4-M，spec: OperatorMethodManager.cs:740-741 注册区；
                // 词法层按 LexicalAnalyzer.cs:404-460 ReadOperator 的贪长匹配产出 `<<`/`>>`）。
                // 单个 `<`/`>` 比较运算符仍不折叠（原版关系运算走别的路径，#DIM 行无需求）。
                c == '<' || c == '>' -> {
                    if (i + 1 < s.length && s[i + 1] == c) {
                        out.add(if (c == '<') SYM_SHL else SYM_SHR)
                        i += 2
                    } else {
                        out.add(symOf(c))
                        i++
                    }
                }
                c in "+-*/%()" -> {
                    out.add(symOf(c))
                    i++
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) {
                            i++
                            sb.append(
                                when (s[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> s[i]
                                },
                            )
                        } else {
                            sb.append(s[i])
                        }
                        i++
                    }
                    if (i >= s.length) throw CodeEE("字符串字面量未闭合：$s")
                    i++
                    out.add(Tok.Str(sb.toString()))
                }
                // 负号是**一元运算符**而非字面量的一部分（spec: LexicalAnalyzer.cs:767-791
                // 运算符分支产 SymbolWord，:755-765 数字分支只从 ASCII 数字起头），
                // `-1` 由 [Folder] 的一元负号折叠——与 `ConstantData.tryToInt64` 先剥符号再乘回去
                // （spec: ConstantData.cs:1532-1541 / :1558）同值，但 `A - 1` 也能读通。
                // 数字起头 = 字面量（spec: LexicalAnalyzer.cs:755-765 的 case '0'..'9'；
                // 全角数字在原版本走标识符分支，故入口只认 ASCII 数字）。
                c in '0'..'9' -> {
                    val fast = fastDecimalAt(s, i)
                    if (fast != null) {
                        out.add(Tok.Number(fast.first))
                        i = fast.second
                    } else {
                        val (v, next) = readInt64At(s, i, lexer)
                        out.add(Tok.Number(v))
                        i = next
                    }
                }
                else -> {
                    val start = i
                    while (i < s.length && !isWordBreak(s[i])) i++
                    out.add(Tok.Word(s.substring(start, i)))
                }
            }
        }
        return out
    }

    /**
     * `@"..."` 逐字格式串（入口 [lex] 的 `@` + `"` 分支）。
     *
     * 逐条对齐 `AnalyseFormattedString(st, FormStrEndWith.DoubleQuotation, trim: false)`
     * （spec: LexicalAnalyzer.cs:1073-1230）：
     * - `{expr}` → `CurlyBraceSubWord`（spec: LexicalAnalyzer.cs:1120-1129）：插入**子表达式**，
     *   子式经 `Restructure` 折叠为 `SingleTerm` 后并入字面段
     *   （spec: StrForm.cs:363-396 Restructure / StrFormTerm.cs:27-39 → 全折叠后 `IsConst`）；
     * - `%expr%` → `PercentSubWord`（spec: LexicalAnalyzer.cs:1113-1119）：同上；
     * - `\n`→换行、`\t`→制表、`\s`→半角空格、`\S`→全角空格 U+3000、`\@`→`\@` 子式
     *   （spec: LexicalAnalyzer.cs:1140-1183），其余 `\x` 原样保留 `x`
     *   （decompiler 标签 `IL_02ed: stringBuilder.Append(current)`，spec: LexicalAnalyzer.cs:1194-1197）；
     * - **没有 `""` 转义**：`case '"'` 在 `endWith == DoubleQuotation` 时直接结束串
     *   （spec: LexicalAnalyzer.cs:1079-1085），普通 `"..."` 走 `ReadString` 同样在首个 `"` 返回
     *   （spec: LexicalAnalyzer.cs:343-348）。
     * - `trim = false` → 首尾空白**不裁剪**（对比 spec: LexicalAnalyzer.cs:1220-1226 的 trim 分支）。
     */
    private fun readFormatString(s: String, from: Int): Pair<Tok, Int> {
        val lits = ArrayList<String>()
        val inners = ArrayList<String>()
        val sb = StringBuilder()
        var i = from
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> {
                    lits.add(sb.toString())
                    return Tok.Format(lits, inners) to (i + 1)
                }
                '{', '%' -> {
                    val close = if (c == '{') '}' else '%'
                    val start = i + 1
                    val end = s.indexOf(close, start)
                    if (end < 0) throw CodeEE("字符串字面量未闭合（缺 ${close}）：${s.substring(from)}")
                    lits.add(sb.toString())
                    sb.setLength(0)
                    inners.add(s.substring(start, end))
                    i = end + 1
                }
                '\\' -> {
                    if (i + 1 >= s.length) throw CodeEE("转义字符缺失：${s.substring(from)}")
                    val e = s[i + 1]
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        's' -> sb.append(' ')
                        'S' -> sb.append('\u3000')
                        '@' -> {
                            // `\@ ... ?` 是三元子表达式（AnalyseYenAt，spec: LexicalAnalyzer.cs:1239-1258）；
                            // 折叠需要运行期条件，初值路径按非常量失败。
                            lits.add(sb.toString())
                            sb.setLength(0)
                            val end = s.indexOf('?', i + 2)
                            if (end < 0) throw CodeEE("字符串字面量未闭合（缺 ?）：${s.substring(from)}")
                            inners.add(s.substring(i + 2, end))
                            i = end + 1
                            continue
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                }
                '\n' -> throw CodeEE("字符串字面量未闭合：${s.substring(from)}")
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        throw CodeEE("字符串字面量未闭合：${s.substring(from)}")
    }

    // ------------------------------------------------------------------
    // 编译期常量折叠（尺寸段 + 初值段）
    // ------------------------------------------------------------------

    /**
     * `#DIM` 尺寸段与初值段的编译期折叠器。
     *
     * 原版两处都不是「读一个记号」，而是把记号流交给表达式层：
     * - 尺寸：`ExpressionParser.ReduceIntegerTerm(wc, TermEndWith.Comma_Assignment).Restructure(null)`
     *   且结果必须是 `SingleLongTerm`，否则 `HasNotExpressionAfterComma`
     *   （spec: UserDefinedVariableData.cs:325-326；越界判据 :337-340）；
     * - 初值：`ExpressionParser.ReduceArguments(wc, ArgsEndWith.EoL, isDefine: false)` 逐项
     *   `Restructure(GlobalStatic.EMediator)`，非 `SingleTerm` 即 `InitialValueOnlyConst`
     *   （spec: UserDefinedVariableData.cs:375 / :406-408），类型不符即
     *   `NotMatchVarTypeAndInitialValue`（:410-411）。
     *
     * 折叠后 `1 + 2`、`(上限 + 1) * 10` 都是 `SingleTerm` 常量（spec: variables.yaml:180
     * 「初值必须编译为 SingleTerm 常量」），故本层接受；**未注入 [DimParseConfig.functionFolder]
     * 时函数调用与变量引用不是** `SingleTerm`（spec: ExpressionParser.cs:543-560 标识符后跟
     * `(`/`[` 走 `GetFunctionMethod`，返回 `FunctionMethodTerm`），保持失败口径。
     *
     * 覆盖的算子（M4-M 收口）：`+ - * / %`、一元 `-`、括号、位移 `<<`/`>>`
     * （spec: OperatorMethodManager.cs:523-534 / :509-520，优先级按 C# 位于加减之上）、
     * 字符串 `+` 链（spec: OperatorMethodManager.cs:28-33 + :41-44）；
     * `&`/`|` 在原版是逻辑与或（spec: OperatorMethodManager.cs:375-410）而非按位，未接。
     * 函数调用折叠经 [DimParseConfig.functionFolder] 注入（spec: FunctionMethodTerm.cs:35-56）。
     */
    private class Folder(
        val toks: List<Tok>,
        var i: Int,
        val config: DimParseConfig,
        val kw: String,
        val name: String,
        /** 同一条声明共用的词法器（mem6）；嵌套折叠（宏体 / 函数实参 / 插值子式）都传这一只。 */
        val lexer: LazyLexer,
        val nesting: Int = 0,
        /** 函数实参模式：`)` 也是段终止符（callArgs 逐参复用时置真）。 */
        val inCall: Boolean = false,
    ) {
        /** 段终止 = 行尾 / `,` / `=`（原版 `TermEndWith.Comma_Assignment`、`ArgsEndWith.EoL`）。 */
        private fun atEnd(): Boolean {
            val t = toks.getOrNull(i) ?: return true
            val s = (t as? Tok.Sym)?.text ?: return false
            return s == "," || s == "=" || (inCall && s == ")")
        }

        private fun isSym(text: String): Boolean = (toks.getOrNull(i) as? Tok.Sym)?.text == text

        private fun fail(detail: String): CodeEE = CodeEE("$kw $name $detail")

        /** 整数段折叠；返回后调用方自行处理分隔符。 */
        fun reduceInt(): Long {
            val v = shiftExpr()
            if (!atEnd()) throw fail("的尺寸/初值表达式有多余内容（原版 cs:325-326 HasNotExpressionAfterComma）")
            return v
        }

        /**
         * 位移段（M4-M，spec: OperatorMethodManager.cs:523-534 LeftShiftIntInt 注册 :741 /
         * :509-520 RightShiftIntInt 注册 :740）：原版 `arguments[0].GetIntValue(exm) <<
         * (int)arguments[1].GetIntValue(exm)` —— C# 对 long 的移位计数取低 6 位，
         * Kotlin `Long.shl` 同为 `and 0x3F`，`(int)` 截断在 toInt() 上复刻。
         * 优先级按**原版表达式层**而非 C#：`LeftShift = 136304` / `RightShift = 136048`
         * 的优先级字节（`__PRIORITY_MASK__` = 255）都是 112，低于 `Plus` 的 128
         * （spec: OperatorCode.cs:6/:11/:34-35）⇒ `1<<2+3` 折成 `1<<(2+3)`=32，
         * 两侧操作数都在 [additive] 层取，与移植侧 ExprParser 的 112/128 口径一致
         * （core/expr/ExprParserTest 钉桩）。
         */
        private fun shiftExpr(): Long {
            var v = additive()
            while (true) {
                when {
                    isSym("<<") -> { i++; v = v shl additive().toInt() }
                    isSym(">>") -> { i++; v = v shr additive().toInt() }
                    else -> return v
                }
            }
        }

        private fun additive(): Long {
            var v = multiplicative()
            while (true) {
                when {
                    isSym("+") -> { i++; v += multiplicative() }
                    isSym("-") -> { i++; v -= multiplicative() }
                    else -> return v
                }
            }
        }

        /**
         * `* / %`：整除与取余的**截断方向**跟 C# 一致（都向零取整，
         * Kotlin 的 `Long.div` / `rem` 同语义），除零按原版运行期异常方向失败。
         */
        private fun multiplicative(): Long {
            var v = unary()
            while (true) {
                when {
                    isSym("*") -> { i++; v *= unary() }
                    isSym("/") -> {
                        i++
                        val d = unary()
                        if (d == 0L) throw fail("除以 0（原版常量折叠期 DivideByZero → CodeEE）")
                        v /= d
                    }
                    isSym("%") -> {
                        i++
                        val d = unary()
                        if (d == 0L) throw fail("模 0（原版常量折叠期 DivideByZero → CodeEE）")
                        v %= d
                    }
                    else -> return v
                }
            }
        }

        private fun unary(): Long {
            if (isSym("-")) { i++; return -unary() }
            if (isSym("+")) { i++; return unary() }
            return primary()
        }

        private fun primary(): Long {
            val t = toks.getOrNull(i) ?: throw fail("的尺寸/初值表达式为空")
            when (t) {
                is Tok.Number -> { i++; return t.value }
                is Tok.Word -> { i++; return constInt(t.text) }
                is Tok.Sym -> {
                    if (t.text == "(") {
                        if (nesting > MAX_NESTING) throw fail("尺寸/初值表达式嵌套过深")
                        i++
                        val v = shiftExpr()
                        if (!isSym(")")) throw fail("缺少右括号（原版 cs:325 Restructure 不成立）")
                        i++
                        return v
                    }
                    throw fail("的尺寸/初值只能是整数常量")
                }
                else -> throw fail("的尺寸/初值只能是整数常量")
            }
        }

        /**
         * 标识符 → 整型常量；`NAME(` 形态先走注入的装载期折叠回调
         * （spec: FunctionMethodTerm.cs:35-56，`CanRestructure` 且实参全 `SingleTerm`
         * 即折成 `GetIntValue(exm)`），回调缺席 / 返回 null / 实参折不动时
         * 保持原失败口径（原版非 `SingleLongTerm`，spec: UserDefinedVariableData.cs:325-326）。
         * 回调自身抛的 [CodeEE]（POWER 越界一类，spec: FunctionMethodCreator.cs:4177-4187）
         * 原样上抛——原版折叠期同样直接炸。
         */
        private fun constInt(word: String): Long {
            if (isSym("(")) {
                val folder = config.functionFolder
                if (folder != null) {
                    val args = tryCallArgs()
                    if (args != null) return folder.foldInt(word.uppercase(), args) ?: throw fail(
                        "的常量表达式含函数调用 ${word}(...)，不是编译期常量（原版 cs:325 非 SingleLongTerm）",
                    )
                }
                throw fail("的常量表达式含函数调用 ${word}(...)，不是编译期常量（原版 cs:325 非 SingleLongTerm）")
            }
            resolveDimConstant(word, config, lexer, nesting)?.let { return it }
            throw fail("的尺寸 '${word}' 不能求值为整数常量" +
                "（非 #DEFINE 宏 / #DIM CONST 常量，原版 cs:321-323 HasNotExpressionAfterComma）")
        }

        /**
         * 解析 `NAME ( a , b )` 的实参串；入口 [i] 必须在 `(` 上，返回时越过 `)`。
         * 逐参「先整型、后字符串」折成 [DimFoldArg]（原版由函数签名的 argumentTypeArray
         * 决定取 `GetIntValue` 还是 `GetStrValue`，spec: FunctionMethodCreator.cs 各构造器；
         * 实参本身必须是 `SingleTerm` 才允许折叠，spec: FunctionMethodTerm.cs:35-56）。
         * 任一段折不动即返回 null，由调用方换成通用「不是编译期常量」文案。
         */
        private fun tryCallArgs(): List<DimFoldArg>? {
            val args = try {
                callArgs()
            } catch (e: CodeEE) {
                return null
            }
            return args
        }

        /**
         * 裸标识符实参（`GETNUM(TALENT, "人类")` 的第一参）。
         *
         * 只接受「单个标识符 token，且后面紧跟 `,` 或 `)`」的形态 —— 不吞表达式，
         * 也不改变其它函数的失败方向（[PureStaticDimFunctionFolder] 对非 GETNUM
         * 的 IdentArg 一律返回 null）。
         */
        private fun tryIdentArg(from: Int): Pair<Int, DimFoldArg>? {
            val t = toks.getOrNull(from) as? Tok.Word ?: return null
            val next = toks.getOrNull(from + 1) as? Tok.Sym ?: return null
            if (next.text != "," && next.text != ")") return null
            return (from + 1) to DimFoldArg.IdentArg(t.text)
        }

        private fun callArgs(): List<DimFoldArg> {
            if (nesting > MAX_NESTING) throw fail("尺寸/初值表达式嵌套过深")
            i++ // 消费 `(`
            val args = ArrayList<DimFoldArg>()
            if (isSym(")")) { i++; return args }
            while (true) {
                val from = i
                val asInt = try {
                    val f = Folder(toks, from, config, kw, name, lexer, nesting + 1, inCall = true)
                    val v = f.reduceInt()
                    f.i to DimFoldArg.IntArg(v)
                } catch (e: CodeEE) {
                    null
                }
                val (nextI, arg) = asInt ?: tryIdentArg(from) ?: run {
                    val g = Folder(toks, from, config, kw, name, lexer, nesting + 1, inCall = true)
                    val s = g.reduceStr()
                    g.i to DimFoldArg.StrArg(s)
                }
                i = nextI
                args.add(arg)
                when {
                    isSym(",") -> i++
                    isSym(")") -> { i++; return args }
                    else -> throw fail("的常量表达式函数实参列表格式错误")
                }
            }
        }

        /**
         * 字符串段折叠：`+` 链（M4-M，spec: OperatorMethodManager.cs:28-33 PlusStrStr，
         * CanRestructure；`:41-44` `GetStrValue` 直接拼接——两段都折成 `SingleStrTerm`
         * 才继续，否则整段按非 `SingleTerm` 失败，spec: UserDefinedVariableData.cs:406-408）。
         * 证据同 [reduceInt]，字符串侧另见 spec: UserDefinedVariableData.cs:413
         * （`((SingleStrTerm)singleTerm).Str`）。
         */
        fun reduceStr(): String {
            var s = strOperand()
            while (isSym("+")) {
                i++
                s += strOperand()
            }
            return s
        }

        /** `+` 的单个操作数：字面量 / `@"..."`（全折叠后成 `SingleStrTerm`）/ 字符串常量标识符 / 注入回调的字符串函数。 */
        private fun strOperand(): String {
            val t = toks.getOrNull(i) ?: throw fail("的初值不能为空")
            when (t) {
                is Tok.Str -> { i++; return t.text }
                is Tok.Format -> {
                    i++
                    if (t.lits.size != t.inners.size + 1) throw fail("的初值只能是常量")
                    val sb = StringBuilder()
                    sb.append(t.lits[0])
                    for (k in t.inners.indices) {
                        sb.append(innerValue(t.inners[k]))
                        sb.append(t.lits[k + 1])
                    }
                    return sb.toString()
                }
                is Tok.Word -> {
                    i++
                    if (isSym("(")) {
                        // 装载期可折叠字符串函数（UNICODE 一类，spec: FunctionMethodTerm.cs:35-56；
                        // `@"%UNICODE(...)%"` 的子式折叠 spec: StrFormTerm.cs:27-29 → StrForm.cs:364-376
                        // 由 innerValue 走到这里）。回调缺席 / 返回 null 保持原失败口径。
                        val folder = config.functionFolder
                        if (folder != null) {
                            val args = tryCallArgs()
                            if (args != null) folder.foldStr(t.text.uppercase(), args)?.let { return it }
                        }
                        throw fail("的初值含函数调用，不是编译期常量（原版 cs:406-408 InitialValueOnlyConst）")
                    }
                    return config.strConstants[t.text.uppercase()]
                        ?: throw fail("的初值 '${t.text}' 不能求值为常量")
                }
                else -> throw fail("的初值只能是常量")
            }
        }

        /**
         * `@"...{EXPR}..."` 子式折叠：先按整数表达式折，再按字符串字面量折；
         * 两者都不成立即非常量（原版 `StrForm.Restructure` 只并 `SingleTerm`，
         * spec: StrForm.cs:373-376 / :381-395）。
         *
         * 整→串的文本 = `SingleLongTerm.GetStrValue` = .NET `Int64.ToString()`，
         * 本层用 [Long.toString]（十进制无前导零、无分组，与不变文化一致）；
         * .NET 数字格式的其它分支由 g-dotnetfmt 收口。TODO(verify)
         */
        private fun innerValue(text: String): String {
            val sub = lex(text, lexer)
            if (sub.isEmpty()) throw fail("的初值插值表达式为空")
            val f = Folder(sub, 0, config, kw, name, lexer, nesting + 1)
            return try {
                // reduceInt 入口现含位移段；`%1<<3%` 一类插值同步可折（原版同一折叠面）。
                f.reduceInt().toString()
            } catch (e: CodeEE) {
                val g = Folder(lex(text, lexer), 0, config, kw, name, lexer, nesting + 1)
                val s = g.reduceStr()
                if (!g.atEnd()) throw fail("的初值插值不是常量")
                s
            }
        }

        private companion object { const val MAX_NESTING = 8 }
    }
}

/**
 * 用户变量运行期存储基类。
 *
 * 位置语义：`charPos` 仅对 `#DIM CHARADATA` 有意义，其余实现忽略；
 * `elem` 是行主序压平后的元素下标（多维由 [flatten] 计算）。
 */
abstract class UserVar(val spec: DimSpec, val def: VarDef) {
    open fun readInt(charPos: Int, elem: Int): Long {
        throw CodeEE("变量 ${def.name} 不是整数变量")
    }

    open fun writeInt(charPos: Int, elem: Int, value: Long) {
        throw CodeEE("变量 ${def.name} 是常量，不能赋值")
    }

    open fun readStr(charPos: Int, elem: Int): String {
        throw CodeEE("变量 ${def.name} 不是字符串变量")
    }

    open fun writeStr(charPos: Int, elem: Int, value: String) {
        throw CodeEE("变量 ${def.name} 是常量，不能赋值")
    }

    /** 进入函数作用域（私有动态变量压栈并新建）。spec: VariableData.cs:1877-1888 */
    open fun scopeIn() {}

    /** 离开函数作用域（弹栈恢复）。spec: VariableData.cs:1890-1901 */
    open fun scopeOut() {}

    /** 清零并回填初值。spec: VariableData.cs:1323-1332 */
    abstract fun setDefault()

    /** 持久化用原始数组（LongArray / IntArray 池下标），M4 存档层使用。 */
    open fun rawArray(): Any? = null
}

/** 静态整数用户变量（含 GLOBAL/SAVEDATA）。spec: VariableData.cs:1295-1386 */
class StaticIntUserVar(spec: DimSpec, def: VarDef) : UserVar(spec, def) {
    private var data: LongArray? = null

    /** 懒分配时回填初值。spec: VariableData.cs:1311-1321 (IfNullInitArray) */
    private fun arr(): LongArray = data ?: LongArray(spec.totalSize).also { d ->
        spec.defaultsInt?.copyInto(d, 0, 0, minOf(d.size, spec.defaultsInt.size))
        data = d
    }

    override fun readInt(charPos: Int, elem: Int): Long = arr()[elem]

    override fun writeInt(charPos: Int, elem: Int, value: Long) {
        if (spec.isConst) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        arr()[elem] = value
    }

    override fun setDefault() {
        val d = arr()
        d.fill(0)
        spec.defaultsInt?.copyInto(d, 0, 0, minOf(d.size, spec.defaultsInt.size))
    }

    override fun rawArray(): Any = arr()
}

/** 静态字符串用户变量。槽位存驻留池下标。 */
class StaticStrUserVar(spec: DimSpec, def: VarDef, private val pool: StringTable) : UserVar(spec, def) {
    private var data: IntArray? = null
    private var defaults: IntArray? = null

    private fun arr(): IntArray = data ?: IntArray(spec.totalSize).also { d ->
        defaultIds().copyInto(d)
        data = d
    }

    private fun defaultIds(): IntArray =
        defaults ?: IntArray(spec.totalSize).also { ids ->
            spec.defaultsStr?.forEachIndexed { i, s -> if (i < ids.size) ids[i] = pool.intern(s) }
            defaults = ids
        }

    override fun readStr(charPos: Int, elem: Int): String = pool.lookup(arr()[elem])

    override fun writeStr(charPos: Int, elem: Int, value: String) {
        if (spec.isConst) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        arr()[elem] = pool.intern(value)
    }

    override fun setDefault() {
        val d = arr()
        val defs = spec.defaultsStr
        if (defs == null) {
            d.fill(0)
        } else {
            // spec: VariableData.cs:1595-1606 —— StaticStr1DVariableToken.SetDefault 在
            // defArray 非空时**只回填 defArray**，不复位其后的元素（同族 StaticInt1D 在
            // :1323-1332 才是"先清零再回填"）。官方怪癖照抄（ADR-005）。
            defaultIds().copyInto(d, 0, 0, defs.size)
        }
    }

    override fun rawArray(): Any = arr()
}

/** 私有动态整数变量：栈式嵌套作用域。spec: VariableData.cs:1820-1901 */
class DynamicIntUserVar(spec: DimSpec, def: VarDef) : UserVar(spec, def) {
    private var data: LongArray? = null
    private val stack = ArrayDeque<LongArray>()

    private fun arr(): LongArray = data ?: throw CodeEE("动态变量 ${def.name} 在当前作用域外被访问")

    override fun readInt(charPos: Int, elem: Int): Long = arr()[elem]

    override fun writeInt(charPos: Int, elem: Int, value: Long) {
        arr()[elem] = value
    }

    override fun scopeIn() {
        data?.let { stack.addLast(it) }
        val next = LongArray(spec.totalSize)
        spec.defaultsInt?.copyInto(next, 0, 0, minOf(next.size, spec.defaultsInt.size))
        data = next
    }

    override fun scopeOut() {
        data = if (stack.isEmpty()) null else stack.removeLast()
    }

    /** 动态变量无值时不做初始化。spec: VariableData.cs:1836-1838 */
    override fun setDefault() {}

    override fun rawArray(): Any? = data
}

/** 私有动态字符串变量。 */
class DynamicStrUserVar(spec: DimSpec, def: VarDef, private val pool: StringTable) : UserVar(spec, def) {
    private var data: IntArray? = null
    private val stack = ArrayDeque<IntArray>()

    private fun arr(): IntArray = data ?: throw CodeEE("动态变量 ${def.name} 在当前作用域外被访问")

    private fun newArray(): IntArray = IntArray(spec.totalSize).also { ids ->
        spec.defaultsStr?.forEachIndexed { i, s -> if (i < ids.size) ids[i] = pool.intern(s) }
    }

    override fun readStr(charPos: Int, elem: Int): String = pool.lookup(arr()[elem])

    override fun writeStr(charPos: Int, elem: Int, value: String) {
        arr()[elem] = pool.intern(value)
    }

    override fun scopeIn() {
        data?.let { stack.addLast(it) }
        data = newArray()
    }

    override fun scopeOut() {
        data = if (stack.isEmpty()) null else stack.removeLast()
    }

    override fun setDefault() {}

    override fun rawArray(): Any? = data
}

/**
 * 引用变量：读未绑定时抛 EmptyRefVar 等价错误。
 * spec: VariableData.cs:2325-2332, ReferenceToken.cs:91-114
 *
 * [targetDims] 是**绑定目标**的各维长度（来自被引用变量的 [VarDef.dims]）。原版 REF 绑的是
 * CLR 数组对象本身（`SetRef(Array)`，spec: ReferenceToken.cs:125-128），多维 `long[,]`/`long[,,]`
 * 自带形状，`GetLength(dimension)` / `CheckElement` 直接问数组本体
 * （spec: ReferenceToken.cs:32-76），装载期按 `CanNotRefDifferentDim` 拒绝维数不符的绑定
 * （spec: ReferenceToken.cs:163-167）。本移植的存储是行主序压平的一维数组，形状在绑定点必须
 * 显式携带，否则多维 REF 的下标被截成第一个下标、也无从逐维判界与压平。
 *
 * 形状口径与 [VarDef.dims] 一致：只含**元素维**，不含角色位置维。CHARADATA 目标
 * 在原版被 `MatchType(allowChara: false)` 拒绝（spec: CalledFunction.cs:159 /
 * ReferenceToken.cs:148-152），[com.eratw.port.core.proc.ProcessRuntime.bindRef]
 * 同样不绑，故这里不存在「角色维 + 元素维」的拼接。
 * 未绑定 / 形状未知时 [targetDims] 保持 null，由下游按一维口径处理或报错，绝不猜形状。
 */
class RefUserVar(spec: DimSpec, def: VarDef, private val pool: StringTable) : UserVar(spec, def) {
    var targetInt: LongArray? = null
    var targetStr: IntArray? = null

    /** 绑定目标（元素维）的各维长度；null = 未绑定或形状未知（旧式手工绑定只给压平数组）。 */
    var targetDims: IntArray? = null

    private var counter = 0

    /**
     * 作用域栈：一次压入（整型目标, 字符串目标, 形状）三件套，ScopeOut 时连形状一起恢复
     * （spec: ReferenceToken.cs:91-114）。原版只把数组本体压进 arrayList、形状随数组对象走；
     * 本移植形状是独立字段，必须与目标同进同出，否则嵌套作用域返回后多维 REF 会静默丢形状。
     */
    private val saved = ArrayDeque<Triple<LongArray?, IntArray?, IntArray?>>()

    fun bindInt(array: LongArray, dims: IntArray? = null) {
        targetInt = array
        targetStr = null
        targetDims = shapeOf(array.size, dims)
    }

    fun bindStr(array: IntArray, dims: IntArray? = null) {
        targetStr = array
        targetInt = null
        targetDims = shapeOf(array.size, dims)
    }

    /**
     * 形状归一：与声明维数一致的 dims 原样保留（取副本）；1 维引用在未给形状时按目标长度补
     * `[size]`（兼容旧调用点 `bindInt(array)` —— 原版 1 维绑定的 `GetLength(0)` 恒等于
     * `array.Length`，spec: ReferenceToken.cs:42/:45-56）；多维引用未给或给错形状时保留 null，
     * 由 [com.eratw.port.core.vars.VariableStore.refElem] 保持「全 0 才放行、否则报错」的口径，
     * 绝不猜一个形状去压平（原版在装载期就按 `CanNotRefDifferentDim` 拒绝维数不符的绑定，
     * spec: ReferenceToken.cs:163-167）。
     */
    private fun shapeOf(size: Int, dims: IntArray?): IntArray? = when {
        dims != null && dims.size == spec.dims.size -> dims.copyOf()
        spec.dims.size <= 1 -> intArrayOf(size)
        else -> null
    }

    /** 已绑定目标的压平元素总数（array.Length）；未绑定返回 null。spec: ReferenceToken.cs:32-43 */
    fun boundLength(): Int? = targetInt?.size ?: targetStr?.size

    private fun intTarget(): LongArray {
        val t = targetInt ?: throw CodeEE("引用变量 ${def.name} 未绑定目标（EmptyRefVar）")
        return t
    }

    private fun strTarget(): IntArray {
        val t = targetStr ?: throw CodeEE("引用变量 ${def.name} 未绑定目标（EmptyRefVar）")
        return t
    }

    private fun checkBound(size: Int, elem: Int) {
        if (elem < 0 || elem >= size) {
            throw CodeEE("引用变量 ${def.name} 的下标 $elem 越界（目标长度 $size）")
        }
    }

    override fun readInt(charPos: Int, elem: Int): Long {
        val t = intTarget()
        checkBound(t.size, elem)
        return t[elem]
    }

    override fun writeInt(charPos: Int, elem: Int, value: Long) {
        val t = intTarget()
        checkBound(t.size, elem)
        t[elem] = value
    }

    override fun readStr(charPos: Int, elem: Int): String {
        val t = strTarget()
        checkBound(t.size, elem)
        return pool.lookup(t[elem])
    }

    override fun writeStr(charPos: Int, elem: Int, value: String) {
        val t = strTarget()
        checkBound(t.size, elem)
        t[elem] = pool.intern(value)
    }

    override fun scopeIn() {
        if (counter > 0) saved.addLast(Triple(targetInt, targetStr, targetDims))
        counter++
        targetInt = null
        targetStr = null
        targetDims = null
    }

    override fun scopeOut() {
        if (saved.isNotEmpty()) {
            // 三件套一起出栈：形状必须跟着目标恢复，否则嵌套作用域返回后多维 REF 丢形状。
            val (i, s, d) = saved.removeLast()
            targetInt = i
            targetStr = s
            targetDims = d
        } else {
            targetInt = null
            targetStr = null
            targetDims = null
        }
        counter--
    }

    override fun setDefault() {
        targetInt = null
        targetStr = null
        targetDims = null
    }
}

/**
 * `#DIM CHARADATA` 整数变量：每角色一份，落在 [CharacterTable.int1] 追加列上
 * （`CharacterData.UserDefCVarDataList[ArrayIndex]`，spec: CharacterData.cs:86-125）。
 */
class CharaIntUserVar(
    spec: DimSpec,
    def: VarDef,
    private val characters: CharacterTable,
    private val col: Int,
) : UserVar(spec, def) {
    override fun readInt(charPos: Int, elem: Int): Long = characters.int1[col].get(charPos, elem)

    override fun writeInt(charPos: Int, elem: Int, value: Long) {
        characters.int1[col].set(charPos, elem, value)
    }

    override fun setDefault() {
        for (i in 0 until characters.count) characters.int1[col].fill(i, 0L)
    }
}

/** `#DIM CHARADATA` 字符串变量。 */
class CharaStrUserVar(
    spec: DimSpec,
    def: VarDef,
    private val characters: CharacterTable,
    private val col: Int,
    private val pool: StringTable,
) : UserVar(spec, def) {
    override fun readStr(charPos: Int, elem: Int): String =
        pool.lookup(characters.str1[col].get(charPos, elem))

    override fun writeStr(charPos: Int, elem: Int, value: String) {
        characters.str1[col].set(charPos, elem, pool.intern(value))
    }

    override fun setDefault() {
        for (i in 0 until characters.count) characters.str1[col].fill(i, 0)
    }
}

/** 多维下标 → 行主序元素下标。 */
fun flatten(indices: IntArray, start: Int, dims: IntArray): Int {
    var idx = 0
    for (i in dims.indices) idx = idx * dims[i] + indices[start + i]
    return idx
}
