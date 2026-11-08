//! CLI 契约闸：逐项钉 `Main.kt:109-126` 的退出码与分发，以及 `Main.kt:49-107` 的 USAGE 文案。
//!
//! 期望值一律来自**真 Kotlin 工具链产物**（`tests/data/rust-scan/kotlin-*.txt`），不手抄。
//! 已知且上报的 JVM 环境差：
//! - USAGE 文案的字节编码：本机 JVM 默认 stdout 字符集是 GBK/cp936，Rust 恒写 UTF-8，
//!   故基准一律带 `-Dsun.stdout.encoding=UTF-8` 重出（本波 ① 重出时实测：不加这个开关，
//!   `-` 之外的中文会按 cp936 落盘，与 Rust 腿的 UTF-8 不可比；加了就是纯 UTF-8，
//!   不再需要「GBK→UTF-8 解码」那一步 —— 早期基准就是这么来的，内容与现在这份一致）；
//! - 行尾：Kotlin `println` 用 `System.lineSeparator()`（本机 = `\r\n`），Rust `println` 恒 `\n`，
//!   两者文本主体相同、仅终端换行符不同。因此对拍比较的是**去掉 JVM 那一层 `\r\n` 后的主体**。
//!
//! ## `kotlin-usage.txt` 的生成命令（①，rust-cli-polish；**禁止手抄/手写**）
//!
//! USAGE 文案变了就要按这两条重出基准（在**仓库根**执行；`ERBCC_CLASSPATH` 用**分号直串**，
//! 指到 `erbcc-rs-classpath.txt` 那个文件路径会让腿 A 报「找不到主类」而整片假红，见 AGENTS.md §5
//! 与派单简报的坑位）：
//!
//! ```text
//! ./gradlew --configure-on-demand :tools:erbcc:installDist
//! java -Dsun.stdout.encoding=UTF-8 -cp "$ERBCC_CLASSPATH" com.eratw.port.erbcc.MainKt --help \
//!   > tools/erbcc-rs/tests/data/rust-scan/kotlin-usage.txt
//! ```
//!
//! 落盘的是那条命令 stdout 的**原始字节**（实测：body 全 LF、只有结尾一个 `\r\n` ——
//! Kotlin 编译器把 raw string 里的 CRLF 归一成 LF，行终止符只出现在 `println` 追加的那一位），
//! 所以 [strip_trailing_crlf] 之后的主体与 `cli::USAGE` 逐字节可比。
//! 本波 ①（USAGE 写明「`--allow` 不是合法 UTF-8 ⇒ 退 2」）读数为 4511 字节 / 59 行（改前 4251/59）。
//!
//! ## 本文件还钉 `--allow` 的两格退出码口径（②，rust-cli-polish）
//!
//! 见 [load_allowlist_缺档不是错误_退出码由审计结果定] 与
//! [load_allowlist_非utf8走usage档且正文与kotlin单源同文]：Kotlin 对「缺档」与「坏字节」
//! 给的是**两个不同档**（前者不占退出码、后者 `err()` 恒退 2），Rust 侧必须同档同文。

use std::path::{Path, PathBuf};

use erbcc_rs::audit;
use erbcc_rs::cli;
use erbcc_rs::types::{absolutize, erbcc_version, find_repo_root, kotlin_path_text, ErbccError};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-scan").join(rel)
}

fn golden_corpus() -> PathBuf {
    manifest_dir()
        .join("../../fixtures/golden")
        .canonicalize()
        .unwrap_or_else(|_| manifest_dir().join("../../fixtures/golden"))
}

/// 去掉末尾一个 CRLF（Kotlin `println` 在本 Windows JVM 上的行终止符）。
fn strip_trailing_crlf(bytes: &[u8]) -> &[u8] {
    if bytes.ends_with(b"\r\n") {
        &bytes[..bytes.len() - 2]
    } else {
        bytes
    }
}

fn run(args: &[&str]) -> i32 {
    let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    cli::run(&owned)
}

#[test]
fn empty_args_prints_usage_and_exits_0() {
    // Main.kt:111
    assert_eq!(run(&[]), 0);
}

#[test]
fn help_exits_0() {
    // Main.kt:112
    assert_eq!(run(&["--help"]), 0);
}

#[test]
fn version_exits_0() {
    // Main.kt:113
    assert_eq!(run(&["--version"]), 0);
}

#[test]
fn version_string_matches_core_vm_source_not_hardcoded() {
    // 版本唯一来源 = core-vm 的 Version.kt（types::erbcc_version），baseline 由 Kotlin 产出。
    let repo = find_repo_root(&manifest_dir()).expect("定位仓库根");
    let v = erbcc_version(&repo).expect("从 Version.kt 读版本");
    let baseline = std::fs::read(data("kotlin-version.txt")).expect("读 Kotlin --version 基线");
    let content = String::from_utf8_lossy(strip_trailing_crlf(&baseline)).into_owned();
    assert_eq!(content, format!("erbcc {v}"), "版本串必须与 Kotlin 同、且来自 Version.kt");
    assert!(!v.is_empty());
}

#[test]
fn usage_text_is_byte_identical_to_kotlin_body() {
    // USAGE 主体（去掉 JVM 的 \r\n 行终止符后）逐字节等于 Rust const。
    let baseline = std::fs::read(data("kotlin-usage.txt")).expect("读 Kotlin --help 基线");
    let body = strip_trailing_crlf(&baseline);
    assert_eq!(
        body,
        cli::USAGE.as_bytes(),
        "USAGE 文案与 Main.kt:49-107 不一致（行尾 CRLF/LF 差异已在此步剔除，编码差异见模块头注释）"
    );
}

/// ①（rust-cli-polish）：USAGE 必须**自己就说明**「`--allow` 白名单不是合法 UTF-8 ⇒ 退 2」。
///
/// 上面那条逐字节格子保证「两侧文案同源」，本条保证「同源的那份里确有这句话」——
/// 谁把 USAGE 改回不提这一档（并跟着重出基准），字节格子会绿、这条会红。
/// 判据来源：`Main.kt:650-663` 的 `readAllowListText`（`CodingErrorAction.REPORT` ⇒ 抛）
/// → `Main.kt:487-491` 的 `cmdAudit` 捕获 → `Main.kt:163-166` 的 `err()` 恒退 2；
/// 缺档不占退出码那半句对位 `Main.kt:651` + `Main.kt:679-681`。
#[test]
fn usage_states_allow_non_utf8_rejected_with_exit_2() {
    let allow_line = cli::USAGE
        .lines()
        .find(|l| l.starts_with("  --allow"))
        .expect("USAGE 里要有 --allow 那行");
    assert!(
        allow_line.contains("不是合法 UTF-8") && allow_line.contains("退出码 2 拒绝"),
        "--allow 行要写明坏字节退 2，实为：{allow_line}"
    );
    assert!(
        allow_line.contains("文件不存在不是错误"),
        "--allow 行要同时写明「缺档不是错误」（否则使用者会把提示行当成失败），实为：{allow_line}"
    );
    let exit_line = cli::USAGE
        .lines()
        .filter(|l| l.starts_with("  退出码"))
        .find(|l| l.contains("--allow"))
        .expect("audit 的「退出码」行要点名 --allow 的 2 档");
    assert!(
        exit_line.contains("2 = 参数错误"),
        "退出码行要给 2 档定义，实为：{exit_line}"
    );
}

/// ②（rust-cli-polish）：Kotlin 对「`--allow` 指向的档**不存在/不是普通文件**」的结论是
/// **不当错误**——`Main.kt:651` 返回 null → `Main.kt:679-681` 打一行提示后 `AllowList.EMPTY`，
/// 进程退出码完全由审计结果自己定（实测两腿同档同 rc=1，且那 1 来自 `--strict` 下 8 处硬差异：
/// `tools/erbcc-rs/build/cli-polish/a3k.err` / `a3r.err`）。
/// 所以 Rust 侧这格**不许**是 `Io`（退 1 的「错误」档）也不许是 `Usage`（退 2）：
/// 只能 `Ok(空)`，退出码归编排层判。简报里「缺文件走 ErbccError::Io ⇒ 退 1」这句在本仓
/// 现存代码里**读不到**（见交付报告：该档一直是 `Ok(Vec::new())`），真实的双档混用是下一条。
#[test]
fn load_allowlist_缺档不是错误_退出码由审计结果定() {
    let missing = data("nope-definitely-not-there.tsv");
    assert!(!missing.exists(), "这格要的是一个确实不存在的路径：{missing:?}");
    let got = audit::load_allowlist(&missing).expect("缺档必须 Ok（Kotlin 只打提示不报错）");
    assert!(got.is_empty(), "缺档 ⇒ 空白名单，实为 {got:?}");

    // 「存在但不是普通文件」的目录档同口径（Kotlin 的判据就是 isRegularFile，Main.kt:651）。
    let dir = manifest_dir().join("tests/data/rust-scan");
    assert!(dir.is_dir());
    audit::load_allowlist(&dir).expect("目录档也必须 Ok（不是 Io）");
}

/// ②（rust-cli-polish）的**真分歧面**：同一个「非合法 UTF-8」条件，Rust 原先在两处给两个档——
/// 编排层的 `cmd_audit::preflight_allowlist` 走 `Usage`（⇒ 退 2，与 Kotlin 同），
/// 而 [audit::load_allowlist] 里的第二道严格解码走 `Io`（⇒ 退 1）。
/// Kotlin 只有**一个**档：`Main.kt:487-491` 捕获 `IllegalStateException` 后转 `err()` ⇒ **恒 2**
/// （`Main.kt:163-166`）。修向＝Rust 对齐 Kotlin（不是反向），正文也收进同一枚单源
/// `cmd_audit::allow_charset_red` ⇒ 两道闸同档同文。
#[test]
fn load_allowlist_非utf8走usage档且正文与kotlin单源同文() {
    let bad = manifest_dir()
        .join("tests/data/rust-cli-surface/allow-nonutf8.tsv");
    assert!(bad.is_file(), "坏字节白名单夹具应存在：{bad:?}");
    let err = audit::load_allowlist(&bad).expect_err("非 UTF-8 白名单必须报红，不许糊成空白名单");
    let msg = match &err {
        ErbccError::Usage(m) => m.clone(),
        other => panic!("必须走 Usage（退出码 2，Kotlin 的 err() 那一把尺），实为 {other:?}"),
    };
    // 正文单源：与 cmd_audit 的第一道闸逐字相同（两枚闸同文 ⇒ 使用者面没有第二种说法）。
    assert_eq!(
        msg,
        erbcc_rs::cmd_audit::allow_charset_red(&kotlin_path_text(&bad.to_string_lossy())),
        "第二道闸的报红正文必须复用同一枚单源"
    );
    assert!(
        msg.starts_with("--allow 白名单不是合法 UTF-8 文本，已拒绝："),
        "正文前缀必须照 Kotlin `allowListCharsetRed`（Main.kt:670-672），实为：{msg}"
    );
}

/// ②的 CLI 面：坏字节白名单在**起 kernel 之前**就退 2（`cli::finish` 把 `Usage` 映射成 2）。
/// 这条只点 in-process 的 [cli::run]：`preflight_allowlist` 在 kernel 之前拦，所以不会起 JVM。
#[test]
fn audit_non_utf8_allow_exits_2_from_cli_dispatch() {
    let erb = manifest_dir()
        .join("tests/data/rust-audit/game/ERB")
        .to_string_lossy()
        .into_owned();
    let allow = manifest_dir()
        .join("tests/data/rust-cli-surface/allow-nonutf8.tsv")
        .to_string_lossy()
        .into_owned();
    let out = manifest_dir().join("target/cli-contract-nonutf8/u.tsv");
    let _ = std::fs::remove_file(&out);
    assert_eq!(
        run(&["audit", "--corpus", &erb, "--allow", &allow, "--out", &out.to_string_lossy()]),
        2,
        "坏字节白名单 ⇒ 参数错误档（Kotlin 同档，见 cli_surface_diff 的 F2 格全文对拍）"
    );
    assert!(!out.exists(), "退 2 的一格不许落 TSV（`Main.kt:487-491` 在任何产物之前）");
}

/// ③（rust-cli-polish）：缺档提示行的**路径形态单源**（本车道不能改 `cmd_audit.rs`，
/// 故把归一后的正文放在这里，等组长接线；分歧本身的实测读数与在册差分见
/// `tests/cli_surface_diff.rs` 的 `c4_audit_allow_相对路径缺档_分歧钉在册`）。
///
/// 期望值取**真跑 Kotlin 腿**的原文（`tools/erbcc-rs/build/cli-polish/a3k.err` 第 6 行）：
/// Kotlin 打在 `Path(原文)` 上 ⇒ 相对原样 + 分隔符归一（`WindowsPath.toString()`），**不绝对化**。
#[test]
fn allow_missing_warning_text_是kotlin的相对形态() {
    let raw = "tools/erbcc-rs/tests/data/rust-cli-surface/nope-missing.tsv";
    let want_path = if cfg!(windows) {
        "tools\\erbcc-rs\\tests\\data\\rust-cli-surface\\nope-missing.tsv"
    } else {
        raw
    };
    let want = format!("白名单文件不存在（按空白名单处理）: {want_path}");
    assert_eq!(audit::allow_missing_warning_text(raw), want);
    assert!(audit::is_allow_missing_warning_line(&want));
    assert!(audit::is_allow_missing_warning_line(
        "白名单文件不存在（按空白名单处理）: C:\\absolute\\form.tsv"
    ));
    assert!(
        !audit::is_allow_missing_warning_line("白名单行格式不对（跳过）: 1 列"),
        "滤 kernel 那行时不许顺手把别的白名单行也滤掉（`Main.kt:692` 的坏行告警要照常透出）"
    );
    // 与「绝不绝对化」这条口径绑死：kotlin_path_text 只折分隔符（absolutize 会把这格做坏）。
    let abs = absolutize(Path::new(raw));
    assert_ne!(
        audit::allow_missing_warning_text(&abs.to_string_lossy()),
        want,
        "把绝对路径喂进本单源就不是 Kotlin 那一行了 —— 接线时传的必须是**原文**"
    );
}

#[test]
fn symbols_and_audit_reject_bad_corpus_with_exit_2() {
    // 五路子命令已全部接线（RUST1-B 收口）。这里钉的是**移植后的参数面**：
    // corpus 不是目录 ⇒ 走 Kotlin `err()` 的等价物退 2（`Main.kt:163-166`），不是「未移植」占位。
    assert_eq!(run(&["symbols", "--corpus", "x"]), 2);
    assert_eq!(run(&["audit", "--corpus", "x"]), 2);
}

#[test]
fn unrecognized_args_exit_2() {
    // Main.kt:119-123
    assert_eq!(run(&["frobnicate", "foo"]), 2);
    assert_eq!(run(&["--nonsense"]), 2);
}

#[test]
fn scan_missing_corpus_exits_2() {
    // Main.kt:148 err → 2
    assert_eq!(run(&["scan"]), 2);
}

#[test]
fn scan_corpus_not_dir_exits_2() {
    // Main.kt:149
    let p = data("kotlin-version.txt"); // 存在的文件，非目录
    assert_eq!(run(&["scan", "--corpus", &p.to_string_lossy()]), 2);
}

#[test]
fn scan_use_index_not_file_exits_2() {
    // Main.kt:150
    let mut out = manifest_dir().join("target/scan-contract-out");
    std::fs::create_dir_all(&mut out).ok();
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--use-index",
            &data("does-not-exist.dat").to_string_lossy(),
            "--out",
            &out.to_string_lossy(),
        ]),
        2
    );
}

#[test]
fn scan_unrecognized_flag_exits_2() {
    // Main.kt:144
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--bogus-flag"
        ]),
        2
    );
}

#[test]
fn scan_flag_without_value_exits_2() {
    // Main.kt:140 `--corpus 需要参数` 等
    assert_eq!(run(&["scan", "--corpus"]), 2);
    assert_eq!(run(&["scan", "--rename"]), 2);
    assert_eq!(run(&["scan", "--out"]), 2);
}

#[test]
fn scan_valid_exits_0() {
    let out = manifest_dir().join("target/scan-contract-valid");
    let _ = std::fs::remove_dir_all(&out);
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--out",
            &out.to_string_lossy()
        ]),
        0
    );
    assert!(out.join("report.json").is_file());
    assert!(out.join("files.tsv").is_file());
    assert!(out.join("summary.txt").is_file());
}

/// 契约铁律 0.6 的现行形态：子命令**即使已移植**，也不许对坏输入退 0 冒充成功。
/// （RUST1 第一波时这两条还是「未移植退 2」的占位护栏；audit/symbols 接线后判据不变、理由变了。）
#[test]
fn not_ported_subcommands_never_return_zero() {
    for sub in ["symbols", "audit"] {
        assert_ne!(
            run(&[sub, "--corpus", "whatever"]),
            0,
            "{sub} 对坏 corpus 必须非 0（禁止假成功）"
        );
    }
}

#[allow(dead_code)]
fn _unused(_: &Path) {}
