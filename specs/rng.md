# ERB 随机数规约（rng.md）

> 依据 emuera-cs 源码提取（Emuera.dll 1.824.0.0，.NET 8）。所有结论附 `文件:行号` 证据，源码根为 `reference/emuera-cs/`。

## ⚠️ 重大发现：引擎未使用 System.Random 作为默认 RNG

引擎**自实现了 SFMT19937（SIMD-oriented Fast Mersenne Twister，周期 2^19937-1）**，类名虽叫 `MTRandom`，但内部字段名为 `sfmt`、状态长度 624、旋转常量 PARITY4=331998852（=0x13C9E684，正是 SFMT19937 的特征校验值）：

- 类定义：`MinorShift.Emuera.Runtime.Utils/MTRandom.cs:5-165`
  - 常量 `MEXP=19937`、`PARITY4=331998852`（MTRandom.cs:7、15）
  - 状态数组 `uint[] sfmt`（624 元素）+ 游标 `idx`（MTRandom.cs:21-23）
- 主求值器持有该实例：`VariableEvaluator.cs:28`（`private MTRandom rand = new MTRandom();`）
- **System.Random 仅作为 eratw 分支新增的可选开关路径**：`VariableEvaluator.cs:30`（`private Random _newRand = new Random();`），受 JSON 配置 `UseNewRandom` 控制（JSONConfigData.cs:10-11）。

## 1. RNG 构造点与种子

- **默认种子 = `Environment.TickCount`**：`MTRandom()` → `this(Environment.TickCount)`（MTRandom.cs:25-28）。非固定种子、非 Random.Shared。
- 显式种子构造：`MTRandom(long seed)` → `init_gen_rand((uint)seed)`（MTRandom.cs:30-33）。
- 种子重设入口 `VariableEvaluator.Randomize(long seed)`（VariableEvaluator.cs:217-220），由 `RANDOMIZE` 指令调用（FunctionIdentifier.cs:1734-1755）：
  - `UseNewRandom=true` 时 RANDOMIZE 仅告警"新算法下被忽略"，不重播种子（FunctionIdentifier.cs:1745-1749）。
- 状态快照进存档：`InitRanddata`/`DumpRanddata` 把 625 元素（624 状态 + idx）与全局数组 `RANDDATA`（= `DataIntegerArray[64]`，VariableEvaluator.cs:117）互拷（VariableEvaluator.cs:222-230），指令入口 INITRAND/DUMPRAND（FunctionIdentifier.cs:1757-1799）。`RANDDATA` 属全局变量，随 global.sav 持久化（见 save-format.md §2.3）。

## 2. .NET 8 System.Random 兼容开关检查

- 存在开关 `UseNewRandom`（JSON 配置文件字段，默认 false——JSONConfigData.cs:11 为自动属性，未在源码中找到默认值赋值语句，C# 布尔默认即 false；设置 UI 见 ConfigDialog.cs:1336-1338）。
- `GetNextRand` 的双路径分发（VariableEvaluator.cs:232-239）：
  ```csharp
  public long GetNextRand(long max)
  {
      if (JSONConfig.Data.UseNewRandom)
          return _newRand.NextInt64(max);   // System.Random（.NET 8 默认 xoshiro256**，时间种子）
      return rand.NextInt64(max);           // SFMT19937
  }
  ```
- `_newRand = new Random()`（VariableEvaluator.cs:30）为无参构造 → .NET 8 下内核随机种子 + xoshiro256**。**源码中未发现 `Random.Shared`、兼容性开关（如 `Random(int, bool)` 类似物）或自定义 Random 子类**——grep `new Random|Random.Shared` 全仓仅命中上述一处。
- 影响范围：开启 `UseNewRandom` 后 `RANDOMIZE/INITHRAND/DUMPRAND` 失效（FunctionIdentifier.cs:1745/1767/1789 全部仅告警），CtrlZ 回退功能明确声明不支持（EmueraConsole.cs:2589-2592）。**eratw 默认关闭该开关时不需要复刻 xoshiro256\*\*。**

## 3. ERB 侧随机函数 → 实现映射

| ERB 语法 | 实现路径 | 证据 |
|---|---|---|
| `RAND(n)` 伪变量 | `RandToken.GetIntValue` → `VEvaluator.GetNextRand(n)`（n≤0 抛错） | VariableData.cs:838-854 |
| `RAND(n)`（CompatiRAND 配置） | `CompatiRandToken`：`GetNextRand(32768) % n`（eramaker 兼容，负数取绝对值，0 返回 0） | VariableData.cs:856-876、3154-3155 |
| `RAND(min,max)` / `RAND(max)` 方法 | `RandMethod`：`GetNextRand(max - min) + min` | FunctionMethodCreator.cs:4027-4073（调用点 4071）、注册 9089 |
| `RANDOMIZE [seed]` | `RANDOMIZE_Instruction` → `VEvaluator.Randomize(seed)`（UseNewRandom 时忽略） | FunctionIdentifier.cs:1734-1755 |
| `INITRAND` / `DUMPRAND` | 从/向 `RANDDATA[625]` 装载/卸出 SFMT 状态 | FunctionIdentifier.cs:1757-1799 |
| `RANDDATA` 数组 | 全局 `long[625]`（624 状态字 + idx），存档可见 | VariableEvaluator.cs:117、VariableCode.cs:89 |
| 底层取值 | `MTRandom.NextInt64(max) = NextUInt64() % max`；`NextUInt64 = (NextUInt32<<32) + NextUInt32`；`NextDouble = NextUInt32() * 2^-32` | MTRandom.cs:35-54 |

## 4. Kotlin 复刻清单（逐值一致所需状态与算法）

### 4.1 必须复刻的状态

1. `UIntArray(624)` 状态数组 + `Int idx` 游标（MTRandom.cs:21-23）。
2. `RANDDATA` 625 元素全局数组随 global.sav 迁移：`SetRand` 前 624 项转 UInt32 装入 sfmt、第 625 项为 idx（MTRandom.cs:56-80）。
3. 初始化：`init_gen_rand(seed)`（MTRandom.cs:92-102）：
   - `sfmt[0]=seed`；`sfmt[i] = 1812433253 * (sfmt[i-1] ^ (sfmt[i-1]>>>30)) + i`（UInt32 溢出乘法）
   - `period_certification()`（MTRandom.cs:104-134，奇偶校验掩码 `{1,0,0,331998852}`）
   - **初始化后 `idx = 624`**（即首次取值先触发一次 gen_rand_all）。

### 4.2 必须复刻的生成算法

- 取值：`NextUInt32()`——`idx>=624` 时调 `gen_rand_all()` 并置 `idx=0`，返回 `sfmt[idx++]`（MTRandom.cs:82-90）。
- `gen_rand_all_19937` 的 SFMT 线性递推（MTRandom.cs:141-164）：四个同时更新的 128-bit 块，含掩码 `0xBFFFFFF6/0xBFFAFFFF/0xDDFECB7F/0xDFFFFFEF`、移位 `<<8/>>24/<<24/>>8/>>11/<<18`，滚动指针 `num2(488)`、`num3(616)`、`num4(620)`。**这是逐值一致的核心，移位必须用无符号移位（Kotlin `ushr`），乘加按 UInt32 模 2^32。**
- 消耗顺序：`NextUInt64` 每次连续消耗两个 UInt32（**高 32 位先取**——MTRandom.cs:48 左操作数先求值，第一个出号进高 32 位；2026-10-26 fixcore 审查更正本文此前「低 32 位先取」的笔误，代码与对拍向量一直正确）；`RAND(n)` 用 `NextUInt64() % n`。
- CompatiRAND 路径固定先取 `GetNextRand(32768)` 再取模（VariableData.cs:874）。

### 4.3 行为细节

- `GetNextRand` 的分发条件是运行时 JSON 配置，Kotlin 侧需同样支持开关（默认关）。
- `RANDOMIZE` 种子被截断为 UInt32（`MTRandom(long seed)` → `init_gen_rand((uint)seed)`，MTRandom.cs:32），负 seed 按补码截断。
- `NextDouble` 为 `(double)NextUInt32() * 2.3283064365386963E-10`（MTRandom.cs:51-54），与 System.Random 不同，若 ERB 用它做概率判断需原样复刻。

## 5. 最终判定

- **默认路径：自实现 SFMT19937**（类名 MTRandom 有误导性），Kotlin 必须完整复刻其状态与递推式才能逐值一致；该算法完全确定性、无平台差异，可复刻性高。
- **System.Random（.NET 8 → xoshiro256**，内核种子）仅在 `UseNewRandom=true` 时启用**，且此模式下 RANDOMIZE/INITHRAND/DUMPRAND 全部失效、行为天然不可复现。源码未发现 .NET 兼容开关或自定义 Random 子类；如 eratw 需要跨平台逐值一致，应在 Android 端禁用该开关（存档格式已将 UseNewRandom 列为 JSON 配置项）。xoshiro256** 复刻"未找到引擎侧实现，需运行验证 .NET 8 运行时行为"。
