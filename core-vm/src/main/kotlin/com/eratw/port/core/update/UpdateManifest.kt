package com.eratw.port.core.update

/**
 * 国内镜像仓**更新清单**（发布物里的 `manifest.txt`）的数据模型与解析。
 *
 * ## 为什么要这份清单
 *
 * 侧载分发的包里，743 MiB 绝大部分是随包数据（`program.erbc` + 语料 + 立绘），dex 只占几个 MiB。
 * 玩家在国内拿不到 GitHub Releases（本仓实测 443 间歇不可达），所以更新源只能是「任何一个
 * 能放静态文件的国内镜像目录」：阿里云 OSS、自建 nginx、对象存储的公开读桶都算。
 * 这类源只提供**目录 + 文件名**，没有 API、没有版本接口 —— 于是版本判定只能是
 * 「客户端取一份纯文本清单，和本地读数比一比」。清单就是这条通道的全部契约。
 *
 * ## 文本形态（与 `erbc.v1/package.report` 同族：一行一条「键 值」，值里允许空格）
 *
 * ```text
 * update-format 1
 * generated 2026-10-29T08:00:00Z
 * apk-version-code 6
 * apk-version-name 0.2.4
 * apk-file eratw-0.2.4+6-abc1234-full-deadbeef.apk
 * apk-size 778451234
 * apk-sha256 <64 位十六进制>
 * assets-version 6
 * assets-file eratw-assets-6.zip
 * assets-size 712345678
 * assets-sha256 <64 位十六进制>
 * assets-full-file eratw-assets-6.zip
 * assets-full-size 712345678
 * assets-full-sha256 <64 位十六进制>
 * assets-delta-file eratw-assets-6to7.zip
 * assets-delta-size 10485760
 * assets-delta-sha256 <64 位十六进制>
 * assets-base-version 6
 * assets-blockdelta-file eratw-assets-blockdelta-7.zip
 * assets-blockdelta-size 104857
 * assets-blockdelta-sha256 <64 位十六进制>
 * note 修掉读档 256 MiB 预算误判
 * rollout-percentage 10
 * mirror https://mirror-a.example/eratw/
 * mirror https://mirror-b.example/eratw/
 * ```
 *
 * `apk-*` 与 `assets-*` 是**两条独立通道**：整包换章时只发 apk 组，只换语料/立绘/产物时只发
 * assets 组。两组各自「要么齐全、要么整组缺席」，缺半组是发布事故（清单写了文件名却没 sha，
 * 客户端就无从校验），所以本解析器直接判**拒**，不猜。
 *
 * ## 差量数据包那一组键（`update-format: 1` 内的**可选**扩展）
 *
 * `assets-full-file/size/sha256`、`assets-delta-file/size/sha256`、`assets-base-version` 三组
 * 全是可选键，字段名由波次契约 CONTRACT-UPDATE §1.2 钉死（解析侧、应用侧、发布侧一字不差）。
 * 可选不是客气，是**兼容性的硬要求**：线上已经发出去的 0.2.32 包读的是没有这些键的清单，
 * 而新清单同时也会被那些老客户端读到 —— 于是这里的口径是「缺省即回落全量，缺半组或形状
 * 不对也只记 warning 并把那一组当缺席」。判成拒收的后果是整条更新通道对玩家哑掉，
 * 而哑掉的代价（拿不到修复）比多下 ≈680 MiB 大得多。
 * 回落与「差量能不能用」的判定全在 [UpdatePolicy]（[UpdatePolicy.dataArtifactFor]），本类只负责读。
 *
 * ### 块级差量那一组键（`assets-blockdelta-file` / `-size` / `-sha256`，P2-13）
 *
 * 与上面三组同一条口径：**整组缺席 = 这台设备的清单没有块级档**（不是错误、不告警）；缺半组或
 * 形状不对只记 warning 并当成缺席。读法直接复用 [BlockDeltaManifest.parse]，本类不另写一份判式 ——
 * 两处判式不一致会得到「发布侧写得出、设备侧读不成」的形状，那种差量静默回落全量、谁都不报错。
 * 读数落在 [UpdateInfo.assetsBlockDelta]，而不是让 app 侧另带一份 `Read` 在管线里走：
 * 理由写在那个字段自己的 KDoc 里。[BlockDeltaManifest] 因此只剩键名常量与判式两件事，
 * 正是它类头预留的收口形态。
 *
 * `assets-version` 的语义**没变**（严格大于才应用），而且它只挂在 `assets-*` 组上：
 * 发布侧即使在写新键，也必须继续同时写 `assets-*` 三件套 + `assets-version`（老包只认它们）。
 *
 * ## 严格度的分配：发布侧严、消费侧宽
 *
 * 清单由 `devtools/emit_update.py` 生成、并由它自己的 `--verify` 逐字段卡死；到玩家手上时，
 * 同一份清单可能被三年前的老客户端读到。因此消费侧的口径是：
 * - **认不认识的键都留着**：未知键只进 [UpdateInfo.warnings]，不拒（新字段不该让老客户端整条
 *   通道哑掉，哑掉的后果是玩家永远收不到修复）；
 * - **`update-format` 大于本类支持值才整条拒**：字段语义变了，猜不得；
 * - 一条 `mirror` 地址写错只丢掉那一条并记 warning，其余镜像照常可用；
 * - **可选组（`assets-full` / `assets-delta` / `assets-base-version`）任何不对都只记 warning
 *   并把那一组当缺席**：回落方向永远是「多下点字节」，而不是「整条通道哑掉」。
 *
 * ## 重复键、半份清单与 BOM（2026-11 收口）
 *
 * - **重复键分档**（P1-2）：必填键（格式行与两条通道的必备件）重复 ⇒ 拒；可选组键重复 ⇒
 *   那一组整组当缺席 + warning，不拒整单；`mirror` 行去重、不拒；
 * - **半份清单**（P1-3）：`apk` 与 `assets` 两条通道都没有 ⇒ 拒（「清单没有任何更新通道，
 *   疑似截断」）—— 不能把一份只剩 mirror/note 的截断文件读成「没有新版」；
 * - **UTF-8 BOM**（P2-1）：首行的 U+FEFF 在解析前剥掉（记事本存盘会带它）。
 *
 * 清单本体的签名面（签名行必须是最后一个非空行、删签名行不降级）不在本类，
 * 见 [ManifestSignature] 与 [UpdatePolicy.verifyManifestPolicy]。
 */
sealed interface ManifestRead {

    /** 解析成功（可能带 [UpdateInfo.warnings]）。 */
    class Ready(val info: UpdateInfo) : ManifestRead

    /** 解析失败：[problems] 是可直接上屏的中文原因，一条一个事实，不做二次加工。 */
    class Rejected(val problems: List<String>) : ManifestRead
}

/** 一个可下载文件：名字 + 声明字节数 + 声明摘要。三者缺一不可（由解析器保证）。 */
class Artifact(
    val fileName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
)

/** APK 通道：整包换版本。 */
class ApkChannel(
    val versionCode: Long,
    val versionName: String,
    val artifact: Artifact,
)

/** 数据通道：只换游戏根下的产物与语料，不动 dex。 */
class AssetsChannel(
    val version: Long,
    val artifact: Artifact,
)

/**
 * 一份合法清单的读数。
 *
 * @param mirrors 清单**声明**的镜像基址（已归一，末尾带斜杠）；与客户端内置表合并后才是候选序
 * @param warnings 消费侧宽容处理的记录（未知键、坏镜像地址、形状不对的可选组），只用于日志与屏上补一行小字
 * @param assetsFull 全量数据包三件套（`assets-full-*`）；键缺席或半组/形状不对 = null，
 *   此时「全量」这件事仍由 [assets] 承载 —— 取哪一件请走 [UpdatePolicy.fullDataArtifact]
 * @param assetsDelta 差量包三件套（`assets-delta-*`）；同上，缺席或不对 = null
 * @param assetsBaseVersion 这份差量的基线版本（`assets-base-version`）：**无键 = -1**，
 *   非正整数同样按 -1（并记 warning）。盘上 `data-version` 必须**恰好等于**它才允许应用差量，
 *   判定在 [UpdatePolicy.deltaUsable]
 */
class UpdateInfo(
    val format: Int,
    val generated: String,
    val note: String,
    val apk: ApkChannel?,
    val assets: AssetsChannel?,
    val mirrors: List<String>,
    val warnings: List<String>,
    val assetsFull: Artifact? = null,
    val assetsDelta: Artifact? = null,
    val assetsBaseVersion: Long = UpdateManifest.NO_ASSETS_BASE_VERSION,
    /**
     * 发布方 APK 的签名证书 SHA-256（小写无分隔，缺席 = 空串）。
     *
     * 为什么清单要带它：Android 的覆盖升级要求**签名证书一致**，章不同的包直接被系统拒
     * （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。本仓实测过这一类包：2026-10-22 那批
     * 用 debug 章签的分发包与发布章互斥。没有这一行，客户端就只能让玩家下完几百 MiB
     * 才发现装不上 —— 那是最坏的失败形状，所以宁可让发布者多写一行。
     */
    val apkCertSha256: String = "",
    /**
     * 灰度发布百分比（0..100）：bucket 小于它的设备才收到这次更新提醒。
     *
     * 缺席或坏值 = [UpdatePolicy.FULL_ROLLOUT_PERCENTAGE]（100 全量）—— 与「未知键只 warning」
     * 同一条兼容口径：新字段不能让老清单、或写坏的一行把整条更新通道哑掉。
     * 判定在 [UpdatePolicy.rolloutSelected] 与 [UpdatePolicy.updateSelected]，本类只负责读。
     */
    val rolloutPercentage: Int = UpdatePolicy.FULL_ROLLOUT_PERCENTAGE,
    /**
     * 清单本体的签名值（`manifest-signature` 行的 Base64，缺席 = 空串 = 未签名清单）。
     *
     * 验签判定在 [UpdatePolicy.verifyManifestPolicy]（三态：过 / 不过 / 未签名），本字段
     * 只承载读数；被签字节与切割规则见 [com.eratw.port.core.update.ManifestSignature]。
     */
    val manifestSignature: String = "",
    /** 清单声明的签名算法（`sig-alg`，缺席 = 空串）；值域由验签层认，解析层不判。 */
    val sigAlg: String = "",
    /** 清单声明的签名键 id（`sig-key-id`，缺席 = 空串）；验签层按它挑内置公钥。 */
    val sigKeyId: String = "",
    /**
     * 清单的 `min-supported-version`（versionCode）：低于它的客户端**必须**更新，
     * 缺席或坏值 = [UpdateManifest.NO_MIN_SUPPORTED_VERSION]（-1 = 无强制档）。
     * 判定在 [UpdatePolicy.isForceUpdateRequired]。
     */
    val minSupportedVersion: Long = UpdateManifest.NO_MIN_SUPPORTED_VERSION,
    /**
     * 块级差量包三件套（`assets-blockdelta` 那一组键，P2-13）；整组缺席或形状不对 = null。
     *
     * 读数由 [BlockDeltaManifest.parse] 产出（判式只有一份，见本类类头那一节）。
     *
     * 为什么**并进本类**而不是让 app 侧多带一份 `BlockDeltaManifest.Read`：这一件是「选哪一档」
     * 的输入，而选件发生在 app 的更新管线入口（`UpdateManager.checkNow`）、消费却一路延伸到
     * 「下次启动装载前应用」那一拍，中间隔着下载服务、玩家的蜂窝确认、进程被系统回收与冷启。
     * 多带一个平行对象的代价不是那几行，
     * 而是「某处只拿到 info、漏了那份 Read」—— 漏点不报错，只会让这一趟静默退回文件级差量，
     * 而日志与遥测照旧说「块级」。挂在 [UpdateInfo] 上，凡是拿得到 [assetsDelta] 的地方就同样
     * 拿得到它，两条差量档不可能各读各的。
     *
     * 缺席时 [UpdatePolicy.dataArtifactFor] 的四参形与三参形**逐字同结果**（U8 把回落写成
     * 「块级 → 三参判定」正是为了这条），所以老清单在新客户端上的行为一字不变。
     * 资格判定不在本类，见 [UpdatePolicy.blockDeltaUsable]。
     */
    val assetsBlockDelta: Artifact? = null,
)

/**
 * 清单文本 → [ManifestRead]（纯函数，不碰网络、不碰文件系统）。
 *
 * 判定顺序是「先看格式，再看必填，再看形状」：格式不对时后面所有键的语义都不成立，
 * 所以只报格式一条，不陪着一堆键名刷屏。
 */
object UpdateManifest {

    /** 本客户端认识的清单格式版本；高于它就拒（见类头「严格度的分配」）。 */
    const val FORMAT_SUPPORTED: Int = 1

    /** 清单文本体积上限：正常几十十字节，给 64 KiB 已经够一千条键值；超了就是拿错文件了。 */
    const val MAX_MANIFEST_BYTES: Long = 64L * 1024L

    /** 单个下载文件的字节数上限（4 GiB）：超过的声明只可能是坏数据，不做下载。 */
    const val MAX_ARTIFACT_BYTES: Long = 4L * 1024L * 1024L * 1024L

    /** note 的显示长度上限（字符）：清单是发布人手写的，截断比整条拒更合适。 */
    const val MAX_NOTE_CHARS: Int = 500

    /**
     * 没有 `assets-base-version` 键（或它的值不是正整数）时的读数：**-1**。
     *
     * 用 -1 而不是 0：0 在数据版本这一系里另有含义（[UpdatePolicy.NO_ASSETS_INSTALLED] = 盘上
     * 从没装过数据）。占两个不同的数，才能保证「老清单」永远撞不出 `基线 == 盘上版本` 的假阳性。
     */
    const val NO_ASSETS_BASE_VERSION: Long = -1L

    /**
     * 没有 `min-supported-version` 键（或它的值不是正整数）时的读数：**-1**。
     *
     * 与 [NO_ASSETS_BASE_VERSION] 同族口径：-1 撞不出 `minSupported ≥ 1` 的假阳性，
     * 「老清单永远无强制档」只由这一个哨兵值承载（判定见 [UpdatePolicy.isForceUpdateRequired]）。
     */
    const val NO_MIN_SUPPORTED_VERSION: Long = -1L

    private const val KEY_FORMAT = "update-format"
    private const val KEY_GENERATED = "generated"
    private const val KEY_NOTE = "note"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_APK_CODE = "apk-version-code"
    private const val KEY_APK_NAME = "apk-version-name"
    private const val KEY_APK_FILE = "apk-file"
    private const val KEY_APK_SIZE = "apk-size"
    private const val KEY_APK_SHA = "apk-sha256"
    private const val KEY_APK_CERT = "apk-cert-sha256"
    private const val KEY_ASSETS_VERSION = "assets-version"
    private const val KEY_ASSETS_FILE = "assets-file"
    private const val KEY_ASSETS_SIZE = "assets-size"
    private const val KEY_ASSETS_SHA = "assets-sha256"
    private const val KEY_ASSETS_FULL_FILE = "assets-full-file"
    private const val KEY_ASSETS_FULL_SIZE = "assets-full-size"
    private const val KEY_ASSETS_FULL_SHA = "assets-full-sha256"
    private const val KEY_ASSETS_DELTA_FILE = "assets-delta-file"
    private const val KEY_ASSETS_DELTA_SIZE = "assets-delta-size"
    private const val KEY_ASSETS_DELTA_SHA = "assets-delta-sha256"
    private const val KEY_ASSETS_BASE_VERSION = "assets-base-version"
    private const val KEY_ROLLOUT_PERCENTAGE = "rollout-percentage"
    private const val KEY_MANIFEST_SIGNATURE = "manifest-signature"
    private const val KEY_SIG_ALG = "sig-alg"
    private const val KEY_SIG_KEY_ID = "sig-key-id"
    private const val KEY_MIN_SUPPORTED_VERSION = "min-supported-version"

    /** UTF-8 BOM（U+FEFF）：Windows 记事本保存的清单会带它，解析前从首行剥掉（P2-1）。 */
    private const val BOM_MARK: String = "\uFEFF"

    /** 一条 `key value` 都没解析出来的空文本 = 源上没这个文件（或被截断），必须区别于合法清单。 */
    fun parse(text: String): ManifestRead {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val values = LinkedHashMap<String, String>()
        val mirrors = ArrayList<String>()
        val duplicateKeys = LinkedHashSet<String>()
        val duplicateLineNos = LinkedHashMap<String, Int>()
        var lineNo = 0
        // P2-1：UTF-8 BOM 在首行会粘成键名的第一个字符（「update-format」认不出来），剥掉再解析。
        // 只在 parse 剥：验签吃的是原始字节（BOM 也在被签字节里），不能在这里动它。
        for (raw in text.removePrefix(BOM_MARK).lineSequence()) {
            lineNo++
            val line = raw.removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val space = line.indexOf(' ')
            if (space <= 0) {
                problems += "第 $lineNo 行不是「键 值」：$line"
                continue
            }
            val key = line.substring(0, space)
            val value = line.substring(space + 1).trim()
            if (key == KEY_MIRROR) {
                // mirror 重复不拒单：归一后的地址在 cleanMirrors 里再去一次重，多写一条只是冗余。
                mirrors += value
                continue
            }
            if (values.containsKey(key)) {
                duplicateKeys += key
                if (!duplicateLineNos.containsKey(key)) duplicateLineNos[key] = lineNo
                continue
            }
            values[key] = value
        }
        // P1-2 重复键分档：必填键重复 ⇒ 整份拒（同一组出现两个值说明发布事故，猜哪个都不是）；
        // 可选组键（差量三组、基线/灰度/最低档、章指纹、签名三行）重复 ⇒ 那一组整组当缺席 + warning；
        // 其余（generated / note / 未知名）保留第一次出现的值 + warning —— 元数据不该让整条通道哑掉。
        val ignoredGroups = LinkedHashMap<String, MutableList<String>>()
        for (key in duplicateKeys) {
            if (key in REQUIRED_KEYS) {
                problems += "第 ${duplicateLineNos[key]} 行键名重复：$key"
                continue
            }
            val group = OPTIONAL_KEY_GROUP[key]
            if (group != null) {
                ignoredGroups.getOrPut(group) { ArrayList() } += key
            } else {
                warnings += "第 ${duplicateLineNos[key]} 行键名重复：$key，保留第一次出现的值"
            }
        }
        if (problems.isNotEmpty()) return ManifestRead.Rejected(problems)
        for ((group, keys) in ignoredGroups) {
            warnings += "可选组 $group 键名重复（${keys.joinToString("、")}）：整组忽略"
            OPTIONAL_GROUP_KEYS.getValue(group).forEach { values.remove(it) }
        }

        val formatText = values[KEY_FORMAT]
        if (formatText == null) {
            return ManifestRead.Rejected(listOf("清单没有 $KEY_FORMAT 行（不是 eratw 更新清单？）"))
        }
        val format = formatText.toIntOrNull()
            ?: return ManifestRead.Rejected(listOf("$KEY_FORMAT 不是整数：$formatText"))
        if (format > FORMAT_SUPPORTED) {
            return ManifestRead.Rejected(
                listOf("清单格式 $format，本客户端只支持到 $FORMAT_SUPPORTED：请先更新应用"),
            )
        }
        if (format < 1) {
            return ManifestRead.Rejected(listOf("$KEY_FORMAT 必须为正整数：$formatText"))
        }

        for (key in values.keys) {
            if (!KNOWN_KEYS.contains(key)) warnings += "忽略不认识的键：$key"
        }

        val apk = readApk(values, problems)
        val assets = readAssets(values, problems)
        if (problems.isNotEmpty()) return ManifestRead.Rejected(problems)
        // P1-3 半份清单：两条通道都没有 ⇒ 这份清单没有任何可下载内容（源上文件被截断、或拿错文件），
        // 不能当成「没有新版」静默放行 —— 那等于让一条只剩镜像/note 的半份清单把玩家永远关在旧版。
        if (apk == null && assets == null) {
            return ManifestRead.Rejected(listOf("清单没有任何更新通道，疑似截断"))
        }

        // 差量那一组是可选扩展：任何不对都只记 warning 并当成缺席（见类头「差量数据包那一组键」）。
        val assetsFull = readOptionalArtifact(
            values, KEY_ASSETS_FULL_FILE, KEY_ASSETS_FULL_SIZE, KEY_ASSETS_FULL_SHA, warnings,
        )
        val assetsDelta = readOptionalArtifact(
            values, KEY_ASSETS_DELTA_FILE, KEY_ASSETS_DELTA_SIZE, KEY_ASSETS_DELTA_SHA, warnings,
        )
        // 块级那一组（P2-13）同样可选，判式**复用 BlockDeltaManifest**（键名与形状只有一份）：
        // 自己再写一遍 readOptionalArtifact 的调用会得到两条「什么叫合法」，而两条口径迟早分叉。
        // problem 非空只记 warning、不拒清单 —— 回落方向仍然是「多下点字节」。
        val blockDeltaRead = BlockDeltaManifest.parse(values)
        blockDeltaRead.problem?.let { warnings += "$it：块级差量不启用，回落文件级差量或全量" }
        val assetsBaseVersion = readBaseVersion(values, warnings)
        val minSupportedVersion = readMinSupportedVersion(values, warnings)

        val cleanMirrors = ArrayList<String>()
        mirrors.forEachIndexed { index, raw ->
            val normalized = UpdatePolicy.normalizeMirror(raw)
            if (normalized == null) {
                warnings += "第 ${index + 1} 条 mirror 不合法，已丢弃：$raw"
            } else if (normalized !in cleanMirrors) {
                cleanMirrors += normalized
            }
        }
        if (cleanMirrors.size > UpdatePolicy.MAX_MIRRORS) {
            warnings += "mirror 超过 ${UpdatePolicy.MAX_MIRRORS} 条，只取前 " +
                "${UpdatePolicy.MAX_MIRRORS} 条"
        }

        val note = (values[KEY_NOTE] ?: "").let {
            if (it.length > MAX_NOTE_CHARS) it.substring(0, MAX_NOTE_CHARS) + "…" else it
        }
        // 章指纹是**可选**键：缺席就留空串（不拦任何设备），形状不对只告警并按缺席处理。
        val certRaw = values[KEY_APK_CERT]
        val certSha = if (certRaw == null) "" else (UpdatePolicy.normalizeSha(certRaw) ?: run {
            warnings += "$KEY_APK_CERT 形状不对，按未声明处理：$certRaw"
            ""
        })
        return ManifestRead.Ready(
            UpdateInfo(
                format = format,
                generated = values[KEY_GENERATED] ?: "",
                note = note,
                apk = apk,
                assets = assets,
                mirrors = cleanMirrors.take(UpdatePolicy.MAX_MIRRORS),
                warnings = warnings,
                assetsFull = assetsFull,
                assetsDelta = assetsDelta,
                assetsBlockDelta = blockDeltaRead.artifact,
                assetsBaseVersion = assetsBaseVersion,
                apkCertSha256 = certSha,
                rolloutPercentage = readRolloutPercentage(values, warnings),
                manifestSignature = values[KEY_MANIFEST_SIGNATURE] ?: "",
                sigAlg = values[KEY_SIG_ALG] ?: "",
                sigKeyId = values[KEY_SIG_KEY_ID] ?: "",
                minSupportedVersion = minSupportedVersion,
            ),
        )
    }

    /** `apk-*` 组：全缺席 → null（本次没有整包可装）；齐了 → 逐字段验形状。 */
    private fun readApk(values: Map<String, String>, problems: MutableList<String>): ApkChannel? {
        val keys = listOf(KEY_APK_FILE, KEY_APK_SIZE, KEY_APK_SHA, KEY_APK_CODE, KEY_APK_NAME)
        val present = keys.filter { values.containsKey(it) }
        if (present.isEmpty()) return null
        if (present.size != keys.size) {
            problems += "apk 通道声明不完整，缺：${(keys - present.toSet()).joinToString(" ")}"
            return null
        }
        val code = values.getValue(KEY_APK_CODE).toLongOrNull()
        if (code == null || code <= 0L) {
            problems += "$KEY_APK_CODE 必须是正整数：${values.getValue(KEY_APK_CODE)}"
            return null
        }
        if (values.getValue(KEY_APK_NAME).isEmpty()) {
            problems += "$KEY_APK_NAME 是空串"
            return null
        }
        val artifact = readArtifact(values, KEY_APK_FILE, KEY_APK_SIZE, KEY_APK_SHA, problems)
            ?: return null
        return ApkChannel(code, values.getValue(KEY_APK_NAME), artifact)
    }

    /** `assets-*` 组：同 [readApk] 的口径，版本号从 1 起（0 保留给「盘上还没装过数据」）。 */
    private fun readAssets(values: Map<String, String>, problems: MutableList<String>): AssetsChannel? {
        val keys = listOf(KEY_ASSETS_FILE, KEY_ASSETS_SIZE, KEY_ASSETS_SHA, KEY_ASSETS_VERSION)
        val present = keys.filter { values.containsKey(it) }
        if (present.isEmpty()) return null
        if (present.size != keys.size) {
            problems += "assets 通道声明不完整，缺：${(keys - present.toSet()).joinToString(" ")}"
            return null
        }
        val version = values.getValue(KEY_ASSETS_VERSION).toLongOrNull()
        if (version == null || version < 1L) {
            problems += "$KEY_ASSETS_VERSION 必须是 ≥1 的整数：${values.getValue(KEY_ASSETS_VERSION)}"
            return null
        }
        val artifact = readArtifact(values, KEY_ASSETS_FILE, KEY_ASSETS_SIZE, KEY_ASSETS_SHA, problems)
            ?: return null
        return AssetsChannel(version, artifact)
    }

    /** 三条一起读：文件名形状、字节数区间、摘要 64 位十六进制（大小写都收，归一成小写）。 */
    private fun readArtifact(
        values: Map<String, String>,
        keyFile: String,
        keySize: String,
        keySha: String,
        problems: MutableList<String>,
    ): Artifact? {
        val fileName = values.getValue(keyFile)
        if (!UpdatePolicy.isArtifactFileName(fileName)) {
            problems += "$keyFile 不是合法文件名（只许 A-Za-z0-9._+-，不许带路径）：$fileName"
            return null
        }
        val size = values.getValue(keySize).toLongOrNull()
        if (size == null || size <= 0L || size > MAX_ARTIFACT_BYTES) {
            problems += "$keySize 不在 1 … $MAX_ARTIFACT_BYTES 区间：${values.getValue(keySize)}"
            return null
        }
        val sha = UpdatePolicy.normalizeSha(values.getValue(keySha))
        if (sha == null) {
            problems += "$keySha 不是 64 位十六进制：${values.getValue(keySha)}"
            return null
        }
        return Artifact(fileName, size, sha)
    }

    /**
     * **可选**的三件套组（`assets-full-*` / `assets-delta-*`）：整组缺席 → null；
     * 缺半组或形状不对 → 记一条 warning 后当成缺席，**绝不拒清单**。
     *
     * 与 [readArtifact] 走同一套形状校验（复用，不另写一份宽口径），区别只在失败的去处：
     * 必填组失败进 `problems`（拒收），可选组失败进 `warnings`（回落）。
     * 回落的方向永远是「多下点字节」而不是「整条通道哑掉」——
     * 哑掉的代价是玩家收不到修复，字节的代价是玩家心疼一下流量。
     *
     * 组名从 [keyFile] 去掉结尾的 `-file` 反推（`assets-delta-file` → `assets-delta`），
     * 这样告警里出现的键名与契约 §1.2 表格逐字一致，发布侧 `emit_update.py --verify-only`
     * 与日志能直接对上。
     */
    private fun readOptionalArtifact(
        values: Map<String, String>,
        keyFile: String,
        keySize: String,
        keySha: String,
        warnings: MutableList<String>,
    ): Artifact? {
        val keys = listOf(keyFile, keySize, keySha)
        val present = keys.filter { values.containsKey(it) }
        if (present.isEmpty()) return null
        val group = keyFile.removeSuffix("-file")
        if (present.size != keys.size) {
            warnings += "可选组 $group 声明不完整，缺：${(keys - present.toSet()).joinToString(" ")}" +
                "：整组忽略，回落全量"
            return null
        }
        val problems = ArrayList<String>()
        val artifact = readArtifact(values, keyFile, keySize, keySha, problems)
        if (artifact == null) {
            warnings += problems.joinToString("；") + "：可选组 $group 整组忽略，回落全量"
            return null
        }
        return artifact
    }

    /**
     * `assets-base-version`：无键 → [NO_ASSETS_BASE_VERSION]；值不是 ≥1 的整数 → 同样回 -1 并告警。
     *
     * 判成 -1 就是判成「差量不启用」（[UpdatePolicy.deltaUsable] 要求基线 ≥1），
     * 因为差量一旦用错基线，得到的是内容不对的语料而不是报错 —— 宁可回落全量。
     */
    private fun readBaseVersion(values: Map<String, String>, warnings: MutableList<String>): Long {
        val raw = values[KEY_ASSETS_BASE_VERSION] ?: return NO_ASSETS_BASE_VERSION
        val version = raw.toLongOrNull()
        if (version == null || version < 1L) {
            warnings += "$KEY_ASSETS_BASE_VERSION 不是 ≥1 的整数：$raw：按无基线处理，差量不启用"
            return NO_ASSETS_BASE_VERSION
        }
        return version
    }

    /**
     * `rollout-percentage`：无键 → [UpdatePolicy.FULL_ROLLOUT_PERCENTAGE]（100 全量，且不告警）；
     * 值不是 0..100 的整数 → 记一条 warning 后同样回落 100。
     *
     * 缺席刻意**不告警**：老清单里本来就没这一行，刷一条「忽略」只会把真问题淹掉
     * （现有断言也钉着合法老清单零告警）。只有写了键却写坏了才留痕。
     * 不拒清单的理由与可选组一致：消费侧比发布侧宽。发布侧 `emit_update.py --verify-only`
     * 会把坏值挡在发版之前，但玩家手上的清单可能被谁改过一行 —— 那时「全量放行」
     * 比「整条通道哑掉」安全得多，后者等于玩家永远收不到修复。
     */
    private fun readRolloutPercentage(values: Map<String, String>, warnings: MutableList<String>): Int {
        val raw = values[KEY_ROLLOUT_PERCENTAGE] ?: return UpdatePolicy.FULL_ROLLOUT_PERCENTAGE
        val value = raw.toIntOrNull()
        if (value == null || value !in 0..UpdatePolicy.FULL_ROLLOUT_PERCENTAGE) {
            warnings += "$KEY_ROLLOUT_PERCENTAGE 不是 0..100 的整数：$raw：按 100 全量处理"
            return UpdatePolicy.FULL_ROLLOUT_PERCENTAGE
        }
        return value
    }

    /**
     * `min-supported-version`：无键 → [NO_MIN_SUPPORTED_VERSION]；值不是 ≥1 的整数 →
     * 同样回 -1 并告警（与 [readBaseVersion] 同口径：坏值当缺席，不拦清单）。
     *
     * 判成 -1 就是判成「无强制档」（[UpdatePolicy.isForceUpdateRequired] 对 ≤0 恒 false）：
     * 这一行是发布方声明「低于它的客户端不再支持」，缺了它只该失去强制能力，
     * 不该让整份清单被判死 —— 拒收的后果是整条更新通道对那台设备哑掉。
     */
    private fun readMinSupportedVersion(
        values: Map<String, String>,
        warnings: MutableList<String>,
    ): Long {
        val raw = values[KEY_MIN_SUPPORTED_VERSION] ?: return NO_MIN_SUPPORTED_VERSION
        val version = raw.toLongOrNull()
        if (version == null || version < 1L) {
            warnings += "$KEY_MIN_SUPPORTED_VERSION 不是 ≥1 的整数：$raw：按无强制档处理"
            return NO_MIN_SUPPORTED_VERSION
        }
        return version
    }

    private val KNOWN_KEYS: Set<String> = setOf(
        KEY_FORMAT,
        KEY_GENERATED,
        KEY_NOTE,
        KEY_APK_CODE,
        KEY_APK_NAME,
        KEY_APK_FILE,
        KEY_APK_SIZE,
        KEY_APK_SHA,
        KEY_ASSETS_VERSION,
        KEY_ASSETS_FILE,
        KEY_ASSETS_SIZE,
        KEY_ASSETS_SHA,
        KEY_APK_CERT,
        KEY_ASSETS_FULL_FILE,
        KEY_ASSETS_FULL_SIZE,
        KEY_ASSETS_FULL_SHA,
        KEY_ASSETS_DELTA_FILE,
        KEY_ASSETS_DELTA_SIZE,
        KEY_ASSETS_DELTA_SHA,
        KEY_ASSETS_BASE_VERSION,
        // 块级那一组的键名归 BlockDeltaManifest 所有（发布侧与设备侧共用一份常量），这里只做
        // 「已知键」登记：漏一条，合法的新清单会平白多出一条「忽略不认识的键」的谎报 ——
        // 客户端明明用了它，屏上却说没认出来。
        BlockDeltaManifest.KEY_FILE,
        BlockDeltaManifest.KEY_SIZE,
        BlockDeltaManifest.KEY_SHA,
        KEY_ROLLOUT_PERCENTAGE,
        KEY_MANIFEST_SIGNATURE,
        KEY_SIG_ALG,
        KEY_SIG_KEY_ID,
        KEY_MIN_SUPPORTED_VERSION,
    )

    /**
     * P1-2 的「必填键」：重复 ⇒ 整份清单拒收。
     *
     * 只收两条更新通道各自的必备件，加格式行本身 —— 这十行里任何一行出现两个值，说明发布侧
     * 拼清单出了事故（或有人在剪贴），消费侧猜哪个都可能装错版本、下错文件，宁可整份拒。
     */
    private val REQUIRED_KEYS: Set<String> = setOf(
        KEY_FORMAT,
        KEY_APK_CODE,
        KEY_APK_NAME,
        KEY_APK_FILE,
        KEY_APK_SIZE,
        KEY_APK_SHA,
        KEY_ASSETS_VERSION,
        KEY_ASSETS_FILE,
        KEY_ASSETS_SIZE,
        KEY_ASSETS_SHA,
    )

    /**
     * P1-2 的「可选组键 → 组名」：组里任一键重复 ⇒ 那一组整组当缺席 + warning，不拒整单。
     *
     * 差量三组与基线/灰度/最低档/章指纹/签名三行都是「缺省即回落」的扩展面，一个重复键
     * 只该让这一组失效（回落方向永远是「多下点字节」或「不启用可选能力」），不该让整条
     * 更新通道对玩家哑掉。签名三行同组：重复说明声明不自洽，整组当缺席后由验签层的
     * 形状判式处理（签名行是否可读只看原文）。
     */
    private val OPTIONAL_KEY_GROUP: Map<String, String> = LinkedHashMap<String, String>().apply {
        put(KEY_ASSETS_FULL_FILE, "assets-full")
        put(KEY_ASSETS_FULL_SIZE, "assets-full")
        put(KEY_ASSETS_FULL_SHA, "assets-full")
        put(KEY_ASSETS_DELTA_FILE, "assets-delta")
        put(KEY_ASSETS_DELTA_SIZE, "assets-delta")
        put(KEY_ASSETS_DELTA_SHA, "assets-delta")
        put(BlockDeltaManifest.KEY_FILE, "assets-blockdelta")
        put(BlockDeltaManifest.KEY_SIZE, "assets-blockdelta")
        put(BlockDeltaManifest.KEY_SHA, "assets-blockdelta")
        put(KEY_ASSETS_BASE_VERSION, "assets-base-version")
        put(KEY_ROLLOUT_PERCENTAGE, "rollout-percentage")
        put(KEY_MIN_SUPPORTED_VERSION, "min-supported-version")
        put(KEY_APK_CERT, "apk-cert-sha256")
        put(KEY_MANIFEST_SIGNATURE, "签名声明")
        put(KEY_SIG_ALG, "签名声明")
        put(KEY_SIG_KEY_ID, "签名声明")
    }

    /** 组名 → 该组全部键：整组忽略时从 values 里一并摘掉，后续读取只会看到「整组缺席」。 */
    private val OPTIONAL_GROUP_KEYS: Map<String, List<String>> =
        OPTIONAL_KEY_GROUP.entries.groupBy({ it.value }, { it.key })
}
