//! `scan` 子命令对拍闸。
//!
//! 每档比对**两个维度**（对齐契约 §4-A：文件集、逐文件编码判定、条数）：
//! 1. 逐文件 `(rel_path, encoding, binary, decodeReplacement)` —— 拿 Rust 落盘的 `report.json`
//!    对 Kotlin 真工具链产出的 `report.json`（`Main.kt:1025-1028` 口径）；
//! 2. 聚合计数 `summary.txt` —— 对 Kotlin 策略表/stderr/files.tsv 归一出的 `*.meta`。
//!
//! 期望值全部来自 `tools/erbcc-rs/tests/data/rust-scan/` 下的**真 Kotlin 产物**（不手抄）。
//! 全量语料档（`$ERATW_CORPUS`）是**第二档**：仅当环境变量与预生成的 Kotlin 基线都在时才实跑，
//! 缺任一即**大声打印跳过并直接返回**（不当作绿）；若环境变量已设但基线缺失则**判红**，
//! 避免「静默假绿」（AGENTS §5 口径）。
//!
//! 已知 JVM 侧不可比字段（写进差异，不凑）：词法读数 physicalLines/logicalLines/tokens/
//! stmtLines/errors/warnings 属 core-vm 前端（契约铁律 0.1），本波不移植，故 report.json
//! 里这些列只存在于 Kotlin 侧、不纳入对拍；对拍只取上面 4 个可移植字段 + 计数。

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use erbcc_rs::scan;

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}
fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-scan").join(rel)
}
fn golden_corpus() -> PathBuf {
    let p = manifest_dir().join("../../fixtures/golden");
    p.canonicalize().unwrap_or(p)
}

/// 解析 `k=v` 行为有序无关的 map。
fn parse_kv(text: &str) -> HashMap<String, String> {
    let mut m = HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            m.insert(k.to_string(), v.to_string());
        }
    }
    m
}

/// 一条 per-file 记录（从 report.json 的一行抽可移植字段）。
#[derive(Debug, Clone, PartialEq, Eq)]
struct Rec {
    encoding: String,
    binary: bool,
    decode_replacement: bool,
}

/// 从 report.json 文本抽 `rel_path -> Rec`。文件条目每个一行，前导字段顺序与 Kotlin 一致：
/// `{"file": "X", "encoding": "Y", "binary": bool` + 可选 `, "decodeReplacement": true`。
fn parse_report_files(text: &str) -> HashMap<String, Rec> {
    let mut out = HashMap::new();
    for line in text.lines() {
        let idx = match line.find("\"file\": \"") {
            Some(i) => i,
            None => continue,
        };
        let after = &line[idx + "\"file\": \"".len()..];
        let rel = after.split('"').next().unwrap_or("").to_string();
        let encoding = extract_quoted(line, "\"encoding\": \"").unwrap_or_default();
        let binary = extract_bool(line, "\"binary\": ");
        let decode_replacement = line.contains("\"decodeReplacement\": true");
        out.insert(
            rel,
            Rec {
                encoding,
                binary,
                decode_replacement,
            },
        );
    }
    out
}

fn extract_quoted(line: &str, key: &str) -> Option<String> {
    let i = line.find(key)? + key.len();
    let rest = &line[i..];
    rest.split('"').next().map(|s| s.to_string())
}

fn extract_bool(line: &str, key: &str) -> bool {
    match line.find(key) {
        Some(i) => line[i + key.len()..].starts_with("true"),
        None => false,
    }
}

/// 跑一次 `cmd_scan::execute` 到临时 out，返回该 out 目录。
fn run_scan(name: &str, corpus: &Path, index: Option<&Path>, rename: Option<&Path>) -> PathBuf {
    let out = manifest_dir().join(format!("target/parity-{name}"));
    let _ = std::fs::remove_dir_all(&out);
    let mut args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus.to_string_lossy().to_string(),
        "--out".to_string(),
        out.to_string_lossy().to_string(),
    ];
    if let Some(idx) = index {
        args.push("--use-index".to_string());
        args.push(idx.to_string_lossy().to_string());
    }
    if let Some(rn) = rename {
        args.push("--rename".to_string());
        args.push(rn.to_string_lossy().to_string());
    }
    let code = erbcc_rs::cmd_scan::execute(&args)
        .unwrap_or_else(|e| panic!("scan {name} execute 失败: {e:?}"));
    assert_eq!(code, 0, "scan {name} 应退 0");
    out
}

/// 逐档对拍核心：summary.txt vs meta、report.json 逐文件 vs Kotlin report.json。
fn check_tier(name: &str, corpus: &Path, index: Option<&Path>, rename: Option<&Path>) {
    let out = run_scan(name, corpus, index, rename);

    let rust_summary = std::fs::read_to_string(out.join("summary.txt"))
        .unwrap_or_else(|e| panic!("读 Rust summary {e}"));
    let base_meta = std::fs::read_to_string(data(format!("{name}.meta").as_str()))
        .unwrap_or_else(|e| panic!("读基线 meta {e}"));
    let got = parse_kv(&rust_summary);
    let want = parse_kv(&base_meta);
    // 键集必须一致（既不缺也不多），再逐键比。
    assert_eq!(
        got.keys().collect::<std::collections::BTreeSet<_>>(),
        want.keys().collect::<std::collections::BTreeSet<_>>(),
        "summary.txt 与 {name}.meta 键集不一致"
    );
    for (k, v) in &want {
        assert_eq!(got.get(k), Some(v), "计数项 {k} 不等（{name}）");
    }

    // 逐文件：Rust report.json vs Kotlin 基线 report.json。
    let rust_rep = std::fs::read_to_string(out.join("report.json")).expect("Rust report.json");
    let base_rep = std::fs::read_to_string(data(format!("{name}/report.json").as_str()))
        .expect("基线 report.json");
    let rust_files = parse_report_files(&rust_rep);
    let want_files = parse_report_files(&base_rep);
    assert_eq!(
        rust_files.keys().collect::<std::collections::BTreeSet<_>>(),
        want_files.keys().collect::<std::collections::BTreeSet<_>>(),
        "文件集（rel_path 集合）与 Kotlin 不一致（{name}）"
    );
    let mut diffs: Vec<String> = Vec::new();
    for (rel, want_rec) in &want_files {
        let got_rec = &rust_files[rel];
        if got_rec != want_rec {
            diffs.push(format!("{rel}: Rust={got_rec:?} vs Kotlin={want_rec:?}"));
        }
    }
    assert!(
        diffs.is_empty(),
        "逐文件编码判定差异（{name}）:\n{}",
        diffs.join("\n")
    );
}

#[test]
fn golden_tier_parity() {
    check_tier("golden", &golden_corpus(), None, None);
}

#[test]
fn sjis_tier_parity() {
    // 合成语料：UTF-8/BOM/valid-SJIS/invalid-SJIS/二进制 全覆盖，钉编码探测与替换标记。
    check_tier("sjis", &data("sjis-corpus"), None, None);
}

#[test]
fn index_tier_parity() {
    // 带索引 + 置換名辞書：钉 all/index/union 文件集、缺档、子目录、BOM 与替换标记。
    let ic = data("index-corpus");
    check_tier(
        "index",
        &ic,
        Some(&ic.join("index.dat")),
        Some(&ic.join("_Rename.csv")),
    );
}

/// 逐文件编码判定直接用 collect API 复核（不依赖落盘），尤其钉替换标记的四类边界。
#[test]
fn encoding_and_binary_classification_via_api() {
    let ic = data("index-corpus");
    let s = ic.to_string_lossy().to_string();
    let files = scan::collect(&s, "union", None).expect("collect union");
    let by: HashMap<String, scan::CorpusFile> =
        files.into_iter().map(|f| (f.rel_path.clone(), f)).collect();

    // UTF-8 无 BOM
    assert_eq!(by["a.ERB"].encoding, "UTF-8");
    assert!(!by["a.ERB"].has_bom);
    assert!(!by["a.ERB"].decode_replacement);
    // 子目录 + UTF-8 带 BOM：decode_replacement = has_bom（原样复刻 Main.kt:804 的怪癖）
    assert_eq!(by["sub/b.ERH"].encoding, "UTF-8");
    assert!(by["sub/b.ERH"].has_bom);
    assert!(by["sub/b.ERH"].decode_replacement);
    // 合法 Shift-JIS（A4 A2）：标签 Shift-JIS、无替换字符
    assert_eq!(by["c.ERB"].encoding, "Shift-JIS");
    assert!(!by["c.ERB"].lossy);
    assert!(!by["c.ERB"].decode_replacement);
    // 非法 Shift-JIS（FF FF）：Shift-JIS + 出现替换字符
    assert_eq!(by["d.ERB"].encoding, "Shift-JIS");
    assert!(by["d.ERB"].lossy);
    assert!(by["d.ERB"].decode_replacement);
}

/// 二进制判定：NUL 命中即 binary=true，且只取编码标签、不置 decode_replacement（Main.kt:817）。
#[test]
fn binary_file_report_fields() {
    let sj = data("sjis-corpus").join("BINARY.ERB");
    let bytes = std::fs::read(&sj).unwrap();
    assert!(scan::contains_nul(&bytes, 8192));
    // detectEncoding 对 AB\0CD（有效 UTF-8）返回 UTF-8；binary 路径下 decode_replacement 恒 false。
    let (enc, _bom, _lossy) = scan::detect_encoding(&bytes);
    assert_eq!(enc, "UTF-8");
    assert!(!scan::decode_replacement_of(&enc, _bom, _lossy, true));
}

/// 文件集策略：all == union（索引内存在文件恒 ⊆ 磁盘），index ⊆ all，缺 index 时 index 为空。
#[test]
fn strategy_set_relationships() {
    let ic = data("index-corpus");
    let s = ic.to_string_lossy().to_string();
    let dat = ic.join("index.dat");
    let d = dat.to_string_lossy().to_string();

    let all: std::collections::BTreeSet<String> = scan::collect(&s, "all", Some(&d))
        .unwrap()
        .into_iter()
        .map(|f| f.rel_path)
        .collect();
    let union: std::collections::BTreeSet<String> = scan::collect(&s, "union", Some(&d))
        .unwrap()
        .into_iter()
        .map(|f| f.rel_path)
        .collect();
    let idx: std::collections::BTreeSet<String> = scan::collect(&s, "index", Some(&d))
        .unwrap()
        .into_iter()
        .map(|f| f.rel_path)
        .collect();
    assert_eq!(all, union, "all 与 union 文件集应相同（本语料）");
    assert!(idx.is_subset(&all));
    assert_eq!(idx.len(), 3, "index 应命中 a/c/sub-b（ghost 缺档）");
    assert!(idx.contains("sub/b.ERH"));
    assert!(!idx.contains("d.ERB") && !idx.contains("notindexed.ERB"));

    // 未知策略报 Usage
    assert!(matches!(
        scan::collect(&s, "engine", Some(&d)),
        Err(erbcc_rs::types::ErbccError::Usage(_))
    ));
}

/// 置換名辞書解析：BOM 剥离、`;` 注释、恰两列、去重（后值覆盖、保留首插序）。
#[test]
fn rename_dict_parsing() {
    let csv = data("index-corpus/_Rename.csv");
    let dict = scan::rename_dict(&csv.to_string_lossy()).expect("rename_dict");
    // 期望唯一键数 = 2（0/5 都映到 [[M-dM-=M- $]] 同键；1 另键；x,y,z 三列被跳过；; 注释跳过）
    assert_eq!(dict.len(), 2);
    // 键带 [[..]] 包裹，值为「置換後」列 trim。
    for (k, _v) in &dict {
        assert!(k.starts_with("[[") && k.ends_with("]]"), "键应形如 [[..]]：{k}");
    }
    let keys: Vec<&str> = dict.iter().map(|(k, _)| k.as_str()).collect();
    // 首插序：[[M-dM-=M- $]] 先出现（来自 0 行），其值被 5 行覆盖，但位置不动。
    assert!(keys[0].contains("M-d"), "首个键应是 0/5 行那把：{keys:?}");
    let val0 = &dict[0].1;
    assert_eq!(val0, "5", "重复键后值覆盖（0 被 5 取代）");
}

/// 索引解析镜像 Main.kt:785-793：BOM、`\`→`/`、TAB 前截断、后缀过滤、大小写不敏感。
#[test]
fn read_index_mirrors_kotlin() {
    let dat = data("index-corpus/index.dat");
    let rels = scan::read_index(&dat.to_string_lossy()).expect("read_index");
    assert_eq!(rels.len(), 4, "ghost.ERB 也在过滤后集合里（缺档在 compute_meta 判）");
    assert_eq!(rels[0], "a.ERB", "首行 BOM 应被剥离");
    assert_eq!(rels[1], "sub/b.ERH", "反斜杠应转成斜杠");
    assert!(rels.contains(&"ghost.ERB".to_string()));
}

// --- 第二档：全量语料（环境门控，缺输入即大声跳过，不当绿）---

#[test]
fn full_corpus_parity_env_gated() {
    let corpus = match std::env::var("ERATW_CORPUS") {
        Ok(v) => PathBuf::from(v),
        Err(_) => {
            eprintln!("SKIP full_corpus_parity: 未设 ERATW_CORPUS（第二档需真机全量语料）");
            return;
        }
    };
    if !corpus.is_dir() {
        eprintln!("SKIP full_corpus_parity: ERATW_CORPUS 不是目录: {corpus:?}");
        return;
    }
    // Kotlin 基线（真工具链产出，gitignore 覆盖 build/，不入库）。缺失且 env 已设 => 判红。
    let base_dir = manifest_dir().join("build/baseline/scan-corpus");
    let base_report = base_dir.join("report.json");
    let base_meta = manifest_dir().join("build/baseline/scan-corpus.meta");
    assert!(
        base_report.is_file() && base_meta.is_file(),
        "ERATW_CORPUS 已设但缺 Kotlin 基线（先跑 :tools:erbcc:run scan --use-index …）：{:?}/{:?}",
        base_report,
        base_meta
    );
    // 索引 = 游戏根下的 lazyloadingfiles.dat（corpus 的父目录）。
    let idx = corpus.parent().map(|p| p.join("lazyloadingfiles.dat"));
    let idx = idx.filter(|p| p.is_file());

    let out = run_scan("corpus", &corpus, idx.as_deref(), None);

    let rust_rep = std::fs::read_to_string(out.join("report.json")).expect("Rust corpus report");
    let base_rep = std::fs::read_to_string(&base_report).expect("Kotlin corpus report baseline");
    let rust_files = parse_report_files(&rust_rep);
    let want_files = parse_report_files(&base_rep);
    assert_eq!(rust_files.len(), want_files.len(), "全量文件集条数不等");
    let missing: Vec<&String> = want_files.keys().filter(|k| !rust_files.contains_key(*k)).collect();
    assert!(missing.is_empty(), "Rust 缺文件 {} 个", missing.len());
    let mut diffs = 0usize;
    let mut samples: Vec<String> = Vec::new();
    for (rel, want) in &want_files {
        if let Some(got) = rust_files.get(rel) {
            if got != want {
                diffs += 1;
                if samples.len() < 20 {
                    samples.push(format!("{rel}: Rust={got:?} vs Kotlin={want:?}"));
                }
            }
        }
    }
    assert_eq!(diffs, 0, "全量逐文件编码判定差异 {diffs} 处:\n{}", samples.join("\n"));

    // summary vs meta（同样可移植计数）。
    let rust_summary = std::fs::read_to_string(out.join("summary.txt")).unwrap();
    let base_meta_txt = std::fs::read_to_string(&base_meta).unwrap();
    let got = parse_kv(&rust_summary);
    let want = parse_kv(&base_meta_txt);
    for (k, v) in &want {
        assert_eq!(got.get(k), Some(v), "全量计数项 {k} 不等");
    }
}

#[allow(dead_code)]
fn _unused(_: &Path) {}
