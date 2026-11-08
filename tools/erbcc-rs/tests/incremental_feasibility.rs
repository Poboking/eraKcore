//! RUST1 / rust-incremental 车道的出口闸：**产物层增量判据的实测证伪**。
//!
//! 判据（`src/incremental.rs` 的模块 doc）：**存在一组文件，改动其中任意一个之后，
//! 未改动文件对应的产物区间逐字节不变。**
//! 本闸不论证「省了几秒」，只跑真编译、真比对，把这条判据打成红：
//! 探针语料里 **从不编辑** 的 `inc_b.ERB`（函数 `IFB_ONE` / `IFB_TWO`）的指令区间，
//! 在只改前档 `inc_a.ERB`（或只改 ERH、或新增一个文件）之后**内容就变了**，不只是位置平移。
//!
//! ## 探针语料与变体
//!
//! `tests/data/rust-incremental/corpus/ERB/` 是三档 ERB + 一档 ERH（装载序
//! `inc_a` < `inc_b` < `inc_c`；ERH 走独立序）。`variants/<tag>/` 是**覆盖层**：
//! 同名文件覆盖基准档，新文件即「语料新增」，`__delete__.lst` 每行一个要删掉的文件名。
//! 每个变体各自跑一次**真编译**（Rust 编排腿 `cmd_compile::execute`：真 JVM kernel +
//! 真清单函数），产物落在 `tools/erbcc-rs/build/incremental/<tag>-<pid>/out/erbc.v1/`。
//!
//! ## 读数登记
//!
//! `tests/data/rust-incremental/probe-readings.txt` 登记每个变体的三件套字节数 + SHA-256，
//! 以及观测量 `IFB_ONE` 的指令区间（绝对起点 + 内容 SHA-256）。本闸逐列断言它 ——
//! 期望值来自真编译产物，不是手抄推演（刷新条件见该文件头注）。
//!
//! ## 运行前提
//!
//! `JAVA_HOME`（或 PATH 上的 `java`）+ `:tools:erbcc:installDist` 产物（或 `ERBCC_CLASSPATH`）。
//! 一趟编译 ≈3 s，全闸 11 趟串行编译 ≈40 s；编译串行化（一把全局锁），以免并发拉起多个 6g 堆的 JVM。

use erbcc_rs::cmd_compile;
use erbcc_rs::incremental::{self, FunctionRegion};
use erbcc_rs::types::absolutize;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// 观测对象：来自**从不编辑**的 `inc_b.ERB`。
const OBSERVED: &str = "IFB_ONE";

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&erbcc_rs::types::current_dir())
        .expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

fn data_dir() -> PathBuf {
    repo_root().join("tools/erbcc-rs/tests/data/rust-incremental")
}

/// 编译串行锁：11 路并行各拉一个 `-Xmx6g` 的 JVM 会把机器压成交换地狱，读数也不可信。
fn compile_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

/// 一个探针：真编译产物根目录 + 递给 `--corpus` 的目录。
struct Probe {
    tag: String,
    /// 清单 `source` 行的相对路径就以它为根，台账比对必须用同一个根。
    erb_root: PathBuf,
    out: PathBuf,
}

impl Probe {
    fn artifact_dir(&self) -> PathBuf {
        self.out.join(erbcc_rs::types::ERBC_DIR_NAME)
    }
    fn erbc(&self) -> PathBuf {
        self.artifact_dir().join("program.erbc")
    }
}

/// 基准探针（整个测试二进制里只编一次，所有比对都以它为基准）。
fn base_probe() -> &'static Probe {
    static BASE: OnceLock<Probe> = OnceLock::new();
    BASE.get_or_init(|| compile_tag("base"))
}

/// 组装语料（基准 + 覆盖层 + 删除表）并真编译。
fn compile_tag(tag: &str) -> Probe {
    let work = repo_root().join(format!(
        "tools/erbcc-rs/build/incremental/{tag}-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).expect("建探针工作目录");
    let corpus = absolutize(&work.join("corpus"));
    copy_dir_all(&data_dir().join("corpus"), &corpus).expect("复制基准语料");
    let overlay = data_dir().join("variants").join(tag);
    if overlay.is_dir() {
        // 删除表先读再删；其余文件一律覆盖（新文件即「语料新增」）。
        let mut delete: Vec<String> = Vec::new();
        for entry in std::fs::read_dir(&overlay).expect("读变体目录") {
            let path = entry.expect("变体目录项").path();
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            if name == "__delete__.lst" {
                let text = std::fs::read_to_string(&path).expect("读删除表");
                delete.extend(
                    text.lines()
                        .map(str::trim)
                        .filter(|l| !l.is_empty() && !l.starts_with('#'))
                        .map(str::to_string),
                );
                continue;
            }
            std::fs::copy(&path, corpus.join("ERB").join(&name)).expect("覆盖变体文件");
        }
        for rel in &delete {
            let victim = corpus.join("ERB").join(rel);
            assert!(victim.is_file(), "删除表点了不存在的文件：{victim:?}");
            std::fs::remove_file(&victim).expect("删除语料文件");
        }
    }
    let out = absolutize(&work.join("out"));
    let erb_root = absolutize(&corpus.join("ERB"));
    let _guard = compile_lock().lock().unwrap_or_else(|e| e.into_inner());
    let code = cmd_compile::execute(&[
        "--corpus".to_string(),
        erb_root.to_string_lossy().into_owned(),
        "--out".to_string(),
        out.to_string_lossy().into_owned(),
    ])
    .unwrap_or_else(|e| panic!("探针 {tag} 编译报红：{e:?}"));
    assert_eq!(code, 0, "探针 {tag} 应编译成功（退 0）");
    Probe {
        tag: tag.to_string(),
        erb_root,
        out,
    }
}

fn copy_dir_all(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let src = entry?.path();
        let dst = to.join(src.file_name().unwrap());
        if src.is_dir() {
            copy_dir_all(&src, &dst)?;
        } else {
            std::fs::copy(&src, &dst)?;
        }
    }
    Ok(())
}

// ======================================================================
// 登记物
// ======================================================================

struct Reading {
    tag: String,
    erbc_bytes: u64,
    erbc_sha: String,
    stmt_bytes: u64,
    stmt_sha: String,
    meta_sha: String,
    observed_start: usize,
    observed_len: usize,
    observed_sha: String,
}

fn readings() -> Vec<Reading> {
    let file = data_dir().join("probe-readings.txt");
    let text =
        std::fs::read_to_string(&file).unwrap_or_else(|e| panic!("读不到登记物 {file:?}：{e}"));
    let mut out = Vec::new();
    for line in text.lines() {
        let cols: Vec<&str> = line.split('|').collect();
        if cols.len() != 9 || cols[0] == "tag" {
            continue;
        }
        out.push(Reading {
            tag: cols[0].to_string(),
            erbc_bytes: cols[1].parse().expect("erbc 字节数是整数"),
            erbc_sha: cols[2].to_string(),
            stmt_bytes: cols[3].parse().expect("stmt 字节数是整数"),
            stmt_sha: cols[4].to_string(),
            meta_sha: cols[5].to_string(),
            observed_start: cols[6].parse().expect("观测函数起点是整数"),
            observed_len: cols[7].parse().expect("观测函数长度是整数"),
            observed_sha: cols[8].to_string(),
        })
    }
    assert!(
        out.len() >= 11,
        "登记物应有 base + 至少 10 个探针，实为 {} 行",
        out.len()
    );
    out
}

fn reading_for(tag: &str) -> Reading {
    readings()
        .into_iter()
        .find(|r| r.tag == tag)
        .unwrap_or_else(|| panic!("登记物里没有探针 {tag}"))
}

fn sha256_of(path: &Path) -> String {
    let raw = std::fs::read(path).unwrap_or_else(|e| panic!("读产物失败 {path:?}: {e}"));
    erbcc_rs::types::sha256_hex(&raw)
}

/// 断言探针产物的三件套摘要 + 观测函数区间都等于登记值，并把读数原样打到 stdout。
fn assert_registered(probe: &Probe) {
    let want = reading_for(&probe.tag);
    let dir = probe.artifact_dir();
    for (name, bytes, sha) in [
        ("program.erbc", want.erbc_bytes, want.erbc_sha.as_str()),
        ("program.stmt.idx", want.stmt_bytes, want.stmt_sha.as_str()),
    ] {
        let path = dir.join(name);
        let raw = std::fs::read(&path).unwrap_or_else(|e| panic!("缺产物 {path:?}：{e}"));
        assert_eq!(raw.len() as u64, bytes, "{} 的 {name} 字节数与登记不等", probe.tag);
        assert_eq!(
            erbcc_rs::types::sha256_hex(&raw),
            sha,
            "{} 的 {name} sha256 与登记不等",
            probe.tag
        );
    }
    // 侧车只登记摘要（尺寸不进判据面）。
    assert_eq!(
        sha256_of(&dir.join("program.meta")),
        want.meta_sha,
        "{} 的 program.meta sha256 与登记不等",
        probe.tag
    );

    let fns = incremental::function_regions(&probe.erbc()).expect("读函数区间");
    let obs = fns
        .iter()
        .find(|f| f.name == OBSERVED)
        .unwrap_or_else(|| panic!("{} 的产物里没有 {OBSERVED}", probe.tag));
    assert_eq!(
        obs.code_start, want.observed_start,
        "{} 的 {OBSERVED} 指令绝对起点与登记不等（区间判据的位置那一半）",
        probe.tag
    );
    assert_eq!(
        obs.code_end - obs.code_start,
        want.observed_len,
        "{} 的 {OBSERVED} 指令长度与登记不等",
        probe.tag
    );
    assert_eq!(
        obs.code_sha256, want.observed_sha,
        "{} 的 {OBSERVED} 指令内容 sha256 与登记不等（区间判据的内容那一半）",
        probe.tag
    );
    println!(
        "PROBE {}: erbc {} 字节 sha {}…；{OBSERVED} 区间 [{},{}) 内容 sha {}…",
        probe.tag,
        want.erbc_bytes,
        &want.erbc_sha[..16],
        obs.code_start,
        obs.code_end,
        &obs.code_sha256[..16]
    );
}

// ======================================================================
// 比对助手
// ======================================================================

fn observed_of(probe: &Probe) -> FunctionRegion {
    incremental::function_regions(&probe.erbc())
        .expect("读函数区间")
        .into_iter()
        .find(|f| f.name == OBSERVED)
        .expect("观测函数必须在产物里")
}

/// 找出某函数指令流里第一条 `op` 等于给定值的指令。
fn first_word(f: &FunctionRegion, op: u32) -> Option<[u32; 4]> {
    f.words.iter().find(|w| w[0] == op).copied()
}

fn plan_of(modified: &[&str], added: &[&str], removed: &[&str]) -> incremental::ChangePlan {
    incremental::ChangePlan {
        modified: modified.iter().map(|s| s.to_string()).collect(),
        added: added.iter().map(|s| s.to_string()).collect(),
        removed: removed.iter().map(|s| s.to_string()).collect(),
    }
}

// ======================================================================
// 判据闸
// ======================================================================

/// **主闸**：判据不成立 —— 只改前档，未改动文件的指令**内容**就变（不是平移）。
///
/// 覆盖四个面：全局串池下标、语句池下标、函数序（增/删）、语料新增。
#[test]
fn 判据不成立_只改前档就让未改动文件的指令内容位移() {
    let base_obs = observed_of(base_probe());
    let cases = [
        ("p2_stradd", "全局串池下标（_SET_STR 的 b）", 3u32, 1u32, 2u32),
        ("p2_stmtadd", "语句池下标（marker 的 a）", 70u32, 4u32, 5u32),
        ("p3_funcadd", "函数表下标（_CALL 的 a）", 22u32, 0u32, 1u32),
    ];
    for (tag, what, op, want_base, want_variant) in cases {
        let probe = compile_tag(tag);
        assert_registered(&probe);
        let obs = observed_of(&probe);
        assert!(
            base_obs.code_sha256 != obs.code_sha256,
            "探针 {tag}（{what}）本应让 {OBSERVED} 的指令内容变化；判据若在这里成立就得说明为什么"
        );
        let a = first_word(&base_obs, op).unwrap_or_else(|| panic!("基准里没有 op={op} 的指令"));
        let b = first_word(&obs, op).unwrap_or_else(|| panic!("探针 {tag} 里没有 op={op} 的指令"));
        // `_SET_STR` 的池下标在 c 位之前（b），marker / `_CALL` 的下标在 a 位。
        let field = if op == 3 { 2 } else { 1 };
        println!(
            "PROBE {tag}: {OBSERVED} op={op} 操作数 基准={} 变体={}（登记期望 {want_base}→{want_variant}，{what}）",
            a[field], b[field]
        );
        assert_eq!(a[field], want_base, "探针 {tag}：基准侧 {what} 的读数与登记不符");
        assert_eq!(b[field], want_variant, "探针 {tag}：变体侧 {what} 的读数与登记不符");

        // 可执行判据：每个探针的变更集都是非空 ⇒ 都必须判「产物层不可行」。
        let plan = plan_of(&["ERB/inc_a.ERB"], &[], &[]);
        assert!(
            !incremental::probe_stability(&plan).expect("probe_stability 不吃 I/O，不该报红"),
            "探针 {tag}：非空变更集必须判产物层不可行"
        );
    }

    // 删函数：串池/常量池同时缩水，后档操作数集体回落。
    let probe = compile_tag("p3_funcdel");
    assert_registered(&probe);
    let obs = observed_of(&probe);
    assert_ne!(
        obs.code_sha256, base_obs.code_sha256,
        "删前档一个函数后 B 的指令内容必须变"
    );
    let w = first_word(&obs, 3).expect("变体里仍应有 _SET_STR");
    println!("PROBE p3_funcdel: {OBSERVED} 的 _SET_STR b = {}（基准 1）", w[2]);
    assert_eq!(w[2], 0, "前档删掉一个字符串字面量后，后档的串池下标应回落到 0");

    // 新增文件：后档所有池下标一起位移。
    let probe = compile_tag("p6_fileadd");
    assert_registered(&probe);
    let obs = observed_of(&probe);
    let m = first_word(&obs, 70).expect("变体里仍应有 marker");
    let s = first_word(&obs, 3).expect("变体里仍应有 _SET_STR");
    println!(
        "PROBE p6_fileadd: {OBSERVED} marker a = {}（基准 4）、_SET_STR b = {}（基准 1）",
        m[1], s[2]
    );
    assert_ne!(
        obs.code_sha256, base_obs.code_sha256,
        "新增最前一档文件后 B 的指令内容必须变"
    );
    assert_eq!(m[1], 6, "新增文件带来 2 条语句：B 的首个 marker a 应从 4 位移到 6");
    assert_eq!(s[2], 2, "新增文件带来 1 个字面量：B 的串池下标应从 1 位移到 2");
}

/// 「区间」的两半：位置那一半比内容更容易失守 —— 纯追加/纯删除也保不住绝对字节区间。
#[test]
fn 纯追加类改动_指令内容保住但字节区间平移() {
    let base = base_probe();
    let base_c = incremental::container_regions(&base.erbc()).expect("读基准容器区间");
    let base_f = incremental::function_regions(&base.erbc()).expect("读基准函数区间");
    for tag in ["p2_strswap", "p5_lastfile", "p7_filedel"] {
        let probe = compile_tag(tag);
        assert_registered(&probe);
        let c = incremental::container_regions(&probe.erbc()).expect("读变体容器区间");
        let f = incremental::function_regions(&probe.erbc()).expect("读变体函数区间");
        assert_ne!(
            base_c.header_sha256, c.header_sha256,
            "探针 {tag}：段表在容器头里（specs/erbc-format.md §2），段长一变段表就变"
        );
        let cmp = incremental::compare_function_regions(&base_f, &f);
        let obs = cmp
            .iter()
            .find(|r| r.name == OBSERVED)
            .expect("观测函数两侧都在");
        println!(
            "PROBE {tag}: {OBSERVED} 内容等={} 区间等={} 基准[{},{}) 变体[{},{})",
            obs.content_equal,
            obs.location_equal,
            obs.base_range.0,
            obs.base_range.1,
            obs.variant_range.0,
            obs.variant_range.1
        );
        assert!(obs.content_equal, "探针 {tag} 本应只动位置、不动 B 的指令内容");
        assert!(
            !obs.location_equal,
            "探针 {tag}：B 的指令内容一字未改，但绝对字节区间平移 ⇒ 产物区间判据仍不成立"
        );
    }
}

/// 唯一「产物逐字节不变」的改动类：注释/空白。它不能反证产物层可行（要先解析才知道）。
#[test]
fn 注释类改动容器逐字节相同_但判据不可用它反证() {
    let probe = compile_tag("p1_comment");
    assert_registered(&probe);
    let base = base_probe();
    let base_c = incremental::container_regions(&base.erbc()).expect("读基准容器区间");
    let c = incremental::container_regions(&probe.erbc()).expect("读变体容器区间");
    assert_eq!(base_c.header_sha256, c.header_sha256, "注释类改动：段表逐字节不变");
    assert_eq!(base_c.size, c.size, "注释类改动：容器长度不变");
    assert_eq!(
        sha256_of(&base.erbc()),
        sha256_of(&probe.erbc()),
        "注释类改动：整个 program.erbc 实测逐字节相同"
    );
    for i in 0..incremental::SECTION_NAMES.len() {
        assert_eq!(
            base_c.section_equal(&c, i),
            Some(true),
            "注释类改动：段 {}（{}）内容必须逐字节不变",
            i,
            incremental::SECTION_NAMES[i]
        );
    }
    // 可执行判据为什么不能靠它：ChangePlan 只看得到「sha 变了」，看不到「变的是注释」。
    assert!(
        !incremental::probe_stability(&plan_of(&["ERB/inc_a.ERB"], &[], &[])).unwrap(),
        "非空变更集一律判不可行：注释类改动是唯一反例，而识别它必须先付解析成本"
    );
    assert!(
        incremental::probe_stability(&plan_of(&[], &[], &[])).unwrap(),
        "空变更集（没有文件改动）才是唯一能断言产物区间不动的情形"
    );
}

/// 跨文件求值面：改 ERH 的 `#DIM CONST` 让 `program.erbc` 一字不变，却把侧车锁死。
#[test]
fn erh_常量改动_容器不变但侧车把后档尺寸读数锁死() {
    let probe = compile_tag("p4_erhconst");
    assert_registered(&probe);
    let base = base_probe();
    assert_eq!(
        sha256_of(&base.erbc()),
        sha256_of(&probe.erbc()),
        "探针 p4_erhconst：容器实测逐字节相同（#DIM CONST 不进容器）"
    );
    let base_meta =
        std::fs::read_to_string(base.artifact_dir().join("program.meta")).expect("读基准侧车");
    let meta = std::fs::read_to_string(probe.artifact_dir().join("program.meta"))
        .expect("读变体侧车");
    assert_ne!(base_meta, meta, "侧车必须变：后档函数的尺寸读数被前档常量锁死");
    let needle = format!("\"{OBSERVED}\"");
    let find = |text: &str| {
        text.lines()
            .find(|l| l.starts_with("function ") && l.contains(&needle))
            .unwrap_or_else(|| panic!("侧车里没有 {OBSERVED} 的 function 行"))
            .to_string()
    };
    let base_line = find(&base_meta);
    let var_line = find(&meta);
    println!("PROBE p4_erhconst: 侧车基准行 {base_line}");
    println!("PROBE p4_erhconst: 侧车变体行 {var_line}");
    assert!(
        base_line.ends_with(" 3 \"inc_b.ERB:2\"") && var_line.ends_with(" 4 \"inc_b.ERB:2\""),
        "KKC 3→4 应把 {OBSERVED} 的尺寸读数从 3 折成 4（实测读数），实为：{base_line} / {var_line}"
    );
    assert_eq!(
        base_line,
        "function 2 3 \"IFB_ONE\" \"IFB_ONE\" 0 0 0 0 3 \"inc_b.ERB:2\"",
        "侧车基准行的实测读数与登记不符"
    );
}

/// 排除表本身的可检查性：每条耦合都要有对应探针目录 + 非空读数 + `文件:行号` 依据。
#[test]
fn 排除表每条目都有探针目录与实测读数() {
    let measured: Vec<String> = readings().into_iter().map(|r| r.tag).collect();
    assert!(
        incremental::EXCLUSIONS.len() >= 7,
        "排除表应覆盖七个锁死面，实为 {}",
        incremental::EXCLUSIONS.len()
    );
    for c in incremental::EXCLUSIONS {
        assert!(
            !c.name.is_empty() && !c.mechanism.is_empty(),
            "排除条目 {} 缺机制说明",
            c.name
        );
        assert!(
            c.evidence.contains(':'),
            "排除条目 {} 缺 文件:行号 依据：{}",
            c.name,
            c.evidence
        );
        assert!(!c.reading.is_empty(), "排除条目 {} 缺实测读数", c.name);
        assert!(
            measured.iter().any(|t| t == c.probe),
            "排除条目 {} 的探针 {} 没有登记读数",
            c.name,
            c.probe
        );
        let dir = data_dir().join("variants").join(c.probe);
        assert!(dir.is_dir(), "排除条目 {} 的探针目录不存在：{dir:?}", c.name);
    }
}

/// 台账变更集：修改 / 新增 / 删除 各自归位（不需要编译，纯内容寻址）。
#[test]
fn 台账变更集把修改新增删除各自归位() {
    let work = repo_root().join(format!(
        "tools/erbcc-rs/build/incremental/ledger-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).expect("建台账工作目录");
    let corpus = absolutize(&work.join("corpus"));
    copy_dir_all(&data_dir().join("corpus"), &corpus).expect("复制探针语料");

    // 台账 = 当前磁盘的真摘要（走唯一口径 manifest::source_entries）。
    let sources = erbcc_rs::manifest::source_entries(&corpus).expect("源条目");
    let text = erbcc_rs::manifest::manifest_text(
        2,
        "0.0.0-test",
        &"0".repeat(64),
        1,
        "full",
        None,
        &corpus.to_string_lossy(),
        &sources,
        &[],
        None,
    )
    .expect("清单文本");
    let ledger = work.join("manifest.txt");
    std::fs::write(&ledger, text).expect("落台账");

    let plan = incremental::diff_against_ledger(&ledger, &corpus).expect("台账比对报红");
    assert!(plan.is_empty(), "同一份磁盘自比应无变更，实为 {} 条", plan.total());

    // 1) 修改：给 inc_a.ERB 追加一行注释（内容寻址必须抓到，哪怕产物字节不动）。
    let a = corpus.join("ERB/inc_a.ERB");
    let mut body = std::fs::read_to_string(&a).expect("读 inc_a");
    body.push_str("; 台账用的一行注释\n");
    std::fs::write(&a, body).expect("写 inc_a");
    // 2) 新增：多一个 ERB 文件。
    std::fs::write(corpus.join("ERB/inc_zz.ERB"), "@IFZ\n\tPRINTL Z-STR\n").expect("新增文件");
    // 3) 删除：末档整文件删掉。
    std::fs::remove_file(corpus.join("ERB/inc_c.ERB")).expect("删除文件");

    let plan = incremental::diff_against_ledger(&ledger, &corpus).expect("改动后的台账比对报红");
    println!(
        "LEDGER modified={:?} added={:?} removed={:?}",
        plan.modified, plan.added, plan.removed
    );
    assert_eq!(plan.modified, vec!["ERB/inc_a.ERB".to_string()], "修改类只该抓到 inc_a");
    assert_eq!(plan.added, vec!["ERB/inc_zz.ERB".to_string()], "新增类只该抓到 inc_zz");
    assert_eq!(plan.removed, vec!["ERB/inc_c.ERB".to_string()], "删除类只该抓到 inc_c");
    assert!(!incremental::probe_stability(&plan).unwrap(), "非空变更集 ⇒ 产物层不可增量");

    // 台账读不出来必须报红，不许返回空表冒充「没改动」。
    assert!(incremental::diff_against_ledger(&work.join("nope.txt"), &corpus).is_err());
}

/// 与既有新鲜度判据交叉核对：台账说变了，`freshness::check` 也必须说「改源未重编」。
#[test]
fn 台账与新鲜度对同一改动同结论() {
    let probe = base_probe();
    let ledger = probe.artifact_dir().join("manifest.txt");
    // 改动前：台账空、新鲜度也应判绿（同一把尺）。
    let plan = incremental::diff_against_ledger(&ledger, &probe.erb_root).expect("台账比对报红");
    assert!(plan.is_empty(), "编译后语料未动，变更集应为空，实为 {} 条", plan.total());
    let report = erbcc_rs::freshness::check(&probe.out, &probe.erb_root).expect("新鲜度检查报红");
    println!("FRESH 未改动：ok={} issues={}", report.ok, report.issues.len());
    assert!(report.ok, "未改动时新鲜度应判绿，实际：{}", report.summary());

    // 改一个字节：两侧必须同时报，都不许放行。
    let victim = probe.erb_root.join("inc_a.ERB");
    let keep = std::fs::read(&victim).expect("读原文件");
    std::fs::write(&victim, "; 只改了字节，没重编\n").expect("写改动");
    let plan = incremental::diff_against_ledger(&ledger, &probe.erb_root).expect("改动后台账比对报红");
    assert_eq!(plan.modified.len(), 1, "台账必须抓到这次改动");
    let report =
        erbcc_rs::freshness::check(&probe.out, &probe.erb_root).expect("改动后新鲜度检查报红");
    let kinds: Vec<&str> = report.issues.iter().map(|i| i.kind.as_str()).collect();
    println!("FRESH 改动后：ok={} issues={:?}", report.ok, kinds);
    assert!(!report.ok, "改源未重编必须判红");
    assert!(
        kinds.contains(&erbcc_rs::freshness::KIND_SOURCE_CHANGED),
        "新鲜度应报 SOURCE_CHANGED，实为 {kinds:?}"
    );
    assert!(!incremental::probe_stability(&plan).unwrap());
    // 还原：基准探针会被别的用例复用。
    std::fs::write(&victim, keep).expect("还原基准语料");
}

// ======================================================================
// 全量档（默认不跑：一趟 ≈29 s、峰值堆 ≈3.4 GiB、产物 ≈313 MiB）
// ======================================================================

/// 全量语料上的真实变更集读数 + 解析层账面（AGENTS.md §5：这条进 `--ignored`）。
///
/// 跑法：
/// ```text
/// export JAVA_HOME='<工具链 JDK>'; export PATH="$JAVA_HOME/bin:$PATH"
/// export ERATW_CORPUS='<游戏根>/ERB'
/// # 台账默认取 tools/erbcc/build/erbc-out/erbc.v1/manifest.txt，可用 ERATW_INCREMENTAL_LEDGER 指别处
/// cargo test --release --test incremental_feasibility -- --ignored --nocapture
/// ```
#[test]
#[ignore = "全量语料：≈29 s/趟、峰值堆 ≈3.4 GiB、产物 ≈313 MiB；只看真实变更集与账面读数"]
fn 全量语料_真实变更集与解析层账面() {
    let corpus = std::env::var("ERATW_CORPUS")
        .expect("缺 ERATW_CORPUS（必须指到 …/ERB 子树，AGENTS.md §5）");
    assert!(Path::new(&corpus).is_dir(), "ERATW_CORPUS 不是目录：{corpus}");
    let ledger = std::env::var("ERATW_INCREMENTAL_LEDGER").unwrap_or_else(|_| {
        repo_root()
            .join("tools/erbcc/build/erbc-out/erbc.v1/manifest.txt")
            .to_string_lossy()
            .into_owned()
    });
    let ledger = PathBuf::from(ledger);
    assert!(ledger.is_file(), "台账不存在：{ledger:?}");

    let started = std::time::Instant::now();
    let plan = incremental::diff_against_ledger(&ledger, Path::new(&corpus)).expect("台账比对报红");
    println!(
        "LEDGER-FULL modified={} added={} removed={} 台账+磁盘内容寻址耗时 {} ms",
        plan.modified.len(),
        plan.added.len(),
        plan.removed.len(),
        started.elapsed().as_millis()
    );
    println!(
        "样例 modified={:?} added={:?} removed={:?}",
        plan.modified.first(),
        plan.added.first(),
        plan.removed.first()
    );
    if !plan.is_empty() {
        assert!(
            !incremental::probe_stability(&plan).unwrap(),
            "全量档的真实变更集非空 ⇒ 必须判产物层不可行"
        );
    }
    println!(
        "账面（本机同腿实测）：全量 compile 墙钟 28933 ms / 内部读数 23032 ms，3971 文件 110066 切片；\
         同一条腿 --limit 1 墙钟 9528 ms / 内部 8054 ms（符号表全量 + 预扫描全量 + 全量发射编排 \
         的固定段）⇒ 跳过未变更文件的解析的收益上界 ≈15 s/趟。"
    );
}
