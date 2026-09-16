#!/usr/bin/env python3
"""生成 Linux 端的图标字体 Assets/LinIcons.ttf。

界面图标写的是 Segoe MDL2 Assets 的码位,那是 Windows 自带字体、不能随包分发 ——
Linux 上一个都画不出来(整排豆腐块)。这里从 Fluent UI System Icons(MIT)里
挑外形对应的字形,**按 MDL2 原码位**重新编进一个小字体,界面代码一个码位都不用改。

新加图标:先在 MAP 里补一行再跑本脚本,漏了的码位在 Linux 上是豆腐块。

    python scripts/gen-icon-font.py
"""
import io
import json
import pathlib
import urllib.request

# 钉住提交:main 上的字形会改名 / 改形,不钉的话同一脚本两次跑出来不一样
COMMIT = "9cf8af0f95a555918a60b8147a2f33a6a1248442"
BASE = f"https://raw.githubusercontent.com/microsoft/fluentui-system-icons/{COMMIT}/fonts/"
OUT = pathlib.Path(__file__).resolve().parent.parent / "apps/windows/LinPlayer.Desktop/Assets/LinIcons.ttf"

# MDL2 码位 → Fluent 图标名(不带 ic_fluent_ 前缀)
MAP = {
    0xE70F: "edit_24_regular",               # 编辑信息
    0xE710: "add_24_regular",                # 添加服务器
    0xE711: "dismiss_24_regular",            # 屏蔽
    0xE713: "settings_24_regular",           # 设置
    0xE71D: "apps_24_regular",               # 插件
    0xE721: "search_24_regular",             # 搜索
    0xE722: "camera_24_regular",             # 截图
    0xE72A: "skip_forward_10_24_regular",    # 前进 10 秒
    0xE72B: "skip_back_10_24_regular",       # 后退 10 秒
    0xE72C: "arrow_clockwise_24_regular",    # 从头播放 / 重新登录
    0xE72E: "lock_closed_24_regular",        # 播放页 锁定界面
    0xE734: "star_24_regular",               # 收藏(空心)
    0xE735: "star_24_filled",                # 已收藏(实心)
    0xE739: "checkbox_unchecked_24_regular", # 未播放
    0xE73E: "checkmark_24_regular",          # 已播放
    0xE73F: "full_screen_minimize_24_regular",
    0xE740: "full_screen_maximize_24_regular",
    0xE74F: "speaker_mute_24_regular",
    0xE767: "speaker_2_24_regular",
    0xE785: "lock_open_24_regular",          # 播放页 解锁界面
    0xE768: "play_24_regular",
    0xE769: "pause_24_regular",
    0xE76B: "chevron_left_24_regular",
    0xE76C: "chevron_right_24_regular",
    0xE774: "globe_24_regular",              # 聚合视界 / 线路
    0xE787: "calendar_ltr_24_regular",       # 追剧日历
    # MDL2 的 E7F4 本身就是 Video,外形要和 Windows 侧对上;filmstrip 已被 E8B2 占了
    0xE7F4: "video_24_regular",              # 右键 转到剧集
    0xE80F: "home_24_regular",
    0xE81C: "history_24_regular",
    0xE890: "eye_24_regular",                # 右键 恢复显示(解除屏蔽)
    0xE893: "next_24_regular",               # 下一集
    0xE896: "arrow_download_24_regular",
    0xE8A9: "grid_24_regular",               # 影视目录
    0xE8B2: "filmstrip_24_regular",          # 下载页封面占位
    0xE8B7: "folder_24_regular",             # 文件浏览
    0xE8BB: "dismiss_24_regular",            # 标题栏 关闭
    0xE8CB: "arrow_sort_24_regular",         # 排行榜
    0xE8F1: "library_24_regular",            # 媒体库
    0xE8FD: "text_bullet_list_ltr_24_regular",  # 选集
    0xE91B: "image_24_regular",              # 编辑图标
    0xE921: "subtract_24_regular",           # 标题栏 最小化
    0xE922: "maximize_24_regular",           # 标题栏 最大化
    0xE946: "info_24_regular",               # 右键 查看详情
    0xE968: "server_24_regular",             # 服务器
    0xE9D9: "pulse_24_regular",              # 测线路
}


def fetch(name):
    with urllib.request.urlopen(BASE + name, timeout=120) as r:
        return r.read()


def main():
    # 只有生成要 fontTools;--check 在 CI 上跑,那边没装
    from fontTools import subset
    from fontTools.ttLib import TTFont, newTable
    from fontTools.ttLib.tables._c_m_a_p import cmap_format_4

    fonts = {}
    for style in ("Regular", "Filled"):
        font = TTFont(io.BytesIO(fetch(f"FluentSystemIcons-{style}.ttf")))
        names = json.loads(fetch(f"FluentSystemIcons-{style}.json"))
        fonts[style.lower()] = (font, names)

    order = [".notdef"]
    picked = {}  # 码位 → 目标字体里的字形名
    wanted = {}  # 风格 → {源码位: 新字形名}
    for cp, icon in MAP.items():
        style = "filled" if icon.endswith("_filled") else "regular"
        src = fonts[style][1].get("ic_fluent_" + icon)
        if src is None:
            raise SystemExit(f"Fluent 里没有 {icon}(给 U+{cp:04X} 用)—— 换个名字")
        gname = icon
        picked[cp] = gname
        wanted.setdefault(style, {})[src] = gname

    out = TTFont(io.BytesIO(fetch("FluentSystemIcons-Regular.ttf")))
    opts = subset.Options()
    opts.notdef_outline = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=list(wanted.get("regular", {})))
    sub.subset(out)

    glyf, hmtx = {}, {}
    for style, srcs in wanted.items():
        font, _ = fonts[style]
        cmap = font.getBestCmap()
        for src_cp, gname in srcs.items():
            g = font["glyf"][cmap[src_cp]]
            g.expand(font["glyf"])
            glyf[gname] = g
            hmtx[gname] = font["hmtx"][cmap[src_cp]]
    src_font = fonts["regular"][0]
    notdef = src_font.getGlyphOrder()[0]
    glyf[".notdef"] = src_font["glyf"][notdef]
    hmtx[".notdef"] = src_font["hmtx"][notdef]
    order += sorted(set(picked.values()))

    out.setGlyphOrder(order)
    out["glyf"].glyphOrder = order
    out["glyf"].glyphs = {n: glyf[n] for n in order}
    out["hmtx"].metrics = {n: hmtx[n] for n in order}

    table = newTable("cmap")
    table.tableVersion = 0
    table.tables = []
    for pid, eid in ((0, 3), (3, 1)):
        st = cmap_format_4(4)
        st.platformID, st.platEncID, st.language = pid, eid, 0
        st.cmap = dict(picked)
        table.tables.append(st)
    out["cmap"] = table
    for key in ("GSUB", "GPOS", "GDEF"):
        if key in out:
            del out[key]
    out["post"].formatType = 3.0  # 不存字形名,省体积

    name = out["name"]
    for nid, value in ((1, "LinIcons"), (2, "Regular"), (4, "LinIcons"), (6, "LinIcons"),
                       (16, "LinIcons"), (17, "Regular")):
        name.setName(value, nid, 3, 1, 0x409)
        name.setName(value, nid, 1, 0, 0)
    out.save(OUT)
    print(f"{OUT}  {OUT.stat().st_size} 字节,{len(picked)} 个码位")


def check():
    """界面代码里用到的码位必须都在 MAP 里。不联网,给 check-style.sh 调。"""
    import re
    import sys
    app = OUT.parent.parent
    used = set()
    for p in list(app.rglob("*.cs")) + list(app.rglob("*.axaml")):
        if "obj" in p.parts or "bin" in p.parts:
            continue
        text = p.read_text(encoding="utf-8-sig")
        used |= {int(h, 16) for h in re.findall(r"(?:\\u|&#x)([Ee][0-9A-Fa-f]{3})", text)}
        used |= {ord(c) for c in text if 0xE000 <= ord(c) <= 0xF8FF}
    missing = sorted(used - set(MAP))
    for cp in missing:
        print(f"  U+{cp:04X} 在界面里用了,LinIcons 里没有 —— Linux 上是豆腐块。补进 scripts/gen-icon-font.py 的 MAP 再跑一次")
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    import sys
    check() if "--check" in sys.argv else main()
