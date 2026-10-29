package com.eratw.port.core.save

import com.eratw.port.core.vars.VariableStore

/**
 * 一会话一份的存档接线参数：落盘策略 + `SAVENOS` 读数。
 *
 * ## 为什么挂在 [VariableStore.aux]
 *
 * 原版这三项是 `Config` 的静态字段（`SystemSaveInBinary` / `ZipSaveData` / `UseSaveFolder`，
 * `ConfigData.cs:107` / `:128` / `:97`），`SAVENOS` 与 `SAVETEXT` 们随手就能读到。移植侧
 * 不许有静态可变全局（AGENTS 铁律 4），而语句层（`SystemOps`）与函数层（`CHKDATA` 的
 * `BuiltinContext`）**拿不到同一个装配对象**：前者由 `ExecContext.systemOps` 持有，后者只有
 * 变量池 + [com.eratw.port.core.fn.BuiltinHost]。aux 槽是两边都看得见的唯一面
 * （[com.eratw.port.core.host.RuntimeAux] 的既有用法，同 xml/map/dt/gfx 池）。
 *
 * 台账：装配点唯一 = `GameSession`（`core/proc/GameSession.kt` 的存档段），从
 * [com.eratw.port.core.config.ConfigStore] 按 [com.eratw.port.core.config.ConfigSpec] 的键读出，
 * 再 `install` 一次。函数层用 [of] 现读，不缓存 —— 与 `SemConfigHost` 的「取表点唯一」同纪律。
 *
 * ## 读不到时怎么办
 *
 * [of] 返回 null = 宿主没走 `GameSession` 装配（纯单测 / erbcc 自检）。此时：
 * - `CHKDATA` 回到 `SaveDataProbe` 的「两个候选目录都探」保守口径；
 * - `SAVENOS` 回到 `BuiltinHost.saveDataNos`（接口缺省 20，`ConfigData.cs:87` 同源）；
 * - 版本判定回到 `version != 0` 的近似（[scriptVersionDefined] 为 null，差异见
 *   [com.eratw.port.core.data.save.GameBaseGate.checkVersion]）。
 *
 * 不抛错：探测类函数在缺失装配时仍应给出原版形状的答案，而不是把装配缺陷伪装成语义错。
 *
 * @property config 落盘策略（二进制 / 压缩 / sav 子目录 / 文本行尾与编码）
 * @property saveDataNos `Config.SaveDataNos` 的**生效值**（`Config.cs:338` 读 + `:441-450` 夹到
 *   20..80，见 `EffectiveConfig.saveDataNos`）——`SAVENOS` 与语句 `SAVENOS` 同一份读数
 * @property emSets `VarExt*.csv` 的 Map/Xml/DT 扩展段名字表（[EmSaveSets]）
 */
data class SaveWiring(
    val config: SaveConfig,
    val saveDataNos: Int,
    /**
     * Map/Xml/DT 扩展段的名字表（`VarExt*.csv` 的九条指令，[EmSaveSets]）。
     * 缺省 [EmSaveSets.EMPTY] = 不写不读扩展段（`VarExt` 缺失时的原版形态，
     * `ConstantData.cs:1311-1316` 的六表清空）。
     *
     * 装配点调 [EmSaveSets.parse] 时应把 `Config.IgnoreCase` 的现值传给它的
     * `ignoreCase` 形参（原版指令名按 `Config.StringComparison` 比较，
     * `ConstantData.cs:1344` + `Config.cs:299-307`；出厂默认与 eratw 的 emuera.config
     * 都是忽略大小写 ⇒ [EmSaveSets.parse] 的缺省值已同形，不传也不会错，
     * 只有把开关关成 NO 的场合才需要显式传 false）。
     */
    val emSets: EmSaveSets = EmSaveSets.EMPTY,
    /**
     * `GameBase.ScriptVersionDefined`（`GameBase.cs:25` + `:138-140`）：GameBase.csv 的
     * `バージョン` 行写了且能解析出数字时为 true，**与解析出来的数值是不是 0 无关**。
     * [com.eratw.port.core.save.SaveDataProbe.checkVersion] 的第 1 条放行要用它。
     *
     * 这一位的**真值源头**是 [com.eratw.port.core.vars.GameBaseData.versionDefined]
     * （由 `GameBaseLoader` 按 `GameBase.cs:138-140` 填），所以本字段只是**宿主级覆盖**：
     * 装配方没有 registry（纯单测 / 存档回放夹具）或想按会话改判时才传值。
     * null = 不覆盖，由 [com.eratw.port.core.data.save.GameBaseGate.checkVersion] 自己退级。
     */
    val scriptVersionDefined: Boolean? = null,
) {

    companion object {

        /** aux 槽名（约定：能力家族前缀 + 小写，见 [com.eratw.port.core.host.RuntimeAux] 类 KDoc）。 */
        const val AUX_SLOT: String = "save.wiring"

        /** 现读；未装配返回 null（口径见类 KDoc）。 */
        fun of(store: VariableStore): SaveWiring? = store.aux.peek(AUX_SLOT)

        /**
         * 装配（只建一次；重复调用保留先装的那份）。
         *
         * 为什么保留先装的：读档（`LOADDATA`）会 `resetValues`，但 aux 槽是**会话级**状态、
         * 不属于存档内容（原版 `Config` 也不会被读档改写）。装配期重复调用只会发生在
         * 宿主重建会话的场合，那种场合本来就该新建 [VariableStore]。
         *
         * @param emSets `VarExt*.csv` 解析结果（[EmSaveSets.parse]）；缺省 = 无扩展段
         * @param scriptVersionDefined 见 [SaveWiring.scriptVersionDefined]；缺省 null = 近似口径
         */
        fun install(
            store: VariableStore,
            config: SaveConfig,
            saveDataNos: Int,
            emSets: EmSaveSets = EmSaveSets.EMPTY,
            scriptVersionDefined: Boolean? = null,
        ): SaveWiring = store.aux.slot(AUX_SLOT) { SaveWiring(config, saveDataNos, emSets, scriptVersionDefined) }
    }
}
