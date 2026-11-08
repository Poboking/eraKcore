use crate::digest;
use crate::kernel::{self, KernelOutput};
use crate::manifest::{self, ProgramStats};
use crate::types::{
    absolutize, current_dir, erbcc_version, find_repo_root, kotlin_path_text, ErbccError, HashEntry,
    Outcome, EXIT_OK,
};
use std::path::{Path, PathBuf};

/// `erbcc compile` 子命令：参数解析（`--corpus`/`--out`/`--limit`，退出码 0/1/2）+
/// kernel 编排 + 落盘目录 `<out>/erbc.v1/{program.erbc,program.meta,program.stmt.idx,manifest.txt}`。
/// 权威口径 = Kotlin `Main.kt:321-393`（含 `--out` 缺省 `tools/erbcc/erbc-out`、
/// 降级模式警告文案、落盘完成读数行）。
///
/// ## 分工（本路与 rust-manifest 的交界）
///
/// - **产物三件套**（容器 / `program.meta` / `program.stmt.idx`）由 JVM kernel 落盘：
///   语义权威在 core-vm 前端，Rust 不重实现（CONTRACT-RUST1 §0.1）。
/// - **`manifest.txt` 由本函数重算并覆盖**：源条目走 rust-manifest 路的
///   [manifest::source_entries]（`listCorpusFiles` 口径单源，本路不另立规则），产物条目由
///   [artifact_entries] 按 Kotlin 的固定序登记，`compiler-digest` 取 [digest::compiler_digest]，
///   文本由 [manifest::manifest_text] 出，落盘走 [manifest::atomic_write]
///   （CONTRACT-RUST1 §4-C「清单由 B 路函数写」）。
///   kernel 那一趟同样会写一份 `manifest.txt`（Kotlin 的行为，删不掉），本路**只把它当
///   规模读数的来源**（`binary-skipped` 是编译期统计，Rust 侧不许自创口径去重算），
///   然后用自己的计算覆盖它 —— 两档口径一致时闸才成立。
pub struct Deps {
    pub run_kernel: fn(&Path, &str, &Path, Option<u64>, &kernel::EnvFn) -> Outcome<KernelOutput>,
    /// 语料 `source` 条目（rust-manifest 路单源）。
    pub source_entries: fn(&Path) -> Outcome<Vec<HashEntry>>,
    pub sha256_file: fn(&Path) -> Outcome<String>,
    pub container_version: fn(&Path) -> Outcome<u16>,
    pub compiler_digest: fn(&Path) -> Outcome<digest::CompilerDigest>,
    pub manifest_text: fn(
        u16,
        &str,
        &str,
        usize,
        &str,
        Option<u64>,
        &str,
        &[HashEntry],
        &[HashEntry],
        Option<&ProgramStats>,
    ) -> Outcome<String>,
    pub atomic_write: fn(&Path, &[u8]) -> Outcome<()>,
}

/// 生产接线：内容寻址/清单文本全走 rust-manifest 路的真函数（单一口径）。
pub const PRODUCTION: Deps = Deps {
    run_kernel: kernel::run_with_env,
    source_entries: manifest::source_entries,
    sha256_file: digest::sha256_file,
    container_version: manifest::container_version_of,
    compiler_digest: digest::compiler_digest,
    manifest_text: manifest::manifest_text,
    atomic_write: manifest::atomic_write,
};

/// 解析完的 `compile` 参数（`Main.kt:321-339`）。
pub struct CompileArgs {
    /// 原样传入的语料参数（报红文案要用它，Kotlin 的 `corpus 不是目录: $corpusDir`
    /// 打的是**未经绝对化**的原始串，见 `Main.kt:337`）
    pub corpus_raw: String,
    /// 绝对化 + Kotlin 口径规范化后的语料根（进清单 `corpus-root` 行、也递给 kernel）
    pub corpus_root: String,
    /// 绝对化后的产物根目录（kernel 的 `--out`）
    pub out_dir: PathBuf,
    /// `--limit` 请求值（`Main.kt:330-331` 的 Int 口径：非正整数直接报红）
    pub limit: Option<i32>,
}

pub fn execute(args: &[String]) -> Outcome<i32> {
    let repo_root = find_repo_root(&current_dir())
        .ok_or_else(|| ErbccError::Io(format!(
            "找不到仓库根（从 {:?} 往上没有 core-vm 编译器源码标记）：\
             清单的 compiler-digest 无源可算，请在仓库内运行 erbcc",
            current_dir()
        )))?;
    let env: &kernel::EnvFn = &kernel::os_env;
    orchestrate(&repo_root, args, env, &PRODUCTION)
}

/// [execute] 的可注入版本（仓库根 + 环境 + 依赖函数），让编排逻辑与闸在
/// rust-manifest 收口前就能用私有替身跑起来。
pub fn orchestrate(
    repo_root: &Path,
    args: &[String],
    env: &kernel::EnvFn,
    deps: &Deps,
) -> Outcome<i32> {
    let parsed = parse_args(args)?;
    // Main.kt:339 —— 降级模式警告在编译之前打，且打的是**请求值**（不是生效值）。
    if let Some(n) = parsed.limit {
        eprintln!("降级模式：只编译前 {n} 个文件（正式产物必须全量）");
    }

    let started = (deps.run_kernel)(
        repo_root,
        &parsed.corpus_root,
        &parsed.out_dir,
        parsed.limit.map(|v| v as u64),
        env,
    )?;
    match kernel::kernel_exit_to_command_code(started.exit_code, &started.stderr) {
        Ok(0) => {}
        Ok(1) => {
            // 编译失败：Kotlin 已把逐条 `文件:行号` 写到 stderr（`Main.kt:358-361`），
            // 原样透出后以 1 收口，**不落清单**。
            forward_stderr(&started.stderr, parsed.limit);
            return Ok(1);
        }
        Ok(other) => return Ok(other),
        Err(e) => {
            forward_stderr(&started.stderr, parsed.limit);
            return Err(e);
        }
    }

    let artifact_dir = absolutize(&parsed.out_dir).join(crate::types::ERBC_DIR_NAME);
    let manifest_path = artifact_dir.join("manifest.txt");
    let program_path = artifact_dir.join("program.erbc");
    if !program_path.is_file() {
        return Err(ErbccError::Io(format!(
            "kernel 报成功但产物容器不存在：{program_path:?}（`Main.kt:376` 的落盘口径）"
        )));
    }

    // ---- kernel 侧读数：清单文本里的 `binary-skipped` 与 `build-mode`/`file-limit` ----
    // 这三行都是**编译期统计**（CorpusCompile.kt:341-343 / :268 / ErbcArtifacts.kt:263-269），
    // Rust 侧不重算口径，直接从 kernel 刚落的清单里取。
    let kernel_manifest = read_kernel_manifest(&manifest_path)?;
    // `binary-skipped` 在 manifest-format 2 且有 stats 时恒在（`ErbcArtifacts.kt:269`）：缺行 = 清单不是本工具产的。
    let binary_skipped = manifest_u64(&kernel_manifest, "binary-skipped")?.ok_or_else(|| {
        ErbccError::Io(format!(
            "{manifest_path:?} 缺 binary-skipped 行（`ErbcArtifacts.kt:269`：有 stats 必写）——\
             不是本次 kernel 落的清单，拒绝拿别处的读数拼清单"
        ))
    })?;
    let build_mode = manifest_field(&kernel_manifest, "build-mode")?
        .ok_or_else(|| ErbccError::Io(format!("{manifest_path:?} 缺 build-mode 行（清单格式不是 manifest-format 2）")))?;
    let file_limit = manifest_u64(&kernel_manifest, "file-limit").ok().flatten();
    // ---- Rust 侧内容寻址（清单行由这些值组成）----
    let sources = (deps.source_entries)(Path::new(&parsed.corpus_root))?;
    // 生效档与 Rust 侧语料文件集必须同一结论（`CorpusCompile.kt:268` 的判据吃的就是这个文件数）：
    // 不一致就是「两侧文件集不同」，当场报红，不把分歧写进清单。
    if let Some(n) = parsed.limit {
        let expect_limited = n > 0 && (n as u64) < sources.len() as u64;
        match (expect_limited, build_mode.as_str()) {
            (true, "limited") | (false, "full") => {}
            _ => {
                return Err(ErbccError::Io(format!(
                    "受限档结论两侧不一致：--limit {n} × 语料 {} 个文件 ⇒ 期望 {}，\
                     kernel 清单 build-mode {build_mode}",
                    sources.len(),
                    if expect_limited { "limited" } else { "full" }
                )))
            }
        }
    }
    let artifacts = artifact_entries(&artifact_dir, deps.sha256_file)?;
    let container_version = (deps.container_version)(&program_path)?;
    let version = erbcc_version(repo_root)?;
    let digest = (deps.compiler_digest)(repo_root)?;

    // ---- 规模读数：kernel stdout 的 `指令 …` 行（Main.kt:378-380）----
    let stats_line = stdout_line_starting_with(&started.stdout, "指令 ").ok_or_else(|| {
        ErbccError::Io(format!(
            "kernel stdout 里读不到 `Main.kt:378-380` 的规模读数行，拒绝猜数字。kernel stdout 原文 ↓\n{}",
            started.stdout
        ))
    })?;
    let numbers = parse_stats_numbers(stats_line)?;
    let stats = ProgramStats {
        instructions: numbers[0],
        statements: numbers[1],
        functions: numbers[2],
        strings: numbers[3],
        constants: numbers[4],
        expressions: numbers[5],
        global_slots: numbers[6],
        entry_function: numbers[7],
        binary_skipped,
        file_limit,
    };

    let text = (deps.manifest_text)(
        container_version,
        &version,
        &digest.digest,
        digest.file_count,
        &build_mode,
        file_limit,
        &parsed.corpus_root,
        &sources,
        &artifacts,
        Some(&stats),
    )?;
    (deps.atomic_write)(&manifest_path, text.as_bytes())?;

    // ---- 上屏：文案逐行照 `Main.kt:377-391` ----
    // kernel 的 stderr（进度、`#DIM`/装载期告警、失败逐条 `文件:行号`）先原样透出，
    // 再出 stdout 块 —— Kotlin 侧就是「编译期 stderr 全部打完才打落盘块」的顺序（`Main.kt:344-391`）。
    forward_stderr(&started.stderr, parsed.limit);
    // kernel 的 stdout 由编排层负责重出（不转发），故下面出的就是用户看到的那一份。
    println!("=== 落盘完成 ===");
    println!("{stats_line}");
    println!(
        "产物: {}（{} 字节, sha256 {}）",
        kotlin_path_string(&program_path),
        artifacts[0].bytes,
        artifacts[0].sha256
    );
    println!(
        "清单: {}（源文件 {} 条）",
        kotlin_path_string(&manifest_path),
        sources.len()
    );
    if let Some(meta) = artifacts.iter().find(|a| a.rel_path.ends_with("/program.meta")) {
        let meta_path = artifact_dir.join("program.meta");
        println!(
            "侧车: {}（{} 字节, sha256 {}）",
            kotlin_path_string(&meta_path),
            meta.bytes,
            meta.sha256
        );
        let meta_line = stdout_line_starting_with(&started.stdout, "侧车内容: ").ok_or_else(|| {
            ErbccError::Io(format!(
                "产物目录里有 program.meta，但 kernel stdout 没有 `Main.kt:386-390` 的侧车内容行，拒绝猜读数"
            ))
        })?;
        println!("{meta_line}");
    }
    Ok(EXIT_OK)
}

/// 转发 kernel 的 stderr（编译进度、`#DIM` 告警、装载期告警、失败逐条 `文件:行号`）。
///
/// 唯一去重的是「降级模式」那一行：本路已经按 `Main.kt:339` 自己打过一次，kernel 子进程
/// 会再打一次（它是同一个 Kotlin 入口），不滤掉就上屏两遍。
fn forward_stderr(stderr: &str, limit: Option<i32>) {
    for line in forwarded_stderr_lines(stderr, limit) {
        eprintln!("{line}");
    }
}

/// [forward_stderr] 的纯函数体（闸直接测它，不碰真 stderr）。
pub fn forwarded_stderr_lines(stderr: &str, limit: Option<i32>) -> Vec<String> {
    stderr
        .lines()
        .filter(|line| !(limit.is_some() && line.starts_with("降级模式：只编译前 ")))
        .map(|s| s.to_string())
        .collect()
}

/// 参数解析（逐项照 `Main.kt:325-337`，含**报红文案**与判定顺序）。
///
/// 返回的 `Usage` 只带 `错误: ` 前缀之后的正文 —— 前缀由 `cli::run` 统一打
/// （Kotlin 侧是 `err()`，`Main.kt:163-166`）。
pub fn parse_args(args: &[String]) -> Outcome<CompileArgs> {
    let mut corpus_raw: Option<String> = None;
    let mut out_raw = "tools/erbcc/erbc-out".to_string();
    let mut limit: Option<i32> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--corpus" => {
                let v = args.get(i + 1).cloned().ok_or_else(|| ErbccError::Usage("--corpus 需要参数".into()))?;
                corpus_raw = Some(v);
                i += 1;
            }
            "--out" => {
                out_raw = args
                    .get(i + 1)
                    .cloned()
                    .ok_or_else(|| ErbccError::Usage("--out 需要参数".into()))?;
                i += 1;
            }
            "--limit" => {
                let v = args
                    .get(i + 1)
                    .cloned()
                    .ok_or_else(|| ErbccError::Usage("--limit 需要参数".into()))?;
                // Main.kt:330-331 `toIntOrNull()?.takeIf { it > 0 }`：超 Int 范围也算「非正整数」。
                limit = Some(
                    v.parse::<i32>()
                        .ok()
                        .filter(|n| *n > 0)
                        .ok_or_else(|| ErbccError::Usage("--limit 需要正整数".into()))?,
                );
                i += 1;
            }
            other => {
                return Err(ErbccError::Usage(format!("无法识别的 compile 参数: {other}")))
            }
        }
        i += 1;
    }
    let corpus_raw = corpus_raw.ok_or_else(|| ErbccError::Usage("缺少 --corpus 参数".into()))?;
    let corpus_dir = absolutize(Path::new(&corpus_raw));
    if !corpus_dir.is_dir() {
        // Main.kt:337 打的是**未经绝对化**的原始串，逐字复刻；F1：分隔符按 Kotlin 的
        // WindowsPath.toString() 口径归一（单源 [crate::types::kotlin_path_text]），四臂同尺。
        return Err(ErbccError::Usage(format!(
            "corpus 不是目录: {}",
            kotlin_path_text(&corpus_raw)
        )));
    }
    Ok(CompileArgs {
        corpus_root: kotlin_path_string(&corpus_dir),
        out_dir: absolutize(Path::new(&out_raw)),
        corpus_raw,
        limit,
    })
}

/// Kotlin 的 `Path.toString()` 口径（Windows 上分隔符恒反斜杠）。
/// F1 单源：实现已上收到 [crate::types::kotlin_path_string]（`audit`/`symbols`/本臂共用一把尺），
/// 这里只做 re-export，既有调用点与 `tests/collator_check_report.rs` 的引用路径不变。
pub use crate::types::kotlin_path_string;

/// 产物条目表（`ErbcArtifacts.kt:188` 的 `listOfNotNull(artifact, meta, stmtIdx)`
/// = 固定序 `program.erbc` → `program.meta` → `program.stmt.idx`，不含 `manifest.txt`）。
pub fn artifact_entries(artifact_dir: &Path, sha256_file: fn(&Path) -> Outcome<String>) -> Outcome<Vec<HashEntry>> {
    const NAMES: [&str; 3] = ["program.erbc", "program.meta", "program.stmt.idx"];
    let dir_name = crate::types::ERBC_DIR_NAME;
    let mut out = Vec::new();
    for name in NAMES {
        let path = artifact_dir.join(name);
        if !path.is_file() {
            continue;
        }
        let bytes = std::fs::metadata(&path)
            .map_err(|e| ErbccError::Io(format!("读产物属性失败 {path:?}: {e}")))?
            .len();
        out.push(HashEntry {
            rel_path: format!("{dir_name}/{name}"),
            bytes,
            sha256: sha256_file(&path)?,
        });
    }
    if out.is_empty() || !out[0].rel_path.ends_with("program.erbc") {
        return Err(ErbccError::Io(format!(
            "产物目录 {artifact_dir:?} 里没有 program.erbc，清单的 artifacts 表无从登记"
        )));
    }
    Ok(out)
}

fn read_kernel_manifest(path: &Path) -> Outcome<String> {
    std::fs::read_to_string(path).map_err(|e| {
        ErbccError::Io(format!(
            "读不到 kernel 落的清单 {path:?}: {e}；`binary-skipped`/`build-mode` 是编译期统计，\
             Rust 侧无源可算，拒绝自创口径"
        ))
    })
}

/// 清单里 `key value` 行的取值（同一 key 取第一条；行首精确匹配，避免 `file-limit`
/// 命中 `binary-skipped` 这类误配）。
pub fn manifest_field(text: &str, key: &str) -> Outcome<Option<String>> {
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix(key) {
            if rest.starts_with(' ') {
                let v = rest.trim();
                if !v.is_empty() {
                    return Ok(Some(v.to_string()));
                }
            }
        }
    }
    Ok(None)
}

fn manifest_u64(text: &str, key: &str) -> Outcome<Option<u64>> {
    match manifest_field(text, key)? {
        None => Ok(None),
        Some(v) => v
            .parse::<u64>()
            .map(Some)
            .map_err(|e| ErbccError::Io(format!("清单 {key} 行取值 `{v}` 不是非负整数: {e}"))),
    }
}

/// kernel stdout 里某个前缀的第一行（原样返回，供编排层重出）。
pub fn stdout_line_starting_with<'a>(stdout: &'a str, prefix: &str) -> Option<&'a str> {
    stdout.lines().find(|l| l.starts_with(prefix))
}

/// 规模读数行的标签序 = `Main.kt:378-380` 的字段序，也是清单 `program …` 八行的序
/// （`ErbcArtifacts.kt:280-287`）。按标签校验后再取值，禁止「按位置猜数字」。
pub const STATS_LABELS: [&str; 8] = ["指令", "语句", "函数", "串池", "常量", "表达式", "全局槽", "入口"];

/// `指令 1351  语句 1077  函数 45  串池 1  常量 8  表达式 32  全局槽 0  入口 0`
/// → 八个数（顺序 = 上面的标签序）。
pub fn parse_stats_numbers(line: &str) -> Outcome<[u64; 8]> {
    let tokens: Vec<&str> = line.split_whitespace().collect();
    if tokens.len() != STATS_LABELS.len() * 2 {
        return Err(ErbccError::Io(format!(
            "规模读数行不是「标签 数值」× 8 的形状（`Main.kt:378-380`），实为 {} 段：`{line}`",
            tokens.len()
        )));
    }
    let mut out = [0u64; 8];
    for (i, label) in STATS_LABELS.iter().enumerate() {
        if tokens[i * 2] != *label {
            return Err(ErbccError::Io(format!(
                "规模读数第 {} 个标签应是 `{label}`，实为 `{}`（`Main.kt:378-380` 的字段序）",
                i + 1,
                tokens[i * 2]
            )));
        }
        let token = tokens[i * 2 + 1];
        let v = token
            .parse::<i64>()
            .map_err(|e| ErbccError::Io(format!("规模读数 `{label} {token}` 不是整数: {e}")))?;
        // 失败档的 `-1` 哨兵（`CorpusCompile.kt:764-771`）在本路径不该出现：kernel 已退 0。
        if v < 0 {
            return Err(ErbccError::Io(format!(
                "规模读数 `{label}` 出现 -1 哨兵（`CorpusCompile.kt:764-771` 的失败档），但 kernel 退出码是 0"
            )));
        }
        out[i] = v as u64;
    }
    Ok(out)
}
