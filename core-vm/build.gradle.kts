plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（与 Android 模块的 jvmTarget/compileOptions 同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // 平台 API 兼容闸（2026-10-22 死循环复盘方案 #1，AndroidApiCompatTest）：扫描 core-vm
    // 主源集字节码，方法/字段引用必须存在于 android.jar（compileSdk 编译面）——
    // 「桌面 JDK 有、ART 编译面没有」的调用（Java 9+ 的 Field.trySetAccessible，真机
    // 死循环根因）在桌面闸当场闸红，不再等真机炸三轮。只扫签名不跑方法体（SKIP_CODE）。
    testImplementation(libs.asm)
    // A6 e2e 装置专用：符号表扫描（装载序/dimSpecs）+ 桥接映射器 com.erbcc.bridge.buildFuncDims。
    // 方向安全：erbcc(main) → core-vm(main)；core-vm(test) → erbcc(main) 无环（测试源集独立）。
    testImplementation(project(":tools:erbcc"))
}

// ---------------------------------------------------------------------------
// CMP-69：真产物档 + savepin 波（2026-11-05）的真存档接线
//
// 旧状况：§5 闸（AGENTS.md §5：`ERATW_GAMEDIR=<游戏根>` + `ERATW_CORPUS=<游戏根>/ERB`）从不
// 导出 ERATW_ERBC / ERATW_ERBC_DIR / ERATW_SAVE_SAMPLES / ERATW_ANDROID_JAR，于是依赖 332 MB
// 真产物的段流式/侧车/mmap 用例与「真档改一个变量再写」「外部真实存档逐字节回环」两条硬钉
// 全部停在 assumeTrue 上 —— 闸跑完全绿、硬钉一条没跑（假绿）。公开仓不含内部存档备份，
// 故真存档样本只认显式来源（`-PsaveSamples` / `ERATW_SAVE_SAMPLES`），缺失时按适配口径 skip。
//
// 两条档分开处理：
//  - **真存档**：6 份 `.sav` 合计约 370 KB，没有堆成本 ⇒ **无条件接**（[wireRealSaveSamples]
//    在 `tasks.withType<Test>` 里对每个 Test 任务生效）⇒ §5 标准闸从此真的读它们；
//  - **真产物**（`program.erbc`，332 MB）：L01 实测真跑十几分钟、且要够大堆，不能无条件接进
//    §5 ⇒ 仍是显式档（`-PwithRealArtifacts=true` / `:core-vm:gateRealArtifacts`），但把它改成
//    **显式门**：档一开就连带导出 `ERATW_REAL_ERBC_REQUIRE=1`，产物缺席时测试**判红**，
//    不再「档开了照样静默 skip」（口径同 `SessionRetainmentCanaryTest` 的 `ERATW_CANARY_REQUIRE`）。
//
//   .\gradlew :tools:erbcc:compileCorpus         # 先出产物（约 1.5 分钟 / 6g）
//   .\gradlew :core-vm:gateRealArtifacts        # 真产物子集（约 3 分钟 / 3g 堆）
//   .\gradlew -PwithRealArtifacts=true :core-vm:test   # 整包 + 真产物用例
//
// 不带 `-PwithRealArtifacts` 时真产物变量一个都不导出（与改前一致）；带上前产物缺失会在
// doFirst 直接失败。`gateRealArtifacts` 名单见其自身 KDoc 的实读计数。
// ---------------------------------------------------------------------------

/** `-PwithRealArtifacts=true` 才把真产物变量接到 Test 任务上。 */
val withRealArtifacts: Provider<Boolean> = providers.gradleProperty("withRealArtifacts")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

/** 真产物目录；缺省 = `:tools:erbcc:compileCorpus` 的默认输出（`erbcc/build/erbc-out/erbc.v1`）。 */
val realErbcDir: Provider<File> = providers.gradleProperty("erbcDir")
    .map { File(it) }
    .orElse(
        providers.provider {
            layout.projectDirectory.dir("../tools/erbcc/build/erbc-out/erbc.v1").asFile
        },
    )

/**
 * 真存档样本目录：公开仓**只认显式来源** —— `-PsaveSamples=<目录>` 或环境变量
 * `ERATW_SAVE_SAMPLES`。公开仓不携带任何内部存档备份路径；没有显式来源时真存档用例
 * 按公开适配口径跳过（上游是硬判红）。
 */
fun resolveRealSaveSamples(): File? =
    providers.gradleProperty("saveSamples").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
        ?: providers.environmentVariable("ERATW_SAVE_SAMPLES").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

/**
 * 把真存档样本目录接到 Test 任务：解析到就导出 `ERATW_SAVE_SAMPLES` 并记进 `inputs`
 * （换根必须重跑，否则 UP-TO-DATE 假绿）；未解析到则不导出，测试侧按公开适配口径 skip。
 *
 * @return 解析到的目录；null = 未提供（等价于「未设置」）。
 */
fun Test.wireRealSaveSamples(): File? {
    val save = resolveRealSaveSamples()
    if (save == null) {
        logger.lifecycle("[saveSamples] 未提供 -PsaveSamples / ERATW_SAVE_SAMPLES ⇒ 真存档用例按公开适配口径跳过。")
        return null
    }
    environment("ERATW_SAVE_SAMPLES", save.absolutePath)
    inputs.property("realSaveSamples", save.absolutePath)
    return save
}

/**
 * 把真产物档的两个环境变量接到当前 Test 任务。
 *
 * `ERATW_ERBC` 与 `ERATW_ERBC_DIR` 同时给：前者被 LazyStatementDecode / ErbcStream 族直接读，
 * 后者被侧车/性能族读（两套查找顺序见各测试的 findErbcDir）。`ERATW_ANDROID_JAR`
 * 只从 `-PandroidJar` 显式提升（未给时沿用环境里的同名变量，再不行由测试自己找 SDK）。
 * 产物缺失在 doFirst 失败 —— 这是本档存在的意义，不能退回 assumeTrue。
 *
 * 另外连带导出 `ERATW_REAL_ERBC_REQUIRE=1`（显式门）：档位既已开启，「找不到产物」就是**事件**，
 * 测试必须判红而不是静默跳过。
 */
fun Test.wireRealArtifacts() {
    val dir = realErbcDir.get()
    val erbc = dir.resolve("program.erbc")
    environment("ERATW_ERBC_DIR", dir.absolutePath)
    environment("ERATW_ERBC", erbc.absolutePath)
    environment("ERATW_REAL_ERBC_REQUIRE", "1")
    inputs.property("realErbcDir", dir.absolutePath)

    providers.gradleProperty("androidJar").orNull?.let {
        environment("ERATW_ANDROID_JAR", File(it).absolutePath)
    }

    doFirst {
        require(erbc.isFile) {
            "真产物档已开启但找不到 ${erbc.absolutePath}；先跑 :tools:erbcc:compileCorpus" +
                "（ERATW_CORPUS 必须指到 <游戏根>/ERB），或用 -PerbcDir=<erbc.v1 目录> 指定"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 全量语料（2.6M 行）的语句 AST + 编译产物需要更大堆；默认 512m 会 OOM 掉 worker，
    // 1536m 在 CorpusCompileSmokeTest（带 ERATW_CORPUS 全量回归）同样实测 OOM —— 与
    // corpusSmoke 的 3g 对齐。
    maxHeapSize = "3g"
    // 挡位旋钮（测量车道，2026-11-03 加）：`-PtestXmx=512m` 把 test worker 降到真机 largeHeap 挡位。
    //
    // 为什么只能在这里改：`HeapWallProbeTest` 的判据是「4.981 装载峰值 ≤ 512 MiB」，而峰值读数走
    // `HeapMeter`（`Runtime.getRuntime().maxMemory()` / `totalMemory()-freeMemory()`）—— 读数天然被
    // 本任务的堆上限夹住，桌面闸跑在 3g 就永远量不出「装不装得下 512」。要量只能让**被测进程本身**
    // 是 512 MiB 的 JVM（fork 子 JVM 那条路在 Gradle 8 上取不到真实 classpath：test worker 的
    // `java.class.path` 只有 gradle-worker.jar，应用类路径在自定义 ClassLoader 里）。
    // 缺省不传 ⇒ maxHeapSize 仍是 3g，与改前逐字一致（其余 40 余条断言用例的堆口径不变）。
    providers.gradleProperty("testXmx").orNull?.let { maxHeapSize = it }
    // 语料冒烟开关（ERATW_CORPUS 等）显式透传给 test JVM（Windows 下 worker 不总继承）
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
    // 跨环境取证旋钮：ERATW_TEST_TZ=America/New_York 一类 → test JVM 的 user.timezone。
    // 用于验证 L3 链常量对系统时区的免疫性（修复前用它抓时区泄漏簇，修复后用它验收）。
    System.getenv("ERATW_TEST_TZ")?.let { systemProperty("user.timezone", it) }
    // 锁等待采样旋钮（FEEL1-L3）：`-Plockwatch=true` → test JVM 的 `eratw.lockwatch`，
    // 让 `LockWatch` 的六处探针在桌面闸里真的打开（缺省不传 ⇒ enabled 仍是 false，
    // 挂载点连 nanoTime 都不取，与挂载前逐字同行为）。口径照上面 `testXmx` 的 gradleProperty 透传。
    providers.gradleProperty("lockwatch").orNull?.let { systemProperty("eratw.lockwatch", it) }
    // CMP-69：默认关闭；开启时只多导出真产物变量（+ 显式门 ERATW_REAL_ERBC_REQUIRE=1），其余口径不变。
    if (withRealArtifacts.get()) wireRealArtifacts()
    // savepin 波（2026-11-05）：真存档只有几十 KB、没有堆成本 ⇒ **无条件**接，
    // §5 标准闸从此真的跑「外部真实存档只读解析与逐字节回环」与「真档改一个变量再写形状保持」
    // （改前这两条只在 -PwithRealArtifacts 档里才被接线，标准闸每次都是 skip = 假绿）。
    wireRealSaveSamples()
}

/**
 * CMP-69 真产物/真存档子集闸（savepin 波 17 条 + realarttag 路 2026-11-06 补圈 26 条 = 43 条）。
 *
 * ## 本闸圈到的 43 条（逐类点名；名字模式与 filter 一一对应）
 * - savepin 波原 17 条（5 个名字模式**原样保留**，一条不许少）：
 *   `*真实产物*` 11（`ErbcStreamAllocProfileTest` / `ErbcStreamEquivalenceTest` / `ErbcStreamPeakTest` 各 1、
 *   `LazyStatementDecodeTest` 4、`MappedCodeSourceTest` 1、`ProgramMmapReleaseTest` 2、`StreamLoadTest` 1）、
 *   `*侧车加速档*` 1（`StmtOffsetSidecarTest`）、`*外部真实存档*` 1（`RealSaveSamplesProbeTest`）、
 *   `*真档改一个变量再写*` 1（`SaveFormatMutatedWriteTest`）、`*ErbcStreamBaseRealTest` 3；
 * - realarttag 路补圈的 26 条（缺口节的产物门控用例，逐类实读后圈定）：
 *   `AndroidLoadPeakTest` 4（方法级点名，见下方「出入」第 0 条）、`HeapWallProbeTest` 2、
 *   `KojoPackageAcceptanceSmokeTest` 2、
 *   `BootMetadataModelSnapshotTest` 3（缺口节写的「真侧车 2」实读漏了同为产物门的「入库指纹…」第 3 条，一并圈）、
 *   `RedKDisasmProbeTest` 2、`RealCorpusPatchSmokeTest` 1、`RedKCharaMovementReproTest` 1、
 *   `RedOProductInspectTest` 1、`PoolDecompAuditTest` 1、`LoadTimingProbe` 1、
 *   `LoadChurnProbeTest` 5（方法级点名；缺口节写的「3」实读是 5 条产物门控钉）、
 *   `TurnLatencyProbeTest` 3（方法级点名）。
 *
 * ## 本闸允许的残余 skip 只有 6 条（多一条 skip 即本闸失效，验收按红处理）
 * - `AndroidLoadPeakTest` 生产档 4_980 两条：门是 `ERATW_ERBC_DIR_BASE` —— 全仓 build 脚本无值源
 *   （孤儿 env）⇒ 任何档位都自证 skip；要判定力需组长给 `wireRealArtifacts` 接第二份产物目录；
 * - `HeapWallProbeTest` 两条：4_981 墙钉要求本任务堆挡位 ≤ 512 MiB（`-PtestXmx=512m`，而本闸其余
 *   用例要 3g 不能整体降挡）⇒ 自证 skip；4_980 对照钉同 `ERATW_ERBC_DIR_BASE` 孤儿门；
 * - `KojoPackageAcceptanceSmokeTest` 两条：门是 `ERATW_KOJO_PKG`（孤儿 env）⇒ 自证 skip。
 *
 * ## realArtifacts Tag 家族：已打标，includeTags 为什么还没接线（realarttag 路实证）
 * 缺口节交办的 `@Tag("realArtifacts")` 化已落：缺口节 12 个文件 27 条产物门控钉全部打标（类级 10 类、
 * 方法级 `LoadChurnProbeTest` 5/6 与 `TurnLatencyProbeTest` 3/4 —— 各自的 `ERATW_PERF_BENCH` /
 * `ERATW_PERF_AB` 旋钮钉不属产物闸，不打标）。但 `useJUnitPlatform { includeTags }` 尚不能进：
 * 实读 Gradle 8.10.2 字节码（`JUnitPlatformTestClassProcessor.addTagsFilter` / `addTestNameFilters`），
 * `TagFilter.includeTags` 与名字过滤 `ClassMethodNameFilter` **都是 PostDiscoveryFilter**，Launcher 按
 * **合取**应用（全部通过才收）⇒ 名字模式与 includeTags 是 **AND** 不是 OR。既有 17 条里 savepin
 * 三测试文件（`RealSaveSamplesProbeTest` / `SaveFormatMutatedWriteTest` / `ErbcStreamBaseRealTest`，
 * 共 5 条）禁碰打不了 Tag ⇒ 现在加 includeTags 会把它们踢出闸，违反「17 条必须原样在内」。
 * 所以本闸圈定仍走名字模式（Tag 目前是家族标记，不参与圈定）；等这 3 个文件与既有 17 条的
 * 另外 8 个文件（`ErbcStreamAllocProfileTest` / `ErbcStreamEquivalenceTest` / `ErbcStreamPeakTest` /
 * `LazyStatementDecodeTest` / `MappedCodeSourceTest` / `ProgramMmapReleaseTest` / `StreamLoadTest` /
 * `StmtOffsetSidecarTest`，非本路所有权）也打上 Tag 后，把上面 5 个旧模式收敛掉、加
 * `useJUnitPlatform { includeTags("realArtifacts") }` 即完成一行切换。
 *
 * ## 缺口（realarttag 路实读订正后的余量，交组长）
 * 0. `AndroidLoadPeakTest.生产档 4_981 逐段瞬时与峰值` 打了 Tag 但**不圈**（本路唯一一条假红风险钉）：
 *    它的判定含「指令数 > 5,000,000 = 4.981 全量产物」地板，而本闸 `-PerbcDir` 接的稳定 bless 家
 *    `bootmeta-bless-cbf0f458` 实读是 **4.980 产物**（manifest `corpus-root=…eratw4.980\ERB`，
 *    指纹文件 artifact=cbf0f458… 绑定同一份）⇒ 实跑指令数 4,884,338 条，地板判假红。
 *    判定逻辑本路一字不动，等共享根落一份 4.981 erbc.v1（`-PerbcDir` 指过去）或组长裁定产物标准后再圈；
 * 1. 缺口节 item-1 的 8 条宽模式产物钉（`GraphicsPoolBudgetProbeTest` 1、`BootMetaParamDefaultsTest` 2、
 *    `RealArtifactForStepProbeTest` 1、`FuncTableLayoutProbeTest` 1、`FuncTableRootSplitProbeTest` 1、
 *    `HeapCompositionProbeTest` 1、`SessionRetainmentCanaryTest` 1）维持 savepin 裁定不圈：圈进来闸耗时
 *    从几分钟涨到十几分钟；其中 `BootMetaParamDefaultsTest.旧侧车真产物照样装载` 读 `ERATW_ERBC_LEGACY_DIR`
 *    （`wireRealArtifacts` 从不导出）⇒ 恒自证 skip，要它跑得先有第二份产物目录的接线口子；
 * 2. 缺口节 item-2 名单里实读后**不打标也不圈**的出入（逐条对账）：
 *    - `DlcPagingReproTest` 1：已由 `:core-vm:gate981Corpus` 圈定（4.981 口径专用，Addition_DLC_70 标记门）；
 *      且它产物解析「`ERATW_ERBC_DIR` 显式值优先」—— 本闸一导出 `ERATW_ERBC_DIR` 就会把它的
 *      DLC 产物覆写成主产物，圈进来反而换掉它量什么，不圈；
 *    - `OptionPatchErbcProbeTest` 1：门是 `ERATW_ERBC_4981`（本仓零接线、缺省路径在本机 worktree
 *      不在位），且缺产物走裸 `return`（静默 PASS 形态）—— 接线与判定形态都待组长，圈进来是假绿；
 *    - `FuncTableFormBenchTest` / `FuncTableQueryBenchTest` / `SkeletonFetchBenchTest` 各 1：
 *      `ERATW_PERF_BENCH=N` 才跑，自带 KDoc 写明「不进常规闸」，是旋钮档不是产物闸；
 *    - `LoadChurnProbeTest.vars 面热路径窄基准` 1 与 `TurnLatencyProbeTest.构造期开关 A_B` 1：同上旋钮档。
 *
 * 全量档现在给每个 Test 任务都导出 `ERATW_REAL_ERBC_REQUIRE=1`，所以档位一开而产物缺席就是**判红**，
 * 不再静默 skip。
 *
 * 产物由调用方先跑 `:tools:erbcc:compileCorpus` 产出（CMP-66 同族：产物必须跟当前编译器
 * 字节码同源；这里不挂跨项目 dependsOn —— 实测 `--configure-on-demand` 下会触发
 * `DefaultClassLoaderScope must be locked` 配置期竞态）。
 * 堆沿用 `tasks.withType<Test>` 的 3g：`ErbcStreamEquivalenceTest` 的「一次只驻留一个 Program」
 * 靠两条路径之间的 `System.gc()` 错开。
 *
 * **不许静默缩水**：产物与真存档样本缺一即红（doFirst `require` + 测试侧判红）；
 * 除「残余 skip 清单」点名的 6 条外，出现任何 skip 都按闸失效处理。
 */
tasks.register<Test>("gateRealArtifacts") {
    group = "verification"
    description = "真产物/真存档子集闸：43 条（savepin 波 17 条名字模式原样保留 + realarttag 路补圈 26 条缺口产物钉）；" +
        "产物或真存档缺席一律判红不跳过；残余 skip 只许 6 条自证（见 KDoc 清单）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    // 堆与 :core-vm:test 一致（3g，见上方 configureEach 的注释与实测读数）。
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
    filter {
        includeTestsMatching("*真实产物*")
        // StmtOffsetSidecarTest 的真产物用例方法名不含「真实产物」，按它的方法名单独圈：
        includeTestsMatching("*侧车加速档*")
        includeTestsMatching("*外部真实存档*")
        // savepin 波补齐的两条缺口：真档改动硬钉（旧名单圈不到）与本路 ErbcStreamBaseRealTest 三条。
        includeTestsMatching("*真档改一个变量再写*")
        includeTestsMatching("*ErbcStreamBaseRealTest*")
        // realarttag 路（2026-11-06）补圈的 26 条缺口产物钉：类级模式 9 个 + 方法级模式 10 个。
        // 这批用例已全部 @Tag("realArtifacts")（Tag 是家族标记，暂不参与圈定 —— 为什么 includeTags
        // 还进不了闸，见本任务 KDoc「realArtifacts Tag 家族」节；名字模式是现状口径）。
        // AndroidLoadPeakTest 是混合类，方法级点名 4 条：生产档 4_980 两条（孤儿 env 自证 skip）、
        // 4_981 催 GC 与 512m 对照两条（判定与产物校准无关，bless 上真跑）；
        // 「生产档 4_981 逐段瞬时与峰值」不圈 —— 它的 5,000,000 指令地板按 4.981 产物校准，
        // bless 家是 4.980 产物（实跑 4,884,338 条）⇒ 圈入必假红，见 KDoc「缺口」第 0 条。
        includeTestsMatching("*AndroidLoadPeakTest.生产档 4_981 逐段催 GC*")
        includeTestsMatching("*AndroidLoadPeakTest.512m 挡位*")
        includeTestsMatching("*AndroidLoadPeakTest.生产档 4_980 逐段瞬时与峰值*")
        includeTestsMatching("*AndroidLoadPeakTest.生产档 4_980 逐段催 GC*")
        includeTestsMatching("*HeapWallProbeTest*")
        includeTestsMatching("*KojoPackageAcceptanceSmokeTest*")
        includeTestsMatching("*BootMetadataModelSnapshotTest*")
        includeTestsMatching("*RedKDisasmProbeTest*")
        includeTestsMatching("*RedOProductInspectTest*")
        includeTestsMatching("*RealCorpusPatchSmokeTest*")
        includeTestsMatching("*RedKCharaMovementReproTest*")
        includeTestsMatching("*PoolDecompAuditTest*")
        includeTestsMatching("*LoadTimingProbe*")
        // LoadChurnProbeTest 是混合类：5 条产物门控钉点名圈入，ERATW_PERF_BENCH 基准钉不圈。
        includeTestsMatching("*LoadChurnProbeTest.生产装载链*")
        includeTestsMatching("*LoadChurnProbeTest.侧车*")
        includeTestsMatching("*LoadChurnProbeTest.metaSidecarReps*")
        includeTestsMatching("*LoadChurnProbeTest.会话装配*")
        // TurnLatencyProbeTest 同为混合类：3 条产物门控钉点名圈入，ERATW_PERF_AB 的 A/B 钉不圈。
        includeTestsMatching("*TurnLatencyProbeTest.量同样多指令的墙钟*")
        includeTestsMatching("*TurnLatencyProbeTest.分类表达式池*")
        includeTestsMatching("*TurnLatencyProbeTest.邻接对*")
    }
    wireRealArtifacts()
    // 真存档无条件接线（与 :core-vm:test 同一份解析），本闸再额外要求它在位。
    val saveSamples = wireRealSaveSamples()
    doFirst {
        require(saveSamples != null) {
            "gateRealArtifacts 需要真存档样本目录：请用 -PsaveSamples=<目录> 或 " +
                "ERATW_SAVE_SAMPLES=<目录> 显式指定（公开仓不内置内部存档备份）。"
        }
    }
}

// ---------------------------------------------------------------------------
// gate981（REPORT-DEDUP3 排队路，2026-11-06 发车）：§5 的第二遍语料口径。
//
// 病灶：skipinv/ledger 家族实测有一批钉的**语料原文只存在于 4.981 打包根**
// （全树差集实测：两根同名文件差 2,146 份，含 COMF641 卡牌 ERH、qol_db.ERB、
// Addition_DLC_70.ERB、对话调用.erb、QOL 物品库 1200 槽等）。这些钉都自带
// 4.981 语料门（`GameRoot981` 标记链 / SKIASHARP 配置标记 / Addition_DLC_70 注册表 /
// COMF641 ERH 在场性），而 §5 标准闸导出的 `ERATW_GAMEDIR` 是 4.980 根 ⇒ 门判 false、
// `assumeTrue` 静默跳，闸跑完全绿、钉一条没判（假绿的第四种形态：语料根口径不匹配）。
//
// 修法不在钉的用例文件里（那会越权），而在跑法口径：本任务把 `ERATW_GAMEDIR` /
// `ERATW_CORPUS` **任务级**指向 4.981 根，再按类点名这批钉 —— 同一批钉从「静默跳」
// 变成「有判定力的 PASS」。根的发现不硬编本机绝对路径：`-Pcorpus981` 显式指定优先，
// 否则走候选链（仓库根直挂 / 共享根 / 两台主机已知共享根），逐候选用 4.981 标记
// `ERB/DLC/AI对话补丁/对话调用.erb`（与测试装置 `GameRoot981` 同一标记）验明正身；
// 一个都不命中时 doFirst 判红并打印逐候选判定 —— 负控（`-Pcorpus981=<不存在的目录>`）
// 必须红，不许静默空转。
//
//   .\gradlew --configure-on-demand :core-vm:gate981Corpus     # 一把跑 10 类 27 枚钉
//
// 注意：本闸**不读**外部 ERATW_GAMEDIR / ERATW_CORPUS（外部给的是 4.980 也照样跑 4.981，
// 这正是它的存在意义）；4.980 口径的读数照旧由 §5 标准闸产出，两边对照就是「按根两列」。
// ---------------------------------------------------------------------------

/** 4.981 打包根目录名（与测试装置 `GameRoot981.DIR` 同源；换根两处一起改）。 */
private val CORPUS_981_DIR = "eraTW4.981-261017-EAGLOV3"

/**
 * 4.981 标记文件：4.980 根**无**此文件（全树差集实测），命中即认 4.981。
 * 与 `GameRoot981` 的 MARKER 同一文件 —— 任务侧与测试侧认同一个根，不许出现两个真相。
 */
private val CORPUS_981_MARKER = "ERB/DLC/AI对话补丁/对话调用.erb"

/** 标记命中判定（取证面：既进构建日志也进判红文案）。 */
fun corpus981MarkerHit(dir: File): Boolean =
    dir.isDirectory && dir.resolve(CORPUS_981_MARKER).isFile

/**
 * 4.981 打包根候选链（**取证面**：解析与判红文案同源，只此一处）。
 *
 * 1. `-Pcorpus981=<游戏根>` 显式指定（权威，指错由 doFirst 判红，不悄悄换目录）；
 * 2. `ERATW_ROOT` 环境变量下的 4.981 打包根（AGENTS §1 的共享根约定）；
 * 3. 仓库检出根（`rootDir.parentFile`，主树与 worktree 都在共享盘上）直挂的 4.981 根；
 * 4. worktree 根上级的 `eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3`（两台主机的共享根形状）；
 * 5. worktree 根上级直挂的 4.981 根（根直接放在共享盘根下的摆放）。
 * 公开仓不内置任何主机绝对路径兜底；需要时用 `-Pcorpus981` / `ERATW_ROOT` 显式指定。
 *
 * 外部 `ERATW_GAMEDIR` / `ERATW_CORPUS` **故意不进**候选链：§5 闸给的是 4.980，
 * 本闸的判定力必须不依赖外口径给对根（防「哪个闸吃哪个根」再次失联）。
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
 * 判红 —— 不悄悄换目录，口径照 `resolveRealSaveSamples`）；未给出才按 [corpus981Candidates]
 * 顺序取第一个标记命中的候选。全落空返回 null。
 */
fun resolveCorpus981(): File? {
    providers.gradleProperty("corpus981").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return File(it).takeIf { dir -> corpus981MarkerHit(dir) }
    }
    return corpus981Candidates().firstOrNull { corpus981MarkerHit(it.first) }?.first
}

/**
 * 4.981 语料口径定向闸（REPORT-DEDUP3 gate981 路；任务级供根，与 §5 标准闸并行的第二遍）。
 *
 * ## 本闸圈到的 10 类 27 枚钉（4.980 §5 口径下逐类 skip 实读，2026-11-06）
 * - `Com641ProductionChainDimLoadPinTest` 4（COMF641 卡牌 ERH 只在 4.981）；
 * - `Com641RealErhDimLoadTest` 2（同上，单份 ERH 原文手喂链）；
 * - `ItemNameCorpusAlignmentTest` 3（qol_item.ERB 1200 槽 + 退役 4.980 混装反例）；
 * - `PowerFloatRealCorpusTest` 4（Add_Wife the Dust.ERB 的 POWER 浮点行）；
 * - `SourceDownbasePowerWireTest` 4（SOURCE_DOWNBASE 行号 625 的真容器链）；
 * - `ChatActionRealCorpusReproTest` 2（AI返回参数.ERH + 对话调用.erb 行34）；
 * - `CallSharpCorpusCensusTest` 1（对话调用.erb 的 CALLSHARP 全量普查）；
 * - `RandZeroArgChainAuditTest` 1（小鈴事件行号在 4.981 口径的差）；
 * - `U981Config981GateTest` 5（emuera.config 的 SKIASHARP 标记 = 4.981 配置链）；
 * - `DlcPagingReproTest` 1（Addition_DLC_70 注册表 + DLC erbc 产物，本地 `.tooling/erbc/dlc-erbc.v1` 在位）。
 *
 * ## 名单外两个近亲（实测双根都真跑，**不进**本闸）
 * `PluginSaveSlotFlowTest`（RealCorpus 门任根都过，QOL_DB_INIT 是函数表存在性守卫不是 skip 门）
 * 与 `CharaListDtCorpusProbeTest`（RealCorpus 门任根都过）—— 4.980 口径实测 4/0/0/0 与 2/0/0/0。
 *
 * ## 读数纪律
 * - 根缺位 / 标记不命中 / `-Pcorpus981` 指错 ⇒ doFirst 判红（负控必须红，不许静默空转）；
 * - `outputs.upToDateWhen { false }`：判定力闸宁可重跑不可 UP-TO-DATE 假绿（AGENTS §5 语料闸纪律）；
 * - 本闸下允许的**残余 skip 只有两处**：`RandZeroArgChainAuditTest` 的 2 枚 4.980 门钉
 *   （GameRoot980 候选链在本机找到 4.980 根时它们真跑，找不到才跳 —— 按根两列的对称面）；
 *   其余出现 skip 即本闸失效，验收按红处理。
 * - 堆与 §5 标准闸一致（configureEach 的 3g）；`Com641ProductionChainDimLoadPinTest` 要做
 *   全量符号表收集（sliceFilter 只收编译切片），单类分钟级是常态。
 */
tasks.register<Test>("gate981Corpus") {
    group = "verification"
    description = "4.981 语料口径定向闸：10 类 27 枚「原文只在 4.981」的钉（§5 的 4.980 口径下全部静默跳）" +
        "—— 任务级供 4.981 根，标记不命中一律判红不空转"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    filter {
        includeTestsMatching("*Com641ProductionChainDimLoadPinTest*")
        includeTestsMatching("*Com641RealErhDimLoadTest*")
        includeTestsMatching("*ItemNameCorpusAlignmentTest*")
        includeTestsMatching("*PowerFloatRealCorpusTest*")
        includeTestsMatching("*SourceDownbasePowerWireTest*")
        includeTestsMatching("*ChatActionRealCorpusReproTest*")
        includeTestsMatching("*CallSharpCorpusCensusTest*")
        includeTestsMatching("*RandZeroArgChainAuditTest*")
        includeTestsMatching("*U981Config981GateTest*")
        includeTestsMatching("*DlcPagingReproTest*")
    }
    val root981 = resolveCorpus981()
    if (root981 == null) {
        inputs.property("corpus981Root", "<未解析到：见构建日志>")
        logger.lifecycle(
            "[gate981] 未解析到 4.981 打包根 ⇒ 本闸 doFirst 将判红（不许静默空转）。候选逐条：\n" +
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
            "gate981Corpus 需要一个 4.981 打包根（标记 $CORPUS_981_MARKER 必须在场），但候选全落空：\n" +
                corpus981Verdicts() + "\n（也可用 -Pcorpus981=<4.981 游戏根> 显式指定）"
        }
    }
}

/** 语料编译冒烟（绕开 test worker 的直跑入口）：ERATW_CORPUS=… gradle :core-vm:corpusSmoke */
tasks.register<JavaExec>("corpusSmoke") {
    mainClass.set("com.eratw.port.core.compile.CorpusSmokeMainKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "3g"
    // CMP-61：该任务此前漏了兄弟任务都有的 ERATW_* 透传（Windows 下 JavaExec 不总继承父进程
    // 环境），与 :core-vm:test / bootSmoke / perfBaseline 同写法。缺 env 现在由 CorpusSmokeMain
    // 直接 exit 2，不再「打印一句 return 0」。
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * 真实游戏 headless 开局冒烟：全量语料 → 单一 Program → BEGIN TITLE/FIRST → 首个阻塞点。
 * 输出 `build/boot-report.md`（cwd = core-vm 模块目录）。测量用途，退出码不表示游戏走通。
 *
 * `ERATW_*` 显式透传（Windows 下 JavaExec 不总继承父进程环境）。
 */
tasks.register<JavaExec>("bootSmoke") {
    group = "verification"
    description = "真实语料全量编译 + BEGIN TITLE/FIRST headless 开局冒烟（写 build/boot-report.md）"
    mainClass.set("com.eratw.port.boot.GameBootSmokeKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * PERF-1：全量语料内存 / 冷启动基线实测（**只测量，不改语义**）。
 *
 * 四段常驻（空 JVM / 原文读入 / `Program` 构建 / `ProcessRuntime` + `begin`）
 * + `Program` 各池逐项计数与占用估算 + 冷启动三段计时，写 `build/perf-report.md`。
 * 堆与 `bootSmoke` 对齐（6g），避免 GC 策略差异污染跨装置对照。
 */
tasks.register<JavaExec>("perfBaseline") {
    group = "verification"
    description = "全量语料四段常驻 + Program 池分解 + 冷启动三段计时（写 build/perf-report.md）"
    mainClass.set("com.eratw.port.perf.PerfBaselineKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * 真机同款冷启链路计时（2026-10-23 性能专项）：`program.erbc` 分段解码 → `program.meta`
 * 解析 → `GameSession` 装配（CSV + #DIM + ProcessRuntime）→ `begin(TITLE)` 首阻塞点。
 * 只测量不改语义；缺产物时打印提示退出 0。
 *
 * `ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> gradle :core-vm:erbcBootProfile`
 */
tasks.register<JavaExec>("erbcBootProfile") {
    group = "verification"
    description = "真机同款冷启链路计时：erbc 分段 + meta + GameSession + begin(TITLE)"
    mainClass.set("com.eratw.port.perf.ErbcBootProfileKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * 设备挡位装载探针（2026-10-30 玩家两张「启动失败：堆不足」的归因装置）。
 *
 * 与 `erbcBootProfile` 的差别只有两样：**堆挡位按真机 `largeHeap` 给**（缺省 512m，
 * `ERATW_PROBE_XMX` 可换挡做对照），以及**装载口径按设备侧**（`flatStatements = false`）。
 * 桌面闸一路跑在 3g/6g，所以「512 MiB 挡位到底装不装得下、装不下的那一段吃了多少」这件事
 * 在桌面上从来没有被量过 —— 失败屏只会留下段边界的旧读数。
 *
 * `ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> gradle :core-vm:deviceTierProbe`
 */
tasks.register<JavaExec>("deviceTierProbe") {
    group = "verification"
    description = "设备挡位（缺省 -Xmx512m）真产物装载探针：逐区间峰值/分配归因"
    mainClass.set("com.eratw.port.perf.DeviceTierLoadProbeKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = System.getenv("ERATW_PROBE_XMX")?.takeIf { it.isNotBlank() } ?: "512m"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * OBS-HEAPROOT 逐根归因探针（**正式任务**，非 TEMP）：真产物装载到 `GameSession` 装配完成，
 * 按根逐面报 live 常驻（指令/code 段、常量池、语句对象树含扁平池、串表/局部串表、
 * FormStringTerm 族横切、函数表/标签表、CSV 表、变量池、控制台行表、未归类兜底），
 * 出口 = `build/heap-root-attribution.md` 的逐根表 + 「入账合计 + 未归类 = live 总额」恒等式
 * （OVERCOUNT 时 exit 1；账面算术本身钉在 `HeapRootLedgerTest`）。
 *
 * 参数：
 * - `-Pprobe.xmx=<size>`：JVM 挡位。**缺省 3g** —— 归因遍历的访问集是几十 MiB 级活物，
 *   512m 养不起；复刻设备挡位用 `-Pprobe.xmx=512m` 并配 `ERATW_PROBE_WALK=0`（只出 live 总额）；
 * - 环境沿用既有口径：`ERATW_ERBC_DIR`（或 `ERATW_ERBC`）指产物目录、`ERATW_GAMEDIR` 指游戏根，
 *   缺任一 exit 2；
 * - 旋钮：`ERATW_PROBE_FLAT=1` 开扁平池对照（缺省设备口径关）；`ERATW_PROBE_JCMD=0` 跳
 *   `jcmd GC.class_histogram` 交叉核对；`ERATW_PROBE_SAMPLE=<n>` 物化抽样条数（0=关）；
 *   `ERATW_PROBE_OUT=<路径>` 换报告落点；`ERATW_PERF_ROUNDS=<n>` GC 轮数。
 *
 * ```
 * ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> \
 * ./gradlew --configure-on-demand :core-vm:heapRootAttribution -Pprobe.xmx=3g
 * ```
 */
tasks.register<JavaExec>("heapRootAttribution") {
    group = "verification"
    description = "真产物装载后的堆逐根归因表（R7 设计参量；写 build/heap-root-attribution.md）"
    mainClass.set("com.eratw.port.perf.HeapRootAttributionProbeKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = providers.gradleProperty("probe.xmx").getOrElse("3g")
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * T2-64 波次 A 闸：真语料全量对拍。装载 `program.erbc` → 语句池全量压扁 → 逐条还原 →
 * 与原件逐条对拍（类 / code / line / 结构键），要求 0 差异（CONTRACT-T2-64 §6.1）。
 *
 * `ERATW_ERBC_DIR=<erbc.v1 目录> gradle :core-vm:flatStmtAudit`；退出码 0 = 全过。
 */
tasks.register<JavaExec>("flatStmtAudit") {
    group = "verification"
    description = "T2-64 波次 A：真语料语句池全量压扁-还原对拍（0 差异闸）"
    mainClass.set("com.eratw.port.boot.FlatStmtCorpusMainKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * T2-64 波次 C 闸：真语料**双路等价**对拍（`CONTRACT-T2-64-C` §3-G）。
 *
 * 装载 `program.erbc` → `ensureFlatStmtSource()` → 从语句池逐条对**同一语句**双跑
 * （树路径 `execute(stmt, ctx)` vs 扁平路径 `execute(source, index, ctx)`），比对控制台输出 /
 * `ExecResult` / 异常文案+位置 / 变量写面 / 抽样终态，要求 0 差异。需要真实宿主或会阻塞的
 * 语句族在装置里跳过（判定表见 `DualPathCorpusMain.kt` 的 KDoc）。
 *
 * `ERATW_ERBC_DIR=<erbc.v1 目录> gradle :core-vm:dualPathAudit`；退出码 0 = 全过。
 * 规模与墙钟：`ERATW_DUAL_LIMIT`（缺省 200000 条）、`ERATW_DUAL_SECONDS`（缺省 240 秒）。
 */
tasks.register<JavaExec>("dualPathAudit") {
    group = "verification"
    description = "T2-64 波次 C：真语料双路（树 vs 扁平）语义对拍（0 差异闸）"
    mainClass.set("com.eratw.port.boot.DualPathCorpusMainKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

/**
 * OBS-PHASETITLE 相位阶梯探针（**正式任务**，非 TEMP）：把 `heapRootAttribution` 的逐根口径
 * 往前推到运行期相位，逐相位差分 —— 上一路把「装载 + 装配完成」钉在 live ≈ 69 MiB，并更正了
 * 「桌面 280 MiB 是装载常驻」那条写错的归因（280 MiB 属于标题页**之后**的相位）。本任务量的是
 * R7 要的「运行期相位谁在长」：
 *
 * - P0 装配完成（对表 69 MiB）/ P1 begin(TITLE) 到第一个等待点 / P2 走一步进游戏（开局链第一段）/
 *   P3 同一条链反复「等待 → 提交 → 推进」到第 N 页（每页净增 + 单调不回吐判据）/
 *   P4 回标题重画一页（`gotoTitle` 之后还不还堆）；
 * - 出口 = `build/phase-heap-ladder.md`：逐相位 live 与恒等式表 + 逐相位分面差分表 +
 *   P3 逐页序列 + 逐相位 jcmd 交叉 + 逐根明细；恒等式逐相位各自闭合
 *   （入账合计 + 未归类 = live 总额，容差 ±10 MiB；任一相位 OVERCOUNT ⇒ exit 1）；
 * - 分面口径与来源标注纪律同源（枚举-精确公式 / 枚举-遍历模型 / jcmd直方图 / GC差，四口径不混为
 *   「实测」）；账面算术本身钉在 `PhaseHeapLadderTest`（不依赖产物）。
 * - **产品代码零改动**；脚本全部在装置自建的一条 VM 线程上跑（ADR-003 不破），主线程只测量与按
 *   等待点提交输入。`:render` / `:tools:uipreview` 不在本任务 classpath 上（依赖方向反了），
 *   「渲染侧缓存」这一面按声明不入账的 0 出行 —— 与桌面 280 MiB 的差额只当差额，不冒充实测。
 *
 * 参数：
 * - `-Pprobe.xmx=<size>`：JVM 挡位。**缺省 3g**（遍历档）；设备挡位复刻 = `-Pprobe.xmx=512m` 配
 *   `ERATW_PROBE_WALK=0`（只出逐相位 live 与计数器）；
 * - 环境沿用既有口径：`ERATW_ERBC_DIR`（或 `ERATW_ERBC`）指产物目录、`ERATW_GAMEDIR` 指游戏根，
 *   缺任一 exit 2；
 * - 相位与驱动旋钮：`ERATW_PROBE_PAGES=<n>`（P3 页数，缺省 8）、`ERATW_PROBE_INPUTS=0,0`
 *   （开局脚本，口径同 `BootPlayChain`）、`ERATW_PROBE_AWAIT_MS` / `ERATW_PROBE_TURN_MS` /
 *   `ERATW_PROBE_WALL_MS`（等待点上限 / 单次翻页上限 / 脚本总墙钟预算）；
 * - 其余：`ERATW_PROBE_WALK=0`、`ERATW_PROBE_JCMD=0`、`ERATW_PROBE_FLAT=1`、
 *   `ERATW_PROBE_OUT=<路径>`、`ERATW_PERF_ROUNDS=<n>`。
 *
 * ```
 * ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> \
 * ./gradlew --configure-on-demand :core-vm:phaseHeapLadder -Pprobe.xmx=3g
 * ```
 */
tasks.register<JavaExec>("phaseHeapLadder") {
    group = "verification"
    description = "运行期相位逐根差分阶梯（装配完→标题页→走一步→反复翻页；写 build/phase-heap-ladder.md）"
    mainClass.set("com.eratw.port.perf.PhaseHeapLadderProbeKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = providers.gradleProperty("probe.xmx").getOrElse("3g")
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

