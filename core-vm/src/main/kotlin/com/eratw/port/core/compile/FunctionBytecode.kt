package com.eratw.port.core.compile

/**
 * 函数签名（编译单元的身份）。
 *
 * core-vm 不依赖 erbcc（模块方向：erbcc → core-vm），故不直接使用 erbcc 的
 * `LabelDef`；调用方（erbcc / 测试）从 `LabelDef` 构造本类：
 *
 * ```
 * FuncSig(name = label.key, arity = label.argCount, isMethod = label.isMethod)
 * ```
 *
 * [name] 必须是**全局唯一**的键（erbcc 口径 = IgnoreCase 归一后的大写 key）。
 * 事件函数的多个定义按 `<KEY>#<defIndex>` 之类的约定命名以避免互撞，事件名本身
 * 留给 [ErbProgramCompiler.compileEventEntry] 的合成入口。
 */
data class FuncSig(
    val name: String,
    /** 形参数（`@F(A, B$)` → 2）。_CALL 按位置把实参复制到被调帧 R0..R(arity-1)。 */
    val arity: Int = 0,
    /** 是否 #FUNCTION/#FUNCTIONS 方法（有返回值，RETURNF 语义）。 */
    val isMethod: Boolean = false,
    /**
     * `#FUNCTIONS`（str 返回方法）= true；`#FUNCTION`（int）与非方法 = false。
     * 决定 `RETURNF` 编 [_RETS]（字符串通道，合同 M3-A §3 A7）还是 [_RET]（long）。
     * 默认 false = 旧行为（int 通道）；调用方（erbcc 桥/E2E）按符号表
     * `#FUNCTION`/`#FUNCTIONS` 声明传入（LabelDef 已合并二者，类型来源见
     * prescan funcTypes / ExprCalls 桥的 methodTypes）。
     */
    val returnsStr: Boolean = false,
    /**
     * 形参名（`@F(A, B$)` → [A, B$]，与 [arity] 等长；无括号形参的 `@F, A, B` 形态为空）。
     *
     * REF 形参绑定（[Program.functions] 的 `paramNames`）靠它把「实参位」翻译成被调函数的
     * 私有 REF 变量名 —— REF 形参在 `#DIM` 里的声明序**不等于**头部形参序（语料 63/539 例
     * 不一致），不能用声明序顶替（spec: ProcessState.cs:435-452 的 `TopLabel.Arg[i]` 是
     * **头部**形参位）。空 = 调用方没有形参名信息，REF 绑定跳过（不猜）。
     */
    val paramNames: List<String> = emptyList(),
)

/** 待链接的函数引用：`_CALL` 的 a 操作数（函数表下标）在 [ErbProgramCompiler.build] 时回填。 */
data class CallRef(
    /** _CALL 指令的 pc（全局）。 */
    val pc: Int,
    /** 目标函数名（大写键）。 */
    val target: String,
    /** 源码行号（报错定位）。 */
    val line: Int,
)

/**
 * 一个 @函数 的编译产物（编译单元）。
 *
 * 指令流直接发射进 Program 级共享的 [com.eratw.port.core.code.CodeBuilder]
 * （常量池/字符串池/表达式池全局去重，无需跨单元重映射），本类只保留**元数据切片**：
 * 入口/长度/帧大小/标签表/待链接引用。
 *
 * @property entryPc  函数入口的全局 pc
 * @property instructionCount  函数体指令数（[entryPc] .. [endPc)-1）
 * @property slotCount  帧槽数（≥ arity + maxArgc 暂存区 + 临时槽高水位）
 * @property labels  `$标签名`（大写）→ 函数内 pc（供调试器/运行期按名跳转；GOTO 已在
 *   编译期解析为偏移，本表是元数据副本）
 * @property labelIndexes  `$标签名`（大写）→ 标签下标（= [Opcode._JUMP_DYN] 的 a 操作数；
 *   pc 在 Program.functions[index].labelPcs 同下标处）
 * @property callRefs  待链接的 [_CALL] 引用（build 时回填函数表下标）
 */
class FunctionBytecode(
    val sig: FuncSig,
    val index: Int,
    val entryPc: Int,
    val instructionCount: Int,
    val slotCount: Int,
    val labels: Map<String, Int>,
    val labelIndexes: Map<String, Int> = emptyMap(),
    val callRefs: List<CallRef>,
) {
    val endPc: Int get() = entryPc + instructionCount
}
