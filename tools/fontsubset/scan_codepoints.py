#!/usr/bin/env python3
"""语料码点闭包扫描器（M4-FONT / t-font）。

只读扫描游戏目录，得出渲染层需要覆盖的**完整码点闭包**，产出确定性、可重复的清单：

    required-codepoints.txt   逐行 `U+XXXX`，升序，两次运行逐字节相同
    scan-report.txt           作用域/编码统计、剔除项、与黄金基准的交叉验证
    scan-manifest.txt         逐文件记录（相对路径、字节数、编码判定、码点数）

用法：
    python scan_codepoints.py [游戏目录] [--out 输出目录]

游戏目录解析顺序：位置参数 > 环境变量 ERATW_GAMEDIR > 内置绝对路径默认值。
本脚本**不向游戏目录写任何东西**（只以 rb 模式打开文件；见 assert_outside_game_dir）。

## 解码口径（先验证后写码，证据如下）

原版装载脚本/CSV 时用的**不是** `Config.Encode`，而是**逐文件自动探测**：

    MinorShift.Emuera.Sub/EraStreamReader.cs:52
        _fileLines = File.ReadAllLines(filepath, EncodingHandler.DetectEncoding(path));
    MinorShift.Emuera/ParserMediator.cs:75      同样走 DetectEncoding
    MinorShift.Emuera.Runtime.Utils/Preload.cs:33,72 预加载同样走 DetectEncoding
    MinorShift.Emuera.Runtime.Config/Config.cs:17     `Encode` 只用于**写**配置文件与日志
        （ConfigData.cs:429,646、DebugDialog.cs:291,309、KeyMacro.cs:68），与读语料无关

`EncodingHandler.DetectEncoding` 的三分支（EncodingHandler.cs:15-40）本脚本逐条复刻：

    1. 前 3 字节等于 EF BB BF      -> UTF-8（BOM 由 File.ReadAllLines 剥离）
    2. 否则整文件按**严格** UTF-8 解码成功 -> UTF-8
       （EncodingHandler.cs:9 的 UTF8Encoding 构造参数 throwOnInvalidBytes = true，
         解码失败会抛异常并被 EncodingHandler.cs:34-37 的 catch 捕获）
    3. 否则                        -> Shift-JIS / CP932（EncodingHandler.cs:11,70）

实测本语料：BOM 命中 4138 个文件，无 BOM 但严格 UTF-8 可解 55 个，
**走 CP932 分支的 0 个**（见 scan-report.txt 的 encoding 段），所以 CP932 表外字
（NEC/IBM 特殊文字）的口径分歧在本语料上不会实际发生。

CP932 分支的实现差异需要如实记录：Python 的 `cp932` 与 .NET 的 `GetEncoding(932)`
在 NEC 行表外字（U+00A5/U+2014/U+2236 等）上的映射不完全一致，本脚本用
`errors = "strict"`，一旦真有文件落到该分支就**直接失败报错**而不是猜（见 decode_file）。

## 行尾与不可渲染字符

`File.ReadAllLines` 会吃掉行终止符（.NET 认 6 种：CR LF、CR、LF、U+0085、U+2028、U+2029），
所以这些码点不会进入被渲染的文本；控制符/格式符也不可能进 cmap，故从请求集里剔除并
在 scan-report.txt 里逐个列出（含出现次数）供复核。剔除表用**显式码点区间**而不是
`unicodedata.category`，避免不同 Python 版本的 Unicode 数据把清单改动。
半角制表符 U+0009 单独剔除：它在测量前就被替换成 8 个空格
（StringMeasure.cs:59-61，对应 Kernel 里的 Grid.expandTabs），不需要字形。

## 确定性

排序键只有码点整数；文件遍历用 sorted 的目录序；输出换行固定 LF；不写时间戳、
不写绝对路径、不写 Unicode 名称。任何一项被破坏都会让两次运行产生差异，
verify_reproducible 参数会自检一次。
"""

from __future__ import annotations

import argparse
import os
import sys
from collections import Counter

DEFAULT_GAMEDIR = r"D:/DSH/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980"

# 原版预加载认可的扩展名集合（Preload.cs:59 逐字节对照：csv/erb/erh/erd/als，忽略大小写）
IN_SCOPE_EXTS = {".csv", ".erb", ".erh", ".erd", ".als"}
# 契约 M4-B §2 t-font 点名的目录；其余目录（README 集 / resources / 更新记录）引擎不加载
IN_SCOPE_DIRS = ("ERB", "CSV")

# 交给子集引擎的字形请求集不含以下码点（显式区间，理由见模块 docstring）
NON_RENDERABLE_RANGES = (
    (0x0000, 0x001F),  # C0 控制符（U+0009 制表符另计，见下）
    (0x007F, 0x009F),  # DEL + C1 控制符
    (0x00AD, 0x00AD),  # 软连字符
    (0x0600, 0x0605),  # 阿拉伯数字前缀符（Cf）
    (0x061C, 0x061C),
    (0x08E2, 0x08E2),
    (0x180E, 0x180E),
    (0x200B, 0x200F),  # 零宽空格/方向标记
    (0x202A, 0x202E),  # 方向格式符
    (0x2060, 0x2064),
    (0x2066, 0x206F),
    (0xFEFF, 0xFEFF),  # 零宽 BOM
    (0xFFF9, 0xFFFB),
)
# .NET ReadLine/ReadAllLines 认可的行终止符：不进文本，等同于剔除
LINE_TERMINATORS = ("\r\n", "\r", "\n", "\x85", "\u2028", "\u2029")

BOM = b"\xef\xbb\xbf"


def is_non_renderable(cp: int) -> bool:
    for lo, hi in NON_RENDERABLE_RANGES:
        if lo <= cp <= hi:
            return True
    # 制表符不需要字形：测量前展开成 8 空格（StringMeasure.cs:59-61）
    return cp == 0x0009


def decode_file(path: str) -> tuple[str, str]:
    """返回（文本，编码判定）。判定标签：utf-8-bom / utf-8 / cp932。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw[:3] == BOM:
        return raw[3:].decode("utf-8", "strict"), "utf-8-bom"
    try:
        return raw.decode("utf-8", "strict"), "utf-8"
    except UnicodeDecodeError:
        # 复刻 DetectEncoding 的第三分支。语料实测不走到这里；走到就说明口径变了，
        # 必须人工核对 CP932 映射，禁止静默替换成 replace 猜测。
        try:
            return raw.decode("cp932", "strict"), "cp932"
        except UnicodeDecodeError as exc:
            raise SystemExit(
                "编码判定失败：%s 既不是严格 UTF-8 也不是严格 CP932（%s）。\n"
                "TODO(verify) 需要人工核对，不允许猜测解码。" % (path, exc)
            )


def strip_line_terminators(text: str) -> str:
    for term in LINE_TERMINATORS:
        text = text.replace(term, "")
    return text


def net_splitlines(text: str) -> list[str]:
    """按 .NET ReadLine 认可的 6 种终止符切行（只用于逐行统计，不影响码点闭包）。"""
    parts = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    for term in ("\x85", "\u2028", "\u2029"):
        out: list[str] = []
        for p in parts:
            out.extend(p.split(term))
        parts = out
    return parts


def iter_scope_files(gamedir: str):
    """按（目录名、相对路径）升序产出作用域内文件，保证遍历序确定。"""
    for top in sorted(IN_SCOPE_DIRS):
        base = os.path.join(gamedir, top)
        if not os.path.isdir(base):
            continue
        for root, dirs, files in os.walk(base):
            dirs.sort()
            for name in sorted(files):
                if os.path.splitext(name)[1].lower() in IN_SCOPE_EXTS:
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, gamedir).replace(os.sep, "/")
                    yield rel, full


def assert_outside_game_dir(gamedir: str, outdir: str) -> None:
    g = os.path.realpath(gamedir).replace(os.sep, "/").lower()
    o = os.path.realpath(outdir).replace(os.sep, "/").lower()
    if o == g or o.startswith(g.rstrip("/") + "/"):
        raise SystemExit("输出目录落在游戏目录内，违反只读铁律：%s" % outdir)


def scan(gamedir: str):
    cps: Counter[int] = Counter()
    excluded: Counter[int] = Counter()
    manifest: list[tuple[str, int, str, int]] = []
    enc_counts: Counter[str] = Counter()
    chkfont_lines = 0
    nfiles = 0
    for rel, full in iter_scope_files(gamedir):
        text, enc = decode_file(full)
        enc_counts[enc] += 1
        nfiles += 1
        chkfont_lines += sum(1 for line in net_splitlines(text) if "CHKFONT" in line.upper())
        text = strip_line_terminators(text)
        local: Counter[int] = Counter()
        for ch in text:
            cp = ord(ch)
            local[cp] += 1
            if is_non_renderable(cp):
                excluded[cp] += 1
        cps.update(local)
        manifest.append((rel, len(text.encode("utf-8")), enc, len(local)))
    return nfiles, cps, excluded, enc_counts, manifest, chkfont_lines


def grid_cell(cp: int) -> int:
    """与 Kernel 的 Grid.cellWidth 同口径（半角 1 / 全角 2），不含辅助平面拆分。"""
    if 0x20 <= cp <= 0x7E:
        return 1
    if 0xFF61 <= cp <= 0xFF9F:
        return 1
    return 2


def fmt_cp(cp: int) -> str:
    return "U+%04X" % cp if cp <= 0xFFFF else "U+%05X" % cp


def write_lines(path: str, lines: list[str]) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))
        if lines:
            fh.write("\n")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="扫描 era 语料得出字体子集所需码点闭包")
    ap.add_argument("gamedir", nargs="?", default=os.environ.get("ERATW_GAMEDIR", DEFAULT_GAMEDIR))
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--golden", default=None, help="黄金基准文本，用于交叉验证（默认读 ERATW_ROOT 下路径）")
    ap.add_argument("--selfcheck", action="store_true", help="再扫一遍，校验两次结果逐字节相同")
    args = ap.parse_args(argv)

    gamedir = os.path.abspath(args.gamedir)
    if not os.path.isdir(gamedir):
        raise SystemExit("游戏目录不存在：%s" % gamedir)
    outdir = os.path.abspath(args.out)
    assert_outside_game_dir(gamedir, outdir)
    os.makedirs(outdir, exist_ok=True)

    nfiles, cps, excluded, enc_counts, manifest, chkfont_lines = scan(gamedir)
    required = sorted(cp for cp in cps if not is_non_renderable(cp))

    cp_lines = [fmt_cp(cp) for cp in required]
    write_lines(os.path.join(outdir, "required-codepoints.txt"), cp_lines)

    report: list[str] = []
    report.append("# required-codepoints 扫描报告（scan_codepoints.py 产出，禁手改）")
    report.append("# 解码口径与行终止符/剔除规则的依据见 scan_codepoints.py 模块注释")
    report.append("scope-dirs            : " + ", ".join(sorted(IN_SCOPE_DIRS)))
    report.append("scope-exts            : " + ", ".join(sorted(IN_SCOPE_EXTS)))
    report.append("files-scanned         : %d" % nfiles)
    for enc in sorted(enc_counts):
        report.append("files-encoding-%-8s: %d" % (enc, enc_counts[enc]))
    report.append("codepoints-observed   : %d" % len(cps))
    report.append("codepoints-required   : %d" % len(required))
    report.append("codepoints-excluded   : %d" % len(excluded))
    report.append("")
    report.append("## 剔除的非渲染码点（出现次数为全语料累计）")
    for cp in sorted(excluded):
        report.append("  %s  count=%d" % (fmt_cp(cp), excluded[cp]))
    report.append("")
    report.append("## 半角/全角分布（Grid.cellWidth 口径）")
    cells = Counter(grid_cell(cp) for cp in required)
    report.append("  1cell=%d  2cell=%d" % (cells[1], cells[2]))
    plane = Counter(cp >> 16 for cp in required)
    report.append("  plane:" + "  ".join("BMP+%d=%d" % (p, plane[p]) for p in sorted(plane)))
    astral = [cp for cp in required if cp > 0xFFFF]
    if astral:
        report.append("  辅助平面码点（Kotlin Char 只能按代理对处理，需 m-grid 决定口径）：")
        report.append("    " + " ".join(fmt_cp(c) for c in astral))
    report.append("")
    report.append("## 易漏区段命中情况（子集必须逐段核对）")
    SEGMENTS = [
        ("ASCII 可打印", 0x0020, 0x007E),
        ("拉丁扩展 A", 0x00A0, 0x017F),
        ("希腊", 0x0370, 0x03FF),
        ("西里尔", 0x0400, 0x04FF),
        ("组合附加符号", 0x0300, 0x036F),
        ("日文假名", 0x3040, 0x30FF),
        ("CJK 符号标点", 0x3000, 0x303F),
        ("半角假名/半角符号", 0xFF61, 0xFF9F),
        ("全角 ASCII", 0xFF01, 0xFF5E),
        ("框线", 0x2500, 0x257F),
        ("块元素", 0x2580, 0x259F),
        ("箭头", 0x2190, 0x21FF),
        ("罗马数字", 0x2160, 0x218F),
        ("一般标点", 0x2000, 0x206F),
        ("符号杂项", 0x2600, 0x26FF),
        ("几何图形", 0x25A0, 0x25FF),
        ("注音符号", 0x3100, 0x312F),
        ("谚文", 0x1100, 0x11FF),
        ("点字", 0x2800, 0x28FF),
        ("货币符号", 0x20A0, 0x20CF),
        ("私用区 PUA", 0xE000, 0xF8FF),
        ("CJK 基本", 0x4E00, 0x9FFF),
        ("CJK 扩展 A", 0x3400, 0x4DBF),
        ("CJK 兼容表意", 0xF900, 0xFAFF),
        ("CJK 扩展 B（平面 2）", 0x20000, 0x2A6DF),
        ("Emoji 杂项（平面 1）", 0x1F000, 0x1FAFF),
    ]
    reqset = set(required)
    for name, lo, hi in SEGMENTS:
        hit = sum(1 for cp in reqset if lo <= cp <= hi)
        report.append("  %-22s %s-%s  required=%d" % (name, fmt_cp(lo), fmt_cp(hi), hit))
    report.append("")
    report.append("## Grid 口径与 East-Asian-Width 的分歧（截图列对齐风险）")
    try:
        import unicodedata

        disagree = []
        for cp in required:
            eaw = unicodedata.east_asian_width(chr(cp))
            gw = grid_cell(cp)
            narrow_per_eaw = eaw in ("Na", "H")
            if narrow_per_eaw != (gw == 1):
                disagree.append((cp, eaw, gw))
        report.append("  按 EAW 判定的分歧数：%d" % len(disagree))
        for cp, eaw, gw in disagree:
            report.append("    %s  EAW=%s  Grid=%dcell" % (fmt_cp(cp), eaw, gw))
        ambiguous = [cp for cp in required if unicodedata.east_asian_width(chr(cp)) == "A"]
        report.append("  EAW=Ambiguous（宽度由应用决定，共 %d 个）——子集必须按 2 格字形出，" % len(ambiguous))
        report.append("  否则与 Grid.cellWidth 的 2 格假设冲突（详见 README 第 3 节）")
    except Exception as exc:  # Unicode 数据版本差异不得影响主清单
        report.append("  未做 EAW 交叉核对：%s" % exc)
    report.append("")
    report.append("## 交叉验证：黄金基准所需码点是否被闭包覆盖")
    golden = args.golden
    if golden is None:
        root = os.environ.get("ERATW_ROOT")
        if root:
            golden = os.path.join(
                root, "eratw-android", "fixtures", "golden", "baseline", "pc-golden-root.txt")
    if golden and os.path.isfile(golden):
        gtext = strip_line_terminators(decode_file(golden)[0])
        gset = {ord(c) for c in gtext if not is_non_renderable(ord(c))}
        miss = sorted(gset - reqset)
        report.append("  golden=%s" % os.path.basename(golden))
        report.append("  golden-codepoints=%d  未被闭包覆盖=%d" % (len(gset), len(miss)))
        if miss:
            report.append("  缺失：" + " ".join(fmt_cp(c) for c in miss))
    else:
        report.append("  未提供 --golden（或文件不存在），跳过交叉验证")
    report.append("")
    report.append("## CHKFONT 分支普查（字体缺失会改变 VM 语义，不只是渲染）")
    report.append("  语料中含 CHKFONT/CHKFONTNAME 的行数：%d" % chkfont_lines)
    report.append("  原版 CHKFONT 同时查系统安装字体与私有字体集")
    report.append("  （FunctionMethodCreator.cs:3488-3507：InstalledFontCollection + GlobalStatic.Pfc）")
    report.append("  私有字体只加载 font 目录的 *.ttf 与 *.otf（Program.cs:131-140），")
    report.append("  随包的 msgothic.ttc/msmincho.ttc 因是 .ttc **根本没被加载**，")
    report.append("  PC 上的实际绘制字体是 emuera.config 里 FONT NAME 指定的系统字体（本包为黑体）。")

    write_lines(os.path.join(outdir, "scan-report.txt"), report)
    write_lines(
        os.path.join(outdir, "scan-manifest.txt"),
        ["# relpath<TAB>utf8bytes<TAB>encoding<TAB>distinct-codepoints-in-file"] +
        ["%s\t%d\t%s\t%d" % m for m in manifest],
    )

    print("files=%d  required-codepoints=%d  excluded=%d" % (nfiles, len(required), len(excluded)))
    print("out=%s" % outdir)
    if args.selfcheck:
        again = scan(gamedir)[1]
        again_required = sorted(cp for cp in again if not is_non_renderable(cp))
        if [fmt_cp(c) for c in again_required] != cp_lines:
            raise SystemExit("自检失败：两次扫描结果不一致")
        print("selfcheck=OK（两次扫描逐字节相同）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
