package com.eratw.port.core.vars

import com.eratw.port.core.lexer.LangKey

/**
 * 变量下标类运行期报错文案 —— 官方 `Lang.Error` 的 zhs 译文逐字（单一真源，ADR-005）。
 *
 * 2026-11-04（lane errtext）收口：本层此前用的是自造串「变量 X 的第 1 维下标 N 越界
 * （长度 L）」，与原版上屏原文不同形。玩家上报里 0.2.35/0.2.39 的
 * 「变量 ITEMNAME 的第 1 维下标 1000 越界（长度 1000）」「变量 EXPLV 的第 1 维下标 5002
 * 越界（长度 200）」「变量 EXPNAME 的第 1 维下标 666 越界（长度 200）」就是这一族。
 *
 * 下面四条模板在**两份 zhs 表里同文**（语料根 `lang/emuera.zhs.xml` 与
 * `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml`），
 * 所以本组的对齐不受「两表前缀口径之争」影响（口径之争见交付报告，涉及 `FuncPrefix` 族）：
 *
 * | 键 | 日文原文 | 语料根表行号 | reference 表行号 | 原版抛出点 |
 * |---|---|---|---|---|
 * | OoRVarArg | Lang.cs:1638 | emuera.zhs.xml:585 | emuera-zhs.xml:364 | UserDefinedVariableToken.cs:62/66/70 与 :78/82/86、LocalVariableToken.cs:43、ReferenceToken.cs:66/70/74、VariableData.cs:85/177 |
 * | OoRCharaVarArg | Lang.cs:1622 | emuera.zhs.xml:568 | emuera-zhs.xml:360 | CharaVariableToken.cs:60/64/68 |
 * | ZeroDVarHasArg | Lang.cs:1566 | emuera.zhs.xml:774 | emuera-zhs.xml:346 | VariableParser.cs:178 |
 * | EmptyRefVar | Lang.cs:1642 | emuera.zhs.xml:272 | emuera-zhs.xml:365 | ReferenceToken.cs:36/49/62/120、VariableData.cs:2329/2338 |
 * | OoRInstructionArg | Lang.cs:1626 | emuera.zhs.xml:575 | emuera-zhs.xml:361 | UserDefinedVariableToken.cs:95/99、ReferenceToken.cs:83/87、CharaVariableToken.cs:77/81 |
 * | GetSizeDimError | Lang.cs:1630 | emuera.zhs.xml:329 | emuera-zhs.xml:362 | ReferenceToken.cs:40、UserDefinedVariableToken.cs:46 |
 *
 * 2026-11-05（lane errtext4）：后两条是**本波新接**的运行期残留（原来自造句
 * 「变量 X 的区间起点 N 越界（长度 L）」「变量 X 是 R 维，必须指定维度」）；
 * 它们与上面四条一起收进 `core/lexer/LangKey` + 仓内 error-texts 文案表这条通道，
 * 本文件的 [oorInstructionArg] / [getSizeDimError] 直接按 [LangKey] 渲染，不再另存字面量。
 *
 * 照抄纪律（只做按序号原位替换，不 trim、不改引号形态、不修误译）：
 * - 引号是半角 U+0022，括号半角，「第1参数」之间**没有空格**；
 * - 维号实参原版一律喂字面量 `"1"` / `"2"` / `"3"`（原版只枚举到三维），本层按同一序号喂十进制数；
 * - `OoRCharaVarArg` 的中文译文把原版的「第{1}引数」误译成「第{1}个变量」，按 ADR-005 原样保留；
 * - 官方另有 `OoRCharaVar`（语料根表 :567 / reference 表 :308，「角色数组变量…超出数组范围」），
 *   它是**语句实参**（`VariableEvaluator.cs:337/375`）那条，与本层的 `CheckElement` 通道不同键，
 *   已在 `StatementExecutor.kt` 用对的键，不要混用。
 */

/**
 * 非角色变量的逐维下标越界（`Error.OoRVarArg`）；[dimNo] 是 1 基维号，与原版实参同形。
 * 模板住在 [LangKey.OoRVarArg]（本层不再存第二份字面量）。
 */
internal fun oorVarArg(name: String, dimNo: Int, index: Long): CodeEE =
    CodeEE(LangKey.OoRVarArg.renderZhs(name, dimNo, index))

/**
 * 角色变量的角色位/逐维下标越界（`Error.OoRCharaVarArg`）；[argNo] 1 基，角色位算第 1 个。
 * 模板住在 [LangKey.OoRCharaVarArg]。
 */
internal fun oorCharaVarArg(name: String, argNo: Int, index: Long): CodeEE =
    CodeEE(LangKey.OoRCharaVarArg.renderZhs(name, argNo, index))

/**
 * 0 维变量被带了下标（`Error.ZeroDVarHasArg`；原版实参 = 变量名）。
 * 模板住在 [LangKey.ZeroDVarHasArg]。
 */
internal fun zeroDVarHasArg(name: String): CodeEE =
    CodeEE(LangKey.ZeroDVarHasArg.renderZhs(name))

/** REF 变量未绑定目标（`Error.EmptyRefVar`）。模板住在 [LangKey.EmptyRefVar]。 */
internal fun emptyRefVar(name: String): CodeEE =
    CodeEE(LangKey.EmptyRefVar.renderZhs(name))

/**
 * 语句实参给出的**区间端点**越出数组范围（`Error.OoRInstructionArg`，2026-11-05 lane errtext4 收口）。
 *
 * 实参顺序照原版 `string.Format`：`funcName`（语句/函数名原文）、`argNo`（1 基参数号）、
 * `index`（越界的那个端点值）、`varName`（数组变量名）。原版喂法：
 * - `VARSET` 语句 = `VariableEvaluator.cs:275/:303` 的 `("VARSET", 3L, 4L)`（起点第 3 参、终点第 4 参）；
 * - 函数侧 `SUMARRAY`(:4404 2L,3L) / `MATCH`(:4472 3L,4L) / `MAXARRAY`·`MINARRAY`(:4754 2L,3L) /
 *   `FINDELEMENT`·`FINDLASTELEMENT`(:4947 3L,4L) / `INRANGEARRAY`(:5068 4L,5L) / `STRJOIN`(:6002 2L,3L)，
 *   移植侧那一路已在 `fn/VarMethods.kt` 的 `arrayRangeOoR` 接好（非本路文件面）。
 *
 * 退役自造串：「变量 X 的区间起点 N 越界（长度 L）」/「…区间终点…」（防回潮闸见
 * `vars/RuntimeErrorTextWiringTest`）；2026-11-05 lane negrange 又退役一对 ——
 * 语句层负端点闸「VARSET命令的区间起点(N)为负值」/「…终点(N)为负值」
 * （`sem/StatementExecutor.kt` 的 `execVarSetFlat` / `execVarSet` 双路口，官方即本键，
 * 判界未动）。
 */
internal fun oorInstructionArg(
    funcName: String,
    argNo: Int,
    index: Long,
    varName: String,
): CodeEE = CodeEE(LangKey.OoRInstructionArg.renderZhs(funcName, argNo, index, varName))

/**
 * 对多维数组变量取「整体长度」而未指定维度（`Error.GetSizeDimError`，2026-11-05 lane errtext4 收口）。
 *
 * 实参顺序与日文模板同为「维数在前、变量名在后」（`ReferenceToken.cs:40`、
 * `UserDefinedVariableToken.cs:46` 都喂 `Dimension.ToString()` 与 `varName`）。
 * 角色变量的同形态是**另外的键**（`GetSizeCharaVarWithoutDim` / `GetSize0DCharaVar`，
 * `CharaVariableToken.cs:40`/`:38`），本层不给角色变量用。
 * 退役自造串：「变量 X 是 R 维，必须指定维度」。
 */
internal fun getSizeDimError(dimCount: Int, name: String): CodeEE =
    CodeEE(LangKey.GetSizeDimError.renderZhs(dimCount, name))

/**
 * `VARSIZE X, N` 的 N 超出变量维数（`Error.GetSizeNonExistDim`，2026-11-05 lane negrange 收口）。
 *
 * 实参 = 变量名（`ReferenceToken.cs:55`、`UserDefinedVariableToken.cs:55`、
 * `LocalVariableToken.cs:36`、`VariableData.cs:165/:265/:382/:695/:759` 都喂 `varName`）。
 * 与 [getSizeDimError] 是**不同键**（那是「多维变量不带维号取整体长度」），不许混用。
 * 退役自造串：「变量 X 不存在第 N 维（维度 R）」（REF 支；float/CSV 支见交付回执）。
 */
internal fun getSizeNonExistDim(name: String): CodeEE =
    CodeEE(LangKey.GetSizeNonExistDim.renderZhs(name))

/**
 * 角色变量取「整体长度」而未指定维度（`Error.GetSizeCharaVarWithoutDim`，元素维数 > 1 一支）。
 * spec: CharaVariableToken.cs:40；与 [getSizeDimError] 是**不同键**（角色族单独一条），
 * 中文形带「角色」二字 ⇒ 不许混用（`reference` 表 :358 / 语料根表 :328 同文）。
 */
internal fun getSizeCharaVarWithoutDim(dimCount: Int, name: String): CodeEE =
    CodeEE(LangKey.GetSizeCharaVarWithoutDim.renderZhs(dimCount, name))

/**
 * 0 维角色变量取整体长度（`Error.GetSize0DCharaVar`）。
 * spec: CharaVariableToken.cs:36-38（`sizes.Length == 0` 一支；reference 表 :357 / 语料根表 :325 同文）。
 */
internal fun getSize0DCharaVar(name: String): CodeEE =
    CodeEE(LangKey.GetSize0DCharaVar.renderZhs(name))
