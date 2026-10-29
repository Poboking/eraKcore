package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SAVE-12：存档写侧条目序对齐 PC `varTokenDic` 的 Add 序（VariableData.cs:3014-3179）。
 *
 * ## 缺陷与修复
 *
 * 修复前 `SaveCapture.systemEntries` 按 `registry.all()` 枚举 —— 那是本仓构造函数的注册序，
 * 按存储形态分组（1D INT → 2D INT → 3D INT → 1D STR → 0D STR），于是 SAVESTR/TSTR 排在
 * TA/TB 之后；而 PC 的 `SaveToStreamBinary`（VariableData.cs:3982 foreach）按 `Dictionary`
 * 枚举序（无删除 ⇒ 插入序，specs/save-format.md §5.3 第 3 条）产出，SAVESTR/TSTR（:3068-3069）
 * 夹在 1D 整数（:3014-3067）与 2D/3D 整数（:3103-3110）之间。未改动档靠
 * `SaveFormat.write` 的 sourceFile 原字节短路，逐字节幂等不受影响；**改档后**整档重写，
 * 条目序就与 PC 漂移。修复 = 写侧改用 `registry.allInVarTokenDicOrder()`。
 *
 * 本文件用合成变量池（默认 CSV 注册表 + `#DIM SAVEDATA` 声明）钉住：
 * 普通档 63 条系统条目的完整 PC 序、改档重写读回等价、全局档序、用户变量殿后。
 * 真档探针见 RealSaveSamplesProbeTest（ERATW_SAVE_SAMPLES 门控）。
 */
class SaveEntryOrderTest {

    /**
     * 普通档系统条目的 PC 序（默认注册表下过 `IsSavedata && !IsCharacterData && !IsGlobal`
     * 过滤后恰为这 63 名）。逐段对齐 VariableData.cs 的 varTokenDic.Add 行号：
     * GLOBAL（:3066）与 GLOBALS（:3072）是 IsGlobal 被普通档排除；STR（:3070，
     * VarCodeInt=1 不满足 VarCodeInt<1）/ RESULTS（:3071）/ SAVEDATA_TEXT（:3073，
     * EXTENDED 位）/ ITEMPRICE（:3111，EXTENDED 位）不过 isSavedata（VariableToken.cs:88-154
     * 的位规则，移植侧 VariableCode.kt:258-274）。
     */
    private val pcNormalOrder = listOf(
        // :3014-3039 —— 1 维整数（DAY..BOUGHT）
        "DAY", "MONEY", "ITEM", "FLAG", "TFLAG", "UP", "PALAMLV", "EXPLV", "EJAC", "DOWN",
        "RESULT", "COUNT", "TARGET", "ASSI", "MASTER", "NOITEM", "LOSEBASE", "SELECTCOM",
        "ASSIPLAY", "PREVCOM", "TIME", "ITEMSALES", "PLAYER", "NEXTCOM", "PBAND", "BOUGHT",
        // :3040-3065 —— 单字母 A..Z
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N",
        "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        // :3067 —— RANDDATA（SAVE_EXTENDED 位）
        "RANDDATA",
        // :3068-3069 —— SAVESTR/TSTR 夹在 1D 整数与 2D 整数之间（SAVE-12 修复点）
        "SAVESTR", "TSTR",
        // :3103-3110 —— 2D / 3D 整数
        "DITEMTYPE", "DA", "DB", "DC", "DD", "DE", "TA", "TB",
    )

    /** 写几个值，构造「改过档」的池（条目是否落盘不取决于值，但场景要求改档后重写）。 */
    private fun modifiedStore(): VariableStore = VariableStore().also { s ->
        s.writeInt("MONEY", longArrayOf(3), 987654L)
        s.writeInt("FLAG", longArrayOf(0), 1L)
        s.writeInt("RANDDATA", longArrayOf(0), 3L)
        s.writeStr("SAVESTR", longArrayOf(0), "保存文字")
        s.writeStr("TSTR", longArrayOf(0), "临时文字")
        s.writeInt("DITEMTYPE", longArrayOf(0, 0), 5L)
        s.writeInt("DA", longArrayOf(9, 9), 6L)
        s.writeInt("TA", longArrayOf(0, 0, 0), 7L)
    }

    // ==================================================================
    // 1. 条目序
    // ==================================================================

    /** 改档重写后，普通档系统条目序 = PC varTokenDic Add 序（整表硬断言）。 */
    @Test
    fun `改档后普通档条目序对齐 PC varTokenDic 注册序`() {
        val store = modifiedStore()
        val keys = SaveCapture.normal(store, "t").variables.map { it.key }
        assertEquals(pcNormalOrder, keys, "普通档系统条目序应 = varTokenDic Add 序（VariableData.cs:3014-3179 经 :3982 枚举）")
    }

    /**
     * SAVE-12 的最小回归位：SAVESTR/TSTR 必须在 1D 整数组之后、2D/3D 整数组之前
     * （SAVESTR=:3068、TSTR=:3069、DITEMTYPE=:3103、TA=:3109、TB=:3110）。
     * 修复前 registry.all() 的构造序把 SAVESTR/TSTR 排到 TB 之后，本断言红。
     */
    @Test
    fun `SAVESTR 与 TSTR 排在 2D 数组之前`() {
        val keys = SaveCapture.normal(modifiedStore(), "t").variables.map { it.key }
        val iSave = keys.indexOf("SAVESTR")
        val iTstr = keys.indexOf("TSTR")
        val iDitem = keys.indexOf("DITEMTYPE")
        val iTa = keys.indexOf("TA")
        val iTb = keys.indexOf("TB")
        assertTrue(iSave in 0 until iDitem, "SAVESTR($iSave) 应在 DITEMTYPE($iDitem) 之前")
        assertTrue(iTstr in 0 until iDitem, "TSTR($iTstr) 应在 DITEMTYPE($iDitem) 之前")
        assertTrue(iSave < iTb, "SAVESTR($iSave) 应在 TB($iTb) 之前（:3068 vs :3110）")
        assertTrue(iTstr < iTb, "TSTR($iTstr) 应在 TB($iTb) 之前（:3069 vs :3110）")
        assertTrue(iTa < iTb, "TA($iTa) 应在 TB($iTb) 之前（:3109-3110）")
    }

    /** 全局档：GLOBAL（:3066）先于 GLOBALS（:3072），且只有这两条 IsGlobal 系统变量。 */
    @Test
    fun `全局档条目序 GLOBAL 先于 GLOBALS`() {
        val keys = SaveCapture.global(VariableStore()).variables.map { it.key }
        assertEquals(listOf("GLOBAL", "GLOBALS"), keys, "全局档系统条目 = varTokenDic 序过滤 IsGlobal（:3963-3970）")
    }

    /** 用户 `#DIM SAVEDATA` 在全部系统条目之后（VariableData.cs:3990 的循环在 :3982-3989 之后）。 */
    @Test
    fun `用户 SAVEDATA 变量条目殿后`() {
        val store = modifiedStore().also { s ->
            s.declareUserVar(
                DimParser.parse("#DIM", "SAVEDATA UI, 3", isPrivate = false, DimParseConfig(systemSaveInBinary = true)),
            )
            s.writeInt("UI", longArrayOf(2), 7L)
        }
        val keys = SaveCapture.normal(store, "t").variables.map { it.key }
        assertEquals(pcNormalOrder + "UI", keys, "用户静态变量追加在系统条目之后（:3990-3996）")
    }

    // ==================================================================
    // 2. 读回等价 + 完整性
    // ==================================================================

    /** 改档 → 抓取 → 落盘字节 → 读回：条目序与值逐项不变（读侧不依赖顺序，但序也不应漂）。 */
    @Test
    fun `改档重写后读回等价不变`() {
        val store = modifiedStore()
        val data = SaveCapture.normal(store, "改档描述")
        val back = SaveFormat.read(SaveFormat.write(data))

        assertEquals(pcNormalOrder, back.variables.map { it.key }, "读回条目序不变")
        val money = back.variables.first { it.key == "MONEY" } as SaveEntry.IntArray1
        assertEquals(987654L, money.values[3], "MONEY[3]")
        val savestr = back.variables.first { it.key == "SAVESTR" } as SaveEntry.StrArray1
        assertEquals("保存文字", savestr.values[0], "SAVESTR[0]")
        val ditem = back.variables.first { it.key == "DITEMTYPE" } as SaveEntry.IntArray2
        assertEquals(5L, ditem.values[0], "DITEMTYPE[0,0] 展平首格")
        val ta = back.variables.first { it.key == "TA" } as SaveEntry.IntArray3
        assertEquals(7L, ta.values[0], "TA[0,0,0] 展平首格")
        assertEquals("改档描述", back.text, "描述文本")
    }

    /**
     * 完整性：varTokenDic 序覆盖注册表全集、无重复。LOCAL/ARG/LOCALS/ARGS 在 C# 落
     * localvarTokenDic（VariableData.cs:3181-3187）不在 varTokenDic，由
     * allInVarTokenDicOrder 的尾部兜底携带（它们 isSavedata=false，不进档，序不可观测）。
     */
    @Test
    fun `varTokenDic 序覆盖注册表全集且无重复`() {
        val registry = VariableStore().registry
        val ordered = registry.allInVarTokenDicOrder().map { it.name }
        val registered = registry.all().map { it.name }
        assertEquals(registered.size, ordered.size, "条目数一致")
        assertEquals(registered.toSet(), ordered.toSet(), "名字集合一致")
        assertEquals(ordered.size, ordered.toSet().size, "无重复")
    }
}
