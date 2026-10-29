package com.eratw.port.uipreview

import com.eratw.port.core.proc.FunctionExitKind
import com.eratw.port.core.proc.FunctionProbe
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.StatementProbe
import com.eratw.port.core.stmt.FunctionCode

/**
 * 开发期流程探针（仅 `ERATW_UIPV_TRACE=1` 时装）：把 `@USERCOM` 子树里的函数进出与
 * `@USERCOM` 自身的 RETURN/DOTRAIN 语句打到 stderr。
 *
 * 用途：排查「点了指令菜单但页面不变」——判断是 USERCOM 某个 `RETURN 0` 提前退出，
 * 还是 `DOTRAIN` 之后状态机没接住。生产/常规验收不设该环境变量即零开销（probe == null）。
 */
object FlowTrace {

    fun install(session: GameSession) {
        val depth = intArrayOf(0)
        var userComDepth = -1
        fun log(msg: String) = System.err.println("[TRACE] $msg")

        session.runtime.functionProbe = object : FunctionProbe {
            override fun onFunctionEnter(meta: FuncMeta?) {
                val name = meta?.key ?: "?"
                if (userComDepth >= 0 || name == "USERCOM") {
                    log("${"  ".repeat(depth[0].coerceAtMost(24))}ENTER $name")
                }
                if (name == "USERCOM" && userComDepth < 0) userComDepth = depth[0]
                depth[0]++
            }

            override fun onFunctionExit(meta: FuncMeta?, outcome: FunctionExitKind) {
                depth[0]--
                if (userComDepth >= 0 && depth[0] == userComDepth) userComDepth = -1
                if (userComDepth >= 0) {
                    log("${"  ".repeat(depth[0].coerceAtMost(24))}EXIT ${meta?.key}")
                }
            }
        }

        session.runtime.statementProbe = StatementProbe { op, _, env, outcome, error, _ ->
            if (userComDepth < 0 || env.functionKey != "USERCOM") return@StatementProbe
            // 波次 C 起探针只拿得到 op（FunctionCode.ordinal）与池下标，不再传语句实例。
            if (op !in FunctionCode.entries.indices) return@StatementProbe
            when (FunctionCode.entries[op]) {
                FunctionCode.RETURN, FunctionCode.RETURNF, FunctionCode.RETURNFORM, FunctionCode.DOTRAIN ->
                    log("STMT USERCOM:${env.line} ${FunctionCode.entries[op]} outcome=$outcome err=${error?.message}")
                else -> Unit
            }
        }
    }
}
