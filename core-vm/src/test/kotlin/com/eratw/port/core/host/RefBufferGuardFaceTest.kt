package com.eratw.port.core.host

import com.eratw.port.core.host.SpritePool.Frame
import com.eratw.port.core.host.SpritePool.Sprite
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **refbuffers 波（2026-11-05）—— 贴顶名单换复用护栏面**。
 *
 * 病灶：`GameHost.releaseUnusedGraphics` 每一发都在**堆 ≥80%/≥92% 的贴顶相位**现搭一张
 * 「不许动」名单 —— [SpritePool.referencedBuffers] 里那个按
 * `sprites.size*2 + resourceSprites.size*2 + 1` 现开的 `HashSet`（三万量级 Sprite 就是
 * 一张 MB 级引用数组 + 每条目一个 32–40 B 的 `HashMap.Node`），外加 CBG 图层那一个。
 * 自救路径自己现搭哈希表，与真机报告 `6c08e8bb` 簇的抛点（回收闸内的 `HashMap.resize`）同族。
 * oomsrc2 已把同族的三个逐出闸接上面，这一发的护栏是 `Set` 契约 ⇒ 传不进
 * `SpriteEvictScratch` ⇒ 本波在 core 侧开三个口：[SpritePool.addReferencedGuards]、
 * [GraphicsPool.releaseUnused] 的护栏面版、[SpritePool.evictCompositedSpritesWithinBudget]
 * 的护栏面版。
 *
 * 本文件钉三件事：
 * 1. **等价臂**：护栏面的成员判定与旧 `Set` 逐字同（名单同集 / `releaseUnused` 的读数与
 *    逐出集同 / 合成图档的读数与摘名集同）—— 判停与保护口径一条没动；
 * 2. **两臂字节读数**：贴顶规模（n=20,000 与 n=10,000，量级照 oomsrc2 的两发现场）下
 *    「不接这一发 = 现搭名单」与「接这一发 = 复用面」的实测字节，用 HotSpot
 *    `com.sun.management.ThreadMXBean` 的线程累计分配（非 HotSpot 按 assumeTrue 跳过，
 *    与 [SpriteEvictScratchTest] / [SpriteEvictSiteScratchTest] 同款装置）；
 * 3. **别名闸**：护栏面与记账面是同一块对象时闸拒绝（`owned.clear()` 会先把宿主名单蒸发）。
 *
 * **摘除臂的做法**（破坏性验证，本文件不自带开关）：把 [SpritePool.addReferencedGuards] 的
 * 装载目标换成闸内 `val owned = SpriteEvictScratch()`（恒现搭一块面）⇒ 下面两条修复臂
 * （`reused` 读数 < 2 048 B）变红，等价臂里「名单进的是调用方的面」那条也变红；复原复绿。
 *
 * 真机（贴顶堆）读数本文件给不出（`adb devices` 空，见交付 TODO(verify)）；
 * 本文件全部字节读数都是**桌面装置读数**（3g 测试挡 + HotSpot 分配计数）。
 */
class RefBufferGuardFaceTest {

    /** 装置尺寸：2×2 像素、16 字节驻留 —— 判据是字节算式，与真实图同形。 */
    private val side = 2
    private val pixelsPerSheet = (side * side).toLong()
    private val bytesPerSheet = pixelsPerSheet * 4L
    private val keepCount = 3
    private val reapTotal = 10

    /** 站外预建的 CBG 图层引用（**不许**在计时区内 new：那会污染两臂读数）。 */
    private val cbgSheetId = 9_100

    private fun sheet(id: Int): GraphicsBuffer = GraphicsBuffer(id).apply { create(side, side) }

    // ------------------------------------------------------------------
    // 1 等价臂
    // ------------------------------------------------------------------

    @Test
    fun `护栏面名单与 referencedBuffers 逐字同集`() {
        val static = sheet(50)
        val animeSheet = GraphicsBuffer(51).apply { create(4, 4) }
        val shared = sheet(52)
        val unreferenced = sheet(53)
        val pool = SpritePool()
        pool.put(Sprite("S_STATIC", side, side, static))
        pool.put(
            Sprite(
                name = "S_ANIME", width = 4, height = 4,
                frames = mutableListOf(
                    Frame(animeSheet, 0, 0, 2, 2, 0, 0, 100),
                    Frame(animeSheet, 2, 0, 2, 2, 0, 0, 100),
                ),
            ),
        )
        pool.put(Sprite("S_DUP_A", side, side, shared))
        pool.put(Sprite("S_DUP_B", side, side, shared))
        pool.putResource(Sprite("R_SHARED", side, side, shared))
        val candidates = listOf(static, animeSheet, shared, unreferenced)

        val refs = pool.referencedBuffers()
        val face = SpriteEvictScratch()
        pool.addReferencedGuards(face)

        // 旧口径的规模读数（宿主 trim 日志那句「保留 N 个在引用中的缓冲」吃它）
        assertEquals(refs.size, face.guardCount(), "名单规模读数逐字一致")
        assertEquals(3, refs.size, "装置：静态基图 + 动画父图 + 共享缓冲，去重后三张")
        for (buffer in candidates) {
            assertEquals(buffer in refs, face.containsGuard(buffer), "成员判定同值：id=${buffer.id}")
        }
        assertTrue(face.containsGuard(static), "静态基图")
        assertTrue(face.containsGuard(animeSheet), "动画帧的父图（两帧同一张 ⇒ 只数一次）")
        assertTrue(face.containsGuard(shared), "两名字/两表共享的那一张")
        assertFalse(face.containsGuard(unreferenced), "没被指着的不进名单")

        // 追加装载不清空：宿主的用法是「先并 CBG、再并 Sprite」
        val extra = sheet(54)
        face.addGuard(extra)
        assertEquals(4, face.guardCount(), "只追加、不重置（与旧 Set 的 += 同一口径）")
        assertTrue(face.containsGuard(extra))
    }

    @Test
    fun `releaseUnused 吃面与吃集合的读数和逐出集逐字一致`() {
        for (budget in listOf(null, bytesPerSheet * 2L)) {
            val a = buildReapSite()
            val b = buildReapSite()
            val setResult = a.gfx.releaseUnused(a.sprites.referencedBuffers(), minPixels = 1, budgetBytes = budget)
            val face = SpriteEvictScratch()
            b.sprites.addReferencedGuards(face)
            val faceResult = b.gfx.releaseUnused(face, minPixels = 1, budgetBytes = budget)

            assertEquals(setResult, faceResult, "ReleaseResult 逐字一致（budget=$budget）")
            assertTrue(setResult.buffers > 0, "装置：这一臂确实收到过东西（budget=$budget）")
            for (id in 1..reapTotal) {
                assertEquals(
                    a.gfx.peek(id)?.isCreated,
                    b.gfx.peek(id)?.isCreated,
                    "gid=$id 的处置两臂同结果（budget=$budget）",
                )
            }
        }
    }

    @Test
    fun `合成图档吃护栏面与吃集合的读数和摘名集逐字一致`() {
        val n = 40
        val a = buildCompositedSite(n)
        val b = buildCompositedSite(n)
        val resident = bytesPerSheet * (n + 1)

        val setResult = a.sprites.evictCompositedSpritesWithinBudget(resident, 0L, setOf(a.cbg))
        val guards = SpriteEvictScratch()
        guards.addGuard(b.cbg)
        val scratch = SpriteEvictScratch()
        val faceResult = b.sprites.evictCompositedSpritesWithinBudget(resident, 0L, guards, scratch)

        assertEquals(setResult, faceResult, "读数逐字一致（预算 0 ⇒ 候选穷尽才停）")
        assertEquals(n, faceResult.sprites, "装置：除 CBG 那张外全摘")
        assertEquals(pixelsPerSheet * n, faceResult.pixels, "释放条数 = 摘掉的 n 张 × 每张像素数")
        assertEquals(a.sprites.names(), b.sprites.names(), "剩下的名字集合逐字相同")
        assertEquals(bytesPerSheet, a.cbg.pixelBytes, "旧臂：CBG 那张驻留不动")
        assertEquals(bytesPerSheet, b.cbg.pixelBytes, "新臂：CBG 那张驻留不动（护栏面真被闸用了）")
        assertTrue(scratch.containsGuard(b.cbg), "宿主名单被抄进闸内护栏面（copyGuardsFrom 生效）")
        assertEquals(1, scratch.guardCount(), "资源表为空 ⇒ 闸内护栏面里只有宿主那一张")
    }

    @Test
    fun `护栏面与记账面同一块时闸拒绝`() {
        val site = buildCompositedSite(2)
        val resident = bytesPerSheet * 3
        val same = SpriteEvictScratch()
        same.addGuard(site.cbg)
        val error = assertFailsWith<IllegalArgumentException> {
            site.sprites.evictCompositedSpritesWithinBudget(resident, 0L, same, same)
        }
        assertTrue("protectedGuards" in error.message.orEmpty(), "报错要说清是哪两块：${error.message}")
        assertEquals(3, site.sprites.size(), "抛掷在遍历之前 ⇒ 一个名字都没摘")
        assertTrue(site.cbg.isCreated, "抛掷那一发一张都没 dispose（宁可不做也不炸）")
    }

    @Test
    fun `copyGuardsFrom 并的是身份不是序也不重复计数`() {
        val source = SpriteEvictScratch()
        val target = SpriteEvictScratch()
        val a = sheet(60)
        val b = sheet(61)
        val c = sheet(62)
        source.addGuard(a)
        source.addGuard(b)
        target.addGuard(b)
        target.addGuard(c)

        target.copyGuardsFrom(source)
        assertEquals(3, target.guardCount(), "并集去重（b 两臂都有 ⇒ 只数一次）")
        for (buffer in listOf(a, b, c)) assertTrue(target.containsGuard(buffer), "id=${buffer.id}")
        assertEquals(2, source.guardCount(), "只读对方，不改对方")

        target.copyGuardsFrom(target)
        assertEquals(3, target.guardCount(), "自并无事可做（同对象当场短路）")

        target.clear()
        assertEquals(0, target.guardCount(), "clear 把两张表都清（面本体既有口径不动）")
        assertFalse(target.containsGuard(a))
    }

    // ------------------------------------------------------------------
    // 2 两臂字节读数
    // ------------------------------------------------------------------

    @Test
    fun `名单装载 n=20000 现搭集合一臂 MB 级、复用面一臂稳态零分配`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 20_000
        val sprites = buildReferencingSprites(n)
        val cbgBuffer = sheet(cbgSheetId)
        val face = SpriteEvictScratch()
        val cbg = SpriteEvictScratch()

        // 修复臂：同一组面第二次装载（预装填把护栏表长到水位）—— 宿主页档就是这条臂
        loadIntoFaces(sprites, cbgBuffer, face, cbg)
        val beforeReused = bean.getThreadAllocatedBytes(tid)
        loadIntoFaces(sprites, cbgBuffer, face, cbg)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        // 复现臂：接线前的调用形状（CBG 现搭一张 + referencedBuffers 现搭一张 + 再并一张）
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val exclude = loadIntoSets(sprites, cbgBuffer)
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("refbuffers.name n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(n + 1, face.guardCount(), "修复臂：n 张 Sprite 引用 + 1 张 CBG 全进护栏面")
        assertEquals(exclude.size, face.guardCount(), "两臂名单规模同 ⇒ 成员判定同口径")
        assertTrue(
            reusedDelta < 2_048L,
            "修复臂：复用护栏面装载 $n 张名单稳态零分配（实测 $reusedDelta 字节）—— " +
                "GameHost.releaseUnusedGraphics 接这一发后就是这条臂",
        )
        assertTrue(
            freshDelta > 1_000_000L,
            "复现臂：不接这一发时名单装载实测只分配了 $freshDelta 字节（$n 条目）—— " +
                "贴顶堆（<1% free，报告 6c08e8bb-0 现场）上这笔 MB 级分配就是压垮点",
        )
        // 装置回收：这两发各造两万级缓冲，而同批内存钉共用**一个** test worker（3g 堆整条闸共用，
        // `core-vm/build.gradle.kts` 的注释就写着「靠两条路径之间的 System.gc() 错开」）；
        // 用完立刻主动回收，免得把峰值工作集顶到后面 `SpritePoolBudgetTest` 的整图解码那一发上
        // （2026-11-05 实测：不回收时 worker 在 stop 阶段 OOM heap space，同批 SpritePool* 两类
        // 的 XML 直接没落盘）。
        System.gc()
    }

    @Test
    fun `整发 n=10000 贴顶形状 现搭一臂与接面一臂的字节读数`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 10_000
        val gfx = GraphicsPool()
        val sprites = SpritePool()
        for (i in 1..n) {
            val buffer = gfx.get(i)
            buffer.create(side, side)
            sprites.put(Sprite("OUT_$i", side, side, buffer))
        }
        // CBG 那一半用「池里但没有具名 Sprite 指着」的一张（id=n+1）—— 与宿主现场同形：
        // 它只靠名单的 CBG 那一半护住。站外预建，绝不在计时区内 new 缓冲。
        val cbgBuffer = gfx.get(n + 1).apply { create(side, side) }
        val face = SpriteEvictScratch()
        val cbg = SpriteEvictScratch()

        // 修复臂整发：护栏面装载 → releaseUnused(面)。全部被名单护住 ⇒ 一张都不 dispose ⇒ 可反复测。
        fullPassWithFaces(sprites, gfx, cbgBuffer, face, cbg)
        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reusedResult = fullPassWithFaces(sprites, gfx, cbgBuffer, face, cbg)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        // 复现臂整发：接线前的形状（现搭 HashSet 名单 → releaseUnused(Set)）
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val freshResult = fullPassWithSets(sprites, gfx, cbgBuffer)
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("refbuffers.fullpass n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(freshResult, reusedResult, "整发读数逐字一致（本装置恒 0：全被名单护住）")
        assertEquals(0, reusedResult.buffers)
        assertEquals(n + 1, face.guardCount(), "两臂名单规模同（n 张 Sprite + 1 张 CBG）")
        assertTrue(
            reusedDelta < 2_048L,
            "修复臂：名单装载 + 回收闸整发稳态零分配（$n 条目实测 $reusedDelta 字节）",
        )
        assertTrue(
            freshDelta > 100_000L,
            "复现臂：整发现搭名单实测只分配了 $freshDelta 字节（$n 条目）—— 同一条贴顶链上的自造分配",
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** n 个具名运行期 Sprite，各指一张独立缓冲（贴顶现场的形状：全被 `SPRITECREATE` 钉住）。 */
    private fun buildReferencingSprites(n: Int): SpritePool {
        val pool = SpritePool()
        for (i in 0 until n) pool.put(Sprite("OUT_$i", side, side, sheet(i + 1)))
        return pool
    }

    /**
     * 接面后的装载形状（对着 `GameHost.releaseUnusedGraphics` 抄）：CBG 面灌一次、
     * 并集面先抄 CBG 再并入 Sprite 名单。面在计时区外已长到水位 ⇒ 本函数应当零新建。
     */
    private fun loadIntoFaces(
        sprites: SpritePool,
        cbgBuffer: GraphicsBuffer,
        face: SpriteEvictScratch,
        cbg: SpriteEvictScratch,
    ) {
        cbg.clear()
        cbg.addGuard(cbgBuffer)
        face.clear()
        face.copyGuardsFrom(cbg)
        sprites.addReferencedGuards(face)
    }

    /** 接线前的装载形状（逐字对着被替换掉的那三段抄）：两张现搭 `HashSet` 并一张。 */
    private fun loadIntoSets(sprites: SpritePool, cbgBuffer: GraphicsBuffer): Set<GraphicsBuffer> {
        val cbgProtected = HashSet<GraphicsBuffer>()
        cbgProtected += cbgBuffer
        val exclude = HashSet<GraphicsBuffer>()
        exclude += sprites.referencedBuffers()
        exclude += cbgProtected
        return exclude
    }

    private fun fullPassWithFaces(
        sprites: SpritePool,
        gfx: GraphicsPool,
        cbgBuffer: GraphicsBuffer,
        face: SpriteEvictScratch,
        cbg: SpriteEvictScratch,
    ): GraphicsPool.ReleaseResult {
        loadIntoFaces(sprites, cbgBuffer, face, cbg)
        return gfx.releaseUnused(face, minPixels = 1, budgetBytes = null)
    }

    private fun fullPassWithSets(sprites: SpritePool, gfx: GraphicsPool, cbgBuffer: GraphicsBuffer) =
        gfx.releaseUnused(loadIntoSets(sprites, cbgBuffer), minPixels = 1, budgetBytes = null)

    /** `releaseUnused` 两臂共用的现场：keepCount 张被具名 Sprite 指着，其余无人引用。 */
    private class ReapSite(val gfx: GraphicsPool, val sprites: SpritePool)

    private fun buildReapSite(): ReapSite {
        val gfx = GraphicsPool()
        val sprites = SpritePool()
        for (id in 1..reapTotal) {
            val buffer = gfx.get(id)
            buffer.create(side, side)
            if (id <= keepCount) sprites.put(Sprite("S$id", side, side, buffer))
        }
        return ReapSite(gfx, sprites)
    }

    /** 合成图档现场：n 个具名合成 Sprite（候选）+ 一张 CBG 图层正指着的名字（护栏）。 */
    private class CompositedSite(val sprites: SpritePool, val cbg: GraphicsBuffer)

    private fun buildCompositedSite(n: Int): CompositedSite {
        val pool = SpritePool()
        for (i in 0 until n) pool.put(Sprite("OUT_$i", side, side, sheet(i + 1)))
        val cbgBuffer = sheet(9_001)
        pool.put(Sprite("CBG_PINNED", side, side, cbgBuffer))
        return CompositedSite(pool, cbgBuffer)
    }

    private fun requireAllocBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        assumeTrue(
            bean.getThreadAllocatedBytes(Thread.currentThread().id) != -1L,
            "线程分配计数未开启，跳过分配读数钉",
        )
        return bean
    }
}
