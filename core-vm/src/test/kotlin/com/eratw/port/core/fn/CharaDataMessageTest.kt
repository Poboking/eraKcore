package com.eratw.port.core.fn

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SAVE-05：`CHKCHARADATA` 的**返回码 ↔ RESULTS:0 文案**一一对应（钉原版口径）。
 *
 * 权威链（行号 2026-10-23 对 reference 逐条复核）：
 * - `CHKCHARADATA` = `CheckdataStrMethod(EraSaveFileType.CharVar)`
 *   （`FunctionMethodCreator.cs:9079`；类体 `:3541-3560`，实参类型 `string` :3548）。
 * - `CheckdataStrMethod.GetIntValue`（`FunctionMethodCreator.cs:3553-3558`）：
 *   `CheckData(str, CharVar)` → `RESULTS = DataMes`（:3557）→ `return (long)State`（:3558）。
 * - `CheckData(string, type)`（`VariableEvaluator.cs:2117-2136`）：CharVar ⇒
 *   `getSaveDataPathC(s)`（:2054-2057，`Program.DatDir + "chara_" + s + ".dat"`）→
 *   `CheckDataByFilename`（:2159）。**只组一条路径**，不做双候选。
 * - `CheckDataByFilename`（`VariableEvaluator.cs:2159-2229`）判定序与文案：
 *   1. `!File.Exists` → `FILENOTFOUND` + 字面量 `"----"`（:2162-2166）；
 *   2. `EraBinaryDataReader.CreateReader == null`（非二进制容器，:2176）⇒ **文本分支没有类别可比**：
 *      UniqueCode（:2179-2184）→ 版本（:2186-2191）→ OK + `ReadString()`（:2192-2194）；
 *   3. 二进制：`ReadFileType() != type` → `ETC_ERROR` + `Lang.Error.CorruptedSaveData`（:2196-2202）；
 *   4. `!UniqueCodeEqualTo` → `GAME_ERROR` + `Lang.Error.DifferentGame`（:2203-2208，
 *      判定本体 `GameBase.cs:60-67`，存档侧写 0 豁免）；
 *   5. `!CheckVersion` → `VIRSION_ERROR` + `Lang.Error.DifferentVersion`（:2209-2215，
 *      判定本体 `GameBase.cs:69-80`）；
 *   6. 全通过 → `OK` + 存档描述（:2216-2218）。
 * - `EraDataResult.DataMes` 缺省是空串（`EraDataResult.cs:7`），`EraDataState` 序号
 *   `OK, FILENOTFOUND, GAME_ERROR, VIRSION_ERROR, ETC_ERROR` = 0..4（`EraDataState.cs:3-10`）。
 * - 官方文案（使用者面，本项目定调中文化）逐字取 `lang.emuera-zhs.xml`：
 *   `Error.DifferentGame` :326、`Error.DifferentVersion` :327、`Error.CorruptedSaveData` :328；
 *   声明侧（日文缺省）`Lang.cs:1486 / :1490 / :1494`。
 *
 * 语料消费点（只读普查，共 3 处）：
 * `ERB/コマンド関連/COMF/日常系/COMF444 女の子を物色.ERB:208`（`IF FIND_CHARADATA(...)` 后
 * `CHKCHARADATA RESULTS:0` 的 RESULTS:0 直接进 `PRINTBUTTON "[%RESULTS:0%]"` ⇒ 文案是玩家可见的
 * 按钮文本）、同文件 `:388`（`STRFIND RESULTS:0, "："` 取描述前半，OK 支的文案参与解析）、
 * `ERB/fromEN/QOL/QoL_Misc.ERB:1185`（与 :208 同形的英文移植）。
 *
 * 两步交付（契约 §3.1）：本文件**先**以断言改前自造文案（「存档文件已损坏」「存档的游戏代码不同」）
 * 的形态落盘，用来钉住现状读数（那版共 6 处断言，即 `CharaFileMethods.kt` 改前 :118/:123 →
 * 「存档文件已损坏」、:127 → 「存档的游戏代码不同」的实际输出；其余三支本来就与官方一致）；
 * 改实现后把这几处断言换成原版口径，
 * 最终留档钉的是原版的**码 + 文案对应**与 :2196 → :2203 → :2209 的判定先后。
 */
class CharaDataMessageTest {

    // ------------------------------------------------------------------
    // 装置（纯 JVM，不吃语料环境变量）
    // ------------------------------------------------------------------

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private class MemOps : FileOps {
        val files = LinkedHashMap<String, String>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun readText(path: String): String? = files[path]
        override fun writeText(path: String, text: String, append: Boolean): Boolean {
            files[path] = if (append) (files[path] ?: "") + text else text
            return true
        }
        override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
    }

    private class MsgHost(private val ops: FileOps, private val raw: Map<String, ByteArray>) : BuiltinHost {
        override val fileOps: FileOps? get() = ops
        override fun readFileBytes(path: String): ByteArray? = raw[path]
    }

    private fun ctx(store: VariableStore, ops: MemOps, raw: Map<String, ByteArray>): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, MsgHost(ops, raw))
    }

    private fun code(c: BuiltinEvalContext, name: String): Long =
        (c.callFunction("CHKCHARADATA", listOf(Value.StrV(name))) as Value.IntV).v

    private fun results0(store: VariableStore): String = store.readStr("RESULTS", longArrayOf(0))

    /** 存档码 123 / 版本 2000 / 兼容下限 100（`GameBaseGate` 三条放行都走到位）。 */
    private val strictBase = GameBaseData(gameCode = 123L, version = 2000L, versionDefined = true, allowVersion = 100L)

    /** 造一份二进制角色档字节。 */
    private fun charaBytes(uniqueCode: Long, scriptVersion: Long, text: String): ByteArray =
        SaveFormat.write(SaveData(SaveFileKind.CHARVAR, uniqueCode, scriptVersion, text, compressed = false))

    private fun charaBytesOfKind(kind: SaveFileKind, uniqueCode: Long, scriptVersion: Long, text: String): ByteArray =
        SaveFormat.write(SaveData(kind, uniqueCode, scriptVersion, text, compressed = false))

    /** 把 `chara_<name>.dat` 挂到宿主（路径口径 `Program.DatDir` + `chara_` 前缀，VariableEvaluator.cs:2054-2057）。 */
    private class Fixture(name: String, bytes: ByteArray) {
        val ops = MemOps()
        val raw = mapOf("dat/chara_$name.dat" to bytes)

        init {
            ops.files["dat/chara_$name.dat"] = "bin"
        }
    }

    // ------------------------------------------------------------------
    // 逐分支：码 + 文案
    // ------------------------------------------------------------------

    /** :2162-2166 —— 文件不存在 → `FILENOTFOUND(1)` + 字面量 `"----"`（不走语言包）。 */
    @Test
    fun `不存在给码1与破折号`() {
        val store = VariableStore()
        val ops = MemOps()
        val c = ctx(store, ops, emptyMap())
        assertEquals(1L, code(c, "NOPE"))
        assertEquals("----", results0(store))
    }

    /** :2196-2202 —— 二进制容器类别不符 → `ETC_ERROR(4)` + `Error.CorruptedSaveData`（lang :328）。 */
    @Test
    fun `二进制类别不符给码4与CorruptedSaveData文案`() {
        val bytes = charaBytesOfKind(SaveFileKind.NORMAL, 0L, 0L, "普通档")
        val f = Fixture("BIN", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(4L, code(c, "BIN"))
        assertEquals("存档数据已损坏", results0(store))
    }

    /**
     * 「读不出」有两条出口，别再并成一条（总账 SAVE-06 收口后本条随 `930cdac5` 改钉）：
     * - **短文本档不是「读不出」**：`CreateReader` 因 `fs.Length < 16` 返回 null
     *   （`EraBinaryDataReader.cs:790-792`）后，原版照旧走文本分支
     *   （`VariableEvaluator.cs:2176-2195`，文本分支没有类别可比）⇒ UniqueCode 0 豁免
     *   （`GameBase.cs:60-67`）→ 版本 0 对 `strictBase` 不符（`:2186-2191`）
     *   ⇒ **码 3 + `Error.DifferentVersion`**。改前移植侧在这里直接判 null 给码 4，
     *   那是 SAVE-06 的缺陷本体，不是原版口径。
     * - **魔数命中但头被截断**才是 `peek` 给 null（同一行 `< 16` 长度闸命中容器魔数）
     *   ⇒ 码 4 + `CorruptedSaveData`，与 `SaveDataProbe.checkBytes` 同一文案。
     */
    @Test
    fun `短文本档走文本分支给码3而截断二进制头才给码4`() {
        val textBytes = "0\n0\n短\n".toByteArray(Charsets.UTF_8) // 8 字节，但三行齐全 = 合法文本档
        val f = Fixture("SHORT", textBytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(3L, code(c, "SHORT"), "文本分支比到版本即止（:2186-2191），不是「读不出容器」")
        assertEquals("存档版本不同", results0(store))

        val truncated = charaBytes(uniqueCode = 0L, scriptVersion = 0L, text = "截断档").copyOf(12)
        val f2 = Fixture("TRUNC", truncated)
        val store2 = VariableStore(VariableRegistry(gameBase = strictBase))
        val c2 = ctx(store2, f2.ops, f2.raw)
        assertEquals(4L, code(c2, "TRUNC"), "容器魔数命中但头不足 16 字节 ⇒ peek null（:790-792）")
        assertEquals("存档数据已损坏", results0(store2))
    }

    /** :2203-2208 —— 存档码不符 → `GAME_ERROR(2)` + `Error.DifferentGame`（lang :326）；版本同样不符也不报 3。 */
    @Test
    fun `存档码不符给码2与DifferentGame文案`() {
        val bytes = charaBytes(uniqueCode = 456L, scriptVersion = 7L, text = "别游戏的角色档")
        val f = Fixture("CODE", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(2L, code(c, "CODE"))
        assertEquals("试图读取的存档属于另一个游戏", results0(store))
    }

    /** `GameBase.cs:60-67`：存档侧写 0 豁免 ⇒ 不因存档码报 2，继续往下判（这里判到 3）。 */
    @Test
    fun `存档码0豁免后继续判版本`() {
        val bytes = charaBytes(uniqueCode = 0L, scriptVersion = 7L, text = "无存档码的角色档")
        val f = Fixture("ZERO", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(3L, code(c, "ZERO"))
        assertEquals("存档版本不同", results0(store))
    }

    /** :2209-2215 —— 版本不符 → `VIRSION_ERROR(3)` + `Error.DifferentVersion`（lang :327；改前该条已是官方原文，钉住防回退）。 */
    @Test
    fun `版本不符给码3`() {
        val bytes = charaBytes(uniqueCode = 123L, scriptVersion = 7L, text = "旧版角色档")
        val f = Fixture("VER", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(3L, code(c, "VER"))
        assertEquals("存档版本不同", results0(store))
    }

    /** :2216-2218 —— 全通过 → `OK(0)` + 存档描述文本进 RESULTS:0。 */
    @Test
    fun `通过给码0与描述文本`() {
        val bytes = charaBytes(uniqueCode = 123L, scriptVersion = 2000L, text = "村山メイ：テスト")
        val f = Fixture("OK", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, f.ops, f.raw)
        assertEquals(0L, code(c, "OK"))
        assertEquals("村山メイ：テスト", results0(store))
    }

    /**
     * :2176-2195 —— 文本格式角色档（非二进制容器）**不比类别**：
     * [strictBase] 下同样的内容若写成二进制 NORMAL 档会先撞 `ETC_ERROR(4)`
     * （见 `二进制类别不符给码4与CorruptedSaveData文案`），文本档没有类别这一位，
     * 只过 UniqueCode / 版本两关便 `OK`。
     */
    @Test
    fun `文本格式不比类别`() {
        val text = "123\n2000\n旧版文本存档\n"
        val ops = MemOps()
        val path = "dat/chara_OLD.dat"
        ops.files[path] = text
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        val c = ctx(store, ops, mapOf(path to text.toByteArray(Charsets.UTF_8)))
        assertEquals(0L, code(c, "OLD"))
        assertEquals("旧版文本存档", results0(store))
    }

    /**
     * 判定序（:2196-2215 的三支先后）：类别、存档码、版本三者同时错时，只报最靠前的 `ETC_ERROR(4)`；
     * 类别对、码与版本同时错时报 `GAME_ERROR(2)`。
     */
    @Test
    fun `三支同时错时按类别、码、版本的先后只报一个`() {
        val badKind = charaBytesOfKind(SaveFileKind.NORMAL, 456L, 7L, "全错")
        val kindFix = Fixture("KIND", badKind)
        val s1 = VariableStore(VariableRegistry(gameBase = strictBase))
        assertEquals(4L, code(ctx(s1, kindFix.ops, kindFix.raw), "KIND"))
        assertEquals("存档数据已损坏", results0(s1))

        val badCode = charaBytes(456L, 7L, "码与版本都错")
        val codeFix = Fixture("CODEVER", badCode)
        val s2 = VariableStore(VariableRegistry(gameBase = strictBase))
        assertEquals(2L, code(ctx(s2, codeFix.ops, codeFix.raw), "CODEVER"))
        assertEquals("试图读取的存档属于另一个游戏", results0(s2))
    }

    /**
     * `FunctionMethodCreator.cs:3557` 的赋值语义：`RESULTS` 只写第 0 项（`VEvaluator.RESULTS`
     * setter = `DataStringArray[2][0] = value`，`VariableEvaluator.cs:75-78`），其余项不动。
     */
    @Test
    fun `只写RESULTS的第0项`() {
        val bytes = charaBytes(456L, 2000L, "另一游戏")
        val f = Fixture("R0", bytes)
        val store = VariableStore(VariableRegistry(gameBase = strictBase))
        store.writeStr("RESULTS", longArrayOf(1), "别的槽")
        val c = ctx(store, f.ops, f.raw)
        assertEquals(2L, code(c, "R0"))
        assertEquals("试图读取的存档属于另一个游戏", results0(store))
        assertEquals("别的槽", store.readStr("RESULTS", longArrayOf(1)))
    }
}
