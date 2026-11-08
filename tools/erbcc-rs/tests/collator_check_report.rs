//! 闸 2（契约 §7 `rust-symbols`）：`--collator-check` 的**报告文本**与 Kotlin 一致，
//! 外加一条**反向证明**证明闸不是空转。
//!
//! 基准同样是真 Kotlin 工具链产出的原始 stdout 字节
//! （`tests/data/rust-symbols/expected/<档>.stdout.bin`，含 JVM 的 `\r\n`；
//! `tests/data/**` 已钉 binary）。比较口径（与第一波三路一致，写进交付）：
//! 1. 行终止符剔除后比（JVM `println` 走 `\r\n`、Rust 走 `\n`）；
//! 2. 末行 `符号表摘要: <绝对路径>` 打的是**产出机**的路径，不入库比较，
//!    单独核对 Rust 腿那一行的取值（= 用户 `--json` 入参的绝对化结果）。
//!
//! 判定权留在 JVM：装载序、重名胜者、事件执行序、自检结论都由 kernel 给
//! （`Symbols.kt:742-804` 的 `collatorCheck`，比较器 = `Symbols.kt:444-451` 的 ja-JP
//! Collator，ADR-006）。本路只做「读数 → 文本」的渲染，且渲染前先按
//! `Main.kt:251-258` 的字面量把 kernel 的行严格解析回来 —— 结论与判据
//! （[symbols::CollatorCheck::passed]，`Symbols.kt:416-417`）不相等时报红，不照抄。

use erbcc_rs::cmd_symbols;
use erbcc_rs::kernel;
use erbcc_rs::symbols::{self, CollatorCheck, COLLATOR_HEADER, SUMMARY_PREFIX};
use erbcc_rs::types::{current_dir, ErbccError};
use std::path::{Path, PathBuf};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-symbols").join(rel)
}

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&current_dir())
        .unwrap_or_else(|| panic!("找不到仓库根（从 {:?} 往上没有 {}）", current_dir(), erbcc_rs::types::MARKER_REL))
}

fn corpus_dir(tier: &str) -> PathBuf {
    match tier {
        "fixture" => data("corpus"),
        "golden" => manifest_dir().join("../../fixtures/golden"),
        "tiny" => manifest_dir().join("tests/data/rust-manifest/corpus"),
        other => panic!("未知档 {other}"),
    }
}

/// Kotlin 基准：原始 stdout 字节 → 行（终止符已剔）+ 末行摘要里的路径。
struct Baseline {
    body: Vec<String>,
    summary_path: Option<String>,
}

fn read_baseline(name: &str) -> Baseline {
    let path = data(&format!("expected/{name}.stdout.bin"));
    let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("读基准 {path:?} 失败: {e}"));
    let text = String::from_utf8(bytes).unwrap_or_else(|e| {
        panic!(
            "基准 {path:?} 不是合法 UTF-8：JVM 侧产出时必须带 \
             `-Dsun.stdout.encoding=UTF-8`（本机缺省会走 cp936）: {e}"
        )
    });
    // `\r\n` 的行尾由 lines() 剥掉；这里再确认一次没有裸 \r 残留
    let mut lines: Vec<String> = text.lines().map(|l| l.replace('\r', "")).collect();
    let mut summary_path = None;
    if let Some(last) = lines.last() {
        if let Some(rest) = last.strip_prefix(SUMMARY_PREFIX) {
            summary_path = Some(rest.to_string());
            lines.pop();
            if lines.last().map(String::as_str) == Some("") {
                lines.pop();
            }
        }
    }
    Baseline {
        body: lines,
        summary_path,
    }
}

/// 跑一次 Rust 腿，返回（上屏全文, 用户的 `--json` 落点）。
fn rust_run(name: &str, tier: &str, ordinal: bool, collator_check: bool, want_json: bool) -> (String, Option<PathBuf>) {
    let out = manifest_dir().join(format!("target/symbols-report-{name}.json"));
    let _ = std::fs::remove_file(&out);
    let mut args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus_dir(tier).to_string_lossy().into_owned(),
    ];
    if ordinal {
        args.push("--ordinal".to_string());
    }
    if collator_check {
        args.push("--collator-check".to_string());
    }
    if want_json {
        args.push("--json".to_string());
        args.push(out.to_string_lossy().into_owned());
    }
    let env: &kernel::EnvFn = &kernel::os_env;
    let done = cmd_symbols::pipeline(&repo_root(), &args, env, &cmd_symbols::PRODUCTION)
        .unwrap_or_else(|e| panic!("Rust symbols {name} 失败: {e:?}"));
    assert_eq!(done.exit_code, 0, "Rust symbols {name} 退出码不是 0");
    (done.stdout_text, want_json.then_some(out))
}

/// 上屏全文 → 行 + 末行摘要里的路径（与 [read_baseline] 同一口径）。
fn split_rendered(text: &str) -> (Vec<String>, Option<String>) {
    let mut lines: Vec<String> = text.lines().map(|l| l.replace('\r', "")).collect();
    let mut summary_path = None;
    if let Some(last) = lines.last() {
        if let Some(rest) = last.strip_prefix(SUMMARY_PREFIX) {
            summary_path = Some(rest.to_string());
            lines.pop();
            if lines.last().map(String::as_str) == Some("") {
                lines.pop();
            }
        }
    }
    (lines, summary_path)
}

fn assert_body_eq(label: &str, got: &[String], want: &[String]) {
    let g = got.join("\n");
    let w = want.join("\n");
    if g == w {
        return;
    }
    // 逐行点名第一处不同（几百行的全量档也能一眼看出差在哪）
    let mut detail = String::new();
    for i in 0..g.lines().count().max(w.lines().count()) {
        let a = g.lines().nth(i).unwrap_or("<无此行>");
        let b = w.lines().nth(i).unwrap_or("<无此行>");
        if a != b {
            detail.push_str(&format!("第 {} 行：\n  Rust  : {a}\n  Kotlin: {b}\n", i + 1));
            break;
        }
    }
    panic!(
        "{label} 报告文本与 Kotlin 不等（Rust {} 行 vs Kotlin {} 行）\n{detail}\
         Rust 全文 ↓\n{g}\nKotlin 全文 ↓\n{w}",
        got.len(),
        want.len()
    );
}

/// 摘要行单独核对：基准里那一条是**产出机**的路径（不可比），这里只核对
/// Rust 腿打的是「用户 `--json` 入参的绝对化结果」，前缀与 `Main.kt:307` 一致。
fn assert_summary_line(label: &str, got: Option<&str>, out: &Path) {
    let want = format!("{}", erbcc_rs::cmd_compile::kotlin_path_string(out));
    assert_eq!(got.unwrap_or("<无摘要行>"), want.as_str(), "{label} 的摘要行取值不对");
}

// ---------------------------------------------------------------------------
// 实跑对拍：四档 × 两比较器
// ---------------------------------------------------------------------------

#[test]
fn fixture_collator_check_report_parity() {
    let (text, out) = rust_run("fixture-ja-check", "fixture", false, true, true);
    let base = read_baseline("fixture-ja");
    let (got, summary) = split_rendered(&text);
    assert_body_eq("fixture ja-JP + --collator-check", &got, &base.body);
    assert_summary_line("fixture ja-JP", summary.as_deref(), &out.expect("给了 --json"));
    // 这一档必须真的走到 FAIL / 翻转组 / 首个位置差异 三条分支上（否则下面的
    // golden PASS 档一绿就把整块当空转过去了）
    let body = got.join("\n");
    assert!(body.contains(COLLATOR_HEADER), "自检块没上屏：{body}");
    assert!(body.contains("结论: FAIL（需人工核对上面的翻转组）"), "FAIL 分支没覆盖：{body}");
    assert!(body.contains("  翻转组(定义): "), "翻转组(定义) 行没覆盖：{body}");
    assert!(body.contains("  首个位置差异: "), "首个位置差异行没覆盖：{body}");
}

#[test]
fn golden_collator_check_report_parity() {
    let (text, out) = rust_run("golden-ja-check", "golden", false, true, true);
    let base = read_baseline("golden-ja");
    let (got, summary) = split_rendered(&text);
    assert_body_eq("golden ja-JP + --collator-check", &got, &base.body);
    assert_summary_line("golden ja-JP", summary.as_deref(), &out.expect("给了 --json"));
    let body = got.join("\n");
    // golden 档（13 个 GOLDEN_ 文件）两比较器装载序一致 ⇒ PASS 分支 + 三条可选行全缺席
    assert!(body.contains("结论: PASS（两比较器对本语料的重名解析结论一致）"), "{body}");
    for absent in ["  首个位置差异: ", "  翻转组(定义): ", "  翻转组(事件序): "] {
        assert!(!body.contains(absent), "可选行「{absent}」本不该出现：{body}");
    }
}

/// 参数面：`--ordinal` 与默认档的**读数差异**（`Main.kt:183/197` 的 `Collator=` 行
/// 与 `Main.kt:226-244` 的胜者/生效者块都跟着装载序走）。
#[test]
fn ordinal_tier_report_parity_and_difference() {
    for tier in ["fixture", "golden"] {
        let (text, out) = rust_run(&format!("{tier}-ordinal-check"), tier, true, true, true);
        let base = read_baseline(&format!("{tier}-ordinal"));
        let (got, summary) = split_rendered(&text);
        assert_body_eq(&format!("{tier} --ordinal + --collator-check"), &got, &base.body);
        assert_summary_line(&format!("{tier} ordinal"), summary.as_deref(), &out.expect("给了 --json"));
        let body = got.join("\n");
        assert!(
            body.contains("Collator=ORDINAL"),
            "{tier} 档的 `Collator=` 读数行没跟着 --ordinal 走：{body}"
        );
        // 自检块本身与比较器档**无关**（`Symbols.kt:747-748` 固定各跑 JA_JP 与 ORDINAL
        // 两遍），所以两档的块必须逐字相同 —— 这条钉住「自检结论不由 --ordinal 决定」。
        let ja = read_baseline(&format!("{tier}-ja"));
        let block_of = |lines: &[String]| -> Vec<String> {
            let start = lines
                .iter()
                .position(|l| l == COLLATOR_HEADER)
                .unwrap_or_else(|| panic!("基准 {tier} 里没有自检块"));
            let end = lines[start..]
                .iter()
                .position(|l| l.starts_with("结论: "))
                .expect("自检块没有结论行");
            lines[start..=start + end].to_vec()
        };
        assert_eq!(
            block_of(&base.body),
            block_of(&ja.body),
            "{tier} 档 --ordinal 与默认档的自检块应当相同（自检内部固定跑两种比较器）"
        );
    }
    // 两档的**主报告**必须不同（胜者/生效者/fileIndex 随装载序变）——
    // 相同就说明这份语料区分不了比较器，反向证明失去意义。
    let ja = read_baseline("fixture-ja");
    let ordinal = read_baseline("fixture-ordinal");
    assert_ne!(
        ja.body.join("\n"),
        ordinal.body.join("\n"),
        "fixture 档的默认与 --ordinal 报告相同 ⇒ 语料无效"
    );
}

#[test]
fn report_without_collator_check_has_no_block() {
    let (text, out) = rust_run("fixture-ja-nocheck", "fixture", false, false, true);
    let base = read_baseline("fixture-ja-nocheck");
    let (got, summary) = split_rendered(&text);
    assert_body_eq("fixture ja-JP，不带 --collator-check", &got, &base.body);
    assert_summary_line("fixture no-check", summary.as_deref(), &out.expect("给了 --json"));
    let body = got.join("\n");
    assert!(!body.contains(COLLATOR_HEADER), "没要自检却打了块：{body}");
    assert!(!body.contains("结论: "), "没要自检却打了结论：{body}");
}

/// 连 `--json` 都不给的一档：kernel 仍要跑（Rust 需要读数），但摘要行**不许**上屏。
#[test]
fn plain_report_has_no_summary_line() {
    let (text, out) = rust_run("fixture-plain", "fixture", false, false, false);
    assert!(out.is_none(), "没给 --json 却返回了落点");
    let base = read_baseline("fixture-plain");
    assert!(
        base.summary_path.is_none(),
        "基准本身是**不带 --json** 跑出来的，末行不该有摘要行：{:?}",
        base.summary_path
    );
    let (got, summary) = split_rendered(&text);
    assert_body_eq("fixture 纯报告档（无 --json/--collator-check）", &got, &base.body);
    assert!(summary.is_none(), "不该有摘要行，实为 {summary:?}");
    assert!(
        !text.contains(SUMMARY_PREFIX),
        "上屏文本里出现了 `--json` 路径：{text}"
    );
}

/// 读数行的**原样复刻**：`Main.kt:197` 的 `配置: … DebugMode=… Collator=…` 那一行
/// 与 `Main.kt:226-244` 的重名组块，Rust 侧一个字符都不许多改/少改。
#[test]
fn forwarded_reading_lines_are_verbatim() {
    let (text, _) = rust_run("fixture-forward", "fixture", false, true, false);
    let base = read_baseline("fixture-ja");
    let (got, _) = split_rendered(&text);
    // 第 2 行 = `配置: IgnoreCase=… Collator=…`（Main.kt:196-198）
    assert_eq!(got[1], base.body[1], "配置行的措辞被改过");
    assert!(
        got[1].starts_with("配置: IgnoreCase=true SortFilenames=true DebugMode=false Collator=JA_JP 宏 "),
        "配置行前缀不是 `Main.kt:196-198` 的口径：{}",
        got[1]
    );
    // 重名组块逐行（含行尾那个由 flags 拼接留下的尾随空格 —— Kotlin 也有）
    let start = base
        .body
        .iter()
        .position(|l| l == "=== 重名组 ===")
        .expect("基准里没有重名组块");
    let end = base.body[start..]
        .iter()
        .position(|l| l == COLLATOR_HEADER)
        .map(|p| start + p)
        .expect("块没有以自检标题结束");
    for i in start..end {
        assert_eq!(got[i], base.body[i], "重名组块第 {} 行不等", i + 1);
    }
    assert!(
        base.body.iter().any(|l| l.ends_with(") #PRI ")),
        "基准里应有带尾随空格的 flags 行，用来钉 `Main.kt:234-241` 的 buildString 口径"
    );
}

// ---------------------------------------------------------------------------
// 渲染层（不吃 JVM）：把 Main.kt:251-258 的字面量钉死
// ---------------------------------------------------------------------------

fn sample(ordinal_flips: bool) -> CollatorCheck {
    CollatorCheck {
        files: 3818,
        shifted_files: 3396,
        first_divergence: Some("COMMON_HTML.ERB".to_string()),
        duplicate_def_files: 20,
        duplicate_def_inversions: 0,
        duplicate_def_pairs: 190,
        duplicate_groups: 6,
        winner_flips: if ordinal_flips {
            vec!["EVENTFIRST".to_string(), "EVENTLOAD".to_string()]
        } else {
            vec![]
        },
        event_order_flips: if ordinal_flips {
            vec!["EVENTCOM".to_string()]
        } else {
            vec![]
        },
    }
}

#[test]
fn collator_block_render_is_pinned_to_kotlin_literals() {
    // PASS 档（无翻转、无位移差异）
    let mut c = sample(false);
    c.shifted_files = 0;
    c.first_divergence = None;
    c.duplicate_def_files = 0;
    c.duplicate_def_pairs = 0;
    c.duplicate_groups = 0;
    assert_eq!(
        c.lines(),
        vec![
            "=== 比较器自检（ja-JP Collator vs ORDINAL）===".to_string(),
            "文件数 3818  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
            "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
            "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
            "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
        ],
        "PASS 档的行序/双空格/全角括号与 `Main.kt:251-258` 不等"
    );
    // FAIL 档：两条清单行都出现，`joinToString()` 的分隔符是 `, `
    let c = sample(true);
    let lines = c.lines();
    assert_eq!(lines[0], COLLATOR_HEADER);
    assert_eq!(
        lines[1],
        "文件数 3818  绝对位置位移 3396（含被动位移，仅供观察）",
        "`Main.kt:252` 的双空格分隔"
    );
    assert_eq!(lines[2], "  首个位置差异: COMMON_HTML.ERB");
    assert_eq!(
        lines[3],
        "重名定义文件 20 个，两两相对次序翻转 0 / 190 对",
        "`Main.kt:254` 的 ` 个，两两相对次序翻转 I / P 对`"
    );
    assert_eq!(
        lines[4],
        "重名组 6  定义集合/次序翻转 2  事件执行序翻转 1",
        "`Main.kt:255` 的两个计数是**条数**，不是键名"
    );
    assert_eq!(lines[5], "  翻转组(定义): EVENTFIRST, EVENTLOAD");
    assert_eq!(lines[6], "  翻转组(事件序): EVENTCOM");
    assert_eq!(lines[7], "结论: FAIL（需人工核对上面的翻转组）");
    assert_eq!(lines.len(), 8);
}

/// 反向证明之一：让 Rust 侧「假设序数比较」——即把两比较器给出的胜者差异当成
/// 没有差异（或反过来，把 ja-JP 档实际存在的胜者差异抹掉）都会让报告与基准不等。
/// 这里用真基准的读数做底板，证明**判据 + 文本**都跟着翻转集合走。
#[test]
fn reverse_proof_flipped_winners_must_go_red() {
    // ① golden 档基准是 PASS：同一批组键若被判定为胜者翻转（= Rust 擅自换成序数
    //    比较后与 ja-JP 档不一致），报告必须红且逐字节不同于基准。
    let base = read_baseline("golden-ja");
    let parsed = {
        let (text, _) = rust_run("golden-for-reverse", "golden", false, true, false);
        let (_body, _) = split_rendered(&text);
        let lines: Vec<&str> = text.lines().collect();
        let start = lines
            .iter()
            .position(|l| *l == COLLATOR_HEADER)
            .expect("golden 基准块里没有自检标题");
        let end = start
            + lines[start..]
                .iter()
                .position(|l| l.starts_with("结论: "))
                .expect("块没有结论行");
        symbols::parse_collator_check(&lines[start..=end]).expect("解析 golden 自检读数")
    };
    assert!(parsed.passed(), "golden 档基准本应 PASS");
    let mut assumed = parsed.clone();
    // 组键从**同一份读数的 JSON 档**里取（不手抄）：把「两比较器胜者不一致」假成事实
    let summary_json = std::fs::read_to_string(data("expected/golden-ja.json")).expect("golden 基准 JSON");
    let summary = symbols::parse_readings(&summary_json).expect("解析 golden 读数");
    assumed.winner_flips = summary.duplicates.iter().map(|g| g.key.clone()).collect();
    let red = assumed.lines().join("\n");
    assert!(
        red.contains("结论: FAIL（需人工核对上面的翻转组）"),
        "假设序数导致胜者翻转时报告仍报 PASS ⇒ 判据没吃翻转集合：{red}"
    );
    let base_block = base
        .body
        .iter()
        .skip_while(|l| *l != COLLATOR_HEADER)
        .cloned()
        .collect::<Vec<String>>()
        .join("\n");
    assert!(
        !base_block.is_empty() && red != base_block,
        "翻转后的报告与基准相同 ⇒ 报告文本根本不随胜者变（闸空转）"
    );

    // ② 相对次序翻转（`duplicateDefInversions`）单独也能把 PASS 打成 FAIL
    //    （`Symbols.kt:416-417` 的三个合取项，少一个就不是自检通过）
    let mut inv = sample(false);
    inv.duplicate_def_inversions = 1;
    assert!(!inv.passed(), "只翻转相对次序（无清单行）时 passed 仍为真：判据少了一项");
    assert!(
        inv.lines()
            .last()
            .map(|l| l.starts_with("结论: FAIL"))
            .unwrap_or(false),
        "inversions 没有体现在结论里：{:?}",
        inv.lines()
    );

    // ③ 两比较器档的主报告必须不同（fixture 档）：否则「Rust 假设序数」无从检出的说法
    //    不成立 —— 已由 ordinal_tier_report_parity_and_difference 覆盖，这里补一条
    //    「同一份序列化器只会命中各自那一份基准」的字节级断言。
    let (ja_text, _) = rust_run("fixture-ja-reverse", "fixture", false, true, false);
    let ordinal_base = read_baseline("fixture-ordinal");
    let (ja_lines, _) = split_rendered(&ja_text);
    assert_ne!(
        ja_lines.join("\n"),
        ordinal_base.body.join("\n"),
        "Rust 默认档的报告竟与序数档基准相同 ⇒ 比较器差异没落到文本上"
    );
}

// ---------------------------------------------------------------------------
// 解析层：读数与判据必须自洽
// ---------------------------------------------------------------------------

/// 把基准里的自检块原样解析回来再渲染，必须逐行等于基准（报告的闭合性，不吃 JVM）。
#[test]
fn parse_then_render_is_identity_on_every_baseline() {
    for tier in ["fixture-ja", "fixture-ordinal", "golden-ja", "golden-ordinal", "tiny-ja"] {
        let base = read_baseline(tier);
        let start = base
            .body
            .iter()
            .position(|l| l == COLLATOR_HEADER)
            .unwrap_or_else(|| panic!("{tier} 基准里没有自检块"));
        let end = start
            + base.body[start..]
                .iter()
                .position(|l| l.starts_with("结论: "))
                .unwrap_or_else(|| panic!("{tier} 的自检块没有结论行"));
        let slice: Vec<&str> = base.body[start..=end].iter().map(String::as_str).collect();
        let parsed = symbols::parse_collator_check(&slice)
            .unwrap_or_else(|e| panic!("{tier} 自检读数解析失败: {e:?}"));
        assert_eq!(
            parsed.lines(),
            base.body[start..=end].to_vec(),
            "{tier} 的自检块重新渲染后与 Kotlin 不等"
        );
    }
}

#[test]
fn parse_kernel_report_strips_block_and_summary() {
    let raw = std::fs::read_to_string(data("expected/fixture-ja.stdout.bin"))
        .expect("fixture-ja 原始 stdout");
    let parts = symbols::parse_kernel_report(&raw).expect("解析 kernel 报告");
    assert!(parts.collator.is_some(), "自检块没被解析出来");
    assert!(parts.summary_path.is_some(), "摘要行没被识别");
    for banned in [COLLATOR_HEADER, "结论: ", SUMMARY_PREFIX] {
        assert!(
            !parts.forwarded.iter().any(|l| l.starts_with(banned)),
            "转发区里残留了编排层自己会重出的行：{banned}"
        );
    }
    // 转发区 + 自检块 + 摘要行必须能拼回原文（一行都不许多、都不许少）
    let c = parts.collator.clone().expect("上面已断言 Some");
    let text = cmd_symbols::render_report(&parts, true, Some(format!("{SUMMARY_PREFIX}X")))
        .expect("渲染应成功");
    let rebuilt: Vec<String> = text.lines().map(|l| l.to_string()).collect();
    let want: Vec<String> = raw.lines().map(|l| l.replace('\r', "")).collect();
    assert_eq!(rebuilt.len(), want.len(), "行数不等：\n{:#?}\n{:#?}", rebuilt, want);
    for i in 0..want.len() {
        if i == want.len() - 1 {
            assert_eq!(rebuilt[i], format!("{SUMMARY_PREFIX}X"), "摘要行由编排层重出");
        } else {
            assert_eq!(rebuilt[i], want[i], "第 {} 行拼不回原文", i + 1);
        }
    }
    // 没请求自检却带着块回来 = 数据不能凭空消失；请求了却没块 = 无读数可编
    let no_block = symbols::KernelReport {
        forwarded: vec!["=== 符号表 ===".to_string()],
        collator: None,
        summary_path: None,
    };
    let err = cmd_symbols::render_report(&no_block, true, None)
        .err()
        .expect("请求 --collator-check 但无块时必须报红");
    assert!(matches!(err, ErbccError::Io(_)), "{err:?}");
    let mut with_block = no_block.clone();
    with_block.collator = Some(c);
    let rendered = cmd_symbols::render_report(&with_block, false, None).expect("块存在就照打");
    assert!(rendered.contains(COLLATOR_HEADER), "{rendered}");
}

#[test]
fn parser_rejects_inconsistent_or_drifted_blocks() {
    let cases: Vec<(&str, Vec<String>)> = vec![
        (
            "缺结论行",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 1  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
            ],
        ),
        (
            "结论与判据分叉（有翻转却报 PASS）",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 1  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 1  定义集合/次序翻转 1  事件执行序翻转 0".to_string(),
                "  翻转组(定义): EVENTX".to_string(),
                "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
            ],
        ),
        (
            "计数与清单行条数不符",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 1  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 2  定义集合/次序翻转 2  事件执行序翻转 0".to_string(),
                "  翻转组(定义): ONLYONE".to_string(),
                "结论: FAIL（需人工核对上面的翻转组）".to_string(),
            ],
        ),
        (
            "标题行不对",
            vec![
                "=== 比较器自检 ===".to_string(),
                "文件数 1  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
                "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
            ],
        ),
        (
            "未登记的行",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 1  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
                "  新加的说明行".to_string(),
                "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
            ],
        ),
        (
            "数值位不是整数",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 一二  绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
                "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
            ],
        ),
        (
            "分隔符漂走（单空格）",
            vec![
                COLLATOR_HEADER.to_string(),
                "文件数 1 绝对位置位移 0（含被动位移，仅供观察）".to_string(),
                "重名定义文件 0 个，两两相对次序翻转 0 / 0 对".to_string(),
                "重名组 0  定义集合/次序翻转 0  事件执行序翻转 0".to_string(),
                "结论: PASS（两比较器对本语料的重名解析结论一致）".to_string(),
            ],
        ),
    ];
    for (name, block) in cases {
        let got = symbols::parse_collator_check(&block.iter().map(String::as_str).collect::<Vec<_>>());
        assert!(
            matches!(got, Err(ErbccError::Io(_))),
            "{name} 应报红，实为 {:?}",
            match got {
                Ok(c) => format!("Ok({c:?})"),
                Err(e) => format!("Err({e:?})"),
            }
        );
    }
}

/// 事件执行序翻转那条清单行（`Main.kt:257`）在本仓语料里没出现过（零翻转是
/// ADR-006 的结论），所以它的渲染/解析只在合成档里钉。
#[test]
fn event_order_flip_line_round_trip() {
    let mut c = sample(false);
    c.event_order_flips = vec!["EVENTTRAIN".to_string(), "EVENTSHOP".to_string()];
    let lines = c.lines();
    assert_eq!(lines[lines.len() - 2], "  翻转组(事件序): EVENTTRAIN, EVENTSHOP");
    assert_eq!(lines.last().map(String::as_str), Some(symbols::CONCLUSION_FAIL));
    let back = symbols::parse_collator_check(&lines.iter().map(String::as_str).collect::<Vec<_>>())
        .expect("合成档应可解析");
    assert_eq!(back, c, "清单行解析/渲染不成对");
}

/// 全量语料第二档（基准在 `build/baseline/`，不入库）：报告文本逐行等。
#[test]
fn full_corpus_report_parity_env_gated() {
    let corpus = match std::env::var("ERATW_CORPUS") {
        Ok(v) => PathBuf::from(v),
        Err(_) => {
            eprintln!("SKIP full_corpus_report_parity: 未设 ERATW_CORPUS（第二档需真机全量语料）");
            return;
        }
    };
    if !corpus.is_dir() {
        eprintln!("SKIP full_corpus_report_parity: ERATW_CORPUS 不是目录: {corpus:?}");
        return;
    }
    for ordinal in [false, true] {
        let tag = if ordinal { "ordinal" } else { "ja" };
        let baseline = manifest_dir().join(format!("build/baseline/symbols/corpus-{tag}.stdout.bin"));
        assert!(
            baseline.is_file(),
            "ERATW_CORPUS 已设但缺 Kotlin 基准 {baseline:?}（先跑 symbols --corpus … --collator-check）"
        );
        let bytes = std::fs::read(&baseline).expect("读全量基准");
        let text = String::from_utf8(bytes).expect("全量基准应是 UTF-8");
        let mut want: Vec<String> = text.lines().map(|l| l.replace('\r', "")).collect();
        if want.last().map(|l| l.starts_with(SUMMARY_PREFIX)).unwrap_or(false) {
            want.pop();
            if want.last().map(String::as_str) == Some("") {
                want.pop();
            }
        }
        let mut args: Vec<String> = vec![
            "--corpus".to_string(),
            corpus.to_string_lossy().into_owned(),
            "--collator-check".to_string(),
        ];
        if ordinal {
            args.push("--ordinal".to_string());
        }
        let env: &kernel::EnvFn = &kernel::os_env;
        let done = cmd_symbols::pipeline(&repo_root(), &args, env, &cmd_symbols::PRODUCTION)
            .unwrap_or_else(|e| panic!("全量 corpus-{tag} 失败: {e:?}"));
        assert_eq!(done.exit_code, 0);
        let (got, _) = split_rendered(&done.stdout_text);
        assert_body_eq(&format!("corpus-{tag} 全量报告"), &got, &want);
    }
}
