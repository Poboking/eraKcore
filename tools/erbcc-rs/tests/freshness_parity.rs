//! 闸 3：新鲜度判定与 Kotlin `erbcc check` 的**退出码 + 逐条 issue** 一致。
//!
//! 四个档位（好产物 + 三种坏档）的基准 = 真 Kotlin CLI 的原始 stdout 字节
//! （`tests/data/rust-manifest/expected/check-<档>.stdout.bin` + 同名 `.rc`），产出命令：
//!
//! ```bash
//! ./gradlew --configure-on-demand :tools:erbcc:run \
//!   --args="check --corpus <语料根> --out <被检目录>"
//! ```
//!
//! 坏档的**破坏方式也与 Kotlin 用例同源**（不是自创）：
//! * source-changed —— 只改 `01_main.ERB` 内容：`ArtifactFreshnessTest.kt:118-124`；
//! * truncated —— `program.erbc` 截到 `size * 3 / 5`：`ArtifactFreshnessTest.kt:158-162`；
//! * no-digest-line —— 删掉 `compiler-digest` / `compiler-sources` 两行：
//!   `CompilerDigestFreshnessTest.kt:148-150`。
//!
//! 基准里的 `summary()` 第一行带的是**产出时的绝对 out 目录**，用例把这一段换成实际目录后再
//! 逐字节比对（其余部分 —— 条数行、每条 issue 的 `KIND: detail`、顺序、分隔符 —— 不含路径，
//! 因此比的是原文）。退出码判据 = `Main.kt:422` 的 `if (report.ok) 0 else 1`。

use std::path::{Path, PathBuf};

use erbcc_rs::freshness::check;
use erbcc_rs::manifest::atomic_write;
use erbcc_rs::types::{absolutize, current_dir, ERBC_DIR_NAME};

const CASES: [&str; 4] = ["good", "source-changed", "truncated", "no-digest-line"];

fn data_dir() -> PathBuf {
    current_dir().join("tests/data/rust-manifest")
}

/// 工作区放在**仓库内**（`target/freshness-parity`）：`check` 要从被检目录上溯找得到
/// core-vm 源码树（`ArtifactFreshness.kt:276-277` 的 `locateRepoRoot(outDir)`），
/// 放临时目录外侧就只剩「找不到源码树」那一条判据可跑。
fn workspace() -> PathBuf {
    current_dir().join("target/freshness-parity")
}

fn copy_tree(src: &Path, dst: &Path) {
    std::fs::create_dir_all(dst).unwrap();
    for entry in std::fs::read_dir(src).unwrap() {
        let entry = entry.unwrap();
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if from.is_dir() {
            copy_tree(&from, &to);
        } else {
            std::fs::copy(&from, &to).unwrap();
        }
    }
}

/// 搭好某个档位（复制基准产物 + 语料，再按 Kotlin 用例的手法破坏）。
fn build_case(case: &str) -> PathBuf {
    let dir = workspace().join(case);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let data = data_dir();
    copy_tree(&data.join(ERBC_DIR_NAME), &dir.join(ERBC_DIR_NAME));
    let corpus = dir.join("corpus");
    copy_tree(&data.join("corpus"), &corpus);

    match case {
        "good" => {}
        "source-changed" => {
            // ArtifactFreshnessTest.kt:118-124 的 trimIndent() 内容（无尾 LF）
            std::fs::write(
                corpus.join("01_main.ERB"),
                "@SYSTEM_TITLE\nPRINTL HELLO_CHANGED\nPRINTFORML C={G_CONST}",
            )
            .unwrap();
        }
        "truncated" => {
            // ArtifactFreshnessTest.kt:158-162：bytes.copyOf(bytes.size * 3 / 5)
            let p = dir.join(ERBC_DIR_NAME).join("program.erbc");
            let bytes = std::fs::read(&p).unwrap();
            let keep = bytes.len() * 3 / 5;
            atomic_write(&p, &bytes[..keep]).unwrap();
            assert_eq!(keep, 570, "基准容器 950 字节 ⇒ 60% 应是 570（与 Kotlin 读数对齐）");
        }
        "no-digest-line" => {
            // CompilerDigestFreshnessTest.kt:148-150：filterNot 掉两行后整档重写
            let p = dir.join(ERBC_DIR_NAME).join("manifest.txt");
            let text = std::fs::read_to_string(&p).unwrap();
            let kept: Vec<&str> = text
                .lines()
                .filter(|l| !l.starts_with("compiler-digest ") && !l.starts_with("compiler-sources "))
                .collect();
            let mut out = kept.join("\n");
            out.push('\n');
            std::fs::write(&p, out).unwrap();
        }
        other => panic!("未知档位: {other}"),
    }
    dir
}

/// 基准 fixture 的原文，以及产出时用的绝对 out 目录（`summary()` 第一行的前缀）。
fn read_expected_fixture(case: &str) -> (String, String, i32) {
    let path = data_dir()
        .join("expected")
        .join(format!("check-{case}.stdout.bin"));
    let raw = std::fs::read(&path)
        .unwrap_or_else(|e| panic!("缺基准 {path:?}（先按本文件头部的命令用 Kotlin 工具链产出）: {e}"));
    let text = String::from_utf8(raw).expect("Kotlin 的 stdout 必须是 UTF-8");
    let recorded_out_dir = text
        .split_once(": ")
        .unwrap_or_else(|| panic!("基准 summary 第一行不带「<outDir>: 」前缀：{text}"))
        .0
        .to_string();
    let rc_raw = std::fs::read_to_string(
        data_dir()
            .join("expected")
            .join(format!("check-{case}.rc")),
    )
    .expect("缺基准退出码文件 check-<档>.rc");
    (text, recorded_out_dir, rc_raw.trim().parse().expect("rc 不是整数"))
}

/// 一个档位：Rust 的 `summary()`（含逐条 issue 与顺序）与退出码都要与 Kotlin 一致。
fn parity_case(case: &str) {
    let dir = build_case(case);
    let corpus = dir.join("corpus");
    let report = check(&dir, &corpus).unwrap_or_else(|e| panic!("{case}：Rust check 失败 {e:?}"));

    let (expected_text, recorded_out_dir, expected_rc) = read_expected_fixture(case);
    let actual_out_dir = absolutize(&dir).to_string_lossy().to_string();
    let expected = expected_text.replace(&recorded_out_dir, &actual_out_dir);

    // 退出码（Main.kt:422 的 `if (report.ok) 0 else 1`）
    let actual_rc = if report.ok { 0 } else { 1 };
    assert_eq!(
        actual_rc, expected_rc,
        "{case}：退出码与 Kotlin `erbcc check` 不等（Kotlin {expected_rc}，Rust {actual_rc}）\n\
         Rust 侧问题清单：{:?}",
        report
            .issues
            .iter()
            .map(|i| i.to_string())
            .collect::<Vec<_>>()
    );

    // 逐条 issue（条数 + 每条 `KIND: detail` 原文 + 顺序）
    let expected_issue_lines: Vec<&str> = expected
        .lines()
        .skip(1)
        .map(|l| l.strip_prefix("  ").unwrap_or(l))
        .collect();
    let actual_issue_lines: Vec<String> = report
        .issues
        .iter()
        .map(|i| i.to_string())
        .collect();
    assert_eq!(
        actual_issue_lines,
        expected_issue_lines,
        "{case}：逐条 issue 与 Kotlin 不等（期望 {:?}\n 实际 {actual_issue_lines:?}）",
        expected_issue_lines
    );

    // 整段 summary 逐字节（措辞 + 行数文案 + 分隔符）
    assert_eq!(
        report.summary(),
        expected,
        "{case}：summary 措辞与 Kotlin 不等\n期望（Kotlin）:\n{expected}\n实际（Rust）:\n{}\n",
        report.summary()
    );

    eprintln!(
        "{case}：与 Kotlin 一致（rc={actual_rc}，issue {} 条，summary {} 字节）",
        report.issues.len(),
        expected.len()
    );
}

#[test]
fn good_artifact_has_zero_issues() {
    parity_case("good");
    // 好产物还须把**当前**源码树指纹挂上报告（ArtifactFreshness.kt:257 的 compiler?.digest）
    let dir = workspace().join("good");
    let report = check(&dir, &dir.join("corpus")).unwrap();
    assert!(report.ok);
    let manifest = std::fs::read_to_string(data_dir().join(ERBC_DIR_NAME).join("manifest.txt")).unwrap();
    let declared = manifest
        .lines()
        .find_map(|l| l.strip_prefix("compiler-digest "))
        .expect("基准必须有 compiler-digest 行")
        .trim()
        .to_string();
    assert_eq!(
        report.compiler_digest.as_deref(),
        Some(declared.as_str()),
        "当前源码树指纹必须等于清单登记值（否则这条档就不叫「好产物」）"
    );
}

#[test]
fn source_changed_case_matches_kotlin() {
    parity_case("source-changed");
}

#[test]
fn truncated_container_case_matches_kotlin() {
    parity_case("truncated");
}

#[test]
fn missing_compiler_digest_line_case_matches_kotlin() {
    parity_case("no-digest-line");
}

/// 四个档位的退出码组合本身要钉住：只有 good 是 0（Kotlin 侧亦如此，见 `.rc` 基准）。
#[test]
fn only_good_case_exits_zero() {
    let mut seen = Vec::new();
    for case in CASES {
        let (_, _, rc) = read_expected_fixture(case);
        seen.push((case, rc));
    }
    assert_eq!(
        seen,
        vec![("good", 0), ("source-changed", 1), ("truncated", 1), ("no-digest-line", 1)],
        "基准退出码组合变了 ⇒ 说明 Kotlin 侧判据动了，本路的对拍要重新生成"
    );
}

/// 第二读数：全量语料产物（基准不入库，在 `build/baseline/`）。
///
/// 直接对**真产物目录**跑 Rust 的 `check`（只读，不复制 314 MiB 容器），
/// 与该目录的 Kotlin `erbcc check` 原始 stdout（`expected-full-check.bin`）逐字节比。
#[test]
fn full_corpus_artifact_verdict_matches_kotlin() {
    let out_dir = std::env::var("ERBCC_RS_FULL_BASELINE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| current_dir().join("build/baseline/full"));
    let manifest_path = out_dir.join(ERBC_DIR_NAME).join("manifest.txt");
    let expected_candidate = out_dir
        .parent()
        .map(|p| p.join("expected-full-check.bin"))
        .unwrap_or_else(|| current_dir().join("build/baseline/expected-full-check.bin"));
    if !manifest_path.is_file() {
        eprintln!(
            "SKIP(第二读数)：没有全量产物 {manifest_path:?}\n生成：./gradlew --configure-on-demand \
             :tools:erbcc:compileCorpus -Pcorpus=\"$ERATW_CORPUS\" -Pout=tools/erbcc-rs/build/baseline/full"
        );
        return;
    }
    if !expected_candidate.is_file() {
        panic!(
            "全量档缺 Kotlin check 基准 {expected_candidate:?}：跑 \
             `:tools:erbcc:run --args=\"check --corpus <语料根> --out <全量产物>\"` 并把原始 stdout 存成该文件"
        );
    }
    let expected_path = expected_candidate;
    let expected_raw = std::fs::read(&expected_path).unwrap();
    let expected_text = String::from_utf8(expected_raw).expect("Kotlin stdout 必须是 UTF-8");
    let manifest = std::fs::read_to_string(&manifest_path).unwrap();
    let corpus_root = manifest
        .lines()
        .find_map(|l| l.strip_prefix("corpus-root "))
        .expect("全量清单必须有 corpus-root 行")
        .trim()
        .to_string();
    let corpus = PathBuf::from(&corpus_root);
    if !corpus.is_dir() {
        eprintln!("SKIP(第二读数)：基准登记的语料根不存在：{corpus_root}");
        return;
    }

    let report = check(&out_dir, &corpus).unwrap_or_else(|e| panic!("全量档 Rust check 失败：{e:?}"));
    let recorded_out_dir = expected_text.split_once(": ").expect("基准 summary 前缀").0.to_string();
    let actual_out_dir = absolutize(&out_dir).to_string_lossy().to_string();
    let expected = expected_text.replace(&recorded_out_dir, &actual_out_dir);
    assert_eq!(
        report.summary(),
        expected,
        "全量档 summary 与 Kotlin 不等\n期望（Kotlin）:\n{expected}\n实际（Rust）:\n{}\n",
        report.summary()
    );
    assert_eq!(
        report.issues.len(),
        expected.lines().count() - 1,
        "全量档 issue 条数与 Kotlin 不等：Rust {:?}（Kotlin 期望 {} 条）",
        report
            .issues
            .iter()
            .map(|i| i.to_string())
            .collect::<Vec<_>>(),
        expected.lines().count() - 1
    );
    eprintln!(
        "全量档：与 Kotlin 一致（rc={}，issue {} 条，{} 个源文件摘要 + 314 MiB 容器摘要重算）",
        if report.ok { 0 } else { 1 },
        report.issues.len(),
        manifest.lines().filter(|l| l.starts_with("source ")).count()
    );
}
