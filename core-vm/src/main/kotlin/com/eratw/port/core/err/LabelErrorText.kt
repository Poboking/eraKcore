package com.eratw.port.core.err

import com.eratw.port.core.lexer.LangKey

/**
 * 动态跳转的标签名查不到 —— 官方 `Lang.Error.NotDefinedLabelName` 的 zhs 译文（单一真源，ADR-005）。
 *
 * | 键 | 日文原文 | 语料根表行号 | reference 表行号 | 原版抛出点 |
 * |---|---|---|---|---|
 * | NotDefinedLabelName | Lang.cs:2342 | lang/emuera.zhs.xml:513 | Properties.lang.emuera-zhs.xml:540 | FunctionIdentifier.cs:3703（CodeEE 支）、:3668（warn 支，level 2 + isError） |
 *
 * 两张 zhs 表**同文**（逐字核过）⇒ 不受「两表不同文一律不动」的裁定约束。
 * 模板住在 [LangKey.NotDefinedLabelName]，本层只给调用点一个函数名。
 *
 * ## `$` 是文案本体，不是 Kotlin 模板符号
 * 官方两张表都写作 `标签名"${0}"`（日文 `ラベル名"${0}"`）—— `$` 是 ERB 标签的 sigil，
 * 屏上必须出现。Kotlin 里 `$` 紧跟 `{` 会被当成模板表达式 `${0}`（取实参 0），
 * 于是字面量塌成 `标签名"0"`。移植侧曾踩过两次：
 * - `core/code/Vm.kt` 的动态 JUMP 支（本波接 [notDefinedLabelName] 修掉）；
 * - `core/proc/ProcessRuntime.kt:3568` 同形缺陷（非本路文件面 ⇒ 交组长接线）。
 * `core/compile/ErbProgramCompiler.kt:2663` 是写对了的那一路（`\${0}` 形），
 * 其读数由 `compile/StaticTargetNotFoundCompileTest.kt:81` 钉住。
 */

/**
 * 「当前函数中不存在指定的标签名"$[label]"」的**成品文案**（不给 CodeEE 壳，
 * 供位置由调用点自己装配的通道使用，如 `code/Vm.kt` 的 `errorAt(message, pc)`）。
 *
 * 实参按原版喂**名字原文**（`FunctionIdentifier.cs:3703` 传的是未大写化的 `text`），
 * 调用点不许先 `uppercase()` 再传 —— 屏上大小写以玩家写的为准。
 */
internal fun notDefinedLabelNameText(label: String): String =
    LangKey.NotDefinedLabelName.renderZhs(label)

/** [notDefinedLabelNameText] 的 CodeEE 形（抛点自带位置信息时用这条）。 */
internal fun notDefinedLabelName(label: String): CodeEE = CodeEE(notDefinedLabelNameText(label))
