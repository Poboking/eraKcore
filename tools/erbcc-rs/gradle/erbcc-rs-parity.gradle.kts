// tools/erbcc-rs/gradle/erbcc-rs-parity.gradle.kts —— RUST1-B `rust-gradle` 车道的 Gradle 接线。
//
// **本脚本由组长在 `tools/erbcc/build.gradle.kts` 末尾一行接线**（worker 不碰 Kotlin 侧文件）：
//
//     apply(from = rootDir.resolve("tools/erbcc-rs/gradle/erbcc-rs-parity.gradle.kts"))
//
// 注册四个任务（口径全部收口在 `devtools/erbcc_rs_parity.py`，本脚本只起进程 + 传参）：
// - `erbccClasspathFile`  —— `ERBCC_CLASSPATH` 的产出方：跑 `installDist` 后把
//   `build/install/erbcc/lib/*.jar` 清单写成 classpath 文件（kernel.rs 档 1 第一态，多行），
//   并打印 cmd/bash 两种接线行。
// - `compileCorpusRs`     —— 用**现成 Rust CLI**（`cargo build --release --bin erbcc` 的产物）
//   编语料出 `erbc.v1/`：`-Pcorpus=<ERB目录> [-Pout=<目录>] [-Plimit=<N>]`，
//   子进程吃 `ERBCC_CLASSPATH`（档 1 文件态）。缺 exe 时报红指路 `cargo build`，不静默跳过。
// - `rsParity`            —— 双跑对拍闸（Kotlin 腿 vs Rust 腿四件套 sha256），委托
//   `python devtools/erbcc_rs_parity.py parity`；`-Pcorpus` 缺省时用小档
//   `tests/data/rust-manifest/corpus`，可带 `-Pout` / `-Plimit` / `-Pexpect=<登记文件>`。
// - `regenRsBaselines`    —— 每台机基准重生成，委托同脚本 `regen`；`-Plane=<rust-manifest|
//   rust-kernel|all>`（缺省 all）。
//
// 脚本对宿主项目零假设：`installDist` 存在才挂依赖（application 插件在 `:tools:erbcc` 里有；
// 别处 apply 时 `erbccClasspathFile` 执行期直接检查 install 目录并带指路文案报红）。
// 本机坑照 AGENTS.md §5：路径含非 ASCII ⇒ 调用方一律 `--configure-on-demand`；daemon 会冻
// 环境变量，改过 `JAVA_HOME`/`ERBCC_CLASSPATH` 后不生效先 `./gradlew --stop`。

import java.io.File

// 仓库根从**本脚本自身位置**上溯四级（…/gradle/x.kts → gradle → erbcc-rs → tools → eratw-android），
// 这样无论组长把 apply(from=…) 落在哪个项目里，路径都不会漂；拿不到脚本文件时退回 rootDir。
val parityScriptFile = buildscript.sourceFile?.canonicalFile
val parityRootDir = if (parityScriptFile != null) {
    parityScriptFile.parentFile.parentFile.parentFile.parentFile
} else {
    rootDir
}
val parityRustDir = File(parityRootDir, "tools" + File.separator + "erbcc-rs")
val parityDriver = File(parityRootDir, "devtools" + File.separator + "erbcc_rs_parity.py")
val parityInstallLib = File(parityRootDir,
    "tools" + File.separator + "erbcc" + File.separator + "build" +
        File.separator + "install" + File.separator + "erbcc" + File.separator + "lib")
val parityIsWindows = System.getProperty("os.name").lowercase().contains("windows")
val parityRustExe = File(parityRustDir,
    "target" + File.separator + "release" + File.separator + "erbcc" + if (parityIsWindows) ".exe" else "")
val parityClasspathFile = File(
    layout.buildDirectory.get().asFile, "erbcc-rs" + File.separator + "erbcc-classpath.txt")

fun parityPython(): String =
    (providers.gradleProperty("parityPython").orNull
        ?: providers.environmentVariable("ERATW_PYTHON").orNull
        ?: if (parityIsWindows) "python" else "python3").let { it }

// ---------------------------------------------------------------- ERBCC_CLASSPATH 产出方

val erbccClasspathFile = tasks.register("erbccClasspathFile") {
    group = "build"
    description = "产出 ERBCC_CLASSPATH（installDist 的运行时 jar 清单 → classpath 文件，档 1 第一态）"
    if (tasks.names.contains("installDist")) {
        dependsOn("installDist")
    }
    val outFile = parityClasspathFile
    outputs.file(outFile)
    inputs.property("installLibDir", parityInstallLib.absolutePath)
    doLast {
        val jars = parityInstallLib.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (jars.isEmpty()) {
            throw GradleException(
                "classpath 产出失败：${parityInstallLib} 里没有 jar。" +
                    "先跑 ./gradlew --configure-on-demand :tools:erbcc:installDist" +
                    "（或去掉对本任务的 --no-build 语义等价物）。不猜路径。"
            )
        }
        outFile.parentFile.mkdirs()
        outFile.writeText(jars.joinToString("\n") { it.absolutePath } + "\n")
        logger.lifecycle("ERBCC_CLASSPATH 文件（多行，档 1 第一态）：${outFile.absolutePath}（${jars.size} 枚 jar）")
        logger.lifecycle("  cmd : set \"ERBCC_CLASSPATH=${outFile.absolutePath}\"")
        logger.lifecycle("  bash: export ERBCC_CLASSPATH='${outFile.absolutePath}'")
    }
}

// ---------------------------------------------------------------- Rust CLI 编语料

tasks.register<Exec>("compileCorpusRs") {
    group = "build"
    description = "Rust CLI erbcc 编语料出 erbc.v1/（-Pcorpus=<ERB目录> [-Pout=<目录>] [-Plimit=<N>]）"
    dependsOn(erbccClasspathFile)
    workingDir = parityRootDir
    val corpusP = providers.gradleProperty("corpus").orElse(providers.environmentVariable("ERATW_CORPUS"))
    val outDefault = File(layout.buildDirectory.get().asFile, "erbc-rs-out").absolutePath
    val outP = providers.gradleProperty("out").orElse(outDefault)
    val limitP = providers.gradleProperty("limit")
    inputs.property("corpusPath", corpusP)
    inputs.property("outPath", outP)
    inputs.property("limit", limitP.orElse(""))
    inputs.file(parityRustExe)
    outputs.dir(outP)
    environment("ERBCC_CLASSPATH", parityClasspathFile.absolutePath)
    doFirst {
        val corpus = corpusP.orNull
        if (corpus.isNullOrBlank()) {
            throw GradleException("缺 corpus：传 -Pcorpus=<ERB目录> 或设置 ERATW_CORPUS")
        }
        if (!parityRustExe.isFile) {
            throw GradleException(
                "Rust CLI 不存在：${parityRustExe}。先 cd tools/erbcc-rs && cargo build --release --bin erbcc"
            )
        }
        val argv = mutableListOf(
            parityRustExe.absolutePath, "compile",
            "--corpus", corpus, "--out", outP.get()
        )
        limitP.orNull?.takeIf { it.isNotBlank() }?.let { argv += listOf("--limit", it) }
        setCommandLine(argv)
    }
}

// ---------------------------------------------------------------- 双跑对拍闸

tasks.register<Exec>("rsParity") {
    group = "verification"
    description = "双跑对拍：同一语料 Kotlin 腿/Rust 腿各出 erbc.v1/，四件套 sha256 全等才算绿" +
        "（[-Pcorpus=<目录>] [-Pout=<目录>] [-Plimit=<N>] [-Pexpect=<登记sha文件>]）"
    dependsOn(erbccClasspathFile)
    workingDir = parityRootDir
    val corpusP = providers.gradleProperty("corpus")
    val outP = providers.gradleProperty("out")
    val limitP = providers.gradleProperty("limit")
    val expectP = providers.gradleProperty("expect")
    inputs.property("corpusPath", corpusP.orElse("<driver-default>"))
    inputs.property("limit", limitP.orElse(""))
    doFirst {
        if (!parityDriver.isFile) {
            throw GradleException("对拍驱动不存在：${parityDriver}")
        }
        val argv = mutableListOf(parityPython(), parityDriver.absolutePath, "parity")
        corpusP.orNull?.takeIf { it.isNotBlank() }?.let { argv += listOf("--corpus", it) }
        outP.orNull?.takeIf { it.isNotBlank() }?.let { argv += listOf("--out", it) }
        limitP.orNull?.takeIf { it.isNotBlank() }?.let { argv += listOf("--limit", it) }
        expectP.orNull?.takeIf { it.isNotBlank() }?.let { argv += listOf("--expect", it) }
        argv += listOf("--classpath-file", parityClasspathFile.absolutePath)
        setCommandLine(argv)
    }
}

// ---------------------------------------------------------------- 基准重生成（每台机）

tasks.register<Exec>("regenRsBaselines") {
    group = "verification"
    description = "在当前机器重生成 tests/data/rust-manifest 与 rust-kernel 基准，打印新旧 sha 对照" +
        "（[-Plane=<rust-manifest|rust-kernel|all>]，缺省 all）"
    dependsOn(erbccClasspathFile)
    workingDir = parityRootDir
    val laneP = providers.gradleProperty("lane").orElse("all")
    inputs.property("lane", laneP)
    doFirst {
        if (!parityDriver.isFile) {
            throw GradleException("对拍驱动不存在：${parityDriver}")
        }
        setCommandLine(
            listOf(
                parityPython(), parityDriver.absolutePath, "regen",
                "--lane", laneP.get(), "--classpath", parityClasspathFile.absolutePath
            )
        )
    }
}
