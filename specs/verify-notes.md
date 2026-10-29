# TODO(verify) 消解记录

**语义考古来源**：Emuera 1.824 官方源码
- 主证据源：`https://github.com/0x00000FF/Emuera`（commit `301ce4a4a4d0b0b20a14428a4680cf3dbd3559d8`，2019-05-23，"PPState Refactor"；`AssemblyInfo.cs:34-35` = `AssemblyVersion("1.824.*")` / `AssemblyFileVersion("1.824.0.0")`，与我们反编译目标版本号一致）
- 交叉验证源 1：`https://github.com/just-a-pony/emuera`（独立镜像，路径结构不同，关键代码一致）
- 交叉验证源 2：`https://github.com/xerysherry/uEmuera`（基于 emuera1824v7 的 Unity 移植，`Assets/Scripts/Emuera/GameData/Expression/OperatorMethod.cs` 关键代码一致）

**三源一致性**：下方所有"官方原文"均已在 ≥2 个独立仓库比对一致；第 1 项经三源验证。

---

## 1. 字符串 `>=`（最重要）

- **结论：官方 bug，被我们如实继承。** confidence: **高**（三仓库一致）
- **证据**（`Emuera/GameData/Expression/OperatorMethod.cs:477-491`，0x00000FF/Emuera）：

```csharp
private sealed class GreaterEqualStrStr : OperatorMethod
{
    public GreaterEqualStrStr()
    {
        CanRestructure = true;
        ReturnType = typeof(Int64);
    }
    public override Int64 GetIntValue(ExpressionMediator exm, IOperandTerm[] arguments)
    {
        int c = string.Compare(arguments[0].GetStrValue(exm), arguments[1].GetStrValue(exm), Config.SCExpression);
        if (c < 0)
            return 1L;
        return 0L;
    }
}
```

  即：**`s1 >= s2` 在 `s1 < s2` 时返回 1**，其余（相等/大于）返回 0 —— 完全反向。
- 同文件对照组（证明是复制粘贴笔误而非风格）：
  - `LessStrStr`（`:439` 附近）：`if (c > 0)`？不对——`LessStrStr` 为 `c < 0 → 1`（**正确**，s1<s2）
  - `GreaterStrStr`：`c > 0 → 1`（**正确**，s1>s2）
  - `EqualStrStr`：`s1 == s2 → 1`（正确，直接字符串相等）
  - `NotEqualStrStr`：`s1 != s2 → 1`（正确）
- **对策：保持复刻**（行为级复刻，不做"修正"）。

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

| # | 位置 | 症状 | 应为 | 实为 |
|---|------|------|------|------|
| 1 | `OperatorMethod.cs` `GreaterEqualStrStr` | 字符串 `>=` 完全反向：`s1<s2` 时为真 | `c >= 0 → 1` | `c < 0 → 1` |
| 2 | `OperatorMethod.cs` `LessEqualStrStr` | 字符串 `<=` 相等泄漏：`s1==s2` 时为假 | `c <= 0 → 1` | `c < 0 → 1` |

- 两者明显是同源复制笔误（`GreaterEqualStrStr` 与 `LessStrStr` 均为 `c < 0`，对照 `GreaterStrStr` 的 `c > 0` 可证方向写反）。
- `EqualStrStr` / `NotEqualStrStr` / `GreaterStrStr` / `LessStrStr` 均正常。
- **复刻纪律**：这两处按 bug 原样复刻（行为级兼容），并在引擎代码中留注释指向本清单。

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

## 10. 2026-10-19 SPEC-FIX 处置（CONTRACT-M4-A §4 · worker l-specfix，worktree `D:/DSH/eratw-wt-specfix`）

工具链：`eratw-android/tools/spec_crosscheck.py` + `tools/miniyaml.py` + `tools/golden-coverage.py`
（Python 3.13.14，零第三方依赖；reference 走 `D:/DSH/eratw-安卓移植计划/reference/emuera-cs`）。
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

## 12. 2026-10-21 ADR-005 偏离登记：串族 S4/S10/S11 + 文件族 F6（worker w-strrest，worktree `D:/DSH/eratw-wt-strrest`，分支 wave/m6strrest）

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

### 13.1 `CALLSHARP` 的常量实参在装载期被分隔线生成器填充（登记不修）
- **机制**：`FunctionIdentifier.cs:1179-1191`（`CALLSHARP_Instruction.CreateArgument`）在
  `new SingleStrTerm(barStr)` **之前**有一句 `barStr = GlobalStatic.Console.getStBar(barStr);`（**:1185**）。
  `getStBar`（`EmueraConsole.cs:3420-3437`）把入参反复自我复制到显示长度 ≥ `Config.DrawableWidth`
  再逐字符回删 ⇒ 实参文本变成**按控制台宽度填充的重复串**，函数名查找用的就是这个串。
  空串入参时 `num` 恒 0 ⇒ **原版死循环**（`:3426` 的 while 无退出条件），与 §「GETLINESTR 零宽死循环」同源。
- **移植侧现状**：`core/stmt/ArgBuilders.kt:647-660` 直接把原文建成常量实参，无填充步。
- **裁定 = 登记不修**，理由三条：① 语料 `ERB` 子树 `CALLSHARP` **零命中**（m-isagap 实测），
  不可达行为；② 复刻需要**装载期**的 `Config.DrawableWidth` + 字体测量通道（`Console.getStBar` 读
  `Config.DefaultFont`），而 #DIM 装载期的同一条通道已单列为 m-dimfold 的 P6 卡点，不该在这里另起一套；
  ③ 填充后的名字几乎必不命中函数表 ⇒ 原版这条语句的实际后果是「装载期把实参弄坏」，
  复刻等于把坏行为接到装载链上，收益为负。
- **重评条件**：若将来有语料/插件用到 `CALLSHARP`，或做「装载期控制台条宽」统一通道（与 P6 同批），
  则按 §13 的机制复刻填充并同步 `isa.yaml` 的 `CALLSHARP` 条目。
- **同批账面项**：`ADDSPCHARA`/`CALLSHARP`/`FORCE_BEGIN` 三条在 `core/stmt/FunctionCode.kt:114/:134/:182`
  的 flag 位为 0，而 `specs/isa.yaml:639/:1533/:1884` 记 4/38/1（原版出处
  `FunctionIdentifier.cs:1353/:1174/:2944`）。运行期无消费点（`git grep "\.flag\b" core/stmt` 只命中注释）
  ⇒ **纯账面分歧**，台账由后续波次对齐，不影响任何读数。

### 13.2 DT 族四条 .NET 框架侧缺口（现状钉桩 + TODO(verify)）
- **分歧 B（`DT_COLUMN_ADD` 不扩老行）**：复核**维持不修正**。原版 `FunctionMethodCreator.cs:2102-2121`
  的 `Columns.Add`（:2118）与 `AllowDBNull` 回填（:2119）都在建列期，全 reference 穷举 12 处
  `Columns.Add`/`NewRow`/`Rows.Add` 均在 `NewRow` 之前（`ConsoleEscapedParts.cs:50-55`→`:69`→`:83`），
  **老行新列取何值完全发生在 .NET `DataColumnCollection.Add` 内部，仓库零行号** ⇒ 不断言框架行为。
  现状可观测面：移植侧行是 index-parallel（`MapDataTableMethods.kt:453-456`），`dtColumnAdd`（:570-583）
  不扩行而 `dtColumnRemove`（:701）缩行 ⇒ 「老行 × 新列」在 6 处裸下标（:858/:870/:879/:899/:905/:908）
  抛 JVM `IndexOutOfBoundsException`（原版这些位置该读到值）。**修法与分歧 D 必须同波**（同一
  `newRow()` 派生规则 :476-487），否则 D 未定案时单修 B 会把 D 的预填读数带回去。
- **分歧 D（不可空列预填 0）/ G（`DT_CELL_GETS` 按 id 的 `(string)` 强转）/ J（`DT_FROMXML` 按
  `msdata:MainDataTable` 卡名）**：三条都缺 .NET 框架侧行号（原版同样在 `:2749-2752` 一类位置吞异常），
  无实机取证 ⇒ **现状钉桩 + TODO(verify)**，由 `MapDataAuditTest` 逐条钉住移植侧读数。
- **分歧 C（`DT_COLUMN_NAMES` 越界静默截断）＝ 已修**（M4-M m-dtcolnames）：原版循环上界是
  `Columns.Count`、与目标数组长度无关（`:2079`），体内 `array[i] = …`（:2081）⇒ 越界抛
  `IndexOutOfRangeException` 且 `:2083` 的 return 走不到（**整个函数无返回值**）；抛出形态是**裸 CLR 异常**
  （`FunctionMethodTerm.cs:20-23` 无 try、`VariableTerm.cs:57-106` 原样 `throw;`、
  `Process.cs:651-660` 的非 EmueraException 分支），故移植侧抛 `IndexOutOfBoundsException` 而非 CodeEE。
- **对照不动项**：`MAP_GETKEYS` 的两处截断 break 是原版自带（`FunctionMethodCreator.cs:2987-2995`），
  **不是**分歧，别跟着一起「修」。

### 13.3 `DELCHARA` 数组参失败路径的「已 Dispose 仍在表内」半死态（登记不修）
- 原版 `VariableEvaluator.cs:1253-1273`：**边校验边 `Dispose()`**（:1267），全部校验通过后才
  `CharacterList.Remove(item)`（:1269-1272）⇒ 第 k 个元素越界时，前 k 个角色**已释放但仍仍在表内**，
  后续读它们会踩到被置 null 的私有数组（.NET 侧 `NullReferenceException`，不是 CodeEE）。
  `CharacterData.Dispose`（`CharacterData.cs:756-773`）只置空本实例的三张私有数组，不写共享状态。
- 移植侧角色表是 SoA 列存（`CharacterTable`），**没有「实例私有数组」可置 null**，也表达不出
  「已释放但仍在表内」⇒ 校验期零副作用、抛错后角色完好可读。
- **裁定 = 登记不修**：复刻需要给 `CharacterTable` 加逐行 disposed 位 + 读路径口径 + 存档面迁移，
  属角色列表语义面改造；语料里越界批量删除不可达（`GOLDEN_04_CHARA.ERB:86` 只有合法的 `DELCHARA 0`）。
- **成功路径已证等价**：摘除同一组互异元素后余下相对顺序与摘除次序无关，且本函数内无回调、
  中间态不可观测 ⇒ 移植侧「按位置降序 `removeAt`」不需改（`DelCharaOverloadTest` 已钉）。
- **对照复刻项**：数组参越界文案的 `System.Int64[]`（`:1259` 传 `charaNoList.ToString()`）
  **已按 ADR-005 复刻**（编号丢失是官方 bug）；单参版带编号（`:1245`），两条重载文案本就不同。
---

## 14. 2026-10-21 条目名型 ghost 消解：缺口总账 4 → 0（worker w7-specgap，worktree `D:/DSH/eratw-wt-w7-specgap`，分支 wave/w7-specgap）

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

## 15. 2026-10-24 编译层缺陷收口 CMP-76/86/87/88（worker cmp-l14，worktree `D:/DSH/eratw-wt-cmp-l14`，分支 `wave/cmp-l14`）

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
  `300px` 粘连形放行 vs 原版词法必拒的分歧维持 ArgBuilders.kt 的既有 TODO(verify)（语料 0 处，待 PC oracle）。
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
6. **`<button/nonbutton pos='N'>`（LockPointX）未落**（HtmlRunFlatten）：HtmlManager.cs:1531-1537 /
   :659-672 / ConsoleButtonString.cs:157-163；语料真实 pos 仅 1 处（カラム機能.ERB），列位会偏。
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
15. **`<button title>`（tooltip）未落**：移动端无 hover，语料 116 处多为分页提示，观感损失小。
16. **UseNewRandom=true 无告警**（StatementExecutor 三处缺 ParserMediator.Warn 等价文案）：
    Android 端禁用该开关（rng.md §5），RNG 源维持 SFMT。

## 17. 2026-10-26 深审波 core/code 路（`wave/deep-code-bc`）与 main 合流 6 红收口

两段工作：① 深审波 code 路交付（子 agent，worktree `D:/DSH/eratw-wt-deep-code-bc`，
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

该路的 5 个文件改动原样留在 worktree `D:/DSH/eratw-wt-deep-lexer` 里**未提交**（mtime 停在
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

### 22.2 `StepLimitExceeded(20000001) @ EVENTTRAIN#4719 → … → GMF_METHOD_UPDATE`（vivo V2183A，gen 362 存档）——**标定缺口（红 L）；已由 02d7faaa「墙钟为主」收口**

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
