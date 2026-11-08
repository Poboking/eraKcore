package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UpdateTelemetry] 定向单测（更新漏斗遥测基座，P1-4）。
 *
 * 钉的是批量口径（32 条 / 48h 先到先刷、失败冷却、**硬上限只保留最近 32 条**）、
 * 事件维度合法性（坏输入拒绝）与手写 JSON 行编码的 round-trip。HTTP 与文件实现不在此
 * （归 app 壳的 UpdateTelemetrySink）。
 */
class UpdateTelemetryTest {

    /** 内存假 storage：记录每次 save 的行快照，单测断言持久化时点。 */
    private class FakeStorage : UpdateTelemetry.Storage {
        val saved: MutableList<List<String>> = mutableListOf()
        var lines: List<String> = emptyList()
        override fun load(): List<String> = lines
        override fun save(lines: List<String>) {
            saved.add(lines.toList())
            this.lines = lines.toList()
        }
    }

    private class FakeTelemetry(storage: FakeStorage, clock: () -> Long) {
        val sent: MutableList<List<String>> = mutableListOf()
        var sendResult = true
        val telemetry = UpdateTelemetry(storage, clock, { lines ->
            sent.add(lines.toList())
            sendResult
        })
    }

    private fun event(
        name: String,
        at: Long = 1_700_000_000_000L,
        version: Long? = 15L,
        kind: String? = null,
        sizeBytes: Long? = null,
        force: Boolean? = null,
        failure: String? = null,
        outcome: String? = null,
        status: String? = null,
    ) = UpdateTelemetry.Event(name, at, version, kind, sizeBytes, force, failure, outcome, status)

    // ------------------------------------------------------------------
    // 事件维度合法性（坏输入拒绝）
    // ------------------------------------------------------------------

    @Test
    fun 未知事件名被拒() {
        val storage = FakeStorage()
        val t = FakeTelemetry(storage, { 100L }).telemetry
        assertFalse(t.record(event("no_such_event")))
        assertEquals(0, t.pendingCount())
        assertEquals(0, storage.saved.size)
    }

    @Test
    fun 非法时间戳与负版本被拒() {
        val storage = FakeStorage()
        val t = FakeTelemetry(storage, { 100L }).telemetry
        assertFalse(t.record(event("check_started", at = 0L)))
        assertFalse(t.record(event("check_started", at = -5L)))
        assertFalse(t.record(event("check_started", version = -1L)))
        assertEquals(0, t.pendingCount())
    }

    @Test
    fun offer_shown_kind_只收apk_assets() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertTrue(t.record(event("offer_shown", kind = "apk", force = false)))
        assertTrue(t.record(event("offer_shown", kind = "assets", force = true)))
        assertFalse(t.record(event("offer_shown", kind = "zip")))
        assertFalse(t.record(event("offer_shown", force = true))) // 缺 kind
    }

    @Test
    fun offer_action_kind_只收三个动作() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertTrue(t.record(event("offer_action", kind = "download")))
        assertTrue(t.record(event("offer_action", kind = "skip")))
        assertTrue(t.record(event("offer_action", kind = "decline")))
        assertFalse(t.record(event("offer_action", kind = "apk")))
        assertFalse(t.record(event("offer_action", kind = "later")))
    }

    @Test
    fun 不允许的维度出现即拒() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertFalse(t.record(event("check_started", kind = "apk")))
        assertFalse(t.record(event("check_started", force = true)))
        assertFalse(t.record(event("check_started", failure = "network")))
        assertFalse(t.record(event("check_started", sizeBytes = 10L)))
        assertFalse(t.record(event("offer_shown", kind = "apk", force = false, failure = "x")))
        // status 归 install_result 独占；outcome 按事件分档（install_result 三档、
        // assets_applied 两档），其余事件带上就是接线错位，整条拒。
        assertFalse(t.record(event("check_started", outcome = "success")))
        assertFalse(t.record(event("install_requested", outcome = "failed", status = "1")))
        assertFalse(t.record(event("download_failed", kind = "apk", failure = "x", status = "1")))
        assertFalse(t.record(event("assets_applied", outcome = "cancelled")))
    }

    @Test
    fun install_result_的三档终态全收_缺或坏_outcome_被拒() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertTrue(t.record(event("install_result", outcome = "success")))
        assertTrue(t.record(event("install_result", outcome = "cancelled")))
        assertTrue(t.record(event("install_result", outcome = "failed")))
        assertTrue(t.record(event("install_result", outcome = "failed", status = "1")))
        assertFalse(t.record(event("install_result")))
        assertEquals("install_result 缺 outcome", t.validate(event("install_result")))
        assertFalse(t.record(event("install_result", outcome = "ok")))
        assertEquals("install_result outcome 非法：ok", t.validate(event("install_result", outcome = "ok")))
        // success / cancelled 不许蹭状态码：那一格只在失败档有含义。
        assertFalse(t.record(event("install_result", outcome = "success", status = "0")))
        assertEquals(
            "install_result 只在 failed 档带 status",
            t.validate(event("install_result", outcome = "cancelled", status = "3")),
        )
    }

    @Test
    fun install_result_的_status_只收短标签() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        // 合法面：0..8 的具名码与未知码（含负数，`-99` 是测试里那条未知码回执的形状）。
        for (status in listOf("0", "1", "8", "-99", "42")) {
            assertTrue(t.record(event("install_result", outcome = "failed", status = status)), "状态码 $status 该收")
        }
        // 空白 / 超长 / 带空格与路径分隔符的自由文本一律拒 —— 系统原话不进漏斗。
        for (bad in listOf("", "  ", "INSTALL_FAILED_UPDATE_INCOMPATIBLE: ", "/data/local/tmp/x.apk", "a".repeat(41))) {
            val problem = t.validate(event("install_result", outcome = "failed", status = bad))
            assertNotNull(problem, "状态标签「$bad」居然被收下")
            assertEquals("install_result status 非法：$bad", problem)
        }
        assertTrue(t.record(event("install_result", outcome = "failed", status = "a".repeat(40))))
    }

    @Test
    fun check_failed_缺_failure_被拒() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertFalse(t.record(event("check_failed")))
        assertTrue(t.record(event("check_failed", failure = "network")))
    }

    @Test
    fun download_failed_缺_failure_被拒且空白failure被拒() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertFalse(t.record(event("download_failed", kind = "apk")))
        assertFalse(t.record(event("download_failed", kind = "apk", failure = "  ")))
        assertTrue(t.record(event("download_failed", kind = "apk", sizeBytes = 100L, failure = "sha_mismatch")))
    }

    @Test
    fun 失败类别超长被拒() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertFalse(t.record(event("check_failed", failure = "x".repeat(41))))
        assertTrue(t.record(event("check_failed", failure = "x".repeat(40))))
    }

    // ------------------------------------------------------------------
    // 第 7 拍（U10）：assets_applied 的 outcome / kind / failure 三格矩阵
    // ------------------------------------------------------------------

    @Test
    fun assets_applied_成功档_必带档位且只收数据档三词() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        assertTrue(t.record(event("assets_applied", outcome = "success", kind = "block_delta")))
        assertTrue(t.record(event("assets_applied", outcome = "success", kind = "file_delta")))
        assertTrue(t.record(event("assets_applied", outcome = "success", kind = "full")))
        assertEquals("assets_applied 缺 outcome", t.validate(event("assets_applied")))
        // NONE 没有标签（映射给 null）⇒ 落进「成功档缺 kind」这一拒 ——
        // 「NONE 不发这一拍」的挡点就在 core 的 validate，app 侧不必再判一遍。
        assertEquals("assets_applied 成功档缺 kind", t.validate(event("assets_applied", outcome = "success")))
        // 产物词表（apk/assets）、动作词表（download）与 none 都进不了档位格：不同事件不同词表。
        for (bad in listOf("apk", "assets", "none", "download", "block-delta", "BLOCK_DELTA")) {
            assertEquals("assets_applied kind 非法：$bad", t.validate(event("assets_applied", outcome = "success", kind = bad)))
        }
        // 两档口径：cancelled 对「应用」不成立（冷启解包线程没有可取消的形态）。
        assertEquals("assets_applied outcome 非法：cancelled", t.validate(event("assets_applied", outcome = "cancelled")))
    }

    @Test
    fun assets_applied_失败档_failure_必填且走短标签严判据() {
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        // 失败档 kind 可缺：归档连打开都打不开时档位没探出来（BundleApply.Crashed.kind 可为 null）。
        assertTrue(t.record(event("assets_applied", outcome = "failed", failure = "rejected")))
        assertTrue(t.record(event("assets_applied", outcome = "failed", kind = "block_delta", failure = "retry-scheduled")))
        assertTrue(t.record(event("assets_applied", outcome = "failed", kind = "full", failure = "gave-up")))
        assertEquals("assets_applied 缺 failure", t.validate(event("assets_applied", outcome = "failed")))
        // 带斜杠/空格/冒号的原话（problem 那种形状）走 isStatusTag 字符集拒掉。
        for (bad in listOf("/data/user/0/x/files/a.zip", "apply failed: java.io.IOException x", "a b", "a".repeat(41).take(41))) {
            assertNotNull(t.validate(event("assets_applied", outcome = "failed", failure = bad)), "failure「$bad」居然被收下")
        }
        assertEquals(
            "assets_applied failure 非法：/tmp/problem.txt",
            t.validate(event("assets_applied", outcome = "failed", failure = "/tmp/problem.txt")),
        )
        // 通用那一档（空白/超长）先命中 —— 判序与 check_failed 同一口径，不许第 7 拍另立。
        assertEquals("失败类别空白", t.validate(event("assets_applied", outcome = "failed", failure = "  ")))
        assertEquals("失败类别超长", t.validate(event("assets_applied", outcome = "failed", failure = "x".repeat(41))))
        // 成功档不许蹭 failure；status 仍归 install_result 独占；force / sizeBytes 进不来。
        assertEquals("assets_applied 不允许 failure", t.validate(event("assets_applied", outcome = "success", kind = "full", failure = "rejected")))
        assertEquals(
            "assets_applied 不允许 status",
            t.validate(event("assets_applied", outcome = "failed", failure = "rejected", status = "5")),
        )
        assertEquals("assets_applied 不允许 force", t.validate(event("assets_applied", outcome = "success", kind = "full", force = true)))
        assertEquals(
            "assets_applied 不允许 sizeBytes",
            t.validate(event("assets_applied", outcome = "failed", failure = "rejected", sizeBytes = 1L)),
        )
    }

    @Test
    fun install_result_三档口径一字未动_与第7拍互不串门() {
        // U10 只给 assets_applied 开格；install_result 的三档/必填/短标签判据逐字保留（U6b 的钉子）。
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        for (outcome in listOf("success", "cancelled", "failed")) {
            assertTrue(t.record(event("install_result", outcome = outcome)))
        }
        assertEquals("install_result 缺 outcome", t.validate(event("install_result")))
        assertEquals("install_result outcome 非法：ok", t.validate(event("install_result", outcome = "ok")))
        assertEquals("install_result 只在 failed 档带 status", t.validate(event("install_result", outcome = "success", status = "0")))
        assertEquals("install_result status 非法：a b", t.validate(event("install_result", outcome = "failed", status = "a b")))
        // 两档词表互借不得：assets_applied 不收 cancelled，install_result 不受 kind 词表影响。
        assertEquals("assets_applied outcome 非法：cancelled", t.validate(event("assets_applied", outcome = "cancelled")))
        assertEquals("install_result 不允许 kind", t.validate(event("install_result", outcome = "success", kind = "full")))
    }

    @Test
    fun 档位映射表_逐字对齐枚举名_NONE与null无标签() {
        assertEquals("block_delta", UpdateTelemetry.dataArtifactKindLabel(DataArtifactKind.BLOCK_DELTA))
        assertEquals("file_delta", UpdateTelemetry.dataArtifactKindLabel(DataArtifactKind.FILE_DELTA))
        assertEquals("full", UpdateTelemetry.dataArtifactKindLabel(DataArtifactKind.FULL))
        assertNull(UpdateTelemetry.dataArtifactKindLabel(DataArtifactKind.NONE))
        assertNull(UpdateTelemetry.dataArtifactKindLabel(null))
        // 映射像集与词表集互逆相等：每个非 NONE 档恰有一个标签、标签恰落词表 ——
        // 枚举改名时这一条是唯一会先叫的证人（app 侧禁止自拼 lowercase，词表只有这一份真相）。
        val labeled = DataArtifactKind.values().filter { it != DataArtifactKind.NONE }
        assertEquals(
            UpdateTelemetry.KIND_DATA_ARTIFACT,
            labeled.map { UpdateTelemetry.dataArtifactKindLabel(it) ?: error("档位 $it 没有标签") }.toSet(),
        )
        for (kind in labeled) {
            assertEquals(kind.name.lowercase(), UpdateTelemetry.dataArtifactKindLabel(kind))
        }
        assertEquals(3, UpdateTelemetry.KIND_DATA_ARTIFACT.size)
    }

    @Test
    fun 坏输入零副作用且不污染后续合法事件() {
        val storage = FakeStorage()
        val fake = FakeTelemetry(storage, { 100L })
        val t = fake.telemetry
        // 连着几条坏输入：一条都不入缓冲、一次都不持久化、一次都不发
        assertFalse(t.record(event("check_started", kind = "apk")))
        assertFalse(t.record(event("offer_shown", force = true))) // 缺 kind
        assertFalse(t.record(event("no_such_event")))
        assertEquals(0, t.pendingCount())
        assertEquals(0, storage.saved.size)
        assertEquals(0, fake.sent.size)
        // 合法事件照常进来，且缓冲里只有这一条（坏输入没留下脏状态）
        assertTrue(t.record(event("check_ok")))
        assertEquals(1, t.pendingCount())
        assertEquals(1, storage.saved.size)
        assertEquals(1, storage.lines.size)
    }

    // ------------------------------------------------------------------
    // 批量：32 条 / 48h 先到先刷、失败不丢
    // ------------------------------------------------------------------

    private fun checkStarted(at: Long) = event("check_started", at = at)

    @Test
    fun `32条内不刷第33条触发整批刷`() {
        val storage = FakeStorage()
        val fake = FakeTelemetry(storage, { 100L })
        repeat(32) { i ->
            assertTrue(fake.telemetry.record(checkStarted(at = i + 1L)))
        }
        assertEquals(0, fake.sent.size)
        assertEquals(32, fake.telemetry.pendingCount())
        assertTrue(fake.telemetry.record(checkStarted(at = 999L)))
        assertEquals(1, fake.sent.size)
        assertEquals(33, fake.sent[0].size)
        assertEquals(0, fake.telemetry.pendingCount())
        // 发送成功后持久化清空
        assertEquals(emptyList(), storage.lines)
    }

    @Test
    fun 发送失败保留上限内的批_下次再试() {
        val storage = FakeStorage()
        val fake = FakeTelemetry(storage, { 100L })
        fake.sendResult = false
        repeat(33) { i -> assertTrue(fake.telemetry.record(checkStarted(at = i + 1L))) }
        assertEquals(1, fake.sent.size) // 试过一次但失败
        // L6 硬上限：33 条触发了那次失败尝试后，只保留最近 MAX_BUFFER 条（最旧 1 条被丢）。
        assertEquals(UpdateTelemetry.MAX_BUFFER, fake.telemetry.pendingCount())
        assertEquals(1L, fake.telemetry.droppedCount())
        assertEquals(UpdateTelemetry.MAX_BUFFER, storage.lines.size) // 持久化只剩保留窗
        fake.sendResult = true
        assertTrue(fake.telemetry.flushNow())
        assertEquals(2, fake.sent.size)
        assertEquals(UpdateTelemetry.MAX_BUFFER, fake.sent[1].size)
        assertEquals(0, fake.telemetry.pendingCount())
    }

    @Test
    fun `48h年龄先刷旧批再落新事件`() {
        val storage = FakeStorage()
        var now = 1_000_000L
        val fake = FakeTelemetry(storage, { now })
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        now += UpdateTelemetry.MAX_AGE_MILLIS
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        assertEquals(1, fake.sent.size)
        assertEquals(1, fake.sent[0].size) // 旧批只含旧事件
        assertEquals(1, fake.telemetry.pendingCount()) // 新事件留在缓冲
    }

    @Test
    fun `48h差1ms不刷_恰好等于上限才刷`() {
        val storage = FakeStorage()
        var now = 1_000_000L
        val fake = FakeTelemetry(storage, { now })
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        now += UpdateTelemetry.MAX_AGE_MILLIS - 1L
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        assertEquals(0, fake.sent.size) // 差 1ms：不刷
        assertEquals(2, fake.telemetry.pendingCount())
        now += 1L // 最旧一条此刻正好满 48h
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        assertEquals(1, fake.sent.size)
        assertEquals(2, fake.sent[0].size) // 旧批含前两条
        assertEquals(1, fake.telemetry.pendingCount()) // 新事件留缓冲
    }

    @Test
    fun 溢出刷后缓冲干净可重新攒() {
        val storage = FakeStorage()
        val fake = FakeTelemetry(storage, { 100L })
        repeat(33) { i -> assertTrue(fake.telemetry.record(checkStarted(at = i + 1L))) }
        assertEquals(1, fake.sent.size)
        assertEquals(0, fake.telemetry.pendingCount())
        // 刷成功后缓冲与持久化都清空：再记一条只攒这一条，旧批不会带回来
        assertTrue(fake.telemetry.record(checkStarted(at = 10_000L)))
        assertEquals(1, fake.telemetry.pendingCount())
        assertEquals(1, storage.lines.size)
    }

    @Test
    fun flushNow_空缓冲直接成功() {
        val fake = FakeTelemetry(FakeStorage(), { 100L })
        assertTrue(fake.telemetry.flushNow())
        assertEquals(0, fake.sent.size)
    }

    // ------------------------------------------------------------------
    // P2（L6）：硬上限丢最旧 + 失败冷却
    // ------------------------------------------------------------------

    @Test
    fun `发送失败时缓冲硬上限只保留最近N条且记丢因`() {
        val storage = FakeStorage()
        var now = 1_000_000L
        val fake = FakeTelemetry(storage, { now })
        fake.sendResult = false
        val total = 40
        repeat(total) {
            now += 1_000L
            assertTrue(fake.telemetry.record(checkStarted(at = now)))
        }
        // 缓冲有界：不多不少只留最近 MAX_BUFFER 条
        assertEquals(UpdateTelemetry.MAX_BUFFER, fake.telemetry.pendingCount())
        assertEquals(total - UpdateTelemetry.MAX_BUFFER.toLong(), fake.telemetry.droppedCount())
        assertNotNull(fake.telemetry.lastDropReason())
        // 持久化也只剩保留窗（不是整批旧账）
        assertEquals(UpdateTelemetry.MAX_BUFFER, storage.lines.size)
        // 保留的是**最近** 32 条：第一条 = 第 9 次写入（40 - 32 = 8 条被丢）
        val oldestKept = fake.telemetry.parseLine(storage.lines.first())
        assertNotNull(oldestKept)
        assertEquals(1_000_000L + 9L * 1_000L, oldestKept.atMillis)
        // 冷却窗内只有「第一次撞上限」那一次出站尝试，不会每条新事件都整批 POST
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `失败冷却窗内不重试_冷却到点后下一条写入才再试`() {
        val storage = FakeStorage()
        var now = 1_000_000L
        val fake = FakeTelemetry(storage, { now })
        fake.sendResult = false
        // 第 33 条触发第一次（也是唯一一次）失败尝试并开冷却窗。
        repeat(33) {
            now += 1_000L
            assertTrue(fake.telemetry.record(checkStarted(at = now)))
        }
        assertEquals(1, fake.sent.size)
        val failedAt = now
        // 冷却窗内继续写满：一次都不再试（否则端点长期不可达时就是每条事件一趟 POST）
        repeat(5) {
            now += 1_000L
            assertTrue(fake.telemetry.record(checkStarted(at = now)))
        }
        assertEquals(1, fake.sent.size)
        // 跨过冷却窗（恰好到点）之后的下一批：允许再一次整批尝试
        now = failedAt + UpdateTelemetry.FAILURE_COOLDOWN_MILLIS
        assertTrue(fake.telemetry.record(checkStarted(at = now)))
        assertEquals(2, fake.sent.size)
    }

    @Test
    fun `flushNow 不受失败冷却限制`() {
        val storage = FakeStorage()
        val fake = FakeTelemetry(storage, { 1_000L })
        fake.sendResult = false
        repeat(33) { i -> assertTrue(fake.telemetry.record(checkStarted(at = i + 1L))) }
        assertEquals(1, fake.sent.size)
        // 冷启/收尾那种低频补发位不受冷却约束：立刻再试一次（仍失败）
        assertFalse(fake.telemetry.flushNow())
        assertEquals(2, fake.sent.size)
        assertTrue(fake.telemetry.pendingCount() > 0)
        assertTrue(storage.lines.isNotEmpty(), "失败时盘上账不能清")
        // 成功那一发要顺手清盘：否则重启后已发出的事件会被再补一次（计数翻倍）
        fake.sendResult = true
        assertTrue(fake.telemetry.flushNow())
        assertEquals(0, fake.telemetry.pendingCount())
        assertEquals(emptyList(), storage.lines)
    }

    // ------------------------------------------------------------------
    // 持久化还原
    // ------------------------------------------------------------------

    @Test
    fun 启动还原_坏行跳过好行保留() {
        val storage = FakeStorage()
        val good = UpdateTelemetry(FakeStorage(), { 100L }, { true })
            .encode(event("check_ok", at = 123L))
        storage.lines = listOf(good, "不是 json", "{}", "null")
        val t = UpdateTelemetry(storage, { 100L }, { true })
        assertEquals(1, t.pendingCount())
    }

    @Test
    fun 启动还原_未知键的行整行拒() {
        val storage = FakeStorage()
        storage.lines = listOf("""{"event":"check_ok","at":123,"evil":1}""")
        val t = UpdateTelemetry(storage, { 100L }, { true })
        assertEquals(0, t.pendingCount())
    }

    // ------------------------------------------------------------------
    // 编码 round-trip
    // ------------------------------------------------------------------

    @Test
    fun 编码形状_空维度不出现() {
        val t = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        assertEquals(
            """{"event":"install_requested","at":1700000000000,"version":15}""",
            t.encode(event("install_requested")),
        )
        assertEquals(
            """{"event":"offer_shown","at":1700000000000,"version":15,"kind":"apk","force":true}""",
            t.encode(event("offer_shown", kind = "apk", force = true)),
        )
        assertEquals(
            """{"event":"download_failed","at":1700000000000,"version":15,"kind":"assets","sizeBytes":712345678,"failure":"sha_mismatch"}""",
            t.encode(event("download_failed", kind = "assets", sizeBytes = 712_345_678L, failure = "sha_mismatch")),
        )
        // install_result 的字段序固定在 failure 之后：outcome、status，null 的整个不出现。
        assertEquals(
            """{"event":"install_result","at":1700000000000,"version":15,"outcome":"success"}""",
            t.encode(event("install_result", outcome = "success")),
        )
        assertEquals(
            """{"event":"install_result","at":1700000000000,"version":15,"outcome":"failed","status":"-99"}""",
            t.encode(event("install_result", outcome = "failed", status = "-99")),
        )
        // 第 7 拍的字段序仍按既有顺序落（kind 在 version 之后、outcome 在 failure 之后），编码面零扩键。
        assertEquals(
            """{"event":"assets_applied","at":1700000000000,"version":15,"kind":"block_delta","outcome":"success"}""",
            t.encode(event("assets_applied", outcome = "success", kind = "block_delta")),
        )
        assertEquals(
            """{"event":"assets_applied","at":1700000000000,"version":15,"failure":"rejected","outcome":"failed"}""",
            t.encode(event("assets_applied", outcome = "failed", failure = "rejected")),
        )
    }

    @Test
    fun 编码解码round_trip_全部事件() {
        val t = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        val samples = listOf(
            event("check_started"),
            event("check_ok"),
            event("check_failed", failure = "network"),
            event("offer_shown", kind = "apk", force = false),
            event("offer_shown", kind = "assets", force = true),
            event("offer_action", kind = "download"),
            event("offer_action", kind = "skip"),
            event("offer_action", kind = "decline"),
            event("download_started", kind = "apk"),
            event("download_finished", kind = "apk", sizeBytes = 778_451_234L),
            event("download_cancelled", kind = "assets"),
            event("download_failed", kind = "assets", sizeBytes = 1L, failure = "disk_full"),
            event("install_requested"),
            event("install_result", outcome = "success"),
            event("install_result", outcome = "cancelled"),
            event("install_result", outcome = "failed", status = "6"),
            // 第 7 拍（U10）的新组合：编码面不扩键（outcome/kind/failure 三格早已存在），钉 round-trip。
            // 裸的 assets_applied（旧形状、无 outcome）从第 7 拍起是非法事件，不再进 round-trip。
            event("assets_applied", outcome = "success", kind = "full"),
            event("assets_applied", outcome = "failed", failure = "rejected"),
            event("assets_applied", outcome = "failed", kind = "block_delta", failure = "gave-up"),
        )
        for (sample in samples) {
            assertEquals(null, t.validate(sample))
            val line = t.encode(sample)
            assertEquals(sample, t.parseLine(line), "round-trip 失败：$line")
        }
    }

    @Test
    fun 编码转义_kind带引号反斜杠round_trip() {
        val t = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        // kind 的合法集是白名单，转义面留给 failure（同为字符串维度）
        val weird = event("check_failed", failure = "a\"b\\c\nd")
        val line = t.encode(weird)
        assertTrue(line.contains("\\\""), "引号必须被转义：$line")
        assertEquals(weird, t.parseLine(line))
    }

    @Test
    fun 十二个事件名齐_与契约一致() {
        assertEquals(
            listOf(
                "check_started", "check_ok", "check_failed",
                "offer_shown", "offer_action",
                "download_started", "download_finished", "download_cancelled", "download_failed",
                "install_requested", "install_result", "assets_applied",
            ),
            UpdateTelemetry.EVENT_NAMES,
        )
    }

    @Test
    fun 白名单之外的事件名仍然一条不收() {
        // 开 install_result 这一口**没有**把校验放宽成「未知也收」：白名单闸仍是 validate 的第一行。
        val t = FakeTelemetry(FakeStorage(), { 100L }).telemetry
        for (name in listOf("installResult", "install_results", "install_outcome", "result", "", "INSTALL_RESULT")) {
            val event = UpdateTelemetry.Event(name, 1_700_000_000_000L)
            assertEquals("未知事件名 $name", t.validate(event))
            assertFalse(t.record(event), "$name 竟然进了缓冲")
        }
        assertEquals(0, t.pendingCount())
        // 带着合法新维的凭空事件名也一样拒：维的合法性不许反过来替事件名发通行证。
        val dressed = UpdateTelemetry.Event("install_result_v2", 1_700_000_000_000L, outcome = "success", status = "5")
        assertEquals("未知事件名 install_result_v2", t.validate(dressed))
        assertFalse(t.record(dressed))
        assertEquals(0, t.pendingCount())
    }
}
