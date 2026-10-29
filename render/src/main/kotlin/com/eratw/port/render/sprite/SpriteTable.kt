package com.eratw.port.render.sprite

/**
 * Sprite 表：渲染层的立绘注册表（契约 §2 m-sprite 要求的 `add` / `dispose` / `disposeAll`）。
 *
 * 对应原版 `AppContents` 的两张名键字典（`MinorShift.Emuera.UI.Game.Image/AppContents.cs:16-22`）：
 * - [runtime] ≈ `imageDictionary`（运行时表，`:18`）；
 * - [resources] ≈ `resourceImageDictionary`（CSV 资源表，`:22`，经 `LoadContents`
 *   末尾 `imageDictionary = new ConcurrentDictionary(resourceImageDictionary)` 整体镜像进运行时表，
 *   `AppContents.cs:161,176`，与 core-vm `SpritePool.putResource` 同口径）。
 *
 * 名字一律 `ToUpper(InvariantCulture)` 归一后作键（`AppContents.cs:45/57/86/102`；
 * Kotlin `uppercase()` 无区域敏感，等价）。
 *
 * **id 模型（重设计选择 + 原版行为对齐）**：原版 sprite 只有名字没有数字 id，
 * 数字 id 属于图形缓冲表 `gList`，且由脚本自选、条目**永不删除**
 * （`GetGraphics` 对任意整数惰性建空条目 `AppContents.cs:28-37`；`GDISPOSE` 的
 * `GetIntValue` 未创建返回 0、否则只调 `GDispose()`（`FunctionMethodCreator.cs:6945-6951`），
 * 后者把 `drawImgList`（`GraphicsImage.cs:531`）与 `g`（`:554`）一并置 null ⇒ `IsCreated`
 * 转 false（判据 `:44-53`：`g == null` 时回落到 `useImgList`，而 `useImgList` 就是
 * `drawImgList != null`（`:29`）），而 `gList` 的键只在 `UnloadContents`/`UnloadGraphicList`
 * 随整表 `Clear()` 一起消失（`AppContents.cs:193` 与 `:202`）。因此本表采用
 * 「id 单调递增、释放后永不复用」：与 `gList` 的键不复用一致；sprite 侧按名释放
 * （`SpriteDispose` 移除键，`AppContents.cs:53-64`）在 id 口径下表现为「该 id 永久失效」。
 *
 * 官方行为原样复刻（ADR-005）：
 * 1. 同名覆盖不释放旧对象、旧 id 立即失效（`imageDictionary[imgName] = value` 直接赋新，
 *    `AppContents.cs:88/104`，旧 sprite 未经 `Dispose()` 即不可达——原版的引用泄漏在
 *    GC 语言下即等价丢弃）；
 * 2. `disposeAll(false)` 把运行时表**整体回滚成资源表副本**（`AppContents.cs:76`）：
 *    先按名释放过的**资源** sprite 会随回滚重新出现在运行时表里（`GetSprite` 非空），
 *    但回滚是**字典键**的复活、对象本身仍是**已 Dispose 的死条目** —— `SpriteDispose`
 *    调 `value.Dispose()`（`AppContents.cs:58-61`，`Dispose()` 在 `:60`），
 *    `ASpriteSingle.Dispose()` 把 `BaseImage` 置 null（`ASpriteSingle.cs:66-69`，赋值在 `:68`），
 *    于是 `IsCreated` 恒 false（`ASpriteSingle.cs:24-34`，判据 `:28-32`），
 *    `SPRITEDISPOSE` 对它返回 0（`FunctionMethodCreator.cs:7087-7090` 的
 *    `!sprite.IsCreated` 守卫；只有过了守卫才在 `:7091` 调 `SpriteDispose`、`:7092` 记 1）。
 *    见 [entry] / [dispose] 的 [Entry.disposed] 判据与 [Entry.isCreated]；
 * 3. `disposeAll` 返回值是纯减法 `运行时表大小 - 资源表大小`（`AppContents.cs:68-69/77` 的
 *    `count - count2`）：先逐个释放资源 sprite 再调用会得到**负数**，照抄不修；
 * 4. `disposeAll(true)`（基准的 `SPRITEDISPOSEALL 1` 分支）两张表全清、
 *    返回清理**前**运行时表的大小，资源表独有条目不计（原版资源恒镜像进运行时表，
 *    故与「全部个数」一致；我们的 `fromCsv=true` 登记同样双写）。
 * 5. 死条目**不照画**：原版每条绘制路径都在 draw 之前查 `IsCreated`，不是「靠 GDI+ 抛异常
 *    兜底」—— 立绘层 `EmueraConsole.cs:1844`（`aSprite != null && aSprite.IsCreated` 才
 *    `GraphicsDraw`，`:1846`），HTML 图片段 `ConsoleImagePart.cs:168`（同守卫，未创建时
 *    退回画 alt 串 `:175-182`），映射取色 `ConsoleImagePart.cs:137`（不成立直接 `return 0`）。
 *    回滚复活的死条目因此有明确屏上读数：立绘层**什么都不画**（HTML 图片层退回画 alt 串），
 *    既不崩、也不再显示旧像素。本层用 [Entry.isCreated] 暴露同一谓词。
 *    已接线（2026-10-26 fixcore render-B1）：`view/ScreenComposer.kt` 的 `drawSprite` 在
 *    [find] 按名命中后补查 [Entry.isCreated]，`EmueraConsole.cs:1844` 的守卫补齐，死条目不再照画。
 *
 * 释放返回值约定：命中且存活记 1、否则 0 —— 原版 `SPRITEDISPOSE` 未命中或未创建返回 0、
 * 命中返回 1（`FunctionMethodCreator.cs:7083-7093`；基准 `pc-golden-root.txt:672`
 * `SPRITEDISPOSE=1`），对不存在的缓冲释放返回 0 的同约定见基准 `:651` `GDISPOSE14=0`。
 *
 * **名字与尺寸的判据分两层，本表只守名字**：
 * - 函数层 `SPRITECREATE`（`SpriteCreateMethod.GetIntValue`）空名**返回 0 不抛**
 *   （`FunctionMethodCreator.cs:7023-7026`）、缓冲未创建返回 0（`:7033-7036`）、
 *   源矩形与父图不相交抛 `ImgRefOutOfRange`（`:7044-7047`）、末两参取绝对值当目标尺寸
 *   （`:7059-7067`），摆位 `pos` 不做非负校验（`:7049-7054`，负偏移是语料的正常用法）；
 * - `AppContents` 层才是对空名抛的那一级（`:82-85`、`:98-101`），CSV 侧的尺寸界另有一套
 *   （`CreateFromCsv`：动画尺寸非正或大于 8192 整行弃 `AppContents.cs:257-261`、源图超 8192
 *   只告警不弃 `:280-283`、裁出的 rect 非正弃 `:316-320`、与父图不相交弃 `:321-325`）。
 *
 * 所以 [add] 只拒绝空名（对齐 `AppContents` 那一层），**不做尺寸判据**：尺寸由调用链
 * 上游（装载 / `SPRITECREATE` 的等价实现）保证，`Raster(0, 0)` 在本层是合法登记
 * （绘制时 `Raster.blit` 自然退化为无操作）。
 */
class SpriteTable {

    /**
     * 表项（不可变身份；摆位与缩放归上层，本波不引入）。
     *
     * @property id 全表生命周期内单调递增、永不复用
     * @property name 已按 InvariantCulture 大写归一的键（`AppContents.cs:45/86` 口径）
     * @property raster 像素源；渲染层持有引用，绘制时由上层 `Raster.blit` 消费
     * @property fromCsv 是否 CSV 资源（对应 `resourceImageDictionary` 一侧）
     */
    class Entry(
        val id: Int,
        val name: String,
        val raster: Raster,
        val fromCsv: Boolean,
    ) {
        /**
         * 原版「已 Dispose」的落点：`SpriteDispose` 的 `value.Dispose()`
         * （`AppContents.cs:58-61`，调用在 `:60`）把 `ASpriteSingle.BaseImage` 置 null
         * （`ASpriteSingle.cs:66-69`，赋值在 `:68`），于是 `IsCreated` 恒 false
         * （`ASpriteSingle.cs:24-34`）；之后 `SpriteDisposeAll(false)` 回滚字典
         * （`AppContents.cs:76`）拷回的是**同一个**已 Dispose 的对象，所以「复活」只是键复活，
         * 条目本身永远不可再释放、也不可再画（见 [isCreated] 与类 KDoc 复刻条款 5）。
         */
        internal var disposed: Boolean = false

        /**
         * 原版 `ASprite.IsCreated` 的同位谓词（`ASpriteSingle.cs:24-34`：`BaseImage` 为 null
         * 即 false）。绘制侧的守卫只读这一个值 —— 立绘层 `EmueraConsole.cs:1844`、
         * 图片段 `ConsoleImagePart.cs:168`、映射取色 `ConsoleImagePart.cs:137`。
         *
         * [SpriteTable.find] 返回非空不等价于「可画」：回滚复活的死条目仍能被按名查到，
         * 但本属性是 false（类 KDoc 复刻条款 2、5）。
         */
        val isCreated: Boolean get() = !disposed

        /** 宽 = 像素源宽（`SpriteG.DestBaseSize` 缩放归上层，本波不引入缩放）。 */
        val width: Int get() = raster.width

        /** 高 = 像素源高。 */
        val height: Int get() = raster.height
    }

    private val runtime = LinkedHashMap<String, Entry>()
    private val resources = LinkedHashMap<String, Entry>()

    /** id -> 表项；只在「当前挂在运行时表上的那个 id」意义上有效（覆盖后旧 id 被守卫挡掉）。 */
    private val alive = HashMap<Int, Entry>()

    private var nextId = 1

    /** 运行时表当前条目数（`imageDictionary.Count` 口径）。 */
    val size: Int get() = runtime.size

    /** 资源表当前条目数（`resourceImageDictionary.Count` 口径）。 */
    val resourceSize: Int get() = resources.size

    /**
     * 登记 sprite，返回新分配的 id。
     *
     * 同名（归一后）直接覆盖运行时表旧项、**不**释放旧项（`AppContents.cs:88/104` 的
     * 字典赋新语义）；`fromCsv = true` 时双写资源表与运行时表（`LoadContents` 镜像语义，
     * `AppContents.cs:161,176`）。id 单调递增、永不复用（见类 KDoc「id 模型」）。
     *
     * @throws IllegalArgumentException 名字为空。原版每条建精灵入口都先拒绝空名：
     *   `CreateSpriteG` 与 `CreateSpriteAnime` 对 `string.IsNullOrEmpty(imgName)` 抛
     *   无参 `ArgumentOutOfRangeException`（`AppContents.cs:82-85`、`:98-101`，抛点在 `:84/:100`），
     *   CSV 侧空名行整行跳过（`CreateFromCsv` 的 `AppContents.cs:240-243`）。异常类型本身
     *   不作逐字对齐（原版那条会被上层兜住），判据是「拒绝登记、不占 id、空键进不了表」：
     *   空名归一后是空键，放进去会让 `find("")` 命中一个原版永不存在的 sprite。
     *   校验放在分配 id **之前**：原版也是先抛后建（`:82-85` 在 `:87` 建对象之前）。
     */
    fun add(name: String, raster: Raster, fromCsv: Boolean = false): Int {
        require(name.isNotEmpty()) { "sprite 名字不能为空（AppContents.cs:82-85/98-101）" }
        val key = normalize(name)
        val entry = Entry(nextId++, key, raster, fromCsv)
        runtime[key] = entry
        alive[entry.id] = entry
        if (fromCsv) resources[key] = entry
        return entry.id
    }

    /**
     * `AppContents.GetSprite`：按名查运行时表，未命中返回 null（`AppContents.cs:39-51`，
     * null 名直接返回 null 一并覆盖：传 null 在本 API 是编译期不可表达，忽略）。
     *
     * 已释放的条目**仍可能被返回**：`SpriteDisposeAll(false)` 把资源表整份拷回运行时表
     * （`AppContents.cs:76`），原版 `GetSprite` 拿到的就是那个已 `Dispose()` 的对象
     * ——「非空但 `IsCreated == false`」这一对读数由本方法与 [entry] 分别承担，
     * 不要合并（合并会丢掉「键在、对象死」这个官方状态）。
     */
    fun find(name: String): Entry? = runtime[normalize(name)]

    /**
     * 按 id 查存活表项；id 未知、已释放或已被同名覆盖时返回 null。
     *
     * 「已释放」按原版 `IsCreated` 判（`ASpriteSingle.cs:24-34`）：条目一旦经 [dispose]
     * 走过 `Dispose()`，即使随后 `disposeAll(false)` 把它拷回运行时表也仍返回 null
     * （`GetSprite` 那一侧仍非空，见 [find] —— 原版这一对读数是「字典里有、`IsCreated` 假」，
     * `AppContents.cs:76` + `FunctionMethodCreator.cs:7087-7090`）。
     */
    fun entry(id: Int): Entry? {
        val e = alive[id] ?: return null
        if (e.disposed) return null
        return if (runtime[e.name]?.id == id) e else null
    }

    /**
     * 释放单个 sprite：返回 1 = 命中且存活（已从运行时表移除键，`AppContents.cs:53-64` +
     * `FunctionMethodCreator.cs:7083-7093`）；返回 0 = id 未知 / 已释放过 / 已被覆盖。
     * 资源条目只从运行时表移除，资源表副本仍在（原版行为），等待 [disposeAll] 的
     * 减法口径与「复活」语义（见类 KDoc 复刻条款 2、3）。
     *
     * 释放是单向的：置 [Entry.disposed]（原版 `ASpriteSingle.Dispose()`，
     * `ASpriteSingle.cs:66-69`），于是回滚复活后的同一个 id 再释放返回 0 —— 与
     * `SpriteDisposeMethod` 的 `!sprite.IsCreated` 守卫（`FunctionMethodCreator.cs:7087-7090`）
     * 一致：原版根本不会再次调 `AppContents.SpriteDispose`，键也就不再从表里移除。
     */
    fun dispose(id: Int): Int {
        val e = entry(id) ?: return 0
        runtime.remove(e.name)
        alive.remove(id)
        e.disposed = true
        return 1
    }

    /**
     * 全表释放，返回**释放个数**（基准 `pc-golden-root.txt:673` `SPRITEDISPOSEALL=35`，
     * GVIEW 分节 `:634-692`）。
     *
     * 复刻 `AppContents.SpriteDisposeAll`（`AppContents.cs:66-78`）：
     * - `delCsv = false`（基准入参 0）：运行时表回滚成资源表的新副本，返回
     *   `释放前运行时表大小 - 资源表大小`（`:68-69` 与 `:77` 的 `count - count2`；
     *   逐个先释放过资源条目后此值可为负，官方减法原样保留）；
     * - `delCsv = true`：两张表全清，返回清理前运行时表大小（`:70-74` 的 `count`）。
     *
     * 原版此处**不逐个调 `Dispose()`**（整表替换、对象交给 GC，`AppContents.cs:76`），
     * 本实现同样只清表；id 一律作废且永不复用。空表返回 0（契约判据）；
     * 连续第二次调用同样返回 0（运行时表已回滚为资源表副本，`count == count2`）。
     *
     * 回滚**不**把 `disposed` 复位：`delCsv = false` 之后资源条目重新出现在运行时表里，
     * 但此前按名释放过的那些仍是死条目（见类 KDoc 复刻条款 2）。
     */
    fun disposeAll(delCsv: Boolean = false): Int {
        val count = runtime.size
        val resourceCount = resources.size
        if (delCsv) {
            runtime.clear()
            resources.clear()
            alive.clear()
            return count
        }
        // 运行时表 = 资源表的新副本（AppContents.cs:76）
        runtime.clear()
        for ((key, e) in resources) runtime[key] = e
        alive.clear()
        for (e in runtime.values) alive[e.id] = e
        return count - resourceCount
    }

    /** `ToUpper(InvariantCulture)`（`AppContents.cs:45` 等；Kotlin `uppercase()` 无区域敏感，等价）。 */
    private fun normalize(name: String): String = name.uppercase()
}
