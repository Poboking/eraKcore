//! **rust-roundtrip 车道的出口闸**：`erbcc audit --roundtrip`（CMP-79）这条子路
//! 「编译 → 落盘 `program.erbc` + `program.meta` → 真装载链读回 → 与内存产物逐字段对账」，
//! 纯 Kotlin 腿与 Rust 编排腿必须给出**同一份对账结论、同一个退出码、同一字节明细**。
//!
//! ## 分工（契约 §0.1/§0.2：判据不重实现）
//!
//! - **判据全在 kernel**：`SymbolLinkAudit.roundTrip`（`SymbolLinkAudit.kt:467-510`）落盘后用
//!   `ProgramLoader.loadFile` + `BootMetadata.read` 重建会话，`compareProduct`（`:564-627`）出
//!   16 项读数（容器语句/指令条数 + 侧车逐字段 + `runtimeGlobals`/`privateVars`/`systemVars`
//!   三读数 + 使用点名字集合哈希）；退出码 0/1 由 `Main.kt:566-574` 判。
//! - **Rust 侧只做三件事**：读回 `RoundTripReport.writeTsv`（`SymbolLinkAudit.kt:148-159`）落的
//!   明细并**逐字节复算**（不等即 `Parity` 红）、独立数「任一项不等」给退出码（`Main.kt:566`）、
//!   与 kernel 的红行 `对账未通过：N 项不等（…）`（`Main.kt:567-570`）交叉核对条数与判据名。
//!
//! ## 两腿都真跑（不手抄基准）
//!
//! - **腿 A**：`java -cp <installDist 的 lib> com.eratw.port.erbcc.MainKt audit … --roundtrip …`
//!   —— classpath 由本测试自己解析，与 `kernel.rs` 的腿不共用一个 bug（口径同
//!   `tests/audit_tsv_parity.rs`）。
//! - **腿 B**：`CARGO_BIN_EXE_erbcc audit … --roundtrip …`（真二进制，走
//!   `cmd_audit::execute` = `kernel::roundtrip_via_jvm` + 复算 + 原子落盘），stdout/stderr
//!   与退出码都从进程取，这样「对账结论文本」也能跨腿比。
//! - **登记基准**：`tests/data/rust-roundtrip/expected/*.tsv` 与 `tamper/*`，全部由**本机真
//!   Kotlin 工具链**产出（2026-11-01）。两腿都等于它 ⇒ 防「两条腿一起漂」。
//!   明细 TSV 五列全是判据名/条数/哈希 ⇒ 与产出机的绝对路径无关，可入库；
//!   唯一的机器/编译器相关量是 `meta.program-sha256` 那一列的值，它系于 `core-vm` 源码树
//!   （改编译器要重生成基准，同 `tests/data/rust-manifest`、`tests/data/rust-kernel` 的既定口径）。
//!   重生成：`python devtools/erbcc_rs_parity.py regen --lane rust-roundtrip`（或 `--lane all`）
//!   一条命令**成套换** —— 它点名跑本文件的 `regen_roundtrip_baseline` 格
//!   （环境变量 `ERBCC_RS_REGEN_ROUNDTRIP=1` 才写盘、`dry` 只打印现跑读数；**未置位时本格直接
//!   跳过，默认闸逐字节行为不变**）。`expected/` 两档与 `tamper/` 三件套 ×3 全部出自**真 Kotlin
//!   腿**（同一份 `leg_kotlin` / `RtVerify`，无手抄）。手工兜底（驱动坏了才用）：
//!   `ERBCC_RS_REGEN_ROUNDTRIP=1 cargo test --test roundtrip_parity -- --exact
//!   regen_roundtrip_baseline --nocapture`（先 export `ERBCC_CLASSPATH` 与 `JAVA_HOME`）；
//!   连这条都没了才照最早的手抄法 —— 跑一遍 `篡改侧车…` 用例，把
//!   `build/roundtrip-gate-tamper-sha-*/kotlin-roundtrip.tsv`、
//!   `…/erbc.v1/program.meta` 与 harness 的 `ok=… mismatches=[…]` 行分别复制回同名三个文件
//!   （三件套必须成套换）。
//!
//! ## 反向证明（闸 3）为什么需要一个 Java 替身入口
//!
//! CLI 的 `--roundtrip` 每次都把产物**重写**到 `--artifact-dir` 再读回
//! （`SymbolLinkAudit.kt:487-495`），所以「从外面改坏产物再对账」这条路 CLI 走不到；
//! Kotlin 侧为这种情况本来就留了另一个入口 `SymbolLinkAudit.verifyArtifacts(corpus, game,
//! erbc, meta, limit)`（`:522-552`，KDoc 原文「对**已经落盘**的产物做同一套对账（红夹具/复现用）」，
//! 与 `roundTrip` 共用同一个 `compareProduct`），它自己也是这么造红夹具的
//! （`SymbolLinkAuditRoundTripTest.kt:136-145`）。本闸用几十行**只调用**（不改 Kotlin 源码、
//! 不碰语义）的 `RtVerify` 跑那个入口，产出真红明细 + 真读数行，再让 Rust 侧对着
//! 同一份字节独立复算判据名 —— 两侧红在同一个判据上才算绿。
//!
//! ## 语料
//!
//! 小语料 `tests/data/rust-audit/game/ERB`（8 文件，rust-audit 车道的闸语料，只读复用）：
//! 有全局 `#DIM CONST`、私有 `#DIM`、`$` 标签与解析失败告警 ⇒ 侧车四类信息齐全，
//! 16 项读数不是「两边都是 0」的空绿。坏档用 `tests/data/rust-audit/bad-game`。
//! 全量语料（`$ERATW_CORPUS`）不进本闸（一趟 ≈30 s + 峰值堆 3.4 GiB + ~370 MiB 产物），
//! 只做一次性人工读数，见交付报告。

use erbcc_rs::audit;
use erbcc_rs::cmd_audit;
use erbcc_rs::kernel::{self, KernelOutput};
use erbcc_rs::manifest;
use erbcc_rs::types::{absolutize, sha256_hex, ErbccError, Outcome};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&manifest_dir())
        .expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-roundtrip").join(rel)
}

/// rust-audit 车道的小语料（只读复用）：游戏根含 `CSV/`，`Main.kt:471-473` 的前置校验要它。
fn game_root() -> PathBuf {
    manifest_dir().join("tests/data/rust-audit/game")
}

fn corpus() -> PathBuf {
    game_root().join("ERB")
}

fn bad_game_root() -> PathBuf {
    manifest_dir().join("tests/data/rust-audit/bad-game")
}

fn bad_corpus() -> PathBuf {
    bad_game_root().join("ERB")
}

fn gate_out(tag: &str) -> PathBuf {
    let dir = manifest_dir().join(format!("build/roundtrip-gate-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("建 roundtrip 闸输出目录");
    dir
}

// ---------------------------------------------------------------------------
// 两腿的启动器
// ---------------------------------------------------------------------------

/// 一次真腿的读数（rc + 两路文本）。
struct Leg {
    rc: i32,
    stdout: String,
    stderr: String,
}

impl Leg {
    /// stderr 里第一条红行（`对账失败：…` 或 `对账未通过：…`，`Main.kt:550/567`）。
    fn red_line(&self) -> String {
        self.stderr
            .lines()
            .find(|l| l.starts_with("对账失败：") || l.starts_with("对账未通过："))
            .unwrap_or_else(|| panic!("腿的 stderr 里没有红行，实为：\n{}", self.stderr))
            .to_string()
    }
}

/// 本测试自己解析 classpath / java（不走 `kernel.rs`，两条腿不许共用一个 bug）。
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

/// 公共实参（两腿同一份，路径都绝对化 —— 腿 B 的 CLI 面自己会再 absolutize 一次）。
fn common_args(corpus: &Path, game: &Path, out_tsv: &Path, artifact_dir: Option<&Path>, extra: &[&str]) -> Vec<String> {
    let mut v = vec![
        "audit".to_string(),
        "--corpus".to_string(),
        absolutize(corpus).to_string_lossy().into_owned(),
        "--game-root".to_string(),
        absolutize(game).to_string_lossy().into_owned(),
        "--out".to_string(),
        absolutize(out_tsv).to_string_lossy().into_owned(),
        "--roundtrip".to_string(),
    ];
    if let Some(dir) = artifact_dir {
        v.push("--artifact-dir".to_string());
        v.push(absolutize(dir).to_string_lossy().into_owned());
    }
    for e in extra {
        v.push((*e).to_string());
    }
    v
}

/// 腿 A：纯 Kotlin 工具链。
fn leg_kotlin(corpus: &Path, game: &Path, out_tsv: &Path, artifact_dir: Option<&Path>, extra: &[&str]) -> Leg {
    let args = common_args(corpus, game, out_tsv, artifact_dir, extra);
    let mut cmd = std::process::Command::new(java_exe());
    cmd.args(["-Xmx6g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(kotlin_tool_classpath())
        .arg("com.eratw.port.erbcc.MainKt")
        .args(&args)
        .current_dir(repo_root());
    let started = cmd.output().expect("起纯 Kotlin 工具链失败（腿 A）：需要 JAVA_HOME + installDist 产物");
    Leg {
        rc: started.status.code().unwrap_or(-1),
        stdout: String::from_utf8_lossy(&started.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&started.stderr).into_owned(),
    }
}

/// 腿 B：Rust 侧的**真二进制**（`src/main.rs` → `cli::run` → `cmd_audit::execute`）。
fn leg_rust(corpus: &Path, game: &Path, out_tsv: &Path, artifact_dir: Option<&Path>, extra: &[&str]) -> Leg {
    let args = common_args(corpus, game, out_tsv, artifact_dir, extra);
    let started = std::process::Command::new(env!("CARGO_BIN_EXE_erbcc"))
        .args(&args)
        .current_dir(manifest_dir())
        .output()
        .expect("跑真 erbcc 二进制失败（腿 B）");
    Leg {
        rc: started.status.code().unwrap_or(-1),
        stdout: String::from_utf8_lossy(&started.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&started.stderr).into_owned(),
    }
}

fn read_bytes(path: &Path) -> Vec<u8> {
    std::fs::read(path).unwrap_or_else(|e| panic!("读不到 {path:?}：{e}"))
}

fn maybe_bytes(path: &Path) -> Option<Vec<u8>> {
    std::fs::read(path).ok()
}

fn digest_line(label: &str, path: &Path) -> String {
    let b = read_bytes(path);
    format!("{label} 字节 {} sha256 {}", b.len(), sha256_hex(&b))
}

/// 报告块里**与机器/进程相关**的行：产物/侧车/明细的绝对路径与耗时毫秒。
/// 剔成占位符后才能比「对账结论」本身（行结构 = `Main.kt:553-573`）。
fn mask_report(text: &str) -> String {
    let mut out = String::new();
    for line in text.lines() {
        let head = ["产物：", "侧车：", "明细："];
        if let Some(p) = head.iter().find(|p| line.starts_with(*p)) {
            // `明细：<绝对路径>（16 行）` 保留尾读的条数，只换路径
            let tail = match line.strip_prefix(p) {
                Some(rest) if p == &"明细：" => rest.rfind('（').map(|i| &rest[i..]).unwrap_or_default(),
                _ => "",
            };
            out.push_str(p);
            out.push_str("<路径>");
            out.push_str(tail);
            out.push('\n');
            continue;
        }
        let mut l = line.to_string();
        if let Some(pos) = l.find('（') {
            if let Some(end) = l[pos..].find(" ms）") {
                l = format!("{}<ms>{}", &l[..pos], &l[pos..][end..]);
            }
        }
        out.push_str(&l);
        out.push('\n');
    }
    out
}

/// 报告块里某行的原文（`Main.kt:554/555/565`）。
fn report_path(text: &str, prefix: &str) -> String {
    text.lines()
        .find(|l| l.starts_with(prefix))
        .unwrap_or_else(|| panic!("报告块缺 `{prefix}` 行：\n{text}"))
        .replacen(prefix, "", 1)
        .trim()
        .to_string()
}

fn parse_detail(path: &Path) -> Vec<audit::RoundTripCheck> {
    let text = String::from_utf8(read_bytes(path)).expect("明细应是 UTF-8（SymbolLinkAudit.kt:158）");
    audit::parse_roundtrip_tsv(&text).expect("解析明细")
}

// ---------------------------------------------------------------------------
// 1. 同一份语料：对账结论 / 退出码 / 明细 TSV 逐项一致（闸 1）+ artifact-dir（闸 2）
// ---------------------------------------------------------------------------

#[test]
fn 同一语料_两侧明细逐字节相同_报告同文_退出码都是0() {
    let dir_a = gate_out("green-a");
    let dir_b = gate_out("green-b");
    let tsv_a = dir_a.join("roundtrip.tsv");
    let tsv_b = dir_b.join("roundtrip.tsv");

    let a = leg_kotlin(&corpus(), &game_root(), &tsv_a, Some(&dir_a), &[]);
    assert_eq!(a.rc, 0, "腿 A 应退 0（对账全绿，Main.kt:574）\nstdout:\n{}\nstderr:\n{}", a.stdout, a.stderr);
    let b = leg_rust(&corpus(), &game_root(), &tsv_b, Some(&dir_b), &[]);
    assert_eq!(b.rc, 0, "腿 B：同一判据必须同样退 0\nstdout:\n{}\nstderr:\n{}", b.stdout, b.stderr);

    // ---- 明细 TSV：两侧逐字节 == 登记基准 ----
    let bytes_a = read_bytes(&tsv_a);
    let bytes_b = read_bytes(&tsv_b);
    assert_eq!(
        bytes_a, bytes_b,
        "对账明细两侧不等（腿 A vs 腿 B）\n{}",
        first_text_diff(&String::from_utf8_lossy(&bytes_a), &String::from_utf8_lossy(&bytes_b))
    );
    let baseline = read_bytes(&data("expected/green.tsv"));
    assert_eq!(bytes_a, baseline, "腿 A 漂了：与 tests/data/rust-roundtrip/expected/green.tsv 不等");
    assert_eq!(bytes_b, baseline, "腿 B 漂了：与 tests/data/rust-roundtrip/expected/green.tsv 不等");
    println!("{}", digest_line("腿 A roundtrip.tsv", &tsv_a));
    println!("{}", digest_line("腿 B roundtrip.tsv", &tsv_b));
    println!("{}", digest_line("登记基准 expected/green.tsv", &data("expected/green.tsv")));

    // ---- 对账结论的文本：剔掉路径/耗时后逐行同文 ----
    assert_eq!(
        mask_report(&a.stdout),
        mask_report(&b.stdout),
        "报告块两侧不等（绝对路径与 ms 已剔）\n{}",
        first_text_diff(&mask_report(&a.stdout), &mask_report(&b.stdout))
    );
    let masked = mask_report(&a.stdout);
    assert!(masked.contains("=== 落盘后再装载对账（CMP-79）==="), "报告块首行（Main.kt:553）");
    assert!(masked.contains("结论：PASS（内存产物与落盘再装载逐字段一致）"), "绿档结论行（Main.kt:573）");
    assert_eq!(masked.lines().filter(|l| l.starts_with("  OK    ")).count(), 16, "16 项全 OK");
    assert!(!masked.contains("DIFF"), "绿档报告里不该有 DIFF 行");

    // ---- 判据面：16 项、全 OK，且三读数不是「两边都 0」的空绿 ----
    let checks = parse_detail(&tsv_b);
    assert_eq!(audit::roundtrip_check_count(&checks), 16, "小语料档应有 16 项读数");
    assert!(audit::roundtrip_mismatches(&checks).is_empty(), "绿档不该有 DIFF 项");
    for name in [
        "program.statements",
        "program.instructions",
        "meta.functions",
        "meta.labels",
        "meta.files",
        "meta.data-sections",
        "meta.global-dims",
        "meta.private-dims",
        "meta.dim-config",
        "meta.program-sha256",
        "runtime.globals",
        "runtime.private-vars",
        "runtime.system-vars",
        "runtime.private-dim-failures",
        "runtime.global-dim-failures",
        "usages.names",
    ] {
        let c = checks.iter().find(|c| c.name == name).unwrap_or_else(|| panic!("缺判据 {name}"));
        assert_eq!(c.in_memory, c.reloaded, "{name} 两侧读数必须相等（绿档）");
        assert!(!c.in_memory.is_empty() || name == "usages.names", "{name} 的读数不该是空串");
        assert!(c.equal, "{name} 应 OK");
    }
    let cell = |n: &str| -> String {
        checks.iter().find(|c| c.name == n).unwrap().in_memory.clone()
    };
    assert_eq!(cell("runtime.private-vars"), "4", "小语料的 4 条私有 #DIM 都进了运行期");
    assert_eq!(cell("runtime.system-vars"), "158", "系统变量表真的装起来了（不是空会话）");
    assert_eq!(cell("meta.private-dims"), "4 条", "侧车模型那条私有 #DIM 有 4 条");
    assert_eq!(cell("meta.program-sha256").len(), 64, "侧车头部摘要 = 容器 SHA-256（真装载链读到的）");
    assert!(cell("usages.names").starts_with("19 个名字 sha256="), "名字集合读数：{}", cell("usages.names"));

    // ---- 闸 2 前半：给了 --artifact-dir 就用给定目录，且两腿的产物四件套逐字节相同 ----
    for dir in [&dir_a, &dir_b] {
        for f in ["program.erbc", "program.meta", "program.stmt.idx", "manifest.txt"] {
            assert!(
                dir.join("erbc.v1").join(f).is_file(),
                "{dir:?} 下缺产物 {f}（写侧没落盘；产物目录由 ErbcArtifacts.kt:140 定名）"
            );
        }
    }
    for f in ["program.erbc", "program.meta", "program.stmt.idx", "manifest.txt"] {
        assert_eq!(
            read_bytes(&dir_a.join("erbc.v1").join(f)),
            read_bytes(&dir_b.join("erbc.v1").join(f)),
            "两腿的产物 {f} 不等 ⇒ 对账的输入就不是同一份"
        );
    }
    // 产物目录**必须保留**（给定时不是临时盘，`SymbolLinkAudit.kt:484` 的 temporary 为假）
    assert!(dir_a.is_dir() && dir_b.is_dir(), "给定 --artifact-dir 时目录要留着供复现");
}

#[test]
fn limit档_两侧明细逐字节相同且降级警告只上一遍() {
    let dir_a = gate_out("limit-a");
    let dir_b = gate_out("limit-b");
    let tsv_a = dir_a.join("roundtrip.tsv");
    let tsv_b = dir_b.join("roundtrip.tsv");

    let a = leg_kotlin(&corpus(), &game_root(), &tsv_a, Some(&dir_a), &["--limit", "3"]);
    assert_eq!(a.rc, 0, "腿 A（--limit 3）应退 0\nstderr:\n{}", a.stderr);
    let b = leg_rust(&corpus(), &game_root(), &tsv_b, Some(&dir_b), &["--limit", "3"]);
    assert_eq!(b.rc, 0, "腿 B（--limit 3）应退 0\nstderr:\n{}", b.stderr);

    let bytes_a = read_bytes(&tsv_a);
    assert_eq!(bytes_a, read_bytes(&tsv_b), "--limit 3 档两侧明细不等");
    assert_eq!(bytes_a, read_bytes(&data("expected/green-limit3.tsv")), "--limit 3 档与登记基准不等");
    println!("{}", digest_line("腿 A limit3 roundtrip.tsv", &tsv_a));

    // --limit 真透传到了 kernel：切片档把 ERH 的全局 #DIM 切在外面（3 条 → 0 条）。
    // 这条读数变化是「参数没被本路吞掉」的判据，而不是靠比 argv。
    let checks = parse_detail(&tsv_a);
    let gdim = checks.iter().find(|c| c.name == "meta.global-dims").unwrap();
    assert_eq!(gdim.in_memory, "0 条", "--limit 3 应把全局 #DIM 切在外面：{gdim:?}");
    let full = parse_detail(&data("expected/green.tsv"));
    assert_eq!(
        full.iter().find(|c| c.name == "meta.global-dims").unwrap().in_memory,
        "3 条",
        "全量档该有 3 条全局 #DIM（对照档）"
    );

    // 降级警告：Kotlin 在分派**之前**打一次（Main.kt:474），kernel 子进程会再打一次 ⇒
    // 本路要滤掉 kernel 那一份；而带缩进的 `降级模式：只切片前 3/8 个文件` 是另一条读数，
    // 必须照常透出（口径同 `cmd_audit::is_duplicate_limit_line`）。
    for (tag, leg) in [("A", &a), ("B", &b)] {
        assert_eq!(
            leg.stderr
                .lines()
                .filter(|l| *l == "降级模式：只审计前 3 个文件（正式闸必须全量）")
                .count(),
            1,
            "腿 {tag}：降级警告整条链上只该上屏一遍，实为：\n{}",
            leg.stderr
        );
        assert!(
            leg.stderr.contains("  降级模式：只切片前 3/8 个文件"),
            "腿 {tag}：kernel 的切片留痕行（带缩进）必须照常透出：\n{}",
            leg.stderr
        );
    }
}

#[test]
fn 缺省artifact_dir走临时目录_绿了自删_两侧一致() {
    // USAGE（Main.kt:93）：`--artifact-dir` 缺省 = 临时目录，**绿了自删**、红了保留路径供复现。
    // 删除动作在 kernel 里（`SymbolLinkAudit.kt:484-485/508`：`temporary && report.ok` 才删）⇒
    // 本路不另起临时盘，只透传「没给目录」这件事，两侧行为必须一致。
    let tsv_a = gate_out("temp-a").join("rt.tsv");
    let tsv_b = gate_out("temp-b").join("rt.tsv");
    let a = leg_kotlin(&corpus(), &game_root(), &tsv_a, None, &[]);
    let b = leg_rust(&corpus(), &game_root(), &tsv_b, None, &[]);
    for (tag, leg, tsv) in [("A", &a, &tsv_a), ("B", &b, &tsv_b)] {
        assert_eq!(leg.rc, 0, "腿 {tag}：缺省 artifact-dir 的对账应退 0\nstderr:\n{}", leg.stderr);
        let artifact = report_path(&leg.stdout, "产物：");
        assert!(
            artifact.contains("erbcc-roundtrip-"),
            "腿 {tag}：缺省产物目录应是 kernel 自己开的临时目录（erbcc-roundtrip-*，\
             SymbolLinkAudit.kt:485），实为 {artifact}"
        );
        assert!(!Path::new(&artifact).exists(), "腿 {tag}：绿档之后临时产物目录必须已自删：{artifact}");
        assert!(tsv.is_file(), "腿 {tag}：明细 TSV 仍要落盘（它不在临时目录里）");
        // 临时目录整棵都要消失（deleteRecursively，:508）
        let parent = Path::new(&artifact).parent().and_then(|p| p.parent());
        if let Some(p) = parent {
            assert!(!p.exists(), "腿 {tag}：临时目录 {p:?} 没被删干净");
        }
    }
    assert_eq!(read_bytes(&tsv_a), read_bytes(&tsv_b), "缺省 artifact-dir 档两侧明细也要逐字节相同");
}

// ---------------------------------------------------------------------------
// 2. 反向证明（闸 3）：读侧被人为改坏 ⇒ 两侧都红，且红在同一个判据
// ---------------------------------------------------------------------------

/// `RtVerify`：几十行、纯 ASCII、**只调用**不改 Kotlin 源码的 harness，跑真读侧入口
/// `SymbolLinkAudit.verifyArtifacts` + 真明细写出层 `RoundTripReport.writeTsv`。
const RT_VERIFY_SRC: &str = r#"import com.eratw.port.erbcc.audit.RoundTripCheck;
import com.eratw.port.erbcc.audit.RoundTripReport;
import com.eratw.port.erbcc.audit.SymbolLinkAudit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class RtVerify {
    public static void main(String[] argv) throws Exception {
        Path corpus = Paths.get(argv[0]);
        Path game = Paths.get(argv[1]);
        Path erbc = Paths.get(argv[2]);
        Path meta = Paths.get(argv[3]);
        Path tsv = Paths.get(argv[4]);
        try {
            RoundTripReport r = SymbolLinkAudit.INSTANCE.verifyArtifacts(corpus, game, erbc, meta, null);
            r.writeTsv(tsv);
            List<String> names = new ArrayList<>();
            for (RoundTripCheck c : r.getMismatches()) names.add(c.getName());
            System.out.println("ok=" + r.getOk() + " checks=" + r.getChecks().size() + " mismatches=" + names);
        } catch (Throwable e) {
            System.out.println("THREW " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
        }
    }
}
"#;

/// 编一次 harness（进程内共享，装在 `build/roundtrip-harness/classes`）。
fn harness_classes() -> PathBuf {
    static CLASSES: OnceLock<PathBuf> = OnceLock::new();
    CLASSES
        .get_or_init(|| {
            let scratch = manifest_dir().join("build/roundtrip-harness");
            std::fs::create_dir_all(&scratch).expect("建 harness 目录");
            let src = scratch.join("RtVerify.java");
            std::fs::write(&src, RT_VERIFY_SRC).expect("写 harness 源");
            let javac = java_exe()
                .parent()
                .unwrap()
                .join(format!("javac{}", std::env::consts::EXE_SUFFIX));
            assert!(
                javac.is_file(),
                "反向证明需要 JDK 的 javac（{javac:?}）：JAVA_HOME 要指到一个完整 JDK，\
                 不是 JRE（AGENTS.md §5 的工具链入口）"
            );
            let compiled = std::process::Command::new(&javac)
                .args(["-encoding", "UTF-8", "-cp", &kotlin_tool_classpath(), "-d"])
                .arg(scratch.join("classes"))
                .arg(&src)
                .output()
                .expect("编 RtVerify 失败");
            assert!(
                compiled.status.success(),
                "编 RtVerify 失败：{}",
                String::from_utf8_lossy(&compiled.stderr)
            );
            scratch.join("classes")
        })
        .clone()
}

/// 一次真读侧调用。`verdict` 形如
/// `ok=false checks=16 mismatches=[meta.program-sha256]`，或 `THREW <类名>: <消息>`；
/// `detail` = kernel 落的明细（抛了就没有）。
struct KotlinVerify {
    verdict: String,
    detail: Option<Vec<u8>>,
}

fn kotlin_verify_artifacts(erbc: &Path, meta: &Path, out_tsv: &Path) -> KotlinVerify {
    let cp = format!(
        "{}{}{}",
        harness_classes().display(),
        if cfg!(windows) { ";" } else { ":" },
        kotlin_tool_classpath()
    );
    let run = std::process::Command::new(java_exe())
        .args(["-Xmx2g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(&cp)
        .arg("RtVerify")
        .args([
            absolutize(&corpus()),
            absolutize(&game_root()),
            absolutize(erbc),
            absolutize(meta),
            absolutize(out_tsv),
        ])
        .current_dir(repo_root())
        .output()
        .expect("跑 RtVerify 失败");
    let text = String::from_utf8_lossy(&run.stdout).into_owned();
    let verdict = text
        .lines()
        .rev()
        .find(|l| l.starts_with("ok=") || l.starts_with("THREW "))
        .unwrap_or_else(|| panic!("harness 没给出读数行：\nSTDOUT\n{text}\nSTDERR\n{}", String::from_utf8_lossy(&run.stderr)))
        .to_string();
    KotlinVerify {
        verdict,
        detail: maybe_bytes(out_tsv),
    }
}

/// 一份干净的产物对（`program.erbc` + `program.meta`），由**真 Kotlin 绿档对账**产出，
/// 进程内共享一份；每个反向证明用例把它复制到自己的目录再改坏。
fn pristine_artifacts() -> (PathBuf, PathBuf) {
    static PAIR: OnceLock<(PathBuf, PathBuf)> = OnceLock::new();
    PAIR.get_or_init(|| {
        let producer = gate_out("producer");
        let leg = leg_kotlin(&corpus(), &game_root(), &producer.join("rt.tsv"), Some(&producer), &[]);
        assert_eq!(
            leg.rc,
            0,
            "造反向证明的基线产物要先来一次绿档对账：\nstdout:\n{}\nstderr:\n{}",
            leg.stdout,
            leg.stderr
        );
        (
            producer.join("erbc.v1/program.erbc"),
            producer.join("erbc.v1/program.meta"),
        )
    })
    .clone()
}

/// 把干净产物复制到 `dst/erbc.v1/`，返回（erbc, meta）。
fn copied_artifacts(dst: &Path) -> (PathBuf, PathBuf) {
    let (src_erbc, src_meta) = pristine_artifacts();
    std::fs::create_dir_all(dst.join("erbc.v1")).expect("建产物副本目录");
    let erbc = dst.join("erbc.v1/program.erbc");
    let meta = dst.join("erbc.v1/program.meta");
    std::fs::copy(&src_erbc, &erbc).expect("复制 program.erbc");
    std::fs::copy(&src_meta, &meta).expect("复制 program.meta");
    (erbc, meta)
}

/// 把侧车里 `program-sha256 "…"` 的**第一个十六进制字符**换掉 ⇒ 读侧摘要与写侧容器不同尺。
/// 不做「整串替换」也不做重新计算：改一个字符就够让 `meta.program-sha256` 那条判据不等
/// （`SymbolLinkAudit.kt:712-718`），而其余 15 项照旧 —— 这才叫「红在同一判据上」。
fn flip_program_sha(meta: &Path) {
    let text = String::from_utf8(read_bytes(meta)).expect("program.meta 是 UTF-8");
    let mut done = false;
    let mut out = String::new();
    for line in text.lines() {
        if !done && line.starts_with("program-sha256 \"") {
            let flipped = flip_first_hex_char(line);
            assert_ne!(flipped, line, "夹具自检：必须改到 program-sha256 行：{line}");
            out.push_str(&flipped);
            done = true;
        } else {
            out.push_str(line);
        }
        out.push('\n');
    }
    assert!(done, "夹具自检：侧车里必须有 program-sha256 行");
    std::fs::write(meta, out).expect("写回篡改侧车");
}

/// 十六进制串里第一个字符换成「另一个十六进制字符」（`0`→`1`，其余减一）。
fn flip_first_hex_char(line: &str) -> String {
    let head = "program-sha256 \"";
    let digest_start = match line.strip_prefix(head) {
        Some(rest) => head.len() + rest.find(|c: char| c.is_ascii_hexdigit()).unwrap_or(0),
        None => return line.to_string(),
    };
    let b = line.as_bytes()[digest_start];
    let replaced: u8 = if b == b'0' { b'1' } else { b - 1 };
    let mut bytes = line.to_string().into_bytes();
    bytes[digest_start] = replaced;
    String::from_utf8(bytes).expect("十六进制行改一个字符后仍是 UTF-8")
}

/// 删掉**最后**一条 `private-dim` 记录并把 `private-dims` 计数减一（复刻 Kotlin 红夹具 #2）。
///
/// 必须是最后一条：`firstListDiff`（`SymbolLinkAudit.kt:826-836`）先找「同序号上的首差」，
/// 删中间/开头那条会让后面全部左移 ⇒ detail 变成 `首差 #0: …`（一长串 privateDimKey），
/// 而登记基准是删最后一条得到的 `条数不同：内存 4 / 装载 3`。两种都红，但明细字节不同尺。
fn drop_one_private_dim(meta: &Path) {
    let text = String::from_utf8(read_bytes(meta)).expect("program.meta 是 UTF-8");
    let lines: Vec<&str> = text.lines().collect();
    let last = lines
        .iter()
        .rposition(|l| l.starts_with("private-dim "))
        .expect("夹具自检：侧车必须含 private-dim 记录");
    let mut out: Vec<String> = Vec::new();
    for (idx, line) in lines.iter().enumerate() {
        if idx == last {
            continue;
        }
        if let Some(n) = line.strip_prefix("private-dims ") {
            let declared: i32 = n.trim().parse().expect("private-dims 计数应是整数");
            assert!(declared > 0, "夹具自检：私有 #DIM 计数应 > 0");
            out.push(format!("private-dims {}", declared - 1));
            continue;
        }
        out.push(line.to_string());
    }
    std::fs::write(meta, out.join("\n") + "\n").expect("写回篡改侧车");
}

/// 截断容器尾部 16 字节（段表声明的字节数与区间实际长度不符）。
fn truncate_erbc(erbc: &Path) {
    let bytes = read_bytes(erbc);
    assert!(bytes.len() > 64, "夹具自检：容器不该这么小");
    std::fs::write(erbc, &bytes[..bytes.len() - 16]).expect("写截断容器");
}

/// 解析 harness 读数里的 `mismatches=[a, b]` ⇒ 判据名表（Kotlin 的顺序 = checks 顺序）。
fn harness_mismatch_names(verdict: &str) -> Vec<String> {
    let inner = verdict
        .split_once("mismatches=[")
        .unwrap_or_else(|| panic!("读数形状不对（不是 ok= 档）：{verdict}"))
        .1
        .trim_end_matches(']')
        .to_string();
    if inner.trim().is_empty() {
        return Vec::new();
    }
    inner.split(", ").map(|s| s.trim().to_string()).collect()
}

/// 读一份登记基准的**文本**（剔行尾终止符、按解码后的 UTF-8 比 —— 已定口径）。
fn expect_text(rel: &str) -> String {
    let raw = String::from_utf8(read_bytes(&data(rel))).expect("登记基准应是 UTF-8");
    raw.lines().collect::<Vec<_>>().join("\n")
}

#[test]
fn 篡改侧车program_sha256_两侧红在同一个判据() {
    let dir = gate_out("tamper-sha");
    let (erbc, meta) = copied_artifacts(&dir);
    flip_program_sha(&meta);

    // Kotlin 侧：真 verifyArtifacts + 真 RoundTripReport.writeTsv
    let kt_tsv = dir.join("kotlin-roundtrip.tsv");
    let kv = kotlin_verify_artifacts(&erbc, &meta, &kt_tsv);
    assert!(kv.verdict.starts_with("ok=false"), "Kotlin 读侧必须红，实为：{}", kv.verdict);
    let kt_names = harness_mismatch_names(&kv.verdict);
    assert_eq!(kt_names, vec!["meta.program-sha256".to_string()], "Kotlin 侧红在这一个判据上（SymbolLinkAudit.kt:712-718）：{}", kv.verdict);

    // Rust 侧：对着**同一份真红明细**独立复算
    let bytes = kv.detail.expect("红档也该落明细");
    let text = String::from_utf8(bytes.clone()).expect("明细应 UTF-8");
    let checks = audit::parse_roundtrip_tsv(&text).expect("Rust 侧读真红明细");
    assert!(audit::roundtrip_is_red(&checks), "Rust 侧必须判红");
    assert_eq!(audit::roundtrip_mismatch_names(&checks), kt_names, "两侧必须红在**同一个判据**（含序）");
    assert_eq!(
        audit::roundtrip_tsv_text(&checks).unwrap().as_bytes(),
        bytes.as_slice(),
        "真红档的明细复算不等：\n{}",
        first_text_diff(&text, &audit::roundtrip_tsv_text(&checks).unwrap())
    );
    // 编排层：kernel 退 1 + 红行 ⇒ 本路退 1
    let code = fake_orchestrate(1, &red_line_from(&checks, &dir), Some(bytes.clone()), &dir);
    assert_eq!(code, 1, "Rust 编排必须跟着红（Main.kt:566-571）");

    // 登记基准（篡改侧车本体 + 真红明细 + Kotlin 读数行）必须还等于今天产出的字节
    assert_eq!(
        bytes,
        read_bytes(&data("tamper/meta-program-sha256-changed.tsv")),
        "篡改档明细与登记基准不等（改了 core-vm 就重生成基准）"
    );
    assert_eq!(
        read_bytes(&meta),
        read_bytes(&data("tamper/meta-program-sha256-changed.program.meta")),
        "篡改侧车本体与登记基准不等"
    );
    assert_eq!(
        kv.verdict.trim(),
        expect_text("tamper/meta-program-sha256-changed.expect.txt").trim(),
        "Kotlin 读数行与登记基准不等"
    );
    println!("{}", digest_line("篡改 program-sha256 的明细", &kt_tsv));
}

#[test]
fn 删一条私有dim_两侧红在meta点private_dims与runtime点private_vars() {
    let dir = gate_out("tamper-priv");
    let (erbc, meta) = copied_artifacts(&dir);
    drop_one_private_dim(&meta);

    let kt_tsv = dir.join("kotlin-roundtrip.tsv");
    let kv = kotlin_verify_artifacts(&erbc, &meta, &kt_tsv);
    assert!(kv.verdict.starts_with("ok=false"), "少一条私有 #DIM 必须红：{}", kv.verdict);
    let kt_names = harness_mismatch_names(&kv.verdict);
    assert!(kt_names.contains(&"meta.private-dims".to_string()), "侧车模型那条要红：{kt_names:?}");
    assert!(
        kt_names.contains(&"runtime.private-vars".to_string()),
        "运行期注册数也要红（真装载链少一条声明）：{kt_names:?}"
    );

    let bytes = kv.detail.expect("红档也该落明细");
    let checks = audit::parse_roundtrip_tsv(&String::from_utf8(bytes.clone()).unwrap()).unwrap();
    assert_eq!(audit::roundtrip_mismatch_names(&checks), kt_names, "两侧判据名必须逐项相同（含序）");
    assert_eq!(
        audit::roundtrip_tsv_text(&checks).unwrap().as_bytes(),
        bytes.as_slice(),
        "真红明细复算不等"
    );
    assert_eq!(fake_orchestrate(1, &red_line_from(&checks, &dir), Some(bytes.clone()), &dir), 1);
    assert_eq!(bytes, read_bytes(&data("tamper/meta-private-dims-dropped.tsv")), "篡改档明细与登记基准不等");
    assert_eq!(
        read_bytes(&meta),
        read_bytes(&data("tamper/meta-private-dims-dropped.program.meta")),
        "篡改侧车本体与登记基准不等"
    );
    println!("{}", digest_line("少一条私有 #DIM 的明细", &kt_tsv));
}

#[test]
fn 截断容器_读侧必须抛且编排层不许退0() {
    // 截断 program.erbc ⇒ 真装载链连容器都解不开（ErbcDecodeException），走不到出读数那一步。
    // Kotlin 侧是「抛」，Rust 侧必须「报红」，不许静默退 0、更不许凭空复算出一份绿明细。
    let dir = gate_out("tamper-truncate");
    let (erbc, meta) = copied_artifacts(&dir);
    truncate_erbc(&erbc);

    let kt_tsv = dir.join("kotlin-roundtrip.tsv");
    let _ = std::fs::remove_file(&kt_tsv);
    let kv = kotlin_verify_artifacts(&erbc, &meta, &kt_tsv);
    assert!(kv.verdict.starts_with("THREW "), "截断后 Kotlin 读侧必须抛，实为：{}", kv.verdict);
    assert!(kv.verdict.contains("ErbcDecodeException"), "要抛在真装载链的解码上，实为：{}", kv.verdict);
    assert!(kv.detail.is_none(), "抛了就不该有明细 —— 有明细说明「红」被糊成了读数");
    assert_eq!(
        kv.verdict.trim(),
        expect_text("tamper/truncated-erbc.expect.txt").trim(),
        "截断档的 Kotlin 读数与登记基准不等"
    );

    // Rust 编排的同一档：kernel 退 1、既没「对账失败」也没「对账未通过」（异常穿透了
    // `Main.kt:549` 的 IllegalStateException 单 catch）、明细也不存在 ⇒ 必须报红。
    let err = fake_orchestrate_err(1, "", None, &dir).expect_err("截断档必须报红，不许退 0");
    match err {
        ErbccError::Io(m) => {
            assert!(m.contains("对账失败") && m.contains("对账未通过"), "文案要点名两条红行都没有，实为：{m}");
            assert!(m.contains("明细") || m.contains("异常"), "文案要指出读侧没落明细，实为：{m}");
        }
        other => panic!("应报 Io，实为 {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// 3. 真 CLI 可达的另一条红：语料编译失败（两侧 rc=1、红行逐字同、不落盘）
// ---------------------------------------------------------------------------

#[test]
fn 真cli坏档_两侧退1且对账失败文案逐字相同_不落明细也不落产物() {
    // `SymbolLinkAudit.kt:478-483`：编译失败 ⇒ 抛 IllegalStateException；
    // `Main.kt:549-552` 打一行 `对账失败：…` 就 return 1 —— 明细与产物都不落（USAGE :95）。
    let dir_a = gate_out("bad-a");
    let dir_b = gate_out("bad-b");
    let tsv_a = dir_a.join("rt.tsv");
    let tsv_b = dir_b.join("rt.tsv");
    let a = leg_kotlin(&bad_corpus(), &bad_game_root(), &tsv_a, Some(&dir_a), &[]);
    let b = leg_rust(&bad_corpus(), &bad_game_root(), &tsv_b, Some(&dir_b), &[]);
    assert_eq!(a.rc, 1, "腿 A 坏档应退 1\nstderr:\n{}", a.stderr);
    assert_eq!(b.rc, 1, "腿 B 坏档必须同样退 1\nstderr:\n{}", b.stderr);

    // 两侧的红行：剔掉「产物保留在 <路径>」尾段后必须逐字相同（那是同一句 kernel 文案）
    let strip = |l: &str| l.split("；产物保留在").next().unwrap_or(l).trim_end().to_string();
    let (la, lb) = (a.red_line(), b.red_line());
    assert_eq!(strip(&la), strip(&lb), "两侧红行剔路径后必须逐字相同\nA = {la}\nB = {lb}");
    assert!(la.contains("对账失败：编译失败（2 条）"), "红行要点名编译失败条数，实为：{la}");
    assert!(la.contains("B1_POISON.ERB"), "红行要点名同一处坏文件，实为：{la}");
    assert_eq!(
        a.stdout, b.stdout,
        "编译失败档两侧 stdout 都该是空的（报告块在异常之后才打）：\nA={}\nB={}",
        a.stdout, b.stdout
    );

    assert!(!tsv_a.exists() && !tsv_b.exists(), "编译失败档两侧都不落明细");
    for dir in [&dir_a, &dir_b] {
        assert!(
            !dir.join("erbc.v1").exists(),
            "编译失败档两侧都不该落产物（USAGE Main.kt:95「不落盘」）：{dir:?}"
        );
    }
}

// ---------------------------------------------------------------------------
// 4. 明细读写层与判据表（不起 JVM 的那半）
// ---------------------------------------------------------------------------

#[test]
fn 表头与五列逐字照_symbol_link_audit_150_且登记基准可复算() {
    assert_eq!(
        audit::RT_TSV_HEADER,
        "status\tcheck\tin_memory\treloaded\tdetail",
        "表头必须逐字等于 Kotlin 的字面量（SymbolLinkAudit.kt:150）"
    );
    for rel in [
        "expected/green.tsv",
        "expected/green-limit3.tsv",
        "tamper/meta-program-sha256-changed.tsv",
        "tamper/meta-private-dims-dropped.tsv",
    ] {
        let disk = read_bytes(&data(rel));
        let text = String::from_utf8(disk.clone()).unwrap_or_else(|_| panic!("{rel} 应是 UTF-8"));
        assert!(text.starts_with(&format!("{}\n", audit::RT_TSV_HEADER)), "{rel} 首行不是登记的表头");
        let checks = audit::parse_roundtrip_tsv(&text).unwrap_or_else(|e| panic!("{rel} 解析失败：{e:?}"));
        for row in text.lines().skip(1) {
            assert_eq!(row.split('\t').count(), 5, "{rel} 每行必须 5 列，实为 `{row}`");
        }
        assert_eq!(
            audit::roundtrip_tsv_text(&checks).unwrap().as_bytes(),
            disk.as_slice(),
            "{rel} 复算与原件不等（写出层口径漂了）"
        );
    }
    // 绿档 16 项全 OK；篡改档必须真有 DIFF（防基准空转）
    let green = parse_detail(&data("expected/green.tsv"));
    assert_eq!(green.len(), 16, "小语料档 16 项读数");
    assert!(!audit::roundtrip_is_red(&green));
    let tamper = parse_detail(&data("tamper/meta-private-dims-dropped.tsv"));
    assert_eq!(tamper.len(), 16, "篡改档同样 16 项，只是其中几项 DIFF");
    assert_eq!(audit::roundtrip_mismatches(&tamper).len(), 2, "少一条私有 #DIM 应红两处");
    assert_eq!(
        audit::roundtrip_mismatch_names(&tamper),
        vec!["meta.private-dims".to_string(), "runtime.private-vars".to_string()],
        "DIFF 项的序 = checks 的序（Main.kt:568 的 joinToString 用的就是这个序）"
    );
}

#[test]
fn 明细坏档一律报红不静默跳行() {
    assert!(audit::parse_roundtrip_tsv("status\tcheck\n").is_err(), "表头不对必须报红");
    assert!(
        audit::parse_roundtrip_tsv(&format!("{}\nOK\tmeta.files\t7 条\n", audit::RT_TSV_HEADER)).is_err(),
        "3 列必须报红"
    );
    assert!(
        audit::parse_roundtrip_tsv(&format!("{}\nTRUE\tmeta.files\t7 条\t7 条\t\n", audit::RT_TSV_HEADER)).is_err(),
        "状态列不是 OK/DIFF 必须报红，拒绝按绿处理"
    );
    // 绿档 detail 非空是合法的（`meta.program-sha256` 那条恒带配对说明，:712-718）⇒
    // 不许把「detail 非空」当红判据
    let ok_with_detail = audit::parse_roundtrip_tsv(&format!(
        "{}\nOK\tmeta.program-sha256\t{}\t{}\t侧车与容器必须成对（头部摘要 = program.erbc 的 SHA-256）\n",
        audit::RT_TSV_HEADER,
        "a".repeat(64),
        "a".repeat(64)
    ))
    .unwrap();
    assert_eq!(ok_with_detail.len(), 1);
    assert!(!audit::roundtrip_is_red(&ok_with_detail), "detail 非空的 OK 行不是红");
    // clean：制表符/换行/回车各换成一个空格（SymbolLinkAudit.kt:161）
    let dirty = audit::RoundTripCheck {
        name: "判\t据\n名\r回".to_string(),
        in_memory: "内存\t值".to_string(),
        reloaded: "装载\n值".to_string(),
        equal: false,
        detail: "明细\t带\r制表".to_string(),
    };
    let text = audit::roundtrip_tsv_text(&[dirty]).unwrap();
    let rows: Vec<&str> = text.lines().skip(1).collect();
    assert_eq!(rows.len(), 1, "clean 后必须还是一行");
    let cols: Vec<&str> = rows[0].split('\t').collect();
    assert_eq!(cols.len(), 5);
    assert_eq!(cols[0], "DIFF", "equal=false ⇒ 第 1 列 DIFF（:152）");
    assert_eq!(cols[1], "判 据 名 回");
    assert_eq!(cols[4], "明细 带 制表");
    // 一次往返必须稳定（clean 不可逆，但复算幂等）
    let back = audit::parse_roundtrip_tsv(&text).unwrap();
    assert_eq!(audit::roundtrip_tsv_text(&back).unwrap().as_bytes(), text.as_bytes());
}

#[test]
fn 编排层_退出码与kernel红行交叉核对不等必须报parity红() {
    // 判据两侧独立算，算不到一起就报红（契约 §0.2：不许静默换算法，也不许跟着 kernel 退 0）。
    let dir = gate_out("cross-check");
    let disk = read_bytes(&data("tamper/meta-program-sha256-changed.tsv"));
    let checks = audit::parse_roundtrip_tsv(&String::from_utf8(disk.clone()).unwrap()).unwrap();
    assert!(audit::roundtrip_is_red(&checks));

    // kernel 忘了入闸：明细里有 DIFF，它自己退 0 ⇒ 不许跟着退 0
    let err = fake_orchestrate_err(0, "", Some(disk.clone()), &dir).expect_err("kernel 退 0 但有 DIFF 必须报红");
    match err {
        ErbccError::Parity(m) => assert!(m.contains("不等"), "文案要点名判据不等，实为：{m}"),
        other => panic!("应报 Parity，实为 {other:?}"),
    }
    // kernel 的红行报 3 项、明细里只有 1 项 ⇒ 两侧读数不等，报红
    let lying = "对账未通过：3 项不等（meta.files、meta.labels、usages.names）；产物保留在 C:/x\n";
    let err = fake_orchestrate_err(1, lying, Some(disk.clone()), &dir).expect_err("红行与明细不等必须报红");
    match err {
        ErbccError::Parity(m) => assert!(m.contains("不等项两侧不等"), "实为：{m}"),
        other => panic!("应报 Parity，实为 {other:?}"),
    }
    // 红行与明细同尺 ⇒ 退 1
    assert_eq!(
        fake_orchestrate(1, &red_line_from(&checks, &dir), Some(disk.clone()), &dir),
        1,
        "红行与明细同尺时应退 1"
    );
    // 红档却没有红行（异常穿透了 `Main.kt:549` 的单 catch）⇒ 无法交叉核对，报红；
    // 不放宽成「按明细 alone 收口」，也不跟着 kernel 退 0。
    let err = fake_orchestrate_err(1, "", Some(disk.clone()), &dir).expect_err("红档缺红行必须报红");
    match err {
        ErbccError::Io(m) => assert!(m.contains("对账未通过"), "文案要点名缺的那条红行，实为：{m}"),
        other => panic!("应报 Io，实为 {other:?}"),
    }
    // 明细被截坏（列数不对）⇒ 报红，不按「没有不等项」处理
    let broken = format!("{}\nOK\tmeta.files\t7 条\t7 条\n", audit::RT_TSV_HEADER);
    let err = fake_orchestrate_err(1, &red_line_from(&checks, &dir), Some(broken.into_bytes()), &dir)
        .expect_err("坏明细必须报红");
    match err {
        ErbccError::Io(m) => assert!(m.contains("5 列"), "实为：{m}"),
        other => panic!("应报 Io，实为 {other:?}"),
    }
    // kernel 退 3（不在 0/1 契约内，Main.kt:95）⇒ 报红
    let err = fake_orchestrate_err(3, "", Some(disk), &dir).expect_err("kernel 退 3 必须报红");
    match err {
        ErbccError::Io(m) => assert!(m.contains("退出码 3"), "实为：{m}"),
        other => panic!("应报 Io，实为 {other:?}"),
    }
}

#[test]
fn 编排层_编译失败档退1且不落明细() {
    // Main.kt:547-552：`roundTrip` 抛 IllegalStateException ⇒ 打一行 `对账失败：…` 就 return 1；
    // 明细的 writeTsv（`:564`）在它之后 ⇒ 一个字节都不该落。
    let dir = gate_out("fake-badcompile");
    let stderr = "对账失败：编译失败（2 条），没有可落盘的内存产物: B1_POISON.ERB:1 语句解析错误\n";
    let code = fake_orchestrate(1, stderr, None, &dir);
    assert_eq!(code, 1, "编译失败 ⇒ 1（USAGE Main.kt:95「或编译失败，不落盘」）");
    assert!(!dir.join("rt.tsv").exists(), "不许由 Rust 侧凭空复算出一份明细");
    // 同一条红行 + 退出码 0 ⇒ 契约不符，报红
    let err = fake_orchestrate_err(0, stderr, None, &dir).expect_err("报了对账失败却退 0 必须报红");
    assert!(matches!(err, ErbccError::Parity(_)), "实为 {err:?}");
}

#[test]
fn 红档保留产物目录且红行整行透出() {
    // USAGE（Main.kt:93）的红半句：「红了保留路径供复现」。
    // 删除动作只在 kernel 的临时目录分支里（`SymbolLinkAudit.kt:505/508`），
    // 本路**一个字节都不删**；红行（`Main.kt:567-570`，尾句 `产物保留在 …`）整行原样透出。
    let dir = gate_out("keep-red");
    std::fs::create_dir_all(dir.join("erbc.v1")).expect("建产物目录");
    let marker = dir.join("erbc.v1/program.erbc");
    std::fs::write(&marker, "placeholder-container").expect("写占位产物");
    let disk = read_bytes(&data("tamper/meta-program-sha256-changed.tsv"));
    let checks = audit::parse_roundtrip_tsv(&String::from_utf8(disk.clone()).unwrap()).unwrap();
    let red = red_line_from(&checks, &dir);

    assert_eq!(fake_orchestrate(1, &red, Some(disk), &dir), 1);
    assert!(
        marker.is_file(),
        "红了必须保留 --artifact-dir 里的产物供复现（本路不许删盘）：{dir:?}"
    );

    let tail = cmd_audit::rt_stderr_tail(&red);
    assert_eq!(tail, vec![red.trim().to_string()], "红行要整行透出，实为 {tail:?}");
    assert!(tail[0].contains("产物保留在"), "尾句（Main.kt:569）不能被截掉：{}", tail[0]);
    assert!(cmd_audit::rt_verdict_red_seen(&red), "红行识别判据");
    assert!(!cmd_audit::rt_compile_failure_seen(&red), "红行不是编译失败行（两条分支不同处置）");
    // 绿档：既没有红行也没有失败行
    let green = "notes 1 条（留痕，不参与成败判定；至多列 20 条）:\n  切片完成\n".to_string();
    assert!(cmd_audit::rt_stderr_tail(&green).is_empty(), "绿档不该有尾段红行");
    assert!(!cmd_audit::rt_verdict_red_seen(&green) && !cmd_audit::rt_compile_failure_seen(&green));
    // 降级警告的去重面：行首那条滤掉、带缩进的留痕行透出
    let with_limit = "降级模式：只审计前 3 个文件（正式闸必须全量）\n  降级模式：只切片前 3/8 个文件\n对账失败：x\n";
    let head = cmd_audit::rt_stderr_head(with_limit, Some(3));
    assert_eq!(head, vec!["  降级模式：只切片前 3/8 个文件".to_string()], "实为 {head:?}");
    assert_eq!(cmd_audit::rt_stderr_tail(with_limit), vec!["对账失败：x".to_string()]);
}

// ---------------------------------------------------------------------------
// 5. argv / classpath 档 / 缺省明细路径（不起 JVM）
// ---------------------------------------------------------------------------

#[test]
fn roundtrip的argv带roundtrip与artifact_dir且不带strict_allow() {
    let argv = kernel::roundtrip_kernel_argv(
        "cp",
        Path::new("C:/corp"),
        Path::new("C:/game"),
        Path::new("C:/rt.tsv"),
        Some(Path::new("C:/art")),
        Some(3),
    );
    let joined: Vec<String> = argv.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    let pos = |s: &str| joined.iter().position(|x| x == s);
    assert_eq!(pos("audit"), pos(kernel::MAIN_CLASS).map(|i| i + 1), "子命令紧跟 MAIN_CLASS，实为 {joined:?}");
    assert!(pos("--roundtrip").is_some(), "--roundtrip 必须进 argv（Main.kt:460/476）");
    assert_eq!(joined[pos("--corpus").unwrap() + 1], "C:/corp");
    assert_eq!(joined[pos("--game-root").unwrap() + 1], "C:/game");
    assert_eq!(joined[pos("--out").unwrap() + 1], "C:/rt.tsv");
    assert_eq!(joined[pos("--artifact-dir").unwrap() + 1], "C:/art");
    assert_eq!(joined[pos("--limit").unwrap() + 1], "3");
    for flag in ["--strict", "--allow"] {
        assert!(!joined.iter().any(|x| x == flag), "{flag} 不进 roundtrip 的 argv（Main.kt:476 之后没人读它）");
    }
    // 缺省 artifact-dir 档：一个都不许多传，让 kernel 自己开临时目录（SymbolLinkAudit.kt:485）
    let bare = kernel::roundtrip_kernel_argv("cp", Path::new("c"), Path::new("g"), Path::new("o"), None, None);
    let b: Vec<String> = bare.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    assert!(b.iter().any(|x| x == "--roundtrip"), "缺省档仍要带 --roundtrip");
    for flag in ["--artifact-dir", "--limit", "--strict", "--allow"] {
        assert!(!b.iter().any(|x| x == flag), "缺省档不该出现 {flag}，实为 {b:?}");
    }
}

#[test]
fn roundtrip的plan走同一套classpath三档优先级() {
    let repo = repo_root();
    let env = |k: &str| -> Option<String> {
        if k == kernel::CLASSPATH_ENV {
            Some("C:/fake/erbcc.jar".to_string())
        } else {
            None
        }
    };
    let plan = kernel::plan_roundtrip(
        &repo,
        Path::new("C:/corp"),
        Path::new("C:/game"),
        Path::new("C:/rt.tsv"),
        Some(Path::new("C:/art")),
        None,
        &env,
    )
    .expect("档 1 直接串应可成计划");
    assert!(plan.classpath_origin.contains("档 1"), "实为：{}", plan.classpath_origin);
    let joined: Vec<String> = plan.argv.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    assert!(joined.iter().any(|x| x == "C:/fake/erbcc.jar"), "-cp 应取环境变量直接串");
    assert!(joined.iter().any(|x| x == "--roundtrip"));
    assert!(
        joined.iter().any(|x| x == &absolutize(Path::new("C:/art")).to_string_lossy().into_owned()),
        "--artifact-dir 要给绝对路径（kernel 子进程的 CWD 是仓库根，相对值会落错地方）：{joined:?}"
    );
    // 堆档与编码参数与第一波同尺（同一份 JVM_OPTS，别在这儿开新口径）
    for opt in kernel::JVM_OPTS {
        assert!(joined.iter().any(|x| x == opt), "JVM 固定参数缺 {opt}，实为 {joined:?}");
    }
    // 取不到 classpath ⇒ 报红并点名要先跑的 Gradle 任务（不许猜路径）
    let none_env = |_: &str| -> Option<String> { None };
    let planned = kernel::plan_roundtrip(
        Path::new("C:/definitely-not-a-repo-root"),
        Path::new("C:/corp"),
        Path::new("C:/game"),
        Path::new("C:/rt.tsv"),
        None,
        None,
        &none_env,
    );
    let err = match planned {
        Ok(_) => panic!("没有 classpath 却成了计划"),
        Err(e) => e,
    };
    match err {
        ErbccError::Io(m) => assert!(m.contains(kernel::INSTALL_DIST_HINT), "要写清先跑哪条任务，实为：{m}"),
        other => panic!("应报 Io，实为 {other:?}"),
    }
}

#[test]
fn roundtrip的缺省明细路径与out_given口径() {
    // Main.kt:476 把 `if (outGiven) out else null` 交给 cmdAuditRoundTrip，
    // 后者缺省 `tools/erbcc/symbol-audit/roundtrip.tsv`（`:563`）—— **不是**普通 audit 的
    // unresolved-symbols.tsv（`:440`）。kernel 子进程 CWD 固定在仓库根 ⇒ 本路按仓库根解析，
    // 读回来才是 kernel 落的那个文件。
    let rt_only = vec![
        "--corpus".to_string(),
        corpus().to_string_lossy().into_owned(),
        "--roundtrip".to_string(),
    ];
    let parsed = cmd_audit::parse_args(&rt_only).expect("只给 --corpus + --roundtrip 应通过");
    assert!(parsed.roundtrip);
    assert!(!parsed.out_given);
    assert!(parsed.artifact_dir.is_none());

    let out_abs = absolutize(&data("expected/green.tsv"));
    let art_abs = absolutize(&gate_out("artifact-arg"));
    let rt_with_out = vec![
        "--corpus".to_string(),
        corpus().to_string_lossy().into_owned(),
        "--roundtrip".to_string(),
        "--out".to_string(),
        out_abs.to_string_lossy().into_owned(),
        "--artifact-dir".to_string(),
        art_abs.to_string_lossy().into_owned(),
    ];
    let given = cmd_audit::parse_args(&rt_with_out).expect("显式 --out/--artifact-dir 应通过");
    assert!(given.out_given);
    assert_eq!(absolutize(given.artifact_dir.as_ref().unwrap()), art_abs);

    let repo = repo_root();
    assert_eq!(
        cmd_audit::roundtrip_out_path(&repo, &parsed),
        repo.join("tools/erbcc/symbol-audit/roundtrip.tsv"),
        "缺省明细 = Main.kt:563 的那个文件，按仓库根（kernel 的 CWD）解析"
    );
    assert_eq!(
        cmd_audit::roundtrip_out_path(&repo, &given),
        out_abs,
        "显式 --out 必须原样用（Main.kt:476 的 outGiven 档）"
    );
    // 普通 audit 的缺省仍是 unresolved-symbols.tsv（已收口的判据，不许被带歪）
    let plain = cmd_audit::parse_args(&vec!["--corpus".to_string(), corpus().to_string_lossy().into_owned()])
        .unwrap();
    assert_eq!(
        plain.out,
        absolutize(Path::new("tools/erbcc/symbol-audit/unresolved-symbols.tsv")),
        "Main.kt:440"
    );
    assert!(!plain.roundtrip);
}

#[test]
fn roundtrip已接线_真二进制不再报未移植() {
    // 替换掉 rust-audit 车道留下的「未移植」占位断言：现在 --roundtrip 必须真走 kernel 腿。
    // 用最便宜的坏档（编译失败）走完整 CLI：退出码 1 + 红行，且 stderr 里没有「未移植」。
    let dir = gate_out("wired");
    let started = std::process::Command::new(env!("CARGO_BIN_EXE_erbcc"))
        .args([
            "audit",
            "--corpus",
            &absolutize(&bad_corpus()).to_string_lossy(),
            "--game-root",
            &absolutize(&bad_game_root()).to_string_lossy(),
            "--out",
            &dir.join("rt.tsv").to_string_lossy(),
            "--roundtrip",
        ])
        .current_dir(manifest_dir())
        .output()
        .expect("跑真 erbcc 二进制");
    let stderr = String::from_utf8_lossy(&started.stderr).into_owned();
    assert!(!stderr.contains("未移植"), "audit --roundtrip 不许还停在「本波未移植」占位：\n{stderr}");
    assert_eq!(started.status.code().unwrap_or(0), 1, "坏档应退 1（对账失败），stderr:\n{stderr}");
    assert!(stderr.contains("对账失败："), "要点名对账失败，实为：\n{stderr}");
}

// ---------------------------------------------------------------------------
// 替身 kernel：让「读回来的明细」与「kernel 的退出码/红行」可控，把编排层的判据面钉死
// ---------------------------------------------------------------------------

/// 按 `Main.kt:567-570` 的模板生成一条与明细同尺的红行。
/// 条数与判据名取自**本路复算**的结果（同一判据、不同算法）；两者不同尺的情形由
/// `编排层_退出码与kernel红行交叉核对不等必须报parity红` 专门覆盖。
fn red_line_from(checks: &[audit::RoundTripCheck], dir: &Path) -> String {
    format!(
        "对账未通过：{} 项不等（{}）；产物保留在 {}\n",
        audit::roundtrip_mismatches(checks).len(),
        audit::roundtrip_mismatch_names(checks).join("、"),
        dir.join("erbc.v1").display()
    )
}

/// 替身 kernel 的读数（明细已在盘上 / 或明确不落明细）。
#[derive(Clone)]
struct FakeKernel {
    rc: i32,
    stderr: String,
    /// `Some(bytes)` = kernel 落了明细；`None` = 没落（编译失败/崩了）。
    detail: Option<Vec<u8>>,
}

thread_local! {
    static FAKE: std::cell::RefCell<Option<FakeKernel>> = const { std::cell::RefCell::new(None) };
}

/// 用替身 kernel 跑编排，返回退出码（panic 于 Err）。
fn fake_orchestrate(rc: i32, stderr: &str, detail: Option<Vec<u8>>, dir: &Path) -> i32 {
    fake_orchestrate_err(rc, stderr, detail, dir).unwrap_or_else(|e| panic!("替身档编排报红：{e:?}"))
}

fn fake_orchestrate_err(rc: i32, stderr: &str, detail: Option<Vec<u8>>, dir: &Path) -> Outcome<i32> {
    std::fs::create_dir_all(dir).expect("建替身档目录");
    let out = dir.join("rt.tsv");
    match &detail {
        Some(bytes) => std::fs::write(&out, bytes).expect("替身 kernel 落明细"),
        None => {
            let _ = std::fs::remove_file(&out);
        }
    }
    FAKE.with(|s| {
        *s.borrow_mut() = Some(FakeKernel {
            rc,
            stderr: stderr.to_string(),
            detail,
        })
    });
    let args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus().to_string_lossy().into_owned(),
        "--game-root".to_string(),
        game_root().to_string_lossy().into_owned(),
        "--out".to_string(),
        out.to_string_lossy().into_owned(),
        "--roundtrip".to_string(),
        "--artifact-dir".to_string(),
        dir.to_string_lossy().into_owned(),
    ];
    let parsed = cmd_audit::parse_args(&args)?;
    let env: &kernel::EnvFn = &kernel::os_env;
    let deps = cmd_audit::RtDeps {
        run_kernel: fake_roundtrip_kernel,
        read_file,
        atomic_write: manifest::atomic_write,
    };
    cmd_audit::orchestrate_roundtrip(&repo_root(), &parsed, &env, &deps)
}

/// 替身 kernel：不回真 JVM，只回放 `FAKE` 里的退出码与文本；
/// 顺带把「明细已被 kernel 写过」的字节从盘上取回（本路的读数面），并断言 `--out` 同尺。
fn fake_roundtrip_kernel(
    repo_root: &Path,
    _corpus: &str,
    _game: &Path,
    out: &Path,
    _artifact_dir: Option<&Path>,
    _limit: Option<i32>,
    _env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    let _ = repo_root;
    FAKE.with(|s| {
        let st = s.borrow().clone().expect("替身 kernel 没被喂读数");
        assert!(out.is_file() == st.detail.is_some(), "替身 kernel 的落明细面与编排层读回的面不一致：{out:?}");
        Ok(KernelOutput {
            exit_code: st.rc,
            stdout: "=== 落盘后再装载对账（CMP-79）===\n".to_string(),
            stderr: st.stderr,
            elapsed_ms: 1,
        })
    })
}

fn read_file(path: &Path) -> Outcome<Vec<u8>> {
    std::fs::read(path).map_err(|e| ErbccError::Io(format!("{}: {e}", path.display())))
}

/// 第一处差异（行号 + 两侧原样）。
fn first_text_diff(a: &str, b: &str) -> String {
    for (i, (la, lb)) in a.lines().zip(b.lines()).enumerate() {
        if la != lb {
            return format!("第 {} 行不等：\n  A = {la}\n  B = {lb}", i + 1);
        }
    }
    let la = a.lines().count();
    let lb = b.lines().count();
    if la != lb {
        return format!("行数不等：{la} vs {lb}");
    }
    "按行全等但字节不等 ⇒ 差在行终止符或编码".to_string()
}

// ---------------------------------------------------------------------------
// 6. 基准重生成（rust-regen-roundtrip：`ERBCC_RS_REGEN_ROUNDTRIP` 显式开关）
// ---------------------------------------------------------------------------
//
// 驱动入口：`python devtools/erbcc_rs_parity.py regen --lane rust-roundtrip` —— python 侧
// 只负责 `cargo test --test roundtrip_parity -- --exact regen_roundtrip_baseline` 与校验落盘，
// **产出全部来自本函数的真 Kotlin 腿现跑**（与入库闸同一套 `leg_kotlin` / `RtVerify` /
// 篡改函数，禁手抄）。未置位开关时本格直接返回 ⇒ 默认闸逐字节行为与从前一致。
// 判据不放宽：这里只不比对「旧基准」，**现跑自检照常报红**（空产物/半套基准/绿档出 DIFF
// 都不许写盘）—— 重生成只能产出「真跑出来的当前值」。

/// 本路全部入库基准（相对 `tests/data/rust-roundtrip/`）。截断档抛在解码前、没有明细，
/// 所以它的「三件套」只有两件（被截容器 + 读数行）—— 与入库闸读的文件集逐一对应。
const REGEN_FILES: [&str; 10] = [
    "expected/green.tsv",
    "expected/green-limit3.tsv",
    "tamper/meta-program-sha256-changed.tsv",
    "tamper/meta-program-sha256-changed.program.meta",
    "tamper/meta-program-sha256-changed.expect.txt",
    "tamper/meta-private-dims-dropped.tsv",
    "tamper/meta-private-dims-dropped.program.meta",
    "tamper/meta-private-dims-dropped.expect.txt",
    "tamper/truncated-erbc.program.erbc",
    "tamper/truncated-erbc.expect.txt",
];

/// 打印 `ERBCC_RS_REGEN|<相对路径>|<字节数>|<sha256>` 并按档写盘（`dry` 只打印）。
/// 防空转第一道：空字节流直接报红，空产物永远不许进基准。
fn regen_emit(emitted: &mut Vec<String>, dry: bool, rel: &str, bytes: &[u8]) {
    assert!(!bytes.is_empty(), "regen 防空转：{rel} 现跑产出为空字节");
    println!("ERBCC_RS_REGEN|{rel}|{}|{}", bytes.len(), sha256_hex(bytes));
    if !dry {
        let path = data(rel);
        std::fs::create_dir_all(path.parent().unwrap()).expect("建 regen 基准子目录");
        std::fs::write(&path, bytes).expect("写 regen 基准文件");
    }
    emitted.push(rel.to_string());
}

/// 绿档明细的现跑自检（不比对旧基准，只保证「真跑出了非空的满表」）：16 项、零 DIFF、
/// 摘要读数 64 位十六进制 —— 少一个都说明读侧链路坏了，禁把半坏基准写进 git。
fn regen_assert_green_detail(bytes: &[u8], tag: &str) {
    let text = String::from_utf8(bytes.to_vec()).unwrap_or_else(|_| panic!("{tag} 明细应是 UTF-8"));
    let checks = audit::parse_roundtrip_tsv(&text).unwrap_or_else(|e| panic!("{tag} 明细解析失败：{e:?}"));
    assert_eq!(audit::roundtrip_check_count(&checks), 16, "{tag}：真跑必须出满 16 项读数");
    assert!(
        audit::roundtrip_mismatches(&checks).is_empty(),
        "{tag}：绿档现跑出 DIFF ⇒ pristine 基线本身坏了，禁止重生成"
    );
    let sha = checks
        .iter()
        .find(|c| c.name == "meta.program-sha256")
        .expect("绿档必有 meta.program-sha256 判据");
    assert_eq!(sha.in_memory.len(), 64, "{tag}：摘要读数应恒为 64 位十六进制，实为 {:?}", sha.in_memory);
}

/// 读数行的落盘形状：JVM `println` 原样（Windows 产出带 `\r\n` 尾）；入库闸用
/// `expect_text(...).trim()` 回读，尾终止符不参与比对 —— 这里保持与现档逐字节一致。
fn regen_verdict_bytes(verdict: &str) -> Vec<u8> {
    format!("{}\r\n", verdict.trim()).into_bytes()
}

#[test]
fn regen_roundtrip_baseline() {
    let mode = std::env::var("ERBCC_RS_REGEN_ROUNDTRIP").unwrap_or_default();
    if mode.is_empty() {
        println!("regen_roundtrip_baseline：ERBCC_RS_REGEN_ROUNDTRIP 未置位 ⇒ 跳过（默认闸行为不变）");
        return;
    }
    if mode != "1" && mode != "dry" {
        panic!("ERBCC_RS_REGEN_ROUNDTRIP 只吃 1（现跑并写盘）或 dry（现跑只打印），实为 {mode:?}");
    }
    let dry = mode == "dry";
    let mut emitted: Vec<String> = Vec::new();

    // ① 绿档：一趟真 Kotlin 腿同时产 `expected/green.tsv` 与 pristine 产物对
    //    （`--artifact-dir` 给定 ⇒ 留盘，`SymbolLinkAudit.kt:484` 的 temporary 为假）。
    let green_dir = gate_out("regen-green");
    let green_tsv = green_dir.join("roundtrip.tsv");
    let leg = leg_kotlin(&corpus(), &game_root(), &green_tsv, Some(&green_dir), &[]);
    assert_eq!(
        leg.rc, 0,
        "regen：绿档 Kotlin 腿必须退 0（工具链坏了先修再重生成，不许写半套基准）\nstderr:\n{}",
        leg.stderr
    );
    let green = read_bytes(&green_tsv);
    regen_assert_green_detail(&green, "green");
    regen_emit(&mut emitted, dry, "expected/green.tsv", &green);
    let pristine_erbc = green_dir.join("erbc.v1/program.erbc");
    let pristine_meta = green_dir.join("erbc.v1/program.meta");
    assert!(
        pristine_erbc.is_file() && pristine_meta.is_file(),
        "regen：绿档产物对没留在 --artifact-dir（tamper 三件套的原料）：{green_dir:?}"
    );

    // ② --limit 3 档
    let lim_dir = gate_out("regen-limit3");
    let lim_tsv = lim_dir.join("roundtrip.tsv");
    let leg_lim = leg_kotlin(&corpus(), &game_root(), &lim_tsv, Some(&lim_dir), &["--limit", "3"]);
    assert_eq!(leg_lim.rc, 0, "regen：--limit 3 档 Kotlin 腿必须退 0\nstderr:\n{}", leg_lim.stderr);
    let lim = read_bytes(&lim_tsv);
    regen_assert_green_detail(&lim, "limit3");
    regen_emit(&mut emitted, dry, "expected/green-limit3.tsv", &lim);

    // ③ tamper 三件套：篡改函数与入库闸同源（flip / drop / truncate 三个函数一个都不另造）。
    //    每档现跑自检照常报红（判据不放宽）：绿档腿坏 ⇒ 整格 panic，绝不允许写半套基准。

    // ③a 侧车 program-sha256 改一个十六进制字符
    let dir_sha = gate_out("regen-tamper-sha");
    std::fs::create_dir_all(dir_sha.join("erbc.v1")).expect("建 tamper 产物目录");
    let erbc_sha = dir_sha.join("erbc.v1/program.erbc");
    let meta_sha = dir_sha.join("erbc.v1/program.meta");
    std::fs::copy(&pristine_erbc, &erbc_sha).expect("复制 pristine program.erbc");
    std::fs::copy(&pristine_meta, &meta_sha).expect("复制 pristine program.meta");
    flip_program_sha(&meta_sha);
    let kt_tsv_sha = dir_sha.join("kotlin-roundtrip.tsv");
    let kv_sha = kotlin_verify_artifacts(&erbc_sha, &meta_sha, &kt_tsv_sha);
    assert!(kv_sha.verdict.starts_with("ok=false"), "regen：篡改 program-sha256 后 Kotlin 读侧必须红：{}", kv_sha.verdict);
    assert_eq!(
        harness_mismatch_names(&kv_sha.verdict),
        vec!["meta.program-sha256".to_string()],
        "regen：这一档必须只红在摘要判据上，否则篡改姿势变了（同步改入库闸）：{}",
        kv_sha.verdict
    );
    let detail_sha = kv_sha.detail.clone().expect("红档也该落明细（没落 = 读侧坏了，禁写半套基准）");
    regen_emit(&mut emitted, dry, "tamper/meta-program-sha256-changed.tsv", &detail_sha);
    regen_emit(&mut emitted, dry, "tamper/meta-program-sha256-changed.program.meta", &read_bytes(&meta_sha));
    regen_emit(&mut emitted, dry, "tamper/meta-program-sha256-changed.expect.txt", &regen_verdict_bytes(&kv_sha.verdict));

    // ③b 删最后一条私有 #DIM + 计数减一
    let dir_pv = gate_out("regen-tamper-priv");
    std::fs::create_dir_all(dir_pv.join("erbc.v1")).expect("建 tamper 产物目录");
    let erbc_pv = dir_pv.join("erbc.v1/program.erbc");
    let meta_pv = dir_pv.join("erbc.v1/program.meta");
    std::fs::copy(&pristine_erbc, &erbc_pv).expect("复制 pristine program.erbc");
    std::fs::copy(&pristine_meta, &meta_pv).expect("复制 pristine program.meta");
    drop_one_private_dim(&meta_pv);
    let kt_tsv_pv = dir_pv.join("kotlin-roundtrip.tsv");
    let kv_pv = kotlin_verify_artifacts(&erbc_pv, &meta_pv, &kt_tsv_pv);
    assert!(kv_pv.verdict.starts_with("ok=false"), "regen：少一条私有 #DIM 必须红：{}", kv_pv.verdict);
    let names_pv = harness_mismatch_names(&kv_pv.verdict);
    assert!(
        names_pv.contains(&"meta.private-dims".to_string()) && names_pv.contains(&"runtime.private-vars".to_string()),
        "regen：这一档必须红在侧车与运行期两条判据上（与入库闸同口径）：{names_pv:?}"
    );
    let detail_pv = kv_pv.detail.clone().expect("红档也该落明细");
    regen_emit(&mut emitted, dry, "tamper/meta-private-dims-dropped.tsv", &detail_pv);
    regen_emit(&mut emitted, dry, "tamper/meta-private-dims-dropped.program.meta", &read_bytes(&meta_pv));
    regen_emit(&mut emitted, dry, "tamper/meta-private-dims-dropped.expect.txt", &regen_verdict_bytes(&kv_pv.verdict));

    // ③c 截断容器尾 16 字节：抛在解码前 ⇒ 只有两件（被截容器 + 读数行），且不许有明细
    let dir_tr = gate_out("regen-tamper-trunc");
    std::fs::create_dir_all(dir_tr.join("erbc.v1")).expect("建 tamper 产物目录");
    let erbc_tr = dir_tr.join("erbc.v1/program.erbc");
    let meta_tr = dir_tr.join("erbc.v1/program.meta");
    std::fs::copy(&pristine_erbc, &erbc_tr).expect("复制 pristine program.erbc");
    std::fs::copy(&pristine_meta, &meta_tr).expect("复制 pristine program.meta");
    truncate_erbc(&erbc_tr);
    let kt_tsv_tr = dir_tr.join("kotlin-roundtrip.tsv");
    let kv_tr = kotlin_verify_artifacts(&erbc_tr, &meta_tr, &kt_tsv_tr);
    assert!(kv_tr.verdict.starts_with("THREW "), "regen：截断档必须抛（读数被糊成 ok= 档就是基准坏了）：{}", kv_tr.verdict);
    assert!(kv_tr.verdict.contains("ErbcDecodeException"), "regen：要抛在真装载链的解码上：{}", kv_tr.verdict);
    assert!(kv_tr.detail.is_none(), "regen：抛了就不该有明细 —— 有明细说明红被糊成了读数");
    regen_emit(&mut emitted, dry, "tamper/truncated-erbc.program.erbc", &read_bytes(&erbc_tr));
    regen_emit(&mut emitted, dry, "tamper/truncated-erbc.expect.txt", &regen_verdict_bytes(&kv_tr.verdict));

    // 成套收口：10 件一件不能少（半套写盘 = 「三件套必须成套换」的红线）
    emitted.sort();
    let mut want: Vec<String> = REGEN_FILES.iter().map(|s| s.to_string()).collect();
    want.sort();
    assert_eq!(emitted, want, "regen：基准必须成套产出，缺件/多件都不许算成功");
    println!(
        "regen_roundtrip_baseline：{} 件基准现跑完成（档：{}）",
        REGEN_FILES.len(),
        if dry { "dry，未写盘" } else { "已写回 tests/data/rust-roundtrip/" }
    );
}
