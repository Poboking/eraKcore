//! RUST1 / rust-container 车道的出口闸：容器头 + 段表解析**收成单源**后的判据面。
//!
//! 三条判据，全部纯字节、不起 JVM：
//! 1. **真产物钉读数**：`tests/data/rust-manifest/erbc.v1/program.erbc`（950 B，v2/9 槽）
//!    与 `build/incremental/base-*/` 那档（2188 B，组长自算读数）的 version/flags/段偏移/
//!    段区间逐项钉死；`build/incremental` 不在时跳过（该档由 incremental_feasibility 编译产生）。
//! 2. **畸形档全套**：合成字节即可，八条判界（magic / version=3 / v1 配 9 槽 / v2 配 7 槽 /
//!    flags 未定义位 / 标志位置位但偏移为 0 / 偏移越文件尾 / 偏移 <10）外加反向自洽、负偏移、
//!    短文件——每条必须 `Err` 且错误文本逐字带 `ErbcDecodeException`（不许返回一个「看起来能用」的头）。
//! 3. **段 2（EXPRESSION_POOL）偏移 0 = 该段不存在**：`section_range` 必须 `Err` 而不是给空区间。
//!
//! 判据权威 = `specs/erbc-format.md` §1.6 / §2 / §3，与 `src/container.rs` 的 doc 同源。

use std::path::{Path, PathBuf};

use erbcc_rs::container::{self, ContainerHeader, SECTION_NAMES};
use erbcc_rs::types::{current_dir, ErbccError};

fn out_msg(e: ErbccError) -> String {
    match e {
        ErbccError::Io(m) | ErbccError::Usage(m) | ErbccError::Parity(m) => m,
        other => format!("(非 Io 形态) {other:?}"),
    }
}

fn read_file(p: &Path) -> Vec<u8> {
    std::fs::read(p).unwrap_or_else(|e| panic!("读 {p:?} 失败：{e}"))
}

fn workspace() -> PathBuf {
    current_dir().join("target/container-sections")
}

/// 合成一个「合规格」的 v2 容器字节（9 槽，flags = HAS_LINES|HAS_STATEMENTS），
/// 畸形用例都在这个模板上改一处——保证每条红都只由那一条判界引起。
fn v2_template(file_len: usize) -> Vec<u8> {
    let mut b = vec![0u8; file_len];
    b[0..4].copy_from_slice(b"ERBC");
    b[4..6].copy_from_slice(&2u16.to_le_bytes()); // version
    b[6..8].copy_from_slice(&0x0005u16.to_le_bytes()); // flags: LINES|STATEMENTS
    b[8..10].copy_from_slice(&9u16.to_le_bytes()); // sectionCount
    let offsets: [u32; 9] = [0, 0, 0, 46, 0, 74, 90, 0, 0];
    for (i, o) in offsets.iter().enumerate() {
        b[10 + i * 4..14 + i * 4].copy_from_slice(&o.to_le_bytes());
    }
    b
}

fn parse_err(bytes: &[u8]) -> String {
    match container::parse(bytes) {
        Ok(h) => panic!(
            "畸形档被放行，返回了一个「看起来能用」的头：version={} flags={:#x} section_count={}",
            h.version, h.flags, h.section_count
        ),
        Err(e) => out_msg(e),
    }
}

// ======================================================================
// 1. 真产物钉读数
// ======================================================================

#[test]
fn manifest_fixture_section_table_is_pinned() {
    let path = current_dir().join("tests/data/rust-manifest/erbc.v1/program.erbc");
    let bytes = read_file(&path);
    assert_eq!(bytes.len(), 950, "基准容器尺寸变了");
    let h = container::parse(&bytes).unwrap_or_else(|e| panic!("真产物必须可解：{}", out_msg(e)));
    assert_eq!(h.version, 2);
    assert_eq!(h.flags, 0x0005, "v2 且只带行号表 + 语句池");
    assert_eq!(h.section_count, 9);
    assert_eq!(
        h.offsets,
        vec![
            None,
            None,
            None,
            Some(46),
            Some(677),
            Some(778),
            Some(910),
            None,
            None
        ],
        "段表读数与基准钉死"
    );
    // 存在的段：区间 = [本段偏移, 下一存在段偏移)，末段到文件长度。
    assert_eq!(h.section_range(3, 950).unwrap(), (46, 677), "STATEMENT_POOL 区间");
    assert_eq!(h.section_range(4, 950).unwrap(), (677, 778), "FUNCTION_TABLE 区间");
    assert_eq!(h.section_range(5, 950).unwrap(), (778, 910), "CODE 区间");
    assert_eq!(h.section_range(6, 950).unwrap(), (910, 950), "末段取文件长度");
    // 不存在的段：偏移 0 = 该段不存在，必须 Err 而不是空区间。
    for absent in [0usize, 1, 2, 7, 8] {
        let err = h
            .section_range(absent, 950)
            .err()
            .unwrap_or_else(|| panic!("段 {absent} 偏移为 0（不存在），section_range 必须 Err"));
        let msg = out_msg(err);
        assert!(
            msg.contains(&format!("段 {absent}")) && msg.contains(&SECTION_NAMES[absent].to_string())
                || msg.contains("不存在"),
            "段 {absent} 的报错要指名道姓：{msg}"
        );
    }
}

/// 组长按段 4 自算的独立读数：base 档 2188 B 与 p2_stradd 变体 2256 B。
/// 这两档由 `incremental_feasibility` 真编译产出（在 `build/incremental/<tag>-<pid>/`），
/// 目录不在就跳过（本闸不起 JVM）；只要在，就必须逐字复现。
#[test]
fn incremental_base_and_stradd_readings_are_reproduced() {
    let base = collect_artifacts("base");
    let stradd = collect_artifacts("p2_stradd");
    if base.is_empty() && stradd.is_empty() {
        eprintln!(
            "SKIP(第二读数)：build/incremental 下没有 base/p2_stradd 产物，先跑 \
             cargo test --test incremental_feasibility"
        );
        return;
    }
    for path in &base {
        let bytes = read_file(path);
        assert_eq!(bytes.len(), 2188, "base 容器尺寸");
        let h = container::parse(&bytes).unwrap_or_else(|e| panic!("base 必须可解：{}", out_msg(e)));
        assert_eq!((h.version, h.flags, h.section_count), (2, 0x001d, 9));
        assert_eq!(
            h.offsets.iter().map(|o| o.unwrap_or(0)).collect::<Vec<u32>>(),
            vec![46, 86, 0, 126, 1191, 1390, 1874, 2002, 2116],
            "base 段偏移与组长读数逐字钉死"
        );
        assert_eq!(h.section_range(5, bytes.len()).unwrap(), (1390, 1874), "base CODE 区间");
        assert_eq!(h.section_range(2, bytes.len()).is_err(), true, "段 2 不存在必须 Err");
    }
    for path in &stradd {
        let bytes = read_file(path);
        assert_eq!(bytes.len(), 2256, "p2_stradd 容器尺寸");
        let h = container::parse(&bytes).unwrap_or_else(|e| panic!("p2_stradd 必须可解：{}", out_msg(e)));
        assert_eq!((h.version, h.flags, h.section_count), (2, 0x001d, 9));
        assert_eq!(
            h.offsets.iter().map(|o| o.unwrap_or(0)).collect::<Vec<u32>>(),
            vec![46, 101, 0, 141, 1206, 1405, 1921, 2057, 2184],
            "p2_stradd 段偏移与组长读数逐字钉死"
        );
        assert_eq!(h.section_range(5, bytes.len()).unwrap(), (1405, 1921), "p2_stradd CODE 区间");
    }
    println!(
        "第二读数：base {} 档（2188 B，CODE [1390,1874) 长 484）、p2_stradd {} 档（2256 B，CODE [1405,1921) 长 516）全部复现",
        base.len(),
        stradd.len()
    );
}

/// 收集 `build/incremental/<tag>-*/out/erbc.v1/program.erbc`（历史 pid 全收，读数应全等）。
fn collect_artifacts(tag: &str) -> Vec<PathBuf> {
    let dir = current_dir().join("build/incremental");
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for entry in rd.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name == tag || name.starts_with(&format!("{tag}-")) {
                let p = entry.path().join("out/erbc.v1/program.erbc");
                if p.is_file() {
                    out.push(p);
                }
            }
        }
    }
    out
}

// ======================================================================
// 2. 合法形态：v1 头 / 尾部多余字节
// ======================================================================

#[test]
fn v1_seven_slot_header_is_valid() {
    // 7 槽模板：头+表 38 字节；段 3/5/6 存在（flags = LINES|STATEMENTS），其余偏移 0。
    let mut b = vec![0u8; 60];
    b[0..4].copy_from_slice(b"ERBC");
    b[4..6].copy_from_slice(&1u16.to_le_bytes());
    b[6..8].copy_from_slice(&0x0005u16.to_le_bytes());
    b[8..10].copy_from_slice(&7u16.to_le_bytes());
    let offsets: [u32; 7] = [0, 0, 0, 38, 46, 0, 50];
    for (i, o) in offsets.iter().enumerate() {
        b[10 + i * 4..14 + i * 4].copy_from_slice(&o.to_le_bytes());
    }
    let h = container::parse(&b).unwrap_or_else(|e| panic!("v1/7 槽必须合法：{}", out_msg(e)));
    assert_eq!((h.version, h.flags, h.section_count), (1, 0x0005, 7));
    assert_eq!(h.section_range(3, 60).unwrap(), (38, 46));
    assert_eq!(h.section_range(6, 60).unwrap(), (50, 60), "末段取文件长度");
    // v1 没有段 7/8：越出段表槽数的下标同样 Err，不给空区间。
    assert!(h.section_range(7, 60).is_err());
}

#[test]
fn trailing_padding_bytes_are_allowed() {
    // §1.5：文件末尾允许多余字节，reader 只按段表寻址。
    let mut b = v2_template(100);
    b.extend_from_slice(&[0xAA; 7]);
    let h = container::parse(&b).unwrap_or_else(|e| panic!("尾部多余字节必须放行：{}", out_msg(e)));
    assert_eq!(h.section_range(6, b.len()).unwrap(), (90, 107), "末段取调用方给的文件长度");
}

// ======================================================================
// 3. 畸形档全套（八条 + 三条补充）
// ======================================================================

#[test]
fn malformed_headers_all_reject_with_decode_exception() {
    let cases: Vec<(&str, Vec<u8>, &str)> = vec![
        // ① magic 错
        {
            let mut b = v2_template(100);
            b[0..4].copy_from_slice(b"XRBC");
            ("magic", b, "magic 不符：期望 ERBC（45 52 42 43），实为 [58 52 42 43]")
        },
        // ② version=3
        {
            let mut b = v2_template(100);
            b[4..6].copy_from_slice(&3u16.to_le_bytes());
            ("version3", b, "不支持的 .erbc 版本 3（本引擎只认 v1/v2，不做降级解释）")
        },
        // ③ v1 头配 9 槽
        {
            let mut b = v2_template(100);
            b[4..6].copy_from_slice(&1u16.to_le_bytes());
            b[6..8].copy_from_slice(&0x0004u16.to_le_bytes());
            ("v1-with-9-slots", b, "段表槽数 9 与 v1 固定的 7 不符")
        },
        // ④ v2 头配 7 槽
        {
            let mut b = v2_template(100);
            b.truncate(10 + 7 * 4);
            b.resize(100, 0);
            b[8..10].copy_from_slice(&7u16.to_le_bytes());
            ("v2-with-7-slots", b, "段表槽数 7 与 v2 固定的 9 不符")
        },
        // ⑤ flags 含 v2 未定义位（0x0020）
        {
            let mut b = v2_template(100);
            b[6..8].copy_from_slice(&0x0025u16.to_le_bytes());
            ("flags-undefined-v2", b, "标志位含 v2 未定义位: 0x25")
        },
        // ⑤b 同一位放在 v1 上同样未定义（掩码按版本选）
        {
            let mut b = v2_template(100);
            b[4..6].copy_from_slice(&1u16.to_le_bytes());
            b[6..8].copy_from_slice(&0x000du16.to_le_bytes());
            ("flags-undefined-v1", b, "标志位含 v1 未定义位: 0xd")
        },
        // ⑥ 标志位置位但段偏移为 0（HAS_EXPRESSIONS ⇔ 段 2）
        {
            let mut b = v2_template(100);
            b[6..8].copy_from_slice(&0x0007u16.to_le_bytes());
            ("flag-set-offset-zero", b, "标志位与段表不一致：EXPRESSION_POOL 段表偏移=0，标志位声明=存在")
        },
        // ⑥b 反向：偏移非 0 但标志位没声明
        {
            let mut b = v2_template(100);
            b[10 + 2 * 4..14 + 2 * 4].copy_from_slice(&60u32.to_le_bytes());
            ("offset-set-flag-zero", b, "标志位与段表不一致：EXPRESSION_POOL 段表偏移=60，标志位声明=不存在")
        },
        // ⑦ 偏移越过文件尾（段 6 = 文件长度本身，`>=` 就炸）
        {
            let mut b = v2_template(100);
            b[10 + 6 * 4..14 + 6 * 4].copy_from_slice(&100u32.to_le_bytes());
            ("offset-past-eof", b, "段 6 偏移(100)越过文件尾(100)")
        },
        // ⑧ 偏移 <10（落在文件头/段表内）
        {
            let mut b = v2_template(100);
            b[10 + 3 * 4..14 + 3 * 4].copy_from_slice(&9u32.to_le_bytes());
            ("offset-inside-header", b, "段 3 偏移(9)落在文件头/段表内")
        },
        // 补充一：u32 按有符号读为负（Kotlin 的「段偏移为负」判据）
        {
            let mut b = v2_template(100);
            b[10 + 3 * 4..14 + 3 * 4].copy_from_slice(&u32::MAX.to_le_bytes());
            ("offset-negative", b, "段 3 偏移为负: -1")
        },
        // 补充二：短于 10 字节，连文件头都读不齐
        ("too-short", b"ERBC".to_vec(), "文件不足文件头长度(4 字节)"),
    ];
    for (tag, bytes, want) in cases {
        let msg = parse_err(&bytes);
        println!("MALFORMED {tag}: {msg}");
        assert!(
            msg.starts_with("ErbcDecodeException: 偏移 "),
            "{tag}：报错必须是 ErbcDecodeException 形态（含偏移号），实为 {msg}"
        );
        assert!(msg.contains(want), "{tag}：报错文案缺判据原文\n期望含：{want}\n实际：{msg}");
    }
}

// ======================================================================
// 4. 单源消费面：open / incremental 都走同一判据
// ======================================================================

#[test]
fn open_and_incremental_regions_agree_with_parse() {
    let path = current_dir().join("tests/data/rust-manifest/erbc.v1/program.erbc");
    let opened = container::open(&path).unwrap_or_else(|e| panic!("打开真产物失败：{e}"));
    assert_eq!(opened.file_size, 950);
    let by_open: &ContainerHeader = &opened.header;
    let by_parse = container::parse(&read_file(&path)).unwrap();
    assert_eq!(by_open, &by_parse, "open 与 parse 必须同一判据同一读数");

    // incremental（区间消费方）现在也走这条单源：畸形容器必须报红，而不是算出区间。
    let bad = {
        let mut b = v2_template(100);
        b[4..6].copy_from_slice(&3u16.to_le_bytes());
        b
    };
    let dir = workspace();
    std::fs::create_dir_all(&dir).unwrap();
    let bad_path = dir.join("version3.erbc");
    std::fs::write(&bad_path, &bad).unwrap();
    let err = erbcc_rs::incremental::container_regions(&bad_path)
        .err()
        .expect("version=3 的容器不许被区间消费方放行");
    let msg = out_msg(err);
    assert!(msg.contains("不支持的 .erbc 版本 3"), "区间侧要带单源判据原文：{msg}");

    // 侧车读 u32 的越界判据仍在（原 freshness 的 u32_at 面）。
    assert!(opened.u32_at(946).is_ok(), "最后 4 字节可读（946..950）");
    assert!(opened.u32_at(947).is_err(), "越界必须 ErbcDecodeException");
    let missing = container::open(&dir.join("nope.erbc")).err().expect("缺文件必须报红");
    assert!(missing.contains("ErbcLoadException"), "装载面报错形态：{missing}");
}

#[test]
fn section_names_are_single_sourced() {
    assert_eq!(
        erbcc_rs::incremental::SECTION_NAMES,
        SECTION_NAMES,
        "incremental 侧必须是再导出而不是第二份"
    );
    assert_eq!(container::section_name(5), "CODE");
    assert_eq!(container::section_name(99), "UNKNOWN");
}

/// 判据不含「段表单调」（`MappedErbc` 也不判），所以这种头**解析得过**；
/// 但区间必须拒算而不是静默夹取 —— 收单源前 incremental 副本会按偏移排序造出重叠区间。
#[test]
fn non_monotonic_offsets_reject_range_not_silently_clip() {
    let mut b = v2_template(100);
    b[10 + 3 * 4..14 + 3 * 4].copy_from_slice(&60u32.to_le_bytes()); // 段 3 = 60
    b[10 + 5 * 4..14 + 5 * 4].copy_from_slice(&50u32.to_le_bytes()); // 段 5 = 50（排在段 3 之后、偏移却更小）
    let h = container::parse(&b).unwrap_or_else(|e| panic!("头本身在判据内合法：{}", out_msg(e)));
    let err = out_msg(
        h.section_range(3, 100)
            .err()
            .expect("段 3 的右开界落在更小的偏移上，必须 Err 而不是给重叠区间"),
    );
    assert!(err.contains("段表顺序不可解释"), "报错要指名乱序：{err}");
}

// ======================================================================
// 5. 装载层段布局判据（RUST1 rust-container-layout 收紧）
//
// 判据本体 = `container::validate_section_layout`，逐条照 Kotlin
// `ProgramLoader.validateSections`（`ProgramLoader.kt:783-868`，入口
// `MappedErbc.checkSectionLayout` `MappedErbc.kt:284-286`）；报错文案**照 Kotlin 原样**，
// 所以下面钉的每一个字符串都是 `tests/data/rust-fuzz-diff/kotlin-verdicts.tsv`
// 里 `kotlin_l2` 那一列的原文（真跑读数，禁手改）。
// 每档都先过 `container::parse`（头层必须放行），保证红只由装载层那一条引起。
// ======================================================================

fn seed() -> Vec<u8> {
    read_file(&current_dir().join("tests/data/rust-manifest/erbc.v1/program.erbc"))
}

fn put_u32_at(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn set_off(b: &mut [u8], section: usize, v: u32) {
    put_u32_at(b, 10 + section * 4, v);
}

/// 头层放行、装载层必须拒的档位：返回装载层的报错原文（`ErbcDecodeException: 偏移 N: …`）。
fn layout_err(bytes: &[u8]) -> String {
    let h = container::parse(bytes)
        .unwrap_or_else(|e| panic!("这档应当只由装载层拒，头层就先红了：{}", out_msg(e)));
    match container::validate_section_layout_of_bytes(&h, bytes) {
        Ok(()) => panic!("装载层放行了畸形段表"),
        Err(e) => out_msg(e),
    }
}

/// 装载层必须**放行**的档位（合法面一条都不许被收紧打掉）。
fn layout_ok(bytes: &[u8], tag: &str) -> container::ContainerHeader {
    let h = container::parse(bytes).unwrap_or_else(|e| panic!("{tag}：头层不该拒：{}", out_msg(e)));
    container::validate_section_layout_of_bytes(&h, bytes)
        .unwrap_or_else(|e| panic!("{tag}：装载层拒了合法段表（收紧过头）：{}", out_msg(e)));
    h
}

/// 合成一个 v2 容器：段 3 = 46（区间正好 4 字节）、段 5（CODE）= 50 起 `code_room` 字节、
/// 段 6（LINE_TABLE）紧跟其后起 `line_room` 字节，其余段不存在。
/// 「撒谎的计数」与「刚好等于需求」两档都由这几个旋钮直接造。
fn synth_v2(code_room: usize, instr_declared: u32, line_room: usize, line_declared: u32, file_count: u32) -> Vec<u8> {
    let code_off = 50usize;
    let lines_off = code_off + code_room;
    let file_len = lines_off + line_room;
    let mut b = v2_template(file_len);
    set_off(&mut b, 3, 46);
    set_off(&mut b, 5, code_off as u32);
    set_off(&mut b, 6, lines_off as u32);
    put_u32_at(&mut b, code_off, instr_declared);
    put_u32_at(&mut b, lines_off, file_count);
    put_u32_at(&mut b, lines_off + 4, line_declared);
    b
}

/// 真产物种子的段布局本来就该放行（`erbcc check` 只判头层，所以这条只有装载层能证）。
#[test]
fn real_artifact_passes_layout_layer() {
    let b = seed();
    let h = layout_ok(&b, "950 B 真产物");
    // 装载层的段界与头层的「下一存在段」在合法（偏移升序）容器上必须逐段相等。
    let ends = container::section_ends(&h, b.len());
    assert_eq!(
        ends,
        vec![0, 0, 0, 677, 778, 910, 950, 0, 0],
        "装载层段界 = 下一个更大的段起点，末段取文件长度"
    );
    for sec in [3usize, 4, 5, 6] {
        let (start, end) = h.section_range(sec, b.len()).unwrap();
        assert_eq!(start, h.offset_of(sec) as usize, "段 {sec} 区间下界 = 段表偏移");
        assert_eq!(end, ends[sec], "段 {sec} 两层段界读数不一致");
    }
}

/// 判据 1：偏移必须 `>= 10 + 4×sectionCount`（规约 §2 装载层那条；头层只挡 `< 10`）。
#[test]
fn layout_rejects_offset_inside_header_plus_section_table() {
    // v2：头+段表 = 46 字节。CODE 偏移改成 40 —— 头层放行（>=10、<文件长），装载层必须拒。
    let mut b = seed();
    set_off(&mut b, 5, 40);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 40: 段 CODE 偏移(40) 落在文件头+段表(46 字节)内",
        "v2 的装载层下界要跟着 sectionCount=9 走"
    );
    // v1：头+段表 = 38 字节 —— 同一个数字（40）在 v1 上是合法的，判据不是常量而是 `10+4×槽数`。
    let mut b1 = seed();
    set_off(&mut b1, 4, 38);
    b1[4..6].copy_from_slice(&1u16.to_le_bytes());
    b1[8..10].copy_from_slice(&7u16.to_le_bytes());
    layout_ok(&b1, "v1 视图里偏移 38 = 头+段表下界，合法");
    set_off(&mut b1, 4, 30);
    assert_eq!(
        layout_err(&b1),
        "ErbcDecodeException: 偏移 30: 段 FUNCTION_TABLE 偏移(30) 落在文件头+段表(38 字节)内",
        "v1 的下界要退到 38，不能拿 46 去判"
    );
}

/// 判据 3：两段不得声明同一偏移（`off-dup` 与其对称面 / 三段连等 / `flags` 声明存在却同偏移）。
#[test]
fn layout_rejects_two_sections_sharing_one_offset() {
    // ①原档：段 4 = 段 3 = 46 —— 收紧前 Rust 给的是 STATEMENT_POOL[46..46] 的**空区间**。
    let mut b = seed();
    set_off(&mut b, 4, 46);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 46: 段 STATEMENT_POOL 与段 FUNCTION_TABLE 声明了同一偏移(46)"
    );
    // ②对称面：段 4 = 段 5 = 778。
    let mut b = seed();
    set_off(&mut b, 4, 778);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 778: 段 FUNCTION_TABLE 与段 CODE 声明了同一偏移(778)"
    );
    // ③后向对称面：段 6 = 段 5 = 778。
    let mut b = seed();
    set_off(&mut b, 6, 778);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 778: 段 CODE 与段 LINE_TABLE 声明了同一偏移(778)"
    );
    // ④三段连等：判据按段号升序扫，第一条撞上的那对先报（段 3 与段 4），不会跳到段 5。
    let mut b = seed();
    set_off(&mut b, 4, 46);
    set_off(&mut b, 5, 46);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 46: 段 STATEMENT_POOL 与段 FUNCTION_TABLE 声明了同一偏移(46)",
        "三段连等要停在**第一对**，与 Kotlin 的 `seen.put` 顺序一致"
    );
    // ⑤`flags` 声明该段存在、偏移却又与下一段相同 —— 头层的自洽判据管不了这种形态
    //   （位与「偏移非 0」双向自洽都成立），只有装载层能拒。
    let mut b = seed();
    b[6..8].copy_from_slice(&0x0007u16.to_le_bytes()); // flags |= HAS_EXPRESSIONS
    set_off(&mut b, 2, 46); // EXPRESSION_POOL 与 STATEMENT_POOL 同偏移
    layout_err(&b);
    // 头层此刻必须仍然放行（否则这条就不是装载层的账），文案逐字钉死。
    let h = container::parse(&b).unwrap_or_else(|e| panic!("⑤ 头层必须放行：{}", out_msg(e)));
    assert!(h.is_present(container::SECTION_EXPRESSIONS), "⑤：标志位与段表自洽 ⇒ 段 2 算存在");
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 46: 段 EXPRESSION_POOL 与段 STATEMENT_POOL 声明了同一偏移(46)"
    );
}

/// 判据 5：段声明需要 N 字节、区间只剩 M<N 一律拒（截尾两档 + 撒谎的计数两档）。
#[test]
fn layout_rejects_region_smaller_than_declared() {
    // 真产物：CODE 8 条 = 128 字节，区间正好 128（910-778-4）⇒ 等号放行；见 real_artifact 用例。
    // ①截到 940：LINE_TABLE(910) 只剩 30 字节，声明需 8+32=40。
    let b = seed()[..940].to_vec();
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 910: LINE_TABLE 声明 8 行（需 40 字节），本段区间只有 30 字节"
    );
    // ②截到 914：读 lineCount 的那个 u32 直接越尾。
    let b = seed()[..914].to_vec();
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 914: 读取 u32 越界（文件长 914）"
    );
    // ③CODE 计数撒谎（8 → 100）。
    let mut b = seed();
    put_u32_at(&mut b, 778, 100);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 778: CODE 段声明 100 条指令（需 1600 字节），本段区间只剩 128 字节"
    );
    // ④行号表长度撒谎（8 → 100）：先撞上「与 CODE 条数不符」。
    let mut b = seed();
    put_u32_at(&mut b, 914, 100);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 914: 行号表长度(100)与 CODE 段指令条数(8)不符"
    );
    // ⑤段号升序但偏移乱序（段 4 = 800 晚于 CODE = 778）：Kotlin 用「下一个更大的段起点」当上界，
    //   于是 CODE 的区间被夹成 800-778-4 = 18 字节 —— 判据本身要实现对，不能靠巧合。
    let mut b = seed();
    set_off(&mut b, 4, 800);
    assert_eq!(
        layout_err(&b),
        "ErbcDecodeException: 偏移 778: CODE 段声明 8 条指令（需 128 字节），本段区间只剩 18 字节"
    );
    let h = container::parse(&b).unwrap();
    assert_eq!(
        container::section_ends(&h, b.len()),
        vec![0, 0, 0, 778, 910, 800, 950, 0, 0],
        "section_ends 必须取「下一个更大的段起点」，不是「下一存在段（按段号）」"
    );
}

/// 合成档：CODE/LINE 的计数与区间对账（含 `<=0` 条数、带源文件 id、区间不足 4 字节）。
#[test]
fn layout_region_accounting_on_synthetic_containers() {
    // 边界合法：CODE 给足 4+16×2、LINE 给足 8+4×2，两个等号同时踩上 ⇒ 必须放行。
    let ok = synth_v2(4 + 32, 2, 8 + 8, 2, 0);
    layout_ok(&ok, "区间刚好等于需求");
    // CODE 少 1 字节（等号改成不等）⇒ 拒，文案给出两侧读数。
    let short = synth_v2(4 + 32 - 1, 2, 8 + 8, 2, 0);
    assert_eq!(
        layout_err(&short),
        "ErbcDecodeException: 偏移 50: CODE 段声明 2 条指令（需 32 字节），本段区间只剩 31 字节"
    );
    // LINE 少 1 字节。
    let short = synth_v2(4 + 32, 2, 8 + 8 - 1, 2, 0);
    assert_eq!(
        layout_err(&short),
        "ErbcDecodeException: 偏移 86: LINE_TABLE 声明 2 行（需 16 字节），本段区间只有 15 字节"
    );
    // 条数必须为正（0 与 -1 两支，Kotlin 都是 `n <= 0`）。
    let zero = synth_v2(4 + 32, 0, 8 + 8, 2, 0);
    assert_eq!(layout_err(&zero), "ErbcDecodeException: 偏移 50: CODE 段指令条数(0)必须为正");
    let neg = synth_v2(4 + 32, u32::MAX, 8 + 8, 2, 0);
    assert_eq!(layout_err(&neg), "ErbcDecodeException: 偏移 50: CODE 段指令条数(-1)必须为正");
    // 行号表带源文件 id（§5.7：v1 恒 0，非 0 即拒），这条排在区间对账之前。
    let with_id = synth_v2(4 + 32, 2, 8 + 8, 2, 1);
    assert_eq!(
        layout_err(&with_id),
        "ErbcDecodeException: 偏移 86: 行号表带源文件 id(1)，但 Program.lines 只有行号（规约 §5.7）"
    );
    // 每段正文至少 4 字节（v1 形态：末段只剩 2 字节）。
    let mut b1 = vec![0u8; 60];
    b1[0..4].copy_from_slice(b"ERBC");
    b1[4..6].copy_from_slice(&1u16.to_le_bytes());
    b1[6..8].copy_from_slice(&0x0005u16.to_le_bytes());
    b1[8..10].copy_from_slice(&7u16.to_le_bytes());
    for (i, o) in [0u32, 0, 0, 38, 46, 0, 58].iter().enumerate() {
        put_u32_at(&mut b1, 10 + i * 4, *o);
    }
    assert_eq!(
        layout_err(&b1),
        "ErbcDecodeException: 偏移 58: 段 LINE_TABLE 的区间长度(2)不足 4 字节"
    );
}

/// 区间消费方必须走收紧后的入口：装载层的报错**原样透出**（不加路径前缀），
/// 才能与 Kotlin `checkSectionLayout` / `loadFile` 的文案逐字对账。
#[test]
fn region_consumer_routes_through_layout_layer_verbatim() {
    let mut b = seed();
    set_off(&mut b, 4, 46);
    let dir = workspace();
    std::fs::create_dir_all(&dir).unwrap();
    let dup = dir.join("off-dup.erbc");
    std::fs::write(&dup, &b).unwrap();
    let err = out_msg(
        erbcc_rs::incremental::container_regions(&dup)
            .err()
            .expect("两段同偏移不许再算出区间"),
    );
    assert_eq!(
        err,
        "ErbcDecodeException: 偏移 46: 段 STATEMENT_POOL 与段 FUNCTION_TABLE 声明了同一偏移(46)",
        "区间消费方的文案要与 Kotlin 段布局腿逐字相同（不带路径、不带中文包装）"
    );

    // 合法面：同一个入口对「区间刚好等于需求」的合成容器必须给出区间读数。
    let ok = synth_v2(4 + 32, 2, 8 + 8, 2, 0);
    let good = dir.join("boundary-legal.erbc");
    std::fs::write(&good, &ok).unwrap();
    let regions = erbcc_rs::incremental::container_regions(&good).expect("边界合法档必须放行");
    let got: Vec<(usize, usize, usize)> = regions
        .sections
        .iter()
        .map(|s| (s.index, s.start, s.end))
        .collect();
    assert_eq!(
        got,
        vec![(3, 46, 50), (5, 50, 86), (6, 86, 102)],
        "区间读数按「下一存在段 / 末段取文件长度」"
    );
}

/// **合法面的最大一档**：真·全量产物（≈313 MiB，装载层判据的每一个等号都踩在真实读数上）。
///
/// 走 [container::validate_section_layout] 的**映射窗口**字节来源（`OpenedContainer::u32_at`，
/// 每次只读 4 字节）—— 与 Kotlin 的两个字节来源同形（`ProgramLoader.kt:747-751` 全量读 /
/// `MappedErbc.kt:284-286` 走 `::u32At`）：**判据只有一份**，换的只是字节源。
/// 这一档如果红，就是「收紧打掉合法产物」，按契约要停下报红而不是改判据。
/// 产物不在（没跑过 `:tools:erbcc:compileCorpus`）时跳过并点名。
#[test]
fn full_product_passes_layout_layer_through_the_windowed_source() {
    let path = current_dir().join("build/baseline/full/erbc.v1/program.erbc");
    if !path.is_file() {
        eprintln!(
            "SKIP(合法面最大档)：没有全量产物 {path:?}（先跑 ./gradlew --configure-on-demand \
             :tools:erbcc:compileCorpus，或把 ERATW_GAMEDIR 的产物放到这个路径）"
        );
        return;
    }
    let opened = container::open(&path).unwrap_or_else(|e| panic!("全量产物必须打得开：{e}"));
    let file_len = opened.file_size as usize;
    let u32_at = |at: usize| opened.u32_at(at as i64);
    container::validate_section_layout(&opened.header, file_len, &u32_at)
        .unwrap_or_else(|e| panic!("装载层拒了真·全量产物（收紧过头）：{}", out_msg(e)));
    // 窗口源与整字节源必须给同一条结论（把整份文件读进来做一次交叉验证）。
    let whole = read_file(&path);
    assert_eq!(whole.len(), file_len, "尺寸读数与容器一致");
    let h2 = container::parse(&whole).unwrap();
    assert_eq!(h2, opened.header, "open 与 parse 同一判据");
    container::validate_section_layout_of_bytes(&h2, &whole)
        .unwrap_or_else(|e| panic!("整字节源与窗口源结论不一致：{}", out_msg(e)));
    println!(
        "全量产物过装载层：{file_len} B / v{} / {} 槽，段偏移 {:?}",
        opened.header.version,
        opened.header.section_count,
        opened.header.offsets.iter().map(|o| o.unwrap_or(0)).collect::<Vec<u32>>()
    );
}
