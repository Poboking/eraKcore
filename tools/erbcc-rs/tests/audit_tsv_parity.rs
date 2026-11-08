//! **rust-audit 车道的出口闸（1/2）**：同一份语料，纯 Kotlin 的 `erbcc audit --out A.tsv`
//! 与 Rust 编排路径产出的 TSV **逐字节相同**（CONTRACT-RUST1 §7「`--strict`/`--allow` 移植，
//! 符号判定仍走 kernel 桥」+ §0.2「一切以与 Kotlin 逐字节相同为出口」）。
//!
//! ## 两腿都是真跑（不手抄、不从文档抄期望值）
//!
//! - **腿 A（纯 Kotlin）**：直接起 `java -cp <installDist 的 lib> com.eratw.port.erbcc.MainKt
//!   audit --corpus … --game-root … --out A.tsv`。本测试**不复用** `kernel.rs` 的 classpath
//!   解析（口径同 `tests/compile_parity.rs`），免得两条腿共用一个 bug 而闸失明。
//! - **腿 B（Rust 编排）**：`cmd_audit::execute(["audit" 之后的参数…, "--out", B.tsv])`
//!   = `kernel::audit_via_jvm`（audit 腿的 argv 由 `kernel::audit_kernel_argv` 组装）
//!   + `audit::parse_tsv` 读回 + `audit::tsv_text` 复算 + `manifest::atomic_write` 落盘。
//! - **登记基准**：`tests/data/rust-audit/expected/full.tsv`（本机由腿 A 的真 Kotlin 产出登记，
//!   刷新命令见该语料目录的注释与本文件头）。两腿都必须等于它 —— 这条防「两腿一起漂」。
//!
//! ## 为什么 TSV 可以入库当基准（与 `manifest.txt` 不同）
//!
//! `Finding.file` 是**语料根下的相对路径**（`SymbolLinkAudit.kt:329` 的 `u.key.file` ←
//! `CorpusCompile.kt:327` 的 `root.relativize(...)` + `\`→`/`），其余六列都是符号名与
//! 编译期读数 ⇒ 与产出机的绝对路径无关，逐字节可入库。
//!
//! ## 语料为什么自带一份，而不是 `$ERATW_CORPUS` / `fixtures/golden`
//!
//! `CorpusCompile.kt:198` 的 `isCollectionCopy` 把 `GOLDEN_` 前缀排除在编译输入之外 ⇒
//! 拿 `fixtures/golden` 直接当语料会得到空文件集（实测见 `tests/compile_parity.rs` 头注）；
//! 而全量语料一趟 ≈30 s、峰值堆 ≈3.4 GiB，不该进定向闸。故本闸用
//! `tests/data/rust-audit/game/ERB`（8 个文件，专门造出 `VAR_UNBOUND`/`KEYWORD_UNBOUND`
//! 两种来源/`KEYWORD_USER_VAR`/`COMPILE_DIM_WARNING` 四类读数）+ 一个**只放着占位文件**的
//! `CSV/`（`Main.kt:471-473` 要求游戏根必须有 `CSV/`，空目录 git 存不住，故放占位文件；
//! Kotlin 侧只按名字读 CSV，不遍历该目录 ⇒ 占位文件不参与判定，2026-11-01 实测与真空
//! CSV 档产出的 TSV 同字节）。

use erbcc_rs::audit;
use erbcc_rs::cmd_audit;
use erbcc_rs::types::{absolutize, sha256_hex};
use std::path::{Path, PathBuf};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> PathBuf {
    erbcc_rs::types::find_repo_root(&manifest_dir()).expect("仓库根应含 core-vm 标记文件（AGENTS.md §1）")
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-audit").join(rel)
}

/// 闸语料的游戏根（含 `CSV/`）与语料根（`<游戏根>/ERB`）。
fn game_root() -> PathBuf {
    data("game")
}

fn corpus() -> PathBuf {
    data("game/ERB")
}

/// 每条腿各自的输出目录（带 pid，避免并行用例互相覆盖）。
fn gate_out(tag: &str) -> PathBuf {
    let dir = manifest_dir().join(format!("build/audit-gate-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("建 audit 闸输出目录");
    dir
}

/// 本测试自己解析 classpath / java（**不走 `kernel.rs`**，两条腿必须互相独立）。
fn kotlin_tool_classpath() -> String {
    if let Ok(v) = std::env::var("ERBCC_CLASSPATH") {
        let t = v.trim();
        if !t.is_empty() {
            if Path::new(t).is_file() {
                let text = std::fs::read_to_string(t).expect("读 classpath 文件");
                let sep = if cfg!(windows) { ";" } else { ":" };
                let sep_char = sep.chars().next().unwrap();
                return text
                    .split(|c| c == '\n' || c == '\r' || c == sep_char)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(sep);
            }
            return t.to_string();
        }
    }
    let lib = repo_root().join("tools/erbcc/build/install/erbcc/lib");
    assert!(
        lib.is_dir(),
        "腿 A 需要纯 Kotlin 工具链：跑 `./gradlew --configure-on-demand :tools:erbcc:installDist`，\
         或设 ERBCC_CLASSPATH（缺 {lib:?}）"
    );
    format!("{}/*", lib.to_string_lossy())
}

fn java_exe() -> PathBuf {
    match std::env::var("JAVA_HOME") {
        Ok(home) if !home.trim().is_empty() => {
            let exe = Path::new(home.trim()).join(format!("bin/java{}", std::env::consts::EXE_SUFFIX));
            assert!(exe.is_file(), "JAVA_HOME 里没有可执行文件：{exe:?}");
            exe
        }
        _ => PathBuf::from("java"),
    }
}

/// 腿 A：纯 Kotlin 的 `audit`（与 `kernel::audit_kernel_argv` 同堆档、同编码参数，
/// 但 classpath 与本测试各自解析）。
fn run_kotlin_audit(out_tsv: &Path, extra: &[&str]) -> (i32, String, String) {
    let mut cmd = std::process::Command::new(java_exe());
    cmd.args([
        "-Xmx6g",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    ])
    .arg("-cp")
    .arg(kotlin_tool_classpath())
    .arg("com.eratw.port.erbcc.MainKt")
    .arg("audit")
    .arg("--corpus")
    .arg(absolutize(&corpus()))
    .arg("--game-root")
    .arg(absolutize(&game_root()))
    .arg("--out")
    .arg(absolutize(out_tsv))
    .args(extra)
    .current_dir(repo_root());
    let ok = cmd.output().expect("起纯 Kotlin 工具链失败（腿 A）：需要 JAVA_HOME + installDist 产物");
    (
        ok.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&ok.stdout).into_owned(),
        String::from_utf8_lossy(&ok.stderr).into_owned(),
    )
}

/// 腿 B：Rust 编排（真 kernel 桥 + 真 `audit::*` 写出层 + 真原子落盘）。
fn run_rust_audit(out_tsv: &Path, extra: &[&str]) -> i32 {
    let mut args: Vec<String> = vec![
        "--corpus".to_string(),
        corpus().to_string_lossy().into_owned(),
        "--game-root".to_string(),
        game_root().to_string_lossy().into_owned(),
        "--out".to_string(),
        out_tsv.to_string_lossy().into_owned(),
    ];
    for e in extra {
        args.push((*e).to_string());
    }
    cmd_audit::execute(&args)
        .unwrap_or_else(|e| panic!("腿 B（Rust 编排）报红：{e:?}"))
}

fn read_bytes(path: &Path) -> Vec<u8> {
    std::fs::read(path).unwrap_or_else(|e| panic!("读不到 {path:?}：{e}"))
}

fn digest_line(label: &str, path: &Path) -> String {
    let b = read_bytes(path);
    format!("{label} 字节 {} sha256 {}", b.len(), sha256_hex(&b))
}

#[test]
fn 同一语料_kotlin与rust编排的tsv逐字节相同() {
    let out_a = gate_out("full").join("a.tsv");
    let out_b = gate_out("full").join("b.tsv");

    let (rc_a, stdout_a, stderr_a) = run_kotlin_audit(&out_a, &[]);
    assert_eq!(rc_a, 0, "腿 A（纯 Kotlin）应退 0。stderr:\n{stderr_a}\nstdout:\n{stdout_a}");

    let rc_b = run_rust_audit(&out_b, &[]);
    assert_eq!(rc_b, 0, "腿 B（Rust 编排）应退 0");

    let a = read_bytes(&out_a);
    let b = read_bytes(&out_b);
    assert_eq!(
        a, b,
        "TSV 两侧不等（腿 A vs 腿 B）\n{}",
        first_text_diff(&String::from_utf8_lossy(&a), &String::from_utf8_lossy(&b))
    );

    // 登记基准：两腿都等于 Kotlin 真跑登记的字节（防两条腿一起漂）
    let baseline = read_bytes(&data("expected/full.tsv"));
    assert_eq!(a, baseline, "腿 A 漂了：与 tests/data/rust-audit/expected/full.tsv 不等");
    assert_eq!(b, baseline, "腿 B 漂了：与 tests/data/rust-audit/expected/full.tsv 不等");

    println!("{}", digest_line("腿 A a.tsv", &out_a));
    println!("{}", digest_line("腿 B b.tsv", &out_b));
    println!("{}", digest_line("登记基准 expected/full.tsv", &data("expected/full.tsv")));
}

#[test]
fn limit档_逐字节相同且argv把limit透传给kernel() {
    // `kernel::audit_kernel_argv` 的 `--limit` 透传面 + `cmd_audit` 的「降级模式」去重面。
    let out_a = gate_out("limit").join("a.tsv");
    let out_b = gate_out("limit").join("b.tsv");

    let (rc_a, stdout_a, _) = run_kotlin_audit(&out_a, &["--limit", "3"]);
    assert_eq!(rc_a, 0, "腿 A（--limit 3）应退 0。stdout:\n{stdout_a}");
    let rc_b = run_rust_audit(&out_b, &["--limit", "3"]);
    assert_eq!(rc_b, 0, "腿 B（--limit 3）应退 0");

    let a = read_bytes(&out_a);
    assert_eq!(a, read_bytes(&out_b), "--limit 3 档两侧 TSV 不等");
    assert_eq!(a, read_bytes(&data("expected/limit3.tsv")), "--limit 3 档与登记基准不等");
    println!("{}", digest_line("腿 A limit3 a.tsv", &out_a));
}

#[test]
fn 表头与列序逐字照_symbollinkaudit_91_且七列齐全() {
    // 表头 = SymbolLinkAudit.kt:91 的那一行；列序 kind/file/function/line/name/detail/count。
    let text = String::from_utf8(read_bytes(&data("expected/full.tsv"))).expect("基准应是 UTF-8");
    assert_eq!(
        audit::TSV_HEADER,
        "kind\tfile\tfunction\tline\tname\tdetail\tcount",
        "表头必须逐字等于 Kotlin 的字面量（SymbolLinkAudit.kt:91）"
    );
    assert!(text.starts_with(&format!("{}\n", audit::TSV_HEADER)), "基准首行不是登记的表头");

    let rows: Vec<&str> = text.lines().skip(1).collect();
    assert_eq!(rows.len(), 12, "基准应有 12 条读数（8 硬差异 + 4 非红告警）");
    for r in &rows {
        assert_eq!(r.split('\t').count(), 7, "TSV 每行必须 7 列，实为 `{r}`");
    }
    // 表头/行序之外再钉一列序：第 1 列 kind 名必须在 8 类里，且第 4/7 列是整数
    let findings = audit::parse_tsv(&text).expect("基准应能被 Rust 侧解析");
    for f in &findings {
        audit::kind_is_blocking(&f.kind)
            .unwrap_or_else(|e| panic!("基准里出现未知 kind `{}`：{e:?}", f.kind));
    }
}

#[test]
fn 行序等于kotlin的比较器_kind序数_file_line_name_detail() {
    // SymbolLinkAudit.kt:281 的 sortWith 是**唯一**排序；Rust 侧不重排，
    // 所以这里把「基准已按该比较器有序」钉住，同时钉住 FINDING_KINDS 的 ordinal 表序。
    let text = String::from_utf8(read_bytes(&data("expected/full.tsv"))).expect("UTF-8");
    let findings = audit::parse_tsv(&text).expect("解析基准");
    let ordinal = |kind: &str| {
        audit::FINDING_KINDS
            .iter()
            .position(|(k, _)| *k == kind)
            .unwrap_or_else(|| panic!("未知 kind `{kind}`")) as isize
    };
    let key = |f: &audit::Finding| {
        (
            ordinal(&f.kind),
            f.file.clone(),
            f.line,
            f.name.clone(),
            f.detail.clone(),
        )
    };
    for w in findings.windows(2) {
        assert!(
            key(&w[0]) <= key(&w[1]),
            "基准行序不满足 SymbolLinkAudit.kt:281 的比较器：\n  前 = {}\n  后 = {}",
            w[0].kind,
            w[1].kind
        );
    }
    // 表序（ordinal）必须与 Kotlin 枚举声明序一致：VAR_UNBOUND 在最前、COMPILE_DIM_WARNING 在最后
    assert_eq!(audit::FINDING_KINDS.first().unwrap().0, "VAR_UNBOUND");
    assert_eq!(audit::FINDING_KINDS.last().unwrap().0, "COMPILE_DIM_WARNING");
}

#[test]
fn rust写出层复算出的字节与kernel落的字节逐位相同() {
    // `cmd_audit::orchestrate` 内部也做这条断言；这里对着**入库的基准**再做一次，
    // 让写出层的口径不依赖 JVM 也能闸（读回来 → 复算 → 与原文件等）。
    let disk = read_bytes(&data("expected/full.tsv"));
    let text = String::from_utf8(disk.clone()).expect("UTF-8");
    let findings = audit::parse_tsv(&text).expect("解析基准");
    let rust = audit::tsv_text(&findings).expect("复算 TSV");
    assert_eq!(rust.as_bytes(), disk.as_slice(), "复算与原件不等：\n{}", first_text_diff(&text, &rust));
}

#[test]
fn clean转义口径制表符换行回车各换成一个空格() {
    // SymbolAuditReport.clean（SymbolLinkAudit.kt:104）；第 1 列 kind 名**不做** clean（:93）。
    let f = audit::Finding {
        kind: "VAR_UNBOUND".to_string(),
        name: "名字\t带制表\n带换行\r带回车".to_string(),
        function_key: Some("函数\t键".to_string()),
        detail: "明细\n多行".to_string(),
        file: "目录\t/文件.ERB".to_string(),
        line: -1,
        occurrences: 3,
    };
    let text = audit::tsv_text(&[f]).expect("复算");
    let rows: Vec<&str> = text.lines().skip(1).collect();
    assert_eq!(rows.len(), 1);
    let cols: Vec<&str> = rows[0].split('\t').collect();
    assert_eq!(cols.len(), 7, "clean 之后必须仍是 7 列，实为 {:?}", rows[0]);
    assert_eq!(cols[0], "VAR_UNBOUND", "第 1 列 kind 不做 clean（:93）");
    assert_eq!(cols[2], "函数 键", "functionKey 走 clean（:95）");
    assert_eq!(cols[3], "-1", "line 走十进制原样（:96），-1 = 定位不到行（:258）");
    assert_eq!(cols[4], "名字 带制表 带换行 带回车", "name 走 clean（:97）");
    assert_eq!(cols[5], "明细 多行", "detail 走 clean（:98）");
    assert_eq!(cols[6], "3", "count 走十进制原样（:99）");
    // 复算 → 读回来必须等价（clean 是不可逆的，但一次往返必须稳定）
    let back = audit::parse_tsv(&text).expect("读回");
    assert_eq!(audit::tsv_text(&back).expect("二次复算").as_bytes(), text.as_bytes());
}

#[test]
fn 坏档一律报红不静默跳行() {
    // 表头不对 / 列数不对 / line、count 不是整数 / 未知 kind ⇒ 全部 Err（丢行等于把硬差异丢到闸外）。
    assert!(audit::parse_tsv("kind\tfile\tfunction\n").is_err(), "表头不对必须报红");
    assert!(
        audit::parse_tsv("kind\tfile\nVAR_UNBOUND\tA.ERB\tF\t1\tX\td\t1\n").is_err(),
        "表头只有两列必须报红"
    );
    assert!(
        audit::parse_tsv(&format!("{}\nVAR_UNBOUND\tA.ERB\tF\t1\tX\td\n", audit::TSV_HEADER)).is_err(),
        "6 列必须报红"
    );
    assert!(
        audit::parse_tsv(&format!("{}\nVAR_UNBOUND\tA.ERB\tF\tabc\tX\td\t1\n", audit::TSV_HEADER)).is_err(),
        "line 非整数必须报红"
    );
    assert!(
        audit::parse_tsv(&format!("{}\nVAR_UNBOUND\tA.ERB\tF\t1\tX\td\t-1\n", audit::TSV_HEADER)).is_err(),
        "count 负数必须报红（Kotlin 侧 occurrences 恒为正）"
    );
    assert!(
        audit::parse_tsv(&format!("{}\nNOT_A_KIND\tA.ERB\tF\t1\tX\td\t1\n", audit::TSV_HEADER)).is_err(),
        "未知 kind 必须报红，不许按非红处理"
    );
    assert!(!audit::kind_is_blocking("COMPILE_DIM_WARNING").unwrap());
    assert!(audit::kind_is_blocking("VAR_UNBOUND").unwrap());
}

#[test]
fn cli的audit臂已接线不再走未移植占位() {
    // 任务 3：`audit` 分派臂接 `cmd_audit::execute`，参数错误照 Kotlin 退 2，
    // 但**必须**是 cmd_audit 的文案，而不是 rust-scan 车道留下的「本波未移植」占位。
    let exe = env!("CARGO_BIN_EXE_erbcc");
    let ok = std::process::Command::new(exe)
        .args(["audit", "--corpus", "this-corpus-dir-does-not-exist"])
        .current_dir(manifest_dir())
        .output()
        .expect("跑真 erbcc 二进制");
    let stderr = String::from_utf8_lossy(&ok.stdout).into_owned()
        + &String::from_utf8_lossy(&ok.stderr).into_owned();
    assert_eq!(ok.status.code(), Some(2), "corpus 不是目录 ⇒ 照 Main.kt:467 的 err() 退 2，stderr:{stderr}");
    assert!(
        stderr.contains("corpus 不是目录:"),
        "必须走 cmd_audit 的入参校验文案（Main.kt:467），实为：{stderr}"
    );
    assert!(
        !stderr.contains("未移植"),
        "audit 臂不许还停在「本波未移植」占位（任务 3），实为：{stderr}"
    );
}

#[test]
fn roundtrip子路已接线_落的是五列对账明细而不是七列符号表() {
    // 替换 rust-audit 车道留下的「--roundtrip 报未移植」占位断言（那条钉的是骨架态）。
    // 现在的真判据：`Main.kt:476` 的分派把 `--roundtrip` 交给 `cmdAuditRoundTrip`，
    // 它**不写**七列的未绑定符号表，改写五列的对账明细（`SymbolLinkAudit.kt:148-159`），
    // 缺省文件名也从 unresolved-symbols.tsv 换成 roundtrip.tsv（`Main.kt:563`）。
    // 判据用真二进制 + 真 kernel 跑一遍（约 2 s），只看「走的是哪条子路」这件事；
    // 明细字节与退出码的逐项对拍在 tests/roundtrip_parity.rs。
    let dir = gate_out("roundtrip-wired");
    let out = dir.join("rt.tsv");
    let started = std::process::Command::new(env!("CARGO_BIN_EXE_erbcc"))
        .args([
            "audit",
            "--corpus",
            &corpus().to_string_lossy(),
            "--game-root",
            &game_root().to_string_lossy(),
            "--out",
            &out.to_string_lossy(),
            "--roundtrip",
            "--artifact-dir",
            &dir.join("art").to_string_lossy(),
        ])
        .current_dir(manifest_dir())
        .output()
        .expect("跑真 erbcc 二进制");
    let stderr = String::from_utf8_lossy(&started.stderr).into_owned();
    let stdout = String::from_utf8_lossy(&started.stdout).into_owned();
    assert!(!stderr.contains("未移植"), "--roundtrip 不许还停在「本波未移植」占位：\n{stderr}");
    assert_eq!(started.status.code().unwrap_or(-1), 0, "干净小语料的对账应退 0\nstdout:{stdout}\nstderr:{stderr}");
    let text = String::from_utf8(read_bytes(&out)).expect("明细应是 UTF-8");
    assert!(
        text.starts_with("status\tcheck\tin_memory\treloaded\tdetail\n"),
        "--roundtrip 该落五列对账明细（SymbolLinkAudit.kt:150），实为首行：`{}`",
        text.lines().next().unwrap_or_default()
    );
    assert!(
        !text.starts_with(&format!("{}\n", audit::TSV_HEADER)),
        "不该把七列的未绑定符号表当对账明细落出去"
    );
    assert!(stdout.contains("=== 落盘后再装载对账（CMP-79）==="), "报告块（Main.kt:553）");
    assert!(stdout.contains("结论：PASS（内存产物与落盘再装载逐字段一致）"), "绿档结论（Main.kt:573）");
}

/// 第一处差异（行号 + 两侧原样）；报红时直接看出是哪一列不对。
fn first_text_diff(a: &str, b: &str) -> String {
    for (i, (la, lb)) in a.lines().zip(b.lines()).enumerate() {
        if la != lb {
            return format!("第 {} 行不等：\n  A(腿) = {la}\n  B(腿) = {lb}", i + 1);
        }
    }
    let la = a.lines().count();
    let lb = b.lines().count();
    if la != lb {
        return format!("行数不等：{la} vs {lb}");
    }
    "按行全等但字节不等 ⇒ 差在行终止符或编码".to_string()
}
