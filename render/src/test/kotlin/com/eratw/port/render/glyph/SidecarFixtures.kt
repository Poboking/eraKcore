package com.eratw.port.render.glyph

import com.eratw.port.core.font.FontSidecarReader

/**
 * §3 sidecar 的**测试夹具**（不是 c-fontsub 的成品，也不许当成品用）。
 *
 * ## 为什么自带夹具
 *
 * 本波 c-fontsub 还在产 render 模块 resources 下的 fonts 目录，消费侧不能依赖那份在飞的文件，
 * 所以度量与测量一律靠这里的小尺寸夹具做断言。成品缺失时的退化路径另有测试覆盖
 * （[TextMetricsTest] 的「成品缺失」用例直接走 classpath，不灌夹具）。
 *
 * ## 夹具的数值来源
 *
 * 数值**不是编的**：本机 fontTools 4.67.0 读语料 `eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf`
 * 的 `head.unitsPerEm` 与 `hmtx` 实测得到（同一批读数与仓内 `tools/fontsubset/probe-report.txt:32-59`
 * 逐项一致）：unitsPerEm = 1280，空格 U+0020 = 400、数字 U+0030 = 640、A = 800、B = 800（lsb 80）、
 * C = 880、い U+3042 = 1200、中 U+4E2D = 1280（lsb 80）、「、」U+3001 = 880、
 * 半角假名 U+FF71 = 800、半角句点 U+FF61 = 560、全角 A U+FF21 = 880。
 * 夹具只取其中 8 个码位（小尺寸），比例差异原样保留 —— 不许为凑 1:2 网格伪造（M4-REALCHECK ②）。
 *
 * 换算手算用的友好点：字号 16 时 `design * 16 / 1280 = design / 80` 全是整值
 * （400 → 5.0、640 → 8.0、800 → 10.0、880 → 11.0、1200 → 15.0、1280 → 16.0、560 → 7.0）；
 * 字号 18 时出现小数（A = 11.25、空格 = 5.625），用来断言"整串一次截断"而不是逐字截断。
 */
internal const val SIDECAR_FIXTURE_PROPORTIONAL: String = "#generator c-glyphmetrics 单测夹具（不是 build_subset.py 产物）\n" +
    "#family Saitamaar\n" +
    "#unitsPerEm 1280\n" +
    "#size 16\n" +
    "0020 400 0\n" +
    "0030 640 0\n" +
    "0041 800 0\n" +
    "0042 800 80\n" +
    "0043 880 0\n" +
    "3042 1200 0\n" +
    "4E2D 1280 80\n" +
    "FF71 800 0\n"

/**
 * [SIDECAR_FIXTURE_PROPORTIONAL] 的 `#family` 值（与它**必须**一字不差；链按名字查表，
 * 改了夹具里的家族名而不同步这里，等于把回退链拆断 —— 用例里有断言把两者钉在一起）。
 * 名字依据：本机 fontTools 读语料 `font/Saitamaar.ttf` 的 name 表 nameID1，
 * 与 c-fontsub 的清单一致（`render/src/main/resources/fonts/subset-build-manifest.txt:10` family-name）。
 */
internal const val SIDECAR_FIXTURE_FAMILY: String = "Saitamaar"

/**
 * 「只有夹具」世界的读取口 —— 回退链的判据不许寄生在"外部目录恰好是空的"上。
 *
 * 为什么不用 [TextMetrics.classpathReader] 当默认：§3 成品（fonts 目录下那份
 * `…-advance.txt` 与子集 ttf）按授权处置**不入仓**（`.gitignore:31-32`），
 * CI 与任何新克隆一律读不到，所以"依赖 classpath 真文件存在"的断言必然退化成
 * [MetricProvenance.DEGRADED_GRID]；反过来，"依赖它不存在"也是同一个隐患的另一面
 * （本机跑 `build_subset.py` 之后目录就有东西了）。故把读得到/读不到显式写进这个读取口。
 *
 * @param entries 路径 → sidecar 文本；表外的路径返回 null（= §3 的"缺失"，由调用方退化，不抛）
 */
internal fun fixtureOnlyReader(vararg entries: Pair<String, String>): FontSidecarReader {
    val files = mapOf(*entries)
    return FontSidecarReader { path -> files[path] }
}

/**
 * 夹具的第二份表：家族名不同，用来构造"请求族没有表 → 回退到替代族"这条链。
 *
 * 数值与 [SIDECAR_FIXTURE_PROPORTIONAL] 里 U+0041 的那一条相同（800），
 * 这样"替代链命中"与"直接命中"在度量上可比，断言只看出处。
 */
internal const val SIDECAR_FIXTURE_SUBSTITUTE: String = "#generator c-glyphmetrics 单测夹具（不是 build_subset.py 产物）\n" +
    "#family SubstituteSerif\n" +
    "#unitsPerEm 1000\n" +
    "0041 500 0\n"
