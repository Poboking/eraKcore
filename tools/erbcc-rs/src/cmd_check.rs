use crate::freshness;
use crate::types::{absolutize, kotlin_path_text, ErbccError, Outcome, EXIT_OK, EXIT_FAIL};
use std::path::{Path, PathBuf};

/// `erbcc check` 子命令：`--corpus`（必填）/`--out`（缺省 `tools/erbcc/erbc-out`），
/// 判据全在 `freshness::check`，**不重编**。退出码 0 全对齐 / 1 有新鲜度问题 / 2 参数错误。
/// 权威口径 = Kotlin `Main.kt:406-423`。
///
/// **`program.meta` 的深判据是一条 JVM 腿**（rust-meta-parity）：`freshness::check` 的头层只筛
/// 三行，深处坏掉的那 60 档（`tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv`）由
/// [freshness::meta_leg_via_jvm] 起一条 `java -cp … MainKt check` 把 `BootMetadata.read` 的
/// 结论原文取回来（`ArtifactFreshness.kt:336-349` 的判定顺序：全文解析失败 ⇒ `META_UNREADABLE`，
/// 读过了才比 `program-sha256` ⇒ `META_STALE`）。argv 形状与 classpath 三档优先级在
/// [crate::kernel::check_via_jvm]，起进程仍是全 crate 唯一落点 [crate::kernel::run_plan]。
///
/// 位点为什么在 `freshness` 而不是本文件的编排层：`Deps` 的字段集被 `tests/kernel_bridge.rs:552-564`
/// 的结构体字面量钉死（加字段即编译红，而那个文件不归本路），而差分闸的 Rust 腿
/// （`tests/sidecar_fuzz_diff.rs` 的 `rust_leg`）调的就是 `freshness::check` 这一个函数 —— 腿挂在编排层
/// 那张表就不会翻。故 [PRODUCTION] 继续指 [freshness::check]（腿在内），本文件只补一条
/// [orchestrate_with_meta_leg] 给注入位，让「CLI 这一层也吃到腿」能被闸直接钉。
pub struct Deps {
    pub check: fn(&Path, &Path) -> Outcome<freshness::Report>,
}

/// 生产接线：判据全在 [freshness::check]（含它的 JVM 深判据腿）。
pub const PRODUCTION: Deps = Deps {
    check: freshness::check,
};

pub struct CheckArgs {
    /// 原始 `--corpus` 串（`Main.kt:419` 的「corpus 不是目录: 」打的是原始串）
    pub corpus_raw: String,
    pub corpus_dir: PathBuf,
    /// kernel `compile --out` 传的那个目录（**不是**它下面的 `erbc.v1`，见 `ArtifactFreshness.kt:140`）
    pub out_dir: PathBuf,
}

pub fn execute(args: &[String]) -> Outcome<i32> {
    orchestrate(args, &PRODUCTION)
}

/// [execute] 的可注入版本：本命令除了 [freshness::check] 没有别的依赖。
pub fn orchestrate(args: &[String], deps: &Deps) -> Outcome<i32> {
    let parsed = parse_args(args)?;
    let report = (deps.check)(&parsed.out_dir, &parsed.corpus_dir)?;
    print_and_exit(report)
}

/// 同 [orchestrate]，但把 `program.meta` 的深判据腿换成注入的替身（闸用：恒放行 / 抛错 / 计数）。
/// 生产路径 [execute] 走 [freshness::check]，腿在它里面 —— 这条函数的存在只为了让
/// 「CLI 层与库层同判」和「腿被抽掉就必须红」能在同一个入口上被钉住。
pub fn orchestrate_with_meta_leg(args: &[String], meta_leg: &freshness::MetaLeg) -> Outcome<i32> {
    let parsed = parse_args(args)?;
    let report = freshness::check_with_meta_leg(&parsed.out_dir, &parsed.corpus_dir, meta_leg)?;
    print_and_exit(report)
}

/// `Main.kt:421-422`：`println(report.summary())` 后按 `report.ok` 收退出码。
/// 有问题**不是**桥的错误 —— 摘要本身就是给用户的判据，不再追加 `错误:` 行。
fn print_and_exit(report: freshness::Report) -> Outcome<i32> {
    println!("{}", report.summary());
    if report.ok {
        Ok(EXIT_OK)
    } else {
        Ok(EXIT_FAIL)
    }
}

/// 参数解析（逐项照 `Main.kt:409-419`，含报红文案与判定顺序）。
pub fn parse_args(args: &[String]) -> Outcome<CheckArgs> {
    let mut out_raw = "tools/erbcc/erbc-out".to_string();
    let mut corpus_raw: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" => {
                out_raw = args
                    .get(i + 1)
                    .cloned()
                    .ok_or_else(|| ErbccError::Usage("--out 需要参数".into()))?;
                i += 1;
            }
            "--corpus" => {
                corpus_raw = Some(
                    args.get(i + 1)
                        .cloned()
                        .ok_or_else(|| ErbccError::Usage("--corpus 需要参数".into()))?,
                );
                i += 1;
            }
            other => return Err(ErbccError::Usage(format!("无法识别的 check 参数: {other}"))),
        }
        i += 1;
    }
    let corpus_raw = corpus_raw.ok_or_else(|| ErbccError::Usage("缺少 --corpus 参数".into()))?;
    let corpus_dir = absolutize(Path::new(&corpus_raw));
    if !corpus_dir.is_dir() {
        // `Main.kt:419` 打在未绝对化的原始串上；F1 用单源 [kotlin_path_text] 归一分隔符，
        // 与 scan/compile/symbols/audit 五臂同尺（否则同一格只差 `\` 与 `/`）。
        return Err(ErbccError::Usage(format!(
            "corpus 不是目录: {}",
            kotlin_path_text(&corpus_raw)
        )));
    }
    Ok(CheckArgs {
        corpus_raw,
        corpus_dir,
        out_dir: absolutize(Path::new(&out_raw)),
    })
}
