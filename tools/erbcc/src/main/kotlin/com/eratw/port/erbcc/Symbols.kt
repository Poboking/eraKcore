package com.eratw.port.erbcc

/**
 * Symbols.kt —— `com.eratw.port.core.frontend.Symbols` 的**名字转口**（无实现）。
 *
 * 2026-10-31 R7-B/L9：符号表整包（`object Symbols` + 13 个顶层声明 + `nameComparator`）
 * 从 `:tools:erbcc` 下沉到 `:core-vm` 的 `com.eratw.port.core.frontend`，供设备端增量编译
 * 复用同一份前端。本文件**不留实现**，只做名字转口：erbcc 侧所有既有调用点
 * （`CorpusCompile`、`CorpusScan`、`Main`、`com.erbcc.bridge.*` 与全部单测）的
 * `import com.eratw.port.erbcc.XXX` 与 `LabelTable.xxx` 写法零改动。
 *
 * 与 L6（`ErbPreprocessor`）同一模式；区别只在 visibility：`internal` 不跨 Gradle 模块，
 * 故 `nameComparator` 在 core 侧改为 `public`，此处用 `internal` 转发保住 erbcc 的旧可见性。
 */

typealias Symbols = com.eratw.port.core.frontend.Symbols
/** `Symbols` 的**嵌套**枚举：`typealias` 只转类型名，不能用于 `Symbols.NameKind` 这种限定访问，
 *  故单独转一个顶层名字（实测：Kotlin 2.0.20 下 `Symbols.NameKind` 报 Unresolved reference）。 */
typealias NameKind = com.eratw.port.core.frontend.Symbols.NameKind
typealias DimKeyword = com.eratw.port.core.frontend.DimKeyword
typealias DimSpec = com.eratw.port.core.frontend.DimSpec
typealias RawLabel = com.eratw.port.core.frontend.RawLabel
typealias EventBucket = com.eratw.port.core.frontend.EventBucket
typealias SymbolConfig = com.eratw.port.core.frontend.SymbolConfig
typealias ErhSymbols = com.eratw.port.core.frontend.ErhSymbols
typealias LabelDef = com.eratw.port.core.frontend.LabelDef
typealias DuplicateGroup = com.eratw.port.core.frontend.DuplicateGroup
typealias SymbolStats = com.eratw.port.core.frontend.SymbolStats
typealias CollatorCheck = com.eratw.port.core.frontend.CollatorCheck
typealias CollatorOption = com.eratw.port.core.frontend.CollatorOption
typealias LabelTable = com.eratw.port.core.frontend.LabelTable

/** 文件名比较器转发（core 侧 `public`，此处保住 erbcc 侧的 `internal` 口径）。 */
internal fun nameComparator(option: CollatorOption): Comparator<String> =
    com.eratw.port.core.frontend.nameComparator(option)
