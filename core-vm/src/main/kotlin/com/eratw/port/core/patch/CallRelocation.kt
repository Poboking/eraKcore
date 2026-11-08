package com.eratw.port.core.patch

import com.eratw.port.core.code.SymbolCatalog

/**
 * `_CALL.a` 操作数的链接期决议（M2-1 Linker 的指令重定位核心，ABI-4/7）。
 *
 * 一个函数体内的调用点只有四种来源，判序**不可换**（两个 super 哨兵也是负数）：
 *
 * | `a` | 含义 | 链接产物 |
 * |---|---|---|
 * | [CallSentinels.CALL_SUPER_SENTINEL] | 调用「本函数的前一同名实现」 | 前实现物理下标，且 `_CALL.b` **必须置 DIRECT 位**（绕过 dispatch，防二次跳转成环）；无前实现 = 链接错误 |
 * | [CallSentinels.TRY_CALL_SUPER_SENTINEL] | 同上但可缺 | 有前实现同 CALL_SUPER；没有 = 整条语句折 NOP（**不写任何返回槽**，同官方 TRYCALL 缺目标语义） |
 * | 其余负值 | 外部导入哨兵 `-(extIdx+1)` | 该 Package `externalCalls[extIdx]` 的 LSC 胜者物理下标 |
 * | `>= 0` | 模块内下标 | 净偏移重定位后的绝对物理下标（运行期照常走 dispatch） |
 *
 * **净偏移公式**（§3）：「目标全局起始下标 − 模块编译期自带 base」。零基线模块 base=0。
 * 本函数只做算术与查表，不碰 `Program`、不写指令字；真正改写由链接器在拿到
 * [Outcome] 后执行（`a` 替换、SuperCall 的 `b |= DIRECT_CALL_FLAG`、NoSuperNop 的语句折叠）。
 *
 * **不得二次分派**：名字解析通道返回的已是执行入口；本表只服务静态 `_CALL`。
 * dispatch 幂等不变量由 [SymbolCatalog.build] 保证，本函数不重复校验。
 *
 * @throws RelocationException 哨兵越域 / super 缺失（非 TRY）/ 配置错误。
 */
object CallRelocation {

    /** 决议结果（调用方据此改写指令字）。 */
    sealed interface Outcome {
        /** 模块内调用：绝对物理下标（编译产物基线下标 0 起 + 净偏移）。 */
        data class Internal(val absoluteIndex: Int) : Outcome

        /** 外部导入：LSC 胜者物理下标（与导入包的链接位置无关）。 */
        data class External(val absoluteIndex: Int) : Outcome

        /** CALL_SUPER / TRY_CALL_SUPER 命中：绝对物理下标 + 必须置 DIRECT 位。 */
        data class SuperCall(val absoluteIndex: Int) : Outcome

        /** TRYCALL_SUPER 无前实现：整条语句折 NOP，不写任何返回槽。 */
        data object NoSuperNop : Outcome
    }

    /**
     * 决议一个 `_CALL.a`。
     *
     * @param a 指令的 `a` 操作数（编译产物形态，未回填）。
     * @param containingFunction **绝对物理下标**（本调用点所在函数），super 决议的对象。
     * @param netOffset 目标全局起始下标 − 模块编译期自带 base。
     * @param imports 本 Package 的外部导入表（[ExternalImportTable.importsOf] 的产物）。
     * @param catalog 全部模块 + base 的最终 LSC。
     * @param where 诊断定位前缀（函数名 + PC 等，可选）。
     */
    fun resolve(
        a: Int,
        containingFunction: Int,
        netOffset: Int,
        imports: ExternalImportTable.PackageImports?,
        catalog: SymbolCatalog,
        where: String = "",
    ): Outcome {
        val at = if (where.isEmpty()) "" else "（$where）"
        if (a == CallSentinels.CALL_SUPER_SENTINEL) {
            val superIndex = catalog.superIndexOf(containingFunction)
            if (superIndex < 0) {
                throw RelocationException(
                    "CALL_SUPER 没有前一同名实现（函数物理下标 $containingFunction）$at",
                )
            }
            return Outcome.SuperCall(superIndex)
        }
        if (a == CallSentinels.TRY_CALL_SUPER_SENTINEL) {
            val superIndex = catalog.superIndexOf(containingFunction)
            return if (superIndex < 0) Outcome.NoSuperNop else Outcome.SuperCall(superIndex)
        }
        if (CallSentinels.isExternal(a)) {
            val extIdx = CallSentinels.externalIndexOf(a)
            val table = imports
                ?: throw RelocationException("外部导入哨兵 $a 出现在没有导入表的 Package 里$at")
            if (extIdx !in table.imports.indices) {
                throw RelocationException(
                    "外部导入槽越域：extIdx=$extIdx，externalCalls 共 ${table.imports.size} 项$at",
                )
            }
            return Outcome.External(table.imports[extIdx].physicalIndex)
        }
        // 判序走到这里时 a 必 >= 0：所有负值要么是 super 哨兵、要么是外部导入哨兵
        // （CallSentinels.isExternal 的定义），不存在第三类负值。
        val absolute = a.toLong() + netOffset.toLong()
        if (absolute !in 0..Int.MAX_VALUE.toLong()) {
            throw RelocationException("净偏移重定位后下标越界：$a + $netOffset = $absolute$at")
        }
        return Outcome.Internal(absolute.toInt())
    }
}

/** `_CALL.a` 无法决议（super 缺失 / 导入越域 / 负值非法）——链接期整组拒绝。 */
class RelocationException(message: String) : RuntimeException(message)
