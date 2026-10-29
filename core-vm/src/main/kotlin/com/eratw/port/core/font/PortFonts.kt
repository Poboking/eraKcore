package com.eratw.port.core.font

/**
 * 仓内字体表 —— CHKFONT 的唯一权威（策略与理由见 `ConsoleQueryMethods.registerConsoleQuery` 的
 * CHKFONT 注释）。
 *
 * ## 表里为什么只有这两条
 *
 * 每条都要能回答"移植版凭什么说这个字体画得出来"：
 *
 * - `黑体`：基准环境的 `Config.FontName`（spec: 语料 `emuera.config:21` FONT NAME:黑体；
 *   `fixtures/golden/baseline/pc-golden-root.txt:279-281` = `FONT=黑体` / `FONT_DEFAULT=黑体` /
 *   `CHKFONT=1`，脚本 `fixtures/golden/GOLDEN_02_PRINT.ERB:226-230`）。
 *   移植版不承诺设备上真装了黑体，而是**按请求名把绘制落到随包的度量表/子集**
 *   （等价原版 `Config.cs:501-523` 那条"配置字体不可用就整体替换"的链，只是替换发生在装配期）。
 *   所以"可用于绘制"= 1 与基准同口径。
 *   TODO(verify)：真机验收若要求"没装就报 0"，就得把这条改成由 §3 sidecar 的 `#family` 生成
 *   （本表随之变成派生数据）——需要 c-fontsub 的成品家族名与 slug 一起定，属组长接线。
 * - `Saitamaar`：语料 `eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf`（**公有领域**，可再分发；
 *   作者本人仓 keage/Saitamaar 的 Licence 节原文「Saitamaar is in the public domain.」，
 *   取证与字段实测见 `render/src/main/resources/fonts/LICENSE-Saitamaar.txt` 与
 *   `tools/fontsubset/name-table-audit.txt`——前一波把它错标成 OFL，f-license 路已定案纠正；
 *   name 表无 ID13/14、`OS/2.fsType=0` 与该声明无冲突，且无 Reserved Font Name ⇒ 子集不必改名）。
 *   本机 fontTools 4.67.0 读 name 表 nameID1 = `Saitamaar`（nameID2 = Normal）；原版会把
 *   font 目录下的 ttf 逐个 AddFontFile 进 `GlobalStatic.Pfc`（spec: Program.cs:129-141）并由
 *   CHKFONT 第二轮循环命中（spec: FunctionMethodCreator.cs:3499-3507）→ 1。
 *   移植版随包分发的正是这一族的子集（契约 §2 c-fontsub）。
 *
 * ## 故意不列的（与 PC 的有意分歧，不静默）
 *
 * - `msgothic` / `msmincho`（语料 font 目录里就有）：它们是 **.ttc**，而原版只加载 ttf 与 otf
 *   （spec: Program.cs:131,136）→ 连 PC 的 Pfc 都进不去；PC 答 1 只是因为 Windows
 *   系统装了它（`InstalledFontCollection`，spec: :3488-3497）。移植版不分发微软版权字体，
 *   本表不列 → 0；设备侧若要答 1 由宿主经 [com.eratw.port.core.fn.BuiltinHost.installedFonts] 追加。
 * - `BIZ UDGothic`：原版内嵌的回退字体（spec: Config.cs:509-522），PC 答 1；
 *   移植版的回退族是 `Saitamaar`，不分发 BIZ UDGothic → 0。
 * - `宋体` / `ＭＳ ゴシック` 等同理：前者是 msmincho 的中文对应、后者是 `ConfigData.cs:65`
 *   的**出厂默认字体名**（基准环境已被 `emuera.config:21` 覆盖成黑体），都没有仓内依据。
 *
 * 比较口径 = **逐名精确相等**（区分大小写），照抄 `families[i].Name == strValue`
 * （spec: FunctionMethodCreator.cs:3493、:3502）；不做大小写折叠、不做相近替代。
 * 这与官方自身在文本段合并层的忽略大小写不一致（spec: StringStyle.cs:59,73）是两回事，
 * 本波不抹平（同 `:render` glyph 包的分桶口径）。
 */
object PortFonts {

    /** 字体名 → 入库依据（顺序只为可读，查表不依赖迭代序）。 */
    private val bundledEvidence: Map<String, String> = linkedMapOf(
        "黑体" to "语料 emuera.config:21 + pc-golden-root.txt:279-281；绘制按请求名落到随包子集（Config.cs:501-523 同构）",
        "Saitamaar" to "语料 font/Saitamaar.ttf（公有领域，nameID1 实测；作者仓 keage/Saitamaar 的 Licence 节，无 RFN）；原版 Program.cs:129-141 → Pfc，CHKFONT 第二循环 :3499-3507",
    )

    /** 仓内表里的字体名（确定性只读集合）。 */
    val bundled: Set<String> get() = bundledEvidence.keys

    /** 某个名字的入库依据（表外返回 null）；装配层与验收用它核对"为什么答 1"。 */
    fun evidenceFor(name: String): String? = bundledEvidence[name]

    /**
     * 唯一查表规则：仓内表命中，或宿主按平台实测追加的名字命中（追加**只能加不能减**）。
     *
     * @param hostReported 宿主侧平台确认的字体名（[com.eratw.port.core.fn.BuiltinHost.installedFonts]，null = 宿主没有点查能力）
     */
    fun isAvailable(name: String, hostReported: Set<String>?): Boolean =
        bundled.contains(name) || (hostReported != null && hostReported.contains(name))

    /**
     * `GSETFONT` 之后 `GGETFONT` 该回读的名字 —— 原版回的是 **GDI+ 解析后的家族名**
     * （spec: FunctionMethodCreator.cs:6219 `return graphicsImage.Fontname`，
     * spec: GraphicsImage.cs:60 `Fontname => font.Name`），不是脚本传进来的请求名。
     *
     * 原版两条构造分支（spec: FunctionMethodCreator.cs:6385-6402）决定了这个名字：
     * - 请求名逐字精确命中 `GlobalStatic.Pfc.Families`（Pfc = `PrivateFontCollection`，
     *   spec: GlobalStatic.cs:38；装进去的是游戏 `font` 目录的 ttf/otf，spec: Program.cs:129-141，
     *   以及回退时解包的 BIZ UDGothic，spec: Config.cs:509-522）⇒ `new Font(FontFamily, ...)`
     *   （:6394），`Font.Name` = 命中的家族名 = **与请求名同串**；
     * - 一个都不命中 ⇒ `new Font(strValue, ...)`（:6400），`Font.Name` = GDI+ 在系统字体表里
     *   **解析出来的规范名**；系统里也没有时 GDI+ 不抛，静默换成兜底族
     *   （2026-10-21 本机 System.Drawing 实测：`NoSuchFamilyZZZ` / `Micro` / `""` / `Saitamaar` /
     *   `BIZ UDGothic` 全部回 `Microsoft Sans Serif`，而 `ms gothic` 回 `MS Gothic`、
     *   `SimHei` 回 `黑体`）。
     *
     * 移植侧的对应关系：[resolvable] = 移植侧认得这个名字（[isAvailable] 命中仓内表/宿主点查，
     * 或 `FontSidecarCatalog.hasOwnTable` 有它自己的度量表）⇒ 原样回读请求名；
     * 认不得时回 [com.eratw.port.core.font.FontSidecarCatalog.fallbackFamily]
     * （移植版真正拿去画的那个族，与原版「静默换族」同构）。
     *
     * TODO(verify)：两处与 PC 不同值，都是**平台字体表**差异而不是移植口径错，登记不修正：
     * ① 请求名解析不到时 PC 给 `Microsoft Sans Serif`（Windows 兜底族），移植版给自己的子集族名；
     * ② GDI+ 会把别名/大小写不敏感的请求折成**规范名**（实测 `SimHei` → `黑体`、
     *   `ms gothic` → `MS Gothic`），移植侧没有这张折名表，命中时只能原样回读。
     *   卡点：需要 PC 实机对拍（基准 `fixtures/golden/baseline/pc-golden-root.txt` 里
     *   GGETFONT 一条读数都没有，只有 `:273-281` 的 FONT/FONT_DEFAULT/CHKFONT），
     *   或宿主侧提供平台字体规范名清单 ⇒ 属组长接线。
     */
    fun resolveFontName(requested: String, resolvable: Boolean, fallbackFamily: String): String =
        if (resolvable) requested else fallbackFamily
}
