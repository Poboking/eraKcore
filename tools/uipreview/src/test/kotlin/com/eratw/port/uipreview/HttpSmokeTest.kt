package com.eratw.port.uipreview

import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP 冒烟（CONTRACT-UP1 §3 单测②）：起服 → tap → submit → revision 递增。
 * 全程打真实 HTTP 端点（127.0.0.1 随机端口），按钮坐标从 /state 的按钮表取。
 */
class HttpSmokeTest {

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        return try {
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            status to body.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(port: Int, path: String, json: String): Pair<Int, String> {
        val conn = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        return try {
            conn.setFixedLengthStreamingMode(json.toByteArray(Charsets.UTF_8).size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            status to body.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `起服 tap submit 推进剧本并出 304`(@TempDir tmp: Path) {
        TestRig.start(tmp, MiniTitle.ERB_INTERACTIVE, columns = 60, screenW = 600, screenH = 1200).use { rig ->
            val port = rig.port

            // 静态页可达。
            val (idxStatus, indexHtml) = httpGet(port, "/")
            assertEquals(200, idxStatus)
            assertTrue(indexHtml.contains("<canvas"), "静态页应有 canvas 贴帧脚本")

            // 等首帧 + 按钮表（VM 线程停在第一次 INPUT 上）。
            rig.waitUntil("首帧") { rig.renderer.currentFrame != null }
            rig.waitUntil("按钮表就绪") { rig.renderer.currentUi?.buttons?.isNotEmpty() == true }
            val (stateStatus, state0) = httpGet(port, "/state")
            assertEquals(200, stateStatus)
            assertTrue(state0.contains("\"columns\":60"), "/state 应报列数: $state0")
            assertTrue(state0.contains("\"type\":\"INT\""), "停在 INPUT 上 /state 应报 INT 等待: $state0")
            assertTrue(state0.contains("\"buttons\":[{"), "/state 应有按钮表: $state0")

            // 帧序号 304 判定：同 rev 回 304。
            val frameRev = assertNotNull(rig.renderer.currentFrame).frameRev
            val (st304, _) = httpGet(port, "/frame.png?rev=$frameRev")
            assertEquals(304, st304, "同 rev 的 /frame.png 应回 304")

            // PNG 魔数 + rev 头。
            val conn = java.net.URL("http://127.0.0.1:$port/frame.png").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            val png = conn.inputStream.use { it.readBytes() }
            val contentType = conn.contentType
            val frameRevHeader = conn.getHeaderField("X-Frame-Rev")
            conn.disconnect()
            assertEquals(0x89, png[0].toInt() and 0xFF, "PNG 魔数")
            assertEquals("image/png", contentType)
            assertTrue(frameRevHeader != null, "应有 X-Frame-Rev 头")

            // 取按钮中心（从渲染器快照读；/state 的按钮表就是同一份 JSON 化）。
            val button = assertNotNull(rig.renderer.currentUi).buttons.first()
            val sinkRev0 = rig.renderer.currentUi!!.sinkRev

            // 点按钮 → 泵线程命中 → QueueInputSink 收到 "1" → INPUT 交付 → 剧本推进到第二次 INPUT。
            val (tapStatus, tapBody) = httpPost(port, "/tap", """{"x":${button.xPx},"y":${button.yPx}}""")
            assertEquals(200, tapStatus, tapBody)
            rig.waitUntil("sinkRev 前进（按钮提交被消费）") {
                val ui = rig.renderer.currentUi
                ui != null && ui.sinkRev > sinkRev0
            }

            // 第二页出现 SECOND 按钮。
            rig.waitUntil("SECOND 按钮出现") {
                rig.renderer.currentUi?.buttons?.any { it.label == "SECOND" } == true
            }
            val second = rig.renderer.currentUi!!.buttons.first { it.label == "SECOND" }
            val (tap2Status, _) = httpPost(port, "/tap", """{"x":${second.xPx},"y":${second.yPx}}""")
            assertEquals(200, tap2Status)
            rig.waitUntil("第二次 INPUT 就绪") {
                val s = rig.session.inputSink
                s.awaiting != null && s.awaiting?.type == com.eratw.port.core.host.InputRequest.InputType.INT
            }

            // POST /submit 交付第二次输入 → 剧本 QUIT 收尾。
            val (subStatus, subBody) = httpPost(port, "/submit", """{"text":"7"}""")
            assertEquals(200, subStatus, subBody)
            assertTrue(subBody.contains("\"queued\":true"), subBody)
            rig.waitUntil("VM 终局（QUIT）") { rig.renderer.vmEnded }

            // /scroll 端点可达（内容已收尾，只验 200）。
            val (scrollStatus, _) = httpPost(port, "/scroll", """{"rows":-2}""")
            assertEquals(200, scrollStatus)
        }
    }

    @Test
    fun `空白点击在等待取值时不推进`(@TempDir tmp: Path) {
        TestRig.start(tmp, MiniTitle.ERB_INTERACTIVE, columns = 60, screenW = 600, screenH = 1200).use { rig ->
            // 等 INPUT 就绪（取值等待），并等泵把这一页（按钮行）发布完再取 sinkRev 基准，
            // 否则基线读的是发布中的旧值、断言会被泵的下一次发布误伤。
            rig.waitUntil("INPUT 就绪") { rig.session.inputSink.awaiting != null }
            rig.waitUntil("按钮表就绪") { rig.renderer.currentUi?.buttons?.isNotEmpty() == true }
            val rev0 = rig.renderer.currentUi?.sinkRev ?: 0
            // 点屏外角落（必然未命中按钮）：数值等待不补空回车（BlankTapPolicy 口径）。
            val (status, _) = httpPost(rig.port, "/tap", """{"x":-5,"y":-5}""")
            assertEquals(200, status)
            Thread.sleep(120)
            assertTrue(rig.session.inputSink.awaiting != null, "数值等待期点空白不应推进")
            assertEquals(rev0, rig.renderer.currentUi!!.sinkRev, "不应有新提交被消费")
        }
    }
}
