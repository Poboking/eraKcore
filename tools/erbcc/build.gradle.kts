import com.eratw.port.buildlogic.CORPUS_PATCHES_MARKER
import com.eratw.port.buildlogic.CorpusPatchPlan
import com.eratw.port.buildlogic.corpusStagingDir
import com.eratw.port.buildlogic.planCorpusPatches

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.eratw.port.erbcc.MainKt")
}

kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（全仓 JVM 面同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 真语料侧车用例（BootMetaRoundTripTest）编译 250 文件；默认 512m 会 OOM，
    // 与 core-vm 的语料闸同口径给 3g；未设 ERATW_CORPUS 时用 Assumptions 跳过。
    maxHeapSize = "3g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * 全量语料 → `erbc.v1/{program.erbc,program.meta,manifest.txt}`。
 *
 * 默认输出 `build/erbc-out/`；产物不进 git（真机联调时 adb push 到应用外部 files 根）。
 * 需要一个较大的堆：全量编译峰值 ≈3.4 GiB，给 6g 与 bootSmoke 对齐。
 */
tasks.register<JavaExec>("compileCorpus") {
    group = "build"
    description = "全量语料编译为 .erbc 容器 + 元数据侧车（-Pcorpus=<ERB目录> [-Pout=<目录>]）"
    mainClass.set("com.eratw.port.erbcc.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "6g"

    // 第 3 层并行预取的调参/逃逸开关。JavaExec 会 fork 一个新 JVM，GRADLE_OPTS 里的
    // -D 只进 Gradle daemon、到不了这个子 JVM，所以必须显式走 jvmArgs。
    // 三个值都登记为任务输入的一部分，改了就会重跑（不会复用另一档的绿缓存）。
    listOf(
        "ERATW_ERBC_PARALLEL" to "eratw.erbcc.parallel",
        "ERATW_ERBC_PARALLEL_THREADS" to "eratw.erbcc.parallel.threads",
        "ERATW_ERBC_PARALLEL_CHUNK" to "eratw.erbcc.parallel.chunk",
    ).forEach { (envKey, propKey) ->
        System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { value ->
            jvmArgs("-D$propKey=$value")
        }
    }

    // CMP-67：语料/产物路径的来源统一走 Provider 链（`-Pcorpus` 优先，其次 `ERATW_CORPUS`）。
    //
    // 旧实现是配置期直接 `System.getenv("ERATW_CORPUS")`：
    //  - 值本身并不陈旧 —— Gradle 8.10.2 的 daemon 会把 client 的环境同步进 daemon 进程
    //    （`native setenv` + 刷新 JDK 的环境快照，见 gradle/gradle#10483 的维护者说明；
    //    本机实测同一 daemon 下改 `ERATW_L15_PROBE` 再跑，`System.getenv` 与
    //    `providers.environmentVariable` 都读到新值）；
    //  - 真缺陷是**不可跟踪**：路径没有声明成任务输入，`inputs.dir` 只留语料文件指纹，
    //    换路径/空目录/受限档时任务仍可能命中 UP-TO-DATE，读到的值不在指纹里；
    //    配置缓存下 `System.getenv` 也只能靠内置插桩，语义弱于 Provider API。
    // 现在：provider 的值进 `inputs.property`（声明为输入、执行前参与指纹），`args` 在
    // `doFirst` 里取**同一个** provider（单源，不再有「配置期读一次、执行期用另一次」分叉）。
    val corpusProvider = project.providers.gradleProperty("corpus")
        .orElse(project.providers.environmentVariable("ERATW_CORPUS"))
        .orElse("")
    val outProvider = project.providers.gradleProperty("out")
        .orElse(project.providers.provider { layout.buildDirectory.dir("erbc-out").get().asFile.absolutePath })
    inputs.property("corpusPath", corpusProvider)
    inputs.property("outPath", outProvider)
    // 第三方口上容错档（`-PallowParseFailures=<容错清单>` 或 `ERATW_ALLOW_PARSE_FAILURES=<清单>`）：
    // 清单（每行 `相对路径:行号`）内的解析失败切片按 THROW 空体桩随包，manifest 追加 degraded-slices 段。
    // 清单外的任何失败、或清单项未命中都由 erbcc 以退出码 1 拒掉，所以容错档不会吞掉未来的编译器回归。
    // 未命中分两种成因分开报（2026-11-04 lane allowlist）：清单项的**源文件不在语料根**（口上包没复制
    // 进来 / 换了根 / 包撤下）与「文件在、行没命中（源修好 / 行号漂移）」修法相反，别只对行号。
    // 清单 0 条在册且编译 0 失败 ⇒ 放行，但读数直说「未降级任何切片」，产物与严格档逐字节一致
    // （取证口是严格档：它自己会把全量失败逐条打到 stderr）。默认空 = 严格档（任何失败拒落盘）。
    //
    // 末尾 `.orElse("")` 不是防御式冗余：两个开关都没给时 provider **无值**，而
    // `inputs.property(name, Provider)` 在快照输入时对无值 provider 直接抛
    // 「value is not present ⇒ 无法计算任务哈希」—— 干净环境里（没 export 过这两个变量的
    // 构建机）`compileCorpus` 会在执行阶段失败，且报错与语料毫无关系。
    val allowParseFailuresProvider = project.providers.gradleProperty("allowParseFailures")
        .orElse(project.providers.environmentVariable("ERATW_ALLOW_PARSE_FAILURES"))
        .orElse("")
    inputs.property("allowParseFailures", allowParseFailuresProvider)
    // 相对路径一律按**仓根**解析：JavaExec 的 workingDir 是本子工程（`tools/erbcc`），
    // 裸 `-PallowParseFailures=devtools/erbc-degraded-allowlist.tsv` 会在那里找
    // `tools/erbcc/devtools/...` ⇒ erbcc 退出码 2「容错清单不是文件」，
    // 报错与语料/清单内容毫无关系（2026-11-03 出 4.981 包时实际踩到）。
    fun resolveAllowListPath(raw: String): File {
        val f = File(raw)
        return if (f.isAbsolute) f else rootProject.projectDir.resolve(raw)
    }
    allowParseFailuresProvider.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
        inputs.files(resolveAllowListPath(p)).withPropertyName("allowParseFailuresList")
    }
    // 编译档（`-PcompileMode=strict|compat` 或 `ERATW_COMPILE_MODE`）：strict 是出包默认；
    // compat 只在带容错清单时生效（清单内的失败切片降级为 THROW 桩）。声明为输入，
    // 档位变化必须让任务重跑，否则复用另一档的旧产物。
    val compileModeProvider = project.providers.gradleProperty("compileMode")
        .orElse(project.providers.environmentVariable("ERATW_COMPILE_MODE"))
        .orElse("strict")
    inputs.property("compileMode", compileModeProvider)
    // 输入/输出必须声明，否则 JavaExec 恒被判「无输出」而每次重跑（6g 堆、全量几分钟），
    // 于是没人愿意在出包前跑它 —— 这正是 `stageGameAssets` 只能读现成产物目录、陈旧 .erbc
    // 被静默随包的原因（2026-10-22 复核）。声明之后：语料 + erbcc/core-vm 字节码未变
    // ⇒ 显式重跑也命中 UP-TO-DATE，出包前跑一次不再有心理负担。
    // （`inputs.dir` 仍需配置期的具体路径；语料路径本身由上面的 `inputs.property` 跟踪。）
    val corpusAtConfig = corpusProvider.orNull ?: ""
    if (corpusAtConfig.isNotBlank()) {
        inputs.dir(corpusAtConfig).withPropertyName("corpus")
        // 侧车的 #DIM 求值还吃游戏根的 CSV（VariableSize / 关键字表）与 emuera.config
        // （SAVEDATA 存档模式）：不声明就会在「只改配置/CSV」时复用陈旧侧车。
        val gameRoot = File(corpusAtConfig).absoluteFile.parentFile
        if (gameRoot != null) {
            inputs.dir(File(gameRoot, "CSV")).withPropertyName("gameCsv")
            inputs.files(File(gameRoot, "emuera.config")).withPropertyName("emueraConfig")
        }
    }
    // 编译/审计代码本身也是输入：不声明时改了 erbcc/core-vm 仍会命中 UP-TO-DATE，
    // 闸会用旧字节码给出假 PASS/假产物。
    inputs.files(classpath).withPropertyName("toolClasspath")
    // 语料内容补丁资产（specs/corpus-bugs.md §3；CB-001 首个落地）：index 或补丁文件一变
    // 必须重跑（staging 与产物都要按新判据重来）。目录不存在时放行（planCorpusPatches
    // 会在执行期对缺 index 判红，配置期不拦纯缺目录的边缘形态）。
    inputs.dir(rootProject.projectDir.resolve("devtools/corpus-patches"))
        .withPropertyName("corpusPatches")
        .optional(true)
    outputs.dir(outProvider.get()).withPropertyName("erbcOut")
    doFirst {
        val corpus = corpusProvider.get()
        require(corpus.isNotBlank()) { "缺 corpus：传 -Pcorpus=<ERB目录> 或设置 ERATW_CORPUS" }
        val mode = compileModeProvider.get().trim().lowercase()
        require(mode in setOf("strict", "compat")) { "compileMode 只能是 strict|compat，收到 '$mode'" }
        // ---- 语料内容补丁（specs/corpus-bugs.md §3；机制本体在 buildSrc 的 CorpusPatches）----
        // 执行前解析 devtools/corpus-patches：目标在且 sha=病灶版 ⇒ 语料 staging 成硬链接副本
        // （build/corpus-staging 下），只对副本套补丁，erbcc 吃副本；目标缺席/已是修后版 ⇒
        // 原样直编 + 留痕日志；sha 双双不命中 ⇒ planCorpusPatches 判红（未知漂移不许静默跳过）。
        // 判定与套用收在 buildSrc（可单测），这里只接线与留痕。
        val patchPlan = planCorpusPatches(
            corpusRoot = File(corpus).absoluteFile,
            patchesDir = rootProject.projectDir.resolve("devtools/corpus-patches"),
            stagingRoot = layout.buildDirectory.dir("corpus-staging").get().asFile,
            logger = logger,
        )
        val effectiveCorpus = when (patchPlan) {
            is CorpusPatchPlan.None -> {
                patchPlan.reasons.forEach { logger.lifecycle("[corpus-patches] $it") }
                corpus
            }
            is CorpusPatchPlan.Staged -> patchPlan.corpusRoot.absolutePath
        }
        val taskArgs = mutableListOf("compile", "--corpus", effectiveCorpus, "--out", outProvider.get(), "--mode", mode)
        // 空串 / 0 / false / no / off 一律视为关；其余非空值 = 容错清单路径
        // （与 `-Pstrict` 的「在即开」口径不同，因为环境变量路径也必须能显式表达「关」）。
        val tolerant = allowParseFailuresProvider.orNull?.trim()
            ?.takeUnless { it.isEmpty() || it.lowercase() in setOf("0", "false", "no", "off") }
        if (tolerant != null) {
            require(mode == "compat") {
                "带容错清单时必须用 -PcompileMode=compat（当前 '$mode'）：strict 档会拒掉清单"
            }
            taskArgs += listOf("--allow-parse-failures", resolveAllowListPath(tolerant).absolutePath)
        } else {
            require(mode != "compat") { "compat 档必须带 -PallowParseFailures=<容错清单>" }
        }
        setArgs(taskArgs)
    }
    doLast {
        // 留痕收口：补丁应用的 staging marker（corpus-patch <id> <相对路径> <坏版sha> <修后sha>）
        // 追加进产物 manifest.txt。erbcc 的清单解析显式容忍追加字段；出包期 CMP-66 源 sha
        // 对账（buildSrc 的 verifyErbcFreshness）与包 manifest 的补丁留痕都读这批行。
        // UP-TO-DATE 命中时 doFirst/doLast 都不跑，manifest 保持上一轮的追记原样。
        val corpus = corpusProvider.get()
        if (corpus.isBlank()) return@doLast
        val marker = File(
            corpusStagingDir(
                File(corpus).absoluteFile,
                layout.buildDirectory.dir("corpus-staging").get().asFile,
            ),
            CORPUS_PATCHES_MARKER,
        )
        if (!marker.isFile) return@doLast
        // erbcc 的产物目录 = <out>/erbc.v1（manifest 与 program.erbc 同层，stageGameAssets 的
        // erbcDir 也指到这一层）；manifest 在那里，不在 out 根。
        val manifest = File(outProvider.get(), "erbc.v1/manifest.txt")
        if (!manifest.isFile) {
            logger.warn("[corpus-patches] marker 在（${marker}）而产物 manifest 缺席（${manifest}）：补丁留痕落空")
            return@doLast
        }
        val trace = marker.readText(Charsets.UTF_8)
        manifest.appendText(trace)
        logger.lifecycle(
            "[corpus-patches] manifest 追记补丁留痕 ${manifest.absolutePath}：" +
                trace.trim().lineSequence().joinToString("；"),
        )
    }
}

/**
 * 装载期符号链接审计（`erbcc audit`）：一次全量编译收集全部 `VarRef` / 关键字下标，
 * 再按 `GameSession` 装出的运行期真实命名空间逐个查绑定，输出未绑定符号 TSV。
 *
 * 需要 corpus（`<gameRoot>/ERB`）与 gameRoot（含 `CSV/`、`emuera.config`）；
 * gameRoot 缺省 = corpus 的父目录。`-Pstrict` 让硬差异变成非零退出码（入闸用）。
 * 与 compileCorpus 同口径：6g 堆（全量编译峰值 ≈3.4 GiB），输入/输出声明齐全。
 */
tasks.register<JavaExec>("symbolAudit") {
    group = "verification"
    description = "装载期符号链接审计（-Pcorpus=<ERB目录> [-PgameRoot=<游戏根>] [-Pout=<TSV>] [-Pstrict] [-PeratwReachability [-PeratwReachOut=<TSV>]])"
    mainClass.set("com.eratw.port.erbcc.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "6g"
    val corpus = (project.findProperty("corpus") as String?)
        ?: System.getenv("ERATW_CORPUS")
        ?: ""
    val gameRoot = (project.findProperty("gameRoot") as String?)
        ?: System.getenv("ERATW_GAMEDIR")
        ?: ""
    val out = (project.findProperty("out") as String?)
        ?: layout.buildDirectory.file("symbol-audit/unresolved-symbols.tsv").get().asFile.absolutePath
    val limit = (project.findProperty("limit") as String?)
    val strict = project.hasProperty("strict")
    doFirst { require(corpus.isNotBlank()) { "缺 corpus：传 -Pcorpus=<ERB目录> 或设置 ERATW_CORPUS" } }
    if (corpus.isNotBlank()) inputs.dir(corpus).withPropertyName("corpus")
    if (gameRoot.isNotBlank()) {
        // 只把影响命名空间的两处算输入：CSV 名字表/角色模板 + 存档模式配置。
        // 整个 gameRoot 还含 600MB 语料与音频，全量哈希只会拖慢闸。
        inputs.dir(File(gameRoot, "CSV")).withPropertyName("gameCsv")
        inputs.files(File(gameRoot, "emuera.config")).withPropertyName("emueraConfig")
    }
    // 审计代码本身也是输入（同 compileCorpus 的理由）；否则改 erbcc/core-vm 后
    // 这条闸会用旧字节码命中 UP-TO-DATE，给出假 PASS。
    inputs.files(classpath).withPropertyName("toolClasspath")
    outputs.file(out)
    // CMP-62：strict/limit 目前只经 args 进入任务；Gradle 8.10.2 的 JavaExec 已把 args 当输入
    // （实测 --info：Value of input property 'args' has changed ⇒ 重跑），这里仍显式登记，
    // 防以后把 strict 移出 args、或换一种 args 组装方式时复用「非 strict / 部分审计」的绿缓存。
    inputs.property("strict", strict)
    inputs.property("limit", limit ?: "")
    args("audit", "--corpus", corpus, "--out", out)
    if (gameRoot.isNotBlank()) args("--game-root", gameRoot)
    limit?.let { args("--limit", it) }
    if (strict) args("--strict")
    // MODERN-R6：可达性/可移植性报告是可选附加产物。**不带** `-PeratwReachability` 时
    // 上面那条命令行与以前逐字符相同（缺省 OFF 的可复现读数见 ReachabilityCliTest 的基准）；
    // 带上才追加 `--reachability`（以及可选的 `--reach-out`）并把报告 TSV 登记成输出。
    val reachability = project.hasProperty("eratwReachability")
    if (reachability) {
        val reachOut = (project.findProperty("eratwReachOut") as String?)
            ?.takeIf { it.isNotBlank() }
            ?: File(File(out).parentFile, "reachability.tsv").absolutePath
        inputs.property("reachability", "on")
        outputs.file(reachOut)
        args("--reachability", "--reach-out", reachOut)
    }
}

// ---------------------------------------------------------------------------
// erbcc981（REPORT-DEDUP3 滚动池补发路，2026-11-06 收 gate981 路接线项①）：
// erbcc 测试面的 4.981 语料口径定向闸，与 core-vm 的同名闸同形同源。
//
// 病灶：`Com641RealCorpusScanTest` 钉的 `COMF641 アビリティカード_カードパラメータ.ERH`
// 原文只在 4.981 打包根（4.980 根无 COMF640-641 目录），而 erbcc §5 标准闸（configureEach
// 的 ERATW_* 透传）导出的是 4.980 根 ⇒ 用例第二道 assumeTrue 恒 false、静默 skip ——
// 2026-11-06 本路实读 1/0/0/1，闸跑全绿、钉零判定（假绿第四形态：语料根口径不匹配；
// core-vm 侧 gate981 路已登记同款并落了第一座闸）。
//
// 修法不在钉的用例文件里（那会越权），而在跑法口径：本任务把 ERATW_GAMEDIR / ERATW_CORPUS
// 任务级指向 4.981 根，再点名这枚钉 —— 从「静默跳」变成「有判定力的 PASS」。根的发现
// 不硬编本机绝对路径：`-Pcorpus981` 显式指定优先，否则走候选链，逐候选用 4.981 标记
// `ERB/DLC/AI对话补丁/对话调用.erb` 验明正身（与 core-vm 的 CORPUS_981_MARKER 同一文件，
// 三处一个真相）；一个都不命中时 doFirst 判红并打印逐候选判定 —— 负控
// （`-Pcorpus981=<不存在的目录>`）必须红，不许静默空转。
//
//   ./gradlew --configure-on-demand :tools:erbcc:gate981Corpus
//
// 注意：本闸**不读**外部 ERATW_GAMEDIR / ERATW_CORPUS（外部给 4.980 也照样跑 4.981，
// 这正是它的存在意义）；4.980 口径读数照旧由 :tools:erbcc:test 标准闸产出，两边对照就是
// 「按根两列」。结果落 tools/erbcc/build/test-results/gate981Corpus/（Test 任务按任务名
// 分目录，与标准闸互不覆盖）。
// ---------------------------------------------------------------------------

/** 4.981 打包根目录名（与 core-vm 的 CORPUS_981_DIR 同源；换根两处一起改）。 */
private val CORPUS_981_DIR = "eraTW4.981-261017-EAGLOV3"

/**
 * 4.981 标记文件：4.980 根**无**此文件（全树差集实测），命中即认 4.981。
 * 与 core-vm 的 CORPUS_981_MARKER、core-vm 测试装置 GameRoot981 同一文件 —— 三处一个真相。
 */
private val CORPUS_981_MARKER = "ERB/DLC/AI对话补丁/对话调用.erb"

/** 标记命中判定（取证面：既进构建日志也进判红文案）。 */
fun corpus981MarkerHit(dir: File): Boolean =
    dir.isDirectory && dir.resolve(CORPUS_981_MARKER).isFile

/**
 * 4.981 打包根候选链（**取证面**：解析与判红文案同源，只此一处；链形状与 core-vm 逐条同形）。
 *
 * 1. `-Pcorpus981=<游戏根>` 显式指定（权威，指错由 doFirst 判红，不悄悄换目录）；
 * 2. `ERATW_ROOT` 环境变量下的 4.981 打包根（AGENTS §1 的共享根约定）；
 * 3. 仓库检出根（`rootDir.parentFile`）直挂的 4.981 根；
 * 4. worktree 根上级的 `eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3`（两台主机的共享根形状）；
 * 5. worktree 根上级直挂的 4.981 根（根直接放在共享盘根下的摆放）。
 * 公开仓不内置任何主机绝对路径兜底；需要时用 `-Pcorpus981` / `ERATW_ROOT` 显式指定。
 *
 * 外部 `ERATW_GAMEDIR` / `ERATW_CORPUS` **故意不进**候选链：§5 闸给的是 4.980，
 * 本闸的判定力必须不依赖外口径给对根。
 */
fun corpus981Candidates(): List<Pair<File, String>> = buildList {
    providers.gradleProperty("corpus981").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
        add(File(it) to "-Pcorpus981 显式指定")
    }
    System.getenv("ERATW_ROOT")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        add(File(it).resolve(CORPUS_981_DIR) to "ERATW_ROOT 下的 4.981 打包根")
    }
    add(rootDir.parentFile.resolve(CORPUS_981_DIR) to "仓库检出根（rootDir.parentFile）直挂的 4.981 打包根")
    rootDir.parentFile.parentFile?.let { up ->
        add(up.resolve("eratw-安卓移植计划").resolve(CORPUS_981_DIR) to "共享根（worktree 上级）eratw-安卓移植计划 下的 4.981 打包根")
        add(up.resolve(CORPUS_981_DIR) to "worktree 根上级直挂的 4.981 打包根")
    }
}

/** 逐候选的实读判定；既进构建日志也进判红文案。 */
fun corpus981Verdicts(): String = corpus981Candidates()
    .joinToString("\n") { (dir, why) ->
        val verdict = when {
            !dir.isDirectory -> "缺目录（${dir.absolutePath} 不存在或不是目录）"
            !corpus981MarkerHit(dir) -> "目录在位但没有 4.981 标记（缺 $CORPUS_981_MARKER），不是 4.981 打包根"
            else -> "命中（4.981 标记在场）"
        }
        "  - ${dir.absolutePath} [$verdict] —— $why"
    }

/**
 * 解析 4.981 打包根：`-Pcorpus981` 显式给出时**即权威**（只验它，验不过返回 null 由 doFirst
 * 判红 —— 不悄悄换目录）；未给出才按 [corpus981Candidates] 顺序取第一个标记命中的候选。
 * 全落空返回 null。
 */
fun resolveCorpus981(): File? {
    providers.gradleProperty("corpus981").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return File(it).takeIf { dir -> corpus981MarkerHit(dir) }
    }
    return corpus981Candidates().firstOrNull { corpus981MarkerHit(it.first) }?.first
}

/**
 * 4.981 语料口径定向闸（REPORT-DEDUP3 erbcc981 路；任务级供根，与 erbcc §5 标准闸并行的第二遍）。
 *
 * ## 本闸圈到的钉（erbcc 测试面 4.981-only 同族实读，2026-11-06）
 * - `Com641RealCorpusScanTest` 1（COMF641 卡牌 ERH 只在 4.981；erbcc 预扫描把行连续块
 *   还原成全局一维整型变量并登记符号表的**编译侧**半边，与 core-vm 运行侧两份独立实现）。
 *
 * ## 名单外的近亲（实读，均不进本闸）
 * - `SymbolsTest` / `DimErhFamilyConstTest`：语料门只认「env 在 + 目录在」，判根走
 *   `ErbccCorpusProfile.detect` 按根取列 —— 4.980 口径下真跑有判定力，非 4.981-only
 *   （gate981 路登记的「双根真跑不在族内」经本路实读复核成立）；
 * - `SymbolLinkAuditRoundTripTest` / `BootMetaRoundTripTest` / `CompileBridgeTest` /
 *   `DimSpecValidationTest`：同款 env+目录门，双根真跑；
 * - `CorpusScanSubscriptTest.语料实况_裸字符串表不登记氷精`：门文件
 *   `魔改内容/AnonTW搬运口上函数库.ERB` 两根都在（双根真跑），但门写的是裸
 *   `?: return` 加 `if (!isRegularFile) return`（**静默 PASS 形态**，不留 skip 痕）——
 *   文件消失或 env 忘导时这条钉会无声失效，属测试用例面，本路禁碰，交组长裁决。
 *
 * ## 读数纪律
 * - 根缺位 / 标记不命中 / `-Pcorpus981` 指错 ⇒ doFirst 判红（负控必须红，不许静默空转）；
 * - `outputs.upToDateWhen { false }`：判定力闸宁可重跑不可 UP-TO-DATE 假绿（AGENTS §5 语料闸纪律）；
 * - 本闸下允许的**残余 skip 只有 0**；再出现 skip 即本闸失效，验收按红处理。
 * - 堆沿用 configureEach 的 3g（与 erbcc 真语料侧车用例同口径）。
 */
tasks.register<Test>("gate981Corpus") {
    group = "verification"
    description = "4.981 语料口径定向闸（erbcc 面）：Com641RealCorpusScanTest 这枚「原文只在 4.981」的钉" +
        "（erbcc §5 标准闸的 4.980 口径下恒静默跳）—— 任务级供 4.981 根，标记不命中一律判红不空转"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    filter {
        includeTestsMatching("*Com641RealCorpusScanTest*")
    }
    val root981 = resolveCorpus981()
    if (root981 == null) {
        inputs.property("corpus981Root", "<未解析到：见构建日志>")
        logger.lifecycle(
            "[gate981Corpus:erbcc] 未解析到 4.981 打包根 ⇒ 本闸 doFirst 将判红（不许静默空转）。候选逐条：\n" +
                corpus981Verdicts(),
        )
    } else {
        // configureEach 的 ERATW_* 透传先把外口径（可能是 4.980）拷进来，这里按任务口径覆盖：
        environment("ERATW_GAMEDIR", root981.absolutePath)
        environment("ERATW_CORPUS", root981.resolve("ERB").absolutePath)
        inputs.property("corpus981Root", root981.absolutePath)
    }
    doFirst {
        require(root981 != null) {
            "gate981Corpus（:tools:erbcc）需要一个 4.981 打包根（标记 $CORPUS_981_MARKER 必须在场），但候选全落空：\n" +
                corpus981Verdicts() + "\n（也可用 -Pcorpus981=<4.981 游戏根> 显式指定）"
        }
    }
}

// RUST1-B 组长接线：erbcc-rs 的双跑对拍/基准重生成/classpath 产出方是**独立可 apply 的脚本**
// （rust-gradle 路交付，worker 无权改本文件）。脚本对宿主零假设，只用本项目的 `installDist` 与
// `tools/erbcc-rs/` 的相对位置，所以这里只加一行 apply，任务体不落在 Kotlin 侧文件里。
apply(from = rootDir.resolve("tools/erbcc-rs/gradle/erbcc-rs-parity.gradle.kts"))
