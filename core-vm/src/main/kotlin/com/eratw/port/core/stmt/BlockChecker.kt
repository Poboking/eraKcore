package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.LangKey

/** 块平衡错误。 */
data class BlockError(val line: Int, val message: String, val code: FunctionCode)

/**
 * 块结构平衡校验 —— 对应原版装载期 `Process` 对 IF/SELECTCASE/FOR/WHILE/DO/REPEAT/
 * PRINTDATA/DATALIST/STRDATA/NOSKIP/TRYCALL…LIST 的配对检查
 * （配对表 spec: FunctionIdentifier.cs:4144-4173；校验时机 spec: Process.cs 内
 * `getMatchFunction` / `getParentFunc` 的调用点）。
 *
 * ## 文案
 * 全部走 [LangKey] 的官方模板，取 **zhs 译文**（使用者面口径，见 `LangKey` 类头第 2 条）。
 * 逐条出处：
 * - CATCH 无 TRYC 系 → `LangKey.MissingTryc`（`ErbLoader.cs:1343`）；
 * - SELECTCASE 前置区的普通指令 → `LangKey.OutsideSelectcase`（`ErbLoader.cs:1100`）；
 * - 文件结束时栈里剩下的开语句 → `LangKey.MissingCorresponding`，实参
 *   `(期望的结束语句, 开语句)`（`ErbLoader.cs:1568` 的 `getMatchFunction` 顺序）；
 * - ELSEIF/ELSE 栈顶不是 IF → `LangKey.InvalidElse`（`ErbLoader.cs:1214`）；
 *   CASE/CASEELSE 栈顶不是 SELECTCASE → `LangKey.OutsideSelectcase`（`:1245`）；
 * - 结束语句无母块 / 与栈顶错配 → 按关闭码分流（`ErbLoader.cs:1229`/`:1272`/`:1353`/`:1332`/
 *   `:1422`/`:1458`/`:1525`/`:1551`）：ENDIF/ENDSELECT/ENDCATCH/ENDLIST 用各自的 Unexpected 键，
 *   ENDDATA/ENDFUNC/ENDNOSKIP 用带 `{0}` 的 Missing 键，REND/NEXT/WEND/LOOP 用
 *   `MissingCorresponding(母语句, 结束语句)`。
 *
 * 用户文案里不再出现 `spec: …` 一类的工程线索（此前 `:44-49`/`:68-70` 把 C# 行号写进了
 * 屏上文案，是工程事故）。
 *
 * ## 判定规则
 * - OPEN 压栈（记录期望的 CLOSE）；
 * - BRANCH 要求栈顶是对应块（ELSEIF/ELSE→IF，CASE/CASEELSE→SELECTCASE）；
 * - CLOSE 要求栈顶是它的母语句（或配对表反查得到）；
 * - 文件结束时栈必须为空。
 *
 * 已知差异（不属本轮文案面，保留登记）：原版 `ErbLoader.cs:1247-1256`/`:1275-1287` 在
 * CASE/ENDSELECT 遇到 SELECTCASE 被中间块挡住时，会**逐个弹栈并逐条**报
 * `LangKey.InstructionNotClosed`；本实现只在错配处报一条，不弹中间块。
 */
class BlockChecker {
    private data class Frame(
        val code: FunctionCode,
        val expectedClose: FunctionCode,
        val line: Int,
        /** SELECTCASE 专用：是否已见到第一个 CASE/CASEELSE（spec: ErbLoader.cs:1098-1102 的 IfCaseList.Count == 0）。 */
        var casesSeen: Boolean = false,
    )

    /** 校验整段语句；返回全部错误（按行号升序）。 */
    fun check(statements: List<Statement>): List<BlockError> {
        val errors = mutableListOf<BlockError>()
        val stack = ArrayDeque<Frame>()
        for (s in statements) {
            // CATCH 双重角色：先关闭 TRY…（funcMatch[TRY*]=CATCH），再开启 CATCH 块（→ENDCATCH）。
            // 栈顶不是 TRY* 系开启语句时，原版装载期报 MissingTryc（isError）且**不把
            // CATCH 压栈**（warn 后直接 break，spec: ErbLoader.cs:1338-1348）——因此后续
            // ENDCATCH 也必然因栈顶不是 CATCH 再报一次 UnexpectedEndcatch（:1351-1356）。
            // 本侧照原样：报错并跳过压栈，让 ENDCATCH 在 checkClose 里自行报不匹配。
            if (s.code == FunctionCode.CATCH) {
                val top = stack.lastOrNull()
                if (top != null && top.expectedClose == FunctionCode.CATCH) {
                    stack.removeLast()
                    // 不 continue：CATCH 同时开启 ENDCATCH 块
                } else {
                    errors.add(BlockError(s.line, LangKey.MissingTryc.render(), s.code))
                    continue
                }
            }
            // SELECTCASE 与第一个 CASE 之间只许 CASE/CASEELSE/ENDSELECT：
            // 原版装载期栈顶是 IfCaseList 为空的 SELECTCASE 时，其余指令行一律
            // OutsideSelectcase（level 2, isError，spec: ErbLoader.cs:1098-1102 warn 后
            // continue —— 该行不再进入压栈/配对 switch）。非指令行（GOTO 标签、#行）
            // 不参与本检查（原版 :1058-1068 对非 InstructionLine 直接 continue），
            // 对应本侧 code == __NULL__ 的元数据/标签语句。
            val top = stack.lastOrNull()
            if (top != null && top.code == FunctionCode.SELECTCASE && !top.casesSeen &&
                s.code != FunctionCode.__NULL__ &&
                s.code != FunctionCode.CASE && s.code != FunctionCode.CASEELSE &&
                s.code != FunctionCode.ENDSELECT
            ) {
                errors.add(
                    BlockError(s.line, LangKey.OutsideSelectcase.render(s.code.name), s.code),
                )
                continue
            }
            if (s.code == FunctionCode.CASE || s.code == FunctionCode.CASEELSE) {
                top?.casesSeen = true
            }
            when (s.role) {
                BlockRole.OPEN -> {
                    val close = s.expectedClose ?: continue
                    stack.addLast(Frame(s.code, close, s.line))
                }
                BlockRole.BRANCH -> checkBranch(s, stack, errors)
                BlockRole.CLOSE -> checkClose(s, stack, errors)
                BlockRole.NONE -> {}
            }
        }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            // 原版 :1568 = MissingCorresponding(getMatchFunction(开语句), 开语句名)。
            errors.add(
                BlockError(
                    f.line,
                    LangKey.MissingCorresponding.render(f.expectedClose.name, f.code.name),
                    f.code,
                ),
            )
        }
        return errors.sortedBy { it.line }
    }

    private fun checkBranch(s: Statement, stack: ArrayDeque<Frame>, errors: MutableList<BlockError>) {
        val top = stack.lastOrNull()
        val ok = when (s.code) {
            FunctionCode.ELSEIF, FunctionCode.ELSE -> top?.code == FunctionCode.IF
            FunctionCode.CASE, FunctionCode.CASEELSE -> top?.code == FunctionCode.SELECTCASE
            else -> true
        }
        if (!ok) {
            // ELSE/ELSEIF 无 IF → InvalidElse（ErbLoader.cs:1214）；
            // CASE/CASEELSE 无 SELECTCASE → OutsideSelectcase（ErbLoader.cs:1245）。
            val key = if (s.code == FunctionCode.ELSEIF || s.code == FunctionCode.ELSE) {
                LangKey.InvalidElse
            } else {
                LangKey.OutsideSelectcase
            }
            errors.add(BlockError(s.line, key.render(s.code.name), s.code))
        }
    }

    private fun checkClose(s: Statement, stack: ArrayDeque<Frame>, errors: MutableList<BlockError>) {
        val top = stack.lastOrNull()
        if (top == null) {
            errors.add(BlockError(s.line, closeMessage(s), s.code))
            return
        }
        // 结束语句必须匹配栈顶期望（spec: FunctionIdentifier.cs:4144-4173）
        if (top.expectedClose == s.code) {
            stack.removeLast()
            return
        }
        // REND/NEXT/WEND/LOOP 的母语句由 funcParent 给出；其余由配对表反查
        val parent = s.closeParent ?: BlockTable.openToClose.entries.firstOrNull { it.value == s.code }?.key
        if (parent != null && top.code == parent) {
            stack.removeLast()
            return
        }
        errors.add(BlockError(s.line, closeMessage(s, top), s.code))
    }

    /**
     * 结束语句的官方文案 —— 按关闭码分流，逐族对照 C# 的 `ErbLoader.cs` 分支与
     * `string.Format` 实参表（行号见类头）。
     *
     * [top] 只用于 REND/NEXT/WEND/LOOP 的反查兜底；原版对这些结束语句**只**看
     * `getParentFunc`（:1329-1333），错配与空栈同一句文案。
     */
    private fun closeMessage(s: Statement, top: Frame? = null): String = when (s.code) {
        FunctionCode.ENDIF -> LangKey.UnexpectedEndif.render()
        FunctionCode.ENDSELECT -> LangKey.UnexpectedEndselect.render()
        FunctionCode.ENDCATCH -> LangKey.UnexpectedEndcatch.render()
        FunctionCode.CATCH -> LangKey.MissingTryc.render()
        FunctionCode.ENDLIST -> LangKey.UnexpectedEndlist.render()
        FunctionCode.ENDDATA -> LangKey.MissingPrintdataStrdata.render(s.code.name)
        FunctionCode.ENDNOSKIP -> LangKey.MissingNoskip.render(s.code.name)
        FunctionCode.ENDFUNC -> LangKey.MissingTrycalllist.render(s.code.name)
        else -> {
            val parent = s.closeParent ?: top?.code
            LangKey.MissingCorresponding.render(parent?.name ?: "", s.code.name)
        }
    }
}
