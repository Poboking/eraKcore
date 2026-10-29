package com.eratw.port.uipreview

import com.eratw.port.render.view.ScreenLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * 设备档案（CONTRACT-UP3 §4.2-4.4）：`tools/uipreview/profiles/devices.yaml` 的数据模型、
 * 零依赖加载器与 serve 几何解析。
 *
 * ## 口径（三条，全部钉死）
 *
 * 1. **存储竖屏、消费横屏**：yaml 里的 widthPx/heightPx 按官方规格页的**竖屏**口径存
 *    （照抄任务书原文）；app 锁 `sensorLandscape`（`AndroidManifest.xml:22`「PC 基准的
 *    横屏版面」，BOARD UP1 行尾注/`9f8779d4` 钉死）⇒ 面板几何一律取
 *    [landscapeWidthPx] × [landscapeHeightPx]（长边 × 短边，宽高互换）。
 * 2. **inset 随旋转重算**：竖屏 insetYPx（顶部状态栏）旋转后落**横向**（
 *    [landscapeInsetXPx]），纵向 inset 归零。本波 serve 不消费 inset（画布 = 整屏，
 *    与 UP1 现状一致），字段先按此口径记录，View 层消费归后续波次。
 * 3. **densityDpi 就近档**：档案缺 densityDpi 时按 [densityDpiFromPpi] 从 ppi 取整
 *    （20 dpi 粒度）。与爬证值对照：398→400、428→420、445→440、505→500、260→260
 *    全中；374→380 与 zfold5-inner 实机 360 不中（OEM 主观档位，不硬凑）——字段有
 *    实测值时以实测为准，规则只兜「无实测」。
 *
 * ## 列数不是设备属性
 *
 * 设备档案只存几何；列数（110 缺省）是**版面偏好**（对应
 * `CanvasConsoleView.fontColumnsOverride` 的手机可读档），由 `--columns` 给、
 * 不进档案。`consoleWidth` 是 boot 参数，切设备 = 重开会话（`GameSession` KDoc）。
 */
data class DeviceProfile(
    val id: String,
    val model: String,
    /** yaml 键 `class`（Kotlin 关键字，改名收口）。 */
    val deviceClass: String,
    val diagonalIn: Double,
    /** 竖屏官方规格口径（消费走 [landscapeWidthPx]）。 */
    val widthPx: Int,
    /** 竖屏官方规格口径（消费走 [landscapeHeightPx]）。 */
    val heightPx: Int,
    val aspect: String,
    val ppi: Int,
    /** 生效密度；档案缺失时由 [DeviceProfiles.densityDpiFromPpi] 就近档补齐。 */
    val densityDpi: Int,
    /** 竖屏顶部 inset（像素）；TODO(verify)：iqoo-neo6se 等 reference 档待真机实测覆盖。 */
    val insetYPx: Int,
    /** reference = 参考真机（几何以它对齐）；crawled = 官方规格页爬证。 */
    val status: String,
) {
    /** 横屏面板宽（长边）。 */
    val landscapeWidthPx: Int get() = maxOf(widthPx, heightPx)

    /** 横屏面板高（短边）。 */
    val landscapeHeightPx: Int get() = minOf(widthPx, heightPx)

    /** 竖屏 insetYPx 旋转后落横向；纵向 inset 归零（见类 KDoc 口径 2）。 */
    val landscapeInsetXPx: Int get() = insetYPx

    /** 横屏档的「真机口径」布局（字号/行高/列数与 `CanvasConsoleView` 同公式）。 */
    fun landscapeLayout(columns: Int): ScreenLayout =
        ScreenLayout.fixedColumns(landscapeWidthPx, densityDpi, columns)
}

/** 一份档案的解析结果（有序、id 唯一）；[source] 是来源标签（文件绝对路径或 classpath 资源名）。 */
class DeviceBook(val devices: List<DeviceProfile>, val source: String) {

    /** 全部可用 id（保序；错误信息用）。 */
    fun ids(): List<String> = devices.map { it.id }

    /** 取指定 id；未知折 [CliError]（信息列全部可用 id）。 */
    fun require(id: String): DeviceProfile =
        devices.firstOrNull { it.id == id }
            ?: throw CliError("未知设备 id: $id（可用: ${ids().joinToString(", ")}；档案 $source）")
}

/**
 * 手写极简 YAML 子集解析（零第三方依赖，UP1 的「JSON 收发零依赖」同款纪律）。
 * 只支持本档案的固定形状：`devices:` 顶头 + 缩进列表项 `- key: value` + 同缩进续行 +
 * `#` 注释（整行或行尾）+ 双引号字符串值。别的 YAML 特性一概不收，遇到即报用法错误。
 */
object DeviceProfiles {

    /** 就近档粒度（dpi）：Android 实机常用档距；60 dpi 主档（120/160/240/320/480/640）太粗。 */
    const val DPI_GRAIN: Int = 20

    /** ppi → densityDpi 就近档（四舍五入到最近的 [DPI_GRAIN]；依据见 [DeviceProfile] KDoc）。 */
    fun densityDpiFromPpi(ppi: Int): Int = (ppi + DPI_GRAIN / 2) / DPI_GRAIN * DPI_GRAIN

    /** 显式指定档案文件的环境变量（最高优先级，供非标准目录运行）。 */
    const val PROFILES_ENV: String = "ERATW_UIPREVIEW_PROFILES"

    /**
     * 档案定位顺序（[loadDefault]）：环境变量 [PROFILES_ENV] → CWD 下
     * `profiles/devices.yaml`（gradle 起服/测试的 CWD = 本模块目录）→ 自 CWD 向上
     * 最多 6 层找 `tools/uipreview/profiles/devices.yaml` 与
     * `eratw-android/tools/uipreview/profiles/devices.yaml`（后者盖住「从 git 根跑
     * installDist 产物」的布局：模块在 eratw-android 子目录下）→ 都没有再退
     * **classpath 里的 `profiles/devices.yaml`**（build 把模块根 `profiles/` 打进 jar，
     * 组长接线：agent 在任意 cwd 跑 installDist 产物也能拿到内置档案）。
     */
    fun defaultLocations(): List<Path> {
        val explicit = System.getenv(PROFILES_ENV)?.let { Path.of(it) }
        val candidates = buildList {
            if (explicit != null) add(explicit)
            add(Path.of("profiles", "devices.yaml"))
            var dir: Path? = Path.of("").toAbsolutePath()
            repeat(6) {
                if (dir != null) {
                    add(dir!!.resolve("tools").resolve("uipreview").resolve("profiles").resolve("devices.yaml"))
                    add(dir!!.resolve("eratw-android").resolve("tools").resolve("uipreview").resolve("profiles").resolve("devices.yaml"))
                }
                dir = dir?.parent
            }
        }
        return candidates
    }

    /** 按定位顺序找第一份存在的档案；文件系全落空再退 classpath 内置；两头皆空折 [CliError]。 */
    fun loadDefault(): DeviceBook {
        val locations = defaultLocations()
        locations.firstOrNull { Files.isRegularFile(it) }?.let { return load(it) }
        val packaged = DeviceProfiles::class.java.classLoader.getResourceAsStream(PACKAGED_PROFILES)
        if (packaged != null) {
            return packaged.bufferedReader(Charsets.UTF_8).use { reader ->
                parse(reader.readLines(), "classpath:$PACKAGED_PROFILES")
            }
        }
        throw CliError(
            "找不到设备档案 devices.yaml（搜索过: ${locations.joinToString("; ") { it.toString() }} 与 " +
                "classpath:$PACKAGED_PROFILES；可用 $PROFILES_ENV 显式指定）",
        )
    }

    /** 解析一份档案文件；IO 失败 / 形状不对折 [CliError]（带文件与行号）。 */
    fun load(file: Path): DeviceBook {
        val lines = try {
            Files.readAllLines(file, Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            throw CliError("读不了设备档案 ${file.toAbsolutePath()}: ${e.message}", e)
        }
        return parse(lines, file.toAbsolutePath().toString())
    }

    /** jar 内置档案的资源路径（build 的 processResources 把模块根 `profiles/` 并入此前缀）。 */
    private const val PACKAGED_PROFILES = "profiles/devices.yaml"

    /** 解析档案行集（文件与 classpath 内置共用入口）；形状不对折 [CliError]（带来源与行号）。 */
    private fun parse(lines: List<String>, source: String): DeviceBook {
        val devices = ArrayList<DeviceProfile>()
        var current: MutableMap<String, String>? = null
        var sawDevicesHeader = false
        for ((idx, raw) in lines.withIndex()) {
            val line = stripComment(raw)
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val body = line.trim()
            val lineNo = idx + 1
            if (indent == 0) {
                if (body == "devices:") {
                    sawDevicesHeader = true
                } else {
                    throw CliError("设备档案只认顶头的 devices: 列表，$source:$lineNo 实到「$body」")
                }
                continue
            }
            if (!sawDevicesHeader) {
                throw CliError("设备档案第一段必须是 devices:，$source:$lineNo 实到「$body」")
            }
            if (body.startsWith("- ")) {
                current?.let { devices += buildProfile(it, source, lineNo) }
                current = LinkedHashMap()
                current += splitEntry(body.removePrefix("- "), source, lineNo)
            } else {
                val map = current ?: throw CliError("devices: 下必须是 `- id: …` 起头的列表项，$source:$lineNo")
                map += splitEntry(body, source, lineNo)
            }
        }
        current?.let { devices += buildProfile(it, source, lines.size) }
        if (devices.isEmpty()) throw CliError("设备档案是空的: $source")
        val dup = devices.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) throw CliError("设备 id 重复: ${dup.joinToString(", ")}（$source）")
        return DeviceBook(devices, source)
    }

    /** 剥 `#` 注释：双引号内的 `#` 不算（aspect 值虽无 `#`，规则先钉住）。 */
    private fun stripComment(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inQuote = false
        for (c in raw) {
            when {
                c == '"' -> {
                    inQuote = !inQuote
                    sb.append(c)
                }
                c == '#' && !inQuote -> return sb.toString()
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** `key: value` 一行 → 一对；value 去引号去空白。 */
    private fun splitEntry(body: String, source: String, lineNo: Int): Pair<String, String> {
        val colon = body.indexOf(':')
        if (colon <= 0) throw CliError("档案行必须是 `key: value`，$source:$lineNo 实到「$body」")
        val key = body.substring(0, colon).trim()
        var value = body.substring(colon + 1).trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isEmpty() || value.isEmpty()) {
            throw CliError("档案行的 key/value 不能为空，$source:$lineNo 实到「$body」")
        }
        return key to value
    }

    /** 一个列表项的字段表 → [DeviceProfile]；必填缺失/数值非法折 [CliError]。 */
    private fun buildProfile(fields: Map<String, String>, source: String, lineNo: Int): DeviceProfile {
        fun required(name: String): String =
            fields[name] ?: throw CliError("设备缺字段 $name（${fields["id"] ?: "开头第 $lineNo 行一带"}，$source）")
        fun intOf(name: String): Int = required(name).toIntOrNull()
            ?: throw CliError("字段 $name 不是整数: ${fields[name]}（$source）")
        val ppi = intOf("ppi")
        val densityDpi = fields["densityDpi"]?.let {
            it.toIntOrNull() ?: throw CliError("字段 densityDpi 不是整数: $it（$source）")
        } ?: densityDpiFromPpi(ppi)
        val profile = DeviceProfile(
            id = required("id"),
            model = required("model"),
            deviceClass = required("class"),
            diagonalIn = required("diagonalIn").toDoubleOrNull()
                ?: throw CliError("字段 diagonalIn 不是数: ${fields["diagonalIn"]}（$source）"),
            widthPx = intOf("widthPx"),
            heightPx = intOf("heightPx"),
            aspect = required("aspect"),
            ppi = ppi,
            densityDpi = densityDpi,
            insetYPx = fields["insetYPx"]?.let {
                it.toIntOrNull() ?: throw CliError("字段 insetYPx 不是整数: $it（$source）")
            } ?: 0,
            status = required("status"),
        )
        if (profile.widthPx <= 0 || profile.heightPx <= 0) {
            throw CliError("设备 ${profile.id} 的面板尺寸必须为正（$source）")
        }
        return profile
    }
}

/**
 * serve 的几何解析结果（CONTRACT-UP3 §4.5 优先级）：
 * **面板 = 档案横屏互换；`--screen WxH` 显式给出则覆盖面板宽高（densityDpi 仍取档案）；
 * 列数 = `--columns`（缺省 110），设备档案不携带列数。**
 *
 * `--screen` 的覆盖是「降级为覆盖项」的口径：想试非标准分辨率（如裁掉 inset 的净区）
 * 不用改档案。字号推导已统一进 [com.eratw.port.render.view.ScreenLayout]（组长接线：
 * `PreviewRenderer` 与 [deviceLayout] 同一份公式，读数恒同值，差 1px 的旧取整口径退役）。
 */
data class ServeGeometry(
    /** 控制台列数（半角格）= `GameSession.consoleWidth` / `ConsoleScreen.clientWidth`。 */
    val columns: Int,
    /** 生效画布宽（像素；= 档案横屏宽或 `--screen` 覆盖值）。 */
    val screenW: Int,
    /** 生效画布高（像素）。 */
    val screenH: Int,
    /** 生效密度（无档案 = null：固定列模式只有 18dp 兜底用得上密度，预览画布不消费）。 */
    val densityDpi: Int?,
    /** `--device` 给出的档案；null = 无档案（纯 `--screen`/缺省路径）。 */
    val device: DeviceProfile?,
) {

    /** 真机口径布局（有档案才算得出）；预览画布实际几何另由 `PreviewRenderer` 出。 */
    fun deviceLayout(): ScreenLayout? =
        device?.landscapeLayout(columns)

    /** 起服行的人读片段（不含画布实际值——那要从 renderer 读，防两口径打架）。 */
    fun describe(): String {
        val head = if (device != null) {
            "device=${device.id}[${device.model}] screen=${screenW}x${screenH}" +
                (densityDpi?.let { "@${it}dpi" } ?: "")
        } else {
            "screen=${screenW}x${screenH}"
        }
        return "$head, $columns columns"
    }

    companion object {

        /**
         * 优先级见类 KDoc；[screenOverride] 只在 `--screen` 显式给出时非 null，
         * [defaultScreen] 是无档案无覆盖时的缺省屏（`Cli.DEFAULT_SCREEN`，横屏口径）。
         */
        fun resolve(
            device: DeviceProfile?,
            screenOverride: Pair<Int, Int>?,
            columns: Int,
            defaultScreen: Pair<Int, Int>,
        ): ServeGeometry {
            val w = screenOverride?.first ?: device?.landscapeWidthPx ?: defaultScreen.first
            val h = screenOverride?.second ?: device?.landscapeHeightPx ?: defaultScreen.second
            return ServeGeometry(columns, w, h, device?.densityDpi, device)
        }
    }
}
