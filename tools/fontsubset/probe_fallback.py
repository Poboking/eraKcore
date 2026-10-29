#!/usr/bin/env python3
"""缺字分档探针（GFX-12，纯标准库实现，可复跑）。

用途：把「随包子集缺了哪些码点」和「每个缺字按什么宽度档给确定性 advance」做成**可复跑**的读数。
真机问题：`saitamaar-subset.ttf` 只覆盖 5,268/8,076 个 required 码点（缺 2,808，34.77%），
缺的字回落到系统字体 ⇒ 宽度随设备变。度量层的对策见
`render/src/main/kotlin/com/eratw/port/render/glyph/CellMetrics.kt` 的 `fallbackClassOf`：
**只看码位**给档位，不看字体、不看设备装了什么系统字体。

分档表（与 `CellMetrics.fallbackClassOf` 逐值同表，改一处必须同批改另一处）：

    ZERO  0 格  U+0300..U+036F / U+20D0..U+20FF / U+FE00..U+FE0F / U+FE20..U+FE2F
    HALF  1 格  U+0000..U+001F / U+007F / U+0080 / U+0020..U+007E / U+00A5 / U+FF61..U+FF9F
    FULL  2 格  其余（东亚宽字符区、假名、CJK、私用区、增补平面 emoji、非法码位）

证据：组合符零宽 = 原版整串测量（`StringMeasure.cs:63-65` 的 MeasureCharacterRanges，
组合符并进基字簇）；半角档 = CP932 单字节（`EmueraConsole.cs:3255` 的 GetByteCount）；
全角 2 格 = 基准 `fixtures/golden/baseline/pc-golden-root.txt:61` 的 99 个 U+2501 填满 1797px。

输出（一律写 `build/` 或 stdout，**不进 git**；本目录不落任何生成文件）：

    build/fontsubset-probe/fallback-report.txt   缺字清单 + 分档汇总（默认产出）
    build/fontsubset-probe/fallback-classes.txt  全部 required 码点的分档表（--all 才产出）

重跑（任一条，输出都会被同目录覆盖刷新）：

    python tools/fontsubset/probe_fallback.py            # 自动找 render 模块 resources 下的子集 ttf
    python tools/fontsubset/probe_fallback.py <字体路径>  # 指定字体（换字体/换子集后复跑）
    python tools/fontsubset/probe_fallback.py --all      # 额外产出全码点分档表
    python tools/fontsubset/probe_fallback.py -o DIR     # 自定义输出目录（默认 build/fontsubset-probe）
    python tools/fontsubset/probe_fallback.py --stdout   # 报告打到 stdout，不落文件

输入依赖（都在本目录、已入库）：`required-codepoints.txt`（scan_codepoints.py 的产物）、
`missing-in-saitamaar-subset.txt`（上一轮探针的缺字清单）。找不到字体时退回该清单，
并在报告里显式写 `source-mode: missing-list` —— 不许把「清单」当「字体实测」读。
"""

from __future__ import annotations

import argparse
import os
import sys

from probe_metrics import Sfnt, fmt_cp, load_required

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_OUT = os.path.join(REPO_ROOT, "build", "fontsubset-probe")
REPORT_NAME = "fallback-report.txt"
CLASSES_NAME = "fallback-classes.txt"
DEFAULT_FONT = os.path.normpath(
    os.path.join(REPO_ROOT, "render", "src", "main", "resources", "fonts", "saitamaar-subset.ttf")
)
MISSING_LIST = os.path.join(HERE, "missing-in-saitamaar-subset.txt")
REQUIRED_LIST = os.path.join(HERE, "required-codepoints.txt")

ZERO_RANGES = ((0x0300, 0x036F), (0x20D0, 0x20FF), (0xFE00, 0xFE0F), (0xFE20, 0xFE2F))
HALF_RANGES = ((0x0000, 0x001F), (0x0020, 0x007E), (0xFF61, 0xFF9F))
HALF_SINGLES = (0x007F, 0x0080, 0x00A5)


def width_class(cp: int) -> str:
    """与 CellMetrics.fallbackClassOf 同一张表：返回 ZERO / HALF / FULL。"""
    if cp < 0 or cp > 0x10FFFF:
        return "FULL"
    for lo, hi in ZERO_RANGES:
        if lo <= cp <= hi:
            return "ZERO"
    for lo, hi in HALF_RANGES:
        if lo <= cp <= hi:
            return "HALF"
    if cp in HALF_SINGLES:
        return "HALF"
    return "FULL"


def class_cells(name: str) -> int:
    return {"ZERO": 0, "HALF": 1, "FULL": 2}[name]


def load_missing(path: str) -> list[int]:
    out = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("U+"):
                out.append(int(line[2:], 16))
    return out


def display_path(path: str) -> str:
    """报告里的路径尽量写成相对仓根的形式：绝对路径会随工作树漂，审计时读不了。"""
    try:
        rel = os.path.relpath(path, REPO_ROOT)
    except ValueError:
        return os.path.basename(path)
    return os.path.basename(path) if rel.startswith("..") else rel.replace("\\", "/")


def build_report(argv: list[str]) -> tuple[list[str], list[int], list[int], bool]:
    """汇总缺字清单与分档；返回 (报告行, required, missing, 有字体实测)。"""
    required = load_required(REQUIRED_LIST)
    if not required:
        print("no required-codepoints.txt; run scan_codepoints.py first", file=sys.stderr)
        return [], [], [], False
    args = [a for a in argv if not a.startswith("--") and a != "-o" and not a.startswith("-o=")]
    font_path = args[0] if args else (DEFAULT_FONT if os.path.isfile(DEFAULT_FONT) else None)
    lines: list[str] = [
        "# probe_fallback.py 产出：缺字清单 + 确定性宽度分档（GFX-12，UTF-8，禁手改）",
        "# 分档表与 CellMetrics.fallbackClassOf 逐值同表；证据见脚本模块注释",
        "# 口径：缺字形时 advance = cellsToPixels(档位格数)，与设备上有没有系统回退字无关",
        "# 复跑：python tools/fontsubset/probe_fallback.py --help",
    ]
    font = None
    if font_path is not None:
        try:
            font = Sfnt(font_path)
            missing = [cp for cp in required if not font.has(cp)]
            lines.append("source-font   : %s" % display_path(font_path))
            lines.append("source-mode   : font")
            lines.append("unitsPerEm    : %d" % font.units_per_em)
            lines.append("cmap-entries  : %d" % len(font.cmap))
        except Exception as exc:  # 字体坏了不能静默：退清单，但把原因写进报告
            lines.append("source-font   : %s (UNREADABLE %s)" % (font_path, exc))
            font = None
    if font is None:
        if not os.path.isfile(MISSING_LIST):
            print("no font and no %s; nothing to probe" % os.path.basename(MISSING_LIST), file=sys.stderr)
            return [], [], [], False
        missing = load_missing(MISSING_LIST)
        lines.append("source-font   : -")
        lines.append("source-mode   : missing-list (%s)" % os.path.basename(MISSING_LIST))

    required_set = set(required)
    missing = sorted(set(cp for cp in missing if cp in required_set))
    missing_set = set(missing)
    covered = [cp for cp in required if cp not in missing_set]
    lines.append("required      : %d" % len(required))
    lines.append("covered       : %d" % len(covered))
    lines.append("missing       : %d (%.2f%%)" % (len(missing), 100.0 * len(missing) / max(1, len(required))))
    if font is not None and os.path.isfile(MISSING_LIST):
        listed = set(load_missing(MISSING_LIST)) & required_set
        drift = missing_set.symmetric_difference(listed)
        lines.append("cross-check   : missing-list=%d font=%d drift=%d" % (len(listed), len(missing), len(drift)))
        for cp in sorted(drift)[:10]:
            lines.append("  drift %s" % fmt_cp(cp))

    buckets: dict[str, list[int]] = {"ZERO": [], "HALF": [], "FULL": []}
    for cp in missing:
        buckets[width_class(cp)].append(cp)
    lines.append("")
    lines.append("## 缺字分档汇总")
    for name in ("ZERO", "HALF", "FULL"):
        lines.append("%-5s n=%-5d cells=%d" % (name, len(buckets[name]), class_cells(name)))
    lines.append("")
    lines.append("## 缺字清单（码点 分档 格数）")
    for cp in missing:
        name = width_class(cp)
        lines.append("%s %s %d" % (fmt_cp(cp), name, class_cells(name)))
    return lines, required, missing, font is not None


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="probe_fallback.py",
        description="缺字分档探针（GFX-12）：列随包子集缺的码点，并按 CellMetrics.fallbackClassOf "
        "的同一张表给出每个缺字的确定性宽度档（ZERO/HALF/FULL = 0/1/2 半角格）。",
        epilog="输出默认写入 build/fontsubset-probe/（gitignore 覆盖，不进 git）；"
        "--stdout 时只打 stdout 不落文件。",
    )
    parser.add_argument("font", nargs="?", default=None,
                        help="字体路径（sfnt/ttf）；缺省自动找 render/src/main/resources/fonts/saitamaar-subset.ttf，"
                             "再缺省退回 missing-in-saitamaar-subset.txt 清单")
    parser.add_argument("--all", action="store_true",
                        help="额外产出全部 required 码点的分档表 fallback-classes.txt")
    parser.add_argument("-o", "--out-dir", default=DEFAULT_OUT,
                        help="输出目录（默认 %(default)s）")
    parser.add_argument("--stdout", action="store_true",
                        help="报告打到 stdout，不写文件")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    emit_all = args.all
    font_arg = [args.font] if args.font else []
    lines, required, missing, from_font = build_report(font_arg)
    if not required:
        return 1

    classes_rows: list[str] = []
    if emit_all:
        classes_rows.append("# probe_fallback.py --all 产出：全部 required 码点的分档（码点 分档 格数 有字形）")
        missing_set = set(missing)
        for cp in required:
            name = width_class(cp)
            classes_rows.append("%s %s %d %s" % (fmt_cp(cp), name, class_cells(name),
                                                 "no" if cp in missing_set else "yes"))

    if args.stdout:
        print("\n".join(lines))
        if emit_all:
            print("\n".join(classes_rows))
    else:
        os.makedirs(args.out_dir, exist_ok=True)
        report_path = os.path.join(args.out_dir, REPORT_NAME)
        with open(report_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        print("[done] required=%d missing=%d (%.2f%%) source=%s"
              % (len(required), len(missing),
                 100.0 * len(missing) / max(1, len(required)),
                 "font" if from_font else "missing-list"))
        print("report=%s" % report_path)
        if emit_all:
            classes_path = os.path.join(args.out_dir, CLASSES_NAME)
            with open(classes_path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write("\n".join(classes_rows) + "\n")
            print("classes=%s" % classes_path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
