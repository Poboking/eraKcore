package com.eratw.port.render.displaylist

/**
 * 行级并查集：把**相邻**的脏行合并成一条 [DirtyRect] 的行区间。
 *
 * ## 为什么粒度是「行」
 *
 * 原版的失效区域就是一条整行：`ConsoleDisplayLine.Clear` 擦的是
 * `(0, pointY, WindowX, LineHeight)` 这一行高的矩形（spec: `ConsoleDisplayLine.cs:112-116`），
 * `OnPaint` 也是按 `for (i = num3; i <= num2; i++)` 逐行画（spec: `EmueraConsole.cs:1852-1856`），
 * ADR-003 因此规定渲染线程在 Choreographer 回调里按行消费。
 * 相邻脏行合成一条带（run）之后，消费侧一次行清除 + 一段行绘制即可，
 * 不必为同一行发多条命令。
 *
 * ## 为什么用并查集而不是线性扫描
 *
 * 线性扫描能算出同样的区间，但并查集把「合并」这个动作变成数据结构：
 * 后续若要把脏粒度细化到段或做跨提交累积，只需换一条 `union` 边，
 * 分区逻辑不用重写。这里实现得极简（无路径递归、按索引升序出结果），
 * 输出顺序只由行下标决定，不依赖任何哈希容器的遍历序（确定性要求）。
 *
 * ## 复用（W8-RENDERPERF）
 *
 * 本类**每次提交都复用同一批数组**：[reset] 只重写已用前缀，[runsInto] 把结果写成
 * `[start, end]` 的下标对而不是 `List<IntRange>`。稳态帧（没有脏行）因此一次分配都不做
 * —— 这是「每帧少分配」在脏区合并这一步的落点；结构与合并口径一个字都没改。
 * 调用约定：单线程（同 [DisplayList]），[reset] 之后才能 [runsInto]，入参长度与 [reset] 一致。
 */
internal class RowUnionFind(initialCapacity: Int = 8) {

    /** 父指针；长度 >= 当前 [size]。 */
    private var parent = IntArray(if (initialCapacity < 1) 1 else initialCapacity)

    /** 秩（这里等价于「区间长度等级」），保证合并树浅。 */
    private var rank = IntArray(parent.size)

    /** [runsInto] 用过的「集合是否已出过」标记；长度 >= [size]。 */
    private var seen = BooleanArray(parent.size)

    /** 本次的行数（[reset] 写入）。 */
    private var size = 0

    /**
     * 开始一次新的合并：把 [n] 行的父指针与秩归位（`parent[i] = i`、`rank[i] = 0`），
     * 并清掉上一次的 [seen]。容量不足时按 2 倍增长（摊销一次分配）。
     */
    fun reset(n: Int) {
        val need = if (n < 1) 1 else n
        if (parent.size < need) {
            var cap = parent.size
            while (cap < need) {
                cap *= 2
            }
            parent = IntArray(cap)
            rank = IntArray(cap)
            seen = BooleanArray(cap)
        }
        var i = 0
        while (i < need) {
            parent[i] = i
            rank[i] = 0
            seen[i] = false
            i++
        }
        size = n
    }

    /** 查根：路径折半压缩，迭代实现（避免深链递归爆栈）。 */
    fun find(x: Int): Int {
        var i = x
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]
            i = parent[i]
        }
        return i
    }

    /** 合并两个集合；同秩时保留下标小的一侧为根，使结果可预期。 */
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) {
            return
        }
        val small = if (ra < rb) ra else rb
        val large = if (ra < rb) rb else ra
        when {
            rank[small] < rank[large] -> parent[small] = large
            rank[small] > rank[large] -> parent[large] = small
            else -> {
                parent[large] = small
                rank[small]++
            }
        }
    }

    /**
     * 把 [dirty] 标记的行聚成按 `rowStart` 升序的连续段，结果写进 [out] 的
     * `[2k] = 段首行`、`[2k + 1] = 段末行（含）`。
     *
     * @param dirty 长度与本实例行数一致的行级脏标记
     * @param out 输出缓冲；长度至少 `2 * size`（不足按 [IllegalArgumentException] 报错）
     * @return 段数（0 表示没有脏行）；`colStart`/`colEnd` 由调用方按段内最大列跨度补齐
     */
    fun runsInto(dirty: BooleanArray, out: IntArray): Int {
        require(dirty.size >= size) { "脏标记长度 ${dirty.size} 小于行数 $size" }
        require(out.size >= size * 2) { "输出缓冲长度 ${out.size} 小于 ${size * 2}" }
        // 相邻脏行连边：只剩「区间」这一个语义，后续细化粒度只需改这里。
        var i = 0
        while (i < size - 1) {
            if (dirty[i] && dirty[i + 1]) {
                union(i, i + 1)
            }
            i++
        }
        var count = 0
        i = 0
        while (i < size) {
            if (!dirty[i]) {
                i++
                continue
            }
            val root = find(i)
            if (seen[root]) {
                i++
                continue
            }
            seen[root] = true
            var end = i
            while (end + 1 < size && dirty[end + 1] && find(end + 1) == root) {
                end++
            }
            out[count * 2] = i
            out[count * 2 + 1] = end
            count++
            i = end + 1
        }
        return count
    }
}
