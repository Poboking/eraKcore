//! 闸 1（契约 §7 `rust-symbols`）：**同一份语料，Kotlin `erbcc symbols --json` 与
//! Rust 序列化层逐字节相同**。
//!
//! 基准全部由真 Kotlin 工具链产出（`tools/erbcc-rs/tests/data/rust-symbols/expected/*.json`
//! 与 `*.stdout.bin`，`tests/data/**` 已钉 `binary: text=unset`，git 不动字节），
//! **没有任何期望值是手抄/从文档抄的**。基准重生成命令（Haruki 机，逐档跑）：
//!
//! ```text
//! export JAVA_HOME='<jdk-home>'; export PATH="$JAVA_HOME/bin:$PATH"
//! cd eratw-android && ./gradlew --configure-on-demand :tools:erbcc:installDist
//! P="tools/erbcc/build/install/erbcc/lib/*"; B=tools/erbcc-rs/build/sym-probe/base; mkdir -p $B
//! # FIXTURE=tools/erbcc-rs/tests/data/rust-symbols/corpus  GOLDEN=fixtures/golden
//! # TINY=tools/erbcc-rs/tests/data/rust-manifest/corpus
//! # 每档三条：<tier> = fixture-{ja|fixture-ja-nocheck|fixture-ordinal|golden-ja|golden-ordinal|tiny-ja|tiny-ordinal}
//! java -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "$P" \
//!   com.eratw.port.erbcc.MainKt symbols --corpus <语料> [--ordinal] [--collator-check] \
//!   [--json $B/<tier>.json] > $B/<tier>.stdout.bin 2> $B/<tier>.stderr.bin
//! # 末行 `符号表摘要: <绝对路径>` 是产出机路径：跨机重生成后仍要入库比较，
//! # 比较两侧都剔除末两行（空行 + 摘要行），摘要行单独核对（见 read_baseline）。
//! # 全量档（不入库，放 build/baseline/symbols/，env-gated 用例硬 assert 其在场）：
//! #   --corpus "$ERATW_CORPUS" --collator-check --json build/baseline/symbols/corpus-{ja|ordinal}.json
//! ```
//!
//! 跑法：`cargo test --test symbols_json_parity`（吃 `ERBCC_CLASSPATH` 或
//! `tools/erbcc/build/install/erbcc/lib`，即先 `./gradlew :tools:erbcc:installDist`；
//! JVM 侧找不到 `java` 会报 `program not found`，那是环境缺 `JAVA_HOME`、不是本路口径问题）。
//!
//! 移植面 = 「解析 kernel 读数 → 按 Kotlin 的字节布局重新序列化」：
//! 装载序/胜者判定留在 JVM（ADR-006），Rust 只序列化，所以字段序、`, ` 分隔、
//! 条件字段、缩进、转义表任何一处漂了都会在这里红。
//!
//! 反向证明（`reverse_proof_*`）：同一份 Rust 序列化器对 **ja-JP 档**与 **序数档**
//! 的基准只会命中各自那一份 —— 若 Rust 侧擅自把装载序换成序数比较，
//! ja-JP 档当场红（闸不是空转）。

use erbcc_rs::cmd_symbols;
use erbcc_rs::symbols;
use erbcc_rs::types::{ErbccError, Outcome};
use std::path::PathBuf;

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-symbols").join(rel)
}

/// 各档语料（小档全部自足、不吃 `ERATW_CORPUS`）。
fn corpus_dir(tier: &str) -> PathBuf {
    match tier {
        // 本路合成语料：重名/事件桶/五面 flag/arg 计数/#LOCALSIZE/dimSpecs
        // （dims 原文含引号与反斜杠、initializer 含 TAB 与 0x01 控制字符）/
        // 非 ASCII 键与文件名/子目录，全在这一档里。
        "fixture" => data("corpus"),
        // 官方 golden 语料（13 个 GOLDEN_*.ERB）：两比较器装载序一致 ⇒ PASS 档
        "golden" => manifest_dir().join("../../fixtures/golden"),
        // rust-manifest 路的 3 文件语料：**零重名** ⇒ `duplicates: []` 的空档布局
        "tiny" => manifest_dir().join("tests/data/rust-manifest/corpus"),
        other => panic!("未知档 {other}"),
    }
}

fn expected_json(tier: &str, ordinal: bool) -> PathBuf {
    data("expected").join(format!(
        "{}-{}.json",
        tier,
        if ordinal { "ordinal" } else { "ja" }
    ))
}

/// 跑一次 Rust 腿，返回落盘的 JSON 字节。
fn rust_json(tier: &str, ordinal: bool, out_name: &str) -> Outcome<Vec<u8>> {
    let out = manifest_dir().join("target").join(format!("symbols-out-{out_name}.json"));
    let _ = std::fs::remove_file(&out);
    let mut args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus_dir(tier).to_string_lossy().into_owned(),
    ];
    if ordinal {
        args.push("--ordinal".to_string());
    }
    args.push("--json".to_string());
    args.push(out.to_string_lossy().into_owned());
    let code = cmd_symbols::execute(&args)?;
    if code != 0 {
        return Err(ErbccError::Parity(format!("Rust symbols 退出码 {code}（应 0）")));
    }
    std::fs::read(&out)
        .map_err(|e| ErbccError::Io(format!("读 Rust 产物 {out:?} 失败: {e:?}")))
}

/// 首个差异的字节偏移 + 两侧上下文（几百 KB 的全量档也要能一眼看出差在哪一行）。
fn first_diff(got: &[u8], want: &[u8]) -> String {
    let n = got.len().min(want.len());
    let mut i = 0;
    while i < n && got[i] == want[i] {
        i += 1;
    }
    let ctx = |b: &[u8]| -> String {
        let from = i.saturating_sub(60);
        String::from_utf8_lossy(&b[from..(i + 60).min(b.len())]).into_owned()
    };
    format!(
        "首个差异在第 {i} 字节（Rust {} 字节 vs Kotlin {} 字节）\n  Rust  ⋮{}\n  Kotlin⋮{}",
        got.len(),
        want.len(),
        ctx(got),
        ctx(want)
    )
}

fn assert_bytes_eq(label: &str, got: &[u8], want: &[u8]) {
    assert_eq!(
        got.len(),
        want.len(),
        "{label} 字节数不等：\n{}",
        first_diff(got, want)
    );
    assert!(got == want, "{label} 逐字节不等：\n{}", first_diff(got, want));
}

fn bytes_or_panic(label: &str, r: Outcome<Vec<u8>>) -> Vec<u8> {
    match r {
        Ok(v) => v,
        Err(e) => panic!("{label} 失败: {e:?}"),
    }
}

// ---------------------------------------------------------------------------
// 闸 1：三档语料 × 两档比较器，Kotlin 基准 vs Rust 序列化 —— 逐字节
// ---------------------------------------------------------------------------

#[test]
fn fixture_tier_json_parity() {
    for ordinal in [false, true] {
        let name = format!("fixture-{}", if ordinal { "ordinal" } else { "ja" });
        let got = bytes_or_panic(&name, rust_json("fixture", ordinal, &name));
        let want = std::fs::read(expected_json("fixture", ordinal))
            .unwrap_or_else(|e| panic!("读基准 {:?} 失败: {e:?}", expected_json("fixture", ordinal)));
        assert_bytes_eq(&name, &got, &want);
        // 落盘字节必须是 UTF-8（Kotlin `Files.writeString` 无 BOM）
        assert!(!got.starts_with(&[0xEF, 0xBB, 0xBF]), "{name} 不应带 BOM");
    }
}

#[test]
fn golden_tier_json_parity() {
    for ordinal in [false, true] {
        let name = format!("golden-{}", if ordinal { "ordinal" } else { "ja" });
        let got = bytes_or_panic(&name, rust_json("golden", ordinal, &name));
        let want = std::fs::read(expected_json("golden", ordinal)).expect("读 golden 基准");
        assert_bytes_eq(&name, &got, &want);
    }
}

#[test]
fn tiny_tier_json_parity_empty_duplicates() {
    for ordinal in [false, true] {
        let name = format!("tiny-{}", if ordinal { "ordinal" } else { "ja" });
        let got = bytes_or_panic(&name, rust_json("tiny", ordinal, &name));
        let want = std::fs::read(expected_json("tiny", ordinal)).expect("读 tiny 基准");
        assert_bytes_eq(&name, &got, &want);
        // 零重名档的布局（`Main.kt:266` 的 `[\n` + `Main.kt:304` 的 `  ]\n}\n`）
        let text = String::from_utf8(got.clone()).expect("tiny 基准应是 UTF-8");
        assert!(
            text.contains("\"duplicates\": [\n  ]\n}\n"),
            "空 duplicates 的布局不是 `Main.kt:266/304` 的形状：\n{text}"
        );
    }
}

// ---------------------------------------------------------------------------
// 反向证明：换比较器就必须红
// ---------------------------------------------------------------------------

#[test]
fn reverse_proof_wrong_comparator_assumption_goes_red() {
    let ja = std::fs::read(expected_json("fixture", false)).expect("fixture ja 基准");
    let ordinal = std::fs::read(expected_json("fixture", true)).expect("fixture ordinal 基准");
    // ① 两份基准本身必须不同（否则下面「只会命中各自那一份」是空话）。
    //    语料里 Z_FIRST/a_second 在 ja-JP 与序数下装载序相反 ⇒ fileIndex/defs 次序都不同。
    assert!(
        ja != ordinal,
        "fixture 的 ja-JP 与序数基准相同 ⇒ 语料没能区分比较器，反向证明失去意义"
    );
    // ② Rust 默认档必须命中 ja-JP、**且不等于**序数基准；
    //    带 --ordinal 时反之。即「Rust 侧假设序数比较」会当场红。
    let got_ja = bytes_or_panic("reverse-ja", rust_json("fixture", false, "reverse-ja"));
    let got_ordinal = bytes_or_panic("reverse-ordinal", rust_json("fixture", true, "reverse-ordinal"));
    assert_bytes_eq("Rust 默认档 vs Kotlin ja-JP 基准", &got_ja, &ja);
    assert_bytes_eq("Rust --ordinal vs Kotlin 序数基准", &got_ordinal, &ordinal);
    assert!(
        got_ja != ordinal,
        "Rust 默认档竟与序数基准相同 ⇒ 比较器差异没有落到字节上"
    );
    assert!(
        got_ordinal != ja,
        "Rust --ordinal 竟与 ja-JP 基准相同 ⇒ --ordinal 没传进 kernel"
    );
    // ③ 差异必须落在「装载序」字段上，不是只落在 collator 那个字符串上
    //    （golden 档才是「只差 collator」的对照：见 file_index_differs_on_fixture）。
    let summary_ja = symbols::parse_readings(&String::from_utf8_lossy(&ja)).expect("ja 读数");
    let summary_ordinal = symbols::parse_readings(&String::from_utf8_lossy(&ordinal)).expect("序数读数");
    let idx = |s: &symbols::SymbolSummary| -> Vec<(String, String, i64)> {
        s.duplicates
            .iter()
            .flat_map(|g| {
                g.defs
                    .iter()
                    .map(|d| (g.key.clone(), d.file.clone(), d.file_index))
            })
            .collect()
    };
    assert_ne!(
        idx(&summary_ja),
        idx(&summary_ordinal),
        "两档的 (组键, 定义文件, fileIndex) 序列竟相同：装载序没有进入读数"
    );
}

/// golden 档是「两比较器装载序相同」的对照：差异只允许落在 `collator` 字段上
/// （与 fixture 档互为反证，钉住「差异确实来自比较器」而不是来自序列化器抖动）。
#[test]
fn golden_tiers_differ_only_in_collator_field() {
    let ja = String::from_utf8(std::fs::read(expected_json("golden", false)).expect("golden ja"))
        .expect("UTF-8");
    let ordinal =
        String::from_utf8(std::fs::read(expected_json("golden", true)).expect("golden ordinal"))
            .expect("UTF-8");
    assert_ne!(ja, ordinal, "golden 两档基准应至少差一个 collator 值");
    let replaced = ja.replace("\"collator\": \"JA_JP\"", "\"collator\": \"ORDINAL\"");
    assert_eq!(
        replaced, ordinal,
        "golden 档两比较器的差异应**只有** collator 字段（13 个 GOLDEN_ 文件名在两种比较器下序一致）"
    );
}

// ---------------------------------------------------------------------------
// 序列化层自身（不吃 JVM，秒级）
// ---------------------------------------------------------------------------

/// 每份入库基准都必须满足 `json_summary(基准) == 基准`（解析 + 重序列化的闭合性）。
/// 这条不成立时上面三档实跑必然红，所以它是最快的回归哨。
#[test]
fn serializer_round_trips_every_recorded_baseline() {
    let tiers = [
        "fixture-ja",
        "fixture-ordinal",
        "golden-ja",
        "golden-ordinal",
        "tiny-ja",
        "tiny-ordinal",
    ];
    for tier in tiers {
        let path = data(&format!("expected/{tier}.json"));
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("读基准 {path:?} 失败: {e:?}"));
        let got = symbols::json_summary(&text)
            .unwrap_or_else(|e| panic!("{tier} 序列化失败: {e:?}"));
        assert_bytes_eq(tier, got.as_bytes(), text.as_bytes());
    }
}

/// 转义表逐分支钉 `Main.kt:1098-1112` 的 `jstr`。
#[test]
fn jstr_escape_table_pinned_to_kotlin() {
    let cases: &[(&str, &str)] = &[
        ("", "\"\""),
        ("plain", "\"plain\""),
        ("带引号\"", "\"带引号\\\"\""),
        ("反斜杠\\目录", "\"反斜杠\\\\目录\""),
        ("换\n行", "\"换\\n行\""),
        ("回车\r行", "\"回车\\r行\""),
        ("制\t表", "\"制\\t表\""),
        // 控制字符走 `\u%04x`，**小写**十六进制、恒 4 位
        ("\u{0}nul", "\"\\u0000nul\""),
        ("\u{1}", "\"\\u0001\""),
        ("\u{1f}", "\"\\u001f\""),
        // 0x7F（DEL）不转义：Kotlin 的判据是 `c < ' '`
        ("\u{7f}", "\"\u{7f}\""),
        // `/` 不转义（Windows 相对路径里的 `/` 与 URL 都原样）
        ("sub/C_サブ.ERB", "\"sub/C_サブ.ERB\""),
        // 非 ASCII 原样输出（不做 \u 化）
        ("日本語", "\"日本語\""),
        // 真实语料里出现过的一整串：`[VARSIZE("RELATION")]`
        ("[VARSIZE(\"RELATION\")]", "\"[VARSIZE(\\\"RELATION\\\")]\""),
    ];
    for (input, want) in cases {
        let got = symbols::jstr(input);
        assert_eq!(&got, want, "jstr({input:?}) 与 Kotlin `Main.kt:1099-1108` 不等");
    }
}

/// keywords 是**不走 jstr** 的直拼（`Main.kt:283-286` 的 `"\"$k\""`），
/// collator 同样直拼（`Main.kt:266`）——本侧必须保持不转义，否则枚举名之外的值会漂。
#[test]
fn keywords_and_collator_are_emitted_unescaped() {
    let summary = symbols::SymbolSummary {
        stats: symbols::Stats {
            total_defs: 1,
            unique_names: 1,
            duplicate_names: 1,
            non_event_duplicates: 1,
            event_duplicates: 0,
            shadowed_defs: 0,
            method_defs: 0,
            collator: "JA_JP".to_string(),
        },
        duplicates: vec![symbols::DuplicateGroup {
            key: "K".to_string(),
            is_event: false,
            defs: vec![
                symbols::LabelDef {
                    file: "A.ERB".to_string(),
                    line: 1,
                    file_index: 1,
                    arg_count: 0,
                    arg_int_count: 0,
                    arg_str_count: 0,
                    local_size: 0,
                    locals_size: 0,
                    dim_specs: vec![symbols::DimSpec {
                        name: "n".to_string(),
                        is_str: false,
                        dims: vec![],
                        keywords: vec!["CONST".to_string(), "DYNAMIC".to_string()],
                        initializer: None,
                    }],
                    method: false,
                    only: false,
                    pri: false,
                    later: false,
                    single: false,
                },
                // 条件字段：只有 true 才写，且顺序固定 method→only→pri→later→single
                symbols::LabelDef {
                    file: "B.ERB".to_string(),
                    line: 2,
                    file_index: 2,
                    arg_count: 0,
                    arg_int_count: 0,
                    arg_str_count: 0,
                    local_size: 0,
                    locals_size: 0,
                    dim_specs: vec![],
                    method: false,
                    only: false,
                    pri: true,
                    later: true,
                    single: false,
                },
            ],
        }],
    };
    let text = symbols::to_json(&summary);
    assert!(
        text.contains("\"keywords\": [\"CONST\", \"DYNAMIC\"]"),
        "keywords 分隔/写法不是 `Main.kt:283-286`：{text}"
    );
    assert!(
        text.contains(", \"pri\": true, \"later\": true}"),
        "条件字段的顺序不是 `Main.kt:294-298`：{text}"
    );
    assert!(
        !text.contains("\"dimSpecs\": []"),
        "defs[1].dimSpecs 为空时不该写出这个键（`Main.kt:273` 的判据是非空才写）：{text}"
    );
    assert!(
        text.ends_with("  ]\n}\n"),
        "收尾不是 `Main.kt:304` 的「两空格 + 右中括号 + 换行 + 右大括号 + 换行」：{text:?}"
    );
    // count 恒等 defs 长度（`Main.kt:268`）
    assert!(text.contains("\"count\": 2, \"defs\": ["), "{text}");
    // 自反：这份手搓结构也要能被解析器原样读回
    let back = symbols::parse_readings(&text).expect("自反解析");
    assert_eq!(back, summary, "to_json 的产物 parse_readings 读不回来（序列化/解析不成对）");
}

// ---------------------------------------------------------------------------
// 解析器的严格性：读数漂了必须红，不许静默丢字段
// ---------------------------------------------------------------------------

fn stats_only_json(extra_top: &str) -> String {
    format!(
        "{{\n  \"stats\": {{\"totalDefs\": 1, \"uniqueNames\": 1, \"duplicateNames\": 0, \
         \"nonEventDuplicates\": 0, \"eventDuplicates\": 0, \"shadowedDefs\": 0, \
         \"methodDefs\": 0, \"collator\": \"JA_JP\"}},\n  \"duplicates\": [\n  ]{extra_top}\n}}\n"
    )
}

#[test]
fn parser_rejects_drifted_readings() {
    let bad: Vec<(&str, String)> = vec![
        (
            "顶层未知字段",
            stats_only_json(",\n  \"futureField\": 1"),
        ),
        (
            "缺 stats 字段",
            "{\n  \"stats\": {\"totalDefs\": 1},\n  \"duplicates\": [\n  ]\n}\n".to_string(),
        ),
        (
            "count 与 defs 不等",
            "{\n  \"stats\": {\"totalDefs\": 2, \"uniqueNames\": 1, \"duplicateNames\": 1, \
             \"nonEventDuplicates\": 1, \"eventDuplicates\": 0, \"shadowedDefs\": 1, \
             \"methodDefs\": 0, \"collator\": \"JA_JP\"},\n  \"duplicates\": [\n    \
             {\"key\": \"K\", \"isEvent\": false, \"count\": 9, \"defs\": []}\n  ]\n}\n"
                .to_string(),
        ),
        (
            "def 里的未知字段",
            "{\n  \"stats\": {\"totalDefs\": 2, \"uniqueNames\": 1, \"duplicateNames\": 1, \
             \"nonEventDuplicates\": 1, \"eventDuplicates\": 0, \"shadowedDefs\": 1, \
             \"methodDefs\": 0, \"collator\": \"JA_JP\"},\n  \"duplicates\": [\n    \
             {\"key\": \"K\", \"isEvent\": false, \"count\": 1, \"defs\": [{\"file\": \"A.ERB\", \
             \"line\": 1, \"fileIndex\": 1, \"argCount\": 0, \"argIntCount\": 0, \"argStrCount\": 0, \
             \"localSize\": 0, \"localsSize\": 0, \"brandNew\": true}]}\n  ]\n}\n"
                .to_string(),
        ),
        (
            "类型漂：line 是字符串",
            "{\n  \"stats\": {\"totalDefs\": 2, \"uniqueNames\": 1, \"duplicateNames\": 1, \
             \"nonEventDuplicates\": 1, \"eventDuplicates\": 0, \"shadowedDefs\": 1, \
             \"methodDefs\": 0, \"collator\": \"JA_JP\"},\n  \"duplicates\": [\n    \
             {\"key\": \"K\", \"isEvent\": false, \"count\": 1, \"defs\": [{\"file\": \"A.ERB\", \
             \"line\": \"1\", \"fileIndex\": 1, \"argCount\": 0, \"argIntCount\": 0, \
             \"argStrCount\": 0, \"localSize\": 0, \"localsSize\": 0}]}\n  ]\n}\n"
                .to_string(),
        ),
        (
            "条件字段出现 false",
            stats_only_json_default_def_with_false(),
        ),
        (
            "尾部垃圾",
            format!("{}\nTRAILING", stats_only_json("")),
        ),
        (
            "JSON 未闭合",
            "{\n  \"stats\": {\"totalDefs\": 1".to_string(),
        ),
        ("裸控制字符", "{ \"a\": \"b\u{1}c\" }".to_string()),
        ("孤立代理转义", "{ \"a\": \"\\ud800\" }".to_string()),
        ("非整数数值", stats_only_json("").replace("\"totalDefs\": 1", "\"totalDefs\": 1.5")),
    ];
    for (name, text) in bad {
        let got = symbols::parse_readings(&text);
        assert!(
            matches!(got, Err(ErbccError::Io(_))),
            "{name} 应报红，实为 {:?}",
            match got {
                Ok(_) => "Ok（静默接受了漂移的读数）".to_string(),
                Err(e) => format!("Err({e:?})"),
            }
        );
    }
}

/// `method: false` 这种写法 Kotlin 侧不会出现（`Main.kt:294-298` 只在为真时写），
/// 出现即读数不是本工具产的。
fn stats_only_json_default_def_with_false() -> String {
    "{\n  \"stats\": {\"totalDefs\": 2, \"uniqueNames\": 1, \"duplicateNames\": 1, \
     \"nonEventDuplicates\": 1, \"eventDuplicates\": 0, \"shadowedDefs\": 1, \
     \"methodDefs\": 0, \"collator\": \"JA_JP\"},\n  \"duplicates\": [\n    \
     {\"key\": \"K\", \"isEvent\": false, \"count\": 1, \"defs\": [{\"file\": \"A.ERB\", \
     \"line\": 1, \"fileIndex\": 1, \"argCount\": 0, \"argIntCount\": 0, \"argStrCount\": 0, \
     \"localSize\": 0, \"localsSize\": 0, \"method\": false}]}\n  ]\n}\n"
        .to_string()
}

// ---------------------------------------------------------------------------
// 参数面（`Main.kt:177-189`）：文案与判定顺序
// ---------------------------------------------------------------------------

#[test]
fn arg_surface_matches_kotlin_texts() {
    let cases: Vec<(Vec<&str>, &str)> = vec![
        (vec![], "缺少 --corpus 参数"),
        (vec!["--corpus"], "--corpus 需要参数"),
        (vec!["--json", "x.json"], "缺少 --corpus 参数"),
        (vec!["--corpus", "x", "--json"], "--json 需要参数"),
        (vec!["--corpus", "x", "--bogus"], "无法识别的 symbols 参数: --bogus"),
        (vec!["--corpus", "不存在的目录"], "corpus 不是目录: 不存在的目录"),
        (
            vec!["--corpus", "x", "--ordinal", "--collator-check", "--json"],
            "--json 需要参数",
        ),
    ];
    for (args, want) in cases {
        let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        let got = cmd_symbols::parse_args(&owned);
        let msg = match &got {
            Err(ErbccError::Usage(m)) => m.clone(),
            other => panic!("{args:?} 应是 Usage 报红，实为 {:?}", std::mem::discriminant(other)),
        };
        assert_eq!(msg, want, "`symbols {args:?}` 的报红文案与 Kotlin `Main.kt:177-189` 不等");
    }
    // `--collator-check` / `--ordinal` 是无值开关（`Main.kt:182-183`），不吃下一个参数
    let p = cmd_symbols::parse_args(&[
        "--collator-check".to_string(),
        "--ordinal".to_string(),
        "--corpus".to_string(),
        corpus_dir("fixture").to_string_lossy().into_owned(),
    ])
    .expect("开关前置的入参应解析成功");
    assert!(p.collator_check && p.ordinal);
    // 报红文案里的 corpus 仍**不绝对化**（`Main.kt:189` 的口径），但分隔符过 `types::kotlin_path_text`
    // 归一成 Windows 形态 —— RUST1 `rust-cli-fixes` 把四臂统一到单源后，这里的期望值随之改成反斜杠。
    // 两腿逐字相同的判据由 `tests/cli_surface_diff.rs` 的 `c2_corpus_*` 格钉，别在这里退回旧的分歧形态。
    let p = cmd_symbols::parse_args(&["--corpus".to_string(), "relative/where".to_string()]);
    assert!(
        matches!(&p, Err(ErbccError::Usage(m)) if m == "corpus 不是目录: relative\\where"),
        "corpus 报红应打 Kotlin 的反斜杠形态（未绝对化）：{p:?}"
    );
}

/// kernel 的 argv：三个开关都必须真的传下去，且 classpath/main/子命令名就位。
#[test]
fn kernel_argv_carries_every_flag() {
    let req = cmd_symbols::KernelRequest {
        corpus: "C:/corpus".to_string(),
        readings_json: "C:/tmp/readings.json".to_string(),
        ordinal: true,
        collator_check: true,
    };
    let argv = cmd_symbols::symbols_argv("cp1;cp2", &req);
    let text: Vec<String> = argv.iter().map(|a| a.to_string_lossy().into_owned()).collect();
    assert!(text.contains(&"-cp".to_string()) && text.contains(&"cp1;cp2".to_string()));
    assert!(text.contains(&"com.eratw.port.erbcc.MainKt".to_string()));
    assert!(text.contains(&"symbols".to_string()));
    assert!(text.contains(&"--corpus".to_string()) && text.contains(&"C:/corpus".to_string()));
    assert!(
        text.contains(&"--json".to_string()) && text.contains(&"C:/tmp/readings.json".to_string()),
        "读数档路径没进 argv ⇒ 编排层拿不到读数"
    );
    assert!(text.contains(&"--ordinal".to_string()));
    assert!(text.contains(&"--collator-check".to_string()));
    // 固定选项：堆上限与 UTF-8 输出编码（不带后两条时本机 stdout 是 cp936，中文读数行糊）
    assert!(text.iter().any(|a| a == "-Xmx6g"), "{text:?}");
    assert!(text.iter().any(|a| a == "-Dsun.stdout.encoding=UTF-8"), "{text:?}");

    // 开关关掉时 argv 里就不该出现它们
    let off = cmd_symbols::KernelRequest {
        ordinal: false,
        collator_check: false,
        ..req
    };
    let argv_off: Vec<String> = cmd_symbols::symbols_argv("cp", &off)
        .iter()
        .map(|a| a.to_string_lossy().into_owned())
        .collect();
    assert!(!argv_off.contains(&"--ordinal".to_string()), "{argv_off:?}");
    assert!(!argv_off.contains(&"--collator-check".to_string()), "{argv_off:?}");
}

/// 读数档落点：给了 `--json` 就放同目录（那里必然可写），否则放系统临时目录；
/// 且**永远不是**用户那份 `--json`（否则读数会顶掉产物）。
#[test]
fn readings_tmp_path_never_collides_with_user_output() {
    let parsed = cmd_symbols::parse_args(&[
        "--corpus".to_string(),
        corpus_dir("fixture").to_string_lossy().into_owned(),
        "--json".to_string(),
        manifest_dir()
            .join("target/symbols-out-paths.json")
            .to_string_lossy()
            .into_owned(),
    ])
    .expect("解析应成功");
    let tmp = cmd_symbols::readings_tmp_path(&parsed);
    assert_ne!(tmp, parsed.json_out.clone().expect("用户 json"), "读数档顶掉了用户产物");
    assert_eq!(tmp.parent(), parsed.json_out.as_ref().and_then(|p| p.parent()));
    let no_json = cmd_symbols::parse_args(&[
        "--corpus".to_string(),
        corpus_dir("fixture").to_string_lossy().into_owned(),
    ])
    .expect("解析应成功");
    let tmp2 = cmd_symbols::readings_tmp_path(&no_json);
    assert_eq!(tmp2.parent(), Some(std::env::temp_dir().as_path()), "{tmp2:?}");
}

/// `execute` 的参数错误必须走 `Usage`（`cli::run` 的 `finish` 把它落成「错误: 」+退 2，
/// `Main.kt:163-166`）。本波 `cli.rs` 的 symbols 臂是占位，所以这里只钉到 execute 层。
#[test]
fn execute_reports_usage_not_success() {
    let owned = vec!["--corpus".to_string(), "no/such/dir".to_string()];
    let got = cmd_symbols::execute(&owned);
    assert!(
        matches!(&got, Err(ErbccError::Usage(m)) if m == "corpus 不是目录: no\\such\\dir"),
        "非法参数必须报 Usage（退 2），不许退 0 占位：{got:?}"
    );
}

// ---------------------------------------------------------------------------
// 第二档：全量语料（`$ERATW_CORPUS`，基准在 build/baseline/，不入库）
// ---------------------------------------------------------------------------

#[test]
fn full_corpus_json_parity_env_gated() {
    let corpus = match std::env::var("ERATW_CORPUS") {
        Ok(v) => PathBuf::from(v),
        Err(_) => {
            eprintln!("SKIP full_corpus_json_parity: 未设 ERATW_CORPUS（第二档需真机全量语料）");
            return;
        }
    };
    if !corpus.is_dir() {
        eprintln!("SKIP full_corpus_json_parity: ERATW_CORPUS 不是目录: {corpus:?}");
        return;
    }
    for ordinal in [false, true] {
        let tag = if ordinal { "ordinal" } else { "ja" };
        let baseline = manifest_dir().join(format!("build/baseline/symbols/corpus-{tag}.json"));
        assert!(
            baseline.is_file(),
            "ERATW_CORPUS 已设但缺 Kotlin 基准 {baseline:?}\
             （先跑 `java -cp tools/erbcc/build/install/erbcc/lib/* com.eratw.port.erbcc.MainKt \
             symbols --corpus $ERATW_CORPUS --json …`）",
        );
        let out = manifest_dir().join(format!("target/symbols-out-corpus-{tag}.json"));
        let _ = std::fs::remove_file(&out);
        let mut args: Vec<String> = vec![
            "--corpus".to_string(),
            corpus.to_string_lossy().into_owned(),
        ];
        if ordinal {
            args.push("--ordinal".to_string());
        }
        args.push("--json".to_string());
        args.push(out.to_string_lossy().into_owned());
        let code = cmd_symbols::execute(&args)
            .unwrap_or_else(|e| panic!("全量 corpus-{tag} execute 失败: {e:?}"));
        assert_eq!(code, 0, "全量 corpus-{tag} 应退 0");
        let got = std::fs::read(&out).expect("读 Rust 全量产物");
        let want = std::fs::read(&baseline).expect("读全量基准");
        assert_bytes_eq(&format!("corpus-{tag}"), &got, &want);
    }
    // 反向证明同样成立：全量语料两比较器的读数不同（位移 3396 个文件）
    let ja = std::fs::read(manifest_dir().join("build/baseline/symbols/corpus-ja.json")).unwrap();
    let or = std::fs::read(manifest_dir().join("build/baseline/symbols/corpus-ordinal.json")).unwrap();
    assert_ne!(ja, or, "全量语料的两比较器基准相同 ⇒ 该档失去反向证明力");
}
