//! 闸 2：同一输入下 Rust `manifest_text` 与 Kotlin 落盘的 `manifest.txt` **逐字节相等**。
//!
//! 基准 = `erbc.v1/manifest.txt`（真 Kotlin 工具链产出，见 `digest_parity.rs` 头部命令）。
//! Rust 侧的入参**除了 Program 规模读数**都自己重算：
//!
//! | 入参 | Rust 侧怎么拿到 | Kotlin 侧来源 |
//! |---|---|---|
//! | `container_version` | 读磁盘 `program.erbc` 偏移 4 的 u16 小端 | `ErbcFormat.LATEST_VERSION`（`ErbcArtifacts.kt:252/254`）|
//! | `erbcc_version` | `types::erbcc_version` 读 `core-vm/.../Version.kt` | `Version.VERSION`（`ErbcArtifacts.kt:255`）|
//! | `compiler_digest` / `compiler_sources` | `digest::compiler_digest` 重算 | `ErbcCompilerDigest.compute`（`:198`）|
//! | `sources` | 遍历语料根重算相对路径/字节数/SHA-256 | `CorpusCompile.kt:775-785` |
//! | `artifacts` | 遍历产物目录重算（erbc → meta → stmt.idx 固定序） | `ErbcArtifacts.kt:188` 的 `listOfNotNull` |
//! | `corpus_root` | `types::absolutize`（**Windows 口径**，不引 `\\?\`） | `toAbsolutePath().normalize()`（`:265`）|
//! | `stats` 的八个读数 | 从基准清单回读 | 编译器产出（`CompileStats`），Rust 不移译语义 ⇒ 不重算 |
//!
//! `stats` 的八个读数本身归 kernel 路（JVM 编排）产出，本闸钉的是**行序、字段名、
//! 存在性规则与全部可重算字段**；把基准里的数值喂回来正是要把「格式化」与「数值来源」
//! 这两件事分开收口（数值面由 `rust-kernel` 的四件套 sha256 闸负责）。

use std::path::{Path, PathBuf};

use erbcc_rs::digest::{compiler_digest, sha256_file};
use erbcc_rs::manifest::{
    container_version_of, list_corpus_files, manifest_text, MANIFEST_FORMAT,
};
use erbcc_rs::types::{absolutize, current_dir, find_repo_root, erbcc_version, HashEntry, ERBC_DIR_NAME};

fn data_dir() -> PathBuf {
    current_dir().join("tests/data/rust-manifest")
}

fn repo_root() -> PathBuf {
    find_repo_root(&current_dir()).expect("测试必须在仓库内运行（cwd 上溯可见 MARKER_REL）")
}

/// 基准清单里 `program <键> <值>` 八行 + `binary-skipped` + `build-mode` 的回读结果。
struct BaselineFields {
    instructions: u64,
    statements: u64,
    functions: u64,
    strings: u64,
    constants: u64,
    expressions: u64,
    global_slots: u64,
    entry_function: u64,
    binary_skipped: u64,
    build_mode: String,
    file_limit: Option<u64>,
    corpus_root: String,
    container_version: u16,
    erbcc: String,
    compiler_digest: String,
    compiler_sources: usize,
}

fn field(lines: &[&str], name: &str) -> Option<String> {
    lines
        .iter()
        .find_map(|l| l.strip_prefix(name).map(|r| r.trim().to_string()))
}

/// 回读基准清单的标量字段（这些值由编译器/kernel 路产出，本闸只把它们喂回去）。
fn read_baseline(manifest: &Path) -> BaselineFields {
    let text = std::fs::read_to_string(manifest)
        .unwrap_or_else(|e| panic!("读不到基准清单 {manifest:?}: {e}"));
    let lines: Vec<&str> = text.lines().collect();
    let num = |name: &str| -> u64 {
        field(&lines, name)
            .unwrap_or_else(|| panic!("基准清单缺 {name} 行: {manifest:?}"))
            .parse()
            .unwrap_or_else(|_| panic!("基准清单 {name} 不是整数"))
    };
    BaselineFields {
        instructions: num("program instructions "),
        statements: num("program statements "),
        functions: num("program functions "),
        strings: num("program strings "),
        constants: num("program constants "),
        expressions: num("program expressions "),
        global_slots: num("program global-slots "),
        entry_function: num("program entry-function "),
        binary_skipped: num("binary-skipped "),
        build_mode: field(&lines, "build-mode ").expect("基准清单缺 build-mode 行"),
        file_limit: field(&lines, "file-limit ").map(|v| v.parse().expect("file-limit 不是整数")),
        corpus_root: field(&lines, "corpus-root ").expect("基准清单缺 corpus-root 行"),
        container_version: num("container-version ") as u16,
        erbcc: field(&lines, "erbcc ").expect("基准清单缺 erbcc 行"),
        compiler_digest: field(&lines, "compiler-digest ").expect("基准缺 compiler-digest"),
        compiler_sources: field(&lines, "compiler-sources ")
            .expect("基准缺 compiler-sources")
            .parse()
            .expect("compiler-sources 不是整数"),
    }
}

/// 产物条目：`erbc.v1/program.erbc` → `program.meta` → `program.stmt.idx` 固定序
/// （Kotlin `ErbcArtifacts.kt:188` 的 `listOfNotNull(artifact, meta, stmtIdx)`；
/// 侧车在容器之后、清单不在表里 —— `:182-184` 的 CMP-74 口径）。
fn artifact_entries(artifact_dir: &Path) -> Vec<HashEntry> {
    let mut out = Vec::new();
    for name in ["program.erbc", "program.meta", "program.stmt.idx"] {
        let path = artifact_dir.join(name);
        if !path.is_file() {
            continue;
        }
        out.push(HashEntry {
            rel_path: format!("{ERBC_DIR_NAME}/{name}"),
            bytes: std::fs::metadata(&path).expect("产物可读").len(),
            sha256: sha256_file(&path).expect("产物摘要可算"),
        });
    }
    out
}

/// 组装 Rust 侧全部可重算入参，与基准清单逐字节比对。
fn compare_against_baseline(label: &str, out_dir: &Path, corpus_dir: &Path) {
    let manifest_path = out_dir.join(ERBC_DIR_NAME).join("manifest.txt");
    let baseline = std::fs::read(&manifest_path)
        .unwrap_or_else(|e| panic!("读不到基准清单 {manifest_path:?}: {e}"));
    let base = read_baseline(&manifest_path);

    // ---- 重算项 ----
    let container_version = container_version_of(&out_dir.join(ERBC_DIR_NAME).join("program.erbc"))
        .expect("容器版本必须从磁盘容器头读出来");
    assert_eq!(
        container_version, base.container_version,
        "{label}：容器头里的真实版本必须等于清单的 container-version/format-version\
         （规约 §8.7 禁止写死版本号）"
    );
    assert_eq!(
        base.container_version, 2,
        "{label}：本仓容器实测是 v2（ErbcFormat.LATEST_VERSION），清单却不是 —— 说明基准或读法坏了"
    );
    let version = erbcc_version(&repo_root()).expect("引擎版本必须从 Version.kt 读到");
    assert_eq!(version, base.erbcc, "{label}：erbcc 版本串与基准登记不符");
    let digest = compiler_digest(&repo_root()).expect("编译器指纹必须算得出来");
    assert_eq!(digest.digest, base.compiler_digest, "{label}：compiler-digest 不符");
    assert_eq!(digest.file_count, base.compiler_sources, "{label}：compiler-sources 不符");

    // 语料 source 条目：自己遍历 + 按相对路径排序（ErbcArtifacts.kt:187 的 sortedBy）
    let mut sources: Vec<HashEntry> = Vec::new();
    let corpus_abs = absolutize(corpus_dir);
    for path in list_corpus_files(&corpus_abs) {
        let rel = path
            .strip_prefix(&corpus_abs)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .expect("语料文件必在语料根之下");
        sources.push(HashEntry {
            bytes: std::fs::metadata(&path).expect("源文件可读").len(),
            sha256: sha256_file(&path).expect("源文件摘要可算"),
            rel_path: rel,
        });
    }
    sources.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));

    let artifacts = artifact_entries(&out_dir.join(ERBC_DIR_NAME));

    // corpus-root 是唯一会暴露 Windows 路径口径差异的行 —— 先单独钉一次，
    // 再进整文本比对（整文本红了也要一眼看出是不是这一行的事）。
    let local_corpus_root = corpus_abs.to_string_lossy().to_string();
    assert_eq!(
        local_corpus_root, base.corpus_root,
        "{label}：`corpus-root` 行与 Kotlin `toAbsolutePath().normalize()` 不等 —— \
         这是 Windows 路径口径（`\\\\?\\` 前缀 / 分隔符 / `..` 归一）的实弹判据。\n\
         期望（Kotlin）: {}\n实际（Rust absolutize）: {local_corpus_root}\n\
         基准产出后语料目录被搬过 ⇒ 重跑 compileCorpus 生成新基准\n",
        base.corpus_root
    );

    let stats = erbcc_rs::manifest::ProgramStats {
        instructions: base.instructions,
        statements: base.statements,
        functions: base.functions,
        strings: base.strings,
        constants: base.constants,
        expressions: base.expressions,
        global_slots: base.global_slots,
        entry_function: base.entry_function,
        binary_skipped: base.binary_skipped,
        file_limit: base.file_limit,
    };
    let got = manifest_text(
        container_version,
        &version,
        &digest.digest,
        digest.file_count,
        &base.build_mode,
        base.file_limit,
        &local_corpus_root,
        &sources,
        &artifacts,
        Some(&stats),
    )
    .expect("manifest_text 不该失败");

    let want = String::from_utf8(baseline.clone()).expect("基准清单必须是 UTF-8");
    if got != want {
        let g: Vec<&str> = got.lines().collect();
        let w: Vec<&str> = want.lines().collect();
        let mut report = format!("{label}：Rust manifest_text 与 Kotlin 落盘清单不逐字节相等\n");
        for i in 0..std::cmp::max(g.len(), w.len()) {
            let (a, b) = (g.get(i).copied().unwrap_or("<无此行>"), w.get(i).copied().unwrap_or("<无此行>"));
            if a != b {
                report.push_str(&format!("第 {} 行差\n  期望（Kotlin）: {b}\n  实际（Rust）  : {a}\n", i + 1));
            }
        }
        panic!("{report}");
    }
    // 逐字节相等已经成立，这两条只是把读数写进输出（交付要报字节数/行数）
    eprintln!(
        "{label}：manifest 逐字节相等，{} 字节 / {} 行（source {} 条、artifact {} 条）",
        want.len(),
        want.lines().count(),
        sources.len(),
        artifacts.len()
    );
}

/// 主闸：小语料档（入库基准）逐字节相等。
#[test]
fn rust_manifest_text_is_byte_identical_to_kotlin_small_corpus() {
    let data = data_dir();
    compare_against_baseline("小语料档", &data.clone(), &data.join("corpus"));
}

/// 第二读数：全量语料档（3,971 条 source / 658 KB 清单；基准不入库，缺档时打印跳过）。
#[test]
fn rust_manifest_text_is_byte_identical_to_kotlin_full_corpus() {
    let dir = std::env::var("ERBCC_RS_FULL_BASELINE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| current_dir().join("build/baseline/full"));
    if !dir.join(ERBC_DIR_NAME).join("manifest.txt").is_file() {
        eprintln!(
            "SKIP(第二读数)：没有全量基准 {:?}\n生成：./gradlew --configure-on-demand \
             :tools:erbcc:compileCorpus -Pcorpus=\"$ERATW_CORPUS\" -Pout=tools/erbcc-rs/build/baseline/full",
            dir.join(ERBC_DIR_NAME).join("manifest.txt")
        );
        return;
    }
    // 全量档的语料根 = 基准清单里登记的 corpus-root（共享盘上的游戏 ERB 目录）
    let manifest = dir.join(ERBC_DIR_NAME).join("manifest.txt");
    let corpus_root = read_baseline(&manifest).corpus_root;
    let corpus = PathBuf::from(&corpus_root);
    if !corpus.is_dir() {
        eprintln!(
            "SKIP(第二读数)：基准登记的语料根不存在：{corpus_root}（换机/换盘时预期）"
        );
        return;
    }
    compare_against_baseline("全量档", &dir, &corpus);
}

// ======================================================================
// 行序与存在性规则（ErbcArtifacts.kt:241-290 的判据，不依赖基准数值）
// ======================================================================

/// 行序：`erbcc` 之后紧跟两行指纹；`build-mode`/`corpus-root`/`source-files` 的位置固定。
#[test]
fn row_order_matches_kotlin_line_by_line() {
    let text = manifest_text(
        2,
        "9.9.9-TEST",
        &"a".repeat(64),
        7,
        "full",
        None,
        "C:/fake/corpus",
        &[HashEntry {
            rel_path: "a.ERB".into(),
            bytes: 3,
            sha256: "b".repeat(64),
        }],
        &[HashEntry {
            rel_path: "erbc.v1/program.erbc".into(),
            bytes: 4,
            sha256: "c".repeat(64),
        }],
        None,
    )
    .unwrap();
    let rows: Vec<&str> = text.lines().collect();
    // 无 stats ⇒ 既没有 binary-skipped 也没有 program 行（build-mode 由调用方给 unknown 才是 Kotlin 口径，
    // 这里只钉「stats 缺失时这两类行必须不出现」）
    assert!(
        !rows.iter().any(|l| l.starts_with("binary-skipped")),
        "stats 缺失不得写 binary-skipped（ErbcArtifacts.kt:269）：{rows:?}"
    );
    assert!(
        !rows.iter().any(|l| l.starts_with("program ")),
        "stats 缺失不得写 program 行（ErbcArtifacts.kt:279-288）：{rows:?}"
    );
    assert!(
        !rows.iter().any(|l| l.starts_with("file-limit")),
        "无 file-limit 不得写该行（ErbcArtifacts.kt:264）：{rows:?}"
    );
    let idx = |p: &str| {
        rows.iter()
            .position(|l| l.starts_with(p))
            .unwrap_or_else(|| panic!("缺 {p} 行：{rows:?}"))
    };
    assert_eq!(idx("# erbc compile manifest "), 0);
    assert_eq!(idx("manifest-format 2"), 1);
    assert_eq!(idx("format-version 2"), 2);
    assert_eq!(idx("container-magic ERBC"), 3);
    assert_eq!(idx("container-version 2"), 4);
    let erbcc_at = idx("erbcc ");
    assert_eq!(idx("compiler-digest "), erbcc_at + 1, "指纹行必须紧跟 erbcc 之后（:258）");
    assert_eq!(idx("compiler-sources "), erbcc_at + 2, "计数行紧跟 digest 之后（:259）");
    assert!(idx("build-mode ") > idx("compiler-sources "), "build-mode 在指纹之后（:263）");
    assert!(idx("corpus-root ") > idx("build-mode "), "corpus-root 在档位之后（:265）");
    assert!(idx("source-files ") > idx("corpus-root "), "source-files 在 corpus-root 之后（:266）");
    assert!(idx("source a.ERB") > idx("source-files "), "逐条 source 在计数之后（:270-273）");
    assert!(idx("artifacts 1") > idx("source a.ERB"), "artifacts 计数在 source 之后（:274）");
    assert!(idx("artifact erbc.v1/program.erbc") > idx("artifacts 1"), "逐条 artifact 在计数之后（:275-278）");
    assert_eq!(MANIFEST_FORMAT, 2, "manifest-format 必须与 Kotlin 的 MANIFEST_FORMAT 同值（:103）");
    assert!(text.ends_with('\n') && !text.contains('\r'), "LF 结尾、不得出现 CR");
    assert_eq!(text.lines().count(), rows.len());
}

/// 受限档：`build-mode limited` + `file-limit N` + `binary-skipped N` + `program` 八行都在。
#[test]
fn limited_mode_rows_are_present_only_when_stats_say_so() {
    let stats = erbcc_rs::manifest::ProgramStats {
        instructions: 1,
        statements: 2,
        functions: 3,
        strings: 4,
        constants: 5,
        expressions: 6,
        global_slots: 7,
        entry_function: 8,
        binary_skipped: 9,
        file_limit: Some(123),
    };
    let text = manifest_text(
        2,
        "9.9.9-TEST",
        &"a".repeat(64),
        7,
        erbcc_rs::manifest::ProgramStats::build_mode_of(Some(&stats), stats.file_limit),
        stats.file_limit,
        "C:/fake/corpus",
        &[],
        &[],
        Some(&stats),
    )
    .unwrap();
    let rows: Vec<&str> = text.lines().collect();
    let pos = |p: &str| rows.iter().position(|l| l.starts_with(p)).unwrap();
    assert_eq!(rows[pos("build-mode ")], "build-mode limited");
    assert_eq!(rows[pos("file-limit ")], "file-limit 123");
    assert_eq!(rows[pos("binary-skipped ")], "binary-skipped 9");
    // 八行的键名与顺序逐字照 Kotlin :280-287
    let program_rows: Vec<&str> = rows.iter().filter(|l| l.starts_with("program ")).copied().collect();
    assert_eq!(
        program_rows,
        vec![
            "program instructions 1",
            "program statements 2",
            "program functions 3",
            "program strings 4",
            "program constants 5",
            "program expressions 6",
            "program global-slots 7",
            "program entry-function 8",
        ],
        "program 段的键名与行序必须与 Kotlin 逐字一致"
    );

    // stats 缺失 ⇒ build-mode 由调用方给 unknown（不谎报 full），且不写 file-limit
    assert_eq!(
        erbcc_rs::manifest::ProgramStats::build_mode_of(None, Some(123)),
        "unknown",
        "stats 缺失一律 unknown（ErbcArtifacts.kt:263）"
    );
    assert_eq!(
        erbcc_rs::manifest::ProgramStats::build_mode_of(Some(&stats), None),
        "limited",
        "档位判据要能在 file_limit 形参缺省时退回 stats.file_limit"
    );
    let full_stats = erbcc_rs::manifest::ProgramStats {
        file_limit: None,
        ..stats
    };
    assert_eq!(
        erbcc_rs::manifest::ProgramStats::build_mode_of(Some(&full_stats), None),
        "full"
    );
    let unknown = manifest_text(
        2,
        "9.9.9-TEST",
        &"a".repeat(64),
        7,
        "unknown",
        None,
        "C:/fake/corpus",
        &[],
        &[],
        None,
    )
    .unwrap();
    assert!(unknown.contains("build-mode unknown\n"), "{unknown}");
    assert!(!unknown.contains("file-limit"), "stats 缺失时不得写 file-limit");
}

/// 原子落盘：内容到位、不留 `.tmp`、已存在文件被整体替换、失败路径不脏化目标
/// （口径 = `AtomicWrite.kt:18-39`；Kotlin 用例 `AtomicWriteTest.kt:28-48`）。
#[test]
fn atomic_write_replaces_and_leaves_no_temp_file() {
    let dir = std::env::temp_dir().join(format!("erbcc-rs-atomic-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let target = dir.join("nested").join("manifest.txt");

    erbcc_rs::manifest::atomic_write(&target, b"new".as_slice()).expect("首次落盘要成功");
    assert_eq!(std::fs::read(&target).unwrap(), b"new");
    assert!(
        !dir.join("nested").join("manifest.txt.tmp").exists(),
        "成功路径不得留 .tmp（AtomicWriteTest.kt:35）"
    );

    // 覆盖已存在文件
    erbcc_rs::manifest::atomic_write(&target, b"older".as_slice()).expect("覆盖要成功");
    assert_eq!(std::fs::read(&target).unwrap(), b"older");

    // 写失败（临时文件路径被同名目录占住，与 Kotlin 用例 :43 同形）⇒ 目标保持旧内容
    let blocked = dir.join("blocked.erbc");
    std::fs::write(&blocked, b"old").unwrap();
    std::fs::create_dir_all(dir.join("blocked.erbc.tmp")).unwrap();
    let err = match erbcc_rs::manifest::atomic_write(&blocked, b"new".as_slice()) {
        Err(e) => e,
        Ok(()) => panic!("临时文件被目录占住时 atomic_write 必须失败"),
    };
    let _ = err;
    assert_eq!(
        std::fs::read(&blocked).unwrap(),
        b"old",
        "失败不得改写目标产物（AtomicWriteTest.kt:47）"
    );

    let _ = std::fs::remove_dir_all(&dir);
}
