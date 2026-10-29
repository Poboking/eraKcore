plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.eratw.port.erbcc.MainKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    outputs.dir(outProvider.get()).withPropertyName("erbcOut")
    doFirst {
        val corpus = corpusProvider.get()
        require(corpus.isNotBlank()) { "缺 corpus：传 -Pcorpus=<ERB目录> 或设置 ERATW_CORPUS" }
        setArgs(listOf("compile", "--corpus", corpus, "--out", outProvider.get()))
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
    description = "装载期符号链接审计（-Pcorpus=<ERB目录> [-PgameRoot=<游戏根>] [-Pout=<TSV>] [-Pstrict]）"
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
}
