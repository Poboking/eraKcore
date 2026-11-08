package com.eratw.port.erbcc

/**
 * 转口（2026-10-31 R7-B/L6）：预处理状态机已下沉到 `:core-vm`
 * （`com.eratw.port.core.frontend.ErbPreprocessor`），让编译器前端能在设备上跑。
 *
 * 本文件**不留实现**，只做名字转口：erbcc 侧所有既有调用点（`CorpusScan`、`Symbols`
 * 的标签预扫、`PreprocessorParityTest`）与 `com.eratw.port.erbcc.LoadWarning` 的
 * 既有 import 一行都不用改，行为逐字节不变（全量语料 `program.erbc` 的 sha256 守着）。
 *
 * 下游若要直接用 core 类型，import `com.eratw.port.core.frontend.*` 即可；不要在这里
 * 重新加实现（两份实现会重新分叉，这正是 CMP-38/39/41 当初统一掉的问题）。
 */
typealias ErbPreprocessor = com.eratw.port.core.frontend.ErbPreprocessor

/** 转口：见 [ErbPreprocessor]。 */
typealias LoadWarning = com.eratw.port.core.frontend.LoadWarning
