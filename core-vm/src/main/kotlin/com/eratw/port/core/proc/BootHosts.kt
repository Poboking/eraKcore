package com.eratw.port.core.proc

import com.eratw.port.core.config.ConfigChainLoad
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.config.ConfigDebugLoad
import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigLayerPresence
import com.eratw.port.core.config.ConfigReplaceLoad
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.DirectoryProbeFileOps
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveDataCheck
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.host.BinaryFileOps
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.save.SaveDataProbe
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 以固定根目录为基准的 [FileOps]（原版 `Program.ExeDir` 口径）。
 *
 * 为什么需要：`LOADTEXT` / `ENUMFILES` / `EXISTFILE` / `GCREATEFROMFILE` 在 core 层
 * 全部经 `BuiltinHost.fileOps`；未接线时函数直接抛 CodeEE（`GraphicsMethods.kt:90`、
 * `HostMethods.kt:174` 等）。BOOT-2 的头号阻塞点 `RM_TITLE` 正落在资源管理脚本里，
 * 该脚本一旦越过常量解析就会走到 `GCREATEFROMFILE`，因此这一层必须同时就位。
 *
 * 规则：
 * - 相对路径一律 `root.resolve(path)`；绝对路径也必须落在 [root] 内（防脚本越狱）；
 * - `\` 与 `/` 都接受（core 的 `getValidPath` 会把 `/` 换成 `\`，见 `HostMethods.kt:264-267`）；
 * - 列表返回**相对 root、`/` 分隔**的路径，与 `ENUMFILES` 的返回值口径一致
 *   （`FunctionMethodCreator.cs:364-372`）。
 * - 文本读写的编码按原版两处口径：读 = `EncodingHandler.DetectEncoding` 的三段回退
 *   （`EncodingHandler.cs:15-41`），写 = UTF-8（原版 `Config.SaveEncode = UTF8BOMEncoding`
 *   的**前导符由调用方以正文前的 U+FEFF 表达**，见 [writeText]）；
 * - 目录判定走 [DirectoryProbeFileOps]（`Directory.Exists`，`FunctionMethodCreator.cs:355`），
 *   与 [exists]（`File.Exists`，:1918）分开报告。
 *
 * TODO(verify)：原版 `Directory.EnumerateFiles` 的返回序依赖文件系统；这里 `sorted()`
 * 以保证可复现。若资源加载序影响结果，需要以 PC 实机顺序为准。
 */
class RootedFileOps(root: Path) : DirectoryProbeFileOps, BinaryFileOps {

    private val base: Path = root.toAbsolutePath().normalize()

    override fun exists(path: String): Boolean = resolvePath(base, path)?.let { Files.exists(it) } ?: false

    /**
     * `Directory.Exists(path)`（`FunctionMethodCreator.cs:355`）。**与 [exists] 分开**：
     * 原版两个判据对目录/文件各认一边，合成一个布尔会让 `EXISTFILE("sav")` 与
     * `ENUMFILES("plain.txt")` 同时错（F3 / F4）。
     */
    override fun isDirectory(path: String): Boolean =
        resolvePath(base, path)?.let { Files.isDirectory(it) } ?: false

    /**
     * 读文本 —— 原版 `File.ReadAllText(path, EncodingHandler.DetectEncoding(path))`（:8082）。
     *
     * 解码按 `EncodingHandler.cs:15-41` 的三段回退：UTF-8 BOM →（StreamReader 嗅探的）
     * UTF-16/UTF-32 BOM → 严格 UTF-8 → **CP932**（`shiftjisEncoding = GetEncoding(932)`，
     * :11）。BOM 由解码步消费，不留在返回串里。解不出来返回 null（原版 `LoadTextMethod`
     * 把 `ReadAllText` 整个包在 try/catch 里给 ""，:8084-8087）。
     */
    override fun readText(path: String): String? {
        val f = resolvePath(base, path) ?: return null
        if (!Files.isRegularFile(f)) return null
        val bytes = runCatching { Files.readAllBytes(f) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return ""
        return decodeWithDetectedEncoding(bytes)
    }

    /**
     * 写文本 —— 原版 `File.WriteAllText(path, text, Config.SaveEncode)`（:8010）。
     *
     * 编码分工（与 `OUTPUTLOG` 同一条约定，见 `DebugMiscMethods.kt:275-276`）：
     * 宿主只管 UTF-8 落字节；`Config.SaveEncode = UTF8BOMEncoding`（`Config.cs:19`）要求的
     * 编码前导符由**调用方**在正文前放 U+FEFF 表达（该码元按 UTF-8 正好编码成 EF BB BF）。
     * 这样「带 BOM / 不带 BOM」是各调用点自己的口径，宿主不替所有调用方决定。
     */
    override fun writeText(path: String, text: String, append: Boolean): Boolean = runCatching {
        val f = resolvePath(base, path) ?: return false
        Files.createDirectories(f.parent)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (append) {
            Files.write(f, bytes, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
        } else {
            Files.write(f, bytes)
        }
        true
    }.getOrDefault(false)

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> {
        val d = resolvePath(base, dir) ?: return emptyList()
        if (!Files.isDirectory(d)) return emptyList()
        val regex = globToRegex(pattern)
        val stream = if (recursive) Files.walk(d) else Files.list(d)
        return stream.use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { base.relativize(it).toString().replace('\\', '/') }
                .filter { regex.matches(it.substringAfterLast('/')) }
                .sorted()
                .collect(java.util.stream.Collectors.toList())
        }
    }

    /**
     * 覆盖写二进制（GSAVE 写真 PNG 的通道，[BinaryFileOps]）。
     *
     * 逐条口径与原版 GSAVE 对齐（spec: `FunctionMethodCreator.cs:8124-8125`）：
     * - 原版先 `Config.CreateSavDir()` 再 `Bitmap.Save` ⇒ 父目录由本方法创建
     *   （[Files.createDirectories] 对已存在的目录是幂等空操作，与 CreateSavDir 的
     *   `Directory.CreateDirectory` 同形；原版仅在 `UseSaveFolder` 时真正铺目录
     *   ——`Config.cs:545-548`，false 时 SavDir = ExeDir 本就在，两分支结局一致）；
     * - `Bitmap.Save` 是覆盖语义 ⇒ 这里 `Files.write`（默认 CREATE + TRUNCATE +
     *   WRITE）不带 APPEND；
     * - Save 抛异常被上层 catch 折成 GSAVE 返回 0（:8127-8130）⇒ 本方法把 IO 失败
     *   收成 false，与 [writeText] 的失败口径一致，不把异常抛回函数层；
     * - 出根路径（`resolvePath` 给 null —— normalize 后必须仍落在 [base] 内才有效）
     *   一律 false，与读路径同一边界，不逃逸。
     */
    override fun writeBytes(path: String, bytes: ByteArray): Boolean = runCatching {
        val f = resolvePath(base, path) ?: return false
        Files.createDirectories(f.parent)
        Files.write(f, bytes)
        true
    }.getOrDefault(false)

    /**
     * Windows 通配 → 正则：`*` = 任意串、`?` = 单个字符（其余字符字面量）。
     *
     * 对应原版 `Directory.EnumerateFiles(validPath, searchPattern, searchOption)`
     * （`FunctionMethodCreator.cs:364`，ENUMFILES 的 pattern）对 **Windows 通配** 的解释。
     * 上一版只处理 `*`、把 `?` 当字面量 ⇒ 含 `?` 的 pattern 行为偏离（`?` 是合法的单字符
     * 通配）。只对**文件名**（[list] 里 `substringAfterLast('/')` 的字段）匹配，故 `*`
     * 用 `.*` 不跨目录即正确。语料实测 pattern 只有 `*`（缺省）与 `*.xml`
     * （`リソース管理/ResourceManager.ERB:46`）⇒ 本修当前零输出差，仅纠正通配语义。
     */
    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}

/**
 * 按名字取字符集，取不到给 null。
 *
 * 为什么不用 `Charset.forName` 直接初始化：这些是**平台相关**名字。`windows-31J` 与
 * `UTF-32*` 在桌面 JDK 上恒在（见 `LangCodec` 的实测表），但 Android 的字符集注册表
 * 由 ICU 提供，缺名时 `forName` 抛 `UnsupportedCharsetException`——写在 `val` 初始化里
 * 会让 `RootedFileOps` 类初始化直接失败（连 UTF-8 的读都做不了）。这里降级为「该编码不可用」，
 * 由解码器按 LOADTEXT 的失败口径返回 null。
 */
private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

/** 原版 `shiftjisEncoding = GetEncoding(932)`（`EncodingHandler.cs:11`）在 JDK 侧的名字。 */
private val CP932: Charset? = charsetOrNull("windows-31J")

/** UTF-32 两种字节序 —— `StandardCharsets` 没有这两个常量（JDK 只给 UTF-8/16 族）。 */
private val UTF32_LE: Charset? = charsetOrNull("UTF-32LE")
private val UTF32_BE: Charset? = charsetOrNull("UTF-32BE")

/**
 * `EncodingHandler.DetectEncoding(string)`（:15-41）的移植：先看 BOM，再看严格 UTF-8，
 * 最后回落 CP932。
 *
 * 逐段口径：
 * 1. 前三字节等于 `EF BB BF` ⇒ `UTF8BOMEncoding`（:23-26）。该编码是
 *    `throwOnInvalidBytes: true`（:13）⇒ **严格**解码，坏字节返回 null（原版抛）。
 * 2. 不带 UTF-8 BOM 时，`new StreamReader(path, UTF8Encoding)`（:27）的
 *    `detectEncodingFromByteOrderMarks` 默认为 true ⇒ 嗅探 UTF-16/UTF-32 前导符；
 *    `CurrentEncoding` 变了就返回它（:29-32）。这几支是 `Encoding.Unicode` / 无抛异常编码，
 *    坏字节按替换字符处理，故**宽松**解码。
 * 3. `ReadToEnd()` 通过（:33）⇒ 整文件是合法 UTF-8 ⇒ 用 UTF8Encoding（严格，:35）。
 * 4. 任一步抛异常 ⇒ `shiftjisEncoding`（:37-40）；CP932 在 .NET 侧是
 *    `DecoderFallback.ExceptionFallback`（:70）⇒ 这里同样严格，坏字节 → null。
 *
 * 平台缺字符集（[charsetOrNull] 给 null）时按「这一支不可用」处理并继续往下走，
 * 最坏落回 null = LOADTEXT 的 ""（与原版 catch 同形）。
 */
private fun decodeWithDetectedEncoding(bytes: ByteArray): String? {
    val n = bytes.size
    fun at(i: Int): Int = bytes[i].toInt() and 0xFF
    if (n >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
        return runCatching { strictDecode(bytes, 3, StandardCharsets.UTF_8) }.getOrNull()
    }
    // StreamReader 的嗅探顺序：UTF-32LE / UTF-16LE / UTF-32BE / UTF-16BE
    if (n >= 2 && at(0) == 0xFF && at(1) == 0xFE) {
        if (n >= 4 && at(2) == 0 && at(3) == 0) {
            val cs = UTF32_LE ?: return null
            return lenientDecode(bytes, 4, cs)
        }
        return lenientDecode(bytes, 2, StandardCharsets.UTF_16LE)
    }
    if (n >= 2 && at(0) == 0xFE && at(1) == 0xFF) {
        if (n >= 4 && at(2) == 0 && at(3) == 0) {
            val cs = UTF32_BE ?: return null
            return lenientDecode(bytes, 4, cs)
        }
        return lenientDecode(bytes, 2, StandardCharsets.UTF_16BE)
    }
    runCatching { strictDecode(bytes, 0, StandardCharsets.UTF_8) }.getOrNull()?.let { return it }
    val sjis = CP932 ?: return null
    return runCatching { strictDecode(bytes, 0, sjis) }.getOrNull()
}

/** 严格解码（对应 .NET 的 `DecoderFallback.ExceptionFallback`）：坏字节抛异常。 */
private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String =
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
        .toString()

/** 宽松解码（对应 `Encoding.Unicode` 一类的缺省替换回退）：坏字节变 U+FFFD。 */
private fun lenientDecode(bytes: ByteArray, offset: Int, charset: Charset): String =
    String(bytes, offset, bytes.size - offset, charset)

/**
 * headless 开局的最小 [BuiltinHost]：文件 IO + 时钟 + 存档槽探测 + 音频目录 + **三层配置链**。
 *
 * 只补「没有它就连第一屏都过不去」的能力，其余一律走接口默认值：
 * - [checkSaveData]：`save{NN}.sav`（根目录或 `sav/`，取决于 `UseSaveFolder`）不存在 →
 *   `FILENOTFOUND` + `"----"`（原版 `VariableEvaluator.cs:2162-2166`）；文件存在时返回
 *   null = 「本宿主只判定存在性，头三分支交给函数层」——`CHKDATA` 会用
 *   `core/save/SaveDataProbe.check` 带着 registry.gameBase 补全探测
 *   （`VariableEvaluator.cs:2196-2217`），不伪造描述文本；
 * - [clockMillis]：可注入，默认 `System.currentTimeMillis()`，对应 GETTIME 家族
 *   （`BuiltinHost.clockMillis`）；
 * - [readFileBytes]：二进制读，`SAVECHARA`/`LOADCHARA` 的通道；
 * - [configOverrides] / [configChain] / [configReplace] / [applyChainTo]：开局一次读盘的
 *   三层配置链加 `_Replace.csv`（见下面几个成员各自的逐条口径）。
 *
 * 其余项（字体表、控制台行、输入、音频后端）**故意留空**：BOOT-2 不猜。
 *
 * @param analysisMode 原版 `-a` / 带文件参数的分析模式（spec: `Program.cs:87-91`）；
 *   true 时 `MAXLOG` 被改写成 10000（spec: `ConfigData.cs:569-572`）。默认 false = 正常开局。
 * @param debugMode 原版 `-debug` 开关（spec: `Program.cs:81`）；只有它为 true 才读
 *   `debug.config`（spec: `Program.cs:142-144` 的 `if (DebugMode)` 守卫）。默认 false。
 * @param glyphMaskHost 字形掩码宿主（M11-E 遗留项②透出；契约 §4 钉死签名的可选能力）。
 *   null（缺省）= 未接线，`GDRAWTEXT` 的消费点按「无 provider」回落推进盒现状
 *   （`fn/GraphicsMethods.kt` 的 `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost`
 *   同 `ops as? BinaryFileOps` 判定，不因此改 `Builtins.kt`）；非 null = 经
 *   [GlyphMaskHostProvider.glyphMaskHost] 透出给函数层。
 *   **位次说明**：插在 [clock] 之前而非末尾 —— 既有调用方（`boot/ConsoleBuiltinHostTest.kt:122`
 *   的 `RootedBuiltinHost(root) { … }`）靠**尾随 lambda 绑定最后一个参数**注入时钟，
 *   新参若排在末尾会抢走该槽位（lambda 类型对不上 `GlyphMaskHost?` 直接编译失败）。
 *   clock 保持末位后该用法原样可编译，本参一律按命名实参传入。
 */
class RootedBuiltinHost(
    root: Path,
    private val analysisMode: Boolean = false,
    private val debugMode: Boolean = false,
    glyphMaskHost: GlyphMaskHost? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BuiltinHost, ConfigChainSource, GlyphMaskHostProvider {

    /** 字形掩码宿主透出（见构造参数 KDoc）；透出即注入实例本身，不复制不改写。 */
    override val glyphMaskHost: GlyphMaskHost? = glyphMaskHost

    private val base: Path = root.toAbsolutePath().normalize()

    override val fileOps: FileOps = RootedFileOps(base)

    /**
     * 开局一次读盘的**三层链**（原版 `ConfigData.LoadConfig`，spec: `ConfigData.cs:479-510`；
     * 调用点 `Program.cs:95`）：
     *
     * ```text
     * CsvDir/_default.config（缺失则 default.config）  fix = false   :481-486, :491
     * ExeDir/emuera.config                              fix = false   :33, :492
     * CsvDir/_fixed.config（缺失则 fixed.config）      fix = true    :487-490, :493
     * ```
     *
     * 这里的 [ConfigChainLoad.store] 是**宿主侧的条目快照**（`useSaveFolder` 与各层读数从它取；
     * [configReplace] 在位时同一份快照也带 Replace 值，见下），
     * 每 VM 的正式条目表由 [applyChainTo] 把同一批字节重放出去 —— 原版只有一个进程级
     * `ConfigData.Instance`（spec: `ConfigData.cs:21`、`:29-36`），移植侧按铁律 4 不能照搬。
     *
     * 语料实测（画蛇添足版 4.980，只读数不改）：三层**全在位** ——
     * `CSV/_default.config` 17 行、`emuera.config` 97 行、`CSV/_fixed.config` 8 行；
     * default 的 17 个键与 fixed 的 8 个键都被 `emuera.config` 的 96 个键覆盖且值一致，
     * 所以**链合并后的值读数与单层完全相同**，差别只有 `fixed` 层的钉位
     * （判据与逐条清单见 `ConfigChainWireTest` 的语料用例与交付报告）。
     */
    override val configChain: ConfigChainLoad =
        EmueraConfigParser.loadChainFromDisk(base, ConfigStore(), analysisMode = analysisMode)

    /**
     * `-debug` 时才有的 `debug.config` 层（spec: `Program.cs:142-144`、
     * `ConfigData.cs:667-710`；路径 `Program.DebugDir + "debug.config"`，
     * spec: `ConfigData.cs:34` + `Program.cs:213-215`）。
     *
     * 它是 `DEBUG` 组七条（spec: `ConfigData.cs:152-158`）的**唯一**写入口：主链只查
     * `GetConfigItem`（spec: `:539`），查不到就 `:541` 静默 continue，所以三层文件里
     * 写「デバッグウィンドウ位置X:777」也改不动那一条；反向也一样，本层的键只比
     * `Name`/`Text`（spec: `:330-348`），英文标签形永远落空。两侧都是原版分区＋原版缺陷，
     * 原样保留（ADR-005）。
     *
     * 语料实测：**没有** `debug/` 目录（根下只有 `DEBUG_MODE.bat` 与
     * `Debug Mode (拖曳启动).bat` 两支 `-debug` 启动脚本），故真语料上恒为
     * [ConfigLayerPresence.ABSENT]，`DEBUG` 七条保持注册默认。
     */
    override val configDebug: ConfigDebugLoad? =
        if (debugMode) EmueraConfigParser.loadDebugFromDisk(base, ConfigStore()) else null

    /**
     * `_Replace.csv` 层（spec: `Process.cs:240-270` → `ConfigData.cs:594-639`）——
     * `REPLACE` 组 16 条（`ConfigData.cs:159-174`）的唯一写入口。
     *
     * 守卫照抄原版 `if (Config.UseReplaceFile && !Program.AnalysisMode && File.Exists(...))`
     * （spec: `Process.cs:240`）：
     *   * `UseReplaceFile` 取**链跑完之后**的条目值（注册默认 true，spec: `ConfigData.cs:47`；
     *     语料 `emuera.config:3` 是 YES）—— 原版 `Config.UseReplaceFile` 由 `Config.SetConfig`
     *     在 `LoadConfig` 末尾写好，`Process.Initialize` 才读它（spec: `ConfigData.cs:494`、
     *     `Process.cs:240`），顺序一致；
     *   * [analysisMode] 为 true 时不读（同一行的 `!Program.AnalysisMode`）。
     * 文件不存在仍返回一个 [ConfigLayerPresence.ABSENT] 的读数对象（与 [configDebug] 同形，
     * 便于把「开关关着」和「文件缺」分开断言）。
     *
     * 解析结果**就地写进 [configChain] 的 store**：原版只有一张进程级条目表，
     * `replaceArray` 与 `configArray` 在同一实例上（spec: `ConfigData.cs:23-27`）；
     * 这里让宿主侧的条目快照先带上 Replace 值，[configOverrides] 与 [applyChainTo]
     * 再分别从同一份记录摊平 / 重放，消费者不会看到两套读数。
     *
     * @see EmueraConfigParser.loadReplaceFromDisk
     */
    val configReplace: ConfigReplaceLoad? = loadReplaceFor(base, configChain, analysisMode)

    /**
     * 宿主级配置覆盖表 = **三层链 + `_Replace.csv` 合并后的值快照**（层序即优先级，
     * `default` → `emuera` → `fixed` → `_Replace.csv`，后层覆盖前层；链的部分见
     * `ConfigData.cs:491-493`，Replace 在原版里晚于 `LoadConfig` 且在 `SetReplace` 前
     * 才写进条目表，spec: `Process.cs:240-270`）。
     * 键取条目标签的**大写英文形**（`AConfigItem.EngText` 在注册期就被 `ToUpperInvariant`，
     * spec: `AConfigItem.cs:22-23`），值取文件里写着的原文（`Trim` 后）。消费侧全部同源：
     * `LangCodec.fromConfig`（码页，`core/expr/FormStringTerm.kt:461`）、
     * Compati 开关族（`core/fn/CompatiOptions.kt:47`）、
     * `LOADTEXT`/`SAVETEXT` 扩展名白名单（`core/fn/HostMethods.kt:329`）、
     * `BARSTR` 填充字符（`core/fn/HostMethods.kt:368` 的 `barChars`）、
     * 存档二进制开关（`core/proc/SystemVariableBootstrap.kt`）、控制台字体与日志行数
     * （`core/proc/GameSession.kt`），以及没实现 [ConfigChainSource] 的宿主下
     * `GETCONFIG` 族的降级播种（`core/fn/ConfigMethods.kt` 的 storeFromHost）。
     *
     * 为什么 Replace 也进这张表：原版这些消费者读的都是 `Config.SetConfig` / `SetReplace`
     * 之后的静态字段（spec: `ConfigData.cs:494` → `Config.cs:296` 起、`:719-741`），
     * 而 `SetReplace` 吃的就是 `replaceArray` 跑完 `LoadReplaceFile` 的条目表；只把 Replace
     * 接进 `GETCONFIG` 而让 `barChars` / 白名单继续读出厂值，就是同一份配置两套读数。
     *
     * 表的形状丢了两件事，逐条登记在交付报告：
     *   * `fixed` 层的**钉位**（`Map` 表达不了 `Fixed`，spec: `ConfigData.cs:493`、`:573-576`）
     *     —— 所以钉位只走 [applyChainTo] 那条通道；
     *   * `EditorArgument` 的**未 Trim 原文**（spec: `ConfigData.cs:566`）。
     *
     * 失败口径（本波合同，与切换前逐项相同）：**任一层**行级解析中止 ⇒ `emptyMap()`，不抛；
     * 三层都不存在同样是 `emptyMap()`。原版是「每层独立 try/catch，抛错的那层保住已解析前缀、
     * 其余层照旧装载」（spec: `ConfigData.cs:579-586`，catch 包在 while 外），本层按合同取更保守的
     * 整表空 —— 半张表会让 `DEFAULT ANSI ENCODING` 一类关键项静默丢值。
     * REPLACE 层的中止不触发这条整表空守卫：原版 `LoadReplaceFile` 的 catch 同样保住前缀
     * （spec: `ConfigData.cs:625-635`），且它写不到 GAME 组任何条目。
     * TODO(verify)：若后续要对齐原版前缀语义，只改 [mergedOverrideMap] 里的中止守卫；
     * [applyChainTo] 那条通道已经是原版口径（逐层独立中止，互不牵连）。
     */
    override val configOverrides: Map<String, String> = mergedOverrideMap(configChain, configReplace)

    /**
     * 存档目录开关的**交出面**：`UseSaveFolder` 条目现值（注册默认 false，spec: `ConfigData.cs:97`；
     * 语料 `emuera.config:51` 与 `CSV/_default.config:17` 都是 YES）。
     *
     * 原版拿它决定 `SavDir`（spec: `Config.cs:147` 字段、`:367` 取值、`:471-481` 拼目录），
     * 存档路径全部拼在 `SavDir` 上（spec: `VariableEvaluator.cs:2018-2037`）。
     * M4-L l-savefolder 收口后本类的 [checkSaveData] **已经**把它喂进
     * `SaveDataProbe.candidatePaths`（true ⇒ 仅 `sav/`、false ⇒ 仅根目录，
     * `core/save/SaveDataProbe.kt:97-142`），不再走「装配缺失才双候选」的回落。
     */
    val useSaveFolder: Boolean get() = ConfigMethods.useSaveFolder(configChain.store)

    /**
     * `MONEYSTR` 的货币标签 / 位置（`BuiltinHost.moneyLabel` / `moneyLabelFirst`）——
     * 原版是 `Config.MoneyLabel` / `Config.MoneyFirst`（spec: `Config.cs:199`、`:201`），
     * 由 `SetReplace` 从 `replaceArray` 写入（spec: `Config.cs:719-741`），读取点
     * `MoneyStrMethod`（spec: `FunctionMethodCreator.cs:3873-3921`）与 `MONEYLABEL` 伪变量
     * （spec: `VariableData.cs:962-971`）。
     *
     * 读 [configChain] 的条目快照（[configReplace] 在位时已带 `_Replace.csv` 的值），与
     * `GETCONFIG` 的条目表同源；不落回接口默认 `$` / true，否则同一局里 `MONEYSTR(123)`
     * 会印 `$123` 而 `GETCONFIGS("お金の単位")` 给出 `￥` —— 又变成两套读数。
     */
    override val moneyLabel: String get() = configChain.store.itemOf(ConfigKey.MoneyLabel).string()

    override val moneyLabelFirst: Boolean get() = configChain.store.itemOf(ConfigKey.MoneyFirst).bool()

    override fun clockMillis(): Long = clock()

    /**
     * 把开局读到的三层链、调试模式在位时的 `debug.config` 层与 `_Replace.csv` 层重放进
     * 调用方的条目表。
     *
     * 重放而不是重新读盘：两条通道（这张覆盖表与函数层的条目表）因此必然是同一批字节，
     * 层序与 `fix` 开关也照抄 `ConfigData.cs:491-493` 的三次调用。`debug` 层在链之后叠
     * （spec: `Program.cs:95` 的 `LoadConfig` 在前、`:144` 的 `LoadDebugConfig` 在后）；
     * Replace 层在最后（原版 `Process.Initialize` 才 `LoadReplaceFile`，晚于两条配置装载，
     * spec: `Process.cs:240-270`）。
     *
     * **不重复叠加**：Replace 的逐行赋值走 `ConfigItem.tryParse`，`List<long>` 条目每次先清空
     * 再写（spec: `ConfigItem.cs:147-158`），同一批行重放多次结果一致；链层同理由
     * （每层都是「写值」而不是「追加」）。正常路径每个 VM 只调一次（见
     * `ConfigMethods.storeFromHost`：先新建 [ConfigStore] 再重放）。
     */
    override fun applyChainTo(store: ConfigStore): ConfigChainLoad {
        EmueraConfigParser.replayChainOn(configChain, store, analysisMode)
        configDebug?.let { EmueraConfigParser.replayDebugOn(it, store) }
        configReplace?.let { EmueraConfigParser.replayReplaceOn(it, store) }
        return configChain
    }

    override fun readFileBytes(path: String): ByteArray? {
        val f = resolvePath(base, path) ?: return null
        if (!Files.isRegularFile(f)) return null
        return runCatching { Files.readAllBytes(f) }.getOrNull()
    }

    override fun checkSaveData(no: Long, kind: SaveFileKind): SaveDataCheck? {
        // 槽位候选目录与存在性判定统一收在 SaveDataProbe.candidatePaths
        // （VariableEvaluator.cs:2018-2047 的 getSaveDataPath*）。组长接线（M4-L l-savefolder）：
        // 本宿主本来就装好了三层配置链，所以把 [useSaveFolder] 现值传进去 —— 原版 `SavDir`
        // 是**唯一**一个目录（Config.cs:471-477 的 true ⇒ ExeDir\sav\ / :478-481 的 false ⇒ ExeDir），
        // 另一侧的同名文件不算存在；官方靠 Config.SetConfig 时的 File.Move 迁移旧档
        // （Config.cs:482-485 + :551-590），不靠「两边都读一遍」。
        val found = SaveDataProbe.candidatePaths(this, kind, no, useSaveFolder).any { rel ->
            val p = resolvePath(base, rel)
            p != null && Files.isRegularFile(p)
        }
        if (!found) return SaveDataCheck(EraDataState.FILENOTFOUND, "----")
        // 命中真实存档：GAME_ERROR / VIRSION_ERROR / OK 三个分支依赖 registry.gameBase，
        // 宿主接口签名（BuiltinHost.checkSaveData）拿不到变量池，宿主层不猜、不伪造状态码。
        // 返回 null = 「宿主只判定存在性」，函数层（HostMethods 的 CHKDATA）用 SaveDataProbe.check
        // 带着真实 gameBase 补全头探测；只有完全无探测能力的宿主才落到「未接线」抛错。
        return null
    }

    override val soundDir: File get() = base.resolve("sound").toFile()
}

/**
 * 路径归一 + 越界拒绝：绝对路径必须仍在 [base] 内，否则返回 null（调用方按「不存在」处理）。
 */
private fun resolvePath(base: Path, path: String): Path? {
    val rel = path.replace('\\', '/')
    if (rel.isEmpty()) return null
    val p = runCatching { Path.of(rel) }.getOrNull() ?: return null
    val abs = if (p.isAbsolute) p.normalize() else base.resolve(p).normalize()
    return if (abs.startsWith(base)) abs else null
}

/**
 * `_Replace.csv` 的装载守卫（spec: `Process.cs:240` 的
 * `Config.UseReplaceFile && !Program.AnalysisMode && File.Exists(...)`）。
 *
 * [ConfigReplaceLoad] 只在**前两条**过的时候才建；文件是否存在交给
 * [EmueraConfigParser.loadReplaceFromDisk]（缺失返回 `ABSENT` 读数对象，与 `debug.config`
 * 同形）。`UseReplaceFile` 取链跑完后的条目值（注册默认 true，spec: `ConfigData.cs:47`）。
 */
private fun loadReplaceFor(base: Path, load: ConfigChainLoad, analysisMode: Boolean): ConfigReplaceLoad? =
    if (!analysisMode && load.store.itemOf(ConfigKey.UseReplaceFile).bool()) {
        EmueraConfigParser.loadReplaceFromDisk(base, load.store, load.csvDir)
    } else {
        null
    }

/**
 * 三层链 + Replace 层 → 宿主覆盖表，外加**本波失败合同**的守卫：任一层行级解析中止 ⇒ `emptyMap()`。
 *
 * 合并口径全部在 [EmueraConfigParser.toOverrideMap] 里（层序即优先级、只收 `changedValue` 行、
 * 键取大写英文标签、值取原文 Trim；Replace 层最后摊）；这里只保留宿主侧的保守策略。
 *
 * 中止守卫是**宿主面**的选择，不是原版口径：原版每层一个独立的 `try/catch`，抛错的那层保住
 * 已解析前缀、后面的层照旧装载（spec: `ConfigData.cs:579-586`，catch 包在整个 while 外，
 * 三次 `loadConfig` 调用 `:491-493` 彼此独立）。函数层的条目表走
 * [RootedBuiltinHost.applyChainTo]，那条通道已经是原版口径。
 * Replace 层的中止不计入这条守卫（它只写 REPLACE 组，原版同样保前缀，spec: `ConfigData.cs:625-635`）。
 * TODO(verify)：若要让覆盖表也按前缀语义，只删这里的 `abortedAtLine` 守卫。
 */
private fun mergedOverrideMap(load: ConfigChainLoad, replace: ConfigReplaceLoad? = null): Map<String, String> {
    if (load.results.any { it.abortedAtLine != null }) return emptyMap() // 本波合同：整表空，不抛
    return EmueraConfigParser.toOverrideMap(load, replace)
}

/**
 * 独立读一遍三层链 + `_Replace.csv` 并摊平成覆盖表 —— [RootedBuiltinHost.configOverrides] 的无宿主形态。
 *
 * 留着它的唯一理由：`GameBootSmoke`（开局装置）与既有报告口径按「给个目录就要一张表」调用它。
 * 内容与 [RootedBuiltinHost.configOverrides] 同源同口径（同一条链、同一条 Replace、同一个合并器、
 * 同一个中止守卫），差别是它不建宿主、也不带 `-a`/`-debug` 开关 —— 因此 `UseReplaceFile` 与
 * `File.Exists` 两条守卫照走，`analysisMode` 恒为 false。
 *
 * 解码口径与切单层时一致：UTF-8（语料三个文件都带 BOM，BOM 由 [ConfigFile.stripBom] 只在
 * 第 0 行剥）。TODO(verify)：原版无 BOM 时还有「UTF-8 嗅探 → Shift-JIS」两级回落
 * （spec: `EncodingHandler.cs:15-41`），那是 `core/loader` 的探测口径、不在本路文件面内。
 */
internal fun loadConfigOverrides(base: Path): Map<String, String> {
    val load = EmueraConfigParser.loadChainFromDisk(base, ConfigStore())
    return mergedOverrideMap(load, loadReplaceFor(base, load, analysisMode = false))
}
