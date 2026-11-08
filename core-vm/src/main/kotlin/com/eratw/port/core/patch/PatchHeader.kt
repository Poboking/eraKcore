package com.eratw.port.core.patch

import com.eratw.port.core.loader.BootMetadata
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 补丁产物格式错误（既不是 `CodeEE`，也与 `.erbc` 容器 / `.meta` 侧车的错误分开）。
 *
 * 分工：[com.eratw.port.core.code.ErbcDecodeException] 管 `.erbc` 容器本身，
 * `com.eratw.port.core.loader.BootMetaFormatException` 管 `.meta` 侧车，
 * 本类管 `patch` 文件（文本头 + 内嵌 `.erbc`）的头解析、补丁体校验与身份绑定。
 *
 * **全或无**（不变量 I5）：本类的每个抛出点都在产物对象构造**之前**，
 * 调用方拿不到「头解析了一半」或「体只读了一截」的半成品。
 */
class PatchFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 单项身份不符：补丁头声明的 base 读数（[expected]）与实际 base（[actual]）对不上。
 *
 * [field] 与 [PatchHeader.FIELD_NAMES] 里的字段名逐字相同 —— 报错文案、DevTools 清单、
 * L5 的 `specs/patch-format.yaml` 三处靠它对齐，改名字要三处一起改。
 *
 * [expected] / [actual] 是**原值**（十进制整数 / 64 位十六进制串），不带引号：
 * 身份字段的值域里没有空白，加引号只会让程序化比对（DevTools 的差异表）多一步去引号。
 */
data class PatchIdentityMismatch(
    val field: String,
    /** 补丁头声明值（期望 base 就是它）。 */
    val expected: String,
    /** 实际 base 的读数。 */
    val actual: String,
)

/**
 * 补丁与 base 身份不符（不变量 I3）：装载期拒绝装载，提示用当前 base 重编补丁。
 *
 * 为什么不 fail-fast：一次报全 7 个数 + sha 的差异，玩家/组长一眼能看出是「base 换了版本」
 * 还是「拿错了补丁」。逐项比对、逐项点名（期望 vs 实际），不只说「不匹配」。
 */
class PatchIdentityMismatchException(
    mismatches: List<PatchIdentityMismatch>,
) : RuntimeException(buildIdentityMessage(mismatches)) {

    /** 全部不符项，按头字段顺序；构造时拷贝，外部改不了。 */
    val mismatches: List<PatchIdentityMismatch> = mismatches.toList()

    init {
        require(this.mismatches.isNotEmpty()) { "PatchIdentityMismatchException 至少要有一项不符" }
    }
}

private fun buildIdentityMessage(mismatches: List<PatchIdentityMismatch>): String =
    "补丁与 base 身份不符（拒绝装载，请用当前 base 重编补丁）：" +
        mismatches.joinToString(separator = "") {
            "\n  - ${it.field}：期望 ${it.expected}，实为 ${it.actual}"
        }

/**
 * base 的身份读数（不变量 I3 的比对对象）。
 *
 * **刻意不是 [com.eratw.port.core.code.Program]**：单测要能造「假 base」逐项翻错，
 * 而装载侧只读 [com.eratw.port.core.code.Program] 的只读计数（见
 * [com.eratw.port.core.patch.patchBaseIdentity]）。接口把「取数」与「比对」解耦，
 * 比对逻辑因此可以被穷举验证，不需要先造出真 Program。
 */
interface PatchBaseIdentity {
    /** base 的 `instructionCount`。 */
    val instructionCount: Int

    /** base 的函数表条数（`Program.functions.size`）。 */
    val functionCount: Int

    /** base 的语句池条数。 */
    val statementCount: Int

    /** base 的字符串池条数。 */
    val stringCount: Int

    /** base 的常量池条数（`Program.constantCount`，即 `ConstantPool.longCount`）。 */
    val longCount: Int

    /** base 的表达式池条数。 */
    val exprCount: Int

    /** base `program.erbc` 的 SHA-256（64 位小写十六进制）。 */
    val sha256: String
}

/** [PatchBaseIdentity] 的普通实现（单测造假 base、装载侧装配都用它）。 */
data class PatchBaseSnapshot(
    override val instructionCount: Int,
    override val functionCount: Int,
    override val statementCount: Int,
    override val stringCount: Int,
    override val longCount: Int,
    override val exprCount: Int,
    override val sha256: String,
) : PatchBaseIdentity

/**
 * 补丁头(v1):15 个固定字段 + 可选段 `private-dims` + 结束标记,固定字段的名字与
 * 顺序**逐字**取自 `CONTRACT-R7-B` §2/L3 的字段表(L5 的 `specs/patch-format.yaml`
 * 按同一张表写,两边不许各写一套)。
 *
 * ## 产物形态
 *
 * ```
 * patch 文件 = 文本头（UTF-8、LF、逐行 "key value"）+ 一行 "---" + 原始 .erbc 字节
 * ```
 *
 * | # | 字段 | 类型 | 含义 |
 * |---|---|---|---|
 * | 1 | `erbc-patch` | 整数 | 补丁格式版本，v1 = 1 / v2 = 2（写侧恒写 2，读侧两者都认） |
 * | 2 | `base-instructions` | 整数 | base 的 `instructionCount` |
 * | 3 | `base-functions` | 整数 | base 的函数表条数 |
 * | 4 | `base-statements` | 整数 | base 的语句池条数 |
 * | 5 | `base-strings` | 整数 | base 的字符串池条数 |
 * | 6 | `base-longs` | 整数 | base 的常量池条数 |
 * | 7 | `base-exprs` | 整数 | base 的表达式池条数 |
 * | 8 | `base-sha256` | 引号串 | base `program.erbc` 的 SHA-256（小写十六进制） |
 * | 9 | `compiler` | 引号串 | 编译器摘要（`Version.NAME + " " + Version.VERSION`） |
 * | 10 | `patch-functions` | 整数 | 本补丁新增函数数 |
 * | 11 | `patch-statements` | 整数 | 本补丁新增语句数 |
 * | 12 | `patch-strings` | 整数 | 本补丁新增字符串数 |
 * | 13 | `patch-longs` | 整数 | 本补丁新增常量数 |
 * | 14 | `patch-exprs` | 整数 | 本补丁新增表达式节点数 |
 * | 15 | `entry-function` | 引号串 | 补丁入口函数名（大写归一） |
 * | 16 | `---` | — | 头结束标记，其后是 `.erbc` 原始字节 |
 * | 可选 | `private-dims` | 引号串 | 补丁私有 `#DIM` 声明（R7-E1；空表**整段省略**，见下） |
 * | 可选 | `function-metas` | 引号串 | 补丁函数 per-function 元数据（4.981 浮点族；空表**整段省略**，见下） |
 *
 * ## 可选段 `private-dims`（R7-E1,兼容方向）
 *
 * 补丁函数内的 `#DIM` 声明编译期抽走、不产指令，`.erbc` 容器也不承载 —— v1 的 15 个
 * 固定字段因此装不下补丁私有的 `#DIM`，会话装配第 5 步（`GameSession` 的
 * `allPrivateDims`）对补丁侧恒空。本段把 `PatchResult.privateDims` 固化进头：
 *
 * - **空表整段省略**：无私有声明的补丁头与 15 字段形**逐字节相同**（波 0 侧车可选段的
 *   同款纪律 —— 「不含本数据的模型,文本与引入本段之前逐字节相同」）。旧产物读回、
 *   新写空表产物,两个方向的字节都不变（I1 零行为差）。
 * - **读侧按字段名取值、未知名跳过**：头是私有格式,值跟字段名走不存在按下标错配的
 *   静默错值面;跳过未知字段让「未来再加可选段」时旧引擎读新头不会当场炸（与波 0
 *   侧车可选段的省略-兼容同一方向）。对「本次改动之前编译出来的读侧」（如果存在部署
 *   面）,遇本段报「未知字段」明确拒绝 —— 是**可见的失败**,不是静默错值（I6）。
 * - **旧头（无该字段）读回恒空**：`privateDims == emptyList()`,消费侧追加零行为差。
 *
 * 载荷编码见 [PrivateDimsCodec]。
 *
 * ## 可选段 `function-metas`（4.981 浮点族，兼容方向与 private-dims 同款）
 *
 * 补丁函数的 `#FUNCTIONF` / `#FUNCTIONS` / `#LOCALSIZE` / `#LOCALSSIZE` / `#LOCALFSIZE`
 * 声明编译期落在 `FuncMeta` 上，`.erbc` 容器的函数记录不承载（base 走 `.meta` 侧车，
 * 补丁没有侧车）—— 不带本段时装载侧按缺省重建（`GameSession.buildPatchFunctionMetas`），
 * 这些声明**静默退缺省**：`#FUNCTIONF` 的返回走整型通道恒 0、`#FUNCTIONS` 恒空串、
 * `#LOCALFSIZE` 的浮点帧长退默认 10（静默错值家族，I6）。本段把
 * [PatchCompiler] 算好的非缺省元数据固化进头：
 *
 * - **空表整段省略**：全部函数六项都缺省（纯 `@label` 语料包的常态）时头文本与无段形
 *   **逐字节相同**；旧产物（无该段）读回恒空表 = 按缺省重建，与今天逐位一致（I1）。
 * - **读侧按字段名取值、未知名跳过**：与 `private-dims` 同一条裁定（R7-E1）——未来再追加
 *   可选段时旧引擎读新头不炸；对旧读侧是「未知字段」明确跳过，不是静默错值。
 * - **只记非缺省函数**：记录按补丁窗口内函数序（offset）稀疏寻址，装载侧按
 *   `offset` 对齐补丁 Program 的函数表、按 `name` 原文互证（对不上 = 产物损坏，
 *   装载期拒绝，不猜）。
 *
 * 载荷编码见 [FunctionMetasCodec]。
 *
 * ## 读写的规范形（为什么读侧比写侧严）
 *
 * 写侧产出**唯一规范形**：字段序固定、整数十进制无前导零、引号串按 JSON 风格转义
 * （`"` `\` `\n` `\r` `\t`，其余 C0 走 `\uXXXX`）、`entry-function` 大写归一、LF 换行。
 * 读侧只接受这个规范形：
 * - `entry-function` 出现小写 ⇒ 拒（不替调用方猜「他是不是想写大写」）；
 * - 整数出现前导零 / 加号 / 负零（`007`、`+5`、`-0`）⇒ 拒（写侧恒写 `toString()`）；
 * - 引号串里出现**未转义**的 C0 ⇒ 拒（写侧从不产出）。
 *
 * 字段值前后的空白是**容忍**的（切行只按第一个空格/制表符分界，载荷再 trim）：那是排版
 * 差异，不是语义差异，为它报错只会让手写补丁的人困惑。除此之外读侧不做任何「宽容解释」。
 *
 * 「缺一即拒」保留：15 个必需字段读完 `---` 时缺任何一个都拒绝（点名缺哪些）。
 * 「多一即拒」按裁定放宽为「未知字段跳过」（见上节）；重复字段仍然拒绝 —— 同名两行
 * 两个值,取哪个都是猜（I6 禁猜）。
 *
 * 判据是「写 → 读 → 写**逐字节相同**」（不变量 I8）：只要求字段相等是不够的 ——
 * 那会让「读侧宽松、写侧规范」的差异一直藏着，直到有人拿产物做哈希比对才炸。
 *
 * ## 版本策略
 *
 * 写侧只产出 [PATCH_FORMAT_VERSION_2]；读侧接受 v1 与 v2（装机后 `mods/` 里的 `.patch`
 * 跨 app 版本存活，旧产物必须读得进），遇更高版本直接拒绝（不猜、不降级），遇更低版本
 * 按「非法版本」拒绝（v1 之前没有版本，也就没有可兼容的字段集）。
 * v1 → v2 的唯一变化在可选段 `private-dims` 的**记录内**：4.981 的 `#DIMF`（`isFloat`）
 * 与浮点初值（`defaultsFloat`）分别插在 `isPrivate` 之后、追加在记录尾 —— 夹带**必需**位
 * 属不兼容改动，按本节末那句裁定必须升版本号。留在 v1 不升的错形态是「补丁里的浮点
 * 私有声明静默退回整型列」（读回恒 0）；升了版本号，旧引擎读到是「拒 → 整体回退无补丁」，
 * 响的错比静默错值安全。
 * 尾部**可选段**的追加不需要升版本（空表省略 + 按名取值 + 未知跳过,两个方向都不炸,
 * 见「可选段」节）—— 这是 R7-E1 的裁定（CONTRACT-R7-D §4/E1）。
 */
class PatchHeader(
    val baseInstructionCount: Int,
    val baseFunctionCount: Int,
    val baseStatementCount: Int,
    val baseStringCount: Int,
    val baseLongCount: Int,
    val baseExprCount: Int,
    val baseSha256: String,
    val compiler: String,
    val patchFunctionCount: Int,
    val patchStatementCount: Int,
    val patchStringCount: Int,
    val patchLongCount: Int,
    val patchExprCount: Int,
    entryFunction: String,
    /**
     * 补丁私有 `#DIM` 声明（可选段 `private-dims` 的载荷,见类 KDoc「可选段」）。
     * 缺省空 = 旧头形态（恒空零行为差）;非空时编码进头,空表整段省略。
     */
    val privateDims: List<BootMetadata.PrivateDim> = emptyList(),
    /**
     * 补丁函数 per-function 元数据（可选段 `function-metas` 的载荷,见类 KDoc「可选段」）。
     * 只装**非缺省**函数（六项全缺省的不进表）;缺省空 = 旧头形态（装载侧按缺省重建,
     * 与引入本段前逐位一致）。
     */
    val functionMetas: List<FunctionMetaRecord> = emptyList(),
    val formatVersion: Int = PATCH_FORMAT_VERSION_2,
) {
    /**
     * 补丁入口函数名，**大写归一**（装载期按大写键查函数表）。
     *
     * 写侧归一、读侧拒非归一形态：见类 KDoc 的「读写的规范形」。
     */
    val entryFunction: String = entryFunction.uppercase()

    init {
        require(formatVersion == PATCH_FORMAT_VERSION_1 || formatVersion == PATCH_FORMAT_VERSION_2) {
            "写侧只产出补丁格式 v$PATCH_FORMAT_VERSION_1（旧记录形）或 v$PATCH_FORMAT_VERSION_2，收到 $formatVersion"
        }
        // v1 的 private-dims 记录里没有 isFloat / defaultsFloat 两个原子：带浮点声明的头
        // 写 v1 = 浮点位静默丢掉（读回来按整数声明注册,运行期恒 0）。判在构造期（I5 前移）。
        if (formatVersion < PATCH_FORMAT_VERSION_2) {
            require(privateDims.none { it.spec.isFloat || it.spec.defaultsFloat != null }) {
                "补丁格式 v$formatVersion 的 private-dims 记录装不下浮点声明" +
                    "（isFloat / defaultsFloat 自 v$PATCH_FORMAT_VERSION_2 起才有）"
            }
        }
        require(baseInstructionCount >= 0) { "base-instructions 不能为负：$baseInstructionCount" }
        require(baseFunctionCount >= 0) { "base-functions 不能为负：$baseFunctionCount" }
        require(baseStatementCount >= 0) { "base-statements 不能为负：$baseStatementCount" }
        require(baseStringCount >= 0) { "base-strings 不能为负：$baseStringCount" }
        require(baseLongCount >= 0) { "base-longs 不能为负：$baseLongCount" }
        require(baseExprCount >= 0) { "base-exprs 不能为负：$baseExprCount" }
        require(patchFunctionCount >= 0) { "patch-functions 不能为负：$patchFunctionCount" }
        require(patchStatementCount >= 0) { "patch-statements 不能为负：$patchStatementCount" }
        require(patchStringCount >= 0) { "patch-strings 不能为负：$patchStringCount" }
        require(patchLongCount >= 0) { "patch-longs 不能为负：$patchLongCount" }
        require(patchExprCount >= 0) { "patch-exprs 不能为负：$patchExprCount" }
        require(isLowerHexSha256(baseSha256)) {
            "base-sha256 必须是 ${SHA256_HEX_LENGTH} 位小写十六进制：" + describe(baseSha256)
        }
        require(this.entryFunction.isNotEmpty()) { "entry-function 不能为空" }
        // private-dims 写侧闸（I5 前移）：坏声明在构造期拒绝,不进产物。函数键大写卡与
        // entry-function 同款纪律 —— 注册链（GameSession 装配第 5 步）按函数键落私有变量,
        // 大小写不一致 = 声明落错函数域,运行期「未定义变量」是静默错形态,所以拒而不是归一。
        val seenPrivateDim = HashSet<String>(privateDims.size * 2)
        for (p in privateDims) {
            require(p.functionKey.isNotEmpty()) { "private-dims 的函数键不能为空" }
            require(p.functionKey == p.functionKey.uppercase()) {
                "private-dims 的函数键必须大写归一：" + describe(p.functionKey)
            }
            require(p.spec.name.isNotEmpty()) {
                "private-dims 的变量名不能为空：${p.functionKey}"
            }
            for (d in p.spec.dims) {
                require(d >= 0) {
                    "private-dims 的维度不能为负：${p.functionKey}/${p.spec.name} = $d"
                }
            }
            require(seenPrivateDim.add("${p.functionKey}\u0000${p.spec.name}")) {
                "private-dims 声明重复：${p.functionKey}/${p.spec.name}"
            }
        }
        // function-metas 写侧闸（I5 前移）：坏记录在构造期拒绝,不进产物。offset 是补丁
        // 窗口内的函数序（0 基）,必须落在 [0, patchFunctionCount) —— 越窗即「头与补丁体
        // 对不上号」,装载期按损坏拒（写侧先拦,不留到读侧）。三个尺寸域与 FuncMeta 的
        // 16 位域同源（构造期会再兜一遍,这里提前给字段名定位）;returnsFloat 只允许出现
        // 在方法上（与侧车 function 行同一道闸,spec: BootMetadata 的 flags 位 2 判据）。
        val seenMetaOffset = HashSet<Int>(functionMetas.size * 2)
        for (m in functionMetas) {
            require(m.offset in 0 until patchFunctionCount) {
                "function-metas 的 offset 越出补丁函数窗口：${m.offset}（patch-functions = $patchFunctionCount）"
            }
            require(m.name.isNotEmpty()) { "function-metas 的函数名不能为空（offset = ${m.offset}）" }
            require(m.floatLocalSize in 0..0xFFFF) {
                "function-metas 的 floatLocalSize 越 16 位域：${m.floatLocalSize}（offset = ${m.offset}）"
            }
            require(m.localSize in 0..0xFFFF) {
                "function-metas 的 localSize 越 16 位域：${m.localSize}（offset = ${m.offset}）"
            }
            require(m.localsSize in 0..0xFFFF) {
                "function-metas 的 localsSize 越 16 位域：${m.localsSize}（offset = ${m.offset}）"
            }
            require(!m.returnsFloat || m.isMethod) {
                "function-metas 的 returnsFloat 只允许出现在方法上（#FUNCTIONF）：${m.name}"
            }
            require(seenMetaOffset.add(m.offset)) {
                "function-metas 的 offset 重复：${m.offset}（${m.name}）"
            }
        }
    }

    /**
     * 身份校验（不变量 I3）：逐项比对 6 个 base 计数 + sha256。
     *
     * 全对才返回；任一不符抛 [PatchIdentityMismatchException]，`mismatches` 按头字段顺序
     * 列出**每一项**差异（期望 vs 实际）。装载侧接住后整体回退到无补丁模式（I5）。
     */
    fun checkAgainst(base: PatchBaseIdentity) {
        val out = ArrayList<PatchIdentityMismatch>(7)
        fun count(
            field: String,
            expected: Int,
            actual: Int,
        ) {
            if (expected != actual) {
                out.add(PatchIdentityMismatch(field, expected.toString(), actual.toString()))
            }
        }
        count(FIELD_BASE_INSTRUCTIONS, baseInstructionCount, base.instructionCount)
        count(FIELD_BASE_FUNCTIONS, baseFunctionCount, base.functionCount)
        count(FIELD_BASE_STATEMENTS, baseStatementCount, base.statementCount)
        count(FIELD_BASE_STRINGS, baseStringCount, base.stringCount)
        count(FIELD_BASE_LONGS, baseLongCount, base.longCount)
        count(FIELD_BASE_EXPRS, baseExprCount, base.exprCount)
        if (baseSha256 != base.sha256) {
            out.add(PatchIdentityMismatch(FIELD_BASE_SHA256, baseSha256, base.sha256))
        }
        if (out.isNotEmpty()) throw PatchIdentityMismatchException(out)
    }

    /** 规范形文本（15 行字段 + 一行 `---`，UTF-8 编码前的字符形态）。 */
    internal fun encodeText(): String = PatchHeaderCodec.encode(this)

    companion object {
        /**
         * 补丁头的字段顺序（权威清单）。
         *
         * 与契约字段表、L5 的 `specs/patch-format.yaml` **逐字段一致**；单测
         * `头字段顺序与契约字段表逐字一致` 把这张表钉死，改顺序必须三处一起改。
         */
        val FIELD_NAMES: List<String> = listOf(
            FIELD_FORMAT,
            FIELD_BASE_INSTRUCTIONS,
            FIELD_BASE_FUNCTIONS,
            FIELD_BASE_STATEMENTS,
            FIELD_BASE_STRINGS,
            FIELD_BASE_LONGS,
            FIELD_BASE_EXPRS,
            FIELD_BASE_SHA256,
            FIELD_COMPILER,
            FIELD_PATCH_FUNCTIONS,
            FIELD_PATCH_STATEMENTS,
            FIELD_PATCH_STRINGS,
            FIELD_PATCH_LONGS,
            FIELD_PATCH_EXPRS,
            FIELD_ENTRY_FUNCTION,
        )
    }
}

/** 补丁格式 v1（历史产物：private-dims 记录无浮点原子;读侧仍接受，写侧只在无私有浮点声明时可用）。 */
const val PATCH_FORMAT_VERSION_1: Int = 1

/**
 * 补丁格式 v2（**写侧唯一产出**）：`private-dims` 记录在 `isPrivate` 之后插 `isFloat`、
 * 行尾追加 `defaultsFloat`，把 4.981 的 `#DIMF` 浮点私有声明与浮点初值带进产物。
 *
 * 15 个必需字段与 `---` 结束标记逐字节不变，只有可选段的记录形变了（版本门控在
 * [PrivateDimsCodec]）—— 见 [PatchHeader] 类 KDoc「版本策略」。
 */
const val PATCH_FORMAT_VERSION_2: Int = 2

/** 头结束标记：单独一行，其后是 `.erbc` 原始字节。 */
const val HEADER_END_MARKER: String = "---"

/**
 * 可选段 `private-dims` 的字段名（R7-E1）。
 *
 * 不进 [PatchHeader.FIELD_NAMES]（那张表是 15 个**必需**字段的权威清单,与契约字段表、
 * L5 的 specs/patch-format.yaml 三处对齐,本段是尾部可选段,见类 KDoc「可选段」）;
 * 读侧按本名识别、写侧在非空时追加。
 */
internal const val FIELD_PRIVATE_DIMS: String = "private-dims"

/**
 * 可选段 `function-metas` 的字段名（4.981 浮点族 per-function 元数据）。
 *
 * 不进 [PatchHeader.FIELD_NAMES]（必需字段权威清单,与契约字段表、specs 三处对齐）;
 * 读侧按本名识别、写侧在非空时追加 —— 与 `private-dims` 同一条「尾部可选段」裁定。
 */
internal const val FIELD_FUNCTION_METAS: String = "function-metas"

/** `base-sha256` 的十六进制位数（SHA-256 = 32 字节）。 */
internal const val SHA256_HEX_LENGTH: Int = 64

private const val FIELD_FORMAT = "erbc-patch"
private const val FIELD_BASE_INSTRUCTIONS = "base-instructions"
private const val FIELD_BASE_FUNCTIONS = "base-functions"
private const val FIELD_BASE_STATEMENTS = "base-statements"
private const val FIELD_BASE_STRINGS = "base-strings"
private const val FIELD_BASE_LONGS = "base-longs"
private const val FIELD_BASE_EXPRS = "base-exprs"
private const val FIELD_BASE_SHA256 = "base-sha256"
private const val FIELD_COMPILER = "compiler"
private const val FIELD_PATCH_FUNCTIONS = "patch-functions"
private const val FIELD_PATCH_STATEMENTS = "patch-statements"
private const val FIELD_PATCH_STRINGS = "patch-strings"
private const val FIELD_PATCH_LONGS = "patch-longs"
private const val FIELD_PATCH_EXPRS = "patch-exprs"
private const val FIELD_ENTRY_FUNCTION = "entry-function"

/** LF / CR 的字节值（`'\n'.code.toByte()` 不是编译期常量，故用 val 而非 const）。 */
private val LF: Byte = '\n'.code.toByte()
private val CR: Byte = '\r'.code.toByte()

/** 64 位小写十六进制（`base-sha256` 的唯一合法形态）。 */
internal fun isLowerHexSha256(s: String): Boolean {
    if (s.length != SHA256_HEX_LENGTH) return false
    for (c in s) {
        if (c !in '0'..'9' && c !in 'a'..'f') return false
    }
    return true
}

/** 错误消息里的值摘录：引号串 + 截断（畸形输入可能有几 KB，不许整段进消息）。 */
internal fun describe(s: String): String {
    val clipped = if (s.length <= 96) s else s.substring(0, 96) + "…"
    return PatchHeaderCodec.quote(clipped)
}

/**
 * 补丁头的编解码（按字段名取值、未知字段跳过、重复与缺一当场拒绝）。
 *
 * 风格照抄 `core/loader/BootMetadata.kt` 的 `BootMetaCodec`：文本记号 + JSON 风格引号串
 * （`"` `\` `\n` `\r` `\t` 与 C0 走 `\uXXXX`）、**先判界再取值**、重复字段当场拒绝、
 * 错误消息一律带行号。与那边的差异有两处：
 * - 那边是 14 个变长段（记录流），这边是 15 个**必需**字段 + 至多两个尾部可选段
 *   （`private-dims` / `function-metas`,见 [FIELD_PRIVATE_DIMS] / [FIELD_FUNCTION_METAS]）
 *   + 一个结束标记；
 * - 字段定位：这里按**字段名**取值（R7-E1 裁定）,未知名跳过 —— 值跟名走,不存在按下标
 *   错配的静默错位;15 个必需字段在 `---` 前缺任何一个都拒绝（点名缺哪些）。
 */
internal object PatchHeaderCodec {

    /** 解析结果：头 + 补丁体在文件里的起始下标。 */
    class Parsed(
        val header: PatchHeader,
        /** `---` 那一行的下一个字节（补丁体首字节）在文件里的下标。 */
        val bodyOffset: Int,
    )

    /**
     * 字段名 → 下标。已知字段集 = 15 必需字段 + 尾部可选段 `private-dims` / `function-metas`
     * —— 可选段必须在集合里,否则 `parse` 会把它当未知字段跳过、读回恒空（正是可选段要
     * 消灭的「旧头形态」）。可选段的下标值无消费方（`build` 按名取值,不按下标）。
     */
    private val FIELD_INDEX: Map<String, Int> =
        (PatchHeader.FIELD_NAMES + FIELD_PRIVATE_DIMS + FIELD_FUNCTION_METAS)
            .withIndex().associate { (i, name) -> name to i }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 规范形文本：15 行 `字段 值` + 一行 `---`，行尾恒为 LF。
     *
     * 确定性：字段序只来自 [PatchHeader.FIELD_NAMES]，值只来自 [PatchHeader] 的只读属性
     * ⇒ 同一个头两次编码逐字节相同（不变量「同一份模型两次 write 逐字节相同」）。
     */
    fun encode(h: PatchHeader): String {
        val values = linkedMapOf(
            FIELD_FORMAT to h.formatVersion.toString(),
            FIELD_BASE_INSTRUCTIONS to h.baseInstructionCount.toString(),
            FIELD_BASE_FUNCTIONS to h.baseFunctionCount.toString(),
            FIELD_BASE_STATEMENTS to h.baseStatementCount.toString(),
            FIELD_BASE_STRINGS to h.baseStringCount.toString(),
            FIELD_BASE_LONGS to h.baseLongCount.toString(),
            FIELD_BASE_EXPRS to h.baseExprCount.toString(),
            FIELD_BASE_SHA256 to quote(h.baseSha256),
            FIELD_COMPILER to quote(h.compiler),
            FIELD_PATCH_FUNCTIONS to h.patchFunctionCount.toString(),
            FIELD_PATCH_STATEMENTS to h.patchStatementCount.toString(),
            FIELD_PATCH_STRINGS to h.patchStringCount.toString(),
            FIELD_PATCH_LONGS to h.patchLongCount.toString(),
            FIELD_PATCH_EXPRS to h.patchExprCount.toString(),
            FIELD_ENTRY_FUNCTION to quote(h.entryFunction),
        )
        // 取值表与字段表必须同源同长：少一项 = 写出残头（读侧必拒），多一项 = 写出未知字段。
        check(values.size == PatchHeader.FIELD_NAMES.size) {
            "补丁头取值表(${values.size})与字段表(${PatchHeader.FIELD_NAMES.size})不一致"
        }
        val sb = StringBuilder(384)
        for (name in PatchHeader.FIELD_NAMES) {
            val v = values[name] ?: error("补丁头字段 $name 没有取值")
            sb.append(name).append(' ').append(v).append('\n')
        }
        // 可选段：空表整段省略（头文本与 15 字段形逐字节相同）;非空才追加一行。
        if (h.privateDims.isNotEmpty()) {
            sb.append(FIELD_PRIVATE_DIMS).append(' ')
                .append(quote(PrivateDimsCodec.encode(h.privateDims, h.formatVersion))).append('\n')
        }
        if (h.functionMetas.isNotEmpty()) {
            sb.append(FIELD_FUNCTION_METAS).append(' ')
                .append(quote(FunctionMetasCodec.encode(h.functionMetas))).append('\n')
        }
        sb.append(HEADER_END_MARKER).append('\n')
        return sb.toString()
    }

    /**
     * 引号串（UTF-8 原文 + JSON 风格转义），与 `BootMetaCodec.quote` **逐字同口径**。
     *
     * 除 `"` `\` 与 `\n` `\r` `\t` 外，所有 C0 控制字符都转成 `\uXXXX` —— 一条记录永远
     * 落在一行里（文本按 LF 切行，字符串里的原始换行会破坏记录边界，进而把补丁体读错位）。
     */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 解析补丁头（不碰补丁体）。
     *
     * 头部的行必须全部是合法 UTF-8：二进制只许出现在 `---` 之后，所以这里对每一行做
     * **严格解码**（畸形字节直接拒，不用替换字符静默吞掉）。
     */
    fun parse(bytes: ByteArray): Parsed {
        val cur = LineCursor(bytes)
        val values = HashMap<String, String>(PatchHeader.FIELD_NAMES.size * 2)
        val lineOf = HashMap<String, Int>(PatchHeader.FIELD_NAMES.size * 2)

        while (true) {
            val text = cur.next() ?: throw PatchFormatException(
                "补丁头不完整：读到文件尾也没遇到结束标记 $HEADER_END_MARKER" +
                    "（头逐行 \"key value\"，必需 ${PatchHeader.FIELD_NAMES.size} 项字段缺一即拒；" +
                    "已读 ${values.size} 项）",
            )
            val lineNo = cur.lineNo
            val sp = separatorIndex(text)
            val key = if (sp < 0) text.trim() else text.substring(0, sp)
            val value = if (sp < 0) "" else text.substring(sp + 1)

            if (key.isEmpty()) {
                throw PatchFormatException(
                    "第 $lineNo 行：空行（补丁头逐行 \"key value\"，空行不许出现在字段区）",
                )
            }
            if (key == HEADER_END_MARKER) {
                if (value.isNotBlank()) {
                    throw PatchFormatException(
                        "第 $lineNo 行：$HEADER_END_MARKER 行带载荷 ${describe(value)}" +
                            "（结束标记必须单独成行，其后才是 .erbc 字节）",
                    )
                }
                break
            }
            if (key !in FIELD_INDEX) {
                // 未知字段：跳过（R7-E1 裁定,见 PatchHeader 类 KDoc「可选段」的兼容方向）。
                // 值跟字段名走,未知行不会错配到任何必需字段;重复字段（可识别的）仍然拒。
                continue
            }
            if (values.put(key, value) != null) {
                throw PatchFormatException(
                    "第 $lineNo 行：字段重复 ${describe(key)}（首次出现在第 ${lineOf[key]} 行）",
                )
            }
            lineOf[key] = lineNo
        }

        // 字段区结束：15 个必需字段缺一即拒（点名缺哪些;--- 行号一起给,可定位）。
        val missing = PatchHeader.FIELD_NAMES.filter { it !in values }
        if (missing.isNotEmpty()) {
            throw PatchFormatException(
                "第 ${cur.lineNo} 行：$HEADER_END_MARKER 提前出现，缺少 " +
                    missing.joinToString("、") + "（必需 ${PatchHeader.FIELD_NAMES.size} 项字段，" +
                    "实读 ${values.size} 项）",
            )
        }

        return Parsed(build(values, lineOf), cur.pos)
    }

    /** 原文表 → [PatchHeader]（逐项判界、判类型，错误消息带行号）。 */
    private fun build(
        values: Map<String, String>,
        lineOf: Map<String, Int>,
    ): PatchHeader {
        fun raw(name: String): String =
            values.getValue(name)

        fun lineNo(name: String): Int =
            lineOf.getValue(name)

        fun intOf(name: String): Int {
            val text = raw(name).trim()
            val v = text.toIntOrNull()
                ?: throw PatchFormatException("第 ${lineNo(name)} 行：$name 不是整数：${describe(text)}")
            // 负数是「写出侧不可能产出的形态」（它们最终都是数组长度/计数）：当场拒绝。
            if (v < 0) throw PatchFormatException("第 ${lineNo(name)} 行：$name 不能为负：$v")
            // 规范十进制：写侧恒写 `v.toString()`，`+5` / `007` / `-0` 都只可能来自手改或坏档。
            if (text != v.toString()) {
                throw PatchFormatException(
                    "第 ${lineNo(name)} 行：$name 不是规范十进制整数（不许前导零/加号/负零）：${describe(text)}",
                )
            }
            return v
        }

        fun strOf(name: String): String = parseQuoted(raw(name), lineNo(name), name)

        val versionLine = lineNo(FIELD_FORMAT)
        val version = intOf(FIELD_FORMAT)
        if (version > PATCH_FORMAT_VERSION_2) {
            throw PatchFormatException(
                "第 $versionLine 行：补丁格式版本 $version 高于本引擎支持的 v$PATCH_FORMAT_VERSION_2" +
                    "（不猜、不降级解释：请升级引擎，或用当前引擎重编补丁）",
            )
        }
        if (version != PATCH_FORMAT_VERSION_1 && version != PATCH_FORMAT_VERSION_2) {
            throw PatchFormatException(
                "第 $versionLine 行：补丁格式版本 $version 非法" +
                    "（合法值只有 v$PATCH_FORMAT_VERSION_1 与 v$PATCH_FORMAT_VERSION_2，" +
                    "v$PATCH_FORMAT_VERSION_1 之前没有版本，也就没有可兼容的字段集）",
            )
        }

        val sha = strOf(FIELD_BASE_SHA256)
        if (!isLowerHexSha256(sha)) {
            throw PatchFormatException(
                "第 ${lineNo(FIELD_BASE_SHA256)} 行：$FIELD_BASE_SHA256 必须是 " +
                    "$SHA256_HEX_LENGTH 位小写十六进制（实际 ${sha.length} 个字符：${describe(sha)}）",
            )
        }

        val entry = strOf(FIELD_ENTRY_FUNCTION)
        val entryLine = lineNo(FIELD_ENTRY_FUNCTION)
        if (entry.isEmpty()) throw PatchFormatException("第 $entryLine 行：$FIELD_ENTRY_FUNCTION 不能为空")
        if (entry != entry.uppercase()) {
            throw PatchFormatException(
                "第 $entryLine 行：$FIELD_ENTRY_FUNCTION 必须大写归一，实为 ${describe(entry)}" +
                    "（写侧恒写大写；读侧不接受小写，免得同一份产物两种键）",
            )
        }

        // 可选段：出现才解析（缺段 = 空表,消费侧追加零行为差）;载荷必须是引号串。
        // 记录形随版本号门控（v1 无浮点原子）—— 版本读侧已经判过界,这里只按它取值。
        val privateDims = if (FIELD_PRIVATE_DIMS in values) {
            PrivateDimsCodec.decode(strOf(FIELD_PRIVATE_DIMS), version)
        } else {
            emptyList()
        }
        // 可选段 function-metas：无版本门控（尾部可选段的追加不需要升版本,见类 KDoc
        // 「版本策略」;旧产物无该段 = 空表 = 装载侧按缺省重建,与引入前逐位一致）。
        val functionMetas = if (FIELD_FUNCTION_METAS in values) {
            FunctionMetasCodec.decode(strOf(FIELD_FUNCTION_METAS))
        } else {
            emptyList()
        }

        return PatchHeader(
            baseInstructionCount = intOf(FIELD_BASE_INSTRUCTIONS),
            baseFunctionCount = intOf(FIELD_BASE_FUNCTIONS),
            baseStatementCount = intOf(FIELD_BASE_STATEMENTS),
            baseStringCount = intOf(FIELD_BASE_STRINGS),
            baseLongCount = intOf(FIELD_BASE_LONGS),
            baseExprCount = intOf(FIELD_BASE_EXPRS),
            baseSha256 = sha,
            compiler = strOf(FIELD_COMPILER),
            patchFunctionCount = intOf(FIELD_PATCH_FUNCTIONS),
            patchStatementCount = intOf(FIELD_PATCH_STATEMENTS),
            patchStringCount = intOf(FIELD_PATCH_STRINGS),
            patchLongCount = intOf(FIELD_PATCH_LONGS),
            patchExprCount = intOf(FIELD_PATCH_EXPRS),
            entryFunction = entry,
            privateDims = privateDims,
            functionMetas = functionMetas,
            formatVersion = version,
        )
    }

    /**
     * 一个引号串字段（载荷里只允许这一个记号 + 首尾空白）。
     *
     * 记号级解析在 [parseQuotedAt]（`private-dims` 载荷的记录里也要逐个解引号串记号，
     * 两处共用同一个转义实现,转义口径才不会漂移）;本函数只补「整行就这一个记号」的
     * 行级约束。
     */
    private fun parseQuoted(
        text: String,
        lineNo: Int,
        field: String,
    ): String {
        var i = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] != '"') {
            throw PatchFormatException("第 $lineNo 行：$field 必须是引号串（形如 \"...\"），实为 ${describe(text)}")
        }
        val (v, after) = parseQuotedAt(text, i, "第 $lineNo 行：$field")
        var j = after
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j < text.length) {
            throw PatchFormatException(
                "第 $lineNo 行：$field 有多余内容：${describe(text.substring(j))}",
            )
        }
        return v
    }

    /**
     * 引号串**记号**：从 [start]（必须指向 `"`）解析到闭引号,返回（串值,闭引号后的下标）。
     *
     * 转义表与 [quote] 严格互逆；出现**未转义**的 C0 直接拒 —— 写侧从不产出它，
     * 接受它就等于允许「读得进、写出来不一样」，I8 的逐字节往返立刻破功。
     * [what] 是错误消息里的定位前缀（头字段 = 「第 N 行：字段名」,`private-dims` 记录 =
     * 「private-dims 第 k 条：记号名」,两处文案形态各自完整可定位）。
     */
    internal fun parseQuotedAt(
        text: String,
        start: Int,
        what: String,
    ): Pair<String, Int> {
        var i = start
        i++ // 开引号（调用方保证 text[i] == '"'）
        val sb = StringBuilder(text.length)
        while (true) {
            if (i >= text.length) throw PatchFormatException("$what 的引号串未闭合")
            val c = text[i]
            if (c == '"') {
                i++
                break
            }
            if (c != '\\') {
                if (c < ' ') {
                    throw PatchFormatException(
                        "$what 的引号串里出现未转义的控制字符（0x${c.code.toString(16)}）" +
                            "：写侧恒转义，读侧不认这种形态",
                    )
                }
                sb.append(c)
                i++
                continue
            }
            i++
            if (i >= text.length) throw PatchFormatException("$what 的转义符后意外结束")
            when (val n = text[i]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 >= text.length) {
                        throw PatchFormatException("$what 的 \\u 转义需要 4 位十六进制")
                    }
                    val hex = text.substring(i + 1, i + 5)
                    val v = hex.toIntOrNull(16)
                        ?: throw PatchFormatException("$what 的 \\u 转义不是十六进制：$hex")
                    sb.append(v.toChar())
                    i += 4
                }
                else -> throw PatchFormatException("$what 出现未知转义 \\$n")
            }
            i++
        }
        return sb.toString() to i
    }

    /** 关键字与载荷的分界（与 `BootMetaCodec.bind` 同口径：第一个空格或制表符）。 */
    private fun separatorIndex(text: String): Int {
        for (i in text.indices) {
            val c = text[i]
            if (c == ' ' || c == '\t') return i
        }
        return -1
    }

    /**
     * 行游标：LF 切行、行尾剥一个 CR（与 `BootMetaCodec` 的 `bind` 同口径），
     * 空行**不跳过**（补丁头里空行 = 字段缺失，必须报错而不是静默跨过）。
     */
    private class LineCursor(private val bytes: ByteArray) {
        var pos: Int = 0
            private set

        var lineNo: Int = 0
            private set

        /** 取下一条行（不含 LF）；已到文件尾返回 null。 */
        fun next(): String? {
            if (pos >= bytes.size) return null
            val from = pos
            var i = from
            while (i < bytes.size && bytes[i] != LF) i++
            var to = i
            if (to > from && bytes[to - 1] == CR) to--
            lineNo++
            pos = if (i < bytes.size) i + 1 else bytes.size
            return decodeStrict(bytes, from, to, lineNo)
        }
    }
}

/**
 * 补丁头可选段 `private-dims` 的载荷编解码（R7-E1）。
 *
 * 值形态 = 一个引号串（与头的其它引号串字段同一套转义,由 [PatchHeaderCodec.quote] /
 * [PatchHeaderCodec.parseQuotedAt] 单源实现）,内容 = 每条声明一行（记录间 LF）。
 * 记录字段序与侧车 `.meta` 的 `private-dim` 记录**逐字段同款**（`BootMetaCodec.encode`
 * 的 private-dim 行：函数键 + 变量名 + 开关位 + 维度 CSV + 默认值段）——
 * 两处编码各自实现（BootMetaCodec 是 private,复用需要挪家）,字段序同表与「写读往返
 * 逐字段相等」的用例钉住同源性。
 *
 * ```
 * v1 记录 = "<funcKey>" "<name>" <isStr> <isConst> <isRef> <isGlobal> <isSavedata>
 *           <isCharaData> <isStatic> <isPrivate> <dimsCsv> <defaultsInt> <defaultsStr>
 * v2 记录 = v1 在 <isPrivate> 后插 <isFloat>、行尾追加 <defaultsFloat>
 *           （4.981 的 #DIMF 与浮点初值;随头字段 erbc-patch 的版本门控,见
 *           [PatchHeader] 类 KDoc「版本策略」）
 * dimsCsv       = "-" | 整数逗号串（空表 = "-"）
 * defaultsInt   = "-" | ""（空数组,两个引号字符）| Long 逗号串
 * defaultsStr   = "-" | ( "<s1>" "<s2>" ... )   （侧车 list() 同款括号形）
 * defaultsFloat = "-" | 有限浮点（与侧车 CMP 的 v3 原子同判据;与侧车不同的一点：
 *                 v2 里**恒写出**（无初值写 `-`）,不留「行尾可缺」的两种规范形）
 * ```
 *
 * 语义校验（重复声明、函数键大写、空名、负维度）在 [PatchHeader] 构造期统一兜,
 * 本类只做**结构**校验（记号形态、0/1 位、整数）;错误消息一律带「private-dims 第 k 条」
 * 前缀,与头层的「第 N 行」互补定位。
 */
internal object PrivateDimsCodec {

    /** [BootMetadata.PrivateDim] 清单 → 字段值原文（记录间 LF;调用方负责整体 quote）。 */
    fun encode(
        dims: List<BootMetadata.PrivateDim>,
        version: Int = PATCH_FORMAT_VERSION_2,
    ): String =
        dims.joinToString(separator = "\n") { p ->
            val s = p.spec
            listOf(
                PatchHeaderCodec.quote(p.functionKey),
                PatchHeaderCodec.quote(s.name),
                bit(s.isStr),
                bit(s.isConst),
                bit(s.isRef),
                bit(s.isGlobal),
                bit(s.isSavedata),
                bit(s.isCharaData),
                bit(s.isStatic),
                bit(s.isPrivate),
                // v2 起有；v1 记录形里没有这个原子（写侧由 PatchHeader 构造期拦住
                // 「v1 + 浮点声明」的组合,所以这里按版本省略不会丢信息）。
                if (version >= PATCH_FORMAT_VERSION_2) bit(s.isFloat) else null,
                if (s.dims.isEmpty()) "-" else s.dims.joinToString(","),
                // 空数组（非 null）写空引号串:写空缺格会让读侧记号整体左移（侧车 CMP-83 同坑）。
                s.defaultsInt?.let { if (it.isEmpty()) "\"\"" else it.joinToString(",") } ?: "-",
                s.defaultsStr?.let { it.joinToString(" ", "(", ")") { v -> PatchHeaderCodec.quote(v) } } ?: "-",
                if (version >= PATCH_FORMAT_VERSION_2) s.defaultsFloat?.toString() ?: "-" else null,
            ).filterNotNull().joinToString(" ")
        }

    /** 字段值原文 → [BootMetadata.PrivateDim] 清单（结构不合法抛 [PatchFormatException]）。 */
    fun decode(
        raw: String,
        version: Int = PATCH_FORMAT_VERSION_2,
    ): List<BootMetadata.PrivateDim> {
        if (raw.isEmpty()) return emptyList()
        val records = raw.split('\n')
        val out = ArrayList<BootMetadata.PrivateDim>(records.size)
        val seen = HashSet<String>(records.size * 2)
        for ((idx, rec) in records.withIndex()) {
            val no = idx + 1
            val r = Record(rec, no)
            val funcKey = r.quoted("函数键")
            val name = r.quoted("变量名")
            if (!seen.add("$funcKey\u0000$name")) {
                throw PatchFormatException("private-dims 第 $no 条：声明重复 $funcKey/$name")
            }
            val isStr = r.bit("isStr")
            val isConst = r.bit("isConst")
            val isRef = r.bit("isRef")
            val isGlobal = r.bit("isGlobal")
            val isSavedata = r.bit("isSavedata")
            val isCharaData = r.bit("isCharaData")
            val isStatic = r.bit("isStatic")
            val isPrivate = r.bit("isPrivate")
            // 与侧车 v1 侧车同款门控：旧产物没有这个原子 ⇒ 按整数声明读入,不是「读不到」。
            val isFloat = if (version >= PATCH_FORMAT_VERSION_2) r.bit("isFloat") else false
            val dims = r.dimsCsv()
            val defaultsInt = r.defaultsInt()
            val defaultsStr = r.defaultsStrList()
            val defaultsFloat = if (version >= PATCH_FORMAT_VERSION_2) r.defaultsFloat() else null
            r.end()
            out.add(
                BootMetadata.PrivateDim(
                    funcKey,
                    com.eratw.port.core.vars.DimSpec(
                        name = name,
                        isStr = isStr,
                        dims = dims,
                        isConst = isConst,
                        isRef = isRef,
                        isGlobal = isGlobal,
                        isSavedata = isSavedata,
                        isCharaData = isCharaData,
                        isStatic = isStatic,
                        isPrivate = isPrivate,
                        defaultsInt = defaultsInt,
                        defaultsStr = defaultsStr,
                        defaultsFloat = defaultsFloat,
                        isFloat = isFloat,
                    ),
                ),
            )
        }
        return out
    }

    private fun bit(b: Boolean): String = if (b) "1" else "0"

    /** 一条记录的记号游标（裸记号 = 非空白连续段;引号串记号走 [PatchHeaderCodec.parseQuotedAt]）。 */
    private class Record(private val text: String, private val no: Int) {
        private var i = 0

        private fun fail(what: String, why: String): Nothing =
            throw PatchFormatException("private-dims 第 $no 条：$what $why")

        private fun skipBlank() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        }

        /** 引号串记号。 */
        fun quoted(what: String): String {
            skipBlank()
            if (i >= text.length || text[i] != '"') fail(what, "必须是引号串，实为 ${describe(text.substring(i).take(32))}")
            val (v, end) = PatchHeaderCodec.parseQuotedAt(text, i, "private-dims 第 $no 条：$what")
            i = end
            return v
        }

        /** 裸记号（非空白连续段）。 */
        fun bare(what: String): String {
            skipBlank()
            if (i >= text.length) fail(what, "缺失")
            val start = i
            while (i < text.length && text[i] != ' ' && text[i] != '\t') i++
            return text.substring(start, i)
        }

        /** 0/1 开关位。 */
        fun bit(what: String): Boolean = when (val t = bare(what)) {
            "0" -> false
            "1" -> true
            else -> fail(what, "必须是 0/1，实为 \"$t\"")
        }

        /** 维度 CSV：`-` = 0 维（标量）;逐段整数、不许负（负值由头构造期同样兜一层）。 */
        fun dimsCsv(): IntArray {
            val t = bare("维度表")
            if (t == "-") return IntArray(0)
            return t.split(',').map {
                val v = it.toIntOrNull() ?: fail("维度表", "不是整数：\"$it\"")
                if (v < 0) fail("维度表", "不能为负：$v")
                v
            }.toIntArray()
        }

        /** 整型默认值：`-` = 无;`""` = 空数组（写侧形态,读侧认回零长）;其余 Long CSV。 */
        fun defaultsInt(): LongArray? {
            val t = bare("整型默认值表")
            return when {
                t == "-" -> null
                t == "\"\"" -> LongArray(0)
                else -> t.split(',').map {
                    it.toLongOrNull() ?: fail("整型默认值表", "不是整数：\"$it\"")
                }.toLongArray()
            }
        }

        /** 字符串默认值：`-` = 无;否则侧车 list() 同款括号形（`( "a" "b" )`），逐项引号串。 */
        fun defaultsStrList(): List<String>? {
            skipBlank()
            if (i >= text.length) fail("字符串默认值表", "缺失")
            when (text[i]) {
                '-' -> {
                    i++
                    return null
                }
                '(' -> i++
                else -> fail("字符串默认值表", "必须是括号列表或 -，实为 ${describe(text.substring(i).take(32))}")
            }
            val out = ArrayList<String>(4)
            while (true) {
                skipBlank()
                if (i >= text.length) fail("字符串默认值表", "括号未闭合")
                if (text[i] == ')') {
                    i++
                    break
                }
                out.add(quoted("字符串默认值表"))
            }
            return out
        }

        /** 浮点初值（v2 行尾）：`-` = 无;其余必须能被 [String.toDouble] 逆解且有限。 */
        fun defaultsFloat(): Double? {
            val t = bare("浮点初值")
            if (t == "-") return null
            val v = t.toDoubleOrNull() ?: fail("浮点初值", "不是浮点数：\"$t\"")
            if (!v.isFinite()) fail("浮点初值", "不是有限数：\"$t\"")
            return v
        }

        /** 记录尾：游标必须到尾（有多余内容 = 记号数不对,当场拒）。 */
        fun end() {
            skipBlank()
            if (i < text.length) fail("记录", "有多余内容：${describe(text.substring(i).take(32))}")
        }
    }
}

/**
 * 补丁函数的 per-function 元数据记录（可选段 `function-metas` 的一条）。
 *
 * 只装**非缺省**函数：六项（[isMethod] / [returnsStr] / [returnsFloat] /
 * [floatLocalSize] / [localSize] / [localsSize]）全缺省的函数不进段 —— 纯 `@label`
 * 语料包的补丁头保持无段形（字节与引入本段前相同）。装载侧按 [offset] 对齐补丁
 * Program 的函数表、按 [name] 原文互证（`GameSession.buildPatchFunctionMetas` 的
 * 消费面），对不上 = 头与补丁体对不上号,装载期拒绝,不猜（I6）。
 *
 * @param offset 补丁窗口内函数序（0 基）;绝对下标 = `base-function-count + offset`。
 * @param name 补丁 Program 的函数名（写侧恒大写归一 + 事件成员 `事件名#绝对下标` 形,
 *   与 `PatchCompiler` 步骤 9 的 `programName` 逐字同源;装载侧用它与函数表互证）。
 */
data class FunctionMetaRecord(
    val offset: Int,
    val name: String,
    /** `#FUNCTION` / `#FUNCTIONS` / `#FUNCTIONF` 方法位（sidecar function 行 flags 位 2 同语义）。 */
    val isMethod: Boolean,
    /** `#FUNCTIONS`（字符串返回通道 `_RETS`）。 */
    val returnsStr: Boolean,
    /** `#FUNCTIONF`（4.981 fork 浮点返回通道 `_RETF`）；只允许与 [isMethod] 同真。 */
    val returnsFloat: Boolean,
    /** `#LOCALFSIZE n`（浮点局部帧长;0 = 未声明,运行期退默认 10）。 */
    val floatLocalSize: Int,
    /** `#LOCALSIZE n`（0 = 未声明）。 */
    val localSize: Int,
    /** `#LOCALSSIZE n`（0 = 未声明）。 */
    val localsSize: Int,
)

/**
 * 补丁头可选段 `function-metas` 的载荷编解码。
 *
 * 值形态 = 一个引号串（与 `private-dims` 同一套转义,由 [PatchHeaderCodec.quote] /
 * [PatchHeaderCodec.parseQuotedAt] 单源实现）,内容 = 每条记录一行（记录间 LF）：
 *
 * ```
 * <offset> "<name>" <isMethod> <returnsStr> <returnsFloat> <floatLocalSize> <localSize> <localsSize>
 * ```
 *
 * 三个布尔位用独立 0/1 原子（不复用侧车 flags 位表）：那 7 位 1..64 与侧车 v2 逐位绑定、
 * `returnsFloat` 在侧车走的是 function 行的**可选原子**而非位（`FuncMeta` 的旁路位不进
 * 侧车 flags）—— 这里照搬原子形态,两处各自的字段表由各自用例钉住,不造第三份位表。
 * 语义闸（offset 越窗、16 位域、returnsFloat 需方法位、offset 重复）在 [PatchHeader]
 * 构造期统一兜;本类只做**结构**校验（记号形态、0/1 位、非负整数）,错误消息一律带
 * 「function-metas 第 k 条」前缀,与头层的「第 N 行」互补定位（PrivateDimsCodec 同款）。
 */
internal object FunctionMetasCodec {

    /** [FunctionMetaRecord] 清单 → 字段值原文（记录间 LF;调用方负责整体 quote）。 */
    fun encode(records: List<FunctionMetaRecord>): String =
        records.joinToString(separator = "\n") { m ->
            listOf(
                m.offset.toString(),
                PatchHeaderCodec.quote(m.name),
                bit(m.isMethod),
                bit(m.returnsStr),
                bit(m.returnsFloat),
                m.floatLocalSize.toString(),
                m.localSize.toString(),
                m.localsSize.toString(),
            ).joinToString(" ")
        }

    /** 字段值原文 → [FunctionMetaRecord] 清单（结构不合法抛 [PatchFormatException]）。 */
    fun decode(raw: String): List<FunctionMetaRecord> {
        if (raw.isEmpty()) return emptyList()
        val records = raw.split('\n')
        val out = ArrayList<FunctionMetaRecord>(records.size)
        for ((idx, rec) in records.withIndex()) {
            val no = idx + 1
            val r = Record(rec, no)
            val offset = r.int("offset")
            val name = r.quoted("函数名")
            val isMethod = r.bit("isMethod")
            val returnsStr = r.bit("returnsStr")
            val returnsFloat = r.bit("returnsFloat")
            val floatLocalSize = r.int("floatLocalSize")
            val localSize = r.int("localSize")
            val localsSize = r.int("localsSize")
            r.end()
            out.add(
                FunctionMetaRecord(
                    offset = offset,
                    name = name,
                    isMethod = isMethod,
                    returnsStr = returnsStr,
                    returnsFloat = returnsFloat,
                    floatLocalSize = floatLocalSize,
                    localSize = localSize,
                    localsSize = localsSize,
                ),
            )
        }
        return out
    }

    private fun bit(b: Boolean): String = if (b) "1" else "0"

    /** 一条记录的记号游标（结构同 PrivateDimsCodec.Record;错误前缀换段名）。 */
    private class Record(private val text: String, private val no: Int) {
        private var i = 0

        private fun fail(what: String, why: String): Nothing =
            throw PatchFormatException("function-metas 第 $no 条：$what $why")

        private fun skipBlank() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        }

        /** 引号串记号。 */
        fun quoted(what: String): String {
            skipBlank()
            if (i >= text.length || text[i] != '"') fail(what, "必须是引号串，实为 ${describe(text.substring(i).take(32))}")
            val (v, end) = PatchHeaderCodec.parseQuotedAt(text, i, "function-metas 第 $no 条：$what")
            i = end
            return v
        }

        /** 裸记号（非空白连续段）。 */
        private fun bare(what: String): String {
            skipBlank()
            if (i >= text.length) fail(what, "缺失")
            val start = i
            while (i < text.length && text[i] != ' ' && text[i] != '\t') i++
            return text.substring(start, i)
        }

        /** 0/1 开关位。 */
        fun bit(what: String): Boolean = when (val t = bare(what)) {
            "0" -> false
            "1" -> true
            else -> fail(what, "必须是 0/1，实为 \"$t\"")
        }

        /** 非负整数（负值不是写侧形态;域上界由头构造期按字段名兜）。 */
        fun int(what: String): Int {
            val t = bare(what)
            val v = t.toIntOrNull() ?: fail(what, "不是整数：\"$t\"")
            if (v < 0) fail(what, "不能为负：$v")
            return v
        }

        /** 记录尾：游标必须到尾（有多余内容 = 记号数不对,当场拒）。 */
        fun end() {
            skipBlank()
            if (i < text.length) fail("记录", "有多余内容：${describe(text.substring(i).take(32))}")
        }
    }
}

/**
 * 严格 UTF-8 解码（畸形字节抛 [PatchFormatException]，不落替换字符）。
 *
 * 补丁头是**外部输入**（装机后存储位腐、手改产物都在可达路径上）：宽松解码会把
 * 一个坏字节变成 U+FFFD 然后「读得进、写出来不一样」，I8 的逐字节往返就此失真。
 */
private fun decodeStrict(
    bytes: ByteArray,
    from: Int,
    to: Int,
    lineNo: Int,
): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
    } catch (e: CharacterCodingException) {
        throw PatchFormatException(
            "第 $lineNo 行：补丁头不是合法 UTF-8（头必须是 UTF-8 文本，" +
                "二进制只许出现在 $HEADER_END_MARKER 之后）",
            e,
        )
    }
}
