package com.eratw.port.render.html

/**
 * mini-HTML 解析在原版里全部走 `CodeEE` 抛出（打印语句直接失败），本 lane 不依赖
 * `:core-vm`，用同语义的 [MiniHtmlException] 复刻；每个 kind 对应原版一个抛点。
 *
 * 官方行为里的「看着像 bug」的分支（如 `#` 前缀缺失时裸十六进制串也报错、
 * `&nbsp;` 还原成半角空格）一律照抄（ADR-005），不做「顺手修好」。
 * 行号主体指向
 * `D:/DSH/eratw-安卓移植计划/reference/emuera-cs/MinorShift.Emuera.UI.Game/HtmlManager.cs`（下面统一写
 * `HtmlManager.cs`）；属性数值解析走同一个 `Utils.ParseMixedNum`，另注
 * `MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs`（统一写 `Utils.cs`），
 * 那条同时是「同名属性重复」在数值属性上的真正抛点（`Utils.cs:361`）。
 */
enum class MiniHtmlErrorKind {
    /** `</p>` 闭合之后仍有尾部文本（HtmlManager.cs:560-563，Lang.TextAfterP）。 */
    TEXT_AFTER_P,

    /** `</nobr>` 闭合之后仍有尾部文本（HtmlManager.cs:564-568，Lang.TextAfterNobr）。 */
    TEXT_AFTER_NOBR,

    /** `<!--` 开始但找不到 `-->`（HtmlManager.cs:577-586，Lang.NotFoundCloseTag）。 */
    COMMENT_UNTERMINATED,

    /**
     * 标签体找不到终止 `>`。全文件只有 `HtmlManager.cs:597-599` 一个 `NotFoundTerminateTag`
     * 抛点；闭合标签 `</` 找不到 `>` 时 `:957-961` 自己不抛，只把游标推到串尾并返回 null，
     * 由同一个 `:597-599` 接手（`Current` 已不是 `>`）。
     */
    TAG_UNTERMINATED,

    /**
     * 串结束时样式/字体栈未清空（HtmlManager.cs:671-674，Lang.TagIsNotClosed）。
     * 同一个 Lang 在原版还有两个抛点，本层子集都到不了：`:614-616`（div 子分区收尾前的
     * 同款检查）与 `:667-670`（`div` 没闭合）—— 都依赖 `div`，见 [UNSUPPORTED_TAG]。
     */
    TAG_NOT_CLOSED,

    /**
     * 闭合标签与当前状态不匹配：未开先闭、重复闭合、样式位不在栈上
     *  （HtmlManager.cs:978-981、:985-990、:992-996、:999-1003，Lang.UnexpectedCloseTag）。
     * 同族的 button/nonbutton/clearbutton/div 守卫在 :1008、:1016、:1024、:1032，
     * 本层这些标签整体走 [UNSUPPORTED_TAG]，不会走到闭合校验。
     */
    UNEXPECTED_CLOSE_TAG,

    /** 样式标签重复开启、`nobr`/`p` 重复（HtmlManager.cs:1075-1078、:1097-1100、:1113-1116，Lang.DuplicateTag）。 */
    DUPLICATE_TAG,

    /** `b/i/s/u/br/nobr` 携带属性（HtmlManager.cs:1071-1074、:1082-1085、:1089-1092，Lang.AttributeSetToTag）。 */
    ATTRIBUTE_ON_STYLE_TAG,

    /** `nobr`/`p` 不在串首（首个文本段之后即失效；HtmlManager.cs:1093-1096、:1109-1112，Lang.TagIsNotBegin）。 */
    TAG_NOT_AT_LINE_HEAD,

    /** `p` 缺 `align` 属性（HtmlManager.cs:1105-1108，Lang.TagHasNotAttribute）。 */
    P_WITHOUT_ATTRIBUTE,

    /** `font` 缺属性（HtmlManager.cs:1603-1606，Lang.TagHasNotAttribute）。 */
    FONT_WITHOUT_ATTRIBUTE,

    /** `img` 缺属性（HtmlManager.cs:1150-1153，Lang.TagHasNotAttribute）。 */
    IMG_WITHOUT_ATTRIBUTE,

    /** `p` 的属性名不是 `align`（HtmlManager.cs:1125-1128，Lang.CanNotInterpretPAttribute）。 */
    UNKNOWN_P_ATTRIBUTE,

    /** `align` 值不属于 left/center/right（HtmlManager.cs:1140-1142，Lang.CanNotInterpretAttribute）。 */
    UNKNOWN_ALIGN_VALUE,

    /** 未知闭合标签（HtmlManager.cs:1036-1037 default，Lang.CanNotInterpretCloseTag）。 */
    UNKNOWN_CLOSE_TAG,

    /** `img` 缺 `src`（HtmlManager.cs:1217-1220，Lang.NotSetAttribute）。 */
    IMG_SRC_MISSING,

    /** 同名属性出现两次（HtmlManager.cs:1177-1198、:1626-1647；Utils.cs:361，Lang.DuplicateAttribute）。 */
    DUPLICATE_ATTRIBUTE,

    /** 属性名不在该标签的支持表内（HtmlManager.cs:1209-1212、:1646-1648，Lang.CanNotInterpretAttributeName）。 */
    UNKNOWN_ATTRIBUTE,

    /** 数值属性解析失败（Utils.cs:342-359，Lang.AttributeCanNotInterpretNum）。 */
    ATTRIBUTE_NOT_NUMBER,

    /** 颜色串为空（HtmlManager.cs:1682-1685，Lang.RequireColorCode）。 */
    COLOR_REQUIRED,

    /** 死代码类别：原版该抛点位于裸 catch 块内、实际被 :1698-1701 转吞为
     *  COLOR_UNPARSABLE（HtmlManager.cs:1693-1701）。本实现保留枚举值以对齐行号，
     *  但任何输入都不会抛它。 */
    COLOR_OUT_OF_RANGE,

    /** `#` 后不是合法十六进制、或越界后被裸 catch 转吞（HtmlManager.cs:1698-1701，
     *  Lang.CanNotInterpretNumValue）。 */
    COLOR_UNPARSABLE,

    /** 原版明确拒绝 `transparent`（HtmlManager.cs:1706-1711，Lang.TransparentUnsupported）。 */
    COLOR_TRANSPARENT_UNSUPPORTED,

    /** 未知颜色名且本身也不是十六进制串（HtmlManager.cs:1712-1719，Lang.InvalidColorName）。 */
    UNKNOWN_COLOR_NAME,

    /** 官方怪癖照抄：不带 `#` 的裸十六进制串能被解析、却仍被拒绝（HtmlManager.cs:1712-1720，Lang.InvalidColorName2）。 */
    COLOR_NAME_REJECTED,

    /** 标签词法/结构整体失败或未知开标签（HtmlManager.cs:1677，Lang.HtmlTagError）。 */
    TAG_ERROR,

    /** 本 lane 子集之外的已知标签（button/shape/div/clearbutton 系；原版可解析，见各自行号）。 */
    UNSUPPORTED_TAG,

    /** 实体引用缺 `;`（HtmlManager.cs:769-775，Lang.MissingSemicolon）。 */
    ENTITY_MISSING_SEMICOLON,

    /** `&` 后紧跟 `;`（HtmlManager.cs:770-777，Lang.ContinuouslyAndSemicolon）。 */
    ENTITY_AMP_SEMI,

    /** 非内置实体且不是数字引用，或数字解析失败（HtmlManager.cs:801-806、:818-826，Lang.InvalidCharacterReference）。 */
    ENTITY_INVALID_REF,

    /** 数字引用越界 0..65535（HtmlManager.cs:827-830，Lang.OoRUnicodeHtml）。 */
    ENTITY_OUT_OF_RANGE,
}

/**
 * mini-HTML 解析失败。携带原版同位的错误类别与出错串（截断到 120 字符便于日志）。
 *
 * @property kind 与原版 `CodeEE` 一一对应的错误类别
 * @property detail 出错上下文（通常是整段 html）
 */
class MiniHtmlException(
    val kind: MiniHtmlErrorKind,
    val detail: String = "",
) : RuntimeException("${kind.name} @ ${detail.take(120)}")
