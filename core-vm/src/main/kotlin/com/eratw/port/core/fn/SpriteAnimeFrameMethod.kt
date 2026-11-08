package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool

/**
 * 「动画精灵的帧数」查询面 —— `SPRITEANIMEFRAME(name)`。
 *
 * 本函数**独立成文件**：写侧（`SPRITEANIMECREATE` / `SPRITEANIMEADDFRAME`）在
 * `fn/GraphicsMethods.kt`，那一份文件有并行改动的所有者，读侧不必挤进去改同一个文件。
 *
 * ## 出处（4.981 fork 新增，原版 Emuera 1.824 无此命令）
 *
 * `画蛇又添足版自改emuera相关说明.txt:236-239`：「新增命令：SPRITEANIMEFRAME，获取动画精灵的
 * 帧数。统一支持 SpriteAnime、SpriteGif、SpriteWebP 三种动画精灵的帧数查询。废弃
 * GET_GIF_FRAMES、GET_GIF_WIDTH、GET_GIF_HEIGHT、PRINT_GIF_FRAME。见手册」。
 *
 * 该 fork 只发布二进制与这份更新记录：`reference/emuera-cs` 全树 grep 零命中，唯一命中就是
 * 这份说明本身（2026-11-06 复核），所以**没有 C# 行号可对**——语义按说明原文 + 原版
 * `SpriteAnime` 的帧表形状推出（`SpriteAnime.cs` 的 `frames` 列表与 `totaltime` 求和），
 * 函数体只有一次查表、没有可复刻的分支。`specs/builtin-functions.yaml` 把该条登记为
 * `confidence: medium`，理由就是这句「无 C# 源」。
 *
 * ## 语义
 *
 * 返回该动画精灵**当前持有的帧数**，即 `SpritePool.Sprite.frames` 的长度：
 * - 名字大小写不敏感（[SpritePool.find] 归一到大写；同原版 `AppContents.GetSprite` 的
 *   `imageDictionary` 口径，AppContents.cs:40-48）；
 * - 帧表只由 `SPRITEANIMEADDFRAME` 追加（`fn/GraphicsMethods.kt` 的同名方法）：入表失败
 *   （gid 未创建 / 源矩形越界 / delay <= 0）**不入表**，所以读数是「成功入表的帧数」，
 *   与原版「帧列表里实际有几个 AnimeFrame」同义；
 * - 帧表里 `srcW/srcH = 0` 的「交集为空」帧也算一帧：原版 `AnimeFrame.Normalize` 只把矩形
 *   裁空（SpriteAnime.cs:24-36），帧仍在表里、`totaltime` 仍加它的 delay（:72-78）。
 *
 * ## 返回 0 的四种输入（都不抛错）
 *
 * 1. 名字从未创建（`SPRITEANIMECREATE` / `SPRITECREATE` 都没建过）→ [SpritePool.find] 为 null；
 * 2. 存在但是**静态** sprite（`SPRITECREATE` / `SPRITECREATED` 建的那族）：`frames == null`，
 *    压根没有「帧数」这个概念；
 * 3. 存在、是动画容器但一帧没加：`SPRITEANIMECREATE` 之后帧表为空，长度 0；
 * 4. 名字是空串（脚本写 `SPRITEANIMEFRAME("")`）：查表必不命中。
 *
 * 查表用 `RuntimeAux.peek`（只查不建）：纯查询不该在 `store.aux` 里凭空造一个 [SpritePool] 槽。
 * 池不存在的语义就是「一个精灵都没有」，答案 0；这也是 headless / 内存编译装置下的常态。
 *
 * ## 与 fork 的差异（登记）
 *
 * - **SpriteGif / SpriteWebP 未支持**：端口的精灵池只有两种形态——静态基图与 `SpriteAnime`
 *   （`frames` 非 null 即动漫形态，见 `host/GraphicsBuffer.kt` 的 [SpritePool.Sprite]）。GIF 完全
 *   不解码；`WebpDecoder` 只出静态首帧（消费点：`fn/GraphicsMethods.kt` 的 `GCREATEFROMFILE`），
 *   所以这两种名字查不到帧表 ⇒ 一律 0。
 *   TODO(verify)：卡点 = 端口无 Gif/WebP 解码面。等这两族落地后本函数要改成按精灵实际形态
 *   分派（SpriteAnime 读帧表、Gif/WebP 读各自帧数），届时上面三个 0 分支要逐条重排。
 * - **废弃命令没有并行实现**：`GET_GIF_FRAMES` / `GET_GIF_WIDTH` / `GET_GIF_HEIGHT` /
 *   `PRINT_GIF_FRAME` 本端口从未登记（[BuiltinRegistry] 与 `fn/HostMethods.kt` 的占位名单里都
 *   没有这四个名字），所以「统一替代」在移植侧是**唯一**读法，没有新旧两套读数要兼容。
 *
 * ## 参数校验（照既有内建惯例）
 *
 * 个数由 [BuiltinMethod.checkArity] 统一报（`函数 SPRITEANIMEFRAME 的参数个数不匹配（需要 1 个）`
 * 这条既有文案）；类型错按邻近家族（`fn/HtmlMethods.kt` 同一句式）报 CodeEE，不做隐式转串——
 * 原版 `GetFunctionMethod` 在解析期就按 `CheckArgumentType` 挡掉类型不符，移植侧的这层检查
 * 至今仍是运行期补齐（METHOD_ARG_TYPE 缺口，见 `PcOracleGapTest`），文案与 HtmlMethods 一致。
 *
 * 注册点：`Builtins.buildRegistry`（`registerSpriteAnimeFrameMethod`）。权威条目：
 * `specs/builtin-functions.yaml` 的 `SPRITEANIMEFRAME` 段（4.981 增量区）。
 */
internal fun registerSpriteAnimeFrameMethod(r: BuiltinRegistry) {
    r.register(
        BuiltinMethod("SPRITEANIMEFRAME", OperandType.INT, 1, 1) { c, a ->
            val name = (a[0] as? Value.StrV)?.v
                ?: throw CodeEE("函数 SPRITEANIMEFRAME 的第 1 个参数必须是字符串")
            val frames = c.aux.peek<SpritePool>(SPRITE_SLOT)?.find(name)?.frames
            Value.IntV(frames?.size?.toLong() ?: 0L)
        },
    )
}
