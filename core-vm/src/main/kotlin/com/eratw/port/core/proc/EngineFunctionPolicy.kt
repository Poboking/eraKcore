package com.eratw.port.core.proc

/**
 * 引擎级函数屏蔽表（**引擎策略，不是语料改动**）。
 *
 * ## 为什么要有这一层
 *
 * 有些语料函数对「能玩」毫无贡献，却能把一屏的时间整块吃掉。典型就是 eraTW 标题画面的
 * `@BADAPPLE`：6573 帧 `AWAIT 16`（≈ 105 秒）+ 一个 `bad-apple-audio.MP3`，纯粹是
 * 标题页的彩蛋动画。它在 4.980 版语料里**已被语料作者自己注释掉**
 * （`ERB/TITLE.ERB:115` 的 `;CALL BADAPPLE`），但：
 *
 * * 语料不进仓（`.gitignore` 排除整个 `eratw-画蛇添足版eratw4.980/`），别人的 clone
 *   用的是上游语料，那一行可能是**活的**；
 * * 手工注释语料 = 每人每次更新语料都要重做一遍，且改的是**玩家自己的存档目录**里的文件
 *   （首次启动从 assets 解包过去的那份），升级/换机就丢。
 *
 * 因此屏蔽动作必须落在**引擎**：默认屏蔽，语料一行都不用改；想看的玩家在设置页关掉开关即可。
 *
 * ## 语义（被屏蔽的函数 = 空体 `RETURN`）
 *
 * 命中屏蔽表的函数在**调用边界**被短路（见 [com.eratw.port.core.code.VmHost.isStubbedFunction]）：
 * 不建帧、不复制实参、不动变量层作用域，调用点直接得到 `0`（字符串位得 `""`），
 * 与「函数体只有一句 `RETURN 0`」逐位等价。三种调用形态（静态 `CALL`、动态
 * `CALLFORM`/`TRYCALLFORM`、`CALLF` 家族）与根级 API（`callFunction` / `executeEvent` …）
 * 走的是同一张表，语料里怎么调都拦得住。
 *
 * ## 为什么按**名字**而不是按下标
 *
 * 函数下标是编译期产物（`.erbc` 的函数表顺序），换语料/换产物就变；名字是语料作者写下的
 * 稳定标识，且原版的函数名比较本来就是 `OrdinalIgnoreCase`（spec: `Config.cs:33` `StrComper`），
 * 故匹配口径与引擎其它名字解析面一致（大小写不敏感）。
 */
object EngineFunctionPolicy {

    /** eraTW 标题彩蛋：6573 帧 `AWAIT 16` + 一个 MP3，纯装饰（`ERB/TITLE.ERB:123`）。 */
    const val BAD_APPLE: String = "BADAPPLE"

    /**
     * 出厂屏蔽集。新增条目时必须在这里写清「为什么它该被默认关掉」，并且
     * **只放对玩法零贡献的函数** —— 屏蔽是静默的，误伤一个真逻辑函数会变成难查的空行为。
     */
    val DEFAULT_DISABLED: Set<String> = setOf(BAD_APPLE)

    /**
     * 归一化宿主传进来的名字集（去空白、大写、丢空串）。
     *
     * 空集是合法输入 = 全部放行（设置页把开关关掉时的口径）。
     */
    fun normalize(names: Set<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>(names.size)
        for (raw in names) {
            val key = raw.trim().uppercase()
            if (key.isNotEmpty()) out.add(key)
        }
        return out
    }
}

/**
 * 屏蔽表的**运行期形态**：`Program.functions` 下标位图 + 实际命中的函数名。
 *
 * 位图是 VM 每 `_CALL` 一次的查询面（[com.eratw.port.core.code.VmHost.isStubbedFunction]），
 * 必须是最便宜的形态：一次 `BooleanArray` 读 + 一次上界比较，零分配、零哈希。
 *
 * 名字集为空（宿主关掉开关）时位图长度为 0，[hit] 恒 false —— 判据退化为一次比较，
 * 与改造前（根本没有这个钩子）的差别只有一次空数组长度读取。
 */
internal class StubTable private constructor(
    private val flags: BooleanArray,
    /** 命中的函数名（`Program.functions` 里的原名，便于宿主原样上屏/记日志）。 */
    val names: List<String>,
) {
    /** 下标是否被屏蔽。[funcIndex] 由 VM 保证 ≥ 0（越界在调用点已抛）。 */
    fun hit(funcIndex: Int): Boolean = funcIndex < flags.size && flags[funcIndex]

    companion object {
        /**
         * 按名匹配 [Program.functions]。名字比较用 `equals(ignoreCase = true)`
         * （不走 `uppercase()`：110,072 个函数逐个 ICU 大写在 ART 上要几十毫秒，
         * 而待查名字集通常只有 1~2 个）。
         *
         * 名字一律先走 [Program.functionNamePeek]（M13-MEM5 刀 1 的**只要名字**取用面，
         * 不建 `FunctionInfo`）；桥不通的下标才退回 `functions[i].name` 物化面
         * ——退路行为与改造前逐字相同，代价（15.6 MiB 常驻）由 mem3 构成表盯着。
         */
        fun of(program: com.eratw.port.core.code.Program, disabled: Set<String>): StubTable {
            val wanted = EngineFunctionPolicy.normalize(disabled)
            if (wanted.isEmpty()) return StubTable(BooleanArray(0), emptyList())
            val functions = program.functions
            val peek = program.functionNamePeek()
            var flags: BooleanArray? = null
            val names = ArrayList<String>(4)
            for (i in functions.indices) {
                val name = peek.nameAt(i) ?: functions[i].name
                // inline 谓词，无 lambda 分配；名字集通常 1~2 项
                if (wanted.none { it.equals(name, ignoreCase = true) }) continue
                val f = flags ?: BooleanArray(functions.size).also { flags = it }
                f[i] = true
                names.add(name)
            }
            return StubTable(flags ?: BooleanArray(0), names)
        }
    }
}
