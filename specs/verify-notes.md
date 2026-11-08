# TODO(verify) 消解记录

**语义考古来源**：Emuera 1.824 官方源码
- 主证据源：`https://github.com/0x00000FF/Emuera`（commit `301ce4a4a4d0b0b20a14428a4680cf3dbd3559d8`，2019-05-23，"PPState Refactor"；`AssemblyInfo.cs:34-35` = `AssemblyVersion("1.824.*")` / `AssemblyFileVersion("1.824.0.0")`，与我们反编译目标版本号一致）
- 交叉验证源 1：`https://github.com/just-a-pony/emuera`（独立镜像，路径结构不同，关键代码一致）
- 交叉验证源 2：`https://github.com/xerysherry/uEmuera`（基于 emuera1824v7 的 Unity 移植，`Assets/Scripts/Emuera/GameData/Expression/OperatorMethod.cs` 关键代码一致）

**三源一致性**：下方所有"官方原文"均已在 ≥2 个独立仓库比对一致；第 1 项经三源验证。

---

## 1. 字符串 `>=` / `<=`（4.981 已修；移植曾误按 1.824 复刻）

- **结论：4.981 判据正确（`>=` = `c >= 0`、`<=` = `c <= 0`）；移植早期按 1.824 的 `c < 0` 复刻成了反向/相等泄漏，2026-11-08 MODERN-T5 校准回 4.981。** confidence: **高**（两棵反编译树分别读源码）
- **4.981 证据**（`Emuera/Runtime/Script/Statements/Expression/OperatorMethod.cs`，本仓 `reference/emuera-skia-4.981/`）：

```csharp
// GreaterEqualStrStr :537-551
int c = string.Compare(arguments[0].GetStrValue(exm), arguments[1].GetStrValue(exm), Config.Config.SCExpression);
if (c >= 0)          // :547 —— 正确
    return 1L;
return 0L;

// LessEqualStrStr :568-582
int c = string.Compare(arguments[0].GetStrValue(exm), arguments[1].GetStrValue(exm), Config.Config.SCExpression);
if (c <= 0)          // :578 —— 正确
    return 1L;
return 0L;
```

  即 4.981 的 `s1 >= s2` 在 `s1 >= s2` 时为真、`s1 <= s2` 在 `s1 <= s2` 时为真 —— 序数比较，正常。
- **1.824 旧锚（已作废）**：`Emuera/GameData/Expression/OperatorMethod.cs:477-491`（0x00000FF/Emuera@301ce4a）的 `GreaterEqualStrStr` 写 `if (c < 0)`（`LessEqualStrStr` 同为 `c < 0`）—— 那是 **1.824** 的官方 bug。移植早期误把 1.824 当考古对象，把它俩复刻成反向/相等泄漏（`Expr.kt` `strCompare` + `Vm.kt` `_STK_SGE`/`_STK_SLE` 两条通道）。
- **比较基准**：`Config.Config.SCExpression` 是 `const StringComparison.Ordinal`（`Config.cs:523`）⇒ 恒序数比较；移植侧 Kotlin `String.compareTo` 同口径，无需改基准。`CaseExpression.cs:64-65` 的 TO 区间判定亦走该常量（移植 `strCaseRange` 用 `!(a > b)` 表达 `<=`，与运算符解耦、行为不变）。
- **对策：对齐 4.981**（MODERN-T5）。语料对字符串 `>=`/`<=` 零命中（IF/SIF 条件、字符串 CASE TO 均未使用）⇒ 现存游戏产物逐字节不变。

---

## 2. 词法器文本表缺 `/`、`%`、`==`

- **结论：官方词法器对单字符/双字符运算符走 switch-case 直构 token，不经字符串字典反查；`==` 由 `'='`+next`'='` 两字符扫描组合产生。** confidence: **高**
- **证据 A**（`Emuera/Sub/LexicalAnalyzer.cs:521-546`，0x00000FF）：

```csharp
case '*':
    return OperatorCode.Mult;
case '/':
    return OperatorCode.Div;
case '%':
    return OperatorCode.Mod;
case '=':
    if (next == '=')
    {
        st.ShiftNext();
        return OperatorCode.Equal;
    }
    if (allowAssignment)
        return OperatorCode.Assignment;
    throw new CodeEE("予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)");
```

- **证据 B**：`OperatorCode.cs` 中的 `opDictionary`（`:61-88`，含 `"/"→Div`、`"%"→Mod`、`"=="→Equal`）**只被 `ToOperatorString(OperatorCode)` 消费**（`OperatorCode.cs:92-103`，用于错误信息回显），词法路径不使用它。grep 全仓库无其他消费方。
- **对策**：我们的实现若同样 switch 直构，与官方一致；文本表缺这三项不是缺陷。

---

## 3. `Program.DebugMode` 默认值

- **结论：默认 `false`；仅当命令行首参数为 `-DEBUG`（大小写不敏感）时置 `true`。** confidence: **高**
- **证据**（`Emuera/Program.cs`）：
  - `:192`：`public static bool debugMode = false;`（属性 `DebugMode` 在 `:193` 只读转发）
  - `:63-68`：

```csharp
int argsStart = 0;
if ((args.Length > 0) && (args[0].Equals("-DEBUG", StringComparison.CurrentCultureIgnoreCase)))
{
    argsStart = 1;//デバッグモードかつ解析モード時に最初の1っこ(-DEBUG)を飛ばす
    debugMode = true;
}
```

  - `:45`：`#if DEBUG` 构建下也有 `//debugMode = true;`（被注释，DEBUG 构建不自动开启）
- **对 ";#;" 的影响**（`Emuera/Sub/LexicalAnalyzer.cs:748-751`，SkipWhiteSpace 内）：

```csharp
case ';':
    if (st.CurrentEqualTo(";#;") && Program.DebugMode)
    {
        st.Jump(3);
```

  即 `;#;`（跳到文件尾的哨兵）**仅在 DebugMode 下生效**。
- **对策**：按官方默认 false + `-DEBUG` 开关复刻。

---

## 4. 编码两条路：`Runtime.Utils.DetectEncoding`（读脚本/数据） vs `Config.Encode`（写侧/调试侧）

> **2026-10-24 重写（CMP-76）。** 旧版本节断言「官方 1.824 没有任何编码探测逻辑、读文件编码固定 Shift-JIS、我们代码里的 DetectEncoding 是"画蛇添足版"定制或移植层自创、官方无对应物」——**与 reference 相反**。旧引证在本 reference 上**整组不成立**：
> - `Runtime.Config/Config.cs:17-19` 实为 `Encode`/`SaveEncode` = `EncodingHandler.UTF8BOMEncoding`（**不是** SHIFT-JIS）；
> - `Sub/EraDataStream.cs` 在本 reference **不存在**（`reference/emuera-cs/` 下无此文件）；
> - `Sub/EraStreamReader.cs:42` 是 `return Open(path, Path.GetFileName(path));`，`DetectEncoding` 在 `:52`；
> - `Content/AppContents.cs:91` 是 `CreateSpriteG`，`DetectEncoding` 在 `UI.Game.Image/AppContents.cs:141`；
> - `Forms/DebugDialog.cs:270` 是 `閉じるToolStripMenuItem_Click`，`Config.Encode` 在 `:291/:309/:338`；
> - `Config.cs:112-114` 的 `SystemSaveInUTF8` 在本 reference **已不存在**（`grep SystemSaveInUTF8` 0 命中）。
>
> 旧引证出自另一个版本的 Emuera 源码，不是本仓 reference。**以下结论只以 `reference/emuera-cs/` 为准**（路径相对该目录）。

### 4.0 版本同一性（先钉死，否则「官方有没有」无从谈起）

- `Properties/AssemblyInfo.cs:11` `AssemblyFileVersion("1.824.0.0")`、`:12` `AssemblyInformationalVersion("1.824+v22+EMv18+EEv53 Lazyloadingv3.0 NAudio x64 NAudio")`、`:17` `AssemblyVersion("1.824.0.0")`；
- 引擎自报版本：`GameData.Variable/VariableData.cs:1127` `return "1.824.0.0";`（`EMUERA_VERSIONToken.GetStrValue`）。
⇒ 这棵 reference 就是 1.824；它**有**编码探测逻辑。

> 本节**不以 GitHub 三源为据**（本文开头那三源比对的是另一版本的源码布局）：本节行号全部在
> `reference/emuera-cs/` 上逐个 `grep`/`read` 复算过；旧 §4 的三源式引证在本 reference 上不成立（见上）。

### 4.1 新路：`EncodingHandler.DetectEncoding` —— 脚本/数据读入的唯一主路

**结论：官方 1.824 有编码探测逻辑。ERB / ERH / CSV / ERD / ALS / `content/` 下 CSV / `_Rename.csv` / 报错回显源码 / `LOADTEXT` / `HOTKEY.ERB` / `macro.txt` / `emuera.config` 的读入都走它（例外见本节末尾「边界」）；判据是「UTF-8 BOM → 严格 UTF-8 → CP932」，严格解码失败才回落，不是启发式打分。**

证据（探测本体）：
- `Runtime.Utils/EncodingHandler.cs:15-41`（路径版）：
  - `:19-22` 打开文件只读前 3 字节；
  - `:23-25` 命中 `EF BB BF` ⇒ 返回 `UTF8BOMEncoding`；
  - `:27-32` 用 `UTF8Encoding` 构造 `StreamReader` 并 `Peek()`，嗅探出的编码不是 UTF-8（UTF-16/32 的 BOM）⇒ 返回该编码；
  - `:33-35` 否则 `ReadToEnd()` 走完整严格解码，成功 ⇒ `UTF8Encoding`；
  - `:37-40` 任一步抛异常（严格解码失败）⇒ `shiftjisEncoding`。
  - `:43-65` 是同一逻辑的流版（`:48` 显式 `detectEncodingFromByteOrderMarks: true`，`:52`/`:57`/`:62` 复位流位置）。
- 严格性来源：`:9` `new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true)`、`:13` BOM 版同；`:11` `shiftjisEncoding = GetEncoding(932)`；`:67-71` `EncoderFallback.ExceptionFallback` / `DecoderFallback.ExceptionFallback`。⇒ 官方的判据是**严格解码成功/失败二分**，**不是**「解不动就替换成 U+FFFD」。

调用点（`grep -n DetectEncoding reference/emuera-cs --include=*.cs` 的全部命中：**14 处 / 9 个文件**，逐点分类）：
- **9 处直接调用**：
  1. `Sub/EraStreamReader.cs:52` —— `File.ReadAllLines(filepath, DetectEncoding(path))`，ERB/ERH/CSV 逐文件行读主入口（`:50-59` 把失败吞成 `false` → 上游 `FailedOpenFile` 告警跳过）；
  2. `Runtime.Utils/EraDataReader.cs:36` —— 存档/数据流的 `StreamReader`；
  3. `ParserMediator.cs:75` —— `_Rename.csv`（`LoadEraExRenameFile`）；
  4. `GameProc/Process.cs:688` —— 报错时按行回显 ERB 源码；
  5. `GameProc/Process.cs:700` —— 同上（CSV 分支；第二参写成 `Program.ErbDir + …` 是官方笔误，登记备查）；
  6. `Runtime.Utils/Preload.cs:33` —— `readAllLinesDetectEncoding` 的第二跳（`:29-32` 先处理 UTF-8 BOM 分支）；
  7. `UI.Game.Image/AppContents.cs:141` —— `content/` 目录下 CSV 的行读；
  8. `UI.Game/HotkeyState.cs:186` —— `HOTKEY.ERB`；
  9. `GameData.Function/FunctionMethodCreator.cs:8082` —— `LOADTEXT` 的 `File.ReadAllText`。
- **2 处是定义**：`EncodingHandler.cs:15`、`:43`。
- **1 处是包装函数名 + 其 2 个调用点**：`Preload.cs:19`（`readAllLinesDetectEncoding`），被 `Preload.cs:63`（懒装载并行预读）/`:72`（单文件）调用；结果经 `Preload.GetFileLines`（`:14-17`）由 `EraStreamReader.OpenOnCache`（`EraStreamReader.cs:73`）消费。
- 间接走同一探测链（不贡献上面的调用计数）：`macro.txt`（`Runtime.Script/KeyMacro.cs:91-92` 用 `EraStreamReader.Open`）、`emuera.config` / `emuera.configdebug`（`Runtime.Config/ConfigData.cs:518-519`、`:669-670` 用 `EraStreamReader.Open`）、`_Replace.csv`（`ConfigData.LoadReplaceFile` 同链）。

⇒ **旧 §4「全仓库所有 ERB/CSV 读取点统一用 `Config.Encode`」不成立。**

边界（什么情况下不适用）：
- 只有能拿到文件路径（`:19` 可打开）或可 `Seek` 的流（`:45`/`:52`/`:57`/`:62`）才适用；不可 Seek 的流不在覆盖内。
- **无 BOM 的 UTF-16/32 识别不了**：`:23-25` 只认 UTF-8 BOM，`:27-32` 的嗅探只认 `StreamReader` 的 BOM 前导 ⇒ 无 BOM 的 UTF-16 会落到「UTF-8 严格解码失败 → CP932」或整体失败。这是官方口径，不是移植缺口。
- CP932 回退是**严格**的（`:70` ExceptionFallback）⇒ 含 CP932 未定义字节的文件在官方是抛异常并**跳过整文件**，不是尽力解码。
- 探测只选**解码器**，不剥 BOM：BOM 由 `StreamReader`/`ReadAllLines` 消费；直接对整份字节严格解码时 U+FEFF 会留在串首（移植侧因此只剥一个前导 BOM，`HostMethods.kt:320-321`）。
- **不是全局规则**：`Process.cs:738`/`:766`/`:767` 三个 lazyloading 索引文件用固定 `Encoding.UTF8`（`StreamReader` 的 BOM 嗅探仍开）；`EmueraConsole.cs:3531` 的 `File.ReadAllText(x)`（缺省 UTF-8）与 `JSONConfig.cs:21` 也不走 `DetectEncoding`。三者都不是脚本/数据主路。

### 4.2 旧路：`Config.Encode` —— reference 里只剩写侧与调试侧

**结论：`Config.Encode` 在本 reference 里是 `UTF8BOMEncoding`（不是 Shift-JIS），消费者只有「写日志 / 写配置 / 写宏」与「调试器读自己写的 watch 列表」，不覆盖任何脚本读取；存档写出方向是 `SaveEncode`。**

证据：
- `Runtime.Config/Config.cs:17` `public static Encoding Encode = EncodingHandler.UTF8BOMEncoding;`；`:19` `SaveEncode` 同值。
- `grep -n 'Config\.Encode' reference/emuera-cs --include=*.cs` 的全部 6 处：
  - `Forms/DebugDialog.cs:291` 写控制台日志、`:309` 写 watch 列表、`:338` **读** watch 列表（调试器自己写出的文件，自洽）；
  - `Runtime.Config/ConfigData.cs:429`/`:646` 写 `emuera.config` / `emuera.configdebug`；
  - `Runtime.Script/KeyMacro.cs:68` 写 `macro.txt`（读走 `:91-92` 的 `EraStreamReader` ⇒ 探测）。
- `SaveEncode` 的消费者只有写侧：`Runtime.Utils/EraDataWriter.cs:24`（存档写）、`GameData.Function/FunctionMethodCreator.cs:8010`（`SAVETEXT`）。
- 旧 §4 引的 `Config.cs:112-114`（`SystemSaveInUTF8` 把 `SaveEncode` 切成 UTF-8）在本 reference **不存在**（`grep SystemSaveInUTF8` 0 命中）⇒ `SaveEncode` 恒为 `UTF8BOMEncoding`。

边界：
- `Config.Encode` 仍是**写侧字节口径**（配置文件、日志、`macro.txt` 的编码），属宿主/产物面；读侧不要拿它当脚本解码口径。
- `DebugDialog.cs:338` 是唯一读点，读的是调试器自己的 watch 列表，与玩家语料无关；把它当「官方读文件固定编码」的证据是误读。
- reference 里**另有**一处硬编码 Shift-JIS，但不在读文件路径上：`GameView/EmueraConsole.cs:3255`
  `Encoding.GetEncoding("Shift-JIS").GetByteCount(str)`（`PRINT_C` 的显示宽度）；它常被误引成「官方读文件固定 Shift-JIS」的证据，实际只算宽度。

### 4.3 移植侧跟随 `DetectEncoding` 是对的（不是自创、不是超集）

**结论：移植侧把 `DetectEncoding` 的三段回退移植成 CP932 回落，与 reference 一致；旧 §4 判它「画蛇添足版定制或移植层自创」是错的，必须更正，不许按旧结论改回固定 Shift-JIS。**

- `CsvLoader.kt:19`、`:71`（探测链逐条对齐 `EncodingHandler.cs:15-41`）；`:24-25` 的「官方无探测、固定 Shift-JIS」注释是旧 §4 的直接产物，**须改**为「官方即此口径」（跨路，见 §15.5 接线清单）。
- `HostMethods.kt:269`、`:312-321`（`LOADTEXT` = `FunctionMethodCreator.cs:8082` + 只剥一个前导 BOM）。
- `EmueraConfigParser.kt:483`、`:508`（无 BOM 严格 UTF-8 失败 → `windows-31J`，对应 `EncodingHandler.cs:37-40`；`emuera.config` 的官方读入口是 `EraStreamReader`，即同一条探测链）。
- 同口径的其它落点：`BootHosts.kt:66`/`:138`、`MacroFile.kt:17`/`:90`、`ConfigFileStore.kt:34`、`Symbols.kt:1240`、`LogicalLineReader.kt:291-293`、`HotkeyErbProgram.kt:16`、`FileOps.kt:44`；严格 UTF-8 校验器 `StrictUtf8.kt` 对齐 `EncodingHandler.cs:9` 的 `throwOnInvalidBytes: true`（`StrictUtf8Test.kt` 的手写判据表，见 §15.2）。

**对策：保持现状。** 任何「改回固定 Shift-JIS / 固定 UTF-8」的所谓纠正都违背 reference，回归基准也会随之错位。

---

## 5. 三目 `? #` 左结合（TermStack 归约顺序）

- **结论：官方实现为"左优先配对"：连续三目 `a ? b # c ? d # e` 归约为 `(a ? b : c) ? d : e`；一对 `?` 只配一个 `#`，多余的 `#` 报"式の数が不足しています"。** confidence: **高**（机制推演自源码，未跑运行时验证）
- **证据**（`Emuera/GameData/Expression/ExpressionParser.cs`）：
  - `TermStack.Add(op)`（`:457-516`）在 state=1 时按优先级即时归约：

```csharp
int priority = OperatorManager.GetPriority(op);
//直前の計算の優先度が同じか高いなら還元。
while (lastPriority() >= priority)
{
    this.reduceLastThree();
}
stack.Push(op);
```

  - `ReduceAll()`（`:547-565`）：`while (stack.Count > 1) { reduceLastThree(); }` —— 从栈顶（右端）向左反复归约。
  - `reduceLastThree`（`:588-607`）遇三目 op 时向左弹出配对：

```csharp
IOperandTerm right = (IOperandTerm)stack.Pop();//後から入れたほうが右側
OperatorCode op = (OperatorCode)stack.Pop();
IOperandTerm left = (IOperandTerm)stack.Pop();
if (OperatorManager.IsTernary(op))
{
    if (stack.Count > 1)
    {
        reduceTernary(left, right, op);
        return;
    }
    throw new CodeEE("式の数が不足しています");
}
```

  - `reduceTernary`（`:609-616`）：再弹出 `newOp`/`newLeft`，`ReduceTernaryTerm(newOp, newLeft, left, right)` —— 即配对总是"当前 `#` 与栈中紧邻其左的 `?`"，多出的 `#` 归约到栈底时 `stack.Count <= 1` → 抛错。
  - 优先级（`OperatorCode.cs`）：`Ternary_a = 0x1800 + 0x05`（`?`，prio 5）、`Ternary_b = 0x1900 + 0x10`（`#`，prio 16），注释 `:47`：`"#"、三項演算子区切り":"が使えないのでかわり`。`?` 优先级低于 `#`，扫描到第二个 `?` 时（prio 5）会先把前面的 `#`（16≥5）归约掉 → 强制左配对。
- **对策**：按"左结合配对 + 多 `#` 报错"复刻；归约顺序为右端优先、遇 `#` 回溯配对最近的未配 `?`。

---

## 6. 宏系统 `#DEFINE` / expandMacro / UseMacro

- **结论：头文件加载期解析 `#DEFINE`，注册 标识符→WordCollection（词序列）；句子级与标识符级两处展开；函数型宏 `$n` 参数替换；每句展开上限 100。** confidence: **高**
- **证据与机制**：
  - **注册**（`Emuera/GameProc/HeaderFileLoader.cs:103-104, 145-230`）：头文件逐行识别 `#DEFINE` → `analyzeSharpDefine`：
    - `ReadSingleIdentifier` 取源标识符（`ICVariable` 时大写化）→ `Analyse(st, EoL, AllowAssignment)` 取替换体 WordCollection；空替换体允许（1808a3）。
    - 函数型：`hasArg = st.Current == '('`（**宏名与 `(` 之间不允许空白**，`:165` 注释）。参数表在 WordCollection 上解析，`word.SetIsMacro()` 标记参数，重复参数名报错；替换体内对应 IdentifierWord 替换为 MacroWord（带序号）。
    - 产物 `DefineMacro`（`Emuera/GameData/DefineMacro.cs:10-31`）：`Keyword / ArgCount / Statement / IDWord / HasArguments / IsNull`；替换体恰为单标识符时缓存 `IDWord` 供标识符级快路径。
  - **展开 A（标识符级）**（`LexicalAnalyzer.cs:357-373`，ReadSingleIdentifierWord 内）：查 `GetMacro(str)` 循环替换；`macro.IDWord != null`（函数型）时抛"マクロ…はこの文脈では使用できません(1単語に置き換えるマクロのみが使用できます)"；上限 `MAX_EXPAND_MACRO = 100`（`:63`），防自引用/循环。
  - **展开 B（句子级）**（`LexicalAnalyzer.cs:986-1100`）：`Analyse` 产词后 `if (UseMacro) return expandMacro(ret);`
    - `expandMacro`：遍历 IdentifierWord，无参宏 `wc.Remove(); wc.InsertRange(macro.Statement);`；有参宏走 `expandFunctionlikeMacro`。
    - `expandFunctionlikeMacro`：要求紧跟 `(`，按 `,`/`)`/括号嵌套收集实参（实参不可为空），删除调用段后把宏体中 MacroWord 替换为实参 WordCollection，再 InsertRange 回原位置，`wc.Pointer = macroStart`（支持展开结果再被扫描）。
  - **开关**（`LexicalAnalyzer.cs:131`）：`public static bool UseMacro = true;`（默认开启）。
- **对策**：M2 按上述双路径（单标识符快路径 + 句级展开）与 100 上限复刻。

---

## 7. 全角字符判定 SystemAllowFullSpace

- **结论：判定就是与 U+3000（'　'）字面直接比较，没有"全角区段"判定函数。空白集合 = 半角空格 + \t + 全角空格（IsWhiteSpace 无条件包含 U+3000），但词法主循环/跳过逻辑中遇 U+3000 是否报错由 `Config.SystemAllowFullSpace` 控制（默认 true）。** confidence: **高**
- **证据**：
  - `LexicalAnalyzer.cs:724-727`：

```csharp
public static bool IsWhiteSpace(char c)
{
    return c == ' ' || c == '\t' || c == '　';
}
```

  - `LexicalAnalyzer.cs:425-428`（ReadSingleIdentifier 内，遇全角空格终止标识符）：

```csharp
case '　':
    if (!Config.SystemAllowFullSpace)
        throw new CodeEE("予期しない全角スペースを発見しました(この警告はシステムオプション「" + Config.GetConfigName(ConfigCode.SystemAllowFullSpace) + "」により無視できます)");
    goto end;
```

  - `SkipWhiteSpace`（`:734-752`）同款检查；`SkipAllSpace`（`:707` 附近）同款。
  - 默认值 `ConfigData.cs:96`：`new ConfigItem<bool>(ConfigCode.SystemAllowFullSpace, "全角スペースをホワイトスペースに含める", true)`。
  - 另一使用点 `GameProc/LogicalLineParser.cs:429`：行首命令后跟非半角空白字符的警告判定（`(!Config.SystemAllowFullSpace || (stream.Current != '　'))`）。
- **对策**：复刻为 U+3000 字面比较；默认放行。

---

## 8. PrintV 单引号分支（AnalyzePrintV）

- **结论：PRINTV 参数表中 `'` 后内容按"读到逗号/行尾"的普通字符串处理（不做数式解析），作为 LiteralStringWord 拼进参数；后续若为 `,` 则继续解析其余参数。** confidence: **高**
- **证据**（`LexicalAnalyzer.cs:51` + `:897-912`）：

```csharp
AnalyzePrintV = 1,//PRINTVの引数で'に続けて文字列を書くと数式ではないが文字列として表示される
```

```csharp
case '\'':
    if ((flag & LexAnalyzeFlag.AllowSingleQuotationStr) == LexAnalyzeFlag.AllowSingleQuotationStr)
    {
        st.ShiftNext();
        ret.Add(new LiteralStringWord(ReadString(st, StrEndWith.SingleQuotation)));
        if (st.Current != '\'')
            throw new CodeEE("\'が閉じられていません");
        st.ShiftNext();
        break;
    }
    if ((flag & LexAnalyzeFlag.AnalyzePrintV) != LexAnalyzeFlag.AnalyzePrintV)
    {
        //AssignmentStr用特殊処理 代入文の代入演算子を探索中で'=の場合のみ許可
        if ((endWith == LexEndWith.Operator) && (nestBracketS == 0) && (nestBracketL == 0) && st.Next == '=')
            goto end;
        throw new CodeEE("字句解析中に予期しない文字'" + st.Current + "'を発見しました");
    }
    st.ShiftNext();
    ret.Add(new LiteralStringWord(ReadString(st, StrEndWith.Comma)));
    if (st.Current == ',')
        goto case ',';//続きがあるなら,の処理へ。それ以外は行終端のはず
    goto end;
```

- **要点**：三条分支互斥——HTML_PRINT 的 `''` 成对单引号串（AllowSingleQuotationStr）、PRINTV 的 `'…` 到逗号/行尾（AnalyzePrintV）、其他上下文报错（但代入文探索中 `'= ` 特例放行）。
- **对策**：按三态分支复刻；PRINTV 分支终止符是 `Comma`（非闭合引号）。

---

## 附：官方 bug 清单（长期资产，M2 对拍基准）

> 2026-11-08 MODERN-T5 校准：本清单原把字符串 `>=`/`<=` 记为「官方 bug 需复刻」，但那读数是 **1.824** 的；
> 本项目目标是 **4.981**，该两处已在 4.981 修好 ⇒ 从「复刻清单」降级为「版本校准纪实」，移植已回正。

| # | 位置 | 1.824 症状（旧） | 4.981 判据（现行） | 移植状态 |
|---|------|------|------|------|
| 1 | `OperatorMethod.cs` `GreaterEqualStrStr` | 字符串 `>=` 完全反向：`s1<s2` 时为真（`c < 0 → 1`） | `c >= 0 → 1`（4.981 `:547`） | **已校准**（MODERN-T5） |
| 2 | `OperatorMethod.cs` `LessEqualStrStr` | 字符串 `<=` 相等泄漏：`s1==s2` 时为假（`c < 0 → 1`） | `c <= 0 → 1`（4.981 `:578`） | **已校准**（MODERN-T5） |

- 1.824 的两处是同源复制笔误（`GreaterEqualStrStr` 与 `LessStrStr` 均为 `c < 0`，对照 `GreaterStrStr` 的 `c > 0` 可证方向写反）；4.981 已改为 `c >= 0` / `c <= 0`。
- `EqualStrStr` / `NotEqualStrStr` / `GreaterStrStr` / `LessStrStr` 两版均正常。
- **校准纪律**：判据以 **4.981 反编译树**（`reference/emuera-skia-4.981/`）为准；1.824 锚（`Emuera/GameData/Expression/`）仅作历史对照，不再作为复刻依据。比较基准 `SCExpression` 两版均为 `const Ordinal`（4.981 `Config.cs:523`）。

---

## 9. 2026-10-19 spec 与 C# 交叉验证（CONTRACT-M4-A §4 · worker l-specgap2）

工具：`eratw-android/tools/spec_crosscheck.py` + 新增的 `tools/miniyaml.py`（specs yaml 的零依赖解析器）。
分工：`tools/golden-coverage.py` 管 **golden 覆盖率**；本工具管 **spec 与 C# 注册表的一致性**
（条目名 / 参数形态 / flag 位 / 引用行号）。两者现在都不再 `import yaml`——本机 Python 3.14.3 无 PyYAML，
AGENTS.md §2 禁止装依赖 ⇒ 统一走 `tools/miniyaml.py`。它只覆盖本项目 yaml 实际用到的构造
（块映射、块序列、单行流式集合、裸串/单双引号串、多行裸串折叠、` #` 注释），
碰到块标量 `|` `>`、锚点、多文档、跨行流式集合一律抛 `MiniYamlError`——**宁可崩也不静默给错值**。

复现命令：

```bash
python tools/spec_crosscheck.py                # 人读报告（A–F 六节）
python tools/spec_crosscheck.py --json         # 机器可读；连跑两次逐字节一致
python tools/spec_crosscheck.py --section      # 9.2 的差异清单（本波 98 行）
python tools/spec_crosscheck.py --emit-flags   # isa.yaml 的 flag 素材（仅强依据条目，157 行）
python tools/spec_crosscheck.py --flags        # 可由注册表静态定出的 flag 表（216/302 名）
python tools/golden-coverage.py                # 300/303 语句、252/271 函数（波前一致，无回退）
```

退出码 0=跑通、2=参考文件缺失；差异本身不改退出码（差异是产出物，不是闸）。
`--json` 里带 10 个参考文件的 sha256，参考树一动就会显形。

### 9.1 本轮按 C# 行号落地的修订

**（1）specs/isa.yaml**

- `BEGIN.aliases` 去掉 `FORCE_BEGIN`，`register` 由 `FunctionIdentifier.cs:3950,4111` 收紧为 `:3950`。
  依据：FORCE_BEGIN 由**独立实现类**注册——`:2939` `private sealed class FORCE_BEGIN_Instruction : AInstruction`、
  `:4111` `addFunction(FunctionCode.FORCE_BEGIN, new FORCE_BEGIN_Instruction())`；BEGIN 自己是 `:2922` / `:3950`。
  该别名的归属本就由独立条目 `FORCE_BEGIN`（register `:4111`）覆盖 ⇒ 规范名优先，注册表语义不变。
  ⚠ **需组长接线**：`core/code/OpcodeTable.kt:622-623` 的注释仍写「FORCE_BEGIN 既是 BEGIN 的别名又是独立语句名」，
  KDoc 措辞与本规约不再一致（`OpcodeTableTest` 的断言与 `StatementAliases.resolve` 行为都不受影响）。
- 新增 `flag:` / `flag_source:` 字段，覆盖 **157 / 169** 条目（素材 = `--emit-flags`，逐条带行号）。
  位定义 `FunctionIdentifier.cs:3721-3753`；合成式 `flag = instruction.Flag | additionalFlag` 见 `:4200-4206`；
  没有构造函数的实现类保持 `AInstruction.cs:8` `protected int flag;` 的默认 0。
  只收两种**强依据**：注册调用里的字面量（ALIGNMENT=6 `:4004`、ASSERT=14 `:4075`），
  或构造函数里的无条件赋值（BREAK=5 `:3307`、CALLF=38 `:1113`、BAR=8198 `:1296`）——这五条已逐行对过原文。
  仍缺 `flag` 的 12 条：PRINT / PRINT_DATA / DEBUGPRINT / METHOD / SET / CALL / GOTO / TRYCALLLIST /
  ELSEIF / ENDIF / REPEAT / REND，全部落在 9.4 的「按别名才可定」簇里，不猜。
- arg_form 补注册形态 token：DATA（`STR_NULLABLE` 与 `FORM_STR_NULLABLE`，`:4017` / `:4018`）、
  TRYCALLLIST + TRYJUMPLIST + TRYGOTOLIST（`FunctionArgType.VOID`，`:4065-4067`）、
  HTML_PRINT_ISLAND_CLEAR（`:395`）。E1 命中 289 → **301**，未体现 5 → **0**。
- 取证加强（不改规约内容，只改工具）：
  ELSE / ELSEIF / CASE / CASEELSE 的形态写在注册实参里——`new ELSEIF_Instruction(FunctionArgType.VOID |
  INT_EXPRESSION | CASE)`（`:3962` `:3963` `:3966` `:3967`），旧脚本只看类体因此判「无形态可取」；
  VARI / VARS / INPUTMOUSEKEY 一类构造期把 `func.Argument` 强转成具体 Argument
  （`:37` `(IntAsignArgument)func.Argument`），同样计为形态依据。
  ⇒ 「实现类无静态形态」8 → **1**（只剩 CUSTOMDRAWLINE，见 9.4）。

**（2）specs/builtin-functions.yaml** — 29 处 `args` 与 1 处 `semantics` 修正，逐条 C# 依据：

| 条目 | 改后 args | C# 依据（FunctionMethodCreator.cs） |
|---|---|---|
| FINDCHARA / FINDLASTCHARA | `var, value[, start[, end]]` | `:3328-3339` 4 槽 + `OmitStart=2`；`:3362-3369` 读 arguments[2]/[3] |
| FINDELEMENT / FINDLASTELEMENT | `var, value[, start[, end[, isExact]]]` | `:4915-4927` 5 槽 + `OmitStart=2`；`:4940-4944` |
| INRANGEARRAY | `varName, low, high[, start[, end]]` | `:5018-5030` 5 槽 + `OmitStart=3`；`:5063-5064` |
| INRANGECARRAY | `varName, low, high[, start[, end]]` | `:5039-5051`（同类的 chara 形态）；`:5063-5064` |
| SUMARRAY / SUMCARRAY | `varName[, start[, end]]` | `:4359-4369` `OmitStart=1` |
| MAXARRAY / MAXCARRAY / MINARRAY / MINCARRAY | `varName[, start[, end]]` | `:4664-4674` `OmitStart=1`（MIN 与 MAX 共用实现类） |
| SUBSTRING / SUBSTRINGU | `str[, start[, length]]` | `:5295-5305` `OmitStart=1` |
| ARRAYMSORTEX | `var, names[, order[, limit]]` | `:506-517` 两份形态均 `OmitStart=2` ⇒ names 必选 |
| DT_CELL_SET | `name, row, colName[, value[, byId]]` | `:2550-2562` `OmitStart=3`（省略 value 的运行期行为另记 9.4） |
| LOADTEXT | `file[, forceSavDir[, evalOnly]]` | `:8025-8035` 3 槽 `OmitStart=1`；`:8046-8049` 第 3 实参**求值后丢弃**（官方行为，ADR-005 原样复刻） |
| SAVETEXT | `text, file[, forceSavDir[, evalOnly]]` | `:7941-7952` 4 槽 `OmitStart=2`；`:7963-7966` 第 4 实参同样丢弃 |
| GFILLRECTANGLE | `gId, x, y, width, height` | `:7172-7179` `Type[5]`；`:7189-7194` ReadGraphics(0)+ReadRectangle(1) ⇒ **C# 没有 color 槽位** |
| RESUMETEXTBOX | `x, y, width` + semantics 注记 | 与 MOVE_TEXTBOX 共用 `:3086-3091` `Type[3]`；`:3098-3101` resume 分支整组丢弃；`Forms/MainWindow.cs:342` `SetTextBoxPos(xOffset, yOffset, width)` |
| HOTKEY_STATE | `key[, state]` | `:8684-8693` `OmitStart=1` |
| HOTKEY_STATE_INIT | `key` | `:8713-8718` 1 槽 + `OmitStart=1` ⇒ 必选（原 yaml 写成可省略） |
| BITMAP_CACHE_ENABLE | `flag` | `:8660-8665` 同上 |
| HTML_GETPRINTEDSTR | `[lineIndex]` | `:6081-6086` 1 槽 + `OmitStart=0` ⇒ 可省略 |
| FLOWINPUT | `def[, input[, canSkip[, forceSkip]]]` | `:8466-8477` 4 槽 `OmitStart=1`；`:8485-8496` 逐位对应 flowinputDef / flowinput / flowinputCanSkip / flowinputForceSkip（原 yaml 漏了第 2 位） |
| GDRAWGWITHROTATE | `gId, srcId, angle[, destX[, destY]]` | `:6596-6608` 5 槽 `OmitStart=3`；`:6620-6638` arguments[2] 作旋转量、[3][4] 走 ReadPoint；`GameView/GraphicsImage.cs:407` 形参 `(srcGra, a, x, y)` ⇒ 原 yaml 把 angle 写在末位是错的 |
| GGETTEXTSIZE | `str, fontName, fontSize[, fontStyle]` | `:6537-6548` 4 槽 `OmitStart=3`；`:6560-6566`、`:6584-6587`（宽为返回值、高写 `RESULT_ARRAY[1]`）⇒ **首实参不是 gId** |
| XML_SET / XML_SET_BYNAME | `doc, xpath, value[, allMatched[, style]]` | `:1156-1166` / `:1188-1202` 5 槽 `OmitStart=3`；`:1259` 第 4 位=多匹配节点开关、`:1260-1264` 第 5 位=style(0..2) |

> 官方 bug / 怪癖按 ADR-005 原样记录，不做“顺手修正”：LOADTEXT / SAVETEXT 的尾随实参求值后丢弃、
> RESUMETEXTBOX 声明三实参却整组忽略、`GAMEBASE_AUTHER` 拼写错误名与正名并存。

**（3）specs/variables.yaml** — 新增 `multi_dim_arrays` 8 条（此前只在 `default_sizes` 的合并说明里以注释出现过）：

- 登记行：`VariableData.cs:3103`（DITEMTYPE）、`:3104-3108`（DA/DB/DC/DD/DE）、`:3109-3110`（TA/TB）。
- 尺寸：2D 打包常量 `429496729700L` ⇒ (100,100)（`ConstantData.cs:224-226`）；
  3D `109951267635300L` ⇒ (100,100,100)（`:232-234`）；建数组 `VariableData.cs:2977` / `:2993`。
- `saved: true` 的依据：这 8 个 VariableCode 值（DITEMTYPE=419627008 … DE=419627013、TA=822280192、
  TB=822280193）都带 `__SAVE_EXTENDED__`(268435456) 位 ⇒ `VariableToken.cs:93-96` 直接判 IsSavedata=true，
  走扩展存档的名字-值对，不入经典定长段（`:103` 的 `Code & 673054720` switch 分支用不到）。
- 同时修掉一个**取证缺陷**（不改规约）：`VariableData.cs:3140-3142`（GAMEBASE_AUTHER / GAMEBASE_AUTHOR
  共用同一个 token 实例）与 `:3153-3155`（RAND 依 `Config.CompatiRAND` 三元式）不是内联
  `varTokenDic.Add("X", new Y(VariableCode.Z…))` 形态，旧脚本把这三个名字误报成「yaml 多出」。
  两种形态都收后 varTokenDic 全集 155 → **158** 名，variables.yaml 覆盖 **158/158**，缺 0 多 0。

### 9.2 差异清单（`--section` 在收口时点的原样输出，逐条）

条目名 / 差异类型 / C# `文件:行号` / 处置（工具口径的建议动作；9.1 已落地的项不再出现在这里）。

| 条目 | 差异类型 | C# 依据 | 处置 |
|---|---|---|---|
| `CUSTOMDRAWLINE` | 参数形态不符 | FunctionIdentifier.cs:? 实现类 CUSTOMDRAWLINE_Instruction | TODO(verify) 实现类 CUSTOMDRAWLINE 无静态 FunctionArgType（条目 CUSTOMDRAWLINE_Instruction） |
| `DT_ROW_REMOVE` | 参数形态不符 | DataTableRowRemoveMethod @ FunctionMethodCreator.cs:2364 (ex, global) | yaml args「name, id \| name, idArray, count」判 4..4，C# 实参表 2..3 ⇒ 上限不符（硬差异） |
| `GDRAWG` | 参数形态不符 | GraphicsDrawGMethod @ FunctionMethodCreator.cs:7209 (ex, global) | yaml args「gId, srcId, destX, destY[, destW, destH, srcX, srcY, srcW, srcH]」判 4..10，C# 实参表 10..11 ⇒ 上限不符（硬差异） |
| `GDRAWSPRITE` | 参数形态不符 | GraphicsDrawSpriteMethod @ FunctionMethodCreator.cs:7356 (ex, global) | yaml args「gId, spriteName[, x, y[, width, height]]」判 2..6，C# 实参表 2..7 ⇒ 上限不符（硬差异） |
| `MAP_GETKEYS` | 参数形态不符 | MapGetStrMethod @ FunctionMethodCreator.cs:2921 (ex, case-label) | yaml args「name[, resultArray]」判 1..2，C# 实参表 1..3 ⇒ 上限不符（硬差异） |
| `REGEXPMATCH` | 参数形态不符 | RegexpMatchMethod @ FunctionMethodCreator.cs:993 (ex, global) | yaml args「str, pattern[, ...]」判 2..3，C# 实参表 2..4 ⇒ 上限不符（硬差异） |
| `XML_REPLACE` | 参数形态不符 | XmlReplaceMethod @ FunctionMethodCreator.cs:1755 (ex, global) | yaml args「doc, xpath, xmlFragment[, index]」判 3..4，C# 实参表 2..4 ⇒ 可选性未用方括号表达（软差异） |
| `XML_REPLACE_BYNAME` | 参数形态不符 | XmlReplaceMethod @ FunctionMethodCreator.cs:1755 (ex, global) | yaml args「docName, xpath, xmlFragment[, index]」判 3..4，C# 实参表 2..4 ⇒ 可选性未用方括号表达（软差异） |
| `PRINT` | flag 位缺失 | FunctionIdentifier.cs:3830（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTC` | flag 位缺失 | FunctionIdentifier.cs:3890（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTCD` | flag 位缺失 | FunctionIdentifier.cs:3898（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTCK` | flag 位缺失 | FunctionIdentifier.cs:3894（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTD` | flag 位缺失 | FunctionIdentifier.cs:3860（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTDATA` | flag 位缺失 | FunctionIdentifier.cs:3902（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAD` | flag 位缺失 | FunctionIdentifier.cs:3908（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATADL` | flag 位缺失 | FunctionIdentifier.cs:3909（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATADW` | flag 位缺失 | FunctionIdentifier.cs:3910（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAK` | flag 位缺失 | FunctionIdentifier.cs:3905（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAKL` | flag 位缺失 | FunctionIdentifier.cs:3906（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAKW` | flag 位缺失 | FunctionIdentifier.cs:3907（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAL` | flag 位缺失 | FunctionIdentifier.cs:3903（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDATAW` | flag 位缺失 | FunctionIdentifier.cs:3904（PRINT_DATA_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:241） |
| `PRINTDL` | flag 位缺失 | FunctionIdentifier.cs:3861（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTDW` | flag 位缺失 | FunctionIdentifier.cs:3862（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORM` | flag 位缺失 | FunctionIdentifier.cs:3839（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMC` | flag 位缺失 | FunctionIdentifier.cs:3892（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMCD` | flag 位缺失 | FunctionIdentifier.cs:3900（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMCK` | flag 位缺失 | FunctionIdentifier.cs:3896（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMD` | flag 位缺失 | FunctionIdentifier.cs:3869（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMDL` | flag 位缺失 | FunctionIdentifier.cs:3870（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMDW` | flag 位缺失 | FunctionIdentifier.cs:3871（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMK` | flag 位缺失 | FunctionIdentifier.cs:3854（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMKL` | flag 位缺失 | FunctionIdentifier.cs:3855（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMKW` | flag 位缺失 | FunctionIdentifier.cs:3856（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORML` | flag 位缺失 | FunctionIdentifier.cs:3840（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMLC` | flag 位缺失 | FunctionIdentifier.cs:3893（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMLCD` | flag 位缺失 | FunctionIdentifier.cs:3901（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMLCK` | flag 位缺失 | FunctionIdentifier.cs:3897（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMN` | flag 位缺失 | FunctionIdentifier.cs:4133（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMS` | flag 位缺失 | FunctionIdentifier.cs:3842（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSD` | flag 位缺失 | FunctionIdentifier.cs:3872（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSDL` | flag 位缺失 | FunctionIdentifier.cs:3873（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSDW` | flag 位缺失 | FunctionIdentifier.cs:3874（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSK` | flag 位缺失 | FunctionIdentifier.cs:3857（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSKL` | flag 位缺失 | FunctionIdentifier.cs:3858（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSKW` | flag 位缺失 | FunctionIdentifier.cs:3859（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSL` | flag 位缺失 | FunctionIdentifier.cs:3843（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSN` | flag 位缺失 | FunctionIdentifier.cs:4134（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMSW` | flag 位缺失 | FunctionIdentifier.cs:3844（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTFORMW` | flag 位缺失 | FunctionIdentifier.cs:3841（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTK` | flag 位缺失 | FunctionIdentifier.cs:3845（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTKL` | flag 位缺失 | FunctionIdentifier.cs:3846（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTKW` | flag 位缺失 | FunctionIdentifier.cs:3847（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTL` | flag 位缺失 | FunctionIdentifier.cs:3831（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTLC` | flag 位缺失 | FunctionIdentifier.cs:3891（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTLCD` | flag 位缺失 | FunctionIdentifier.cs:3899（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTLCK` | flag 位缺失 | FunctionIdentifier.cs:3895（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTN` | flag 位缺失 | FunctionIdentifier.cs:4130（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTS` | flag 位缺失 | FunctionIdentifier.cs:3836（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSD` | flag 位缺失 | FunctionIdentifier.cs:3866（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSDL` | flag 位缺失 | FunctionIdentifier.cs:3867（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSDW` | flag 位缺失 | FunctionIdentifier.cs:3868（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLE` | flag 位缺失 | FunctionIdentifier.cs:3875（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLED` | flag 位缺失 | FunctionIdentifier.cs:3885（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORM` | flag 位缺失 | FunctionIdentifier.cs:3878（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORMD` | flag 位缺失 | FunctionIdentifier.cs:3888（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORMK` | flag 位缺失 | FunctionIdentifier.cs:3883（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORMS` | flag 位缺失 | FunctionIdentifier.cs:3879（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORMSD` | flag 位缺失 | FunctionIdentifier.cs:3889（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEFORMSK` | flag 位缺失 | FunctionIdentifier.cs:3884（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEK` | flag 位缺失 | FunctionIdentifier.cs:3880（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLES` | flag 位缺失 | FunctionIdentifier.cs:3877（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLESD` | flag 位缺失 | FunctionIdentifier.cs:3887（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLESK` | flag 位缺失 | FunctionIdentifier.cs:3882（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEV` | flag 位缺失 | FunctionIdentifier.cs:3876（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEVD` | flag 位缺失 | FunctionIdentifier.cs:3886（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSINGLEVK` | flag 位缺失 | FunctionIdentifier.cs:3881（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSK` | flag 位缺失 | FunctionIdentifier.cs:3851（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSKL` | flag 位缺失 | FunctionIdentifier.cs:3852（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSKW` | flag 位缺失 | FunctionIdentifier.cs:3853（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSL` | flag 位缺失 | FunctionIdentifier.cs:3837（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSN` | flag 位缺失 | FunctionIdentifier.cs:4132（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTSW` | flag 位缺失 | FunctionIdentifier.cs:3838（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTV` | flag 位缺失 | FunctionIdentifier.cs:3833（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVD` | flag 位缺失 | FunctionIdentifier.cs:3863（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVDL` | flag 位缺失 | FunctionIdentifier.cs:3864（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVDW` | flag 位缺失 | FunctionIdentifier.cs:3865（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVK` | flag 位缺失 | FunctionIdentifier.cs:3848（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVKL` | flag 位缺失 | FunctionIdentifier.cs:3849（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVKW` | flag 位缺失 | FunctionIdentifier.cs:3850（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVL` | flag 位缺失 | FunctionIdentifier.cs:3834（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVN` | flag 位缺失 | FunctionIdentifier.cs:4131（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTVW` | flag 位缺失 | FunctionIdentifier.cs:3835（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `PRINTW` | flag 位缺失 | FunctionIdentifier.cs:3832（PRINT_Instruction） | TODO(verify) 逐别名才可定 flag：条件不可由注册实参求值（首个不可求值赋值 FunctionIdentifier.cs:92） |
| `DO_NOTHING` | 别名与大小写口径 | FunctionIdentifier.cs:3822 / :4135 | 口径说明：条目名按实现类去 _Instruction 后缀，本就不是注册名 |
| `METHOD` | 别名与大小写口径 | FunctionIdentifier.cs:3822 / :4135 | 口径说明：条目名按实现类去 _Instruction 后缀，本就不是注册名 |
| `PRINT_DATA` | 别名与大小写口径 | FunctionIdentifier.cs:3822 / :4135 | 口径说明：条目名按实现类去 _Instruction 后缀，本就不是注册名 |
| `SAVELOADGAME` | 别名与大小写口径 | FunctionIdentifier.cs:3822 / :4135 | 口径说明：条目名按实现类去 _Instruction 后缀，本就不是注册名 |

### 9.3 口径说明（不是缺陷，别再逐条追）

- **条目名不等于注册名**：`meta.note` 定的是「`name` = 实现类名去掉 `_Instruction` 后缀」，
  因此 4 个条目名本身不进 funcDic：`DO_NOTHING`（注册名 ENDDATA `FunctionIdentifier.cs:4019`）、
  `METHOD`（别名写成散文「所有未与语句名冲突的函数方法名」，注册走方法并入循环 `:4135-4142`）、
  `PRINT_DATA`（注册名 PRINTDATA* `:3902-3910`）、`SAVELOADGAME`（注册名 SAVEGAME/LOADGAME `:3951-3952`）。
  ⇒ 9.2 里这 4 行是口径说明。工具已把「条目名型 ghost」与「别名串里的假名」分开计数，
  后者本波为 **0**（改 BEGIN 前是 1 个 FORCE_BEGIN 型重复归属，见 9.1）。
- **funcDic 全集 568 名 = 语句 302 + 方法 271 + SET**：语句名优先，方法并入循环 `:4135-4142` 不覆盖同名语句；
  `SET` 走 `setFunc` 特例不进 funcDic（`:3829`）。FunctionCode 枚举 304 项是注册名的合法来源。
- **arity 的 C# 侧口径**：一个方法类可携带多份 `ArgTypeList`（按 `OmitStart` 与各份槽位取并集），
  工具取 min/max 聚合值；yaml 的 `args` 用嵌套方括号表达 ⇒ 二者只在「槽位数」或「必选位数」不等时才算差异。
  工具自身的 `bf_arg_arity` 本波也修了一处：旧实现先一次性删光 `[ ]` 再数必选段，
  把 `[filename[, hideInfo]]`（0..2）误判成 min=1，制造过假差异。

### 9.4 TODO(verify)（拿不准的一律没写进 yaml，附卡点）

1. **86 个注册名的 flag 只能按别名逐个定**（PRINT / PRINTV… / PRINTDATA* / BAR / DEBUGPRINT / CALL / GOTO /
   ELSEIF / ENDIF / REPEAT / REND / TRYCALLLIST 族）。
   卡点：PRINT 族后缀是**运行期单遍解析** `charStream`（`FunctionIdentifier.cs:85-171`），
   flag 赋值落在解析分支内（首个不可求值赋值 `:92`），无法由注册实参静态求值 ⇒ 需先给 isa.yaml
   定一个「按别名记 flag」的字段口径（建议 `flag_by_alias`），由组长拍板后本工具即可批量出表（`--flags`）。
   缺 `flag` 字段的 12 条 isa 条目全部来自这一簇 + METHOD / SET 两个非注册名条目。
   **→ 已消解（2026-10-20）**：schema 拍板 §10.3 方案 1 并落地，`--emit-flag-by-alias` 即批量出表，见 §11。
2. `GDRAWG`（C# 10..11 vs yaml 4..10）与 `GDRAWSPRITE`（2..7 vs 2..6）：
   卡点：同一实现类挂多份 ArgTypeList（`:7205-7235` / `:7352-7390`，其中 `OmitStart=6` 在 `:7384`），
   装载期按实参个数挑形态的分支没读完，第 7 / 11 个槽位的名字未定 ⇒ 不敢改 args。
3. `DT_ROW_REMOVE`（2..3 vs yaml `name, id | name, idArray, count`）、`MAP_GETKEYS`（1..3）、
   `REGEXPMATCH`（2..4）、`XML_REPLACE` / `XML_REPLACE_BYNAME`（2..4）：
   卡点：yaml 用 `|` / `...` 表达重载，工具无法分段；且 `MapGetStrMethod` 的 GetKeys 两份形态
   （`:2919-2926` 与 `:2928-2934`）里第三个 Int 实参的作用要看 `:2966-2990` 的 else 分支才能定名。
   ⇒ 需先在规约里定「重载怎么表达」（建议 `args_overloads` 列表），再逐条对齐。
4. `CUSTOMDRAWLINE`：注册只给实现类（`FunctionIdentifier.cs:4005`），实现类 `:506` 既无 FunctionArgType
   token 也无 `func.Argument` 强转 ⇒ 形态需读其 `CreateArgument` 覆写才能定。
5. `DT_CELL_SET` 省略 `value` 的运行期行为（装载期 `OmitStart=3` 已确认可省）：
   卡点：未读 `DataTableCellSetMethod` 的 GetIntValue 尾段。
6. `GGETTEXTSIZE` 首实参不是 gId（已按 C# 改为 `str, fontName, fontSize[, fontStyle]`）：
   卡点：宽度走返回值、高度写 `RESULT_ARRAY[1]`（`:6585-6586`）与 console / GVIEW 侧的写入口径需实机对拍；
   若 GVIEW 波另有依据请回灌本节。

### 9.5 计数（工具输出原样，勿手改）

命中与缺口计数：
- 命中：isa.yaml 条目 169（别名展开 307 名）；builtin-functions.yaml 条目 271；register 引用命中 179；方法 source 类命中 271；语句 arg_form 命中 301；variables.yaml 列出 158 名
- C# 全集：funcDic 568 名 = 语句注册 302 + 方法 271 + SET 特例；FunctionCode 枚举 304；varTokenDic 158 名；可静态定 flag 的注册名 216 / 302
- 缺口 缺条目：0
- 缺口 参数形态不符：8
- 缺口 flag 位缺失：86
- 缺口 别名与大小写口径：4
- 缺口 引用行号存疑：0
- 缺口 变量：yaml 缺 0；yaml 多出（非 varTokenDic 来源）0

复现一致性：`--json` 连跑两次逐字节相同；`--section` 同上。
`tools/golden-coverage.py` 读数与波前基线一致：**300/303 语句、252/271 函数**（未回退）。

---

## 10. 2026-10-19 SPEC-FIX 处置（CONTRACT-M4-A §4 · worker l-specfix，worktree `<work-root>/eratw-wt-specfix`）

工具链：`eratw-android/tools/spec_crosscheck.py` + `tools/miniyaml.py` + `tools/golden-coverage.py`
（Python 3.13.14，零第三方依赖；reference 走 `<work-root>/eratw-安卓移植计划/reference/emuera-cs`）。
本节只登记 §9.2 那 98 条差异的逐条处置与由此产生的工具/规约动作，不改历史节。

### 10.1 总账

| 缺口类型 | 波前（§9.5） | 本波处置后 |
|---|---|---|
| 缺条目 | 0 | 0 |
| 参数形态不符 | 8 | **0** |
| flag 位缺失 | 86 | **86**（不改 yaml，schema 缺 per-alias 表达，见 10.3） |
| 别名与大小写口径 | 4 | 4（拒绝修，与 C# 实为一致，见 10.4） |
| **合计** | **98** | **90** |

- `golden-coverage.py`：**300/303 语句、252/271 函数**，与波前基线逐字节一致（MISS 名单不变：ADDSPCHARA/CALLSHARP/FORCE_BEGIN；19 个函数）。
- 复现一致性：`--json`、`--section` 各连跑两次，`cmp` 逐字节相同。
- 本波未触碰任何 Kotlin / `render/**` / `core/**` / `build.gradle.kts` / BOARD.md / AGENTS.md。

### 10.2 参数形态 8 条：7 条改 yaml，1 条拒绝（yaml 本就正确，假阳在工具）

7 处 yaml 改动在 wave/specfix 起始工作区里已是未提交状态；本波逐条对照 C# 原文复核后接受，
未发现无行号支撑的改动。逐条「原值 → 新值 + 依据」：

| 条目 | args 原值 → 新值（判定 arity） | C# 依据（FunctionMethodCreator.cs） |
|---|---|---|
| REGEXPMATCH | `str, pattern[, ...]`（2..3）→ `str, pattern[, flagOrGroupCount[, resultArray]]`（2..4） | 两份 ArgTypeList：String,String,Int `OmitStart=2`（2..3）与 String,String,RefInt,RefString1D（4..4）`:989-1011`；运行期 3 实参第 3 位 Int 开关写 `RESULT_ARRAY[1]`/`RESULTS_ARRAY`、4 实参第 3/4 位 RefInt/RefString1D `:1047-1062` |
| XML_REPLACE | `doc, xpath, xmlFragment[, index]`（3..4）→ `doc, xpath[, xmlFragment[, index]]`（2..4） | 参数less ctor 三份 ArgTypeList 含 2 槽 `Any,String`（2..2）`:1751-1760`；2 实参官方特例：第 2 位同充 XML 片段与 XPath `:1820`/`:1860`，第 1 位为文档池名时整篇替换直接返回 1 `:1840-1844` |
| MAP_GETKEYS | `name[, resultArray]`（1..2）→ `name[, resultArrayOrFlag[, flag]]`（1..3） | GetKeys 分支两份 ArgTypeList：String,Int `OmitStart=1`（1..2）与 String,RefString1D,Int（3..3）`:2917-2937`；运行期 2 实参第 2 位 Int 开关、3 实参第 2 位数组第 3 位开关，键总数写 RESULT `:2962-3002` |
| DT_ROW_REMOVE | `name, id \| name, idArray, count`（重载写法）→ `name, idOrIdArray[, count]`（2..3） | 两份 ArgTypeList：String,Int（2..2）与 String,RefInt1D,Int（3..3）`:2360-2379`；运行期按 `arguments.Count == 3` 分互斥重载，3 实参 count 必填（`Math.Min(count, 长度)`，≤0 返回 0）`:2393-2422` |
| GDRAWG | `gId, srcId, destX, destY[, destW, destH, srcX, srcY, srcW, srcH]`（4..10）→ 10 必选 + `[, colormatrix]`（10..11） | 两份 11 槽 ArgTypeList 均 `OmitStart=10` `:7205-7243`；运行期 dest=实参 2..5、src=6..9、colormatrix=10，`Count == 10 \|\| arguments[10] == null` 时无颜色矩阵 `:7264-7272` |
| GDRAWSPRITE | `gId, spriteName[, x, y[, width, height]]`（2..6）→ `[, x, y[, width, height[, colormatrix]]]`（2..7） | 四份 ArgTypeList：2 / 4 / 7 槽 `OmitStart=6` / 7 槽 `OmitStart=6` `:7352-7400`；运行期实参个数只允许 2/4/6/7，7 实参读 colormatrix `:7422-7444` |
| CUSTOMDRAWLINE（isa.yaml） | arg_form `ExpressionArgument (const string)` → `'CreateArgument: 1 raw primitive (mandatory) → ExpressionArgument const string'` | 实现类 ctor `base.ArgBuilder = null; flag = 6;` `:508-512`；覆写 `CreateArgument`：`line.PopArgumentPrimitive()` 取 1 个原始串，EOS 抛 CodeEE，产出带 `ConstStr` 的 `ExpressionArgument` `:514-528`。工具侧同步加 CreateArgument 兜底（`statement_arg_type`），E1 命中 301 → 302 |

**XML_REPLACE_BYNAME：拒绝改 yaml。** yaml `docName, xpath, xmlFragment[, index]`（3..4）与 C# **实为一致**：
注册走 `new XmlReplaceMethod(byname: true)`（`:9239`），byName 重载 ctor 用**一份** 4 槽 `OmitStart=3` 的
ArgTypeList 整体覆盖参数less ctor 的三份（`:1787-1805`）⇒ 真实 arity 3..4。工具报的「C# 2..4」是假阳：
`resolve_entry_facts` 旧口径只解析类的**首个**构造函数（参数less ctor），看不到重载。本波修工具（见 10.5.1），
假阳消除，yaml 一字未动。

### 10.3 flag 位 86 条：现有 schema 不能表达 per-alias ⇒ 不改 yaml，需组长拍板

**结论：现有 schema 无法表达 per-alias flag，因此 86 条一律不改 yaml，缺口计数照报（不为了让计数好看而藏）。**

- 86 条 = `PRINT` 条目的 77 个注册别名 + `PRINT_DATA` 条目的 9 个（`FunctionIdentifier.cs:3830-3901` + `:4130-4134` 共 77 个 `addPrintFunction`，`:3902-3910` 共 9 个 `addPrintDataFunction`）。
- 现有 schema 每个条目只有**条目级标量** `flag:` / `flag_source:`（§9.1 落的 157/169 条目）。PRINT 一个条目 77 个别名，flag 取值 **23 个不同值**（8196 / 8452 / 8960 / 8198 / 8708 / 10246 / 10502 / 11010 / 12294 / 12550 / 13058 / 9222 / 11270 / 13318 / 73750 / 74006 / 74514 / 75798 / 76054 / 76562 / 77846 / 78102 / 78610），任何单值都与 C# 不符 ⇒ 不是「补个值」能解决的。
- flag 的 C# 真值来源是运行期（装载期）逐别名单遍解析：`PRINT_Instruction(string name)` 用 `CharStream` 按 `PRINT [SINGLE] [V|S|FORMS|FORM] [LC|C] [K] [D] [N] [L|W]` 文法逐后缀置位（`FunctionIdentifier.cs:85-171`），`PRINT_DATA_Instruction` 同构（`:233-268`）。注册调用里只有名字、没有 flag 字面量，所以 `resolve_instruction_flag` 按注册实参求不出（首个不可求值赋值 `:92`）——这是**真缺口**，不是工具缺陷。

**schema 缺什么**：一个「按别名记 flag」的字段。现成先例：`PRINT` 条目的 `arg_form` 已用
`'varies by alias: STR_NULLABLE / STR_EXPRESSION / FORM_STR_NULLABLE / SP_PRINTV'` 的散文口径表达
per-alias 形态；flag 需要的是**可机器校验**的映射，散文不够。

**建议怎么扩**（三选一，请组长拍板）：

1. `flag_by_alias: {别名: 值, ...}` + `flag_source_by_alias: {别名: "FI:行号,..."}`，与条目级 `flag:` 并存
   （单值条目继续用标量）；工具侧 `--emit-flags` 增加按映射发射的分支，`--section` 的 flag 缺口改为
   「条目缺 flag_by_alias 键」判定。
2. `aliases` 从串改结构化列表 `- {name: PRINTL, flag: 8452, flag_source: "..."}`——表达力最强但动所有
   169 个条目，且 `isa_alias_map` / `golden-coverage.py` 的别名抽取口径都要跟着改，代价最大。
3. 维持现状，把 86 条留在缺口里当作已知边界（PRINT 族 flag 由 Kotlin 侧按同一文法实现，
   用单测对照本表断言）——不改 schema，但规约面不完整。

**素材已就绪**：工具里的 `NameSuffixSim` 按上述文法逐名模拟（本波修了一个变量错位 bug，见 10.5.2），
86/86 个名字全部可解，`python tools/spec_crosscheck.py --flags` 末尾的「逐别名运行期解析 flag 模拟表」
逐行给出 `flag 值 / 位组合 / 依据行号 / ArgBuilder`。下表即该输出的快照（**模拟值，未落 yaml**）；
拍板选方案 1 或 2 后即可批量落地，落地前不得当作规约。

```
# 别名 | flag | 位组合 | ArgBuilder | 依据（FunctionIdentifier.cs） | 注册行
PRINT|8196|METHOD_SAFE+IS_PRINT|STR_NULLABLE|87,117,163,165|3830
PRINTC|8196|METHOD_SAFE+IS_PRINT|STR_NULLABLE|87,117,127,163,165|3890
PRINTCD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,117,127,141,143,163,165|3898
PRINTCK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,117,127,136,138,163,165|3894
PRINTD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,117,141,143,163,165|3860
PRINTDATA|73750|EXTENDED+METHOD_SAFE+PARTIAL+IS_PRINT+IS_PRINTDATA|VAR_INT|235,260,262|3902
PRINTDATAD|75798|EXTENDED+METHOD_SAFE+PARTIAL+ISPRINTDFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,244,246,260,262|3908
PRINTDATADL|76054|EXTENDED+METHOD_SAFE+PARTIAL+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,244,246,249,251,252|3909
PRINTDATADW|76562|EXTENDED+PARTIAL+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,244,246,255,257|3910
PRINTDATAK|77846|EXTENDED+METHOD_SAFE+PARTIAL+ISPRINTKFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,239,241,260,262|3905
PRINTDATAKL|78102|EXTENDED+METHOD_SAFE+PARTIAL+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,239,241,249,251,252|3906
PRINTDATAKW|78610|EXTENDED+PARTIAL+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT+IS_PRINTDATA|VAR_INT|235,239,241,255,257|3907
PRINTDATAL|74006|EXTENDED+METHOD_SAFE+PARTIAL+PRINT_NEWLINE+IS_PRINT+IS_PRINTDATA|VAR_INT|235,249,251,252|3903
PRINTDATAW|74514|EXTENDED+PARTIAL+PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT+IS_PRINTDATA|VAR_INT|235,255,257|3904
PRINTDL|10502|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,117,141,143,152,154,155|3861
PRINTDW|11010|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,117,141,143,158,160|3862
PRINTFORM|8196|METHOD_SAFE+IS_PRINT|FORM_STR_NULLABLE|87,112,163,165|3839
PRINTFORMC|8198|EXTENDED+METHOD_SAFE+IS_PRINT|FORM_STR_NULLABLE|87,112,127,131,163,165|3892
PRINTFORMCD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,127,141,143,163,165|3900
PRINTFORMCK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,127,136,138,163,165|3896
PRINTFORMD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,141,143,163,165|3869
PRINTFORMDL|10502|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,141,143,152,154,155|3870
PRINTFORMDW|11010|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,141,143,158,160|3871
PRINTFORMK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,136,138,163,165|3854
PRINTFORMKL|12550|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,136,138,152,154,155|3855
PRINTFORMKW|13058|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,136,138,158,160|3856
PRINTFORML|8452|METHOD_SAFE+PRINT_NEWLINE+IS_PRINT|FORM_STR_NULLABLE|87,112,152,154,155|3840
PRINTFORMLC|8198|EXTENDED+METHOD_SAFE+IS_PRINT|FORM_STR_NULLABLE|87,112,121,123,163,165|3893
PRINTFORMLCD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,121,123,141,143,163,165|3901
PRINTFORMLCK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,112,121,123,136,138,163,165|3897
PRINTFORMN|8708|METHOD_SAFE+PRINT_WAITINPUT+IS_PRINT|FORM_STR_NULLABLE|87,112,146,149,163,165|4133
PRINTFORMS|8196|METHOD_SAFE+IS_PRINT|STR_EXPRESSION|87,106,163,165|3842
PRINTFORMSD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,106,141,143,163,165|3872
PRINTFORMSDL|10502|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,106,141,143,152,154,155|3873
PRINTFORMSDW|11010|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,106,141,143,158,160|3874
PRINTFORMSK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,106,136,138,163,165|3857
PRINTFORMSKL|12550|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,106,136,138,152,154,155|3858
PRINTFORMSKW|13058|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,106,136,138,158,160|3859
PRINTFORMSL|8452|METHOD_SAFE+PRINT_NEWLINE+IS_PRINT|STR_EXPRESSION|87,106,152,154,155|3843
PRINTFORMSN|8708|METHOD_SAFE+PRINT_WAITINPUT+IS_PRINT|STR_EXPRESSION|87,106,146,149,163,165|4134
PRINTFORMSW|8960|PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT|STR_EXPRESSION|87,106,158,160|3844
PRINTFORMW|8960|PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT|FORM_STR_NULLABLE|87,112,158,160|3841
PRINTK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,117,136,138,163,165|3845
PRINTKL|12550|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,117,136,138,152,154,155|3846
PRINTKW|13058|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,117,136,138,158,160|3847
PRINTL|8452|METHOD_SAFE+PRINT_NEWLINE+IS_PRINT|STR_NULLABLE|87,117,152,154,155|3831
PRINTLC|8198|EXTENDED+METHOD_SAFE+IS_PRINT|STR_NULLABLE|87,117,121,123,163,165|3891
PRINTLCD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,117,121,123,141,143,163,165|3899
PRINTLCK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,117,121,123,136,138,163,165|3895
PRINTN|8708|METHOD_SAFE+PRINT_WAITINPUT+IS_PRINT|STR_NULLABLE|87,117,146,149,163,165|4130
PRINTS|8196|METHOD_SAFE+IS_PRINT|STR_EXPRESSION|87,101,163,165|3836
PRINTSD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,101,141,143,163,165|3866
PRINTSDL|10502|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,101,141,143,152,154,155|3867
PRINTSDW|11010|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,101,141,143,158,160|3868
PRINTSINGLE|9222|EXTENDED+METHOD_SAFE+PRINT_SINGLE+IS_PRINT|STR_NULLABLE|87,90,92,117,163,165|3875
PRINTSINGLED|11270|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTDFUNC+IS_PRINT|STR_NULLABLE|87,90,92,117,141,143,163,165|3885
PRINTSINGLEFORM|9222|EXTENDED+METHOD_SAFE+PRINT_SINGLE+IS_PRINT|FORM_STR_NULLABLE|87,90,92,112,163,165|3878
PRINTSINGLEFORMD|11270|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTDFUNC+IS_PRINT|FORM_STR_NULLABLE|87,90,92,112,141,143,163,165|3888
PRINTSINGLEFORMK|13318|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTKFUNC+IS_PRINT|FORM_STR_NULLABLE|87,90,92,112,136,138,163,165|3883
PRINTSINGLEFORMS|9222|EXTENDED+METHOD_SAFE+PRINT_SINGLE+IS_PRINT|STR_EXPRESSION|87,90,92,106,163,165|3879
PRINTSINGLEFORMSD|11270|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,90,92,106,141,143,163,165|3889
PRINTSINGLEFORMSK|13318|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,90,92,106,136,138,163,165|3884
PRINTSINGLEK|13318|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTKFUNC+IS_PRINT|STR_NULLABLE|87,90,92,117,136,138,163,165|3880
PRINTSINGLES|9222|EXTENDED+METHOD_SAFE+PRINT_SINGLE+IS_PRINT|STR_EXPRESSION|87,90,92,101,163,165|3877
PRINTSINGLESD|11270|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTDFUNC+IS_PRINT|STR_EXPRESSION|87,90,92,101,141,143,163,165|3887
PRINTSINGLESK|13318|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,90,92,101,136,138,163,165|3882
PRINTSINGLEV|9222|EXTENDED+METHOD_SAFE+PRINT_SINGLE+IS_PRINT|SP_PRINTV|87,90,92,95,163,165|3876
PRINTSINGLEVD|11270|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTDFUNC+IS_PRINT|SP_PRINTV|87,90,92,95,141,143,163,165|3886
PRINTSINGLEVK|13318|EXTENDED+METHOD_SAFE+PRINT_SINGLE+ISPRINTKFUNC+IS_PRINT|SP_PRINTV|87,90,92,95,136,138,163,165|3881
PRINTSK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,101,136,138,163,165|3851
PRINTSKL|12550|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,101,136,138,152,154,155|3852
PRINTSKW|13058|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT|STR_EXPRESSION|87,101,136,138,158,160|3853
PRINTSL|8452|METHOD_SAFE+PRINT_NEWLINE+IS_PRINT|STR_EXPRESSION|87,101,152,154,155|3837
PRINTSN|8708|METHOD_SAFE+PRINT_WAITINPUT+IS_PRINT|STR_EXPRESSION|87,101,146,149,163,165|4132
PRINTSW|8960|PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT|STR_EXPRESSION|87,101,158,160|3838
PRINTV|8196|METHOD_SAFE+IS_PRINT|SP_PRINTV|87,95,163,165|3833
PRINTVD|10246|EXTENDED+METHOD_SAFE+ISPRINTDFUNC+IS_PRINT|SP_PRINTV|87,95,141,143,163,165|3863
PRINTVDL|10502|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTDFUNC+IS_PRINT|SP_PRINTV|87,95,141,143,152,154,155|3864
PRINTVDW|11010|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTDFUNC+IS_PRINT|SP_PRINTV|87,95,141,143,158,160|3865
PRINTVK|12294|EXTENDED+METHOD_SAFE+ISPRINTKFUNC+IS_PRINT|SP_PRINTV|87,95,136,138,163,165|3848
PRINTVKL|12550|EXTENDED+METHOD_SAFE+PRINT_NEWLINE+ISPRINTKFUNC+IS_PRINT|SP_PRINTV|87,95,136,138,152,154,155|3849
PRINTVKW|13058|EXTENDED+PRINT_NEWLINE+PRINT_WAITINPUT+ISPRINTKFUNC+IS_PRINT|SP_PRINTV|87,95,136,138,158,160|3850
PRINTVL|8452|METHOD_SAFE+PRINT_NEWLINE+IS_PRINT|SP_PRINTV|87,95,152,154,155|3834
PRINTVN|8708|METHOD_SAFE+PRINT_WAITINPUT+IS_PRINT|SP_PRINTV|87,95,146,149,163,165|4131
PRINTVW|8960|PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT|SP_PRINTV|87,95,158,160|3835
PRINTW|8960|PRINT_NEWLINE+PRINT_WAITINPUT+IS_PRINT|STR_NULLABLE|87,117,158,160|3832
```

抽样人工核对（对 C# 原文逐行）：PRINT=8192|4（`:87`+`:165`）、PRINTL=8192|256|4（`:154,:155`）、
PRINTW=8192|768（`:160`）、PRINTK=8192|4098（`:138`）、PRINTSINGLE=8192|1026（`:92`）、
PRINTFORMC=8192|2|4（`:129-132` 的 `name == "PRINTFORMC"` 特例 + `:165`）、PRINTDATA=73746|4（`:235`+`:262`）。
位定义 `FunctionIdentifier.cs:3721-3753`；ArgBuilder 与 isa.yaml `arg_form` 的 per-alias 口径一致。

### 10.4 别名与大小写 4 条：拒绝修（与 C# 实为一致，差异来自命名口径）

`DO_NOTHING` / `METHOD` / `PRINT_DATA` / `SAVELOADGAME` 四条按 §9.3 的口径说明处理，**不改 yaml**：
`meta.note` 规定条目名 = 实现类名去 `_Instruction` 后缀，本就不是 funcDic 注册名；四个名字背后的真实注册名
均已被自身或相关条目覆盖——`DO_NOTHING`→注册名 `ENDDATA`（`FunctionIdentifier.cs:4019`，isa.yaml `aliases: ENDDATA` 条目在册）、
`SAVELOADGAME`→`SAVEGAME`/`LOADGAME`（`:3951-3952`，isa.yaml 该条目 aliases 在册）、
`PRINT_DATA`→`PRINTDATA*` 九个注册名（`:3902-3910`，本条目 aliases 在册）、
`METHOD`→方法并入循环（`:4135-4142`，由 builtin-functions.yaml 271 条 + 该条目散文覆盖）。
且「缺条目」计数为 0 ⇒ 没有任何注册名漏登。工具把「条目名型 ghost」与「别名串里的假名」分开计数，
后者为 0；这 4 行是口径说明不是缺陷，保留在计数里是为了让报表自解释。

### 10.5 工具修改（tools/spec_crosscheck.py，均有 C# 依据；`--json` / `--section` 两次连跑逐字节一致）

**10.5.1 构造函数重载选择（消掉 XML_REPLACE_BYNAME 假阳）。** `parse_method_classes` 现在收集类内**全部**
构造函数重载（`class_all_ctor_spans`，记形参个数）；`resolve_entry_facts` 按注册实参的顶层逗号段数选对应重载。
C# 语义依据：反编译里重载常在 `: this()` 之后整体重写 `argumentTypeArrayEx`
（`XmlReplaceMethod(bool byname)` `:1787-1805` 一份 4 槽 `OmitStart=3` 覆盖参数less ctor 三份），
只解析首个 ctor 会把按重载注册的名字读成基础 ctor 形态。同时按 `: this()` 先执行的语义做基 ctor 回退：
`ReturnType` 取基 ctor 的，实参表以重载为准（重载没写时才继承基 ctor）。
全量 diff 验证：302 个注册名里**只有** XML_REPLACE_BYNAME 的 C# 侧读数变化（2..4 → 3..4，与 yaml 一致），
其余条目（含 XML_GET/XML_SET/XML_REPLACE/SUMCARRAY/MAXCARRAY/MINARRAY/INRANGECARRAY/CSV*/TOUPPER/LOG10 等
全部多构造类注册名）读数不变——已逐一核对各重载 arity 与基础 ctor 相同。

**10.5.2 NameSuffixSim 变量错位 bug 修复 + 接入 `--flags`。** `_exec` 里 `_line, cond = item[2], item[3]`
把 cond 绑到了内层语句表（item[3]）而不是条件元组（item[2]），导致任何含 `if (name == "…")` 嵌套分支的
名字一律判「不支持」——修复前 86 个名字里 PRINTC/PRINTCD/PRINTCK/PRINTFORMC/PRINTFORMCD/PRINTFORMCK
六个 C 变体解不出（`FunctionIdentifier.cs:129-132` 正是给 PRINTFORMC 补 EXTENDED 的特例分支）。
修复后 86/86 可解，六个 C 变体值与原文手工核对一致（PRINTC=8196 无 EXTENDED、PRINTFORMC=8198 有）。
该类此前已定义但 main() 从未调用（死代码）；本波只把它接入 `--flags` 末尾的**模拟表**输出并明确标注
「未落 yaml，待 schema 决策」，**没有**接入缺口计数——yaml 不记录就不算消解，86 条缺口照报。

**10.5.3 CUSTOMDRAWLINE 的 CreateArgument 兜底**（工作区既有未提交改动，本波复核接受）：
`statement_arg_type` 在实现类内既无 `GetArgumentBuilder` 也无 `func.Argument` 强转时，记录
`public override Argument CreateArgument(` 行作为形态依据（CUSTOMDRAWLINE_Instruction，
`FunctionIdentifier.cs:514-528`），token 记 `CreateArgument`；isa.yaml 该条目 arg_form 同步含
`CreateArgument` 字样 ⇒ E1 命中 301 → 302、「实现类无静态形态」1 → 0。

### 10.6 新发现隐藏缺口（不在 §9.2 的 98 条内）——需组长拍板

跑 10.5.1 的重载选择时顺带发现：**XML_ADDATTRIBUTE / XML_ADDATTRIBUTE_BYNAME 的 yaml args 与 C# 不一致**，
但工具的 `resolve_entry_facts` 对 if/else 结构的构造函数不可解析（只认 case 标签），所以这两条从来没进过
差异清单——属隐藏缺口，本波**未改 yaml**（不在 98  mandate 内，且该族槽位命名需一并理顺）：

- yaml 现为 `doc, xpath, name, value[, index]`（判 4..5）。
- C# 真实形态：`XmlAddNodeMethod` Attribute 分支 6 槽 `OmitStart=3`（基础 ctor `:1365-1393`、byName 重载
  `:1423-1438`）⇒ 3..6；运行期第 3 位 name 必选（`:1566`）、第 4 位 value 可选（`:1567-1570`）、
  第 5 位 method 可选（`:1494-1499`，Attribute 时 num=5）、第 6 位 all-matched 开关可选（`:1538-1539`，num3=6）。
- 同族 XML_ADDNODE/XML_ADDNODE_BYNAME 的 arity（3..5）与 yaml 一致，但第 4/5 槽命名（value/index）
  与运行期含义（method/all-matched 开关）也对不上，建议下一波连同本族一起按 `:1492-1592` 重校。
- 工具侧若要让 if/else 结构可解析，需先处理 `if (typeisInt)` 这类**布尔条件**分支选择
  （GetConfigMethod，`:typeisInt` 实参 true/false 决定分支）——朴素按标签末段匹配会把 GETCONFIGS 选错分支，
  故本波不做，留待专项。

### 10.7 TODO(verify)

1. 10.3 表里 86 个 flag 为 NameSuffixSim 模拟值：文法与 13 个代表名（含 6 个 C 变体）已对 C# 原文逐行核对，
   但**未经运行期/实机对拍**；schema 拍板后落地时建议随 E2E 补一条 PRINT 族 flag 断言。
   （→ 2026-10-20 映射已按 §11 落 yaml；「模拟值未经实机对拍 + E2E 断言」仍是遗留，归 Kotlin 侧 E2E 装置路。）
2. 10.6 的 XML_ADDATTRIBUTE 家族槽位语义（第 5/6 槽名字与方法位含义）待下一波按 `:1492-1592` 重校后改 yaml。
3. 本 worktree 基线 HEAD（`6637010`）落后于 main（`1c6fee2`，colorsync 合流）；`specs/` 与 `tools/` 两目录
   main 侧无改动（`git diff HEAD main -- specs tools` 为空），合并无冲突面；未跑 gradle / commit / push（worker 纪律）。

## 11. 2026-10-20 FLAG-86 落地（flag_by_alias schema · 会话 main-c95a55de，wave/flag86）

**拍板：§10.3 三选一取方案 1** —— 条目级映射 `flag_by_alias:`（别名 → flag 值）+
`flag_source_by_alias:`（别名 → `"FunctionIdentifier.cs:行号,..."`），与条目级标量 `flag:` 并存
（单值条目一律不动）；拒绝方案 2（动全部 169 条目，代价最大）与方案 3（规约面永久不完整）。

### 11.1 落地面

- `specs/isa.yaml`：PRINT 条目增 `flag_by_alias`（77 名）+ `flag_source_by_alias`（77 名），
  PRINT_DATA 条目增 9 + 9；值与依据即 §10.3 快照表原样落地（`PRINT|8196`、`PRINTC|8196`、
  `PRINTCD|10246`… 抽查逐行一致），注释行注明模拟口径与 §10.3/§11 出处。
- `tools/spec_crosscheck.py`：
  - 新增 `per_alias_flag_crosscheck()`：yaml 映射 vs `NameSuffixSim` 逐名对拍，
    五类判定（缺键 / 值不符 / 依据行号不符 / 模拟不出 / 多键），输出全排序保确定性；
  - `--section` 的「flag 位缺失」改由对拍驱动（映射未落的条目即缺键清单，旧行为可复现）；
  - 新增 `--emit-flag-by-alias`：映射块落库/重生成素材（与 yaml 现状无关，C# 更新后整体重生成）；
  - `--flags` 模拟表尾增对拍读数行；`--flags` 帮助与模块 docstring 同步。

### 11.2 读数（本机 HOMEJIA · Python 3.13.14 · 受管 venv 仅跑工具，零依赖未破坏）

- `--section`：缺口 **flag 位缺失 86 → 0**（余 4 为别名与大小写口径，§10.4 已裁定非缺陷）；
  `per-alias flag_by_alias 86 / 86`；缺口总账 90 → 4。
- `--json` / `--section` 各连跑两次 `cmp` **逐字节相同**。
- `--emit-flag-by-alias` 输出与 isa.yaml 已落映射**逐行一致**（值行 156 行全等；差异仅注释文案，
  比较器按注释行剔除后比对）。
- 变异测试：`PRINT: 8196 → 8197` 后 `--section` 即报
  「`yaml flag_by_alias.PRINT=8197 与模拟值 8196 不符`」（缺口 1），还原后归零 ⇒ 对拍非摆设。
- `tools/golden-coverage.py` 在改后 isa.yaml 上跑通（别名抽取不受新字段影响，GOLDEN 13 文件 / 1,717 行）。
- 本波未触碰 Kotlin / render / core / `build.gradle.kts` / BOARD.md（BOARD 回执在交付合并提交里）；
  本路无 gradle 闸，未跑 gradle。

### 11.3 遗留

- ~~86 个值仍是**模拟值**，未经运行期/实机对拍~~ **已消解（2026-10-20 同日追加）**：新增
  `core-vm/src/test/kotlin/com/eratw/port/spec/PrintFlagByAliasTest.kt`（新包 `com.eratw.port.spec`，
  3 例）——移植侧 `PrintSpec.parse`（C# `PRINT_Instruction` 构造器 :85-171 的 Kotlin 逐行移植）
  算出的 flag 与 isa.yaml `flag_by_alias` 的 PRINT 77 名**逐名一致**（bit 级）；两族解析口径互斥
  （PRINTDATA 喂 PRINT 文法必须拒绝，C# :167-170 抛 ExeEE 的同构）；PRINT_DATA 9 名移植侧未建模
  flag（`PrintDataStmt` 行为走 code 身份），只断 FunctionCode 注册 + 表内覆盖。
  读数：`:core-vm:test --tests "*PrintFlagByAliasTest*" --configure-on-demand
  -Pkotlin.daemon.jvmargs=-Xmx6g` ⇒ **3 tests / 0 failed**（Gradle 8.10.2 + Temurin 17，
  47s 含模块全量编译；HOMEJIA）。真机/模拟器层面的 PRINT 行为对拍仍归设备路线（M4-SKEL-RT / M6）。
- C# 参考源更新（`reference_sha256` 变化）后：重跑 `--emit-flag-by-alias` 重生成映射块即可；
  对拍红 = 文法或值漂移，逐名修，不许手改模拟器迁就旧值；`PrintFlagByAliasTest` 会同步拦住
  移植侧漂移（规约表、Python 模拟器、Kotlin 移植三方互钉）。

---

## 12. 2026-10-21 ADR-005 偏离登记：串族 S4/S10/S11 + 文件族 F6（worker w-strrest，worktree `<work-root>/eratw-wt-strrest`，分支 wave/m6strrest）

同一波把 S7（`TOFULL`/`TOHALF`，2052/GBK 口径）与文件族 F1-F4（SAVETEXT 的 BOM、LOADTEXT 的
三段解码、`EXISTFILE` 的 `File.Exists` / `ENUMFILES` 的 `Directory.Exists`）落进产品后，
剩下四条**官方未捕获崩溃**照 ADR-005 逐条裁决为「**不复刻崩溃，保留可诊断路径**」：
异常一律落 `CodeEE`（TRYC 接得住）或给出可预期的返回值。四条都由审计测试钉住当前读数。

### 12.1 S4 `REPLACE` 字面模式（mode=2）的空模式串

- **结论**：原版 `strValue.Replace(arguments[1].GetStrValue(exm), arguments[2].GetStrValue(exm))`
  （`FunctionMethodCreator.cs:5684`）—— .NET 的 `String.Replace` 对 `oldValue` 长度 0 抛
  `ArgumentException`，而 mode=2 这条分支**没有 try/catch** ⇒ 未捕获崩溃（TRYC 接不住）。
  **本仓不复刻崩溃**：按 Kotlin 逐位插入语义给 `"-a-b-c-"`。
- **证据**：`FunctionMethodCreator.cs:5657-5688`（mode 分支 :5669-5686，:5683-5684 是 mode=2）。
- **现状 vs 原版**：`REPLACE("abc", "", "-", 2)` = `"-a-b-c-"` vs 原版进程级崩溃。
- **对策**：保留；卡点 = 崩溃不可对拍（无法把「进程退出」写进 E2E 读数）。
- **钉住**：`StringFamilyAuditTest` 的「REPLACE 字面模式空模式串与 mode=1 值路径 分歧 S4」。

### 12.2 S10 `UNICODEBYTE` 的空串

- **结论**：原版先按 UTF-32 求字节数再 `BitConverter.ToInt32(array, 0)`（`FunctionMethodCreator.cs:5735-5737`）；
  空串时 `array` 长度为 0 ⇒ `ArgumentOutOfRangeException`，**未捕获**。
  **本仓不复刻崩溃**：空串返回 0（可预期、可回归）。
- **证据**：`FunctionMethodCreator.cs:5732-5738`（:5735 建 0 长数组、:5737 取值）。
- **现状 vs 原版**：`UNICODEBYTE("")` = `0` vs 原版崩溃。
- **对策**：保留；卡点同 12.1。
- **钉住**：`StringFamilyAuditTest` 的「UNICODEBYTE 空串与孤立代理的两处崩溃不复刻 S10 S11」。

### 12.3 S11 `ENCODETOUNI` 指向低位代理

- **结论**：原版 `char.ConvertToUtf32(strValue, num)`（`FunctionMethodCreator.cs:5865`）：`num` 若指向
  代理对的**低位**码元，.NET 抛 `ArgumentException`（未捕获）。**本仓不复刻崩溃**：直接返回该码元
  （`ENCODETOUNI("😀",1)` = `0xDE00` = 56832），越界/负数分支仍按原版抛 `CodeEE`（:5857-5864）。
- **证据**：`FunctionMethodCreator.cs:5849-5866`。
- **现状 vs 原版**：`UNICODE("😀")` 一类不受影响；`ENCODETOUNI("😀",1)` = `56832`（低位代理 0xDE00）
  vs 原版抛 `ArgumentException`。
- **对策**：保留；卡点同 12.1。
- **钉住**：同 12.2 的用例（S10/S11 同测）。

### 12.4 F6 `ENUMFILES` 第 4 参不是字符串一维数组变量

- **结论**：原版 `((arguments[3] as VariableTerm).Identifier.GetArray() as string[])`
  （`FunctionMethodCreator.cs:370`）：第 4 实参若不是 `VariableTerm`，`as` 给出 null ⇒ 取
  `.Identifier` 立刻空引用崩溃，**无 try/catch**。**本仓不复刻崩溃**：抛可诊断的
  `CodeEE("函数 ENUMFILES 的第 4 个参数必须是字符串一维数组变量引用")`。
- **证据**：`FunctionMethodCreator.cs:352-373`（:370 是唯一取第 4 参的位置）。
- **现状 vs 原版**：`ENUMFILES("d","*.txt",0,"RESULTS")` ⇒ `CodeEE` vs 原版空引用崩溃。
- **对策**：保留；卡点同 12.1。同族对照：`REPLACE` mode=1 与 `RESUMETEXTBOX` 的类似强转已按原版抛
  `CodeEE`（:5675-5678），故本条与族内既有口径一致。
- **钉住**：`FileFamilyAuditTest` 的「ENUMFILES 第四参不是变量时抛 CodeEE 分歧 F6」。

## 13. 2026-10-21 ADR-005 偏离登记：CALLSHARP 装载期填充 + DT 族四条框架缺口 + DELCHARA 半死态（组长裁定，波次 M4-L/M4-M）

### 13.1 `CALLSHARP` 的常量实参在装载期被分隔线生成器填充（**原前提已证伪**，2026-11-05 改写为实测口径）
- **结论先说**：本节登记的「CALLSHARP 的常量实参在装载期被 `getStBar` 填成按控制台宽度的重复串」
  **不成立** —— 那段带 `getStBar` 的代码对 CALLSHARP 是**永不执行的死代码路径**。
  原前提于 2026-11-05 由 lane modelset 依 `FunctionIdentifier.cs:1173` + `ArgumentParser.cs:2680-2701`
  证伪；本路逐字回 reference 复核，确认推翻成立，并把本节改写为下面的实测口径（编号与位置保留）。
- **原登记（留档，勿再据以施工）**：机制段引 `FunctionIdentifier.cs:1179-1191`
  （`CALLSHARP_Instruction.CreateArgument`）里 `new SingleStrTerm(barStr)` 之前的
  `barStr = GlobalStatic.Console.getStBar(barStr);`（现号 **:1185**），以及
  `getStBar` 本体（`EmueraConsole.cs:3420-3437`：反复自我复制到显示长度 ≥ `Config.DrawableWidth`
  再逐字符回删；空入参时 `num` 恒 0 ⇒ `:3426` 的 while 无退出条件 = 原版死循环，与
  §「GETLINESTR 零宽死循环」同源）。**这些行号都对，代码也都在，但不可达。**
- **证伪链条（2026-11-05 实测行号，三段闭合）**：
  ① `FunctionIdentifier.cs:1169-1175` 的 `CALLSHARP_Instruction` 构造在 **`:1173`** 设了
    `base.ArgBuilder = ArgumentParser.GetArgumentBuilder(FunctionArgType.SP_CALLCSHARP)`；
    该值经 `AInstruction.cs:12`（`public ArgumentBuilder ArgBuilder { get; protected set; }`）→
    `FunctionIdentifier.cs:4203`（`arg = instruction.ArgBuilder;`）→ `:3779`
    （`public ArgumentBuilder ArgBuilder => arg;`）暴露成 `line.Function.ArgBuilder` ⇒ **非 null**。
  ② `ArgumentParser.cs:2679-2701`（`SetArgumentTo`）的 **`:2701`** 是三元分派：
    `line.Function.ArgBuilder == null ? line.Function.Instruction.CreateArgument(…) : line.Function.ArgBuilder.CreateArgument(…)`
    ⇒ ArgBuilder 非 null 时**只走 ArgBuilder**，`Instruction.CreateArgument` 的 override 永不被调。
  ③ 真正干活的是 `SP_CALLCSHARP_ArgumentBuilder`（`ArgumentParser.cs:795-861`，注册点 `:2642`）：
    `:800` 用 `ReadString(charStream, StrEndWith.LeftParenthesis_Bracket_Comma_Semicolon).Trim(' ', '\t')`
    读名并包成 `SingleStrTerm`；`:849` 建 `SpCallSharpArgment`；`:850-853` 在 `aExpression is SingleTerm`
    时置 `IsConst = true` / `ConstStr = aExpression.GetStrValue(null)` ⇒ **查表名是干净名**
    （4.981 真形 = `ModelSet`），全程**没有** `getStBar`；空串另走 `:854-858` 的 NotSpecifiedFuncName。
  ⇒ `FunctionIdentifier.cs:1177-1191`（带 `getStBar` 的那个 override）只服务于**没有** ArgBuilder 的调用形态，
  对 CALLSHARP 不可达。
- **移植侧现状**：`core/stmt/ArgBuilders.kt:1027-1032`（`callSharp`，KDoc 自述 spec 段与官方同形）
  就是 `readString(…).trim(' ', '\t')` 直接建 `StrLiteral`、无填充步 ⇒ 移植侧本来就与③一致。
  （原登记写的 `ArgBuilders.kt:647-660` 行号已搬家，那个位置现在是 `strDouble`／TOOLTIP 族，不是 CALLSHARP；
  另该 KDoc 自称的 `ArgumentParser.cs:877-943` 也已过期，现号 `:795-861`。）
- **裁定 = 撤销「复刻填充」的重评条件**，不是改判为修。原重评条件「若将来有语料/插件用到 `CALLSHARP`，
  或做装载期控制台条宽统一通道（与 P6 同批），则按 §13 的机制复刻填充并同步 `isa.yaml`」作废两条理由：
  ① **前提已被 4.981 触发**：语料有 3 处 `CALLSHARP`（`ERB/DLC/AI对话补丁/对话调用.erb:35`（ModelSet）、
    `:36`（Chat 2 参）、`:54`（Chat 9 参，整段被 `:45`–`:56` 的 `[SKIPSTART]`/`[SKIPEND]` 包住 ⇒ 预处理期
    不产逻辑行，spec: `ErbLoader.cs:408-411` + `EraStreamReader.cs:130`），可达原生名只有 `{ModelSet, Chat}`；
    4.980 的**游戏子树** 0 命中（全树只有 readme 散文两处：
    `原版+前人整合等各种readme（口上模板及口上教程在内）/更新内容 4.956~汉化.erb:556/:558`）；
    宿主桩 `app/…/GameHost.kt` 的 `wireCorpusNativeMethods` 已上线。
  ② **照旧条件施工会直接复发上报**：`getStBar` 填充后的串必然不命中桩的注册名（`ModelSet`/`Chat`），
    屏上退回 `No native method … found` —— 即 0.2.39 那一簇。原「登记不修」的三条理由里，①（语料零命中，
    指 4.980）已过期，②③ 因本条根本不该做而失去意义。
- **本语句余下的两处真分歧**（都不在本节修，登记在 `specs/isa.yaml` 的 CALLSHARP 条目注释块，
  该条目因本次补登现起于 `isa.yaml:1587`）：
  (a) **报错相位** = **已复刻（B1a，2026-11-06，SMALL-BX 路）**：官方在联结期
    `FunctionIdentifier.cs:1193-1210` 的 `:1204` 用
    `ParserMediator.Warn($"No native method {…} found", func, 2, isError: true)` 只记进
    `line.IsError/ErrMes`（`ParserMediator.cs:125-136` 的 `:129-130`），运行期走到该行才由
    `Process.cs:1017-1019` / `:1030-1032` 抛 `CodeEE(ErrMes)`。移植侧的注册表挂 `store.aux`、
    宿主装配晚于语料装载（`app GameHost.kt:3476`）⇒ 装载期注册表恒空，照抄原版相位会误杀
    已接桩的 ModelSet/Chat（0.2.39 上报簇的复发方向）；故裁决点后移到「该行第一次执行」
    （宿主装配在 drive() 之前，此刻注册表已是终表），机制 = 行级联结记录
    `fn/PluginMethods.kt` 的 `SharpLinkTable`（未命中记行错并抛、命中绑定方法本体、
    **此后不重查** = 原版绑死 CallFunc/IsError），执行期抛**记录值**；
    flat 生产路 `StatementExecutor.resolveSharpMethodFlat`（行键 = 语句池下标）与旧树
    对拍路 `resolveSharpMethodTree`（行键 = 实参对象身份）两通道同构。const 名判定 =
    `funcNameTerm is StrLiteral`（原版名字恒 SingleStrTerm ⇒ IsConst 恒真，
    `ArgumentParser.cs:800`/`:850-853`，SetJumpTo 的 `!IsConst` 分支对 CALLSHARP 不可达）。
    **英文文案原样、不是待翻译项**（ADR-005）。构造钉 = `CallSharpLinkPhaseTest`。
  (a2) **装载期警告面** = **已复刻（CSHARP-WIRE，2026-11-06）**：SMALL-BX 登记的「残余相位差」
    —— 原版 `warningList` 装载完统一上屏（`ParserMediator.cs:143-158` FlushWarningList）、
    **该行不执行也见警告** —— 在本波落地。装配序收口为会话装配段内的三段严格序：
    `wireCorpusNativeMethods`（注册终表）→ `fn/PluginMethods.kt` 的 `prefillSharpLinks`
    （装载趟裁决）→ `drive()`（app `GameHost` 装配段；「本地方法表在装载趟扫描时已可用」由
    本序保证 —— 注册表挂 `store.aux`、store 在 GameSession init 出生，注册物理上不可能早于
    容器解码，见 prefillSharpLinks 的 KDoc 证据链）。扫描 = `Program.sharpStatementIndices`
    的**记录标签字节扫**（语句记录首字节 = `StmtTag.CALL_SHARP`，`ErbcFormat.kt:284`；erbc
    惰性档逐条读标签字节、命中候选才物化，source 编译档按 args 形态识别 —— 两条路径同一份
    裁决代码）；未命中收集 `No native method … found`（`:1204` 文案原样）按语句池序返回 =
    原版装载序，宿主经既有日志通道统一上屏（`Log.w`；会话读数面 = `GameSession.sharpLoadWarnings`，
    形态同 `erdWarnings`）。裁决落笔后**不重查**（幂等跳过已裁决行 = 原版绑死 CallFunc/IsError），
    首执裁决（(a) 的机制）保留为兜底通道（未跑预填的装置/非常量名行），两通道写同一张表。
    **残余呈现差（有意，登记不修）**：原版 `PrintWarning` 在控制台上屏（警告样式 + 源位跳转钮，
    `EmueraConsole.cs:3064-3089`），移植侧呈现面 = logcat 日志 + 会话读数清单 —— 控制台上屏
    要动文案表（三语）与按钮面，超出本波「既有装载警告/日志通道」的口径（契约 C3）。
    构造钉 = `CallSharpLoadPrefillTest`（source/erbc 两路径 × flat/树通道 × 幂等不重查）。
  (b) **变量实参回写的常量闸**：官方**无**常量闸（`FunctionIdentifier.cs:1218-1232` →
    `VariableTerm.cs:108-129`/`:131-152` → 存储层只 `array[i] = value`，`VariableData.cs:1340-1344`；
    官方的 const 实参拦截只在解析期 `ArgumentBuilder.cs:92-105`，CALLSHARP 不经它），
    移植侧闸在存储层（`UserVariables.kt:1925`/`:1957` 抛「变量 … 是常量，不能赋值」）。
    **本基线（4c2bf23e）实测：回写循环 `StatementExecutor.kt:2687-2691` 对每个 VarRef 无条件回写
    （`writeVarRef`/`writeVarRefStr` 只是透传，`:4865-4871`）⇒ 常量实参位会抛错，而不是跳过**；
    且真语料可达 —— `对话调用.erb:35` 的三个实参 `模型API`/`模型Key`/`模型名称` 全是 `#DIMS CONST`
    （`AI返回参数.ERH:6/:7/:8`）。lane modelset 的解法（回写侧跳过常量实参位，件名 `writeBackNativeArg`
    + 真语料钉 `ChatActionRealCorpusReproTest`）**不在本基线**，见本路交付「需组长接线」。
- **同批账面项（2026-11-05 复测：已对齐，可销账）**：原文记 `ADDSPCHARA`/`CALLSHARP`/`FORCE_BEGIN` 三条在
  `core/stmt/FunctionCode.kt:114/:134/:182` 的 flag 位为 0，而 `specs/isa.yaml:639/:1533/:1884` 记 4/38/1。
  本路实测：`FunctionCode.kt` 的构造签名是 `(argType, flag, kind)`（**:167**），现号
  **`:175` ADDSPCHARA = 4**、**`:195` CALLSHARP = 38**、**`:243` FORCE_BEGIN = 1** ⇒ 与 isa.yaml 一致，
  账面分歧已消；原文的 `:114/:134/:182` 行号同时搬家。isa.yaml 一侧因本路补登位移 +64，
  现号为 `:639`（ADDSPCHARA，未移）/ `:1597`（CALLSHARP）/ `:1948`（FORCE_BEGIN）。

### 13.2 DT 族四条 .NET 框架侧缺口（2026-11-05 主人指令推翻原裁定：B/D/G/J 全量复刻，波次 DT-FRAME）
- **裁定变更**：本节原「B 复核维持不修正 / D·G·J 钉桩 + TODO(verify)」由主人 2026-11-05 指令推翻，
  四条按 .NET 框架真实行为复刻（B 与 D 同波、G/J 随收；实施于 worktree `<work-root>/eratw-wt-dt`）。
  框架侧行为的证据由 **dotnet 8 探针**取数（netcoreapp8.0 与游戏运行时同源 ——
  `reference/emuera-cs/Emuera.csproj:7`；临时工程不落仓，探针输出行标 A1…/B3…/C…/D2…/J1… 直接引用）。
- **分歧 B（`DT_COLUMN_ADD` 不扩老行）＝ 已复刻**：`:2118 Columns.Add` 是表级操作 —— 探针 A1/A3
  实测对有行的表加列后，**老行新格恒 DBNull**（数值列与字符串列同），不是类型零值；移植侧
  `dtColumnAdd` 补上按 `emptyCell`（DefaultValue → DBNull，见分歧 D 的同一条派生规则）的回填循环。
  旧「6 处裸下标越界」的病根（行宽不随列数走）随之消除。
- **分歧 D（不可空列预填）＝ 已复刻；旧登记名「预填 0」被探针证伪**：NewRow 对不可空列同样
  DBNull（探针 A5），`:2318 Rows.Add` 当场抛 `NoNullAllowedException`（探针 A5b：行不落表）
  ⇒ **PC 上「预填 0」不可观测**（该行加不进去）；「数字列读 0、字符串列空串」的真实来源是
  取值函数对 DBNull 的合并面（DT_CELL_GET→0 = `FunctionMethodCreator.cs:2486-2490`、
  GETS→"" = `:2536-2541`、ISNULL→1 = `:2484-2485`）。移植侧 `emptyCell` 收成**一条派生规则**
  （`DefaultValue`，未设即 DBNull），非空约束在四处提交边界复刻：`Rows.Add`（`dtRowAdd`）、
  `AllowDBNull` setter（`dtColumnAdd`：有行时置 false 抛 `DataException`，探针 A2，且列**已在表内**、
  保持可空 —— 探针 A2b 部分状态一并复刻）、单元格 DBNull 写（`dtCellSet`，探针 D2/D2b）、
  ReadXml 装载（探针 J13）。设了 DefaultValue 的不可空列填默认值放行（探针 D3）。
  抛出形态全部按分歧 C 的裸 CLR→裸 JVM 映射（`IllegalStateException` / `ClassCastException`，
  非 CodeEE）。
- **分歧 G（`DT_CELL_GETS` 按 id 的 `(string)` 强转）＝ 已复刻**：原版 byId 分支是
  `(string)obj` 直接强转（`FunctionMethodCreator.cs:2529`），装箱整数强转抛 `InvalidCastException`
  （探针 C），链路无 try（`FunctionMethodTerm.cs:20-23` → `Process.cs` 非 EmueraException 分支）
  ⇒ 想定外中止；行号寻址走 `obj.ToString()`（`:2538`）不经此路。移植侧由 fn-data-B1 的 CodeEE
  折面改抛裸 `ClassCastException`（同分歧 C 的 `IndexOutOfBoundsException` 先例）。
  语料可达性（T6 普查，4.980/4.981 各 12 处）：全部带类型闸（`TEMP_CHARA_LIST_BY_DT_TYPE定義.ERB`
  CASEELSE 的 `DT_COLUMN_EXIST(...) == 5` 才走 GETS）或直打 xs:string 列 ⇒ 强转中止 **0 处可达**，
  构造用例钉（`MapDataAuditTest`）。
- **分歧 J（`DT_FROMXML` 表名）＝ 已复刻；「按 `msdata:MainDataTable` 卡名」的前提被探针证伪**：
  `DataTable.ReadXmlSchema` **不消费** `msdata:MainDataTable`（探针 J1/J6：指向不存在的 "ghost"
  也静默无视，`TableName` 保持构造名、列照建）；ERB 名匹配不到**表元素名**时抛
  `ArgumentException`（探针 J3/J4/J7）→ `:2749-2752` catch → 0；装载后 `TableName` 不变，
  后续 `ReadXml` 按它匹配行元素（探针 J2/J8：行元素名不匹配 ⇒ 静默 0 行，不是错误）。
  移植侧维持「按表元素名匹配、MainDataTable 不参与」（wave/audit-dt 5703341fc 的口径与
  .NET 一致，本波补探针证据）；畸形面同步复刻：根元素名不校验（探针 J9）、空根 0 行（J12）、
  缺不可空列元素且无 DefaultValue → 0（J13）、重复主键 → 0（J14）、有 DefaultValue 填默认值放行（D3）。
  语料可达性：`DT_FROMXML` 4.980/4.981 各 2 处（`BBAS_DATASET.ERB:11/:17`），schema 形态正是
  `MainDataTable="db"` + 表元素/数据行 `bbas_dataset` ⇒ 卡名实现必坏语料，探针与 PC 实测（160 行）
  同向。
- **分歧 C（`DT_COLUMN_NAMES` 越界静默截断）＝ 已修**（M4-M m-dtcolnames）：原版循环上界是
  `Columns.Count`、与目标数组长度无关（`:2079`），体内 `array[i] = …`（:2081）⇒ 越界抛
  `IndexOutOfRangeException` 且 `:2083` 的 return 走不到（**整个函数无返回值**）；抛出形态是**裸 CLR 异常**
  （`FunctionMethodTerm.cs:20-23` 无 try、`VariableTerm.cs:57-106` 原样 `throw;`、
  `Process.cs:651-660` 的非 EmueraException 分支），故移植侧抛 `IndexOutOfBoundsException` 而非 CodeEE。
- **分歧 J15（`Rows.Add` 重复主键）＋ setCell 省参 DBNull 对称闸 ＝ 已复刻/不加闸（RESIDUE-BX2，2026-11-06）**：
  DT-FRAME 收口时登记的两条相邻分歧，dotnet 8.0.425 探针取数（`<work-root>/residue-probe/probe-output.txt`）。
  - **J15 ＝ 已复刻（裸抛）**：`:1999 Unique = true` + `:2001 PrimaryKey` 的表在 `:2318 Rows.Add`
    对撞抛 `ConstraintException`（探针 R3a "Column 'id' is constrained to be unique.  Value '1'
    is already present."——unique 后双空格原样），异常后行不落表（R3b）；`:2749-2752` 的
    `catch { return 0L; }` 只在 `DataTableFromXmlMethod` 内、**不**覆盖 `DataTableRowSetMethod`
    （无 try）⇒ 按分歧 C 的裸 CLR→裸 JVM 映射抛 `IllegalStateException`，不是折 0。
    空约束先于唯一约束（探针 R3g：重复主键 + DBNull 不可空列并存报 NoNullAllowed）⇒ 移植侧
    `dtRowAdd` 空循环在前、主键闸在后；无主键表不设闸（探针 R3c）。对撞用例：DT_FROMXML 载入
    「下一自产 id」的行再 `DT_ROW_ADD`（`MapDataTableMethodsTest`，`nextRowId` 严格递增只管本池自产 id）。
  - **setCell 省参 DBNull 对称闸 ＝ 不加（官方不可达）＋ 空位实参翻案为解析期拒绝**：`:2215` 的
    `v == null → row[name] = DBNull.Value` 只对 DT_CELL_SET（OmitStart = 3，:2568 起的 ArgTypeList）
    可达，其不可空闸移植侧已有（探针 R4a/R4b 复证 D2/D2b：附加行 DBNull 写入当场抛
    `NoNullAllowedException`、原值不动；新行写入不抛、约束在 Rows.Add 边界落，探针 R4d/R4e/R4f）。
    成对/数组形态的 ArgTypeList **无 OmitStart**（缺省 -1，`FunctionMethodCreator.cs:2140-2155`/
    `:2168-2187`）⇒ 空位实参在原版**解析期**就被 `CheckArgumentTypeEx` 拒绝
    （`FunctionMethod.cs:198-205` ⇒ `ArgCanNotBeNull`，`Lang.cs:3734`，zhs
    `{0}函数: 第{1}个参数不能省略`），DBNull 写入根本不可达 ⇒ **对称闸不加**（ADR-005 不硬造）。
    翻案面：移植旧实现把空位实参静默写成 DBNull（DT_ROW_SET 侧直接污染附加行不可空列），
    已改为运行期同文案 CodeEE（`dtRowAdd`/`dtRowSet` 成对循环，钉子 `MapDataTableMethodsTest` ×2）；
    `setCell` 的 `:2215` 镜像分支按原文保留（成对/数组形态到达前已被拒，与 C# 不可达性同构）。
- **对照不动项**：`MAP_GETKEYS` 的两处截断 break 是原版自带（`FunctionMethodCreator.cs:2987-2995`），
  **不是**分歧，别跟着一起「修」。

### 13.3 `DELCHARA` 数组参失败路径的「已 Dispose 仍在表内」半死态（**已复刻**，B5 翻案落地，SMALL-BX 2026-11-06）
- **裁定变更**：本节原「登记不修」由主人 2026-11-05 指令推翻（B 类登记分歧全部翻案复刻），
  2026-11-06 落地于 worktree `<work-root>/eratw-wt-small`（`wave/small-bx`）。成功路径与数组参越界文案
  `System.Int64[]`（:1259，官方 bug）原样保留未动，单参重载（:1241-1249，判界先于 Dispose）
  本就无半死态，也未动。
- **原版机制（逐行回读）**：`VariableEvaluator.cs:1253-1273` **边校验边 `Dispose()`**（:1267），
  全部校验通过后才 `CharacterList.Remove(item)`（:1269-1272）⇒ 第 k 个元素越界/重复
  （:1257-1260/:1262-1265）抛 CodeEE 时，前 k 个角色**已释放但仍仍在表内**。
  `CharacterData.Dispose`（`CharacterData.cs:756-770`）只置空本实例的
  `dataIntegerArray / dataStringArray / dataIntegerArray2D`（系统 1 维/2 维角色变量的存储本体），
  **不写共享状态**；0 维的 `dataInteger[2]`（ISASSI/NO）与 `dataString[4]`
  （NAME/CALLNAME/NICKNAME/MASTERNAME）以及用户 CVAR 的 `UserDefCVarDataList`
  （`CharacterData.cs:85`/`:124`，Dispose 不触碰）**不在释放面内**。后续读半死行的
  1 维/2 维变量踩置 null 的数组 ⇒ .NET `NullReferenceException`（非 CodeEE）。
- **移植侧落地**：`CharacterTable` 加逐行 `disposed` 位（SoA 列存的「已释放仍在表内」表达），
  结构操作全随行维护（removeAt 左移搬位、swap 换位、reorder 重排、addBare/resetChar 清位、
  clear 全清、ensureCapacity 扩容）；`VariableStore.deleteCharacters` 在校验循环内置位、
  抛错不回滚（= :1267 的时序）。**读路径 NRE 等价闸** = `CharacterTable.requireSystemRowAlive`，
  接进 `VariableStore` 的 `Storage.C_INT1/C_INT2/C_STR1` 读写六处 + `setAll/setAllStr`
  （VARSET 族整段写）三处 + `CharacterTable.copy`（COPYCHARA/ADDCOPYCHARA 的整行搬运，
  原版 `CopyTo` spec: CharacterData.cs:239-349 读写三族数组）；0 维与用户 CVAR 列**不过闸**
  （与释放面同档），半死行照常读写旧值。抛裸 `NullPointerException`（裸 CLR→裸 JVM 映射，
  口径照 §13.2 分歧 C 先例）。
- **可观测性边界（已核，写死）**：该 CodeEE **不可捕获** —— ERB 无通用 try 块，TRY* 家族只在
  自己的语句执行里查 `func.JumpToEndCatch`（`FunctionIdentifier.cs:3284`/`:3583-3585`/`:3705-3707`），
  DELCHARA 的 CodeEE 从 `DoInstruction`（`Process.cs:1047`）直穿顶层（`Process.cs:462-476`）→
  `ConsoleState.Error` ⇒ 同一会话不再有后续语句（`EmueraConsole.cs:757-768`/`:1090-1100`）；
  移植侧 TRYC 家族同为调用/跳转目标捕面 ⇒ **存档面永远看不到半死行**（两侧同构）。
  假想强行存档的官方形态也已核并记档：`CharacterData.SaveToStreamBinary`
  （`CharacterData.cs:513-558`）把置 null 的三族数组（:532/:535/:538）传给
  `EraBinaryDataWriter.WriteWithKey`，其 `is` 类型链（`EraBinaryDataWriter.cs:83-173`）对 null
  全部落空 ⇒ **静默不写该条目**（0 维 :526/:529 与用户 CVAR :553 照写）。移植侧存档面维持整行照写
  （可达流上零观测差）；若将来接调试控制台打开可达流，按「只写 0 维 + 用户 CVAR」补
  （TODO(verify)，卡点 = 该面未实现）。
- **钉子**：`DelCharaOverloadTest` 单元 6 翻案重钉 —— 旧断言「校验期零副作用、前缀角色完好可读」
  拆成：0 维/用户 CVAR 照常读写（原读数逐条保留）、1 维/2 维读写 NRE（新断言）、
  半死位随行左移、成功批次不制造/不清除他人半死位、COPYCHARA 过闸、SWAP 不过闸。

### 13.4 SMALL-BX 同批登记：CharaCsvLoader 文件序（B6）与 SaveLoadHandoff functionList（B7，销账）
- **B6 = 已复刻（2026-11-06）**：原版 `Config.getFiles`（`Config.cs:654-712`）对本层文件与子目录
  各自 `Array.Sort` 排**全路径**（:681/:697），comparer = `Comparer<string>.Default` =
  `String.CompareTo` = **当前文化**；深度优先前序不变（文件循环 :683-689 先于目录递归 :690-704）。
  移植侧 `CharaCsvLoader.visit` 由「同层 ordinal 文件名序」改为 ja-JP 文化序
  （`PATH_COLLATOR` = `java.text.Collator.getInstance(Locale.JAPAN)`，TERTIARY = 大小写敏感）。
  文化取值证据链：reference 全树对 `CurrentCulture` 零处改写（只有 InvariantCulture 的内部键
  大写化）⇒ 进程文化 = 宿主 OS 文化；游戏原产/预期环境是日文 Windows ⇒ 钉 ja-JP，
  不读设备 Locale（否则文件序随设备漂移）。JVM 探针实测分叉例（Temurin 17，2026-11-06）：
  `CharaAa.csv < CharaAB.csv`（文化序 a&lt;b 主权重；ordinal `B`(0x42)&lt;`a`(0x61) 反序）、
  `Charaア < Charaい`（五十音 a 行先于 i 行；ordinal 反序）。
  TODO(verify)：.NET NLS 与 JVM ICU 的 ja-JP 排序键非同源实现，残余差异面（长音符 `ー`、
  浊点组合、全角/半角宽）未经 .NET 8 探针对账 —— 与 §16.3-1（SORTCHARA 字符串键）同一卡点；
  语料 168 个 CHARA CSV 文件名全 ASCII（2026-10-22 实测）⇒ 差异当前不可达。
  钉子 = `CharaCsvLoaderTest` 新增 3 例（子目录 ja-JP 序 + 根层文件先出 + ASCII 混合分叉；
  同字母大小写分叉在 NTFS 上不可构造，已改用 NTFS 安全形）。
- **B7 = 已等价，销账（2026-11-06）**：`SaveLoadHandoff.kt:29` 登记的「functionList 复制面」
  经对拍核证**不是欠账**。原版 `ProcessState.Clone()`（`ProcessState.cs:475-484`）只复制
  `currentLine`/`sysStateCode`/`begintype`（+ `isClone` 位），`functionList` 是字段初始化
  （`:16`）的**新空表** —— 原版 Clone 本就不复制 functionList；`saveCurrentState`
  （`Process.cs:1959-1966`）换上这个空表实例 ⇒ `ScriptEnd`（`:34`，`functionList.Count ==
  currentMin`，clone 的 `currentMin` 缺省 0）立即为真 ⇒ `DoScript` 外层
  `while (state.ScriptEnd && console.IsRunning) runSystemProc()`（`Process.cs:445-452`）回到
  系统态处理 —— 语义就是「整栈丢弃 + 回系统态」。移植侧
  `ProcessRuntime.executeStatement` 读 `takeFunctionListCleared()` 返回 `StmtOutcome.Unwind`
  （整栈丢弃）+ `beginStep`/`resumeSystem` 取 `takeSystemState()` 换态 —— 与原版逐项同读，
  **不是**「逐帧弹到调用方」。按契约禁止事项，等价信号**不**重写成字面复制；
  `SaveLoadHandoff.kt` 的登记文字本就写的这套（`:27-32`/`:67-70`），无需改动。
---

## 14. 2026-10-21 条目名型 ghost 消解：缺口总账 4 → 0（worker w7-specgap，worktree `<work-root>/eratw-wt-w7-specgap`，分支 wave/w7-specgap）

`tools/spec_crosscheck.py --section` 剩下的缺口只有「别名与大小写口径」4 条：`DO_NOTHING` /
`METHOD` / `PRINT_DATA` / `SAVELOADGAME`。§10.4 已裁定它们不是缺陷（条目名 = 实现类名去
`_Instruction` 后缀，本就不是 funcDic 注册名），但报表一直把它们算在缺口里。本波把这个口径变成
**工具可核对的登记**：四条转入 `--section` 的「已解释」块并逐条印出 C# 依据行，五类缺口全部归零。

### 14.1 判定（三条全成立才放行，任一不成立照旧报缺口）

- `specs/isa.yaml` 顶层 `entry_name_kind` 把该名登记为 `class_derived`（本波新增；块放在文件
  **末尾**，不动任何既有行号 —— `OpcodeTableTest`、`ButtonAcceptance` 等按行号引用本文件）；
- `FunctionIdentifier.cs` 里确有 `<条目名>_Instruction` 实现类 ⇒ 派生命名成立，不是别名串笔误；
- 该条目 aliases 里至少有一个 funcDic 注册名 ⇒ 注册面确被覆盖。`METHOD` 例外：它没有语句别名，
  注册面是把全部函数方法名并入 funcDic 的循环。

另查「失效登记」：登记名若已是 funcDic 注册名、或根本不是 isa.yaml 条目，落「别名与大小写口径」
缺口（纯文本报告 `A4d`），避免登记表退化成免检白名单。

### 14.2 四条依据（C# 行号逐条回查）

| 条目 | 实现类 | 注册面 |
|---|---|---|
| `DO_NOTHING` | `DO_NOTHING_Instruction` `FunctionIdentifier.cs:1959` | 注册名 `ENDDATA` `:4019`（条目 aliases 在册） |
| `METHOD` | `METHOD_Instruction` `:584` | 无语句别名；方法并入 funcDic 的循环 `:4135-4142`（builtin-functions.yaml 271 条、缺条目 0） |
| `PRINT_DATA` | `PRINT_DATA_Instruction` `:231` | `PRINTDATA*` 9 名 `:3902-3910`（经 `addPrintDataFunction` `:3817-3820`） |
| `SAVELOADGAME` | `SAVELOADGAME_Instruction` `:2956` | `SAVEGAME` / `LOADGAME` `:3951-3952` |

### 14.3 工具改动（`tools/spec_crosscheck.py`）

- 新增 `classify_entry_name_ghosts()`：返回 `(explained, gaps, stale)`，三者全排序；
- `report["diff"]` 增 `explained_entry_names` / `unexplained_entry_names` / `stale_entry_name_kinds`
  （`--json` 可 diff）；`ghost_isa_entry_names` 保留原义（原始名单，便于回溯）；
- `--section`：缺口表不再列条目名型 ghost，表后另起「已解释」块（条目 / 实现类 / 依据行 / 说明），
  计数行增「已解释（条目名去后缀口径，不计缺口）」；
- 纯文本报告：`A4a` 改口径为「已解释」，新增 `A4c`（未登记或 C# 无实现类 ⇒ 仍报缺口）与
  `A4d`（失效登记）。

### 14.4 读数与变异测试（HOMEJIA · Python 3.14.3 · 零第三方依赖不变）

- 改前 `--section`：缺口 4（缺条目 0 / 参数形态不符 0 / flag 位缺失 0 / 别名与大小写口径 4 /
  引用行号存疑 0；变量缺口 0/0）。
- 改后：**五类缺口全 0**，已解释 4；命中读数一字未动（isa.yaml 169 条目 / 307 名、builtin 271、
  register 引用 179、方法 source 类 271、语句 arg_form 302、variables 158、per-alias flag 86/86）。
- `--json` / `--section` / `--flags` / `--emit-flag-by-alias` 各连跑两次**逐字节一致**。
- 变异（临时改 isa.yaml，跑完即还原，还原后读数回到 0）：
  1. 删 `PRINT_DATA: class_derived` ⇒ 该条回缺口、已解释 3；
  2. 加 `PRINT: class_derived`（已是 funcDic 注册名）⇒ stale 1、缺口 1；
  3. 加 `NOSUCH: class_derived` ⇒ stale 1；
  4. 清空整块映射 ⇒ 四条全部回缺口 ⇒ 判定不是硬编码白名单。
- `tools/golden-coverage.py` 在改后 isa.yaml 上读数不变：语句 300/303、函数 252/271、13 文件 1,717 行。
- 本路无 Kotlin 面，未跑 gradle；未 commit / push（worker 纪律）。

### 14.5 遗留

- `entry_name_kind` 目前只定义 `class_derived` 一个取值。将来若出现别的派生命名口径（例如来自
  `Process.cs` 而非实现类的条目），须另立取值并同步判据 —— 未知取值不得默认当「已解释」，
  现行实现对此按「未登记」报缺口。

---

## 15. 2026-10-24 编译层缺陷收口 CMP-76/86/87/88（worker cmp-l14，worktree `<work-root>/eratw-wt-cmp-l14`，分支 `wave/cmp-l14`）

> 本路独占文件面（CONTRACT-CMP §3 L14）：`specs/verify-notes.md`、`specs/loader.md`、
> `specs/erbc-format.md`（只提需求不落笔）、`core-vm/src/test/**/StrictUtf8Test.kt`。
> **CMP-87/88 的改点在源码/测试注释里，不在本路面**：结论与**替代文案**给在 §15.3/§15.4，
> 由组长接线落笔（清单 §15.5）。本路只跑定向 `:core-vm:test --tests "*StrictUtf8Test*"`，
> 未跑全闸、未 commit / push、未动 BOARD.md。

### 15.1 CMP-76（P0 文档）：§4 重写

- 改前：§4「编码探测 DetectEncoding」断言官方 1.824 无任何探测、读固定 Shift-JIS、
  我们的 DetectEncoding 是「画蛇添足版定制或移植层自创」，并据此建议「读取固定 Shift-JIS 复刻」。
- 改后：§4 按「`Runtime.Utils.DetectEncoding`（读脚本/数据）vs `Config.Encode`（写侧/调试侧）」
  两条路重写，每条给「证据（文件:行号）+ 结论 + 边界」，无 confidence 措辞；明确写出
  **官方有探测逻辑、移植侧跟随 DetectEncoding 是对的**，并禁止按旧结论改回固定 Shift-JIS。
- 复算读数：`DetectEncoding` **14 处 / 9 文件** = 9 直接调用 + 2 定义（`EncodingHandler.cs:15`/`:43`）
  + 1 包装函数名（`Preload.cs:19`）与其 2 调用点（`Preload.cs:63`/`:72`）；
  `Config.Encode` **6 处**全为写侧/调试侧；`SystemSaveInUTF8` 在 reference **0 命中**。
- 旧引证逐条证伪见 §4 开头引用块（`Config.cs:17-19` 实为 `UTF8BOMEncoding`；`EraDataStream.cs` 不存在；
  `EraStreamReader.cs:42`/`AppContents.cs:91`/`DebugDialog.cs:270` 行号不指该语义；等）。
- 新发现的**边界**（不是缺陷）：`Process.cs:738`/`:766`/`:767` 三个 lazyloading 索引文件用固定
  `Encoding.UTF8`，`EmueraConsole.cs:3531` 的 `File.ReadAllText(x)` 与 `JSONConfig.cs:21` 也不走探测
  —— 三者都不是脚本/数据主路，已写进 §4.1 边界节。

### 15.2 CMP-86：`StrictUtf8Test` 改为手写判据表（不再由同族解码器生成期望值）

改前（同义反复）：
- `:28-33` 的 `capture` 与 `:35-39` 的 `upstream`（= `bytes.decodeToString(..., throwOnInvalidSequence = true)`）；
- `:47-52` 的 `check` 用 `upstream` 生成期望值 ⇒ 手写校验器与 JDK 同步错也绿。

改后：
- **手写字节表**。合法 13 例：ASCII、`0x7F`(DEL)、U+0085(NEL)、U+FEFF、C2 80、DF BF、E0 A0 80、
  U+D7FF、U+E000、U+10000、U+10FFFF、U+1F600、U+4E2D。非法 17 例：代理项 `ED A0 80`/`ED BF BF`、
  超长 `C0 80`/`C1 BF`/`E0 9F BF`/`F0 8F BF BF`、越界 `F4 90 80 80`、孤立续字节 `80`/`BF`、
  `FE`/`FF`、截断 `C3`/`E4 B8`/`F0 9F 92`、5 字节 `F8 …`、6 字节 `FC …`、合法前缀后中段非法。
  另有 `from`/`len` 切片 6 例（含「从代理对中间起切」）。
- 期望值来源：RFC 3629 最短形式 + Unicode 码位（表里直接写 `\uXXXX`）；异常按手写
  `java.nio.charset.MalformedInputException: Input length = N` 文本（Temurin 17.0.20.1 实测记录；
  `ErbcStreamEquivalenceTest` 比的就是「类型 + 消息」）。
- 对照 `StrictUtf8.kt:49`（合法 ⇒ `String` 快路径，逐码位比值）与 `:51-55`（非法 ⇒ 冷路径委托 JDK 抛，
  另断言属 `CharacterCodingException` 家族）；同时对 `StrictUtf8.kt:63-134` 的 `isWellFormed`
  直接断言（合法必 true / 非法必 false），避免只测到委托路径。
- 对照 `EncodingHandler.cs:9`（`new UTF8Encoding(..., throwOnInvalidBytes: true)`，`:67-71`
  `ExceptionFallback`）：非法字节**必须抛**，表里逐条断言「未替换成 U+FFFD」。
- 随机样本 24,000 全随机 + 24,000 近似合法：**只断结构不变量**、不再生成期望值 ——
  `isWellFormed == true` ⇒ `decode` 不抛且重新编码逐字节等于输入；`isWellFormed == false` ⇒ `decode` 必抛。
  同时钉「过松」（放过非法序列 ⇒ `String` 快路径替换 U+FFFD ⇒ 回编码不等）与「过严」
  （JDK 能解却不抛 ⇒ 红）。
- 定向读数：`./gradlew --configure-on-demand :core-vm:test --tests "*StrictUtf8Test*"`
  ⇒ **4 tests / 0 failed / 0 skipped**（0.745 s，HOMEJIA）。
- 变异验证（证非空转）：把 U+0085 的期望值手改成 U+0084 ⇒ **1 test failed**
  （`AssertionFailedError`，`StrictUtf8Test.kt:91`）；还原后文件与绿版逐字节相同。

### 15.3 CMP-87：5 条 `TODO(verify)` 回读 reference 的结论

行号复算：BOARD 给的 5 个行号全部按字面命中（`grep -n 'TODO(verify)'` 逐文件核过）；
`LineParser.kt` 同段有第二条（`:537`），一并处置。

| # | 位置 | 现表述（摘） | reference 回读（文件:行号） | 判定 |
|---|---|---|---|---|
| 1 | `core/stmt/ArgBuilders.kt:1157` | 「原版 ReadInt64 是否同样拒绝 `10px` 待对照」 | **原版确实拒绝**：`LexicalAnalyzer.cs:66-68` 把数字后的 `p` 当 2 底指数 ⇒ `readDigits(st, 10)` 读到 `x` 得空串（`:100-111`）⇒ `:140-152` `long.Parse("")` 抛 FormatException ⇒ CodeEE。`px` 只有在是**独立 IdentifierWord** 且后随 `,`/行尾时才终止项（`ExpressionParser.cs:534`，`(TermEndWith)34 = KeyWordPx|Comma`，`ArgumentParser.cs:57`/`:124`）⇒ 官方接受 `10 px`、拒绝 `10px` | **销账**；口径改为「超集规避」，挂 CMP-53 同族 |
| 2 | `core/stmt/LineParser.kt:535` | 「常量下标 / 默认值类型匹配（ErbLoader.cs:685-695 / 724-741）尚未复刻」 | 两半不同：**默认值类型匹配已复刻** —— `parseLabelDefault`（`LineParser.kt:656-672`）按 `ErbLoader.cs:724-741` 的 `SingleTerm` + 同型判据，不一致不注入（原版是 Warn+置错标签，`ErbLoader.cs:769`；本移植沿用「不新增错误等级」既有口径）；**常量下标校验已于 2026-10-24 复刻（CMP-85A，N2 路）** —— `extractLabelParamRefs` 按顶层 `:` 分段、逐段 `reduceIntegerTerm` 归约、结果必须 `IntLiteral`，否则解析期抛 `ArgSubscriptOnlyConst`（`ErbLoader.cs:687-695`、`:691-693`） | **已复刻**（`ArgHasNotSubscript` 的无下标分支仍待核；语料 0 命中） |
| 3 | `core/stmt/LineParser.kt:537` | 「方括号形参 `@F[…]`（ErbLoader.cs:613-640，isDefine:false 的常量参数）未复刻；语料 0 条」 | 出处应扩到 `:613-648`：`ReduceArguments(RightBracket, isDefine:false)`（`:617`）后空表 ⇒ `CanNotEmptyFuncSBrackets`（`:618-621`）；逐项 `Restructure` 必须成 `SingleTerm`，否则 `ArgCanOnlyConst`（`:624-640` → `:762-764`）；尾随杂字 ⇒ `WrongArgFormat`（`:641-648`） | **改成准确表述**（行号 + 三支错误） |
| 4 | `core/expr/Parser.kt:447` | 「无解析器（独立单元测试）时的回退……原版此处直接抛未定义标识符」 | 原版确在 `ExpressionParser.cs:444-445` 抛「未定义的标识符」；**生产不可达**：`resolver == null` 只出现在单测装置（`LineParser.kt:90` 缺省 `StrictSymbolResolver`；`ArgBuilders`/`ParseSupport`/`VarMethods`/`ProcessRuntime` 等生产调用点全传非空） | **销账**（改写成「装置特权」） |
| 5 | `core/lexer/LexicalAnalyzer.kt:793` | 「整行 Analyse 用 AllowAssignment，与原版 ParseLine 的 None 不同（待验）」 | 原版**没有**「整行 AllowAssignment」形态：`LogicalLineParser.cs:536-556` 是「`Analyse(Operator, None)` 截左值 + `ReadAssignmentOperator` 另读赋值符」；`None` 下裸 `=` 由 `ReadOperator` 抛 `UnexpectedEqual`（`LexicalAnalyzer.cs:429-439`），赋值符只由 `ReadAssignmentOperator` 产出（`:559-565`）。移植侧语句层已两段式（`stmt/LineParser.kt:295-300`），`tokenizeLine` 全仓只被 test 调用 | **销账**（改写成接口口径注记） |
| 6 | `core/compile/ErbProgramCompiler.kt:2109` | 「CALL/JUMP 在 #FUNCTION 方法内一律 CodeEE 是**运行期**错误……故此处不 throw」 | **表述正确**：`CalledFunction.cs:98-109`（`CallFunction` 判 `nonEventLabel.IsMethod` 抛 `CallToUserFunc`；装载期不拦）；移植侧 sem 层已有四处抛点：`ProcessRuntime.kt:653`（`_CALL_BY_NAME`）、`:1205`（TRY 系）、`:1229`（CALL 系）、`:1986`（事件帧后 CALL） | **销账** |

**第 2 条「常量下标」面的语料可达性实测**（`ERATW_CORPUS` = ERB 子树 3,971 文件全量）：
ERB 里函数标签形参的下标**全是十进制字面量** —— 三类模式各 0 命中：
`^\s*@NAME\s*\([^)]*[A-Za-z_]\w*\s*:\s*[A-Za-z_(]`、`…:\s*\d+\s*[+\-*/%<>&|^]`、`…:\s*[-+]`；
ERH 不产生 `@` 标签（`ErhLoader.cs` 无标签分支，见 `loader.md` D.2 第 1 条：`ErhLoader.cs:104-120`）
⇒ 未复刻的常量下标校验**当前语料不可达**。
另：方括号形参 `@F[…]` 的形态 `^\s*@[A-Za-z_0-9]+\s*\[` 同样 **0 命中** ⇒ 第 3 条原文「语料 0 条」经复算成立。

**替代文案（交 owner 落笔，逐条可直接替换）**

`ArgBuilders.kt:1152-1158`（KDoc 第 4-7 行 + TODO）：

```kotlin
    /**
     * 按顶层逗号切分参数原文（引号/小括号/中括号内的逗号不切）。
     * 用于带 `px` 单位的参数（PRINT_IMG / PRINT_RECT / PRINT_SPACE）：原版的 `px` 是**独立的
     * IdentifierWord**，只能与数字用空白隔开（`10 px`），且必须后随 `,` 或行尾
     * （spec: ExpressionParser.cs:534，`(TermEndWith)34 = KeyWordPx|Comma`，ArgumentParser.cs:57/:124）。
     * `10px` 在官方词法层就报 CodeEE —— ReadInt64 把数字后的 `p` 当 2 底指数
     * （spec: LexicalAnalyzer.cs:66-68），readDigits 读到 `x` 得空串（:100-111、:140-152）。
     * 因此这里按文本切分是**超集规避**（接受官方会拒绝的 `10px`），不是待验项；
     * 逐字复刻须改词法器（同 CMP-53 的登记面）。
     */
```

`LineParser.kt:535-538`（两条 TODO → 一段准确表述）：

```kotlin
     * 形参项的原版校验面（spec: ErbLoader.cs:672-742）本遍的复刻程度：
     * - `:681-685` 不是变量 / 常量名 ⇒ ArgCanOnlyAssignableVar：已复刻（:598-618）；
     * - `:687-695` 无下标 / 非常量下标拒绝（ArgHasNotSubscript `:777-778`、
     *   ArgSubscriptOnlyConst `:774-776`）：**非常量下标已复刻**（CMP-85A：解析期
     *   `reduceIntegerTerm` 归约 + 必须 IntLiteral，否则抛 ArgSubscriptOnlyConst）；
     *   无下标分支仍待核；实测本语料函数标签下标全为十进制字面量（三类非常量模式 0 命中）；
     * - `:716-741` 默认值：空值按形参类型折 0/""（:717-722，已复刻 `parseLabelDefault`），
     *   非空值必须是 SingleTerm 且与形参同型（NotMatchTypeArgAndInitialValue `:737-741`）——
     *   已复刻为「不一致则不注入」（:668-670）；原版还会 Warn 置错标签（`:769`），
     *   本移植沿用既有「解析期抛/不注入、不新增错误等级」口径。
     * - `:613-648` 方括号形参 `@F[…]`（isDefine:false）：`ReduceArguments(RightBracket)`
     *   （:617）后空表 ⇒ CanNotEmptyFuncSBrackets（:618-621）、逐项必须折成 SingleTerm
     *   （否则 ArgCanOnlyConst，:624-640 → :762-764）、尾随杂字 ⇒ WrongArgFormat（:641-648）。
     *   **未复刻**：本遍一律按 WrongArgFormat 拒绝；实测 3,971 文件语料 0 条。
```

`Parser.kt:446-451`（回退分支）：

```kotlin
        if (resolver == null) {
            // 无解析器（独立单元测试）时的回退——按变量引用处理，操作数类型假定 INT。
            // 原版此处必抛「未定义的标识符」（spec: ExpressionParser.cs:444-445），但生产路径的
            // resolver 恒非空（LineParser.kt:90 缺省 StrictSymbolResolver；ArgBuilders / ParseSupport /
            // VarMethods / ProcessRuntime 等生产调用点全传非空）⇒ 本分支只服务单测装置，
            // 是装置特权，不是未验证的运行时口径。
            val fallback = VarInfo(name = idStr, operandType = OperandType.INT, dimension = 0, isCharacterData = false)
            return reduceVariable(fallback, subKey)
        }
```

`LexicalAnalyzer.kt:789-794`（行级接口 KDoc 的 TODO）：

```kotlin
    /**
     * 普通代码行整行分析（调试 / 全行 Token 流接口）。
     *
     * 原版没有「整行 AllowAssignment」的形态：赋值行是两段式 ——
     * `Analyse(stream, LexEndWith.Operator, None)` 截左值，再 `ReadAssignmentOperator` 单独读
     * 赋值符（spec: LogicalLineParser.cs:536-556）；`None` 下裸 `=` 会由 `ReadOperator` 抛
     * `UnexpectedEqual`（spec: LexicalAnalyzer.cs:429-439），赋值符只能由
     * `ReadAssignmentOperator` 产出（`:559-565`）。语句层必须复刻两段式
     * （移植侧 `stmt/LineParser.kt:295-300` 即此式），本方法只为调试/测试提供全行 Token 流，
     * 因此用 ALLOW_ASSIGNMENT 让 `=` 通过；**不得**拿它当语句层词法入口。
     */
```

`ErbProgramCompiler.kt:2109-2113`（TODO → 结论 + 落点）：

```kotlin
            // CALL/JUMP 在 #FUNCTION 方法内是**运行期** CodeEE，不是装载期：原版 CallFunction
            // 先查事件名、再判 nonEventLabel.IsMethod 抛 CallToUserFunc（spec: CalledFunction.cs:98-109），
            // 装载期不拦 —— 因此编译期直接拒绝会误杀原版可装载的语料切片，此处不 throw；
            // 由 sem/proc 层按当前帧 isMethod 抛：ProcessRuntime.kt:653（_CALL_BY_NAME）、
            // :1205（TRY 系）、:1229（CALL 系）、:1986（事件帧后 CALL）。
            // PARTIAL(16) 方法体 / SIF 后禁用同理属语句层（spec: EmueraConsole.cs:2216-2219、:3049-3052）。
```

### 15.4 CMP-88：两处「把未证实当已证」的判定

判据（ADR-005）：只允许复刻**有 C# 出处**的官方 bug；「语料实测 0 例」≠「原版如此」。
两处都**缺**「官方如此」的出处，但**各有**「官方实为另一行为」的出处 ⇒ 处置 = 按 §12/§13 的
ADR-005 **偏离登记**口径改口径，不得再写成「按现状钉＝官方口径」。

**(a) D-CMP88-1：`LexerAuditTest.kt:307`「块内全是空行 ⇒ 仍产出一条空 CODE 行」**

- 出处证明的是**官方丢弃该行**：`EraStreamReader.cs:181-183`（合并续行后 `SkipWhiteSpace`，
  到 EOS 也照样返回 charStream）+ `LogicalLineParser.cs:387-391`（`ParseLine` 先
  `SkipWhiteSpace`，`stream.EOS` ⇒ `return null`）⇒ 该块**不产生任何逻辑行**。
- 移植侧在块分支直接 `classify` ⇒ 产出一条 `content == ""` 的 CODE 行 = **移植侧偏离**，
  当前语料不可观测（「块内全是空行」0 例），但登记性质是「偏离 D-CMP88-1」，不是「官方 bug 复刻」。
- 建议替代文案（`LexerAuditTest.kt:303-307`）：

```kotlin
        // C# 侧：ReadEnabledLine 合并后 SkipWhiteSpace，到 EOS 也照样返回 charStream（EraStreamReader.cs:181-183），
        // 随后 ErbLoader 走 LogicalLineParser.ParseLine，后者 EOS 时 return null（LogicalLineParser.cs:387-391）
        // ⇒ 原版对该块不产生任何逻辑行。
        // 移植版在块分支直接 classify（LogicalLineReader 的 readLogicalLine 块分支），于是产出一条 content 为空的 CODE 行。
        // **这是已登记的移植侧偏离 D-CMP88-1（specs/verify-notes.md §15.4(a)），不是官方复刻**：
        // 语料实测「块内全是空行」0 例 ⇒ 当前不可观测；修法 = 块折叠后 SkipWhiteSpace 若 EOS 则返回 null，
        // 归读行层 owner（LogicalLineReader）。
```

**(b) D-CMP88-2：`AstralWidthTest.kt:24`「把现状逐值钉死」（三层格数 4 / 2 / 4）**

- 出处证明的是**官方没有「格」概念**：折行索引空间是 UTF-16 `Char`（`PrintStringBuffer.cs:453-496`
  在 `text.Length` 上二分、`:471`/`:483-486` 用 `Slice(0, num4)` 量前缀宽；`ConsoleStyledString.cs:54-73`
  的 `DivideAt(index)` 直接 `Substring`），宽度是 GDI 像素（`StringMeasure.cs:42-72`，
  `TextRenderer.MeasureText` / `MeasureCharacterRanges`）。一对代理项在 GDI 眼里是**一个字形**，
  既不是 4 格也不是 2 格；CP932 口径（`EmueraConsole.cs:3255` 的 `GetByteCount`）对 U+10000 以上
  也给不出 4。⇒ 三层整数读数都是**移植侧近似**，维持现状是产品决策（契约 M4-F §2 出路 b），
  **不是**「原版如此」。
- 该文件 `:26-34` 其实已如实写出「官方是像素宽度、两个整数读数都是兜底近似」；
  缺的是**偏离登记**这一步与 `:15`/`:24` 的措辞。建议把 `:15` 的标题与 `:24` 一句改成：

```kotlin
 * ## 口径性质：已登记的移植侧近似 D-CMP88-2（不是 ADR-005 的「官方 bug 复刻」）
 *
 * ADR-005 只允许复刻**有 C# 出处**的官方 bug；本文件的整数格数没有 C# 出处 ——
 * 官方折行索引空间是 UTF-16 Char（PrintStringBuffer.cs:453-496，:471 取 Slice(0, num4) 量前缀宽；
 * ConsoleStyledString.cs:54-73 的 DivideAt 直接用 Substring），宽度是 GDI 像素
 * （StringMeasure.cs:42-72）。因此「逐码元 4 格 / 逐码位 2 格 / 折行 4 格」三层都是**移植侧近似**，
 * 偏离登记见 specs/verify-notes.md §15.4(b)；本文件只把现状逐值钉死，作为该偏离的可执行形式，
 * 并把「三层对同一字符串各给多少格」列成参数化表。
```

### 15.5 需组长接线（源码/测试注释改点，不在本路文件面）

| 文件:行号 | 动作 | 依据 |
|---|---|---|
| `core-vm/.../core/data/csv/CsvLoader.kt:24-25` | 删掉「官方 1.824 无编码探测、固定 Shift-JIS、属超集行为」整句，改为「官方即此口径（`EncodingHandler.cs:15-41`）」 | 旧 §4 的直接产物；新 §4 已证伪 |
| `core-vm/.../core/stmt/ArgBuilders.kt:1152-1158` | KDoc + TODO 替换 | §15.3 第 1 条替代文案 |
| `core-vm/.../core/stmt/LineParser.kt:535-538` | 两条 TODO 替换 | §15.3 第 2/3 条；注意 `LineParser.kt` 是 Q2 `q2-core-06` 独占面，本轮不得动 |
| `core-vm/.../core/expr/Parser.kt:446-451` | 回退分支注释替换 | §15.3 第 4 条 |
| `core-vm/.../core/lexer/LexicalAnalyzer.kt:789-794` | 行级接口 KDoc 替换 | §15.3 第 5 条 |
| `core-vm/.../core/compile/ErbProgramCompiler.kt:2109-2113` | TODO 注释替换为结论 + 落点 | §15.3 第 6 条 |
| `core-vm/src/test/.../lexer/LexerAuditTest.kt:303-307` | 注释改「已登记偏离 D-CMP88-1」 | §15.4(a) |
| `render/src/test/.../grid/AstralWidthTest.kt:15`/`:24` | KDoc 口径改「已登记偏离 D-CMP88-2」 | §15.4(b) |

> 第 2 行 `CsvLoader.kt` 属装载面（CONTRACT-CMP 无在飞认领）；第 3 行 `LineParser.kt` 明属 Q2，
> 由组长决定并轨还是留待 Q2；其余按 CONTRACT-CMP §3 的独占面判定。

### 15.6 本路未做 / 遗留

- 同批另两件规格改动：`loader.md` 新增 §7「文本解码（装载链的编码口径）」（把 §4 的结论落到装载链逐点）；
  `erbc-format.md` 新增 §12「侧车字节条目的需求清单」（**需求不是正文**，正文落笔归 L02）。
- 未跑全闸、未 commit / push、未动 BOARD.md；语料与 `reference/` 一字节未改。
- `StrictUtf8Test` 的异常文本是 JDK 17.0.20.1 的实测记录：JDK 升级若改文案，该表会红 ——
  这是有意的跨版本绊线，**不得**改回「用 `decodeToString(strict)` 生成期望值」。
- CMP-88 的两处偏离**没有修行为**，只登记口径（修 `LogicalLineReader` 丢弃空块行、
  统一增补平面格数口径都属跨路改造，由组长按成本裁）。


---

## 16. 2026-10-26 fixcore 审查波：九路深审修复与登记（组长 fixcore，main@cb2012b0 之上）

九路并行审查（vm-core / vars / fn-strings / fn-data / loader-erbcc / save / render / 横切+RNG / 8红根因）
产出 A×3、B×15+、C×12+；产品修复与登记如下。审查报告全文在工作区 `.review/review-*.md`（不入库）。

### 16.1 已修复（行为对齐原版）

1. **重复 `$标签` 首个生效**（ErbProgramCompiler.defineLabel）：C# TryAdd（LabelDictionary.cs:342-350 +
   ErbLoader.cs:463-468）；旧实现抛异常 → 整函数空体桩；语料 @AFFAIR_DISCLOSURE `$INPUT_EMBRACE`（:67/:210）
   可达。erbcc 产物须全量重编。
2. **QUIT 家族整链停止**（ProcessRuntime）：原版只置 ConsoleState.Quit、不做 state.Return
   （Process.cs:1307-1309 → EmueraConsole.cs:733-735）；旧实现 Stop(0) 在嵌套调用里只拆一帧。现 Unwind(0)。
3. **erbcc reader 接 `SystemAllowFullSpace`**（CorpusCompile 等 5 构造点 + CorpusScan trim 口径）：
   eraTW_PATCH_NOTE_0.ERB 的 4 个幻影 `@函数` 此前进产物且 FuncMeta 重键；修后与原版装载读数对齐。
4. **RESETDATA / 回标题清 SAVE 扩展池**（StatementExecutor RESETDATA + ProcessRuntime.gotoTitle）：
   C# ResetData 第一步 RemoveEMSaveData（VariableEvaluator.cs:1338 → VariableData.cs:4006-4029）。
5. **LOADDATA 先复位后解析**（FileSystemOps.loadData）：VariableEvaluator.cs:2625-2626 的顺序。
6. **LOADCHARA payload 损坏收敛 CodeEE**（CharaFileIO.load）：原版 FileEE 受控进 Error（Process.cs:462-476）。
7. **角色块条目序改 varTokenDic 序**（CharaFileIO.captureCharacter → allInVarTokenDicOrder）：
   specs/save-format.md §5.3 的改档重写逐字节目标。
8. **DT_CELL_GETS byId+整数列中止**（MapDataTableMethods）：FMC.cs:2529 `(string)obj`；语料全打字符串列，零影响。
9. **XML_ADDNODE/ADDATTRIBUTE method=0 打非元素目标中止**（XmlMethods）：FMC.cs:1444-1448 / :1468-1476；
   语料全打元素路径，零影响。
10. **TOUPPER/TOLOWER 全映射**（PureMethods）：.NET ICU 全映射（ß→SS、ﬁ→FI、İ→i̇）；旧 KDoc
    「1:1 不扩长」前提不成立。语料 TOUPPER 18 处 / TOLOWER 134 处，ASCII 键面读数不变。
11. **ARRAYMSORT/EX 整数比较器复刻回绕**（VarWriteMethods）：FMC.cs:590/:5117 `Math.Sign(a.Key-b.Key)`；
    字符串键 CurrentCulture 序未复刻（见 16.3-1）。
12. **TIMES 溢出钉 Long.MIN_VALUE**（StatementExecutor 两条路径）：x64 cvttsd2si 整数不定值
    （FunctionIdentifier.cs:1339-1341；JLS 5.1.3 饱和正溢出给 MAX_VALUE，两侧分叉）。
13. **ARRAYSHIFT shift=Int.MIN_VALUE 报错**（StatementExecutor）：原版 Math.Abs OverflowException
    （VariableEvaluator.cs:722/:732）。
14. **VARSET 负 END + 缺 START = no-op**（StatementExecutor）：交换仅当 Start!=null
    （FunctionIdentifier.cs:1642-1656）。
15. **MesSkip 吞掉后无条件清标志**（WAIT / WAITANYKEY / PRINT W 位 / TWAIT 四处）：
    EmueraConsole.cs:1456；语料 INPUT canSkip 0 处，当前零可观测面，纯对齐。
16. **立绘死条目不画**（ScreenComposer.drawSprite isCreated 守卫）：EmueraConsole.cs:1844。
17. **GOTO 动态标签缺失文案对齐 zhs**（Vm.kt + ProcessRuntime.resolveLabel）：emuera-zhs.xml:540。
    **11-05 更正并真修**（lane labeldollar `e956db74`）：这一条当时只对齐了 `{0}` = 求值名**原文**，
    **漏了模板自带的字面 `$`**（zhs 串是 `标签名"${0}"`）——Kotlin 把 `${...}` 之外的 `$raw` 当插值，
    sigil 就此丢失，屏上少一个 `$`（PC 给 `名"$TAIL"`，移植侧给 `名"TAIL"`）。现两侧都走
    `core/err/LabelErrorText.kt` 的单一真源（`notDefinedLabelNameText` / `notDefinedLabelName`），
    判据与「求值名自带 `$` 时官方给双 `$`、照抄不修」写进 `ProcessRuntime` 三处 KDoc 与
    `specs/isa.yaml` 的 GOTO 条目；逐字钉在 `core/code/LabelResolutionFidelityTest`（12 例）。
    同条目还核清两件事：作用域 = **当前执行的那条函数定义实例**（`CalledFunction.cs:204-207` +
    `LabelDictionary.cs:382-389` 内层键是 `FunctionLabelLine` **对象引用**，未覆写 `Equals`）；
    官方**不存在** `$CATCH` 标签概念（TRY 未命中跳的是装载期 TRYC 配对链写的 `JumpToEndCatch`，
    `ErbLoader.cs:1339-1358`；纯 TRY 恒 null = 跳过本行）。
18. **erbcc SJIS 回退改 windows-31j**（LogicalLineReader + Symbols）：CP932=windows-31j
    （EncodingHandler.cs:11），NEC 选中区不再 U+FFFD；严格回退未复刻，见 16.3-4。
19. **erbcc `[KW A,B]` 终止符语义**（ErbPreprocessor）：ErbLoader.cs:390-406；语料三形态 0 例。
20. **erbcc 裸 `.uppercase()` → `Locale.ROOT`**（17 处）：tr/az 宿主折叠。
21. **GETTIME RESULTS / SAVETEXT 文件名钉 Locale.ROOT**（StatementExecutor / HostMethods）：
    与 TimeMethods.kt:207 同口径。
22. **specs/rng.md §4.2 笔误更正**：NextUInt64 **高 32 位先取**（MTRandom.cs:48）；代码与对拍向量一直正确。
23. **SaveDataProbe KDoc 两段过时「残留缺口」更正**（checkSaveData 已传 useSaveFolder；peek<16 已修）。

### 16.2 已复核为「上游已修 / 无缺陷」（勿重报）

- BOARD 红K 行的 core-vm 4032/8 红：全部为 27285e07（px 词法级对齐）的下游夹具红，58ee2129+486ee723
  已修——本波在 cb2012b0 实测 GraphicsSinkInjection 8/0、PrintRectColor 7/0、M11eExpandE2E 2/0。
  `300px` 粘连形放行 vs 原版词法必拒的分歧**已翻案落定（B4，SMALL-BX 2026-11-06）**：原版词法
  必拒有 C# 链证（数字字面量由 ReadInt64 吃进 LexicalAnalyzer.cs:765，`readDigits` 后游标停在
  `p`，:66-68 当 2 进制指数标记、ShiftNext 后指数位非数字 ⇒ 第二次 readDigits 得空串 →
  `long.Parse("")` 抛 FormatException → :149-152 转 CodeEE(CanNotConvertToInt)），
  移植侧词法器同链条（core/lexer/LexicalAnalyzer.kt），`spaceOutPxUnit` 放行近似已撤销、
  粘连文本原样进词法层即同形拒绝（`LexError ""を整数値に変換できません`）；钉子 =
  `Cmp53PxArgTest`（原「粘连形与空格式同解」断言翻案为词法必拒）+ `GraphicsSinkInjectionTest`
  语句改空格形（读数不变）。
- M10-SAVE 六条 TODO(verify) 全部已修（save 路逐条核销）。
- 语料 DT_SELECT 实测全在已落子集内（`=` `<` `>` `>=` `<>` + `AND` + `ASC`，BUILD_FILTER 生成器同型）
  —— fn-data §5.1 的 A 级疑点销账。
- RNG 三方对拍（specs/rng.md ↔ Sfmt19937.kt ↔ MTRandom.cs）逐行一致；L3 依赖面（randSeed 注入 +
  纯 SFMT + drawProbe 缺省零行为）成立。

### 16.3 登记不修（偏离 / 待证）

1. **ARRAYMSORT/SORTCHARA 字符串键排序**：C# `string.CompareTo`=CurrentCulture（FMC.cs:5132/:606、
   CharacterData.cs:772-780）vs 移植 ordinal —— 日文键可分叉；需 .NET 8 ja-JP/zh-CN 序例探针后定
   （复刻或 ADR-005 登记）。相等键 C# List.Sort 不稳定 vs Kotlin 稳定，实现定义面，一并登记。
2. **TOSTR/MONEYSTR 的 C/P 格式**：C# 走 CurrentCulture（FMC.cs:5491/:3908；zh-CN 实测 `¥12,345.00`、
   P 三位小数无空格），移植钉 invariant（¤ / 括号 / " %"）—— 主受众屏上可见偏差；接线宿主 locale
   或登记 ADR-005，待组长拍板。
3. **UNICODEBYTE 尾随孤立高位代理**：原版 flush:false 字节计数=0 ⇒ BitConverter 崩溃；本仓按 §12
   政策返 0xFFFD（登记 + PureMethods KDoc 已更正）。
4. **erbcc SJIS 严格回退未复刻**：原版坏字节 → 整文件 FailedOpenFile 跳过（EncodingHandler.cs:67-71 +
   EraStreamReader.cs:50-59）；移植 REPLACE 继续。语料可达性未测（TODO(verify)）。
5. **HTML_PRINT …,1 串中段 `<br>` 不切行**（core/host/LineBufferConsoleSink.htmlVisibleText）：
   原版逐项 AppendButton 含 null 项、Flush 真切行（EmueraConsole.cs:3211-3216 +
   PrintStringBuffer.cs:209-216）；语料 12 处 option=1 无一证实中段 br（TODO(verify)）——
   机制成立但改动触及全部 HTML 缓冲面，缓修。
6. ~~**`<button/nonbutton pos='N'>`（LockPointX）渲染期未消费**（HtmlRunFlatten）~~
   —— **翻案（POS-WIRE，2026-11-06）：显示期消费已接**。解析/承载/往返三面照旧
   （FONT-MEASURE F3：`HtmlStrictParser` 的 pos 判型与 nobr/左对齐校验 HM :1521-1537/:683-697、
   `HtmlPrintButton.pos` 承载、`HtmlPopTooltipPosTest` 逐字节往返），其上 render `HtmlRunFlatten`
   现按原版语义落位：`pos='N'` 随 `cssToButton` 落 `LockPointX`（HM :848-851 →
   `PointX = rel_px * Config.FontSize / 100`，ConsoleButtonString.cs:157-163），展平层在 pos 组
   首段前插**带号 `InlineGraphic.Space` 间隔段**——目标列与 img 尺寸同一把「字号百分比 →
   半角格」尺（`graphicSize`）；游标接力照 `setWidthToButtonList` 的 `pointx = PointX + Width`
   （PrintStringBuffer.cs:393-411）自然累计，锁定点在当前游标之前时用**负宽间隔段回退**
   （原版不夹取、后组叠画在前组上；语料 `カラム機能.ERB:169` 一行三列 pos=0/2050/0 非单调）；
   折行判 `PointX + Width <= DrawableWidth`（PrintStringBuffer.cs:217）的 PointX 项由间隔段
   承载，nobr 直印行照旧不折行。空组不锁（HM :659,:675 的 `list.Count > 0` 守卫）；间隔段
   不带按钮身份 / tooltip / 底色（gap 不是节点，命中区从锁定列起）。toPrintBuffer 混排行
   以行内前序占宽为游标种子（原版锁定值随按钮进打印缓冲、随普通 Flush 一起排游标）；
   div 剔块协同：pos 组在被剔除区间内时主行无间隔段（区间在适配层源文本上整段剥除），
   div 子行的 pos 相对**子行左缘**落位（原版递归子解析 HM :636-642 的 buttonList 从 0 起
   排；`SetAlignment` 平移不豁免锁定按钮，ConsoleDisplayLine.cs:62-90）。钉子 =
   `HtmlRunFlattenPosTest`（13 例：语料锚 + nobr + 接力 + 回退 + 零/负 pos + 空组 + 身份 +
   畸形值 + 混排 + div 两面）。
   **登记偏差（ADR-001 网格近似同族）**：① 锁定列取整到半角格——原版保像素并留
   `XsubPixel` 亚像素进位（ConsoleButtonString.cs:160），列位最多差 1 格；② 网格叠列与
   PC 逐像素叠画在遮盖序上不逐像素一致（负 pos / 回退形态）。TODO(verify)：真机
   `カラム機能` 屏与 PC 截图对拍列位（卡点：本机无 WinForms 取证）。
7. **实参求值序 lazy vs eager**（fn-data C2 / vm-core C5，TINPUT/DT_CELL_SET/GETKEY 等）：仅副作用
   实参可观测，语料无常量外形态。
8. **BAR 填充字符未走 Config.BarChar1/2**（StatementExecutor.createBar）：本作配置未改，现值一致。
9. **SPLIT 空分隔符**：.NET Framework 行为未取证（移植按单元素）；TODO(verify) 一行实测可销账。
10. **REGEXPMATCH 4 参强转崩溃不复刻**（本仓抛可诊断 CodeEE）+ **TOSTR 精度护栏 1,000,000**
    （原版 999,999,999）：同 §12「官方未捕获崩溃不复刻」政策，钉在既有审计测试。
11. **EXISTSOUND 路径逃逸加固**（HostMethods/SoundResourcePaths）：有意收窄，语料无越界形态。
12. **erbcc scanLabels 丢 `{}` 续行块**（Symbols.kt:806-812）：布局面 dimSpecs 缺块内 #DIM（语料 65 条）；
    REF_in_block=0 ⇒ 当前产物无损；块内 #DIM REF 将来出现即触发 CMP-25 同款失效——潜在雷，待重排。
13. **erbcc 诊断静默两处**（scanLabels 无归属 # 行 Warn InvalidSharp、fileEnd 行号 0）：补留痕即可，缓。
14. **WINDOW_TITLE = <int> 失败文案 / 字符串关键字下标空串先报序**（vars C1/C2）：错误路径文案差，
    归 error-texts 统一波。
15. ~~**`<button title>`（tooltip）未落**~~ —— **翻案（FONT-MEASURE F3，2026-11-06）：已落**。
    渲染链 `HtmlRunFlatten` 的 `TextRun.tooltip`（`<button title>` / `<nonbutton title>` 开标签
    取值，属性值内 `<br>` 也认）→ `ButtonLayer.tooltip(row,col)` → app `CanvasConsoleView`
    长按（`tooltipAt`）→ ShellUi `ButtonTooltipOverlay` 浮窗。平台对应物偏离 = **长按代替
    hover**（Android 无鼠标悬停，ADR-005 在册）。POP 往返侧：`title`/`pos` 随 HTML 段原文
    逐字节保留（`HtmlPopTooltipPosTest` 钉，语料锚 `TEMP_CHARA_LIST_BY_DT.ERB:382` /
    `UPDATE_MAIN.ERB:1561`）；PRINTBUTTON 产的按钮原版本就不带这两属性，语料 4 个
    HTML_POPPRINTINGSTR 调用点（`PRINT_STATE_BODYGRAPH.ERB:89` 等）同判。剩余缺口（`pos` 显示期
    定位）已由 POS-WIRE 接线收口，见第 6 条。
16. **UseNewRandom=true 无告警**（StatementExecutor 三处缺 ParserMediator.Warn 等价文案）：
    Android 端禁用该开关（rng.md §5），RNG 源维持 SFMT。

## 17. 2026-10-26 深审波 core/code 路（`wave/deep-code-bc`）与 main 合流 6 红收口

两段工作：① 深审波 code 路交付（子 agent，worktree `<work-root>/eratw-wt-deep-code-bc`，
提交 `5d22db34`，合入 main 为 `4e65ef7f`）；② 该路合流后 main 全量闸浮出的 6 红收口
（组长，提交 `273037bb`，先于 ① 落 main）。下文行号对应当前 main。

### 17.1 已修（code 路交付，`5d22db34`）

1. **读侧操作码范围**：`ErbcFormat.OPCODE_MAX = Opcode._RETS = 201` + `isValidOpcode`；
   `ErbcReader.needsStatementPool` 从「纯分类」扩成「分类 + 越界即抛」。它是字节路
   `Decoder.validate` 与映射路 `ErbcStreamDecoder.validate` **唯一**的共同调用点，两条装载
   路的判定与文案因此天然逐字相同（`ErbcStreamEquivalenceTest` 的逐字节变异对拍锁死）。
   旧实现 `op = 202 / 500 / -1` 一类脏字节被**静默装载**，留到 VM 跑到那条才报「语句
   `<op:N>` 尚未实现」——运行期文案与真因（产物损坏）无关，违反 `specs/erbc-format.md §1.6`
   「禁止静默降级」。区间口径：VM 内部 0..28（`Opcode.INTERNAL_COUNT = 29`）/ isa 语句
   29..197（`MAX_STATEMENT_OPCODE`）/ 编译器扩展 198..201（`_CALL_BY_NAME` / `_JUMP_BY_NAME`
   / `_JUMP_DYN` / `_RETS`）。
2. **汇编器隐式 main 边界**：新增 `closedAnyFunction`（`Assembler.finishFunction` 置位）⇒
   `.end` 之后再写裸指令**显式失败**（`ensureFunction` 文案「指令出现在函数之外（`.end`
   之后或 `.func` 之间）」）；同时删除 EOF 处的 `implicitMain` 回填。旧实现会拿**最后那个
   函数**的 `funcMaxSlot` 回填 index 0 的帧大小（静默污染前一函数槽数）；`.func main` +
   `.end` 再写指令则撞 `Program.declareFunction` 的裸 `IllegalArgumentException`。
3. **函数内标签表往返**：`Disassembler.appendFuncHeader` 在 `.func` 头后逐条打
   `.label "<名>" <pc>`，`Assembler` 新增 `.label` 指示符登记回 `FunctionInfo.labelPcs`。
   `_JUMP_DYN` 的 a 是这张表的下标，旧 `asm(disasm(p))` 往返得**空标签表**——指令字虽同，
   运行期跳到不存在的标签。
4. 新增 `ErbcCodeBcAuditTest` 5 例：越界操作码（202/500/-1/Int.MIN_VALUE）装载期拒载且文案
   点名操作码、上界 201 放行；`.end` 后再写指令两形态均 `AssemblyException`；隐式 main
   （slots=0）与显式函数（slots=10）帧大小互不污染；`.label` 往返后名表/pc 表逐条相同且
   `_JUMP_DYN a=1` 仍指 `labelPcs[1]`；无标签函数不产 `.label`。
5. **撤回的候选修复**：曾让 `ErbcWriter.build()` 复用 `requireJumpTargets`（修「写得出、
   读不回」），实测 `JumpTargetLoadTest` 故意用 `ErbcWriter.encode` 伪造跨函数/越界跳转容器
   来验**读侧**拒载 ⇒ 写侧保持「不复判跳转归属」是既定设计（结构不变量写侧拦、跳转归属
   装载期拦），`ErbcWriter.kt` 净零改动。

### 17.2 已修（main 合流 6 红收口，`273037bb`）

多路深审波各自只跑定向闸，合到 main 后全量闸浮出 6 红（在 `4e665d96` 上原样复现同 6 红，
确证非某一路引入）。逐条取证后：**1 处真缺陷 + 5 处夹具按现行实现改钉**。

1. **`CALLF` 落到非方法名走错分支**（`ProcessRuntime.resolveForMethod`，唯一真缺陷）：
   `userDefinedOnly` 的 `GetFunctionMethod` 是**三态**（`IdentifierDictionary.cs:551-575`）——
   非方法 ⇒ `CallfNonMethodFunc`（:557-560，zhs:214）；是方法 ⇒ 建 `UserDefinedMethodTerm`
   返回（:561-564）；不存在 ⇒ null，由调用方抛 `NotDefinedUserFunc`
   （`FunctionIdentifier.cs:1161-1163`，zhs:507）。旧实现把首尾两支并成一条，于是
   `CALLF @普通函数` 屏上变成「没有找到行内函数"PLAIN"」——把「已定义但不是方法」说成
   「名字不存在」，排障方向被指反。`CompatiCallEvent` 分叉不动。
2. `ProcessRuntimeTest` 位置占位 `(:第-1行)` → **`(:第0行)`**：生产 `splitPosition` 对空
   position 回落行号 **0**（「无位置信息、不猜」），自 `f5d40c36` 起如此；`deep-proc-rt`
   分叉点 `3fa3f430` 更早，两者无祖先关系（`git merge-base --is-ancestor f5d40c36
   3fa3f430` 返回 1），夹具停在 -1。合流后按生产口径统一。
3. `HostContractAuditTest`：`ENUMFILES` 的 `?` 是**单字符通配**（.NET / Windows 语义，
   原版把 pattern 原样交 `Directory.EnumerateFiles`，`FunctionMethodCreator.cs:359-364`），
   本仓 `BootHosts.globToRegex` 已按 `'?' -> '.'` 补齐；旧用例却钉「`?` 是字面量」并断言
   `a?.txt` 空集，现改为 `listOf("ab.txt")` 单命中，用例名同步。`*.*` 两侧读数一致。
4. `SetAddConstTest` / `SetMultiAssignNonArrayTest`：伪变量族的 `VariableCode` **全部**带
   `__UNCHANGEABLE__`(0x400000)（`VariableCode.cs:11`；`CHARANUM = 12713985`、
   `LINECOUNT = 29491208`、`RAND = 13238272` 逐条含该位），`ArgumentParser.cs:903-907` 在
   运算符分派**之前**先判 `IsConst`，`assignwarn(level=2)` 置 `line.IsError`
   （`ArgumentBuilder.cs:19-28`）⇒ `SET` 写伪变量是**装载期**错误，执行期的
   `AssignToPseudoVar` 对本语句不可达（`PseudoVariableToken.SetValue` 覆写只服务宿主 API /
   直调写入口）。原三条 `CHARANUM` 用例合并为一条三形态循环（断言 `StmtParseException`
   含「左值是常量」），另留一条**直调 `env.store.setValues`** 的用例钉住存储层守卫不被
   误删。
5. `VariableStore.pseudoAssignError` 的 KDoc「可达性」段同步更正（同上口径）。

### 17.3 需接线（本轮只登记，未改生产）

1. **映射装载路不校验跳转归属** —— `ErbcStream.kt` 从不调用
   `ErbcReader.requireJumpTargets`；全仓只有一处调用点 `ErbcReader.kt:587`（字节路
   `Decoder.validate` 末尾）。`requireJumpTargets` 的 KDoc 已自认「CMP-55 收口时只接了字节
   路，映射路接线见交付回执」并写明「两处必须调用本函数，否则同一份产物、两条装载路会给出
   两种判定」。**生产装载走的正是映射路** ⇒ 跨函数跳 / 越界跳的畸形产物在生产装载上零抛出，
   留到运行期静默执行错函数体（调用栈返回点留在别的函数里，帧模型直接坏掉）。
   - 接线方案（评估过，未实施）：在 `ErbcStreamDecoder.validate` 末尾照
     `requireJumpTargets` 的两趟做法——收集长度 n 的 entryPc，`sortedBy` 求 order，再求
     ends（后继 entryPc，末位取 `instructionCount`），逐函数扫自己的 `[entryPc, end)` 读
     `program.op(pc)`。读的是本轮指令趟已触碰的同一份映射页，增量成本≈再走一遍取指面；
     常驻增量 2 × 4 B × 110k ≈ 0.9 MiB。
   - **卡点**：`MappedFunctionTable` 是惰性表，`program.functions` 一迭代就全量物化（这正是
     `validateRecords` 走 parallel 条带的原因）；要在不物化的前提下拿到 entryPc 数组，得给
     `MappedFunctionTable` 加一条只读 entryPc 的旁路 —— 结构性改动，需单独一波 +
     `corpusSmoke` 峰值复测。
   - 可达性：真产物由自家 erbcc 生成，跳转归属天然成立；风险面只有「产物损坏 / 被改」。

### 17.4 登记不修（code 路 TODO(verify)）

1. **`SpColorArgument` 解码容错**（`ErbcReader.kt:1017-1023`）：`rgb != null` 优先，否则取
   r/g/b 三子，**三子全为 null 也不报**（构造出全空 `SpColorArgument`）。写侧只产生「rgb
   单子」或「rgb 三子」两种形态，读侧却接受任意混合；收紧需先给 `SpColorArgument` 加合法性
   判据与 zhs 文案，语料 0 命中畸形形态。
2. **函数 `paramNames` 不随 `asm(disasm(p))` 往返**：反汇编只打 `.func <名> <slots>`，形参名
   不进文本 ⇒ 往返后 `FuncSig.paramNames` 变空。只影响调试/诊断面（生产用 erbc 二进制），
   要修需扩 `.func` 语法。
3. **`Disassembler` 按 entryPc 升序排函数头**：函数表若**不是** entryPc 升序声明，
   `asm(disasm(p))` 会按 pc 序重编号函数，`_CALL` 的 a（函数下标）随之漂。自家 erbcc 产物
   恒为升序，当前无害。
4. **`ErbcStringTable.writeTo` 的 `acc`/`off` 为 `Int` 累加**（`ErbcFormat.kt:700-712`）：
   串表数据 ≥ 2 GiB 会溢成负数写进 u32。当前最大产物 332 MB，无实际可达性；销账需改 Long
   并在写完比对 u32 上限。
5. **任务书列的「LEB / ZigZag 极值」核销为不适用**：erbc 容器是定长 u32/i64 编码
   （`specs/erbc-format.md`），全仓无 varint 编解码点。
6. **`OpcodeTable.kt` 是 `isa.yaml` 的生成物**，不手改；操作码上界口径以本轮新增的
   `ErbcFormat.OPCODE_MAX` 为准，生成器无需变更。

### 17.5 闸（main `4e65ef7f`，cleanTest + 语料变量 + `ERATW_ANDROID_JAR`）

core-vm **4201 例 0 失败 15 skipped**（413 suites）· erbcc **163/0** · vmdbg **97/0** ·
render **818/0**；`corpusSmoke` **GATE GREEN**（3971 文件 / 110,066 切片全对账 / 编译失败 0
/ 解析失败 0 / 指令 4,623,999 / 耗时 33.6 s）；`E2EGoldenTest` **5 例 0 失败**，
`core-vm/build/e2e-report/e2e-report.md`：全量对拍**基准 610 行 / 实际 610 行逐行一致**，
分节 15 节全 PASS（INPUT-MANUAL 按 runbook §4.1 半人工采集，不入自动装置）。

## 18. 2026-10-26 深审波 core/lexer 路（`wave/deep-lexer` 残存交付的验收与补钉）

该路的 5 个文件改动原样留在 worktree `<work-root>/eratw-wt-deep-lexer` 里**未提交**（mtime 停在
12:37，约 1.5 h 无新写入，与 code 路同款额度中断形态）；分支 `wave/deep-lexer` 落后 main
34 个提交，但它要改的 4 个文件在 main 上自 `7ad04ed7` 起**未被改动**
（`git log 7ad04ed7..main -- .../core/lexer/` 为空）⇒ 移植到基于 `main@6d04f77c` 的临时分支
`wave/deep-lexer-adopt` 过完三闸后收下（提交 `e79572ab`，合入 main `bb9d5c40`）。合并树与
已过闸树逐字节相同（`git diff e79572ab bb9d5c40` 为空）。

### 18.1 已修

1. **`{}` 续行块与 `[` 预处理的次序错位**（真缺陷）：原版 `{}` 在
   `EraStreamReader.ReadEnabledLine` **内**完成合并（`EraStreamReader.cs:130-184`），
   ErbLoader 拿到的是**合并后**的流才做 `[` 判定（`ErbLoader.cs:387-390`）。本仓把块处理
   放在 `[` 之后、合并完直接 `classify(merged)` 返回，两个后果：① 块内拼出的 `[...]` 行被
   当代码行，预处理指令整条失效；② 块内全是空白时多吐一条 `content=''` 的 CODE 行（原版
   合并后到 EOS 也照样交回流，`LogicalLineParser.ParseLine` 的 EOS 分支 `return null`，
   `LogicalLineParser.cs:387-391`）。改法：块处理提到 `[` 判定之前、只由 `!pp.disabled`
   把关（对齐 ReadEnabledLine 只在启用态合并），合并后走统一出口
   `classify(st.rowString, st)`，EOS 时 `continue` 不产行。
2. **BOM 探测补四族**（真缺陷）：原版 `DetectEncoding` 顺序是 UTF-8(`EF BB BF`) →
   UTF-32LE(`FF FE 00 00`) → UTF-16LE(`FF FE`) → UTF-16BE(`FE FF`) → UTF-32BE(`00 00 FE
   FF`)（`EncodingHandler.cs:20-41`）。本仓只剥 UTF-8 BOM，其余四族一律按无 BOM 走严格
   UTF-8 → 失败回落 CP932 ⇒ UTF-16/32 文件整篇乱码。已按同序补齐；UTF-32LE 必须先于
   UTF-16LE 判（前缀 `FF FE` 重叠）。
3. **全角空格报错的 `{0}` 改填配置显示名**：`{0}` 是
   `Config.GetConfigName(SystemAllowFullSpace)`，即 `ConfigData` 条目的
   `Text + "/" + EngText`（`LexicalAnalyzer.cs:751` + `Config.cs:291-294` +
   `ConfigData.cs:204-214`），不是枚举名。新增 `LexerConfig.systemAllowFullSpaceName` 与
   `configDisplayName()`（走 `ConfigSpecTable.specOf` 的 `jpLabel`/`enLabel`；本作该条目的
   两标签是「全角スペースをホワイトスペースに含める/Whitespace includes full-width
   space」）。
4. **`[[` 的实参是字面量 `"[["`**：`RenameDic == null` 分支（`LexicalAnalyzer.cs:809-813`）
   实参是双方括号，旧串只填了一个 `[`。
5. **未闭合双引号串上屏三连双引号**：NotClosed 模板 `"{0}"が閉じられていません`
   （`Lang.cs:3598`），`{0}` = 一个半角双引号；旧串多写一个 `\` 变成四连。
6. **宏展开超限改官方文案**：MacroOverLimit（`Lang.cs:3554`），实参 `100` 不带单位；旧串
   `マクロの展開回数が100回を超えました（循環 ##DEFINE?）: <ident>` 是自造文案。
7. **补齐上一手漏改的一条用例**：`MacroBodyAssignmentTest.assignmentBodyCycleStillHitsExpansionLimit`
   仍断言旧宏文案（改生产文案时漏改），是该路唯一的红；已按 `Lang.cs:3554` 改钉。

新增 `LexerDeepAuditTest` 10 例：续行块合并出的 `[#IF …]` 按预处理指令处理 / 参与分支
选择 / 空白块后紧跟的 `#` 行照常识别；UTF-16LE / UTF-16BE / UTF-32LE / UTF-32BE 四族 BOM
分别解码且 UTF-32 优先于 UTF-16 前缀；未闭合双引号串上屏三连双引号；顶层 `[[` 报
UnexpectedCharacter 且实参是双方括号；全角空格报错填官方配置显示名；宏展开超限报
MacroOverLimit 官方文案。`LexerAuditTest` 里那条把旧行为钉成契约的 TODO(verify) 用例
（`只含空白行的续行块当前仍产出一条空逻辑行`）改钉为 `不产逻辑行`。

### 18.2 登记不修（沿用 §16.3-4 的既有条目，本路未变）

CP932 解码器的 `ExceptionFallback` 严格回退仍未复刻（`EncodingHandler.cs:67-71` +
`EraStreamReader.cs:50-59`：坏字节应抛成整文件跳过）；本函数无「读失败」回传通道，回退档
仍是 REPLACE。语料 3,818 个 `.ERB` 实测严格 CP932 失败 0 例 ⇒ 不影响读数，见 §16.3-4。

### 18.3 闸（`main@bb9d5c40`，cleanTest + 语料变量 + `ERATW_ANDROID_JAR`）

core-vm **4211 例 0 失败 15 skipped**（414 suites）· erbcc **163/0** · vmdbg **97/0** ·
render **818/0**；`corpusSmoke` **GATE GREEN**（3971 文件 / 110,066 切片 / 指令
4,623,999，与采纳前**逐项相同** ⇒ 词法重排对语料零影响）；`E2EGoldenTest` 610/610 行。

## 19. 2026-10-26 与 `origin/main` 分叉合流（本地 30 提交 × 远程 13 提交）

本会话推进期间，`origin/main` 被另一会话（修复即推）从 `5889f1e1` 推到 `b04c224f`，与本会话
`88887049` 同 base 分叉。合流 commit `d65f48c5`。`git merge` **零文本冲突**（`BOARD.md` /
`ProcessRuntime.kt` / `StatementExecutor.kt` / `ProcessRuntimeTest.kt` 四文件 automerge），
但零文本冲突 ≠ 零语义冲突 —— 闸浮出 1 红，正是两侧对**同一条不变量**的不同处置。

### 19.1 碰撞：`arity <= slotCount` 从「VM 兜底夹取」升级为「构建期不变量」

- 本会话 `wave/deep-code-vm` 路（`VmDeepAuditTest`）把 `arity > slotCount` 当成**可装载的畸形
  函数表**，测 VM 侧兜底夹取：`declareFunction("sub", …, arity=200, slotCount=1)` 直接建表，
  断言 `min(argc, arity, slotCount)` 不得写坏相邻帧。
- 远程 `b04c224f`（深审波 R1）给 `CodeBuilder.build` 补了跨字段不变量
  `require(arity <= slotCount)`（与 `ErbcReader:548` / `ErbcWriter:254` / `ErbcStream:1135`
  三路装载同款），并在 `VmFrameSlotAuditTest` 新增 `构建期拒绝 arity 大于 slotCount 的函数`。
- ⇒ 本会话那条用例的**构造前提被合法收窄**：`build` 当场抛 `IllegalArgumentException`。

**裁决：两侧都保留，各测一层。** 构建期不变量是对的 —— 三路装载早已同款，`build` 是内存
`Program` 的唯一出口，漏掉它就是第四个不一致的入口；VM 兜底夹取也仍在（`FunctionInfo.slotCount`
是 `var`，对象对宿主可见、可被改坏）。改钉方式：按合法形态（`arity=200 slots=200`）建好后把
`slotCount` 压到 1，模拟「表在构建之后被改坏」，仍钉「VM 不得越界写坏相邻帧」；构建期拒绝由
远程新增用例单独钉住。用例更名 `函数表被改坏成 arity 大于 slotCount 时实参复制仍夹到本帧槽区间`。

> 与 §16.2「改钉先判夹具过时还是实现退化」同一条纪律：这次是**夹具前提被上游合法收窄**、
> 不是实现退化，所以改夹具并保留被测算的兜底，而不是删用例。

### 19.2 语义共存复核（四个 automerge 文件逐处读）

- `ProcessRuntime.kt`：本会话的三态 `resolveForMethod`（存在但非方法 ⇒ `CallfNonMethodFunc`；
  不存在 ⇒ `NotDefinedUserFunc`，`IdentifierDictionary.cs:551-575`）与远程的 `executeMethod`
  逐位绑参（`rootBindOf(args, args)`）+ `_RETS` 读 `activeVm.stringResult` 并存，无覆盖。
- `StatementExecutor.kt`：远程的方法帧早退（`ctx.isFunctionMethodFrame()` ⇒
  `ExecResult.Return(0L)`，`ProcessState.cs:289-295`）与 §17.2 改钉的 `SET` 常量拦截互不回写 ——
  两边动的是 `execReturn` 与 `execSet` 两个分支。
- `Program.kt`：`build` 的 arity 不变量与 `declareFunction` 的 `nameToIndex.put` 去重、
  `setFunctionSlots` 取 max 三者互不重叠。不变量放 `build` 而非 `declareFunction` 的取舍正确：
  两阶段声明（占位 → `setFunctionSlots` 抬升）的中间态可合法 `arity > slotCount`，只有 `build`
  时刻的终态才是真不变量。
- `BOARD.md`：两侧各写各的波次段，无重复锚点、无互相覆盖。

### 19.3 闸（合并树，cleanTest + 语料变量 + `ERATW_ANDROID_JAR`）

- 合流首跑：core-vm **4217 例 1 失败 15 skipped** —— 唯一红即 19.1。
- 改钉后全量复跑：core-vm **4217 例 0 失败 15 skipped**（415 suites）· erbcc **163/0** ·
  vmdbg **97/0** · render **825/0**（远程新增 `GraphicGeometryTest` 7 例，818 → 825）；
  `corpusSmoke` **GATE GREEN**（3971 文件 / 110,066 切片 / 编译失败 0 / 解析失败 0 /
  指令 4,623,999 / 40.3 s，各项与合流前**逐项相同** ⇒ 合流对语料零影响）；
  `E2EGoldenTest` **610/610 行逐行一致**（15 节 PASS，INPUT-MANUAL 按 runbook §4.1 半人工）。

## 20. 2026-10-26 §17.3 收口：跳转归属校验接进生产装载路（`MappedFunctionTable.JumpScan`）

§17.3 登记的是「`ErbcStream.kt` 从不调用 `ErbcReader.requireJumpTargets`，而**生产装载走的
正是映射路** ⇒ 跨函数跳/越界跳的畸形产物在生产上零抛出」。本条闭合该缺口。

### 20.1 为什么不能直接调用 `requireJumpTargets`

那个函数按 `program.functions.indices` 逐条取 `FunctionInfo`（`entryPc` / `labelPcs` / `name`）。
映射路的函数表是 `MappedFunctionTable`（`AbstractList` + 按下标惰性解码 + 永久缓存），
迭代一次就**全量物化 110,072 条**记录与函数名子串 —— 那正是 M11-MEM 整波省掉的堆开销
（`Program.materializedFunctionCount` 就是这条判据的读数）。这也是 §17.3 当时判「结构性
改动、需单独一波」的原因。

### 20.2 落地：同判据、同文案、零物化、零额外指令趟

- `MappedFunctionTable.jumpScan(instructionCount)`：只读每条记录前 8 字节（`u32 名字下标 +
  i32 entryPc`，名字下标只跳过**不解串**），把 `(entryPc, index)` 打包成 `Long` 排序求区间
  端点。常驻 = 三个 `IntArray(n)`（110k 时各 440 KiB），打包数组用完即弃。
- `JumpScan.observe(pc, op, a, b)`：**搭在已有的 480 万条指令验证趟上**
  （`ErbcStreamDecoder.validate` 的 `for (pc in 0 until count)`），不额外多走一遍。重复
  `entryPc` 的归属与字节路 `if (end <= start) continue` 同口径（由后者独占区间）。
- `_JUMP_DYN` 的标签 pc 只在真遇到该操作码时重放**这一条**记录（一函数缓存，只读 pc、
  不解标签名）；记录区本身畸形时返回 null，让 `validateRecords` 先报。
- **文案单一真源**：`ErbcReader.jumpOutOfRangeMes` / `jumpDynLabelIndexMes`，字节路与映射路
  共用；`ErbcStreamEquivalenceTest` 的逐字节变异对拍继续锁死「两条路逐字相同」。
- 整池档（非惰性函数表，含测试替身）改为在记录校验后直接调 `ErbcReader.requireJumpTargets`
  —— 与字节路同一份实现，不留第二条判据。

### 20.3 错误优先级：为什么不在指令趟里直接抛

字节路把 `requireJumpTargets` 放在**函数记录校验之后**。映射路若在指令趟里直接抛，同一份
「既有记录区畸形、又有跨函数跳」的产物会报成跳转错，与字节路分叉。落地做法：指令趟里只记
「`(函数下标, pc)` 最小」的那一条候选（与字节路「按函数下标升序、函数内按 pc 升序」等价），
等 `validateRecords` 通过后再抛 —— 顺序一致、判据一致、不多走一趟。

### 20.4 装置（`JumpTargetLoadTest` +4 例）

同一份字节同时喂 `ProgramLoader.load`（字节路）与 `ProgramLoader.loadMapped`（生产映射路），
取 loader 包装**之前**的格式层原文逐字比对：跨函数 `_JUMP` / 越界 `_JUMP` / `_JUMP_DYN`
下标越界三条拒绝，加一条合法跳转（前向 `_JUMP` + 合法标签跳）照常执行。
**红绿对拍**：把 `jumpScan` 临时置 null ⇒ 恰好这 3 条拒绝用例红、合法用例仍绿；恢复即绿。

### 20.5 闸

- 全量 unit 闸：core-vm **4221 例 0 失败 15 skipped**（415 suites，含本波新增 4 例）·
  erbcc **163/0** · vmdbg **97/0** · render **827/0**。
- `corpusSmoke` **GATE GREEN**：3971 文件 / 110,066 切片 / 编译失败 0 / 解析失败 0 /
  指令 4,623,999 / **37.7 s**（接线前同机 40.3 s —— 扫描搭在既有指令趟上，无额外成本；
  语料读数逐项不变 ⇒ 生产装载路对 3,971 个 `.ERB` 的判定零变化）。
- `E2EGoldenTest` **610/610 行逐行一致**（15 节 PASS，INPUT-MANUAL 按 runbook §4.1 半人工）。

## 21. 2026-10-27 第六批验收登记：leaveFunction 出场遍历实时 map，与 enter 快照不对称（存量，TODO(verify)）

**现象**：`VariableStore.enterFunction`（2026-10-27 第六批起用复用缓冲 `scopeInBuf`，
语义与旧 `values.toList()` 快照逐项等价）只对**进入时刻**私有变量表里的动态变量跑
`scopeIn()`；而 `leaveFunction` 对 `f.privMap ?: privateVars[f.funcKey]` 的**实时** values
逐个跑 `scopeOut()`。函数体内才声明的私有动态变量（`refreshPrivMap` 的兜底注释承认
这一顺序存在：`#DIM …, isPrivate` 写在过程体内、建帧时 `privateVars[key]` 还不存在）
会走到「没 scopeIn 过却被 scopeOut」——空栈分支把 data 置 null。

**为什么登记而不修**：这不对称在本批之前就存在（leave 侧一直没动过）；且「动态私有
随函数作废」本身可能就是原版语义——退函数时把它清掉恰好多对。官方 bug 原样复刻是
铁律，判明之前不许顺手改。

**销账路线**（两问都要答）：
1. C# 对质：`Process.cs:531-536` finally 里的 ScopeOut 遍历对象是什么——调用时刻记录的
   变量清单，还是函数当前变量表？`VariableData.cs:1890-1901` 的 ScopeIn/ScopeOut 栈语义
   在「作用域建立后才声明」的变量上如何表现（原版 IsDynamic 局部在声明那一刻是否已经
   带着一层隐式 ScopeIn）。
2. 语料可达性：量一遍语料里是否真有「过程体内部声明的私有 DYNAMIC `#DIM`」（没有的话
   本条降级为纯理论分歧，钉注释收口）。

**现场**：代码侧 TODO(verify) 已钉在 `VariableStore.leaveFunction` KDoc（2026-10-27）。

---

## 22. 2026-10-29 玩家上报两条定性（上报端点 `eratw/2026-10-2[89]/…`，构建 89604c4 / app 0.2.0）

### 22.1 `CodeEE 无效输入。 @ VIRGIN_SET 行65`（Xiaomi 23049RAD8C）——**游戏脚本既有行为，非引擎 bug，不改**

- **现场**：开局处女设定菜单（`ERB/NEWGAME/NEWGAME_VIRGINITY.ERB:7-68 @VIRGIN_SET`）。
  玩家在 `ASK_CHOICES` 菜单上给出的数值不在 `{0,1,2,3,4,5,9,100}` 里（最可能是点了屏上
  **残留的旧按钮**——个别设定界面留下的 `[999] 设定结束` / 角色按钮在 Emuera 系控制台上
  始终可点，PC 上同样会提交其值），落到 `CASEELSE → THROW 无效输入。`（`NEWGAME_VIRGINITY.ERB:65-66`）。
- **脚本层根因**：`ERB/NEWGAME/NEWGAME_UTILS.ERB:79-84 @ASK_CHOICES` 的输入循环判据写的是
  `SIF argCanselNo || (RESULT < VARSIZE("refChoices") && refChoices:RESULT != "")` ——
  第一个操作数是**取消号本身**（VIRGIN_SET 传 100），非零恒真 ⇒ **任何输入都原样 RETURN**，
  后面的校验与 `CLEARLINE` 重试是死代码。这是模组脚本自己的笔误。
- **定性**：与 PC Emuera 行为一致（同一份 ERB 同一个 THROW）。按 ADR-005 原样复刻，
  **不修**；玩家对策 = 别点残留旧按钮/别输菜单外数字，失败屏「回到标题」可正常退出。
- **引擎侧无可归因项**：`SELECTCASE` 的 `CASE IS == 全員の貞操` / `CASEELSE` 落点、
  `SIF` 恒真短路、按钮值提交均有黄金/定向覆盖。

### 22.2 `StepLimitExceeded(20000001) @ EVENTTRAIN#4719 → … → GMF_METHOD_UPDATE`（vivo V2183A，gen 362 存档）——**标定缺口（红 L）；已收口，2026-10-30 二次改判为「10 亿步确定性闸 + 墙钟关」**

- **现场**：读档后日始 `EVENTTRAIN` 整段（相邻两次输入让出之间）撞 20,000,000 步闸；
  屏面正常推进到「少女移动中…」，无重复打印，gen 362 说明此前 361 个日始同样跑过
  ⇒ **合法重负载误杀，不是死循环**（与红 K 同链同判据）。
- **根因**：上一轮（红 K，2026-10-25）的标定只量了 `CHARA_MOVEMENT` **单函数**
  （12.08M 指令 @ CHARANUM=168，`RedKCharaMovementReproTest`），而玩家撞闸的是
  `EVENTTRAIN` 行 4719 起的**整个日始段**——`CHARA_MOVEMENT` 之前还有 ~4,700 行日始
  事件处理。20M 的 1.65× 余量没有覆盖「段 ≥ 函数」的差值。
- **后果**：日始段在**读档时重放** ⇒ 误报等于把存档变成永久无法启动（比单次误报严重）。
- **处置**：本条与 02d7faaa 收口的玩家反馈是**同一根因的两个数据点**（那次是命莲寺
  第 47 日旧档，本次是 gen 362 晚期存档，调用链同落 `GMF_METHOD_UPDATE`）。终局修法以
  02d7faaa 为准：生产 `GameHost` 改「单段墙钟 60 s 为主 + 步数闸 5 亿兜底」
  （`DEFAULT_MAX_RUN_MILLIS` / `PRODUCTION_MAX_STEPS_PER_RUN`），装置/CI 刻意保留
  `DEFAULT_MAX_STEPS_PER_RUN = 20M` 求确定性。本条新增的标定信息：红 K 只量了
  `CHARA_MOVEMENT` 单函数（12.08M @ CHARANUM=168），而实际撞闸面是 `EVENTTRAIN`
  **整段**（#4719 前还有 ~4,700 行日始事件）——「段 ≥ 函数」的差值是步数口径
  无法只靠抬闸覆盖的结构性理由，为 02d7faaa 弃步数口径补证。
  曾试探的「直接把缺省抬到 100M」作废：与 02d7faaa 的装置/CI 确定性决策冲突，未合入。
- **2026-10-30 改判（玩家报「开地图重修插件后启动失败」，`e854af9-dirty` / vivo V2199A）**：
  屏上 `执行时间超过上限(60013ms，已执行 133496832 步)`，调用链同本条。
  桌面用真产物 + `fixtures/saves/save03.sav`（第 47 日 `TIME=1345`、`CHARANUM=168`、地图重修开启）
  把「整段」量全：一次 `EVENTTRAIN` 派发连调 **24 次** `CHARA_MOVEMENT`，共 123,496,792 条语句
  ≈ **2.90 亿条指令**（单次 12,080,416；97.8% = `GMF_METHOD_UPDATE`/`RESTORE`）。
  玩家机 60 s 只够 1.33 亿步（≈2.22M 指令/秒）⇒ 同段需 ≈130 s ⇒ **墙钟当判据同样误杀**
  （它是设备速度的函数）。终局：生产 `PRODUCTION_MAX_STEPS_PER_RUN` = **10 亿**
  （3.4× 余量），`PRODUCTION_MAX_RUN_MILLIS` = **0（关）**；装置口径不变
  （`DEFAULT_MAX_STEPS_PER_RUN = 20M`、`VmConfig.maxRunMillis = 0`）。
  回归锁：`RunMillisLimitTest`（生产取值）+ `RedKCharaMovementReproTest`（单次实测 ≤ 20M，
  且生产闸 ≥ 起床链整段）。新包（含 PERF-PAGEFETCH 8.51×）玩家自测已不复现。

---

## 23. 2026-10-30 4.981（Emuera SkiaX 0.77.0）新增内建/语句再取证（worker w0-specs，worktree `<work-root>/eratw-wt-u4`，分支 `wave/u0-specs`）

**证据源约定**：本条所有 `fork-src` 行号指 GitHub `qirchen421/emuera.sk` 分支 `develop-skiasharp`
commit `48494165eaf3335f3982b6a7c33bee662b8ba2c1`（2026-08-28）的 1-based 行号。
`reference/emuera-cs/`（Emuera 1.824）里**这些命令一条都没有**，不能拿它做反证；`docs/` 三个子目录为空，
唯一官方文字证据是 `魔改版更新记录文档/画蛇又添足版自改emuera相关说明.txt`（512 行，下文简称「自改说明」）。

**4.981 配置漂移**：`setting.json`（单行 JSON）里 `UseNewRandom` 由 4.980 的 `false` 翻成 `true`，
并新增 `RenderingBackend:0`；详见 `rng.md` §6，本条不改 rng 既有判定。

**落地位置**：`builtin-functions.yaml`（299 条，本波 +28）、`isa.yaml`（175 条，+6 个条目覆盖 8 个注册名）、
`variables.yaml`（新增顶层 `float_vars` 段 + `local_vars` 的 LOCALF/ARGF + 存档三注）。全部条目 `confidence: high`。

### 23.1 浮点字面量与 `TOFLOAT` —— A

- 字面量：`LexicalAnalyzer.cs:274-300` `ReadDouble`（可选符号、整数段、`.`、`e/E` 指数，末尾
  `double.TryParse(str, out result)`）；唯一 literal 使用点 `LexicalAnalyzer.cs:896`
  `ret.Add(new LiteralFloatWord(ReadDouble(st, false)))` ⇒ 词法层解析失败即抛 CodeEE（retZero=false）。
  常量折叠另有 `ArgumentBuilder.cs:2591`。
- `TOFLOAT`：`Creator.Method.cs:6145-6165`（`ReturnType=EraType.Float`、实参 `[String]`、`CanRestructure=true`；
  空串 / 长度不足 / 解析失败一律返回 `0.0`）；注册 `Creator.cs:145`。
- 语料（B）：`DLC\魔法DLC\使用魔法.ERB:1248-1256`（`LOCALF += YOGORE:(LOCAL) * 0.01` 后 `TOINT(LOCALF)`）。
- **TODO(verify)**：两处 `double.TryParse` 都没带 `CultureInfo.InvariantCulture` —— 小数点为逗号的设备文化下
  `"1.5"` 会解析失败（字面量抛 CodeEE、TOFLOAT 返回 0.0）。平台差异，需在 Android 侧统一文化后复验。

### 23.2 `SQL_*` 数据库族（15 名 + 参数化四件套）—— A

- 实现：`Creator.Method.cs:10237-10350` 15 个 `FunctionMethod` 子类；`SqlManager.cs` 分节落点：
  `:15` 连接表、`:67-82` `SqlConnect`、`:96-121` `ExecuteNonQuery`、`:124-153` `ExecuteReader`、
  `:156-164` `ReaderRead`、`:167-175` `ReaderGetLong`、`:178-186` `ReaderGetString`、`:200-207` `ReaderIsNull`、
  `:210-219` + `:221-234` `ReaderClose`/`CloseAll`、`:238-273` `ExecuteScalar<T>`、`:275-276`/`:278-279`
  `ExecuteScalarLong`/`String`、`:285-339` `ImportMapXml`、`:605-609` `Escape`。
- 关键语义（已写进 `builtin-functions.yaml`）：参数占位 `@0…@n` 顺序绑定（`:249-255`）；未连接一律
  `CodeEE "数据库 '{dbName}' 未连接。"`（`:241`）；NULL → `ReaderGetLong` 返回 0、`ReaderGetString` 返回 `""`
  （`:171`、`:182`）；非法 readerId 抛 `无效的 Reader ID: {id}`；`SQL_READER_CLOSE` 底层 `void`、
  不存在即静默（语句层恒得 1）；`SQL_ESCAPE` 只做 `'` → `''`（`:608`）。
- 参数化：`SQL_P_*` 用 `ArgTypeList{ ArgTypes={String,String,VariadicString}, OmitStart=2 }`
  （`Creator.Method.cs:2653-2743`）⇒ 首个 SQL 串之后全部按字符串绑定。
- 语料（B）：`魔改内容\qol\qol_db.ERB:61-62`（`SQL_IMPORT_MAP_XML "TR_DB", …`）、`qol_item.ERB:99`、
  `qol_mushi.ERB:114`、`qol_PHARMACY.ERB:196`、`qol_graph_query.ERB:146`。自改说明 `:22-24`、`:249-256`。
- **TODO(verify)**：`Creator.cs:404-428` 实际注册了 **25 个** SQL 名（还有 `GET_FLOAT` /
  `SCALAR_FLOAT` / `P_SCALAR_FLOAT` / `SQL_DISCONNECT` / 6 个 `IMPORT/EXPORT_*_XML`），本波只按任务书
  落了 4.981 语料用到的那批，剩余记 TODO；驱动是 Microsoft.Data.Sqlite ⇒ Android 端要换
  SQLitePCLRaw bundle 才有 A 级等价（需组长接线）。

### 23.3 `GETANIMETIMER` —— A

- `Creator.Method.cs:9012-9024`（`ReturnType=Integer`、无实参）；取值 `EmueraConsole.cs:927`
  `AnimeTimer => redrawTimer.Enabled ? (int)redrawTimer.Interval : 0`；`setRedrawTimer`（`:944-955`）
  ≤0 停表、<10 抬到 10。注册 `Creator.cs:242`。
- 语料（B）：`魔改内容\PRINT系\Toolkits.ERB:314-325`、`SHOP関連\TEST.ERB:254`、
  `キャラデータ\Chara_data_2_る～こと.ERB:248`、`キャラデータ\Chara_data_25_藍.ERB:268`。
- **TODO(verify)**：Android 渲染层的重绘间隔时钟源未定（SkiaX 有独立 `RenderingBackend`），需与 `SETANIMETIMER` 联调。

### 23.4 `TINPUTNF` / `TINPUTSNF`（含 `TONEINPUTNF`/`TONEINPUTSNF`）—— A

- `Instraction.Child.cs:1080-1133`（`TINPUT_Instruction`）、`:1135-1186`（`TINPUTS_Instruction`），
  两个类都加了 `bool noFocus = false` 构造参数；flag `IS_PRINT|IS_INPUT|EXTENDED` = 24578（`:1085`/`:1140`）。
- 注册：`FunctionIdentifier.cs:201-204`（NF 后缀家族，注释写明「仅 T 前缀：INPUT 全阻塞无轮询」）。
- 行为：走 `EmueraConsole.cs:777` `WaitInputNoFocus`（`:799-815`，不抢焦点、不强制滚动，`nfBack` 从上一个
  NF 等待继承），状态 `ConsoleState.WaitInputNoFocus`（`:52`）。
- 语料（B）：`魔改内容\qol\qol_MAP.ERB:54`（动画帧超时后轮询 `MOUSEB`，是地图动画/悬停的等待原语）。

### 23.5 `HTML_PRINTC` —— A

- `Instraction.Child.cs:415-434`（class `HTML_PRINTC_Instruction`，`flag=EXTENDED|METHOD_SAFE` `:421`，
  ArgBuilder `SP_HTML_PRINTC` `:422`）；注册 `FunctionIdentifier.cs:472`（`|IS_PRINT` ⇒ 8198）。
- 参数 `ArgumentBuilder.cs:408-442`（1..2 个：HTML 串必填、像素宽可选整型）；渲染
  `EmueraConsole.Print.cs:521-574`：`cellWidthPx<=0` ⇒ `Config.PrintCLength * FontSize / 2`，右对齐前补空格、
  超宽先 Flush；**`Html2ButtonList` 为空则直接 return** ⇒ 纯文本片段不会落屏（重要语义）。
- 语料（B）：自改说明 `:17-18`（明确说明动机是「像素制表」）；语料调用点见 `魔改内容\PRINT系\HTML_PRINT_Components.ERB:127-129`。
- **TODO(verify)**：Android 侧需要 Skia 文本测量才能复刻「像素宽 → 填充空格数」，`FontSize`/`PrintCLength` 的换算待定。

### 23.6 `TEXT_BGC_ON` / `TEXT_BGC_OFF` —— A

- `Instraction.Child.cs:1627-1654` / `:1656-1668`，flag `METHOD_SAFE|EXTENDED` = 6（`:1632`/`:1661`）；
  注册 `FunctionIdentifier.cs:291-292`。ON 的两实参 = RGB + 不透明度百分比，装载期把常量折叠成
  `(rgb<<8)+(alpha&0xFF)`（`ArgumentBuilder.cs:2074-2099`）；运行时写 `EmueraConsole.cs:94`
  `Color? TextBackgroundColor`，OFF 置 null。
- 语料（B）：`魔改内容\QOL_IMAGE.ERB:2564`、`DLC\背景图补丁\地点转换.ERB:55`
  （`LIMIT(衬底透明度*10+1, 1, 100)` —— 语料把 0..10 的档位映射成百分比）。
- **TODO(verify)**：4.980 语料已在调用但 1.824 无此命令 ⇒ 旧 spec 缺项；Android 图元层的衬底绘制未实现。

### 23.7 `LOCALF` / `ARGF` / `#DIMF` —— A（已落 `variables.yaml`）

- 存储：`VariableData.cs:26/29/32/35` 四件套；构造 `:155-160`（长度复用 `__COUNT_STRING__`(2) 与
  `__COUNT_STRING_ARRAY__`(7)，2D/3D 为空容器）；清零 `SetDefaultValue` `:668-669`。
- 局部：`VariableData.cs:376-379` 注册 `LOCALF`/`ARGF`，尺寸取 `ConstantData.cs:211,216-217`（默认 10，
  可被 `VARIABLESIZE.CSV` 改写 `:532-533`）；`#LOCALFSIZE` 按函数拉大 `LogicalLineParser.cs:257-266`；
  实际数组在 `ExecutionContext.cs:29,55-62`；运行时按需扩容 `Process.State.cs:672-677`。
- `#DIMF`：`UserDefinedVariable.cs:74`（`dimtype = "#DIMF"`）、`VariableData.cs:469-474`（静态 1 维）、
  `:554-561`（私有静态）、`:586-593`（私有动态）；2D/3D/0 维一律 `ExeEE(AbnormalVarDeclaration)`；
  `#REFF` 仅标量（`:451-456` 全局/静态、`:519-526` 私有）。
- **疑似 bug（ADR-005 原样复刻）**：`CreateUserDefCharaVariable`（`VariableData.cs:410-429`）只有
  `TypeIsStr` 与 else(整数) 两支 ⇒ `#DIMF CHARADATA` 掉进整数分支。TODO(verify) 待语料/真机确认。
- 语料（B）：`DLC\魔法DLC\魔法相关函数.ERB:3190` `#DIMF DYNAMIC 倍率`。

### 23.8 `UNCHECKED_ADD` / `UNCHECKED_MUL`（含 SUB/NEG）—— A

- `Creator.Method.cs:4567-4581`（Add）、`:4599-4613`（Multiply）；注册 `Creator.cs:102`/`:104`
  （SUB `:103`、NEG `:105`）。语义 = 64 位无检查运算（溢出回绕）。
- 语料（B）：`COMMON.ERB:3039-3040`（`UNCHECKED_ADD(UNCHECKED_MUL(…, 60493), 19990303)` 的整型哈希）。
- 结论：JVM `Long` 乘法/加法溢出同样回绕 ⇒ 逐值一致无需额外处理，Kotlin 侧直接用 `Long` 即可
  （无需 `Math.addExact` 之类的检查版本）。

### 23.9 `DISABLE_INPUT_MACRO` / `ENABLE_INPUT_MACRO` —— A

- `Creator.Method.cs:9666-9679` / `:9681-9694`；注册 `Creator.cs:444`/`:445`（`SEQUENCEINPUT` 在其前一节 `:443`）。
- **TODO(verify)**：开关的实际生效面（输入宏展开 / `SEQUENCEINPUT` 递归）未逐行核对；Android 输入层的宏展开器是否受它控制需接线。

### 23.10 `TRYCCALLSTR` —— A

- 与 CALLSTR 家族共用 `CALLS_Instruction`（`Instraction.Child.cs:3950-4066`），flag 计算 `:3957-3960`
  （`FLOW_CONTROL|FORCE_SETARG` + `IS_JUMP`/`IS_TRY`/`IS_TRYC|PARTIAL`）；注册 `FunctionIdentifier.cs:369`
  （`|EXTENDED` ⇒ **32947**）；装载期配对 `funcMatch[TRYCCALLSTR]="CATCH"`（`:502`）、`CATCH→ENDCATCH`（`:503`），
  缺配对由 `ErbLoader.cs:1595` 告警 `MissingCorresponding`。
- 语义：字符串 `函数名(参数…)` 或 `函数名, 参数…` 两种形态；TRY 变体在「表达式解析失败 / 函数未定义 /
  实参转换失败」三种情况都不抛错，而是跳到 CATCH 末尾（`JumpToEndCatch`）。
- 语料（B）：自改说明 `:275`（列出 6 个注册名）。

### 23.11 `GETPLATFORM` —— A

- `Creator.Method.cs:3469-3487`：Windows 0 / **Android 1** / iOS 2 / macOS 3 / Linux 4 / 其他 5；
  `ReturnType=Integer`、无实参、`CanRestructure=true`；注册 `Creator.cs:438`。
- 语料（B）：`TITLE.ERB:86-91`（`check_platform = GETPLATFORM()` 后按平台分支）。
- **需组长接线**：Android 端必须返回 **1**；若移植层用自研 VM 就必须在 `GETPLATFORM` 的实现里硬写 1，
  否则语料会走 PC 分支（`TITLE.ERB` 的平台分支直接决定标题/路径行为）。

### 23.12 `TRYCALLSTR` —— A

- 同 23.10 的类；注册 `FunctionIdentifier.cs:367`（`isJump=false, isTry=true` ⇒ flag **163**）。
- 语料（B）：自改说明 `:275`。与 `CALLSTR`(33)/`JUMPSTR`(97)/`TRYJUMPSTR`(227)/`TRYCJUMPSTR`(33011) 一并在
  `isa.yaml` 的 `CALLS` 条目里用 `flag_by_alias` 给出。
- **TODO(verify)**：`Restructure` 实参编号（`%0%` 风格）与 `CALLFORM` 的差异未逐条对齐。

### 23.13 `MAP_MERGE` / `MAP_TOSTRING` / `MAP_FINDKEY` / `MAP_REMOVEIF` —— A

- `Creator.Method.cs:2434-2452`（MERGE）、`:2575-2602`（TOSTRING）、`:2505-2573`（FINDKEY）、
  `:2454-2503`（REMOVEIF）；注册 `Creator.cs:313`/`:316`/`:315`/`:314`（另有 `MAP_VALUES` `:2360-2432`、
  `MAP_FROMSTRING` 未在本波范围）。条件串 `KEY_CONTAINS/KEY_PREFIX/KEY_SUFFIX/VAL_CONTAINS/VAL_EQ/VAL_NE`。
- 语料（B）：自改说明 `:34-39`；**语料里只有注释级引用**（`fromEN\Custom_Commands\摄影用lib\STRINGSET.ERB:26,42`、
  `ステータス表示関連\CN_GRAPHIC\_List.ERB:47,49,53`）⇒ 运行时可达性未知，按 B 级记「有说明书 + 无实调用」。
- **TODO(verify)**：`MAP_FINDKEY` 的条件分支是否含 `VAL_NE`（MERGE/REMOVEIF 已确认含）——本波只核对了类跨度，
  未逐分支核对；另外 `MAP_TOSTRING` 的分隔符缺省值未核。

### 23.14 `HTML_PRINTLC` —— A

- 与 `HTML_PRINTC` 共用实现类，构造参数 `alignRight=false`（`FunctionIdentifier.cs:473`
  `new HTML_PRINTC_Instruction(false)`）；注册 flag 同为 8198；渲染左对齐（后补空格）
  （`EmueraConsole.Print.cs:521-574`）。详见 23.5 的像素制表语义。
- 语料（B）：自改说明 `:17-18`。

### 23.15 本波自检口径（未跑 Gradle）

- 三个 YAML 全部 `yaml.safe_load` 通过：`builtin-functions.yaml` 299 条 / `isa.yaml` 175 条 / `variables.yaml` 全键可读；
  无重复条名、无 BOM、行尾统一 CRLF。
- 行号回读：`builtin-functions.yaml` 里 28 条新条目的 `Creator.cs:<N>` 全部对齐 `Creator.cs` 真实注册行（脚本逐条比对）；
  `Creator.Method.cs` / `SqlManager.cs` 的起止行都落在「类/方法签名 → 闭合花括号」边界。


### 23.16 `SEQUENCEINPUT` / `SETIMAGELAYERL` —— A（4.981 最后 5 个解析失败切片）

- `SEQUENCEINPUT` 在 fork 是 **FunctionMethod**（`Creator.Method.cs:9691-9705`：`ReturnType=Integer`、
  `argumentTypeArray=[String]`、`CanRestructure=false`；函数体两行 = 整段覆盖
  `Process.sequenceInputValue` 并置 `hasSequenceInput=true`，返回 0），注册
  `Creator.cs:447`（`#region Dominare追加` 内，`DISABLE/ENABLE_INPUT_MACRO` 在 `:448`/`:449`；
  §23.9 记的 `:443` 是旧读数，作废）。状态字段 `Process.SystemProc.cs:117-118`。
- 消费语义：`EmueraConsole.cs:704-712`（`SimulatePressEnter` 取串、清两标志）、等待首行守卫
  `:718`（`WaitInput`）/ `:783`（`WaitInputNoFocus`）；`PressEnterKey` 按 `\n` 拆段逐段
  `RunEmueraProgram`（`:1419-1530`）。**TODO(verify)**：段内 `\e`（清 MesSkip）与 ONEINPUT 截首字符
  （`:1443-1461`）、`InputType.Void` 空段丢弃、`DISABLE_INPUT_MACRO` 下不拆宏但保留 `\n` 分段 ——
  移植侧只落地了「按 `\n` 拆段、每次取值等待消费一段」，其余见 `sem/StatementExecutor.kt`
  的 `sequenceOutcome` KDoc。语料（B）：全包唯一调用 `ERB/コマンド関連/USERCOM.ERB:177`。
- `SETIMAGELAYERL` 的形参构造器 `ArgumentBuilder.cs:2679-2728`（数组 `:2687-2690`：String + Integer×6 +
  Void，`minArg=2`；**重写 `CreateArgument` 不走通用 `checkArgumentType`**，只校验第 1/2 槽
  `:2706-2715`，超 8 个实参静默丢弃 `:2719-2725`），注册表绑定 `ArgumentBuilder.cs:255`、
  `FunctionArgType.cs:95`；指令类 `Instraction.Child.cs:4161-4209`（`flag=METHOD_SAFE|EXTENDED`
  `:4166`、`lineNo=GetLineNo` `:4188`、height 回落 `:4192-4198`、坐标公式 `:4199-4205`、
  `followScroll=true` `:4207`），注册 `FunctionIdentifier.cs:283`。
- **需组长接线**：本仓 `host/GraphicsSink` 无图层接口、也无 `SETIMAGELAYER`/`CLEARIMAGELAYER` 语句，
  故 `SETIMAGELAYERL` 只把图层登记进 `store.aux` 的 `sprite.layer` 槽
  （`sem/StatementExecutor.kt` 的 `ImageLayerRegistry`，按 depth 索引，保留 lineNo/followScroll）；
  最终屏幕坐标依赖 `LineHeight`/`ClientHeight`/`GetLinePointY`（core 层没有这三项数据面）
  与 `GraphicsSink` 的图层写入口，须由渲染层接线后消费该槽。
- 语料（B）：`ERB/魔改内容/PRINT系/Flan_UI.ERB:153,296,314,325`（`SETIMAGELAYERL` 共 4 处调用，
  实参 6~7 个）；`IMAGELAYER` 在 4.980 语料零命中，本仓 Kotlin 侧此前也无实现。
- 装载闸口径：`isa.yaml` 由 175 条增至 **177** 条（`display 51→52`、`input 17→18`；
  `confidence high 171→172 / medium 4→5`）；§23.15 记的「175 条」为加条前读数。

## 24. 2026-11-01 4.981 升版 · C 路：SAVEDATA 声明 diff + 迁移链取证 + 合成档语义锁（worker lane-save，worktree `<work-root>/eratw-wt-u981-save`，分支 `wave/u981-save`）

对应契约 `CONTRACT-U981-SAVE.md`（§10.3 存档兼容结论的 W4 落账）。本节只追加，不改旧节。

### 24.1 取证口径与方法

- 语料（只读）：4.980 = `<work-root>/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980/ERB`，4.981 = `<work-root>/eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3/ERB`。
- 声明模式：game 族 `#DIMS? SAVEDATA <名>` 与 chara 族 `#DIMS? CHARADATA SAVEDATA <名>`（ERB/ERH 全树，大小写全覆盖含小写 `.erh`/`.erb`）。
- 注释行剔除是**行级**的：匹配点之前出现 `;` 的行一律不算声明（行首注释与行中注释都盖住）。实测踩过三类伪影并已修正：
  1. `;` 注释行里的历史声明（4.981 `DIM.ERH:255-260` 的 `PLANT_*` 5 元素旧声明、`DIM.ERH:574/585/957`、`DLC/DLC.ERH:24,61,62` 等）——若按「名字在任何注释行出现过就整名剔除」会误删真实声明（`PLANT_*` 在 `DLC/生活系统大修/0_生活系统_变量.ERH:90-97` 有 240 元素真身），必须逐行剔；
  2. `-o` 模式会把行中注释的前缀吃掉（`MY宴会.ERB:46` 的 `;宴会名(#DIMS SAVEDATA EnkaiTitle)` 产出伪名 `EnkaiTitle)`）——用完整行管道判定；
  3. `--include` glob 大小写敏感，`*.ERH` 漏掉小写 `.erh`（`DLC/音乐补丁/补丁参数.erh:8-13` 的 `BGMVOLUME`/`实用设置开关`/`新版音乐文件夹模式` 等）——补全后清单 +2。
- 清单以解析器口径自检的替代：以上管道两次独立实现（`-o` + 名级剔注释 vs 完整行 + 行级剔注释）结果对齐，残余差异均可归因到上述三类伪影。
- 抽查误报：随机抽 20 名回查声明行原文，均为真实 `#DIM`/`#DIMS` 声明。

### 24.2 声明总量与 diff 实数

| 项 | 4.980 | 4.981 |
|---|---|---|
| SAVEDATA 声明名（game + chara，注释剔除后） | 532 | 746 |
| 4.981 新增 | — | **217**（game 族 212 + chara 族 5） |
| 4.981 删除（旧档丢弃键） | — | **3**：`MP_GRADE`、`P_AdjList`、`P_AdjListDone` |

> 实取 217 ≠ 实施方案 §10.3 估的「约 27」：该 27 是当时仅对 `生活系统_存档版本` 迁移链直接相关的少数变量的估计。本节以实取为准（契约 §3.1 明示）。§10.3 的结论「真风险：0 是否等于未初始化需逐个确认」不变，且适用面扩大到全部 217 个。

**新增分组**：`ADD_BANQUET*`（宴会补丁）21 个；`ADD_TOUCH*`/`ADD_TEMPTOUCH*`（触摸补丁）16 个；`DISH*`/`EX_DISH_NAME`（料理）15 个；`COM640_LRM_*` 3 个 + `COM641_AC_*`（卡牌）23 个；`生活系统_*`（生活系统大修）57 个；`Setting_*`（实用设置补丁）10 个；其余散件 72 个。
**chara 族新增（5）**：`CUSTOM_DAILY_OUTFIT`（`fromEN/QOL/QoL_Misc.ERH:90`）、`EVENT_CLOTHING_SET_PARAM/SOURCE/STACK`（`fromEN/QOL/QOL_CLOTHES_MISC.ERB:30-80` 一族，声明在 `QoL_Misc.ERH` 同文件）、`SEMEN_MATRIX`（`魔改内容/SEMEN_MATRIX.ERH:7`）。
**删除 3 个的落点**：4.980 独有声明（grep 定位 `DIM.ERH` 域），4.981 全树无声明——旧档读入时按未知键丢弃（save-format.md §5.3 口径），属 §10.3「被删变量真丢失」一类。

**4.981 新增 217 名全单**（按字母/编码序）：
`ADD_BANQUET12_BAD_EXPERIENCE`、`ADD_BANQUET12_DISASTER`、`ADD_BANQUET12_KASEN_HOST`、`ADD_BANQUET12_PARTICIPATION_TIME`、`ADD_BANQUET12_POPULARITY`、`ADD_BANQUET12_PREPARATION_STAGE`、`ADD_BANQUET12_QUALITY`、`ADD_BANQUET12_TOOLS`、`ADD_BANQUET21_INVITE`、`ADD_BANQUET43_LIQUOR_ITEM`、`ADD_BANQUETS_DURATION`、`ADD_BANQUETS_INVITED`、`ADD_BANQUETS_LAST_DAY_CHECKED`、`ADD_BANQUETS_LIQUOR_STOCK_REDUCTION`、`ADD_BANQUETS_LOCATION`、`ADD_BANQUETS_SCALE`、`ADD_BANQUETS_START_DAY`、`ADD_BANQUETS_START_TIME`、`ADD_BANQUETS_STATUS`、`ADD_BANQUETS_UPCOMING`、`ADD_BANQUETS_VISITED`、`ADD_TEMPTOUCHMODE`、`ADD_TEMPTOUCH_ACTIVE`、`ADD_TEMPTOUCH_ACTOR`、`ADD_TEMPTOUCH_ACTOR_PART`、`ADD_TEMPTOUCH_POSE`、`ADD_TEMPTOUCH_RESET`、`ADD_TEMPTOUCH_TARGET`、`ADD_TEMPTOUCH_TARGET_PART`、`ADD_TOUCH_ACTIVE`、`ADD_TOUCH_ACTOR`、`ADD_TOUCH_ACTOR_PART`、`ADD_TOUCH_HISTORY`、`ADD_TOUCH_MARK_REMOVE`、`ADD_TOUCH_POSE`、`ADD_TOUCH_TARGET`、`ADD_TOUCH_TARGET_PART`、`AI对话补丁`、`Activity_Duration`、`Activity_Type`、`Add_Fairy_Prank_Betrayal`、`Add_Fairy_Prank_Evil_Shenanigans`、`Add_Fairy_Prank_Success`、`BEFORE_MOUSOU_CFLAG`、`BEFORE_MOUSOU_CSTR`、`BEFORE_MOUSOU_EX`、`BEFORE_MOUSOU_EXP`、`BEFORE_MOUSOU_TALENT`、`BGMVOLUME`、`COM640_LRM_MAIN_MAP`、`COM640_LRM_MAPID`、`COM640_LRM_ODEKAKE_MAP`、`COM641_AC_カードデータ_売却ロック`、`COM641_AC_カードデータ_編集ロック`、`COM641_AC_カード効果ID_特殊効果`、`COM641_AC_カード所有数`、`COM641_AC_カード数値_うふふ補助`、`COM641_AC_カード数値_ステータス補助`、`COM641_AC_カード数値_価格`、`COM641_AC_カード数値_戦闘補助`、`COM641_AC_カード数値_技能補助`、`COM641_AC_カード装備スロット数`、`COM641_AC_キャラカード登録枚数`、`COM641_AC_セット中`、`COM641_AC_セーブデータのバージョン`、`COM641_AC_初期化チェック`、`COM641_AC_文字データ_カードレアリティ`、`COM641_AC_文字データ_カード名`、`COM641_AC_文字データ_カード詳細`、`COM641_AC_記録配列_うふふ補助`、`COM641_AC_記録配列_ステータス補助`、`COM641_AC_記録配列_実装状態`、`COM641_AC_記録配列_戦闘補助`、`COM641_AC_記録配列_技能補助`、`COM641_AC_記録配列_特殊効果`、`COOKING_LCOUNT`、`CUSTOM_DAILY_OUTFIT`、`DEBUG_HISTORY`、`DISHNAME`、`DISH_ASSI`、`DISH_BASEPOINT`、`DISH_COMMENT`、`DISH_DRG`、`DISH_DRG_2`、`DISH_ING`、`DISH_LIMIT`、`DISH_MAXLIMIT`、`DISH_NAME`、`DISH_RANK`、`DISH_TASTE`、`DISH_TIME`、`DISH_TYPE`、`DLC_Toggle`、`EVENT_CLOTHING_SET_PARAM`、`EVENT_CLOTHING_SET_SOURCE`、`EVENT_CLOTHING_SET_STACK`、`EXTRA_UFUFU_TIME`、`EX_DISH_NAME`、`FA16_HOLE`、`FA17_EXIST`、`FA17_OBJ`、`FOUND_PANTS_OWNER`、`FOUND_PANTS_TYPE`、`GO_OUT_FLAG_09`、`IRAI_STR`、`JOB_NAME`、`MACRO_INDEX`、`MACRO_PRESET`、`MASTER_PREV_LOCATION`、`MHW_CustomRoomName`、`M_SPELL`、`PLANT_HARVESTED`、`SEMEN_MATRIX`、`SPELL_LEVEL_RESETED`、`Setting_FLAG`、`Setting_TFLAG`、`Setting_单独一人吃饭`、`Setting_即时重载`、`Setting_测试妄想自慰`、`Setting_背景图片`、`Setting_跟随型后宫`、`Setting_逆推反感禁止`、`Setting_逆推口上找补`、`Setting_高亮背景图`、`UNIQUE_FA_NAME`、`WEDDING_OFFICIANT_ID`、`WEDDING_OFFICIANT_NAME`、`nMilkCount`、`nMilkHint`、`储存前BUFF_TSP`、`储存前TSP`、`催眠_HISTORY`、`半神取得履历`、`妄想产物编号`、`妄想自慰补丁`、`孵化槽_剩余天数`、`孵化槽_生物ID`、`孵化槽_蛋种`、`实用设置开关`、`差分临时储存`、`所在地察觉_其他区域立绘显示`、`抚摸済み`、`新版音乐文件夹模式`、`无限TSP`、`无限法力`、`旧日取得履历`、`测试补丁`、`测试补丁_附加1`、`测试补丁_附加2`、`生活系统_上次基础畝数`、`生活系统_伐木增强`、`生活系统_倒掉饭菜`、`生活系统_养蜂`、`生活系统_养鸡`、`生活系统_天堂制造加速`、`生活系统_妖精好感`、`生活系统_存档版本`、`生活系统_学习教育`、`生活系统_巧克力转换`、`生活系统_开荒数`、`生活系统_开荒日期`、`生活系统_悟性`、`生活系统_指令开始时刻`、`生活系统_授粉标记`、`生活系统_教育_价格档`、`生活系统_教育_同伴经验`、`生活系统_料理委托改善`、`生活系统_武道大会`、`生活系统_武道大会_奖励领取日`、`生活系统_武道大会_胜利数`、`生活系统_水龙头`、`生活系统_活跃妖精`、`生活系统_熊好感`、`生活系统_熊敲门剩`、`生活系统_熊赶跑剩`、`生活系统_种地`、`生活系统_花田妖精`、`生活系统_蜂王保底`、`生活系统_蜂箱有蜂`、`生活系统_蜂箱蜜`、`生活系统_蜂箱进度`、`生活系统_读书`、`生活系统_酿酒`、`生活系统_钓鱼`、`生活系统_钓鱼_难度`、`生活系统_隙间扩容次数`、`生活系统_音乐`、`生活系统_魔法联动`、`生活系统_鱼塘`、`生活系统_鱼塘产卵剩`、`生活系统_鱼塘产卵季`、`生活系统_鱼塘孵化剩`、`生活系统_鱼塘建造中`、`生活系统_鱼塘建造剩余`、`生活系统_鱼塘性别`、`生活系统_鱼塘成长剩`、`生活系统_鱼塘数量`、`生活系统_鱼塘繁殖剩`、`生活系统_鱼塘请求`、`生活系统_鱼塘请求剩`、`生活系统_鱼塘请求物`、`生活系统_鱼塘请求量`、`生活系统_鱼塘长度`、`生活系统_鱼塘鱼数`、`生活系统_鱼塘鱼种`、`生活系统_鱼塘鱼种ID`、`符咒铭刻位`、`育苗槽`、`背景图补丁开关`、`背景展示开关`、`花屋苗今日获得`、`花屋苗解锁`、`衬底透明度`、`酒桶_内容物`、`酒桶_天数`、`饲料存量`、`高亮背景图开关`。

### 24.3 「默认 0 等价未初始化」逐变量判定

**判定为等价（安全）——开局路径消费的 8 个 + chara 族 5 个，共 13 个**，每条带声明点与首个消费点：

| 变量 | 声明 | 首个消费点 | 判据 |
|---|---|---|---|
| `Setting_FLAG` | `DLC/实用设置补丁/实用设置.ERH:15` | `SYSTEM.ERB:175-177` | 开局自愈：`SIF !Setting_FLAG:常量更新` → `CALL 实用设置常量更新` → 置 1。0 = 未做，正中设计 |
| `Setting_TFLAG` | `实用设置.ERH:16` | （全库无消费点） | 仅声明未使用，0 填充平凡无影响 |
| `无限TSP` | `DLC/魔法DLC/魔法相关参数.ERH:24` | `COMMON.ERB:611` | `SIF 无限TSP && ARGS == "TSP"` → 回复量 0；0 = 正常回复。开关 setter：`DLC/魔法DLC/魔法相关函数.ERB:1743-1747`、清零 `:3415-3416` |
| `无限法力` | `魔法相关参数.ERH:25` | `COMMON.ERB:613` | 同型：`SIF 无限法力 && ARGS == "法力"`，0 = 正常回复 |
| `EXTRA_UFUFU_TIME` | `DLC/魔法DLC/魔法相关参数.ERH:26` | `魔改内容/UFUFU_LOG.ERB:45`（加法） | 加法恒等：`TIME_PROGRESS(...) + EXTRA_UFUFU_TIME`，0 = 无加时；`BASE自然変動.ERB:631` 亦 `+=`；`COMMON.ERB:1305` 有显式清零点 |
| `DLC_Toggle` | `DLC/DLC.ERH:60`（`200,10` 二维） | `DLC/OPTIONDLC.ERB:6/30` | 派生 UI 缓存：DLC 菜单打开时 `UPDATE_DLC_Toggle`（`魔法相关函数.ERB:35-49`）从各 gameplay 变量整体回填（`:42-49`）；开局选择写侧 `NEWGAME/NEWGAME_MAKE.ERB:55-90`。缺 0 只影响菜单首显，gameplay 主数据在旧变量里 |
| `生活系统_存档版本` | `DLC/生活系统大修/0_生活系统_变量.ERH:16` | `VERSION_UP.ERB:221-242` | 自愈设计：0 < 框架版本(=1，`0_生活系统_变量.ERH:15`) 触发迁移块并写回 1（`:242`） |
| `PLANT_HARVESTED` | `0_生活系统_变量.ERH:97`（240 元素，0=未收 1=已收） | `DLC/生活系统大修/生活系统_COM418.ERB:305` | `SIF PLANT_HARVESTED:ARG` / `:469` `!PLANT_HARVESTED:ARG`——0 即「未收」缺省语义；迁移块 `VERSION_UP.ERB:238` 亦显式写 0 |
| `半神取得履历` | `DLC/魔法DLC/魔法相关参数.ERH:79`（#DIMS） | `ステータス表示関連/PRINT_STATE2.ERB:215` | 消费点显式容忍空串：`!= "尚未取得" && != ""`；空串 = 未取得 |
| `旧日取得履历` | `魔法相关参数.ERH:80`（#DIMS） | `PRINT_STATE2.ERB:213` | 同上 |
| `CUSTOM_DAILY_OUTFIT` | `fromEN/QOL/QoL_Misc.ERH:90` | `コマンド関連/COMF/日常系/COMF429 着せ替え.ERB:136` | `SIF CUSTOM_DAILY_OUTFIT:C_ID:LOCAL`——0 = 空槽跳过（`:133` 注释明说防空存储） |
| `EVENT_CLOTHING_SET_STACK/SOURCE/PARAM` | `fromEN/QOL/QoL_Misc.ERH` 域 | `fromEN/QOL/QOL_CLOTHES_MISC.ERB:22` | `SIF EVENT_CLOTHING_SET_STACK:C_ID:LOCAL == 0`——0 = 空栈位，SOURCE/PARAM 仅在 STACK 非 0 时被读（`:60`、`:80`） |
| `SEMEN_MATRIX` | `魔改内容/SEMEN_MATRIX.ERH:7` | （chara 族，非开局路径） | 计数矩阵，0 = 无记录；消费点未核，归入 24.3 末段 TODO(verify) |

**挂 TODO(verify)——其余 204 个**，按族分组与卡点（不许假装判完）：

- `生活系统_*` 其余 52 个（声明集中 `DLC/生活系统大修/0_生活系统_变量.ERH:17-146`）：绝大多数是开关/计数器（声明行注释即语义，0 = 关/空），但逐个消费点未核。重点疑名单：`生活系统_鱼塘数量`（注释「默认1，上限5」——0 是否被当作「无塘」且与开荒流程兼容待核，`0_生活系统_变量.ERH:63`）、`生活系统_开荒数`/`生活系统_上次基础畝数`/`生活系统_蜂王保底`（计数器 0 起步应安全，消费式样未核）。
- `ADD_BANQUET*` 21 个：消费集中 `fromEN/Addition/Banquets/`（如 `Add_Banquet_Drinking_Common.ERB:101-270` 的数组读法 `ADD_BANQUETS_SCALE:BANQUET_INDEX`），依赖 `ADD_BANQUETS_STATUS/UPCOMING` 等状态机的初值约定，未核。TODO(verify)。
- `ADD_TOUCH*`/`ADD_TEMPTOUCH*` 16 个、`DISH*` 15 个、`COM640_LRM_*` + `COM641_AC_*` 26 个：模组玩法状态，均非开局路径。TODO(verify)。
- `Setting_*` 其余 8 个（`实用设置.ERH`）：开关族，预期与 `Setting_FLAG` 同型自愈，未逐个核。TODO(verify)。
- 模板/未接线变量：`测试补丁`、`测试补丁_附加1/2`（`DLC/完全新增的DLC模板/Addition_DLC_X.ERH:1-3`）、`AI对话补丁`（`DLC/AI对话补丁/AI返回参数.ERH:12`，实施方案 §10.2 W6 已知未接线）。低风险，TODO(verify)。
- 其余散件（`FA16_HOLE`、`FOUND_PANTS_*`、`MACRO_*`、`WEDDING_OFFICIANT_*`、`nMilk*`、`酒桶_*`、`育苗槽`、`饲料存量` 等约 45 个）：声明点已定位（多数在 `DIM.ERH` 4.981 版或各 DLC 头文件），消费点未核。TODO(verify)。

### 24.4 迁移链落账（4.981）

- **触发行**：`ERB/VERSION_UP.ERB:25` —— `IF SAVESTR:本体バージョン != eraTW_Version || NOW_VERSION != modding_version`；块内做 exp 数据存储重排（`:27-32`）、孕数据迁移（`:35-46`，`modding_version < 20250413` 时 `CFLAG:(880+i)` → `CHILD_DADDY`）。
- **调用点**：`SYSTEM.ERB:74`（`@EVENTLOAD` 内，`INIT_NG_OR_LOAD`/`ROOMSETTING` 之后）与 `NEWGAME/NEWGAME.ERB:209`（`@EVENTFIRST` 链）。
- **降版警告**：`VERSION_UP.ERB:218-219` `SIF modding_version > NOW_VERSION → PRINTFORML ※您正在将版本更高的存档加载到游戏`（与 §10.3「单向」结论一致：只提示、不带未知键继续跑）。
- **生活系统子系统迁移块**：`VERSION_UP.ERB:221-243`。`IF 生活系统_存档版本 < 生活系统大修_框架版本`（框架版本常量 = 1，`0_生活系统_变量.ERH:15`）：
  - 育苗用品一式保底转换：`ITEM:192 > 0` → `ITEM:908 = MIN(ITEM:192, 3)`、`ITEM:192 = 0`（`:224-228`，夹取防 `育苗槽` 第一维越界，注释在 `:222-223`）；
  - 清理误种的种子培育设施：`PLANT_TYPE:LOCAL == 908`（LOCAL 0..4，旧档 5 元素口径）时清 `PLANT_TYPE/DAYS/GROW/DRY/HATAKE_NAME/PLANT_FOOD/PLANT_HARVESTED`（`:230-241`）；
  - 版本戳：`生活系统_存档版本 = 生活系统大修_框架版本`（`:242`）。
  - 注意：该迁移块只覆盖种地/育苗一系；`生活系统_*` 其余子系统没有各自的迁移块（它们整体是 4.981 新增，不存在「旧值搬运」需求），与 24.3 的 0 等价判定互补。

### 24.5 GameBase.csv 勘误（修正 §10.3 第 2 条措辞）

两版 `CSV/GameBase.csv` 实测均为：

```
コード,7153
バージョン,proto
バージョン違い認める,0012
```

- `ScriptUniqueCode` **有**声明 = 7153（GameBase.cs:132-137）——§10.3 说「两版都没有声明 ScriptUniqueCode/ScriptVersion」不准确；
- `バージョン,proto` ⇒ `tryatoi`（GameBase.cs:82-104）遇非数字失败落 0 ⇒ `ScriptVersionDefined = false` 且 `ScriptVersion = 0`（赋值点 GameBase.cs:139，C# `out` 在 TryParse 失败路径落 0）——「未声明」实质是「声明了但解析失败落 0」；
- `バージョン違い認める,0012` ⇒ `ScriptCompatibleMinVersion = 12`（GameBase.cs:141-143）；
- 版本闸实际走第 1 条放行（GameBase.cs:71-74，`!ScriptVersionDefined && target != 1000`），真档 `scriptVersion` 实测 0（specs/save-format.md §6.3 表）恒放行；
- 唯一码对拍：真档 `uniqueCode` 实测 7153（同 §6.3 表）与 `ScriptUniqueCode` 相等（GameBase.cs:66）。
- **结论不变**：4.980 档在 4.981 下、4.981 档在本仓探测下都过闸；但语义是「版本行写了 proto + 唯一码恰好一致」，不是「两者都没写」。

**存档头 scriptVersion 的比较分支与调用点**（组长接线补充，2026-11-01）：

- 分支本体：GameBase.cs:69-80 —— 第 1 条 `:71-74`（`!ScriptVersionDefined && target != 1000`）、第 2 条 `:75-78`（`ScriptCompatibleMinVersion <= target`）、版本相等比较 `:79`（`ScriptVersion == target`）。
- 调用点（target = 从存档头读出的 scriptVersion，`ReadInt64` 之后紧跟 `CheckVersion`）：文本 CHKDATA `VariableEvaluator.cs:2183-2190`、二进制 CHKDATA `:2209-2215`、文本 LOADDATA `:2435`、二进制 LOADDATA（`LoadFromStreamBinary`）`:2620-2623`；不过闸即 `VIRSION_ERROR`（`:2211-2212` 等）。
- **两层口径**（都用例锁死，见 24.6 第 6 条）：键不存在 ⇒ `ScriptVersionDefined` 保持字段初值 false（GameBase.cs:23/25）、ScriptVersion=0，走第 1 条；键存在但解析失败（proto）⇒ 同为 Defined=false + ScriptVersion=0（GameBase.cs:139），仍走第 1 条——版本闸层面两形态**不可区分**。
- 口径澄清：eraTW **两版** CSV 的バージョン行都是 `proto`（§24.5 开头两版实拍一致），不存在「4.980 键不存在」的实况；「键不存在」形态作为防御性口径保留用例，防上游换 CSV。

### 24.6 合成档定向用例

新增 `core-vm/src/test/kotlin/com/eratw/port/core/save/SaveUpgradeSemanticsTest.kt`（6 用例，全绿；零环境依赖、不吃 ERATW_* 门）：

1. `升版档的 4点981 新增键被丢弃 既有键照常生效` —— keepKey（4.980 口径）下真实 4.981 新增键（`生活系统_存档版本`/`ADD_BANQUETS_SCALE`/`孵化槽_蛋种`/`生活系统_养蜂`）整条不落地，`MONEY`/`DAY`/`CFLAG` 值保留；旧口径（无过滤器）全量读入不炸。对齐 save-format.md §2.2/§5.3 与 `SaveUnknownKeySkipTest` 的两口径。
2. `ScriptVersion 未声明时任意存档版本号都放行` —— `GameBaseGate.checkVersion`，gb 复刻两版 GameBase.csv 实值（24.5）；target=0（真档值）与任意非 1000 值走第 1 条（GameBase.cs:71-74），target=1000 落到兼容下限第 2 条。
3. `唯一码对拍 存档码 0 豁免 其余必须等于 7153` —— `GameBaseGate.uniqueCodeEqualTo`（GameBase.cs:60-67）。
4. `探测入口对真档同款头判 OK` —— `SaveDataProbe.checkBytes(bytes, NORMAL, eratwGameBase())`，uniqueCode=7153/scriptVersion=0（= §6.3 实测头）→ `EraDataState.OK`。
5. `GAMEBASE バージョン解析失败落 0 与缺行同判` —— 组长接线补充的两层口径：形态 A（バージョン行不存在，versionDefined 交 null 退近似）与形态 B（行存在值 proto，tryatoi GameBase.cs:82-104 失败落 0、GameBase.cs:139 赋 Defined=false）对同一 target 判等必须一致，且任意非 1000 target 都走第 1 条先于 `:79` 相等比较。存档头 scriptVersion 比较分支的调用点行号：`VariableEvaluator.cs:2183-2190`（文本 CHKDATA）、`:2209-2215`（二进制 CHKDATA）、`:2620-2623`（LoadFromStreamBinary/LOADDATA）。
6. `缩微升版档装载后新增变量读回 0 与空串` —— 生产装载路径（`FileSystemOps.loadData` = `resetValues` + `SaveData.applyTo`）灌回只有 `MONEY`/`DAY`/`NO`/`CFLAG` 的缩微档后，新声明变量 `生活系统_存档版本`=0、`孵化槽_蛋种`=""、`生活系统_蜂箱蜜[3]`=0（缺失键 = 未初始化），且既有键照常生效。测试装置构造手法与 `SaveRoundTripTest` 同源（`VariableStore()` + `DimParser.parse("#DIM", "SAVEDATA …")` + `MemorySaveIo`）。

过程发现（不涉产品代码改动）：`SaveData.applyEntry` 对「标量条目 vs 数组 def」与「维度不符」一律静默跳过（`core-vm/src/main/kotlin/com/eratw/port/core/data/save/SaveData.kt:281-296`），用例按此以 `IntArray1` 构造 MONEY/DAY 条目——该口径本身已在 main 侧收口，本路只是复用。

### 24.7 真档 4.981 样本缺口

- TODO(verify)：本机无 4.981 产的 `saveXX.sav`/`global.sav`/`chara_*.dat`（`ERATW_SAVE_SAMPLES` 指向的 4.980 备份目录同样不在本机）。合成档判据（24.6）已锁「未知键丢弃 / 缺失键填 0 / 版本闸放行」三条装载语义，但 **4.981 写侧实况**（540→? 条全局条目序、浮点段实际出现形态、`SaveCapture` 写出的字节能否被 PC 端 ErskiaX 回读）未与真档对拍。
- 获取途径建议：
  1. 在装有 4.981（EAGLOV3 整合包）的 PC 端启动一次并存档，取 `sav/save00.sav`、`global.sav` 与 `chara` 目录（最能代表完整写面）；
  2. 沿用 2026-10-29 玩家上报通道收集真实玩家档（见本账 §22）；
  3. 到手后放 `<internal-workdir>/<sample-backup>/sav-<date>-981/`，并**由组长**把样本清单接进 `RealSaveSamplesProbeTest`（该文件本路禁碰，本路保持原样未动）。

## 25. 2026-11-01 4.981 升版 · I 路：H 路符号取证落账 + §24 疑名单头部清账（worker lane-notelog，worktree `<work-root>/eratw-wt-u981-notelog`，分支 `wave/u981-notelog`）

本节只追加，不改 §24 及更早各节。

### 25.1 取证口径

- 源报告：`<work-root>/.tmp-decomp-4981/H-SYMPROBE-REPORT.md`（2026-11-01「组长采信版」）。**来源分层（lane-symprobe 2026-11-01 澄清）**：H 路的原始交付是发给组长的消息本体（任务简报要求最终回复即交付 + 零文件改动），H-SYMPROBE-REPORT.md **不是 lane-symprobe 所写**，属转抄层产物——其中符号名类笔误（见 §25.3 第 2 条）以此归因，消息交付才是权威清单。本节是落账人的**独立复核账**：44 个符号全部由落账人在反编译源里重新定位行号后才入账，不照抄报告。
- 反编译源：`<work-root>/.tmp-decomp-4981/decomp/`（Emuera_skiaV16_x64.exe bundle 手工解出 Emuera.dll，ilspycmd 8.2.0）——**版本未对证**（与 fork commit 48494165 的对应关系未确认，沿用 H 路标注；所有行号同此保留度）。
- 布局差异提示：反编译产物按命名空间重排了文件——fork 的 `Creator.cs`/`Creator.Method.cs` 注册面对应产物 `MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs` 的字符串字典区，`Instraction.Child.cs` 的指令类并入产物 `MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs`。**本节行号全部是反编译产物的 1-based 行号，与各 yaml 里既有 fork-src 行号不通用、不可互换**。
- 符号清单口径（已与 H 路原始交付对表收口）：**44 = builtin-functions 28 注册名 + isa 增量 8 条目 + float_vars 族 8 项符号**（28+8+8=44）。转抄层报告记的「isa +6 / float 族 10」是旧口径——H 原始交付清单（lane-symprobe 2026-11-01 消息补送）isa 段实为 8 条目，float_vars 族实为 8 项符号 + 4 条行为注记（`dimf_chara_quirk`/`dimf_default_value`/`save_note_classic`/`save_note_extended` 是行为注记不单列符号）；isa「+6」的另一种解释（5 个新 AInstruction 类 + TINPUT_Instruction 的 noFocus 变体面）已由 H 自注，落账按 yaml 条目 8 条计。本节 46 项初核（自建口径）与 H 清单对表**零分歧**，双方行号全部互证一致。

### 25.2 逐条复核（全部命中）

**builtin-functions 28 注册名**（产物 `MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`；注册 = 字典区 `["名"] = new XxxMethod()`，实现类行号由落账人逐条复核 28/28 命中）：

| 符号 | 注册行 | 实现类 | 符号 | 注册行 | 实现类 |
|---|---|---|---|---|---|
| `TOFLOAT` | :13175 | ToFloatMethod :7958 | `SQL_CONNECT` | :13379 | SqlConnectMethod :12198 |
| `UNCHECKED_ADD` | :13136 | UncheckedAddMethod :6270 | `SQL_EXECUTE_NONQUERY` | :13381 | SqlExecuteNonQueryMethod :12242 |
| `UNCHECKED_MUL` | :13138 | UncheckedMultiplyMethod :6312 | `SQL_EXECUTE_READER` | :13382 | SqlExecuteReaderMethod :12261 |
| `GETANIMETIMER` | :13253 | GetAnimeTimerMethod :10916 | `SQL_READER_READ` | :13383 | SqlReaderReadMethod :12280 |
| `MAP_VALUES` | :13308 | MapValuesMethod :3545 | `SQL_READER_GET_LONG` | :13384 | SqlReaderGetLongMethod :12295 |
| `MAP_MERGE` | :13309 | MapMergeMethod :3663 | `SQL_READER_GET_STRING` | :13386 | SqlReaderGetStringMethod :12314 |
| `MAP_REMOVEIF` | :13310 | MapRemoveIfMethod :3693 | `SQL_READER_ISNULL` | :13387 | SqlReaderIsNullMethod :12333 |
| `MAP_FINDKEY` | :13311 | MapFindKeyMethod :3785 | `SQL_READER_CLOSE` | :13388 | SqlReaderCloseMethod :12352 |
| `MAP_TOSTRING` | :13312 | MapToStringMethod :3889 | `SQL_EXECUTE_SCALAR_LONG` | :13389 | SqlExecuteScalarLongMethod :12368 |
| `GETPLATFORM` | :13409 | GetPlatformMethod :4863 | `SQL_EXECUTE_SCALAR_STRING` | :13391 | SqlExecuteScalarStringMethod :12387 |
| `DISABLE_INPUT_MACRO` | :13411 | DisableInputMacroMethod :11578 | `SQL_ESCAPE` | :13397 | SqlEscapeMethod :3993 |
| `ENABLE_INPUT_MACRO` | :13412 | EnableInputMacroMethod :11594 | `SQL_IMPORT_MAP_XML` | :13392 | SqlImportMapXmlMethod :12406 |
| `SQL_P_EXECUTE_NONQUERY` | :13398 | SqlExecuteNonQueryParamMethod :4008 | `SQL_P_EXECUTE_READER` | :13399 | SqlExecuteReaderParamMethod :4046 |
| `SQL_P_EXECUTE_SCALAR_LONG` | :13400 | SqlExecuteScalarLongParamMethod :4084 | `SQL_P_EXECUTE_SCALAR_STRING` | :13402 | SqlExecuteScalarStringParamMethod :4122 |

**isa 增量 8 条目**（产物 `MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs`，另注明者除外）：

| 条目 | 注册行 | 实现类 / 参数构造（产物行号） |
|---|---|---|
| `TINPUTNF`（含 `TONEINPUTNF`） | :4439（TONE :4441；枚举 FunctionCode.cs:38/:40） | `TINPUT_Instruction` :1082（无焦点分支 :1130 → WaitInputNoFocus） |
| `TINPUTSNF`（含 `TONEINPUTSNF`） | :4440（TONE :4442；枚举 FunctionCode.cs:39/:41） | `TINPUTS_Instruction` :1139 |
| `HTML_PRINTC` / `HTML_PRINTLC` | :4657 / :4658（枚举 FunctionCode.cs:320-321） | 类 :417；`SP_HTML_PRINTC_ArgumentBuilder` ArgumentParser.cs:217、绑定 :2929 |
| `TEXT_BGC_ON` | :4512（枚举 FunctionCode.cs:165） | 类 :1647；`SP_COLOR_ALPHA_ArgumentBuilder` ArgumentParser.cs:2054、绑定 :2906 |
| `TEXT_BGC_OFF` | :4513（枚举 FunctionCode.cs:166） | 类 :1675 |
| `CALLS`（aliases 6 名） | JUMPSTR :4567、CALLSTR :4568、TRYJUMPSTR :4569、TRYCALLSTR :4570、TRYCJUMPSTR :4571、TRYCCALLSTR :4572 | `CALLS_Instruction` :3933；funcMatch :4683-4684；枚举 FunctionCode.cs:322-327；ErbLoader TRY 配对校验 ErbLoader.cs:1218-1219、:1385 |
| `SEQUENCEINPUT` | FunctionMethodCreator.cs:13410 | `SequenceInputMethod` :11561（写 sequenceInputValue :11572-11573） |
| `SETIMAGELAYERL` | :4505（枚举 FunctionCode.cs:145） | 类 :4125；`SP_SETIMAGELAYERL_ArgumentBuilder` ArgumentParser.cs:2768、绑定 :2932 |

**float_vars 族 8 项符号**（落账口径 = H 原始交付 8 项；variables.yaml float_vars 段另有 4 条行为注记 `dimf_chara_quirk`/`dimf_default_value`/`save_note_classic`/`save_note_extended`，非符号不单列，其 C# 锚点已含于下表）：

| # | 符号 | 复核行（产物，另注明者除外） |
|---|---|---|
| 1 | `RESULTF` | 枚举 VariableCode.cs:109（=0x1000001）；描述表注册 VariableDescriptorTable.cs:28（Float/Scalar/Extended）；token VariableData.cs:4074（`FloatScalarVariableToken` 类 VariableData.cs:336）；读写 VariableEvaluator.cs:55/:59（`DataFloat[1]`） |
| 2 | `LOCALF` | 枚举 VariableCode.cs:94（0x3090000）；VDT.cs:73（CanForbid/Local/Extended）；注册 VariableData.cs:4239（`CreateLocalFloat` :4254）；消费 VariableData.cs:1418（ctx.LocalFloats） |
| 3 | `ARGF` | 枚举 VariableCode.cs:95；VDT.cs:74；注册 VariableData.cs:4241；消费 VariableData.cs:1419（ctx.ArgFloats）；下界钳制名单不含 LOCALF（VariableLocal.cs:70：`ARG || ARGS || ARGF` 三名，复核命中） |
| 4 | 存储四件套 `dataFloat`/`dataFloatArray`/`dataFloatArray2D`/`dataFloatArray3D` | 字段 VariableData.cs:3903/:3909/:3915/:3921；分配 :4049-4056（2/7/空容器）；清零 :4518（循环 :4542-4544）；长度表本体 ConstantData.cs:112-120、:338-345（长 2、出厂 10、2D/3D/角色四表空容器）、写入支 ConstantData.cs:692-694 |
| 5 | `#DIMF` | 探测 LogicalLineParser.cs:309-313（dimf → `Create(…, dimf, isPrivate: true)`）；Create 签名 UserDefinedVariableData.cs:97（`TypeIsFloat = dimf` :103） |
| 6 | `#REFF` | 探测 LogicalLineParser.cs:331-332（→ `CreateRefScalar(isFloat)`，签名 UserDefinedVariableData.cs:45）；REFF token `ReferenceFloatScalarToken` VariableData.cs:3536（H 原报 :3537，复核实为 :3536 类声明行、:3537 为其 base 调用行——本账唯一行号微差，语义无影响） |
| 7 | `#LOCALFSIZE` | LogicalLineParser.cs:289-302（LOCALF 禁用告警 :291-293） |
| 8 | VARIABLESIZE.CSV 浮点档 | 表本体/出厂值见 #4 的 ConstantData.cs 行；不可达 IsFloat 分支 ConstantData.cs:634/:647/:663/:679（复核命中，同 :692-694 可达写入支） |

与 yaml 既有读数的一致性抽查：`dataFloat` 复用 `__COUNT_STRING__`=2、`dataFloatArray` 复用 `__COUNT_STRING_ARRAY__`=7（variables.yaml float_vars.storage）与产物 :4049-4050 的字面量 2/7 一致；`RESULTF = 0x1000001` 与 VariableCode.cs:109 一致。**复核结论：44 符号（28+8+8）逐条对表全命中，唯一微差 = #6 的 REFF token 行 ±1（:3537 vs :3536，类声明 vs base 调用行），无语义影响**。

### 25.3 附加发现 4 条落账（H 报告原文 + 落账复核）

1. **注册面比 yaml 宽**——复核成立。4.981 实机还注册了语料未用的一批名（产物 FunctionMethodCreator.cs）：`UNCHECKED_SUB` :13137、`UNCHECKED_NEG` :13139、`TOSTRF` :13176、`SQL_CONNECTION_OPEN` :13378、`SQL_DISCONNECT` :13380、`SQL_READER_GET_FLOAT` :13385、`SQL_EXECUTE_SCALAR_FLOAT` :13390、`SQL_IMPORT_DT_XML` :13393、`SQL_EXPORT_MAP_XML` :13394、`SQL_EXPORT_DT_XML` :13395、`SQL_IMPORT_XML_CUSTOM` :13396、`SQL_P_EXECUTE_SCALAR_FLOAT` :13401（另 `EVAL/EVALF/EVALS` :13372-13374、`BITSET/BITGET/BITTOGGLE` :13403-13405 等更宽，此处只登 SQL/浮点相关）。语料零使用 ⇒ 不阻塞，登记即可；与 §23.2「Creator.cs:404-428 注册 25 个 SQL 名」的 TODO(verify) 相互印证。
2. **CALLSTR 家族勘误登记（正式，isa.yaml 本体不动）**——实质结论成立；「6 名符号名有误」经 lane-symprobe 2026-11-01 澄清定性为**转抄层笔误**（H-SYMPROBE-REPORT.md 非 lane-symprobe 所写，见 §25.1 来源分层），H 原始交付清单符号名本就正确：
   - 转抄层报告作：「CALLSTR/CALLSTRV/TRYCALLSTR/TRYCALLSTRV/CCALLSTR/TRYCCALLSTR 6 名在 4.981 已全部进枚举（isa.yaml:2492-2493『4 名尚未进枚举』注记过时）」。
   - 复核实为：`CALLSTRV` / `TRYCALLSTRV` / `CCALLSTR` 在反编译源全树 **0 命中**（疑为 `TRYCCALLSTR` 被截断重排的转抄伪影）。4.981 实机进枚举并注册的 6 名 = **`CALLSTR` / `JUMPSTR` / `TRYCALLSTR` / `TRYJUMPSTR` / `TRYCCALLSTR` / `TRYCJUMPSTR`**（枚举 FunctionCode.cs:322-327、注册 FunctionIdentifier.cs:4567-4572、funcMatch TRYCCALLSTR/TRYCJUMPSTR→CATCH :4683-4684）——与 H 原始交付清单逐字一致。
   - 「注记过时」要分口径：isa.yaml:2492-2493 原文是「CALLSTR/JUMPSTR/TRYJUMPSTR/TRYCJUMPSTR 的 fork flag（33/97/227/33011）暂不登记：这 4 名尚未进 FunctionCode 枚举、语料零使用」。同段上一行引的是 `FunctionCode.kt:126-131`，即该注记指的是**本移植侧** Kotlin 枚举——复核本仓 `core-vm/src/main/kotlin/com/eratw/port/core/stmt/FunctionCode.kt:489-490` 确实只有 `TRYCALLSTR`/`TRYCCALLSTR`，4 名未登记 ⇒ 注记对移植侧**仍准确**，不是过时；对 fork 侧则 4 名 4.981 实机确已全部进枚举并注册。
   - **正式勘误**：isa.yaml:2492-2493 建议补一句「fork 侧 4.981 已全部进枚举并注册（反编译源 FunctionCode.cs:322-327、FunctionIdentifier.cs:4567-4572），『尚未进枚举』仅指本移植侧 FunctionCode.kt」；yaml 本体不改，以本条为账面勘误。移植影响：4 名在 4.981 语料零使用（与注记一致）不阻塞；未来若语料/玩家脚本用到，按 flag 33/97/227/33011 补登 FunctionCode.kt。
3. **fork 对应关系仍未对证**——沿用：反编译源与 fork commit 48494165 的对应关系未确认，本节全部行号按「版本未对证」保留度记账。
4. **反编译产物路径保留**——沿用：`<work-root>/.tmp-decomp-4981/` 保留供后续取证复用。

### 25.4 §24 疑名单头部清账（TODO(verify) 转「已判」，5 项）

按 §24.3 已判 13 个的同口径（声明点 + 4.981 语料首个消费点 + 「0 是否等价未初始化」）逐个判定。语料 = `<work-root>/eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3/ERB`（只读）。

1. **`生活系统_鱼塘数量` —— 0 等价（0 = 无塘，合法空态）**。声明 `DLC/生活系统大修/0_生活系统_变量.ERH:63`（注释「默认1，上限5」）。消费点全集（ERB 全树 9 处命中全核）：`fromEN/Custom_Commands/ADD_CUSTOM_COM515 鱼塘.ERB:15`（`FOR LOCAL:1, 1, 数量+1` ⇒ 0 空循环，一览无塘）、`:47`（`IF RESULT <= 数量` ⇒ 0 时选塘输入全部无效落回主菜单，守卫式）、`:341`（`IF 数量 >= 5` ⇒ 0 可建造）；`DLC/生活系统大修/生活系统_鱼塘.ERB:24`（建成时 `数量 = LIMIT(数量+1, 0, 5)` ⇒ 0→1，首塘必经建造）、`:28`（每日结算 FOR 空循环，且 `:18` `SIF !生活系统_鱼塘 RETURN` 总开关在前）；`DLC/生活系统大修/生活系统_魔法联动.ERB:69`（同款 LIMIT 递增）。全库无 `== 0 → 置 1` 自愈点，NEWGAME 链亦无初始化 ⇒ PC 实机新档同样从 0 起步，旧档迁移后的 0 与 PC 新档**同态**；所有表达式均为加法/比较守卫，无下标/乘法依赖。附加登记：ERH:63 注释「默认1」是说明书级语义，实机缺省实为 0（无任何 0→1 初始化点），复刻以行为为准。
2. **`生活系统_开荒数` —— 0 等价（加法恒等）**。声明 `0_生活系统_变量.ERH:104`。消费点：`DLC/生活系统大修/生活系统_COM418.ERB:400`（`畝尺寸 = 生活系统_基础畝数() + 生活系统_开荒数`，`:401-403` 夹 `畝上限`）⇒ 0 = 只有基础畝，即未开荒真值；`DLC/生活系统大修/生活系统_种地.ERB:320`（`基础畝数() + 开荒数 >= 畝上限()` 加法比较）；唯一写侧 `fromEN/Custom_Commands/ADD_CUSTOM_COM511 开荒.ERB:55`（`++`）。开荒是 4.981 新增玩法，旧档 0 = 未开荒正中语义；无除法/下标依赖。
3. **`生活系统_上次基础畝数` —— 0 等价（显式 >0 守卫，自愈式）**。声明 `0_生活系统_变量.ERH:105`。唯一消费点 `生活系统_种地.ERB:320` 条件首项即 `生活系统_上次基础畝数 > 0 &&` ⇒ 0 = 未记录、整条提示分支短路；`:323` 每日无条件回写 `= 生活系统_基础畝数()` ⇒ 首个种地日事件即自愈。0 只会少弹一条「开荒完毕」提示，无状态破坏。
4. **`生活系统_蜂王保底` —— 0 等价（保底计数自然起点）**。声明 `0_生活系统_变量.ERH:51`（注释「采集蜂蜜未出蜂王的连续次数（保底计数）」）。消费点集 `コマンド関連/COMF/日常系/COMF445 採集する.ERB:165`（采到蜂蜜且 `生活系统_养蜂` 开时 `++`）、`:167`（`IF RAND:10 < 3 || 生活系统_蜂王保底 >= 4` ⇒ 0 不会误触保底）、`:170`（出蜂王即 `= 0` 清零）。0 = 「连续 0 次未出」即设计初值。
5. **`ADD_BANQUETS_STATUS` —— 0 等价（0 = 空槽，状态常量从 1 起）**。声明 `fromEN/Addition/Banquets/Add_Banquet.ERH:7`（`ADD_BANQUETS_MAX = 10`，`ERH:1`）。状态机：`ERH:16-20` PLANNED=1 / PREPARING=2 / IN_PROGRESS=3 / FINISHED=4 / CANCELLED=5 —— 0 不在状态集内，是「该槽无宴会」的保留值：分槽创建只挑 `ADD_BANQUETS_UPCOMING:LOCAL == 0` 的槽并写 `STATUS:INDEX = 1`（`Add_Banquet.ERB:87-92`、`:103`）；全部遍历先验 `SIF !ADD_BANQUETS_UPCOMING:LOCAL CONTINUE`（`Add_Banquet.ERB:19-21`、`:56-58`、`:113-116`）⇒ STATUS 从不在空槽上被读，0 槽不可达；`@ADD_BANQUET_RESET_ALL` 显式 `VARSET ADD_BANQUETS_STATUS`（`Add_Banquet.ERB:831`、`:837`）⇒ 全 0 是补丁自己的「已重置」形态。旧档迁移后全 0 = 无任何宴会计划，与 RESET_ALL 后状态一致，自洽。该判定可作 `ADD_BANQUET*` 族（UPCOMING 作槽占用标记、其余槽值 0 = 空记录）初值约定的锚点，但族内其余 20 个成员的逐变量判定不借本条扩权，仍留 TODO(verify)。

### 25.5 残余 TODO(verify)

- ~~H 路 44 符号原始清单对表~~ 已收口：lane-symprobe 2026-11-01 补送原始清单，与本账逐条对表零分歧（唯一微差 #6 REFF token 行 ±1，见 §25.2 表内标注）；§25.1 口径已按对表结果改写（44 = 28+8+8）。
- §24 挂账 204 个中本节已判 5 个（25.4），**其余 199 个 TODO(verify) 原样保留**：`生活系统_*` 48 个、`ADD_BANQUET*` 20 个、`ADD_TOUCH*`/`ADD_TEMPTOUCH*` 16 个、`DISH*` 15 个、`COM640_LRM_*`+`COM641_AC_*` 26 个、`Setting_*` 8 个、模板/未接线 5 个、散件约 45 个、chara 族 `SEMEN_MATRIX` 1 个及 §24.3 表内未展开散目，卡点同 §24.3。
- 附加发现 1 的注册面宽出部分（GET_FLOAT/SCALAR_FLOAT/XML 导入导出等）是否补进 builtin-functions.yaml，需组长裁决（本路禁碰 yaml）。

## 26. 2026-11-02 4.981 升版 · T10 路：存档升版语义复核（GMF 三者自洽 + §24 复核 + 真档缺口）

本节只追加，不改 §24/§25 及更早各节。承接 `CONTRACT-U981-SAVE`（§24 的 C 路）的**复核**与 **GMF 面补证**；
唯一 test 归属 `core-vm/src/test/kotlin/com/eratw/port/core/save/SaveUpgradeSemanticsTest.kt`（本路独占）。
语料（只读）：4.980 = `<work-root>/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980/ERB`，
4.981 = `<work-root>/eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3/ERB`。

### 26.1 复核范围与口径

- T10 acceptance 三面：① 4.981 `#DIM SAVEDATA` 默认 0 / `VERSION_UP` 迁移链 / 未知键丢弃（合成档用例 + 本节）；
  ② `GMF` 位 + `GMF_HOME_MAP` + `MAIN_MAP` 三者自洽；③ 真档 4.981 样本缺口 `TODO(verify)` + 获取途径。
- C# 证据根：`reference/emuera-cs/`（1.824；`Properties/AssemblyInfo.cs:11/17` 版本行）；每条结论带 `文件:行号`。

### 26.2 §24 关键结论复核（逐条对回证据，全部成立）

- **未知键丢弃**：`VariableData.LoadVariableBinary`（`VariableData.cs:4081`）对查不到 token 的键
  （`:4084` `variableToken = null`；`:4085-4088` 查表；`:4089-4092` 命中
  `IsCharacterData|IsConst|IsPrivate|IsLocal|IsCalc` 一律置 null）**读完值即丢**——每个类型分支
  先判 `variableToken == null || !IsInteger/IsString || Dimension != n`，不匹配走
  `reader.ReadInt()` / `ReadIntArray(null, needInit:true)` 一族（Int 分支 `:4127` 起）；
  非法类型码落 `:4207-4208` `throw new FileEE(AbnormalData)`。移植侧对应
  `SaveData.applyEntry`（`core-vm/.../data/save/SaveData.kt:268-289`）返回 false 跳过；`SaveFormat`
  的 `keepKey` 谓词只决定**是否建数组**，键名本身照读（`core-vm/.../data/save/SaveFormat.kt:582-592`）。
  引用锚点 `specs/save-format.md §5.3`（本节配套勘误已补写）。
- **版本闸**：`GameBase.CheckVersion`（`GameBase.cs:69-80`）三条：`:71-74` `!ScriptVersionDefined &&
  target != 1000` → 通过；`:75-78` `ScriptCompatibleMinVersion <= target` → 通过；`:79`
  `ScriptVersion == target`。唯一码 `:60-67`（target 0 豁免）；`tryatoi` `:82-104`。
  移植投影 `GameBaseGate`（`core-vm/.../data/save/GameBaseGate.kt:39-57`）一致（§24.5/§24.6 已锁）。
- **SAVEDATA 默认 0**：`#DIM SAVEDATA X` 无初值 ⇒ `DimParser` 补 `dims=[1]`、默认 0
  （`core-vm/.../vars/UserVariables.kt:738`「dims 为空补 1」）；缺失键装载后停在默认 0/空串，
  与「未初始化」同值（§24.6 第 6 条、本路用例 `缩微升版档装载后新增变量读回 0 与空串` 已锁）。
- **两版 GameBase.csv 实值**（§24.5）复核未变：`コード,7153` / `バージョン,proto`（`tryatoi` 失败落 0、
  `ScriptVersionDefined=false`）/ `バージョン違い認める,0012`。
- **`VERSION_UP` 迁移链**（§24.4）复核未变：触发行 `ERB/VERSION_UP.ERB:25`；生活系统子系统迁移块
  `:221-243`（`生活系统_存档版本` 自愈 + 育苗用品保底转换 + 清误种设施）。迁移块本体属**语料逻辑**，
  引擎侧前提是 `生活系统_存档版本` 等 SAVEDATA 键按名往返（§24.6 第 6 条已锁）——引擎零改动。

### 26.3 `GMF` 位 + `GMF_HOME_MAP` + `MAIN_MAP` 三者自洽取证

**声明面（三个键都是 `#DIM SAVEDATA`，两版一致）**：

| 键 | 声明文本 | 4.980 | 4.981 |
|---|---|---|---|
| `GENSOKYO_MAP_FIX`（GMF 位容器，bit0=幻想乡大地图） | `#DIM SAVEDATA GENSOKYO_MAP_FIX` | `DLC/DLC.ERH:139` | `DLC/DLC.ERH:139` |
| `GMF_HOME_MAP`（GMF 模式下的家地图） | `#DIM SAVEDATA GMF_HOME_MAP ;对应MAIN_MAP` | `DLC/GENSOKYO_MAP_FIX/GENSOKYO_MAP_FIX.ERH:2` | 同 |
| `MAIN_MAP`（非 GMF 模式下的家地图） | `#DIM SAVEDATA MAIN_MAP` | `DIM.ERH:245` | `DIM.ERH:248` |

**语义链（4.981 新增 `@GET_HOME_MAP()` 是前提）**：

- GMF 位 = `GENSOKYO_MAP_FIX` 的 bit0：`@GMF_GETBIT("幻想乡大地图")` →
  `RETURNF GETBIT(GENSOKYO_MAP_FIX, 0)`（`DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:412-425`，命中分支 `:416-418`）。
- `@GET_HOME_MAP()`（**4.981 新增**；`MOVEMENTS/物件関連/COMMON2.ERB:90-98`）：`:94-95`
  `IF GMF_GETBIT("幻想乡大地图")` → `RETURNF GMF_HOME_MAP`；`:97` 否则 `RETURNF MAIN_MAP`。
- 判式切换：`@CHK_DATENOW`（`COMMON2.ERB:7-15`）`:11` `IF ARG == GET_HOME_MAP()`
  —— 4.980 同文件同函数为 `:15` `IF ARG == MAIN_MAP`（4.980 `COMMON2.ERB:15` 实拍）。
- `GET_HOME_MAP` 出现量：4.981 **138 处 / 48 文件**、4.980 **0 处**（`grep -roh` 实数；
  §24/BOARD 记的「136 处」为旧口径，以本次实数 138 为准）。
- 语料自持同步：`幻想乡地图重修.ERB:401` `MAIN_MAP = GMF_HOME_MAP`（GMF 载入后把两值对齐）。

**引擎侧前提（本路判据）**：三键都按变量名进二进制存档，`applyEntry` 只写同名同型/同维声明
（`SaveData.kt:268-289`）；故三键必须**各自独立往返**，否则 `GET_HOME_MAP()` 在对应模式下读到
0/旧值 ⇒ `CHK_DATENOW` 过判（独处被派约会）。本路用例
`GMF 位与 GMF_HOME_MAP MAIN_MAP 三者随升版档往返并自洽` 锁此前提：档内 `GENSOKYO_MAP_FIX=[1]`、
`GMF_HOME_MAP=[7]`、`MAIN_MAP=[3]` 经生产装载路径（`FileSystemOps.loadData`）灌回后三值独立保留，
且复刻 `GET_HOME_MAP` 公式按 bit0 取值（开→7、关→3）。

**边界**：此「自洽」是**语料公式**（4.981 新增 `#FUNCTION`）对**引擎持久化面**的依赖，**不是引擎内建语义**；
引擎侧零改动（ADR-005：`GET_HOME_MAP` 由语料实现，`erbcc` 按普通 `#FUNCTION` 登记，
见 BOARD 2026-11-02「GET_HOME_MAP 交 u981」节）。引擎不校验 `GMF_HOME_MAP == MAIN_MAP`
（无此口径）；二者「值相同但语义不同」仅由 `幻想乡地图重修.ERB:401` 维护。

### 26.4 真档 4.981 样本缺口（TODO(verify)，沿用 §24.7）

- **TODO(verify)**：本机无 4.981 产的真档（`saveXX.sav`/`global.sav`/`chara_*.dat`），
  `ERATW_SAVE_SAMPLES` 指向的 4.980 备份目录同样不在本机。
- **获取途径**：① 在装有 4.981（EAGLOV3 整合包）的 PC 端启动一次并存档，取 `sav/save00.sav`、
  `global.sav` 与 `chara` 目录（最能代表完整写面）；② 沿用 2026-10-29 玩家上报通道收集真实玩家档（见本账 §22）；
  ③ 到手后放 `<internal-workdir>/<sample-backup>/sav-<date>-981/`，并**由组长**接进 `RealSaveSamplesProbeTest`
  （该文件本路禁碰，保持原样未动）。
- **不得假装判完**：合成档判据只锁「未知键丢弃 / 缺失键填 0 / 版本闸放行 / GMF 三键往返」四条装载语义；
  写侧实况（540→? 条全局条目序、浮点段实际出现形态、`SaveCapture` 写出的字节能否被 PC 端 ErskiaX 回读）
  **未**与真档对拍，归 `TODO(verify)`。

### 26.5 残余 TODO(verify)

- 真档 4.981 写侧对拍（见 §26.4）。
- `GMF` 位装载态：`GMF_GETBIT` 依赖 `GENSOKYO_MAP_FIX` 的 bit0 在存档往返中保持（本路用例已锁三键往返，
  但 `SETBIT`/`GETBIT` 位运算本体属 stmt/builtin 面，非本路）。
- §25.5 的 199 个「默认 0 等价未初始化」逐变量判定与本节无交叠，原样保留。

## 27. XML 已知分歧（v4.981 审查修复波 · T8 · 2026-11-02；XML-FIVE 翻案复刻 · 2026-11-06）

依据 `C-XML-DIVERGENCE@2` 方向乙（OD-1 定夺）+ ADR-005「不可达即不擅改」。对拍 = dotnet 8.0.425，探针走 Emuera 同 API `XmlDocument.LoadXml(string)`/`SelectNodes(xpath)`（`FunctionMethodCreator.cs:179/:189/:3058`），留档 `<work-root>/dotprobe-t8/`（T8）与 `<work-root>/xml-five-probe/`（XML-FIVE 三轮复探，2026-11-06）。**2026-11-06 主人指令翻案**：第 2/4②/5/6 条按同 API 复探结论复刻（波次 CONTRACT-XML-FIVE），第 1 条 BOM 与第 3 条未实现轴**不在翻案范围、维持现状**。
1. **BOM（不改，维持现状）**：`XmlParser.parseDocument` 剥串首 U+FEFF（该行保留）。.NET 抛 XmlException ⇒ .NET=CodeEE / 移植=成功；真实链路 `LOADTEXT`→`File.ReadAllText`（`FunctionMethodCreator.cs:8082`）已消费 BOM ⇒ 不可达。
2. **`@*`/xmlns（已复刻，X5）**：.NET `<r xmlns:p="urn:x" a="1">` 的 `/r/@*`=1、`/r/@xmlns`=0、`/r[@xmlns]`=0、`/r[@xmlns='urn:x']`=0；默认 xmlns 经根通配步 `/*/@*`=1。`XmlPath` 属性轴与属性谓词（`HasAttr`/`AttrCompare`）现按 namespace 节点口径过滤 `xmlns`/`xmlns:` 声明（解析器仍存普通属性供 OuterXml 序列化；`RealCorpusXmlTest` 根级锚 `/*/@*`=1 钉真语料 schema.xml）。带前缀 `@xmlns:p` 走「缺 namespace manager」报错与本条正交（旧实现把声明当属性节点计数=2，已翻案）。
3. **未实现轴/节点测试/谓词函数（不改，维持现状）**：.NET 支持 `preceding::`/`following::`/`namespace::*`/`processing-instruction()`/`[position()]`/`[contains()]`；移植抛 XmlPathException→CodeEE。语料实到形态仅 名字/`*` 步/`/text()`/`[n]`/`[@a='v']` ⇒ 不可达。
4. **字符引用**：① `&#xFFFE;`/`&#xFFFF;`/`&#xFDD0;` = .NET 放行 / 移植放行 ⇒ 一致（旧 TODO 闭环，移植正确）；② `#x0`..`#x1F`（除 9/A/D，含 0）= **已复刻（X1）**：.NET 放行（复探 `&#x0;&#x1;&#x8;&#x1F;` InnerText 码位 0,1,8,31），移植改为同口径放行（旧实现拒绝、方向相反，已翻案；跨域钉子 `fn/MapDataTableMethodsTest`/`fn/MapXmlUnifyTest` 已翻钉）。回写面对齐 .NET：OuterXml/InnerXml/属性值把 `<0x20`（除 `\t`/`\n`/`\r`）控制符重编成大写十六进制字符引用 `&#x1F;`（复探实测）。~~相邻分歧~~**裸控制符源文本 = 已复刻（RESIDUE-BX2 R1，2026-11-06）**：源文本**裸**控制符（非字符引用形态）.NET 抛 XmlException、移植旧解析层不拦——本波同口径拦截，布点 = 文本/属性值/注释/CDATA/PI 数据 + 散位（根前/根后），名字位走「Name cannot begin with」变体文案；字面 `U+FFFE`/`U+FFFF` 一并拦（探针 R1k/R1n），0x7F/0x85 放行（R1p/R1q），孤立代理字面**不复刻**（.NET 报文是代理对成对消费后的下一字符，R1o，框架内部行为，语料不可达）。探针留档 `<work-root>/residue-probe/probe-output.txt`（R1a-R1x；zh-CN culture 下报文不变，net8 System.Xml 无本地化资源）。③ 代理区/大于 0x10FFFF = .NET 抛 ArgumentException（Emuera 只 catch XmlException ⇒ 原版未捕获崩溃）/ 移植按 ADR-005 转 XmlParseException→CodeEE（维持现状）。
5. **谓词通配 `[*]`/`[@*]`（已复刻，X2）**：.NET 语义「有子元素」/「有属性」（count>0，与名字无关），移植已按此实装（旧实现按「名为 `*` 的名字测试」恒 0，已翻案）。复探补读钉死边界：注释/PI/纯文本（含 `#whitespace`）子的元素对 `[*]` 命中 0；只有 xmlns 声明的元素对 `[@*]` 命中 0（与 X5 同口径）。
6. **畸形限定名文案（已复刻，X3）**：.NET 文案引**整串查询原文**（含前导空白/谓词方括号/联合竖线）且分两族，移植已逐字复刻（旧实现引号里只有名字片段且两支判反，均已翻案）：多冒号（`a:b:c`、`a::b`）与空前缀（`:c`、`:`）⇒ `'<全文>' has an invalid token.`；空本地名（`a:`、`@a:`、`[a:]`）与名字含空白（`a: b`、`@a: b`）⇒ `'<全文>' has an invalid qualified name.`。同族延伸（复探实测）：未知轴名（语料 `宴会_SUB.ERB:201` 错拼 `preceeding-sibling::`）同属 invalid token 族 ⇒ `'/root/preceeding-sibling::a' has an invalid token.`，移植 `Axis.ofName` 的自拟中文文案已按探针翻案。
7. **纯空白片段节点类型（已复刻，X4，2026-11-06 新登记）**：`InnerXml` 赋值的**纯空白**片段在 .NET 是 `#whitespace` 节点（Name 可经 `XML_GET` style=4 观测，`text()`/`node()` 选中它、`[*]` 不计），移植已建 `XmlWhitespace` 复刻。**原残差登记（`XmlPath.kt` KDoc 残差段）把产生路径写成 `InnerText` 赋值——复探证实方向写反**：`InnerText` 赋值（值含纯空白/空串）一律建普通 `#text`（移植既有实现本就正确，无需改）。相邻面按 ADR-005 折化：给 `#whitespace` 节点赋非空白值 .NET 抛 ArgumentException（原版未捕获）⇒ 移植转 CodeEE；空白值照常可写。**空串读数 = 已探并销账（RESIDUE-BX2 R2，2026-11-06，不改码）**：dotnet 8.0.425 探针（`<work-root>/residue-probe/probe-output.txt` R2b/R2c/R2e/R2f）——对 `#whitespace` 节点 Value/InnerText 赋**空串**都放行，节点保持 XmlWhitespace（Name 不变、`text()` 照选、元素 OuterXml 写空文本段 `<r></r>`）；`InnerXml=""`（打元素）把含空白在内的全部子节点整段清空。与移植现状（`isWhitespaceOnly("")` 为真、空串原样写入）逐项一致 ⇒ 只销账不改码，钉子 `XmlMethodsTest`「whitespace 节点赋空串放行且节点类型不变」。

## 28. 四个面板折叠开关跨 BEGIN TRAIN 保留（移植侧有意偏差，2026-11-03）

- **现象**：玩家反馈「每次开始新的一天都会自动把palam打开」（上报端点 1793715846086 的屏上快照同为 `▼[－][Palam]` 展开态）。
- **原版语义**：eraTW 4.981 把面板折叠开关放在 `TFLAG:170 PALAM非表示 / :171 Status非表示 / :173 USERCOM非表示 / :174 LOOK非表示`（`ERB/Headers/AutoConst_TFLAG.ERH:147-150`），由按钮 RESULT 3000–3003 翻转（`USERCOM.ERB:641-648`）；`INFO.ERB:695`/`:170`、`Look.ERB:11`、`USERCOM_コマンド表示処理.ERB:38` 按该值选 `▼[－]`（展开）/`▽[＋]`（折叠）。引擎每次 `BEGIN TRAIN` 整表清 TFLAG（`reference/emuera-cs/.../VariableEvaluator.cs:1673-1676`）。每日起床 `SHOP.ERB:304-308`（USERSHOP RESULT==100 → `BEGIN TRAIN`）后开关归 0 ⇒ 次日面板重新展开；这是原版默认行为（只有 `Setting_FLAG:即时重载` 开启时，`COMF402 就寝.ERB:12-37` 的 `SaveDataInTrain/STflag` 备份才让次日 EVENTTRAIN 经 `BEFORETRAIN.ERB:61` 还原 TFLAG）。
- **决策**：按组长 2026-11-03 指示做**有意 QoL 偏差**——`ProcessRuntime.beginTrainReset` 清 TFLAG 前快照上述 4 个下标、清 0 后回写；其余 TFLAG 元素仍照 C# 清 0。读档路径不受影响：`SYSTEM.ERB:44-70` 在复位前已 `ARRAYCOPY "TFLAG","STflag"`，`BEFORETRAIN.ERB:61` 回填的仍是同源快照。
- **影响面**：语料中对这 4 个下标的消费点全部是显示分支（上述 4 文件 + `USERCOM.ERB:642-648` 的翻转），无战斗/数值逻辑依赖。
- **验证**：`core-vm` 定向用例 `BeginTrainResetTest`「BEGIN TRAIN 保留 Palam Status USERCOM LOOK 四个折叠开关」+ 原有 5 例，6 用例 / 0 失败；用例同时断言相邻与远端 TFLAG 仍清 0。

## 29. `ITEMNAME:1000 越界` 定性为「4.981 erbc + 4.980 数据」混装（出包侧缺陷，2026-11-03）

- **现象**：上报端点 1793712013906 / 1793715910544（app 0.2.39，产物指纹 `b9914ebc6de0`，Xiaomi 23127PN0CC / Android 14）启动链 `SYSTEM_TITLE → QOL_DB_INIT → QOL_ITEM_INIT → QOL_ITEM_DATA_REGISTER → QOL_ITEMDATA_ORIGINAL` 报 `[错误] 下标超出范围：ITEMNAME:1000（长度 1000）`。
- **语义核对（引擎无责）**：4.981 的 `CSV/VariableSize.csv:74` 是 `ITEMNAME,1200`，4.980 同位置是 `ITEMNAME,1000`；运行期名字表长度取 `maxDataList[6]`（`VariableSizes.kt`，缺省 1000），`ITEMPRICE` 与它同槽（`ConstantData.cs:451-455`）。4.981 的 `ERB/魔改内容/qol/qol_item.ERB:113` 是 `FOR ITEM_ID, 1, 1200`，循环体内 `:402` 读 `ITEMNAME:ARG` ⇒ 数据只有 1000 槽时必然越界。原版同样是 `IndexOutOfRangeException`（系统过程内被 catch，屏上一条脚本报错），移植侧照抄（ADR-005），无需改引擎。
- **根因（出包侧）**：`devtools/pack_release.py` 的 `toolchain_env()` 缺省把 `ERATW_GAMEDIR` 写死成 4.980 游戏根，而 erbc 由 `ERATW_CORPUS`（4.981 `ERB`）编译 —— 只要打包机只导出了语料变量、没导出 `ERATW_GAMEDIR`，就会静默出成「4.981 脚本 + 4.980 CSV/resources」的包；上报/签名/包型三项体检都不看数据版本，全绿。
- **修法**：① `ERATW_GAMEDIR` 缺省改为「`ERATW_CORPUS` 的父目录 > 4.981 游戏根 > 4.980 游戏根」，两条路径恒同源；② 新增出包前判据 `data_source_alignment`：语料父目录与游戏根（含 `--game-root` 覆盖）不同源即 exit 2，`--skip-assets` 放行（该包不随数据）。
- **已装机的修复面**：`GameAssetInstaller` 首启档按「目标存在且字节数与资产相同才跳过」，4.980/4.981 的 `VariableSize.csv` / `Item.csv` 字节数不同 ⇒ 下一份完整包在旧装机上会就地覆盖；`--skip-assets` 小包不会。
- **验证**：`python devtools/pack_release.py --dry-run --offline` 通过；`data_source_alignment` 的七个来源组合在 Haruki 机实测（都没设 / 只给语料 / 语料 4.981 + 数据 4.980 / skip-assets / `--game-root` 覆盖 / 显式同源 / 只给游戏根），混装档按预期报红。

## 30. `SQL_IMPORT_MAP_XML` 的「先 WorkingDir 再 ExeDir」双根查找未复刻（移植侧单根；组长裁定 2026-11-05）

- **现象**：真机上报 `SQL_IMPORT_MAP_XML：找不到文件 plugins/tw_csv_chs.xml` 一族（端点 2026-11-04 批次），屏上只给相对路径，看不出引擎试的是哪个根。
- **官方口径**：`specs/builtin-functions.yaml` 的 `SQL_IMPORT_MAP_XML` 条目写「文件路径先按 WorkingDir 再按 ExeDir 找，都找不到抛 CodeEE」，出处标 `fork-src Creator.Method.cs:10343-10350`。**fork-src 不在本机**（`reference/emuera-cs` 全库对该函数名 0 命中——它是 eraTW fork 专有，上游 Emuera 没有），故这条顺序属**规约转述、不可逐行核** ⇒ `TODO(verify)`。
- **移植侧现状**：相对路径只按**宿主单根**解析、出根即 null（`core-vm/src/main/kotlin/com/eratw/port/core/proc/BootHosts.kt` 的 `resolvePath`），没有第二把根。
- **裁定 = 暂不复刻第二根**，三条理由：① Android 上 WorkingDir 不是玩家可见概念（进程 CWD 与游戏根是同一个私有目录），「第二把根映射到哪个物理位置」是产品决策不是语义决策；② 语料里该函数的真实用法（`魔改内容/qol/qol_db.ERB:61-62`）传的是相对游戏根的路径，单根已覆盖现有全部用法；③ ADR-005 只要求复刻官方**行为**，不要求臆造官方**判停顺序**。
- **补偿面**：lane sqlroot 落 `core/diag/FileReadTrail`（读轴留痕，报告里读得出「试的哪个根 / 解析落点 / 是否出根被拒 / 常规文件还是目录」），宿主接线在 `app/.../ErrorReportCollector.kt` 的 `collectFull`。将来若真机出现「文件确实存在却报找不到」的样本，凭这一行即可判是缺第二根还是别的，不用再手工串一遍装配链。
- **验证**：`core/sql/SqlFileRootDiagnosticsTest` 6 例（两根同句「游戏根成功 / 沙盒根失败」且读数点出根、出根被拒写明越界闸拦截、桩宿主降级不抛、连续失败只留最近 16 条、掩码口径只留末两节、报告正文整节形状）；组长侧合流头定向 286 tests / 0 failures / 0 errors / 0 skipped（fresh XML）。
## 31. TRYC 空名链在 4.981 真语料可达（ENGFIX-5 取证，2026-11-05）

- **现象**：0.2.31 收编的「TRYC 动态名空名守卫」来自真机致命报错「ARG_VARNAME未定义为变量名 ARG_VARNAME=」，触发面是「高级列表功能」开着的存档打开能力表示。ENGFIX-2 拆守卫后需确认这条链在 4.981 语料上是否仍然可达。
- **判据链（全部落在 4.981 真语料）**：
  1. `CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:13-14` 把 `COLUMN_NAME`/`COLUMN_VAR` 声明为 `MAX_COLUMN_GROUP=10`、`MAX_COLUMN=12` 的固定 10×12；
  2. `TEMP_CHARA_LIST_BY_DT_FUNC.ERB` 的 `LIST_BY_DT_COLUMN_INIT` 只给 group 0–5 灌预设（group 6–9 的 `CASEELSE` 为空，内层 `FOR` 在空 `COLUMN_GROUP_PRESET` 处 `BREAK`，`:696-774`）；
  3. 收集循环 `TEMP_CHARA_LIST_BY_DT.ERB:296-333` 按 `VARSIZE("COLUMN_NAME", 0/1)` 全槽遍历，**没有**初始化循环 `:269` 的 `STRLENS(COLUMN_VAR)==0 → CONTINUE` 守卫；
  4. 空槽 `LIST_DT_CELL_DISABLE(",")` 落 `CASEELSE RETURNF 0`（`個別処理.ERB:207-208`）⇒ 不禁用 ⇒ `TRYCCALLFORM %L_ELEMENT:0%`（空名）；
  5. 官方对空名无早退分支：`CalledFunction.cs:93-105` 查表失败即 `isTry` 跳 `JumpToEndCatch`，装载期 `ErbLoader.cs:1339-1348` 把它设为 CATCH 行本身，主循环先 `ShiftNextLine` 再执行 ⇒ 落点是 CATCH 的下一行（体内首行）；体内 `GET_INT_BY_NAME_ELEM("")` 落 `CASEELSE THROW ARG_VARNAME未定义为变量名`（`TEMP_CHARA_LIST_BY_DT_FUNC.ERB:327-328`）。
- **可达性**：**可达，但有门** —— 只有 SAVEDATA `高级列表功能` 为 1 时 `能力表示.ERB:926` 才 `CALL SHOW_CHARA_LIST_DT`；该键是 `#DIM SAVEDATA`、缺省 0，所以默认主线永远不会进这条链（这就是「实况不炸」的来源，不是语义不可达）。
- **裁定（已翻案，见本节末尾 2026-11-06 追记）**：按 ADR-005 原样复刻——空槽照样进 CATCH 体、照样 THROW，引擎不恢复 521c42c3 的自造空名守卫；根因是上游语料初始化/收集两遍的守卫不对称，修复权在语料侧。
- **验证（ENGFIX-5 当期）**：`core-vm/src/test/kotlin/com/eratw/port/boot/CharaListDtCorpusProbeTest.kt` 2 例 / 0 失败（`ERATW_CORPUS` 指到 4.981 `.../ERB`）：①`LIST_BY_DT_COLUMN_INIT` 跑真语料动态 `DEFINE_COLUMN_*` 后 group 0 首列非空、group 6–9 共 48 槽整组为空、`COLUMN_VAR:9:11` 可访问；② `LIST_DT_CELL_DISABLE(0,",")==0`、`GET_INT_BY_NAME_ELEM(0,"BASE",0)==BASE:0:0`、`GET_INT_BY_NAME_ELEM(0,"",0)` 抛 `CodeEE` 且文案含「ARG_VARNAME未定义为变量名」。同轮 `CharaListDtEmptyGuardTest` 仍绿（守卫不复活）。
- **翻案（2026-11-06，玩家反馈复核；上面「裁定」作废）**：第 5 条把「进 CATCH 体」直接等同于「`GET_INT_BY_NAME_ELEM("")` 被求值」，漏了 CATCH 体里包着它的 `DT_CELL_SET`。
  - 官方 `DataTableCellSetMethod`（`FunctionMethodCreator.cs:2568-2619`）是 `FunctionMethod`，`GetIntValue(exm, List<AExpression> arguments)` 收的是**未求值的表达式列表**：先求 `[0]` 表名 / `[4]` byId / `[1]` 行 / `[2]` 列名，仅当 `dataRow != null && Columns.Contains(列名)`（:2594）才碰 `[3]`（:2603/:2609/:2613）。空槽的 `COLUMN_NAME` 是空串，建表循环（`TEMP_CHARA_LIST_BY_DT.ERB:266-283` 的 `STRLENS==0 → BREAK`）压根没建这一列 ⇒ `Contains("")` 为假 ⇒ `return -3`（:2618），`GET_INT_BY_NAME_ELEM(chr,"",0)` **一次都不会被调用**。
  - ⇒ 原版不弹错；「两遍守卫不对称」只是语料写法，不构成致命路径。**真缺陷在本移植引擎**：语句位内建方法经 `ErbProgramCompiler.lowerPoolInstruction → lowerArgument` 把参数层的用户方法 `FuncCall` 降级成前导 `_CALL` + 返回哨兵，第 4 实参在进 `dtCellSet` 之前就被求值 ⇒ THROW。`MapDataTableMethods.dtCellSet` 本身是惰性的（行/列未命中先 `return -3`），是编译期降级抹掉了短路。ENGFIX-5 只直接调了 `GET_INT_BY_NAME_ELEM("")`、没走 `DT_CELL_SET` 语句位，故取证结论方向错了。
  - **修复**：`BuiltinRegistry.needsExprArgs` + `lowerPoolInstruction` 对声明 `evalRef`（= 需要裸 `Expr` 实参）的语句位内建保持实参形态，只做 `padRuntimeCalls` 缺省补位；运行期经 `EvalContext.callFunctionRef` 的引用路径交回方法体按需求值。与表达式位既有的 `BUILTIN` 保节点路径对齐 —— 不改语料、不加空名守卫、不动 TRYC/CATCH 语义；`CharaListDtEmptyGuardTest`（守卫不得复活）仍全绿。
  - **验证**：新增 `core-vm/src/test/kotlin/com/eratw/port/core/compile/CharaListDtLazyCellSetTest.kt` **6 例 / 0 失败**：旧代码 2 红（空列名短路 / 玩家链 `TRYCCALLFORM` 空名→CATCH 不炸），修复后 6/0；四例反钉「列命中时实参照常求值（THROW 路径）/ 无参用户方法正常写入 / **带参**用户方法正常写入 / `#FUNCTIONS` 字符串用户方法写入字符串列」。带参用例专钉运行期重入的参数绑定（`ARGS:1` 没绑上会保持默认空串、同样落 CASEELSE THROW ⇒ 只靠 THROW 路径会假绿）；STR 用例钉 `exprCallUserMethodRef → invokeUserMethod` 按 `FuncMeta.returnsStr` 取 `_RETS` 通道与 `dtCellSet` 的 `operandType == STR` 检查。同轮 `CharaListDtEmptyGuardTest` 4/0（守卫不得复活仍有效）、`CharaListDtCorpusProbeTest` 2/0。
  - **产物级验证（2026-11-06 已实测，非口头约束）**：`:tools:erbcc:compileCorpus` 严格档重编标准出包根 4.980（3971 文件 / 110066 切片 / 失败 0 / 解析失败切片 0 / 指令 4,884,011 / 语句 1,712,420），产物落 `<work-root>/erbc-20261106-lazy-cellset/erbc.v1`（独立目录，未覆盖 10-29 旧产物）。`compiler-digest` 由旧 `a16113ba…` 变为 `e4f0f474…`；**合入 `origin/main` 对面 3 笔后已二次重编**：`compiler-digest` 覆盖 `core-vm/src/main/kotlin` 整棵树（`ErbcCompilerDigest.SOURCE_ROOT_REL`），对面 `16b13bf7`/`9c360d5e`/`d6c08f0b` 改到 `host/CellWidth.kt`、`proc/*.kt` ⇒ 指纹再变 `77d0ece2…`，上午产物一度被判 `COMPILER_STALE`；按合并后源码树重编到同一目录后 `erbcc check` 全项对齐（BUILD SUCCESSFUL，COMPILER_STALE 消除）。语句池仍 1,712,420 条 / `program.erbc` 329,708,517 字节（sha256 `1f16083b…`），与首次重编一致 —— 对面改动不落在编译期降级逻辑；新产物 `:core-vm:flatStmtAudit` = `PASS 1712420 statements, 0 diff`。新旧 `program.erbc` 329,311,959 → 329,708,517 字节、`program.stmt.idx` 6,866,568 → 6,849,704（方向与「前导 `_CALL` 语句被移除」一致，但 `compiler-sources 185→219` 说明新旧差还混着上游提交，不单独归因）。`SHOW_CHARA_LIST_DT` 在新旧 `program.meta` 均为 `function 533 534`，4.980 标准出包根同样含 `TEMP_CHARA_LIST_BY_DT.ERB:325` 那句 `DT_CELL_SET … GET_INT_BY_NAME_ELEM(…)`，故本修复对正式包同样生效。
  - **零缺陷复核（2026-11-06 第二轮）**：
    - *静态影响面*：修复把 `lowerExpr(term)` 换成 `padRuntimeCalls(term)`。逐行核对后者**已递归** `VarRef.indices`、`FormStringTerm`（含 conversion 的 width / YenAt 分支）、`Binary` / `Ternary` / `Unary` / `KeywordIndexArg`，是 `lowerExpr` 的完整「不降级版」，未丢非 FuncCall 变换；`ExprCallTable.classify("DT_CELL_SET") = BUILTIN` ⇒ 外层不会误补用户方法缺省实参；`BuiltinRegistry.map` 是大小写不敏感 `TreeMap` ⇒ 原样传 `s.methodName` 安全。**唯一理论边界**：`lowerFuncCall` 的 `NON_METHOD_LABEL` 编译期校验（UsedNonMethodFunc）在保持 Expr 的路径上不触发 —— 但这与既有短路位（`&&` 右侧 / 三目分支同样走 `padRuntimeCalls`）同口径，且 strict 档全语料编译 0 失败（2026-11-06 实测）**仅为观测值，不构成真语料不可达的证明**——覆盖面向上推断「不可达」不作为判据（ENGFIX3 B4 遗留②订正，2026-11-08）；不为此扩修，避免无收益地放大修复面。
    - *漏声明保护的承保面订正（ENGFIX3 B4 遗留②，2026-11-08）*：本修复「语句位内建保持裸 Expr」的保护不依赖语料观测，承保的是声明期不变量——`BuiltinMethod` 构造期 `require(variadicGroup == null || evalRef != null)`（`core-vm/src/main/kotlin/com/eratw/port/core/fn/Builtins.kt:831`，随 B4 `0c549223a` 落地）：声明成对变参组却不声明 `evalRef` 的内建在注册期即构造失败，把「装载期成对声明」与「语句位惰性求值声明」钉在同一处，漏声明即注册红，无须等语料踩中。
    - *真产物语句池 A/B*（一次性探针读新旧 `.erbc` 段 3，跑完即删）：全池 13 条 `DT_CELL_SET`，玩家报障那句 `TEMP_CHARA_LIST_BY_DT.ERB:324` 在**旧产物** `stmt#13775` 是 `arg3=FuncCall(name=#EXPR_RET#11, argc=0)`（前导 `_CALL` 返回哨兵 ⇒ 无条件求值 ⇒ THROW），**新产物** `stmt#13556` 是 `arg3=FuncCall(name=GET_INT_BY_NAME_ELEM, argc=3, type=INT)`（原节点 ⇒ 空列名短路不求值）。其余 12 条（`VarRef` 与 `DT_CELL_GET` / `SUMARRAY` 内建）新旧**逐条一致** ⇒ 改动只作用于用户 `#FUNCTION`，未波及别的语句形态。
    - *动态闸门*：新产物 `:core-vm:flatStmtAudit` = `PASS 1712420 statements, 0 diff`；`:core-vm:dualPathAudit` 全量（`ERATW_DUAL_LIMIT=1712420`）= `PASS scanned=1709125 + skipped=3295 = 1712420, fallback=0, stopBy=END_OF_POOL`（树路径 vs 扁平路径执行语义 0 差异）；`:core-vm:gateRealArtifacts`（真产物/真存档钉）= BUILD SUCCESSFUL；`erbcc check` 新鲜度全项对齐。
    - *全量回归*：导出 `env.sh`（`ERATW_CORPUS` = 干净 4.980 根）⇒ 动态语料用例全生成，`:core-vm:test` **6316 例 / 5 红 / 84 skipped**、`:tools:erbcc:test` **全绿**。5 红同源 = `PRINT_RECT: ""を整数値に変換できません` / `GOLDEN_03_GFX.ERB:23 PRINT_RECT 5px` 的 GFX 夹具（`PrintRectColorTest` 1 + `M11eExpandE2ETest` 1 + `L3HashChainTest` 3，`aux:gfx` 簇哈希由此带出）。**A/B 定性**：`git stash push -u` 撤回两处源码后只跑这三个类仍是 `18 tests / 5 failed`，红例集合不变 ⇒ 与本次修复无关；stash 已 pop、列表为空。（上一轮 3227/1 与 erbcc 221/1 的读数来自另一语料根/未导出动态用例，不具可比性。）
  - **交付硬约束**：修复落在编译期降级、结果写进 `.erbc` 语句池 ⇒ **必须重编 erbc 产物再出包**；只换 APK 吃旧 `.erbc` 不生效（同 `ErbcFreshness` 的 `compiler-digest` 口径，`stageGameAssets` 会抛 `GradleException`）。出包时须**显式**把产物目录接上：`-PeratwErbcDir=<work-root>/erbc-20261106-lazy-cellset/erbc.v1` 或导出 `ERATW_ERBC_DIR` 指向它 —— 显式档才会令 `erbcDirIsFallback=false`、`verifyErbcFreshness(hard=true)`；直接吃 `tools/erbcc/build/erbc-out` 兜底目录会被 `-PeratwRequireComplete` 拒包（`pack_release.py:798` 亦记过这个坑）。真机端 TODO(verify)（本机 `adb devices` 空）。

## 32. `maxStepsPerRun = 10 亿` 的 `DeviceBudget` 兜底是移植层语义（ENGFIX-4 登记，2026-11-05）

- **现象**：真机极慢/超长合法链（2026-10-30 的 `EVENTTRAIN` 起床链实测 2.90 亿条指令）曾被 60 秒墙钟误杀；ENGFIX-3 起官方死循环语义（墙钟 + 弹框问玩家、从不自动中止，`Process.cs:470-489`）在 core 复刻，`maxStepsPerRun` 随之降级为**移植层极端兜底**。
- **官方口径**：Emuera 从不按指令数中止脚本 —— 死循环判据只有墙钟（`Config.InfiniteLoopAlertTime` 注册缺省 5000ms，`ConfigData.cs:75`）+ 同步 YesNo 弹框（`Process.cs:478-488`、`Dialog.cs:21-28`），玩家点「否」即继续。
- **移植侧偏差（ADR-005 例外）**：`GameSession.PRODUCTION_MAX_STEPS_PER_RUN = 1_000_000_000L` 是 Android 侧的极端兜底，命中即抛 `StepLimitExceeded`（`LoopGuardCause.DeviceBudget`），按装载失败收口并留 `deadloop-site.txt` 现场。取值依据：实测最坏合法链 2.90 亿条的 3.4 倍；代价是真死循环 ≈7.5 分钟才报（见该常量 KDoc）。
- **为什么保留**：设备被系统降频/抢占时墙钟会误杀合法重负载，指令数闸与设备速度无关，是「不误杀优先」的最后一道；官方行为面（弹框问玩家）由 ENGFIX-4 的 AlertDialog 接线承接，两者不冲突。
- **ENGFIX-4 接线面**：`VmConfig.infiniteLoopAlertTimeMs` 阈值取会话生效配置 `InfiniteLoopAlertTime`（置 0 关）；`ProcessRuntime.askInfiniteLoop` 经 `infiniteLoopPrompt` 把 VM 线程阻塞到 UI 作答；app 侧 `BlockingInfiniteLoopPrompt` + `PortraitShellCopy.dialogYes/dialogNo`（三语）。
- **验证**：`core-vm` 定向 `*InfiniteLoop*` / `*StepLimit*`、`platform` 的 `BlockingInfiniteLoopPromptTest`（6 例）、`app` 的 `PortraitShellCopyTest`（56 条形状闸）。

## 33. div 覆盖块版面语义全量复刻（「登记分歧（浮动块模型缺失，不硬造）」翻案，HTMLDIV-B1 · 2026-11-06）

- **翻案依据**：主人 2026-11-05 指令推翻旧裁定——div 定位块是复刻欠账里唯一「会塌版」的一族，
  按原版语义把模型造出来（CONTRACT-HTMLDIV-B1）。旧「移植侧近似：内容独立成行（带底色）、
  两栏并排退化为上下堆叠」的登记（`LineBufferConsoleSink.printHtml` 旧 KDoc）就此作废。
- **原版口径（逐条行号）**：块零宽内嵌同行（`HtmlManager.cs:646` + `ConsoleDivPart.cs:217-219`
  空 SetWidth、`:66` CanDivide=false）；属性全量 `HtmlManager.cs:1225-1373`；几何与
  `Math.Abs` 先于重建 `ConsoleDivPart.cs:68-135`；逃逸登记 `ConsoleButtonString.cs:165-185`
  + `EmueraConsole.cs:2916-2925`（未逃逸块无绘制面，`ConsoleButtonString.cs:361-364`）；
  覆盖绘制 depth 降序、depth 0 正文槽 `EmueraConsole.cs:1829-1873`；块内几何三段收缩与
  子行落点 `ConsoleDivPart.cs:174-206`（width+2 fudge `:178`）；SubDivisionWidth
  `HtmlManager.cs:618-635`（width **原值**不 abs），只作用于子行折行/对齐
  （`:698`、`:702-704`），对量宽面（`HtmlLength` 只累加父行 `array[0]`，`:176-181`）不可观测；
  GETDISPLAYLINE 走 `ToString()` 重现头尾标签（`ConsoleDivPart.cs:221-233`）、
  HTML_GETPRINTEDSTR 对块**无分支**（`HtmlManager.cs:446-464`，块整段跳过）——两个函数
  行为刻意不同，都照抄。
- **落地**：core 模型 `core/host/DisplayDivBlock.kt`（几何脚本像素 + altHeadTag 逐字节重建
  + sourceRange）；sink 直印/缓冲两路解析成块（`LineBufferConsoleSink`：div 不断行、块内
  br 结子行、LINECOUNT 与原版同判——div 内容不数逻辑行）；`HtmlMethods.kt` 的
  `consolePrintedLine` 按 sourceRange 做 GETDISPLAYLINE 拼接与 GETPRINTEDSTR 剔除；
  render `RenderLine.divBlocks` + `adapter/DivOverlay.kt`（覆盖绘制规划器：两形态矩形、
  三段收缩、depth 两侧、逃逸/行区间过滤、子行落点、SubDivisionWidth 透传）。
- **与原版差异（登记不修 / TODO(verify)）**：
  1. **覆盖绘制的消费端已接线（DIV-WIRE 波翻案，2026-11-06）**：过渡期「块区间原文留在
     行文本、子行内联显示」已停用——`ConsoleAdapter.adapt` 按 [DisplayDivBlock.sourceRange]
     把块区间从段原文整段剔除（零宽节点原义，ConsoleDivPart.cs:217-219），`ConsoleAdapter.fold`
     把块按锚列落到锚排（折点边界归前一排，PrintStringBuffer.cs:217 的零宽判式）、PRINTN
     并入两行块拼接；`ScreenComposer.composeInto`（headless）与 `CanvasConsoleView.drawScreen`
     （真机）经 `DivOverlayPlanner.planFrame` 消费同一份规划，按「behind（depth 降序）→
     正文 → front（depth 降序）」的全局层序夹正文绘制（EmueraConsole.cs:1829-1857 的
     depth 0 正文槽），子行落内容盒逐行落点（CDP :196-203）、对齐按 SubDivisionWidth 折格
     （ConsoleDisplayLine.cs:80-83 的 customWidth 口径，`DivChildDraw`）；块在场的帧两头都
     整屏重绘（块像素横跨行带，按行擦背景会擦出洞——「行刷新/翻页不留陈迹」的收口）。
     sink 构造链（GameSession:695 起）现喂 `scriptFontSize` + `lineHeightPx = effective.lineHeight`
     （真机逃逸判定与版面同尺；缺省 18/19 回落保持给 headless 构造点，纯 JVM 零行为）。
     残留 TODO(verify)（接线引入，语料可达性均已核）：
     a. **绝对块锚行滚出窗口不画**：原版对 absolute 块无条件取景（ConsoleEscapedParts.cs:77
        的 type==3 恒进），落点公式按行号算、与窗口成员无关（EmueraConsole.cs:1870）；本侧
        扫描窗 = 窗口行 ± 一屏 margin（`ConsoleScreen.divOverlayScan`），更高/更远的块会漏
        ——语料 40 处 div 无 `display='absolute'`（全部相对），最大纵向跨度 TITLE.ERB:79 的
        `ypos=-CLIENTHEIGHT+行高`（≈52 行）在一屏 margin 内，现无实例可达。
     b. **立绘与块的合并 z 序不复刻**：原版 cbgList 与逃逸块在同一轮按 z/depth 降序归并
        （EmueraConsole.cs:1834-1848），本侧沿用 ScreenComposer 既有近似（立绘 z>=0 → 块
        behind → 正文 → 块 front → 立绘 z<0），同键交错不复刻。
     c. **边框/圆角形状近似**：headless 栅格边框按直角矩形条带铺（原版每边是两端斜切的
        梯形 + 圆角路径，Shape.cs DrawBorder/drawNormalCornerBorderLeftTop），radius>0 不画
        圆角；真机侧背景用逐角圆角（Path.addRoundRect，语料 TITLE.ERB:79 `radius='4px'`、
        神签.ERB:80 `radius='12px'`）、边框条带仍直角。TODO(verify)：真机截图对拍。
     d. **`bcolor` 没写的边框色回落取出厂 `Config.ForeColor`**（DrawBorder :94/:111 的
        colors == null 支），真机配置改过 ForeColor 后差一档；语料带 border 的 div 全部写了
        bcolor，现无实例可达。
     e. **块锚列在 TAB/增补平面上两层格口可差**（核心层 anchorCol 的 `CellWidth.of` 对渲染层
        `Grid.cells` 的 TAB 展开 8 空格与代理对 4 格）；语料 div 锚行两者同值。带 div 的行上
        若混排 PRINTBUTTON，核心层按钮列区间含块区间可见宽、剔除后本层折行让文本左移、
        按钮切点可能错位——语料 40 处 div 锚行均无 PRINTBUTTON 混排，无实例可达。
     f. **子行图形段无基图尺寸源**（`CanvasConsoleView.divFlattener` 的 sizeSource 为 null，
        缺宽 img 按高见方占位）；语料 div 子行 0 处 img。core 模型 `DisplayDivBlock` 类文档
        的「过渡期近似」段已过时（锚行文本保留的描述），归属 HTMLDIV-B1 的文件，留组长顺手改。
  2. **显示路径宽容 vs 原版抛错**：width/height 缺失、未知属性、嵌套 div、未闭合 div、
     `color='#'` 这类原版打印期 CodeEE（HM :1227/:1360-1366/:667-670/:1029-1035、
     stringToColorInt32 :1684），显示路径宽容成「不成块/只认最外层/现状收块」；
     严格抛错归 `HtmlStrictParser`（量宽面，`HtmlStrictTest` 已钉）。语料 4.981 的 40 处
     div 全部平铺且带 width/height，宽容分支现无语料可达（`TEMP_CHARA_LIST_BY_DT.ERB:103`
     的 `color='#'` 在原版会抛 `CanNotInterpretNumValue`——该行是活代码，真机上这句
     HTML_PRINT 本来就报错，移植侧同样不染不成块，TODO(verify)：等真机对拍采集）。
  3. **div 颜色名形态**：sink 侧 `parseDivSpec` 的 color 只认 `#RRGGBB`/十进制
     （host 不依赖 fn 的 ColorNames 表），颜色名写法不成块；语料 div 的 color 全部
     `#` 十六进制（`%HTML_BG%`/`%INT_TO_RGB%` 展开后同形），0 处颜色名。
  4. **锚点像素 = 行内像素游标（2026-11-06 根修，原「等宽近似」已作废）**：
     `anchorPointXPx` 改由 `LineBufferConsoleSink` 的 `inlineCursor` 逐节点累加给出
     （文本 = 格 × FontSize/2、`<img>` 按显式宽或基图纵横比的**像素**
     `CellWidth.graphicWidthPxOrNull`、`<shape>` 像素、`<div>` 0），`anchorCol` 由同一趟
     遍历的格投影给出。原实现是「可见文本格数 × FontSize/2」——`<img>` 被去标签剥成 0，
     语料 `魔改内容/PRINT系/Toolkits.ERB:77-81` 的 `LAYOUT_IMG_TEXT`（`<img width='180px'>`
     后接 `<div xpos='20px'>`，`SPTALK` 全走它）div 落回行首 20px、台词压在立绘上
     （真机截图实证）；`神签.ERB:64/:80`（只给 height 的 img + div）同病，本次一并靠
     `imageSizeOf` 宿主注入（`GameSession` 现读 aux 的 `SpritePool`，与
     `BuiltinHost.imageSizeOf` 同表）修掉。
     残留近似（在册）：①文本像素仍按「格 × FontSize/2」等宽近似（原版 GDI 量宽，
     比例字体下有偏差，ADR-001 同性质）；②负尺寸 `<img>` 只按绝对值占位、**不做镜像绘制**
     （`ConsoleImagePart.cs:83-92` 的翻转；语料 `画像反転フラグ` 0 处非 0，无可取证对象）；
     ③`fn/HtmlMethods.kt` 的段宽 `widths`（GETDISPLAYLINE/GETPRINTEDSTR 的按钮列）仍按
     剥标签可见文本量，未接本游标 —— 语料无「HTML 图元行与 PRINTBUTTON 同一逻辑行」实例，
     无屏上证据。
  5. **缓冲路径（option=1）的块锚列**：整段未拆时按段内偏移计，被 `[n]` 自动按钮切散的
     段块丢弃（语料缓冲段 div 内 0 处 `[n]`）；黄金锚 `pc-golden-root.txt:71` 的前导
     「【欢落五子棋】」token 的采集出处不在 fixtures（div 部分字节已逐字对上），
     TODO(verify)：卡点=采集脚本不在仓。
- **验证**：`core-vm` `HtmlDivBlockTest`（13 例：TITLE 全属性几何、百分比/abs/未逃逸/
  rect+size、量宽面不观测、GETDISPLAYLINE 黄金锚逐字节、GETPRINTEDSTR 剔除、缓冲路径、
  宽容收口）、`LineBufferConsoleSinkTest` 的 div 用例按复刻口径重钉（2 例，原断言→新断言
  见测试 KDoc）、`render` `DivOverlayTest`（8 例）；`HtmlStrictTest`/`HtmlDivWidthTest`
  等既有 HTML 面全绿（解析器未动）。
- **消费端接线验证（DIV-WIRE 波补记，2026-11-06）**：`render` `DivOverlayWireTest`（10 例：
  adapt 剔块 + 区间不可考不剔 + 无块行不变、fold 锚排/折点边界归前排、PRINTN 并入块拼接、
  planFrame 深度两侧/未逃逸不进/窗口外相对块按登记区间过滤 absolute 恒进、divOverlayScan
  挑块行、composeInto 像素级「正文剔除子行/behind 底色垫正文下/front 压正文上/子行落内容盒」、
  ConsoleFrame 覆盖块在场与退场都整屏）；定向验收读数 core-vm（Html/LineBuffer/Div 三族）+
  render + render-canvas 141 套 1429/0 + E2E 黄金 4.981 口径 5/0。

## 34. A4 三件翻案落地：img 无 width 按图元宽高比 + 立绘段按钮行内命中 + 图层几何精确式（IMG-A4 · 2026-11-06）

对应契约 `CONTRACT-IMG-A4.md`（主人 2026-11-05 指令）。本节只记「近似/偏差 → 复刻」的翻案与残留 TODO。

### 34.1 无 `width` 属性的 `<img>` 按图元真实宽高比（翻案「回落量 AltText」）

- **原版**：`ConsoleImagePart` 构造先 `AppContents.GetSprite(ResourceName)`（ConsoleImagePart.cs:60）；
  取到图元且无 `width` 属性时按 `DestBaseSize.Width * 折算高 / DestBaseSize.Height` 写段宽
  （:67-71，`num` = :66 的 height 折算值），`XsubPixel` 吃浮点余数（:70）；**取不到**才
  `Text = AltText`（:61-64）让 `SetWidth` 量文案（:110-114）。
- **翻案前**：core 无 sprite 度量表 ⇒ 无 `width` 的 `<img>` 一律走量 AltText 回落
  （FN-01 的登记余项）。
- **落地（I1）**：`BuiltinHost.imageSizeOf: ((String) -> ImagePixelSize?)?`（默认 null，
  null = 宿主未接线 ⇒ 回落零变化）；生产**不需要宿主覆写** —— `BuiltinEvalContext` 的
  `AuxMacroHost` 装饰器在委托缺省时现读 `store.aux` 的 `SPRITE_SLOT` 池（`SpritePool.find`，
  `AppContents.GetSprite` 的移植同构、键归一大写），真机 sprite 表（含 CSV 登记尺寸）在
  `GameHost.installGraphics` 装配期已入 aux。消费点在 `HtmlStrict.kt` 的 `imgGroupNode`
  （img/shape 计宽段的物理所在；契约写的 `HtmlMethods.kt` 是该面的入口链 —— `htmlLength`
  把 `c.host.imageSizeOf` 传进解析器），`HtmlSubString` 的 img 扣宽与 `HTML_STRINGLEN`
  共用同一把尺（原版 HtmlSubString 的 img 扣宽也是调 `HtmlLength`，HtmlManager.cs:279-284）。
- **有意偏差**：原版 :69 在 `DestBaseSize.Height == 0` 时整数除零崩溃；移植侧把
  `heightPx <= 0` 的退化登记当「图元缺失」回落量 AltText，不崩。语料无法构造该形态
  （CSV/SPRITECREATE 均校验正尺寸）；TODO(verify)：真语料出现零高 sprite 登记时再对拍
  原版崩溃口径，卡点＝无实测样本。
- **验证**：`core-vm` `HtmlPrintedWidthTest` 17 例（新增 4 例：宽高比四形态 num 折算、
  亚像素余数被 shape 吸收、负宽取绝对值、宿主缺位/未命中回落逐位不变 + aux SpritePool
  生产接线形状）；`HtmlStrictTest` 等既有 HTML 面全绿（宿主缺位读数零变化）；组长独立复跑（2026-11-06，聚合 XML）
core-vm 4 类 **54 例 / 0 失败 / 0 错误 / 0 跳过**（HtmlDivBlock 15 + HtmlMethods 13 +
HtmlPrintedWidth 17 + HtmlStrict 9）。

### 34.2 立绘段按钮命中行内纵向矩形（翻案「按整行」）

- **原版**：命中路由对按钮内**每个节点**判 `PointX > x || PointX + Width < x ||
  num4 < Top || num4 > Bottom`（EmueraConsole.cs:2368；`num4` = 点击点相对行顶的像素，
  :2333/:2356 逐行折出；逃逸支 :2420 同口径闭区间）。节点盒：立绘
  `ConsoleImagePart.cs:31-33,81,93`（`[ypos, ypos+高]`）、矩形件
  `ConsoleRectangleShapePart.cs:19-21,34-35`、文本/Space 缺省带
  `AConsoleDisplayNode.cs:29-31`（`[0, FontSize]`）。
- **翻案前**：`ButtonHitAuditTest` 第 3 条登记「本层按整行判定，无纵向子范围」，且其
  「文本段 `AConsoleColoredPart` 恒为整行」的旧注有误 —— 缺省带是 `[0, FontSize]`，
  语料 `LINE HEIGHT == FONT SIZE == 18`（`emuera.config:22-23`）下才等价整行。
- **落地（I2）**：`ButtonLayer.attach` 给入表图形段同记**行内纵向门**（列区间 + 图元负载）；
  新面 `hitInline(row, col, yInRowPx, fontSizePx, acceptance)` 按图元节点盒加严（y 闭区间
  照抄 :2368/:2420），文本列/无门列整行带直过；旧面 `hit(row, col)` 行为逐位不变。
  溢出立绘（跨行图身）的点击解析原归 `view/GraphicTapAnchor`（逃逸支同构面），互补不重叠。
- **需组长接线**：`CanvasConsoleView` 的命中路（`hitOnCurrentView`/`setPointer`）把 tapY
  折成行内像素后改调 `hitInline` —— render-canvas 不在本波文件面。
- **已知网格近似**：非语料配置（`LINE HEIGHT > FONT SIZE`）下文本按钮的
  `[FontSize, LineHeight]` 死带不复刻（移植侧文本列恒整行可点）；真机手感差异待真机对拍
  TODO(verify)，卡点＝无设备。
- **验证**：`render` `ButtonHitAuditTest` 10 例（第 3 条翻案重写 + 新增 3 例：立绘盒内
  闭区间/盒外不命中/旧口径不变、矩形件 `Math.Max(FontSize,…)` 整行盖、混排按钮文本列
  整行图段列加严）；worker 首跑读数 1106 例，组长独立复跑（2026-11-06，聚合 XML）
  **1110 例 / 0 失败 / 0 错误 / 0 跳过** —— 差值来自两波基点的既有用例增量，以复跑为准。

### 34.3 `SETIMAGELAYERL` 图层几何 tops 近似精确化（翻案 `ImageLayerGeometry` 类 TODO）

- **原版（本地可证部分）**：`GetLinePointY(lineNo) = (MainPicBox.Height - LineHeight)
  - (num2 - lineNo)·LineHeight`，`num2 = min(ScrollBar.Value - 1, Count - 1)`
  （EmueraConsole.cs:1786-1795）；`ClientHeight = MainPicBox.Height`
  （:232-235）。坐标公式本体出自 fork 源（`Instraction.Child.cs:4199-4205`，本机
  reference 快照无 fork-src 文件），公式文本以 `specs/isa.yaml` 的 SETIMAGELAYERL 条目
  为 pinned 依据。
- **翻案前**：调用侧以 `RowLayout.Plan.tops[lineNo]`（内容区坐标）充当窗口坐标的
  `GetLinePointY`，差一个内容区原点（类 KDoc TODO(verify)，卡点＝无 PC 截图可比）。
- **落地（I3）**：把本地 `GetLinePointY` 展开代入坐标公式后，`+LineHeight - ClientHeight`
  与展开式里的 `(ClientHeight - LineHeight)` **严格相消** ⇒ 精确式
  `y = imageHeight - LineHeight - (num2 - lineNo)·LineHeight + ypos` 不再引用锚行行顶的
  绝对坐标（滚动/坐标系换算在代数层面消失）。`ImageLayerGeometry` 新增
  `linePointYPx` / `layerTopPxExact` / `layerBoxPxExact`（逐项 C# 对拍写进 KDoc）；
  旧四项式 `layerTopPx`/`layerBoxPx` 保留为近似面（调用侧未切）。
- **残留 TODO(verify)**：① 坐标公式本体不可本机取证（fork-src 缺文件）——卡点不变；
  ② `CanvasConsoleView.drawImageLayers` 尚未切精确式（传 `linesBelowAnchor` 而非 tops）
  —— 需组长接线；③ 精确式与旧式在「行高等高 + 内容恰满一屏」形态逐位同值（测试钉），
  立绘行改高/滚动中两形态的真机读数待对拍，卡点＝无设备。
- **验证**：`render` `ImageLayerGeometryTest` 9 例（新增 4 例：GetLinePointY 本地展开、
  精确式与四项式等价自证、底行锚定消 H、精确式整框）。

## 图文并排 / 图层显示簇收口（2026-11-06，Lead + 三路并发 lane）

触发证据：真机截图「立绘被黄色台词压住」= 语料 `魔改内容/PRINT系/Toolkits.ERB` 的
`IMG_TALK`（`<img width='180px'>` 后接 `<div xpos='20px'>`）。根因在移植侧量宽口径，
**不是语料**。本波一次性收口以下八条（全部带定向单测）：

1. **div 锚点 = 行内像素游标**（根修，替换「可见文本格数 × FontSize/2」）：
   `LineBufferConsoleSink.inlineCursor` 逐节点累加 `(脚本像素, 半角格)`（文本走
   `CellWidth.of`、图元走 `CellWidth.graphicWidthPxOrNull`、`<div>` 零宽），
   `divAnchor` 用它给 `anchorPointXPx`/`anchorCol`；纯文本读数逐值不变。
   依据 `ConsoleButtonString.cs:292-299`（`PointX = num; num += node.Width`）、
   `ConsoleImagePart.cs:66-82`（图元像素宽）、`ConsoleDivPart.cs:217-219`（块零宽）。
2. **缺 `width` 的 `<img>` 按基图纵横比占宽**：`CellWidth.visibleHtmlCells(s, fontPx, imageSizeOf)`；
   尺寸源由宿主注入（生产 `GameSession` 从 aux `SpritePool` 现读，`LineBufferConsoleSink.imageSizeOf`），
   未接线时计 0（headless 零行为）。依据 `ConsoleImagePart.cs:66-70`。
3. **HTML 段列坐标图形感知**：`fn/HtmlMethods.kt` 的 `consolePrintedLine` 段宽由「剥标签可见文本」
   改为按段取 `visibleHtmlCells`（HTML 段）/ `CellWidth.of`（纯文本段），按钮开闭区间随之正确
   （`HTML_GETPRINTEDSTR`/`GETDISPLAYLINE` 回读）。
4. **回读按会话生效配置重建**：`htmlPrintDocument` 的 `fontSize`/`focusColor` 由出厂常量改为注入面，
   生产从 sink 现取（`ConsoleBuiltinHost.printedLinesOf`）——`Config.FontSize` / 焦点色非出厂值时
   回读与屏上不再分叉。依据 `Utils.cs:87-98/364-371`、`HtmlStrict.kt:677/890` 的值比较语义。
5. **行内文本像素走宿主真度量**：sink 新增 `textPxOf`，`GameSession` 复用已有
   `textMeasureRun`（app `AndroidTextMeasure`）；未接线/该段答 null 回落 `格 × FontSize/2`。
   依据 `StringMeasure.cs:42-72`（GDI 实测）。残留：只吃纯文本 ⇒ 恒按生效 FONT NAME + 常规体 +
   charSpace 0 量（逐段 `SETFONT`/FONTBOLD 的样式精度仍近似，TODO(verify)）。
6. **命中纵盒随行压缩 k 缩放**：`GraphicGeometry.rowScaleOf` 成为绘制/命中唯一入口，
   `ButtonLayer.hitInline` 与 `GraphicTapAnchor.resolveAnchorRow` 新增行几何入参；
   上沿不乘 k（绘制侧 `yOffsetPx` 不缩，只缩图高），缺省参数 = 旧口径。
7. **负尺寸 `<img>` = 翻转而非丢弃**：`InlineGraphic.Image.flipX/flipY`（判据是**折算后**符号，
   照 `ConsoleImagePart.cs:83-92`），布局侧仍取绝对值；`CanvasConsoleView.drawImage` 绕目标矩形
   中线 `scale(±1)` 实现原位镜像。语料 `画像反転フラグ` 全库无一处非 0 ⇒ 该分支当前不可达。
   残留：`ImageBandMerge`/`PRINT_IMG` 重建 Image 时不拷标记（语料走不到，TODO(verify)）。
8. **CBG 图层上屏**（`CBGSETG`/`CBGSETSPRITE`/`CBGSETBUTTONSPRITE`/`CBGCLEAR*`）：core
   `CbgPool.snapshot` 推整表快照（depth 降序）→ `GraphicsSink.setCbgLayers`（缺省空转）→
   `GraphicsLayer.cbgLayers` → headless `ScreenComposer` 按 depth 与立绘线性归并 + 真机
   `CanvasConsoleView.drawCbgLayers`（depth>=0 垫正文、depth<0 压正文，下沿锚定
   `y + 客户区高 - 图高`）。依据 `EmueraConsole.cs:645-646/679-680`（Add+Sort）、`:1834-1857`
   （双路归并）、`:1846`（下沿锚定）、`:1844`（IsCreated 守卫）、`:1816-1820`（在烘焙背景之上）。
   gid 层像素走 `ImageFrameSource.frameByGid`（platform 从 `GraphicsPool` 现读缓冲，只查不建、
   复用 LRU/OOM 兜底），不把像素塞进快照、不注册同名 sprite（避免 `aux:sprite` 进 L3 哈希）。
   残留：CBG 按钮态换图/命中/tooltip 未接（DTD 只带最小集）；headless 的 gid 像素仍空；
   uipreview 的 scene 不带 `cbgLayers`（预览不显示名路，TODO(verify)）。
9. **渲染侧接线补齐**：`render-canvas` 直接依赖 `:core-vm`（`GraphicsLayer.cbgLayers` 在公共 API
   暴露 `CbgLayerSnapshot`，缺依赖编译红）；`ConsoleFrame` 整屏判定加 `scene.cbgLayers.isNotEmpty()`；
   div 子行展平器接 `ImageFrameSource` 尺寸源（缺 width img 不再见方占位）。

**宽度模型分层（2026-11-06 第二轮收口，原「两张表」条目作废）**：显示宽度只有一个模型 ——
`render` 的 `Grid.cells` / `glyph/CellMetrics.cellsOf` / `GlyphCache` 推进 = 逐 UTF-16 **码元**
（TAB 展开 8 空格、合法代理对各 2 格 = 增补平面 **4 格**）；core 的
`CellWidth.displayCells` / `displayCodePoint` 与之**逐值同口径**（显示路径：折行/行宽/div 锚点/
按钮列位/`padC`/`barString`/`GETLINESTR`/`HTML_PRINT` 行宽/`HtmlMethods` 段宽全切过去）。
`CellWidth.of` / `ofCodePoint` 保留**字节数**口径（CP932 `GetByteCount`：TAB=1、增补平面=2），
只服务字节数语义（`EmueraConsole.cs:3255`、`GDRAWTEXT` 单码位步进）与既有测试。
两条口径是**语义分层**不是分叉：显示路径一律 display*，字节路径一律 of*。

**并发构建坑（记一次）**：多 lane 在同一 worktree 并发跑 gradle 会互删
`tools/erbcc/build/**` 的 Kotlin 增量缓存，`erbcc` jar 被压成 260 字节空包 ⇒ 下游 test
编译报 `unresolved reference` 假红（本波出现过 `LabelDef` 一类）。恢复：
`--rerun-tasks :tools:erbcc:jar`；根治：同 worktree 的构建串行化（本波后期已改串行验收）。

## 第二轮自审复核（2026-11-06 晚，三路只读审计 + Lead 修复）

审计方法：三路只读子代理分别审 core 宽度/游标、render 命中/翻转/CBG、core CBG/回读/度量，
要求「给可复现反例、给期望 vs 实际」，不接受「看起来没问题」。修掉的**真缺陷**：

1. **TAB 宽度两把尺**：`CellWidth` 按 CP932 字节数把 TAB 记 1 格，但原版量宽前先
   `Replace("\t", "        ")`（`StringMeasure.cs:59-62`），渲染侧也展开 8 空格 ⇒ 折行/锚点/
   选项列与屏上实占差 7 格。改法**不是**动 `of/ofCodePoint`（那是字节数口径，`FnWidthSingleSource`
   等 4 个测试按 .NET 932 `GetByteCount` 钉死 TAB=1）：新增
   `CellWidth.displayCells` / `displayCodePoint`（TAB = `TAB_CELLS` = 8），显示路径
   （`lineWidth`/折行/div 锚点/按钮列位/`padC`/`barString`/`GETLINESTR`/`HTML_PRINT` 行宽/
   `HtmlMethods` 段宽）全部切过去；`GDRAWTEXT` 的单码位步进保持字节口径（`ofCodePoint`，
   既有测试钉 9px）。回归：`HtmlWidthRulerTest`。
2. **未闭合 `<` 两把尺**：`visibleHtmlCells` 把没有 `>` 的尾巴当标签吃到串尾（0 格），
   游标按文本计；原版正则 `\<[^<]*\>` 不吞未闭合（`HtmlManager.cs:714-717`）。
   改：折行侧同样按文本计。
3. **越界数字实体两把尺**：`&#x1F600;` 折行侧按增补平面算 2 格，游标侧 Unescape 保留字面量
   算 9 格；原版 `numericEntity` 只认 0..65535（`HtmlManager.cs:817-831`）⇒ 改折行侧按字面量
   （`&…;` 逐字符半角）计。
4. **负尺寸 `<img>` 占宽取负**：原版把负宽翻正（`ConsoleImagePart.cs:83-87`，镜像另由
   `destRect.X = -Width` 表达），端口渲染侧也 abs，但 core 占宽仍是负数 ⇒
   `<img width='-180px'>` 后面的 div/文字整体左移。改：`imgWidthPxOrNull` 两支出参取绝对值；
   `PRINT_IMG` 路（`GraphicsSinkBridge`）同步落 `flipX/flipY` + abs（此前负值会退化成帧宽）。
5. **`<div>` 子行文本计入父行宽**：原版块零宽（`ConsoleDivPart.cs:217-219`），子行是独立
   display line；端口折行/按钮列位把块内文本也算进去（一行 N 张卡的父行宽 = 全部台词总宽）。
   改：`visibleHtmlCells`/`inlineCursor` 遇 `<div>` 开标签整块跳到 `</div>`
   （`CellWidth.divBlockEnd`，嵌套宽容为第一个闭合）。
6. **跨 span 锚点基址重解析**（本波自引入）：`rowContentPx` 曾把 `RowBuilder.segs` 原文拼起来
   当 HTML 解析 ⇒ 纯文本段里的 `<`/`&…;` 被当标签/实体吞掉（`print("A<B>C")` 基址算成 2 格）。
   改：**逐段**量 —— HTML 段走 `inlineCursor`、非 HTML 段走 `textWidthPx(text, CellWidth.of(text))`。
7. **命中纵门零生产调用点**（本波自引入的「死 API」）：`ButtonLayer.hitInline` + B1 的 k 只活在
   单测里，`CanvasConsoleView` 仍调 `buttons.hit`（整行可点）。改：新增
   `ConsoleScreen.hitInline/clickInline`，Canvas 的 `cellHitAt` 统一产出
   （指示点/按下高亮/长按提示/`MOUSEB()` 四条路同判据），`resolveAnchorRow` 补齐
   `heights/artOnly/lineHeightPx`。
8. **CBG 真机绘制缺依赖**：`render` 对 `:core-vm` 是 `implementation`，公共 API 暴露
   `CbgLayerSnapshot` ⇒ `:render-canvas` 编译红。改：`render-canvas` 直接依赖 `:core-vm`。

**登记不修（语料 0 命中 / 结构债，均有卡点）**：
- CBG 按钮态（`spriteNameB`/`isButton`/`buttonValue`/`CBGSETBMAPG`）：4.980/4.981 语料
  `CBGSETBUTTONSPRITE`/`CBGSETBMAPG` 各 0 处；DTO 只带最小集，按钮换图与命中/tooltip 未接。
- CBG 动画 sprite 的帧尺寸/`Offset`：`CBGSETSPRITE` 语料 0 处；快照给 DestBaseSize，
  原版动画走帧矩形+Offset（`SpriteAnime.cs:137-146`）。
- CBG 与 div 的真实单趟 depth 归并：端口是「全部 CBG>=0 → div behind → 正文 → div front →
  CBG<0」两段；原版 CBG 与逃逸文本段同一张 depth 归并（`:1834-1857`），depth 交叉时前后可能颠倒。
- CBG 名层每帧按名重查（`SPRITEDISPOSE` 后端口会认同名重建的新 sprite；原版持引用永久不画）。
- ~~`render` 的 `Grid.cells` 与 core `CellWidth` 两张表~~：2026-11-06 第二轮已收口为「显示宽度 / 字节数」分层（见上）。
- 压缩行命中盒不含 `DestBasePosition` 位移：原版 `Top/Bottom` 同样不含（`ConsoleImagePart.cs:81,93`
  + `EmueraConsole.cs:2368`），属复刻而非缺陷（KDoc 已改写说明）。
- `CBGREMOVERANGE` 在 `zmin > zmax` 早退后仍推快照 + invalidate（多余一次整屏，语义无害）。
- `CBGSETBMAPG` / `CBGSETBUTTONSPRITE` 的按钮值走位图取色（`EmueraConsole.cs:1309-1320`）未接：
  语料 0 处；`CbgPool.buttonMapGid` 目前只有写没有读。

**既有红（不是本波引入，已用干净 worktree 复现）**：`:core-vm:test` 全量 6359 例里下列
5 例在**未含本波改动的基线**上同样红 —— `41bd5048`（wave 前）与 `origin/main` 两个
`git worktree` 上跑同一 `--tests` 都红：
- `L3HashChainTest` 3 例（同进程两次运行链值不同，首个差异簇 `aux:gfx`；属 L3 装置的
  确定性/环境问题）；
- `PrintRectColorTest` 1 例（`PRINT_RECT 5px` 被解析成「""を整数値に変換できません」）；
- `M11eExpandE2ETest` 1 例（同根：`GOLDEN_03_GFX.ERB` 的 `PRINT_RECT 5px` 切片编译失败）；
- `HeapCompositionProbeTest` 1 例（「侧车没生效（回落扫描档）」：内存探针装置读数）。
判定口径：本波只对「既有红之外新增的红」负责，本波新增红已全部清零。

## 第三轮收口（2026-11-06 夜，CBG / SPRITEANIMEFRAME / srcm / px 词法）

**结论**：上一轮登记的「遗留问题」全部收口；本轮另修掉 4 条**既有红**（其中 3 条是同根：引擎按原版拒粘连 `px`、fixtures 没跟上）。

### 收口项（每条都有生产调用点，无死 API）
1. **CBG 完整能力**（原「只垫静态图」）：DTO 扩 `spriteNameB/isButton/buttonValue/buttonMapGid/baseHeightPx/frames/frameIndex`；
   `CbgPool.snapshot` 给完整帧表（动画层首帧几何、无帧 `NO_FRAME` = 整层不画）；`CBGSETBMAPG`/`CBGREMOVEBMAP` 补推送；
   纯函数 `CbgDrawOrder.frameGeometry` / `CbgAnimeClock` / `buttonMapSamplePoint` / `hitCbgButton` / `merge`；
   `ScreenComposer` / `ConsoleFrame` / uipreview 走同一份；**真机 Canvas 已接**（归并序、按下换图、动画时钟、
   `NO_FRAME` 跳过、取色图命中 → `GameHost.submitCbgButtonValue` → `QueueInputSink.submitCbgButtonValue`）。
2. **`SPRITEANIMEFRAME`**（4.981 fork 增量，`画蛇又添足版自改emuera相关说明.txt:236-239`）：新
   `fn/SpriteAnimeFrameMethod.kt`，`Builtins`/`BuiltinSymbols`（functions+methods）/`tools/erbcc` 的 `ERB_INSTRUCTIONS`
   四处登记齐全，ledger 计数 299→300 / 296→297。Gif/WebP 两形态端口无解码面 ⇒ 恒 0，`TODO(verify)` 登记。
3. **HTML `<img srcm='…'>`**：`HtmlRunFlatten.emitImage` 读 `srcm` → `Image.mappingResource`（空串 → null，
   `ConsoleImagePart.cs:51-54`/`:98-101`）；消费链原本已通（Canvas `mappingColorOrNull` → `MainActivity.pickMappedColorAt`
   → `GameHost.submitMappedColor`），把两处「需组长接线」的过时 KDoc 改成现状。
4. **显示宽度单源**：显示模型 = 逐 UTF-16 码元（TAB 8、增补平面 4）——core `CellWidth.displayCells/displayCodePoint`
   与 render `Grid.cells`/`CellMetrics.cellsOf`/`GlyphCache`/`DisplayList` 逐值同值；`CellWidth.of` 保留**字节数**口径
   （CP932，TAB=1、增补平面=2）。曾试图把布局改成「一个码位 2 格」→ 实测渲染 `advanceCells` 是 4 格，会让布局比落笔窄一半，
   已回滚并把显示口径定在 4（见 `HtmlWidthRulerTest` 的「增补平面显示宽度四格 字节口径两格」）。
5. **按下高亮死 API**：`CanvasConsoleView.setPointer/clearPointer` 在 `MainActivity` 触摸段接线（DOWN → setPointer、
   UP/CANCEL → clearPointer）。

### 顺带修掉的既有红（同根：fixtures 用了粘连 `px`）
`PRINT_IMG/PRINT_RECT` 的 `px` 只认「自成一词、后随逗号或行尾」（`ExpressionParser.cs:534-537`；粘连形词法层
`long.Parse("")` → CodeEE）。引擎已按原版拒粘连，但 `fixtures/golden-m11e/GOLDEN_03_GFX.ERB`、
`PrintRectColorTest`、uipreview 的 `ImageFrameTest`/`GalleryPngTest` 还写着 `5px` ⇒ 统一改成 `5 px`。
**收益**：`PrintRectColorTest`、`M11eExpandE2ETest`、uipreview `ImageFrameTest`/`GalleryPngTest` 全绿（原红列表收缩）。

### 仍未绿（既有、非本轮引入；两者都在 `41bd5048` 与 `origin/main` 上复现过）
- `L3HashChainTest` ×3：同进程两次运行的链值在 `aux:gfx` 簇出现差异（`SpritePool`/`GraphicsPool` 的
  `LinkedHashMap` 访问序被 `peek` 触碰，`peek` 本身不改像素但改 LRU 序 ⇒ 序列化哈希不稳定）。**修法**：给
  `GraphicsPool.peek` 换非访问序读写或让 L3 对 `aux:gfx` 走稳定视图；属 core host 面，单开一刀。
- `HeapCompositionProbeTest`：堆采样读数受机器环境（JVM/OS 页分配）影响，本机 1286 MiB 恒定不过；
  属**环境读数**，不是像素/语义回归。
## 2026-11-08 SETFONT 换字形也换排版尺（AA 画/老虎机字符画错位修复）

玩家反馈「觉的 AA 画、红魔馆老虎机字符画显示错位」。根因是移植版把排版宽度钉成一张
**与字体无关**的静态 CP932 格数表（core `CellWidth.displayCodePoint` / render `Grid.cells`），
真机绘制再用 `Paint.textScaleX` 把字形横向拉伸塞进静态格子 —— `SETFONT` 只换了字形长相、
没换排版尺。原版每段宽度都按该段字体实测（`StringMeasure.cs:42-72` GetDisplayLength；
`ConsoleStyledString.cs:25,82` `Width = sm.GetDisplayLength(Text, Font)`；
`ConsoleDisplayLine.cs:66` `num += node.Width` 逐段累加），两处错位由此而来：

- 老虎机（`CASINO/CASINO_SLOT_UTIL.ERB:633` `SETFONT "MS Gothic"`）：块元素
  `▂ U+2582`/`▉ U+2589` 在 CP932 表是「表外字」⇒ 2 格，随包等宽字体 hmtx 给的是半角
  ⇒ 实际 1 格；82 字符上框被算成 164 格，横屏 136 列必被误折行。
- 觉 AA 画（`049 Satori`/`FONT_AA(通用字符画).erb:29-38` `SETFONT "Saitamaar"`）：
  比例字体，靠 `U+2009`/`U+200A`/`:` 做像素级微调（2.25px/1.125px/3.375px），
  静态表把这些窄字符放大 2.7~16 倍，各行膨胀量不同 ⇒ 整幅画散架。

**修法**（引擎侧，不改语料）：新增字体感知排版尺 `render/glyph/FontWidthRuler`
（读 §3 sidecar 的真实 advance，缺表/缺行确定性回落静态网格，整格吸附保等宽观感），
经 core 契约 `core/host/FontDisplayWidth`（`:render -> :core-vm` 单向依赖下 core 只见接口）
贯穿全链：`LineBufferConsoleSink.layoutRows`（核心折行）→ `ConsoleScreen.fontWidth`
→ `LineFolder`/`DisplayList`/`ButtonLayer`/`ConsoleAdapter`（二次折行/列区间/命中/按钮切段）
→ `CanvasConsoleView.drawCmdText`（逐字形浮点推进，`textScaleX` 改填「尺的 advance」，
比例字体 scaleX≈1 不再拉伸）。真机装配：`render-canvas` 的 `AndroidFontWidthRuler` 从
assets 读 sidecar（classpath resources 不进 APK；`#family` 反查文件名，兼容霞鹜 RFN 改名），
`CanvasConsoleView.fontDisplayWidth` 同一实例喂 `GameSession.fontWidth`。
缺省 `StaticFontDisplayWidth` = 接线前静态表，headless/黄金基准读数逐值不变。

已知近似（`TODO(verify)`）：
1. 整数格折行是 ADR-001 的重设计（原版 TEXTRENDERER 是纯像素折行）；比例字体段宽按
   「整串浮点累加一次取整」，与逐字取整的前缀宽不严格同式，只影响折行切点 —— 语料里
   比例字体只用于整幅 AA 画（单段、不折行），要贴 PC 须把列游标整体浮点化并同步重钉
   三处整数格结构，属跨模块重设计。
2. 相邻段的列衔接取整误差 ≤0.5 格（AA 画是单段行，实际不可见）。
3. 老虎机上框 82 字符在竖屏 62 列下仍会折行 —— 这是**正确**行为（PC 同样装不下），
   与此前「被算成 164 格而误折」是两回事。
定向闸：`FontWidthRulerTest`（尺读数）、`ConsoleScreenFontWidthWireTest`（屏级接线：
折行行数/列区间按尺 vs 缺省静态表逐值不变）。
