package com.eratw.port.core.save

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
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

/**
 * M4-L `savefolder` 的验收：**`UseSaveFolder` ⇒ 单一 `SavDir` 口径**（CONTRACT-M4-L §2.9）。
 *
 * ## 断言级结论（每条都有 C# 行号，逐行核对见 `SaveDataProbe` 类 KDoc）
 *
 * `SavDir` 在原版只有一个赋值处、两个互斥分支，且没有任何「另一条目录再试一次」的读取回退：
 * 1. `Config.cs:147` 字段 + `Config.cs:367` 从条目取现值（注册默认 false = `ConfigData.cs:97`），
 *    两行都在 `Config.SetConfig`（`Config.cs:296` 起）的体内 ⇒ 一次装载只算一次；
 * 2. `Config.cs:471-477` `if (UseSaveFolder) SavDir = Program.ExeDir + "sav" + sep`；
 *    `Config.cs:478-481` `else SavDir = Program.ExeDir` —— **true ⇒ 仅 `sav/`，false ⇒ 仅根目录**；
 * 3. `Config.cs:482-485` + `Config.cs:551-590`：开关打开而 `sav` 目录不存在时，原版把根目录的
 *    `global.sav`（:577）与 `save*.sav`（:579-584）`File.Move` 进 `sav` —— 旧位置的文件靠**迁移**
 *    消解，不靠双探测读取。这就是「另一侧不算存在」的官方实现；
 * 4. `VariableEvaluator.cs:2018-2037`：`getSaveDataPathG`（:2020）/ `getSaveDataPath(int)`
 *    （:2027-2031）/ `getSaveDataPath(string)`（:2036）全部只拼 `Config.SavDir`；
 *    `VariableEvaluator.cs:2138-2156` 的 `CheckData` 按类别只组**一条** filename，
 *    `CheckDataByFilename`（:2159-2167）对那一条做**一次** `File.Exists`；
 * 5. 写读删同源：`SaveTo`（:2644-2646）、`LoadFrom`（:2687-2693）、`DelData`（:2710-2713）
 *    用的就是同一个 `getSaveDataPath(index)` ⇒ 探测目录与落盘目录必然相同；
 * 6. 语句调用点：`SAVEDATA` = `Process.cs:1327-1349`（:1344 进 `SaveTo`）、
 *    `LOADDATA` = `Process.cs:1841-1863`（:1852 先 `CheckData(..., Normal)`、:1856 再 `LoadFrom`）
 *    ⇒ 读档前的校验与读档本体共用同一 `SavDir`，没有第二条路径可钻；
 * 7. VAR/CHARVAR 拼 `Program.DatDir`（`VariableEvaluator.cs:2039-2057`；目录定义
 *    `Program.cs:216-218`），该根**不受开关影响** ⇒ 这两类恒为单候选；
 * 8. `Config.ForceSavDir`（`Config.cs:470` 恒为 `ExeDir` + `sav` + sep）只服务
 *    `SAVETEXT`/`LOADTEXT` 的 force 形参（`FunctionMethodCreator.cs:7978`、`:8061`），
 *    与 `.sav` 存档槽无关。
 *
 * ## 移植侧现状（本路实测）
 *
 * - [SaveDataProbe.candidatePaths] 的 `useSaveFolder` 为 `true`/`false` 时已经是**单候选**；
 *   `null`（会话没装 [SaveWiring] 且调用方没交出条目值）保留双候选回落 —— 移植侧自造、
 *   非原版语义，本文件把它钉成「只在装置场合可达」，见 [未装配时保持双候选的保守回落]。
 * - 函数层 `CHKDATA` 从 [SaveWiring] 现读该值（`core/fn/SaveMethods.kt:54-56`），
 *   会话装配把 `ConfigStore` 的条目现值写进接线（`core/proc/GameSession.kt:233-244`）
 *   ⇒ 生产路径（GameSession 必装 SaveWiring）已经是单候选，**假存档槽在函数层已闭合**，
 *   见 [函数层读数不受宿主存在性探测回落影响]。
 * - 唯一残留：`RootedBuiltinHost.checkSaveData`（`core/proc/BootHosts.kt:224`）调用
 *   `candidatePaths` 时不传本参数，宿主存在性检查仍走 `null` 双候选。该文件不在本路文件面
 *   ⇒ 交付报告「需组长接线」（一行改动 + 两处 fixture 需同步，本文件已给出）。
 *
 * 语料侧读数（只读，一字节不动）：`emuera.config:51` 与 `CSV/_default.config:17` 都是 YES
 * ⇒ 原版 `SavDir` = `ExeDir\sav\`；实测语料根目录**没有任何** `.sav`，全部 5 份存档
 * （`save00` ~ `save03` + `save1000`）都在 `sav/` 下 ⇒ 单候选口径与语料实存一致。
 */
class SaveFolderWiringTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 只做 [EvalContext] 的空壳：函数调用不经过它（走 [BuiltinEvalContext]）。 */
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun chkCtx(
        host: BuiltinHost,
        useSaveFolder: Boolean?,
        gameBase: GameBaseData = GameBaseData(),
    ): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore(registry = VariableRegistry(gameBase = gameBase))
        if (useSaveFolder != null) {
            SaveWiring.install(
                store,
                SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = useSaveFolder),
                20,
            )
        }
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host) to store
    }

    private fun iv(v: Value): Long = (v as Value.IntV).v

    /** 合成二进制存档字节（与 `SaveDataProbeTest` 同装置）。 */
    private fun binarySave(kind: SaveFileKind, text: String): ByteArray = SaveFormat.write(
        SaveData(fileType = kind, uniqueCode = 0L, scriptVersion = 5L, text = text, compressed = true),
    )

    private fun write(root: Path, rel: String, bytes: ByteArray) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
    }

    /**
     * 造一个**条目表与开关一致**的宿主：先把 `USE SAV FOLDER` 写进临时根目录的
     * `emuera.config`（标签口径 = `ConfigData.cs:97` 的英文标签 `Use sav folder` 大写形，
     * 与 `SaveBuiltinsTest` 的夹具同形），再建宿主 —— 宿主在构造期一次读盘
     * （`BootHosts.kt:137-138`），所以必须**先写文件**。
     */
    private fun hostWith(root: Path, useSaveFolder: Boolean): RootedBuiltinHost {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("emuera.config"),
            "USE SAV FOLDER:" + (if (useSaveFolder) "YES" else "NO") + "\n",
            StandardCharsets.UTF_8,
        )
        return RootedBuiltinHost(root)
    }

    // ------------------------------------------------------------------
    // 1. 候选集合的三条分支（Config.cs:471-481）
    // ------------------------------------------------------------------

    @Test
    fun `开关为真时普通档与全局档只探 sav 一条`(@TempDir root: Path) {
        val host = RootedBuiltinHost(root)
        // Config.cs:471-477 ⇒ SavDir = ExeDir + "sav" + sep；
        // VariableEvaluator.cs:2027-2031（save{index:00}.sav）与 :2020（global.sav）拼在同一条 SavDir 上。
        assertEquals(
            listOf("sav/save03.sav"),
            SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 3L, true),
            "true ⇒ 仅 sav/（原版的 else 分支不可达）",
        )
        assertEquals(listOf("sav/global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, true))
        // GLOBAL 不吃下标（getSaveDataPathG :2018-2021）：任意 no 都是同一条候选。
        assertEquals(
            SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 0L, true),
            SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 79L, true),
        )
    }

    @Test
    fun `开关为假时普通档与全局档只探根目录一条`(@TempDir root: Path) {
        val host = RootedBuiltinHost(root)
        // Config.cs:478-481 ⇒ SavDir = Program.ExeDir（ExeDir 的定义 = Program.cs:206）；
        // ConfigData.cs:97 出厂默认即此分支。
        assertEquals(
            listOf("save03.sav"),
            SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 3L, false),
            "false ⇒ 仅根目录（不得再探 sav/）",
        )
        assertEquals(listOf("global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, false))
    }

    @Test
    fun `未装配时保持双候选的保守回落`(@TempDir root: Path) {
        // 契约 §2.9 要求 2：调用方没交出口径时**保持现口径**，不新增报红。
        // 这条是移植侧自造的保守读法，原版没有对应物（Config.cs:471-481 的分支互斥），
        // 因此只在「会话没装 SaveWiring」的装置场合可达 —— 生产路径见 SaveMethods.kt:54。
        val host = RootedBuiltinHost(root)
        assertEquals(
            listOf("save03.sav", "sav/save03.sav"),
            SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 3L, null),
        )
        assertEquals(listOf("global.sav", "sav/global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 0L, null))
    }

    @Test
    fun `变量档与角色档的候选不吃开关`(@TempDir root: Path) {
        // VariableEvaluator.cs:2039-2047 拼的是 Program.DatDir（Program.cs:216-218 = ExeDir\dat\），
        // 与 Config.SavDir 无关 ⇒ 三条分支下候选集合完全相同。
        val host = RootedBuiltinHost(root)
        for (flag in listOf(true, false, null)) {
            assertEquals(
                listOf("dat/var_02.dat"),
                SaveDataProbe.candidatePaths(host, SaveFileKind.VAR, 2L, flag),
                "VAR 候选必须与 UseSaveFolder 无关（flag=$flag）",
            )
            assertEquals(
                listOf("dat/chara_07.dat"),
                SaveDataProbe.candidatePaths(host, SaveFileKind.CHARVAR, 7L, flag),
                "CHARVAR 候选必须与 UseSaveFolder 无关（flag=$flag）",
            )
        }
    }

    @Test
    fun `探测候选与落盘路径同源`(@TempDir root: Path) {
        // 原版 SaveTo/LoadFrom/DelData 与 CheckData 用的是同一个 getSaveDataPath
        // （VariableEvaluator.cs:2646 / :2689 / :2712 vs :2144）⇒ 移植侧探测目录必须
        // 等于落盘目录，否则「写得出去、读不回来」或反之。
        for (flag in listOf(true, false)) {
            val cfg = SaveConfig(useSaveFolder = flag)
            val host = RootedBuiltinHost(root)
            for (no in listOf(0L, 3L, 79L)) {
                assertEquals(
                    listOf(cfg.normalPath(no.toInt())),
                    SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, no, flag),
                    "NORMAL 探测候选必须等于 SaveConfig.normalPath（flag=$flag, no=$no）",
                )
            }
            assertEquals(
                listOf(cfg.globalPath()),
                SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 5L, flag),
                "GLOBAL 探测候选必须等于 SaveConfig.globalPath",
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. 假存档槽的关闭（探测层）
    // ------------------------------------------------------------------

    @Test
    fun `开关为真时根目录的同名档按不存在处理`(@TempDir root: Path) {
        // 「另一侧的文件不算存在」的官方实现是迁移而不是双读（Config.cs:482-485 + :551-590）。
        val host = RootedBuiltinHost(root)
        write(root, SaveDataProbe.saveName(5), binarySave(SaveFileKind.NORMAL, "根目录的假槽"))
        val r = SaveDataProbe.check(host, SaveFileKind.NORMAL, 5L, GameBaseData(), true)
        assertNotNull(r)
        // VariableEvaluator.cs:2162-2166：File.Exists(SavDir+save05) 为假 ⇒ FILENOTFOUND + "----"
        assertEquals(EraDataState.FILENOTFOUND, r.state, "UseSaveFolder=true 时根目录档必须按不存在处理")
        assertEquals(SaveDataProbe.MES_NOT_FOUND, r.mes)
    }

    @Test
    fun `开关为假时 sav 子目录的同名档按不存在处理`(@TempDir root: Path) {
        val host = RootedBuiltinHost(root)
        write(root, "sav/" + SaveDataProbe.saveName(6), binarySave(SaveFileKind.NORMAL, "sav 里的假槽"))
        val r = SaveDataProbe.check(host, SaveFileKind.NORMAL, 6L, GameBaseData(), false)
        assertNotNull(r)
        assertEquals(EraDataState.FILENOTFOUND, r.state, "UseSaveFolder=false 时 sav/ 档必须按不存在处理")
        assertEquals(SaveDataProbe.MES_NOT_FOUND, r.mes)
        // 同一份字节按 true 查询 ⇒ 唯一候选命中 ⇒ OK（Config.cs:471-477）
        val ok = SaveDataProbe.check(host, SaveFileKind.NORMAL, 6L, GameBaseData(), true)
        assertNotNull(ok)
        assertEquals(EraDataState.OK, ok.state)
        assertEquals("sav 里的假槽", ok.mes)
    }

    // ------------------------------------------------------------------
    // 3. 函数层（CHKDATA）与宿主交出面
    // ------------------------------------------------------------------

    @Test
    fun `宿主交出的条目现值与开关夹具一致`(@TempDir root: Path) {
        // ConfigMethods.useSaveFolder 是条目现值的唯一取值点（Config.cs:147 字段 + :367 取值），
        // RootedBuiltinHost.useSaveFolder 是它的宿主侧交出面（BootHosts.kt:188-197）。
        // 三层配置文件全缺失时，条目表保持注册默认 false（ConfigData.cs:97）⇒ SavDir = ExeDir。
        val bare = RootedBuiltinHost(root.resolve("bare").also { Files.createDirectories(it) })
        assertEquals(false, bare.useSaveFolder, "缺省条目表 ⇒ 注册默认 false（ConfigData.cs:97）")
        assertEquals(listOf("save01.sav"), SaveDataProbe.candidatePaths(bare, SaveFileKind.NORMAL, 1L, bare.useSaveFolder))

        val on = hostWith(root.resolve("on"), true)
        assertEquals(true, on.useSaveFolder, "USE SAV FOLDER:YES ⇒ Config.cs:471-477 走 sav 分支")
        assertEquals(listOf("sav/save01.sav"), SaveDataProbe.candidatePaths(on, SaveFileKind.NORMAL, 1L, on.useSaveFolder))

        val off = hostWith(root.resolve("off"), false)
        assertEquals(false, off.useSaveFolder, "USE SAV FOLDER:NO ⇒ Config.cs:478-481 走 ExeDir 分支")
        assertEquals(listOf("save01.sav"), SaveDataProbe.candidatePaths(off, SaveFileKind.NORMAL, 1L, off.useSaveFolder))
    }

    @Test
    fun `CHKDATA 端到端只认条目指定的那一条目录`(@TempDir root: Path) {
        val on = hostWith(root, true)
        write(root, "sav/" + SaveDataProbe.saveName(1), binarySave(SaveFileKind.NORMAL, "sav 槽一"))
        // 同一份字节的副本落在根目录（原版不可能命中的位置）
        write(root, SaveDataProbe.saveName(2), binarySave(SaveFileKind.NORMAL, "根目录副本"))
        val (ctxOn, storeOn) = chkCtx(on, true)
        assertEquals(0L, iv(ctxOn.callFunction("CHKDATA", listOf(Value.IntV(1)))))
        assertEquals("sav 槽一", storeOn.readStr("RESULTS", longArrayOf(0)))
        // VariableEvaluator.cs:2162-2166 ⇒ 状态码 1 + "----"
        assertEquals(1L, iv(ctxOn.callFunction("CHKDATA", listOf(Value.IntV(2)))))
        assertEquals(SaveDataProbe.MES_NOT_FOUND, storeOn.readStr("RESULTS", longArrayOf(0)))

        // 关掉开关：同一条字节（槽二在根）变可见、槽一（在 sav/）变不存在
        val off = hostWith(root, false)
        val (ctxOff, storeOff) = chkCtx(off, false)
        assertEquals(0L, iv(ctxOff.callFunction("CHKDATA", listOf(Value.IntV(2)))))
        assertEquals("根目录副本", storeOff.readStr("RESULTS", longArrayOf(0)))
        assertEquals(1L, iv(ctxOff.callFunction("CHKDATA", listOf(Value.IntV(1)))))
    }

    /**
     * 函数层读数与宿主存在性探测的**保守回落**无关：
     * `CHKDATA` 在宿主回 null（命中）后必用带 flag 的 [SaveDataProbe.check] 复核，
     * 而宿主判 FILENOTFOUND 的场合双候选也必然判 FILENOTFOUND（候选集是超集）
     * ⇒ 只要会话装了 [SaveWiring]，`BootHosts.kt:224` 未传参这件事不会改变 CHKDATA 的读数。
     * 本用例把这句话钉住，同时说明组长那行接线是**口径统一**而不是语义修正。
     */
    @Test
    fun `函数层读数不受宿主存在性探测回落影响`(@TempDir root: Path) {
        // 故意让宿主与接线不一致：宿主条目 = NO（根目录），存档只写在 sav/，接线 = NO。
        val host = hostWith(root, false)
        write(root, "sav/" + SaveDataProbe.saveName(9), binarySave(SaveFileKind.NORMAL, "sav 独占"))
        val (ctx, store) = chkCtx(host, false)
        assertEquals(1L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(9)))))
        assertEquals(SaveDataProbe.MES_NOT_FOUND, store.readStr("RESULTS", longArrayOf(0)))
        // 同一槽位、宿主与接线都是 NO 的另一侧夹具：根目录有档 ⇒ 命中（Config.cs:478-481）
        write(root, SaveDataProbe.saveName(9), binarySave(SaveFileKind.NORMAL, "根目录独占"))
        assertEquals(0L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(9)))))
    }

    // ------------------------------------------------------------------
    // 4. 真语料（ERATW_GAMEDIR 可见才跑；只读，一个字节都不写）
    // ------------------------------------------------------------------

    /** 游戏根：`ERATW_GAMEDIR` 给出即权威；回落取 `ERATW_CORPUS`（指到 ERB 子树）的父目录。 */
    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    @Test
    fun `语料开关为真且唯一候选是 sav 子目录`() {
        val dir = gameDir()
        val sav00 = dir?.resolve("sav/save00.sav")
        Assumptions.assumeTrue(
            sav00 != null && Files.isRegularFile(sav00),
            "缺 ERATW_GAMEDIR/ERATW_CORPUS 或 sav/save00.sav，跳过语料判据",
        )
        val host = RootedBuiltinHost(dir!!)

        // emuera.config:51 与 CSV/_default.config:17 都是 YES ⇒ Config.cs:471-477 的分支
        assertTrue(host.useSaveFolder, "语料条目现值必须是 YES（emuera.config 第 51 行）")
        val single = SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 0L, host.useSaveFolder)
        assertEquals(listOf("sav/save00.sav"), single, "SavDir 唯一口径：语料实存全在 sav/ 下")

        val ops = host.fileOps
        assertNotNull(ops)
        // 根目录没有任何 .sav（实测：语料根下 0 个 sav 文件），故 false 分支的候选必然落空
        assertEquals(false, ops.exists("save00.sav"))
        assertEquals(true, ops.exists(single[0]))
        val offOnly = SaveDataProbe.check(host, SaveFileKind.NORMAL, 0L, GameBaseData(), false)
        assertNotNull(offOnly)
        assertEquals(EraDataState.FILENOTFOUND, offOnly.state, "按 false 口径查同一槽必须判不存在")

        // 真口径下必须**命中文件**（否则会是 FILENOTFOUND）；而裸 `GameBaseData()` 的 gameCode=0
        // 与语料存档码 7153 不符 ⇒ 走 `VariableEvaluator.cs:2202-2206` 的 GAME_ERROR 分支
        // （组长闸验实测读数；原作者在此断 OK 是没跑过的猜测）。
        val mismatch = SaveDataProbe.check(host, SaveFileKind.NORMAL, 0L, GameBaseData(), true)
        assertNotNull(mismatch)
        assertEquals(
            EraDataState.GAME_ERROR,
            mismatch.state,
            "单候选确实命中了 sav/save00.sav，红点在游戏码而非路径（VariableEvaluator.cs:2202-2206）",
        )
        assertEquals(SaveDataProbe.MES_DIFFERENT_GAME, mismatch.mes)

        // 换成 eraTW 实机 GAMEBASE 读数（gameCode 7153 / allowVersion 12，CsvLoaderTest:39-41）⇒ OK + 描述文本
        val on = SaveDataProbe.check(
            host,
            SaveFileKind.NORMAL,
            0L,
            GameBaseData(gameCode = 7153L, version = 0L, allowVersion = 12L),
            true,
        )
        assertNotNull(on)
        assertEquals(EraDataState.OK, on.state)
        assertTrue(on.mes.isNotEmpty(), "存档描述文本不应为空串")
    }
}
