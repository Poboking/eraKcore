use crate::types::{ErbccError, Outcome};
use std::path::Path;

/// 未绑定符号审计的 **Rust 侧写出层**：TSV 落盘、`--allow` 白名单解析、`--strict` 入闸判据。
///
/// 语义分工（不许越界）：「什么算未绑定」由 JVM kernel 判（`SymbolLinkAudit.kt` 走 core-vm 的
/// 运行期命名空间与 `SymbolUses` 使用点），Rust 只负责把这些读数变成确定性文件。
///
/// 权威口径 = Kotlin `Main.kt` 的 `cmdAudit`（缺省 out `tools/erbcc/symbol-audit/unresolved-symbols.tsv`、
/// `--game-root` 缺省 = corpus 父目录、缺 `CSV/` 目录直接报红「满屏假红」那条文案）+
/// `audit/SymbolLinkAudit.kt` 的 `FindingKind`（8 类）与 TSV 列序。
///
/// 本模块的行文里一律写「`specs` 目录下的 yaml」，不写星号斜杠那种通配（AGENTS.md §2.5）。

/// 一条审计读数（= `SymbolLinkAudit.kt:49-60` 的 `Finding`，也是 TSV 的一行）。
///
/// 字段与 TSV 列一一对应（列序 `kind file function line name detail count`，
/// `SymbolLinkAudit.kt:91`）：[Finding::function_key] 用 `Option` 表达「空串」这一列
/// （Kotlin 的 `functionKey` 是普通 String，`SymbolAuditReport.writeTsv` 对空串只会写空字段，
/// 见 `:95` + `:104` 的 `clean`），[Finding::line] 允许 `-1`（`SymbolLinkAudit.kt:258` 的
/// 私有 `#DIM` 拒绝项就用 -1 表示「定位不到行」）。
///
/// 行序**由输入决定**、本模块不重排：Kotlin 侧的排序在
/// `SymbolLinkAudit.kt:281`（`kind.ordinal, file, line, name, detail`）就已完成，
/// kernel 落盘的 TSV 即终序；Rust 侧再排一次只是把「同一个比较器」抄第二遍，
/// 抄错反而制造差异（`TODO(verify)`：抄来的 ordinal 序与 `compareBy` 的稳定性没对拍过）。
pub struct Finding {
    pub kind: String,
    pub name: String,
    pub function_key: Option<String>,
    pub detail: String,
    /// TSV 第 2 列（语料根下的相对路径，`CorpusCompile.kt:327` 的 `root.relativize(...)` +
    /// `\`→`/`，由 `:513` 的 `SymbolUseWalker.walk(s.rel, …)` 带进使用点键；
    /// 因此这一列与产出机的绝对路径无关，基准可入库）。
    pub file: String,
    /// TSV 第 4 列（0 基的语句行号；`-1` = 定位不到，`SymbolLinkAudit.kt:258`）。
    pub line: i64,
    /// TSV 第 7 列 = `Finding.occurrences`（同一使用点里的出现次数，`SymbolUses.kt:51-52`）。
    pub occurrences: u64,
}

/// TSV 表头，逐字照 `SymbolAuditReport.writeTsv` 的第一行（`SymbolLinkAudit.kt:91`）。
pub const TSV_HEADER: &str = "kind\tfile\tfunction\tline\tname\tdetail\tcount";

/// `FindingKind` 的 8 类与各自的 `blocking` 位（`SymbolLinkAudit.kt:29-46`）。
///
/// 这是「哪些 kind 算硬差异」的**唯一判据表**，逐条抄自 Kotlin 的枚举声明：
/// `VAR_UNBOUND`/`VAR_TYPE_MISMATCH`/`VAR_DIM_MISMATCH`/`KEYWORD_UNBOUND`/`KEYWORD_USER_VAR`/
/// `PRIVATE_DIM_REJECTED`/`GLOBAL_DIM_REJECTED` 都是 `true`，只有 `COMPILE_DIM_WARNING` 是
/// `false`（`:45`，原版 `ParserMediator.Warn(…, 2)` 同样只告警不中断）。
///
/// 「动态关键字下标不计」不在这张表里：下标不是字符串字面量的使用点在 JVM 侧就
/// 只进 `dynamicKeywordSites` 计数、**根本不生成 Finding**（`SymbolLinkAudit.kt:238-245`），
/// 所以 Rust 侧无需（也不许）再加一条「按关键字过滤」的自创口径。
pub const FINDING_KINDS: [(&str, bool); 8] = [
    ("VAR_UNBOUND", true),
    ("VAR_TYPE_MISMATCH", true),
    ("VAR_DIM_MISMATCH", true),
    ("KEYWORD_UNBOUND", true),
    ("KEYWORD_USER_VAR", true),
    ("PRIVATE_DIM_REJECTED", true),
    ("GLOBAL_DIM_REJECTED", true),
    ("COMPILE_DIM_WARNING", false),
];

/// kind 名 → `blocking` 位。读数的第 1 列不在表内 ⇒ 报红：TSV 是 kernel 产的，
/// 出现未知 kind 只可能是「Kotlin 加了新 kind 而这张表没跟着改」，
/// 静默当成非红会把闸放宽（契约 §0.2 禁止自创口径）。
pub fn kind_is_blocking(kind: &str) -> Outcome<bool> {
    for (name, blocking) in FINDING_KINDS {
        if name == kind {
            return Ok(blocking);
        }
    }
    Err(ErbccError::Io(format!(
        "未知审计 kind `{kind}`：不在 SymbolLinkAudit.kt:29-46 的 8 类里。\
         要么 Kotlin 侧新增了 FindingKind 而这张表没跟着补，要么读到的不是 erbcc audit 产的 TSV；\
         拒绝按非红处理。"
    )))
}

/// 硬差异读数（`SymbolAuditReport.blocking`，`SymbolLinkAudit.kt:84`：`kind.blocking` 为真的那些）。
pub fn blocking_findings<'a>(findings: &'a [Finding]) -> Outcome<Vec<&'a Finding>> {
    let mut out = Vec::new();
    for f in findings {
        if kind_is_blocking(&f.kind)? {
            out.push(f);
        }
    }
    Ok(out)
}

/// 一条读数是否被白名单放行（`Main.kt:615-617` 的 `AllowList.matches`）。
///
/// 两级匹配都是**整串相等**，不做大小写折叠、不做前缀匹配：
/// - `<kind>\t<name>`（2 列行）；
/// - `<kind>\t<functionKey>\t<name>`（3 列行，中间是读数自己的 functionKey，空就是空串）。
pub fn allow_matches(allow: &[(String, Option<String>, String)], f: &Finding) -> bool {
    let function_key = f.function_key.clone().unwrap_or_default();
    // 两个查表串就是 Kotlin 的 `byName` / `byFunction` 键形（`Main.kt:616-617` + `:636-637`）。
    let by_name = format!("{}\t{}", f.kind, f.name);
    let by_function = format!("{}\t{}\t{}", f.kind, function_key, f.name);
    allow.iter().any(|(kind, fn_key, name)| match fn_key {
        None => by_name == format!("{kind}\t{name}"),
        Some(fk) => by_function == format!("{kind}\t{fk}\t{name}"),
    })
}

/// `--strict` 判据：存在「既算硬差异、又没被白名单放行」的读数 ⇒ 真
/// （`Main.kt:502` 的 `blocking = report.blocking.filterNot { allowed.matches(it) }` +
/// `:522` 的 `if (strict && blocking.isNotEmpty()) return 1`）。
pub fn has_hard_diff(findings: &[Finding], allow: &[(String, Option<String>, String)]) -> Outcome<bool> {
    Ok(hard_diff_count(findings, allow)? > 0)
}

/// 同上，但返回**条数**（`Main.kt:507` 打的 `硬差异：${blocking.size} 处` 用的就是它，
/// 编排层拿它与 kernel 自己的读数交叉核对，不等即报红）。
pub fn hard_diff_count(findings: &[Finding], allow: &[(String, Option<String>, String)]) -> Outcome<usize> {
    let mut n = 0usize;
    for f in blocking_findings(findings)? {
        if !allow_matches(allow, f) {
            n += 1;
        }
    }
    Ok(n)
}

/// 确定性 TSV 文本（表头 + 每行七列、LF、结尾带换行，无时间戳）。
///
/// 逐字照 `SymbolAuditReport.writeTsv`（`SymbolLinkAudit.kt:89-104`）：
/// - 第 1 列 kind 名**不做** `clean`（枚举名里没有制表符，`:93`）；
/// - 其余文本列走 `clean`：制表符/换行/回车各换成一个空格（`:104`）；
/// - 数值列（line/count）按十进制原样写（`:96`/`:99`）。
pub fn tsv_text(findings: &[Finding]) -> Outcome<String> {
    let mut out = String::new();
    out.push_str(TSV_HEADER);
    out.push('\n');
    for f in findings {
        kind_is_blocking(&f.kind)?; // 未知 kind 不落盘：先验列再写，避免写出闸读不懂的文件
        out.push_str(&f.kind);
        out.push('\t');
        out.push_str(&clean(&f.file));
        out.push('\t');
        out.push_str(&clean(f.function_key.as_deref().unwrap_or("")));
        out.push('\t');
        out.push_str(&f.line.to_string());
        out.push('\t');
        out.push_str(&clean(&f.name));
        out.push('\t');
        out.push_str(&clean(&f.detail));
        out.push('\t');
        out.push_str(&f.occurrences.to_string());
        out.push('\n');
    }
    Ok(out)
}

/// TSV 文本 → 读数表（kernel 落的文件是 Rust 侧唯一的取读数面）。
///
/// 表头不对、列数不是 7、`line`/`count` 不是整数、kind 不在 8 类里，一律报红而不是
/// 「跳过这一行」：静默丢行会把硬差异丢到闸外。
pub fn parse_tsv(text: &str) -> Outcome<Vec<Finding>> {
    let mut lines = text.split('\n');
    let head = lines.next().unwrap_or("");
    if head != TSV_HEADER {
        return Err(ErbccError::Io(format!(
            "TSV 表头不是 erbcc audit 的七列（`SymbolLinkAudit.kt:91`），实为：`{head}`"
        )));
    }
    let mut out = Vec::new();
    for (idx, raw) in lines.enumerate() {
        if raw.is_empty() {
            continue; // 结尾换行产生的空尾行（writeTsv 每行都以 \n 收口）
        }
        let cols: Vec<&str> = raw.split('\t').collect();
        if cols.len() != 7 {
            return Err(ErbccError::Io(format!(
                "TSV 第 {} 行是 {} 列，应为 7 列（`SymbolLinkAudit.kt:91`）：`{raw}`",
                idx + 2,
                cols.len()
            )));
        }
        kind_is_blocking(cols[0])?;
        let line = cols[3].parse::<i64>().map_err(|e| {
            ErbccError::Io(format!(
                "TSV 第 {} 行的 line 列 `{}` 不是整数：{e}",
                idx + 2,
                cols[3]
            ))
        })?;
        let occurrences = cols[6].parse::<u64>().map_err(|e| {
            ErbccError::Io(format!(
                "TSV 第 {} 行的 count 列 `{}` 不是非负整数：{e}",
                idx + 2,
                cols[6]
            ))
        })?;
        out.push(Finding {
            kind: cols[0].to_string(),
            file: cols[1].to_string(),
            function_key: if cols[2].is_empty() {
                None
            } else {
                Some(cols[2].to_string())
            },
            line,
            name: cols[4].to_string(),
            detail: cols[5].to_string(),
            occurrences,
        });
    }
    Ok(out)
}

/// `--allow` 白名单（`Main.kt:650-663` 的 `readAllowListText` + `Main.kt:678-696` 的 `readAllowList`）。
///
/// 逐条口径：
/// - 文件不是普通文件 ⇒ **不报错、不占退出码**，返回空白名单（`Main.kt:651` 的
///   `if (!path.isRegularFile()) return null` → `Main.kt:679-681` 打一行
///   `白名单文件不存在（按空白名单处理）: <路径>` 后 `AllowList.EMPTY`，进程退出码仍由**审计结果**定：
///   实测两腿同档同 rc —— Kotlin 腿 `build/cli-polish/a3k.err` rc=1 来自 `--strict` 下 8 处硬差异，
///   不是「缺白名单」这件事本身。那一行的文本单源在 [allow_missing_warning_text]（**原文形态**），
///   但由 `cmd_audit::stderr_head_lines`（携 `--allow` 原文）把 kernel 透出的那一份**就地换成原文形态**（保持行位置，也不会上屏两遍）。
/// - 按 `\n` 与 `\r` 两个字符切行（`Main.kt:685` 的 `split('\n', '\r')`，CRLF 会切出空行，
///   下一步 trim 后丢掉）；
/// - 整行 trim 后为空或以 `#` 开头 ⇒ 注释/空行跳过（`Main.kt:687`）；
/// - 以制表符切列：**2 列** = `kind<TAB>name`，**3 列** = `kind<TAB>functionKey<TAB>name`，
///   其它列数的行跳过（`Main.kt:689-693`，跳过的告警同上由 kernel 打）；
/// - 列内不做 trim（Kotlin 只 trim 整行），所以列首/列尾的空格是匹配串的一部分。
///
/// 返回的元组第 2 位用 `None` 表示 2 列行、`Some(fn)` 表示 3 列行 —— 与
/// [allow_matches] 的两级匹配一一对应。
///
/// ## 退出码口径（②，rust-cli-polish）
///
/// 非法 UTF-8 走 [ErbccError::Usage]（⇒ `cli::finish` 退 **2**），正文用
/// [crate::cmd_audit::allow_charset_red]：Kotlin 侧是 `readAllowListText` 抛
/// `IllegalStateException`（`Main.kt:660-661`）→ `cmdAudit` 转 `err()`（`Main.kt:487-491`，
/// `Main.kt:163-166` 恒退 2），**不是** 1。本函数原先返回 `Io`（退 1），与编排层
/// [crate::cmd_audit::preflight_allowlist] 的 `Usage`（退 2）对同一个条件给两个档 ——
/// 修向是把 Rust 对齐 Kotlin（取 2、取同一段报红正文），Kotlin 判据一字不动。
/// 生产链上 [crate::cmd_audit::preflight_allowlist] 在起 kernel 之前先拦，所以这枚是第二道闸；
/// 两道闸现在同档同文，无论谁先命中，使用者看到的退出码与 Kotlin 都是一格。
pub fn load_allowlist(path: &Path) -> Outcome<Vec<(String, Option<String>, String)>> {
    let text = match std::fs::read(path) {
        Err(e) => {
            // Main.kt:651 + Main.kt:679-681：缺文件（或不是普通文件）按空白名单处理，不占退出码。
            let _ = e;
            return Ok(Vec::new());
        }
        Ok(bytes) => decode_utf8_strict(&bytes, path)?,
    };
    let mut out = Vec::new();
    for raw in text.split(['\n', '\r']) {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let cols: Vec<&str> = line.split('\t').collect();
        match cols.len() {
            2 => out.push((cols[0].to_string(), None, cols[1].to_string())),
            3 => out.push((cols[0].to_string(), Some(cols[1].to_string()), cols[2].to_string())),
            _ => {}
        }
    }
    Ok(out)
}

/// `--allow` 缺档提示行的**正文单源**（`Main.kt:680` 的 `System.err.println`）。
///
/// Kotlin 打在 `Path(原文)` 上 ⇒ 相对原样 + 分隔符归一（实测读数：
/// `白名单文件不存在（按空白名单处理）: tools\erbcc-rs\tests\data\rust-cli-surface\nope-missing.tsv`，
/// 见 `tools/erbcc-rs/build/cli-polish/a3k.err`）。Rust 编排层把**绝对化**后的路径透给 kernel，
/// kernel 那行因此是绝对路径（修前读数 `build/cli-polish/a3r.err`）⇒ 两腿这一格曾不同形。
/// 归一方式与 F1 同一条：[crate::types::kotlin_path_text] 只折分隔符、**不绝对化**。
///
/// 接线已落地（组长，rust-cli-polish 收口）：`cmd_audit::orchestrate` 在缺档时用**原文**调本函数自己打这一行
/// （嗂给 kernel 的仍是绝对路径 —— 子进程 CWD 固定在仓库根，传相对会读错档），kernel 透出的那一份
/// 由 [crate::cmd_audit::stderr_head_lines]（携 `--allow` 原文）用 [is_allow_missing_warning_line] 认出后**就地换成原文形态**。
/// `tests/cli_surface_diff.rs` 的对应格已从「钉在册」翻成全文 parity（含「改回喷 `allow_abs` 就红」的反向证明）。
pub fn allow_missing_warning_text(path_raw: &str) -> String {
    format!(
        "白名单文件不存在（按空白名单处理）: {}",
        crate::types::kotlin_path_text(path_raw)
    )
}

/// [allow_missing_warning_text] 那行的**前缀判据**（用来滤掉 kernel 透出的那一份，口径同
/// `cmd_audit::is_duplicate_limit_line`：只按行首前缀认，不改判据本身）。
pub fn is_allow_missing_warning_line(line: &str) -> bool {
    line.starts_with(ALLOW_MISSING_PREFIX)
}

/// `Main.kt:680` 那行的固定前缀（不含路径段）。
const ALLOW_MISSING_PREFIX: &str = "白名单文件不存在（按空白名单处理）: ";

/// Kotlin `readAllowListText`（`Main.kt:650-663`）的**严格 UTF-8** 解码：解不动就按**参数错误**报红，
/// 不按替换字符糊过去 —— 白名单糊一条就等于少放行一条，Kotlin 侧同样是 `CodingErrorAction.REPORT`
/// 那一档（坏字节直接抛，不做有损解码；这里也不做 BOM 剥离、不做 Shift-JIS 回退，
/// 与 `Main.kt:658-659` 的注释口径一字一样：合法输入必须逐字同解）。
fn decode_utf8_strict(bytes: &[u8], path: &Path) -> Outcome<String> {
    std::str::from_utf8(bytes)
        .map(|s| s.to_string())
        .map_err(|_| {
            // 与 cmd_audit::preflight_allowlist 同档（Usage ⇒ 退 2）同文（同一枚 red 单源）。
            ErbccError::Usage(crate::cmd_audit::allow_charset_red(&crate::types::kotlin_path_text(
                &path.to_string_lossy(),
            )))
        })
}

/// `SymbolAuditReport.clean`（`SymbolLinkAudit.kt:104`）：制表符/换行/回车 → 空格。
fn clean(s: &str) -> String {
    s.replace('\t', " ").replace('\n', " ").replace('\r', " ")
}

// ---------------------------------------------------------------------------
// audit --roundtrip（CMP-79）：对账明细 TSV 的读写与判据复算
//
// 判据本身**一条都不在这里**：侧车逐字段 / 三读数 / 名字集合哈希 / 容器条数全由
// `SymbolLinkAudit.compareProduct`（`SymbolLinkAudit.kt:564-627`）判，Rust 侧只把
// `RoundTripReport.writeTsv`（`:148-159`）落的文件读回来复算字节、数DIFF 行、
// 给退出码（`Main.kt:566-574`）。
// ---------------------------------------------------------------------------

/// 一条对账读数（= `RoundTripCheck`，`SymbolLinkAudit.kt:114-125`；也是明细 TSV 的一行）。
///
/// [RoundTripCheck::detail] 在**绿档也可能非空**：`meta.program-sha256` 那条把配对说明
/// 无条件带上（`SymbolLinkAudit.kt:712-718`，detail 不在 `equal` 的三元里），
/// 所以 Rust 侧不许把「detail 非空」当红判据 —— 红只认 [RoundTripCheck::equal]
/// （Kotlin 的 `mismatches = checks.filterNot { it.equal }`，`:143`）。
#[derive(Debug)]
pub struct RoundTripCheck {
    /// TSV 第 2 列 `check`（判据名，如 `meta.private-dims`）。
    pub name: String,
    /// TSV 第 3 列 `in_memory`（内存侧可读读数，`RoundTripCheck.inMemory`）。
    pub in_memory: String,
    /// TSV 第 4 列 `reloaded`（真装载链的读数）。
    pub reloaded: String,
    /// TSV 第 1 列：`OK` → true、`DIFF` → false（`writeTsv` 的 `:152`）。
    pub equal: bool,
    /// TSV 第 5 列（首差位置/差集样例；绿档可为空，见类型注）。
    pub detail: String,
}

/// 明细 TSV 表头，逐字照 `RoundTripReport.writeTsv` 的第一行（`SymbolLinkAudit.kt:150`）。
pub const RT_TSV_HEADER: &str = "status\tcheck\tin_memory\treloaded\tdetail";

/// 确定性明细 TSV 文本（表头 + 每行五列、LF、每行以换行收口，无时间戳）。
///
/// 逐字照 `SymbolLinkAudit.kt:150-157`：第 1 列写 `OK`/`DIFF`（`:152`），
/// 其余四列全部走 `clean`（`:153-156`，与 [tsv_text] 的 `:104` 同一把尺）。
pub fn roundtrip_tsv_text(checks: &[RoundTripCheck]) -> Outcome<String> {
    let mut out = String::new();
    out.push_str(RT_TSV_HEADER);
    out.push('\n');
    for c in checks {
        out.push_str(status_label(c.equal)); // :152 的三元，只产这两个字面量
        out.push('\t');
        out.push_str(&clean(&c.name));
        out.push('\t');
        out.push_str(&clean(&c.in_memory));
        out.push('\t');
        out.push_str(&clean(&c.reloaded));
        out.push('\t');
        out.push_str(&clean(&c.detail));
        out.push('\n');
    }
    Ok(out)
}

/// TSV 文本 → 对账读数表（kernel 落的明细是 Rust 侧唯一的取读数面）。
///
/// 表头不对、列数不是 5、状态列不是 `OK`/`DIFF` 一律报红而不是跳过：
/// 丢一行等于丢一条判据，而 `Main.kt:566` 的红判据是「任一项不等」。
pub fn parse_roundtrip_tsv(text: &str) -> Outcome<Vec<RoundTripCheck>> {
    let mut lines = text.split('\n');
    let head = lines.next().unwrap_or("");
    if head != RT_TSV_HEADER {
        return Err(ErbccError::Io(format!(
            "对账明细 TSV 表头不是 erbcc audit --roundtrip 的五列（`SymbolLinkAudit.kt:150`），实为：`{head}`"
        )));
    }
    let mut out = Vec::new();
    for (idx, raw) in lines.enumerate() {
        if raw.is_empty() {
            continue; // 结尾换行产生的空尾行（writeTsv 每行都以 \n 收口）
        }
        let cols: Vec<&str> = raw.split('\t').collect();
        if cols.len() != 5 {
            return Err(ErbccError::Io(format!(
                "对账明细 TSV 第 {} 行是 {} 列，应为 5 列（`SymbolLinkAudit.kt:150`）：`{raw}`",
                idx + 2,
                cols.len()
            )));
        }
        out.push(RoundTripCheck {
            equal: parse_status(cols[0]).map_err(|m| {
                ErbccError::Io(format!("对账明细 TSV 第 {} 行：{m}", idx + 2))
            })?,
            name: cols[1].to_string(),
            in_memory: cols[2].to_string(),
            reloaded: cols[3].to_string(),
            detail: cols[4].to_string(),
        });
    }
    Ok(out)
}

/// 不等项（`RoundTripReport.mismatches`，`SymbolLinkAudit.kt:143`：**保持 checks 原序**，
/// 排序权在 JVM 侧，Rust 不重排 —— 口径同 [Finding] 的行序说明）。
pub fn roundtrip_mismatches(checks: &[RoundTripCheck]) -> Vec<&RoundTripCheck> {
    checks.iter().filter(|c| !c.equal).collect()
}

/// 判据条数（`Main.kt:557` 的 `对拍 ${report.checks.size} 项` / `:565` 的明细行数）。
pub fn roundtrip_check_count(checks: &[RoundTripCheck]) -> usize {
    checks.len()
}

/// `Main.kt:566` 的红判据：**任一**项不等即红（对账本身是闸，不另设 `--strict`）。
pub fn roundtrip_is_red(checks: &[RoundTripCheck]) -> bool {
    !roundtrip_mismatches(checks).is_empty()
}

/// 不等项的判据名，按 checks 序（`Main.kt:568` 的 `mismatches.joinToString("、") { it.name }`
/// 就是这个序 —— 编排层拿它把 kernel 的红行与本路复算交叉核对）。
pub fn roundtrip_mismatch_names(checks: &[RoundTripCheck]) -> Vec<String> {
    roundtrip_mismatches(checks)
        .into_iter()
        .map(|c| c.name.clone())
        .collect()
}

fn status_label(equal: bool) -> &'static str {
    if equal {
        "OK"
    } else {
        "DIFF"
    }
}

/// 第 1 列 → `equal`。只认 `OK`/`DIFF`（`SymbolLinkAudit.kt:152` 的三元只产这两个字面量）；
/// 出现别的取值说明读到的不是本命令产的明细，报红、不按「相等」处理。
fn parse_status(s: &str) -> Result<bool, String> {
    match s {
        "OK" => Ok(true),
        "DIFF" => Ok(false),
        other => Err(format!(
            "状态列 `{other}` 不是 OK/DIFF（`SymbolLinkAudit.kt:152`）；拒绝按绿处理"
        )),
    }
}
