package com.eratw.port.core.proc

import com.eratw.port.core.host.LineBufferConsoleSink

/**
 * 键入回显（ECHO-INPUT）的引擎档位 —— [GameSession] 的装配期配置项。
 *
 * 原版 `doInputToEmueraProgram` 在每次取值输入交付末尾打一行
 * `Print(str) + PrintFlush(force:false)`（`EmueraConsole.cs:1280-1282`）。那行走普通打印
 * 路径：非临时、无按钮、计入 LINECOUNT（`EmueraConsole.cs:509`），也是脚本 `CLEARLINE 1`
 * 与引擎级 `deleteLine(1)/(2)`
 * （`Process.cs:2194/:2430/:2498/:2518/:2624/:2629/:2648`）的删除对象。玩家在
 * 「选择口上剧本」页点一颗按钮后屏上最新一行冒出的数字就是它
 * （`ERB/fromEN/HTML_TALENTS/KOJO_DESC.ERB:19-42` 的 `ASK_CHOICES_TITLE`，有效项直接
 * `RETURN RESULT` 不清行）。
 *
 * 两档的差别只在「这行是否落进显示行表」：[REPLICA] 逐字复刻；[SUPPRESSED] 不落显示行，
 * 改由 [LineBufferConsoleSink] 的回显行账本把它计进 LINECOUNT，并在随后的 `deleteLine`
 * 折算掉 —— 于是脚本按原版行数写的 `CLEARLINE` 不会误删真菜单行，而屏上既没有那行数字、
 * 冻结段前后也没有帧差。
 *
 * 缺省 [REPLICA] 是刻意分层的：core 装置、E2E golden 与全部既有定向用例都吃原版口径；
 * 玩家侧由壳层偏好映射，缺省落在 [SUPPRESSED]。
 */
enum class EchoInputMode {

    /** 原版复刻：交付的生效文本照常打成一行普通显示行。 */
    REPLICA,

    /** 不复刻回显：不落显示行，只在引擎行账本里留一笔待折算的逻辑行。 */
    SUPPRESSED,
}
