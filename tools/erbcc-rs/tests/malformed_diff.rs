//! RUST1 / rust-fuzz-diff 车道闸：**畸形 `.erbc` 的两侧判界对照**（主动挖虫，不是加功能）。
//!
//! 同一个容器头/段表现在有**三个实现方**在判：
//! * Rust：`src/container.rs`（rust-container 路收成的单源），消费方 `freshness::check`（= `erbcc check`）
//!   与 `incremental::container_regions`（区间消费方）；
//! * Kotlin：`core/loader/MappedErbc.kt`（头/段表，经 `erbcc check` 走到）+
//!   `ProgramLoader.validateSections` / `MappedErbc.checkSectionLayout`（段布局，装载链）+
//!   `ProgramLoader.loadFile`（头 + 段布局 + `ErbcReader.decode` 全量装载）；
//! * 第三实现方 `app/build.gradle.kts` 的 `verifyErbcFreshness` —— 本闸**不判它**（不许跑 `:app` 任务），
//!   只读它的判据并把结论写进交付报告。
//!
//! **两侧对同一份畸形产物一个拒一个放行 = 规约 §1.6「禁止静默降级」真的发生了**。
//! 权威口径：`specs/erbc-format.md` §1.5（尾部多余字节**允许**）/ §1.6 / §2（含「装载层再加一条：
//! 偏移必须 `>= 10 + 4×sectionCount`」）/ §3（标志位与段表双向自洽 + 缺段仍需该段）。
//!
//! ## 与 `container_sections.rs` 的分工
//!
//! 那条闸用**合成字节**钉 `container::parse` 自身的八条判界；本闸用**真产物**
//! （`tests/data/rust-manifest/erbc.v1/program.erbc`，950 B / v2 / 9 槽）做种子逐字段变异，
//! 每档同时取三腿读数并把 Kotlin 侧结论钉成基准：
//! 1. `container::parse(bytes)` —— Rust 头层判界；
//! 2. `freshness::check(out, corpus)` —— Rust 的 `erbcc check` 腿（与 Kotlin CLI 同判据同文案）；
//! 3. `incremental::container_regions(path)` —— Rust 的**区间消费方**（本路唯一的真虫捕手，见下）。
//!
//! ## 为什么变异体要把 `manifest.txt` / `program.meta` 一起同步（本路最大的假一致坑）
//!
//! `check` 在容器判据**之前**还有 `compiler-digest`、`ARTIFACT_SIZE`、`ARTIFACT_SHA` 三道前置判据；
//! 变异体若不改这些，两腿会各自先在前置判据上红，容器判据的分歧就被完全掩盖（假一致）。
//! 故每档都把清单里 `artifact erbc.v1/program.erbc` 的**字节数与 SHA-256 改成变异体的真值**，
//! 并把 `program.meta` 的 `program-sha256` 同步成同一摘要 —— 于是 `ARTIFACT_SIZE` /
//! `ARTIFACT_SHA` / `META_STALE` / `SOURCE_*` / `COMPILER_STALE` 都不触发，
//! 剩下的 `CONTAINER_INVALID` 有无与文本就是**纯容器判据**的读数。
//! 变异体本身（畸形档）**不入库**，只入「两侧结论对照表」`tests/data/rust-fuzz-diff/kotlin-verdicts.tsv`
//! —— 里面每一行都是真跑 Kotlin 腿的原始读数（生成命令见 [dump_verdicts]，禁手抄）。
//!
//! ## 已知分歧账（**已由 rust-container-layout 清账**）
//!
//! `container::parse` 只做到规约 §2 的头层判界（Kotlin `MappedErbc.open` 同层，两腿逐字一致）；
//! 规约 §2 明写的**装载层再加一条**（偏移 `>= 10 + 4×sectionCount`、两段不得同偏移）与 §3 的
//! 「段声明需要 N 字节但只剩 M<N」原先在 Rust 侧**没有对应判据**：`incremental::container_regions`
//! 对这 5 档照样算出区间、照抄 sha256（静默降级），而 Kotlin 装载链一律 `ErbcDecodeException`。
//! 现在那三条判据做进了 `container::validate_section_layout`（= Kotlin `validateSections` 同本体），
//! 区间消费方先过这道闸 ⇒ 5 档账清零，逐档两侧原文见 [LAYOUT_REJECTED_BOTH_SIDES]。
//! 另有两档（`off-eq-10` / `off-out-of-order`）原先是「结论对、理由错」——Rust 只被
//! `section_range` 的「下一存在段偏移更早」顺带撞到；判据本体实现出来后，这两档与那 5 档
//! 一起升级成**两侧文案逐字相同**（同一条 [LAYOUT_REJECTED_BOTH_SIDES]，不再是「只对照不断言」）。

use std::path::{Path, PathBuf};

use erbcc_rs::container;
use erbcc_rs::freshness;
use erbcc_rs::incremental;
use erbcc_rs::types::{current_dir, sha256_hex, ErbccError};

/// 种子容器（真产物，950 B / v2 / 9 槽；配套语料让 `check` 的源 sha 判据本来就是绿的）。
const SEED_REL: &str = "tests/data/rust-manifest/erbc.v1/program.erbc";
const SEED_MANIFEST_REL: &str = "tests/data/rust-manifest/erbc.v1/manifest.txt";
const SEED_META_REL: &str = "tests/data/rust-manifest/erbc.v1/program.meta";
const SEED_SIDECAR_REL: &str = "tests/data/rust-manifest/erbc.v1/program.stmt.idx";
const CORPUS_REL: &str = "tests/data/rust-manifest/corpus";
/// 两侧结论对照表（Kotlin 真跑读数；变异体不入库，只入这张表）。
const VERDICTS_REL: &str = "tests/data/rust-fuzz-diff/kotlin-verdicts.tsv";

fn repo_tool_dir() -> PathBuf {
    current_dir()
}

fn read(p: &Path) -> Vec<u8> {
    std::fs::read(p).unwrap_or_else(|e| panic!("读 {p:?} 失败：{e}"))
}

fn seed_bytes() -> Vec<u8> {
    read(&repo_tool_dir().join(SEED_REL))
}

// ======================================================================
// 变异面（契约的 1–8 类，每类至少一档）
// ======================================================================

struct Mutant {
    id: &'static str,
    desc: &'static str,
    bytes: Vec<u8>,
}

fn put_u16(b: &mut [u8], at: usize, v: u16) {
    b[at..at + 2].copy_from_slice(&v.to_le_bytes());
}

fn put_u32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

/// 段表槽 `section` 的绝对偏移（段表首 = 10，每槽 4 字节，规约 §2）。
fn set_offset(b: &mut [u8], section: usize, v: u32) {
    put_u32(b, 10 + section * 4, v);
}

fn with_head(mut f: impl FnMut(&mut [u8])) -> Vec<u8> {
    let mut b = seed_bytes();
    f(&mut b);
    b
}

fn mutants() -> Vec<Mutant> {
    let seed = seed_bytes();
    assert_eq!(seed.len(), 950, "种子容器必须 950 字节（对照表的字节基准）");
    let mut out: Vec<Mutant> = Vec::new();
    let mut push = |id: &'static str, desc: &'static str, bytes: Vec<u8>| {
        out.push(Mutant { id, desc, bytes })
    };

    // ---- 0 对照组 ----
    push("good-seed", "种子原样 950 字节（三腿都必须放行）", seed.clone());

    // ---- 1 magic ----
    push(
        "magic-bit-flip",
        "magic 首字节 0x45 改一位成 0x44（ERBC→DRBC）",
        with_head(|b| b[0] ^= 0x01),
    );
    push(
        "magic-zero",
        "magic 四字节全部清零",
        with_head(|b| b[0..4].copy_from_slice(&[0, 0, 0, 0])),
    );

    // ---- 2 version ----
    push("version-0", "version = 0", with_head(|b| put_u16(b, 4, 0)));
    push(
        "version-3",
        "version = 3（§8.1 只认 1/2，不做向前兼容解释）",
        with_head(|b| put_u16(b, 4, 3)),
    );
    push(
        "version-ffff",
        "version = 0xFFFF",
        with_head(|b| put_u16(b, 4, 0xFFFF)),
    );

    // ---- 3 sectionCount ----
    push(
        "count-7-on-v2",
        "v2 头配 sectionCount=7（§2 明令必须当场拒）",
        with_head(|b| put_u16(b, 8, 7)),
    );
    push(
        "count-9-on-v1",
        "v1 头配 sectionCount=9",
        with_head(|b| {
            put_u16(b, 4, 1);
            put_u16(b, 8, 9);
        }),
    );
    push("count-0", "sectionCount = 0", with_head(|b| put_u16(b, 8, 0)));
    push(
        "count-100",
        "sectionCount = 100（段表要 410 字节，头最多读 46 字节）",
        with_head(|b| put_u16(b, 8, 100)),
    );

    // ---- 4 flags ----
    push(
        "flags-undef-20",
        "v2 flags = 0x0020（该版本未定义位）",
        with_head(|b| put_u16(b, 6, 0x0020)),
    );
    push(
        "flags-undef-25",
        "v2 flags = 0x0025（未定义位 0x20 + 已定义 0x01/0x04）",
        with_head(|b| put_u16(b, 6, 0x0025)),
    );
    push(
        "flags-bit-no-offset",
        "flags |= 0x08(HAS_CALL_ARGS) 但段 7 偏移仍 0（置位却缺段）",
        with_head(|b| put_u16(b, 6, 0x0005 | 0x0008)),
    );
    push(
        "flags-expr-no-offset",
        "flags |= 0x02(HAS_EXPRESSIONS) 但段 2 偏移仍 0",
        with_head(|b| put_u16(b, 6, 0x0005 | 0x0002)),
    );
    push(
        "flags-offset-no-bit",
        "段 3 偏移=46 仍在却清掉 0x04(HAS_STATEMENTS)（有段却未声明，反向）",
        with_head(|b| put_u16(b, 6, 0x0001)),
    );
    push(
        "v1-flags-undef-08",
        "v1 头（version=1 / count=7）flags 含 v1 未定义位 0x08（= 0xd）",
        with_head(|b| {
            put_u16(b, 4, 1);
            put_u16(b, 8, 7);
            put_u16(b, 6, 0x0005 | 0x0008);
        }),
    );
    push(
        "v1-shadow-7slots",
        "version=1 + sectionCount=7、段表其余原样（§8.2 合法的 v1 视图，两侧都必须放行）",
        with_head(|b| {
            put_u16(b, 4, 1);
            put_u16(b, 8, 7);
        }),
    );

    // ---- 5 段偏移 ----
    push("off-eq-10", "CODE（段 5）偏移改成 10（= 头字节数，落在段表区内）", with_head(|b| set_offset(b, 5, 10)));
    push("off-lt-10", "CODE 段偏移改成 9（< 10）", with_head(|b| set_offset(b, 5, 9)));
    push("off-eq-len", "CODE 段偏移 = 文件长度 950", with_head(|b| set_offset(b, 5, 950)));
    push("off-gt-len", "CODE 段偏移 = 951（> 文件长度）", with_head(|b| set_offset(b, 5, 951)));
    push(
        "off-max-u32",
        "CODE 段偏移 = 0xFFFFFFFF（按 i32 读成负数）",
        with_head(|b| set_offset(b, 5, 0xFFFF_FFFF)),
    );
    push(
        "off-dup",
        "FUNCTION_TABLE（段 4）偏移改成 46 = STATEMENT_POOL（段 3）偏移（两段同偏移）",
        with_head(|b| set_offset(b, 4, 46)),
    );
    push(
        "off-out-of-order",
        "FUNCTION_TABLE 偏移改成 800，晚于 CODE（段 5）=778（段号升序但偏移乱序）",
        with_head(|b| set_offset(b, 4, 800)),
    );

    // ---- 6 末段之后截断（段声明需要 N 字节但只剩 M<N，§3 的「缺段却仍需该段」）----
    push("trunc-940", "整文件截到 940 字节：LINE_TABLE(910) 只剩 30 字节，声明需 8+32=40", seed[..940].to_vec());
    push("trunc-914", "整文件截到 914 字节：LINE_TABLE(910) 只剩 4 字节，读 lineCount 的 u32 直接越尾", seed[..914].to_vec());
    push("trunc-910", "整文件截到 910 字节：LINE_TABLE 偏移 == 文件长度（头层就该拒）", seed[..910].to_vec());
    push(
        "code-count-lie",
        "CODE 段首的 instructionCount 由 8 改成 100（声明 1600 字节、区间只有 132）",
        with_head(|b| put_u32(b, 778, 100)),
    );
    push(
        "line-count-lie",
        "LINE_TABLE 的 lineCount 由 8 改成 100（既与 CODE 的 8 条不符、也超区间）",
        with_head(|b| put_u32(b, 914, 100)),
    );

    // ---- 7 尾部多余字节（§1.5 允许：谁拒了谁就是 bug）----
    push("append-7", "文件尾追加 7 个 0x00（§1.5：reader 只按段表寻址，不校验尾部长度）", {
        let mut v = seed.clone();
        v.extend_from_slice(&[0u8; 7]);
        v
    });
    push("append-junk", "文件尾追加 13 个非零垃圾字节", {
        let mut v = seed.clone();
        v.extend_from_slice(&(0x41u8..0x4E).collect::<Vec<u8>>());
        v
    });

    // ---- 8 整文件退化 ----
    push("tiny-3", "整文件只留前 3 字节（连文件头长度都不够）", seed[..3].to_vec());
    push("empty", "空文件（0 字节）", Vec::new());

    out
}

// ======================================================================
// 变异体落盘：build/fuzz-diff/mut-rs/<id>/erbc.v1/ 四件套（畸形档一律不入库）
// ======================================================================

fn case_dir(tag: &str, id: &str) -> PathBuf {
    current_dir()
        .join("build/fuzz-diff/mut-rs")
        .join(tag)
        .join(id)
}

/// 写变异体并同步 `manifest.txt` 的 artifact 行 + `program.meta` 的 program-sha256，
/// 让 `ARTIFACT_SIZE` / `ARTIFACT_SHA` / `META_STALE` 这些前置判据都不触发。
/// `tag` 按用例分目录，避免同一档位被并行用例同时重写（各用例内容逐字节相同，只是不互相踩）。
fn write_case(tag: &str, m: &Mutant) -> PathBuf {
    let dir = case_dir(tag, m.id);
    let _ = std::fs::remove_dir_all(&dir);
    let art = dir.join("erbc.v1");
    std::fs::create_dir_all(&art).expect("建变异体目录");
    let erbc = art.join("program.erbc");
    std::fs::write(&erbc, &m.bytes).expect("写 program.erbc");
    std::fs::copy(
        repo_tool_dir().join(SEED_SIDECAR_REL),
        art.join("program.stmt.idx"),
    )
    .expect("复制 program.stmt.idx");

    let sha = sha256_hex(&m.bytes);
    let seed_sha = sha256_hex(&seed_bytes());
    let meta = read(&repo_tool_dir().join(SEED_META_REL));
    let meta_text = String::from_utf8_lossy(&meta).into_owned();
    assert_eq!(
        meta_text.matches(seed_sha.as_str()).count(),
        1,
        "program.meta 里的容器摘要应恰好出现一次"
    );
    std::fs::write(
        art.join("program.meta"),
        meta_text.replace(seed_sha.as_str(), sha.as_str()),
    )
    .expect("写同步后的 program.meta");

    let manifest = std::fs::read_to_string(repo_tool_dir().join(SEED_MANIFEST_REL))
        .expect("读种子 manifest.txt");
    let mut hit = 0usize;
    let synced = manifest
        .split_inclusive('\n')
        .map(|line| {
            if line.starts_with("artifact erbc.v1/program.erbc ") {
                hit += 1;
                format!("artifact erbc.v1/program.erbc {} {}\n", m.bytes.len(), sha)
            } else {
                line.to_string()
            }
        })
        .collect::<String>();
    assert_eq!(hit, 1, "manifest.txt 里必须恰好一条容器 artifact 行");
    std::fs::write(art.join("manifest.txt"), synced).expect("写同步后的 manifest.txt");
    dir
}

// ======================================================================
// 三腿读数
// ======================================================================

fn err_text(e: ErbccError) -> String {
    match e {
        ErbccError::Io(m) | ErbccError::Usage(m) | ErbccError::Parity(m) => m,
        other => format!("(非 Io 形态) {other:?}"),
    }
}

/// 腿 R1：Rust 头层判界（`container::parse`）。放行 = `PASS`。
fn rust_parse_verdict(bytes: &[u8]) -> String {
    match container::parse(bytes) {
        Ok(h) => format!(
            "PASS(version={},flags={:#07x},count={})",
            h.version, h.flags, h.section_count
        ),
        Err(e) => err_text(e),
    }
}

/// 把读数里的**被检文件绝对路径**折成 `<PATH>`：装载链的报错会带上 `program.erbc` 的完整路径
/// （`load` 走 `withSource` 加前缀），而路径里的用例目录名随 `write_case` 的 tag 变，
/// 不折叠就没法把「现跑」和「钉下的基准」（不同 tag 目录产）对齐 —— 这类行的原文只对照、
/// 不断言路径本身。
fn collapse_path(s: &str) -> String {
    const MARK: &str = "program.erbc: ";
    match s.find(MARK) {
        Some(at) => format!("<PATH>: {}", &s[at + MARK.len()..]),
        None => s.to_string(),
    }
}

/// 腿 R2：Rust 的 `erbcc check`（进程内，同一份判据与文案）。返回**全部** issue 的
/// `KIND: detail` 文本 —— 前置判据（`ARTIFACT_SIZE`/`ARTIFACT_SHA`/`SOURCE_*`/`COMPILER_STALE`）
/// 必须一条都不出现，否则容器判据的分歧就被它掩盖（假一致）。
/// 退出码因此可由读数直接推出：`report.ok` = 空列表 = rc 0。
fn rust_check_issues(out: &Path, corpus: &Path) -> Result<Vec<String>, String> {
    let report = freshness::check(out, corpus).map_err(err_text)?;
    Ok(report.issues.iter().map(|i| format!("{i}")).collect())
}

/// 从 issue 列表里挑容器判据那条（没有 = 放行）。
fn container_issue(issues: &[String]) -> Option<String> {
    issues
        .iter()
        .find(|s| s.starts_with(freshness::KIND_CONTAINER_INVALID))
        .cloned()
}

/// 腿 R3：Rust 的**区间消费方**（`incremental::container_regions`）。
/// 放行时给出「每段区间字节数」——静默降级就藏在这里：区间算得出、sha 照抄，谁也不知道段正文是假的。
fn rust_regions_verdict(path: &Path) -> String {
    match incremental::container_regions(path) {
        Ok(r) => {
            let mut s = String::from("PASS");
            for sec in &r.sections {
                s.push_str(&format!(" {}[{}..{}]", sec.name, sec.start, sec.end));
            }
            s
        }
        Err(e) => {
            let t = err_text(e);
            // 头层错误会被包一层「容器 {path:?} 的头/段表未通过单源校验：」，剥掉它对路径的依赖。
            match t.split_once("头/段表未通过单源校验：") {
                Some((_, inner)) => format!("容器头未过单源校验：{inner}"),
                None => t,
            }
        }
    }
}

// ======================================================================
// 对照表（Kotlin 真跑读数）
// ======================================================================

/// 一行对照表。Kotlin 三列都来自**真跑**：
/// `check` = `erbcc check` 的 `CONTAINER_INVALID` 那条 issue 原文（去掉 `KIND: 相对路径 段表不可信: ` 前缀）；
/// `l2` = `MappedErbc.open` + `checkSectionLayout()`（= `ProgramLoader.validateSections`）；
/// `l3` = `ProgramLoader.loadFile()`（头 + 段布局 + `ErbcReader.decode`）。
/// `kotlin_l2` / `kotlin_l3` 两列里被检文件的绝对路径在采集时归一成 `<PATH>`
/// （`run_kotlin_load_leg` 里做，不然「档位目录名」会混进基准、现跑与钉下的永远不等）。
#[derive(Debug)]
struct Row {
    id: String,
    desc: String,
    /// 变异体的字节数（防「表与档不是同一份字节」）。
    size: usize,
    kotlin_check: String,
    kotlin_l2: String,
    kotlin_l3: String,
    /// Rust 区间消费方的当前读数（**不是** Kotlin 基准，只是 Rust 自己的回归钉）。
    rust_regions: String,
}

fn verdicts() -> Vec<Row> {
    let text = std::fs::read_to_string(repo_tool_dir().join(VERDICTS_REL)).unwrap_or_else(|e| {
        panic!(
            "缺对照表 {:?}（{e}）—— 它是本闸的 Kotlin 基准，按 dump_verdicts 的 KDoc 重新生成",
            repo_tool_dir().join(VERDICTS_REL)
        )
    });
    text.lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| {
            let c: Vec<&str> = l.split('\t').collect();
            assert!(
                c.len() >= 7,
                "对照表列数不对（要 id/desc/size/kotlin_check/kotlin_l2/kotlin_l3/rust_regions）：{l}"
            );
            Row {
                id: c[0].to_string(),
                desc: c[1].to_string(),
                size: c[2].parse().unwrap_or_else(|_| panic!("size 列不是数字：{l}")),
                kotlin_check: c[3].to_string(),
                kotlin_l2: collapse_path(c[4]),
                kotlin_l3: collapse_path(c[5]),
                rust_regions: c[6].to_string(),
            }
        })
        .collect()
}

fn row_for<'a>(rows: &'a [Row], id: &str) -> &'a Row {
    rows.iter()
        .find(|r| r.id == id)
        .unwrap_or_else(|| panic!("对照表里没有档位 {id}（新增变异档要同步重跑 Kotlin 腿）"))
}

/// Kotlin 的 `CONTAINER_INVALID` issue 原文 = `kind + " " + 相对路径 + " 段表不可信: " + 头层消息`；
/// 取出末段以便与 Rust `container::parse` 的错误文本逐字比。
fn kotlin_container_message(issue: &str) -> String {
    if issue == "PASS" {
        return "PASS".to_string();
    }
    issue
        .split_once("段表不可信: ")
        .map(|(_, rest)| rest.to_string())
        .unwrap_or_else(|| panic!("CONTAINER_INVALID 行形态不对（缺「段表不可信: 」）：{issue}"))
}

// ======================================================================
// 装载层判据账（RUST1 rust-container-layout：5 档静默放行已收紧，两档「理由错」已换判据本体）
// ======================================================================

/// Rust 侧**没有**对应判据、Kotlin 装载链却按规约 §2/§3 拒掉的档位清单 —— **已清账（5 → 0）**。
///
/// 原先这 5 档（`off-dup` / `trunc-940` / `trunc-914` / `code-count-lie` / `line-count-lie`）
/// 是「同一份畸形产物一个拒一个放行」= §1.6 静默降级的真虫；现在三条装载层判据
/// （偏移 `>= 10 + 4×sectionCount`、两段不得同偏移、段区间长度与 CODE/LINE 计数对账）
/// 做进了 `container::validate_section_layout`（照 Kotlin `ProgramLoader.validateSections`），
/// 区间消费方 `incremental::container_regions` 先过这道闸 ⇒ 账上无档。
/// 逐档收紧后的两侧原文见 [LAYOUT_REJECTED_BOTH_SIDES]。
/// **这条清单仍然是双向绊线**：将来出现新的同类分歧而不在账上 → [gap_账_逐档核对_已知分歧仍在且无新增]
/// 的反向面直接红；写进账上却已经不再成立 → 同一个用例正面红。清单保持空 = 「本波没有已知放行虫」。
const KNOWN_RUST_GAP: [&str; 0] = [];

/// 收紧后的正账：这 7 档**两侧都拒且文案逐字相同** —— Rust 区间消费方的读数必须等于
/// Kotlin 段布局腿（`MappedErbc.open` + `checkSectionLayout`，`kotlin_l2` 列）那一句话。
///
/// 前 5 档来自刚清空的 [KNOWN_RUST_GAP]（Rust 原先放行）；后 2 档（`off-eq-10` /
/// `off-out-of-order`）原先是「两侧都拒但判界不同，只对照不断言」——Rust 只是被
/// `section_range` 的「下一存在段偏移更早」顺带撞到。判据本体实现出来以后，这两档
/// 升级为逐字相同：**以后谁改判据，先红在这里，而不是红在巧合上**。
const LAYOUT_REJECTED_BOTH_SIDES: [&str; 7] = [
    "off-eq-10",
    "off-dup",
    "off-out-of-order",
    "trunc-940",
    "trunc-914",
    "code-count-lie",
    "line-count-lie",
];

/// §1.5 / §8.2 的**放行面**：三腿都必须放行，谁拒了谁就是 bug。
const MUST_PASS_BOTH: [&str; 4] = ["good-seed", "append-7", "append-junk", "v1-shadow-7slots"];

// ======================================================================
// 用例
// ======================================================================

#[test]
fn 头层判界_rust与kotlin逐字一致() {
    let rows = verdicts();
    let mut checked = 0usize;
    for m in mutants() {
        let row = row_for(&rows, m.id);
        assert_eq!(m.bytes.len(), row.size, "{}：档位字节与对照表不符", m.id);
        let got = rust_parse_verdict(&m.bytes);
        let want = kotlin_container_message(&row.kotlin_check);
        if want == "PASS" {
            assert!(
                got.starts_with("PASS"),
                "{}（{}）：Kotlin 头层放行而 Rust 拒 —— 过度判界（合法产物装不上）同样是 bug",
                m.id,
                row.desc
            );
        } else {
            assert_eq!(
                got, want,
                "{}（{}）：Rust 头层判界与 Kotlin `erbcc check` 不一致 —— 一侧拒一侧放行就是 §1.6 静默降级",
                m.id,
                row.desc
            );
        }
        checked += 1;
    }
    assert_eq!(checked, rows.len(), "对照表档位与变异面数量不一致");
}

#[test]
fn check腿_容器判据与kotlin基准一致() {
    let rows = verdicts();
    let corpus = repo_tool_dir().join(CORPUS_REL);
    for m in mutants() {
        let row = row_for(&rows, m.id);
        let out = write_case("check", &m);
        let issues = rust_check_issues(&out, &corpus)
            .unwrap_or_else(|e| panic!("{}：Rust check 腿本身失败：{e}", m.id));
        // 前置判据必须**一条都不出现**（出现就说明容器判据的分歧被它盖住了 = 假一致）。
        for banned in ["ARTIFACT_SIZE", "ARTIFACT_SHA", "SOURCE_CHANGED", "COMPILER_STALE"] {
            assert!(
                !issues.iter().any(|s| s.starts_with(banned)),
                "{}：Rust check 腿里出现前置判据 {banned} —— 档位生成没把 manifest/源 sha 同步好",
                m.id
            );
        }
        let got = container_issue(&issues).unwrap_or_else(|| "PASS".to_string());
        assert_eq!(
            got, row.kotlin_check,
            "{}（{}）：Rust `erbcc check` 的 CONTAINER_INVALID 读数与 Kotlin 基准不同",
            m.id,
            row.desc
        );
        // 退出码判据：`report.ok` 空列表 = rc 0，非空 = rc 1（`Main.kt:422` 同口径）。
        assert_eq!(
            issues.is_empty(),
            row.kotlin_check == "PASS",
            "{}：Rust check 的 ok/rc 与 Kotlin 基准的拒/放行不同（issues={issues:?}）",
            m.id
        );
    }
}

#[test]
fn 放行面_尾部多余字节与合法v1视图三腿都放行() {
    // §1.5：尾部多余字节是**允许**的；§8.2：v1 产物在 v2 reader 上照旧装载。
    // 这一组钉的是「不许过度判界」——过度判界会让合法产物装不上，同样是 bug。
    let rows = verdicts();
    let corpus = repo_tool_dir().join(CORPUS_REL);
    for id in MUST_PASS_BOTH {
        let m = mutants().into_iter().find(|x| x.id == id).expect("档位存在");
        let row = row_for(&rows, id);
        assert_eq!(row.kotlin_check, "PASS", "{id}：Kotlin check 腿拒了合法档");
        assert_eq!(row.kotlin_l2, "OK", "{id}：Kotlin 段布局腿拒了合法档");
        assert_eq!(row.kotlin_l3, "OK", "{id}：Kotlin 全量装载腿拒了合法档");
        assert!(
            rust_parse_verdict(&m.bytes).starts_with("PASS"),
            "{id}：Rust 头层拒了合法档（过度判界）"
        );
        let out = write_case("pass-face", &m);
        let issues = rust_check_issues(&out, &corpus).expect("check 腿跑通");
        assert!(issues.is_empty(), "{id}：Rust check 腿拒了合法档 = {issues:?}");
        let regions = rust_regions_verdict(&out.join("erbc.v1").join("program.erbc"));
        assert!(
            regions.starts_with("PASS"),
            "{id}：Rust 区间消费方拒了合法档 = {regions}"
        );
    }
}

#[test]
fn 反向证明_对照面确实在分辨而不是两侧同因假一致() {
    let rows = verdicts();
    // ①§1.6 明令必须拒的档：version=3 两腿都拒（文案逐字相同的红，不是「都因为前置判据红」）。
    let v3 = row_for(&rows, "version-3");
    assert!(
        v3.kotlin_check.contains("不支持的 .erbc 版本 3"),
        "version=3 的 Kotlin 基准不再是「头层拒」：{v3:?}"
    );
    assert!(
        rust_parse_verdict(&with_head(|b| put_u16(b, 4, 3))).contains("不支持的 .erbc 版本 3"),
        "version=3 的 Rust 腿不再与 Kotlin 同判"
    );
    // ②前置判据确实被掐住了：所有档位的 Kotlin check 读数要么 PASS、要么是容器判据本身，
    //   一条 `ARTIFACT_SIZE` / `ARTIFACT_SHA` / `SOURCE_CHANGED` 都不许出现（否则「两侧都红」是假一致）。
    for r in &rows {
        for banned in ["ARTIFACT_SIZE", "ARTIFACT_SHA", "SOURCE_CHANGED", "COMPILER_STALE"] {
            assert!(
                !r.kotlin_check.contains(banned),
                "{}：对照表里混进了前置判据 {banned} —— 容器判据的分歧会被它掩盖",
                r.id
            );
        }
    }
    // ③同一批字节里三态齐备才说明这张表在分辨：两侧都放行 / 两侧同因逐字同判地拒 /
    //   头层都放行但**层间**结论相反（Kotlin 装载链拒、Rust 区间消费方放行）。
    let pass = rows.iter().filter(|r| r.kotlin_check == "PASS").count();
    let reject = rows.iter().filter(|r| r.kotlin_check != "PASS").count();
    let gap = rows
        .iter()
        .filter(|r| r.kotlin_check == "PASS" && r.kotlin_l2 != "OK" && r.rust_regions.starts_with("PASS"))
        .count();
    assert!(pass >= 4, "放行档只有 {pass} 张，表可能整体红（假一致）");
    assert!(reject >= 20, "头层拒的档只有 {reject} 张，覆盖面不满足契约 1–8");
    assert_eq!(gap, KNOWN_RUST_GAP.len(), "层间分歧档数变了：{gap} vs {KNOWN_RUST_GAP:?}");
    // ④装载层那 7 档：两侧都拒，且**文案逐字相同**（判据本体对齐，不再靠「顺序判界顺带撞到」）。
    for id in LAYOUT_REJECTED_BOTH_SIDES {
        let r = row_for(&rows, id);
        assert_ne!(r.kotlin_l2, "OK", "{id}：Kotlin 段布局腿不再拒");
        let m = mutants().into_iter().find(|x| x.id == id).expect("档位存在");
        let out = write_case("wording", &m);
        let regions = rust_regions_verdict(&out.join("erbc.v1").join("program.erbc"));
        assert_eq!(
            regions, r.kotlin_l2,
            "{id}：Rust 区间消费方与 Kotlin 段布局腿的拒档文案不再逐字相同 —— Rust={regions:?} Kotlin={:?}",
            r.kotlin_l2
        );
    }
}

#[test]
fn 区间消费方_现跑读数与钉下的rust基准一致() {
    // Rust 侧自己的回归钉（不是 Kotlin 基准）：`incremental::container_regions` 的逐档结论
    // 若因 src 改动而漂，本用例点名报红 —— 这样 [gap_账_逐档核对_已知分歧仍在且无新增] 的
    // 分歧账才有「当前 Rust 真实判界」可依据，而不是拿对照表里的旧 Rust 读数自证。
    let rows = verdicts();
    for m in mutants() {
        let row = row_for(&rows, m.id);
        let out = write_case("regions", &m);
        let got = rust_regions_verdict(&out.join("erbc.v1").join("program.erbc"));
        assert_eq!(
            got, row.rust_regions,
            "{}（{}）：Rust 区间消费方读数与钉下的基准不同（src/container.rs 或 incremental.rs 改了？）",
            m.id,
            row.desc
        );
    }
}

#[test]
fn gap_账_逐档核对_已知分歧仍在且无新增() {
    let rows = verdicts();
    for id in KNOWN_RUST_GAP {
        let r = row_for(&rows, id);
        assert_eq!(
            r.kotlin_check, "PASS",
            "{id}：这条分歧发生在**头层之上**（段布局层），Kotlin check 腿应当也放行"
        );
        assert_ne!(r.kotlin_l2, "OK", "{id}：Kotlin 段布局腿不再拒 —— 若已收紧，请把本档移出 KNOWN_RUST_GAP 并写进报告");
        assert!(
            r.rust_regions.starts_with("PASS"),
            "{id}：Rust 区间消费方不再放行畸形档（已收紧？请同步 KNOWN_RUST_GAP）：{}",
            r.rust_regions
        );
    }
    // 反向：不在账上的档位，Rust 区间消费方（现跑）与 Kotlin 段布局腿必须同结论（拒/放行一致）。
    for m in mutants() {
        if KNOWN_RUST_GAP.contains(&m.id) {
            continue;
        }
        let r = row_for(&rows, m.id);
        let out = write_case("gap", &m);
        let regions = rust_regions_verdict(&out.join("erbc.v1").join("program.erbc"));
        let kotlin_rejects_layout = r.kotlin_l2 != "OK";
        let rust_rejects_regions = !regions.starts_with("PASS");
        assert_eq!(
            rust_rejects_regions, kotlin_rejects_layout,
            "{}（{}）：Rust 区间消费方与 Kotlin 段布局腿出现**账外**分歧 —— Kotlin L2={:?} Rust={:?}",
            m.id, m.desc, r.kotlin_l2, regions
        );
    }
}

#[test]
fn 装载链文案_kotlin段布局腿与全量腿同判据() {
    // 钉的是「Kotlin 自己没有两套口径」：`checkSectionLayout`（映射装载链）与
    // `loadFile`（整读装载链）对同一份畸形字节必须给同一条判据。
    // 两条文案只在**类名前缀**（`ErbcLoadException` 是 `load` 的 `withSource` 包装）与
    // **被检文件绝对路径**上不同 —— 含路径的原文属「只对照不断言」，本用例断言的是
    // 剥掉「`<Class>: `」与「`<绝对路径>: `」两层前缀后的判据本体。
    fn judge(text: &str) -> String {
        if text == "OK" {
            return "OK".to_string();
        }
        let body = match text.split_once(": ") {
            Some((_, rest)) => rest.to_string(),
            None => text.to_string(),
        };
        const MARK: &str = "program.erbc: ";
        let bare = match body.find(MARK) {
            Some(at) => body[at + MARK.len()..].to_string(),
            None => body,
        };
        // 采集侧把被检文件的绝对路径归一成 <PATH>（见 run_kotlin_load_leg），这里再剥一层。
        match bare.strip_prefix("<PATH>: ") {
            Some(rest) => rest.to_string(),
            None => bare,
        }
    }
    let rows = verdicts();
    for r in &rows {
        if r.kotlin_l2 == "OK" {
            assert_eq!(r.kotlin_l3, "OK", "{}：段布局腿放行但全量腿拒（Kotlin 内部分歧）", r.id);
            continue;
        }
        assert_eq!(
            judge(&r.kotlin_l2),
            judge(&r.kotlin_l3),
            "{}：Kotlin 两条装载入口的判据本体不同 —— L2={:?} L3={:?}",
            r.id,
            r.kotlin_l2,
            r.kotlin_l3
        );
    }
}

/// **纯 Kotlin 单侧真跑**（契约「闸不是空转」要求的第二次观察）：
/// 抽一档 §1.6 明令必须拒的（version=3）+ 一档必须放行的（append-7），直接起 Kotlin CLI，
/// 与对照表逐字比 —— 证明 Rust 腿不是「自己跟自己对齐」，而是真与 Kotlin 对得上。
/// 需要 `JAVA_HOME`（或 PATH 上的 java）+ `:tools:erbcc:installDist`（或 `ERBCC_CLASSPATH`）。
#[test]
fn kotlin腿真跑_抽验一档拒一档放行() {
    let Some(cp) = kotlin_classpath() else {
        eprintln!(
            "跳过：缺纯 Kotlin 工具链（跑 ./gradlew --configure-on-demand :tools:erbcc:installDist，\
             或设 ERBCC_CLASSPATH）—— 对照表里的 Kotlin 读数就不是本趟现跑的"
        );
        return;
    };
    let rows = verdicts();
    let corpus = repo_tool_dir().join(CORPUS_REL);
    for id in ["version-3", "append-7"] {
        let m = mutants().into_iter().find(|x| x.id == id).expect("档位存在");
        let out = write_case("kotlin-live", &m);
        let (rc, stdout) = run_kotlin_check(&cp, &corpus, &out);
        let issue = stdout
            .lines()
            .map(str::trim)
            .find(|l| l.starts_with("CONTAINER_INVALID:"))
            .map(str::to_string);
        let want = row_for(&rows, id).kotlin_check.clone();
        let got = issue.unwrap_or_else(|| "PASS".to_string());
        assert_eq!(got, want, "{id}：现跑的 Kotlin CLI 读数与钉下的基准不同（Kotlin 侧漂了或档位变了）");
        let expect_reject = want != "PASS";
        assert_eq!(
            rc == 1,
            expect_reject,
            "{id}：Kotlin CLI 退出码形态不对（rc={rc}, stdout={stdout}）"
        );
    }
}

/// 可选：纯 Kotlin 装载链腿（jshell 直调 `MappedErbc.checkSectionLayout` / `ProgramLoader.loadFile`）
/// 的真跑复验，默认关（一趟 30+ 秒 × 33 档）。开 `ERATW_FUZZDIFF_JVM=1` 逐档现跑并与对照表比。
#[test]
fn kotlin装载链腿真跑_可选复验() {
    if std::env::var("ERATW_FUZZDIFF_JVM").map(|v| v != "1").unwrap_or(true) {
        eprintln!("跳过：设 ERATW_FUZZDIFF_JVM=1 才逐档现跑 Kotlin 装载链腿（jshell，慢）");
        return;
    }
    let Some(cp) = kotlin_classpath() else {
        panic!("需要纯 Kotlin 工具链才能跑本用例");
    };
    let rows = verdicts();
    for m in mutants() {
        let row = row_for(&rows, m.id);
        let out = write_case("jvm-live", &m);
        let erbc = out.join("erbc.v1").join("program.erbc");
        let (l2, l3) = run_kotlin_load_leg(&cp, &erbc);
        assert_eq!(l2, row.kotlin_l2, "{}：Kotlin 段布局腿读数漂了", m.id);
        assert_eq!(l3, row.kotlin_l3, "{}：Kotlin 全量装载腿读数漂了", m.id);
    }
}

// ======================================================================
// JVM 腿的小工具（本测试自带解析，不与 src/kernel.rs 共用，免得两腿共享一个 bug）
// ======================================================================

fn kotlin_classpath() -> Option<String> {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
            return Some(t.to_string());
        }
    }
    let lib = repo_tool_dir().join("../erbcc/build/install/erbcc/lib");
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

fn jshell_exe() -> PathBuf {
    match std::env::var("JAVA_HOME") {
        Ok(home) if !home.trim().is_empty() => {
            Path::new(home.trim()).join(format!("bin/jshell{}", std::env::consts::EXE_SUFFIX))
        }
        _ => PathBuf::from("jshell"),
    }
}

fn run_kotlin_check(cp: &str, corpus: &Path, out: &Path) -> (i32, String) {
    let o = std::process::Command::new(java_exe())
        .args(["-Xmx2g", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"])
        .arg("-cp")
        .arg(cp)
        .arg("com.eratw.port.erbcc.MainKt")
        .args(["check", "--corpus"])
        .arg(corpus)
        .arg("--out")
        .arg(out)
        .current_dir(repo_tool_dir())
        .output()
        .expect("起纯 Kotlin erbcc check 失败");
    (
        o.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&o.stdout).into_owned(),
    )
}

/// Kotlin 装载链两腿：`MappedErbc.open` + `checkSectionLayout()`（段布局）与 `ProgramLoader.loadFile`（全量）。
/// 只调用 core-vm 既有公开入口，不新增也不改任何 Kotlin 代码。
/// jshell 的 stdout 在 Windows 上是 cp936，故结果由脚本侧用 UTF-8 写文件再回读。
fn run_kotlin_load_leg(cp: &str, erbc: &Path) -> (String, String) {
    let dir = current_dir().join("build/fuzz-diff/jsh");
    std::fs::create_dir_all(&dir).expect("建 jsh 目录");
    let jsh = dir.join("probe.jsh");
    let res = dir.join("probe.out");
    let _ = std::fs::remove_file(&res);
    let script = format!(
        "import com.eratw.port.core.loader.*;\n\
         import java.nio.file.*;\n\
         import java.nio.charset.StandardCharsets;\n\
         import java.util.List;\n\
         String P = \"{}\";\n\
         String l2 = \"?\";\n\
         try {{ MappedErbc m = MappedErbc.Companion.open(Paths.get(P));\n\
           try {{ m.checkSectionLayout(); l2 = \"OK\"; }}\n\
           catch (Throwable t) {{ l2 = t.getClass().getSimpleName() + \": \" + t.getMessage(); }}\n\
           m.close(); }}\n\
         catch (Throwable t) {{ l2 = t.getClass().getSimpleName() + \": \" + t.getMessage(); }}\n\
         String l3 = \"?\";\n\
         try {{ ProgramLoader.INSTANCE.loadFile(Paths.get(P)); l3 = \"OK\"; }}\n\
         catch (Throwable t) {{ l3 = t.getClass().getSimpleName() + \": \" + t.getMessage(); }}\n\
         Files.write(Paths.get(\"{}\"), List.of(l2, l3), StandardCharsets.UTF_8);\n/exit\n",
        erbc.to_string_lossy().replace('\\', "\\\\"),
        res.to_string_lossy().replace('\\', "\\\\")
    );
    std::fs::write(&jsh, script).expect("写 jshell 脚本");
    let o = std::process::Command::new(jshell_exe())
        .arg("-R-Dsun.stdout.encoding=UTF-8")
        .arg("--class-path")
        .arg(cp)
        .arg(&jsh)
        .output()
        .expect("起 jshell 失败");
    let text = std::fs::read_to_string(&res)
        .unwrap_or_else(|e| panic!("jshell 没写出读数 {res:?}（rc={:?}）：{e}", o.status.code()));
    // 绝对路径在 Rust 侧归一（jshell 里的 `String.replace` 依赖转义后的同一个串，
    // 一旦档位目录名不同就会换行、把路径写进基准 ⇒ 现跑与钉下的永远不等）。
    let path_text = erbc.to_string_lossy().into_owned();
    let mut lines = text
        .lines()
        .map(|l| collapse_path(l.trim_end_matches('\r')).replace(path_text.as_str(), "<PATH>"));
    let l2 = lines.next().expect("L2 行");
    let l3 = lines.next().expect("L3 行");
    (l2, l3)
}

/// 重新生成对照表用的读数倾倒（`cargo test --test malformed_diff dump_verdicts -- --ignored
/// --nocapture`）：三腿 Rust + Kotlin CLI + Kotlin 装载链，逐档一行 TSV 打到 stdout。
/// 把输出存进 `tests/data/rust-fuzz-diff/kotlin-verdicts.tsv` 就是新的 Kotlin 基准
/// （**必须**真跑，禁手抄；两列 Kotlin 腿都来自 JVM 侧）。
#[test]
#[ignore = "重生成对照表才跑：cargo test --test malformed_diff dump_verdicts -- --ignored --nocapture"]
fn dump_verdicts() {
    let corpus = repo_tool_dir().join(CORPUS_REL);
    let cp = kotlin_classpath().expect("dump 需要纯 Kotlin 工具链（installDist 或 ERBCC_CLASSPATH）");
    println!("# id\tdesc\tsize\tkotlin_check\tkotlin_l2\tkotlin_l3\trust_regions");
    for m in mutants() {
        let out = write_case("dump", &m);
        let erbc = out.join("erbc.v1").join("program.erbc");
        let (_, stdout) = run_kotlin_check(&cp, &corpus, &out);
        let issue = stdout
            .lines()
            .map(str::trim)
            .find(|l| l.starts_with("CONTAINER_INVALID:"))
            .unwrap_or("PASS")
            .to_string();
        let (l2, l3) = run_kotlin_load_leg(&cp, &erbc);
        let regions = rust_regions_verdict(&erbc);
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}",
            m.id,
            m.desc,
            m.bytes.len(),
            issue,
            l2,
            l3,
            regions
        );
    }
}
