package com.eratw.port.uipreview

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * 设备档案 + serve 几何解析（CONTRACT-UP3 §3）：
 * 加载与剥壳、横屏互换（面板宽高互换，BOARD UP1 行尾注钉死）、densityDpi 就近档、
 * `--screen`/`--columns` 覆盖优先级、`--device` serve 冒烟（起服行带 device 与几何）、
 * 未知 id 折退出码 1。
 *
 * 仓库真档案（`profiles/devices.yaml`）按 CWD 定位加载 —— gradle 测试的 CWD =
 * 本模块目录，`DeviceProfiles.defaultLocations` 的第一候选就是它。
 */
class DeviceProfileTest {

    /** 真档案加载：六机型逐字段 + 行尾 TODO 注释剥壳 + 引号值剥壳。 */
    @Test
    fun `加载真档案_六机型齐全_iqoo逐字段`() {
        val book = DeviceProfiles.loadDefault()
        assertEquals(
            listOf("iqoo-neo6se", "zenfone-10", "pixel-8", "galaxy-s24-ultra", "redmi-13c", "zfold5-inner"),
            book.ids(),
        )
        val iqoo = book.require("iqoo-neo6se")
        assertEquals("iQOO Neo6 SE (vivo V2199A)", iqoo.model)
        assertEquals("mainstream-fhd", iqoo.deviceClass)
        assertEquals(6.62, iqoo.diagonalIn)
        assertEquals(1080, iqoo.widthPx)
        assertEquals(2400, iqoo.heightPx)
        assertEquals("20:9", iqoo.aspect)
        assertEquals(398, iqoo.ppi)
        // 行尾 `# TODO(verify)` 注释必须剥掉，值是 400 不是 "400          # TODO…"
        assertEquals(400, iqoo.densityDpi)
        assertEquals(96, iqoo.insetYPx)
        assertEquals("reference", iqoo.status)
    }

    /** 横屏互换：面板一律长边 × 短边（竖屏档案 → 横屏消费），inset 落横向。 */
    @Test
    fun `横屏互换_面板取长边短边_竖屏inset落横向`() {
        val book = DeviceProfiles.loadDefault()
        for (d in book.devices) {
            assertTrue(d.landscapeWidthPx >= d.landscapeHeightPx, "${d.id} 长边必须在宽")
            assertEquals(maxOf(d.widthPx, d.heightPx), d.landscapeWidthPx)
            assertEquals(minOf(d.widthPx, d.heightPx), d.landscapeHeightPx)
            assertEquals(d.insetYPx, d.landscapeInsetXPx)
        }
        assertEquals(2400 to 1080, book.require("iqoo-neo6se").let { it.landscapeWidthPx to it.landscapeHeightPx })
        assertEquals(3120 to 1440, book.require("galaxy-s24-ultra").let { it.landscapeWidthPx to it.landscapeHeightPx })
        assertEquals(1600 to 720, book.require("redmi-13c").let { it.landscapeWidthPx to it.landscapeHeightPx })
        assertEquals(2176 to 1812, book.require("zfold5-inner").let { it.landscapeWidthPx to it.landscapeHeightPx })
    }

    /** densityDpi 就近档（20dpi 粒度）：五个爬证值全中；zfold 的 374→380 与实机 360 的偏差是规则已知边界。 */
    @Test
    fun `就近档_20dpi粒度_爬证ppi全中`() {
        assertEquals(400, DeviceProfiles.densityDpiFromPpi(398))
        assertEquals(420, DeviceProfiles.densityDpiFromPpi(428))
        assertEquals(440, DeviceProfiles.densityDpiFromPpi(445))
        assertEquals(500, DeviceProfiles.densityDpiFromPpi(505))
        assertEquals(260, DeviceProfiles.densityDpiFromPpi(260))
        // 已登记偏差：zfold5-inner ppi 374 就近档给 380，实机 OEM 定 360 —— 有实测值时以实测为准
        assertEquals(380, DeviceProfiles.densityDpiFromPpi(374))
    }

    /** 手写档案解析：缺 densityDpi 落就近档、缺必填/重复 id/顶头杂段都折 CliError。 */
    @Test
    fun `手写档案_缺densityDpi落就近档_坏形状报用法错`(@TempDir tmp: Path) {
        val mini = tmp.resolve("mini.yaml")
        Files.writeString(
            mini,
            """
            devices:
              - id: made-up
                model: Made Up 7
                class: test
                diagonalIn: 7.0
                widthPx: 1000
                heightPx: 2000
                aspect: "2:1"
                ppi: 399
                status: test
            """.trimIndent(),
        )
        val book = DeviceProfiles.load(mini)
        val madeUp = book.require("made-up")
        assertEquals(400, madeUp.densityDpi, "399ppi 就近档 → 400")
        assertEquals(2000 to 1000, madeUp.landscapeWidthPx to madeUp.landscapeHeightPx)

        // 重复 id
        val dup = tmp.resolve("dup.yaml")
        Files.writeString(
            dup,
            """
            devices:
              - id: a
                model: A
                class: c
                diagonalIn: 1.0
                widthPx: 10
                heightPx: 20
                aspect: "2:1"
                ppi: 300
                densityDpi: 300
                insetYPx: 10
                status: t
              - id: a
                model: A2
                class: c
                diagonalIn: 1.0
                widthPx: 10
                heightPx: 20
                aspect: "2:1"
                ppi: 300
                status: t
            """.trimIndent(),
        )
        assertCliError("重复") { DeviceProfiles.load(dup) }

        // 顶头杂段
        val junk = tmp.resolve("junk.yaml")
        Files.writeString(junk, "others:\n  - id: x\n")
        assertCliError("devices:") { DeviceProfiles.load(junk) }

        // 空档案
        val empty = tmp.resolve("empty.yaml")
        Files.writeString(empty, "devices:\n")
        assertCliError("空") { DeviceProfiles.load(empty) }

        // 缺必填字段
        val missing = tmp.resolve("missing.yaml")
        Files.writeString(missing, "devices:\n  - id: x\n    model: X\n")
        assertCliError("缺字段") { DeviceProfiles.load(missing) }
    }

    /** 优先级：`--screen` 覆盖档案宽高（densityDpi 仍取档案）；无档案无覆盖落缺省屏。 */
    @Test
    fun `resolve优先级_screen覆盖档案_无档案落缺省`() {
        val book = DeviceProfiles.loadDefault()
        val iqoo = book.require("iqoo-neo6se")
        val def = 2400 to 1080

        val byDevice = ServeGeometry.resolve(iqoo, screenOverride = null, columns = 110, defaultScreen = def)
        assertEquals(2400, byDevice.screenW)
        assertEquals(1080, byDevice.screenH)
        assertEquals(400, byDevice.densityDpi)
        assertEquals(110, byDevice.columns)

        val overridden = ServeGeometry.resolve(iqoo, screenOverride = 1000 to 500, columns = 84, defaultScreen = def)
        assertEquals(1000, overridden.screenW)
        assertEquals(500, overridden.screenH)
        assertEquals(400, overridden.densityDpi, "densityDpi 仍取档案")
        assertEquals(84, overridden.columns)

        val noDevice = ServeGeometry.resolve(device = null, screenOverride = null, columns = 110, defaultScreen = 1800 to 900)
        assertEquals(1800 to 900, noDevice.screenW to noDevice.screenH)
        assertEquals(null, noDevice.densityDpi)
        assertEquals(null, noDevice.deviceLayout())
    }

    /** 真机口径布局：iqoo 110 列 = 字 42/行高 44/画布宽 2402（ScreenLayoutTest 同锚）；S24U 3120 宽 = 字 54。 */
    @Test
    fun `deviceLayout_与ScreenLayout同式_iqoo42_S24U54`() {
        val book = DeviceProfiles.loadDefault()
        val iqoo = book.require("iqoo-neo6se").landscapeLayout(110)
        assertEquals(42, iqoo.fontPixelSize)
        assertEquals(44, iqoo.lineHeight)
        assertEquals(2402, iqoo.canvasWidthPx)
        assertEquals(24, iqoo.heightLinesFor(1080))

        // 3120 宽 / 110 列：ScreenLayout（=真机 CanvasConsoleView）给 54；
        // 预览 renderer 的 floor/ceil 口径给 55 —— 差 1px 是已登记口径差（统一归组长接线）
        val s24u = book.require("galaxy-s24-ultra").landscapeLayout(110)
        assertEquals(54, s24u.fontPixelSize)
    }

    /** serve 冒烟：`--device redmi-13c` 起服，起服行带 device/横屏几何/真机口径行，退出码 0。 */
    @Test
    fun `serve冒烟_device起服_横屏几何进起服行`(@TempDir tmp: Path) {
        val built = MiniTitle.build(tmp, MiniTitle.ERB_AUTOQUIT)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val rc = Cli.run(
            arrayOf(
                "serve", "--erbc", built.erbcDir.toString(), "--game", built.gameRoot.toString(),
                "--port", "0", "--device", "redmi-13c", "--columns", "40",
            ),
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
        )
        assertEquals(0, rc, "stderr: ${err.toString(Charsets.UTF_8)}")
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.contains("uipreview serve: http://127.0.0.1:"), text)
        assertTrue(text.contains("device=redmi-13c"), text)
        assertTrue(text.contains("1600x720"), "档案竖屏 720x1600 必须互换成长边×短边: $text")
        assertTrue(text.contains("40 columns"), text)
        assertTrue(text.contains("canvas "), text)
        assertTrue(text.contains("device-layout: font "), text)
    }

    /** 未知设备 id：折用法错误（退出码 1），信息列全部可用 id。 */
    @Test
    fun `serve_未知设备id_退出码1并列可用id`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val rc = Cli.run(
            arrayOf("serve", "--device", "no-such-phone"),
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
        )
        assertEquals(1, rc)
        val text = err.toString(Charsets.UTF_8)
        assertTrue(text.contains("未知设备 id: no-such-phone"), text)
        assertTrue(text.contains("iqoo-neo6se"), text)
    }

    private fun assertCliError(keyword: String, block: () -> Unit) {
        try {
            block()
            fail("应折 CliError（含「$keyword」）")
        } catch (e: CliError) {
            assertTrue(e.message?.contains(keyword) == true, "CliError 信息应含「$keyword」，实到: ${e.message}")
        }
    }
}
