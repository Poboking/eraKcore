package com.eratw.port.vmdbg

import java.nio.file.Files
import java.nio.file.Path

/**
 * 引导链步骤（CONTRACT-DBG6 §4.E1.4）：一条「输入 ;; 期望子串」的顺序表。
 *
 * 执行口径（驱动器侧，见 [ExploreDriver.bootNextActionLocked]）：第 k 步在**第 k 个等待点**
 * 先查屏尾是否含期望子串（空串 = 不查），再喂入该步输入；喂入后引擎推进到第 k+1 个等待点，
 * 以此类推。不中即引导失败（现场落盘 boot-fail.json + boot-fail-console.txt，不静默跳步）。
 * 任意键等待点不经食谱（自动 PassAnyKey 放行，对应 FLOW.md §2 里的 `(Enter×N)` 段）。
 *
 * ## 与 FLOW.md 的对照
 *
 * 步骤内容照抄 `$ERATW_ROOT/.tmp/emu-play/FLOW.md` §2 的 START 开局实测链（2026-10-25
 * 物理驱动取证）；FLOW.md §0 的四条机制是链能跑通的先决：
 * 1. ASK_YN 族 [0]=是、[1]=否（答反静默走否分支）——第 3/7/15 步喂 0 的依据；
 * 2. TINPUT 超时自动推进（标题菜单 20s 不答 = 进 BADAPPLE DEMO）——本驱动立即应答，
 *    不吃真实时限，但**超时是隐藏分支**（FLOW.md §5.2），策略层枚举时须计入；
 * 3. PRINTFORMW 链大量纯推进等待点——anykey 自动放行覆盖；
 * 4. anykey 后数字题的输入框残留——真实键盘才有，引擎级注入无此现象。
 * 地图第 9 步选 [3] 经典 = FLOW.md §0.2 的避坑指令（绕开动画地图的 5s 超时重绘循环）。
 *
 * 期望子串取自 FLOW.md §2 逐屏引注的原文；FLOW.md 未引屏文的步骤留空（不检查）——
 * 钉子只钉有出处的屏文，不脑补文案。
 */
data class BootStep(val input: String, val expect: String)

/** 引导链配方：步骤表 + 终点判定子串（终点到达即打种子快照）。 */
class BootRecipe(val steps: List<BootStep>, val terminal: String)

object ExploreBoot {

    /**
     * 链终点判定子串（CONTRACT-DBG6 §4.E1.4 钉死）：第 1 日主菜单的日期行。
     * 终点到达即打种子快照——开局链几十个等待点重走一次约数分钟（FLOW.md §5.4），
     * 必须快照播种，不许每集重走。
     */
    const val TERMINAL_DAY1 = "第1日"

    /**
     * 内置 START 开局链（FLOW.md §2 全通实证链，到第 1 日主菜单为止）。
     * 输入列与 FLOW.md 的数字串逐行对应；期望列 = 该屏应见的子串（§2 引注原文）。
     */
    val BUILTIN_START: BootRecipe = BootRecipe(
        listOf(
            // FLOW.md §2：装载完成任意键（§2 未引屏文，不检查）
            BootStep("Enter", ""),
            // 标题菜单 [0] 开始游戏（FLOW.md §4：[0] 开始游戏）
            // 无「确定要开始新游戏吗」确认步：headless 沙箱恒无存档，TITLE.ERB 的
            // CHKDATA 分支直接 RESETDATA+BEGIN FIRST（真机有档路径用 --boot 覆盖）。
            BootStep("0", "开始游戏"),
            // 「检测到已存在的存档，确定要开始新游戏吗？」ASK_YN [0]那当然（FLOW.md §0.1：[0]=是）
            // ★★请选择游戏模式★★ [0]START
            BootStep("0", "请选择游戏模式"),
            // 「在游戏开始前你可以自定义你的角色。」（PRINTW 系任意键）
            BootStep("Enter", "自定义你的角色"),
            // 角色生成面板 [9999] 设定完毕（[200]/[300]/[4000] 可微调，直接定稿）
            BootStep("9999", "设定完毕"),
            // 「你这样真的可以吗？」[0]是
            BootStep("0", "这样真的可以吗"),
            // 角色剧本选择界面 [1100] 退出（选剧本可留默认）
            BootStep("1100", "1100"),
            // 「请选择地图的种类」[3] 经典（FLOW.md §0.2：选3避动画地图坑）
            BootStep("3", "请选择地图的种类"),
            // 主地图住所 [1999]=决定（默认博丽神社）
            BootStep("1999", "1999"),
            BootStep("Enter", "设定完毕"),
            BootStep("Enter", ""),
            // 居所细选 [0] (53)仓库（默认认定；FLOW.md 未引稳定屏文，不检查）
            BootStep("0", ""),
            BootStep("Enter", "私室"),
            // 最终自定义菜单 [100] 完成自定义阶段并开始游戏（[0]-[20] 可选改，跳过）
            BootStep("100", "完成自定义阶段"),
            // 口上技能变更「保留/全部覆盖/不变更」→ [1] 全部覆盖
            BootStep("1", "全部覆盖"),
            // 口上版本选择「用旧的/用新的/托人情」→ [1] 用新的
            BootStep("1", "用新的"),
            // 「检测到60个角色有新的可选立绘…跳过更新」[0]是
            BootStep("0", "可选立绘"),
            // 之后 FLOW.md §2 的 (Enter×N) 余下链由 anykey 自动放行，直至「第1日」现身
        ),
        TERMINAL_DAY1,
    )

    /**
     * `--boot <recipe文件>` 读取：每行 `输入 ;; 期望子串`（`;;` 缺省 = 该步不查期望）；
     * `#` 开头为注释行，空白行跳过。终点判定子串恒为 [TERMINAL_DAY1]（合同钉死，不在文件里配）。
     */
    fun parseFile(path: Path): BootRecipe {
        if (!Files.isRegularFile(path)) throw CliError("--boot 配方文件不存在: $path")
        return parseLines(Files.readAllLines(path, Charsets.UTF_8), path.toString())
    }

    /** 配方文本解析（[parseFile] 与单测共用；[source] 仅用于报错定位）。 */
    fun parseLines(lines: List<String>, source: String = "recipe"): BootRecipe {
        val steps = ArrayList<BootStep>()
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(";;")
            val input: String
            val expect: String
            if (sep < 0) {
                input = line
                expect = ""
            } else {
                input = line.substring(0, sep).trim()
                expect = line.substring(sep + 2).trim()
            }
            if (input.isEmpty()) throw CliError("$source 第 ${idx + 1} 行输入为空: $raw")
            steps.add(BootStep(input, expect))
        }
        if (steps.isEmpty()) throw CliError("$source 没有任何配方步骤（每行形如「输入 ;; 期望子串」，# 注释）")
        return BootRecipe(steps, TERMINAL_DAY1)
    }
}
