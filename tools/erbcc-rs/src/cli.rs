use crate::types::{
    current_dir, erbcc_version, find_repo_root, ErbccError, Outcome, EXIT_FAIL, EXIT_OK, EXIT_USAGE,
};

/// USAGE 文案 —— 逐字复刻 Kotlin `Main.kt:49-107` 的 `private const val USAGE`。
///
/// 内容含 `*.erb/*.erh`（即出现 `/*` 字符对），故必须放在**字符串字面量**里而非块注释
/// （AGENTS §2.5：块注释正文出现 `/*` 会开嵌套注释吞掉闭合符）。
/// 结尾随 Kotlin 一样带一个换行（`Main.kt:106` 后、`"""` 前的 `\n`），
/// 再经 `println!` 追加换行，与 Kotlin `println(USAGE)` 的字节输出一致。
///
/// ①（rust-cli-polish）：audit 段的 `--allow` / `退出码` 两行写明「白名单不是合法 UTF-8 ⇒ 退出码 2
/// 拒绝」与「文件不存在不是错误」，对位 Kotlin 的严格解码 `Main.kt:650-663`
/// （`readAllowListText` 走 `CodingErrorAction.REPORT`，坏字节抛 `IllegalStateException`）与
/// `Main.kt:487-491` → `err()`（`Main.kt:163-166` 恒退 2）；缺档提示见 `Main.kt:680`
/// （`readAllowList` 打一行后返回 `AllowList.EMPTY`，不占退出码）。
/// **改这两行时刻意不增减行数**（`Main.kt:150/163/487/650/680` 等行号在全仓各处被点名引用，
/// USAGE 里多一行会把 `Main.kt:107` 之后的所有引用整体错位）。入库基准
/// `tests/data/rust-scan/kotlin-usage.txt` 由**真 Kotlin 工具链** `--help` 的原始 stdout 落盘
/// （body 全 LF + 结尾一个 `\r\n`，即 JVM `println` 本机行终止符；生成命令见交付报告），由
/// `tests/cli_contract.rs::usage_text_is_byte_identical_to_kotlin_body` 逐字节钉住。
pub const USAGE: &str = r#"erbcc - EraBasic 编译器 (eratw-android)

用法:
  erbcc scan --corpus <ERB目录> [--use-index <lazyloadingfiles.dat>] [--rename <_Rename.csv>] [--out <报告目录>]
  erbcc symbols --corpus <ERB目录> [--json <输出文件>]
  erbcc compile --corpus <ERB目录> --out <产物目录> [--limit <N>]
  erbcc check --corpus <ERB目录> [--out <产物目录>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] [--out <TSV文件>] [--limit <N>] [--strict] [--allow <白名单>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] --roundtrip [--artifact-dir <产物目录>] [--limit <N>]
  erbcc --help
  erbcc --version

symbols 选项（@标签符号表，规约见 specs/loader.md 附录 D）:
  --corpus          游戏数据根（ERB 语料所在目录）
  --json            追加写出机器可读的符号表摘要
  --collator-check  比较器自检：ja-JP Collator 与序数比较各跑一遍，对比重名胜者与事件执行序
  --ordinal         改用序数文件名排序（默认 ja-JP Collator，见 ADR-006）

compile 选项（全量语料 → .erbc 产物 + 确定性 manifest，规约见 specs/erbc-format.md）:
  --corpus      游戏数据根（ERB 语料所在目录）
  --out         产物输出目录（默认 tools/erbcc/erbc-out/；落成 <out>/erbc.v1/{program.erbc,program.meta,manifest.txt}）
  --limit       只编译前 N 个文件（调试用；manifest 会注明，正式产物必须全量）
  退出码        0 = 成功并落盘；1 = 编译/构建失败（stderr 逐条给 文件:行号，不落盘）；2 = 参数错误
  侧车          program.meta 是装载期元数据（函数元数据 / #DIM / 标签 / 数据节 / 宏表），
                与 program.erbc 成对分发；装载侧用 ProgramLoader + BootMetadata.read 一起读。

check 选项（产物新鲜度：清单登记的源文件摘要 × 磁盘现状，见 ArtifactFreshness）:
  --corpus      语料根（与编译时同一路径；清单里的相对路径相对它）
  --out         产物目录（`compile --out` 传的那个；缺省 tools/erbcc/erbc-out）
  退出码        0 = 容器/侧车/源文件全对齐；1 = 有新鲜度问题（逐条 stderr，含「改源未重编」）；2 = 参数错误

audit 选项（装载期符号链接审计：解析期使用点 × 运行期真实命名空间）:
  --corpus      语料根（通常 <gameRoot>/ERB）
  --game-root   游戏根（含 CSV/ 与 emuera.config；缺省 = corpus 的父目录）
  --out         未绑定符号 TSV（缺省 tools/erbcc/symbol-audit/unresolved-symbols.tsv）
  --limit       只切片前 N 个文件（调试用）
  --strict      有硬差异时退出码 1（未绑定变量/关键字/#DIM 注册失败等；动态关键字下标不计）
  --allow       白名单 TSV（每行 `<kind>\t<name>` 或 `<kind>\t<functionKey>\t<name>`；# 开头为注释）；不是合法 UTF-8 ⇒ 退出码 2 拒绝（不做有损解码，且在落任何产物之前挡下）；文件不存在不是错误 —— 打一行提示后按空白名单处理
  退出码        0 = 审计完成且（--strict 时）无硬差异；1 = 编译失败或 --strict 下有硬差异；2 = 参数错误（含 `--allow` 白名单不是合法 UTF-8 的拒绝档）

audit --roundtrip（CMP-79：落盘后再装载对账，审计口径改走 .erbc + program.meta 真装载链）:
  --roundtrip      编译 → 落盘 → ProgramLoader + BootMetadata.read 重建会话 → 与内存产物对账；
                   绿的判据 = 侧车模型逐字段 + runtimeGlobals/privateVars/systemVars 三读数 +
                   使用点名字集合哈希 + 容器语句/指令条数全部相等
  --artifact-dir   产物落盘目录（缺省临时目录：绿了自删、红了保留路径供复现）
  --out            对账明细 TSV（缺省 tools/erbcc/symbol-audit/roundtrip.tsv）
  退出码           0 = 对账全绿；1 = 任一项不等（或编译失败，不落盘）

scan 选项:
  --corpus      游戏数据根（ERB 语料所在目录，索引路径相对此目录）
  --use-index   lazyloadingfiles.dat 装载索引（提供 index/union 策略）
  --rename      _Rename.csv 置換名辞書（默认自动探测 <corpus>/../CSV/_Rename.csv）
  --out         报告输出目录（默认 tools/erbcc/scan-report/）

文件集策略（一次运行内全部执行并对比）:
  all    磁盘上全部 *.erb/*.erh
  index  仅 lazyloadingfiles.dat 索引内文件
  union  磁盘与索引的并集
"#;

/// CLI 分发：子命令 → 各路 `execute`。逐项复刻 Kotlin `Main.kt:109-126` 的 `main`。
///
/// - 空参 / `--help` → 打 USAGE 退 0（`Main.kt:111-112`）；
/// - `--version` → 打 `erbcc <引擎版本>` 退 0（`Main.kt:113`，版本串走
///   `types::erbcc_version`，禁止硬编码）；
/// - `scan` → `cmd_scan::execute`（`Main.kt:114`）；`compile`/`check` → 各自 execute
///   （`Main.kt:116-117`）；`audit` → `cmd_audit::execute`（`Main.kt:118`，RUST1-B rust-audit 车道）；
/// - `symbols` → `cmd_symbols::execute`（`Main.kt:115`，RUST1-B rust-symbols 车道，组长落的分派臂）；
/// - 无法识别的参数 → 照 `Main.kt:119-123` 文案退 2。
pub fn run(args: &[String]) -> i32 {
    if args.is_empty() {
        // Main.kt:111
        println!("{USAGE}");
        return EXIT_OK;
    }
    match args[0].as_str() {
        "--help" => {
            println!("{USAGE}");
            EXIT_OK
        }
        "--version" => {
            // Main.kt:113：`erbcc ${Version.VERSION}`。版本无源可读就报错，不硬编码。
            let root = find_repo_root(&current_dir());
            match root.as_ref().map(|r| erbcc_version(r)) {
                Some(Ok(v)) => {
                    println!("erbcc {v}");
                    EXIT_OK
                }
                Some(Err(e)) => {
                    eprintln!("错误: 读取引擎版本失败: {}", err_text(&e));
                    EXIT_FAIL
                }
                None => {
                    eprintln!("错误: 定位不到仓库根（找不到标记文件 {}），版本无源可读、拒绝硬编码",
                        crate::types::MARKER_REL);
                    EXIT_FAIL
                }
            }
        }
        "scan" => finish(crate::cmd_scan::execute(&args[1..])),
        "compile" => finish(crate::cmd_compile::execute(&args[1..])),
        "check" => finish(crate::cmd_check::execute(&args[1..])),
        // audit：RUST1-B / rust-audit 车道接线（`Main.kt:118` 的等价物）。
        // `--roundtrip`（CMP-79）子路由 rust-roundtrip 车道落地，分派在 `cmd_audit` 内部
        // （照 `Main.kt:474` 降级警告在先、`:476` 分派在后的顺序）。
        "audit" => finish(crate::cmd_audit::execute(&args[1..])),
        // symbols：RUST1-B / rust-symbols 车道接线（`Main.kt:115` 的等价物，组长落的分派臂）。
        // 装载序与比较器判定仍在 JVM kernel，本臂只做参数面、读数转发与 JSON 序列化。
        "symbols" => finish(crate::cmd_symbols::execute(&args[1..])),
        _ => {
            // Main.kt:119-123 文案原样
            eprintln!("错误: 无法识别的参数: {}", args.join(" "));
            eprintln!("使用 --help 查看用法。");
            EXIT_USAGE
        }
    }
}

/// 把 `Outcome<i32>` 落成退出码：Ok 原样；`Usage`→2、其余→1（Kotlin 里参数错误经 `err()`
/// 恒退 2、运行期异常退非 0）。
fn finish(result: Outcome<i32>) -> i32 {
    match result {
        Ok(code) => code,
        Err(ErbccError::Usage(msg)) => {
            eprintln!("错误: {msg}"); // Main.kt:164 的 err() 文案
            EXIT_USAGE
        }
        Err(e) => {
            eprintln!("错误: {}", err_text(&e));
            EXIT_FAIL
        }
    }
}

fn err_text(e: &ErbccError) -> String {
    match e {
        ErbccError::NotPorted(name) => format!("{name} 本波未移植"),
        ErbccError::Usage(m) | ErbccError::Io(m) | ErbccError::Parity(m) => m.clone(),
    }
}
