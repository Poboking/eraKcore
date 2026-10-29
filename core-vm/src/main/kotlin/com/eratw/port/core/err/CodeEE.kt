package com.eratw.port.core.err

/**
 * 脚本级运行错误 —— **全工程唯一的 CodeEE 真源**（team-lead 裁决 2026-10-17）。
 *
 * 对应原版 `CodeEE`（`MinorShift.Emuera` 的脚本错误）：ERB 语义错误在运行期抛出，
 * 可被 `TRYC` 家族捕获（捕获语义属语句层 / M2 第二批）。
 *
 * 位置信息是**结构化字段**，不揉进 message：message 保持原版 `Lang.Error` 的文案，
 * 宿主需要"函数:行:pc"时自行拼接或直接读字段。
 *
 * - [funcName]：当前帧的 ERB 函数名；非 VM 场景为 null。
 * - [line]：源码行号（本工程的汇编器与 erbcc 都用 1 起）。
 * - [pc]：指令下标；非 VM 产生的错误为 -1。
 *
 * 之所以是 `open`：VM 需要区分"脚本自身错了"（CodeEE）与"VM 保护闸触发"
 * （[com.eratw.port.core.code.StepLimitExceeded] / [com.eratw.port.core.code.CallStackOverflow]），
 * 后者继承本类但仍可被 `catch (CodeEE)` 兜住。**不要**再复制第二份实现。
 */
open class CodeEE(
    message: String,
    val funcName: String? = null,
    val line: Int = -1,
    val pc: Int = -1,
) : RuntimeException(message)
