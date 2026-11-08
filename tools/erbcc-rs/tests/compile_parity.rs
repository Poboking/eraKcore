//! **本路的出口定义**：同一份语料，Rust 编排产出的 `erbc.v1/` 四件套必须与纯 Kotlin 路径
//! **逐字节相同**（CONTRACT-RUST1 §4-C 闸 1）。
//!
//! 两腿都是**真跑**（不手抄、不从文档抄期望值）：
//! - **腿 A（纯 Kotlin）**：直接起 `java -cp <installDist 的 lib> com.eratw.port.erbcc.MainKt
//!   compile --corpus <语料> --out <A>` —— 与 Gradle 任务 `:tools:erbcc:compileCorpus` 同一个
//!   mainClass（`tools/erbcc/build.gradle.kts:38`）、同一组 args（`:84`）、同一堆档（`:40` 的 6g）。
//!   本测试**不复用** `kernel.rs` 的解析代码，免得两条腿共用一个 bug 而闸失明。
//! - **腿 B（Rust 编排）**：`cmd_compile::execute(["--corpus", 语料, "--out", B])`
//!   = 真 kernel 桥（`kernel::run_with_env`）+ rust-manifest 路的真清单函数（`PRODUCTION`）。
//!
//! 判据：`program.erbc` / `program.meta` / `program.stmt.idx` / `manifest.txt`
//! **四件套 sha256 与字节数全等**。再加一条「腿 A 的三件套 == `tests/data/rust-kernel/
//! parity-baseline-sha256.txt`（由 `:tools:erbcc:compileCorpus` 真跑登记）」——
//! 这条防的是「两腿一起漂」（比如有人同时改了 kernel 的 JVM 选项与测试的拼法）。
//!
//! ## 语料为什么不是原样的 `fixtures/golden`
//!
//! `CorpusCompile.kt:198` 的 `isCollectionCopy` 把 `GOLDEN_` 前缀的文件排除在编译输入之外
//! （`:205-218` 的 `listCorpusFiles`），直接拿 `fixtures/golden` 当语料会得到**空文件集**：
//! 实测 2026-11-01 `编译：文件 0`、空档还退 1（`Program 构建失败: 入口函数下标越界`），
//! 四件套闸会退化成「两份空产物相等」的空转。故本闸把 13 个 fixture **原样复制**成
//! `PARITY_*.ERB`（内容一个字节不改，只换文件名）放进 `<repo>/tools/erbcc-rs/build/
//! parity-game-<tag>/ERB`，旁边补一个 `CSV/` 空目录（`:71-73` 把 `<gameRoot>/CSV` 声明成输入，
//! 缺目录 Gradle 直接拒任务）。产物字节与目录名无关（腿 A/腿 B 用同一份语料、不同 `--out`）。
//!
//! ## 运行前提
//!
//! `JAVA_HOME`（或 PATH 上的 `java`）+ `:tools:erbcc:installDist` 的产物，或
//! `ERBCC_CLASSPATH`。全量语料档（`$ERATW_CORPUS`）**不在本闸**：一趟几分钟、峰值堆 ≈3.4 GiB，
//! 读数写在交付报告里（AGENTS.md §5「定向单测优先」）。

use erbcc_rs::cmd_compile;
use erbcc_rs::types::{absolutize, sha256_hex, Outcome};
use std::path::{Path, PathBuf};

const GOLDEN_NAMES: [&str; 13] = [
    "GOLDEN_00_ROOT.ERB",
    "GOLDEN_01_BASIC.ERB",
    "GOLDEN_02_PRINT.ERB",
    "GOLDEN_03_FLOW.ERB",
    "GOLDEN_04_CHARA.ERB",
    "GOLDEN_05_DATA.ERB",
    "GOLDEN_06_INPUT_AUTO.ERB",
    "GOLDEN_07_INPUT_MANUAL.ERB",
    "GOLDEN_08_IO.ERB",
    "GOLDEN_09_MISC.ERB",
    "GOLDEN_10_XMLMAP.ERB",
    "GOLDEN_11_GVIEW.ERB",
    "GOLDEN_12_SYMBOLS.ERB",
];

const FOUR: [&str; 4] = [
    "program.erbc",
    "program.meta",
    "program.stmt.idx",
    "manifest.txt",
];

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&erbcc_rs::types::current_dir())
        .expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

/// 腿 A/B 共用的语料目录（每个 tag 一份，避免并行用例互相覆盖副本）。
fn parity_corpus(tag: &str) -> PathBuf {
    let root = repo_root();
    let game = root.join(format!("tools/erbcc-rs/build/parity-game-{tag}"));
    let erb = game.join("ERB");
    std::fs::create_dir_all(&erb).expect("建 parity 语料目录");
    std::fs::create_dir_all(game.join("CSV")).expect("建 parity CSV 目录");
    for name in GOLDEN_NAMES {
        let src = root.join("fixtures/golden").join(name);
        let dst = erb.join(name.replace("GOLDEN_", "PARITY_"));
        std::fs::copy(&src, &dst).expect("复制金标 fixture 进 parity 语料");
    }
    // 副本必须与 fixture 同字节，否则两腿对拍的是「两份不同的语料」
    for name in GOLDEN_NAMES {
        let src = std::fs::read(repo_root().join("fixtures/golden").join(name)).unwrap();
        let dst = std::fs::read(erb.join(name.replace("GOLDEN_", "PARITY_"))).unwrap();
        assert_eq!(sha256_hex(&src), sha256_hex(&dst), "副本 {name} 与 fixture 不同字节");
    }
    erb
}

fn fresh_out(tag: &str) -> PathBuf {
    let dir = repo_root().join(format!(
        "tools/erbcc-rs/build/parity-out-{tag}-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("建产物目录");
    dir
}

/// 本测试自己解析 classpath / java（**不走 kernel.rs**，两腿必须独立）。
fn kotlin_tool_classpath() -> String {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
            if Path::new(t).is_file() {
                let text = std::fs::read_to_string(t).expect("读 classpath 文件");
                let sep = if cfg!(windows) { ";" } else { ":" };
                let sep_char = sep.chars().next().unwrap();
                return text
                    .split(|c| c == '\n' || c == '\r' || c == sep_char)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(sep);
            }
            return t.to_string();
        }
    }
    let lib = repo_root().join("tools/erbcc/build/install/erbcc/lib");
    assert!(
        lib.is_dir(),
        "腿 A 需要纯 Kotlin 工具链：跑 `./gradlew --configure-on-demand :tools:erbcc:installDist`，\
         或设 ERBCC_CLASSPATH（缺 {lib:?}）"
    );
    format!("{}/*", lib.to_string_lossy())
}

fn java_exe() -> PathBuf {
    match std::env::var("JAVA_HOME") {
        Ok(home) if !home.trim().is_empty() => {
            let exe = Path::new(home.trim()).join(format!("bin/java{}", std::env::consts::EXE_SUFFIX));
            assert!(exe.is_file(), "JAVA_HOME 里没有可执行文件：{exe:?}");
            exe
        }
        _ => PathBuf::from("java"),
    }
}

/// 腿 A：纯 Kotlin 路径（与 `:tools:erbcc:compileCorpus` 同 mainClass / 同 args / 同堆档）。
fn run_kotlin_leg(corpus: &Path, out: &Path, tag: &str) -> (i32, String, String) {
    let _ = tag;
    let out_abs = absolutize(out);
    let corpus_abs = absolutize(corpus);
    let mut cmd = std::process::Command::new(java_exe());
    cmd.args(["-Xmx6g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(kotlin_tool_classpath())
        .arg("com.eratw.port.erbcc.MainKt")
        .arg("compile")
        .arg("--corpus")
        .arg(&corpus_abs)
        .arg("--out")
        .arg(&out_abs)
        .current_dir(repo_root());
    let ok = cmd.output().expect("起纯 Kotlin 工具链失败（腿 A）");
    (
        ok.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&ok.stdout).into_owned(),
        String::from_utf8_lossy(&ok.stderr).into_owned(),
    )
}

/// 腿 B：Rust 编排（真 kernel 桥 + 真清单函数）。
fn run_rust_leg(corpus: &Path, out: &Path) -> Outcome<i32> {
    cmd_compile::execute(&[
        "--corpus".to_string(),
        corpus.to_string_lossy().into_owned(),
        "--out".to_string(),
        out.to_string_lossy().into_owned(),
    ])
}

/// 四件套的 `文件名 字节数 sha256` 三列。
fn four_piece_digest(dir: &Path) -> Vec<String> {
    let artifact_dir = dir.join(erbcc_rs::types::ERBC_DIR_NAME);
    FOUR.iter()
        .map(|name| {
            let path = artifact_dir.join(name);
            let bytes = std::fs::read(&path)
                .unwrap_or_else(|e| panic!("缺产物 {path:?}（腿 A/B 都要四件套齐）：{e}"));
            format!("{name} {} {}", bytes.len(), sha256_hex(&bytes))
        })
        .collect()
}

fn parse_recorded_baseline() -> Vec<String> {
    let file = repo_root().join("tools/erbcc-rs/tests/data/rust-kernel/parity-baseline-sha256.txt");
    let text = std::fs::read_to_string(&file)
        .unwrap_or_else(|e| panic!("读不到登记基准 {file:?}：{e}"));
    text.lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| {
            let mut parts = l.split_whitespace();
            let name = parts.next().expect("列 1 = 文件名").rsplit('/').next().unwrap().to_string();
            let bytes = parts.next().expect("列 2 = 字节数");
            let sha = parts.next().expect("列 3 = sha256");
            format!("{name} {bytes} {sha}")
        })
        .collect()
}

#[test]
fn 同一语料_rust编排与纯kotlin路径_四件套sha256全等() {
    let corpus = parity_corpus("gate");
    let out_kotlin = fresh_out("gate-kotlin");
    let out_rust = fresh_out("gate-rust");

    let (rc_a, stdout_a, stderr_a) = run_kotlin_leg(&corpus, &out_kotlin, "a");
    assert_eq!(rc_a, 0, "腿 A（纯 Kotlin）应退 0。stderr:\n{stderr_a}\nstdout:\n{stdout_a}");

    let rc_b = run_rust_leg(&corpus, &out_rust).expect("腿 B（Rust 编排）报红");
    assert_eq!(rc_b, 0, "腿 B 应退 0");

    let a = four_piece_digest(&out_kotlin);
    let b = four_piece_digest(&out_rust);
    // 差异原样贴两侧，方便组长直接看是哪一件、哪一列不对
    for (i, name) in FOUR.iter().enumerate() {
        assert_eq!(a[i], b[i], "第 {} 件（{name}）两侧不等 —— 期望(腿 A) vs 实际(腿 B)", i + 1);
    }

    // 附带读数（写进交付）：四件套各自的字节数 + sha256
    println!("语料 = {}", corpus.to_string_lossy());
    println!("腿 A out = {}", out_kotlin.to_string_lossy());
    println!("腿 B out = {}", out_rust.to_string_lossy());
    for line in &a {
        println!("OK {line}");
    }
}

#[test]
fn 纯kotlin腿的三件套等于_gradle_compilecorpus_登记的基准() {
    // 这条只验「腿 A 自己没漂」：三件套（不含 manifest.txt —— 它的 `corpus-root` 行是本机
    // 绝对路径，机器相关，故不登记）必须等于 `:tools:erbcc:compileCorpus` 真跑产出的读数。
    // 若编译器源码（core-vm）改了导致产物变了，请重跑登记命令刷新基准文件（见该文件头注）。
    let corpus = parity_corpus("recorded");
    let out_kotlin = fresh_out("recorded-kotlin");
    let (rc, stdout, stderr) = run_kotlin_leg(&corpus, &out_kotlin, "r");
    assert_eq!(rc, 0, "腿 A 应退 0。stderr:\n{stderr}\nstdout:\n{stdout}");

    let four = four_piece_digest(&out_kotlin);
    let recorded = parse_recorded_baseline();
    assert_eq!(recorded.len(), 3, "登记基准应有三件套");
    for line in &recorded {
        let name = line.split(' ').next().unwrap();
        let actual = four.iter().find(|l| l.starts_with(&format!("{name} "))).expect("三件套缺一件");
        assert_eq!(line, actual, "腿 A 与 Gradle 登记基准不等（{name}）");
    }
}

/// 全量语料档（`$ERATW_CORPUS`）的性能读数 —— CONTRACT-RUST1 §4-C「附带读数」的 Rust 腿。
///
/// `#[ignore]` 的理由：一趟几十秒到几分钟、峰值堆 ≈3.4 GiB、产物 ≈380 MiB（AGENTS.md §5
/// 「定向单测优先」），不该进每次 `cargo test` 的默认面。跑法（与 Kotlin 腿同一台机、同一份语料）：
///
/// ```text
/// export JAVA_HOME='<工具链 JDK>'; export PATH="$JAVA_HOME/bin:$PATH"
/// export ERATW_CORPUS='<游戏根>/ERB'
/// export ERATW_GAMEDIR='<游戏根>'
/// cargo test --release --test compile_parity -- --ignored --nocapture
/// ```
///
/// 读数口径：`WALL_MS` = `cmd_compile::execute` 整趟墙钟（含起 JVM、编译、Rust 侧内容寻址、
/// 重写清单）；`ADDRESSING_MS` = 同一份语料上**只跑 Rust 侧内容寻址**（语料摘要 + 产物摘要 +
/// 编译器指纹 + 清单文本）的耗时 —— 两数之差就是「纯 Kotlin 路径没有的那一段开销」。
/// 峰值 RSS 由外部采样器读（Rust 标准库拿不到子进程的 peak working set），见交付报告。
#[test]
#[ignore = "全量语料性能读数：需要 ERATW_CORPUS + JAVA_HOME + installDist 产物；几十秒~几分钟一趟"]
fn 全量语料_rust编排_墙钟与内容寻址开销() {
    let corpus = std::env::var("ERATW_CORPUS").expect("缺 ERATW_CORPUS：全量档读数不吃环境变量就没法跑");
    if !Path::new(&corpus).is_dir() {
        panic!("ERATW_CORPUS 指向的目录不存在：{corpus}");
    }
    let out = repo_root().join("tools/erbcc-rs/build/perf/rust-full");
    let _ = std::fs::remove_dir_all(&out);
    std::fs::create_dir_all(&out).expect("建全量产物目录");

    let started = std::time::Instant::now();
    let code = cmd_compile::execute(&[
        "--corpus".to_string(),
        corpus.clone(),
        "--out".to_string(),
        out.to_string_lossy().into_owned(),
    ])
    .expect("全量语料 Rust 编排报红");
    let wall_ms = started.elapsed().as_millis();
    assert_eq!(code, 0);

    // 只重跑 Rust 侧那一段（不起 JVM）：这就是编排相对纯 Kotlin 路径新增的开销
    let started = std::time::Instant::now();
    let sources = erbcc_rs::manifest::source_entries(Path::new(&corpus)).expect("语料摘要");
    let artifacts = cmd_compile::artifact_entries(
        &out.join(erbcc_rs::types::ERBC_DIR_NAME),
        erbcc_rs::digest::sha256_file,
    )
    .expect("产物摘要");
    let digest = erbcc_rs::digest::compiler_digest(&repo_root()).expect("编译器指纹");
    let text = erbcc_rs::manifest::manifest_text(
        2,
        "0",
        &digest.digest,
        digest.file_count,
        "full",
        None,
        &corpus,
        &sources,
        &artifacts,
        None,
    )
    .expect("清单文本");
    let addressing_ms = started.elapsed().as_millis();

    println!("WALL_MS={wall_ms} ADDRESSING_MS={addressing_ms} SOURCES={} ARTIFACTS={} DIGEST_FILES={}",
        sources.len(),
        artifacts.len(),
        digest.file_count);
    println!("清单文本 {} 字节", text.len());
    println!(
        "四件套 = {}",
        four_piece_digest(&out).join(" | ")
    );
}
