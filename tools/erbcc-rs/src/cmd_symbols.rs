//! `erbcc symbols` 子命令的 **Rust 编排层**：参数面 + 自足的 JVM 子进程启动器 +
//! 读数落盘。序列化与报告文本的**口径**在 [crate::symbols]。
//!
//! 权威 = Kotlin `tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/Main.kt:172-309`
//! （`cmdSymbols`）与 `Main.kt:61-66`（USAGE 的 symbols 段）。
//!
//! ## 启动器：与其余四条腿同一段（契约 §7 硬约束 0 的临时重复已合并）
//!
//! [crate::kernel] 由 rust-audit 路并行 append，本路第一波**不许改它**，所以 `symbols`
//! 的 argv 组装与子进程启动当时在本文件里自足实现。九路收口后这段重复已合并：
//! 本路只交出 `symbols` 的 **argv 尾部**（`symbols` 而非 `compile`，带 `--json`/
//! `--ordinal`/`--collator-check` 而非 `--out`/`--limit`），classpath 三档、`java` 定位、
//! argv 前缀（[crate::kernel::JVM_OPTS] + `-cp` + 入口类）、起进程与捕获/计时
//! 全部走 [crate::kernel::run_main] 这一条入口，本文件不再另立口径。
//!
//! ## 读数从哪儿来（判定权留 JVM）
//!
//! ja-JP Collator 是 ADR-006 的冻结口径（`Symbols.kt:444-451`），Rust 不复刻比较器。
//! 因此装载序、重名胜者、事件执行序、自检结论**全部由 kernel 判**：
//! 本路起 kernel 跑 `symbols --corpus … --json <临时读数档> [--ordinal] [--collator-check]`，
//! 把 kernel 落的读数档与 stdout 交给 [crate::symbols] 严格解析，再按 Kotlin 的字节布局
//! 重新序列化/重新渲染上屏。**没有 `--json` 入参时也照样跑 kernel**（否则无读数可用），
//! 只是不把临时档的内容当作用户产物、也不打摘要行。
//!
//! `--collator-check` 的语义按 `Main.kt:64`（比较器自检：ja-JP Collator 与序数比较
//! 各跑一遍，对比重名胜者与事件执行序）原样透传给 kernel；`USAGE` 文案由 rust-scan 路
//! 在 `cli.rs` 里逐字复刻，本路不另立文案。

use crate::kernel::{self, KernelOutput};
use crate::symbols::{self, COLLATOR_HEADER, SUMMARY_PREFIX};
use crate::types::{
    absolutize, current_dir, find_repo_root, kotlin_path_text, ErbccError, Outcome, EXIT_OK,
};
use std::path::{Path, PathBuf};

/// `symbols` 的参数面（`Main.kt:177-189`）。
#[derive(Debug, Clone)]
pub struct SymbolsArgs {
    /// 原始 `--corpus` 串：`corpus 不是目录: $corpusDir` 打的是**未绝对化**的原样
    /// （`Main.kt:189`），与 kernel 收到的绝对路径不是一回事。
    pub corpus_raw: String,
    /// 绝对化 + Kotlin `Path.toString()` 口径的语料根（进 kernel 的 `--corpus`）
    pub corpus_root: String,
    /// 绝对化后的 `--json` 落点（None = 用户没要 JSON）
    pub json_out: Option<PathBuf>,
    /// `--collator-check`（`Main.kt:182`）
    pub collator_check: bool,
    /// `--ordinal` → `SymbolConfig.collator = CollatorOption.ORDINAL`（`Main.kt:183`）
    pub ordinal: bool,
}

/// 一次 kernel `symbols` 调用的请求（本路的 seam，测试替身按它落假读数）。
#[derive(Debug, Clone)]
pub struct KernelRequest {
    pub corpus: String,
    /// kernel 写读数的临时档路径（**不是**用户的 `--json` 路径）
    pub readings_json: String,
    pub ordinal: bool,
    pub collator_check: bool,
}

/// 可注入依赖（写法对齐 `cmd_compile::Deps`：函数指针 + `const PRODUCTION`）。
pub struct Deps {
    pub run_kernel: fn(&Path, &KernelRequest, &kernel::EnvFn) -> Outcome<KernelOutput>,
}

/// 生产接线：真起 JVM 子进程。
pub const PRODUCTION: Deps = Deps {
    run_kernel: launch,
};

/// `Main.kt:64` 的 `--collator-check` 释义（比较器自检：ja-JP Collator 与序数比较
/// 各跑一遍，对比重名胜者与事件执行序）——`USAGE` 由 rust-scan 路在 `cli.rs` 里
/// 逐字复刻，本路只按同一语义透传开关，不另立文案。

pub fn execute(args: &[String]) -> Outcome<i32> {
    let cwd = current_dir();
    let repo_root = find_repo_root(&cwd).ok_or_else(|| {
        ErbccError::Io(format!(
            "找不到仓库根（从 {cwd:?} 往上没有 {}）：kernel 子进程的 CWD 无从确定",
            crate::types::MARKER_REL
        ))
    })?;
    let env: &kernel::EnvFn = &kernel::os_env;
    orchestrate(&repo_root, args, env, &PRODUCTION)
}

/// [execute] 的可注入版本（仓库根 + 环境 + kernel seam）：跑全链路并把 stdout 打出去。
pub fn orchestrate(
    repo_root: &Path,
    args: &[String],
    env: &kernel::EnvFn,
    deps: &Deps,
) -> Outcome<i32> {
    let done = pipeline(repo_root, args, env, deps)?;
    print!("{}", done.stdout_text);
    Ok(done.exit_code)
}

/// 一次编排的结果：退出码 + **本应上屏的全文**（不打印，供闸逐字节比基准）。
pub struct Pipeline {
    pub exit_code: i32,
    pub stdout_text: String,
}

/// 全链路（参数 → kernel → 读数 → 序列化/渲染），把上屏文本**返回**而不是打印它。
///
/// 拆出来只为一件事：闸要拿到「用户看到的那一份文本」逐字节比基准，而集成测试里
/// 抓不到 `println`（`cli.rs` 的 `symbols` 分派臂由组长接，本波测试只能直接调本模块）。
pub fn pipeline(
    repo_root: &Path,
    args: &[String],
    env: &kernel::EnvFn,
    deps: &Deps,
) -> Outcome<Pipeline> {
    let parsed = parse_args(args)?;
    let readings_path = readings_tmp_path(&parsed);
    let req = KernelRequest {
        corpus: parsed.corpus_root.clone(),
        readings_json: readings_path.to_string_lossy().into_owned(),
        ordinal: parsed.ordinal,
        collator_check: parsed.collator_check,
    };

    let started = (deps.run_kernel)(repo_root, &req, env)?;
    // 退出码契约与 compile 腿同尺（`Main.kt:67-73` + `kernel::kernel_exit_to_command_code`）：
    // 0 继续；1 = kernel 自己报错（stderr 已逐条写好）→ 原样透出后同码收口；
    // 2 = 我们拼坏了参数 → 报红；其它 = JVM 异常 → 报红。
    match kernel::kernel_exit_to_command_code(started.exit_code, &started.stderr) {
        Ok(EXIT_OK) => {}
        Ok(other) => {
            forward_stderr(&started.stderr);
            remove_readings(&readings_path);
            return Ok(Pipeline { exit_code: other, stdout_text: String::new() });
        }
        Err(e) => {
            forward_stderr(&started.stderr);
            remove_readings(&readings_path);
            return Err(e);
        }
    }

    let readings = std::fs::read_to_string(&readings_path).map_err(|e| {
        ErbccError::Io(format!(
            "读 kernel 的符号表读数 {readings_path:?} 失败: {e}；kernel 退出码 {}、\
             stdout 末行 `{}`、stderr 原文 ↓\n{}",
            started.exit_code,
            last_line(&started.stdout),
            started.stderr
        ))
    })?;
    // 读数档是一次性的：解析完即删（删不掉也不报红，temp 里的孤儿档不影响产物）
    let _ = std::fs::remove_file(&readings_path);

    let report = symbols::parse_kernel_report(&started.stdout)?;
    if report.summary_path.is_none() {
        return Err(ErbccError::Io(format!(
            "kernel stdout 里没有 `{SUMMARY_PREFIX}` 行（`Main.kt:307`）：\
             读数档内容无从核对，拒绝拿别处的读数拼摘要"
        )));
    }
    let json_text = symbols::json_summary(&readings)?;

    // 用户要 JSON 才落盘（`Main.kt:305-306`：先建父目录再写；UTF-8、无 BOM、LF）。
    let summary_line = match &parsed.json_out {
        None => None,
        Some(out) => {
            if let Some(parent) = out.parent() {
                if !parent.as_os_str().is_empty() {
                    std::fs::create_dir_all(parent).map_err(|e| {
                        ErbccError::Io(format!("建 `--json` 父目录 {parent:?} 失败: {e}"))
                    })?;
                }
            }
            std::fs::write(out, json_text.as_bytes())
                .map_err(|e| ErbccError::Io(format!("写 `--json` 产物 {out:?} 失败: {e}")))?;
            Some(format!(
                "{SUMMARY_PREFIX}{}",
                crate::cmd_compile::kotlin_path_string(out)
            ))
        }
    };

    let stdout_text = render_report(&report, parsed.collator_check, summary_line)?;
    Ok(Pipeline { exit_code: EXIT_OK, stdout_text })
}

/// 上屏全文（`Main.kt:195-259/307` 的行序）：透出的读数行 + 自检块 + 摘要行。
///
/// 行终止符按 Rust 口径 `\n`（kernel 侧 `println` 是 `\r\n`）——对拍时剔除终止符，
/// 这条口径写进交付（与第一波三路一致）。
pub fn render_report(
    report: &symbols::KernelReport,
    want_collator_check: bool,
    summary_line: Option<String>,
) -> Outcome<String> {
    let mut lines: Vec<String> = report.forwarded.clone();
    if let Some(c) = &report.collator {
        // `Main.kt:250` 的 `println()`：块前一个空行
        lines.push(String::new());
        lines.extend(c.lines());
    } else if want_collator_check {
        return Err(ErbccError::Io(format!(
            "请求了 `--collator-check`，但 kernel stdout 里没有 `{COLLATOR_HEADER}` 块：\
             读数无从核对，拒绝自己编自检结论（装载序判定权在 JVM）"
        )));
    }
    if let Some(line) = summary_line {
        // `Main.kt:307` 的 `println("\n符号表摘要: …")`：同一个 println 带的前导换行
        lines.push(String::new());
        lines.push(line);
    }
    let mut text = String::new();
    for l in lines {
        text.push_str(&l);
        text.push('\n');
    }
    Ok(text)
}

/// 参数解析（逐项照 `Main.kt:177-189`，含报红文案与判定顺序）。
pub fn parse_args(args: &[String]) -> Outcome<SymbolsArgs> {
    let mut corpus_raw: Option<String> = None;
    let mut json_raw: Option<String> = None;
    let mut collator_check = false;
    let mut ordinal = false;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--corpus" => {
                corpus_raw = Some(
                    args.get(i + 1)
                        .cloned()
                        .ok_or_else(|| ErbccError::Usage("--corpus 需要参数".into()))?,
                );
                i += 1;
            }
            "--json" => {
                json_raw = Some(
                    args.get(i + 1)
                        .cloned()
                        .ok_or_else(|| ErbccError::Usage("--json 需要参数".into()))?,
                );
                i += 1;
            }
            // 无值开关（`Main.kt:182-183`）
            "--collator-check" => collator_check = true,
            "--ordinal" => ordinal = true,
            other => {
                return Err(ErbccError::Usage(format!("无法识别的 symbols 参数: {other}")))
            }
        }
        i += 1;
    }
    let corpus_raw = corpus_raw.ok_or_else(|| ErbccError::Usage("缺少 --corpus 参数".into()))?;
    let corpus_dir = absolutize(Path::new(&corpus_raw));
    if !corpus_dir.is_dir() {
        // `Main.kt:189` 打原始串（未绝对化），逐字复刻；F1：分隔符走单源 [kotlin_path_text]，
        // 与 scan/compile/check/audit 同尺。
        return Err(ErbccError::Usage(format!(
            "corpus 不是目录: {}",
            kotlin_path_text(&corpus_raw)
        )));
    }
    Ok(SymbolsArgs {
        json_out: json_raw.as_ref().map(|v| absolutize(Path::new(v))),
        corpus_root: crate::cmd_compile::kotlin_path_string(&corpus_dir),
        corpus_raw,
        collator_check,
        ordinal,
    })
}

// ---------------------------------------------------------------------------
// kernel 启动器：argv 尾部 + 起进程全部复用 [crate::kernel]（RUST1 收口，
// 契约 §7 硬约束 0 当初留下的那份临时重复已合并）
// ---------------------------------------------------------------------------

/// `symbols` 腿的 **argv 尾部**（子命令名 + 它的参数；前缀由
/// [kernel::main_prefix_argv] 单源提供，与 compile/audit/roundtrip 同一份）。
///
/// 入参口径：`corpus` 与 `readings_json` 都已在 [parse_args]/[pipeline] 里绝对化并
/// 过 Kotlin `Path.toString()`，这里**不再**做路径处理——尾部原样进 argv。
/// 开关顺序（`--collator-check` 在 `--ordinal` 之前）与合并前逐段一致。
pub fn symbols_argv_tail(req: &KernelRequest) -> Vec<std::ffi::OsString> {
    let os = |s: &str| std::ffi::OsString::from(s);
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    tail.push(os("symbols"));
    tail.push(os("--corpus"));
    tail.push(std::ffi::OsString::from(&req.corpus));
    tail.push(os("--json"));
    tail.push(std::ffi::OsString::from(&req.readings_json));
    if req.collator_check {
        tail.push(os("--collator-check"));
    }
    if req.ordinal {
        tail.push(os("--ordinal"));
    }
    tail
}

/// 组装完整 argv（纯函数，闸可直接钉「`--ordinal` 到底传没传下去」）：
/// 固定选项与 classpath/入口类前缀与其余四条腿同尺（[kernel::JVM_OPTS]：`-Xmx6g`
/// + 把 `sun.*.encoding` 钉成 UTF-8 —— 不带后两条时本机 JVM stdout 走 cp936，中文读数行会糊）。
pub fn symbols_argv(classpath: &str, req: &KernelRequest) -> Vec<std::ffi::OsString> {
    let mut argv = kernel::main_prefix_argv(classpath);
    argv.extend(symbols_argv_tail(req));
    argv
}

/// [launch] 的可测内核：只算计划、不起进程（classpath 三档与 `java` 定位走 [kernel::plan_main]）。
pub fn plan(repo_root: &Path, req: &KernelRequest, env: &kernel::EnvFn) -> Outcome<kernel::KernelPlan> {
    kernel::plan_main(repo_root, &symbols_argv_tail(req), env)
}

/// 起 JVM 子进程跑 kernel 的 `symbols`，捕获 stdout/stderr 与退出码、计时。
///
/// 合并后本函数只是 [kernel::run_main] 的一层薄封装（那条入口把「argv 前缀 + classpath
/// 三档 + `java` 定位 + 起进程 + 捕获/计时」收成单源，CWD 固定在仓库根、
/// Windows 下不经 shell 等口径都在那里）；腿名 `"symbols"` 只进报红文案，不进 argv。
pub fn launch(
    repo_root: &Path,
    req: &KernelRequest,
    env: &kernel::EnvFn,
) -> Outcome<KernelOutput> {
    kernel::run_main(repo_root, "symbols", &symbols_argv_tail(req), env)
}

/// 临时读数档的落点：用户给了 `--json` 就放它的同目录（那里必然可写），
/// 否则放系统临时目录。文件名带 pid + 纳秒，避免同机并发互踩。
pub fn readings_tmp_path(parsed: &SymbolsArgs) -> PathBuf {
    let name = format!(
        "erbcc-symbols-readings-{}-{}.json",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(0)
    );
    match parsed.json_out.as_ref().and_then(|p| p.parent()) {
        Some(dir) if !dir.as_os_str().is_empty() => dir.join(name),
        _ => std::env::temp_dir().join(name),
    }
}

fn forward_stderr(stderr: &str) {
    for line in stderr.lines() {
        eprintln!("{line}");
    }
}

fn remove_readings(path: &Path) {
    let _ = std::fs::remove_file(path);
}

fn last_line(stdout: &str) -> String {
    stdout.lines().last().unwrap_or("(stdout 为空)").to_string()
}

// classpath 分隔符（Windows `;`）与拼接由 [kernel] 单源提供，本路不另立口径。
