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
            val no = iv(a[0]!!)
            // :3527-3534 —— 文案取 Lang.Error.ArgIsNegative / ArgIsTooLarge
            // （lang/emuera.zhs.xml:913 / :915），{0} = 函数名、{1} = 参数号、{2} = 实参。
            if (no < 0) throw CodeEE("CHKDATA函数: 第1个参数被是负值($no)")
            if (no > Int.MAX_VALUE) throw CodeEE("CHKDATA函数: 第1个参数($no)过大")
            // 宿主自带探测优先（可返回完整 SaveDataCheck）；宿主只判定存在性（found 回 null）时
            // 带着 gameBase 与 UseSaveFolder 补全头探测。探测回 null 只剩「fileOps 未接线」一种
            // —— 槽位在而读盘失败已由 SaveDataProbe.check 折成 ETC_ERROR(4) + LoadError 文案
            // （VariableEvaluator.cs:2220-2228，Lang.Error.LoadError =「加载时发生错误」），
            // 不再冒充「未接线」。两路皆无能力才抛错 —— 不静默答「不存在」。
            val useSaveFolder = SaveWiring.of(c.store)?.config?.useSaveFolder
            val res = c.host.checkSaveData(no, SaveFileKind.NORMAL)
                ?: SaveDataProbe.check(c.host, SaveFileKind.NORMAL, no, c.store.registry.gameBase, useSaveFolder)
                ?: throw CodeEE("函数 CHKDATA 需要宿主接线存档探测（checkSaveData == null）")
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
