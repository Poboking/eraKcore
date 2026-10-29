package com.eratw.port.core.proc

import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.Assumptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SystemSaveInBinary` 两处开关的**同源喂入** + 字符串标量 CONST 表接线。
 *
 * 语义依据（逐条）：
 * - 配置项注册与出厂默认 false：spec: `ConfigData.cs:107`
 *   （`ConfigItem<bool>(ConfigCode.SystemSaveInBinary, "セーブデータをバイナリ形式で保存する",
 *   "Use the binary format for saving data", t: false)`）；
 * - 声明期闸门：spec: `UserDefinedVariableData.cs:465-475`
 *   （`!isPrivate && Save && !Config.SystemSaveInBinary` 时，字符串多维 → StrVarrRequiredBinaryOption，
 *   CHARADATA → CharaStrRequiredBinaryOption）；移植侧同判据 `core/vars/UserVariables.kt:384-387`，
 *   **本测试锁死判据本身仍在**（开关为 false 时必须继续失败）；
 * - 值词法：spec: `ConfigItem.cs:227-250` 的 `tryStringToBool`（整数非 0 / YES / TRUE / 前为真，
 *   NO / FALSE / 後为假），无效值保留旧值 spec: `ConfigItem.cs:89-95` + `ConfigData.cs:563-586`；
 * - 键匹配：spec: `ConfigData.cs:262-284`（Trim 后大写比较，英文/日文标签等价）；
 * - 语料真值：`eratw-画蛇添足版eratw4.980/emuera.config:60` =
 *   `USE THE BINARY FORMAT FOR SAVING DATA:YES`；
 * - 字符串 CONST 折叠：spec: `UserDefinedVariableData.cs:405-416`
 *   （初值逐项 `Restructure` 后必须是 `SingleTerm`，`dims` 侧取 `SingleStrTerm.Str` 写 `DefaultStr`），
 *   未注册标识符 → InitialValueOnlyConst spec: `UserDefinedVariableData.cs:406-408`；
 *   消费点 `core/vars/UserVariables.kt:843`（`DimParseConfig.strConstants`）。
 *
 * 语料实形（本测试第 9–11 例钉住）：
 * `ステータス表示関連/グラフィック特殊効果.ERH:19` = `#DIMS CONST EFFNAME_SPLIT = ","`，
 * `ステータス表示関連/グラフィック特殊効果.ERB:64` = `#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT`
 * （全语料唯一一条「初值是裸标识符的字符串 CONST」，故 `EFFNAME_SPLIT` 确为 CONST 而非 CSV 名字表项）。
 */
class SaveBinarySwitchTest {

    private fun decl(raw: String, file: String = "T.ERH", line: Int = 1) =
        SystemVariableBootstrap.RawDeclaration(raw, file, line)

    // ------------------------------------------------------------------
    // 1) 取值口径
    // ------------------------------------------------------------------

    @Test
    fun `英文标签键 YES 命中开关`() {
        val overrides = mapOf("USE THE BINARY FORMAT FOR SAVING DATA" to "YES")
        assertTrue(SystemVariableBootstrap.systemSaveInBinaryFrom(overrides))
    }

    @Test
    fun `日文标签键与大小写混写都命中同一条目`() {
        // spec: ConfigData.cs:262-284（日文/英文标签等价）；BootHosts 的覆盖表键已是英文大写形，
        // 这里额外锁死调用方直接喂日文形也等价。
        assertTrue(
            SystemVariableBootstrap.systemSaveInBinaryFrom(
                mapOf("セーブデータをバイナリ形式で保存する" to "yes"),
            ),
        )
        assertTrue(
            SystemVariableBootstrap.systemSaveInBinaryFrom(
                mapOf(" use the binary format for saving data " to "True"),
            ),
        )
    }

    @Test
    fun `NO 前 后 整数与无效值的口径`() {
        fun of(v: String) =
            SystemVariableBootstrap.systemSaveInBinaryFrom(mapOf("USE THE BINARY FORMAT FOR SAVING DATA" to v))
        // spec: ConfigItem.cs:227-250
        assertFalse(of("NO"))
        assertFalse(of("FALSE"))
        assertFalse(of("後"))
        assertFalse(of("0"))
        assertTrue(of("前"))
        assertTrue(of("1"))
        // 无效值 ⇒ 保留旧值 = 出厂默认 false（spec: ConfigItem.cs:89-95）
        assertFalse(of("たぶん壊れてる"))
    }

    @Test
    fun `键缺失时取出厂默认 false`() {
        assertFalse(SystemVariableBootstrap.systemSaveInBinaryFrom(emptyMap()))
        assertFalse(
            SystemVariableBootstrap.systemSaveInBinaryFrom(mapOf("ALLOW SP CHARACTERS" to "YES")),
        )
    }

    @Test
    fun `两处开关可由同一个取值构造`() {
        // 判据：DimParseConfig（声明闸门）与 VarConfig（StateHash meta 簇）必须吃同一个值。
        val value = SystemVariableBootstrap.systemSaveInBinaryFrom(
            mapOf("USE THE BINARY FORMAT FOR SAVING DATA" to "YES"),
        )
        val dimConfig = DimParseConfig(systemSaveInBinary = value)
        val varConfig = VarConfig(systemSaveInBinary = value)
        assertTrue(dimConfig.systemSaveInBinary)
        assertTrue(varConfig.systemSaveInBinary)
    }

    // ------------------------------------------------------------------
    // 2) 闸门本体：开关 false 时**不许**放行
    // ------------------------------------------------------------------

    @Test
    fun `非二进制下 CHARADATA SAVEDATA 仍被拒`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIM CHARADATA SAVEDATA CHILD_SEX, 101 ; 子供える")),
        )
        assertEquals(0, r.declared)
        assertEquals(1, r.failures.size)
        assertTrue(r.failures.single().message.contains("非二进制存档"), "实际：${r.failures.single().message}")
    }

    @Test
    fun `非二进制下多维 SAVEDATA 字符串仍被拒`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS SAVEDATA MULTI, 4, 4")),
        )
        assertEquals(0, r.declared)
        assertTrue(r.failures.single().message.contains("非二进制存档"), "实际：${r.failures.single().message}")
    }

    @Test
    fun `开关置真后同一条声明注册成功`() {
        val cfg = DimParseConfig(systemSaveInBinary = true)
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM CHARADATA SAVEDATA CHILD_SEX, 101 ; 子供える"),
                decl("#DIMS CHARADATA SAVEDATA CHILDNAME, 101"),
                decl("#DIMS SAVEDATA MULTI, 4, 4"),
            ),
            cfg,
        )
        assertEquals(3, r.declared, "失败：${r.failures}")
        assertTrue(r.failures.isEmpty())
        assertEquals(101, store.getLength("CHILD_SEX", -1))
    }

    // ------------------------------------------------------------------
    // 3) 字符串常量表
    // ------------------------------------------------------------------

    @Test
    fun `字符串标量 CONST 进入字符串常量表`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS CONST EFFNAME_SPLIT = \",\" ; 效果分隔符")),
        )
        assertEquals(1, r.declared, "失败：${r.failures}")
        assertEquals(",", r.strConstants["EFFNAME_SPLIT"])
        assertEquals(",", store.readStr("EFFNAME_SPLIT", longArrayOf(0L)))
    }

    @Test
    fun `字符串 CONST 的初值可以是另一条字符串 CONST`() {
        // 语料实形：グラフィック特殊効果.ERB:64 `#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT`
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIMS CONST EFFNAME_SPLIT = \",\"", "グラフィック特殊効果.ERH", 19),
                decl("#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT ; 效果分隔符", "グラフィック特殊効果.ERB", 64),
            ),
        )
        assertEquals(2, r.declared, "失败：${r.failures}")
        assertEquals(",", r.strConstants["C_SPLITWORD"])
        assertEquals(",", store.readStr("C_SPLITWORD", longArrayOf(0L)))
    }

    @Test
    fun `声明序相反时由不动点重试环兜住`() {
        // 后置常量：第一轮 C_SPLITWORD 的初值未注册 → 重新入队（spec: ErhLoader.cs:315-321），
        // 第二轮 EFFNAME_SPLIT 已注册 → 通过。
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT", "グラフィック特殊効果.ERB", 64),
                decl("#DIMS CONST EFFNAME_SPLIT = \",\"", "グラフィック特殊効果.ERH", 19),
            ),
        )
        assertEquals(2, r.declared, "失败：${r.failures}")
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun `入参里的既有字符串常量参与折叠`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS CONST SEED_STR = K_SPLIT")),
            DimParseConfig(strConstants = mapOf("K_SPLIT" to "|")),
        )
        assertEquals(1, r.declared, "失败：${r.failures}")
        assertEquals("|", r.strConstants["SEED_STR"])
    }

    @Test
    fun `未注册的字符串标识符初值仍记失败`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS CONST NOT_THERE = SOMETHING_UNDEFINED")),
        )
        assertEquals(0, r.declared)
        assertEquals(1, r.failures.size)
        // 初值段的未注册标识符先按「可等待下一轮」处理（与整型尺寸段同口径，
        // spec: ErhLoader.cs:315-321 重新入队 / :334-337 整轮无进展才降级告警），
        // 耗尽重试后落为「依赖的常量无法解析」，仍是失败——不许静默放行。
        assertTrue(
            r.failures.single().message.contains("不能求值为常量") ||
                r.failures.single().message.contains("依赖的常量无法解析"),
            "实际：${r.failures.single().message}",
        )
    }

    @Test
    fun `数组字符串 CONST 不折叠为单值`() {
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(decl("#DIMS CONST ARR_STR = \"a\", \"b\"")),
        )
        assertEquals(1, r.declared, "失败：${r.failures}")
        assertEquals(null, r.strConstants["ARR_STR"])
    }

    // ------------------------------------------------------------------
    // 4) 语料真值（缺语料时跳过，不硬编码绝对路径）
    // ------------------------------------------------------------------

    @Test
    fun `真实 emuera config 该条为 YES`() {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(corpus != null && corpus.isNotEmpty(), "未设 ERATW_CORPUS：跳过真实 emuera.config 读数")
        val erbRoot = Path.of(corpus!!)
        val gameRoot = erbRoot.parent ?: erbRoot
        val overrides = RootedBuiltinHost(gameRoot).configOverrides
        val raw = overrides.entries.firstOrNull {
            it.key.trim().uppercase() == "USE THE BINARY FORMAT FOR SAVING DATA"
        }
        Assumptions.assumeTrue(raw != null, "覆盖表里没有该条（语料布局与预期不符）：${overrides.size} 行")
        // 语料原文 emuera.config:60 = USE THE BINARY FORMAT FOR SAVING DATA:YES
        assertEquals("YES", raw!!.value.trim())
        assertTrue(SystemVariableBootstrap.systemSaveInBinaryFrom(overrides))
    }

    @Test
    fun `语料 GRAPHIC 效果分隔符两条声明在同一次装载里都注册`() {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(corpus != null && corpus.isNotEmpty(), "未设 ERATW_CORPUS：跳过语料声明")
        val erbRoot = Path.of(corpus!!)
        val gameRoot = erbRoot.parent ?: erbRoot
        val cfg = DimParseConfig(
            systemSaveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(
                RootedBuiltinHost(gameRoot).configOverrides,
            ),
        )
        val store = VariableStore()
        val r = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIMS CONST EFFNAME_SPLIT = \",\"", "ステータス表示関連/グラフィック特殊効果.ERH", 19),
                decl("#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT ; 效果分隔符", "ステータス表示関連/グラフィック特殊効果.ERB", 64),
                decl("#DIM CHARADATA SAVEDATA CHILD_SEX, 101", "DIM.ERH", 468),
            ),
            cfg,
        )
        assertEquals(3, r.declared, "开关取值 ${cfg.systemSaveInBinary}；失败：${r.failures}")
        assertEquals(",", r.strConstants["C_SPLITWORD"])
    }
}
