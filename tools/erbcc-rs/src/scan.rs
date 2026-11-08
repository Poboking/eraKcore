use std::collections::HashSet;
use std::path::{Path, PathBuf};

use crate::types::{absolutize, ErbccError, Outcome};

/// 全量语料的文件集与逐文件字节/编码读数（不含解析）。
///
/// 权威口径 = Kotlin `Main.kt` 的 ScanRunner 装置 + `compile/CorpusScan.kt` 的预扫描口径：
/// - 文件集策略 `all` / `index` / `union`（`lazyloadingfiles.dat` 每行 `相对路径<TAB>FILETIME`，
///   路径相对 corpus、`\` 分隔，只留 `.erb`/`.erh`）—— 见 `Main.kt:775-793`（walkDisk/readIndex）；
/// - 编码探测：UTF-8 严格校验（BOM 剥离）→ 失败回退 Shift-JIS，并标记是否出现替换字符
///   —— 见 `Main.kt:795-809`（detectEncoding）；
/// - 二进制判定：前 8KB 出现 NUL 即按二进制归类，只取编码标签、不置替换标记
///   —— 见 `Main.kt:811-822`（scanFile）与 `CorpusScan.kt:283-287`（containsNul）；
/// - 置換名辞書 `_Rename.csv`：`置換後 , [[置換前]]`，逗号恰两列，`;` 开头为注释
///   —— 见 `Main.kt:644-657`（loadRenameDic）。
#[derive(Debug, Clone)]
pub struct CorpusFile {
    /// 相对 corpus 根的路径，一律 `/` 分隔（`Main.kt:732-733` 的 relativize+replace 口径）。
    pub rel_path: String,
    /// 文件原始字节（内容寻址/后续闸用；本波 `collect` 只读不解析）。
    pub bytes: Vec<u8>,
    /// 编码标签：`UTF-8` / `Shift-JIS`（`Main.kt:804`/`:807`）。
    pub encoding: String,
    /// 是否带 UTF-8 BOM（`Main.kt:797`）。
    pub has_bom: bool,
    /// 回退 Shift-JIS 后解码是否出现替换字符 U+FFFD（`Main.kt:807` 的 `contains('\uFFFD')`）；
    /// UTF-8 分支恒 `false`（与 Kotlin `detectEncoding` 一致，第二元素在 UTF-8 分支返回 `hasBom`）。
    pub lossy: bool,
    /// 前 8KB 是否出现 NUL（`Main.kt:815` / `CorpusScan.kt:255`）。
    pub binary: bool,
    /// 报告里写出的 `decodeReplacement` 值，逐项复刻 Kotlin `FileResult.decodeReplacement` 的赋值口径：
    /// 二进制文件恒 `false`（`Main.kt:817` 只取 `.first`，`:823` 未执行）；
    /// 非二进制 UTF-8 取 `has_bom`（`Main.kt:804` 的成功分支把 `hasBom` 当第二元素返回，
    /// `:821-823` 又把它赋给 `decodeReplacement`——这是原样复刻的官方怪癖，ADR-005，不"顺手修好"）；
    /// 非二进制 Shift-JIS 取替换字符是否出现（`Main.kt:807`）。
    pub decode_replacement: bool,
}

/// 文件集与索引对账的计数读数（`Main.kt:705-727` 的 ScanReport 字段）。
#[derive(Debug, Clone, Default)]
pub struct ScanMeta {
    /// `report.diskCount`：磁盘 walkDisk 命中的 `.erb`/`.erh` 数（`Main.kt:707-708`）。
    pub disk_count: usize,
    /// `report.indexCount`：`readIndex` 过滤后的条目数（含重复，`Main.kt:715`）。
    pub index_total: usize,
    /// `indexFiles.size`：索引内解析后落在磁盘上的文件数（含重复，`Main.kt:711-714`）。
    pub index_on_disk: usize,
    /// `report.indexMissingOnDisk`：`index_total - index_on_disk`（`Main.kt:716`）。
    pub index_missing_on_disk: usize,
    /// `report.diskNotInIndex`：磁盘文件里不在索引集合中的数量（`Main.kt:717-718`）。
    pub disk_not_in_index: usize,
    /// `allFiles.size`：磁盘 ∪ 索引内存在文件（`Main.kt:724-725`）。
    pub union_count: usize,
    /// `report.indexRelSet`：索引内存在文件的相对路径集合（索引原文大小写，`Main.kt:719-722`）。
    pub index_rel_set: HashSet<String>,
}

/// 逐字节复刻 `Main.kt:795-809` 的 `detectEncoding`。
///
/// 返回 `(编码标签, 是否 BOM, 回退 Shift-JIS 时是否出现替换字符)`。第二/三个返回值供上层
/// 依据 `Main.kt:817`/`:821-823` 的赋值规则再合成报告用的 `decodeReplacement`。
///
/// - BOM：`bytes.len()>=3 && EF BB BF`（`Main.kt:797`）。
/// - UTF-8 严格校验：Kotlin 用 `CharsetDecoder.onMalformedInput/onUnmappableCharacter(REPORT)`
///   （`Main.kt:800-803`），Rust 用 `std::str::from_utf8`（同样拒绝过长序列/代理对/超范围，
///   与 REPORT 等价，不做「宽容解码」）。
/// - 回退 Shift-JIS：Kotlin `String(body, charset("Shift_JIS"))` 后 `contains('\uFFFD')`
///   （`Main.kt:806-807`）。Rust 用 `encoding_rs` 的**无替换**解码
///   （`decode_without_bom_handling_and_without_replacement`），它对任何畸形/未定义字节直接
///   返回 `None`，即「不宽容」——`None` ⟺ Kotlin 侧必然出现替换字符。body 已剥 UTF-8 BOM，
///   与 Kotlin 传入 `String(body, ...)` 的入参同源。
pub fn detect_encoding(bytes: &[u8]) -> (String, bool, bool) {
    let has_bom = bytes.len() >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
    let body = if has_bom { &bytes[3..] } else { bytes };
    if std::str::from_utf8(body).is_ok() {
        // UTF-8 成功分支：Kotlin 把 `hasBom` 当第二元素返回（`Main.kt:804`），
        // 但作为「是否出现替换字符」的语义位在 UTF-8 分支下应为 false，故 lossy=false。
        (String::from("UTF-8"), has_bom, false)
    } else {
        let lossy = encoding_rs::SHIFT_JIS
            .decode_without_bom_handling_and_without_replacement(body)
            .is_none();
        (String::from("Shift-JIS"), has_bom, lossy)
    }
}

/// 复刻 `Main.kt:815` 与 `CorpusScan.kt:255/283-287`：前 `limit` 字节内出现 NUL 即视为二进制。
pub fn contains_nul(bytes: &[u8], limit: usize) -> bool {
    let end = bytes.len().min(limit);
    bytes[..end].contains(&0)
}

/// 依 `Main.kt:821-823` 与 `:817` 的合成规则算出报告用的 `decodeReplacement`。
pub fn decode_replacement_of(encoding: &str, has_bom: bool, lossy: bool, binary: bool) -> bool {
    if binary {
        false
    } else if encoding == "UTF-8" {
        has_bom
    } else {
        lossy
    }
}

/// 复刻 `Main.kt:775-783` 的 `walkDisk`：递归收集 `.erb`/`.erh`（后缀大小写不敏感）。
///
/// 返回按相对路径（`/` 分隔）升序排列的 `(rel, abs)`。Kotlin 用 `Files.walk().sorted()`
/// （Path 的自然序，**非** core-vm 的 ja-JP Collator，ADR-006 不在本波复刻范围内）；
/// Rust 侧只对**相对路径**做字节序排序，`scan_parity` 以集合口径对拍（与排序无关），
/// 故此处排序仅用于稳定输出，不参与语义判定。
pub fn walk_disk(corpus_root: &str) -> Outcome<Vec<(String, PathBuf)>> {
    let base = absolutize(Path::new(corpus_root));
    let mut out: Vec<(String, PathBuf)> = Vec::new();
    walk_into(&base, "", &mut out)?;
    out.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(out)
}

fn walk_into(dir: &Path, rel_prefix: &str, out: &mut Vec<(String, PathBuf)>) -> Outcome<()> {
    let entries = std::fs::read_dir(dir)
        .map_err(|e| ErbccError::Io(format!("读目录 {dir:?} 失败: {e}")))?;
    for entry in entries {
        let entry = entry.map_err(|e| ErbccError::Io(format!("目录项读取失败: {e}")))?;
        let name = entry.file_name().to_string_lossy().to_string();
        let path = entry.path();
        let rel = if rel_prefix.is_empty() {
            name.clone()
        } else {
            format!("{rel_prefix}/{name}")
        };
        let ft = entry
            .file_type()
            .map_err(|e| ErbccError::Io(format!("取文件类型失败 {path:?}: {e}")))?;
        if ft.is_dir() {
            walk_into(&path, &rel, out)?;
        } else if ft.is_file() && is_erb_erh(&name) {
            out.push((rel, path));
        }
    }
    Ok(())
}

/// 后缀判定：`.erb` 或 `.erh`，大小写不敏感（`Main.kt:779` 的 `endsWith(.., ignoreCase=true)`）。
fn is_erb_erh(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".erb") || lower.ends_with(".erh")
}

/// 复刻 `Main.kt:785-793` 的 `readIndex`：`lazyloadingfiles.dat` 每行 `相对路径<TAB>FILETIME`。
///
/// 逐口径对齐：`readText`（UTF-8，畸形退化为替换字符）→ 按 `\n`/`\r` 双切分 → 每段
/// `trimStart('\uFEFF')` → 非空（`isNotBlank`）→ `substringBefore('\t').replace('\\','/')`
/// → 后缀 `.erb`/`.erh` 不敏感过滤。顺序与 Kotlin 的 map/filter 链严格一致。
pub fn read_index(dat_path: &str) -> Outcome<Vec<String>> {
    let bytes = std::fs::read(dat_path)
        .map_err(|e| ErbccError::Io(format!("读索引 {dat_path:?} 失败: {e}")))?;
    // Kotlin readText 用 UTF-8 且默认 REPLACE，Rust 用 from_utf8_lossy 等价镜像。
    let text = String::from_utf8_lossy(&bytes);
    let mut rels = Vec::new();
    for segment in text.split(['\n', '\r']) {
        let trimmed = segment.trim_start_matches('\u{FEFF}');
        if trimmed.chars().all(char::is_whitespace) {
            continue; // isBlank → 跳过（Kotlin 的 filter{isNotBlank}）
        }
        let path_field = trimmed.split('\t').next().unwrap_or("");
        let rel = path_field.replace('\\', "/");
        if is_erb_erh(&rel) {
            rels.push(rel);
        }
    }
    Ok(rels)
}

/// 复刻 `ScanRunner.run` 里对磁盘/索引集合的构建与计数（`Main.kt:705-727`），
/// 但不做词法分析（词法留 core-vm/JVM，ADR-006 与本波铁律 0.1）。
///
/// `index` 为 `Some(dat_path)` 时按其构建索引集合；`None` 时索引全为 0
/// （缺档行为与 `Main.kt:710` 的 `?: emptyList()` 一致）。
pub fn compute_meta(corpus_root: &str, index: Option<&str>) -> Outcome<ScanMeta> {
    let base = absolutize(Path::new(corpus_root));
    let disk = walk_disk(corpus_root)?;
    let mut meta = ScanMeta::default();
    meta.disk_count = disk.len();

    // 磁盘绝对路径集合（与索引文件绝对路径比对用，对应 Main.kt:717-718 的 indexSet）。
    let disk_abs: HashSet<PathBuf> = disk.iter().map(|(_, p)| absolutize(p)).collect();

    let index_rel_paths: Vec<String> = match index {
        Some(dat) => read_index(dat)?,
        None => Vec::new(),
    };
    meta.index_total = index_rel_paths.len();

    let mut index_on_disk = 0usize;
    let mut index_rel_set: HashSet<String> = HashSet::new();
    for rel in &index_rel_paths {
        let resolved = base.join(rel);
        if resolved.is_file() {
            index_on_disk += 1;
            if !index_rel_set.contains(rel) {
                // report.indexRelSet 用索引原文大小写的相对路径（Main.kt:719-722）。
                index_rel_set.insert(rel.clone());
            }
        }
    }
    meta.index_on_disk = index_on_disk;
    meta.index_missing_on_disk = index_rel_paths.len() - index_on_disk;
    meta.index_rel_set = index_rel_set;

    // diskNotInIndex：磁盘文件的绝对路径不在索引文件绝对路径集合中的数量（Main.kt:717-718）。
    let index_abs: HashSet<PathBuf> = index_rel_paths
        .iter()
        .map(|rel| absolutize(&base.join(rel)))
        .collect();
    meta.disk_not_in_index = disk_abs.iter().filter(|p| !index_abs.contains(*p)).count();

    // union：磁盘 ∪ 索引内存在文件（Main.kt:724-725 的 LinkedHashSet）。
    let mut union = disk_abs;
    for rel in &index_rel_paths {
        let resolved = absolutize(&base.join(rel));
        if resolved.is_file() {
            union.insert(resolved);
        }
    }
    meta.union_count = union.len();

    Ok(meta)
}

/// 冻结签名（契约 §2）：返回指定文件集策略下的每文件读数。
///
/// `strategy` 取 `all` / `index` / `union`（`Main.kt:103-106` USAGE、`:980-988` aggregate）：
/// - `all`：磁盘 walkDisk 全部文件；
/// - `union`：磁盘 ∪ 索引内存在文件（本语料里索引文件恒 ⊆ 磁盘，故与 all 同集，
///   见 `Main.kt:724-725`，缺索引时退化为 all）；
/// - `index`：磁盘文件里 `rel_path` 命中 `index_rel_set` 的子集（`Main.kt:984` 的 `f.relPath in idx`，
///   字符串精确匹配，索引原文大小写）；缺 `index` 参数时为空（`Main.kt:710` 口径）。
///
/// 每个返回文件读 `bytes`、做二进制探测与编码判定（不解析）。未知策略报 `Usage`。
pub fn collect(corpus_root: &str, strategy: &str, index: Option<&str>) -> Outcome<Vec<CorpusFile>> {
    let meta = compute_meta(corpus_root, index)?;
    let disk = walk_disk(corpus_root)?;

    let selected: Vec<(String, PathBuf)> = match strategy {
        "all" => disk.clone(),
        // union 的语义集 = 磁盘 ∪ 索引内存在文件；索引内存在文件恒为磁盘子集，故 union = all。
        // 仍显式按 rel 去重，行为与 Main.kt:724-725 的 LinkedHashSet 对齐。
        "union" => disk.clone(),
        "index" => disk
            .into_iter()
            .filter(|(rel, _)| meta.index_rel_set.contains(rel))
            .collect(),
        other => {
            return Err(ErbccError::Usage(format!(
                "未知文件集策略: {other}（应为 all/index/union）"
            )))
        }
    };

    let mut files = Vec::with_capacity(selected.len());
    for (rel, path) in selected {
        let bytes = std::fs::read(&path)
            .map_err(|e| ErbccError::Io(format!("读文件 {path:?} 失败: {e}")))?;
        let (encoding, has_bom, lossy) = detect_encoding(&bytes);
        let binary = contains_nul(&bytes, 8192);
        let decode_replacement = decode_replacement_of(&encoding, has_bom, lossy, binary);
        files.push(CorpusFile {
            rel_path: rel,
            bytes,
            encoding,
            has_bom,
            lossy,
            binary,
            decode_replacement,
        });
    }
    Ok(files)
}

/// 冻结签名（契约 §2）：复刻 `Main.kt:644-657` 的 `loadRenameDic`。
///
/// `readText`（UTF-8，畸形退化）→ 按 `\n`/`\r` 双切分 → 每段 `trimStart('\u{FEFF}').trim()`
/// → 空或 `;` 开头跳过 → `split(',')` 恰两列才登记 → `map["[[列2.trim()]]"] = 列1.trim()`。
///
/// Kotlin 用 LinkedHashMap（重复键覆盖值、保留首插位置）；Rust 侧同样按首次出现顺序输出
/// 去重后的 `(键, 值)`，与 `renameDic.size`（唯一键数）一致。
pub fn rename_dict(csv_path: &str) -> Outcome<Vec<(String, String)>> {
    let bytes = std::fs::read(csv_path)
        .map_err(|e| ErbccError::Io(format!("读置換名辞書 {csv_path:?} 失败: {e}")))?;
    let text = String::from_utf8_lossy(&bytes);
    let mut order: Vec<String> = Vec::new();
    let mut map: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    for segment in text.split(['\n', '\r']) {
        let t = segment
            .trim_start_matches('\u{FEFF}')
            .trim(); // Kotlin: trimStart('\uFEFF').trim()
        if t.is_empty() || t.starts_with(';') {
            continue;
        }
        let parts: Vec<&str> = t.split(',').collect();
        if parts.len() == 2 {
            let key = format!("[[{}]]", parts[1].trim());
            let value = parts[0].trim().to_string();
            map.entry(key.clone()).or_insert_with(|| {
                order.push(key.clone());
                value.clone()
            });
            // 覆盖语义与 LinkedHashMap.put 一致：重复键更新值、不移动插入序。
            *map.get_mut(&key).unwrap() = value;
        }
    }
    Ok(order
        .into_iter()
        .map(|k| {
            let v = map.remove(&k).unwrap_or_default();
            (k, v)
        })
        .collect())
}
