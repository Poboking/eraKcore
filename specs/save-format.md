# ERB 存档格式规约（save-format.md）

> 依据 emuera-cs 源码提取（Emuera.dll 1.824.0.0，.NET 8）。所有结论均附 `文件:行号` 证据，源码根为 `reference/emuera-cs/`。

## 1. 存档文件结构

### 1.1 两种格式并存：文本格式（默认）与二进制格式（可选）

- 是否二进制由配置项 `SystemSaveInBinary` 决定，默认 `false`（文本格式）：
  - `MinorShift.Emuera.Runtime.Config/ConfigData.cs:107`（默认 `t: false`）
  - `MinorShift.Emuera.Runtime.Config/Config.cs:371`
- 二进制格式下可选 GZip 压缩（`ZipSaveData`，默认 `false`）：
  - `ConfigData.cs:128`、`Config.cs:388`
  - 压缩仅当 `SystemSaveInBinary && ZipSaveData` 同时为真才生效：`EraBinaryDataWriter.cs:22-32`
- **eratw 实际采用哪种取决于用户配置；文本格式是保守默认，二进制格式是必须兼容的主路径。**

### 1.2 二进制格式头部（魔数 + 版本字段）

`EraBinaryDataWriter.WriteHeader()`（EraBinaryDataWriter.cs:35-51）写入顺序：

| 偏移 | 长度 | 内容 | 证据 |
|---|---|---|---|
| 0 | 8 字节 (UInt64 LE) | 魔数：未压缩 `727905341820519817`（0x0A18_4206_5F4A_8B49? 十进制原样）；GZip 压缩 `743174590507926921` | EraBinaryDataWriter.cs:37-44 |
| 8 | 4 字节 (UInt32) | 版本号 `1808` | EraBinaryDataWriter.cs:45 |
| 12 | 4 字节 (UInt32) | 校验表数量，**恒写 0** | EraBinaryDataWriter.cs:46 |
| 16 | 0 条 | 校验表本体（历史遗留，循环上限为 0） | EraBinaryDataWriter.cs:47-50 |

- 读取侧对称校验两个魔数：`EraBinaryDataReader.CreateReader()`（EraBinaryDataReader.cs:786-830）；第 2 个魔数命中时将后续字节经 `GZipStream` 解压后再读（EraBinaryDataReader.cs:807-815）。
- 版本号只认 `1808`，其他值返回 null（按文本格式回退处理）：EraBinaryDataReader.cs:820-824。
- **无加密；无有效校验和**（校验表恒空）。文件末尾以 `EOF=0xFF` 字节分界：EraBinaryDataWriter.cs:78-81、VariableEvaluator.cs:2604-2606。

### 1.3 二进制数据编码（正文字节序/编码）

- 容器：`BinaryWriter`/`BinaryReader` 均以 **Encoding.Unicode（UTF-16LE）** 创建字符串编码：
  - EraBinaryDataWriter.cs:25/30、EraBinaryDataReader.cs:794/814/818
- 所有数值 little-endian（.NET BinaryWriter 默认）。
- 变长整数压缩编码 `m_WriteInt`（EraBinaryDataWriter.cs:175-196）：
  - `0..207` → 单字节原值
  - `-32768..32767` → `0xD0` + Int16
  - int 范围 → `0xD1` + Int32
  - 其余 → `0xD2` + Int64
- 标记字节（EraBinaryDataWriter.cs:68-81）：
  - `0xFD` Separator、`0xFE` EOC（角色数据块结束）、`0xFF` EOF
- 类型字节（WriteWithKey，EraBinaryDataWriter.cs:83-173；枚举定义 EraSaveDataType.cs:3-19）：
  - `0` Int、`1..3` Int1D/2D/3D、`16` Str、`17..19` Str1D/2D/3D、`32` Map、`33` Xml、`34` DataTable
- 数组采用 0 游程压缩（RLE）：`0xF0`=连续 n 个 0（1D，EraBinaryDataWriter.cs:203-223）；2D/3D 另有 `0xE0`（整行/面零）、`0xF1`、`0xF2`（EraBinaryDataWriter.cs:225-332）；字符串数组以 `0xD8`+字符串表示非空项、空/null 计入 0 游程（EraBinaryDataWriter.cs:346-478）。读取侧严格对称：EraBinaryDataReader.cs:102-764。
- 字符串数组中 **null 与 "" 在写入时等同**（都按空项跳过，读回后统一为 null/空，needInit 时 Int 填 0、Str 填 null）：EraBinaryDataWriter.cs:352、EraBinaryDataReader.cs:481-497。

### 1.4 文本格式（SystemSaveInBinary=false）

- 逐行文本，`StreamWriter` 编码为 `Config.SaveEncode`，默认 **UTF-8 with BOM**：EraDataWriter.cs:21-25、Config.cs:19。
- 读侧用 `EncodingHandler.DetectEncoding(file)` 自动探测：EraDataReader.cs:36。
- 数值即十进制行；数组以最后非零/非空元素截断后逐行输出，以 `__FINISHED` 行收尾：EraDataWriter.cs:52-109。
- 扩展段标记：`__EMUERA_1808_STRAT__`（注意源码拼写 STRAT）开启 1808 扩展区，`__EMU_SEPARATOR__` 分段，`key:value` / `key` + 行列表格式：EraDataWriter.cs:11-15、111-127、129-219；读取版本探测 `__EMUERA_STRAT__`(1700)/1708/1729/1803/1808：EraDataReader.cs:14-26、133-175。

## 2. 哪些数据进存档

### 2.1 文件清单

| 文件 | 路径 | 证据 |
|---|---|---|
| 普通存档 | `save{index:00}.sav` | VariableEvaluator.cs:2023-2032 |
| 全局存档 | `global.sav` | VariableEvaluator.cs:2018-2021 |
| 变量存档 | `var_{index:00}.dat` | VariableEvaluator.cs:2039-2042 |
| 角色存档 | `chara_{index:00}.dat` | VariableEvaluator.cs:2044-2047 |

### 2.2 普通存档（saveXX.sav）整体布局

- 写入链：`Process.saveGameWaitInputOverwrite` → `VariableEvaluator.SaveTo` → `SaveToStream`/`SaveToStreamBinary`（Process.cs:2857-2889、VariableEvaluator.cs:2644-2685）。
- `SAVEDATA_TEXT` 在保存前由 `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "` 填入并允许 `@SAVEINFO` 修改：Process.cs:2872。
- 二进制布局（VariableEvaluator.SaveToStreamBinary，VariableEvaluator.cs:2591-2607）：
  1. Header（魔数+1808+0）
  2. FileType=0（Normal，EraSaveFileType.cs:3-9）
  3. Int64 `gamebase.ScriptUniqueCode`
  4. Int64 `gamebase.ScriptVersion`
  5. String `saveDataText`
  6. Int64 角色数；逐个角色 `CharacterData.SaveToStreamBinary`（CharacterData.cs:513-558，以 EOC=0xFE 结尾）
  7. `VariableData.SaveToStreamBinary`：**遍历 varTokenDic，凡 `IsSavedata && !IsCharacterData && !IsGlobal` 的变量按字典序逐个 WriteWithKey**（VariableData.cs:3980-3997），后接用户自定义静态变量
  8. EOF(0xFF)；EM 段（SAVECHARA/SAVEGLOBAL 之外的 Map/Xml/DT 扩展，VariableData.cs:3936-3959）；EOF(0xFF)
- 文本布局（VariableEvaluator.SaveToStream，VariableEvaluator.cs:2409-2426）：
  1. UniqueCode 行、ScriptVersion 行、saveDataText 行
  2. 角色数行；每角色：2 个 String + 2 个 Int + 17 个 Int1D 数组（CharacterData.cs:351-369）
  3. VariableData：60 个 Int1D + 1 个 String1D（VariableData.cs:3516-3534）
  4. `__EMUERA_1808_STRAT__` 后接扩展区（ExtSaveList 变量按 VariableCode 枚举顺序 + 用户自定义变量，8 段各以 `__EMU_SEPARATOR__` 分隔）：VariableData.cs:3556-3626、CharacterData.cs:391-423
- **RANDDATA（625 元素全局数组）属于 global.sav，不进 saveXX.sav**（`IsGlobal` 变量被 SaveToStreamBinary 排除：VariableData.cs:3985；RANDDATA 定义 VariableEvaluator.cs:117、VariableCode.cs:89）。
- SAVESTR 即 `DataIntegerArray` 同级的 String1D 存档变量，文本格式随 VariableData 主体第 1 个 String1D 段写入（VariableData.cs:3530-3533，SAVESTR 注册 VariableData.cs:3068）。

### 2.3 全局存档（global.sav）

- 写入链：`SAVEGLOBAL` 指令 → `VariableEvaluator.SaveGlobal`（FunctionIdentifier.cs:1801-1812、VariableEvaluator.cs:2474-2511）。
- 二进制：Header → FileType=1(Global) → UniqueCode → ScriptVersion → 空串 → `SaveGlobalToStreamBinary`（**凡 `IsSavedata && !IsCharacterData && IsGlobal` 的变量**，VariableData.cs:3961-3978）→ EOF → GlobalEM 段 → EOF（VariableEvaluator.cs:2483-2493）。
- 文本：UniqueCode、ScriptVersion 行后写 `dataIntegerArray[63]`（GLOBAL）与 `dataStringArray[5]`（GLOBALS）两个数组（VariableData.cs:3804-3814），1808 扩展区再写用户自定义全局变量（VariableData.cs:3816-3846）。

## 3. 版本迁移（LASTLOAD_VERSION 与兼容检查）

- **不存在自动触发 VERSION_UP.ERB 的机制**：全仓 grep `VERSION_UP` 无任何引擎侧代码命中（仅 era 脚本层可自行实现）。加载失败直接抛 FileEE。
- 存档中保存的 ScriptVersion 在加载时写回 `varData.LastLoadVersion`，供 ERB 通过伪变量 `LASTLOAD_VERSION` 读取：
  - 赋值点：VariableEvaluator.cs:2442（文本）、2627（二进制）
  - 伪变量：VariableData.cs:904-913、3158；`LastLoadVersion` 默认 -1（VariableData.cs:2898）
  - 同族：`LASTLOAD_NO`（VariableEvaluator.cs:2706）、`LASTLOAD_TEXT`
- 校验逻辑 `GameBase.CheckVersion`（GameBase.cs:69-80）：
  - 未定义 ScriptVersion 且存档版本 !=1000 → 通过（旧档宽容）
  - `ScriptCompatibleMinVersion <= 存档版本` → 通过
  - 否则要求版本完全相等，失败抛 `DifferentVersion`（VariableEvaluator.cs:2435-2438）
- UniqueCode 校验：存档为 0 时豁免，否则必须相等（GameBase.cs:60-67）。
- 扩展区版本迁移：文本格式按 `__EMUERA_*_STRAT__` 标记判定 emu_version，`<1803` 走 `LoadFromStreamExtended_Old1802` 兼容路径（VariableEvaluator.cs:2457-2471、CharacterData.cs:477）。

## 4. 读写两侧类与方法清单

### 写侧

| 类/方法 | 位置 |
|---|---|
| `EraBinaryDataWriter..ctor` | EraBinaryDataWriter.cs:20 |
| `EraBinaryDataWriter.WriteHeader` | EraBinaryDataWriter.cs:35 |
| `EraBinaryDataWriter.WriteFileType/WriteInt64/WriteString` | EraBinaryDataWriter.cs:53/58/63 |
| `EraBinaryDataWriter.WriteWithKey` | EraBinaryDataWriter.cs:83 |
| `EraBinaryDataWriter.m_WriteInt / writeData(...)` | EraBinaryDataWriter.cs:175/198-478 |
| `EraDataWriter（文本）` | EraDataWriter.cs:9 |
| `VariableEvaluator.SaveTo / SaveToStream / SaveToStreamBinary` | VariableEvaluator.cs:2644/2409/2591 |
| `VariableEvaluator.SaveGlobal / SaveGlobalToStreamBinary` | VariableEvaluator.cs:2474、VariableData.cs:3961 |
| `VariableData.SaveToStream / SaveToStreamExtended / SaveToStreamBinary` | VariableData.cs:3516/3556/3980 |
| `CharacterData.SaveToStream / SaveToStreamExtended / SaveToStreamBinary` | CharacterData.cs:351/391/513 |

### 读侧

| 类/方法 | 位置 |
|---|---|
| `EraBinaryDataReader.CreateReader`（魔数/版本/解压入口） | EraBinaryDataReader.cs:786 |
| `EraBinaryDataReader1808`（唯一实现，ReaderVersion=1808） | EraBinaryDataReader.cs:14-16 |
| `ReadFileType/ReadInt/ReadString/ReadVariableCode/ReadIntArray*` | EraBinaryDataReader.cs:23/92/97/82/102-459 |
| `ReadStrArray*` | EraBinaryDataReader.cs:461-764 |
| `EraDataReader（文本，含编码探测与版本探测）` | EraDataReader.cs:32、36、133 |
| `VariableEvaluator.LoadFrom / LoadFromStream / LoadFromStreamBinary` | VariableEvaluator.cs:2687/2428/2609 |
| `VariableEvaluator.LoadGlobal / LoadGlobalFromStreamBinary` | VariableEvaluator.cs:2513 |
| `VariableData.LoadFromStream / LoadFromStreamBinary / LoadVariableBinary` | VariableData.cs:3536/3999/4081 |
| `CharacterData.LoadFromStream / LoadFromStreamBinary` | CharacterData.cs:371/560 |
| `SAVEGAME/LOADGAME 指令入口` | FunctionIdentifier.cs:2956-2981、3951-3952 |
| `SAVEGLOBAL 指令` | FunctionIdentifier.cs:1801 |

## 5. 兼容性结论：Kotlin 逐字节兼容最脆弱的 3 个点

1. **字符串编码（UTF-16LE + BinaryWriter 7-bit 变长长度前缀）**。二进制格式所有字符串是 UTF-16LE 字节序列，且长度前缀为 .NET `BinaryWriter.Write(string)` 的 **LEB128（7-bit 变长，每字节低 7 位，最高位为续位标志）** 编码——Kotlin 侧必须手写该前缀，DataOutputStream 的 UTF 格式不兼容。文本格式则需复刻 UTF-8 BOM 写入与 `EncodingHandler.DetectEncoding` 的 BOM 探测（EraDataReader.cs:36）。
2. **二进制头 + GZip 分支的精确字节**。两个 8 字节魔数、UInt32 版本 1808、恒 0 的校验表数量（EraBinaryDataWriter.cs:37-50）；`ZipSaveData=true` 时 GZip 必须用与 .NET `GZipStream` 相同的头部/尾部（header 的 MTIME 等字段 .NET 写 0），否则 Windows 端 .NET 读不了 Android 端产出。**MTIME 字段一致性未在源码中找到显式保证，需运行验证。**
3. **变量写入顺序的确定性**。二进制格式按 `varTokenDic`（`Dictionary<string, VariableToken>`）枚举顺序写出（VariableData.cs:3963/3982/3989）。.NET Dictionary 枚举顺序在无删除时为**插入序**，而 Kotlin `LinkedHashMap`/`HashMap` 遍历序不同——任何顺序偏差都会产出不同字节但语义相同的存档。逐字节兼容必须用**注册序（即 varTokenDic.Add 的调用顺序，VariableData.cs:3068-3160）**复刻，用户自定义变量亦然。文本格式的角色数组计数（2 String/2 Int/17 Int1D，CharacterData.cs:353-368）与 60 Int1D + 1 Str1D（VariableData.cs:3526-3533）为硬编码，须原样保留。

> 次要风险：`DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")` 受 locale 影响需固定 InvariantCulture（Process.cs:2872）；`Environment.TickCount` 仅影响 RNG 种子，不影响存档字节。

## 6. 实测勘误（a2-savechara，2026-10-19 实测）

> 本节为**实机对拍**（6 个 PC 原版样本，`.workbuddy/sav-backup/sav-20261018-110513/`）得到的修正，
> 与上文源码推断冲突处以本节为准（合同：真相优先，附证据）。固化于 `core-vm/.../SaveFormatTest`。

### 6.1 容器头逐字节实测

| 偏移 | 长度 | 实测值 | 结论 |
|---|---|---|---|
| 0 | 8 (UInt64 LE) | `743174590507926921` | 6 个样本**全部命中 GZip 分支魔数**（即 eratw 实机为 `SystemSaveInBinary=1 && ZipSaveData=1`） |
| 8 | 4 (UInt32) | `1808` | 与 §1.2 一致 |
| 12 | 4 (UInt32) | `0` | 校验表恒空，与 §1.2 一致 |
| 16 | 10 | `1f 8b 08 00 00 00 00 00 00 0a` | .NET 8 `GZipStream` 固定的 OS 字节 `0x0A`（Windows 侧亦为 0x0A，非通常的 0x00/0x03） |

- GZip 成员之后的固定尾含 **CRC32 + ISIZE**（.NET `GZipStream` 与小端 `Deflater` 尾部布局一致），故整文件末 4 字节是 ISIZE 高位，不是 `EOF=0xFF`；判 `EOF` 要去 GZip 外壳看 payload 末字节。

### 6.2 六个真实样本头字段实测表

| 样本 | FileType | UniqueCode | ScriptVersion | 角色数 | 备注 |
|---|---|---|---|---|---|
| `save00.sav` | 0 Normal | 7153 | 0 | 168 | 描述 `2026/05/18 14:22:54 第  1日(春之月1日) …CH20260326 `（尾含空格） |
| `save01.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save02.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save03.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save1000.sav` | 0 Normal | 7153 | 0 | 168 | 描述尾 `*自动存档*` |
| `global.sav` | 1 Global | 7153 | 0 | 0 | 无角色块；描述为空串 |

- 该批样本 `ScriptVersion=0`（即游戏侧未定义 `ScriptVersion`），`UniqueCode=7153` 全批一致；
  据此 `CheckVersion` 只能走「未定义 + 存档版本 != 1000 → 通过」这一支（GameBase.cs:69-80）。

### 6.3 往返幂等实测：整文件 6/6 + payload 6/6

- `write(read(f)) == f`（含 GZip 外壳）**6/6 逐字节相等**；`encodePayload` 与实机 payload 亦 **6/6 相等**。
- 但**不可据此认为 Kotlin 压缩器与 .NET 逐字节同构**：实测同一 payload 用 `java.util.zip.Deflater`
  level 6 输出 508 字节，.NET `DeflateStream` 为 510 字节。故实现侧 `SaveFormat.write` 对**未改动**的存档
  直接回写原字节（`SaveData.sourceFile`），仅对改动过的存档重新压缩（.NET 侧可读但字节不同）。这是 6/6 幂等的真实机制，
  §5.2 关于「GZip 需与 .NET 完全一致否则不可读」的**担忧不成立**：.NET `GZipStream` 只要能解压即可，不要求压缩字节相同。

### 6.4 勘误：RANDDATA 出现在 `saveXX.sav`（修正 §2.2 第 85 行）

- §2.2 称「RANDDATA（625 元素全局数组）属于 global.sav，不进 saveXX.sav」。**实测为假**：
  `save00.sav` 的全局块里 `RANDDATA` 确实出现（payload 偏移 ~564xxx，625 元素）。
- 归因：`RANDDATA` 定义处 `IsGlobal` 标记与「是否写入普通存档」的筛选口径不一致——
  `SaveToStreamBinary` 排除的是 `IsGlobal`，但 RANDDATA 的 `IsGlobal` 实为假（详见 TODO(verify)）。
  **结论：普通存档的全局块遍历筛选必须覆盖 RANDDATA，否则 saveXX.sav 幂等必破。**

### 6.5 勘误：角色存档（CHARVAR）尾部多一个 EOF 字节（补 §2.2 / §1.2）

- 角色文件 `chara_<name>.dat` 的 payload 布局，在 §1.3/§2.2 之外**末尾还有一个 `0xFF`**：
  `… 角色数 | 每角色条目…EOC | EOF(0xFF)`。
- 证据：`VariableEvaluator.SaveChara` 在写完角色块后调用 `WriteEOF()`（VariableEvaluator.cs:2270）；
  而 `LoadChara` 只读 `num` 个角色、**不消费**这个字节（VariableEvaluator.cs:2308-2314），故读侧宽容、
  写侧必须补，否则 `write(read(f)) == f` 在末字节差 1 字节。已在 `SaveFormat.encodePayload` 对 CHARVAR 补齐。

### 6.6 勘误（实现侧拦路石）：SAVECHARA 语句参数序被颠倒

- 原版参数序为 **`termList[0]=文件名(String), termList[1]=描述(String), termList[2..]=角色号(long…)`**：
  `SAVECHARA_Instruction.DoInstruction`（FunctionIdentifier.cs:1874-1895，`strValue=term[0]`、`strValue2=term[1]`、
  `array[i]=term[i+2]`）+ `SP_SAVECHARA_ArgumentBuilder`（ArgumentParser.cs:2231-2242，类型 `String,String,Long`）。
- 当前 Kotlin 实现把顺序反了（`sem/StatementExecutor.kt:741-748`：`nos=terms.dropLast(2)`、`filename=terms[size-2]`、
  `text=terms[size-1]`）。后果：`SAVECHARA "GOLDEN_CHARA.dat","golden chara",0` 被解析成
  `saveChara(nos=[0(文件名的 getIntValue→0)], filename="golden chara"(描述), text="0")`，
  实际落盘文件名 = `chara_golden chara.dat`（E2E 临时 dump 实证）。
- 影响的判据：GOLDEN_IO 的 IO-CHARAFILE 节（`FIND_CHARADATA=0` / `CHKCHARADATA=1` / `AFTER-LOAD` 无角色）。
  该文件非本 agent 所有权，按合同列为「需组长接线」。修法见交付报告。
