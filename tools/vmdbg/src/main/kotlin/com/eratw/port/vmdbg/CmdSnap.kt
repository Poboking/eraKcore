package com.eratw.port.vmdbg

import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.code.VirtualMachine
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime

/**
 * `snapshot` / `restore`：调试快照（DBG4 P2）—— 一键存 / 还原完整运行时，独立于游戏存档。
 *
 * ## snapshot
 *
 * `snapshot [--out <文件>]`：当前会话的完整状态落盘。
 *
 * - **变量面复用引擎存档序列化，不发明第二种**：走 [FileSystemOps.saveData] 的正式抓取
 *   链（FileSystemOps.kt:91-99：[com.eratw.port.core.save.SaveCapture.normal] +
 *   [com.eratw.port.core.save.SaveCodec.write] + emData 扩展段），字节 = 游戏自己的
 *   save 格式（二进制容器 / 文本由会话 [com.eratw.port.core.save.SaveConfig] 决定）。
 *   写进快照专用槽 [DEBUG_SLOT]，落点是会话自带沙箱（vmdbg 会话本来就是沙箱 ops，
 *   不污染游戏根），随后把字节拷出到 `--out`。
 * - **sidecar**（`<主文件>.json`，主文件名不带 .dbg 时为 `<主文件>.dbg.json`）：RNG 状态
 *   （[com.eratw.port.core.vars.RandProvider.state]，Sfmt19937 的 625 元素导出，
 *   Sfmt19937.kt:90-95）、console 尾 200 行文本、funcStack / depth 反射快照
 *   （**只记录不还原**：函数栈的还原语义就是丢弃，见 restore 节）、commit（环境变量
 *   `ERATW_COMMIT`，未设为空串——构建戳注入归组长接线）、randSeed、墙钟 ISO-8601。
 *
 * ## restore
 *
 * `restore <快照>`：**新装配**一个会话（同 randSeed = [Cli.RAND_SEED]，确定性由种子保证），
 * 然后两步：
 * 1. **变量面走引擎已有的 LOADDATA 语义链**：快照字节拷进本进程沙箱的 [DEBUG_SLOT] 槽，
 *    用读侧 = 沙箱的 [FileSystemOps]（构造同 GameSession.kt:536 生产形态，仅换根）调
 *    [FileSystemOps.loadData]——完整复用 FileSystemOps.kt:105-129 的原链
 *    （SaveDataProbe 头探测 → EmDataPools.clear → SaveCodec.read → resetValues/resetLocalValues
 *    → applyTo(restoreCharacters=true) → EmDataPools.restore → lastLoadNo）。
 *    **调用栈不还原是正确语义**：原版 LOADDATA 就是整栈丢弃（Process.cs:1860
 *    ClearFunctionList，移植侧 = SaveLoadHandoff.requestDataLoaded → StmtOutcome.Unwind，
 *    ProcessRuntime.kt:740-747 消费点）——快照的 funcStack 只记录、永不还原。
 * 2. **RNG setState**：sidecar 的 625 元素灌回 [com.eratw.port.core.vars.RandProvider.loadState]
 *    （Sfmt19937.kt:102-106，对拍过 C# SetRand）。
 *
 * LOADDATA 语义链还会置 SaveLoadHandoff 的 functionListCleared / systemState
 * （FileSystemOps.loadData 末尾的 requestDataLoaded）——那是「脚本执行期读档」的交接信号；
 * restore 是**装配期**还原、没有任何脚本帧在飞，挂着的 Unwind 信号会让恢复后第一条语句
 * 被错误整栈丢弃，所以紧接 [SaveLoadHandoff.reset] 清掉（等价于消费而非丢弃语义：工具侧
 * 没有可丢弃的栈）。CLI 形态的 restore 落完即验（打印还原读数）——同进程继续调函数的
 * 消费面是单测与下波 DevTools 接线（交付报告「需组长接线」）。
 */
object CmdSnap {

    /**
     * 快照运输槽：saveData/loadData 的 no 参数（文件名 save99.sav，SaveDataProbe.saveName
     * 的 %02d 口径）。选 99 = UI 存档清单（saveDataNos，0..9 一族）之外，与真实玩家档
     * 无撞槽语义；且只存在于每进程私有沙箱里，从不落游戏根。
     */
    const val DEBUG_SLOT = 99

    /** console 尾随行数（sidecar 记录面）。 */
    const val CONSOLE_TAIL_LINES = 200

    /** snapshot：CLI 入口（[Args] 已解析；`--out` 缺省 `<work>/snapshot.dbg`）。 */
    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val assembled = Assembler.assemble(a)
        val main = Path.of(a.valueOr("--out", defaultSnapPath(a))).toAbsolutePath().normalize()
        return snapshot(assembled, main, out)
    }

    /** restore：CLI 入口（快照路径缺省 `<work>/snapshot.dbg`）。 */
    fun runRestore(a: Args, out: PrintStream, err: PrintStream): Int {
        val raw = a.positional.getOrNull(1) ?: defaultSnapPath(a)
        val main = Path.of(raw).toAbsolutePath().normalize()
        val assembled = Assembler.assemble(a)
        return restore(assembled, main, out)
    }

    /**
     * 快照主体（CLI 与单测共用；[main] 为绝对路径）。
     * 返回 0 = 成功（输出 status=PASS + 主文件/sidecar 路径）。
     */
    fun snapshot(assembled: Assembler.Assembled, main: Path, out: PrintStream): Int {
        val store = assembled.session.store
        val sandbox = assembled.sandboxDir
        // 变量面：引擎 saveData 全链（抓取 + 编码 + 写沙箱槽）。false = 引擎吞掉的失败
        //（FileSystemOps.saveData catch Throwable → false），多半是沙箱盘问题。
        val ops = FileSystemOps(store, RootedSaveFileIo(sandbox), assembled.session.saveConfig)
        val text = "vmdbg snapshot " + OffsetDateTime.now()
        val ok = try {
            ops.saveData(DEBUG_SLOT, text)
        } catch (e: Exception) {
            throw CliError("快照抓取异常: ${e.message}", e)
        }
        if (!ok) throw CliError("快照抓取失败（saveData 返回 false，见 audit/盘空间）")
        val bytes = Files.readAllBytes(sandbox.resolve(assembled.session.saveConfig.normalPath(DEBUG_SLOT)))
        Files.createDirectories(main.parent)
        Files.write(main, bytes)

        val sidecar = sidecarPath(main)
        Files.writeString(sidecar, buildSidecar(assembled, text), Charsets.UTF_8)
        out.println("status=PASS")
        out.println("main=$main")
        out.println("sidecar=$sidecar")
        out.println("slot=$DEBUG_SLOT")
        return 0
    }

    /**
     * 还原主体（CLI 与单测共用）。[main] 为快照主文件绝对路径；sidecar 可缺
     * （只还变量面与 RNG 时 sidecar 的 RNG 段缺席则跳过 RNG 还原，输出注明）。
     */
    fun restore(assembled: Assembler.Assembled, main: Path, out: PrintStream): Int {
        if (!Files.isRegularFile(main)) throw CliError("快照主文件不存在: $main")
        val store = assembled.session.store
        val bytes = Files.readAllBytes(main)

        // 变量面：拷进本进程沙箱槽 → 引擎 loadData 全链（LOADDATA 语义，见类 KDoc）。
        // 槽相对路径可能带 `sav/` 前缀（USE SAV FOLDER:YES 的 realCorpus 形态，
        // SaveConfig.saveFolderPrefix），落盘前必须建父目录 —— 2026-10-25 真语料冒烟实锤。
        val sandbox = assembled.sandboxDir
        val slotRel = assembled.session.saveConfig.normalPath(DEBUG_SLOT)
        val slotPath = sandbox.resolve(slotRel)
        slotPath.parent?.let { Files.createDirectories(it) }
        Files.write(slotPath, bytes)
        val ops = FileSystemOps(store, RootedSaveFileIo(sandbox), assembled.session.saveConfig)
        try {
            ops.loadData(DEBUG_SLOT)
        } catch (e: com.eratw.port.core.err.CodeEE) {
            throw CliError("快照还原被引擎拒收（LOADDATA 语义）: ${e.message}", e)
        }
        // 装配期还原没有脚本帧在飞：LOADDATA 的交接信号（Unwind）在此消费清零，
        // 否则恢复后第一条语句被整栈丢弃（见类 KDoc「调用栈不还原」节）。
        SaveLoadHandoff.of(store)?.reset()

        // RNG：sidecar 625 元素灌回（手扫数组字段 —— Jsonl.parseLine 只出平面值，
        // 数字数组会被首个逗号截断，不能用它读 rngState）
        val sidecarPath = sidecarPath(main)
        var rngLoaded = false
        if (Files.isRegularFile(sidecarPath)) {
            val line = Files.readString(sidecarPath, Charsets.UTF_8).lineSequence().firstOrNull() ?: ""
            val rngRaw = jsonArrayField(line, "rngState")
            if (!rngRaw.isNullOrEmpty() && rngRaw != "[]") {
                val arr = parseLongArray(rngRaw)
                val provider = store.randProvider
                if (provider != null && arr.size == com.eratw.port.core.rand.Sfmt19937.RANDDATA_LEN) {
                    provider.loadState(arr)
                    rngLoaded = true
                }
            }
        }
        out.println("status=PASS")
        out.println("main=$main")
        out.println("lastLoadNo=${store.lastLoadNo}")
        out.println("rngLoaded=$rngLoaded")
        out.println("funcStack=not-restored（正确语义：LOADDATA 整栈丢弃，见 CmdSnap KDoc）")
        return 0
    }

    // ------------------------------------------------------------------
    // sidecar
    // ------------------------------------------------------------------

    /** sidecar 路径：主文件以 .dbg 结尾 → 加 .json；否则加 .dbg.json（规格 §4 的同名形态）。 */
    fun sidecarPath(main: Path): Path {
        val s = main.toString()
        return Path.of(if (s.endsWith(".dbg")) "$s.json" else "$s.dbg.json")
    }

    /** sidecar JSON（手拼：rngState/consoleTail/funcStack 是数组，Jsonl.obj 只出平面对象）。 */
    private fun buildSidecar(assembled: Assembler.Assembled, text: String): String {
        val store = assembled.session.store
        val console = assembled.session.console
        val rng: LongArray = try {
            store.randProvider?.state() ?: LongArray(0)
        } catch (_: com.eratw.port.core.err.CodeEE) {
            LongArray(0) // 非 SFMT 提供者：记录为空，还原侧跳过
        }
        val tail = console.snapshot().takeLast(CONSOLE_TAIL_LINES).map { console.textOf(it) }
        val (stack, depth) = reflectFuncStack(assembled)
        val commit = System.getenv("ERATW_COMMIT") ?: ""
        return buildString {
            append("{\"format\":1")
            append(",\"createdAt\":\"").append(Jsonl.escape(OffsetDateTime.now().toString())).append('"')
            append(",\"snapshotText\":\"").append(Jsonl.escape(text)).append('"')
            append(",\"commit\":\"").append(Jsonl.escape(commit)).append('"')
            append(",\"randSeed\":").append(Cli.RAND_SEED)
            append(",\"rngState\":[").append(rng.joinToString(",")).append(']')
            append(",\"consoleTail\":[")
            tail.forEachIndexed { i, l ->
                if (i > 0) append(',')
                append('"').append(Jsonl.escape(l)).append('"')
            }
            append("]")
            append(",\"funcStack\":[")
            stack.forEachIndexed { i, k ->
                if (i > 0) append(',')
                append('"').append(Jsonl.escape(k)).append('"')
            }
            append("]")
            append(",\"funcDepth\":").append(depth)
            append("}")
        }
    }

    /**
     * funcStack / depth 反射快照（**只记录**）：[VirtualMachine] 的私有 `funcStack`
     * （IntArray，函数下标栈）与 `depth`。字段改名 / 反射不可得 → 空栈 + (-1)，
     * 不阻塞快照主流程。函数下标 → key 映射用 vmdbg 自留的 [Assembler.Assembled.metadata]。
     */
    private fun reflectFuncStack(assembled: Assembler.Assembled): Pair<List<String>, Int> {
        val rt = assembled.session.runtime
        val vm = try {
            val f = ProcessRuntime::class.java.getDeclaredField("vm")
            f.isAccessible = true
            f.get(rt) as? VirtualMachine
        } catch (_: Throwable) {
            null
        } ?: return emptyList<String>() to -1
        val stack = try {
            val f = VirtualMachine::class.java.getDeclaredField("funcStack")
            f.isAccessible = true
            f.get(vm) as? IntArray
        } catch (_: Throwable) {
            null
        }
        val depth = try {
            val f = VirtualMachine::class.java.getDeclaredField("depth")
            f.isAccessible = true
            f.get(vm) as? Int
        } catch (_: Throwable) {
            null
        } ?: return emptyList<String>() to -1
        val arr = stack ?: return emptyList<String>() to depth
        if (depth < 0 || depth >= arr.size) return emptyList<String>() to depth
        val byIndex = HashMap<Int, String>()
        for (m in assembled.metadata.functions) byIndex[m.index] = m.key
        return (0..depth).map { byIndex[arr[it]] ?: "#" + arr[it] } to depth
    }

    /** "[1,2,3]" → LongArray（restore 的 rngState 解析；坏形状返回空数组）。 */
    private fun parseLongArray(raw: String): LongArray {
        val body = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return LongArray(0)
        return body.split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    }

    /**
     * 单行 JSON 里取数组字段的原文（含括号）：`"key":[...]`。字段缺席 → null。
     * 只服务本文件的 sidecar 自读自写（形状自己产的，不做通用 JSON 解析）。
     */
    private fun jsonArrayField(line: String, key: String): String? {
        val needle = "\"$key\":["
        val start = line.indexOf(needle)
        if (start < 0) return null
        val from = start + needle.length
        val end = line.indexOf(']', from)
        if (end < 0) return null
        return line.substring(from - 1, end + 1)
    }

    /** 快照缺省落点：`<work>/snapshot.dbg`（与 last-error.json 同一 --work 口径）。 */
    private fun defaultSnapPath(a: Args): String =
        Path.of(a.valueOr("--work", defaultWorkDir())).resolve("snapshot.dbg").toString()

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}
