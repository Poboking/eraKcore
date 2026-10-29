package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.SoundResourcePaths
import com.eratw.port.core.save.SaveWiring
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 第三批：依赖宿主/注入资源的函数（RAND——ADR-001 RNG 契约、时间家族、少量配置读取），
 * 以及明确定义为 M3+ 的函数（图像/音频/网络/窗口类）——注册为报错占位。
 *
 * 存档槽家族（`CHKDATA` / `SAVENOS`）自 M6-SAVE-WIRE 起整体迁到 `SaveMethods.kt`；
 * 本文件不再注册任何存档函数。
 */
internal fun registerHostMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    // 注：本函数原先还有 `val S = OperandType.STR`，随 GETTIMES（唯一 STR 返回的注册）
    // 一起迁到 TimeMethods.kt 后不再使用，故删除（见下方时间段的迁移说明）。

    // ---- 随机 ----

    // spec: FunctionMethodCreator.cs:4027-4074 RandMethod。
    // ADR-001：必须走注入的 RandProvider（store.randProvider），不得自建 Random。
    r.register(BuiltinMethod("RAND", I, 1, 2) { c, a ->
        val rng = c.store.randProvider
            ?: throw CodeEE("RAND 需要注入 RandProvider（ADR-001）")
        val min = if (a.size == 2 && a[0] != null) iv(a[0]!!) else 0L
        val max = if (a.size == 2) iv(a[1]!!) else iv(a[0]!!)
        if (max <= min) {
            if (min == 0L) throw CodeEE("函数 RAND：最大值 $max 必须为正数")
            throw CodeEE("函数 RAND：最大值 $max 不得小于最小值 $min")
        }
        Value.IntV(rng.next(max - min) + min)
    })

    // ---- 存档槽探测 ----
    // CHKDATA(no) / SAVENOS() 自 M6-SAVE-WIRE 起由 `SaveMethods.kt` 的 registerSaveMethods 持有
    // 真相（Builtins.buildRegistry 末尾接线）。两条旧注册已删：注册点只有一处，避免
    // 「两个文件各注册一份、以调用顺序决定语义」的第二真相。迁移期的让路闸
    // （SaveMethods 的 claimable）仍保留，重复接线安全。

    // ---- 音频 ----

    // EXISTSOUND(name)：`.\sound\{name}` 是否存在。spec: :8202-8220 ExistSoundMethod
    //   - argumentTypeArray = string（:8207），ReturnType = long，CanRestructure = false（:8208）
    //   - `File.Exists(Path.GetFullPath(".\\sound\\" + strValue))`（:8214-8218）
    // 宿主未接线时以进程工作目录下的 `sound/` 为基准，等价于原版相对路径语义。
    // 判定与 PLAYSOUND/PLAYBGM 同走 [SoundResourcePaths.resolve]：Windows 的 `File.Exists`
    // 大小写不敏感，语料里 `bad-apple-audio.MP3` 对磁盘 `bad-apple-audio.mp3`，两条若各判各的
    // 就会出现「EXISTSOUND=0 但能播出声」的自相矛盾。
    r.register(BuiltinMethod("EXISTSOUND", I, 1, 1) { c, a ->
        val name = sv(a[0]!!)
        val root = c.host.soundDir ?: File("sound")
        Value.IntV(if (SoundResourcePaths.resolve(root, name) != null) 1L else 0L)
    })

    // ---- 音频 ----
    // 本区两条查询函数的**全部**可观测口径都由 [BuiltinHost.soundChannels] /
    // [BuiltinHost.isPlayingSound] / [BuiltinHost.isPlayingBgm] 决定，而这三项的缺省
    // （声道数 0、恒不在播）正是基准 SOUND-MISSING 行的来源。core/audio 的声道状态机
    // （M4-D d-audio）只是把这套口径钉成可单测的模型，本波的注册表与缺省行为一字未动。

    // ISPLAYINGSOUND(ch)：声道 ch 在播放就返回 ch，否则 -1；下标越界同样 -1。
    // spec: :8830-8875 IsPlayingSoundMethod
    //   - 参数表 = 1 槽 Int + `OmitStart = 0`（:8835-8842）⇒ 合法元数 **0..1**：
    //     `OmitStart` 是**最少实参个数**（`arguments.Count >= OmitStart`，FunctionMethod.cs:183），
    //     不是「恰好一个」。0 参在原版**解析期合法**，运行期 :8848 直接读 `arguments[0]` 才抛
    //     `ArgumentOutOfRangeException`（fixtures/GOLDEN_09_MISC.ERB:49-50 记载的就是这条官方 bug）；
    //     移植侧没有崩溃通道，求值体显式抛 CodeEE。CMP-70 裁决（C# 权威）把注册下界从 1 降回 0。
    //   - `num < 0 || num >= GlobalStatic.Sound.Length` → -1（:8849-8852）
    //   - `Sound[num] != null && Sound[num].isPlaying()` → 该下标，否则 -1（:8866-8870）；
    //     **不**惰性建道（对照 GETSOUNDORBGMINFO 的 :8799-8802），isPlaying() 见 Sound.cs:171-178
    //     ⇒ 暂停中的声道读不到。
    //   - :8853-8865 的「arguments[0] == null ⇒ 往后扫第一个在播的」分支在 :8848 先读 arguments[0]
    //     之后不可达，且自身带官方 bug（循环条件比 num、体内只 num2++ ⇒ 死循环后越界）；不移植。
    // 宿主无音频后端时 soundChannels = 0 ⇒ 恒 -1（= PC 基准 GOLDEN_09:50 的 ISPLAYINGSOUND=-1）。
    r.register(BuiltinMethod("ISPLAYINGSOUND", I, 0, 1) { c, a ->
        // :8848 —— `int num = (int)arguments[0].GetIntValue(exm)`：截断发生在越界判定**之前**，
        // 故 2^32 落成 0、-2^32 也落成 0。这里必须先 toInt 再比，不能拿 Long 原值比。
        val arg = a.getOrNull(0)
            ?: throw CodeEE("函数 ISPLAYINGSOUND：缺少第 1 个参数（原版 :8848 直接读 arguments[0]）")
        val ch = iv(arg).toInt()
        when {
            ch < 0 || ch >= c.host.soundChannels -> Value.IntV(-1L)
            c.host.isPlayingSound(ch) -> Value.IntV(ch.toLong())
            else -> Value.IntV(-1L)
        }
    })
    // ISPLAYINGBGM()：BGM 在播放 → 1，否则 0。spec: :8949-8966
    //   `GlobalStatic.Bgm != null && Bgm.isPlaying()`（:8960-8964）——同样不惰性建；
    //   Bgm 出厂即建（GlobalStatic.cs:44），所以 PC 侧「返回 0」的含义是「没在播」而不是「没有对象」。
    r.register(BuiltinMethod("ISPLAYINGBGM", I, 0, 0) { c, _ ->
        Value.IntV(if (c.host.isPlayingBgm()) 1L else 0L)
    })

    // ---- 热键 / 内存（宿主级副作用，返回码与原版一致）----

    // HOTKEY_STATE_INIT(n)：初始化热键状态表。spec: :8708-8731
    //   参数表 `{ Int }` + OmitStart = 1（:8713-8720）⇒ **恰好 1 个**参数：OmitStart 是必需的前导
    //   位数（`arguments.Count >= OmitStart` 才过闸，FunctionMethod.cs:183），0 个实参在解析期就报
    //   NotEnoughArgs（:283），原版函数体也直接读 `arguments[0]`（:8727）。恒返回 0（:8730）。
    //   原版副作用是 `Window.hotkeyState.HotkeyStateInit`（Win32 全局热键）——Android 无对应物，
    //   此处按 no-op 处理：返回值与原版一致，副作用缺失。
    r.register(BuiltinMethod("HOTKEY_STATE_INIT", I, 1, 1, omitStart = 1) { _, _ -> Value.IntV(0L) })

    // HOTKEY_STATE([k [, v]])：查询/设置热键状态。spec: :8679-8705
    //   参数表 `{ Int, Int }` + OmitStart = 1（:8684-8693）⇒ 1 或 2 个参数；恒返回 0（:8704）。
    //   同上：副作用（查/设 Win32 热键）在 Android 侧无对应物，返回值照抄 0。
    r.register(BuiltinMethod("HOTKEY_STATE", I, 1, 2) { _, _ -> Value.IntV(0L) })

    // CLEARMEMORY()：触发 GC，返回释放的字节数。spec: :8323-8340
    //   原版取 `Process.WorkingSet64` 在 `GC.Collect()` 前后的差（:8334-8338）。
    //   JVM 侧用已用堆（total - free）近似 WorkingSet；返回值本就依赖运行时状态、无固定期望，
    //   只要真的触发一次 GC 即语义等价。
    r.register(BuiltinMethod("CLEARMEMORY", I, 0, 0) { _, _ ->
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        Value.IntV(before - after)
    })

    // ---- 时间（宿主时钟 + 宿主时区注入）----

    // spec: FunctionMethodCreator.cs:3967-3982 GettimeMethod —— 逐字段读 `DateTime.Now`
    //   后拼 yyyymmddhhmmssmmm（:3978）。`DateTime.Now` 是 .NET 的**本地墙上时间**，
    //   移植侧的「本地」由宿主回答，与 TimeMethods 族同一条协议（Builtins.kt:37-48）：
    //   时刻取 [BuiltinHost.clockMillis]、时区取 [BuiltinHost.zoneId]。
    //   时区不许再从 `Calendar.getInstance()` 的进程默认区读（FN-09：两族口径不一致）。
    r.register(BuiltinMethod("GETTIME", I, 0, 0) { c, _ ->
        Value.IntV(timeFields(c.host.clockMillis(), c.host.zoneId()).let { t ->
            t[0] * 10000000000000L + t[1] * 100000000000L + t[2] * 1000000000L +
                t[3] * 10000000L + t[4] * 100000L + t[5] * 1000L + t[6]
        })
    })
    // GETTIMES / GETMILLISECOND / GETSECOND 自 M4-J j-dispatch 起由 `TimeMethods.kt` 的
    // registerTimeMethods 持有真相（注册收口见 Builtins.buildRegistry）。这里摘掉三条的
    // 原因是口径缺陷：原版 `GetmsMethod.GetIntValue` 是 `DateTime.Now.Ticks / 10000`
    // （FunctionMethodCreator.cs:4008），`DateTime.Now` 是**本地墙上时间**，而旧的
    // `clockMillis() + 62135596800000` 用的是 UTC epoch 毫秒、漏了本地时区的 UTC 偏移
    // ⇒ Asia/Shanghai 下比原版小 28 800 000（8 小时）。
    // GETTIMES 另有一处：它是**零参返回单个** `yyyy/MM/dd HH:mm:ss` 串
    // （:3987 `Array.Empty<Type>()`、:3991-3994），旧注释里的「九个元素」与反编译不符。
    // GETTIME（:3967-3982）仍留在本文件，但时区同样走宿主注入点 [BuiltinHost.zoneId]
    // （缺省 = ZoneId.systemDefault，Builtins.kt:48 ⇒ 未覆写的宿主行为不变）。

    // LINEISEMPTY()：打印缓冲是否为空。spec: FunctionMethodCreator.cs:5596-5610 LineIsEmptyMethod
    r.register(BuiltinMethod("LINEISEMPTY", I, 0, 0) { c, _ ->
        Value.IntV(if (c.host.lineIsEmpty) 1L else 0L)
    })
}

/**
 * [FileOps] 的**目录判定能力位** —— 原版 `Directory.Exists`（`FunctionMethodCreator.cs:355`）。
 *
 * 为什么不是 [FileOps] 的成员：`FileOps` 目前只有「存在」（[FileOps.exists]）一个布尔，
 * 原版却有一对互斥判据（`File.Exists` :1918 / `Directory.Exists` :355），两者合成一个
 * 必然让 F3（`EXISTFILE("sav")` 应为 0）与 F4（`ENUMFILES("plain.txt")` 应为 -1）同时错。
 * 把 `isDirectory` 加成独立能力位是**源码兼容**的做法：未实现本接口的宿主走 [isDirectoryOf]
 * 的「能否枚举出条目」兜底（空目录会误判，见该函数的 KDoc）。
 * 长期形态应是 `FileOps.isDirectory` 本身（本次未动 `core/host/FileOps.kt`，接线需求见交付报告）。
 */
interface DirectoryProbeFileOps : FileOps {
    /** 路径存在且是目录。spec: `Directory.Exists`（`FunctionMethodCreator.cs:355`）。 */
    fun isDirectory(path: String): Boolean
}

/**
 * 目录判定的取数口径（`Directory.Exists`，`FunctionMethodCreator.cs:355`）。
 *
 * 优先问宿主的能力位；宿主没实现 [DirectoryProbeFileOps] 时用「能不能枚举出条目」兜底 ——
 * 这是修前唯一可用的信号，且对 `RootedFileOps` 背后的只读装饰（`WriteBlockedFileOps`，
 * `ConsoleBuiltinHost.kt:81`）同样成立：它对普通文件返回空表（`RootedFileOps.list` 先
 * `isDirectory` 再 `Files.list`）。**空目录**在这条兜底路径上会误判，故落地宿主必须实现
 * 能力位（`RootedFileOps` 已实现；装饰器的接线需求见交付报告）。
 */
private fun isDirectoryOf(ops: FileOps, path: String): Boolean =
    (ops as? DirectoryProbeFileOps)?.isDirectory(path)
        ?: runCatching { ops.list(path, "*", false).isNotEmpty() }.getOrDefault(false)

/**
 * 第五批：文本文件函数族（SAVETEXT/LOADTEXT/EXISTFILE/ENUMFILES）。
 *
 * 全部 IO 经 [com.eratw.port.core.host.FileOps]（宿主负责把相对路径映射到 ERB 工作目录），
 * core 层不直接触碰文件系统（CONTRACT-M3-A §5）。
 *
 * 语义逐条对照 `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`：
 * - ExistFileMethod :1906-1924 → EXISTFILE（`File.Exists` 口径，目录 ⇒ 0）
 * - SaveTextMethod  :7936-8017 → SAVETEXT（`Config.SaveEncode` = UTF-8 带 BOM；BOM 由本函数的
 *   正文前 U+FEFF 表达，宿主只写 UTF-8，见就地注释）
 * - LoadTextMethod  :8020-8089 → LOADTEXT（`EncodingHandler.DetectEncoding` 三段回退在宿主）
 * - EnumFilesMethod :330-375   → ENUMFILES（`Directory.Exists` 口径，普通文件 ⇒ -1；第 4 参取
 *   整份目标数组、恒从下标 0 起写 `num` 个，见注册点的 FN-08 注释）
 * 辅助口径：Utils.GetValidPath（Utils.cs:473-488）、GetSaveDataPathText（:9466-9469）、
 * Config.ValidExtension 缺省 ["txt"]、可由宿主 [BuiltinHost.configOverrides] 的
 * `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT` 一行覆盖（ConfigData.cs:120-127；键匹配口径
 * 同 `GetConfigItem`，:262-284 —— 见 [validExtensions]）、
 * SavDir/ForceSavDir（Config.cs:470-481）：`SavDir` = `ExeDir`，`UseSaveFolder` 为真时
 * `ExeDir+"sav/"`；`ForceSavDir` 恒 `ExeDir+"sav/"`。序号寻址的文本档因此是
 * force=0 ⇒ [saveFolderPrefix]（`UseSaveFolder` 现值，语料 `emuera.config:51` = YES ⇒ `sav/`）、
 * force≠0 ⇒ 恒 `sav/`；不是「默认根目录」。
 */
internal fun registerFileMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // EXISTFILE(path)：路径非法（GetValidPath null）→ 0。spec: :1915-1923
    // :1918 `if (validPath != null && File.Exists(validPath)) return 1L` ——
    // `File.Exists` 对**目录**恒 false（dotnet 8.0.27 实测：File.Exists(dir)=False、
    // Directory.Exists(file)=False），故目录要先被 [isDirectoryOf] 认出来再扣掉。
    r.register(BuiltinMethod("EXISTFILE", I, 1, 1) { c, a ->
        val ops = c.host.fileOps ?: throw CodeEE("函数 EXISTFILE 需要宿主接线 FileOps")
        val p = getValidPath(sv(a[0]!!)) ?: return@BuiltinMethod Value.IntV(0L)
        val isDir = isDirectoryOf(ops, p)
        Value.IntV(if (ops.exists(p) && !isDir) 1L else 0L)
    })

    // SAVETEXT(text, pathOrIndex, [force], [_ignored])：写成功 1 / 失败 0。spec: :7958-8016
    // 第 4 参原版求值后丢弃（:7963-7968）；arg[1] 为整数时按存档序号寻址，目录取
    // `force ? ForceSavDir : SavDir`（:7978）——见 [saveDataTextPath]。
    r.register(BuiltinMethod("SAVETEXT", I, 2, 4) { c, a ->
        val ops = c.host.fileOps ?: throw CodeEE("函数 SAVETEXT 需要宿主接线 FileOps")
        val text = sv(a[0]!!)
        val force = optInt(a, 2, 0L) != 0L
        if (a.size > 3) iv(a[3]!!) // 原版求值即丢弃（:7963-7968）
        val path = when (val target = a[1]!!) {
            is Value.IntV -> {
                val n = target.v
                if (n < 0 || n > Int.MAX_VALUE) return@BuiltinMethod Value.IntV(0L) // :7973-7976
                saveDataTextPath(c, n.toInt(), force) // :7978
            }
            else -> {
                val p = getValidPath(sv(target)) ?: return@BuiltinMethod Value.IntV(0L) // :7982-7986
                ensureTxtExtension(p, validExtensions(c)) // :7987-7991
            }
        }
        val ok = try {
            // :8010 `File.WriteAllText(text, strValue, Config.SaveEncode)`；`Config.SaveEncode =
            // UTF8BOMEncoding`（Config.cs:19）⇒ 落盘字节 = EF BB BF + UTF-8 正文。
            // BOM 的落字节分工与 OUTPUTLOG（DebugMiscMethods.kt:275-276）同一条约定：
            // 宿主只写 UTF-8，调用方用正文前的 U+FEFF 表达「这个文件带 BOM」。
            ops.writeText(path, "\uFEFF$text", append = false)
        } catch (_: Exception) {
            false // :8012-8015
        }
        Value.IntV(if (ok) 1L else 0L)
    })

    // LOADTEXT(pathOrIndex, [force], [_ignored])：读不到/扩展名不在白名单 → ""。spec: :8041-8088
    r.register(BuiltinMethod("LOADTEXT", S, 1, 3) { c, a ->
        val ops = c.host.fileOps ?: throw CodeEE("函数 LOADTEXT 需要宿主接线 FileOps")
        val force = optInt(a, 1, 0L) != 0L
        if (a.size > 2) iv(a[2]!!) // 原版求值即丢弃（:8046-8051）
        val path = when (val target = a[0]!!) {
            is Value.IntV -> {
                val n = target.v
                if (n < 0 || n > Int.MAX_VALUE) return@BuiltinMethod Value.StrV("") // :8056-8059
                saveDataTextPath(c, n.toInt(), force) // :8061
            }
            else -> {
                val p = getValidPath(sv(target)) ?: return@BuiltinMethod Value.StrV("") // :8065-8069
                if (!hasValidExtension(p, validExtensions(c))) return@BuiltinMethod Value.StrV("") // :8070-8074
                p
            }
        }
        val text = ops.readText(path) ?: return@BuiltinMethod Value.StrV("") // :8076-8079（File.Exists）
        // :8082 `File.ReadAllText(path, EncodingHandler.DetectEncoding(path))`：解码三段回退
        // （BOM → UTF-16/32 BOM → 严格 UTF-8 → CP932，EncodingHandler.cs:15-41）在宿主
        // `RootedFileOps.readText`；解码失败 ⇒ 该重载抛 ⇒ 原版 :8084-8087 的 catch 给 ""。
        // 解出来的串里理论上不会有编码前导符（StreamReader 消费它），但内存宿主/旧宿主可能把
        // U+FEFF 当正文交上来，故仍按原版口径剥掉**唯一的前导** U+FEFF，随后做 :8088 的 \r 去除。
        Value.StrV(stripLeadingBom(text).replace("\r", "")) // :8082 / :8088
    })

    // ENUMFILES(dir, [pattern="*"], [recursive=0], [destStrArray=RESULTS])：返回写入个数，
    // 目录不存在/枚举异常 → -1。spec: :352-373。dest 为字符串一维数组引用（RefString1D），
    // 且**只取它指向的整份数组**（`:370` 的 `Identifier.GetArray()`）：第 4 参上写了显式下标
    // （`RESULTS:5`）也照样从目标数组下标 0 起写 `num` 个（`:372` 的 `Array.Copy(array, array2,
    // num)` 恒从 0 拷）—— 下标不是起点，详见下方写循环的就地注释（FN-08）。
    r.register(BuiltinMethod("ENUMFILES", I, 1, 4, evalRef = { c, e ->
        val ops = c.host.fileOps ?: throw CodeEE("函数 ENUMFILES 需要宿主接线 FileOps")
        val dir = getValidPath(e[0]?.getStrValue(c) ?: "") ?: return@BuiltinMethod Value.IntV(-1L) // :354-358
        // :355 `!Directory.Exists(validPath)` —— 目录判定与 EXISTFILE 的 `File.Exists` 互斥。
        val isDir = isDirectoryOf(ops, dir)
        if (!isDir) return@BuiltinMethod Value.IntV(-1L)
        val pattern = if (e.size > 1 && e[1] != null) e[1]!!.getStrValue(c) else "*" // :359
        val recursive = e.size > 2 && e[2] != null && e[2]!!.getIntValue(c) != 0L // :360
        val files = try {
            ops.list(dir, pattern, recursive) // :364
        } catch (_: Exception) {
            return@BuiltinMethod Value.IntV(-1L) // :366-368
        }
        val dest = e.getOrNull(3) as? VarRef
            ?: if (e.size > 3 && e[3] != null) {
                throw CodeEE("函数 ENUMFILES 的第 4 个参数必须是字符串一维数组变量引用") // :370 原版强制转换
            } else null
        // :370 `string[] array2 = arguments.Count != 4 ? exm.VEvaluator.RESULTS_ARRAY
        //   : ((arguments[3] as VariableTerm).Identifier.GetArray() as string[])` —— 取的是
        // **整份数组**，第 4 参上的显式下标既不缩小数组、也不参与寻址；连下标表达式都不求值
        // （原版只碰 `Identifier`，从不 `GetIntValue(arguments[3])`）。旧实现把下标当起始偏移
        // 拼成 `base + i`，对 1 维目标会凑出 2 个下标、在 store 的 argCount 校验处直接抛
        // 「需要 1 个下标（实际 2）」（FN-08）。
        val destName = dest?.name ?: "RESULTS" // :370（exm.VEvaluator.RESULTS_ARRAY）
        val len = c.store.getLength(destName, -1).toLong() // array2.Length（整份目标数组）
        val n = minOf(files.size.toLong(), len).toInt() // :371
        // :372 `Array.Copy(array, array2, num)` 恒从目标数组下标 0 起写 num 个；单元格
        // 下标容器复用一份一次性数组，避免逐文件新分配（core 层热路径不产垃圾）。
        val cell = LongArray(1)
        for (i in 0 until n) {
            cell[0] = i.toLong()
            c.store.writeStr(destName, cell, files[i])
        }
        Value.IntV(n.toLong()) // :373
    }))
}

// ---------------------------------------------------------------------------
// 文件函数族内部辅助（口径见 registerFileMethods KDoc）
// ---------------------------------------------------------------------------

/**
 * 剥掉文本**唯一的前导** BOM（U+FEFF），其余字符一律不动。
 *
 * 对应 `LOADTEXT` 的 `File.ReadAllText(path, EncodingHandler.DetectEncoding(path))`
 * （FunctionMethodCreator.cs:8082）：该重载的 StreamReader 只消费开头一个编码前导符，
 * `EncodingHandler.DetectEncoding`（EncodingHandler.cs:15-41）用 BOM 判定 UTF-8-BOM 编码，
 * 但 BOM 本身不留在解码后的字符串里。
 *
 * 只剥一个：双重前导 BOM 时第二个按正文内容保留（与 .NET 一致），正文中间的 U+FEFF 永不触碰；
 * 对已剥过的文本重复调用即原样返回（首字符非 U+FEFF 时无副作用），故与前导判定天然幂等。
 */
private fun stripLeadingBom(s: String): String =
    if (s.isNotEmpty() && s[0].code == 0xFEFF) s.substring(1) else s

/** 盘符根（`X:`）判定，对应 .NET `Path.GetPathRoot(p) != ""` 的 Windows 分支。 */
private val DRIVE_ROOT = Regex("^[A-Za-z]:")

/**
 * Utils.GetValidPath（Utils.cs:473-488）：`/`→`\`、剔除 `..\`、拒绝根路径（返回 null）。
 * 空串是合法路径（GetPathRoot("") == ""），原样返回。
 */
private fun getValidPath(path: String): String? {
    val p = path.replace('/', '\\').replace("..\\", "")
    return if (p.startsWith("\\") || DRIVE_ROOT.containsMatchIn(p)) null else p
}

/** Config.ValidExtension 缺省值：len 1 的 List<string> = ["txt"]（spec: ConfigData.cs:122-127）。 */
private val DEFAULT_VALID_EXTENSIONS = listOf("txt")

/**
 * 配置键 `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT` 能被命中的两种标签形（大写后）：
 * 英文 `EngText` 与日文 `Text`（spec: ConfigData.cs:120-127 的注册行 + AConfigItem.cs:22-23
 * 注册期 `ToUpperInvariant`）。原版 `GetConfigItem` 还比 `Name`（enum 名 `ValidExtension`，
 * 混合大小写 —— 大写化后的键恒命中不了，官方缺陷照抄），不再列。
 */
private val VALID_EXTENSION_KEYS = setOf(
    "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT",
    "LOADTEXTとSAVETEXTで使える拡張子",
)

/**
 * 扩展名白名单（LOADTEXT/SAVETEXT 共用）：`BuiltinHost.configOverrides` 里找
 * `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT` 一行（键 Trim 后大写化比较，同
 * `GetItem` 口径，spec: ConfigData.cs:262-284），值按 `,` 切、逐段 Trim
 * （spec: ConfigItem.cs:165-171 + :215-225 的 List<string> 分支 tryStringToStringList）。
 *
 * 比较口径（本波合同）：扩展名与表项都转小写再比 —— 原版只把小写化后的扩展名
 * `Contains` 原文表项（spec: FunctionMethodCreator.cs:8072-8074）；语料本行
 * `emuera.config:73` 全小写，两种口径无观测差。
 * 空值/键缺失/宿主未接线 ⇒ 缺省 ["txt"]（空串原版 TryParse 直接 false、保留既有值，
 * spec: ConfigItem.cs:89-95）。
 */
private fun validExtensions(c: BuiltinContext): List<String> {
    val raw = c.host.configOverrides.entries
        .firstOrNull { it.key.trim().uppercase() in VALID_EXTENSION_KEYS }
        ?.value
        ?: return DEFAULT_VALID_EXTENSIONS
    val text = raw.trim()
    if (text.isEmpty()) return DEFAULT_VALID_EXTENSIONS
    return text.split(',').map { it.trim().lowercase() }
}

/**
 * BARSTR 的两个填充字符（`Config.BarChar1` / `Config.BarChar2`，spec: ExpressionMediator.cs:155-156）。
 *
 * 出处：`Config.cs:730-731` 在加载配置时把两项读成 char；出厂值 `'*'` / `'.'`
 * （ConfigData.cs:164-165）；可被 `_Replace.csv` 覆盖（ConfigData.cs:236-340 的 replace 层）。
 * 键匹配同 [validExtensions]：键 Trim 后大写化，比 `Text` / `EngText` 两形；`Name`（enum 名
 * `BarChar1`，混合大小写）大写化后恒命中不了，原版缺陷照抄。
 *
 * 值走 `char.TryParse`（ConfigItem.cs:119-126）：Trim 后必须**恰好一个 UTF-16 码元**，
 * 否则 TryParse false ⇒ 保留当前（出厂）值（ConfigItem.cs:89-95）。
 */
internal fun barChars(c: BuiltinContext): Pair<Char, Char> =
    Pair(configChar(c, BAR_CHAR1_KEYS, '*'), configChar(c, BAR_CHAR2_KEYS, '.'))

private val BAR_CHAR1_KEYS = setOf("BAR文字1", "BAR CHARACTER 1")
private val BAR_CHAR2_KEYS = setOf("BAR文字2", "BAR CHARACTER 2")

/** [barChars] 的单键取值。 */
private fun configChar(c: BuiltinContext, keys: Set<String>, fallback: Char): Char {
    val raw = c.host.configOverrides.entries
        .firstOrNull { it.key.trim().uppercase() in keys }
        ?.value
        ?: return fallback
    val text = raw.trim()
    return if (text.length == 1) text[0] else fallback
}

/** 路径的扩展名（小写、不含点）；无点文件名（`Path.HasExtension == false`）→ ""。 */
private fun extOf(path: String): String {
    val name = path.substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    return if (dot < 0) "" else name.substring(dot + 1).lowercase()
}

/** SAVETEXT 的 Path.ChangeExtension(text, "txt")（:7987-7991）：白名单外（或无扩展名）换成 .txt。 */
private fun ensureTxtExtension(p: String, valid: List<String>): String {
    if (hasValidExtension(p, valid)) return p
    val sep = p.lastIndexOf('\\')
    val name = p.substring(sep + 1)
    val dot = name.lastIndexOf('.')
    val stem = if (dot >= 0) p.substring(0, sep + 1 + dot) else p
    return "$stem.txt"
}

/** LOADTEXT 的白名单校验（:8070-8074）：无扩展名（item = ""）或扩展名不在白名单 → false。 */
private fun hasValidExtension(p: String, valid: List<String>): Boolean = valid.contains(extOf(p))

/**
 * GetSaveDataPathText(index, dir)（FunctionMethodCreator.cs:9466-9469）= `{dir}txt{index:00}.txt`；
 * 目录实参是 `force ? Config.ForceSavDir : Config.SavDir`（:7978 / :8061）。
 *
 * `SavDir`（Config.cs:470-481）不是「恒根目录」：`UseSaveFolder` 为真时是 `ExeDir\sav\`
 * （语料 `emuera.config:51` = `USE SAV FOLDER:YES`），`ForceSavDir` 恒 `ExeDir\sav\`。
 * 现值优先取会话装好的 [SaveWiring]（`GameSession` 把生效配置装进 aux，与 `CHKDATA` 同源），
 * 未装配时回落到条目表现值 [ConfigMethods.useSaveFolder]。此处产出**相对**路径，
 * 由宿主映射到 ERB 工作目录（`RootedFileOps.writeText` 会建父目录，等价原版
 * `Config.CreateSavDir()` / `Directory.CreateDirectory`，:7996-8007）。
 */
private fun saveDataTextPath(c: BuiltinContext, index: Int, force: Boolean): String =
    (if (force) "sav/" else saveFolderPrefix(c)) + "txt%02d.txt".format(java.util.Locale.ROOT, index)

/** `Config.SavDir` 的相对前缀（`Config.cs:471-481`）：`UseSaveFolder` 真 ⇒ `sav/`，假 ⇒ 根目录。 */
private fun saveFolderPrefix(c: BuiltinContext): String {
    val useSaveFolder = SaveWiring.of(c.store)?.config?.useSaveFolder
        ?: ConfigMethods.useSaveFolder(c)
    return if (useSaveFolder) "sav/" else ""
}

/**
 * epoch 毫秒 → [年, 月(1-12), 日, 时, 分, 秒, 毫秒]，按 [zone] 取日历字段。
 *
 * 字段来源是原版 `GettimeMethod.GetIntValue` 里逐个读的 `DateTime.Now`
 * （spec: `FunctionMethodCreator.cs:3978` 的 `Year/Month/Day/Hour/Minute/Second/Millisecond`）；
 * `DateTime.Now` 是 .NET 的本地墙上时间，其「本地」在移植侧由宿主回答
 * （[BuiltinHost.zoneId]，Builtins.kt:37-48），与 `TimeMethods` 族同一口径
 * （TimeMethods.kt:119-142）—— 不再由 `Calendar.getInstance()` 偷读 JVM 进程默认时区。
 *
 * 用 java.time 而不是 `Calendar.getInstance()`：后者的缺省时区是 JVM 进程默认区，
 * 正是 FN-09 的缺陷来源；这里的折算与 `TimeMethods` 的纯折算函数同一条 JDK 口径
 * （TimeMethods.kt:190-211，同样吃注入的 [ZoneId]）。
 * 一次 `clockMillis()` 读一个时刻、只拆一次字段，保持既有的单快照口径。
 * TODO(verify): 原版 :3978 在一行里调用 `DateTime.Now` 七次（理论上可跨秒/毫秒边界，
 * 卡点：是否要按 ADR-005 复刻该竞态 —— 本路只修时区，未动取钟次数）。
 */
private fun timeFields(millis: Long, zone: ZoneId): LongArray {
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    return longArrayOf(
        t.year.toLong(),
        t.monthValue.toLong(),
        t.dayOfMonth.toLong(),
        t.hour.toLong(),
        t.minute.toLong(),
        t.second.toLong(),
        (t.nano / 1_000_000).toLong(),
    )
}

/**
 * M3+ 占位：被调用时抛 CodeEE 并带函数名（不静默，与项目 ADR 一致）。
 *
 * 分类依据 specs/builtin-functions.yaml：view(73)/input(13)/save(7)/load(3)/
 * debug(6) 及 variable/other 中的 XML/DataTable/Map/音频/窗口家族。
 */
internal fun registerUnimplementedAll(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // (名字, 返回类型, 最少参数, 最多参数) —— 签名对照 yaml 的 args 栏
    val unimplemented = listOf(
        // XML 文档池
        "XML_DOCUMENT" to (I to 4), "XML_RELEASE" to (I to 4), "XML_EXIST" to (I to 4),
        "XML_GET" to (I to 4), "XML_GET_BYNAME" to (I to 4), "XML_SET" to (I to 4),
        "XML_SET_BYNAME" to (I to 4), "XML_TOSTR" to (S to 4), "XML_ADDNODE" to (I to 4),
        "XML_ADDNODE_BYNAME" to (I to 4), "XML_ADDATTRIBUTE" to (I to 4),
        "XML_ADDATTRIBUTE_BYNAME" to (I to 4), "XML_REMOVENODE" to (I to 4),
        "XML_REMOVENODE_BYNAME" to (I to 4), "XML_REMOVEATTRIBUTE" to (I to 4),
        "XML_REMOVEATTRIBUTE_BYNAME" to (I to 4), "XML_REPLACE" to (I to 4),
        "XML_REPLACE_BYNAME" to (I to 4),
        // Map 池
        "MAP_CREATE" to (I to 4), "MAP_EXIST" to (I to 4), "MAP_RELEASE" to (I to 4),
        "MAP_SET" to (I to 4), "MAP_HAS" to (I to 4), "MAP_REMOVE" to (I to 4),
        "MAP_CLEAR" to (I to 4), "MAP_SIZE" to (I to 4), "MAP_GET" to (S to 4),
        "MAP_GETKEYS" to (S to 4), "MAP_TOXML" to (S to 4), "MAP_FROMXML" to (I to 4),
        // DataTable 池
        "DT_CREATE" to (I to 4), "DT_EXIST" to (I to 4), "DT_RELEASE" to (I to 4),
        "DT_CLEAR" to (I to 4), "DT_NOCASE" to (I to 4), "DT_COLUMN_ADD" to (I to 4),
        "DT_COLUMN_NAMES" to (I to 4), "DT_COLUMN_EXIST" to (I to 4),
        "DT_COLUMN_REMOVE" to (I to 4), "DT_ROW_ADD" to (I to -1), "DT_ROW_SET" to (I to -1),
        "DT_ROW_REMOVE" to (I to 4), "DT_COLUMN_LENGTH" to (I to 4),
        "DT_ROW_LENGTH" to (I to 4), "DT_CELL_GET" to (I to 4), "DT_CELL_ISNULL" to (I to 4),
        "DT_CELL_GETS" to (S to 4), "DT_CELL_SET" to (I to 4), "DT_SELECT" to (I to 4),
        "DT_TOXML" to (S to 4), "DT_FROMXML" to (I to 4),
        // 文件（SAVETEXT/LOADTEXT/EXISTFILE/ENUMFILES 已实现，见同文件 registerFileMethods）
        "CHKCHARADATA" to (I to 4), "FIND_CHARADATA" to (I to 4),
        "GSAVE" to (I to 4), "GLOAD" to (I to 4),
        // 视图 / 窗口（GETCOLOR 家族/CLIENTWIDTH/PRINTCLENGTH 等已实现，见 ConsoleQueryMethods.kt）
        "GETDISPLAYLINE" to (S to 4),
        "GETTEXTBOX" to (S to 4), "SETTEXTBOX" to (I to 4), "MOVETEXTBOX" to (I to 4),
        "RESUMETEXTBOX" to (I to 4), "BITMAP_CACHE_ENABLE" to (I to 4),
        "GCREATED" to (I to 4), "GWIDTH" to (I to 4), "GHEIGHT" to (I to 4),
        "GGETCOLOR" to (I to 4), "GGETFONT" to (S to 4), "GGETFONTSIZE" to (I to 4),
        "GGETFONTSTYLE" to (I to 4), "GGETTEXTSIZE" to (I to 4), "GGETBRUSH" to (I to 4),
        "GGETPEN" to (I to 4), "GGETPENWIDTH" to (I to 4), "GCREATE" to (I to 4),
        "GCREATEFROMFILE" to (I to 4), "GDISPOSE" to (I to 4), "GCLEAR" to (I to 4),
        "GFILLRECTANGLE" to (I to 4), "GSETCOLOR" to (I to 4), "GDRAWG" to (I to 4),
        "GDRAWGWITHMASK" to (I to 4), "GDRAWSPRITE" to (I to 4), "GSETBRUSH" to (I to 4),
        "GSETFONT" to (I to 4), "GSETPEN" to (I to 4), "GDRAWGWITHROTATE" to (I to 4),
        "GDRAWTEXT" to (I to 4), "GDRAWLINE" to (I to 4), "GDASHSTYLE" to (I to 4),
        "SPRITECREATED" to (I to 4), "SPRITEWIDTH" to (I to 4), "SPRITEHEIGHT" to (I to 4),
        "SPRITEPOSX" to (I to 4), "SPRITEPOSY" to (I to 4), "SPRITEMOVE" to (I to 4),
        "SPRITESETPOS" to (I to 4), "SPRITEGETCOLOR" to (I to 4), "SPRITECREATE" to (I to 4),
        "SPRITEDISPOSE" to (I to 4), "SPRITEDISPOSEALL" to (I to 4),
        "SPRITEANIMECREATE" to (I to 4), "SPRITEANIMEADDFRAME" to (I to 4),
        "SETANIMETIMER" to (I to 4), "CBGSETG" to (I to 4), "CBGSETSPRITE" to (I to 4),
        "CBGCLEAR" to (I to 4), "CBGCLEARBUTTON" to (I to 4), "CBGREMOVERANGE" to (I to 4),
        "CBGREMOVEBMAP" to (I to 4), "CBGSETBMAPG" to (I to 4),
        "CBGSETBUTTONSPRITE" to (I to 4), "HTML_GETPRINTEDSTR" to (S to 4),
        "HTML_POPPRINTINGSTR" to (S to 4),
        // 输入
        "GETKEY" to (I to 4), "GETKEYTRIGGERED" to (I to 4), "MOUSEX" to (I to 4),
        "MOUSEY" to (I to 4), "MOUSEB" to (S to 4), "ISACTIVE" to (I to 4),
        "ISSKIP" to (I to 4), "MOUSESKIP" to (I to 4), "MESSKIP" to (I to 4),
        "FLOWINPUT" to (I to 4), "FLOWINPUTS" to (I to 4),
        // 调试 / 配置 / 日志
        "OUTPUTLOG" to (I to 4), "GETMEMORYUSAGE" to (I to 4),
        "GETDOINGFUNCTION" to (S to 4), "GETCONFIG" to (I to 4), "GETCONFIGS" to (S to 4),
        "GETLINESTR" to (S to 4),
        // 函数表 / 宏
        "ISDEFINED" to (I to 4), "EXISTFUNCTION" to (I to 4), "GETMETH" to (I to -1),
        "GETMETHS" to (S to -1), "EXISTMETH" to (I to 4),
        // 音频
        "GETSOUNDORBGMINFO" to (I to 4),
        "SOUNDCONTROL" to (I to 4),
        "BGMCONTROL" to (I to 4),
        // 枚举（需要宏表/函数表/文件系统）
        "ENUMVARBEGINSWITH" to (I to 4), "ENUMVARENDSWITH" to (I to 4),
        "ENUMVARWITH" to (I to 4), "ENUMMACROBEGINSWITH" to (I to 4),
        "ENUMMACROENDSWITH" to (I to 4), "ENUMMACROWITH" to (I to 4),
        "ENUMFUNCBEGINSWITH" to (I to 4), "ENUMFUNCENDSWITH" to (I to 4),
        "ENUMFUNCWITH" to (I to 4),
        // 写类 / 排序（需要变量写通道，M3 语句层一并实现）
        "SETVAR" to (I to 4), "VARSETEX" to (I to 4), "ARRAYMSORT" to (I to -1),
        "ARRAYMSORTEX" to (I to 4), "EXISTVAR" to (I to 4),
        // HTML 家族
        "HTML_STRINGLEN" to (I to 4), "HTML_SUBSTRING" to (S to 4),
        "HTML_STRINGLINES" to (I to 4), "HTML_TOPLAINTEXT" to (S to 4),
        "HTML_ESCAPE" to (S to 4),
        // 正则引用捕获版
    )
    for ((name, spec) in unimplemented) {
        val (ret, maxArgs) = spec
        r.registerUnimplemented(name, ret, 0, maxArgs)
    }
}
