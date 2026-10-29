plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 平台 API 兼容闸（2026-10-22 死循环复盘方案 #1，AndroidApiCompatTest）：扫描 core-vm
    // 主源集字节码，方法/字段引用必须存在于 android.jar（compileSdk 编译面）——
    // 「桌面 JDK 有、ART 编译面没有」的调用（Java 9+ 的 Field.trySetAccessible，真机
    // 死循环根因）在桌面闸当场闸红，不再等真机炸三轮。只扫签名不跑方法体（SKIP_CODE）。
    testImplementation("org.ow2.asm:asm:9.8")
    // A6 e2e 装置专用：符号表扫描（装载序/dimSpecs）+ 桥接映射器 com.erbcc.bridge.buildFuncDims。
    // 方向安全：erbcc(main) → core-vm(main)；core-vm(test) → erbcc(main) 无环（测试源集独立）。
    testImplementation(project(":tools:erbcc"))
}

// ---------------------------------------------------------------------------
// CMP-69：真产物档（默认关闭）
//
// §5 闸从不导出 ERATW_ERBC / ERATW_ERBC_DIR / ERATW_SAVE_SAMPLES / ERATW_ANDROID_JAR，
// 于是依赖 332 MB 真产物的段流式/侧车/mmap 用例（11 条）与真存档回环（1 条）全部停在
// assumeTrue 上，闸内从没跑过这些语义。L01 实测：真跑要十几分钟、且要够大堆，所以不能
// 无条件接进 §5。这里给一个显式开启的档：
//
//   .\gradlew :tools:erbcc:compileCorpus         # 先出产物（约 1.5 分钟 / 6g）
//   .\gradlew :core-vm:gateRealArtifacts        # 真产物子集（约 3 分钟 / 3g 堆）
//   .\gradlew -PwithRealArtifacts=true :core-vm:test   # 整包 + 真产物用例
//
// 默认（不带 -PwithRealArtifacts）行为与改前逐字一致：不导出任何额外变量。
// 开启后若产物缺失，任务在 doFirst 直接失败，不再静默回到 skip。
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

/** 真存档样本目录；缺省优先用本机 worktree 的只读备份，不存在时按未设置处理。 */
val realSaveSamples: Provider<File> = providers.gradleProperty("saveSamples")
    .map { File(it) }
    .orElse(
        providers.provider {
            rootDir.parentFile.resolve(".workbuddy/sav-backup/sav-20261018-110513")
        },
    )

/**
 * 把真产物档的四个环境变量接到当前 Test 任务。
 *
 * `ERATW_ERBC` 与 `ERATW_ERBC_DIR` 同时给：前者被 LazyStatementDecode / ErbcStream 族直接读，
 * 后者被侧车/性能族读（两套查找顺序见各测试的 findErbcDir）。`ERATW_ANDROID_JAR`
 * 只从 `-PandroidJar` 显式提升（未给时沿用环境里的同名变量，再不行由测试自己找 SDK）。
 * 产物缺失在 doFirst 失败 —— 这是本档存在的意义，不能退回 assumeTrue。
 *
 * @param requireSaveSamples 专用闸传 true：真存档样本目录也必须是现成的，
 *   否则 12 例会静默缩成 11 例（`gateRealArtifacts` 不允许这种缩水）。
 */
fun Test.wireRealArtifacts(requireSaveSamples: Boolean = false) {
    val dir = realErbcDir.get()
    val erbc = dir.resolve("program.erbc")
    environment("ERATW_ERBC_DIR", dir.absolutePath)
    environment("ERATW_ERBC", erbc.absolutePath)
    inputs.property("realErbcDir", dir.absolutePath)

    val save = realSaveSamples.get()
    if (save.isDirectory) {
        environment("ERATW_SAVE_SAMPLES", save.absolutePath)
        inputs.property("realSaveSamples", save.absolutePath)
    } else if (!requireSaveSamples) {
        logger.lifecycle(
            "[withRealArtifacts] 未找到真存档样本目录 ${save.absolutePath}；" +
                "RealSaveSamplesProbeTest 将按 assumeTrue 跳过（可用 -PsaveSamples=<目录> 指定）",
        )
    }
    providers.gradleProperty("androidJar").orNull?.let {
        environment("ERATW_ANDROID_JAR", File(it).absolutePath)
    }

    doFirst {
        require(erbc.isFile) {
            "真产物档已开启但找不到 ${erbc.absolutePath}；先跑 :tools:erbcc:compileCorpus" +
                "（ERATW_CORPUS 必须指到 <游戏根>/ERB），或用 -PerbcDir=<erbc.v1 目录> 指定"
        }
        if (requireSaveSamples) {
            require(save.isDirectory) {
                "gateRealArtifacts 需要真存档样本目录，但 ${save.absolutePath} 不存在；" +
                    "用 -PsaveSamples=<目录> 指定（目录内至少一份 .sav）"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 全量语料（2.6M 行）的语句 AST + 编译产物需要更大堆；默认 512m 会 OOM 掉 worker，
    // 1536m 在 CorpusCompileSmokeTest（带 ERATW_CORPUS 全量回归）同样实测 OOM —— 与
    // corpusSmoke 的 3g 对齐。
    maxHeapSize = "3g"
    // 语料冒烟开关（ERATW_CORPUS 等）显式透传给 test JVM（Windows 下 worker 不总继承）
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
    // 跨环境取证旋钮：ERATW_TEST_TZ=America/New_York 一类 → test JVM 的 user.timezone。
    // 用于验证 L3 链常量对系统时区的免疫性（修复前用它抓时区泄漏簇，修复后用它验收）。
    System.getenv("ERATW_TEST_TZ")?.let { systemProperty("user.timezone", it) }
    // CMP-69：默认关闭；开启时只多导出真产物变量，其余口径不变。
    if (withRealArtifacts.get()) wireRealArtifacts()
}

/**
 * CMP-69 真产物子集闸：只跑 11 条 `ERATW_ERBC*` 门控用例（含 StmtOffsetSidecar 的侧车用例）
 * + 1 条 `ERATW_SAVE_SAMPLES` 用例。
 *
 * 产物由调用方先跑 `:tools:erbcc:compileCorpus` 产出（CMP-66 同族：产物必须跟当前编译器
 * 字节码同源；这里不挂跨项目 dependsOn —— 实测 `--configure-on-demand` 下会触发
 * `DefaultClassLoaderScope must be locked` 配置期竞态）。用 `*真实产物*` / `*侧车加速档*` /
 * `*外部真实存档*` 三个方法名过滤精确圈定这 12 条，不把同类的合成用例也算进「真产物档」耗时。
 * 堆沿用 `tasks.withType<Test>` 的 3g：本档 12 例实测 worker `-Xmx3g`、峰值工作集
 * ≈3,409 MiB，未 OOM（`ErbcStreamEquivalenceTest` 的「一次只驻留一个 Program」靠
 * 两条路径之间的 `System.gc()` 错开）。
 */
tasks.register<Test>("gateRealArtifacts") {
    group = "verification"
    description = "真产物子集闸：ERATW_ERBC* 门控的 11 条 + ERATW_SAVE_SAMPLES 的 1 条"
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
    }
    // 专用闸不许静默缩水：产物与真存档样本缺一即红。
    wireRealArtifacts(requireSaveSamples = true)
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

