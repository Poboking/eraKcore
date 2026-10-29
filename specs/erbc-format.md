# erbc-format.md —— `.erbc` 容器规约（v1 定稿 / v2 定稿）

状态：**v1 定稿**（CONTRACT-M4-A §2 / 实施方案 §3 P0 第 8 项）；**v2 定稿**（段 7/8：
调用点实参表 + 函数头部形参名表，向后兼容 v1）。
实现：`core/code/ErbcFormat.kt`（常量与字节原语）、`ErbcWriter.kt`（编码）、`ErbcReader.kt`（解码）、
`core/loader/ProgramLoader.kt` / `MappedErbc.kt` / `ErbcStream.kt`（三路装载）。
单测：`core/code/ErbcRoundTripTest.kt`、`core/code/ErbcDeterminismTest.kt`、
`core/code/ErbcV2SectionsTest.kt`、`core/loader/ErbcV2RefBindingTest.kt`。
上位决策：ADR-001（构建期编译 → `.erbc` → mmap 零解析）、ADR-004（i64 + 字符串驻留 + 指令定长 4×u32）。
本文件是唯一权威；`docs/eratw-项目实施方案.md` §4 的 v1 草案只做导航，冲突处以本文件为准。

术语：**产物** = erbcc 编码出的 `.erbc`；**装载** = ERBC-B 把产物变回 `Program`；
**段（section）** = 段表寻址的一块独立字节区；**记录（record）** = 池元素的可变长字节表示。

---

## 1 容器总则

1. **字节序：小端**（little-endian）。`u8 / u16 / u32 / i64` 全部按小端读写。
   理由：目标平台（Android/ARM/x86）与 JVM 的 `IntArray` 随机寻址都按小端最省事；
   固定一种字节序是「零解析装载」的前提（ADR-001）。
2. **有符号与无符号**：线格式统一按无符号宽度读写；`i32` 与 `u32` 同宽（4 字节），
   `SlotAddr.NONE = 0xFFFFFFFF`、行号 `-1` 都靠补码原样往返（`core/code/Instruction.kt:81-114`）。
3. **对齐**：v1 **不做**任何填充对齐；段与段紧挨着排。装载侧要按段 `mmap` 时自行复制或用
   `FileChannel` 的区间读，不依赖段起始地址的对齐性（分片协议见 §7）。
4. **可缺省引用哨兵**：`0xFFFFFFFF`（即 `Int` 的 -1）表示「此处为 null」，
   只用于可缺省的节点下标 / 字符串下标 / 列表长度（`SpPrintImgArgument.param` 为 null 等）。
   任何真实下标都必须 `>= 0`，装载期逐条检查。
5. **文件末尾允许多余字节**（assets 打包/分片拼接的对齐填充），reader 只按段表寻址，不校验尾部长度。
6. **禁止静默降级**：任何「读不懂 / 表达不出 / 声明缺段却仍需该段」的情况一律抛异常：
   编码期 `ErbcEncodeException`，解码期 `ErbcDecodeException`（`ErbcFormat.kt` 末尾）。
   容错只允许发生在**明确写了默认值的保留字段**上，而 v1 的每个保留字段都写了「非 0 即报错」。

---

## 2 文件头与段表

```
偏移 0  : u8[4]  magic  = 45 52 42 43 ("ERBC")
偏移 4  : u16    version           1 或 2
偏移 6  : u16    flags             段存在性位（§3）
偏移 8  : u16    sectionCount      段表槽数：v1 = 7，v2 = 9
偏移 10 : u32[sectionCount] sectionOffsets
                                   段表；值 = 该段正文相对文件首的绝对偏移；0 = 该段不存在
偏移 10 + 4×sectionCount : 各段正文（按段号升序连续排列，但装载侧只认段表偏移，不依赖此顺序）
```

| 版本 | `sectionCount` | 头 + 段表字节数 | 段号范围 |
|---|---|---|---|
| 1 | 7 | 38 | 0..6 |
| 2 | 9 | 46 | 0..8（v2 追加 7/8） |

* `magic` 不符 ⇒ `ErbcDecodeException`，消息里给实际 4 字节的十六进制。
* `version` 不是 1 或 2 ⇒ `ErbcDecodeException`（**不做**向前兼容解释，见 §8）。
* `sectionCount` 与 `version` **严格配对**：v1 只认 7、v2 只认 9，其它值 ⇒ `ErbcDecodeException`。
  「v2 头 + 7 槽」若被容忍，段号语义会随文件而变，因此必须当场拒绝。
* `flags` 含该版本未定义位 ⇒ `ErbcDecodeException`（掩码按版本选，见 §3）。
* 偏移 `< 10` 或 `>= 文件长度` ⇒ `ErbcDecodeException`。偏移为 0 只允许表达「该段不存在」。
  装载层（`ProgramLoader.validateSections`）再加一条：偏移必须 `>= 10 + 4×sectionCount`。

**为什么段表是固定槽位而不是 (id, offset) 变长表**：装载侧要 `switch(段号)` 直接取 mmap 区间，
段号必须是编译期常量；空段写 0 比写一个零长段更省字节，也不会让「u32 偏移 × n」的骨架变形。
新增段只能追加为段号 7、8… 并升 `version`（§4）。

**为什么版本与槽数要配对而不是各自宽松**：v1 与 v2 的段号 0..6 逐字节同义，只有槽数不同。
允许错配会在「段 6 是 LINE_TABLE 还是 CALL_ARGS」上产生歧义，而歧义在 golden 对拍里表现为
静默读错数据 —— 按 §1.6 一律当场失败。

---

## 3 标志位与段存在性

| 位 | 名称 | 含义 | 版本 |
|---|---|---|---|
| 0x0001 | `HAS_LINES` | 携带行号表（段 6） | v1 |
| 0x0002 | `HAS_EXPRESSIONS` | 携带表达式池（段 2） | v1 |
| 0x0004 | `HAS_STATEMENTS` | 携带语句池（段 3） | v1 |
| 0x0008 | `HAS_CALL_ARGS` | 携带调用点实参表（段 7） | v2 |
| 0x0010 | `HAS_PARAM_NAMES` | 携带函数头部形参名表（段 8） | v2 |

**自洽规则（双向校验，装载期强制）**：`flags` 的某位置位 ⇔ 对应段 `offset != 0`。
不一致 = 畸形文件，直接报错。允许的位集合按版本选：v1 掩码 = `0x0007`，v2 掩码 = `0x001F`。

**缺段的代价（这是「不许静默降级」的落点）**：

* `HAS_STATEMENTS` 未置位 ⇒ 语句池为空。此时指令流里只要出现任何 marker
  （`op ∈ 29..197`，区间权威 = `Vm.kt:758-769` 的 marker 分派 + `OpcodeTable.kt:548 MAX_STATEMENT_OPCODE`，
  与契约 C3 一致），装载即报
  「本文件声明不含语句池，但 pc=… 的语句 marker … 需要语句下标 …」。
* `HAS_EXPRESSIONS` 未置位 ⇒ 同理，`_EVAL_INT(27)`/`_EVAL_STR(28)` 触发明确错误。
* `HAS_LINES` 未置位 ⇒ `Program.lines` 全填 `-1`。这**不是**降级而是设计意图：
  `-1 = 未知` 是 `Program.kt:117` 的既有口径，行号只服务 CodeEE 报错定位（release 包省 4 字节/指令）。
* `HAS_CALL_ARGS` / `HAS_PARAM_NAMES` 未置位 ⇒ 对应数据为空，且**只能**退化到「类型桶 → `ARG:i`」
  的旧绑定（`ProcessRuntime.kt:547-551`）；该退化不是「猜」，但仍会让 REF/具名形参失效，因此
  `Program.callArgSource` 记录来源（`ERBC_V1` / `ERBC_V2`）、`Program.bindsCallArgsPositionally`
  给出布尔判据，上层必须能查（§5.8/§5.9）。

编码侧同样自洽：`ErbcEncodeOptions.includeStatements=false` 而程序语句池非空 ⇒
`ErbcEncodeException`（写出来的文件必然骗人，所以拒写）；`includeExpressions` 同理。
v1 选项下程序若带 `callArgs` / `paramNames` 也一并拒写（v1 容器没有段 7/8，写出来必丢语义）。
段的存在性还额外受「池非空」约束：池为空时该段直接省略（offset=0，标志位清零），不写空壳段。

---

## 4 段号总表

| 段号 | 名称 | 必选 | 内容 |
|---|---|---|---|
| 0 | `STRING_POOL` | 可选（空池即省略） | 全局驻留字符串池（下标 = 池下标） |
| 1 | `CONSTANT_POOL` | 可选（空池即省略） | i64 常量池 + f64 预留位 |
| 2 | `EXPRESSION_POOL` | 由 `HAS_EXPRESSIONS` 决定 | 表达式节点表 + 池根下标（§5.3） |
| 3 | `STATEMENT_POOL` | 由 `HAS_STATEMENTS` 决定 | 语句身份记录 + 段内节点表（§5.4） |
| 4 | `FUNCTION_TABLE` | **必选** | 函数表 + `$标签` 平行表 + `globalSlots` + `entryFunction` |
| 5 | `CODE` | **必选** | 定长 4×u32 指令流 |
| 6 | `LINE_TABLE` | 由 `HAS_LINES` 决定 | pc → 源码行号 |
| 7 | `CALL_ARGS` | v2 且由 `HAS_CALL_ARGS` 决定 | 调用点 → 逐位实参元数据（§5.8） |
| 8 | `PARAM_NAMES` | v2 且由 `HAS_PARAM_NAMES` 决定 | 函数下标 → 头部形参名（§5.9） |

新增段只能追加为段号 9、10… 并升 `version`；已有段的编号与含义永不改变。
v2 相对 v1 **只追加了段 7/8**：段 0..6 的布局、标签值、判界规则逐字节不变，因此 v1 产物
在 v2 reader 上照旧可装载（只是拿不到段 7/8，装载后 `Program.callArgSource == ERBC_V1`）。

---

## 5 各段布局

### 5.1 字符串表（`STRING_TABLE`，可复用布局）

段 0、以及段 2/3/4 各自的**局部**串池都用同一布局：

```
u32  count
u32  dataLen
u32  offset[count + 1]      相对数据区首；offset[0] = 0，offset[count] = dataLen
u8   data[dataLen]          第 i 项 = data[offset[i] .. offset[i+1])，UTF-8
```

* 用 `count+1` 个偏移同时表达「第 i 项的起点」和「第 i 项的长度」，因此不需要第二张长度表；
  mmap 侧按 `offset[i]..offset[i+1]` 直接切，零拷贝零解析。
* 项长 0 = 空串（合法）。偏移必须单调不减，否则报错。
* **解码严格 UTF-8**：非法序列 ⇒ `ErbcDecodeException`，不允许悄悄变成替换字符。
* **为什么每段自带局部串池**：全局字符串池的下标是**指令操作数**
  （`_SET_STR` 的 b、`_PRINT_LITERAL` 的 a，`OpcodeTable.kt:48`），
  往里追加函数名 / 枚举名 / 语句原文会让已编译指令的池下标整体漂移。
  段 0 的下标必须逐字等于 `ConstantPool` 的字符串池下标（`Program.kt:65-68` 的驻留池）。

### 5.2 段 1：常量池

```
u32 longCount
u32 doubleCount          v1 恒 0
i64 longs[longCount]
```

`ConstantPool` 目前只有 Long 池与 String 池（`Program.kt:36-40`），没有 Double 池，
所以 v1 的 `doubleCount` 只能是 0；非 0 ⇒ `ErbcDecodeException`。
实施方案 §4 骨架里的「常量池 i64 / f64 / 字符串索引」在本版本落成：**i64 已实现、
f64 留位、字符串索引 = 段 0**（详见 §9 未覆盖项 1）。

`_SET_INT` 的 b 是这里的下标（`OpcodeTable.kt:46`），装载期逐条判界。

### 5.3 段 2：表达式池（难点一，处置见 §6.1）

```
u32 nodeCount
u32 poolCount
STRING_TABLE                 段内局部串池（字面量、变量名、函数名、运算符名）
record  node[nodeCount]      按节点下标升序；每条 = u8 标签 + 该标签字段
u32     root[poolCount]      池下标 k → 根节点下标 root[k]
```

**节点表是后序（post-order）发射的**：任何节点的子引用下标必须严格小于自身下标
（装载期逐条检查）。因此读侧**单遍顺序构建、零递归**，深表达式树不会撑爆栈；
这也是选择「节点表 + 叶类型」而不是「内联递归子树」的原因（契约 §2.2 的推荐形态）。

结构相等的子树在写侧被合并为同一节点（`Expr` 是 data class，`Program.kt:70-77` 的池本身就是
结构相等去重）；共享只影响字节数，不影响语义。

节点标签与字段（`tag` 值冻结在 `ErbcFormat.ExprTag`）：

| tag | 节点 | 字段（顺序即线序） |
|---|---|---|
| 1 | `INT_LITERAL` | i64 value, i32 line, i32 col |
| 2 | `STR_LITERAL` | u32 str, i32 line, i32 col |
| 3 | `NULL_TERM` | u8 isInt, i32 line, i32 col |
| 4 | `VAR_REF` | u32 name, u32 subKey(可 null), u8 operandType, u8 flags(bit0 isNoArg / bit1 isConst), u32 n + u32 child[n], i32 line, i32 col |
| 5 | `KEYWORD_INDEX_ARG` | u32 child, u32 varName, i32 dim, i32 line, i32 col |
| 6 | `FUNC_CALL` | u32 name, u8 operandType, u32 n + u32 arg[n](可 null), i32 line, i32 col |
| 7 | `UNARY` | u32 opName, u32 child, i32 line, i32 col |
| 8 | `UNARY_AFTER` | u32 opName, u32 child, i32 line, i32 col |
| 9 | `BINARY` | u32 opName, u32 left, u32 right, u8 operandType, i32 line, i32 col |
| 10 | `TERNARY` | u32 cond, u32 ifTrue, u32 ifFalse, u8 operandType, i32 line, i32 col |
| 11 | `FORM_STRING` | u32 partCount + 部件表, i32 line, i32 col |

* `operandType` 线值：0=INT、1=STR（`OperandType`，`Expr.kt:4-7`）。
* **`line` / `col` 必须逐字往返**：它们是 data class 的组成字段，丢了会让「结构相等去重」的
  口径变化（两条只差行列号的表达式会被错误合并），也会破坏 `Expr` 的相等性断言。
* `FORM_STRING` 部件：`u8 类型`，1=TEXT → `u32 str`；2=EMBEDDED → `u32 ast` +
  转换规则 `u8 类型`：1=CURLY / 2=PERCENT → `u32 width(可 null)` + `u8 alignLeft`；
  3=YEN_AT → `u32 ifTrue` + `u32 ifFalse`（`FormStringTerm.kt:19-52`）。
* 枚举名（`OperatorCode`）走段内串池而不是序号：**序号会随枚举插入而漂移**，
  名字则永远自描述；串池去重后每段最多 29 个运算符名、302 个 `FunctionCode` 名，成本可忽略。

### 5.4 段 3：语句池（难点二，处置见 §6.2）

```
u32 nodeCount
u32 stmtCount
STRING_TABLE                 段内局部串池（raw、FunctionCode 名、参数名、文本…）
record node[nodeCount]       与段 2 同一套节点记录（语句字段里的 VarRef / Expr 落在这里）
record stmt[stmtCount]       按池下标升序，一条记录 = 一个 Statement 实例
```

语句记录：

```
u8   classTag               Statement 具体类（ErbcFormat.StmtTag，37 类）
i32  line
u32  raw                    语句原文（Statement.kt:39 的 abstract val）
u32  codeName               FunctionCode 名（每类都写；派生 code 的类装载后逐字校验回来）
     <类专属字段>
     <参数记录>             仅当该类有 args 构造参数；否则 1 字节 u8 = ArgTag.NONE(0)
```

参数记录：

```
u8   argTag                 Argument 具体类（ErbcFormat.ArgTag，47 类）
u8   header                 bit0 = isConst，bit1 = 下面两字段存在；其余位保留且必须为 0
u32  constStr               （header.bit1 时）
i64  constInt               （header.bit1 时）
     <argTag 专属字段>
```

`isConst / constStr / constInt` 是 `Argument` 基类的可变缓存字段（解析期常量折叠，
`Argument.kt:14-32`；写点见 `ArgBuilders.kt:175-257`），运行期被 PRINT/SET 等快路径直接读，
所以**必须**落盘；`IntAsignArgument` / `StrAsignArgument` 在构造里就把 `constStr` 置成变量名
（`Argument.kt:76-79`、`:90-93`），因此头位为 0 的写法对它们并不成立 —— 用「非默认才写」的位判定。

字段级表（写序 = 读序，逐字段互逆；实现即文档）见
`ErbcWriter.kt:RecordSink.writeArgument` / `ErbcReader.kt:RecordSource.readArgument`
与两侧的同名语句表。规约只锁三件事：**标签值冻结、字段顺序固定、类表穷尽**。
新增类必须追加标签值并升 version（§8），永不复用旧值。

派生字段规则（v1 的关键取舍）：

| 类 | 派生来源 | 处置 |
|---|---|---|
| `AssignStmt.code` | 恒 `SET`（`Statement.kt:116`） | 不传参；装载后校验 `code.name` 与记录一致 |
| `IfStmt.code` | `single ? SIF : IF`（`Statement.kt:181`） | `single` 落盘；装载后校验 |
| `PrintStmt.code` | `print.code`（`Statement.kt:91`） | `PrintSpec` 落盘；装载后校验 |
| `FuncLabelStmt` / `GotoLabelStmt` / `SharpStmt` 的 `code`、`args` | 类内 `get()` 恒 `__NULL__` / `VoidArgument` | 参数记录位必须写 `ArgTag.NONE`，读侧强制 |
| `PrintSpec.name` | `code.name`（`PrintSpec.kt:45`） | 不落盘，读侧回填；编码期若两者不一致 ⇒ 拒写 |

`InstructionStmt` 是 `open` 类且是其余语句类的父类，因此**写侧的 `when` 分支必须把它排在最后**
（`when` 取首个命中分支；与编译器 `ErbProgramCompiler.kt:579-603` 同一纪律）。
这条顺序是格式级约束：一旦错位，`PrintStmt` 会被编成 `INSTRUCTION` 记录，
PRINT 家族的后缀标志全丢 —— 属于会静默改变文字流输出的事故，所以 `when` 用 sealed 穷尽、
读侧再加 `classTag` 与 `code` 的双重校验。

### 5.5 段 4：函数表

```
u32 funcCount
u32 entryFunction           Program.entryFunction（Program.kt:121）
u32 globalSlots             Program.globalSlots（Program.kt:123）
STRING_TABLE                段内局部串池（函数名、标签名）
record func[funcCount]      按 functions 列表原序
    u32 name
    i32 entryPc
    i32 arity
    i32 slotCount
    u32 flags               v1 恒 0
    u32 labelCount
    { u32 labelName, i32 labelPc }[labelCount]
```

* 函数下标 = 记录序（`_CALL` 的 a 引用的就是它，`OpcodeTable.kt:104`）。
* `labelNames` / `labelPcs` 是平行数组，下标即 `_JUMP_DYN` 的 a（`Program.kt:16-23`）；
  名字均为大写归一（`CodeBuilder.declareLabel` 的 `uppercase()`），v1 **原样落盘不再归一**，
  装载侧的 `labelCount` 与两侧长度一致性由写侧保证。
* `flags != 0` ⇒ `ErbcDecodeException`：实施方案 §4 要求「函数表保留是否 EVENT 函数」，
  但 `FunctionInfo`（`Program.kt:7-24`）没有该字段，事件/方法属性目前在
  erbcc 的 `SymbolTable`/`FuncMeta` 一侧。v1 拒绝写一个无处安放的位而不是假装写了（§9 未覆盖项 3）。
* 函数名重复 ⇒ `ErbcDecodeException`（与 `CodeBuilder.declareFunction` 的
  `require(nameToIndex.put(...) == null)` 同口径，`Program.kt:224`）。

### 5.6 段 5：指令流

```
u32 instructionCount
u32 word[instructionCount × 4]     顺序恒为 op, a, b, c
```

定长 4×u32、无 tag 位、操作数语义由 opcode 决定（ADR-004；`Instruction.kt:1-49` 的模式表）。
装载后 `code` 就是 `Program.code`，VM 按 `pc × 4` 直读，不复制、不解释（ADR-001 的零解析）。

装载期逐条判界（`ErbcReader.validate`）：

| 操作数 | 检查 |
|---|---|
| `_SET_INT(2)` 的 b | `< constantCount` |
| `_SET_STR(3)` 的 b、`_PRINT_LITERAL(26)` 的 a | `< stringCount` |
| `_EVAL_INT(27)` / `_EVAL_STR(28)` 的 a | 需 `HAS_EXPRESSIONS` 且 `< exprCount` |
| `_CALL(22)` 的 a | `< funcCount` |
| `op ∈ 29..197`（marker）的 a | 需 `HAS_STATEMENTS` 且 `< statementCount` |
| `entryPc`、每个 `labelPc` | `< instructionCount` |

区间外的编译器扩展操作码（198..201：`_CALL_BY_NAME` / `_JUMP_BY_NAME` / `_JUMP_DYN` / `_RETS`，
`OpcodeTable.kt:518-540`）的 a 是槽号或标签下标，v1 不做跨函数归属检查（需要 pc→函数归属，
归 ERBC-B 的 `FuncMeta` 侧）。

### 5.7 段 6：行号表

```
u32 fileTableCount          v1 恒 0
u32 lineCount               必须 == instructionCount
i32 line[lineCount]         -1 = 未知
```

实施方案 §4 的草案写的是「pc → (文件id, 行)」，但 `Program.lines` 只有行号一列
（`Program.kt:116-117`），文件归属在 erbcc 的符号表一侧。v1 因此**留了 `fileTableCount` 槽位**：
非 0 ⇒ `ErbcDecodeException`，等 ERBC-B 决定「文件表 + 行号两列」的扩展形态（§9 未覆盖项 2）。

### 5.8 段 7（v2）：调用点实参表 `CALL_ARGS`

```
u32 callSiteCount
u32 totalArgCount            各站点 argCount 之和（冗余校验）
STRING_TABLE                 段内局部串池（实参裸变量名；可空）
record site[callSiteCount]   按 pc **严格升序**
    u32 pc
    u32 argCount             必须 >= 1（只登记非空表）
    record arg[argCount]     按 position **严格升序**
        u32 position         头部形参位（0 起）
        u32 bucket           该实参在类型桶里的槽号（int 桶或 str 桶）
        u8  flags            bit0 = isStr，bit1 = present；其余位保留且必须为 0
        u32 name             NONE_REF(0xFFFFFFFF) = 无名字；否则段内串池下标
```

* 语义来源：`Program.kt:24-30` 的 `CallArg(position / bucket / isStr / name / present)`。
  原版依据 `ProcessState.cs:435-452`（逐**头部形参位**写回，`Arguments[i] == null` 的位跳过 :437）
  与 `UserDefinedFunctionArgument.cs:35-69`（REF 位记 `TransporterRef`，其余按类型分桶）。
* 编码侧登记点：`ErbProgramCompiler.kt:1085-1111`（逐位建 `CallArg`）与 `:1134`
  （`_CALL` 发射后 `declareCallArgs`）；`_CALL_BY_NAME` 的同款登记在 `:1668`。
  `position` 由实参序产生，本就是升序；写侧仍显式排序（`position` 与站点 `pc` 都排），
  因为 `Program.callArgs` 是 `Map`，迭代序随实现而变 —— 排序是对「同一 Program 两次编码逐字节
  相同」的保证，不是语义的一部分。
* 读侧消费点：`ProcessRuntime.kt:596-649` 的 `bindCallArgs`（`onFunctionEnter` 里
  `program.paramNames(...)` 非空才走逐位绑定，:547-551）。
* `name` 只在实参是**裸 `VarRef`** 时非空（`(e as? VarRef)?.name`，`ErbProgramCompiler.kt:1105`）；
  REF 绑定的目标数组只能从变量名定位（`ReferenceToken.cs:125-128`）。
  `present=false` 的位表示「省略实参」，此时 `name` 恒 NONE_REF；`bucket` 仍是该位在类型桶里的
  下一个空槽（`ErbProgramCompiler.kt:1091` 的 `intBucket++` 对省略位照样占位），读侧不得据此推断
  「省略位 = 桶 0」。
* 判界：`pc ∈ [0, instructionCount)`；站点 `pc` 严格升序（同一 pc 只能有一份表）；
  实参 `position` 严格升序且 `>= 0`；`bucket >= 0`；`flags` 保留位为 0；
  逐站 `argCount` 求和必须等于 `totalArgCount`。
  越过这条线的文件一律 `ErbcDecodeException` —— 排序容错会掩盖「同一 pc 写了两份表」。
* **为什么不按「类型桶号」反推形参位**：桶号是调用方帧的槽号，与头部形参位无对应关系
  （`@ADDCHARA_INIT(C_ID, ARG:0)` 只传 1 个实参时桶会把 0 号实参写给 `ARG[0]`，
  `ProcessRuntime.kt:601-605` 记录了这条事故）。位置必须由编译器登记。
* TODO(verify)：`bucket` 的合法上界需要「pc → 函数归属 + 帧大小」，v1/v2 都不携带
  （§9 第 7 项），因此读侧只判 `>= 0`。

### 5.9 段 8（v2）：函数头部形参名表 `PARAM_NAMES`

```
u32 funcCount                必须 == 段 4 的 funcCount
STRING_TABLE                 段内局部串池（形参名；`ARG`/`ARG:n`/`ARGS`/`ARGS:n` 原样落盘）
record func[funcCount]       下标 = 函数下标（= 段 4 记录序）
    u32 paramCount
    u32 name[paramCount]
```

* 语义来源：`Program.kt:41-49` 的 `FunctionInfo.paramNames`（头部形参名，来自装载侧
  `FuncLabelStmt.paramNames`）。**不是** `#DIM` 声明序：全语料 539 个 REF 形参里 63 个两者不同
  （例 `ERB/CHARA_LIST.ERB:4`），因此 `ProcessRuntime.bindCallArgs` 只按头部位取名字
  （`ProcessRuntime.kt:639`）。
* `paramCount` 与 `FunctionInfo.arity` **不要求相等**：`arity` 是帧内值参槽数，形参名表包含
  `ARG*` 形式位，两者口径不同（`Program.kt:37` vs `:49`）。读侧不做交叉断言。
* 段 4 的 `flags` 仍是 v1 恒 0 的保留字段（§5.5）；**头部形参名不进函数记录**，
  而是独立成段 —— 这样 v1 的段 4 逐字节不变，v2 reader 也能在「只有段 4」时正常装载。
* 判界：`funcCount` 与段 4 逐字相等；每个 `name` 下标 `< 串池项数`。记录错位等于
  REF 绑到别的形参上，故 `funcCount` 对账是本段唯一且必须的结构约束。
* 空表（所有函数都没有形参名）⇒ 整段省略（offset 0、`HAS_PARAM_NAMES` 清零）：
  「声明不带」比写一堆 `paramCount=0` 更能暴露上游漏喂形参名。

---

## 6 两个池的处置与理由（本条的真正难点）

### 6.1 表达式池 `ConstantPool.exprs`：**v1 完整覆盖（段 2）**

* 现状：`exprs` 是 `Expr` data class 树，`addExpr` 按**结构相等**去重
  （`Program.kt:42-45`、`:70-77`），`_EVAL_*` 的 `$k` 操作数按池下标引用它
  （`OpcodeTable.kt:119-123`，VM 消费点 `Vm.kt:594-606`）。
* 处置：序列化为「**后序节点表 + 叶类型**」（§5.3）。可序列化的理由：`Expr` 是 sealed
  的 11 个 data class 实现（`Expr.kt` 的 `IntLiteral / StrLiteral / NullTerm / VarRef /
  KeywordIndexArg / FuncCall / Unary / UnaryAfter / Binary / Ternary` + `FormStringTerm`），
  无宿主引用、无惰性状态，全部字段（含 `line` / `col`）都是纯值。
* 装载后的去重语义为什么不会破：`ErbcReader` 按 `root[0..poolCount)` 的顺序逐条
  `addExpr` 重灌，随后**校验 `pool.exprCount == poolCount`**；一旦文件里存在结构相等的
  重复根条目（`addExpr` 会把它们并成一条、池下标整体漂移），装载当场报错而不是返回一个
  「下标全部错位」的 Program。原池由 `addExpr` 构造，本就不可能含重复条目，所以该校验
  在实际产物上恒过，只用来兜住手写/被篡改的字节。
* 与原版运行期 parse 不冲突：运行期 parse ERB 属于 ADR-001「明确不照抄」的重设计区
  （`docs/eratw-架构决策记录.md:32`），预解析表达式随产物落盘正是该决策的落点；
  语义契约（ADR-001 的 8 项）约束的是**求值结果**，而 `Expr` 的求值方法本就挂在节点上
  （`Expr.kt:16-27`，对应原版 `AExpression.cs:23-40`），落盘-装载不引入新语义。

### 6.2 语句池 `ConstantPool.statements`：**v1 完整覆盖（段 3）**

* 现状与不可去重的根据：语句池**按身份追加、严禁去重**（`Program.kt:47-58`、`:84-90`），
  因为 sem 的数据节以 `Statement` **实例**为键
  （`sem/ExecContext.kt:101-110` 的 `DataSections.groupsOf(stmt)`，注释原文「键为该 PRINTDATA 语句实例」；
  `proc/ProcessRuntime.kt:1571-1583` 的 `DataSectionSource` / `installDataSections` / `dataGroupsOf`）。
  原版同款结构：数据节挂在**语句行对象自己的字段**上 ——
  `InstructionLine.cs:30` `public List<List<InstructionLine>> dataList;`，
  PRINTDATA/STRDATA 行装载时各自 `new dataList`（`ErbLoader.cs:1383`、`:1403`），
  DATA 行把组 `add` 到栈顶那条语句对象上（`ErbLoader.cs:1430`、`:1445`），
  运行期由该语句对象的 `DoInstruction(exm, func, state)` 读 `func.dataList` 并随机取一组
  （`FunctionIdentifier.cs:270-286`；`IsPrintData()` 在 `FunctionIdentifier.cs:4285`）。
  ⇒ 结构相等的两条语句在原版也是两个不同的行对象、两份数据节；移植侧同理必须两个条目、两个实例。
* 处置：语句池逐条编码为「类标签 + 全部构造字段」的**身份记录流**（§5.4）。
  装载侧每条记录**新建一个实例**，然后按记录序逐条 `addStatement`，并且
  **校验 `pool.statementCount == stmtCount`**。要点：
  1. 读侧没有任何形式的合并/驻留（不同于 §6.1 的表达式池）；
     两条字节完全相同的记录必然产生两个不同对象（`Statement` 不是 data class，
     相等性即身份，`Statement.kt:58` 只覆写 `toString`）。
  2. 语句内联的 `Expr` / `VarRef` 子树落进**段内节点表**，会被结构共享 ——
     这是安全的：`VarRef` 等是 data class，移植侧没有任何地方按 `VarRef` 的实例身份取数
     （身份键只有 `Statement` 一处，见上），而 `.erbc` 里必须重建的是「同一批语句实例」。
  3. marker 的 `a` 因此逐字回到装载后的同一槽位；`Vm.kt:608-620` 的
     `program.statement(a)` 取回的就是该 marker 对应的实例。
  4. 「同一实例被追加两次」的入口（`ConstantPool.addStatement` 恒新开条目）在产物里表现为
     两条独立记录，装载后仍是两个实例 —— 与内存态口径一致（`StatementPoolTest` 锁死的正是这条）。
* 数据节（`PRINTDATA`/`STRDATA` 的 `DATA`/`DATAFORM` 组）的**归属映射不在 `Program` 里、
  也不在容器里**：它在 `program.meta` 侧车的 `data-sections` 段，键 = 语句池下标
  （`core/loader/BootMetadata.kt:102-109`、`:323-326`），装载侧由
  `core/proc/GameSession.kt:442`（交给 `SidecarTables`）、`:542-601`（池下标 → 语句实例
  身份表）与 `ProcessRuntime.installDataSections`（`proc/ProcessRuntime.kt:1571-1583`）
  装配。容器侧只需保证「语句实例集合 + 槽位」可重建，v1/v2 都不为它新增段（§9 第 4 项已结）。

### 6.3 标志位与「不覆盖」的表达

v1 两个池都覆盖，因此 `HAS_EXPRESSIONS` / `HAS_STATEMENTS` 在真实产物上恒置位。
它们的作用是**显式声明「本文件不含 X 池」**（契约 §2.3）：debug 之外若要产出「只有指令流」的
小产物（例如只为 ERBC-B 的装载装置做骨架），把选项关掉即可，装载侧在需要该池时报
指名道姓的错而不是跑到那一行才炸，也不是静默返回一个空池假装成功。
写侧拒绝产生不自洽的文件（§3）：池非空而不让写 ⇒ `ErbcEncodeException`。

---

## 7 分片与 assets（实产口径；CMP-74 已对齐）

* 实施方案 §4：「分片 ≤ 32MB，assets 内不压缩，`AssetFileDescriptor` 直读」。
* v1 的**单文件容器**由 u32 偏移决定容量上界（< 4GB-1）；装载侧按 `Int` 解释段表 ⇒
  实用上限 2^31-1，写侧由 `ErbcWriter` 的 `checkedSectionEndOffset` 把门（越界即
  `ErbcEncodeException`，绝不写出「写得出、读不回」的产物）。**32MiB 是切片阈值、
  不是容器上限**：全量语料的 CODE 段实测约 74MB、整容器约 313MiB
  （`app/build.gradle.kts` 头部的 STORED 读数），必然要切片。
  `ErbcFormat` 里原有一个 `MAX_SHARD_BYTES = 32MiB` 常量，**零读者**（只剩一句 KDoc
  引用），已删（CMP-74）—— 切片阈值以本节的 32MiB 协议值与 app 打包参数
  `-PeratwErbcChunkMb` 为准，别再放一个没人读的常量冒充实现。

### 7.1 实产分片协议（权威；与 `app/build.gradle.kts` / `GameAssetInstaller` 同源）

1. **切片只在打包期发生**：erbcc 永远只产**单文件** `program.erbc`；APK 的切片由
   `app/build.gradle.kts` 的 `stageGameAssets` 按 `-PeratwErbcChunkMb`（MiB，0 = 不切）执行。
2. **分片是整容器字节流的裸切片**：从 `program.erbc` 的偏移 0 起按固定字节数顺序切，
   文件名为 `program.partNNN.erbc`（三位零填充，从 `000` 起）。
   切片点**不要求**在段边界或指令边界；每片**不是**合法容器（没有 header / 段表），
   单独 `ErbcReader.probe` 必失败 —— 它是字节片段，不是「续片容器」。
3. **拼接契约 = `program.parts` 的行序**：每行「文件名 TAB 字节数」（两列，设备侧
   `GameAssetInstaller.readPartsManifest` 的现行解析口径；文件名**不保证**字典序）。
   按行序拼接后的字节必须与切分前的 `program.erbc` **逐字节相同** —— 这一条由随包清单的
   `container-artifact` 与 `shard` 汇总证明（见 §7.3）。
4. **装载侧**：`GameAssetInstaller` 先按 `program.parts` 把分片拼回 `$GAME_ROOT/erbc.v1/program.erbc`
   落盘，再交给装载器；装载器只读拼回的**整文件**，不存在「按片顺序追加 CODE 段」的读法。
   拼装是事务性的（`.tmp` + 校验后改名），任一片缺失/字节数不符即整对回滚。
5. 分片名保留 `.erbc` 后缀是打包机制要求：aapt2 的 `noCompress` **只按后缀匹配**，
   叫 `program.erbc.p1` 会掉进 DEFLATE、STORED 口径失效（2026-10-21 实测：3 片全变 DEFLATE）。

### 7.2 退役协议（**从未实现，不得再引用**）

旧文本曾写「主片 `*.erbc` + 续片 `*.erbc.p1`/`.p2` 是**合法容器**，只带 header + 段表 + `CODE` 段，
`instructionCount` 按片计、按片序拼接后等于主片总条数」。该方案在本仓**没有任何实现**
（写侧不产、读侧不认），且与实产命名相反（实产是 `program.partNNN.erbc` 的裸切片）。
自 CMP-74 起**正式退役**：将来若要做「容器级续片」，必须新立规约节、升容器 `version`、
并定义装载侧拼接点，**不得复用本节字段**，也不得把裸切片解释成容器。

### 7.3 assets 与「清单 ↔ 随包文件集」对账（CMP-74）

* `assets` 侧必须把 `erbc` 加进 `noCompress`（实施方案 §6 M6 已登记）；APK 内未压缩才有
  `AssetFileDescriptor` 直读（mmap 友好）。
* **构建面清单** `erbc.v1/manifest.txt`（erbcc 落盘）：首部 `manifest-format 2` 是清单格式版本
  （缺该行 = 旧格式 v1；大于 2 = 未来格式，读侧拒绝）。`artifacts N` + N 行
  `artifact <相对路径> <字节数> <SHA-256>` 必须覆盖产物目录里**除 `manifest.txt` 自身之外**
  的全部文件（`program.erbc` / `program.meta` / `program.stmt.idx`）。清单格式版本与容器
  `container-version` **解耦**：清单加字段不升容器版本，容器加段不升清单格式。
* **随包面清单**：打包期在同一份清单末尾追加
  `manifest-source package`、`package-artifacts N` + `package-artifact ...`（APK 内 `erbc.v1/`
  下除 `manifest.txt` 与 `package.report` 的全部条目）、`container-artifact ...`（切分前的整容器
  字节数 + SHA-256）、`container-shards N` + `shard ...`（行序 = 拼接序，每片字节数 + SHA-256）。
  `package.report` 同时写 `manifest-sha256`（清单字节的摘要），供「报告 ↔ 清单」对账。
* **对账器** `tools/apkpack/inspect_apk.py` 的四条判据：①清单 `package-artifact` 表与 zip 里
  `erbc.v1/` 的实际文件集**双向相等**；②逐条字节数 + SHA-256；③分片行序与 `program.parts`
  一致、逐片 sha 正确、按行序汇总后等于 `container-artifact`；④`package.report` 的
  `manifest-sha256` 等于清单字节摘要。单条形态下 `container-shards 0`，此时
  `program.erbc` 本身必须等于 `container-artifact`。
* `program.parts` 保持**两列**（设备侧解析器契约）；每片 SHA-256 因此记在清单的 `shard` 行。
  将来 platform 的 `readPartsManifest` 支持第三列后，可升为「文件名 TAB 字节数 TAB SHA-256」，
  对账器已同时接受两列/三列。
* **旧产物处理**：无 `manifest-format` 行 = 旧格式 v1，其 `artifacts 1` 只登记 `program.erbc`，
  侧车/分片不可对账。打包期遇到旧清单会在随包面补 `manifest-format 2` 并按实际文件重建
  `package` 段（随包面始终可对账），并在日志告警；构建面目录里的旧清单**只证明 `program.erbc`**，
  建议重跑 `:tools:erbcc:compileCorpus` 拿新格式。旧 APK（改造前打出来的）不含
  `package-artifact` 段，`inspect_apk.py` 在 `--require-bundled` 下判「旧格式、不可对账」。
* TODO(verify)：容器级续片若重新立项，需要「片级总条数/段表」的新规约节；当前裸切片协议
  没有片级语义，`container-artifact` 的整容器摘要即为唯一的段完整性判据。

---

## 8 版本与兼容规则

1. `version` 只表示**容器版本**。reader 只认 1 与 2，其余一律报错 —— 不做「尽力解析」，
   因为语义错位在 golden 对拍里表现为「输出差一个字」，比装载期直接失败贵几个数量级（ADR-005 纪律）。
2. **v1 产物在 v2 reader 上照旧装载**：段 0..6 逐字节同义，段 7/8 缺席即「声明不带」，
   装载后 `Program.callArgSource == CallArgSource.ERBC_V1`、`Program.bindsCallArgsPositionally == false`，
   运行期退回「类型桶 → `ARG:i`」绑定（不是绑错，但 REF/具名形参不生效）。
   `Program.bindsCallArgsPositionally` 是上层判断「这条产物能不能逐位绑定」的唯一入口。
3. 写侧默认产出 v2（`ErbcEncodeOptions.version = ErbcFormat.LATEST_VERSION`）。
   显式要 v1 时：程序带 `Program.callArgs` / `FunctionInfo.paramNames` ⇒ `ErbcEncodeException`
   （v1 容器表达不了，写出去必丢语义，见 §1.6）。
4. 兼容变更 = 追加新段号 / 追加新 `ArgTag` / `StmtTag` / `ExprTag` 值 / `PrintSpec` 位 / 标志位，
   并且 `version` 递增；**旧标签值永不复用**。
5. 不兼容变更（改字段顺序、改池语义、改指令宽度）必须升 `version` 的主号并另立规约节。
   v2 只做「追加段 + 追加标志位」，因此 v1 的段 0..6 一个字节都没动。
6. `jump` 的偏移单位是**指令条数**（`Instruction.kt:44-46`），因此指令宽度若变化，
   已编译产物的所有跳转会失效 —— 这正是把宽度写进 ADR-004 并让 version 把关的原因。
7. 任一派生工具（`tools/erbcc` 的产物目录名 / manifest 的 `container-version`）必须用
   `ErbcFormat.LATEST_VERSION` 而不是写死 `VERSION_1`；`VERSION_1` 只用于「显式产 v1」的路径。

---

## 9 v1/v2 未覆盖（逐项 + 卡点 + 归属）

| # | 未覆盖内容 | 精确范围 | 卡点 | 归属 |
|---|---|---|---|---|
| 1 | f64 常量池 | 段 1 的 `doubleCount` 必须为 0；非 0 ⇒ 装载报错 | `ConstantPool` 只有 Long/String 两个值池（`Program.kt:36-40`），没有 Double 池；语句里的 `Double`（`SpTimesArgument.doubleValue`、`StrDoubleArgument.doubleValue`）已随语句记录落 8 字节 raw bits，不需要常量池 | 需要 f64 常量池时由 ERBC-C 追加（先扩 `ConstantPool`，再定标签） |
| 2 | 行号表的「文件 id」 | 段 6 的 `fileTableCount` 必须为 0 | `Program.lines` 只有行号一列（`Program.kt:116-117`）；文件归属信息只在 erbcc 符号表一侧 | ERBC-B（随 `FuncMeta` 导出方案一起定） |
| 3 | 函数标志位（EVENT / METHOD / ONLY / PRI / LATER） | 段 4 的 `flags` 必须为 0 | `FunctionInfo` 无对应字段（`Program.kt:7-24`）；事件属性现由 erbcc `SymbolTable`（specs/loader.md 附录 D）与 `ProcessRuntime.FuncMeta` 承载 | ERBC-B |
| 4 | ~~数据节归属表（`PRINTDATA 实例 → DATA 组`）~~ **已覆盖：归属在 meta 侧车，不在容器** | 容器的段 3 只保证「语句实例集合 + 槽位」可重建，**不携带组归属**；组归属由 `program.meta` 的 `data-sections` 段承载：键 = **语句池下标**，值 = 组列表（`core/loader/BootMetadata.kt:102-109`，文本编码见 `:323-326`）。用池下标而不是实例身份的理据：实例身份跨进程不可能稳定，池下标由 `ErbcWriter`（追加序）与 `ErbcReader`（读回序）共同保证，且 marker 指令的 `a` 操作数就是它 | 无（已落地）。装载侧装配：`core/proc/GameSession.kt:442` 把 `metadata.dataSections` 交给 `SidecarTables`；`:542-601` 按「语句池下标 → 语句实例」建身份表（越界下标按旧口径跳过，`:584-601`），再由 `ProcessRuntime.installDataSections` 注入 | 已落地（原文的「ERBC-C 追加一个段」方案作废：池下标即键，容器不需要这一段） |
| 5 | `SharpStmt.tokens`（词法 Token 表） | 编码期 `tokens` 非空 ⇒ `ErbcEncodeException` | `core.lexer` 的 `Token`/`StrForm`/`SubWord` 是带嵌套的可变词法结构，v1 未定稿其线格式；`#` 预处理行本就**不进语句池**（`ErbProgramCompiler.kt:534` 直接跳过元数据行） | 仅当有产物真的把带 Token 的 `SharpStmt` 投进池时才需要（ERBC-C） |
| 6 | 容器级续片（每片自带 header/段表、按片 mmap） | **未实现且已退役**（§7.2）；实产分片是整容器的**裸字节切片**，靠 `program.parts` 行序拼回整文件后装载 | 片级语义（条数/段表/拼接点）要新立规约节并升容器 `version` | 不立项（CMP-74 裁定：裸切片已满足体积上限；续片容器零消费者） |
| 7 | pc → 函数归属、`_JUMP_DYN(200)` 的标签下标判界 | 装载期不检查（见 §5.6 末） | 需要函数区间表；`Program` 侧只有 `entryPc`，区间要按函数序推导 | ERBC-B |
| 8 | 非法 UTF-8 之外的字符级容错 | 解码严格 UTF-8；编码走 `String → UTF-8` | JVM 对**孤立代理项**的 UTF-8 编码会替换为 `?`（与 C# 的 best-fit 同为有损），因此含孤立代理项的字符串不能逐字节往返 | 记为已知口径；语料若出现需实测确认（TODO(verify)） |
| 9 | 段 7 的 `bucket` 上界、站点 pc 与调用指令的对应关系 | 读侧只判 `bucket >= 0` 与 `pc ∈ [0, instructionCount)`，不校验该 pc 的 opcode 是否为 `_CALL`/`_CALL_BY_NAME` | 两者都要「pc → 函数归属 + 帧大小」（同第 7 项）。误判 pc 会让实参值从错的调用方帧槽读，故宁可不判；`ProcessRuntime.bindCallArgs` 只按 `callArgsAt(lastCallPc)` 精确命中，错 pc 的站点是死数据而非错绑 | ERBC-B（与第 7 项同一张函数区间表） |

---

## 10 证据索引

Kotlin（本仓，`core-vm/src/main/kotlin/com/eratw/port/`）：

| 结论 | 出处 |
|---|---|
| 指令定长 4×u32、跳转单位=指令条数、`SlotAddr.NONE=0xFFFFFFFF` | `core/code/Instruction.kt:1-49`、`:81-114` |
| 操作码语义（`_SET_INT` b=常量池、`_SET_STR` b=串池、`_EVAL_*` a=表达式池、`_CALL` a=函数表） | `core/code/OpcodeTable.kt:44-123` |
| 语句池按身份追加、严禁去重 | `core/code/Program.kt:47-58`、`:84-90` |
| 表达式池按结构相等去重 | `core/code/Program.kt:42-45`、`:70-77` |
| marker 编码（op 29..197、a=池下标、b=0、c=NONE） | `core/compile/ErbProgramCompiler.kt:271-283` |
| VM 的 marker 分派区间与池取回 | `core/code/Vm.kt:608-620`、`:868` |
| 数据节以语句实例为键（接口与注入点） | `core/sem/ExecContext.kt:101-110`、`core/proc/ProcessRuntime.kt:1571-1583` |
| 数据节的**归属**由 meta 侧车承载（容器不带） | `core/loader/BootMetadata.kt:102-109`、`:323-326`；装配 `core/proc/GameSession.kt:442`、`:542-601` |
| 装载后的语句池就是运行期唯一的一份池（合流后已无「launcher 克隆池」这一步） | `core/proc/ProcessRuntime.kt:170`（VM 直接跑编译产物的 `Program`）、`core/code/Vm.kt:616-619`（下标越界显式失败）、事故登记 `core/proc/StatementBridgeTest.kt:401-404` |
| `Statement` / `Argument` 类表与派生字段 | `core/stmt/Statement.kt:33-71`、`:78-404`、`core/stmt/Argument.kt:14-433` |
| `Expr` / `FormPart` / `FormConversion` 类表 | `core/expr/Expr.kt:16-334`、`core/expr/FormStringTerm.kt:19-68` |
| `PrintSpec` 只由 `parse(code)` 构造（`name == code.name`） | `core/stmt/PrintSpec.kt:40-125` |
| 语句分派把 `args` 强转为具体类（⇒ 参数必须逐字段保真） | `core/sem/StatementExecutor.kt:1260-1317`（`stmt.args as SpArrayShiftArgument`） |
| `CallArg(position / bucket / isStr / name / present)` 与 `FunctionInfo.paramNames` | `core/code/Program.kt:24-30`、`:41-49` |
| 逐位实参写回（REF 别名 / `ARG:n` 省略位） | `core/proc/ProcessRuntime.kt:596-649`；门禁 `:547-551`（缺元数据即退回类型桶） |

C# 反编译（只读，路径相对 `D:/DSH/eratw-安卓移植计划/reference/emuera-cs/`）：

| 结论 | 出处 |
|---|---|
| 数据节挂在语句行对象的字段上（实例为键的原型） | `MinorShift.Emuera.Runtime.Script.Statements/InstructionLine.cs:30` |
| PRINTDATA / STRDATA 行各自 new `dataList` | `MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:1383`、`:1403` |
| DATA / ENDLIST 把组 add 进**栈顶那条语句对象**的 `dataList` | `ErbLoader.cs:1430`、`:1445` |
| ENDDATA 校验归属行的 `dataList.Count`、回填 `JumpTo` | `ErbLoader.cs:1465-1469` |
| PRINTDATA 运行期从该语句对象读 `dataList` 并随机取一组 | `MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:270-286` |
| `IsPrintData()`（数据节家族判定） | `FunctionIdentifier.cs:4285` |
| 表达式求值虚方法挂在节点上（预解析树可携带） | `MinorShift.Emuera.Runtime.Script.Statements.Expression/AExpression.cs:23-40`（`GetIntValue` / `GetStrValue` 的虚方法默认实现） |

---

## 11 与契约 §2 的逐项对照

| 契约要求 | 本规约 |
|---|---|
| 头（magic + u16 版本 + u16 标志 + u16 段数） | §2 |
| 段表（u32 偏移 × n） | §2、§4 |
| 字符串池（UTF-8、池内去重、u32 偏移索引） | §5.1（去重在 `ConstantPool.addString`，`Program.kt:65-68`；线格式用 count+1 偏移表） |
| 常量池（i64 / f64 / 字符串索引） | §5.2（i64 落地；f64 留位 ⇒ §9.1；字符串索引 = 段 0） |
| 函数表（名称=串 idx、entryPc、arity、slotCount、标志、`$标签` 平行表） | §5.5（标志位为 v1 恒 0 的保留字段 ⇒ §9.3） |
| 指令流（u32×4 定长） | §5.6 |
| 行号表（pc→源码行，受 debug 标志位控制） | §5.7 + §3 |
| 分片 ≤32MB、assets 内不压缩 | §7（打包期裸字节切片 + `program.parts` 行序拼回；容器级续片退役） |
| 表达式池处置定稿 + 理由 | §6.1 |
| 语句池处置定稿 + 理由 | §6.2 |
| 标志位能表示「不含表达式池 / 不含语句池」 | §3、§6.3 |
| 调用点实参元数据（位置 / 类型桶 / 裸变量名 / 省略位）可持久化 | §5.8（v2 段 7；编码点 `ErbProgramCompiler.kt:1085-1111`，消费点 `ProcessRuntime.kt:596-649`） |
| 函数头部形参名可持久化（REF/具名形参定位依据） | §5.9（v2 段 8；`Program.kt:41-49`） |
| 旧 v1 产物仍可装载且退化可查 | §8.2（`Program.callArgSource == ERBC_V1`、`bindsCallArgsPositionally == false`） |

---

## 12 侧车字节条目的需求清单（**本节是需求，不是规约正文**；落笔归 L02）

> 起因：CMP-04 —— 两个**线上产物格式** `program.meta`（≈49 MB 文本）与 `program.stmt.idx`
> （magic `ERSTMT01`）在 `specs/` 里至今**零条目**，字节级契约只活在 KDoc 里，改格式不会有任何测试红。
> 本节只列「规格必须写清楚哪些事」与证据行号，**不写字段值、不写规则正文**；正文由 L02 按代码逐项核对后落笔。
> 代码内已有 KDoc 出处：`tools/erbcc/.../compile/BootMetaWriter.kt:8-80`、
> `core-vm/.../core/loader/StmtOffsetSidecar.kt:9-107`、`core-vm/.../core/loader/BootMetadata.kt:30-90`、
> `:250-310`（文本格式骨架）、`:572-905`（逐行解析）。

### 12.1 `program.meta`（文本侧车）—— 需写明的 10 件事

| # | 需写明 | 现状事实（代码:行号） |
|---|---|---|
| R-META-1 | 文件名、与 `program.erbc` 的配对关系、分发单位；缺侧车 / 多侧车 / 基名不符时装载侧的行为 | 文件名 `program.meta`（`BootMetaWriter.kt:43`）；同目录同基名、分发单位是目录（`:23-26`）；缺文件时 `BootMetadata.read` 抛 `BootMetaFormatException`（`BootFailureTest.kt:80`） |
| R-META-2 | 字节编码、换行、结尾、禁止字符 | UTF-8 无 BOM、LF、不得含 CR、必须以 LF 结尾（`BootMetaWriter.kt:60-77` 的两条 `check`）；**读侧现状非严格** —— `newBufferedReader(UTF_8)` 默认 REPLACE（CMP-07，`BootMetadata.kt:215`）⇒ 规格要定读侧是否同口径严格 |
| R-META-3 | 头部两行的语法：`engine "<tag>"`、`program-sha256 "<hex>" \| -`；`-` 的语义与读侧处置 | 格式注释 `BootMetadata.kt:255-256`；写出 `:306-307`；读入 `:683-686`；写侧缺省即 `-`（`BootMetaWriter.kt:48-57`）⇒ 规格要定「`-` 是拒绝还是放行并且谁判」（L02 判据：sha=`-` 必被拒） |
| R-META-4 | 记录结构与段关键字全集、段序、数量行、`end` 终止语义 | `:669`（version 记录）、`:683-884`（各段：labels / files / functions / global-dims / private-dims / dim-parse / datasec / str-constants）、`:891`（`end` 之后再有记录即抛） |
| R-META-5 | 逐段字段表：每段的行 tag、字段序、字段类型（i64/串/路径/表达式） | 同上区间 + `:995-1180`（表达式/格式串的解码器）；必须逐段列全，否则改字段不会有测试红 |
| R-META-6 | 引号串的转义规则与**字符集边界**：`"`、`\`、CR/LF、`0x7F`、U+0085、未配对代理项、U+FEFF | `BootMetadata.kt:452-467`（`quote`）；CMP-07 已点名 0x7F/U+0085/未配对代理项未转义 |
| R-META-7 | 表达式节点的单行记号文法（tag + 字段序 + 括号嵌套上限） | `:398`（单行记号串）、`:483-560`（tokenizer）、`MAX_EXPR_DEPTH` 限制（`:492` 注释） |
| R-META-8 | 数值/计数的值域闸、重复键口径、越界处置 | `:661-706`（`checkCount` 的计数校验）、`:748-768`（function 段只校条数，`index` 值域不设限，CMP-82）、`:876-884`（`str-constants` 段未调 `checkCount`，对照 `:850`/`:862`）；规格要定哪些是「格式约束」哪些是「读侧容错」 |
| R-META-9 | 确定性：同一输入两次写出逐字节相同（Map 按键序、函数按 index 序、表达式后序不加缓存） | `BootMetadata.kt:83-88`；`BootMetaWriter.kt:27-33` |
| R-META-10 | 读侧内存契约：49 MB 文本不得整份读成 String | `BootMetadata.kt:206-208`（按行流式 `parseReader`）；规格要写「读侧允许的最大驻留形态」 |

补充（CMP-74 已收口，正文 §7.3 是权威）：`manifest.txt` 的 `manifest-format 2` 起，`artifacts` 表
登记产物目录里除清单自身外的**全部**文件（`program.erbc` / `program.meta` / `program.stmt.idx`，
`ErbcArtifacts.manifestText`）；打包期再追加 `package-artifact` / `shard` 段。
⇒ 「随包文件清单」的唯一权威 = manifest，`package.report` 只做体积/压缩口径自述（另带 `manifest-sha256`）。
旧格式（无 `manifest-format` 行、`artifacts 1`）只证明 `program.erbc`，不得当作随包完整性的证据。

### 12.2 `program.stmt.idx`（二进制偏移侧车）—— 需写明的 7 件事

| # | 需写明 | 现状事实（代码:行号） |
|---|---|---|
| R-IDX-1 | 文件名、与 `program.erbc` 的配对关系、可选件语义（缺失即回落扫描，不算错误） | `StmtOffsetSidecar.kt:45-46`、`:55`；可选件与回落见 `:9-28`、`:57-62` |
| R-IDX-2 | 字节布局（小端）：magic `ERSTMT01`(8) / version u32=1 / stmtCount u32 / recordBytes u32 / offsets u32×N | `:30-42`（KDoc 布局）、`:48-52`（常量）、`:96-105`（写出） |
| R-IDX-3 | 偏移基准与不变量：相对**记录区起点**、单调递增、`offsets[0] == 0`、`recordBytes` = 记录区总字节数 | `:36-37`（KDoc） |
| R-IDX-4 | 校验与回落判据：文件缺失 / 魔数 / 版本 / 长度 / 条数不符 ⇒ 返回 null 回落扫描；「单调 + 在区内 + 末条收在段尾」由装载器判 | `:40-41`、`:63-83`；放行点 `ErbcStream.kt:1119-1120`（CMP-01 称之为「唯一放行判据」） |
| R-IDX-5 | `recordBytes` 的性质：粗校验还是必须与段 3 记录区长度**逐字节相等**；`stmtCount` 与段 3 条数的对账点（构建期 / 装载期） | `:78`（读出即丢，CMP-01）；`ErbcStream.kt:953`（`validateRecordOffsets`） |
| R-IDX-6 | 写原子性：`tmp` + `ATOMIC_MOVE` 是契约还是实现细节；`program.erbc`/`program.meta` 是否必须同等原子（CMP-06 的倒挂） | `:85-106`（原子侧车） vs `ErbcArtifacts.kt:105`（裸 `Files.write` 写 erbc）、`BootMetaWriter.kt:77`（裸写 meta） |
| R-IDX-7 | 产物目录清理与装机：孤儿侧车的处置、装机判重拷的判据（size vs 哈希） | `ErbcArtifacts.kt:98-117`（只覆盖不删除，CMP-09）；`GameAssetInstaller.kt:339-340`（按 size 判重拷，归 Q2） |

### 12.3 L02 落笔的交付判据（判据本身不是规约）

1. 两条格式都必须进 `specs/erbc-format.md` 正文（不是 KDoc），字段级表格 + 版本/兼容规则（与本文 §8 同构）。
2. 负例测试必须真造错件：错表「中段平移 1 字节且末条仍对齐」必须被打回（CMP-01/03）；`sha = -` 的 meta 必须被拒。
3. 本波**不改产物字节**：落笔只动规格与测试，格式先冻结现状（`ErbcWriter.encodeIndexed` 的输出）。
