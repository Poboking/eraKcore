#!/usr/bin/env python3
"""Saitamaar 子集生成器（M4-FONT 后半 / c-fontsub，真出二进制）。

## 事实更新（本波前提变化）

上一波（M4-B）本机没有 fonttools，只交付了清单与脚本骨架；且那版脚本按
Sarasa Mono SC 假设写，从未真实执行过，若干 fontTools API 用法是错的
（`SubsettingOptions`/`options.output`/`options.name_ids` 在 4.67.0 里不存在）。
本版对着 **fonttools 4.67.0 + Python 3.14.3** 实跑重写：`Options` 类、
`Subsetter.populate(unicodes=...)`、`TTFont(..., recalcTimestamp=...)`。

## 输入与授权（判据①，逐条证据见 render/src/main/resources/fonts/LICENSE-Saitamaar.txt）

- 源字体：语料 `eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf`（只读，一字节不改）。
  语料内唯一可再分发的原件（授权定案：**公有领域**，作者本人仓 keage/Saitamaar 的 Licence 节
  原文 "Saitamaar is in the public domain."；前一波按契约错标成 OFL，f-license 路已纠正。
  TTF name 表本来就没有 13/14 许可证记录，与 PD 声明无冲突，且无 Reserved Font Name ⇒ 子集不必改名）。
- `msgothic.ttc`/`msmincho.ttc`/`simhei.ttf` 的 name ID 13 原文为
  "Microsoft supplied font... You may only (i) embed this font in content as permitted by the
  embedding restrictions..."——没有子集再分发权，**子集禁入库**（guard_source 硬性拒绝，
  它们只允许本机跑 probe_metrics.py 做度量参照）。
- 语料 `font/字体与显示.txt:1`：「font中的字体都是游戏必须的；Saitamaar.ttf是字符画所需的字体，现默认搭载。」
  `:12`：「作者的编译测试环境是字号16、行高18.」→ sidecar 的 `#size` 取 16。

## 产出（全部落 render/src/main/resources/fonts/，路径由脚本自算）

1. `saitamaar-subset.ttf`——required-codepoints.txt（8076 码点）∩ 源字体 cmap（5268 码点）的子集。
2. `saitamaar-advance.txt`——契约 §3 sidecar：advance/lsb 设计单元度量表，
   **从落盘的子集成品重新打开读取**（不是从内存对象、更不是原件），逐码点：
   `码点十六进制大写(无前缀) advance lsb`，升序、LF、无空行；源 cmap 没有的码点不出行。
3. `subset-build-manifest.txt`——复现凭据：源/清单/产物的 sha256、fonttools 版本、子集参数。
4. 校验读数（--verify，判据②）：对 required 逐项实测 hit/miss，miss 集合与
   missing-in-Saitamaar.txt 比对；sidecar 每行用纯标准库的 probe_metrics.Sfnt **独立复读**一次。

## 可重复性（判据⑤）

- `TTFont(recalcTimestamp=False)`：`head.modified` 保持源值（3512211716），不写当前时间；
- `drop_tables += ["FFTM"]`：去掉 FontForge 的 built-in 时间戳表；
- fontTools 子集化对「同一原件+同一版本+同一参数」输出逐字节相同，连跑两次 sha256 相同（已实测）。
  若某天两遍 sha 变了，先查 fontTools 版本是否漂移（manifest 里有记录）。

## 宽度口径（判据④，M4-REALCHECK ②）

Saitamaar 是**比例字体**（unitsPerEm=1280）：空格 advance=400、数字 640、拉丁 800、
CJK 1280，连全角块内也不统一（如 FF21=880）。本脚本与 --verify 一律**如实记录**这些数值，
绝不为了凑「半角1格/全角2格」网格改写或伪造；1:2 断言只适用于等宽参照字体
（probe_metrics.py 对 simhei 的 GRID 判定），在本子集上宽度审计只报分布、不设闸。

## 用法

    python build_subset.py                     # 默认：语料源 → 仓内 fonts/，带 --verify
    python build_subset.py --font X --out-dir Y --size 16 --no-verify

禁地提醒：本路不动 Kotlin（消费方 c-glyphmetrics 按 §3 读 sidecar）。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REPO_FONTS_DIR = os.path.join(ANDROID_ROOT, "render", "src", "main", "resources", "fonts")
CORPUS_SOURCE = r"D:/DSH/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf"

# 允许入库再分发的源字体文件名关键字（判据①）
ALLOWED_SOURCE_HINTS = ("saitamaar",)
# 微软字体：子集也不许进仓库（name ID 13 原文证据见 LICENSE-Saitamaar.txt）
DENIED_SOURCE_HINTS = ("msgothic", "msmincho", "msyh", "simhei", "simsun", "meiryo", "yugothic")


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_required(path: str) -> list[int]:
    cps = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("U+"):
                cps.append(int(s[2:], 16))
    if not cps:
        raise SystemExit("required 清单为空或格式不对：%s（先跑 scan_codepoints.py）" % path)
    if len(cps) != len(set(cps)):
        raise SystemExit("required 清单有重复码点，先修 scan_codepoints.py 再谈子集")
    return sorted(cps)


def guard_source(font_path: str, allow_any: bool) -> None:
    name = os.path.basename(font_path).lower()
    if any(h in name for h in DENIED_SOURCE_HINTS):
        raise SystemExit(
            "拒绝用 %s 生成入库资产：微软字体 name ID 13 无子集再分发权（判据①）。\n"
            "它只能本机跑 probe_metrics.py 做度量参照，产物禁止进仓库。" % font_path)
    if not allow_any and not any(h in name for h in ALLOWED_SOURCE_HINTS):
        raise SystemExit(
            "中止：文件名不含 saitamaar，无法对应语料内唯一可再分发原件的授权链。"
            "确认许可证逐条核验通过后再加 --i-own-the-license 明确声明。")


def import_fonttools():
    try:
        import fontTools
        from fontTools.subset import Options, Subsetter
        from fontTools.ttLib import TTFont
    except ImportError:
        raise SystemExit(
            "缺 fonttools，本脚本不生成任何东西（没有工具时生成＝伪造）。\n"
            "安装：pip install fonttools==4.67.0")
    return fontTools, Options, Subsetter, TTFont


def open_source(font_path: str, index: int, TTFont):
    if font_path.lower().endswith((".ttc", ".otc")):
        font = TTFont(font_path, fontNumber=index, recalcTimestamp=False)
    else:
        font = TTFont(font_path, recalcTimestamp=False)
    family = font["name"].getDebugName(1)
    if family != "Saitamaar":
        raise SystemExit("源字体 name ID 1 = %r，不是授权的 Saitamaar，中止" % family)
    return font, family


def build_subset(font_path: str, index: int, required: list[int], out_dir: str,
                 TTFont, Options, Subsetter) -> str:
    out_path = os.path.join(out_dir, "saitamaar-subset.ttf")
    options = Options()
    options.notdef_outline = True          # 缺字方框画得出来，回退链末端依赖它
    options.recalc_bounds = True           # 子集后按留下的字形重算包围盒（确定性）
    options.recalc_timestamp = False       # head.modified 不写当前时间（判据⑤）
    options.drop_tables = list(options.drop_tables) + ["FFTM"]  # FontForge 时间戳表
    options.name_IDs = {0, 1, 2, 3, 4, 5, 6}  # 保留署名/版本记录，name 表原样
    options.name_languages = list(options.name_languages)  # 不动语言副本集合，只按 name_IDs 过滤
    options.layout_features = ["*"]
    options.hinting = True
    options.desubroutinize = False
    options.retain_gids = False            # gid 重编号是子集压缩体积的主要手段

    font, _family = open_source(font_path, index, TTFont)
    subsetter = Subsetter(options=options)
    subsetter.populate(unicodes=required)
    subsetter.subset(font)
    font.save(out_path)
    return out_path


def family_slug(family: str) -> str:
    return "".join(c if c.isalnum() else "-" for c in family.lower()).strip("-")


def write_sidecar(subset_path: str, out_dir: str, size: int, TTFont) -> tuple[str, int, dict]:
    """判据③：从**落盘的子集成品**重新打开读度量，产出契约 §3 sidecar，一字不差。

    返回 (路径, 行数, 家族/unitsPerEm 信息)。缺失码点不出行（§3：由消费方走回退链）。
    """
    font = TTFont(subset_path, recalcTimestamp=False)   # 读成品文件，不读内存里的原件对象
    family = font["name"].getDebugName(1)
    upem = font["head"].unitsPerEm
    cmap = font.getBestCmap()                            # cp -> glyphName（只含成品 cmap 里的码点）
    hmtx = font["hmtx"]
    rows = []
    for cp in sorted(cmap):
        advance, lsb = hmtx[cmap[cp]]
        rows.append("%X %d %d" % (cp, advance, lsb))
    path = os.path.join(out_dir, "%s-advance.txt" % family_slug(family))
    header = [
        "#generator build_subset.py",
        "#family %s" % family,
        "#unitsPerEm %d" % upem,
        "#size %d" % size,
    ]
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(header + rows) + "\n")
    font.close()
    return path, len(rows), {"family": family, "unitsPerEm": upem, "size": size}


def write_manifest(out_dir: str, font_path: str, required_path: str, subset_path: str,
                   sidecar_path: str, required: list[int], meta: dict,
                   fonttools_version: str) -> str:
    lines = [
        "# 由 build_subset.py 生成，禁手改（复现凭据，审计时三样齐才算可复现）",
        "source-font        : %s" % font_path,
        "source-sha256      : %s" % sha256(font_path),
        "source-size        : %d" % os.path.getsize(font_path),
        "required-manifest  : %s" % os.path.relpath(required_path, HERE).replace("\\", "/"),
        "required-sha256    : %s" % sha256(required_path),
        "required-count     : %d" % len(required),
        "fonttools-version  : %s" % fonttools_version,
        "python-version     : %s (%s)" % (platform.python_version(), platform.machine()),
        "family-name        : %s" % meta["family"],
        "unitsPerEm         : %d" % meta["unitsPerEm"],
        "sidecar-size-px    : %d" % meta["size"],
        "subset-output      : %s" % os.path.basename(subset_path),
        "subset-sha256      : %s" % sha256(subset_path),
        "subset-size        : %d" % os.path.getsize(subset_path),
        "subset-numGlyphs   : %d" % meta["numGlyphs"],
        "sidecar-output     : %s" % os.path.basename(sidecar_path),
        "sidecar-sha256     : %s" % sha256(sidecar_path),
        "sidecar-rows       : %d" % meta["sidecarRows"],
        "coverage-hit       : %d" % meta["hit"],
        "coverage-miss      : %d" % meta["miss"],
        "head-modified      : %d" % meta["headModified"],
    ]
    path = os.path.join(out_dir, "subset-build-manifest.txt")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def fmt_cp(cp: int) -> str:
    return "U+%04X" % cp if cp <= 0xFFFF else "U+%05X" % cp


def verify(subset_path: str, sidecar_path: str, required: list[int],
           missing_ref: str | None, TTFont) -> int:
    """判据②＋③交叉验证；宽度只报分布不设闸（判据④）。返回 0=绿。"""
    sys.path.insert(0, HERE)
    from probe_metrics import Sfnt  # 纯标准库的第二重独立读取器

    font = TTFont(subset_path, recalcTimestamp=False)
    cmap = font.getBestCmap()
    sfnt = Sfnt(subset_path)  # 另开一条实现路径读同一成品

    miss = [cp for cp in required if cp not in cmap]
    hit = len(required) - len(miss)
    print("coverage: required=%d hit=%d miss=%d" % (len(required), hit, len(miss)))

    rc = 0
    if missing_ref and os.path.isfile(missing_ref):
        ref = set()
        with open(missing_ref, "r", encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if s.startswith("U+"):
                    ref.add(int(s[2:], 16))
        extra_in_ref = sorted(ref - set(miss))     # 参考表说缺、成品却有 → 成品多了没要求的字？不该发生
        extra_in_miss = sorted(set(miss) - ref)    # 成品缺、参考表说有不缺 → 覆盖率回退
        if extra_in_ref or extra_in_miss:
            rc = 1
            print("MISS-LIST-DIFF ref-only=%d subset-only=%d" % (len(extra_in_ref), len(extra_in_miss)))
            for cp in extra_in_ref[:10]:
                print("  ref-says-miss-but-present %s" % fmt_cp(cp))
            for cp in extra_in_miss[:10]:
                print("  subset-miss-but-ref-says-hit %s" % fmt_cp(cp))
        else:
            print("miss 集合与 %s 逐项一致（%d 条，判据②复核通过：源字体本就没有）"
                  % (os.path.basename(missing_ref), len(miss)))

    # Sfnt 与 fontTools 两套读取器对 cmap 的口径也要一致
    sfnt_hit = sum(1 for cp in required if sfnt.has(cp))
    if sfnt_hit != hit:
        rc = 1
        print("READER-DIFF fontTools hit=%d vs probe_metrics.Sfnt hit=%d" % (hit, sfnt_hit))
    else:
        print("reader cross-check: fontTools 与 Sfnt 覆盖读数一致（%d）" % hit)

    # sidecar 每行与成品对账
    rows = []
    with open(sidecar_path, "r", encoding="utf-8") as fh:
        for line in fh:
            s = line.rstrip("\n")
            if s.startswith("#"):
                continue
            if s:
                rows.append(s)
    cps = [int(r.split(" ")[0], 16) for r in rows]
    if cps != sorted(cps):
        rc = 1
        print("SIDECAR-NOT-SORTED")
    if len(rows) != len(cmap):
        rc = 1
        print("SIDECAR-ROWS=%d != subset-cmap=%d" % (len(rows), len(cmap)))
    extras = sorted(set(cps) - set(required))
    if extras:
        # 合法超集来源：bidi_closure（fontTools 4.67 subset/__init__.py:3066-3072）会拉入
        # 请求码点的镜像对偶（本次 U+2252 → U+2253）。不是漏裁，如实报告。
        print("sidecar extra cps outside required (bidi-closure 拉入，+%d): %s"
              % (len(extras), " ".join(fmt_cp(c) for c in extras)))
    bad = []
    for cp, row in zip(cps, rows):
        _, a, l = row.split(" ")
        a = int(a)
        if sfnt.advance(cp) != a:
            bad.append(cp)
    if bad:
        rc = 1
        print("SIDECAR-ADVANCE-MISMATCH=%d 例：%s" % (len(bad), ", ".join(fmt_cp(c) for c in bad[:10])))
    else:
        print("sidecar: %d 行，advance 逐行与 Sfnt 复读一致" % len(rows))

    # 宽度审计：如实报告分布，不设 1:2 闸（判据④——比例字体伪造数值即返工）
    hmtx = font["hmtx"]
    buckets = {
        "space U+0020": [0x20],
        "ascii digits": list(range(0x30, 0x3A)),
        "ascii letters": list(range(0x41, 0x5B)) + list(range(0x61, 0x7B)),
        "CJK 4E00-9FFF": [cp for cp in cps if 0x4E00 <= cp <= 0x9FFF],
        "kana 3040-30FF": [cp for cp in cps if 0x3040 <= cp <= 0x30FF],
        "fullwidth FF01-FF60": [cp for cp in cps if 0xFF01 <= cp <= 0xFF60],
    }
    print("width audit（设计单元，unitsPerEm=%d；比例字体，只记录不设闸）：" % font["head"].unitsPerEm)
    for label, cps_ in buckets.items():
        advs = sorted({hmtx[cmap[cp]][0] for cp in cps_ if cp in cmap})
        print("  %-20s 码点=%-5d distinct advance=%s" % (label, len(cps_), advs[:12]))
    print("verify: %s" % ("GREEN" if rc == 0 else "RED"))
    return rc


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="生成 Saitamaar 子集 + §3 sidecar（需 fonttools）")
    ap.add_argument("--font", default=CORPUS_SOURCE, help="源字体路径（语料只读）")
    ap.add_argument("--font-index", type=int, default=0, help="集合内序号（.ttc/.otc 用）")
    ap.add_argument("--required", default=os.path.join(HERE, "required-codepoints.txt"))
    ap.add_argument("--out-dir", default=REPO_FONTS_DIR)
    ap.add_argument("--size", type=int, default=16, help="sidecar #size：参考字号 px（作者环境 16，字体与显示.txt:12）")
    ap.add_argument("--verify", dest="verify", action="store_true", default=True)
    ap.add_argument("--no-verify", dest="verify", action="store_false")
    ap.add_argument("--missing-ref", default=os.path.join(HERE, "missing-in-Saitamaar.txt"))
    ap.add_argument("--i-own-the-license", dest="allow_any", action="store_true",
                    help="声明输入字体确实可再分发（跳过文件名授权闸）")
    args = ap.parse_args(argv)

    guard_source(args.font, args.allow_any)
    fontTools, Options, Subsetter, TTFont = import_fonttools()
    required = load_required(args.required)
    os.makedirs(args.out_dir, exist_ok=True)

    subset_path = build_subset(args.font, args.font_index, required, args.out_dir,
                               TTFont, Options, Subsetter)
    sidecar_path, sidecar_rows, meta = write_sidecar(subset_path, args.out_dir, args.size, TTFont)

    font = TTFont(subset_path, recalcTimestamp=False)
    meta.update({
        "numGlyphs": font["maxp"].numGlyphs,
        "sidecarRows": sidecar_rows,
        "headModified": font["head"].modified,
        "hit": sum(1 for cp in required if cp in font.getBestCmap()),
    })
    meta["miss"] = len(required) - meta["hit"]
    font.close()

    manifest_path = write_manifest(args.out_dir, args.font, args.required, subset_path,
                                   sidecar_path, required, meta, fontTools.version)
    print("subset   : %s (%d bytes, sha256=%s)" % (subset_path, os.path.getsize(subset_path), sha256(subset_path)))
    print("sidecar  : %s (%d rows)" % (sidecar_path, sidecar_rows))
    print("manifest : %s" % manifest_path)
    if args.verify:
        return verify(subset_path, sidecar_path, required, args.missing_ref, TTFont)
    return 0


if __name__ == "__main__":
    sys.exit(main())
