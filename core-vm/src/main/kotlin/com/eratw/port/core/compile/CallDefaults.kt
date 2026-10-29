package com.eratw.port.core.compile

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.stmt.ParamDefault

/**
 * 用户函数缺省实参默认值表：**函数键（大写）→ 逐位默认值**（与形参个数等长；
 * null 位 = 该形参没有可注入的默认值；键缺失 = 该函数无默认值表）。
 *
 * 口径 = 原版 `FunctionLabelLine.Def`（spec: FunctionLabelLine.cs:49，`SingleTerm[]`）：
 * 调用点被省略的实参用 `Def[i]` 顶替后再求值（spec: CalledFunction.cs:167），
 * 被调侧照常按位写 `ARG`/`ARGS`（spec: ProcessState.cs:435-452）。
 *
 * **只在编译期用**：编译期把默认值补进实参表，`.erbc` 容器因此不需要新增段/版本位
 * （与 `FuncLabelStmt.paramRefs` 同一条取舍 —— 那份逐位绑定信息也不落盘）。
 * 装载侧（erbcc / 两个 core-vm 装置）从 `@F(A, B = 2)` 头采集后经本表注入
 * [ErbProgramCompiler.callDefaults]。
 */
typealias CallDefaults = Map<String, List<Expr?>>

/**
 * 默认值采集器：把逐函数的 `FuncLabelStmt.paramDefaults`（[ParamDefault] 列表）折成
 * [CallDefaults]，并登记**本遍拿不到常量值**的位（常量名/任意表达式/解析失败）。
 *
 * 为什么要单独统计：本遍只注入字面量常量（`ParamDefault.expr != null`），
 * 其余位保持「省略即不写」的既有行为（spec: ProcessState.cs:437）—— 语料实测
 * 1,936 处默认值里 1,918 处是字面量，18 处是常量名/带尾注的行（`C_YELLOW`、
 * `OBJ_ID_FIRST` 等）；把这些静默当成 0/"" 就是猜，所以只登记、由装载报告带出去。
 *
 * 调用方只应登记**可被 `CALL` 命中的定义**（重名被遮蔽的定义不在调用面内）。
 */
class CallDefaultsCollector {

    private val table = HashMap<String, List<Expr?>>()

    /** 非字面量默认值：`FUNC#位号 原文`（位号 1 基，与人读的形参序一致）。 */
    val unsupported: MutableList<String> = ArrayList()

    /** 已登记的「含可注入默认值」函数数。 */
    val functionsWithDefaults: Int get() = table.size

    /**
     * 登记一个函数头。
     *
     * @param functionKey 可调用名（`CALL` 目标口径；本类统一大写归一）
     * @param paramDefaults 与形参逐位对齐的默认值表（`FuncLabelStmt.paramDefaults`）
     */
    fun add(functionKey: String, paramDefaults: List<ParamDefault?>) {
        if (paramDefaults.isEmpty()) return
        var injectable = false
        val defs = paramDefaults.mapIndexed { i, d ->
            if (d == null) return@mapIndexed null
            val e = d.expr
            if (e == null) {
                unsupported.add("$functionKey#${i + 1} ${d.raw}")
                null
            } else {
                injectable = true
                e
            }
        }
        if (injectable) table[functionKey.uppercase()] = defs
    }

    /** 冻结成 [CallDefaults]（调用后仍可继续 [add]，但编译器持有的是此处返回的快照）。 */
    fun build(): CallDefaults = HashMap(table)
}
