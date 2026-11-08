//! RUST1 / rust-meta-parity 车道闸：**`program.meta` 的深判据与 Kotlin 同判**。
//!
//! ## 这路修的是什么
//!
//! `rust-sidecar-fuzz` 量出的账（`tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv`）：110 档两侧对照里
//! **60 档 `KOTLIN_REJECT_RUST_PASS`**，全部落在 `program.meta` 的头部三行之后。成因是唯一的：
//! Kotlin 的 `erbcc check` 在 `ArtifactFreshness.kt:337` 走 `BootMetadata.read`
//! （`core-vm/src/main/kotlin/com/eratw/port/core/loader/BootMetadata.kt:414-426`，全文两趟状态机
//! `:1382-1818`）并把失败折成 `META_UNREADABLE`（`:344-349`）；Rust 的 `freshness::read_meta_head`
//! 只解 `format-version` / `engine` / `program-sha256` 三行，头部之后的段计数、记录序、载荷口径
//! 一条都不看 ⇒ 同一份坏档 Kotlin rc=1、Rust rc=0。rust-playable 那条「打包期放行、装机首启才暴露」
//! 说的就是这批档。
//!
//! ## 修法是委托，不是重写
//!
//! [erbcc_rs::freshness::meta_leg_via_jvm] 起一条子进程**真跑** Kotlin `erbcc check`
//! （`Main.kt:406-423`，argv 形状在 `kernel::check_via_jvm`），只把它 `META_*` 三条 kind 的
//! 结论原文取回来接进 Rust 的 issue 单；其余判据（清单行 / 容器 / `program.stmt.idx` / 逐源 sha）
//! 一律丢弃，仍由 Rust 自己算 —— 于是头层读数一条没放宽，而深判据的**唯一实现方仍是 core-vm**。
//! Rust 侧重写那台状态机就是第二实现方漂移，契约 §0 铁律 1 明令禁止。
//!
//! 判定顺序也照 Kotlin（`ArtifactFreshness.kt:336-349`）：全文解析失败 ⇒ 一条 `META_UNREADABLE`，
//! 此时配对判据 `program-sha256` **根本不跑**；读过了才比 ⇒ `META_STALE`。
//!
//! ## 本闸的四件事
//!
//! 1. [族代表档_两侧逐字同判] —— 六个判据族各取代表档（计数 / 记录 / 截尾 / 头部 engine / 控制 /
//!    配对凭证），**两腿现跑**，`rc` 与逐条 issue 原文必须逐字节相同；
//! 2. [六十档_tsv_逐档两侧同判且逐字同文] —— 那 60 档在**重生成后的**对照表里必须全部两侧都拒、
//!    文案逐字相同（表由 `cargo test --test sidecar_fuzz_diff dump_verdicts -- --ignored --nocapture`
//!    现跑产出，禁手抄）；
//! 3. [反向证明_恒放行替身下同一批断言必须红] —— 把委托点打桩成「恒放行」，**同一个**判据函数
//!    [verdict_of] 必须对这 14 档给出「不通过」；生产腿过、替身腿不过，才证明红是腿带来的。
//!    另配 [腿真被跑了一次_计数断言]：每次 `check` 恰好走一次 JVM 腿，头层已判死的档一次都不走；
//! 4. [不空转_前置判据零出现_读表两列] + [assert_no_prelude] —— 八个前置 kind 在深判据档的两侧读数
//!    里一条都不许出现（出现了就是「同因假红」，深判据的分歧会被它盖住）。配对凭证两档按
//!    `rust-sidecar-fuzz` 的同款口径只豁免 `META_STALE`。
//!
//! 代价读数（第 4 项任务）由 [dump_cost_readings] 现跑打印，落档
//! `tests/data/rust-meta-parity/cost-readings.txt`，由 [代价读数在册] 钉住它在册且两档齐。
//!
//! 变异体本体不入库（落 `tools/erbcc-rs/build/meta-parity/cases/`），种子与语料复用
//! `tests/data/rust-sidecar-fuzz/{seed,corpus}`（同一份真编译产物；改过 `core-vm` 后按该目录的
//! `REPRODUCE.txt` 重生成，否则 `COMPILER_STALE` 会把两侧一起判红 —— 那是前置判据，
//! 禁词断言会先替我喊）。

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use erbcc_rs::freshness::{self, Issue};
use erbcc_rs::types::{current_dir, Outcome, sha256_hex};

/// 种子产物 + 同源语料（与 `rust-sidecar-fuzz` 同一份，别建第二份基准）。
const SEED_DIR_REL: &str = "tests/data/rust-sidecar-fuzz/seed";
const CORPUS_REL: &str = "tests/data/rust-sidecar-fuzz/corpus/ERB";
/// 两侧结论对照表（每行都是两腿真跑读数，本路清账后重生成）。
const TABLE_REL: &str = "tests/data/rust-sidecar-fuzz/kotlin-vs-rust.tsv";
/// 代价读数（本路第 4 项任务的落档处）。
const COST_READING_REL: &str = "tests/data/rust-meta-parity/cost-readings.txt";

/// 控制档 id（一字未改，两侧都必须放行）。
const CONTROL_ID: &str = "mp-control";
/// 不空转锚点：`program-sha256` 末位翻掉 ⇒ 两侧都红且逐字同文（`META_STALE`）。
const SHA_ANCHOR_ID: &str = "mp-sha-flip";
/// 不空转锚点之二：`program-sha256` 换成裸短横线（写侧「无配对凭证」形态）。
const SHA_DASH_ID: &str = "mp-sha-dash";

/// 前置判据禁词（照 `sidecar_fuzz_diff.rs` 的同名清单，把「同因假红」挡在外面）。
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

/// 配对凭证两档豁免 `META_STALE`（它们判的就是这条判据），与 `rust-sidecar-fuzz` 同一口径。
const PAIRING_PROBE_IDS: [&str; 2] = [SHA_ANCHOR_ID, SHA_DASH_ID];

/// `rust-sidecar-fuzz` 记在册的那 60 档（原 `KNOWN_RUST_GAP`，本路把它们从
/// `KOTLIN_REJECT_RUST_PASS` 收成两侧都拒）。清单在本闸里写死一份：账清之后对照表里不再有
/// `KOTLIN_REJECT_RUST_PASS` 可筛，没有这份清单就没人能证明「60 档一档没漏」。
const META_DEEP_IDS: [&str; 60] = [
    "sc-functions-minus1",
    "sc-functions-plus1",
    "sc-functions-zero",
    "sc-functions-huge",
    "sc-labels-plus1",
    "sc-labels-minus1",
    "sc-labels-nonint",
    "sc-files-huge",
    "sc-datasec-plus1",
    "sc-globaldims-plus1",
    "sc-globaldims-neg",
    "sc-privdims-plus1",
    "sc-macros-plus1",
    "sc-constants-plus1",
    "sc-strconst-plus1",
    "sc-loadwarns-plus1",
    "sc-paramdef-plus1",
    "sc-methodtypes-plus1",
    "sc-dimconfig-nonint",
    "sc-dimconfig-two",
    "sc-func-drop-field",
    "sc-func-extra-field",
    "sc-func-dup-index",
    "sc-func-flags-oob",
    "sc-func-index-neg",
    "sc-func-defindex-neg",
    "sc-func-argint-neg",
    "sc-file-quote-unterminated",
    "sc-keyword-swap-globaldim",
    "sc-privdim-bit-two",
    "sc-privdim-dims-neg",
    "sc-macro-dup-key",
    "sc-label-dup",
    "sc-const-value-nonint",
    "sc-const-dup-key",
    "sc-datasec-dup-idx",
    "sc-datasec-idx-neg",
    "sc-strconst-dup-key",
    "sc-paramdef-dup-key",
    "sc-methodtype-badletter",
    "sc-methodtype-multichar",
    "sc-labelset-count-lie",
    "sc-escaped-quote-broken",
    "sc-datasec-paren-unbalanced",
    "sc-datasec-bad-atom",
    "sc-paramdef-paren-unbalanced",
    "sc-loadwarn-extra-field",
    "sc-globaldim-dup-record",
    "sc-privdim-dup-key",
    "sc-file-dup-key",
    "sc-engine-unquoted",
    "sc-engine-extra-field",
    "sc-head-only",
    "sc-keep-through-functions",
    "sc-keep-through-labels",
    "sc-cut-midrecord-late",
    "sc-drop-end-line",
    "sc-drop-last-record",
    "sc-record-after-end",
    "sc-end-payload-junk",
];

/// 六个判据族（`rust-sidecar-fuzz` 的读数分族：计数 20 / 记录 30 / 截尾 8 / 头 2，另加控制与
/// 配对凭证两条锚）；本闸的代表档逐个覆盖，**一次 JVM 腿收一族**。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Family {
    Control,
    MetaCount,
    MetaRecord,
    MetaHead,
    MetaTrunc,
    Pairing,
}

impl Family {
    fn tag(self) -> &'static str {
        match self {
            Family::Control => "control",
            Family::MetaCount => "meta-count",
            Family::MetaRecord => "meta-record",
            Family::MetaHead => "meta-head",
            Family::MetaTrunc => "meta-trunc",
            Family::Pairing => "pairing",
        }
    }
}

struct Case {
    id: &'static str,
    family: Family,
    desc: &'static str,
    /// 对种子 `program.meta` 的破坏配方（只改侧车本体；容器 / 偏移侧车 / 清单一个字节不动）。
    apply: fn(&mut Vec<u8>),
    /// 期望的拒因 kind（控制档 = None：两侧都必须 rc=0）。
    expect_kind: Option<&'static str>,
}

// ======================================================================
// 变异配方（每档都断言「真的改到了」，防空改变成假绿）
// ======================================================================

fn text_of(meta: &[u8]) -> String {
    String::from_utf8(meta.to_vec()).expect("种子 program.meta 应是 UTF-8")
}

/// 把以 `prefix` 开头的那一行整行换掉（命中数必须恰好 1）。
fn replace_line(meta: &mut Vec<u8>, prefix: &str, line: &str) {
    let text = text_of(meta);
    let hits = text.lines().filter(|l| l.starts_with(prefix)).count();
    assert_eq!(hits, 1, "{prefix:?} 作为行首应恰好命中一行（实得 {hits}）—— 档位失效");
    let out: String = text
        .lines()
        .map(|l| if l.starts_with(prefix) { format!("{line}\n") } else { format!("{l}\n") })
        .collect();
    *meta = out.into_bytes();
}

/// 只保留前 `n` 行（截断族）。
fn keep_lines(meta: &mut Vec<u8>, n: usize) {
    let text = text_of(meta);
    let total = text.lines().count();
    assert!(n < total, "截断行数 {n} 必须小于总行数 {total}");
    let out: String = text.lines().take(n).map(|l| format!("{l}\n")).collect();
    *meta = out.into_bytes();
}

/// 删掉以 `prefix` 开头的整行（命中数必须恰好 1）。
fn drop_line(meta: &mut Vec<u8>, prefix: &str) {
    let text = text_of(meta);
    let hits = text.lines().filter(|l| l.starts_with(prefix)).count();
    assert_eq!(hits, 1, "{prefix:?} 应恰好一行（实得 {hits}）");
    let out: String = text.lines().filter(|l| !l.starts_with(prefix)).map(|l| format!("{l}\n")).collect();
    *meta = out.into_bytes();
}

/// 行内子串替换（原文里必须恰好命中一次）。
fn replace_once(meta: &mut Vec<u8>, find: &str, repl: &str) {
    let text = text_of(meta);
    let hits = text.matches(find).count();
    assert_eq!(hits, 1, "{find:?} 在 program.meta 里应恰好出现一次（实得 {hits}）");
    *meta = text.replacen(find, repl, 1).into_bytes();
}

/// 段计数字段加 `delta`（与 [insert_before] 配套做「计数与记录数自洽但键重复」的档）。
fn bump_count(meta: &mut Vec<u8>, prefix: &str, delta: i64) {
    let text = text_of(meta);
    let line = text
        .lines()
        .find(|l| l.starts_with(prefix))
        .unwrap_or_else(|| panic!("计数字段 {prefix:?} 找不到"))
        .to_string();
    let n: i64 = line[prefix.len()..].trim().parse().unwrap_or_else(|_| panic!("{prefix:?} 载荷不是整数"));
    replace_line(meta, prefix, &format!("{prefix}{}", n + delta));
}

/// 在锚点行之前插入一行（命中数必须恰好 1）。
fn insert_before(meta: &mut Vec<u8>, anchor_prefix: &str, line: &str) {
    let text = text_of(meta);
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
    *meta = out.into_bytes();
}

/// 取种子 `program.meta` 里以 `prefix` 开头的那一行（恰好一条）。
fn line_starting_with(meta: &[u8], prefix: &str) -> String {
    let text = text_of(meta);
    let mut hits = text.lines().filter(|l| l.starts_with(prefix));
    let line = hits.next().unwrap_or_else(|| panic!("种子里没有 {prefix:?} 行"));
    assert!(hits.next().is_none(), "种子里 {prefix:?} 应恰好一行");
    line.to_string()
}

/// `program-sha256` 的十六进制末位翻掉（容器字节不动 ⇒ 侧车与容器不再是一对）。
fn flip_meta_sha(meta: &mut Vec<u8>) {
    let line = line_starting_with(meta, "program-sha256 ");
    let sha = line["program-sha256 ".len()..].trim_matches('"').to_string();
    assert_eq!(sha.len(), 64, "program-sha256 应是 64 位十六进制，实得 {sha}");
    let last = sha.as_bytes()[63] as char;
    let repl: char = match last {
        '0' => '1',
        c if c.is_ascii_digit() => (c as u8 - 1) as char,
        'a' => 'b',
        c if (b'b'..=b'f').contains(&(c as u8)) => (c as u8 - 1) as char,
        c => panic!("末位不是十六进制字符：{c}"),
    };
    let flipped = format!("{}{repl}", &sha[..63]);
    replace_once(meta, &sha, &flipped);
}

/// 代表档清单：**六个判据族各若干档**（走一次 JVM 腿就收一族，逐档 hack 不收）。
fn cases() -> Vec<Case> {
    let mut v: Vec<Case> = Vec::new();
    let mut push = |id, family, desc, apply: fn(&mut Vec<u8>), expect_kind| {
        v.push(Case { id, family, desc, apply, expect_kind })
    };
    // ---- 控制 / 配对凭证（不空转锚）----
    push(CONTROL_ID, Family::Control, "四件套一字未改（两侧都 rc=0）", |_m| {}, None);
    push(
        SHA_ANCHOR_ID,
        Family::Pairing,
        "program-sha256 末位翻掉（META_STALE，两侧逐字同文）",
        flip_meta_sha,
        Some("META_STALE"),
    );
    push(SHA_DASH_ID, Family::Pairing, "program-sha256 换成裸短横线（写侧「无配对凭证」形态）", |m| {
        replace_line(m, "program-sha256 ", "program-sha256 -")
    }, Some("META_STALE"));

    // ---- 计数族（`rust-sidecar-fuzz` 读数 20 档）----
    push("mp-count-functions-minus1", Family::MetaCount, "functions 3→2（状态机在 global-dims 处撞见 function）", |m| {
        replace_line(m, "functions ", "functions 2")
    }, Some("META_UNREADABLE"));
    push("mp-count-labels-nonint", Family::MetaCount, "labels 计数改成非整数（段计数必须是整数）", |m| {
        replace_line(m, "labels ", "labels abc")
    }, Some("META_UNREADABLE"));
    push("mp-count-globaldims-neg", Family::MetaCount, "global-dims 计数为负", |m| {
        replace_line(m, "global-dims ", "global-dims -1")
    }, Some("META_UNREADABLE"));
    push("mp-count-files-huge", Family::MetaCount, "files 计数吹到 9999999（判界在分配前）", |m| {
        replace_line(m, "files ", "files 9999999")
    }, Some("META_UNREADABLE"));

    // ---- 记录族（30 档）----
    push("mp-record-func-extra-field", Family::MetaRecord, "function 记录尾部多一个裸记号", |m| {
        let line = line_starting_with(m, "function 0 ");
        replace_once(m, &line, &format!("{line} 9"))
    }, Some("META_UNREADABLE"));
    push("mp-record-func-flags-oob", Family::MetaRecord, "function flags 越界（999 > 127）", |m| {
        replace_once(m, "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 0", "function 0 1 \"SC_ALPHA\" \"SC_ALPHA\" 999")
    }, Some("META_UNREADABLE"));
    push("mp-record-const-dup-key", Family::MetaRecord, "constants 计数 +1 并重复整条 const（Map 段后者覆盖）", |m| {
        let line = line_starting_with(m, "const \"SC_G_CONST\"");
        bump_count(m, "constants ", 1);
        insert_before(m, "const \"SC_G_CONST\"", &line);
    }, Some("META_UNREADABLE"));
    push("mp-record-methodtype-badletter", Family::MetaRecord, "method-type 类型位 I→X（只认 I/S/U）", |m| {
        replace_line(m, "method-type ", "method-type \"SC_METH\" X")
    }, Some("META_UNREADABLE"));
    push("mp-record-dimconfig-two", Family::MetaRecord, "dim-config 载荷 0→2（CMP-82 后严卡 0/1）", |m| {
        replace_line(m, "dim-config ", "dim-config 2")
    }, Some("META_UNREADABLE"));

    // ---- 截尾族（8 档）----
    push("mp-trunc-head-only", Family::MetaTrunc, "只留头部三行（头层全绿，深判据撞在 labels）", |m| keep_lines(m, 3), Some("META_UNREADABLE"));
    push("mp-trunc-drop-end", Family::MetaTrunc, "删掉 end 行（侧车在 end 处提前结束）", |m| drop_line(m, "end"), Some("META_UNREADABLE"));
    push("mp-trunc-record-after-end", Family::MetaTrunc, "end 之后再追一条 macro 记录", |m| {
        let mut text = text_of(m);
        if !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str("macro \"SC_AFTER_END\" \"1\"\n");
        *m = text.into_bytes();
    }, Some("META_UNREADABLE"));
    push("mp-trunc-cut-midrecord-late", Family::MetaTrunc, "按字节砍在 private-dims 记录中途（头部三行完好）", |m| {
        let idx = m.windows(14).position(|w| w == b"private-dims 1").expect("种子里找 private-dims 计数行");
        m.truncate(idx + 40);
    }, Some("META_UNREADABLE"));

    // ---- 头部族里的 engine 两档（原 `HEAD_FAMILY_RUST_PASS` 的正主）----
    push("mp-head-engine-unquoted", Family::MetaHead, "engine 载荷去掉引号（头层只认关键字，深判据认载荷）", |m| {
        replace_line(m, "engine ", "engine erbcc 0.1.0-SNAPSHOT")
    }, Some("META_UNREADABLE"));
    push("mp-head-engine-extra-field", Family::MetaHead, "engine 记录尾部多一个裸记号", |m| {
        replace_line(m, "engine ", "engine \"erbcc 0.1.0-SNAPSHOT\" 9")
    }, Some("META_UNREADABLE"));

    // ---- 头层就判死的档（[腿真被跑了一次_计数断言] 用它证「不走腿」）----
    push("mp-head-empty", Family::MetaHead, "program.meta 清空（头层「侧车为空」直接拒，不走深判据腿）", |m| m.clear(), Some("META_UNREADABLE"));
    v
}

/// 深判据族（计数 / 记录 / 截尾 / 头部 engine）：这几族必须**由腿**判红。
fn is_deep_family(f: Family) -> bool {
    matches!(f, Family::MetaCount | Family::MetaRecord | Family::MetaHead | Family::MetaTrunc)
}

/// 深判据族里**由头层判死**的档：`program.meta` 连头部三行都读不出来，`freshness::read_meta_head`
/// 直接抛 ⇒ 按 Kotlin 的判定顺序（`ArtifactFreshness.kt:336-349`，`BootMetadata.read` 同一处抛）
/// 这条 `META_UNREADABLE` 与腿的原文逐字同文，而腿**一次都不跑**。
///
/// 它们是 [反向证明_恒放行替身下同一批断言必须红] 的天然例外（抽掉腿判据不变），所以这张表必须是
/// 封闭的并且被实测核对 —— 头层判界一旦被放宽，某档会从「头层判死」搬到「腿判死」，那格会立刻红。
/// 计数的独立自证在 [腿真被跑了一次_计数断言]②（这些档要求腿恰好 0 次）。
const HEAD_DECIDED_IDS: [&str; 1] = ["mp-head-empty"];

// ======================================================================
// 落盘 + 两腿
// ======================================================================

fn tool_dir() -> PathBuf {
    current_dir()
}

/// 真拷一份产物目录再改侧车（不能硬链接：改硬链接会串改种子原件）。
///
/// `tag` = 调用方的用例名，档位目录按它分格。本闸的用例是并发跑的（cargo 默认多线程），
/// 而下面的用例共用同一批 `mp-*` 档名：不分格时别的线程 `remove_dir_all` 会把本线程刚拷好的
/// `erbc.v1` 连父目录端走（实测报 `拷 program.erbc: NotFound`），更坏的一档是 JVM 腿读到半截
/// 目录 ⇒ 出一条不属于这档的读数。分格后两腿读的永远是自己的拷贝。
fn write_case(tag: &str, c: &Case) -> PathBuf {
    let dir = tool_dir().join("build/meta-parity/cases").join(tag).join(c.id);
    let _ = std::fs::remove_dir_all(&dir);
    let art = dir.join("erbc.v1");
    std::fs::create_dir_all(&art).expect("建档位目录");
    let seed = tool_dir().join(SEED_DIR_REL).join("erbc.v1");
    let seed_meta = std::fs::read(seed.join("program.meta")).expect("读种子 program.meta");
    std::fs::copy(seed.join("program.erbc"), art.join("program.erbc")).expect("拷 program.erbc");
    let mut meta = seed_meta.clone();
    (c.apply)(&mut meta);
    if c.family != Family::Control {
        assert_ne!(meta, seed_meta, "{}：配方没改到 program.meta（空改变 = 假绿）", c.id);
    }
    std::fs::write(art.join("program.meta"), &meta).expect("写变异后的 program.meta");
    std::fs::copy(seed.join("program.stmt.idx"), art.join("program.stmt.idx")).expect("拷 program.stmt.idx");
    std::fs::copy(seed.join("manifest.txt"), art.join("manifest.txt")).expect("拷 manifest.txt");
    dir
}

fn corpus_dir() -> PathBuf {
    tool_dir().join(CORPUS_REL)
}

/// 一条腿的结论：退出码 + 逐条 `KIND: detail` 原文（被检目录绝对路径折成 `<CASE>`）。
#[derive(Debug, Clone, PartialEq, Eq)]
struct Leg {
    rc: i32,
    issues: Vec<String>,
}

impl Leg {
    fn kinds(&self) -> Vec<String> {
        self.issues.iter().map(|s| s.split(':').next().unwrap_or("").trim().to_string()).collect()
    }

    fn joined(&self) -> String {
        if self.issues.is_empty() {
            "PASS".to_string()
        } else {
            self.issues.join(" | ")
        }
    }
}

/// 档位目录的绝对路径折成 `<CASE>`：三种分隔符拼法都要折（Java 的 `WindowsPath.toString()`
/// 恒出反斜杠，Rust 的 [PathBuf] 原样保留 —— 只折一种会留下「同判但分隔符不同」的假不同文）。
fn collapse(s: &str, case_dir: &Path) -> String {
    let raw = case_dir.to_string_lossy().into_owned();
    let mut out = s.to_string();
    for variant in [raw.clone(), raw.replace('\\', "/"), raw.replace('/', "\\")] {
        out = out.replace(variant.as_str(), "<CASE>");
    }
    out
}

/// **恒放行替身腿**（反向证明用）：`Ok(vec![])` = 「深判据没意见」。
/// 生产判据走 [erbcc_rs::freshness::check]（里面是真腿），本函数只接在注入位上。
fn stub_leg(out: &Path, corpus: &Path) -> Outcome<Vec<Issue>> {
    let _ = (out, corpus);
    Ok(Vec::new())
}

/// 腿 R：Rust 的 `erbcc check`。**注入位**在此：`leg = None` 走生产腿（真起 JVM），
/// `Some(&f)` 走替身（恒放行 / 计数）—— 反向证明与调用计数都靠它。
fn rust_leg(case_dir: &Path, leg: Option<&dyn Fn(&Path, &Path) -> Outcome<Vec<Issue>>>) -> Leg {
    let corpus = corpus_dir();
    let report = match leg {
        Some(l) => freshness::check_with_meta_leg(case_dir, &corpus, l),
        None => freshness::check(case_dir, &corpus),
    }
    .unwrap_or_else(|e| panic!("Rust check 腿本身失败：{e:?}"));
    Leg {
        rc: if report.ok { 0 } else { 1 },
        issues: report.issues.iter().map(|i| collapse(&i.to_string(), case_dir)).collect(),
    }
}

fn kotlin_classpath() -> Option<String> {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
            // 必须是**分号直串**：指到 classpath 文件会让 Kotlin 腿报「找不到或无法加载主类」
            // ⇒ 一片假红（CONTRACT §8 实测的坑），故这里显式把文件形态折成直串。
            if Path::new(t).is_file() {
                if let Ok(text) = std::fs::read_to_string(t) {
                    let jars: Vec<String> = text
                        .lines()
                        .flat_map(|l| l.split(';'))
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty())
                        .collect();
                    if !jars.is_empty() {
                        return Some(jars.join(if cfg!(windows) { ";" } else { ":" }));
                    }
                }
                return None;
            }
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

/// 腿 K：纯 Kotlin CLI（期望值的唯一来源）。`Main.kt:406-423` 的 `cmdCheck`
/// = `println(report.summary())` + `ok ? 0 : 1`；summary 首行 `<outDir>: …`，其后每条 issue 两空格前缀。
fn kotlin_leg(cp: &str, case_dir: &Path, corpus: &Path) -> Leg {
    let o = std::process::Command::new(java_exe())
        .args(["-Xmx6g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(cp)
        .arg("com.eratw.port.erbcc.MainKt")
        .args(["check", "--corpus"])
        .arg(corpus)
        .arg("--out")
        .arg(case_dir)
        .current_dir(tool_dir().join(".."))
        .output()
        .expect("起纯 Kotlin erbcc check 失败（先跑 ./gradlew --configure-on-demand :tools:erbcc:installDist）");
    let rc = o.status.code().unwrap_or(-1);
    let stdout = String::from_utf8_lossy(&o.stdout).into_owned();
    let mut issues: Vec<String> = Vec::new();
    for line in stdout.lines() {
        if line.starts_with("  ") {
            issues.push(collapse(line.trim_start(), case_dir));
        }
    }
    assert!(rc == 0 || rc == 1, "Kotlin 腿退出码不是 0/1（rc={rc}）：stdout={stdout} stderr={}", String::from_utf8_lossy(&o.stderr));
    if rc == 0 {
        assert!(issues.is_empty(), "Kotlin rc=0 却解析出 issue：{stdout}");
    } else {
        assert!(!issues.is_empty() && stdout.contains("条新鲜度问题"), "Kotlin rc={rc} 却没解析出 issue：{stdout}");
    }
    Leg { rc, issues }
}

fn kotlin_leg_at(cp: &str, case_dir: &Path) -> Leg {
    kotlin_leg(cp, case_dir, &corpus_dir())
}

// ======================================================================
// 判据函数（生产腿与替身腿共用同一个，反向证明才可比）
// ======================================================================

/// 一格的判据：`Err(原因)` = 「这格不通过」（= 真闸在这里会红），`Ok(说明)` = 「这格通过」。
///
/// 深判据族要求**两侧都拒、kind 序列相同、issue 原文逐字相同**，且拒因必须是期望的那个 kind；
/// 控制档要求两侧都放行且读数相同。
fn verdict_of(c: &Case, k: &Leg, r: &Leg) -> Result<String, String> {
    if c.family == Family::Control {
        if k.rc != 0 || r.rc != 0 {
            return Err(format!(
                "控制档两侧必须都放行：Kotlin rc={} {:?}｜Rust rc={} {:?}",
                k.rc, k.issues, r.rc, r.issues
            ));
        }
        if k != r {
            return Err(format!("控制档两侧读数不同：Kotlin {:?}｜Rust {:?}", k.issues, r.issues));
        }
        return Ok("两侧都放行（PASS）".to_string());
    }
    if k.rc == 0 {
        return Err(format!("{}：Kotlin 腿放行了（种子过期？两腿都无从判）：{:?}", c.id, k.issues));
    }
    if r.rc == 0 {
        return Err(format!(
            "{}（{}）：Rust 腿放行、Kotlin 腿拒 —— 本路的原虫还在：Kotlin {:?}",
            c.id, c.desc, k.issues
        ));
    }
    if k.kinds() != r.kinds() {
        return Err(format!("{}：两侧都拒但 kind 序列不同：Kotlin {:?}｜Rust {:?}", c.id, k.issues, r.issues));
    }
    if k != r {
        return Err(format!("{}：两侧都拒但文案不同：Kotlin {:?}｜Rust {:?}", c.id, k.issues, r.issues));
    }
    if let Some(want) = c.expect_kind {
        if !r.issues.iter().any(|s| s.starts_with(want)) {
            return Err(format!("{}：拒因不是 {want}：{:?}", c.id, r.issues));
        }
    }
    Ok(format!("两侧都拒且逐字同文（{}）", r.joined()))
}

/// 前置判据零出现（假一致防线）；配对凭证两档豁免 `META_STALE`。
fn assert_no_prelude(c: &Case, k: &Leg, r: &Leg) {
    let exempt = PAIRING_PROBE_IDS.contains(&c.id);
    for banned in BANNED_PRELUDE {
        if exempt && banned == "META_STALE" {
            continue;
        }
        for (side, issues) in [("kotlin", &k.issues), ("rust", &r.issues)] {
            assert!(
                !issues.iter().any(|s| s.starts_with(banned)),
                "{}：{side} 腿出现前置判据 {banned} —— 深判据的分歧会被它盖住（假一致）：{:?}",
                c.id,
                issues
            );
        }
    }
    if exempt {
        assert_eq!(k.issues.len(), 1, "{}：配对凭证档 Kotlin 应只有一条 issue：{:?}", c.id, k.issues);
        assert_eq!(r.issues.len(), 1, "{}：配对凭证档 Rust 应只有一条 issue：{:?}", c.id, r.issues);
    }
}

// ======================================================================
// 用例
// ======================================================================

#[test]
fn 族代表档_两侧逐字同判() {
    let cp = kotlin_classpath().expect(
        "缺纯 Kotlin 工具链：先跑 ./gradlew --configure-on-demand :tools:erbcc:installDist，\
         或把 ERBCC_CLASSPATH 设成 classpath 直串（不是文件路径）",
    );
    let mut lines: Vec<String> = Vec::new();
    for c in cases() {
        let dir = write_case("reps", &c);
        let k = kotlin_leg_at(&cp, &dir);
        let r = rust_leg(&dir, None);
        assert_no_prelude(&c, &k, &r);
        match verdict_of(&c, &k, &r) {
            Ok(why) => lines.push(format!("{} [{}] rc=K{}R{} {why}", c.id, c.family.tag(), k.rc, r.rc)),
            Err(why) => panic!("{why}"),
        }
    }
    assert!(lines.len() >= 18, "代表档只剩 {} 条，族面缩水了", lines.len());
    println!("\n=== 族代表档两侧对照（两腿现跑，逐字同文）===");
    for l in &lines {
        println!("{l}");
    }
}

#[test]
fn 控制档两侧都放行() {
    let cp = kotlin_classpath().expect("缺纯 Kotlin 工具链（同上）");
    let c = cases().into_iter().find(|x| x.id == CONTROL_ID).expect("控制档存在");
    let dir = write_case("control", &c);
    let k = kotlin_leg_at(&cp, &dir);
    let r = rust_leg(&dir, None);
    assert_eq!(k.rc, 0, "控制档 Kotlin 腿不绿（种子过期？按 rust-sidecar-fuzz/REPRODUCE.txt 重生成）：{:?}", k.issues);
    assert_eq!(
        r.rc, 0,
        "控制档 Rust 腿不绿 —— 本路把深判据接进来后把合法档判红了 = 过度判界（合法产物装不上同样是 bug）：{:?}",
        r.issues
    );
    assert_eq!(k.issues, r.issues, "控制档两侧读数不同");
    assert!(r.issues.is_empty(), "控制档不许有任何 issue：{:?}", r.issues);
}

#[test]
fn sha锚点两侧都红且逐字同文() {
    let cp = kotlin_classpath().expect("缺纯 Kotlin 工具链（同上）");
    let c = cases().into_iter().find(|x| x.id == SHA_ANCHOR_ID).expect("锚点档存在");
    let dir = write_case("sha", &c);
    let k = kotlin_leg_at(&cp, &dir);
    let r = rust_leg(&dir, None);
    assert_ne!(k.rc, 0, "sha 锚点 Kotlin 腿放行了（种子/容器摘要变了？）");
    assert_ne!(r.rc, 0, "sha 锚点 Rust 腿放行了（配对判据没了？）");
    assert_eq!(k.issues, r.issues, "sha 锚点两侧都红但不同文：Kotlin {:?}｜Rust {:?}", k.issues, r.issues);
    assert_eq!(k.issues.len(), 1, "sha 锚点应只有一条 issue：{:?}", k.issues);
    assert!(k.issues[0].starts_with("META_STALE: program.meta 记 "), "sha 锚点拒因不是 META_STALE：{:?}", k.issues);
    // 容器摘要本身（锚点只改侧车那一条，容器一个字节没动）：读数要还能对上，否则这格不叫锚点。
    let seed_container = tool_dir().join(SEED_DIR_REL).join("erbc.v1").join("program.erbc");
    let sha = sha256_hex(&std::fs::read(&seed_container).expect("读种子容器"));
    assert!(k.issues[0].contains(&sha), "锚点文案里的「容器摘要」应就是种子容器的 sha256 {sha}：{:?}", k.issues);
}

/// **反向证明**：把委托点打桩成恒放行，[verdict_of] 必须对 14 档深判据代表档全部给「不通过」。
///
/// 同一个判据函数、同一批档位、只换腿 —— 生产腿 [Ok]、恒放行替身 [Err]。
/// 若哪天有人把头层判界放宽到能吃下这些坏档（或把断言写成恒真），这格会立刻红。
///
/// 例外只有一类，且是**封闭清单**：[HEAD_DECIDED_IDS] 里的档按 Kotlin 的判定顺序
/// （`ArtifactFreshness.kt:336-349`：`BootMetadata.read` 在头层就抛 ⇒ 只报一条，配对判据与深判据都不跑）
/// 根本不经过腿，抽掉腿自然还是同判 —— 它们不属于「必须红」，但观测集与本清单必须完全相等，
/// 判据搬家时这格立刻红（同档的独立自证：[腿真被跑了一次_计数断言] 要求这些档腿恰好 0 次）。
#[test]
fn 反向证明_恒放行替身下同一批断言必须红() {
    let mut reds: Vec<String> = Vec::new();
    let mut greens: Vec<String> = Vec::new();
    let mut head_decided: Vec<String> = Vec::new();
    for c in cases() {
        if !is_deep_family(c.family) {
            continue;
        }
        let dir = write_case("reverse", &c);
        // ① 生产腿：这格必须过（= 真闸的绿）。
        let prod = rust_leg(&dir, None);
        let fake_kotlin = Leg { rc: 1, issues: prod.issues.clone() };
        match verdict_of(&c, &fake_kotlin, &prod) {
            Ok(_) => greens.push(c.id.to_string()),
            Err(why) => panic!("生产腿下 {} 就不该红：{why}", c.id),
        }
        // ② 恒放行替身：由腿判死的档同一格必须红（= 把腿抽掉后本闸立刻不绿）。
        let stubbed = rust_leg(&dir, Some(&stub_leg));
        match verdict_of(&c, &fake_kotlin, &stubbed) {
            Err(why) => reds.push(format!("{} ⇒ 红（预期）：{why}", c.id)),
            Ok(why) => {
                assert!(
                    HEAD_DECIDED_IDS.contains(&c.id),
                    "反向证明失败：{} 把委托点打桩成恒放行，判据却仍然通过（{why}）—— \
                     这档的拒因不在腿上。若拒因真的搬回了头层（头层判界变宽），要显式加进 \
                     HEAD_DECIDED_IDS 并同步 [腿真被跑了一次_计数断言] 的「腿 0 次」断言，\
                     不许在这里默认放行",
                    c.id
                );
                head_decided.push(c.id.to_string());
            }
        }
    }
    let want_head: Vec<String> = HEAD_DECIDED_IDS.iter().map(|s| s.to_string()).collect();
    assert_eq!(
        head_decided, want_head,
        "实测的头层判死集与 HEAD_DECIDED_IDS 不等（判据搬家）：实测 {head_decided:?}｜清单 {want_head:?}"
    );
    assert_eq!(
        reds.len() + head_decided.len(),
        greens.len(),
        "「抽腿必红」+「头层判死」与生产腿绿的档数不等（有档既不经腿也不在清单里？）"
    );
    assert!(reds.len() >= 14, "反向证明只覆盖 {} 档（深判据代表档缩水）", reds.len());
    println!("\n=== 反向证明：恒放行替身下必须红的 {} 档（另 {} 档头层判死、不经腿）===", reds.len(), head_decided.len());
    for l in &reds {
        println!("{l}");
    }
    for id in &head_decided {
        println!("{id} ⇒ 头层判死（腿不参与，见 HEAD_DECIDED_IDS）");
    }
}

/// 腿**真被跑了**的计数断言：头层全绿的档每次 `check` 恰好走一次 JVM 腿；
/// 头层已判死的档一次都不走（Kotlin 也只报一条 `META_UNREADABLE`，走腿只会把同一条拒因换措辞）。
#[test]
fn 腿真被跑了一次_计数断言() {
    let hits = Arc::new(AtomicUsize::new(0));
    let counter = {
        let hits = Arc::clone(&hits);
        move |out: &Path, corpus: &Path| -> Outcome<Vec<Issue>> {
            hits.fetch_add(1, Ordering::SeqCst);
            freshness::meta_leg_via_jvm(out, corpus)
        }
    };
    // ① 头层全绿 ⇒ 腿恰好跑 1 次，且判定与生产腿逐字相同（计数包装不许改判）。
    for id in [CONTROL_ID, "mp-count-functions-minus1", "mp-trunc-head-only", "mp-head-engine-unquoted"] {
        let c = cases().into_iter().find(|x| x.id == id).expect("档存在");
        let dir = write_case("count", &c);
        hits.store(0, Ordering::SeqCst);
        let counted = rust_leg(&dir, Some(&counter));
        assert_eq!(
            hits.load(Ordering::SeqCst),
            1,
            "{id}：深判据腿被跑的次数不是 1（被短路了？还是每次 check 跑了好几遍？）"
        );
        let prod = rust_leg(&dir, None);
        assert_eq!(counted, prod, "{id}：计数包装后的判定与生产腿不同（委托点接错线？）");
    }
    // ② 头层就判死的档（清空 program.meta ⇒ 「侧车为空」）⇒ 腿一次都不跑。
    let dead = cases().into_iter().find(|x| x.id == "mp-head-empty").expect("档存在");
    let dir = write_case("count", &dead);
    hits.store(0, Ordering::SeqCst);
    let counted = rust_leg(&dir, Some(&counter));
    assert_eq!(counted.rc, 1, "清空侧车必须拒：{:?}", counted.issues);
    assert!(counted.issues[0].starts_with("META_UNREADABLE"), "清空侧车的拒因不是 META_UNREADABLE：{:?}", counted.issues);
    assert_eq!(
        hits.load(Ordering::SeqCst),
        0,
        "头层已判死的档不该再走深判据腿（Kotlin 的 read 也在这里抛，只报一条）"
    );
}

/// 对照表里那 60 档（[META_DEEP_IDS]）逐档两侧同判、且**逐字同文**。
///
/// 表是 `sidecar_fuzz_diff dump_verdicts` 现跑重生成的（两列都真跑，禁手抄）；本用例只读表，
/// 不再起 110 × 2 条进程 —— 现跑的抽样在 [族代表档_两侧逐字同判] 与 [tsv_抽样档_现跑复验两腿]。
#[test]
fn 六十档_tsv_逐档两侧同判且逐字同文() {
    let rows = table();
    for id in META_DEEP_IDS {
        let r = row_for(&rows, id);
        assert_ne!(r.kotlin_rc, 0, "{id}：Kotlin 腿放行了（对照表过期或种子变了）");
        assert_ne!(r.rust_rc, 0, "{id}：Rust 腿还在放行 —— 本路没修好这档");
        assert_eq!(
            r.kotlin_issues, r.rust_issues,
            "{id}：两侧都拒但读数不同文（Kotlin {:?}｜Rust {:?}）",
            r.kotlin_issues, r.rust_issues
        );
        assert_eq!(r.verdict, "BOTH_REJECT_VERBATIM", "{id}：判定列不是 BOTH_REJECT_VERBATIM：{r:?}");
        assert!(
            r.rust_issues.iter().any(|s| s.starts_with("META_UNREADABLE")),
            "{id}：拒因不是 META_UNREADABLE：{:?}",
            r.rust_issues
        );
    }
    // 账清到底：全表不许再有一格 KOTLIN_REJECT_RUST_PASS / 反向分歧。
    let left: Vec<&str> = rows.iter().filter(|x| x.verdict == "KOTLIN_REJECT_RUST_PASS").map(|x| x.id.as_str()).collect();
    assert!(left.is_empty(), "对照表里还有 {} 档 Kotlin 拒/Rust 放行（本路的账没清完）：{left:?}", left.len());
    let reverse: Vec<&str> = rows.iter().filter(|x| x.verdict == "RUST_REJECT_KOTLIN_PASS").map(|x| x.id.as_str()).collect();
    assert!(reverse.is_empty(), "出现反向分歧（Rust 比 Kotlin 严）：{reverse:?}");
}

/// 对照表的抽样现跑：本闸同族同判据的代表档与表里那格的 **kind 必须一致**
/// —— 两闸写的是同一批档面，漂了就说明有一边在自创口径。
#[test]
fn tsv_抽样档_现跑复验两腿() {
    let cp = kotlin_classpath().expect("缺纯 Kotlin 工具链（同上）");
    let rows = table();
    let sample = [
        ("sc-control", CONTROL_ID),
        ("sc-sha-flip", SHA_ANCHOR_ID),
        ("sc-functions-minus1", "mp-count-functions-minus1"),
        ("sc-const-dup-key", "mp-record-const-dup-key"),
        ("sc-dimconfig-two", "mp-record-dimconfig-two"),
        ("sc-head-only", "mp-trunc-head-only"),
        ("sc-engine-unquoted", "mp-head-engine-unquoted"),
        ("sc-engine-extra-field", "mp-head-engine-extra-field"),
    ];
    for (table_id, local_id) in sample {
        let want = row_for(&rows, table_id);
        let c = cases().into_iter().find(|x| x.id == local_id).expect("本闸同族替身档存在");
        let dir = write_case("sample", &c);
        let k = kotlin_leg_at(&cp, &dir);
        let r = rust_leg(&dir, None);
        assert_no_prelude(&c, &k, &r);
        assert!(verdict_of(&c, &k, &r).is_ok(), "抽样现跑不通过：{}", verdict_of(&c, &k, &r).unwrap_err());
        assert_eq!(
            k.kinds(),
            want.kotlin_issues.iter().map(|s| s.split(':').next().unwrap_or("").trim().to_string()).collect::<Vec<_>>(),
            "{table_id}：本闸替身档 {local_id} 的 Kotlin 现跑 kind 与对照表不一致（本闸 {:?}｜表 {:?}）",
            k.kinds(),
            want.kotlin_issues
        );
        assert_eq!(
            k.kinds(),
            r.kinds(),
            "{table_id}：本闸替身档 {local_id} 的两侧 kind 不一致（腿没接上？）"
        );
    }
}

#[test]
fn 不空转_前置判据零出现_读表两列() {
    let rows = table();
    for id in META_DEEP_IDS {
        let r = row_for(&rows, id);
        for banned in BANNED_PRELUDE {
            for (side, issues) in [("kotlin", &r.kotlin_issues), ("rust", &r.rust_issues)] {
                assert!(
                    !issues.iter().any(|s| s.starts_with(banned)),
                    "{id}：{side} 腿出现前置判据 {banned} —— 深判据的分歧会被它盖住（假一致）：{:?}",
                    issues
                );
            }
        }
    }
}

/// 委托腿的**判据族归族**读数：表里 60 档的 kind 必须全在 `META_UNREADABLE` 一条上
/// （`META_STALE` 是配对判据、头层本来就管；`SIDECAR_*` / `CONTAINER_*` 出现在这批档里
/// 就说明「修 meta 却顺手收紧了别的层」）。
#[test]
fn 六十档_拒因族读数() {
    let rows = table();
    let mut seen: std::collections::BTreeMap<String, usize> = std::collections::BTreeMap::new();
    for id in META_DEEP_IDS {
        let r = row_for(&rows, id);
        assert_eq!(r.rust_issues.len(), 1, "{id}：修完之后应当只有一条深判据 issue：{:?}", r.rust_issues);
        *seen.entry(r.rust_issues[0].split(':').next().unwrap().trim().to_string()).or_default() += 1;
    }
    assert_eq!(seen.len(), 1, "60 档的 kind 不止一种（说明有档被别的判据抢先拒了）：{seen:?}");
    assert_eq!(seen.get("META_UNREADABLE"), Some(&60), "60 档不全在 META_UNREADABLE 上：{seen:?}");
    println!("六十档拒因：{seen:?}");
}

/// 代价读数必须在册且两档齐（第 4 项任务的落档；读数本身由 [dump_cost_readings] 现跑产出）。
#[test]
fn 代价读数在册() {
    let path = tool_dir().join(COST_READING_REL);
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!(
            "缺代价读数 {path:?}（{e}）—— 跑 \
             `cargo test --test meta_deep_parity dump_cost_readings -- --ignored --nocapture` 现产后落盘"
        )
    });
    for want in ["小语料档", "全量语料档", "Kotlin 腿", "Rust 腿（含委托 JVM 的深判据）", "Rust 腿（恒放行替身）"] {
        assert!(text.contains(want), "代价读数缺「{want}」这一档/这一列：{path:?}");
    }
    assert!(text.contains("WALL_MS"), "代价读数里没有墙钟读数：{path:?}");
}

// ======================================================================
// 代价读数（本路第 4 项任务）：现跑打印，落 tests/data/rust-meta-parity/cost-readings.txt
// ======================================================================

/// `cargo test --test meta_deep_parity dump_cost_readings -- --ignored --nocapture`
/// 量三件事 × 两档语料：Kotlin 腿墙钟、Rust 腿**含**委托腿的墙钟、Rust 腿**不含**委托腿
/// （恒放行替身）的墙钟 ⇒ 差值就是「深判据委托」给 `check` 加的开销。
///
/// 峰值内存由 `build/meta-parity/measure.ps1`（PowerShell 轮询 `PeakWorkingSet64`）量外层次，
/// 读数一并写进那份 readings —— Rust 侧在这里起的是**孙进程**，`std::process` 量不到它的峰值，
/// 只能拿同形状的独立 Kotlin 进程当上界（口径写在 readings 文件头）。
#[test]
#[ignore = "量代价才跑：cargo test --test meta_deep_parity dump_cost_readings -- --ignored --nocapture"]
fn dump_cost_readings() {
    let cp = kotlin_classpath().expect("缺纯 Kotlin 工具链（先 :tools:erbcc:installDist）");
    println!("# 小语料档（{} + seed 产物）", CORPUS_REL);
    let control = cases().into_iter().find(|x| x.id == CONTROL_ID).expect("控制档存在");
    let dir = write_case("cost", &control);
    time_all(&cp, &dir, "小语料档");
    for id in ["mp-count-functions-minus1", "mp-trunc-head-only"] {
        let c = cases().into_iter().find(|x| x.id == id).expect("档存在");
        time_all(&cp, &write_case("cost", &c), &format!("小语料档·深判据拒档 {id}"));
    }

    let full_out = std::env::var("ERBCC_RS_FULL_BASELINE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| tool_dir().join("build/baseline/full"));
    let manifest = full_out.join("erbc.v1").join("manifest.txt");
    if !manifest.is_file() {
        println!("# 全量语料档：缺 {manifest:?}（跳过）—— 生成见 tests/freshness_parity.rs 的第二读数注释");
        return;
    }
    println!("# 全量语料档（build/baseline/full + manifest 登记的 corpus-root）");
    time_full(&cp, &full_out, &manifest);
}

fn time_all(cp: &str, dir: &Path, label: &str) {
    let corpus = corpus_dir();
    let t0 = std::time::Instant::now();
    let k = kotlin_leg(cp, dir, &corpus);
    let kotlin_ms = t0.elapsed().as_millis();
    let t1 = std::time::Instant::now();
    let r_full = freshness::check(dir, &corpus).unwrap_or_else(|e| panic!("Rust 腿报红：{e:?}"));
    let rust_full_ms = t1.elapsed().as_millis();
    let t2 = std::time::Instant::now();
    let r_stub = freshness::check_with_meta_leg(dir, &corpus, &stub_leg).unwrap();
    let rust_stub_ms = t2.elapsed().as_millis();
    println!(
        "{label}\tKotlin 腿 rc={} WALL_MS={kotlin_ms}\tRust 腿（含委托 JVM 的深判据）rc={} WALL_MS={rust_full_ms}\tRust 腿（恒放行替身）rc={} WALL_MS={rust_stub_ms}\t委托净增 {} ms",
        k.rc,
        if r_full.ok { 0 } else { 1 },
        if r_stub.ok { 0 } else { 1 },
        rust_full_ms.saturating_sub(rust_stub_ms)
    );
}

fn time_full(cp: &str, dir: &Path, manifest: &Path) {
    let text = std::fs::read_to_string(manifest).expect("读全量清单");
    let corpus_root = text
        .lines()
        .find_map(|l| l.strip_prefix("corpus-root "))
        .expect("全量清单必须有 corpus-root 行")
        .trim()
        .to_string();
    let corpus = PathBuf::from(&corpus_root);
    if !corpus.is_dir() {
        println!("全量语料档：语料根不存在 {corpus_root}（跳过）");
        return;
    }
    let t0 = std::time::Instant::now();
    let k = kotlin_leg(cp, dir, &corpus);
    let kotlin_ms = t0.elapsed().as_millis();
    let t1 = std::time::Instant::now();
    let r_full = freshness::check(dir, &corpus).unwrap_or_else(|e| panic!("Rust 腿（全量）报红：{e:?}"));
    let rust_full_ms = t1.elapsed().as_millis();
    let t2 = std::time::Instant::now();
    let r_stub = freshness::check_with_meta_leg(dir, &corpus, &stub_leg).unwrap();
    let rust_stub_ms = t2.elapsed().as_millis();
    println!(
        "全量语料档\tKotlin 腿 rc={} WALL_MS={kotlin_ms}\tRust 腿（含委托 JVM 的深判据）rc={} WALL_MS={rust_full_ms}\tRust 腿（恒放行替身）rc={} WALL_MS={rust_stub_ms}\t委托净增 {} ms",
        k.rc,
        if r_full.ok { 0 } else { 1 },
        if r_stub.ok { 0 } else { 1 },
        rust_full_ms.saturating_sub(rust_stub_ms)
    );
}

// ======================================================================
// 对照表读取（只读；重生成走 sidecar_fuzz_diff 的 dump 腿）
// ======================================================================

#[derive(Debug, Clone)]
struct Row {
    id: String,
    kotlin_rc: i32,
    kotlin_issues: Vec<String>,
    rust_rc: i32,
    rust_issues: Vec<String>,
    verdict: String,
}

fn table() -> Vec<Row> {
    let text = std::fs::read_to_string(tool_dir().join(TABLE_REL))
        .unwrap_or_else(|e| panic!("缺对照表 {:?}（{e}）—— 跑 dump_verdicts 重生成", tool_dir().join(TABLE_REL)));
    let mut out = Vec::new();
    for line in text.lines() {
        if line.starts_with('#') || line.trim().is_empty() {
            continue;
        }
        let c: Vec<&str> = line.split('\t').collect();
        assert!(c.len() >= 8, "对照表列数不对：{line}");
        out.push(Row {
            id: c[0].to_string(),
            kotlin_rc: c[3].parse().unwrap_or_else(|_| panic!("kotlin_rc 不是数字：{line}")),
            kotlin_issues: split_issues(c[4]),
            rust_rc: c[5].parse().unwrap_or_else(|_| panic!("rust_rc 不是数字：{line}")),
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

/// 两侧同拒时分三档（与 `sidecar_fuzz_diff.rs::classify` 同一形状，只用来查表自身自洽）。
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

/// 表自身的自洽检查（判定列与两列读数必须互推得出）—— 免得「表是手抄的」蒙过 [六十档_tsv_逐档两侧同判且逐字同文]。
#[test]
fn tsv_判定列与两列读数自洽() {
    let rows = table();
    assert!(rows.len() >= 110, "对照表档位数 {} 少于 110（表被截断？）", rows.len());
    for r in &rows {
        let k = Leg { rc: r.kotlin_rc, issues: r.kotlin_issues.clone() };
        let got = Leg { rc: r.rust_rc, issues: r.rust_issues.clone() };
        assert_eq!(classify(&k, &got), r.verdict.as_str(), "{}：判定列与两列读数不自洽", r.id);
    }
}
