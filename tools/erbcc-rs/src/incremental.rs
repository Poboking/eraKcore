use std::collections::BTreeMap;
use std::io::Read;
use std::path::Path;

use crate::manifest;
use crate::types::{ErbccError, Outcome, ERBC_DIR_NAME};

/// 「erbcc 能不能**增量编译**」的可行性判据面（RUST1-B 收尾路）。
///
/// 为什么这一路先要回答的是「能不能」而不是「怎么快」：`:tools:erbcc:compileCorpus` 的产物是
/// **单一 `Program`**（`specs/erbc-format.md` 段 0..8：全局串池、常量池、`defIndex` 序、
/// `globalSlots`、入口函数下标），而 `CorpusCompile` 的编译输入是**全量目录扫描**
/// （`Main.kt:42-47`，与 `lazyloadingfiles.dat` 解耦）。也就是说「一个文件改了」会牵动
/// 池下标与函数序，而池下标是**产物字节的一部分**。
///
/// 因此本模块的出口不是「增量实现了」，而是下面这条判据能不能成立：
/// **存在一组文件，改动其中任意一个后，未改动文件对应的产物区间逐字节不变。**
/// 能成立 ⇒ 给出可执行判据与实测；不能成立 ⇒ 给出排除表（哪些语义耦合把它锁死），
/// 并说明增量只能停在「跳过未变更文件的**解析**」这一层（而不是跳过产物重算）。
///
/// ## 实测结论（RUST1 rust-incremental 车道，2026-11-01，探针读数见
/// ## `tests/data/rust-incremental/probe-readings.txt` + `tests/incremental_feasibility.rs`）
///
/// **判据不成立**。探针语料有三档（装载序 `inc_a.ERB` < `inc_b.ERB` < `inc_c.ERB`），
/// 观测对象是**从不编辑**的 `inc_b.ERB` 的产物区间。改 `inc_a.ERB` 之后：
///
/// * 新增一条字符串字面量（探针 p2_stradd）⇒ B 的 `_SET_STR` 的 `b`（全局串池下标）1→2，
///   **B 自己的指令字节变了**（不只是位置平移）。
/// * 新增一条语句（探针 p2_stmtadd）⇒ B 的每条 marker 的 `a`（语句池下标）+1，同上。
/// * 新增/删除一个 `#FUNCTION`（探针 p3_funcadd / p3_funcdel）⇒ B 的 `_CALL` 的 `a`
///   （函数表下标）与串池/常量池下标一起位移，同上。
/// * 新增一个文件（探针 p6_fileadd）⇒ 它后面**所有**文件的指令内容都变，同上。
/// * 只改 `inc_c.ERB`（装载序末档，探针 p5_lastfile）、或删掉末档（p7_filedel）⇒
///   A/B 的指令**内容**保住，但**绝对字节区间**仍平移（前面的段长变了 ⇒ 段表与所有段起点重排）。
/// * 改 ERH 的 `#DIM CONST`（探针 p4_erhconst）⇒ `program.erbc` 整文件逐字节相同，
///   但 `program.meta` 里 B 的函数条目 `function … 3` → `function … 4`（跨文件求值把
///   后档的尺寸读数锁死）。⇒ 连「容器不动」的那一类改动也锁着**产物集**的另一件。
/// * 只改注释/空白（探针 p1_comment）⇒ **整件 `program.erbc` 逐字节相同**（含段表）。
///
/// 最后一条是唯一「产物区间不变」的类，但「这处改动是不是纯注释」只能靠**解析**判定 ——
/// 而解析正是增量想省掉的那一步，所以它不构成产物层增量的判据，只构成**解析层**增量的收益上界
/// （见下面「解析层账面」）。
///
/// ## 为什么位置相关量必然进产物
///
/// 1. 语句池按身份追加、严禁去重，`marker` 指令的 `a` 就是池下标
///    （`Program.kt:511-516` 的 `addStatement` + `ErbProgramCompiler.kt:512-521` 的
///    `emitStatementMarker`）⇒ 前档加/删一条语句，后档所有 marker 的 `a` 全体位移。
/// 2. 函数发射序 = `defIndex` 序（`CorpusCompile.kt:503` 的 `units.sortBy` 一行），
///    `defIndex` = 装载序里的定义序号（`Symbols.kt:461-492`），装载序由 ja-JP Collator 排文件名
///    （`Symbols.kt:427-451`，ADR-006）⇒ 前档加/删一个 `#FUNCTION`，后档函数下标与 `_CALL` 的 `a` 位移。
/// 3. 全局串池/常量池按下标驻留、下标是指令操作数（`specs/erbc-format.md` §5.1/§5.2 +
///    `ErbProgramCompiler.kt:813-814` 的 `_SET_INT`/`_SET_STR`）⇒ 前档新增一个字面量，
///    后档引用它的操作数 +1。
/// 4. 段表是**容器头的一部分**（`specs/erbc-format.md` §2：偏移 10 起 `u32[sectionCount]`），
///    任何段变长都会让后面所有段的绝对起点重排 ⇒ 即使某个函数的指令内容一字未改，
///    它在文件里的字节区间也变了。
/// 5. `#DIM` 尺寸/`#LOCALSSIZE` 吃跨文件 `#DIM CONST` 求值（`Symbols.kt:469-473` 的
///    `erhDimConsts` + `CorpusCompile.kt:544` 的 `sizeConsts`）⇒ 前档 ERH 改一个常量，
///    后档函数的尺寸读数变（实测落在 `program.meta`，见探针 p4_erhconst）。
///
/// ## 解析层账面（产物层被判不成立之后唯一出路）
///
/// `compile` 全量在本机实测：墙钟 28,933 ms（内部读数 23,032 ms）、3,971 文件 / 110,066 切片。
/// 同一条腿带 `--limit 1`（只切片第一个文件，其余函数走空体桩）实测墙钟 9,528 ms（内部 8,054 ms）
/// —— 这 ≈8 s 是**符号表全量构建 + 预扫描全量 + 全量发射编排**的固定成本，与「改了哪些文件」无关。
/// 读数口径注意：`--limit 1` 只把**切片**限制到 1 档，符号表里的 110,066 个定义仍逐个走空体桩发射
/// （stderr 读数「切片 4 成功 110066」），所以这 8 s 是「不可跳过段」的**上界**而不是下界 ——
/// 真正的增量方案不会重发空桩，但那 8 s 里的符号表 + 预扫描两趟是全量目录扫描，跑不掉。
/// ⇒ 「跳过未变更文件的解析」的收益上界 ≈ 23.0 − 8.1 ≈ **15 s/趟**，且要连那 8 s 一起缓存才谈得上
/// 数量级收益。
///
/// 跳过解析的**前置条件**（缺一即必须重解该文件，逐条来自上面 1..5 的耦合面）：
/// 1. 该文件自身 sha256 未变（台账判据，见 [diff_against_ledger]）；
/// 2. ERH 全集（宏表 `#DEFINE` + `#DIM CONST` 原文）未变 —— 它们折进每个文件的表达式与 `#DIM` 尺寸；
/// 3. `emuera.config` 与 `renameDic` 未变 —— 词法开关进 `LexerConfig`（`CorpusCompile.kt:309-321`）；
/// 4. 全量符号表（`LabelTable`：装载序 + 每档的 `defIndex` + 重名胜者）未变；
/// 5. 预扫描读数（`CorpusScan`：函数返回类型、私有变量作用域、宏名集）未变。
/// 其中 2..5 任一变了，**所有**文件的解析结果都不可复用 —— 而这五项都是「全量目录扫一遍」的产物，
/// 所以缓存它们的成本本身就 ≈ 那 8 s 固定段。
///
/// 为什么它不会把「改源未重编」这类真红漏掉：跳过只发生在**前置条件全绿**时，
/// 台账判据（[diff_against_ledger] 的 `modified`/`added`/`removed` 非空）与既有
/// `freshness::check` 的 `SOURCE_CHANGED`/`SOURCE_ADDED`/`SOURCE_MISSING` 同一条口径
/// （`ArtifactFreshness.kt:225-255`）；闸（`tests/incremental_feasibility.rs` 的
/// `台账与新鲜度对同一改动同结论` 用例）实测两者对同一份改动给出一致结论，`SOURCE_CHANGED` 仍判红。
///
/// 全量档实测（同一条 Rust 编排腿，`cargo test -- --ignored` 可复现）：
/// * [diff_against_ledger] 在 3,971 文件 / ≈170 MB 语料上的对账耗时 **3,646 ms**，
///   与自身台账自比 = 0 改 / 0 增 / 0 删（零假阳）；
/// * 拿另一版游戏的台账（4,029 条）对同一份语料 ⇒ 0 改 / 0 增 / **58 删** —— 三类变更各自归位。
/// * ⇒ 变更集本身极便宜，贵的是它后面那 23 s；**产物层不可增量** 才是这一路的结论。
///
/// 已有底座复用，不重造：[manifest::source_entries]（文件集与 `source` 行的唯一口径）、
/// [crate::digest::sha256_file]、[crate::freshness::check]。

/// 一次「台账 vs 磁盘」的变更集。相对路径一律 `/` 分隔（与清单 `source` 行同口径）。
pub struct ChangePlan {
    /// 台账里有、磁盘上摘要不同的文件（相对路径，`/` 分隔）
    pub modified: Vec<String>,
    pub added: Vec<String>,
    pub removed: Vec<String>,
}

impl ChangePlan {
    /// 空变更集 = 语料与台账逐字一致（没有任何文件需要重解析，也没有任何池基数会变）。
    pub fn is_empty(&self) -> bool {
        self.modified.is_empty() && self.added.is_empty() && self.removed.is_empty()
    }

    /// 变更集规模（诊断行用）。
    pub fn total(&self) -> usize {
        self.modified.len() + self.added.len() + self.removed.len()
    }
}

/// 一条把产物字节锁死的语义耦合（[EXCLUSIONS] 的元素）。
pub struct Coupling {
    /// 耦合名（人读的短标签）。
    pub name: &'static str,
    /// 机制：为什么它会动到**未改动文件**的产物区间。
    pub mechanism: &'static str,
    /// 语义依据（`文件:行号`，逐条可查）。
    pub evidence: &'static str,
    /// 证伪它的探针标签（`tests/data/rust-incremental/variants/` 下的目录名）。
    pub probe: &'static str,
    /// 实测读数（期望 vs 实际，全部来自真编译产物，不是推演）。
    pub reading: &'static str,
}

/// 排除表：判「不成立」的逐条依据。每条都有探针实测读数支撑。
pub const EXCLUSIONS: &[Coupling] = &[
    Coupling {
        name: "STATEMENT_POOL_INDEX",
        mechanism: "语句池按身份追加、严禁去重，marker 指令的 a 就是池下标；前档加/删一条语句，\
                    后档每条 marker 的 a 全体位移。",
        evidence: "core/code/Program.kt:511-516; core/compile/ErbProgramCompiler.kt:512-521",
        probe: "p2_stmtadd",
        reading: "IFB_ONE pc+13 (op=70,a=4)→(70,5)、pc+21 (70,5)→(70,6)、pc+22 (121,6)→(121,7)；\
                  IFB_TWO / IFC_ONE 同型位移；容器 2188→2293 字节。",
    },
    Coupling {
        name: "GLOBAL_STRING_POOL_INDEX",
        mechanism: "全局驻留串池按下标寻址且下标进指令操作数；前档新增一个字面量，后档引用它的\
                    操作数 +1（串是按编译序惰性入池的，前档的新增落在后档已有条目之前）。",
        evidence: "specs/erbc-format.md §5.1; core/compile/ErbProgramCompiler.kt:813-814",
        probe: "p2_stradd",
        reading: "IFB_ONE pc+16 (op=3,a=5,b=1)→(3,5,2)；串池 2→3 条、CODE 段 484→516 字节；\
                  IFB_ONE 的 code sha a430ba531a53c43d→1a0a70cd1f35ced8。",
    },
    Coupling {
        name: "GLOBAL_CONSTANT_POOL_INDEX",
        mechanism: "i64 常量池同按下标寻址；前档增删字面量让后档 `_SET_INT` 的 b 位移。",
        evidence: "specs/erbc-format.md §5.2; core/compile/ErbProgramCompiler.kt:813",
        probe: "p3_funcdel",
        reading: "删掉前档一个函数后常量池 4→2，IFB_ONE pc+14 (2,3,b=2)→(2,3,b=0)、\
                  pc+15 (2,4,3)→(2,4,1)、pc+16 (3,5,1)→(3,5,0)。",
    },
    Coupling {
        name: "FUNCTION_TABLE_ORDER",
        mechanism: "函数发射序 = defIndex 序 = 装载序（ja-JP Collator 排文件名），`_CALL` 的 a 是\
                    函数表下标；前档加/删一个 #FUNCTION 让后档下标位移，段 4/8 同步重排。",
        evidence: "tools/erbcc/.../CorpusCompile.kt:503; core/frontend/Symbols.kt:427-451, :461-492",
        probe: "p3_funcadd",
        reading: "IFB_ONE pc+20 (op=22,a=0)→(22,1)；IFB_ONE/IFB_TWO/IFC_ONE 的 marker a 各 +2；\
                  函数表 199→235 字节、PARAM_NAMES 72→76 字节。",
    },
    Coupling {
        name: "SECTION_TABLE_IS_HEADER_BYTES",
        mechanism: "段表在容器头里（偏移 10 起 u32 × sectionCount），任何段变长都会重排后面所有段的\
                    绝对起点 —— 指令内容一字未改的函数，它的字节区间也变了。",
        evidence: "specs/erbc-format.md §2; core/code/ErbcWriter.kt:231-248（段偏移由段长推出来，再逐条写进头）",
        probe: "p2_strswap",
        reading: "只换一个字面量的正文（串池仍 2 条）：CODE 段起点 1390→1392，\
                  IFB_ONE 的 code 绝对起点 1602→1604，五个函数的 code 内容 sha 全等但区间全变。",
    },
    Coupling {
        name: "CROSS_FILE_DIM_CONST_EVAL",
        mechanism: "ERH 的 #DIM CONST 求值折进其它文件的 #DIM/#LOCALSSIZE 尺寸读数；改前档一个常量，\
                    后档函数的尺寸条目变。",
        evidence: "core/frontend/Symbols.kt:469-473; tools/erbcc/.../CorpusCompile.kt:544",
        probe: "p4_erhconst",
        reading: "inc_k.ERH 的 KKC 3→4：program.erbc 逐字节相同（sha 716045fa…），\
                  program.meta 的 `function 2 3 \"IFB_ONE\" … 3 \"inc_b.ERB:2\"` → 末位 3→4，\
                  meta sha 12e6bf87…→f5da1bb7…（产物集另一件被锁死）。",
    },
    Coupling {
        name: "CORPUS_IS_FULL_SCAN",
        mechanism: "编译输入是全量目录扫描（与 lazyloadingfiles.dat 解耦），新增文件按装载序插进池序，\
                    它后面每个文件的指令内容都位移。",
        evidence: "tools/erbcc/.../Main.kt:42-47; tools/erbcc/.../CorpusCompile.kt:205-218",
        probe: "p6_fileadd",
        reading: "新增装载序最前的 inc_aa.ERB：IFB_ONE pc+13..22 的 marker a、常量 b、串池 b 全位移\
                  （(70,4)→(70,6)、(2,3,2)→(2,3,4)、(3,5,1)→(3,5,2)）；容器 2188→2621 字节。",
    },
];

/// 与既有清单台账比对，得出变更集。**只答「哪些文件变了」，不答「产物能不能不动」**。
///
/// 判据：
/// 1. 台账侧 = `manifest.txt` 里逐条 `source <rel> <bytes> <sha256>`（`ErbcArtifacts.kt:270-273` 写的行）；
///    行的切分从**右**往左（末两段是字节数与摘要，其余整段是相对路径），与
///    `ArtifactFreshness.kt:431-441` 同一口径 —— 语料目录名可以含空格，从左切会把路径切碎。
/// 2. 磁盘侧 = [manifest::source_entries]（唯一文件集口径 = `CorpusCompile.listCorpusFiles`）。
/// 3. `modified` = 两侧都有但字节数或摘要不等；`added` = 磁盘有、台账无；`removed` = 台账有、磁盘无。
///    三个表都按相对路径字典序（台账走 [BTreeMap]，磁盘侧本身已排序）。
/// 4. 清单读不出来 = `Io` 报红（不返回空变更集冒充「没有文件改动」—— 那是把真红放行的口径）。
/// 5. 解码走**宽松** UTF-8（`String::from_utf8_lossy`），与 `ArtifactFreshness.kt:166` 的
///    `String(bytes, Charsets.UTF_8)` 同口径：坏字节不遮盖「台账里确实写了这条」。
pub fn diff_against_ledger(manifest_path: &Path, corpus_root: &Path) -> Outcome<ChangePlan> {
    let raw = std::fs::read(manifest_path).map_err(|e| {
        ErbccError::Io(format!(
            "读不到清单 {manifest_path:?}: {e}；没有台账就无从谈变更集，拒绝返回空表"
        ))
    })?;
    let text = String::from_utf8_lossy(&raw).into_owned();
    let mut ledger: BTreeMap<String, (u64, String)> = BTreeMap::new();
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("source ") else {
            continue;
        };
        if let Some((rel, bytes, sha)) = parse_ledger_entry(rest) {
            ledger.insert(rel, (bytes, sha));
        }
    }
    let disk = manifest::source_entries(corpus_root)?;

    let mut modified: Vec<String> = Vec::new();
    let mut added: Vec<String> = Vec::new();
    let mut seen: std::collections::HashSet<&str> = std::collections::HashSet::new();
    for entry in &disk {
        seen.insert(entry.rel_path.as_str());
        match ledger.get(&entry.rel_path) {
            None => added.push(entry.rel_path.clone()),
            Some((bytes, sha)) => {
                if *bytes != entry.bytes || *sha != entry.sha256 {
                    modified.push(entry.rel_path.clone());
                }
            }
        }
    }
    let removed: Vec<String> = ledger
        .keys()
        .filter(|rel| !seen.contains(rel.as_str()))
        .cloned()
        .collect();
    Ok(ChangePlan {
        modified,
        added,
        removed,
    })
}

/// `source` / `artifact` 行的尾部切分（口径 = `ArtifactFreshness.kt:431-441` 的 `parseEntry`）。
/// 返回 `None` 表示这一行不成形（缺字节数或缺摘要），按「不认识的行忽略」处理，与读侧一致。
fn parse_ledger_entry(rest: &str) -> Option<(String, u64, String)> {
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
    Some((rel.to_string(), bytes, sha.to_string()))
}

/// 可行性判据：改动 `ChangePlan` 里的文件后，未改动部分的产物区间是否仍逐字节不变。
/// 返回 `Ok(true)` 才能谈增量；`Ok(false)` 或报红都要写进排除表。
///
/// 实测结论 = **不成立**，因此本函数的可执行判据只有一条非平凡分支：
/// * **空变更集** ⇒ `Ok(true)`：没有任何文件改动，池基数与段表都不会动，产物区间逐字节不变
///   （这就是「增量」能给出的全部：跳过 = 什么都不用做）。
/// * **非空变更集** ⇒ `Ok(false)`：见 [EXCLUSIONS] 七条耦合。要点是变更集这一层
///   **区分不了**「会位移池序的改动」和「纯注释/空白改动」——
///   实测只有后者能让产物逐字节不动（探针 p1_comment：容器 sha256 与基准全等），
///   而「是不是纯注释」必须先解析该文件才知道，解析正是要省掉的那一步。
///   所以不许用「注释类改动产物不变」反过来论证产物层可行：那是解析层收益，不是产物层判据。
///
/// 报红面（`Err`）：本函数不吃路径、只做判据，没有 I/O，当前实现不会 `Err`。
/// 签名里留下的 `Err` 通道给「需要现场复测」的后续车道（要先有 `ChangePlan` 之外的入参才能起编译，
/// 见交付报告「需组长接线」）。
pub fn probe_stability(plan: &ChangePlan) -> Outcome<bool> {
    Ok(plan.is_empty())
}

// ======================================================================
// 产物区间比对：段区间（容器头/段表口径）与「按函数的指令区间」
// ======================================================================

/// 段名表（下标 = 段号，`specs/erbc-format.md` §4）。
/// **单源在 `crate::container`**（RUST1 rust-container 收口：原先这里与 `freshness.rs`
/// 各有一份副本，现两处都指向同一张表）；本道再导出是给本模块的区间读数与
/// `tests/incremental_feasibility.rs` 用的，保持既有引用面不变。
pub use crate::container::SECTION_NAMES;

/// 一个段的字节区间（绝对偏移，`[start, end)`）。
pub struct SectionRegion {
    pub index: usize,
    pub name: &'static str,
    pub start: usize,
    pub end: usize,
    /// 该段字节的 SHA-256（小写十六进制）。
    pub sha256: String,
}

/// 容器区间快照：头 + 段表 + 每段字节区间。
pub struct ContainerRegions {
    pub path: String,
    pub size: usize,
    pub version: u16,
    pub flags: u16,
    pub section_offsets: Vec<u32>,
    /// 段表字节（含 magic）—— 任何段变长都会让它变，这是「区间」判据最敏感的一处。
    pub header_sha256: String,
    pub sections: Vec<SectionRegion>,
}

impl ContainerRegions {
    /// 按段号取区间（不存在 = None）。
    pub fn section(&self, index: usize) -> Option<&SectionRegion> {
        self.sections.iter().find(|s| s.index == index)
    }

    /// 两段区间是否逐字节相同（只看存在的段；存在性差异由 [Self::section] 返回 None 暴露）。
    pub fn section_equal(&self, other: &Self, index: usize) -> Option<bool> {
        match (self.section(index), other.section(index)) {
            (Some(a), Some(b)) => Some(a.sha256 == b.sha256),
            (None, None) => Some(true),
            _ => None,
        }
    }
}

/// 一个函数在 CODE 段里的指令区间快照。
pub struct FunctionRegion {
    pub name: String,
    pub entry_pc: i32,
    /// 该函数指令区间的右开界（= 下一个函数的 entryPc，最后一个 = instructionCount）。
    pub end_pc: i32,
    pub arity: i32,
    pub slot_count: i32,
    /// 指令区间在**文件里的绝对字节区间**（`[code_start, code_end)`）。
    pub code_start: usize,
    pub code_end: usize,
    /// 指令内容字节的 SHA-256（与绝对位置无关）。
    pub code_sha256: String,
    /// 从 entryPc 起的 4×u32 指令（op, a, b, c），供逐条读数对比。
    pub words: Vec<[u32; 4]>,
}

/// 读容器头 + 段表 + 每段字节区间。
///
/// 口径：`specs/erbc-format.md` §2（头/段表）**与 §2 末条明写的装载层再加一条**+ §3（缺段的代价），
/// 即 Kotlin 的 `ProgramLoader.validateSections` / `MappedErbc.checkSectionLayout` 那条闸。
/// 段的右开界取「下一存在段的偏移」，末段取文件长度（§1.5 允许尾部多余字节，但 erbcc 产的容器没有，
/// 取长度即可；合法容器上它与装载层的 `section_ends`「下一个更大的段起点」逐段相等）。
///
/// **两道闸的顺序**（RUST1 rust-container-layout 收口，静默降级的落点就在这里）：
/// 1. 头层 `crate::container::parse`（= `MappedErbc.open` 同层）——失败时包一层
///    「容器 {path:?} 的头/段表未通过单源校验：」，路径是诊断身份，头层文本仍在末尾逐字对齐 Kotlin；
/// 2. 装载层 `crate::container::validate_section_layout`（= `checkSectionLayout` 同层）——
///    偏移落进段表、两段同偏移、区间不足 4 字节、CODE/LINE_TABLE 声明与区间对账不过。
///    失败时**原样透出 Kotlin 形态的 `ErbcDecodeException: 偏移 N: …`**，不加路径前缀：
///    Kotlin 的两条装载入口对段布局也只给这一句话 ——
///    `tests/data/rust-fuzz-diff/kotlin-verdicts.tsv` 里畸形段布局那 7 档的 `kotlin_l2`（
///    `MappedErbc.checkSectionLayout`）与 `kotlin_l3`（`ProgramLoader.loadFile`）两列逐字相同、
///    都不带被检文件路径，「两侧同文案」要钉的就是这一句。
pub fn container_regions(program_erbc: &Path) -> Outcome<ContainerRegions> {
    let bytes = read_whole(program_erbc)?;
    let header = crate::container::parse(&bytes).map_err(|e| {
        ErbccError::Io(format!(
            "容器 {program_erbc:?} 的头/段表未通过单源校验：{}",
            crate::container::message_of(&e)
        ))
    })?;
    crate::container::validate_section_layout_of_bytes(&header, &bytes)?;
    let section_count = header.section_count as usize;
    let section_offsets: Vec<u32> = header.offsets.iter().map(|o| o.unwrap_or(0)).collect();
    let head_end = crate::container::first_section_offset(section_count);
    let mut sections = Vec::with_capacity(section_count);
    for index in 0..section_count {
        if !header.is_present(index) {
            continue;
        }
        let (start, end) = header.section_range(index, bytes.len())?;
        sections.push(SectionRegion {
            index,
            name: crate::container::section_name(index),
            start,
            end,
            sha256: crate::types::sha256_hex(&bytes[start..end]),
        });
    }
    Ok(ContainerRegions {
        path: program_erbc.to_string_lossy().into_owned(),
        size: bytes.len(),
        version: header.version,
        flags: header.flags,
        section_offsets,
        header_sha256: crate::types::sha256_hex(&bytes[0..head_end]),
        sections,
    })
}

/// 读「段 4 函数表 + 段 5 指令流」，给出每个函数的指令区间与内容摘要。
///
/// 布局口径：`specs/erbc-format.md` §5.5（函数表：funcCount / entryFunction / globalSlots /
/// 段内 STRING_TABLE / 逐条记录）与 §5.6（指令流：instructionCount + 定长 4×u32）。
/// 区间右开界按 entryPc 升序取下一个函数的 entryPc（§5.5 的「记录序 = functions 列表原序」，
/// 发射序即 entryPc 升序）。
pub fn function_regions(program_erbc: &Path) -> Outcome<Vec<FunctionRegion>> {
    let bytes = read_whole(program_erbc)?;
    let regions = container_regions(program_erbc)?;
    let table = regions
        .section(4)
        .ok_or_else(|| ErbccError::Io("容器缺段 4（函数表），无法定位函数区间".to_string()))?;
    let code = regions
        .section(5)
        .ok_or_else(|| ErbccError::Io("容器缺段 5（指令流），无法取指令字节".to_string()))?;
    let mut cur = Cursor::new(&bytes, table.start);
    let func_count = cur.u32()? as usize;
    let _entry_function = cur.u32()?;
    let _global_slots = cur.u32()?;
    let names = cur.string_table()?;
    let mut raw: Vec<(String, i32, i32, i32)> = Vec::with_capacity(func_count);
    for _ in 0..func_count {
        let name_index = cur.u32()? as usize;
        let entry_pc = cur.i32()?;
        let arity = cur.i32()?;
        let slot_count = cur.i32()?;
        let _flags = cur.u32()?;
        let label_count = cur.u32()?;
        for _ in 0..label_count {
            let _label_name = cur.u32()?;
            let _label_pc = cur.i32()?;
        }
        let name = names.get(name_index).cloned().ok_or_else(|| {
            ErbccError::Io(format!(
                "段 4 第 {} 条记录的函数名下标 {name_index} 越出段内串池（{} 项）",
                raw.len(),
                names.len()
            ))
        })?;
        raw.push((name, entry_pc, arity, slot_count));
    }
    let mut cur = Cursor::new(&bytes, code.start);
    let instruction_count = cur.u32()? as usize;
    let code_body_start = cur.pos;
    let need = instruction_count * 16;
    if code_body_start + need > bytes.len() {
        return Err(ErbccError::Io(format!(
            "段 5 声明 {instruction_count} 条指令，剩余字节 {} 不够（每 4×u32）",
            bytes.len() - code_body_start
        )));
    }
    let mut by_pc = raw.clone();
    by_pc.sort_by_key(|(_, entry, _, _)| *entry);
    let mut out = Vec::with_capacity(by_pc.len());
    for (i, (name, entry, arity, slots)) in by_pc.iter().enumerate() {
        let end = if i + 1 < by_pc.len() {
            by_pc[i + 1].1
        } else {
            instruction_count as i32
        };
        let start_byte = code_body_start + (*entry.max(&0) as usize) * 16;
        let end_byte = code_body_start + (end.max(0) as usize) * 16;
        let slice = &bytes[start_byte..end_byte];
        let mut words = Vec::with_capacity(slice.len() / 16);
        for k in (0..slice.len()).step_by(16) {
            words.push([
                u32::from_le_bytes([slice[k], slice[k + 1], slice[k + 2], slice[k + 3]]),
                u32::from_le_bytes([slice[k + 4], slice[k + 5], slice[k + 6], slice[k + 7]]),
                u32::from_le_bytes([slice[k + 8], slice[k + 9], slice[k + 10], slice[k + 11]]),
                u32::from_le_bytes([slice[k + 12], slice[k + 13], slice[k + 14], slice[k + 15]]),
            ]);
        }
        out.push(FunctionRegion {
            name: name.clone(),
            entry_pc: *entry,
            end_pc: end,
            arity: *arity,
            slot_count: *slots,
            code_start: start_byte,
            code_end: end_byte,
            code_sha256: crate::types::sha256_hex(slice),
            words,
        });
    }
    Ok(out)
}

/// 取某个函数区间的「区间级」比对读数：内容是否等 + 绝对字节区间是否等。
/// 判据「未改动文件对应的产物区间逐字节不变」要**两个都等**才算成立。
pub struct RegionCompare {
    pub name: String,
    pub content_equal: bool,
    pub location_equal: bool,
    pub base_range: (usize, usize),
    pub variant_range: (usize, usize),
    pub base_sha: String,
    pub variant_sha: String,
}

/// 按函数名配对比较两份区间快照。台账里不存在该函数 ⇒ 不产出该条（新增/删除由调用方报）。
pub fn compare_function_regions(
    base: &[FunctionRegion],
    variant: &[FunctionRegion],
) -> Vec<RegionCompare> {
    let mut out = Vec::new();
    for a in base {
        let Some(b) = variant.iter().find(|v| v.name == a.name) else {
            continue;
        };
        out.push(RegionCompare {
            name: a.name.clone(),
            content_equal: a.code_sha256 == b.code_sha256,
            location_equal: a.code_start == b.code_start && a.code_end == b.code_end,
            base_range: (a.code_start, a.code_end),
            variant_range: (b.code_start, b.code_end),
            base_sha: a.code_sha256.clone(),
            variant_sha: b.code_sha256.clone(),
        })
    }
    out
}

fn read_whole(path: &Path) -> Outcome<Vec<u8>> {
    let mut f = std::fs::File::open(path)
        .map_err(|e| ErbccError::Io(format!("打开容器失败 {path:?}: {e}")))?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)
        .map_err(|e| ErbccError::Io(format!("读容器失败 {path:?}: {e}")))?;
    Ok(buf)
}

/// 只读游标（小端；越界即报红，不猜 0）。
struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8], pos: usize) -> Cursor<'a> {
        Cursor { bytes, pos }
    }

    fn take(&mut self, n: usize, what: &str) -> Outcome<&'a [u8]> {
        if self.pos + n > self.bytes.len() {
            return Err(ErbccError::Io(format!(
                "容器解析越界：{what} 需要 {n} 字节，偏移 {} 处只剩 {}",
                self.pos,
                self.bytes.len() - self.pos
            )));
        }
        let at = self.pos;
        self.pos += n;
        Ok(&self.bytes[at..at + n])
    }

    fn u32(&mut self) -> Outcome<u32> {
        let b = self.take(4, "u32")?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn i32(&mut self) -> Outcome<i32> {
        let b = self.take(4, "i32")?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// §5.1 的 STRING_TABLE：`u32 count, u32 dataLen, u32 offset[count+1], u8 data[dataLen]`。
    fn string_table(&mut self) -> Outcome<Vec<String>> {
        let count = self.u32()? as usize;
        let data_len = self.u32()? as usize;
        let mut offsets = Vec::with_capacity(count + 1);
        for _ in 0..count + 1 {
            offsets.push(self.u32()? as usize);
        }
        let data = self.take(data_len, "STRING_TABLE 数据区")?;
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            let from = offsets[i].min(data.len());
            let to = offsets[i + 1].min(data.len());
            let (from, to) = if from <= to { (from, to) } else { (to, from) };
            out.push(String::from_utf8_lossy(&data[from..to]).into_owned());
        }
        Ok(out)
    }
}

/// 产物目录名（转发自 `types::ERBC_DIR_NAME`，让本模块的路径拼装与别的腿同常量）。
pub fn erbc_dir_name() -> &'static str {
    ERBC_DIR_NAME
}
