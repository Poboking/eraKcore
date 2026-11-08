package com.eratw.port.render.memory

import com.eratw.port.core.host.GraphicsPool

/**
 * **分配前**的内存预检：一笔像素/字节现在要，是要得还是不要得。
 *
 * ## 它补的是哪一段
 *
 * 端点 2026-11-04 那批 0.2.39 报告（512 MiB 挡、`内存档位=放开`）的共同形状是
 * 「堆已经贴顶了还在 decode」：`free` 只剩几百 KB～4 MiB，而请求是 1,440,016 / 1,000,016 /
 * 500,016 / 8,000,016 字节这一族（ARGB 像素阵列与存档整段数组的量级，逐份对应见
 * `PixelPreflightTest` 的真机读数用例）。既有三档闸（可重解逐出 → 画过的压 PNG → 合成图逐出）
 * 全是**分配之后**在 `GraphicsPool.get` 的语句检查点上收账，收的是「池里驻留了多少像素」；
 * 堆本身被装载基线、角色整数、存档数据顶满时，那一族检查点管不着，下一笔瞬时分配照旧把整局带走。
 * 本闸只管一件事：**要之前先看要得起吗**，要不起就走调用方既有的降级出口。
 *
 * ## 三条腿，全部沿用既有档（不新造常数、不新造系数）
 *
 * 1. **头寸**：`needed <= maxMemory - used`。同式出处 `app/GameHost.kt:2638` 的装载 tight
 *    预检（`estimate.requiredBytes > estimate.maxMemoryBytes - trace.usedBytes()`），
 *    `used` 的读数式与 `LoadTrace.usedBytes()`（`app/LoadTrace.kt:157`）逐字同式
 *    （`totalMemory() - freeMemory()`）。
 * 2. **单件上限**（只折余量，不作永久拒）：一笔像素/字节超过 Java 堆侧的像素预算
 *    [GraphicsPool.defaultPixelBudgetBytes]（= 堆上限 ÷ 4，`core/host/GraphicsBuffer.kt:1647`）时，
 *    这一笔**要多要一份预算档的空闲**才放行（判式 `needed + ceiling <= max - used`）。
 *    分母不随 [com.eratw.port.core.host.MemoryTier] 放开档抬：这一档量的是 Dalvik 堆里的
 *    `IntArray`，位图改道 native 那条前提对它不成立（2026-11-04 撤回 ÷3 的那一笔，见
 *    `MemoryTier` 类 KDoc「放开档管哪几道闸」）。
 *    **为什么不再是无条件否决**（2026-11-05 误伤普查，产物 `<work-root>/.tmp/report-20261104/`）：
 *    绝对否决把「这张图任何时候都不许解」当成了 OOM 防护，而它防不住 OOM、只防住画面 ——
 *    4.981 语料里唯一越 128 MiB 档的一张是 `resources/汎用モブ子dairi版/汎用モブ子dairi版.webp`
 *    （6964x6191 = 164.5 MiB ARGB；装载期 CSV 必经登记，画到才落地 ——
 *    `GDRAWSPRITE` → `GraphicsBuffer.blitFrom` 读 `src.pixels` 那一条合成链），
 *    堆空闲 443 MiB 时它照样被拒。
 *    更要命的是拒绝的下游：`core/host/GraphicsBuffer.kt:257-276` 的解码失败支会把延迟源置
 *    null（**此后不再重试**）并**同一尺寸分配一份全透明 `IntArray`** 顶上去 ——
 *    那一笔和被拒的那笔一样大，所以绝对否决在这条路上连内存都没省下来，只是把图换成了透明。
 * 3. **不可知堆不设闸**：`maxMemory` 读不到（<= 0 或 `Long.MAX_VALUE`）时一律放行，
 *    与 `defaultPixelBudgetBytes` 对不可知堆回落到 `Long.MAX_VALUE`（= 预算档不生效）同口径
 *    —— 桌面装置、纯 JVM 单测、语料对拍不能被我这道闸改变行为。
 *
 * ## 为什么不套报告头部那个「预检系数=70%」
 *
 * 那一列是 `ProgramLoader.artifactResidentPercent()`：它折的是**需求侧**
 * （估需 = 产物字节 × 0.7 / 0.8），系数越小越宽松。本闸要折的是**供给侧**（空闲余量），
 * 同一个系数换个轴就变成「放开档比保守档更严格」，方向正好反了 —— 那才是新造一套。
 * 所以这里只做「这笔要完整地装得进空闲，越预算档的大件再多留一档」的硬判式，档位差异由
 * 第 2 条腿的预算读数（堆上限 ÷ 4）自然带出，与 9a5225fc 之后「Java 堆侧不随档位放宽」的纪律一致。
 *
 * ## native 与 Java 两个池子不许混
 *
 * 第 2 条腿的预算是 **Java 堆**（`IntArray` 像素）。`Bitmap` 像素在 API 26 起进 native 堆，
 * 而 Android 不暴露单进程 native 上限（没有可对的分母，理由逐字见
 * `platform/.../SpriteBitmapSource.kt` 的 `SHEET_CACHE_BUDGET_BYTES` 与
 * `app/.../GraphicsTrimPlan.kt` 的「水位判据只看 Java 堆」两段）⇒ **native 侧一律不做头寸预检**，
 * 只做「分配失败不抛穿 + 先把可重建的交出去」（见 `render-canvas` 那三个 `createBitmap` 点）。
 *
 * ## 覆写性质（定向单测与装置专用，生产不设）
 *
 * 口径与 [GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY] 完全一致：性质只在**现取**那一层读，
 * 纯判式 [fits] 只吃传进来的读数。装置用它们复刻 512 MiB 挡贴顶现场：
 * - `eratw.preflight.maxMemoryBytes` = 挡位（字节）
 * - `eratw.preflight.usedBytes` = 已用（字节）
 */
object PixelPreflight {

    /** 一个 ARGB 像素的字节数（口径同 `GraphicsBuffer.pixelBytes`：Int = 4 字节/像素）。 */
    const val BYTES_PER_PIXEL: Long = 4L

    /** 传给 [fits] 的「这一腿不判」哨兵（读不到预算、或调用方没有池账时用）。 */
    const val UNKNOWN: Long = -1L

    /** 装置覆写：判定用的堆上限（字节）。生产不设，理由见类 KDoc。 */
    const val MAX_MEMORY_OVERRIDE_PROPERTY: String = "eratw.preflight.maxMemoryBytes"

    /** 装置覆写：判定用的已用堆（字节）。生产不设，理由见类 KDoc。 */
    const val USED_OVERRIDE_PROPERTY: String = "eratw.preflight.usedBytes"

    /**
     * 一张 `width x height` 的 ARGB 像素阵列的字节数（等价于 `GraphicsBuffer.pixelBytes`
     * 的 `pixels.size * 4L`，也是 ART 报给 `IntArray(n)` 的那个数减掉数组头）。
     *
     * **全程 Long**：`w * h * 4` 在 Int 上会回绕（`8192x8192x4 = 268,435,456` 还安全，
     * 但被改坏的表头/极大矩形会绕成小正数，于是「一笔只要 12 B」这种谎报会骗过头寸腿）。
     * 非正值折成 0 = 没有要分配的东西。
     */
    fun pixelBytes(width: Int, height: Int): Long =
        if (width <= 0 || height <= 0) 0L else width.toLong() * height.toLong() * BYTES_PER_PIXEL

    /** 本进程现取的已用堆（字节）；同 `LoadTrace.usedBytes()` 的读数式。 */
    fun usedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** 本进程现取的堆上限（字节）；同装载预检用的那一个读数。 */
    fun maxMemoryBytes(): Long = Runtime.getRuntime().maxMemory()

    /**
     * 纯判式（三条腿见类 KDoc；三个读数全部由调用方给，装置可复刻任意挡位现场）。
     *
     * 头寸腿是**唯一无条件**的那一条：装不进空闲就是装不进，任何一件都要被拒。
     * 单件上限腿只做**加码**：越过上限的大件要多要一份上限那么大的空闲
     * （`needed + ceiling <= max - used`），而不是任何时候都拒 ——
     * 后者会把「堆很空时也解不出来」的好图永久打成透明图，而下游失败支照样按同尺寸分配
     * 一份 `IntArray`（`core/host/GraphicsBuffer.kt:257-276` 的解码失败支，见类 KDoc 第二条腿），
     * 既不省内存也没防住崩。
     *
     * @param neededBytes 这一笔要多少字节；<= 0 一律判要得
     * @param maxMemoryBytes 堆上限；<= 0 或 [Long.MAX_VALUE] = 不可知 ⇒ 不设闸
     * @param usedBytes 当前已用堆
     * @param singleItemCeilingBytes 单件上限（像素预算）；[UNKNOWN] 或 <= 0 = 这一腿不加码
     */
    fun fits(
        neededBytes: Long,
        maxMemoryBytes: Long,
        usedBytes: Long,
        singleItemCeilingBytes: Long = UNKNOWN,
    ): Boolean {
        if (neededBytes <= 0L) return true
        if (!heapKnown(maxMemoryBytes)) return true
        val headroom = maxMemoryBytes - usedBytes
        if (headroom <= 0L) return false
        // 大件加码：越上限的一件要额外留出「一整个预算档」的空闲才解
        // （÷4 档的原始论据第 2 条就是「留下 3/4 给装载基线与瞬时拷贝」，这里只是让
        // 单件自己遵守这份留白，而不是把它永久赶出画面）。
        val margin = if (singleItemCeilingBytes > 0L && neededBytes > singleItemCeilingBytes) {
            singleItemCeilingBytes
        } else {
            0L
        }
        // 写成减法而不是 `needed + margin <= headroom`：两笔都接近 Long 上限时加法会绕成
        // 负数，绕出来的小数反倒「装得下」而被放行（谎报的尺寸族正是这一形）。
        return neededBytes <= headroom - margin
    }

    /** 堆上限是否是一个可对的读数（口径同 `defaultPixelBudgetBytes` 的不可知回落）。 */
    fun heapKnown(maxMemoryBytes: Long): Boolean =
        maxMemoryBytes > 0L && maxMemoryBytes != Long.MAX_VALUE

    /**
     * 一张 [width]x[height] 的 ARGB 像素阵列现在要得起吗（现取读数 + 既有像素预算）。
     *
     * 判不足时先催一次 GC 再判第二遍：ART 的 `freeMemory` 里扣着尚未回收的瞬时垃圾，
     * 一次误判的代价是「这一帧这块画面缺一块」，而一次多余 GC 的代价是几十毫秒 ——
     * 只在**拒绝分支**上付，绿路零开销。催 GC 的既有同族先例是装载期
     * `LoadTrace.forceGc` / `LoadGcPolicy`（app 侧，本模块不引它，只同口径）。
     */
    fun affordsPixels(width: Int, height: Int): Boolean =
        affordsBytes(pixelBytes(width, height))

    /**
     * 这么大一笔**堆内存**现在要得起吗（`IntArray` 像素那一路；`ByteArray` 那一路见
     * [affordsFileBytes]，它不套单件上限）。
     *
     * 单件上限走 `GraphicsPool.defaultPixelBudgetBytes(判定用的堆)`：正、负两段的预算闸共用这**一个**
     * 旋钮（覆写性质、÷4 分母、不可知堆回落到 `Long.MAX_VALUE` 全部同源，见其 KDoc），
     * 判档用的堆与头寸腿用的是同一个读数 —— 装置注入 512 MiB 挡时两边一起跟着走，不出两张皮
     * （同 `MemoryTier.isGenerous(maxMemoryBytes)` 那句「判定用的堆交给调用方」的纪律）。
     */
    fun affordsBytes(neededBytes: Long): Boolean = affordsWithCeiling(neededBytes, ceiling = true)

    /**
     * 这么大一份**整文件压缩字节**现在读得起吗（`ResourceCsvHost.readBytes` 专用）。
     *
     * 与 [affordsBytes] 只差一处：**不套第 2 条腿的单件上限**。理由是单位不对 ——
     * 那一档量的是解码后的 ARGB `IntArray`，而这里要的是压缩字节：4.981 语料 3,090 张图里
     * 单张最大文件 8.7 MiB（`resources/isaac/cg/isaac_cg.webp`，lane preflightblast 2026-11-05
     * 尺寸普查），离任何挡位的上限（保守 64 MiB / 放开 128 MiB）都还差一个量级，这一腿**今天
     * 一次都没生效过**；留着它只会在「有人塞进一个超大 csv / 音频」那天把判定推到永久拒那一边。
     * 而这一口的失败面比像素那一路大得多：CSV 正文读不到 = `ResourceContents.kt:104-108`
     * 的「打开失败，已跳过」⇒ **整张表的 sprite 一起没**，不是一块图。
     * 头寸腿照旧无条件生效（0.2.38 那三份装载期报告的正是这一腿的量级），拒绝分支也照旧先催一次 GC。
     */
    fun affordsFileBytes(neededBytes: Long): Boolean = affordsWithCeiling(neededBytes, ceiling = false)

    private fun affordsWithCeiling(neededBytes: Long, ceiling: Boolean): Boolean {
        val max = maxMemoryOverride() ?: maxMemoryBytes()
        val used = usedOverride() ?: usedBytes()
        val itemCeiling = if (ceiling) ceilingBytes(max) else UNKNOWN
        if (fits(neededBytes, max, used, itemCeiling)) return true
        runCatching { System.gc() }
        return fits(neededBytes, max, usedOverride() ?: usedBytes(), itemCeiling)
    }

    /**
     * 单件上限（字节）= 池子那条预算档：覆写性质优先，回落堆上限 ÷ [GraphicsPool.PIXEL_BUDGET_DIVISOR]。
     * 不另写一份除法，也不另立一个常数（判据 2「不许发明第二个预算常数」）。
     */
    fun ceilingBytes(maxMemoryBytes: Long = maxMemoryOverride() ?: maxMemoryBytes()): Long {
        System.getProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }?.let { return it }
        return GraphicsPool.defaultPixelBudgetBytes(maxMemoryBytes)
    }

    /** 一行取证读数（调用方打进 logcat；数值不参与任何判式）。 */
    fun readout(neededBytes: Long): String =
        "要 $neededBytes B / 上限 ${maxMemoryBytes()} B / 已用 ${usedBytes()} B / " +
            "像素预算 ${ceilingBytes()} B"

    private fun maxMemoryOverride(): Long? =
        System.getProperty(MAX_MEMORY_OVERRIDE_PROPERTY)?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private fun usedOverride(): Long? =
        System.getProperty(USED_OVERRIDE_PROPERTY)?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
}
