# erbcc scan 全量语料扫描报告（M1 出口）

- 日期：2026-10-17（输入判定按 m1-loader-sem 定稿修订，spec: ErbLoader.cs:256、Process.cs:801-870，specs/loader.md 附录 C）
- 语料：`eratw-画蛇添足版eratw4.980/ERB/`，**3,818 个 .ERB + 153 个 .ERH = 3,971 文件**，物理行 **4,124,266**
- 扫描命令：`erbcc scan --corpus <ERB> --use-index lazyloadingfiles.dat`
- 报告文件：`report.json`（明细+分组）、`files.tsv`（每文件一行）

## 一、总量（all 策略）

| 指标 | 数值 |
|---|---|
| 物理行 | 4,124,266 |
| 逻辑行（{} 合并/去空行/注释后） | 2,968,564 |
| 其中指令/方法行（参数延后语句层 M2） | 2,601,625 |
| 其中赋值/标签/#DIM 行（词法器全路径覆盖） | 366,939 |
| 词法可见 token 数 | 516,386 |
| 异常 | **0** |
| 警告 | **0** |

参考：首轮全行强制 tokenize（不区分语句路由）token 总数 10,026,306，异常 32,413 —— 已全部归类说明（见三）。

## 二、策略对比（仅报告数据；最终判定见下）

| 策略 | 文件数 | 逻辑行 | token | 异常 | 警告 |
|---|---|---|---|---|---|
| **all（磁盘全量，默认/最终编译输入）** | **3,971** | 2,968,564 | 516,386 | **0** | **0** |
| index（仅 lazyloadingfiles.dat） | 2,154 | 2,436,570 | 298,405 | 0 | 0 |
| union（磁盘∪索引） | 3,971 | 2,968,564 | 516,386 | 0 | 0 |
| engine（报告参考：磁盘−索引 = 急切装载近似集） | 1,817 | 531,994 | 217,981 | 0 | 0 |

索引 2,154 条全部存在于磁盘（缺失 0）；磁盘不在索引内 1,817 个。

**erbcc 最终编译输入（定稿）：全量目录扫描的 3,818 个 .ERB（+153 个 .ERH 单独走 ERH 规约），与 lazyloadingfiles.dat 完全解耦，即 all 策略。**
依据（m1-loader-sem 定稿，specs/loader.md 附录 C）：
1. 索引只决定「急切装载 vs 运行期懒装载」：索引内文件被 ErbLoader.cs:256 的 `!LazyLoadingFiles.Contains()` 排除在急切装载之外；
2. 懒文件按 lazyloading.dat 的函数名→文件表在运行期取回并**完整解析**（Process.cs:711），因此口上等 2,154 个索引文件同样进入编译，全量编译判定不变；
3. scan 默认策略定为 all。

## 三、首轮 32,413 个异常的分类（全部可解释）

| 消息 | 数量 | 归类 |
|---|---|---|
| 予期しない文字'[' | 15,063 | `[[置換名]]`：reader 层替换缺失 → **已修**（见四）；修后由 _Rename.csv（2,616 项）替换，如 `[[麟]]`→160 |
| 予期しない文字''' | 7,348 | PRINTL 系原文行/AA 图；及 `LOCAL '= 字符串` 赋值符（readAssignmentOperator 已支持） |
| 予期しない文字'{' | 5,673 | PRINTFORML/DEBUGPRINTFORML 的 FORM 参数内 `{expr}` |
| 予期しない文字'\' | 2,004 | DATAFORM/FORM 内 `\n` 等转义 |
| 予期しない文字'}' | 1,487 | PRINTL 原文 AA 行 |
| 括号不闭合 3 类 | 514+234+48 | COLOREDMAPS 的 `AA:NN = <AA画>` 赋值右侧含 ASCII 括号；原版在赋值两段式于首个顶层 `=` 截断（LogicalLineParser.cs:536-556），右侧交由 SET 参数/表达式层，词法扫描按同口径截断 |
| 予期しない文字'$' | 39 | FORM 字符串内字面 `$` |
| 整数溢出/转换 3 例 | 3 | FORM 字符串内数字字面量 |

以上除第 1 类外全部是**原版语句层语义**：原版 ParseLine（LogicalLineParser.cs:385-570）对指令行只做第一级分派，参数由 ArgumentBuilder 按 PRINT/FORM 规则解析，`{`、`'`、`\`、`$`、数字等在 FORM/原文行内均为合法字面量。扫描器 v2 复刻了该分派（568 个指令/方法关键字提取自 FunctionIdentifier.cs funcDic 注册项 + FunctionMethodCreator.cs 方法表）。

## 四、词法器修改（唯一例外路径 core-vm/src/**/lexer/**）

1. `LogicalLineReader` 增加 `renameDic: Map<String, String>?` 参数，在 readLine() 对物理行做 `[[置換名]]` 替换（{} 续行同享）。
   - fix: 对照 EraStreamReader.cs:106-119（useRename 主分支）与 :152-161（{} 续行 match2 分支）；useRename 开关来源 ErbLoader.cs:370（Config.UseRenameFile && RenameDic != null，本游戏 emuera.config 显式开启且 CSV/_Rename.csv 存在）。
   - core-vm 测试 107 例全绿，无回退。

无其他词法器 bug：所有 LexError 消息与原版 CodeEE 文案一致，且均在原版同路径可复现。

## 五、编码统计

- 3,971 个文件全部 UTF-8（BOM 剥离生效），Shift-JIS 回退路径 0 次触发，解码后含 U+FFFD 的文件 0 个，二进制文件（NUL 探测）0 个。

## 六、TODO(verify)（遗留待 M2+）

1. `#DEFINE` 宏未实现：`[IF xxx]` 恒 disabled。影响面：语料 [IF] 41 行、[IF_DEBUG/IF_NDEBUG] 43 行（PreprocessorState.kt:71 注释）。
2. `;#;` 仅 DebugMode 跳过，Program.DebugMode 默认值未定稿，暂取 false。影响面：45 行。
3. 编码探测：DetectEncoding 的 StreamReader 双重探测未完整复刻，本实现为 UTF-8 严格校验→Shift-JIS；本语料未触发差异。
4. 语句参数层（ArgumentBuilder，PRINTV/PRINTFORM/CALL 等）在 M2 实现；届时指令行参数才进入词法/语法分析。scan 中 2,601,625 个指令行的参数尚未深扫。
5. 全量编译集内的函数标签重复（初步独立统计约 280 个重名 @标签，含同文件重复与大小写变体）：原版以何种规则容忍/覆盖需 M2 对照 ErbLoader 定稿，erbcc 符号表策略随之确定。
6. 索引的 FILETIME 与热重载（DeletedFiles/ChangedFiles）不在 M1 范围。

## 七、复现

```
cd eratw-android
JAVA_HOME=../.tooling/jdk-17.0.20.1+1 ../.tooling/gradle-8.10.2/bin/gradle --no-daemon :tools:erbcc:run --args="scan --corpus '<游戏>/ERB' --use-index '<游戏>/lazyloadingfiles.dat' --out tools/erbcc/scan-report"
```
