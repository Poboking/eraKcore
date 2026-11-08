use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use crate::scan::{self, CorpusFile};
use crate::types::{absolutize, kotlin_path_text, ErbccError, Outcome, EXIT_OK};

/// `erbcc scan` 子命令：参数解析 + 报告落盘 + 退出码。
///
/// 权威口径 = Kotlin `Main.kt:132-161` 的 `cmdScan` 与 `Main.kt:698-745` 的 ScanRunner 装置、
/// `Main.kt:948-963` 的 printSummary。本波只移植**编排/扫描层**（文件集策略、逐文件编码判定、
/// 索引对账、置換名辞書探测、计数）；词法读数（logicalLines/tokens/stmtLines/异常/警告）留在
/// core-vm/JVM 前端（契约铁律 0.1、AGENTS §2），故报告只含可移植子集，绝不伪造那些列。
pub fn execute(args: &[String]) -> Outcome<i32> {
    let mut corpus: Option<PathBuf> = None;
    let mut index: Option<PathBuf> = None;
    let mut rename: Option<PathBuf> = None;
    let mut out = PathBuf::from("tools/erbcc/scan-report"); // Main.kt:136
    // 复刻 Main.kt:138-147：取到 --xxx 后 ++i 吞掉参数值，循环尾再 i++ —— 每对参数前进两格。
    let mut i = 0usize;
    while i < args.len() {
        let flag = args[i].as_str();
        let mut take_value = |slot: &str| -> Outcome<String> {
            i += 1;
            args.get(i)
                .cloned()
                .ok_or_else(|| ErbccError::Usage(format!("{slot} 需要参数")))
        };
        match flag {
            "--corpus" => corpus = Some(PathBuf::from(take_value("--corpus")?)),
            "--use-index" => index = Some(PathBuf::from(take_value("--use-index")?)),
            "--rename" => rename = Some(PathBuf::from(take_value("--rename")?)),
            "--out" => out = PathBuf::from(take_value("--out")?),
            other => {
                return Err(ErbccError::Usage(format!(
                    "无法识别的 scan 参数: {other}"
                )))
            }
        }
        i += 1;
    }

    // Main.kt:148-150：corpus 必填且须为目录；use-index 若给须为文件。
    // F1 + ④（rust-cli-polish）：三条报红都要过 [kotlin_path_text]。Kotlin 那边 `--corpus`/`--use-index`
    // 都是 `Path(args[i])`（**未绝对化**），`$corpusDir`/`$index` 走的是 Java `WindowsPath.toString()`
    // （`/` 归一成 `\`），实测读数：
    //   `scan --use-index tools/nope/index.dat` ⇒ Kotlin `错误: use-index 不是文件: tools\nope\index.dat`
    //   （见 tools/erbcc-rs/build/cli-polish/c4k.err），而修前的 Rust 原样回显正斜杠 ⇒ 同一格只差分隔符。
    // 只调用单源、不改 [crate::types] 那两个函数本体（本车道所有权）。
    let corpus_dir = corpus.ok_or_else(|| ErbccError::Usage("缺少 --corpus 参数".to_string()))?;
    if !corpus_dir.is_dir() {
        return Err(ErbccError::Usage(format!(
            "corpus 不是目录: {}",
            kotlin_path_text(&corpus_dir.to_string_lossy())
        )));
    }
    if let Some(idx) = &index {
        if !idx.is_file() {
            return Err(ErbccError::Usage(format!(
                "use-index 不是文件: {}",
                kotlin_path_text(&idx.to_string_lossy())
            )));
        }
    }

    // Main.kt:151-154：缺省自动探测 <corpus>/../CSV/_Rename.csv（绝对化规范化后取父目录）。
    if rename.is_none() {
        let abs = absolutize(&corpus_dir);
        let guess = abs.parent().map(|p| p.join("CSV/_Rename.csv"));
        if let Some(g) = guess {
            if g.is_file() {
                rename = Some(g);
            }
        }
    }

    // Main.kt:155-156：仅当 rename 存在才载入并打印。
    let rename_entries: Vec<(String, String)> = match &rename {
        Some(r) => {
            let entries = scan::rename_dict(&r.to_string_lossy())?;
            let file_name = r.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
            // Main.kt:156 文案：置換名辞書: <fileName>（<size> 项）
            eprintln!("置換名辞書: {file_name}（{} 项）", entries.len());
            entries
        }
        None => Vec::new(),
    };

    // 文件集与编码读数（report.files 迭代的是 allFiles=union，Main.kt:724-731）。
    let corpus_str = corpus_dir.to_string_lossy();
    let index_str: Option<&str> = index.as_ref().and_then(|p| p.to_str());
    let meta = scan::compute_meta(&corpus_str, index_str)?;
    let files = scan::collect(&corpus_str, "union", index_str)?;

    // Main.kt:727：磁盘文件 / 索引文件 / 并集（含重复计数口径 index_on_disk/index_total）。
    eprintln!(
        "磁盘文件: {}  索引文件: {}/{}  并集: {}",
        meta.disk_count, meta.index_on_disk, meta.index_total, meta.union_count
    );

    let agg = aggregate(&files, &meta.index_rel_set);

    // 落盘（Main.kt:996 Files.createDirectories + writeReports）。
    if let Err(e) = std::fs::create_dir_all(&out) {
        return Err(ErbccError::Io(format!("建报告目录 {out:?} 失败: {e}")));
    }
    let out_dir = absolutize(&out);
    write_reports(&out_dir, &files, &agg, &meta, rename_entries.len())?;
    print_summary(&agg, &meta, &files);

    Ok(EXIT_OK)
}

/// 逐策略聚合（复刻 `Main.kt:966-991` aggregate，但只统计可移植列：files / binary）。
///
/// `all`/`union` 计入每个 union 文件；`index` 计入 rel 命中 `index_rel_set` 者
/// （`Main.kt:984` 的字符串精确匹配）；`engine` 计入未命中者（`Main.kt:988`）。
pub struct StrategyAgg {
    pub name: &'static str,
    pub files: usize,
    pub binary: usize,
}

struct Aggs {
    all: StrategyAgg,
    index: StrategyAgg,
    union: StrategyAgg,
    engine: StrategyAgg,
}

fn aggregate(files: &[CorpusFile], index_rel_set: &HashSet<String>) -> Aggs {
    let mut aggs = Aggs {
        all: StrategyAgg { name: "all", files: 0, binary: 0 },
        index: StrategyAgg { name: "index", files: 0, binary: 0 },
        union: StrategyAgg { name: "union", files: 0, binary: 0 },
        engine: StrategyAgg { name: "engine", files: 0, binary: 0 },
    };
    for f in files {
        for s in [&mut aggs.all, &mut aggs.union] {
            s.files += 1;
            if f.binary {
                s.binary += 1;
            }
        }
        if index_rel_set.contains(&f.rel_path) {
            aggs.index.files += 1;
            if f.binary {
                aggs.index.binary += 1;
            }
        } else {
            aggs.engine.files += 1;
            if f.binary {
                aggs.engine.binary += 1;
            }
        }
    }
    aggs
}

/// 编码统计（复刻 `Main.kt:962` 的 `groupingBy{encoding}.eachCount()`）。
fn encoding_stats(files: &[CorpusFile]) -> HashMap<String, usize> {
    let mut m: HashMap<String, usize> = HashMap::new();
    for f in files {
        *m.entry(f.encoding.clone()).or_insert(0) += 1;
    }
    m
}

/// 落 report.json（可移植子集）+ files.tsv（可移植列）+ summary.txt（供 scan_parity 逐键对拍）。
///
/// 每个文件行字段顺序对齐 `Main.kt:1025-1027`（file/encoding/binary），
/// `decodeReplacement` 仅在为真时写出（`Main.kt:1028`）；不含词法列。
fn write_reports(
    out_dir: &Path,
    files: &[CorpusFile],
    agg: &Aggs,
    meta: &scan::ScanMeta,
    rename_count: usize,
) -> Outcome<()> {
    // report.json
    let mut json = String::new();
    json.push_str("{\n");
    json.push_str("  \"rsNote\": \"Rust 侧仅移植编排/扫描层；词法读数(physicalLines/logicalLines/tokens/stmtLines/errors/warnings)与 errorGroups/warningGroups/topErrorFiles 属 core-vm/JVM 前端(契约铁律 0.1)，此处不产出，未伪造。\",\n");
    json.push_str("  \"files\": [\n");
    let total = files.len();
    for (fi, f) in files.iter().enumerate() {
        json.push_str(&format!(
            "    {{\"file\": {}, \"encoding\": \"{}\", \"binary\": {}",
            jstr(&f.rel_path),
            f.encoding,
            f.binary
        ));
        if f.decode_replacement {
            json.push_str(", \"decodeReplacement\": true");
        }
        json.push('}');
        if fi != total - 1 {
            json.push(',');
        }
        json.push('\n');
    }
    json.push_str("  ]\n}\n");
    let report_path = out_dir.join("report.json");
    std::fs::write(&report_path, json.as_bytes())
        .map_err(|e| ErbccError::Io(format!("写报告 {report_path:?} 失败: {e}")))?;
    println!("报告已写入: {}", report_path.display());

    // files.tsv（可移植列：file / encoding / binary / decodeReplacement）
    let mut tsv = String::from("file\tencoding\tbinary\tdecodeReplacement\n");
    for f in files {
        tsv.push_str(&format!(
            "{}\t{}\t{}\t{}\n",
            f.rel_path,
            f.encoding,
            if f.binary { 1 } else { 0 },
            if f.decode_replacement { 1 } else { 0 }
        ));
    }
    let tsv_path = out_dir.join("files.tsv");
    std::fs::write(&tsv_path, tsv.as_bytes())
        .map_err(|e| ErbccError::Io(format!("写清单 {tsv_path:?} 失败: {e}")))?;
    println!("文件清单已写入: {}", tsv_path.display());

    // summary.txt（与 tests/data/rust-scan/*.meta 同键，供逐键对拍）
    let enc = encoding_stats(files);
    let mut sum = String::new();
    sum.push_str(&format!("disk_count={}\n", meta.disk_count));
    sum.push_str(&format!("union_count={}\n", meta.union_count));
    sum.push_str(&format!("index_on_disk={}\n", agg.index.files));
    sum.push_str(&format!("index_total={}\n", meta.index_total));
    sum.push_str(&format!("index_missing_on_disk={}\n", meta.index_missing_on_disk));
    sum.push_str(&format!("disk_not_in_index={}\n", agg.engine.files));
    sum.push_str(&format!("strategy_all_files={}\n", agg.all.files));
    sum.push_str(&format!("strategy_all_binary={}\n", agg.all.binary));
    sum.push_str(&format!("strategy_index_files={}\n", agg.index.files));
    sum.push_str(&format!("strategy_index_binary={}\n", agg.index.binary));
    sum.push_str(&format!("strategy_union_files={}\n", agg.union.files));
    sum.push_str(&format!("strategy_union_binary={}\n", agg.union.binary));
    sum.push_str(&format!("strategy_engine_files={}\n", agg.engine.files));
    sum.push_str(&format!("strategy_engine_binary={}\n", agg.engine.binary));
    sum.push_str(&format!("encoding_UTF8={}\n", enc.get("UTF-8").copied().unwrap_or(0)));
    sum.push_str(&format!("encoding_ShiftJIS={}\n", enc.get("Shift-JIS").copied().unwrap_or(0)));
    // 置換名辞書载入项数（Main.kt:155-156 的 renameDic.size；无 --rename 且未探测到时为 0）。
    sum.push_str(&format!("rename_count={rename_count}\n"));
    let sum_path = out_dir.join("summary.txt");
    std::fs::write(&sum_path, sum.as_bytes())
        .map_err(|e| ErbccError::Io(format!("写摘要 {sum_path:?} 失败: {e}")))?;

    Ok(())
}

/// 打印人类可读小结（复刻 `Main.kt:948-963` 的结构，但列只到可移植口径）。
fn print_summary(agg: &Aggs, meta: &scan::ScanMeta, files: &[CorpusFile]) {
    println!();
    println!("=== 策略对比（可移植列：文件数 / 二进制；词法列见 JVM 侧报告）===");
    println!("策略     文件数   二进制");
    for s in [&agg.all, &agg.index, &agg.union, &agg.engine] {
        println!("{:<7} {:>7}  {:>6}", s.name, s.files, s.binary);
    }
    println!();
    println!(
        "磁盘不在索引内的文件: {}   索引内缺失于磁盘: {}",
        agg.engine.files, meta.index_missing_on_disk
    );
    let mut enc: Vec<(String, usize)> = encoding_stats(files).into_iter().collect();
    enc.sort();
    let pairs: Vec<String> = enc.iter().map(|(k, v)| format!("{k}={v}")).collect();
    println!("编码统计: {{{}}}", pairs.join(", "));
}

/// 复刻 `Main.kt:1098-1112` 的 `jstr`（JSON 字符串转义；本波用于文件名，纯 ASCII/UTF-8）。
fn jstr(s: &str) -> String {
    let mut sb = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => sb.push_str("\\\""),
            '\\' => sb.push_str("\\\\"),
            '\n' => sb.push_str("\\n"),
            '\r' => sb.push_str("\\r"),
            '\t' => sb.push_str("\\t"),
            c if (c as u32) < 0x20 => sb.push_str(&format!("\\u{:04x}", c as u32)),
            c => sb.push(c),
        }
    }
    sb.push('"');
    sb
}
