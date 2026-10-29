# loader.md — Emuera 脚本装载 / 排序 / 覆盖规约

来源: `reference/emuera-cs/` 反编译源码（Emuera 1.824.0.0）。所有结论均附 `文件:行号`。
路径均相对 `reference/emuera-cs/`。

## 0. 装载总时序

`Process.Initialize`（游戏启动）依次执行（`MinorShift.Emuera.GameProc/Process.cs:240-333`）:

1. `_Replace.csv`（仅 `Config.UseReplaceFile` 且文件存在于 `csv/`，`ConfigData.LoadReplaceFile`，Process.cs:240-256）
2. `_Rename.csv`（仅 `Config.UseRenameFile`，`ParserMediator.LoadEraExRenameFile`，Process.cs:261-275）
3. `GAMEBASE.CSV`（Process.cs:282-288）
4. 其余 CSV：`ConstantData.LoadData(Program.CsvDir, ...)`（Process.cs:292-293）
5. ERH：`ErhLoader.LoadHeaderFiles(Program.ErbDir, ...)`（Process.cs:309-318）
6. ERB：`ErbLoader.LoadErbDir(Program.ErbDir, ...)`（Process.cs:329）

目录根：`Program.SetDirPaths` 固定为 `ExeDir/csv` 与 `ExeDir/erb`（`MinorShift.Emuera/Program.cs:202-225`）。

---

## 1. ERB 文件从哪些目录扫描

结论：**只扫描 `erb/` 根目录本身，以及名字含 `#` 的一级子目录；子目录内部是否递归由 `SearchSubdirectory` 配置决定。根目录会被扫描，但被装载在 `#` 目录之后。**

证据：
- `ErbLoader.LoadErbDir`（`MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:210-335`）:
  - 第 214 行: `Directory.GetDirectories(erbDir, "*#*", SearchOption)` —— 只取**名字匹配 `*#*`**（含一个 `#` 即可）的直接子目录；递归深度由 `Config.SearchSubdirectory` 决定 `AllDirectories/TopDirectoryOnly`。
  - 第 216 行: `Config.GetFiles(erbDir, "*.ERB")` —— 根目录的 ERB 文件。
  - 第 224-238 行: **先**装载各 `#` 子目录中的 ERB（对每个子目录调用 `Config.GetFiles(dir, erbDir, "*.ERB")`）。
  - 第 252-264 行: **后**装载根目录文件，且跳过已由子目录装载过的路径（`list.Contains` 去重）。
- `Config.GetFiles`（`MinorShift.Emuera.Runtime.Config/Config.cs:644-706`）: 当 `SearchSubdirectory=true` 时在各目录内再按 `Directory.GetDirectories(dir,"*",TopDirectoryOnly)` 递归全部子目录；`SearchSubdirectory=false`（默认，ConfigData.cs:84）时只取该目录顶层。
- 目录名不匹配 `*#*` 的子目录**永远不会被扫描**（即使 SearchSubdirectory=true，因为列表来自第 214 行的 `*#*` 过滤）。
- 另一入口 `LoadErbList`（ErbLoader.cs:337-365）仅供调试器/部分重载（AnalysisMode）按显式文件列表装载（Process.cs:323-330）。

## 2. 文件排序规则

结论：**排序由配置 `SortWithFilename`（默认 false）控制；开启时用 `Array.Sort(string[])`（即按当前 culture 的 `String.CompareTo`，不是序数比较，也不是 `AscCharacterComparison`）；关闭时保持 `Directory.GetFiles` 的文件系统返回顺序（NTFS 上通常为按名序，但不保证）。`AscCharacterComparison` 与 ERB 装载无关，它只用于 SORTCHARA。**

证据：
- `Config.getFiles`（Config.cs:654-706）: 第 679-682 行 `if (sort) Array.Sort(files)`；第 695-698 行对子目录同样 `Array.Sort(directories)`；`sort` 实参来自 `Config.SortWithFilename`（Config.cs:646,651）。
- `Array.Sort(string[])` 无比较器 → `Comparer<string>.Default` → `string.CompareTo`（culture 敏感排序）。反编译源中没有任何 culture 设置，随运行环境。
- `ConfigData.cs:85`: `SortWithFilename` 默认 `false`（"読み込み順をファイル名順にソートする"）。
- `AscCharacterComparison`/`DescCharacterComparison` 定义于 `MinorShift.Emuera.Runtime.Script.Statements.Variable/CharacterData.cs:772-790`，仅被 `VariableEvaluator.SortChara` 调用（`MinorShift.Emuera.Runtime.Script.Statements.Variable/VariableEvaluator.cs:1441,1445`）—— 即 SORTCHARA 指令的角色排序比较器，与文件装载无关（全仓 Grep 仅此两处使用）。
- 装载顺序的实际影响：同名非事件函数多重定义时，`FunctionLabelLine.CompareTo` 按 `FileIndex → 行号 → Index` 排序（`MinorShift.Emuera.Runtime.Script.Statements/FunctionLabelLine.cs:85-96`），`FileIndex` 即装载序（`MinorShift.Emuera.Runtime.Script.Data/LabelDictionary.cs:318-322`），因此**排序规则间接决定同名函数谁生效**。

## 3. 同名函数覆盖规则

结论：**同名非事件函数：后装载的定义胜出（按 FileIndex/行号排序后取列表第一个，即最早装载者胜出——见注意）；事件函数（EVENTFIRST 等）不覆盖而是全部注册并按 ONLY/PRI/LATER 分桶依次调用；"ALLOW OVERRIDING SYSTEM FUNCTIONS" 对应配置项 `AllowFunctionOverloading`（默认 true），关闭时用户函数与系统函数/保留字重名直接报错。**

证据与注意：
- 所有同名 label 都存入 `labelAtDic[name]` 列表（LabelDictionary.cs:318-337），不互相覆盖。
- `SortLabels` 中 `value2.Sort()`（LabelDictionary.cs:170-172）按 FunctionLabelLine.CompareTo（FileIndex→行号，FunctionLabelLine.cs:85-96）排序，随后**取 `value2[0]` 注册为非事件函数**（LabelDictionary.cs:174-178）——即**装载序最靠前（先装载）的定义生效**。注意：`noneventLabelDic[labelName] = label` 为直接赋值（LabelDictionary.cs:65），在逐文件装载路径（`SortLabel`，LabelDictionary.cs:60-68）下后装载者会覆盖前者；`SortLabels` 全量重建路径下先装载者生效。两条路径并存，移植时以 `SortLabels`（每次 LoadErbDir 结束前 labelDic.Initialized=true 后调用链，ErbLoader.cs:568-575 setLabelsArg→SortLabels）为准。
- `WarnNormalFunctionOverloading`（默认 false，ConfigData.cs:94）: 开启时对重复定义告警（ErbLoader.cs:443-451）。
- `AllowFunctionOverloading`（默认 true，ConfigData.cs:89）:
  - 关闭时: `WarnFunctionOverloading` 强制为 true（Config.cs:344-352）；标识符检查中与保留字/系统方法重名 warnLevel=2（错误）（`MinorShift.Emuera/IdentifierDictionary.cs:210-235`）。
  - 开启时: 与保留字/系统方法重名仅 warnLevel=1（警告）（IdentifierDictionary.cs:212-235）。
  - 装载结束后若允许覆盖且 `WarnFunctionOverloading`，打印被覆盖系统函数清单（ErbLoader.cs:914-934；列表来自 `IdentifierDictionary.GetOverloadedList`）。
- 事件函数分桶: ONLY/PRI/LATER/普通 4 桶（LabelDictionary.cs:90-105, 202-230），调用顺序由解释器按桶序执行。
- 事件函数带参数报错、系统函数（IsSystem）带参数报错（ErbLoader.cs:584-603）。

## 4. CSV 装载

结论：**`csv/` 下按固定文件名装载到固定变量名字表；`_Replace.csv` 在一切之前、`_Rename.csv` 在 GAMEBASE 之前处理（仅影响后续解析，不做变量改名）；`CHARA*.CSV` 建角色模板；`VariableSize.CSV` 先行决定数组尺寸；`VarExt*.csv` 递归搜索声明可存档 Map/Xml/DT。**

证据（`MinorShift.Emuera.Runtime.Script.Data/ConstantData.cs`）:
- `LoadData`（672-756 行）顺序:
  1. `VariableSize.CSV`（675 行）→ `changeVariableSizeData`（309-551 行）改 `VariableXxxArrayLength/CharacterXxxArrayLength`；名字表与对应变量长度联动（`decideActualArraySize`，583-670 行）；0/负尺寸可将 `__CAN_FORBID__` 变量禁用（340-357 行）；非局部数组最小 100、上限 1,000,000（364-378 行）。
  2. 29 组名字 CSV（682-710 行）: ABL→names[0], EXP→1, TALENT→2, PALAM→3, TRAIN→4, MARK→5, ITEM→6(+ItemPrice), BASE→7, SOURCE→8, EX→9, STR→10, EQUIP→11, TEQUIP→12, FLAG→13, TFLAG→14, CFLAG→15, TCVAR→16, CSTR→17, STAIN→18, CDFLAG1→19, CDFLAG2→20, STRNAME→21, TSTR→22, SAVESTR→23, GLOBAL→24, GLOBALS→25, DAY→26, TIME→27, MONEY→28。
  3. `CHARA*.CSV`（`loadCharacterData`，1257-1307 行; `Config.GetFiles(csvDir,"CHARA*.CSV")`）→ CharacterTemplate；`NO` 行起始模板，支持日文键（名前/呼び名/あだ名/主人の呼び方/能力/素質/経験/刻印/相性/フラグ/装着物/珠）（1568-1745 行）；文件名 CHARA 后的数字存为 `csvNo`（1474-1491 行）；按 No 排序（1503 行）；重复 No 告警（1282-1306 行）。
  4. `VarExt*.csv`（递归 AllDirectories，1309-1429 行）: 键 GLOBAL_MAPS/SAVE_MAPS/GLOBAL_XMLS/SAVE_XMLS/GLOBAL_DTS/SAVE_DTS/STATIC_MAPS/STATIC_XMLS/STATIC_DTS。
- `_REPLACE.CSV`: `ConfigData.LoadReplaceFile`（Process.cs:246）—— 在 ConstantData 之前执行；替换 `Globals`/`固定尺寸`等配置值。具体键集合见 `MinorShift.Emuera.Runtime.Config/ConfigData.cs:518-670`（LoadReplaceFile/LoadEraExRenameFile 同文件）。时机: **先于 GAMEBASE.CSV 与一切 CSV/ERB**（Process.cs:240-258）。
- `_RENAME.CSV`: `ParserMediator.LoadEraExRenameFile`（`MinorShift.Emuera/ParserMediator.cs:65-102`）—— 格式 `原名,新名`，存入 `RenameDic["[[新名]"]=原名]`；时机: 在 ERH/ERB 解析前（Process.cs:261-275），ERB 读取时若 `UseRenameFile` 开启则将 `[[xxx]]` 替换回原名（`MinorShift.Emuera.Sub/EraStreamReader.cs:111-160`; ErbLoader.cs:370, ErhLoader.cs:80）。CSV 装载器全部 `useRename:false`（ConstantData.cs:268,1320,1434,1755,1852），即 **_Rename 不作用于 CSV**。

## 5. `.als` 文件（别名文件）

结论：**被引擎加载。处理类为 `ConstantData`：每装载一个名字 CSV（如 ABL.CSV）后，检查同目录同名 `.als`（ABL.CSV → ABL.als），格式为 `序号,别名`，登记为该名字表的别名；别名仅用于"字符串 → 序号"的索引转换，不改变数组内容。**

证据（ConstantData.cs）:
- 挂载点: `loadDataTo` 末尾，1834-1838 行:
  `Path.GetDirectoryName(csvPath) + "\\" + Path.GetFileNameWithoutExtension(csvPath) + ".als"` 存在则 `loadAliases(text, targetIndex)`。因此任意一组名字 CSV（ABL/EXP/…/MONEY 共 29 组）都可配 `.als`，不只是 Abl.als。
- 解析: `loadAliases`（1841-1903 行）: 每行 `Split(',')`，第 1 个 token 必须可解析为 int，第 2 个 token（Trim 后）为别名；重复别名告警"别名已定义"（1877-1880 行）；存入 `aliases[targetIndex]`。
- 生效: 别名在 `LoadData` 中并入 `nameToIntDics[j]`（725-736 行），先到先得（正式名字优先于别名，720-723 行）；之后经 `GetKeywordDictionary`/`KeywordToInteger`（897-912 行）供 `ABL:名字` 之类的字符串索引用。
- `CHARA*.CSV` 没有 `.als` 机制（`loadCharacterDataFile` 不调用 loadAliases）。

## 6. 相关配置项行为

| 配置（ConfigData.cs 行号） | 默认 | 行为 |
|---|---|---|
| `IgnoreCase`（=49 区间内，ConfigData.cs） | 见 ConfigData | 开启: 标识符/宏比较用 `OrdinalIgnoreCase`（`Config.StringComparison`/`StrComper`，Config.cs:299-308）；变量名大小写不敏感；关闭则为 `Ordinal` |
| `CompatiFunctionNoignoreCase` | - | 兼容开关（ConfigDialog.cs:746），影响函数名大小写匹配（MinorShift.Emuera/IdentifierDictionary.cs:404,421,504 宏哈希方式） |
| `IgnoreUncalledFunction`（ConfigData.cs:79，默认 **true**） | true | ErbLoader.ParseScript（ErbLoader.cs:831, 867-876）: 为 true 时**不解析**未被调用的函数体，仅把其函数体下一行标记 IsError="呼び出されないはずの関数が呼ばれた"（运行到即报错）；为 false 时全部解析 |
| `FunctionNotCalledWarning`（ConfigData.cs:81，默认 IGNORE） | IGNORE | ONCE/DISPLAY 控制未被调用函数的告警打印（ErbLoader.cs:810, 842-866） |
| `FunctionNotFoundWarning`（ConfigData.cs:80，默认 IGNORE） | IGNORE | 控制调用未定义函数告警: IGNORE 静默计数、DISPLAY 打印、ONCE 每文件一次（ErbLoader.cs:937-993） |
| `AllowFunctionOverloading`（ConfigData.cs:89，默认 true） | true | 允许用户函数覆盖系统函数名（见第 3 节） |
| `WarnFunctionOverloading`（ConfigData.cs:90，默认 true） | true | 覆盖发生时打印清单（ErbLoader.cs:914-934） |
| `WarnNormalFunctionOverloading`（ConfigData.cs:94，默认 false） | false | 同名非事件函数重复定义告警（ErbLoader.cs:443-451） |
| `SearchSubdirectory`（ConfigData.cs:84，默认 false） | false | 递归子目录（影响 ERB/ERH/CSV 搜索深度，Config.cs:614-651） |
| `SortWithFilename`（ConfigData.cs:85，默认 false） | false | 文件名排序装载（见第 2 节） |
| `UseRenameFile`（ConfigData.cs:46，默认 false） | false | 启用 `_Rename.csv`（Process.cs:261-275; EraStreamReader.cs:111-160） |
| `UseReplaceFile`（ConfigData.cs:47，默认 true） | true | 启用 `_Replace.csv`（Process.cs:240-256） |
| `UseERD`（ConfigData.cs:116，默认 true） | true | 启用 ERD 数据文件: ERH 中 `#DIM` 后按变量名匹配 `*.erd`（ErbDir 递归）与 `csv/*.csv`（ErhLoader.cs:342-388; ConstantData.UserDefineLoadData 758-793） |
| `UseLazyLoading` | - | 惰性装载 ERB（Process.cs:329; ErbLoader.cs:239-251） |
| `ReduceArgumentOnLoad` | - | 装载期预求值函数实参（ErbLoader.cs:1036-1039） |

注: `StrComper`（变量/函数字典比较器）在 `IgnoreCase=true` 时为 `StringComparer.OrdinalIgnoreCase`，否则 `Ordinal`（Config.cs:33, 299-308）；`varTokenDic`、`labelAtDic` 等全部以它构建（VariableData.cs:2904-2906; LabelDictionary.cs:12-28）。

## 7. 文本解码（装载链的编码口径）

结论：**脚本/数据文件的读入走 `Runtime.Utils.EncodingHandler.DetectEncoding`（`UTF-8 BOM` → 严格 `UTF-8` → `CP932` 回落）；`Config.Encode` 只管写侧/调试侧，且在本 reference 里是 `UTF8BOMEncoding`（不是 Shift-JIS）。固定 `Encoding.UTF8` 只出现在三个 lazyloading 索引文件，与脚本装载无关（例外面见下）。**

证据（路径相对 `reference/emuera-cs/`）：

- 探测本体与严格性：`Runtime.Utils/EncodingHandler.cs:15-41`（路径版）、`:43-65`（流版）、`:9`/`:13`（`throwOnInvalidBytes: true`）、`:67-71`（`EncoderFallback`/`DecoderFallback` = ExceptionFallback）。
- 装载链上的读入口：
  - ERB/ERH/CSV：`MinorShift.Emuera.Sub/EraStreamReader.cs:52` —— `File.ReadAllLines(filepath, DetectEncoding(path))`；失败由 `:50-59` 吞成 `false` → 上游 `FailedOpenFile` 告警并跳过整文件。
  - 懒装载预读：`Runtime.Utils/Preload.cs:19-46`（`:29-32` 先判 UTF-8 BOM，`:33` 再探测），结果经 `Preload.GetFileLines`（`:14-17`）由 `EraStreamReader.OpenOnCache`（`:73`）消费。
  - `emuera.config` / `emuera.configdebug`：`Runtime.Config/ConfigData.cs:518-519`、`:669-670` 用 `EraStreamReader.Open`（同一条探测链）。
  - `_Rename.csv`：`ParserMediator.cs:75`；`_Replace.csv`：`ConfigData.LoadReplaceFile`（同 `EraStreamReader` 链）。
  - `content/` 下 CSV：`UI.Game.Image/AppContents.cs:141`；`HOTKEY.ERB`：`UI.Game/HotkeyState.cs:186`；`macro.txt`：`Runtime.Script/KeyMacro.cs:91-92`（`EraStreamReader.Open`）；`LOADTEXT`：`GameData.Function/FunctionMethodCreator.cs:8082`。
  - 报错回显源码：`GameProc/Process.cs:688`（ERB）/`:700`（CSV）。
- 写侧：`Config.Encode` 只有 6 处消费者（`Forms/DebugDialog.cs:291`/`:309`/`:338`、`Runtime.Config/ConfigData.cs:429`/`:646`、`Runtime.Script/KeyMacro.cs:68`），全为写日志/写配置/写宏 + 调试器读自己的 watch 列表；存档方向用 `SaveEncode`（`Runtime.Utils/EraDataWriter.cs:24`、`FunctionMethodCreator.cs:8010`）。
- **不适用面**：三个 lazyloading 索引文件用固定 `Encoding.UTF8`（`GameProc/Process.cs:738`/`:766`/`:767`）；`EmueraConsole.cs:3531`（`File.ReadAllText` 缺省编码）与 `JSONConfig.cs:21` 也不走探测。三者都不是脚本/数据主路。

边界：

- 探测只对「可打开的文件」或「可 `Seek` 的流」成立（`EncodingHandler.cs:19`；`:45`/`:52`/`:57`/`:62`）。
- 无 BOM 的 UTF-16/32 识别不了（只有 BOM 嗅探分支，`:23-25`/`:27-32`）⇒ 落到 CP932 或整体失败。
- CP932 回落是严格的（`:70`）⇒ 坏字节抛异常、整文件按打开失败处理，不是「尽力解码」。
- 探测只选解码器，不剥 BOM（BOM 由 `StreamReader`/`ReadAllLines` 消费）；直接对整份字节严格解码时 U+FEFF 会留在串首。

> 移植侧对应实现、以及「不许按旧结论改回固定 Shift-JIS」的裁定见 `verify-notes.md` §4；严格 UTF-8 校验器的手写判据表见 `verify-notes.md` §15.2。

---

## 未找到证据 / 需运行验证

1. ** culture 无关的文件排序**: `Array.Sort(string[])` 依赖运行时 culture（Config.cs:681）；反编译源未见固定 culture，精确排序序需在目标环境验证。
2. **同名非事件函数"后装载覆盖先装载"的两条路径歧义**: `SortLabel`（逐文件，LabelDictionary.cs:60-68 直接赋值覆盖）与 `SortLabels`（全量重建取排序后第一个，LabelDictionary.cs:166-178）并存；需以运行验证最终生效者（静态读码倾向于 SortLabels: 先装载者生效）。
3. **`CompatiFunctionNoignoreCase` 的精确语义**: 仅定位到 ConfigDialog.cs:746 与 IdentifierDictionary.cs:404,421,504 的使用点，未追完整调用链。
4. **`GetCharacterTemplate_UseSp` 的二分查找使用未排序比较器**（ConstantData.cs:1230-1238）: 疑似反编译伪代码失真，需运行验证。
5. **`LoadErbDir` 中 `Directory.GetDirectories` 的目录枚举顺序**: 源码未显式排序（ErbLoader.cs:214），多个 `#` 目录间的先后依文件系统返回序。
6. **`analyzeSharpFunction`（ERH 的 #FUNCTION/#FUNCTIONS）**: 直接抛 NotImplCodeEE（ErhLoader.cs:390-393），本版本未实现。

---

## 附录：组长运行证据补充（2026-10-17）

### A. 开放问题①（扫描目录）已定位到代码，结论修正

实测：ERB 树内**不存在任何**名字含 `#` 的目录；游戏却加载 2,348+ 个深层口上文件——与正文第 1 条的扫描逻辑矛盾。

解决：本引擎（LazyLoading 定制版）读取 `lazyloadingfiles.dat` 索引：

- 索引文件：2,154 行，UTF-8 BOM，每行 `相对路径<TAB>FILETIME(.NET ticks)`
- 引用点：`Process.cs:78`（`LazyLoadingFilesFilePath`）、`Process.cs:154-178`（`LazyLoadingFilesTable`/`LazyLoadingFiles`，`Config.IgnoreCase` 时用 `OrdinalIgnoreCase` 字典）
- 结论：**目录枚举不被索引替代**。精确语义见**附录 C（已定稿）**。

### B. 第 2 条（排序）按本游戏配置修正

- 本游戏 `emuera.config` 明确 `SORT FILENAMES:YES` → `Array.Sort(files)` 使用 **culture-sensitive `string.CompareTo`**（非序数比较）。
- 影响ADR-001 契约#2：JVM 侧须复刻 .NET 当前文化的字符串比较；在同一台机器上行为确定，跨机器文化差异是固有风险（PC 原版同样存在）。

---

## 附录 C：LazyLoadingFilesTable 消费语义（定稿，2026-10-17）

本附录精读 `Process.cs` 懒装载全链路后的定稿。路径相对 `reference/emuera-cs/`。

### C.0 三个文件的分工（定稿）

| 文件 | 定义点 | 内容 | 角色 |
|---|---|---|---|
| `lazyloading.cfg` | Process.cs:80 | 每行一个相对目录（本游戏：`口上・メッセージ関連\個人口上`，实测 1 行） | **声明哪些目录参与懒装载**（`LoadLazyLoadingFolders`，Process.cs:726-751；前缀过滤 `GetLazyFiles`，Process.cs:831-842） |
| `lazyloading.dat` | Process.cs:76 | 每行 `函数名<TAB>相对路径` | **函数名 → 文件**的运行期查找表（`LazyLoadingTable`，Process.cs:803-820），供调用未命中时按名取回文件 |
| `lazyloadingfiles.dat` | Process.cs:78 | 每行 `相对路径<TAB>FILETIME` | **新鲜度索引**（`LazyLoadingFilesTable`，Process.cs:770-800），判断文件自建索引后是否被修改/删除 |

### C.1 问1：索引在装载流程中的角色 —— **仅作排除/新鲜度判断，不替代目录扫描**

主装载列表始终来自目录扫描。证据链（`ErbLoader.LoadErbDir`，ErbLoader.cs:210-335）：

1. 214-238 行：装载 `#` 子目录（本游戏无此类目录）。
2. 239-251 行：`useLazyLoading` 时调 `parentProcess.LoadLazyLoadingTable(files)`——实参 `files` 是**全量目录扫描结果**（216 行 `Config.GetFiles(erbDir, "*.ERB")`，本游戏 `SearchSubdirectory:YES` → 递归全树，Config.cs:646 + emuera.config:39）。此步只**填充**三张表，不装载任何文件。
3. 252-264 行：遍历全量扫描 `files`，对每个文件：
   - 已被 `#` 子目录装载 → 跳过（256 行 `list.Contains`）；
   - `useLazyLoading && parentProcess.LazyLoadingFiles.Contains(value2)` → 跳过（256 行）——**唯一由索引排除文件的点**；
   - 否则 `loadErb`（262 行）。
4. `LazyLoadingFiles` 的成员 = 索引中 FILETIME 与磁盘一致（未修改）**且**存在于 `lazyloading.dat` 的文件全路径（Process.cs:785-794 只有未变更者进 `LazyLoadingFilesTable`；Process.cs:814-819 从 `lazyloading.dat` 的条目构建 `LazyLoadingFiles`）。已修改（`ChangedFiles`）/已删除（`DeletedFiles`）/索引外文件一律**立即装载**。
5. 被排除（懒装载）文件在运行期补齐：调用非事件函数未命中 `labelDic` 时 `CalledFunction.cs:94` → `TryLazyLoadErb`（Process.cs:705-724）查 `LazyLoadingTable[函数名]` → `LoadErbList` 现场解析；方法形式调用另有触发点 `FunctionMethodCreator.cs:8251`。
6. `LazyLoadingFilesTable` 本身不参与跳过判断，仅用于：计算新增懒文件（Process.cs:801-802 `lazyFiles.ExceptWith(...Keys)`）与索引增量重写（Process.cs:972-975）。

**结论：扫描是全集，索引只做"从中扣除新鲜的懒装载文件"的差集运算。索引不存在/过期/损坏时退化为全量装载（Process.cs:759-763 缺文件 → BuildTable；解析失败 → Error，822-827 行，两者都不跳过任何文件）。**

### C.2 问2：2,154 vs 3,971 差值 —— 索引**按设计**只覆盖懒装载目录的子集，非过期

实测（游戏目录 `eratw-画蛇添足版eratw4.980/`）：

- erb/ 树磁盘文件：4,511 个，其中 `.ERB` 3,818、`.ERH` 152（组长口径 3,971 ≈ 3,818 ERB + 152 ERH + 1，即把 ERH 计入；**纯 ERB 为 3,818**，此差异已澄清）。
- 索引 2,154 行**全部**位于 `口上・メッセージ関連\個人口上` 下（cut 统计：2,153 + BOM 首行 1）。
- 该懒装载目录磁盘 `.ERB` 为 2,285 个 → 差 131 个。

判定依据（索引生成逻辑 `SaveLazyLoadingList`，Process.cs:844-895）：

1. 候选集 = 扫描结果中路径以 `lazyloading.cfg` 目录为前缀的文件（Process.cs:838-841）。**懒目录之外的 3,818−2,285=1,533 个文件按设计永不入索引**。
2. 候选中含 `#FUNCTION`（`IsMethod`）或事件函数（`IsEvent`）定义的文件被**剔除**（Process.cs:858-870）——其余 131 个差值即来自此类（含事件函数/纯方法文件，或无任何有效 label 的文件）。【推断】未逐文件验证 131 个的构成，机制有行号证据。
3. FILETIME 采样值全部相同（如 134190950720000000）→ 索引为一次性批量生成，与 `索引生成.bat`（内容仅 `python mkResourceXml.py`，游戏目录内）吻合，属外部工具生成，非引擎增量更新产物。【推断】
4. 索引未过期：若文件 mtime 与索引不符会被 `LoadLazyLoadingTable` 判入 `ChangedFiles`（Process.cs:787-790）并立即装载——这是引擎内建的新鲜度兜底，无需担心陈旧索引导致漏装载。

### C.3 问3：`lazyloading.dat` 与 `lazyloadingfiles.dat` 分工

- `lazyloading.dat`：**解析缓存/查找表**——`函数名 → 文件路径列表`（`LazyLoadingTable`，Process.cs:803-820），运行期 `TryLazyLoadErb` 按函数名定位需现场装载的文件。它是懒装载的唯一"取回"通道。
- `lazyloadingfiles.dat`：**纯新鲜度账本**——`文件 → FILETIME`（`LazyLoadingFilesTable`），只回答"这个文件自建索引后改过没有"，不参与取回。
- 两者在 `LoadLazyLoadingTable` 中成对读取（Process.cs:766-767），缺任一即整体重建（759-763 行）。

### C.4 问4：装载顺序定稿

本游戏配置（`SearchSubdirectory:YES`、`SortWithFilename:YES`、无 `#` 目录）下的精确顺序：

1. **ERH 先于全部 ERB**（Process.cs:309-318 → ErhLoader；ERH 内部顺序见 C.5）。
2. ERB 急切装载序 = `Config.getFiles` 的输出序（Config.cs:654-706）：
   - erb/ 根目录文件：`Directory.GetFiles` 后 `Array.Sort`（culture-sensitive `string.CompareTo`，Config.cs:678-682）；
   - 随后按 `Directory.GetDirectories` + `Array.Sort` 的子目录序（Config.cs:692-698）递归，每层内同样先排文件再进子目录（**先序 DFS：同层内文件先于其子目录文件**）。
   - `SORT FILENAMES` 作用于 `getFiles` 内部的文件与子目录排序（Config.cs:646/651 传入 `SortWithFilename`）；**不作用于** `LoadErbDir:214` 的 `GetDirectories("*#*")`（该调用未排序，本游戏无 `#` 目录，无影响）。
3. **索引不影响装载顺序**——它只从第 2 步列表中扣除 2,154 个新鲜懒文件（ErbLoader.cs:256）。懒文件的"装载"（解析）发生在运行期首次调用时，其 `FileIndex` 依调用先后动态分配。
4. 同名非事件函数的生效者由装载序决定（正文第 3 节）；懒文件之间在索引 `lazyloading.dat` 中的行序决定 `TryLazyLoadErb` 的装载序（Process.cs:819 按读入顺序 append）。

### C.5 问5：ERH 走独立扫描，不经懒装载索引

- `ErhLoader.LoadHeaderFiles`（ErhLoader.cs:40-75）: 42 行 `Config.GetFiles(headerDir, "*.ERH")` 独立目录扫描，与 `LazyLoadingFiles` 无任何交集（全仓 Grep `LazyLoadingFiles` 仅 Process.cs / ErbLoader.cs:245,256 两处消费）。
- 排序同样受 `SortWithFilename` 控制（Config.cs:646）。**ERH 全部在 ERB 之前装载**（Process.cs:309-318 先于 :329）。
- 实测 `.ERH` 152 个（组长口径 157 差 5 个，疑似统计口径含 `erh` 小写或其他目录，未深究——数量不影响语义）。

### C.6 问6：erbcc 编译输入算法（定稿）

核心判断：**懒装载文件在运行期仍会被完整解析（`TryLazyLoadErb` → `LoadErbList`，Process.cs:711），因此 erbcc 必须编译全部 ERB；索引只影响"何时解析"，不影响"是否解析"。编译输入列表 = 全量目录扫描，与索引无关。**

```
输入: erbDir, lazy_cfg(可选), 配置 SearchSubdirectory/SortWithFilename/UseLazyLoading

scan(dir, topOnly = !SearchSubdirectory, sort = SortWithFilename):
    files = Directory.GetFiles(dir, "*.ERB", TopOnly)      # CultureSensitive 排序 if sort
    for f in files: yield f
    if !topOnly:
        for d in Sort(Directory.GetDirectories(dir)):      # 注意: 全部子目录, 无 # 过滤
            yield from scan(d)

ERB 编译输入 = scan(erbDir)                                # 即全部 3,818 个 .ERB
ERH 编译输入 = scan_erh(erbDir)                            # 独立扫描 "*.ERH", 全部先编译
```

补充语义（若 erbcc 需复刻运行期行为）：

- 急切装载子集 = `scan(erbDir)` − `{索引内 FILETIME 新鲜且在 lazyloading.dat 中的文件}`（ErbLoader.cs:256）；本游戏即 3,818 − 2,154 = 1,664 个急切 + 2,154 个懒。
- 懒文件编译产物的查找键 = 函数名（`lazyloading.dat` 的映射），运行期按名索引。
- 若 `UseLazyLoading:NO` 或索引缺失 → 全部急切装载。

### C.7 残留风险清单

1. **`Array.Sort` 的 culture 依赖**（Config.cs:681）: 跨环境排序可能不同，影响同名函数胜出者与编译序。JVM 侧须冻结同一 culture（正文附录 B 已记）。
2. **懒文件间同名函数的 FileIndex 动态性**: 懒文件解析序 = 运行期首次调用序（或 `lazyloading.dat` 同函数多文件时的行序，Process.cs:810-819），静态编译无法预知；本游戏口上函数名按角色前缀区分，实际冲突概率低。【推断】
3. **131 个懒目录内未索引文件的精确构成**未逐个验证（机制证据充分，见 C.2）；erbcc 若要做"急切/懒"二分必须复刻 `SaveLazyLoadingList` 的剔除规则（IsMethod/IsEvent 剔除，Process.cs:858-870），建议 erbcc 直接放弃二分、全量编译。
4. **索引为外部工具生成**（`索引生成.bat` → python 脚本），其 FILETIME 填充方式与引擎 `ToFileTimeUtc`（Process.cs:884,963,974）的兼容性未验证；若安卓侧自建索引，须用 `File.GetLastWriteTime(...).ToFileTimeUtc()` 同源算法。
5. `FunctionMethodCreator.cs:8251` 的懒装载触发条件（方法形式调用解析时）细节未深读，只确认存在该触发点。
6. 组长口径"3,971 个 ERB"与实测"3,818 .ERB + 152 .ERH"的差异已在本附录澄清，后续文档以 3,818/152 为准（157 个 ERH 的口径未复现）。

### C.8 组长实证修正（2026-10-17，erbcc 全量扫描反证）

C.6「编译输入 = 全量扫描」**修正为：编译输入 = engine 集（lazyloadingfiles.dat 索引 ∪ ERB 根目录）= 2,180 文件**。

反证：全量编译时全集 280 个重名 @函数标签中 **267 个由非索引非根的 1,791 个死文件引入**——这些文件引擎永不装载（不在索引、不在根目录，lazyloading.dat 不会指向它们），全量编译会产生原版运行时不可能出现的 267 个函数重定义错误。

区分两个概念：**运行期可达集**（index∪root，懒文件按需解析）≠ **磁盘全集**。erbcc 的编译输入取前者。工具链实测：all/index/union/engine 四策略扫描均 0 无法解释异常（tools/erbcc/scan-report/）。

### C.9 二次修正（2026-10-17，erbcc 修订版确认）：C.8 的重名反证不成立，编译输入 = 全量（3,818 .ERB）

C.8 的「267 个重名标签 → 死文件」反证**被推翻**：原版对重名函数标签本身有容忍机制（非事件函数全存列表，SortLabels 按 FileIndex→行号取先装载者生效，见 `IdentifierDictionary.cs`；事件函数另有覆盖规则）——重名不等于装载错误。m1-loader-sem 的源码证据（`Process.cs:711` 懒文件运行期仍完整解析）权重更高。

**最终定稿**：
- erbcc 编译输入 = 全量目录扫描（3,818 个 .ERB + 153 个 .ERH，与 `lazyloadingfiles.dat` 解耦）；erbcc scan 默认策略 = all。
- 全量编译集内约 280 个重名 @标签按原版容忍规则处理——**精确规则（先装载者生效 vs 事件函数覆盖）是 M2 最高优先验证项**，erbcc 符号表策略随其定稿。
- 「engine 集（index∪root=2,180）」降级为「急切装载近似集」参考概念，不再是编译输入。

---

## 附录 D：@函数标签重名与覆盖规则（定稿，2026-10-17，m2-symbols）

本附录回答「重名 @标签谁生效」，为 erbcc 符号表（`tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/Symbols.kt`）的依据，
并用 `tools/symbols_analysis.py` 对全量语料实测。**本节更正正文第 3 节与附录 C.9 的「先装载者生效」表述**（正文其他节未改动）。

### D.1 七问定稿

| # | 问题 | 定稿结论 | 源码证据 |
|---|---|---|---|
| 1 | 非事件函数重名：先/后装载者生效 | **后装载者生效**（last-wins）。逐文件装载结束时 `SortLabel` 对非事件标签执行 `noneventLabelDic[labelName] = label`，字典直接赋值 → 最后写入者留下。`SortLabels`（排序后取 `[0]`，即先装载者）**无任何调用点，是死代码**。排序键为 `FileIndex → LineNo → Index`，三者构成全序（`Index` 全局唯一），`List.Sort` 的稳定性与结论无关；该路径既已死，排序键对生效者无影响。 | `ErbLoader.cs:514-518`（逐文件调 `SortLabel`）、`LabelDictionary.cs:60-68`（收 `:65` 直接赋值）、`LabelDictionary.cs:154-241`（`SortLabels`，排序键 `FunctionLabelLine.cs:85-96`）、`ErbLoader.cs:568-575`（唯一的调用者 `setLabelsArg()` 无参版，全仓无调用方） |
| 2 | 事件函数能否重名 / 覆盖方向 / 优先级机制 | **允许重名，且不覆盖：全部注册、全部执行**。按 `#ONLY(0) → #PRI(1) → 普通(2) → #LATER(3)` 四桶依次调用，同桶内按装载序。`#ONLY` 命中即终止后续桶（`FinishEvent`）；`#SINGLE` 标签返回 1 时切到下一桶。事件函数**不进入** `noneventLabelDic`（本游戏 `CompatiCallEvent:NO`），故 `CALL EVENTTRAIN` 直接 CodeEE。 | `LabelDictionary.cs:70-105, 185-239`、`CalledFunction.cs:62-85, 228-257`、`ProcessState.cs:326-333`、`IdentifierDictionary.cs:72-89`（事件名集合）、`CalledFunction.cs:100-103` |
| 3 | 大小写 | `IgnoreCase=YES` → `Config.StrComper = StringComparer.OrdinalIgnoreCase`，`labelAtDic`/`noneventLabelDic`/`eventLabelDic` 全部用它构建 → @Foo 与 @FOO 是同一键、互相覆盖。本游戏 `emuera.config` 首行即 `IGNORE CASE:YES`。 | `Config.cs:33, 299-308`、`LabelDictionary.cs:12-28`、`ConfigData.cs:45`（默认 true）、游戏 `emuera.config:1` |
| 4 | 同文件内重复定义 | **不报错**，与跨文件同规则：非事件后者覆盖前者；事件全部入桶按行序执行。仅在 `WarnNormalFunctionOverloading` 开启时告警（warnLevel=1，不中断装载、不影响 `noError`）。本游戏该配置为 `YES`。 | `ErbLoader.cs:443-451`（告警但不置错）、`ConfigData.cs:94`、游戏 `emuera.config:DUPLICATED FUNCTIONS WARNING:YES` |
| 5 | 与系统语句/内建函数重名 | `AllowFunctionOverloading` 默认 **true**（本游戏 `YES`）→ 与保留字/系统方法重名仅 warnLevel=1（**允许**，标签照常注册）；为 false 时 warnLevel=2 → `InvalidLabelLine`，标签**不注册**。语句位置仍由指令表优先（行首标识符查 `instructionDic`）；用户 `#FUNCTION` 同名时在**表达式**位置覆盖内建方法，普通 `@label` 同名则回落到内建方法。 | `ConfigData.cs:89`、`Config.cs:344-352`、`IdentifierDictionary.cs:206-256`（`:210-243` 两分支）、`LogicalLineParser.cs:409-414`、`IdentifierDictionary.cs:548-586` |
| 6 | 玩家自制 ERB 与本体冲突 | **无任何"用户优先"特权**，胜负完全由装载序（路径排序）决定。注意 `getFiles` 是「同层文件 → 同层子目录（递归）」：放子目录的自制文件必然晚于根目录文件装载 → 覆盖本体；放根目录则按文件名 culture 序竞争（`zz_*.erb` 可稳定压过本体）。名字含 `#` 的目录**最先**装载（最容易被覆盖）。 | `ErbLoader.cs:214-264`、`Config.cs:654-706` |
| 7 | 全量实测 | 见 D.3：引擎层重名 **6 个名字 / 30 条定义，全部是事件函数**；非事件重名 **0**；无法解释 **0**。文本层「≈280 个重名」是**未建模 `[SKIPSTART]`** 的假象（283 个幽灵重名）。 | `tools/symbols-report/` |

### D.2 装载序（本游戏配置下的精确定义）

`emuera.config`：`SEARCH SUBFOLDERS:YES`、`SORT FILENAMES:YES`、`IGNORE CASE:YES`、`ALLOW OVERRIDING SYSTEM FUNCTIONS:YES`、`ALLOW CALL ON EVENT FUNCTIONS:NO`、`USELAZYLOADING:YES`。

1. ERH 先于全部 ERB（`Process.cs:309-318`）。ERH 只允许 `#` 行，**不产生 @标签**（`ErhLoader.cs:104-120`；实测 153 个 ERH 中 0 条 @标签）。
2. ERB：先名字含 `#` 的目录（本语料 0 个，且该 `GetDirectories` 调用未排序），再 `Config.getFiles` 的「同层文件排序 → 同层子目录排序 → 递归」前序 DFS（`ErbLoader.cs:214-264`、`Config.cs:678-704`）。
3. `FileIndex` = 装载序（1-based，`LabelDictionary.cs:303-322`），`Index` = 全局注册序。
4. 懒装载只改变「何时装载」，不改变「是否装载」；懒文件在运行期按调用补齐后同样走 `SortLabel`，因此**运行期也遵守 last-wins**，只是参与的集合随调用时序变化（见 D.7）。

### D.3 全量实测（`python tools/symbols_analysis.py --corpus <ERB>`）

| 指标 | 引擎层（原版真正注册） | 文本层（不排除禁用区，对账 280 口径） |
|---|---|---|
| @标签定义 | **110,066** | 113,708 |
| 去重名字 | **110,042** | 113,386 |
| 重名名字 | **6** | 289 |
| 同名次条定义（非首个） | 24（事件函数全部执行，此列**不代表遮蔽**） | 322 |

分类（引擎层 6 组）：

| 类别 | 计数 |
|---|---|
| 仅同文件重复 | 0 |
| 跨文件重复 | 6（其中 3 组同时含同文件多定义） |
| 事件函数重名 | **6** |
| 非事件函数重名 | **0** |
| 系统标签名（非事件） | 0 |
| 大小写变体折叠后重名 | 0 |
| 与系统语句名重名 | 0 |
| 无法解释的重名组 | **0** |

- 属性统计：`#FUNCTION/#FUNCTIONS` 定义 4,216 个；`#PRI` 6 个、`#LATER` 8 个；`#ONLY`、`#SINGLE` **本语料 0 个**（故事件桶实际只用 PRI/普通/LATER 三层）。
- 排序保真（ADR-006）：排序比较器冻结为 **ja-JP Collator**（`CollatorOption.JA_JP`，保留 `ORDINAL` 对照）。
  `erbcc symbols --corpus <ERB> --collator-check` 实测：3,818 文件、重名定义文件 20 个、两两相对次序翻转 **0 / 190 对**、
  定义集合翻转 **0**、事件执行序翻转 **0** → **PASS**。
- 双实现交叉验证：Python 建模与 Kotlin `SymbolTable`（`erbcc symbols`）在定义总数、唯一名、6 组的 `fileIndex`/行号上**完全一致**。
- **账面 280 的来源已定位**：289 个文本级重名中，283 个只存在于 `[SKIPSTART]`/未命中的 `[IF 宏]`/`[IF_DEBUG]` 禁用区内（例：`COMMON.ERB:199` 的 `@DRAW_INFORMATIONLINE` 位于 `[SKIPSTART]` 175-220 行之间，原版根本不注册）。**编译器若不复刻预处理，会凭空造出 283 个重定义并改变解析目标**。

### D.4 实例

引擎层全部 6 组（30 条定义，下表按**装载序**排列；实际执行序见下方注）：

| 标签 | 序 | 定义位置 | 属性 |
|---|---|---|---|
| `EVENTFIRST` | 1 | `SYSTEM.ERB:8` | |
| | 2 | `DLC/音乐补丁/音乐播放.ERB:81` | |
| `EVENTLOAD` | 1 | `SYSTEM.ERB:32` | |
| | 2 | `DLC/音乐补丁/音乐播放.ERB:84` | |
| | 3 | `fromEN/Addition/Add_Relation.ERB:1367` | |
| | 4-6 | `fromEN/NEW_UPDATE/EVENTLOAD.ERB:2 / :32 / :63` | #LATER ×3 |
| | 7 | `fromEN/NEW_UPDATE/UPDATE_VERSIONBASED/UPDATE_VERSIONBASED.ERB:91` | #LATER |
| | 8 | `イベント関連/妊娠関連/出産経過.ERB:260` | |
| | 9 | `ステータス表示関連/PRINT_STATE.ERB:387` | #LATER |
| `EVENTTURNEND` | 1 | `DLC/告白之神.ERB:264` | |
| | 2 | `イベント関連/EVENTTURNEND.ERB:1` | |
| `EVENTCOMEND` | 1 | `DLC/音乐补丁/音乐播放.ERB:78` | |
| | 2 | `fromEN/QOL/QoL_DetailedInfo.ERB:734` | #PRI |
| | 3 | `fromEN/QOL/QoL_Misc.ERB:1881` | #PRI |
| | 4 | `fromEN/QOL/QoL_Misc.ERB:1887` | #LATER |
| | 5 | `イベント関連/EVENTCOMEND.ERB:8` | |
| | 6 | `魔改内容/DokuToolLib/DOKU_BLACK_TECH.ERB:23` | |
| `EVENTTRAIN` | 1 | `fromEN/DAYEVENT.ERB:726` | #LATER |
| | 2 | `fromEN/Addition/Add_ItemMod.ERB:876` | |
| | 3 | `fromEN/Addition/Add_Misc.ERB:1096` | #PRI |
| | 4 | `fromEN/QOL/QoL_DetailedInfo.ERB:715` | #PRI |
| | 5 | `イベント関連/Add_InsectsForagingService.ERB:246` | |
| | 6 | `イベント関連/BEFORETRAIN.ERB:12` | |
| | 7 | `魔改内容/DokuToolLib/DOKU_DOKU_FREE_ACT_API.ERB:190` | |
| `EVENTCOM` | 1 | `fromEN/QOL/QoL_Com_Order.ERB:708` | #PRI |
| | 2 | `fromEN/QOL/QoL_Misc.ERB:1875` | #PRI |
| | 3 | `fromEN/QOL/QoL_Misc.ERB:510` | |
| | 4 | `魔改内容/DokuToolLib/DOKU_BLACK_TECH.ERB:2` | |

> 执行序按桶重排：`EVENTCOM` 实际序为 708(PRI) → 1875(PRI) → 510 → DOKU:2；`EVENTTRAIN` 为 1096(PRI) → 715(PRI) → 876 → 246 → 12 → 190 → 726(LATER)；`EVENTCOMEND` 为 734(PRI) → 1881(PRI) → 78 → 8 → 23 → 1887(LATER)；`EVENTLOAD` 为 32 → 84 → 1367 → 260 → 2 → 32 → 63 → 91 → 387（5 条 `#LATER`：EVENTLOAD.ERB 三处 + UPDATE_VERSIONBASED:91 + PRINT_STATE:387，全部排到桶 3）。

幽灵重名示例（文本层有、引擎层无；全部因所在区被预处理禁用）：

| 标签 | 生效定义 | 被忽略的「重名」 | 原因 |
|---|---|---|---|
| `DRAW_INFORMATIONLINE` | `魔改内容/PRINT系/HTML_PRINT_Components.ERB:248` | `COMMON.ERB:199` | 后者在 `[SKIPSTART]`(175-220) 内 |
| `PRINTBUTTON_EX` | `魔改内容/PRINT系/HTML_PRINT_Components.ERB:13` | `COMMON.ERB:1463` | 后者在 `[SKIPSTART]`(1457-1507) 内 |
| `MAXWIDTH` | `魔改内容/PRINT系/String_Layout_&_Format.ERB:11` | `COMMON.ERB:3768` | 后者在 `[SKIPSTART]`(3764-3783) 内 |
| `VERSION_UP` | `VERSION_UP.ERB:2` | `fromEN/NEW_UPDATE/UPDATE_VERSIONBASED/VERSION_UP.ERB:3` | 该 fromEN 文件整体在 `[SKIPSTART]`(1-75) 内 |
| `CUSTOM_NAME` | `NEWGAME/NEWGAME_CUSTOM.ERB:230` | `fromEN/NEW_UPDATE/NEWGAME_CUSTOM.ERB:205` | 该 fromEN 文件整体在 `[SKIPSTART]`(1-763) 内 |
| `HEX_TO_RGB` | `魔改内容/PRINT系/UI_Components_&_Tables.ERB:59` | `DLC/SEX_TATTOO/COLOR_CLAC.ERB:90` | 后者在 `[SKIPSTART]`(2-157) 内 |
| `重置素质` | `DLC/DEBUG.ERB:1502` | `SHOP関連/TEST.ERB:166` | 后者在 `[SKIPSTART]`(51-636) 内 |
| `PRINT_COLORBAR` | `魔改内容/PRINT系/UI_Components_&_Tables.ERB:959` | `ステータス表示関連/COLOR.ERB:104`（整文件 `[SKIPSTART]` 1-295）、`method_from_eratohoЯeverse/PANIMATION.ERB:102`（`[SKIPSTART]` 1-355） | 两处均被禁用 |

> 上表第 6、8 行曾写反（把被禁用的一侧当成了生效者），由 `SymbolsTest` 的语料抽样断言发现后更正；
> 现表逐行用 `parse_file` 的活动集核对过。`COLOR.ERB` 整文件被 `[SKIPSTART]` 包裹是一个容易误判的实例。

### D.5 对正文第 3 节 / 附录 C 的更正

1. **正文第 3 节「先装载者生效」→ 更正为「后装载者生效」**。原文把 `SortLabels`（`LabelDictionary.cs:166-178`）当成主路径，但该方法的唯一调用者 `ErbLoader.setLabelsArg()`（无参版，`ErbLoader.cs:568-575`）在反编译源中**没有任何调用点**（全仓 grep 仅定义与 `SortLabels` 定义本身）。实际生效路径是 `loadErb` 末尾的逐文件 `SortLabel`（`ErbLoader.cs:514-518`）。正文第 2 节最后一条「间接决定同名函数谁生效」的推论方向也随之反转。
2. **附录 C.9 引用「先装载者生效」的括注同样作废**；C.9 的「重名不等于装载错误、编译输入取全量」结论**不变**，且被本附录强化：全量编译集内非事件重名 = 0，不存在"重定义错误"。
3. 附录 C.8 的「267 个重名由死文件引入」与本附录的 283 个幽灵重名**部分同源**（禁用区 vs 磁盘死文件），二者已由 C.9 统一降级为历史推断，不再作为编译输入判据。
4. 正文第 3 节关于事件函数分桶与 `AllowFunctionOverloading` 的描述**正确，无需更正**。

### D.6 符号表 API（`tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/Symbols.kt`）

```kotlin
data class SymbolConfig(ignoreCase: Boolean = true, sortFilenames: Boolean = true,
                        debugMode: Boolean = false, macros: Set<String> = emptySet(),
                        collator: CollatorOption = CollatorOption.JA_JP)   // ADR-006；另有 ORDINAL
enum class CollatorOption { JA_JP, ORDINAL }

data class LabelDef(name, key, file, lineNo, fileIndex, defIndex,
                    isEvent, isSystem, isMethod, isOnly, isPri, isLater, isSingle,
                    argCount, localSize, localsSize) {
    val eventBucket: EventBucket   // ONLY(0)/PRI(1)/NORMAL(2)/LATER(3)
}
data class DuplicateGroup(key, defs) { val winner: LabelDef; val firstLoaded: LabelDef; val isEvent }

class SymbolTable(config) {
    fun addFile(relPath: String, labels: List<RawLabel>)   // 装载序递增调用
    fun freeze()
    fun keyOf(name: String): String                        // IgnoreCase 时 uppercase(ROOT)
    fun defsOf(name: String): List<LabelDef>               // 同名全部定义（装载序）
    fun resolve(name: String): LabelDef?                   // 非事件：最后装载者
    fun eventBuckets(name: String): List<List<LabelDef>>?  // 4 桶，null=不是事件
    fun eventCallOrder(name: String): List<LabelDef>       // 桶序展平 = 实际执行序
    fun duplicateGroups(): List<DuplicateGroup>
    fun stats(): SymbolStats
    val nameKeys: Set<String>                              // 全部归一键（与系统语句重名检查用）
    companion object {
        fun loadOrder(corpus: Path, config): List<Path>    // 装载序
        fun erhOrder(corpus: Path, config): List<Path>
        fun scanLabels(text: String, config): List<RawLabel>  // 预处理感知的标签扫描
        fun collectMacros(text: String): Set<String>
        fun build(corpus: Path, config): SymbolTable
        fun collatorCheck(corpus: Path, config): CollatorCheck   // JA_JP vs ORDINAL 对拍
    }
}
object Symbols { fun isEventLabelName(name); fun isSystemLabelName(name) }
```

CLI：`erbcc symbols --corpus <ERB> [--json <file>]` 打印统计与全部重名组（含执行序），可选导出 JSON。
**stmtparse 使用方式**：`CALL`/方法名解析用 `resolve()`（返回 null 即"未定义"）；`CALLEVENT`/事件调度用 `eventCallOrder()` 逐条执行；诊断用 `defsOf()`。

#### D.6.1 与变量层的接口约定（`functionKey` / `#DIM`）

符号表只做标签级解析，不解析变量；但 `#DIM` 私有作用域必须以标签为 key，两侧需对齐：

1. **`functionKey` 一律传 `LabelDef.key`**（`IgnoreCase` 时为 `uppercase`）。依据：原版私有变量字典
   `FunctionLabelLine.privateVar` 用 `Config.StrComper`（`FunctionLabelLine.cs:17`）构建，本游戏
   `IGNORE CASE:YES` → `OrdinalIgnoreCase`。符号表已把 `@Foo`/`@FOO` 折叠成同一个键，若变量层改用
   原始拼写做 key 就会分裂出两个作用域（`VariableStore.declareUserVar/enterFunction` 内部会
   `uppercase` 兜底，但调用方不应依赖该兜底）。
2. **重名标签只给"胜者"注册 `#DIM`**。原版在装载期为每个定义各自执行 `#DIM`，但写进各自的
   `FunctionLabelLine.privateVar`，互不合并；运行期只有 `noneventLabelDic` 里的胜者能被 `CALL` 到。
   变量层 `declareUserVar` 是"同 key 按名累积、同名第二次即报重定义"，因此把被遮蔽定义的 `#DIM`
   一并灌入会造出原版不存在的冲突。做法：`resolve(name)` 命中的那条才注册。
3. **`isPrivate` 由语句层按位置判定**：`#DIM` 出现在函数体内传 `isPrivate=true`，出现在函数外
   （ERH/ERB 顶层）传 `false`。`DYNAMIC/STATIC` 只能在函数内、`GLOBAL/SAVEDATA/CHARADATA` 只能在
   函数外，这一约束在 `DimParser` 内部按 `isPrivate` 强制，符号表层不重复校验。
4. 局部变量**尺寸**不走符号表：`func.LocalLength/ArgLength/LocalsLength/ArgsLength` 由语句层解析
   `#LOCALSIZE/#LOCALSSIZE/参数表` 后交给 `VariableStore.defineLocalLengths(funcKey, …)`。

本语料非事件重名 = 0（D.3），故第 2 条只在玩家追加同名 ERB 时才会被触达；
`fixtures/golden/GOLDEN_12_SYMBOLS.ERB` 的 `@G12_FUNC`×2 / `@G12_PLAIN`×3 均不带 `#DIM`，可直接复用。

#### D.6.2 下游字段：`argCount` / `localSize` / `localsSize`

| 字段 | 语义 | 源码 |
|---|---|---|
| `argCount` | 标签形参个数：`@F(A, B$)` → 2，含带默认值者；`@F()`/无括号 → 0。按**顶层逗号**计数，跳过字符串内逗号与嵌套括号。原版把 int/string 形参分别记入 `ArgLength`/`ArgsLength`，二者之和即本字段。 | `ErbLoader.cs:700-755` |
| `localSize` | `#LOCALSIZE n` 的 n，未声明 → 0；只接受正整数，`<=0` 或非整数按未声明处理（原版告警后忽略）。 | `LogicalLineParser.cs:218-262` |
| `localsSize` | `#LOCALSSIZE n` 的 n，规则同上。 | `LogicalLineParser.cs:263-273` |

三者随 `LabelDef` 走，重名时各自独立：非事件取 `resolve()` 命中的胜者那条，事件每组各条分别保留。
`erbcc symbols --json` 的每个定义都带这三项。实际抽样（全量语料）：
`FUNC_CASINO` → 3/1/1、`GET_MOB_ELEMENT_COLORNAME` → 1/1/1、`PRINT_COLORBAR` → 7/1/1。

> 断言已固化：`tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/SymbolsTest.kt`
> （`gradle :tools:erbcc:test`；全量语料段需 `ERATW_CORPUS=<游戏>/ERB`，缺失自动 skip）。

### D.7 残留风险 / 需运行验证

| 项 | 说明 | confidence |
|---|---|---|
| A. `SortLabels` 死代码 | 「无调用点」是**静态**证据（反编译源未裁剪整方法，但无法 100% 排除）。**对拍方法**：造两个文件分别定义 `#FUNCTION FOO()`（函数体 `RETURNF 1` / `RETURNF 2`），脚本里 `PRINTV FOO()` —— 原版输出 2 即证 last-wins。本语料非事件重名 = 0，无法用真实语料反证。 | 高（静态）／需运行验证 |
| B. 排序比较器（已决策，ADR-006） | 原版 `Array.Sort(string[])` 按当前 culture 比较（`Config.cs:681`）。移植侧冻结为 `java.text.Collator(Locale.JAPANESE)`（`SymbolConfig.collator=JA_JP`，可切 `ORDINAL`）。**近似性**：.NET `CompareInfo` 与 `java.text.Collator` 的排序权重表并非逐字节等价，极端文件名对（全角/半角混排、长共同前缀后接异体字）可能不同序；同一次构建内二者都是确定的全序，故只影响跨文件重名的胜者。自检（`--collator-check`）：本语料重名定义文件相对次序 **0/190 对翻转**、胜者与事件执行序 0 翻转（PASS）；新加中日文名文件时需重跑该自检。 | 高（本语料）／近似（一般情形） |
| C. 懒装载的装载时序 | 本游戏 `USELAZYLOADING:YES` 且 `lazyloading.dat`（2,154 文件）存在：运行期只有被调用到的懒文件才装载，其 `FileIndex` 由调用序决定 → 同一名字的胜者可能随玩法变化。**移植决策**：启动即全量装载（确定性上界），与"懒装载下首次调用前不参与事件"的原版行为存在可观测差异（如懒文件中的 `@EVENTTRAIN` 在原版要等该文件被加载后才开始每回合执行）。此为**运行期策略**问题，不影响编译期符号表。 | 高（机制）／需产品决策 |
| D. `[SKIPSTART]`/`[IF 宏]` 建模 | 本实现按 `ErbLoader.PPState`（`:25-181`）逐条对齐；`[IF DEBUG]`（41 处）因 `DEBUG` 未定义而全部禁用、`[IF NDEBUG]`（2 处）同样禁用。若原版以 DebugMode 启动，装载结果会不同（标签集合变化），移植版固定 `debugMode=false`。 | 高 |
| E. 与系统语句同名的标签 | 本语料 0 例，故 `AllowFunctionOverloading` 的两条分支在本语料上不可观测；实现按源码分支保留（warnLevel 1/2）。 | 高 |

## 附录 D.8：每函数 `#DIM`/`#DIMS` 收集（dimSpecs，2026-10-17，m3-fixes）

erbcc 的 `scanLabels` 在收集 `#LOCALSIZE`（LogicalLineParser.cs:218-262）之外，追加收集
紧跟函数标签的 `#DIM`/`#DIMS` 声明，供语句→字节码编译器获得**每个函数体的完整局部变量布局**。

### D.8.1 规则来源

| 规则 | spec 位置 |
|---|---|
| `#DIM`/`#DIMS` 行归属当前标签（logicalLine2 必须仍是标签行） | ErbLoader.cs:415-424 |
| `#DIM` → `UserDefinedVariableData.Create(wc, isString, isPrivate=true, pos)` → `label.AddPrivateVariable` | LogicalLineParser.cs:279-288 |
| 关键字循环 CONST/REF/DYNAMIC/STATIC/GLOBAL/SAVEDATA/CHARADATA（冲突/重复即 CodeEE） | UserDefinedVariableData.cs:60-260 |
| 第一个非关键字标识符 = 名字（关键字与名字间须有分隔符，`CONSTX` 是名字） | UserDefinedVariableData.cs:54-263 |
| 尺寸段：`ReduceIntegerTerm(...).Restructure(null)` 须为 `SingleLongTerm`（宏/ERH 常量在此展开，erbcc 不求值、保留原文） | UserDefinedVariableData.cs:300-343 |
| REF 变量每个顶层 `,` 恰贡献一个 0 尺寸 | UserDefinedVariableData.cs:309-319 |
| 初值 `=` 之后的原文（REF/多维/CHARADATA 禁止） | UserDefinedVariableData.cs:344-427 |
| 维度上限：CONST 1 维 / CHARADATA 2 维 / 一般 3 维 | UserDefinedVariableData.cs:437-449 |

### D.8.2 重名约定（D.6.1 的延伸）

- **同函数体重名**：先到者胜。`privateVar` 以 `Config.StrComper`（OrdinalIgnoreCase）为键
  （FunctionLabelLine.cs:17），`AddPrivateVariable` 对已存在的键返回 false
  （FunctionLabelLine.cs:98-108），后到者仅告警（VarNameAlreadyUsed，warnLevel=2）后丢弃。
- **事件组每条各自收集**：事件函数不做覆盖（LabelDictionary.cs:70-105），每条
  `LabelDef` 携带自己的 `dimSpecs`；跨文件非事件重名的胜者 = 后装载者（D.1 定稿），
  但 `dimSpecs` 挂在各自定义上，调用方按 `resolve()` / `eventBuckets()` 取用。

### D.8.3 API 扩展

```kotlin
enum class DimKeyword { CONST, REF, DYNAMIC, STATIC, GLOBAL, SAVEDATA, CHARADATA }
data class DimSpec(name: String, isStr: Boolean, dims: List<String>,
                   keywords: Set<DimKeyword>, initializer: String?)   // dims/initializer 均为原文
// RawLabel / LabelDef 追加 val dimSpecs: List<DimSpec> = emptyList()
// `erbcc symbols --json` 的每条 def 输出 dimSpecs 数组（isStr/dims/keywords/initializer）
```

收集器容错：关键字冲突/重复/缺名/维度越界等原版 CodeEE 的行**不收集**（返回 null），
与原版「该行无效、变量不注册」一致；空尺寸段（`#DIM A,`）原版报错
（HasNotExpressionAfterComma），一般变量侧宽松跳过空段，REF 侧按逗号数计维。

**D.8 追加（2026-10-18，A7：宏展开体 + ERH `#DIM CONST` 收集）**

- `SymbolConfig.macros` 从 `Set<String>`（仅宏名）升级为 **`Map<String, String>`：宏名（大写归一）→
  展开体原文**（erbcc 仍**不做宏求值**，纪律不变）。证据：`#DEFINE` 仅 ERH 处理
  （ErhLoader.cs:109-112，ErbLoader 无 DEFINE 分支，本语料 ERB 内 `#DEFINE` 出现 0 处）；
  展开体 = 名后剩余 token 原样入 `DefineMacro.Statement`（ErhLoader.cs:213-218，DefineMacro.cs:5-31）；
  无替换式 `#DEFINE FOO` 存空体（ErhLoader.cs:167-171 `IsNull`，供 `[IF FOO]`）；
  重名宏**先到者胜**——`CheckUserMacroName` 对已有 UserMacro 冲突 warnLevel=2，
  `analyzeSharpDefine` 随即 return 不执行 `AddMacro`（ErhLoader.cs:155-164 +
  IdentifierDictionary.cs:324-327, 401-406；注意与「非事件标签后到者胜」相反）；
  参数化宏 `#DEFINE F(A) ...` 本版本**禁止声明**（解析完仍 `throw CodeEE`
  「関数型マクロは宣言できません」，ErhLoader.cs:241-244 + Lang.cs:2382；展开侧
  expandFunctionlikeMacro 为死路残留）→ 不收集（本语料 0 处）。
- ERH 读取按 `EraStreamReader.ReadEnabledLine` 口径：空/纯注释行跳过；独占一行的 `{`…`}`
  续行块以单空格连接成一条逻辑行（EraStreamReader.cs:100-128, 136-184 + Config.cs:386 +
  ConfigData.cs:119，本游戏 emuera.config:72 `" "`）——多行 `#DEFINE`/`#DIM CONST` 依此还原。
- `SymbolTable.build` 同步收集 ERH `#DIM CONST` 声明**原文**到 `SymbolTable.erhDimConsts`
  （`List<DimSpec>`，CONST 必带初值否则原版 CodeEE 不收，UserDefinedVariableData.cs:294-297,
  344-350；`#DIMS CONST` 字符串 CONST 亦收集但求值方只纳整型）。同名 CONST 先到者胜
  （nameDic.Add/CheckUserVarName 拒重名，IdentifierDictionary.cs:283-285, 394-399；本语料 0 例）。
- 求值全部在编译器桥 `com.erbcc.bridge.sizeConsts(macros, dimConsts)` → `Map<String, Long>`：
  宏体/初值原文喂 core-vm `DimSizeEvaluator`（十进制整数、标识符、`+ - * / %`、括号），
  **定点迭代**消化 `宏→宏`/`宏→常量` 链与相互引用——等价原版 ERH 装载尾部
  `analyzeSharpDimLines` 的 IdentifierNotFoundCodeEE 重试队列（ErhLoader.cs:249-340）；
  `#DIM CONST` 多元素数组取**首个顶层逗号段**（裸标识符 = 元素 0，
  VariableTerm.cs:337-340 CanRestructure→GetValue 默认下标）。不可求值的声明
  维持 D.8「不收集」口径（`toLocalDim` → null，不报错）。
  `buildFuncDims` 的 `consts` 由空表升级为 `sizeConsts(SymbolTable)` 直喂，
  `TODO(verify)`（宏无体/常量缺收）消解。
- 仍不可求值形态（DimSizeEvaluator 无 hex `0x…`/`名@下标`/VARSIZE/COLOR_FROMNAME 函数折叠，
  原版 ReduceIntegerTerm 支持）→ 保留 TODO(verify)。全语料实测（2026-10-18）：
  ERH 宏 22 个（展开体齐）、`#DIM CONST` 声明 6117 条、尺寸可解常量 5770 个；
  函数体 `#DIM` 因尺寸不可求值被丢弃的声明数 **收集前 112 → 收集后 2**，
  剩余 2 条均为 `#DIM X [VARSIZE("RELATION")]`（fromEN/QOL/QoL_ShowRelation.ERB:1，
  函数形态维度，vars 层 DimParseConfig 同样不可静态解，m3-fixes B2 结论一致）。
- API 增量：`SymbolConfig.macros: Map<String,String>`；
  `data class ErhSymbols(macros: Map<String,String>, dimConsts: List<DimSpec>)`；
  `SymbolTable.collectErhSymbols(text): ErhSymbols`、`SymbolTable.loadErhSymbols(corpus, config)`、
  属性 `SymbolTable.erhDimConsts`；`collectMacros(text)` 保留为取键兼容入口。
  桥侧 `sizeConsts(table)` / `sizeConsts(macros, dimConsts)` /
  `DimSpec.droppedByUnresolvableDim(consts)`（丢弃数诊断口径）；
  `macroConsts` 委托 `sizeConsts`（纯整数为其子集）。
  CLI：`erbcc symbols --corpus <ERB>` 打印宏/常量收集数与丢弃声明前后对比。
