use std::path::{Path, PathBuf};

use crate::container::{self, OpenedContainer};
use crate::digest::{self, CompilerDigest};
use crate::kernel;
use crate::manifest::{list_corpus_files, ARTIFACT_DIR_VERSION, META_FILE_NAME};
use crate::types::{absolutize, current_dir, find_repo_root, ErbccError, HashEntry, Outcome, MARKER_REL};

/// 产物新鲜度检查（**不重编**）。口径权威 = `compile/ArtifactFreshness.kt`：
/// 容器字节数/摘要/段表、每个源文件摘要、`program.meta` 的 `program-sha256`、
/// `program.stmt.idx` 的语句条数、`compiler-digest` × 当前 core-vm 源码树。
/// 清单无 `compiler-digest` 行或不等 ⇒ 判旧产物（与 app staging 的 CMP-66 同一判据）。
///
/// `program.meta` 分两层：**头层**（[read_meta_head]，三行 + 配对凭证）本文件自己算；
/// **深层**（`BootMetadata.read` 的全文状态机）由 [meta_leg_via_jvm] **委托 JVM 真跑**取回结论
/// （rust-meta-parity：不在 Rust 侧重写侧车语义，那是第二实现方漂移的根源）。
///
/// 问题分类与 Kotlin 的 `FreshnessIssueKind`（`ArtifactFreshness.kt:13-68`）逐条同名，
/// [Issue::kind] 直接存这些字符串 —— `summary()` 的 `"$kind: $detail"` 形态
/// （`ArtifactFreshness.kt:72`）因此可逐字节对拍。
pub const KIND_MANIFEST_MISSING: &str = "MANIFEST_MISSING";
pub const KIND_MANIFEST_UNREADABLE: &str = "MANIFEST_UNREADABLE";
pub const KIND_ARTIFACT_MISSING: &str = "ARTIFACT_MISSING";
pub const KIND_ARTIFACT_SIZE: &str = "ARTIFACT_SIZE";
pub const KIND_ARTIFACT_SHA: &str = "ARTIFACT_SHA";
pub const KIND_CONTAINER_INVALID: &str = "CONTAINER_INVALID";
pub const KIND_SOURCE_MISSING: &str = "SOURCE_MISSING";
pub const KIND_SOURCE_CHANGED: &str = "SOURCE_CHANGED";
pub const KIND_COMPILER_STALE: &str = "COMPILER_STALE";
pub const KIND_SOURCE_ADDED: &str = "SOURCE_ADDED";
pub const KIND_META_MISSING: &str = "META_MISSING";
pub const KIND_META_UNREADABLE: &str = "META_UNREADABLE";
pub const KIND_META_STALE: &str = "META_STALE";
pub const KIND_SIDECAR_MISSING: &str = "SIDECAR_MISSING";
pub const KIND_SIDECAR_UNREADABLE: &str = "SIDECAR_UNREADABLE";
pub const KIND_SIDECAR_STALE: &str = "SIDECAR_STALE";

/// 清单文件名（`ArtifactFreshness.kt:135`）。
pub const MANIFEST_NAME: &str = "manifest.txt";
/// 段 3 侧车文件名（`StmtOffsetSidecar.kt:69`）。
pub const SIDECAR_FILE_NAME: &str = "program.stmt.idx";
/// 容器后缀：判定「哪一条 `artifact` 是容器」用它（`ProgramLoader.kt:147`）。
const ARTIFACT_SUFFIX: &str = ".erbc";

/// 一条新鲜度问题（Kotlin `FreshnessIssue`，`ArtifactFreshness.kt:71-73`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Issue {
    pub kind: String,
    pub detail: String,
}

impl Issue {
    fn new(kind: &str, detail: String) -> Issue {
        Issue { kind: kind.to_string(), detail }
    }
}

impl std::fmt::Display for Issue {
    /// `ArtifactFreshness.kt:72` 的 `override fun toString(): String = "$kind: $detail"`。
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.kind, self.detail)
    }
}

/// 一次新鲜度检查的结果（Kotlin `FreshnessReport`，`ArtifactFreshness.kt:81-96`）。
///
/// `ok` = 无问题（`:86`）。`out_dir` 与 `compiler_digest` 是 Kotlin 侧 `FreshnessReport`
/// 的两个字段（`:82`、`:84`），[Report::summary] 的措辞要用到它们，故本结构一并携带 ——
/// 调用方（`cmd_check`）只需 `println!(report.summary())`，与 Kotlin `Main.kt:421` 同形。
pub struct Report {
    pub ok: bool,
    pub issues: Vec<Issue>,
    /// 本次检查时**当前编译器源码树**重算出的指纹；定位不到源码树时为 None
    /// （此时必有 [KIND_COMPILER_STALE]，`ArtifactFreshness.kt:78-79`）。
    pub compiler_digest: Option<String>,
    /// 检查的产物目录（绝对规范路径，= Kotlin `check` 里的 `root`，`:152`）。
    pub out_dir: PathBuf,
}

impl Report {
    /// 摘要文本：与 Kotlin `ArtifactFreshness.summary()`（`ArtifactFreshness.kt:89-95`）逐字对齐。
    ///
    /// * 无问题：`"$outDir: 产物与语料/编译器一致（容器/侧车/源文件摘要/编译器指纹全对齐
    ///   [，compiler-digest <hex>]）"` —— 方括号里的后缀只在指纹可用时追加；
    /// * 有问题：`"$outDir: N 条新鲜度问题\n"` + 每条前缀两空格。
    pub fn summary(&self) -> String {
        let out = self.out_dir.display();
        if self.ok {
            let tail = match &self.compiler_digest {
                Some(d) => format!("，compiler-digest {d}"),
                None => String::new(),
            };
            format!("{out}: 产物与语料/编译器一致（容器/侧车/源文件摘要/编译器指纹全对齐{tail}）")
        } else {
            let mut s = format!("{out}: {} 条新鲜度问题\n", self.issues.len());
            for (i, issue) in self.issues.iter().enumerate() {
                s.push_str("  ");
                s.push_str(&issue.to_string());
                if i + 1 < self.issues.len() {
                    s.push('\n');
                }
            }
            s
        }
    }
}

// ======================================================================
// The .erbc container header / section table / flags-bit self-consistency checks are
// single-sourced in src/container.rs now. The former private copies (parse_header,
// require_bit, open_container, u32_at, ContainerHeader, SECTION_NAMES and the format
// constants) moved there line-by-line: validation criteria and Kotlin-verbatim error
// wording are unchanged. This file only consumes container::open / OpenedContainer.
// ======================================================================

// ======================================================================
// program.meta 头部（只取配对凭证 program-sha256）
// ======================================================================

/// 侧车头部解析结果。
struct MetaHead {
    /// `program-sha256` 行：`-` = None（`BootMetadata.kt:1463-1465`）。
    program_sha256: Option<String>,
}

/// 读 `program.meta` 的头部三行（`format-version` / `engine` / `program-sha256`）。
///
/// **口径范围（诚实声明）**：本函数只解头部三行，Kotlin 侧走 `BootMetadata.read`
/// （`BootMetadata.kt:414-426`，`core-vm` 里那台全文状态机 `:1382-1818`）做**全文**解析
/// （严格 UTF-8 + 全部段的记录序/计数/载荷校验）。**深处坏而头部好**的侧车本函数一律返回 Ok ——
/// 这不是放行：`check_sidecars` 在 Ok 分支上必须再走 [meta_leg_via_jvm]（委托 JVM 真跑
/// `BootMetadata.read`，把 `META_UNREADABLE` / `META_STALE` 原文取回来），判据面与 Kotlin
/// `ArtifactFreshness.kt:336-349` 同宽。Rust 侧不重写侧车语义（契约 §0 铁律 1：那是第二实现方
/// 漂移的根源），头层这几条只是**便宜的先筛**，一条没放宽（`kotlin-vs-rust.tsv` 的头部族
/// 逐字同文读数就是它的现状钉）。
///
/// 头部判据逐条对齐 `BootMetaCodec.parseReader`（`BootMetadata.kt:1444-1465`）：
/// 空侧车 / 首条必须 `format-version` / 版本 1..=FORMAT_VERSION / 次条 `engine` / 再次 `program-sha256`。
fn read_meta_head(path: &Path) -> Result<MetaHead, String> {
    const META_FORMAT_VERSION: i32 = 1; // BootMetadata.kt:386
    let raw = std::fs::read(path)
        .map_err(|e| format!("BootMetaFormatException: {}: 读取失败（IOException: {e}）", path.display()))?;
    // 严格 UTF-8（CMP-07，`BootMetadata.kt:405-410`）：坏字节报错，不许换成 U+FFFD 静默进模型。
    let text = std::str::from_utf8(&raw).map_err(|_| {
        format!(
            "BootMetaFormatException: {}: 严格 UTF-8 解码失败（MalformedInputException）",
            path.display()
        )
    })?;
    // 记录切分口径（`BootMetadata.kt:1256-1287`）：只按 `\n` 切、剥**一个**尾部 CR、空行跳过但计入行号。
    let mut records: Vec<(usize, &str)> = Vec::new();
    for (i, line) in text.split('\n').enumerate() {
        let line_no = i + 1;
        let stripped = line.strip_suffix('\r').unwrap_or(line);
        if stripped.trim().is_empty() {
            continue;
        }
        records.push((line_no, stripped));
    }
    if records.is_empty() {
        return Err("BootMetaFormatException: 侧车为空".to_string());
    }
    let (line_no, first) = records[0];
    let (kw, rest) = split_record(first);
    if kw != "format-version" {
        return Err(format!(
            "BootMetaFormatException: 第 {line_no} 行：首条记录必须是 format-version"
        ));
    }
    let version: i32 = rest.trim().parse().map_err(|_| {
        format!("BootMetaFormatException: 第 {line_no} 行：format-version 不是整数")
    })?;
    if version > META_FORMAT_VERSION {
        return Err(format!(
            "BootMetaFormatException: 侧车格式版本 {version} 高于本引擎支持的 {META_FORMAT_VERSION}（由更新的 erbcc 写出）"
        ));
    }
    if version < 1 {
        return Err(format!(
            "BootMetaFormatException: 侧车格式版本非法: {version}"
        ));
    }
    expect(records.get(1), "engine")?;
    let sha_rec = expect(records.get(2), "program-sha256")?;
    let payload = sha_rec.trim();
    // 写侧无配对哈希时写裸短横线（`BootMetadata.kt:1463-1465`），非空时写引号十六进制串。
    let program_sha256 = if payload == "-" {
        None
    } else {
        Some(unquote(payload).ok_or_else(|| {
            "BootMetaFormatException: program-sha256 必须是引号串".to_string()
        })?)
    };
    Ok(MetaHead { program_sha256 })
}

/// 一条记录拆成 `(关键字, 载荷)`（`BootMetaCodec.bind` 的口径：第一个空格前是关键字）。
fn split_record(line: &str) -> (&str, &str) {
    match line.find(' ') {
        Some(sp) => (&line[..sp], &line[sp + 1..]),
        None => (line.trim(), ""),
    }
}

/// `Cursor.next(expect)`（`BootMetadata.kt:1415-1423`）的头部版判据。
fn expect<'a>(rec: Option<&'a (usize, &'a str)>, want: &str) -> Result<&'a str, String> {
    let rec = rec.ok_or_else(|| {
        format!("BootMetaFormatException: 侧车在 {want} 处提前结束")
    })?;
    let (kw, payload) = split_record(rec.1);
    if kw != want {
        return Err(format!(
            "BootMetaFormatException: 第 {} 行：期望 {want}，实际 {kw}",
            rec.0
        ));
    }
    Ok(payload)
}

/// 引号串取内容（`atomString` 的头部用法：整条载荷就是一个 `"..."`）。
fn unquote(payload: &str) -> Option<String> {
    let p = payload.trim();
    p.strip_prefix('"')
        .and_then(|s| s.strip_suffix('"'))
        .map(|s| s.to_string())
}

// ======================================================================
// 深判据腿（program.meta 的全文解析委托给 JVM —— 唯一权威是 BootMetadata.read）
// ======================================================================

/// 深判据腿的签名：`(产物根, 语料根) -> 只含 `META_*` 三条 kind 的结论`。
///
/// 存在理由：`check` 的 Rust 腿只做**便宜的先筛**（头层 [read_meta_head]、容器、清单），
/// 而 `program.meta` 「头部三行之后」的判据权威在 `core-vm` 那台全文状态机里
/// （`BootMetadata.kt:414-426` + `:1382-1818`）。Rust 侧重写它就是第二实现方漂移
/// （契约 §0 铁律 1），所以**委托**：真跑一次 JVM 的 `erbcc check`，把它的 `META_*`
/// 结论原文取回来。测试用这一层注入替身（恒放行 / 抛错 / 计数），生产走 [meta_leg_via_jvm]。
pub type MetaLeg<'a> = dyn Fn(&Path, &Path) -> Outcome<Vec<Issue>> + 'a;

/// [meta_leg_via_jvm] 取不到仓库根时的报红文案（**不猜路径**，口径同 [crate::kernel]）。
pub const META_LEG_NO_REPO_ROOT: &str = "program.meta 深判据腿起不来：定位不到仓库根";

/// 生产深判据腿：起一条 JVM 真跑 Kotlin `erbcc check`（内含 `BootMetadata.read`），
/// **只**取回 `META_MISSING` / `META_UNREADABLE` / `META_STALE` 三条 kind 的结论原文。
///
/// 三条口径要点：
/// 1. **只取 meta**：其余判据（清单行、容器字节/摘要、`program.stmt.idx`、逐源 sha、
///    语料新增）一律丢弃 —— 那些 Rust 侧自己算且已与 Kotlin 同判
///    （`kotlin-vs-rust.tsv` 的 sidecar 16 档 / manifest 19 档两侧同判就是这条的读数）。
///    接进来会让 Rust 的 `check` 变成 Kotlin 的复读机，头层与清单族的在册读数也会重归面。
/// 2. **拿不到报告就是错，不是「深判据没意见」**：退出码不在 0/1 契约内（`Main.kt:406-423`
///    只会 0/1，2 = 参数错 ⇒ 说明 argv 拼坏）或 stdout 不是 `summary()` 的形状 ⇒
///    [ErbccError::Io]。宁可是 `check` 整体报红（`cli.rs::finish` 折成退出码 1），
///    也不许把「没问出结论」当成「结论是放行」（契约 §0.6）。
/// 3. **语料根原样传下去**：腿的 META 结论只由 `<out>/erbc.v1/program.meta` 决定
///    （`ArtifactFreshness.kt:337` 只喂 `metaPath`），但传**同一份**语料是纪律 ——
///    传一个空的替身目录虽然也能拿到 meta 结论，那是拿别人的判据面作弊（两侧其余判据的
///    形状就不再是「同一次检查」了），记在这里防止后来人优化。
pub fn meta_leg_via_jvm(out_dir: &Path, corpus_root: &Path) -> Outcome<Vec<Issue>> {
    let repo_root = find_repo_root(out_dir).or_else(|| find_repo_root(&current_dir())).ok_or_else(|| {
        ErbccError::Io(format!(
            "{META_LEG_NO_REPO_ROOT}（从 {} / 工作目录 {} 往上都没有 {MARKER_REL}）：\
             委托 JVM 跑 BootMetadata.read 需要 classpath，不猜路径。",
            out_dir.display(),
            current_dir().display()
        ))
    })?;
    let out = kernel::check_via_jvm(&repo_root, corpus_root, out_dir, &kernel::os_env)?;
    if out.exit_code != 0 && out.exit_code != 1 {
        return Err(ErbccError::Io(format!(
            "program.meta 深判据腿（JVM `erbcc check`）退出码 {}，不在 0/1 契约内：kernel 侧文案 → {}",
            out.exit_code,
            out
                .stderr
                .lines()
                .find(|l| !l.trim().is_empty())
                .unwrap_or("(stderr 为空)")
        )));
    }
    if !kernel::is_kotlin_check_report(&out.stdout) {
        return Err(ErbccError::Io(format!(
            "program.meta 深判据腿（JVM `erbcc check`，rc={}）的 stdout 不是新鲜度报告的形状，\
             拿不到深判据就当没意见是假绿：stdout → {}｜stderr → {}",
            out.exit_code,
            out.stdout.lines().next().unwrap_or("(stdout 为空)"),
            out
                .stderr
                .lines()
                .find(|l| !l.trim().is_empty())
                .unwrap_or("(stderr 为空)")
        )));
    }
    let mut issues = Vec::new();
    for line in kernel::kotlin_check_issue_lines(&out.stdout) {
        // `ArtifactFreshness.kt:72` 的 `"$kind: $detail"`：按第一个 `": "` 折成 kind/detail，
        // 折出来的 Issue 再 Display 一次必须逐字回到这行（[Issue::to_string] 就是那个形状）。
        let Some((kind, detail)) = line.split_once(": ") else {
            return Err(ErbccError::Io(format!(
                "program.meta 深判据腿的 issue 行不带 `KIND: detail` 形状：{line}"
            )));
        };
        if META_KINDS.contains(&kind) {
            issues.push(Issue { kind: kind.to_string(), detail: detail.to_string() });
        }
    }
    Ok(issues)
}

/// 深判据腿的调用现场（产物根 + 语料根 + 腿本体），一路从 [check_with_meta_leg] 传到侧车判据。
pub struct MetaLegCtx<'a> {
    pub out_dir: &'a Path,
    pub corpus_root: &'a Path,
    pub leg: &'a MetaLeg<'a>,
}

/// 三个 `META_*` kind（与 [KIND_META_MISSING] / [KIND_META_UNREADABLE] / [KIND_META_STALE] 同值，
/// 只是腿的过滤器要一个数组形状）。
pub const META_KINDS: [&str; 3] = [KIND_META_MISSING, KIND_META_UNREADABLE, KIND_META_STALE];

// ======================================================================
// program.stmt.idx（StmtOffsetSidecar.read 的判据副本）
// ======================================================================

/// 侧车 magic（`StmtOffsetSidecar.kt:74`）。
const SIDECAR_MAGIC: &[u8; 8] = b"ERSTMT01";
/// v1 头 20 字节 / v2 头 24 字节（`StmtOffsetSidecar.kt:85-88`）。
const SIDECAR_HEADER_BYTES_LEGACY: usize = 20;
const SIDECAR_HEADER_BYTES: usize = 24;
const SIDECAR_VERSION_LEGACY: i32 = 1;
const SIDECAR_VERSION_PAIRED: i32 = 2;

/// 读侧车并返回语句条数；None = 结构不可信（`StmtOffsetSidecar.read`，`:166-182`）。
///
/// 判据逐条：文件缺失 / 长度 < 20 / 长度 > Int.MAX / 短到读不满 20 字节 / magic 不符 /
/// 版本非 1|2 / count 或 recordBytes 为负 / `size != headerBytes + count*4` ⇒ null
/// （装载侧同样回落到扫描，新鲜度侧据此报 `SIDECAR_UNREADABLE`）。
fn read_sidecar_count(path: &Path) -> Option<usize> {
    let raw = std::fs::read(path).ok()?;
    let size = raw.len();
    if size < SIDECAR_HEADER_BYTES_LEGACY || size > u32::MAX as usize {
        return None;
    }
    // 头按 v2 的 24 字节解析；短到不足 24 的残件尾字节按 0 补（`StmtOffsetSidecar.kt:137-141`）。
    let mut head = [0u8; SIDECAR_HEADER_BYTES];
    let take = std::cmp::min(size, SIDECAR_HEADER_BYTES);
    head[..take].copy_from_slice(&raw[..take]);
    if &head[..8] != SIDECAR_MAGIC {
        return None;
    }
    let version = i32::from_le_bytes([head[8], head[9], head[10], head[11]]);
    if version != SIDECAR_VERSION_LEGACY && version != SIDECAR_VERSION_PAIRED {
        return None;
    }
    let count = i32::from_le_bytes([head[12], head[13], head[14], head[15]]);
    let record_bytes = i32::from_le_bytes([head[16], head[17], head[18], head[19]]);
    if count < 0 || record_bytes < 0 {
        return None;
    }
    let header_bytes = if version >= SIDECAR_VERSION_PAIRED {
        SIDECAR_HEADER_BYTES
    } else {
        SIDECAR_HEADER_BYTES_LEGACY
    };
    if size != header_bytes + count as usize * 4 {
        return None;
    }
    Some(count as usize)
}

// ======================================================================
// 清单解析（ArtifactFreshness.parse / entry 的判据副本）
// ======================================================================

/// 清单解析结果（只取新鲜度需要的字段，`ArtifactFreshness.kt:390-398`）。
struct ParsedManifest {
    /// 以 [ARTIFACT_SUFFIX] 结尾的那条 `artifact`（= 容器；CMP-74 起 `artifact` 不止一条）。
    artifact: Option<HashEntry>,
    sources: Vec<HashEntry>,
    compiler_digest: Option<String>,
    compiler_sources: Option<usize>,
}

/// 解析 `manifestText` 的文本（`ArtifactFreshness.kt:411-429`）。
///
/// 相对路径可能含空格（语料目录名就有），所以从**右**往左切：末两段是字节数与摘要，
/// 其余整段是相对路径（`:403-409` 的说明，实现见 `:431-441`）。不认识的行忽略
/// —— 清单允许追加字段，读侧不许对未知行报错。
fn parse_manifest(text: &str) -> ParsedManifest {
    let mut artifacts = Vec::new();
    let mut sources = Vec::new();
    let mut compiler_digest: Option<String> = None;
    let mut compiler_sources: Option<usize> = None;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("artifact ") {
            if let Some(e) = parse_entry(rest) {
                artifacts.push(e);
            }
        } else if let Some(rest) = line.strip_prefix("source ") {
            if let Some(e) = parse_entry(rest) {
                sources.push(e);
            }
        } else if let Some(rest) = line.strip_prefix("compiler-digest ") {
            let v = rest.trim();
            if !v.is_empty() {
                compiler_digest = Some(v.to_string());
            }
        } else if let Some(rest) = line.strip_prefix("compiler-sources ") {
            compiler_sources = rest.trim().parse::<usize>().ok();
        }
    }
    // CMP-74：容器 = 以 `.erbc` 结尾的那条，不是「最后一条」（取最后一条会把侧车当容器，
    // 六个用例一起红，`:406-409`）。找不到就 None，让上层报 MANIFEST_UNREADABLE。
    let container = artifacts
        .iter()
        .find(|e| e.rel_path.ends_with(ARTIFACT_SUFFIX))
        .cloned();
    ParsedManifest {
        artifact: container,
        sources,
        compiler_digest,
        compiler_sources,
    }
}

/// `artifact`/`source` 行尾段解析（`ArtifactFreshness.kt:431-441`）。
fn parse_entry(rest: &str) -> Option<HashEntry> {
    let sha_sep = rest.rfind(' ')?;
    if sha_sep == 0 {
        return None;
    }
    let size_sep = rest[..sha_sep].rfind(' ')?;
    if size_sep == 0 {
        return None;
    }
    let bytes: u64 = rest[size_sep + 1..sha_sep].parse().ok()?;
    let sha = &rest[sha_sep + 1..];
    let rel = &rest[..size_sep];
    if rel.is_empty() || sha.is_empty() {
        return None;
    }
    Some(HashEntry {
        rel_path: rel.to_string(),
        bytes,
        sha256: sha.to_string(),
    })
}

// ======================================================================
// 检查主流程
// ======================================================================

/// 编译器指纹判据（`ArtifactFreshness.checkCompiler`，`:270-313`）。
///
/// 返回当前源码树的指纹（定位不到 = None，同时已记一条 [KIND_COMPILER_STALE]）。
/// 三种形态共用一个 kind（语义都是「产物不是当前编译器编的」），detail 里区分。
///
/// 仓库根定位：Kotlin `locateRepoRoot(outDir)` 有三档提示（outDir → `user.dir` →
/// 本类 class 位置，`ErbcCompilerDigest.kt:117-134`）。冻结的 `check` 签名里没有仓库根形参，
/// 故本侧按 **outDir → 进程工作目录** 两档上溯；第三档（class 位置）在 Rust 无对应物 ——
/// 产物目录与 cwd 都在仓库外时两侧行为不同（Kotlin 仍可能定位到、本侧报 COMPILER_STALE），
/// 记 TODO(verify)。
fn check_compiler(
    manifest: &ParsedManifest,
    out_dir: &Path,
    issues: &mut Vec<Issue>,
) -> Option<CompilerDigest> {
    let repo_root = find_repo_root(out_dir).or_else(|| find_repo_root(&current_dir()));
    let current = match repo_root {
        None => {
            issues.push(Issue::new(
                KIND_COMPILER_STALE,
                format!(
                    "找不到编译器源码树（从 {} / 工作目录 / 本类 class 位置往上都没有 {MARKER_REL}）→ \
                     不能证明产物出自当前编译器，必须重编：./gradlew :tools:erbcc:compileCorpus",
                    out_dir.display()
                ),
            ));
            return None;
        }
        Some(root) => match digest::compiler_digest(&root) {
            Ok(result) => result,
            Err(e) => {
                issues.push(Issue::new(
                    KIND_COMPILER_STALE,
                    format!(
                        "无法重算编译器的 compiler-digest（{}：IllegalStateException: {}）→ \
                         不能证明产物出自当前编译器，必须重编：./gradlew :tools:erbcc:compileCorpus",
                        root.display(),
                        err_text(&e)
                    ),
                ));
                return None;
            }
        },
    };
    match &manifest.compiler_digest {
        None => issues.push(Issue::new(
            KIND_COMPILER_STALE,
            format!(
                "产物由旧编译器产出（清单没有 compiler-digest 行，是旧格式/旧编译器产物），必须重编：\
                 当前源码树 {} 共 {} 个源文件 → ./gradlew :tools:erbcc:compileCorpus",
                current.digest, current.file_count
            ),
        )),
        Some(declared) => {
            // Kotlin: `declared.equals(current.digest, ignoreCase = true)`（`:304`）。
            if !declared.eq_ignore_ascii_case(&current.digest) {
                let declared_sources = match manifest.compiler_sources {
                    Some(n) => n.to_string(),
                    None => "(缺)".to_string(),
                };
                issues.push(Issue::new(
                    KIND_COMPILER_STALE,
                    format!(
                        "产物由旧编译器产出，必须重编：清单 compiler-digest {declared}\
                         （compiler-sources {declared_sources} 个），当前源码树 {}\
                         （{} 个）→ ./gradlew :tools:erbcc:compileCorpus",
                        current.digest, current.file_count
                    ),
                ));
            }
        }
    }
    Some(current)
}

/// [ErbccError] 的消息文本（Kotlin 侧对应 `e.message`）。
fn err_text(e: &ErbccError) -> String {
    match e {
        ErbccError::Io(m) | ErbccError::Usage(m) | ErbccError::Parity(m) => m.clone(),
        ErbccError::NotPorted(m) => m.to_string(),
    }
}

/// 两个侧车的配对校验（`ArtifactFreshness.checkSidecars`，`:322-377`；容器已确认可解头）。
///
/// `program.meta` 这一段与 Kotlin 的**判定顺序**必须一致（`ArtifactFreshness.kt:336-349`）：
/// 先 `BootMetadata.read` 全文解析 —— 读不过 ⇒ 一条 `META_UNREADABLE`（**配对判据此时根本不跑**）；
/// 读过了 ⇒ 才比 `program-sha256` ⇒ 可能一条 `META_STALE`。Rust 侧把「全文解析」这一层
/// 委托给 [meta_leg_via_jvm]，于是：
/// * 头层 [read_meta_head] **报错** ⇒ 就报头层那条（同 kind；文案薄一档，`kotlin-vs-rust.tsv`
///   里 `SAME_KIND_DIFFERENT_WORDING` 那两档在册）。头层已经判死的档不再走腿 ——
///   Kotlin 也只报一条，走腿只会把同一条拒因换个措辞。
/// * 头层 **通过** ⇒ 先走腿；腿有 `META_*` 结论 ⇒ 那条就是 Kotlin 的原文，直接进单（逐字同文）；
///   腿没结论 ⇒ 才跑头层的配对比较（这条比较两侧同源，文案本来就逐字相同）。
fn check_sidecars(
    artifact_dir: &Path,
    container: &OpenedContainer,
    artifact_entry: &HashEntry,
    meta_leg: &MetaLegCtx<'_>,
    issues: &mut Vec<Issue>,
) -> Outcome<()> {
    // ---- program.meta ----
    let meta_path = artifact_dir.join(META_FILE_NAME);
    if !meta_path.is_file() {
        issues.push(Issue::new(
            KIND_META_MISSING,
            format!("缺 {META_FILE_NAME}（装载期元数据：函数表 / #DIM / 标签 / 数据节）"),
        ));
    } else {
        match read_meta_head(&meta_path) {
            Ok(head) => {
                // 深判据来自 JVM 腿（BootMetadata.read 的全文状态机），Rust 不重写侧车语义。
                let deep = (meta_leg.leg)(meta_leg.out_dir, meta_leg.corpus_root)?;
                if deep.is_empty() {
                    if head.program_sha256.as_deref() != Some(artifact_entry.sha256.as_str()) {
                        issues.push(Issue::new(
                            KIND_META_STALE,
                            format!(
                                "{META_FILE_NAME} 记 {}，容器摘要 {}（侧车与容器不是一对）",
                                head.program_sha256.unwrap_or_else(|| "-".to_string()),
                                artifact_entry.sha256
                            ),
                        ));
                    }
                } else {
                    issues.extend(deep);
                }
            }
            Err(message) => issues.push(Issue::new(
                KIND_META_UNREADABLE,
                format!("{META_FILE_NAME} 读取失败: {message}"),
            )),
        }
    }

    // ---- program.stmt.idx ----
    let idx_path = artifact_dir.join(SIDECAR_FILE_NAME);
    let stmt_count = container_statement_count(container);
    if !idx_path.is_file() {
        if stmt_count > 0 {
            issues.push(Issue::new(
                KIND_SIDECAR_MISSING,
                format!("缺 {SIDECAR_FILE_NAME}（装载期会回落逐条扫描段 3，慢但正确）"),
            ));
        }
        return Ok(());
    }
    let offsets = match read_sidecar_count(&idx_path) {
        None => {
            issues.push(Issue::new(
                KIND_SIDECAR_UNREADABLE,
                format!("{SIDECAR_FILE_NAME} 魔数/版本/尺寸不符（装载侧同样会忽略它）"),
            ));
            return Ok(());
        }
        Some(n) => n,
    };
    if stmt_count >= 0 && offsets != stmt_count as usize {
        issues.push(Issue::new(
            KIND_SIDECAR_STALE,
            format!(
                "{SIDECAR_FILE_NAME} 声明 {offsets} 条，容器段 3 声明 {stmt_count} 条"
            ),
        ));
    }
    Ok(())
}

/// 容器段 3 声明的语句条数（`ArtifactFreshness.containerStatementCount`，`:380-387`）。
///
/// 段 3 布局 = `u32 nodeCount, u32 语句数, …`（`ErbcWriter.kt:313-343`）⇒ 读段首 +4 的 u32。
/// 读不出来 = -1（Kotlin 的 catch 分支；此时侧车条数判据跳过，`stmtCount >= 0` 那一条）。
fn container_statement_count(container: &OpenedContainer) -> i32 {
    if !container.header.is_present(crate::container::SECTION_STATEMENTS) {
        return 0;
    }
    let offset = container.header.offset_of(crate::container::SECTION_STATEMENTS) as i64 + 4;
    match container.u32_at(offset) {
        Ok(v) => v,
        Err(_) => -1,
    }
}

/// 检查 `<outDir>/erbc.v1` 下的产物是否对应当前 [corpus_root] **与当前编译器源码树**
/// （= Kotlin `ArtifactFreshness.check`，`:151-258`；**不重编**）。
///
/// 生产口径：**头层先筛 + `program.meta` 的深判据委托 JVM**（[meta_leg_via_jvm]，真跑
/// `BootMetadata.read`）。这条腿会给 `check` 加一次 JVM 起进程 + 全文侧车解析的开销，
/// 读数见 `tests/meta_deep_parity.rs` 的代价用例与交付报告；**没有任何开关能关掉它**
/// （关掉就是 60 档「打包期放行、装机首启才暴露」的原虫，rust-playable/rust-sidecar-fuzz 的账）。
/// 要绕开这条腿只有 [check_with_meta_leg] 的注入位，那是闸用的替身，不是产品档位。
pub fn check(out_dir: &Path, corpus_root: &Path) -> Outcome<Report> {
    check_with_meta_leg(out_dir, corpus_root, &meta_leg_via_jvm)
}

/// [check] 的**深判据腿注入版**（闸用替身：恒放行 / 抛错 / 计数）。
///
/// 判据顺序与 Kotlin 完全一致（顺序决定 `summary()` 的行序）：
/// 1. 清单缺失 / 读不出来 / 无 `.erbc` 条目 ⇒ 直接返回（`:158-182`）；
/// 2. **编译器指纹**（先查：语料摘要对得上但编译器换了是最容易被放行的形态，`:184-185`）；
/// 3. 容器存在 / 字节数 / 摘要 / 段表 + 两个侧车（`:187-223`）；
/// 4. 清单里每个源文件的字节数与摘要（`:225-249`）；
/// 5. 语料新增（`:250-255`）。
pub fn check_with_meta_leg(
    out_dir: &Path,
    corpus_root: &Path,
    meta_leg: &MetaLeg,
) -> Outcome<Report> {
    let root = absolutize(out_dir);
    let corpus = absolutize(corpus_root);
    let meta_leg = MetaLegCtx { out_dir: &root, corpus_root: &corpus, leg: meta_leg };
    let artifact_dir = root.join(format!("erbc.v{ARTIFACT_DIR_VERSION}"));
    let manifest_path = artifact_dir.join(MANIFEST_NAME);
    let mut issues: Vec<Issue> = Vec::new();

    if !manifest_path.is_file() {
        issues.push(Issue::new(
            KIND_MANIFEST_MISSING,
            format!(
                "缺 {} —— 没有清单就无从判断产物属于哪一版语料",
                manifest_path.display()
            ),
        ));
        return Ok(report(root, issues, None));
    }
    let raw = match std::fs::read(&manifest_path) {
        Ok(b) => b,
        Err(e) => {
            issues.push(Issue::new(
                KIND_MANIFEST_UNREADABLE,
                format!(
                    "{} 读取失败: IOException: {e}",
                    manifest_path.display()
                ),
            ));
            return Ok(report(root, issues, None));
        }
    };
    // Kotlin 用 `String(bytes, Charsets.UTF_8)`（`:166`）—— **宽松**解码（坏字节换 U+FFFD，
    // 不抛异常），故本侧同样走 lossy，别误用严格解码把可解析的清单判成读不出来。
    let text = String::from_utf8_lossy(&raw).to_string();
    let manifest = parse_manifest(&text);
    let artifact_entry = match manifest.artifact.clone() {
        Some(e) => e,
        None => {
            issues.push(Issue::new(
                KIND_MANIFEST_UNREADABLE,
                format!(
                    "{} 没有可解析的 artifact 行（清单被截断或格式不符）",
                    manifest_path.display()
                ),
            ));
            return Ok(report(root, issues, None));
        }
    };

    // ---- 编译器指纹（先查）----
    let compiler = check_compiler(&manifest, &root, &mut issues);

    // ---- 1. 容器字节 + 段表 ----
    let artifact_path = root.join(&artifact_entry.rel_path);
    if !artifact_path.is_file() {
        issues.push(Issue::new(
            KIND_ARTIFACT_MISSING,
            format!("清单登记的 {} 不存在", artifact_entry.rel_path),
        ));
    } else {
        let size = std::fs::metadata(&artifact_path)
            .map_err(|e| ErbccError::Io(format!("读容器尺寸失败: {e}")))?
            .len();
        if size != artifact_entry.bytes {
            issues.push(Issue::new(
                KIND_ARTIFACT_SIZE,
                format!(
                    "{} 实际 {size} 字节，清单登记 {} 字节（写一半或被截断）",
                    artifact_entry.rel_path, artifact_entry.bytes
                ),
            ));
        } else {
            let sha = crate::digest::sha256_file(&artifact_path)?;
            if sha != artifact_entry.sha256 {
                issues.push(Issue::new(
                    KIND_ARTIFACT_SHA,
                    format!(
                        "{} 摘要 {sha}，清单登记 {}",
                        artifact_entry.rel_path, artifact_entry.sha256
                    ),
                ));
            }
        }
        // 头层单源（= Kotlin `MappedErbc.open`，`ArtifactFreshness.kt:207-215` 只走到这一层）。
        // **装载层段布局判据（`container::validate_section_layout`）不在这里调**：Kotlin 的
        // `erbcc check` 腿不做段布局（`checkSectionLayout` 只在装载链与 `estimateHeap` 上），
        // 加进来会让 `check` 腿比 Kotlin 更严（一侧收紧过头 + `CONTAINER_INVALID` 文案错位）。
        // 那条闸的 Rust 侧落点是**区间消费方** `incremental::container_regions`。
        match container::open(&artifact_path) {
            Err(message) => issues.push(Issue::new(
                KIND_CONTAINER_INVALID,
                format!("{} 段表不可信: {message}", artifact_entry.rel_path),
            )),
            Ok(container) => check_sidecars(&artifact_dir, &container, &artifact_entry, &meta_leg, &mut issues)?,
        }
    }

    // ---- 2. 源文件 ----
    let mut recorded: std::collections::HashSet<String> =
        std::collections::HashSet::with_capacity(manifest.sources.len());
    for s in &manifest.sources {
        recorded.insert(s.rel_path.clone());
        let path = corpus.join(&s.rel_path);
        if !path.is_file() {
            issues.push(Issue::new(
                KIND_SOURCE_MISSING,
                format!("清单登记的源文件已不存在: {}", s.rel_path),
            ));
            continue;
        }
        let disk_len = match std::fs::metadata(&path) {
            Ok(m) => m.len(),
            Err(e) => {
                issues.push(Issue::new(
                    KIND_SOURCE_CHANGED,
                    format!(
                        "源文件读不出来（无法确认与产物同版）: {} IOException",
                        s.rel_path
                    ),
                ));
                let _ = e;
                continue;
            }
        };
        let sha = crate::digest::sha256_file(&path)?;
        if disk_len != s.bytes || sha != s.sha256 {
            issues.push(Issue::new(
                KIND_SOURCE_CHANGED,
                format!(
                    "源文件已改动但产物未重编: {}（清单 {} 字节 / 磁盘 {disk_len} 字节）",
                    s.rel_path, s.bytes
                ),
            ));
        }
    }
    // ---- 3. 语料新增 ----
    for path in list_corpus_files(&corpus) {
        let rel = path
            .strip_prefix(&corpus)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_else(|_| path.to_string_lossy().replace('\\', "/"));
        if !recorded.contains(&rel) {
            issues.push(Issue::new(
                KIND_SOURCE_ADDED,
                format!("语料新增文件未编进产物: {rel}"),
            ));
        }
    }

    Ok(report(
        root,
        issues,
        compiler.map(|c| c.digest),
    ))
}

fn report(out_dir: PathBuf, issues: Vec<Issue>, compiler_digest: Option<String>) -> Report {
    Report {
        ok: issues.is_empty(),
        issues,
        compiler_digest,
        out_dir,
    }
}
