# corpus-bugs —— 随包语料内容缺陷台账

> 源头：上报端点去重簇里判「语料内容侧」的缺陷。引擎语义与文案判定归 `verify-notes.md` 与
> `error-texts.yaml`；本表只记**随包语料数据本体**（ERB/ERH/CSV 内容错误、相互不一致）的缺陷与处置。
> 铁律：语料根一个字节不改（AGENTS §1）；官方 PC 同形的引擎一律不动（ADR-005）；
> 内容补丁只走 §3 的编译期通道，随 git 分发，两机产物一致。

## 1. 在册缺陷

| id | 簇（端点签名/读数） | 语料落点 | 病灶 | 判定 | 处置 | 证据 |
|---|---|---|---|---|---|---|
| CB-001 | `K51_EGG_SPECIAL_EVENT函数: 参数过多` · 6 起/4 机 · 全 0.2.39 | `ERB/口上・メッセージ関連/個人口上/051 Nitori [にとり]/Nitori/` | `M_KOJO_K51_special_events.ERB:806` 声明**零参**；`M_KOJO_K51_everyday_commmands.ERB:24`（`@M_KOJO_EGG_MESSAGE_COM_K51_300(Counter)`）`:64` **一参调用** `CALL K51_EGG_SPECIAL_EVENT(Counter)` | 内容缺陷（官方 PC 同炸：`CalledFunction.cs:138-141` `ConvertArg` 在 `topLabel.Arg.Length < srcArgs.Count` 时置 `Lang.Error.TooManyFuncArgs`（zhs reference:901 `{0}函数: 参数过多` / 根表:682 `函数{0}: 参数过多`，两表词序相反）⇒ 引擎判参正确、错在语料；4.980 语料无此函数，仅 4.981 引入，`Nitori/` 目录 4.980 实测不存在） | **已修（补丁 CB-001 随 wave/report-cb001）**：修法裁定 = 调用点去参（兄弟调用全带 Counter、唯此声明零参，系笔误；补参=替官方写新逻辑，越权）→ `devtools/corpus-patches/CB-001.patch` + `index.yaml`（病灶 sha `3482ab97…`，修后 sha `5a3a8c7b…`）；§3 编译期通道随本波落地（compileCorpus 执行前 staging 硬链接副本套补丁；4.980 无同名文件=缺席即跳过留痕）；玩家获得修复=重新出包（§3.5，节奏主人掌握） | raw2 `2026-11-05__1793845051968-*.txt`（调用栈 `ProcessRuntime.requireArgCount` ← `M_KOJO_EGG_MESSAGE_COM_K51_300`）；对照调用 `conversations.ERB:3269` 零参不炸 |
| CB-002 | `变量 ITEMNAME 的第 1 维下标 1000 越界（长度 1000）` · 2 起/2 机 · 0.2.39 | `魔改内容/qol/qol_item.ERB:113`（`FOR ITEM_ID, 1, 1200`）→ `:402`（`SIF ITEMNAME:ARG == ""`）；标题期 `stepTitle` 经 `strCompare` | erbc 按 4.981 语料编译、随包 CSV/resources 取自 4.980 游戏根（**混装包**）：4.981 的 QOL 循环按 1200 槽读 `ITEMNAME:ARG`，而 4.980 的 `CSV/VariableSize.csv:74` 只有 `ITEMNAME,1000` | **出包侧数据混装（非官方同形）**：同源 4.981 数据（`CSV/VariableSize.csv:74` = `ITEMNAME,1200`）下脚本 1..1200 与表长一致，PC 与移植侧均不越界；`VariableData.cs:30` 裸下标 / `VariableTerm.cs:70-76` rethrow 只说明「越界必崩」，不构成本簇的同形证据 | **已修（出包侧）**：`devtools/pack_release.py` 的 `fallback_game_root`（:155-162）+ `toolchain_env`（:259-277）让 `ERATW_GAMEDIR` 与 `ERATW_CORPUS` 恒同源，`data_source_alignment`（:427-465）不同源即 `return 2`（:1208-1216；dry-run 仅告警、`--skip-assets` 放行）；已装机靠 `GameAssetInstaller` 字节数比对换数据。语料与引擎均不动 | raw-500 `2026-11-03__1793712013906-*.txt`（Xiaomi 23127PN0CC，`BeginFlow.stepTitle`）；翻案见 `verify-notes.md §29:2416-2423`，D6 复核（2026-11-07）逐条回主源确认两版 `VariableSize.csv:74` = 1000 / 1200 |
| CB-003 | `变量 SEMEN_MATRIX …（未找到 ERD 表 SEMEN_MATRIX.csv）` · 1 起 · 0.2.39 | `魔改内容/SEMEN_MATRIX.ERH` + `SEMEN_MATRIX@2.ERD`（4.981 随包两件；4.980 两件皆无；verify-notes §24 在册） | **证据不足**：报错串「未找到 ERD 表」在官方 `reference/` 全库**零命中**；官方缺表/维度不符走 `ConstantData.cs:1193/:1199/:1201` 的 `CanNotSpecifiedByString` / `CannotIndexSpecifiedByString`。随包只有 `@2.ERD`（2 维表）、无 `.csv`/`@1` ⇒ 报错若指向裸名，更像「脚本按第 1 维做字符串键」或「玩家装内缺 `@2.ERD`」二者之一 | 证据不足（原因与文案双缺证）——**不是**已定案的玩家缺件 | 挂 raw2 正文 + 该玩家装内 ERD 清单 + 脚本访问维度（第 1 维 vs 第 2 维）核查后再判；引擎/语料均不动 | verify-notes.md §24 表；raw2 无该正文（端点 stats 簇）；D6 复核（2026-11-07）：`ConstantData.cs:1193/1199/1201` 实测、报错串全库零命中 |
| CB-004 | **不可达（`[SKIPSTART]` 禁用区）· 2026-11-07 复核撤销补丁候选**。附带仍有效的一条：官方屏上本应出 `GETBIT函数: 第2个参数(64)在从0到63的范围外`（reference:917 模板），移植侧即便走到也出 `函数 GETBIT：第 2 个参数 64 越界（0-63）`（自造第三形，见 `error-texts.yaml` 附三 §一.6)）——但这段**永不编译**，故不是线上现象 | `ERB/fromEN/_TR Lib.ERB:1406`（`IF GETBIT(STAIN:ARG:2,64)`，4.980/4.981 **同线**）；该行落在真配对 `[SKIPSTART]` `:1186` – `[SKIPEND]` `:2296` 内，`:1405 ;[SKIPSTART]` / `:1430 ;[SKIPEND]` 是**注释**（不是指令，见证据列） | 装载期即被丢弃，不生成逻辑行、不进编译 ⇒ **无屏上现象**；`@TINKO_TR`（声明 `:1394`）同样不注册 | **死代码，非内容缺陷、非移植偏差**。若哪天语料去掉这层 skip，官方 PC 与移植会同崩（`FunctionMethodCreator.cs:4783-4786`，`bit < 0 \|\| bit > 63` 即 throw CodeEE），届时才需要先核查 64 的语义（本段其余支用的是 1/4/8，64 疑为遗留位；全语料 `GETBIT(...,64)` 仅此一处） | **撤销 §3 补丁候选**：引擎与语料均不动 | 指令判定 `ErbLoader.cs:387-406`（首个非空白字符须是 `[` 且 `Next != '['`）+ `EraStreamReader.cs:96-129`（只跳空白、不剥 `;`）⇒ 带 `;` 的两行是普通注释，遮不住外层真配对。移植侧 `LogicalLineReader.kt:257` 同判。禁用区内代码不编译、标签不注册（`specs/loader.md` 附录）。**实证**：4.981 单文件 compat 编译产物 `program.meta` `labels 5`，恰等于按区间剔除后存活的 5 个标签（`:145` `:1041` `:2298` `:2310` `:4517` 五行），`TINKO_TR` 缺席；调用点 `:269/:909/:1537` 与 `Com_Stuff.ERB:307`（4.981 配对 21-185 / 202-1172）同样全在禁用区。另：线上 `GETBIT(x,150)` 簇与本行**不同源**（150 是 `SHOP.ERB:188` 的按钮值，`BONUS2.ERB` 126 处 GETBIT 字面最大 16、动态支 `FOR LOCAL,1,11` 上界 11 ⇒ 出厂链产不出 150），见 BOARD OBS2-L3 ③ |
| CB-005 | **语料表缺键（非端点簇）· 2026-11-08 立项（裁定③）**：4.981 出包根 `CSV/_Rename.csv` 没有料理解名键，第三方口上的 4 行 `CASE [[旧译名]]` 与所属函数一起降级 | `CSV/_Rename.csv`（4.981 出包根）；病灶消费者 `ERB/口上・メッセージ関連/個人口上/016 Remilia [レミリア]/蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日常系指令.ERB:6311/6365/6419/6473`（0 基） | 4.981 的改名表删掉了 4.980 的料理解名块（4.980 `CSV/_Rename.csv:1649-1652` 有 `351,油炸跳蛋` / `352,电动按摩风跳蛋湯引` / `353,夹子用跳蛋握手` / `354,飛機杯剪裁跳蛋水煮`）；料理本体仍在 4.981（`AutoConst_DISH.ERH:531-544`：351 揚げローター / 352 Eマッサージャ風ローターの湯引き / 353 クリキャップ用ローターの握り / 354 オナホール仕立てのローター煮込み），只是中文译名换新、旧名查 `[[…]]` 的键没登记 ⇒ 读行层查不到键、`[[` 漏到词法器 | **随包数据缺陷（出包根，非引擎）**：编号必须按 4.981 自身 DISH 表核（与 4.980 同编号同物、只换译名）；修法只补「旧译名 → 编号」4 键，不改第三方 ERB、不改 4.981 常量表 | **已修（补丁 CB-005）**：`devtools/corpus-patches/CB-005.patch` + `index.yaml`（`root: game` 新口径；病灶 sha `97fc9f66…` → 修后 `1a5e48d9…`；4.980 原表 sha 入 `skip-sha256`，别的 4.980 版式仍判红）；编译 staging 与出包资产 staging 同源应用，`applied_corpus_patches` 列随包留痕；严格档 13→8、`degraded-slices 8` | 4.980 `CSV/_Rename.csv:1649-1652`；4.981 `ERB/Headers/AutoConst_DISH.ERH:531-544`；消费者 `M_KOJO_K16_5_日常系指令.ERB:6312/6366/6420/6474`（1 基）。C# 锚：`EraStreamReader.cs:106-119`（`[[原名]]` → 替换名）+ `LexicalAnalyzer.cs:811-813`（键缺席时 `[[` 漏到词法器） |

## 2. 什么进这张表（判定口径）

1. **随包语料数据本体**的缺陷（ERB/ERH/CSV 内容错误、声明与调用不一致、表尺寸与脚本假设不符）。
2. 引擎判定/自造串/门控问题 → `verify-notes.md`；测试装置假绿 → CONTRACT-REPORT-DEDUP3 台账；玩家私装缺件 → 记「缺件候选」留证，不立项。
3. 每行必须带：端点签名或正文样张路径、语料 `文件:行号`、判官方同形时的 C# 证据。
4. 状态机：`待判定 → （主人拍板）待修 / 官方同形不修 / 玩家缺件 / 等上游 / 出包侧缺陷` → `已修（补丁 id）` / `已修（出包侧）` / `已判结`。
   「出包侧缺陷」= 语料与引擎各自都对、混装才炸（CB-002）；判它必须给出**同源数据下不越界**的正证，
   不能只引「越界必崩」的裸下标锚（那只说明后果，不说明本案同形）。
5. 「官方同形不修」是**行为判词**，必须带 C# `文件:行号` 指到原版同一条路径的同一后果；
   台账文字、既有判例、报错串相似都不算证据（D6 复核 2026-11-07：K51 与 CB-002 两处旧判词即因此翻案）。
   `error-texts.yaml` 的「不改-同形」是**文案判词**，两类标签不可混用。

## 3. 内容补丁的多主机分发机制（修包唯一通道）

语料根不进 git、两机各持只读副本 ⇒ 修复必须是**仓库内资产、编译期应用**：

1. **补丁资产**：`devtools/corpus-patches/<id>.patch`（统一 diff）+ `devtools/corpus-patches/index.yaml`
   （id、目标文件、**目标文件 sha256**、适用语料版本、病灶/修法摘要、状态）。
   `root: game`（可选，缺省 `corpus`）把目标解析根从 `<游戏根>/ERB` 换到游戏根，用于
   `CSV/_Rename.csv` 这类与 ERB 平级、但同样随包的数据；`skip-sha256`（可选）登记
   「已知无需补丁的其它版本」（如 4.980 根已有同一批键），其余未知 sha 仍判红。
2. **应用点**：`:tools:erbcc:compileCorpus`（及一切喂 erbcc 的流程）编译前把补丁应用到**临时副本**再喂编译器；
   `root: game` 的目标**同时**在 `:app:stageGameAssets` 落 assets 时替换（硬链接只读，删链接后写修后字节），
   否则会出现「erbc 修了、随包 CSV 没修」的半截修复。
   应用失败（目标 sha 不符 / diff 上下文不匹配）即**判红**，不许静默跳过——防上游语料换版漂移。
3. **留痕**：编译日志与包 manifest 记录已应用补丁 id + 目标 sha（对齐 degraded-slices 的留痕先例）；
   `root: game` 的留痕路径相对游戏根（如 `CSV/_Rename.csv`），出包面 `applied_corpus_patches` 列同源回读。
4. **两机同步**：补丁随 git pull 即达，与语料物理位置无关；Haruki/HOMEJIA 各自编译产物一致。
5. **玩家获得修复 = 重新出包**（erbc 随包）；不出包则线上无感——出包节奏主人掌握（P0 行）。

（机制本体在首个补丁落地时实现，先于补丁合入；§1 中任一「待修」行获批即开工。）

## 4 降级切片的治理裁定（ENGFIX3 `eng2-deg`，2026-11-08）

**结论：THROW 空体桩 + `--allow-degraded-corpus` 不构成缺陷掩盖**，但只能在 §4.2 四条边界内使用；
越界条目按「掩盖」处理（判据见 `.local/引擎缺陷掩盖审查-20261106.md` §1）。

### 4.1 为什么不是掩盖：语义与原版同形 + 硬闸在册

1. **原版同形**：Emuera 装载期函数体解析失败时**不删标签**，而是给函数打 `ErrMes`——
   `ErbLoader.cs:995-1010`（`ParseFunctionWithCatch` 捕获 → `label.ErrMes = Lang.Error.CalledFailedFunc.Text`，
   继续装载其余函数）；运行期走到该函数即抛：`CalledFunction.cs:133`（`errMes = TopLabel.ErrMes`）→
   `Process.cs:1017-1035`（`throw new CodeEE(currentLine.ErrMes)`）。调用点也会被静态标记：
   `FunctionIdentifier.cs:3544-3548`（`TopLabel.IsError ⇒ func.IsError = true; func.ErrMes = …`）。
   原版文案 `Lang.cs:2450`「ロード時に解析に失敗した関数が呼び出されました」。
   移植侧「入口 THROW 桩」是同一形态；strict 档（拒绝落盘）反而比原版更严，属本项目的刻意加严。
2. **不静默**：清单外失败判红、清单项未命中判红（`ParseFailureToleranceTest`）、
   `degraded-slices` 必须等于清单条数、逐条 `degraded` 文案进 manifest、出包需显式
   `--allow-degraded-corpus`、`applied_corpus_patches` 独立列随包自证（D8/D8b）。
   与「改白名单让错误消失」的掩盖手法相反：这里每一条都必须点名、留痕、随包可查。
3. **不是唯一出路**：数据层能修的先修数据（§3）。CB-005 把 4 条 `CASE [[…]]` 从清单里消灭（13→8），
   是「降级 → 数据补丁」的正确退出路径判例。

### 4.2 使用纪律（四条硬边界，违反即按掩盖打回）

1. **只收第三方**：清单只登记第三方口上的装载期失败；本仓自己的 ERB 编不过必须修源 + 回 strict 档。
2. **数据层可修的不许进清单**：缺键 / 缺表 / 版本差异走 §3 语料补丁（CB-005 判例）；
   修复落地后**同步删除**对应清单条目——「修了还留条」等于让根因继续以合法形态滞留。
3. **每条三件套**：C# 或语料锚 + 「为什么不能改上游」+ 「可达性影响」。可达且调用即抛的函数，
   必须写明玩家后果（进哪个场景会看到哪条错误），不许用「反正没人调」收尾。
4. **清单随 main 走**：禁止跨机本地重登（B3 历史：本地重登 13 条导致「发出的包 ≠ main 的状态」）。

### 4.3 剩余 8 条的处分（2026-11-08）

- **7 条日文字形 + `_COM_K16_440` 标记**：第三方口上按另一套译名写 `CASE 桜花道的鳥居 …`，
  4.980/4.981 两版 ERH 都只定义简体名（`DIM.ERH:67/77/96/158`、`PLACE_ERH.ERH`）⇒
  原版 PC 在**同语料**下同样会把这个函数标 `ErrMes`。该函数经基座的动态调用链
  `TRYCALLFORM M_KOJO%KOJO_NAME%_MESSAGE_COM_K{NO:TARGET}_{…}`（`DLC/实用设置补丁/实用设置.ERB:406/408/437/478`；
  `{…}` 取到 440 时命中本函数）调用，桩被触发时与原版同形地抛错，不是静默走错分支。**默认保留**：这是对原版行为的忠实复刻。
  若要「更好」，唯一合法路线是给 4.981 头文件补别名常量（CB-006 形态）——那会引入官方
  没有的内容，**属主人裁定项**，未经裁定不得做（`eng2-pack`/`eng2-deg` 均未实施）。
- **文案债（已结，2026-11-08）**：桩文案原为自造串「函数「@X」在装载时出错，无法使用」；
  现改取册内官方原文 `Lang.Error.CalledFailedFunc`（`Lang.cs:2450` / zhs 表 :567 / eng 表 :570，
  0 占位符），经 `LangKey.CalledFailedFunc.renderZhs()` 内嵌进降级桩
  （`tools/erbcc/.../CorpusCompile.kt` 的 `degradedBody`）。
  `specs/error-texts.yaml` 与 `LangKey` 已双向对账（`ErrorTextRenderTest` / `LangKeySourceTableTest`），
  `ParseFailureToleranceTest` 钉住「语句池里有官方串、旧自造串已退役」。
  函数名信息不再进屏（原版也不给），定位靠错误屏的位置信息。
- **可达性登记**：`@M_KOJO_色情特化_MESSAGE_COM_K16_440` 是 Remilia 的指令口上函数；
  玩家触发对应指令时按原版同形报错（错误屏），不会静默继续。当前包（0.2.43+19）即此状态。
