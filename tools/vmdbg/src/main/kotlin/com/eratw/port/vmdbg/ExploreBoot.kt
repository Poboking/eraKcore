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
 *
 * ## 第 15 步的口径更正（obs-bootrecipe，2026-11-01 桌面取证）
 *
 * FLOW.md §2 那三行括注（「保留/全部覆盖/不变更」「用旧的/用新的/托人情」「检测到60个角色…」）
 * 是**人工转述**，不是屏上原文。2026-11-01 用 boot-steps.jsonl 逐步量到第 15 面的原文后逐条对上：
 * - 第 15 面确实存在（屏上走到那一面了），但屏上三个选项是 `[0]-保留 / [1]-変更 / [2]-不変更`，
 *   面头一行 `-----素質技能の変更-----`；**「全部覆盖」四字在整份控制台转写里零出现**，
 *   也在整个语料树里零命中（`ERB/` 全量 grep 只有一句「积雪将周围全部覆盖…」的无关正文）
 *   ⇒ 判为甲类：期望串口径错，不是流程回归、不是产物过期。出处
 *   `口上・メッセージ関連/個人口上/031 Sanae [早苗]/早苗/M_KOJO_K31_UPDATE.ERB:129`。
 * - 第 16、17 步的转述串（「用新的」「托人情」「可选立绘」里的前两个）同样零命中；第 17 面的出处
 *   存在（`fromEN/NEW_UPDATE/UPDATE_MAIN.ERB:988` 打「检测到N个角色有新的可选立绘…」）但按
 *   `Img_Detected` 是否为正才开火，headless 沙箱未必满足 ⇒ 两步一律留空（只推进不判定），
 *   拿到本次运行的真实屏文后再钉回（TODO(verify)）。
 * - 另一处装置侧根因（期望门抢在任意键宽容之前开火）记在
 *   [ExploreDriver.bootNextActionLocked] 的 KDoc：FLOW.md §2 明写第 15 步之前有
 *   「(Enter×N) ← 开局链：经验/能力换算、初始处理、各角色口上UPDATE（几十个等待点）」，
 *   配方不可能逐个列出这几个 `N`，只有「任意键面不经食谱」这条既定口径生效，配方才走得过去。
 */
data class BootStep(val input: String, val expect: String)

/**
 * 期望子串的命中判式（纯函数，[ExploreDriver] 的引导门与本仓单测共用同一个口）：
 * - 期望留空 = 该步不判定，只推进（[乙] 类分支面的容错口径：本局不出现这一面的步就留空）；
 * - 否则屏尾**任一行**含该子串才算命中——逐行 `contains`，不跨行拼串：
 *   原版一行一语句，跨行的文案在控制台里是两条行对象，拼串会把「按钮标签被换行打断」
 *   这类排版差异算成命中（假绿）；
 * - 比对前不做 trim / 全半角归一：期望串取的是屏上原文，归一了就等于「任何屏都命中」，
 *   判式失去可判性。
 */
fun bootExpectHit(expect: String, consoleTail: List<String>): Boolean =
    expect.isEmpty() || consoleTail.any { it.contains(expect) }

/**
 * 「推进步」判式（纯函数；[ExploreDriver] 的 bootActionFor 分支与引导门的 anykey 宽容共用这一个口）：
 * 输入列留空、或写 `Enter`（大小写随意）= 「按任意键往下走」，不带取值语义。
 * 之所以要抽出来：任意键等待点该不该消费这一步，取决于这一步是不是推进步——
 * 推进步消费（配方作者点名要在这里按一下），取值步不消费（见 [ExploreDriver.bootNextActionLocked]）。
 */
fun isEnterInput(input: String): Boolean =
    input.isBlank() || input.trim().equals("Enter", ignoreCase = true)

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
            // 素质・技能变更确认面 → [1]-変更（2026-11-01 屏上取证，见本类 KDoc「第 15 步的口径更正」）：
            // 面头一行是「-----素質技能の変更-----」，三个选项逐字是
            //   [0]-保留(现在不変更、这个選択肢会在版本変更的时候再登場)
            //   [1]-変更(这个選択肢不会再登場了)
            //   [2]-不変更(这个選択肢不会再登場了)
            // 期望串取面头「素質技能の変更」：这一行不含角色名（下一行「开始変更早苗的素質技能」含），
            // 逐字符一致、只随语料版本变，比任何一行正文都稳。
            BootStep("1", "素質技能の変更"),
            // 口上版本选择「用旧的/用新的/托人情」→ [1]
            // ★期望留空：FLOW.md §2 的这三条括注是**转述**不是屏文，全语料 grep「用新的」「托人情」
            // 零命中（唯一近似项是两句无关正文），屏上真实文案未取证 ⇒ 按 [乙] 类口径只推进不判定，
            // 取证到稳定屏文后再钉回来（TODO(verify) 见类 KDoc）。
            BootStep("1", ""),
            // 「检测到N个角色有新的可选立绘…是否缄默进行此次立绘更新」[0]是
            // ★同上留空：出处在 `fromEN/NEW_UPDATE/UPDATE_MAIN.ERB:988`，但该面按
            // `Img_Detected` 是否为正才开火，headless 沙箱未必满足 ⇒ 判定它会变成「这一局根本不出现」
            // 的假红，只推进不判定。
            BootStep("0", ""),
            // 「检测到N个角色有新的可选立绘…是否缄默进行此次立绘更新」[0] 是（跳过更新）
            // 2026-11-01 取证：这一面在内置链耗尽之后才现身（boot-steps.jsonl 里它是第 18 面，
            // 栈顶 ASK_YN:4675，屏上逐字「检测到60个角色有新的可选立绘」），所以 FLOW.md §2 把它
            // 记在第 17 位是**位次错**、不是文案错 ⇒ 补成第 18 步并钉真串；补这一步之后 headless
            // 引导链首次走到终点「第1日」并落 seed.dbg（锚点态 Shop_CallShowShop）。
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
