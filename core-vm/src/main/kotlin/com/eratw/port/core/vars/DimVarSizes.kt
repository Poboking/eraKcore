package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.VariableSizeLoader
import java.nio.file.Path

/**
 * `#DIM` 装载期折叠用的 **VARSIZE 尺寸快照**（大写变量名 → 各维长度）。
 *
 * ## 为什么是「快照」而不是活表
 *
 * 原版 `VARSIZE(name)` 折叠读的是 `GlobalStatic.IdentifierDictionary` 里那条变量
 * token 的静态尺寸，不是运行期可变状态：
 * - 注册与折叠：`FunctionMethodCreator.cs:3437-3438`（`CanRestructure`/`HasUniqueRestructure`）、
 *   `:3441-3454`（`GetIntValue` 走 `GetVariableToken(...).GetLength(num)`）、
 *   `:3456-3473`（`UniqueRestructure`：变量名查不到或 `IsReference` 就返回 false，即**不折叠**）。
 * - 名字解析口径：`IdentifierDictionary.cs:429`（`GetVariableToken(key, subKey, allowPrivate)`）。
 * - 取维口径：`VariableToken.cs:202-210`（0 维 → `GetLength` 抛 CodeEE）、
 *   `VariableData.cs:67-74`（1 维：`dimension==0` 才返回 `array.Length`，更深维抛）、
 *   `:159-166`（2 维：0/1 维有效）、`:259-266`（3 维：0/1/2 维有效）、
 *   `:827-830`（伪变量的 `GetLength(int)` 一律抛 CodeEE）；
 *   角色变量 `CharaVariableToken.cs:43-55`（`sizes` 不含角色位置，越界抛）；
 *   用户自定义变量 `UserDefinedVariableToken.cs:49-56`（同上，越界抛）。
 * - **装载时点**：`Process.cs:292-293` 先建 `ConstantData` 并 `LoadData(csvDir,…)`
 *   （其中 `VariableSize.CSV` 改尺寸，spec: ConstantData.cs:309-551），`:295` 挂到
 *   `GlobalStatic.ConstantData`，`:300-301` 才建 `IdentifierDictionary`，
 *   `:309/:313` 才开始装 ERH。所以 ERH/ERB 里任何 `VARSIZE(...)` 看到的都是
 *   **CSV 改写之后**的尺寸表 —— 本快照必须同样先吃 `VariableSize.csv`，
 *   否则 `CSV/VariableSize.csv:99 PALAM,200` / `:104 RELATION,200` 会被折成出厂值 100。
 *
 * ## 快照取自哪个数据结构
 *
 * [ofRegistry] 读 [VariableRegistry.all] 的 `VarDef.dims`（元素维，不含角色位置，
 * 口径见 [VarDef.dimension] 的 KDoc = `VariableToken.cs:74-87`），装载时点即
 * [VariableRegistry] 构造完成那一行（它的 `dims` 全部来自传入的 [VariableSizes]）。
 * 用户自定义全局 `#DIM` 不在系统表里（`VariableRegistry.kt:54` 明确「用户 #DIM 变量不在这里注册」），
 * 由 [withUserDecls] 单独并进来，REF 变量按 `:3466-3469` 的方向**不入册**。
 *
 * ## 与运行期变量池的口径差（如实登记）
 *
 * 运行期的 `store` 由 `core/data/csv/GameDataLoader.kt` 总装：它除了
 * `VariableSizeLoader.load` 还会 `autoGrowByCsvExtent`（按 CSV 实际行数抬
 * `maxDataList`，spec: ConstantData.cs:672-706）。本快照只跑 `VariableSizeLoader.load`
 * （与 `loadVariableSizeData` 同一条 `changeVariableSizeData` switch），
 * 因此 29 个 CSV 名字表（`ABLNAME`/`ITEMNAME`…）的 `VARSIZE` 可能比运行期池**小**；
 * 角色/全局主数组（PALAM、RELATION、FLAG…）不受影响，两侧同源同值。
 * `TODO(verify)`：语料 `#DIM` 行没有对 CSV 名字表调 `VARSIZE` 的用例（M4-L `l-dimtriage`
 * 的 12 条缺口只用 `PALAM`/`RELATION`），故未把 `autoGrowByCsvExtent` 拉进本快照——
 * 真要口径一致，应改为直接读 `store` 那份 registry（需组长在 boot 侧调整装载顺序）。
 *
 * 纯 JVM：无静态可变全局，快照一律由调用点构造后作为参数注入（AGENTS 铁律 4）。
 */
object DimVarSizes {

    /**
     * 系统变量尺寸快照（大写名 → 各维长度）。
     *
     * 0 维与伪变量的 `dims` 为空数组，一并入册：原版对它们调 `GetLength` 直接抛
     * CodeEE（spec: VariableToken.cs:202-210、VariableData.cs:827-830），折叠面
     * `DimFunctionFolders` 取 `dims.getOrNull(n)` 得 null ⇒ 同样保持失败口径，
     * 不会把「原版会报错」的形态折成静默成功。
     */
    fun ofRegistry(registry: VariableRegistry): Map<String, LongArray> {
        val out = LinkedHashMap<String, LongArray>(256)
        for (def in registry.all()) {
            val dims = def.dims
            out[def.name.uppercase()] = if (dims.isEmpty()) EMPTY else LongArray(dims.size) { dims[it].toLong() }
        }
        return out
    }

    /**
     * 按装载时点构造系统尺寸快照：`VariableSize.csv`（[VariableSizeLoader]，
     * 目录为 null 或文件缺失时按出厂 [VariableSizes] —— 与 [ofRegistry] 的默认构造同值）。
     *
     * @param csvDir `CSV` 目录（原版 `Program.CsvDir`，spec: Process.cs:293）；null = 不装载 CSV。
     * @param warnings 收集 `VariableSize.csv` 的告警行（调用方决定打印还是丢弃）。
     */
    fun systemSnapshot(csvDir: Path?, warnings: MutableList<String> = ArrayList()): Map<String, LongArray> {
        val sizes = VariableSizes()
        if (csvDir != null) VariableSizeLoader.load(csvDir, sizes, warnings)
        return ofRegistry(VariableRegistry(sizes))
    }

    /**
     * 把装载期已解析成功的**全局**用户 `#DIM` 并入快照
     * （对应原版 ERH 边装边往 `IdentifierDictionary` 注册，`IdentifierDictionary.cs:429`
     * 之后就能被 `VARSIZE` 查到）。
     *
     * REF 变量跳过（spec: FunctionMethodCreator.cs:3466-3469 `IsReference` ⇒ 不折叠）；
     * 0 维标量以空数组入册（`GetLength` 抛 ⇒ 折不了，口径同上）。
     * 返回新 Map，不修改 [base]。
     */
    fun withUserDecls(base: Map<String, LongArray>, decls: Iterable<DimSpec>): Map<String, LongArray> {
        val out = LinkedHashMap(base)
        for (spec in decls) {
            if (spec.isRef) continue
            val dims = spec.dims
            out[spec.name.uppercase()] =
                if (dims.isEmpty()) EMPTY else LongArray(dims.size) { dims[it].toLong() }
        }
        return out
    }

    /** 0 维变量的共用空表（避免每个条目各 new 一个）。 */
    private val EMPTY = LongArray(0)
}
