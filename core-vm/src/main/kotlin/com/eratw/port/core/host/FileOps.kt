package com.eratw.port.core.host

/**
 * 文本文件宿主能力（SAVETEXT/LOADTEXT/EXISTFILE/ENUMFILES 家族）。
 *
 * 路径由宿主负责映射到 ERB 工作目录——core 层不得假定工作目录，也不得直接触碰
 * `java.io` 之外的平台 API（CONTRACT-M3-A §5）。
 *
 * 语义依据（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）：
 * - SavetextMethod  → SAVETEXT
 * - LoadtextMethod  → LOADTEXT
 * - ExistfileMethod → EXISTFILE
 * - EnumfilesMethod → ENUMFILES
 * （函数层 KDoc 注明各自核对行号。）
 */
interface FileOps {
    fun exists(path: String): Boolean

    /** 读全部文本；不存在返回 null（原版 LOADTEXT 不存在时的错误路径由函数层决定）。 */
    fun readText(path: String): String?

    /**
     * 写文本；[append]=false 覆盖。返回 false = 失败（原版 SAVETEXT 返回 0）。
     *
     * 原版**没有追加写**：SAVETEXT 是 `File.WriteAllText(path, text, Config.SaveEncode)`
     * （FunctionMethodCreator.cs:8010，第 4 参求值后丢弃 :7963-7968），OUTPUTLOG 同样是
     * `File.WriteAllText`（EmueraConsole.cs:3449）。所以现有调用点全部传 `append=false`
     * （`fn/HostMethods.kt:218` 的 SAVETEXT、`fn/DebugMiscMethods.kt:284` 的 OUTPUTLOG、
     * `fn/GraphicsMethods.kt:470` 的 GSAVE）；[append] 只是宿主能力位，
     * 新增消费点前必须先在 C# 里找到对应语句。
     */
    fun writeText(path: String, text: String, append: Boolean): Boolean

    // ---- 编码 / 路径 / 关闭口径（2026-10-22 L09 续任复核；实现方与函数层都按这段对齐）----
    //
    // 编码（写）：原版是 `File.WriteAllText(path, text, Config.SaveEncode)`（FunctionMethodCreator.cs:8010），
    // 而 `Config.SaveEncode` 是**静态字段、初值即 `EncodingHandler.UTF8BOMEncoding`**（Config.cs:19，
    // `encoderShouldEmitUTF8Identifier: true`，EncodingHandler.cs:13），全仓只有三处引用
    // （声明 Config.cs:19 + SAVETEXT :8010 + `.era` 存档写出 EraDataWriter.cs:24），
    // **没有任何配置项能改写它** ⇒ SAVETEXT 恒为 UTF-8 *带 BOM*，与界面语言 / `Config.Encode` 无关；
    // OUTPUTLOG 另写死同一编码（EmueraConsole.cs:3449）。移植侧分工：宿主落 UTF-8 字节，
    // BOM 由**调用方**在正文前放 U+FEFF 表达（见 `proc/BootHosts.kt` 的 `RootedFileOps.writeText`）。
    //
    // 编码（读）：原版 `File.ReadAllText(path, EncodingHandler.DetectEncoding(path))`（:8082）的三段回退 =
    // UTF-8 BOM → StreamReader 嗅探到的 UTF-16/32 → 严格 UTF-8 → **CP932**
    // （EncodingHandler.cs:15-41，回退目标 `GetEncoding(932)` 在 :11/:39）。原版的四条失败出口
    // ——路径非法 :8065-8069、扩展名不在白名单 :8070-8074、文件不存在 :8076-8079、读/解码异常
    // :8084-8087—— **一律返回空串**，所以 [readText] 的 null 只表达「宿主读不到」，折成 "" 归函数层
    // （`fn/HostMethods.kt:254`），同一层还负责剥前导 BOM 与按 :8088 去掉所有 `\r`。
    //
    // 路径：`Utils.GetValidPath`（Utils.cs:473-488）先把 `/` 折成 `\`、剔掉 `..\`，再拒绝一切
    // **有根**的串（`Path.GetPathRoot(p) != ""` → null）⇒ 原版只吃相对**进程工作目录**的路径，
    // 不是 ERB 目录；移植侧根挂在哪由宿主决定（现有 `RootedFileOps.resolvePath`）。
    // 父目录创建只在路径里含反斜杠时才发生（SAVETEXT :8006-8008），数字索引分支走
    // CreateSavDir / ForceCreateSavDir（:7995-8005）。扩展名口径**写读不对称**：SAVETEXT 对白名单外
    // 的扩展名是补 `.txt`（:7987-7991 `Path.ChangeExtension`），LOADTEXT 是直接返回 ""（:8070-8074），
    // 别把两条并成同一个校验。
    //
    // 关闭：原版三条语句都是现开现闭（`File.WriteAllText` / `File.ReadAllText` /
    // `Directory.EnumerateFiles` :364），**没有跨调用的句柄，也没有追加状态**；本接口刻意不暴露流，
    // 宿主不得缓存 writer（`.era` 存档那个长生命周期 StreamWriter 归 `core/save` + EraDataWriter.cs:24）。

    /**
     * 目录枚举（pattern 为原版 `*` 通配）。
     *
     * [recursive] 对应原版 `SearchOption.AllDirectories`
     * （EnumFilesMethod 第 3 参，FunctionMethodCreator.cs:360）；false = TopDirectoryOnly。
     *
     * 返回值的**路径形状**由实现决定（原版给的是 `Directory.EnumerateFiles` 的绝对路径，
     * :364-373 原样拷进 RESULTS_ARRAY；`RootedFileOps` 给的是相对宿主根的 '/' 分隔路径
     * —— 这条差异登记在 M6-B HOST-AUDIT 回执，不在本接口内定夺）。
     */
    fun list(dir: String, pattern: String, recursive: Boolean = false): List<String>
}
