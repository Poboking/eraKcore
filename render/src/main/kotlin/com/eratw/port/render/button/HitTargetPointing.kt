package com.eratw.port.render.button

import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.render.HitTarget

/**
 * 命中目标 → `MOUSEB` 的指向态。
 *
 * 原版 `ConsoleButtonString` 的两套构造：
 * - 整型按钮：`Inputs = input.ToString()`（`ConsoleButtonString.cs:80-95`，`:85`）；
 * - 字符串按钮：值直接进 `Inputs`（`:97-110`）；
 * - 两者都 `IsButton = true`，非按钮文本段才置 false（`:68-78`）。
 *
 * 本层命中表只收真实按钮，所以这里恒 `isButton = true`；
 * `MouseState.buttonValue()` 的整型/字符串分支见 `core/host/MouseState.kt:151-156`。
 */
fun HitTarget.toPointing(): ConsoleButtonPointing =
    if (value is Long) {
        ConsoleButtonPointing(
            isButton = true,
            isInteger = true,
            input = value,
            inputs = value.toString(),
        )
    } else {
        ConsoleButtonPointing(
            isButton = true,
            inputs = value.toString(),
        )
    }
