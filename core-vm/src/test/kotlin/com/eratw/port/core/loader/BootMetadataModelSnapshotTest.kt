package com.eratw.port.core.loader

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimSpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 读侧表示形态的**模型快照闸**（mem4）。
 *
 * 存在的理由：mem4 只许动「表示形态与生命周期」（流式记录、记号不落地成 String），
 * 一个字都不许动行为。判据不能靠 `encodeText()` 闭环（那只会把同一个错读两次都对上），
 * 必须是**改前落一份逐字段快照、改后与同一份侧车比对，字节恒等**。
 *
 * 快照口径：
 * - 模型的全部字段，按**迭代序**展开（`labels` / `fileByKey` / `dataSections` 是 LinkedHashMap，
 *   插入序本身就是模型形状的一部分）；`Expr` 用显式递归（`toString` 会把未转义的换行写进快照，
 *   破坏行结构，因此这里自己拼、所有字符串一律走 [esc]）。
 * - 末尾一段**身份 census**：函数键 / 书写名 / 源文件值 / 标签名 这几张表里 `String` 的
 *   **不同实例数**。驻留池（`keyPool` / `pathPool`）一旦被拆掉，内容快照不变而 census 会变，
 *   这一行就是那个回归面（`FuncKeyInternTest` 只覆盖合成小样本，真产物的重复率要在这里钉）。
 *
 * 用法：
 * ```
 * ERATW_ERBC_DIR=<含 program.meta 的目录> \
 * ERATW_SNAPSHOT_WRITE=1 ./gradlew --configure-on-demand :core-vm:test --tests "*BootMetadataModelSnapshot*" -i   # 落基线
 * ERATW_ERBC_DIR=... ./gradlew --configure-on-demand :core-vm:test --tests "*BootMetadataModelSnapshot*" -i        # 比对
 * ```
 * 快照落 `core-vm/build/bootmetadata-snapshot.txt`（30 MiB 量级，build 目录不入库）。
 * 未设环境变量时跳过；设了环境变量但快照不存在且没带写旗 → 直接失败（闸不许静默空转）。
 */
class BootMetadataModelSnapshotTest {

    private fun realMetaArtifact(): Path? {
        val dir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() }?.let {
                val p = Path.of(it)
                if (Files.isDirectory(p)) p else p.parent
            } ?: return null
        if (!Files.isDirectory(dir)) return null
        return dir.resolve("program.meta").takeIf { Files.isRegularFile(it) }
    }

    private fun snapshotPath(): Path =
        System.getProperty("bootmeta.snapshot")?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: Path.of("build", "bootmetadata-snapshot.txt")

    /** 入库指纹（sha256 + 字节数）：49.8 MB 全量快照不入库，便携判据只有这一小文件。 */
    private fun fingerprintPath(): Path =
        Path.of("src", "test", "fixtures", "bootmetadata-snapshot.fingerprint.txt")

    @Test
    fun `真侧车解析出的模型与基线快照逐字节相同`() {
        val meta = realMetaArtifact()
        assumeTrue(meta != null, "ERATW_ERBC / ERATW_ERBC_DIR 未指向含 program.meta 的目录：跳过")
        val path = snapshotPath()
        val writing = System.getenv("ERATW_SNAPSHOT_WRITE") != null
        val model = BootMetadata.read(meta!!)

        val sb = StringBuilder(1 shl 20)
        dump(model, sb)
        val text = sb.toString()
        println("[bootmeta-snapshot] 模型 ${meta.fileName} → 快照 ${text.length} 字符 / ${path}")

        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fp = fingerprintPath()
        // 指纹绑定**具体产物**：模型快照的正文里含 `sha <programSha256>` 与全部侧车内容，
        // 换一个 program.meta 就不再是同一份对照。因此指纹文件带一行 `artifact=`，
        // 产物对不上就**显式跳过**（不是绿、也不是红）：这条闸只对 bless 时那份产物有判定力。
        val artifactId = model.programSha256 ?: "-"
        if (writing) {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            Files.createDirectories(fp.parent)
            Files.writeString(fp, "artifact=$artifactId\nsha256=$digest\nbytes=${bytes.size}\n")
            println("[bootmeta-snapshot] 写入基线：${Files.size(path)} B / 指纹 $fp（artifact=$artifactId）")
            return
        }
        // 入库指纹恒在：换机、删掉 build/ 大快照也照样守得住；模型一变就红，重 bless 必须显式做。
        assertTrue(Files.isRegularFile(fp), "入库指纹缺失：$fp（模型漂移要显式重 bless：ERATW_SNAPSHOT_WRITE=1 后提交指纹文件）")
        val fpText = Files.readString(fp)
        val boundArtifact = Regex("artifact=([^\\r\\n]+)").find(fpText)?.groupValues?.get(1)
        assumeTrue(
            boundArtifact == null || boundArtifact == artifactId,
            "产物不匹配：指纹绑定 artifact=$boundArtifact，当前=$artifactId —— " +
                "这条闸只对 bless 时那份 program.meta 有判定力；换产物要么指向原产物，要么 ERATW_SNAPSHOT_WRITE=1 重 bless",
        )
        assertEquals(
            Regex("sha256=([0-9a-f]{64})").find(fpText)?.groupValues?.get(1),
            digest,
            "模型指纹与入库值不同：装载回来的元数据变了（本地若有 build/ 大快照会给首个差异）",
        )
        if (!Files.isRegularFile(path)) {
            println("[bootmeta-snapshot] 本地大快照不在（$path）：入库指纹已过，逐字节诊断跳过")
            return
        }
        val baseline = Files.readString(path)
        if (baseline == text) return
        val diff = firstDiff(baseline, text)
        throw AssertionError("模型与基线快照不一致（首个差异 $diff）\n基线 ${baseline.length} 字符 / 本次 ${text.length} 字符")
    }

    /** 二次编码闭环（同一模型再走一遍 文本→模型→文本，与快照互为交叉验证）。 */
    @Test
    fun `真侧车模型再编码逐字节闭环`() {
        val meta = realMetaArtifact()
        assumeTrue(meta != null, "缺 program.meta：跳过")
        val model = BootMetadata.read(meta!!)
        val once = model.encodeText()
        val again = BootMetadata.parse(once).encodeText()
        assertEquals(once, again, "文本→模型→文本 必须逐字节闭环")
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    private fun dump(m: BootMetadata, out: StringBuilder) {
        out.append("engine ").append(esc(m.engineTag)).append('\n')
        out.append("sha ").append(m.programSha256?.let(::esc) ?: "-").append('\n')

        out.append("labels ").append(m.labels.size).append('\n')
        for ((k, table) in m.labels) {
            out.append("labelset ").append(esc(k)).append(' ').append(table.size).append('\n')
            for ((n, pc) in table) out.append("label ").append(esc(n)).append(' ').append(pc).append('\n')
        }

        out.append("files ").append(m.fileByKey.size).append('\n')
        for ((k, v) in m.fileByKey) out.append("file ").append(esc(k)).append(' ').append(esc(v)).append('\n')

        out.append("dataSections ").append(m.dataSections.size).append('\n')
        for ((idx, groups) in m.dataSections) {
            out.append("datasec ").append(idx).append(' ').append(groups.size).append('\n')
            for (g in groups) {
                out.append("group ").append(g.size).append('\n')
                for (e in g) out.append("expr ").append(exprDump(e)).append('\n')
            }
        }

        out.append("functions ").append(m.functions.size).append('\n')
        for (f in m.functions) {
            out.append("function ").append(f.index).append(' ').append(f.defIndex).append(' ')
                .append(esc(f.key)).append(' ').append(esc(f.name)).append(' ')
                .append(b(f.isEvent)).append(b(f.isMethod)).append(b(f.isOnly)).append(b(f.isPri))
                .append(b(f.isLater)).append(b(f.isSingle)).append(b(f.returnsStr)).append(' ')
                .append(f.argIntCount).append(' ').append(f.argStrCount).append(' ')
                .append(f.localSize).append(' ').append(f.localsSize).append(' ')
                .append(esc(f.position)).append('\n')
        }

        out.append("globalDims ").append(m.globalDims.size).append('\n')
        for (d in m.globalDims) {
            out.append("global-dim ").append(esc(d.file)).append(' ').append(d.line).append(' ')
                .append(esc(d.raw)).append('\n')
        }

        out.append("privateDims ").append(m.privateDims.size).append('\n')
        for (p in m.privateDims) {
            out.append("private-dim ").append(esc(p.functionKey)).append(' ').append(dimSpec(p.spec)).append('\n')
        }

        val cfg = m.dimParseConfig
        out.append("dim-config ").append(b(cfg.systemSaveInBinary)).append('\n')
        out.append("macros ").append(cfg.macros.size).append('\n')
        for ((k, v) in cfg.macros) out.append("macro ").append(esc(k)).append(' ').append(esc(v)).append('\n')
        out.append("constants ").append(cfg.intConstants.size).append('\n')
        for ((k, v) in cfg.intConstants) out.append("const ").append(esc(k)).append(' ').append(v).append('\n')
        out.append("strConstants ").append(cfg.strConstants.size).append('\n')
        for ((k, v) in cfg.strConstants) out.append("str-const ").append(esc(k)).append(' ').append(esc(v)).append('\n')

        val w = m.loadWarnings
        out.append("loadWarnings ").append(if (w == null) "null" else w.size.toString()).append('\n')
        if (w != null) {
            for (x in w) out.append("load-warn ").append(esc(x.file)).append(' ').append(x.line).append(' ')
                .append(esc(x.message)).append('\n')
        }

        identityCensus(m, out)
    }

    private fun dimSpec(s: DimSpec): String = buildString {
        append(esc(s.name)).append(" str=").append(b(s.isStr))
        append(" dims=").append(s.dims.toList())
        append(" const=").append(b(s.isConst)).append(" ref=").append(b(s.isRef))
        append(" global=").append(b(s.isGlobal)).append(" save=").append(b(s.isSavedata))
        append(" chara=").append(b(s.isCharaData)).append(" static=").append(b(s.isStatic))
        append(" private=").append(b(s.isPrivate))
        append(" defInt=").append(s.defaultsInt?.let { "[${it.joinToString(",")}]" } ?: "null")
        append(" defStr=").append(s.defaultsStr?.joinToString(",", "[", "]") { esc(it) } ?: "null")
    }

    /** 显式递归（覆盖 sealed Expr 的 11 种节点；新增节点会在 `when` 上编译失败）。 */
    private fun exprDump(e: Expr): String = when (e) {
        is IntLiteral -> "int ${e.value} ${e.line} ${e.col}"
        is StrLiteral -> "str ${esc(e.value)} ${e.line} ${e.col}"
        is NullTerm -> "null ${b(e.isInt)} ${e.line} ${e.col}"
        is VarRef -> "var ${esc(e.name)} ${e.subKey?.let(::esc) ?: "-"} ${e.operandType} " +
            "${b(e.isNoArg)} ${b(e.isConst)} ${e.line} ${e.col} [${e.indices.joinToString(";") { exprDump(it) }}]"
        is KeywordIndexArg -> "kidx ${esc(e.varName)} ${e.dim} ${e.line} ${e.col} {${exprDump(e.expr)}}"
        is FuncCall -> "call ${esc(e.name)} ${e.operandType} ${e.line} ${e.col} [" +
            e.args.joinToString(";") { it?.let(::exprDump) ?: "null" } + "]"
        is Unary -> "unary ${e.op} ${e.line} ${e.col} {${exprDump(e.operand)}}"
        is UnaryAfter -> "unarya ${e.op} ${e.line} ${e.col} {${exprDump(e.operand)}}"
        is Binary -> "binary ${e.op} ${e.operandType} ${e.line} ${e.col} {${exprDump(e.left)}} {${exprDump(e.right)}}"
        is Ternary -> "tern ${e.operandType} ${e.line} ${e.col} {${exprDump(e.cond)}} {${exprDump(e.ifTrue)}}" +
            " {${exprDump(e.ifFalse)}}"
        is FormStringTerm -> "form ${e.line} ${e.col} [" + e.parts.joinToString(";") { partDump(it) } + "]"
    }

    private fun partDump(p: FormPart): String = when (p) {
        is FormPart.Text -> "t ${esc(p.str)}"
        is FormPart.Embedded -> "e {${exprDump(p.ast)}} ${convDump(p.conversion)}"
    }

    private fun convDump(c: FormConversion): String = when (c) {
        is FormConversion.Curly -> "curly ${c.width?.let { "{${exprDump(it)}}" } ?: "-"} ${b(c.alignLeft)}"
        is FormConversion.Percent -> "percent ${c.width?.let { "{${exprDump(it)}}" } ?: "-"} ${b(c.alignLeft)}"
        is FormConversion.YenAt -> "yenat {${exprDump(c.ifTrue)}} {${exprDump(c.ifFalse)}}"
    }

    /**
     * 身份 census：各表里 `String` 的**不同实例数**（内容相同但实例不同 = 驻留池没生效）。
     *
     * `IdentityHashMap` 按 `===` 归组，与内容无关，因此这一行只反映生命周期/共享，不反映值。
     */
    private fun identityCensus(m: BootMetadata, out: StringBuilder) {
        fun census(vararg groups: List<String>): Pair<Int, Int> {
            val seen = IdentityHashMap<String, Boolean>()
            var total = 0
            for (g in groups) {
                total += g.size
                for (s in g) seen.put(s, true)
            }
            return total to seen.size
        }
        val labelKeys = m.labels.keys.toList()
        val labelNames = ArrayList<String>()
        for (t in m.labels.values) labelNames.addAll(t.keys)
        val fileKeys = m.fileByKey.keys.toList()
        val fileVals = m.fileByKey.values.toList()
        val funcKeys = m.functions.map(FuncMeta::key)
        val funcNames = m.functions.map(FuncMeta::name)
        val privKeys = m.privateDims.map { it.functionKey }
        val globalFiles = m.globalDims.map { it.file }

        for ((name, c) in listOf(
            "labels.keys" to census(labelKeys),
            "labels.names" to census(labelNames),
            "fileByKey.keys" to census(fileKeys),
            "fileByKey.values" to census(fileVals),
            "functions.keys" to census(funcKeys),
            "functions.names" to census(funcNames),
            "privateDims.functionKey" to census(privKeys),
            "globalDims.file" to census(globalFiles),
            "keys+files+funcs" to census(labelKeys, fileKeys, funcKeys, privKeys),
            "paths" to census(fileVals, globalFiles),
        )) {
            out.append("identity ").append(name).append(" total=").append(c.first)
                .append(" distinct=").append(c.second).append('\n')
        }
    }

    private fun b(v: Boolean): String = if (v) "1" else "0"

    /** 快照行的转义：反斜杠先走，保证 `esc` 自身可逆、快照永远是行结构。 */
    private fun esc(s: String): String {
        var plain = true
        for (c in s) {
            if (c == '\\' || c == '\n' || c == '\r' || c == '\t' || c.code < 0x20 || c.code == 0x7f) {
                plain = false
                break
            }
        }
        if (plain) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20 || c.code == 0x7f) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    private fun firstDiff(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        val line = a.substring(0, minOf(i, a.length)).count { it == '\n' } + 1
        fun ctx(s: String): String = s.substring(i, minOf(s.length, i + 120)).replace('\n', '␊')
        return "第 $line 行 / 字符 $i；基线「${ctx(a)}」 vs 本次「${ctx(b)}」"
    }
}
