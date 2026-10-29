#!/usr/bin/env python3
"""字体度量与覆盖探针（M4-FONT / t-font，纯标准库实现）。

用途：在没有 fonttools 的机器上，仍然能拿到**决定截图行列对齐**的两类硬数据：

    1. 参照字体的** Advance 比例**：验证 Grid 的「半角 1 格 / 全角 2 格」口径
       是否等于 PC 实际绘制字体的真实度量；框线/希腊/西里尔/罗马数字这类
       East-Asian-Width = Ambiguous 的字符到底占几格。
    2. 参照字体对 required-codepoints.txt 的**覆盖率**：哪些码点必须走缺字回退链。

为什么探针对象选 `C:/Windows/Fonts/simhei.ttf`：
    本包的 `emuera.config` 写的是 `FONT NAME:黑体`，而引擎只把 font 目录里的
    `*.ttf`/`*.otf` 注册成私有字体（Program.cs:131-140），随包的
    msgothic.ttc/msmincho.ttc 是 **.ttc 集合，压根不会被加载**；
    所以 PC 上真正参与绘制的是系统安装的「黑体」。用它当度量参照，
    比拿 Sarasa 的自述更贴近 M4 的截图基准。

限制（如实声明，别当作结论）：
    只读 head/hhea/maxp/cmap/hmtx 五张表，够算 advance；**不解释 GDI 字体链接
    （font linking）与 LineHeight/DPI 取整**，所以「SimHei 里没有的字形 PC 上画成什么」
    本探针给不出答案，需要在真机截图上核对。本脚本不产出任何字体二进制。

用法：
    python probe_metrics.py [字体路径 ...] [--required required-codepoints.txt]
"""

from __future__ import annotations

import os
import struct
import sys
from array import array

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FONTS = [
    r"C:/Windows/Fonts/simhei.ttf",
    r"D:/DSH/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf",
    r"C:/Windows/Fonts/msgothic.ttc",
]
# 口径判定用的取样码点：每类一个代表，覆盖 Kernel.Grid.isFullWidth 的争议区。
# 标签一律用 ASCII 码点号，避免控制台代码页（GBK）把输出打崩；完整报告写文件。
PROBES = [
    (0x0020, "space"),
    (0x0030, "ascii-digit"),
    (0x0041, "ascii-letter"),
    (0xFF71, "halfwidth-kana"),
    (0xFF61, "halfwidth-punct"),
    (0xFF21, "fullwidth-latin"),
    (0x3042, "hiragana"),
    (0x30AB, "katakana"),
    (0x4E2D, "cjk-ideograph"),
    (0x3001, "cjk-punct"),
    (0xFF1F, "fullwidth-punct"),
    (0x2500, "box-drawing-9250"),
    (0x2503, "box-drawing-9253"),
    (0x2589, "block-element"),
    (0x2192, "arrow"),
    (0x2160, "roman-numeral"),
    (0x03A9, "greek"),
    (0x0410, "cyrillic"),
    (0xFFE5, "fullwidth-yen"),
    (0x00A5, "yen-u00a5"),
    (0x2015, "bar-punct"),
    (0x2600, "misc-symbol"),
    (0x2801, "braille"),
    (0x3105, "bopomofo"),
]


class Sfnt:
    """最小 sfnt/ttc 读取器：只要 advance 与 cmap，其它表一概不碰。"""

    def __init__(self, path: str, index: int = 0):
        with open(path, "rb") as fh:
            self.data = fh.read()
        self.path = path
        base = self._font_offset(index)
        num_tables = self._u16(base + 4)
        self.tables: dict[str, tuple[int, int]] = {}
        for i in range(num_tables):
            rec = base + 12 + i * 16
            tag = self.data[rec:rec + 4].decode("latin-1")
            off = self._u32(rec + 8)
            length = self._u32(rec + 12)
            self.tables[tag] = (off, length)
        for need in ("head", "hhea", "maxp", "cmap", "hmtx"):
            if need not in self.tables:
                raise ValueError("%s 缺 %s 表" % (os.path.basename(path), need))
        head_off = self.tables["head"][0]
        self.units_per_em = self._u16(head_off + 18)
        hhea_off = self.tables["hhea"][0]
        self.num_h_metrics = self._u16(hhea_off + 34)
        self.num_glyphs = self._u16(self.tables["maxp"][0] + 4)
        self.cmap = self._build_cmap()
        self.hmtx = self._build_hmtx()

    # --- 基础读取 -------------------------------------------------------
    def _u16(self, off: int) -> int:
        return struct.unpack_from(">H", self.data, off)[0]

    def _u32(self, off: int) -> int:
        return struct.unpack_from(">I", self.data, off)[0]

    def _font_offset(self, index: int) -> int:
        tag = self.data[:4]
        if tag in (b"ttcf", b"OTTO", b"true", b"\x00\x01\x00\x00"):
            if tag == b"ttcf":
                count = self._u32(8)
                if index >= count:
                    raise ValueError("ttc 索引越界：%d/%d" % (index, count))
                return self._u32(12 + index * 4)
            return 0
        raise ValueError("不是 sfnt/ttc 字体：%s" % self.path)

    # --- cmap ----------------------------------------------------------
    def _build_cmap(self) -> dict[int, int]:
        off = self.tables["cmap"][0]
        num = self._u16(off + 2)
        subs = []
        for i in range(num):
            rec = off + 4 + i * 8
            platform = self._u16(rec)
            encoding = self._u16(rec + 2)
            subs.append((platform, encoding, off + self._u32(rec + 4)))
        # 优先 Unicode 全码点子表（3,10 / 0,4 / 0,6），再退回 BMP（3,1 / 0,3）
        full = [s for s in subs if s[1] in (10, 4, 6)]
        bmp = [s for s in subs if s[1] in (1, 3)]
        mapping: dict[int, int] = {}
        for platform, encoding, sub in bmp + full:
            fmt = self._u16(sub)
            if fmt == 4:
                mapping.update(self._cmap4(sub))
            elif fmt == 12:
                mapping.update(self._cmap12(sub))
            elif fmt == 6:
                mapping.update(self._cmap6(sub))
        return mapping

    def _cmap4(self, sub: int) -> dict[int, int]:
        seg_x2 = self._u16(sub + 6)
        seg = seg_x2 // 2
        end_off = sub + 14
        start_off = end_off + seg_x2 + 2
        delta_off = start_off + seg_x2
        range_off = delta_off + seg_x2
        out: dict[int, int] = {}
        for i in range(seg):
            end = self._u16(end_off + i * 2)
            start = self._u16(start_off + i * 2)
            delta = struct.unpack_from(">h", self.data, delta_off + i * 2)[0]
            ro = self._u16(range_off + i * 2)
            for cp in range(start, end + 1):
                if cp == 0xFFFF:
                    break
                if ro == 0:
                    gid = (cp + delta) & 0xFFFF
                else:
                    gpos = range_off + i * 2 + ro + (cp - start) * 2
                    gid = self._u16(gpos)
                    if gid != 0:
                        gid = (gid + delta) & 0xFFFF
                if gid != 0:
                    out[cp] = gid
        return out

    def _cmap6(self, sub: int) -> dict[int, int]:
        first = self._u16(sub + 6)
        count = self._u16(sub + 8)
        out: dict[int, int] = {}
        for i in range(count):
            gid = self._u16(sub + 10 + i * 2)
            if gid:
                out[first + i] = gid
        return out

    def _cmap12(self, sub: int) -> dict[int, int]:
        ngroups = self._u32(sub + 12)
        out: dict[int, int] = {}
        p = sub + 16
        for _ in range(ngroups):
            start = self._u32(p)
            end = self._u32(p + 4)
            gid0 = self._u32(p + 8)
            for cp in range(start, end + 1):
                g = gid0 + (cp - start)
                if g:
                    out[cp] = g
            p += 12
        return out

    # --- hmtx ----------------------------------------------------------
    def _build_hmtx(self) -> array:
        off = self.tables["hmtx"][0]
        adv = array("i", [0]) * self.num_glyphs
        last = 0
        for g in range(self.num_h_metrics):
            last = self._u16(off + g * 4)
            adv[g] = last
        for g in range(self.num_h_metrics, self.num_glyphs):
            adv[g] = last
        return adv

    def advance(self, cp: int) -> int | None:
        gid = self.cmap.get(cp)
        if gid is None or gid >= len(self.hmtx):
            return None
        return self.hmtx[gid]

    def has(self, cp: int) -> bool:
        return cp in self.cmap


def load_required(path: str) -> list[int]:
    cps = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("U+"):
                cps.append(int(line[2:], 16))
    return cps


def grid_cell(cp: int) -> int:
    if 0x20 <= cp <= 0x7E or 0xFF61 <= cp <= 0xFF9F:
        return 1
    return 2


def fmt_cp(cp: int) -> str:
    return "U+%04X" % cp if cp <= 0xFFFF else "U+%05X" % cp


def probe(path: str, required: list[int], lines: list[str]) -> None:
    """把一个字体的度量/覆盖结论追加到 lines（UTF-8 落盘），stdout 只打 ASCII 摘要。"""
    name = os.path.basename(path)
    try:
        font = Sfnt(path)
    except Exception as exc:
        lines.append("== %s : SKIP %s" % (name, exc))
        print("[skip] %s" % name)
        return
    ascii_adv = font.advance(0x0041) or 0
    lines.append("== %s  unitsPerEm=%d  numGlyphs=%d  cmapEntries=%d  advance(A)=%d"
                 % (name, font.units_per_em, font.num_glyphs, len(font.cmap), ascii_adv))
    lines.append("   %-20s %-9s %-9s %-8s %-6s %s"
                 % ("sample", "codepoint", "advance", "adv/A", "grid", "verdict"))
    mismatch = 0
    missing = 0
    for cp, label in PROBES:
        a = font.advance(cp)
        if a is None:
            missing += 1
            lines.append("   %-20s %-9s %-9s %-8s %-6d %s"
                         % (label, fmt_cp(cp), "-", "-", grid_cell(cp), "NOT-IN-CMAP"))
            continue
        ratio = a / ascii_adv if ascii_adv else float("nan")
        expect = grid_cell(cp) * ascii_adv if ascii_adv else 0
        tol = max(1, ascii_adv * 0.06)
        ok = bool(ascii_adv) and abs(a - expect) <= tol
        if not ok:
            mismatch += 1
        lines.append("   %-20s %-9s %-9d %-8.3f %-6d %s"
                     % (label, fmt_cp(cp), a, ratio, grid_cell(cp),
                        "MATCH-GRID" if ok else "GRID-MISMATCH"))
    if required:
        miss_all = [cp for cp in required if not font.has(cp)]
        lines.append("   coverage: required=%d hit=%d miss=%d (%.2f%%)"
                     % (len(required), len(required) - len(miss_all), len(miss_all),
                        100.0 * len(miss_all) / max(1, len(required))))
        with open(os.path.join(HERE, "missing-in-%s.txt" % os.path.splitext(name)[0]),
                  "w", encoding="utf-8", newline="\n") as fh:
            for cp in miss_all:
                fh.write(fmt_cp(cp) + "\n")
    lines.append("   summary: grid-mismatch=%d  probe-samples-not-in-cmap=%d" % (mismatch, missing))
    print("[done] %s  unitsPerEm=%d  cmap=%d  gridMismatch=%d" % (name, font.units_per_em, len(font.cmap), mismatch))


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    fonts = args if args else DEFAULT_FONTS
    req_path = os.path.join(HERE, "required-codepoints.txt")
    required = load_required(req_path) if os.path.isfile(req_path) else []
    if not required:
        print("no required-codepoints.txt; run scan_codepoints.py first", file=sys.stderr)
    lines: list[str] = [
        "# probe_metrics.py 产出：参照字体的 advance 度量与码点覆盖（UTF-8，禁手改）",
        "# 依据见脚本模块注释：PC 实际绘制字体取 emuera.config 的 FONT NAME（本包为黑体 simhei.ttf）",
        "# verdict 口径：advance == grid_cell(codepoint) * advance(A)，容差 max(1, 6% of advance(A))",
    ]
    for path in fonts:
        if not os.path.isfile(path):
            lines.append("== %s : SKIP file-not-found" % os.path.basename(path))
            continue
        probe(path, required, lines)
    out = os.path.join(HERE, "probe-report.txt")
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("report=%s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
