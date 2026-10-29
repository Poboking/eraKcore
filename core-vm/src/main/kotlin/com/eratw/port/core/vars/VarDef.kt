package com.eratw.port.core.vars

/**
 * 变量层错误，等价原版 `CodeEE`（ExpressionParser/VariableData 各处 throw）。
 *
 * 唯一真源已迁到 [com.eratw.port.core.err.CodeEE]（team-lead 裁决 2026-10-17），
 * 本包保留同名 typealias：调用点写法不变，宿主 `catch (CodeEE)` 可统一兜住。
 */
typealias CodeEE = com.eratw.port.core.err.CodeEE

/** 变量静态类型。spec: VariableToken.cs:67（typeof(long) / typeof(string)）。 */
enum class VarType { INT, STR }

/** 变量在存储层的归属与形状（把 C# 的一串 private token 子类压成枚举）。 */
enum class Storage {
    /** 全局 1 维整数数组：`VariableData.dataIntegerArray[65]` */
    G_INT1,

    /** 全局 2 维整数：`dataIntegerArray2D[6]` */
    G_INT2,

    /** 全局 3 维整数：`dataIntegerArray3D[2]` */
    G_INT3,

    /** 全局 1 维字符串：`dataStringArray[7]` */
    G_STR1,

    /** 全局 0 维字符串：`dataString`（长度 1） */
    G_STR0,

    /** 角色 0 维整数：`CharacterData.dataInteger[2]` */
    C_INT0,

    /** 角色 0 维字符串：`CharacterData.dataString[4]` */
    C_STR0,

    /** 角色 1 维整数：`CharacterData.dataIntegerArray[84]` */
    C_INT1,

    /** 角色 1 维字符串：`CharacterData.dataStringArray[1]` */
    C_STR1,

    /** 角色 2 维整数：`CharacterData.dataIntegerArray2D[1]` */
    C_INT2,

    /** 函数局部 1 维整数（LOCAL/ARG） */
    L_INT,

    /** 函数局部 1 维字符串（LOCALS/ARGS） */
    L_STR,

    /** 伪变量/常量（RAND/CHARANUM/GAMEBASE_* …） */
    PSEUDO,

    /** CSV 常量名字表（ABLNAME 等，只读）。spec: VariableData.cs:3112-3139 */
    CSV_CONST,

    /** 用户自定义 #DIM 变量 */
    USER,
}

/** 伪变量种类。spec: VariableData.cs:3154-3179 注册的每个 token 一类。 */
enum class PseudoKind {
    RAND,
    CHARANUM,
    LASTLOAD_TEXT,
    LASTLOAD_VERSION,
    LASTLOAD_NO,
    LINECOUNT,
    ISTIMEOUT,
    INT_MAX,
    INT_MIN,
    EMUERA_VERSION,
    WINDOW_TITLE,
    MONEYLABEL,
    DRAWLINESTR,
    DEBUG_FILE,
    DEBUG_FUNCTION,
    DEBUG_LINE,
    GAMEBASE_INT,
    GAMEBASE_STR,
}

/**
 * 变量元信息。系统变量表**只由 [VariableRegistry] 构造一次**，运行期只读。
 *
 * [dims] 是"元素维度"（不含角色位置）；角色变量的完整维度 = dims.size + 1，
 * 与 `VarInfo.dimension` 口径一致（spec: VariableParser.cs 的下标补全按完整维度判断）。
 */
class VarDef(
    val name: String,
    val code: Int,
    val type: VarType,
    val storage: Storage,
    val index: Int,
    val dims: IntArray,
    val isConst: Boolean,
    val isSavedata: Boolean,
    val isGlobal: Boolean,
    val keywordDim: Int = -1,
    val keywordTable: String? = null,
    val keywordTableByDim: Boolean = false,
    val pseudo: PseudoKind? = null,
    val isCharacterData: Boolean = storage == Storage.C_INT0 || storage == Storage.C_STR0 ||
        storage == Storage.C_INT1 || storage == Storage.C_STR1 || storage == Storage.C_INT2,
) {
    /**
     * 名字的大写归一，**构造期算一次**（PERF 2026-10-26）。
     *
     * 变量名在变量层一律按大写键索引（`Config.cs:33` 的 `StrComper = OrdinalIgnoreCase`），
     * 而 `String.uppercase()` 在 ART 上走 ICU CaseMapper 逐字符分配。局部变量取实例
     * （`VariableStore.localFor`）、元素校验（`checkElement`）等热路径每次访问都要一份，
     * 真机采样里这条链占 1.7% 且是纯分配。定义期算好、之后所有消费点直读。
     */
    val upperName: String = name.uppercase()

    /**
     * 局部变量实例的**密集槽位号**（`VariableStore` 首次触达时分配，`-1` = 尚未分配）。
     *
     * 只对 `Storage.L_INT` / `L_STR` 这类「每函数一份」的定义有意义，语料里就是
     * `LOCAL`/`LOCALS`/`ARG`/`ARGS` 四个（`VariableStore.LOCAL_NAME_ORDER`）。
     *
     * 存在理由：旧实现每次局部变量访问都要按**名字**从
     * `localsByFunc[funcKey]` → `[upperName]` 两层 `LinkedHashMap` 里取实例
     * （`localFor` 两次 + `checkElement` 两次 = 每次访问四次哈希）。有稳定槽位号之后，
     * 帧侧持一张 `Array<Local1D?>` 直接下标取，**命中路径零哈希**。
     *
     * 由 `VariableStore` 单调分配、只写一次；跨 `VariableStore` 实例复用时可能撞号，
     * 所以取用侧一律再用 `v.def === def` 验身份（同 `bindOf` 的 `bindNames[slot] === name`）。
     */
    internal var localSlot: Int = -1

    /**
     * 元素维度（**不含**角色位置），与 `VariableToken.Dimension`（spec: VariableToken.cs:74-87）
     * 及 `VariableParser.ReduceVariable` 的分支口径一致：
     * NAME（0D 角色）→ 0，CFLAG（1D 角色）→ 1，CDFLAG（2D 角色）→ 2。
     */
    val dimension: Int get() = dims.size

    /** 下标个数 = 元素维度 + （角色变量）1。 */
    val argCount: Int = dims.size + if (isCharacterData) 1 else 0

    /**
     * 元素总数（1D 长度；2D/3D 各维乘积）。
     *
     * **不能**照抄 `d * d1 * d2` 的 Int 折叠：原版多维数组是 CLR 的 `new long[n, m]`，
     * 由运行时按维度分配、从不做 32 位预乘（spec: VariableData.cs:2974-2981 的
     * `new long[num2, num3]`、:2990-2997 的 `new long[num8, num9, num10]`）。
     * Int 折叠在乘积超过 2^31 时溢出成负数（`NegativeArraySizeException`）或偏小的正数
     * （后续行主序寻址 AIOOBE），两种都不是 CodeEE。故一律走 [dimProduct] 的 Long 预乘 + 上界判定。
     */
    val totalSize: Int get() = dimProduct(name, dims)

    val elementCount: Int get() = if (dims.isEmpty()) 1 else totalSize

    /**
     * 尺寸为 0 的数组不可用。spec: VariableData.cs:25,112,676
     *
     * **构造期算一次**（T2-62）：旧实现是 getter，每次变量访问都跑一遍
     * `dims.any { it == 0 }`（JFR 自耗时 1.8%）；[dims] 与 [isCharacterData] 都是构造参数且
     * 声明期后不再变化（`VariableRegistry` 只构造一次；用户变量的 def 拿 `DimSpec.dims` 的
     * 副本，全仓无写点），预计算与逐次求值等价。
     */
    val isForbid: Boolean = dims.isNotEmpty() && dims.any { it == 0 }

    fun length(dim: Int): Int {
        if (dim < 0 || dim >= dims.size) {
            throw CodeEE("变量 $name 不存在第 ${dim + 1} 维（维度 ${dims.size}）")
        }
        return dims[dim]
    }

    /** 可被赋值？伪变量仅 WINDOW_TITLE 可写，spec: VariableData.cs:943-960。 */
    val isWritable: Boolean
        get() = when (storage) {
            Storage.PSEUDO -> pseudo == PseudoKind.WINDOW_TITLE
            else -> !isConst
        }

    /** 供 expr 层解析期使用的元信息。 */
    fun toVarInfo(): com.eratw.port.core.expr.VarInfo = com.eratw.port.core.expr.VarInfo(
        name = name,
        operandType = if (type == VarType.INT) com.eratw.port.core.expr.OperandType.INT
        else com.eratw.port.core.expr.OperandType.STR,
        dimension = dimension,
        isCharacterData = isCharacterData,
        isConst = isConst,
    )

    override fun toString(): String = "$name(code=$code, dim=$dimension, $storage)"
}

/**
 * 各维乘积（行主序元素总数），**Long 预乘**后再判可分配上限。
 *
 * 原版由 CLR 按维度分配（`new long[num2, num3]` / `new long[num8, num9, num10]`，
 * spec: VariableData.cs:2980 / :2997），没有 32 位预乘这一步；移植侧若用 Int 折叠，
 * 大维度会先溢出再分配 —— 负数抛 `NegativeArraySizeException`、偏小的正数则在运行期越界。
 * 两者都不是 CodeEE，与同族 [com.eratw.port.core.data.save.SaveFormat] 的 dimProduct 统一口径。
 *
 * 负数维度同样直接拒绝（原版尺寸在声明/CSV 装载期就已被卡在正数，
 * spec: UserDefinedVariableData.cs:337-340 / ConstantData.cs:342-356）。
 */
internal fun dimProduct(name: String, dims: IntArray): Int {
    var total = 1L
    for (d in dims) {
        if (d < 0) throw CodeEE("变量 $name 的尺寸 $d 非法（${dims.joinToString(" x ")}）")
        total *= d
        if (total > Int.MAX_VALUE) {
            throw CodeEE("变量 $name 的元素总数 $total 超出可分配上限（${dims.joinToString(" x ")}）")
        }
    }
    return total.toInt()
}

/**
 * 角色模板（CSV `CHARA*.CSV` 的一行）。spec: CharacterTemplate.cs:7-46,
 * `CharacterData(ConstantData, CharacterTemplate, VariableData)` 的拷贝规则见 CharacterData.cs:128-192。
 */
data class CharacterTemplate(
    val no: Long,
    val name: String = "",
    val callname: String = "",
    val nickname: String = "",
    val mastername: String = "",
    val maxbase: Map<Int, Long> = emptyMap(),
    val mark: Map<Int, Long> = emptyMap(),
    val exp: Map<Int, Long> = emptyMap(),
    val abl: Map<Int, Long> = emptyMap(),
    val talent: Map<Int, Long> = emptyMap(),
    val relation: Map<Int, Long> = emptyMap(),
    val cflag: Map<Int, Long> = emptyMap(),
    val equip: Map<Int, Long> = emptyMap(),
    val juel: Map<Int, Long> = emptyMap(),
    val cstr: Map<Int, String> = emptyMap(),
    /** CSV 行号；ADDCHARA 按 NO 查，ADDCHARA 的 CSV 变体按 csvNo 查。 */
    val csvNo: Long = no,
) {
    companion object {
        /** 伪角色：未命中 CSV 时使用，NO=0、名字全空。spec: ConstantData.cs:1252-1255 */
        fun pseudo(csvNo: Long = 0L): CharacterTemplate = CharacterTemplate(no = 0L, csvNo = csvNo)
    }
}

/** GAMEBASE.CSV 的常量值。M3 CSV 装载层填充；默认值与原版空 GAMEBASE 一致。 */
data class GameBaseData(
    val author: String = "",
    val info: String = "",
    val year: String = "",
    val title: String = "",
    val url: String = "",
    val versionName: String = "",
    val gameCode: Long = 0L,
    val version: Long = 0L,
    /**
     * `GameBase.ScriptVersionDefined`（`GameBase.cs:25` + `:138-140`）：GAMEBASE.CSV 的
     * `バージョン` 行**写了且能解析出数字**才 true，与解析出来的数值是不是 0 无关
     * （`バージョン,proto` ⇒ false 且 version=0；`バージョン,0` ⇒ true 且 version=0）。
     * 存档版本判定的第 1 条放行读的就是这一位（`GameBaseGate.checkVersion`）。
     *
     * null = 这一位没被 CSV 装载层交出（手工构造的夹具、无 GAMEBASE.CSV 的场合）
     * ⇒ 判定退回 `version != 0` 的近似，三态与差异见
     * [com.eratw.port.core.data.save.GameBaseGate.checkVersion]。
     */
    val versionDefined: Boolean? = null,
    val allowVersion: Long = 0L,
    val defaultChara: Long = 0L,
    val noItem: Long = 0L,
)
