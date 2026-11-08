//! `@标签` 符号表摘要的 **确定性序列化层** + `--collator-check` **报告文本层**。
//!
//! 规约 = `specs/loader.md` 附录 D；文案与字段序的权威 = Kotlin
//! `tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/Main.kt:261-309`（`--json` 的
//! `StringBuilder` 逐段）与 `Main.kt:247-259`（比较器自检报告），转义口径 =
//! `Main.kt:1098-1112` 的 `jstr`。
//!
//! ## 语义分工（为什么这里只有序列化）
//!
//! 装载序与比较结果的**判定权留在 JVM**：ja-JP Collator 是 ADR-006 的冻结口径
//! （`core-vm/src/main/kotlin/com/eratw/port/core/frontend/Symbols.kt:444-451` 的
//! `nameComparator`，strength=TERTIARY），Rust 侧**不复刻比较器、不引入 ICU**
//! （契约 §2：`icu_collator` 本机拉不到，方向也不对）。本模块只吃 kernel 给的**读数**
//! （`symbols --json` 写出的机器可读摘要 + `--collator-check` 的报告行），
//! 严格解析成类型化模型后再按 Kotlin 的字节布局重新序列化 ——
//! 「解析 + 重序列化」就是本路的移植面：字段序、`,`/` ` 分隔、条件字段、转义表
//! 任何一处漂了，对拍闸（`tests/symbols_json_parity.rs`）当场红。

use crate::types::{ErbccError, Outcome};

/// Kotlin `Main.kt:251` 的比较器自检块标题（原样，含全角括号）。
pub const COLLATOR_HEADER: &str = "=== 比较器自检（ja-JP Collator vs ORDINAL）===";

/// kernel stdout 末行 `符号表摘要: <绝对路径>` 的前缀（`Main.kt:307`）。
/// 该行打的是 kernel 自己那趟的**机器路径**，不属于可移植文本，由编排层按用户入参重出。
pub const SUMMARY_PREFIX: &str = "符号表摘要: ";

/// 结论行的两种取值（`Main.kt:258`）。
pub const CONCLUSION_PASS: &str = "结论: PASS（两比较器对本语料的重名解析结论一致）";
pub const CONCLUSION_FAIL: &str = "结论: FAIL（需人工核对上面的翻转组）";

// ---------------------------------------------------------------------------
// 类型化读数模型（字段集 = Main.kt:263-299 实际写出的那些；注释给 Kotlin 行号）
// ---------------------------------------------------------------------------

/// `SymbolStats`（`Symbols.kt:390-398`）→ JSON `stats` 对象（`Main.kt:263-266`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Stats {
    pub total_defs: i64,
    pub unique_names: i64,
    pub duplicate_names: i64,
    pub non_event_duplicates: i64,
    pub event_duplicates: i64,
    pub shadowed_defs: i64,
    pub method_defs: i64,
    /// `SymbolConfig.collator`（枚举名 `JA_JP`/`ORDINAL`，`Symbols.kt:437`）
    pub collator: String,
}

/// 单条 `#DIM`/`#DIMS` 原文声明（`DimSpec`，`Symbols.kt:241-249`）→
/// JSON 的 `dimSpecs` 元素（`Main.kt:276-290`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DimSpec {
    pub name: String,
    /// `#DIMS` → true（`Main.kt:277` 的 `isStr`）
    pub is_str: bool,
    /// 各维长度**原文**（`Main.kt:278-281`，逐条 `jstr` 转义）
    pub dims: Vec<String>,
    /// 关键字（`DimKeyword` 枚举名；Kotlin 侧 `Main.kt:283-286` 是**不转义**的
    /// `"$k"` 直拼，本侧同口径 —— 枚举名恒为 `[A-Z]+`，两种写法字节相同）
    pub keywords: Vec<String>,
    /// `=` 之后原文；无初值时 Kotlin **不写这个键**（`Main.kt:289`）
    pub initializer: Option<String>,
}

/// 一条标签定义（`LabelDef`，`Symbols.kt:348-379`）→ JSON `defs` 元素
/// （`Main.kt:271-299`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LabelDef {
    /// 语料根相对路径，`/` 分隔（`Symbols.kt:693`）
    pub file: String,
    pub line: i64,
    pub file_index: i64,
    pub arg_count: i64,
    pub arg_int_count: i64,
    pub arg_str_count: i64,
    pub local_size: i64,
    pub locals_size: i64,
    pub dim_specs: Vec<DimSpec>,
    /// 以下五个是**条件字段**：只在为真时出现（`Main.kt:294-298`）
    pub method: bool,
    pub only: bool,
    pub pri: bool,
    pub later: bool,
    pub single: bool,
}

/// 重名组（`DuplicateGroup`，`Symbols.kt:382-388`）→ JSON `duplicates` 元素
/// （`Main.kt:268`）。`count` 恒 = `defs.len()`，故不单存字段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DuplicateGroup {
    pub key: String,
    pub is_event: bool,
    pub defs: Vec<LabelDef>,
}

/// 一份完整读数（kernel 的 `--json` 产物）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SymbolSummary {
    pub stats: Stats,
    pub duplicates: Vec<DuplicateGroup>,
}

/// 比较器自检读数（`CollatorCheck`，`Symbols.kt:405-418`）。
///
/// 注意与 Kotlin 的结构差异：Kotlin 存 `duplicateDefFiles: List<String>` 且
/// `firstDivergence` 是**绝对路径**、打印时才 relativize（`Main.kt:253`）；
/// kernel 的读数（stdout 报告行）里能拿到的只有**个数**与**已相对化**的那一条，
/// 所以本类型存 `duplicate_def_files: i64`（个数）与相对化后的字符串。
/// 上屏文本逐字节等价，判定（[CollatorCheck::passed]）也等价 —— 只是不把
/// 「文件清单」与「绝对路径」带过来（Rust 侧不需要，且带了也无从核对）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollatorCheck {
    pub files: i64,
    pub shifted_files: i64,
    /// 已按语料根相对化的首个位置差异（`Main.kt:253` 的打印值）
    pub first_divergence: Option<String>,
    pub duplicate_def_files: i64,
    pub duplicate_def_inversions: i64,
    pub duplicate_def_pairs: i64,
    pub duplicate_groups: i64,
    pub winner_flips: Vec<String>,
    pub event_order_flips: Vec<String>,
}

impl CollatorCheck {
    /// `Symbols.kt:416-417`：`passed` = 两类翻转组皆空 **且** 相对次序翻转 0。
    /// **不是**读 kernel 的 `结论:` 行 —— 行文本与判据都在这儿重算，
    /// 解析时再与 kernel 打的结论核对一次（不等即读数与判据分叉，报红）。
    pub fn passed(&self) -> bool {
        self.winner_flips.is_empty() && self.event_order_flips.is_empty() && self.duplicate_def_inversions == 0
    }
}

// ---------------------------------------------------------------------------
// JSON 读数：解析
// ---------------------------------------------------------------------------

/// 读数 JSON 的最小值域（手写，不引依赖：`Cargo.toml` 冻结，只有 `encoding_rs`/`sha2`）。
#[derive(Debug, Clone, PartialEq)]
enum Json {
    Obj(Vec<(String, Json)>),
    Arr(Vec<Json>),
    Str(String),
    Num(i64),
    Bool(bool),
    Null,
}

impl Json {
    fn ctx(&self) -> &'static str {
        match self {
            Json::Obj(_) => "对象",
            Json::Arr(_) => "数组",
            Json::Str(_) => "字符串",
            Json::Num(_) => "整数",
            Json::Bool(_) => "布尔",
            Json::Null => "null",
        }
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(text: &'a str) -> Self {
        Cursor { bytes: text.as_bytes(), pos: 0 }
    }

    fn fail<T>(&self, msg: impl AsRef<str>) -> Outcome<T> {
        Err(ErbccError::Io(format!(
            "kernel 的符号表读数不是本工具认可的 JSON（第 {} 字节）：{}",
            self.pos,
            msg.as_ref()
        )))
    }

    fn peek(&self) -> Option<u8> {
        self.bytes.get(self.pos).copied()
    }

    fn skip_ws(&mut self) {
        while matches!(self.peek(), Some(b' ') | Some(b'\t') | Some(b'\n') | Some(b'\r')) {
            self.pos += 1;
        }
    }

    fn eat(&mut self, c: u8) -> Outcome<()> {
        self.skip_ws();
        match self.peek() {
            Some(got) if got == c => {
                self.pos += 1;
                Ok(())
            }
            _ => self.fail(format!("应为 `{}`，实为 {:?}", c as char, self.peek().map(char::from))),
        }
    }

    fn value(&mut self) -> Outcome<Json> {
        self.skip_ws();
        match self.peek() {
            Some(b'{') => self.object(),
            Some(b'[') => self.array(),
            Some(b'"') => Ok(Json::Str(self.string()?)),
            Some(b't') => self.lit("true", Json::Bool(true)),
            Some(b'f') => self.lit("false", Json::Bool(false)),
            Some(b'n') => self.lit("null", Json::Null),
            Some(c) if c == b'-' || c.is_ascii_digit() => self.number(),
            other => self.fail(format!("值起始处出现意外字节 {:?}", other.map(char::from))),
        }
    }

    fn lit(&mut self, word: &str, v: Json) -> Outcome<Json> {
        if self.bytes[self.pos..].starts_with(word.as_bytes()) {
            self.pos += word.len();
            Ok(v)
        } else {
            self.fail(format!("应为字面量 `{word}`"))
        }
    }

    /// 整数：Kotlin 侧全是 `Int` 直拼（无小数、无指数、无前导零），
    /// 存 i64 后按 `to_string()` 回写即可逐字节还原。
    fn number(&mut self) -> Outcome<Json> {
        let start = self.pos;
        if self.peek() == Some(b'-') {
            self.pos += 1;
        }
        let digits_start = self.pos;
        while matches!(self.peek(), Some(c) if c.is_ascii_digit()) {
            self.pos += 1;
        }
        if self.pos == digits_start {
            return self.fail("数字缺少数位");
        }
        if matches!(self.peek(), Some(b'.') | Some(b'e') | Some(b'E')) {
            return self.fail("读数里出现非整数数值（Kotlin 侧的 stats/defs 字段全是 Int）");
        }
        let text = std::str::from_utf8(&self.bytes[start..self.pos])
            .map_err(|e| ErbccError::Io(format!("数字片段不是 UTF-8: {e}")))?;
        let v = text
            .parse::<i64>()
            .map_err(|e| ErbccError::Io(format!("读数整数 `{text}` 越界: {e}")))?;
        Ok(Json::Num(v))
    }

    fn object(&mut self) -> Outcome<Json> {
        self.eat(b'{')?;
        let mut fields = Vec::new();
        if self.peek() == Some(b'}') {
            self.pos += 1;
            return Ok(Json::Obj(fields));
        }
        loop {
            self.skip_ws();
            let key = match self.peek() {
                Some(b'"') => self.string()?,
                _ => return self.fail("对象的键不是字符串"),
            };
            self.eat(b':')?;
            let value = self.value()?;
            fields.push((key, value));
            self.skip_ws();
            match self.peek() {
                Some(b',') => self.pos += 1,
                Some(b'}') => {
                    self.pos += 1;
                    return Ok(Json::Obj(fields));
                }
                _ => return self.fail("对象成员之间缺 `,` 或收尾 `}`"),
            }
        }
    }

    fn array(&mut self) -> Outcome<Json> {
        self.eat(b'[')?;
        let mut items = Vec::new();
        self.skip_ws();
        if self.peek() == Some(b']') {
            self.pos += 1;
            return Ok(Json::Arr(items));
        }
        loop {
            items.push(self.value()?);
            self.skip_ws();
            match self.peek() {
                Some(b',') => self.pos += 1,
                Some(b']') => {
                    self.pos += 1;
                    return Ok(Json::Arr(items));
                }
                _ => return self.fail("数组元素之间缺 `,` 或收尾 `]`"),
            }
        }
    }

    /// 字符串：转义表的**解码**侧。Kotlin 的 `jstr`（`Main.kt:1098-1112`）只会产出
    /// `\"` `\\` `\n` `\r` `\t` 与 `\u00XX`（XX < 0x20，小写十六进制），其余字节
    /// （含非 ASCII 与 `/`）原样写出；本侧按同一张表解码，多余的标准转义也接受
    /// （`\/` `\b` `\f`），但解码后重新编码时会按 Kotlin 口径回写 —— 回写不等即对拍红。
    fn string(&mut self) -> Outcome<String> {
        self.eat(b'"')?;
        let mut out: Vec<u8> = Vec::new();
        loop {
            let c = match self.peek() {
                Some(c) => c,
                None => return self.fail("字符串未闭合"),
            };
            self.pos += 1;
            match c {
                b'"' => {
                    return String::from_utf8(out).map_err(|e| {
                        ErbccError::Io(format!("读数里的字符串不是合法 UTF-8: {e}"))
                    });
                }
                b'\\' => {
                    let e = self.peek().ok_or_else(|| {
                        ErbccError::Io("kernel 的符号表读数：反斜杠后没有转义字符".to_string())
                    })?;
                    self.pos += 1;
                    match e {
                        b'"' => out.push(b'"'),
                        b'\\' => out.push(b'\\'),
                        b'/' => out.push(b'/'),
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(0x08),
                        b'f' => out.push(0x0C),
                        b'u' => {
                            let cp = self.hex4()?;
                            // Kotlin 的 jstr 按 UTF-16 码元处理，落盘时非 BMP 字符会被
                            // Java 的 UTF-8 编码器替换成 `?`，所以读数里不会出现代理对；
                            // 真出现了就是读数不是本工具产的（或口径变了），当场报红不静默。
                            if (0xD800..0xE000).contains(&cp) {
                                return self.fail(format!(
                                    "读数里出现孤立代理码元 \\u{cp:04x}（Kotlin 的 jstr 不会产出它）"
                                ));
                            }
                            let ch = char::from_u32(cp).ok_or_else(|| {
                                ErbccError::Io(format!(
                                    "kernel 的符号表读数：\\u{cp:04x} 不是可表示的字符"
                                ))
                            })?;
                            let mut buf = [0u8; 4];
                            out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                        }
                        other => {
                            return self.fail(format!("未知转义 `\\{}`", other as char))
                        }
                    }
                }
                // 控制字符在字符串原文里不合法（Kotlin 侧一律转义），出现即读数坏
                0x00..=0x1F => return self.fail("字符串原文里出现裸控制字符"),
                _ => out.push(c),
            }
        }
    }

    fn hex4(&mut self) -> Outcome<u32> {
        if self.pos + 4 > self.bytes.len() {
            return self.fail("\\u 后不足 4 位十六进制");
        }
        let digits = std::str::from_utf8(&self.bytes[self.pos..self.pos + 4])
            .map_err(|_| ErbccError::Io("kernel 的符号表读数：\\u 的十六进制位不是 UTF-8".to_string()))?;
        let cp = u32::from_str_radix(digits, 16)
            .map_err(|e| ErbccError::Io(format!("kernel 的符号表读数：\\u{digits} 不是十六进制码位: {e}")))?;
        self.pos += 4;
        Ok(cp)
    }
}

/// 取对象字段（不存在 → None）。
fn field<'j>(obj: &'j [(String, Json)], key: &str) -> Option<&'j Json> {
    obj.iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

/// 断言对象的键集恰好是 `known`（多键 = Kotlin 侧加了字段而本路没跟，
/// 静默丢字段会把差异写进产物，所以当场报红）。
fn check_keys(obj: &[(String, Json)], known: &[&str], ctx: &str) -> Outcome<()> {
    for (k, _) in obj {
        if !known.contains(&k.as_str()) {
            return Err(ErbccError::Io(format!(
                "{ctx} 出现未知字段 `{k}`（本路只认 {:?}）：kernel 读数字段表与 Rust 序列化层已分叉，拒绝静默丢字段",
                known
            )));
        }
    }
    Ok(())
}

fn req<'j>(obj: &'j [(String, Json)], key: &str, ctx: &str) -> Outcome<&'j Json> {
    field(obj, key).ok_or_else(|| {
        ErbccError::Io(format!("{ctx} 缺字段 `{key}`（`Main.kt:263-299` 的字段表里它是必写的）"))
    })
}

fn as_str<'j>(v: &'j Json, ctx: &str, key: &str) -> Outcome<&'j str> {
    match v {
        Json::Str(s) => Ok(s.as_str()),
        other => Err(ErbccError::Io(format!("{ctx}.{key} 应是字符串，实为{}", other.ctx()))),
    }
}

fn as_i64(v: &Json, ctx: &str, key: &str) -> Outcome<i64> {
    match v {
        Json::Num(n) => Ok(*n),
        other => Err(ErbccError::Io(format!("{ctx}.{key} 应是整数，实为{}", other.ctx()))),
    }
}

/// 布尔字段：Kotlin 只在为真时写出，缺键 = false（`Main.kt:294-298`）。
/// 显式的 `false` **不接受** —— Kotlin 侧不会那样写，出现即读数不是本工具产的，
/// 静默接受会把「条件字段」的语义变成「恒有字段」，序列化时就与基准不等了。
fn as_flag(obj: &[(String, Json)], key: &str, ctx: &str) -> Outcome<bool> {
    match field(obj, key) {
        None => Ok(false),
        Some(Json::Bool(true)) => Ok(true),
        Some(Json::Bool(false)) => Err(ErbccError::Io(format!(
            "{ctx}.{key} = false：Kotlin 的条件字段只在为真时写出（`Main.kt:294-298`），\
             读数里不该出现显式 false"
        ))),
        Some(other) => Err(ErbccError::Io(format!(
            "{ctx}.{key} 应是 true 或键本身缺席（`Main.kt:294-298` 的条件字段），实为{}",
            other.ctx()
        ))),
    }
}

fn as_str_list(v: &Json, ctx: &str, key: &str) -> Outcome<Vec<String>> {
    match v {
        Json::Arr(items) => items
            .iter()
            .map(|i| match i {
                Json::Str(s) => Ok(s.clone()),
                other => Err(ErbccError::Io(format!(
                    "{ctx}.{key} 的元素应是字符串，实为{}",
                    other.ctx()
                ))),
            })
            .collect(),
        other => Err(ErbccError::Io(format!(
            "{ctx}.{key} 应是字符串数组，实为{}",
            other.ctx()
        ))),
    }
}

const STATS_KEYS: [&str; 8] = [
    "totalDefs",
    "uniqueNames",
    "duplicateNames",
    "nonEventDuplicates",
    "eventDuplicates",
    "shadowedDefs",
    "methodDefs",
    "collator",
];
const DEF_KEYS: [&str; 14] = [
    "file",
    "line",
    "fileIndex",
    "argCount",
    "argIntCount",
    "argStrCount",
    "localSize",
    "localsSize",
    "dimSpecs",
    "method",
    "only",
    "pri",
    "later",
    "single",
];
const GROUP_KEYS: [&str; 4] = ["key", "isEvent", "count", "defs"];
const DIM_KEYS: [&str; 5] = ["name", "isStr", "dims", "keywords", "initializer"];

/// 解析 kernel 的 `--json` 读数（严格：未知字段/类型不符/未闭合都报红，不猜默认值）。
pub fn parse_readings(text: &str) -> Outcome<SymbolSummary> {
    let mut cur = Cursor::new(text);
    let root = cur.value()?;
    cur.skip_ws();
    if cur.peek().is_some() {
        return cur.fail("读数 JSON 之后还有多余内容（kernel 只该写一份对象）");
    }
    let Json::Obj(fields) = root else {
        return Err(ErbccError::Io(format!(
            "符号表读数的顶层应是对象，实为{}",
            root.ctx()
        )));
    };
    check_keys(&fields, &["stats", "duplicates"], "读数顶层")?;

    let stats_node = req(&fields, "stats", "读数顶层")?;
    let Json::Obj(stats) = stats_node else {
        return Err(ErbccError::Io("读数的 stats 不是对象".into()));
    };
    check_keys(stats, &STATS_KEYS, "stats")?;
    let stats = Stats {
        total_defs: as_i64(req(stats, "totalDefs", "stats")?, "stats", "totalDefs")?,
        unique_names: as_i64(req(stats, "uniqueNames", "stats")?, "stats", "uniqueNames")?,
        duplicate_names: as_i64(req(stats, "duplicateNames", "stats")?, "stats", "duplicateNames")?,
        non_event_duplicates: as_i64(
            req(stats, "nonEventDuplicates", "stats")?,
            "stats",
            "nonEventDuplicates",
        )?,
        event_duplicates: as_i64(req(stats, "eventDuplicates", "stats")?, "stats", "eventDuplicates")?,
        shadowed_defs: as_i64(req(stats, "shadowedDefs", "stats")?, "stats", "shadowedDefs")?,
        method_defs: as_i64(req(stats, "methodDefs", "stats")?, "stats", "methodDefs")?,
        collator: as_str(req(stats, "collator", "stats")?, "stats", "collator")?.to_string(),
    };

    let dups_node = req(&fields, "duplicates", "读数顶层")?;
    let Json::Arr(groups) = dups_node else {
        return Err(ErbccError::Io("读数的 duplicates 不是数组".into()));
    };
    let mut duplicates = Vec::with_capacity(groups.len());
    for (gi, g) in groups.iter().enumerate() {
        let ctx = format!("duplicates[{gi}]");
        let Json::Obj(gobj) = g else {
            return Err(ErbccError::Io(format!("{ctx} 不是对象")));
        };
        check_keys(gobj, &GROUP_KEYS, &ctx)?;
        let defs_node = req(gobj, "defs", &ctx)?;
        let Json::Arr(defs) = defs_node else {
            return Err(ErbccError::Io(format!("{ctx}.defs 不是数组")));
        };
        // `count` 只作校验用（`Main.kt:268` 写的是 `g.defs.size`）：不等 = 读数自相矛盾
        let count = as_i64(req(gobj, "count", &ctx)?, &ctx, "count")?;
        if count != defs.len() as i64 {
            return Err(ErbccError::Io(format!(
                "{ctx}.count = {count} 与 defs 的 {} 条不符（`Main.kt:268` 的口径是 defs 长度）",
                defs.len()
            )));
        }
        let mut out_defs = Vec::with_capacity(defs.len());
        for (di, d) in defs.iter().enumerate() {
            let dctx = format!("{ctx}.defs[{di}]");
            let Json::Obj(dobj) = d else {
                return Err(ErbccError::Io(format!("{dctx} 不是对象")));
            };
            check_keys(dobj, &DEF_KEYS, &dctx)?;
            let dim_specs = match field(dobj, "dimSpecs") {
                None => Vec::new(),
                Some(Json::Arr(list)) => {
                    let mut v = Vec::with_capacity(list.len());
                    for (si, s) in list.iter().enumerate() {
                        let sctx = format!("{dctx}.dimSpecs[{si}]");
                        let Json::Obj(sobj) = s else {
                            return Err(ErbccError::Io(format!("{sctx} 不是对象")));
                        };
                        check_keys(sobj, &DIM_KEYS, &sctx)?;
                        v.push(DimSpec {
                            name: as_str(req(sobj, "name", &sctx)?, &sctx, "name")?.to_string(),
                            is_str: as_i64_bool(req(sobj, "isStr", &sctx)?, &sctx, "isStr")?,
                            dims: as_str_list(req(sobj, "dims", &sctx)?, &sctx, "dims")?,
                            keywords: as_str_list(req(sobj, "keywords", &sctx)?, &sctx, "keywords")?,
                            initializer: match field(sobj, "initializer") {
                                None => None,
                                Some(v) => Some(as_str(v, &sctx, "initializer")?.to_string()),
                            },
                        });
                    }
                    v
                }
                Some(other) => {
                    return Err(ErbccError::Io(format!(
                        "{dctx}.dimSpecs 应是数组（空时也写成 `[]`，`Main.kt:273` 的判据是非空才写），实为{}",
                        other.ctx()
                    )))
                }
            };
            out_defs.push(LabelDef {
                file: as_str(req(dobj, "file", &dctx)?, &dctx, "file")?.to_string(),
                line: as_i64(req(dobj, "line", &dctx)?, &dctx, "line")?,
                file_index: as_i64(req(dobj, "fileIndex", &dctx)?, &dctx, "fileIndex")?,
                arg_count: as_i64(req(dobj, "argCount", &dctx)?, &dctx, "argCount")?,
                arg_int_count: as_i64(req(dobj, "argIntCount", &dctx)?, &dctx, "argIntCount")?,
                arg_str_count: as_i64(req(dobj, "argStrCount", &dctx)?, &dctx, "argStrCount")?,
                local_size: as_i64(req(dobj, "localSize", &dctx)?, &dctx, "localSize")?,
                locals_size: as_i64(req(dobj, "localsSize", &dctx)?, &dctx, "localsSize")?,
                dim_specs,
                method: as_flag(dobj, "method", &dctx)?,
                only: as_flag(dobj, "only", &dctx)?,
                pri: as_flag(dobj, "pri", &dctx)?,
                later: as_flag(dobj, "later", &dctx)?,
                single: as_flag(dobj, "single", &dctx)?,
            });
        }
        duplicates.push(DuplicateGroup {
            key: as_str(req(gobj, "key", &ctx)?, &ctx, "key")?.to_string(),
            is_event: as_i64_bool(req(gobj, "isEvent", &ctx)?, &ctx, "isEvent")?,
            defs: out_defs,
        });
    }
    Ok(SymbolSummary { stats, duplicates })
}

fn as_i64_bool(v: &Json, ctx: &str, key: &str) -> Outcome<bool> {
    match v {
        Json::Bool(b) => Ok(*b),
        other => Err(ErbccError::Io(format!(
            "{ctx}.{key} 应是布尔，实为{}",
            other.ctx()
        ))),
    }
}

// ---------------------------------------------------------------------------
// JSON 读数：序列化
// ---------------------------------------------------------------------------

/// 字符串转义 —— 逐分支复刻 Kotlin `Main.kt:1099-1108` 的 `jstr`：
/// `"` `\\` `\n` `\r` `\t` 四类短转义，其余 **码元 < 0x20** 走 `\u00xx`（小写十六进制、
/// 恒 4 位），其它字符（含非 ASCII 与 `/`、0x7F）**原样输出**。
///
/// 口径差异（写进交付的「与基准差异」）：Kotlin 遍历的是 **UTF-16 码元**，
/// 非 BMP 字符会拆成一对孤立代理、落盘时被 Java 的 UTF-8 编码器替换成 `?`；
/// 本侧遍历 Unicode 标量、原样写出真 UTF-8。本仓语料无此类字符（卡点见交付）。
pub fn jstr(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len() + 2);
    out.push('"');
    for c in raw.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            other if (other as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", other as u32)),
            other => out.push(other),
        }
    }
    out.push('"');
    out
}

/// 序列化一份读数（`Main.kt:263-304` 的 `StringBuilder` 逐段对齐：LF、两空格缩进、
/// `", "` 分隔、条件字段、末行 `  ]\n}\n`）。
pub fn to_json(summary: &SymbolSummary) -> String {
    let mut s = String::new();
    let st = &summary.stats;
    // Main.kt:263-266
    s.push_str("{\n  \"stats\": {\"totalDefs\": ");
    s.push_str(&st.total_defs.to_string());
    s.push_str(", \"uniqueNames\": ");
    s.push_str(&st.unique_names.to_string());
    s.push_str(", \"duplicateNames\": ");
    s.push_str(&st.duplicate_names.to_string());
    s.push_str(", \"nonEventDuplicates\": ");
    s.push_str(&st.non_event_duplicates.to_string());
    s.push_str(", \"eventDuplicates\": ");
    s.push_str(&st.event_duplicates.to_string());
    s.push_str(", \"shadowedDefs\": ");
    s.push_str(&st.shadowed_defs.to_string());
    s.push_str(", \"methodDefs\": ");
    s.push_str(&st.method_defs.to_string());
    s.push_str(", \"collator\": \"");
    // Kotlin 侧 collator 是 `"${table.config.collator}"`（枚举名，不过 jstr，`Main.kt:266`）
    s.push_str(&st.collator);
    s.push_str("\"},\n  \"duplicates\": [\n");

    let last_group = summary.duplicates.len().saturating_sub(1);
    for (gi, g) in summary.duplicates.iter().enumerate() {
        // Main.kt:268
        s.push_str("    {\"key\": ");
        s.push_str(&jstr(&g.key));
        s.push_str(", \"isEvent\": ");
        s.push_str(&g.is_event.to_string());
        s.push_str(", \"count\": ");
        s.push_str(&(g.defs.len() as i64).to_string());
        s.push_str(", \"defs\": [");
        for (di, d) in g.defs.iter().enumerate() {
            if di > 0 {
                s.push_str(", ");
            }
            // Main.kt:271-272
            s.push_str("{\"file\": ");
            s.push_str(&jstr(&d.file));
            s.push_str(", \"line\": ");
            s.push_str(&d.line.to_string());
            s.push_str(", \"fileIndex\": ");
            s.push_str(&d.file_index.to_string());
            s.push_str(", \"argCount\": ");
            s.push_str(&d.arg_count.to_string());
            s.push_str(", \"argIntCount\": ");
            s.push_str(&d.arg_int_count.to_string());
            s.push_str(", \"argStrCount\": ");
            s.push_str(&d.arg_str_count.to_string());
            s.push_str(", \"localSize\": ");
            s.push_str(&d.local_size.to_string());
            s.push_str(", \"localsSize\": ");
            s.push_str(&d.locals_size.to_string());
            // Main.kt:273-293：非空才写 dimSpecs
            if !d.dim_specs.is_empty() {
                s.push_str(", \"dimSpecs\": [");
                for (si, spec) in d.dim_specs.iter().enumerate() {
                    if si > 0 {
                        s.push_str(", ");
                    }
                    s.push_str("{\"name\": ");
                    s.push_str(&jstr(&spec.name));
                    s.push_str(", \"isStr\": ");
                    s.push_str(&spec.is_str.to_string());
                    s.push_str(", \"dims\": [");
                    for (xi, dim) in spec.dims.iter().enumerate() {
                        if xi > 0 {
                            s.push_str(", ");
                        }
                        s.push_str(&jstr(dim));
                    }
                    s.push_str("], \"keywords\": [");
                    // Main.kt:283-286：keywords 是枚举名直拼 `"\"$k\""`，不走 jstr
                    for (ki, kw) in spec.keywords.iter().enumerate() {
                        if ki > 0 {
                            s.push_str(", ");
                        }
                        s.push('"');
                        s.push_str(kw);
                        s.push('"');
                    }
                    s.push(']');
                    if let Some(init) = &spec.initializer {
                        // Main.kt:289：null 时整个键不写
                        s.push_str(", \"initializer\": ");
                        s.push_str(&jstr(init));
                    }
                    s.push('}');
                }
                s.push(']');
            }
            // Main.kt:294-298：五个条件字段，顺序固定
            if d.method {
                s.push_str(", \"method\": true");
            }
            if d.only {
                s.push_str(", \"only\": true");
            }
            if d.pri {
                s.push_str(", \"pri\": true");
            }
            if d.later {
                s.push_str(", \"later\": true");
            }
            if d.single {
                s.push_str(", \"single\": true");
            }
            s.push('}');
        }
        s.push_str("]}");
        s.push_str(if gi == last_group { "\n" } else { ",\n" });
    }
    s.push_str("  ]\n}\n");
    s
}

/// 骨架冻结签名：kernel 读数文本 → 确定性 JSON 文本。
pub fn json_summary(readings: &str) -> Outcome<String> {
    Ok(to_json(&parse_readings(readings)?))
}

// ---------------------------------------------------------------------------
// --collator-check 报告文本
// ---------------------------------------------------------------------------

/// kernel stdout 拆成「可直接透出的读数行」+「比较器自检块（解析成类型）」+
/// 「`符号表摘要:` 行的取值」。
///
/// 剔除规则（都是编排层自己会重出的部分，`Main.kt:250/261-308`）：
/// 1. 自检块：标题行 + 其**前导空行**（`println()`，`Main.kt:250`）到 `结论:` 行；
/// 2. 末行 `符号表摘要: <绝对路径>` 与它的前导空行（`println("\n符号表摘要: …")`，
///    `Main.kt:307`）—— 打的是 kernel 那趟写出去的路径，不是用户要的路径。
pub fn parse_kernel_report(stdout: &str) -> Outcome<KernelReport> {
    let lines: Vec<&str> = stdout.lines().collect();
    let mut forwarded: Vec<String> = Vec::with_capacity(lines.len());
    let mut collator: Option<CollatorCheck> = None;
    let mut summary_path: Option<String> = None;
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        if line == COLLATOR_HEADER {
            // 标题行的前导空行归自检块（Kotlin 是 `println()` 后接标题）
            if forwarded.last().map(String::as_str) == Some("") {
                forwarded.pop();
            }
            let mut j = i;
            while j < lines.len() && !lines[j].starts_with("结论: ") {
                j += 1;
            }
            if j == lines.len() {
                return Err(ErbccError::Io(format!(
                    "kernel stdout 的比较器自检块没有 `结论:` 收尾行（`Main.kt:258`），拒绝半块解析"
                )));
            }
            collator = Some(parse_collator_check(&lines[i..=j])?);
            i = j + 1;
            continue;
        }
        if let Some(rest) = line.strip_prefix(SUMMARY_PREFIX) {
            // 它前面的空行是同一个 `println` 打的换行，一并交还编排层
            if forwarded.last().map(String::as_str) == Some("") {
                forwarded.pop();
            }
            summary_path = Some(rest.to_string());
            i += 1;
            continue;
        }
        forwarded.push(line.to_string());
        i += 1;
    }
    Ok(KernelReport { forwarded, collator, summary_path })
}

/// [parse_kernel_report] 的产物。
#[derive(Debug, Clone)]
pub struct KernelReport {
    /// 由编排层原样透出的读数行（不含行终止符）
    pub forwarded: Vec<String>,
    /// 解析出的比较器自检读数（kernel 未跑自检时是 None）
    pub collator: Option<CollatorCheck>,
    /// kernel 自己那趟的 `--json` 落点（只用于校验 kernel 真的落了读数，不转发）
    pub summary_path: Option<String>,
}

/// 比较器自检块 → 类型化读数（逐行按 `Main.kt:251-258` 的字面量剥，剥不动即报红）。
pub fn parse_collator_check(block: &[&str]) -> Outcome<CollatorCheck> {
    let err = |m: String| ErbccError::Io(format!("kernel 的比较器自检读数解析失败：{m}"));
    let mut it = block.iter();
    let header = it.next().ok_or_else(|| err("空块".to_string()))?;
    if *header != COLLATOR_HEADER {
        return Err(err(format!("首行应是 `{COLLATOR_HEADER}`，实为 `{header}`")));
    }
    let line = it.next().ok_or_else(|| err("缺 `文件数 …` 行（`Main.kt:252`）".to_string()))?;
    let rest = line
        .strip_prefix("文件数 ")
        .ok_or_else(|| err(format!("`文件数` 行前缀不对：`{line}`")))?;
    let (files, shifted_part) = rest
        .split_once("  绝对位置位移 ")
        .ok_or_else(|| err(format!("`文件数` 行缺 `  绝对位置位移 ` 分隔：`{line}`")))?;
    let shifted = shifted_part
        .strip_suffix("（含被动位移，仅供观察）")
        .ok_or_else(|| err(format!("`文件数` 行缺尾注：`{line}`")))?;
    let files = num(files, line, "文件数")?;
    let shifted_files = num(shifted, line, "绝对位置位移")?;

    let mut first_divergence: Option<String> = None;
    let mut dup_line: Option<&str> = None;
    let mut group_line: Option<&str> = None;
    let mut winner_flips = Vec::new();
    let mut event_order_flips = Vec::new();
    let mut conclusion: Option<&str> = None;
    for l in it {
        if let Some(v) = l.strip_prefix("  首个位置差异: ") {
            first_divergence = Some(v.to_string());
        } else if l.starts_with("重名定义文件 ") {
            dup_line = Some(*l);
        } else if l.starts_with("重名组 ") {
            group_line = Some(*l);
        } else if let Some(v) = l.strip_prefix("  翻转组(定义): ") {
            winner_flips = v.split(", ").map(|s| s.to_string()).collect();
        } else if let Some(v) = l.strip_prefix("  翻转组(事件序): ") {
            event_order_flips = v.split(", ").map(|s| s.to_string()).collect();
        } else if let Some(v) = l.strip_prefix("结论: ") {
            conclusion = Some(v);
        } else {
            return Err(err(format!("自检块里出现未登记的行：`{l}`")));
        }
    }
    let dup_line = dup_line.ok_or_else(|| err("缺 `重名定义文件 …` 行（`Main.kt:254`）".to_string()))?;
    let group_line = group_line.ok_or_else(|| err("缺 `重名组 …` 行（`Main.kt:255`）".to_string()))?;
    let conclusion = conclusion.ok_or_else(|| err("缺 `结论: ` 行（`Main.kt:258`）".to_string()))?;

    // `重名定义文件 N 个，两两相对次序翻转 I / P 对`
    let dpart = dup_line
        .strip_prefix("重名定义文件 ")
        .ok_or_else(|| err(format!("`重名定义文件` 行前缀不对：`{dup_line}`")))?;
    let (n_txt, inv_part) = dpart
        .split_once(" 个，两两相对次序翻转 ")
        .ok_or_else(|| err(format!("`重名定义文件` 行缺分隔：`{dup_line}`")))?;
    let (inv_txt, pairs_txt) = inv_part
        .split_once(" / ")
        .ok_or_else(|| err(format!("`重名定义文件` 行缺 ` / `：`{dup_line}`")))?;
    let pairs = pairs_txt
        .strip_suffix(" 对")
        .ok_or_else(|| err(format!("`重名定义文件` 行应以 ` 对` 收尾：`{dup_line}`")))?;
    let duplicate_def_files = num(n_txt, dup_line, "重名定义文件")?;
    let duplicate_def_inversions = num(inv_txt, dup_line, "相对次序翻转")?;
    let duplicate_def_pairs = num(pairs, dup_line, "对数")?;

    // `重名组 G  定义集合/次序翻转 F  事件执行序翻转 E`
    let gpart = group_line
        .strip_prefix("重名组 ")
        .ok_or_else(|| err(format!("`重名组` 行前缀不对：`{group_line}`")))?;
    let (g_txt, wf_part) = gpart
        .split_once("  定义集合/次序翻转 ")
        .ok_or_else(|| err(format!("`重名组` 行缺分隔：`{group_line}`")))?;
    let (wf_txt, ef_txt) = wf_part
        .split_once("  事件执行序翻转 ")
        .ok_or_else(|| err(format!("`重名组` 行缺 `  事件执行序翻转 `：`{group_line}`")))?;
    let duplicate_groups = num(g_txt, group_line, "重名组")?;
    let winner_flip_count = num(wf_txt, group_line, "定义集合/次序翻转")?;
    let event_order_flip_count = num(ef_txt, group_line, "事件执行序翻转")?;

    // 行内计数与翻转组清单行必须自洽（`Main.kt:255-257`：清单行只在非空时打，
    // 且打的就是同一个列表）——不自洽说明读数和判据分叉，当场报红不猜。
    if winner_flip_count != winner_flips.len() as i64 || event_order_flip_count != event_order_flips.len() as i64 {
        return Err(err(format!(
            "`重名组` 行的翻转计数（定义 {winner_flip_count} / 事件 {event_order_flip_count}）与清单行条数\
             （定义 {} / 事件 {}）不符：{}",
            winner_flips.len(),
            event_order_flips.len(),
            group_line
        )));
    }

    let c = CollatorCheck {
        files,
        shifted_files,
        first_divergence,
        duplicate_def_files,
        duplicate_def_inversions,
        duplicate_def_pairs,
        duplicate_groups,
        winner_flips,
        event_order_flips,
    };
    let expected = if c.passed() { CONCLUSION_PASS } else { CONCLUSION_FAIL };
    let got = format!("结论: {conclusion}");
    if got != expected {
        return Err(err(format!(
            "kernel 打的结论行与判据 [CollatorCheck::passed] 不等：期望 `{expected}`，实为 `{got}`\
             （判据 = Symbols.kt:416-417）"
        )));
    }
    Ok(c)
}

fn num(text: &str, line: &str, what: &str) -> Outcome<i64> {
    text.trim()
        .parse::<i64>()
        .map_err(|e| ErbccError::Io(format!("自检读数 `{what}` = `{text}` 不是整数（行：`{line}`）: {e}")))
}

impl CollatorCheck {
    /// 自检块的报告文本（逐行照 `Main.kt:251-258`；**不含**前导空行，由调用方补）。
    pub fn lines(&self) -> Vec<String> {
        let mut out = Vec::with_capacity(8);
        out.push(COLLATOR_HEADER.to_string());
        out.push(format!(
            "文件数 {}  绝对位置位移 {}（含被动位移，仅供观察）",
            self.files, self.shifted_files
        ));
        if let Some(v) = &self.first_divergence {
            out.push(format!("  首个位置差异: {v}"));
        }
        out.push(format!(
            "重名定义文件 {} 个，两两相对次序翻转 {} / {} 对",
            self.duplicate_def_files, self.duplicate_def_inversions, self.duplicate_def_pairs
        ));
        out.push(format!(
            "重名组 {}  定义集合/次序翻转 {}  事件执行序翻转 {}",
            self.duplicate_groups,
            self.winner_flips.len(),
            self.event_order_flips.len()
        ));
        if !self.winner_flips.is_empty() {
            // Main.kt:256 的 `joinToString()` 默认分隔符 `, `
            out.push(format!("  翻转组(定义): {}", self.winner_flips.join(", ")));
        }
        if !self.event_order_flips.is_empty() {
            out.push(format!("  翻转组(事件序): {}", self.event_order_flips.join(", ")));
        }
        out.push(if self.passed() {
            CONCLUSION_PASS.to_string()
        } else {
            CONCLUSION_FAIL.to_string()
        });
        out
    }
}
