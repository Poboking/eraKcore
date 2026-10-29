package com.eratw.port.erbcc

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Symbols.kt —— @函数标签符号表（装载序 + 分类 + 覆盖规则 + 查询）
 *
 * 全部规则来自反编译源码，注释里以 `spec: 文件:行号` 标注（路径相对 reference/emuera-cs/）。
 * 定稿结论（详见 specs/loader.md 附录 D）：
 *
 *  1. 非事件函数重名 → **后装载者生效**。
 *     装载期每文件末尾调 `LabelDictionary.SortLabel`（ErbLoader.cs:514-518），
 *     非事件分支执行 `noneventLabelDic[labelName] = label`（LabelDictionary.cs:60-68）——
 *     字典直接赋值，最后写入者留下。
 *     另一条路径 `SortLabels()`（LabelDictionary.cs:154-241，按 FileIndex→行号取 [0]，即先装载者胜）
 *     **没有任何调用点**（ErbLoader.cs:568-575 的私有 `setLabelsArg()` 是死代码），故不采用。
 *
 *  2. 事件函数（EVENTFIRST/EVENTTRAIN/EVENTLOAD/EVENTSHOP/EVENTBUY/EVENTCOM/EVENTEND/
 *     EVENTTURNEND/EVENTCOMEND）不作覆盖：全部注册到 `eventLabelDic[name]` 的 4 个桶
 *     （LabelDictionary.cs:70-105），调用时按 ONLY(0) → PRI(1) → 普通(2) → LATER(3) 顺序
 *     逐个执行（CalledFunction.cs:228-243）。`#ONLY` 命中一个即终止后续（ProcessState.cs:326-333）。
 *     `CompatiCallEvent=false`（本游戏 emuera.config:ALLOW CALL ON EVENT FUNCTIONS:NO）时
 *     事件函数不能出现在 `noneventLabelDic`，`CALL EVENTTRAIN` 直接 CodeEE（CalledFunction.cs:100-103）。
 *
 *  3. 大小写：`IgnoreCase=YES`（emuera.config）→ `Config.StrComper = OrdinalIgnoreCase`
 *     （Config.cs:33, 299-308），labelAtDic/noneventLabelDic 全部用它构建（LabelDictionary.cs:12-28）。
 *     故 @Foo 与 @FOO 是同一个键。
 *
 *  4. 同文件内重复：与跨文件同规则——同一文件的 `list` 按行序注册，后者覆盖前者（非事件）；
 *     事件则全部进入桶，按行序执行。原版只告警（`WarnNormalFunctionOverloading`，
 *     ErbLoader.cs:443-451，warnLevel=1，不中断装载）。
 *
 *  5. 与系统语句/内建函数重名：`AllowFunctionOverloading` 默认 true（ConfigData.cs:89），
 *     本游戏 emuera.config:ALLOW OVERRIDING SYSTEM FUNCTIONS:YES → 仅 warnLevel=1 警告
 *     （IdentifierDictionary.cs:210-243），标签照常注册。若为 false，warnLevel=2 →
 *     InvalidLabelLine，标签**不注册**（LogicalLineParser.cs:330-338, 370-382）。
 *     语句位置的解析优先级仍归指令表（LogicalLineParser.cs:409-414），用户函数只在
 *     `CALL`/方法表达式位置参与（IdentifierDictionary.cs:538-586）。
 *
 *  6. 玩家自制 ERB 与本体冲突：没有任何"用户优先"特权，胜负完全由装载序决定
 *     （Config.getFiles 的 culture 排序，Config.cs:654-706）。
 *
 * 装载序（ErbLoader.cs:210-264 + Config.cs:654-706）：
 *   先名字含 '#' 的目录（未排序）→ 其余按 getFiles：同层文件排序 → 同层子目录排序 → 递归前序 DFS。
 *   本语料无 '#' 目录；`SortWithFilename=YES`、`SearchSubdirectory=YES`。
 *
 * 排序保真（ADR-006）：原版 `Array.Sort(string[])` 是 culture 敏感比较（Config.cs:681），
 * 本实现默认冻结为 `ja-JP` Collator，并保留 `ORDINAL` 作为对照项（[CollatorOption]）。
 * 两者在极端文件名对上可能不同序（.NET CompareInfo 与 java.text.Collator 的权重表不等价），
 * 属**近似**；`SymbolTable.collatorCheck` / `erbcc symbols --collator-check` 用全量语料自检，
 * 结果为 6 组重名、30 条定义、胜者与事件执行序 **零翻转**（见 tools/symbols-report/）。
 */
object Symbols {

    // spec: IdentifierDictionary.cs:72-89
    val EVENT_LABEL_NAMES: Set<String> = setOf(
        "EVENTFIRST", "EVENTTRAIN", "EVENTLOAD", "EVENTSHOP", "EVENTBUY",
        "EVENTCOM", "EVENTEND", "EVENTTURNEND", "EVENTCOMEND",
    )

    // spec: IdentifierDictionary.cs:91-135
    val SYSTEM_LABEL_NAMES: Set<String> = setOf(
        "EVENTFIRST", "EVENTTRAIN", "EVENTLOAD", "EVENTSHOP", "SHOW_SHOP", "USERABLUP",
        "SHOW_JUEL", "EVENTBUY", "EVENTCOM", "EVENTEND", "SAVEINFO", "USERSHOP",
        "SOURCE_CHECK", "EVENTTURNEND", "SYSTEM_TITLE", "CALLTRAINEND", "SHOW_USERCOM",
        "EVENTCOMEND", "SHOW_STATUS", "SYSTEM_LOADEND", "TITLE_LOADGAME", "USERCOM",
        "SHOW_ABLUP_SELECT", "SYSTEM_AUTOSAVE",
    )

    // spec: IdentifierDictionary.cs:121-132（^COM[0-9]+$ / ^COM_ABLE[0-9]+$ / ^ABLUP[0-9]+$）
    private val SYSTEM_LABEL_REGEXES = listOf(
        Regex("^COM[0-9]+$"), Regex("^COM_ABLE[0-9]+$"), Regex("^ABLUP[0-9]+$"),
    )

    fun isEventLabelName(name: String): Boolean = name.uppercase(Locale.ROOT) in EVENT_LABEL_NAMES

    fun isSystemLabelName(name: String): Boolean {
        val u = name.uppercase(Locale.ROOT)
        return u in SYSTEM_LABEL_NAMES || SYSTEM_LABEL_REGEXES.any { it.matches(u) }
    }

    // -----------------------------------------------------------------------
    // CORE-06 —— 用户变量/宏名撞「系统名」的拒绝表
    //
    // 原版把所有内建名登记进一张 `nameDic`（spec: IdentifierDictionary.cs:38-48 定义、
    // :136-183 构造），用户 `#DIM` / `#DEFINE` 撞上即报错（**不是**静默接受）：
    //   - 变量名走 `CheckUserVarName`（spec: IdentifierDictionary.cs:258-297，
    //     局部 `#DIM` 走 `CheckUserPrivateVarName` :340-392），warnLevel 恒 2
    //     → `UserDefinedVariableData.cs:280-292` 抛 CodeEE → 该行无效、变量不注册；
    //   - 宏名走 `CheckUserMacroName`（spec: IdentifierDictionary.cs:299-338），
    //     warnLevel 恒 2 → `ErhLoader.cs:155-164` 置 `noError=false` → 整条 ERH 链中断。
    // 比较器是 `Config.StrComper`（:48），本游戏 `IGNORE CASE:YES` ⇒ 大写归一。
    // -----------------------------------------------------------------------

    /** 原版 `nameDic` 中会让用户变量名失效的四类（spec: IdentifierDictionary.cs:141-183）。 */
    enum class NameKind {
        /** 语法保留字（`#DIM REF` 里的 REF 之类） */
        RESERVED,

        /** `FunctionMethodCreator.GetMethodList()` 的成员（:170） */
        SYSTEM_METHOD,

        /** `VariableData` 的全局/局部系统变量（:174/:178） */
        SYSTEM_VARIABLE,

        /** `FunctionIdentifier.GetInstructionNameDic()` 的指令名（:182） */
        SYSTEM_INSTRUMENT,
    }

    /**
     * 15 个保留字（spec: IdentifierDictionary.cs:141-155，顺序照抄 `nameDic.Add` 次序）。
     * 撞上即 `VarConflictReservedWord` / `MacroConflictReservedWord`（zhs:873 / zhs:881）。
     */
    val RESERVED_NAMES: List<String> = listOf(
        "IS", "TO", "INT", "STR", "REFFUNC", "STATIC", "DYNAMIC", "GLOBAL", "PRIVATE",
        "SAVEDATA", "CHARADATA", "REF", "__DEBUG__", "__SKIP__", "_",
    )

    /**
     * 名字在原版 `nameDic` 里的类别；null = 未登记（用户可自由使用）。
     *
     * 查找顺序复刻原版**注册顺序**（先注册者占住键）：方法用 `Add`（:170）→ 变量用
     * `TryAdd`（:174/:178）→ 指令用 `TryAdd`（:182），故同名同时是方法与变量时判
     * SystemMethod（两者的报错文案相同，见下）。保留字先于全部内建登记（:141-155）。
     *
     * TODO(verify)：指令表用 [com.eratw.port.core.stmt.FunctionCode.BY_NAME]，该表为
     * CMP-48 剔除了 `SET`/`METHOD` 两个伪码名（FunctionCode.kt:484-490）——原版
     * `FunctionIdentifier` 里它们是正式指令，`#DIM SET` 在原版会被拒，本实现不会。
     * 语料 0 命中。
     */
    fun nameKind(name: String): NameKind? {
        val u = name.uppercase(Locale.ROOT)
        if (u in RESERVED_NAMES) return NameKind.RESERVED
        if (com.eratw.port.core.stmt.BuiltinSymbols.isBuiltinMethod(u)) return NameKind.SYSTEM_METHOD
        if (com.eratw.port.core.stmt.BuiltinSymbols.variable(u) != null) return NameKind.SYSTEM_VARIABLE
        if (com.eratw.port.core.stmt.FunctionCode.BY_NAME.containsKey(u)) return NameKind.SYSTEM_INSTRUMENT
        return null
    }

    /**
     * `badSymbolAsIdentifier`（spec: IdentifierDictionary.cs:40 定义，:261/:302/:348 三处使用）：
     * 变量名/宏名只允许「字母、数字、`_`」与非 ASCII 字符（日文名合法）。返回 true = 命中。
     *
     * 原版那张表是编译期嵌入的字符 Span，反编译读不到字面量；这里按 `VarContainsOtherThanUnderline`
     * 的文案（zhs:872）取「ASCII 可见标点中除 `_` 之外一律非法」的等价口径。
     */
    internal fun hasBadSymbol(name: String): Boolean =
        name.any { c -> c.code in 0x21..0x7E && c != '_' && !c.isLetterOrDigit() }

    /**
     * `CheckUserVarName`（spec: IdentifierDictionary.cs:258-297）：返回官方错误文案，
     * null = 原版接受。
     *
     * @param userMacros 已登记的宏名（大写归一口径）；命中 = `UserMacro`（zhs:876）
     * @param userGlobals 已登记的全局变量名（大写归一）；命中 = `UserGlobalVariable`（zhs:877）
     */
    fun checkUserVarName(
        name: String,
        userMacros: Set<String> = emptySet(),
        userGlobals: Set<String> = emptySet(),
    ): String? {
        if (hasBadSymbol(name)) return "变量名\"$name\"包含\"_\"以外的符号"          // zhs:872
        when (nameKind(name)) {
            NameKind.RESERVED -> return "变量名\"$name\"为Emuera的保留字"           // zhs:873
            NameKind.SYSTEM_METHOD, NameKind.SYSTEM_INSTRUMENT ->
                return "变量名\"$name\"为Emuera所使用的变量或者命令名"                // zhs:874
            NameKind.SYSTEM_VARIABLE -> return "变量名\"$name\"为Emuera所使用的变量名" // zhs:875
            null -> {}
        }
        val u = name.uppercase(Locale.ROOT)
        // nameDic 一类一键：宏先于全局登记（ErhLoader 扫描期 AddMacro 早于
        // analyzeSharpDimLines 的 AddUseDefinedVariable，spec: ErhLoader.cs:246 / :268）
        if (u in userMacros) return "变量名\"$name\"为已定义的宏"                      // zhs:876
        if (u in userGlobals) return "变量名\"$name\"为用户已定义的全局变量名"           // zhs:877
        return null
    }

    /**
     * `CheckUserMacroName`（spec: IdentifierDictionary.cs:299-338）：返回官方错误文案，
     * null = 原版接受。与 [checkUserVarName] 同一张表，只是文案换成「宏名」（zhs:880-885）。
     */
    fun checkUserMacroName(
        name: String,
        userMacros: Set<String> = emptySet(),
        userGlobals: Set<String> = emptySet(),
    ): String? {
        if (hasBadSymbol(name)) return "宏名\"$name\"包含\"_\"以外的符号"            // zhs:880
        when (nameKind(name)) {
            NameKind.RESERVED -> return "宏名\"$name\"为Emuera的保留字"              // zhs:881
            NameKind.SYSTEM_METHOD, NameKind.SYSTEM_INSTRUMENT ->
                return "宏名\"$name\"为Emuera所使用的变量或者命令名"                  // zhs:882
            NameKind.SYSTEM_VARIABLE -> return "宏名\"$name\"为Emuera所使用的变量名"  // zhs:883
            null -> {}
        }
        val u = name.uppercase(Locale.ROOT)
        if (u in userMacros) return "宏名\"$name\"为已定义的宏"                        // zhs:884
        if (u in userGlobals) return "宏名\"$name\"为用户已定义的全局变量名"            // zhs:885
        return null
    }

    /** spec: LexicalAnalyzer.cs:28 identifierTerminators（与 core-vm LexicalAnalyzer 同表） */
    internal val IDENT_TERMINATORS: Set<Char> =
        " \u3000.+-*/%=!<>|&^~?#)}],:({[\$\\'\"@;\t".toSet()
}

/**
 * `#DIM`/`#DIMS` 行上出现的修饰关键字（spec: UserDefinedVariableData.cs:60-260）。
 */
enum class DimKeyword { CONST, REF, DYNAMIC, STATIC, GLOBAL, SAVEDATA, CHARADATA }

/**
 * 单条 `#DIM`/`#DIMS` 声明（**未求值**的原文形态）。
 *
 * spec: LogicalLineParser.cs:279-288 —— `#DIM`/`#DIMS` → `UserDefinedVariableData.Create`
 * → `label.AddPrivateVariable`（FunctionLabelLine.cs:98-108）。
 * 各字段与 `UserDefinedVariableData`（UserDefinedVariableData.cs:11-37）的对应关系：
 *   [name]↔Name、[isStr]↔TypeIsStr（#DIMS）、[dims]↔Lengths 的原文、
 *   [keywords]↔Const/Reference/Static=false/Global/Save/CharaData/Dynamic 的并集、
 *   [initializer]↔DefaultInt/DefaultStr 的原文。
 *
 * 下游约定：[dims] 各维保留**原文**——尺寸可以是 `#DEFINE` 宏、ERH 常量、
 * 全局 `#DIM CONST` 标识符或任意可常量折叠表达式。原版在装载期用
 * `ExpressionParser.ReduceIntegerTerm(...).Restructure(null)` 求值并要求结果为
 * `SingleLongTerm`（spec: UserDefinedVariableData.cs:325-341），erbcc 不在收集期求值，
 * 求值（宏展开 + 常量表查询）交给下游编译器。
 */
data class DimSpec(
    val name: String,
    val isStr: Boolean,
    /** 各维长度原文（顶层逗号分段后逐段 trim；REF 变量的段恒为 "0"/""，spec: UserDefinedVariableData.cs:309-319） */
    val dims: List<String>,
    val keywords: Set<DimKeyword>,
    /** `=` 之后的原文（含引号与逗号）；无初值 → null（spec: UserDefinedVariableData.cs:344-427） */
    val initializer: String?,
)

/** 单个 @标签行（尚未进入符号表） */
data class RawLabel(
    val name: String,
    val lineNo: Int,
    val isMethod: Boolean = false,   // #FUNCTION / #FUNCTIONS
    val isOnly: Boolean = false,     // #ONLY
    val isPri: Boolean = false,      // #PRI
    val isLater: Boolean = false,    // #LATER
    val isSingle: Boolean = false,   // #SINGLE
    /** 实参个数：`@F(A, B$)` → 2（含带默认值的形参；无括号 → 0）。见 ErbLoader.cs:753-754 */
    val argCount: Int = 0,
    /** 整型形参个数（ARG 槽）。判定规则见 [Symbols.countTypedArgs] KDoc。 */
    val argIntCount: Int = 0,
    /** 字符串形参个数（ARGS 槽）。判定规则见 [Symbols.countTypedArgs] KDoc。 */
    val argStrCount: Int = 0,
    /** `#LOCALSIZE n`（LogicalLineParser.cs:218-262）；未声明 → 0 */
    val localSize: Int = 0,
    /** `#LOCALSSIZE n`（LogicalLineParser.cs:263-273）；未声明 → 0 */
    val localsSize: Int = 0,
    /** 函数体内的 `#DIM`/`#DIMS` 声明（LogicalLineParser.cs:279-288）；同函数重名只收先到者 */
    val dimSpecs: List<DimSpec> = emptyList(),
)

/** spec: LabelDictionary.cs:77-105 / CalledFunction.cs:228-243 —— 事件分桶 */
enum class EventBucket(val index: Int) { ONLY(0), PRI(1), NORMAL(2), LATER(3) }

data class SymbolConfig(
    /** spec: emuera.config IGNORE CASE:YES → Config.IgnoreCase */
    val ignoreCase: Boolean = true,
    /** spec: emuera.config SORT FILENAMES:YES → Config.SortWithFilename */
    val sortFilenames: Boolean = true,
    /** spec: Program.DebugMode —— [IF_DEBUG]/[IF_NDEBUG] 分支开关 */
    val debugMode: Boolean = false,
    /**
     * ERH 的 `#DEFINE` 宏：**宏名（大写归一）→ 展开体原文**（未求值）。
     * spec: ErhLoader.cs:146-247 analyzeSharpDefine → `DefineMacro(text, wordCollection, argCount)`
     * → `IdentifierDictionary.AddMacro`（IdentifierDictionary.cs:401-406，键 = OrdinalIgnoreCase
     * 哈希 ≙ 大写归一，IGNORE CASE:YES emuera.config:1）。
     * 空体 = `#DEFINE 名` 无替换式（spec: ErhLoader.cs:167-171，`DefineMacro.IsNull`；
     * 供 `[IF 名]` 判定，[LexicalAnalyzer.cs:966-967] 展开为空 token 流）。
     * 重名宏 = **先到者胜**：`CheckUserMacroName` 对已存在的 UserMacro 返回 warnLevel=2，
     * `analyzeSharpDefine` 随即 return、**不执行 AddMacro**（spec: ErhLoader.cs:155-164 +
     * IdentifierDictionary.cs:324-327），后到者被拒。
     * 参数化宏 `#DEFINE F(A) ...`：本版本原版**禁止声明**（解析后仍
     * `throw CodeEE(CanNotDeclaredFuncMacro)`，spec: ErhLoader.cs:241-244，
     * Lang.cs:2382「関数型マクロは宣言できません」；展开侧 expandFunctionlikeMacro
     * 为死路残留）→ erbcc 不收集。
     * erbcc **不做宏求值**（D.8 纪律）：原文喂给编译器侧 `DimSizeEvaluator`/`macroConsts`。
     */
    val macros: Map<String, String> = emptyMap(),
    /** 文件名排序策略，默认冻结为 ja-JP Collator（ADR-006） */
    val collator: CollatorOption = CollatorOption.JA_JP,
    /**
     * `#LOCALSIZE`/`#LOCALSSIZE` 值求值用的常量表（大写归一名 → Long）。
     *
     * 原版这两条 `#` 的值不是「裸整数」，而是整段过 `ExpressionParser.ReduceIntegerTerm`
     * 的常量表达式（spec: LogicalLineParser.cs:224-234）——ERH `#DIM CONST` 与 `#DEFINE`
     * 宏都能当值（语料实形 `fromEN/MISC.ERB:1246` `#LOCALSIZE 人物数量上限`，
     * DIM.ERH:209）。erbcc 侧由 [SymbolTable.build] 用桥 `com.erbcc.bridge.sizeConsts`
     * 定点折出整表注入；直连 [SymbolTable.scanLabels] 的调用方不传时表为空，
     * 标识符形态回落为「不可解 → 0」（与旧口径一致）。
     */
    val constValues: Map<String, Long> = emptyMap(),
)

/**
 * 单个 `.ERH` 文件收集到的全局符号（装载期两件事，spec: ErhLoader.cs:88-127）：
 * `#DEFINE` 宏与 `#DIM`/`#DIMS` 声明（后者在原版装载尾部的 `analyzeSharpDimLines`
 * 多轮重试队列里建全局变量，ERH 里只支持 CONST 之外的普通全局；本收集只取
 * **CONST 声明原文**做尺寸常量表，其余不归 erbcc 管）。
 */
data class ErhSymbols(
    /** 宏名（大写归一）→ 展开体原文；先到者胜（见 [SymbolConfig.macros]） */
    val macros: Map<String, String>,
    /** `#DIM CONST` / `#DIMS CONST` 声明（未求值原文；[DimSpec.initializer] 恒非 null——CONST 缺初值原版 CodeEE，spec: UserDefinedVariableData.cs:294-297, 344-350） */
    val dimConsts: List<DimSpec>,
    /** 装载期告警/降级留痕（level 1 语义）：ERH 里原版本会报错但不该被静默吞掉的行。 */
    val diag: List<LoadWarning> = emptyList(),
    /**
     * 是否出现「原版会中断整条 ERH 链」的装载期错误（CMP-42）。
     *
     * spec: ErhLoader.cs:57-61 —— `loadHeaderFile` 任一行报错即 `return false`，
     * `LoadHeaderFiles` 的 `foreach` 随即 `break`：剩余 ERH 一份都不装。
     * 抛出点见 :96-99（非 # 起头）/ :101-106（# 后无指令名，level 1 但也 return false）/
     * :127（未知预处理）/ :130-137（CodeEE 兜底）/ :155-164 宏名冲突置 `noError=false`。
     * `#DIM`/`#DIMS` 行的 CodeEE 不计入：它们排在全部文件读完之后由
     * `analyzeSharpDimLines` 逐行 try/catch（spec: ErhLoader.cs:249-340，:328-332
     * 只把 `result` 置 false），不影响已装载/未装载的文件集。
     */
    val chainAborted: Boolean = false,
) {
    companion object {
        val EMPTY = ErhSymbols(emptyMap(), emptyList())
    }
}

/** 一条标签定义。Index 语义对齐 FunctionLabelLine.Index / FileIndex（FileIndex 1-based）。 */
data class LabelDef(
    val name: String,
    val key: String,
    val file: String,
    val lineNo: Int,
    val fileIndex: Int,
    val defIndex: Int,
    val isEvent: Boolean,
    val isSystem: Boolean,
    val isMethod: Boolean,
    val isOnly: Boolean,
    val isPri: Boolean,
    val isLater: Boolean,
    val isSingle: Boolean,
    val argCount: Int = 0,
    val argIntCount: Int = 0,
    val argStrCount: Int = 0,
    val localSize: Int = 0,
    val localsSize: Int = 0,
    val dimSpecs: List<DimSpec> = emptyList(),
) {
    /** spec: LabelDictionary.cs:90-105 分桶条件（ONLY 优先于 PRI/LATER，ONLY 会清掉二者） */
    val eventBucket: EventBucket
        get() = when {
            isOnly -> EventBucket.ONLY
            isPri -> EventBucket.PRI
            isLater -> EventBucket.LATER
            else -> EventBucket.NORMAL
        }

    val eventGroupIndex: Int get() = eventBucket.index
}

/** 重名组（同名全部定义 + 生效者）。事件组 winner 仅为 CALL 语义占位，实际全部执行。 */
data class DuplicateGroup(val key: String, val defs: List<LabelDef>) {
    /** spec: LabelDictionary.cs:60-68 直接赋值 → 最后装载者 */
    val winner: LabelDef get() = defs.last()
    /** 仅作对照：SortLabels 的死代码路径会选这一个（先装载者） */
    val firstLoaded: LabelDef get() = defs.first()
    val isEvent: Boolean get() = defs.first().isEvent
}

data class SymbolStats(
    val totalDefs: Int,
    val uniqueNames: Int,
    val duplicateNames: Int,
    val nonEventDuplicates: Int,
    val eventDuplicates: Int,
    val shadowedDefs: Int,
    val methodDefs: Int,
)

/**
 * 比较器自检结果（ja-JP Collator vs 序数）。结论看 [winnerFlips]/[eventOrderFlips]，
 * 以及重名定义文件之间的**相对次序**是否翻转（[duplicateDefInversions]，0 = 相对次序不变）。
 * [shiftedFiles] 只是绝对位置位移（含被别的文件挤下去的被动位移），数量大属正常。
 */
data class CollatorCheck(
    val files: Int,
    val shiftedFiles: Int,
    val firstDivergence: String?,
    val duplicateDefFiles: List<String>,
    val duplicateDefPairs: Int,
    val duplicateDefInversions: Int,
    val duplicateGroups: Int,
    val winnerFlips: List<String>,
    val eventOrderFlips: List<String>,
) {
    val passed: Boolean
        get() = winnerFlips.isEmpty() && eventOrderFlips.isEmpty() && duplicateDefInversions == 0
}

/**
 * 文件名排序策略。
 *
 * spec: Config.cs:678-682 —— 原版 `Array.Sort(files)` 无比较器 → `Comparer<string>.Default`
 * → `string.CompareTo`，即 **当前 culture 的字符串比较**（不是序数比较）。
 *
 * 本实现提供两种近似：
 *  - [JA_JP]（默认，冻结口径，见 ADR-006）：`java.text.Collator.getInstance(Locale.JAPANESE)`，
 *    strength=TERTIARY（等价于 .NET 的默认 culture 比较对大小写/重音的次级权重）。
 *  - [ORDINAL]：UTF-16 码元序，仅作对照/回归基线（历史实现用的就是这个）。
 *
 * **已知偏差**：.NET 的 `CompareInfo.Compare`（NLS/ICU）与 `java.text.Collator` 的权重表
 * 并非逐字节等价，两者在极端的文件名对（如全角/半角混排、长共同前缀后接异体字）上可能给出
 * 不同次序；同一次构建内二者都是**全序且确定**的，因此只影响"跨文件重名的胜者"这一类结论。
 * 已用两比较器对全量语料各跑一遍（`erbcc symbols --collator-check`）：6 组重名、30 条定义的
 * 胜者与事件执行序 **零翻转**。
 */
enum class CollatorOption { JA_JP, ORDINAL }

/**
 * 文件名比较器。注意：只在**同一目录**内比较文件名即可等价于比较完整路径——
 * 同层文件的父路径前缀相同，首个差异必然落在文件名内（原版比较的是完整路径字符串）。
 * 跨目录的相对次序由 getFiles 的递归结构决定（目录也在同层内排序），同样只用到文件名比较。
 */
internal fun nameComparator(option: CollatorOption): Comparator<String> = when (option) {
    CollatorOption.ORDINAL -> Comparator { a, b -> a.compareTo(b) }
    CollatorOption.JA_JP -> {
        val collator = java.text.Collator.getInstance(Locale.JAPANESE)
        collator.strength = java.text.Collator.TERTIARY
        Comparator { a, b -> collator.compare(a, b) }
    }
}

/**
 * 符号表。构建顺序即装载顺序：addFile 的 fileIndex 必须递增，
 * 文件内 labels 必须按行序给出（调用方负责，scanLabels 已保证）。
 */
class SymbolTable(val config: SymbolConfig) {

    private val byKey = LinkedHashMap<String, MutableList<LabelDef>>()
    private var defIndex = 0
    private var fileIndex = 0
    private var methodDefs = 0
    private var frozen = false

    /**
     * 全语料 ERH `#DIM CONST` 声明原文（未求值，大写归一交由尺寸求值方处理）。
     * 由 [SymbolTable.build] 经 [loadErhSymbols] 填充；编译器桥用
     * `com.erbcc.bridge.sizeConsts(config.macros, erhDimConsts)` 求值成常量表。
     * spec: ErhLoader.cs:121-125（#DIM/#DIMS 入队）+ 249-340（analyzeSharpDimLines 多轮建全局变量，
     * CONST 值经 UserDefinedVariableData.cs:374-422 静态求值）。
     */
    val erhDimConsts: MutableList<DimSpec> = ArrayList()

    fun keyOf(name: String): String = if (config.ignoreCase) name.uppercase(Locale.ROOT) else name

    /** 追加一个文件的标签。spec: LabelDictionary.AddLabel（LabelDictionary.cs:318-337，按装载序 append） */
    fun addFile(relPath: String, labels: List<RawLabel>) {
        require(!frozen) { "SymbolTable 已冻结，不能再追加文件" }
        fileIndex++
        for (l in labels) {
            val key = keyOf(l.name)
            defIndex++
            if (l.isMethod) methodDefs++
            byKey.getOrPut(key) { ArrayList() }.add(
                LabelDef(
                    name = l.name,
                    key = key,
                    file = relPath,
                    lineNo = l.lineNo,
                    fileIndex = fileIndex,
                    defIndex = defIndex,
                    isEvent = Symbols.isEventLabelName(key),
                    isSystem = Symbols.isSystemLabelName(key),
                    isMethod = l.isMethod,
                    isOnly = l.isOnly,
                    isPri = l.isPri,
                    isLater = l.isLater,
                    isSingle = l.isSingle,
                    argCount = l.argCount,
                    argIntCount = l.argIntCount,
                    argStrCount = l.argStrCount,
                    localSize = l.localSize,
                    localsSize = l.localsSize,
                    dimSpecs = l.dimSpecs,
                )
            )
        }
    }

    fun freeze() { frozen = true }

    /** 全部标签键（已按 IgnoreCase 归一），供"与系统语句重名"等集合运算使用。 */
    val nameKeys: Set<String> get() = byKey.keys

    /** 同名全部定义，按装载序（= defIndex 升序）。 */
    fun defsOf(name: String): List<LabelDef> = byKey[keyOf(name)] ?: emptyList()

    /**
     * 非事件函数/CALL 目标的生效定义。
     * spec: LabelDictionary.cs:60-68 `noneventLabelDic[labelName] = label` → 最后装载者。
     * 事件函数在 CompatiCallEvent=false 时不可 CALL（CalledFunction.cs:100-103），
     * 此处返回最后一条仅供诊断；调用方须先查 isEvent，按 eventBuckets 展开。
     */
    fun resolve(name: String): LabelDef? = defsOf(name).lastOrNull()

    /** 事件函数的 4 桶（每桶内按装载序）。spec: CalledFunction.cs:228-243 桶序 0→1→2→3 */
    fun eventBuckets(name: String): List<List<LabelDef>>? {
        val defs = defsOf(name)
        if (defs.isEmpty() || !defs.first().isEvent) return null
        val buckets = List(4) { ArrayList<LabelDef>() }
        for (d in defs) buckets[d.eventGroupIndex].add(d)
        return buckets
    }

    /** 事件函数的实际执行序（含桶序）。spec: ProcessState.cs:326-333 + CalledFunction.cs:228-243 */
    fun eventCallOrder(name: String): List<LabelDef> =
        eventBuckets(name)?.flatten() ?: emptyList()

    fun contains(name: String): Boolean = defsOf(name).isNotEmpty()

    fun duplicateGroups(): List<DuplicateGroup> =
        byKey.values.filter { it.size > 1 }.map { DuplicateGroup(it.first().key, it) }

    /**
     * 装载期「同名非事件函数重复定义」告警（原版 level 1，不中断装载）。
     *
     * ## 触发点与判据
     *
     * spec: ErbLoader.cs:441-451 —— 每注册一条 `@标签`（`labelDic.AddLabel`，:440）之后：
     * `if (!label.IsEvent && (Config.WarnNormalFunctionOverloading || Program.AnalysisMode))`
     * 就取 `GetSameNameLabel`（LabelDictionary.cs:39-56），非 null 则
     * `Warn(…, level 1)`（:448）。三条容易抄错的细节：
     *
     * 1. **参照的是「首条定义」而不是「上一条」**：`GetSameNameLabel` 返回
     *    `labelAtDic[name][0]`（LabelDictionary.cs:54），而 `AddLabel` 是 append
     *    （:318-337）——所以第 3 次定义报的仍是第 1 次定义的文件行
     *    （PC 基准 `.workbuddy/golden-pc-loadwarnings.txt:2-3` 两行都指向 48 行即此）。
     * 2. **事件函数不告警**（:443 的 `!IsEvent`）：同名事件全部进桶、按
     *    ONLY→PRI→普通→LATER 执行（CalledFunction.cs:228-243），不是覆盖关系。
     * 3. **文案**（Lang.cs:2434，zhs 表 `Error.FuncIsAlreadyDefined`，emuera-zhs.xml:563）：
     *    `函数"@{0}"已经被定义过({1}的{2}行)`——0 = 标签名（**不带** `@`）、
     *    1 = 首定义的文件、2 = 首定义的行号（1 基）。
     *
     * ## 顺序
     *
     * 按各「被遮蔽定义」的装载序（[LabelDef.defIndex] 升序）输出，与原版逐文件装载时
     * 逐条打印的先后一致（不是按名字分组）。`golden-pc-loadwarnings.txt:1-3` 即
     * 43 → 52 → 56。
     *
     * ## 未建模（TODO(verify)）
     *
     * - 原版 `GetSameNameLabel` 对 `point.IsError` 返回 null（LabelDictionary.cs:45-48）；
     *   本仓 [scanLabels] 不产出错误标签（空名 `@` 行直接不登记），故无对应位。
     * - `Config.WarnNormalFunctionOverloading` 的配置门（ConfigData.cs:94 默认 **false**，
     *   `t: false`）：本仓的批量编译管线对应原版 `Program.AnalysisMode`
     *   （Program.cs:87-91，有位置参数即置位），该模式下门恒开，故不另设开关。
     *   若将来要按 emuera.config 复刻普通运行期的静默，须在这里加一个参数并接线。
     */
    fun duplicateFunctionWarnings(): List<LoadWarning> {
        val hits = ArrayList<Pair<Int, LoadWarning>>()
        for (group in byKey.values) {
            if (group.size < 2) continue
            val first = group.first()
            for (d in group.drop(1)) {
                if (d.isEvent) continue
                hits.add(
                    d.defIndex to LoadWarning(
                        d.file,
                        d.lineNo,
                        "函数\"@${d.name}\"已经被定义过(${first.file}的${first.lineNo}行)",
                    ),
                )
            }
        }
        hits.sortBy { it.first }
        return hits.map { it.second }
    }

    fun stats(): SymbolStats {
        val dups = duplicateGroups()
        return SymbolStats(
            totalDefs = defIndex,
            uniqueNames = byKey.size,
            duplicateNames = dups.size,
            nonEventDuplicates = dups.count { !it.isEvent },
            eventDuplicates = dups.count { it.isEvent },
            shadowedDefs = defIndex - byKey.size,
            methodDefs = methodDefs,
        )
    }

    companion object {

        /** spec: ErbLoader.cs:210-264 + Config.cs:654-706 */
        fun loadOrder(corpus: Path, config: SymbolConfig = SymbolConfig()): List<Path> {
            val cmp = nameComparator(config.collator)
            val erb = getFiles(corpus, ".erb", config.sortFilenames, cmp)
            // 名字含 '#' 的目录先装载（ErbLoader.cs:214-238）；源码未排序，本语料为空
            val hashDirs = ArrayList<Path>()
            Files.walk(corpus).use { s ->
                s.filter { Files.isDirectory(it) && it != corpus && it.fileName.toString().contains('#') }
                    .forEach { hashDirs.add(it) }
            }
            if (hashDirs.isEmpty()) return erb
            val first = ArrayList<Path>()
            for (d in hashDirs) first.addAll(getFiles(d, ".erb", config.sortFilenames, cmp))
            val firstSet = first.map { it.toAbsolutePath().normalize() }.toSet()
            return first + erb.filter { it.toAbsolutePath().normalize() !in firstSet }
        }

        /** spec: ErhLoader.cs:40-75 —— ERH 独立扫描，逐个 #DEFINE 进宏表 */
        fun erhOrder(corpus: Path, config: SymbolConfig = SymbolConfig()): List<Path> =
            getFiles(corpus, ".erh", config.sortFilenames, nameComparator(config.collator))

        /**
         * spec: Config.cs:654-706 getFiles
         *   同层文件（排序）→ 同层子目录（排序）→ 递归。`toponly` 由 SearchSubdirectory 决定，
         *   本游戏为 YES，故全递归。
         *   排序键 = 文件名，比较器由 [SymbolConfig.collator] 决定（原版排的是完整路径，
         *   同层前缀相同故等价）。
         */
        private fun getFiles(
            dir: Path,
            suffix: String,
            sort: Boolean,
            cmp: Comparator<String> = nameComparator(CollatorOption.JA_JP),
        ): List<Path> {
            if (!Files.isDirectory(dir)) return emptyList()
            val entries = Files.list(dir).use { it.toList() }
            val fileCmp = Comparator<Path> { a, b -> cmp.compare(a.fileName.toString(), b.fileName.toString()) }
            val files = entries.filter { Files.isRegularFile(it) && it.name.lowercase(Locale.ROOT).endsWith(suffix) }
                .let { if (sort) it.sortedWith(fileCmp) else it }
            val dirs = entries.filter { Files.isDirectory(it) }
                .let { if (sort) it.sortedWith(fileCmp) else it }
            val out = ArrayList<Path>()
            out.addAll(files)
            for (d in dirs) out.addAll(getFiles(d, suffix, sort, cmp))
            return out
        }

        /** 构建全量符号表。 */
        fun build(corpus: Path, config: SymbolConfig = SymbolConfig()): SymbolTable {
            val erh = loadErhSymbols(corpus, config)
            val merged = LinkedHashMap<String, String>(config.macros)
            for ((k, v) in erh.macros) merged.putIfAbsent(k, v)   // 先到者胜（ErhLoader.cs:155-164）
            // `#LOCALSIZE`/`#LOCALSSIZE` 值求值表：宏体与 ERH #DIM CONST 原文经桥侧
            // sizeConsts 定点折出（求值口径同 D.8，spec: ErhLoader.cs:249-340 重试队列）。
            val effective = config.copy(
                macros = merged,
                constValues = com.erbcc.bridge.sizeConsts(merged, erh.dimConsts),
            )
            val table = SymbolTable(effective)
            table.erhDimConsts.addAll(erh.dimConsts)
            val root = corpus.toAbsolutePath().normalize()
            for (p in loadOrder(corpus, effective)) {
                val rel = root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/')
                table.addFile(rel, scanLabels(decode(p), effective, rel))
            }
            table.freeze()
            return table
        }

        /**
         * 按 [erhOrder]（= 原版 ErhLoader.LoadHeaderFiles 的 Config.GetFiles 装载序，
         * spec: ErhLoader.cs:40-75）收集全部 ERH 的宏（名→展开体原文，先到者胜）与
         * `#DIM CONST` 声明原文。
         */
        fun loadErhSymbols(corpus: Path, config: SymbolConfig = SymbolConfig()): ErhSymbols {
            val macros = LinkedHashMap<String, String>()
            val dimConsts = LinkedHashMap<String, DimSpec>()
            val diag = ArrayList<LoadWarning>()
            var chainAborted = false
            for (erh in erhOrder(corpus, config)) {
                val absFile = erh.toAbsolutePath().normalize().toString().replace('\\', '/')
                val got = collectErhSymbols(
                    decode(erh),
                    absFile,
                    // 只在本次调用期间读取的视图（装载方随后才合并，勿改）
                    priorMacroKeys = macros.keys,
                    priorDimConstKeys = dimConsts.keys,
                    stopAtFirstChainError = true,
                )
                for ((k, v) in got.macros) macros.putIfAbsent(k, v)
                for (s in got.dimConsts) dimConsts.putIfAbsent(s.name.uppercase(Locale.ROOT), s)
                diag.addAll(got.diag)
                if (got.chainAborted) {
                    // spec: ErhLoader.cs:57-61 —— 本文件报错 ⇒ 剩余 ERH 一份都不装
                    chainAborted = true
                    break
                }
            }
            return ErhSymbols(macros, dimConsts.values.toList(), diag, chainAborted)
        }

        /**
         * 比较器自检：同一语料分别用 ja-JP Collator 与序数比较构建符号表，
         * 报告「装载序位置不同的文件」与「重名组胜者/事件执行序翻转的组」。
         *
         * spec 依据：原版排序是 culture 敏感（Config.cs:681），但 .NET CompareInfo 与
         * java.text.Collator 不等价（见 [CollatorOption] 的 KDoc）。本自检的作用是证明
         * **在本语料上二者结论一致**，把「近似」限定为可控风险。
         */
        fun collatorCheck(corpus: Path, config: SymbolConfig = SymbolConfig()): CollatorCheck {
            val a = build(corpus, config.copy(collator = CollatorOption.JA_JP))
            val b = build(corpus, config.copy(collator = CollatorOption.ORDINAL))

            fun positions(cfg: SymbolConfig): Map<String, Int> =
                loadOrder(corpus, cfg).mapIndexed { i, p ->
                    p.toAbsolutePath().normalize().toString() to i
                }.toMap()
            val orderA = loadOrder(corpus, config.copy(collator = CollatorOption.JA_JP))
            val orderB = loadOrder(corpus, config.copy(collator = CollatorOption.ORDINAL))
            val posA = orderA.mapIndexed { i, p -> p.toAbsolutePath().normalize().toString() to i }.toMap()
            val posB = orderB.mapIndexed { i, p -> p.toAbsolutePath().normalize().toString() to i }.toMap()
            val shifted = posA.count { (k, v) -> posB[k] != null && posB[k] != v }
            var firstDivergence: String? = null
            for (i in orderA.indices) {
                val a = orderA[i].toAbsolutePath().normalize().toString()
                val b = if (i < orderB.size) orderB[i].toAbsolutePath().normalize().toString() else ""
                if (a != b) { firstDivergence = a; break }
            }

            val keys = (a.duplicateGroups().map { it.key } + b.duplicateGroups().map { it.key }).distinct().sorted()
            val winnerFlips = ArrayList<String>()
            val orderFlips = ArrayList<String>()
            val dupDefFiles = LinkedHashSet<String>()
            for (k in keys) {
                val da = a.defsOf(k)
                val db = b.defsOf(k)
                fun sig(d: List<LabelDef>) = d.joinToString("|") { "${it.file}:${it.lineNo}" }
                if (sig(da) != sig(db)) winnerFlips.add(k)          // 定义集合或次序不同
                val oa = a.eventCallOrder(k).map { "${it.file}:${it.lineNo}" }
                val ob = b.eventCallOrder(k).map { "${it.file}:${it.lineNo}" }
                if (oa != ob) orderFlips.add(k)
                for (d in da + db) dupDefFiles.add(d.file)
            }
            // 重名定义文件之间的**相对次序**是否翻转（0 = 与序数比较器结论一致）
            val root = corpus.toAbsolutePath().normalize()
            val idxA = dupDefFiles.associateWith { posA[root.resolve(it).normalize().toString()] ?: -1 }
            val idxB = dupDefFiles.associateWith { posB[root.resolve(it).normalize().toString()] ?: -1 }
            val list = dupDefFiles.toList()
            var inversions = 0
            var pairs = 0
            for (i in list.indices) for (j in i + 1 until list.size) {
                val x = list[i]
                val y = list[j]
                pairs++
                if ((idxA[x]!! < idxA[y]!!) != (idxB[x]!! < idxB[y]!!)) inversions++
            }
            return CollatorCheck(
                files = posA.size,
                shiftedFiles = shifted,
                firstDivergence = firstDivergence,
                duplicateDefFiles = list,
                duplicateDefPairs = pairs,
                duplicateDefInversions = inversions,
                duplicateGroups = keys.size,
                winnerFlips = winnerFlips,
                eventOrderFlips = orderFlips,
            )
        }

        /**
         * 复刻 loadErb 的标签注册分支（ErbLoader.cs:387-527 + EraStreamReader.cs:96-187），
         * 只产出**会真正 AddLabel** 的标签：
         *   · [SKIPSTART]..[SKIPEND] / 未命中的 [IF 宏] / 禁用的 [IF_DEBUG] 段内 → 不产出
         *   · ';' 注释、{} 续行块内 → 不产出
         *
         * 预处理判定委托给 [ErbPreprocessor]（erbcc 内唯一一份，与预扫/切片同源）。
         *
         * @param file 告警定位用的文件名
         * @param warnings 装载期告警收集处（null = 丢弃；调用方应传自己的表，否则
         *   「未闭合的 [SKIPSTART]」一类静默）
         */
        fun scanLabels(
            text: String,
            config: SymbolConfig = SymbolConfig(),
            file: String = "<erb>",
            warnings: MutableList<LoadWarning>? = null,
        ): List<RawLabel> {
            val out = ArrayList<RawLabel>()
            var curPos = -1                             // out 中当前标签的下标
            var cont = false
            val pp = ErbPreprocessor(config.debugMode, config.macros.keys, file)
            // D.6.1：同函数内 #DIM 重名（OrdinalIgnoreCase，spec: FunctionLabelLine.cs:17 privateVar
            // 用 Config.StrComper 构建）先到者胜——AddPrivateVariable 对后到者返回 false
            // （FunctionLabelLine.cs:98-101，LogicalLineParser.cs:284-287 告警 VarNameAlreadyUsed 丢弃）。
            val seenDims = HashSet<String>()

            for ((i, rawLine) in text.split('\n').withIndex()) {
                val lineNo = i + 1
                val rest = stripWs(rawLine.removeSuffix("\r"))
                // 1) 续行块（仅 !Disabled 时生效，spec: EraStreamReader.cs:130-184）
                if (cont) {
                    if (rest.trim() == "}") cont = false
                    continue
                }
                if (!pp.disabled && rest.trim() == "{") { cont = true; continue }
                if (rest.isEmpty()) continue
                // 2) 预处理器（Disabled 时仍处理，spec: ErbLoader.cs:390-406）
                if (pp.process(rest, lineNo)) continue
                if (pp.disabled) continue
                // 3) '#' 行：只取归属当前标签的属性（spec: LogicalLineParser.cs:20-307）
                if (rest.startsWith("#")) {
                    if (curPos < 0) continue
                    val c = out[curPos]
                    val parts = rest.substring(1).trim().split(Regex("\\s+"), 2)
                    val kw = parts.firstOrNull()?.uppercase(Locale.ROOT) ?: ""
                    val arg = parts.getOrNull(1)?.trim() ?: ""
                    out[curPos] = when (kw) {
                        // spec: LogicalLineParser.cs:151-213 —— #FUNCTION/#FUNCTIONS 三类声明无效：
                        //   ① 函数名以数字开头（:153-158 CanNotDeclaredBeginNumberFunction，告警 level 1
                        //      且 IsError=true，IsMethod 不置位）；
                        //   ② 事件/系统标签（:178-182 label.Depth==0 → UseSharpInSystemFunc 告警 level 2；
                        //      Depth==0 由 ParseLabelLine.cs:352-362 对事件/系统标签置位）；
                        //   ③ 同一标签行上重复声明（:161-176 AlreadySharpDeclared 后 return false，
                        //      先到者胜——同型重复是幂等 no-op；交叉型 #FUNCTION/#FUNCTIONS 原版保留
                        //      先到 MethodType，RawLabel 不含返回类型（返回类型表在 CorpusScan.funcTypes），
                        //      故此处 Boolean 层面交叉重复同样是 no-op）。
                        // 接受声明时（:183-212）除 IsMethod=true 外还按 :193-212 作废此前 #PRI/#LATER/
                        // #SINGLE/#ONLY 标记（UsePri/Later/Single/OnlyUserFunc 告警）。
                        "FUNCTION", "FUNCTIONS" ->
                            if (c.name[0].isDigit() ||
                                Symbols.isEventLabelName(c.name) || Symbols.isSystemLabelName(c.name)
                            ) {
                                c
                            } else {
                                c.copy(
                                    isMethod = true,
                                    isPri = false, isLater = false, isSingle = false, isOnly = false,
                                )
                            }
                        // spec: LogicalLineParser.cs:32-147 —— #PRI/#LATER/#SINGLE/#ONLY 仅对事件
                        // 标签（label.IsEvent，ParseLabelLine.cs:352-356）生效，且 #ONLY 接受时作废
                        // PRI/LATER/SINGLE（:131-145）。本处不做事件标签守卫：下游 eventBuckets
                        // （Symbols.kt eventBuckets：!isEvent → null）只对事件标签读这些位，
                        // 非事件标签上的残留标记行为不可见（语料核验 MOD-NONEVENT=0）。
                        "ONLY" -> c.copy(isOnly = true, isPri = false, isLater = false, isSingle = false)
                        "PRI" -> c.copy(isPri = true)
                        "LATER" -> c.copy(isLater = true)
                        "SINGLE" -> c.copy(isSingle = true)
                        // spec: LogicalLineParser.cs:218-262 —— #LOCALSIZE 写 LocalLength、#LOCALSSIZE 写 LocalsLength；
                        // 值是常量表达式（宏/ERH 常量均可，:224-234 ReduceIntegerTerm→SingleLongTerm），
                        // <=0（:236-239）或 >= int.MaxValue（:241-244）告警后不生效；事件函数整个
                        // 分支被跳过（:226-233 EventFuncIgnoreSpecified）。不可解 → 保持未声明（0）。
                        "LOCALSIZE" -> {
                            val v = evalSharpInt(arg, config)
                            if (v != null && !Symbols.isEventLabelName(c.name)) c.copy(localSize = v) else c
                        }
                        "LOCALSSIZE" -> {
                            val v = evalSharpInt(arg, config)
                            if (v != null && !Symbols.isEventLabelName(c.name)) c.copy(localsSize = v) else c
                        }
                        // spec: LogicalLineParser.cs:279-288 —— #DIM/#DIMS → UserDefinedVariableData.Create
                        //（UserDefinedVariableData.cs:44-477，isPrivate=true）→ label.AddPrivateVariable。
                        // ERB 内无 GLOBAL/SAVEDATA/CHARADATA（UserDefinedVariableData.cs:171-175,
                        // 200-205, 229-233 CanNotUseKeywordLocalVar），关键字冲突/重复/缺名为
                        // CodeEE → 原版该行无效、变量不注册（warnLevel=2），此处不收集（返回 null）。
                        "DIM", "DIMS" -> {
                            // CMP-45：字面尺寸越界 / REF 非 0 在原版是 CodeEE（该行无效、变量不注册，
                            // spec: UserDefinedVariableData.cs:329-340），这里沿用「不收集」并把
                            // 官方文案写进 warnings 槽（收集行为与旧值逐字节一致）。
                            // CORE-06：**局部** #DIM 的名字拒绝（CheckUserPrivateVarName，
                            // IdentifierDictionary.cs:340-392）暂不接线 —— 走
                            // BuiltinSymbols.variable 会把语料里 `#DIM LINE`（SEX_TATTOO.ERB:52 等
                            // 10 余处）判成 SystemVariable，而原版 VariableData 并没有 LINE
                            // （reference 全库 grep `"LINE"` 0 命中）⇒ 该内建表条目本身待核，
                            // 见交付回执「需组长接线」。误拒会直接改语料读数。
                            var rejectMsg: String? = null
                            val spec = parseDimSpec(kw, arg, isPrivate = true) { rejectMsg = it }
                            if (spec != null && seenDims.add(spec.name.uppercase(Locale.ROOT))) {
                                c.copy(dimSpecs = c.dimSpecs + spec)
                            } else {
                                rejectMsg?.let { warnings?.add(LoadWarning(c.name, lineNo, it)) }
                                c
                            }
                        }
                        else -> c
                    }
                    continue
                }
                // 4) '@' / '$' 行（spec: ErbLoader.cs:425-474、LogicalLineParser.cs:314-383）
                if (rest[0] == '@') {
                    var j = 1
                    while (j < rest.length && rest[j] !in Symbols.IDENT_TERMINATORS) j++
                    val name = rest.substring(1, j)
                    if (name.isEmpty()) {
                        curPos = -1
                    } else {
                        // spec: ErbLoader.cs:600-760 —— 形参个数 = 括号内顶层逗号分隔的段数（含带默认值者）
                        val (argCount, argInt, argStr) =
                            if (j < rest.length && rest[j] == '(') countTypedArgs(rest, j) else Triple(0, 0, 0)
                        out.add(RawLabel(name = name, lineNo = lineNo, argCount = argCount, argIntCount = argInt, argStrCount = argStr))
                        curPos = out.size - 1
                        seenDims.clear()   // 新函数体：#DIM 重名集合重置（spec: FunctionLabelLine.cs:98 每标签独立 privateVar）
                    }
                    continue
                }
                // '$' 行与普通行都会让 '# 指令' 失去作用对象：原版只认紧跟在
                // FunctionLabelLine 之后的 '#'（ErbLoader.cs:415-424 logicalLine2 必须仍是该标签行）
                curPos = -1
            }
            // 未闭合区间（spec: ErbLoader.cs:513 + PPState.FileEnd :169-180）
            pp.fileEnd(0)
            warnings?.addAll(pp.warnings)
            return out
        }

        /** spec: ErhLoader.cs:119-124 analyzeSharpDefine —— 兼容入口：只要宏名集合 */
        fun collectMacros(text: String): Set<String> = collectErhSymbols(text).macros.keys.toSet()

        /**
         * 收集单个 ERH 的 `#DEFINE` 宏（名大写归一 → **展开体原文**，先到者胜）与
         * `#DIM CONST`/`#DIMS CONST` 声明（原文，未求值）。
         *
         * 口径（逐条证据）：
         *  - 逻辑行 = [erhLogicalLines]（含 `{...}` 续行块合并，spec: EraStreamReader.cs:136-184，
         *    连接串 = Config.ReplaceContinuationBR 去引号 —— 本游戏 emuera.config:72 `" "`，
         *    Config.cs:386 + ConfigData.cs:119）。
         *  - `#DEFINE 名 内容`：名 = 首个标识符（[LexicalAnalyzer.ReadSingleIdentifier]，
         *    spec: ErhLoader.cs:148）；名后**紧邻** `(` 判为参数化宏 → 原版解析完仍
         *    throw CodeEE「関数型マクロは宣言できません」（spec: ErhLoader.cs:165, 241-244，
         *    Lang.cs:2382）→ 不收集（本语料 0 处）。
         *    内容 = 名之后的剩余原文，剥顶层 `;` 注释（体的 token 流由 Analyse 产出：
         *    `;` 终止、`;!;` 继续、`;^;` 也终止，spec: LexicalAnalyzer.cs:919-929；
         *    与行首 SkipWhiteSpace 的 `;^;` 继续口径不同，LexicalAnalyzer.cs:668-671）。
         *  - `#DEFINE 名`（无内容）：原版存空替换式（spec: ErhLoader.cs:167-171）→ 收集，体 = ""。
         *  - 重名宏：`CheckUserMacroName` UserMacro 冲突 warnLevel=2 → analyzeSharpDefine
         *    直接 return 不 AddMacro（spec: ErhLoader.cs:155-164 + IdentifierDictionary.cs:324-327,
         *    401-406）→ **先到者胜**（与「非事件标签后到者胜」相反，勿混）。
         *  - `#DIM[S] CONST`：走 [parseDimSpec]（isPrivate=false）；CONST 必须带初值
         *    （spec: UserDefinedVariableData.cs:294-297, 344-350 ConstHasNotInitialValue），
         *    初值原文保留（多维元素逗号分隔/十六进制/函数式均原样存，求值方 DimSizeEvaluator
         *    判可否）。原版装载尾部多轮重试建全局（spec: ErhLoader.cs:249-340，
         *    IdentifierNotFoundCodeEE → 重新入队直到无进展）——前向引用靠迭代解决，
         *    桥侧 [com.erbcc.bridge.sizeConsts] 以定点迭代等价复刻。
         *  erbcc 不做宏求值（D.8 纪律）。
         *
         * ## 链中断（CMP-42，spec: ErhLoader.cs:57-61 + :90-93）
         *
         * 原版 `loadHeaderFile` 任一行报错即返回 false，`LoadHeaderFiles` 的 `foreach`
         * 随即 `break`：**剩余 ERH 一份都不装**。本函数两种模式：
         *  - [stopAtFirstChainError] = false（默认，诊断口径）：整份文件逐处留痕后继续扫
         *    （[collectMacros] 与既有诊断用例依赖这一点）。
         *  - true（[loadErhSymbols] 的装载口径）：第一条链级错误后不再处理本文件剩余行，
         *    并把 [ErhSymbols.chainAborted] 置真，由装载方停止后续文件。
         * `#DIM`/`#DIMS` 行不算链级错误：原版把它们排进 dimlines 队列、等全部文件读完
         * 才在 `analyzeSharpDimLines` 里逐行 try/catch（spec: ErhLoader.cs:249-340，
         * :328-332 只把 `result` 置 false），不改变「哪些文件被装载」。
         *
         * ## 跨文件重名（CMP-42）
         *
         * 原版宏名判定用同一个 idDic（spec: ErhLoader.cs:155-164），**跨文件同样命中**；
         * 原先两条合并路径是静默 `putIfAbsent`，现在把「先前装载的 ERH 已定义」作为
         * [priorMacroKeys] / [priorDimConstKeys] 传进来，在**出现的那一行**留痕，不再静默弃后到者。
         *
         * ## 保留名（CORE-06）
         *
         * 宏名撞 nameDic（保留字 / 系统变量 / 系统方法 / 系统指令 / 已定义宏）走
         * [Symbols.checkUserMacroName]，变量名走 [Symbols.checkUserVarName]
         * （spec: IdentifierDictionary.cs:258-338）；两者文案都取 zhs 表原文。
         *
         * @param priorMacroKeys 先前装载的 ERH 已定义的宏名（大写归一）
         * @param priorDimConstKeys 先前装载的 ERH 已声明的 `#DIM CONST` 名（大写归一）
         */
        fun collectErhSymbols(
            text: String,
            file: String = "<erh>",
            priorMacroKeys: Set<String> = emptySet(),
            priorDimConstKeys: Set<String> = emptySet(),
            stopAtFirstChainError: Boolean = false,
        ): ErhSymbols {
            val macros = LinkedHashMap<String, String>()
            val dimConsts = LinkedHashMap<String, DimSpec>()
            val diag = ArrayList<LoadWarning>()
            var chainAborted = false

            /** 链级错误：原版会让 `loadHeaderFile` 返回 false ⇒ 剩余 ERH 一份都不装。 */
            fun fatal(lineNo: Int, message: String) {
                diag.add(LoadWarning(file, lineNo, message))
                chainAborted = true
            }
            for ((lineNo, logical) in erhLogicalLinesWithLines(text)) {
                // spec: ErhLoader.cs:90-93 —— `noError` 已倒：本文件剩余行不再处理
                if (stopAtFirstChainError && chainAborted) break
                if (!logical.startsWith("#")) {
                    // 原版 ERH 只收 `#` 起头行：其余 `CodeEE(NotStartedSharpLineInHeader)`
                    // → Warn(level 2) → loadHeaderFile 返回 false → LoadHeaderFiles **中断整条
                    // ERH 链**（spec: ErhLoader.cs:96-99 + :57-61 + :130-137）。不静默跳过：
                    // 按 [fatal] 记为链级错误（装载口径下后续 ERH 一份都不装）。
                    fatal(lineNo, "ERH 非 # 起头行（原版 level 2 报错并中止 ERH 装载）: ${logical.take(80)}")
                    continue
                }
                val parts = logical.substring(1).trim().split(Regex("\\s+"), 2)
                val kw = parts.firstOrNull()?.uppercase(Locale.ROOT) ?: ""
                if (kw.isEmpty()) {
                    // spec: ErhLoader.cs:101-106 —— 空标识符 → Warn(CanNotInterpretSharpLine,1) → 中止
                    fatal(lineNo, "ERH 的 # 后无指令名（原版告警并中止 ERH 装载）")
                    continue
                }
                when (kw) {
                    "DEFINE" -> {
                        val rest = parts.getOrNull(1)?.trim()
                        if (rest.isNullOrEmpty()) {
                            // spec: ErhLoader.cs:148-152 —— ReadSingleIdentifier 为空 → CodeEE
                            // MissingReplacementSource（level 2 → 中止）
                            fatal(lineNo, "#DEFINE 缺宏名（原版 CodeEE: 置換元がありません）")
                            continue
                        }
                        var j = 0
                        while (j < rest.length && rest[j] !in Symbols.IDENT_TERMINATORS) j++
                        val name = rest.substring(0, j)
                        if (name.isEmpty()) {
                            fatal(lineNo, "#DEFINE 缺宏名（原版 CodeEE: 置換元がありません）")
                            continue
                        }
                        val key = name.uppercase(Locale.ROOT)
                        // CORE-06：CheckUserMacroName（spec: ErhLoader.cs:155-164 +
                        // IdentifierDictionary.cs:299-338）—— 保留字 / 系统变量 / 系统方法 /
                        // 系统指令 / 已定义宏（**含先前装载的 ERH**，同一个 idDic）一律
                        // warnLevel 2 → analyzeSharpDefine 直接 return（不 AddMacro）→
                        // noError=false → 本文件剩余行不再处理、整条 ERH 链中断。先到者胜。
                        // 注意这里**不**传全局变量表：原版所有 #DIM 都排在全部 ERH 扫完之后
                        // 才登记（ErhLoader.cs:249-268），宏定义时刻 nameDic 里没有用户全局。
                        val dup = key in macros || key in priorMacroKeys
                        val nameReject = Symbols.checkUserMacroName(name, macros.keys + priorMacroKeys)
                        if (nameReject != null) {
                            fatal(lineNo, if (dup) "$nameReject（先到者胜，本条被弃）" else nameReject)
                            continue
                        }
                        if (rest.startsWith("(", j)) {
                            // spec: ErhLoader.cs:165 + :241-244 —— 解析完仍 throw
                            // CodeEE(CanNotDeclaredFuncMacro)（level 2 → 中止）；不收集。
                            fatal(lineNo, "#DEFINE $name(...) 参数化宏（原版 CodeEE: 関数型マクロは宣言できません）")
                            continue
                        }
                        macros[key] = cutComment(rest.substring(j).trim())
                    }
                    "FUNCTION", "FUNCTIONS" -> {
                        // spec: ErhLoader.cs:114-119 → analyzeSharpFunction :390-393
                        // `throw new NotImplCodeEE()` → :130-137 Warn(level 2) → 中止整条 ERH 链。
                        // ERH 里的 #FUNCTION 在原版**根本不支持**，erbcc 也不收集。
                        fatal(lineNo, "ERH 里的 #$kw（原版 NotImplCodeEE → level 2 并中止 ERH 装载）")
                    }
                    "DIM", "DIMS" -> {
                        var rejectMsg: String? = null
                        val spec = parseDimSpec(kw, parts.getOrNull(1) ?: "", isPrivate = false) {
                            rejectMsg = it
                        }
                        if (spec == null) {
                            // 原版 UserDefinedVariableData.Create 抛 CodeEE → ErhLoader.cs:328-332
                            // Warn(level 2) 且 result=false（整次装载算失败）。**不中断链**：
                            // 全部 ERH 早已进 dimlines 队列，这里只逐行丢弃该声明。
                            // CMP-45 的越界/REF 非 0 有官方文案（[parseDimSpec] 给原因）。
                            diag.add(
                                LoadWarning(
                                    file, lineNo,
                                    rejectMsg ?: "$kw 声明无法解析（原版 level 2 报错，变量不注册）: ${logical.take(120)}",
                                ),
                            )
                            continue
                        }
                        // CORE-06：名字撞原版 nameDic（保留字 / 系统变量 / 系统方法 / 系统指令 /
                        // 已定义宏 / 已声明全局）→ CheckUserVarName（UserDefinedVariableData.cs:280-292
                        // + IdentifierDictionary.cs:258-297）→ CodeEE、变量不注册（level 2、不中断链）。
                        // TODO(verify)：原版此刻 nameDic 已含**全部** ERH 的宏与全部 #DIM 全局；
                        // 本实现单遍扫描只知已扫过的文件、且不收集非 CONST 的 ERH 全局 ⇒
                        // 「后到文件才 #DEFINE 的同名宏」「与非 CONST 全局重名」两类冲突会漏报
                        //（本语料两类均 0 命中）。
                        val nameReject = Symbols.checkUserVarName(
                            spec.name,
                            macros.keys + priorMacroKeys,
                            dimConsts.keys + priorDimConstKeys,
                        )
                        if (nameReject != null) {
                            diag.add(LoadWarning(file, lineNo, nameReject))
                            continue
                        }
                        if (DimKeyword.CONST in spec.keywords && spec.initializer == null) {
                            // spec: UserDefinedVariableData.cs:294-297/344-350 ConstHasNotInitialValue
                            diag.add(LoadWarning(file, lineNo, "$kw CONST ${spec.name} 缺初值（原版 CodeEE，变量不注册）"))
                            continue
                        }
                        if (DimKeyword.CONST in spec.keywords) {
                            // 同名先到者胜：后到者被上面 [Symbols.checkUserVarName] 的
                            // UserGlobalVariable 分支拒掉（spec: IdentifierDictionary.cs:381-384）。
                            dimConsts[spec.name.uppercase(Locale.ROOT)] = spec
                        }
                    }
                    else -> {
                        // spec: ErhLoader.cs:127 —— CodeEE(UnknownPreprocessorInSharpLine)
                        // → Warn(level 2) → 中止整条 ERH 链。erbcc 不收集也不静默。
                        diag.add(LoadWarning(file, lineNo, "ERH 未知指令 #$kw（原版 CodeEE: unrecognized # 预处理，level 2 并中止装载）: ${logical.take(80)}"))
                        chainAborted = true
                    }
                }
            }
            return ErhSymbols(macros, dimConsts.values.toList(), diag, chainAborted)
        }

        /**
         * ERH 逻辑行装配（只要文本，行号口径见 [erhLogicalLinesWithLines]）。
         */
        internal fun erhLogicalLines(text: String): List<String> = erhLogicalLinesWithLines(text).map { it.second }

        /**
         * ERH 逻辑行装配：逐行 [stripWs]（词法注释语义）后，空行跳过（原版
         * ReadEnabledLine 对空/纯注释行 continue，spec: EraStreamReader.cs:100-128）；
         * 独占一行的 `{` 起续行块，直到独占一行的 `}`，中间各行以单空格连接为一条逻辑行
         * （spec: EraStreamReader.cs:136-184 + emuera.config:72）。
         * 容错：块内再遇 `{` / EOF 未闭合在原版是 CodeEE（EraStreamReader.cs:167-176, 146-149），
         * 本实现跳过/截收，不中断收集。
         *
         * 返回 (1 基起始物理行号, 逻辑行)：装载期报错要定位到 `文件:行`
         * （原版 `ScriptPosition(filename, eraStreamReader.LineNo)`，spec: ErhLoader.cs:94）。
         */
        internal fun erhLogicalLinesWithLines(text: String): List<Pair<Int, String>> {
            val raw = text.split('\n')
            val out = ArrayList<Pair<Int, String>>()
            var i = 0
            while (i < raw.size) {
                val startLine = i + 1                       // 1 基物理行号（逻辑行的起始行）
                val line = stripWs(raw[i].removeSuffix("\r"))
                i++
                if (line.isEmpty()) continue
                if (line.trim() != "{") {
                    out.add(startLine to line)
                    continue
                }
                val sb = StringBuilder()
                while (i < raw.size) {
                    val c = raw[i].removeSuffix("\r")
                    i++
                    val t = c.trim()
                    if (t == "}") break
                    if (t.isEmpty() || t == "{") continue
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
                if (sb.isNotEmpty()) out.add(startLine to sb.toString())
            }
            return out
        }

        /** 剥去首个顶层 `;` 注释（字符串内不算；`;!;`/`;^;` 标记跳过继续，同 [stripWs]）。 */
        private fun cutComment(s: String): String {
            var inStr = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '"') inStr = false
                } else if (c == '"') {
                    inStr = true
                } else if (c == ';') {
                    // `#DEFINE` 展开体的 token 流由 LexicalAnalyzer.Analyse 产出（ErhLoader.cs:166），
                    // Analyse 侧只有 `;!;`（与 DebugMode 的 `;#;`）继续读、其余一律终止
                    // （spec: LexicalAnalyzer.cs:919-929）；`;^;` 只在行首 SkipWhiteSpace 侧继续
                    // （LexicalAnalyzer.cs:668-671），体中间出现即注释开始。旧实现把 `;^;`
                    // 当继续标记，会让 `#DEFINE A 1 ;^; 2` 多收 " 2"。
                    if (s.startsWith(";!;", i)) { i += 3; continue }
                    return s.substring(0, i).trimEnd()
                }
                i++
            }
            return s
        }

        /**
         * `#DIM`/`#LOCALSIZE` 值段的注释剥离（**Analyse 语义**，spec: LexicalAnalyzer.cs:919-929）：
         * 引号内的 `;` 是字符串内容（ReadString 的引号扫描，LexicalAnalyzer.cs:330）不算注释；
         * `;!;` 标记继续读后面的 token，其余 `;` 起整段废弃（`;^;` 只在行首 SkipWhiteSpace
         * 侧继续，LexicalAnalyzer.cs:668-671；`;#;` 仅 DebugMode，erbcc 按 false 处理）。
         * 旧实现 `substringBefore(';')` 对引号不设防，`#DIMS X = "a;b"` 会被截成 `"a`。
         */
        internal fun cutAnalyseComment(s: String): String {
            var inStr = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    ';' -> {
                        if (s.startsWith(";!;", i)) { i += 3; continue }
                        return s.substring(0, i)
                    }
                }
                i++
            }
            return s
        }

        /**
         * `#LOCALSIZE`/`#LOCALSSIZE` 值求值（spec: LogicalLineParser.cs:218-244）：
         * 原版把整段交给 `ExpressionParser.ReduceIntegerTerm(...).Restructure(null)` 并要求
         * `SingleLongTerm`（:224-234）——宏与 ERH `#DIM CONST` 都能当值（语料实形
         * `fromEN/MISC.ERB:1246` `#LOCALSIZE 人物数量上限`，DIM.ERH:209）。
         * 本实现：整数直读；否则按 [SymbolConfig.constValues]（= 桥 sizeConsts 定点折出的
         * 宏/常量表）交 core-vm `DimSizeEvaluator` 求表达式；不可解返回 null（= 原版
         * SharpHasNotValidValue 后不生效，LocalLength 保持 0）。
         * 上限口径与原版一致：`<=0`（LocalsizeLessThan1，:236-239）与
         * `>= int.MaxValue`（TooManyLocalsize，:241-244）都不生效。
         * `0x` 十六进制已由 DimSizeEvaluator 支持（spec: LexicalAnalyzer.cs:36-45）；
         * TODO(verify)：二进制 `0b…` 等其余 ReadInt64 进制形态，按「不可解 → 0」降级
         * （本语料 `#LOCALSIZE` 值只有整数与 CONST 标识符两种实形）。
         */
        private fun evalSharpInt(arg: String, config: SymbolConfig): Int? {
            val body = cutAnalyseComment(arg).trim()
            if (body.isEmpty()) return null
            val v = body.toLongOrNull()
                ?: com.eratw.port.core.compile.DimSizeEvaluator(config.constValues).eval(body)
                ?: return null
            return if (v in 1 until Int.MAX_VALUE.toLong()) v.toInt() else null
        }

        /**
         * 解析 `#DIM`/`#DIMS` 声明行为 [DimSpec]（未求值）。
         * 返回 null = 原版装载期 CodeEE（该行无效、变量不注册），收集方跳过。
         *
         * 逐条规则对照 `UserDefinedVariableData.Create`（spec 路径 UserDefinedVariableData.cs）：
         *  - 关键字循环（cs:54-264）：关键字与名字之间必须以空白/分隔符分隔（WordCollection
         *    的 IdentifierWord 边界），`CONSTX` 整体是名字而非 CONST。
         *  - 名字（cs:262-268）：关键字循环遇到的第一个非关键字标识符即 Name。
         *  - 尺寸（cs:300-343）：顶层 `,` 分段；REF 变量段必须是 0（cs:329-335）；
         *    每段原版经 ReduceIntegerTerm 求值且须为 SingleLongTerm（cs:325-341）——
         *    erbcc 保留原文，不求值。
         *  - 初值（cs:344-427）：`=` 之后；REF（cs:357-360）/多维（cs:361-364）/CHARADATA
         *    （cs:365-368）不能设初值。
         *  - 维度上限（cs:437-449）：CONST 一维（cs:438-441）、CHARADATA 两维（cs:442-445）、
         *    一般三维（cs:446-449）。
         *
         * TODO(verify)：`=` 之后原文未做「初值个数 vs 尺寸」「初值与类型匹配」校验
         *（cs:380-390, 410-413）——下游求值后需自行核对。
         *
         * @param onReject 可选的拒绝原因回调（CMP-45）：原版抛 CodeEE 的行，把官方文案
         *   （zhs 表原文）回传给调用方写 diag；不传则只返回 null（旧口径不变）。
         */
        internal fun parseDimSpec(
            keyword: String,
            body: String,
            isPrivate: Boolean,
            onReject: ((String) -> Unit)? = null,
        ): DimSpec? {
            val isStr = keyword.equals("DIMS", ignoreCase = true)
            var i = 0
            val n = body.length
            fun skipBlanks() {
                while (i < n && (body[i] == ' ' || body[i] == '\t' || body[i] == '\u3000')) i++
            }

            // ---- 关键字循环（cs:54-264）----
            val kws = LinkedHashSet<DimKeyword>()
            var dynamicSeen = false      // DYNAMIC/STATIC 是否已显式出现（原版 flag）
            var staticNow = true         // UserDefinedVariableData.Static 默认 true（cs:31）
            var ref = false
            val name: String
            while (true) {
                skipBlanks()
                if (i >= n || body[i] == ',' || body[i] == '=') return null  // cs:265-268 NotVarAfterKeyword
                val start = i
                while (i < n && body[i] != ' ' && body[i] != '\t' && body[i] != '\u3000' &&
                    body[i] != ',' && body[i] != '=' && body[i] != ';'
                ) i++
                val word = body.substring(start, i)
                val kw = try {
                    DimKeyword.valueOf(word.uppercase(Locale.ROOT))
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (kw == null) { name = word; break }   // cs:262-263 第一个非关键字标识符 = Name
                if (kw in kws) return null               // 重复关键字 → cs:84,113,139,161,224,257 DuplicateKeyword
                when (kw) {
                    DimKeyword.CONST -> {                // cs:60-87
                        if (DimKeyword.CHARADATA in kws || DimKeyword.GLOBAL in kws ||
                            DimKeyword.SAVEDATA in kws || ref || !staticNow
                        ) return null
                        kws.add(kw)
                    }
                    DimKeyword.REF -> {                  // cs:89-117
                        if ((dynamicSeen && staticNow) || DimKeyword.CHARADATA in kws ||
                            DimKeyword.GLOBAL in kws || DimKeyword.SAVEDATA in kws ||
                            DimKeyword.CONST in kws
                        ) return null
                        kws.add(kw)
                        ref = true
                        staticNow = false                // cs:116 隐式 DYNAMIC
                    }
                    DimKeyword.DYNAMIC -> {              // cs:119-143
                        if (!isPrivate || DimKeyword.CHARADATA in kws || DimKeyword.CONST in kws ||
                            dynamicSeen
                        ) return null
                        kws.add(kw)
                        dynamicSeen = true
                        staticNow = false
                    }
                    DimKeyword.STATIC -> {               // cs:145-169
                        if (!isPrivate || DimKeyword.CHARADATA in kws || dynamicSeen || ref) return null
                        kws.add(kw)
                        dynamicSeen = true
                        staticNow = true
                    }
                    DimKeyword.GLOBAL -> {               // cs:171-198
                        if (isPrivate || DimKeyword.CHARADATA in kws || ref ||
                            DimKeyword.CONST in kws || dynamicSeen
                        ) return null
                        kws.add(kw)
                    }
                    DimKeyword.SAVEDATA -> {             // cs:200-227
                        if (isPrivate || dynamicSeen || ref || DimKeyword.CONST in kws) return null
                        kws.add(kw)
                    }
                    DimKeyword.CHARADATA -> {            // cs:229-260
                        if (isPrivate || ref || DimKeyword.CONST in kws || dynamicSeen ||
                            DimKeyword.GLOBAL in kws
                        ) return null
                        kws.add(kw)
                    }
                }
            }

            // ---- 名字之后：尺寸与初值 ----
            // ';' 起为注释（Analyse 语义：token 流在 ';' 处终止，引号内不算）
            val rest = cutAnalyseComment(body.substring(i))
            if (rest.isBlank()) {
                // cs:291-299 —— EOL：无尺寸（默认 1 维）；CONST 缺初值 → CodeEE（cs:294-297）
                if (DimKeyword.CONST in kws) return null
                return DimSpec(name, isStr, emptyList(), kws, null)
            }
            val eq = topLevelIndexOf(rest, '=')
            val dimsPart: String
            val init: String?
            if (eq >= 0) {
                dimsPart = rest.substring(0, eq)
                init = rest.substring(eq + 1).trim()
                if (DimKeyword.REF in kws) return null          // cs:357-360 REF 不能设初值
            } else {
                dimsPart = rest
                init = null
            }
            val segs = splitTopLevel(dimsPart, ',').map { it.trim() }
            val dims: List<String>
            if (DimKeyword.REF in kws) {
                // spec: UserDefinedVariableData.cs:309-320 —— REF 变量每个顶层 ',' 恰贡献
                // 一个 0 尺寸（Add(0)），Dimension = 逗号数（`#DIM REF 集合, ,` → 2 维）。
                // CMP-45：逗号后的段若求值非 0 → `CanNotSizedRef`（cs:329-335，zhs:762
                // 「引用型变量不能指定数组大小(请省略或指定为0)」）。非字面量（宏/CONST/
                // 表达式）此处不判，留给下游求值（TODO(verify)）。
                for (seg in segs.drop(1)) {
                    val v = literalSize(seg) ?: continue
                    if (v != 0L) {
                        onReject?.invoke("引用型变量不能指定数组大小(请省略或指定为0)")
                        return null
                    }
                }
                dims = List(maxOf(segs.size - 1, 0)) { "" }
            } else {
                // 一般变量：空段原版 cs:321-323 HasNotExpressionAfterComma 报错，宽松跳过
                val kept = segs.filter { it.isNotEmpty() }
                // CMP-45：字面尺寸须落在 1..1_000_000（cs:337-340 抛 `OoRDefinable`，
                // zhs:763「用户定义变量的数组大小须在1至1000000之间」）。
                for (seg in kept) {
                    val v = literalSize(seg) ?: continue
                    if (v <= 0L || v > 1_000_000L) {
                        onReject?.invoke("用户定义变量的数组大小须在1至1000000之间")
                        return null
                    }
                }
                dims = kept
            }
            // cs:437-449 维度上限
            if (DimKeyword.CONST in kws && dims.size > 1) return null   // cs:438-441
            if (DimKeyword.CHARADATA in kws && dims.size > 2) return null // cs:442-445
            if (dims.size > 3) return null                              // cs:446-449
            return DimSpec(name, isStr, dims, kws, init)
        }

        /**
         * 尺寸段的**字面量**解析（CMP-45 前置判定）：十进制与 `0x` 十六进制，
         * 与原版 `ReadInt64` 的实形对齐（spec: LexicalAnalyzer.cs:36-45）。
         *
         * 非纯字面量（`#DEFINE` 宏、ERH `#DIM CONST` 标识符、任意表达式）返回 null ——
         * 原版在装载期 `ReduceIntegerTerm` 求值后才判上下限（spec: UserDefinedVariableData.cs:325-341），
         * erbcc 不在收集期求值（D.8 纪律），这类段由下游求值后自行校验。
         */
        internal fun literalSize(seg: String): Long? {
            val s = seg.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("0x", ignoreCase = true)) return s.substring(2).toLongOrNull(16)
            return s.toLongOrNull()
        }

        /** 引号与嵌套括号之外的首个 [ch] 下标；找不到 → -1。 */
        private fun topLevelIndexOf(s: String, ch: Char): Int {
            var depth = 0
            var inStr = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    ch -> if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** 顶层（引号外、括号深度 0）按 [sep] 分段（桥侧尺寸求值共用，故 internal）。 */
        internal fun splitTopLevel(s: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var inStr = false
            var start = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    sep -> if (depth == 0) { out.add(s.substring(start, i)); start = i + 1 }
                }
                i++
            }
            out.add(s.substring(start))
            return out
        }

        /**
         * 数标签形参并按类型拆分：返回 (总数, 整型数, 字符串数)。
         * 分段规则与 [countTopLevelArgs] 相同（顶层逗号、跳过引号与嵌套括号）。
         *
         * 类型判定规则（按证据强度排序）：
         * 1. 形参名以 `$` 结尾 → 字符串（Emuera 通用标识符规则；本语料实测 0 处使用）。
         * 2. 形参名恰为 `ARGS`（或 `ARGS:n` 下标）→ 字符串；`ARG`/`ARG:n` → 整型
         *    （spec: ErbLoader.cs:707-714 —— Identifier.Code == ARG → num 桶、== ARGS → num2 桶）。
         * 3. 带默认值时按默认值字面量类型：`"..."` → 字符串，其余 → 整型。
         *    证据：本语料 176 处 `X = "str"` 形态（如 `MOB_FUNCTION.ERB:1` 的 `strPart=""`、
         *    `TYPE="get"`、`ARGNAME=""`）且这些函数在原版上正常运行；若按"无 $ 名 = 整型"
         *    字面执行 ErbLoader.cs:765-771（NotMatchTypeArgAndInitialValue, warnLevel=2,
         *    isError=true → 标签不注册），它们全部会失效——与游戏可运行矛盾。故本语料的
         *    事实规则是"默认值决定类型"。
         * 4. 无默认值且非 $ 名 → 整型（spec: ErbLoader.cs:716-723 —— NullTerm 按
         *    variableTerm.GetOperandType() 取 0 或 ""，GetOperandType 由标识符决定）。
         *
         * TODO(verify)（M2 对拍期，合成 ERB 于原版实测一次定案）：
         * a. 机制解释——为何 `TYPE="get"` 不触发 ErbLoader.cs:741-743 的类型不匹配？
         *    猜测：define 上下文中新建私有标识符的类型继承自默认值字面量（ExpressionParser.cs:279-281
         *    在 ReduceArguments 定义模式做了配对类型检查，能通过说明标识符已是字符串型）。
         * b. 无默认值的非 $ 名（如 `SET_BODYGRAPH_SETS_P(..., strImgSet1, ...)`）按名字规则
         *    应为整型——若原版按调用点实参类型重定，本计数会少算字符串形参。
         * c. 默认值为常量引用（非字面量）时无法静态判定，暂按整型。
         */
        internal fun countTypedArgs(line: String, open: Int): Triple<Int, Int, Int> {
            var depth = 0
            var inStr = false
            var total = 0
            var intCnt = 0
            var strCnt = 0
            var segStart = open + 1
            var i = open
            fun classify(seg: String): Int {
                var s = seg.trim()
                if (s.length >= 4 && s.startsWith("REF ", ignoreCase = true)) s = s.substring(4).trim()
                // 形参名：读到第一个分隔符（下标/默认值/括号）
                var n = 0
                while (n < s.length && s[n] !in " (=,:)") n++
                val name = s.substring(0, n)
                if (name.endsWith("$")) return 1
                if (name == "ARGS" || name.startsWith("ARGS:")) return 1
                if (name == "ARG" || name.startsWith("ARG:")) return 0
                // 默认值字面量
                val eq = s.indexOf('=', n)
                if (eq >= 0) {
                    val def = s.substring(eq + 1).trim()
                    if (def.startsWith("\"")) return 1
                    return 0
                }
                return 0
            }
            while (i < line.length) {
                val c = line[i]
                if (inStr) {
                    if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '(', '[' -> depth++
                    ')', ']' -> {
                        depth--
                        if (depth == 0) {
                            val seg = line.substring(segStart, i)
                            if (seg.isNotBlank()) {
                                total++
                                if (classify(seg) == 1) strCnt++ else intCnt++
                            }
                            return Triple(total, intCnt, strCnt)
                        }
                    }
                    ',' -> if (depth == 1) {
                        val seg = line.substring(segStart, i)
                        if (seg.isNotBlank()) {
                            total++
                            if (classify(seg) == 1) strCnt++ else intCnt++
                        }
                        segStart = i + 1
                    }
                }
                i++
            }
            val seg = line.substring(minOf(segStart, line.length))
            if (seg.isNotBlank()) {   // 括号未闭合的容错
                total++
                if (classify(seg) == 1) strCnt++ else intCnt++
            }
            return Triple(total, intCnt, strCnt)
        }

        /** 兼容入口：只要总数。 */
        internal fun countTopLevelArgs(line: String, open: Int): Int = countTypedArgs(line, open).first

        /**
         * spec: LexicalAnalyzer.cs:647-690 SkipWhiteSpace
         * ' ' '\t' 跳过；'\u3000' 仅在 systemAllowFullSpace 时跳过（本游戏 NO）；';' 起注释。
         */
        private fun stripWs(line: String): String {
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    ' ', '\t' -> i++
                    '\u3000' -> return line.substring(i)
                    ';' -> {
                        if (line.startsWith(";!;", i) || line.startsWith(";^;", i)) { i += 3; continue }
                        return ""
                    }
                    else -> return line.substring(i)
                }
            }
            return ""
        }

        /**
         * spec: EncodingHandler.cs:9-41 —— 有 UTF-8 BOM 即按 UTF-8 读（:23-26）；
         * 否则用 `throwOnInvalidBytes` 的严格 UTF-8 试读（:9, :27-35），失败回落 CP932
         * （:37-40；Java 侧 charset "windows-31j"，NEC 选中区 0x8740-0x879C 不再变
         * U+FFFD；SJIS 侧同样是 ExceptionFallback，:70）。
         *
         * CMP-43（未收口，需组长接线）：同一份语料存在**两套解码政策**——
         * 本函数（Symbols 侧：无 NUL 探测、读失败直接抛、CP932 用非严格解码替换坏字节）
         * 与 `CorpusScan.readForScan`（CorpusScan.kt:248-265：前 8KB 有 NUL 即当二进制跳过、
         * 读失败/解码失败只留痕不抛）。原版这两类都是 `CodeEE`（读不开见 ErhLoader.cs:81-84
         * `FailedOpenFile`），差异只在「整批中止 vs 单文件跳过」。本路文件面不含
         * CorpusScan.kt，未改。
         */
        fun decode(path: Path): String {
            val bytes = Files.readAllBytes(path)
            val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) bytes.copyOfRange(3, bytes.size) else bytes
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString()
            } catch (_: CharacterCodingException) {
                // 2026-10-26 fixcore 波 B1：windows-31j = CP932（NEC 选中区不再变 U+FFFD）。
                // TODO(verify): 原版严格解码回退（坏字节 → 整文件跳过，
                // EncodingHandler.cs:67-71 + EraStreamReader.cs:50-59）本波未接。
                String(body, charset("windows-31j"))
            }
        }
    }
}
