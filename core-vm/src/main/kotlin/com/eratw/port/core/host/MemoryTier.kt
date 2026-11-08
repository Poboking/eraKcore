package com.eratw.port.core.host

/**
 * 「这台机能不能多占内存」的档位 —— 三道自家内存闸共用的**唯一**一条判式。
 *
 * ## 先说清楚它不管什么
 *
 * Java 堆的硬上限（`Runtime.maxMemory()`）应用侧抬不动：`AndroidManifest.xml` 已经开了
 * `android:largeHeap="true"`，而框架对这一位的处理就是
 * `ActivityThread.handleBindApplication` 里那句 `VMRuntime.clearGrowthLimit()`
 * （一手代码：AOSP frameworks/base `main@1cdfff555f4a`
 * `core/java/android/app/ActivityThread.java:7649-7656`；不开 largeHeap 走对面那一支
 * `clampGrowthLimit()`）。清掉 growth limit 之后顶到的就是 `dalvik.vm.heapsize`，
 * 真机读数（vivo V2199A / Android 14 / API 34）：heapgrowthlimit=256m、heapsize=512m
 * ⇒ 我们拿到的 512 MiB 已经是这台机的顶格。**所以本档位只改我们自己写死的闸，不改系统上限。**
 *
 * ## 放开档的两个条件（缺一不可）
 *
 * 1. **API >= 26（Android 8.0）**：这条版本线把 Android 7.x 留在保守档。支撑它的读数有两条 ——
 *    ① 端点里 blackshark SKW-A0（Android 7.1.2）那份崩溃原文是 `Failed to allocate a 16 byte
 *    allocation with 4008 free bytes and 3KB until OOM`：连 16 字节都分配不出来，是 **Java 堆
 *    本身被榨干**，在那种机上多放行只会把闪退提前；② 本仓 2026-10-31 那条稳定性复核明记
 *    「7.1.2 的 largeHeap/native 上限口径与 Android 14 不同，本条未分开归因」—— 档位分档就是
 *    那一条的分开归因。
 *    TODO(verify)：**「API 26 是位图内存改道那一版」这个机制说法本仓没有一手取证**。本轮一手
 *    核过的只有 `largeHeap` = `clearGrowthLimit` 那一条（见上节）。要补的是两档真机
 *    `dumpsys meminfo` 的 Java Heap / Native Heap 分项对照（同产物同进度），拿到之前
 *    这条版本线的依据只有上面那两个读数。
 * 2. **`Runtime.maxMemory()` >= 512 MiB**：OEM 按整机内存配的挡位。少了这一条，
 *    256 MiB 挡的**新机型**也会被一起放开，而装载预检在 256 MiB 挡恒判不足是**产品护栏**
 *    （理由逐字在册：`core/.../loader/ProgramLoader.kt` 的「2026-11-03 重标」一节 ——
 *    该挡游玩期连图像预算都放不下）。
 *
 * ## 放开档管哪几道闸（2026-11-04 撤回第一道）
 *
 * - 工作表缓存（native 位图）：192 → 384 MiB —— 位图在 API >= 26 确实离开本挡 Java 堆，
 *   这条是真正「多拿内存」的那一道；
 * - 解码期 GC 线：55% → 65%；装载预检系数：80% → 70%（两条都是装载/解码期的放开力度）。
 * - **图形像素预算（`GraphicsPool.defaultPixelBudgetBytes`）不放开**：这一档量的是
 *   `IntArray` 像素，躺在本挡 Dalvik 堆里，位图改道那条前提对它不成立；抬到堆上限 ÷3
 *   （512 MiB 挡 170 MiB）等于从「3/4 留给装载基线与瞬时拷贝」的余量里划走 42 MiB。
 *   2026-11-04 端点报告 004311（Xiaomi 25060RK16C / API 35 / LOOK_AT 差分立绘合成）就是
 *   这份余量见底的形状：Java 堆 511/512 MiB、正号段驻留 248 MiB，`GraphicsBuffer.blitFrom`
 *   一发 0.5 MiB 瞬时分配 OOM。该闸已撤回，分母恒 ÷4。
 *
 * ## 性质从哪来（core 不许读 Android API）
 *
 * 铁律 4 禁 core 层碰任何 Android API，所以 API 级别由宿主在进程启动时落成 JVM 性质
 * （落地见 `app/.../EratwApplication.kt`）。**读不到性质一律按保守档**：桌面装置、
 * 定向单测、vmdbg 清扫全都没有这个性质，行为与本刀加进来之前逐字一致。
 *
 * 每读现取、不在进程里缓存：缓存就需要可变静态（口径同 [GraphicsPool] 的预算读法）。
 */
object MemoryTier {

    /** 宿主落下的 API 级别性质名（宿主只写这一个键，判式只认这一个键）。 */
    const val SDK_INT_PROPERTY: String = "eratw.device.sdkInt"

    /** 性质缺失时的读数：任何真实机型的 API 级别都大于 0，所以 0 恰好表示「不知道」。 */
    const val SDK_INT_UNKNOWN: Int = 0

    /** 版本线：Android 8.0（位图像素离开本挡 Java 堆的那一版）。 */
    const val GENEROUS_FROM_SDK_INT: Int = 26

    /** 堆线（MiB）：低于这个挡位不放开，理由见类 KDoc 第 2 条。 */
    const val GENEROUS_MIN_MAX_MEM_MIB: Long = 512L

    private const val MIB = 1024L * 1024L

    /**
     * 两入参的纯判式（单测钉两条线的边界用，不碰任何进程读数）。
     *
     * `maxMemoryBytes` 交给调用方是因为装载预检那条链上「判定用的堆」可以被测试注入
     * （`ProgramLoader.estimateHeap` 的 `maxMemoryOverride`），档位必须跟着同一个读数走，
     * 否则测试里会出现「按注入的堆判不足、按真实堆判放开」两张皮。
     */
    fun isGenerous(sdkInt: Int, maxMemoryBytes: Long): Boolean =
        sdkInt >= GENEROUS_FROM_SDK_INT && maxMemoryBytes >= GENEROUS_MIN_MAX_MEM_MIB * MIB

    /** 现取宿主落下的 API 级别；没落 ⇒ [SDK_INT_UNKNOWN]（= 保守档）。 */
    fun sdkInt(): Int = System.getProperty(SDK_INT_PROPERTY)?.trim()?.toIntOrNull() ?: SDK_INT_UNKNOWN

    /** 按「性质里的 API 级别 + 调用方给定的堆上限」现判。 */
    fun isGenerous(maxMemoryBytes: Long): Boolean = isGenerous(sdkInt(), maxMemoryBytes)

    /** 本进程现判：读一次性质、读一次 `Runtime.maxMemory()`。 */
    fun isGenerous(): Boolean = isGenerous(Runtime.getRuntime().maxMemory())
}
