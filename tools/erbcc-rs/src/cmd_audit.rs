use crate::audit;
use crate::kernel::{self, KernelOutput};
use crate::manifest;
use crate::types::{
    absolutize, current_dir, find_repo_root, kotlin_path_string, kotlin_path_text, ErbccError,
    Outcome, EXIT_FAIL, EXIT_OK,
};
use std::path::{Path, PathBuf};

/// `erbcc audit` 子命令：参数解析 + kernel 桥取读数 + `audit::*` 落 TSV + `--strict` 入闸。
/// 权威口径 = Kotlin `Main.kt:437-528`（参数面与前置报红）与 `:76-87` USAGE 的退出码契约。
///
/// ## 分工（与 rust-kernel 路的 kernel 桥同构）
///
/// - **「什么算未绑定符号」由 JVM 判**：`SymbolLinkAudit.audit`（`SymbolLinkAudit.kt:178-300`）
///   吃 core-vm 的运行期命名空间（`GameSession` 的 CSV 系统变量表 + 全局 `#DIM` 安装 +
///   侧车私有 `#DIM`）与解析期 `SymbolUses` 使用点。这是语义权威面，Rust 侧一个判据都不重实现
///   （CONTRACT-RUST1 §0.1）。
/// - **TSV 由 kernel 落盘，Rust 侧读回来逐字节复算再落一次**：读回来的七列读数
///   （[audit::parse_tsv]）是 Rust 侧唯一的取读数面；[audit::tsv_text] 复算出的文本必须与
///   kernel 落的文件**逐字节相同**，不等即 `Parity` 报红；相等才由本路用自己的原子落盘把
///   同一份字节写回 `--out`（写出层归本车道）。
/// - **`--strict` 的退出码由本路判**：[audit::hard_diff_count] 独立复算「硬差异且未被白名单放行」
///   的条数，据此给退出码，再与 kernel 自己的退出码交叉核对（不等即报红）。
///   `--strict`/`--allow` 同时透传给 kernel：整段报告文本（`硬差异：N 处`、分组明细、
///   `结论：…`）都吃这两个开关（`Main.kt:501-526`），不透传就得在 Rust 侧复刻一遍文案与
///   `printGroups` 的分组排序 —— 那是自创口径，§0.2 禁止。
/// - **`--roundtrip`（CMP-79）走 [orchestrate_roundtrip]**：判据仍在 kernel
///   （`SymbolLinkAudit.roundTrip` + `compareProduct`，`SymbolLinkAudit.kt:467-627`），
///   本路读它对账明细、复算字节、独立数「任一项不等」给退出码。
pub struct Deps {
    /// JVM kernel 桥（audit 腿）。
    pub run_kernel:
        fn(&Path, &str, &Path, &Path, Option<i32>, bool, Option<&Path>, &kernel::EnvFn) -> Outcome<KernelOutput>,
    /// TSV 读数（kernel 落的文件）。
    pub read_file: fn(&Path) -> Outcome<Vec<u8>>,
    /// Rust 侧写出（与 rust-manifest 路同一把原子落盘尺）。
    pub atomic_write: fn(&Path, &[u8]) -> Outcome<()>,
    /// `--allow` 白名单读取（[audit::load_allowlist]）。
    pub load_allowlist: fn(&Path) -> Outcome<Vec<(String, Option<String>, String)>>,
}

/// 生产接线：真 kernel 桥 + 真原子落盘 + 真白名单读取（单一口径）。
pub const PRODUCTION: Deps = Deps {
    run_kernel: kernel::audit_via_jvm,
    read_file: read_bytes,
    atomic_write: manifest::atomic_write,
    load_allowlist: audit::load_allowlist,
};

/// 解析完的 `audit` 参数（`Main.kt:437-473`）。
#[derive(Debug)]
pub struct AuditArgs {
    /// `--corpus` 原样串（`corpus 不是目录:` 那条报红要用它，见 [kotlin_path_text]）
    pub corpus_raw: String,
    /// 绝对化 + Kotlin 口径后的语料根（递给 kernel 的 `--corpus`）
    pub corpus_root: String,
    /// 游戏根：显式 `--game-root` 或「语料根的父目录」（`Main.kt:469`）
    pub game_root: PathBuf,
    /// 未绑定符号 TSV 路径（缺省 `tools/erbcc/symbol-audit/unresolved-symbols.tsv`，`Main.kt:440`）
    pub out: PathBuf,
    /// `--out` 是否显式给过（只有 `--roundtrip` 那条路会区分缺省与显式，`Main.kt:441/476`）
    pub out_given: bool,
    /// `--limit`（`Main.kt:456-457`：非正整数直接报红）
    pub limit: Option<i32>,
    /// `--strict`
    pub strict: bool,
    /// `--allow` 白名单路径
    pub allow: Option<PathBuf>,
    /// `--roundtrip`：CMP-79「落盘后再装载对账」，走 [orchestrate_roundtrip]（`Main.kt:476`）
    pub roundtrip: bool,
    /// `--artifact-dir`：产物落盘目录，**只被 `--roundtrip` 分支消费**（`Main.kt:540-546`）
    pub artifact_dir: Option<PathBuf>,
}

pub fn execute(args: &[String]) -> Outcome<i32> {
    let repo_root = find_repo_root(&current_dir()).ok_or_else(|| {
        ErbccError::Io(format!(
            "找不到仓库根（从 {:?} 往上没有 core-vm 编译器源码标记）：\
             audit 的缺省 TSV 路径与 kernel classpath 都以仓库根为尺，请在仓库内运行 erbcc",
            current_dir()
        ))
    })?;
    let env: &kernel::EnvFn = &kernel::os_env;
    orchestrate(&repo_root, args, env, &PRODUCTION)
}

/// [execute] 的可注入版本（仓库根 + 环境 + 依赖函数），让编排逻辑不依赖真 JVM 也能闸。
pub fn orchestrate(
    repo_root: &Path,
    args: &[String],
    env: &kernel::EnvFn,
    deps: &Deps,
) -> Outcome<i32> {
    let parsed = parse_args(args)?;
    // Main.kt:474 —— 降级警告在**任何分派之前**打（`:476` 的 `--roundtrip` 分派在它后面），
    // 且打的是**请求值**；顺序错了会让 `--roundtrip --limit N` 这一档的 stderr 少一行。
    if let Some(n) = parsed.limit {
        eprintln!("降级模式：只审计前 {n} 个文件（正式闸必须全量）");
    }
    // Main.kt:476 的分派点：`--roundtrip` 走 CMP-79 那条子路（落盘后再装载对账）。
    // `--artifact-dir` 只在 `--roundtrip` 分支里被消费（`Main.kt:540-546`），
    // 所以单独给 `--artifact-dir` 与 Kotlin 一样被忽略（它不进普通 audit 那条 argv）。
    if parsed.roundtrip {
        return orchestrate_roundtrip(repo_root, &parsed, env, &PRODUCTION_RT);
    }

    let allow_abs = parsed.allow.as_deref().map(absolutize);
    // Kotlin `Main.kt:651` + `:679-681`：`--allow` 指向的档**不存在不是错误** —— 打一行提示、按空白
    // 名单继续，且那一行打在 `Path(原文)` 上（相对形态 + `\` 分隔，**不绝对化**）。喂给 kernel 的必须
    // 仍是绝对路径（子进程 CWD 固定在仓库根，传相对会读错档），所以这一行的**归一在透出侧做**：
    // `stderr_head_lines` 就地把 kernel 那一行换成原文形态。
    // 不要改成「编排层自己提前打」——Kotlin 的那一行在 `readAllowList`（审计阶段，`Main.kt:680`）里，
    // 位置在 `CorpusCompile` 的 notes/切片段**之后**；提前打会让它在 Rust 腿排到第 0 行（实测把
    // `cli_surface_diff` 的 c4/③ 两格打红：行序也是 stderr 的一部分）。
    // F2：`--allow` 的字节在**起 kernel 之前**做严格 UTF-8 校验，坏输入退参数错误档、不起 JVM。
    // 这一格原本是「kernel 跑完整份审计 → 本路 load_allowlist 严格解码报 Io ⇒ rc=1」，
    // 而 Kotlin 腿用 `readText()`（REPLACE）有损放过 ⇒ rc=0 并落 12 行 TSV：同一格两侧一个静默
    // 放过、一个延迟硬失败。修向是**收紧 Kotlin**（见 Main.kt 的 readAllowListText），两侧都在
    // 任何产物落盘之前挡下来，本路顺带把校验点提到起 kernel 前 —— 坏白名单不必白花一次全量编译。
    preflight_allowlist(parsed.allow.as_deref())?;
    let started = (deps.run_kernel)(
        repo_root,
        &parsed.corpus_root,
        &parsed.game_root,
        &parsed.out,
        parsed.limit,
        parsed.strict,
        allow_abs.as_deref(),
        env,
    )?;

    // ---- kernel 的 stderr：`降级模式`（本路已打过）/ `审计未通过`（本路按自己的判据补打）
    //      / `编译失败 …` 那一段（要排在 stdout 报告块之后，与 Kotlin 的时序一致）都先摘出来。
    let head = stderr_head_lines(&started.stderr, parsed.limit, parsed.allow.as_deref());
    for line in head {
        eprintln!("{line}");
    }

    if compile_failure_seen(&started.stderr) {
        // Main.kt:486-490：编译失败 ⇒ 报告块只打到「编译：…」就收，TSV 一个字节不落，退 1。
        for line in stdout_lines(&started.stdout) {
            println!("{line}");
        }
        for line in stderr_compile_failure_lines(&started.stderr) {
            eprintln!("{line}");
        }
        if started.exit_code != EXIT_FAIL as i32 {
            return Err(ErbccError::Parity(format!(
                "kernel 报了「编译失败」段落（`Main.kt:487`）却退出 {}，不是契约里的 1",
                started.exit_code
            )));
        }
        return Ok(EXIT_FAIL);
    }

    // 到这里 kernel 只能是 0（审计完成）或 1（--strict 下有硬差异）；其余码说明参数在桥这一段
    // 被拼坏或 JVM 自己退了，交给 kernel 的统一映射报红。
    if started.exit_code != 0 && started.exit_code != 1 {
        return Err(match kernel::kernel_exit_to_command_code(started.exit_code, &started.stderr) {
            Err(e) => e,
            Ok(code) => ErbccError::Io(format!(
                "kernel 退出码 {code} 与 audit 的 0/1 契约不符（`Main.kt:87`）：\
                 且没有报「编译失败」，无法定位失败原因"
            )),
        });
    }

    // ---- 取读数：kernel 落的 TSV ----
    let disk = (deps.read_file)(&parsed.out).map_err(|e| {
        ErbccError::Io(format!(
            "kernel 审计完成（退出码 {}）但读不到明细 TSV {path}：{reason}；\
             `Main.kt:519` 的 writeTsv 应当已经落盘（父目录自己建），拒绝凭空复算",
            started.exit_code,
            path = parsed.out.display(),
            reason = err_message(&e)
        ))
    })?;
    let disk_text = decode_utf8(&disk, &parsed.out)?;
    let findings = audit::parse_tsv(&disk_text)?;
    // ---- Rust 侧写出层复算：表头/列序/行序/转义必须与 kernel 落的字节逐位相同 ----
    let rust_text = audit::tsv_text(&findings)?;
    if rust_text.as_bytes() != disk.as_slice() {
        return Err(ErbccError::Parity(format!(
            "audit TSV 复算与 kernel 落盘不等：{}\n{}",
            first_diff(&disk_text, &rust_text),
            "左边 = kernel（SymbolLinkAudit.kt:89-104）实际落的字节，右边 = Rust 写出层复算的字节"
        )));
    }
    (deps.atomic_write)(&parsed.out, rust_text.as_bytes())?;

    // ---- --allow / --strict 判据（本车道独立复算）----
    let allowed = match allow_abs.as_ref() {
        Some(p) => (deps.load_allowlist)(p)?,
        None => Vec::new(),
    };
    let hard = audit::hard_diff_count(&findings, &allowed)?;
    let code = if parsed.strict && hard > 0 { EXIT_FAIL } else { EXIT_OK };

    // ---- 报告块（kernel 原文透出；`硬差异：N 处` 与 `结论：…` 都吃 --strict/--allow，
    //      这两个开关已透传给 kernel，所以文本与本路的判据同源不同算，正好用来交叉核对）----
    for line in stdout_lines(&started.stdout) {
        println!("{line}");
    }
    if code != started.exit_code {
        return Err(ErbccError::Parity(format!(
            "--strict 判据两侧不等：Rust 侧复算 {hard} 处硬差异（白名单 {} 条）⇒ 退出码 {code}，\
             kernel 自己退 {}",
            allowed.len(),
            started.exit_code
        )));
    }
    if code == EXIT_FAIL {
        // Main.kt:522-524 的文案；条数用**本路复算**的，与上面 kernel 的读数不等就走 Parity 红。
        eprintln!("审计未通过：--strict 下仍有 {hard} 处硬差异。");
    }
    Ok(code)
}

/// 参数解析 + 前置校验（逐项照 `Main.kt:448-473`，含**报红文案**与判定顺序）。
///
/// 返回的 `Usage` 只带 `错误: ` 前缀之后的正文 —— 前缀由 `cli::finish` 统一打
/// （Kotlin 侧是 `err()`，`Main.kt:162-165`，恒退 2）。
pub fn parse_args(args: &[String]) -> Outcome<AuditArgs> {
    let mut corpus_raw: Option<String> = None;
    let mut game_raw: Option<String> = None;
    // Main.kt:440 的缺省 out（相对路径，按当前工作目录解析 = Kotlin 的 `Path(...)` 口径）
    let mut out_raw = "tools/erbcc/symbol-audit/unresolved-symbols.tsv".to_string();
    let mut out_given = false;
    let mut limit: Option<i32> = None;
    let mut strict = false;
    let mut allow_raw: Option<String> = None;
    let mut roundtrip = false;
    let mut artifact_raw: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--corpus" => {
                corpus_raw = Some(next_value(args, &mut i, "--corpus")?);
            }
            "--game-root" => {
                game_raw = Some(next_value(args, &mut i, "--game-root")?);
            }
            "--out" => {
                out_raw = next_value(args, &mut i, "--out")?;
                out_given = true;
            }
            "--limit" => {
                let v = next_value(args, &mut i, "--limit")?;
                // Main.kt:456-457 `toIntOrNull()?.takeIf { it > 0 }`：超 Int 范围也算「非正整数」。
                limit = Some(
                    v.parse::<i32>()
                        .ok()
                        .filter(|n| *n > 0)
                        .ok_or_else(|| ErbccError::Usage("--limit 需要正整数".into()))?,
                );
            }
            "--strict" => {
                strict = true;
                i += 1;
            }
            "--allow" => allow_raw = Some(next_value(args, &mut i, "--allow")?),
            "--roundtrip" => {
                roundtrip = true;
                i += 1;
            }
            "--artifact-dir" => artifact_raw = Some(next_value(args, &mut i, "--artifact-dir")?),
            other => return Err(ErbccError::Usage(format!("无法识别的 audit 参数: {other}"))),
        }
    }

    let corpus_raw = corpus_raw.ok_or_else(|| ErbccError::Usage("缺少 --corpus 参数".into()))?;
    // Main.kt:467 打在**未经绝对化**的 Path 上（Java 的 WindowsPath.toString() 会把 `/` 归成 `\`，
    // 实测：传 C:/x/… 报的是 C:\x\…），故这里只做分隔符归一、不绝对化。
    if !absolutize(Path::new(&corpus_raw)).is_dir() {
        return Err(ErbccError::Usage(format!(
            "corpus 不是目录: {}",
            kotlin_path_text(&corpus_raw)
        )));
    }
    let corpus_dir = absolutize(Path::new(&corpus_raw));
    // Main.kt:469：`gameRoot ?: root.parent`，两者都拿不到才报「无法推断」。
    let game_root = match game_raw.as_deref() {
        Some(raw) => absolutize(Path::new(raw)),
        None => corpus_dir
            .parent()
            .ok_or_else(|| ErbccError::Usage("无法从 corpus 推断游戏根，请显式传 --game-root".into()))?
            .to_path_buf(),
    };
    // Main.kt:471-473 的前置报红：缺 `CSV/` ⇒ 关键字表与角色模板没来源，审计会满屏假红。
    // 这条**必须**在起 kernel 之前挡下来：让它静默跑出一份全红 TSV 等于把闸变成噪音。
    if !game_root.join("CSV").is_dir() {
        return Err(ErbccError::Usage(format!(
            "游戏根缺少 CSV 目录: {}/CSV（关键字表与角色模板都从这里来，缺表时审计会满屏假红）",
            kotlin_path_string(&game_root)
        )));
    }

    Ok(AuditArgs {
        corpus_raw,
        corpus_root: kotlin_path_string(&corpus_dir),
        game_root,
        out: absolutize(Path::new(&out_raw)),
        out_given,
        limit,
        strict,
        allow: allow_raw.as_deref().map(|p| PathBuf::from(p)),
        roundtrip,
        artifact_dir: artifact_raw.as_deref().map(PathBuf::from),
    })
}

/// `--allow` 非法 UTF-8 的报红正文（不含 `错误: ` 前缀）。
///
/// 与 Kotlin `Main.kt` 的 `allowListCharsetRed` **逐字相同**：两侧都是使用者面报红，
/// 全文对拍钉在 `tests/cli_surface_diff.rs` 的 F2 格。`path_text` 取
/// [kotlin_path_text] 口径（Kotlin 那边是 `WindowsPath.toString()`）。
pub fn allow_charset_red(path_text: &str) -> String {
    format!(
        "--allow 白名单不是合法 UTF-8 文本，已拒绝：{path_text}\
         （请把该文件另存为 UTF-8 再跑；这里不做有损解码，否则你以为挂上的是白名单，实际是一堆乱码）"
    )
}

/// F2 的字符集闸：`--allow` 指向的文件必须是合法 UTF-8，否则按**参数错误**（退出码 2）挡下。
///
/// 口径与 Kotlin 侧 [audit::load_allowlist] 的读取端一一对应：
/// - 不是普通文件（不存在 / 是目录）⇒ **不报红**，照旧按空白名单处理，
///   告警仍由 kernel 打（`Main.kt` 的「白名单文件不存在（按空白名单处理）」）；
/// - 合法 UTF-8 ⇒ 一律放行，解析形状（`#` 注释、2/3 列、坏行跳过）完全不动；
/// - 非法 UTF-8 ⇒ `Usage`（`cli::finish` 打 `错误: <正文>` 并退 2，与 Kotlin 的 `err()` 同尺），
///   不起 kernel、不落 TSV、不出报告块。
///
/// 与 [audit::load_allowlist] 里那枚严格解码不冲突：那枚返回 `Io`（退 1），本闸在它之前拦，
/// 只在「编排层直接喂坏档」时才作为第二道；两者互不改变对方的读法。
pub fn preflight_allowlist(path: Option<&Path>) -> Outcome<()> {
    let Some(path) = path else { return Ok(()) };
    if !path.is_file() {
        return Ok(());
    }
    let bytes = std::fs::read(path)
        .map_err(|e| ErbccError::Io(format!("读白名单 {path:?} 失败：{e}")))?;
    if std::str::from_utf8(&bytes).is_err() {
        return Err(ErbccError::Usage(allow_charset_red(&kotlin_path_text(
            &path.to_string_lossy(),
        ))));
    }
    Ok(())
}

/// 取 `--xxx <值>` 的值并把游标推过它（Kotlin 的 `args.getOrNull(++i) ?: err(...)`，
/// 循环尾再 `i++`，`Main.kt:449-462`）。
fn next_value(args: &[String], i: &mut usize, flag: &str) -> Outcome<String> {
    let v = args
        .get(*i + 1)
        .cloned()
        .ok_or_else(|| ErbccError::Usage(format!("{flag} 需要参数")))?;
    *i += 2;
    Ok(v)
}

/// 排在 stdout 报告块**之前**的 kernel stderr：去掉本路自己打过的 `降级模式` 行、
/// 本路按自己判据补打的 `审计未通过` 行，以及要排在报告块**之后**的 `编译失败` 段落。
/// 给了 `allow_raw`（`--allow` 的**原文**）时，把 kernel 透出的 `白名单文件不存在` 一行
/// **就地换成原文形态**（kernel 收的是绝对路径，Kotlin 直跑打在 `Path(原文)` 上）——
/// 只换内容不动位置：行序也是 stderr 的一部分，滤掉再由本路提前打会把它挤到第 0 行。
pub fn stderr_head_lines(stderr: &str, limit: Option<i32>, allow_raw: Option<&Path>) -> Vec<String> {
    let mut out = Vec::new();
    for line in stderr.lines() {
        if is_compile_failure_line(line) {
            break; // 这一段之后全是失败明细，交给 stderr_compile_failure_lines
        }
        if is_strict_red_line(line) || is_duplicate_limit_line(line, limit) {
            continue;
        }
        // kernel 收的是绝对路径，所以它那一行是绝对形态；Kotlin 直跑打在 `Path(原文)` 上 ⇒ 就地换成
        // 原文形态（**保持行位置**，两侧 stderr 才逐字相同）。原文没给、或这行不是缺档提示 ⇒ 原样透出。
        if let Some(raw) = allow_raw.filter(|_| crate::audit::is_allow_missing_warning_line(line)) {
            out.push(crate::audit::allow_missing_warning_text(&raw.to_string_lossy()));
            continue;
        }
        out.push(line.to_string());
    }
    out
}

/// `编译失败 N 条（至多列 50 条）:` 起的那一段（`Main.kt:487-488`，含其后的缩进明细行）。
pub fn stderr_compile_failure_lines(stderr: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut seen = false;
    for line in stderr.lines() {
        if is_compile_failure_line(line) {
            seen = true;
        }
        if seen {
            out.push(line.to_string());
        }
    }
    out
}

fn is_compile_failure_line(line: &str) -> bool {
    line.starts_with("编译失败 ") && line.contains("条（至多列 50 条）:")
}

fn is_strict_red_line(line: &str) -> bool {
    line.starts_with("审计未通过：--strict 下仍有 ")
}

/// kernel 子进程是同一个 Kotlin 入口，`Main.kt:474` 的降级警告它会再打一遍；
/// 本路已经按同一行位置自己打过，所以滤掉 kernel 的那一份（口径同 `cmd_compile`）。
/// 只滤行首那一条：`CorpusCompile` 的留痕行 `  降级模式：只切片前 2/6 个文件` 带缩进，
/// 是另一条读数，必须照常透出。
fn is_duplicate_limit_line(line: &str, limit: Option<i32>) -> bool {
    limit.is_some() && line.starts_with("降级模式：只审计前 ")
}

/// 是否报了「编译失败」段落（`Main.kt:486` 的 `!report.result.ok` 分支）——
/// 这一档 kernel **不落 TSV**（`:519` 在它之后就 return 了），本路也不能去读。
pub fn compile_failure_seen(stderr: &str) -> bool {
    stderr.lines().any(is_compile_failure_line)
}

/// kernel stderr 的第一条非空行（把底层文案嵌进本路报红时用，不整段回显）。
fn first_stderr_line(stderr: &str) -> &str {
    stderr
        .lines()
        .find(|l| !l.trim().is_empty())
        .unwrap_or("(stderr 为空)")
}

/// kernel stdout 的行（报告块原样透出，含 `（N ms）` 这类非确定性读数；
/// 逐字节对拍的只有 TSV，见 tests/audit_tsv_parity.rs 的口径说明）。
pub fn stdout_lines(stdout: &str) -> Vec<String> {
    stdout.lines().map(|s| s.to_string()).collect()
}

fn read_bytes(path: &Path) -> Outcome<Vec<u8>> {
    std::fs::read(path).map_err(|e| ErbccError::Io(format!("{}: {e}", path.display())))
}

/// kernel 的产物按 UTF-8 读（`Files.writeString` 无 charset 参数 ⇒ UTF-8，
/// `SymbolAuditReport.writeTsv`，`SymbolLinkAudit.kt:101`）；解不动就报红，
/// 不做有损解码——那会把「复算 == 原件」变成「两边一起糊」。
fn decode_utf8(bytes: &[u8], path: &Path) -> Outcome<String> {
    String::from_utf8(bytes.to_vec()).map_err(|e| {
        ErbccError::Io(format!(
            "audit TSV {path:?} 不是合法 UTF-8：{e}；Kotlin 侧 writeTsv 恒写 UTF-8"
        ))
    })
}

/// 两侧文本的第一处差异（行号 + 两侧原样），报红时直接能看出是哪一列/哪一行不对。
fn first_diff(a: &str, b: &str) -> String {
    let mut out = String::new();
    for (i, (la, lb)) in a.lines().zip(b.lines()).enumerate() {
        if la != lb {
            out.push_str(&format!(
                "第 {} 行不等：\n  kernel: {la}\n  rust  : {lb}",
                i + 1
            ));
            return out;
        }
    }
    let la = a.lines().count();
    let lb = b.lines().count();
    if la == lb {
        // 行内容全等但字节不等 ⇒ 差在行尾/编码上（例如一边 LF 一边 CRLF）
        return format!(
            "按行切分后 {la} 行全等，但字节不等 ⇒ 差在行终止符或编码上（kernel 文本 {} 字节 / rust 文本 {} 字节）",
            a.as_bytes().len(),
            b.as_bytes().len()
        );
    }
    out.push_str(&format!("行数不等：kernel {la} 行 / rust {lb} 行"));
    out
}

/// 只取消息文本（编排层的报红要把底层错误嵌进自己的文案里，不带 `错误: ` 前缀）。
/// 不往 [crate::types::ErbccError] 上加 inherent 方法：那是第一波收口的共享层，
/// 本车道只借用它已有的形状。
fn err_message(e: &ErbccError) -> String {
    match e {
        ErbccError::NotPorted(name) => (*name).to_string(),
        ErbccError::Usage(m) | ErbccError::Io(m) | ErbccError::Parity(m) => m.clone(),
    }
}

// ---------------------------------------------------------------------------
// audit --roundtrip（CMP-79：落盘后再装载对账）
// ---------------------------------------------------------------------------

/// `--roundtrip` 腿的依赖注入面。与 [Deps] 分开成第二个结构体是**刻意的**：
/// 普通 audit 腿的形状被 `tests/audit_strict.rs` 的 18 例钉着（§7 硬约束 0 的同一纪律），
/// 加字段会让它编译不过。
///
/// 判据分工：「落盘 → 真装载链读回 → 逐字段对拍」全在 kernel
/// （`SymbolLinkAudit.roundTrip`，`SymbolLinkAudit.kt:467-510`；`compareProduct`，`:564-627`），
/// 本路只做三件事：读回明细 TSV 复算字节（[audit::parse_roundtrip_tsv] +
/// [audit::roundtrip_tsv_text]）、独立数「任一项不等」给退出码（`Main.kt:566`）、
/// 与 kernel 的红行交叉核对判据名与条数。
pub struct RtDeps {
    /// JVM kernel 桥（`audit --roundtrip` 腿）。
    pub run_kernel: fn(
        &Path,
        &str,
        &Path,
        &Path,
        Option<&Path>,
        Option<i32>,
        &kernel::EnvFn,
    ) -> Outcome<KernelOutput>,
    /// 明细 TSV 读数（kernel 落的文件）。
    pub read_file: fn(&Path) -> Outcome<Vec<u8>>,
    /// Rust 侧写出（与 rust-manifest 路同一把原子落盘尺）。
    pub atomic_write: fn(&Path, &[u8]) -> Outcome<()>,
}

/// 生产接线：真 kernel 桥（roundtrip 腿）+ 真原子落盘。
pub const PRODUCTION_RT: RtDeps = RtDeps {
    run_kernel: kernel::roundtrip_via_jvm,
    read_file: read_bytes,
    atomic_write: manifest::atomic_write,
};

/// 对账明细的落点（`Main.kt:476` 把 `if (outGiven) out else null` 交给
/// `cmdAuditRoundTrip`，后者缺省用 `tools/erbcc/symbol-audit/roundtrip.tsv`，`:563`）。
///
/// 缺省档按**仓库根**解析而不是按 Rust 进程的 CWD：kernel 子进程的 CWD 固定在仓库根
/// （见 `kernel::roundtrip_via_jvm`），Kotlin 那个相对 `Path(...)` 在那里落地；
/// 本路要把同一个文件读回来，必须落在同一个位置。
pub fn roundtrip_out_path(repo_root: &Path, parsed: &AuditArgs) -> PathBuf {
    if parsed.out_given {
        parsed.out.clone()
    } else {
        repo_root.join("tools/erbcc/symbol-audit/roundtrip.tsv")
    }
}

/// [execute] 的 `--roundtrip` 分支（可注入 kernel 桥 / 读数 / 写出）。
pub fn orchestrate_roundtrip(
    repo_root: &Path,
    parsed: &AuditArgs,
    env: &kernel::EnvFn,
    deps: &RtDeps,
) -> Outcome<i32> {
    let out = roundtrip_out_path(repo_root, parsed);
    // 只在用户给过时透传 --artifact-dir；缺省让 kernel 自己开临时目录并做
    // 「绿了自删、红了保留」（SymbolLinkAudit.kt:484-508），Rust 不另起一套临时盘。
    let artifact_abs = parsed.artifact_dir.as_deref().map(absolutize);
    let started = (deps.run_kernel)(
        repo_root,
        &parsed.corpus_root,
        &parsed.game_root,
        &out,
        artifact_abs.as_deref(),
        parsed.limit,
        env,
    )?;

    // ---- 文本透出：kernel 的 stderr 分两段，顺序与 Kotlin 一致 ----
    // `Main.kt:549-551` 的 `对账失败：…` 在**任何 stdout 之前**（那条路上 stdout 是空的），
    // `Main.kt:567-570` 的 `对账未通过：…` 在**整个报告块之后**；两段之间是 CorpusCompile 的
    // notes 留痕（实测：与 stdout 分属两管，先于报告块）。降级警告由本路在分派前打过
    // （`Main.kt:474`），kernel 那份滤掉。
    for line in rt_stderr_head(&started.stderr, parsed.limit) {
        eprintln!("{line}");
    }
    for line in stdout_lines(&started.stdout) {
        println!("{line}");
    }
    for line in rt_stderr_tail(&started.stderr) {
        eprintln!("{line}");
    }

    // 到这里 kernel 只能是 0（全绿）或 1（任一项不等 / 编译失败）；其余码走统一映射报红。
    if started.exit_code != 0 && started.exit_code != 1 {
        return Err(match kernel::kernel_exit_to_command_code(started.exit_code, &started.stderr) {
            Err(e) => e,
            Ok(code) => ErbccError::Io(format!(
                "kernel 退出码 {code} 与 audit --roundtrip 的 0/1 契约不符（`Main.kt:95`）：\
                 且没有报「对账失败」，无法定位失败原因"
            )),
        });
    }

    if rt_compile_failure_seen(&started.stderr) {
        // Main.kt:547-552：roundTrip 抛 IllegalStateException（编译失败 / 没有内存产物）
        // ⇒ 打一行 `对账失败：…` 就 return 1，明细 TSV 一个字节不落（`:564` 的 writeTsv 在它之后）。
        if started.exit_code != EXIT_FAIL as i32 {
            return Err(ErbccError::Parity(format!(
                "kernel 报了「对账失败」（`Main.kt:550`）却退出 {}，不是契约里的 1",
                started.exit_code
            )));
        }
        return Ok(EXIT_FAIL);
    }

    if !rt_verdict_red_seen(&started.stderr) && started.exit_code == EXIT_FAIL as i32 {
        // kernel 退 1 却既没报「对账失败」也没报「对账未通过」（`Main.kt:549-570` 的两条红行都没有）
        // ⇒ 本路无法定位它红在哪。最常踩到的就是这条：真装载链直接抛（截断的容器 ⇒
        // ErbcDecodeException），而 Kotlin 只 catch IllegalStateException（`Main.kt:549`），
        // 其它异常穿透到 main ⇒ 退出码 1 + 一段栈 + **不落明细**。
        // 这里必须报红：不许凭空复算一份明细，更不许退 0（契约 §0.6 的反面）。
        return Err(ErbccError::Io(format!(
            "kernel 退 1 但两条红行（`对账失败：…` / `对账未通过：…`）都没打：\
             多半是真装载链抛了非 IllegalStateException 的异常（对账的语义是红，不是炸），\
             这一档 kernel 不落明细；kernel 侧 stderr 原文如下 —— {stderr}",
            stderr = first_stderr_line(&started.stderr)
        )));
    }

    // ---- 取读数：kernel 落的对账明细 ----
    let disk = (deps.read_file)(&out).map_err(|e| {
        ErbccError::Io(format!(
            "kernel 对账结束（退出码 {}）但读不到明细 TSV {path}：{reason}；\
             `Main.kt:564` 的 writeTsv 应当已经落盘（父目录自己建），拒绝凭空复算",
            started.exit_code,
            path = out.display(),
            reason = err_message(&e)
        ))
    })?;
    let disk_text = decode_utf8(&disk, &out)?;
    let checks = audit::parse_roundtrip_tsv(&disk_text)?;
    // ---- Rust 侧写出层复算：表头/列序/状态列/detail 转义必须与 kernel 落的字节逐位相同 ----
    let rust_text = audit::roundtrip_tsv_text(&checks)?;
    if rust_text.as_bytes() != disk.as_slice() {
        return Err(ErbccError::Parity(format!(
            "对账明细 TSV 复算与 kernel 落盘不等：{}\n{}",
            first_diff(&disk_text, &rust_text),
            "左边 = kernel（SymbolLinkAudit.kt:148-159）实际落的字节，右边 = Rust 写出层复算的字节"
        )));
    }
    (deps.atomic_write)(&out, rust_text.as_bytes())?;

    // ---- 退出码由本路独立复算（Main.kt:566：任一项不等 ⇒ 1）----
    let red = audit::roundtrip_is_red(&checks);
    let names = audit::roundtrip_mismatch_names(&checks);
    let code = if red { EXIT_FAIL } else { EXIT_OK };

    // ---- 交叉核对一：kernel 的红行（条数 + 判据名与本路同源不同算）----
    if let Some((kernel_count, kernel_names)) = rt_kernel_verdict(&started.stderr) {
        if kernel_count != names.len() || kernel_names != names {
            return Err(ErbccError::Parity(format!(
                "不等项两侧不等：kernel 红行报 {kernel_count} 项 {kernel_names:?}，\
                 Rust 侧复算 {} 项 {names:?}（明细：{}）",
                names.len(),
                out.display()
            )));
        }
    } else if red {
        return Err(ErbccError::Parity(format!(
            "Rust 侧数出 {} 项不等（{names:?}），kernel 却没打 `对账未通过：…` 红行（`Main.kt:567-570`）：\
             两侧判据无法交叉核对，拒绝按红收口",
            names.len()
        )));
    }

    // ---- 交叉核对二：退出码 ----
    if code != started.exit_code {
        return Err(ErbccError::Parity(format!(
            "对账判据两侧不等：Rust 侧复算 {} 项不等 ⇒ 退出码 {code}，kernel 自己退 {}",
            names.len(),
            started.exit_code
        )));
    }
    Ok(code)
}

/// `对账失败：…`（`Main.kt:550`，`roundTrip` 抛 IllegalStateException 的那条路：
/// 编译失败或内存里没有 Program）。
pub fn rt_compile_failure_seen(stderr: &str) -> bool {
    stderr.lines().any(|l| l.starts_with(RT_FAIL_PREFIX))
}

/// `对账未通过：…`（`Main.kt:567`，任一项不等的那条路）。
pub fn rt_verdict_red_seen(stderr: &str) -> bool {
    stderr.lines().any(|l| l.starts_with(RT_RED_PREFIX))
}

const RT_FAIL_PREFIX: &str = "对账失败：";
const RT_RED_PREFIX: &str = "对账未通过：";

/// 排在 stdout 报告块**之前**的 kernel stderr：滤掉本路打过的降级警告，遇到红行/失败行即止
/// （那两行要排在报告块之后或自成一段）。
pub fn rt_stderr_head(stderr: &str, limit: Option<i32>) -> Vec<String> {
    let mut out = Vec::new();
    for line in stderr.lines() {
        if line.starts_with(RT_FAIL_PREFIX) || line.starts_with(RT_RED_PREFIX) {
            break;
        }
        if is_duplicate_limit_line(line, limit) {
            continue;
        }
        out.push(line.to_string());
    }
    out
}

/// 排在 stdout 报告块**之后**的 kernel stderr（从第一条红行/失败行起到结尾，原样透出）。
pub fn rt_stderr_tail(stderr: &str) -> Vec<String> {
    let lines: Vec<&str> = stderr.lines().collect();
    let start = match lines
        .iter()
        .position(|l| l.starts_with(RT_FAIL_PREFIX) || l.starts_with(RT_RED_PREFIX))
    {
        Some(i) => i,
        None => return Vec::new(),
    };
    lines[start..].iter().map(|s| s.to_string()).collect()
}

/// 解析 kernel 的红行 `对账未通过：N 项不等（A、B）；产物保留在 X`
/// （`Main.kt:567-570`）⇒（条数 N，判据名表）。
///
/// 只在形状完全对上时返回 `Some`；形状对不上（Kotlin 改了措辞）返回 `None`，
/// 由调用方按「红档却没有红行」报红（退出码 1 时走 Io，见 [orchestrate_roundtrip] 的注释）。
///
/// `TODO(verify)`：这一行的**真档**没在 CLI 上对拍过 —— CLI 的 `--roundtrip` 每次都先重写产物
/// 再读回（`SymbolLinkAudit.kt:487-495`），「任一项不等」那种红从 CLI 外面造不出来，
/// 只有 `SymbolLinkAudit.verifyArtifacts`（`:522-552`）那条 API 造得出
/// （`tests/roundtrip_parity.rs` 用几十行只调用的 harness 走的就是它）。
/// 措辞与分隔符（`、`、`；产物保留在 `）按 `Main.kt:567-570` 逐字引用；
/// 判据名与条数始终由本路从明细独立复算，红行只当交叉核对面。
pub fn rt_kernel_verdict(stderr: &str) -> Option<(usize, Vec<String>)> {
    let line = stderr.lines().find(|l| l.starts_with(RT_RED_PREFIX))?;
    let rest = &line[RT_RED_PREFIX.len()..];
    // `${report.mismatches.size} 项不等（${names}）；产物保留在 ${dir}`
    let (head, tail) = rest.split_once(" 项不等（")?;
    let count: usize = head.trim().parse().ok()?;
    let close = tail.find('）')?;
    let names_raw = &tail[..close];
    let names: Vec<String> = if names_raw.trim().is_empty() {
        Vec::new()
    } else {
        names_raw.split('、').map(|s| s.trim().to_string()).collect()
    };
    Some((count, names))
}
