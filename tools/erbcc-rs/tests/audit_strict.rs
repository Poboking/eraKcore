//! **rust-audit 车道的出口闸（2/2）**：`--strict` 的退出码与「哪些 kind 算硬差异、
//! 哪些被 `--allow` 放行」的判定，必须与 Kotlin 一致；白名单**双向**证明
//! （放行前两侧都红、放行后两侧都绿），防闸空转。
//!
//! Kotlin 权威口径（逐条带 文件:行号）：
//! - `Main.kt:502` `blocking = report.blocking.filterNot { allowed.matches(it) }`
//!   ⇒ 硬差异 = 「`kind.blocking` 为真」且「不在白名单」；
//! - `Main.kt:522-525` `if (strict && blocking.isNotEmpty()) { err红; return 1 }`
//!   ⇒ 只有 `--strict` 才把硬差异变成退出码；
//! - `SymbolLinkAudit.kt:29-46` 8 类 `FindingKind` 的 blocking 位（只有
//!   `COMPILE_DIM_WARNING(false)` 非红）+ `:84` `blocking = findings.filter { it.kind.blocking }`；
//! - `SymbolLinkAudit.kt:238-245` 关键字使用点里 `keyword == null`（**动态下标**）只计数、
//!   `continue`，**根本不生成 Finding** ⇒「动态关键字下标不计」是 JVM 侧不产读数，
//!   Rust 侧不许自创「按 kind 过滤动态项」的第二套口径（见 `src/audit.rs` 的 FINDING_KINDS 注）；
//! - `Main.kt:611-642` 白名单：`<kind>\t<name>` / `<kind>\t<functionKey>\t<name>`、
//!   `#` 注释、坏行跳过、缺文件按空白名单处理。
//!
//! 语料与基准同 `tests/audit_tsv_parity.rs`（`tests/data/rust-audit/`，基准由真 Kotlin 产出）。

use erbcc_rs::audit;
use erbcc_rs::cmd_audit;
use erbcc_rs::kernel::{self, KernelOutput};
use erbcc_rs::manifest;
use erbcc_rs::types::{absolutize, ErbccError, Outcome};
use std::path::{Path, PathBuf};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&manifest_dir()).expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-audit").join(rel)
}

/// 好档：`game/ERB`（8 文件、12 条读数、8 处硬差异）。
fn game_root() -> PathBuf {
    data("game")
}

fn corpus() -> PathBuf {
    data("game/ERB")
}

/// 坏档：`bad-game/ERB`（解析失败 ⇒ Kotlin `Main.kt:486-489` 退 1、**不落 TSV**）。
fn bad_game_root() -> PathBuf {
    data("bad-game")
}

fn bad_corpus() -> PathBuf {
    data("bad-game/ERB")
}

fn gate_out(tag: &str) -> PathBuf {
    let dir = manifest_dir().join(format!("build/audit-strict-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("建 --strict 闸输出目录");
    dir
}

/// 本测试自己解析 classpath / java（与 `kernel.rs` 独立，两条腿不许共用一个 bug）。
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

/// 腿 A：纯 Kotlin 的 `audit`。返回（退出码，stdout，stderr）。
fn run_kotlin(corpus: &Path, game: &Path, out_tsv: &Path, extra: &[&str]) -> (i32, String, String) {
    let mut cmd = std::process::Command::new(java_exe());
    cmd.args(["-Xmx6g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(kotlin_tool_classpath())
        .arg("com.eratw.port.erbcc.MainKt")
        .arg("audit")
        .arg("--corpus")
        .arg(absolutize(corpus))
        .arg("--game-root")
        .arg(absolutize(game))
        .arg("--out")
        .arg(absolutize(out_tsv))
        .args(extra)
        .current_dir(repo_root());
    let ok = cmd.output().expect("起纯 Kotlin 工具链失败（腿 A）：需要 JAVA_HOME + installDist 产物");
    (
        ok.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&ok.stdout).into_owned(),
        String::from_utf8_lossy(&ok.stderr).into_owned(),
    )
}

/// 腿 B：Rust 编排（真 kernel 桥 + 真写出层）。
fn run_rust(corpus: &Path, game: &Path, out_tsv: &Path, extra: &[&str]) -> Outcome<i32> {
    let mut args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus.to_string_lossy().into_owned(),
        "--game-root".to_string(),
        game.to_string_lossy().into_owned(),
        "--out".to_string(),
        out_tsv.to_string_lossy().into_owned(),
    ];
    for e in extra {
        args.push((*e).to_string());
    }
    cmd_audit::execute(&args)
}

/// `硬差异：N 处` 行（`Main.kt:507`）里的 N；`（另有 M 处按白名单放行）` 里的 M。
fn kotlin_hard_diff(stdout: &str) -> (usize, usize) {
    let line = stdout
        .lines()
        .find(|l| l.starts_with("硬差异："))
        .unwrap_or_else(|| panic!("Kotlin stdout 没有 `硬差异：N 处` 行（Main.kt:507）：\n{stdout}"));
    let head = line
        .trim_start_matches("硬差异：")
        .split(' ')
        .next()
        .unwrap_or_default();
    let hard: usize = head
        .trim_end_matches('处')
        .parse()
        .unwrap_or_else(|e| panic!("硬差异条数解不出来 `{line}`：{e}"));
    let suppressed = match line.find("另有 ") {
        Some(i) => line[i + "另有 ".len()..]
            .trim_start()
            .split(' ')
            .next()
            .unwrap_or_default()
            .trim_end_matches('处')
            .parse()
            .unwrap_or_else(|e| panic!("放行条数解不出来 `{line}`：{e}")),
        None => 0,
    };
    (hard, suppressed)
}

/// `关键字 N 次 / M 处（静态 S / 动态 D）` 行（`Main.kt:491-495`）里的 S 与 D。
fn kotlin_keyword_sites(stdout: &str) -> (usize, usize) {
    let line = stdout
        .lines()
        .find(|l| l.contains("关键字") && l.contains("静态"))
        .unwrap_or_else(|| panic!("Kotlin stdout 没有符号使用行（Main.kt:491-495）：\n{stdout}"));
    let grab = |mark: &str| -> usize {
        let at = line
            .find(mark)
            .unwrap_or_else(|| panic!("符号使用行缺 `{mark}`：{line}"));
        line[at + mark.len()..]
            .trim_start()
            .trim_end_matches(|c| c == '/' || c == '（' || c == '）' || c == ' ')
            .split(' ')
            .next()
            .unwrap_or_default()
            .parse()
            .unwrap_or_else(|e| panic!("`{mark}` 后面的读数解不出来：{line}（{e}）"))
    };
    (grab("静态"), grab("动态"))
}

/// `编译期 #DIM 告警（level 2，非红）：N 处`（`Main.kt:516`）；没有该行按 0 处理。
fn kotlin_dim_warning_sites(stdout: &str) -> usize {
    match stdout.lines().find(|l| l.starts_with("编译期 #DIM 告警（level 2，非红）：")) {
        Some(line) => {
            let rest = line.split('：').nth(1).unwrap_or_default();
            rest.split(' ')
                .next()
                .unwrap_or_default()
                .trim_end_matches('处')
                .parse()
                .unwrap_or_else(|e| panic!("告警条数解不出来 `{line}`：{e}"))
        }
        None => 0,
    }
}

fn read_tsv(path: &Path) -> Vec<audit::Finding> {
    let bytes = std::fs::read(path).unwrap_or_else(|e| panic!("读不到 {path:?}：{e}"));
    audit::parse_tsv(&String::from_utf8(bytes).expect("TSV 应是 UTF-8（SymbolLinkAudit.kt:101）"))
        .expect("TSV 解析失败")
}

// ---------------------------------------------------------------------------
// 1. --strict 退出码：两侧同红 / 同绿（真跑两腿）
// ---------------------------------------------------------------------------

#[test]
fn strict_无白名单_两侧同红且硬差异条数同尺() {
    let dir = gate_out("strict-none");
    let a = dir.join("a.tsv");
    let b = dir.join("b.tsv");

    let (rc_a, stdout_a, stderr_a) = run_kotlin(&corpus(), &game_root(), &a, &["--strict"]);
    assert_eq!(rc_a, 1, "腿 A：--strict + 8 处硬差异应退 1（Main.kt:522-525）\nstdout:{stdout_a}\nstderr:{stderr_a}");
    let (hard_a, suppressed_a) = kotlin_hard_diff(&stdout_a);
    assert_eq!((hard_a, suppressed_a), (8, 0), "腿 A 的硬差异读数");
    assert!(
        stderr_a.contains("审计未通过：--strict 下仍有 8 处硬差异。"),
        "腿 A 的红行文案（Main.kt:523）实为：\n{stderr_a}"
    );

    let rc_b = run_rust(&corpus(), &game_root(), &b, &["--strict"]).expect("腿 B 报红");
    assert_eq!(rc_b, 1, "腿 B：同一判据必须同样退 1");

    // Rust 侧独立复算（读腿 B 自己落的 TSV + 空白名单）
    let findings = read_tsv(&b);
    let hard_b = audit::hard_diff_count(&findings, &[]).expect("Rust 复算");
    assert_eq!(hard_b, hard_a, "Rust 复算的硬差异条数必须等于 Kotlin 的读数（Main.kt:502/507）");
    assert_eq!(findings.len(), 12, "基准应有 12 条读数");
}

#[test]
fn strict_全量放行档_两侧都转绿() {
    // 双向证明的后半：放行后两侧都绿（放行前见上一条用例，两侧都红）。
    let dir = gate_out("strict-fullpass");
    let allow = data("allow/full-pass.tsv");
    let a = dir.join("a.tsv");
    let b = dir.join("b.tsv");

    let (rc_a, stdout_a, stderr_a) =
        run_kotlin(&corpus(), &game_root(), &a, &["--strict", "--allow", &allow.to_string_lossy()]);
    assert_eq!(rc_a, 0, "腿 A：全部硬差异被放行 ⇒ 退 0（Main.kt:502 的 filterNot 空了）\nstderr:{stderr_a}");
    let (hard_a, suppressed_a) = kotlin_hard_diff(&stdout_a);
    assert_eq!((hard_a, suppressed_a), (0, 8), "腿 A 应报「0 处（另有 8 处按白名单放行）」");

    let rc_b = run_rust(&corpus(), &game_root(), &b, &["--strict", "--allow", &allow.to_string_lossy()])
        .expect("腿 B 报红");
    assert_eq!(rc_b, 0, "腿 B：同一张白名单必须同样退 0");

    let allowed = audit::load_allowlist(&allow).expect("读白名单");
    assert_eq!(allowed.len(), 8, "全量放行档应有 8 条（2 条注释行不计）");
    let findings = read_tsv(&b);
    assert_eq!(audit::hard_diff_count(&findings, &allowed).expect("Rust 复算"), 0);
    // 放行前后 TSV 本身不许变（白名单只影响退出码，不影响明细）
    assert_eq!(
        std::fs::read(&b).unwrap(),
        std::fs::read(data("expected/full.tsv")).unwrap(),
        "--allow 不该改变明细 TSV（Main.kt:519 的 writeTsv 在 filterNot 之后、无条件落全量）"
    );
}

#[test]
fn strict_部分放行_两侧同红且剩余条数同尺() {
    let dir = gate_out("strict-partial");
    let allow = data("allow/partial.tsv");
    let a = dir.join("a.tsv");
    let b = dir.join("b.tsv");

    let (rc_a, stdout_a, stderr_a) =
        run_kotlin(&corpus(), &game_root(), &a, &["--strict", "--allow", &allow.to_string_lossy()]);
    assert_eq!(rc_a, 1, "腿 A：8 处里只放行 2 处 ⇒ 仍退 1\nstderr:{stderr_a}");
    let (hard_a, suppressed_a) = kotlin_hard_diff(&stdout_a);
    assert_eq!((hard_a, suppressed_a), (6, 2), "腿 A 应报「6 处（另有 2 处按白名单放行）」");

    let rc_b = run_rust(&corpus(), &game_root(), &b, &["--strict", "--allow", &allow.to_string_lossy()])
        .expect("腿 B 报红");
    assert_eq!(rc_b, 1, "腿 B：部分放行必须同样仍退 1");

    let allowed = audit::load_allowlist(&allow).expect("读白名单");
    // 部分放行档：3 条 2/3 列行 + 1 条 2 列的未知 kind（Kotlin 也照收不误，Main.kt:636）
    // + 1 条 1 列坏行（跳过，:638）⇒ 4 条生效项
    assert_eq!(allowed.len(), 4, "坏行跳过、未知 kind 仍入库（与 Kotlin 的 HashSet 同口径）");
    let findings = read_tsv(&b);
    assert_eq!(audit::hard_diff_count(&findings, &allowed).expect("Rust 复算"), hard_a);
}

#[test]
fn strict_越权白名单不放行任何东西_两侧同尺() {
    // 防闸空转的另一半：白名单形状对但 functionKey 不对 ⇒ 一条也不该放行。
    let dir = gate_out("strict-nonmatch");
    let allow = data("allow/nonmatching.tsv");
    let (rc_a, stdout_a, _) =
        run_kotlin(&corpus(), &game_root(), &dir.join("a.tsv"), &["--strict", "--allow", &allow.to_string_lossy()]);
    let (hard_a, suppressed_a) = kotlin_hard_diff(&stdout_a);
    assert_eq!((rc_a, hard_a, suppressed_a), (1, 8, 0), "腿 A：越权白名单必须一条不放");

    let b = dir.join("b.tsv");
    let rc_b = run_rust(&corpus(), &game_root(), &b, &["--strict", "--allow", &allow.to_string_lossy()])
        .expect("腿 B 报红");
    let allowed = audit::load_allowlist(&allow).expect("读白名单");
    let hard_b = audit::hard_diff_count(&read_tsv(&b), &allowed).expect("Rust 复算");
    assert_eq!((rc_b, hard_b), (1, hard_a), "腿 B 必须与腿 A 同尺");
}

#[test]
fn 非红kind不计入硬差异_动态关键字下标不生成读数() {
    // 全部用腿 A 的真读数交叉核对，Rust 侧不另立判据：
    // 1) `编译期 #DIM 告警（level 2，非红）：N 处` 的那 N 条**不进** blocking（SymbolLinkAudit.kt:45）；
    // 2) `（静态 S / 动态 D）` 里的 D 不生成 Finding（SymbolLinkAudit.kt:238-245）
    //    ⇒ TSV 里 KEYWORD_* 的行数 == S，而不是 S+D。
    let dir = gate_out("kinds");
    let a = dir.join("a.tsv");
    let (rc_a, stdout_a, _) = run_kotlin(&corpus(), &game_root(), &a, &[]);
    assert_eq!(rc_a, 0);
    let (hard_a, _) = kotlin_hard_diff(&stdout_a);
    let (static_sites, dynamic_sites) = kotlin_keyword_sites(&stdout_a);
    let warnings_a = kotlin_dim_warning_sites(&stdout_a);

    let findings = read_tsv(&a);
    let keyword_rows = findings
        .iter()
        .filter(|f| f.kind == "KEYWORD_UNBOUND" || f.kind == "KEYWORD_USER_VAR")
        .count();
    assert!(dynamic_sites > 0, "语料必须真的带一个动态关键字下标使用点，否则这条断言空转");
    assert_eq!(
        keyword_rows, static_sites,
        "TSV 的关键字类读数应等于「静态」使用点数（动态下标不生成 Finding）"
    );

    let warn_rows = findings
        .iter()
        .filter(|f| f.kind == "COMPILE_DIM_WARNING")
        .count();
    assert_eq!(warn_rows, warnings_a, "`编译期 #DIM 告警（非红）` 条数应等于 TSV 的该类行数");

    let blocking = audit::blocking_findings(&findings).expect("Rust 复算");
    assert_eq!(blocking.len(), hard_a, "Rust 的 blocking 条数必须等于 Kotlin 的硬差异读数");
    assert_eq!(
        findings.len() as usize - warn_rows,
        hard_a,
        "硬差异 = 全部读数 - 非红告警（COMPILE_DIM_WARNING 是唯一非红类，SymbolLinkAudit.kt:45）"
    );
}

// ---------------------------------------------------------------------------
// 2. 编译失败档与前置报红
// ---------------------------------------------------------------------------

#[test]
fn 编译失败档_两侧退1且不落tsv() {
    // Main.kt:486-490：`!report.result.ok` ⇒ 打「编译失败 N 条」后 return 1，
    // `:519` 的 writeTsv 在它之后，所以**一个字节都不落**。
    let dir = gate_out("badcompile");
    let a = dir.join("a.tsv");
    let b = dir.join("b.tsv");

    let (rc_a, stdout_a, stderr_a) = run_kotlin(&bad_corpus(), &bad_game_root(), &a, &[]);
    assert_eq!(rc_a, 1, "腿 A（坏档）应退 1\nstdout:{stdout_a}\nstderr:{stderr_a}");
    assert!(
        stderr_a.contains("条（至多列 50 条）:"),
        "腿 A 应有编译失败段（Main.kt:487），实为：\n{stderr_a}"
    );
    assert!(!a.exists(), "腿 A 编译失败时不该落 TSV");

    let rc_b = run_rust(&bad_corpus(), &bad_game_root(), &b, &[]).expect("腿 B（坏档）报红");
    assert_eq!(rc_b, 1, "腿 B：编译失败必须同样退 1，不许退 0 也不许升级成参数错误");
    assert!(!b.exists(), "腿 B 编译失败时不该落 TSV（也不该凭空复算一份）");
}

#[test]
fn 游戏根缺csv目录_两侧都退2且文案逐字相同() {
    // Main.kt:471-473：缺 `CSV/` 直接报红。这条必须复刻 —— 让它静默跑出一份全红 TSV
    // 等于把闸变成噪音（任务点名的「满屏假红」）。
    let dir = gate_out("nocsv");
    let no_csv_root = data("expected"); // 一个真实存在、但没有 CSV 子目录的目录
    let a = dir.join("a.tsv");
    let (rc_a, _, stderr_a) = run_kotlin(&corpus(), &no_csv_root, &a, &[]);
    assert_eq!(rc_a, 2, "腿 A：缺 CSV ⇒ err() 恒退 2（Main.kt:162-165）\nstderr:{stderr_a}");
    let line_a = stderr_a
        .lines()
        .find(|l| l.starts_with("错误: 游戏根缺少 CSV 目录: "))
        .unwrap_or_else(|| panic!("腿 A 文案不对，实为：\n{stderr_a}"));
    assert!(line_a.contains("缺表时审计会满屏假红"), "文案尾句要照 Kotlin 原样：{line_a}");

    let b = dir.join("b.tsv");
    let err = run_rust(&corpus(), &no_csv_root, &b, &[]).expect_err("腿 B 必须报红");
    let msg = match &err {
        ErbccError::Usage(m) => m.clone(),
        other => panic!("缺 CSV 应走 Usage（退出码 2），实为 {other:?}"),
    };
    assert_eq!(
        format!("错误: {msg}"),
        line_a,
        "两侧报红文案必须逐字相同（路径口径都由 types::absolutize + 分隔符归一给出）"
    );
    assert!(!b.exists(), "前置报红不该起 kernel、不该落 TSV");
}

#[test]
fn allow文件不存在_按空白名单处理且两侧同红() {
    // Main.kt:625-628：缺文件不是错误，打一行提示后当空白名单。
    let dir = gate_out("noallow");
    let missing = dir.join("definitely-not-there.tsv");
    let a = dir.join("a.tsv");
    let b = dir.join("b.tsv");

    let (rc_a, stdout_a, stderr_a) = run_kotlin(
        &corpus(),
        &game_root(),
        &a,
        &["--strict", "--allow", &missing.to_string_lossy()],
    );
    assert_eq!(rc_a, 1, "缺白名单 ⇒ 一处也没放行，仍退 1");
    assert!(
        stderr_a.contains("白名单文件不存在（按空白名单处理）"),
        "腿 A 应打 Main.kt:626 的提示，实为：\n{stderr_a}"
    );
    assert_eq!(kotlin_hard_diff(&stdout_a).0, 8);

    assert!(audit::load_allowlist(&missing).expect("缺文件不该是错误").is_empty());
    let rc_b = run_rust(&corpus(), &game_root(), &b, &["--strict", "--allow", &missing.to_string_lossy()])
        .expect("腿 B 报红");
    assert_eq!(rc_b, 1, "腿 B：同一档必须同样退 1");
    assert_eq!(
        std::fs::read(&b).unwrap(),
        std::fs::read(&a).unwrap(),
        "缺白名单档的明细两侧逐字节相同"
    );
}

// ---------------------------------------------------------------------------
// 3. 编排层的判据闸（替身 kernel，不起 JVM）
// ---------------------------------------------------------------------------

/// 替身 kernel：把 `canned()` 的读数写到 `out`，并按 `EXIT` 返回 kernel 的文本。
/// 每个替身只服务一条用例的退出码面 ⇒ 用不同函数，避免共享可变状态。
const CANNED_TSV: &str = "kind\tfile\tfunction\tline\tname\tdetail\tcount\n\
VAR_UNBOUND\tA1_UNBOUND.ERB\tAUDIT_A1\t1\tAUDIT_NO_SUCH_VAR_A1\tdetail one\t1\n\
KEYWORD_USER_VAR\tA2_KEYWORD.ERB\tAUDIT_A2\t3\tAUDIT_LOCAL_VAR:NOKEY\tdetail two\t2\n\
COMPILE_DIM_WARNING\tA6_PRIVWARN.ERB\t\t0\tAUDIT_P6A\tnot red\t1\n";

/// `Main.kt:507` 的读数行（与 CANNED_TSV 的 2 处硬差异同尺）。
fn canned_stdout(hard: usize, suppressed: usize) -> String {
    let mut s = String::from("=== 符号链接审计 ===\n编译：文件 3  切片 3  成功 3  失败 0  解析失败切片 0  指令 12（1 ms）\n符号使用：语句 4  VarRef 6 次 / 4 处  关键字 2 次 / 2 处（静态 2 / 动态 0）\n运行期命名空间：全局用户变量 0  注册私有 #DIM 2  系统变量 158\n\n");
    s.push_str(&format!("硬差异：{hard} 处"));
    if suppressed > 0 {
        s.push_str(&format!("（另有 {suppressed} 处按白名单放行）"));
    }
    s.push('\n');
    s
}

/// 真落盘的替身 kernel：写 CANNED_TSV，退 1（`--strict` 下有硬差异）。
fn fake_kernel_red(
    repo_root: &Path,
    _corpus: &str,
    _game: &Path,
    out: &Path,
    _limit: Option<i32>,
    _strict: bool,
    _allow: Option<&Path>,
    _env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let _ = repo_root;
    std::fs::write(out, CANNED_TSV).expect("替身 kernel 落 TSV");
    Ok(KernelOutput {
        exit_code: 1,
        stdout: canned_stdout(2, 0),
        stderr: String::new(),
        elapsed_ms: 1,
    })
}

/// 替身 kernel：退 0，但 TSV 里仍有 2 处硬差异 ⇒ 本路必须抓出「kernel 忘了入闸」。
fn fake_kernel_forgets_to_gate(
    repo_root: &Path,
    corpus: &str,
    game: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
    env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let started = fake_kernel_red(repo_root, corpus, game, out, limit, strict, allow, env)?;
    Ok(KernelOutput {
        exit_code: 0,
        ..started
    })
}

/// 替身 kernel：非红档（全部读数被放行）⇒ 两侧都该退 0。
fn fake_kernel_green_all_allowed(
    repo_root: &Path,
    _corpus: &str,
    _game: &Path,
    out: &Path,
    _limit: Option<i32>,
    _strict: bool,
    _allow: Option<&Path>,
    _env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let _ = repo_root;
    std::fs::write(out, CANNED_TSV).expect("替身 kernel 落 TSV");
    Ok(KernelOutput {
        exit_code: 0,
        stdout: canned_stdout(0, 2),
        stderr: String::new(),
        elapsed_ms: 1,
    })
}

/// 替身 kernel：编译失败段（`Main.kt:487`），**不落 TSV**。
fn fake_kernel_compile_failure(
    repo_root: &Path,
    _corpus: &str,
    _game: &Path,
    _out: &Path,
    _limit: Option<i32>,
    _strict: bool,
    _allow: Option<&Path>,
    _env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let _ = repo_root;
    Ok(KernelOutput {
        exit_code: 1,
        stdout: "=== 符号链接审计 ===\n编译：文件 1  切片 0  成功 1  失败 0  解析失败切片 2  指令 2（1 ms）\n".to_string(),
        stderr: "编译失败 2 条（至多列 50 条）:\n  B1_POISON.ERB:1 语句解析错误\n".to_string(),
        elapsed_ms: 1,
    })
}

/// 替身 kernel：JVM 退了 3（不在 0/1 契约内）⇒ 编排层必须报红，不许当成功。
fn fake_kernel_bogus_exit(
    repo_root: &Path,
    corpus: &str,
    game: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
    env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let started = fake_kernel_red(repo_root, corpus, game, out, limit, strict, allow, env)?;
    Ok(KernelOutput {
        exit_code: 3,
        ..started
    })
}

fn deps_with(run_kernel: fn(
    &Path,
    &str,
    &Path,
    &Path,
    Option<i32>,
    bool,
    Option<&Path>,
    &kernel::EnvFn,
) -> Outcome<KernelOutput>) -> cmd_audit::Deps {
    cmd_audit::Deps {
        run_kernel,
        read_file,
        atomic_write: manifest::atomic_write,
        load_allowlist: audit::load_allowlist,
    }
}

fn read_file(path: &Path) -> Outcome<Vec<u8>> {
    std::fs::read(path).map_err(|e| ErbccError::Io(format!("{}: {e}", path.display())))
}

/// 替身档的入参：语料/游戏根用真的好档（要让 parse_args 通过），`--out` 指到独占目录。
fn canned_args(dir: &Path, allow: Option<&Path>) -> Vec<String> {
    let mut v = vec![
        "--corpus".to_string(),
        corpus().to_string_lossy().into_owned(),
        "--game-root".to_string(),
        game_root().to_string_lossy().into_owned(),
        "--out".to_string(),
        dir.join("c.tsv").to_string_lossy().into_owned(),
    ];
    if let Some(a) = allow {
        v.push("--allow".to_string());
        v.push(a.to_string_lossy().into_owned());
    }
    v
}

#[test]
fn 替身档_strict有硬差异_编排退1且明细由rust侧落盘() {
    let dir = gate_out("fake-red");
    let deps = deps_with(fake_kernel_red);
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = canned_args(&dir, None);
    args.push("--strict".to_string());
    let code = cmd_audit::orchestrate(&repo_root(), &args, env, &deps).expect("编排报红");
    assert_eq!(code, 1, "Main.kt:522-525：--strict + 2 处硬差异 ⇒ 1");
    // 本路自己的原子落盘必须已经把复算结果写回 --out
    assert_eq!(std::fs::read(dir.join("c.tsv")).unwrap(), CANNED_TSV.as_bytes());
}

#[test]
fn 替身档_白名单放行全部硬差异_编排退0() {
    let dir = gate_out("fake-green");
    let allow = dir.join("allow.tsv");
    // 现场造一张恰好覆盖 CANNED_TSV 两处硬差异的白名单（2 列 + 3 列各一条）
    std::fs::write(
        &allow,
        "VAR_UNBOUND\tAUDIT_NO_SUCH_VAR_A1\nKEYWORD_USER_VAR\tAUDIT_A2\tAUDIT_LOCAL_VAR:NOKEY\n# 注释行\n",
    )
    .expect("写白名单");
    let deps = deps_with(fake_kernel_green_all_allowed);
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = canned_args(&dir, Some(&allow));
    args.push("--strict".to_string());
    let code = cmd_audit::orchestrate(&repo_root(), &args, env, &deps).expect("编排报红");
    assert_eq!(code, 0, "放行后 blocking 空 ⇒ 退 0（Main.kt:502 + :522）");
    // 同一张白名单对着 kernel 的读数复算，必须也是 0 处
    let allowed = audit::load_allowlist(&allow).expect("读白名单");
    assert_eq!(allowed.len(), 2, "注释行与空行不计（Main.kt:633）");
    assert_eq!(audit::hard_diff_count(&read_tsv(&dir.join("c.tsv")), &allowed).expect("复算"), 0);
    assert!(!audit::has_hard_diff(&read_tsv(&dir.join("c.tsv")), &allowed).expect("复算"));
    assert!(audit::has_hard_diff(&read_tsv(&dir.join("c.tsv")), &[]).expect("空白名单复算"));
}

#[test]
fn 替身档_kernel忘了入闸_编排必须报parity红() {
    // 交叉核对面：kernel 退 0，但 Rust 自己数出 2 处硬差异 ⇒ 不许跟着退 0（§0.2 不自创口径、
    // 也不许把两侧不一致糊过去）。
    let dir = gate_out("fake-ungated");
    let deps = deps_with(fake_kernel_forgets_to_gate);
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = canned_args(&dir, None);
    args.push("--strict".to_string());
    let err = cmd_audit::orchestrate(&repo_root(), &args, env, &deps)
        .expect_err("kernel 退 0 而 Rust 数出硬差异，必须报红");
    match err {
        ErbccError::Parity(m) => assert!(m.contains("判据两侧不等"), "文案要点名判据不等，实为：{m}"),
        other => panic!("应报 Parity，实为 {other:?}"),
    }
}

#[test]
fn 替身档_编译失败不落tsv不退0() {
    let dir = gate_out("fake-badcompile");
    let deps = deps_with(fake_kernel_compile_failure);
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = canned_args(&dir, None);
    args.push("--strict".to_string());
    let code = cmd_audit::orchestrate(&repo_root(), &args, env, &deps).expect("编排报红");
    assert_eq!(code, 1, "Main.kt:489：编译失败 ⇒ 1（与本路判据无关）");
    assert!(!dir.join("c.tsv").exists(), "编译失败档不许由 Rust 侧凭空复算出一份 TSV");
}

#[test]
fn 替身档_kernel异常退出码必须报红() {
    let dir = gate_out("fake-bogus");
    let deps = deps_with(fake_kernel_bogus_exit);
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = canned_args(&dir, None);
    args.push("--strict".to_string());
    let err = cmd_audit::orchestrate(&repo_root(), &args, env, &deps)
        .expect_err("kernel 退 3 不在 0/1 契约内（Main.kt:87）⇒ 必须报红");
    match err {
        ErbccError::Io(m) => {
            assert!(m.contains("退出码 3"), "文案要点名退出码，实为：{m}");
            assert!(m.contains("契约"), "文案要点名 0/1 契约，实为：{m}");
        }
        other => panic!("应报 Io，实为 {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// 4. 白名单匹配口径（纯 Rust 侧，逐条钉 Kotlin 行号）
// ---------------------------------------------------------------------------

#[test]
fn 白名单两级匹配的键形与整串相等() {
    // Main.kt:616-617（matches）+ :636-637（读入时的键形）；大小写不折叠、不做前缀匹配。
    let f = audit::Finding {
        kind: "VAR_UNBOUND".to_string(),
        name: "RESULT".to_string(),
        function_key: Some("AUDIT_F1".to_string()),
        detail: String::new(),
        file: "A.ERB".to_string(),
        line: 1,
        occurrences: 1,
    };
    let parse = |text: &str| {
        let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("build/audit-strict-keyshape.tmp");
        std::fs::write(&p, text).expect("写临时白名单");
        let v = audit::load_allowlist(&p).expect("读临时白名单");
        let _ = std::fs::remove_file(&p);
        v
    };

    assert!(audit::allow_matches(&parse("VAR_UNBOUND\tRESULT\n"), &f), "2 列行按 kind+name 放行");
    assert!(
        audit::allow_matches(&parse("VAR_UNBOUND\tAUDIT_F1\tRESULT\n"), &f),
        "3 列行按 kind+functionKey+name 放行"
    );
    assert!(!audit::allow_matches(&parse("VAR_UNBOUND\tAUDIT_OTHER\tRESULT\n"), &f), "functionKey 不符不得放行");
    assert!(!audit::allow_matches(&parse("var_unbound\tRESULT\n"), &f), "不做大小写折叠");
    assert!(!audit::allow_matches(&parse("VAR_UNBOUND\tRESUL\n"), &f), "不做前缀匹配");
    assert!(!audit::allow_matches(&parse("KEYWORD_UNBOUND\tRESULT\n"), &f), "kind 不符不得放行");
    assert!(!audit::allow_matches(&[], &f), "空白名单不放行任何东西");

    // 空 functionKey 的读数（COMPILE_DIM_WARNING 那类，TSV 第 3 列是空串）
    let nofunc = audit::Finding {
        kind: "COMPILE_DIM_WARNING".to_string(),
        name: "AUDIT_P6A".to_string(),
        function_key: None,
        detail: String::new(),
        file: "A.ERB".to_string(),
        line: 0,
        occurrences: 1,
    };
    assert!(
        audit::allow_matches(&parse("COMPILE_DIM_WARNING\t\tAUDIT_P6A\n"), &nofunc),
        "3 列行的中间列是空串时，按空 functionKey 的读数匹配（Main.kt:617 的 \"$kind\\t$functionKey\\t$name\"）"
    );

    // 整行 trim、列内不 trim（Main.kt:632-633）：列首空格是匹配串的一部分
    let padded = parse("VAR_UNBOUND\t RESULT\n");
    assert!(!audit::allow_matches(&padded, &f), "列内的前导空格不当作被 trim 掉");
    assert_eq!(padded[0].1, None);
    assert_eq!(padded[0].2, " RESULT");

    // 注释行 / 空行 / CRLF / 坏列数（Main.kt:631-638）
    let messy = parse("\r\n# 注释\n\nVAR_UNBOUND\tX\tY\tZ\nVAR_UNBOUND\tOK\n");
    assert_eq!(messy.len(), 1, "4 列行与注释/空行都不入表");
    assert_eq!(messy[0].2, "OK");
}

#[test]
fn 参数面缺省与报红文案照_main_kt_的_cmd_audit() {
    // Main.kt:440（缺省 out）/ :469（缺省游戏根 = 语料根父目录）/ :456-457（--limit 正整数）
    // / :462（无法识别）/ :450（缺值）/ :466（缺 --corpus）。全部不起 JVM。
    let owned = |args: &[&str]| -> Vec<String> { args.iter().map(|s| (*s).to_string()).collect() };
    let ok = |args: &[&str]| cmd_audit::parse_args(&owned(args)).unwrap_or_else(|e| panic!("{args:?} 应通过：{e:?}"));
    let usage = |args: &[&str]| match cmd_audit::parse_args(&owned(args)) {
        Err(ErbccError::Usage(m)) => m,
        other => panic!("{args:?} 应报 Usage，实为 {other:?}"),
    };

    // 缺省面：只给 --corpus（好档），游戏根应推断成语料根的父目录，out 用 Main.kt:440 的缺省串
    let p = ok(&["--corpus", &corpus().to_string_lossy()]);
    assert_eq!(
        absolutize(&p.game_root),
        absolutize(&game_root()),
        "缺省游戏根 = corpus 的父目录（Main.kt:469）"
    );
    assert_eq!(
        p.out,
        absolutize(Path::new("tools/erbcc/symbol-audit/unresolved-symbols.tsv")),
        "缺省 out（Main.kt:440，相对当前工作目录，与 Kotlin 的 Path(...) 同尺）"
    );
    assert!(!p.out_given, "没传 --out 时 out_given 必须为假（Main.kt:441/476 的 --roundtrip 分支要它）");
    assert!(!p.strict);
    assert!(p.allow.is_none());
    assert!(p.limit.is_none());
    assert!(!p.roundtrip);

    // 显式 --out 时 out_given 为真；--strict 是开关、不吃值（Main.kt:458）
    let q = ok(&[
        "--corpus",
        &corpus().to_string_lossy(),
        "--out",
        &data("expected/full.tsv").to_string_lossy(),
        "--strict",
        "--game-root",
        &game_root().to_string_lossy(),
    ]);
    assert!(q.strict && q.out_given);

    // 报红文案逐字（Kotlin 的 err() 前缀由 cli::finish 统一打，这里比正文）
    assert_eq!(usage(&["--limit", "0", "--corpus", &corpus().to_string_lossy()]), "--limit 需要正整数");
    assert_eq!(usage(&["--limit", "-3", "--corpus", &corpus().to_string_lossy()]), "--limit 需要正整数");
    assert_eq!(usage(&["--limit", "abc", "--corpus", &corpus().to_string_lossy()]), "--limit 需要正整数");
    // Kotlin 的 `toIntOrNull()` 对超出 Int 范围的串同样返回 null ⇒ 同一句文案
    assert_eq!(
        usage(&["--limit", "99999999999999999999", "--corpus", &corpus().to_string_lossy()]),
        "--limit 需要正整数"
    );
    assert_eq!(usage(&["--bogus-flag"]), "无法识别的 audit 参数: --bogus-flag");
    assert_eq!(usage(&["--corpus"]), "--corpus 需要参数");
    assert_eq!(usage(&[]), "缺少 --corpus 参数");
    assert_eq!(usage(&["--game-root"]), "--game-root 需要参数");
    assert_eq!(usage(&["--out"]), "--out 需要参数");
    assert_eq!(usage(&["--allow"]), "--allow 需要参数");
    assert_eq!(usage(&["--artifact-dir"]), "--artifact-dir 需要参数");
    assert_eq!(
        usage(&["--corpus", "this-corpus-dir-does-not-exist"]),
        "corpus 不是目录: this-corpus-dir-does-not-exist",
        "Main.kt:467 打的是**未经绝对化**的原文（WindowsPath.toString 只归一分隔符）"
    );
    // 语料根没有父目录可推断（Windows 上盘根）→ 「无法从 corpus 推断游戏根」这条走不通，
    // 用「父目录存在但没有 CSV」覆盖 :471-473 那一档；:469 的缺档报红留 TODO(verify)。
}

#[test]
fn blocking判据表逐条照_symbol_link_audit_的枚举声明() {
    // SymbolLinkAudit.kt:29-46：8 类，只有 COMPILE_DIM_WARNING 非红。
    assert_eq!(audit::FINDING_KINDS.len(), 8);
    let expect: [(&str, bool); 8] = [
        ("VAR_UNBOUND", true),              // :31
        ("VAR_TYPE_MISMATCH", true),        // :33
        ("VAR_DIM_MISMATCH", true),         // :35
        ("KEYWORD_UNBOUND", true),          // :37
        ("KEYWORD_USER_VAR", true),         // :39
        ("PRIVATE_DIM_REJECTED", true),     // :41
        ("GLOBAL_DIM_REJECTED", true),      // :43
        ("COMPILE_DIM_WARNING", false),     // :45
    ];
    for (got, want) in audit::FINDING_KINDS.iter().zip(expect.iter()) {
        assert_eq!(got.0, want.0, "kind 名/声明序必须照 Kotlin 枚举");
        assert_eq!(got.1, want.1, "{} 的 blocking 位（Main.kt:502 的判据）", want.0);
    }
    // 8 类都必须在 parse_tsv / tsv_text 眼里合法（未知 kind 才报红）
    for (name, _) in audit::FINDING_KINDS.iter() {
        let row = format!("{}\n{name}\tA.ERB\tF\t1\tN\td\t1\n", audit::TSV_HEADER);
        let parsed = audit::parse_tsv(&row).unwrap_or_else(|e| panic!("{name} 应合法：{e:?}"));
        audit::tsv_text(&parsed).unwrap_or_else(|e| panic!("{name} 应能落盘：{e:?}"));
    }
}

#[test]
fn audit的argv把strict与allow透传给kernel() {
    // `kernel::audit_kernel_argv`（append 段）的入参面：`--strict`/`--allow` 不透传的话，
    // kernel 的整段报告文本与本路判据就不是同一口径（`Main.kt:501-526`）。
    let argv = kernel::audit_kernel_argv(
        "cp",
        Path::new("C:/corp"),
        Path::new("C:/game"),
        Path::new("C:/out.tsv"),
        Some(3),
        true,
        Some(Path::new("C:/allow.tsv")),
    );
    let joined: Vec<String> = argv.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    let pos = |s: &str| joined.iter().position(|x| x == s);
    assert_eq!(
        pos("audit"),
        pos(kernel::MAIN_CLASS).map(|i| i + 1),
        "子命令必须紧跟 MAIN_CLASS，实为：{joined:?}"
    );
    assert_eq!(joined[pos("--corpus").unwrap() + 1], "C:/corp");
    assert_eq!(joined[pos("--game-root").unwrap() + 1], "C:/game");
    assert_eq!(joined[pos("--out").unwrap() + 1], "C:/out.tsv");
    assert_eq!(joined[pos("--limit").unwrap() + 1], "3");
    assert!(pos("--strict").is_some(), "--strict 必须透传");
    assert_eq!(joined[pos("--allow").unwrap() + 1], "C:/allow.tsv", "--allow 必须透传");

    // 反向档：不给 limit/strict/allow 时一个都不许多传
    let bare = kernel::audit_kernel_argv("cp", Path::new("c"), Path::new("g"), Path::new("o"), None, false, None);
    let b: Vec<String> = bare.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    for flag in ["--limit", "--strict", "--allow", "--roundtrip", "--artifact-dir"] {
        assert!(!b.iter().any(|x| x == flag), "不该出现 {flag}（--roundtrip 本波不移植）");
    }
}

#[test]
fn kernel的audit腿argv不含roundtrip_且plan走同一套classpath档() {
    // 只验「classpath 三档优先级在 audit 腿同样成立」（§7 硬约束 0：不改第一波的函数）。
    let repo = repo_root();
    let env = |k: &str| -> Option<String> {
        if k == kernel::CLASSPATH_ENV {
            Some("C:/fake/erbcc.jar".to_string())
        } else {
            None
        }
    };
    let plan = kernel::plan_audit(
        &repo,
        Path::new("C:/corp"),
        Path::new("C:/game"),
        Path::new("C:/out.tsv"),
        None,
        false,
        None,
        &env,
    )
    .expect("档 1 直接串应可成计划");
    assert!(plan.classpath_origin.contains("档 1"), "实为：{}", plan.classpath_origin);
    let joined: Vec<String> = plan.argv.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    assert!(joined.iter().any(|x| x == "C:/fake/erbcc.jar"), "-cp 应取环境变量直接串");
    assert!(!joined.iter().any(|x| x == "--roundtrip"), "--roundtrip 不进 argv（本波不移植）");
}
