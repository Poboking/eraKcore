package com.eratw.port.core.xml

/**
 * XML 文档池（对应原版 `VariableData.DataXmlDocument`，
 * spec: `MinorShift.Emuera.GameData.Variable/VariableData.cs:2926`
 * `public Dictionary<string, XmlDocument> DataXmlDocument { get; set; } = new ...`）。
 *
 * 池子按**每 VM 一份**挂在 `c.store.aux.slot("xml")` 上（契约 §2），不许用 object 单例。
 *
 * 键语义（spec: FunctionMethodCreator.cs:1111-1113 / :161-172）：
 * 第一个参数是整数时键 = `值.ToString()`（十进制，无前导零），是字符串时键 = 字符串本身。
 * 字典是**区分大小写**的普通 `Dictionary<string, XmlDocument>`（原版没有传比较器），
 * 所以 `"GDOC"` 与 `"gdoc"` 是两个文档。
 *
 * 存档关联（需组长接线，见交付报告）：`GameBase.csv` 的 `SAVE_XMLS` / `GLOBAL_XMLS` 列出的
 * 键会被写进存档（VariableData.cs:3915-3955 遍历 `ConstantData.SaveXmls` / `GlobalSaveXmls`
 * 后 `ContainsKey` 判定），序列化形态是文档的 `OuterXml` 字符串
 * （EraBinaryDataReader.ReadXml → `LoadXml(reader.ReadString())`）。本池提供
 * [contains] / [get] / [put] 供存档层按名读写。
 */
class XmlPool {
    // LinkedHashMap：保留创建顺序，给未来的枚举/调试一个确定性顺序（原版 Dictionary 无序，
    // 但本家族没有枚举 API，顺序不可观测）。
    private val docs = LinkedHashMap<String, XmlDocument>()

    /** 文档数（测试/调试用）。 */
    val size: Int get() = docs.size

    operator fun get(key: String): XmlDocument? = docs[key]

    fun contains(key: String): Boolean = docs.containsKey(key)

    /** 放入/覆盖（`XML_REPLACE` 的整文档替换路径用它）。 */
    fun put(key: String, doc: XmlDocument) {
        docs[key] = doc
    }

    /** `XML_DOCUMENT` 的 Create：已存在返回 false 且不覆盖（spec: :1114-1129）。 */
    fun create(key: String, doc: XmlDocument): Boolean {
        if (docs.containsKey(key)) return false
        docs[key] = doc
        return true
    }

    /** `XML_RELEASE`：不存在返回 false（spec: :1131-1142）。 */
    fun release(key: String): Boolean = docs.remove(key) != null

    /** 清空（RESTART / 读档重建时用）。 */
    fun clear() = docs.clear()
}
