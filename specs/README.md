# specs — 语义规格与对拍证据

`specs/` 是 eraKcore 的语义权威。实现与规格不一致时以规格为准；如果实现证明规格
有误，先改规格、再改实现，顺序不能反。

## 文件

| 文件 | 内容 |
|---|---|
| `isa.yaml` | 指令集：操作码、操作数形态、寄存器 / 变量寻址、语句到字节码的映射 |
| `builtin-functions.yaml` | 内建函数表：名称、参数、返回值、别名与实现类 |
| `variables.yaml` | 变量体系：系统 / 用户 / 角色 / 局部 / 全局 / 引用形参、维度与初始化 |
| `loader.md` | 装载链：文件枚举、重名定义顺序、符号表、`#DIM` / `#FUNCTION` 等声明 |
| `erbc-format.md` | `.erbc` 容器格式：段表、指令编码、字符串驻留、清单与确定性要求 |
| `save-format.md` | 存档格式：头、变量区、扩展区、文本档、兼容迁移 |
| `rng.md` | 随机数：SFMT19937 状态、种子、`RAND` 系列语义 |
| `patch-format.yaml` | 可链接补丁容器：头、模块描述、重定位、外部导入、链接顺序 |
| `error-texts.yaml` | 错误文案表：键、语言列、Emuera 出处与「不改-同形」判词 |
| `corpus-bugs.md` | 语料缺陷台账与内容补丁机制（CB-xxx） |
| `verify-notes.md` | 开发期验证记录（历史日志，不是规范正文） |

## 证据口径

- 每条语义结论都应附 Emuera 反编译源码的 `文件:行号` 出处。
- 规格里引用的产品路径一律使用**仓库根相对路径**；引用其它仓库时写清仓库名。
- 规格变更必须同时更新受影响的测试与 `fixtures/` 夹具；能逐字节对拍的场合优先
  逐字节对拍。

## 与测试的关系

- `fixtures/golden/` 是自写的 golden ERB 套件，`fixtures/replay/` 是回放轨迹，
  `fixtures/` 下的其它数据是各模块的定向夹具。
- 真实语料、真产物、真存档、Android 编译面相关的用例由 `ERATW_*` 环境变量门控；
  未设置时按公开仓口径跳过，详见根 README 的「构建与测试」。
- `tools/erbcc` 的 `compileCorpus` / `symbolAudit` / `gate981Corpus`，以及
  `core-vm` 的 L3 哈希链、E2E golden、装载审计，都是规格的可执行对拍装置。

## 内部工具说明

开发期还使用过 `spec_crosscheck.py`、`miniyaml.py`、`golden-coverage.py`、
`symbols-report/` 等内部对拍工具。它们不随公开仓发布；`verify-notes.md` 中出现的
这些名字属于历史记录，不影响公开仓的构建与测试。
