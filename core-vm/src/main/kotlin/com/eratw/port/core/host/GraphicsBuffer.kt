package com.eratw.port.core.host

import com.eratw.port.core.diag.LockWatch
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.image.DemotedPngPixels
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 图形缓冲 / Sprite / CBG 的软件实现（纯 JVM，无 Android / AWT 依赖）。
 *
 * 对应原版 `MinorShift.Emuera.UI.Game.Image` 包：
 * - [GraphicsBuffer]  ≈ `GraphicsImage`（`Bitmap` + `Graphics` + 画笔/画刷/字体状态），
 *   像素是 ARGB IntArray，替代 `System.Drawing.Bitmap` 的 `Format32bppArgb`。
 * - [GraphicsPool]    ≈ `AppContents.gList`（`ConcurrentDictionary<int, GraphicsImage>`）。
 * - [SpritePool]      ≈ `AppContents.imageDictionary` + `resourceImageDictionary`。
 * - [CbgPool]         ≈ `EmueraConsole.cbgList` / `cbgButtonMap`（背景图与按钮图层）。
 *
 * 像素布局：`0xAARRGGBB`（与 `Color.ToArgb()` 同构）。原版查询颜色时做
 * `ToArgb() & 0xFFFFFFFF`（FunctionMethodCreator.cs:6189,6191,6255），即把 Int 当无符号 32 位；
 * 函数层用 [unsignedArgb] 完成该转换。
 *
 * 绘制保证「返回码 + 状态自洽」，并对真正落进缓冲的绘制（`GDRAWG` / `GDRAWSPRITE` 的
 * 贴图合成，总账 GFX-01；`GDRAWTEXT` 的替代字形，总账 GFX-08）给出可断言的像素；
 * 不做抗锯齿 / 字体栅格化（契约 §4 A4）：`GDRAWTEXT` 落的是「推进盒」而不是字形轮廓，
 * 口径与接线项见 `core/fn/GraphicsMethods.kt` 里 GDRAWTEXT 的 KDoc。
 */

/** ARGB 颜色转无符号 32 位（原版 `ToArgb() & 0xFFFFFFFF`）。 */
fun unsignedArgb(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

/**
 * 宿主侧**二进制写**能力（GSAVE 落真 PNG 的通道）。
 *
 * 为什么不是 [FileOps] 的成员：[FileOps] 只声明文本读写（`readText` / `writeText`），
 * 写出来的字节恒是 UTF-8 正文（`RootedFileOps.writeText`，`core/proc/BootHosts.kt`），
 * PNG 是二进制容器，经文本通道必然损坏。二进制**读**侧早有独立通道
 * （`BuiltinHost.readFileBytes`），本接口补的是对称的写侧。
 *
 * ## 接线口径
 *
 * 由宿主的 [FileOps] 实现**顺带实现**本接口（`as?` 判定，不做强制）：
 * - `RootedFileOps`：按它自己的 `resolvePath` 落在注入根内、建父目录、`Files.write`；
 * - `WriteBlockedFileOps`（`core/proc/ConsoleBuiltinHost.kt`）：写闸关着时返回 false，
 *   开着时转发给可写根的 `RootedFileOps` —— 与 `writeText` 同一条闸，不另开旁路。
 *
 * 未实现本接口时 GSAVE 回落旧文本载体（[GraphicsBuffer.toSaveText]），
 * 见 `fn/GraphicsMethods.kt` 的 GSAVE 段。
 */
interface BinaryFileOps {
    /**
     * 覆盖写 [bytes]；返回 false = 失败（原版 `Bitmap.Save` 抛异常 ⇒ GSAVE 返回 0，
     * FunctionMethodCreator.cs:8127-8130）。
     *
     * 父目录由实现创建（原版 GSAVE 先 `Config.CreateSavDir()` 再 Save，:8124-8125）。
     */
    fun writeBytes(path: String, bytes: ByteArray): Boolean
}

/**
 * 单个图形缓冲。对应 `GraphicsImage`（GraphicsImage.cs:11-671）。
 *
 * 生命周期（GraphicsImage.cs:102-117 / :528-560）：
 * - [create] 先释放旧内容再建新位图（`GCreate` 调 `GDispose`），画笔/画刷/字体一并清空；
 * - [dispose] 尺寸归 0、像素清空、三个绘图状态置 null，[isCreated] 转 false；
 * - 未创建时宽高读 0（`size = new Size(0,0)`）。
 *
 * 状态字段对应 `GraphicsImage` 的 `brush` / `pen` / `font` / `style`：
 * [brushColor] 是 `SolidBrush.Color`，[penColor]+[penWidth] 是 `Pen.Color`/`Pen.Width`，
 * [fontName]+[fontSize]+[fontStyle] 是**请求名**、`Font.Size` 与 `Fontstyle` 位掩码，
 * [fontResolvedName] 才是 `Font.Name`（GraphicsImage.cs:60；解析口径见其 KDoc）。
 *
 * **画写不变量（2026-10-30 稳定性审计 #1）**：任何路径只要写了像素就必然已 [markDirty]；
 * 一条写不到像素的路径（裁剪/求交为空、全透明合成、越界）**不得** markDirty。
 * 反向也成立 —— [fileSource] 还在 ⇔ 「一个像素都没被改写」，[evictDeferred] 的回收资格
 * 完全挂在这条等价式上，多 mark 一次就是把一张 122 MiB 工作表永久踢出回收面。
 */
class GraphicsBuffer(
    val id: Int,
    /**
     * 池级像素驻留字节累加器（[GraphicsPool] 建条目时传入；直接 new 的缓冲为 null = 不记账）。
     *
     * 为什么要一根原子计数器而不让读侧遍历 `buffers`：像素数组的换代有两类线程
     * （VM 线程 create/dispose、UI 线程首访问延迟解码），而诊断读侧（壳层内存采样）在
     * **第三条线程**上 —— 遍历普通 `HashMap` 会撞上 VM 线程的 resize（CME 或脏读）。
     * 这里每次换代只做两个原子操作（相对整图解码/分配的开销可忽略），读侧一次
     * `AtomicLong.get()` 就能拿到「此刻有多少像素字节」，不阻塞任何线程、不加新锁。
     */
    private val poolResidentBytes: java.util.concurrent.atomic.AtomicLong? = null,
    /**
     * 池级「合成源拿不到像素」计数器（[GraphicsPool.stats] 的 `sourceMisses`）。
     *
     * 为什么必须记：原版 CSV 父图是 `Utils.LoadImage` **整张解码**（`AppContents.cs:273`），
     * 解不出来就整条 sprite 作废（`:274-278` 警告 + null）—— 原版**不存在**「SPRITECREATED 为真
     * 却画不出内容」这种状态。移植侧为了防 OOM 把父图改成延迟解码 + 像素预算，才长出这条
     * 第三种状态；一旦触发，`GDRAWSPRITE` 只能什么都不画（静默透明）。
     * 2026-11-06 布娃娃预览整页碎片就是它，而当时**零日志**、只能逐层打诊断才挖出来。
     * 记一笔以后，真机 `gfx=` 读数行里直接能看见「有几笔合成拿不到源像素」。
     */
    private val poolSourceMisses: java.util.concurrent.atomic.AtomicLong? = null,
) {

    /**
     * **不可变一代快照**：几何 + 像素 + 两级延迟源打包成一个对象整体发布（2026-11 H1 收口）。
     *
     * 字段名沿用旧散字段名（`width` / `height` / `pixelData` / `pendingPixels` /
     * `deferredSource`）：[com.eratw.port.l3.StateHash] 按字段名反射混入 aux:gfx 簇，
     * 其中 `deferredSource` 靠名字命中它的宿主派生排除表 —— 改名会把闭包放进链，
     * 链值随 lambda 合成类名漂移。
     */
    private class Surface(
        val width: Int,
        val height: Int,
        val pixelData: IntArray,
        /** 延迟解码源；非 null = 像素尚未落地。 */
        val pendingPixels: (() -> IntArray?)?,
        /** 解码完成后仍留着的重解源（[GraphicsBuffer.evictDeferred] 的资格凭据）。 */
        val deferredSource: (() -> IntArray?)?,
    )

    /**
     * 当前代快照；null = 未创建或已 [dispose]。
     *
     * ## 线程契约（2026-11 H1，本车道主活）
     *
     * 旧实现把几何与像素散在若干普通字段上：读侧靠 [pixels] 的监视器解码，写侧
     * （create/createFrom/createDeferred/dispose）**完全不取锁** —— 旧注释「写侧都走同一把锁」
     * 与事实不符。后果有两类：
     * 1. **撕裂**：读者可以拿到「新 width + 旧 pixelData」的配对，行距错位甚至越界；
     *    迟到的解码还能把「旧尺寸的一代像素」发布进已被换掉的几何里，稳态直接违背
     *    `pixels.size == width*height` 这条下游不变量；
     * 2. **复活**：dispose 之后同一笔延迟解码照常落像素并重入池级记账（poolResidentBytes），
     *    而回收凭据（deferredSource）已被 dispose 清掉 —— 这块字节既画不到、也收不回，读数永久虚高。
     *
     * 现口径：可变状态收拢进不可变 [Surface]，本引用 `@Volatile` 发布 ——
     * - **读侧无锁**：一次取引用即得自洽快照（宽高像素恒属同一代）；
     * - **写侧换代**：一切变更（包括延迟解码落地）都经 [publishSurfaceLocked] 整代替换，
     *   且收进本实例监视器；锁内以**重新读取的当前代**为底稿，杜绝跨代错贴。
     *   dispose 与解码在锁上串行：解码先完则 dispose 随后整代置 null 并把记账**成对扣回**；
     *   dispose 先完则读侧无锁快路径看到 null，连解码都不会发起 —— 「已 dispose 的缓冲
     *   被迟到像素复活重入账」这条旧病灶不再有成立路径（记账成对作废，参照 stability 波纪律）。
     *
     * 画写路径（[setPixel] / [blendPixel] 等）对像素数组的**元素级**写入仍是弱一致（与历史
     * 口径相同：画写只发生在 VM 线程，UI 读侧逐像素允许旧值）；本 holder 保证的是
     * 「几何与像素引用这一对」永不撕裂、dispose 后不可能被复活。
     */
    @Volatile
    private var surface: Surface? = null

    /** 位图宽度；未创建 / 已 dispose = 0（`GraphicsImage.Width => size.Width`）。 */
    val width: Int get() = surface?.width ?: 0

    /** 位图高度；未创建 / 已 dispose = 0。 */
    val height: Int get() = surface?.height ?: 0

    /**
     * 这一代**实际占有的 Java 堆字节**，已计入 [poolResidentBytes]；换代时用 getAndSet 拿旧值算差。
     *
     * 口径在 2026-11-06 修正过：从前只记 `pixelData.size * 4`（已解码的像素阵列），
     * 而 [demotePixelsToPng] 发布的是「像素空 + PNG 闭包」的一代 —— PNG `ByteArray`
     * 同样落在 Dalvik 堆的大对象空间里，却一分钱都不进账。详见 [publishSurfaceLocked]。
     */
    private val accountedBytes = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 本缓冲**当前代**占有的 Java 堆字节（含降级态的压缩载荷），池级账本的分项读数。
     *
     * 计算属性、**无后备字段**（Kotlin 编译成 getter）：[com.eratw.port.l3.StateHash] 只反射
     * 字段，方法不进 aux:gfx 簇，故不新增 L3 登记项 —— 与 [isPixelsDemoted] 同一条纪律。
     * 与 [pixelBytes] 的分工：那个只答「已解码的像素阵列多大」（降级中恒为 0，
     * 渲染侧拿它判能不能直读），这个答「这一代到底还占着堆里多少字节」（回收闸的判据）。
     *
     * `GraphicsPool.stats` 的遍历读数与 [GraphicsPool.residentBytes] 的原子计数都取本属性，
     * 两者才恒等；只读，不触发延迟解码，也不触发 PNG 晋升。
     */
    val retainedBytes: Long get() = accountedBytes.get()

    /**
     * 像素数组换代的**唯一**落点：整代替换 [surface]，并把池级记账从旧代换到新代。
     *
     * 调用方必须持有本实例监视器（[synchronized]），锁内只做引用替换与两个原子操作 ——
     * 不解码、不绘制、不回调外部。记账与换代**成对出现**：发布 null（dispose）就是扣回，
     * 发布新代就是按新代像素入账，不存在「换了代不入账 / 入了账不换代」的单侧路径。
     *
     * ## 账本必须覆盖「这一代占有的全部 Java 堆字节」（2026-11-06 根因修复）
     *
     * 旧算式只有 `(pixelData.size) * 4`。[demotePixelsToPng] 把画过的合成图压成 PNG 后
     * 发布 `Surface(w, h, EMPTY_PIXELS, closure, null)`，于是整幅像素被扣回、账本记 0，
     * 可闭包捕获的那份 PNG `ByteArray` **仍在堆里**。这不是「释放」，只是把驻留从账本内
     * 搬到了账本外，后果是整条自救链集体失明：
     * - [GraphicsPool.get] 的每语句预算闸 `evictDeferredPixelsWithinBudget` 一进门就按
     *   `residentPixelBytes <= budgetBytes` 早退；
     * - 预算闸第二档对已在降级态的缓冲调 [demotePixelsToPng] 恒收 0，拿这笔载荷毫无办法；
     * - `RunTrail.shouldEvictCompositedSprites` 的条件一 `residentBytes > budgetBytes` 恒假，
     *   于是唯一会 dispose 降级缓冲、真正释放 PNG 的第三档一次都不触发；
     * - 真机报告行与 `LiveSetCensus` 也读同一根账本，账上写着 0，堆里却是几百 MiB。
     *
     * 而 `GraphicsPool.buffers` 的空壳条目会随游玩时长累积：语料 `@GET_EMPTY_G` 每帧扫
     * 空编号发新 gid ⇒ 每降级一张就留一份（修复前是账外 PNG，2026-11-07 玩家报告
     * 5783b90 读数 1543 项）。2026-11-08 OOM-A 波起 [GraphicsPool.releaseUnusedLocked]
     * 在 dispose 后把空壳条目摘表（见该函数注释）；但两次回收之间它们仍驻留，
     * 这份累积压力的根（PNG 载荷占 Java 堆）要等下一波把载荷搬出堆才彻底消除。
     * 累积到贴顶的旧形态 = 大对象空间撑爆、`OutOfMemoryError` 穿过 core 的
     * `catch (e: Exception)` 把 VM 线程带走 —— 玩家看到的正是「玩久了有时闪退，什么报错都没有」。
     *
     * 修法不是加一道新阈值，是让账本与实物恒等：本函数按
     * `pixelData.size * 4 + retainedPayloadBytes` 记账，[demotePixelsToPng] 把 PNG 的实际
     * 字节数传进来，其余所有发布点（create / createFrom / 解码落地即晋升 / evictDeferred /
     * dispose）载荷恒为 0 ⇒ 晋升与 dispose 自动把载荷成对扣回，不新增任何单侧路径。
     * 账本说真话之后，预算闸、水位档、合成图逐出档与报告读数**同一处**恢复视力。
     *
     * 不做「换代与记账」的对外原子成对（池级读数在并发换代的一瞬间可能偏一张图的量）——
     * 它是诊断读数，宁可偏一点也不给像素路径加新锁（旧 `storePixels` 的记账口径原样保留）。
     *
     * @param retainedPayloadBytes 新代里除 `pixelData` 之外仍被 [Surface.pendingPixels]
     *   强引用着的 Java 堆字节（只有 [demotePixelsToPng] 的 PNG 载荷非 0）。`next == null`
     *   时一律按 0，载荷随换代一起作废。
     */
    private fun publishSurfaceLocked(next: Surface?, retainedPayloadBytes: Long = 0L) {
        val nextBytes = (next?.pixelData?.size ?: 0) * 4L + (if (next == null) 0L else retainedPayloadBytes)
        val previous = accountedBytes.getAndSet(nextBytes)
        val old = surface
        surface = next
        poolResidentBytes?.addAndGet(nextBytes - previous)
        // 旧代的延迟源若是堆外载荷句柄（OOM-B：mmap PNG），随换代一并释放 ——
        // 晋升 / dispose / 再降级三条路都经这里，不新增单侧释放路径。
        // 堆内闭包不是 AutoCloseable，这个判断对既有各代恒为 false，行为逐字不变。
        val oldPending = old?.pendingPixels
        if (oldPending is AutoCloseable && oldPending !== next?.pendingPixels) {
            runCatching { oldPending.close() }
        }
    }

    /**
     * 这张缓冲的**文件源路径**（`GCREATEFROMFILE` 与 CSV 资源表的父图）；null = 无文件源，
     * 或像素已被画写操作改过（[markDirty] 会把它清掉）。
     *
     * ## 它解决什么
     *
     * 语料里的立绘动图是**整张工作表**：`resources/As06MARK-IV/anime/服_笑顔动_25.webp`
     * 是 8000x4000，按 ARGB 解出来 **122 MiB**。原版 PC 上这张图是 GDI+ `Bitmap`（原生内存），
     * 移植侧 `pixels` 是 `IntArray`（Dalvik 堆，`largeHeap` 512 MiB 挡）—— 只解一张工作表就吃掉
     * 近四分之一堆，再叠上渲染侧同尺寸的 `Bitmap` 副本，训练场景里点一个指令（打印立绘/挿絵）
     * 就足以 `OutOfMemoryError` 直接闪退（2026-10-30 玩家反馈「点击爱抚指令后闪退了」）。
     *
     * 有了这个字段，渲染侧（`:platform` 的 `SpriteBitmapSource`）可以**按路径直接解出 Bitmap**，
     * 完全绕开 `pixels` 这条 Java 堆通道；`pixels` 仍然可用（`GGETCOLOR` / `GDRAWSPRITE` 等），
     * 只是退回「首访问现解」的延迟语义。
     *
     * 判据必须保守：**只要有人往这张缓冲上画过东西**，文件内容就不再等于缓冲内容，
     * 真正写像素的画写入口都会调 [markDirty] 把它清成 null，渲染侧随之回落到 `pixels`；
     * 写不到像素的路径（裁剪成空、全透明合成）不调 —— 见类 KDoc 的画写不变量。
     *
     * `@Volatile`：读侧在 UI 线程（`SpriteBitmapSource.bitmapOf` 每帧判一次），写侧在 VM 线程
     * （`GCREATEFROMFILE` 建图 / `GDRAWG` 等画写作废）。读到过期值两个方向都坏：过期 null 会退回
     * 解 `pixels`（正是要避开的那 122 MiB），过期路径会在画过之后仍按文件内容出图。
     */
    @Volatile
    var fileSource: String? = null
        private set

    /**
     * 像素已被画写改过 ⇒ 文件源不再代表内容（见 [fileSource]）。
     *
     * **调用纪律（类 KDoc 的画写不变量）**：只在「这一步确定至少会写一个像素」之后、
     * 首次写之前调用。裁剪/求交为空、alpha=0 合成这类**写不到像素**的路径不得调用 ——
     * 2026-10-30 稳定性审计 #1 的病灶就是「一笔裁没的 `blitFrom` 也先 mark 再早退」，
     * 一次点按就把 122 MiB 工作表永久踢出 [evictDeferred] 的回收面。
     */
    /**
     * 像素**写代数**：每次图形写操作 +1。
     *
     * 为什么必须有它：渲染层按 `GraphicsBuffer.pixels` 的**数组引用**判断位图缓存要不要重建
     * （`platform/SpriteBitmapSource.kt` 的 `cached.pixelsRef.get() === pixels`）。而脚本反复往同一块
     * 缓冲上重画（`GCREATE` 一次 + 每帧 `GDRAWSPRITE` 合成）时数组引用**不变**，缓存就永远
     * 命中最早那张位图 —— 真机现象是「布娃娃预览每次改选项都不刷新，一直显示半成品」。
     * 数组引用只表达「换代」（新 `Surface`），表达不了「原地重写」，所以补这一条第 0 代以外的凭据。
     *
     * 推进点放在**每笔写操作的入口**，不放在逐像素的 [markDirty]（那是热路径，逐像素一次
     * 写代数会把 blit 抬高一个数量级），代价是「写了 0 个像素也 +1」—— 那只会让缓存多重建一次。
     */
    @Volatile
    private var writeRevision: Int = 0

    /** 当前写代数（只读；渲染层拿它和位图缓存里记的值比）。 */
    val pixelRevision: Int get() = writeRevision

    /**
     * 最近一笔图形写的时刻（`System.nanoTime() / 1_000_000L`，单调时钟；0 = 从未写过）。
     *
     * 渲染层用 [msSinceLastWrite] 判「这波写还在不在进行」：脚本合成一屏立绘是**一长串**
     * `GDRAWSPRITE`（布娃娃页 20+ 层，每层还要解一次区域），若 UI 线程每次查到写代数变了就
     * 重建一次 850x1280 位图（4.3 MiB 分配 + 逐像素拷贝），一屏合成期间能重建十几次 ——
     * 纯浪费 + GC 压力，玩家看到的就是「点一下要等好几秒」。合成进行中先用旧位图顶着，
     * 等写空闲下来再重建一次即可（`SpriteBitmapSource` 的稳定期判据）。
     */
    @Volatile
    private var lastWriteAtMs: Long = 0L

    /**
     * 距最近一笔图形写过去多少毫秒；从未写过返回 `Long.MAX_VALUE`（渲染层按「已稳定」处理）。
     */
    fun msSinceLastWrite(): Long {
        val at = lastWriteAtMs
        if (at == 0L) return Long.MAX_VALUE
        return System.nanoTime() / 1_000_000L - at
    }

    /** 一笔图形写操作的入口调用（见 [pixelRevision] 的 KDoc）。 */
    private fun bumpWriteRevision() {
        writeRevision++
        lastWriteAtMs = System.nanoTime() / 1_000_000L
    }
    private fun markDirty() {
        fileSource = null
    }

    /**
     * 像素，行主序，`0xAARRGGBB`；未创建 / 已 dispose = 空数组。
     *
     * 读侧可能来自 UI 线程（`SpriteBitmapSource` 建位图）而写侧是 VM 线程
     * （`GCREATEFROMFILE` / `GDISPOSE`）。延迟解码与换代都收进本实例监视器，
     * 一次读取拿到的是**同一代**的像素数组（线程契约见 [surface] 的 KDoc）。
     *
     * 延迟解码源的历史口径（保留）：非 null 时首像素访问才真正解码 ——
     * 原版 `AppContents.LoadContents` 是逐文件立即 `Bitmap`（AppContents.cs:273-293），
     * 本语料四个 CSV 引用的 134 张图全解码约 105 MiB，真机 512 MiB 堆上不可常驻；
     * 尺寸/`IsCreated` 已在登记时按文件头给出，故语义等价（`ASpriteSingle.IsCreated`
     * 只看 `BaseImage.IsCreated`，ASpriteSingle.cs:24-34），只是像素按需落地。
     */
    val pixels: IntArray
        get() {
            // 无锁快路径：一次取引用即自洽快照；未创建/已 dispose 给空数组，绝不触发解码。
            val fast = surface ?: return EMPTY_PIXELS
            if (fast.pendingPixels == null) return fast.pixelData
            // FEEL1-L3 探针（只观测，锁的粒度/顺序/语义一字未动）：未挂载时 watch == null
            // ⇒ 不取时钟、不进 LockWatch、零分配；两个早退分支（已 dispose / 无延迟源）没在
            // 锁里干活，按 LockWatch 类 KDoc「早退不记样本」的口径直接跳过。
            val watch = LockWatch.handleOrNull()
            val enterNs = if (watch === null) 0L else System.nanoTime()
            var acquiredNs = 0L
            var decodeTag = 0L
            val decoded = synchronized(this) {
                if (watch !== null) acquiredNs = System.nanoTime()
                val cur = surface ?: return EMPTY_PIXELS
                val load = cur.pendingPixels ?: return cur.pixelData
                // 解码全程持锁 ⇒ 同一代只解一次；create/dispose 的换代在锁外排队，
                // 醒来后看到的一定是解码完成后的状态，不会再出现「解完才发现已被 dispose」。
                val loadW = cur.width
                val loadH = cur.height
                val decoded = try {
                    load()
                } catch (t: Throwable) {
                    // 宿主解码器是实现方（Android 侧 BitmapFactory / 桌面侧纯 JVM PNG），
                    // 抛异常一律按「读不出这张图」处理，不让它穿到 VM 线程或 UI 线程。
                    null
                }
                // `decoded != null` 同时承担判据与智能转换：写成先算 `usable` 再 `&& decoded != null`
                // 会让编译器报「Condition is always 'true'」（HEAD 既有的冗余条件），语义逐字不变。
                val next = if (decoded != null && decoded.size == loadW * loadH) {
                    Surface(loadW, loadH, decoded, null, cur.deferredSource)
                } else {
                    // 解码失败：原版这一行在装载期就被丢弃（AppContents.cs:274-278，`LoadImage`
                    // 抛异常折成 null 后 `continue`）。延迟源拿不到像素，这里退化成同尺寸全透明图，
                    // 保持 `pixels.length == width * height` 这条不变量 —— 下游按尺寸索引
                    // （`GDRAWSPRITE` 的 arraycopy、`SpriteBitmapSource` 建 Bitmap）才不会越界；
                    // 全透明在屏上等价于「没画」。失败结果同样清掉源，免得每次访问都重试解码
                    // （`GCREATEFROMFILE` 读不出图时折成 0 同口径，FunctionMethodCreator.cs:6905）。
                    val transparent = try {
                        IntArray(loadW * loadH)
                    } catch (t: Throwable) {
                        // 连透明图都分配不出来（真机堆已满）：退回「未创建」，让下游画写入口
                        // 全部静默早退。尺寸与像素在同一代里成对清零，读者不会拿到半套状态。
                        // 这是**像素层**的「缺图降级」，只允许影响这一笔能否画出内容；
                        // 脚本可见的 `SPRITECREATED / SPRITEWIDTH / SPRITEHEIGHT` 由
                        // [SpritePool.Sprite.isCreated] 的 SpriteF 登记期口径独立兜住
                        // （ASpriteSingle.cs:24-34 的官方语义）——不许把整张 CSV 父图切出来的
                        // sprite 一起判死，否则 `Toolkits.ERB:135` 会拿到 0 分母。
                        publishSurfaceLocked(Surface(0, 0, EMPTY_PIXELS, null, null))
                        return EMPTY_PIXELS
                    }
                    Surface(loadW, loadH, transparent, null, null)
                }
                publishSurfaceLocked(next)
                if (watch !== null) decodeTag = loadW.toLong() * loadH
                next.pixelData
            }
            // 记录点在监视器**外侧**（LockWatch 的叶锁不变式 + QueueInputSink 的回调锁外口径）。
            if (watch !== null) {
                watch.noteSegment(
                    LockWatch.ID_GFX_PIXELS_DECODE, enterNs, acquiredNs, System.nanoTime(), decodeTag,
                )
            }
            return decoded
        }

    /**
     * 已驻留像素的等效字节数（Int = 4 字节/像素，等价于 `pixels.size * 4L`）。
     *
     * 只读诊断读数，**不触发延迟解码**：CSV 资源表的缓冲在首像素访问前
     * （[createDeferred] 已登记、[pixels] 的延迟源尚未消费）还没有像素数组，这里按 0 计 ——
     * 归因堆压力时不该为了报一个数把整张图解码出来（那正好是它要观测的那笔分配）。
     * 非延迟缓冲与本属性恒等于 `pixels.size * 4L`；不改变任何既有字段与行为。
     */
    val pixelBytes: Long get() = (surface?.pixelData?.size ?: 0) * 4L

    /** `SolidBrush.Color`；null = 未设置（`brush == null`）。 */
    var brushColor: Int? = null

    /** `Pen.Color`；null = 未设置。 */
    var penColor: Int? = null

    /** `Pen.Width`；默认 1（`new Pen(color)` 的默认宽度）。 */
    var penWidth: Int = 1

    /** `Pen.DashStyle`（`GDashStyle` 只写不改，GraphicsImage.cs:436-448）。 */
    var dashStyle: Int = 0

    /** `Pen.DashCap`。 */
    var dashCap: Int = 0

    /**
     * `Font.Name` —— 移植侧记的是**请求名**（`GSETFONT` 第 2 参原样）。
     * null = 未设置（`font == null`）。
     *
     * 测量走这个名字（`FontSidecarCatalog.chainFor` 的第一跳），与原版一致：原版把
     * 解析后的 `Font` 对象存进 `GraphicsImage.font`，绘制与测量都读同一个对象
     * （spec: FunctionMethodCreator.cs:6394 / :6400 → GraphicsImage.cs:89 `Fnt`）。
     */
    var fontName: String? = null

    /**
     * 解析名 —— 原版 `GraphicsImage.cs:60` 的 `Fontname => font.Name`，即 **GDI+ 解析后**的家族名，
     * 不是请求名；`GGETFONT` 回读的就是它（spec: FunctionMethodCreator.cs:6219）。
     *
     * 请求名解析不到时 GDI+ 静默换一个族继续画（实测 `new Font("NoSuchFamilyZZZ", 16f, Regular, Pixel)`
     * 的 `Name` = `Microsoft Sans Serif`，2026-10-21 本机 System.Drawing），所以两者可以不同。
     * 移植侧的对应值由 `PortFonts.resolveFontName` 给出，见 `GSETFONT` 分支的 KDoc。
     * null = 未设置字体。
     */
    var fontResolvedName: String? = null

    /** `Font.Size`（构造时用 `GraphicsUnit.Pixel`，故 `Size` 就是像素值）。 */
    var fontSize: Int = 0

    /** `Fontstyle` 位掩码：Bold=1 / Italic=2 / Strikeout=4 / Underline=8（GraphicsImage.cs:64-87）。 */
    var fontStyle: Int = 0

    /** `GraphicsImage.IsCreated`：`g != null`（GraphicsImage.cs:44-54）。一次取快照，宽高恒同代。 */
    val isCreated: Boolean
        get() {
            val s = surface ?: return false
            return s.width > 0 && s.height > 0
        }

    /** `GCreate`：先 `GDispose` 再建位图（GraphicsImage.cs:102-117）。 */
    fun create(w: Int, h: Int) {
        dispose()
        val px = IntArray(w * h)
        synchronized(this) { publishSurfaceLocked(Surface(w, h, px, null, null)) }
    }

    /**
     * `GCreateFromF`：按源位图尺寸重建并落像素（GraphicsImage.cs:119-130）。
     *
     * ## 长度恰好等于 `w * h` 时**接管**那份数组，不再拷第二份
     *
     * 原版这一句的「拷贝」是 GDI+ 把源位图 `DrawImage` 到透明目标，跟我们的数组所有权无关。
     * 而移植侧的调用方（[createFromArgb] 的三条路：宿主 `BitmapFactory` 解码、纯 JVM
     * `PngDecoder` / `WebpDecoder`、`GSAVE` 文本载体）交给这里的都是一份**刚解出来、
     * 除它自己没人引用**的像素 —— 再 `IntArray(w*h) + arraycopy` 一遍，就是让每张图在
     * Dalvik 堆上同时存在两份。本作语料的立绘工作表单张 8000×4000 = **122 MiB**，
     * 2× 就是 244 MiB 瞬时，而 `largeHeap` 挡位一共 512 MiB（真机 512 MiB 挡的装载读数见
     * `:core-vm:deviceTierProbe`）。延迟装载那条路（[createDeferred] 落地时直接接管解码出的数组）
     * 本来就是接管口径，本次把立即解码这条也对齐。
     *
     * 所有权契约：接管之后 `src` 归本缓冲独占，**调用方不得再持有/改写它**。
     * 长度不等（截断或补零）的形态仍按旧口径新建一份 —— 那是唯一能保住
     * `pixels.size == width * height` 这条下游不变量（`GDRAWSPRITE` 的 arraycopy、
     * `SpriteBitmapSource` 建位图都按它索引）的做法。
     */
    fun createFrom(w: Int, h: Int, src: IntArray) {
        dispose()
        val n = w * h
        val px = if (src.size == n) {
            src
        } else {
            IntArray(n).also { System.arraycopy(src, 0, it, 0, minOf(src.size, n)) }
        }
        synchronized(this) { publishSurfaceLocked(Surface(w, h, px, null, null)) }
    }

    /**
     * `GCREATEFROMFILE` / `GLOAD` 的解码像素落点：先校验再按 [createFrom] 重建。
     *
     * 校验口径：
     * - 宽/高必须 1..[MAX_IMAGE_SIZE]：原版 `bitmap.Width > 8192 || bitmap.Height > 8192`
     *   时函数直接返回 0（FunctionMethodCreator.cs:6905 / :8177）；
     * - [src] 长度必须恰好 `w * h`：解码器已保证，这里兜底，避免半张图落进缓冲。
     *
     * 返回 false 时**不修改**当前缓冲（原版这条路径也不会走到 `GCreateFromF`）。
     */
    fun createFromArgb(w: Int, h: Int, src: IntArray): Boolean {
        if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return false
        if (src.size != w * h) return false
        createFrom(w, h, src)
        return true
    }

    /**
     * CSV 资源表的延迟图片：尺寸取自文件头（原版 `LoadImage` 后 `bitmap.Width/Height`，
     * AppContents.cs:273-280），像素在首访问时由 [load] 给出。
     *
     * @return false = 尺寸非法（原版 >8192 只告警但仍建图，AppContents.cs:280-283；
     *   这里对齐 `GCREATEFROMFILE` 的硬闸，因为延迟源拿不到半张图）。
     */
    fun createDeferred(w: Int, h: Int, load: () -> IntArray?): Boolean {
        if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return false
        dispose()
        synchronized(this) { publishSurfaceLocked(Surface(w, h, EMPTY_PIXELS, load, load)) }
        return true
    }

    /**
     * 带**文件源**的延迟图片：语义与 [createDeferred] 逐项相同，只是额外记下 [fileSource]，
     * 让渲染侧能绕开 `pixels` 直接按路径解位图（理由见 [fileSource]）。
     *
     * 调用点只有两处：`GCREATEFROMFILE`（语句链）与 `ResourceContents`（CSV 资源表的父图）。
     * 两处都满足「尺寸已由文件头量过、像素按需落地、缓冲只当图源用」这个前提。
     *
     * 与 [evictDeferred] 的配合：那张表回收的是 `pixels`（内存压力下退回未解码态），
     * 本字段说的是「像素还能从哪个文件按路径取」——两者互不覆盖，回收后 [fileSource] 仍然有效，
     * 渲染侧照旧走 native 位图。
     *
     * @param path 相对游戏根（或绝对）的图片路径，原样透传给渲染侧
     */
    fun createDeferredFromFile(w: Int, h: Int, path: String, load: () -> IntArray?): Boolean {
        return createDeferredFromFile(w, h, path, load, null)
    }

    /**
     * 带文件源**与区域像素源**的延迟图片（CSV 父图专用，PIXREG 波）。
     *
     * 为什么需要区域源：CSV 资源表的父图是**拼图**，个别表极大（4.981 的
     * `resources/汎用モブ子dairi版/汎用モブ子dairi版.webp` 是 6962x6189 = 164.4 MiB ARGB，
     * 超过解码像素预算 128 MiB）。`GDRAWSPRITE` 合成只用到其中一小块子矩形
     * （该表的 sprite 最大 850x1280 ≈ 4.3 MiB），整张解码既被预算闸拒（真机实测
     * 「解码像素预检不足 … 按缺图降级」⇒ 整页立绘全空），解码出来也纯属浪费。
     *
     * 有区域源时 [regionPixels] 只解那一块；[pixels] 的整张解码路径**保持不变**
     * （读整张的语义逐字不动，仍由 [PixelPreflight] 那一侧判）。
     *
     * @param region 给定 `(x, y, w, h)` 返回该矩形像素（行主序，`w*h` 个 `0xAARRGGBB`）；
     *   null / 越界 / 解码失败都返回 null，调用方退回整张解码。
     */
    fun createDeferredFromFile(
        w: Int,
        h: Int,
        path: String,
        load: () -> IntArray?,
        region: ((x: Int, y: Int, w: Int, h: Int) -> IntArray?)?,
    ): Boolean {
        if (!createDeferred(w, h, load)) return false
        fileSource = path
        regionSource = region
        return true
    }

    /**
     * 区域像素源；null = 不支持区域解码（走整张 [pixels]）。
     *
     * 只由 [createDeferredFromFile] 的 `region` 形参写入，且在缓冲发布前后各写一次
     * （写侧是 VM 线程、读侧可能是 UI 线程，`@Volatile` 保证可见性）。
     */
    @Volatile
    var regionSource: ((x: Int, y: Int, w: Int, h: Int) -> IntArray?)? = null
        private set

    /**
     * 取源图 `(x, y, w, h)` 这一块的像素（[blitFrom] 的采样面）。
     *
     * 三道守卫：没有区域源 / 像素已经落地（`pixelData` 非空——那时整张就在本对象里，
     * 直接读它更快）/ 矩形非法或全在源外 ⇒ 返回 null，调用方退回整张 `pixels`。
     *
     * 线程口径随本类：只读 [surface] 与 [regionSource]，不取 [this] 监视器
     * （区域解码在宿主侧自己的缓存里做，见 `ResourceCsvHost.decodeRegion`）。
     */
    fun regionPixels(x: Int, y: Int, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        val cur = surface ?: return null
        if (cur.pixelData.isNotEmpty()) return null
        val source = regionSource ?: return null
        if (x < 0 || y < 0 || x + w > cur.width || y + h > cur.height) return null
        return runCatching { source(x, y, w, h) }.getOrNull()
    }

    /**
     * 内存压力下把这张图的像素**退回未解码态**，返回释放的像素条数（0 = 什么都没做）。
     *
     * 判据只有一条硬核心：**内容必须恒等于某份文件的解码结果**，否则重解出来的不是同一张图。
     * 落到代码上是两个「还没被画过」的证据，任一成立即可回收：
     * 1. [id] 为负 —— 负数段是 CSV 父图专用编号（`ResourceContents.kt:194-197`），
     *    图形池的 gid 恒 ≥ 0，而 VM 的一切画图语句（`setPixel` / `fillRect` / `blitFrom` …）
     *    只能按 gid 寻到缓冲 ⇒ 负号段的像素**只会被读**，重解一次就是同一张图；
     * 2. [fileSource] 还在 —— 这是 `GCREATEFROMFILE` 建出来的 G_ID 缓冲，而 [markDirty] 收在
     *    **每一个真正写像素的落点**（写不到像素的裁剪/透明路径不作废，见类 KDoc 的画写不变量），
     *    所以「文件源还在」精确等价于「一个像素都没被改写」。
     *    2026-10-30 之前这里只认负数段，于是 G_ID 段的工作表一旦被 `GGETCOLOR` /
     *    `GSAVE` / `GDRAWG` 读过一次，那 122 MiB 就**永久常驻** Dalvik 堆（本板欠账第一条）。
     *    有了第 2 条，它们同样能退回未解码态，下次真读时由 [pixels] 透明重解。
     *
     * 仍要求当前代的 `deferredSource` 还在（= 这代像素从没被显式写过）：
     * [create] / [createFrom] / [dispose] 发布的新代都不带它，所以「能被回收」永远不会误伤脚本画出来的内容。
     *
     * 尺寸与 `isCreated` 刻意不动：原版 `GraphicsImage` 的 `IsCreated` 只看位图在不在
     * （`GraphicsImage.cs:44-54`），这里等价于「尺寸还在」；`ASpriteSingle.IsCreated`
     * 因此逐字不变（`ASpriteSingle.cs:24-34`），下次访问由 [pixels] 透明地重解。
     */
    fun evictDeferred(): Long {
        val source = surface?.deferredSource ?: return 0L
        // 两条「没被画过」的证据，见 KDoc：负数段（CSV 父图）或文件源还在（G_ID 段工作表）。
        if (id >= 0 && fileSource == null) return 0L
        return synchronized(this) {
            val cur = surface ?: return 0L
            if (cur.deferredSource !== source || cur.pixelData.isEmpty()) {
                0L
            } else {
                val freed = cur.pixelData.size
                // 换代必须经 [publishSurfaceLocked]（2026-10-30 审计 #2）：像素换代**必须**同步
                // 池级记账（accountedBytes / poolResidentBytes）。旧写法直接写后备字段绕过了记账，
                // trim 之后驻留读数虚高，之后清池再扣一次就成负数 ——
                // 而 GameHost 的内存采样把 -1 当「无数据」哨兵，负数读数与哨兵混在同一列。
                // 新代保住 deferredSource：它一丢，这张图就永久退出回收面。
                publishSurfaceLocked(Surface(cur.width, cur.height, EMPTY_PIXELS, source, source))
                freed.toLong()
            }
        }
    }

    /**
     * 「降级中」读数：当前代的像素还没落地、由 [demotePixelsToPng] 挂上的压缩闭包承载。
     *
     * 计算属性（无后备字段，Kotlin 编译成 getter 方法）：[com.eratw.port.l3.StateHash] 只反射
     * **字段**，方法不进链 —— aux 槽对象「不新增实例字段」的硬纪律不受影响
     * （见 [GraphicsPool.buffers] 的 KDoc：加一个字段就要重落一次 L3 登记常量）。
     */
    val isPixelsDemoted: Boolean
        get() {
            val s = surface ?: return false
            // 用 deferredSource 区分两种延迟态：CSV 父图的未解码态（createDeferredFromFile）
            // 也挂 pendingPixels，但它的内容是文件解码结果（deferredSource 非空）——那不叫
            // 降级，叫「还没落地的重解」，宿主报告的「压缩驻留」不许把它数进去。
            return s.pendingPixels != null && s.deferredSource == null
        }

    /**
     * 当前**已驻留**的像素数组；降级中 / 未创建 / 已 dispose 一律返回 null。
     *
     * 与 [pixels] 的分工：[pixels] 是「读侧语义」（降级中会透明解回来并换代 = 晋升），
     * 本函数是「驻留探测」（只看一眼当前代，**绝不**触发解码）—— 给渲染侧在
     * 「降级中且原生位图还有缓存」的形状下直接复用缓存、避免每帧把 1.44 MiB
     * 解回 Java 堆用（见 `SpriteBitmapSource.bitmapOf` 的降级支）。
     */
    fun pixelsIfResident(): IntArray? {
        val s = surface ?: return null
        return if (s.pendingPixels == null) s.pixelData else null
    }

    /**
     * 降级代的**只读**取图凭据：给渲染侧一条不改动引擎状态的路（配套字节口
     * [demotedPngBytes]）。与字节口分开是因为堆外载荷（OOM-B [PngPayloadStore]）读字节
     * 是一次瞬时拷贝 —— 凭据口必须**零分配**，平台侧才能每帧先验缓存、命中就不付拷贝。
     *
     * ## 这是哪条欠账（2026-11-08，0.2.43 `查看其他人的位置` OOM）
     *
     * [demotePixelsToPng] 把一屏 `highres_顔絵_*` 合成缓冲逐张压成 PNG，把 Java 堆从
     * 「600×600×4 = 1.44 MiB/张 × 几十~上百张」降到 PNG 载荷。但平台侧
     * `SpriteBitmapSource.bitmapOf` 在「降级态 + 位图缓存 miss」时走的是
     * `resident ?: buffer.pixels` —— [pixels] 是**有状态读**：解回整幅 `IntArray` 并
     * [publishSurfaceLocked] 发布新代，像素重新钉进 Dalvik 堆。位图缓存只有 32 MiB，
     * 一屏头像的工作集远超这个额度 ⇒ miss 是常态 ⇒ 页尾刚压下去的像素在等待输入期间被
     * 逐张解回来；而 `QueueInputSink.waitInput` 期间 VM 阻塞，既没有 G 语句推进预算闸、
     * 也没有页中检查点，三档自救全部静默 ⇒ 堆贴到 512 MiB。
     *
     * 根因不是「降级力度不够」，是**渲染这个纯消费者借了有状态的 VM 读口**。
     * 本口与 [demotedPngBytes] 把两种读分开：VM 侧语义读（[pixels]，可晋升，写路径依赖它）
     * 与渲染侧快照读（只给凭据/字节）。平台侧据此把 PNG 直接解成 native 位图，
     * 全程不进 Java 堆像素阵列、不换代、不晋升，降级因此**粘得住**。
     *
     * ## 契约（逐条）
     *
     * - **零副作用**：只读 [surface] 引用，不调 `pendingPixels`、不解码、不 `publishSurfaceLocked`、
     *   不动 [accountedBytes] / 池级 `residentPixelBytes`；`GCREATED`、宽高、写代数逐字不变。
     * - **只在降级代给凭据**：`deferredSource != null` 的是「可重解但还没落地」的 CSV 父图
     *   （[isPixelsDemoted] 的同款判据），走 `fileSource` 按路径解位图那一支，不给凭据；
     *   已晋升 / dispose / 未创建一律 null。
     * - **凭据身份即代际**：堆内载荷（[DemotedPngPixels]）给载体本体；堆外载荷
     *   （[PngPayloadStore.DecodeClosure]）给闭包本体 —— 两者都是当代 `pendingPixels`
     *   **强引用着的那个对象**，换代（晋升、再降级、dispose）必换对象，渲染侧按引用
     *   比较判缓存失效。堆外载荷被换代口 close 后 [demotedPngBytes] 读出 null，
     *   凭据失配同样兜得住。
     * - 返回 `Any` 是刻意的：平台不感知载荷在哪、只做引用比较，把这个判据锁在 core 侧。
     * - 计算属性式的方法（无后备字段）：[com.eratw.port.l3.StateHash] 只反射字段，
     *   方法不进 aux:gfx 簇，不新增 L3 登记项。
     */
    fun demotedCredential(): Any? {
        val s = surface ?: return null
        if (s.pendingPixels == null || s.deferredSource != null) return null
        return when (val p = s.pendingPixels) {
            is DemotedPngPixels -> p
            is PngPayloadStore.DecodeClosure -> p
            else -> null
        }
    }

    /**
     * 降级代 PNG 载荷的**只读**字节口（配套凭据口 [demotedCredential]，判缓存先用那边——
     * 本口对堆外载荷是一次瞬时拷贝）。
     *
     * - 堆内载荷（[DemotedPngPixels]）：返回与当代 `pendingPixels` 强引用的**同一个**
     *   `ByteArray`，零分配；反复读恒等。
     * - 堆外载荷（OOM-B [PngPayloadStore]）：载荷字节在 mmap 文件里，本口经
     *   [PngPayloadStore.Payload.readBytes] 做一次瞬时拷贝（PNG 级，几十~几百 KB，
     *   远小于 1.44 MiB 像素阵列）—— 平台侧只该在缓存 miss 时调它，命中走凭据口。
     *   载荷已被换代口 close（晋升竞态）时读出 null，平台按缺图支走。
     *
     * 零副作用与「只在降级代给」契约同 [demotedCredential]，不再复述。
     */
    fun demotedPngBytes(): ByteArray? {
        val s = surface ?: return null
        if (s.pendingPixels == null || s.deferredSource != null) return null
        return when (val p = s.pendingPixels) {
            is DemotedPngPixels -> p.bytes
            is PngPayloadStore.DecodeClosure -> p.readBytes()
            else -> null
        }
    }

    /**
     * 内存压力下把「画过的」缓冲的像素**无损压缩保留**（PNG 字节挂在延迟闭包里），
     * 像素数组退场，返回释放的像素条数（0 = 不在候选集或已在降级态）。
     *
     * ## 这是哪条欠账
     *
     * [evictDeferredPixelsWithinBudget]（GraphicsPool）那道预算闸只能退「重解必得同一张图」
     * 的缓冲；真机 2026-11-04 的五份 512 MiB 挡报告（`eratw/2026-11-04/1793779*`，
     * 构建 0.2.39+15）显示剩下的常驻恰恰是**画过的 G_ID 合成缓冲**：HUAWEI TET-AN00 一份
     * `图形池：332 项 / 331 张已创建 / 像素驻留 279 MiB`，而像素预算（当时 ÷3）只有 170 MiB ——
     * 超出部分全部是「QOL_HITOSAGASI → HIGHRES_GET_BASE_RESOURCE → 画像合成」每角色一张的
     * `highres_顔絵` 合成图（600×600×4 = 1.44 MiB/张，`GraphicsBuffer.create` 的分配正是
     * 另两份报告的 OOM 栈顶 `GraphicsBuffer.create:334`）。这批图被 `SPRITECREATE` 引用着，
     * 宿主 `releaseUnused` 的 exclude 白名单（Sprite/CBG 引用面）护住它们 ⇒ 预算闸、水位档、
     * 强档全都动不了，Java 堆 511/512 MiB 后任何一个 1.44 MiB 分配都是
     * `OutOfMemoryError`。dispose 掉它们（`releaseUnused` 的 92% 档）有脚本可见面
     * （`GCREATED` 翻 false，GraphicsImage.cs:44-54 的 IsCreated 只看位图在不在）；
     * 本函数给出**零脚本可见面**的第三条路。
     *
     * ## 无损性与脚本可见面（逐条）
     *
     * - **像素逐字保留**：PNG 字节由 [toSavePng] 编码（filter=0 + zlib，本仓 GSAVE 的同款
     *   载体，`GsavePngTest` 已证往返逐字节一致），解回来就是同一份 `IntArray`；
     * - **GCREATED / 宽高不动**：降级只换代像素（[Surface] 的 pixelData 换成空数组 +
     *   pendingPixels 挂闭包），几何还在同一代形状里，[isCreated]（`width > 0 && height > 0`）
     *   与 [width]/[height] 逐字不变 —— 与 [evictDeferred] 的口径一致（ASpriteSingle.cs:24-34
     *   的 IsCreated 只看 BaseImage.IsCreated）；
     * - **首次真读透明晋升**：[pixels] 的延迟解码路径（既有代码）会把闭包解出来并换代，
     *   调用方拿到的是与降级前逐字相同的数组；写路径（[setPixel] / [blendPixel] / [fillRect]
     *   等）全都先经 [pixels] 取数组 ⇒ 降级后照常可画，markDirty 语义不受影响；
     * - **渲染侧不晋升**：[demotedCredential] / [demotedPngBytes] 给出只读凭据与字节，
     *   平台侧直接解成 native 位图，无需碰 [pixels]。2026-11-08「查看其他人的位置」OOM 的
     *   根因就是渲染这个纯消费者借了有状态的 VM 读口，把页尾刚压下去的像素逐张解回堆里；
     * - **不进 [evictDeferred] 的回收面**：发布的 [Surface] 的 deferredSource 为 null ——
     *   降级缓冲的内容不是文件解码结果，把它当重解源退掉就真的丢了；预算闸对它返回 0，
     *   晋升后同样不在候选集。
     *
     * ## 候选判据
     *
     * 与 [evictDeferred] **互补**而不是重叠：可重解的（负号段、或 [fileSource] 还在的
     * G_ID 工作表，判据见该方法 KDoc）交给那一档整退 —— 它们能退得更干净（连 PNG 字节
     * 都不留）；本函数只收剩下的「画过的正号段」：`id >= 0 && fileSource == null`、
     * 像素已驻留、且还不在任何延迟态。同一张图两条路只会走一条。
     *
     * ## 线程与记账
     *
     * 编码在 `synchronized(this)` 内做（与 [pixels] 的「解码全程持锁」同口径：同一代只编
     * 一次、换代与发布原子）——画写只在 VM 线程（[surface] KDoc 的线程契约），本函数也只在
     * VM 线程（GraphicsPool 预算闸）被调，锁内不会与画写交叠；UI 线程同缓冲的像素读会
     * 排队等一段编码时长（单张 600×600 实测量级几十 ms，与既有延迟解码的 UI 侧代价同形）。
     * 换代经 [publishSurfaceLocked]，池级记账按「像素阵列 − PNG 载荷」的**差额**成对扣回：
     * 真正省下多少堆，账本就降多少，不许多降一个字节。载荷仍被闭包强引用着，
     * 把它记成 0 会让整条自救链按一个不存在的空闲额度判停（见 [publishSurfaceLocked] 的 KDoc）。
     *
     * 编码途中的 `OutOfMemoryError` 按「这张退不了」处理：捕住返回 0，让预算闸继续试下一张
     * （堆压力下逐张降压正是本函数的目的，不能因为一张编不动把整条 VM 线程炸穿 ——
     * `Error` 一路穿出 [get] 就是真机闪退）。zlib 流在 finally 里收尾，失败不留半开资源。
     *
     * @param payloadStore 堆外载荷通道（OOM-B）：非 null 且 spill 成功时 PNG 写临时文件
     *   mmap，闭包只持映射句柄、`retainedPayloadBytes` 记 0（账本口径是 **Java 堆**字节，
     *   载荷已出堆）；null 或 spill 失败回落堆内闭包、照旧按 `png.size` 记账。
     *   句柄随换代由 [publishSurfaceLocked] 统一 close（晋升 / dispose / 再降级全覆盖）。
     */
    fun demotePixelsToPng(payloadStore: PngPayloadStore? = null): Long {
        val probe = surface ?: return 0L
        if (probe.pendingPixels != null) return 0L // 已在降级/延迟态
        if (id < 0 || fileSource != null) return 0L // 可重解的走 evictDeferred，不走压缩档
        if (probe.pixelData.isEmpty()) return 0L // 无像素可压
        return synchronized(this) {
            val cur = surface ?: return 0L
            if (cur.pendingPixels != null || cur.pixelData.isEmpty()) return 0L
            val freedPixels = cur.pixelData.size
            val png = try {
                toSavePng()
            } catch (_: OutOfMemoryError) {
                return 0L
            }
            // 堆外通道（OOM-B）：spill 成功 ⇒ 载荷出 Java 堆，账本记 0；失败 ⇒ 回落
            // 堆内闭包、照旧记账。两条路的像素语义逐字相同（PNG 解码逐字复原）。
            val spilled = payloadStore?.spill(png)
            // 降级代：几何原样、像素空、压缩闭包挂 pendingPixels、deferredSource 置 null
            //（内容不是文件解码结果，不许进 evictDeferred 的重解面，见上）。
            // 堆内回落时 PNG 载荷仍被这个闭包强引用着、仍占 Java 堆的大对象空间，必须按它的
            // 实际字节数入池级账本。少这一笔就是 2026-11-06 判死的「账本归零、载荷常驻」：
            // 预算闸 / 水位档 / 合成图逐出档全部按账本判停，从此再也看不见这份驻留。
            // 见 [publishSurfaceLocked] 的 KDoc。
            if (spilled != null) {
                publishSurfaceLocked(
                    Surface(cur.width, cur.height, EMPTY_PIXELS, PngPayloadStore.DecodeClosure(spilled), null),
                    retainedPayloadBytes = 0L,
                )
            } else {
                // 堆内回落挂具名载体（DemotedPngPixels）而不是匿名 lambda：渲染侧要经
                // demotedPngBytes() 只读拿到字节直解 native 位图，匿名 lambda 给不出字节
                //（见该访问器 KDoc 的「查看其他人的位置」OOM 根因段）。
                publishSurfaceLocked(
                    Surface(cur.width, cur.height, EMPTY_PIXELS, DemotedPngPixels(png), null),
                    retainedPayloadBytes = png.size.toLong(),
                )
            }
            freedPixels.toLong()
        }
    }

    /**
     * `GDispose`：尺寸 0、位图/画笔/画刷/字体全部释放（GraphicsImage.cs:528-560）。
     *
     * holder 整代置 null：几何、像素、延迟源、回收凭据一并作废，池级记账成对扣回；
     * 正在锁里解码的迟到结果醒来发现换代已被 dispose 抢走，**不复活**（见 [surface] 的线程契约）。
     */
    fun dispose() {
        synchronized(this) {
            publishSurfaceLocked(null)
            fileSource = null
        }
        brushColor = null
        penColor = null
        penWidth = 1
        dashStyle = 0
        dashCap = 0
        fontName = null
        fontResolvedName = null
        fontSize = 0
        fontStyle = 0
    }

    /**
     * 读像素（原版 `Bitmap.GetPixel`；越界给透明色而不是抛异常）。
     *
     * 越界不炸是延迟缓冲的要求：CSV 资源表的图在首像素访问时才解码，调用方手里那份
     * `width/height` 可能来自解码前的登记尺寸（解码失败时尺寸会被清零，见 [pixels]），
     * 按它算出来的坐标就可能落到实际像素数组之外。原版这条路走的是完整加载的 `Bitmap`，
     * 没有这个窗口。
     */
    fun pixelAt(x: Int, y: Int): Int {
        val px = pixels
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val idx = y * width + x
        return if (idx < px.size) px[idx] else 0
    }

    /**
     * 写像素（直接覆盖，用于 `GSetColor` 的 `Bitmap.SetPixel`）。
     *
     * 越界 = 一个像素都写不到 ⇒ 静默返回且**不作废文件源**（与 [pixelAt] 的越界口径对称；
     * 原版 `Bitmap.SetPixel` 的 ArgumentException 折叠在函数层 `GSETCOLOR` 的边界判断里，
     * 见 `fn/GraphicsMethods.kt`，本类的生产调用点都已先判过界）。
     */
    fun setPixel(x: Int, y: Int, argb: Int) {
        bumpWriteRevision()
        val px = pixels
        val idx = y * width + x
        if (idx < 0 || idx >= px.size) return
        markDirty()
        px[idx] = argb
    }

    /**
     * `Graphics.Clear(Color)`。
     *
     * GDI+ 实测口径：**alpha == 0 的 Clear 结果是全 0**（不是 0x00RRGGBB）。证据 =
     * 基准 `pc-golden-root.txt:642`：`GCLEAR(10, 0x0000FF)` 之后 `GGETCOLOR(10,0,0)=0`；
     * 若按 ARGB 原样填充，该像素应是 0x000000FF=255。GDI+ 内部按预乘处理，A=0 时 RGB 信息丢失。
     * TODO(verify)：A 在 1..254 时的舍入行为未取到样本（基准只覆盖 A=0），此处按原样填充。
     */
    fun clear(argb: Int) {
        bumpWriteRevision()
        val px = pixels
        // 未创建（像素为空）：一个像素都写不到 ⇒ 不作废文件源（类 KDoc 的画写不变量）。
        if (px.isEmpty()) return
        markDirty()
        val v = if ((argb ushr 24) == 0) 0 else argb
        px.fill(v)
    }

    /** `GClear(color, x, y, w, h)`：只清矩形区域（GraphicsImage.cs:141-152，SetClip+Clear）。 */
    fun clearRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        bumpWriteRevision()
        fillRect(x, y, w, h, argb, replace = true)
    }

    /**
     * `Graphics.FillRectangle`。
     *
     * [replace] = true 时按 [argb] 原样覆盖（用于 `Graphics.Clear` 的裁剪分支，它走 Clear 而非
     * FillRectangle）；false 时按 source-over 合成（GDI+ 画刷语义，A=0 不改目标像素）。
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int, replace: Boolean = false) {
        bumpWriteRevision()
        if (w <= 0 || h <= 0) return
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        // GDI+ Rectangle 按 float 处理，x+w 不会 Int 回绕（GraphicsImage.cs:305-315 的
        // DrawImage 同族）；这里升 Long 求交避免 x+w / y+h 溢出成负数把整段裁没。
        val x1 = minOf(width.toLong(), x.toLong() + w.toLong()).toInt()
        val y1 = minOf(height.toLong(), y.toLong() + h.toLong()).toInt()
        if (x0 >= x1 || y0 >= y1) return
        // 非 replace 且 alpha=0：blendPixel 逐像素都是无操作 ⇒ 整笔写不到像素，不作废文件源。
        if (!replace && (argb ushr 24) == 0) return
        // markDirty 收在裁剪判定之后：到这里本笔确定会写 ≥1 个像素（类 KDoc 的画写不变量）。
        markDirty()
        val v = if (replace && (argb ushr 24) == 0) 0 else argb
        for (ty in y0 until y1) {
            val row = ty * width
            for (tx in x0 until x1) {
                if (replace) pixels[row + tx] = v else blendPixel(tx, ty, argb)
            }
        }
    }

    /**
     * 描边矩形：只画边框，[thickness] 像素全部落在框**内**。
     *
     * 对应 `Graphics.DrawPath(pen, path)`（GraphicsImage.cs:180-187）在软件缓冲上的近似 ——
     * GDI+ 沿路径中线描边（一半在路径外）、并且做斜接与端点样式；这里没有路径几何，
     * 只把边框画在框内，故 `GDRAWTEXT` 的替代字形轮廓比原版窄约半个 pen 宽（见该函数的 KDoc）。
     *
     * 坐标一律 Long：调用方（`GDRAWTEXT`）的 `x` 来自 `ReadPoint`（FMC.cs:9362-9375，只做
     * Int 范围检查），而 `x + advance` 在 Int 里会回绕；GDI+ 对越界路径只是裁剪
     * （同函数实测：origin=(-20,-20) 只留下画布内的像素），所以这里按 Long 求交后丢弃画布外部分。
     * 合成走 [blendPixel]（SourceOver，alpha=0 不改目标）。
     */
    fun strokeRect(x0: Long, y0: Long, x1: Long, y1: Long, argb: Int, thickness: Int = 1) {
        bumpWriteRevision()
        if (thickness <= 0) return
        // 不在这里 markDirty：所有写都落 blendPixel（alpha=0 无操作），作废由 blendPixel
        // 在真正写像素时做 —— 整框被裁没时一个像素都不写，也不该作废文件源。
        val left = minOf(x0, x1)
        val top = minOf(y0, y1)
        val right = maxOf(x0, x1)
        val bottom = maxOf(y0, y1)
        val t = thickness.toLong()
        val cx0 = maxOf(0L, left)
        val cy0 = maxOf(0L, top)
        val cx1 = minOf(width.toLong(), right)
        val cy1 = minOf(height.toLong(), bottom)
        if (cx0 >= cx1 || cy0 >= cy1) return
        val innerLeft = left + t
        val innerRight = right - t
        val innerTop = top + t
        val innerBottom = bottom - t
        for (ty in cy0 until cy1) {
            val onHorizontalEdge = ty < innerTop || ty >= innerBottom
            for (tx in cx0 until cx1) {
                if (onHorizontalEdge || tx < innerLeft || tx >= innerRight) {
                    blendPixel(tx.toInt(), ty.toInt(), argb)
                }
            }
        }
    }

    /**
     * `GDrawLine`：Bresenham，1px（原版按 `Pen.Width` 描边；宽度近似见函数层 KDoc）。
     *
     * 对应 `GraphicsImage.GDrawLine`（GraphicsImage.cs:421-434）。
     *
     * 端点先按 Liang-Barsky 裁剪进画布再迭代：原实现的 `|x1-x0|` / `|y1-y0|` 用 Int，
     * 坐标差 ≥ 2^30 时 `err` 溢出，`e2 >= dy` 与 `e2 <= dx` 可能同时不成立 ⇒ 坐标永不推进的死循环。
     * 裁剪后坐标差不超过画布宽高，仍全部用 Long 运算，并以「包围盒宽+高」作迭代数兜底。
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        bumpWriteRevision()
        if (width <= 0 || height <= 0) return
        // 不在这里 markDirty：Liang-Barsky 裁没时下面几个 clip 直接早退、一个像素都不写；
        // 作废由真正落笔的 blendPixel 负责（类 KDoc 的画写不变量）。
        val ax = x0.toDouble()
        val ay = y0.toDouble()
        val ddx = x1.toDouble() - ax
        val ddy = y1.toDouble() - ay
        var t0 = 0.0
        var t1 = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val r = q / p
            if (p < 0.0) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
        val maxX = (width - 1).toLong()
        val maxY = (height - 1).toLong()
        if (!clip(-ddx, ax)) return
        if (!clip(ddx, maxX.toDouble() - ax)) return
        if (!clip(-ddy, ay)) return
        if (!clip(ddy, maxY.toDouble() - ay)) return
        var x = (ax + t0 * ddx).toLong().coerceIn(0L, maxX)
        var y = (ay + t0 * ddy).toLong().coerceIn(0L, maxY)
        val ex = (ax + t1 * ddx).toLong().coerceIn(0L, maxX)
        val ey = (ay + t1 * ddy).toLong().coerceIn(0L, maxY)
        val dx = kotlin.math.abs(ex - x)
        val dy = -kotlin.math.abs(ey - y)
        val sx = if (x < ex) 1L else -1L
        val sy = if (y < ey) 1L else -1L
        var err = dx + dy
        val maxSteps = dx - dy + 2
        var steps = 0L
        while (true) {
            blendPixel(x.toInt(), y.toInt(), argb)
            if ((x == ex && y == ey) || steps++ >= maxSteps) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    /**
     * `GDrawG`：把 [src] 的 `(sx,sy,sw,sh)` 缩放贴到 `(dx,dy,dw,dh)`。
     *
     * 对应 `GraphicsImage.GDrawG`（GraphicsImage.cs:305-315，GDI+ `DrawImage`）。
     * 最近邻取样（GDI+ 是双线性；像素级差异不属于验收口径，契约 §4 A4）。
     *
     * **所有中间量升 Long**：原版 `g.DrawImage(bitmap, destRect, srcRect, GraphicsUnit.Pixel)`
     * （GraphicsImage.cs:314）在 GDI+ 里按 float 处理矩形，坐标/宽高的加减乘**从不回绕**，
     * 超界部分一律被裁剪（dest 裁到目标表面、src 越界采透明）。移植侧若用 Int 做这些
     * 中间量，极大值会回绕成负矩形（`sw*sh` 溢出、`x+w` 溢出），行为就与原版分叉了。
     * 快照**只对 `src === this`（自贴读冻结）**物化「实际会被采样且落在源缓冲内」的区域；
     * 非同源贴图的 src 与 dest 是两份独立像素数组，写入 dest 不可能污染尚未读到的源像素，
     * 整窗拷贝纯属瞬时浪费 —— 512 MiB 堆挡上这一发就是压垮骆驼的最后一根稻草
     * （0.2.39 真机 `LOOK_AT` 立绘差分合成：`GraphicsBuffer.blitFrom` 0.5 MiB 分配 OOM，
     * 当时 Java 堆 511/512 MiB；同档另有 EVENTTRAIN 立绘合成 1.5 MiB 分配 OOM，
     * 端点报告 2026-11-03 233215）。极大矩形仍然不会被物化，自贴侧照旧不给 OOM 通道。
     *
     * 冻结判据与原版对齐：GDRAWG 的 dst/src 都按编号取 `GraphicsImage`
     * （FunctionMethodCreator.cs:7252-7261），同号即同一实例，而 `g` 与 `GetBitmap()` 共用
     * 同一份 `RealBitmap`（GraphicsImage.cs:104-117 / :23-34）⇒ 自贴就是「同一张 Bitmap
     * 画回自己」；GDI+ 实测（2026-11-04 Windows System.Drawing 探针）对重叠区仍读**原值**
     * （8 像素行右移 1 像素得 01,01,02,03,04,05,06,07，非边读边写的 01,01,01,...），
     * 冻结通道与原版可见行为一致；非同源则是两张独立 Bitmap，本无读改交叠。
     * 黄金基准对自贴的钉法是 pc-golden-root.txt:652 `GDRAWG=1`
     * （GDRAWG(10,10,30,0,...)，src/dest 矩形不相交、只钉返回值）。
     *
     * [matrix]（GFX-01 新增，只有 `GDRAWSPRITE` 的 7 参形态传非 null）是 GDI+ `ColorMatrix`
     * 的 25 个 Float、**行主序、行 = 源通道、列 = 目标通道**（口径与出处见 [applyColorMatrix]）。
     * 原版把矩阵交给 `ImageAttributes.SetColorMatrix(...)` 后由 `DrawImage` 消费
     * （`GDRAWSPRITE` 链路：GraphicsImage.cs:291-303 → ASpriteSingle.cs:89-99 /
     * SpriteAnime.cs:161-172），GDI+ 的顺序是**取样 → 颜色换算 → 合成**，故这里对
     * 「实际被采到的那个源像素」逐像素换算后再 [blendPixel]；1:1 贴图上与原版同序，
     * 缩放贴图时差异只可能来自取样模式（最近邻 vs 双线性），已按契约 §4 A4 登记。
     */
    fun blitFrom(
        src: GraphicsBuffer,
        dx: Int,
        dy: Int,
        dw: Int,
        dh: Int,
        sx: Int,
        sy: Int,
        sw: Int,
        sh: Int,
        matrix: FloatArray? = null,
    ) {
        bumpWriteRevision()
        if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return
        // 不在这里 markDirty：iLo>=iHi / jLo>=jHi 判的是「dest 整笔裁没」，此后一个像素
        // 都不写；写不到像素就不该作废文件源（2026-10-30 审计 #1 的原病灶）。
        // 落笔作废由循环里的 blendPixel 承担（全透明源/矩阵换算后 A=0 同样不作废）。
        val dxL = dx.toLong(); val dyL = dy.toLong()
        val dwL = dw.toLong(); val dhL = dh.toLong()
        val sxL = sx.toLong(); val syL = sy.toLong()
        val swL = sw.toLong(); val shL = sh.toLong()
        val wL = width.toLong(); val hL = height.toLong()
        // 缓冲内实际会写的 dest 像素范围（Long，不回绕）
        val iLo = maxOf(0L, -dxL)
        val iHi = minOf(dwL, wL - dxL)
        val jLo = maxOf(0L, -dyL)
        val jHi = minOf(dhL, hL - dyL)
        if (iLo >= iHi || jLo >= jHi) return
        // 源图可能是 CSV 资源表的延迟缓冲（首像素访问才解码）。这里先只读**尺寸**
        // （不碰 `pixels`：取它会把整张父图解进堆），把采样范围算出来之后，
        // 优先走**区域解码**——只解这一笔真正会采样的矩形（见 [regionPixels]）。
        // 4.981 的 `汎用モブ子dairi版.webp` 是 6962x6189 = 164.4 MiB ARGB，整张既超像素预算
        // 又被预算闸拒（真机实测「解码像素预检不足 … 按缺图降级」⇒ 布娃娃预览整页全空），
        // 而单层 sprite 最大 850x1280 ≈ 4.3 MiB。
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return
        // dest (i,j) → 源采样坐标；与旧口径同式（i*sw/dw 整除 + coerce 到源矩形内）
        fun srcX(i: Long): Long = sxL + (i * swL / dwL).coerceIn(0L, swL - 1L)
        fun srcY(j: Long): Long = syL + (j * shL / dhL).coerceIn(0L, shL - 1L)
        // 实际会被采样且落在源缓冲内的区域（srcX/srcY 对 i/j 单调不减，端点即范围）
        val xMin = maxOf(0L, srcX(iLo))
        val xMax = minOf(srcW.toLong() - 1L, srcX(iHi - 1L))
        val yMin = maxOf(0L, srcY(jLo))
        val yMax = minOf(srcH.toLong() - 1L, srcY(jHi - 1L))
        if (xMin > xMax || yMin > yMax) return // 采样全落源外：全是透明色，blendPixel 无操作
        val regionW = (xMax - xMin + 1L).toInt()
        val regionH = (yMax - yMin + 1L).toInt()
        // 自贴（src === this）不走区域：dest 的写入会覆盖尚未读到的源像素，整张已在手才冻结得了。
        val region = if (src === this) null else
            src.regionPixels(xMin.toInt(), yMin.toInt(), regionW, regionH)
        if (region != null && region.size == regionW * regionH) {
            for (j in jLo until jHi) {
                val ty = (dyL + j).toInt()
                val py = srcY(j)
                if (py < yMin || py > yMax) continue
                val row = ((py - yMin) * regionW).toInt()
                for (i in iLo until iHi) {
                    val px = srcX(i)
                    if (px < xMin || px > xMax) continue
                    val raw = region[row + (px - xMin).toInt()]
                    blendPixel((dxL + i).toInt(), ty, if (matrix === null) raw else applyColorMatrix(raw, matrix))
                }
            }
            return
        }
        // 整张路径：要求像素与尺寸自洽（解码失败退化成同尺寸透明图、极端情况下尺寸被清零，
        // 都不能让下面的 arraycopy 拿旧尺寸去索引空数组）。
        val srcPixels = src.pixels
        if (srcPixels.size != srcW * srcH) {
            // 源像素不可用（延迟解码失败 / 逐出后重解失败）= 原版没有的第三种状态：记一笔给
            // [GraphicsPool.stats]（见 [poolSourceMisses] 的 KDoc），不再静默吞掉。
            poolSourceMisses?.incrementAndGet()
            return
        }
        // 快照只在自贴时物化：src === this 时 dest 的写入会落到尚未读到的源像素上，必须先冻结；
        // 非同源时 srcPixels 是另一份独立数组，写入 dest 碰不到它，直接取样即可。
        val snap: IntArray?
        val snapW: Int
        if (src === this) {
            snapW = (xMax - xMin + 1L).toInt()
            val snapH = (yMax - yMin + 1L).toInt()
            snap = IntArray(snapW * snapH)
            for (yy in 0 until snapH) {
                System.arraycopy(srcPixels, ((yMin + yy) * srcW + xMin).toInt(), snap, yy * snapW, snapW)
            }
        } else {
            snap = null
            snapW = 0
        }
        for (j in jLo until jHi) {
            val ty = (dyL + j).toInt() // jHi ≤ hL - dyL ⇒ ty ∈ [0, height)，不越界不回绕
            val py = srcY(j)
            if (py < yMin || py > yMax) continue
            val snapRow = ((py - yMin) * snapW).toInt()
            val srcRow = (py * srcW).toInt()
            for (i in iLo until iHi) {
                val px = srcX(i)
                if (px < xMin || px > xMax) continue // 源外采样 = 透明（GDI+ 越界口径）
                val raw = if (snap === null) srcPixels[srcRow + px.toInt()] else snap[snapRow + (px - xMin).toInt()]
                // 颜色换算发生在合成之前（GDI+ 的 DrawImage 顺序），逐像素做一次 5x5 乘加。
                blendPixel((dxL + i).toInt(), ty, if (matrix === null) raw else applyColorMatrix(raw, matrix))
            }
        }
    }

    /**
     * `GDrawGWithMask`：逐像素遮罩合成（GraphicsImage.cs:332-392 原样复刻）。
     *
     * 注意原版把 **BGRA 首字节（蓝通道）** 当遮罩 alpha 用（`array2[num2]`，GraphicsImage.cs:356/364/370），
     * 因为 `LockBits` 的 32bppArgb 内存序是 B,G,R,A。这里同样取低 8 位（蓝），属官方 bug 原样复刻。
     * 混合公式也照抄：`(src*(m+1) + dst*(256-(m+1))) >> 8`。
     */
    fun blitMasked(src: GraphicsBuffer, mask: GraphicsBuffer, dx: Int, dy: Int) {
        bumpWriteRevision()
        if (src.width != mask.width || src.height != mask.height) return
        // 不在这里 markDirty：dest 可能整块落在画布外（下面 continue 全跳过）、遮罩也可能
        // 全 0（`when` 的 m==0 支不写）——写不到像素就不作废；作废收在真正写像素的两支。
        for (j in 0 until src.height) {
            val ty = dy + j
            if (ty < 0 || ty >= height) continue
            for (i in 0 until src.width) {
                val tx = dx + i
                if (tx < 0 || tx >= width) continue
                val sIdx = j * src.width + i
                val m = mask.pixels[sIdx] and 0xFF
                val dIdx = ty * width + tx
                when (m) {
                    0 -> {}
                    255 -> {
                        markDirty()
                        pixels[dIdx] = src.pixels[sIdx]
                    }
                    else -> {
                        markDirty()
                        val k = m + 1
                        val s = src.pixels[sIdx]
                        val d = pixels[dIdx]
                        val b = (((s and 0xFF) * k + (d and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val g = ((((s shr 8) and 0xFF) * k + ((d shr 8) and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val r = ((((s shr 16) and 0xFF) * k + ((d shr 16) and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val a = ((((s ushr 24)) * k + ((d ushr 24)) * (256 - k)) shr 8) and 0xFF
                        pixels[dIdx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    /**
     * `GDrawGWithRotate`：绕 `(cx,cy)` 旋转后把 [src] 贴到 (0,0)（GraphicsImage.cs:407-419）。
     *
     * 原版用 GDI+ 变换矩阵；这里用逆变换 + 最近邻取样（像素级近似，见契约 §4 A4）。
     */
    fun rotateFrom(src: GraphicsBuffer, angleDegrees: Long, cx: Int, cy: Int) {
        bumpWriteRevision()
        if (src.width <= 0 || src.height <= 0) return
        // 不在这里 markDirty：采样全落源外（大角度转出画布）时一个像素都不写；
        // 作废由真正落笔的 blendPixel 负责（类 KDoc 的画写不变量）。
        val rad = Math.toRadians(angleDegrees.toDouble())
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)
        for (ty in 0 until height) {
            for (tx in 0 until width) {
                val dx = tx - cx
                val dy = ty - cy
                val fx = dx * cos + dy * sin + cx
                val fy = -dx * sin + dy * cos + cy
                val sx = kotlin.math.floor(fx).toInt()
                val sy = kotlin.math.floor(fy).toInt()
                if (sx in 0 until src.width && sy in 0 until src.height) {
                    blendPixel(tx, ty, src.pixels[sy * src.width + sx])
                }
            }
        }
    }

    /**
     * source-over 合成（非预乘）。alpha=0 不改目标，alpha=255 直接覆盖。
     *
     * 这是全部「合成式」画写（`fillRect` 非 replace / `strokeRect` / `drawLine` /
     * `blitFrom` / `rotateFrom`）的公共落点，[markDirty] 就收在这里、且只在 alpha!=0
     * （确定会写一个像素）之后调用 —— 「写了像素 ⇒ 作废文件源」「一笔没写 ⇒ 保留文件源、
     * 仍可 [evictDeferred]」两条都由这一处保证（类 KDoc 的画写不变量）。
     *
     * ## 通道式（2026-11-07 修「立绘脸红变脸黑」）
     *
     * 缓冲是**直通** ARGB（类 KDoc 像素布局段），所以合成结果也必须是直通值：
     * `out_rgb = (src_rgb×a×255 + dst_rgb×dst_a×ia) / (out_a×255)`、
     * `out_a = a + dst_a×ia/255`（ia = 255−a）。旧式 `out_rgb = (src×a + dst×ia)/255`
     * 只在 dst 不透明（dst_a=255 ⇒ out_a=255）时与它逐字节同值；**dst_a=0（透明画布）时
     * 旧式存出的是 `src×a` 的预乘形**（例：A128 R250 G80 B80 叠全透明 ⇒ 旧 (A128,R125,G40,B40)，
     * 正确 = 直通 (A128,R250,G80,B80)），显示端按直通消费 ⇒ 半透明特效叠层（照れ/吐息/汗
     * 这些先画到全透明画布的层）整体发暗 —— 玩家报的「立绘脸红变脸黑」本体（真素材对拍
     * 与截图特征见 BOARD 交付说明）。dst_a=255 档的钉值（GdrawSpriteCompositeTest 的
     * `0xFF693C28` 等）逐字节不变；中间档随 GDI+ 的 8-bit 预乘中间量在 ±2 内
     * （2026-10-23 实测 (A128,R200,G100,B50) 叠 (A128,R10,G20,B30) ⇒ GDI+ (A192,R135,G73,B42)，
     * 本式给 (A191,R137,G73,B43)，旧式是 (A191,R105,G60,B40)），契约 §4 A4 像素近似口径。
     */
    fun blendPixel(x: Int, y: Int, argb: Int) {
        val a = argb ushr 24
        if (a == 0) return
        // 越界静默跳过：与 [setPixel] 同口径（stability 波的既有裁决）——调用层多数自裁
        // （fillRect/strokeRect），但坏参数不该把 VM 线程打成 ArrayIndexOutOfBounds；
        // 「一笔没写 ⇒ 不 markDirty、保留文件源」的不变量同 setPixel 早退支。
        if (x < 0 || y < 0 || x >= width || y >= height) return
        markDirty()
        val idx = y * width + x
        if (a == 255) {
            pixels[idx] = argb
            return
        }
        val d = pixels[idx]
        val dstA = d ushr 24
        val ia = 255 - a
        val oa = a + dstA * ia / 255
        // 直通域合成：dst 的贡献按 dst_a 加权（预乘攒入分子），再除 out_a 反预乘。
        // 分子每通道 ≤ 255×255×255 + 255×255×254 ≈ 3.3×10^7，Int 不溢出。
        val w = dstA * ia
        val den = oa * 255
        val r = (((argb shr 16) and 0xFF) * a * 255 + ((d shr 16) and 0xFF) * w) / den
        val g = (((argb shr 8) and 0xFF) * a * 255 + ((d shr 8) and 0xFF) * w) / den
        val b = ((argb and 0xFF) * a * 255 + (d and 0xFF) * w) / den
        pixels[idx] = (oa shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------
    // GSAVE / GLOAD 的字节载体
    // ------------------------------------------------------------------

    /**
     * 编码为**真 PNG**（GSAVE 的落盘载体）。
     *
     * 原版 `graphicsImage.Bitmap.Save(saveDataPathGraphics)`（FunctionMethodCreator.cs:8125）
     * 不指定 `ImageFormat`，落盘格式由路径扩展名 `.png` 决定（:9471-9474 的
     * `$"{Config.SavDir}img{index:0000}.png"`）。GDI+ 侧实测（2026-10-24，本机 .NET 8
     * System.Drawing，`new Bitmap(4,3).Save("x.png")`）：
     * 签名 `89 50 4E 47 0D 0A 1A 0A`、IHDR `depth=8 colortype=6 comp=0 filter=0 interlace=0`
     * —— 与本函数的编码参数逐项一致。本函数把移植侧此前「写文本载体代偿」的那一步补成真 PNG。
     *
     * ## 编码参数（逐项有据）
     *
     * - **8 位/通道、色型 6（真彩 + alpha，非预乘）**：缓冲像素就是 `0xAARRGGBB`
     *   （见类 KDoc 的像素布局段），与 RGBA8 逐通道一一对应，无量化损失；
     * - **每扫描线 filter 类型 0（None）**：GDI+ 的 filter 选择是编码器内部启发式
     *   （反编译无行号、也无基准读数），复刻它既不可验证也无收益；像素级等价即够
     *   （契约 §4 A4 的像素近似口径）；
     * - **zlib 用 [Deflater] 默认级别**，逐行喂入：不物化整幅 RGBA 原始流
     *   （8192×8192 的扫描线是 256 MB，一次分配就会在真机堆上炸）。
     *
     * ## 失败口径
     *
     * 原版把 `Bitmap.Save` 整个包在 `try` 里，任何异常（含 GDI+ 的 `OutOfMemoryException`）
     * 都被 `catch` 折成 GSAVE 的返回 0（:8122-8130）。本函数**不吞异常**：编码失败照常抛
     * （`OutOfMemoryError` 也是），由函数层按同一口径折成 0（见 `fn/GraphicsMethods.kt`
     * 的 GSAVE 段），这样「缓冲未创建」这类编程错误不会被静默吃掉。
     *
     * @throws IllegalStateException 缓冲未创建（宽或高为 0）
     */
    fun toSavePng(): ByteArray {
        check(width > 0 && height > 0) { "未创建的缓冲不能编码 PNG（${width}x$height）" }
        val out = ByteArrayOutputStream(1 shl 16)
        // PNG 文件签名（PNG 规范 5.2）：89 50 4E 47 0D 0A 1A 0A
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR：宽、高、位深、色型、压缩方法、滤波方法、隔行扫描
        val ihdr = ByteArray(13)
        putIntBe(ihdr, 0, width)
        putIntBe(ihdr, 4, height)
        ihdr[8] = 8 // 位深 8 位/通道（与 0xAARRGGBB 逐通道对齐）
        ihdr[9] = 6 // 色型 6 = 真彩 + alpha（RGBA，非预乘）
        writeChunk(out, "IHDR", ihdr)
        val idat = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater()
        try {
            val row = ByteArray(1 + width * 4)
            val buf = ByteArray(1 shl 16)
            for (y in 0 until height) {
                row[0] = 0 // filter: None
                var p = 1
                val base = y * width
                for (x in 0 until width) {
                    val v = pixels[base + x]
                    row[p++] = ((v shr 16) and 0xFF).toByte()
                    row[p++] = ((v shr 8) and 0xFF).toByte()
                    row[p++] = (v and 0xFF).toByte()
                    row[p++] = (v ushr 24).toByte()
                }
                deflater.setInput(row)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(buf)
                    if (n <= 0) break
                    idat.write(buf, 0, n)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                idat.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        writeChunk(out, "IDAT", idat.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * 序列化为自描述文本（**旧** GSAVE 载体，现仅作回落与读侧兼容）。
     *
     * 原版 `Bitmap.Save(path)` 写 PNG（FunctionMethodCreator.cs:8125）。移植侧早先没有
     * 可移植的 PNG 编码器，用文本格式承载同一份 ARGB 像素：首行魔数+版本，第二行
     * `宽 高`，第三行起每行一个像素的 8 位十六进制。
     *
     * 现状（GFX-13）：GSAVE 在宿主实现 [BinaryFileOps] 时改写 [toSavePng] 的真 PNG；
     * 未接线时才回落本载体（`fn/GraphicsMethods.kt` 的 GSAVE 段）。读取侧
     * `GCREATEFROMFILE` / `GLOAD` 先按 PNG 解码真实图片
     * （[com.eratw.port.core.image.PngDecoder]），签名不是 PNG 时才回落本载体，
     * 故旧档与回落产物都仍可读，GSAVE → GLOAD 往返像素逐字节一致（契约 §4 A4）。
     */
    fun toSaveText(): String {
        // 初始容量按像素数估算（每像素 9 字符），但只按上限预分配；实际增长交给 StringBuilder，
        // 避免 `width * height * 9` 在超大缓冲上一次性申请数百 MB。
        val estimate = 64L + pixels.size.toLong() * 9L
        val cap = minOf(estimate, 1L shl 22).toInt()
        val sb = StringBuilder(cap)
        sb.append(SAVE_MAGIC).append('\n')
        sb.append(width).append(' ').append(height).append('\n')
        for (v in pixels) {
            val h = Integer.toHexString(v)
            for (k in h.length until 8) sb.append('0')
            sb.append(h).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /**
         * 共享空像素数组：「无像素」时读侧的统一返回值。
         * 空数组长度 0、不可变，天然可跨缓冲/跨线程共享（不存在元素级数据竞争）。
         */
        private val EMPTY_PIXELS = IntArray(0)

        /** GSAVE 文本载体魔数（见 [toSaveText]）。 */
        const val SAVE_MAGIC = "ERATW-GFX 1"

        /** 大端写 32 位整数（PNG 的块长 / CRC 与 IHDR 的宽高都是大端）。 */
        private fun putIntBe(dst: ByteArray, at: Int, v: Int) {
            dst[at] = (v ushr 24).toByte()
            dst[at + 1] = (v ushr 16).toByte()
            dst[at + 2] = (v ushr 8).toByte()
            dst[at + 3] = v.toByte()
        }

        /**
         * 写一个 PNG 块：`长度(4) 类型(4) 数据 CRC(4)`（PNG 规范 5.3）。
         *
         * CRC32 覆盖「类型 + 数据」，不含长度字段。
         */
        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val len = ByteArray(4)
            putIntBe(len, 0, data.size)
            out.write(len)
            val tag = type.toByteArray(Charsets.US_ASCII)
            out.write(tag)
            out.write(data)
            val crc = CRC32()
            crc.update(tag)
            crc.update(data)
            val sum = ByteArray(4)
            putIntBe(sum, 0, crc.value.toInt())
            out.write(sum)
        }

        /** 尺寸上限（`AbstractImage.MAX_IMAGESIZE`，AbstractImage.cs:8）。 */
        const val MAX_IMAGE_SIZE = 8192

        /**
         * 文本载体允许的最大像素数（只约束 [fromSaveText] 的**读**侧）。
         *
         * 文本载体每像素约 9 字符（8 位十六进制 + 换行），4,000,000 像素 ≈ 36 MB 字符串，
         * 再大就接近 JVM 堆上限。GSAVE 自 GFX-13 起写真 PNG（[toSavePng]），**不再有**这条
         * 限制：原版 GSAVE 对像素数无上限，失败只来自 `Bitmap.Save` 抛异常被 catch 折成 0
         * （FunctionMethodCreator.cs:8122-8130）。
         */
        const val MAX_SAVE_TEXT_PIXELS = 4_000_000L

        /**
         * 单图**像素数**上限（宽 × 高），解码侧（`PngDecoder` / `WebpDecoder`）在分配任何
         * 大缓冲之前用它拒绝巨图。
         *
         * 原版对宽或高 > 8192 的图返回 0（FunctionMethodCreator.cs:6905-6908），但**不限制
         * 像素总数**：8192×8192 在 GDI+ 里由 `new Bitmap(path)` 直接解出来，OOM 了也是
         * `Utils.LoadImage` 的空 catch 兜底（Utils.cs:521-523）→ null → 函数返回 0。移植侧
         * 解码器是自己分配 `IntArray(w * h)`，8192×8192 一张就是 256 MB 像素 + 同量级的
         * 解压缓冲，在真机堆上直接抛 `OutOfMemoryError`——而 `readImageFile` 只捕
         * `Exception`（GraphicsMethods.kt:971-975），Error 会一路炸穿 VM 线程。所以这里
         * 按「GDI+ 在内存受限宿主上 OOM ⇒ 加载失败」的同一结局，把上限**显式化**：
         * 超限在分配前抛解码失败（Exception），上层按原版口径折成 0。
         *
         * 取值：语料普查的最大图是 8192×4000 = 32,768,000 px（WebpDecoder KDoc 的语料
         * 读数），取 2 的幂 33,554,432（2^25，约 128 MB 像素）刚好覆盖语料、排除
         * 8192×8192 级别的巨图。
         */
        const val MAX_IMAGE_PIXELS = 33_554_432

        /** 颜色矩阵的元素个数（GDI+ `ColorMatrix` 是 5x5）。 */
        const val COLOR_MATRIX_SIZE = 25

        /**
         * GDI+ `ColorMatrix` 的单像素换算（总账 GFX-01，`GDRAWSPRITE` 的 7 参形态）。
         *
         * ## 索引口径（**不是**图形学课本的转置）
         *
         * [m] 是 25 个 Float 的**行主序**数组，**行 = 源通道、列 = 目标通道**，第 4 行是常量项
         * （偏移），即
         * `out[dst] = in[R]*m[0][dst] + in[G]*m[1][dst] + in[B]*m[2][dst] + in[A]*m[3][dst] + m[4][dst]`。
         * 依据：
         * - 脚本侧矩阵元素按 `array[i][j] = 变量值 / 256f` 装进 `float[5][5]`
         *   （spec: FunctionMethodCreator.cs:9427-9434 二维分支、:9454-9461 三维分支 ⇒ 第一个下标
         *   是脚本里 `ARG_CM:i:j` 的 `i`，它直接进 `ColorMatrix` 的 Matrix i）；
         * - `Matrix33` 被用作**不透明度**（spec: ConsoleBackground.cs:19-22 `colorMatrix.Matrix33 = opacity`）
         *   ⇒ 通道下标 0..3 = R,G,B,A、4 = 常量行；
         * - 通道换位的实测读数（2026-10-23 本机 System.Drawing / GDI+，`DrawImage(1x1, 1x1, Pixel, attr)`，
         *   源像素 A255 R200 G100 B50、目标全透明，单位阵打底后只改指名的那一个元素）：
         *   `m[0][0] = 0` 且 `m[0][1] = 1`（第 1 行仍是单位行）⇒ `A255 R0 G255 B50`
         *   （源 R 落到目标 G：G' = 200 + 100 = 300 → 夹到 1.0 → 255；若按「行 = 目标通道」
         *   应当得 `R100`，实测否掉了那一种）；
         *   `m[1][1] = 0` 且 `m[1][2] = 1` ⇒ `A255 R200 G0 B150`（源 G 落到目标 B：100 + 50）；
         *   偏移项取 `m[4][0] = 0.5` ⇒ `R200 → 255`（0.784+0.5 超过 1.0 后夹住），
         *   而把同样的 0.5 写在 `m[0][4]` 不产生任何偏移 ⇒ 偏移确实住在**第 4 行**。
         *
         * ## 数值口径（实测 2026-10-23，同一装置）
         *
         * 通道值按 `通道 / 255f` 归一后参与乘加，结果**先夹到 [0,1] 再 `round(v * 255f)`**：
         * - `m[0][0] = 0.5`（脚本值 128）对源 R=1/3/5/129/255 → 1/2/3/65/128，
         *   即 0.5/1.5/2.5/64.5/127.5 全部**四舍五入向上**（向下取整会是 0/1/2/64/127）；
         * - 脚本值 90/256 对源 R=2/4/5/7/9/255 → 1/1/2/2/3/90（0.703/1.406/1.758/2.461/3.164/89.65 取最近）；
         * - 负值夹到 0（`m[0][0] = -1` 配源 R=200 → 0）、超过 1 夹到 1（`m[0][0] = 2` 配源 R=200 → 255）；
         * - 对角全 256（=1.0）逐通道原样透传。
         * 换算在**直通（非预乘）ARGB** 上做：源 A=0、RGB=(200,100,50) 配「A 行偏移 +0.5」时
         * 实测结果 = 把 (A128,R200,G100,B50) 合成到目标上的值（若矩阵作用在预乘值上，RGB 会是 0）。
         *
         * ## 与 [blendPixel] 的分工
         *
         * 本函数只负责颜色换算，之后由 [blendPixel] 做 source-over 合成（原版从不设
         * `Graphics.CompositingMode`，取 GDI+ 默认 SourceOver ⇒ **叠加**而非覆盖，全仓检索无
         * `CompositingMode` 赋值点）；换算后 A=0 的像素一个都不改目标（实测：A=0 源配单位阵
         * 叠在不透明目标上目标保持 `A255 R10 G20 B30`；5x5 全 0 同样保持原值）。
         * 中间档残差（2026-11-07 blendPixel 直通域修正后）：GDI+ 的预乘中间量是 8-bit
         * （实测 源(A128,R200,G100,B50) 叠 目标(A128,R10,G20,B30) → `A192 R135 G73 B42`），
         * 本侧直通域整型式给 `A191 R137 G73 B43`（旧式 `A191 R105 G60 B40` 是把透明底
         * 也按「dst×(1−a) 权重吃掉 RGB」算的旧病，2026-11-07 修，见 [blendPixel] KDoc）；
         * ±2 属契约 §4 A4 像素近似口径。dst 两端档逐通道一致（实测
         * 源(A128,R200,G100,B50) 叠 目标(A255,R10,G20,B30) → 两侧同得 `A255 R105 G60 B40`）。
         *
         * @param m 25 个 Float，行主序，行 = 源通道、列 = 目标通道（见上）
         */
        fun applyColorMatrix(argb: Int, m: FloatArray): Int {
            require(m.size == COLOR_MATRIX_SIZE) { "ColorMatrix 必须是 ${COLOR_MATRIX_SIZE} 个 Float" }
            val a = argb ushr 24
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val nr = mixChannel(r, g, b, a, m, 0)
            val ng = mixChannel(r, g, b, a, m, 1)
            val nb = mixChannel(r, g, b, a, m, 2)
            val na = mixChannel(r, g, b, a, m, 3)
            return (na shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        /** [applyColorMatrix] 的单通道式：`Σ 源通道 × m[源][dst] + m[4][dst]`，夹到 [0,1] 后四舍五入。 */
        private fun mixChannel(r: Int, g: Int, b: Int, a: Int, m: FloatArray, dst: Int): Int {
            val f = (r / 255f) * m[dst] + (g / 255f) * m[5 + dst] + (b / 255f) * m[10 + dst] +
                (a / 255f) * m[15 + dst] + m[20 + dst]
            // Float 溢出可能是 NaN（脚本侧矩阵元素没有范围检查，spec: FunctionMethodCreator.cs:9432
            // 只做 `(float)long / 256f`）；NaN 走夹取会原样漏到 round 里，显式折 0。
            val v = if (f.isNaN()) 0f else f.coerceIn(0f, 1f)
            return Math.round(v * 255f)
        }

        /**
         * 解析 [toSaveText] 的产物；格式/尺寸不合法返回 null（GLOAD 返回 0）。
         * 不修改 this，返回新缓冲（GLOAD 走 `GCreateFromF`，会先释放旧内容）。
         *
         * 逐行用 [lineSequence] 迭代而不是 `split`：后者会把整份文本切成行数组，
         * 峰值内存翻倍（数十 MB 载体会多占一份等量对象），与 GSAVE 的 OOM 风险同源。
         * 像素数超过 [MAX_SAVE_TEXT_PIXELS] 按「载体不合法」返回 null，不尝试分配。
         */
        fun fromSaveText(id: Int, text: String): GraphicsBuffer? {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != SAVE_MAGIC) return null
            if (!lines.hasNext()) return null
            val dims = lines.next().trim().split(' ')
            if (dims.size != 2) return null
            val w = dims[0].toIntOrNull() ?: return null
            val h = dims[1].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return null
            val n = w.toLong() * h.toLong()
            if (n > MAX_SAVE_TEXT_PIXELS) return null
            val count = n.toInt()
            val px = IntArray(count)
            for (i in 0 until count) {
                if (!lines.hasNext()) return null
                val s = lines.next().trim()
                if (s.isEmpty() || s.length > 8) return null
                px[i] = (s.toLongOrNull(16) ?: return null).toInt()
            }
            val buf = GraphicsBuffer(id)
            return if (buf.createFromArgb(w, h, px)) buf else null
        }
    }
}

/**
 * `store.aux` 里图形池的槽名。
 *
 * 抽成常量是因为**两侧**都要用：语句层（`GraphicsMethods` 的 `gfx()`）写，宿主侧的
 * 内存回收（`GameHost.releaseResourceImagePixels` / `releaseUnusedGraphics`）读。
 * 早先两边各写一个字面量 `"gfx"`，改一处忘一处就是「回收永远扫到空表」这种静默失效。
 */
const val GRAPHICS_SLOT: String = "gfx"

/** `store.aux` 里 CBG 池的槽名（同 [GRAPHICS_SLOT] 的理由）。 */
const val CBG_SLOT: String = "cbg"

/**
 * `store.aux` 里 Sprite 池的槽名（同 [GRAPHICS_SLOT] 的理由）。
 *
 * `:platform` 的 `SpriteBitmapSource.SLOT_SPRITE` 直接引用本常量 —— 依赖方向是
 * `platform -> core-vm`，反向 import 不成立，所以单一来源只能放在 core 这一侧。
 */
const val SPRITE_SLOT: String = "sprite"

/**
 * 图形缓冲池。对应 `AppContents.gList`（AppContents.cs:20,28-37）。
 *
 * 按编号惰性建条目：`GetGraphics(i)` 对不存在的编号也会登记一个**未创建**的
 * `GraphicsImage`（这就是 `GDISPOSE(14)=0` 而非报错的原因——基准 651 行）。
 */
class GraphicsPool {
    /**
     * 条目表：**accessOrder** 的 LinkedHashMap —— 预算档（[evictDeferredPixelsWithinBudget]）
     * 要「最久未访问先出」，而这张表的迭代序本身就是 LRU 序（头部 = 最久没被 [get]/[peek] 碰过）。
     *
     * 为什么用表序而不是给每条目挂访问时间戳：**不新增实例字段**是 aux 槽对象的硬纪律
     * （`StateHash.instanceFields` 按字段名反射混入 `aux:gfx` 簇，加一个字段就要重落一次
     * L3 登记常量；登记值现为就绪态，重落是组长的活）。`StateHash` 对 Map 条目**按键文本
     * 排序后**混入，所以 backing 从 HashMap 换成 LinkedHashMap 不改链值一个字。
     *
     * 代价（登记在册）：accessOrder 的 `get` 是**结构性写**（重挂链表节点），全部入口都在本实例
     * 监视器内（[get] KDoc 的 `@Synchronized` 口径）；绕锁裸遍历这张表的诊断面
     * （L3 `StateHash`、perf 探针的 `ResidentWalker`）必须只在 VM 线程停住的点调用 ——
     * 这是既有纪律（旧 HashMap 并发 resize 同样炸遍历），只是从「碰巧只在增删时坏」
     * 变成明面上的约束。生产读侧（GameHost 报告/回收）走 [stats] / [residentBytes]，都在锁内或原子读。
     */
    private val buffers: MutableMap<Int, GraphicsBuffer> = LinkedHashMap(16, 0.75f, true)

    /**
     * 池内全部缓冲**真实占有的 Java 堆字节**（含降级态 PNG 载荷；跨线程安全的诊断读数，
     * 由各缓冲换代发布时增量维护，见 [GraphicsBuffer.publishSurfaceLocked]）。真机内存采样在 UI 线程读它，而条目增删发生在 VM 线程 —— 遍历 `buffers`
     * 会撞上 resize，所以读数走计数器不遍历（见 [GraphicsBuffer] 的构造参数）。
     */
    private val residentPixelBytes = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 累计「合成源拿不到像素」的笔数（[Stats.sourceMisses]）：每次 [GraphicsBuffer.blitFrom]
     * 因源像素不可用而放弃都 +1，并透传给池内每个缓冲（见 [GraphicsBuffer] 的构造参数 KDoc）。
     */
    private val sourceMisses = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 跨线程安全的像素驻留字节读数（诊断用，一次原子 get，可任意线程调用）。
     *
     * 与 [stats] 的 `pixelBytes` 同源同值（单线程时逐字相等）；并发换代的一瞬间可能偏一张图
     * 的量级 —— 诊断读数可接受，热路径不为此加锁。
     */
    fun residentBytes(): Long = residentPixelBytes.get()

    /**
     * 跨线程安全的条目数读数（`HashMap.size` 是一次普通 int 读，越界不会崩，只可能偏一次增删）。
     *
     * 只供诊断；要逐条内容仍得走 [stats]。与 [size] 同值 —— 本类是两条线各自加的读数口，
     * 名字都留着（`entryCount` 供内存采样串（`gfx=条目/字节`），`size` 供回收路的日志）。
     * `@Synchronized`：`HashMap.size` 虽是单次 int 读，但裸读在 JMM 下无可见性承诺
     * （报告线程可能长期看到陈旧值）；与同类读数口 [stats]/[size] 的加锁口径归一。
     */
    @Synchronized
    fun entryCount(): Int = buffers.size

    /** [stats] 的读数快照。
     *
     * @param entries 当前登记条目数（**含**未创建的空条目，即 [get] 惰性建出来的那些）
     * @param created `isCreated` 为真的条目数
     * @param pixelBytes 全部条目 [GraphicsBuffer.retainedBytes] 之和 —— 这一代**真实占有的
     *   Java 堆字节**，含降级态的 PNG 载荷。字段名沿用旧称，读数必须与
     *   [residentBytes] 恒等（`GraphicsPoolBudgetProbeTest` 钉着这条不变量）：
     *   只算已解码像素会让降级态的载荷从遍历读数里凭空消失。
     * @param demoted 像素已压成 PNG 闭包驻留的条目数（[GraphicsBuffer.isPixelsDemoted]，
     *   2026-11-04 OOM 波的第二档读数：真机报告里用它定性「画过合成图到底降没降级」）
     * @param sourceMisses 累计「合成源拿不到像素」的笔数（[GraphicsBuffer.blitFrom] 的源像素
     *   不可用分支）。原版没有这个状态（CSV 父图整张解码，失败即 sprite 作废，
     *   `AppContents.cs:273-278`）；这是移植侧延迟解码 + 像素预算长出来的第三种状态，
     *   非 0 就说明画面有「该画却画不出来」的合成层 —— 2026-11-06 布娃娃预览整页碎片即此。
     */
    data class Stats(
        val entries: Int,
        val created: Int,
        val pixelBytes: Long,
        val demoted: Int,
        val sourceMisses: Long = 0L,
    )

    /**
     * `AppContents.GetGraphics`：不存在则建一个空条目（IsCreated=false）。
     *
     * ## 为什么这些方法都 `@Synchronized`
     *
     * 写侧是 VM 线程（`readGraphics` 每条 G 语句一次），读侧除了 VM 线程还有**回收侧**：
     * `GameHost.releaseResourceImagePixels` 从 UI 线程（`onTrimMemory`）与 VM 线程
     * （回合边界的 `releaseImagePixelsIfHeapHigh`）两条路都会遍历整张表。普通 `HashMap`
     * 在遍历中被人 `getOrPut` 插一个新 gid 就会抛 `ConcurrentModificationException`，
     * 而那个异常发生在 UI 线程的 trim 回调里 —— 正是最不该炸的时刻。
     *
     * 用 `@Synchronized`（实例监视器）而不是加一个 `lock` 字段：多一个字段就多一个
     * `StateHash` 反射项，会把 L3 链值顶掉（见 `StateHash.HOST_DERIVED_FIELDS` 的说明）。
     * 锁内不做解码、不做绘制、不回调外部。
     */
    @Synchronized
    fun get(id: Int, payloadStore: PngPayloadStore? = null): GraphicsBuffer {
        val buffer = buffers.getOrPut(id) { GraphicsBuffer(id, residentPixelBytes, sourceMisses) }
        // 预算档自动检查（fix-pixelbudget 车道）：驻留像素超过 Java 堆侧预算就先退「重解必得
        // 同一张图」的那一档。判据全部复用 [evictDeferred]，这里只补它缺的**触发面**；
        // 每条 G 语句都经 [get]（`GraphicsMethods.readGraphics`），所以像素落地后的下一个
        // 语句点就是检查点。未超预算时零遍历。
        //
        // `protect = buffer`：**不许把刚交出去的这一张退掉**。少这道护栏时，「单张可重解图
        // 自己就超预算」的形状（小挡位机型 + 一张整工作表）会退化成
        // 「逐出 → 调用方下一次读像素就整图重解 → 再逐出」的每条语句重解码死循环 ——
        // 那比不加预算更糟。accessOrder 表序把刚碰过的排到尾部，逐出序上它本来就最晚出，
        // 但整表退完仍不达标那一趟会绕回来动它，所以要显式护住（读数见
        // `GraphicsPoolBudgetTest` 的「唯一一张且超预算」例）。
        //
        // 成本口径（组长验收时更正，别照抄旧文案的「一次原子读 + 一次除法」）：这一发除原子读
        // 外还付一次 [pixelBudgetBytes]，而它内含 `System.getProperty`（libcore 是属性表
        // 线性扫）；2026-11-03 内存分档起再加**一发**属性查表（[MemoryTier] 的 sdkInt 性质，
        // Hashtable 锁内），`Runtime.maxMemory()` 仍只读一发（档位判式复用同一个读数，
        // 见 [defaultPixelBudgetBytes]）。G 语句量级远小于变量访问，未超预算时也只付这一发，
        // 眼下没有实测证据说它贵；但**这两发的成本未实测**，已按「热路径代价实测」的规矩
        // 登记为下一手（回执在册）。
        evictDeferredPixelsWithinBudget(pixelBudgetBytes(), protect = buffer, payloadStore = payloadStore)
        return buffer
    }

    /** 只查不建。 */
    @Synchronized
    fun peek(id: Int): GraphicsBuffer? = buffers[id]

    /**
     * 只读诊断：条目数 / 已创建条目数 / 已驻留像素总字节。
     *
     * 只供诊断，**调用频率低**（一次 O(n) 遍历全部条目，不在绘制或语句热路径上）。
     *
     * 线程口径随本类的 `@Synchronized` regime：回收侧会从 UI 线程遍历这张表
     * （见 [get] 的 KDoc），本函数也挂同一把实例监视器 —— 任意线程可调，锁内只读不改。
     *
     * 不建条目（与 [peek] 同侧）、不淘汰、不改变 [get] 的惰性建条目语义；
     * 未创建 / 已释放条目按 [GraphicsBuffer.retainedBytes] 计 0；降级中的条目按
     * 它那代 PNG 载荷的真实字节数计（与 [residentBytes] 同一把尺）。
     */
    @Synchronized
    fun stats(): Stats {
        var created = 0
        var pixelBytes = 0L
        var demoted = 0
        for (buffer in buffers.values) {
            if (buffer.isCreated) created++
            // 与 residentPixelBytes 同一把尺：降级态的 PNG 载荷仍然占着 Java 堆，
            // 遍历读数不许把它数丢（见 Stats.pixelBytes 与本类 KDoc）。
            pixelBytes += buffer.retainedBytes
            if (buffer.isPixelsDemoted) demoted++
        }
        return Stats(buffers.size, created, pixelBytes, demoted, sourceMisses.get())
    }

    @Synchronized
    fun clear() {
        // 逐条 dispose 再清表：记账成对扣回（只 clear() 会让 residentBytes 永久虚高），
        // 且堆外载荷句柄随 dispose 的换代统一 close（OOM-B）—— 不走 dispose 的话
        // mmap 文件要等宿主退出清场才收，回标题（UnloadGraphicList）这一刀就漏了。
        for (buffer in buffers.values) buffer.dispose()
        buffers.clear()
    }

    /** 当前条目数（诊断用：G_ID 池「只增不减」到底增到多少）。 */
    @Synchronized
    fun size(): Int = buffers.size

    /**
     * 内存压力下让**所有**「内容仍等于文件」的缓冲退回未解码态，返回释放的像素条数。
     *
     * 与 `SpritePool.evictDeferredPixels` 的分工：那一版只扫 CSV 资源表的父图
     * （`resourceSprites` 引用的那批），这一版扫**整张 G_ID 表**。两者都必须做 ——
     * `GCREATEFROMFILE` 建的立绘工作表（8000x4000 = 122 MiB）挂在 G_ID 段，
     * 只要被 `GGETCOLOR` / `GSAVE` / `GDRAWG` 读过一次就会落进 Dalvik 堆，
     * 而它不归 `resourceSprites` 管（见 [GraphicsBuffer.evictDeferred] 的判据 2）。
     *
     * 这里**不需要**任何「谁还引用着它」的白名单：能回收的只有「一个像素都没被改写」的缓冲，
     * 重解出来逐字相同，谁引用都不影响结果。
     */
    @Synchronized
    fun evictDeferredPixels(): Long {
        var freed = 0L
        for (buffer in buffers.values) freed += buffer.evictDeferred()
        return freed
    }

    /**
     * **Java 堆侧主动像素预算**（fix-pixelbudget 车道，2026-11-06）：驻留超过 [budgetBytes] 时，
     * 按 LRU（[buffers] 的 accessOrder 迭代序 = 最久未访问在前）把 [evictDeferred] 那一档
     * 退回未解码态，直到驻留回到预算内、或可退的已全部退完。返回退回的**像素条数**
     * （口径同 [evictDeferredPixels]）。
     *
     * 这一档与两条既有路的关系：
     * - [evictDeferredPixels]（宿主水位档调用的全表版）：**不看预算、一次退干净**，维持原样；
     * - [releaseUnused]（真 dispose 画过的缓冲）：本函数**一律不碰** —— 那一档有脚本可见的
     *   风险面（KDoc 写在它自己身上），触发条件不许在这里放宽。
     *
     * 「可重解优先」是判据本身决定的：只有「内容恒等于文件解码结果」的缓冲（负号段 CSV 父图、
     * 或 [GraphicsBuffer.fileSource] 还在的 G_ID 工作表）能被退回去，重解逐字得同一张图，
     * 画过的根本不在候选集里；「最久未访问先出」由 accessOrder 表序给出 —— 脚本刚碰过的 gid
     * 排尾部，最先出的是它最久没碰的那些。换代全部经 [GraphicsBuffer.publishSurfaceLocked]
     * （池级记账 [residentPixelBytes] 成对跟着动），这是硬约束，不走旁路。
     *
     * [budgetBytes] ≤ 0 时判据恒不满足 ⇒ 整表扫一遍可退项，退化成全表版 [evictDeferredPixels]，
     * 这是「预算为 0 = 尽量退」的自洽读法，不是特判分支。
     *
     * @param protect 这一张**永不逐出**（[get] 传它刚交出去的那个缓冲；null = 无护持者）。
     *   整表退完仍超预算时，少了这一枚就会绕回来退掉调用方正要用的一张 ⇒ 每语句重解码死循环。
     * @param payloadStore 堆外载荷通道（OOM-B），透传给 [GraphicsBuffer.demotePixelsToPng]；
     *   null = 堆内闭包老路（纯 JVM 闸 / 语料对拍全是这种）。
     */
    @Synchronized
    fun evictDeferredPixelsWithinBudget(
        budgetBytes: Long = pixelBudgetBytes(),
        protect: GraphicsBuffer? = null,
        payloadStore: PngPayloadStore? = null,
    ): Long {
        if (residentPixelBytes.get() <= budgetBytes) return 0L
        var freed = 0L
        for (buffer in buffers.values) {
            if (residentPixelBytes.get() <= budgetBytes) break
            if (buffer === protect) continue
            freed += buffer.evictDeferred()
        }
        // 第二档（无损降级，2026-11-04 OOM 波）：可重解的退完仍超预算 ⇒ 把「画过的」按
        // accessOrder LRU 压成 PNG 闭包（[GraphicsBuffer.demotePixelsToPng]，像素逐字保留、
        // GCREATED/宽高不动、首次真读透明晋升）。真机 2026-11-04 五份 512 MiB 挡报告的形状：
        // 331 张已创建 / 279 MiB 驻留里超预算的部分**全部**是画过的合成图（每角色一张
        // highres_顔絵，1.44 MiB/张），而它们被 SPRITECREATE 引用着 —— 宿主 releaseUnused 的
        // exclude 白名单护住 ⇒ 此前所有档位对它们都是「收回 0」，Java 堆 511/512 MiB 后任何
        // 一次 1.44 MiB 分配就是 OutOfMemoryError（栈顶 GraphicsBuffer.create / blitFrom）。
        // 候选序沿用表序（最久未脚本访问在前）：页尾合成完的那批图脚本不再碰，正是该降的。
        // [GraphicsBuffer.demotePixelsToPng] 与第一档判据互补（可重解的已在上面的循环里退完，
        // 到这里的都是 fileSource == null 的正号段），protect 同口径护住刚交出去的那张。
        //
        // 时间帽：降级在 [get] 的每语句检查点上跑，单张编码几十 ms 量级 —— 不设帽的话
        // 「整页 150 张合成图第一次越线」那一发语句要一次性编完 150 张（秒级停顿）。
        // 帽只封**发起**：一张已经开始的编码照常做完（软帽，单发最坏多付一张的编码时长），
        // 剩下的由后续语句的检查点逐发推进 —— 每张降级当场释放 1.44 MiB，堆压力随进程回落。
        if (residentPixelBytes.get() > budgetBytes) {
            val deadline = System.nanoTime() + DEMOTE_PASS_BUDGET_NS
            for (buffer in buffers.values) {
                if (residentPixelBytes.get() <= budgetBytes) break
                if (buffer === protect) continue
                freed += buffer.demotePixelsToPng(payloadStore)
                if (System.nanoTime() >= deadline) break
            }
        }
        return freed
    }

    /**
     * 本池当前生效的像素驻留预算（字节）。
     *
     * 正常口径 = [defaultPixelBudgetBytes]（纯 JVM：`Runtime.maxMemory()` 按挡位换算，禁读任何
     * Android API —— core 层纪律）；系统性质 [PIXEL_BUDGET_OVERRIDE_PROPERTY] 只为**装置与定向
     * 单测**在任意挡位复刻设备挡位（512 MiB 挡 ⇒ 128 MiB），生产不设。性质每读一次现取，
     * 不在进程里缓存 —— 缓存就需要可变静态，违反铁律 4。
     *
     * fix-spritebudget 车道起算式本体上收到 [sharedPixelBudgetBytes]（伴生）：负号段的
     * [SpritePool.evictDeferredPixelsWithinSharedBudget] 与这里必须读同一档、同一旋钮。
     * 本方法保留为既有调用口，行为逐字不变。
     */
    fun pixelBudgetBytes(): Long = sharedPixelBudgetBytes()

    /**
     * 内存压力下**释放掉**没人引用的 G_ID 缓冲，返回释放的字节数（0 = 什么都没做）。
     *
     * ## 这是哪条欠账
     *
     * 本板登记的第一条长期欠账：「6400 张 G_ID 池只增不减、`@RM_全部リリース` 在本语料被注释掉」。
     * 语料里的分配口径是 `@GET_EMPTY_G`（`グラフィック特殊効果.ERB:254-267`）：
     * **扫描 `GCREATED(CNT)==0` 的 gid 再 `GCREATE`** —— 也就是说复用完全依赖「有人 dispose 过」。
     * 而唯一会 dispose 的 `@RM_全部リリース`（`ResourceManager.ERB:268-286`）在 :297/:302
     * 被作者注释掉了，理由写在 :291「清除图像缓存只会让游戏有更多卡顿机会」。
     * 于是 `縁取り` 这类每帧 `GET_EMPTY_G` 四次（结果图 + 两张临时图 + 掩膜）的路径
     * **一次都不回收**，gid 一路涨到 `THROW 图像过多`，中间那些整屏大小的缓冲全在堆里。
     *
     * ## 为什么这么做是安全的（以及哪里不安全）
     *
     * 这一刀就是语料自带那个函数的**内存压力版**：`@RM_全部リリース` 无条件
     * `GDISPOSE` 掉 `RM_GMAP` 里的每一个 gid（连「谁在用」都不看），而这里还多两道过滤 ——
     * 只动**没有被任何 Sprite / CBG 图层引用**的（[exclude]），且只动**够大**的
     * （[minPixels]，小块回收纯属白折腾）。
     *
     * 仍然存在的不安全面：脚本把 gid 存在自己的变量里、而该 gid 又没挂到 Sprite/CBG 上时，
     * 释放后那一格会变成「未创建」。这与语料作者自己那句 `CALL RM_全部リリース` 的行为一致，
     * 且**只在堆真的贴顶时**发生（[shouldReleaseImagePixels] 的水位闸），
     * 取舍是「可能一帧空白」对「进程被系统杀掉」。日志会记下释放了多少个、多少 MiB。
     *
     * @param exclude 不能动的缓冲（Sprite 的基图与动画帧、CBG 图层用的 gid）
     * @param minPixels 只释放「宽×高 ≥ 这个数」的缓冲，省得为几十字节的图反复折腾
     * @param budgetBytes 预算档（2026-11-01 收件箱「Java 堆贴顶」）：驻留像素**收到这里为止**就停手，
     *   按 [buffers] 的 accessOrder 序（最久未访问在前）出队。null = 不分档，
     *   与加这个参数之前逐字相同（92% 水位那一档与 `onTrimMemory` 强档请求都走 null）。
     *
     *   ## 为什么「画过的」也要有预算档（设备实证）
     *
     *   `evictDeferredPixelsWithinBudget` 只能退「重解必得同一张图」的那一档，而真机上问题
     *   恰恰不在那一档：2026-11-01 的 0.2.35 轨迹（HONOR ALI-AN00，512 MiB 挡）稳态
     *   `used=500~511MiB`、图形池 657 张**全部已创建**、`midpage.trim` 每次收回 0 MiB ——
     *   页中回收挑不到任何可重解项，95 秒的长页（`@APPLYCOLOR_F`）就是这样一路顶到 OOM 的。
     *   所以预算必须有第二根闸：驻留超预算时，连**脚本画过的**（无重解源的）也按 LRU 退到预算内。
     *   风险面与 [releaseUnused] 正文一致（脚本手里留着的 gid 可能变「未创建」），因此调用方
     *   只在堆水位高企时才用它（`GameHost.releaseImagePixelsIfHeapHigh` / [GameHost] 的页中悬崖档），
     *   且始终带 [exclude] 护住 Sprite / CBG 正在显示的那批。
     *
     *   [budgetBytes] ≤ 0 = 尽量收（判据恒不满足 ⇒ 退化成不分档的全量版），与
     *   `evictDeferredPixelsWithinBudget` 的同款读法一致，不另开特判分支。
     */
    @Synchronized
    fun releaseUnused(
        exclude: Set<GraphicsBuffer>,
        minPixels: Int,
        budgetBytes: Long? = null,
    ): ReleaseResult = releaseUnusedLocked(minPixels, budgetBytes) { it in exclude }

    /**
     * [releaseUnused] 的**零分配护栏版**（refbuffers 波，2026-11-05；组长为这一发开的
     * core 侧口子）：判停、门槛、`fileSource` 分支、dispose 顺序与 `ReleaseResult` 读数全部
     * 走**同一段**循环体（[releaseUnusedLocked]），差别只在「不许动」这一判从哪里读 ——
     * 这里读 [SpriteEvictScratch.containsGuard]（身份开放寻址表），[releaseUnused] 读
     * `Set.contains`。
     *
     * ## 为什么必须吃面而不是 `Set`
     * 宿主唯一的贴顶消费点（`GameHost.releaseUnusedGraphics`）每发都要为这张名单现搭
     * `HashSet`：一张按水位长的引用数组 + 每条目一个 32–40 字节的 `HashMap.Node`，
     * 而这一发**只在堆 ≥80%/≥92% 时才走** ⇒ 与真机报告 `6c08e8bb` 簇「回收闸自己把堆打爆」
     * 同族；三万量级 Sprite 就是 MB 级。复用面稳态是纯读写（口径见 [SpriteEvictScratch]
     * 的类 KDoc）。官方对照形状（常驻登记表 + 逐项布尔判据，AppContents.cs:26/:217-229、
     * GraphicsImage.cs:29）与「官方并不在本相位现搭名单」的判词写在
     * [SpritePool.addReferencedGuards] 的 KDoc，一处不重复。
     *
     * ## 成员判定逐字等价
     * `GraphicsBuffer` 未覆写 `equals`/`hashCode` ⇒ 旧 `HashSet` 的判同就是引用判同，与
     * [SpriteEvictScratch.containsGuard] 的 `===` 同一把尺；装载面由
     * [SpritePool.addReferencedGuards] 与 [SpriteEvictScratch.copyGuardsFrom] 保证与旧
     * `referencedBuffers()` ∪ CBG 逐项同集（等价臂钉在 `RefBufferGuardFaceTest`）。
     *
     * ## 线程与面生命周期
     * 本池 `@Synchronized` 只护 `buffers`；[guards] 由调用方持有、**必须**与调用同线程装载
     * （宿主那一发有 `onVmThread()` 硬闸）。本函数**只读**护栏面、不写 ⇒ 同一块面在
     * `releaseUnused` 之后仍可给下一档（合成图档）继续用。
     *
     * @param guards 已装好「不许动」名单的复用护栏面（本函数不清空、不重置它的表）
     * @param minPixels 同 [releaseUnused]
     * @param budgetBytes 同 [releaseUnused]
     */
    @Synchronized
    fun releaseUnused(
        guards: SpriteEvictScratch,
        minPixels: Int,
        budgetBytes: Long? = null,
    ): ReleaseResult = releaseUnusedLocked(minPixels, budgetBytes) { guards.containsGuard(it) }

    /**
     * 两把 [releaseUnused] 出入口共用的循环本体。`inline` ⇒ 两个调用点都不生成 `Function1`
     * 对象（护栏判定直接内联进循环，稳态零分配），同时保证「判停/门槛/分支」只有一份代码、
     * 两臂不可能各自漂移。调用方必须已持有本池监视器（两把出入口都是 `@Synchronized`）。
     */
    private inline fun releaseUnusedLocked(
        minPixels: Int,
        budgetBytes: Long?,
        isExcluded: (GraphicsBuffer) -> Boolean,
    ): ReleaseResult {
        if (budgetBytes != null && residentPixelBytes.get() <= budgetBytes) return ReleaseResult(0, 0L)
        var count = 0
        var bytes = 0L
        // 一边遍历一边 dispose：dispose() 只改缓冲自身状态、不动表结构，所以迭代安全；
        // 预算档要在每一刀之后重读驻留读数才能「够预算就停」，先收集再释放做不到这一点。
        val disposedIds = ArrayList<Int>()
        for (buffer in buffers.values) {
            if (budgetBytes != null && residentPixelBytes.get() <= budgetBytes) break
            if (!buffer.isCreated) continue
            if (isExcluded(buffer)) continue
            // 文件源还在的交给 evictDeferredPixels：那批能原样重解，dispose 掉纯属浪费
            // （下次读要重新走一遍文件解码，而且尺寸/GCREATED 还得靠它维持）。
            if (buffer.fileSource != null) continue
            val area = buffer.width.toLong() * buffer.height.toLong()
            if (area < minPixels.toLong()) continue
            bytes += area * 4L
            buffer.dispose()
            disposedIds.add(buffer.id)
            count++
        }
        // 空壳条目摘表（2026-11-08 OOM-A 波，治「buffers 只增不减」）：dispose 过的条目
        // 只剩一个全零的 GraphicsBuffer 壳挂在表里，语料 `@GET_EMPTY_G`
        // （グラフィック特殊効果.ERB:254-267）每帧扫空编号发新 gid，旧口径下这些壳随游玩
        // 时长线性累积（2026-11-07 玩家报告 5783b90 的图形池读数 = 1543 项）。摘掉后
        // 下一次 get(id) 走 getOrPut 重建空条目，isCreated == false ⇒ GCREATED == 0，
        // 与 PC 原版「释放后可复用」语义逐字一致（GraphicsImage.cs:528-560 释放路径）。
        //
        // 安全论证：走到 dispose 的条目必然不在 guards（isExcluded 先过滤）、必然无
        // fileSource（上面 continue 过滤），而 guards 面 = 具名 sprites ∪ CBG ∪ 资源表
        // （等价臂钉在 RefBufferGuardFaceTest）⇒ 没有任何引用方持有这个 GraphicsBuffer
        // 对象，摘表后重建的新对象不会与旧对象分叉。动画槽等其他 aux 持有面是否全进
        // guards：TODO(verify)（卡点 = 无真机复现「动画引用的合成图被本档 dispose」的形状）。
        //
        // 摘除放循环外：accessOrder 表在迭代中结构性增删会 CME；这里每次回收多付一个
        // 小 ArrayList（水位触发的低频路径，非热路径），换「dispose 过的壳不再常驻」。
        for (id in disposedIds) buffers.remove(id)
        return ReleaseResult(count, bytes)
    }

    /** [releaseUnused] 的读数快照。 */
    data class ReleaseResult(val buffers: Int, val bytes: Long)

    companion object {
        /**
         * 预算分母：驻留像素预算 = JVM 堆上限 / 这个数。算式一眼版：
         *
         * **预算 = `Runtime.getRuntime().maxMemory()` ÷ 4**
         * —— 真机 largeHeap **512 MiB** 挡 ⇒ **512 ÷ 4 = 128 MiB**。
         *
         * 取值论据（逐项可核）：
         * 1. 本仓最大的单一 Java 堆持有者是 G_ID 工作表的 `IntArray` 像素
         *    （ARGB 4 B/像素，[GraphicsBuffer.pixelBytes]）：一张 8000×4000 = 32,000,000 像素
         *    × 4 B = **122.1 MiB**（[GraphicsBuffer.fileSource] 的 KDoc 与本车道任务书同读数）。
         *    128 MiB 预算恰好容得下「一张整工作表 + 几张整屏级中图」常驻；第二张工作表一叠上
         *    （244 MiB）下一个语句点就逐出最久未碰的可重解项。
         * 2. 剩下 3/4（384 MiB）留给装载基线与瞬时拷贝：标题页稳态 live ≈ 69 MiB
         *    （`:core-vm:heapRootAttribution` 同产物读数，PhaseHeapLadderProbe 的 P0 对表行）、
         *    真机回合轨迹稳态 used ≈ 182 MiB（HeapCompositionProbeTest 类 KDoc），
         *    再加整图解码的瞬时份（[GraphicsBuffer.createFrom] KDoc：接管口径下 1×，
         *    尺寸不符时 2×）与控制台/变量池 —— ÷2（256 MiB）会让两张工作表常驻、预检余量归零，
         *    ÷8（64 MiB）则一张工作表就超预算，逐出后下一次 `GGETCOLOR` 必重解，纯烧 CPU。
         * 3. 与被动水位档对表：GameHost 的水位在堆 **80%** 才叫回收
         *    （`RunTrail.RELEASE_AT_PERCENT = 80`，512 MiB 挡 ⇒ 410 MiB 已用），而预检本身要吃掉
     *    一大截基线，像素驻留等到 410 MiB 才动就没有节制可言；128 MiB 预算档在**驻留自己**
         *    贴顶前就限住它，水位档退化为兜底而不再是唯一触发面。
         * 4. 桌面装置：3g 测试挡 ⇒ 768 MiB 预算，黄金语料的驻留远够不着 ⇒ 装载/基准行为与加档
         *    前逐字一致；即便够着，逐出对脚本也不可见（重解逐字同图，尺寸/`isCreated` 不动，
         *    `GraphicsImage.cs:44-54` / `ASpriteSingle.cs:24-34`，反编译路径见 ERATW_ROOT 下
         *    reference/emuera-cs）。
         *
         * TODO(verify)：÷4 是按上面第 1/2 条的静态算式定的；设备侧真机驻留峰值分布
         * （回合轨迹的 `used=` 序列 + 图形池行的并排样本）到手后可能要把分母做成按挡位查表。
         * 卡点：需要装机的 RunWatch 轨迹上报样本，桌面复现不了 512 MiB 挡的分配形状。
         */
        const val PIXEL_BUDGET_DIVISOR: Long = 4L

        /**
         * 预算闸第二档（无损降级）单发时间帽（纳秒）：一次 [GraphicsPool.get] 出口的降级
         * 最多发起这么久的编码（软帽，见 [GraphicsPool.evictDeferredPixelsWithinBudget] 的
         * 时间帽注）。25 ms ≈ 一张 600×600 合成图的编码时长量级 —— 帽的价值不在省总工时
         * （该编的总得编完），而在把「整页合成图第一次越线」的一次性秒级停顿摊薄到后续
         * 每语句检查点上。
         */
        const val DEMOTE_PASS_BUDGET_NS: Long = 25_000_000L

        /**
         * 预算覆写性质（字节）：仅供定向单测与性能装置在任意 JVM 挡位复刻设备挡位
         * （`-Deratw.graphics.pixelBudgetBytes=134217728` = 512 MiB 挡的 128 MiB 预算）。
         * 生产不设；读法见 [pixelBudgetBytes]。
         */
        const val PIXEL_BUDGET_OVERRIDE_PROPERTY: String = "eratw.graphics.pixelBudgetBytes"

        /**
         * 默认预算 = 堆上限 ÷ [PIXEL_BUDGET_DIVISOR]（÷4），**不随 [MemoryTier] 放开档抬分母**。
         *
         * 这一档量的是 `IntArray` 像素（Dalvik 堆），不是 `SpriteBitmapSource` 的 native 位图：
         * 放开档「API >= 26 位图内存改道」那条前提对它不成立 —— 抬到 ÷3（512 MiB 挡 170 MiB）
         * 等于从「3/4 留给装载基线与瞬时拷贝」的余量里划走 42 MiB。2026-11-04 真机报告
         * （LOOK_AT 差分立绘合成）正是这份余量见底的形状：Java 堆 511/512 MiB、正号段驻留
         * 248 MiB，`blitFrom` 一发 0.5 MiB 瞬时分配就 OOM。放开档只抬 native 侧
         * （工作表缓存 192 → 384 MiB，见 `SpriteBitmapSource.defaultSheetCacheBytes`）。
         *
         * 堆上限不可知（无上限/读数为 0）时返回 [Long.MAX_VALUE] = 预算档不生效，绝不拿一个
         * 拍出来的绝对值去截桌面装置。
         *
         * `maxMemoryBytes` 缺省 = 本进程现取；显式传它只为**定向单测**在不自控测试 JVM 挡位的
         * 前提下复刻设备挡位（注入点口径同 [PIXEL_BUDGET_OVERRIDE_PROPERTY]，生产不传）。
         */
        fun defaultPixelBudgetBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Long {
            if (maxMemoryBytes <= 0L || maxMemoryBytes == Long.MAX_VALUE) return Long.MAX_VALUE
            return maxMemoryBytes / PIXEL_BUDGET_DIVISOR
        }

        /**
         * **共用一档**预算的现取读法（fix-spritebudget 车道）：覆写性质优先，回落
         * [defaultPixelBudgetBytes]。算式本体原先只在 [GraphicsPool.pixelBudgetBytes] 实例方法里，
         * 负号段（[SpritePool] 的 CSV 父图预算闸）拿不到 [GraphicsPool] 实例也要读同一档 ——
         * 判据 2 明令不许发明第二个预算常数，所以把这段「性质 + 默认算式」上收到伴生，
         * 实例方法一字不改语义地委托过来。正/负两段的检查点从此共用**一个**旋钮
         * （覆写性质、分母、不可知堆的 MAX 回落全部同源）。
         *
         * 每读现取、不在进程里缓存：缓存就需要可变静态，违反 core 层纪律（口径同实例侧）。
         */
        fun sharedPixelBudgetBytes(): Long {
            val override = System.getProperty(PIXEL_BUDGET_OVERRIDE_PROPERTY)?.trim()?.toLongOrNull()
            if (override != null && override > 0L) return override
            return defaultPixelBudgetBytes()
        }
    }
}

/**
 * 回收闸的**可复用记账面**（oomsrc 波，2026-11-05）：负号段预算闸与合成图档在
 * 「堆快满了，去回收像素」这条自救路上需要的两件事——按对象身份去重、按首次
 * 登记序遍历候选——过去每次调用都用 `HashSet`/`LinkedHashSet`/`ArrayList` 现搭
 * （2026-11-04 真机报告 `6c08e8bb` 簇：`SpritePool.evictDeferredPixelsWithinBudget`
 * 里 `HashMap.resize` 新建的 0.5 MiB 引用数组就是压垮堆的那一笔，报告头
 * `giving up on allocation because <1% of heap free after GC`）。本类把这两件事
 * 收进**稳态零分配**的形状：
 *
 * - **身份开放寻址表**（`Any===` 判同，不依赖 equals —— `GraphicsBuffer` 未覆写，
 *   语义与旧 HashSet 逐字相同）：引用数组槽位复用，`clear` 只填 null，
 *   **不缩表、不重挂节点**；容量只在历史水位创新高时翻倍（`HashSet` 每次
 *   `add` 都要新建一个 `HashMap.Node`（约 32–40 B），而真机实录连 40 字节的
 *   分配都能在贴顶堆上失败（同簇样张 `6c08e8bb-1`），所以复用 stdlib 集合
 *   等于没修——这就是本类存在的理由）；
 * - **候选序数组**：首次登记序与 [SpritePool] 旧 `ordered` 列表逐项同序，
 *   判停算术逐字不变；
 * - **护栏表**（只判身份、不记序）：给合成图档的 protectedBuffers 名单用，
 *   与候选面同住一个对象，宿主一个字段就能护住整条回收路。
 *
 * ## 口径纪律（对齐 core/diag/LockWatch 的采样面）
 * 对面 LockWatch 的判词是「构造期定长的 8×4 槽数组，无一张会增长的表；关时零
 * 分配」。本类做不到严格定长（水位装载前不知道候选数），取的是同一条口径的
 * 弱化形：**分配只发生在历史水位创新高那一次**；稳态每次回收路走完都是纯读写。
 *
 * ## 线程与所有权
 * 本类**无内部锁**：一块面同一时刻只许一条线程摸。落地上由调用方保证——
 * 回合边界与页中检查点全在 VM 线程（`GameHost.releaseImagePixelsIfHeapHigh`
 * 的既有线程约束，见该方法 KDoc），遍历本体又收在 `SpritePool` 的监视器里，
 * 于是「宿主持有一块面 + 池锁护遍历」就是全部的同步面。不新增静态可变全局
 * （铁律 4）：面挂在**调用方**实例字段上，随宿主生命周期回收。
 *
 * ## 为什么不加进 SpritePool 的字段
 * `SpritePool` 挂在 `store.aux` 槽上被 L3 StateHash 按**字段名**反射混入
 * `aux:sprite` 簇（该类 KDoc「不新增实例字段是本池的硬纪律」一节），本类是
 * 参数不是字段，池的登记形状一个字没动。
 */
class SpriteEvictScratch {

    private var candidateTable = arrayOfNulls<GraphicsBuffer>(0)
    private var candidateOrder = arrayOfNulls<GraphicsBuffer>(0)
    private var candidateSize = 0
    private var guardTable = arrayOfNulls<GraphicsBuffer>(0)
    private var guardSize = 0

    /** 清空两张表的**内容**（容量保留——缩表就等于把稳态零分配还回去）。 */
    fun clear() {
        java.util.Arrays.fill(candidateTable, null)
        java.util.Arrays.fill(guardTable, null)
        candidateSize = 0
        guardSize = 0
    }

    /** 候选数（= 去重后的首次登记序长度）。 */
    fun candidateCount(): Int = candidateSize

    /** 第 [index] 个候选；越界由调用方的循环上界兜住（本类不做防御拷贝，这是热面）。 */
    fun candidateAt(index: Int): GraphicsBuffer =
        candidateOrder[index] ?: throw IndexOutOfBoundsException("candidate index $index >= $candidateSize")

    /** 身份判同：这个缓冲在**本次 clear 之后**被 [addCandidate] 收过吗。 */
    fun containsCandidate(buffer: GraphicsBuffer): Boolean {
        if (candidateSize == 0) return false
        return candidateTable[slotOf(candidateTable, buffer)] === buffer
    }

    /** true = 首次见到（并追加到候选序尾部）；false = 已收过，序与计数不动。 */
    fun addCandidate(buffer: GraphicsBuffer): Boolean {
        if ((candidateSize + 1) * 2 > candidateTable.size) growCandidates()
        val slot = slotOf(candidateTable, buffer)
        if (candidateTable[slot] === buffer) return false
        candidateTable[slot] = buffer
        candidateSize++
        if (candidateSize > candidateOrder.size) {
            candidateOrder = candidateOrder.copyOf(maxOf(candidateOrder.size * 2, 16))
        }
        candidateOrder[candidateSize - 1] = buffer
        return true
    }

    /** 身份判同：这个缓冲在**本次 clear 之后**被 [addGuard] 登记过吗。 */
    fun containsGuard(buffer: GraphicsBuffer): Boolean {
        if (guardSize == 0) return false
        return guardTable[slotOf(guardTable, buffer)] === buffer
    }

    /**
     * 护栏数（= 去重后的「不许动」名单长度）。
     *
     * refbuffers 波（2026-11-05）加的读数口：宿主回收路的日志原来直接打 `exclude.size`
     * （现搭的 `HashSet` 的规模），换复用面后这条读数的口径必须逐字不变 ⇒ 由本口给同一个数。
     * 纯字段读，零分配。
     */
    fun guardCount(): Int = guardSize

    /**
     * 把 [source] 的护栏项逐条并入本面（**只判身份、不记序**，与 [addGuard] 同一张表）。
     *
     * refbuffers 波加的搬运口：宿主的「不许动」名单现在长住在护栏面里，而合成图档要的护栏
     * 是本面的子集（CBG 那一半）+ 资源表引用 ⇒ 需要「面 → 面」的搬运而不是「集合 → 面」的
     * 装载，否则就得为每一发重新物化一张 `Set`（正是本波要治的病灶）。
     *
     * 语义：并入后 `containsGuard(x)` 对本调用前后任一来源登记过的 x 恒为 true；
     * 重复并入同一项不改变计数（走 [addGuard] 的首判）。遍历的是 [source] 的**槽位数组**
     * （容量恒为 2 的幂、装载率 ≤ 0.5 ⇒ 至多扫 2×n 个槽），全程无节点对象、无迭代器分配。
     *
     * 同对象自并（`source === this`）当场无事可做：本面每一项已在自己表里，[addGuard]
     * 的首判全部命中 ⇒ 直接返回，不读不写（省一趟纯浪费的扫描，也免得调用方误以为
     * 「把自己清空再灌」是合并的写法）。
     */
    fun copyGuardsFrom(source: SpriteEvictScratch) {
        if (source === this) return
        val table = source.guardTable
        var i = 0
        while (i < table.size) {
            table[i]?.let { addGuard(it) }
            i++
        }
    }

    /**
     * 登记一个「不许动」的缓冲（重复登记无害，计数只在首次增长）。
     *
     * 返回形是 **true = 本次 clear 之后首次见到**（并已登记）、false = 已在表里（计数不动）。
     * reportres 波（2026-11-05）把原来吞掉的 `Unit` 收成 `Boolean`：[SpritePool.resourceResidentPixelBytes]
     * 面臂要在**同一条口**上拿到首判才能求和不重复计父图，
     * 语义与 [addCandidate] 的首判逐字相同，只是不记序。既有调用点全部把这个口当语句用
     * （`for` / `?.let` 里逐条灌 ⇒ 返回值落进 `let` 的 lambda 结果里被丢弃），一条判据都不动。
     */
    fun addGuard(buffer: GraphicsBuffer): Boolean {
        if ((guardSize + 1) * 2 > guardTable.size) growGuards()
        val slot = slotOf(guardTable, buffer)
        if (guardTable[slot] === buffer) return false
        guardTable[slot] = buffer
        guardSize++
        return true
    }

    /**
     * 线性探测槽位：`identityHashCode` 低位取模（容量恒为 2 的幂）。
     * 调用方保证装载率 ≤ 0.5，空槽必达，循环必然终止。
     */
    private fun slotOf(table: Array<GraphicsBuffer?>, buffer: GraphicsBuffer): Int {
        var i = (System.identityHashCode(buffer) and 0x7FFFFFFF) and (table.size - 1)
        while (true) {
            val cur = table[i]
            if (cur === null || cur === buffer) return i
            i = (i + 1) and (table.size - 1)
        }
    }

    /** 候选表翻倍并重挂全部现存项（只在水位创新高时发生；序不受影响——序在另一根数组上）。 */
    private fun growCandidates() {
        val next = arrayOfNulls<GraphicsBuffer>(maxOf(candidateTable.size * 2, 16))
        for (j in 0 until candidateSize) {
            val buffer = candidateOrder[j] ?: continue
            next[slotOf(next, buffer)] = buffer
        }
        candidateTable = next
    }

    /** 护栏表翻倍并重挂全部现存项（同上；本表无遍历语义，逐槽搬即可）。 */
    private fun growGuards() {
        val next = arrayOfNulls<GraphicsBuffer>(maxOf(guardTable.size * 2, 16))
        for (old in guardTable) {
            if (old != null) next[slotOf(next, old)] = old
        }
        guardTable = next
    }
}

/**
 * Sprite 池。对应 `AppContents.imageDictionary`（运行时表）与 `resourceImageDictionary`
 * （CSV 资源表，AppContents.cs:16-22）。
 *
 * 名字一律按 `ToUpper(InvariantCulture)` 归一（AppContents.cs:45/57/86/102）。
 * [Sprite.isCreated] 的语义分两种（ASpriteSingle.cs:24-34 / SpriteAnime.cs:54）：
 * - 静态 Sprite（SpriteG/SpriteF）：基图存在且基图已创建；
 * - 动画 Sprite（SpriteAnime）：恒 true（帧列表可以为空）。
 *
 * ## 线程口径：UI 线程只读安全
 *
 * 内部容器是普通 `LinkedHashMap`，而读侧（platform 的 `SpriteBitmapSource` 经
 * `aux.peek` 拿到本实例后，在 UI 线程 `CanvasConsoleView.onDraw` 每帧 `find`）
 * 与写侧（VM 线程的 SPRITECREATE / SPRITEDELETE 语句）跨线程并发。因此**全部
 * 公开方法（含 [animeTimerMs] 的读写）都收进 `this` 监视器**：保证容器结构一致
 * ——不会读到 resize 半途的表、不会迭代失败——调用方（读侧在 platform 模块、
 * 写侧在 core-vm 语句层）无需自己加锁。锁内不做任何外部回调或绘制。
 *
 * 注意 [Sprite] 本身仍含可变状态（[Sprite.posX]/[Sprite.posY]、[Sprite.frames]
 * 列表、buffer 像素数组）：`find` 返回引用后对这些字段的读取不在本锁范围内，
 * UI 侧可能读到 VM 线程刚写入的新值（弱一致，只会「下一帧生效」，对象结构不
 * 会被破坏）；[Sprite.name]/[Sprite.width]/[Sprite.height]/srcX/srcY/srcW/srcH
 * 是登记后不再变化的 val，读侧可放心使用。
 */
class SpritePool {

    /** 动画帧。对应 `SpriteAnime.AnimeFrame`（SpriteAnime.cs:12-42）。 */
    class Frame(
        val buffer: GraphicsBuffer,
        val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
        val offsetX: Int, val offsetY: Int,
        val delayMs: Int,
    )

    /**
     * 单个 Sprite。
     *
     * @param frames null = 静态 Sprite（引用 [buffer] 的 [srcX]/[srcY]/[srcW]/[srcH] 区域）；
     *   非 null = 动画 Sprite（帧列表，[buffer] 无意义）。
     * @param width  `DestBaseSize.Width`（缩放后的目标尺寸）。
     * @param height `DestBaseSize.Height`。
     */
    class Sprite(
        val name: String,
        val width: Int,
        val height: Int,
        val buffer: GraphicsBuffer? = null,
        val srcX: Int = 0,
        val srcY: Int = 0,
        val srcW: Int = width,
        val srcH: Int = height,
        var posX: Int = 0,
        var posY: Int = 0,
        val frames: MutableList<Frame>? = null,
    ) {
        val isAnime: Boolean get() = frames != null

        /**
         * `ASpriteSingle.IsCreated` / `SpriteAnime.IsCreated`（`ASpriteSingle.cs:24-34` / `SpriteAnime.cs:54`）。
         *
         * ## 为什么 CSV 资源 sprite 不看父图的当前像素态
         *
         * 官方 `ASpriteSingle.IsCreated` 读的是 `BaseImage != null && BaseImage.IsCreated`
         * （`ASpriteSingle.cs:24-34`），`SpriteF.BaseImage` 是 `ConstImage`
         * （`CreateFromCsv` 装载期一次建好的 `Bitmap`，`AppContents.cs:273-293` /
         * `SpriteF.cs:5-10`），而 `ConstImage.IsCreated => RealIsCreated`（`ConstImage.cs:21`）。
         * 这份 `ConstImage` 不在 `UnloadGraphicList()` 的清理面里 —— 那一刀只 `GDispose`
         * `gList` 里的 `GraphicsImage`（SpriteG 的基图，`AppContents.cs:196-203`）；
         * `UnloadTempLoadedConstImageNames()` 收的是另一批临时常量图，不碰 CSV 资源表。
         * `DestBaseSize` 是 `readonly`（`ASprite.cs:9,16-30`）。也就是说：**只要登记表里
         * 还有这条 sprite，`SPRITECREATED` 恒 1、`SPRITEWIDTH/HEIGHT` 恒为登记尺寸**，
         * 与延迟解码、像素逐出、回标题这些内部内存动作无关。
         *
         * 移植侧为了在 512 MiB 堆上装 4.981 的 2,795 张 CSV 图，把父图改成延迟解码 +
         * 可逐出（[GraphicsBuffer.createDeferredFromFile] / [GraphicsBuffer.evictDeferred]），
         * 而 [GraphicsBuffer.isCreated] 读的是「当前这一代像素面的宽高」。于是两处内部动作
         * 会把脚本可见状态一起带塌：
         * 1. 堆贴顶时延迟解码失败、连同尺寸透明图都分配不出来（[GraphicsBuffer.pixels] 的
         *    OOM 支），缓冲整代发布成 0x0 ⇒ 这张父图切出来的**所有** sprite 当场变「未创建」，
         *    且 `deferredSource` 被清掉，堆松了也再解不回来；
         * 2. `ProcessRuntime.unloadGraphicListForGotoTitle` 旧实现逐 sprite 无差别
         *    `dispose()`，把负号段父图也一并清了（回标题后资源表不重装）。
         *
         * 任一命中，语料 `Toolkits.ERB:135` 的 MOB 分支 `画像合成(GID,"ダミー")` 就会因
         * `SPRITECREATED` 转假而建不出 G、`リソース登録` 失败，最后拿到
         * `SPRITEHEIGHT(RES_NAME) == 0` 并抛「除数为0的除法运算」——玩家报告
         * `eratw-report-20261108-002637-trail.txt`（构建 9363da7，Java 堆 511/512 MiB）
         * 的屏上原文正是这一句。
         *
         * 修法按官方口径收口：负号段缓冲专属于 CSV 父图（[GraphicsBuffer.id] 的口径，
         * `ResourceContents.kt:252-255` 用 `-(buffers.size+1)` 发号，图形池 gid 恒 >= 0），
         * 它就是 `SpriteF.BaseImage`。这类 sprite 的创建态是**登记期事实**，不看像素驻留：
         * 画面可能按「缺图降级」画成透明（`GraphicsBuffer.blitFrom` 的尺寸自洽守卫已兜住，
         * 并由 `poolSourceMisses` 记一笔），但 `SPRITE*` 读数不许变，更不能把 0 分母递给脚本。
         * 运行时 sprite（`SpriteG`，正号段 G_ID）仍按 `buffer.isCreated` 跟随 `GDISPOSE`，
         * 与 `GotoTitleTest` 钉的既有语义一致。
         */
        val isCreated: Boolean
            get() = when {
                frames != null -> true // SpriteAnime：帧列表可空，恒 true（SpriteAnime.cs:54）
                buffer?.let { it.id < 0 } == true -> true // SpriteF：ConstImage，登记期一次建好
                else -> buffer?.isCreated == true // SpriteG：跟随 gList 的 GraphicsImage
            }

        /** 动画总时长（`SpriteAnime.totaltime`，SpriteAnime.cs:46,78）。 */
        val totalTimeMs: Int get() = frames?.sumOf { it.delayMs } ?: 0
    }

    private val sprites = LinkedHashMap<String, Sprite>()
    private val resourceSprites = LinkedHashMap<String, Sprite>()

    /**
     * 池级互斥锁：为什么收在类内？因为读侧（platform 的 UI 线程）与写侧（VM 线程）
     * 分属不同模块，调用方各自加锁既容易漏、也无法覆盖两模块间的组合操作；
     * 锁用 `this` 监视器（不新增字段：aux 槽对象会被 L3 状态哈希按字段名反射混入，新增字段会改变登记常量）并对所有公开方法生效后，`aux.peek` 拿到引用即安全。
     */
    /**
     * 动画重绘定时器（`SETANIMETIMER` → `Console.setRedrawTimer`）。
     *
     * 写侧是 VM 线程（GraphicsMethods 的 SETANIMETIMER），读侧可能来自 UI 线程，
     * 故读写同样收进 `this` 监视器保证可见性（裸 Int 属性跨线程无 happens-before）。
     * 自定义访问器不改变 `pool.animeTimerMs = x` 的调用方语法。
     */
    var animeTimerMs: Int = 0
        get() = synchronized(this) { field }
        set(value) {
            synchronized(this) { field = value }
        }

    /** `AppContents.GetSprite`（AppContents.cs:39-51，:45 查表前 `ToUpper(InvariantCulture)`）：未命中返回 null。 */
    fun find(name: String): Sprite? = synchronized(this) { sprites[name.uppercase()] }

    /**
     * 登记/覆盖一个 Sprite（`AppContents.CreateSpriteG` / `CreateSpriteAnime`）。
     *
     * 键**在本层归一成大写**（AppContents.cs:86 的 `imgName = imgName.ToUpper()`、
     * :102 同款 —— 原版插入与查表两侧各自归一，4.981 语料的 `SPRITECREATE
     * @"flan_frame_{gid}"`（`Flan_UI.ERB:152`）喂进来的就是小写名，语句层
     * （`GraphicsMethods` 的 SPRITECREATE）虽然先 `uppercase()` 了，归一收进本层
     * 才让「登记侧大小写」不再是每个生产者自己的纪律：漏掉它的下场是 [find] 按大写
     * 查不到、图静默消失（真机黑屏形态，见 [ResourceContents] 的 CSV 名字条目）。
     * [Sprite.name] 字段保持原样（调用方的告警/日志还要用原文名）。
     */
    fun put(sprite: Sprite) {
        synchronized(this) { sprites[sprite.name.uppercase()] = sprite }
    }

    /** `AppContents.SpriteDispose`（AppContents.cs:53-64，:57 移除前同样归一）：命中则移除并返回 true。 */
    fun dispose(name: String): Boolean = synchronized(this) { sprites.remove(name.uppercase()) != null }

    /**
     * `AppContents.SpriteDisposeAll`（AppContents.cs:66-78）。
     *
     * @param delResourceSprites true = 连 CSV 资源一起清（返回清掉的**全部**个数）；
     *   false = 只清运行时创建的，表回滚成资源表（返回 `count - resourceCount`）。
     */
    fun disposeAll(delResourceSprites: Boolean): Long = synchronized(this) {
        val count = sprites.size
        val resourceCount = resourceSprites.size
        if (delResourceSprites) {
            sprites.clear()
            resourceSprites.clear()
            return@synchronized count.toLong()
        }
        sprites.clear()
        sprites.putAll(resourceSprites)
        (count - resourceCount).toLong()
    }

    /**
     * 登记一个 CSV 资源 Sprite（`resourceImageDictionary` + 运行表副本，
     * 对应 `LoadContents` 末尾 `imageDictionary = new ConcurrentDictionary(resourceImageDictionary)`，
     * AppContents.cs:161,176）。
     *
     * 键归一口径与 [put] 相同（CSV 侧的原版出处是 `CreateFromCsv` 的
     * `imgName.Trim().ToUpper()`，AppContents.cs:238；4.981 `resources/MISC/list.csv:39`
     * 的 `TW_title,title.webp` 就是混合大小写登记名，`ResourceContents.parseRow` 已先
     * `uppercase()`，本层再归一一次是把这条纪律收进表本身）。
     */
    fun putResource(sprite: Sprite) {
        synchronized(this) {
            resourceSprites[sprite.name.uppercase()] = sprite
            sprites[sprite.name.uppercase()] = sprite
        }
    }

    /**
     * 播种 count 个**非资源**的运行时 Sprite（空动画，`IsCreated=true`）。
     *
     * 用途：复现 PC 会话在标题/开局阶段由游戏自身创建的 Sprite。基准 673 行
     * `SPRITEDISPOSEALL=35` 就是这 35 个的个数（`count - resourceCount`，当时资源表为空）。
     * 生产路径不会调用它——只给 GoldenPipeline 还原前置状态。
     */
    fun seedRuntimeSprites(count: Int, prefix: String = "GOLDEN_PRE_") {
        synchronized(this) {
            for (i in 0 until count) {
                val name = (prefix + i).uppercase()
                sprites[name] = Sprite(name, 1, 1, frames = ArrayList())
            }
        }
    }

    fun size(): Int = synchronized(this) { sprites.size }
    fun resourceSize(): Int = synchronized(this) { resourceSprites.size }

    /**
     * 内存压力下回收全部 CSV 资源父图的像素（逐张走 [GraphicsBuffer.evictDeferred]），
     * 返回释放的像素条数。
     *
     * 只扫 `resourceSprites`：那批缓冲正是 CSV 声明的父图（`AppContents.LoadContents`，
     * AppContents.cs:107-178），一张父图常被切成几十个 Sprite 复用，故按**对象身份**去重，
     * 免得同一张图解码计数被放大。
     *
     * 正号 gid（`GCREATEFROMFILE` 建的立绘工作表）不归这里管 —— 它们在图形池里，
     * 由 [GraphicsPool.evictDeferredPixels] 扫（判据是「文件源还在 ⇒ 没被画过」，
     * 见 [GraphicsBuffer.evictDeferred]）。两处都要跑，缺一边那批像素就常驻。
     *
     * 这一版是**宿主水位档调用的全表版**（不看预算、一次退干净）；与它同形的**预算档**见
     * [evictDeferredPixelsWithinBudget] / [evictDeferredPixelsWithinSharedBudget]
     * （fix-spritebudget 车道，负号段的驻留上限不再只靠进程水位）。
     *
     * @param scratch 复用的身份去重面（[SpriteEvictScratch]，oomsrc 波）：宿主传入稳态零分配，
     *   传 null 闸内现建；退回集合与 freed 口径两者逐字一致。
     */
    fun evictDeferredPixels(scratch: SpriteEvictScratch? = null): Long {
        // FEEL1-L3 探针（只观测）：本池监视器「锁内遍历全表」这段的排队与持有时长。
        // 未挂载 = watch == null ⇒ 不取时钟、不进 LockWatch、零分配；锁的粒度与遍历口径一字未动。
        val watch = LockWatch.handleOrNull()
        val enterNs = if (watch === null) 0L else System.nanoTime()
        var acquiredNs = 0L
        val result = synchronized(this) {
            if (watch !== null) acquiredNs = System.nanoTime()
            // 身份去重面（oomsrc 波）：宿主传入就复用（稳态零分配），传 null 才现建 ——
            // 判据与逐字口径同旧 `HashSet<GraphicsBuffer>`（未覆写 equals ⇒ 引用判同；
            // 去重与遍历序对本函数的**求和**语义无影响，退化的张数/条数读数不变）。
            val owned = scratch ?: SpriteEvictScratch()
            owned.clear()
            var freed = 0L
            for (sprite in resourceSprites.values) {
                sprite.buffer?.takeIf { owned.addCandidate(it) }?.let { freed += it.evictDeferred() }
                sprite.frames?.let { frames ->
                    for (frame in frames) {
                        if (owned.addCandidate(frame.buffer)) freed += frame.buffer.evictDeferred()
                    }
                }
            }
            freed
        }
        // tag = 本次退回的像素点数（各张 `evictDeferred()` 的 pixelData.size 之和）。
        if (watch !== null) {
            watch.noteSegment(LockWatch.ID_SPRITE_POOL_EVICT, enterNs, acquiredNs, System.nanoTime(), result)
        }
        return result
    }

    /**
     * 负号段（CSV 父图）当前的 Java 堆像素驻留字节 —— **O(n) 现算快照**。
     *
     * 为什么是现算而不是像 [GraphicsPool.residentBytes] 那样挂一根原子计数器：
     * 1. **不新增实例字段是本池的硬纪律**。本对象挂在 `store.aux` 的 SPRITE 槽上，
     *    [com.eratw.port.l3.StateHash] 会把 aux 槽对象按**字段名**反射混进 `aux:sprite` 簇，
     *    加一根计数器就把已登记的 L3 链值顶掉（上一路 `GraphicsPool` 的计数器是登记在册的
     *    既有字段，本路不给新池开这个口）；
     * 2. **调用频率撑得起 O(n)**：本读数只出现在预算闸的入口（VM 线程回合边界一发）与崩溃
     *    报告行，不在绘制或语句热路径上；n = CSV 父图的 sprite 数（真语料读数见
     *    `SpritePoolBudgetProbeTest`），一次遍历加一枚身份去重集，成本与既有
     *    [referencedBuffers] 同量级；
     * 3. 快照**不触发任何延迟解码**：读的是 [GraphicsBuffer.pixelBytes]（volatile 一代快照的
     *    像素长度 ×4），为了报数去解整张图正是要避免的事。
     *
     * ## 第 2 条的账要重读（reportres 波，2026-11-05）
     * 消费点实测三处，其中**两处就在堆贴顶相位**：宿主 `GameHost.graphicsPoolReportLine` 的报告行与
     * 宿主 `GameHost.censusTrailLine` → [com.eratw.port.core.diag.LiveSetCensus.census]
     * （判式 `HeapCensusPin.shouldPin`：本挡 Java 堆 ≥90%、30 s 冷却一发、VM 线程），
     * 第三处 `LiveSetCensus.kt:546` 与第二处是同一条链。于是「一次遍历加一枚身份去重集」这一句
     * 里的**集**是每次调用现搭的：表数组按**登记条数**长、节点按**去重后的父图张数**长
     * （一张父图常被切成十几条 sprite 复用，两段各自的量级要分开读）。
     * 桌面装置实测的单发字节（`ResourceResidentSnapScratchTest` 的 `量·资源表规模曲线`）：
     * 134 条 = 6,448 B、2,795 条 = 122,320 B、38,861 条 = 610,672 B。贴顶相位自己现搭
     * 0.1–0.6 MB 容器，与真机报告 `6c08e8bb` 簇的抛点（回收闸内 `HashMap.resize` 新建的引用
     * 数组）同族 ⇒ 本口开一个可选的复用面参数；量级与两臂读数的账全部在
     * [snapshotResourceResidentBytesWithFaceLocked] 的类头。
     *
     * 口径：只统计 [resourceSprites]（CSV 资源表）可达的**去重后**缓冲 —— 一张父图常被切成
     * 几十个 sprite 复用（[evictDeferredPixels] 同理按身份去重，免得同一张图的字节被放大计）；
     * 运行表里 `SPRITECREATE` 建的 sprite 引用的是正号 gid 缓冲，那份驻留归
     * [GraphicsPool.residentBytes] 记账，两口径互不重叠、相加即两段总量。
     *
     * 线程：收在 `this` 监视器内（本池纪律），锁内只有读数与集合操作，不解码、不绘制、
     * 不回调外部；读侧（platform UI 线程）与写侧（VM 线程）任意线程可调。并发换代的一瞬间
     * 可能偏一张图的量级 —— 与 [GraphicsPool.residentBytes] 同口径的诊断/预算读数，可接受。
     *
     * @param face 复用的身份去重面（[SpriteEvictScratch] 的护栏表）：宿主传入则本读数稳态零
     *   新建（走 [snapshotResourceResidentBytesWithFaceLocked]）；**null（缺省）= 旧形状逐字
     *   不变**（走 [snapshotResourceResidentBytesLocked]，闸内现搭 `HashSet`），旧调用点与旧
     *   读数一律不动。面**归调用方所有**、进门先 `clear()`，且只在本池监视器内被摸
     *   （线程口径见 [snapshotResourceResidentBytesWithFaceLocked] 的「线程口径」一节）。
     */
    fun resourceResidentPixelBytes(face: SpriteEvictScratch? = null): Long =
        synchronized(this) {
            if (face === null) snapshotResourceResidentBytesLocked()
            else snapshotResourceResidentBytesWithFaceLocked(face)
        }

    /**
     * 负号段的 **Java 堆侧主动像素预算**档（fix-spritebudget 车道；与
     * [GraphicsPool.evictDeferredPixelsWithinBudget] 同形，任务书判据逐条对应）：驻留超过
     * [budgetBytes] 时，按 [resourceSprites] 的**登记序**（CSV 声明序）把
     * [GraphicsBuffer.evictDeferred] 那一档退回未解码态，直到驻留回到预算内、或可退的已全部
     * 退完。返回退回的**像素条数**（口径同 [evictDeferredPixels]）。
     *
     * 判据一条不放宽、也不新造：可退集完全由 `evictDeferred` 决定 —— 负号段缓冲恒满足它的
     * 判据 1（「只能按 gid 寻缓冲，负号段的像素只会被读」是结构性的，见该方法 KDoc），
     * 重解逐字得同一张图，`isCreated`/宽高/尺寸读数不动（GraphicsImage.cs:44-54、
     * ASpriteSingle.cs:24-34，反编译在 ERATW_ROOT 下 reference/emuera-cs）。
     * 真解失败的缓冲（[GraphicsBuffer.pixels] 失败支把 `deferredSource` 置 null 的那批）
     * 自动不在候选集里，与全表版行为一致。
     *
     * **为什么退序用登记序而不是 accessOrder LRU**：正号段能白嫖 accessOrder 是因为每条
     * G 语句都经 [GraphicsPool.get] 触碰表条目；负号段的读侧（platform 的取图口、真解闭包）
     * **根本不回头查 `resourceSprites`**（取到 Sprite 引用后直接读缓冲），表序里没有任何
     * 触碰点可登记，改 accessOrder 只会把插入序变成访问序的假象。登记序在此形状下无损：
     * 这批图装载一次、读多序恒定，「谁最久没被碰」读不出来，那就按声明序先出 ——
     * 「不许动正在用的那一张」由 [protect] 显式承担，而不是靠表序碰运气。
     *
     * 与水位档的分工：[evictDeferredPixels]（全表版）**维持原样**，仍由宿主水位/trim 路调用；
     * 本档是它前面加的一道**上限** —— 像素落地后到进程水位（512 MiB 挡的 410 MiB 已用）之间，
     * 本档保证负号段不会无限堆在 Java 堆上。[budgetBytes] ≤ 0 ⇒ 退化成尽量退（自洽读法同
     * 正号段，[evictDeferredPixelsWithinSharedBudget] 在正号段贴满共用预算时恰好落到这一支）。
     *
     * @param protect 这一张**永不逐出**。语义同正号段 `get()` 传的「刚交出去的那张」：
     *   读侧将来若在取图点挂本档检查点（回执里的接线项），必须把自己正要用的一张传进来，
     *   否则「唯一一张且超预算」会退化成每帧重解的螺旋（上一路踩过的坑，对照组见
     *   `SpritePoolBudgetTest` 的护持者例）。宿主页档没有「正在交出的一张」，传 null。
     * @param scratch 复用的记账面（[SpriteEvictScratch]，oomsrc 波）：宿主在回合边界传自己
     *   持有的那一块，慢路径稳态零分配；传 null 时闸内现建一块（语义与读数逐字不变，
     *   只是把「闸自己分配」的窗口还给调用方——贴顶堆上这一次现建仍可能失败）。
     */
    fun evictDeferredPixelsWithinBudget(
        budgetBytes: Long,
        protect: GraphicsBuffer? = null,
        scratch: SpriteEvictScratch? = null,
    ): Long =
        synchronized(this) {
            // 快路径（零分配，2026-11-04 OOM 波）：先按「不去重」扫一遍驻留。不去重和
            // ≥ 去重和（共享父图的每一片 Sprite 都会把同一份 pixelBytes 再数一遍），所以
            // 不去重和 ≤ 预算 ⇒ 去重驻留必然 ≤ 预算 ⇒ 必然无事可做，直接返回。
            //
            // 价值：这条闸挂在**回合边界**（GameHost 页档），每次进闸都要为读数建一个
            // 30k+ 槽的 HashSet —— 真机 0.2.39+15 报告
            // `eratw/2026-11-04/1793779421942`（HUAWEI TET-AN00，负号段驻留已是 0 MiB）里
            // `SpritePool.evictDeferredPixelsWithinBudget` 内的 `HashMap.resize` 就是这笔：
            // Java 堆 511/512 MiB、可用不足 1% 时，一道「检查是否需要回收」的闸自己先
            // 分配出 0.5 MiB 的表并当场 OOM。快路径在常见形状（负号段本就空）下把这道闸
            // 变成纯读，堆贴顶时不再有闸内分配。
            var upperBound = 0L
            for (sprite in resourceSprites.values) {
                sprite.buffer?.let { upperBound += it.pixelBytes }
                sprite.frames?.let { frames ->
                    for (frame in frames) upperBound += frame.buffer.pixelBytes
                }
                if (upperBound > budgetBytes) break
            }
            if (upperBound <= budgetBytes) return 0L
            // 慢路径（oomsrc 波，2026-11-05）：快路径只救「本就空/本就够」的形状；能走到
            // 这里说明**去重和要现算**，而旧形状在此之后还要两趟全表（snapshot 的 HashSet +
            // seen/ordered 的 HashSet 与 ArrayList，共三件容器），回合边界每发一次现搭一次
            // —— vivo 报告 `417844ea-0`（负号段驻留 129 MiB > 共用档剩余）就是这个形状在
            // 页间反复成立。现合成**一趟扫描**：候选面同时给出「去重后的驻留和」与「首次
            // 登记序」（= 旧 seen+ordered 的序与口径，判停算术逐字不变）；宿主传入的面
            // 稳态零分配（见 [SpriteEvictScratch] 的分配口径）。
            val owned = scratch ?: SpriteEvictScratch()
            owned.clear()
            var resident = 0L
            for (sprite in resourceSprites.values) {
                sprite.buffer?.takeIf { owned.addCandidate(it) }?.let { resident += it.pixelBytes }
                sprite.frames?.let { frames ->
                    for (frame in frames) {
                        if (owned.addCandidate(frame.buffer)) resident += frame.buffer.pixelBytes
                    }
                }
            }
            if (resident <= budgetBytes) return 0L
            var freedPixels = 0L
            var i = 0
            while (i < owned.candidateCount()) {
                if (resident <= budgetBytes) break
                val buffer = owned.candidateAt(i)
                if (buffer !== protect) {
                    val freed = buffer.evictDeferred()
                    if (freed > 0L) {
                        freedPixels += freed
                        resident -= freed * 4L
                    }
                }
                i++
            }
            freedPixels
        }

    /**
     * 共用一档预算下的负号段检查点入口（fix-spritebudget 车道，判据 2 的落点）：
     * 负号段预算 = [GraphicsPool.sharedPixelBudgetBytes] − [gfxResidentBytes]。
     *
     * **为什么两段共享一档、而不是各设一档**：分母与论据只有一份（`PIXEL_BUDGET_DIVISOR`
     * 的 KDoc：堆上限 ÷4，3/4 留给装载基线与瞬时拷贝；放开档只抬 native 侧工作表缓存，
     * 本档分母不变）。各设一档等于把驻留上限抬到
     * maxMemory/2（512 MiB 挡 = 两段各 128 MiB），叠上稳态基线就顶到水位档的 410 MiB，
     * 「预算档先于水位限住驻留」这个立档理由当场作废。共享一档的算术：正号段单张最大
     * 工作表 122.1 MiB < 128 MiB ⇒「一张工作表
     * 常驻」这一档形状照样容得下；工作表驻留期间
     * 负号段剩约 6 MiB，被退掉的父图下一次**只**在 VM 语句真读像素时重解（绘制侧走
     * `fileSource` native 通道，不回头解 Java 像素 —— 见 `SpriteBitmapSource.bitmapOf` 的
     * 文件源优先支），页与页之间最多各解一次，这就是共享档买来的下限代价，两段的
     * `releaseUnused`/dispose 面均未放宽。
     *
     * [gfxResidentBytes] > 总预算时差值为负 ⇒ 负号段整档尽量退（见上面预算 ≤ 0 的自洽读法）；
     * 总预算为 `Long.MAX_VALUE`（堆上限不可知，桌面装置）时减法不翻符号 ⇒ 判据恒不成立，
     * 装载/基准行为逐字不变。
     *
     * 调用口径：读正号段驻留用 [GraphicsPool.residentBytes]（原子读，任意线程）；本函数在
     * VM 线程回合边界调（GameHost 的页档），锁内只退不化解，见类 KDoc 的线程口径。
     */
    fun evictDeferredPixelsWithinSharedBudget(
        gfxResidentBytes: Long,
        protect: GraphicsBuffer? = null,
        scratch: SpriteEvictScratch? = null,
    ): Long = evictDeferredPixelsWithinBudget(
        GraphicsPool.sharedPixelBudgetBytes() - gfxResidentBytes,
        protect,
        scratch,
    )

    /** [evictCompositedSpritesWithinBudget] 的读数快照。 */
    data class CompositedEviction(val sprites: Int, val pixels: Long)

    /**
     * **合成缓冲档**（fix-hiresmem 车道）：驻留仍超像素预算、而前两档都收不动时，把
     * 「脚本合成出来、被运行时 Sprite 名字钉住」的那批整尺寸工作表按**登记序**（最旧先出）
     * 逐出 —— 摘掉 Sprite 名 **并** `dispose()` 它指向的缓冲，直到驻留回到预算内或候选穷尽。
     * 返回摘掉的名字数与释放的像素条数（条数口径同 [evictDeferredPixels]）。
     *
     * ## 为什么需要第三档（2026-11-04 端点收件箱 91 份）
     *
     * 0.2.39/0.2.40 的静默死（LOW_MEMORY ×8 / CRASH_NATIVE signal=6 ×5 / iAwareF[LowMem] ×3）
     * 全带同一个前置读数：Java 堆 96–99%、`Java 堆结构账面` 的**正号段像素 250–309 MiB**
     * （预算 = 堆上限 ÷4 = 128–170 MiB）。抛点栈逐字同形：
     * `画像合成` ← `HIGHRES_GET_BASE_RESOURCE` ← `GRAPH_ROUTE_SYS_BASE` ←
     * `QOL_GET_BASE_RESOURCE`/`QOL_PRINT_TARGET_IMAGE` ← `LOOK_AT`/`PRINT_ROSTER`，
     * Java 侧落在 `GraphicsBuffer.blitFrom` / `GraphicsBuffer.create`。同一条轨迹里
     * `turn.trim` / `turn.trim.budget` / `midpage.trim.budget` 一行都没有 —— 前两档**结构上
     * 看不见这批缓冲**：
     * - [GraphicsBuffer.evictDeferred] 要求 `deferredSource` 还在，而 `GCREATE` 发布的是
     *   `Surface(w, h, px, null, null)`（本文件 `create` 那一行），合成图天生不可重解；
     * - [GraphicsPool.releaseUnused] 的名单里有 [referencedBuffers]，而语料把合成结果登记成了
     *   具名 Sprite（`SPRITECREATE OUT_STAND, GID, …`），于是它恒被排除。
     *
     * ## 为什么摘名字是不坏的（自愈链在语料侧，逐条可核）
     *
     * 原版**没有任何自动逐出**：`imageDictionary` 只能靠 `SPRITEDELETE`/`SPRITEDELALL` 缩表
     * （AppContents.cs:53-78，反编译在 ERATW_ROOT 下 reference/emuera-cs），PC 上无所谓 ——
     * 差分缓存本来就在 GB 级内存里长住。移植侧要给它设上限，唯一安全的前提是**语料会自己重算**，
     * 而 4.981 的写法正好满足：每一处使用都带创建闸
     * `IF !SPRITECREATED(OUT_STAND)` → `CALL 画像合成(…)` → `SPRITECREATE`
     * （`ERB/BODY_INFO/HIGHRES_CHARA.ERB:174-185`，MOUSE_FACE 支 `:196-232` 同形），
     * 逐出后 `SPRITECREATED` 转 false ⇒ 下一次经过这一屏重合成一遍。代价是 CPU 与一次
     * 全尺寸 `GCREATE`，换到的是堆回到预算内。
     * 附带治了本板第一条长期欠账（`GraphicsPool.releaseUnused` 的 KDoc：G_ID 池只增不减）：
     * dispose 后 `GCREATED(gid)` 转 0，语料的空编号扫描 `WHILE GCREATED(グラフィックID)`
     * （`ERB/グラフィック表示ライブラリ/グラフィック合成.ERB:38-46`）从此能复用编号。
     *
     * ## 判据为什么收到这四个（一个都不许多）
     *
     * 只动 `sprites`（运行时表）的**静态** Sprite，且其缓冲 `fileSource == null` 且 `isCreated`，
     * 且不在 [exclude] 里：
     * - 资源表 `resourceSprites` 是 CSV 父图，重解无损 ⇒ 由 [evictDeferredPixelsWithinBudget]
     *   那一档负责，这里摘名字等于把可重解的图换成必须重算的图，纯亏；
     * - 动画 Sprite 的 `frames` 是逐帧登记的工作表集合，其缓冲多带 `fileSource`（走 `GCREATEFROMFILE`），
     *   同样归前档；这一档不碰动画，是为了不在首版把风险面铺到动图上；
     * - `fileSource != null` 的画过的缓冲已由 [GraphicsPool.releaseUnused] 处理（那档会 dispose
     *   不具名的），本档不重复。
     *
     * **一个缓冲可能挂着两个名字**：4.981 用同一个 `GID` 同时登记全身与脸区
     * （`SPRITECREATE OUT_STAND, GID, …` 与 `SPRITECREATE OUT_FACE, GID, …`，
     * `HIGHRES_CHARA.ERB:185` 与 `:194`）。所以逐出分两遍：先按预算选出要 dispose 的**缓冲**，
     * 再把**所有**指向这些缓冲的名字一并摘掉。只摘选中的那一个名字会留下一个「名字还在、
     * 缓冲已 dispose」的活 Sprite —— `SPRITECREATED` 仍报 true，而语料重算闸
     * `IF SPRITECREATED(SRC_FACE) && SPRITECREATED(OUT_STAND) && !SPRITECREATED(OUT_FACE)`（`:189`）
     * 恰好因为 `OUT_STAND` 没了而不再进这一支 ⇒ 那张脸永久空白。这是本档唯一会自己造出
     * 永久缺陷的形态，`同一个 GID 挂着两个名字时两个名字一起消失` 那条单测钉住它。
     *
     * ## 风险面（与既有档位同一个取舍，不新立）
     *
     * 正在被绘制的名字被摘掉那一帧是空白 —— 与 [GraphicsPool.releaseUnused] 正文那句
     * 「取舍是『可能一帧空白』对『进程被系统杀掉』」是同一条判词。UI 线程侧不会炸：
     * `dispose()` 把尺寸归零后 `SpriteBitmapSource.bitmapOf` 的长度判据
     * （`pixels.size.toLong() != w * h`）会先命中并摘掉缓存条目、返回 null（platform 模块
     * `SpriteBitmapSource.kt` 的 bitmapOf），绘制侧按「这一张没图」继续。
     * 本函数**只许 VM 线程调**（线程约束与 `GameHost.releaseUnusedGraphics` 同源：`dispose()`
     * 清零尺寸时 VM 线程若正停在 `GDRAWG` 里按旧尺寸索引同一张缓冲就是越界），宿主侧有硬闸。
     *
     * [budgetBytes] ≤ 0 ⇒ 尽量退；`Long.MAX_VALUE`（堆上限不可知，桌面装置）⇒ 判据恒不成立，
     * 装载与基准行为逐字不变（自洽读法同 [evictDeferredPixelsWithinBudget]）。
     *
     * @param gfxResidentBytes 调用时刻的正号段驻留读数（[GraphicsPool.residentBytes]，原子读）；
     *   本档量的是全池驻留，不是自己那几张，所以判停要吃这个入参而不是内部遍历（跨线程禁遍历）。
     * @param budgetBytes 共用一档预算（[GraphicsPool.sharedPixelBudgetBytes]）—— 不许发明第二个常数。
     * @param exclude 不许动的缓冲（宿主传的 CBG 图层引用集；资源表引用在本函数内自动并入）。
     * @param scratch 复用的记账面（[SpriteEvictScratch]，oomsrc 波）：宿主传入则稳态零分配，
     *   传 null 现建；判据、候选集与摘名算术与不传时逐字一致。
     */
    fun evictCompositedSpritesWithinBudget(
        gfxResidentBytes: Long,
        budgetBytes: Long,
        exclude: Set<GraphicsBuffer> = emptySet(),
        scratch: SpriteEvictScratch? = null,
    ): CompositedEviction = synchronized(this) {
        if (gfxResidentBytes <= budgetBytes) return CompositedEviction(0, 0L)
        // 不动的名单 = 调用方给的 + 资源表引用的（后者重解无损，归上一档，摘了纯亏）。
        // 记账面走 [SpriteEvictScratch]（oomsrc 波）：这一档的触发判据本身就写着「堆贴顶」
        // （RunTrail.shouldEvictCompositedSprites），旧形状在此现搭 HashSet(资源表×2)+
        // LinkedHashSet 两件容器，与 6c08e8bb 簇的病灶同族。护栏面装 protectedBuffers、
        // 候选面装 chosen（插入序 = 旧 LinkedHashSet 的迭代序，逐字同）。
        val owned = scratch ?: SpriteEvictScratch()
        owned.clear()
        for (buffer in exclude) owned.addGuard(buffer)
        evictCompositedLocked(gfxResidentBytes, budgetBytes, owned)
    }

    /**
     * [evictCompositedSpritesWithinBudget] 的**零分配护栏版**（refbuffers 波，2026-11-05）：
     * 「不许动」的名单由调用方的复用护栏面提供，不再要求宿主物化一张 `Set`。
     *
     * 与本类另一把 `Set` 版（[evictCompositedSpritesWithinBudget]）的差别**只在名单怎么进护栏面**：
     * 那一把逐条 `addGuard(buffer)` 自集合，这一把走 [SpriteEvictScratch.copyGuardsFrom]
     * （同一张护栏表、同一个身份判同、同一个「首次登记才计数」的口径），护栏装载完之后的
     * 判停、候选选择、dispose、摘名算术共用 [evictCompositedLocked] 那一份代码 ⇒ 两臂不可能漂移。
     *
     * ## 为什么宿主需要这一把
     * 宿主 `GameHost.releaseUnusedGraphics` 的 CBG 名单原先每发现搭一个 `HashSet`；它自己
     * 只有几十字节到几 KB（图层数量级），但**所在的那一发就是堆贴顶相位**，而同一发里
     * [SpritePool.addReferencedGuards] 那一半已经换成面 —— 名单两头都长住，这一发才真的
     * 一处都不新建（判据原文见 `addReferencedGuards` 的 KDoc 与官方对照）。
     *
     * ## 别名闸
     * [scratch] 与 [protectedGuards] **必须是两块不同的面**：本函数第一件事是 `owned.clear()`，
     * 同一块面上等于先把宿主的名单清空再抄自己 ⇒ 名单当场蒸发、CBG 图层变成可逐出（比
     * 现搭集合的旧形状更坏）。这条不许靠调用序兜，故直接 `require` 抛掷；宿主的这一发外套
     * 着 `runCatching`（见 `GameHost.evictCompositedSpritesIfOverBudget`），抛掷 = 这一发不做，
     * 与「宁可不做也不炸」的既有闸同口径。
     *
     * @param protectedGuards 宿主的「不许动」名单所在的复用护栏面（本函数只读它）
     * @param scratch 本档自己的记账面；传 null 闸内现建（语义与读数逐字不变，只是把分配
     *   窗口还给调用方）
     */
    fun evictCompositedSpritesWithinBudget(
        gfxResidentBytes: Long,
        budgetBytes: Long,
        protectedGuards: SpriteEvictScratch,
        scratch: SpriteEvictScratch? = null,
    ): CompositedEviction = synchronized(this) {
        if (gfxResidentBytes <= budgetBytes) return CompositedEviction(0, 0L)
        val owned = scratch ?: SpriteEvictScratch()
        require(owned !== protectedGuards) {
            "evictCompositedSpritesWithinBudget: protectedGuards 与 scratch 是同一块面（抄名单前会被 clear 清空）"
        }
        owned.clear()
        owned.copyGuardsFrom(protectedGuards)
        evictCompositedLocked(gfxResidentBytes, budgetBytes, owned)
    }

    /**
     * 两把 [evictCompositedSpritesWithinBudget] 共用的遍历本体（调用方必须已持有 `this` 监视器，
     * 且已把调用方的「不许动」名单装进 [owned] 的护栏表）。
     *
     * 拆出来只为一个理由：护栏装载的两条来源（`Set` / 复用面）之外，**判停算术与摘名一遍
     * 都不许多份**。本函数体与拆分前逐字相同（含下面那段资源表护栏）。
     */
    private fun evictCompositedLocked(
        gfxResidentBytes: Long,
        budgetBytes: Long,
        owned: SpriteEvictScratch,
    ): CompositedEviction {
        // 不动的名单 = 调用方给的 + 资源表引用的（后者重解无损，归上一档，摘了纯亏）。
        for (sprite in resourceSprites.values) {
            sprite.buffer?.let { owned.addGuard(it) }
            sprite.frames?.let { frames -> for (frame in frames) owned.addGuard(frame.buffer) }
        }
        // 第一遍只选缓冲、不动手：预算判停要按「选好之后的总额」算，边选边 dispose 会让
        // 一个缓冲挂两个名字时选到第二个（同一个对象）而把释放量记两遍。
        var chosenPixels = 0L
        var projected = gfxResidentBytes
        for (sprite in sprites.values) {
            if (projected <= budgetBytes) break
            val buffer = sprite.buffer ?: continue
            if (sprite.frames != null) continue
            if (!buffer.isCreated) continue
            if (buffer.fileSource != null) continue
            if (owned.containsGuard(buffer)) continue
            if (!owned.addCandidate(buffer)) continue
            // 判停与释放量都按「这一代真实占有的 Java 堆字节」算：降级态缓冲的
            // pixelBytes 是 0，可它的 PNG 载荷还占着堆。按 0 算时 projected 一格不降，
            // 逐出档会把候选全选光也退不出预算，更糟的是账本归零后本档根本不触发。
            // 见 [GraphicsBuffer.publishSurfaceLocked] 的 KDoc。
            val retained = buffer.retainedBytes
            chosenPixels += retained / 4L
            projected -= retained
        }
        if (owned.candidateCount() == 0) return CompositedEviction(0, 0L)
        var k = 0
        while (k < owned.candidateCount()) {
            owned.candidateAt(k).dispose()
            k++
        }
        // 第二遍摘名字：凡是指向这批缓冲的，一个不留（理由见上面「一个缓冲两个名字」那段）。
        var removed = 0
        val iterator = sprites.entries.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.value.buffer?.let { owned.containsCandidate(it) } == true) {
                iterator.remove()
                removed++
            }
        }
        return CompositedEviction(removed, chosenPixels)
    }

    /**
     * [resourceResidentPixelBytes] 的锁内本体**旧臂**（调用方必须已持有 `this` 监视器）。
     * 与 [evictDeferredPixels] 的扫描面逐项同集：静态基图 + 动画帧、按对象身份去重。
     *
     * reportres 波（2026-11-05）**一个字没动**：默认参数（不传面）就是走这一臂，
     * `core/diag/LiveSetCensus` 那条还没接线的链读的也正是它。函数体与本函数被拆成两臂之前的
     * 形状逐字相同（连 `takeIf` / `let` 的写法都留着，为的是让 `git diff` 在这一臂上读不出改动；
     * detekt 的 `NestedBlockDepth` 存量项也原样挂在旧签名上，不新增违例）。
     */
    private fun snapshotResourceResidentBytesLocked(): Long {
        val seen = HashSet<GraphicsBuffer>(resourceSprites.size * 2 + 1)
        var bytes = 0L
        for (sprite in resourceSprites.values) {
            sprite.buffer?.takeIf { seen.add(it) }?.let { bytes += it.pixelBytes }
            sprite.frames?.let { frames ->
                for (frame in frames) {
                    if (seen.add(frame.buffer)) bytes += frame.buffer.pixelBytes
                }
            }
        }
        return bytes
    }

    /**
     * [resourceResidentPixelBytes] 的锁内本体**面臂**（调用方必须已持有 `this` 监视器）：
     * 与上面的旧臂同一张表、同一个身份判同、同一句 `pixelBytes` 相加，只是把每次调用现搭的
     * `HashSet` 换成调用方那块 [SpriteEvictScratch] 的**护栏表**。
     *
     * ## 病灶与量级（同族第四刀：报告/取证链上剩下的那一发贴顶现搭表）
     * 旧臂的表数组按**登记条数**长、节点按**去重后的父图张数**长（一张父图常被切成十几条 sprite
     * 复用 ⇒ 两段各自的量级要分开读）。桌面装置实测每发字节（`ResourceResidentSnapScratchTest`
     * 的 `量·资源表规模曲线`）：134 条 = 6,448 B、2,795 条 = 122,320 B、38,861 条 = 610,672 B。
     * 消费链三处（宿主 `GameHost.graphicsPoolReportLine` 的报告行 / 宿主 `GameHost.censusTrailLine`
     * 的分项钉发 / `LiveSetCensus.kt:546`），后两处是同一条链且都跑在 `HeapCensusPin` 的
     * **堆 ≥90% 贴顶相位**（VM 线程、
     * 30 s 冷却一发），第一处在崩溃报告里 —— 自救与取证相位自己现搭 0.1–0.6 MB 容器，
     * 与真机报告 `6c08e8bb` 簇的抛点（回收闸内 `HashMap.resize` 新建的引用数组）同族。
     *
     * ## 为什么用护栏表而不是候选表
     * 本读数只问「这张缓冲见过没有」，**不记序**；候选表另背一根首次登记序数组（每条一枚引用 +
     * 水位创新高时一次 `copyOf`），在只求和的表上是纯浪费。护栏表本就是为「只判身份」造的。
     *
     * ## 线程口径（互斥由本池监视器给出）
     * [SpriteEvictScratch] 无内部锁、一块面同一时刻只许一条线程摸（该类 KDoc）。本读数的消费点
     * 分两类线程：VM 线程的贴顶分项钉发与**任意线程**的崩溃报告 ⇒ 宿主为此另开一份面
     * （`GameHost.reportResidentScratch`），而它对面的每一次读写都发生在 [resourceResidentPixelBytes]
     * 外面那句 `synchronized(this)` 之内：两条线程不可能同时在面里，锁的 unlock→lock 就是
     * happens-before。口径与 [addReferencedGuards] 同一条（面是搬运对象、锁只保证被扫的表结构
     * 一致），差别只在独占性的来源：那里是「调用方线程独占」，这里是「池锁独占」——因为本读数
     * 的调用线程不固定。
     *
     * ## 面的所有权
     * 进门先 [SpriteEvictScratch.clear]：口径同其余几把闸（调用方拿到的永远是「这一次的全部」，
     * 中途抛掷不留半张表参与下一次读数）。传进来的面**归调用方所有**，本函数不缓存、不外借、
     * 不在锁外读它。
     *
     * @param face 复用的身份去重面（宿主长生命周期那份）
     */
    private fun snapshotResourceResidentBytesWithFaceLocked(face: SpriteEvictScratch): Long {
        face.clear()
        var bytes = 0L
        for (sprite in resourceSprites.values) {
            sprite.buffer?.takeIf { face.addGuard(it) }?.let { bytes += it.pixelBytes }
            sprite.frames?.let { frames ->
                for (frame in frames) {
                    if (face.addGuard(frame.buffer)) bytes += frame.buffer.pixelBytes
                }
            }
        }
        return bytes
    }

    /**
     * 当前被任何 Sprite 引用着的缓冲（静态基图 + 动画帧），**两张表都扫**
     * （运行表 `sprites` 与资源表 `resourceSprites`）。
     *
     * 给 [GraphicsPool.releaseUnused] 当「不许动」的名单：那些缓冲正在被绘制，
     * `dispose()` 掉就是屏上直接缺一块。返回的是对象身份集合（`GraphicsBuffer` 未覆写
     * `equals`，等价于引用相等），调用方拿到的快照不再受本池锁保护。
     */
    fun referencedBuffers(): Set<GraphicsBuffer> = synchronized(this) {
        val out = HashSet<GraphicsBuffer>(sprites.size * 2 + resourceSprites.size * 2 + 1)
        for (table in arrayOf(sprites, resourceSprites)) {
            for (sprite in table.values) {
                sprite.buffer?.let { out.add(it) }
                sprite.frames?.let { frames -> for (frame in frames) out.add(frame.buffer) }
            }
        }
        out
    }

    /**
     * [referencedBuffers] 的**零分配版**：把同一份「不许动」名单逐条登记进调用方持有的
     * 复用护栏面（[SpriteEvictScratch.addGuard]），不物化任何集合。
     *
     * ## 为什么要有这一口（refbuffers 波，2026-11-05；组长开的 core 侧口子）
     * 宿主 `GameHost.releaseUnusedGraphics` 的每一发都在**堆 ≥80% / ≥92% 的贴顶相位**调
     * [referencedBuffers]，而那一句的形状是「按 `sprites.size*2 + resourceSprites.size*2 + 1`
     * 现搭 `HashSet` 再逐条 `add`」—— 三万量级 Sprite 就是**一张 0.5 MiB 的引用数组 + 几万个
     * 32–40 字节的 `HashMap.Node`**，全部落在唯一不能分配的时刻（自救路径自己现搭哈希表，
     * 与真机报告 `6c08e8bb` 簇同族，那一簇的抛点就是回收闸内的 `HashMap.resize`）。
     *
     * ## 官方对照：官方不是「每次现算名单」这个形状
     * 反编译里最接近「哪些位图不许Unload」的是 `AppContents.tempLoadedGraphicsImages`
     * （AppContents.cs:26）：一块**进程级长住**的 `HashSet`，装载时就登记进去
     * （GraphicsImage.cs:113-116 的 `GCreate`、:666-669 的 `Load`），要Unload 时**遍历这块常驻表**
     * 并对每一项问一个布尔判据 `useImgList`（AppContents.cs:217-229 与 GraphicsImage.cs:29；
     * `SpriteG.cs:9` 同一个判据的委托形），事后 `Clear()` 复用、从不为本发新建一张表。
     * ⇒ 「贴顶时现搭一张引用名单」是**移植侧自造的额外一层**（官方根本没有内存压力回收这一发，
     * 上面那两处只在 RELOAD/存档换代时走：Process.cs:2672、ProcessState.cs:141/:158），
     * 不是官方行为，也就没有 ADR-005 的复刻义务；本口的形状（常驻身份表 + 逐条布尔判定）
     * 恰好把口径掰回官方那一边。
     *
     * ## 语义逐字等价
     * 扫描面与 [referencedBuffers] 逐项同集：两张表都扫（`sprites` 与 `resourceSprites`）、
     * 静态基图 [Sprite.buffer] 与动画帧 [Sprite.frames] 的每一帧缓冲都算，判同是**对象身份**
     * （[GraphicsBuffer] 未覆写 `equals`/`hashCode`，本文件:79 起那个 `class GraphicsBuffer`
     * 只有引用语义）⇒ 名单成员与旧 `HashSet` 一致；[SpriteEvictScratch.containsGuard] 与
     * `HashSet.contains` 对同一项给出同一个布尔。顺序无关（护栏只判成员，不记序）。
     *
     * ## 为什么这里不用 `arrayOf(sprites, resourceSprites)`
     * 旧口用 `arrayOf` 是因为它本来就在这次调用里现搭集合；本口的存在意义是**这一次什么都不新建**，
     * 于是两张表写成两段同形循环（体与旧口逐字对应）。锁内的两段 `LinkedHashMap.values` 迭代各起
     * 一个迭代器对象（约几十字节，与 [evictDeferredPixels] 传面后的形状同档），
     * 与「每条目一个节点 + 每发一张按水位长的数组」不是同一个量级 —— 稳态判据见交付的单测臂。
     *
     * ## 线程
     * 收在 `this` 监视器内（本池纪律，见类 KDoc）。[target] 由调用方持有：本函数只在
     * **调用方那条线程**上写它，池锁只保证表结构一致，不给 [target] 提供跨线程可见性 ——
     * 宿主侧的面必须与这里的调用点同线程（`GameHost` 的两个消费点都在 VM 线程，
     * 其硬闸原文见 `releaseUnusedGraphics`）。
     *
     * @param target 装载名单的复用护栏面；调用前**不**要求清空（本函数只追加），
     *   调用后 `target.containsGuard(b)` 对且仅对 [referencedBuffers] 里的 b 为 true。
     */
    fun addReferencedGuards(target: SpriteEvictScratch) = synchronized(this) {
        for (sprite in sprites.values) {
            sprite.buffer?.let { target.addGuard(it) }
            sprite.frames?.let { frames -> for (frame in frames) target.addGuard(frame.buffer) }
        }
        for (sprite in resourceSprites.values) {
            sprite.buffer?.let { target.addGuard(it) }
            sprite.frames?.let { frames -> for (frame in frames) target.addGuard(frame.buffer) }
        }
    }

    /**
     * 逐条**流式**遍历运行表 `sprites`（登记序），**不建快照、不按名二次查表**。
     *
     * ## 为什么要有这一口（census3 波，2026-11-05：回标题路径上最后一份现搭集合）
     * [names] 的形状是「锁内 `keys.toSet()` 物化一份 `Set<String>`，调用方再逐名 [find] 第二次查表」。
     * 唯一的生产消费点 `ProcessRuntime.unloadGraphicListForGotoTitle`（原 :4230）只要「每个 sprite
     * 的基图」，那份名字集合是**纯中间态、无任何消费者**：大池（真语料数万条登记）在回标题这一刻
     * 现搭一张按登记数长的 `LinkedHashSet` 表数组 + 每条一枚 `Node`（32–40 B），紧跟着再按名
     * `uppercase()` 现造一个查表键、走一遍 `HashMap.get`——而值本来就摆在迭代器当前位置上。
     * 与 `6c08e8bb` 簇（贴顶相位自己现搭哈希表）同族，只是这一发落在回标题相位。
     *
     * ## 语义（与旧「`names()` + `find(name)`」逐字等价）
     * 顺序：`sprites` 是 `LinkedHashMap`，`values` 迭代序 = 登记序 = 旧 `keys.toSet()`
     * （同表同迭代器，`toSet` 只是复制）⇒ 回调顺序与旧逐名序一致。
     * 集合：旧口先取全部键再逐名查表，键必然命中（同一把锁内快照与查表之间除非有别的线程
     * `dispose`，而旧口在两次加锁之间**确实**可能被插队 ⇒ 旧口的 `?: continue` 是给这种竞态
     * 兜底的，不是常规分支）。本口在同一把锁里遍历同一张表：每条登记恰好回调一次，既不漏
     * （不存在「找不到」）也不重（`values` 不重复），**故 `?: continue` 这一支结构性消失**。
     * 键归一：[Sprite.name] 是登记时的原文名，与旧口经 [find] 取回的是**同一个对象**，字段读数不变。
     *
     * ## 线程与回调纪律
     * 收在 `this` 监视器内（本池纪律）。`synchronized` 可重入 ⇒ [action] 里再调本池的读方法
     * （[find] / [size] / [resourceSize]）是安全的；但**不许**调 [put] / [dispose] / [disposeAll] /
     * [putResource]（结构性修改会让迭代器抛 `ConcurrentModificationException`，需要边遍历边改表
     * 的调用方请自备「锁内收集到调用方零分配面」的写法，不要退回 `keys.toSet()`）。
     * 也不许在 [action] 里回调宿主/渲染层：那是「池锁内调外部」的锁序倒置，宿主一旦在自己的锁上
     * 反过来摸本池就成环。当前唯一消费点只做 `GraphicsBuffer.dispose()`（纯 JVM 像素数组置空）。
     *
     * 分配面：一次 `LinkedHashMap.values` 迭代器（数十字节），与 [addReferencedGuards] /
     * [evictDeferredPixels] 传面后的形状同档；不新建 `Set`、不造名字串。单测臂
     * （`SpritePoolForEachSpriteTest` 的分配读数）钉住稳态 < 2 KiB。
     *
     * @param action 每条 Sprite 一次，锁内同步调用；回调期间池锁由本线程持有（见上面的纪律）。
     */
    fun forEachSprite(action: (Sprite) -> Unit) = synchronized(this) {
        for (sprite in sprites.values) action(sprite)
    }

    /**
     * 返回锁内快照（`keys.toSet()`），调用方拿到的是不受锁保护的副本。
     *
     * ## 现状（census3 波，2026-11-05 核对后的实况）
     * **生产面已无消费点**：原唯一调用点 `ProcessRuntime.unloadGraphicListForGotoTitle` 已改走
     * [forEachSprite]（回标题路径不再物化名字集合）。`grep -rn "\.names()"` 之后剩下来的
     * 全是单测（`RefBufferGuardFaceTest`、`ResourceContentsTest`、`SpritePoolBudgetProbeTest`
     * 各一处），它们拿名字集合当**装置读数的比较基准**，正是本方法该留下的用途。
     * 公共方法保留不删（是否有别的宿主想按名取，由组长裁）；语义一个字没动。
     */
    fun names(): Set<String> = synchronized(this) { sprites.keys.toSet() }
}

/**
 * CBG（控制台背景图 / 按钮）池。对应 `EmueraConsole.cbgList` 与 `cbgButtonMap`
 * （EmueraConsole.cs:563-682）。
 *
 * 只维护图层顺序与清除语义；真正绘制归 M4 的控制台。`zdepth == 0` 的条目是常驻底图
 * （`CBG_Clear` 会重新塞一个，EmueraConsole.cs:574），`CBG_ClearRange` 永不清 depth 0。
 */
class CbgPool {

    /** 一个背景图层。`Img`/`ImgB` 用缓冲编号或 Sprite 名表示。 */
    data class Layer(
        val depth: Int,
        val gid: Int? = null,
        val spriteName: String? = null,
        val spriteNameB: String? = null,
        val x: Int = 0,
        val y: Int = 0,
        val isButton: Boolean = false,
        val buttonValue: Int = 0,
        val tooltip: String? = null,
    )

    val layers = ArrayList<Layer>()

    /** `cbgButtonMap`（CBGSETBMAPG 的位图）。 */
    var buttonMapGid: Int? = null

    /**
     * 追加一层并保持图层序。spec: EmueraConsole.cs:645-646（CBG_SetImage）与 :679-680
     * （CBG_SetButtonImage）——`cbgList.Add(item); cbgList.Sort();`，而
     * `ClientBackGroundImage.CompareTo`（:58-65）是 `-zdepth.CompareTo(other.zdepth)`
     * ⇒ 排序结果为 **zdepth 降序**。消费方有两处都依赖这个序：
     * 绘制循环按列表序从深到浅合并（EmueraConsole.cs:1834-1868，取
     * `Math.Max` 的 zdepth 并优先消费列表头部），tooltip 命中取列表序第一个
     * （:1898-1906 的 foreach 首匹配）。升序会让深层先画/浅层先命中 tooltip，
     * 与原版首差落在 zdepth!=0 的多图层场景。
     *
     * 同 depth 的并列序：原版 [List.Sort] 是不稳定排序，C# 侧并列项相对顺序未定义；
     * 本类用稳定排序（插入序保留）作为并列项的确定化，不构成可证偏离。
     */
    fun add(layer: Layer) {
        layers.add(layer)
        layers.sortByDescending { it.depth }
    }

    /** `CBG_Clear`：清全部并复位按钮图（EmueraConsole.cs:563-575）。 */
    fun clear() {
        layers.clear()
        clearButtonMap()
    }

    /** `CBG_ClearRange`：清 `[zmin,zmax]` 且 depth != 0；`zmin > zmax` 时不动（:577-596）。 */
    fun clearRange(zmin: Int, zmax: Int) {
        if (zmin > zmax) return
        layers.removeAll { it.depth in zmin..zmax && it.depth != 0 }
    }

    /** `CBG_ClearButton`：清按钮图层并复位按钮图（:598-614）。 */
    fun clearButtons() {
        layers.removeAll { it.isButton }
        clearButtonMap()
    }

    /** `CBG_ClearBMap`（:616-621）。 */
    fun clearButtonMap() {
        buttonMapGid = null
    }

    /**
     * 只读整表快照：当前图层（**已按 depth 降序**，[add] 维持的序）转成渲染侧 DTO。
     *
     * 每次调用新建一份列表，调用方拿到后不再与池共享可变状态 —— 与
     * `GraphicsLayer.background` / `imageLayers` 的「整体换代」纪律同一条。
     *
     * ## 尺寸只在查得到时给
     *
     * [CbgLayerSnapshot.widthPx] / [CbgLayerSnapshot.heightPx] 是**落位尺寸**的来源：
     * - gid 层：`graphics.peek(gid)`（**不建**条目 —— `get` 会惰性建空条目，见
     *   `GraphicsPool.get`）；未创建 ⇒ 0；
     * - 名层：`sprites.find(name)`（同 `AppContents.GetSprite`，`AppContents.cs:39-51`）。
     *   已 Dispose 的条目仍能查到（回滚复活语义），尺寸照给；「画不画」由渲染侧的
     *   `isCreated` 守卫决定（`EmueraConsole.cs:1844`），本层不替它判。
     *
     * **动画层给的是当前帧的尺寸**，不是 `DestBaseSize` —— 原版 `GraphicsDraw(g, Point)`
     * 的落位尺寸取 `currentFrame.SrcRectangle.Size`（`SpriteAnime.cs:144`），
     * `DestBaseSize.Height` 只用于下沿锚点（`EmueraConsole.cs:1846` ⇒ 本 DTO 的
     * [CbgLayerSnapshot.baseHeightPx]）。静态层两者相等（`ASpriteSingle.cs:71-75` 的
     * `new Rectangle(offset, DestBaseSize)` ⇒ 尺寸就是 DestBaseSize）。
     *
     * ## 按钮层与取色图
     *
     * [CbgPool.Layer] 的 `spriteNameB` / `isButton` / `buttonValue` 原样进快照，
     * 取色图 gid 取池级 [buttonMapGid]（-1 = 无）**重复写进每一条图层**：它决定
     * 「按下态」的比对来源（`EmueraConsole.cs:1840-1843` 的 `buttonValue ==
     * selectingCBGButtonInt`），逐层携带让渲染侧只看一份快照就能完成换图判定。
     *
     * ## 动画帧表
     *
     * [CbgLayerSnapshot.frames] 是 `SpriteAnime.FrameList` 的几何投影（尺寸/偏移/时长）；
     * 没有帧的动画 sprite 给 [CbgLayerSnapshot.NO_FRAME]（原版 `GetCurrentFrame` 返回 null，
     * 一个像素都不画）。**取哪一帧不在本方法**：core 没有动画时钟的数据面（见
     * [CbgLayerSnapshot] 类 KDoc「动画 CBG」），这里恒给首帧 = 原版 `lastFrame == -1`
     * 分支（`SpriteAnime.cs:96-102`）。
     *
     * @param graphics 图形缓冲池；null = 拿不到 gid 尺寸（快照里给 0）
     * @param sprites 精灵表；null = 拿不到名层尺寸（快照里给 0）
     */
    fun snapshot(graphics: GraphicsPool? = null, sprites: SpritePool? = null): List<CbgLayerSnapshot> {
        if (layers.isEmpty()) return emptyList()
        val out = ArrayList<CbgLayerSnapshot>(layers.size)
        val mapGid = buttonMapGid ?: -1
        for (layer in layers) {
            var widthPx = 0
            var heightPx = 0
            var baseHeightPx = 0
            var frames: List<CbgFrameGeometry> = emptyList()
            var frameIndex = 0
            val gid = layer.gid
            if (gid != null) {
                val buffer = graphics?.peek(gid)
                if (buffer != null) {
                    widthPx = buffer.width
                    heightPx = buffer.height
                    baseHeightPx = buffer.height
                }
            } else {
                val name = layer.spriteName
                val sprite = if (name == null) null else sprites?.find(name)
                if (sprite != null) {
                    // DestBaseSize：下沿锚点（EmueraConsole.cs:1846）与静态层的落位尺寸。
                    baseHeightPx = sprite.height
                    val spriteFrames = sprite.frames
                    if (spriteFrames == null) {
                        widthPx = sprite.width
                        heightPx = sprite.height
                    } else if (spriteFrames.isEmpty()) {
                        // GetCurrentFrame 返回 null（SpriteAnime.cs:92-95）：整层不画。
                        frames = emptyList()
                        frameIndex = CbgLayerSnapshot.NO_FRAME
                    } else {
                        val built = ArrayList<CbgFrameGeometry>(spriteFrames.size)
                        for (f in spriteFrames) {
                            built.add(
                                CbgFrameGeometry(
                                    widthPx = f.srcW,
                                    heightPx = f.srcH,
                                    offsetX = f.offsetX,
                                    offsetY = f.offsetY,
                                    delayMs = f.delayMs,
                                ),
                            )
                        }
                        frames = built
                        val first = built[0]
                        widthPx = first.widthPx
                        heightPx = first.heightPx
                    }
                }
            }
            out.add(
                CbgLayerSnapshot(
                    depth = layer.depth,
                    x = layer.x,
                    y = layer.y,
                    spriteName = layer.spriteName,
                    gid = gid ?: -1,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    spriteNameB = layer.spriteNameB,
                    isButton = layer.isButton,
                    buttonValue = layer.buttonValue,
                    buttonMapGid = mapGid,
                    baseHeightPx = baseHeightPx,
                    frames = frames,
                    frameIndex = frameIndex,
                ),
            )
        }
        return out
    }
}

/**
 * GoldenPipeline 接线助手：给一个 VM 的附属状态播种 [count] 个运行时 Sprite。
 *
 * 基准 673 行 `SPRITEDISPOSEALL=35` 的前置条件（PC 会话在进入 GOLDEN_GVIEW 之前已由游戏
 * 自身创建 35 个非资源 Sprite，资源表为空）。装置侧一行：
 * `seedGoldenRuntimeSprites(store.aux, 35)`。
 */
fun seedGoldenRuntimeSprites(aux: RuntimeAux, count: Int): SpritePool =
    aux.slot("sprite") { SpritePool() }.also { it.seedRuntimeSprites(count) }
