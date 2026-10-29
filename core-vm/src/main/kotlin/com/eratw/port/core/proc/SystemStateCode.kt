package com.eratw.port.core.proc

/**
 * 原版 `SystemStateCode`（`MinorShift.Emuera.Runtime.Script/SystemStateCode.cs:3-50`）的镜像。
 *
 * 数值**必须**与 C# 一值不差：上位标志位（`__CAN_SAVE__` / `__CAN_BEGIN__`）与状态值
 * 按位或在一起，原版靠 `(sysStateCode & __CAN_BEGIN__) == __CAN_BEGIN__` 判定
 * `BEGIN` 指令在当前状态是否可用（`ProcessState.cs:179-184`）。
 *
 * 本枚举只做**状态标识 + 标志位查询**；状态的进入/退出语义在 [SystemStateGraph]，
 * 执行在 [BeginFlow]。
 */
enum class SystemStateCode(val code: Int) {
    Title_Begin(0),
    Openning(1),

    Train_Begin(16),
    Train_CallEventTrain(17),
    Train_CallShowStatus(18),
    Train_CallComAbleXX(19),
    Train_CallShowUserCom(20),
    Train_WaitInput(21),
    Train_CallEventCom(131094),
    Train_CallComXX(131095),
    Train_CallSourceCheck(131096),
    Train_CallEventComEnd(131097),
    Train_DoTrain(26),

    AfterTrain_Begin(131104),

    Ablup_Begin(48),
    Ablup_CallShowJuel(49),
    Ablup_CallShowAblupSelect(50),
    Ablup_WaitInput(51),
    Ablup_CallAblupXX(131124),

    Turnend_Begin(131136),

    Shop_Begin(65616),
    Shop_CallEventShop(196689),
    Shop_CallShowShop(65618),
    Shop_WaitInput(65619),
    Shop_CallEventBuy(196692),

    SaveGame_Begin(256),
    SaveGame_WaitInput(257),
    SaveGame_WaitInputOverwrite(258),
    SaveGame_CallSaveInfo(259),
    LoadGame_Begin(272),
    LoadGame_WaitInput(273),
    LoadGameOpenning_Begin(288),
    LoadGameOpenning_WaitInput(289),
    AutoSave_CallSaveInfo(513),
    AutoSave_CallUniqueAutosave(514),
    AutoSave_Skipped(515),
    LoadData_DataLoaded(528),
    LoadData_CallSystemLoad(131601),
    LoadData_CallEventLoad(131602),
    Openning_TitleLoadgame(544),
    System_Reloaderb(560),
    First_Begin(576),

    Normal(262143),
    ;

    /** `__CAN_SAVE__`（SystemStateCode.cs:5）：存档流程可用。 */
    val canSave: Boolean get() = (code and CAN_SAVE) == CAN_SAVE

    /** `__CAN_BEGIN__`（SystemStateCode.cs:6）：`BEGIN` 指令在该状态可用。 */
    val canBegin: Boolean get() = (code and CAN_BEGIN) == CAN_BEGIN

    companion object {
        /** SystemStateCode.cs:5 */
        const val CAN_SAVE: Int = 65536

        /** SystemStateCode.cs:6 */
        const val CAN_BEGIN: Int = 131072

        /** 反查（存档/回放恢复状态时用）；未知数值返回 null。 */
        fun ofCode(code: Int): SystemStateCode? = entries.firstOrNull { it.code == code }
    }
}
