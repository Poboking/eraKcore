package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions

/** 临时调试：追踪单文件的块结构与解析异常。 */
class DebugReproTest {
    @Test
    fun trace() {
        val corpusEnv = System.getenv("ERATW_CORPUS")
        // CMP-68：两个前置都走 assumption（计 skipped），不许 println+return 静默 PASS。
        Assumptions.assumeTrue(
            !corpusEnv.isNullOrBlank(),
            "未设 ERATW_CORPUS：单文件追踪装置按假设跳过（计 skipped）",
        )
        val traceFile = System.getenv("ERATW_TRACE_FILE")
        Assumptions.assumeTrue(
            !traceFile.isNullOrBlank(),
            "未设 ERATW_TRACE_FILE：单文件追踪装置按假设跳过（计 skipped）",
        )
        val corpus = requireNotNull(corpusEnv)
        val target = requireNotNull(traceFile)
        val files = Files.walk(Path(corpus)).use { s ->
            s.filter { Files.isRegularFile(it) && (it.name.endsWith(".erb", true) || it.name.endsWith(".erh", true)) }
                .sorted().toList()
        }
        val pre = CorpusValidator.prescan(files)
        val resolver = CorpusValidator.ScopedResolver(pre)
        val parser = LineParser(resolver)
        val out = StringBuilder()
        val path = Path(corpus).resolve(target)
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(Files.readAllBytes(path)), target,
            renameDic = CorpusValidator.loadRenameDic(Path(corpus)),
        )
        val stack = ArrayDeque<String>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            val stmt = try {
                parser.parseLogicalLine(line)
            } catch (e: Exception) {
                out.appendLine("${line.lineNo + 1}: PARSE-FAIL ${e.message} | ${line.content.take(80)}")
                continue
            } ?: continue
            if (stmt is FuncLabelStmt) {
                resolver.enter(stmt.name)
                stack.clear()
                out.appendLine("${line.lineNo + 1}: @ ${stmt.name} (scope reset)")
                continue
            }
            when (stmt.role) {
                BlockRole.OPEN -> stack.addLast("${stmt.code.name}@${line.lineNo + 1}")
                BlockRole.BRANCH -> {
                    if (stack.isEmpty()) {
                        out.appendLine("${line.lineNo + 1}: BRANCH ${stmt.code} with EMPTY stack | ${line.content.take(60)}")
                    }
                }
                BlockRole.CLOSE -> {
                    if (stack.isEmpty()) {
                        out.appendLine("${line.lineNo + 1}: CLOSE ${stmt.code} with EMPTY stack | ${line.content.take(60)}")
                    } else if (stmt.code == FunctionCode.CATCH) {
                        stack.removeLast()
                        stack.addLast("CATCH@${line.lineNo + 1}")
                    } else {
                        val top = stack.removeLast()
                        val openName = top.substringBefore('@')
                        val expectedClose = runCatching { BlockTable.openToClose[FunctionCode.valueOf(openName)] }.getOrNull()
                        val ok = expectedClose == stmt.code || BlockTable.parentOf[stmt.code]?.name == openName
                        if (!ok) {
                            out.appendLine("${line.lineNo + 1}: CLOSE ${stmt.code} mismatched top=$top | ${line.content.take(60)}")
                        }
                    }
                }
                BlockRole.NONE -> {}
            }
            if (stmt.code in setOf(
                    FunctionCode.IF, FunctionCode.SELECTCASE, FunctionCode.FOR, FunctionCode.WHILE,
                    FunctionCode.DO, FunctionCode.REPEAT, FunctionCode.TRYCCALLFORM, FunctionCode.TRYCALLLIST,
                )
            ) {
                out.appendLine("${line.lineNo + 1}: ${stmt.code} depth=${stack.size} | ${line.content.take(60)}")
            }
        }
        out.appendLine("FINAL stack: ${stack.joinToString()}")
        // 落点固定在模块 build/ 下：本文件此前写共享根 `.tmp/trace.txt`（跨 agent 共享目录），
        // 启用追踪时会写他人工作区；改到 build 目录后不污染仓库也不写共享根。
        val traceOut = java.nio.file.Path.of("build/debug-trace/trace.txt")
        Files.createDirectories(traceOut.parent)
        Files.writeString(traceOut, out.toString())
    }
}
