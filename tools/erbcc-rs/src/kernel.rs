use crate::types::{absolutize, ErbccError, Outcome};
use std::path::{Path, PathBuf};

/// ERB 解析/代码生成/容器编码的 **JVM kernel 桥**。
///
/// 为什么留 JVM：`ERB 源 → Program → .erbc` 的语义权威在 core-vm 前端
/// （`lexer`/`ExprParser`/`ErbProgramCompiler`/`ErbcWriter`）与 `specs/loader.md` 符号表
/// 装载序（ja-JP Collator，ADR-006）里；Rust 侧不重实现语义，只负责编排与内容寻址，
/// 产物字节必须与纯 Kotlin 路径逐位相同（这是本路的闸）。
///
/// classpath 来源（L3 需实现并写死优先级，取不到就报红、不许猜）：
/// 1. 环境变量 `ERBCC_CLASSPATH`（Gradle 侧写出来的运行时 classpath 文件路径或直接串）；
/// 2. `tools/erbcc/build/install/erbcc/lib`（`installDist` 产物）；
/// 3. 都没有 ⇒ `ErbccError::Io`，消息里说明先跑哪条 Gradle 任务。
/// `java` 可执行文件：`JAVA_HOME/bin/java` 优先，其次 PATH。
pub struct KernelOutput {
    pub exit_code: i32,
    /// JVM 侧 stdout，原样保留供编排层解析规模读数与落盘摘要
    pub stdout: String,
    pub stderr: String,
    pub elapsed_ms: u128,
}

/// Kotlin 侧 CLI 入口（`tools/erbcc/build.gradle.kts:7` 的 `mainClass`，
/// 也是 `compileCorpus` 任务 `doFirst` 里 `setArgs` 的同一个 main，见 `:84`）。
pub const MAIN_CLASS: &str = "com.eratw.port.erbcc.MainKt";

/// classpath 档 1 的环境变量名（rust-gradle 路的产出方按这个名字写）。
pub const CLASSPATH_ENV: &str = "ERBCC_CLASSPATH";

/// classpath 档 2 的仓库内相对目录（`installDist` 的 lib 落点）。
pub const INSTALL_LIB_REL: &str = "tools/erbcc/build/install/erbcc/lib";

/// 取不到 classpath 时要写进报红的 Gradle 任务（口径 = CONTRACT-RUST1 §2 档 2）。
pub const INSTALL_DIST_HINT: &str = "./gradlew --configure-on-demand :tools:erbcc:installDist";

/// 子进程堆上限：与 Gradle 侧 `compileCorpus` 同尺（`tools/erbcc/build.gradle.kts:40`
/// 的 `maxHeapSize = "6g"`；全量编译峰值堆 ≈3.4 GiB）。
/// 两侧不同尺导致产物差异是本路闸抓不到的伪差异，所以在这里写死并让测试钉住。
pub const MAX_HEAP_OPT: &str = "-Xmx6g";

/// 传给 `java` 的固定选项（顺序即 argv 顺序）。
///
/// 后两条是**捕获 stdout/stderr 的前提**：子进程不接控制台时，JDK 17 的
/// `System.out`/`System.err` 按 `sun.*.encoding`（缺省 = 本机 ANSI 码页）编码，
/// 中文读数行会被 GBK 字节流糊掉。本函数把它们钉成 UTF-8，Rust 侧按 UTF-8 解码。
/// 实测（2026-11-01）：带这两条起子进程产出的 `program.erbc`/`program.meta`/
/// `program.stmt.idx` 与 `:tools:erbcc:compileCorpus` 的字节逐位相同
/// （见 tests/compile_parity.rs 的 recorded 基准），即这三条只影响控制台文本。
pub const JVM_OPTS: &[&str] = &[
    MAX_HEAP_OPT,
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
];

/// 环境读取器（测试注入假环境用；生产走 [os_env]）。
///
/// `+ 'static` 是写死的：本类型要进 `const PRODUCTION` 的 fn 指针位，
/// 省略生命周期会让「函数项」比「函数指针」更窄（`E0308: one type is more general…`）。
pub type EnvFn = dyn Fn(&str) -> Option<String> + 'static;

/// 生产环境读取。
pub fn os_env(key: &str) -> Option<String> {
    std::env::var(key).ok()
}

/// classpath 条目分隔符：Windows 是 `;`，Unix 是 `:`（`java -cp` 的口径，
/// 与 `Path::split_paths` 一致）。本仓在 Windows 上跑，写成 `:` 会让整条 classpath
/// 变成一个「文件名里带冒号」的条目，classpath 事实上为空。
pub fn classpath_separator() -> &'static str {
    if cfg!(windows) {
        ";"
    } else {
        ":"
    }
}

/// 多条 classpath 条目拼成 `-cp` 取值。
pub fn join_classpath(entries: &[&str]) -> String {
    let mut out = String::new();
    for (i, e) in entries.iter().enumerate() {
        if i > 0 {
            out.push_str(classpath_separator());
        }
        out.push_str(e);
    }
    out
}

/// classpath 解析结果（`origin` 是给人看的档 1/档 2 说明，报障时要点名用了哪档）。
#[derive(Debug)]
pub struct Classpath {
    pub value: String,
    pub origin: String,
}

/// classpath 三档优先级（骨架文档注释的 L3 实现）。
///
/// 1. `ERBCC_CLASSPATH`：值指向**存在的文件** ⇒ 读文件内容（Gradle 写出来的 classpath 文件，
///    多行按分隔符拼接）；否则把值本身当 classpath 串。空值不算命中，继续往档 2 落。
/// 2. `<repo>/tools/erbcc/build/install/erbcc/lib` 存在 ⇒ 该目录的 jar 通配（星号形式）
///    （`java -cp` 的目录通配会展开该目录下全部 jar，`installDist` 把 main jar + 依赖都放这儿，
///    实测本机 `annotations` / `core-vm` / `erbcc` / `kotlin-stdlib` 四枚）。
/// 3. 都没有 ⇒ [ErbccError::Io]，消息里写清要跑的 Gradle 任务；**不猜路径**。
pub fn resolve_classpath(
    repo_root: &Path,
    env: &EnvFn,
    file_exists: &dyn Fn(&Path) -> bool,
) -> Outcome<Classpath> {
    if let Some(raw) = env(CLASSPATH_ENV) {
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            let as_path = Path::new(trimmed);
            if as_path.is_file() && file_exists(as_path) {
                let text = std::fs::read_to_string(as_path)
                    .map_err(|e| ErbccError::Io(format!("读 {CLASSPATH_ENV} 指向的文件 {trimmed} 失败: {e}")))?;
                let entries: Vec<&str> = text
                    .split(|c| c == '\n' || c == '\r' || c == classpath_separator().chars().next().unwrap())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .collect();
                if entries.is_empty() {
                    return Err(ErbccError::Io(format!(
                        "{CLASSPATH_ENV} 指向的文件 {trimmed} 里没有 classpath 条目"
                    )));
                }
                let joined = join_classpath(&entries);
                return Ok(Classpath {
                    value: joined,
                    origin: format!("档 1：{CLASSPATH_ENV} = 文件 {trimmed}"),
                });
            }
            return Ok(Classpath {
                value: trimmed.to_string(),
                origin: format!("档 1：{CLASSPATH_ENV} = 直接串"),
            });
        }
    }

    let lib_dir = repo_root.join(INSTALL_LIB_REL);
    if lib_dir.is_dir() && file_exists(&lib_dir) {
        return Ok(Classpath {
            value: format!("{}/*", lib_dir.to_string_lossy()),
            origin: format!("档 2：{INSTALL_LIB_REL}（installDist 产物，通配展开 lib 下全部 jar）"),
        });
    }

    Err(ErbccError::Io(format!(
        "取不到 erbcc 的 JVM classpath：环境变量 {CLASSPATH_ENV} 未设置，且 {lib_dir:?} 不存在。\
         先跑 `{INSTALL_DIST_HINT}` 产出档 2，或把 {CLASSPATH_ENV} 设成运行时 classpath\
         （直接串，或一个装着 classpath 的文件路径）；不猜路径。"
    )))
}

/// `java` 可执行文件定位：`JAVA_HOME/bin/java` 优先，其次 PATH 上的 `java`。
/// Windows 上要带 `.exe` 后缀才能被 `CreateProcess` 直接命中（PATH 查找由 `Command` 兜底）。
pub fn resolve_java(repo_root: &Path, env: &EnvFn) -> Outcome<PathBuf> {
    let _ = repo_root;
    if let Some(home) = env("JAVA_HOME") {
        let home = home.trim();
        if !home.is_empty() {
            let exe = Path::new(home).join(format!("bin/java{}", std::env::consts::EXE_SUFFIX));
            if exe.is_file() {
                return Ok(exe);
            }
            return Err(ErbccError::Io(format!(
                "JAVA_HOME 设了但里面没有可执行文件：{exe:?}；要么把它设成真的 JDK 根 \
                 （AGENTS.md §5 的工具链入口），要么清空改用 PATH 上的 java。不猜路径。"
            )));
        }
    }
    // 落 PATH：交给 `Command::new("java")` 做系统级查找（本机没有时 spawn 会报红）。
    Ok(PathBuf::from("java"))
}

/// 组装 argv（不含 java 本体）。拆成纯函数是为了让「`--limit` 到底传没传下去」
/// 「classpath 分隔符对不对」这类判据能在不起子进程的情况下钉住。
///
/// 前缀（固定选项 + `-cp` + 入口类）由 [main_prefix_argv] 单源提供，本函数只拼
/// compile 腿的尾部（[compile_argv_tail]）——五条腿（compile / check 的重编腿 /
/// audit / roundtrip / symbols）共用同一份前缀，差异只在尾部，起进程一律 [run_plan]，
/// 见 [run_main]。
pub fn kernel_argv(
    classpath: &str,
    corpus: &Path,
    out_dir: &Path,
    limit: Option<i32>,
) -> Vec<std::ffi::OsString> {
    let mut argv = main_prefix_argv(classpath);
    argv.extend(compile_argv_tail(corpus, out_dir, limit));
    argv
}

/// compile 腿的 argv **尾部**：`compile --corpus <P> --out <P> [--limit N]`
/// （`Main.kt:325-337` 的解析循环；`--limit` 不给时**不许**凭空加档）。
/// 路径的绝对化由调用方（[plan]）完成。
fn compile_argv_tail(corpus: &Path, out_dir: &Path, limit: Option<i32>) -> Vec<std::ffi::OsString> {
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    tail.push(os("compile"));
    tail.push(os("--corpus"));
    tail.push(corpus.as_os_str().to_os_string());
    tail.push(os("--out"));
    tail.push(out_dir.as_os_str().to_os_string());
    if let Some(n) = limit {
        tail.push(os("--limit"));
        tail.push(os(&n.to_string()));
    }
    tail
}

/// 把 `&str` 变成 argv 段（`std::ffi::OsString` 的 `From` 在这里最不啰嗦）。
fn os(s: &str) -> std::ffi::OsString {
    std::ffi::OsString::from(s)
}

/// 五条腿共用的 **argv 前缀**（顺序即 argv 顺序）：
/// [JVM_OPTS] → `-cp` → classpath → [MAIN_CLASS]。
///
/// 单源的理由：这三段（堆档、classpath 分隔符口径、入口类）**任何一腿漂了都改产物**，
/// 而各腿的闸只钉自己那一份，漂在别处没人看见。腿与腿的唯一区别是**尾部**
/// （子命令名 + 它的参数），由各自的 `*_argv_tail` 组装。
pub fn main_prefix_argv(classpath: &str) -> Vec<std::ffi::OsString> {
    let mut argv: Vec<std::ffi::OsString> = Vec::new();
    argv.extend(JVM_OPTS.iter().map(|s| os(s)));
    argv.push(os("-cp"));
    argv.push(os(classpath));
    argv.push(os(MAIN_CLASS));
    argv
}

/// 一次 kernel 调用的完整计划（java 本体 + argv + 用了哪档 classpath）。
pub struct KernelPlan {
    pub java: PathBuf,
    pub argv: Vec<std::ffi::OsString>,
    pub classpath_origin: String,
}

/// [run_main] 的可测内核：解析 classpath/`java` + 拼前缀 + 接上尾部，不起进程。
///
/// **尾部原样进 argv，一个字节都不动**：路径的绝对化（[absolutize]）由各腿的
/// `plan_*` 在完成——各腿传进来的口径本就不同（compile/audit 传 [Path]，
/// symbols 传已经过 Kotlin `Path.toString()` 口径的字符串）。
pub fn plan_main(repo_root: &Path, tail: &[std::ffi::OsString], env: &EnvFn) -> Outcome<KernelPlan> {
    let cp = resolve_classpath(repo_root, env, &|p: &Path| p.exists())?;
    let java = resolve_java(repo_root, env)?;
    let mut argv = main_prefix_argv(&cp.value);
    argv.extend_from_slice(tail);
    Ok(KernelPlan { java, argv, classpath_origin: cp.origin })
}

/// [compile_via_jvm] 的可测内核：只算计划、不起进程。
pub fn plan(
    repo_root: &Path,
    corpus: &Path,
    out_dir: &Path,
    limit: Option<i32>,
    env: &EnvFn,
) -> Outcome<KernelPlan> {
    plan_main(
        repo_root,
        &compile_argv_tail(&absolutize(corpus), &absolutize(out_dir), limit),
        env,
    )
}

/// JVM 退出码 → erbcc 退出码契约（`Main.kt:67-73`：0 落盘成功 / 1 编译失败 / 2 参数错误）。
///
/// - `0` → `Ok(0)`，编排层继续落清单；
/// - `1` → `Ok(1)`：**这不是桥的错误**，Kotlin 侧已把「文件:行号」逐条写进 stderr，
///   编排层原样透出后直接以 1 收口，不再追加一行 Rust 自己的文案（否则上屏文本与基准不等）；
/// - `2` → `Err(Usage)`：我们传下去的参数是校验过的，出现 2 说明参数在桥这一段被拼坏了，
///   属于编排缺陷，必须当错误报；
/// - 其它（JVM 起不来自己退、OOM 被系统杀、`exitProcess` 之外的异常码）→ `Err(Io)`。
pub fn kernel_exit_to_command_code(exit_code: i32, stderr: &str) -> Outcome<i32> {
    match exit_code {
        0 => Ok(0),
        1 => Ok(1),
        2 => Err(ErbccError::Usage(format!(
            "JVM kernel 报参数错误（退出码 2）：kernel 侧文案 → {}",
            first_line(stderr)
        ))),
        other => Err(ErbccError::Io(format!(
            "JVM kernel 异常退出（退出码 {other}，不在 0/1/2 契约内）：kernel 侧文案 → {}",
            first_line(stderr)
        ))),
    }
}

fn first_line(text: &str) -> &str {
    text.lines().find(|l| !l.trim().is_empty()).unwrap_or("(stderr 为空)")
}

/// 起 JVM 子进程跑 `com.eratw.port.erbcc.MainKt compile --corpus … --out … [--limit N]`，
/// 捕获 stdout/stderr 与退出码、计时。
///
/// 口径要点：
/// - `corpus` 原样透传（编排层已把 [crate::types::absolutize] 过的路径字符串递进来），
///   Kotlin 侧再做 `toAbsolutePath().normalize()`（`Main.kt:338`）；
/// - 子进程 CWD 固定在仓库根：清单的 `compiler-digest` 由「从产物目录/工作目录上溯」定位
///   源码树（`ErbcArtifacts.kt:191-197`），CWD 落在仓库里才能保证两档口径同树；
/// - Windows 下 `std::process::Command` 不经 shell，参数里的空格/中文由 `Command` 自己引用，
///   **不要**再套 `cmd /C`（会把引号语义拆成两层）。
pub fn compile_via_jvm(
    repo_root: &Path,
    corpus: &str,
    out_dir: &Path,
    limit: Option<u64>,
) -> Outcome<KernelOutput> {
    run_with_env(repo_root, corpus, out_dir, limit, &os_env)
}

/// [compile_via_jvm] 的可注入环境版本（测试拿它跑假 classpath / 真 classpath 但不吃进程环境）。
/// `limit` 按 Kotlin 的 Int 口径夹到 `i32`（`Main.kt:330-331` 只接受正整数且不超过 Int 范围）。
pub fn run_with_env(
    repo_root: &Path,
    corpus: &str,
    out_dir: &Path,
    limit: Option<u64>,
    env: &EnvFn,
) -> Outcome<KernelOutput> {
    let limit_i32 = limit.map(|v| v.min(i32::MAX as u64) as i32);
    let corpus_path = absolutize(Path::new(corpus));
    let plan = plan(repo_root, &corpus_path, out_dir, limit_i32, env)?;
    run_plan(repo_root, &plan, "compile")
}

/// **起 JVM 子进程的唯一落点**：按 [KernelPlan] 拉起、捕获 stdout/stderr、计时。
///
/// 三条腿（[run_with_env] / [audit_via_jvm] / [roundtrip_via_jvm]）与 [run_main]
/// 都收敛到这里；`leg` **只进报红文案**（点名是哪条腿起不来），不进 argv——
/// argv 的逐段口径由各腿的 `*_argv` 决定，这里一个字节都不碰。
///
/// - 子进程 CWD 固定在仓库根（清单的 `compiler-digest` 靠「从产物目录/工作目录上溯」
///   定位源码树，`ErbcArtifacts.kt:191-197`，两档口径必须同树）；
/// - Windows 下 `std::process::Command` 不经 shell，参数里的空格/中文由 `Command`
///   自己引用，**不要**再套 `cmd /C`（会把引号语义拆成两层）。
pub fn run_plan(repo_root: &Path, plan: &KernelPlan, leg: &str) -> Outcome<KernelOutput> {
    let started = std::time::Instant::now();
    let output = std::process::Command::new(&plan.java)
        .args(&plan.argv)
        .current_dir(repo_root)
        .output()
        .map_err(|e| {
            ErbccError::Io(format!(
                "起 JVM kernel（{leg} 腿）失败（{} {:?}，classpath {}）：{e}",
                plan.java.display(),
                plan.argv,
                plan.classpath_origin
            ))
        })?;
    let elapsed_ms = started.elapsed().as_millis();
    Ok(KernelOutput {
        exit_code: output.status.code().unwrap_or(-1),
        stdout: decode_utf8(&output.stdout),
        stderr: decode_utf8(&output.stderr),
        elapsed_ms,
    })
}

/// 子进程文本一律按 UTF-8 解（JVM_OPTS 已把 `sun.*.encoding` 钉成 UTF-8）；
/// 解不动时**保留替换字符并原样回传**，让读数行照常上屏，不静默吞。
fn decode_utf8(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

/// 「起一个 JVM 跑 `com.eratw.port.erbcc.MainKt <tail>`」的**单一入口**（RUST1 收口）。
///
/// 存在理由：`cmd_symbols` 第一波为了不与 rust-audit 路抢 `kernel.rs`
/// （契约 §7 硬约束 0）自带了一套同构启动器；本节收口后那条腿只交出 **自己的尾部**，
/// classpath 三档、`java` 定位、argv 前缀、起进程/捕获/计时全部单源在此。
/// compile / check（重编腿）/ audit / roundtrip / symbols 五条腿都走这一形状：
/// 有 `Path` 口径要绝对化的腿把 [plan_main] 包一层自己的 `plan_*`
/// （[plan] / [plan_audit] / [plan_roundtrip]，测试钉着这三个名字），起进程那一步一律 [run_plan]。
///
/// 口径要点：
/// - `tail` **原样**拼到 [main_prefix_argv] 之后，一个字节不改：路径绝对化（[absolutize]）
///   由调用方完成——各腿传进来的口径本就不同；
/// - **退出码不在这里映射**：0/1/2 的契约由各腿自己用 [kernel_exit_to_command_code] 判，
///   编排层的上屏文案不掺进桥里；
/// - `leg` 只用于报红点名（例：`"symbols"`），**不进 argv**。
pub fn run_main(
    repo_root: &Path,
    leg: &str,
    tail: &[std::ffi::OsString],
    env: &EnvFn,
) -> Outcome<KernelOutput> {
    let plan = plan_main(repo_root, tail, env)?;
    run_plan(repo_root, &plan, leg)
}

// ---------------------------------------------------------------------------
// audit 腿（rust-audit 车道，CONTRACT-RUST1 §7）：以下全部是 **append**，
// 上面第一波收口的 `resolve_classpath`/`compile_via_jvm`/`KernelOutput`/`kernel_argv`
// 的形状一个字节没动（rust-kernel 的 29 例闸钉着它们）。
// ---------------------------------------------------------------------------

/// `audit` 腿的 argv（不含 java 本体）。与 [kernel_argv] 同构、单独成函数，
/// 是为了让第一波收口的 compile 形状保持冻结（§7 硬约束 0：只许 append）。
///
/// 参数面逐项照 Kotlin `Main.kt:449-462` 的 `cmdAudit` 解析循环：
/// `--corpus` / `--game-root` / `--out` / `--limit` / `--strict` / `--allow`。
/// `--roundtrip` 与 `--artifact-dir`（`Main.kt:460-461`）走**另一条 argv**
/// （[roundtrip_kernel_argv]）：Kotlin 在 `Main.kt:476` 就分派到
/// `cmdAuditRoundTrip`，`--strict`/`--allow` 那条路一个字节都不消费它们，反之亦然。
pub fn audit_kernel_argv(
    classpath: &str,
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
) -> Vec<std::ffi::OsString> {
    let mut argv = main_prefix_argv(classpath);
    argv.extend(audit_argv_tail(corpus, game_root, out, limit, strict, allow));
    argv
}

/// audit 腿的 argv **尾部**：`audit --corpus <P> --game-root <P> --out <P> [--limit N]
/// [--strict] [--allow <P>]`。绝对化由调用方（[plan_audit]）完成。
fn audit_argv_tail(
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
) -> Vec<std::ffi::OsString> {
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    tail.push(os("audit"));
    tail.push(os("--corpus"));
    tail.push(corpus.as_os_str().to_os_string());
    tail.push(os("--game-root"));
    tail.push(game_root.as_os_str().to_os_string());
    tail.push(os("--out"));
    tail.push(out.as_os_str().to_os_string());
    if let Some(n) = limit {
        tail.push(os("--limit"));
        tail.push(os(&n.to_string()));
    }
    if strict {
        tail.push(os("--strict"));
    }
    if let Some(a) = allow {
        tail.push(os("--allow"));
        tail.push(a.as_os_str().to_os_string());
    }
    tail
}

/// [audit_via_jvm] 的可测内核：只算计划、不起进程（与 [plan] 同一套 classpath 三档优先级）。
pub fn plan_audit(
    repo_root: &Path,
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
    env: &EnvFn,
) -> Outcome<KernelPlan> {
    let allow_abs = allow.map(absolutize);
    plan_main(
        repo_root,
        &audit_argv_tail(
            &absolutize(corpus),
            &absolutize(game_root),
            &absolutize(out),
            limit,
            strict,
            allow_abs.as_deref(),
        ),
        env,
    )
}

/// 起 JVM 子进程跑 `com.eratw.port.erbcc.MainKt audit --corpus … --game-root … --out … [选项]`，
/// 捕获 stdout/stderr 与退出码、计时。
///
/// 「什么算未绑定符号」的判定权**全在 kernel**（core-vm 运行期命名空间 × `SymbolUses` 使用点，
/// `SymbolLinkAudit.kt:178-300`）；本函数只把参数透下去、把文本捞回来。TSV 由 kernel
/// 自己按 `SymbolAuditReport.writeTsv`（`:89-104`）落盘，编排层读它取读数。
///
/// `--strict` 与 `--allow` 也透传：Kotlin 侧的整段报告文本（`硬差异：N 处`、分组明细、
/// `审计未通过：…`、`结论：…`）都吃这两个开关，不透传就得在 Rust 侧复刻一遍文案与分组排序
/// —— 那是「自创口径」，契约 §0.2 禁止。编排层再用 [crate::audit::hard_diff_count] 独立复算
/// 同一个判据，与 kernel 的退出码交叉核对（不等即报红），退出码由 Rust 侧的判据给。
pub fn audit_via_jvm(
    repo_root: &Path,
    corpus: &str,
    game_root: &Path,
    out: &Path,
    limit: Option<i32>,
    strict: bool,
    allow: Option<&Path>,
    env: &EnvFn,
) -> Outcome<KernelOutput> {
    let corpus_path = absolutize(Path::new(corpus));
    let plan = plan_audit(
        repo_root,
        &corpus_path,
        game_root,
        out,
        limit,
        strict,
        allow,
        env,
    )?;
    run_plan(repo_root, &plan, "audit")
}

// ---------------------------------------------------------------------------
// audit --roundtrip 腿（CMP-79，rust-roundtrip 车道，CONTRACT-RUST1 §7 硬约束 0）：
// 以下同样是 **append**，上面收口的 audit/compile 两腿一个字节没动。
// ---------------------------------------------------------------------------

/// `audit --roundtrip` 腿的 argv（不含 java 本体）。
///
/// 参数面逐项照 Kotlin `Main.kt:449-462` 的解析循环 + `:476` 的分派：
/// `audit --corpus … --game-root … --out … --roundtrip [--artifact-dir D] [--limit N]`。
///
/// 口径要点（都有行号）：
/// - **`--out` 恒显式给**：Kotlin 侧的缺省值是 `cmdAuditRoundTrip` 自己兜的
///   `tools/erbcc/symbol-audit/roundtrip.tsv`（`Main.kt:563`），编排层已经把它按**仓库根**
///   解析成绝对路径再传下来（kernel 子进程的 CWD 就是仓库根，两处落点相同）；
///   不传 `--out` 会让 kernel 把它当**相对 kernel CWD** 的路径，与编排层读回来的路径不可比。
/// - **`--artifact-dir` 只在用户给过时才透传**：缺省时让 kernel 自己
///   `Files.createTempDirectory("erbcc-roundtrip-")`（`SymbolLinkAudit.kt:485`），
///   「绿了自删、红了保留」由它自己做（`:505/:508`）—— 那是读侧的既有语义，Rust 侧
///   另起一个临时目录会让 stdout 的 `产物：` 行与纯 Kotlin 腿不可比（契约 §0.2 不自创口径）。
/// - `--strict` / `--allow` **不进这条 argv**：`Main.kt:476` 的分派在它们被消费之前，
///   `cmdAuditRoundTrip` 全程不读这两个开关（成败判据是「任一项不等」，`Main.kt:95`）。
pub fn roundtrip_kernel_argv(
    classpath: &str,
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    artifact_dir: Option<&Path>,
    limit: Option<i32>,
) -> Vec<std::ffi::OsString> {
    let mut argv = main_prefix_argv(classpath);
    argv.extend(roundtrip_argv_tail(corpus, game_root, out, artifact_dir, limit));
    argv
}

/// roundtrip 腿的 argv **尾部**：`audit --corpus <P> --game-root <P> --out <P> --roundtrip
/// [--artifact-dir <P>] [--limit N]`。绝对化由调用方（[plan_roundtrip]）完成。
fn roundtrip_argv_tail(
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    artifact_dir: Option<&Path>,
    limit: Option<i32>,
) -> Vec<std::ffi::OsString> {
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    tail.push(os("audit"));
    tail.push(os("--corpus"));
    tail.push(corpus.as_os_str().to_os_string());
    tail.push(os("--game-root"));
    tail.push(game_root.as_os_str().to_os_string());
    tail.push(os("--out"));
    tail.push(out.as_os_str().to_os_string());
    tail.push(os("--roundtrip"));
    if let Some(dir) = artifact_dir {
        tail.push(os("--artifact-dir"));
        tail.push(dir.as_os_str().to_os_string());
    }
    if let Some(n) = limit {
        tail.push(os("--limit"));
        tail.push(os(&n.to_string()));
    }
    tail
}

/// [roundtrip_via_jvm] 的可测内核：只算计划、不起进程（classpath 三档优先级与 [plan]/[plan_audit] 同源）。
pub fn plan_roundtrip(
    repo_root: &Path,
    corpus: &Path,
    game_root: &Path,
    out: &Path,
    artifact_dir: Option<&Path>,
    limit: Option<i32>,
    env: &EnvFn,
) -> Outcome<KernelPlan> {
    let artifact_abs = artifact_dir.map(absolutize);
    plan_main(
        repo_root,
        &roundtrip_argv_tail(
            &absolutize(corpus),
            &absolutize(game_root),
            &absolutize(out),
            artifact_abs.as_deref(),
            limit,
        ),
        env,
    )
}

/// 起 JVM 子进程跑 `com.eratw.port.erbcc.MainKt audit --corpus … --game-root … --out …
/// --roundtrip [--artifact-dir D] [--limit N]`，捕获 stdout/stderr 与退出码、计时。
///
/// **对账判据全在 kernel**：`SymbolLinkAudit.roundTrip`（`SymbolLinkAudit.kt:467-510`）做
/// 「编译 → 落盘 → `ProgramLoader.loadFile` + `BootMetadata.read` 读回 → `compareProduct`
/// 逐字段对拍（`:564-627`）」，Rust 侧一个判据都不重实现（契约 §0.2/§0.1）。本函数只把参数
/// 透下去、把文本捞回来；明细 TSV 由 kernel 按 `RoundTripReport.writeTsv`
/// （`SymbolLinkAudit.kt:148-159`）落盘，编排层读它取读数并独立复算退出码。
pub fn roundtrip_via_jvm(
    repo_root: &Path,
    corpus: &str,
    game_root: &Path,
    out: &Path,
    artifact_dir: Option<&Path>,
    limit: Option<i32>,
    env: &EnvFn,
) -> Outcome<KernelOutput> {
    let corpus_path = absolutize(Path::new(corpus));
    let plan = plan_roundtrip(
        repo_root,
        &corpus_path,
        game_root,
        out,
        artifact_dir,
        limit,
        env,
    )?;
    run_plan(repo_root, &plan, "audit --roundtrip")
}

// ---------------------------------------------------------------------------
// check 腿的**深判据委托**（rust-meta-parity）：以下全部是 append，
// 上面收口的 compile / audit / roundtrip 三腿与 run_plan 一个字节没动。
//
// 为什么要有这条腿：`program.meta` 的判据只有一处权威 —— core-vm 的
// `BootMetadata.read`（`core-vm/src/main/kotlin/com/eratw/port/core/loader/BootMetadata.kt:414-426`，
// 全文两趟状态机 `:1382-1818`）。Kotlin 的 `erbcc check` 在
// `tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/compile/ArtifactFreshness.kt:337`
// 调它，读不过就折成 `META_UNREADABLE`（`:344-349`）、读过了才比 `program-sha256`
// 折成 `META_STALE`（`:338-343`）。Rust 的 `freshness::read_meta_head` 只解头部三行
// （它自己的 KDoc 就写了这条诚实声明），于是「头部三行之后坏掉」的侧车 Rust 放行、
// Kotlin 拒 —— `tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv` 量出 60 档。
//
// 修法是**委托**而不是重写：把 Kotlin 的 `check` 当子进程真跑一遍（`Main.kt:406-423`），
// 只取回它 `META_*` 三条 kind 的结论，其余判据一律丢弃（头层/侧车结构/清单仍由 Rust 自己算，
// 一条不放宽）。Rust 侧不写第二台侧车状态机 ⇒ 不会有第二实现方漂移。
// ---------------------------------------------------------------------------

/// `check` 腿的 argv 尾部：`check --corpus <P> --out <P>`。
///
/// 参数面逐项照 Kotlin `Main.kt:409-419`（`cmdCheck` 只认这两个开关，`--corpus` 必填）；
/// 两个路径的绝对化由 [plan_check] 完成（与 [compile_argv_tail] 同一口径）。
fn check_argv_tail(corpus: &Path, out_dir: &Path) -> Vec<std::ffi::OsString> {
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    tail.push(os("check"));
    tail.push(os("--corpus"));
    tail.push(corpus.as_os_str().to_os_string());
    tail.push(os("--out"));
    tail.push(out_dir.as_os_str().to_os_string());
    tail
}

/// `check` 腿的完整 argv（不含 java 本体）：与 [kernel_argv] 同构、单独成函数，
/// 理由是 §7 硬约束 0（第一波收口的形状只许 append）。
pub fn check_kernel_argv(
    classpath: &str,
    corpus: &Path,
    out_dir: &Path,
) -> Vec<std::ffi::OsString> {
    let mut argv = main_prefix_argv(classpath);
    argv.extend(check_argv_tail(corpus, out_dir));
    argv
}

/// [check_via_jvm] 的可测内核：只算计划、不起进程（classpath 三档优先级与 [plan] 同源）。
pub fn plan_check(
    repo_root: &Path,
    corpus: &Path,
    out_dir: &Path,
    env: &EnvFn,
) -> Outcome<KernelPlan> {
    plan_main(
        repo_root,
        &check_argv_tail(&absolutize(corpus), &absolutize(out_dir)),
        env,
    )
}

/// 起 JVM 子进程跑 `com.eratw.port.erbcc.MainKt check --corpus … --out …`，
/// 把 stdout/stderr 与退出码、计时捞回来（本 crate 唯一的进程落点仍是 [run_plan]）。
pub fn check_via_jvm(
    repo_root: &Path,
    corpus: &Path,
    out_dir: &Path,
    env: &EnvFn,
) -> Outcome<KernelOutput> {
    let plan = plan_check(repo_root, corpus, out_dir, env)?;
    run_plan(repo_root, &plan, "check")
}

/// Kotlin `erbcc check` 的 stdout 里那**逐条 issue 原文**（不含两空格缩进）。
///
/// 形状 = `Main.kt:421` 的 `println(report.summary())` → `ArtifactFreshness.kt:89-95` 的
/// `summary()`：有问题时首行 `"<outDir>: N 条新鲜度问题"`，其后每行**两个空格**前缀一条
/// `"$kind: $detail"`（`ArtifactFreshness.kt:72`）；全对齐时只有一行、无任何缩进行。
/// 首行带 `outDir`（含冒号与反斜杠），所以只认「行首恰好两个空格」这一条，别按行号取。
pub fn kotlin_check_issue_lines(stdout: &str) -> Vec<String> {
    stdout
        .lines()
        .filter(|l| l.starts_with("  "))
        .map(|l| l.trim_start().to_string())
        .collect()
}

/// Kotlin `check` 的 stdout 是否**确实是一份新鲜度报告**（而不是 USAGE / 异常栈 / 空输出）。
///
/// 委托腿拿不到这个形状就不许当「深判据没意见」放行 —— 假绿比假红贵（契约 §0.2/§0.6）。
pub fn is_kotlin_check_report(stdout: &str) -> bool {
    let first = stdout.lines().find(|l| !l.trim().is_empty()).unwrap_or("");
    first.contains("条新鲜度问题") || first.contains("产物与语料/编译器一致")
}

