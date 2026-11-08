package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.save.SaveDataProbe
import com.eratw.port.core.save.SaveWiring

/**
 * 存档槽家族的内建函数（[registerSaveMethods]）：`CHKDATA` / `SAVENOS`。
 *
 * 落盘本体在 `core/save/FileSystemOps.kt`（`SystemOps` 的真文件实现，装配点在
 * `core/proc/GameSession.kt`）；本文件只保留「存档槽 → 可读状态」的两条查询函数，
 * 并在 `Builtins.buildRegistry()` 末尾注册（占位先铺满、真实现后覆盖，见该函数的顺序契约）。
 *
 * ## 逐条口径
 *
 * `CHKDATA(no)` = `CheckdataMethod`（FunctionMethodCreator.cs:3512-3539）：
 * - `argumentTypeArray = new Type[1] { typeof(long) }`、`ReturnType = typeof(long)`、
 *   `CanRestructure = false`（:3519-3520）—— 故 [BuiltinMethod] 的 arity 恰为 1；
 * - 负值 → `ArgIsNegative`（:3527-3530）：`CHKDATA函数: 第1个参数被是负值({2})`
 *   （lang/emuera.zhs.xml:913）；
 * - `> Int.MAX_VALUE` → `ArgIsTooLarge`（:3531-3534）：`CHKDATA函数: 第1个参数({2})过大`
 *   （lang/emuera.zhs.xml:915）；
 * - `exm.VEvaluator.RESULTS = eraDataResult.DataMes`（:3536）后返回 `(long)State`（:3537）。
 *   探测本体走 `core/save/SaveDataProbe`（`CheckDataByFilename`，VariableEvaluator.cs:2159-2246）。
 *   `UseSaveFolder` 从 [SaveWiring] 现读：真值下只探 `sav/`，否则只探根目录
 *   （`SavDir`，Config.cs:471-481）；未装配则为 null = 双候选保守口径。
 *
 * `SAVENOS()` = `GetSaveNosMethod`（FunctionMethodCreator.cs:3952-3966）：
 * `argumentTypeArray = Array.Empty<Type>()`、`CanRestructure = true`（:3955-3956），
 * 返回 `Config.SaveDataNos`（:3963）—— 生效值经 [SaveWiring.saveDataNos]
 * （`Config.cs:338` 读 + `:441-450` 夹到 20..80，见 `EffectiveConfig.saveDataNos`）；
 * 未装配时回落 [BuiltinHost.saveDataNos]（接口缺省 20，ConfigData.cs:87 同源）。
 *
 * ## 本文件不做的
 *
 * `CHKCHARADATA` / `FIND_CHARADATA`（角色档族，占位；见 `CharaFileMethods.kt` 与交付报告）、
 * `CHKDATAS`（`CheckdataStrMethod`，本仓 builtin-functions.yaml 未收录该名字）。
 */
internal fun registerSaveMethods(r: BuiltinRegistry) {
    val I = OperandType.INT

    // spec: FunctionMethodCreator.cs:3512-3539 CheckdataMethod
    if (claimable(r, "CHKDATA")) {
        r.register(BuiltinMethod("CHKDATA", I, 1, 1) { c, a ->
            val no = requiredIntArg(a, 0, "CHKDATA")
            // :3527-3534 —— 文案取 Lang.Error.ArgIsNegative / ArgIsTooLarge
            // （lang/emuera.zhs.xml:913 / :915），{0} = 函数名、{1} = 参数号、{2} = 实参。
            if (no < 0) throw CodeEE("CHKDATA函数: 第1个参数被是负值($no)")
            if (no > Int.MAX_VALUE) throw CodeEE("CHKDATA函数: 第1个参数($no)过大")
            // **目录口径必须与 SAVEDATA / DELDATA 同源**（2026-11-01 玩家反馈收口）。
            //
            // `SAVEDATA` / `LOADDATA` / `DELDATA` 的落盘路径由 `FileSystemOps` 的
            // `SaveConfig.useSaveFolder` 决定（`GameSession` 从**会话配置**读出并装进
            // [SaveWiring]）；而 `RootedBuiltinHost.checkSaveData` 读的是**宿主自己那份
            // 配置链**（`BootHosts.kt:395` = `ConfigMethods.useSaveFolder(configChain.store)`）。
            // 两把尺来自两个对象，一旦不一致就出现「写进 A、查的是 B」：
            // - 宿主尺 = false、会话尺 = true ⇒ 档写在 `sav/` 而列表永远报「空槽」
            //   （玩家看到的就是「存档存不上 / 不能覆盖旧存档」）；
            // - 反向 ⇒ `DELDATA` 删掉根目录那份，`CHKDATA` 仍在 `sav/` 里找到旧档，
            //   删完的槽位又「冒出」一个存档（玩家报的「出现新的未知存档」）。
            //
            // 而且旧写法里这个不一致**必然**暴露：`checkSaveData` 在「没找到」时返回的是
            // 非 null 的 `SaveDataCheck(FILENOTFOUND)`（`BootHosts.kt:452`），`?:` 因此
            // 短路，带着权威 `useSaveFolder` 的 [SaveDataProbe.check] **永远轮不到** ——
            // 只有「宿主找不到」这一种情况才最需要它。
            //
            // 故：会话已接线（[SaveWiring] 在）时直接走 [SaveDataProbe.check]，与写盘侧共用
            // 同一份 `SaveConfig`；未接线（单测装置 / 裸宿主）才退回「宿主先判存在性」的旧序，
            // 那里宿主的链就是唯一可用口径，两者不可能分叉。
            val wiring = SaveWiring.of(c.store)
            val useSaveFolder = wiring?.config?.useSaveFolder
            val res = if (wiring != null) {
                SaveDataProbe.check(
                    c.host, SaveFileKind.NORMAL, no, c.store.registry.gameBase, useSaveFolder,
                    // 存档列表每页 40 格、每次重绘都调 CHKDATA：只解头字段，
                    // 不在这里为显示描述文本把整份存档解压一遍（2026-10-31 性能修）。
                    headerOnly = true,
                )
            } else {
                // 宿主自带探测优先（可返回完整 SaveDataCheck）；宿主只判定存在性（found 回 null）时
                // 带着 gameBase 补全头探测。探测回 null 只剩「fileOps 未接线」一种
                // —— 槽位在而读盘失败已由 SaveDataProbe.check 折成 ETC_ERROR(4) + LoadError 文案
                // （VariableEvaluator.cs:2220-2228，Lang.Error.LoadError =「加载时发生错误」），
                // 不再冒充「未接线」。两路皆无能力才抛错 —— 不静默答「不存在」。
                c.host.checkSaveData(no, SaveFileKind.NORMAL)
                    ?: SaveDataProbe.check(
                        c.host, SaveFileKind.NORMAL, no, c.store.registry.gameBase, useSaveFolder,
                        headerOnly = true,
                    )
            } ?: throw CodeEE("函数 CHKDATA 需要宿主接线存档探测（checkSaveData == null）")
            // :3536 —— 直接给 RESULTS:0 赋描述文本；RESULTS 长度不足时静默越界（同 ENUMFILES 口径）。
            if (c.store.getLength("RESULTS", -1) > 0) c.store.writeStr("RESULTS", longArrayOf(0), res.mes)
            Value.IntV(res.state.code) // :3537
        })
    }

    // spec: FunctionMethodCreator.cs:3952-3966 GetSaveNosMethod
    if (claimable(r, "SAVENOS")) {
        r.register(BuiltinMethod("SAVENOS", I, 0, 0) { c, _ ->
            val nos = SaveWiring.of(c.store)?.saveDataNos ?: c.host.saveDataNos
            Value.IntV(nos.toLong()) // :3963 `return Config.SaveDataNos`
        })
    }
}

/** 名字还能被本家族占用？（不存在，或仍是占位实现） */
private fun claimable(r: BuiltinRegistry, name: String): Boolean =
    r[name] == null || r.isPlaceholder(name)
