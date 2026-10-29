package com.eratw.port.uipreview

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 合成 mini 语料装置（CONTRACT-UP1 单测口径）：临时目录写 mini ERB → erbcc 的
 * [CorpusCompile] + [ErbcArtifacts] 产 erbc.v1 目录 → 装配 → 断言；不依赖真语料，
 * ERATW_* 环境变量一概不用（与 tools/vmdbg 的 MiniCorpus 同款编译法）。
 */
object MiniTitle {

    /** 交互式剧本：标题页 + 按钮 + 两次 INPUT（阻塞等提交）+ QUIT。
     *  按钮后必须跟一条 PRINTL：按钮进的是打印缓冲（LineBufferConsoleSink.bufferButton），
     *  行提交（换行）才把按钮 span 落进显示行 —— 真实脚本的页尾都有换行，装置照做。 */
    val ERB_INTERACTIVE: String = erb(
        "@SYSTEM_TITLE",
        "PRINTL UIPREVIEW-TITLE-PAGE",
        """PRINTBUTTON "START", 1""",
        "PRINTL",
        "INPUT",
        "PRINTFORML PICKED={RESULT}",
        """PRINTBUTTON "SECOND", 2""",
        "PRINTL",
        "INPUT",
        "PRINTFORML SECOND={RESULT}",
        "QUIT",
    )

    /** 自动收尾剧本：标题页 + 按钮，无输入等待，跑完即 QUIT（serve 退出码 0 的测试面）。 */
    val ERB_AUTOQUIT: String = erb(
        "@SYSTEM_TITLE",
        "PRINTL UIPREVIEW-GEOMETRY-PAGE",
        """PRINTBUTTON "START", 1""",
        "PRINTL",
        "QUIT",
    )

    /** ERB 行统一 TAB 缩进（fixtures 同款），首行注释顶格。 */
    private fun erb(vararg lines: String): String =
        "; uipreview 合成 mini 语料（CONTRACT-UP1 单测装置）\n" +
            lines.joinToString("\n") { "\t$it" }

    /** 装置产物：游戏根（只读面）+ erbc.v1 目录。 */
    class Built(val gameRoot: Path, val erbcDir: Path)

    /** 在 [tmp] 下建 `game/`（空 emuera.config + ERB/mini.erb）并编译出 erbc.v1。 */
    fun build(tmp: Path, erb: String): Built {
        val game = tmp.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        // 空配置：三层配置链读不到键时全部走注册缺省（RootedBuiltinHost.applyChainTo 容缺）。
        Files.writeString(game.resolve("emuera.config"), "")
        Files.writeString(erbDir.resolve("mini.erb"), erb)
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "mini 语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = tmp.resolve("erbc-out")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        return Built(game, outDir.resolve("erbc.v1"))
    }

    /**
     * 装配会话（测试直构，不走 [Assembler] 的环境变量面）：`consoleWidth` = [columns]、
     * `randSeed` = Cli.RAND_SEED；gameRoot 是测试临时目录，无需存档沙箱。
     */
    fun assemble(built: Built, columns: Int): GameSession {
        val program = ProgramLoader.loadMapped(built.erbcDir.resolve("program.erbc"))
        val metadata = BootMetadata.read(built.erbcDir.resolve("program.meta"))
        return GameSession(
            gameRoot = built.gameRoot,
            program = program,
            metadata = metadata,
            consoleWidth = columns,
            randSeed = Cli.RAND_SEED,
        )
    }
}

/**
 * 测试共用台架：装配 + 渲染器 + HTTP 服务（端口 0）+ VM 线程，附 [waitUntil] 轮询器。
 * 用完调 [stop]（停服 + 关会话 + join VM 线程）。
 */
class TestRig(
    val renderer: PreviewRenderer,
    val server: PreviewServer,
    private val vm: Thread,
) : AutoCloseable {

    val port: Int get() = server.port

    /** 会话（测试断言等待态/终局用）。 */
    val session: GameSession get() = renderer.session

    fun start() {
        server.start()
        renderer.startPump(Cli.PUMP_INTERVAL_MS)
        vm.start()
    }

    override fun close() {
        server.stop(0)
        renderer.close()
        vm.join(5_000)
    }

    /** 轮询 [cond] 到真或超时（断言失败给 [what]）。 */
    fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）: $what")
    }

    companion object {
        /** 起一套交互式（或自动收尾）台架。 */
        fun start(tmp: Path, erb: String, columns: Int, screenW: Int, screenH: Int): TestRig {
            val built = MiniTitle.build(tmp, erb)
            val session = MiniTitle.assemble(built, columns)
            val renderer = PreviewRenderer(session, columns, screenW, screenH)
            val server = PreviewServer(renderer, 0)
            val vm = Thread({ runSessionLoop(session, renderer) }, "test-uipreview-vm")
            val rig = TestRig(renderer, server, vm)
            rig.start()
            return rig
        }
    }
}

/** VM 线程是否已结束的观察口（避免测试直接 join 挂死）。 */
internal fun TestRig.vmEnded(): Boolean = renderer.vmEnded

/** 一次性标志的简易轮询等待（给 CLI serve 线程用）。 */
internal fun waitFor(timeoutMs: Long = 10_000, cond: AtomicBoolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond.get()) return
        Thread.sleep(10)
    }
    throw AssertionError("等待超时（${timeoutMs}ms）")
}
