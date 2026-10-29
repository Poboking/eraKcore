package com.eratw.port.erbcc.spec

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.FunctionCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CMP-70：规格双表 JUnit 闸。
 *
 * 此前 `specs` 目录里的 isa.yaml（169 条语句的 `arg_form`）与 builtin-functions.yaml
 * （271 条内建的 `args` / `returns`）只有仓外 Python `tools/spec_crosscheck.py` 对拍，
 * 而它不在 AGENTS §5 的三条命令里 ⇒ 采样表漂移可一路过闸。本测试把两条对拍
 * 直接钉在 `:tools:erbcc:test` 的测试面上：
 *
 * 1. isa.yaml 每条语句的 `arg_form` 必须解析出至少一个 [ArgType]，且该条目
 *    `name` 与 `aliases` 里每个已注册名在 [FunctionCode] 中的 `argType` 都落在解析集合内；
 *    同时断言 [FunctionCode] 除 `__NULL__` 外的每个条目都被 isa.yaml 覆盖（双向一一对应）。
 * 2. builtin-functions.yaml 每个内建的 `args` 解析出的元数（必需前缀 / 上限）必须与
 *    [Builtins] 注册表的 `minArgs` / `maxArgs` / `omitStart` 一致，`returns` 必须与
 *    注册表及 [BuiltinSymbols.functions] 的返回类型一致。
 *
 * 散文形态（机器无法从字面推出的）一律进显式白名单并写明原因；白名单键若在
 * specs 里已不存在，测试也会红（防表项过期后继续放行）。**没有任何静默跳过路径**。
 */
class SpecCrossTableTest {

    // ------------------------------------------------------------------
    // 规格文件定位与极简解析
    // ------------------------------------------------------------------

    /** 从 `tools/erbcc` 模块目录向上找 `specs` 目录，不把机器绝对路径写死。 */
    private fun specFile(name: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, "../../specs/$name"),
            File(cwd, "../../../specs/$name"),
            File(cwd, "specs/$name"),
            File(cwd, "../specs/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 specs/$name（工作目录 $cwd）")
    }

    private data class IsaEntry(
        val name: String,
        val argForm: String,
        val aliases: List<String>,
    )

    /**
     * 极简 yaml 解析：只认 isa.yaml 的 `- name:` / `  arg_form:` / `  aliases:`。
     * `aliases` 允许折行（续行以 4 个空格开头，与 `OpcodeTableTest` 同一口径）。
     */
    private fun parseIsa(): List<IsaEntry> {
        val lines = specFile("isa.yaml").readLines(Charsets.UTF_8)
        val out = ArrayList<IsaEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("- name: ")) {
                i++
                continue
            }
            val name = line.removePrefix("- name: ").trim()
            var argForm = ""
            var aliases = emptyList<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("- name: ")) {
                val l = lines[j]
                if (l.startsWith("  arg_form: ")) argForm = l.removePrefix("  arg_form: ").trim()
                if (l.startsWith("  aliases: ")) {
                    val sb = StringBuilder(l.removePrefix("  aliases: "))
                    var k = j + 1
                    while (k < lines.size && lines[k].startsWith("    ")) {
                        sb.append(' ').append(lines[k].trim())
                        k++
                    }
                    aliases = Regex("[A-Z][A-Z0-9_]*")
                        .findAll(sb.toString())
                        .map { it.value }
                        .distinct()
                        .toList()
                }
                j++
            }
            out += IsaEntry(name, argForm, aliases)
            i = j
        }
        return out
    }

    private data class BuiltinEntry(
        val name: String,
        val args: String,
        val returns: String,
    )

    private fun parseBuiltins(): List<BuiltinEntry> {
        val lines = specFile("builtin-functions.yaml").readLines(Charsets.UTF_8)
        val out = ArrayList<BuiltinEntry>()
        var current: BuiltinEntry? = null
        for (line in lines) {
            if (line.startsWith("- name: ")) {
                current = BuiltinEntry(line.removePrefix("- name: ").trim(), "", "")
                out += current
                continue
            }
            val e = current ?: continue
            when {
                line.startsWith("  args: ") ->
                    current = e.copy(args = line.removePrefix("  args: ").trim())
                line.startsWith("  returns: ") ->
                    current = e.copy(returns = line.removePrefix("  returns: ").trim())
            }
            if (current !== e) out[out.size - 1] = current!!
        }
        return out
    }

    // ------------------------------------------------------------------
    // 1. isa.yaml 的 arg_form ↔ FunctionCode.argType
    // ------------------------------------------------------------------

    /**
     * 散文 / 非离散形态的显式白名单（键 = isa.yaml 里 `arg_form` 原文）。
     *
     * 只有确实无法从 `arg_form` 字面推出唯一 [ArgType] 集合的条目才允许登记；
     * 每条都写明机器读不出的原因，值给该条目全部注册名应命中的形态集合。
     * 键在 specs 里不存在时测试会红（白名单过期同样算漂移）。
     */
    private val isaArgFormProse: Map<String, Pair<Set<ArgType>, String>> = mapOf(
        "IntAsignArgument" to Pair(
            setOf(ArgType.INT_ASIGN),
            "VARI：原版注册时 ArgBuilder 为 null，参数在 LogicalLineParser 手工解析（FunctionCode.kt:13 口径）",
        ),
        "StrAsignArgument" to Pair(
            setOf(ArgType.STR_ASIGN),
            "VARS：同 VARI，参数在 LogicalLineParser 手工解析（FunctionCode.kt:13 口径）",
        ),
        "'CreateArgument: 1 raw primitive (mandatory) → ExpressionArgument const string'" to Pair(
            setOf(ArgType.STR),
            "CUSTOMDRAWLINE：实现类覆写 CreateArgument 自取 1 个原始常量串，不走 FunctionArgType 注册表",
        ),
        "ExpressionsArgument (普通参数)" to Pair(
            setOf(ArgType.EXPRESSIONS),
            "INPUTMOUSEKEY：GetNormalArgumentBuilder(\"I\", 0) 的 0..n 整型表达式形态（FunctionCode.kt:15）",
        ),
        "结构块 (列表元素为 SP_CALL 语句行)；注册形态 FunctionArgType.VOID（FunctionIdentifier.cs:4065-4067）" to Pair(
            setOf(ArgType.VOID),
            "TRYCALLLIST：列表元素是语句行而非实参；注册字节码形态为 FunctionArgType.VOID",
        ),
        "结构块 (列表元素为标签名)；注册形态 FunctionArgType.VOID（FunctionIdentifier.cs:4067）" to Pair(
            setOf(ArgType.VOID),
            "TRYGOTOLIST：列表元素是标签名而非实参；注册字节码形态为 FunctionArgType.VOID",
        ),
        "'varies by alias: STR_NULLABLE / STR_EXPRESSION / FORM_STR_NULLABLE / SP_PRINTV'" to Pair(
            setOf(ArgType.PRINT),
            "PRINT：77 个注册别名的真实形态由语句名后缀在 PrintSpec.parse 单遍解析，语句层用伪类型 PRINT",
        ),
        "SpHtmlPrint (SP_HTML_PRINT)；注册形态 FunctionArgType.VOID（FunctionIdentifier.cs:395）" to Pair(
            setOf(ArgType.VOID),
            "HTML_PRINT_ISLAND_CLEAR：arg_form 前缀是类名，但注册实参显式给 FunctionArgType.VOID",
        ),
    )

    /** 从 `arg_form` 提取 [ArgType] 集合；先查白名单，再按括号内 token 解析，无括号时回退整串。 */
    private fun argFormTypes(form: String): Set<ArgType>? {
        isaArgFormProse[form]?.let { return it.first }
        val tokens = ArrayList<String>()
        val paren = Regex("""\(([A-Z_ /]+)\)""")
        for (m in paren.findAll(form)) {
            tokens += m.groupValues[1].split('/').map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (tokens.isEmpty()) {
            val head = form.substringBefore('(').trim().trim('\'')
            tokens += head.split('/').map { it.trim().trim('\'') }.filter { it.isNotEmpty() }
        }
        val types = tokens.mapNotNull { token -> runCatching { ArgType.valueOf(token) }.getOrNull() }
        return types.toSet().takeIf { it.isNotEmpty() }
    }

    @Test
    fun `isa 每条 arg_form 都映射到 FunctionCode 的 ArgType`() {
        val isa = parseIsa()
        assertEquals(169, isa.size, "isa.yaml 条目数（OpcodeTableTest 同口径）")

        val mismatches = ArrayList<String>()
        val unrecognized = ArrayList<String>()
        for (e in isa) {
            val expected = argFormTypes(e.argForm)
            if (expected == null) {
                unrecognized += "${e.name}: ${e.argForm}"
                continue
            }
            val names = (listOf(e.name) + e.aliases).distinct()
            for (n in names) {
                val code = FunctionCode.entries.firstOrNull { it.name == n } ?: continue
                if (code == FunctionCode.__NULL__) continue
                if (code.argType !in expected) {
                    mismatches += "${e.name}/$n: arg_form=${e.argForm} 判 $expected，FunctionCode.argType=${code.argType}"
                }
            }
        }
        assertTrue(
            unrecognized.isEmpty(),
            "isa.yaml 有 arg_form 无法映射到 ArgType（必须补显式白名单并写原因）：\n  " +
                unrecognized.joinToString("\n  "),
        )
        assertTrue(
            mismatches.isEmpty(),
            "isa.yaml 的 arg_form 与 FunctionCode.argType 不一致：\n  " + mismatches.joinToString("\n  "),
        )
    }

    @Test
    fun `isa 覆盖 FunctionCode 的全部注册名（双向）`() {
        val isa = parseIsa()
        val isaNames = isa.flatMap { listOf(it.name) + it.aliases }.toSet()

        // SET / METHOD 两条伪码不在 BY_NAME（CMP-48），但仍是 enum 条目，必须由 isa 覆盖；
        // __NULL__ 是移植侧占位，原版无对应语句。
        val registered = FunctionCode.entries.filter { it != FunctionCode.__NULL__ }
        val missing = registered.filter { it.name !in isaNames }.map { it.name }
        assertTrue(missing.isEmpty(), "FunctionCode 有注册名未出现在 isa.yaml（name 或 aliases）：$missing")

        // 反向：每条 isa 条目至少要有一个注册名落在 FunctionCode 里。
        // isa 的 name 是**实现类名去掉 _Instruction 后缀**（PRINT_DATA / SAVELOADGAME / DO_NOTHING
        // 这类派生名本来就不是注册名），所以按条目名查 enum 会误报；注册名在 aliases 里。
        val enumNames = FunctionCode.entries.map { it.name }.toSet()
        val unmapped = isa.filter { e ->
            (listOf(e.name) + e.aliases).none { it in enumNames }
        }
        assertTrue(
            unmapped.isEmpty(),
            "isa.yaml 有条目（name 或 aliases）没有任何注册名能解析到 FunctionCode：${unmapped.map { it.name }}",
        )
    }

    @Test
    fun `isa 的散文白名单不陈旧`() {
        val forms = parseIsa().map { it.argForm }.toSet()
        val stale = isaArgFormProse.keys.filter { it !in forms }
        assertTrue(stale.isEmpty(), "isaArgFormProse 白名单存在 specs 里已不存在的键（过期即红）：$stale")
        assertEquals(8, isaArgFormProse.size, "isa.yaml 散文 arg_form 的白名单条数（每条必须写原因）")
    }

    // ------------------------------------------------------------------
    // 2. builtin-functions.yaml 的 args / returns ↔ BuiltinRegistry
    // ------------------------------------------------------------------

    private data class Arity(val min: Int, val max: Int)

    /** 散文 `args` 的显式覆盖：args 原文（防 spec 漂移）+ 按 C# 实参表读出的真实元数 + 原因。 */
    private data class ArityRule(val args: String, val expected: Arity, val reason: String)

    /**
     * 散文 / 欠表达 `args` 的显式白名单（键 = 内建函数名）。
     *
     * 这些条目的 `args` 字面不足以推出 C# 实参表（变长标记、重载、散文括号），
     * 必须按 FunctionMethodCreator 的 ArgTypeList 显式登记；`args` 原文一起钉住，
     * specs 一改就会红，必须重新对 C# 才能改这张表。上限 -1 = 变长。
     */
    private val builtinArgsProse: Map<String, ArityRule> = mapOf(
        "RAND" to ArityRule(
            "min, max (min 可省略)", Arity(1, 2),
            "FunctionMethodCreator.cs:4030-4040 两份 Int 槽 + OmitStart=1 ⇒ 1..2；" +
                "散文写明 min 可省略（不能按两个必填数）",
        ),
        "NOSAMES" to ArityRule(
            "value, ...", Arity(2, -1),
            "FunctionMethodCreator.cs:4561-4572 实参表 2 槽（Any + VariadicSameAsFirst）、OmitStart 缺省 -1 " +
                "⇒ 至少 2 参；args 的「value, ...」只写了首参",
        ),
        "ALLSAMES" to ArityRule(
            "value, ...", Arity(2, -1),
            "FunctionMethodCreator.cs:4610-4621 同 NOSAMES：2 槽 + 变长，OmitStart 缺省 -1 ⇒ 至少 2 参",
        ),
        "XML_ADDATTRIBUTE" to ArityRule(
            "doc, xpath, name, value[, index]", Arity(3, 6),
            "verify-notes.md §10.6 已登记：C# Attribute 分支 6 槽 OmitStart=3（FunctionMethodCreator.cs:1365-1393）" +
                "⇒ 3..6；yaml 的 4..5 是欠表达",
        ),
        "XML_ADDATTRIBUTE_BYNAME" to ArityRule(
            "docName, xpath, name, value[, index]", Arity(3, 6),
            "verify-notes.md §10.6 同族：byName 重载 6 槽 OmitStart=3（FunctionMethodCreator.cs:1423-1438）⇒ 3..6",
        ),
        "DT_ROW_ADD" to ArityRule(
            "name, colName, value, ...", Arity(1, -1),
            "FunctionMethodCreator.cs:2144-2160 第一份 ArgTypeList（String + VariadicString + VariadicAny）" +
                "OmitStart=1 ⇒ 至少 1 参；「列名,值」成对形态由运行期数参",
        ),
        "GDASHSTYLE" to ArityRule(
            "gId, style, ...", Arity(3, 3),
            "FunctionMethodCreator.cs:6445-6460 用旧式 Type[3]（long,long,long）**恰好 3 参**，" +
                "args 的「...」与实现不符",
        ),
        "GETMETH" to ArityRule(
            "funcName, default, args...", Arity(1, -1),
            "FunctionMethodCreator.cs:8537-8548 3 槽（String, Int, VariadicAny）OmitStart=1 ⇒ 至少 1 参；" +
                "default 可省略（缺失且函数不存在时抛 NotDefinedUserFunc）",
        ),
        "GETMETHS" to ArityRule(
            "funcName, default, args...", Arity(1, -1),
            "FunctionMethodCreator.cs:8550-8570 与 GETMETH 同构：3 槽 OmitStart=1 ⇒ 至少 1 参",
        ),
    )

    /**
     * 已确认的**注册表 vs specs/C# 分歧**（键 = 内建函数名）。
     *
     * 2026-10-24 N8 裁决后本表**清空**：原 4 条（XML_REPLACE_BYNAME / DT_ROW_SET / GSETFONT /
     * ISPLAYINGSOUND）的 `args` 与 C# 实参表一致，偏的是 [Builtins] 注册值；N8 已按 C# 把注册
     * 改回 3..4 / 4..-1 / 2..4 / 0..1（core-vm 的 XmlMethods / MapDataTableMethods /
     * GraphicsMethods / HostMethods），并在 `BuiltinArityCSharpTest` 里逐条钉住 C# 行号、
     * 个数闸放行/拒绝行为与「短参形态求值期 CodeEE」口径。
     *
     * 本表保留为空表：将来再出现真分歧必须显式登记（键在 specs 里不存在 / 注册值再漂移都会红）。
     */
    private val builtinArityDivergences: Map<String, ArityDivergence> = emptyMap()

    private data class ArityDivergence(
        val args: String,
        val csharp: Arity,
        val registry: Arity,
        val reason: String,
    )

    /**
     * 把 `args` 串解析成元数。
     *
     * 口径与 `tools/spec_crosscheck.py` 的 `bf_arg_arity` 一致并补变长：
     * - 去掉首尾引号；空串 = 0..0；
     * - 去掉全部 `[` / `]` 后按顶层逗号数得到上限；
     * - 第一个 `[` 之前的顶层逗号段数 = 必需前缀（min），`[pattern]` 这种全可省略给 0；
     * - 末段带 `...`（`value, ...` / `args...` / `sortKeys...`）是变长：上限 -1，
     *   该段不计数（`value, ...` 的必需前缀是 1）。
     *
     * 字面推不出真实元数的条目在 [builtinArgsProse] / [builtinArityDivergences] 里显式登记。
     */
    private fun parseArgsArity(args: String): Arity {
        var s = args.trim().trim('\'', '"')
        if (s.isEmpty()) return Arity(0, 0)
        val variadic = s.split(',').any { it.trim().endsWith("...") }
        if (variadic) {
            s = s.split(',').filterNot { it.trim().endsWith("...") }.joinToString(",")
        }
        val cut = s.indexOf('[')
        val flat = s.replace("[", "").replace("]", "")
        val segments = flat.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val min = if (cut < 0) segments.size else {
            val head = s.substring(0, cut).trim().trim(',')
            head.split(',').map { it.trim() }.count { it.isNotEmpty() }
        }
        return Arity(min, if (variadic) -1 else segments.size)
    }

    /** `returns` 字段 → [OperandType]；规格只出现 int / string 两种。 */
    private fun returnTypeOf(entry: BuiltinEntry): OperandType = when (entry.returns) {
        "int" -> OperandType.INT
        "string" -> OperandType.STR
        else -> error("builtin-functions.yaml 的 ${entry.name} 有未知 returns=${entry.returns}")
    }

    @Test
    fun `builtin args 的元数与 BuiltinRegistry 一致`() {
        val yaml = parseBuiltins()
        assertEquals(271, yaml.size, "builtin-functions.yaml 条目数（summary.total）")
        assertEquals(271, Builtins.registry.size(), "注册表条目数")

        val yamlNames = yaml.map { it.name }.toSet()
        assertEquals(271, yamlNames.size, "builtin-functions.yaml 有重名")
        val missing = yamlNames - Builtins.registry.names()
        val extra = Builtins.registry.names() - yamlNames
        assertTrue(missing.isEmpty(), "注册表缺少：$missing")
        assertTrue(extra.isEmpty(), "注册表多出：$extra")

        val arityBad = ArrayList<String>()
        val omitBad = ArrayList<String>()
        val proseSeen = ArrayList<String>()
        val divergenceSeen = ArrayList<String>()
        for (e in yaml) {
            val m = assertNotNull(Builtins.registry[e.name], "${e.name} 不在注册表")
            val divergence = builtinArityDivergences[e.name]
            val rule = builtinArgsProse[e.name]
            when {
                divergence != null -> {
                    divergenceSeen += e.name
                    assertEquals(
                        divergence.args, e.args,
                        "${e.name} 的 args 已漂移，必须重新对 C# 后再改 builtinArityDivergences",
                    )
                    assertEquals(
                        divergence.registry.min, m.minArgs,
                        "${e.name} 注册下界变了（${divergence.reason}）",
                    )
                    assertEquals(
                        divergence.registry.max, m.maxArgs,
                        "${e.name} 注册上界变了（${divergence.reason}）",
                    )
                    // specs 与 C# 必须仍一致；将来注册值对齐 C# 后本表项必须删掉。
                    assertEquals(
                        divergence.csharp, parseArgsArity(e.args),
                        "${e.name} 的 specs 已不再等于 C# 实参表，须重判分歧表",
                    )
                    assertTrue(
                        divergence.csharp != divergence.registry,
                        "${e.name} 的注册值已与 C# 对齐；请删掉 builtinArityDivergences 里的白名单项",
                    )
                }
                rule != null -> {
                    proseSeen += e.name
                    assertEquals(
                        rule.args, e.args,
                        "${e.name} 的 args 已漂移，必须重新对 C# 后再改 builtinArgsProse",
                    )
                    assertEquals(rule.expected.min, m.minArgs, "${e.name} 注册下界：${rule.reason}")
                    assertEquals(rule.expected.max, m.maxArgs, "${e.name} 注册上界：${rule.reason}")
                }
                else -> {
                    val expected = parseArgsArity(e.args)
                    if (expected.min != m.minArgs || expected.max != m.maxArgs) {
                        arityBad += "${e.name}: args=${e.args} 判 ${expected.min}..${expected.max}，" +
                            "注册表 ${m.minArgs}..${m.maxArgs}（omitStart=${m.omitStart}）"
                    }
                    // omitStart 只在注册处显式给了（>=0）时才有对拍意义：它是原版
                    // `ArgTypeList.OmitStart` 的精确文案位，未用时注册表留 -1（元数由 min/max 把关）。
                    if (m.omitStart >= 0 && expected.max > expected.min && expected.max >= 0 &&
                        expected.min != m.omitStart
                    ) {
                        omitBad += "${e.name}: args=${e.args} 必需前缀 ${expected.min}，注册表 omitStart=${m.omitStart}"
                    }
                }
            }
        }
        assertEquals(
            builtinArgsProse.keys, proseSeen.toSet(),
            "builtinArgsProse 的键必须在 builtin-functions.yaml 里（白名单不许有幽灵项）",
        )
        assertEquals(
            builtinArityDivergences.keys, divergenceSeen.toSet(),
            "builtinArityDivergences 的键必须在 builtin-functions.yaml 里（白名单不许有幽灵项）",
        )
        assertTrue(
            builtinArgsProse.keys.intersect(builtinArityDivergences.keys).isEmpty(),
            "同一函数不能同时在散文白名单与分歧白名单里",
        )
        assertTrue(
            arityBad.isEmpty(),
            "builtin-functions.yaml 的 args 元数与 BuiltinRegistry 不一致：\n  " + arityBad.joinToString("\n  "),
        )
        assertTrue(
            omitBad.isEmpty(),
            "builtin-functions.yaml 的必选/可选分界与 BuiltinRegistry.omitStart 不一致：\n  " +
                omitBad.joinToString("\n  "),
        )
    }

    @Test
    fun `builtin returns 类型与注册表及 BuiltinSymbols 一致`() {
        val yaml = parseBuiltins()
        val mismatches = ArrayList<String>()
        val symbolBad = ArrayList<String>()
        for (e in yaml) {
            val expected = returnTypeOf(e)
            val m = Builtins.registry[e.name]
            if (m == null) {
                mismatches += "${e.name}: 注册表缺失"
                continue
            }
            if (m.retType != expected) {
                mismatches += "${e.name}: returns=${e.returns} 判 $expected，注册表 ${m.retType}"
            }
            val static = BuiltinSymbols.functions[e.name]
            if (static != expected) {
                symbolBad += "${e.name}: returns=${e.returns} 判 $expected，BuiltinSymbols=${static ?: "<缺失>"}"
            }
        }
        assertTrue(
            mismatches.isEmpty(),
            "builtin-functions.yaml 的 returns 与 BuiltinRegistry.retType 不一致：\n  " +
                mismatches.joinToString("\n  "),
        )
        assertTrue(
            symbolBad.isEmpty(),
            "builtin-functions.yaml 的 returns 与 BuiltinSymbols.functions 不一致：\n  " +
                symbolBad.joinToString("\n  "),
        )
    }

    @Test
    fun `builtin 白名单不陈旧且条数固定`() {
        val names = parseBuiltins().map { it.name }.toSet()
        val stale = (builtinArgsProse.keys + builtinArityDivergences.keys).filter { it !in names }
        assertTrue(stale.isEmpty(), "builtin 白名单存在 specs 里已不存在的函数名（过期即红）：$stale")
        assertTrue(
            builtinArgsProse.values.all { it.reason.isNotBlank() } &&
                builtinArityDivergences.values.all { it.reason.isNotBlank() },
            "白名单每条都必须写原因（不许静默跳过）",
        )
        assertEquals(9, builtinArgsProse.size, "散文/欠表达 args 白名单条数")
        // N8 裁决后 4 条注册表分歧全部对齐 C#（见 builtinArityDivergences 的 KDoc）；再冒出新分歧必须显式登记。
        assertEquals(0, builtinArityDivergences.size, "注册表 vs C# 分歧白名单条数")
    }
}
