//! 闸 1：Rust 编译器指纹 == Kotlin 产物 `manifest.txt` 的 `compiler-digest` 行，
//! 且 `compiler-sources` 计数相等。
//!
//! 基准由**真 Kotlin 工具链**产出（`CONTRACT-RUST1.md` §3.2），不是手抄：
//!
//! ```bash
//! ./gradlew --configure-on-demand :tools:erbcc:run \
//!   --args="compile --corpus <小语料> --out <out>"
//! ```
//!
//! 落盘的 `erbc.v1/manifest.txt` 里那两行就是基准本身：
//! * `compiler-digest` 由 `ErbcCompilerDigest.compute` 写（`ErbcArtifacts.kt:198-199`、`:258`）；
//! * `compiler-sources` 由同一处写（`ErbcCompilerDigest.kt:69`、`ErbcArtifacts.kt:259`）。
//!
//! 第二读数（全量语料）走 `build/baseline/full/erbc.v1/manifest.txt`（体积 658 KB，不入库），
//! 由 `:tools:erbcc:compileCorpus -Pcorpus=$ERATW_CORPUS -Pout=build/baseline/full` 产出；
//! 缺该产物时本文件的全量用例只打印跳过 —— **小语料用例始终在跑，闸不因为缺第二档而变松**。

use std::path::{Path, PathBuf};

use erbcc_rs::digest::compiler_digest;
use erbcc_rs::types::{current_dir, find_repo_root, COMPILER_SOURCE_ROOT_REL, ERBC_DIR_NAME};

/// 入库基准（小语料，13 行清单 + 3 个语料文件 + 产物四件套）。
fn data_dir() -> PathBuf {
    current_dir().join("tests/data/rust-manifest")
}

/// 仓库根：测试的工作目录在 `eratw-android/tools/erbcc-rs`，上溯必命中 `MARKER_REL`。
fn repo_root() -> PathBuf {
    find_repo_root(&current_dir())
        .unwrap_or_else(|| panic!("找不到仓库根（{} 应从 cwd 上溯可见）", COMPILER_SOURCE_ROOT_REL))
}

/// 从 Kotlin 落盘的清单里取 `compiler-digest` / `compiler-sources` 两行。
fn kotlin_manifest_lines(manifest: &Path) -> (String, usize) {
    let text = std::fs::read_to_string(manifest)
        .unwrap_or_else(|e| panic!("读不到基准清单 {manifest:?}: {e}"));
    let mut digest: Option<String> = None;
    let mut sources: Option<usize> = None;
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("compiler-digest ") {
            digest = Some(v.trim().to_string());
        } else if let Some(v) = line.strip_prefix("compiler-sources ") {
            sources = Some(
                v.trim()
                    .parse()
                    .unwrap_or_else(|_| panic!("基准 compiler-sources 不是整数: {v}")),
            );
        }
    }
    (
        digest.unwrap_or_else(|| panic!("基准清单缺 compiler-digest 行: {manifest:?}")),
        sources.unwrap_or_else(|| panic!("基准清单缺 compiler-sources 行: {manifest:?}")),
    )
}

/// 主闸：Rust 的 digest 与 `compiler-sources` 计数同时等于 Kotlin 清单登记值。
#[test]
fn rust_digest_equals_kotlin_manifest_compiler_digest_small_corpus() {
    let manifest = data_dir().join(ERBC_DIR_NAME).join("manifest.txt");
    let (kotlin_digest, kotlin_sources) = kotlin_manifest_lines(&manifest);

    let got = compiler_digest(&repo_root())
        .unwrap_or_else(|e| panic!("Rust compiler_digest 失败：{e:?}"));

    assert_eq!(
        got.digest, kotlin_digest,
        "compiler-digest 与 Kotlin 基准不等（基准清单 {manifest:?}）。\n\
         期望（Kotlin）: {kotlin_digest}\n\
         实际（Rust）  : {}\n\
         若 core-vm 源码树在基准产出后被改过，这是**预期的红**：重跑基准再对拍\n",
        got.digest
    );
    assert_eq!(
        got.file_count, kotlin_sources,
        "compiler-sources 计数与 Kotlin 基准不等：基准 {kotlin_sources}，Rust {}",
        got.file_count
    );
    // Kotlin 侧的 digest 形态判据（CompilerDigestFreshnessTest.kt:82-85）
    assert_eq!(got.digest.len(), 64, "digest 必须是 64 字符：{}", got.digest);
    assert!(
        got
            .digest
            .chars()
            .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)),
        "digest 必须是小写十六进制：{}",
        got.digest
    );
}

/// 同一棵源码树两次计算逐字节相同（`ErbcCompilerDigest.kt:47` 的确定性判据，
/// 对应 Kotlin 用例 `CompilerDigestFreshnessTest.kt:78-80`）。
#[test]
fn rust_digest_is_deterministic_on_real_tree() {
    let root = repo_root();
    let a = compiler_digest(&root).expect("真实源码树必须能算出指纹");
    let b = compiler_digest(&root).expect("第二次计算同样必须成功");
    assert_eq!(a.digest, b.digest, "同一源码树两次计算必须逐字节相同");
    assert_eq!(a.file_count, b.file_count);
    // 与基准的计数再次对齐（防「只比 digest 不比数量」的漏判）
    let (_, kotlin_sources) = kotlin_manifest_lines(&data_dir().join(ERBC_DIR_NAME).join("manifest.txt"));
    assert_eq!(a.file_count, kotlin_sources);
}

/// 文件集判据：`core-vm/src/main/kotlin` 递归的全部 `.kt` 普通文件（`:42` 的口径 1），
/// 数量必须等于 Kotlin 基准的 `compiler-sources`。
#[test]
fn file_set_is_every_kt_under_compiler_source_root() {
    let root = repo_root();
    let src_root = root.join(COMPILER_SOURCE_ROOT_REL);
    let mut count = 0usize;
    let mut stack = vec![src_root.clone()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).expect("源码根必须可遍历") {
            let path = entry.expect("目录项可读").path();
            if path.is_dir() {
                stack.push(path);
            } else if path.is_file() && path.file_name().map(|n| n.to_string_lossy().ends_with(".kt")).unwrap_or(false) {
                count += 1;
            }
        }
    }
    let got = compiler_digest(&root).expect("指纹计算应成功");
    let (_, kotlin_sources) = kotlin_manifest_lines(&data_dir().join(ERBC_DIR_NAME).join("manifest.txt"));
    assert_eq!(
        got.file_count, count,
        "指纹参与的文件数必须等于 core-vm/src/main/kotlin 下的 .kt 总数"
    );
    assert_eq!(
        count, kotlin_sources,
        "磁盘上的 .kt 总数与 Kotlin 基准 compiler-sources 不等（基准 {kotlin_sources}，磁盘 {count}）：\
         基准产出后 core-vm 改过 ⇒ 重跑 compileCorpus 生成新基准\n"
    );
}

/// 合成树：改一个字节 ⇒ 指纹必变、计数不变（Kotlin 用例
/// `CompilerDigestFreshnessTest.kt:87-90`，两个 `.kt` 的最小仓库）。
#[test]
fn synthetic_tree_digest_is_sensitive_to_one_byte_change() {
    let repo = std::env::temp_dir().join(format!(
        "erbcc-rs-digest-parity-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&repo);
    let write_tree = |body: &str| {
        for rel in [
            "core-vm/src/main/kotlin/com/eratw/port/core/compile/ErbProgramCompiler.kt",
            "core-vm/src/main/kotlin/com/eratw/port/core/code/ErbcWriter.kt",
        ] {
            let p = repo.join(rel);
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        }
        std::fs::write(
            repo.join(
                "core-vm/src/main/kotlin/com/eratw/port/core/compile/ErbProgramCompiler.kt",
            ),
            body.as_bytes(),
        )
        .unwrap();
        std::fs::write(
            repo.join("core-vm/src/main/kotlin/com/eratw/port/core/code/ErbcWriter.kt"),
            b"fun write() = 2\n",
        )
        .unwrap();
    };

    write_tree("fun compile() = 1\n");
    let first = compiler_digest(&repo).expect("合成树必须能算指纹");
    assert_eq!(first.file_count, 2, "参与文件数 = 源码树里的 .kt 个数（Kotlin :81）");

    write_tree("fun compile() = 99\n");
    let changed = compiler_digest(&repo).expect("改内容后仍要能算指纹");
    assert_ne!(
        first.digest, changed.digest,
        "编译器源码改一个字节 digest 必须变（Kotlin 用例 :89）"
    );
    assert_eq!(
        first.file_count,
        changed.file_count,
        "只改内容不改文件集，计数必须不变（Kotlin 用例 :90）"
    );

    // 排序键是「相对仓库根的 `/` 分隔路径」：把一个文件挪到另一个目录 ⇒ 指纹变、计数不变
    let moved_from = repo.join("core-vm/src/main/kotlin/com/eratw/port/core/code/ErbcWriter.kt");
    let moved_to = repo.join("core-vm/src/main/kotlin/com/eratw/port/core/code2/ErbcWriter.kt");
    std::fs::create_dir_all(moved_to.parent().unwrap())
        .expect("目标目录要能建出来（否则这条断言根本没跑到）");
    std::fs::rename(&moved_from, &moved_to).expect("文件挪动必须成功，否则路径判据用例形同虚设");
    let moved = compiler_digest(&repo).expect("挪动目录后仍要能算指纹");
    assert_ne!(
        changed.digest, moved.digest,
        "相对路径参与摘要（u32be(路径长) ‖ 路径），路径变了 digest 必须变"
    );

    let _ = std::fs::remove_dir_all(&repo);
}

/// 失败面：源码树缺失 / 树里没有 `.kt` ⇒ 必须报错，不许返回空哈希冒充「一致」
/// （`ErbcCompilerDigest.kt:81-83` 与 `:91` 的两处 `check`）。
#[test]
fn missing_or_empty_source_tree_is_an_error_not_an_empty_hash() {
    let base = std::env::temp_dir().join(format!("erbcc-rs-digest-fail-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&base);

    // 1) 整棵树不存在
    let no_tree = base.join("no-tree");
    std::fs::create_dir_all(&no_tree).unwrap();
    let err = match compiler_digest(&no_tree) {
        Err(e) => e,
        Ok(r) => panic!(
            "源码树缺失必须报错（不许返回空哈希冒充一致），实际返回 digest={}",
            r.digest
        ),
    };
    let msg = format!("{err:?}");
    assert!(
        msg.contains("编译器源码树不存在"),
        "错误文案要与 Kotlin 同源（ErbcCompilerDigest.kt:82）：{msg}"
    );

    // 2) 树存在但一个 .kt 都没有
    let empty_tree = base.join("empty-tree").join(COMPILER_SOURCE_ROOT_REL);
    std::fs::create_dir_all(empty_tree.join("com/eratw/port/core")).unwrap();
    std::fs::write(
        empty_tree.join("com/eratw/port/core/README.txt"),
        b"not a kotlin file\n",
    )
    .unwrap();
    let err2 = match compiler_digest(&base.join("empty-tree")) {
        Err(e) => e,
        Ok(r) => panic!(
            "源码树里没有 .kt 必须报错（不许把空文件集折成一个哈希），实际 digest={}",
            r.digest
        ),
    };
    let msg2 = format!("{err2:?}");
    assert!(
        msg2.contains("没有 .kt 文件"),
        "错误文案要与 Kotlin 同源（ErbcCompilerDigest.kt:91）：{msg2}"
    );

    let _ = std::fs::remove_dir_all(&base);
}

/// 第二读数：全量语料的基准清单（不入库；缺档时打印跳过，小语料闸不受影响）。
#[test]
fn rust_digest_equals_kotlin_manifest_compiler_digest_full_corpus() {
    let dir = std::env::var("ERBCC_RS_FULL_BASELINE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| current_dir().join("build/baseline/full"));
    let manifest = dir.join(ERBC_DIR_NAME).join("manifest.txt");
    if !manifest.is_file() {
        eprintln!(
            "SKIP(第二读数)：没有全量基准 {manifest:?}\n\
             生成：./gradlew --configure-on-demand :tools:erbcc:compileCorpus \
             -Pcorpus=\"$ERATW_CORPUS\" -Pout=tools/erbcc-rs/build/baseline/full"
        );
        return;
    }
    let (kotlin_digest, kotlin_sources) = kotlin_manifest_lines(&manifest);
    let got = compiler_digest(&repo_root()).expect("Rust compiler_digest 应成功");
    assert_eq!(
        got.digest, kotlin_digest,
        "全量档的 compiler-digest 与小语料档/当前源码树三方必须同一把尺：基准 {kotlin_digest}，Rust {}",
        got.digest
    );
    assert_eq!(got.file_count, kotlin_sources, "全量档 compiler-sources 计数不等");
}
