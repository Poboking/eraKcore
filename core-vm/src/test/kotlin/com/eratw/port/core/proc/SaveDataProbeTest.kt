package com.eratw.port.core.proc

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 存档槽头探测（`CHKDATA` found 分支）。
 *
 * 断言的是 `CheckDataByFilename`（VariableEvaluator.cs:2159-2230）逐个分支的**具体读数**
 * （EraDataState 状态码 + RESULTS:0 描述文本），不是「不抛异常」：
 * - 不存在 → `1` + `"----"`（:2162-2166）
 * - 类别不符 → `4` + 损坏文案（:2196-2201）；文本格式头不比类别（:2176-2194 无类型字段）
 * - 游戏码不符 → `2`（:2202-2206，GameBase.cs:60-67，存档码 0 豁免）
 * - 版本不符 → `3`（:2207-2212，GameBase.cs:69-80）
 * - 全过 → `0` + 存档描述（:2213-2215）
 * - 宿主完全无探测能力 → 抛 CodeEE（禁止伪造 FILENOTFOUND 把推进伪装成回退）
 *
 * ## 存档目录口径（M4-L savefolder 重钉）
 *
 * 原版 `SavDir` 只有一个值、两条互斥分支（`Config.cs:471-477` 走 `ExeDir + sav`、
 * `:478-481` 走 `ExeDir`），`CheckData` 按类别只组**一条** filename
 * （`VariableEvaluator.cs:2138-2156`），落盘与读取用的也是同一条（`SaveTo` :2646 /
 * `LoadFrom` :2689 / `DelData` :2712）。⇒ 凡「槽位在哪一侧」的读数都必须显式给出
 * `UseSaveFolder`，不再靠双候选蒙对：
 * - 函数层用例经 [fnCtx] 的 `useSaveFolder` 形参把值装进 [SaveWiring]
 *   （`CHKDATA` 的唯一取值通道 = `core/fn/SaveMethods.kt:54-56`）；
 * - 同时用 [hostWithFlag] 把同一个开关写进临时根目录的 `emuera.config`，让**宿主条目表**
 *   （`BootHosts.kt:197`）与会话接线取值一致 —— 原版只有一个进程级 `SavDir`
 *   （`Config.cs:296` 的 `SetConfig` 体内），夹具不一致就是自造分歧；
 *   这条也让本文件对「组长把 `BootHosts.kt:224` 的双候选接成单候选」那次改动免疫。
 *
 * 合成存档用 `SaveFormat.write`（二进制容器，版本 1808）；真实语料用例只在
 * `ERATW_GAMEDIR`（或 `ERATW_CORPUS` 的同级回落）可见时执行，且**只读不写** `sav/`。
 */
private class ProbeBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("ProbeBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/**
 * 函数层夹具。`useSaveFolder` 非空 ⇒ 装一份 [SaveWiring]（会话级接线），
 * 让 `CHKDATA` 按 `Config.cs:471-481` 的单候选口径探测；null ⇒ 不装，
 * 探测层保持「装配缺失时的双候选保守回落」（契约 §2.9 要求 2 保留的口径，
 * 只在纯装置场合可达，见 `SaveDataProbe.candidatePaths` 的 KDoc）。
 */
private fun fnCtx(
    host: BuiltinHost,
    gameBase: GameBaseData = GameBaseData(),
    useSaveFolder: Boolean? = null,
): BuiltinEvalContext {
    val store = VariableStore(registry = VariableRegistry(gameBase = gameBase))
    if (useSaveFolder != null) {
        SaveWiring.install(
            store,
            SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = useSaveFolder),
            20,
        )
    }
    val base = ProbeBaseCtx().also { it.variables = store }
    return BuiltinEvalContext(base, store, host)
}

/**
 * 造一个**条目表与开关一致**的宿主：先写临时根目录的 `emuera.config`
 * （标签 = `ConfigData.cs:97` 注册的英文标签 `Use sav folder` 的大写形），再建宿主 ——
 * 宿主在构造期一次读盘（`BootHosts.kt:137-138`），顺序反了就读不到。
 * 只在临时目录里造，语料目录一个字节都不写。
 */
private fun hostWithFlag(root: Path, useSaveFolder: Boolean): RootedBuiltinHost {
    Files.writeString(
        root.resolve("emuera.config"),
        "USE SAV FOLDER:" + (if (useSaveFolder) "YES" else "NO") + "\n",
        StandardCharsets.UTF_8,
    )
    return RootedBuiltinHost(root)
}

/** 合成二进制存档字节（NORMAL/GLOBAL/VAR/CHARVAR 四类共用骨架）。 */
private fun binarySave(
    kind: SaveFileKind,
    code: Long = 0L,
    version: Long = 5L,
    text: String,
): ByteArray = SaveFormat.write(
    SaveData(fileType = kind, uniqueCode = code, scriptVersion = version, text = text, compressed = true),
)

/** eraTW 实机 GAMEBASE 读数（CsvLoaderTest:39-41 的离线核对值）。 */
private val REAL_GB = GameBaseData(gameCode = 7153L, version = 0L, allowVersion = 12L)

class SaveDataProbeTest {

    private fun tempRoot(): Path = Files.createTempDirectory("eratw-savprobe")

    private fun writeSave(root: Path, rel: String, bytes: ByteArray): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        return p
    }

    // ------------------------------------------------------------------
    // CHKDATA 函数层（fn → host/probe 的读数分发）
    // ------------------------------------------------------------------

    @Test
    fun `CHKDATA 宿主完全无探测能力时抛错不伪造不存在`() {
        // 老红线：bare BuiltinHost（无 fileOps / 无 checkSaveData 覆写）必须抛，
        // 不许静默答 FILENOTFOUND（那会把「宿主没接」伪装成「槽位是空的」）。
        val ctx = fnCtx(object : BuiltinHost {})
        val e = assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(0))) }
        assertTrue(e.message!!.contains("宿主接线"), "报错文案应指回接线缺口：${e.message}")
    }

    @Test
    fun `CHKDATA 空槽位返回 1 与四横线`() {
        // VariableEvaluator.cs:2162-2166。空目录时两条分支（Config.cs:471-477 / :478-481）
        // 都必然落空 ⇒ 开关取什么都不影响本用例，故不装接线。
        val ctx = fnCtx(RootedBuiltinHost(tempRoot()))
        assertEquals(1L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(5))) as Value.IntV).v)
        assertEquals("----", ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 槽位存在但读不出时返回 4 与加载错误`() {
        // VariableEvaluator.cs:2219-2228：CheckDataByFilename 的 catch 分支一律 ETC_ERROR，
        // 描述取 LoadError（zhs :329 =「加载时发生错误」）。模拟「File.Exists 为真、Open 失败」：
        // 委托宿主的 exists/isRegularFile 照常，只让 readFileBytes 返回 null。
        val root = tempRoot()
        val file = root.resolve(SaveDataProbe.saveName(9))
        Files.write(file, ByteArray(32))
        val rooted = RootedBuiltinHost(root)
        val host = object : BuiltinHost by rooted {
            override fun readFileBytes(path: String): ByteArray? = null
        }
        val ctx = fnCtx(host, useSaveFolder = false)
        assertEquals(4L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(9))) as Value.IntV).v)
        assertEquals(SaveDataProbe.MES_LOAD_ERROR, ctx.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("加载时发生错误", SaveDataProbe.MES_LOAD_ERROR)

        val probe = SaveDataProbe.check(host, SaveFileKind.NORMAL, 9L, GameBaseData(), false)
        assertNotNull(probe, "fileOps 在位 ⇒ 探测能力在位，不得回 null 冒充未接线")
        assertEquals(EraDataState.ETC_ERROR, probe.state)
        assertEquals(SaveDataProbe.MES_LOAD_ERROR, probe.mes)
    }

    @Test
    fun `CHKDATA 命中根目录槽位返回 0 与描述文本`() {
        // OK 分支 :2213-2215；存档码 0 走 GameBase.cs:62-64 的豁免，version=0 的
        // 未定义版本走 :71-73 的放行（存档版本 5 != 1000）。
        // 目录口径：**UseSaveFolder=false ⇒ SavDir = Program.ExeDir**（Config.cs:478-481；
        // 注册默认即 false，ConfigData.cs:97）⇒ 根目录是唯一候选，本用例不再靠双候选命中。
        val root = tempRoot()
        writeSave(root, SaveDataProbe.saveName(7), binarySave(SaveFileKind.NORMAL, text = "第七槽描述"))
        val ctx = fnCtx(hostWithFlag(root, false), useSaveFolder = false)
        assertEquals(0L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(7))) as Value.IntV).v)
        assertEquals("第七槽描述", ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 命中 sav 子目录槽位返回 0 与描述文本`() {
        // 目录口径：**UseSaveFolder=true ⇒ SavDir = ExeDir + "sav" + sep**（Config.cs:471-477），
        // 路径拼接见 VariableEvaluator.cs:2027-2031。开关关掉时同一份字节必须判不存在
        // （Config.cs:478-481 的 else 分支 + :2162 的一次 File.Exists）。
        val root = tempRoot()
        writeSave(root, "sav/" + SaveDataProbe.saveName(2), binarySave(SaveFileKind.NORMAL, text = "CH20260326 槽二"))
        val ctx = fnCtx(hostWithFlag(root, true), useSaveFolder = true)
        assertEquals(0L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(2))) as Value.IntV).v)
        assertEquals("CH20260326 槽二", ctx.store.readStr("RESULTS", longArrayOf(0)))
        // 反向：同一批字节按 false 口径查同一槽 ⇒ FILENOTFOUND(1)。这里直接打**探测层**
        // （不带宿主存在性闸门），钉的就是 `Config.cs:478-481` 的分支本身：
        // SavDir = ExeDir 时 `sav/save02.sav` 不在探测范围内（VariableEvaluator.cs:2162 的一次 File.Exists）。
        assertEquals(
            EraDataState.FILENOTFOUND,
            SaveDataProbe.check(RootedBuiltinHost(root), SaveFileKind.NORMAL, 2L, GameBaseData(), false)?.state,
            "UseSaveFolder=false 时 sav/ 的档不算存在（Config.cs:478-481）",
        )
    }

    @Test
    fun `CHKDATA 游戏码不符返回 2`() {
        // :2202-2206：存档码 999 既不是 0 也不是 gameBase.gameCode=123。
        // 档在 sav/ ⇒ 夹具开关必须是 true（Config.cs:471-477），否则探测目录根本不含它。
        val root = tempRoot()
        writeSave(root, "sav/" + SaveDataProbe.saveName(8), binarySave(SaveFileKind.NORMAL, code = 999L, text = "别家游戏"))
        val ctx = fnCtx(hostWithFlag(root, true), GameBaseData(gameCode = 123L), useSaveFolder = true)
        assertEquals(2L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(8))) as Value.IntV).v)
        assertEquals(SaveDataProbe.MES_DIFFERENT_GAME, ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 版本不符返回 3`() {
        // :2207-2212 + GameBase.cs:69-80：version=50（已定义）、allowVersion=100、存档版本 7
        // → 三个放行条件全不成立 → VIRSION_ERROR。档在根目录 ⇒ 开关 false（Config.cs:478-481）。
        val root = tempRoot()
        writeSave(root, SaveDataProbe.saveName(4), binarySave(SaveFileKind.NORMAL, code = 123L, version = 7L, text = "旧版档"))
        val ctx = fnCtx(hostWithFlag(root, false), GameBaseData(gameCode = 123L, version = 50L, allowVersion = 100L), useSaveFolder = false)
        assertEquals(3L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(4))) as Value.IntV).v)
        assertEquals(SaveDataProbe.MES_DIFFERENT_VERSION, ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 头损坏返回 4`() {
        // 非魔数且非三行文本形 → peek null → ETC_ERROR（原版的 catch Exception :2225-2229，
        // 文案 = Lang.Error.CorruptedSaveData，lang/emuera.zhs.xml:328）。档在根目录 ⇒ 开关 false（Config.cs:478-481）。
        val root = tempRoot()
        writeSave(root, SaveDataProbe.saveName(6), ByteArray(32) { 0xAB.toByte() })
        val ctx = fnCtx(hostWithFlag(root, false), useSaveFolder = false)
        assertEquals(4L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(6))) as Value.IntV).v)
        assertEquals(SaveDataProbe.MES_CORRUPTED, ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 参数越界仍先于探测报错`() {
        // FunctionMethodCreator.cs:3527-3534：负值 ArgIsNegative / 过大 ArgIsTooLarge。
        val ctx = fnCtx(RootedBuiltinHost(tempRoot()))
        assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(-1))) }
        assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(Long.MAX_VALUE))) }
    }

    /**
     * 三条描述文本逐字钉死 `Lang.Error` 中文语言包原文（Results 可被脚本观察，
     * `FunctionMethodCreator.cs:3536`）：CorruptedSaveData / DifferentGame / DifferentVersion
     * = lang/emuera.zhs.xml:328 / :326 / :327。防止回退到自造措辞（2026-10-23 勘误）。
     */
    @Test
    fun `探测描述文本取 Lang 原文`() {
        assertEquals("存档数据已损坏", SaveDataProbe.MES_CORRUPTED)
        assertEquals("试图读取的存档属于另一个游戏", SaveDataProbe.MES_DIFFERENT_GAME)
        assertEquals("存档版本不同", SaveDataProbe.MES_DIFFERENT_VERSION)
        assertEquals("----", SaveDataProbe.MES_NOT_FOUND) // :2164-2165 的字面量
    }

    // ------------------------------------------------------------------
    // SaveDataProbe.check —— SaveFileKind 四类与文本格式头
    // ------------------------------------------------------------------

    @Test
    fun `GLOBAL 槽走 global sav 且不吃下标`() {
        // getSaveDataPathG（:2018-2021）：无论 no 传什么都命中同一文件。
        // 开关取 true ⇒ 唯一候选 `sav/global.sav`（Config.cs:471-477）；候选集合本身先钉住，
        // 再让探测走这条候选（不再靠双候选命中第二项）。
        val root = tempRoot()
        writeSave(root, "sav/global.sav", binarySave(SaveFileKind.GLOBAL, text = "全局档描述"))
        val host = RootedBuiltinHost(root)
        assertEquals(
            listOf("sav/global.sav"),
            SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, true),
            "GLOBAL 的候选必须只有一条且不吃下标",
        )
        val r = SaveDataProbe.check(host, SaveFileKind.GLOBAL, 42L, GameBaseData(), true)
        assertNotNull(r)
        assertEquals(EraDataState.OK, r.state)
        assertEquals("全局档描述", r.mes)
        // 同一槽按 false 口径查：候选变成根目录那一条（Config.cs:478-481），档在 sav/ ⇒ 不存在
        assertEquals(listOf("global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, false))
        assertEquals(
            EraDataState.FILENOTFOUND,
            SaveDataProbe.check(host, SaveFileKind.GLOBAL, 42L, GameBaseData(), false)?.state,
        )
    }

    @Test
    fun `VAR 与 CHARVAR 槽落在 datDir 下`() {
        // getSaveDataPathV/C（:2039-2047）拼的是 Program.DatDir（Program.cs:216-218 = ExeDir\dat\），
        // **与 UseSaveFolder 无关**；目录前缀取 host.datDir（默认 dat）。
        val root = tempRoot()
        writeSave(root, "dat/var_02.dat", binarySave(SaveFileKind.VAR, text = "变量档"))
        writeSave(root, "dat/chara_03.dat", binarySave(SaveFileKind.CHARVAR, text = "角色档"))
        val host = RootedBuiltinHost(root)
        for (flag in listOf(true, false, null)) {
            assertEquals(listOf("dat/var_02.dat"), SaveDataProbe.candidatePaths(host, SaveFileKind.VAR, 2L, flag))
            assertEquals(listOf("dat/chara_03.dat"), SaveDataProbe.candidatePaths(host, SaveFileKind.CHARVAR, 3L, flag))
        }
        assertEquals(EraDataState.OK, SaveDataProbe.check(host, SaveFileKind.VAR, 2L, GameBaseData())?.state)
        assertEquals("变量档", SaveDataProbe.check(host, SaveFileKind.VAR, 2L, GameBaseData())?.mes)
        assertEquals(EraDataState.OK, SaveDataProbe.check(host, SaveFileKind.CHARVAR, 3L, GameBaseData())?.state)
        assertEquals("角色档", SaveDataProbe.check(host, SaveFileKind.CHARVAR, 3L, GameBaseData())?.mes)
        // 槽位没写过 → 不存在分支
        assertEquals(EraDataState.FILENOTFOUND, SaveDataProbe.check(host, SaveFileKind.VAR, 9L, GameBaseData())?.state)
    }

    @Test
    fun `二进制头类别与查询类别不符返回 4`() {
        // :2196-2201：save09.sav 里装着 GLOBAL 类型的存档，按 NORMAL 查询。
        // 档在根目录 ⇒ 单候选口径用 false（Config.cs:478-481）。
        val root = tempRoot()
        writeSave(root, SaveDataProbe.saveName(9), binarySave(SaveFileKind.GLOBAL, text = "放错目录的全局档"))
        val host = RootedBuiltinHost(root)
        val r = SaveDataProbe.check(host, SaveFileKind.NORMAL, 9L, GameBaseData(), false)
        assertNotNull(r)
        assertEquals(EraDataState.ETC_ERROR, r.state)
        assertEquals(SaveDataProbe.MES_CORRUPTED, r.mes)
        // 开关打开时这条档不在探测范围内（唯一候选是 sav/save09.sav）⇒ 先判不存在
        assertEquals(EraDataState.FILENOTFOUND, SaveDataProbe.check(host, SaveFileKind.NORMAL, 9L, GameBaseData(), true)?.state)
    }

    @Test
    fun `文本格式头不比类别直接走码与版本判定`() {
        // 原版文本分支（EraDataReader，:2176-2194）没有 ReadFileType 这一步，
        // 故文本档查 GLOBAL 也不报类别不符——UniqueCode 先行、版本其次、描述最后。
        // 档在根目录 ⇒ false 单候选（Config.cs:478-481）。
        val root = tempRoot()
        writeSave(root, "global.sav", "0\n7\n文本档描述\n".toByteArray(Charsets.UTF_8))
        val r = SaveDataProbe.check(RootedBuiltinHost(root), SaveFileKind.GLOBAL, 0L, GameBaseData(), false)
        assertNotNull(r)
        assertEquals(EraDataState.OK, r.state)
        assertEquals("文本档描述", r.mes)
    }

    @Test
    fun `宿主只判定存在性时 found 返回 null 交函数层补全`() {
        // BootHosts 契约：不存在 → FILENOTFOUND；命中 → null（宿主看不到 gameBase，不猜状态码）。
        // 夹具给宿主条目 false ⇒ 根目录那一条候选（Config.cs:478-481）；这条读数在
        // 「宿主接线由双候选改单候选」前后都不变（BootHosts.kt:224，交付报告需组长接线）。
        val root = tempRoot()
        writeSave(root, SaveDataProbe.saveName(1), binarySave(SaveFileKind.NORMAL, text = "接线检查"))
        val host = hostWithFlag(root, false)
        assertEquals(EraDataState.FILENOTFOUND, host.checkSaveData(2L, SaveFileKind.NORMAL)?.state)
        assertNull(host.checkSaveData(1L, SaveFileKind.NORMAL))
        assertEquals(false, host.useSaveFolder, "宿主条目现值与夹具一致（BootHosts.kt:197）")
    }

    // ------------------------------------------------------------------
    // 真实语料（ERATW_GAMEDIR 可见才跑；只读 sav/，不写一个字）
    // ------------------------------------------------------------------

    /** 游戏根目录：`ERATW_GAMEDIR` 显式给出即权威；回落取 `ERATW_CORPUS`（指到 ERB 子树）的父目录。 */
    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    @Test
    fun `语料真实存档槽的探测读数`() {
        val dir = gameDir()
        val sav00 = dir?.resolve("sav/save00.sav")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            sav00 != null && Files.isRegularFile(sav00),
            "缺 ERATW_GAMEDIR/ERATW_CORPUS 或 sav/save00.sav，跳过语料判据",
        )
        val host = RootedBuiltinHost(dir!!)

        // 候选目录口径：`emuera.config:51` 与 `CSV/_default.config:17` 都是 YES
        // ⇒ Config.cs:471-477 的分支 ⇒ SavDir = ExeDir\sav\，**唯一候选就是 sav/ 那一条**
        // （原版 CheckData 只组一条 filename，VariableEvaluator.cs:2138-2156）。
        assertTrue(host.useSaveFolder, "语料条目现值必须是 YES（Config.cs:367 的取值通道）")
        val candidates = SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 0L, host.useSaveFolder)
        assertEquals(listOf("sav/save00.sav"), candidates)
        val ops = host.fileOps
        assertNotNull(ops)
        // 语料根目录实测没有任何 `.sav`（全部存档都在 sav/ 下）⇒ false 分支的候选必然落空
        assertEquals(false, ops.exists("save00.sav"))
        assertEquals(true, ops.exists(candidates[0]))
        assertEquals(EraDataState.FILENOTFOUND, SaveDataProbe.check(host, SaveFileKind.NORMAL, 0L, REAL_GB, false)?.state)

        // 装配缺失时的保守回落（双候选，只在装置场合可达）：第一项不存在、第二项命中。
        assertEquals(listOf("save00.sav", "sav/save00.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 0L))

        // 头字段（与 SaveFormat.kdoc 对 save00.sav 的实测一致：容器版本 1808、类别 Normal）。
        val bytes = host.readFileBytes("sav/save00.sav")
        assertNotNull(bytes)
        val head = SaveFormat.peek(bytes)
        assertNotNull(head)
        assertEquals(true, head.binary)
        assertEquals(1808, head.version)
        assertEquals(SaveFileKind.NORMAL, head.fileType)

        // 用 GAMEBASE 实机读数（gameCode 7153 / allowVersion 12 / version 未定义，
        // CsvLoaderTest:39-41）探测 0 号槽：状态码与描述文本都是钉死的具体读数。
        val r = SaveDataProbe.check(host, SaveFileKind.NORMAL, 0L, REAL_GB, host.useSaveFolder)
        assertNotNull(r)
        assertEquals(EraDataState.OK, r.state)
        assertEquals(head.text, r.mes)
        assertTrue(r.mes.isNotEmpty(), "存档描述文本不应为空串")

        // 同一份字节按 GLOBAL 类别查询 → 类别不符 → ETC_ERROR(4)（:2196-2201）。
        val wrongKind = SaveDataProbe.checkBytes(bytes, SaveFileKind.GLOBAL, REAL_GB)
        assertEquals(EraDataState.ETC_ERROR, wrongKind.state)
        assertEquals(SaveDataProbe.MES_CORRUPTED, wrongKind.mes)

        // 换一个游戏码 → GAME_ERROR(2)（:2202-2206）。
        val otherGame = SaveDataProbe.check(host, SaveFileKind.NORMAL, 0L, GameBaseData(gameCode = 999_999L), host.useSaveFolder)
        assertNotNull(otherGame)
        assertEquals(EraDataState.GAME_ERROR, otherGame.state)
        assertEquals(SaveDataProbe.MES_DIFFERENT_GAME, otherGame.mes)
    }
}
