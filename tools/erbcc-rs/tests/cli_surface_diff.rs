// CLI 参数面差分闸：把 Kotlin CLI 与 Rust CLI 在「参数组合」这张网格上的行为
// （退出码 / stdout / stderr / 落不落盘）逐格对比。
//
// 两腿都真跑（不手抄、不把 Rust 的输出当基准）：
//   腿 A（Kotlin）：java -cp <installDist 的 lib> com.eratw.port.erbcc.MainKt <子命令与参数>
//     —— mainClass 与 `:tools:erbcc:compileCorpus` 同源，堆档 -Xmx6g 同尺。
//   腿 B（Rust）：cargo 构建出来的 `erbcc` 可执行（env CARGO_BIN_EXE_erbcc）。
// 期望值全部来自腿 A 的实跑读数，判定口径写死在每个用例里。
//
// 归一口径（既有，沿用）：
//   - JVM 侧强制 `-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`，Rust 侧恒 UTF-8，
//     两侧字节都是 UTF-8；
//   - JVM `println` 用 CRLF，Rust 用 LF，故比较前把 CRLF 折成 LF；
//   - 除此之外不做任何 normalize（不抹空格、路径、耗时数字）——含绝对路径 / 耗时的格子
//     只能「比 rc，不比全文」，逐格在用例里说明原因。
//
// 语料用相对路径时把两腿的 CWD 固定在仓库根，这样「corpus 不是目录」这类报红行与产出机
// 绝对路径无关、可全文比对。
//
// 差分三格里 F1（`corpus 不是目录` 的分隔符）与 F2（`--allow` 非法 UTF-8）已在 rust-cli-fixes 修掉、
// 两格的断言由「钉分歧」翻成「钉 parity」（F2 修的是 Kotlin 那侧，见 Main.kt 的 readAllowListText）；
// F3 由组长判「不为凑 parity 重塑行为」，本表不碰。
//
// rust-cli-polish（本波 CLI 尾巴四笔）在本表加了/加严了三格：
//   ② `c4_audit_allow_missing_or_malformed_rc_parity` —— 绝对路径档从「只比 rc」加严到
//     **stderr 逐字节全等** + 缺档提示行恰好一条；同时把「缺档不占退出码」的读数钉成 rc=1
//     （来自 --strict 的硬差异，不是缺档）。
//   ③ `c4_audit_allow_缺档相对路径_路径形态两腿全文parity` —— 相对路径档原先两腿不同形（K 相对 / R 绝对，
//     登记名 `allow-missing-path-form`），**组长接线后已清账**：`cmd_audit::orchestrate` 改用**原文**调
//     `audit::allow_missing_warning_text`（kernel 仍收绝对路径），再由 `stderr_head_lines` 携原文**就地换掉**那一份的路径段落，
//     本格从「钉分歧」翻成全文 parity（仍保留行位置与其余行逐字节全等两格）。
//   ④ `c2_scan_use_index_not_file_separator_full_parity` —— `use-index 不是文件:` 与 F1 同形缺陷，
//     已用单源 `types::kotlin_path_text` 收进 parity，并带「正斜杠变体不等于基准」的反证。
// ①（USAGE 写明 `--allow` 坏字节 ⇒ 退 2）钉在 `tests/cli_contract.rs`（USAGE 逐字节 + 措辞两格），
// 入库基准 `tests/data/rust-scan/kotlin-usage.txt` 由真 Kotlin 工具链 `--help` 重出。

use std::path::{Path, PathBuf};
use std::process::Command;

// ---------------------------------------------------------------------------
// 两腿驱动
// ---------------------------------------------------------------------------

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&erbcc_rs::types::current_dir())
        .expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
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

/// 纯 Kotlin 工具链 classpath：优先 `ERBCC_CLASSPATH`，否则 installDist 的 lib 通配。
fn kotlin_classpath() -> String {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
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

fn rust_exe() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_erbcc"))
}

/// 一次子进程运行结果：退出码 + UTF-8 解码并折行尾后的 stdout/stderr。
struct Run {
    rc: i32,
    stdout: String,
    stderr: String,
}

fn decode_nl(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).replace("\r\n", "\n")
}

fn run_case(args: &[&str]) -> (Run, Run) {
    let root = repo_root();
    let ka = Command::new(java_exe())
        .args(["-Xmx6g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(kotlin_classpath())
        .arg("com.eratw.port.erbcc.MainKt")
        .args(args)
        .current_dir(&root)
        .output()
        .expect("起纯 Kotlin 工具链失败（腿 A）");
    let ra = Command::new(rust_exe())
        .args(args)
        .current_dir(&root)
        .output()
        .expect("起 Rust erbcc 可执行失败（腿 B）");
    (
        Run { rc: ka.status.code().unwrap_or(-1), stdout: decode_nl(&ka.stdout), stderr: decode_nl(&ka.stderr) },
        Run { rc: ra.status.code().unwrap_or(-1), stdout: decode_nl(&ra.stdout), stderr: decode_nl(&ra.stderr) },
    )
}

fn small_corpus() -> String {
    repo_root().join("tools/erbcc-rs/tests/data/rust-manifest/corpus").to_string_lossy().into_owned()
}
fn audit_erb() -> String {
    repo_root().join("tools/erbcc-rs/tests/data/rust-audit/game/ERB").to_string_lossy().into_owned()
}
fn data_file(rel: &str) -> String {
    repo_root().join("tools/erbcc-rs/tests/data/rust-cli-surface").join(rel).to_string_lossy().into_owned()
}
/// build/cli-surface 临时目录（用例产物落这里，收尾删）。
fn scratch(tag: &str) -> PathBuf {
    let dir = repo_root().join(format!("tools/erbcc-rs/build/cli-surface/{}", tag));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("建临时目录");
    dir
}

// ---------------------------------------------------------------------------
// 分类：全等 / 只比 rc / 已知分歧（钉死读数）
// ---------------------------------------------------------------------------

/// 断言两腿退出码相同、stdout 相同、stderr 相同（UTF-8 + 折行尾后逐字节）。
fn assert_full_parity(tag: &str, args: &[&str]) -> (i32, i32) {
    let (k, r) = run_case(args);
    assert_eq!(k.rc, r.rc, "[{tag}] 退出码不等：Kotlin {} vs Rust {}\nK stderr:{:?}\nR stderr:{:?}", k.rc, r.rc, k.stderr, r.stderr);
    assert_eq!(k.stdout, r.stdout, "[{tag}] stdout 不等（期望=Kotlin 腿实际=Rust 腿）");
    assert_eq!(k.stderr, r.stderr, "[{tag}] stderr 不等（期望=Kotlin 腿实际=Rust 腿）");
    (k.rc, r.rc)
}

/// 断言两腿退出码相同；stdout/stderr 含绝对路径或耗时，不比全文（只比 rc 的理由见各处）。
fn assert_rc_parity(tag: &str, args: &[&str], why: &str) -> (i32, i32) {
    let (k, r) = run_case(args);
    assert_eq!(k.rc, r.rc, "[{tag}] 退出码不等（{why}）：Kotlin {} vs Rust {}\nK stderr:{:?}", k.rc, r.rc, k.stderr);
    // 顺带把两侧全文落到临时目录，供人工核对（不入库）。
    if let Some(dir) = scratch_opt(tag) {
        let _ = std::fs::write(dir.join("k.out"), &k.stdout);
        let _ = std::fs::write(dir.join("k.err"), &k.stderr);
        let _ = std::fs::write(dir.join("r.out"), &r.stdout);
        let _ = std::fs::write(dir.join("r.err"), &r.stderr);
    }
    (k.rc, r.rc)
}

fn scratch_opt(tag: &str) -> Option<PathBuf> {
    std::env::var("CLI_SURFACE_KEEP").ok().map(|_| scratch(tag))
}

// ===========================================================================
// 类别 1：零参 / 缺参（各子命令不带 --corpus）
// ===========================================================================

#[test]
fn c1_subcommand_without_args_missing_corpus() {
    // 五个子命令各自不带任何参数 ⇒ 都在起 kernel 前退 2「缺少 --corpus 参数」，文案逐字同。
    for sub in ["scan", "symbols", "compile", "check", "audit"] {
        let (k, _r) = assert_full_parity(&format!("c1/{sub}"), &[sub]);
        assert_eq!(k, 2, "{sub} 零参必须退 2");
    }
}

#[test]
fn c1_missing_corpus_with_other_flags() {
    // 只给 --out / --game-root 不给 --corpus，仍缺 corpus 退 2（判定顺序与 Kotlin 同）。
    assert_full_parity("c1/scan-no-corpus", &["scan", "--out", "tools/erbcc-rs/build/cli-surface/o1"]);
    assert_full_parity("c1/compile-no-corpus", &["compile", "--out", "tools/erbcc-rs/build/cli-surface/o2"]);
    assert_full_parity("c1/audit-no-corpus", &["audit", "--game-root", &audit_erb_parent()]);
}

fn audit_erb_parent() -> String {
    // game 根（含 CSV），只用来把 --game-root 撑成一个合法值，corpus 仍缺。
    repo_root().join("tools/erbcc-rs/tests/data/rust-audit/game").to_string_lossy().into_owned()
}

// ===========================================================================
// 类别 2：坏值
// ===========================================================================

#[test]
fn c2_limit_bad_values_exit_2() {
    // compile/audit 的 --limit 走 Kotlin `toIntOrNull()?.takeIf { it > 0 }`（Main.kt:330/456）：
    // 0、-1、非数字、超 Int 全退 2，文案 `--limit 需要正整数`。Rust 侧 i32 解析等价（超 Int 也拦）。
    let corpus = small_corpus();
    let erb = audit_erb();
    for bad in ["0", "-1", "abc", "99999999999"] {
        assert_full_parity(
            &format!("c2/compile-limit-{bad}"),
            &["compile", "--corpus", &corpus, "--limit", bad],
        );
        assert_full_parity(
            &format!("c2/audit-limit-{bad}"),
            &["audit", "--corpus", &erb, "--limit", bad],
        );
    }
}

#[test]
fn c2_corpus_is_file_separator_normalised_full_parity() {
    // F1（已修）：corpus 指到一个**文件**时，五臂的「corpus 不是目录: <路径>」两腿必须逐字节相等。
    // 修前：Kotlin 打 WindowsPath.toString()（反斜杠），Rust 的 scan/compile/check/symbols 原样回显
    // 入参的正斜杠、只有 audit 做了归一 ⇒ Rust 自己内部都不一致。
    // 修向：Rust 跟 Kotlin 的归一口径，`kotlin_path_text` 上收到 types.rs 当单源、五臂共用。
    // 期望值是**真跑 Kotlin 腿**的原文（改后首跑读数见 tools/erbcc-rs/build/cli-fixes/after/），
    // 写死成字面量而不是拿 Rust 输出当基准 ⇒ 任何一臂退回原样回正斜杠都会红。
    let rel = "tools/erbcc-rs/tests/data/rust-manifest/corpus/01_main.ERB";
    let want = concat!(
        "错误: corpus 不是目录: ",
        "tools\\erbcc-rs\\tests\\data\\rust-manifest\\corpus\\01_main.ERB\n",
    );
    for sub in ["scan", "compile", "check", "symbols"] {
        let mut args: Vec<&str> = vec![sub, "--corpus", rel];
        if sub == "check" {
            args.push("--out");
            args.push("tools/erbcc-rs/build/cli-surface/none");
        }
        let (k, r) = run_case(&args);
        assert_eq!(k.rc, 2, "{sub} corpus 是文件必须退 2");
        assert_eq!(r.rc, 2, "{sub} corpus 是文件 Rust 也必须退 2");
        assert_eq!(k.stderr, want, "[{sub}] 基准=真跑 Kotlin 腿的归一化报红原文");
        assert_eq!(r.stderr, want, "[{sub}] F1 parity：Rust 腿必须与 Kotlin 腿逐字节同文");
    }
    // audit 是修前就已归一的对照臂：现在与四臂同一把尺，同样逐字节相等。
    let (k, r) = run_case(&["audit", "--corpus", rel]);
    assert_eq!(k.rc, 2);
    assert_eq!(r.rc, 2);
    assert_eq!(k.stderr, want, "[audit] 对照臂的原文基准");
    assert_eq!(r.stderr, k.stderr, "[audit] 对照臂与四臂同尺");
}

#[test]
fn c2_game_root_missing_csv_exit_2() {
    // --game-root 指到没有 CSV/ 的目录 ⇒ Kotlin/Rust 都在起 kernel 前退 2「游戏根缺少 CSV」。
    let erb = audit_erb();
    let no_csv = "tools/erbcc-rs/tests/data/rust-manifest/corpus"; // 该目录下无 CSV
    let (k, r) = assert_full_parity(
        "c2/game-root-no-csv",
        &["audit", "--corpus", &erb, "--game-root", no_csv],
    );
    assert_eq!(k, 2);
    assert_eq!(r, 2);
}

#[test]
fn c2_corpus_missing_dir_exit_2() {
    // corpus 指到不存在的目录（相对正斜杠）：F1 修后两腿、两臂都逐字节同文。
    // 修前只有 audit 归一分隔符、compile 原样回显正斜杠 ⇒ 同一格差一个分隔符。
    // 期望串同样取自真跑 Kotlin 腿（build/cli-fixes/after/nope-k.err 与 nopec-k.err）。
    let want = "错误: corpus 不是目录: tools\\nope\\does-not-exist\n";
    for sub in ["audit", "compile"] {
        let (k, r) = run_case(&[sub, "--corpus", "tools/nope/does-not-exist"]);
        assert_eq!(k.rc, 2, "{sub} corpus 目录不存在必须退 2");
        assert_eq!(r.rc, 2, "{sub} Rust 侧也必须退 2");
        assert_eq!(k.stderr, want, "[{sub}] 基准=真跑 Kotlin 腿");
        assert_eq!(r.stderr, want, "[{sub}] F1 parity：Rust 与 Kotlin 逐字节同文");
    }
}

#[test]
fn c2_scan_use_index_not_file_separator_full_parity() {
    // ④（rust-cli-polish）：`--use-index` 指到不存在的档时，`use-index 不是文件: <路径>` 这条报红
    // 与 F1 同一形缺陷 —— 修前 Rust 用 `idx.display()` 原样回显入参的正斜杠，而 Kotlin 打在
    // `Path(args[i])`（`Main.kt:150`）上 ⇒ Java `WindowsPath.toString()` 恒出反斜杠。
    // 期望串取自**真跑 Kotlin 腿**的原文（tools/erbcc-rs/build/cli-polish/c4k.err），写死成字面量：
    //   Kotlin rc=2 + `错误: use-index 不是文件: tools\nope\index.dat`
    //   Rust（修前）rc=2 + `错误: use-index 不是文件: tools/nope/index.dat`
    // 修向：Rust 调用单源 `types::kotlin_path_text`（只改调用点 `cmd_scan.rs`，函数本体不动）。
    let rel = "tools/nope/index.dat";
    let want = concat!("错误: use-index 不是文件: ", "tools\\nope\\index.dat\n");
    let corpus = "tools/erbcc-rs/tests/data/rust-manifest/corpus";
    let dir = scratch("useindex");
    let out = dir.join("r").to_string_lossy().into_owned();
    let (k, r) = run_case(&["scan", "--corpus", corpus, "--use-index", rel, "--out", &out]);
    assert_eq!(k.rc, 2, "[④] Kotlin 腿：use-index 不是文件必须退 2（Main.kt:150 的 err()）");
    assert_eq!(r.rc, 2, "[④] Rust 腿同档");
    assert_eq!(k.stderr, want, "[④] 基准=真跑 Kotlin 腿的归一化报红原文");
    assert_eq!(r.stderr, want, "[④] ④ parity：Rust 腿逐字节同文（漏用 kotlin_path_text 就红）");
    assert_eq!(k.stdout, r.stdout, "[④] 两腿 stdout 全等（报红在起扫描之前）");
    assert_eq!(k.stdout, "", "[④] 这一格 stdout 必须是空的");
    assert!(!dir.join("r").exists(), "[④] 坏 --use-index ⇒ 两腿都不许建 --out 目录、不落报告");

    // 反向证明：正斜杠变体**不等于**基准 ⇒ 这格真的在分辨分隔符，不是恰好相等。
    let want_slash = "错误: use-index 不是文件: tools/nope/index.dat\n";
    assert_ne!(want, want_slash, "两条字面量必须只差在分隔符上，否则这格不分辨任何东西");
    let (k, r) = run_case(&[
        "scan",
        "--corpus",
        corpus,
        "--use-index",
        rel,
        "--out",
        &scratch("useindex-ne").join("r").to_string_lossy(),
    ]);
    assert_ne!(k.stderr, want_slash, "[④/反证] 基准不能是正斜杠变体");
    assert_ne!(r.stderr, want_slash, "[④/反证] Rust 腿回到原样回显就会红在这里");
}

// ===========================================================================
// 类别 3：未知参数
// ===========================================================================

#[test]
fn c3_unknown_args_exit_2() {
    let corpus = small_corpus();
    assert_full_parity("c3/global-nope", &["--nope"]);
    assert_full_parity("c3/scan-typo-strictt", &["scan", "--corpus", &corpus, "--strictt"]);
    assert_full_parity("c3/compile-bare-word", &["compile", "foo"]);
    assert_full_parity("c3/scan-dashdash", &["scan", "--"]);
    assert_full_parity("c3/symbols-bogus", &["symbols", "--corpus", &corpus, "--no-such"]);
}

#[test]
fn c3_flag_without_value_exit_2() {
    assert_full_parity("c3/scan-out-noval", &["scan", "--out"]);
    assert_full_parity("c3/compile-limit-noval", &["compile", "--corpus", &small_corpus(), "--limit"]);
    assert_full_parity("c3/audit-allow-noval", &["audit", "--corpus", &audit_erb(), "--allow"]);
}

// ===========================================================================
// 类别 4：开关组合（起 kernel，多为只比 rc；两处已知分歧钉死）
// ===========================================================================

#[test]
fn c4_audit_allow_missing_or_malformed_rc_parity() {
    // --allow 缺文件：两腿都「按空白名单处理」并往 stderr 打一行，随后正常审计；
    // --allow 坏行（合法 UTF-8）：两腿都「白名单行格式不对（跳过）」。审计有硬差异 ⇒ strict 下 rc=1。
    // 审计 stdout 的「编译：…（N ms）」含耗时 ⇒ 只比 rc。
    //
    // ②③（rust-cli-polish）加严：这里 `--allow` 传的是**绝对路径**（`data_file` 的口径），
    // 这一格两腿 stderr 在折行尾之后**必须逐字节相同**（实测 `tools/erbcc-rs/build/cli-polish/`
    // 的 `b3k.err` vs `b3r.err`：7 行全等、只差行终止符）—— 原来只比 rc 是放宽，现在把全文钉上。
    // 缺档提示行的**条数**（每腿恰好一行，不许 Rust 侧重复打）也在这里钉；
    // 相对路径入参时两腿仍不同形 ⇒ 那是另一格，见 `c4_audit_allow_缺档相对路径_路径形态分歧钉在册`。
    let erb = audit_erb();
    let out = scratch("allow1").join("u.tsv").to_string_lossy().into_owned();
    let (k, r) = run_case(&[
        "audit",
        "--corpus",
        &erb,
        "--strict",
        "--allow",
        &data_file("nope-missing.tsv"),
        "--out",
        &out,
    ]);
    assert_eq!(k.rc, r.rc, "[c4/allow-missing] 退出码不等");
    assert_eq!(k.rc, 1, "[c4/allow-missing] rc 来自 --strict 下的硬差异，不是缺档本身（②）");
    assert_eq!(
        k.stderr, r.stderr,
        "[c4/allow-missing] 绝对路径档的 stderr 两腿必须逐字节相同（折行尾后）"
    );
    assert_allow_missing_line_count(&k, 1, "Kotlin 腿");
    assert_allow_missing_line_count(&r, 1, "Rust 腿");

    let out2 = scratch("allow2").join("u.tsv").to_string_lossy().into_owned();
    assert_rc_parity(
        "c4/allow-malformed",
        &["audit", "--corpus", &erb, "--strict", "--allow", &data_file("allow-malformed.tsv"), "--out", &out2],
        "报告块含耗时读数",
    );
}

// ---------------------------------------------------------------------------
// `--allow` 缺档提示行（`Main.kt:680`）的两腿形态对账（③）
// ---------------------------------------------------------------------------

/// 那行的固定前缀（与 `src/audit.rs` 的 `ALLOW_MISSING_PREFIX` 同串；这里不复用是**刻意**的：
/// 测试要钉住「Kotlin 的原文」，不能被被测代码的单源带着一起改错）。
const ALLOW_MISSING_PREFIX: &str = "白名单文件不存在（按空白名单处理）: ";

/// 一次 run 里所有缺档提示行的（行号, 原文）。只许出现一次：Rust 接线成「自己按 Kotlin
/// 形态打一行」之后必须把 kernel 透出的那份滤掉，否则同一行上屏两遍。
fn allow_missing_lines(run: &Run) -> Vec<(usize, String)> {
    run.stderr
        .lines()
        .enumerate()
        .filter(|(_, l)| l.starts_with(ALLOW_MISSING_PREFIX))
        .map(|(i, l)| (i, l.to_string()))
        .collect()
}

fn assert_allow_missing_line_count(run: &Run, want: usize, leg: &str) {
    let got = allow_missing_lines(run);
    assert_eq!(got.len(), want, "[{leg}] 缺档提示行应恰好 {want} 条，实为 {got:?}");
}

#[test]
fn c4_audit_allow_缺档相对路径_路径形态两腿全文parity() {
    // ③（rust-cli-polish）：`--allow` 指到**不存在的档**且入参是**相对路径**时，缺档提示行的
    // 路径形态两腿不同 —— 与 F1（`corpus 不是目录`）同一把尺就能修，但落点在 `src/cmd_audit.rs`
    // （`allow_abs` 那段 + `stderr_head_lines` 的透出），**不在本车道所有权内**（CONTRACT-RUST1 的
    // rust-cli-polish 行），故按纪律把分歧**钉在册**并要求组长接线；归一后的正文单源已在
    // `src/audit.rs` 的 `allow_missing_warning_text` / `is_allow_missing_warning_line` 铺好。
    //
    // 实测读数（两腿真跑，原文落在 tools/erbcc-rs/build/cli-polish/a3k.err 与 a3r.err）：
    //   Kotlin: 白名单文件不存在（按空白名单处理）: tools\erbcc-rs\tests\data\rust-cli-surface\nope-missing.tsv
    //   Rust  : 白名单文件不存在（按空白名单处理）: <work-root>\eratw-android\tools\erbcc-rs\tests\data\rust-cli-surface\nope-missing.tsv
    // 成因：Rust 编排层把路径**绝对化**后才透给 kernel（`cmd_audit::orchestrate` 的 `allow_abs`），
    // kernel（同一个 Kotlin 入口）就按绝对形态打；Kotlin 直跑时 `Main.kt:680` 打在 `Path(原文)` 上
    // （只折分隔符、不绝对化）。
    //
    // 除这一行之外两腿 stderr **逐字节相同** ⇒ 本用例同时把「只差这一行」做实。
    let erb = audit_erb();
    let allow_rel = "tools/erbcc-rs/tests/data/rust-cli-surface/nope-missing.tsv";
    let out_k = scratch("allowrel-k").join("u.tsv").to_string_lossy().into_owned();
    let out_r = scratch("allowrel-r").join("u.tsv").to_string_lossy().into_owned();
    // Kotlin 腿与 Rust 腿各用**独立 --out**，避免两腿写同一目录互踩（同 c6 的口径）。
    let (k, _) = run_case(&[
        "audit", "--corpus", &erb, "--strict", "--allow", allow_rel, "--out", &out_k,
    ]);
    let (_, r) = run_case(&[
        "audit", "--corpus", &erb, "--strict", "--allow", allow_rel, "--out", &out_r,
    ]);

    assert_eq!(k.rc, r.rc, "[③/allow-rel] 退出码不等");
    assert_eq!(k.rc, 1, "[③/allow-rel] strict 下 8 处硬差异 ⇒ 1（②：缺档不占退出码）");

    let kl = allow_missing_lines(&k);
    let rl = allow_missing_lines(&r);
    assert_eq!(kl.len(), 1, "[③] Kotlin 腿恰好一行：{kl:?}");
    assert_eq!(rl.len(), 1, "[③] Rust 腿恰好一行（不许打两遍）：{rl:?}");
    assert_eq!(
        kl[0].0, rl[0].0,
        "[③] 两腿这一行的**位置**必须相同（行序也是 stderr 的一部分）：{} vs {}",
        kl[0].0, rl[0].0
    );

    // 基准 = 真跑 Kotlin 腿的原文：相对形态 + 反斜杠，与产出机绝对路径无关 ⇒ 写死成字面量。
    let want_k = concat!(
        "白名单文件不存在（按空白名单处理）: ",
        "tools\\erbcc-rs\\tests\\data\\rust-cli-surface\\nope-missing.tsv"
    );
    assert_eq!(
        want_k.strip_suffix("tools\\erbcc-rs\\tests\\data\\rust-cli-surface\\nope-missing.tsv"),
        Some(ALLOW_MISSING_PREFIX),
        "过滤用的前缀常量与基准原文的前缀必须是同一串"
    );
    assert_eq!(kl[0].1, want_k, "[③] 基准=真跑 Kotlin 腿的缺档提示原文（Main.kt:680）");

    // 组长接线已落地（rust-cli-polish 收口）：`cmd_audit::orchestrate` 用**原文**调
    // `audit::allow_missing_warning_text`，kernel 透出的那一份由 `stderr_head_lines` 携原文**就地换内容、不动行位置**
    // ⇒ 在册分歧 `allow-missing-path-form` 清账，本格从「钉分歧」翻成**全文 parity**。
    // 反向证明：把 `orchestrate` 那一处改回喂 `allow_abs` ⇒ 本行红在 Rust 腿的绝对形态上。
    assert_eq!(
        rl[0].1, want_k,
        "[③] Rust 腿的缺档提示行必须与 Kotlin 逐字相同（原文形态、不绝对化）：Rust={:?} Kotlin={:?}",
        rl[0].1, want_k
    );

    // 其余行整体逐字节相同（折行尾后）；「只差这一行」不是整段比不了的挡箭牌。
    let strip = |s: &str| {
        s.lines()
            .filter(|l| !l.starts_with(ALLOW_MISSING_PREFIX))
            .collect::<Vec<_>>()
            .join("\n")
    };
    assert_eq!(
        strip(&k.stderr),
        strip(&r.stderr),
        "[③] 除缺档行以外两腿 stderr 必须逐字节相同"
    );
    assert!(
        Path::new(&out_k).is_file() && Path::new(&out_r).is_file(),
        "[③] 缺档不是错误 ⇒ 两腿都要照常落 TSV"
    );
    assert_eq!(
        std::fs::read(&out_k).unwrap(),
        std::fs::read(&out_r).unwrap(),
        "[③] 缺白名单档的明细两腿逐字节相同"
    );
}

#[test]
fn c4_audit_allow_nonutf8_both_reject_parity() {
    // F2（已修·修的是 Kotlin 那侧）：`--allow` 指到**非法 UTF-8** 白名单时两腿都必须在
    // 起 kernel / 出报告块 / 落 TSV 之前拒绝，并且报红正文、退出码、stdout、落不落盘全等。
    //
    // 修前读数（同格四臂，见 build/cli-fixes/before/）：
    //   Kotlin：`path.readText()` 按 UTF-8 用替换字符（U+FFFD）有损解码 ⇒ 跑完整份审计、
    //           打满报告块、落 12 行 TSV，无 --strict ⇒ rc=0（=「以为挂了白名单、实际是一堆乱码」的静默放过）；
    //   Rust：严格解码 ⇒ 但拦在 kernel 跑完之后，rc=1、stdout 空、TSV 已被 kernel 落盘。
    //   两侧都不对：一侧放过、一侧延迟硬失败，所以修向是收紧 Kotlin（对齐「坏输入必须拒绝」），
    //   并把 Rust 的拦截点提到起 kernel 之前；绝不允许反过来把 Rust 改成有损放过。
    //
    // 修后口径：rc=2（参数错误档，与 `--allow 需要参数`、`--limit 需要正整数` 同尺，`Main.kt` 的 `err()`）；
    // stdout 空；stderr 恰一行报红；`--out` 一个字节都不落。
    // 期望串取自**真跑 Kotlin 腿**的改后读数（build/cli-fixes/after/f2-k-open.err），
    // 不是拿 Rust 输出当基准；入参用**相对正斜杠**路径，报红里的路径段才与产出机无关。
    let erb = audit_erb();
    let allow_rel = "tools/erbcc-rs/tests/data/rust-cli-surface/allow-nonutf8.tsv";
    let want_red = concat!(
        "错误: --allow 白名单不是合法 UTF-8 文本，已拒绝：",
        "tools\\erbcc-rs\\tests\\data\\rust-cli-surface\\allow-nonutf8.tsv",
        "（请把该文件另存为 UTF-8 再跑；这里不做有损解码，",
        "否则你以为挂上的是白名单，实际是一堆乱码）\n",
    );
    for strict in [false, true] {
        let dk = scratch(&format!("nonutf8-k-{strict}"));
        let dr = scratch(&format!("nonutf8-r-{strict}"));
        let out_k = dk.join("u.tsv").to_string_lossy().into_owned();
        let out_r = dr.join("u.tsv").to_string_lossy().into_owned();
        let mut head: Vec<&str> = vec!["audit", "--corpus", &erb];
        if strict {
            head.push("--strict");
        }
        let mut ak = head.clone();
        ak.extend_from_slice(&["--allow", allow_rel, "--out", &out_k]);
        let mut ar = head;
        ar.extend_from_slice(&["--allow", allow_rel, "--out", &out_r]);
        // 两腿各跑一次：Kotlin 腿给基准，Rust 腿被它比。
        let (k, _) = run_case(&ak);
        let (_, r) = run_case(&ar);
        let tier = if strict { "strict" } else { "open" };
        assert_eq!(k.rc, 2, "[F2/{tier}] Kotlin 腿必须按参数错误档拒绝");
        assert_eq!(r.rc, 2, "[F2/{tier}] Rust 腿退出码与 Kotlin 同（修前是 1）");
        assert_eq!(k.stdout, "", "[F2/{tier}] Kotlin 腿不得打报告块（修前打满一整块）");
        assert_eq!(r.stdout, "", "[F2/{tier}] Rust 腿不得打报告块");
        assert_eq!(k.stderr, want_red, "[F2/{tier}] 基准=真跑 Kotlin 腿的报红原文");
        assert_eq!(r.stderr, want_red, "[F2/{tier}] F2 parity：Rust 报红逐字节同文");
        assert!(!dk.join("u.tsv").exists(), "[F2/{tier}] Kotlin 腿不该落 TSV");
        assert!(!dr.join("u.tsv").exists(), "[F2/{tier}] Rust 腿不该落 TSV（修前 kernel 已落）");
    }
}

#[test]
fn c4_symbols_json_unwritable_known_divergence() {
    // --json 落点是个已存在的**目录** ⇒ 写不进去。两腿都退非 0（rc 相同），但报错面不同：
    //   Kotlin：AccessDeniedException 栈穿透 main（stderr 是 Java 栈），报告块已先打到 stdout；
    //   Rust：干净的一行 `错误: 写 --json 产物 … 失败`，stdout 无报告块（编排层缓冲后才打）。
    // 属「同结论不同文案」的既有 JVM/Rust 报错面差 ⇒ 只钉 rc 相等 + 两侧都失败，不比全文。
    let corpus = small_corpus();
    let (k, r) = run_case(&["symbols", "--corpus", &corpus, "--json", &corpus]);
    assert_eq!(k.rc, r.rc, "symbols --json 指到目录：两腿退出码应相等（都失败）");
    assert_ne!(k.rc, 0, "坏 --json 落点必须非 0（禁止假成功）");
}

#[test]
fn c4_symbols_collator_check_ordinal_full_parity() {
    // symbols 报告（无 --json、无耗时读数）⇒ 可全文比对。装载序/比较器判定权在 JVM，两腿同源。
    let corpus = small_corpus();
    let (k, _) = assert_full_parity(
        "c4/symbols-collator-ordinal",
        &["symbols", "--corpus", &corpus, "--collator-check", "--ordinal"],
    );
    assert_eq!(k, 0);
}

#[test]
fn c4_audit_roundtrip_limit_downgrade_before_dispatch() {
    // 复验上一波修过的顺序 bug：降级警告必须先于 --roundtrip 分派（Main.kt:474 先、:476 后）。
    // 观察口径：两腿 stderr 的第一行都必须是 `降级模式：只审计前 2 个文件…`；
    // stdout 对账块含耗时 ⇒ 只比 rc + stderr 首行。
    let erb = audit_erb();
    let art = scratch("rtart").to_string_lossy().into_owned();
    let out = scratch("rtout").join("rt.tsv").to_string_lossy().into_owned();
    let (k, r) = run_case(&[
        "audit", "--corpus", &erb, "--roundtrip", "--limit", "2",
        "--artifact-dir", &art, "--out", &out,
    ]);
    assert_eq!(k.rc, r.rc, "roundtrip --limit rc 不等");
    assert_eq!(k.rc, 0, "小语料 roundtrip 应全绿退 0");
    let downgrade = "降级模式：只审计前 2 个文件（正式闸必须全量）";
    assert_eq!(first_line(&k.stderr), downgrade, "Kotlin 降级警告须是 stderr 首行（分派前）");
    assert_eq!(first_line(&r.stderr), downgrade, "Rust 降级警告须是 stderr 首行（分派前）");
}

fn first_line(s: &str) -> &str {
    s.lines().next().unwrap_or("")
}

// ===========================================================================
// 类别 6：组合到真活（compile --limit 2，受限档 + 清单/产物逐字节同）
// ===========================================================================

#[test]
fn c6_compile_limit_two_limited_mode_and_artifacts_identical() {
    // compile --corpus <小语料> --out <目录> --limit 2：受限档必须 build-mode limited + file-limit 2，
    // 两腿产物三件套 sha 相等、manifest.txt 逐字节相等（清单不含 --out 绝对路径，仅 corpus-root，
    // 两腿同机同 corpus ⇒ 可比；stdout 落盘块含耗时 ⇒ 不比 stdout）。
    let corpus = small_corpus();
    let out_k = scratch("c6k").to_string_lossy().into_owned();
    let out_r = scratch("c6r").to_string_lossy().into_owned();
    let (k, r) = run_case(&["compile", "--corpus", &corpus, "--out", &out_k, "--limit", "2"]);
    let (_, r2) = run_case(&["compile", "--corpus", &corpus, "--out", &out_r, "--limit", "2"]);
    assert_eq!(k.rc, 0, "Kotlin compile --limit 2 rc");
    assert_eq!(r2.rc, 0, "Rust compile --limit 2 rc");
    let _ = r; // r 是同 out 的 Rust 腿，此处只用 r2 到独立目录，避免两腿写同一目录竞态

    let man_k = read_manifest(&PathBuf::from(&out_k));
    let man_r = read_manifest(&PathBuf::from(&out_r));
    assert!(man_k.contains("build-mode limited\n"), "Kotlin 清单 build-mode 非 limited：{man_k}");
    assert!(man_k.contains("file-limit 2\n"), "Kotlin 清单缺 file-limit 2");
    assert_eq!(man_k, man_r, "两腿 manifest.txt 不等（受限档口径分歧）");

    for name in ["program.erbc", "program.meta", "program.stmt.idx"] {
        let a = std::fs::read(PathBuf::from(&out_k).join("erbc.v1").join(name)).expect("K 产物");
        let b = std::fs::read(PathBuf::from(&out_r).join("erbc.v1").join(name)).expect("R 产物");
        assert_eq!(erbcc_rs::types::sha256_hex(&a), erbcc_rs::types::sha256_hex(&b), "{name} 两腿 sha 不等");
    }
}

fn read_manifest(out: &Path) -> String {
    let text = std::fs::read_to_string(out.join("erbc.v1").join("manifest.txt")).expect("读 manifest");
    decode_nl(text.as_bytes())
}

// ===========================================================================
// 反向证明：闸不空转
// ===========================================================================

#[test]
fn reverse_proof_f1_f2_pins_actually_discriminate() {
    // 反向证明的机械面：F1/F2 两格的期望值都是**写死的原文字面量**（取自真跑 Kotlin 腿的读数），
    // 不是从某一腿的输出推出来的，所以任何一侧回归都会红：
    //   - 任一臂漏用 kotlin_path_text（回退成原样回显正斜杠）⇒ 该臂 r.stderr 与 want 不等，红；
    //   - Kotlin 把 --allow 的严格解码改回 readText() ⇒ k.rc 变 0、stdout 冒出报告块，红；
    //   - Rust 的报红正文与 Kotlin 差一个字（或退出码回到 1）⇒ r.stderr/r.rc 不等，红。
    // 实操读数（两条各改一次跑一次看红再回滚）写在交付里。
    //
    // 这里再加一条「不是恰好相等」的证据：把基准串换成正斜杠变体，两腿都必须**不等于**它 ——
    // 若哪天有人把 want 改成随两侧输出动态生成，这一句会先失去意义，也就提醒他去补字面量。
    let rel_file = "tools/erbcc-rs/tests/data/rust-manifest/corpus/01_main.ERB";
    let want_bs = concat!(
        "错误: corpus 不是目录: ",
        "tools\\erbcc-rs\\tests\\data\\rust-manifest\\corpus\\01_main.ERB\n",
    );
    let want_slash = "错误: corpus 不是目录: tools/erbcc-rs/tests/data/rust-manifest/corpus/01_main.ERB\n";
    assert_ne!(want_bs, want_slash, "两条字面量必须只差在分隔符上，否则这格不分辨任何东西");
    let (k, r) = run_case(&["scan", "--corpus", rel_file]);
    assert_eq!(k.stderr, want_bs, "[反证/F1] Kotlin 腿仍是反斜杠原文");
    assert_eq!(r.stderr, want_bs, "[反证/F1] Rust 腿已跟上归一口径（漏用 helper 就红在这里）");
    assert_ne!(k.stderr, want_slash, "[反证/F1] 基准不能是正斜杠变体");
    assert_ne!(r.stderr, want_slash, "[反证/F1] 同上");

    // F2 侧：坏 UTF-8 白名单必须是 rc=2 的拒绝，且 stdout 一个字节都没有 ——
    // 一旦谁把严格解码放宽（任一侧），rc 回 0/1、或报告块冒出来，这四句立刻红。
    let erb = audit_erb();
    let allow_rel = "tools/erbcc-rs/tests/data/rust-cli-surface/allow-nonutf8.tsv";
    let d = scratch("rev-nonutf8");
    let out = d.join("u.tsv").to_string_lossy().into_owned();
    let (k, r) = run_case(&["audit", "--corpus", &erb, "--allow", allow_rel, "--out", &out]);
    assert_eq!(k.rc, 2, "[反证/F2] Kotlin 腿必须仍是参数错误档拒绝");
    assert_eq!(r.rc, 2, "[反证/F2] Rust 腿同档");
    assert_eq!(k.stdout, "", "[反证/F2] 放宽回有损解码就会打报告块");
    assert_eq!(r.stdout, "", "[反证/F2] 同上");
    assert!(k.stderr.starts_with("错误: --allow 白名单不是合法 UTF-8"), "[反证/F2] 报红必须点名白名单：{}", k.stderr);
    assert_eq!(k.stderr, r.stderr, "[反证/F2] 两腿报红逐字节同文");
}
