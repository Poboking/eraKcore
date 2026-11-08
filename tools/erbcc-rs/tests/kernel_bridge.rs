//! rust-kernel 路：JVM kernel 桥 + `compile`/`check` 编排的契约闸。
//!
//! 钉的是 Kotlin 侧的**编排契约**（不是语义）：
//! - classpath 三档优先级与取不到时的报红文案（`kernel.rs` 文档注释 + CONTRACT-RUST1 §4-C）；
//! - `java` 定位（`JAVA_HOME/bin/java` 优先，其次 PATH）；
//! - JVM 退出码 → erbcc 退出码 0/1/2（`Main.kt:67-73` 的 USAGE 权威描述、`:358-361` 失败档）；
//! - `--limit` 降级档经过桥传到 kernel，并让清单落回 `build-mode limited` + `file-limit N`
//!   （`Main.kt:330-339`、`CorpusCompile.kt:268`、`ErbcArtifacts.kt:263-264`）；
//! - 参数报红文案逐字（`Main.kt:325-337`、`:409-419`）；
//! - `check` 把 `freshness::check` 的结论原样折成退出码（`Main.kt:420-422`）。
//!
//! 依赖 rust-manifest 路的 `digest::*`/`manifest::*`/`freshness::check` 已收口，故本文件的
//! 三条端到端用例走 `cmd_compile::PRODUCTION`（真 JVM + 真清单函数）；`check` 的判式本身
//! 归它的 `freshness_parity` 闸，本路只注入 `Report` 验退出码这一层接线。
//! 产物四件套与 Kotlin 逐字节相同 = `tests/compile_parity.rs`。

use erbcc_rs::cmd_check;
use erbcc_rs::cmd_compile::{self, PRODUCTION};
use erbcc_rs::freshness::{Issue, Report};
use erbcc_rs::kernel;
use erbcc_rs::manifest::{self, ProgramStats};
use erbcc_rs::types::{ErbccError, Outcome};
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------
// 测试装置
// ---------------------------------------------------------------------------

mod fixture {
    use super::*;

    pub fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("erbcc-rs-kernel-{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("建临时目录");
        dir
    }

    pub fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let owned: Vec<(String, String)> = pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect();
        move |key: &str| owned.iter().find(|(k, _)| k == key).map(|(_, v)| v.clone())
    }

    /// 真存在的语料目录（仓库自带的 fixtures，不吃环境变量）。
    pub fn real_corpus() -> PathBuf {
        repo_root().join("fixtures/golden")
    }

    /// 闸用小语料：`fixtures/golden` 的 13 个 `GOLDEN_*.ERB` 复制成 `PARITY_*.ERB`。
    ///
    /// 为什么要改名：`CorpusCompile.kt:198` 把 `GOLDEN_` 前缀当「金标采集副本」排除在编译
    /// 输入之外 —— 直接拿 `fixtures/golden` 当语料会编出**空文件集**（实测 2026-11-01：
    /// `编译：文件 0`，空档还退 1「入口函数下标越界」），闸就变成空转。改名后
    /// 13 文件 / 43 切片 / 157 KB 容器，一趟约 1 秒。
    ///
    /// 布局照 Gradle 侧 `compileCorpus` 的输入声明（`tools/erbcc/build.gradle.kts:68-75`）：
    /// `<gameRoot>/ERB` + `<gameRoot>/CSV`（CSV 目录不存在时 Gradle 直接拒任务）。
    /// 复制是幂等的：每次调用都重新覆盖，语料本体永远等于 `fixtures/golden`。
    pub fn ensure_parity_corpus(tag: &str) -> PathBuf {
        let root = repo_root();
        // 每条用例一份语料目录：并行跑时「A 在覆盖副本、B 在读」会在 Windows 上撞
        // 文件占用（os error 32）。目录只差个后缀，产物字节与目录名无关（见 compile_parity 的读数）。
        let game = root.join(format!("tools/erbcc-rs/build/parity-game-{tag}"));
        let erb = game.join("ERB");
        std::fs::create_dir_all(&erb).expect("建 parity 语料目录");
        std::fs::create_dir_all(game.join("CSV")).expect("建 parity CSV 目录");
        let names = [
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
        for name in names {
            let src = root.join("fixtures/golden").join(name);
            let dst = erb.join(name.replace("GOLDEN_", "PARITY_"));
            std::fs::copy(&src, &dst).expect("复制金标 fixture 进 parity 语料");
        }
        assert_eq!(names.len(), 13);
        erb
    }
}

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&erbcc_rs::types::current_dir())
        .expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

/// 走真 JVM kernel 的 `compile`（`cmd_compile::PRODUCTION`：真 kernel 桥 + 真清单函数）。
fn compile_via_production(corpus: &Path, out: &Path, limit: Option<&str>) -> Outcome<i32> {
    let env: &kernel::EnvFn = &kernel::os_env;
    let mut args = vec![
        "--corpus".to_string(),
        corpus.to_string_lossy().into_owned(),
        "--out".to_string(),
        out.to_string_lossy().into_owned(),
    ];
    if let Some(n) = limit {
        args.push("--limit".to_string());
        args.push(n.to_string());
    }
    cmd_compile::orchestrate(&repo_root(), &args, env, &PRODUCTION)
}

// ---------------------------------------------------------------------------
// classpath 三档优先级
// ---------------------------------------------------------------------------

#[test]
fn classpath_档1_直接串原样透传() {
    let cp_value = "C:/x/erbcc.jar;C:/x/core-vm.jar";
    let env = fixture::env(&[("ERBCC_CLASSPATH", cp_value)]);
    let cp = kernel::resolve_classpath(&repo_root(), &env, &|p: &Path| p.exists()).expect("档 1 应命中");
    assert_eq!(cp.value, cp_value);
    assert!(cp.origin.contains("档 1"), "origin 要写清用了哪档：{}", cp.origin);
}

#[test]
fn classpath_档1_指向文件时读其内容并按分隔符拼接() {
    let dir = fixture::temp_dir("cp-file");
    let file = dir.join("runtime-classpath.txt");
    std::fs::write(
        &file,
        format!("C:/a/erbcc.jar{}\nC:/a/core-vm.jar\n", kernel::classpath_separator()),
    )
    .unwrap();
    let path_str = file.to_string_lossy().into_owned();
    let env = fixture::env(&[("ERBCC_CLASSPATH", &path_str)]);
    let cp = kernel::resolve_classpath(&repo_root(), &env, &|p: &Path| p.exists()).expect("档 1 文件应命中");
    assert_eq!(
        cp.value,
        format!("C:/a/erbcc.jar{}C:/a/core-vm.jar", kernel::classpath_separator())
    );
    assert!(cp.origin.contains("文件"), "要写明是 classpath 文件：{}", cp.origin);
}

#[test]
fn classpath_档1_优先于档2() {
    let root = fixture::temp_dir("cp-tier1");
    // 两档都在：档 1 必须赢（优先级写死在 kernel.rs 文档注释里）。
    std::fs::create_dir_all(root.join(kernel::INSTALL_LIB_REL)).unwrap();
    let env = fixture::env(&[("ERBCC_CLASSPATH", "C:/only/this.jar")]);
    let cp = kernel::resolve_classpath(&root, &env, &|p: &Path| p.exists()).unwrap();
    assert_eq!(cp.value, "C:/only/this.jar");
    assert!(!cp.value.contains(kernel::INSTALL_LIB_REL));
}

#[test]
fn classpath_档1空值退化到档2_installlib() {
    let root = fixture::temp_dir("cp-tier2");
    let lib = root.join(kernel::INSTALL_LIB_REL);
    std::fs::create_dir_all(&lib).unwrap();
    let env = fixture::env(&[("ERBCC_CLASSPATH", "   ")]);
    let cp = kernel::resolve_classpath(&root, &env, &|p: &Path| p.exists()).unwrap();
    assert_eq!(cp.value, format!("{}/*", lib.to_string_lossy()));
    assert!(cp.origin.contains("档 2"), "origin 要写清用了哪档：{}", cp.origin);
}

#[test]
fn classpath_两档都缺时报红点名gradle任务且不猜路径() {
    let root = fixture::temp_dir("cp-none");
    let env = fixture::env(&[]);
    let err = kernel::resolve_classpath(&root, &env, &|p: &Path| p.exists())
        .expect_err("两档都缺必须红，不许猜 classpath");
    let msg = match err {
        ErbccError::Io(m) => m,
        other => panic!("取不到 classpath 应报 Io（退出码 1），实际 {other:?}"),
    };
    for needle in [
        "ERBCC_CLASSPATH",
        kernel::INSTALL_LIB_REL,
        ":tools:erbcc:installDist",
        "不猜路径",
    ] {
        assert!(msg.contains(needle), "报红文案缺 `{needle}`：{msg}");
    }
}

#[test]
fn classpath_分隔符在windows是分号() {
    // 写成 `:` 会让整条 classpath 变成一个「名字里带冒号」的条目 —— classpath 事实上为空。
    if cfg!(windows) {
        assert_eq!(kernel::classpath_separator(), ";");
    } else {
        assert_eq!(kernel::classpath_separator(), ":");
    }
    assert_eq!(
        kernel::join_classpath(&["a.jar", "b.jar"]),
        format!("a.jar{}b.jar", kernel::classpath_separator())
    );
}

// ---------------------------------------------------------------------------
// java 定位
// ---------------------------------------------------------------------------

#[test]
fn java_优先取_java_home_下的可执行文件() {
    let home = fixture::temp_dir("java-home");
    let bin = home.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let exe = bin.join(format!("java{}", std::env::consts::EXE_SUFFIX));
    std::fs::write(&exe, b"stub").unwrap();
    let home_str = home.to_string_lossy().into_owned();
    let env = fixture::env(&[("JAVA_HOME", &home_str)]);
    let got = kernel::resolve_java(&repo_root(), &env).unwrap();
    assert_eq!(got, exe);
}

#[test]
fn java_设了_java_home_但里面没有可执行文件时报红() {
    let home = fixture::temp_dir("java-home-broken");
    let home_str = home.to_string_lossy().into_owned();
    let env = fixture::env(&[("JAVA_HOME", &home_str)]);
    let err = kernel::resolve_java(&repo_root(), &env).expect_err("JAVA_HOME 坏了要红，不许回落 PATH 猜");
    let msg = match err {
        ErbccError::Io(m) => m,
        other => panic!("应是 Io（退出码 1），实际 {other:?}"),
    };
    assert!(msg.contains("JAVA_HOME"), "报红要点名 JAVA_HOME：{msg}");
}

#[test]
fn java_未设_java_home_时落_path_查找() {
    let env = fixture::env(&[]);
    let got = kernel::resolve_java(&repo_root(), &env).unwrap();
    assert_eq!(got, PathBuf::from("java"), "PATH 档交给 Command 做系统级查找");
}

// ---------------------------------------------------------------------------
// argv 组装：--limit 传得下去、入口类与子命令不动
// ---------------------------------------------------------------------------

fn argv_strs(argv: &[std::ffi::OsString]) -> Vec<String> {
    argv.iter().map(|s| s.to_string_lossy().into_owned()).collect()
}

#[test]
fn argv_固定段与_main_class_与_subcommand() {
    let strs = argv_strs(&kernel::kernel_argv("C:/lib/*", Path::new("C:/corpus"), Path::new("C:/out"), None));
    for opt in kernel::JVM_OPTS {
        assert!(strs.iter().any(|s| s == opt), "JVM 选项 {opt} 必须原样传（堆档位与编码口径）");
    }
    assert!(strs.iter().any(|s| s == "-cp"));
    assert!(strs.iter().any(|s| s == kernel::MAIN_CLASS), "入口类 = Kotlin 的 mainClass");
    assert!(strs.windows(3).any(|w| w == ["compile", "--corpus", "C:/corpus"]));
    assert!(strs.windows(2).any(|w| w == ["--out", "C:/out"]));
    assert!(!strs.iter().any(|s| s == "--limit"), "没传 --limit 时不许凭空加档");
}

#[test]
fn argv_limit_原样进_kernel_命令行() {
    let strs = argv_strs(&kernel::kernel_argv("C:/lib/*", Path::new("C:/corpus"), Path::new("C:/out"), Some(3)));
    assert!(
        strs.windows(2).any(|w| w == ["--limit", "3"]),
        "--limit 必须原样落到子进程命令行：{strs:?}"
    );
}

// ---------------------------------------------------------------------------
// 退出码契约（Main.kt:67-73）
// ---------------------------------------------------------------------------

#[test]
fn 退出码_0_与_1_照契约落_2_是编排缺陷_其它是_io() {
    assert_eq!(kernel::kernel_exit_to_command_code(0, "").unwrap(), 0);
    // 1 = 编译失败：Kotlin 已把逐条 `文件:行号` 打进 stderr，编排层原样透出即可，
    // 不能再包一层 Rust 自己的文案（否则上屏文本与基准不等）。
    assert_eq!(
        kernel::kernel_exit_to_command_code(1, "编译失败 1 条（至多列 50 条，格式 文件:行号）:").unwrap(),
        1
    );
    match kernel::kernel_exit_to_command_code(2, "错误: 缺少 --corpus 参数") {
        Err(ErbccError::Usage(m)) => assert!(m.contains("缺少 --corpus 参数"), "kernel 文案要带上：{m}"),
        other => panic!("退出码 2 应是 Usage（退出码 2），实际 {other:?}"),
    }
    // Windows 上 JVM 自己崩了（0xC0000409 一类）或被杀（`status.code()` 给 None → -1）
    for code in [-1, 1073807364, 3] {
        match kernel::kernel_exit_to_command_code(code, "boom") {
            Err(ErbccError::Io(m)) => assert!(m.contains("退出码"), "报红要点名退出码：{m}"),
            other => panic!("契约外退出码 {code} 应是 Io，实际 {other:?}"),
        }
    }
}

// ---------------------------------------------------------------------------
// compile / check 参数报红文案（Main.kt:325-337、:409-419 逐字）
// ---------------------------------------------------------------------------

fn compile_usage(args: &[&str]) -> String {
    let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    match cmd_compile::parse_args(&owned) {
        Err(ErbccError::Usage(m)) => m,
        Err(other) => panic!("应是 Usage，实际 {other:?}"),
        Ok(_) => panic!("应报红"),
    }
}

fn check_usage(args: &[&str]) -> String {
    let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    match cmd_check::parse_args(&owned) {
        Err(ErbccError::Usage(m)) => m,
        Err(other) => panic!("应是 Usage，实际 {other:?}"),
        Ok(_) => panic!("应报红"),
    }
}

#[test]
fn compile_参数报红文案逐字对齐_kotlin() {
    let corpus = fixture::real_corpus();
    let corpus_str = corpus.to_string_lossy().into_owned();
    assert_eq!(compile_usage(&[]), "缺少 --corpus 参数");
    assert_eq!(compile_usage(&["--corpus"]), "--corpus 需要参数");
    assert_eq!(compile_usage(&["--corpus", &corpus_str, "--out"]), "--out 需要参数");
    assert_eq!(compile_usage(&["--corpus", &corpus_str, "--limit"]), "--limit 需要参数");
    // Main.kt:330-331 `toIntOrNull()?.takeIf { it > 0 }`
    for bad in ["0", "-3", "abc", "", "1.5", "99999999999"] {
        assert_eq!(
            compile_usage(&["--corpus", &corpus_str, "--limit", bad]),
            "--limit 需要正整数",
            "`--limit {bad}` 的文案",
        );
    }
    assert_eq!(
        compile_usage(&["--corpus", &corpus_str, "--strict"]),
        "无法识别的 compile 参数: --strict"
    );
    // 非目录：打的是原始串（未绝对化），`Main.kt:337` 同尺
    assert_eq!(compile_usage(&["--corpus", "no-such-dir-xyz"]), "corpus 不是目录: no-such-dir-xyz");
}

#[test]
fn compile_out_缺省是_tools_erbcc_erbc_out() {
    let corpus = fixture::real_corpus();
    let args = vec!["--corpus".to_string(), corpus.to_string_lossy().into_owned()];
    let parsed = cmd_compile::parse_args(&args).expect("只给 --corpus 应可解析");
    let tail = parsed.out_dir.to_string_lossy().replace('/', "\\");
    assert!(
        tail.ends_with("tools\\erbcc\\erbc-out"),
        "--out 缺省 = Main.kt:323 的 tools/erbcc/erbc-out（绝对化后仍要以它结尾），实际 {tail}"
    );
    // 语料根必须是 Kotlin 口径：绝对 + Windows 反斜杠（清单 `corpus-root` 行同尺）
    assert!(!parsed.corpus_root.contains('/'), "corpus-root 不许留正斜杠：{}", parsed.corpus_root);
    assert!(Path::new(&parsed.corpus_root).is_absolute(), "corpus-root 要绝对化");
}

#[test]
fn check_参数报红文案逐字对齐_kotlin() {
    let corpus = fixture::real_corpus();
    let corpus_str = corpus.to_string_lossy().into_owned();
    assert_eq!(check_usage(&[]), "缺少 --corpus 参数");
    assert_eq!(check_usage(&["--corpus"]), "--corpus 需要参数");
    assert_eq!(check_usage(&["--out"]), "--out 需要参数");
    assert_eq!(check_usage(&["--corpus", &corpus_str, "--limit", "3"]), "无法识别的 check 参数: --limit");
    assert_eq!(check_usage(&["--corpus", "no-such-dir-xyz"]), "corpus 不是目录: no-such-dir-xyz");

    let ok = cmd_check::parse_args(&["--corpus".to_string(), corpus_str]).unwrap();
    assert!(ok.out_dir.to_string_lossy().replace('/', "\\").ends_with("tools\\erbcc\\erbc-out"));
}

// ---------------------------------------------------------------------------
// kernel stdout 读数（Main.kt:378-391）
// ---------------------------------------------------------------------------

#[test]
fn 规模读数行_八个数按字段序落位() {
    // 真跑出来的行（2026-11-01，PARITY 语料的 kernel stdout）
    let line = "指令 1351  语句 1077  函数 45  串池 1  常量 8  表达式 32  全局槽 0  入口 0";
    assert_eq!(
        cmd_compile::parse_stats_numbers(line).unwrap().to_vec(),
        vec![1351u64, 1077, 45, 1, 8, 32, 0, 0]
    );
}

#[test]
fn 规模读数缺项或非数字时报红不猜() {
    for bad in [
        "指令 1  语句 2  函数 3  串池 4  常量 5  表达式 6  全局槽 7",
        "指令 1  语句 x  函数 3  串池 4  常量 5  表达式 6  全局槽 7  入口 8",
        "指令 -1  语句 2  函数 3  串池 4  常量 5  表达式 6  全局槽 7  入口 8",
    ] {
        assert!(
            cmd_compile::parse_stats_numbers(bad).is_err(),
            "读数不合法必须红：`{bad}`"
        );
    }
}

#[test]
fn kernel_stdout_读数行按前缀取回且原样重出() {
    let stdout = "=== 落盘完成 ===\n指令 1  语句 2\n侧车内容: 函数 45  标签函数 45（9 条）\n";
    assert_eq!(
        cmd_compile::stdout_line_starting_with(stdout, "指令 "),
        Some("指令 1  语句 2")
    );
    assert_eq!(cmd_compile::stdout_line_starting_with(stdout, "没有这行"), None);
}

#[test]
fn 容器版本从字节头取_魔数不对或太短都报红() {
    // 本路对 rust-manifest 路 `container_version_of` 的依赖面（规约 §8.7 禁写死版本号）。
    let dir = fixture::temp_dir("container");
    let good = dir.join("program.erbc");
    let mut bytes = b"ERBC".to_vec();
    bytes.extend_from_slice(&2u16.to_le_bytes());
    std::fs::write(&good, &bytes).unwrap();
    assert_eq!(manifest::container_version_of(&good).unwrap(), 2);

    let bad_magic = dir.join("bad.erbc");
    std::fs::write(&bad_magic, b"XXXX\x02\x00").unwrap();
    assert!(manifest::container_version_of(&bad_magic).is_err());

    let short = dir.join("short.erbc");
    std::fs::write(&short, b"ERB").unwrap();
    assert!(manifest::container_version_of(&short).is_err());
}

// ---------------------------------------------------------------------------
// 语料文件集口径（CorpusCompile.kt:198/205-218 + :775-785）
// 判式归 digest/manifest 路的 parity 闸，本路钉的是「编排吃到的那一份」。
// ---------------------------------------------------------------------------

#[test]
fn 语料清单_递归_大小写_GOLDEN_排除_斜杠相对路径_字典序() {
    let dir = fixture::temp_dir("corpus-walk");
    std::fs::create_dir_all(dir.join("SUB")).unwrap();
    std::fs::write(dir.join("b.ERB"), b"b").unwrap();
    std::fs::write(dir.join("a.erb"), b"aa").unwrap();
    std::fs::write(dir.join("SUB").join("c.ErH"), b"ccc").unwrap();
    std::fs::write(dir.join("GOLDEN_skip.ERB"), b"ignore me").unwrap();
    std::fs::write(dir.join("notes.txt"), b"not corpus").unwrap();

    let entries = manifest::source_entries(&dir).unwrap();
    let rels: Vec<&str> = entries.iter().map(|e| e.rel_path.as_str()).collect();
    assert_eq!(
        rels,
        vec!["SUB/c.ErH", "a.erb", "b.ERB"],
        "排序键 = relPath（ErbcArtifacts.kt:187）"
    );
    assert_eq!(entries[0].bytes, 3);
    assert_eq!(entries[1].bytes, 2);
    assert_eq!(entries[2].bytes, 1);
    // 相对路径一律 `/` 分隔，即使目录本身在 Windows 上（CorpusCompile.kt:776 的 replace）
    assert!(rels.iter().all(|r| !r.contains('\\')));
}

#[test]
fn 产物条目表按_erbc_meta_stmtidx_固定序登记() {
    let dir = fixture::temp_dir("artifact-entries").join("erbc.v1");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("manifest.txt"), b"whatever").unwrap();
    std::fs::write(dir.join("program.erbc"), b"ERBC\x02\x00").unwrap();
    std::fs::write(dir.join("program.stmt.idx"), b"idx").unwrap();
    let entries = cmd_compile::artifact_entries(&dir, erbcc_rs::digest::sha256_file).unwrap();
    let rels: Vec<&str> = entries.iter().map(|e| e.rel_path.as_str()).collect();
    assert_eq!(
        rels,
        vec!["erbc.v1/program.erbc", "erbc.v1/program.stmt.idx"],
        "固定序 = ErbcArtifacts.kt:188 的 listOfNotNull(artifact, meta, stmtIdx)；manifest.txt 不登记自身"
    );
}

#[test]
fn 清单字段取值_行首精确匹配不误配() {
    let text = "binary-skipped 0\nfile-limit 7\nbuild-mode limited\nsource-files 3971\n";
    assert_eq!(
        cmd_compile::manifest_field(text, "binary-skipped").unwrap().as_deref(),
        Some("0")
    );
    assert_eq!(cmd_compile::manifest_field(text, "file-limit").unwrap().as_deref(), Some("7"));
    assert_eq!(cmd_compile::manifest_field(text, "build-mode").unwrap().as_deref(), Some("limited"));
    assert_eq!(cmd_compile::manifest_field(text, "file-limit-x").unwrap(), None);
    assert_eq!(cmd_compile::manifest_field(text, "nope").unwrap(), None);
}

// ---------------------------------------------------------------------------
// stderr 转发去重（本路自己打降级行，kernel 子进程会再打一次）
// ---------------------------------------------------------------------------

#[test]
fn 降级模式行只保留编排层那一份() {
    let stderr = "降级模式：只编译前 3 个文件（正式产物必须全量）\n\
                  notes 1 条（留痕，不参与成败判定；至多列 20 条）:\n  x\n";
    let forwarded = cmd_compile::forwarded_stderr_lines(stderr, Some(3));
    assert_eq!(forwarded.len(), 2, "降级行滤掉、其余原样保序：{forwarded:?}");
    assert!(!forwarded.iter().any(|l| l.starts_with("降级模式")));
    // 没传 --limit 时一行都不滤（那行是 kernel 自己冒出来的，得让用户看到）
    assert_eq!(cmd_compile::forwarded_stderr_lines(stderr, None).len(), 3);
}

// ---------------------------------------------------------------------------
// 端到端（PARITY 小语料，真起 JVM + 真清单函数）
// ---------------------------------------------------------------------------

#[test]
fn limit_降级档经过桥落到_build_mode_limited() {
    let corpus = fixture::ensure_parity_corpus("limit");
    let out = fixture::temp_dir("bridge-limit");
    let code = compile_via_production(&corpus, &out, Some("3"))
        .expect("kernel 桥跑通（受限档）：需要 JAVA_HOME + `:tools:erbcc:installDist` 产物");
    assert_eq!(code, 0);

    let manifest = std::fs::read_to_string(out.join("erbc.v1").join("manifest.txt")).unwrap();
    // CMP-65：受限产物必须与全量档在清单里可区分，否则受限产物会被当全量打包
    assert!(manifest.contains("build-mode limited\n"), "{manifest}");
    assert!(manifest.contains("file-limit 3\n"), "{manifest}");
    // 受限档的 source 行仍按全量语料登记（`CorpusCompile.kt:775` 吃 allFiles）
    assert!(manifest.contains("source-files 13\n"), "{manifest}");
}

#[test]
fn 全量档经过桥落到_build_mode_full_且不带_file_limit() {
    let corpus = fixture::ensure_parity_corpus("full");
    let out = fixture::temp_dir("bridge-full");
    let code = compile_via_production(&corpus, &out, None).expect("kernel 桥跑通（全量档）");
    assert_eq!(code, 0);
    let manifest = std::fs::read_to_string(out.join("erbc.v1").join("manifest.txt")).unwrap();
    assert!(manifest.contains("build-mode full\n"), "{manifest}");
    assert!(!manifest.contains("file-limit"), "全量档不许带 file-limit（ErbcArtifacts.kt:264）");
}

#[test]
fn kernel_编译失败时不落清单且透传逐条文案后返_1() {
    // 空语料目录：Kotlin 侧 `Program 构建失败: 入口函数下标越界` → 退出码 1（实测 2026-11-01）。
    let corpus = fixture::temp_dir("bridge-empty-corpus");
    let out = fixture::temp_dir("bridge-empty-out");
    let code = compile_via_production(&corpus, &out, None).expect("编译失败是契约内的 1，不是编排错误");
    assert_eq!(code, 1, "Main.kt:361 的失败档退出码");
    assert!(
        !out.join("erbc.v1").join("manifest.txt").exists(),
        "`Main.kt:71` 明写失败不落盘：清单不许被改写"
    );
}

#[test]
fn check_把新鲜度判式的结论原样折成退出码() {
    // 判式本身归 rust-manifest 路的 freshness_parity；本路只钉「ok → 0 / 有问题 → 1」这条接线
    // 与「判式报 IO 时不许当成新鲜度问题」这条边界（`Main.kt:420-422`）。
    let corpus_str = fixture::real_corpus().to_string_lossy().into_owned();

    let deps_ok = cmd_check::Deps { check: fake_report_ok };
    assert_eq!(
        cmd_check::orchestrate(&["--corpus".to_string(), corpus_str.clone()], &deps_ok).unwrap(),
        0
    );

    let deps_bad = cmd_check::Deps { check: fake_report_issues };
    assert_eq!(
        cmd_check::orchestrate(&["--corpus".to_string(), corpus_str.clone()], &deps_bad).unwrap(),
        1
    );

    let deps_io = cmd_check::Deps { check: fake_report_io };
    match cmd_check::orchestrate(&["--corpus".to_string(), corpus_str], &deps_io) {
        Err(ErbccError::Io(_)) => {}
        other => panic!("应把 IO 原样上抛，实际 {other:?}"),
    }
}

/// `freshness::Report` 的三个替身（`Deps.check` 吃 fn 指针，故写成自由函数）。
fn fake_report_ok(_out: &Path, _corpus: &Path) -> Outcome<Report> {
    Ok(Report {
        ok: true,
        issues: Vec::new(),
        compiler_digest: Some("0".repeat(64)),
        out_dir: PathBuf::from("out"),
    })
}

fn fake_report_issues(_out: &Path, _corpus: &Path) -> Outcome<Report> {
    Ok(Report {
        ok: false,
        issues: vec![Issue {
            kind: "SOURCE_CHANGED".into(),
            detail: "源文件已改动但产物未重编".into(),
        }],
        compiler_digest: Some("0".repeat(64)),
        out_dir: PathBuf::from("out"),
    })
}

fn fake_report_io(_out: &Path, _corpus: &Path) -> Outcome<Report> {
    Err(ErbccError::Io("读不到 manifest.txt".into()))
}

/// `ProgramStats` 的字段序 = 清单 `program …` 八行（`ErbcArtifacts.kt:280-287`），
/// 也是 kernel stdout 规模读数行的字段序（`Main.kt:378-380`）；本路按这个序把读数灌进清单。
#[test]
fn program_stats_字段序与清单八行一致() {
    let s = ProgramStats {
        instructions: 1,
        statements: 2,
        functions: 3,
        strings: 4,
        constants: 5,
        expressions: 6,
        global_slots: 7,
        entry_function: 8,
        binary_skipped: 9,
        file_limit: Some(10),
    };
    let ordered: Vec<(&str, u64)> = vec![
        ("program instructions", s.instructions),
        ("program statements", s.statements),
        ("program functions", s.functions),
        ("program strings", s.strings),
        ("program constants", s.constants),
        ("program expressions", s.expressions),
        ("program global-slots", s.global_slots),
        ("program entry-function", s.entry_function),
    ];
    for (i, (key, v)) in ordered.iter().enumerate() {
        assert_eq!(*v, i as u64 + 1, "清单行 `{key}` 的取值顺序");
    }
    assert_eq!(ProgramStats::build_mode_of(Some(&s), s.file_limit), "limited");
    // 全量档：`file_limit` 两处都缺省才是 full（`ErbcArtifacts.kt:263` 的 stats.fileLimit 判据）
    let full = ProgramStats {
        file_limit: None,
        ..s
    };
    assert_eq!(ProgramStats::build_mode_of(Some(&full), None), "full");
    assert_eq!(ProgramStats::build_mode_of(None, None), "unknown");
}

/// `--limit N` 且 N ≥ 语料文件数 ⇒ Kotlin 侧 `effectiveLimit = null`
/// （`CorpusCompile.kt:268` 的 `fileLimit in 1 until allFiles.size`），产物必须仍是**全量档**
/// —— 这一条边界两侧都容易写错（受限档判据吃的是「真的小于」）。
#[test]
fn limit_不小于语料数时仍是全量档_not_limited() {
    let corpus = fixture::ensure_parity_corpus("limit-all");
    let out = fixture::temp_dir("bridge-limit-all");
    let code = compile_via_production(&corpus, &out, Some("13")).expect("kernel 桥跑通（limit=13 = 语料数）");
    assert_eq!(code, 0);
    let manifest = std::fs::read_to_string(out.join("erbc.v1").join("manifest.txt")).unwrap();
    assert!(manifest.contains("build-mode full\n"), "N == 文件数应折成全量档：{manifest}");
    assert!(!manifest.contains("file-limit"), "全量档不许带 file-limit 行：{manifest}");
}
