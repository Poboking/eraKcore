#!/usr/bin/env python3
"""随包像素字体包生成器（GFX-12 / 换字体波）。

## 为什么要有这个脚本

上一版随包字体只有 `saitamaar-subset.ttf`——Saitamaar 是**字符画用的比例字体**
（advance 空格 400 / 数字 640 / 拉丁 800 / CJK 1280，upem 1280），拿它当正文正文字体
等于「用错字体」：它对语料 8076 个码点只覆盖 5268 个（65.23%），其余 2808 个在屏上是
方框。本脚本按 `required-codepoints.txt`（语料码点闭包，唯一语义输入）逐个生成候选字体的
子集，并**如实记录**每个子集的覆盖率与 advance 分布，供装配层挑默认族。

## 输入与授权（判据：只允许可再分发的源）

| 族 | 源 | 许可 | 备注 |
|---|---|---|---|
| **Fusion Pixel 12px**（默认族） | `@fontpkg/fusion-pixel` 20220405 | OFL-1.1 | 缝合像素字体，upem 1200，半角 600 |
| Ark Pixel 12px | 仓内 `app/src/main/res/font/ark_pixel.ttf` | OFL-1.1 | 12px 点阵，upem 1200，半角 600 |
| Unifont 15.0.01 | `@fontpkg/unifont` 15.0.1 | OFL-1.1 / GPLv2+FE | 16px 点阵，upem 1024，半角 512 |

`msgothic.ttc` / `msmincho.ttc` / `simhei.ttf` 的 name ID 13 没有子集再分发权，
`guard_source` 硬性拒绝（它们只允许本机跑 `probe_metrics.py` 做度量参照）。

## 两个覆盖率口径（选默认族必须看后者）

* **种类覆盖** = 闭包 8076 个码点里命中多少个。`required-codepoints.txt` 是「有没有这个字」。
* **字数覆盖** = 按语料**实际出现次数**加权（`--gamedir` 给了才算）。这是「读起来卡不卡」。

两者会给出相反的结论：Ark 与 Fusion 的种类覆盖只差 10 个（7252 vs 7262），
但字数覆盖差 **5.7 倍**（99.522% vs 99.916%）—— Ark 缺的是 `恋`（87,504 次）、
`然`（56,883 次）这类高频简体字，它多出来的却是点字（175 个）与冷僻字。
**所以默认族是 Fusion，不是 Ark。**

## 产出（全部落 `render/src/main/resources/fonts/`）

1. `<slug>-subset.ttf`——`required-codepoints.txt` ∩ 源字体 cmap 的子集，`notdef_outline=True`
   （回退末端要有可画的框）。
2. `<slug>-advance.txt`——契约 §3 sidecar，**从落盘的成品重新打开读取**，逐码点
   `码点十六进制大写(无前缀) advance lsb`，升序、LF、无空行。slug 折叠规则照
   `FontSidecarCatalog.slugOf`：ASCII 小写、空白/`-`/`/`/反斜杠 折成单个 `-`。
3. `font-pack-manifest.txt`——复现凭据：源 sha256、产物 sha256、fonttools 版本、
   覆盖率读数、advance 分布。

## 可重复性

`TTFont(recalcTimestamp=False)` + `drop_tables += ["FFTM"]`：同一原件同一参数两次输出
逐字节相同（已实测）。

## 用法

    python build_font_pack.py                  # 全部重出 + 校验
    python build_font_pack.py --only ark       # 只重出某一族
    python build_font_pack.py --check          # 只报读数，不落盘
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REPO_FONTS_DIR = os.path.join(ANDROID_ROOT, "render", "src", "main", "resources", "fonts")
REQUIRED = os.path.join(HERE, "required-codepoints.txt")

# 微软字体：子集也不许进仓库（name ID 13 原文见 LICENSE-Saitamaar.txt）
DENIED_SOURCE_HINTS = ("msgothic", "msmincho", "msyh", "simhei", "simsun", "meiryo", "yugothic")

# 候选表。**顺序即装配侧默认族顺序**（第一个 = 默认族）。
# source 允许用环境变量覆盖（换机器时不必改脚本）。
# `family` 必须与 Kotlin 侧 BundledFontFamilies 的族名逐字一致，slug 由它折叠而来。
CANDIDATES = [
    {
        "id": "fusion",
        "family": "Fusion Pixel 12px",
        "slug": "fusion-pixel-12px",
        "size": 12,
        "source": os.environ.get("ERATW_FONT_FUSION", ""),
        "source_note": "npm @fontpkg/fusion-pixel 20220405.0.0 → package/fusion-pixel.ttf（OFL-1.1）",
        "license": "LICENSE-FusionPixel.txt",
    },
    {
        "id": "ark",
        "family": "Ark Pixel 12px",
        "slug": "ark-pixel-12px",
        "size": 12,
        "source": os.path.join(ANDROID_ROOT, "app", "src", "main", "res", "font", "ark_pixel.ttf"),
        "source_note": "仓内 app/src/main/res/font/ark_pixel.ttf（OFL-1.1，TakWolf/ark-pixel-font）",
        "license": "LICENSE-ArkPixel.txt",
    },
    {
        "id": "unifont",
        "family": "Unifont",
        "slug": "unifont",
        "size": 16,
        "source": os.environ.get("ERATW_FONT_UNIFONT", ""),
        "source_note": "npm @fontpkg/unifont 15.0.1 → package/unifont-15.0.01.ttf（OFL-1.1 / GPLv2+FE）",
        "license": "LICENSE-Unifont.txt",
    },
]


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def guard_source(path: str) -> None:
    name = os.path.basename(path).lower()
    for bad in DENIED_SOURCE_HINTS:
        if bad in name:
            raise SystemExit(
                "拒绝把 %s 当子集母体：name ID 13 无子集再分发权（只允许本机做度量参照）" % path
            )


def load_required() -> list[int]:
    cps = []
    with open(REQUIRED, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("U+"):
                cps.append(int(line[2:], 16))
    return cps


# 行终止符与不可渲染字符（口径同 scan_codepoints.py 的 DROP 表）
DROP_CODEPOINTS = {0x07, 0x09, 0x200B, 0x200D, 0x202C, 0x206A}
CORPUS_EXTS = (".erb", ".erh", ".erd", ".csv", ".als")


def load_corpus_freq(gamedir: str) -> dict[int, int]:
    """扫语料 `ERB/` 数**逐字符出现次数**（只读，不改任何文件）。

    为什么要有这个读数：码点**种类**覆盖率会骗人 —— Ark Pixel 与 Fusion Pixel 的
    种类覆盖只差 10 个（7252 vs 7262），但按**实际出现次数**算，Ark 缺 45.8 万次、
    Fusion 只缺 8.1 万次（差 5.7 倍）。原因是 Ark 缺的是 `恋`（87,504 次）、`然`（56,883 次）
    这类高频简体字，而它多出来的是点字与冷僻字。选默认族必须按后者判。
    """
    import collections

    root = os.path.join(gamedir, "ERB")
    if not os.path.isdir(root):
        return {}
    freq: dict[int, int] = collections.Counter()
    for dirpath, _, names in os.walk(root):
        for name in names:
            if not name.lower().endswith(CORPUS_EXTS):
                continue
            with open(os.path.join(dirpath, name), "rb") as fh:
                raw = fh.read()
            if raw.startswith(b"\xef\xbb\xbf"):
                text = raw[3:].decode("utf-8", "replace")
            else:
                try:
                    text = raw.decode("utf-8")
                except UnicodeDecodeError:
                    text = raw.decode("cp932", "replace")
            for ch in text:
                cp = ord(ch)
                if cp < 0x20 or cp in DROP_CODEPOINTS:
                    continue
                freq[cp] += 1
    return dict(freq)


def read_cmap_and_hmtx(path: str):
    """纯 fontTools 读 cmap 与 advance（子集成品要复核，不能只看内存对象）。"""
    from fontTools.ttLib import TTFont

    font = TTFont(path, lazy=True)
    cmap: dict[int, str] = {}
    for table in font["cmap"].tables:
        if table.isUnicode():
            cmap.update(table.cmap)
    hmtx = font["hmtx"].metrics
    upem = font["head"].unitsPerEm
    names = font.getGlyphOrder()
    adv = {}
    for cp, gname in cmap.items():
        adv[cp] = hmtx.get(gname)
    font.close()
    return cmap, adv, upem, len(names)


def build_one(cand: dict, required: list[int], check_only: bool, freq: dict[int, int]) -> dict:
    from fontTools import subset
    from fontTools.ttLib import TTFont

    src = cand["source"]
    if not src or not os.path.exists(src):
        raise SystemExit(
            "%s 的源字体缺失：%r\n"
            "用环境变量指路，例如：\n"
            "  ERATW_FONT_FUSION=/path/fusion-pixel.ttf ERATW_FONT_UNIFONT=/path/unifont.ttf python build_font_pack.py"
            % (cand["id"], src)
        )
    guard_source(src)

    cmap, _, upem, _ = read_cmap_and_hmtx(src)
    hit = [cp for cp in required if cp in cmap]
    miss = len(required) - len(hit)
    row = {
        "id": cand["id"],
        "family": cand["family"],
        "slug": cand["slug"],
        "source": src,
        "source_sha256": sha256(src),
        "source_note": cand["source_note"],
        "upem": upem,
        "hit": len(hit),
        "required": len(required),
        "miss": miss,
    }
    if freq:
        total = sum(freq.values())
        missing_runs = sum(n for cp, n in freq.items() if cp not in cmap)
        row["occ_total"] = total
        row["occ_missing"] = missing_runs
        row["occ_coverage"] = (total - missing_runs) * 100.0 / total
    print(
        "  %-8s upem=%-5d 种类覆盖 %d/%d (%.2f%%) 缺 %d"
        % (cand["id"], upem, len(hit), len(required), len(hit) * 100.0 / len(required), miss)
        + (
            " · 字数覆盖 %.3f%%（缺 %d 次）" % (row["occ_coverage"], row["occ_missing"])
            if freq
            else ""
        )
    )
    if check_only:
        return row

    font = TTFont(src, recalcTimestamp=False)
    options = subset.Options()
    options.notdef_outline = True
    options.recalc_timestamp = False
    options.drop_tables += ["FFTM"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=required)
    subsetter.subset(font)

    os.makedirs(REPO_FONTS_DIR, exist_ok=True)
    out_ttf = os.path.join(REPO_FONTS_DIR, cand["slug"] + "-subset.ttf")
    font.save(out_ttf)
    font.close()

    # sidecar 从**落盘成品**重新打开读取（不是内存对象，更不是原件）
    out_cmap, out_adv, out_upem, out_glyphs = read_cmap_and_hmtx(out_ttf)
    lines = [
        "#generator build_font_pack.py",
        "#family " + cand["family"],
        "#unitsPerEm %d" % out_upem,
        "#size %d" % cand["size"],
    ]
    adv_hist: dict[int, int] = {}
    for cp in sorted(out_cmap):
        metric = out_adv.get(cp)
        advance = metric[0] if metric else 0
        lsb = metric[1] if metric else 0
        adv_hist[advance] = adv_hist.get(advance, 0) + 1
        lines.append("%X %d %d" % (cp, advance, lsb))
    out_side = os.path.join(REPO_FONTS_DIR, cand["slug"] + "-advance.txt")
    with open(out_side, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")

    row.update(
        {
            "ttf": out_ttf,
            "ttf_bytes": os.path.getsize(out_ttf),
            "ttf_sha256": sha256(out_ttf),
            "sidecar": out_side,
            "sidecar_bytes": os.path.getsize(out_side),
            "sidecar_sha256": sha256(out_side),
            "glyphs": out_glyphs,
            "advance_hist": sorted(adv_hist.items(), key=lambda kv: -kv[1])[:6],
        }
    )
    return row


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=None, help="只处理某一族（id）")
    ap.add_argument("--check", action="store_true", help="只报读数，不落盘")
    ap.add_argument("--gamedir", default=None, help="语料根（用于算字数覆盖），缺省读 ERATW_GAMEDIR")
    args = ap.parse_args()

    try:
        import fontTools
    except ImportError:
        raise SystemExit("需要 fonttools：pip install fonttools==4.67.0")

    required = load_required()
    print("required = %d 码点" % len(required))
    gamedir = args.gamedir or os.environ.get("ERATW_GAMEDIR", "")
    freq = load_corpus_freq(gamedir) if gamedir else {}
    if freq:
        print("语料字频 = %d 种 / %d 次（%s）" % (len(freq), sum(freq.values()), gamedir))
    else:
        print("未给 --gamedir / ERATW_GAMEDIR ⇒ 只报码点种类覆盖，不报字数覆盖")
    rows = []
    for cand in CANDIDATES:
        if args.only and cand["id"] != args.only:
            continue
        rows.append(build_one(cand, required, args.check, freq))

    if args.check:
        return 0

    manifest = [
        "# 随包像素字体包 —— 复现凭据（build_font_pack.py 生成，勿手改）",
        "# fonttools %s" % fontTools.version,
        "# required-codepoints.txt sha256 %s" % sha256(REQUIRED),
        "",
    ]
    for row in rows:
        manifest.append("[%s]" % row["id"])
        manifest.append("family = %s" % row["family"])
        manifest.append("source = %s" % row["source_note"])
        manifest.append("source-sha256 = %s" % row["source_sha256"])
        manifest.append("unitsPerEm = %d" % row["upem"])
        manifest.append(
            "coverage = %d/%d (%.2f%%), miss %d"
            % (row["hit"], row["required"], row["hit"] * 100.0 / row["required"], row["miss"])
        )
        manifest.append("subset = %s-subset.ttf" % row["slug"])
        manifest.append("subset-sha256 = %s" % row["ttf_sha256"])
        manifest.append("subset-bytes = %d" % row["ttf_bytes"])
        manifest.append("sidecar = %s-advance.txt" % row["slug"])
        manifest.append("sidecar-sha256 = %s" % row["sidecar_sha256"])
        if "occ_coverage" in row:
            manifest.append(
                "occurrence-coverage = %.3f%% (%d/%d 次), miss %d"
                % (row["occ_coverage"], row["occ_total"] - row["occ_missing"], row["occ_total"], row["occ_missing"])
            )
        manifest.append("advance-top = %s" % row["advance_hist"])
        manifest.append("")
    out = os.path.join(REPO_FONTS_DIR, "font-pack-manifest.txt")
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(manifest))
    print("写出 %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
