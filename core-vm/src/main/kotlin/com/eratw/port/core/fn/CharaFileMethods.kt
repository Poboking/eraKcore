package com.eratw.port.core.fn

import com.eratw.port.core.data.save.GameBaseGate
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.save.SaveDataProbe

/**
 * 角色存档文件家族（[registerCharaFileMethods]）。**文件所有者：A8**（M3-B 第二波）。
 *
 * 落地名单：`FIND_CHARADATA` / `CHKCHARADATA`。
 * `SAVECHARA` / `LOADCHARA` 是语句（OpcodeTable.SAVECHARA/LOADCHARA），归语句层；
 * 二进制内核见 [SaveFormat]（本代理独占 `core/data/save`）。
 *
 * 权威实现与逐条口径：
 * - `FIND_CHARADATA([pattern])` = `FindFilesMethod(CharVar)`
 *   （FunctionMethodCreator.cs:3562-3604，注册 :9080）：
 *   1. `pattern` 缺省 `"*"`（:3586-3590）；
 *   2. `VariableEvaluator.GetDatFiles(true, pattern)`（:3591）→ 目录 `Program.DatDir`
 *      （:2076-2102），搜索模式 `"chara_" + pattern + ".dat"`（:2083-2088）；
 *   3. 只取扩展名为 `.dat` 的文件（OrdinalIgnoreCase，:2093），
 *      `Path.GetFileNameWithoutExtension` 去掉**最后一个**扩展名后再 `Substring(6)`
 *      去掉 `chara_` 前缀（:2094-2097），空名丢弃（:2098）；
 *   4. 结果原样复制进 `DataStringArray[2]`（即 `RESULTS`），超出部分截断（:3592-3600）；
 *   5. 返回**命中总数**（不截断，:3601）。
 *
 *   注意原版坑（照抄）：`SAVECHARA "A.dat"` 会写出 `chara_A.dat.dat`，
 *   于是 `FIND_CHARADATA "A*"` 返回的是 `A.dat` 而不是 `A`——基准
 *   `pc-golden-root.txt:495` 的 `F0=GOLDEN_CHARA.dat` 正是这个结果。
 *
 * - `CHKCHARADATA(name)` = `CheckdataStrMethod(CharVar)`
 *   （FunctionMethodCreator.cs:3541-3560，注册 :9079，实参 `string` :3548）：
 *   1. 文件名 = `{DatDir}chara_{name}.dat`（`CheckData(string, CharVar)` 走
 *      `getSaveDataPathC(string)`，VariableEvaluator.cs:2131-2133 + :2054-2057；**只组一条路径**）；
 *   2. `CheckDataByFilename`（VariableEvaluator.cs:2159-2229）判定序（码 ↔ 文案一一对应，
 *      文案是使用者面 ⇒ 中文，逐字取 `lang.emuera-zhs.xml`）：
 *      文件不存在 → `FILENOTFOUND(1)` + 字面量 `"----"`（:2162-2166，不走语言包）；
 *      文本格式（`EraBinaryDataReader.CreateReader` == null，:2176-2195）**没有类别可比**，
 *      直接 UniqueCode / 版本两判（旧版 `SAVECHARA` 在 `SystemSaveInBinary=NO` 时写的就是这种）；
 *      二进制头类别不是 CharVar → `ETC_ERROR(4)` + `Error.CorruptedSaveData`
 *      「存档数据已损坏」（:2196-2202，语言包 :328；声明侧 Lang.cs:1494）；
 *      UniqueCode 不符 → `GAME_ERROR(2)` + `Error.DifferentGame`
 *      「试图读取的存档属于另一个游戏」（:2203-2208，语言包 :326；Lang.cs:1486；
 *      判定本体 GameBase.cs:60-67，存档码 0 豁免）；
 *      版本不符 → `VIRSION_ERROR(3)` + `Error.DifferentVersion`「存档版本不同」
 *      （:2209-2215，语言包 :327；Lang.cs:1490；判定本体 GameBase.cs:69-80）；
 *      成功 → `OK(0)` + 存档描述文本（:2216-2218）；
 *   3. `RESULTS = DataMes` 后返回 `(long)State`（FunctionMethodCreator.cs:3557-3558；
 *      `RESULTS` 的 setter 只写 `DataStringArray[2][0]`，VariableEvaluator.cs:75-78）。
 *
 *   文案常量与 `CHKDATA` 共用同一取值点 [SaveDataProbe]（MES_NOT_FOUND / MES_CORRUPTED /
 *   MES_DIFFERENT_GAME / MES_DIFFERENT_VERSION / MES_LOAD_ERROR），不再在本文件各写一份
 *   （SAVE-05：改前这里是自造措辞「存档文件已损坏」「存档的游戏代码不同」；「存档版本不同」
 *   那条本来就与官方一致）。
 *   已知残余差异（状态码同为 `ETC_ERROR`，只有文案差）：原版「容器读不出」走的是
 *   `catch (FileEE)` / `catch (Exception)` 两支（VariableEvaluator.cs:2220-2229），文案是
 *   `FileEE.Message` 或 `Error.LoadError`「加载时发生错误」（语言包 :329）；本层与
 *   [SaveDataProbe.checkBytes] 同口径统一给 MES_CORRUPTED。触发条件是「既不是二进制容器、
 *   文本前三行又读不出两个数字」的畸形/截断档（含 <16 字节档，即 SAVE-06），
 *   与 [com.eratw.port.core.save.SaveDataProbe] 类 KDoc 记的是同一处，修法归那一侧。
 *
 *   另一支「文件在、字节读不出」（权限 / 坏盘 / 宿主读失败）**不再抛 CodeEE**：原版这一支
 *   与上面同属 `catch (Exception)` ⇒ `ETC_ERROR` + `Error.LoadError`（VariableEvaluator.cs:2226-2229），
 *   且 `CHKDATA` 走的 [SaveDataProbe.check] 早已按此口径给
 *   [SaveDataProbe.MES_LOAD_ERROR]（`core/save/SaveDataProbe.kt:203-204`）。两个函数在原版里
 *   是**同一个** `CheckDataByFilename`（:2159-2229），移植侧不能一族静默折码、另一族把
 *   「宿主没接线」的 CodeEE 甩到屏上并中止脚本。
 *
 * 基准：`fixtures/golden/baseline/pc-golden-root.txt:493-498`
 * （`FIND_CHARADATA=1 F0=GOLDEN_CHARA.dat` / `CHKCHARADATA=0`）。文案只在**非 OK 支**变化，
 * 基准走的是 OK 支（描述文本原样进 RESULTS），开局读数不变。
 */
internal fun registerCharaFileMethods(r: BuiltinRegistry) {
    val I = OperandType.INT

    // FIND_CHARADATA([pattern])：返回命中数，名字写入 RESULTS。spec: FunctionMethodCreator.cs:3562-3604
    r.register(BuiltinMethod("FIND_CHARADATA", I, 0, 1) { c, a ->
        val ops = c.host.fileOps
            ?: throw CodeEE("函数 FIND_CHARADATA 需要宿主接线 FileOps（fileOps == null）")
        val pattern = if (a.isNotEmpty() && a[0] != null) sv(a[0]!!) else "*"
        val dir = c.host.datDir.trimEnd('/', '\\')
        // Directory.GetFiles(DatDir, "chara_" + pattern + ".dat", TopDirectoryOnly)：:2091
        val files = ops.list(dir, "chara_$pattern.dat", recursive = false)

        val names = ArrayList<String>(files.size)
        for (path in files) {
            val file = path.substringAfterLast('/').substringAfterLast('\\')
            if (file.length <= 4) continue
            // Path.GetExtension(path).Equals(".dat", OrdinalIgnoreCase)：:2093
            if (!file.substring(file.length - 4).equals(".dat", ignoreCase = true)) continue
            // GetFileNameWithoutExtension（去掉最后一个扩展名）再 Substring(6)：:2094-2096
            val stem = file.substring(0, file.length - 4)
            if (stem.length <= CHARA_PREFIX.length) continue
            val bare = stem.substring(CHARA_PREFIX.length)
            if (bare.isEmpty()) continue // :2098 IsNullOrEmpty
            names.add(bare)
        }

        // datFiles.CopyTo(DataStringArray[2])：:3592-3600（超长截断，不清空其余项）
        val destLen = c.store.getLength("RESULTS", -1)
        val n = minOf(names.size, destLen)
        for (i in 0 until n) {
            c.store.writeStr("RESULTS", longArrayOf(i.toLong()), names[i])
        }
        Value.IntV(names.size.toLong()) // :3601
    })

    // CHKCHARADATA(name)：返回 (long)EraDataState，RESULTS = 描述。spec: FunctionMethodCreator.cs:3541-3560
    r.register(BuiltinMethod("CHKCHARADATA", I, 1, 1) { c, a ->
        val ops = c.host.fileOps
            ?: throw CodeEE("函数 CHKCHARADATA 需要宿主接线 FileOps（fileOps == null）")
        val name = requiredStrArg(a, 0, "CHKCHARADATA")
        val result = checkCharaData(c, ops, name)
        if (c.store.getLength("RESULTS", -1) > 0) {
            c.store.writeStr("RESULTS", longArrayOf(0), result.mes) // :3557
        }
        Value.IntV(result.state.code) // :3558
    })
}

/** `chara_` 前缀（`getSaveDataPathC` VariableEvaluator.cs:2044-2047 / :2054-2057；`GetDatFiles` :2086 + :2094）。 */
private const val CHARA_PREFIX = "chara_"

/**
 * 角色存档头探测。逐分支（码 + 官方文案）对应 `CheckDataByFilename`
 * （VariableEvaluator.cs:2159-2229），文案常量与 `CHKDATA` 同一取值点 [SaveDataProbe]；
 * 判据与差异说明见 [registerCharaFileMethods] 的类 KDoc。
 *
 * 读盘失败（`readFileBytes` 给 null **或直接抛异常**：权限 / 坏盘 / 宿主没装二进制读）**不抛错**：
 * 文件已由 [FileOps.exists] 判过存在，剩下的失败按原版 `catch (Exception)`
 * （VariableEvaluator.cs:2226-2229）折成 `ETC_ERROR` + `Error.LoadError`
 * 「加载时发生错误」，与 [SaveDataProbe.check] 的同名分支一字不差
 * （`core/save/SaveDataProbe.kt:203-204`）。抛 CodeEE 会把「一个坏档」升级成
 * 「本条脚本整体中止 + 红字上屏」，而原版只是让 CHKCHARADATA 返回 4。
 *
 * 唯一的宿主能力缺口（`fileOps == null`）在调用方已先行拦下。
 */
private fun checkCharaData(
    c: BuiltinContext,
    ops: com.eratw.port.core.host.FileOps,
    name: String,
): SaveDataCheck {
    val dir = c.host.datDir.trimEnd('/', '\\')
    val path = "$dir/$CHARA_PREFIX$name.dat" // getSaveDataPathC(string)：:2054-2057

    if (!ops.exists(path)) {
        return SaveDataCheck(EraDataState.FILENOTFOUND, SaveDataProbe.MES_NOT_FOUND) // :2162-2166
    }
    val bytes = try {
        c.host.readFileBytes(path)
    } catch (_: Exception) {
        null // :2226-2229 —— 与原版 catch (Exception) 同路：读失败不冒到语句层
    } ?: return SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_LOAD_ERROR) // :2226-2229
    // 列表/单格探测只要头：`CHKDATA`/`CHKCHARADATA` 在存档列表里成批调用，
    // 不在这里为读几个头字段把整份角色档解压一遍（2026-10-31 性能修）。
    // 代价：头完好、GZip 尾部损坏的档在这里仍显示为 OK；真正 `LOADCHARA` 由
    // CharaFileIO.isBinaryContainer 全量校验后静默 `RESULT = 0`（与原版同一结局）。
    //
    // 合并取舍（2026-11-01）：`readFileBytes` 返回 null 一律折成 MES_LOAD_ERROR，
    // 不再像 origin 侧那样 `throw CodeEE("需要宿主接线")` —— L4 稳定性波的口径是
    // 「读失败不冒到语句层」（原版 :2226-2229 就是 catch 后折 ETC_ERROR），而宿主
    // 未接线在装机链上不可达、在测试夹具里由 `Builtins.readFileBytes` 的默认 null
    // 直接覆盖。两处口径不能并存，取折码这一侧。
    val head = SaveFormat.peekHeader(bytes)
        ?: return SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_CORRUPTED) // :2199-2201 口径，见类 KDoc

    // :2196-2202 —— 类别只在**二进制容器**里比；文本格式分支（:2176-2195）没有类别可比，
    // 直接读 UniqueCode / ScriptVersion（与 SaveDataProbe.checkBytes 同一判据）。
    if (head.binary && head.fileType != SaveFileKind.CHARVAR) {
        return SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_CORRUPTED) // :2197-2202
    }
    val gameBase = c.store.registry.gameBase
    if (!GameBaseGate.uniqueCodeEqualTo(gameBase, head.uniqueCode)) {
        return SaveDataCheck(EraDataState.GAME_ERROR, SaveDataProbe.MES_DIFFERENT_GAME) // :2203-2208
    }
    if (!GameBaseGate.checkVersion(gameBase, head.scriptVersion)) {
        return SaveDataCheck(EraDataState.VIRSION_ERROR, SaveDataProbe.MES_DIFFERENT_VERSION) // :2209-2215
    }
    return SaveDataCheck(EraDataState.OK, head.text) // :2216-2218
}

/**
 * 取字符串参数直接用 PureMethods.kt 的同包 internal `sv(v: Value)`
 * （本文件不再私有定义——同包同签名会 conflicting overloads）。
 */

