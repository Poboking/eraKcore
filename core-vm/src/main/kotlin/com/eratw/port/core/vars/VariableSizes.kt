package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE

/**
 * 变量默认值与尺寸配置。
 *
 * 默认值全部取自 `ConstantData.setDefaultArrayLength`（spec: ConstantData.cs:171-260）
 * 与 `ConfigData` 的配置默认值（spec: ConfigData.cs:171-174）。
 */
data class VarConfig(
    /** PALAMLV 的初值。spec: ConfigData.cs:172 */
    val palamLvDef: LongArray = longArrayOf(0, 100, 500, 3000, 10000, 30000, 60000, 100000, 150000, 250000),

    /** EXPLV 的初值。spec: ConfigData.cs:171 */
    val expLvDef: LongArray = longArrayOf(0, 1, 4, 20, 50, 200),

    /** PBAND[0] 初值。spec: ConfigData.cs:173 */
    val pbandDef: Long = 4,

    /** RELATION 全元素初值（新建角色时）。spec: ConfigData.cs:174, CharacterData.cs:163-167 */
    val relationDef: Long = 0,

    /** CompatiRAND 开关，影响 RAND 的算法。spec: VariableData.cs:856-876 */
    val compatiRand: Boolean = false,

    /**
     * SystemSaveInBinary。默认 false（ConfigData.cs:107），
     * 该开关会禁止部分 #DIM SAVEDATA 声明（spec: UserDefinedVariableData.cs:465-475）。
     */
    val systemSaveInBinary: Boolean = false,

    /** DebugMode：决定 __FILE__/__FUNCTION__/__LINE__ 返回真实位置还是空值。 */
    val debugMode: Boolean = false,

    /**
     * VarsizeDimConfig：VARSIZE 的维度实参是否按 ERD 口径减一。
     * 出厂默认 false（spec: ConfigData.cs:117 `t: false`），只有为真且维度 > 0 时减一
     * （spec: FunctionMethodCreator.cs:3449-3452）。运行期消费点见
     * 运行期消费点见 [com.eratw.port.core.fn.VarMethods] 的 `VARSIZE`；装载期折叠面在
     * [DimParseConfig.varsizeDimConfig]。
     */
    val varsizeDimConfig: Boolean = false,
    ) {
    // LongArray 在 data class 里的 equals 是引用比较；本类只当配置包用，不做值比较。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 系统变量的尺寸表。默认值来自 `ConstantData.cs:171-260`，可被 `VariableSize.CSV` 改写
 * （spec: ConstantData.cs:309-551）。
 *
 * 必须在构造 [VariableStore] **之前**完成尺寸调整：存储池按此一次性分配。
 *
 * 全语义（VARSIZE-FULL）：
 * - [applySize] 对应 `changeVariableSizeData` 的写入 switch（ConstantData.cs:449-546）：
 *   ITEMPRICE/ITEMNAME 同时改 int1D[60] 与 maxDataList[6]；STR 同时改 str1D[1] 与
 *   maxDataList[10]；其余 CSV 名字表只改 maxDataList；角色/全局/局部/2 维/3 维按
 *   [VariableCode] 的标志位分派存储。
 * - [decideActualArraySize] 对应 `decideActualArraySize`（ConstantData.cs:583-670）：
 *   23 组主数组↔名字表联动（ConstantData.cs:585-607）、PALAM/JUEL↔PALAMNAME 专例
 *   （:608-641）、CDFLAG↔CDFLAGNAME1/2 专例（:642-668）。只在 [changed] 集合内判定；
 *   两边都改且不等时取 max 并告警，只改一边时同步另一边。
 * - [changed] 即 C# 的 `changedCode`（ConstantData.cs:547-550），重复定义靠它识别。
 *
 * 与 C# 的区别（仅告警/异常形态，数据结果一致）：
 * - 0 长度与"非 CanForbid 的负数"由装载层按原版两条告警拒绝（ConstantData.cs:342-351）；
 *   [applySize] 只在 CanForbid 变量上接受**全 0** 尺寸（负数改写后的禁用形态，:347-356）。
 * - CDFLAG 联动冲突在 C# 抛 CodeEE；本移植降为告警，避免整盘装载中断。
 */
class VariableSizes {
    /** 1 维整数数组长度，下标 = code 低 16 位（0..64；61/62 为 LOCAL/ARG，63 GLOBAL，64 RANDDATA）。 */
    val int1D: IntArray = IntArray(65) { 1000 }

    /** 1 维字符串数组长度（0..6；3/4 同时是 LOCALS/ARGS 的槽）。 */
    val str1D: IntArray = IntArray(7) { 100 }

    /** 2 维整数数组尺寸（6 组）。 */
    val int2D: Array<IntArray> = Array(6) { intArrayOf(100, 100) }

    /** 3 维整数数组尺寸（2 组）。 */
    val int3D: Array<IntArray> = Array(2) { intArrayOf(100, 100, 100) }

    /** 角色 1 维整数数组长度（84 组）。 */
    val charaInt1D: IntArray = IntArray(84) { 100 }

    /** 角色 1 维字符串长度（1 组）。 */
    val charaStr1D: IntArray = IntArray(1) { 100 }

    /** 角色 2 维整数尺寸（1 组，CDFLAG）。 */
    val charaInt2D: Array<IntArray> = arrayOf(intArrayOf(1, 1))

    /** CSV 名字表长度（29 组）。spec: ConstantData.cs:88,173-201 */
    val maxDataList: IntArray = intArrayOf(
        100, 100, 1000, 200, 1000, 100, 1000, 100, 1000, 100,
        20000, 100, 100, 10000, 1000, 1000, 100, 100, 1000, 1,
        1, 20000, 100, 100, 1000, 100, 100, 100, 100,
    )

    init {
        // ConstantData.cs:212-218
        int1D[3] = 10000          // FLAG
        int1D[60] = maxDataList[6] // ITEMPRICE
        int1D[64] = 625           // RANDDATA（VARSIZE 禁止改，ConstantData.cs:328-332）
        // ConstantData.cs:219-223
        str1D[1] = maxDataList[10] // STR = STR.CSV 长度
        // ConstantData.cs:240-251
        charaInt1D[3] = 1000      // TALENT
        charaInt1D[9] = 1000      // CFLAG
        charaInt1D[10] = 200      // JUEL
        charaInt1D[15] = 200      // GOTJUEL
    }

    // ------------------------------------------------------------------
    // 名字解析（等价 C# VariableIdentifier 的 nameDic）
    // ------------------------------------------------------------------

    /**
     * VARSIZE 行首名字 → 变量 code。C# 的 nameDic 由枚举名建立且区分大小写
     * （VariableIdentifier.cs:61-93），本表逐项取自 [SystemLayout] 的注册清单。
     */
    fun variableCode(name: String): Int? = codeByName[name]

    /** 是否 1/2/3 维数组（C# IsArray1D/2D/3D 的并集）。 */
    fun isArray(code: Int): Boolean = VariableCode.dimension(code) != 0

    /** 是否局部变量（LOCAL/ARG/LOCALS/ARGS）。 */
    fun isLocal(code: Int): Boolean = (code and VariableCode.LOCAL_FLAG) == VariableCode.LOCAL_FLAG

    /** 是否计算变量（RAND/CHARANUM 等，禁止改尺寸，ConstantData.cs:328-332）。 */
    fun isCalc(code: Int): Boolean = (code and VariableCode.CALC) == VariableCode.CALC

    /** 负数尺寸是否表示"禁用数组"（C# CanForbid）。 */
    fun canForbid(code: Int): Boolean = (code and VariableCode.CAN_FORBID) == VariableCode.CAN_FORBID

    /** 是否角色变量（C# IsCharacterData）。 */
    fun isCharacter(code: Int): Boolean = VariableCode.isCharacterData(code)

    /** code → 注册名（告警文案用；未知 code 回退十六进制）。 */
    fun codeName(code: Int): String =
        nameByCode[code] ?: "0x" + code.toUInt().toString(16)

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** VARSIZE.CSV：按名字改 1 维整数数组长度（既有 API，行为不变）。 */
    fun setInt1D(name: String, size: Int) {
        verifySize(size)
        if (name == "RANDDATA") throw CodeEE("RANDDATA 的尺寸不可修改") // ConstantData.cs:328-332
        val code = SystemLayout.int1D.firstOrNull { it.first == name }?.second
            ?: throw CodeEE("未知的 1 维整数变量 $name")
        applySize(code, intArrayOf(size))
    }

    /** VARSIZE.CSV：按名字改 1 维字符串数组长度（既有 API，行为不变）。 */
    fun setStr1D(name: String, size: Int) {
        verifySize(size)
        val code = SystemLayout.str1D.firstOrNull { it.first == name }?.second
            ?: throw CodeEE("未知的 1 维字符串变量 $name")
        applySize(code, intArrayOf(size))
    }

    /**
     * 写入一条已由装载层校验过的尺寸（ConstantData.cs:449-546 的写入 switch）。
     *
     * @return false 表示该 code 此前已改过（C# VarSizeAlreadyDefined，ConstantData.cs:547-550）
     */
    fun applySize(code: Int, dims: IntArray): Boolean {
        if (isCalc(code) || code == VariableCode.RANDDATA) {
            throw CodeEE("该变量尺寸不可修改：${codeName(code)}")
        }
        val low = VariableCode.lowIndex(code)
        val dim = VariableCode.dimension(code)
        if (dim == 0 || dims.size != dim) {
            throw CodeEE("变量 ${codeName(code)} 需要 $dim 维尺寸，实际 ${dims.size} 个")
        }
        if (dims.any { it < 0 }) {
            throw CodeEE("数组长度不能为负数（${dims.joinToString(" x ")}）")
        }
        // CanForbid 变量负数改写后的"禁用"形态：各维全 0（ConstantData.cs:356 的 result3 = 0）。
        // 原版恒写 0 长度数组，故只接受全 0；0 与非 CanForbid 的负数已在装载层拒绝。
        if (dims.any { it == 0 } && !(canForbid(code) && dims.all { it == 0 })) {
            throw CodeEE("数组长度必须为正数（${dims.joinToString(" x ")}）")
        }
        when {
            // ITEMPRICE 与 ITEMNAME 是同一份数据的两个名字（ConstantData.cs:451-455）
            code == VariableCode.ITEMPRICE || code == VariableCode.ITEMNAME -> {
                int1D[60] = dims[0]
                maxDataList[6] = dims[0]
            }
            // STR 的字符串表与 STR.CSV 名字表同步（ConstantData.cs:456-459）
            code == VariableCode.STR -> {
                str1D[1] = dims[0]
                maxDataList[10] = dims[0]
            }
            // 其余 26 个 CSV 名字表只写 MaxDataList（ConstantData.cs:460-488）
            code in csvNameCodes -> maxDataList[low] = dims[0]
            VariableCode.isCharacterData(code) -> when (dim) {
                1 -> if (VariableCode.isString(code)) {
                    charaStr1D[low] = dims[0]
                } else {
                    charaInt1D[low] = dims[0]
                }
                2 -> charaInt2D[low] = intArrayOf(dims[0], dims[1])
                else -> throw CodeEE("不支持的角色变量维度：${codeName(code)}")
            }
            else -> when (dim) {
                1 -> if (VariableCode.isString(code)) str1D[low] = dims[0] else int1D[low] = dims[0]
                2 -> if (VariableCode.isString(code)) {
                    throw CodeEE("不支持的系统字符串 2 维变量：${codeName(code)}")
                } else {
                    int2D[low] = intArrayOf(dims[0], dims[1])
                }
                else -> if (VariableCode.isString(code)) {
                    throw CodeEE("不支持的系统字符串 3 维变量：${codeName(code)}")
                } else {
                    int3D[low] = intArrayOf(dims[0], dims[1], dims[2])
                }
            }
        }
        return changedCodes.add(code)
    }

    // ------------------------------------------------------------------
    // 主数组↔名字表联动（decideActualArraySize）
    // ------------------------------------------------------------------

    /**
     * 全部行装载完后调用一次：调和主数组与名字表尺寸并清空 [changed]
     * （spec: ConstantData.decideActualArraySize，ConstantData.cs:583-670）。
     */
    fun decideActualArraySize(warnings: MutableList<String>) {
        for (c in COUPLINGS) {
            when (c.kind) {
                Kind.INT1D -> couple1D(c.main, c.name, int1D, warnings)
                Kind.STR1D -> couple1D(c.main, c.name, str1D, warnings)
                Kind.CHARA_INT1D -> couple1D(c.main, c.name, charaInt1D, warnings)
                Kind.CHARA_STR1D -> couple1D(c.main, c.name, charaStr1D, warnings)
            }
        }
        decidePalamJuel(warnings)
        decideCdflag(warnings)
        changedCodes.clear()
    }

    /**
     * `_decideActualArraySize_sub`（ConstantData.cs:553-581）：
     * 只在 changed 内判定；两边都改且不等 → 取 max；只改名字 → 主数组跟随；
     * 只改主数组 → 名字表跟随。
     */
    private fun couple1D(main: Int, name: Int, array: IntArray, warnings: MutableList<String>) {
        val nameSlot = VariableCode.lowIndex(name)
        val mainSlot = VariableCode.lowIndex(main)
        val mainChanged = changedCodes.contains(main)
        val nameChanged = changedCodes.contains(name)
        if (mainChanged && nameChanged) {
            val mainLen = array[mainSlot]
            val nameLen = maxDataList[nameSlot]
            if (mainLen != nameLen) {
                val max = maxOf(mainLen, nameLen)
                array[mainSlot] = max
                maxDataList[nameSlot] = max
                if (mainLen == 0 || nameLen == 0) {
                    warnings.add(
                        "VariableSize.csv: ${codeName(main)} 与 ${codeName(name)} 有一侧尺寸为 0（禁用），" +
                            "已同时取 $max",
                    )
                } else {
                    warnings.add(
                        "VariableSize.csv: 主数组与名字表尺寸不一致（${codeName(main)}=$mainLen / " +
                            "${codeName(name)}=$nameLen），已同时取最大值 $max",
                    )
                }
            }
        } else if (nameChanged) {
            array[mainSlot] = maxDataList[nameSlot]
        } else if (mainChanged) {
            maxDataList[nameSlot] = array[mainSlot]
        }
    }

    /** PALAM/JUEL 与 PALAMNAME 专例（ConstantData.cs:608-641）。 */
    private fun decidePalamJuel(warnings: MutableList<String>) {
        if (changedCodes.contains(VariableCode.PALAM) || changedCodes.contains(VariableCode.JUEL)) {
            val max = maxOf(charaInt1D[6], charaInt1D[10])
            if (changedCodes.contains(VariableCode.PALAMNAME)) {
                if (maxDataList[3] != max) {
                    val merged = maxOf(maxDataList[3], max)
                    maxDataList[3] = merged
                    if (charaInt1D[6] == max) charaInt1D[6] = merged
                    if (charaInt1D[10] == max) charaInt1D[10] = merged
                    warnings.add(
                        "VariableSize.csv: PALAM/JUEL 与 PALAMNAME 尺寸不一致，已同时取最大值 $merged",
                    )
                }
            } else {
                maxDataList[3] = max
            }
        } else if (changedCodes.contains(VariableCode.PALAMNAME)) {
            charaInt1D[6] = maxDataList[3]
            if (maxDataList[3] < charaInt1D[10]) {
                warnings.add(
                    "VariableSize.csv: PALAMNAME 小于 JUEL，PALAMNAME 已提升到 ${charaInt1D[10]}",
                )
                maxDataList[3] = charaInt1D[10]
            }
        }
    }

    /** CDFLAG 2 维数组与 CDFLAGNAME1/2 专例（ConstantData.cs:642-668）。 */
    private fun decideCdflag(warnings: MutableList<String>) {
        val namesChanged = changedCodes.contains(VariableCode.CDFLAGNAME1) ||
            changedCodes.contains(VariableCode.CDFLAGNAME2)
        val mainChanged = changedCodes.contains(VariableCode.CDFLAG)
        var width = charaInt2D[0][0]
        var height = charaInt2D[0][1]
        if (mainChanged && namesChanged) {
            if (width != maxDataList[19] || height != maxDataList[20]) {
                warnings.add(
                    "VariableSize.csv: CDFLAG 的 2 维尺寸（$width x $height）与 " +
                        "CDFLAGNAME1/2（${maxDataList[19]} x ${maxDataList[20]}）不一致；" +
                        "C# 在此抛 CodeEE，本移植告警后保留 CDFLAG 侧",
                )
            }
        } else if (namesChanged) {
            width = maxDataList[19]
            height = maxDataList[20]
            if (width.toLong() * height > 1_000_000L) {
                warnings.add("VariableSize.csv: CDFLAG 元素数超过 1000000（$width x $height）")
            } else {
                charaInt2D[0] = intArrayOf(width, height)
            }
        } else if (mainChanged) {
            maxDataList[19] = width
            maxDataList[20] = height
        }
    }

    internal fun verifySize(size: Int) {
        if (size <= 0) throw CodeEE("数组长度必须为正数（实际 $size）")
    }

    /** 已改尺寸变量 code 的只读快照（诊断/测试用；等价 C# changedCode）。 */
    val changed: Set<Int> get() = changedCodes.toSet()

    // ------------------------------------------------------------------
    // 内部表
    // ------------------------------------------------------------------

    private val changedCodes = HashSet<Int>()

    private val layoutPairs: List<Pair<String, Int>> = buildList {
        addAll(SystemLayout.int1D)
        addAll(SystemLayout.str1D)
        addAll(SystemLayout.str0D)
        addAll(SystemLayout.int2D)
        addAll(SystemLayout.int3D)
        addAll(SystemLayout.charaInt0D)
        addAll(SystemLayout.charaStr0D)
        addAll(SystemLayout.charaInt1D)
        addAll(SystemLayout.charaStr1D)
        addAll(SystemLayout.charaInt2D)
        addAll(SystemLayout.localInt)
        addAll(SystemLayout.localStr)
        addAll(SystemLayout.csvConsts)
        for ((n, c, _) in SystemLayout.pseudo) add(n to c)
        addAll(SystemLayout.gameBaseStr)
        addAll(SystemLayout.gameBaseInt)
    }

    private val codeByName: Map<String, Int> = layoutPairs.toMap()

    private val nameByCode: Map<Int, String> = buildMap {
        for ((n, c) in layoutPairs) if (!containsKey(c)) put(c, n)
    }

    /** 29 个 CSV 名字表 code（ITEMNAME 特例在写入 switch 里先拦截）。 */
    private val csvNameCodes: Set<Int> = SystemLayout.csvConsts.mapTo(HashSet()) { it.second }

    private enum class Kind { INT1D, STR1D, CHARA_INT1D, CHARA_STR1D }

    private data class Coupling(val main: Int, val name: Int, val kind: Kind)

    companion object {
        /** C# decideActualArraySize 的 23 条联动调用，顺序照抄 ConstantData.cs:585-607。 */
        private val COUPLINGS: List<Coupling> = listOf(
            Coupling(VariableCode.ABL, VariableCode.ABLNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.TALENT, VariableCode.TALENTNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.EXP, VariableCode.EXPNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.MARK, VariableCode.MARKNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.BASE, VariableCode.BASENAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.SOURCE, VariableCode.SOURCENAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.EX, VariableCode.EXNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.EQUIP, VariableCode.EQUIPNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.TEQUIP, VariableCode.TEQUIPNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.FLAG, VariableCode.FLAGNAME, Kind.INT1D),
            Coupling(VariableCode.TFLAG, VariableCode.TFLAGNAME, Kind.INT1D),
            Coupling(VariableCode.CFLAG, VariableCode.CFLAGNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.TCVAR, VariableCode.TCVARNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.CSTR, VariableCode.CSTRNAME, Kind.CHARA_STR1D),
            Coupling(VariableCode.STAIN, VariableCode.STAINNAME, Kind.CHARA_INT1D),
            Coupling(VariableCode.STR, VariableCode.STRNAME, Kind.STR1D),
            Coupling(VariableCode.TSTR, VariableCode.TSTRNAME, Kind.STR1D),
            Coupling(VariableCode.SAVESTR, VariableCode.SAVESTRNAME, Kind.STR1D),
            Coupling(VariableCode.GLOBAL_VAR, VariableCode.GLOBALNAME, Kind.INT1D),
            Coupling(VariableCode.GLOBALS_VAR, VariableCode.GLOBALSNAME, Kind.STR1D),
            Coupling(VariableCode.DAY, VariableCode.DAYNAME, Kind.INT1D),
            Coupling(VariableCode.TIME, VariableCode.TIMENAME, Kind.INT1D),
            Coupling(VariableCode.MONEY, VariableCode.MONEYNAME, Kind.INT1D),
        )
    }
}

/**
 * 简单 RNG 接口。M2 第二批接 SFMT19937（specs/rng.md），本层只依赖取模前的采样。
 *
 * 状态管理三方法（RANDOMIZE/INITRAND/DUMPRAND 语句消费，spec: VariableEvaluator.cs:217-230）
 * 默认抛 [CodeEE]：非 SFMT 提供者显式失败，不静默降级。
 */
fun interface RandProvider {
    /** 返回 [0, bound) 的均匀随机数。 */
    fun next(bound: Long): Long

    /**
     * 重播种（RANDOMIZE）。spec: VariableEvaluator.cs:217-220 → MTRandom(long seed)（MTRandom.cs:30-33）。
     * 原版是**新建** MTRandom（init_gen_rand + idx=624），语义等价于 reseed。
     */
    fun reseed(seed: Long): Unit = throw CodeEE("当前 RandProvider 不支持重播种（RANDOMIZE）")

    /** 导出 625 元素状态（DUMPRAND → RANDDATA 全局数组）。spec: VariableEvaluator.cs:227-229 → MTRandom.cs:69-80 GetRand */
    fun state(): LongArray = throw CodeEE("当前 RandProvider 不支持状态导出（DUMPRAND）")

    /** 装载 625 元素状态（INITRAND ← RANDDATA 全局数组）。spec: VariableEvaluator.cs:222-224 → MTRandom.cs:56-67 SetRand */
    fun loadState(data: LongArray): Unit = throw CodeEE("当前 RandProvider 不支持状态装载（INITRAND）")
}
