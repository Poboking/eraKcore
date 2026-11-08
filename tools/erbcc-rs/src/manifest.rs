use std::path::{Path, PathBuf};

use crate::digest::sha256_file;
use crate::types::{ErbccError, HashEntry, Outcome};

/// 确定性清单文本。口径权威 = `compile/ErbcArtifacts.kt:241-290`（`manifestText`）。
///
/// 行序与字段名逐行对齐 Kotlin：`# erbc compile manifest …` 注释行、`manifest-format 2`、
/// `format-version`/`container-version`（恒取容器真实版本，不得写死 1）、`container-magic ERBC`、
/// `erbcc <引擎版本>`、`compiler-digest`/`compiler-sources`（固定排在 `erbcc` 之后）、
/// `build-mode full|limited|unknown`、`file-limit N`（仅受限档）、`corpus-root <绝对规范路径>`、
/// `source-files N`、`binary-skipped N`（仅有 stats 时）、逐条 `source …`、`artifacts N` +
/// 逐条 `artifact …`、`program <键> <值>` 八行（仅 stats 非空时）。
/// LF 换行、无时间戳、无主机名。
pub const MANIFEST_FORMAT: u32 = 2;

/// 一次编译的规模读数（清单 `program …` 八行 + `binary-skipped` + `file-limit` 的来源）。
///
/// 字段与 Kotlin `CompileStats`（`CorpusCompile.kt:63-94`）里进清单的那些一一对应；
/// 不进清单的读数（`corpusFiles`/`slices`/`compiled`/`compileFailures`/`parseFailures`/
/// `eventEntries`）不在此列 —— 清单只登记规约要求的八行 + 两个档位读数。
pub struct ProgramStats {
    pub instructions: u64,
    pub statements: u64,
    pub functions: u64,
    pub strings: u64,
    pub constants: u64,
    pub expressions: u64,
    pub global_slots: u64,
    pub entry_function: u64,
    /// `binary-skipped`（CMP-64，`ErbcArtifacts.kt:269` ← `CorpusCompile.kt:93`）。
    pub binary_skipped: u64,
    /// 生效的文件上限（CMP-65，`CorpusCompile.kt:85`）：受限档非 null。
    pub file_limit: Option<u64>,
}

impl ProgramStats {
    /// 清单的 `build-mode` 取值（`ErbcArtifacts.kt:263`）：
    /// `stats` 缺失 = `unknown`（旧调用点，不谎报 full）；`fileLimit` 缺失 = `full`；否则 `limited`。
    pub fn build_mode_of(stats: Option<&ProgramStats>, file_limit: Option<u64>) -> &'static str {
        match stats {
            None => "unknown",
            Some(_s) => {
                if effective_file_limit(stats, file_limit).is_none() {
                    "full"
                } else {
                    "limited"
                }
            }
        }
    }
}

/// `file-limit` 行取哪个来源：形参优先，退回 `stats.file_limit`。
///
/// Kotlin 侧只有 `stats` 一个来源（`ErbcArtifacts.kt:264` 的 `stats?.fileLimit`）；Rust 的
/// 冻结签名里 `file_limit` 与 `stats` 是两个入参，故这里定「stats 为 None 时一律不写
/// `file-limit`」（与 Kotlin 的 `stats?.` 短路一致），stats 存在时以显式形参为准、
/// 缺省退回 `stats.file_limit`。两处在真实调用链上必然同源（都来自 `CompileStats.fileLimit`）。
fn effective_file_limit(stats: Option<&ProgramStats>, file_limit: Option<u64>) -> Option<u64> {
    if stats.is_none() {
        return None;
    }
    file_limit.or(stats.and_then(|s| s.file_limit))
}

/// 确定性 manifest 文本（= Kotlin `ErbcArtifacts.manifestText`，`ErbcArtifacts.kt:241-290`）。
///
/// 行序**逐行**对齐（每行右侧标注 Kotlin 行号）：
/// 1. `# erbc compile manifest —— 确定性构建清单（同一语料两次编译逐字节相同）`（`:250`）
/// 2. `manifest-format 2`（`:251`，[MANIFEST_FORMAT]）
/// 3. `format-version <容器真实版本>`（`:252`，= `ErbcFormat.LATEST_VERSION`）
/// 4. `container-magic ERBC`（`:253`，= `ErbcFormat.MAGIC_TEXT`，`ErbcFormat.kt:39`）
/// 5. `container-version <容器真实版本>`（`:254`）
/// 6. `erbcc <引擎版本>`（`:255`，= `Version.VERSION`，经 `types::erbcc_version` 读源码）
/// 7. `compiler-digest <hex>` / 8. `compiler-sources <N>`（`:258-259`，固定排在 `erbcc` 之后）
/// 9. `build-mode full|limited|unknown`（`:263`）
/// 10. `file-limit N`（`:264`，仅受限档）
/// 11. `corpus-root <绝对规范路径>`（`:265`，Kotlin `toAbsolutePath().normalize()`）
/// 12. `source-files N`（`:266`）
/// 13. `binary-skipped N`（`:269`，仅 `stats` 非空）
/// 14. 逐条 `source <rel> <bytes> <sha256>`（`:270-273`）
/// 15. `artifacts N` + 逐条 `artifact <rel> <bytes> <sha256>`（`:274-278`，CMP-74 随包文件集）
/// 16. `program …` 八行（`:279-288`，仅 `stats` 非空；键名顺序照 Kotlin）
///
/// 全部行以 LF 结尾；不含时间戳、主机名、绝对路径（除 `corpus-root` 这一条规约要求的登记）。
#[allow(clippy::too_many_arguments)]
pub fn manifest_text(
    container_version: u16,
    erbcc_version: &str,
    compiler_digest: &str,
    compiler_sources: usize,
    build_mode: &str,
    file_limit: Option<u64>,
    corpus_root: &str,
    sources: &[HashEntry],
    artifacts: &[HashEntry],
    stats: Option<&ProgramStats>,
) -> Outcome<String> {
    let mut sb = String::new();
    sb.push_str("# erbc compile manifest —— 确定性构建清单（同一语料两次编译逐字节相同）\n");
    sb.push_str(&format!("manifest-format {MANIFEST_FORMAT}\n"));
    sb.push_str(&format!("format-version {container_version}\n"));
    sb.push_str("container-magic ERBC\n");
    sb.push_str(&format!("container-version {container_version}\n"));
    sb.push_str(&format!("erbcc {erbcc_version}\n"));
    sb.push_str(&format!("compiler-digest {compiler_digest}\n"));
    sb.push_str(&format!("compiler-sources {compiler_sources}\n"));
    sb.push_str(&format!("build-mode {build_mode}\n"));
    if let Some(limit) = effective_file_limit(stats, file_limit) {
        sb.push_str(&format!("file-limit {limit}\n"));
    }
    sb.push_str(&format!("corpus-root {corpus_root}\n"));
    sb.push_str(&format!("source-files {}\n", sources.len()));
    if let Some(st) = stats {
        sb.push_str(&format!("binary-skipped {}\n", st.binary_skipped));
    }
    for s in sources {
        sb.push_str(&format!("source {} {} {}\n", s.rel_path, s.bytes, s.sha256));
    }
    sb.push_str(&format!("artifacts {}\n", artifacts.len()));
    for a in artifacts {
        sb.push_str(&format!(
            "artifact {} {} {}\n",
            a.rel_path, a.bytes, a.sha256
        ));
    }
    if let Some(st) = stats {
        sb.push_str(&format!("program instructions {}\n", st.instructions));
        sb.push_str(&format!("program statements {}\n", st.statements));
        sb.push_str(&format!("program functions {}\n", st.functions));
        sb.push_str(&format!("program strings {}\n", st.strings));
        sb.push_str(&format!("program constants {}\n", st.constants));
        sb.push_str(&format!("program expressions {}\n", st.expressions));
        sb.push_str(&format!("program global-slots {}\n", st.global_slots));
        sb.push_str(&format!("program entry-function {}\n", st.entry_function));
    }
    Ok(sb)
}

/// 临时文件 + 原子改名落盘。口径 = `compile/AtomicWrite.kt:18-39`。
///
/// 1. 父目录不存在先创建（`AtomicWrite.kt:29`）；
/// 2. 写同目录 `<name>.tmp`（后缀常量 `TEMP_SUFFIX`，`AtomicWrite.kt:21`，与段 3 侧车同形）；
/// 3. 原子改名到目标，已存在则替换（`AtomicWrite.kt:33` 的 `REPLACE_EXISTING + ATOMIC_MOVE`）；
/// 4. 任何一步失败都删掉临时文件再把错误原样抛出 —— 目标文件保持旧内容
///    （不存在时保持不存在，`AtomicWrite.kt:34-37`）。
///
/// Windows 上 `std::fs::rename` 走 `MoveFileExW`（带 `MOVEFILE_REPLACE_EXISTING`），
/// 与 Java 的 `ATOMIC_MOVE` 同一底层语义；跨卷改名两侧都会失败（产物目录同目录写，不触发）。
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Outcome<()> {
    const TEMP_SUFFIX: &str = ".tmp";
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| ErbccError::Io(format!("创建目录失败 {}: {e}", parent.display())))?;
    }
    let name = path
        .file_name()
        .ok_or_else(|| ErbccError::Io(format!("落盘路径没有文件名：{}", path.display())))?;
    let tmp = path.with_file_name(format!("{}{TEMP_SUFFIX}", name.to_string_lossy()));
    std::fs::write(&tmp, bytes).map_err(|e| ErbccError::Io(format!("写临时文件失败 {}: {e}", tmp.display())))?;
    match std::fs::rename(&tmp, path) {
        Ok(()) => Ok(()),
        Err(e) => {
            // 与 Kotlin 的 `runCatching { Files.deleteIfExists(tmp) }` 一致：清理失败不遮盖原错误。
            let _ = std::fs::remove_file(&tmp);
            Err(ErbccError::Io(format!(
                "原子改名失败 {} -> {}：{e}",
                tmp.display(),
                path.display()
            )))
        }
    }
}

/// 产物目录名里的版本号 = `ErbcFormat.VERSION_1`（`ErbcFormat.kt:42`）。
///
/// 目录名 `erbc.v1` 是**冻结的跨模块路径常量**，与容器真实版本（清单的 `container-version`）
/// 无关（`ErbcArtifacts.kt:50-55`）；容器真实版本恒取 `ErbcFormat.LATEST_VERSION`（= 2，
/// `ErbcFormat.kt:55`），由调用方从磁盘容器头读出来传进 [manifest_text]，
/// **不得写死 1**（规约 §8.7，`specs/erbc-format.md:490`）。
pub const ARTIFACT_DIR_VERSION: u16 = 1;

/// 元数据侧车文件名（Kotlin `BootMetaWriter.FILE_NAME`，`BootMetaWriter.kt:57`；
/// 与 `program.erbc` 同目录）。
pub const META_FILE_NAME: &str = "program.meta";

/// 读容器头里的真实版本（`program.erbc` 偏移 4 的 u16 小端，`ErbcFormat.kt:14-19`）。
///
/// 清单的 `format-version`/`container-version` 必须等于它，而不是任何写死的常量。
pub fn container_version_of(program_erbc: &Path) -> Outcome<u16> {
    let mut bytes = [0u8; 6];
    let f = std::fs::File::open(program_erbc)
        .map_err(|e| ErbccError::Io(format!("打开容器失败 {}: {e}", program_erbc.display())))?;
    use std::io::Read;
    let mut f = f;
    f.read_exact(&mut bytes)
        .map_err(|e| ErbccError::Io(format!("读容器头失败 {}: {e}", program_erbc.display())))?;
    if &bytes[0..4] != b"ERBC" {
        return Err(ErbccError::Io(format!(
            "容器 magic 不符：{} 前 4 字节不是 ERBC",
            program_erbc.display()
        )));
    }
    Ok(u16::from_le_bytes([bytes[4], bytes[5]]))
}

/// 语料 ERB/ERH 清单（= Kotlin `CorpusCompile.listCorpusFiles`，`CorpusCompile.kt:205-218`）。
///
/// 判据逐条：递归、只普通文件、扩展名 `.erb`/`.erh`（**ignoreCase**）、排除金标采集副本
/// `GOLDEN_*`（`:198` 的 `isCollectionCopy`，也是 ignoreCase）、按**规范化绝对路径字符串**排序。
/// 目录不存在返回空表（`:206`）。
///
/// 新鲜度对账（`ArtifactFreshness.kt:250`）与清单生成（`CorpusCompile.kt:263`）共用这一份口径，
/// Rust 侧也只在 [crate::freshness] 与本模块共用这一个实现，不另立规则。
pub fn list_corpus_files(root: &Path) -> Vec<PathBuf> {
    if !root.is_dir() {
        return Vec::new();
    }
    let abs = crate::types::absolutize(root);
    let mut out: Vec<PathBuf> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![abs];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            if !path.is_file() {
                continue;
            }
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().to_lowercase())
                .unwrap_or_default();
            if !(name.ends_with(".erb") || name.ends_with(".erh")) {
                continue;
            }
            if name.starts_with("golden_") {
                continue;
            }
            out.push(crate::types::absolutize(&path));
        }
    }
    out.sort_by_key(|p| p.to_string_lossy().to_string());
    out
}

/// 清单的 `source` 条目（= `CorpusCompile.kt:775-785` + `ErbcArtifacts.kt:187` 的排序）。
///
/// 相对路径 = 相对语料根、`\` → `/`；读不出来的文件跳过并在 Kotlin 侧记 `notes`
/// （`:781`），Rust 侧这里没有 notes 面 —— 真实调用链上 `CorpusCompile` 已经产出过
/// 同一份清单，本函数主要服务于测试与 `check` 的新增文件判定。
pub fn source_entries(corpus_root: &Path) -> Outcome<Vec<HashEntry>> {
    let root = crate::types::absolutize(corpus_root);
    let mut out = Vec::new();
    for path in list_corpus_files(&root) {
        let rel = path
            .strip_prefix(&root)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_else(|_| path.to_string_lossy().replace('\\', "/"));
        let bytes = std::fs::metadata(&path)
            .map_err(|e| ErbccError::Io(format!("源文件读不出来（不进 manifest）: {rel}: {e}")))?;
        out.push(HashEntry {
            sha256: sha256_file(&path)?,
            bytes: bytes.len(),
            rel_path: rel,
        });
    }
    out.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));
    Ok(out)
}
