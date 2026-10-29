package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * BOARD 红 A：裸文本赋值的 RHS 里冒号不是下标。
 *
 * `LOCALS = 氷精:Strongest Fairy:Unnatural Chill` 是 STR 赋值，整段 RHS 是字符串；
 * 预扫描若按 `IDENT:` 兜底登记，会把 `氷精` 变成 3 维 INT 幽灵变量，抢在 Parser
 * 的 `resolveIndexKeyword` 之前解析成 VarRef，运行期按下标读字符串即崩。
 * spec: ExpressionParser.cs:396-445 —— 未定义标识符在字符串位回落 SingleStrTerm。
 *
 * 2026-10-22 合流 origin/main@2421154 后，兜底登记的口径由「整行扫 `IDENT:`」收窄为
 * 「只认语句级赋值左侧」（`assignmentDest` + `registerDest`，与 core-vm 装置
 * `CorpusValidator.prescan` 同一实现，远端 `abf4bb45`）。原口径的两个洞在这里各钉一条：
 * ① STR 赋值的 RHS 整段是字符串；② 比较表达式（`IF A == B:1`）里的冒号也不是下标。
 * 断言「RHS 不登记」正是本文件原先写反的那一条：原版没有裸用即声明
 * （ExpressionParser.cs:444-445），把它当声明点就是幽灵变量的同源行为。
 */
class CorpusScanPhantomTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `STR 裸文本 RHS 不登记冒号前的幽灵变量`() {
        val erb = dir.resolve("red-a.ERB")
        Files.writeString(
            erb,
            """
            @TEST
            LOCALS = 氷精:Strongest Fairy:Unnatural Chill
            X = MYVAR:ARG:氷精
            """.trimIndent(),
        )

        val scan = CorpusScan.scan(listOf(erb))

        assertNull(scan.global.resolveVariable("氷精", null), "氷精 不应被登记为变量")
        assertNull(
            scan.global.resolveVariable("MYVAR", null),
            "赋值右侧的 MYVAR:ARG 不构成声明点（收窄后只有赋值左侧才登记）",
        )
        // 赋值左侧仍会补登记的情形由 CorpusScanSubscriptTest「赋值目标仍补登记未声明的系统变量」
        // 钉住（`UNK_SYSVAR:1 = 3` → 1 维），本文件不重复钉。
    }
}
