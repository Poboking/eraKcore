package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.ConsoleBeginHost
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 玩家反馈复现（2026-11-01，eratw 4.980 + 「即时存档」插件 `ERB/魔改内容/qol/隨時操作存檔.ERB`）。
 *
 * 两条现象：
 * 1. 点「删档」后冒出一条玩家没存过的存档；
 * 2. 覆盖已有存档失败，屏上原样文案 `SAVEDATA命令在存档时发生了意料之外的错误`。
 *
 * 与 [SaveOverwriteReproTest] 的分工：那边只压引擎存档层（`SystemOps` 三个方法），
 * **不经过 ERB**；本用例把插件本体 `@Real_Time_Save` 整条交互链跑起来 —— 于是
 * `CHKDATA 选择的存档位`（**语句**形态，靠 `RESULT`）、`DELDATA`、`NEW_SAVE_MARK`
 * （`#DIM GLOBAL SAVEDATA`，`MODDING_VALUE.ERH:8`）、`SAVEGLOBAL`、`saveManageSaveGame`
 * 的 `SAVEDATA` 全部按玩家的点击顺序真跑一遍。
 *
 * 判据（对应玩家的两条现象）：
 * - 删档：`sav/` 目录里的条目集合必须**只少不多**（多出来的就是「新的未知存档」）；
 * - 覆盖：`sav/save03.sav` 必须被换掉且控制台**不出现**那句原样文案。
 *
 * **4.981 全局初始化**（2026-11-02 T12 独立验证补口）：本装置直接呼叫
 * `HANDLE_SAVE_SLOT_ACTION`（`隨時操作存檔.ERB:261`），不经过 `BEGIN TITLE` 菜单流，
 * 因此必须自己补上生产里 TITLE 阶段会跑的 `@QOL_DB_INIT`（见 [assemble] 的
 * `runGlobalInit` 参数文档）：4.981 的存档备注生成链
 * `createSaveInfo → GET_MAPNAME → NAME_TR → SQL_EXECUTE_SCALAR_STRING("TR_DB", …)`
 * 依赖该初始化登记的 `TR_DB` 连接（`qol_db.ERB:60`）；4.980 无此连接也无该链。
 *
 * **读轴同源**（2026-11-05 收口本族唯一常年的红）：`@QOL_DB_INIT` 在 `qol_db.ERB:61-62` 用
 * **相对**路径读两张随包表（`plugins/tw_csv_chs.xml` / `plugins/tw_taste_chs.xml`），而移植侧把
 * 相对路径一律解析到**宿主根**（生产的宿主根就是游戏根）。装置把宿主根挪进 `build/` 后只搬了
 * `emuera.config`、没搬这一层 ⇒ 4.981 根下三条用例在装配阶段就抛
 * CodeEE「SQL_IMPORT_MAP_XML：找不到文件 plugins/tw_csv_chs.xml」（4.980 根下当年为绿只是因为
 * 它没有 `@QOL_DB_INIT`：4.980 读同一张表的是 `@INIT_NG_OR_LOAD`（`SYSTEM.ERB:87` 定义、
 * `:96-97` 读表），本装置只 `CALL HANDLE_SAVE_SLOT_ACTION`、不进开局/读档初始化；且 `LOADTEXT`
 * 缺文件按原版折成空串不抛（`fn/HostMethods.kt:285-289`）⇒ 分叉一直藏着）。
 * 修法是**按档案取列**：铺当前根自己的 `plugins/`
 * （见 [stageRootAssets]），不硬编文件名、不按版本分支、更不「4.981 下跳过」。
 *
 * 输入脚本按原版键位（`BOARD.md:2022` 的实测口径：`ASK_YN` 0=第一项=是）：
 * - 删档：`1003`（切「删  档」）→ `3`（点槽 3）→ `0`（确认删）→ `0`（确认不可逆）→ `10000`（EXIT）
 * - 覆盖：`1001`（切「存  档」）→ `3`（点槽 3）→ `0`（确认替换）→ `10000`
 * - 空槽新建：`1001` → `5` → `0` → `10000`（走「目标为空」那一支，`隨時操作存檔.ERB:326-340`）
 *
 * 语料缺失（CI 无 `ERATW_CORPUS`）整体跳过；写根在 `build/` 下，语料目录一个字节都不写（AGENTS §1）。
 */
class PluginSaveSlotFlowTest {

    private companion object {
        /** 单条场景的墙钟预算；插件是 `WHILE 1 + INPUT` 死循环，喂不到输入就必须收口。 */
        private const val SCENARIO_BUDGET_MS = 120_000L

        private const val MES_SAVEDATA_UNEXPECTED = "SAVEDATA命令在存档时发生了意料之外的错误"

        /**
         * 全量语料只编一次：四条用例各自装配一套运行期（store / 沙盒不同），
         * 但 [BootModel] 是只读产物，重复编译只是把 3,971 文件 × 4 遍的等待加回来。
         */
        private val shared by lazy { compileCorpus() }

        private class Corpus(
            val gameRoot: Path,
            val model: BootModel,
            val saveInBinary: Boolean,
            val effective: EffectiveConfig,
        )

        private fun compileCorpus(): Corpus? {
            val corpus = RealCorpus.load() ?: return null
            val gameRoot = corpus.root.parent ?: corpus.root
            val overrides = loadConfigOverrides(gameRoot)
            val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(overrides)
            val parserConfig = RealCorpusCompile.parserConfigFromOverrides(overrides)
            val model = RealCorpusCompile.compile(corpus, null, saveInBinary, parserConfig)
            val effective = EffectiveConfig.of(EmueraConfigParser.loadChainFromDisk(gameRoot).store)
            return Corpus(gameRoot, model, saveInBinary, effective)
        }

        /**
         * 把**当前语料根自己的** `plugins/` 目录镜像进沙盒根（源只读，落点在 `build/` 下，
         * 语料一个字节都不写，AGENTS §1）。
         *
         * 为什么装置必须有这一手：移植侧**读轴的基准就是宿主根** —— 相对路径一律
         * `base.resolve(path)`、出根即 null（`core/proc/BootHosts.kt:467-473` 的 `resolvePath`，
         * fileOps 建在 `BootHosts.kt:282` / `ConsoleBuiltinHost.kt:176-181`），
         * 而生产的会话就把宿主根**取游戏根**（`core/proc/GameSession.kt:890-896` 的
         * `ConsoleBuiltinHost(gameRoot, …, writeRoot = writeRoot)`：读恒走 `RootedFileOps(root)`、
         * 写另由 `writeRoot` 闸；开机读配置也是同根 —— `app/.../GameHost.kt:822`
         * 的 `RootedBuiltinHost(gameRoot.toPath())`）。于是语料里那句
         * `SQL_IMPORT_MAP_XML "TR_DB", "CSV_MAP", "plugins/tw_csv_chs.xml"`
         * （4.981 `魔改内容/qol/qol_db.ERB:61-62`，4.980 同形状走 `SYSTEM.ERB:96-97` 的
         * `MAP_FROMXML CSV_MAP, LOADTEXT("plugins/tw_csv_chs.xml")`）在生产落的是**真游戏根的
         * `plugins/`**。本装置把写根挪进 `build/`（语料只读铁律）却没带这一层，4.981 根下
         * `@QOL_DB_INIT` 就在 `SqlMethods.kt:181` 抛 CodeEE「找不到文件」；4.980 根下当年看着是绿，
         * 只是因为 4.980 没有 `@QOL_DB_INIT`、那张表的 `LOADTEXT` 又只在 `@INIT_NG_OR_LOAD`
         * 里（`SYSTEM.ERB:87` 定义、`:96-97` 读表；本装置不进开局/读档初始化），
         * **读轴的分叉一直没被触发**而已（2026-11-05 定位）。
         *
         * 与 `assemble()` 里 `emuera.config` 那一手是同一条纪律：挪了根就得把**被挪那一手要用到的
         * 根内文件**一起搬过来，而不是给某一条根硬编期望、更不是「4.981 下跳过」。
         *
         * 取列方式＝**按档案取列**：清单直接来自当前根的 `plugins/` 目录本身
         * （`Files.walk` 的规则文件），不写死文件名、不按 NOW_VERSION 分支 —— 换根即换清单，
         * 两根各自的 `tw_csv_chs.xml` / `tw_taste_chs.xml` 都从**该根自己**读
         * （2026-11-05 实读：4.981 该目录 15 项、4.980 6 项，两根都在位）。
         * 目录不存在 ⇒ 铺 0 个文件：此时语料的读行为与「生产里游戏根真缺这张表」完全一致，
         * 用例照原样报红，本装置不替语料兜底。
         *
         * 自我校验：清单非空但**复制数 != 清单数**（IO 半途失败）即判红点名 ——
         * 否则装置自己的漏铺会伪装成「语料缺表」那条 CodeEE，读数就不可信了。
         */
        private fun stageRootAssets(gameRoot: Path, saveRoot: Path, dirName: String): Int {
            val src = gameRoot.resolve(dirName)
            if (!Files.isDirectory(src)) return 0
            val dst = saveRoot.resolve(dirName)
            var copied = 0
            val files = Files.walk(src).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            val failed = ArrayList<String>()
            for (f in files) {
                val target = dst.resolve(src.relativize(f).toString())
                runCatching {
                    Files.createDirectories(target.parent)
                    Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }.onFailure { failed.add("$f → ${it.message}") }
            }
            assertTrue(
                failed.isEmpty(),
                "装置镜像 $src 到 $dst 时有 ${failed.size} 个文件没铺进去（清单 ${files.size} 个，" +
                    "已铺 $copied 个）：这会让语料的相对读伪装成「语料缺表」，读数不可信。\n" +
                    failed.take(10).joinToString("\n"),
            )
            return copied
        }
    }

    private class Env(
        val rt: ProcessRuntime,
        val store: VariableStore,
        val console: LineBufferConsoleSink,
        val sink: QueueInputSink,
        val saveRoot: Path,
        val saveConfig: SaveConfig,
        val host: ConsoleBuiltinHost,
        val ops: FileSystemOps,
        /** 内建宿主的委托体：它的 `useSaveFolder` 读的是**宿主自己的配置链**。 */
        val saveRooted: RootedBuiltinHost,
    ) {
        /** `sav/` 下**全部**条目（含 `global.sav` 与临时文件）—— 排障读数用。 */
        fun allFiles(): List<String> = runCatching {
            Files.list(saveRoot.resolve("sav")).use { s ->
                s.map { it.fileName.toString() }.sorted().toList()
            }
        }.getOrDefault(emptyList())

        /**
         * `sav/` 下的**存档槽**条目（`save*.sav`）。
         *
         * 「新的未知存档」= 多出来的槽位，所以判据只看这一族；`global.sav` 是
         * `SAVEGLOBAL` 的正常产物（插件在删档/存档两条链尾都会写它），不算槽位。
         */
        fun slotFiles(): List<String> = allFiles().filter { it.startsWith("save") && it.endsWith(".sav") }

        fun text(): String = console.snapshot().joinToString("\n") { line ->
            line.segments.joinToString("") { it.text }
        }
    }

    /**
     * 装配一次（对象图对齐 `GameSession` / [BootPlayChain]），每个场景一套全新沙盒。
     *
     * @param withCorpusConfig 沙盒根是否搬一份语料的 `emuera.config`。正常场景必须搬
     *   （生产里宿主根就是游戏根，只有一份配置）；只有「两把尺不一致」那条反例才关掉它。
     * @param runGlobalInit 是否在驱动前跑 4.981 全局初始化 `@QOL_DB_INIT`
     *   （生产入口 `TITLE.ERB:28` 的 `CALL QOL_DB_INIT`）。
     *
     *   存档链（`操作模式 = 1001`）经 `createSaveInfo`（`INFO.ERB:269`）
     *   → `GET_MAPNAME`（`PLACE_拠点別分岐.ERB:84` 的 `RETURNF NAME_TR(PNAME)`）
     *   → `NAME_TR`（`CSV.ERB:184`）→ `SQL_EXECUTE_SCALAR_STRING("TR_DB", …)`；
     *   `TR_DB` 正是由该初始化的 `SQL_CONNECT "TR_DB"`（`qol_db.ERB:60`）登记 ——
     *   4.980 既无此连接也无该调用链，故本装置在 4.981 语料下必须补这一步，
     *   否则存档链抛 CodeEE「数据库连接 "TR_DB" 不存在」（2026-11-02 T12 独立验证实测）。
     *   查档/删档链（`1002`/`1003`）不生成备注、不碰 SQL。
     *
     *   **函数存在性守卫**：`@QOL_DB_INIT` 仅 4.981 有（`qol_db.ERB:47`），4.980 全库 0 处。
     *   故调用前先用编译产物的函数表探测（[FunctionTable.classify]），判 `NOT_FOUND` 则跳过
     *   并在 System.err 记一行依据 —— 保证 4.980 口径不回退（其存档链不碰 TR_DB），
     *   4.981 口径照常登记 TR_DB。
     *
     *   缺省 true = 与生产同形：任何一次真会话在 TITLE 阶段都跑过它。
     *   `divergent` 反例置 false —— 它刻意让沙盒根不带 `emuera.config`，使宿主配置链落到
     *   严格出厂口径；该口径下 `@QOL_DB_INIT` 会在与本用例判据无关的 `QOL_ITEM_INIT` 段
     *   抛出 TOSTR 兼容性 CodeEE（`画蛇添足版` 子系统的参数转换告警），而它走的是删档链、
     *   本就不需要 TR_DB，故不跑（避免把无关子系统的行为混进反例读数）。
     */
    private fun assemble(tag: String, withCorpusConfig: Boolean = true, runGlobalInit: Boolean = true): Env {
        val sharedCorpus = shared
        assumeTrue(sharedCorpus != null, "缺 ERATW_CORPUS，跳过")
        val gameRoot = sharedCorpus!!.gameRoot
        val model = sharedCorpus.model
        val saveInBinary = sharedCorpus.saveInBinary
        val effective = sharedCorpus.effective
        val program = model.program ?: error("Program 构建失败：${model.buildError}")

        // 沙盒根必须**先**建：生产里内建宿主与存档 IO 是同一个根（`GameSession` 传 `gameRoot`
        // 给两边），`CHKDATA` 的存在性判定与 `SAVEDATA` 的落盘因此同源。装置把写根挪到
        // `build/` 下（语料只读铁律，AGENTS §1），就必须把内建宿主的根一并挪过来，
        // 否则 `CHKDATA` 会在**语料目录**里找槽位、与写盘分叉（本用例第一版就踩了这条）。
        val saveRoot = Path.of("build", "plugin-saveflow-$tag").toAbsolutePath().normalize()
        runCatching { saveRoot.toFile().deleteRecursively() }
        Files.createDirectories(saveRoot.resolve("sav"))
        // `emuera.config` 必须跟着搬进沙盒：`RootedBuiltinHost.useSaveFolder` 读的是
        // **它自己那份配置链**（`BootHosts.kt:395` = `ConfigMethods.useSaveFolder(configChain.store)`），
        // 沙盒根没有 `emuera.config` 时该值为 false ⇒ `CHKDATA` 只找根目录的 `save03.sav`，
        // 而 `FileSystemOps` 用的是语料配置（`USE SAV FOLDER:YES`）⇒ 只找 `sav/save03.sav`。
        // 两把尺不一致时，脚本里的 `CHKDATA 选择的存档位` 会把已有档判成空槽（本用例第二版
        // 就是被这条假绿骗过：覆盖流程走了「空槽新建」分支却照样打印「已存档3」）。
        val corpusConfig = gameRoot.resolve("emuera.config")
        if (withCorpusConfig && Files.exists(corpusConfig)) {
            Files.copy(corpusConfig, saveRoot.resolve("emuera.config"))
        }
        // 读轴同源：把**当前根自己的** `plugins/` 镜像进沙盒，让语料那句
        // `plugins/tw_csv_chs.xml` 相对读落在装置内（与生产「会话宿主根 = 游戏根」同形，
        // 依据与口径见 [stageRootAssets]）。写侧口径一字不动：本装置照旧不给
        // ConsoleBuiltinHost 注入 writeRoot（宿主写请求仍被拒并计数），存档落盘只进沙盒，
        // 语料目录零写入。
        val stagedPlugins = stageRootAssets(gameRoot, saveRoot, "plugins")
        System.err.println(
            "[device] plugins 镜像：$stagedPlugins 个文件，源=$gameRoot/plugins" +
                "（沙盒读根=$saveRoot/plugins；缺表时按语料原样报红，不兜底）",
        )
        val saveRooted = RootedBuiltinHost(saveRoot)

        val console = LineBufferConsoleSink(clientWidth = 1800, printCWidth = 25, maxLines = 2000)
        val sink = QueueInputSink()
        val beginHost = ConsoleBeginHost(sink, console)
        val host = ConsoleBuiltinHost(saveRoot, console)
        val gameData = GameDataLoader.load(gameRoot.resolve("CSV"))
        val store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            VarConfig(systemSaveInBinary = saveInBinary),
        )
        gameData?.let { data ->
            for (t in data.templates) store.registerTemplate(t)
            store.loadItemPrice(data.itemPrices)
            store.reloadStrNames()
        }
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L) else repeat(4) { store.characters.addBare() }
        SystemVariableBootstrap.install(
            store,
            model.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            model.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
        )
        for ((fk, spec) in model.userVarDecls) runCatching { store.declareUserVar(spec, fk) }
        if (effective.bool(ConfigKey.UseERD)) UserKeywordTables.install(store, gameRoot)

        val saveConfig = SaveConfig(
            systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
            zipSaveData = effective.bool(ConfigKey.ZipSaveData),
            useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
        )
        SaveWiring.install(store, saveConfig, effective.saveDataNos, EmSaveSets.parse(gameRoot.resolve("CSV")))
        val ops = FileSystemOps(store, RootedSaveFileIo(saveRoot, saveRooted.fileOps), saveConfig)
        val rt = ProcessRuntime(
            program,
            store,
            model.table,
            VmConfig(maxStepsPerRun = 20_000_000L),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5, saveDataNos = effective.saveDataNos),
            systemOps = ops,
            builtinHost = host,
            randSeed = 20260226L,
            beginHost = beginHost,
            inputSink = sink,
        )
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                model.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
        })
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> model.dataSections[program.statement(index)] })
        // 4.981 全局初始化（见 [assemble] 的 runGlobalInit 参数文档）：与生产同一条登记链 ——
        // `TITLE.ERB:28` 的 `CALL QOL_DB_INIT` 经 `qol_db.ERB:60` 的 `SQL_CONNECT "TR_DB"`
        // 把 TR_DB 连接登记好；装置直接 CALL 该 ERB 入口，不走 BEGIN TITLE 的菜单流。
        // 函数存在性守卫：`@QOL_DB_INIT` 是 4.981 **专有**（`qol_db.ERB:47`；4.980 全库 0 处，
        // 其存档链亦不调 NAME_TR），故按编译产物的函数表探测，不存在则跳过并在 System.err 记依据
        // —— 4.980 口径不回退，4.981 口径照常登记 TR_DB。
        if (runGlobalInit) {
            val kind = model.table.classify("QOL_DB_INIT")
            if (kind == FunctionTable.TargetKind.NOT_FOUND) {
                System.err.println(
                    "[device] 跳过 QOL_DB_INIT：当前语料无 `@QOL_DB_INIT`（函数表判 NOT_FOUND）" +
                        " —— 4.980 口径专有跳过（该函数仅 4.981 的 qol_db.ERB:47 有；" +
                        "4.980 存档链亦不调 NAME_TR，故不需要 TR_DB 连接）",
                )
            } else {
                val ret = rt.callFunction("QOL_DB_INIT")
                System.err.println("[device] QOL_DB_INIT 完成，返回=$ret（TR_DB 连接已由 qol_db.ERB:60 登记）")
            }
        }
        return Env(rt, store, console, sink, saveRoot, saveConfig, host, ops, saveRooted)
    }

    /**
     * 喂输入并调用 [method]。
     *
     * 代理线程只在「有等待且队列已空」时喂下一条（与 [BootPlayChain] 的 `Feeder` 同一条纪律），
     * 脚本耗尽后按 `INPUT` 的缺省值作答（`ASK_YN` 无缺省 ⇒ 0），并把耗尽这件事记下来 ——
     * 脚本不够长时读数会直接体现在控制台尾，不会静默挂住。
     */
    private fun drive(env: Env, method: String, intArgs: LongArray, inputs: List<Long>): String {
        val script = ArrayDeque(inputs)
        val stopped = AtomicBoolean(false)
        val exhausted = AtomicBoolean(false)
        val feeder = Thread({
            var awaitingLast = false
            while (!stopped.get()) {
                try {
                    if (!awaitingLast && env.sink.isAwaiting && env.sink.pendingCount == 0) {
                        val req: InputRequest? = env.sink.awaiting
                        val next = script.removeFirstOrNull()
                        if (next == null) exhausted.set(true)
                        val answer = next ?: (req?.let { if (it.hasDef) it.defInt else 0L } ?: 0L)
                        if (env.sink.submitInt(answer)) awaitingLast = true
                    }
                    if (awaitingLast && env.sink.pendingCount == 0) awaitingLast = false
                    Thread.sleep(1L)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    return@Thread
                }
            }
        }, "plugin-saveflow-feeder").apply { isDaemon = true; start() }

        val t0 = System.nanoTime()
        val watcher = Thread({
            while (!stopped.get() && (System.nanoTime() - t0) / 1_000_000 < SCENARIO_BUDGET_MS) Thread.sleep(50L)
            if (!stopped.get()) runCatching { env.sink.close() }
        }, "plugin-saveflow-watchdog").apply { isDaemon = true; start() }

        var failure: String? = null
        var ret: Long? = null
        try {
            ret = env.rt.callFunction(method, intArgs)
        } catch (e: InputSinkClosedException) {
            failure = "InputSinkClosed（预算 ${SCENARIO_BUDGET_MS}ms 内没走完）"
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            stopped.set(true)
            feeder.interrupt()
            watcher.interrupt()
            runCatching { env.sink.close() }
        }

        val head = "[DRIVE] $method(${intArgs.joinToString(",")}) 返回=$ret"
        val tail = env.text()
        if (failure != null) return head + "\n" + tail + "\n[DRIVE] $failure"
        if (exhausted.get()) return head + "\n" + tail + "\n[DRIVE] 脚本耗尽（按缺省值作答后继续）"
        return head + "\n" + tail
    }

    /** 铺一个真存档到槽 3（PC 4.980 真档，168 角色）。 */
    private fun seedSlot3(env: Env) {
        val fixture = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(Files.exists(fixture), "缺 fixtures/saves/save03.sav，跳过")
        val slot3 = env.saveRoot.resolve("sav").resolve("save03.sav")
        Files.createDirectories(slot3.parent)
        Files.copy(fixture, slot3)
    }

    private fun probe(env: Env, no: Long) =
        SaveDataProbe.check(env.host, SaveFileKind.NORMAL, no, env.store.registry.gameBase, env.saveConfig.useSaveFolder)

    /**
     * `@HANDLE_SAVE_SLOT_ACTION(操作模式, 选择的存档位, qSave_Mode)` 的实参表
     * （`隨時操作存檔.ERB:262`）。
     *
     * 这是玩家点某个槽位之后**真正跑的那一段**：`CHKDATA 选择的存档位`（语句形态，靠 `RESULT`）
     * 判空/非空，再按 `操作模式` 分到 替换 / 读取 / 删除 三支。不经过
     * `@Real_Time_Save` 的 `WHILE 1 + INPUT` 循环与 `CLEARLINE` 重绘，所以控制台读数不会被清掉。
     */
    private fun slotAction(mode: Long, slot: Long): LongArray = longArrayOf(mode, slot, 0L)

    // ------------------------------------------------------------------
    // ① 删档：不许凭空多出存档
    // ------------------------------------------------------------------

    @Test
    fun `删档之后 sav 目录只许少不许多`() {
        val env = assemble("del")
        seedSlot3(env)
        val before = env.slotFiles()
        assertTrue(before.contains("save03.sav"), "夹具没铺进去：$before")
        // 铺档后、驱动前先读一次探针：把「宿主根 / headerOnly / useSaveFolder」三处口径
        // 与脚本里 `CHKDATA 选择的存档位` 的读数对齐，装置自身接错线时当场暴露。
        val preFull = probe(env, 3L)?.state
        val preHeader = SaveDataProbe.check(
            env.host, SaveFileKind.NORMAL, 3L, env.store.registry.gameBase,
            env.saveConfig.useSaveFolder, headerOnly = true,
        )?.state
        assertTrue(
            preFull == EraDataState.OK && preHeader == EraDataState.OK,
            "铺档后 CHKDATA(3) 必须 OK：全量=$preFull headerOnly=$preHeader；" +
                "文件=${Files.exists(env.saveRoot.resolve("sav").resolve("save03.sav"))}；全目录=${env.allFiles()}",
        )

        val log = drive(env, "HANDLE_SAVE_SLOT_ACTION", slotAction(1003L, 3L), listOf(0L, 0L))
        val after = env.slotFiles()

        assertEquals(
            before.filter { it != "save03.sav" },
            after,
            "删档后 sav 目录凭空多出存档槽（玩家报的「新的未知存档」）：\n前=$before\n后=$after\n" +
                "全目录=${env.allFiles()}\n--- 控制台 ---\n$log",
        )
        assertTrue(!after.contains("save03.sav"), "DELDATA 之后 save03.sav 必须消失：$after\n全目录=${env.allFiles()}\n--- 控制台 ---\n$log")
        assertEquals(EraDataState.FILENOTFOUND, probe(env, 3L)?.state, "删档后 CHKDATA(3) 必须回 FILENOTFOUND\n$log")
        assertTrue(log.contains("存档3删除完成"), "插件没走到删除完成分支：\n$log")
        assertTrue(
            log.contains("是否要删除该存档"),
            "删档流程没走「删除该存档」确认支（说明 CHKDATA 把已有档判成了空槽）：\n$log",
        )
        assertTrue(!log.contains(MES_SAVEDATA_UNEXPECTED), "删档链不该碰 SAVEDATA 报错：\n$log")
    }

    // ------------------------------------------------------------------
    // ② 覆盖旧存档：必须成功且不留残档
    // ------------------------------------------------------------------

    @Test
    fun `覆盖旧存档必须成功且不报 SAVEDATA 意外错误`() {
        val env = assemble("overwrite")
        seedSlot3(env)
        val before = env.slotFiles()
        val beforeSize = Files.size(env.saveRoot.resolve("sav").resolve("save03.sav"))

        val log = drive(env, "HANDLE_SAVE_SLOT_ACTION", slotAction(1001L, 3L), listOf(0L))
        val after = env.slotFiles()

        assertTrue(!log.contains(MES_SAVEDATA_UNEXPECTED), "出现了玩家报的原样文案：\n$log")
        // 必须真的走「替换该存档」那一支（`隨時操作存檔.ERB:279-288`）—— 玩家报的
        // 「不能覆盖旧存档」就是这一支；只看「已存档3」会被「空槽新建」分支蒙混过关。
        assertTrue(
            log.contains("是否要替换该存档"),
            "覆盖流程没走「替换该存档」确认支（说明 CHKDATA 把已有档判成了空槽）：\n$log",
        )
        assertEquals(before, after, "覆盖存档后存档槽集合必须不变（多出来 = 幽灵存档）：\n前=$before\n后=$after\n全目录=${env.allFiles()}\n$log")
        val slot3 = env.saveRoot.resolve("sav").resolve("save03.sav")
        assertTrue(Files.exists(slot3), "覆盖后 save03.sav 必须还在\n$log")
        assertTrue(log.contains("已存档3"), "插件没走到存档完成分支：\n$log")
        assertEquals(EraDataState.OK, probe(env, 3L)?.state, "覆盖后 CHKDATA(3) 必须仍可读\n$log")
        assertTrue(Files.size(slot3) > 0, "覆盖后体积不能为 0（旧=$beforeSize）\n$log")
    }

    // ------------------------------------------------------------------
    // ③ 空槽新建：必须走「目标为空」那一支（`CHKDATA` 语句形态的 RESULT 判据）
    // ------------------------------------------------------------------

    @Test
    fun `空槽新建走目标为空分支且只新增一个档`() {
        val env = assemble("fresh")
        val before = env.slotFiles()

        val log = drive(env, "HANDLE_SAVE_SLOT_ACTION", slotAction(1001L, 5L), listOf(0L))
        val after = env.slotFiles()

        assertTrue(!log.contains(MES_SAVEDATA_UNEXPECTED), "空槽新建不该报 SAVEDATA 意外错误：\n$log")
        // `隨時操作存檔.ERB:277` 的 `CHKDATA 选择的存档位` 是**语句**形态，判据是 `RESULT != 1`。
        // 若移植侧没把语句形态的返回值写进 RESULT，空槽会被误判成「已存在」，文案就变成
        // 「已存在存档5」+「替换该存档？」。
        assertTrue(
            log.contains("要将存档存储在5吗"),
            "空槽被误判成已有存档（`CHKDATA` 语句形态的 RESULT 没写回？）：\n$log",
        )
        assertEquals(listOf("save05.sav"), after, "空槽新建只该多出 save05.sav：\n前=$before\n后=$after\n全目录=${env.allFiles()}\n$log")
        assertEquals(EraDataState.OK, probe(env, 5L)?.state, "新建后 CHKDATA(5) 必须 OK\n$log")
    }

    // ------------------------------------------------------------------
    // ④ 目录口径必须同源：`CHKDATA` 与 `SAVEDATA`/`DELDATA` 共用一份 `SaveConfig`
    // ------------------------------------------------------------------

    /**
     * 「两把尺」回归（2026-11-01 修 [com.eratw.port.core.fn.registerSaveMethods] 的 CHKDATA 优先级）。
     *
     * 反例构造：沙盒根**不放** `emuera.config` ⇒ 内建宿主自己那条配置链读不到
     * `USE SAV FOLDER:YES`，`RootedBuiltinHost.useSaveFolder` 退回 false（只找根目录）；
     * 而会话的 `SaveConfig.useSaveFolder` 仍为 true（`SAVEDATA` / `DELDATA` 落 `sav/`）。
     *
     * 这正是玩家那两条现象的成因形态：写进 `sav/`、查的是根目录 ⇒ 已有档被判成空槽
     * （「不能覆盖旧存档」），删档删掉根目录那份而 `sav/` 里的旧档还在（「冒出未知存档」）。
     * 判据：即便宿主那把尺是 false，`CHKDATA` 也必须跟会话的 `SaveConfig` 走、认得出槽 3。
     */
    @Test
    fun `宿主配置链与 SaveConfig 不一致时 CHKDATA 必须跟 SaveConfig 走`() {
        // 不跑 4.981 全局初始化：本反例的严格出厂口径会让 @QOL_DB_INIT 在无关的
        // QOL_ITEM_INIT 段抛 TOSTR 兼容性 CodeEE，且它走删档链、不需要 TR_DB
        // （见 [assemble] 的 runGlobalInit 参数文档）。
        val env = assemble("divergent", withCorpusConfig = false, runGlobalInit = false)
        seedSlot3(env)

        // 反例前提自证：宿主那把尺确实是 false，会话那把是 true —— 否则这条用例什么都没钉住。
        assertTrue(
            !env.saveRooted.useSaveFolder,
            "反例构造失败：宿主配置链仍报 useSaveFolder=true（沙盒根不该有 emuera.config）",
        )
        assertTrue(env.saveConfig.useSaveFolder, "反例构造失败：会话 SaveConfig.useSaveFolder 必须为 true")
        assertTrue(env.slotFiles().contains("save03.sav"), "夹具没铺进去：${env.slotFiles()}")

        // 走真实 ERB：`HANDLE_SAVE_SLOT_ACTION` 的第一句就是 `CHKDATA 选择的存档位`。
        val log = drive(env, "HANDLE_SAVE_SLOT_ACTION", slotAction(1003L, 3L), listOf(1L))
        assertTrue(
            log.contains("已存在存档3"),
            "CHKDATA 跟了宿主配置链（false）而不是会话 SaveConfig（true），" +
                "把 sav/ 里的已有档判成了空槽：\n$log",
        )
        assertTrue(
            !log.contains("此处没有存档文件"),
            "CHKDATA 报了「此处没有存档文件」——两把尺仍在分叉：\n$log",
        )
    }
}
