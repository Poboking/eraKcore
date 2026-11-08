package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ProgramBase
import com.eratw.port.core.code.ProgramOverlay
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.patch.FunctionMetaRecord
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.patch.patchBaseIdentity
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintDataStmt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * R7-C4：mods 补丁装载器（CONTRACT-R7-C §2/C4）。扫描 mods 目录里的补丁产物，
 * 逐个走「[PatchArtifacts.read] → 身份校验（I3）→ [ErbcReader.decode] 补丁体 →
 * [ProgramOverlay.overlay] 叠加」的装载链，产出会话装配（`GameSession`）所需的
 * 全部追加数据。
 *
 * ## 装载链（每个补丁五步）
 *
 * 1. **读文件**：mods 目录里的候选（见下文扫描口径），按文件名序确定性装载；
 * 2. **[PatchArtifacts.read]**：头解析 + 补丁体容器校验（失败即 [PatchFormatException]，
 *    文案自带补丁头内的行号）；
 * 3. **身份校验（I3）**：[PatchArtifacts.checkAgainst] 对 base 的六个计数 + sha256 逐项比对，
 *    任一不符点名字段与期望/实际（[com.eratw.port.core.patch.PatchIdentityMismatchException]）；
 * 4. **解码补丁体**：[ErbcReader.decode] 按补丁头的六个 base-* 字段组装 [ProgramBase]
 *    （补丁的绝对下标窗口），解出带基线的补丁 [Program]；
 * 5. **叠加**：[ProgramOverlay.overlay] 把补丁拼到当前 Program 之后（窗口对齐闸 + I2
 *    线性自检都在那一边，异常双轨都是 RuntimeException）。
 *
 * 每个补丁在叠加前还有一道**重名闸**（R7 §0.1 第 4 条「v1 禁止补丁定义 base 已有同名
 * 非事件函数」的装载期防线）：补丁函数键撞 base 函数键或撞已装载补丁函数键 ⇒ 点名
 * 函数拒绝。**事件键放行**（[Symbols.isEventLabelName] 命中的 9 个事件名）：同名事件
 * 函数是叠加语义——全部执行（spec: CalledFunction.cs:228-243），PatchCompiler 为事件
 * 成员编出合成入口（Program 名 = 事件名）接管 nonEventByKey 的入口位，base 成员链经
 * base 入口原样执行（PatchCompiler 类 KDoc「事件函数成员」节），放行不会静默遮蔽；
 * 非事件覆盖是 override 语义，需要改写 base 调用点，另立项。C1 的 PatchCompiler 在
 * 编译期做同款闸；这里兜住「手工构造产物」与「C1 闸有洞」两类残余面 —— 不拦的话
 * `FunctionTable` 的后装载者语义会**静默遮蔽** base 函数（I6 禁静默错值）。
 *
 * ## I5 全或无（整体回退，禁半装载）
 *
 * 任一补丁在任一步失败 ⇒ **整体回退**到无补丁模式：返回 base 原样（同一实例），
 * 已叠加的补丁一并作废，标签表/数据节等追加数据全部清空，只留失败清单（I7）。
 * 装了一半补丁的 VM 是最坏形态（函数表与指令流对不上号），所以失败后立即停止装载
 * （fail-fast），剩余候选不再尝试。
 *
 * ## 扫描口径
 *
 * - 目录不存在 / 无候选 ⇒ 空 Result（`rolledBack = false`）：行为与今天逐位一致（I1）；
 * - 路径存在但不是目录、或列不出目录 ⇒ 回退 + 失败清单（配置/权限事故不许静默说成
 *   「没有补丁」）；
 * - 候选 = **普通文件** 且扩展名是 [PATCH_EXTENSION]（大小写不敏感）。跳过（不报错）：
 *   隐藏文件（`.` 开头）、子目录、其它扩展名 —— 玩家把 readme 或 `.nomedia` 丢进来
 *   不该报红；源 `.ERB`（C5 导入链存在 mods 目录里的编译输入）天然被扩展名过滤掉。
 * - 装载序 = 文件名序（[compareNames]：ASCII 大小折叠、locale 无关，与 platform
 *   `ModStore.compareNames` 同款口径 —— 那边管源文件导入面，这边管补丁装载面，
 *   两边对同一目录给出同一排序）。
 *
 * ## 多补丁语义（v1）
 *
 * 每个补丁的 I3 都对**原始 base** 校验（sha 只有 base 产物有）。因此 v1 可共存的补丁
 * 必须都按同一份 program.erbc 编译 —— 而两个这种补丁的绝对下标窗口必然重叠，第二个
 * 会在 [ProgramOverlay] 的窗口对齐闸被拒 ⇒ 整体回退。换句话说 v1 的事实语义是
 * 「单补丁生效；多补丁并存要么按累计基线编译（C5 导入链的扩展，格式另议），要么整体
 * 回退并给出可定位的失败清单」。这不是装载器偷懒：在补丁头 v1 格式（15 个固定字段）
 * 下，第二个补丁的窗口起点无处申明。
 *
 * ## 私有 #DIM（R7-E1 载体）
 *
 * [Result.privateDims] 是会话装配期私有 #DIM 注册链（`GameSession` 装配第 5 步的
 * `allPrivateDims`）的追加输入。补丁头的可选段 `private-dims`（`PatchHeader.privateDims`，
 * R7-E1）承载补丁函数内 `#DIM` 声明（`#DIM` 声明编译期抽走、不产指令，`.erbc` 容器
 * 也不承载，所以走头）；本类在装载链里按装载序收集，喂给会话装配。
 * 旧头（15 字段，无该段）读回恒空表 —— 追加零行为差；空表整段省略的写侧纪律保证
 * 无私有声明的补丁产物字节与 v1 的 15 字段形逐字节相同。
 */
object ModPatchLoader {

    /**
     * 补丁产物的扩展名（含点，小写形）。C5 导入链把设备端补丁编译器（C1 的 PatchCompiler）
     * 的产物（[PatchArtifacts.write] 的字节）以这个扩展名落进 mods 目录；本类只认这个
     * 扩展名为补丁候选（判定大小写不敏感）。**C5 落盘必须用同一扩展名**
     * （需组长接线：契约未定，本常量即权威口径）。
     */
    const val PATCH_EXTENSION: String = ".patch"

    /** position 前缀（补丁函数 FuncMeta.position 的 I7 定位形态：`mods/<补丁名>`）。 */
    internal const val PATCH_POSITION_PREFIX: String = "mods/"

    /**
     * 一个已装载补丁的读数（I7）：DevTools 清单的最小条目。
     * 「函数数 / 指令数」即 CONTRACT-R7.md §1.6 I7 要求的两项读数。
     */
    class LoadedPatch(
        /** 补丁文件名（mods 目录里的名字，含扩展名）。 */
        val name: String,
        /** 本补丁新增函数数。 */
        val functionCount: Int,
        /** 本补丁段指令数（不含 base 与先行补丁）。 */
        val patchInstructionCount: Int,
        /** 本补丁新增函数键（大写归一，装载序）—— I7 定位与重名诊断用。 */
        val functionKeys: List<String>,
    )

    /**
     * 装载结果。**契约**：[rolledBack] 为 true 时 [program] 与入参 base 是**同一实例**，
     * 且 [labels] / [dataSections] / [privateDims] / [functionMetas] / [loadedPatches] 全空、
     * [failures] 非空（I5 整体回退的唯一形态）；无补丁时全部空且 `rolledBack = false`（I1）。
     */
    class Result(
        /**
         * 交给会话的 Program：全部成功 = 补丁叠加后的 overlay；回退或无补丁 = base 原样。
         * 回退时与入参 base 同一实例（调用方可用引用相等断言回退事实）。
         */
        val program: Program,
        /** 补丁侧标签表（函数键 → 标签名 → 绝对 pc）。标签 pc 已是绝对口径（补丁窗口基线）。 */
        val labels: Map<String, Map<String, Int>>,
        /** 补丁侧数据节（绝对语句池下标 → 数据节组）。键与 base 侧车同域（绝对下标）。 */
        val dataSections: Map<Int, List<List<Expr>>>,
        /** 补丁侧私有 #DIM 声明（补丁头可选段 private-dims 的抽取,R7-E1;旧头恒空）。 */
        val privateDims: List<BootMetadata.PrivateDim>,
        /**
         * 补丁函数 per-function 元数据（补丁头可选段 function-metas 的抽取,4.981 浮点族;
         * 旧头恒空表）。键 = 补丁窗口内函数序（offset,0 基;绝对下标 = base 函数数 + offset）,
         * 值 = 头里声明的非缺省元数据（`#FUNCTIONF`/`#FUNCTIONS`/`#LOCALSIZE`/`#LOCALSSIZE`/
         * `#LOCALFSIZE`）。消费点 = `GameSession.buildPatchFunctionMetas`（缺记录的函数
         * 按缺省重建,与无段产物逐位一致）。
         */
        val functionMetas: Map<Int, FunctionMetaRecord>,
        /** I7：已装载补丁清单（回退时全空 —— 已装载的也一并作废）。 */
        val loadedPatches: List<LoadedPatch>,
        /** I7：失败清单。条目 = `补丁名: 原因`，原因自带行号/pc/字段名（可定位）。 */
        val failures: List<String>,
        /** I7：非致命告警（不影响装载，但会话侧要能查出来）。 */
        val warnings: List<String>,
        /** I5：true = 任一补丁失败整体回退，补丁全部未生效。 */
        val rolledBack: Boolean,
    )

    /**
     * 装载 mods 目录里的全部补丁并叠加到 [base] 之后。
     *
     * @param modsDirectory mods 目录（纯 JVM 收目录参数；Android 面的 SAF/路径选择归 C5）
     * @param base base 产物（`.erbc` 装载或源码直编）
     * @param baseSha256 base `program.erbc` 的 SHA-256（64 位小写十六进制）。
     *   Program 本身不记哈希（见 [patchBaseIdentity] 的 KDoc），由手里有产物字节的调用方算出传入。
     * @throws IllegalArgumentException [baseSha256] 形态非法（调用方契约错误，不进回退；
     *   补丁层面的失败一律走 [Result.rolledBack]，不抛）
     */
    fun load(
        modsDirectory: Path,
        base: Program,
        baseSha256: String,
    ): Result {
        val baseIdentity = base.patchBaseIdentity(baseSha256)
        val candidates = try {
            scanPatchFiles(modsDirectory)
        } catch (e: IOException) {
            // 目录列不出来是配置/权限事故：整体回退 + 失败清单，不静默说成「没有补丁」。
            return Result(
                program = base,
                labels = emptyMap(),
                dataSections = emptyMap(),
                privateDims = emptyList(),
                functionMetas = emptyMap(),
                loadedPatches = emptyList(),
                failures = listOf("mods: ${e.message ?: e.toString()}"),
                warnings = emptyList(),
                rolledBack = true,
            )
        }
        if (candidates.isEmpty()) {
            // I1：无候选 = 与今天逐位一致（base 原样、零清单）。
            return Result(
                program = base,
                labels = emptyMap(),
                dataSections = emptyMap(),
                privateDims = emptyList(),
                functionMetas = emptyMap(),
                loadedPatches = emptyList(),
                failures = emptyList(),
                warnings = emptyList(),
                rolledBack = false,
            )
        }

        // 重名闸的判定域：base 全部函数键 + 已装载补丁的函数键（跨补丁同名同样静默遮蔽，同拦）。
        // 注意：补丁撞补丁即便不重名也几乎必炸——每张补丁的窗口都锚裸 base，第二张接不上
        // ProgramOverlay 的窗口对齐闸；设备端导入链的正解是「全部留档源并集重编成一张」
        // （ModImportController.UNION_PATCH_NAME），本装载器只兜手工多张面的可定位报错。
        val baseKeys = HashSet<String>(base.functions.size * 2)
        for (f in base.functions) baseKeys.add(f.upperKey)
        val knownKeys = HashSet<String>(baseKeys)

        val loaded = ArrayList<LoadedPatch>(candidates.size)
        val labels = LinkedHashMap<String, Map<String, Int>>()
        val dataSections = LinkedHashMap<Int, List<List<Expr>>>()
        // 私有 #DIM（R7-E1）：补丁头可选段 private-dims 的抽取,按装载序收集。重复声明
        // 不在本层拦（与 base 侧语义一致:第二条在会话装配期被 declareUserVar 拒,
        // 降级成装配失败清单条目,见 GameSession 装配第 5 步的逐条 runCatching）。
        val privateDims = ArrayList<BootMetadata.PrivateDim>()
        // per-function 元数据（4.981 浮点族）：补丁头可选段 function-metas 的抽取,按
        // 装载序合并。offset 在多补丁下不唯一 —— 每张补丁的窗口都锚裸 base、函数表
        // 顺序追加,而能并存的补丁至多一张（第二张过不了窗口对齐闸）,所以按键合并
        // 实际不会撞;撞了按坏产物拒（put 成功与否判,不静默覆盖）。
        val functionMetas = LinkedHashMap<Int, FunctionMetaRecord>()
        val warnings = ArrayList<String>()
        var current = base

        for (candidate in candidates) {
            try {
                val bytes = Files.readAllBytes(candidate)
                val artifact = PatchArtifacts.read(bytes)
                artifact.checkAgainst(baseIdentity)
                val patchProgram = ErbcReader.decode(artifact.bodyBytes(), programBaseOf(artifact.header))
                for (f in patchProgram.functions) {
                    // 重名闸：补丁撞 base 非事件同名拒绝（覆盖需要改写 base 调用点，另立项，
                    // 且后装载者会静默遮蔽 base 函数）；事件键放行——同名事件函数是叠加语义
                    //（全部执行，spec: CalledFunction.cs:228-243），PatchCompiler 为事件成员
                    // 额外编出合成入口（Program 名 = 事件名），装载后 nonEventByKey 的入口位
                    // 被补丁入口接管、base 成员链经 base 入口原样执行（PatchCompiler 类 KDoc
                    //「事件函数成员」节）。放行的前提是补丁来自 PatchCompiler（成员 Program 名
                    // 带 #下标、入口 0 参）；手工构造的裸事件名函数会被当成入口位接管——
                    // 装载器不验编制，容器 v2 带编制后再收紧。补丁撞补丁同样拦：两张设备端
                    // 补丁的窗口都锚裸 base，第二张本就过不了窗口对齐闸，与其让后到的窗口
                    // 报错绕弯，不如在这里点名「多张不并存，请重导入为一张」。
                    if (!knownKeys.add(f.upperKey) && !Symbols.isEventLabelName(f.upperKey)) {
                        throw IllegalArgumentException(
                            if (baseKeys.contains(f.upperKey)) {
                                "补丁函数 ${f.name} 与 base 已有函数重名（v1 禁止补丁定义 base 已有" +
                                    "同名非事件函数，否则后装载者会静默遮蔽 base 函数。事件函数成员是" +
                                    "支持的——同名事件全部执行（spec: CalledFunction.cs:228-243）；" +
                                    "非事件覆盖需要改写 base 调用点，另立项）"
                            } else {
                                "补丁函数 ${f.name} 与更早装载的补丁重名（mods 里多张补丁不是合法形态：" +
                                    "每张补丁的窗口都锚定裸 base 产物，第二张接不上窗口对齐闸；" +
                                    "设备端导入会把全部留档源并集重编成一张——请把口上源重新导入一次）"
                            },
                        )
                    }
                    if (f.paramNames.isEmpty() && f.arity > 0) {
                        // 补丁容器缺形参名（.erbc v1 无段 8）⇒ ARG/ARGS 桶分不出 ⇒ 桶长 0，
                        // 函数体内 ARG:i 读不到实参。不静默：登记告警（I7），装载继续。
                        warnings.add(
                            "${candidate.fileName}: 函数 ${f.name} 的形参名缺失（arity=${f.arity}），" +
                                "ARG/ARGS 桶不绑定（补丁容器需 v2 形参名段）",
                        )
                    }
                }
                val overlay = ProgramOverlay.overlay(current, patchProgram)
                labels.putAll(harvestLabels(patchProgram))
                dataSections.putAll(harvestDataSections(patchProgram))
                privateDims.addAll(artifact.header.privateDims)
                harvestFunctionMetas(artifact.header, patchProgram, candidate, functionMetas)
                loaded.add(
                    LoadedPatch(
                        name = candidate.fileName.toString(),
                        functionCount = patchProgram.functions.size,
                        patchInstructionCount =
                            patchProgram.instructionCount - current.instructionCount,
                        functionKeys = patchProgram.functions.map { it.upperKey },
                    ),
                )
                current = overlay
            } catch (e: Exception) {
                // I5 全或无：任一步失败 ⇒ 整体回退（已叠加的补丁一并作废），fail-fast。
                // 全部拒绝路（PatchFormatException / PatchIdentityMismatchException /
                // ErbcDecodeException / overlay 与重名闸的 IllegalArgumentException）都是
                // RuntimeException；IOException 同样只可能是这份补丁读不出来 —— 一律回退。
                return Result(
                    program = base,
                    labels = emptyMap(),
                    dataSections = emptyMap(),
                    privateDims = emptyList(),
                    functionMetas = emptyMap(),
                    loadedPatches = emptyList(),
                    failures = listOf(
                        "${candidate.fileName}: ${e.message ?: e.toString()}",
                    ),
                    warnings = emptyList(),
                    rolledBack = true,
                )
            }
        }

        return Result(
            program = current,
            labels = labels,
            dataSections = dataSections,
            // 补丁头可选段 private-dims 的抽取（R7-E1）;旧头无该字段 = 空表,追加零行为差。
            privateDims = privateDims,
            // 补丁头可选段 function-metas 的抽取（4.981 浮点族）;旧头无该字段 = 空表,
            // 装载侧按缺省重建,与引入本段前逐位一致。
            functionMetas = functionMetas,
            loadedPatches = loaded,
            failures = emptyList(),
            warnings = warnings,
            rolledBack = false,
        )
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 列出 mods 目录里的补丁候选（绝对路径不承诺，原样返回 [Files.list] 的条目），
     * 按文件名序（[compareNames]）排序。口径见类 KDoc「扫描口径」。
     *
     * @throws IOException 路径存在但不是目录，或列不出目录条目
     */
    private fun scanPatchFiles(modsDirectory: Path): List<Path> {
        if (!Files.exists(modsDirectory)) return emptyList()
        if (!Files.isDirectory(modsDirectory)) {
            throw IOException("mods 路径不是目录：$modsDirectory")
        }
        val out = ArrayList<Path>()
        Files.list(modsDirectory).use { stream ->
            for (p in stream) {
                val name = p.fileName.toString()
                if (name.startsWith(".")) continue
                if (!hasPatchExtension(name)) continue
                if (!Files.isRegularFile(p)) continue
                out.add(p)
            }
        }
        out.sortWith { a, b -> compareNames(a.fileName.toString(), b.fileName.toString()) }
        return out
    }

    /**
     * 扩展名判定：大小写不敏感的 [PATCH_EXTENSION]，且主名非空（`.patch` 本身算隐藏文件，
     * 已被扫描口径拦掉，这里只兜长度下界）。逐字符比较不用 `lowercase()`：locale 无关的
     * 判定才保证「同机同输入同输出」（与 platform `ModStore.hasErbExtension` 同款纪律）。
     */
    fun hasPatchExtension(name: String): Boolean {
        if (name.length <= PATCH_EXTENSION.length) return false
        val start = name.length - PATCH_EXTENSION.length
        for (i in PATCH_EXTENSION.indices) {
            if (foldCase(name[start + i]) != foldCase(PATCH_EXTENSION[i])) return false
        }
        return true
    }

    /**
     * 文件名比较：先按 ASCII 折叠（大小写不敏感）比，再按原文比 —— 全程不碰 locale。
     * 与 platform `ModStore.compareNames` 逐字同口径（同一目录两张面必须同序）。
     */
    private fun compareNames(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val ca = foldCase(a[i])
            val cb = foldCase(b[i])
            if (ca != cb) return ca.compareTo(cb)
        }
        if (a.length != b.length) return a.length - b.length
        return a.compareTo(b)
    }

    /** ASCII 大小写折叠（只折 A-Z，其余原样）。 */
    private fun foldCase(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    // ------------------------------------------------------------------
    // 补丁 Program 的侧车数据抽取
    // ------------------------------------------------------------------

    /**
     * 补丁头的六个 base-* 字段 → [ProgramBase]（[ErbcReader.decode] 的基线参数）。
     * 计数与 [ProgramBase] 字段一一对应（[PatchHeader] 的字段表）；codeBase 的单位是字，
     * 头里的 `base-instructions` 是指令条数，换算乘 [Instruction.WORDS]。
     */
    private fun programBaseOf(h: PatchHeader): ProgramBase = ProgramBase(
        codeBase = h.baseInstructionCount * Instruction.WORDS,
        functionBase = h.baseFunctionCount,
        statementBase = h.baseStatementCount,
        stringBase = h.baseStringCount,
        longBase = h.baseLongCount,
        exprBase = h.baseExprCount,
    )

    /**
     * 抽补丁函数的 `$标签` 表（函数键 → 标签名 → 绝对 pc）。
     *
     * 补丁 [Program] 是带基线的窗口 Program：[com.eratw.port.core.code.FunctionInfo.labelPcs]
     * 已是绝对 pc（[com.eratw.port.core.code.CodeBuilder] 的基线预置让 here/emit 全程绝对），
     * 与 base 侧车的标签表（`GameSession` 装配第 8 步 SidecarTables 的输入）同域可直接合并。
     * 空标签表的函数不进表（与侧车装配期的剪枝同口径）。
     */
    private fun harvestLabels(patchProgram: Program): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, Map<String, Int>>()
        for (f in patchProgram.functions) {
            if (f.labelCount == 0) continue
            val table = LinkedHashMap<String, Int>(f.labelCount * 2)
            for (k in 0 until f.labelCount) {
                table[f.labelNames[k]] = f.labelPcs[k]
            }
            out[f.upperKey] = table
        }
        return out
    }

    /**
     * 抽补丁的 PRINTDATA/STRDATA 数据节（绝对语句池下标 → 数据节组）。
     *
     * 收割口径 = 桌面侧 `CorpusCompile.harvestDataSections`（CONTRACT-R7 §1 侧车段的同源
     * 生产端）：从 [PrintDataStmt] 或 STRDATA 的 [DataStmt] 起、扫到 ENDDATA 止；
     * DATALIST 内每行 DATA/DATAFORM 归一组，裸 DATA/DATAFORM 各成一组。键是数据节语句
     * 自己的**绝对**池下标（语句层消费点拿 marker 的 a 操作数来查，与 base 侧车同域）。
     *
     * 补丁 Program 的语句池是堆内全量池（[ErbcReader.decode] 产物，非 mmap 惰性池），
     * 按下标逐条取不触发物化问题；下标区间 [statementBase, statementCount) 是补丁段
     * （statementCount 是绝对口径）。
     */
    private fun harvestDataSections(patchProgram: Program): Map<Int, List<List<Expr>>> {
        val out = LinkedHashMap<Int, List<List<Expr>>>()
        var i = patchProgram.statementBase
        while (i < patchProgram.statementCount) {
            val s = patchProgram.statement(i)
            val isConsumer = s is PrintDataStmt || (s is DataStmt && s.code == FunctionCode.STRDATA)
            if (!isConsumer) {
                i++
                continue
            }
            val groups = ArrayList<List<Expr>>()
            var j = i + 1
            while (j < patchProgram.statementCount) {
                val t = patchProgram.statement(j)
                if (t is EndBlockStmt && t.code == FunctionCode.ENDDATA) break
                if (t is DataListStmt && t.code == FunctionCode.DATALIST) {
                    val acc = ArrayList<Expr>()
                    j++
                    while (j < patchProgram.statementCount) {
                        val u = patchProgram.statement(j)
                        if (u is DataListStmt && u.code == FunctionCode.ENDLIST) break
                        if (u is DataStmt && (u.code == FunctionCode.DATA || u.code == FunctionCode.DATAFORM)) {
                            acc.add(dataExpr(u))
                        }
                        j++
                    }
                    groups.add(acc)
                } else if (t is DataStmt && (t.code == FunctionCode.DATA || t.code == FunctionCode.DATAFORM)) {
                    groups.add(listOf(dataExpr(t)))
                }
                j++
            }
            out[i] = groups
            i = j + 1
        }
        return out
    }

    /** DATA 行的取值表达式：表达式参数优先，否则字符串字面量，再否则空串（同 `CorpusCompile.dataExpr`）。 */
    private fun dataExpr(d: DataStmt): Expr =
        (d.args as? ExpressionArgument)?.term
            ?: d.args.literalStr?.let { StrLiteral(it) }
            ?: StrLiteral("")

    /**
     * 校验并收割补丁头可选段 `function-metas`（4.981 浮点族 per-function 元数据）。
     *
     * 头里的记录带两把钥匙 —— [com.eratw.port.core.patch.FunctionMetaRecord.offset]
     * （补丁窗口内函数序）与 [com.eratw.port.core.patch.FunctionMetaRecord.name]
     * （补丁 Program 函数名）—— 这里与补丁体**逐条互证**：offset 落在补丁函数表内、
     * 名字与同序位函数逐字相同。对不上 = 头与补丁体对不上号（产物损坏 / 手改头），
     * 抛 [IllegalArgumentException] 走整体回退,不猜（I6 禁静默）；
     * 缺段（旧头）= 空表,消费侧按缺省重建,与引入本段前逐位一致（I1）。
     *
     * 跨补丁 offset 撞键按坏产物拒：能并存的补丁至多一张（第二张过不了窗口对齐闸,
     * 见类 KDoc「多补丁语义」）,所以撞键只可能来自手改产物,不静默覆盖。
     */
    private fun harvestFunctionMetas(
        header: PatchHeader,
        patchProgram: Program,
        candidate: Path,
        out: MutableMap<Int, FunctionMetaRecord>,
    ) {
        for (m in header.functionMetas) {
            val f = patchProgram.functions.getOrNull(m.offset)
            if (f == null) {
                throw IllegalArgumentException(
                    "${candidate.fileName}: function-metas 的 offset 越出补丁函数表：" +
                        "${m.offset}（patch-functions = ${patchProgram.functions.size}，函数 ${m.name}）",
                )
            }
            if (f.name != m.name) {
                throw IllegalArgumentException(
                    "${candidate.fileName}: function-metas 的函数名与补丁体不符：" +
                        "头写 ${m.name}（offset = ${m.offset}），补丁体同序位是 ${f.name}",
                )
            }
            if (out.put(m.offset, m) != null) {
                throw IllegalArgumentException(
                    "${candidate.fileName}: function-metas 的 offset 与更早装载的补丁撞键：" +
                        "${m.offset}（${m.name}）——多张补丁不是合法形态,请把口上源重新导入为一张",
                )
            }
        }
    }
}