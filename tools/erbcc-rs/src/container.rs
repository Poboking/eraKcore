use std::path::{Path, PathBuf};

use crate::types::{ErbccError, Outcome};

/// **容器头与段表的单源解析**（`specs/erbc-format.md` §2/§3）。
///
/// 为什么要单独成模块：`freshness.rs`（rust-manifest 路）里曾有一份私有的头/段表解析，
/// `incremental.rs`（rust-incremental 路）为了算区间又复刻了第二份。**同一判据出现两个实现，
/// 就是未来的假对账源**——本路把它收成单源，两处都改为调用这里，行为零改动。
///
/// 格式（v1 = 7 槽 / v2 = 9 槽，`version` 与槽数**严格配对**，错配必须报错，规约 §2 明令）：
/// ```text
/// 0  : u8[4] magic = "ERBC"
/// 4  : u16   version（1 或 2）
/// 6  : u16   flags（段存在性位）
/// 8  : u16   sectionCount（v1=7，v2=9）
/// 10 : u32[sectionCount] sectionOffsets（0 = 该段不存在；装载侧只认这里，不依赖排列顺序）
/// ```
/// 判界口径（不许放宽）：magic 不符、version 非 1/2、`sectionCount` 与 version 不配对、
/// `flags` 含该版本未定义位（v1 掩码 `0x0007`、v2 掩码 `0x001F`）、偏移 `< 10` 或 `>= 文件长度`、
/// `flags` 某位与对应段 `offset != 0` 不一致 —— 全部 **报错**，规约 §1.6「禁止静默降级」。
/// 文件末尾的多余字节**允许**（规约 §1.5：reader 只按段表寻址，不校验尾部长度）。
///
/// 判据与报错文案逐字收编自 `MappedErbc.open` / `ErbcCursor`（`ErbcFormat.kt`）——
/// 即原 `freshness.rs` 的 `parse_header`/`require_bit`/`open_container`/`u32_at` 一份，
/// 消息形态保持 `"{类名}: {消息}"`（`decode_err`/`load_err`），供新鲜度逐字节对拍。
/// `incremental.rs` 的最小副本（无校验的区间算法）已删除：区间消费方现在也走全量校验，
/// 畸形容器不再能被算出区间——这是收单源的**有意收紧**，对合法产物读数零改动。
///
/// ## 两层判界，各有一个消费面（RUST1 rust-container-layout 收口）
///
/// * **头层** = [parse] / [open]（`MappedErbc.open` 同层）：magic / 版本 / 槽数配对 / 标志位自洽 /
///   偏移 `< 10` 或 `>= 文件长度`。消费方是 `freshness::check` 的 `CONTAINER_INVALID` 腿 ——
///   Kotlin 的 `erbcc check`（`ArtifactFreshness.kt:207-215`）只走到 `MappedErbc.open`，
///   **所以 check 腿不许调装载层判据**，否则一侧收紧过头、两侧文案错位。
/// * **装载层（段布局）** = [validate_section_layout]（`ProgramLoader.validateSections` 同层）：
///   偏移 `>= 10 + 4×sectionCount`、两段不得同偏移、每段区间 `>= 4` 字节、
///   CODE/LINE_TABLE 的声明长度与区间对账。消费方是**区间消费方**
///   `incremental::container_regions` —— 它会拿区间去比字节、算 sha，
///   段表一旦不可解释，算出来的「段内容」就是假的（规约 §1.6 的静默降级）。
///   `section_range` 自己那三条判界（段不存在 / 偏移不在长度内 / 下一存在段偏移更早）**保留不变**：
///   装载层跑在它前面，同偏移这类畸形不会再走到它，也就不会再产出「长度为 0 的空区间」。

/// 容器 magic（`ErbcFormat.kt:35-39`）。
pub const MAGIC: [u8; 4] = [0x45, 0x52, 0x42, 0x43];
/// 文件头字节数（`ErbcFormat.kt:58`）。
pub const HEADER_BYTES: usize = 10;
pub const VERSION_1: i32 = 1;
pub const VERSION_2: i32 = 2;
/// v1/v2 段表槽数（`ErbcFormat.kt:61-64`）。
pub const SECTION_COUNT_V1: usize = 7;
pub const SECTION_COUNT_V2: usize = 9;
pub const SECTION_EXPRESSIONS: usize = 2;
pub const SECTION_STATEMENTS: usize = 3;
pub const SECTION_CODE: usize = 5;
pub const SECTION_LINES: usize = 6;
pub const SECTION_CALL_ARGS: usize = 7;
pub const SECTION_PARAM_NAMES: usize = 8;
const FLAG_HAS_LINES: i32 = 0x0001;
const FLAG_HAS_EXPRESSIONS: i32 = 0x0002;
const FLAG_HAS_STATEMENTS: i32 = 0x0004;
const FLAG_HAS_CALL_ARGS: i32 = 0x0008;
const FLAG_HAS_PARAM_NAMES: i32 = 0x0010;
/// 段名（`ErbcFormat.kt:84-87`，下标即段号；口径同 `specs/erbc-format.md` §4 段号总表）。
pub const SECTION_NAMES: [&str; 9] = [
    "STRING_POOL",
    "CONSTANT_POOL",
    "EXPRESSION_POOL",
    "STATEMENT_POOL",
    "FUNCTION_TABLE",
    "CODE",
    "LINE_TABLE",
    "CALL_ARGS",
    "PARAM_NAMES",
];

/// 版本 → 段表槽数（`ErbcFormat.kt:95-99`；未支持版本 = -1）。
fn section_count_for(version: i32) -> i32 {
    match version {
        VERSION_1 => SECTION_COUNT_V1 as i32,
        VERSION_2 => SECTION_COUNT_V2 as i32,
        _ => -1,
    }
}

/// 版本 → 允许的标志位掩码（`ErbcFormat.kt:135-139`）。
fn flag_mask_for(version: i32) -> i32 {
    match version {
        VERSION_1 => FLAG_HAS_LINES | FLAG_HAS_EXPRESSIONS | FLAG_HAS_STATEMENTS,
        VERSION_2 => {
            FLAG_HAS_LINES
                | FLAG_HAS_EXPRESSIONS
                | FLAG_HAS_STATEMENTS
                | FLAG_HAS_CALL_ARGS
                | FLAG_HAS_PARAM_NAMES
        }
        _ => 0,
    }
}

/// 文件头 + 段表字节数（`ErbcFormat.kt:102`）。
pub fn first_section_offset(section_count: usize) -> usize {
    HEADER_BYTES + section_count * 4
}

/// `ErbcDecodeException` 的文本形态：`"偏移 $offset: $message"`（`ErbcFormat.kt:357`）。
fn decode_err(offset: i64, message: &str) -> String {
    format!("ErbcDecodeException: 偏移 {offset}: {message}")
}

/// `ErbcLoadException` 的文本形态（`MappedErbc.kt:84/90/104/208/211`）。
fn load_err(message: &str) -> String {
    format!("ErbcLoadException: {message}")
}

/// 把 [ErbccError] 还原成 Kotlin 形态的消息文本（判据消息本就存在 Io 载荷里）。
pub fn message_of(e: &ErbccError) -> String {
    match e {
        ErbccError::Io(m) | ErbccError::Usage(m) | ErbccError::Parity(m) => m.clone(),
        other => format!("{other:?}"),
    }
}

/// 容器头快照（= `ErbcHeader`，`ErbcFormat.kt:397-402）。
///
/// `offsets` 下标 = 段号；`None` = 该段不存在（段表偏移 0）。解析成功即保证：
/// 每个 `Some(v)` 满足 `10 <= v < 文件长度`，且与 `flags` 的对应位双向自洽。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContainerHeader {
    pub version: u16,
    pub flags: u16,
    pub section_count: u16,
    /// 下标 = 段号；`None` = 该段不存在（偏移 0）。
    pub offsets: Vec<Option<u32>>,
}

impl ContainerHeader {
    /// 该段是否存在（`ErbcHeader.kt:415` 的 `hasSection`：下标越界 = 不存在）。
    pub fn is_present(&self, section: usize) -> bool {
        matches!(self.offsets.get(section), Some(Some(_)))
    }

    /// 该段绝对偏移；不存在 = 0（`MappedErbc.kt:238-241`）。
    pub fn offset_of(&self, section: usize) -> u32 {
        self.offsets.get(section).copied().flatten().unwrap_or(0)
    }

    /// 某段的字节区间 `[start, end)`：`end` = 下一个存在段的偏移，末段取文件长度。
    /// 段不存在 ⇒ `Err`（调用方自己先判存在性，别拿到空区间当「这段是空的」）。
    ///
    /// **它挡不住「两段同偏移」**（那种形态这里给出的正是长度 0 的空区间，字面上看像「这段是空的」）：
    /// 这条判据在装载层 [super::validate_section_layout] 里（= Kotlin `validateSections`），
    /// 区间消费方必须先过那道闸再来这里取区间（`incremental::container_regions` 已按此接线）。
    pub fn section_range(&self, section: usize, file_len: usize) -> Outcome<(usize, usize)> {
        let name = section_name(section);
        let start = match self.offsets.get(section) {
            Some(Some(v)) => *v as usize,
            _ => {
                return Err(ErbccError::Io(format!(
                    "段 {section}（{name}）不存在（段表偏移 0），没有字节区间可取；\
                     空区间不等于「该段为空」，调用方须先判存在性"
                )))
            }
        };
        if start >= file_len {
            return Err(ErbccError::Io(format!(
                "段 {section}（{name}）偏移({start})不在文件长度({file_len})内"
            )));
        }
        let end = self.offsets[section + 1..]
            .iter()
            .find_map(|o| *o)
            .map(|v| v as usize)
            .unwrap_or(file_len);
        if end < start {
            // 段号升序但偏移不升序：区间无法按「下一存在段」闭合，静默夹取会造出假区间。
            return Err(ErbccError::Io(format!(
                "段 {section}（{name}）偏移({start})晚于下一存在段偏移({end})，段表顺序不可解释"
            )));
        }
        Ok((start, end))
    }
}

/// 段名表越界时的兜底名（报错与报告用；口径 = Kotlin `nameOf`，`ProgramLoader.kt:891-892`）。
pub fn section_name(section: usize) -> &'static str {
    SECTION_NAMES.get(section).copied().unwrap_or("UNKNOWN")
}

// ======================================================================
// 装载层段布局判据（= Kotlin `ProgramLoader.validateSections`）
// ======================================================================

/// 每段的上界（不含）= 段表里**下一个更大的段起点**，没有则文件尾；不存在的段为 0。
///
/// **逐字照 Kotlin `ProgramLoader.sectionEnds`（`ProgramLoader.kt:760-776`）**：先把存在的偏移
/// 拷一份排序，再对每段取 `firstOrNull { it > off } ?: size`。它和
/// [ContainerHeader::section_range] 的「下一存在段（按段号升序）」**不是**一个口径：
/// 段表被改坏（段号升序但偏移乱序）时，本口径仍然保守 —— 只认「数值上更大的下一个起点」，
/// 不会把排在它后面的段字节当本段正文放过（规约 §1.3「段与段紧挨着排」的前提）。
/// 装载层的 CODE/LINE_TABLE 区间对账（[validate_section_layout]）用的就是这个上界，
/// 所以畸形段表在这一步就被拒，而不是算出一块吃别人的区间。
pub fn section_ends(header: &ContainerHeader, file_len: usize) -> Vec<usize> {
    let mut starts: Vec<usize> = header.offsets.iter().flatten().map(|&v| v as usize).collect();
    starts.sort_unstable();
    header
        .offsets
        .iter()
        .map(|slot| match slot {
            None => 0,
            Some(off) => starts
                .iter()
                .copied()
                .find(|&s| s > *off as usize)
                .unwrap_or(file_len),
        })
        .collect()
}

/// 整份容器字节上的小端 u32（`ProgramLoader.u32`，`ProgramLoader.kt:880-889`）。
///
/// 判界按 `at + 4 > 文件长度`（Kotlin 用 `Long` 加法防 Int 回绕，这里用饱和加同效），
/// 越界消息「读取 u32 越界（文件长 N）」与 [OpenedContainer::u32_at]（`MappedErbc.u32At`，
/// `MappedErbc.kt:260-267`）逐字同一条 —— 两条字节来源共用一份文案，装载层才不会有第二套口径。
pub fn u32_at_bytes(bytes: &[u8], at: usize) -> Result<i32, String> {
    if at.saturating_add(4) > bytes.len() {
        return Err(decode_err(
            at as i64,
            &format!("读取 u32 越界（文件长 {}）", bytes.len()),
        ));
    }
    Ok(i32::from_le_bytes([
        bytes[at],
        bytes[at + 1],
        bytes[at + 2],
        bytes[at + 3],
    ]))
}

/// 指令字宽（`Instruction.WORDS`，Kotlin 侧 `n × WORDS × 4 = n × 16`，
/// `MappedErbc.kt:301` / `ProgramLoader.kt:833`）。
const CODE_BYTES_PER_INSTRUCTION: i64 = 16;

/// **装载层段布局校验**（逐字照 Kotlin `ProgramLoader.validateSections`，
/// `ProgramLoader.kt:783-868`；入口两条 = `MappedErbc.checkSectionLayout`（`MappedErbc.kt:284-286`）
/// 与 `load` 里的私有同名函数（`ProgramLoader.kt:747-751`）；规约 §2 末条 + §3「缺段的代价」）。
///
/// 头层（[parse]）只管 magic/版本/槽数/标志位自洽/偏移 `< 10` 或 `>= 文件长度`；
/// **段与段之间的账**在这一层，五条判据一条都不能少（`specs/erbc-format.md` §1.6 禁止静默降级）：
///
/// 1. 偏移必须 `>= 10 + 4×sectionCount`（§2 明写的「装载层再加一条」；头层只挡 `< 10`，
///    落进**段表区**里的那一段只有这层能拒）；
/// 2. 偏移必须 `< 文件长度`（头层同判据，装载层用段名重写一遍 —— Kotlin 就有两条，原样保留）；
/// 3. **两段不得声明同一偏移**（否则下一段白吃上一段，或者上一段被算成长度 0 的「空段」）；
/// 4. 每段区间长度 `>= 4`（每个段正文至少有一个计数/长度字，短于此必是段表被改坏）；
/// 5. CODE/LINE_TABLE 的**声明长度与本段区间对账**：CODE 条数 × 16 不得超过区间可用字节，
///    行号表的 `fileTableCount` 必须为 0、`lineCount` 必须等于 CODE 条数、
///    `8 + lineCount × 4` 不得超过区间可用字节。
///
/// **边界口径（不许收紧过头）**：判定一律是 `need > available`，所以「区间**刚好等于**需求」
/// （`need == available`）**必须放行** —— 真产物的 CODE/LINE_TABLE 就正踩在这个等号上
/// （`tests/data/rust-incremental/probe-readings.txt` 那批读数），改成 `>=` 会把合法产物全拒掉。
/// 同偏移判定是「已见过同一偏移」而不是「偏移不递增」，所以合法的非递增段表（不存在）与
/// 乱序但互不相等的段表都走 5 那条区间对账，不会被这条误伤。
///
/// 报错文本已是 `"{类名}: {消息}"` 形态（[decode_err]），与 Kotlin 的 `ErbcDecodeException`
/// 逐字相同 ⇒ 消费方原样透出即可做「两侧同文案」对账。
/// `u32_at` 由调用方给（对应 Kotlin 的两个字节来源：整份 `ByteArray` 与 4 字节映射窗口），
/// 整字节侧用 [validate_section_layout_of_bytes] / [u32_at_bytes]，映射侧用 [OpenedContainer::u32_at]。
pub fn validate_section_layout(
    header: &ContainerHeader,
    file_len: usize,
    u32_at: &dyn Fn(usize) -> Result<i32, String>,
) -> Outcome<()> {
    let first = first_section_offset(header.offsets.len());
    let mut present: Vec<usize> = Vec::with_capacity(header.offsets.len());
    // Kotlin 用 `HashMap<Int, Int>`（偏移 → 段号）；段表最多 9 槽，这里线性扫同一份账。
    let mut seen: Vec<(usize, usize)> = Vec::with_capacity(header.offsets.len());
    for (i, slot) in header.offsets.iter().enumerate() {
        let Some(off32) = slot else { continue };
        let off = *off32 as usize;
        present.push(i);
        if off < first {
            return Err(ErbccError::Io(decode_err(
                off as i64,
                &format!(
                    "段 {} 偏移({off}) 落在文件头+段表({first} 字节)内",
                    section_name(i)
                ),
            )));
        }
        if off >= file_len {
            return Err(ErbccError::Io(decode_err(
                off as i64,
                &format!(
                    "段 {} 偏移({off}) 越过文件尾({file_len})",
                    section_name(i)
                ),
            )));
        }
        if let Some((_, prev)) = seen.iter().find(|(o, _)| *o == off) {
            return Err(ErbccError::Io(decode_err(
                off as i64,
                &format!(
                    "段 {} 与段 {} 声明了同一偏移({off})",
                    section_name(*prev),
                    section_name(i)
                ),
            )));
        }
        seen.push((off, i));
    }

    let ends = section_ends(header, file_len);
    // 段起点 → 段号 → 该段上界；与 MappedErbc.endOf 完全同一个数组（`ProgramLoader.kt:812-815`）。
    let end_of = |off: usize| -> usize {
        match header.offsets.iter().position(|o| *o == Some(off as u32)) {
            Some(id) if ends[id] != 0 => ends[id],
            _ => file_len,
        }
    };

    for &i in &present {
        let off = header.offset_of(i) as usize;
        let len = end_of(off) as i64 - off as i64;
        // 每个段正文至少有一个计数/长度字；短于此必是段表被改坏（`ProgramLoader.kt:820-823`）。
        if len < 4 {
            return Err(ErbccError::Io(decode_err(
                off as i64,
                &format!("段 {} 的区间长度({len})不足 4 字节", section_name(i)),
            )));
        }
    }

    let code_off = header.offset_of(SECTION_CODE) as usize;
    let mut instr_count: i64 = -1;
    if code_off != 0 {
        let available = end_of(code_off) as i64 - code_off as i64 - 4;
        let n = u32_at(code_off).map_err(ErbccError::Io)?;
        if n <= 0 {
            return Err(ErbccError::Io(decode_err(
                code_off as i64,
                &format!("CODE 段指令条数({n})必须为正"),
            )));
        }
        let need = (n as i64) * CODE_BYTES_PER_INSTRUCTION;
        if need > available {
            return Err(ErbccError::Io(decode_err(
                code_off as i64,
                &format!(
                    "CODE 段声明 {n} 条指令（需 {need} 字节），本段区间只剩 {available} 字节"
                ),
            )));
        }
        instr_count = n as i64;
    }

    let lines_off = header.offset_of(SECTION_LINES) as usize;
    if lines_off != 0 {
        let file_count = u32_at(lines_off).map_err(ErbccError::Io)?;
        if file_count != 0 {
            return Err(ErbccError::Io(decode_err(
                lines_off as i64,
                &format!(
                    "行号表带源文件 id({file_count})，但 Program.lines 只有行号（规约 §5.7）"
                ),
            )));
        }
        let n = u32_at(lines_off + 4).map_err(ErbccError::Io)?;
        if instr_count >= 0 && (n as i64) != instr_count {
            return Err(ErbccError::Io(decode_err(
                lines_off as i64 + 4,
                &format!("行号表长度({n})与 CODE 段指令条数({instr_count})不符"),
            )));
        }
        let need = 8i64 + (n as i64) * 4;
        let available = end_of(lines_off) as i64 - lines_off as i64;
        if n < 0 || need > available {
            return Err(ErbccError::Io(decode_err(
                lines_off as i64,
                &format!(
                    "LINE_TABLE 声明 {n} 行（需 {need} 字节），本段区间只有 {available} 字节"
                ),
            )));
        }
    }
    Ok(())
}

/// [validate_section_layout] 的「整份字节在手」入口 —— Kotlin 侧同样是两个入口共用一份本体
/// （`ProgramLoader.kt:747-751` 的私有 `validateSections(header, bytes, source)`）。
pub fn validate_section_layout_of_bytes(
    header: &ContainerHeader,
    bytes: &[u8],
) -> Outcome<()> {
    validate_section_layout(header, bytes.len(), &|at| u32_at_bytes(bytes, at))
}

/// 头字节游标：判界与消息逐字照 `ErbcCursor`（`ErbcFormat.kt:566-675`）。
struct HeadCursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> HeadCursor<'a> {
    /// `ErbcCursor.need`（`:584-588`）：Long 加法判界，消息含「需要/只剩/总长」。
    fn need(&self, n: usize, what: &str) -> Result<usize, String> {
        if self.pos + n > self.bytes.len() {
            return Err(decode_err(
                self.pos as i64,
                &format!(
                    "{what} 需要 {n} 字节，文件只剩 {} 字节（总长 {}）",
                    self.bytes.len() - self.pos,
                    self.bytes.len()
                ),
            ));
        }
        Ok(self.pos)
    }

    fn u8(&mut self) -> Result<i32, String> {
        let at = self.need(1, "u8")?;
        self.pos += 1;
        Ok(self.bytes[at] as i32)
    }

    /// 小端 u16（`ErbcCursor.u16`，`:597-602`）。
    fn u16(&mut self) -> Result<i32, String> {
        let at = self.need(2, "u16")?;
        self.pos += 2;
        Ok((self.bytes[at] as i32) | ((self.bytes[at + 1] as i32) << 8))
    }

    /// 小端 u32 按有符号 i32 原样取回（`ErbcCursor.u32`，`:606-613`：负数原样带出，
    /// 「段偏移为负」的判据就靠它）。
    fn u32_as_i32(&mut self) -> Result<i32, String> {
        let at = self.need(4, "u32")?;
        self.pos += 4;
        Ok(i32::from_le_bytes([
            self.bytes[at],
            self.bytes[at + 1],
            self.bytes[at + 2],
            self.bytes[at + 3],
        ]))
    }
}

/// 解析并**全量校验**容器头 + 段表（`bytes` = 容器整文件字节）。
///
/// 头按 `min(文件长度, 46)` 取（`MappedErbc.kt:94`：按最新版本槽数取头，
/// 读到 v1 文件时多余的读取只会截到文件尾），文件长度用于段偏移上界判定。
/// 判界与消息逐字对齐 `MappedErbc.kt:115-193`（实现见 [parse_head]）。
pub fn parse(bytes: &[u8]) -> Outcome<ContainerHeader> {
    let head_len = std::cmp::min(bytes.len(), first_section_offset(SECTION_COUNT_V2));
    parse_head(&bytes[..head_len], bytes.len() as i64)
}

/// 头 + 段表的判据本体：**判定与消息逐字对齐 `MappedErbc.kt:115-193`**。
///
/// `head` 是容器前 `min(file_size, 46)` 字节，`file_size` 是**文件真实长度**
/// （freshness 只按需读头，不整读 314 MiB 的全量容器，故两者分开给）。
/// 报错文本已是 `"{类名}: {消息}"` 形态，包装进 [ErbccError::Io] 供调用方原样拼进 detail。
fn parse_head(head: &[u8], file_size: i64) -> Outcome<ContainerHeader> {
    parse_head_inner(head, file_size).map_err(ErbccError::Io)
}

fn parse_head_inner(head: &[u8], file_size: i64) -> Result<ContainerHeader, String> {
    if head.len() < HEADER_BYTES {
        return Err(decode_err(
            0,
            &format!("文件不足文件头长度({file_size} 字节)"),
        ));
    }
    let mut cur = HeadCursor {
        bytes: head,
        pos: 0,
    };
    let m0 = cur.u8()?;
    let m1 = cur.u8()?;
    let m2 = cur.u8()?;
    let m3 = cur.u8()?;
    if [m0, m1, m2, m3] != [MAGIC[0] as i32, MAGIC[1] as i32, MAGIC[2] as i32, MAGIC[3] as i32] {
        // Kotlin: `listOf(m0,m1,m2,m3).joinToString(" "){ it.toString(16) }` —— 无前导零、小写。
        let got = [m0, m1, m2, m3]
            .iter()
            .map(|v| format!("{:x}", v))
            .collect::<Vec<_>>()
            .join(" ");
        return Err(decode_err(
            0,
            &format!("magic 不符：期望 ERBC（45 52 42 43），实为 [{got}]"),
        ));
    }
    let version = cur.u16()?;
    let expected_sections = section_count_for(version);
    if expected_sections < 0 {
        return Err(decode_err(
            4,
            &format!(
                "不支持的 .erbc 版本 {version}（本引擎只认 v{VERSION_1}/v{VERSION_2}，不做降级解释）"
            ),
        ));
    }
    let flags = cur.u16()?;
    let mask = flag_mask_for(version);
    if (flags & !mask) != 0 {
        return Err(decode_err(
            6,
            &format!("标志位含 v{version} 未定义位: 0x{:x}", flags),
        ));
    }
    let section_count = cur.u16()? as usize;
    if section_count != expected_sections as usize {
        return Err(decode_err(
            8,
            &format!("段表槽数 {section_count} 与 v{version} 固定的 {expected_sections} 不符"),
        ));
    }
    let mut raw_offsets = Vec::with_capacity(section_count);
    for _ in 0..section_count {
        raw_offsets.push(cur.u32_as_i32()?);
    }
    // 报错位置用 `cur.pos - 4`：与 Kotlin 一样整张表先读完再逐条判，pos 恒为头+表（`:158-166`）。
    let err_pos = cur.pos as i64 - 4;
    for (i, &off) in raw_offsets.iter().enumerate() {
        if off < 0 {
            return Err(decode_err(err_pos, &format!("段 {i} 偏移为负: {off}")));
        }
        if off != 0 && (off as usize) < HEADER_BYTES {
            return Err(decode_err(
                err_pos,
                &format!("段 {i} 偏移({off})落在文件头/段表内"),
            ));
        }
        if (off as i64) >= file_size {
            return Err(decode_err(
                err_pos,
                &format!("段 {i} 偏移({off})越过文件尾({file_size})"),
            ));
        }
    }
    require_bit(&raw_offsets, flags, FLAG_HAS_LINES, SECTION_LINES)?;
    require_bit(&raw_offsets, flags, FLAG_HAS_EXPRESSIONS, SECTION_EXPRESSIONS)?;
    require_bit(&raw_offsets, flags, FLAG_HAS_STATEMENTS, SECTION_STATEMENTS)?;
    if version >= VERSION_2 {
        require_bit(&raw_offsets, flags, FLAG_HAS_CALL_ARGS, SECTION_CALL_ARGS)?;
        require_bit(&raw_offsets, flags, FLAG_HAS_PARAM_NAMES, SECTION_PARAM_NAMES)?;
    }
    Ok(ContainerHeader {
        version: version as u16,
        flags: flags as u16,
        section_count: section_count as u16,
        offsets: raw_offsets
            .iter()
            .map(|&off| if off == 0 { None } else { Some(off as u32) })
            .collect(),
    })
}

/// 标志位与段表的双向自洽校验（`MappedErbc.kt:178-193`，消息逐字一致）。
fn require_bit(offsets: &[i32], flags: i32, bit: i32, section: usize) -> Result<(), String> {
    let present = offsets.get(section).copied().unwrap_or(0) != 0;
    let declared = (flags & bit) != 0;
    if present != declared {
        return Err(decode_err(
            (HEADER_BYTES + section * 4) as i64,
            &format!(
                "标志位与段表不一致：{} 段表偏移={}，标志位声明={}",
                SECTION_NAMES[section],
                offsets.get(section).copied().unwrap_or(0),
                if declared { "存在" } else { "不存在" }
            ),
        ));
    }
    Ok(())
}

/// 已打开的容器：头 + 文件长度 + 路径（新鲜度只需要段表与段 3 的一个 u32，不读段正文）。
pub struct OpenedContainer {
    pub path: PathBuf,
    pub header: ContainerHeader,
    pub file_size: u64,
}

impl OpenedContainer {
    /// 绝对偏移上的小端 u32（`MappedErbc.u32At`，`:259-266`：越界即 `ErbcDecodeException`）。
    ///
    /// Kotlin 走 4 字节映射窗口；本侧同样按需读 4 字节（不整读 314 MiB 的全量容器）。
    pub fn u32_at(&self, offset: i64) -> Result<i32, String> {
        if offset < 0 || offset + 4 > self.file_size as i64 {
            return Err(decode_err(
                offset,
                &format!("读取 u32 越界（文件长 {}）", self.file_size),
            ));
        }
        use std::io::{Read, Seek, SeekFrom};
        let mut f = std::fs::File::open(&self.path)
            .map_err(|e| load_err(&format!("{}: 打开失败（IOException: {e}）", self.path.display())))?;
        f.seek(SeekFrom::Start(offset as u64))
            .map_err(|e| load_err(&format!("{}: 读取失败（IOException: {e}）", self.path.display())))?;
        let mut win = [0u8; 4];
        f.read_exact(&mut win)
            .map_err(|e| load_err(&format!("{}: 读到 EOF（{e}）", self.path.display())))?;
        Ok(i32::from_le_bytes(win))
    }
}

/// 打开容器：只读文件头 + 段表（`MappedErbc.kt:80-106`），不触碰任何段正文。
///
/// 失败面与 Kotlin 同形：打开/读尺寸失败与「超过 u32 偏移寻址上界」是 `ErbcLoadException`，
/// 头与段表的畸形是 `ErbcDecodeException`；返回的错误串已是 `"{类名}: {消息}"` 形态，
/// 直接拼进 `KIND_CONTAINER_INVALID` 的 detail（`ArtifactFreshness.kt:210-214`）。
pub fn open(path: &Path) -> Result<OpenedContainer, String> {
    let mut file = std::fs::File::open(path).map_err(|e| {
        load_err(&format!(
            "{}: 打开失败（IOException: {e}）",
            path.display()
        ))
    })?;
    let file_size = file.metadata().map_err(|e| {
        load_err(&format!(
            "{}: 读取失败（IOException: {e}）",
            path.display()
        ))
    })?.len();
    if file_size > u32::MAX as u64 {
        return Err(load_err(&format!(
            "{}: 产物 {file_size} 字节超过 u32 偏移寻址上界（Int.MAX_VALUE）",
            path.display()
        )));
    }
    // 头长度 = min(file_size, 46)：按「最新版本（v2 = 9 槽）的槽数」取，读到 v1 文件时
    // 多余的读取只会截到文件尾（`MappedErbc.kt:93-96`）。
    let head_len = std::cmp::min(file_size as usize, first_section_offset(SECTION_COUNT_V2));
    let mut head = vec![0u8; head_len];
    use std::io::Read;
    file.read_exact(&mut head).map_err(|e| {
        load_err(&format!(
            "{}: 读到 EOF（已读 {}/{} 字节，{e}）",
            path.display(),
            0,
            head_len
        ))
    })?;
    let header = parse_head(&head, file_size as i64).map_err(|e| message_of(&e))?;
    Ok(OpenedContainer {
        path: path.to_path_buf(),
        header,
        file_size,
    })
}
