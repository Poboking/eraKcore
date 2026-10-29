package com.eratw.port.uipreview

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLI 冒烟（CONTRACT-UP1 §3 单测③）：state / tap / type / shot 打在进程内起的服务上
 * （退出码 0）；坏端口 → 2；未知子命令 → 1；serve 用自动 QUIT 剧本跑完退出码 0。
 */
class CliSmokeTest {

    private fun streams(): Triple<ByteArrayOutputStream, ByteArrayOutputStream, PrintStreamPair> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return Triple(out, err, PrintStreamPair(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)))
    }

    /** out/err 成对携带（Kotlin 没有多个返回值的轻量写法，用Pair装）。 */
    private class PrintStreamPair(val out: PrintStream, val err: PrintStream)

    @Test
    fun `serve 自动QUIT剧本退出码0`(@TempDir tmp: Path) {
        val built = MiniTitle.build(tmp, MiniTitle.ERB_AUTOQUIT)
        val (outBuf, errBuf, streams) = streams()
        val rc = Cli.run(
            arrayOf(
                "serve", "--erbc", built.erbcDir.toString(), "--game", built.gameRoot.toString(),
                "--port", "0", "--columns", "40", "--screen", "540x960",
            ),
            streams.out, streams.err,
        )
        assertEquals(0, rc, "stderr: ${errBuf.toString(Charsets.UTF_8)}")
        val outText = outBuf.toString(Charsets.UTF_8)
        assertTrue(outText.contains("uipreview serve: http://127.0.0.1:"), outText)
    }

    @Test
    fun `serve 屏幕参数非法折成用法错误退出码1`(@TempDir tmp: Path) {
        val built = MiniTitle.build(tmp.resolve("bad"), MiniTitle.ERB_AUTOQUIT)
        val (_, errBuf, streams) = streams()
        val rc = Cli.run(
            arrayOf(
                "serve", "--erbc", built.erbcDir.toString(), "--game", built.gameRoot.toString(),
                "--screen", "not-a-size",
            ),
            streams.out, streams.err,
        )
        assertEquals(1, rc)
        assertTrue(errBuf.toString(Charsets.UTF_8).contains("--screen"), "应点名坏参数")
    }

    @Test
    fun `state tap type shot 打进程内服务 全部退出码0`(@TempDir tmp: Path) {
        TestRig.start(tmp, MiniTitle.ERB_INTERACTIVE, columns = 60, screenW = 600, screenH = 1200).use { rig ->
            val portArg = arrayOf("--port", rig.port.toString())
            rig.waitUntil("INPUT 就绪") { rig.session.inputSink.awaiting != null }
            // /state 的按钮表是泵线程发布的快照：等泵把按钮行发布出来再断言（INPUT 就绪
            // 与按钮行发布之间有泵周期级的时间差，直接断言会抢跑）。
            rig.waitUntil("按钮表就绪") { rig.renderer.currentUi?.buttons?.isNotEmpty() == true }

            // state：退出码 0 + JSON 有关键字段。
            val (out1, err1, s1) = streams()
            val rc1 = Cli.run(arrayOf("state") + portArg, s1.out, s1.err)
            assertEquals(0, rc1, err1.toString(Charsets.UTF_8))
            val state1 = out1.toString(Charsets.UTF_8)
            assertTrue(state1.contains("\"columns\":60"), state1)
            assertTrue(state1.contains("\"buttons\":[{"), state1)

            // tap：用按钮中心。
            val button = assertNotNull(rig.renderer.currentUi).buttons.first()
            val (out2, err2, s2) = streams()
            val rc2 = Cli.run(arrayOf("tap", button.xPx.toString(), button.yPx.toString()) + portArg, s2.out, s2.err)
            assertEquals(0, rc2, err2.toString(Charsets.UTF_8))
            rig.waitUntil("第一页被点掉") {
                rig.renderer.currentUi?.buttons?.any { it.label == "SECOND" } == true
            }
            rig.waitUntil("第二次 INPUT 就绪") { rig.session.inputSink.awaiting != null }

            // type：位置参数带空格拼回一条文本。
            val (out3, err3, s3) = streams()
            val rc3 = Cli.run(arrayOf("type", "hello", "world") + portArg, s3.out, s3.err)
            assertEquals(0, rc3, err3.toString(Charsets.UTF_8))
            // "hello world" 不是整数 ⇒ INT 解析失败丢弃（继续等）；不应推进到 QUIT。
            Thread.sleep(120)
            assertTrue(rig.session.inputSink.awaiting != null, "INT 解析失败的提交应被丢弃、继续等待")

            // type 一个合法整数 → 剧本收尾。
            val (out4, err4, s4) = streams()
            val rc4 = Cli.run(arrayOf("type", "9") + portArg, s4.out, s4.err)
            assertEquals(0, rc4, err4.toString(Charsets.UTF_8))
            rig.waitUntil("VM 终局") { rig.renderer.vmEnded }

            // shot：落盘文件存在且是 PNG。
            val shotPath = tmp.resolve("cli-shot.png")
            val (out5, err5, s5) = streams()
            val rc5 = Cli.run(arrayOf("shot", "--out", shotPath.toString()) + portArg, s5.out, s5.err)
            assertEquals(0, rc5, err5.toString(Charsets.UTF_8))
            val bytes = assertNotNull(Files.readAllBytes(shotPath))
            assertEquals(0x89, bytes[0].toInt() and 0xFF, "PNG 魔数")
            assertTrue(out5.toString(Charsets.UTF_8).contains("shot:"), out5.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `坏端口退出码2 未知子命令退出码1`() {
        val (_, _, s1) = streams()
        val rc1 = Cli.run(arrayOf("state", "--port", "1"), s1.out, s1.err)
        assertEquals(2, rc1, "连不上应折成目标不存在档")

        val (_, err2, s2) = streams()
        val rc2 = Cli.run(arrayOf("bogus"), s2.out, s2.err)
        assertEquals(1, rc2)
        assertTrue(err2.toString(Charsets.UTF_8).contains("无法识别的子命令"), err2.toString(Charsets.UTF_8))
    }
}
