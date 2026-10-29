package com.eratw.port.core.fn

import com.eratw.port.core.host.SpritePool

/**
 * 背景层准入判定：`SETBGIMAGE` 的资源名能否进背景层。对应原版
 * `EmueraConsole.AddBackgroundImage`（`EmueraConsole.cs:897-907`）外的那道条件：
 * `if (AppContents.GetSprite(name) is SpriteF spr)`（`EmueraConsole.cs:899`）。
 *
 * ## 语义（自 `GameHost.installGraphics` 的 GraphicsLayer lambda 原样下沉，GameHost.kt:907-918）
 *
 * 名字查得到 sprite 表、非动画、且是整幅基图（src 矩形覆盖整个 buffer）才准进背景层，
 * 其余整条语句静默不成立——「SETBGIMAGE 打在 GCREATE 之前」与 PC 同判，迟一步冒出来的
 * 不一致就此收口。判定不过时原版连 `backgroundList.Add`（:903）与 `BakeBackground`（:905）
 * 都不执行：那层永远不进背景表，之后资源加载上了也不会补画。
 *
 * 三个钉死的口径（原 lambda 注释原样保留）：
 * 1. 「SETBGIMAGE 打在 GCREATE 之前」与 PC 同判：判定要求 [SpritePool.Sprite.isCreated]
 *    （基图已创建，`ASpriteSingle.cs:24-34` 的 `BaseImage != null && BaseImage.IsCreated`）。
 *    基图释放后 sprite 登记项不删、isCreated 转 false（口径见 `ProcessRuntime.kt:2221-2222`），
 *    此时同样不收——不存在「先收下、图到位后补画」的路径。
 * 2. 只查 sprite 字典、不回退文件资源：`GetSprite` 就只查字典（spec: `AppContents.cs:39-51`，
 *    键按 `ToUpper(InvariantCulture)` 归一在 :45，未命中返回 null 在 :46-50），原版没有按
 *    文件名兜底的分支。字典查找本身不在本函数内（查找是 app 侧装配细节）：调用方先
 *    `aux.peek` 到 [SpritePool] 再 `find(name)`，把查到的 sprite（或 null）交进来。
 * 3. SpriteG 全矩形参近似：C# 只按**构造形态**区分 SpriteF/SpriteG——`SpriteF` 只在 CSV
 *    资源装载路径构造（`AppContents.cs:369`，唯一构造器 `SpriteF.cs:5-12`，基图是
 *    `ConstImage`；CSV 里矩形缺省整幅在 `AppContents.cs:300`、可被子矩形覆盖在 :314），
 *    `SpriteG` 只在 SPRITECREATE 语句路径构造（`AppContents.cs:80-89`，:87
 *    `new SpriteG(imgName, parent, rect, ...)`，基图是 `GraphicsImage` 拼图 + 子矩形）。
 *    移植侧 [SpritePool.Sprite] 不携带构造形态，本判定以「src 矩形覆盖整个 buffer」近似
 *    `is SpriteF`：SPRITECREATE 全矩形参（src 覆盖整幅）的 SpriteG 也判过——像素覆盖等价，
 *    C# 只按构造形态区分 SpriteF/SpriteG。`DestBaseSize`（[SpritePool.Sprite.width] 与
 *    [SpritePool.Sprite.height]，缩放目标尺寸）不参与判定，与原 lambda 一致。
 *
 * ## 纯函数边界
 *
 * 只读入参字段：无 IO、无随机、无静态可变状态（AGENTS.md 铁律 4）。
 * [SpritePool.Sprite] 的 name/srcX/srcY/srcW/srcH 是登记后不再变化的 val（读侧可放心用，
 * 见 `core/host/GraphicsBuffer.kt` 的 SpritePool 类 KDoc）；buffer 宽高是运行期可变状态，
 * 读到的口径与原 lambda 相同，跨线程纪律归调用方的装配点。
 * 动画 sprite 的 [SpritePool.Sprite.isCreated] 恒 true（`SpriteAnime.cs:54` 的
 * `IsCreated => true`），所以 `!isAnime` 必须显式判，不能指望 isCreated 把动画挡掉。
 */
object BackgroundAdmission {

    /**
     * 五态判定表（期望值与 `GameHost.kt:913-918` 的原 lambda 逐条一致）：
     * - null（字典未命中，`GetSprite` 返回 null，`AppContents.cs:46-50`）→ false；
     * - 未创建（静态 sprite 的 buffer 缺失或宽高为 0，[SpritePool.Sprite.isCreated] false）→ false；
     * - 动画（[SpritePool.Sprite.isAnime] true；帧表 sprite 不进背景层）→ false；
     * - 部分矩形 src（SPRITECREATE 6/8/10 参形态，src 不覆盖整幅 buffer）→ false；
     * - 整幅 src（`srcX == 0 && srcY == 0 && srcW == buffer.width && srcH == buffer.height`）→ true。
     *
     * 判定式与原 lambda 逐字等价，短路顺序也一致（先 isCreated 后 isAnime：动画 sprite
     * 的 isCreated 恒 true，顺序不影响结果，保留只为逐字对齐）。
     */
    fun admissible(sprite: SpritePool.Sprite?): Boolean =
        sprite != null && sprite.isCreated && !sprite.isAnime && sprite.buffer.let { g ->
            g != null && sprite.srcX == 0 && sprite.srcY == 0 &&
                sprite.srcW == g.width && sprite.srcH == g.height
        }
}
