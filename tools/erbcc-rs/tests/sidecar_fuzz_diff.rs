//! RUST1 / rust-sidecar-fuzz 车道闸：**侧车与清单的两侧判界对照**（纯差分挖虫，零 `src` 改动）。
//!
//! 上一路 `rust-fuzz-diff` 把畸形**容器**做了两侧差分（结论：`check` 层两侧 33/33 一致）。
//! 本路把差分面从容器扩到它旁边那两个文件与清单本身：
//! * `program.meta`（行式装载期侧车）—— Kotlin `erbcc check` 走 `ArtifactFreshness.kt:337`
//!   的 `BootMetadata.read`（**全文**解析：`BootMetadata.kt:1382-1818` 的整台状态机），
//!   Rust 走 `src/freshness.rs` 的 `read_meta_head`（**只解头部三行**，`:120-186` 自带诚实声明）；
//! * `program.stmt.idx`（段 3 偏移侧车）—— 两侧都只做结构判据（Kotlin `StmtOffsetSidecar.kt:166-182`
//!   的 `read`；Rust `freshness.rs:236-267` 的 `read_sidecar_count`），配对指纹（`recordBytes` /
//!   `recordCrc32`）与偏移内容**两侧都不判**（那两层在装载链的 `validateRecordOffsets`）；
//! * `manifest.txt` —— 两侧的清单解析是同构两写（Kotlin `ArtifactFreshness.kt:411-441` /
//!   Rust `freshness.rs:287-345`），但整数解析宽度不同（`toLongOrNull` 对 `parse u64`）。
//!
//! ## 本路的第一纪律：每档**两条腿都跑**
//!
//! `rust-playable` 那条「`erbcc check` 放行坏侧车」的读数只跑了 Rust 一条腿，结论被推翻
//! （同一份坏档 Kotlin rc=1、Rust rc=0 ⇒ 分歧在 Rust 侧）。故对照表的 Kotlin 列一律来自
//! 真跑 `com.eratw.port.erbcc.MainKt check`，Rust 列一律来自真跑 `freshness::check`
//! （= `erbcc.exe check` 调的那一个函数；[rust_cli腿与库函数腿同判] 抽查过二者一致）。
//!
//! ## 假一致防线（照 `malformed_diff.rs` 的做法，不自创第二套）
//!
//! 改侧车内部字段时**不同步**改别的文件，于是必须确认前置判据一条都没触发，否则比的是
//! 「同因假红」而不是深判据。[BANNED_PRELUDE] 那八个 kind 在 meta/sidecar 两族的每一档
//! 两侧都不许出现（[禁词断言_前置判据零出现]）；控制档 [CONTROL_ID] 两侧都 rc=0
//! （[不空转一_控制档两侧都放行]，证明拒因来自变异而不是拷贝流程）；[SHA_ANCHOR_ID]
//! （`program-sha256` 改一位）两侧都红且**逐字同文**（[不空转三_sha锚点两侧都红且逐字同文]，
//! 证明这张表在分辨而不是两侧各自瞎红）。
//!
//! ## 本路的结论形状（一句话）
//!
//! 分歧**全部**落在 `program.meta` 的「头部三行之后」（当年 60 档）；`program.stmt.idx` 的 16 档
//! **两侧逐字同判**（Rust 那份 `read_sidecar_count` 是 Kotlin `read` 的忠实副本），
//! `manifest.txt` 的 21 档里 19 档同判、2 档因整数宽度（`Long` 对 `u64`）落在不同 kind 上、
//! 退出码仍相同。⇒ 要补的深判据只在侧车那一条腿上。
//!
//! **这条账已由 `rust-meta-parity` 清掉（2026-11-01）**：`freshness::check` 把 `BootMetadata.read`
//! 的全文判据委托 JVM 真跑（`meta_leg_via_jvm`），对照表重生成后 110 档 =
//! 90 逐字同文 + 16 两侧同放行 + 2 文案薄（头部层）+ 2 kind 不同（清单整数宽度），
//! `KOTLIN_REJECT_RUST_PASS` 与 `RUST_REJECT_KOTLIN_PASS` **各 0 档** ⇒ [KNOWN_RUST_GAP] 清空，
//! 本闸剩下的是「不许再有分歧」的双向绊线。深判据自身的四件事在 `tests/meta_deep_parity.rs`。
//!
//! 畸形档本体**不入库**：只入两侧结论对照表 `tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv`
//! （每行都是两腿真跑读数，禁手抄）与复现配方 `tests/data/rust-sidecar-fuzz/REPRODUCE.txt`；
//! 变异体一律落 `tools/erbcc-rs/build/sidecar-fuzz/`（`.gitignore` 覆盖 `/build`，用完即清）。

use std::path::{Path, PathBuf};

use erbcc_rs::freshness;
use erbcc_rs::types::{current_dir, sha256_hex};

/// 种子产物（真编译产物的四件套；配方见 [reproduce_recipe]，字节不许手改）。
const SEED_DIR_REL: &str = "tests/data/rust-sidecar-fuzz/seed";
/// 与种子同源的语料（`check` 的源 sha 判据本来就绿）。
const CORPUS_REL: &str = "tests/data/rust-sidecar-fuzz/corpus/ERB";
/// 两侧结论对照表（每行都是两腿真跑读数）。
const TABLE_REL: &str = "tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv";

/// 控制档 id（一字未改）。
const CONTROL_ID: &str = "sc-control";
/// 不空转锚点①：`program-sha256` 改一位 ⇒ 两侧都红且同文（`META_STALE`）。
const SHA_ANCHOR_ID: &str = "sc-sha-flip";
/// 不空转锚点②：`functions` 计数 -1 ⇒ 两侧都红且逐字同文（原来是 Kotlin 拒 / Rust 放行，
/// 由 rust-meta-parity 的深判据腿收口，见 [锚点_functions减一_两侧都拒且逐字同文]）。
const KNOWN_ANCHOR_ID: &str = "sc-functions-minus1";

/// 前置判据禁词（meta/sidecar 两族两侧都不许出现；出现即「假一致」——分歧被它盖住）。
const BANNED_PRELUDE: [&str; 8] = [
    "ARTIFACT_SIZE",
    "ARTIFACT_SHA",
    "META_STALE",
    "SOURCE_MISSING",
    "SOURCE_CHANGED",
    "SOURCE_ADDED",
    "COMPILER_STALE",
    "MANIFEST_UNREADABLE",
];

// ======================================================================
// 变异面
// ======================================================================

/// 一族变异面（对应派单简报的六个破坏面）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Family {
    /// `program.meta` 的各类计数字段。
    MetaCount,
    /// `program.meta` 的记录体本身。
    MetaRecord,
    /// `program.meta` 头部三行。
    MetaHead,
    /// `program.meta` 截断 / 清空。
    MetaTrunc,
    /// `program.stmt.idx`。
    Sidecar,
    /// `manifest.txt`。
    Manifest,
}

impl Family {
    fn tag(self) -> &'static str {
        match self {
            Family::MetaCount => "meta-count",
            Family::MetaRecord => "meta-record",
            Family::MetaHead => "meta-head",
            Family::MetaTrunc => "meta-trunc",
            Family::Sidecar => "sidecar",
            Family::Manifest => "manifest",
        }
    }

    /// meta/sidecar 两族 = 「深判据」族：前置禁词必须零出现。
    /// 清单族判的本来就是那些前置判据自身，禁词不适用（直接对照两列结论）。
    fn prelude_banned(self) -> bool {
        !matches!(self, Family::Manifest)
    }
}

/// 一份变异体的三个可动文件（容器字节本路一律不动，避免与 rust-container-layout 抢档面）。
struct Fuzz {
    meta: Vec<u8>,
    idx: Vec<u8>,
    manifest: Vec<u8>,
}

struct Mut {
    id: &'static str,
    family: Family,
    desc: &'static str,
    apply: fn(&mut Fuzz),
}

// ---- 文本/字节小工具（每档都断言「改到了」，防止空改变成假绿）----

fn meta_text(f: &Fuzz) -> String {
    String::from_utf8(f.meta.clone()).expect("种子 program.meta 应是 UTF-8")
}

/// 把以 `prefix` 开头的那一行整行换掉；命中数必须恰好 1。
fn meta_replace_line(f: &mut Fuzz, prefix: &str, line: &str) {
    let text = meta_text(f);
    let hits = text.lines().filter(|l| l.starts_with(prefix)).count();
    assert_eq!(hits, 1, "{prefix:?} 作为行首应恰好命中一行（实得 {hits}）—— 档位失效");
    let out: String = text
        .lines()
        .map(|l| if l.starts_with(prefix) { format!("{line}\n") } else { format!("{l}\n") })
        .collect();
    f.meta = out.into_bytes();
}

/// 行内子串替换（要求原文里恰好命中一次，免得改到别的记录）。
fn meta_replace_once(f: &mut Fuzz, find: &str, repl: &str) {
    let text = meta_text(f);
    let hits = text.matches(find).count();
    assert_eq!(hits, 1, "{find:?} 在 program.meta 里应恰好出现一次（实得 {hits}）");
    f.meta = text.replacen(find, repl, 1).into_bytes();
}

/// 删掉以 `prefix` 开头的整行（要求恰好一条）。
fn meta_drop_line(f: &mut Fuzz, prefix: &str) {
    let text = meta_text(f);
    let hits = text.lines().filter(|l| l.starts_with(prefix)).count();
    assert_eq!(hits, 1, "{prefix:?} 应恰好一行（实得 {hits}）");
    let out: String = text.lines().filter(|l| !l.starts_with(prefix)).map(|l| format!("{l}\n")).collect();
    f.meta = out.into_bytes();
}

/// 保留前 `n` 行（截断到某个记录中途用）。
fn meta_keep_lines(f: &mut Fuzz, n: usize) {
    let text = meta_text(f);
    assert!(n < text.lines().count(), "截断行数必须小于总行数");
    let out: String = text.lines().take(n).map(|l| format!("{l}\n")).collect();
    f.meta = out.into_bytes();
}

/// 按字节截断（让最后一行落在记录中途）。
fn meta_cut_bytes(f: &mut Fuzz, n: usize) {
    assert!(n < f.meta.len() && n > 0, "字节截断点必须落在文件内");
    f.meta.truncate(n);
}

/// 在锚点行之前插入一行（用来做「计数与记录数自洽、但键重复」的档）。
fn meta_insert_before(f: &mut Fuzz, anchor_prefix: &str, line: &str) {
    let text = meta_text(f);
    let mut out = String::new();
    let mut hits = 0usize;
    for l in text.lines() {
        if l.starts_with(anchor_prefix) {
            hits += 1;
            out.push_str(line);
            out.push('\n');
        }
        out.push_str(l);
        out.push('\n');
    }
    assert_eq!(hits, 1, "插入锚点 {anchor_prefix:?} 应恰好一条（实得 {hits}）");
    f.meta = out.into_bytes();
}

/// 段计数字段加 `delta`（与 [meta_insert_before] 配套：计数与记录数同时改，
/// 才会撞到 CMP-82 的「重复键」判据，而不是「期望 X 实际 Y」那条）。
fn meta_bump_count(f: &mut Fuzz, prefix: &str, delta: i64) {
    let text = meta_text(f);
    let line = text
        .lines()
        .find(|l| l.starts_with(prefix))
        .unwrap_or_else(|| panic!("计数字段 {prefix:?} 找不到"))
        .to_string();
    let n: i64 = line[prefix.len()..].trim().parse().unwrap_or_else(|_| panic!("{prefix:?} 载荷不是整数"));
    meta_replace_line(f, prefix, &format!("{prefix}{}", n + delta));
}

/// 把一段十六进制的**末位**翻掉（sha / digest 的「改一位」，两边都必然不等）。
fn flip_last_hex(hex: &str) -> String {
    let last = hex.as_bytes()[hex.len() - 1] as char;
    let repl: char = match last {
        '0' => '1',
        c if c.is_ascii_digit() => (c as u8 - 1) as char,
        'a' => 'b',
        c if (b'b'..=b'f').contains(&(c as u8)) => (c as u8 - 1) as char,
        c => panic!("末位不是十六进制字符：{c}"),
    };
    format!("{}{repl}", &hex[..hex.len() - 1])
}

/// 种子里容器（`program.erbc`）的真实摘要 —— `program.meta` 与 `manifest.txt` 都登记它。
fn container_sha() -> String {
    let bytes =
        std::fs::read(tool_dir().join(SEED_DIR_REL).join("erbc.v1").join("program.erbc")).expect("读种子容器");
    sha256_hex(&bytes)
}

fn manifest_text(f: &Fuzz) -> String {
    String::from_utf8(f.manifest.clone()).expect("种子 manifest.txt 应是 UTF-8")
}

fn manifest_replace_once(f: &mut Fuzz, find: &str, repl: &str) {
    let text = manifest_text(f);
    let hits = text.matches(find).count();
    assert_eq!(hits, 1, "{find:?} 在 manifest.txt 里应恰好出现一次（实得 {hits}）");
    f.manifest = text.replacen(find, repl, 1).into_bytes();
}

/// 删掉以 `prefix` 开头的整行。
fn manifest_drop_line(f: &mut Fuzz, prefix: &str) {
    let text = manifest_text(f);
    let hits = text.lines().filter(|l| l.starts_with(prefix)).count();
    assert_eq!(hits, 1, "{prefix:?} 作为行首应恰好一行（实得 {hits}）");
    let out: String = text.lines().filter(|l| !l.starts_with(prefix)).map(|l| format!("{l}\n")).collect();
    f.manifest = out.into_bytes();
}

fn manifest_append(f: &mut Fuzz, line: &str) {
    let mut text = manifest_text(f);
    if !text.ends_with('\n') {
        text.push('\n');
    }
    text.push_str(line);
    text.push('\n');
    f.manifest = text.into_bytes();
}

/// 取 manifest 里以 `prefix` 开头的那一行（要求恰好一条）。
fn manifest_line(text: &str, prefix: &str) -> String {
    let hits: Vec<&str> = text.lines().filter(|l| l.starts_with(prefix)).collect();
    assert_eq!(hits.len(), 1, "{prefix:?} 应恰好一行（实得 {}）", hits.len());
    hits[0].to_string()
}

/// 取 manifest 里以 `prefix` 开头的**第一条**（`source` 行有 4 条，档位只动第一条）。
fn manifest_first_line(text: &str, prefix: &str) -> String {
    text.lines()
        .find(|l| l.starts_with(prefix))
        .unwrap_or_else(|| panic!("{prefix:?} 一行都没有，档位失效"))
        .to_string()
}

fn idx_u32_at(f: &Fuzz, at: usize) -> u32 {
    u32::from_le_bytes([f.idx[at], f.idx[at + 1], f.idx[at + 2], f.idx[at + 3]])
}

fn idx_put_u32(f: &mut Fuzz, at: usize, v: u32) {
    f.idx[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn seed_fuzz() -> Fuzz {
    let dir = tool_dir().join(SEED_DIR_REL).join("erbc.v1");
    Fuzz {
        meta: std::fs::read(dir.join("program.meta")).expect("读种子 program.meta"),
        idx: std::fs::read(dir.join("program.stmt.idx")).expect("读种子 program.stmt.idx"),
        manifest: std::fs::read(dir.join("manifest.txt")).expect("读种子 manifest.txt"),
    }
}

// ======================================================================
// 六族档位
// ======================================================================

/// 族 1：`program.meta` 的计数字段（+1 / -1 / 0 / 巨大 / 负 / 非整数）。
fn fam_meta_count() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::MetaCount, desc, apply })
    };
    push(
        KNOWN_ANCHOR_ID,
        "functions 计数 3→2（少读一条 ⇒ 状态机在 global-dims 处撞见 function）",
        |f| meta_replace_line(f, "functions ", "functions 2"),
    );
    push("sc-functions-plus1", "functions 计数 3→4（多要一条 ⇒ 期望 function 实际 global-dims）", |f| {
        meta_replace_line(f, "functions ", "functions 4")
    });
    push("sc-functions-zero", "functions 计数 3→0（列表段清零 ⇒ 三条 function 变孤儿）", |f| {
        meta_replace_line(f, "functions ", "functions 0")
    });
    push(
        "sc-functions-huge",
        "functions 计数 3→2000000000（旧实现先按计数预分配再撞 EOF ⇒ checkCount 判界）",
        |f| meta_replace_line(f, "functions ", "functions 2000000000"),
    );
    push("sc-labels-plus1", "labels 计数 3→4（第 4 段不存在 ⇒ 期望 labelset 实际 files）", |f| {
        meta_replace_line(f, "labels ", "labels 4")
    });
    push("sc-labels-minus1", "labels 计数 3→2（少读一段 ⇒ 状态机在 files 处撞见 labelset）", |f| {
        meta_replace_line(f, "labels ", "labels 2")
    });
    push("sc-labels-nonint", "labels 计数改成 `labels abc`（段计数必须是整数）", |f| {
        meta_replace_line(f, "labels ", "labels abc")
    });
    push("sc-files-huge", "files 计数 3→9999999（判界在分配前）", |f| {
        meta_replace_line(f, "files ", "files 9999999")
    });
    push("sc-datasec-plus1", "data-sections 计数 1→2（第二条 datasec 不存在）", |f| {
        meta_replace_line(f, "data-sections ", "data-sections 2")
    });
    push("sc-globaldims-plus1", "global-dims 计数 4→5（多要一条 ⇒ 期望 global-dim 实际 private-dims）", |f| {
        meta_replace_line(f, "global-dims ", "global-dims 5")
    });
    push("sc-globaldims-neg", "global-dims 计数 4→-1（负计数 ⇒「为负」判据）", |f| {
        meta_replace_line(f, "global-dims ", "global-dims -1")
    });
    push("sc-privdims-plus1", "private-dims 计数 1→2（多要一条）", |f| {
        meta_replace_line(f, "private-dims ", "private-dims 2")
    });
    push("sc-macros-plus1", "macros 计数 1→2（宏表多要一条）", |f| {
        meta_replace_line(f, "macros ", "macros 2")
    });
    push("sc-constants-plus1", "constants 计数 2→3（整型常量表多要一条）", |f| {
        meta_replace_line(f, "constants ", "constants 3")
    });
    push("sc-strconst-plus1", "str-constants 计数 1→2（字符串常量表多要一条）", |f| {
        meta_replace_line(f, "str-constants ", "str-constants 2")
    });
    push("sc-loadwarns-plus1", "load-warnings 计数 1→2（装载告警段多要一条）", |f| {
        meta_replace_line(f, "load-warnings ", "load-warnings 2")
    });
    push("sc-paramdef-plus1", "param-defaults 计数 1→2（可选段：缺省实参表多要一条）", |f| {
        meta_replace_line(f, "param-defaults ", "param-defaults 2")
    });
    push("sc-methodtypes-plus1", "method-types 计数 1→2（可选段：方法返回类型表多要一条）", |f| {
        meta_replace_line(f, "method-types ", "method-types 2")
    });
    push("sc-dimconfig-nonint", "dim-config 载荷改成 `x`（该记录只有一个整数字段）", |f| {
        meta_replace_line(f, "dim-config ", "dim-config x")
    });
    push("sc-dimconfig-two", "dim-config 载荷 0→2（CMP-82 后位口径严卡 0/1）", |f| {
        meta_replace_line(f, "dim-config ", "dim-config 2")
    });
    v
}

/// 族 2：`program.meta` 的记录体（少字段 / 多字段 / 引号不闭合 / 关键字换类 / 重复键 / 值域越界）。
fn fam_meta_record() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::MetaRecord, desc, apply })
    };
    push("sc-func-drop-field", "function 记录砍掉最后一个字段（position）⇒ 字段不足", |f| {
        meta_replace_line(f, "function 0 1 ", "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 0 0 0 0 0")
    });
    push("sc-func-extra-field", "function 记录尾部多一个裸记号 ⇒「function 有多余内容」", |f| {
        meta_replace_line(
            f,
            "function 0 1 ",
            "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 0 0 0 0 0 \"SC_A.ERB:2\" 9",
        )
    });
    push("sc-func-dup-index", "functions 计数 +1 并重复整条 function 0（byIndex 槽位重复 ⇒ 后者覆盖）", |f| {
        meta_bump_count(f, "functions ", 1);
        meta_insert_before(
            f,
            "function 1 2 ",
            "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 0 0 0 0 0 \"SC_A.ERB:2\"",
        );
    });
    push("sc-func-flags-oob", "function flags 0→999（高位没有消费者 ⇒ 位宽闸）", |f| {
        meta_replace_line(
            f,
            "function 0 1 ",
            "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 999 0 0 0 0 \"SC_A.ERB:2\"",
        )
    });
    push("sc-func-index-neg", "function index 0→-1（负槽位会被 byIndex 静默丢弃）", |f| {
        meta_replace_line(
            f,
            "function 0 1 ",
            "function -1 1 \"SC_ALPHA\" \"SC_ALPHA\" 0 0 0 0 0 \"SC_A.ERB:2\"",
        )
    });
    push("sc-func-defindex-neg", "function defIndex 1→-1（装载序号恒 >= 1，负值无语义来源）", |f| {
        meta_replace_line(
            f,
            "function 0 1 ",
            "function 0 -1 \"SC_ALPHA\" \"SC_ALPHA\" 0 0 0 0 0 \"SC_A.ERB:2\"",
        )
    });
    push("sc-func-argint-neg", "function argIntCount 0→-3（形参桶是数组长度 ⇒ 负值先拦）", |f| {
        meta_replace_line(
            f,
            "function 0 1 ",
            "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 0 -3 0 0 0 \"SC_A.ERB:2\"",
        )
    });
    push("sc-file-quote-unterminated", "file 记录第二个引号串不闭合（少一个右引号）", |f| {
        meta_replace_line(f, "file \"SC_ALPHA\" ", "file \"SC_ALPHA\" \"SC_A.ERB")
    });
    push("sc-keyword-swap-globaldim", "第一条 global-dim 的关键字换成 private-dim ⇒「期望 X 实际 Y」", |f| {
        meta_replace_line(
            f,
            "global-dim \"SC_GLOBALS.ERH\" 0 ",
            "private-dim \"SC_GLOBALS.ERH\" 0 \"#DIM SC_G_VAR, 3\"",
        )
    });
    push("sc-privdim-bit-two", "private-dim 的 isStr 位 0→2（位口径严卡 0/1）", |f| {
        meta_replace_line(
            f,
            "private-dim \"SC_ALPHA\" ",
            "private-dim \"SC_ALPHA\" \"SC_LOCAL\" 2 0 0 0 0 0 1 1 2 - -",
        )
    });
    push("sc-privdim-dims-neg", "private-dim 的 dims 2→-2（维度是数组长度，负值必须拒）", |f| {
        meta_replace_line(
            f,
            "private-dim \"SC_ALPHA\" ",
            "private-dim \"SC_ALPHA\" \"SC_LOCAL\" 0 0 0 0 0 0 1 1 -2 - -",
        )
    });
    push("sc-macro-dup-key", "macros 计数 +1 并重复整条 macro（Map 段同名后者覆盖）", |f| {
        meta_bump_count(f, "macros ", 1);
        meta_insert_before(f, "constants ", "macro \"SC_G_MACRO\" \"7\"");
    });
    push("sc-label-dup", "labelset 的条数 1→2 并重复同名 label（旧实现静默覆盖 pc）", |f| {
        meta_replace_line(f, "labelset \"SC_ALPHA\" ", "labelset \"SC_ALPHA\" 2");
        meta_insert_before(f, "labelset \"SC_DATA\" ", "label \"SC_L1\" 0");
    });
    push("sc-const-value-nonint", "const 的值改成 `4x2`（整型常量表必须是整数）", |f| {
        meta_replace_line(f, "const \"SC_G_CONST\" ", "const \"SC_G_CONST\" 4x2")
    });
    push("sc-const-dup-key", "constants 计数 +1 并重复整条 const（Map 段同名后者覆盖）", |f| {
        meta_bump_count(f, "constants ", 1);
        meta_insert_before(f, "str-constants ", "const \"SC_G_CONST\" 42");
    });
    push("sc-datasec-dup-idx", "data-sections 计数 +1 并重复整条 datasec（同下标两节 ⇒ 后者覆盖）", |f| {
        meta_bump_count(f, "data-sections ", 1);
        meta_insert_before(
            f,
            "functions ",
            "datasec 3 (((str \"D-ONE\" -1 -1)) ((str \"D-TWO\" -1 -1)))",
        );
    });
    push("sc-datasec-idx-neg", "datasec 的语句下标 3→-3（拿去 statement(i) 取消费者语句，负值必越界）", |f| {
        meta_replace_once(f, "datasec 3 ", "datasec -3 ")
    });
    push("sc-strconst-dup-key", "str-constants 计数 +1 并重复整条 str-const", |f| {
        meta_bump_count(f, "str-constants ", 1);
        meta_insert_before(f, "load-warnings ", "str-const \"SC_G_SC\" \"SC-STR-CONST\"");
    });
    push("sc-paramdef-dup-key", "param-defaults 计数 +1 并重复整条 param-default（函数键重复）", |f| {
        meta_bump_count(f, "param-defaults ", 1);
        meta_insert_before(f, "method-types ", "param-default \"SC_METH\" (- (int 7 0 27))");
    });
    push("sc-methodtype-badletter", "method-type 的类型位 I→X（只认 I/S/U）", |f| {
        meta_replace_line(f, "method-type ", "method-type \"SC_METH\" X")
    });
    push("sc-methodtype-multichar", "method-type 的类型位 I→INT（多字符同样不认）", |f| {
        meta_replace_line(f, "method-type ", "method-type \"SC_METH\" INT")
    });
    push("sc-labelset-count-lie", "labelset 的条数字段 1→2（第二条 label 不存在 ⇒ 期望 label 实际 labelset）", |f| {
        meta_replace_line(f, "labelset \"SC_ALPHA\" ", "labelset \"SC_ALPHA\" 2")
    });
    push("sc-escaped-quote-broken", "global-dim 原文里的转义引号去掉（JSON 风格转义坏掉 ⇒ 载荷边界错位）", |f| {
        meta_replace_once(f, "\\\"SC-STR-CONST\\\"", "\"SC-STR-CONST\"")
    });
    push("sc-datasec-paren-unbalanced", "datasec 的表达式树少一个右括号（括号配平是 decodeGroups 的判据）", |f| {
        meta_replace_once(f, "((str \"D-TWO\" -1 -1)))", "((str \"D-TWO\" -1 -1))")
    });
    push("sc-datasec-bad-atom", "datasec 树里的原子记号关键字 `str`→`strstr`（未知原子类型必须拒）", |f| {
        meta_replace_once(f, "(str \"D-ONE\"", "(strstr \"D-ONE\"")
    });
    push("sc-paramdef-paren-unbalanced", "param-default 的默认值表达式少一个右括号", |f| {
        meta_replace_once(f, "(- (int 7 0 27))", "(- (int 7 0 27)")
    });
    push("sc-meta-bad-utf8", "private-dim 记录里插一个非法 UTF-8 字节 0xFF（CMP-07 严格解码，两侧都该拒）", |f| {
        let marker = b"dim-config 0\n";
        let at = f
            .meta
            .windows(marker.len())
            .position(|w| w == marker)
            .expect("种子里找 dim-config 行尾");
        f.meta.insert(at + marker.len(), 0xFF);
    });
    push("sc-loadwarn-extra-field", "load-warn 记录尾部多一个裸记号 ⇒「load-warn 有多余内容」", |f| {
        let line = manifest_free_meta_line(f, "load-warn ");
        meta_replace_line(f, "load-warn ", &format!("{line} 9"));
    });
    push("sc-globaldim-dup-record", "global-dims 计数 +1 并重复整条 global-dim（三元组相同只可能来自坏档）", |f| {
        meta_bump_count(f, "global-dims ", 1);
        let line = manifest_free_meta_line(f, "global-dim \"SC_GLOBALS.ERH\" 0 ");
        meta_insert_before(f, "private-dims ", &line);
    });
    push("sc-privdim-dup-key", "private-dims 计数 +1 并重复整条 private-dim（函数键+变量名重复）", |f| {
        meta_bump_count(f, "private-dims ", 1);
        meta_insert_before(
            f,
            "dim-config ",
            "private-dim \"SC_ALPHA\" \"SC_LOCAL\" 0 0 0 0 0 0 1 1 2 - -",
        );
    });
    push("sc-file-dup-key", "files 计数 +1 并重复整条 file（同一函数键两行 ⇒ 前者静默丢）", |f| {
        meta_bump_count(f, "files ", 1);
        meta_insert_before(f, "data-sections ", "file \"SC_ALPHA\" \"SC_A.ERB\"");
    });
    v
}

/// 族 3：`program.meta` 头部三行（这些**两侧都判**——用来做同判据的对照面）。
fn fam_meta_head() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::MetaHead, desc, apply })
    };
    push("sc-fmtver-2", "format-version 1→2（高于本引擎支持的版本 ⇒ 两侧都拒）", |f| {
        meta_replace_line(f, "format-version ", "format-version 2")
    });
    push("sc-fmtver-0", "format-version 1→0（版本非法 ⇒ 两侧都拒）", |f| {
        meta_replace_line(f, "format-version ", "format-version 0")
    });
    push("sc-fmtver-nonint", "format-version 载荷改成 `x`（不是整数 ⇒ 两侧都拒）", |f| {
        meta_replace_line(f, "format-version ", "format-version x")
    });
    push("sc-head-order-swap", "首条记录换成 engine（首条必须是 format-version ⇒ 两侧都拒）", |f| {
        let text = meta_text(f);
        let mut ls: Vec<String> = text.lines().map(str::to_string).collect();
        assert!(ls.len() >= 2, "侧车至少要有两条记录");
        ls.swap(0, 1);
        let mut out = ls.join("\n");
        out.push('\n');
        f.meta = out.into_bytes();
    });
    push(SHA_ANCHOR_ID, "program-sha256 末位翻掉（侧车与容器不是一对 ⇒ META_STALE，两侧都红且同文）", |f| {
        let sha = container_sha();
        meta_replace_once(f, &sha, &flip_last_hex(&sha));
    });
    push("sc-sha-dash", "program-sha256 载荷改成裸短横线（写侧「无配对凭证」形态 ⇒ 与容器不等，两侧都红）", |f| {
        meta_replace_line(f, "program-sha256 ", "program-sha256 -")
    });
    push("sc-sha-unquoted", "program-sha256 载荷去掉引号（既不是 `-` 也不是引号串 ⇒ 两侧都拒）", |f| {
        let text = meta_text(f);
        let line = meta_line(&text, "program-sha256 ");
        let bare = line.replace('"', "");
        meta_replace_line(f, "program-sha256 ", &bare);
    });
    push("sc-sha-line-missing", "删掉 program-sha256 整行（头部第三条缺失 ⇒ 两侧都拒）", |f| {
        meta_drop_line(f, "program-sha256 ")
    });
    push("sc-engine-unquoted", "engine 载荷去掉引号（整条记录必须是一个引号串）", |f| {
        meta_replace_line(f, "engine ", "engine erbcc 0.1.0-SNAPSHOT")
    });
    push("sc-engine-extra-field", "engine 记录尾部多一个裸记号（atomString 后有剩余 ⇒「engine 有多余内容」）", |f| {
        meta_replace_line(f, "engine ", "engine \"erbcc 0.1.0-SNAPSHOT\" 9")
    });
    push("sc-engine-version-mismatch", "engine 标签换成别的引擎版本串（**两侧都不判它**）", |f| {
        meta_replace_line(f, "engine ", "engine \"SomeOtherEmu 9.9.9\"")
    });
    v
}

/// 族 4：`program.meta` 截断 / 清空。
fn fam_meta_trunc() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::MetaTrunc, desc, apply })
    };
    push("sc-head-only", "只留头部三行（后续段一条都没有）", |f| meta_keep_lines(f, 3));
    push("sc-keep-through-functions", "留到 functions 段末（global-dims 起全没）", |f| meta_keep_lines(f, 18));
    push("sc-keep-through-labels", "留到 labels 段末（files 起全没）", |f| meta_keep_lines(f, 8));
    push("sc-cut-midrecord", "按字节砍在 program-sha256 那行中途（记录体半截，两侧都拒但文案不同）", |f| {
        let idx = f
            .meta
            .windows(16)
            .position(|w| w == b"program-sha256 \"")
            .expect("种子里找 program-sha256 行首");
        meta_cut_bytes(f, idx + 22)
    });
    push("sc-cut-midrecord-late", "按字节砍在 private-dims 记录中途（头部三行完好 ⇒ Rust 看不见）", |f| {
        let idx = f
            .meta
            .windows(14)
            .position(|w| w == b"private-dims 1")
            .expect("种子里找 private-dims 计数行");
        meta_cut_bytes(f, idx + 40)
    });
    push("sc-drop-end-line", "砍掉尾部最后一条记录（end 行 ⇒ 侧车在 end 处提前结束）", |f| meta_drop_line(f, "end"));
    push("sc-drop-last-record", "砍掉最后一条业务记录（method-type），end 仍在", |f| {
        meta_drop_line(f, "method-type ")
    });
    push("sc-meta-empty", "program.meta 清空成 0 字节（侧车为空，两侧都拒）", |f| f.meta.clear());
    push("sc-record-after-end", "end 之后再追一条 macro 记录（end 之后还有记录）", |f| {
        let mut text = meta_text(f);
        if !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str("macro \"SC_AFTER_END\" \"1\"\n");
        f.meta = text.into_bytes();
    });
    push("sc-end-payload-junk", "end 记录带载荷（end 后不应有内容）", |f| {
        meta_replace_line(f, "end", "end junk")
    });
    v
}

/// 族 5：`program.stmt.idx`（两侧都做同一组结构判据 ⇒ 对照面；配对指纹两侧都不判 ⇒ 共同洞）。
fn fam_sidecar() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::Sidecar, desc, apply })
    };
    push("sc-idx-count-plus1", "stmtCount 10→11（声明条数与字节长度不符 ⇒ SIDECAR_UNREADABLE）", |f| {
        let n = idx_u32_at(f, 12);
        idx_put_u32(f, 12, n + 1)
    });
    push("sc-idx-count-minus1", "stmtCount 10→9（同样与长度不符）", |f| {
        let n = idx_u32_at(f, 12);
        idx_put_u32(f, 12, n - 1)
    });
    push(
        "sc-idx-count-consistent-wrong",
        "stmtCount 10→9 并真的砍掉最后 4 字节（结构自洽、但与容器段 3 条数不符 ⇒ SIDECAR_STALE）",
        |f| {
            idx_put_u32(f, 12, 9);
            let end = f.idx.len() - 4;
            f.idx.truncate(end);
        },
    );
    push("sc-idx-truncate", "整体截掉末尾 4 字节（表半截，长度不符）", |f| {
        let end = f.idx.len() - 4;
        f.idx.truncate(end);
    });
    push("sc-idx-empty", "清空成 0 字节（连头都不够 20 字节）", |f| f.idx.clear());
    push("sc-idx-only-header", "只留 24 字节头（stmtCount 仍 10 ⇒ 长度不符）", |f| {
        f.idx.truncate(24);
    });
    push("sc-idx-magic-flip", "magic 首字节改一位（ERSTMT01 → DRSTMT01）", |f| f.idx[0] ^= 0x01);
    push("sc-idx-version-3", "version 2→3（只认 1/2）", |f| idx_put_u32(f, 8, 3));
    push("sc-idx-version-0", "version 2→0", |f| idx_put_u32(f, 8, 0));
    push("sc-idx-v1-legacy", "降级成 v1 形态：version 2→1 并删掉 recordCrc32 字段（两侧都按结构合法接受）", |f| {
        idx_put_u32(f, 8, 1);
        let mut out = f.idx[..20].to_vec();
        out.extend_from_slice(&f.idx[24..]);
        f.idx = out;
    });
    push("sc-idx-count-negative", "stmtCount 改成 0xFFFFFFFF（按 i32 读成负数 ⇒ 负数判据）", |f| {
        idx_put_u32(f, 12, 0xFFFF_FFFF)
    });
    push("sc-idx-recordbytes-lie", "recordBytes 改成 99999（配对判据；**check 腿两侧都不读这个字段**）", |f| {
        idx_put_u32(f, 16, 99999)
    });
    push("sc-idx-crc-lie", "recordCrc32 改一位（配对指纹；**check 腿两侧都不读这个字段**）", |f| {
        let v = idx_u32_at(f, 20);
        idx_put_u32(f, 20, v ^ 0x01)
    });
    push("sc-idx-offset-shift", "整张偏移表每条 +1（表被平移；check 腿只看条数 ⇒ 两侧都放行）", |f| {
        let n = (f.idx.len() - 24) / 4;
        for i in 0..n {
            let at = 24 + i * 4;
            let v = idx_u32_at(f, at);
            idx_put_u32(f, at, v + 1);
        }
    });
    push("sc-idx-offset-backwards", "首两条偏移改成 5, 0（表非单调；check 腿不判内容 ⇒ 两侧都放行）", |f| {
        idx_put_u32(f, 24, 5);
        idx_put_u32(f, 28, 0);
    });
    push("sc-idx-append-junk", "偏移表尾部追加 2 个字节（长度不再是 header+4N ⇒ 两侧都拒）", |f| {
        f.idx.extend_from_slice(&[0x7f, 0x7f]);
    });
    v
}

/// 族 6：`manifest.txt`（清单族判的就是前置判据自身 ⇒ 不适用禁词断言）。
fn fam_manifest() -> Vec<Mut> {
    let mut v: Vec<Mut> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, apply: fn(&mut Fuzz)| {
        v.push(Mut { id, family: Family::Manifest, desc, apply })
    };
    push("sc-man-artifacts-count-lie", "artifacts 3→7（与 artifact 行数不符；两侧都不解析这行的语义）", |f| {
        manifest_replace_once(f, "artifacts 3", "artifacts 7")
    });
    push("sc-man-artifacts-count-neg", "artifacts 3→-1（同上）", |f| {
        manifest_replace_once(f, "artifacts 3", "artifacts -1")
    });
    push("sc-man-source-files-count-lie", "source-files 4→9（与 source 行数不符 ⇒ 两侧都不判）", |f| {
        manifest_replace_once(f, "source-files 4", "source-files 9")
    });
    push("sc-man-no-compiler-digest", "删掉 compiler-digest 行（旧编译器产物 ⇒ COMPILER_STALE，两侧都拒）", |f| {
        manifest_drop_line(f, "compiler-digest ")
    });
    push("sc-man-digest-flip", "compiler-digest 末位翻掉（产物由旧编译器产出 ⇒ COMPILER_STALE）", |f| {
        let text = manifest_text(f);
        let line = manifest_line(&text, "compiler-digest ");
        let digest = line["compiler-digest ".len()..].to_string();
        manifest_replace_once(f, &line, &format!("compiler-digest {}", flip_last_hex(&digest)));
    });
    push("sc-man-compiler-sources-junk", "compiler-sources 改成非整数（只进文案，判据只认 digest ⇒ 两侧都放行）", |f| {
        let text = manifest_text(f);
        let line = manifest_line(&text, "compiler-sources ");
        manifest_replace_once(f, &line, "compiler-sources oops");
    });
    push("sc-man-container-bytes-lie", "容器 artifact 行的字节数 +1 ⇒ ARTIFACT_SIZE，两侧都拒", |f| {
        let text = manifest_text(f);
        let line = manifest_line(&text, "artifact erbc.v1/program.erbc ");
        let sha_sep = line.rfind(' ').expect("sha 分隔符");
        let size_sep = line[..sha_sep].rfind(' ').expect("字节数分隔符");
        let bytes: u64 = line[size_sep + 1..sha_sep].parse().expect("字节数列是整数");
        let sha = &line[sha_sep + 1..];
        manifest_replace_once(f, &line, &format!("{} {} {sha}", &line[..size_sep], bytes + 1));
    });
    push("sc-man-container-sha-lie", "容器 artifact 行的 sha 末位翻掉 ⇒ ARTIFACT_SHA（并连带 META_STALE）", |f| {
        let sha = container_sha();
        let flipped = flip_last_hex(&sha);
        manifest_replace_once(f, &sha, &flipped);
    });
    push(
        "sc-man-container-bytes-i64over",
        "容器 artifact 行的字节数 = 2^63（Rust 的 u64 收得下、Kotlin 的 Long 收不下 ⇒ 拒因不同）",
        |f| {
            let text = manifest_text(f);
            let line = manifest_line(&text, "artifact erbc.v1/program.erbc ");
            let sha = line.rsplit(' ').next().expect("sha 列").to_string();
            manifest_replace_once(
                f,
                &line,
                &format!("artifact erbc.v1/program.erbc 9223372036854775808 {sha}"),
            );
        },
    );
    push(
        "sc-man-container-bytes-neg",
        "容器 artifact 行的字节数 = -5（Kotlin 的 Long 收得下、Rust 的 u64 收不下 ⇒ 拒因不同）",
        |f| {
            let text = manifest_text(f);
            let line = manifest_line(&text, "artifact erbc.v1/program.erbc ");
            let sha = line.rsplit(' ').next().expect("sha 列").to_string();
            manifest_replace_once(f, &line, &format!("artifact erbc.v1/program.erbc -5 {sha}"));
        },
    );
    push("sc-man-drop-container-line", "删掉容器的 artifact 行（没有 .erbc 条目 ⇒ MANIFEST_UNREADABLE）", |f| {
        manifest_drop_line(f, "artifact erbc.v1/program.erbc ")
    });
    push(
        "sc-man-meta-artifact-lie",
        "program.meta 的 artifact 行字节数改成 99999（**侧车自身的登记两侧都不核对** ⇒ 两侧都放行）",
        |f| {
            let text = manifest_text(f);
            let line = manifest_line(&text, "artifact erbc.v1/program.meta ");
            let sha = line.rsplit(' ').next().expect("sha 列").to_string();
            manifest_replace_once(f, &line, &format!("artifact erbc.v1/program.meta 99999 {sha}"));
        },
    );
    push(
        "sc-man-idx-artifact-lie",
        "program.stmt.idx 的 artifact 行 sha 末位翻掉（同样没人核对 ⇒ 两侧都放行）",
        |f| {
            let text = manifest_text(f);
            let line = manifest_line(&text, "artifact erbc.v1/program.stmt.idx ");
            let sha = line.rsplit(' ').next().expect("sha 列").to_string();
            manifest_replace_once(f, &line, &format!("artifact erbc.v1/program.stmt.idx 64 {}", flip_last_hex(&sha)));
        },
    );
    push("sc-man-unknown-line", "追加一行未知字段（读侧不许对未知行报错 ⇒ 两侧都放行）", |f| {
        manifest_append(f, "some-future-field hello world")
    });
    push("sc-man-empty", "manifest.txt 清空（连 artifact 行都没有 ⇒ MANIFEST_UNREADABLE）", |f| {
        f.manifest.clear();
    });
    push("sc-man-source-sha-lie", "第一条 source 行的 sha 末位翻掉 ⇒ SOURCE_CHANGED，两侧都拒", |f| {
        let text = manifest_text(f);
        let line = manifest_first_line(&text, "source ");
        let sha = line.rsplit(' ').next().expect("sha 列").to_string();
        manifest_replace_once(f, &line, &line.replace(&sha, &flip_last_hex(&sha)));
    });
    push("sc-man-source-bytes-lie", "第一条 source 行的字节数 +1（sha 照旧）⇒ SOURCE_CHANGED，两侧都拒", |f| {
        let text = manifest_text(f);
        let line = manifest_first_line(&text, "source ");
        // 从**右**往左切（相对路径可能含空格，与两侧解析同一个口径）：末段 sha、次末段字节数。
        let sha_sep = line.rfind(' ').expect("sha 分隔符");
        let size_sep = line[..sha_sep].rfind(' ').expect("字节数分隔符");
        let bytes: u64 = line[size_sep + 1..sha_sep].parse().expect("source 行的字节数列是整数");
        let sha = &line[sha_sep + 1..];
        let new_line = format!("{} {} {sha}", &line[..size_sep], bytes + 1);
        manifest_replace_once(f, &line, &new_line);
    });
    push("sc-man-drop-source-line", "删掉第一条 source 行（磁盘多出的文件 ⇒ SOURCE_ADDED，两侧都拒）", |f| {
        let text = manifest_text(f);
        let line = manifest_first_line(&text, "source ");
        let out: String = text.lines().filter(|l| *l != line).map(|l| format!("{l}\n")).collect();
        f.manifest = out.into_bytes();
    });
    push("sc-man-manifest-format-bump", "manifest-format 2→3（两侧都不读这一行的语义 ⇒ 都放行）", |f| {
        manifest_replace_once(f, "manifest-format 2", "manifest-format 3")
    });
    push("sc-man-container-version-bump", "container-version 2→3（两侧都不读；容器本身没改 ⇒ 都放行）", |f| {
        manifest_replace_once(f, "container-version 2", "container-version 3")
    });
    push("sc-man-sha-uppercase", "容器 artifact 行的 sha 全大写（Kotlin 逐字比、Rust 也逐字比 ⇒ 看两侧是否折大小写）", |f| {
        let sha = container_sha();
        manifest_replace_once(f, &sha, &sha.to_uppercase());
    });
    v
}

/// `program.meta` 里以 `prefix` 开头的那一行（要求恰好一条）。
fn meta_line(text: &str, prefix: &str) -> String {
    let hits: Vec<&str> = text.lines().filter(|l| l.starts_with(prefix)).collect();
    assert_eq!(hits.len(), 1, "{prefix:?} 应恰好一行（实得 {}）", hits.len());
    hits[0].to_string()
}

/// 同上，但直接作用于 [Fuzz]（做「重复整条记录」类档位时先取原文再插）。
fn manifest_free_meta_line(f: &Fuzz, prefix: &str) -> String {
    meta_line(&meta_text(f), prefix)
}

fn all_mutants() -> Vec<Mut> {
    let mut v = Vec::new();
    v.push(Mut {
        id: CONTROL_ID,
        family: Family::MetaHead,
        desc: "控制档：四件套一字未改（两腿都必须 rc=0，否则拒因来自拷贝流程而不是变异）",
        apply: |_f| {},
    });
    v.extend(fam_meta_count());
    v.extend(fam_meta_record());
    v.extend(fam_meta_head());
    v.extend(fam_meta_trunc());
    v.extend(fam_sidecar());
    v.extend(fam_manifest());
    let mut ids: Vec<&str> = v.iter().map(|m| m.id).collect();
    let n = ids.len();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), n, "档位 id 必须唯一");
    v
}

// ======================================================================
// 落盘与两腿
// ======================================================================

fn tool_dir() -> PathBuf {
    current_dir()
}

/// 真拷贝一份产物目录再改（**不能用硬链接**：改硬链接会串改种子原件）。
fn write_case(tag: &str, m: &Mut) -> PathBuf {
    let dir = tool_dir().join("build/sidecar-fuzz/mut").join(tag).join(m.id);
    let _ = std::fs::remove_dir_all(&dir);
    let art = dir.join("erbc.v1");
    std::fs::create_dir_all(&art).expect("建变异体目录");
    let seed = tool_dir().join(SEED_DIR_REL).join("erbc.v1");
    std::fs::copy(seed.join("program.erbc"), art.join("program.erbc")).expect("拷 program.erbc");

    let mut f = seed_fuzz();
    (m.apply)(&mut f);
    std::fs::write(art.join("program.meta"), &f.meta).expect("写变异后的 program.meta");
    std::fs::write(art.join("program.stmt.idx"), &f.idx).expect("写变异后的 program.stmt.idx");
    std::fs::write(art.join("manifest.txt"), &f.manifest).expect("写变异后的 manifest.txt");
    dir
}

/// 一条腿的结论：退出码 + 全部 issue 的 `KIND: detail` 原文（被检目录路径已折成 `<CASE>`）。
#[derive(Debug, Clone, PartialEq, Eq)]
struct Leg {
    rc: i32,
    issues: Vec<String>,
}

impl Leg {
    fn kinds(&self) -> Vec<String> {
        self.issues
            .iter()
            .map(|s| s.split(':').next().unwrap_or("").trim().to_string())
            .collect()
    }

    fn joined(&self) -> String {
        if self.issues.is_empty() {
            "PASS".to_string()
        } else {
            self.issues.join(" | ")
        }
    }
}

/// 把读数里出现的**档位目录绝对路径**折成 `<CASE>`：`MANIFEST_UNREADABLE` 那两条的 detail 带路径，
/// 而路径里有档位目录名（随 tag 变），不折叠就没法把「现跑」与「钉下的表」对齐。
///
/// **三种拼法都要试**：Rust 的 [PathBuf] 把我拼进去的 `build/sidecar-fuzz/...` 原样保留（混和
/// 分隔符），而 Java 的 `WindowsPath.toString()` 恒出反斜杠 —— 只折一种拼法会留下「两腿同判但
/// 文案带不同分隔符」的假不同文（组长在首跑 dump 时实测踩过）。
fn collapse(s: &str, case_dir: &Path) -> String {
    let raw = case_dir.to_string_lossy().into_owned();
    let mut out = s.to_string();
    for variant in [
        raw.clone(),
        raw.replace('\\', "/"),
        raw.replace('/', "\\"),
    ] {
        out = out.replace(variant.as_str(), "<CASE>");
    }
    out
}

/// 腿 R：Rust 的 `erbcc check`（`freshness::check` 就是 CLI 调的那一个函数，退出码同
/// `cmd_check.rs:36-40` 的 `report.ok ? 0 : 1`）。
fn rust_leg(out: &Path, corpus: &Path, case_dir: &Path) -> Leg {
    let report = freshness::check(out, corpus).unwrap_or_else(|e| panic!("Rust check 腿本身失败：{e:?}"));
    Leg {
        rc: if report.ok { 0 } else { 1 },
        issues: report.issues.iter().map(|i| collapse(&format!("{i}"), case_dir)).collect(),
    }
}

// ---- 腿 K：纯 Kotlin CLI（期望值的唯一来源）----

fn kotlin_classpath() -> Option<String> {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
            return Some(t.to_string());
        }
    }
    let lib = tool_dir().join("../erbcc/build/install/erbcc/lib");
    if !lib.is_dir() {
        return None;
    }
    let mut jars: Vec<String> = Vec::new();
    for e in std::fs::read_dir(&lib).ok()? {
        let p = e.ok()?.path();
        if p.extension().map(|x| x == "jar").unwrap_or(false) {
            jars.push(p.to_string_lossy().into_owned());
        }
    }
    jars.sort();
    Some(jars.join(if cfg!(windows) { ";" } else { ":" }))
}

fn java_exe() -> PathBuf {
    match std::env::var("JAVA_HOME") {
        Ok(home) if !home.trim().is_empty() => {
            Path::new(home.trim()).join(format!("bin/java{}", std::env::consts::EXE_SUFFIX))
        }
        _ => PathBuf::from("java"),
    }
}

/// Kotlin `Main.kt:406-423` 的 `cmdCheck`：`println(report.summary())`，`ok ? 0 : 1`。
/// summary 首行是 `<outDir>: …`，其后每行两空格前缀一条 issue —— 按这个形状解析。
fn kotlin_leg(cp: &str, corpus: &Path, out: &Path, case_dir: &Path) -> Leg {
    let o = std::process::Command::new(java_exe())
        .args(["-Xmx2g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(cp)
        .arg("com.eratw.port.erbcc.MainKt")
        .args(["check", "--corpus"])
        .arg(corpus)
        .arg("--out")
        .arg(out)
        .current_dir(tool_dir().join(".."))
        .output()
        .expect("起纯 Kotlin erbcc check 失败（先跑 :tools:erbcc:installDist）");
    let rc = o.status.code().unwrap_or(-1);
    let stdout = String::from_utf8_lossy(&o.stdout).into_owned();
    let mut issues: Vec<String> = Vec::new();
    for line in stdout.lines() {
        if line.starts_with("  ") {
            issues.push(collapse(line.trim_start_matches(' '), case_dir));
        }
    }
    assert!(
        rc == 0 || rc == 1,
        "Kotlin 腿退出码不是 0/1（rc={rc}）：stdout={stdout} stderr={}",
        String::from_utf8_lossy(&o.stderr)
    );
    if rc == 0 {
        assert!(issues.is_empty(), "Kotlin rc=0 却解析出 issue：{stdout}");
    } else {
        assert!(
            !issues.is_empty() && stdout.contains("条新鲜度问题"),
            "Kotlin rc={rc} 却没解析出 issue：{stdout}"
        );
    }
    Leg { rc, issues }
}

/// 两侧同拒时分「逐字同文」「同 kind 不同文案」「不同 kind」三档。
fn classify(k: &Leg, r: &Leg) -> &'static str {
    if k.rc == 0 && r.rc == 0 {
        "BOTH_PASS"
    } else if k.rc != 0 && r.rc == 0 {
        "KOTLIN_REJECT_RUST_PASS"
    } else if k.rc == 0 && r.rc != 0 {
        "RUST_REJECT_KOTLIN_PASS"
    } else if k.issues == r.issues {
        "BOTH_REJECT_VERBATIM"
    } else if k.kinds() == r.kinds() {
        "BOTH_REJECT_SAME_KIND"
    } else {
        "BOTH_REJECT_DIFFERENT_KIND"
    }
}

// ======================================================================
// 对照表
// ======================================================================

#[derive(Debug, Clone)]
struct Row {
    id: String,
    family: String,
    desc: String,
    kotlin_rc: i32,
    kotlin_issues: Vec<String>,
    rust_rc: i32,
    rust_issues: Vec<String>,
    verdict: String,
}

fn table() -> Vec<Row> {
    let text = std::fs::read_to_string(tool_dir().join(TABLE_REL)).unwrap_or_else(|e| {
        panic!(
            "缺对照表 {:?}（{e}）—— 它是本闸的两腿基准，按 dump_verdicts 的 KDoc 重新生成",
            tool_dir().join(TABLE_REL)
        )
    });
    let mut out = Vec::new();
    for line in text.lines() {
        if line.starts_with('#') || line.trim().is_empty() {
            continue;
        }
        let c: Vec<&str> = line.split('\t').collect();
        assert!(
            c.len() >= 8,
            "对照表列数不对（要 id/family/desc/kotlin_rc/kotlin_issues/rust_rc/rust_issues/verdict）：{line}"
        );
        out.push(Row {
            id: c[0].to_string(),
            family: c[1].to_string(),
            desc: c[2].to_string(),
            kotlin_rc: c[3].parse().unwrap_or_else(|_| panic!("kotlin_rc 列不是数字：{line}")),
            kotlin_issues: split_issues(c[4]),
            rust_rc: c[5].parse().unwrap_or_else(|_| panic!("rust_rc 列不是数字：{line}")),
            rust_issues: split_issues(c[6]),
            verdict: c[7].to_string(),
        });
    }
    out
}

fn split_issues(s: &str) -> Vec<String> {
    if s == "PASS" {
        Vec::new()
    } else {
        s.split(" | ").map(|x| x.to_string()).collect()
    }
}

fn row_for(rows: &[Row], id: &str) -> Row {
    rows.iter()
        .find(|r| r.id == id)
        .unwrap_or_else(|| panic!("对照表里没有档位 {id}（新增变异档要同步重跑两腿）"))
        .clone()
}

// ======================================================================
// 账（本路的猎物与共同洞；逐档读数在对照表里）
// ======================================================================

/// Rust `check` **放行**、Kotlin `check` **拒**的档位 —— **本路 0 条**（账已由 `rust-meta-parity` 清）。
///
/// 历史上这里钉着 60 档，全部落在 `program.meta` 的「头部三行之后」：Rust 的 `read_meta_head` 只解
/// `format-version`/`engine`/`program-sha256`，而 Kotlin 走 `BootMetadata.read` 全文状态机
/// （`ArtifactFreshness.kt:336-349`）。2026-11-01 起 `freshness::check` 把这一段**委托 JVM 真跑**
/// （`freshness::meta_leg_via_jvm`，只取回 `META_*` 结论原文），对照表重生成后
/// `KOTLIN_REJECT_RUST_PASS` 归零 ⇒ 这张清单随之清空。
///
/// 空清单不是「这条绊线没了」，恰恰相反：[分歧账_双向绊线]①②③ 三段合起来钉的是
/// **「重生成后的表里一条分歧都不许有」** —— 任何一档重新变成 Rust 放行（腿被短路、被放宽、
/// 或有人新增变异档忘了跑两腿）都会在这里红，且红在档名上。修法不许是往这张表里加档。
const KNOWN_RUST_GAP: &[&str] = &[
];

/// Rust 拒 / Kotlin **放行** 的档位（反向分歧）—— 本路 **0 条**：`program.stmt.idx` 与
/// `manifest.txt` 两侧判据宽度一致，头部三行两侧同源，没有一格让 Rust 比 Kotlin 严。
const REVERSE_DIVERGENCE: &[&str] = &[
];

/// 两侧都拒但**文案不同**（同一 kind）：Kotlin 的 `BootMetaFormatException` 消息带了行号或
/// JDK 异常的细节，Rust 侧没带 ⇒ 判据在、文案薄。`rust-meta-parity` 若把深判据委托回 JVM
/// 装载链，这几条会自然变成逐字同文。只对照不逐字比。
const SAME_KIND_DIFFERENT_WORDING: &[&str] = &[
    "sc-meta-bad-utf8",
    "sc-cut-midrecord",
];

/// 两侧都拒但 **kind 不同**：`artifact` 行的字节数一列 Kotlin 用 `toLongOrNull`（有符号 64 位）、
/// Rust 用 `u64`，于是「负的字节数」与「大于 2^63 的字节数」两腿落在不同判据上
/// （一侧 `ARTIFACT_SIZE`、一侧 `MANIFEST_UNREADABLE`）。**退出码两侧都是 1**，不放大成装载事故，
/// 属文案/判据归一面，写进交付报告给组长定夺。
const DIFFERENT_KIND_ROWS: &[&str] = &[
    "sc-man-container-bytes-i64over",
    "sc-man-container-bytes-neg",
];

/// meta 头部族里**曾由 Rust 放行**的两条 `engine` 载荷档（Rust 认 `engine` 关键字但不解它的载荷）。
/// rust-meta-parity 把深判据委托回 JVM 后，这两档变成两侧逐字同文 ⇒ 本清单的角色从「豁免」翻成
/// 「这条修法确实吃到了头部族」的正向钉：它们不再两侧同拒、或文案漂开，[分歧账_双向绊线]⑥ 立刻红。
const FORMER_HEAD_FAMILY_RUST_PASS: &[&str] = &[
    "sc-engine-unquoted",
    "sc-engine-extra-field",
];

/// 「判的就是配对凭证」的两档：`program-sha256` 末位翻掉 / 换成裸短横线。
/// `META_STALE` 在**别的档**上属前置判据（会把深判据的分歧盖住），在这两档上是被判对象本身，
/// 故 [不空转二_禁词断言_前置判据零出现] 只对它们豁免这一条禁词。
const PAIRING_PROBE_IDS: &[&str] = &["sc-sha-flip", "sc-sha-dash"];

/// 两侧都放行、但判据本可以收紧的档（**两侧共同的洞**，不是 Rust 独有的窄面）。
/// `check` 腿两侧都只对账容器那一条 `artifact`（Kotlin `ArtifactFreshness.kt:427` 只
/// `firstOrNull { endsWith(".erbc") }`、Rust `freshness.rs:312-315` 同口径），于是
/// **侧车自身的登记行**坏了两侧都不看；`engine` 标签两侧也都只是原样读出不参与判据。
const BOTH_PASS_SHARED_GAP: &[&str] = &[
    "sc-engine-version-mismatch",
    "sc-man-meta-artifact-lie",
    "sc-man-idx-artifact-lie",
];

/// `program.stmt.idx` 的**配对指纹与偏移内容**：两侧 `check` 腿都只看「魔数/版本/头+4N 长度」，
/// `recordBytes` / `recordCrc32` / 偏移值本身两侧都不读（判它们在装载链的
/// `ErbcStreamDecoder.validateRecordOffsets`，两层判据的口径写在 `StmtOffsetSidecar.kt:51-64`：
/// 内容级配对 + 边界抽查）。
/// ⇒ **不是 Rust 独有的窄面**（两侧同放行，且随包出去的仍然是同一份 JVM 装载链去拒/回落），
/// 但形态正是「打包期放行、装机首启才暴露」，登记给组长定夺要不要把配对判据提到 `check` 层。
const SIDECAR_PAIRING_FACE_LOOSE: &[&str] = &[
    "sc-idx-recordbytes-lie",
    "sc-idx-crc-lie",
    "sc-idx-offset-shift",
    "sc-idx-offset-backwards",
];

/// 两侧都放行且**放行是对的**（读侧按规约不许对未知行报错 / 该字段本就不参与新鲜度判据 /
/// v1 形态的偏移侧车结构上合法）—— 谁哪天把这些判红，就是过度判界（合法产物装不上同样是 bug）。
const SHOULD_PASS_BY_DESIGN: &[&str] = &[
    "sc-idx-v1-legacy",
    "sc-man-unknown-line",
    "sc-man-artifacts-count-lie",
    "sc-man-artifacts-count-neg",
    "sc-man-source-files-count-lie",
    "sc-man-compiler-sources-junk",
    "sc-man-manifest-format-bump",
    "sc-man-container-version-bump",
];

// ======================================================================
// 用例
// ======================================================================

#[test]
fn 不空转一_控制档两侧都放行() {
    let Some(cp) = kotlin_classpath() else {
        panic!("缺纯 Kotlin 工具链：先跑 ./gradlew --configure-on-demand :tools:erbcc:installDist（或设 ERBCC_CLASSPATH)");
    };
    let corpus = tool_dir().join(CORPUS_REL);
    let m = all_mutants().into_iter().find(|x| x.id == CONTROL_ID).expect("控制档存在");
    let out = write_case("control", &m);
    let r = rust_leg(&out, &corpus, &out);
    assert_eq!(r.rc, 0, "控制档的 Rust 腿不绿：{:?}", r.issues);
    let k = kotlin_leg(&cp, &corpus, &out, &out);
    assert_eq!(k.rc, 0, "控制档的 Kotlin 腿不绿（种子过期？按 reproduce_recipe 重生成）：{:?}", k.issues);
    assert_eq!(k.issues, r.issues, "控制档两侧读数不同");
    let row = row_for(&table(), CONTROL_ID);
    assert_eq!(row.verdict, "BOTH_PASS", "控制档在表里不是 BOTH_PASS：{row:?}");
}

#[test]
fn 不空转二_禁词断言_前置判据零出现() {
    let rows = table();
    let corpus = tool_dir().join(CORPUS_REL);
    let mut checked = 0usize;
    for m in all_mutants() {
        if !m.family.prelude_banned() {
            continue;
        }
        let r = row_for(&rows, m.id);
        // 现跑一遍 Rust 腿（表里的禁词可能是旧读数），加上表里两腿的读数一起判。
        let out = write_case("banned", &m);
        let live = rust_leg(&out, &corpus, &out);
        let sides: Vec<(&str, Vec<String>)> = vec![
            ("kotlin(表)", r.kotlin_issues.clone()),
            ("rust(表)", r.rust_issues.clone()),
            ("rust(现跑)", live.issues.clone()),
        ];
        // 配对凭证那两档（`program-sha256` 改一位 / 换成裸短横线）**判的就是** `META_STALE`，
        // 故只豁免这一条禁词；其余前置判据照样一条不许出现。
        let exempt = PAIRING_PROBE_IDS.contains(&m.id);
        for banned in BANNED_PRELUDE {
            if exempt && banned == "META_STALE" {
                continue;
            }
            for (side, issues) in &sides {
                assert!(
                    !issues.iter().any(|s| s.starts_with(banned)),
                    "{}：{side} 腿出现前置判据 {banned} —— 深判据的分歧会被它盖住（假一致）",
                    m.id
                );
            }
        }
        if exempt {
            for (side, issues) in &sides {
                assert_eq!(issues.len(), 1, "{side} 腿的配对凭证档应当只有一条 issue：{issues:?}");
                assert!(
                    issues[0].starts_with("META_STALE"),
                    "{side} 腿的配对凭证档拒因不是 META_STALE：{issues:?}"
                );
            }
        }
        checked += 1;
    }
    assert!(checked >= 70, "禁词断言只判了 {checked} 档，档位面缩水了");
}

#[test]
fn 不空转三_sha锚点两侧都红且逐字同文() {
    let rows = table();
    let r = row_for(&rows, SHA_ANCHOR_ID);
    assert_ne!(r.kotlin_rc, 0, "sha 锚点的 Kotlin 腿不再拒 —— 对照表过期");
    assert_ne!(r.rust_rc, 0, "sha 锚点的 Rust 腿不再拒 —— 对照表过期");
    assert_eq!(r.verdict, "BOTH_REJECT_VERBATIM", "sha 锚点两侧不再逐字同文：{r:?}");
    assert_eq!(r.kotlin_issues, r.rust_issues, "sha 锚点两侧都红但不同文");
    assert!(
        r.kotlin_issues.iter().any(|s| s.starts_with("META_STALE")),
        "sha 锚点的拒因不是 META_STALE：{:?}",
        r.kotlin_issues
    );
    // 现跑复验（Rust 腿）：同一判据、同一串字。
    let corpus = tool_dir().join(CORPUS_REL);
    let m = all_mutants().into_iter().find(|x| x.id == SHA_ANCHOR_ID).expect("档位存在");
    let out = write_case("sha-anchor", &m);
    let live = rust_leg(&out, &corpus, &out);
    assert_eq!(live.issues, r.rust_issues, "sha 锚点的 Rust 现跑读数与对照表不同");
}

#[test]
fn 锚点_functions减一_两侧都拒且逐字同文() {
    let rows = table();
    let r = row_for(&rows, KNOWN_ANCHOR_ID);
    // 这档是原 60 档分歧的代表（`rust-playable` 报出的那一条装机首启崩溃形态）。rust-meta-parity
    // 把深判据委托回 JVM 之后，它从 KOTLIN_REJECT_RUST_PASS 升级为两侧都拒且**文案逐字相同**；
    // 谁把这行改回「Rust 放行」，这里先红，红在档名上。
    assert_eq!(r.verdict, "BOTH_REJECT_VERBATIM", "锚点档不再是两侧逐字同文：{r:?}");
    assert_ne!(r.kotlin_rc, 0, "锚点档的 Kotlin 腿放行了（对照表过期或种子变了）");
    assert_ne!(r.rust_rc, 0, "锚点档的 Rust 腿又放行了（深判据腿被短路？）");
    assert_eq!(r.kotlin_issues, r.rust_issues, "锚点档两侧都拒但不同文");
    let text = r.kotlin_issues.join(" ");
    assert!(
        text.starts_with("META_UNREADABLE: program.meta 读取失败: BootMetaFormatException: 第 ")
            && text.contains("期望 global-dims，实际 function"),
        "锚点档的 Kotlin 原文与 rust-playable 那一条不同形：{text}"
    );
    // 现跑复验：委托腿的原文就是表里那一串（表是死的，这条是活的）。
    let corpus = tool_dir().join(CORPUS_REL);
    let m = all_mutants().into_iter().find(|x| x.id == KNOWN_ANCHOR_ID).expect("档位存在");
    let out = write_case("anchor-live", &m);
    let live = rust_leg(&out, &corpus, &out);
    assert_eq!(live.issues, r.rust_issues, "锚点档的 Rust 现跑读数与对照表不同");
}

#[test]
fn rust腿现跑_与钉下的表逐档同判() {
    let rows = table();
    let corpus = tool_dir().join(CORPUS_REL);
    for m in all_mutants() {
        let want = row_for(&rows, m.id);
        assert_eq!(want.desc, m.desc, "{}：对照表的变异说明与档位定义不同（表过期）", m.id);
        let out = write_case("live-rust", &m);
        let got = rust_leg(&out, &corpus, &out);
        assert_eq!(got.rc, want.rust_rc, "{}：Rust 退出码与对照表不同", m.id);
        assert_eq!(
            got.issues,
            want.rust_issues,
            "{}（{}）：Rust 读数与对照表不同（freshness.rs 改了？还是档位构造漂了？）",
            m.id,
            m.desc
        );
        assert_eq!(
            classify(&Leg { rc: want.kotlin_rc, issues: want.kotlin_issues.clone() }, &got),
            want.verdict,
            "{}：判定列与两腿读数不自洽",
            m.id
        );
    }
    assert_eq!(rows.len(), all_mutants().len(), "对照表档位数与变异面不等");
}

#[test]
fn 分歧账_双向绊线() {
    let rows = table();
    // ①账上每一条确实还是「Kotlin 拒 / Rust 放行」。
    for id in KNOWN_RUST_GAP {
        let r = row_for(&rows, id);
        assert_eq!(
            r.verdict,
            "KOTLIN_REJECT_RUST_PASS",
            "{id}：账上档位的判定变了（若已收紧，请移出 KNOWN_RUST_GAP 并写进交付报告）：{r:?}",
        );
    }
    // ②账外不许出现同类分歧（新虫必须先登记再收口）。
    let offbook: Vec<String> = rows
        .iter()
        .filter(|r| r.verdict == "KOTLIN_REJECT_RUST_PASS" && !KNOWN_RUST_GAP.contains(&r.id.as_str()))
        .map(|r| format!("{}：{}", r.id, r.kotlin_issues.join(" | ")))
        .collect();
    assert!(
        offbook.is_empty(),
        "出现**账外**的 Kotlin 拒-Rust 放行档（先登记再收口）：\n{}",
        offbook.join("\n")
    );
    assert_eq!(
        rows.iter().filter(|r| r.verdict == "KOTLIN_REJECT_RUST_PASS").count(),
        KNOWN_RUST_GAP.len(),
        "分歧总数与账目条数不等（有档位重名或漏记）"
    );
    // ③反向分歧（Rust 比 Kotlin 严）也要在案。
    let reverse: Vec<&Row> = rows.iter().filter(|r| r.verdict == "RUST_REJECT_KOTLIN_PASS").collect();
    for r in &reverse {
        assert!(
            REVERSE_DIVERGENCE.contains(&r.id.as_str()),
            "出现没登记过的 Rust 拒-Kotlin 放行档：{}（{:?}）",
            r.id,
            r.rust_issues
        );
    }
    for id in REVERSE_DIVERGENCE {
        assert!(reverse.iter().any(|r| r.id == *id), "{id}：在册的反向分歧不再成立（Kotlin 收紧了？更新账与报告）");
    }
    // ④两侧同放行的档必须全部归进三个账之一（清单/引擎标签共同洞、侧车配对面、设计上就该放行）。
    for r in rows.iter().filter(|r| r.verdict == "BOTH_PASS" && r.id != CONTROL_ID) {
        assert!(
            BOTH_PASS_SHARED_GAP.contains(&r.id.as_str())
                || SIDECAR_PAIRING_FACE_LOOSE.contains(&r.id.as_str())
                || SHOULD_PASS_BY_DESIGN.contains(&r.id.as_str()),
            "{}：新的两侧同放行档没归账（两侧一起变宽？）",
            r.id
        );
    }
    for id in BOTH_PASS_SHARED_GAP {
        let r = row_for(&rows, id);
        assert_eq!(r.verdict, "BOTH_PASS", "{id}：这条「两侧共同的洞」不再两侧同放行：{r:?}");
    }
    for id in SIDECAR_PAIRING_FACE_LOOSE {
        let r = row_for(&rows, id);
        assert_eq!(r.verdict, "BOTH_PASS", "{id}：侧车配对面不再两侧同放行（收紧了？更新账）：{r:?}",);
    }
    for id in SHOULD_PASS_BY_DESIGN {
        let r = row_for(&rows, id);
        assert_eq!(r.verdict, "BOTH_PASS", "{id}：设计上该放行的档被判红了（过度判界）：{:?}", r.rust_issues);
    }
    // ⑤「都拒但 kind 不同」（清单整数宽度两档）与「都拒但文案不同」（截在半截记录上）各自在册。
    for id in DIFFERENT_KIND_ROWS {
        let r = row_for(&rows, id);
        assert_eq!(r.verdict, "BOTH_REJECT_DIFFERENT_KIND", "{id}：不再是「都拒但 kind 不同」：{r:?}");
        assert_ne!(r.kotlin_rc, 0, "{id}：Kotlin 腿不再拒");
        assert_ne!(r.rust_rc, 0, "{id}：Rust 腿不再拒");
    }
    let offbook_diff_kind: Vec<&str> = rows
        .iter()
        .filter(|r| r.verdict == "BOTH_REJECT_DIFFERENT_KIND" && !DIFFERENT_KIND_ROWS.contains(&r.id.as_str()))
        .map(|r| r.id.as_str())
        .collect();
    assert!(offbook_diff_kind.is_empty(), "出现账外的「都拒但 kind 不同」档：{offbook_diff_kind:?}");
    for id in SAME_KIND_DIFFERENT_WORDING {
        let r = row_for(&rows, id);
        assert_eq!(r.verdict, "BOTH_REJECT_SAME_KIND", "{id}：不再是「都拒但文案不同」：{r:?}");
    }
    // ⑥meta 头部族是**两侧同源**的那三面（format-version / program-sha256 / 记录序）：清账后
    //    整族不许再有「Kotlin 拒 / Rust 放行」，其中登记在册的两条 engine 载荷档必须由深判据腿
    //    吃到逐字同文（它们是「委托确实覆盖了头部族」的正向证据，不只是不再分歧）。
    for r in rows.iter().filter(|r| r.family == "meta-head") {
        if FORMER_HEAD_FAMILY_RUST_PASS.contains(&r.id.as_str()) {
            assert_eq!(
                r.verdict, "BOTH_REJECT_VERBATIM",
                "{}：头部族这条曾经的豁免档不再两侧逐字同文 —— 深判据腿没吃到 engine 载荷？{r:?}",
                r.id
            );
        } else if r.id != CONTROL_ID {
            assert!(
                r.verdict == "BOTH_REJECT_VERBATIM"
                    || r.verdict == "BOTH_REJECT_SAME_KIND"
                    || BOTH_PASS_SHARED_GAP.contains(&r.id.as_str()),
                "{}：头部族出现账外分歧（Rust 的头部三行判界变了？）：{r:?}",
                r.id
            );
        }
    }
}

#[test]
fn 族覆盖_每族至少三档() {
    let rows = table();
    let mut count: std::collections::BTreeMap<&str, usize> = std::collections::BTreeMap::new();
    for r in &rows {
        *count.entry(r.family.as_str()).or_insert(0usize) += 1;
    }
    let mut total = 0usize;
    for fam in ["meta-count", "meta-record", "meta-head", "meta-trunc", "sidecar", "manifest"] {
        let n = *count.get(fam).unwrap_or(&0);
        assert!(n >= 3, "族 {fam} 只有 {n} 档（契约要求每族至少 3 档）");
        total += n;
    }
    assert_eq!(total, rows.len(), "有档位的族名不在六族之内");
    assert!(rows.len() >= 80, "档位总数 {} 少于预期（变异面缩水）", rows.len());
}

#[test]
fn 侧车结构判据_两侧同判_清单族两侧同判() {
    // sidecar 族的**结构**判据两侧都有实现（Rust 抄 Kotlin 的 read），故除已知几条外必须同判；
    // manifest 族的判据两侧都有实现，除整数宽度那两档外必须同判（且 kinds 一致）。
    let rows = table();
    for r in rows.iter().filter(|r| r.family == "sidecar") {
        assert_eq!(
            r.kotlin_rc, r.rust_rc,
            "{}：sidecar 族的拒/放行两侧不同（这条应当在 KNOWN_RUST_GAP 里登记）：{r:?}",
            r.id
        );
        assert!(
            r.kotlin_issues == r.rust_issues || KNOWN_RUST_GAP.contains(&r.id.as_str()),
            "{}：sidecar 族两侧都拒但文案不同：{:?}",
            r.id,
            r.kotlin_issues
        );
    }
    let manifest_int_width: [&str; DIFFERENT_KIND_ROWS.len()] = DIFFERENT_KIND_ROWS.try_into().expect("DIFFERENT_KIND_ROWS 长度变了同步改这里");
    for r in rows.iter().filter(|r| r.family == "manifest") {
        assert_eq!(r.kotlin_rc, r.rust_rc, "{}：manifest 族的拒/放行两侧不同：{r:?}", r.id);
        if !manifest_int_width.contains(&r.id.as_str()) {
            assert_eq!(
                r.kotlin_issues, r.rust_issues,
                "{}：manifest 族两侧都拒但文案/kind 不同",
                r.id
            );
        }
    }
}

/// 纯 Kotlin 腿的真跑复验（默认抽 8 档；开 `ERATW_SIDECAR_FUZZ_JVM=1` 逐档全跑）。
///
/// 这一条是「表里的 Kotlin 列不是抄来的」的第二次观察：`rust-playable` 那条反向结论
/// 就是只跑一条腿造成的，故本闸把「两条腿都跑」做成常驻用例，而不是手一次算完。
#[test]
fn kotlin腿真跑_与钉下的表一致() {
    let Some(cp) = kotlin_classpath() else {
        panic!(
            "缺纯 Kotlin 工具链：先跑 ./gradlew --configure-on-demand :tools:erbcc:installDist \
             （或设 ERBCC_CLASSPATH）—— 否则本趟没有任何 Kotlin 侧观察"
        );
    };
    let full = std::env::var("ERATW_SIDECAR_FUZZ_JVM").map(|v| v == "1").unwrap_or(false);
    let rows = table();
    let corpus = tool_dir().join(CORPUS_REL);
    let sample = [
        CONTROL_ID,
        KNOWN_ANCHOR_ID,
        SHA_ANCHOR_ID,
        "sc-functions-huge",
        "sc-idx-count-plus1",
        "sc-man-no-compiler-digest",
        "sc-head-only",
        "sc-methodtype-badletter",
    ];
    let mut ran = 0usize;
    for m in all_mutants() {
        if !full && !sample.contains(&m.id) {
            continue;
        }
        let want = row_for(&rows, m.id);
        let out = write_case(if full { "live-kotlin" } else { "spot-kotlin" }, &m);
        let got = kotlin_leg(&cp, &corpus, &out, &out);
        assert_eq!(got.rc, want.kotlin_rc, "{}：Kotlin 退出码与对照表不同（现跑 {:?}）", m.id, got.issues);
        assert_eq!(
            got.issues,
            want.kotlin_issues,
            "{}（{}）：Kotlin 现跑读数与钉下的表不同 —— Kotlin 侧漂了或档位构造变了",
            m.id,
            m.desc
        );
        ran += 1;
    }
    if !full {
        assert_eq!(ran, sample.len(), "抽样档数不对（有档位 id 拼错）：{ran}");
    }
}

/// `src/` 里最新的 `.rs` 比 CLI 二进制新 ⇒ 返回那个时间（= 二进制是旧的）。
///
/// 存在的理由：本闸的 CLI 腿拿的是 `target/release/erbcc.exe`，而 cargo test **不会**重编 release
/// 产物。2026-11-01 加 `program.meta` 的深判据腿后没重编，旧二进制对同一档退 0、新库腿退 1，
/// 于是出一条「CLI 退出码与对照表不同」的假读数 —— 腿换了行为时，旧二进制这一腿根本没在跑今天的判据。
fn cli_stale_against_src(cli: &Path) -> Option<std::time::SystemTime> {
    let built = cli.metadata().and_then(|m| m.modified()).ok()?;
    let mut newest: Option<std::time::SystemTime> = None;
    let rd = match std::fs::read_dir(tool_dir().join("src")) {
        Ok(rd) => rd,
        Err(_) => return None,
    };
    for entry in rd.flatten() {
        let p = entry.path();
        if p.extension().map(|x| x == "rs").unwrap_or(false) {
            if let Ok(t) = p.metadata().and_then(|m| m.modified()) {
                if newest.map(|n| t > n).unwrap_or(true) {
                    newest = Some(t);
                }
            }
        }
    }
    newest.filter(|t| *t > built)
}

/// Rust 的 **CLI 腿**与库函数腿同判（本闸把 Rust 侧读数交给 `freshness::check`，
/// 这一条钉住「CLI 只是它的一层壳」这个前提）。缺 `erbcc.exe` 时跳过并报一句话；
/// **二进制比 `src/` 旧时报红**（旧二进制不是「没测」，是「测出一条假读数」，见 [cli_stale_against_src]）。
#[test]
fn rust_cli腿与库函数腿同判() {
    let cli = tool_dir().join(format!("target/release/erbcc{}", std::env::consts::EXE_SUFFIX));
    if !cli.is_file() {
        eprintln!("跳过：缺 target/release/erbcc.exe（cargo build --release --bin erbcc 后再跑本用例）");
        return;
    }
    if let Some(t) = cli_stale_against_src(&cli) {
        panic!(
            "CLI 腿过期：{} 比 src/ 里最新的 .rs（{t:?}）还旧 ⇒ 这一腿跑的不是今天的判据。\
             先 cargo build --release --bin erbcc 再复跑本用例（2026-11-01 加深判据腿后就撞过这条）",
            cli.display()
        );
    }
    let corpus = tool_dir().join(CORPUS_REL);
    let rows = table();
    let sample = [
        CONTROL_ID,
        KNOWN_ANCHOR_ID,
        SHA_ANCHOR_ID,
        "sc-idx-count-consistent-wrong",
        "sc-man-digest-flip",
        "sc-record-after-end",
        "sc-man-container-bytes-neg",
    ];
    for m in all_mutants() {
        if !sample.contains(&m.id) {
            continue;
        }
        let out = write_case("cli-vs-lib", &m);
        let o = std::process::Command::new(&cli)
            .args(["check", "--corpus"])
            .arg(&corpus)
            .arg("--out")
            .arg(&out)
            .output()
            .expect("起 erbcc.exe check 失败");
        let rc = o.status.code().unwrap_or(-1);
        // 上屏原文里带被检目录的绝对路径，先按同一口径折成 <CASE> 再比（否则「同判但分隔符不同」）。
        let stdout = collapse(&String::from_utf8_lossy(&o.stdout), &out);
        let want = row_for(&rows, m.id);
        assert_eq!(rc, want.rust_rc, "{}：CLI 退出码与对照表不同（stdout={stdout}）", m.id);
        let live = rust_leg(&out, &corpus, &out);
        assert_eq!(live.rc, rc, "{}：库函数腿与 CLI 退出码不一致（cmd_check 的 rc 映射漂了？）", m.id);
        for issue in &live.issues {
            assert!(
                stdout.contains(issue.as_str()),
                "{}：CLI 上屏里没有这条 issue 原文 {issue:?}（stdout={stdout}）",
                m.id
            );
        }
        if rc == 0 {
            assert!(live.issues.is_empty(), "{}：CLI rc=0 但库函数腿有 issue：{:?}", m.id, live.issues);
        }
    }
}

// ======================================================================
// 重生成（禁手抄：两列都来自真跑）
// ======================================================================

/// 复现配方（怎么重生成种子、怎么单档手跑两腿）——把读数交给组长复用的唯一入口。
#[test]
fn reproduce_recipe() {
    let seed_manifest = tool_dir().join(SEED_DIR_REL).join("erbc.v1").join("manifest.txt");
    let text = std::fs::read_to_string(&seed_manifest).expect("读种子 manifest.txt");
    let digest = manifest_line(&text, "compiler-digest ");
    println!("种子：{}（{:?}）", SEED_DIR_REL, std::fs::metadata(tool_dir().join(SEED_DIR_REL)).map(|m| m.len()));
    println!("语料：{CORPUS_REL}");
    println!("种子里容器的 sha256 = {}", container_sha());
    println!("种子里 {digest}");
    println!(
        "重生成种子：erbcc.exe compile --corpus <绝对路径>/{CORPUS_REL} --out build/sidecar-fuzz/seedgen \
         然后把产出的 erbc.v1 整份拷进 {SEED_DIR_REL}/（改过 core-vm 之后必须重生成，否则 compiler-digest 判据两侧同红）"
    );
    println!("单档手跑（两腿都要跑）：cargo test --test sidecar_fuzz_diff dump_verdicts -- --ignored --nocapture > 新表");
}

/// `cargo test --test sidecar_fuzz_diff dump_verdicts -- --ignored --nocapture`
/// 把两腿真跑读数按 TSV 打到 stdout；重定向进 [TABLE_REL] 就是新的基准。
///
/// `ERATW_SIDECAR_FUZZ_RUSTONLY=1` 只跑 Rust 腿（Kotlin 列打 `?`）—— 用它先过一遍
/// 「每档变异是否真的改到了」（各 helper 都带命中断言），再跑全量，省一半 JVM 启动。
#[test]
#[ignore = "重生成对照表才跑：cargo test --test sidecar_fuzz_diff dump_verdicts -- --ignored --nocapture"]
fn dump_verdicts() {
    let rust_only = std::env::var("ERATW_SIDECAR_FUZZ_RUSTONLY").map(|v| v == "1").unwrap_or(false);
    let cp = if rust_only { None } else { Some(kotlin_classpath().expect("dump 需要纯 Kotlin 工具链（installDist 或 ERBCC_CLASSPATH）")) };
    let corpus = tool_dir().join(CORPUS_REL);
    println!("# id\tfamily\tdesc\tkotlin_rc\tkotlin_issues\trust_rc\trust_issues\tverdict");
    for m in all_mutants() {
        let out = write_case("dump", &m);
        let r = rust_leg(&out, &corpus, &out);
        let Some(cp) = cp.as_deref() else {
            println!(
                "{}\t{}\t{}\t?\t?\t{}\t{}\t?",
                m.id,
                m.family.tag(),
                m.desc.replace('\t', " "),
                r.rc,
                r.joined()
            );
            continue;
        };
        let k = kotlin_leg(cp, &corpus, &out, &out);
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            m.id,
            m.family.tag(),
            m.desc.replace('\t', " "),
            k.rc,
            k.joined(),
            r.rc,
            r.joined(),
            classify(&k, &r)
        );
    }
}
