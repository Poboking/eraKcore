package com.eratw.port.vmdbg

import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.nio.file.Files
import java.nio.file.Path

/**
 * 合成 mini 语料装置（CONTRACT-DBG1 路2 单测口径：临时目录写 mini ERB/ERH → erbcc 的
 * [CorpusCompile] + [ErbcArtifacts] 产 erbc.v1 目录 → vmdbg 装配 → 断言 CLI 输出；
 * 不依赖真语料与真产物，ERATW_* 环境变量一概不用）。
 *
 * 函数清单与用途（见 [MINI_ERB]）：
 * - `MINI_PASS`：PRINTL 后正常返回 —— call/sweep 的 PASS 面；
 * - `MINI_ARGS(ARG, ARGS)`：1 整形 + 1 字符串形参（argIntCount=1 / argStrCount=1，
 *   Symbols.kt:1224 按 ARG/ARGS 桶名分桶），体里 PRINTVL/PRINTSL + SAVEGLOBAL ——
 *   实参伪造面 + **存档沙箱的写路径**（SAVEGLOBAL 落 global.sav，语料树无新文件断言
 *   的对手方）；PRINT 要带 L 后缀，否则行缓冲不 flush、控制台尾快照为空；
 * - `MINI_CODEEE`：CALLFORM 运行期解析到不存在的函数 → CodeEE（未定义函数）—— CODEEE 面；
 * - `MINI_METHOD(ARG)`：`#FUNCTION` 方法 —— --skip-methods 面（callFunction 对方法名恒
 *   methodCallError，ProcessRuntime.kt:1455）；
 * - `MINI_INPUTLOOP`：FOR 百万圈、每圈 `INPUT LX` —— **输入路径墙钟闸的回归面**：
 *   输入是让出边界（StatementExecutor.waitInput 让步数闸重算）、纯步数闸永不超限；
 *   会话不吃立即应答 sink 时本函数直接 park 死在 QueueInputSink 上
 *   （2026-10-25 真语料全量 sweep 首跑 BATTLE_ATK 的实锤形态）；
 *   局部名**不能用单字母**：原版 `VariableData.cs:3040-3063` 把 A-Z 全册为系统变量，
 *   `#DIM DYNAMIC I` 在原版同样是硬错（`IdentifierDictionary.cs:279-282`
 *   CheckUserVarName → VarNameAlreadyUsed），CORE-06 保留名对齐后本装置会原地踩雷；
 * - `EVENTFIRST`：事件函数 —— sweep 必须跳过（isEvent），且 erbcc 合成的事件入口 meta
 *   （position = ·event-entry·，CorpusCompile.kt:855）同样必须跳过；
 * - `GLOBAL_TOTAL`：**ERH** 里的全局 `#DIM`（CMP-89：ERB 函数外的 #DIM 被原版语义丢弃、
 *   全局用户变量只从 ERH 装载，SystemVariableBootstrap.kt:171-179）—— var/vars 命令的
 *   数据面（私有 #DIM 出帧即失，不能当 var 的断言对象）。
 *
 * 形参不用 `名字$` 后缀：本移植词法器在该位置拒绝 `$`（真语料也从未使用）。
 */
object MiniCorpus {

    /** 全局用户变量声明（ERH；全局 #DIM 只从 ERH 装载）。 */
    const val MINI_ERH = """; vmdbg 合成 mini 语料的全局面
#DIM GLOBAL_TOTAL
"""

    /** TAB 缩进，与 fixtures/golden 同款。 */
    const val MINI_ERB = """; vmdbg 合成 mini 语料（CONTRACT-DBG1 路2 单测装置）
@MINI_PASS
	PRINTL mini-pass-ok

@MINI_ARGS(ARG, ARGS)
	PRINTVL ARG
	PRINTSL ARGS
	SAVEGLOBAL

@MINI_CODEEE
	CALLFORM MINI_NOPE_XYZ

@MINI_METHOD(ARG)
	#FUNCTION
	RETURNF ARG + 1

@MINI_INPUTLOOP
	#DIM DYNAMIC LI
	#DIM DYNAMIC LX
	FOR LI, 0, 1000000
		INPUT LX
	NEXT

@EVENTFIRST
	PRINTL mini-event
"""

    /** 装置产物：游戏根（只读面）+ erbc.v1 目录。 */
    class Built(val gameRoot: Path, val erbcDir: Path) {
        /** 游戏树快照（相对路径 → 字节数；sweep 后断言无变化）。 */
        fun treeSnapshot(): Map<String, Long> {
            val root = gameRoot.toAbsolutePath().normalize()
            return Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .map { Pair(root.relativize(it).toString().replace('\\', '/'), Files.size(it)) }
                    .toList()
                    .toMap()
            }
        }
    }

    /** 在 [tmp] 下建 `game/`（含空 emuera.config + ERH + ERB/mini.erb）并编译出 erbc.v1。 */
    fun build(tmp: Path): Built {
        val game = tmp.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        // 空配置：三层配置链读不到键时全部走注册缺省（RootedBuiltinHost.applyChainTo 容缺）。
        Files.writeString(game.resolve("emuera.config"), "")
        Files.writeString(erbDir.resolve("mini.erh"), MINI_ERH)
        Files.writeString(erbDir.resolve("mini.erb"), MINI_ERB)
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "mini 语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = tmp.resolve("erbc-out")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        return Built(game, outDir.resolve("erbc.v1"))
    }

    /** 组一组 CLI 参数（--erbc/--game/--work 前置 + 子命令）。 */
    fun args(built: Built, work: Path, vararg rest: String): Array<String> =
        arrayOf("--erbc", built.erbcDir.toString(), "--game", built.gameRoot.toString(), "--work", work.toString()) + rest
}
