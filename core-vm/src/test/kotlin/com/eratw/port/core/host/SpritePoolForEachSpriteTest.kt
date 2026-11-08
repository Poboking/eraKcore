package com.eratw.port.core.host

import com.eratw.port.core.diag.LiveSetCensus
import com.eratw.port.core.host.SpritePool.Frame
import com.eratw.port.core.host.SpritePool.Sprite
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **census3 波（2026-11-05）—— 回标题路径抽掉现搭的名字集合**。
 *
 * 病灶：`ProcessRuntime.unloadGraphicListForGotoTitle` 旧形状是
 * `for (name in pool.names()) { pool.find(name) ?: continue ... }` —— 每一发在回标题相位
 * 先物化一份按登记数长的 `Set<String>`（`SpritePool.names()` 的 `keys.toSet()`），再逐名
 * `uppercase()` 造查表键、走第二遍 `HashMap.get`，而循环体只要 sprite 本身。与真机报告
 * `6c08e8bb` 簇（贴顶相位自己现搭哈希表）同族。
 *
 * 本文件钉 [SpritePool.forEachSprite] 三件事：
 * 1. **等价臂**：每个 sprite 恰好回调一次、顺序 = 登记序（旧口 `keys.toSet()` 复制的是同一张
 *    `LinkedHashMap` 的迭代序）、回调拿到的是池里**同一个对象**（`===`），静态/动画/资源三类都在；
 * 2. **零分配臂**：n = 20,000 时整发流式遍历的线程累计分配 < 2,048 B（HotSpot
 *    `com.sun.management.ThreadMXBean`，非 HotSpot 按 `assumeTrue` 跳过，装置与
 *    `RefBufferGuardFaceTest` / `SpriteEvictScratchTest` 同款），而同规模旧形状
 *    （`names()` + 逐名 `find`）实测 > 100,000 B —— 这是「没建快照」的量化证据；
 * 3. **锁臂**：遍历期间池锁由调用线程持有 ⇒ 另一线程的 `names()` / `find()` 被挡在锁外，
 *    而回调体内再调本池读方法是安全的（`synchronized` 可重入）。
 *
 * **不重复造的那一半**：`ProcessRuntime.unloadGraphicListForGotoTitle` 走完后的终态
 * （静态 sprite `isCreated` 转 false、动画 sprite 保持 true、登记项仍在、`cbgList` 不动）已由
 * `core/proc/GotoTitleTest` 的用例「gotoTitle 卸掉图形缓冲但保留 sprite 登记与 cbg 图层」
 * 逐条钉住（`core-vm/src/test/kotlin/com/eratw/port/core/proc/GotoTitleTest.kt:262-306`）；
 * 本波只把那一句循环换成流式口，动作体逐字未动 ⇒ 该用例继续覆盖 ③，本文件不重造。
 */
class SpritePoolForEachSpriteTest {

    private val side = 2

    /** 装置尺寸：2×2 像素、16 字节驻留 —— 判据是逐条回调，与真实图同形。 */
    private fun sheet(id: Int): GraphicsBuffer = GraphicsBuffer(id).apply { create(side, side) }

    // ------------------------------------------------------------------
    // 1 等价臂
    // ------------------------------------------------------------------

    @Test
    fun `流式口每条恰好回调一次且顺序与登记序一致`() {
        val pool = SpritePool()
        val staticBase = sheet(1)
        val animeSheet = sheet(2)
        val resourceBase = sheet(3)
        pool.put(Sprite("A_STATIC", side, side, staticBase))
        pool.put(
            Sprite(
                name = "B_ANIME", width = side, height = side,
                frames = mutableListOf(
                    Frame(animeSheet, 0, 0, side, side, 0, 0, 100),
                    Frame(animeSheet, 0, 0, side, side, 0, 0, 100),
                ),
            ),
        )
        pool.putResource(Sprite("C_RESOURCE", side, side, resourceBase))
        // 小写原文名：表键归一大写（`put` 的 KDoc），回调拿到的应是登记时的原文名。
        pool.put(Sprite("d_lower", side, side, sheet(4)))
        // 同表覆盖（`put` 语义是覆盖，不新增条目）。
        pool.put(Sprite("A_STATIC", side, side, staticBase))

        val visited = ArrayList<Sprite>()
        pool.forEachSprite { visited.add(it) }

        assertEquals(4, pool.size(), "覆盖登记不新增条目")
        assertEquals(pool.names().toList(), visited.map { it.name.uppercase() }, "每条恰好一次、顺序 = 登记序")
        assertEquals(visited.size, visited.map { it.name.uppercase() }.toSet().size, "不许重复回调")
        // 回调拿到的是池里同一个对象，不是按名重建的副本
        for (sprite in visited) {
            val found = pool.find(sprite.name)
            assertNotNull(found, "按名能查回 ${sprite.name}")
            assertSame(found, sprite, "流式口交出的就是池里那一枚（${sprite.name}）")
        }
        assertTrue(visited.any { it.isAnime }, "动画 sprite 在遍历面里")
        assertTrue(visited.any { !it.isAnime }, "静态 sprite 在遍历面里")
        assertEquals("d_lower", visited.single { it.name.uppercase() == "D_LOWER" }.name, "交出的是原文名")
    }

    // ------------------------------------------------------------------
    // 2 零分配臂
    // ------------------------------------------------------------------

    private fun requireAllocBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        assumeTrue(
            bean.getThreadAllocatedBytes(Thread.currentThread().id) != -1L,
            "线程分配计数未开启，跳过分配读数钉",
        )
        return bean
    }

    /** n 个具名运行期 Sprite，各指一张独立缓冲（回标题现场：`SPRITECREATE` 钉住的整张表）。 */
    private fun buildPool(n: Int): SpritePool {
        val pool = SpritePool()
        for (i in 0 until n) pool.put(Sprite("GOTO_$i", side, side, sheet(i + 1)))
        return pool
    }

    @Test
    fun `零分配臂：流式口不建快照，旧 names 加 find 形状现搭整张表`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 20_000
        val pool = buildPool(n)

        // 回调体只读（计数器持有者已在 lambda 外，避免把捕获壳算进读数）。
        var visited = 0
        val counter: (Sprite) -> Unit = { visited++ }

        // 预热两遍：JIT 认路、迭代器类先加载（稳态读数才是本簇要的判据）。
        pool.forEachSprite(counter)
        pool.names()

        visited = 0
        val beforeReused = bean.getThreadAllocatedBytes(tid)
        pool.forEachSprite(counter)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        // 被替换掉的旧形状，逐字对着原调用点抄：`for (name in pool.names()) pool.find(name)`
        var oldVisited = 0
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        for (name in pool.names()) {
            if (pool.find(name) != null) oldVisited++
        }
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("census3.foreach n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(n, visited, "流式口每条恰好一次（预热臂已归零）")
        assertEquals(n, oldVisited, "两臂访问到的条目数一致")
        assertTrue(
            reusedDelta < 2_048L,
            "流式臂稳态零分配：$n 条目整发实测 $reusedDelta 字节（只应有一个迭代器）",
        )
        assertTrue(
            freshDelta > 100_000L,
            "旧形状同样是贴着这条链的一发：$n 条目实测分配 $freshDelta 字节（名字集合 + 每个查表键）",
        )
    }

    // ------------------------------------------------------------------
    // 4 普查透传臂
    // ------------------------------------------------------------------

    /**
     * `LiveSetCensus.census(..., residentFace)` 的 additive 口（census3 波第二笔）：
     * 面**只换分配**，`csvResidentPixelBytes` 与缺省臂逐字相同；面真的被消费（`guardCount`
     * 恰好等于去重后的父图张数）；面臂的整发分配比缺省臂小一个数量级以上（缺省臂每发在池锁内
     * 现搭一张按资源表登记数长的 `HashSet`，正是普查的两个消费点落在贴顶相位时不该做的事）。
     */
    @Test
    fun `普查透传面只换分配，读数与缺省臂逐字相同`() {
        val n = 5_000
        val pool = SpritePool()
        for (i in 0 until n) pool.putResource(Sprite("R_$i", side, side, sheet(10_000 + i)))

        val noFace = LiveSetCensus.census(null, null, null, null, pool, fullScan = false)
        val face = SpriteEvictScratch()
        val withFace = LiveSetCensus.census(null, null, null, null, pool, fullScan = false, residentFace = face)

        assertEquals(n * side * side * 4L, noFace.csvResidentPixelBytes, "缺省臂读数 = 去重父图尺寸算式")
        assertEquals(noFace.csvResidentPixelBytes, withFace.csvResidentPixelBytes, "两臂读数逐字相同（面只换分配）")
        assertEquals(n, face.guardCount(), "面确实被面臂消费：去重后的父图张数")
        assertEquals(-1L, withFace.gfxResidentPixelBytes, "null 池的项仍折 -1（口径没被本口带歪）")

        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val faced = { LiveSetCensus.census(null, null, null, null, pool, fullScan = false, residentFace = face) }
        val unfaced = { LiveSetCensus.census(null, null, null, null, pool, fullScan = false) }
        faced()
        unfaced() // 预热

        val beforeFaced = bean.getThreadAllocatedBytes(tid)
        faced()
        val facedDelta = bean.getThreadAllocatedBytes(tid) - beforeFaced
        val beforeUnfaced = bean.getThreadAllocatedBytes(tid)
        unfaced()
        val unfacedDelta = bean.getThreadAllocatedBytes(tid) - beforeUnfaced

        println("census3.census n=$n faced=${facedDelta}B unfaced=${unfacedDelta}B")
        assertTrue(
            facedDelta * 10 < unfacedDelta,
            "面臂整发 $facedDelta 字节应远小于缺省臂 $unfacedDelta 字节（后者现搭整张 HashSet）",
        )
    }

    // ------------------------------------------------------------------
    // 3 锁臂
    // ------------------------------------------------------------------

    @Test
    fun `遍历持有池锁，外线程被挡而回调内可重入读池`() {
        val n = 8
        val pool = buildPool(n)
        val attempting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val started = AtomicReference<Thread?>(null)
        val rounds = 200

        // 外线程在**第一个回调内**启动：此时池锁已在本线程手里 ⇒ 「它被挡着」是结构性成立的，
        // 不靠调度运气（上一版在遍历前 start，弱线程可能先跑完 200 轮，读数反向）。
        fun startHammer() {
            val other = Thread {
                try {
                    attempting.countDown()
                    repeat(rounds) {
                        val names = pool.names()
                        // 快照必须是「全部 n 条」——遍历期间池不改结构，读不到半张表。
                        check(names.size == n) { "names() 读到 ${names.size} 条，期望 $n" }
                        check(pool.find("GOTO_0") != null) { "find 丢失 GOTO_0" }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    finished.countDown()
                }
            }
            started.set(other)
            other.start()
        }

        var callbacks = 0
        pool.forEachSprite { sprite ->
            callbacks++
            assertEquals(side, sprite.width, "回调体内读 sprite 字段")
            // 可重入：同一把 `this` 监视器，回调里再调本池读方法不该自锁。
            assertEquals(n, pool.names().size, "回调内 names() 重入可读")
            assertEquals(sprite.name.uppercase(), pool.find(sprite.name)?.name?.uppercase(), "回调内 find 重入可读")
            if (callbacks == 1) {
                startHammer()
                attempting.await()
                val deadline = System.nanoTime() + 300_000_000L
                while (System.nanoTime() < deadline && finished.count == 0L) Thread.onSpinWait()
                assertEquals(1L, finished.count, "遍历持锁期间外线程的 names()/find() 不得完成")
            }
        }
        started.get()?.join(5_000)

        assertEquals(n, callbacks, "锁臂同样每条一次")
        assertEquals(0L, finished.count, "放锁之后外线程跑完")
        assertNull(failure.get(), "外线程在那 $rounds 轮里没有异常/半张表读数")
    }
}
