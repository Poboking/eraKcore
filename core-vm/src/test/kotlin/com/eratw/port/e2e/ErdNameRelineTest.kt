package com.eratw.port.e2e

import com.eratw.port.core.stmt.FunctionCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RELINE 语义钉（ENGFIX-2 · call-sem，2026-11-05；旧名「ERDNAME + RELINE 缺口回归钉」）。
 *
 * 旧版本在本文件钉的是「`CALL RELINE(n)` 编译期重写成 `CLEARLINE(n)`」（commit `e83c1f57`），
 * 前提是「RELINE 是 Emuera_skia 的魔改内建指令」。该前提本轮取证 **证伪**：
 *
 * 1. skia 侧没有 RELINE：`unpacked/Emuera.dll`（skia v1.824+v22+EMv18+EEv53）的全量反编译树
 *    `unpacked/skia-decompiled/`（307 个 .cs）里 `RELINE` 字面量 0 处；组长侧对
 *    `Emuera_skiaV16_x64.exe`、旧版 `eratw-sub-modding/Emuera_skiaV10_x64.exe`、
 *    `plugins/ChatCallSharp.dll`、`plugins/ClassCallSharp` 同级插件 dll、`lazyloading.bin/cfg`、
 *    `emuera.config`、`setting.json`、`macro.txt` 做 ASCII + UTF-16LE 双编码普查也全 0 命中。
 *    `FunctionCode.cs`（skia 与官方同树）也没有 `RELINE` 成员。
 * 2. RELINE 是**语料自带的用户函数**：`@Reline(LineCountTag,ClearOrStore)`
 *    （语料根 `ERB/魔改内容/Misc.ERB:29`，体 :36-41 = 首次/存行模式把 `LINECOUNT`
 *    记进 `行数存档:LineCountTag`，清行模式 `CLEARLINE LINECOUNT-行数存档:tag`）。
 *    语义是**按栏位的书签式清行**：第 1 参是 0-9 号记录栏位，不是行数；
 *    语料调用点最大用到 `CALL RELINE(9,1)`（`ERB/DLC/GETINFODLC.ERB:1327`），
 *    旧重写把它折成 `CLEARLINE(9)`/`CLEARLINE(10)`，在真语料形态下直接删错行。
 *    （4.981 语料同函数同形：`ERB/魔改内容/Misc.ERB` 的 `@Reline`。）
 * 3. 名字缺失时的 oracle = **行级 NotDefinedFunc**，装载照常、执行到该行才抛 CodeEE
 *    （spec: FunctionIdentifier.cs:3532-3535 + ErbLoader.cs:1615-1625 + Process.cs:1017-1019），
 *    移植侧由 `ErbProgramCompiler.emitStaticTargetNotFound` 复刻；旧实现自造的
 *    「RELINE 第 2 参必须是整型字面量」文案在官方语言表里不存在，随重写一并删除。
 *    TODO(verify)：`D:/Game/eraTW4.981-261017-EAGLOV3/reraconfig.toml` 证明该目录同_shape
 *    兼容 RustyEra（profile `emuera.skia.snake`）客户端，本机无 RustyEra 二进制可反证
 *    「RELINE 是 RustyEra 内建」；本移植目标引擎是 skia，按 skia 口径 = 未定义名走 CALL。
 *
 * 编译器的重写已拆（见 `ErbProgramCompiler` 静态名分支的注释），本文件改钉四件事：
 * 静态名 `CALL RELINE` 绑到同名用户函数并按其本体执行；调用点不得出现编译期注入的
 * `CLEARLINE`；本体走书签语义（存行不清、取行才清）；名字缺失是运行期行级未定义，
 * 且第 2 参非常量也照常编译。断言全部对着上面 1-3 的出处，不拿旧默认自证。
 */
class ErdNameRelineTest {

    // ------------------------------------------------------------------
    // 装置（GoldenPipeline：erbcc 符号表 → 切片 → 编译器 → Program → ProcessRuntime）
    // ------------------------------------------------------------------

    private fun newFixture(vararg erbFiles: Pair<String, String>): Path {
        val dir = Files.createTempDirectory("eratw-reline")
        // GoldenPipeline.build 硬要求 GOLDEN_ROOT 切片且至少含一个 CALL（头/尾切分）
        dir.resolve("E_ROOT.ERB").writeText(
            "@GOLDEN_ROOT\nCALL T_RELINE_DUMMY\n@T_RELINE_DUMMY\nRETURN\n",
            Charsets.UTF_8,
        )
        for ((name, body) in erbFiles) dir.resolve(name).writeText(body.trimIndent() + "\n", Charsets.UTF_8)
        return dir
    }

    private fun runFunction(dir: Path, key: String): Pair<GoldenModel, List<String>> {
        val m = GoldenPipeline.build(dir)
        assertTrue(m.compileFailures.isEmpty(), "编译失败: ${m.compileFailures}")
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(m, console, 100_000)
        rt.callFunction(key)
        // 快照首行固定是 LOADING MESSAGE（GoldenPipeline 启动横幅，基准 pc-golden-root.txt:9 同源），
        // 与本钉无关；调用方一律 drop(1) 后比对。
        return m to console.snapshot()
    }

    /** 语料 `ERB/魔改内容/Misc.ERB:29-40` 的 @Reline 本体（逐字缩样：去注释、去 #LOCALSIZE/#LOCALSSIZE；
     *  两枚 `#DIM DYNAMIC` 形参声明必须保留 —— 装置 ScopedResolver 按 funcVarDefs 口径要求形参有声明）。 */
    private val relineDef = listOf(
        "@Reline(LineCountTag,ClearOrStore)",
        "#DIM DYNAMIC LineCountTag",
        "#DIM DYNAMIC ClearOrStore",
        "#DIM 行数存档,10",
        "IF !行数存档:LineCountTag || ClearOrStore",
        "行数存档:LineCountTag = LINECOUNT",
        "ELSE",
        "CLEARLINE LINECOUNT-行数存档:LineCountTag",
        "ENDIF",
    )

    /** 「@Reline 本体 + 调用方」的整文件文本。 */
    private fun withRelineDef(caller: String): String = (relineDef + listOf("") + caller.trimIndent().lines()).joinToString("\n")

    // ------------------------------------------------------------------
    // 1. 静态名 = 用户函数（不是内建重写）
    // ------------------------------------------------------------------

    @Test
    fun reline_静态名绑到同名用户函数并按本体执行() {
        val (_, lines) = runFunction(
            newFixture(
                "E_B.ERB" to """
                @Reline(LineCountTag,ClearOrStore)
                #DIM DYNAMIC LineCountTag
                #DIM DYNAMIC ClearOrStore
                PRINTFORML RELINE-BODY {LineCountTag}/{ClearOrStore}
                @T_RELINE_BIND
                PRINTL BEFORE
                CALL RELINE(3,1)
                PRINTL AFTER
                """.trimIndent(),
            ),
            "T_RELINE_BIND",
        )
        // oracle：CALL RELINE 与普通用户函数静态绑定同形 —— 被调体在调用点执行、
        // 形参按位收到实参（第 1 参 = 栏位号 3，第 2 参 = 存/清开关 1），调用点前后行不受影响。
        // 旧实现在此处永不进入被调体（编译期换成 CLEARLINE），故本断言即是对旧口径的反证。
        assertEquals(listOf("BEFORE", "RELINE-BODY 3/1", "AFTER"), lines.drop(1))
    }

    @Test
    fun reline_调用点不得出现编译期注入的CLEARLINE() {
        val dir = newFixture(
            "E_S.ERB" to """
            @Reline(LineCountTag,ClearOrStore)
            #DIM DYNAMIC LineCountTag
            #DIM DYNAMIC ClearOrStore
            PRINTL RAN
            @T_RELINE_SHAPE
            PRINTL A1
            CALL RELINE(2)
            PRINTL A2
            """.trimIndent(),
        )
        val (m, lines) = runFunction(dir, "T_RELINE_SHAPE")
        // 源文本一处 CLEARLINE 都没有 ⇒ 产物里出现 CLEARLINE 只能是编译器注入的（旧重写）。
        val injected = m.program.statements().filter { it.code == FunctionCode.CLEARLINE }
        assertTrue(injected.isEmpty(), "调用点被注入了 ${injected.size} 条 CLEARLINE（旧 e83c1f57 重写复活）")
        assertEquals(listOf("A1", "RAN", "A2"), lines.drop(1))
    }

    // ------------------------------------------------------------------
    // 2. 本体语义 = 书签式清行（第 1 参是栏位号，不是行数）
    // ------------------------------------------------------------------

    @Test
    fun reline_存行模式不删任何行() {
        val dir = newFixture(
            "E_STORE.ERB" to withRelineDef(
                """
                @T_RELINE_STORE
                PRINTL K1
                PRINTL K2
                CALL RELINE(3,1)
                PRINTL S1
                PRINTL S2
                """.trimIndent(),
            ),
        )
        val (_, lines) = runFunction(dir, "T_RELINE_STORE")
        // Misc.ERB:36-37 —— ClearOrStore=1 时只记 LINECOUNT，不产生任何删行副作用。
        // 旧重写把这一行换成 CLEARLINE(3+1)，会删掉 K1/K2/S1（且删多少与栏位号无关）。
        assertEquals(listOf("K1", "K2", "S1", "S2"), lines.drop(1))
    }

    @Test
    fun reline_取行模式按书签清掉记录之后的行() {
        val dir = newFixture(
            "E_CLEAR.ERB" to withRelineDef(
                """
                @T_RELINE_CLEAR
                PRINTL K1
                CALL RELINE(3,1)
                PRINTL S1
                PRINTL S2
                CALL RELINE(3,0)
                PRINTL T1
                """.trimIndent(),
            ),
        )
        val (_, lines) = runFunction(dir, "T_RELINE_CLEAR")
        // 推演（Misc.ERB:36-40，CLEARLINE k = 删末尾 k 个逻辑行）：
        // 存行时记 LINECOUNT=X，取行时 LINECOUNT=X+2 ⇒ CLEARLINE 2 恰好删掉 S1/S2。
        // 种子行号口径在减法里抵消，故本断言不依赖装置行数起点。
        // 同一份源在旧重写下的两次调用折成 CLEARLINE(4) 与 CLEARLINE(3)，与栏位号绑定无关
        // （它把第 1 参当行数），输出形态不同 ⇒ 本断言钉的是本体语义而非实现自证。
        assertEquals(listOf("K1", "T1"), lines.drop(1))
    }

    // ------------------------------------------------------------------
    // 3. 名字缺失 = 行级未定义；非常量实参 = 照常编译
    // ------------------------------------------------------------------

    @Test
    fun reline_语料缺失该名时是运行期行级未定义而不是编译期自造报错() {
        val dir = newFixture(
            "E_MISS.ERB" to """
            @T_RELINE_MISS
            PRINTL BEFORE
            CALL RELINE(2)
            PRINTL AFTER
            """.trimIndent(),
        )
        val m = GoldenPipeline.build(dir)
        // 装载/编译不因缺失目标而失败（spec: ErbLoader.cs:1615-1625 只把该行记为 IsError；
        // 旧口径「build() 抛未定义的函数 → 含它的函数整页报废」不成立）。
        assertTrue(m.compileFailures.isEmpty(), "缺失 RELINE 不该让编译失败: ${m.compileFailures}")
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(m, console, 100_000)
        val err = runCatching { rt.callFunction("T_RELINE_MISS") }.exceptionOrNull()
        val msg = listOfNotNull(err?.message, err?.cause?.message).joinToString(" | ")
        assertTrue(
            msg.contains("没有找到指定的函数") && msg.contains("RELINE"),
            "执行到该行应抛原版 NotDefinedFunc 文案（spec: lang.emuera-zhs.xml:520），实际: $msg",
        )
        // 抛在该行本身：其后的 PRINTL 不再执行
        assertTrue(console.snapshot().none { it.contains("AFTER") }, "实际输出: ${console.snapshot()}")
    }

    @Test
    fun reline_第2参是非常量也照常编译() {
        val dir = newFixture(
            "E_DYN.ERB" to """
            @Reline(LineCountTag,ClearOrStore)
            #DIM DYNAMIC LineCountTag
            #DIM DYNAMIC ClearOrStore
            PRINTFORML RELINE-BODY {LineCountTag}/{ClearOrStore}
            @T_RELINE_DYN
            #DIM DYNAMIC MV
            MV = 1
            CALL RELINE(0,MV)
            PRINTL MARK
            """.trimIndent(),
        )
        val m = GoldenPipeline.build(dir)
        // 官方 CALL 的实参本就是运行期表达式，编译器无权对第 2 参加字面量闸门；
        // 旧实现的自造中文报错（「RELINE 第 2 参必须是整型字面量」）在官方语言表里不存在。
        assertTrue(m.compileFailures.isEmpty(), "非常量第 2 参不该编译失败: ${m.compileFailures}")
        val console = GoldenPipeline.newConsole()
        GoldenPipeline.newRuntime(m, console, 100_000).callFunction("T_RELINE_DYN")
        assertEquals(listOf("RELINE-BODY 0/1", "MARK"), console.snapshot().drop(1))
    }
}
